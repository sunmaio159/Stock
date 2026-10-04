package probe;

import java.util.ArrayList;
import java.util.List;

/**
 * 单源探针指标 + 逐轮明细（06 W0.4：产出须能画出"成功率曲线 / 延迟分布"）。
 * 纯探针模型，不引业务依赖（F-CRAWL-01）。
 */
public class ProbeResult {
    public final String source;
    public final String type;      // GUBA | QUOTE
    public int rounds;
    public int success;            // 通过状态码 + 非封禁/WAF 挑战
    public int banned;             // 命中封禁/WAF/JS 挑战特征
    public int fieldComplete;      // 舆情：四字段解析齐备；行情：数值字段解析齐备
    public final List<Long> latencies = new ArrayList<>();
    public final List<RoundRow> detail = new ArrayList<>();
    /** 行情按标的分桶的价格样本（交叉偏差必须同标的对比，不能跨价位平均）。 */
    public final java.util.Map<String, List<Double>> priceByCode = new java.util.LinkedHashMap<>();

    public void addPrice(String code, double p) {
        priceByCode.computeIfAbsent(code, k -> new ArrayList<>()).add(p);
    }

    public Double avgPrice(String code) {
        List<Double> v = priceByCode.get(code);
        return (v == null || v.isEmpty()) ? null
                : v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    public ProbeResult(String source, String type) {
        this.source = source;
        this.type = type;
    }

    public double successRate() {
        return rounds == 0 ? 0 : (double) success / rounds;
    }

    public double fieldCompleteRate() {
        return rounds == 0 ? 0 : (double) fieldComplete / rounds;
    }

    /** 采集成功判据（F-CRAWL-07：HTTP 200 不得单独作为成功判据）。 */
    public boolean http200OnlyIsEnough() {
        return false;
    }

    public long p95Latency() {
        if (latencies.isEmpty()) return -1;
        List<Long> s = new ArrayList<>(latencies);
        s.sort(Long::compareTo);
        int idx = (int) Math.ceil(0.95 * s.size()) - 1;
        return s.get(Math.max(0, Math.min(idx, s.size() - 1)));
    }

    public long maxLatency() {
        return latencies.stream().mapToLong(Long::longValue).max().orElse(-1);
    }

    /** 逐轮明细行（落 CSV，供曲线/分布还原）。 */
    public record RoundRow(int round, long tsEpochMs, String code, int http, boolean ban,
                           boolean success, boolean fieldComplete, long latencyMs, String reason) {
    }

    /** 记录一轮结果，并同步累加计数。 */
    public void addRound(RoundRow r) {
        rounds++;
        detail.add(r);
        latencies.add(r.latencyMs());
        if (r.ban()) banned++;
        if (r.success()) success++;
        if (r.fieldComplete()) fieldComplete++;
    }
}
