package probe;

import probe.SourceProbe.CodeStyle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 五源长样本探针（06 W0.4 硬门禁脚本）。纯 JDK，不依赖主工程（F-CRAWL-01）。
 *
 * <p>产出：① 汇总 txt；② 每源逐轮明细 CSV（可还原"成功率曲线 / 延迟分布"）；
 * ③ 行情与东财参考源的交叉偏差；④ 与 06 判据对齐的 GO 结论。
 *
 * <p>正式门禁须跑满 3 日：{@code java -cp aquila-probe/out probe.Probe 144 1800}
 * （144 轮 = 每 30min × 72h）；雪球源须注入 Cookie：{@code set AQUILA_XQ_COOKIE="..."}。
 * 用法：{@code java -cp out probe.Probe [轮次=1] [间隔秒=0] [输出目录=reports]}
 */
public final class Probe {

    /** 舆情三源达线阈值（06 W0.4：成功率 & 四字段完整率 ≥ 98%）。 */
    private static final double GUBA_THR = 0.98;
    /** 行情源成功率阈值（06 W0.4：≥ 99.5%）。 */
    private static final double QUOTE_THR = 0.995;
    /** 行情与东财参考源交叉偏差阈值（06 W0.4：≤ 0.5%）。 */
    private static final double DEVIATION_THR = 0.005;

    /** 样本：高/中/低活跃混合（06 W0.4 要求覆盖不同活跃度标的）。 */
    private static final String[] SYMBOLS = {"600519", "000001", "300750"};

    public static void main(String[] args) throws Exception {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        long intervalSec = args.length > 1 ? Long.parseLong(args[1]) : 0;
        Path outDir = Path.of(args.length > 2 ? args[2] : "reports");
        Files.createDirectories(outDir);

        String xqCookie = System.getenv("AQUILA_XQ_COOKIE");

        List<SourceProbe> probes = List.of(
                new SourceProbe(eastmoneyGuba()),
                new SourceProbe(xueqiuGuba(xqCookie)),
                new SourceProbe(thsGuba()),
                new SourceProbe(tencentQuote()),
                new SourceProbe(sinaQuote()),
                new SourceProbe(eastmoneyQuoteRef())   // 行情交叉偏差参考源
        );
        List<ProbeResult> results = new ArrayList<>();
        for (SourceProbe p : probes) {
            results.add(new ProbeResult(p.def().name, p.def().type));
        }

        System.out.printf("探针启动：轮次=%d 间隔=%ds 样本=%s%n", rounds, intervalSec, String.join(",", SYMBOLS));

        for (int r = 1; r <= rounds; r++) {
            long t0 = System.currentTimeMillis();
            for (int i = 0; i < probes.size(); i++) {
                SourceProbe sp = probes.get(i);
                ProbeResult acc = results.get(i);
                // 舆情源抓全部样本；行情源也逐标的抓（观察字段稳定性）
                for (String sym : SYMBOLS) {
                    SourceProbe.Outcome o = sp.probe(sym);
                    acc.addRound(new ProbeResult.RoundRow(
                            r, System.currentTimeMillis(), sym, o.http(), o.ban(),
                            o.success(), o.success() && fieldOk(o), o.latencyMs(), o.reason()));
                    if (o.price() != null) {
                        acc.addPrice(sym, o.price());
                    }
                }
            }
            System.out.printf("  round %d/%d done (%dms)%n", r, rounds, System.currentTimeMillis() - t0);
            if (r < rounds && intervalSec > 0) {
                Thread.sleep(intervalSec * 1000);
            }
        }

        String date = LocalDate.now().toString();
        boolean intraday = isTradingSessionNow();
        Path csvDir = outDir;
        writeDetailCsv(csvDir, date, results);
        String summary = buildSummary(date, rounds, results, intraday);
        Path txt = csvDir.resolve("probe_report_" + date + ".txt");
        Files.writeString(txt, summary, StandardCharsets.UTF_8);
        System.out.println(summary);
        System.out.println("明细 CSV 目录：" + csvDir.toAbsolutePath());
        System.out.println("汇总报告：" + txt.toAbsolutePath());
    }

    private static boolean fieldOk(SourceProbe.Outcome o) {
        return "OK".equals(o.reason());
    }

