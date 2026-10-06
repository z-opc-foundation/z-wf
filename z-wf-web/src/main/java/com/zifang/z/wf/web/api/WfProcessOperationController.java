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
import com.zifang.z.wf.core.view.WfActivityInstanceView;
import com.zifang.z.wf.core.persistence.WfMessageCorrelation;
import com.zifang.z.wf.core.service.WfActivityInstanceService;
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

    @Resource
    private WfActivityInstanceService activityInstanceService;

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
     * 消息关联：<b>不给流程实例 id，由引擎自己找到那条该被唤醒的流程</b>。
     *
     * <p>与 {@code /message} 的区别不是"多几个可选字段"，而是<b>调用方手里有什么</b>：
     * {@code /message} 要求你已经知道是哪一条流程实例；本端点面向的是
     * 「收到 ERP 回执，发一条 {@code erpDone}，按业务键与业务字段去找那条在等它的单」
     * 这种消息驱动集成的常态 —— 那时调用方手里只有业务键，没有任何引擎侧 id。
     *
     * <p><b>零匹配与多匹配都报错，且分开说</b>：「没有任何流程在等这条消息」
     * 与「有 3 条在等但业务键对不上」是两种完全不同的故障，合并成一句
     * "没找到"会让排查从零开始。多匹配时列出候选，不静默挑一条。
     *
     * <p><b>与 {@code /signal} 不构成一对</b>：唤醒全部的语义只有广播信号一个入口，
     * 刻意没有做「correlateAll」。
     */
    @PostMapping("/message/correlate")
    @Operation(summary = "015_消息关联：不给流程实例 id，按业务键 / 变量由引擎找到那条单")
    public Result<WfViews.ProcessInstanceView> correlateMessage(
            @RequestBody WfRequests.MessageCorrelation request) {
        if (request == null) {
            throw new WfEngineException("请求体不能为空");
        }
        WfMessageCorrelation criteria = new WfMessageCorrelation(request.getMessageName())
                .setProcessInstanceId(request.getProcessInstanceId())
                .setBusinessKey(request.getBusinessKey())
                .setDefinitionKey(request.getDefinitionKey())
                .setVariables(request.getVariables())
                .setLocalVariables(request.getLocalVariables());
        WfProcessInstance instance = runtimeService.correlate(criteria, request.getUserId(),
                request.getComment());
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

    /**
     * 改流程实例名称。
     *
     * <p>与 {@code businessKey} 不是一回事：businessKey 是业务方的单号
     * （对外、要做唯一性、要能被业务系统查回来），name 是给界面看的可读描述。
     * 这里只改 name，不碰 businessKey，也不碰实例状态。
     */
    @PostMapping("/name")
    @Operation(summary = "015_改流程实例名称：name 传 null 表示清空，传空白串报 400")
    public Result<WfViews.ProcessInstanceView> setName(
            @RequestBody WfRequests.ProcessInstanceName request) {
        if (request == null) {
            throw new com.zifang.z.wf.core.service.WfEngineException("请求体不能为空");
        }
        WfProcessInstance instance = runtimeService.setProcessInstanceName(
                request.getProcessInstanceId(), request.getName());
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
        // 活动实例树并进总览，而不是单开一个端点：前端"流程详情"页本来就要
        // 拿实例 + 待办 + 轨迹 + 订阅 + 故障，再单独发一次请求只为拿"并行分支在哪"，
        // 会让中间状态不一致（轨迹查完又被人办了，树里对不上）。
        // 注意它与 trail 是**两种切法**：trail 是时间序，tree 是结构。
        //
        // 实例不存在时**不要构树**。注意这里不能说「overview 对不存在的实例返回空」——
        // 那是错的：subscriptions 与 incidents 两项在 controller 里是无条件塞进去的，
        // 所以那个响应本来就有两个键（实测确认）。加树之前的既有行为是
        // 「没有 process / trail / openTasks，但有 subscriptions / incidents」，
        // 加树之后必须还是这样，否则一个只读视图的增强就改变了老接口的响应形状。
        // 复用上面已经取到的 overview，**不要再调一次 getProcessOverview**：
        // 那个方法存在的理由正是"一次算完、避免中间状态不一致"，
        // 在它算完之后又调一次，等于把它的理由作废还多一次查库。
        if (overview.get("process") != null) {
            overview.put("activityTree", activityInstanceService.getActivityInstance(
                    processInstanceId));
        }
        return Result.success(overview);
    }

    /**
     * 活动实例树 —— "这条单现在走到哪了，并发分支在哪，各分支停在哪一步"。
     *
     * <p><b>与 {@code GET /trail} 是两种切法，不是同一份数据的两种格式。</b>
     * trail 是<b>时间序</b>的事实记录；本树是<b>结构</b>。
     * 并行分支一旦跑起来，trail 里的行在时间上交错、看不出谁是谁的分支 ——
     * 而交错的历史行推不出层级，硬推会在并行分支上猜错，
     * 猜出来的层级比扁平轨迹更坏：它看起来可信。
     *
     * <p><b>流程结束了也照样返回</b>：执行令牌连同 parentId 留在库里，
     * 所以并发结构在结束后仍然可读。但它<b>不是</b>独立于历史的一份数据 ——
     * {@code DELETE /api/wf/history/cleanup} 会把令牌与历史一起清掉。
     * ⇒ <b>这棵树活多久，取决于历史保留多久</b>；实例查不到时这里返回 400 而不是空树。
     */
    @GetMapping("/activity-instance")
    @Operation(summary = "007a_活动实例树：并发结构 + 每条分支走过的步骤")
    public Result<WfActivityInstanceView> activityInstance(
            @RequestParam String processInstanceId) {
        return Result.success(activityInstanceService.getActivityInstance(processInstanceId));
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
