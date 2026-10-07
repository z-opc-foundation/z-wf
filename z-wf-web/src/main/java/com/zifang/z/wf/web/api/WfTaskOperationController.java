package com.zifang.z.wf.web.api;

import java.util.Date;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    /**
     * 调整管理属性：优先级 / 到期时间 / 候选用户 / 候选组（第 45 轮补 REST 入口）。
     *
     * <p>service 层的 {@code WfTaskService#updateTask} <b>早就存在</b>，
     * 但一直没有 HTTP 入口 —— 于是「调高优先级」「延长办理期限」这两件
     * 审批系统天天要做的事，前端只能改数据库。
     * ⇒ 这一条把那个半套补齐。
     *
     * <p><b>{@code null} 表示"这一项不改"，而不是"把它清空"</b>：
     * 改期与优先级都很难表达"清空截止时间"这个意图
     * （清空 DDL 列与"没有过"在业务上不是一回事），
     * 与 {@code WfProcessInstance#name}「空串报错、null 才表示清空」是同一条规矩。
     * 候选用户与候选组传空数组才是"清空"。
     *
     * <p><b>时间用毫秒时间戳</b>，与本仓所有视图（{@code WfViews.TaskSummary#dueDate}）
     * 和 {@code WfFilterService} 的时间条件保持同一口径 ——
     * 存字符串的日期在不同库、不同机器上会按本地时区解释，
     * 同一段调用换个环境就查出一批不同的单。
     */
    @PutMapping("/management")
    @Operation(summary = "008_调整管理属性（优先级 / 到期时间 / 候选池；null 表示这一项不改）")
    public Result<WfViews.TaskSummary> management(@RequestBody ManagementRequest request) {
        if (request == null || request.getTaskId() == null || request.getTaskId().trim().isEmpty()) {
            throw new com.zifang.z.wf.core.service.WfEngineException("taskId 不能为空");
        }
        if (request.getPriority() == null && request.getDueDate() == null
                && request.getCandidateUsers() == null && request.getCandidateGroups() == null) {
            // 一个都没给就返回成功，等于"调了一下管理属性"而什么都没发生，
            // 调用方看到 200 就以为改成了 —— **比报错难查得多**
            throw new com.zifang.z.wf.core.service.WfEngineException(
                    "要调就至少给一项：priority / dueDate / candidateUsers / candidateGroups。"
                            + "四项全是空的话这次调用什么都不会改。");
        }
        Date due = request.getDueDate() == null ? null : new Date(request.getDueDate().longValue());
        WfTask task = taskService.updateTask(request.getTaskId().trim(), request.getPriority(),
                due, request.getCandidateUsers(), request.getCandidateGroups());
        return Result.success(viewMapper.toSummary(task));
    }

    /** {@code PUT /management} 的入参。 */
    public static class ManagementRequest {
        private String taskId;
        private Integer priority;
        /** 毫秒时间戳；{@code null} = 不改。 */
        private Long dueDate;
        /** 空数组 = 清空候选用户；{@code null} = 不改。 */
        private java.util.List<String> candidateUsers;
        /** 空数组 = 清空候选组；{@code null} = 不改。 */
        private java.util.List<String> candidateGroups;

        public String getTaskId() {
            return taskId;
        }

        public void setTaskId(String taskId) {
            this.taskId = taskId;
        }

        public Integer getPriority() {
            return priority;
        }

        public void setPriority(Integer priority) {
            this.priority = priority;
        }

        public Long getDueDate() {
            return dueDate;
        }

        public void setDueDate(Long dueDate) {
            this.dueDate = dueDate;
        }

        public java.util.List<String> getCandidateUsers() {
            return candidateUsers;
        }

        public void setCandidateUsers(java.util.List<String> candidateUsers) {
            this.candidateUsers = candidateUsers;
        }

        public java.util.List<String> getCandidateGroups() {
            return candidateGroups;
        }

        public void setCandidateGroups(java.util.List<String> candidateGroups) {
            this.candidateGroups = candidateGroups;
        }
    }
}
