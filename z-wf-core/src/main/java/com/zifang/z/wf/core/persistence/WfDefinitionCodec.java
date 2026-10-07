package com.zifang.z.wf.core.persistence;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.zifang.util.json.JsonUtil;
import com.zifang.z.wf.core.definition.WfAssociation;
import com.zifang.z.wf.core.definition.WfDataAssociation;
import com.zifang.z.wf.core.definition.WfDataDirection;
import com.zifang.z.wf.core.definition.WfDataObject;
import com.zifang.z.wf.core.definition.WfDataObjectReference;
import com.zifang.z.wf.core.definition.WfDataScope;
import com.zifang.z.wf.core.definition.WfDataStore;
import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfFlow;
import com.zifang.z.wf.core.definition.WfNode;

/**
 * 流程定义的持久化编解码 —— 只搬运"图的形状"，不搬运运行时对象。
 *
 * <p><b>为什么不直接 {@code JsonUtil.toJson(definition)}</b>：
 * {@code WfDefinition} 带 {@code java.util.Date startTime}，而 z-util 的 JsonUtil
 * 把 Date 序列化成 epoch-millis 的 Long，反序列化时<b>无法</b>把 Long 还原成 Date
 * （实测抛 {@code Can not set java.util.Date field ... to java.lang.Long}），
 * {@code fromJsonQuietly} 则静默返回 null ⇒ 读回来的定义是 null ⇒
 * 后续"取最新版本"永远拿不到东西，表现为"明明部署成功了却说流程定义不存在"。
 *
 * <p>部署时间另有 {@code ZWF_DEFINITION.DEPLOY_TIME} 一列承载，不进 JSON 图。
 *
 * <p>这也顺带落实了 web 层那条原则：<b>持久化形状 ≠ 运行时对象</b>。
 * 以后给 {@link WfDefinition} 加运行时字段（缓存、监听器引用……）不会被顺手写进库。
 *
 * @author zifang
 */
public final class WfDefinitionCodec {

    private WfDefinitionCodec() {
    }

    /**
     * 图快照（可序列化，无 Date）。
     */
    public static class Graph implements Serializable {

        private static final long serialVersionUID = 1L;

        private String key;
        private String name;
        private int version;
        private String category;
        private String description;
        private List<GraphNode> nodes = new ArrayList<>();
        private List<GraphFlow> flows = new ArrayList<>();

        /** 关联线（第 37 轮）：补偿边界事件 ↔ 补偿处理器。 */
        private List<GraphAssociation> associations = new ArrayList<>();

        // ---- 数据声明（第 46 轮）----
        // 四个列表都必须落：只落其中三个，重启后数据关联的端点就会解析不到，
        // 而症状是「同一个模型重启前后表现不同」——重启是所有偶发问题的经典替罪羊。
        // 存量定义里这四个键是缺的，读回来按空列表处理（老定义照常可用）。
        private List<GraphDataObject> dataObjects = new ArrayList<>();

        private List<GraphDataObjectReference> dataObjectReferences = new ArrayList<>();

        private List<GraphDataStore> dataStores = new ArrayList<>();

        private List<GraphDataAssociation> dataAssociations = new ArrayList<>();

        public List<GraphAssociation> getAssociations() {
            return associations;
        }

        public void setAssociations(List<GraphAssociation> associations) {
            this.associations = associations;
        }

        public List<GraphDataObject> getDataObjects() {
            return dataObjects;
        }

        public void setDataObjects(List<GraphDataObject> dataObjects) {
            this.dataObjects = dataObjects;
        }

        public List<GraphDataObjectReference> getDataObjectReferences() {
            return dataObjectReferences;
        }

        public void setDataObjectReferences(List<GraphDataObjectReference> dataObjectReferences) {
            this.dataObjectReferences = dataObjectReferences;
        }

        public List<GraphDataStore> getDataStores() {
            return dataStores;
        }

        public void setDataStores(List<GraphDataStore> dataStores) {
            this.dataStores = dataStores;
        }

        public List<GraphDataAssociation> getDataAssociations() {
            return dataAssociations;
        }

        public void setDataAssociations(List<GraphDataAssociation> dataAssociations) {
            this.dataAssociations = dataAssociations;
        }

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

        public List<GraphNode> getNodes() {
            return nodes;
        }

        public void setNodes(List<GraphNode> nodes) {
            this.nodes = nodes == null ? new ArrayList<GraphNode>() : nodes;
        }

        public List<GraphFlow> getFlows() {
            return flows;
        }

        public void setFlows(List<GraphFlow> flows) {
            this.flows = flows == null ? new ArrayList<GraphFlow>() : flows;
        }
    }

