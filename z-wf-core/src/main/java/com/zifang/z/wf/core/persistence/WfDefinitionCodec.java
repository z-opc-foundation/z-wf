package com.zifang.z.wf.core.persistence;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.zifang.util.json.JsonUtil;
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
        private String resultVariable;
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

        public String getResultVariable() {
            return resultVariable;
        }

        public void setResultVariable(String resultVariable) {
            this.resultVariable = resultVariable;
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
            gn.setResultVariable(node.getResultVariable());
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
            gf.setDefaultFlow(flow.isDefaultFlow());
            gf.setProperties(new HashMap<>(flow.getProperties()));
            flows.add(gf);
        }
        graph.setFlows(flows);

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
            node.setResultVariable(gn.getResultVariable());
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
            flow.setDefaultFlow(gf.isDefaultFlow());
            flow.setProperties(new HashMap<>(gf.getProperties()));
            flows.add(flow);
        }
        definition.setFlows(flows);

        definition.buildIndex();
        return definition;
    }
}
