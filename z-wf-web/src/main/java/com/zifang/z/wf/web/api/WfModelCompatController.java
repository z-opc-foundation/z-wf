package com.zifang.z.wf.web.api;

import com.zifang.util.core.meta.Result;
import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonArray;
import com.zifang.util.json.model.JsonObject;
import com.zifang.z.meta.api.annotation.BizApi;
import com.zifang.z.meta.api.annotation.BizField;
import com.zifang.z.meta.api.annotation.BizParam;
import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfFlow;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.view.WfDiagramInfo;
import com.zifang.z.wf.core.view.WfShape;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程设计 Controller —— z-camuda {@code ProcessModelController} 的兼容实现。
 *
 * <p>基址 {@code /api/approval-center/processes/model}，与 z-camuda 同路径同语义，
 * 前端 {@code designerApi}（getFlowGraph / saveFlowGraph / deployProcess）无需改动即可
 * 指向本引擎。设计稿是 LogicFlow 图 JSON，按流程 key 覆盖式保存（未发布草稿）；
 * 发布时经 {@code deployJson} 转成引擎定义产生新版本。
 *
 * <p>端到端契约：z-camuda 的 {@code GET /graph} 返回 {@code {processKey, name, graph,
 * source, updatedAt}}，graph 为 JSON 字符串；保存收 {@code {processKey, name, graph}}；
 * 发布收 {@code {processKey, name}}，回 {@code {deploymentId, processKey,
 * processDefinitionId, version}}。本实现一一对齐。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/approval-center/processes/model")
@Tag(name = "01x_流程设计（兼容 z-camuda ProcessModelController）")
public class WfModelCompatController {

    @Resource
    private WfRepositoryService repositoryService;

    // ==================== 1. 读设计稿 ====================

