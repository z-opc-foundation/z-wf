package com.zifang.z.wf.core.engine;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfTimerSupport;
import com.zifang.z.wf.core.definition.WfTimerType;
import com.zifang.z.wf.core.engine.expression.WfExpressionEvaluator;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.service.WfDelegateRegistry;

/**
 * 一次推进操作的执行上下文。
 *
 * <p>把"流程定义 + 实例 + 当前 token + 变量"打包传给引擎，引擎内部不持有跨调用的可变状态。
 * 这样引擎可以安全地被多线程调用（每个推进动作一个 context，天然隔离），
 * 也让"回滚/重放"成为可能。
 *
 * <p><b>变量读取顺序</b>：token 局部变量 → 流程实例变量。
 * 写入默认落到<b>流程实例</b>（{@link #setVariable}），
 * 只有显式调 {@link #setLocalVariable} 才写 token 局部 —— 因为审批场景里
 * "审批人填的意见/决定"几乎总是全流程可见的，写局部会"下一个节点看不到"，是常见坑。
 *
 * @author zifang
 */
public class WfContext {

    private final WfDefinition definition;

    private final WfProcessInstance processInstance;

    private WfExecution currentExecution;

    /** 本次推进产生的 token（fork 时可能有多个）。 */
    private final java.util.List<WfExecution> newExecutions = new java.util.ArrayList<>();

    /** 本次推进创建的 task。 */
    private final java.util.List<WfTask> createdTasks =
            new java.util.ArrayList<>();

    /** 本次推进产生的历史记录。 */
    private final java.util.List<WfActivityInstance> activityHistory =
            new java.util.ArrayList<>();

    /** 本次推进新建的 job（定时器边界事件）。 */
    private final java.util.List<WfJob> createdJobs =
            new java.util.ArrayList<>();

    /**
     * 本次推进要撤掉的 job 所属的 token。
     *
     * <p>记录 token 而不是 job id：定时器到期时刻尚未知（时长可以引用流程变量），
     * 且清理要覆盖"这个 token 在本节点期间建的所有 job"。
     */
    private final java.util.List<String> jobsToClearByExecution =
            new java.util.ArrayList<>();

    /**
     * 本次推进请求<b>终止整个作用域</b>（{@code terminateEndEvent}），值为该作用域的
     * 内联容器 id；<b>空串表示进程级</b>（即终止整个实例）。
     *
     * <p>用 {@code null} 表示"没有终止请求"而不是空串 ——
     * "没有请求"与"请求终止整个实例"是两件事，空串是后者，是最常被用到的那个，
     * 两者混在一个槽位里会让"忘了初始化"表现为"终止整个实例"。
     *
     * <p><b>为什么只登记不自己动手</b>：{@code WfEngine} 手里<b>没有持久化</b>，
     * 而终止要做的三件事（结束其余 token、作废待办、撤 job）全都得落库。
     * 与 {@link #pendingEvents} / {@link #jobsToClearByExecution} 同一套形状：
     * 引擎登记意图，运行期服务在 {@code finishTransaction} 里统一执行。
     */
    private String terminateScope;

    /**
     * 登记一次作用域终止。
     *
     * @param scope 内联容器 id；<b>空串</b>表示进程级（终止整个实例）
     */
    public void requestTerminate(String scope) {
        this.terminateScope = scope == null ? "" : scope;
    }

    /** 本次推进是否请求了作用域终止。 */
    public boolean isTerminateRequested() {
        return terminateScope != null;
    }

    /**
     * 本次推进请求终止的作用域：内联容器 id，空串表示进程级。
     *
     * <p><b>未请求时返回 {@code null}</b>，不要与空串混为一谈 ——
     * 调用方必须先判 {@link #isTerminateRequested()}。
     */
    public String getTerminateScope() {
        return terminateScope;
    }

    // ==================== 补偿登记（第 37 轮） ====================

