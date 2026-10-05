package com.zifang.z.wf.web.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 请求体集合。
 *
 * @author zifang
 */
public final class WfRequests {

    private WfRequests() {
    }

    /**
     * 发起流程。
     */
    public static class StartProcess implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 流程定义 key。 */
        private String definitionKey;

        /** 指定版本；不填用最新。 */
        private Integer version;

        /** 业务键（单号）。 */
        private String businessKey;

        /** 发起人。 */
        private String userId;

        /** 发起人部门。 */
        private String deptId;

        /** 流程变量。 */
        private Map<String, Object> variables = new HashMap<>();

        public String getDefinitionKey() {
            return definitionKey;
        }

        public void setDefinitionKey(String definitionKey) {
            this.definitionKey = definitionKey;
        }

        public Integer getVersion() {
            return version;
        }

        public void setVersion(Integer version) {
            this.version = version;
        }

        public String getBusinessKey() {
            return businessKey;
        }

        public void setBusinessKey(String businessKey) {
            this.businessKey = businessKey;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getDeptId() {
            return deptId;
        }

        public void setDeptId(String deptId) {
            this.deptId = deptId;
        }

        public Map<String, Object> getVariables() {
            return variables;
        }

        public void setVariables(Map<String, Object> variables) {
            this.variables = variables == null ? new HashMap<String, Object>() : variables;
        }
    }

    /**
     * 办理任务（审批通过 / 驳回）。
     */
    public static class CompleteTask implements Serializable {

        private static final long serialVersionUID = 1L;

        private String taskId;

        /** 办结人。 */
        private String userId;

        /** 审批意见。 */
        private String comment;

        /** 本次带入的变量（如 approved=false 会影响网关走向）。 */
        private Map<String, Object> variables = new HashMap<>();

        public String getTaskId() {
            return taskId;
        }

        public void setTaskId(String taskId) {
            this.taskId = taskId;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }

        public Map<String, Object> getVariables() {
            return variables;
        }

        public void setVariables(Map<String, Object> variables) {
            this.variables = variables == null ? new HashMap<String, Object>() : variables;
        }
    }

    /**
     * 任务流转操作（转办 / 委派 / 认领 / 撤回 / 跳转 / 强制完成 共用）。
     */
    /**
     * 变量变更请求。
     *
     * <p>{@code remove} 为 true 时忽略 {@code values}，只删 {@code names} 里的变量。
     * 删除单独走一个字段而不是约定"值为 null 即删除"，是因为后者一旦被调用方
     * 误用，赋值会静默变成删除，而被删掉的变量会让引用它的条件表达式
     * 走 fail-closed 分支改变流程走向。
     */
    public static class VariableOperation implements Serializable {

        private static final long serialVersionUID = 1L;

        private String processInstanceId;
        private String userId;
        private Map<String, Object> values = new HashMap<>();
        private List<String> names = new ArrayList<>();
        private boolean remove;

        public String getProcessInstanceId() {
            return processInstanceId;
        }

        public void setProcessInstanceId(String processInstanceId) {
            this.processInstanceId = processInstanceId;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public Map<String, Object> getValues() {
            return values;
        }

        public void setValues(Map<String, Object> values) {
            this.values = values;
        }

        public List<String> getNames() {
            return names;
        }

        public void setNames(List<String> names) {
            this.names = names;
        }

        public boolean isRemove() {
            return remove;
        }

        public void setRemove(boolean remove) {
            this.remove = remove;
        }
    }

    public static class TaskOperation implements Serializable {
        private static final long serialVersionUID = 1L;

        private String taskId;

        /** 操作人。 */
        private String userId;

        /** 目标人（转办/委派/强制完成时使用）。 */
        private String targetUserId;

        /** 目标用户所属组（认领时用）。 */
        private List<String> targetGroups;

        /** 原因 / 意见。 */
        private String comment;

        /** 跳转目标节点（jump 时使用）。 */
        private String targetActivityId;

        /** 附带变量。 */
        private Map<String, Object> variables = new HashMap<>();

        public String getTaskId() {
            return taskId;
        }