    /**
     * 取某个流程 key 的最新设计稿 (LogicFlow 图数据)。
     *
     * <p>没有设计稿但已有流程定义时，把定义结构转回 LogicFlow 图（坐标取
     * BPMN DI，缺坐标时网格兜底），保证设计器总能打开；两者都没有才给骨架图。
     *
     * @return {@code {processKey, name, graph, source, updatedAt}} ;
     *     source ∈ designed / definition / empty
     */
    @GetMapping("/graph")
    @Operation(summary = "011_获取流程设计稿")
    @BizApi(summary = "取某个流程 key 的最新设计稿 (LogicFlow 图数据)", description = "取某个流程 key 的最新设计稿 (LogicFlow 图数据)。 没有设计稿但已有流程定义时，把定义结构转回 LogicFlow 图（坐标取 BPMN DI，缺坐标时网格兜底），保证设计器总能打开；两者都没有才给骨架图。 source ∈ designed / definition / empty", tags = { "z-wf" })
    public Result<Map<String, Object>> getGraph(@RequestParam @BizParam(name = "processKey", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.QUERY) String processKey) {
        if (processKey == null || processKey.trim().isEmpty()) {
            return Result.fail("processKey 不能为空");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("processKey", processKey);

        String design = repositoryService.findDesignGraph(processKey);
        if (design != null && !design.trim().isEmpty()) {
            out.put("name", definitionName(processKey));
            out.put("graph", design);
            out.put("source", "designed");
            out.put("updatedAt", "");
            return Result.success(out);
        }

        WfDefinition definition = safeLatest(processKey);
        if (definition != null) {
            out.put("name", definition.getName());
            out.put("graph", toLogicFlowGraph(definition));
            out.put("source", "definition");
            out.put("updatedAt", "");
            return Result.success(out);
        }

        out.put("name", processKey);
        out.put("graph", starterGraph());
        out.put("source", "empty");
        out.put("updatedAt", "");
        return Result.success(out);
    }

    // ==================== 2. 存设计稿 ====================

    /**
     * 保存设计稿 (LogicFlow 图数据)，同 key 覆盖式。
     *
     * <p>只做形状自检（非空 / 有节点 / 有 start），语义错误留到部署时才报，
     * 避免用户画到一半就被拦 —— 与 z-camuda 行为一致。
     */
    @PostMapping("/graph")
    @Operation(summary = "012_保存流程设计稿")
    @BizApi(summary = "保存设计稿 (LogicFlow 图数据)，同 key 覆盖式", description = "保存设计稿 (LogicFlow 图数据)，同 key 覆盖式。 只做形状自检（非空 / 有节点 / 有 start），语义错误留到部署时才报， 避免用户画到一半就被拦 —— 与 z-camuda 行为一致。", tags = { "z-wf" })
    public Result<String> saveGraph(@RequestBody @BizParam(name = "request", type = BizField.FieldType.JSON, description = "", position = BizParam.Position.BODY) Map<String, String> request) {
        String processKey = trim(request.get("processKey"));
        String graph = request.get("graph");
        if (processKey == null) {
            return Result.fail("processKey 不能为空");
        }
        String error = validateGraph(graph);
        if (error != null) {
            return Result.fail(error);
        }
        repositoryService.saveDesignGraph(processKey, graph);
        return Result.success("设计稿已保存");
    }

    // ==================== 3. 发布 ====================

    /**
     * 把已保存的设计稿发布为新的流程定义版本。
     *
     * <p>本引擎原生支持 LogicFlow JSON（{@code {"logicflow": {...}}}），
     * 所以不走 BPMN 中转：包装后直接 {@code deployJson}，天然产生新版本。
     *
     * @param request {@code {processKey, name}}；name 为空时用流程 key
     * @return {@code {deploymentId, processKey, processDefinitionId, version}}
     */
    @PostMapping("/deploy")
    @Operation(summary = "013_发布流程")
    @BizApi(summary = "把已保存的设计稿发布为新的流程定义版本", description = "把已保存的设计稿发布为新的流程定义版本。 本引擎原生支持 LogicFlow JSON（{\"logicflow\": {...}}）， 所以不走 BPMN 中转：包装后直接 deployJson，天然产生新版本。", tags = { "z-wf" })
    public Result<Map<String, Object>> deploy(@RequestBody @BizParam(name = "request", type = BizField.FieldType.JSON, description = "{@code {processKey, name}}；name 为空时用流程 key", position = BizParam.Position.BODY) Map<String, String> request) {
        String processKey = trim(request.get("processKey"));
        String name = trim(request.get("name"));
        if (processKey == null) {
            return Result.fail("processKey 不能为空");
        }
        String graph = repositoryService.findDesignGraph(processKey);
        if (graph == null || graph.trim().isEmpty()) {
            return Result.fail("还没有设计稿，请先保存流程图");
        }
        String wrapper = "{\"name\":" + quote(name == null ? processKey : name)
                + ",\"logicflow\":" + graph + "}";
        final WfDefinition definition;
        try {
            definition = repositoryService.deployJson(wrapper, processKey);
        } catch (WfDefinitionException e) {
            return Result.fail("生成流程定义失败: " + e.getMessage());
        }

        Map<String, Object> out = new HashMap<>();
        String definitionId = definition.getKey() + ":" + definition.getVersion();
        out.put("deploymentId", definitionId);
        out.put("processKey", processKey);
        out.put("processDefinitionId", definitionId);
        out.put("version", definition.getVersion());
        return Result.success(out);
    }

    // ==================== 4. 列出可设计的流程 ====================

    /**
     * 列出「有设计稿」与「已部署但还没有设计稿」的流程 key，各自一份。
     *
     * @return {@code {designed:[...], deployedOnly:[...]}}
     */
    @GetMapping("/keys")
    @Operation(summary = "014_列出可设计的流程")
    @BizApi(summary = "列出「有设计稿」与「已部署但还没有设计稿」的流程 key，各自一份", description = "列出「有设计稿」与「已部署但还没有设计稿」的流程 key，各自一份。", tags = { "z-wf" })
    public Result<Map<String, List<String>>> listKeys() {
        List<String> designed = repositoryService.findDesignedKeys();
        List<String> deployedOnly = new ArrayList<>();
        for (WfDefinition definition : repositoryService.getAllDefinitions()) {
            if (!designed.contains(definition.getKey())) {
                deployedOnly.add(definition.getKey());
            }
        }
        Map<String, List<String>> out = new HashMap<>();
        out.put("designed", designed);
        out.put("deployedOnly", deployedOnly);
        return Result.success(out);
    }

    // ==================== 内部方法 ====================

    private WfDefinition safeLatest(String processKey) {
        try {
            return repositoryService.getLatestDefinition(processKey);
        } catch (WfDefinitionException e) {
            return null;
        }
    }

    private String definitionName(String processKey) {
        WfDefinition definition = safeLatest(processKey);
        return definition == null ? processKey : definition.getName();
    }

    /**
     * 已部署定义 → LogicFlow 图 JSON。节点语义类型映射到设计器已注册的
     * start / approval / condition / end；坐标优先取 BPMN DI，没有 DI 时网格兜底。
     */
    private String toLogicFlowGraph(WfDefinition definition) {
        Map<String, double[]> coords = diagramCoords(definition);
        List<WfNode> nodes = definition.getNodes();
        StringBuilder nodeJson = new StringBuilder();
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) {
                nodeJson.append(',');
            }
            WfNode node = nodes.get(i);
            double[] xy = coords.get(node.getId());
            double x = xy != null ? xy[0] : 200 + 240 * (i % 5);
            double y = xy != null ? xy[1] : 120 + 150 * ((i / 5) % 4);
            nodeJson.append("{\"id\":").append(quote(node.getId()))
                    .append(",\"type\":").append(quote(uiType(node)))
                    .append(",\"x\":").append(x)
                    .append(",\"y\":").append(y)
                    .append(",\"text\":{\"value\":").append(quote(node.getName())).append("}}");
        }
        List<WfFlow> flows = definition.getFlows();
        StringBuilder edgeJson = new StringBuilder();
        for (int i = 0; i < flows.size(); i++) {
            if (i > 0) {
                edgeJson.append(',');
            }
            WfFlow flow = flows.get(i);
            edgeJson.append("{\"id\":").append(quote(flow.getId()))
                    .append(",\"sourceNodeId\":").append(quote(flow.getSourceRef()))
                    .append(",\"targetNodeId\":").append(quote(flow.getTargetRef()))
                    .append(",\"type\":\"polyline\"}");
        }
        return "{\"nodes\":[" + nodeJson + "],\"edges\":[" + edgeJson + "]}";
    }

    private String uiType(WfNode node) {
        String bpmn = node.getType() == null ? "" : node.getType().bpmnName();
        if ("startEvent".equals(bpmn)) {
            return "start";
        }
        if ("endEvent".equals(bpmn)) {
            return "end";
        }
        if ("exclusiveGateway".equals(bpmn) || "parallelGateway".equals(bpmn)
                || "inclusiveGateway".equals(bpmn)) {
            return "condition";
        }
        return "approval";
    }

    private Map<String, double[]> diagramCoords(WfDefinition definition) {
        Map<String, double[]> coords = new HashMap<>();
        try {
            WfDiagramInfo info = repositoryService.getProcessDiagram(
                    definition.getKey(), definition.getVersion());
            for (WfShape shape : info.getShapes()) {
                coords.put(shape.getId(), new double[] {shape.getX(), shape.getY()});
            }
        } catch (RuntimeException e) {
            // 没有 DI / XML 缺失时给空坐标，走调用方兜底，不因图而挂接口
        }
        return coords;
    }

    private String starterGraph() {
        return "{\"nodes\":["
                + "{\"id\":\"start-1\",\"type\":\"start\",\"x\":200,\"y\":240,"
                + "\"text\":{\"value\":\"开始\"}},"
                + "{\"id\":\"end-1\",\"type\":\"end\",\"x\":560,\"y\":240,"
                + "\"text\":{\"value\":\"结束\"}}],"
                + "\"edges\":[{\"id\":\"e-1\",\"sourceNodeId\":\"start-1\","
                + "\"targetNodeId\":\"end-1\",\"type\":\"polyline\"}]}";
    }

    /**
     * 形状自检：合法 JSON 对象 + 非空节点 + 有 start。
     */
    private String validateGraph(String graph) {
        if (graph == null || graph.trim().isEmpty()) {
            return "graph 不能为空";
        }
        final Map<String, Object> root;
        try {
            root = JsonUtil.parseToMap(graph);
        } catch (Exception e) {
            return "graph 不是合法的 JSON 对象: " + e.getMessage();
        }
        Object rawNodes = root.get("nodes");
        List<Object> nodes = listOf(rawNodes);
        if (nodes == null || nodes.isEmpty()) {
            return "graph 至少需要一个节点";
        }
        boolean hasStart = false;
        for (Object item : nodes) {
            String type = nodeTypeOf(item);
            if ("start".equals(type) || "start-event".equals(type) || "startEvent".equals(type)) {
                hasStart = true;
                break;
            }
        }
        if (!hasStart) {
            return "graph 缺少开始节点";
        }
        return null;
    }

    /**
     * 取数组。z-util 解析嵌套数组为 {@code JsonArray} 而非 {@code List}，
     * 两种都认得（与 WfJsonParser#listOf 同源问题）。
     */
    private List<Object> listOf(Object value) {
        if (value instanceof List) {
            return new ArrayList<>((List<?>) value);
        }
        if (value instanceof JsonArray) {
            JsonArray array = (JsonArray) value;
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                list.add(array.get(i));
            }
            return list;
        }
        return null;
    }

    /**
     * 取节点对象的 type 字段。z-util 解析嵌套对象为 {@code JsonObject} 而非 Map，
     * 这里必须两种容器都认得（与 WfJsonParser#asMap 同源问题）。
     */
    @SuppressWarnings("unchecked")
    private String nodeTypeOf(Object item) {
        if (item instanceof Map) {
            return String.valueOf(((Map<String, Object>) item).get("type"));
        }
        if (item instanceof JsonObject) {
            for (Object entry : ((JsonObject) item).getAllKeyValue()) {
                Map.Entry<String, Object> e = (Map.Entry<String, Object>) entry;
                if ("type".equals(e.getKey())) {
                    return String.valueOf(e.getValue());
                }
            }
        }
        return null;
    }

    private static String trim(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.append('"').toString();
    }
}