    /**
     * 本次推进里<b>刚刚完成、且可补偿</b>的活动 id，按完成的先后排列。
     *
     * <p>列表顺序就是补偿的<b>逆序</b>依据，所以它必须是确定的：
     * 并行分支上的两个活动可能同一毫秒完成，用时间戳排会让补偿次序变成随机的，
     * 而"后做的先撤"是补偿唯一不能错的地方（先退款再退订与反过来，
     * 前者会让钱白退一次）。
     *
     * <p>与 {@link #terminateScope} 同一套形状：<b>引擎只登记意图，运行期服务落库</b> ——
     * {@code WfEngine} 没有持久化。
     */
    private final java.util.List<String> pendingCompensations = new java.util.ArrayList<>();

    /**
     * 本次推进登记的补偿的<b>作用域</b>（内联容器 id，空串为进程级）。
     *
     * <p>按<b>每个活动各记各的</b>而不是全局一个：内层的补偿不能撤掉外层已完成的步骤，
     * 而这两个作用域的 token 在同一时刻都还活着。
     */
    private final java.util.Map<String, String> compensationScopes =
            new java.util.LinkedHashMap<>();

    /**
     * 本次推进起始时已有的补偿登记条数 —— 用来给新登记的条目算续号。
     *
     * <p>不能每条都用 {@code 1,2,3}：实例跑过几轮之后表里已经有几十条，
     * 续号必须接着往下走，否则同一实例里两次登记会拿到同一个 seq，
     * 而逆序补偿靠 seq 定序，同 seq 就是随机的。
     */
    private long compensationSeqBase;

    /** 是否正在执行补偿。补偿期间的活动完成<b>不再</b>登记（否则会自我触发）。 */
    private boolean inCompensation;

    /**
     * 登记一条补偿。
     *
     * @param activityId 刚完成且可补偿的活动
     * @param scope      该活动所属的内联容器 id，空串表示进程级
     */
    public void addPendingCompensation(String activityId, String scope) {
        if (activityId == null || activityId.trim().isEmpty()) {
            return;
        }
        if (!pendingCompensations.contains(activityId)) {
            pendingCompensations.add(activityId);
        }
        compensationScopes.put(activityId, scope == null ? "" : scope);
    }

    public java.util.List<String> getPendingCompensations() {
        return pendingCompensations;
    }

    public String getCompensationScopeOf(String activityId) {
        String scope = compensationScopes.get(activityId);
        return scope == null ? "" : scope;
    }

    public long getCompensationSeqBase() {
        return compensationSeqBase;
    }

    public void setCompensationSeqBase(long compensationSeqBase) {
        this.compensationSeqBase = compensationSeqBase;
    }

    public boolean isInCompensation() {
        return inCompensation;
    }

    public void setInCompensation(boolean inCompensation) {
        this.inCompensation = inCompensation;
    }

    public java.util.List<WfJob> getCreatedJobs() {        return createdJobs;
    }

    public void addCreatedJob(WfJob job) {
        if (job != null) {
            createdJobs.add(job);
        }
    }

    public java.util.List<String> getJobsToClearByExecution() {
        return jobsToClearByExecution;
    }

    public void clearJobsOf(String executionId) {
        if (executionId != null) {
            jobsToClearByExecution.add(executionId);
        }
    }

    /**
     * 登记一条待投递的抛事件。
     *
     * <p>登记而不是当场投递，理由写在 {@link WfPendingEvent} 的类注释里：
     * behavior 处在单实例事务内部，当场投会被外层的回写覆盖掉（丢更新，且不报错）。
     */
    public void addPendingEvent(WfPendingEvent event) {
        if (event != null) {
            pendingEvents.add(event);
        }
    }
    public List<WfPendingEvent> getPendingEvents() {
        return pendingEvents;
    }

    /**
     * token 刚进入某个节点，为挂在这个节点上的每个定时器边界起一个 job。
     *
     * <p><b>起算点是"进入本节点这一刻"而不是流程启动时刻</b>：
     * 超时提醒问的是"这一步停了多久"，不是"这单办了多久"。
     * 用流程启动时刻的话，一张走了三天的单会在进入审批的同一秒就超时。
     *
     * <p>起算点取 {@code enteredTime}：{@code WfEngine.enter} 在调用本方法之前
     * 已经把它设成当前时刻，所以并行分支上同一个节点的不同 token 各自起表、互不干扰。
     */
    public void startTimerJobs(java.util.Collection<WfNode> boundaries) {
        startTimerJobs(boundaries, null);
    }