        public void setTaskId(String taskId) {
            this.taskId = taskId;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getTargetUserId() {
            return targetUserId;
        }

        public void setTargetUserId(String targetUserId) {
            this.targetUserId = targetUserId;
        }

        public List<String> getTargetGroups() {
            return targetGroups;
        }

        public void setTargetGroups(List<String> targetGroups) {
            this.targetGroups = targetGroups;
        }

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }

        public String getTargetActivityId() {
            return targetActivityId;
        }

        public void setTargetActivityId(String targetActivityId) {
            this.targetActivityId = targetActivityId;
        }

        public Map<String, Object> getVariables() {
            return variables;
        }

        public void setVariables(Map<String, Object> variables) {
            this.variables = variables == null ? new HashMap<String, Object>() : variables;
        }
    }

    /**
     * 流程操作（挂起 / 激活 / 终止 / 评论 共用）。
     */
    public static class ProcessOperation implements Serializable {

        private static final long serialVersionUID = 1L;

        private String processInstanceId;

        private String userId;

        private String reason;

        private String content;

        public String getProcessInstanceId() {
            return processInstanceId;
        }

        public void setProcessInstanceId(String processInstanceId) {
            this.processInstanceId = processInstanceId;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }

    /**
     * 投递消息 / 广播信号。
     *
     * <p>事件网关、消息边界与接收任务共用这一个请求体：三者等的是同一个"事件名"，
     * 拆成三个 DTO 只会让调用方为了换个等待方式而改整个请求结构。
     */
    /**
     * 实例迁移。
     *
     * <p>与 {@code TaskOperation} 分开而不是复用：jump 的入口是<b>任务</b>，
     * move 的入口是<b>流程实例</b>，而"没有待办可指"的流程恰恰是 move 存在的理由 ——
     * 共用一个请求体会让人以为 move 也需要一个 taskId，于是把迁移用在了跳不动的场景上。
     */
    public static class InstanceMigration implements Serializable {

        private static final long serialVersionUID = 1L;

        private String processInstanceId;

        /** 迁移到的目标节点 id，必须在定义里存在。 */
        private String targetActivityId;

        /**
         * 只迁移停在这个节点上的 token。
         *
         * <p>留空表示迁移该实例<b>全部</b>未结束的 token —— 并行分支走到一半时
         * 通常只想迁其中一条，指明源节点能避免把整棵执行树一次端掉。
         */
        private String sourceActivityId;

        private String userId;

        /** 写进评论与轨迹的一句话原因。迁移是运营干预，没有原因事后无法追责。 */
        private String reason;

        private Map<String, Object> variables;

        public String getProcessInstanceId() {
            return processInstanceId;
        }

        public void setProcessInstanceId(String processInstanceId) {
            this.processInstanceId = processInstanceId;
        }

        public String getTargetActivityId() {
            return targetActivityId;
        }

        public void setTargetActivityId(String targetActivityId) {
            this.targetActivityId = targetActivityId;
        }

        public String getSourceActivityId() {
            return sourceActivityId;
        }

        public void setSourceActivityId(String sourceActivityId) {
            this.sourceActivityId = sourceActivityId;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public Map<String, Object> getVariables() {
            return variables;
        }

        public void setVariables(Map<String, Object> variables) {
            this.variables = variables;
        }
    }

    /**
     * 投递消息 / 广播信号。
     *
     * <p>事件网关、消息边界与接收任务共用这一个请求体：三者等的是同一个"事件名"，
     * 拆成三个 DTO 只会让调用方为了换个等待方式而改整个请求结构。
     */
    public static class EventDelivery implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 消息名或信号名，对应 {@code messageRef} / {@code signalRef}。 */
        private String name;

        /** 限定在某个流程实例内；为空表示全局查找。 */
        private String processInstanceId;

        /** 触发人，记入评论与轨迹。 */
        private String userId;

        /** 随事件带进的流程变量。 */
        private Map<String, Object> variables;

        /** 写进评论的一句话说明。 */
        private String comment;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getProcessInstanceId() {
            return processInstanceId;
        }

        public void setProcessInstanceId(String processInstanceId) {
            this.processInstanceId = processInstanceId;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public Map<String, Object> getVariables() {
            return variables;
        }

        public void setVariables(Map<String, Object> variables) {
            this.variables = variables;
        }

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }
    }
}
