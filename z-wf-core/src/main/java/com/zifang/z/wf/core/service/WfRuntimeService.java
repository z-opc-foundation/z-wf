package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
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
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.persistence.WfProcessInstanceQuery;
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
        WfContext context = engine.newContext(definition, instance, null);
        context.setAuthenticatedUserId(userId);
        context.setSubProcessLauncher(this);
        engine.start(context);

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

        WfContext context = engine.newContext(definition, instance, null);
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

        WfContext context = engine.newContext(definition, instance, execution);
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
        for (WfTask task : persistence.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(processInstanceId).setStatus(WfTask.Status.CANCELLED))) {
            hookDispatcher.fireAfterComplete(task.getId(), task.getAssignee(), "terminated");
        }
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
        List<WfTask> matched = findWaitingReceiveTasks(signalName, null);
        if (matched.isEmpty()) {
            throw new WfEngineException("没有等待信号 [" + signalName + "] 的接收任务");
        }
        List<WfProcessInstance> advanced = new ArrayList<>();
        for (WfTask task : matched) {
            advanced.add(completeTask(task.getId(), userId, comment, variables));
        }
        log.info("广播信号 [{}] 唤醒 {} 个接收任务", signalName, matched.size());
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
        WfProcessInstance instance = requireInstance(job.getProcessInstanceId());
        if (instance.getStatus().isTerminal()) {
            // 流程已经结束了还在响：多半是残留 job（实例被外部直接改库终止、
            // 或清理逻辑漏了）。返回 false 而不是抛异常 ——
            // 抛出去会被执行器算成失败并重试，而重试永远不会有用。
            log.warn("流程 {} 已是终态 {}，却还有 job {} 到期，忽略触发",
                    instance.getId(), instance.getStatus(), job.getId());
            return false;
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
            log.info("job {} 触发时 token 已不在宿主节点 {} 上（当前 {}），"
                            + "按已办结处理，不触发边界事件",
                    job.getId(), job.getAttachedToRef(),
                    execution == null ? "不存在" : execution.getActivityId());
            return false;
        }

        long waitedMillis = System.currentTimeMillis()
                - (job.getCreateTime() == null ? System.currentTimeMillis()
                : job.getCreateTime().getTime());
        persistence.saveComment(new WfComment(idGenerator.nextCommentId(),
                instance.getId(), "system", "job",
                "节点 " + job.getAttachedToRef() + " 停留超时（已等 "
                        + WfTimerSupport.describeDuration(waitedMillis)
                        + "），触发边界事件 " + boundary.getId()));
        log.info("流程 {} 节点 {} 超时，触发边界事件 {}",
                instance.getId(), job.getAttachedToRef(), boundary.getId());

        // 宿主节点上的待办作废：人已经超时了，待办还挂着只会让人以为还能办
        cancelOpenTasksOn(instance.getId(), job.getAttachedToRef());

        WfContext context = contextForError(instance, definition, execution, null);
        execution.setActivityId(boundary.getId());
        execution.setState(WfExecution.State.ACTIVE);
        execution.setEnteredTime(new Date());
        // 结论交给 leave() 写成唯一那条记录（见 WfContext#pendingActivityOutcome）。
        // 不在这里单独 recordActivity：那会让边界事件在轨迹上出现两行 ——
        // 一行写 timer:30 分钟，一行写 completed —— 而"一次节点访问一条"是本引擎的硬约定。
        context.setPendingActivityOutcome("timer:" + WfTimerSupport.describeDuration(waitedMillis));
        engine.startFrom(context, execution);
        persistAll(context);
        resolveCompletion(context);
        return true;
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

    private WfContext contextForError(WfProcessInstance instance, WfDefinition definition,
                                      WfExecution execution, Map<String, Object> variables) {
        WfContext context = engine.newContext(definition, instance, execution);
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
    private void persistJobs(WfContext context) {
        for (String executionId : context.getJobsToClearByExecution()) {
            persistence.deleteJobsByExecution(executionId);
        }
        for (WfJob job : context.getCreatedJobs()) {
            if (job.getId() == null) {
                job.setId(idGenerator.nextJobId());
            }
            persistence.saveJob(job);
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
