package probe;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单源抓取 + 封禁/WAF 识别 + 字段解析。探针判据的核心（06 W0.4）。
 *
 * <p>修正的三处必然性缺陷：
 * <ol>
 *   <li>封禁识别不再用裸 {@code captcha}：东财股吧正常页恒含 {@code em_capt.js} SDK 路径，
 *       旧正则对每次成功响应都命中 → 东财永远过不了线。改高特异性特征（状态码 / WAF / JS 挑战）。</li>
 *   <li>识别阿里云 WAF / JS 挑战页（{@code _waf_} / {@code renderData}）：雪球无 Cookie 返回挑战页，
 *       HTTP 200 但非真数据，旧探针计 success → 违反 F-CRAWL-07。现计失败。</li>
 *   <li>字段完整率是真实解析（舆情四字段 / 行情数值），不再是"标的代码出现在 URL 里"的自证。</li>
 * </ol>
 */
public class SourceProbe {

    /** 封禁/反爬/WAF 挑战特征：只用高特异性串，避免误伤正常页内的 SDK 路径。 */
    private static final Pattern BAN = Pattern.compile(
            "_waf_|aliyun_waf|renderData\\\"|cf-browser-verification|challenge-platform"
                    + "|滑动验证|安全验证|访问验证|请输入验证码|输入验证码|进行验证|验证一下"
                    + "|访问过于频繁|操作过于频繁|请求过于频繁|频率过高|请稍后再试"
                    + "|For input string|403 Forbidden|Forbidden\\s*\\|\\s*nginx"
                    + "|Access Denied|拒绝访问|拦截|您的访问被封|blocked|Too Many Requests",
            Pattern.CASE_INSENSITIVE);

    /** 明确的挑战/拦截 HTTP 状态。 */
    private static boolean banStatus(int code) {
        return code == 403 || code == 405 || code == 406 || code == 419
                || code == 429 || code == 451 || code == 521 || code == 503;
    }

    private final Def def;
    private final HttpClient client;

    public SourceProbe(Def def) {
        this.def = def;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)   // 东财 push2 对 h2c 升级处理不当，强制 HTTP/1.1（避免 header parser received no bytes）
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Def def() {
        return def;
    }

    /** 标的代码在不同源的书写形式。 */
    public enum CodeStyle { PLAIN, UPPER, LOWER, SECID }

    /** 按源要求把 6 位代码格式化（沪/深前缀、东财 secid 市场位等）。 */
    public static String fmt(String sym, CodeStyle style) {
        boolean sz = sym.startsWith("0") || sym.startsWith("3");
        return switch (style) {
            case PLAIN -> sym;
            case UPPER -> (sz ? "SZ" : "SH") + sym;
            case LOWER -> (sz ? "sz" : "sh") + sym;
            case SECID -> (sz ? "0." : "1.") + sym;
        };
    }

    /** 抓取一个标的并给出分类结论（入参为 6 位原始代码，内部按 CodeStyle 格式化）。 */
    public Outcome probe(String symbol) {
        long t0 = System.nanoTime();
        int http = -1;
        String body = "";
        String err = null;
        String code = fmt(symbol, def.codeStyle);
        try {
            String url = def.url.replace("%s", code);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .header("User-Agent", def.ua);
            def.headers.forEach(b::header);
            if (def.cookie != null && !def.cookie.isBlank()) {
                b.header("Cookie", def.cookie);
            }
            HttpResponse<byte[]> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            http = resp.statusCode();
            body = new String(resp.body(), def.charset);
        } catch (Exception e) {
            err = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        long latencyMs = (System.nanoTime() - t0) / 1_000_000;

        // 未注入 Cookie 且该源要求 Cookie：判失败（不得用挑战页冒充成功），单列原因。
        if (def.requiresCookie && (def.cookie == null || def.cookie.isBlank())) {
            return new Outcome(http, latencyMs, false, false, null, "NO_COOKIE");
        }
        boolean ban = banStatus(http) || (body != null && BAN.matcher(head(body)).find());
        if (ban) {
            return new Outcome(http, latencyMs, true, false, null, "BAN_OR_WAF_CHALLENGE");
        }
        if (http != 200) {
            return new Outcome(http, latencyMs, false, false, null, err != null ? err : "HTTP_" + http);
        }

        // 字段解析
        boolean fieldOk = true;
        for (Pattern p : def.fieldPatterns) {
            if (!p.matcher(body).find()) {
                fieldOk = false;
                break;
            }
        }
        // 行情额外要求：价格可解析且 > 0（F-CRAWL-07：数值解析才算成功）
        Double price = null;
        if ("QUOTE".equals(def.type)) {
            price = parsePrice(body);
            if (price == null || price <= 0) {
                fieldOk = false;
                return new Outcome(http, latencyMs, false, false, null, "PRICE_PARSE_FAIL");
            }
        }
        // 舆情额外要求：标的代码需在正文出现（排除返回通用兜底页）
        if ("GUBA".equals(def.type) && !body.contains(code)) {
            fieldOk = false;
            return new Outcome(http, latencyMs, false, false, null, "CODE_NOT_IN_BODY");
        }
        return new Outcome(http, latencyMs, false, true, price, fieldOk ? "OK" : "FIELD_INCOMPLETE");
    }

    private Double parsePrice(String body) {
        if (def.pricePattern == null) return null;
        Matcher m = def.pricePattern.matcher(body);
        if (!m.find()) return null;
        try {
            double v = Double.parseDouble(m.group(1));
            return def.priceScale > 0 ? v / def.priceScale : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String head(String body) {
        int n = Math.min(body.length(), 20000);
        return body.substring(0, n);
    }

    /** 抓取结果（success=真成功；fieldComplete=字段解析齐备）。 */
    public record Outcome(int http, long latencyMs, boolean ban, boolean success,
                          Double price, String reason) {
    }

    /** 源定义。 */
    public static class Def {
        String name;
        String type;               // GUBA | QUOTE
        String url;                // 含 %s 占位标的代码
        CodeStyle codeStyle = CodeStyle.PLAIN;
        String ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 aquila-probe/0.1";
        Charset charset = StandardCharsets.UTF_8;
        boolean requiresCookie = false;
        String cookie = null;
        final Map<String, String> headers = new LinkedHashMap<>();
        final List<Pattern> fieldPatterns = new java.util.ArrayList<>();
        Pattern pricePattern;      // QUOTE：捕获组 1 = 价格
        double priceScale = 0;     // >0 时 price = 捕获值 / priceScale（如东财 f43=分×... 用 100）
    }
}
