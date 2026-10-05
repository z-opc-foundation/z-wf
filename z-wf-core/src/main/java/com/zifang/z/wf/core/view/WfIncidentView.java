package com.zifang.z.wf.core.view;

import java.util.Date;

/**
 * 运行期故障视图 —— 一条"现在还没干成、而且正在为此付出代价"的 job。
 *
 * <p>与 {@link WfSubscriptionView} 是一对：订阅回答"在等什么"，
 * 本视图回答"哪件事没干成"。两者都从 {@code ZWF_JOB} 派生，但取的是不同的行 ——
 * 同一张表里既有"安安静静在等"的行，也有"已经炸了、还躺在那儿"的行，
 * 而后者此前<b>没有任何接口能列出来</b>。
 *
 * <p><b>为什么不做成 Camunda 那种独立 Incident 实体</b>：
 * Camunda 有自己的 {@code ACT_RU_INCIDENT} 表，带 handlerType / 配置参数 /
 * acknowledge / resolve 一整套生命周期。代价是同一件事有两处真源 ——
 * job 失败了一次，incident 表记一条，job 表也记一次，两者随时可能对不上，
 * 而"对不上"正是排障时最不能容忍的事。
 * 故障的定义已经完全由 job 的两个字段决定（{@code retries} 与
 * {@code lastFailureTime}），从 job 派生就没有对不上的可能。
 *
 * <p>代价是本视图只覆盖<b>当前</b>故障：job 一旦执行成功就被删掉，
 * 事后复盘（"上周三那批单为什么全卡住了"）需要另一套持久化记录，
 * 那是独立的一件事，本视图不假装能做。
 *
 * @author zifang
 */
public class WfIncidentView {

    /** 就是 job id —— 故障与 job 是一对一，不另造 id，否则两者要对账。 */
    private String id;

    private String processInstanceId;

    private String executionId;

    /**
     * 出事的是哪个节点。
     *
     * <p>对边界事件来说是边界事件自己的 id，对外部任务/异步来说是宿主节点 ——
     * 也就是说，它指"这件事挂在流程的哪个位置上"，而不是"哪个节点本身炸了"。
     */
    private String activityId;

    /** 宿主节点。{@code activityId} 是边界事件时，两者不同。 */
    private String attachedToRef;

    private String definitionKey;

    /** 节点显示名，排障时人看的是"超时提醒"而不是 {@code timerEscalate}。 */
    private String activityName;

    private String jobType;

    /**
     * 这条订阅原本在等什么（消息名 / 信号名 / 外部任务主题）。
     *
     * <p><b>与 {@link #errorMessage} 分开是本视图存在的理由之一</b>：
     * "它在等 X，但没等到，报的是 Y"这句话只有在两个值分开时才说得出来。
     * 之前两者挤在一列里，这一列只能显示其中一个。
     */
    private String subscriptionName;

    /** 最近一次失败的原因。 */
    private String errorMessage;

    /** 异常类名，与 {@link #errorMessage} 分开展示，便于按类型归类。 */
    private String errorType;

    private Date lastFailureTime;

    /**
     * 距今已失败多少毫秒。
     *
     * <p>比 {@link #lastFailureTime} 好用来分诊："失败 3 小时"与"失败 3 秒"要采取的动作不同，
     * 而让人自己对两个时间戳做减法，在排障现场是多余的一道手工活。
     * 没失败过（{@code lastFailureTime} 为 null）时是 {@code null} 而不是 0 ——
     * 0 会被读成"刚刚失败"，与"没失败"是两回事。
     */
    private Long failedMillis;

    /** 剩余重试次数；{@code <= 0} 表示已耗尽，不会再自动重试。 */
    private int retries;

    /**
     * 还会再自动重试吗。
     *
     * <p>与 {@code retries <= 0} 重复，但方向相反：一个用来筛"还活着但在挣扎"，
     * 一个用来筛"已经彻底不动了"。两者的处置动作不同 —— 前者可以等，
     * 后者必须人去看。让人在 UI 上做减法，比给两个布尔值更容易用错。
     */
    private boolean retryable;

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

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getAttachedToRef() {
        return attachedToRef;
    }

    public void setAttachedToRef(String attachedToRef) {
        this.attachedToRef = attachedToRef;
    }

    public String getDefinitionKey() {
        return definitionKey;
    }

    public void setDefinitionKey(String definitionKey) {
        this.definitionKey = definitionKey;
    }

    public String getActivityName() {
        return activityName;
    }

    public void setActivityName(String activityName) {
        this.activityName = activityName;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public String getSubscriptionName() {
        return subscriptionName;
    }

    public void setSubscriptionName(String subscriptionName) {
        this.subscriptionName = subscriptionName;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public Date getLastFailureTime() {
        return lastFailureTime;
    }

    public void setLastFailureTime(Date lastFailureTime) {
        this.lastFailureTime = lastFailureTime;
    }

    public Long getFailedMillis() {
        return failedMillis;
    }

    public void setFailedMillis(Long failedMillis) {
        this.failedMillis = failedMillis;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public void setRetryable(boolean retryable) {
        this.retryable = retryable;
    }

    @Override
    public String toString() {
        return "WfIncidentView{" + id + " @ " + activityId + " " + jobType
                + " retries=" + retries + "}";
    }
}