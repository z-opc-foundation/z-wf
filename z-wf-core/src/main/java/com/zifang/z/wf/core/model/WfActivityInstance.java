package com.zifang.z.wf.core.model;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 活动实例历史 —— 审批轨迹的一行。
 *
 * <p>轨迹（trail）是 z-wf 排障和"审批到哪一步了"查询的主数据源，<b>与运行态 token 分离存储</b>：
 * 运行态 token 会随跳转/撤回被改写甚至删除，历史只追加不改。
 *
 * @author zifang
 */
public class WfActivityInstance implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String processInstanceId;

    private String processDefinitionKey;

    private String activityId;

    private String activityName;

    private String activityType;

    private String executionId;

    private String assignee;

    /** 停留时长（毫秒）。 */
    private long durationMillis;

    private Date startTime;

    private Date endTime;

    /** 离开原因：completed / rejected / cancelled / terminated / failed。 */
    private String outcome;

    /** 审批意见 / 异常信息。 */
    private String detail;

    /** 进入该活动时的变量快照（脱敏后由业务方决定）。 */
    private Map<String, Object> variables = new HashMap<>();

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

    public String getProcessDefinitionKey() {
        return processDefinitionKey;
    }

    public void setProcessDefinitionKey(String processDefinitionKey) {
        this.processDefinitionKey = processDefinitionKey;
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

    public long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public Date getStartTime() {
        return startTime;
    }

    public void setStartTime(Date startTime) {
        this.startTime = startTime;
    }

    public Date getEndTime() {
        return endTime;
    }

    public void setEndTime(Date endTime) {
        this.endTime = endTime;
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

    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables == null ? new HashMap<String, Object>() : variables;
    }

    @Override
    public String toString() {
        return "WfActivityInstance{" + processInstanceId + " " + activityName + " -> " + outcome + "}";
    }
}
