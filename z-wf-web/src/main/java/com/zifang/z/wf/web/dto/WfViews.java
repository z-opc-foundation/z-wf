package com.zifang.z.wf.web.dto;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批中心视图对象集合。
 *
 * <p>刻意不直接返回引擎内部的 {@code WfTask} / {@code WfProcessInstance}：
 * 那些是<b>持久化实体</b>，字段（revision / variables / arrivedActivities 等）是内部实现，
 * 一旦直接暴露给前端，将来加个内部字段就会意外变成对外 API。
 * VO 是显式的契约边界。
 *
 * @author zifang
 */
public final class WfViews {

    private WfViews() {
    }

    /**
     * 仪表盘统计。
     */
    public static class DashboardStats implements Serializable {

        private static final long serialVersionUID = 1L;

        private long todoCount;
        private long doneCount;
        private long myProcessCount;
        private long overdueCount;
        private long ccCount;

        public long getTodoCount() {
            return todoCount;
        }

        public void setTodoCount(long todoCount) {
            this.todoCount = todoCount;
        }

        public long getDoneCount() {
            return doneCount;
        }

        public void setDoneCount(long doneCount) {
            this.doneCount = doneCount;
        }

        public long getMyProcessCount() {
            return myProcessCount;
        }

        public void setMyProcessCount(long myProcessCount) {
            this.myProcessCount = myProcessCount;
        }

        public long getOverdueCount() {
            return overdueCount;
        }

        public void setOverdueCount(long overdueCount) {
            this.overdueCount = overdueCount;
        }

        public long getCcCount() {
            return ccCount;
        }

        public void setCcCount(long ccCount) {
            this.ccCount = ccCount;
        }
    }

    /**
     * 任务摘要（列表项）。
     */
    public static class TaskSummary implements Serializable {

        private static final long serialVersionUID = 1L;

        private String taskId;
        private String taskName;
        private String processInstanceId;
        private String processKey;
        private String businessKey;
        private String definitionId;
        private String formKey;
        private String category;
        private String assignee;
        private String owner;
        private String handler;
        private String status;
        private int priority;
        private long createTime;
        private long dueDate;
        private boolean overdue;
        private String startUserId;

        public String getTaskId() {
            return taskId;
        }

        public void setTaskId(String taskId) {
            this.taskId = taskId;
        }

        public String getTaskName() {
            return taskName;
        }

        public void setTaskName(String taskName) {
            this.taskName = taskName;
        }

        public String getProcessInstanceId() {
            return processInstanceId;
        }

        public void setProcessInstanceId(String processInstanceId) {
            this.processInstanceId = processInstanceId;
        }

        public String getProcessKey() {
            return processKey;
        }

        public void setProcessKey(String processKey) {
            this.processKey = processKey;
        }

        public String getBusinessKey() {
            return businessKey;
        }

        public void setBusinessKey(String businessKey) {
            this.businessKey = businessKey;
        }

        public String getDefinitionId() {
            return definitionId;
        }

        public void setDefinitionId(String definitionId) {
            this.definitionId = definitionId;
        }

        public String getFormKey() {
            return formKey;
        }

        public void setFormKey(String formKey) {
            this.formKey = formKey;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getAssignee() {
            return assignee;
        }

        public void setAssignee(String assignee) {
            this.assignee = assignee;
        }

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        /**
         * 当前处理人（委派态为 owner）。
         */
        public String getHandler() {
            return handler;
        }

        public void setHandler(String handler) {
            this.handler = handler;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public long getCreateTime() {
            return createTime;
        }

        public void setCreateTime(long createTime) {
            this.createTime = createTime;
        }

        public long getDueDate() {
            return dueDate;
        }

        public void setDueDate(long dueDate) {
            this.dueDate = dueDate;
        }

        public boolean isOverdue() {
            return overdue;
        }

        public void setOverdue(boolean overdue) {
            this.overdue = overdue;
        }

        public String getStartUserId() {
            return startUserId;
        }

        public void setStartUserId(String startUserId) {
            this.startUserId = startUserId;
        }
    }

    /**
     * 任务详情（比摘要多意见与变量）。
     */
    public static class TaskDetail extends TaskSummary {

        private static final long serialVersionUID = 1L;

        private String comment;
        private String completerId;
        private long endTime;
        private Map<String, Object> variables = new HashMap<>();
        private List<Map<String, Object>> trail = new java.util.ArrayList<>();

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }

        public String getCompleterId() {
            return completerId;
        }

        public void setCompleterId(String completerId) {
            this.completerId = completerId;
        }

        public long getEndTime() {
            return endTime;
        }

        public void setEndTime(long endTime) {
            this.endTime = endTime;
        }

        public Map<String, Object> getVariables() {
            return variables;
        }

        public void setVariables(Map<String, Object> variables) {
            this.variables = variables;
        }

        public List<Map<String, Object>> getTrail() {
            return trail;
        }

        public void setTrail(List<Map<String, Object>> trail) {
            this.trail = trail;
        }
    }

    /**
     * 流程实例摘要。
     */
    public static class ProcessInstanceView implements Serializable {

        private static final long serialVersionUID = 1L;

        private String processInstanceId;
        private String definitionKey;
        private String definitionId;
        private int definitionVersion;
        private String businessKey;
        private String startUserId;
        private String startDeptId;
        private String category;
        private String status;
        private String result;
        private long startTime;
        private long endTime;
        private long durationMillis;
        private int openTaskCount;

        public String getProcessInstanceId() {
            return processInstanceId;
        }

        public void setProcessInstanceId(String processInstanceId) {
            this.processInstanceId = processInstanceId;
        }

        public String getDefinitionKey() {
            return definitionKey;
        }

