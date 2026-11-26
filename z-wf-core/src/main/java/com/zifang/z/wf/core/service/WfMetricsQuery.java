package com.zifang.z.wf.core.service;

import com.zifang.z.wf.core.model.WfMetric;

/**
 * 指标查询条件（第 44 轮）。
 *
 * <p>对照 Camunda 的 {@code MetricsQuery}：那边是
 * {@code metric} / {@code startDate} / {@code endDate} / {@code registerDate} 一组，
 * 本类保留其中<b>真正改变读数</b>的三项，其余刻意不给。
 *
 * @author zifang
 */
public class WfMetricsQuery {

    private WfMetric metric;

    /** 窗口下界（含），按该指标的<b>起点时间</b>比（实例是 startTime，任务是 createTime）。 */
    private java.util.Date startDate;

    /** 窗口上界（含）。 */
    private java.util.Date endDate;

    /** 限定某个流程定义；为空 = 全部。 */
    private String definitionKey;

    public WfMetricsQuery() {
    }

    public WfMetricsQuery(WfMetric metric) {
        this.metric = metric;
    }

    public WfMetric getMetric() {
        return metric;
    }

    public WfMetricsQuery setMetric(WfMetric metric) {
        this.metric = metric;
        return this;
    }

    public java.util.Date getStartDate() {
        return startDate;
    }

    public WfMetricsQuery setStartDate(java.util.Date startDate) {
        this.startDate = startDate;
        return this;
    }

    public java.util.Date getEndDate() {
        return endDate;
    }

    public WfMetricsQuery setEndDate(java.util.Date endDate) {
        this.endDate = endDate;
        return this;
    }

    public String getDefinitionKey() {
        return definitionKey;
    }

    public WfMetricsQuery setDefinitionKey(String definitionKey) {
        this.definitionKey = definitionKey == null || definitionKey.trim().isEmpty()
                ? null : definitionKey.trim();
        return this;
    }

    /**
     * 条件自相矛盾时直接拒绝，不让它变成一个零结果。
     *
     * <p>与 {@code WfProcessInstanceQuery#assertConsistent()} 同一个理由：
     * 矛盾条件叠上去只会得到一个空表，而调用方分不清是"这段时间没有单"
     * 还是"窗口写反了" —— 而指标是要摆在看板上的，
     * 看板上那个 0 会被当成业务结论。
     */
    public void assertConsistent() {
        if (metric == null) {
            throw new IllegalArgumentException("指标查询必须给出 metric：要算哪一类指标。");
        }
        if (startDate != null && endDate != null && startDate.after(endDate)) {
            throw new IllegalArgumentException(
                    "指标查询条件矛盾：startDate(" + startDate + ") 晚于 endDate(" + endDate + ")。");
        }
    }
}