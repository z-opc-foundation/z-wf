package com.zifang.z.wf.web.api;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.zifang.util.core.meta.Result;
import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.web.dto.WfViews;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 流程分组 Controller —— 基于定义的 category 做分组管理。
 *
 * <p>基址 {@code /api/wf/group}，形状对齐 z-camuda 的 {@code GroupController}
 * （那里基于 Camunda Category）。<b>但两者不等价</b>：Camunda 的 Category 是
 * <b>独立持久化实体</b>、可被多套定义引用、有自己的查询与 ID；
 * 本仓只是定义上的一个 {@code category} <b>字符串字段</b>，改分组要改定义、
 * 也没有"按分组查定义"的独立身份。写成「语义相同」会把后者的限制藏起来。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/wf/group")
@Tag(name = "004_流程分组")
public class WfGroupController {

    @Resource
    private WfRepositoryService repositoryService;

    @GetMapping("/list")
    @Operation(summary = "001_全部分组")
    public Result<List<String>> list() {
        return Result.success(repositoryService.getAllCategories());
    }

    @GetMapping("/processes")
    @Operation(summary = "002_分组下的流程")
    public Result<List<WfViews.DefinitionView>> processes(@RequestParam String category) {
        List<WfViews.DefinitionView> result = new ArrayList<>();
        for (WfDefinition definition : repositoryService.getDefinitionsByCategory(category)) {
            WfViews.DefinitionView view = new WfViews.DefinitionView();
            view.setKey(definition.getKey());
            view.setName(definition.getName());
            view.setVersion(definition.getVersion());
            view.setCategory(definition.getCategory());
            view.setNodeCount(definition.getNodes().size());
            view.setFlowCount(definition.getFlows().size());
            result.add(view);
        }
        return Result.success(result);
    }

    @GetMapping("/detail")
    @Operation(summary = "003_流程定义图结构（供设计器/前端渲染）")
    public Result<WfDefinition> definitionDetail(@RequestParam String key,
                                                 @RequestParam(required = false) Integer version) {
        return Result.success(repositoryService.getDefinitionOrLatest(key, version));
    }
}
