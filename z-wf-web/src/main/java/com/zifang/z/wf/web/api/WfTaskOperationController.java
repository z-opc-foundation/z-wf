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

    /**
     * 调整候选池。四个动作合在一个端点上，用 {@code type} 区分
     * （candidateUser/candidateGroup × add/remove）——
     * 拆成四个端点的话前端要维护四份几乎一样的调用，而它们的入参本来就是同一个。
     */
    @PostMapping("/candidate")
    @Operation(summary = "调整候选池（加/减候选用户或候选组）")
    public Result<WfViews.TaskSummary> candidate(@RequestBody CandidateRequest request) {
        if (request == null) {
            throw new com.zifang.z.wf.core.service.WfEngineException("请求体不能为空");
        }
        String type = request.getType() == null ? "" : request.getType().trim();
        String target = request.getTarget();
        WfTask task;
        boolean add = !"remove".equalsIgnoreCase(request.getAction());
        if ("candidateUser".equals(type)) {
            task = add ? taskService.addCandidateUser(request.getTaskId(), target)
                    : taskService.removeCandidateUser(request.getTaskId(), target);
        } else if ("candidateGroup".equals(type)) {
            task = add ? taskService.addCandidateGroup(request.getTaskId(), target)
                    : taskService.removeCandidateGroup(request.getTaskId(), target);
        } else {
            // 猜错 type 会改到错误的列表上，而错误本身毫无提示
            throw new com.zifang.z.wf.core.service.WfEngineException(
                    "type 只能是 candidateUser 或 candidateGroup，实际: " + type);
        }
        return Result.success(viewMapper.toSummary(task));
    }

    /** 候选池调整请求。 */
    public static class CandidateRequest {
        private String taskId;
        /** candidateUser / candidateGroup */
        private String type;
        /** 目标用户 id 或组 id */
        private String target;
        /** add / remove，缺省按 add 处理 */
        private String action;

        public String getTaskId() {
            return taskId;
        }

        public void setTaskId(String taskId) {
            this.taskId = taskId;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }
    }

    @PostMapping("/suspend")
    @Operation(summary = "挂起待办（等条件成立；挂起期间仍可见但不能办理）")
    public Result<WfViews.TaskSummary> suspend(@RequestBody WfRequests.TaskOperation request) {
        return Result.success(viewMapper.toSummary(
                taskService.suspendTask(request.getTaskId(), request.getUserId())));
    }

    @PostMapping("/activate")
    @Operation(summary = "恢复被挂起的待办")
    public Result<WfViews.TaskSummary> activate(@RequestBody WfRequests.TaskOperation request) {
        return Result.success(viewMapper.toSummary(
                taskService.activateTask(request.getTaskId(), request.getUserId())));
    }

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
