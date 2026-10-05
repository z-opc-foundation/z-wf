package com.zifang.z.wf.web.api;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.zifang.util.core.meta.Result;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.service.WfHistoryService;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.web.dto.WfRequests;
import com.zifang.z.wf.web.dto.WfViews;
import com.zifang.z.wf.web.mapper.WfViewMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 流程操作 Controller —— 挂起 / 激活 / 评论 / 轨迹 / 总览。
 *
 * <p>基址 {@code /api/wf/process}，与 z-camuda 的 {@code ProcessOperationController} 对齐。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/wf/process")
@Tag(name = "003_流程操作")
public class WfProcessOperationController {

    @Resource
    private WfRuntimeService runtimeService;

    @Resource
    private WfHistoryService historyService;

    @Resource
    private WfRepositoryService repositoryService;

    @Resource
    private WfViewMapper viewMapper;

    @PostMapping("/suspend")
    @Operation(summary = "001_挂起流程实例")
    public Result<Void> suspend(@RequestBody WfRequests.ProcessOperation request) {
        runtimeService.suspend(request.getProcessInstanceId(), request.getReason());
        return Result.success();
    }

    @PostMapping("/activate")
    @Operation(summary = "002_激活流程实例")
    public Result<Void> activate(@RequestBody WfRequests.ProcessOperation request) {
        runtimeService.activate(request.getProcessInstanceId());
        return Result.success();
    }

    @PostMapping("/terminate")
    @Operation(summary = "003_终止流程实例（会作废未完成任务）")
    public Result<Void> terminate(@RequestBody WfRequests.ProcessOperation request) {
        runtimeService.terminate(request.getProcessInstanceId(), request.getReason());
        return Result.success();
    }

    @PostMapping("/comment")
    @Operation(summary = "004_加签评论")
    public Result<Map<String, Object>> addComment(@RequestBody WfRequests.ProcessOperation request,
                                                 @RequestParam(required = false) String taskId,
                                                 @RequestParam(required = false) String type) {
        WfComment comment = runtimeService.addComment(
                request.getProcessInstanceId(), taskId, request.getUserId(),
                type == null ? "comment" : type, request.getContent());
        // 返回与 GET /comments 相同的形状：两个端点字段不一致时，
        // 前端会先按"新建返回"渲染再被"列表返回"打脸
        return Result.success(viewMapper.toComment(comment));
    }

    @GetMapping("/comments")
    @Operation(summary = "005_评论列表")
    public Result<List<Map<String, Object>>> comments(@RequestParam String processInstanceId) {
        return Result.success(viewMapper.toComments(processInstanceId));
    }

    @GetMapping("/trail")
    @Operation(summary = "006_审批轨迹")
    public Result<List<Map<String, Object>>> trail(@RequestParam String processInstanceId) {
        return Result.success(viewMapper.toTrail(processInstanceId));
    }

    @GetMapping("/overview")
    @Operation(summary = "007_流程总览（实例+待办+轨迹+评论）")
    public Result<Map<String, Object>> overview(@RequestParam String processInstanceId) {
        return Result.success(historyService.getProcessOverview(processInstanceId));
    }

    @GetMapping("/executions")
    @Operation(summary = "008_当前执行令牌（排障用）")
    public Result<List<WfViews.ExecutionView>> executions(
            @RequestParam String processInstanceId) {
        // 走 VO 而不是直接返回 WfExecution：后者带 arrivedActivities / variables，
        // 是引擎的汇合判据与中间量，抖出去就把内部实现固化成对外契约
        return Result.success(viewMapper.toExecutionViews(
                runtimeService.getExecutions(processInstanceId)));
    }

    @PostMapping("/advance")
    @Operation(summary = "009_推进流程（外部消息触发 receiveTask）")
    public Result<WfViews.ProcessInstanceView> advance(
            @RequestParam String processInstanceId,
            @RequestBody(required = false) Map<String, Object> variables) {
        WfProcessInstance instance = runtimeService.advance(processInstanceId, variables);
        return Result.success(viewMapper.toProcessView(instance));
    }
}
