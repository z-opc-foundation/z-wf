package com.zifang.z.wf.web.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    /**
     * 按消息 / 信号启动流程。
     *
     * <p>不同时给 {@code messageName} 与 {@code signalName}：那是两种不同的触发源，
     * 都填了说明调用方自己也没想清楚要哪一种，此时挑一个执行等于替它做决定。
     */
    public static class StartByEvent implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 消息名（对应 startEvent 上的 messageRef）。与 {@link #signalName} 二选一。 */
        private String messageName;

        /** 信号名（对应 startEvent 上的 signalRef）。与 {@link #messageName} 二选一。 */
        private String signalName;

        /**
         * 流程定义 key。
         *
         * <p>不填则跨定义查找订阅了该事件的流程。填了就只在这一份里找 ——
         * 同名订阅有多个时用它消除歧义。
         */
        private String definitionKey;

        /** 业务键（单号）。 */
        private String businessKey;

        /**
         * 发起人。
         *
         * <p>由外部系统触发时通常没有"人"，留空即可。
         * 注意它<b>不等于</b>流程里的当前办理人 —— 后者由流程自己在第一个任务节点决定。
         */
        private String userId;

        private String deptId;

        private Map<String, Object> variables = new HashMap<>();

        public String getMessageName() {
            return messageName;
        }

        public void setMessageName(String messageName) {
            this.messageName = messageName;
        }

        public String getSignalName() {
            return signalName;
        }

        public void setSignalName(String signalName) {
            this.signalName = signalName;
        }

        public String getDefinitionKey() {
            return definitionKey;
        }

        public void setDefinitionKey(String definitionKey) {
            this.definitionKey = definitionKey;
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
    /**
     * 分支级变量操作（写在某条并行分支上，只对该分支的条件表达式可见）。
     *
     * <p>与 {@link VariableOperation} 分成两个 DTO 而不是加个 scope 字段：
     * 两者作用的对象<b>生命周期不同</b> —— 流程变量跟着实例走，分支变量跟着那条
     * 分支走、分支结束就没了。合成一个带 scope 的 DTO，调用方很容易以为
     * scope 只是换个存储位置，而误用之后症状是「变量莫名其妙失效了」。
     *
     * <p><b>入参用 taskId 而不是 executionId</b>：执行树是引擎内部结构，
     * 仓里有测试钉着「任务响应里不得出现 executionId」。而任务天然绑定一条 token，
     * 调用方手上有的恰恰是 taskId。服务层 {@code WfVariableService#executionIdOfTask}
     * 负责换算，换算不了（流程级待办那种）时当场报错。
     */
    public static class LocalVariableOperation implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 任务 id；服务层据此定位它所在的 token。 */
        private String taskId;

        /** 操作人。 */
        private String userId;

        private Map<String, Object> values = new HashMap<>();

        private List<String> names = new ArrayList<>();

        /** true = 删除 {@link #names}；false = 写入 {@link #values}。 */
        private boolean remove;

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

        public Map<String, Object> getValues() {
            return values;
        }

        public void setValues(Map<String, Object> values) {
            this.values = values == null ? new HashMap<String, Object>() : values;
        }

        public List<String> getNames() {
            return names;
        }

        public void setNames(List<String> names) {
            this.names = names == null ? new ArrayList<String>() : names;
        }

        public boolean isRemove() {
            return remove;
        }

        public void setRemove(boolean remove) {
            this.remove = remove;
        }
    }

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

    /**
     * 消息关联：不给流程实例 id，由引擎自己找到那条该被唤醒的流程。
     *
     * <p>与 {@link EventDelivery} 的差别就在 {@code processInstanceId} 上 ——
     * 那个是"我知道是哪一条"，这个是"我只知道业务键与业务字段，你去找"。
     * 两者字段不共用一个类：{@code EventDelivery} 的 variables 是
     * <b>随事件带进流程</b>的载荷，本类的 variables 是
     * <b>用来匹配</b>的条件，合并成一个类会让调用方分不清自己那份
     * variables 到底会被写入还是只被读取。
     */
    public static class MessageCorrelation implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 消息名，对应 {@code messageRef}。必填。 */
        private String messageName;

        /** 可选，进一步限定在某个流程实例内。 */
        private String processInstanceId;

        /** 可选，按业务键匹配（{@code WfProcessInstance#getBusinessKey}）。 */
        private String businessKey;

        /** 可选，按流程定义 key 匹配。 */
        private String definitionKey;

        /** 可选，流程级变量相等匹配；<b>只读不写</b>。 */
        private Map<String, Object> variables;

        /** 可选，执行级变量相等匹配；<b>只读不写</b>。 */
        private Map<String, Object> localVariables;

        /** 触发人，记入评论与轨迹。 */
        private String userId;

        /** 写进评论的一句话说明。 */
        private String comment;

        public String getMessageName() {
            return messageName;
        }

        public void setMessageName(String messageName) {
            this.messageName = messageName;
        }

        public String getProcessInstanceId() {
            return processInstanceId;
        }

        public void setProcessInstanceId(String processInstanceId) {
            this.processInstanceId = processInstanceId;
        }

        public String getBusinessKey() {
            return businessKey;
        }

        public void setBusinessKey(String businessKey) {
            this.businessKey = businessKey;
        }

        public String getDefinitionKey() {
            return definitionKey;
        }

        public void setDefinitionKey(String definitionKey) {
            this.definitionKey = definitionKey;
        }

        public Map<String, Object> getVariables() {
            return variables;
        }

        public void setVariables(Map<String, Object> variables) {
            this.variables = variables;
        }

        public Map<String, Object> getLocalVariables() {
            return localVariables;
        }

        public void setLocalVariables(Map<String, Object> localVariables) {
            this.localVariables = localVariables;
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
    }

    /**
     * 保存筛选器的新建 / 修改。
     *
     * <p>新建与修改共用一个 DTO，靠"有没有 filterId"区分 ——
     * 两者字段完全一样（除了 id 在 URL 上），拆成两个类只会让调用方
     * 在"我这次该用哪个"上多花一次心思。
     *
     * <p>{@code properties} 是 {@code Map<String, Object>} 而不是
     * {@code Map<String, String>}：JSON 里 {@code true} 与 {@code 500}
     * 天生就是布尔与数字，写成 String 的话 Jackson 会照收，
     * 错误就推迟到服务层解析时才炸。声明成 Object 让**类型错误在反序列化时
     * 就暴露**，比推迟到"某天有人跑这张筛选器"要好。
     */
    public static class FilterOperation implements Serializable {

        private static final long serialVersionUID = 1L;

        private String name;

        /** {@code task} / {@code processInstance} / {@code incident}。 */
        private String resourceType;

        private String owner;

        private Map<String, Object> properties = new LinkedHashMap<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getResourceType() {
            return resourceType;
        }

        public void setResourceType(String resourceType) {
            this.resourceType = resourceType;
        }

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        public Map<String, Object> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, Object> properties) {
            this.properties = properties == null ? new LinkedHashMap<>() : properties;
        }
    }
}
