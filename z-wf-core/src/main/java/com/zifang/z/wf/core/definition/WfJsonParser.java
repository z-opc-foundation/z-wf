package com.zifang.z.wf.core.definition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonArray;
import com.zifang.util.json.model.JsonObject;

/**
 * JSON → {@link WfDefinition} 解析器。
 *
 * <p>存在的理由：z-camuda 的设计器是 <b>LogicFlow</b>（z-camuda-admin 内置静态前端），
 * 它导出的流程图是 JSON 而不是 BPMN XML。如果 z-wf 只吃 XML，"设计器画图"这条路就断了，
 * 而设计器是审批类流程的主要入口。
 *
 * <p>接受两种 JSON 形态：
 * <ol>
 *   <li><b>LogicFlow 原生</b>：{@code {"logicflow": {"nodes": [...], "edges": [...]}}}，
 *       节点 {@code type} 为 {@code "start"} / {@code "exclusive"} / {@code "user-task"} 等 UI 语义名</li>
 *   <li><b>z-wf 原生</b>：{@code {"key": ..., "nodes": [{"id","name","type",...}], "flows": [...]}}</li>
 * </ol>
 * 两种都能解析；LogicFlow 的 type 名通过 {@link #LOGICFLOW_TYPE_ALIASES} 映射到 BPMN 语义。
 *
 * <p>节点/连线的核心字段在两种形态下同名（{@code id} / {@code name} / {@code source} / {@code target}），
 * 因此解析器对两种形态共用同一套取值逻辑，只在"找容器"和"映射类型名"上分叉。
 *
 * @author zifang
 */
public class WfJsonParser {

    /**
     * LogicFlow 节点 type → BPMN 节点类型。
     *
     * <p>LogicFlow 的 type 串允许中缀（{@code user-task}）与驼峰（{@code userTask}）两种写法，
     * 这里两种都登记，避免"设计器升个版本就解析不出来"。
     */
    private static final String[][] LOGICFLOW_TYPE_ALIASES = {
            {"start", "startEvent"},
            {"start-event", "startEvent"},
            {"startEvent", "startEvent"},
            {"end", "endEvent"},
            {"end-event", "endEvent"},
            {"endEvent", "endEvent"},
            {"user-task", "userTask"},
            {"userTask", "userTask"},
            {"approval", "userTask"},
            {"service-task", "serviceTask"},
            {"serviceTask", "serviceTask"},
            {"script-task", "scriptTask"},
            {"scriptTask", "scriptTask"},
            {"manual-task", "manualTask"},
            {"manualTask", "manualTask"},
            {"send-task", "sendTask"},
            {"sendTask", "sendTask"},
            {"receive-task", "receiveTask"},
            {"receiveTask", "receiveTask"},
            {"exclusive", "exclusiveGateway"},
            {"exclusive-gateway", "exclusiveGateway"},
            {"exclusiveGateway", "exclusiveGateway"},
            {"branch", "exclusiveGateway"},
            {"parallel", "parallelGateway"},
            {"parallel-gateway", "parallelGateway"},
            {"parallelGateway", "parallelGateway"},
            {"inclusive", "inclusiveGateway"},
            {"inclusive-gateway", "inclusiveGateway"},
            {"inclusiveGateway", "inclusiveGateway"},
            {"sub-process", "subProcess"},
            {"subProcess", "subProcess"},
            {"call-activity", "callActivity"},
            {"callActivity", "callActivity"},
            {"task", "task"},
    };