    /**
     * 关联线快照（第 37 轮）。
     *
     * <p>刻意<b>不带 properties</b>：{@link com.zifang.z.wf.core.definition.WfAssociation}
     * 只有三个字段，且语义固定（BPMN 规范里 association 就这三个属性）。
     * 给它挂一个永远空的扩展槽，是为了让「将来可能用到」这种假设先落进数据结构 ——
     * 真要加时补上，那时该连着它的往返判据一起补。
     */
    public static class GraphAssociation implements Serializable {

        private static final long serialVersionUID = 1L;

        private String id;
        private String sourceRef;
        private String targetRef;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getSourceRef() {
            return sourceRef;
        }

        public void setSourceRef(String sourceRef) {
            this.sourceRef = sourceRef;
        }

        public String getTargetRef() {
            return targetRef;
        }

        public void setTargetRef(String targetRef) {
            this.targetRef = targetRef;
        }
    }

    // ==================== 数据声明快照（第 46 轮） ====================

    /**
     * {@code <dataObject>} 快照。
     *
     * <p>{@code scope} 存成<b>枚举名</b>（{@code PROCESS} / {@code STAGE}）而不是小写：
     * 枚举名是它在本仓的权威写法，读回来直接 {@code valueOf} 即可，
     * 换成小写就得再写一张对照表，而那张表迟早与枚举脱节。
     * 读不认识的枚举名按 {@code PROCESS} 兜底（存量的、别处手写的）而不是抛 ——
     * 数据声明不参与任何执行判断，为一个显示字段挡住整份定义的加载不划算。
     */
    public static class GraphDataObject implements Serializable {

        private static final long serialVersionUID = 1L;

        private String id;
        private String name;
        private String itemSubjectRef;
        private String scope;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getItemSubjectRef() {
            return itemSubjectRef;
        }

        public void setItemSubjectRef(String itemSubjectRef) {
            this.itemSubjectRef = itemSubjectRef;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }
    }

    /** 数据引用快照（含 ioSpecification 的 dataInput / dataOutput）。 */
    public static class GraphDataObjectReference implements Serializable {

        private static final long serialVersionUID = 1L;

        private String id;
        private String name;
        private String dataObjectRef;
        private String itemSubjectRef;
        private String kind;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDataObjectRef() {
            return dataObjectRef;
        }

        public void setDataObjectRef(String dataObjectRef) {
            this.dataObjectRef = dataObjectRef;
        }

        public String getItemSubjectRef() {
            return itemSubjectRef;
        }

        public void setItemSubjectRef(String itemSubjectRef) {
            this.itemSubjectRef = itemSubjectRef;
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }
    }

    /** {@code <dataStore>} 快照。 */
    public static class GraphDataStore implements Serializable {

        private static final long serialVersionUID = 1L;

        private String id;
        private String name;
        private Integer capacity;
        private boolean unlimited;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getCapacity() {
            return capacity;
        }

        public void setCapacity(Integer capacity) {
            this.capacity = capacity;
        }

        public boolean isUnlimited() {
            return unlimited;
        }

        public void setUnlimited(boolean unlimited) {
            this.unlimited = unlimited;
        }
    }

    /**
     * 数据关联快照。
     *
     * <p>{@code direction} 与 {@code kind} 一样存枚举名。
     *
     * <p>{@code assignments} 存成 {@link List}：BPMN 允许一条关联带多个
     * {@code <assignment>}，拼成一段文本就分不出"两条"与"一条里的两个 to"。
     */
    public static class GraphDataAssociation implements Serializable {

        private static final long serialVersionUID = 1L;

        private String id;
        private String direction;
        private String ownerId;
        private String sourceRef;
        private String targetRef;
        private String transformation;
        private List<String> assignments = new ArrayList<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getDirection() {
            return direction;
        }

        public void setDirection(String direction) {
            this.direction = direction;
        }

        public String getOwnerId() {
            return ownerId;
        }

        public void setOwnerId(String ownerId) {
            this.ownerId = ownerId;
        }

        public String getSourceRef() {
            return sourceRef;
        }

        public void setSourceRef(String sourceRef) {
            this.sourceRef = sourceRef;
        }

        public String getTargetRef() {
            return targetRef;
        }

        public void setTargetRef(String targetRef) {
            this.targetRef = targetRef;
        }

        public String getTransformation() {
            return transformation;
        }

        public void setTransformation(String transformation) {
            this.transformation = transformation;
        }

        public List<String> getAssignments() {
            return assignments;
        }

        public void setAssignments(List<String> assignments) {
            this.assignments = assignments == null ? new ArrayList<String>() : assignments;
        }
    }

