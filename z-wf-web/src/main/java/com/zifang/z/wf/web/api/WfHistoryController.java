package com.zifang.z.wf.web.api;

import java.util.Date;
import java.util.List;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.zifang.util.core.meta.Result;
import com.zifang.util.core.meta.page.PageResult;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.WfHistoricActivityInstanceQuery;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfProcessInstanceQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.persistence.WfVariableAuditQuery;
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.service.WfHistoryService;
import com.zifang.z.wf.core.service.WfJobService;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.service.WfTaskService;
import com.zifang.z.wf.web.dto.WfViews;
import com.zifang.z.wf.web.mapper.WfViewMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 历史查询与 Job 管理。
 *
 * <p>基址 {@code /api/wf/history}，对应 z-camuda 的 {@code HistoryController} 与
 * {@code ManagementController}（job 部分）。
 *
 * <p><b>为什么分页一律下推而不是"拉全量再切"。</b> 审批系统的历史表是跑得最久的表：
 * 一张单办结就留十几行活动、几个任务。拉全量的做法在测试数据下看不出问题，
 * 到了生产会同时出三个症状 —— 越翻越慢、total 是个假数（第 N 页根本翻不到）、
 * 一次请求把整张表读进内存。所以这里的每个列表端点都配一个同条件的 count，
 * total 来自 count 而不是 {@code list.size()}。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/wf/history")
@Tag(name = "005_历史与Job")
public class WfHistoryController {

    @Resource
    private WfHistoryService historyService;

    @Resource
    private WfRuntimeService runtimeService;

    @Resource
    private WfTaskService taskService;

    @Resource
    private WfJobService jobService;

    @Resource
    private WfViewMapper viewMapper;

    // ==================== 历史活动 ====================

