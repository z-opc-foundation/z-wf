package com.zifang.z.wf.web.api;

import java.util.ArrayList;
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
import com.zifang.util.core.meta.page.PageResult;
import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.WfProcessInstanceQuery;
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.service.WfTaskService;
import com.zifang.z.wf.web.dto.WfRequests;
import com.zifang.z.wf.web.dto.WfViews;
import com.zifang.z.wf.web.mapper.WfViewMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 审批中心 Controller —— 前端审批页的统一入口。
 *
 * <p>基址 {@code /api/approval-center}，端点与 z-camuda 同名同义，
 * 便于从 Camunda 迁移过来的前端不用改 URL。
 *
 * <p><b>鉴权不在本层</b>：与 z-camuda 一致，由 z-ctc 统一拦截。
 * 因此所有 {@code userId} 都来自请求参数，<b>本层不校验"这个 userId 是不是调用者"</b> ——
 * 把它交给统一鉴权层比对，否则每个接口都要写一遍同样的检查。
 * 若本仓独立部署且无 z-ctc，业务方必须自行在网关层补上身份校验。
 *
 * <p>异常约定：业务异常（{@link WfEngineException}）返回 400 + 失败消息，
 * 由 {@link WfExceptionAdvice} 统一转换。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/approval-center")
@Tag(name = "001_审批中心")
public class WfApprovalCenterController {

    @Resource
    private WfRuntimeService runtimeService;

    @Resource
    private WfTaskService taskService;

    @Resource
    private WfRepositoryService repositoryService;

    @Resource
    private WfViewMapper viewMapper;

    // ==================== 1. 仪表盘 ====================

    @GetMapping("/dashboard")
    @Operation(summary = "001_获取仪表盘统计")
    public Result<WfViews.DashboardStats> dashboard(
            @Parameter(description = "当前用户ID") @RequestParam String userId) {

        WfViews.DashboardStats stats = new WfViews.DashboardStats();
        List<WfTask> todo = taskService.getTodoList(userId, null, 1, 10000);
        stats.setTodoCount(todo.size());
        for (WfTask task : todo) {
            if (task.isOverdue()) {
                stats.setOverdueCount(stats.getOverdueCount() + 1);
            }
        }
        stats.setDoneCount(countDone(userId));
        stats.setMyProcessCount(countStarted(userId));
        stats.setCcCount(0L);
        return Result.success(stats);
    }

    // ==================== 2. 待办 / 已办 ====================

