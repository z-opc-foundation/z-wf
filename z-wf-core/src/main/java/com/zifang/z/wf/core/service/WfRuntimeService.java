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
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.engine.WfIdGenerator;
import com.zifang.z.wf.core.engine.WfSubProcessLauncher;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
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
        // 汇合判定需要看到本实例的全部 token
        context.setProcessExecutions(persistence.findExecutionsByProcessInstance(instance.getId()));

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
        if (execution != null && definition.node(task.getDefinitionId()) != null) {
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

        log.info("流程终止: {}, 原因={}, 作废任务 {} 个", processInstanceId, reason, cancelled);
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

    // ==================== 子流程 ====================

    @Override
    public String launch(WfContext context, com.zifang.z.wf.core.definition.WfNode node,
                         WfExecution execution) {
        String calledKey = node.getCalledElementKey();
        try {
            WfDefinition subDefinition = repositoryService.getLatestDefinition(calledKey);
            // 子流程继承父流程变量（浅拷贝：子流程改自己的变量不影响父）
            Map<String, Object> subVariables = new HashMap<>(context.getProcessInstance().getVariables());
            String subInstanceId = startProcessInstance(subDefinition, null,
                    context.getAuthenticatedUserId(), null, subVariables);
            // 记录父子关联：父 token 的 children 之外，用变量记住子实例 id
            context.setVariable("__wf_sub_" + node.getId(), subInstanceId);
            log.info("启动子流程: node={}, 子流程定义={}, 子实例={}",
                    node.getId(), calledKey, subInstanceId);
            return subInstanceId;
        } catch (Exception e) {
            // 子流程启动失败：记录并让父流程继续（不因为子流程缺失而卡死主流程），
            // 业务方可通过 trail 里缺失的节点发现"这一步没真正跑起来"
            log.error("启动子流程失败: node={}, key={}", node.getId(), calledKey, e);
            context.setVariable("__wf_sub_error_" + node.getId(), e.getMessage());
            return null;
        }
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
        WfProcessInstance instance = context.getProcessInstance();
        instance.nextRevision();
        persistence.saveProcessInstance(instance);
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
