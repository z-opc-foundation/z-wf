package com.zifang.z.wf.core.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 一条<b>历史故障记录</b>（第 40 轮）——「某个 job 曾经失败过」。
 *
 * <p>它补的是 {@link com.zifang.z.wf.core.service.WfIncidentService} 那套
 * <b>推导式</b>故障查询补不了的那一半：那边每次都从 {@link WfJob} 现场扫出来，
 * 而 job 一旦执行成功（或被清理掉）就没了 —— 于是
 * 「上周三那批单为什么全卡住了」这类事后复盘<b>查不到</b>。
 * 推导是「现在的样子」，记录才是「发生过的事」。
 *
 * <p><b>一个 job 一条，反复失败时累加而不是追加新行</b>（对应 Camunda 的
 * {@code JobLogManager}）。理由是运维问的从来不是「第 3 次失败是什么时候」，
 * 而是「它试过几次、每次为什么、什么时候开始的」——
 * 拆成三行之后，「这个 job 一共失败了几次」就变成了一个必须自己 group by 的问题。
 *
 * <p><b>刻意冗余存了 {@link #definitionKey} 与 {@link #activityName}</b>：
 * 故障记录的全部价值在于「事情过去之后还能查」，
 * 而流程实例与流程定义都可能已经被清理。那样的话这一行会退化成
 * 「一个 job id 加一句错误信息」—— 恰好把最有用的两个字段（谁的流程、哪个环节）丢了。
 *
 * @author zifang
 */
public class WfHistoricIncident implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    /**
     * 对应的 job id。
     *
     * <p><b>job 迟早会被删，而这一行不会</b>。所以它只是线索，不是外键 ——
     * 查得到 job 说明它还在卡着，查不到只说明"现在不卡了"，不代表记录有问题。
     */
    private String jobId;

    private String processInstanceId;

    private String executionId;

    /** 停在哪个节点上（对应 job 的 {@code elementId}）。 */
    private String elementId;

    private String attachedToRef;

    /** 冗余存：实例与定义都可能被清理，那时只有这一列还能说清"谁的流程"。 */
    private String definitionKey;

    /** 冗余存：定义会被改版，按今天的定义查历史会得到一个对不上的名字。 */
    private String activityName;

    /** {@link WfJobType} 的枚举名。 */
    private String jobType;

    private String subscriptionName;

    /** 异常类名。与 {@link #errorMessage} 分列：排障要同时看到「错在哪类」与「错在哪句」。 */
    private String errorType;

    /** 最近一次失败的原因（{@code 异常类名: 消息} 或外部任务自己报的那句）。 */
    private String errorMessage;

    /** 累计失败次数，**从 1 起**。0 是「没有失败过」，而这样的行根本不该存在。 */
    private int failureCount;

    /** 第一次失败的时刻。 */
    private Date firstFailureTime;

    /** 最近一次失败的时刻。 */
    private Date lastFailureTime;

    /**
     * 乐观锁版本。与 {@link WfJob} / {@link WfTask} 同一套约定：
     * 改既有记录前先 {@link #nextRevision()}。
     *
     * <p>它在这张表上尤其必要：一次重试链里可能有两个执行器线程先后记失败，
     * 没有版本号的话 {@code failureCount} 会丢掉一次。
     */
    private int revision;

    public WfHistoricIncident() {
    }

    /**
     * 记一次失败：累计次数、刷新最近失败时刻，并按「异常类名: 消息」拆出错误类型。
     *
     * <p>拆类型这件事此前只存在于 {@link WfIncidentService} 的解析里（去消息前面
     * 截一个 {@code XxxException:} 前缀）。放在这里是必须的：历史记录要在
     * <b>job 早就不在了</b>的时候还能读，而那种行没有 job 可解析。
     *
     * @param raw 失败原因原文
     * @return 累加后的失败次数
     */
    public int recordFailure(String raw) {
        if (failureCount == 0) {
            firstFailureTime = new Date();
        }
        failureCount++;
        errorMessage = raw;
        errorType = WfJob.exceptionTypeOf(raw);
        lastFailureTime = new Date();
        return failureCount;
    }


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
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

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public Date getFirstFailureTime() {
        return firstFailureTime;
    }

    public void setFirstFailureTime(Date firstFailureTime) {
        this.firstFailureTime = firstFailureTime;
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

    public void nextRevision() {
        this.revision = revision + 1;
    }

    @Override
    public String toString() {
        return "WfHistoricIncident{" + jobId + " x" + failureCount
                + " at=" + lastFailureTime + ": " + errorMessage + "}";
    }
}