        public void setDefinitionKey(String definitionKey) {
            this.definitionKey = definitionKey;
        }

        public String getDefinitionId() {
            return definitionId;
        }

        public void setDefinitionId(String definitionId) {
            this.definitionId = definitionId;
        }

        public int getDefinitionVersion() {
            return definitionVersion;
        }

        public void setDefinitionVersion(int definitionVersion) {
            this.definitionVersion = definitionVersion;
        }

        public String getBusinessKey() {
            return businessKey;
        }

        public void setBusinessKey(String businessKey) {
            this.businessKey = businessKey;
        }

        public String getStartUserId() {
            return startUserId;
        }

        public void setStartUserId(String startUserId) {
            this.startUserId = startUserId;
        }

        public String getStartDeptId() {
            return startDeptId;
        }

        public void setStartDeptId(String startDeptId) {
            this.startDeptId = startDeptId;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        public long getStartTime() {
            return startTime;
        }

        public void setStartTime(long startTime) {
            this.startTime = startTime;
        }

        public long getEndTime() {
            return endTime;
        }

        public void setEndTime(long endTime) {
            this.endTime = endTime;
        }

        public long getDurationMillis() {
            return durationMillis;
        }

        public void setDurationMillis(long durationMillis) {
            this.durationMillis = durationMillis;
        }

        public int getOpenTaskCount() {
            return openTaskCount;
        }

        public void setOpenTaskCount(int openTaskCount) {
            this.openTaskCount = openTaskCount;
        }
    }

    /**
     * 流程详情（实例 + 当前待办 + 轨迹 + 评论）。
     */
    public static class ProcessDetail extends ProcessInstanceView {

        private static final long serialVersionUID = 1L;

        private List<TaskSummary> openTasks = new java.util.ArrayList<>();
        private List<Map<String, Object>> trail = new java.util.ArrayList<>();
        private List<Map<String, Object>> comments = new java.util.ArrayList<>();
        private Map<String, Object> variables = new HashMap<>();

        public List<TaskSummary> getOpenTasks() {
            return openTasks;
        }

        public void setOpenTasks(List<TaskSummary> openTasks) {
            this.openTasks = openTasks;
        }

        public List<Map<String, Object>> getTrail() {
            return trail;
        }

        public void setTrail(List<Map<String, Object>> trail) {
            this.trail = trail;
        }

        public List<Map<String, Object>> getComments() {
            return comments;
        }

        public void setComments(List<Map<String, Object>> comments) {
            this.comments = comments;
        }

        public Map<String, Object> getVariables() {
            return variables;
        }

        public void setVariables(Map<String, Object> variables) {
            this.variables = variables;
        }
    }

    /**
     * 执行令牌（排障用）。
     *
     * <p><b>刻意不暴露 {@code arrivedActivities} 与 {@code variables}</b>：
     * 前者是引擎做汇合判据的内部状态（"这个网关已经记下了哪些到达过的分支"），
     * 后者是令牌级中间变量、只对引擎自己有意义。两者抖给前端等于把内部实现
     * 固化成对外契约 —— 以后改汇合算法就得改前端。
     * 排障真正需要的是"这个流程现在卡在哪个节点、是什么状态"。
     */
    public static class ExecutionView implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 令牌 id。 */
        private String executionId;
        /** 所属流程实例 id。 */
        private String processInstanceId;
        /** 父令牌 id（fork 出来的子令牌才有）。 */
        private String parentExecutionId;
        /** 当前所在节点 id。 */
        private String activityId;
        /** 令牌状态：ACTIVE / WAITING / ENDED。 */
        private String state;
        /** 进入该节点的时间（epoch-millis）。 */
        private long enteredTime;
        /** 是否为 fork 出的子令牌。 */
        private boolean child;
        /** 状态快捷判据（由 state 推导，不额外查库）。 */
        private boolean active;
        private boolean waiting;
        private boolean ended;

        public String getExecutionId() {
            return executionId;
        }

        public void setExecutionId(String executionId) {
            this.executionId = executionId;
        }

        public String getProcessInstanceId() {
            return processInstanceId;
        }

        public void setProcessInstanceId(String processInstanceId) {
            this.processInstanceId = processInstanceId;
        }

        public String getParentExecutionId() {
            return parentExecutionId;
        }

        public void setParentExecutionId(String parentExecutionId) {
            this.parentExecutionId = parentExecutionId;
        }

        public String getActivityId() {
            return activityId;
        }

        public void setActivityId(String activityId) {
            this.activityId = activityId;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public long getEnteredTime() {
            return enteredTime;
        }

        public void setEnteredTime(long enteredTime) {
            this.enteredTime = enteredTime;
        }

        public boolean isChild() {
            return child;
        }

        public void setChild(boolean child) {
            this.child = child;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public boolean isWaiting() {
            return waiting;
        }

        public void setWaiting(boolean waiting) {
            this.waiting = waiting;
        }

        public boolean isEnded() {
            return ended;
        }

        public void setEnded(boolean ended) {
            this.ended = ended;
        }
    }

    /**
     * 流程定义摘要。
     */
    public static class DefinitionView implements Serializable {

        private static final long serialVersionUID = 1L;

        private String key;
        private String name;
        private int version;
        private String category;
        private String description;
        private int nodeCount;
        private int flowCount;
        private boolean suspended;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public int getNodeCount() {
            return nodeCount;
        }

        public void setNodeCount(int nodeCount) {
            this.nodeCount = nodeCount;
        }

        public int getFlowCount() {
            return flowCount;
        }

        public void setFlowCount(int flowCount) {
            this.flowCount = flowCount;
        }

        public boolean isSuspended() {
            return suspended;
        }

        public void setSuspended(boolean suspended) {
            this.suspended = suspended;
        }
    }
}
