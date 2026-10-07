package com.zifang.z.wf.core.view;

import java.io.Serializable;
import java.util.Date;

/**
 * 一条历史故障的对外视图（第 40 轮）。
 *
 * <p><b>不复用 {@link com.zifang.z.wf.core.model.WfHistoricIncident} 实体</b>，
 * 理由与 {@link WfBatchView} 一样：实体是给引擎自己再读回去的持久化形态，
 * 原样吐到接口上就等于把持久化格式变成了对外契约。
 *
 * <p>多出来的那个 {@link #isStillFailing} 是<b>推导出来的</b>（现场那个 job 还在不在、
 * 带不带失败痕迹），<b>不是这一行上的事实</b> —— 换句话说它随时可能变，
 * 拿它做历史报表会得到一个会随时间自己改数的结论。
 *
 * @author zifang
 */
public class WfHistoricIncidentView implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String jobId;

    private String processInstanceId;

    private String executionId;

    private String activityId;

    private String definitionKey;

    private String activityName;

    private String jobType;

    private String subscriptionName;

    private String errorType;

    private String errorMessage;

    private int failureCount;

    private Date firstFailureTime;

    private Date lastFailureTime;

    /**
     * 这条记录对应的 job <b>现在还在卡着吗</b>。
     *
     * <p><b>推导值，不是历史事实</b>：job 还在且带失败痕迹才算 true。
     * 用来回答"上周三那批现在好了没有"，不要用来做趋势统计。
     */
    private boolean stillFailing;

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

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
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

    public boolean isStillFailing() {
        return stillFailing;
    }

    public void setStillFailing(boolean stillFailing) {
        this.stillFailing = stillFailing;
    }
}