    /**
     * 只为当前 token 起满足 {@code filter} 的那些边界。
     *
     * @param filter 只起满足条件的边界；{@code null} 表示全起
     */
    public void startTimerJobs(java.util.Collection<WfNode> boundaries,
                               java.util.function.Predicate<WfNode> filter) {
        startTimerJobs(getCurrentExecution(), boundaries, filter);
    }

    /**
     * 为<b>指定的一条</b> token 起定时器边界，只起满足 {@code filter} 的那些。
     *
     * <p>{@code parallelMultiple="true"} 的边界必须走这里：它们的表是
     * <b>每个实例一条</b>，而 {@link #startTimerJobs} 绑的是"当前 token"
     * （进入节点时是<b>第一条</b>实例那条）。若让它们走默认那条路，
     * 三个实例会共用一条表 —— 触发其中一个就等于同时回答了三个实例，
     * 实际仍然是"整个活动一个边界事件"，恰好是 {@code parallelMultiple="false"} 的语义。
     *
     * @param filter 只起满足条件的边界；{@code null} 表示全起
     */
    public void startTimerJobsFor(WfExecution execution,
                                  java.util.Collection<WfNode> boundaries,
                                  java.util.function.Predicate<WfNode> filter) {
        startTimerJobs(execution, boundaries, filter);
    }

    private void startTimerJobs(WfExecution execution, java.util.Collection<WfNode> boundaries,
                                java.util.function.Predicate<WfNode> filter) {
        if (execution == null || boundaries == null || boundaries.isEmpty()) {
            return;
        }
        Date base = execution.getEnteredTime() != null ? execution.getEnteredTime() : new Date();
        for (WfNode boundary : boundaries) {
            if (filter != null && !filter.test(boundary)) {
                continue;
            }
            WfJob job = new WfJob();
            job.setProcessInstanceId(getProcessInstanceId());
            job.setExecutionId(execution.getId());
            job.setElementId(boundary.getId());
            job.setAttachedToRef(boundary.getAttachedToRef());
            job.setCreateTime(new Date());
            job.setRetries(WfJob.DEFAULT_RETRIES);
            if (boundary.isMessageBoundary() || boundary.isSignalBoundary()
                    || boundary.isEscalationEvent()) {
                // 订阅型：没有触发时刻，duedate 留空。
                // 名字记在 subscriptionName 而不是 exceptionMessage ——
                // 后者要和"失败原因"共用，而排障视图需要同时看到这两个值（见 WfJob#subscriptionName）。
                // 升级用**自己的** job 类型而不是并进 SIGNAL：投递方要靠类型区分
                // 「叫醒一条分支」与「打断宿主（待办作废）」这两种后果完全不同的动作
                job.setType(boundary.isEscalationEvent() ? WfJobType.ESCALATION
                        : boundary.isSignalBoundary()
                        ? WfJobType.SIGNAL
                        : WfJobType.MESSAGE);
                job.setDuedate(null);
                job.setSubscriptionName(boundary.isEscalationEvent()
                        ? boundary.getEscalationCode()
                        : boundary.isSignalBoundary()
                        ? boundary.getSignalName() : boundary.getMessageName());
                createdJobs.add(job);
                continue;
            }
            // 定时器算不出触发时刻就直接抛：建一个永远不响的哑定时器，
            // 比启动失败危险得多 —— 它表现为"超时提醒一直没来"，没人查得到根因。
            // 底层抛 IllegalArgumentException（它是入参问题），这里转成引擎的
            // 对外异常，让调用方与其它启动失败拿到同一种处理方式。
            try {
                job.setDuedate(WfTimerSupport.resolveDueDate(boundary.getTimerType(),
                        boundary.getTimerExpression(), base, mergedVariables()));
            } catch (IllegalArgumentException e) {
                throw new WfEngineException("边界事件 " + boundary.getId()
                        + " 的定时器算不出触发时刻: " + e.getMessage(), e);
            }
            if (boundary.getTimerType() == WfTimerType.CYCLE) {
                // 循环定时器的初始那条就是**第 1 次**：cycleIndex 记的是
                // 「这是第几次触发」（1 起）而不是「已经响过几次」——
                // 后者会差出一个 1，而差的这一下会让 R3 照响第 4 次
                job.setCycleIndex(1);
            }
            createdJobs.add(job);
        }
    }