    /**
     * 解析 JSON 字符串。
     */
    public WfDefinition parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new WfDefinitionException("流程定义 JSON 为空");
        }
        Map<String, Object> root;
        try {
            root = JsonUtil.parseToMap(json);
        } catch (Exception e) {
            throw new WfDefinitionException("流程定义 JSON 解析失败: " + e.getMessage(), e);
        }
        return parse(root);
    }

    /**
     * 解析已反序列化的 Map。
     */
    @SuppressWarnings("unchecked")
    public WfDefinition parse(Map<String, Object> root) {
        if (root == null) {
            throw new WfDefinitionException("流程定义 JSON 解析结果为 null");
        }

        // LogicFlow 形态：真正的图在 root["logicflow"] 下。
        // 必须用 asMap 归一，不能写 `instanceof Map` —— JsonUtil.parseToMap 只保证
        // 顶层是 Map，嵌套对象是 JsonObject（不是 Map 的子类），
        // 那样判会静默保留错的外层容器，解析出 0 个节点且不报错。
        Object graphHolder = root.get("logicflow");
        Map<String, Object> graph = asMap(graphHolder);
        if (graph == null) {
            graph = root;
        }

        WfDefinition definition = new WfDefinition();
        definition.setKey(str(root.get("key"), str(graph.get("key"), str(root.get("id"), null))));
        definition.setName(str(root.get("name"), str(graph.get("name"), definition.getKey())));
        definition.setCategory(str(root.get("category"), str(graph.get("category"), null)));
        definition.setVersion(intOf(root.get("version"), 1));

        // ---- 节点 ----
        List<WfNode> nodes = new ArrayList<>();
        for (Object item : listOf(graph.get("nodes"))) {
            WfNode node = parseNode(asMap(item));
            if (node != null) {
                nodes.add(node);
            }
        }
        // ---- 连线：LogicFlow 用 edges，z-wf 原生用 flows ----
        List<WfFlow> flows = new ArrayList<>();
        Object rawFlows = graph.get("flows") != null ? graph.get("flows") : graph.get("edges");
        for (Object item : listOf(rawFlows)) {
            WfFlow flow = parseFlow(asMap(item));
            if (flow != null) {
                flows.add(flow);
            }
        }

        definition.setNodes(nodes);
        definition.setFlows(flows);
        definition.buildIndex();
        return definition;
    }

    private WfNode parseNode(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        WfNode node = new WfNode();
        node.setId(str(raw.get("id"), null));
        if (node.getId() == null) {
            return null;
        }
        node.setName(str(raw.get("name"), node.getId()));
        node.setType(resolveType(str(raw.get("type"), "task")));

        node.setCategory(str(raw.get("category"), null));
        node.setFormKey(str(raw.get("formKey"), null));
        node.setAssignee(str(raw.get("assignee"), null));
        node.setMessageName(str(raw.get("messageName"), null));
        node.setDelegateClass(str(raw.get("delegateClass"), null));
        node.setDelegateExpression(str(raw.get("delegateExpression"), null));
        node.setScript(str(raw.get("script"), null));
        node.setCalledElementKey(str(raw.get("calledElementKey"), str(raw.get("calledElement"), null)));
        node.setResultExpression(str(raw.get("resultExpression"), null));
        node.setDueDateDuration(str(raw.get("dueDate"), str(raw.get("dueDateDuration"), null)));
        node.setPriority(intOf(raw.get("priority"), WfNode.DEFAULT_PRIORITY));
        node.setCandidateUsers(stringList(raw.get("candidateUsers")));
        node.setCandidateGroups(stringList(raw.get("candidateGroups")));
        node.setRequiredVariables(stringList(raw.get("requiredVariables")));

        // LogicFlow 会把业务属性塞进 properties/text，一并接收
        Object properties = raw.get("properties");
        if (properties instanceof Map) {
            node.getProperties().putAll(asMap(properties));
        }
        node.getProperties().putAll(pickKnown(raw));
        return node;
    }

    private WfFlow parseFlow(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        WfFlow flow = new WfFlow();
        flow.setId(str(raw.get("id"), null));
        flow.setName(str(raw.get("name"), null));
        // sourceRef/targetRef 与 LogicFlow 的 source/target 两种写法都收
        flow.setSourceRef(str(raw.get("sourceRef"), str(raw.get("source"), null)));
        flow.setTargetRef(str(raw.get("targetRef"), str(raw.get("target"), null)));
        if (flow.getSourceRef() == null || flow.getTargetRef() == null) {
            return null;
        }
        flow.setConditionExpression(str(raw.get("conditionExpression"),
                str(raw.get("condition"), null)));
        flow.setDefaultFlow(boolOf(raw.get("defaultFlow"), boolOf(raw.get("default"), false)));
        return flow;
    }

    /**
     * 类型名解析：先查 LogicFlow 别名表，再走 BPMN 归一。
     */
    private WfNodeType resolveType(String rawType) {
        if (rawType == null || rawType.trim().isEmpty()) {
            return WfNodeType.TASK;
        }
        String normalized = rawType.trim();
        for (String[] alias : LOGICFLOW_TYPE_ALIASES) {
            if (alias[0].equalsIgnoreCase(normalized)) {
                return WfNodeType.fromBpmn(alias[1]);
            }
        }
        return WfNodeType.fromBpmn(normalized);
    }

    /**
     * 把散落在顶层的已知字段收进 properties（供 delegate 等场景读取）。
     */
    private Map<String, Object> pickKnown(Map<String, Object> raw) {
        Map<String, Object> picked = new java.util.HashMap<>();
        for (String key : new String[]{"expression", "payload", "timeout", "escalation", "ext"}) {
            Object value = raw.get(key);
            if (value != null) {
                picked.put(key, value);
            }
        }
        return picked;
    }

    // ==================== 取值辅助 ====================

    /**
     * 取节点/连线对象。
     * <p>接受两种容器：{@link java.util.Map} 与 z-util 的 {@code JsonObject}。
     * 后者是必须的 —— {@code JsonUtil.parseToMap} 只保证<b>顶层</b>是 Map，
     * 嵌套对象实际是 {@code JsonObject}（其 {@code getAllKeyValue()} 才能取出条目），
     * 嵌套数组是 {@code JsonArray}。只认 Map 会让 LogicFlow 导出的
     * {@code {"logicflow":{...}}} 解析出 0 个节点。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        if (value instanceof JsonObject) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ((JsonObject) value).getAllKeyValue()) {
                map.put(entry.getKey(), normalize(entry.getValue()));
            }
            return map;
        }
        return null;
    }

    /**
     * 取数组。
     * <p>同样兼容 {@code List} 与 {@code JsonArray}。
     */
    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object value) {
        if (value instanceof List) {
            List<Object> list = new ArrayList<>();
            for (Object item : (List<Object>) value) {
                list.add(normalize(item));
            }
            return list;
        }
        if (value instanceof JsonArray) {
            JsonArray array = (JsonArray) value;
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                list.add(normalize(array.get(i)));
            }
            return list;
        }
        return new ArrayList<>();
    }

    /**
     * 递归归一化：JsonObject → LinkedHashMap，JsonArray → List。
     * <p>保证下游拿到的全是标准 JDK 容器，LogicFlow 导出里的任意嵌套深度都能处理。
     *
     * <p><b>四路分派必须写全</b>（Map / JsonObject / List / JsonArray）。
     * 少写一路的症状是"某个嵌套层级静默变成空列表"——
     * 比如只把 JsonObject 交给 {@code listOf} 处理，得到的永远是空集合，
     * 于是 LogicFlow 的 nodes 解析出 0 个节点，而且不抛任何异常。
     */
    @SuppressWarnings("unchecked")
    private static Object normalize(Object value) {
        if (value instanceof Map) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                map.put(entry.getKey(), normalize(entry.getValue()));
            }
            return map;
        }
        if (value instanceof JsonObject) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ((JsonObject) value).getAllKeyValue()) {
                map.put(entry.getKey(), normalize(entry.getValue()));
            }
            return map;
        }
        if (value instanceof List || value instanceof JsonArray) {
            return listOf(value);
        }
        return value;
    }

    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item != null && !String.valueOf(item).trim().isEmpty()) {
                    result.add(String.valueOf(item).trim());
                }
            }
        } else if (value instanceof String) {
            for (String part : ((String) value).split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    private static String str(Object primary, String fallback) {
        if (primary != null && !String.valueOf(primary).trim().isEmpty()) {
            return String.valueOf(primary).trim();
        }
        return fallback;
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean boolOf(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value != null) {
            String s = String.valueOf(value).trim();
            if ("true".equalsIgnoreCase(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s)) {
                return false;
            }
        }
        return fallback;
    }
}