    /**
     * 是否 A 股盘中时段（Asia/Shanghai 工作日 09:15–15:15，含缓冲）。
     * 仅用于区分“盘后三源同一收盘缓存→偏差不可信”与“盘中→偏差可验证”；不判节假日。
     */
    static boolean isTradingSessionNow() {
        java.time.ZoneId z = java.time.ZoneId.of("Asia/Shanghai");
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(z);
        java.time.DayOfWeek dow = now.getDayOfWeek();
        if (dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY) {
            return false;
        }
        java.time.LocalTime t = now.toLocalTime();
        return !t.isBefore(java.time.LocalTime.of(9, 15)) && !t.isAfter(java.time.LocalTime.of(15, 15));
    }

    private static String buildSummary(String date, int rounds, List<ProbeResult> rs, boolean intraday) {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 五源长样本探针报告 ").append(date).append(" ==========\n");
        sb.append("轮次=").append(rounds).append("  样本=").append(String.join(",", SYMBOLS)).append("\n");
        sb.append("口径：成功=HTTP200 且非封禁/WAF 挑战且字段可解析（F-CRAWL-07，不以 200 单独判成功）\n");
        sb.append("取样时段：").append(intraday ? "盘中（偏差检验有效）" : "非盘中/盘后（三源同一收盘缓存，交叉偏差不可信）").append("\n\n");

        List<ProbeResult> guba = rs.stream().filter(x -> "GUBA".equals(x.type)).toList();
        List<ProbeResult> quote = rs.stream().filter(x -> "QUOTE".equals(x.type)).toList();

        sb.append("---- 舆情源（阈值 成功率&字段完整率 ≥ ").append(pct(GUBA_THR)).append("）----\n");
        for (ProbeResult g : guba) {
            sb.append(line(g)).append("  ").append(gubaPass(g) ? "[达线]" : "[未达线]").append("\n");
        }

        sb.append("\n---- 行情源（阈值 成功率 ≥ ").append(pct(QUOTE_THR)).append("，东财 push2 为偏差参考源、不计门禁）----\n");
        ProbeResult ref = quote.stream().filter(q -> "EM_QUOTE_REF".equals(q.source)).findFirst().orElse(null);
        boolean refAvailable = ref != null && ref.successRate() > 0;
        for (ProbeResult q : quote) {
            if ("EM_QUOTE_REF".equals(q.source)) {
                continue; // 参考源单独渲染，不与门禁源混排
            }
            String dev;
            if (!refAvailable) {
                dev = "  偏差=不可计算(参考源不可用·可能限流/临时故障)";
            } else {
                Double d = maxDeviation(ref, q);
                if (d == null) {
                    dev = "  偏差=N/A(参考源缺样)";
                } else if (!intraday) {
                    dev = String.format("  最大交叉偏差=%.3f%% [未检验·非盘中取样·须3日盘中跑]", d * 100);
                } else {
                    dev = String.format("  最大交叉偏差=%.3f%% %s", d * 100, d <= DEVIATION_THR ? "[达标]" : "[超阈]");
                }
            }
            sb.append(line(q)).append("  ").append(quotePass(q) ? "[达线]" : "[未达线]").append(dev).append("\n");
        }
        if (ref != null) {
            sb.append(String.format("%s(参考源·不计门禁)  成功=%.2f%%  %s%n",
                    ref.source, ref.successRate() * 100,
                    refAvailable ? "可用" : "不可用(可能限流/临时故障)→偏差标注为 N/A"));
        }

        sb.append("\n---- GO 结论（对齐 06 W0.4）----\n");
        sb.append(verdict(rs)).append("\n");
        return sb.toString();
    }

