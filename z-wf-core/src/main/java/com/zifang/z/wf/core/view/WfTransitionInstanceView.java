package com.zifang.z.wf.core.view;

import java.io.Serializable;

/**
 * 活动实例树里的一个<b>已完成步骤</b>（对应 Camunda 的 {@code TransitionInstance}）。
 *
 * <p>一条 {@link WfActivityInstanceView} 的 {@code childTransitionInstances}，
 * 装的是<b>它那条 token 已经离开过的节点</b>，按发生顺序排。
 * 当前正停着的那个节点不在里面 —— 那一行还没写历史（历史是离开时才写的，
 * 一次节点访问一条），它由节点自身的 {@code activityId} 表示。
 *
 * <p>字段直接来自 {@code WfActivityInstance}，没有加工：
 * 视图层一旦开始"推导"，它与真实历史就会分家，而排障时最不能容忍的就是两处对不上。
 * {@code variables} <b>刻意不搬过来</b> —— 它是节点变量，
 * 要看走 {@code WfVariableService}，否则一个只读结构的接口会变成数据出口。
 *
 * @author zifang
 */
public class WfTransitionInstanceView implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String parentActivityInstanceId;

    private String processInstanceId;

    private String activityId;

    private String activityName;

    private String activityType;

    /** 是哪条 token 走过的这一步。 */
    private String executionId;

    private String assignee;

    /** 这一步的结论（completed / 某条出线名 / approved 之类）。 */
    private String outcome;

    private String detail;

    private long durationMillis;

    private Long startTime;

    private Long endTime;

    public WfTransitionInstanceView() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getParentActivityInstanceId() {
        return parentActivityInstanceId;
    }

    public void setParentActivityInstanceId(String parentActivityInstanceId) {
        this.parentActivityInstanceId = parentActivityInstanceId;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public void setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getActivityName() {
        return activityName;
    }

    public void setActivityName(String activityName) {
        this.activityName = activityName;
    }

    public String getActivityType() {
        return activityType;
    }

    public void setActivityType(String activityType) {
        this.activityType = activityType;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getAssignee() {
        return assignee;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public Long getStartTime() {
        return startTime;
    }

    public void setStartTime(Long startTime) {
        this.startTime = startTime;
    }

    public Long getEndTime() {
        return endTime;
    }

    public void setEndTime(Long endTime) {
        this.endTime = endTime;
    }

    @Override
    public String toString() {
        return "TransitionInstance{" + activityId + " -> " + outcome + "}";
    }
}