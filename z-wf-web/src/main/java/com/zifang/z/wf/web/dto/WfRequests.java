package com.zifang.z.wf.web.dto;

import java.io.Serializable;
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
}