    /** 本次推进产生的结果标记（endEvent 的 resultExpression 求值结果）。 */
    private String processResult;

    /** 本次推进的触发人。 */
    private String authenticatedUserId;

    /**
     * 本次推进处于异步 job 的续跑状态；{@link #NONE} 表示是一次普通的流程推进。
     *
     * <p>它必须区分方向，因为两个方向要绕开的东西不同：
     * <ul>
     *   <li>{@link #ENTER}（异步前置续跑）—— 绕开"再挂一个异步前置 job"，
     *       否则每续跑一次排一次单，执行器无限循环、流程永远不动；</li>
     *   <li>{@link #LEAVE}（异步后置续跑）—— 除了同样要绕开"再挂一个异步后置 job"，
     *       还要绕开<b>重复记一条节点历史</b>：节点在那次正常的 leave 里已经记过了，
     *       续跑再记一遍的话轨迹上会出现两次"审批"，而"一次节点访问一条"是硬约定。</li>
     * </ul>
     *
     * <p>它是一次性的（每次续跑重新构造 context），所以放 context 上就够，
     * 不必进 {@code WfExecution} 加列。
     */
    public enum Resume {
        /** 普通推进。 */
        NONE,
        /** 异步前置的续跑：正在进入尚未执行的节点。 */
        ENTER,
        /** 异步后置的续跑：正在离开已经执行完的节点。 */
        LEAVE
    }

    private Resume resume = Resume.NONE;

    /**
     * 本次推进的钩子分发器；为 null 表示没有装配任何钩子。
     *
     * <p><b>为什么挂在 context 上而不是注入 engine</b>：钩子触发属于"本次推进"
     * 的过程，而 dispatcher 是宿主共享的编排组件。挂 context 就不用改
     * {@code WfEngine} 的构造签名 —— 那会让每一个直接 new 引擎的测试都要跟着改，
     * 而它们与钩子毫无关系。
     *
     * <p>允许为 null：core 层的单元测试大量直接用引擎，强制注入会让它们
     * 为一个与被测行为无关的依赖付出构造代价。触发点一律判空。
     */
    private com.zifang.z.wf.core.hook.WfHookDispatcher hookDispatcher;

    public com.zifang.z.wf.core.hook.WfHookDispatcher getHookDispatcher() {
        return hookDispatcher;
    }

    public void setHookDispatcher(
            com.zifang.z.wf.core.hook.WfHookDispatcher hookDispatcher) {
        this.hookDispatcher = hookDispatcher;
    }

    /** 触发流转钩子；没装配钩子时静默跳过。 */
    public void fireTransition(String fromActivityId, String toActivityId, String flowId) {
        if (hookDispatcher == null || getDefinition() == null) {
            return;
        }
        hookDispatcher.fireTransition(getDefinition().getKey(), getProcessInstanceId(),
                fromActivityId, toActivityId, flowId);
    }

    public Resume getResume() {
        return resume;
    }

    public void setResume(Resume resume) {
        this.resume = resume == null ? Resume.NONE : resume;
    }

    /** 本次推进是不是异步 job 的续跑。 */
    public boolean isAsyncContinuation() {
        return this.resume != Resume.NONE;
    }

    /** 引擎异常信息（内部终止时填充）。 */
    private String failureMessage;

    /** 条件/脚本表达式求值器（由 WfEngine 注入）。 */
    private WfExpressionEvaluator expressionEvaluator;

    /** delegate 解析器（由 WfEngine 注入）。 */
    private WfDelegateRegistry delegateRegistry;

