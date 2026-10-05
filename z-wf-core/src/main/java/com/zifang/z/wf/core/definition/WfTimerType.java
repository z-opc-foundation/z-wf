package com.zifang.z.wf.core.definition;

/**
 * 定时器边界的触发类型，对应 BPMN {@code timerEventDefinition} 的三个子元素。
 *
 * <p>三者互斥：一个 {@code boundaryEvent} 只会挂一种 {@code eventDefinition}。
 *
 * @author zifang
 */
public enum WfTimerType {

    /**
     * 相对时长：{@code timeDuration}，如 {@code PT5M}。
     *
     * <p>从<b>进入宿主节点那一刻</b>起算，而不是从流程启动起算 ——
     * 超时提醒关心的是"这单在这一步停了多久"。
     */
    DURATION("timeDuration"),

    /**
     * 绝对时刻：{@code timeDate}，如 {@code 2026-12-31T18:00:00Z}。
     *
     * <p>用于"到点自动提醒"这类与单据无关的固定时点场景。
     */
    DATE("timeDate"),

    /**
     * 循环周期：{@code timeCycle}，如 {@code R3/PT10M}。
     *
     * <p><b>本实现刻意不支持</b>。循环定时器在流程引擎里是个陷阱：
     * 它需要独立的"下一次触发时间"状态、需要在实例终止时清理、
     * 还会和会签、补偿事务纠缠。本引擎没有那套状态，
     * 解析出来只为在部署期给出可操作的报错。
     */
    CYCLE("timeCycle");

    private final String elementName;

    WfTimerType(String elementName) {
        this.elementName = elementName;
    }

    public String getElementName() {
        return elementName;
    }
}
