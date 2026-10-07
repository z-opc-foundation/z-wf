package com.zifang.z.wf.web.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.zifang.util.core.meta.Result;
import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.service.WfDeploymentEntry;
import com.zifang.z.wf.core.service.WfDeploymentOrder;
import com.zifang.z.wf.core.service.WfDeploymentQuery;
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.service.WfRepositoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 流程定义的部署与停用管理。
 *
 * <p>基址 {@code /api/wf/definitions}。
 *
 * <p><b>为什么定义级的停用要单独一个控制器</b>：{@code /api/wf/process/suspend}
 * 挂的是<b>实例</b>，而这里的 {@code /suspend} 挂的是<b>定义版本</b>。
 * 两者都叫 suspend 但语义正交（停用是"不再接新单"，实例挂起是"这单先别走"），
 * 混在一个控制器里迟早有人把两者当成一回事调。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/wf/definitions")
@Tag(name = "001_流程定义管理")
public class WfDefinitionController {

    @Resource
    private WfRepositoryService repositoryService;

    @GetMapping
    @Operation(summary = "001_查流程定义（key/名称模糊 + 停用状态）")
    public Result<List<Map<String, Object>>> list(
            @RequestParam(required = false) String keyLike,
            @RequestParam(required = false) String nameLike,
            @RequestParam(required = false) Boolean suspended) {

        List<Map<String, Object>> rows = new ArrayList<>();
        for (WfDefinition definition
                : repositoryService.queryDefinitions(keyLike, nameLike, suspended)) {
            rows.add(toView(definition));
        }
        return Result.success(rows);
    }