    /** 子流程启动器（由 WfRuntimeService 注入；为 null 时 callActivity 空转）。 */
    private WfSubProcessLauncher subProcessLauncher;

    /**
     * 决策服务（由 WfEngine 注入；为 null 时业务规则任务报错而不是 NPE）。
     *
     * <p>刻意<b>可为空</b>：绝大多数部署里没人用 DMN，
     * 让 engine 的构造器必须收一个决策服务，会把"没用这条能力"也变成接线负担。
     * 为空时业务规则任务报一句说清是<b>接线漏了</b>的话，
     * 比在引擎里 new 一个空壳服务、让所有决策都返回"找不到"要好。
     */
    private com.zifang.z.wf.core.service.WfDecisionService decisionService;

    /**
     * 本流程实例的<b>全部</b> token 视图。
     *
     * <p>并行/包容网关的汇合判定需要看"所有兄弟 token 到哪了"，
     * 光看本次新建的 token 不够（兄弟可能是在上一次请求里建的）。
     * 因此 runtime service 在推进前把实例的全部 token 装进来。
     */
    private List<WfExecution> processExecutions = new ArrayList<>();

    /**
     * 本次推进中被修改过的既有 token（需要在推进后重新落库）。
     */
    private final List<WfExecution> touchedExecutions = new ArrayList<>();

    /** 本次推进里 intermediateThrowEvent 登记的待投递事件。 */
    private final List<WfPendingEvent> pendingEvents = new ArrayList<>();

    /**
     * 装入本实例全部 token（供汇合判定）。
     */
    public void setProcessExecutions(List<WfExecution> executions) {
        this.processExecutions = executions == null ? new ArrayList<WfExecution>() : executions;
    }

    public List<WfExecution> getProcessExecutions() {
        return processExecutions;
    }

    /**
     * 登记一个被修改的既有 token，使其在推进后被重新持久化。
     */
    public void markTouched(WfExecution execution) {
        if (execution != null && !touchedExecutions.contains(execution)) {
            touchedExecutions.add(execution);
        }
    }

    public List<WfExecution> getTouchedExecutions() {
        return touchedExecutions;
    }

    public WfExpressionEvaluator getExpressionEvaluator() {
        return expressionEvaluator;
    }

    public void setExpressionEvaluator(WfExpressionEvaluator expressionEvaluator) {
        this.expressionEvaluator = expressionEvaluator;
    }

    public WfDelegateRegistry getDelegateRegistry() {
        return delegateRegistry;
    }

    public void setDelegateRegistry(WfDelegateRegistry delegateRegistry) {
        this.delegateRegistry = delegateRegistry;
    }

    public WfSubProcessLauncher getSubProcessLauncher() {
        return subProcessLauncher;
    }

    public void setSubProcessLauncher(WfSubProcessLauncher subProcessLauncher) {
        this.subProcessLauncher = subProcessLauncher;
    }

    public com.zifang.z.wf.core.service.WfDecisionService getDecisionService() {
        return decisionService;
    }

    public void setDecisionService(com.zifang.z.wf.core.service.WfDecisionService decisionService) {
        this.decisionService = decisionService;
    }

    public WfContext(WfDefinition definition, WfProcessInstance processInstance, WfExecution currentExecution) {
        this.definition = definition;
        this.processInstance = processInstance;
        this.currentExecution = currentExecution;
    }

    public WfDefinition getDefinition() {
        return definition;
    }

    public WfProcessInstance getProcessInstance() {
        return processInstance;
    }

    public WfExecution getCurrentExecution() {
        return currentExecution;
    }

    public void setCurrentExecution(WfExecution currentExecution) {
        this.currentExecution = currentExecution;
    }

    public String getProcessInstanceId() {
        return processInstance == null ? null : processInstance.getId();
    }

    public String getAuthenticatedUserId() {
        return authenticatedUserId;
    }

    public void setAuthenticatedUserId(String authenticatedUserId) {
        this.authenticatedUserId = authenticatedUserId;
    }

    public java.util.List<WfExecution> getNewExecutions() {
        return newExecutions;
    }

    public java.util.List<WfTask> getCreatedTasks() {
        return createdTasks;
    }

