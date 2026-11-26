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

    /**
     * 这条 job 是否<b>互斥</b>：不与同一流程实例的其它 exclusive job 并发执行。
     *
     * <p>默认 {@code true}，与 Camunda 一致（"Exclusive Jobs are the default
     * configuration"）。
     *
     * <p><b>为什么落到 job 上、而不是只留在节点定义里</b>：执行器判断"要不要排它"
     * 时手上只有 job，读不到节点定义；而 job 可能早就排好了、定义早就换过版本。
     * 把它抄进 job，执行期看到的才是当初排它时的那个意图。
     *
     * <p>与 {@link #priority} 同属"建 job 时从宿主节点抄一份"的字段，
     * 也同样要过存量库补列那一关。
     */
    private boolean exclusive = true;

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
     * <p><b>调用方除了扣重试，还必须写一条历史故障记录</b>
     * （第 40 轮起，见 {@code WfHistoricIncidentService#recordFailure}）。
     * 截至目前有<b>两处</b>调用方：{@code WfJobService#recordFailure}（执行器路径）
     * 与 {@code WfExternalTaskService#fail}（外部任务路径）。
     * 漏掉哪一处，哪一类失败就在历史里彻底消失 ——
     * 而且<b>不会报错</b>：job 上的失败痕迹一切正常，只是没人把它记下来。
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

    /**
     * 从 {@code 异常类名 + ": " + 消息} 里把<b>类型</b>截出来；截不出就返回 {@code null}。
     *
     * <p><b>这里是这条格式的唯一实现</b>（生产者是各条失败路径传给
     * {@link #recordFailure} 的那句）。两处消费：当前故障视图
     * （{@code WfIncidentService}）与历史故障记录（{@code WfHistoricIncident}）。
     * 曾经两边各写一份，而新写的那份<b>少了「首字母大写才算类名」那条</b> ——
     * 于是外部任务报的「连接超时: 连不上 db」在历史里会被认成一个叫"连接超时"的类型，
     * 在当前故障里却不会。同一句话、两个答案，而两边都不报错。
     *
     * <p>为什么要有「首字母大写且无空格」这条：{@link #recordFailure} 收的是
     * 人写的句子（外部任务 {@code fail} 直接透传 worker 报的原因），
     * 里面带冒号很正常。无条件截一刀会把一句正文的前半截当成类型，
     * 按类型筛选时把不同的问题混成一类 —— 那比不筛选更坏。
     *
     * @param errorMessage {@code EXCEPTION_MSG} 那列的原文
     * @return 认得出的类型名；认不出返回 {@code null}（**不是**截出来的半句话）
     */
    public static String exceptionTypeOf(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        int colon = errorMessage.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String head = errorMessage.substring(0, colon).trim();
        return !head.isEmpty() && head.indexOf(' ') < 0
                && Character.isUpperCase(head.charAt(0)) ? head : null;
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

    /** 是否互斥（不与同实例的其它 exclusive job 并发）。默认 {@code true}。 */
    public boolean isExclusive() {
        return exclusive;
    }

    public void setExclusive(boolean exclusive) {
        this.exclusive = exclusive;
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
