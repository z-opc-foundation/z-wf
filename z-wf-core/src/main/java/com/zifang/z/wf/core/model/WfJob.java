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

    public static final int DEFAULT_RETRIES = 3;

    /** 最近一次失败的异常信息，排障时直接看得到为什么它不执行。 */
    private String exceptionMessage;

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

    public String getExceptionMessage() {
        return exceptionMessage;
    }

    public void setExceptionMessage(String exceptionMessage) {
        this.exceptionMessage = exceptionMessage;
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