    @GetMapping("/tasks/todo")
    @Operation(summary = "002_我的待办")
    public Result<PageResult<WfViews.TaskSummary>> todoList(
            @RequestParam String userId,
            @RequestParam(required = false) String groups,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {

        List<String> groupList = splitCsv(groups);
        // 分页下推 + 真实 total。此前是"拉 10000 条再在内存里切"，
        // 后果是待办超过 1 万条时第 2 页起永远拿不到，而页面上的"共 N 条"就是那个 10000
        List<WfTask> rows = taskService.getTodoList(userId, groupList, pageNum, pageSize);
        return Result.success(new PageResult<>(viewMapper.toSummaries(rows),
                taskService.countTodoList(userId, groupList), pageNum, pageSize));
    }

    @GetMapping("/tasks/done")
    @Operation(summary = "003_我的已办")
    public Result<PageResult<WfViews.TaskSummary>> doneList(
            @RequestParam String userId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {

        List<WfTask> rows = taskService.getDoneList(userId, pageNum, pageSize);
        return Result.success(new PageResult<>(viewMapper.toSummaries(rows),
                taskService.countDoneList(userId), pageNum, pageSize));
    }

    @GetMapping("/tasks/get")
    @Operation(summary = "004_任务详情")
    public Result<WfViews.TaskDetail> taskDetail(@RequestParam String taskId) {
        return Result.success(viewMapper.toDetail(taskService.getTask(taskId)));
    }

    @PostMapping("/tasks/complete")
    @Operation(summary = "005_办理任务（审批通过/驳回）")
    public Result<WfViews.ProcessInstanceView> completeTask(
            @RequestBody WfRequests.CompleteTask request) {
        WfProcessInstance instance = runtimeService.completeTask(
                request.getTaskId(), request.getUserId(),
                request.getComment(), request.getVariables());
        return Result.success(viewMapper.toProcessView(instance));
    }

    @GetMapping("/tasks/claimable")
    @Operation(summary = "006_可认领任务")
    public Result<PageResult<WfViews.TaskSummary>> claimableList(
            @RequestParam String userId,
            @RequestParam(required = false) String groups,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {

        List<String> groupList = splitCsv(groups);
        List<WfTask> rows = taskService.getClaimableList(userId, groupList, pageNum, pageSize);
        return Result.success(new PageResult<>(viewMapper.toSummaries(rows),
                taskService.countClaimableList(groupList), pageNum, pageSize));
    }

    // ==================== 3. 流程实例 ====================

    @GetMapping("/my-processes")
    @Operation(summary = "007_我发起的流程")
    public Result<PageResult<WfViews.ProcessInstanceView>> myProcesses(
            @RequestParam String userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {

        WfProcessInstanceQuery query = new WfProcessInstanceQuery()
                .setStartUserId(userId)
                .setPageNum(pageNum).setPageSize(pageSize);
        if (status != null && !status.trim().isEmpty()) {
            query.setStatus(WfProcessStatus.valueOf(status.trim()));
        }
        List<WfViews.ProcessInstanceView> views = viewMapper.toProcessViews(
                runtimeService.queryProcessInstances(query));
        return Result.success(new PageResult<>(views,
                runtimeService.countProcessInstances(query), pageNum, pageSize));
    }

    @GetMapping("/processes/get")
    @Operation(summary = "008_流程详情（实例+待办+轨迹+评论）")
    public Result<WfViews.ProcessDetail> processDetail(@RequestParam String processInstanceId) {
        WfProcessInstance instance = runtimeService.getProcessInstance(processInstanceId);
        if (instance == null) {
            return Result.fail("流程实例不存在: " + processInstanceId);
        }
        WfViews.ProcessDetail detail = new WfViews.ProcessDetail();
        copyProcess(viewMapper.toProcessView(instance), detail);
        detail.setOpenTasks(viewMapper.toSummaries(
                taskService.getOpenTasks(processInstanceId)));
        detail.setTrail(viewMapper.toTrail(processInstanceId));
        detail.setComments(viewMapper.toComments(processInstanceId));
        detail.setVariables(instance.getVariables());
        return Result.success(detail);
    }

    @PostMapping("/processes/start")
    @Operation(summary = "009_发起流程")
    public Result<String> startProcess(@RequestBody WfRequests.StartProcess request) {
        String processId = runtimeService.startProcessInstance(
                request.getDefinitionKey(), request.getVersion(),
                request.getBusinessKey(), request.getUserId(),
                request.getDeptId(), request.getVariables());
        if (processId == null) {
            return Result.fail("流程启动被钩子否决");
        }
        return Result.success(processId);
    }

    @GetMapping("/processes/definitions")
    @Operation(summary = "010_可发起的流程定义")
    public Result<List<WfViews.DefinitionView>> definitions() {
        List<WfViews.DefinitionView> result = new ArrayList<>();
        for (WfDefinition definition : repositoryService.getAllDefinitions()) {
            WfViews.DefinitionView view = new WfViews.DefinitionView();
            view.setKey(definition.getKey());
            view.setName(definition.getName());
            view.setVersion(definition.getVersion());
            view.setCategory(definition.getCategory());
            view.setDescription(definition.getDescription());
            view.setNodeCount(definition.getNodes().size());
            view.setFlowCount(definition.getFlows().size());
            result.add(view);
        }
        return Result.success(result);
    }

    @GetMapping("/processes/versions")
    @Operation(summary = "011_流程定义历史版本")
    public Result<List<WfViews.DefinitionView>> definitionVersions(@RequestParam String key) {
        List<WfViews.DefinitionView> result = new ArrayList<>();
        for (WfDefinition definition : repositoryService.getDefinitionVersions(key)) {
            WfViews.DefinitionView view = new WfViews.DefinitionView();
            view.setKey(definition.getKey());
            view.setName(definition.getName());
            view.setVersion(definition.getVersion());
            view.setCategory(definition.getCategory());
            result.add(view);
        }
        return Result.success(result);
    }

    @GetMapping("/processes/search")
    @Operation(summary = "012_按业务键/状态搜索流程")
    public Result<PageResult<WfViews.ProcessInstanceView>> searchProcesses(
            @RequestParam(required = false) String businessKey,
            @RequestParam(required = false) String definitionKey,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {

        WfProcessInstanceQuery query = new WfProcessInstanceQuery()
                .setPageNum(pageNum).setPageSize(pageSize);
        if (isNotBlank(businessKey)) {
            query.setBusinessKey(businessKey);
        }
        if (isNotBlank(definitionKey)) {
            query.setDefinitionKey(definitionKey);
        }
        if (isNotBlank(userId)) {
            query.setStartUserId(userId);
        }
        if (isNotBlank(status)) {
            query.setStatus(WfProcessStatus.valueOf(status.trim()));
        }
        // total 必须单独问：queryProcessInstances 返回的是当前页，
        // 拿它的 size() 当总数会让前端分页器认为只有一页
        long total = runtimeService.countProcessInstances(query);
        List<WfProcessInstance> found = runtimeService.queryProcessInstances(query);
        return Result.success(new PageResult<WfViews.ProcessInstanceView>(
                viewMapper.toProcessViews(found), total, pageNum, pageSize));
    }

    @DeleteMapping("/processes")
    @Operation(summary = "013_终止流程实例")
    public Result<Void> terminateProcess(@RequestParam String processInstanceId,
                                         @RequestParam(required = false) String reason) {
        runtimeService.terminate(processInstanceId, reason);
        return Result.success();
    }

    // ==================== 内部 ====================

    private long countDone(String userId) {
        return taskService.getDoneList(userId, 1, 10000).size();
    }

    private long countStarted(String userId) {
        return runtimeService.queryProcessInstances(new WfProcessInstanceQuery()
                .setStartUserId(userId).setPageNum(1).setPageSize(10000)).size();
    }

    private PageResult<WfViews.TaskSummary> page(List<WfViews.TaskSummary> all,
                                                 int pageNum, int pageSize) {
        int from = Math.max(0, (pageNum - 1) * pageSize);
        if (from >= all.size()) {
            return new PageResult<>(new ArrayList<>(), all.size(), pageNum, pageSize);
        }
        int to = Math.min(all.size(), from + pageSize);
        return new PageResult<>(all.subList(from, to), all.size(), pageNum, pageSize);
    }

    private PageResult<WfViews.ProcessInstanceView> pageProcesses(
            List<WfViews.ProcessInstanceView> all, int pageNum, int pageSize) {
        int from = Math.max(0, (pageNum - 1) * pageSize);
        if (from >= all.size()) {
            return new PageResult<>(new ArrayList<>(), all.size(), pageNum, pageSize);
        }
        int to = Math.min(all.size(), from + pageSize);
        return new PageResult<>(all.subList(from, to), all.size(), pageNum, pageSize);
    }

    private void copyProcess(WfViews.ProcessInstanceView from, WfViews.ProcessDetail to) {
        to.setProcessInstanceId(from.getProcessInstanceId());
        to.setDefinitionKey(from.getDefinitionKey());
        to.setDefinitionId(from.getDefinitionId());
        to.setDefinitionVersion(from.getDefinitionVersion());
        to.setBusinessKey(from.getBusinessKey());
        to.setStartUserId(from.getStartUserId());
        to.setStartDeptId(from.getStartDeptId());
        to.setCategory(from.getCategory());
        to.setStatus(from.getStatus());
        to.setResult(from.getResult());
        to.setStartTime(from.getStartTime());
        to.setEndTime(from.getEndTime());
        to.setDurationMillis(from.getDurationMillis());
        to.setOpenTaskCount(from.getOpenTaskCount());
    }

    private List<String> splitCsv(String csv) {
        List<String> result = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) {
            return result;
        }
        for (String part : csv.split(",")) {
            if (!part.trim().isEmpty()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