    /**
     * 06 W0.4 分支钥匙是"舆情主源（东财）"是否达线，而非达线源个数：
     * - 东财达线 + 行情至少一源过线 → GO（东财=主源）；舆情仅部分源可用只登范围风险、不扣 GO。
     * - 东财不过但雪球/同花顺过 → 降级方案 GO（主源切换）。
     * - 舆情三源全不过 → 停线重评。
     */
    private static String verdict(List<ProbeResult> rs) {
        ProbeResult em = find(rs, "EM_GUBA");
        ProbeResult xq = find(rs, "XUEQIU");
        ProbeResult ths = find(rs, "THS_GUBA");
        boolean emPass = em != null && gubaPass(em);
        boolean xqPass = xq != null && gubaPass(xq);
        boolean thsPass = ths != null && gubaPass(ths);
        int gubaPassCnt = (emPass ? 1 : 0) + (xqPass ? 1 : 0) + (thsPass ? 1 : 0);
        boolean quoteOk = rs.stream()
                .filter(q -> "QUOTE".equals(q.type) && !"EM_QUOTE_REF".equals(q.source))
                .anyMatch(Probe::quotePass);

        if (gubaPassCnt == 0) {
            return "★ 停线重评：舆情三源均未达线，直采前提不成立（06 W0.4）。";
        }
        if (emPass) {
            StringBuilder v = new StringBuilder("★ GO：舆情主源东财达线");
            v.append(quoteOk ? "，行情至少一源过线。" : "；【风险】行情源均未过线，W1 前须定行情降级方案（不阻塞舆情 GO，须登记）。");
            if (gubaPassCnt < 3) {
                v.append("范围守门·砍单顺序 1 登记：舆情仅 ").append(gubaPassCnt)
                        .append("/3 源可用，雪球/同花顺降为东财单源，风险照登、不扣 GO。");
            } else {
                v.append("舆情三源全部达线，直采。");
            }
            return v.toString();
        }
        String alt = (xqPass ? "雪球" : "同花顺");
        return "★ 降级方案 GO：东财未过线，主源切换为 " + alt + "（06：东财不过但雪球/同花顺过→降级）。W1 前须定代理池/协议方案。";
    }

    private static ProbeResult find(List<ProbeResult> rs, String source) {
        return rs.stream().filter(x -> x.source.equals(source)).findFirst().orElse(null);
    }

    static boolean gubaPass(ProbeResult g) {
        return g.successRate() >= GUBA_THR && g.fieldCompleteRate() >= GUBA_THR;
    }

    static boolean quotePass(ProbeResult q) {
        return q.successRate() >= QUOTE_THR;
    }

    static Double maxDeviation(ProbeResult ref, ProbeResult q) {
        // 交叉偏差必须同标的对比：逐个共有 code 求均值相对偏差，取最大值。
        Double max = null;
        for (String code : q.priceByCode.keySet()) {
            Double a = ref.avgPrice(code);
            Double b = q.avgPrice(code);
            if (a == null || b == null || a == 0) continue;
            double dev = Math.abs(a - b) / a;
            if (max == null || dev > max) max = dev;
        }
        return max;
    }

    private static String line(ProbeResult p) {
        return String.format("%-14s 成功=%.2f%% 字段完整=%.2f%% P95=%dms max=%dms 封禁/WAF=%d 轮=%d",
                p.source, p.successRate() * 100, p.fieldCompleteRate() * 100,
                p.p95Latency(), p.maxLatency(), p.banned, p.rounds);
    }

    private static void writeDetailCsv(Path dir, String date, List<ProbeResult> rs) throws IOException {
        for (ProbeResult r : rs) {
            StringBuilder csv = new StringBuilder("round,ts_epoch_ms,code,http,ban,success,field_complete,latency_ms,reason\n");
            for (ProbeResult.RoundRow row : r.detail) {
                csv.append(row.round()).append(',').append(row.tsEpochMs()).append(',').append(row.code())
                        .append(',').append(row.http()).append(',').append(row.ban()).append(',')
                        .append(row.success()).append(',').append(row.fieldComplete()).append(',')
                        .append(row.latencyMs()).append(',').append(row.reason()).append('\n');
            }
            Files.writeString(dir.resolve("probe_detail_" + r.source + "_" + date + ".csv"),
                    csv.toString(), StandardCharsets.UTF_8);
        }
    }

    private static String pct(double v) {
        return String.format("%.1f%%", v * 100);
    }

    // ---- 源定义（URL/字符集/字段解析正则/价格解析，均据实测响应结构）----

