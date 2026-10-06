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
        private String name;
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
        /** 任务是否挂起。挂起的仍会出现在待办里，前端据此显示[暂停]角标而不是隐藏。 */
        private boolean suspended;

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

        public boolean isSuspended() {
            return suspended;
        }

        public void setSuspended(boolean suspended) {
            this.suspended = suspended;
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

        /**
         * 实例名称 —— 给界面看的可读描述（「张三的请假申请」）。
         *
         * <p>与 {@link #businessKey} 不是一回事：businessKey 是业务方的单号
         * （对外、要做唯一性、要能被业务系统查回来），name 是事后补的可读描述。
         * 两者都不强制、都不唯一、可以为空，但<b>不能互相顶替</b> ——
         * 拿单号当标题会得到一串没人看得懂的编号，而那串编号怎么显示
         * 是业务方自己的事，不该由引擎替他们决定。
         */
        private String name;

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

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
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

    /**
     * 历史活动实例（审批轨迹的一行）。
     *
     * <p>与 {@code toTrail} 拼的 Map 字段一一对应，只是这里有具名字段与类型，
     * 给"跨流程聚合"的场景用（要排序/筛选/做导出时，Map 不好用）。
     */
    public static class ActivityInstanceView implements Serializable {

        private static final long serialVersionUID = 1L;

        private String processInstanceId;
        private String processDefinitionKey;
        private String activityId;
        private String activityName;
        private String activityType;
        private String assignee;
        private String outcome;
        private String detail;
        private long startTime;
        private long endTime;
        private long durationMillis;

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
    }

    /**
     * Job（定时器边界事件等"到点要做的事"）。
     *
     * <p>时间字段用 epoch 毫秒的 long，与既有 {@code ExecutionView#enteredTime}
     * 的约定一致：直接序列化 {@code Date} 会让时区与毫秒格式成为对外契约的一部分。
     */
    public static class JobView implements Serializable {

        private static final long serialVersionUID = 1L;

        private String jobId;
        private String processInstanceId;
        private String executionId;
        private String elementId;
        private String attachedToRef;

        /**
         * job 种类（TIMER / MESSAGE / SIGNAL / EXTERNAL / ASYNC_BEFORE / ASYNC_AFTER）。
         *
         * <p>不暴露就分不清表里一堆 job 哪些是"等时间到"、哪些是"等外部 worker 领"、
         * 哪些是"等异步续跑" —— 排障时只能挨个点开看，碰到没到期的更是完全无从判断。
         * 这是持久化层早就有的字段（决定执行器捞不捞它），只是漏进了对外视图。
         */
        private String type;

        /** 外部任务的主题名；非外部任务为 null。 */
        private String topic;

        /** 外部任务当前锁在谁手里；为 null/空表示可被领取。 */
        private String lockedBy;

        private long duedate;
        private int retries;
        private boolean retriesExhausted;
        private String exceptionMessage;
        private long createTime;
        private long lastFailureTime;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
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

        public long getDuedate() {
            return duedate;
        }

        public void setDuedate(long duedate) {
            this.duedate = duedate;
        }

        public int getRetries() {
            return retries;
        }

        public void setRetries(int retries) {
            this.retries = retries;
        }

        public boolean isRetriesExhausted() {
            return retriesExhausted;
        }

        public void setRetriesExhausted(boolean retriesExhausted) {
            this.retriesExhausted = retriesExhausted;
        }

        public String getExceptionMessage() {
            return exceptionMessage;
        }

        public void setExceptionMessage(String exceptionMessage) {
            this.exceptionMessage = exceptionMessage;
        }

        public long getCreateTime() {
            return createTime;
        }

        public void setCreateTime(long createTime) {
            this.createTime = createTime;
        }

        public long getLastFailureTime() {
            return lastFailureTime;
        }

        public void setLastFailureTime(long lastFailureTime) {
            this.lastFailureTime = lastFailureTime;
        }
    }

    /**
     * 变量变更审计的一条记录。
     *
     * <p>只解析引擎自己保证的那一段：审计内容由引擎写成 {@code 变量名: 变更描述}，
     * 所以冒号前是变量名、后面原样是变更描述。
     *
     * <p><b>不拆 oldValue / newValue</b>：变更描述里的 {@code " -> "} 是分隔符，
     * 而变量的值本身完全可能含这个串（remark = "紧急 -> 明天"）。按分隔符硬拆就会
     * 悄悄切错值 —— 审计给出错的值比不给值更糟。要自己拆的调用方拿 {@link #content} 去拆，
     * 引擎不替它猜。
     */
    public static class VariableChangeView implements Serializable {

        private static final long serialVersionUID = 1L;

        private String auditId;
        private String processInstanceId;
        private String taskId;
        private String changedBy;
        private String variableName;
        private String change;
        private String content;
        private long time;

        public String getAuditId() {
            return auditId;
        }

        public void setAuditId(String auditId) {
            this.auditId = auditId;
        }

        public String getProcessInstanceId() {
            return processInstanceId;
        }

        public void setProcessInstanceId(String processInstanceId) {
            this.processInstanceId = processInstanceId;
        }

        public String getTaskId() {
            return taskId;
        }

        public void setTaskId(String taskId) {
            this.taskId = taskId;
        }

        public String getChangedBy() {
            return changedBy;
        }

        public void setChangedBy(String changedBy) {
            this.changedBy = changedBy;
        }

        public String getVariableName() {
            return variableName;
        }

        public void setVariableName(String variableName) {
            this.variableName = variableName;
        }

        public String getChange() {
            return change;
        }

        public void setChange(String change) {
            this.change = change;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public long getTime() {
            return time;
        }

        public void setTime(long time) {
            this.time = time;
        }
    }
}