    @GetMapping("/versions")
    @Operation(summary = "002_查某定义的全部版本（新 → 旧）")
    public Result<List<Map<String, Object>>> versions(@RequestParam String key) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WfDefinition definition : repositoryService.getDefinitionVersions(key)) {
            rows.add(toView(definition));
        }
        return Result.success(rows);
    }

    /**
     * 部署历史：<b>跨所有 key 的全部版本</b>（第 47 轮）。
     *
     * <p><b>与 {@link #versions} 的分工</b>：那个要先知道 key，且只答那一个 key；
     * 这个不预设任何 key，答的是「这段时间里 / 这个分类下 / 这批 key 上，
     * 到底部署过什么」。存量界面上没有第二种能力。
     *
     * <p>返回<b>分页信封</b>（{@code records} / {@code total} / {@code pageNum} / {@code pageSize}）而不是裸列表 ——
     * 部署历史天然会越积越多，而裸列表只能靠"截断"来限流，
     * 截断的那一份<b>看起来就是全部</b>。
     */
    @GetMapping("/history")
    @Operation(summary = "009_查部署历史（跨 key 全部版本，可按分类/时间窗/有无原始 XML 筛）")
    public Result<Map<String, Object>> history(
            @RequestParam(required = false) String key,
            @RequestParam(required = false) String keyLike,
            @RequestParam(required = false) String nameLike,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean suspended,
            @RequestParam(required = false) Boolean defaultDefinition,
            @RequestParam(required = false) Boolean hasSourceXml,
            @RequestParam(required = false) Long deployedFrom,
            @RequestParam(required = false) Long deployedTo,
            @RequestParam(required = false) String orderBy,
            @RequestParam(required = false, defaultValue = "1") Integer pageNum,
            @RequestParam(required = false, defaultValue = "50") Integer pageSize) {

        WfDeploymentQuery query = new WfDeploymentQuery()
                .setKey(key)
                .setKeyLike(keyLike)
                .setNameLike(nameLike)
                .setCategory(category)
                .setSuspended(suspended)
                .setDefaultDefinition(defaultDefinition)
                .setHasSourceXml(hasSourceXml)
                .setDeployedFrom(toDate(deployedFrom))
                .setDeployedTo(toDate(deployedTo))
                .setOrderBy(parseOrder(orderBy))
                .setPageNum(pageNum == null ? 1 : pageNum)
                .setPageSize(pageSize == null ? 50 : pageSize);

        List<WfDeploymentEntry> records = repositoryService.queryDeployments(query);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("records", deploymentEntryViews(records));
        view.put("total", repositoryService.countDeployments(query));
        view.put("pageNum", query.normalizedPageNum());
        view.put("pageSize", query.normalizedPageSize());
        return Result.success(view);
    }

    /**
     * 排序参数解析，<b>不认识的直接报错并列出可选值</b>。
     *
     * <p>不静默回落成默认排序：那一栏会真的按另一个次序出数据，
     * 而调用方以为是自己选错了顺序 —— 症状是"排序功能时灵时不灵"。
     */
    private WfDeploymentOrder parseOrder(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return WfDeploymentOrder.DEPLOY_TIME_DESC;
        }
        String normalized = raw.trim().replace('-', '_').toUpperCase(java.util.Locale.ROOT);
        for (WfDeploymentOrder candidate : WfDeploymentOrder.values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        StringBuilder options = new StringBuilder();
        for (WfDeploymentOrder candidate : WfDeploymentOrder.values()) {
            if (options.length() > 0) {
                options.append(", ");
            }
            options.append(candidate.name());
        }
        throw new WfEngineException("不支持的部署历史排序: " + raw + "，可选值: " + options);
    }

    /**
     * 毫秒时间戳 → {@link java.util.Date}；{@code null} 透传。
     *
     * <p>入参用毫秒时间戳而不是日期字符串，与本仓其它所有视图与筛选器条件同一口径 ——
     * 存字符串的日期在不同机器上会按本地时区解释，
     * 症状是「按时间窗查部署，边界上少了几条」且换个时区就复现不了。
     */
    private java.util.Date toDate(Long epochMillis) {
        return epochMillis == null ? null : new java.util.Date(epochMillis);
    }

    private List<Map<String, Object>> deploymentEntryViews(List<WfDeploymentEntry> entries) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WfDeploymentEntry entry : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", entry.getKey());
            row.put("version", entry.getVersion());
            row.put("name", entry.getName());
            row.put("category", entry.getCategory());
            row.put("description", entry.getDescription());
            row.put("suspended", entry.isSuspended());
            row.put("defaultDefinition", entry.isDefaultDefinition());
            // 毫秒时间戳：与本仓其它视图同一口径，调用方不用再猜时区
            row.put("deployTime", entry.getDeployTime() == null
                    ? null : entry.getDeployTime().getTime());
            // hasSourceXml 是这一层最有信息量的一列：
            // JSON 部署的定义恒为 false，而 getProcessModel 遇到它们会直接抛错
            row.put("hasSourceXml", entry.isHasSourceXml());
            rows.add(row);
        }
        return rows;
    }

    /**
     * 某定义某一版的<b>数据声明清单</b>（第 46 轮）。
     *
     * <p><b>为什么单独一个端点而不是并进 {@link #list}</b>：数据声明是
     * 「走查一份流程到底在用哪些数据」的答案，而走查是低频动作；
     * 列表端点每个定义都要多带四段结构，日常打开流程列表的人一个都用不上。
     *
     * <p><b>返回体里刻意带上 {@code engineReadsData=false}</b>：
     * 本引擎<b>不执行数据关联、不对 dataStore 做存取</b>
     * （见 {@code WfDataObject} / {@code WfDataStore} 的类注释，与 Camunda 7 一致）。
     * 只列声明不说这一点，调用方会以为"列出来的就是引擎在管的"——
     * 而那正是第 46 轮改动前最坏的一类静默：写了等于没写，且没有任何提示。
     * 让这条事实出现在<b>响应体里</b>而不是只写在文档里，是为了它跟着接口一起被看见。
     */
    @GetMapping("/data")
    @Operation(summary = "008_查某定义某一版的数据声明（dataObject/dataStore/数据关联，本引擎只读不执行）")
    public Result<Map<String, Object>> dataDeclarations(
            @RequestParam String key,
            @RequestParam(required = false) Integer version) {
        // 刻意<b>不用</b> getDefinitionOrLatest：那个方法在版本不存在时会回落到最新版本。
        // 查数据声明时静默换一版，等于回答了另一个模型的问题，而响应里的
        // version 字段会把版本号照实写出来，调用方更难发现。
        //
        // 两个分支都<b>不判 null</b>：getLatestDefinition / getDefinition 找不到就抛，
        // 由异常 advice 翻成 4xx。这与同控制器的 /model、/diagram 一致 ——
        // 「问一个从没部署过的 key」是真问错了，而 {@link #getDefault()} 回 null
        // 是因为「还没配默认」本身是正常状态。两者不是一回事，
        // 在这里判一个永远不成立的 null 分支，等于写下一条
        // 「查不到是正常状态」的注释，而代码里没有任何一条路径会走到它。
        WfDefinition definition = version == null
                ? repositoryService.getLatestDefinition(key)
                : repositoryService.getDefinition(key, version);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("key", definition.getKey());
        view.put("version", definition.getVersion());
        view.put("engineReadsData", false);
        view.put("engineReadsDataReason",
                "本引擎不执行 dataInputAssociation/dataOutputAssociation，也不对 dataStore 做存取；"
                        + "业务数据一律走流程变量（WorkflowService#setVariable）。"
                        + "这里的清单是建模声明，供走查与设计器回显用。");
        view.put("dataObjects", dataObjectViews(definition));
        view.put("dataObjectReferences", dataReferenceViews(definition));
        view.put("dataStores", dataStoreViews(definition));
        view.put("dataAssociations", dataAssociationViews(definition));
        return Result.success(view);
    }

    private List<Map<String, Object>> dataObjectViews(WfDefinition definition) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (com.zifang.z.wf.core.definition.WfDataObject dataObject : definition.getDataObjects()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", dataObject.getId());
            row.put("name", dataObject.getName());
            row.put("itemSubjectRef", dataObject.getItemSubjectRef());
            // 作用域随响应给出（不让人靠"嵌了几层"自己数）
            row.put("scope", dataObject.getScope() == null ? null : dataObject.getScope().name());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> dataReferenceViews(WfDefinition definition) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (com.zifang.z.wf.core.definition.WfDataObjectReference reference
                : definition.getDataObjectReferences()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", reference.getId());
            row.put("name", reference.getName());
            row.put("dataObjectRef", reference.getDataObjectRef());
            row.put("itemSubjectRef", reference.getItemSubjectRef());
            // kind 必须一起给：这份列表里混着 <dataObjectReference> 与 ioSpecification 的
            // dataInput/dataOutput，不给就得靠 id 猜它长什么样
            row.put("kind", reference.getKind() == null ? null : reference.getKind().name());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> dataStoreViews(WfDefinition definition) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (com.zifang.z.wf.core.definition.WfDataStore store : definition.getDataStores()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", store.getId());
            row.put("name", store.getName());
            // capacity 是 Integer：没配就是 null，不能显示成 0（那是"容量为零"）
            row.put("capacity", store.getCapacity());
            row.put("unlimited", store.isUnlimited());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> dataAssociationViews(WfDefinition definition) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (com.zifang.z.wf.core.definition.WfDataAssociation association
                : definition.getDataAssociations()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", association.getId());
            row.put("direction", association.getDirection() == null
                    ? null : association.getDirection().name());
            row.put("ownerId", association.getOwnerId());
            // ownerId 为 null 表示写在 <process> 上。给出显式布尔位而不是
            // "靠 ownerId 是不是 null 去判断"：后者是让调用方猜
            row.put("processLevel", association.getOwnerId() == null);
            row.put("sourceRef", association.getSourceRef());
            row.put("targetRef", association.getTargetRef());
            row.put("transformation", association.getTransformation());
            row.put("assignments", association.getAssignments());
            rows.add(row);
        }
        return rows;
    }

    @GetMapping("/model")
    @Operation(summary = "003_回读原始 BPMN XML（模型编辑器集成用）")
    public Result<String> model(@RequestParam String key, @RequestParam Integer version) {
        return Result.success(repositoryService.getProcessModel(key, version));
    }

    @GetMapping("/diagram")
    @Operation(summary = "010_回读流程图元（节点坐标 + 连线折点，供前端渲染）")
    public Result<com.zifang.z.wf.core.view.WfDiagramInfo> diagram(
            @RequestParam String key, @RequestParam Integer version) {
        return Result.success(repositoryService.getProcessDiagram(key, version));
    }

    @DeleteMapping("/definition")
    @Operation(summary = "007_物理删除某个版本（仍有在途实例时拒绝）")
    public Result<Void> delete(@RequestParam String key, @RequestParam Integer version) {
        repositoryService.deleteDefinition(key, version);
        return Result.success();
    }

    @PostMapping("/suspend")
    @Operation(summary = "004_停用某个版本（不再接新单，在跑的实例不受影响）")
    public Result<Void> suspend(@RequestParam String key, @RequestParam Integer version) {
        repositoryService.suspendDefinition(key, version);
        return Result.success();
    }

    @PostMapping("/activate")
    @Operation(summary = "005_启用某个版本")
    public Result<Void> activate(@RequestParam String key, @RequestParam Integer version) {
        repositoryService.activateDefinition(key, version);
        return Result.success();
    }

    @PostMapping("/deploy")
    @Operation(summary = "006_部署 BPMN XML")
    public Result<Map<String, Object>> deploy(@RequestBody DeployRequest request) {
        if (request == null || request.getXml() == null || request.getXml().trim().isEmpty()) {
            // 空 XML 会在解析器里报一句与调用方语境无关的错，这里先挡一次
            throw new com.zifang.z.wf.core.service.WfEngineException("部署内容不能为空");
        }
        WfDefinition definition = request.getKey() == null || request.getKey().trim().isEmpty()
                ? repositoryService.deployXml(request.getXml(), null)
                : repositoryService.deployXml(request.getXml(), request.getKey());
        return Result.success(toView(definition));
    }

    @PostMapping("/default")
    @Operation(summary = "011_设为默认流程定义（调用方不知道 key 时的发起入口）")
    public Result<Map<String, Object>> setDefault(@RequestParam String key,
                                                  @RequestParam Integer version) {
        return Result.success(toView(repositoryService.setDefaultDefinition(key, version)));
    }

    @DeleteMapping("/default")
    @Operation(summary = "012_取消默认流程定义")
    public Result<Void> clearDefault(@RequestParam String key, @RequestParam Integer version) {
        repositoryService.clearDefaultDefinition(key, version);
        return Result.success();
    }

    @GetMapping("/default")
    @Operation(summary = "013_查当前默认流程定义（没设过则返回 null）")
    public Result<Map<String, Object>> getDefault() {
        WfDefinition definition = repositoryService.getDefaultDefinition();
        // 没设过默认是正常状态（回 data=null），不是异常：
        // 入口页要能据此提示"还没配默认"，而不是收到一个 4xx
        return Result.success(definition == null ? null : toView(definition));
    }

    private Map<String, Object> toView(WfDefinition definition) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("key", definition.getKey());
        view.put("version", definition.getVersion());
        view.put("name", definition.getName());
        view.put("category", definition.getCategory());
        view.put("description", definition.getDescription());
        view.put("suspended", definition.isSuspended());
        view.put("defaultDefinition", definition.isDefaultDefinition());
        // 不回传整份图结构：定义列表是给人看的，节点树动辄几十个节点，
        // 要看结构请走 /model 回读原始 XML
        view.put("nodeCount", definition.getNodes() == null ? 0 : definition.getNodes().size());
        view.put("flowCount", definition.getFlows() == null ? 0 : definition.getFlows().size());
        view.put("hasSourceXml", definition.getSourceXml() != null
                && !definition.getSourceXml().trim().isEmpty());
        return view;
    }

    /** 部署请求。 */
    public static class DeployRequest {
        private String key;
        private String xml;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getXml() {
            return xml;
        }

        public void setXml(String xml) {
            this.xml = xml;
        }
    }
}