    /**
     * 节点快照。
     */
    public static class GraphNode implements Serializable {

        private static final long serialVersionUID = 1L;

        private String id;
        private String name;
        private String type;
        private String category;
        private String formKey;
        private String assignee;
        private List<String> candidateUsers = new ArrayList<>();
        private List<String> candidateGroups = new ArrayList<>();
        private int priority = WfNode.DEFAULT_PRIORITY;
        private String dueDateDuration;
        private List<String> requiredVariables = new ArrayList<>();
        private String delegateClass;
        private String delegateExpression;
        private String script;
        private String messageName;
        private String signalName;
        private String escalationCode;
        private String linkName;
        private String topic;
        private String caseVariable;

        private String complexJoin;
        private String activationCondition;
        private boolean asyncBefore;
        private boolean asyncAfter;

        /**
         * 用 {@link Boolean} 而不是 {@code boolean}：<b>存量 JSON 里没有这个键</b>。
         *
         * <p>解码成 {@code boolean} 的话 Jackson 把它当 {@code false} ——
         * 也就是"全部不互斥"。于是升级那一刻起，所有已部署流程里的异步 job
         * 一起失去 Camunda 默认给的那层保护，而日志里什么异常都没有。
         * 兜回 {@code true} 是唯一与默认值一致的选择。
         */
        private Boolean exclusive;
        private String resultVariable;
        private String decisionRef;
        private String decisionRefBinding;
        private String decisionRefVersion;
        private String mapDecisionResult;
        private boolean multiInstance;
        private String loopCardinality;
        private String loopCollection;
        private String loopElement;
        private String completionCondition;
        private boolean sequential;
        private String loopAssignees;
        private String errorCode;
        private boolean nonInterrupting;
        private boolean parallelMultiple;
        private String attachedToRef;
        private String timerType;
        private String timerExpression;
        private String calledElementKey;
        private String resultExpression;
        private Map<String, Object> properties = new HashMap<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getFormKey() {
            return formKey;
        }

        public void setFormKey(String formKey) {
            this.formKey = formKey;
        }

        public String getAssignee() {
            return assignee;
        }

        public void setAssignee(String assignee) {
            this.assignee = assignee;
        }

        public List<String> getCandidateUsers() {
            return candidateUsers;
        }

        public void setCandidateUsers(List<String> candidateUsers) {
            this.candidateUsers = candidateUsers == null ? new ArrayList<String>() : candidateUsers;
        }

        public List<String> getCandidateGroups() {
            return candidateGroups;
        }

