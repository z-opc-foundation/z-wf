package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfFlow;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.definition.WfTimerSupport;
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.engine.WfIdGenerator;
import com.zifang.z.wf.core.engine.WfMultiInstance;
import com.zifang.z.wf.core.engine.WfSubProcessLauncher;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.persistence.WfProcessInstanceQuery;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 运行时服务 —— 流程实例的发起 / 推进 / 挂起 / 终止。
 *
 * <p>对应 z-camuda 侧的 {@code RuntimeService}，但实现完全自研。
 * 它是<b>事务边界</b>：一次 {@code startProcessInstance} / {@code completeTask} 里
 * 引擎产生的所有 token、任务、历史都先在内存 context 里攒齐，最后统一落库。
 *
 * <p><b>为什么先攒后落</b>：如果边推进边落库，中途失败会留下"token 已前进但任务没建"的半截状态，
 * 排障时极难还原。先攒齐再一次提交，失败就整体不落（内存实现天然满足；
 * JDBC 实现由 {@link #persistAll} 在单个事务里完成）。
 *
 * <p>本类实现 {@link WfSubProcessLauncher}，因此 callActivity 有了真实行为。
 *
 * @author zifang
 */
public class WfRuntimeService implements WfSubProcessLauncher {

    private static final Logger log = LoggerFactory.getLogger(WfRuntimeService.class);

    /** 子实例 id 在父流程变量里的存放前缀，键为 callActivity 节点 id。 */
    public static final String SUB_INSTANCE_PREFIX = "__wf_sub_";

    /** 子流程结果在父流程变量里的固定名（未配 resultExpression 时也写入）。 */
    public static final String SUB_RESULT_VARIABLE = "__wf_sub_result";

    /** 被调流程实例 id 在父流程变量里的固定名。 */
    public static final String SUB_INSTANCE_VARIABLE = "__wf_sub_instance";

    private final WfRepositoryService repositoryService;

    private final WfPersistence persistence;

    private final WfEngine engine;

    private final WfHookDispatcher hookDispatcher;

    private final WfIdGenerator idGenerator;

    public WfRuntimeService(WfRepositoryService repositoryService,
                            WfPersistence persistence,
                            WfEngine engine,
                            WfHookDispatcher hookDispatcher) {
        this(repositoryService, persistence, engine, hookDispatcher,
                new WfIdGenerator.DefaultWfIdGenerator());
    }

    public WfRuntimeService(WfRepositoryService repositoryService,
                            WfPersistence persistence,
                            WfEngine engine,
                            WfHookDispatcher hookDispatcher,
                            WfIdGenerator idGenerator) {
        this.repositoryService = repositoryService;
        this.persistence = persistence;
        this.engine = engine;
        this.hookDispatcher = hookDispatcher;
        this.idGenerator = idGenerator;
    }

    // ==================== 发起 ====================

    /**
     * 启动流程实例（用最新版本定义）。
     *
     * @return 流程实例 ID；被钩子否决时返回 {@code null}
     */
    public String startProcessInstance(String definitionKey, String businessKey, String userId,
                                       Map<String, Object> variables) {
        return startProcessInstance(definitionKey, null, businessKey, userId, null, variables);
    }

    /**
     * 启动流程实例。
     *
     * @param version  指定版本；{@code null} = 最新
     * @return 流程实例 ID；被钩子否决时返回 {@code null}
     */
    public String startProcessInstance(String definitionKey, Integer version, String businessKey,
                                       String userId, String deptId, Map<String, Object> variables) {
        WfDefinition definition = repositoryService.getDefinitionOrLatest(definitionKey, version);
        return startProcessInstance(definition, businessKey, userId, deptId, variables);
    }

    /**
     * 启动流程实例（定义对象已知）。
     *
     * @return 流程实例 ID；被钩子否决时返回 {@code null}
     */
    public String startProcessInstance(WfDefinition definition, String businessKey, String userId,
                                       String deptId, Map<String, Object> variables) {
        return startProcessInstance(definition, null, businessKey, userId, deptId, variables);
    }

    /**
     * 从指定起始节点启动。
     *
     * <p>{@code startNode} 传 {@code null} 表示走无条件入口（{@link WfDefinition#startNode}）。
     * 不做成两个各写一遍的方法，是因为"建实例 → 钩子 → 落库 → 终态判定 → 后置钩子"
     * 这一整套有 5 步，抄一遍就多一处将来只改一边的可能。
     */
    public String startProcessInstance(WfDefinition definition, WfNode startNode,
                                       String businessKey, String userId,
                                       String deptId, Map<String, Object> variables) {
        // ---- 0. 定义必须已落库，否则启动即制造一个永远推不动的死实例 ----
        // 流程一旦停在等待态，后续 completeTask / advance 都要靠 definitionKey+version
        // 从仓储重新载入定义；没 deploy 过的定义拿不回来，于是每推进一步都报
        // "流程定义不存在"。而这个错误出现在**完全不同的调用点**上，
        // 启动时一切正常、待办也建出来了，很难联想到是启动姿势的问题。
        // 所以在这里就挡掉，并说清楚正确用法。
        WfDefinition persisted = repositoryService.findPersisted(definition.getKey(),
                definition.getVersion());
        if (persisted == null) {
            throw new WfDefinitionException("流程定义 [" + definition.getKey()
                    + "] 尚未部署，无法启动。实例一旦停在等待态就再也推不动了，"
                    + "请先调用 WfRepositoryService#deploy 部署该定义");
        }

        // ---- 0.5 停用的版本不能再接新单 ----
        // 判据取 **persisted** 那一份而不是入参 definition：调用方手里的定义对象可能
        // 是停用之前取的，带着过期的 suspended=false，拿它判等于这道闸门形同虚设。
        // 已在跑的实例不受影响，停用只挡新启动 —— 这是"下架老版本"和"终止在跑的"两件事。
        if (persisted.isSuspended()) {
            throw new WfDefinitionException("流程定义 [" + definition.getKey() + ":"
                    + definition.getVersion() + "] 已停用，不能启动新实例。"
                    + "如需恢复请调用 WfRepositoryService#activateDefinition");
        }

        // ---- 1. 前置钩子（可否决）----
        if (!hookDispatcher.fireBeforeStart(definition.getKey(), variables)) {
            log.info("流程启动被钩子否决: {}", definition.getKey());
            return null;
        }

        // ---- 2. 建实例 ----
        WfProcessInstance instance = new WfProcessInstance(
                idGenerator.nextProcessInstanceId(),
                definition.getKey(),
                definition.getKey() + ":" + definition.getVersion());
        instance.setDefinitionVersion(definition.getVersion());
        instance.setBusinessKey(businessKey);
        instance.setStartUserId(userId);
        instance.setStartDeptId(deptId);
        instance.setCategory(definition.getCategory());
        instance.setStatus(WfProcessStatus.ACTIVE);
        instance.setStartTime(new Date());
        if (variables != null) {
            instance.setVariables(new HashMap<>(variables));
        }

        // ---- 3. 引擎推进 ----
        WfContext context = newContext(definition, instance, null);
        context.setAuthenticatedUserId(userId);
        context.setSubProcessLauncher(this);
        engine.startAt(context, startNode == null ? definition.startNode() : startNode);

        // ---- 4. 落库 ----
        persistAll(context);
        resolveCompletion(context);

        // ---- 5. 后置钩子 ----
        hookDispatcher.fireAfterStart(definition.getKey(), instance.getId(), instance.getVariables());
        if (instance.getStatus() == WfProcessStatus.COMPLETED) {
            hookDispatcher.fireComplete(definition.getKey(), instance.getId(), instance.getResult());
        }

        return instance.getId();
    }

    // ==================== 消息 / 信号启动 ====================

    /**
     * 收到消息就启动流程（不指定定义 —— 跨定义找订阅了这条消息的那个）。
     *
     * <p><b>与 {@link #triggerMessage} 是两件不同的事，别混</b>：
     * {@code triggerMessage} 唤醒<b>已经在等</b>这条消息的实例；这里创建<b>新</b>实例。
     * 一个流程实例身上可以同时挂着这两件事（消息起始 + 消息边界），
     * 收到一条消息时两个都可能发生，调用方得自己分清要哪一种。
     *
     * <p>不带版本号找定义是刻意的：触发方（另一个系统）通常只知道自己发了
     * 一条"订单已创建"，不知道也不该知道我们把这个流程部署了几个版本。
     * 若多个定义都订阅了这条消息，报错并列出是哪几个 ——
     * 静默挑一个启动的后果是"流程起来了但不是预期的那个"，而调用方看不出问题。
     *
     * @return 流程实例 ID；被钩子否决时返回 {@code null}
     */
    public String startProcessInstanceByMessage(String messageName, String businessKey,
                                                String userId, String deptId,
                                                Map<String, Object> variables) {
        return startProcessInstanceByMessage(messageName, null, businessKey, userId, deptId, variables);
    }

    /** 同上，但显式指定定义 key —— 跨定义查找有歧义时用它消除歧义。 */
    public String startProcessInstanceByMessage(String messageName, String definitionKey,
                                                String businessKey, String userId,
                                                String deptId, Map<String, Object> variables) {
        WfDefinition definition = resolveEventStartDefinition(messageName, definitionKey, true);
        WfNode startNode = definition.messageStartNode(messageName);
        if (startNode == null) {
            throw new WfEngineException("流程定义 [" + definition.getKey() + "] 里没有订阅消息 ["
                    + messageName + "] 的起始事件。该定义只能用无条件入口启动，"
                    + "或换一个订阅了这条消息的定义");
        }
        return startProcessInstance(definition, startNode, businessKey, userId, deptId, variables);
    }

    /** 收到信号就启动流程。语义与 {@link #startProcessInstanceByMessage} 一一对应。 */
    public String startProcessInstanceBySignal(String signalName, String businessKey,
                                               String userId, String deptId,
                                               Map<String, Object> variables) {
        return startProcessInstanceBySignal(signalName, null, businessKey, userId, deptId, variables);
    }

    /** 同上，但显式指定定义 key。 */
    public String startProcessInstanceBySignal(String signalName, String definitionKey,
                                               String businessKey, String userId,
                                               String deptId, Map<String, Object> variables) {
        WfDefinition definition = resolveEventStartDefinition(signalName, definitionKey, false);
        WfNode startNode = definition.signalStartNode(signalName);
        if (startNode == null) {
            throw new WfEngineException("流程定义 [" + definition.getKey() + "] 里没有订阅信号 ["
                    + signalName + "] 的起始事件。该定义只能用无条件入口启动，"
                    + "或换一个订阅了这个信号的定义");
        }
        return startProcessInstance(definition, startNode, businessKey, userId, deptId, variables);
    }

    /**
     * 找到订阅了该消息 / 信号的定义。
     *
     * <p>指定了 {@code definitionKey} 就只在这一份里找 —— 调用方显式点名时，
     * 即使别处也有同名订阅也不该在这里报歧义，那是两件事。
     *
     * <p>跨定义扫描只看<b>未被停用</b>的版本：停用是"这一版不再接新单"，
     * 让一条停用的消息起始继续接单，运维会以为自己已经下架了它。
     * 同一 key 的多个版本都订阅时取最新 —— 消息启动不带版本号，
     * "该用哪个版本"不该由触发方回答。
     */
    private WfDefinition resolveEventStartDefinition(String eventName, String definitionKey,
                                                     boolean message) {
        if (eventName == null || eventName.trim().isEmpty()) {
            throw new WfEngineException((message ? "消息" : "信号") + "名不能为空");
        }
        if (definitionKey != null && !definitionKey.trim().isEmpty()) {
            // 这里刻意**不**再查一次停用：闸门在 startProcessInstance 的 0.5 步，
            // 是所有启动路径共用的那一条。再查一遍不仅多余，还会让人以为
            // 消息启动有自己一道独立的闸门 —— 而真删掉它，行为完全一样。
            return repositoryService.getDefinitionOrLatest(definitionKey, null);
        }
        String wanted = eventName.trim();
        List<WfDefinition> hits = new ArrayList<WfDefinition>();
        for (WfDefinition each : repositoryService.getAllDefinitions()) {
            if (each == null || each.isSuspended()) {
                continue;
            }
            boolean matched = message
                    ? each.messageStartNode(wanted) != null
                    : each.signalStartNode(wanted) != null;
            if (matched) {
                hits.add(each);
            }
        }
        if (hits.isEmpty()) {
            throw new WfEngineException("没有流程订阅" + (message ? "消息" : "信号") + " [" + wanted
                    + "] 的起始事件。检查对应定义的 startEvent 上是否写了 "
                    + (message ? "messageEventDefinition" : "signalEventDefinition")
                    + " 且 messageRef/signalRef 与此一致");
        }
        if (hits.size() > 1) {
            List<String> keys = new ArrayList<String>();
            for (WfDefinition each : hits) {
                keys.add(each.getKey() + ":" + each.getVersion());
            }
            throw new WfEngineException((message ? "消息" : "信号") + " [" + wanted
                    + "] 被多个流程订阅: " + keys + "。引擎无法判断该起哪一个 —— "
                    + "请改用带 definitionKey 的重载显式指定");
        }
        return hits.get(0);
    }

    // ==================== 推进 ====================

    /**
     * 推进流程（把挂起的 token 往前推一步）。
     *
     * <p>典型用法：外部消息触发 receiveTask 后调用。
     *
     * @return 推进后的实例（可能已结束）
     */
    public WfProcessInstance advance(String processInstanceId, Map<String, Object> variables) {
        WfProcessInstance instance = requireInstance(processInstanceId);
        if (instance.getStatus() == WfProcessStatus.SUSPENDED) {
            throw new WfEngineException("流程实例已挂起，无法推进: " + processInstanceId);
        }
        if (instance.getStatus().isTerminal()) {
            log.debug("流程实例已是终态 {}，忽略推进: {}", instance.getStatus(), processInstanceId);
            return instance;
        }

        WfDefinition definition = definitionOf(instance);
        List<WfExecution> executions = persistence.findExecutionsByProcessInstance(processInstanceId);

        WfContext context = newContext(definition, instance, null);
        context.setSubProcessLauncher(this);
        context.setVariables(variables);
        // 汇合判定需要看到本实例的全部 token（含上次请求建的）
        context.setProcessExecutions(executions);

        for (WfExecution execution : executions) {
            if (execution.isActive()) {
                context.setCurrentExecution(execution);
                engine.advance(context);
            }
        }

        persistAll(context);
        resolveCompletion(context);
        return instance;
    }

    /**
     * 完成一个任务并推进流程 —— 审批主路径。
     *
     * @param taskId   任务 ID
     * @param userId   办结人
     * @param comment  审批意见
     * @param variables 本次带入的变量（并入流程级变量）
     * @return 流程实例
     */
    public WfProcessInstance completeTask(String taskId, String userId, String comment,
                                          Map<String, Object> variables) {
        WfTask task = requireTask(taskId);
        if (!task.isOpen()) {
            throw new WfEngineException("任务已完成或已作废，无法重复完成: " + taskId);
        }
        if (task.isSuspended()) {
            // 单独一条而不是并进上面那句：办结是流程语义（做完就没了），
            // 挂起是运营语义（随时可恢复），调用方要做的后续动作完全不同
            throw new WfEngineException("任务已挂起，无法办结: " + taskId
                    + "。如需继续请先调用 WfTaskService#activateTask");
        }

        // 办结人必须是当前处理人（委派态看 owner）
        String handler = task.effectiveHandler();
        if (handler != null && userId != null && !handler.equals(userId)) {
            throw new WfEngineException("任务 " + taskId + " 当前处理人为 " + handler
                    + "，无权以 " + userId + " 的身份办结");
        }

        // 前置钩子（可否决）
        if (!hookDispatcher.fireBeforeComplete(taskId, userId, variables)) {
            throw new WfEngineException("任务完成被钩子否决: " + taskId);
        }

        WfProcessInstance instance = requireInstance(task.getProcessInstanceId());
        if (instance.getStatus().isTerminal()) {
            throw new WfEngineException("流程实例已结束，无法完成任务: " + task.getProcessInstanceId());
        }

        WfDefinition definition = definitionOf(instance);

        // ---- 1. 完成任务 ----
        task.setStatus(WfTask.Status.COMPLETED);
        task.setCompleterId(userId);
        task.setComment(comment);
        task.setEndTime(new Date());
        if (variables != null) {
            task.getVariables().putAll(variables);
        }
        task.nextRevision();
        persistence.saveTask(task);

        // ---- 2. 找 token 并推进 ----
        WfExecution execution = task.getExecutionId() != null
                ? persistence.findExecution(task.getExecutionId()) : null;
        if (execution == null) {
            List<WfExecution> all = persistence.findExecutionsByProcessInstance(instance.getId());
            for (WfExecution candidate : all) {
                if (task.getDefinitionId() != null
                        && task.getDefinitionId().equals(candidate.getActivityId())) {
                    execution = candidate;
                    break;
                }
            }
        }

        WfContext context = newContext(definition, instance, execution);
        context.setAuthenticatedUserId(userId);
        context.setSubProcessLauncher(this);
        context.setVariables(variables);
        // 审批意见交给 leave() 写成唯一那条活动记录（见 WfContext#pendingActivityOutcome）
        context.setPendingActivityOutcome(comment);
        // 汇合判定需要看到本实例的全部 token
        context.setProcessExecutions(persistence.findExecutionsByProcessInstance(instance.getId()));

        // ---- 多实例会签：办结其中一个实例，不等于这个节点办完了 ----
        // 会签节点上每个实例一个 token、一条任务。办结其中一条时若直接往下走，
        // 流程会在"还差 2 个人没批"的时候就跑到了下一节点 —— 那不是会签，那是抢占。
        if (execution != null && isMultiInstance(definition, task.getDefinitionId())) {
            if (!closeMultiInstanceIfDone(context, instance, definition,
                    task.getDefinitionId(), task.getExecutionId())) {
                // 会签未收口：把本 token 停在本节点，流程不往下走
                execution.setState(WfExecution.State.WAITING);
                persistAll(context);
                resolveCompletion(context);
                recordActivityComplete(instance, definition, task, execution, userId, comment);
                return instance;
            }
        }

        if (execution != null) {
            engine.advance(context);
            persistAll(context);
            resolveCompletion(context);
        } else {
            // 找不到 token：说明 token 状态与任务状态已经不一致（多为并发或数据损坏）。
            // 记 ERROR 并仍然保存实例，避免"任务已完成但变量没带上"的静默不一致。
            log.error("任务 {} 找不到对应 token（executionId={}），流程变量未带入",
                    taskId, task.getExecutionId());
            instance.nextRevision();
            persistence.saveProcessInstance(instance);
        }

        // ---- 3. 活动历史 ----
        // 正常路径由 leave() 统一写；这里仅在"没有 token、无法 advance"时兜底 ——
        // 那种情况下 leave 不会被调用，不写就丢了这笔历史。
        if (execution == null) {
            recordActivityComplete(instance, definition, task, null, userId, comment);
        }

        // ---- 4. 钩子 ----
        hookDispatcher.fireAfterComplete(taskId, userId, "completed");
        hookDispatcher.notifyApprovalResult(instance.getId(), instance.getDefinitionKey(),
                instance.getStartUserId(), "completed", comment);
        if (instance.getStatus() == WfProcessStatus.COMPLETED) {
            hookDispatcher.fireComplete(instance.getDefinitionKey(), instance.getId(),
                    instance.getResult());
        }

        return instance;
    }

    // ==================== 挂起 / 终止 ====================

    /**
     * 挂起流程实例。
     */
    public void suspend(String processInstanceId, String reason) {
        WfProcessInstance instance = requireInstance(processInstanceId);
        if (instance.getStatus().isTerminal()) {
            throw new WfEngineException("流程实例已结束，无法挂起: " + processInstanceId);
        }
        instance.setStatus(WfProcessStatus.SUSPENDED);
        instance.setSuspendReason(reason);
        instance.nextRevision();
        persistence.saveProcessInstance(instance);
        log.info("流程挂起: {}, 原因={}", processInstanceId, reason);
    }

    /**
     * 激活流程实例。
     */
    public void activate(String processInstanceId) {
        WfProcessInstance instance = requireInstance(processInstanceId);
        if (instance.getStatus() != WfProcessStatus.SUSPENDED) {
            log.debug("流程实例状态为 {}，无需激活: {}", instance.getStatus(), processInstanceId);
            return;
        }
        instance.setStatus(WfProcessStatus.ACTIVE);
        instance.setSuspendReason(null);
        instance.nextRevision();
        persistence.saveProcessInstance(instance);
        log.info("流程激活: {}", processInstanceId);
    }

    /**
     * 终止流程实例（外部强制终止），并作废其全部未完成任务。
     */
    public void terminate(String processInstanceId, String reason) {
        WfProcessInstance instance = requireInstance(processInstanceId);
        if (instance.getStatus().isTerminal()) {
            log.debug("流程实例已是终态 {}，忽略终止: {}", instance.getStatus(), processInstanceId);
            return;
        }
        instance.setStatus(WfProcessStatus.EXTERNALLY_TERMINATED);
        instance.setEndTime(new Date());
        instance.setDeleteReason(reason);
        instance.nextRevision();
        persistence.saveProcessInstance(instance);

        // 作废未完成任务，否则待办列表会永远挂着永远办不完的活
        int cancelled = 0;
        for (WfTask task : persistence.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(processInstanceId).setOpenOnly(true))) {
            task.setStatus(WfTask.Status.CANCELLED);
            task.setEndTime(new Date());
            task.nextRevision();
            persistence.saveTask(task);
            hookDispatcher.fireDeleted(task.getId(), task.getAssignee(),
                    processInstanceId, "terminated");
            cancelled++;
        }

        // 结束全部 token
        for (WfExecution execution : persistence.findExecutionsByProcessInstance(processInstanceId)) {
            execution.setState(WfExecution.State.ENDED);
            persistence.saveExecution(execution);
        }

        // 撤掉它名下的所有 job：实例已经没了，到点的 job 会去找一个
        // 不存在的流程，把"流程早就被强制终止了"变成一条莫名其妙的执行失败
        persistence.deleteJobsByProcessInstance(processInstanceId);

        log.info("流程终止: {}, 原因={}, 作废任务 {} 个", processInstanceId, reason, cancelled);

        // 终止同样要走收尾钩子。旧实现只改状态、只打日志，一个钩子都不发 ——
        // 于是"申请人撤回""管理员终止"之后，审批人永远收不到"这单已经作废"，
        // 待办虽然消失了，但对方并不知道发生了什么，只当是自己被收回了权限。
        // 对审批系统来说，终止与正常结束一样需要通知到人。
        hookDispatcher.fireComplete(instance.getDefinitionKey(), processInstanceId, "terminated");
        hookDispatcher.notifyApprovalResult(processInstanceId, instance.getDefinitionKey(),
                instance.getStartUserId(), "terminated", reason);
        // 这里原来还有一个循环，对 CANCELLED 的任务发 fireAfterComplete。
        // 去掉有两个原因：
        // 1) 它查的正是上面作废循环刚标记的那批任务 ⇒ 同一个终止动作，
        //    每个被作废的任务会收到两次通知。按通知发短信/IM 的接入方会发两条。
        // 2) 它发的是 fireAfterComplete（"办结"），而任务是被作废的 ——
        //    审批方据此发出的"某人办结了这单"是条假消息。
        // 逐个任务的消失通知由上面的作废循环负责，流程级的由 fireComplete/notify 负责。
    }

    // ==================== 查询 ====================

    public WfProcessInstance getProcessInstance(String id) {
        return persistence.findProcessInstance(id);
    }

    public List<WfProcessInstance> queryProcessInstances(WfProcessInstanceQuery query) {
        return persistence.queryProcessInstances(query);
    }

    /**
     * 统计符合条件的流程实例总条数（分页组件要它算总页数）。
     *
     * <p>别用 {@code queryProcessInstances(...).size()} 代替：那拿到的是<b>当前页</b>条数。
     */
    public long countProcessInstances(WfProcessInstanceQuery query) {
        return persistence.countProcessInstances(query);
    }

    public List<WfProcessInstance> getProcessInstancesByBusinessKey(String businessKey) {
        return persistence.findProcessInstancesByBusinessKey(businessKey);
    }

    /**
     * 审批轨迹。
     */
    public List<WfActivityInstance> getTrail(String processInstanceId) {
        return persistence.findActivityInstances(processInstanceId);
    }

    public List<WfExecution> getExecutions(String processInstanceId) {
        return persistence.findExecutionsByProcessInstance(processInstanceId);
    }

    /**
     * 加签评论。
     */
    public WfComment addComment(String processInstanceId, String taskId, String userId,
                                String type, String content) {
        WfComment comment = new WfComment(idGenerator.nextCommentId(), processInstanceId, userId,
                type, content);
        comment.setTaskId(taskId);
        persistence.saveComment(comment);
        return comment;
    }

    public List<WfComment> getComments(String processInstanceId) {
        return persistence.findComments(processInstanceId);
    }

    // ==================== 消息与信号 ====================

    /**
     * 触发一个等待中的<b>接收任务</b>（点对点）。
     *
     * <p>这是 {@code receiveTask} 唯一的正常唤醒路径。任务停在
     * {@link WfTask.Status#CREATED}、token 停在该节点，直到这里按
     * {@link com.zifang.z.wf.core.definition.WfNode#getMessageName()} 匹配上。
     *
     * <p><b>匹配到多于一个时直接报错，不挑一个执行。</b> 消息的语义是"点对点"，
     * 多个任务同时等同一个消息名说明流程定义或消息名分配有歧义；
     * 此时按 id 排序取第一个看起来很合理，实际后果是"另一条流程永远等不到"，
     * 而且没有任何报错。宁可让调用方把消息名改具体，或改用
     * {@link #broadcastSignal} 明确表达"就是要广播"。
     *
     * @param messageName 消息名，对应 receiveTask 的 {@code messageName}
     * @param processInstanceId 限定在某个流程实例内；{@code null} 表示全局查找
     * @return 被触发的流程实例
     * @throws WfEngineException 没有匹配任务，或匹配到多个
     */
    public WfProcessInstance triggerMessage(String messageName, String processInstanceId,
                                            String userId, Map<String, Object> variables,
                                            String comment) {
        // 事件网关分支优先于消息边界：两者都是"等一条消息"，但网关分支的等待
        // 是<b>竞速</b>（其余分支同时作废），边界的等待是<b>打断</b>。
        // 谁先判谁就决定了同名事件落到哪种语义上，所以放在最前面并说清楚顺序。
        WfProcessInstance raced = fireEventGatewayBranches(WfJobType.EVENT_MESSAGE, messageName,
                processInstanceId, userId, comment);
        if (raced != null) {
            return raced;
        }
        // 消息边界优先：它会打断在办的流程，而 receiveTask 是"等这条消息来"。
        // 两者同名时先看有没有订阅 —— 有订阅说明作者写的是打断语义。
        WfProcessInstance interrupted = fireSubscriptions(WfJobType.MESSAGE, messageName,
                processInstanceId, userId, comment);
        if (interrupted != null) {
            return interrupted;
        }
        List<WfTask> matched = findWaitingReceiveTasks(messageName, processInstanceId);
        if (matched.isEmpty()) {
            throw new WfEngineException("没有等待消息 [" + messageName + "] 的接收任务"
                    + (processInstanceId == null ? "" : "（流程实例 " + processInstanceId + "）"));
        }
        if (matched.size() > 1) {
            List<String> ids = new ArrayList<>();
            for (WfTask task : matched) {
                ids.add(task.getId() + "@" + task.getProcessInstanceId());
            }
            throw new WfEngineException("消息 [" + messageName + "] 匹配到 " + matched.size()
                    + " 个接收任务，点对点消息无法决定触发哪一个: " + ids
                    + "。请把消息名改得更具体，或改用 broadcastSignal 表达广播语义");
        }
        return completeTask(matched.get(0).getId(), userId, comment, variables);
    }

    /**
     * 广播一个信号，唤醒<b>全部</b>等待该信号名的接收任务。
     *
     * <p>与 {@link #triggerMessage} 的区别就是信号与消息的区别：
     * 消息点对点、只唤醒一个；信号广播、唤醒所有订阅者。
     * 提供两个方法而不是给 {@code triggerMessage} 加一个 boolean 开关，
     * 是为了让"我以为我唤醒了一个"这种误用在编译期就暴露。
     *
     * @return 被触发的流程实例（按任务创建顺序）
     * @throws WfEngineException 一个都没匹配到 —— 静默返回空列表会让"消息名写错了"
     *         这类问题一直潜伏到某天没人再等这条消息为止
     */
    public List<WfProcessInstance> broadcastSignal(String signalName, String userId,
                                                   Map<String, Object> variables, String comment) {
        // 信号可以命中多个实例上的多个分支，每一个都要各自走一次竞速
        List<WfProcessInstance> advanced = new ArrayList<>();
        WfProcessInstance raced = fireEventGatewayBranches(WfJobType.EVENT_SIGNAL,
                signalName, null, userId, comment);
        if (raced != null) {
            advanced.add(raced);
        }
        // 信号边界订阅也算订阅者：广播的语义本来就是"叫醒所有在听的"
        List<WfProcessInstance> interrupted = fireAllSubscriptions(WfJobType.SIGNAL,
                signalName, userId, comment);
        List<WfTask> matched = findWaitingReceiveTasks(signalName, null);
        if (matched.isEmpty() && interrupted.isEmpty() && advanced.isEmpty()) {
            throw new WfEngineException("没有等待信号 [" + signalName + "] 的接收任务、信号订阅"
                    + "或事件网关分支");
        }
        // 三路收集的实例都要进返回值。漏掉任何一路的后果是"事情做了但调用方看不见"：
        // 边界订阅那一路曾经只推进不返回，于是广播一次信号后调用方拿到空列表，
        // 以为没人订阅，而流程其实已经被打断了。
        for (WfProcessInstance each : interrupted) {
            advanced.add(each);
        }
        for (WfTask task : matched) {
            advanced.add(completeTask(task.getId(), userId, comment, variables));
        }
        log.info("广播信号 [{}] 唤醒 {} 个事件网关分支，触发 {} 个信号订阅，唤醒 {} 个接收任务",
                signalName, raced == null ? 0 : 1, interrupted.size(), matched.size());
        return advanced;
    }

    /**
     * 消费一条消息/信号边界订阅。
     *
     * <p>与 {@code receiveTask} 的区别是<b>打断</b>：宿主节点上的待办作废、
     * token 被拉到边界事件上走补偿分支。典型用途是"撤销申请""加急插队"。
     *
     * @return 被触发的流程实例；没有匹配订阅时返回 {@code null}
     */
    private WfProcessInstance fireSubscriptions(WfJobType type, String eventName,
                                                String processInstanceId, String userId,
                                                String comment) {
        List<WfProcessInstance> fired = fireAllSubscriptions(type, eventName, userId, comment,
                processInstanceId);
        if (fired.isEmpty()) {
            return null;
        }
        if (fired.size() > 1) {
            // 点对点消息必须能决定触发哪一个。匹配到多个说明作者该用信号（广播），
            // 与 receiveTask 那边同一个理由：宁可报错也别随机挑一个
            List<String> ids = new ArrayList<>();
            for (WfProcessInstance instance : fired) {
                ids.add(instance.getId());
            }
            throw new WfEngineException(type.getLabel() + " [" + eventName + "] 匹配到 "
                    + fired.size() + " 个订阅实例，点对点触发无法决定是哪一个: " + ids
                    + "。请把名字改得更具体，或改用 broadcastSignal 表达广播语义");
        }
        return fired.get(0);
    }

    private List<WfProcessInstance> fireAllSubscriptions(WfJobType type, String eventName,
                                                         String userId, String comment) {
        return fireAllSubscriptions(type, eventName, userId, comment, null);
    }

    /**
     * 消费全部匹配订阅。
     *
     * <p>订阅的名字存在 job 的 exceptionMessage 里（见 WfContext#startTimerJobs），
     * SQL 查不出来，只能按类型捞回来在内存里比对。订阅的量级是"在办的单数"，
     * 审批系统里通常只有同时在办的那几十上百条，够用。
     */
    private List<WfProcessInstance> fireAllSubscriptions(WfJobType type, String eventName,
                                                         String userId, String comment,
                                                         String processInstanceId) {
        List<WfProcessInstance> advanced = new ArrayList<>();
        if (eventName == null || eventName.trim().isEmpty()) {
            throw new WfEngineException(type.getLabel() + "名不能为空");
        }
        String wanted = eventName.trim();
        WfJobQuery query = new WfJobQuery().setType(type);
        if (processInstanceId != null) {
            query.setProcessInstanceId(processInstanceId);
        }
        for (WfJob job : persistence.queryJobs(query.setPageNum(1).setPageSize(500))) {
            if (!wanted.equals(job.getSubscriptionName())) {
                continue;
            }
            // 先删再触发：并发触发器抢同一条订阅时，
            // 后到的那个会看到任务不在宿主节点上而安全跳过，而不是把补偿分支走两遍
            persistence.deleteJob(job.getId());
            WfProcessInstance instance = fireEventBoundary(job, type.outcomePrefix()
                    + type.getLabel() + " [" + wanted + "]"
                    + (comment == null ? "" : "：" + comment));
            if (instance != null) {
                advanced.add(instance);
            }
        }
        if (!advanced.isEmpty()) {
            log.info("{} [{}] 触发 {} 个边界订阅", type.getLabel(), wanted, advanced.size());
        }
        return advanced;
    }

    /**
     * 找出等待指定消息/信号的接收任务。
     *
     * <p>必须按 {@code type=receiveTask} 过滤，不能只按 category 匹配：
     * {@code WfReceiveTaskBehavior} 是把 messageName 写进 {@code category} 的，
     * 而 {@code category} 本身是通用字段，人工任务、审批分组都会用它。
     * 只按 category 查会把一批普通人工任务一起"唤醒"，而它们其实在等人。
     */
    private List<WfTask> findWaitingReceiveTasks(String messageName, String processInstanceId) {
        if (messageName == null || messageName.trim().isEmpty()) {
            throw new WfEngineException("消息名不能为空");
        }
        WfTaskQuery query = new WfTaskQuery()
                .setCategory(messageName)
                .setOpenOnly(true)
                .setPageNum(1)
                .setPageSize(Integer.MAX_VALUE);
        if (processInstanceId != null) {
            query.setProcessInstanceId(processInstanceId);
        }
        List<WfTask> found = persistence.queryTasks(query);
        if (found == null || found.isEmpty()) {
            return new ArrayList<>();
        }
        List<WfTask> matched = new ArrayList<>();
        for (WfTask task : found) {
            if (task != null
                    && com.zifang.z.wf.core.definition.WfNodeType.RECEIVE_TASK
                            .bpmnName().equals(task.getType())) {
                matched.add(task);
            }
        }
        return matched;
    }

    /**
     * 记一条"该活动完成"的历史。
     *
     * <p><b>只在两条路径上调用</b>：会签未收口（那一步不走 leave），
     * 以及没有 token 可推进的兜底。正常路径由 {@code WfEngine#leave} 统一写，
     * 两边都写会让同一步骤在轨迹上出现两条。
     */
    private void recordActivityComplete(WfProcessInstance instance, WfDefinition definition,
                                        WfTask task, WfExecution execution, String userId,
                                        String comment) {
        if (definition.node(task.getDefinitionId()) == null) {
            return;
        }
        WfActivityInstance history = new WfActivityInstance();
        history.setId(idGenerator.nextActivityId());
        history.setProcessInstanceId(instance.getId());
        history.setProcessDefinitionKey(instance.getDefinitionKey());
        history.setActivityId(task.getDefinitionId());
        history.setActivityName(task.getName());
        history.setActivityType(task.getType());
        history.setExecutionId(execution.getId());
        history.setAssignee(userId);
        history.setStartTime(task.getCreateTime());
        history.setEndTime(new Date());
        history.setDurationMillis(task.getCreateTime() == null ? 0L
                : System.currentTimeMillis() - task.getCreateTime().getTime());
        history.setOutcome(comment != null && !comment.isEmpty() ? comment : "completed");
        persistence.saveActivityInstance(history);
    }

    // ==================== 多实例（会签 / 或签） ====================

    private boolean isMultiInstance(WfDefinition definition, String nodeId) {
        WfNode node = nodeId == null ? null : definition.node(nodeId);
        return node != null && node.isMultiInstance();
    }

    /**
     * 会签是否收口；收口时作废剩余实例、结束兄弟 token。
     *
     * <p>收口后<b>只让当前这条 token 往下走</b>，其余 token 直接结束 ——
     * 它们停在同一个节点上，而流程只能有一个 token 离开该节点，
     * 否则每收口一次就多出一条并行的后续路径。
     *
     * <p>剩余实例的任务被置为 {@code CANCELLED} 而不是删掉：
     * 已下发出去的待办要从办理人的列表里消失，但"曾经有过这个会签实例"
     * 是审计要问的事，删了就答不上来了。
     *
     * @return true = 已收口，流程可以继续往下；false = 还要继续等
     */
    private boolean closeMultiInstanceIfDone(WfContext context, WfProcessInstance instance,
                                             WfDefinition definition, String nodeId,
                                             String currentExecutionId) {
        WfNode node = definition.node(nodeId);
        List<WfTask> nodeTasks = persistence.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(instance.getId())
                .setDefinitionId(nodeId)
                .setPageNum(1).setPageSize(Integer.MAX_VALUE));
        WfMultiInstance.Stats stats = WfMultiInstance.stats(nodeTasks);

        int loopCounter = 0;
        WfExecution current = currentExecutionId == null
                ? null : persistence.findExecution(currentExecutionId);
        if (current != null && current.getVariables().get(WfMultiInstance.LOOP_COUNTER) != null) {
            loopCounter = ((Number) current.getVariables()
                    .get(WfMultiInstance.LOOP_COUNTER)).intValue();
        }
        Map<String, Object> loopVars = WfMultiInstance.loopVariables(
                instance.getVariables(), stats, loopCounter);
        boolean done = WfMultiInstance.isComplete(node.getCompletionCondition(),
                engine.getExpressionEvaluator(), stats, loopVars);

        WfMultiInstance.publishStats(context, stats);
        log.info("会签进度 {} -> {}", WfMultiInstance.describe(node, stats), done ? "收口" : "继续等待");

        if (!done) {
            return false;
        }

        // ---- 收口：作废剩余实例的待办 ----
        for (WfTask pending : WfMultiInstance.cancellable(nodeTasks)) {
            pending.setStatus(WfTask.Status.CANCELLED);
            pending.setEndTime(new Date());
            pending.nextRevision();
            persistence.saveTask(pending);
            hookDispatcher.fireDeleted(pending.getId(), pending.getAssignee(),
                    instance.getId(), "multi-instance-closed");
        }

        // ---- 收口：结束仍停在本节点的其他 token ----
        for (WfExecution token : persistence.findExecutionsByProcessInstance(instance.getId())) {
            if (token.isEnded()
                    || !nodeId.equals(token.getActivityId())
                    || (currentExecutionId != null && currentExecutionId.equals(token.getId()))) {
                continue;
            }
            token.setState(WfExecution.State.ENDED);
            persistence.saveExecution(token);
        }
        return true;
    }

    // ==================== 错误路由（BPMN Error） ====================

    /**
     * 在指定任务所在的活动上抛出一个 BPMN 业务错误，路由到匹配的边界事件。
     *
     * <p>这是"某一步失败了不要让整条流程死掉"的入口：边界事件带一个
     * {@code errorCode}，只捕获同码的错误。没有匹配时流程以内部终止收场
     * 并记下错误码 —— 不静默继续，因为静默继续等于"这一步没做但看起来做了"。
     *
     * <p>触发边界事件时：宿主节点上<b>还没办结的待办被作废</b>
     * （不然后续流程走完了，列表里还挂着一个永远办不完的活）。
     *
     * @param taskId 出错的任务；用任务反查宿主节点与 token
     * @param errorCode 错误码，与 boundaryEvent 的 errorRef 匹配（不区分大小写）
     * @return 被触发后流程实例的最新状态
     */
    public WfProcessInstance handleBpmnError(String taskId, String errorCode,
                                             String message, Map<String, Object> variables) {
        WfTask task = requireTask(taskId);
        if (!task.isOpen()) {
            // 任务已办结/作废时它的 token 早已不在该节点上。
            // 此时再路由错误，会去找一个不属于该活动的 token，
            // 把流程从别处拽到边界事件上 —— 比不路由更糟。
            throw new WfEngineException("任务已办结或已作废（" + task.getStatus()
                    + "），不能再路由错误: " + taskId);
        }
        WfProcessInstance instance = requireInstance(task.getProcessInstanceId());
        if (instance.getStatus().isTerminal()) {
            throw new WfEngineException("流程实例已结束，无法再路由错误: " + instance.getId());
        }
        if (errorCode == null || errorCode.trim().isEmpty()) {
            throw new WfEngineException("errorCode 不能为空："
                    + "没有码就没有边界事件能捕获它，流程只能直接失败");
        }
        WfDefinition definition = definitionOf(instance);
        String nodeId = task.getDefinitionId();
        WfNode boundary = findErrorBoundary(definition, nodeId, errorCode);

        // ---- 记录错误：让"为什么失败"在轨迹与日志里都能查到 ----
        persistence.saveComment(new WfComment(idGenerator.nextCommentId(),
                instance.getId(), errorOperator(task), "error",
                "节点 " + nodeId + " 抛出 BPMN 错误 [" + errorCode + "]: " + message));
        log.warn("流程 {} 节点 {} 抛出 BPMN 错误 [{}]: {}",
                instance.getId(), nodeId, errorCode, message);

        if (boundary == null) {
            instance.setStatus(WfProcessStatus.INTERNALLY_TERMINATED);
            instance.setEndTime(new Date());
            instance.setDeleteReason("BPMN 错误 [" + errorCode + "]: " + message
                    + "（节点 " + nodeId + " 上没有匹配该错误码的边界事件）");
            instance.nextRevision();
            persistence.saveProcessInstance(instance);
            // 待办全部作废，否则它们会永远挂在办理人列表里
            cancelOpenTasks(instance.getId());
            return instance;
        }

        // ---- 找 token：它必须停在宿主节点上，否则路由没有"当前执行"可言 ----
        WfExecution execution = task.getExecutionId() != null
                ? persistence.findExecution(task.getExecutionId()) : null;
        if (execution == null) {
            for (WfExecution candidate
                    : persistence.findExecutionsByProcessInstance(instance.getId())) {
                if (nodeId.equals(candidate.getActivityId())) {
                    execution = candidate;
                    break;
                }
            }
        }

        cancelOpenTasksOn(instance.getId(), nodeId);
        if (execution == null) {
            instance.setStatus(WfProcessStatus.INTERNALLY_TERMINATED);
            instance.setEndTime(new Date());
            instance.setDeleteReason("BPMN 错误 [" + errorCode + "] 无可用 token，无法路由到边界事件 "
                    + boundary.getId());
            instance.nextRevision();
            persistence.saveProcessInstance(instance);
            return instance;
        }

        // ---- 把 token 移到边界事件上，由它沿出线走补偿分支 ----
        WfContext context = contextForError(instance, definition, execution, variables);
        execution.setActivityId(boundary.getId());
        execution.setState(WfExecution.State.ACTIVE);
        execution.setEnteredTime(new Date());
        context.recordActivity(boundary.getId(), boundary.getName(),
                boundary.getType().bpmnName(), "error:" + errorCode);
        engine.startFrom(context, execution);
        persistAll(context);
        resolveCompletion(context);
        log.info("流程 {} 的错误 [{}] 已路由到边界事件 {}",
                instance.getId(), errorCode, boundary.getId());
        return instance;
    }

    /**
     * 触发定时器边界事件 —— 由 {@link WfJobService} 在 job 到期时调用。
     *
     * <p>形状与 {@link #handleBpmnError} 一致：把 token 从宿主节点挪到边界事件上，
     * 由它沿出线走补偿/升级分支。差别只在"为什么挪"：
     * 错误边界是人或委托抛错触发的，定时器边界是时间到了触发的。
     *
     * @param job 已到期的 job（调用方保证它已被删除，不会重复触发）
     * @return 是否真的触发了边界事件
     */
    public boolean fireTimerBoundary(WfJob job) {
        long waitedMillis = System.currentTimeMillis()
                - (job.getCreateTime() == null ? System.currentTimeMillis()
                : job.getCreateTime().getTime());
        return fireEventBoundary(job, "timer:节点 " + job.getAttachedToRef() + " 停留超时（已等 "
                + WfTimerSupport.describeDuration(waitedMillis) + "）") != null;
    }

    /**
     * 触发任意边界事件（定时器 / 消息 / 信号）。
     *
     * <p>三种边界的<b>打断语义完全一样</b>：宿主上的待办作废、token 拉到边界事件上
     * 走补偿分支。差别只在"什么时刻触发"，所以合并成一个方法，
     * 免得三份实现各修一次、只修到两处。
     *
     * @param reason 写进评论与轨迹的原因，要能让人事后看懂这次为什么被打断
     * @return 是否真的触发了
     */
    public WfProcessInstance fireEventBoundary(WfJob job, String reason) {
        WfProcessInstance instance = requireInstance(job.getProcessInstanceId());
        if (instance.getStatus().isTerminal()) {
            // 流程已经结束了还在响：多半是残留 job（实例被外部直接改库终止、
            // 或清理逻辑漏了）。返回 false 而不是抛异常 ——
            // 抛出去会被执行器算成失败并重试，而重试永远不会有用。
            // 传 null 定义：流程已终态，定义可能已被删除（definitionOf 会抛），
            // 而这个分支的全部意义就是"别抛"。钩子拿不到 definitionKey 是这次早退的必然。
            fireJobExecuted(null, instance.getId(), job, false);
            log.warn("流程 {} 已是终态 {}，却还有 job {} 到期，忽略触发",
                    instance.getId(), instance.getStatus(), job.getId());
            return null;
        }
        WfDefinition definition = definitionOf(instance);
        WfNode boundary = WfJobService.resolveBoundary(definition, job);

        // 找 token：它必须仍停在宿主节点上。
        // token 早就走了还触发，等于把流程从别处拽到边界事件上 ——
        // 比如人已经办完、token 已进入下一节点，只是 job 撤得慢了一步。
        WfExecution execution = job.getExecutionId() != null
                ? persistence.findExecution(job.getExecutionId()) : null;
        if (execution == null || execution.isEnded()
                || !job.getAttachedToRef().equals(execution.getActivityId())) {
            fireJobExecuted(definition, instance.getId(), job, false);
            log.info("job {} 触发时 token 已不在宿主节点 {} 上（当前 {}），"
                            + "按已办结处理，不触发边界事件",
                    job.getId(), job.getAttachedToRef(),
                    execution == null ? "不存在" : execution.getActivityId());
            return null;
        }

        persistence.saveComment(new WfComment(idGenerator.nextCommentId(),
                instance.getId(), "system", "job",
                reason + "，触发边界事件 " + boundary.getId()));
        log.info("流程 {} 节点 {} 被 {} 打断，触发边界事件 {}",
                instance.getId(), job.getAttachedToRef(), reason, boundary.getId());

        // 宿主节点上的待办作废：人已经被打断了，待办还挂着只会让人以为还能办
        cancelOpenTasksOn(instance.getId(), job.getAttachedToRef());

        WfContext context = contextForError(instance, definition, execution, null);
        execution.setActivityId(boundary.getId());
        execution.setState(WfExecution.State.ACTIVE);
        execution.setEnteredTime(new Date());
        // 结论交给 leave() 写成唯一那条记录（见 WfContext#pendingActivityOutcome）。
        // 不在这里单独 recordActivity：那会让边界事件在轨迹上出现两行 ——
        // 一行写打断原因，一行写 completed —— 而"一次节点访问一条"是本引擎的硬约定。
        context.setPendingActivityOutcome(reason);
        engine.startFrom(context, execution);
        persistAll(context);
        resolveCompletion(context);
        // 通知放在落库之后：钩子实现方常拿 processInstanceId 去查实例与轨迹，
        // 放在 persistAll 之前它读到的是推进前的旧状态（token 还在宿主节点上、
        // 历史还没记），于是"这个 job 触发后流程走到哪了"这类统计永远差一步
        fireJobExecuted(definition, instance.getId(), job, true);
        return instance;
    }

    /**
     * 记错误时用谁的名义。
     *
     * <p>优先取 owner（委派态下责任在人身上），其次 assignee。
     * 两者都没有时记 "system" —— 不能写 null，否则"是谁触发的错误"永远查不到。
     */
    private String errorOperator(WfTask task) {
        if (task.getOwner() != null && !task.getOwner().trim().isEmpty()) {
            return task.getOwner();
        }
        if (task.getAssignee() != null && !task.getAssignee().trim().isEmpty()) {
            return task.getAssignee();
        }
        return "system";
    }

    /** 找挂在 host 节点上、且捕获该错误码的边界事件。 */
    private WfNode findErrorBoundary(WfDefinition definition, String hostNodeId,
                                     String errorCode) {
        if (hostNodeId == null || definition.getNodes() == null) {
            return null;
        }
        for (WfNode node : definition.getNodes()) {
            if (node.getType() == WfNodeType.BOUNDARY_EVENT
                    && hostNodeId.equals(node.getAttachedToRef())
                    && node.catchesError(errorCode)) {
                return node;
            }
        }
        return null;
    }

    // ==================== 事件网关 ====================

    /**
     * 事件到达，唤醒它对应的那一条分支，并作废其余分支。
     *
     * <p>这就是"事件定向唤醒 token"的全部机制，共三步：
     * <ol>
     *   <li><b>认领</b>：确认这条 job 的 token 仍停在这个捕获事件上。
     *       token 已经走了还唤醒，等于把流程从别处拽回来 —— 与
     *       {@link #fireEventBoundary} 里那道闸门同构。</li>
     *   <li><b>作废兄弟</b>：把同一网关下其余分支的 token 结束掉、
     *       订阅 job 删掉、轨迹上留一条"未选中"。不这么做的话，
     *       流程会同时跑两条分支然后在汇合点碰头 —— 那不是竞速，是"全都走"。</li>
     *   <li><b>前进</b>：被唤醒的 token 沿自己的出线离开。</li>
     * </ol>
     *
     * <p>兄弟的判定来自<b>流程定义</b>而不是 job 上的某个字段：捕获事件的入线
     * 在部署期就被限定为"只有一条，且来自事件网关"，所以顺着入线就能找到网关，
     * 再顺着网关的出线就能列出全部兄弟。多一份存储就多一处可能与定义不一致的副本。
     *
     * @return 是否真的唤醒了（token 已挪走这类"该响没响"要能被调用方区分开）
     */
    public boolean fireEventGatewayBranch(WfJob job, String userId, String reason) {
        WfProcessInstance instance = requireInstance(job.getProcessInstanceId());
        if (instance.getStatus().isTerminal()) {
            fireJobExecuted(null, instance.getId(), job, false);
            log.warn("流程 {} 已是终态 {}，却还有事件网关订阅 {} 到期，忽略触发",
                    instance.getId(), instance.getStatus(), job.getId());
            return false;
        }
        WfDefinition definition = definitionOf(instance);
        WfExecution token = job.getExecutionId() == null
                ? null : persistence.findExecution(job.getExecutionId());
        if (token == null || token.isEnded()
                || !job.getElementId().equals(token.getActivityId())) {
            // 这道闸门是**第二道**：真正挡住"同一条分支被走两遍"的是下面那句
            // deleteJob（先删再推进），并发投递时后到的那次根本查不到 job。
            // 保留它是为了覆盖"token 挪走了而订阅没被清掉"这种脏数据 ——
            // 此时若继续推进，等于把流程从别处拽回来，而它正在等的那个事件其实已经无关。
            // 返回 false 而不是抛异常，让调用方把它算成"没触发"而不是失败重试。
            fireJobExecuted(definition, instance.getId(), job, false);
            log.info("事件网关订阅 {} 触发时 token 已不在 {} 上（当前 {}），按已唤醒处理",
                    job.getId(), job.getElementId(),
                    token == null ? "不存在" : token.getActivityId());
            return false;
        }

        WfNode catchEvent = definition.node(job.getElementId());
        WfNode gateway = gatewayOf(definition, catchEvent);

        // 先删再推进：与 WfJobService#fire、completeExternalTask 同一顺序。
        // 并发投递时后到的那次会落到上面那道"token 已挪走"的闸门，
        // 而不是把同一条分支走两遍。
        persistence.deleteJob(job.getId());

        WfContext context = newContext(definition, instance, token);
        // 触发者要一路带进轨迹与评论：事件网关的竞速结果事后追责时，
        // "这条分支是谁的消息选中的"是第一个要回答的问题
        context.setAuthenticatedUserId(userId == null || userId.trim().isEmpty()
                ? "system" : userId);
        context.setProcessExecutions(
                persistence.findExecutionsByProcessInstance(instance.getId()));

        int cancelled = gateway == null ? 0 : cancelSiblingBranches(context, definition, gateway,
                catchEvent.getId());
        persistence.saveComment(new WfComment(idGenerator.nextCommentId(),
                instance.getId(), context.getAuthenticatedUserId(), "job",
                reason + "，走事件网关分支 " + catchEvent.getId()
                        + (cancelled > 0 ? "，作废其余 " + cancelled + " 条分支" : "")));
        log.info("流程 {} 事件网关 {}：事件 {} 命中分支 {}，作废其余 {} 条",
                instance.getId(), gateway == null ? "?" : gateway.getId(),
                job.getExceptionMessage(), catchEvent.getId(), cancelled);

        // 捕获事件只"等"，没有任何动作要执行，事件到了直接离开。
        // 走 startFrom（它同样是 leave，不是重进）并把触发原因先挂成结果，
        // 让 leave 写出唯一那条活动记录 —— 与 fireEventBoundary 同一套写法。
        // 这样两条分支在轨迹上是对称的：赢的那条记"被什么事件选中的"，
        // 输的那些各记一条 eventGatewayLost；不这么做的话赢的那条压根不出现，
        // 而"这次竞速选中了谁"只能靠猜。
        context.setPendingActivityOutcome(reason);
        engine.startFrom(context, token);
        persistAll(context);
        resolveCompletion(context);
        fireJobExecuted(definition, instance.getId(), job, true);
        return true;
    }

    /**
     * 作废同一事件网关下除 {@code winnerId} 外的所有分支。
     *
     * @return 作废了几条
     */
    private int cancelSiblingBranches(WfContext context, WfDefinition definition,
                                      WfNode gateway, String winnerId) {
        List<String> siblingIds = new ArrayList<>();
        for (WfFlow flow : definition.outgoingFlows(gateway.getId())) {
            if (flow.getTargetRef() != null && !flow.getTargetRef().equals(winnerId)) {
                siblingIds.add(flow.getTargetRef());
            }
        }
        if (siblingIds.isEmpty()) {
            return 0;
        }
        int cancelled = 0;
        for (WfExecution execution : context.getProcessExecutions()) {
            if (execution == null || execution.isEnded()) {
                continue;
            }
            if (!siblingIds.contains(execution.getActivityId())) {
                continue;
            }
            // 先撤订阅再结束 token：反过来的话 token 已 ENDED，
            // 而按 executionId 删 job 的那条路径会顺手判"token 不在了"而跳过删除
            deleteCatchJobsOf(context, execution);
            context.recordActivity(execution.getActivityId(), null, "intermediateCatchEvent",
                    "eventGatewayLost");
            execution.setState(WfExecution.State.ENDED);
            // setState 只改内存对象。既有 token 要重新落库必须登记进 touched，
            // 漏了这一句的症状是"内存里作废了、库里还是 WAITING" ——
            // 落选的分支会永远留在那里，流程再也结束不了，而引擎日志一片正常
            context.markTouched(execution);
            cancelled++;
        }
        return cancelled;
    }

    /**
     * 捕获事件所属的事件网关 —— 顺着它唯一的入线找源头。
     *
     * <p>入线不唯一时<b>抛异常</b>而不是返回 {@code null}：返回 null 的话这一轮竞速
     * 会因为"没有网关"而一个兄弟都不作废，流程于是把全部分支都跑一遍 ——
     * 那正是这个功能存在的理由被推翻，且从外面看一切正常。
     * 部署期本来就该拦住这种定义（见 {@code WfDefinitionValidator}），
     * 抛出来是为了让"绕过部署期"这件事本身变得可见。
     *
     * <p>入线唯一但源头不是事件网关时返回 {@code null}，那是合法的：普通的
     * 中间捕获事件（流程里直接写、等消息继续）触发时只前进自己。
     */
    private WfNode gatewayOf(WfDefinition definition, WfNode catchEvent) {
        if (catchEvent == null) {
            throw new WfEngineException("事件网关订阅指向的节点在定义里不存在："
                    + "多半是定义被换过而残留的旧 job");
        }
        List<WfFlow> inFlows = definition.incomingFlows(catchEvent.getId());
        if (inFlows.size() != 1) {
            throw new WfEngineException("中间捕获事件 " + catchEvent.getId() + " 有 "
                    + inFlows.size() + " 条入线，认不出它属于哪个事件网关，"
                    + "也就不知道该作废哪些兄弟分支。这是部署期就应当拦下的定义错误");
        }
        WfNode source = definition.node(inFlows.get(0).getSourceRef());
        return source != null && source.getType() == WfNodeType.EVENT_BASED_GATEWAY ? source : null;
    }

    /** 删掉某个 token 上还挂着的捕获事件订阅。 */
    private void deleteCatchJobsOf(WfContext context, WfExecution execution) {
        WfJobQuery query = new WfJobQuery()
                .setProcessInstanceId(execution.getProcessInstanceId())
                .setPageNum(1).setPageSize(200);
        for (WfJob job : persistence.queryJobs(query)) {
            if (job != null && execution.getId().equals(job.getExecutionId())
                    && isEventGatewayJob(job.getType())) {
                persistence.deleteJob(job.getId());
            }
        }
    }

    private static boolean isEventGatewayJob(WfJobType type) {
        return type == WfJobType.EVENT_MESSAGE
                || type == WfJobType.EVENT_SIGNAL;
    }

    /**
     * 事件网关的分支与边界订阅是<b>两种不同的触发后果</b>，所以分两条路走：
     * 边界是"打断宿主"，网关是"竞速兄弟分支"。
     *
     * @return 被唤醒的流程实例；没有匹配分支时返回 {@code null}
     */
    private WfProcessInstance fireEventGatewayBranches(WfJobType type, String eventName,
                                                       String processInstanceId, String userId,
                                                       String comment) {
        String wanted = eventName == null ? null : eventName.trim();
        if (wanted == null || wanted.isEmpty()) {
            throw new WfEngineException("事件名不能为空");
        }
        WfJobQuery query = new WfJobQuery().setType(type);
        if (processInstanceId != null) {
            query.setProcessInstanceId(processInstanceId);
        }
        WfProcessInstance advanced = null;
        int fired = 0;
        for (WfJob job : persistence.queryJobs(query.setPageNum(1).setPageSize(500))) {
            if (!wanted.equals(job.getSubscriptionName())) {
                continue;
            }
            if (fireEventGatewayBranch(job, userId, type.outcomePrefix() + type.getLabel()
                    + " [" + wanted + "]" + (comment == null ? "" : "：" + comment))) {
                fired++;
                advanced = requireInstance(job.getProcessInstanceId());
            }
        }
        if (fired > 0) {
            log.info("{} [{}] 唤醒 {} 个事件网关分支", type.getLabel(), wanted, fired);
        }
        return advanced;
    }

    /**
     * 外部任务完成：让 token 在原地继续往下走。
     *
     * <p>放在 runtime 而不是外部任务服务里，是因为上下文的构造与落库管线只有这里有
     * （{@code newContext} + {@code persistAll} + {@code resolveCompletion}），
     * 在外面复制一份的结果是两条管线各自漂移，而漂移的表现是"外部任务完成后流程没往下走"。
     *
     * @param jobId     外部任务 job 的 id
     * @param workerId  完成人（记入评论与轨迹，便于追责）
     * @param variables 外部返回的变量
     */
    public WfProcessInstance completeExternalTask(String jobId, String workerId,
                                                   Map<String, Object> variables) {
        com.zifang.z.wf.core.model.WfJob job = persistence.findJob(jobId);
        if (job == null) {
            throw new WfEngineException("外部任务不存在: " + jobId);
        }
        WfProcessInstance instance = requireInstance(job.getProcessInstanceId());
        WfDefinition definition = definitionOf(instance);
        WfExecution token = job.getExecutionId() == null
                ? null : persistence.findExecution(job.getExecutionId());
        if (token == null || token.isEnded()
                || !job.getElementId().equals(token.getActivityId())) {
            // token 必须仍停在这一步。token 早就走了还推进，等于把流程从别处拽下来 ——
            // 与 fireEventBoundary 里那道同构的闸门是同一个道理。
            //
            // 走不到这里的最常见情形是 worker 超时重发：那时 job 已被上一个 complete
            // 删掉，requireLockHolder 会先报"外部任务不存在"。能落到这里的要么是
            // 清理逻辑漏了一步，要么是并发下有人绕过了锁校验。
            //
            // 返回当前实例而不是抛异常：worker 需要知道"不用再做了"，
            // 抛异常会让它无限重试
            log.info("外部任务 {} 提交时 token 已不在节点 {} 上（当前 {}），按重复提交处理",
                    jobId, job.getElementId(),
                    token == null ? "不存在" : token.getActivityId());
            return instance;
        }
        if (variables != null && !variables.isEmpty()) {
            instance.getVariables().putAll(variables);
        }
        persistence.saveComment(new WfComment(idGenerator.nextCommentId(),
                instance.getId(), workerId, "external",
                "外部任务 [" + job.getTopic() + "] 完成"
                        + (variables == null || variables.isEmpty() ? "" : "，结果 " + variables)));

        // 显式删掉，顺序与 WfJobService#fire 一致（先删再推进）。
        //
        // 实测：startFrom 里的 leave 会 clearJobsOf(token)，落库时顺带把本 token 上的
        // job 全撤掉，所以这一步在当前路径上并非必需。仍然显式删是为了不把
        // 「这件事办完了」寄托在一个间接机制上 —— 那个机制服务于定时器边界
        // （token 离开节点时撤掉它起过的表），语义是"清本节点的旧表"，
        // 与"这件外部任务完结了"不是一回事。
        persistence.deleteJob(jobId);

        WfContext context = contextForError(instance, definition, token, null);
        context.setAuthenticatedUserId(workerId);
        engine.startFrom(context, token);
        persistAll(context);
        resolveCompletion(context);
        return instance;
    }

    /**
     * 续跑一个异步 job —— 异步前置与异步后置共用这一个入口。
     *
     * <p>两者的区别全部落在 {@link WfEngine#resumeEnter} 与
     * {@link WfEngine#resumeLeave} 上：前置要把节点真的跑一遍，后置只补"离开"这个动作。
     * 放在一个方法里而不是两个，是因为它们的校验、事务边界、落库管线完全一样，
     * 拆开会各自漂移 —— 而漂移的表现是"异步 job 执行了但流程没动"。
     *
     * <p>先删 job 再续跑，顺序与 {@code WfJobService#fire}、
     * {@link #completeExternalTask} 一致：异步 job 排一次队执行一次，
     * 留着会被下一次扫描再执行一遍。前置的话节点行为会被跑第二遍
     * （delegate 调两次、审批任务重建），后置的话流程会多走一格。
     *
     * @return 是否真的续跑了（流程已结束、token 已挪走这类"该做没做"要能被区分开）
     */
    public boolean executeAsyncJob(com.zifang.z.wf.core.model.WfJob job) {
        com.zifang.z.wf.core.model.WfJobType type = job.getType();
        boolean before = type == com.zifang.z.wf.core.model.WfJobType.ASYNC_BEFORE;
        boolean after = type == com.zifang.z.wf.core.model.WfJobType.ASYNC_AFTER;
        if (!before && !after) {
            throw new WfEngineException("job " + job.getId() + " 不是异步 job（类型 "
                    + type + "），不能按异步续跑处理");
        }
        WfProcessInstance instance = requireInstance(job.getProcessInstanceId());
        if (instance.getStatus().isTerminal()) {
            // 流程已经结束还在续跑：多半是残留 job（清理逻辑漏了）。
            // 返回 false 而不是抛异常 —— 抛出去会被执行器算成失败并重试，
            // 而重试永远不会有用
            // 传 null 定义的道理同 fireEventBoundary 的终态早退：
            // 流程已终态，定义可能已删，强行取会抛 —— 而这里正是不该抛的地方
            fireJobExecuted(null, instance.getId(), job, false);
            log.warn("流程 {} 已是终态 {}，却还有异步 job {} 待续跑，忽略",
                    instance.getId(), instance.getStatus(), job.getId());
            return false;
        }
        WfDefinition definition = definitionOf(instance);
        WfNode node = definition.node(job.getElementId());
        if (node == null) {
            throw new WfEngineException("异步 job " + job.getId() + " 指向的节点不存在: "
                    + job.getElementId() + "。流程定义可能在 job 建立后被替换过");
        }
        WfExecution token = job.getExecutionId() == null
                ? null : persistence.findExecution(job.getExecutionId());
        // token 必须还停在这一步上。早就走了还续跑，等于把流程从别处拽回来 ——
        // 与 fireEventBoundary / completeExternalTask 里的同构闸门是同一个道理。
        if (token == null || token.isEnded() || !job.getElementId().equals(token.getActivityId())) {
            fireJobExecuted(definition, instance.getId(), job, false);
            log.info("异步 job {} 续跑时 token 已不在节点 {} 上（当前 {}），忽略",
                    job.getId(), job.getElementId(),
                    token == null ? "不存在" : token.getActivityId());
            return false;
        }

        persistence.saveComment(new WfComment(idGenerator.nextCommentId(),
                instance.getId(), "system", "job",
                (before ? "异步前置" : "异步后置") + "续跑节点 " + node.getId()));
        persistence.deleteJob(job.getId());

        WfContext context = contextForError(instance, definition, token, null);
        context.setAuthenticatedUserId("system");
        if (before) {
            engine.resumeEnter(context, token);
        } else {
            engine.resumeLeave(context, token);
        }
        persistAll(context);
        resolveCompletion(context);
        fireJobExecuted(definition, instance.getId(), job, true);
        log.info("流程 {} 的异步{} job {} 已续跑，token 位于 {}",
                instance.getId(), before ? "前置" : "后置", job.getId(), token.getActivityId());
        return true;
    }

    /**
     * 建一次推进的上下文，并把钩子分发器挂上去。
     *
     * <p><b>必须走这里而不是直接 {@code engine.newContext}</b>：漏掉一处就等于
     * 那条路径上的流转钩子静默不触发 —— 而"接口在、方法有、就是不响"是最难查的一类。
     * 新增推进入口时照此调用即可。
     */
    // ==================== 迁移 ====================

    /**
     * 实例迁移 —— 把 token 强行挪到指定节点并从那里继续。
     *
     * <p><b>与 {@code WfTaskService#jump} 的区别是「有没有待办可跳」</b>：
     * jump 的入口是任务 id，所以一条正在等消息、信号、定时器或外部 worker 的流程
     * 压根跳不动 —— 它没有任务。而"改流程时把在途的单迁过去"恰恰最常发生在这种单上：
     * 它已经等了很久，业务方决定不再等事件，直接走人工。
     *
     * <p>迁移必须把源节点上"正在等的那些东西"一并撤掉，否则会出现一个 token
     * 同时被两个东西盯着的情况：事件照常到达时照样会推进这条已经迁走的分支，
     * 于是流程在一个节点上被走两遍。撤的四样：源节点上的待办、该 token 上的 job、
     * 该 token 的到达记录（否则汇合会误判它已经到过）。
     *
     * <p><b>不检查图上是否可达</b>，与 jump 同一理由：运营改流程后图往往已经对不上，
     * 强行校验可达性等于"运营改一次流程就得重画一遍"。代价是可能跳到图外节点，
     * 因此目标节点必须在定义里存在。
     *
     * @param sourceActivityId 只迁移停在这个节点上的 token；{@code null} 表示
     *                         迁移该实例<b>全部</b>未结束的 token
     * @return 被迁移的流程实例
     */
    public WfProcessInstance move(String processInstanceId, String targetActivityId,
                                   String sourceActivityId, String operatorId,
                                   String reason, Map<String, Object> variables) {
        WfProcessInstance instance = requireInstance(processInstanceId);
        if (instance.getStatus().isTerminal()) {
            throw new WfEngineException("流程实例 " + processInstanceId + " 已是终态 "
                    + instance.getStatus() + "，无法迁移");
        }
        WfDefinition definition = definitionOf(instance);
        if (definition.node(targetActivityId) == null) {
            throw new WfEngineException("迁移目标节点不存在于流程定义 " + definition.getKey()
                    + " 中: " + targetActivityId);
        }
        if (targetActivityId.equals(sourceActivityId)) {
            // 不是死代码：source 为 null 且实例只有一条 token 时，源与目标会是同一个节点。
            // 不挡住的话会走完"撤光待办与 job、再原地进入"这一整套，结果是待办被重建
            throw new WfEngineException("迁移目标与当前节点相同: " + targetActivityId
                    + "。这多半是漏传了 sourceActivityId —— "
                    + "不给源就表示迁移全部 token，而实例当前只有这一条");
        }

        List<WfExecution> candidates = new ArrayList<>();
        for (WfExecution execution : persistence.findExecutionsByProcessInstance(processInstanceId)) {
            if (execution == null || execution.isEnded()) {
                continue;
            }
            if (sourceActivityId == null || sourceActivityId.trim().isEmpty()
                    || sourceActivityId.equals(execution.getActivityId())) {
                candidates.add(execution);
            }
        }
        if (candidates.isEmpty()) {
            throw new WfEngineException("流程实例 " + processInstanceId + " 上没有可迁移的 token"
                    + (sourceActivityId == null ? ""
                    : "（找的是停在 " + sourceActivityId + " 上的）")
                    + "。已结束的 token 不参与迁移 —— 拿它去改会造出一条孤儿记录");
        }

        persistence.saveComment(new WfComment(idGenerator.nextCommentId(), processInstanceId,
                operatorId == null || operatorId.trim().isEmpty() ? "system" : operatorId,
                "move", "迁移到节点 " + targetActivityId + "：迁走 " + candidates.size()
                + " 条 token（原位置 "
                + candidates.get(0).getActivityId() + "）"
                + (reason == null || reason.trim().isEmpty() ? "" : "。原因: " + reason)));

        // 先把源节点上的待办统一撤掉，再逐条迁移。
        // 顺序有讲究：先撤后迁，中间不存在"待办还挂在已经被迁走的节点上"的窗口。
        // 撤待办这件事放在循环外而不是逐条做，是因为它是**按节点**的，
        // 而逐条做会让人误以为每条 token 只带走自己那条 —— 多实例节点上
        // 一个节点有 N 条 token N 个待办，这个区别不是笔误：
        // 撤错了会留下"token 还在原地但待办没了"的死分支，永远办不完也查不出来。
        // 对 move 来说这个节点的所有 token 都在 candidates 里，所以按节点撤是安全的。
        Set<String> sourceNodes = new LinkedHashSet<>();
        for (WfExecution execution : candidates) {
            sourceNodes.add(execution.getActivityId());
        }
        for (String sourceNode : sourceNodes) {
            cancelOpenTasksOn(processInstanceId, sourceNode);
        }

        // 变量在循环外并一次：往 instance 上 putAll 是幂等的，
        // 放进循环里只会让人误以为每条 token 拿到的是不同的一份
        if (variables != null && !variables.isEmpty()) {
            instance.getVariables().putAll(variables);
        }
        for (WfExecution execution : candidates) {
            migrateToken(instance, definition, execution, targetActivityId, operatorId,
                    reason == null || reason.trim().isEmpty()
                            ? "（迁移）" : "（迁移: " + reason + "）");
        }
        log.info("流程 {} 迁移到 {}: 迁走 {} 条 token，操作人={}",
                processInstanceId, targetActivityId, candidates.size(), operatorId);
        return instance;
    }

    /**
     * 把<b>一条</b> token 从它当前所在节点迁到目标节点。
     *
     * <p>{@link #move} 与 {@code WfTaskService#jump} 共用这一段。两者本质是同一个动作的
     * 不同粒度 —— 迁整个实例里符合条件的所有 token，还是只迁某个待办所在的那一条 ——
     * 拆成两份写，代价是修一处漏一处，而漏的那处症状还极不直观
     * （本轮 {@code jump} 就是漏了"重新进入目标节点"这步，跳过去没有待办、
     * 流程一路跑到了结束，看上去像跳转功能坏了）。
     *
     * <p>能合在这里还有一个前提：context 构造、{@code hookDispatcher} 挂载、
     * {@code persistAll} 与终态判定<b>全都在本类</b>里完成。
     * 分出去一份就等于"有的迁移带生命周期钩子、有的不带"，
     * 而钩子差异不会报错，只会让一半的迁移绕过审批前置校验。
     *
     * <p><b>不管源节点上遗留的待办</b>，那是调用方的策略，因为两者对"源节点"的定义不同：
     * {@link #move} 是按节点整体腾空（该节点所有 token 都在迁走），所以在循环前统一撤；
     * {@code jump} 只针对一条 token，它自己的待办已经由 jump 办结了，
     * 而同一个节点上<b>别的</b> token 的待办必须留着 —— 一起撤掉会让那些分支
     * 停在原地却没有待办，既办不完也查不出来。
     *
     * @param traceLabel 写进轨迹的来源标注，如 {@code "（迁移: 改流程）"} / {@code "（跳转）"}
     */
    void migrateToken(WfProcessInstance instance, WfDefinition definition, WfExecution execution,
                      String targetActivityId, String operatorId, String traceLabel) {
        String processInstanceId = instance.getId();
        String from = execution.getActivityId();
        // 1. 撤掉这条 token 上的 job（消息订阅 / 事件网关分支 / 定时器 / 外部任务）。
        //    漏掉的话事件照常到达会推进一条已经迁走的分支 —— 同一个节点被走两遍
        cancelJobsOf(processInstanceId, execution.getId());
        // 2. 清到达记录：token 换了位置，"它到过哪些节点"这件事也得跟着改。
        // 先把现状说清楚，免得下一个读代码的人被注释带偏：
        // 这份列表目前**只写不读** —— 汇合判定 allSiblingsArrived 比的是兄弟 token 的
        // activityId，不查它。所以清不清当前都不改变行为（反向验证摘掉这一行，
        // 本轮 17 条用例全绿，证实了这点）。
        // 仍然清，是因为这份记录随 token 落库，而它描述的是一件已经变了的事实：
        // 留着等于给将来"拿它做判定"的代码埋一份脏数据。
        // 顺带一提，WfExecution#arriveAt 的注释写着"汇合判定用"，那个说法现在也不准确 ——
        // 汇合逻辑改用 activityId 比较之后就没再查过它。
        execution.clearArrived();

        WfContext context = newContext(definition, instance, execution);
        context.setAuthenticatedUserId(operatorId);
        context.setSubProcessLauncher(this);
        context.setProcessExecutions(
                persistence.findExecutionsByProcessInstance(processInstanceId));
        execution.setActivityId(targetActivityId);
        execution.setState(WfExecution.State.ACTIVE);
        execution.setEnteredTime(new Date());
        // 迁移记录记在**源**节点上，不靠 pendingActivityOutcome。
        // 目标节点会在它自己离开时写它那一条（人工节点 = 办结时），
        // 而源节点这次是被我们直接改掉 activityId 的、走不到 leave，
        // 所以不补这一条的话轨迹上会凭空少一次"等主管批"，
        // 看起来就像流程是从事件网关直接到了 manual
        WfNode source = definition.node(from);
        context.recordActivity(from, source == null ? null : source.getName(),
                source == null ? null : source.getType().bpmnName(),
                "→ " + targetActivityId + traceLabel);
        // enterAt 而不是 advance/startFrom：目标节点一次都没跑过，
        // 用 leave 会直接沿它的出线跳过去 —— "迁到人工节点却没有待办"
        engine.enterAt(context, execution);
        persistAll(context);
        resolveCompletion(context);
    }

    /**
     * 撤掉某个 token 上挂着的全部 job。
     *
     * <p>按 executionId 逐条删而不是按类型删：迁移不关心它是等消息、等定时器
     * 还是等外部 worker，漏掉任何一类都会留下"一个 token 被两个东西盯着"的状态。
     */
    private void cancelJobsOf(String processInstanceId, String executionId) {
        if (executionId == null) {
            return;
        }
        List<WfJob> jobs = persistence.queryJobs(new WfJobQuery()
                .setProcessInstanceId(processInstanceId).setPageNum(1).setPageSize(500));
        if (jobs == null) {
            return;
        }
        for (WfJob job : jobs) {
            if (job != null && executionId.equals(job.getExecutionId())) {
                persistence.deleteJob(job.getId());
            }
        }
    }

    /**
     * 本服务使用的引擎。
     *
     * <p>存在的理由是<b>结构性的</b>：{@code WfTaskService#jump} 曾经自己
     * {@code new WfEngine()}，于是跳转丢掉自定义的行为注册表、表达式求值器配置
     * 与 id 生成器 —— 同一份流程，跳转前后的节点行为可能不是同一套。
     * 把引擎从这里暴露出去而不是给 {@code WfTaskService} 加构造参数，
     * 是为了让"用同一个引擎"成为**没法搞错**的形态而不是一条约定。
     */
    public WfEngine getEngine() {
        return engine;
    }

    private WfContext newContext(WfDefinition definition, WfProcessInstance instance,
                                 WfExecution execution) {
        WfContext context = engine.newContext(definition, instance, execution);
        context.setHookDispatcher(hookDispatcher);
        return context;
    }

    private WfContext contextForError(WfProcessInstance instance, WfDefinition definition,
                                      WfExecution execution, Map<String, Object> variables) {
        WfContext context = newContext(definition, instance, execution);
        context.setSubProcessLauncher(this);
        context.setVariables(variables);
        context.setProcessExecutions(
                persistence.findExecutionsByProcessInstance(instance.getId()));
        return context;
    }

    private void cancelOpenTasks(String processInstanceId) {
        cancelOpenTasksOn(processInstanceId, null);
    }

    /** 作废未完成任务；{@code nodeId} 非 null 时只作废该节点上的。 */
    private void cancelOpenTasksOn(String processInstanceId, String nodeId) {
        WfTaskQuery query = new WfTaskQuery().setProcessInstanceId(processInstanceId)
                .setOpenOnly(true).setPageNum(1).setPageSize(Integer.MAX_VALUE);
        if (nodeId != null) {
            query.setDefinitionId(nodeId);
        }
        // 先把要作废的收齐：作废之后就查不到了（openOnly 只看未完成），
        // 分两个循环的话第二个循环永远空转，定时器一个都撤不掉
        List<WfTask> pendingTasks = persistence.queryTasks(query);
        for (WfTask pending : pendingTasks) {
            pending.setStatus(WfTask.Status.CANCELLED);
            pending.setEndTime(new Date());
            pending.nextRevision();
            persistence.saveTask(pending);
            // 人是被边界事件打断的，不是办结的：必须发 fireDeleted 而不是
            // fireAfterComplete，否则审批方收到的是"办结成功"的通知
            hookDispatcher.fireDeleted(pending.getId(), pending.getAssignee(),
                    processInstanceId, "boundary-interrupted");
        }
        // 同步撤掉对应的定时器：
        // nodeId 非 null 时流程本身还活着（走补偿分支），只撤这个节点 token 上的表；
        // 为 null 时整个实例都要收尾，按实例清。
        if (nodeId != null) {
            for (WfTask pending : pendingTasks) {
                if (pending.getExecutionId() != null) {
                    persistence.deleteJobsByExecution(pending.getExecutionId());
                }
            }
        } else {
            persistence.deleteJobsByProcessInstance(processInstanceId);
        }
    }

    // ==================== 子流程 ====================
    @Override
    public String launch(WfContext context, com.zifang.z.wf.core.definition.WfNode node,
                         WfExecution execution) {
        String calledKey = node.getCalledElementKey();
        // 被调流程不存在时必须让父流程失败。旧实现在这里 catch 住所有异常、
        // 记一条 __wf_sub_error_ 变量就放行，于是"调子流程算价"这一步静默失败，
        // 主流程照样一路跑完并报完成 —— 而下一步拿到的是空结果。
        // 与 serviceTask 的 delegate 同理：这一步没做，不能装作做过。
        WfDefinition subDefinition = repositoryService.getLatestDefinition(calledKey);

        // 子流程继承父流程变量（浅拷贝：子流程改自己的变量不影响父）
        Map<String, Object> subVariables = new HashMap<>(context.getProcessInstance().getVariables());
        String subInstanceId = startProcessInstance(subDefinition, null,
                context.getAuthenticatedUserId(), null, subVariables);
        context.setVariable(SUB_INSTANCE_PREFIX + node.getId(), subInstanceId);
        backfillCallResult(context, node, subInstanceId);
        log.info("启动子流程: node={}, 子流程定义={}, 子实例={}",
                node.getId(), calledKey, subInstanceId);
        return subInstanceId;
    }

    /**
     * 被调流程的结果回填到父流程变量。
     *
     * <p>此前 {@code WfNode#resultExpression} 在 callActivity 上是<b>死字段</b>：
     * 解析器把它读进节点、校验器也不管，但 launch 从不求值它 ——
     * 作者写 {@code zifang:resultExpression="..."} 期望拿到子流程结果，
     * 实际永远是 null，而流程照常完成。
     *
     * <p><b>为什么拆成 resultVariable + resultExpression 两个属性</b>，
     * 而不靠"剥掉 {@code ${}} 剩下的就是变量名"来猜：
     * {@code ${a + b}} 和 {@code a + b} 都是合法表达式，前者求值、后者是字面量，
     * 从字符串形状根本分不出"这段是要算的"还是"这段是要写进哪个变量的"。
     * 猜错的后果是静默把值写进一个没人读的变量。
     * 所以和 Camunda 一样：{@code resultVariable} 存名字（要写进哪），
     * {@code resultExpression} 存表达式（怎么算）。两个都不配时，
     * 结果落在固定名 {@link #SUB_RESULT_VARIABLE} 上。
     */
    private void backfillCallResult(WfContext context,
                                    com.zifang.z.wf.core.definition.WfNode node,
                                    String subInstanceId) {
        WfProcessInstance child = persistence.findProcessInstance(subInstanceId);
        if (child == null) {
            throw new WfEngineException("子流程实例不存在，无法回填结果: " + subInstanceId);
        }

        String target = node.getResultVariable();
        String expression = node.getResultExpression();
        boolean hasTarget = target != null && !target.trim().isEmpty();
        boolean hasExpression = expression != null && !expression.trim().isEmpty();

        if (!hasTarget && !hasExpression) {
            // 什么都没配：结果仍要能取到，否则子流程算出来的东西无处可取
            context.setVariable(SUB_RESULT_VARIABLE, child.getResult());
            context.setVariable(SUB_INSTANCE_VARIABLE, subInstanceId);
            return;
        }
        if (hasTarget && !hasExpression) {
            // 只配了变量名：直接把子流程的流程结果放进去，这是最常见的用法
            context.setVariable(target.trim(), child.getResult());
            context.setVariable(SUB_INSTANCE_VARIABLE, subInstanceId);
            return;
        }

        Map<String, Object> merged = new HashMap<>(context.mergedVariables());
        merged.putAll(child.getVariables());
        merged.put(SUB_RESULT_VARIABLE, child.getResult());
        merged.put(SUB_INSTANCE_VARIABLE, subInstanceId);
        Object value = context.getExpressionEvaluator()
                .evalRaw(expression, merged);

        // 配了表达式但没配变量名：结果只能落固定名，否则无处安放
        String name = hasTarget ? target.trim() : SUB_RESULT_VARIABLE;
        context.setVariable(name, value);
        log.info("回填子流程结果: node={} 变量={} 值={}", node.getId(), name, value);
    }

    // ==================== 内部 ====================

    /**
     * 把 context 里攒下的 token / 任务 / 历史统一落库。
     * <p>顺序有讲究：先 token 再任务（任务外键指向 token），最后实例（实例是聚合根）。
     */
    private void persistAll(WfContext context) {
        // ---- 0. 任务创建前置钩子（可否决）：必须跑在任何落库之前 ----
        // 放在保存任务的循环里逐个判，会留下"token 已落库、任务没落、instance 也没落"的
        // 撕裂写（persistAll 的顺序是 token → task → history → instance）。
        // 钩子是在"创建审批任务"这个动作上表达意见，早于任何持久化才谈得上"否决"。
        fireBeforeCreateHooks(context);

        // 本次 persistAll 已落库的 token id（防同一 token 被重复写）。
        // 必须是**方法局部变量**：service 是单例且被多线程共享，
        // 放字段里会让 A 请求的 token 混进 B 请求的去重表 → B 的 token 漏存。
        List<String> persisted = new ArrayList<>();

        // 已存在的 token 必须先落：引擎在 leave() 里改的就是它（推进位置、置 ENDED），
        // 而它既不在 newExecutions（本次没新建）也不在 touchedExecutions（只记了汇合折叠）
        // 里 —— 漏存的后果是"任务已办结但 token 仍停在原节点"，
        // 表现为流程永远 ACTIVE、待办点不动。
        persistExecutionOnce(context, context.getCurrentExecution(), persisted);
        for (WfExecution execution : context.getNewExecutions()) {
            persistExecutionOnce(context, execution, persisted);
        }
        for (WfExecution execution : context.getTouchedExecutions()) {
            persistExecutionOnce(context, execution, persisted);
        }
        for (WfTask task : context.getCreatedTasks()) {
            if (task.getId() == null) {
                task.setId(idGenerator.nextTaskId());
            }
            persistence.saveTask(task);
            hookDispatcher.fireAfterCreate(task.getId(), task.getAssignee(), task.getProcessInstanceId());
            if (task.getAssignee() != null) {
                hookDispatcher.notifyTaskAssigned(task.getId(), task.getProcessInstanceId(),
                        task.getAssignee(), context.getProcessInstance().getDefinitionKey(),
                        context.getProcessInstance().getVariables());
            }
        }
        for (WfActivityInstance history : context.getActivityHistory()) {
            if (history.getId() == null) {
                history.setId(idGenerator.nextActivityId());
            }
            persistence.saveActivityInstance(history);
        }
        persistJobs(context);
        WfProcessInstance instance = context.getProcessInstance();
        instance.nextRevision();
        persistence.saveProcessInstance(instance);
    }

    /**
     * 落本次推进产生的 job，并撤掉已离开节点的 job。
     *
     * <p>顺序是"先撤后建"：token 挂在一个既有定时器边界的节点上又离开它时，
     * 先撤才能保证它不会在同一次推进里既被删又被建回来。
     * 反过来的话，每绕一圈回同一个节点就会多留一只永远不响的旧表。
     */
    /**
     * 通知钩子：排上了一个 job。
     *
     * <p>从 {@link #persistJobs} 里抽出来是因为除了"本次推进新建的 job"，
     * 还有别处也会建 job（比如外部任务失败后写 duedate 重建一条重试记录）。
     */
    /**
     * 通知钩子：一个 job 执行完了。
     *
     * <p>所有触发路径（定时器/边界事件、消息信号、异步续跑）都走它，
     * 免得每加一种 job 就得记得补一处 —— 那正是 §3 审计里"实现了但从不触发"的成因。
     *
     * @param success true=真的推进了；false=这次没推进（流程已结束、token 已挪走）。
     *               报成 true 会让"执行成功率"这个指标凭空好看
     */
    private void fireJobExecuted(WfDefinition definition, String processInstanceId,
                                 WfJob job, boolean success) {
        hookDispatcher.fireJobExecuted(definition == null ? null : definition.getKey(),
                processInstanceId, job.getId(),
                job.getType() == null ? null : job.getType().name(), job.getElementId(), success);
    }

    private void fireJobScheduled(WfDefinition definition, String processInstanceId, WfJob job) {
        hookDispatcher.fireJobScheduled(definition == null ? null : definition.getKey(),
                processInstanceId, job.getId(),
                job.getType() == null ? null : job.getType().name(), job.getElementId());
    }

    private void persistJobs(WfContext context) {
        for (String executionId : context.getJobsToClearByExecution()) {
            persistence.deleteJobsByExecution(executionId);
        }
        for (WfJob job : context.getCreatedJobs()) {
            if (job.getId() == null) {
                job.setId(idGenerator.nextJobId());
            }
            persistence.saveJob(job);
            // 必须在 saveJob 之后触发：钩子实现方常拿 jobId 去查表，
            // 报一个还不存在的 id 会让"统计 job 排队"这件事第一次就失败
            fireJobScheduled(context.getDefinition(), context.getProcessInstanceId(), job);
        }
    }

    /**
     * 任务创建前置钩子（可否决）。
     *
     * <p>跑在 {@link #persistAll} 的最前面，早于任何持久化：
     * 钩子是在"创建审批任务"这个动作上表达意见，被否决时必须一个字节都没落库。
     *
     * <p>否决一律抛 {@link WfEngineException}，与 {@code onBeforeComplete} 的否决口径一致
     * （都映射成 HTTP 400）。不选"静默跳过这个任务"是因为那样 token 会停在本节点
     * 且没有任何待办可办 —— 表现为流程永远 ACTIVE 且待办点不动，是最难排查的一类挂起。
     */
    private void fireBeforeCreateHooks(WfContext context) {
        for (WfTask task : context.getCreatedTasks()) {
            if (task.getId() == null) {
                // 提前分配：钩子拿得到 taskId 才能把它写进审计/告警上下文
                task.setId(idGenerator.nextTaskId());
            }
            if (!hookDispatcher.fireBeforeCreate(task.getId(), task.getAssignee(),
                    task.getVariables())) {
                throw new WfEngineException("任务创建被钩子否决: "
                        + (task.getName() != null ? task.getName() : task.getDefinitionId()));
            }
        }
    }

    /**
     * 落一个 token，同 id 只落一次（currentExecution 常同时出现在 new / touched 里）。
     */
    private void persistExecutionOnce(WfContext context, WfExecution execution,
                                      List<String> persistedIds) {
        if (execution == null || execution.getId() == null) {
            return;
        }
        if (persistedIds.contains(execution.getId())) {
            return;
        }
        persistedIds.add(execution.getId());
        persistence.saveExecution(execution);
    }

    /**
     * 推进后判定流程是否完成。
     *
     * <p>判据是<b>存储层的真实状态</b>，不是引擎内存里的推测：
     * "全部 token 已结束 <b>且</b> 没有未完成任务 ⇒ 完成"。
     *
     * <p>为什么要两个条件：只判 token 会漏掉"token 已走到 endEvent 但任务还开着"
     * （并发办结两个任务时的竞态）；只判任务会在"任务被 terminate 作废"时误判完成。
     * 引擎内部做这个判断不可靠 —— 它看不见别的请求建的 token 与任务，
     * 而那正是多节点部署下的常态。
     */
    private void resolveCompletion(WfContext context) {
        WfProcessInstance instance = context.getProcessInstance();
        if (instance.getStatus() != null && instance.getStatus().isTerminal()) {
            return;
        }
        String processId = instance.getId();

        boolean anyActiveToken = false;
        for (WfExecution execution : persistence.findExecutionsByProcessInstance(processId)) {
            if (!execution.isEnded()) {
                anyActiveToken = true;
                break;
            }
        }
        if (anyActiveToken) {
            return;
        }
        boolean hasOpenTask = !persistence.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(processId).setOpenOnly(true)).isEmpty();
        if (hasOpenTask) {
            return;
        }

        instance.setStatus(WfProcessStatus.COMPLETED);
        instance.setEndTime(new Date());
        if (instance.getResult() == null) {
            instance.setResult(context.getProcessResult() == null ? "completed" : context.getProcessResult());
        }
        instance.nextRevision();
        persistence.saveProcessInstance(instance);
        // 兜底：正常路径上 leave() 已按 token 撤过表，这里是第二道。
        // 它存在是因为"撤表"有若干条旁路会绕过 leave —— 并行分支汇合时被折叠掉的
        // token、流程定义被替换后残留的表。前者让定时器在 token 早就不存在时去触发
        // 一条已经走完的分支，后者让到点的表去一个已不存在的流程实例上找宿主。
        // 代价是这一句无法单独反向验证：正常路径上它总是被 leave 兜住，
        // 功能测试对它天然无区分（同"分页下推"那类行为等价的防护）。
        persistence.deleteJobsByProcessInstance(processId);
        log.info("流程完成: processInstanceId={}, result={}", processId, instance.getResult());
    }

    /**
     * 取实例对应的流程定义（按实例记录的版本）。
     */
    private WfDefinition definitionOf(WfProcessInstance instance) {
        return repositoryService.getDefinitionOrLatest(
                instance.getDefinitionKey(), instance.getDefinitionVersion());
    }

    private WfProcessInstance requireInstance(String id) {
        WfProcessInstance instance = persistence.findProcessInstance(id);
        if (instance == null) {
            throw new WfEngineException("流程实例不存在: " + id);
        }
        return instance;
    }

    private WfTask requireTask(String id) {
        WfTask task = persistence.findTask(id);
        if (task == null) {
            throw new WfEngineException("任务不存在: " + id);
        }
        return task;
    }
}
