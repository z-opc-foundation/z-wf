package com.zifang.z.wf.core.model;

import java.io.Serializable;
import java.util.Date;

/**
 * Job —— 引擎里"到点要做的一件事"。
 *
 * <p>目前唯一的种类是<b>定时器边界事件</b>：token 进入带定时器边界的节点时建一个 job，
 * 到期还没人办就触发边界事件（超时提醒、超时升级、超时终止）。
 * 异步任务（外部调用、消息投递）会复用同一个载体。
 *
 * <p><b>job 自己不自带定时器</b>：本引擎不启动任何后台线程，
 * 什么时候来扫、扫多频繁由宿主决定（见 {@code WfJobService#executeDueJobs}）。
 * 理由与 {@code WfOverdueScanner} 相同 —— 频率是业务决定的事，
 * 引擎内嵌定时器会让"引依赖就跑起来"成为默认行为。
 *
 * @author zifang
 */
public class WfJob implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    /** 属于哪个流程实例。 */
    private String processInstanceId;

    /** 挂在哪个 token 上。token 结束/转移时对应 job 要被清掉。 */
    private String executionId;

    /** 关联的流程节点（这里是边界事件 id）。 */
    private String elementId;

    /** 宿主节点 id —— 边界事件挂在谁身上。超时是从进入宿主那一刻起算的。 */
    private String attachedToRef;

    /**
     * job 种类。默认 {@link WfJobType#TIMER} —— 存量数据与既有调用方都按定时器对待。
     */
    private WfJobType type = WfJobType.TIMER;

    /**
     * 到期时刻。扫描器只取 {@code duedate <= now} 的 job。
     *
     * <p>消息 / 信号订阅的 duedate 为 {@code null}：它们不由时间触发。
     * 扫描器还要额外按 {@link #type} 过滤，不能只靠 duedate 为空来区分。
     */
    private Date duedate;

    /**
     * 外部任务的主题名。worker 按 topic 领活。
     *
     * <p>单独一列而不是塞进 exceptionMessage：worker 取活是<b>服务端过滤</b>
     * （SQL 条件），而 message/signal 的名字只在触发那一刻在内存里比对一次。
     */
    private String topic;

    /** 当前锁定者（worker id）。{@code null} 表示可被领取。 */
    private String lockedBy;

    /** 锁定时刻。领活时写入；超过 leaseMillis 没完成就被别的 worker 重新领走。 */
    private Date lockedAt;

    /**
     * 剩余重试次数。
     *
     * <p>job 执行失败（不是流程失败）时递减；归零后不再重试，
     * 避免一个必然失败的 job 永远重试把执行器打满。
     */
    private int retries = DEFAULT_RETRIES;

    /**
     * 循环定时器已经响过几次（{@code timeCycle}）。
     *
     * <p>只为循环定时器而存在，其余 job 恒为 0。它不可省：
     * 重新挂下一次触发时要判断"还响不响"，而
     * {@code R3/PT1H} 的锚点（进入宿主节点的那一刻）<b>不在 job 上</b>，
     * 手上只有上一条 job 的触发时刻。少了这个计数就没法区分
     * "第 3 次（响完就停）"与"第 1 次（还得再挂两次）"。
     *
     * <p>与 {@link #retries} 分列而不是复用：两者都会递减，但一个数的是
     * "还能失败几次"、一个数的是"已经响过几次"，
     * 挤在一列的话排障时看到 retries=0 分不清是重试耗尽还是循环响完了。
     */
    private int cycleIndex;

    public static final int DEFAULT_RETRIES = 3;

    /**
     * 优先级，取自宿主节点的 {@code zifang:priority}。
     *
     * <p><b>与 {@code WfTask#priority} 共用同一个来源但不是同一个东西</b>：
     * 任务优先级决定「待办列表里谁排前面」，那是给人看的；
     * job 优先级决定「队列里谁先被取走执行」，那是给执行器看的。
     * 一个加急单子的两个节点常常要写两遍同一个数 —— 所以它们从同一个字段拷贝，
     * 而不是各配各的（配两遍的话，改了其中一个就会出现「待办很急但流程不急」）。
     *
     * <p><b>本字段目前只有异步 job 真的按它排序</b>
     * （见 {@code WfJobQuery#setOrderByPriority}）。
     * 其它 job 类型建的时候也会把它拷上，但查询不启用排序 ——
     * 字段先备着、用上再开，比"要用了再回来加字段"省一轮迁移。
     */
    private int priority = com.zifang.z.wf.core.definition.WfNode.DEFAULT_PRIORITY;

    /** 最近一次失败的异常信息，排障时直接看得到为什么它不执行。 */
    private String exceptionMessage;

    /**
     * 这条订阅在等什么（消息名 / 信号名）。
     *
     * <p><b>从 {@link #exceptionMessage} 里搬出来，单独占一列</b> ——
     * 理由与 {@link #topic} 给外部任务单开一列完全一样，而且这里更硬：
     * 排障视图必须同时呈现「这个 job 在等什么」与「它错在哪」，
     * 而这两个值原来挤在同一列里，只能二选一。
     *
     * <p><b>为什么以前没出事</b>：引擎的 {@code recordFailure} 目前只作用于
     * {@code TIMER} / {@code ASYNC} / {@code EXTERNAL} 三类，而这三种都不写这一列；
     * 写订阅名的 {@code MESSAGE} / {@code SIGNAL} / {@code EVENT_*} 走不到扣重试那条路。
     * 但这是<b>巧合而不是约定</b> —— 引擎本来就有"job 失败就扣重试"的机制
     * （{@link #recordFailure}），只是当前按类型分流绕开了。
     * 一旦哪条路径对订阅型 job 调了它，订阅名会被失败信息覆盖，
     * 之后按名字匹配再也匹配不上，那条订阅等于从引擎里消失且不报错。
     *
     * <p>订阅型 job 在触发那一刻就被删了，正常路径上不需要读这一列；
     * 留着是为了故障视图能说清「它在等什么、等的是什么」，
     * 以及让上面那条隐患不再成立。
     */
    private String subscriptionName;

    private Date createTime;

    private Date lastFailureTime;

    /** 乐观锁版本，语义与 {@link WfTask#getRevision()} 一致。 */
    private int revision;

    /**
     * 还能重试吗。
     *
     * <p>{@code RETRIES_EXHAUSTED} 只是个"减到负数"的下限标记，
     * 不单独加字段 —— 多一个布尔字段就多一处可能与 retries 不一致的状态。
     */
    public static final int RETRIES_EXHAUSTED = -1;

    public boolean isRetriesExhausted() {
        return retries <= 0;
    }

    /**
     * 记一次失败并扣重试次数。
     *
     * <p>扣到 0 就钉死在 {@link #RETRIES_EXHAUSTED}，不再继续减 ——
     * 归零后再减会变成 -2、-3，界面上显示"重试 -2 次"没人看得懂。
     *
     * @return 扣减后的剩余次数
     */
    public int recordFailure(String message) {
        this.exceptionMessage = message;
        this.lastFailureTime = new Date();
        if (this.retries > 0) {
            this.retries--;
        }
        if (this.retries <= 0) {
            this.retries = RETRIES_EXHAUSTED;
        }
        return this.retries;
    }

    public int nextRevision() {
        return ++this.revision;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public void setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getElementId() {
        return elementId;
    }

    public void setElementId(String elementId) {
        this.elementId = elementId;
    }

    public String getAttachedToRef() {
        return attachedToRef;
    }

    public void setAttachedToRef(String attachedToRef) {
        this.attachedToRef = attachedToRef;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public Date getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(Date lockedAt) {
        this.lockedAt = lockedAt;
    }

    public WfJobType getType() {
        return type;
    }

    public void setType(WfJobType type) {
        this.type = type == null ? WfJobType.TIMER : type;
    }

    public Date getDuedate() {
        return duedate;
    }

    public void setDuedate(Date duedate) {
        this.duedate = duedate;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public int getCycleIndex() {
        return cycleIndex;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public void setCycleIndex(int cycleIndex) {
        this.cycleIndex = cycleIndex;
    }

    public String getExceptionMessage() {
        return exceptionMessage;
    }

    public void setExceptionMessage(String exceptionMessage) {
        this.exceptionMessage = exceptionMessage;
    }

    public String getSubscriptionName() {
        return subscriptionName;
    }

    public void setSubscriptionName(String subscriptionName) {
        this.subscriptionName = subscriptionName;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getLastFailureTime() {
        return lastFailureTime;
    }

    public void setLastFailureTime(Date lastFailureTime) {
        this.lastFailureTime = lastFailureTime;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    @Override
    public String toString() {
        return "WfJob{" + id + " element=" + elementId + " duedate=" + duedate
                + " retries=" + retries + "}";
    }
}