    private static SourceProbe.Def eastmoneyGuba() {
        SourceProbe.Def d = base("EM_GUBA", "GUBA", CodeStyle.PLAIN);
        d.url = "https://guba.eastmoney.com/list,%s.html";
        d.charset = gbk();
        // 四字段：标题 / 作者 / 更新时间 / 阅读(互动)
        d.fieldPatterns.add(Pattern.compile("<div class=\"title\"><a[^>]*>"));
        d.fieldPatterns.add(Pattern.compile("class=\"author\"><a"));
        d.fieldPatterns.add(Pattern.compile("class=\"update\">[\\d:\\- ]+|分钟前|小时前|天前"));
        d.fieldPatterns.add(Pattern.compile("class=\"read\">\\d+"));
        return d;
    }

    private static SourceProbe.Def xueqiuGuba(String cookie) {
        SourceProbe.Def d = base("XUEQIU", "GUBA", CodeStyle.UPPER);
        d.url = "https://xueqiu.com/S/%s";
        d.requiresCookie = true;
        d.cookie = cookie;
        // 有 Cookie 时的真页面判据（时间/讨论标记）；无 Cookie 直接判 NO_COOKIE，不会假成功
        d.fieldPatterns.add(Pattern.compile("雪球|xueqiu"));
        d.fieldPatterns.add(Pattern.compile("分钟前|小时前|天前|\\d{2}-\\d{2} \\d{2}:\\d{2}|帖子|讨论"));
        return d;
    }

    private static SourceProbe.Def thsGuba() {
        SourceProbe.Def d = base("THS_GUBA", "GUBA", CodeStyle.PLAIN);
        d.url = "https://t.10jqka.com.cn/guba/%s/";
        d.charset = gbk();
        d.fieldPatterns.add(Pattern.compile("class=\"[^\"]*(title|item)[^\"]*\""));
        d.fieldPatterns.add(Pattern.compile("class=\"[^\"]*(user|name|author)[^\"]*\""));
        d.fieldPatterns.add(Pattern.compile("分钟前|小时前|天前|\\d{2}-\\d{2} \\d{2}:\\d{2}|\\d{4}-\\d{2}-\\d{2}"));
        return d;
    }

    private static SourceProbe.Def tencentQuote() {
        SourceProbe.Def d = base("TENCENT_QUOTE", "QUOTE", CodeStyle.LOWER);
        d.url = "https://qt.gtimg.cn/q=%s";
        d.charset = gbk();
        d.headers.put("Referer", "https://gu.qq.com/");
        d.pricePattern = Pattern.compile("\"[0-9]{1,2}~[^~]+~\\d{6}~([0-9.]+)~");
        d.fieldPatterns.add(Pattern.compile("\\d{14}"));   // 行情时间戳
        return d;
    }

    private static SourceProbe.Def sinaQuote() {
        SourceProbe.Def d = base("SINA_QUOTE", "QUOTE", CodeStyle.LOWER);
        d.url = "https://hq.sinajs.cn/list=%s";
        d.charset = gbk();
        d.headers.put("Referer", "https://finance.sina.com.cn");
        d.pricePattern = Pattern.compile("hq_str_\\w+=\"[^,]*,[0-9.]+,[0-9.]+,([0-9.]+),");
        d.fieldPatterns.add(Pattern.compile("\\d{4}-\\d{2}-\\d{2}"));   // 行情日期
        return d;
    }

    private static SourceProbe.Def eastmoneyQuoteRef() {
        SourceProbe.Def d = base("EM_QUOTE_REF", "QUOTE", CodeStyle.SECID);
        d.url = "https://push2.eastmoney.com/api/qt/stock/get?secid=%s&fields=f43,f57,f58";
        d.pricePattern = Pattern.compile("\"f43\":([0-9]+)");
        d.priceScale = 100;   // f43 = 价格 ×100
        d.fieldPatterns.add(Pattern.compile("\"f57\":\"\\d{6}\""));
        return d;
    }

    private static SourceProbe.Def base(String name, String type, SourceProbe.CodeStyle style) {
        SourceProbe.Def d = new SourceProbe.Def();
        d.name = name;
        d.type = type;
        d.codeStyle = style;
        return d;
    }

    private static java.nio.charset.Charset gbk() {
        try {
            return java.nio.charset.Charset.forName("GBK");
        } catch (Exception e) {
            return StandardCharsets.ISO_8859_1;
        }
    }
}
