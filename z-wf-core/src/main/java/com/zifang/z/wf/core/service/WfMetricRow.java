package com.zifang.z.wf.core.service;

/**
 * 一行指标结果（第 44 轮）。
 *
 * <p><b>刻意让一类指标的所有分组装在同一个列表里</b>，
 * 而不是「Map&lt;分组, 值&gt; + 另一个 Map 存时长」：
 * 后者在前端要拼两个对象，而拼接时漏掉其中一个不会报错，
 * 看板上就出现「有分布没有均值」或反过来 —— 那种半套指标比没有更难解释。
 *
 * @author zifang
 */
public class WfMetricRow {

    /**
     * 汇总行的 {@link #name}。
     *
     * <p>直方图指标除各桶之外<b>额外给一行这个</b>，带总数与最值。
     * 用固定常量而不是空串：空串在 JSON 里会与"真的没有值"分不开。
     */
    public static final String ALL = "__ALL__";

    /** 分组键：定义 key / 办理人 / 直方图区间标签；汇总行为 {@link #ALL}。 */
    private String name;

    private long count;

    /** 总时长（毫秒）；非时长类指标为 {@code null}，<b>不是 0</b>。 */
    private Long sumMillis;

    private Long avgMillis;

    private Long minMillis;

    private Long maxMillis;

    public WfMetricRow() {
    }

    public WfMetricRow(String name, long count) {
        this.name = name;
        this.count = count;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public Long getSumMillis() {
        return sumMillis;
    }

    public void setSumMillis(Long sumMillis) {
        this.sumMillis = sumMillis;
    }

    public Long getAvgMillis() {
        return avgMillis;
    }

    public void setAvgMillis(Long avgMillis) {
        this.avgMillis = avgMillis;
    }

    public Long getMinMillis() {
        return minMillis;
    }

    public void setMinMillis(Long minMillis) {
        this.minMillis = minMillis;
    }

    public Long getMaxMillis() {
        return maxMillis;
    }

    public void setMaxMillis(Long maxMillis) {
        this.maxMillis = maxMillis;
    }

    /** 时长类指标才有这几个值。 */
    public boolean hasDuration() {
        return sumMillis != null;
    }

    @Override
    public String toString() {
        return "WfMetricRow{name=" + name + ", count=" + count
                + (sumMillis == null ? "" : ", avg=" + avgMillis + "ms")
                + "}";
    }
}