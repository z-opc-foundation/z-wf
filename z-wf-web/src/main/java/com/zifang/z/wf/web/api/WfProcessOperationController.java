package com.zifang.z.wf.web.api;

import java.util.ArrayList;
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
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.service.WfHistoryService;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfIncidentService;
import com.zifang.z.wf.core.service.WfSubscriptionService;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.service.WfVariableService;
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

    @Resource
    private WfVariableService variableService;

    @Resource
    private WfSubscriptionService subscriptionService;

    @Resource
    private WfIncidentService incidentService;

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

    /**
     * 投递消息（点对点）。
     *
     * <p>一个端点同时能叫醒三种等待者：事件网关的分支、消息边界订阅、receiveTask。
     * 顺序由 {@link WfRuntimeService#triggerMessage} 定死并在那边写了理由 ——
     * 事件网关是竞速、消息边界是打断、receiveTask 是等着继续，三者对同一条消息的
     * 反应完全不同，谁先判就决定了这条消息落到哪种语义上。
     */
    @PostMapping("/message")
    @Operation(summary = "012_投递消息：点对点，可唤醒事件网关分支 / 消息边界 / 接收任务")
    public Result<WfViews.ProcessInstanceView> deliverMessage(
            @RequestBody WfRequests.EventDelivery request) {
        WfProcessInstance instance = runtimeService.triggerMessage(request.getName(),
                request.getProcessInstanceId(), request.getUserId(),
                request.getVariables(), request.getComment());
        return Result.success(viewMapper.toProcessView(instance));
    }

    /**
     * 广播信号：叫醒<b>全部</b>等待该信号名的接收者。
     *
     * <p>与消息分两个端点而不是加一个 boolean 开关，是为了让"我以为我唤醒了一个"
     * 这种误用在调用处就暴露出来 —— 那是这类接口最常见的线上事故。
     */
    @PostMapping("/signal")
    @Operation(summary = "013_广播信号：唤醒全部等待者，可同时推进多个实例")
    public Result<List<WfViews.ProcessInstanceView>> broadcastSignal(
            @RequestBody WfRequests.EventDelivery request) {
        List<WfProcessInstance> advanced = runtimeService.broadcastSignal(request.getName(),
                request.getUserId(), request.getVariables(), request.getComment());
        List<WfViews.ProcessInstanceView> payload = new ArrayList<>();
        for (WfProcessInstance instance : advanced) {
            payload.add(viewMapper.toProcessView(instance));
        }
        return Result.success(payload);
    }

    /**
     * 实例迁移 —— 把 token 挪到指定节点并从那里继续。
     *
     * <p><b>与 {@code /api/wf/tasks/jump} 的区别</b>：jump 入口是任务，
     * 所以一条正在等消息 / 等信号 / 等定时器 / 等外部 worker 的流程根本跳不动 ——
     * 它没有任务。而"改流程后把在途的单迁过去"最常发生在这种单上。
     *
     * <p><b>权限提示</b>：与 {@code jump} / {@code force-complete} 同级 ——
     * 它能把任意在途流程挪到任意节点，绕过全部业务规则。若本仓无 z-ctc 统一鉴权，
     * 必须在网关层限制这个路径的访问。
     */
    @PostMapping("/move")
    @Operation(summary = "014_实例迁移：把在途流程的 token 挪到指定节点（含等事件/等定时器的单）")
    public Result<WfViews.ProcessInstanceView> move(
            @RequestBody WfRequests.InstanceMigration request) {
        if (request == null) {
            throw new com.zifang.z.wf.core.service.WfEngineException("请求体不能为空");
        }
        WfProcessInstance instance = runtimeService.move(request.getProcessInstanceId(),
                request.getTargetActivityId(), request.getSourceActivityId(),
                request.getUserId(), request.getReason(), request.getVariables());
        return Result.success(viewMapper.toProcessView(instance));
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
    @Operation(summary = "007_流程总览（实例+待办+轨迹+评论+当前等待）")
    public Result<Map<String, Object>> overview(@RequestParam String processInstanceId) {
        Map<String, Object> overview = historyService.getProcessOverview(processInstanceId);
        // 在 web 这一层把"当前等待"并进总览，而不是让 WfHistoryService 依赖
        // WfSubscriptionService：订阅是**运行期**状态，而 history 管的是已经发生的事，
        // 让它去依赖运行期是分层倒置。视图的组装本来就归 web。
        //
        // 之所以非加不可：一条在等消息的流程在待办、轨迹、评论里都看不到任何东西，
        // 而且没有任何报错。没有这一段，"这条单子怎么不动了"只能靠翻 XML 猜
        overview.put("subscriptions", subscriptionService.subscriptionsOf(processInstanceId));
        // 故障与订阅要一起给：只给订阅时，"单子不动了"分不清是在耐心等还是已经炸了，
        // 而这两者的处置完全不同。与订阅同理，放在 web 层合并，
        // 不让 WfHistoryService 去依赖运行期状态
        overview.put("incidents", incidentService.incidentsOf(processInstanceId));
        return Result.success(overview);
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

    @GetMapping("/variables")
    @Operation(summary = "010_读取流程变量")
    public Result<Map<String, Object>> variables(@RequestParam String processInstanceId) {
        return Result.success(variableService.getVariables(processInstanceId));
    }

    @PostMapping("/variables")
    @Operation(summary = "011_修改变量（整批只落一次库，并留审计记录）")
    public Result<WfViews.ProcessInstanceView> updateVariables(
            @RequestBody WfRequests.VariableOperation request) {
        // 删除与赋值走两个分支而不是"值为 null 即删除"：
        // 后者一旦被误用，赋值会静默变成删除，删掉的变量又会让引用它的
        // 条件表达式走 fail-closed 分支，把流程带向另一条路。
        if (request.isRemove()) {
            for (String name : request.getNames()) {
                variableService.removeVariable(
                        request.getProcessInstanceId(), name, request.getUserId());
            }
        } else {
            variableService.setVariables(request.getProcessInstanceId(),
                    request.getValues(), request.getUserId());
        }
        return Result.success(viewMapper.toProcessView(
                runtimeService.getProcessInstance(request.getProcessInstanceId())));
    }

    // ==================== 分支级变量 ====================

    @GetMapping("/branch-variables")
    @Operation(summary = "011b_读某条分支的局部变量（不做作用域回退）")
    public Result<Map<String, Object>> branchVariables(@RequestParam String taskId) {
        return Result.success(variableService.getVariablesLocal(
                variableService.executionIdOfTask(taskId)));
    }

    @PostMapping("/branch-variables")
    @Operation(summary = "011c_改某条分支的局部变量（并行分支各改各的，不互相污染）")
    public Result<Map<String, Object>> updateBranchVariables(
            @RequestBody WfRequests.LocalVariableOperation request) {
        if (request == null) {
            throw new WfEngineException("请求体不能为空");
        }
        String executionId = variableService.executionIdOfTask(request.getTaskId());
        // 删除与赋值分两个分支，不复用「值为 null 即删除」：
        // 那套约定在流程级已经解释过一次，分支级照样成立 ——
        // 被误用成删除时，条件表达式读不到变量会走 fail-closed 把流程带向另一条路
        if (request.isRemove()) {
            for (String name : request.getNames()) {
                variableService.removeVariableLocal(executionId, name, request.getUserId());
            }
        } else {
            for (Map.Entry<String, Object> entry : request.getValues().entrySet()) {
                variableService.setVariableLocal(executionId, entry.getKey(),
                        entry.getValue(), request.getUserId());
            }
        }
        return Result.success(variableService.getVariablesLocal(executionId));
    }
}