    @GetMapping("/activities")
    @Operation(summary = "001_查历史活动（可组合条件 + 真实分页）")
    public Result<PageResult<WfViews.ActivityInstanceView>> activities(
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) String activityId,
            @RequestParam(required = false) String activityType,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) Long startedAfter,
            @RequestParam(required = false) Long startedBefore,
            @RequestParam(required = false) Long minDurationMillis,
            @RequestParam(defaultValue = "false") boolean orderByDurationDesc,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {

        WfHistoricActivityInstanceQuery query = new WfHistoricActivityInstanceQuery()
                .setProcessInstanceId(processInstanceId)
                .setProcessDefinitionKey(processDefinitionKey)
                .setActivityId(activityId)
                .setActivityType(activityType)
                .setAssignee(assignee)
                .setMinDurationMillis(minDurationMillis)
                .setPageNum(pageNum).setPageSize(pageSize);
        if (startedAfter != null) {
            query.setStartedAfter(new Date(startedAfter));
        }
        if (startedBefore != null) {
            query.setStartedBefore(new Date(startedBefore));
        }
        if (orderByDurationDesc) {
            query.orderByDurationDesc();
        }
        List<WfActivityInstance> rows = historyService.queryActivities(query);
        return Result.success(new PageResult<>(viewMapper.toActivityViews(rows),
                historyService.countActivities(query), pageNum, pageSize));
    }

    @GetMapping("/activities/average-duration")
    @Operation(summary = "002_各环节平均耗时（找瓶颈）")
    public Result<java.util.Map<String, Long>> averageDuration(
            @RequestParam String processDefinitionKey,
            @RequestParam(defaultValue = "100") int sampleLimit) {
        return Result.success(historyService.getAverageDurationByActivity(
                processDefinitionKey, sampleLimit));
    }

    // ==================== 历史任务 ====================

    @GetMapping("/tasks")
    @Operation(summary = "003_查历史任务（只含已办结）")
    public Result<PageResult<WfViews.TaskSummary>> tasks(
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String definitionId,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String completerId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {

        WfTaskQuery query = new WfTaskQuery()
                .setProcessInstanceId(processInstanceId)
                .setDefinitionId(definitionId)
                .setAssignee(assignee)
                .setCompleterId(completerId)
                .setPageNum(pageNum).setPageSize(pageSize);
        // 历史任务 = 已办结的任务。这是历史查询，语义上不可能包含未完成的
        List<WfTask> rows = historyService.queryCompletedTasks(query);
        return Result.success(new PageResult<>(viewMapper.toSummaries(rows),
                historyService.countCompletedTasks(query), pageNum, pageSize));
    }

    // ==================== 历史流程实例 ====================

    @GetMapping("/processes")
    @Operation(summary = "004_查历史流程实例（含三种终态）")
    public Result<PageResult<WfViews.ProcessInstanceView>> processes(
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) String businessKey,
            @RequestParam(required = false) String startUserId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {

        WfProcessInstanceQuery query = new WfProcessInstanceQuery()
                .setDefinitionKey(processDefinitionKey)
                .setBusinessKey(businessKey)
                .setStartUserId(startUserId)
                .setPageNum(pageNum).setPageSize(pageSize);
        List<WfProcessInstance> rows = historyService.queryFinishedProcesses(query);
        return Result.success(new PageResult<>(viewMapper.toProcessViews(rows),
                historyService.countFinishedProcesses(query), pageNum, pageSize));
    }

    // ==================== 历史清理 ====================

    @DeleteMapping("/cleanup")
    @Operation(summary = "005_清理历史（只删已结束流程）")
    public Result<Integer> cleanup(@RequestParam Long before) {
        if (before == null) {
            // service 层也会拒，但在这里挡一次能让错误信息更贴近调用方的语境
            throw new WfEngineException("清理时间点不能为空："
                    + "没有时间点就不成'清掉全部'");
        }
        return Result.success(historyService.deleteHistoryBefore(new Date(before)));
    }

    // ==================== Job ====================

    @GetMapping("/jobs")
    @Operation(summary = "006_查 job（按到期时刻正序）")
    public Result<PageResult<WfViews.JobView>> jobs(
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String elementId,
            @RequestParam(required = false) Boolean retriesExhausted,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {

        WfJobQuery query = new WfJobQuery()
                .setProcessInstanceId(processInstanceId)
                .setElementId(elementId)
                .setRetriesExhausted(retriesExhausted)
                .setPageNum(pageNum).setPageSize(pageSize);
        List<WfJob> rows = jobService.listJobs(query);
        return Result.success(new PageResult<>(viewMapper.toJobViews(rows),
                jobService.countJobs(query), pageNum, pageSize));
    }

    @GetMapping("/jobs/exhausted")
    @Operation(summary = "007_重试耗尽的 job（漏发的提醒在这里查）")
    public Result<PageResult<WfViews.JobView>> exhaustedJobs(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        WfJobQuery query = new WfJobQuery().setRetriesExhausted(Boolean.TRUE)
                .setPageNum(pageNum).setPageSize(pageSize);
        return Result.success(new PageResult<>(
                viewMapper.toJobViews(jobService.listJobs(query)),
                jobService.countJobs(query), pageNum, pageSize));
    }

    @PostMapping("/jobs/execute")
    @Operation(summary = "008_执行到期 job（引擎不自带定时器，由宿主调它）")
    public Result<Integer> executeJobs(@RequestParam(required = false) Long now) {
        return Result.success(jobService.executeDueJobs(
                now == null ? new Date() : new Date(now)));
    }

    @PostMapping("/jobs/execute-async")
    @Operation(summary = "010_执行到期异步 job（asyncBefore/asyncAfter 续跑）")
    public Result<Integer> executeAsyncJobs(@RequestParam(required = false) Long now) {
        return Result.success(jobService.executeAsyncJobs(
                now == null ? new Date() : new Date(now)));
    }

    // ==================== 变量变更审计 ====================

    @GetMapping("/variable-changes")
    @Operation(summary = "009_查变量变更审计（这个变量什么时候被谁改的）")
    public Result<PageResult<WfViews.VariableChangeView>> variableChanges(
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String variableName,
            @RequestParam(required = false) String changedBy,
            @RequestParam(required = false) Long changedFrom,
            @RequestParam(required = false) Long changedTo,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {

        WfVariableAuditQuery query = new WfVariableAuditQuery()
                .setProcessInstanceId(processInstanceId)
                .setVariableName(variableName)
                .setChangedBy(changedBy)
                .setPageNum(pageNum).setPageSize(pageSize);
        if (changedFrom != null) {
            query.setChangedFrom(new Date(changedFrom));
        }
        if (changedTo != null) {
            query.setChangedTo(new Date(changedTo));
        }
        List<WfComment> rows = historyService.queryVariableChanges(query);
        return Result.success(new PageResult<>(viewMapper.toVariableChangeViews(rows),
                historyService.countVariableChanges(query), pageNum, pageSize));
    }
}
