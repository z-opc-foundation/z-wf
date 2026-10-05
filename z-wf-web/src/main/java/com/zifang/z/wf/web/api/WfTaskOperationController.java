package com.zifang.z.wf.web.api;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.zifang.util.core.meta.Result;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.service.WfTaskService;
import com.zifang.z.wf.web.dto.WfRequests;
import com.zifang.z.wf.web.dto.WfViews;
import com.zifang.z.wf.web.mapper.WfViewMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 任务操作 Controller —— 六类任务流转。
 *
 * <p>基址 {@code /api/wf/task}，与 z-camuda 的 {@code TaskOperationController} 端点同名。
 *
 * <p><b>{@code /force-complete} 与 {@code /jump} 的权限提示</b>：
 * 这两个接口<b>不做办理人身份校验</b>（force-complete 的存在意义就是绕过校验，
 * jump 则是运营干预）。若本仓无 z-ctc 统一鉴权，必须在网关层限制这两个路径的访问，
 * 否则任何人都能替别人办结任务。类级 javadoc 的这段提示不是免责，是使用前提。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/wf/task")
@Tag(name = "002_任务操作")
public class WfTaskOperationController {

    @Resource
    private WfTaskService taskService;

    @Resource
    private WfRuntimeService runtimeService;

    @Resource
    private WfViewMapper viewMapper;

    @PostMapping("/transfer")
    @Operation(summary = "001_转办（责任人转移）")
    public Result<WfViews.TaskSummary> transfer(@RequestBody WfRequests.TaskOperation request) {
        WfTask task = taskService.transfer(request.getTaskId(), request.getUserId(),
                request.getTargetUserId(), request.getComment());
        return Result.success(viewMapper.toSummary(task));
    }

    @PostMapping("/delegate")
    @Operation(summary = "002_委派（处理权转移，责任不变）")
    public Result<WfViews.TaskSummary> delegate(@RequestBody WfRequests.TaskOperation request) {
        WfTask task = taskService.delegate(request.getTaskId(), request.getUserId(),
                request.getTargetUserId(), request.getComment());
        return Result.success(viewMapper.toSummary(task));
    }

    @PostMapping("/claim")
    @Operation(summary = "003_认领（从候选池取走）")
    public Result<WfViews.TaskSummary> claim(@RequestBody WfRequests.TaskOperation request) {
        WfTask task = taskService.claim(request.getTaskId(), request.getUserId(),
                request.getTargetGroups());
        return Result.success(viewMapper.toSummary(task));
    }

    @PostMapping("/unclaim")
    @Operation(summary = "004_取消认领（放回候选池）")
    public Result<WfViews.TaskSummary> unclaim(@RequestBody WfRequests.TaskOperation request) {
        WfTask task = taskService.unclaim(request.getTaskId(), request.getUserId());
        return Result.success(viewMapper.toSummary(task));
    }

    @PostMapping("/withdraw")
    @Operation(summary = "005_撤回（放回候选池）")
    public Result<WfViews.TaskSummary> withdraw(@RequestBody WfRequests.TaskOperation request) {
        WfTask task = taskService.withdraw(request.getTaskId(), request.getUserId(),
                request.getComment());
        return Result.success(viewMapper.toSummary(task));
    }

    @PostMapping("/force-complete")
    @Operation(summary = "006_强制完成（跳过办理人权限，需网关层限制访问）")
    public Result<WfViews.ProcessInstanceView> forceComplete(
            @RequestBody WfRequests.TaskOperation request) {
        WfProcessInstance instance = taskService.forceComplete(request.getTaskId(),
                request.getUserId(), request.getComment(), request.getVariables());
        return Result.success(viewMapper.toProcessView(instance));
    }

    @PostMapping("/jump")
    @Operation(summary = "007_跳转（把 token 移到指定节点，需网关层限制访问）")
    public Result<WfViews.ProcessInstanceView> jump(@RequestBody WfRequests.TaskOperation request) {
        WfProcessInstance instance = taskService.jump(request.getTaskId(), request.getUserId(),
                request.getTargetActivityId(), request.getComment());
        return Result.success(viewMapper.toProcessView(instance));
    }
}