        public void setCandidateGroups(List<String> candidateGroups) {
            this.candidateGroups = candidateGroups == null ? new ArrayList<String>() : candidateGroups;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public String getDueDateDuration() {
            return dueDateDuration;
        }

        public void setDueDateDuration(String dueDateDuration) {
            this.dueDateDuration = dueDateDuration;
        }

        public List<String> getRequiredVariables() {
            return requiredVariables;
        }

        public void setRequiredVariables(List<String> requiredVariables) {
            this.requiredVariables = requiredVariables == null
                    ? new ArrayList<String>() : requiredVariables;
        }

        public String getDelegateClass() {
            return delegateClass;
        }

        public void setDelegateClass(String delegateClass) {
            this.delegateClass = delegateClass;
        }

        public String getDelegateExpression() {
            return delegateExpression;
        }

        public void setDelegateExpression(String delegateExpression) {
            this.delegateExpression = delegateExpression;
        }

        public String getScript() {
            return script;
        }

        public void setScript(String script) {
            this.script = script;
        }

        public String getMessageName() {
            return messageName;
        }

        public String getSignalName() {
            return signalName;
        }

        public void setSignalName(String signalName) {
            this.signalName = signalName;
        }

        public String getEscalationCode() {
            return escalationCode;
        }

        public void setEscalationCode(String escalationCode) {
            this.escalationCode = escalationCode;
        }

        public String getLinkName() {
            return linkName;
        }

        public void setLinkName(String linkName) {
            this.linkName = linkName;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getResultVariable() {
            return resultVariable;
        }

        public String getCaseVariable() {
            return caseVariable;
        }

        public void setCaseVariable(String caseVariable) {
            this.caseVariable = caseVariable;
        }

        public String getComplexJoin() {
            return complexJoin;
        }

        public void setComplexJoin(String complexJoin) {
            this.complexJoin = complexJoin;
        }

        public String getActivationCondition() {
            return activationCondition;
        }

        public void setActivationCondition(String activationCondition) {
            this.activationCondition = activationCondition;
        }

        public boolean isAsyncBefore() {
            return asyncBefore;
        }

        public void setAsyncBefore(boolean asyncBefore) {
            this.asyncBefore = asyncBefore;
        }

        public boolean isAsyncAfter() {
            return asyncAfter;
        }

        public void setAsyncAfter(boolean asyncAfter) {
            this.asyncAfter = asyncAfter;
        }

        /** 存量为 {@code null}（老 JSON 无此键），由解码处兜成默认的 {@code true}。 */
        public Boolean getExclusive() {
            return exclusive;
        }

        public void setExclusive(Boolean exclusive) {
            this.exclusive = exclusive;
        }

        public boolean isMultiInstance() {
            return multiInstance;
        }

        public void setMultiInstance(boolean multiInstance) {
            this.multiInstance = multiInstance;
        }

        public String getLoopCardinality() {
            return loopCardinality;
        }

        public void setLoopCardinality(String loopCardinality) {
            this.loopCardinality = loopCardinality;
        }

        public String getLoopCollection() {
            return loopCollection;
        }

        public void setLoopCollection(String loopCollection) {
            this.loopCollection = loopCollection;
        }

        public String getLoopElement() {
            return loopElement;
        }

        public void setLoopElement(String loopElement) {
            this.loopElement = loopElement;
        }

        public String getCompletionCondition() {
            return completionCondition;
        }

        public void setCompletionCondition(String completionCondition) {
            this.completionCondition = completionCondition;
        }

        public boolean isSequential() {
            return sequential;
        }

        public void setSequential(boolean sequential) {
            this.sequential = sequential;
        }

        public String getLoopAssignees() {
            return loopAssignees;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public void setErrorCode(String errorCode) {
            this.errorCode = errorCode;
        }

        public boolean isNonInterrupting() {
            return nonInterrupting;
        }

        public void setNonInterrupting(boolean nonInterrupting) {
            this.nonInterrupting = nonInterrupting;
        }

        public boolean isParallelMultiple() {
            return parallelMultiple;
        }

        public void setParallelMultiple(boolean parallelMultiple) {
            this.parallelMultiple = parallelMultiple;
        }

        public String getAttachedToRef() {
            return attachedToRef;
        }

        public void setAttachedToRef(String attachedToRef) {
            this.attachedToRef = attachedToRef;
        }

        /**
         * 定时器类型存字符串而不是枚举。
         *
         * <p>与 {@link #type} 同样处理：DB 里的定义要跨版本可读，
         * 枚举改名不该让老数据读不出来。
         */
        public String getTimerType() {
            return timerType;
        }

        public void setTimerType(String timerType) {
            this.timerType = timerType;
        }

        public String getTimerExpression() {
            return timerExpression;
        }

        public void setTimerExpression(String timerExpression) {
            this.timerExpression = timerExpression;
        }

        public void setLoopAssignees(String loopAssignees) {
            this.loopAssignees = loopAssignees;
        }

        public void setResultVariable(String resultVariable) {
            this.resultVariable = resultVariable;
        }

        public String getDecisionRef() {
            return decisionRef;
        }

        public void setDecisionRef(String decisionRef) {
            this.decisionRef = decisionRef;
        }

        public String getDecisionRefBinding() {
            return decisionRefBinding;
        }

        public void setDecisionRefBinding(String decisionRefBinding) {
            this.decisionRefBinding = decisionRefBinding;
        }

        public String getDecisionRefVersion() {
            return decisionRefVersion;
        }

        public void setDecisionRefVersion(String decisionRefVersion) {
            this.decisionRefVersion = decisionRefVersion;
        }

        public String getMapDecisionResult() {
            return mapDecisionResult;
        }

        public void setMapDecisionResult(String mapDecisionResult) {
            this.mapDecisionResult = mapDecisionResult;
        }

        public void setMessageName(String messageName) {
            this.messageName = messageName;
        }

        public String getCalledElementKey() {
            return calledElementKey;
        }

        public void setCalledElementKey(String calledElementKey) {
            this.calledElementKey = calledElementKey;
        }

        public String getResultExpression() {
            return resultExpression;
        }

        public void setResultExpression(String resultExpression) {
            this.resultExpression = resultExpression;
        }

        public Map<String, Object> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, Object> properties) {
            this.properties = properties == null ? new HashMap<String, Object>() : properties;
        }
    }

    /**
     * 连线快照。
     */
    public static class GraphFlow implements Serializable {

        private static final long serialVersionUID = 1L;

        private String id;
        private String name;
        private String sourceRef;
        private String targetRef;
        private String conditionExpression;
        private String caseValue;
        private boolean defaultFlow;
        private Map<String, Object> properties = new HashMap<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSourceRef() {
            return sourceRef;
        }

        public void setSourceRef(String sourceRef) {
            this.sourceRef = sourceRef;
        }

        public String getTargetRef() {
            return targetRef;
        }

        public void setTargetRef(String targetRef) {
            this.targetRef = targetRef;
        }

        public String getConditionExpression() {
            return conditionExpression;
        }

        public String getCaseValue() {
            return caseValue;
        }

        public void setCaseValue(String caseValue) {
            this.caseValue = caseValue;
        }

        public void setConditionExpression(String conditionExpression) {
            this.conditionExpression = conditionExpression;
        }

        public boolean isDefaultFlow() {
            return defaultFlow;
        }

        public void setDefaultFlow(boolean defaultFlow) {
            this.defaultFlow = defaultFlow;
        }

        public Map<String, Object> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, Object> properties) {
            this.properties = properties == null ? new HashMap<String, Object>() : properties;
        }
    }

