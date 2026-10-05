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

    @GetMapping("/model")
    @Operation(summary = "003_回读原始 BPMN XML（模型编辑器集成用）")
    public Result<String> model(@RequestParam String key, @RequestParam Integer version) {
        return Result.success(repositoryService.getProcessModel(key, version));
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

    private Map<String, Object> toView(WfDefinition definition) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("key", definition.getKey());
        view.put("version", definition.getVersion());
        view.put("name", definition.getName());
        view.put("category", definition.getCategory());
        view.put("description", definition.getDescription());
        view.put("suspended", definition.isSuspended());
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
