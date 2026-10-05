package com.zifang.z.wf.core.model;

/**
 * job 的种类。
 *
 * <p>三种边界事件共用 {@link WfJob} 这一个载体，但触发方式完全不同：
 * <ul>
 *   <li>{@link #TIMER} —— 到点即响，由扫描器捞</li>
 *   <li>{@link #MESSAGE} —— 特定流程实例的特定消息到达时响</li>
 *   <li>{@link #SIGNAL} —— 广播信号时响，可能一次命中多个实例</li>
 * </ul>
 *
 * <p>为什么不用"duedate 为 null 就不是定时器"来区分：那是个隐式约定，
 * 任何人写 {@code saveJob} 时都可能漏掉，于是消息订阅被扫描器当成到期 job 消费掉 ——
 * 表现是"还没发消息，流程自己往前走了"，极难定位。所以这里给一个显式字段。
 *
 * @author zifang
 */
public enum WfJobType {

    /** 定时器边界：按 duedate 到期。 */
    TIMER("定时器"),

    /** 消息边界：订阅某个消息名，消息到达时触发。 */
    MESSAGE("消息"),

    /** 信号边界：订阅某个信号名，广播时触发。 */
    SIGNAL("信号");

    private final String label;

    WfJobType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 写进活动历史 outcome 的前缀（如 {@code timer:}）。
     *
     * <p>带前缀是为了让"这次节点访问是被什么打断的"从轨迹上就能读出来 ——
     * 事后追责时不必再去翻评论。定时器这一支的 {@code timer:} 是既有契约。
     */
    public String outcomePrefix() {
        return name().toLowerCase() + ":";
    }
}