    // ==================== 编解码 ====================

    /**
     * 定义 → JSON 文本。
     */
    public static String encode(WfDefinition definition) {
        Graph graph = new Graph();
        graph.setKey(definition.getKey());
        graph.setName(definition.getName());
        graph.setVersion(definition.getVersion());
        graph.setCategory(definition.getCategory());
        graph.setDescription(definition.getDescription());

        List<GraphNode> nodes = new ArrayList<>();
        for (WfNode node : definition.getNodes()) {
            if (node == null) {
                continue;
            }
            GraphNode gn = new GraphNode();
            gn.setId(node.getId());
            gn.setName(node.getName());
            // 存 BPMN 名而不是枚举名：枚举改名不会让老数据读不出来
            gn.setType(node.getType() == null ? "task" : node.getType().bpmnName());
            gn.setCategory(node.getCategory());
            gn.setFormKey(node.getFormKey());
            gn.setAssignee(node.getAssignee());
            gn.setCandidateUsers(new ArrayList<>(node.getCandidateUsers()));
            gn.setCandidateGroups(new ArrayList<>(node.getCandidateGroups()));
            gn.setPriority(node.getPriority());
            gn.setDueDateDuration(node.getDueDateDuration());
            gn.setRequiredVariables(new ArrayList<>(node.getRequiredVariables()));
            gn.setDelegateClass(node.getDelegateClass());
            gn.setDelegateExpression(node.getDelegateExpression());
            gn.setScript(node.getScript());
            gn.setMessageName(node.getMessageName());
            gn.setSignalName(node.getSignalName());
            gn.setEscalationCode(node.getEscalationCode());
            gn.setLinkName(node.getLinkName());
            gn.setTopic(node.getTopic());
            gn.setResultVariable(node.getResultVariable());
            gn.setDecisionRef(node.getDecisionRef());
            gn.setDecisionRefBinding(node.getDecisionRefBinding());
            gn.setDecisionRefVersion(node.getDecisionRefVersion());
            gn.setMapDecisionResult(node.getMapDecisionResult());
            gn.setCaseVariable(node.getCaseVariable());
            gn.setComplexJoin(node.getComplexJoin());
        gn.setActivationCondition(node.getActivationCondition());
            gn.setAsyncBefore(node.isAsyncBefore());
            gn.setAsyncAfter(node.isAsyncAfter());
            gn.setExclusive(node.isExclusive());
            gn.setMultiInstance(node.isMultiInstance());
            gn.setLoopCardinality(node.getLoopCardinality());
            gn.setLoopCollection(node.getLoopCollection());
            gn.setLoopElement(node.getLoopElement());
            gn.setCompletionCondition(node.getCompletionCondition());
            gn.setSequential(node.isSequential());
            gn.setLoopAssignees(node.getLoopAssignees());
            gn.setErrorCode(node.getErrorCode());
            gn.setNonInterrupting(node.isNonInterrupting());
            gn.setParallelMultiple(node.isParallelMultiple());
            gn.setAttachedToRef(node.getAttachedToRef());
            gn.setTimerType(node.getTimerType() == null ? null : node.getTimerType().name());
            gn.setTimerExpression(node.getTimerExpression());
            gn.setCalledElementKey(node.getCalledElementKey());
            gn.setResultExpression(node.getResultExpression());
            gn.setProperties(new HashMap<>(node.getProperties()));
            nodes.add(gn);
        }
        graph.setNodes(nodes);

        List<GraphFlow> flows = new ArrayList<>();
        for (WfFlow flow : definition.getFlows()) {
            if (flow == null) {
                continue;
            }
            GraphFlow gf = new GraphFlow();
            gf.setId(flow.getId());
            gf.setName(flow.getName());
            gf.setSourceRef(flow.getSourceRef());
            gf.setTargetRef(flow.getTargetRef());
            gf.setConditionExpression(flow.getConditionExpression());
            gf.setCaseValue(flow.getCaseValue());
            gf.setDefaultFlow(flow.isDefaultFlow());
            gf.setProperties(new HashMap<>(flow.getProperties()));
            flows.add(gf);
        }
        graph.setFlows(flows);

        // 关联线（第 37 轮）。不落库的后果是重启后补偿边界事件找不到处理器 ——
        // 而症状是「补偿安静地不发生」，流程照常跑完，没有任何报错。
        List<GraphAssociation> associations = new ArrayList<>();
        for (WfAssociation association : definition.getAssociations()) {
            if (association == null || association.getSourceRef() == null
                    || association.getTargetRef() == null) {
                continue;
            }
            GraphAssociation ga = new GraphAssociation();
            ga.setId(association.getId());
            ga.setSourceRef(association.getSourceRef());
            ga.setTargetRef(association.getTargetRef());
            associations.add(ga);
        }
        graph.setAssociations(associations);

        // ---- 数据声明（第 46 轮）----
        // 与 associations 同一取舍：必须落库。少落任何一份，重启后
        // 「同一个模型表现不同」，而重启是所有偶发问题的经典替罪羊。
        List<GraphDataObject> dataObjects = new ArrayList<>();
        for (WfDataObject dataObject : definition.getDataObjects()) {
            if (dataObject == null || dataObject.getId() == null) {
                continue;
            }
            GraphDataObject gdo = new GraphDataObject();
            gdo.setId(dataObject.getId());
            gdo.setName(dataObject.getName());
            gdo.setItemSubjectRef(dataObject.getItemSubjectRef());
            gdo.setScope(dataObject.getScope() == null ? null : dataObject.getScope().name());
            dataObjects.add(gdo);
        }
        graph.setDataObjects(dataObjects);

        List<GraphDataObjectReference> dataReferences = new ArrayList<>();
        for (WfDataObjectReference reference : definition.getDataObjectReferences()) {
            if (reference == null || reference.getId() == null) {
                continue;
            }
            GraphDataObjectReference gdr = new GraphDataObjectReference();
            gdr.setId(reference.getId());
            gdr.setName(reference.getName());
            gdr.setDataObjectRef(reference.getDataObjectRef());
            gdr.setItemSubjectRef(reference.getItemSubjectRef());
            gdr.setKind(reference.getKind() == null ? null : reference.getKind().name());
            dataReferences.add(gdr);
        }
        graph.setDataObjectReferences(dataReferences);

        List<GraphDataStore> dataStores = new ArrayList<>();
        for (WfDataStore store : definition.getDataStores()) {
            if (store == null || store.getId() == null) {
                continue;
            }
            GraphDataStore gds = new GraphDataStore();
            gds.setId(store.getId());
            gds.setName(store.getName());
            gds.setCapacity(store.getCapacity());
            gds.setUnlimited(store.isUnlimited());
            dataStores.add(gds);
        }
        graph.setDataStores(dataStores);

        List<GraphDataAssociation> dataAssociations = new ArrayList<>();
        for (WfDataAssociation association : definition.getDataAssociations()) {
            if (association == null || association.getId() == null) {
                continue;
            }
            GraphDataAssociation gda = new GraphDataAssociation();
            gda.setId(association.getId());
            gda.setDirection(association.getDirection() == null ? null : association.getDirection().name());
            gda.setOwnerId(association.getOwnerId());
            gda.setSourceRef(association.getSourceRef());
            gda.setTargetRef(association.getTargetRef());
            gda.setTransformation(association.getTransformation());
            gda.setAssignments(new ArrayList<>(association.getAssignments()));
            dataAssociations.add(gda);
        }
        graph.setDataAssociations(dataAssociations);

        return JsonUtil.toJson(graph);
    }

