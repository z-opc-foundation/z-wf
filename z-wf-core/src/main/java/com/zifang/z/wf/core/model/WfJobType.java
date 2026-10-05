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
    SIGNAL("信号"),

    /**
     * 外部任务：停在某个 topic 上等外部 worker 来取。
     *
     * <p>与前三种的根本区别：它<b>不由时间或消息触发</b>，而是等着被人来领。
     * 没有它，流程要调外部系统就只能同步阻塞在引擎线程里 ——
     * 外部系统慢会把审批线程池拖住，外部系统挂了整条流程一起卡死。
     */
    EXTERNAL("外部任务"),

    /**
     * 异步前置：token 到达节点后、执行它之前挂起。
     *
     * <p>job 执行时<b>进入</b>该节点，把它的行为真正跑一遍（建任务 / 跑 delegate）。
     * 与 {@link #ASYNC_AFTER} 分成两个类型而不是共用一个，是因为两者的续跑动作
     * 方向相反：共用一个的话，执行器就必须去查"这到底是前置还是后置"，
     * 而那个信息本来就该由 job 自己携带。
     */
    ASYNC_BEFORE("异步前置"),

    /**
     * 异步后置：节点已执行完、离开之前挂起。
     *
     * <p>job 执行时<b>离开</b>该节点、沿出线前进 —— 节点本身不会再跑一遍。
     */
    ASYNC_AFTER("异步后置");

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