    public java.util.List<WfActivityInstance> getActivityHistory() {
        return activityHistory;
    }

    public String getProcessResult() {
        return processResult;
    }

    public void setProcessResult(String processResult) {
        this.processResult = processResult;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    public void addNewExecution(WfExecution execution) {
        if (execution != null) {
            newExecutions.add(execution);
        }
    }

    public void addCreatedTask(WfTask task) {
        if (task != null) {
            createdTasks.add(task);
        }
    }

    public void addActivityHistory(WfActivityInstance instance) {
        if (instance != null) {
            activityHistory.add(instance);
        }
    }

    /**
     * 读取变量：先 token 局部，再流程级。
     */
    public Object getVariable(String name) {
        if (name == null) {
            return null;
        }
        if (currentExecution != null) {
            Object local = currentExecution.getVariables().get(name);
            if (local != null) {
                return local;
            }
        }
        return processInstance == null ? null : processInstance.getVariables().get(name);
    }

    /**
     * 写流程级变量（全流程可见）。
     */
    public void setVariable(String name, Object value) {
        if (name == null || processInstance == null) {
            return;
        }
        if (value == null) {
            processInstance.getVariables().remove(name);
        } else {
            processInstance.getVariables().put(name, value);
        }
    }

    /**
     * 写 token 局部变量（仅本分支可见）。
     */
    public void setLocalVariable(String name, Object value) {
        if (name == null || currentExecution == null) {
            return;
        }
        if (value == null) {
            currentExecution.getVariables().remove(name);
        } else {
            currentExecution.getVariables().put(name, value);
        }
    }

    /**
     * 批量写流程级变量。
     */
    public void setVariables(Map<String, Object> variables) {
        if (variables == null || processInstance == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            setVariable(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 合并视图：流程级变量 + token 局部变量（局部覆盖流程级）。
     * <p>给条件求值与 history 快照用。
     */
    public Map<String, Object> mergedVariables() {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (processInstance != null) {
            merged.putAll(processInstance.getVariables());
        }
        if (currentExecution != null) {
            merged.putAll(currentExecution.getVariables());
        }
        return merged;
    }

    /**
     * 本次节点访问的结论（通常是审批意见）。
     *
     * <p>存在的理由：活动历史此前有<b>三个写入点</b>（引擎的 enter / leave、
     * service 的 recordActivityComplete），而 leave 并不掌握审批意见 ——
     * 于是同一步骤被记了三条，且其中两条的 {@code assignee} 取的是
     * {@code authenticatedUserId}。在"进入审批"那一刻那个值还是<b>发起人</b>，
     * 轨迹上就会显示"alice 开始了审批"，而实际办理人是别人。
     *
     * <p>现在收敛成：{@code completeTask} 把意见放进这里，
     * {@code leave()} 一次性写出唯一一条记录。
     */
    private String pendingActivityOutcome;

    public void setPendingActivityOutcome(String pendingActivityOutcome) {
        this.pendingActivityOutcome = pendingActivityOutcome;
    }

    /** 取走结论；取走后清空，避免下一次节点访问误用上一次的意见。 */
    public String consumePendingActivityOutcome() {
        String value = pendingActivityOutcome;
        pendingActivityOutcome = null;
        return value;
    }

    /**
     * 构造一条活动历史并登记。
     */
    public WfActivityInstance recordActivity(String activityId, String activityName,
                                              String activityType, String outcome) {
        WfActivityInstance instance =
                new WfActivityInstance();
        instance.setProcessInstanceId(getProcessInstanceId());
        instance.setActivityId(activityId);
        instance.setActivityName(activityName);
        instance.setActivityType(activityType);
        instance.setOutcome(outcome);
        instance.setStartTime(new Date());
        instance.setEndTime(new Date());
        instance.setAssignee(authenticatedUserId);
        instance.setExecutionId(currentExecution == null ? null : currentExecution.getId());
        if (processInstance != null) {
            instance.setProcessDefinitionKey(processInstance.getDefinitionKey());
        }
        addActivityHistory(instance);
        return instance;
    }
}