    /**
     * JSON 文本 → 定义（自动建索引）。
     *
     * @return 解析失败返回 {@code null}（调用方按"定义不存在"处理）
     */
    public static WfDefinition decode(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        Graph graph;
        try {
            graph = JsonUtil.fromJson(json, Graph.class);
        } catch (Exception e) {
            return null;
        }
        if (graph == null) {
            return null;
        }

        WfDefinition definition = new WfDefinition();
        definition.setKey(graph.getKey());
        definition.setName(graph.getName());
        definition.setVersion(graph.getVersion());
        definition.setCategory(graph.getCategory());
        definition.setDescription(graph.getDescription());

        List<WfNode> nodes = new ArrayList<>();
        for (GraphNode gn : graph.getNodes()) {
            if (gn == null || gn.getId() == null) {
                continue;
            }
            WfNode node = new WfNode();
            node.setId(gn.getId());
            node.setName(gn.getName());
            node.setType(com.zifang.z.wf.core.definition.WfNodeType.fromBpmn(gn.getType()));
            node.setCategory(gn.getCategory());
            node.setFormKey(gn.getFormKey());
            node.setAssignee(gn.getAssignee());
            node.setCandidateUsers(new ArrayList<>(gn.getCandidateUsers()));
            node.setCandidateGroups(new ArrayList<>(gn.getCandidateGroups()));
            node.setPriority(gn.getPriority());
            node.setDueDateDuration(gn.getDueDateDuration());
            node.setRequiredVariables(new ArrayList<>(gn.getRequiredVariables()));
            node.setDelegateClass(gn.getDelegateClass());
            node.setDelegateExpression(gn.getDelegateExpression());
            node.setScript(gn.getScript());
            node.setMessageName(gn.getMessageName());
            node.setSignalName(gn.getSignalName());
            node.setEscalationCode(gn.getEscalationCode());
            node.setLinkName(gn.getLinkName());
            node.setTopic(gn.getTopic());
            node.setResultVariable(gn.getResultVariable());
            node.setDecisionRef(gn.getDecisionRef());
            node.setDecisionRefBinding(gn.getDecisionRefBinding());
            node.setDecisionRefVersion(gn.getDecisionRefVersion());
            node.setMapDecisionResult(gn.getMapDecisionResult());
            node.setCaseVariable(gn.getCaseVariable());
            node.setComplexJoin(gn.getComplexJoin());
        node.setActivationCondition(gn.getActivationCondition());
            node.setAsyncBefore(gn.isAsyncBefore());
            node.setAsyncAfter(gn.isAsyncAfter());
            // 存量 JSON 没有这个键（getExclusive() 返回 null）⇒ 兜成默认 true，
            // 与 WfNode#exclusive 的默认值一致 —— 见该字段的注释
            node.setExclusive(gn.getExclusive() == null || gn.getExclusive());
            node.setMultiInstance(gn.isMultiInstance());
            node.setLoopCardinality(gn.getLoopCardinality());
            node.setLoopCollection(gn.getLoopCollection());
            node.setLoopElement(gn.getLoopElement());
            node.setCompletionCondition(gn.getCompletionCondition());
            node.setSequential(gn.isSequential());
            node.setLoopAssignees(gn.getLoopAssignees());
            node.setErrorCode(gn.getErrorCode());
            node.setNonInterrupting(gn.isNonInterrupting());
            node.setParallelMultiple(gn.isParallelMultiple());
            node.setAttachedToRef(gn.getAttachedToRef());
            // 枚举名不认识时保持 null（= 非定时器边界）而不抛：
            // 老库里的定义被新版读、或反之，都不该让引擎整体起不来
            if (gn.getTimerType() != null && !gn.getTimerType().trim().isEmpty()) {
                try {
                    node.setTimerType(com.zifang.z.wf.core.definition.WfTimerType
                            .valueOf(gn.getTimerType().trim()));
                } catch (IllegalArgumentException e) {
                    node.setTimerType(null);
                }
            }
            node.setTimerExpression(gn.getTimerExpression());
            node.setCalledElementKey(gn.getCalledElementKey());
            node.setResultExpression(gn.getResultExpression());
            node.setProperties(new HashMap<>(gn.getProperties()));
            nodes.add(node);
        }
        definition.setNodes(nodes);

        List<WfFlow> flows = new ArrayList<>();
        for (GraphFlow gf : graph.getFlows()) {
            if (gf == null || gf.getSourceRef() == null || gf.getTargetRef() == null) {
                continue;
            }
            WfFlow flow = new WfFlow();
            flow.setId(gf.getId());
            flow.setName(gf.getName());
            flow.setSourceRef(gf.getSourceRef());
            flow.setTargetRef(gf.getTargetRef());
            flow.setConditionExpression(gf.getConditionExpression());
            flow.setCaseValue(gf.getCaseValue());
            flow.setDefaultFlow(gf.isDefaultFlow());
            flow.setProperties(new HashMap<>(gf.getProperties()));
            flows.add(flow);
        }
        definition.setFlows(flows);

        List<WfAssociation> associations = new ArrayList<>();
        // 老库里没有这个字段 ⇒ 读回来是 null ⇒ 按空处理，不抛：
        // 与上面 timerType 的取舍同一条，升级不能让整批存量定义读不出来。
        if (graph.getAssociations() != null) {
            for (GraphAssociation ga : graph.getAssociations()) {
                if (ga == null || ga.getSourceRef() == null || ga.getTargetRef() == null) {
                    continue;
                }
                associations.add(new WfAssociation(ga.getId(), ga.getSourceRef(), ga.getTargetRef()));
            }
        }
        definition.setAssociations(associations);

        // ---- 数据声明（第 46 轮）----
        // 老库里没有这四个键 ⇒ 读回来是 null ⇒ 全部按空处理，不抛：
        // 与上面 associations / timerType 同一条取舍，升级不能让整批存量定义读不出来。
        List<WfDataObject> dataObjects = new ArrayList<>();
        if (graph.getDataObjects() != null) {
            for (GraphDataObject gdo : graph.getDataObjects()) {
                if (gdo == null || gdo.getId() == null) {
                    continue;
                }
                WfDataObject dataObject = new WfDataObject();
                dataObject.setId(gdo.getId());
                dataObject.setName(gdo.getName());
                dataObject.setItemSubjectRef(gdo.getItemSubjectRef());
                dataObject.setScope(parseDataScope(gdo.getScope()));
                dataObjects.add(dataObject);
            }
        }
        definition.setDataObjects(dataObjects);

        List<WfDataObjectReference> dataReferences = new ArrayList<>();
        if (graph.getDataObjectReferences() != null) {
            for (GraphDataObjectReference gdr : graph.getDataObjectReferences()) {
                if (gdr == null || gdr.getId() == null) {
                    continue;
                }
                WfDataObjectReference reference = new WfDataObjectReference();
                reference.setId(gdr.getId());
                reference.setName(gdr.getName());
                reference.setDataObjectRef(gdr.getDataObjectRef());
                reference.setItemSubjectRef(gdr.getItemSubjectRef());
                reference.setKind(parseReferenceKind(gdr.getKind()));
                dataReferences.add(reference);
            }
        }
        definition.setDataObjectReferences(dataReferences);

        List<WfDataStore> dataStores = new ArrayList<>();
        if (graph.getDataStores() != null) {
            for (GraphDataStore gds : graph.getDataStores()) {
                if (gds == null || gds.getId() == null) {
                    continue;
                }
                WfDataStore store = new WfDataStore();
                store.setId(gds.getId());
                store.setName(gds.getName());
                store.setCapacity(gds.getCapacity());
                store.setUnlimited(gds.isUnlimited());
                dataStores.add(store);
            }
        }
        definition.setDataStores(dataStores);

        List<WfDataAssociation> dataAssociations = new ArrayList<>();
        if (graph.getDataAssociations() != null) {
            for (GraphDataAssociation gda : graph.getDataAssociations()) {
                if (gda == null || gda.getId() == null) {
                    continue;
                }
                WfDataAssociation association = new WfDataAssociation();
                association.setId(gda.getId());
                association.setDirection(parseDataDirection(gda.getDirection()));
                association.setOwnerId(gda.getOwnerId());
                association.setSourceRef(gda.getSourceRef());
                association.setTargetRef(gda.getTargetRef());
                association.setTransformation(gda.getTransformation());
                association.setAssignments(new ArrayList<>(gda.getAssignments()));
                dataAssociations.add(association);
            }
        }
        definition.setDataAssociations(dataAssociations);

        definition.buildIndex();
        return definition;
    }

    /**
     * 枚举名 → 枚举，读不认识的按给定兜底值返回，<b>不抛</b>。
     *
     * <p>这三个枚举都不参与任何执行判断（数据声明在本仓不执行），
     * 为一个显示/分类字段挡住整份定义的加载不划算。
     * 兜底值都取<b>语义上最"普通"的那一个</b>：{@code PROCESS}（进程级）、
     * {@code REFERENCE}（流程级引用）、{@code INPUT}。
     */
    private static WfDataScope parseDataScope(String name) {
        if (name == null) {
            return WfDataScope.PROCESS;
        }
        try {
            return WfDataScope.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return WfDataScope.PROCESS;
        }
    }

    private static WfDataObjectReference.Kind parseReferenceKind(String name) {
        if (name == null) {
            return WfDataObjectReference.Kind.REFERENCE;
        }
        try {
            return WfDataObjectReference.Kind.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return WfDataObjectReference.Kind.REFERENCE;
        }
    }

    private static WfDataDirection parseDataDirection(String name) {
        if (name == null) {
            return WfDataDirection.INPUT;
        }
        try {
            return WfDataDirection.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return WfDataDirection.INPUT;
        }
    }
}
