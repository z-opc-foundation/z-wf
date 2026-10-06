package com.zifang.z.wf.core.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfFlow;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfTimerSupport;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.engine.expression.WfExpressionEvaluator;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.service.WfDelegateRegistry;

/**
 * z-wf 执行引擎 —— token 推进器。
 *
 * <p><b>引擎只做"图语义"，不做"业务语义"</b>：决定 token 从哪来、去哪、什么时候结束；
 * 节点具体怎么执行委托给 {@link WfBehaviorRegistry} 里的
 * {@link com.zifang.z.wf.core.engine.behavior.WfActivityBehavior}。
 *
 * <p><b>核心模型：enter / leave 分离。</b>
 * 这是本引擎最容易写错、也最关键的一处结构。必须严格区分两件事：
 * <ul>
 *   <li><b>{@code leave(token)}</b>：token <b>离开</b>当前节点，沿出线前进。
 *       用于"用户任务刚办结"这类场景 —— 任务节点已经执行过了，不能再执行一次。</li>
 *   <li><b>{@code enter(token)}</b>：token <b>进入</b>当前节点，执行该节点的行为。
 *       用于"刚抵达一个新节点"。</li>
 * </ul>
 * 早期版本把两者合成一个 {@code advance}，导致办结用户任务后又把同一个任务重建了一遍
 * （"审批一次生成两个待办"）。拆分后语义无歧义：
 * {@link #start} = 建根 token 在开始节点 + {@code enter}；{@link #advance} = {@code leave}。
 *
 * <p><b>并行网关的 token 语义</b>：fork 出 N 个出线时生成 N 个<b>独立 token</b>，
 * 每个 token 各自走到自己的分支终点。汇合网关判定"所有兄弟 token 都已抵达"后才继续，
 * 抵达后把兄弟 token 一并结束（收敛成一棵树而非一片散沙）。
 *
 * <p>线程安全：引擎自身无状态，可多线程共享；状态都在 {@link WfContext} 与 model 里。
 *
 * @author zifang
 */
public class WfEngine {

    private static final Logger log = LoggerFactory.getLogger(WfEngine.class);

    /** 单次推进的最大递归深度（防流程图成环导致栈溢出）。 */
    public static final int MAX_DEPTH = 512;

    /**
     * 多实例展开上限。
     *
     * <p>{@code loopCardinality} 可以是表达式，理论上能求值出很大的数。
     * 不设上限的话 {@code ${99999999}} 会一次性造出上亿个 token 与任务，
     * 把内存和数据库同时打满——而这只是设计器里的一次手滑。
     */
    public static final int MAX_LOOP_INSTANCES = 200;

    private final WfBehaviorRegistry behaviorRegistry;

    private final WfExpressionEvaluator expressionEvaluator;

    private final WfIdGenerator idGenerator;

    private final WfDelegateRegistry delegateRegistry;

    public WfEngine() {
        this(new WfBehaviorRegistry(), new WfExpressionEvaluator(),
                new WfIdGenerator.DefaultWfIdGenerator(), new WfDelegateRegistry());
    }

    public WfEngine(WfBehaviorRegistry behaviorRegistry,
                    WfExpressionEvaluator expressionEvaluator,
                    WfIdGenerator idGenerator) {
        this(behaviorRegistry, expressionEvaluator, idGenerator, new WfDelegateRegistry());
    }

    public WfEngine(WfBehaviorRegistry behaviorRegistry,
                    WfExpressionEvaluator expressionEvaluator,
                    WfIdGenerator idGenerator,
                    WfDelegateRegistry delegateRegistry) {
        this.behaviorRegistry = behaviorRegistry;
        this.expressionEvaluator = expressionEvaluator;
        this.idGenerator = idGenerator;
        this.delegateRegistry = delegateRegistry;
    }

    /**
     * 接上决策服务（业务规则任务用它求值 DMN）。
     *
     * <p>做成 setter 而不是构造参数：引擎有四个构造器、三处 {@code new WfEngine()}，
     * 加参数会让每一处都得改，而<b>绝大多数部署里没人用 DMN</b> ——
     * 让它们为了不用的一条能力去构造一个决策服务，是一种强加的接线负担。
     * {@link WfBehaviorRegistry#register} 是同样的形状：按需覆盖，不进构造器。
     */
    public WfEngine withDecisionService(com.zifang.z.wf.core.service.WfDecisionService decisionService) {
        this.decisionService = decisionService;
        return this;
    }

    public com.zifang.z.wf.core.service.WfDecisionService getDecisionService() {
        return decisionService;
    }

    /** 业务规则任务用的决策服务；为 null 时该节点报「接线漏了」而不是 NPE。 */
    private com.zifang.z.wf.core.service.WfDecisionService decisionService;

    /**
     * 构造带协作组件的上下文。
     */
    public WfContext newContext(WfDefinition definition, WfProcessInstance instance,
                                WfExecution execution) {
        WfContext context = new WfContext(definition, instance, execution);
        context.setExpressionEvaluator(expressionEvaluator);
        context.setDelegateRegistry(delegateRegistry);
        context.setDecisionService(decisionService);
        return context;
    }

    public WfDelegateRegistry getDelegateRegistry() {
        return delegateRegistry;
    }

    public WfIdGenerator getIdGenerator() {
        return idGenerator;
    }

    public WfExpressionEvaluator getExpressionEvaluator() {
        return expressionEvaluator;
    }

    public WfBehaviorRegistry getBehaviorRegistry() {
        return behaviorRegistry;
    }

    // ==================== 启动 ====================

    /**
     * 启动流程：根 token 落在开始节点，然后 <b>enter</b>。
     */
    public void start(WfContext context) {
        startAt(context, context.getDefinition().startNode());
    }

    /**
     * 从指定起始节点启动（{@code startProcessInstanceByMessage} / {@code BySignal} 用）。
     *
     * <p>与 {@link #start} 的区别只有一个参数，语义却差很远：{@code start} 的入口是
     * "这个流程无条件开始"，而消息启动的入口是"收到了 X 才开始" ——
     * 两者落到同一段代码，但只有拆开写，调用方才不能误把消息起始当成无条件入口
     * （{@code WfDefinition#startNode} 已经把带触发条件的那个排除掉了）。
     *
     * <p><b>起始事件本身不进 enter</b>：BPMN 里 startEvent 是流程的入口，
     * 不是一步要执行的活动。{@link #enter} 对 startEvent 直接放行到它的出线，
     * 根 token 落在 startEvent 上而活动历史从它的下一个节点开始记。
     */
    public void startAt(WfContext context, WfNode startNode) {
        WfDefinition definition = context.getDefinition();

        WfExecution root = new WfExecution(idGenerator.nextExecutionId(),
                context.getProcessInstanceId(), startNode.getId());
        root.setChild(false);
        context.setCurrentExecution(root);
        context.addNewExecution(root);
        context.getProcessInstance().setStatus(WfProcessStatus.ACTIVE);

        log.info("流程启动: processInstanceId={}, definition={}, startNode={}",
                context.getProcessInstanceId(), definition.getKey(), startNode.getId());

        enter(context, 0);
    }

    /**
     * 推进 token —— <b>离开</b>当前节点沿出线前进。
     *
     * <p>调用时机：用户任务办结后、receiveTask 被消息触发后、挂起后恢复时。
     * 此时 token 停在"已经执行过的节点"上，所以走 leave 而不是 enter。
     */
    /**
     * 从指定 token 的当前活动开始推进（不重新 arriveAt）。
     *
     * <p>与 {@link #advance} 的区别：{@code advance} 会让 token 重新进入当前活动
     * （用于"任务办结后再走一次"），而这里调用方已经把 token 摆到了目标活动上
     * （错误路由把 token 移到了边界事件），需要的是"从这里出发"。
     * 调 advance 会让边界事件被当成刚进入的任务节点跑一遍，语义不对。
     */
    public void startFrom(WfContext context, WfExecution token) {
        WfDefinition definition = context.getDefinition();
        WfNode node = definition.node(token.getActivityId());
        if (node == null) {
            fail(context, "流程定义 " + definition.getKey()
                    + " 中找不到节点: " + token.getActivityId());
            return;
        }
        WfExecution saved = context.getCurrentExecution();
        context.setCurrentExecution(token);
        try {
            leave(context, 0);
        } finally {
            context.setCurrentExecution(saved);
        }
    }

    public void advance(WfContext context) {
        leave(context, 0);
    }

    /**
     * 进入 token 当前所在的节点并执行它 —— 语义是「token 刚到达这个节点」。
     *
     * <p><b>与 {@link #advance} / {@link #startFrom} 的区别就在这一个词</b>：
     * 那两个都走 {@code leave}，假设这个节点<b>已经执行过了</b>（任务办结后、
     * 边界事件把 token 挪过去之后）。用它进入一个从没跑过的节点，
     * 结果是直接沿它的出线跳过去 —— 症状是"迁到人工节点却没有待办，
     * 流程反而一下就走完了"。
     *
     * <p>供实例迁移（{@code WfRuntimeService#move}）使用。
     * 刻意<b>不</b>复用 {@link #resumeEnter}：那个会打上 asyncContinuation 标记，
     * 而迁移进去的目标节点如果自己带 {@code asyncBefore}，那个 job 是<b>该</b>排的 ——
     * 续跑的语义是"补跑一个已经排过队的节点"，迁移的语义是"第一次到达它"。
     */
    public void enterAt(WfContext context, WfExecution token) {
        WfExecution saved = context.getCurrentExecution();
        context.setCurrentExecution(token);
        try {
            enter(context, 0);
        } finally {
            context.setCurrentExecution(saved);
        }
    }

    // ==================== enter：进入节点 ====================

    /**
     * 进入 token 当前所在的节点并执行其行为。
     */
    private void enter(WfContext context, int depth) {
        if (depth > MAX_DEPTH) {
            fail(context, "流程推进深度超过 " + MAX_DEPTH + "（疑似流程图成环且条件恒成立）");
            return;
        }
        WfExecution token = context.getCurrentExecution();
        if (token == null || token.isEnded()) {
            return;
        }

        WfDefinition definition = context.getDefinition();
        WfNode node = definition.node(token.getActivityId());
        if (node == null) {
            fail(context, "流程定义 " + definition.getKey()
                    + " 中找不到节点: " + token.getActivityId());
            return;
        }

        token.setState(WfExecution.State.ACTIVE);
        token.arriveAt(node.getId());
        // 不再在这里记 "entered"：那个时点的 authenticatedUserId 是"谁触发了进入"，
        // 对审批节点而言通常是发起人而不是办理人，记下来轨迹上就会显示错人。
        // 一次节点访问只应当在 leave 时记一条（见 leave）。

        // ---- 定时器边界：token 一进入宿主节点就起表 ----
        // 必须在"停留"类节点之前：定时器测的就是这一步停多久。
        // 结束事件不会挂定时器边界（挂上去也没有"停留"可言），
        // 但放在一起读起来更顺 —— 起表与节点是否真的停下无关，交给 job 自己去判断。
        context.startTimerJobs(definition.eventBoundariesOf(node.getId()));

        // ---- 异步前置：节点还没执行，先挂起 ----
        // 位置在 arriveAt 之后、任何业务行为之前：asyncBefore 的定义就是
        // "进入这一步之前先排一次队"，所以定时器边界（也要在停留类节点之前起表）
        // 排在它前面，节点类型分支一律排在它后面。
        //
        // asyncContinuation 是必须的：续跑动作正是"进入这个节点"，
        // 不切断的话每续跑一次就再挂一个 job，执行器无限循环，流程永远不动。
        if (node.isAsyncBefore() && !context.isAsyncContinuation()) {
            context.addCreatedJob(asyncJob(context, node, token,
                    com.zifang.z.wf.core.model.WfJobType.ASYNC_BEFORE));
            token.setState(WfExecution.State.WAITING);
            return;
        }

        // ---- 结束事件 ----
        if (node.getType() == WfNodeType.END_EVENT) {
            // 内联在 subProcess 里的 endEvent 不是"流程结束"，而是"子流程到此为止"：
            // token 要回到那个 subProcess 节点上，由它沿自己的出线继续走主图。
            // 少这一道分派，内联子流程的 token 就会在子流程内部直接结束，
            // 现象是"subProcess 之后的节点一个都没跑，而实例状态是 COMPLETED"。
            if (definition.isInline(node)) {
                leaveSubProcess(context, node, token, depth);
                return;
            }
            evaluateResult(context, node);
            // 结束节点不走 leave，所以在这里补上唯一那条记录
            WfActivityInstance end = context.recordActivity(node.getId(), node.getName(),
                    node.getType().bpmnName(),
                    context.getProcessResult() == null ? "completed" : context.getProcessResult());
            // 结束不是任何一个人的动作。留着手办人会污染"某人办过哪些单"：
            // 查 ceo 会把每一条恰好由他办结的流程的结束节点也一并捞出来。
            // Camunda 的 endEvent 历史同样是 USER_ID 为空。
            end.setAssignee(null);
            token.setState(WfExecution.State.ENDED);
            return;
        }

        // ---- 嵌入式子流程：把 token 推进内联起始节点 ----
        // 排在 END_EVENT 之后、网关之前：subProcess 既不是结束也不是网关。
        //
        // 只对"真的画了内联内容"的 subProcess 生效（isInlineSubProcess）。空容器配
        // calledElementKey 的那种在 BPMN 里等价于 callActivity，仍走
        // WfCallActivityBehavior —— 两者共用同一个枚举值，靠"容器里有没有节点"区分。
        if (definition.isInlineSubProcess(node)) {
            enterSubProcess(context, node, token, depth);
            return;
        }

        // ---- 网关 ----
        if (node.getType().isGateway()) {
            handleGateway(context, node, token, depth);
            return;
        }

        // ---- 中间捕获事件：停下挂订阅，不建人工待办 ----
        //
        // 必须排在 createsTask() 之前，而它自己 createsTask() 为 false ——
        // 两道闸缺一不可：这里不 return 的话会掉进"其他节点"分支，
        // 而 WfBehaviorRegistry 里没有它的行为（它不执行任何动作，只等），
        // 拿到的是 null 行为；反过来若把它算进 createsTask()，
        // 行为返回的 null 会被当成"决定不建任务直接通过"，流程当场穿过去。
        if (node.getType() == WfNodeType.INTERMEDIATE_CATCH_EVENT) {
            com.zifang.z.wf.core.model.WfJob catchJob = eventCatchJob(context, node, token);
            if (catchJob == null) {
                // eventCatchJob 已经把失败原因写进 context 并把流程标成内部终止
                return;
            }
            context.addCreatedJob(catchJob);
            token.setState(WfExecution.State.WAITING);
            return;
        }

        // ---- 外部任务：停下挂 job，不在引擎里执行 ----
        // 必须在 createsTask 之前：它是 serviceTask，不建 WfTask，而是等外部 worker 领走。
        //
        // 这里不需要区分"首次进入"与"外部完成后重入"：completeExternalTask 走的是
        // startFrom，而 startFrom 调 leave —— 它让 token 离开当前节点走向出线，
        // 不会重新 enter 这个节点，所以挂 job 的分支只在流程首次流经它时才会执行。
        // （曾在这里加过一个 externalResult 标记来防"每次 complete 又挂一个新 job"，
        //  探针实测证明那个场景不会发生：该标记在当前 startFrom 语义下恒为死代码。）
        if (node.isExternalStep()) {
            context.addCreatedJob(externalJob(context, node, token));
            token.setState(WfExecution.State.WAITING);
            return;
        }

        // ---- 任务类节点：执行行为创建任务，然后挂起 ----
        if (node.getType().createsTask()) {
            if (node.isMultiInstance()) {
                enterMultiInstance(context, node, token);
                return;
            }
            try {
                WfTask task = behaviorRegistry.getBehavior(node.getType())
                        .execute(context, node, token);
                if (task != null) {
                    context.addCreatedTask(task);
                    token.setState(WfExecution.State.WAITING);
                }
                // task == null 表示行为方决定"不建任务直接通过"（如 force-complete 后的继续）
            } catch (Exception e) {
                fail(context, "创建任务失败: " + node.getId() + " - " + e.getMessage());
            }
            return;
        }

        // ---- 其他节点：执行行为后离开 ----
        try {
            WfTask stray = behaviorRegistry.getBehavior(node.getType())
                    .execute(context, node, token);
            if (stray != null) {
                // 穿透型节点却建出了任务：这个任务既不会入库、token 也不会停，
                // 等于凭空消失，而流程照常往下跑、轨迹照常记"completed"——
                // 从外面看一切正常。所以这里必须炸，不能只记一条日志。
                // （真实踩过的坑：receiveTask 漏在 createsTask() 之外，
                //   WfReceiveTaskBehavior 建的任务被这一行原样丢弃，流程直接穿到结束。）
                fail(context, "节点 " + node.getId() + "（" + node.getType().bpmnName()
                        + "）的行为创建了任务，但该类型不是等待态，任务无法登记。"
                        + "若本节点应等待外部动作，请把它加进 WfNodeType#createsTask()");
                return;
            }
        } catch (Exception e) {
            fail(context, "节点执行失败: " + node.getId() + " - " + e.getMessage());
            return;
        }
        leave(context, depth + 1);
    }

    /**
     * 为中间捕获事件建一条"等消息 / 等信号 / 等到点"的 job。
     *
     * <p>{@code elementId} 记的是<b>捕获事件本身</b>而不是网关：事件到达时要能
     * 精确唤醒"停在这一格上的那一条 token"，而不是"某个网关上的某条 token"。
     * 竞速的粒度就在这一格上。
     *
     * <p>网关 id <b>不存</b>，触发时从流程定义里查出来：捕获事件的入线只有一条，
     * 源头就是网关。存一份反而多出一个可能与定义不一致的副本。
     *
     * <p><b>定时器分支用 {@code EVENT_TIMER} 而不是复用 {@code TIMER}，且
     * {@code attachedToRef} 留空</b>：边界事件的定时器有"宿主"
     * （{@code attachedToRef} 指向被挂的那个节点），网关分支的定时器没有 ——
     * 它自己就是那一格。类型要分开则是因为"哪些是竞速型 job"必须是一份能直接
     * 列举的清单：落选分支的清理（{@code deleteCatchJobsOf}）按类型白名单删，
     * 两种形态共用一个类型就得多一步"再按节点类型反推是不是竞速"，
     * 而漏掉一次的症状是流程被静默地多推一遍。
     * 到期时按 <b>job 类型</b>分派（见 {@code WfRuntimeService#fireTimer}），
     * 节点类型只留作交叉校验。
     */
    private com.zifang.z.wf.core.model.WfJob eventCatchJob(WfContext context, WfNode node,
                                                            WfExecution token) {
        boolean timerBranch = node.isTimerEvent();
        if (!node.isSupportedGatewayBranch()
                || (timerBranch && !context.getDefinition().isEventGatewayBranch(node))) {
            // 走到这里说明部署期校验被绕过了（自定义装配、或直接调引擎不经过部署）。
            // 挂一个永远不会被触发的哑订阅，等于让流程停在那里且无人报错 ——
            // 与其那样，不如把流程停成"内部终止 + 写明原因"，让人一眼看出卡在哪。
            fail(context, "中间捕获事件 " + node.getId() + " 不是本引擎等得住的事件网关分支"
                    + "（事件网关分支支持 messageEventDefinition / signalEventDefinition / "
                    + "timerEventDefinition，独立的捕获事件只支持前两种），"
                    + "它在图上是一条永远等不到的死路");
            return null;
        }
        com.zifang.z.wf.core.model.WfJob job = new com.zifang.z.wf.core.model.WfJob();
        job.setProcessInstanceId(context.getProcessInstanceId());
        job.setExecutionId(token.getId());
        job.setElementId(node.getId());
        if (timerBranch) {
            // EVENT_TIMER 而不是 TIMER：竞速与打断共用一个类型时，
            // 落选分支的清理（按类型白名单删 job）就会漏掉这一种
            job.setType(com.zifang.z.wf.core.model.WfJobType.EVENT_TIMER);
            // 算不出触发时刻就直接抛：建一个永远不响的哑定时器比启动失败危险得多 ——
            // 它的症状是"超时提醒一直没来"，没人查得到根因。
            // 变量引用取不到值时按 fail-closed 报错，而不是当成 0 或忽略
            Date base = token.getEnteredTime() != null ? token.getEnteredTime() : new Date();
            try {
                job.setDuedate(WfTimerSupport.resolveDueDate(node.getTimerType(),
                        node.getTimerExpression(), base, context.mergedVariables()));
            } catch (IllegalArgumentException e) {
                // 抛而不是 fail()：与定时器边界那条路（WfContext#startTimerJobs）一致 ——
                // 都是"启动时算不出触发时刻"这类定义/参数问题，直接让这次启动失败。
                // 标记终止的话调用方拿到的是一个"内部终止"的实例，
                // 而真正的原因（哪个变量没值）只出现在引擎日志里
                throw new com.zifang.z.wf.core.service.WfEngineException(
                        "事件网关定时器分支 " + node.getId() + " 算不出触发时刻: " + e.getMessage(), e);
            }
            job.setCreateTime(new java.util.Date());
            job.setRetries(com.zifang.z.wf.core.model.WfJob.DEFAULT_RETRIES);
            return job;
        }
        job.setType(node.isSignalEvent()
                ? com.zifang.z.wf.core.model.WfJobType.EVENT_SIGNAL
                : com.zifang.z.wf.core.model.WfJobType.EVENT_MESSAGE);
        // 订阅型 job 没有触发时刻，duedate 留空：留了会被定时器扫描器当成"到点了"
        job.setDuedate(null);
        job.setSubscriptionName(node.isSignalEvent() ? node.getSignalName() : node.getMessageName());
        job.setCreateTime(new java.util.Date());
        job.setRetries(com.zifang.z.wf.core.model.WfJob.DEFAULT_RETRIES);
        return job;
    }

    /**
     * 为外部任务节点建 job。
     *
     * <p>{@code duedate} 留空：外部任务不由时间触发，留个时间戳只会被扫描器误捞。
     * {@code JOB_TYPE} 标明它是 EXTERNAL，扫描器只捞 TIMER，worker 取活只捞 EXTERNAL。
     */
    private com.zifang.z.wf.core.model.WfJob externalJob(WfContext context, WfNode node,
                                                         WfExecution token) {
        com.zifang.z.wf.core.model.WfJob job = new com.zifang.z.wf.core.model.WfJob();
        job.setProcessInstanceId(context.getProcessInstanceId());
        job.setExecutionId(token.getId());
        job.setElementId(node.getId());
        job.setAttachedToRef(node.getId());
        job.setTopic(node.getTopic());
        job.setType(com.zifang.z.wf.core.model.WfJobType.EXTERNAL);
        job.setDuedate(null);
        job.setCreateTime(new java.util.Date());
        job.setRetries(com.zifang.z.wf.core.model.WfJob.DEFAULT_RETRIES);
        return job;
    }

    /**
     * 进入多实例节点：展开 N 个实例。
     *
     * <p><b>实例数从哪来</b>：{@code loopCardinality}（作者写死的个数）
     * 或 {@code collection}（集合大小），二选一。
     *
     * <p><b>并行与串行是两种形状，不是同一条路的两个参数</b>：
     * <ul>
     *   <li><b>并行</b>（默认）：一次把 N 条实例全建出来，每条一个独立的 token，
     *       各自停在同一节点上、各自建一条任务。之所以用独立 token 而不是
     *       一条 token 记计数：实例之间会真的并行存在（三个人同时在办），
     *       用一条 token 就没法表达"谁办完了谁没办"。</li>
     *   <li><b>串行</b>（{@code isSequential="true"}）：任何时刻只有<b>一条</b>
     *       实例在办，办完一个才建下一个。而这恰恰是"同一条 token 迭代"——
     *       每办结一次就把这条 token 上的循环变量推进一格、再建一条新任务。
     *       复用同一条 token 是有意的：串行下"这个节点上总共几个实例"从任务数
     *       数不出来（同一时刻只有一条任务），所以计划数必须冻在这条 token 上，
     *       而它正是驱动这个循环的那条。</li>
     * </ul>
     *
     * <p>每个 token 带<b>局部</b>变量：{@code loopCounter}（0 起的序号）、
     * 配了 {@code collection} 时的 {@code elementVariable}（元素原值）与
     * {@code loopAssignee}（元素的字符串形式）。
     * 用局部而非流程级，是因为它们属于单个实例——
     * 放进流程变量会互相覆盖，最后一个实例的值会把前面的盖掉。
     *
     * <p>集合元素在<b>分叉时用 Java 取</b>，而不是在表达式里写
     * {@code ${approvers[loopCounter]}}：实测 z-util 的 EL 不支持变量下标
     * （{@code approvers[1]} 可以，{@code approvers[loopCounter]} 抛 ElException）。
     */
    private void enterMultiInstance(WfContext context, WfNode node, WfExecution token) {
        int count = resolveLoopCount(context, node);
        if (count <= 0) {
            // 展开 0 个实例：BPMN 语义是直接完成该节点，往下走。
            // 注意这不是"出错"：collection 指向一个空列表时，作者要的就是"这一步没人办"
            if (isFailed(context)) {
                return;
            }
            leave(context, 0);
            return;
        }
        if (count > MAX_LOOP_INSTANCES) {
            fail(context, "多实例节点 " + node.getId() + " 的实例数 " + count
                    + " 超过上限 " + MAX_LOOP_INSTANCES + "，拒绝展开");
            return;
        }

        if (node.isSequential()) {
            // 串行：只建第一个实例，并把计划数冻在这条 token 上
            bindLoopVariables(context, node, token, 0, null);
            token.getVariables().put(WfMultiInstance.PLANNED_TOTAL, count);
            createInstanceTask(context, node, token, 0);
            return;
        }

        // 集合在这里求值**一次**并传下去，而不是每个实例求一遍：
        // 次数来自第 1 次求值、元素来自第 2..N 次，两次结果不一致时
        // 会出现"实例数按 A、元素按 B"而越界报错 —— 那种不一致没有任何价值，
        // 只是白跑 N 次表达式
        List<Object> elements = isBlank(node.getLoopCollection()) ? null
                : resolveCollection(context, node);
        for (int i = 0; i < count; i++) {
            WfExecution branch = i == 0 ? token : new WfExecution(
                    idGenerator.nextExecutionId(), context.getProcessInstanceId(), node.getId());
            branch.setActivityId(node.getId());
            branch.setEnteredTime(new Date());
            branch.setState(WfExecution.State.ACTIVE);
            if (i > 0) {
                branch.setParentId(token.getId());
                branch.setChild(true);
                token.getChildren().add(branch.getId());
                context.addNewExecution(branch);
            }
            bindLoopVariables(context, node, branch, i, elements);
            createInstanceTask(context, node, branch, i);
        }
    }

    /**
     * 串行多实例：当前实例办完后，把<b>同一条 token</b> 推进到第 {@code next} 个实例。
     *
     * <p>由 {@code WfRuntimeService#closeMultiInstanceIfDone} 在"任务已办结、
     * 流程还没往下走"的那一刻调用。返回 false 表示流程应当继续等这条新实例。
     *
     * <p>不新建 token 是刻意的：串行下"这个节点总共几个实例"从任务集合数不出来
     * （同一时刻只有一条任务在办），计划数只能存在驱动它的那条 token 上；
     * 而这条 token 正是随后要继续往下走的那个"当前 token"，
     * 换一条就得把当前 token 的身份在多处传递下去。
     *
     * @return 是否真的建出了下一个实例（false = 调用方应当收口，让 token 离开本节点）
     */
    public boolean advanceSequentialInstance(WfContext context, WfNode node,
                                             WfExecution token, int next) {
        if (next >= MAX_LOOP_INSTANCES) {
            fail(context, "多实例节点 " + node.getId() + " 的实例序号 " + next
                    + " 超过上限 " + MAX_LOOP_INSTANCES + "，拒绝展开");
            return false;
        }
        bindLoopVariables(context, node, token, next, null);
        token.setState(WfExecution.State.ACTIVE);
        token.setEnteredTime(new Date());
        return createInstanceTask(context, node, token, next);
    }

    /**
     * 在 token 上建出第 {@code index} 个实例的任务。
     *
     * <p>建不出任务（behavior 返回 null）时返回 false：调用方据此判断
     * "这个实例到底有没有真的开起来"，而不是以为它挂上了正在等人办。
     */
    private boolean createInstanceTask(WfContext context, WfNode node, WfExecution token, int index) {
        WfExecution saved = context.getCurrentExecution();
        context.setCurrentExecution(token);
        try {
            WfTask task = behaviorRegistry.getBehavior(node.getType())
                    .execute(context, node, token);
            if (task != null) {
                context.addCreatedTask(task);
                token.setState(WfExecution.State.WAITING);
                return true;
            }
            return false;
        } catch (Exception e) {
            fail(context, "多实例第 " + index + " 个实例创建任务失败: " + e.getMessage());
            return false;
        } finally {
            context.setCurrentExecution(saved);
        }
    }

    /**
     * 实例数：{@code collection} 优先（它由集合大小决定），否则取 {@code loopCardinality}。
     *
     * <p>两者都没配时校验器已经报过 ERROR；这里返回 0 只是为了让运行期
     * 有一条确定的行为（跳过该节点）而不是 NPE。
     */
    private int resolveLoopCount(WfContext context, WfNode node) {
        if (!isBlank(node.getLoopCollection())) {
            List<Object> elements = resolveCollection(context, node);
            if (elements == null) {
                // resolveCollection 已经 fail 过了：取不到集合时按"取不到人"处理，
                // 不能当成"没人"——后者会让流程直接跳过整个会签节点往下走
                return 0;
            }
            return elements.size();
        }
        return resolveLoopCardinality(context, node);
    }

    /** loopCardinality：纯数字直接取；{@code ${}} 表达式求值后转 int。 */
    private int resolveLoopCardinality(WfContext context, WfNode node) {
        String raw = node.getLoopCardinality();
        if (raw == null || raw.trim().isEmpty()) {
            return 0;
        }
        try {
            Object value = raw.trim().matches("\\d+")
                    ? Integer.valueOf(raw.trim())
                    : expressionEvaluator.evalRaw(raw, context.mergedVariables());
            if (value == null) {
                fail(context, "多实例节点 " + node.getId()
                        + " 的 loopCardinality 求值为空: " + raw);
                return 0;
            }
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            fail(context, "多实例节点 " + node.getId()
                    + " 的 loopCardinality 不是整数: " + raw);
            return 0;
        }
    }

    /**
     * 求 {@code collection} 指向的集合。
     *
     * <p>每次调用都重新求值，<b>不缓存</b>：串行多实例每个实例都要取一次，
     * 而缓存下来就得把用户数据复制进 token 的局部变量里 ——
     * 那个 Map 走 JSON 存库，元素类型会被 JSON 往返改写（BigDecimal 变 Double、
     * Date 变字符串），于是"第 2 个人拿到的东西和第 1 个人在内存里看到的不一样"。
     * 实例数在<b>进入时</b>就冻在 token 上（{@link WfMultiInstance#PLANNED_TOTAL}），
     * 所以"循环按进入时的人数走"这件事有保证，不依赖缓存。
     *
     * <p>取不到 / 类型不对都 fail-closed 报错并返回 null，绝不当成空集合。
     */
    private List<Object> resolveCollection(WfContext context, WfNode node) {
        String expression = node.getLoopCollection();
        Object value = expressionEvaluator.evalRaw(expression, context.mergedVariables());
        if (value == null) {
            fail(context, "多实例节点 " + node.getId() + " 的 collection=" + expression
                    + " 求值为空或未定义。变量不存在时按 fail-closed 处理为「取不到人」，"
                    + "而不是「没人」——后者会让流程直接跳过这个会签节点往下走");
            return null;
        }
        if (value instanceof List) {
            return (List<Object>) value;
        }
        if (value instanceof Object[]) {
            return java.util.Arrays.asList((Object[]) value);
        }
        if (value instanceof Collection) {
            return new java.util.ArrayList<>((Collection<Object>) value);
        }
        fail(context, "多实例节点 " + node.getId() + " 的 collection=" + expression
                + " 必须是集合或数组，实际类型: " + value.getClass().getName());
        return null;
    }

    /**
     * 把第 {@code index} 个实例的循环变量绑到 token 上。
     *
     * <p>绑三样东西：{@code loopCounter}、{@code elementVariable}（元素原值，
     * 仅当配了 collection 与 elementVariable）、{@code loopAssignee}（元素或
     * 兼容写法 {@code zifang:loopAssignees} 里那一项的字符串形式）。
     *
     * <p>元素<b>每次重新求值</b>而不是入循环时取一次：串行下每个实例建任务的
     * 时机不同，取一次就得缓存在 token 上（同上，JSON 往返会改写元素类型）。
     * 取不到第 index 个时报错停住——实例数已经冻住了，集合却变短了，
     * 悄悄少办几个人比报错危险得多。
     */
    private void bindLoopVariables(WfContext context, WfNode node, WfExecution token, int index,
                                   List<Object> preResolved) {
        token.getVariables().put(WfMultiInstance.LOOP_COUNTER, index);
        Object element = null;
        boolean hasCollection = !isBlank(node.getLoopCollection());
        if (hasCollection) {
            // 并行路径传进来的是同一次求值的结果；串行传 null —— 每个实例
            // 建任务的时机不同，重取一次比缓存一份用户数据更可控（见 resolveCollection）
            List<Object> elements = preResolved != null ? preResolved : resolveCollection(context, node);
            if (elements == null) {
                return;
            }
            if (index >= elements.size()) {
                fail(context, "多实例节点 " + node.getId() + " 取第 " + index
                        + " 个实例的元素时越界：collection=" + node.getLoopCollection().trim()
                        + " 现在只有 " + elements.size() + " 个元素。"
                        + "实例数是在进入这个节点时定的，不会跟着集合变；"
                        + "请在进入前把人员列表备齐");
                return;
            }
            element = elements.get(index);
            if (!isBlank(node.getLoopElement())) {
                token.getVariables().put(node.getLoopElement().trim(), element);
            }
        }
        // loopAssignee 给的是**字符串形式**：集合里放对象时，
        // zifang:assignee="${loopAssignee}" 拼出来的仍是办理人 id 字符串，
        // 而要读元素字段的作者有 loopElement 可用
        String assignee = hasCollection
                ? (element == null ? null : String.valueOf(element))
                : legacyAssigneeAt(context, node, index);
        if (assignee != null) {
            token.getVariables().put(WfMultiInstance.LOOP_ASSIGNEE, assignee);
        } else {
            // 串行复用同一条 token：上一轮的元素值必须清掉，
            // 否则第 2 个实例会在自己的 loopElement 里看到第 1 个人的数据
            token.getVariables().remove(WfMultiInstance.LOOP_ASSIGNEE);
            if (!isBlank(node.getLoopElement())) {
                token.getVariables().remove(node.getLoopElement().trim());
            }
        }
    }

    /**
     * 兼容写法 {@code zifang:loopAssignees}：第 {@code index} 个办理人。
     *
     * <p>与 {@code collection} 的差别只有一处：它<b>不决定实例数</b>，
     * 实例数仍由 {@code loopCardinality} 给。因此列表比实例数短时，
     * 后面那几个实例没有办理人——这是这条老写法的既有行为，不改。
     */
    private String legacyAssigneeAt(WfContext context, WfNode node, int index) {
        if (isBlank(node.getLoopAssignees())) {
            return null;
        }
        List<?> assignees = resolveLoopAssignees(context, node);
        return assignees != null && index < assignees.size()
                ? String.valueOf(assignees.get(index)) : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 这次推进是否已经被 {@link #fail} 标成了内部终止。 */
    private boolean isFailed(WfContext context) {
        WfProcessInstance instance = context.getProcessInstance();
        return instance != null && instance.getStatus() == WfProcessStatus.INTERNALLY_TERMINATED;
    }

    /** 每个实例的办理人列表；未配置返回 null。 */
    private List<?> resolveLoopAssignees(WfContext context, WfNode node) {
        String expression = node.getLoopAssignees();
        if (expression == null || expression.trim().isEmpty()) {
            return null;
        }
        Object value = expressionEvaluator.evalRaw(expression, context.mergedVariables());
        if (value == null) {
            fail(context, "多实例节点 " + node.getId()
                    + " 的 loopAssignees 变量为空或未定义: " + expression
                    + "。变量不存在时按 fail-closed 处理为无办理人，"
                    + "会导致这批待办没人认领");
            return null;
        }
        if (value instanceof List) {
            return (List<?>) value;
        }
        fail(context, "多实例节点 " + node.getId()
                + " 的 loopAssignees 必须是集合，实际类型: " + value.getClass().getName());
        return null;
    }

    /**
     * endEvent 的 resultExpression 求值 → 流程结果。
     */
    private void evaluateResult(WfContext context, WfNode node) {
        if (node.getResultExpression() == null || node.getResultExpression().trim().isEmpty()) {
            if (context.getProcessResult() == null) {
                context.setProcessResult("completed");
            }
            return;
        }
        Object value = expressionEvaluator.evalRaw(node.getResultExpression(),
                context.mergedVariables());
        context.setProcessResult(value == null ? "completed" : String.valueOf(value));
    }

    // ==================== leave：离开节点 ====================

    /**
     * 离开 token 当前节点，沿出线前进。
     */
    private void leave(WfContext context, int depth) {
        if (depth > MAX_DEPTH) {
            fail(context, "流程推进深度超过 " + MAX_DEPTH);
            return;
        }
        WfExecution token = context.getCurrentExecution();
        if (token == null || token.isEnded()) {
            return;
        }
        WfDefinition definition = context.getDefinition();
        WfNode node = definition.node(token.getActivityId());
        if (node == null) {
            fail(context, "流程定义 " + definition.getKey()
                    + " 中找不到节点: " + token.getActivityId());
            return;
        }

        // 一次节点访问的唯一一条历史记录。意见取自 completeTask 放进来的
        // pendingActivityOutcome；没有意见时记 "completed"。
        //
        // 网关排除在外：它是路由而不是活动，轨迹上冒出"排他网关 3"这种行
        // 对审批人没有意义，却会按流程步数把历史表撑大一倍。Camunda 的
        // HistoricActivityInstance 同样只收 startEvent / endEvent / task /
        // subProcess / callActivity。
        //
        // 这道判断当前并不决定结果 —— 网关走 handleGateway 自己的 followFlows，
        // 压根到不了 leave。写在这里是把"网关不进历史"这条规则钉在唯一该钉的
        // 地方：将来若把网关改成经由 leave 推进，这行就是拦住误记的那道闸。
        // WfHistoryQueryTest#gatewaysAreNotRecorded 负责证明这条规则确实成立。
        // 异步后置的续跑不再记一条：节点在那次正常的 leave 里已经记过了。
        // 记两条的话轨迹上会出现两次"审批"，而"一次节点访问一条"是本引擎的硬约定 ——
        // 排障时看到同一单审了两次而实际只有一个人办过一次。
        //
        // 异步前置的续跑（ENTER）不适用这条豁免：它走 enter 而不是直接来 leave，
        // 真正的 leave 发生在节点执行完时，那一条才是该记的。
        if (!node.getType().isGateway()
                && context.getResume() != WfContext.Resume.LEAVE) {
            String outcome = context.consumePendingActivityOutcome();
            context.recordActivity(node.getId(), node.getName(), node.getType().bpmnName(),
                    outcome == null || outcome.trim().isEmpty() ? "completed" : outcome);
        }

        // ---- 撤掉本节点起过的定时器 ----
        // 放在历史记录之后、选线之前：无论下一步走哪条线，这个 token 都已经离开了
        // 这个节点，它在这里起的那几只表必须一起撤。
        // 不撤的后果是审批系统里最招骂的那类 bug —— 人已经按时办完了，
        // 30 分钟后定时器照样响，把一条正常结束的流程拽进"超时"分支。
        context.clearJobsOf(token.getId());

        // ---- 异步后置：节点已经执行完，离开之前先排一次队 ----
        //
        // 放在 clearJobsOf 之后：那个调用会清掉本 token 上的全部 job，
        // 排在它前面的话，这里刚挂的 job 会在同一次落库里被自己撤掉 ——
        // 症状是"异步后置的 job 从来没出现过"，而流程看起来完全正常。
        //
        // asyncContinuation 同样必须切断：续跑动作就是 leave，不切断就是死循环。
        if (node.isAsyncAfter() && !context.isAsyncContinuation()) {
            context.addCreatedJob(asyncJob(context, node, token,
                    com.zifang.z.wf.core.model.WfJobType.ASYNC_AFTER));
            token.setState(WfExecution.State.WAITING);
            return;
        }

        // ---- 链接抛出事件：把 token 改道到 catch ----
        //
        // 位置在「取出线」**之前**，这一处就是 link 语义的全部要害：
        // token 离开 throw 事件的方式只有一条 —— 直接出现在 catch 上。
        // 放在取线之后会让 token 先沿 throw 的出线走一遍、然后才跳，
        // 于是「跳过一整段图」变成「跑完整段图再跳一遍」，
        // 作者以为跳过的那段照跑，而图上看不出任何异常。
        //
        // 与 escalation 的处置恰好相反（抛出去的 escalation 会让 throw 自己的
        // 出线也被走掉），所以这两处不能互相参照着改。
        //
        // 放在 clearJobsOf 与 asyncAfter **之后**：这两个动作与"下一步去哪"无关，
        // 而改道要重入 enter()，那会走一遍新的进入序列。顺序不能倒过来。
        if (node.getType() == WfNodeType.LINK_THROW) {
            jumpToLinkCatch(context, node, token, depth);
            return;
        }

        // ---- 网关离开：先记历史，选线由 handleGateway 在进入时已完成 ----
        List<WfFlow> flows = definition.outgoingFlows(node.getId());
        if (flows.isEmpty()) {
            log.warn("节点 {} 没有出线，token 结束", node.getId());
            token.setState(WfExecution.State.ENDED);
            return;
        }

        // 普通节点/网关离开时按条件筛出线
        List<WfFlow> selected = selectFlows(context, node, flows);
        if (selected.isEmpty()) {
            log.warn("节点 {} 的所有出线条件均不成立，token 停留", node.getId());
            token.setState(WfExecution.State.ACTIVE);
            return;
        }
        followFlows(context, node, token, selected, depth + 1);
    }

    /**
     * 链接改道：把 token 从 link 抛出事件搬到同名的 link 捕获事件上。
     *
     * <p>与 {@link #enterSubProcess} 是同一种形状（改 activityId 后重入 {@link #enter}），
     * 但刻意<b>不是</b>同一个方法：两者对"没找到落点"的处置理由完全不同 ——
     * 内联子流程找不到起点说明容器画空了，link 找不到落点说明图上少画了一个点。
     * 合成一个方法的话，错误信息只能说出一个模棱两可的版本。
     *
     * <p><b>落点找不到时必须停成内部终止，不能挑一个。</b>部署期已经把"找不到 catch"
     * 与"同名 catch 有多个"都报成 ERROR 了，走到这里只剩一种可能：定义被绕过了
     * 部署期校验（自定义装配、或直接调引擎）。此时随便挑一个 catch 跳过去，
     * 症状是流程走进另一条<b>完全正确</b>的分支并正常结束 —— 没有任何报错。
     */
    private void jumpToLinkCatch(WfContext context, WfNode node, WfExecution token, int depth) {
        WfDefinition definition = context.getDefinition();
        String linkName = node.getLinkName() == null ? null : node.getLinkName().trim();
        WfNode target = definition.linkTargetOf(linkName);
        if (target == null) {
            fail(context, "链接抛出事件 " + node.getId() + " 找不到落点: link 名 ["
                    + linkName + "] 对应的链接捕获事件在本流程定义 " + definition.getKey()
                    + " 里"
                    + (linkName == null || linkName.isEmpty()
                    ? "不存在（该节点没有 name，而 name 就是 link 名）"
                    : "找不到，或叫这个名字的 catch 不止一个（多个时「跳到哪一个」没有答案）")
                    + "。部署期会把这两种情况报成 ERROR，"
                    + "走到这一步说明流程定义绕过了部署期校验");
            return;
        }
        log.debug("link 改道: {} (link={}) -> {}", node.getId(), linkName, target.getId());
        token.setActivityId(target.getId());
        // 与 enterSubProcess 一样重置进入时间：catch 是一个新到达的节点，
        // 留着 throw 那个时间会让"在 catch 上停了多久"算成"在 throw 上停了多久"
        token.setEnteredTime(new Date());
        enter(context, depth + 1);
    }

    /**
     * 进入嵌入式子流程 —— 把 token 从 subProcess 节点挪到它的内联起始节点。
     *
     * <p><b>不新建 execution</b>：内联子流程与 callActivity 的根本差别就在这里 ——
     * 它展开在父实例的同一棵 token 树里，所以并行网关的汇合判定、
     * 轨迹的父子归属都沿用主图的机制，不需要跨实例回查。
     * 代价是没有独立作用域（内联节点与主流程共享变量），这是与 Camunda 的显式差异，
     * 已写进 {@code docs/capability-gap.md}。
     *
     * <p>内联子流程的进入<b>不发 transition 事件</b>：BPMN 里没有一条 sequenceFlow
     * 跨容器边界（subProcess 到它的内联起始节点之间本来就没有线），
     * {@code fireTransition} 需要一个真实 flowId，这里给不出语义正确的值。
     */
    private void enterSubProcess(WfContext context, WfNode node, WfExecution token, int depth) {
        WfDefinition definition = context.getDefinition();
        WfNode start = definition.inlineStartNode(node.getId());
        if (start == null) {
            // 部署期校验器会挡掉"零个或多个内联起始节点"，走到这里说明被绕过了
            // （自定义装配、或直接调引擎不经过部署）。此时若随便挑一个进入，
            // 症状是"另一半内联节点一次都没跑"且实例正常结束 —— 必须停成内部终止。
            fail(context, "嵌入式 subProcess " + node.getId()
                    + " 找不到唯一的内联起始节点（容器内无入线的节点）: "
                    + idsOf(definition.inlineChildrenOf(node.getId()))
                    + "。内联子流程必须恰好有一个内联起始节点");
            return;
        }
        log.debug("进入嵌入式 subProcess {}: {} -> 内联起始 {}", node.getId(), node.getId(), start.getId());
        token.setActivityId(start.getId());
        token.setEnteredTime(new Date());
        enter(context, depth + 1);
    }

    /**
     * 离开嵌入式子流程 —— token 抵达内联结束事件，回到容器节点上沿它的出线走主图。
     *
     * <p>内联结束事件自己那条历史在这里补记：它走不到 {@link #leave}（那条路以
     * "离开当前节点"为前提），而"一次节点访问一条历史"是本引擎的硬约定。
     * 办理人置空与流程级 endEvent 同理由：结束不是任何一个人的动作，
     * 留着手办人会污染"某人办过哪些单"。
     */
    private void leaveSubProcess(WfContext context, WfNode endNode, WfExecution token, int depth) {
        WfDefinition definition = context.getDefinition();
        String containerId = endNode.nestedIn();
        WfNode container = definition.node(containerId);
        if (container == null || container.getType() != WfNodeType.SUB_PROCESS) {
            // nestedIn 是解析期写死的字符串，理论上恒指向一个 subProcess 节点；
            // 这里仍要炸：容器 id 对不上时若继续走，token 会被塞到一个不是子流程的
            // 节点上沿出线跳掉，流程"看起来跑通了"而内联内容被整段跳过。
            fail(context, "内联结束事件 " + endNode.getId() + " 声称嵌在 " + containerId
                    + " 里，但该 id 在流程定义 " + definition.getKey() + " 中不是 subProcess 节点"
                    + "（找到的是: " + (container == null ? "null" : container.getType()) + "）");
            return;
        }

        WfActivityInstance ended = context.recordActivity(endNode.getId(), endNode.getName(),
                endNode.getType().bpmnName(), "completed");
        ended.setAssignee(null);

        log.debug("离开嵌入式 subProcess {}: 内联结束 {} -> 回到容器", containerId, endNode.getId());
        token.setActivityId(container.getId());
        token.setEnteredTime(new Date());
        // 容器的历史、起过的 job、asyncAfter、出线选择全部由 leave 统一处理，
        // 这里不重复做其中任何一件 —— 重复记一条历史的症状是轨迹上"审批了两次"。
        leave(context, depth + 1);
    }

    /** 节点 id 列表（诊断信息用）。 */
    private static String idsOf(List<WfNode> nodes) {
        StringBuilder ids = new StringBuilder("[");
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) {
                ids.append(", ");
            }
            ids.append(nodes.get(i).getId());
        }
        return ids.append(']').toString();
    }

    /**
     * 按节点类型选择要走的出线。
     */
    private List<WfFlow> selectFlows(WfContext context, WfNode node, List<WfFlow> flows) {
        if (!node.getType().isGateway()) {
            // 普通节点：条件不成立的线不走；全不成立则停留
            List<WfFlow> passed = new ArrayList<>();
            for (WfFlow flow : flows) {
                if (flow.isUnconditional()
                        || expressionEvaluator.evaluate(flow.getConditionExpression(),
                        context.mergedVariables())) {
                    passed.add(flow);
                }
            }
            return passed;
        }

        Map<String, Object> variables = context.mergedVariables();
        List<WfFlow> selected = new ArrayList<>();

        if (node.getType() == WfNodeType.PARALLEL_GATEWAY) {
            selected.addAll(flows);
            return selected;
        }

        if (node.getType() == WfNodeType.EVENT_BASED_GATEWAY) {
            // 全部分叉出去，每条分支各自停在自己的捕获事件上等。
            // 这里放行全部出线与并行网关同形，但<b>后果不同</b>：
            // 并行网关的分支最终要汇合，事件网关的分支互相排斥 ——
            // 谁先被事件唤醒，其余分支连同各自的订阅一起作废。
            // 互斥发生在触发侧（见 WfRuntimeService#fireEventGatewayBranch），
            // 不是在这里筛线：分叉的那一刻还没有任何一条分支知道自己会不会被选中。
            selected.addAll(flows);
            return selected;
        }

        if (node.getType() == WfNodeType.EXCLUSIVE_GATEWAY) {
            for (WfFlow flow : flows) {
                if (flow.isDefaultFlow()) {
                    continue;
                }
                if (flow.isUnconditional()
                        || expressionEvaluator.evaluate(flow.getConditionExpression(), variables)) {
                    selected.add(flow);
                    return selected; // 只走第一条
                }
            }
            return defaultFlow(flows);
        }

        if (node.getType() == WfNodeType.COMPLEX_GATEWAY) {
            // 复杂网关有两种判定方式，**由网关自己有没有判别变量决定**：
            // 有 → 取值分派（Camunda 的 caseValue 扩展，只走一条）；
            // 没有 → 条件分派（BPMN 2.0 对复杂网关的定义：出线带条件，成立的都激活）。
            // 两种方式混在同一个网关上会让"走哪几条"没有唯一答案，
            // 所以校验器会把混用挡在部署期，而不是在这里挑一种执行。
            if (node.getCaseVariable() == null || node.getCaseVariable().trim().isEmpty()) {
                return selectByConditions(flows, variables);
            }
            return selectByCaseValue(context, node, flows, variables);
        }

        // 包容网关：所有条件成立的线
        for (WfFlow flow : flows) {
            if (flow.isDefaultFlow()) {
                continue;
            }
            if (flow.isUnconditional()
                    || expressionEvaluator.evaluate(flow.getConditionExpression(), variables)) {
                selected.add(flow);
            }
        }
        if (selected.isEmpty()) {
            return defaultFlow(flows);
        }
        return selected;
    }

    /**
     * 复杂网关的<b>条件分派</b>：出线带条件，条件成立的线**全部激活**。
     *
     * <p>这是 BPMN 2.0 对复杂网关的定义，也是它区别于排他网关的地方 ——
     * 排他是"第一条成立就停"，复杂是"有几条成立就走几条"。
     * 此前本引擎只按 {@code caseValue} 分派，出线上的 {@code <conditionExpression>}
     * 被<b>完全忽略</b>：一条只带条件的出线永远不会被选中，流程静默落到默认线，
     * 而校验器却会因为它与 caseValue 同时出现而报错 ——
     * 也就是说这个属性"看起来有意义、实际不起作用"。
     *
     * <p><b>多条激活不会让汇合死锁</b>：汇合判定数的是"确实已激活的兄弟 token"
     * （见 {@link #allSiblingsArrived}），没被激活的线根本不会产生 token，
     * 所以汇合不会去等它们。这条是本方法成立的前提。
     *
     * <p>一条都没成立时走默认线；<b>无条件且非默认的出线不存在</b> ——
     * 校验器会报 ERROR，因为"没有条件也照样走"在这种网关上无法与
     * "条件成立才走"区分。
     */
    private List<WfFlow> selectByConditions(List<WfFlow> flows, Map<String, Object> variables) {
        List<WfFlow> selected = new ArrayList<>();
        for (WfFlow flow : flows) {
            if (flow.isDefaultFlow()) {
                continue;
            }
            // 求值是 fail-closed 的：引用不存在的变量算"不成立"而不是抛，
            // 与排他/包容网关保持同一条约定
            if (expressionEvaluator.evaluate(flow.getConditionExpression(), variables)) {
                selected.add(flow);
            }
        }
        if (selected.isEmpty()) {
            return defaultFlow(flows);
        }
        return selected;
    }

    /**
     * 复杂网关选线：取判别变量的值，走第一条 {@code caseValue} 相同的线。
     *
     * <p><b>只走一条</b>，即使多条 caseValue 都能匹配。这是复杂网关与包容网关的
     * 根本区别，放行多条会让"按状态分派"变成"状态一变全走一遍"。
     *
     * <p>变量没值时<b>不静默走默认线</b>，而是让 token 停住并把原因说清楚：
     * 变量缺失通常是上游忘了赋值或变量名写错，而静默走默认线的话流程会
     * "成功地"走错分支且没有任何报错 —— 那是最难查的一类。
     */
    private List<WfFlow> selectByCaseValue(WfContext context, WfNode node,
                                           List<WfFlow> flows, Map<String, Object> variables) {
        // 判别变量写的是 ${} 表达式而不是变量名，所以要走 evalRaw 取"原始值"。
        // 走 evaluate（返回 boolean）会把 approved/rejected 都压成 false，
        // 于是所有 caseValue 都匹配不上，流程永远走默认线。
        Object actual = expressionEvaluator.evalRaw(node.getCaseVariable(), variables);
        if (actual == null) {
            log.warn("复杂网关 {} 的判别变量 {} 取不到值，token 停留。"
                    + "常见原因是上游忘了赋值或变量名写错 —— 静默走默认线会让"
                    + "流程成功地走错分支且没有任何报错", node.getId(), node.getCaseVariable());
            return new ArrayList<WfFlow>();
        }
        String value = String.valueOf(actual);
        for (WfFlow flow : flows) {
            if (flow.getCaseValue() != null && flow.getCaseValue().equals(value)) {
                List<WfFlow> picked = new ArrayList<WfFlow>();
                picked.add(flow);
                return picked;
            }
        }
        List<WfFlow> fallback = defaultFlow(flows);
        if (fallback.isEmpty()) {
            log.warn("复杂网关 {} 的判别变量值 {} 没有匹配的出线，也没有默认流，token 停留。"
                    + "流程不会再前进", node.getId(), value);
        }
        return fallback;
    }

    /**
     * 取默认流。
     */
    private List<WfFlow> defaultFlow(List<WfFlow> flows) {
        List<WfFlow> result = new ArrayList<>();
        for (WfFlow flow : flows) {
            if (flow.isDefaultFlow()) {
                result.add(flow);
                break;
            }
        }
        return result;
    }

    // ==================== 异步续跑 ====================

    /**
     * 为异步节点建 job。
     *
     * <p>{@code duedate} 设为当前时刻：异步 job 一排好队就该被下一次扫描捞走，
     * 留空反而捞不到（{@code DUEDATE <= ?} 对 null 恒不成立）。
     * 需要延后执行的场景由业务侧推迟扫描时刻，不在本版范围内。
     *
     * <p>{@code attachedToRef} 与 {@code elementId} 都填节点本身：异步 job 不挂在
     * 某个边界事件上，续跑时靠 {@code elementId} 找到该进/该离的节点。
     */
    private com.zifang.z.wf.core.model.WfJob asyncJob(WfContext context, WfNode node,
                                                        WfExecution token,
                                                        com.zifang.z.wf.core.model.WfJobType type) {
        com.zifang.z.wf.core.model.WfJob job = new com.zifang.z.wf.core.model.WfJob();
        job.setProcessInstanceId(context.getProcessInstanceId());
        job.setExecutionId(token.getId());
        job.setElementId(node.getId());
        job.setAttachedToRef(node.getId());
        job.setType(type);
        job.setDuedate(new java.util.Date());
        job.setCreateTime(new java.util.Date());
        // 优先级从宿主节点拷过来而不是另配一个：加急单子通常在图上写一次
        // zifang:priority，让任务的排序与队列的取用顺序对得上。
        // 两处各配各的话，会出现「待办里排最前、流程却最后才跑」
        job.setPriority(node.getPriority());
        job.setRetries(com.zifang.z.wf.core.model.WfJob.DEFAULT_RETRIES);
        return job;
    }

    /**
     * 异步 job 的续跑：<b>进入</b> token 当前所在的节点并执行它。
     *
     * <p>供 {@code ASYNC_BEFORE} 使用：那次节点还没跑过，续跑就是把它的行为补上
     * （建任务 / 跑 delegate / 路由网关），跑完可能停在节点上（任务类），
     * 也可能直接继续往下（穿透型）。
     *
     * <p>会打上 {@code asyncContinuation} 标记：{@link #enter} 里那个"要不要挂
     * 异步前置 job"的判断必须看见它，否则续跑会再挂一个 job，执行器无限循环。
     */
    public void resumeEnter(WfContext context, WfExecution token) {
        context.setResume(WfContext.Resume.ENTER);
        WfExecution saved = context.getCurrentExecution();
        context.setCurrentExecution(token);
        try {
            enter(context, 0);
        } finally {
            context.setCurrentExecution(saved);
        }
    }

    /**
     * 异步 job 的续跑：<b>离开</b> token 当前所在的节点、沿出线前进。
     *
     * <p>供 {@code ASYNC_AFTER} 使用：那次节点已经执行完（人已办结 / delegate 已跑过），
     * 续跑只是补上"离开这一步"的动作，<b>绝不能重跑节点行为</b> ——
     * 重跑一次意味着审批任务被重建、delegate 被调第二遍。
     */
    public void resumeLeave(WfContext context, WfExecution token) {
        context.setResume(WfContext.Resume.LEAVE);
        WfExecution saved = context.getCurrentExecution();
        context.setCurrentExecution(token);
        try {
            leave(context, 0);
        } finally {
            context.setCurrentExecution(saved);
        }
    }

    // ==================== 网关 ====================

    /**
     * 处理网关：先判汇合，再选线，再决定是否 fork。
     */
    private void handleGateway(WfContext context, WfNode node, WfExecution token, int depth) {
        WfDefinition definition = context.getDefinition();
        List<WfFlow> inFlows = definition.incomingFlows(node.getId());

        // ---- 汇合判定 ----
        if (isJoin(node, inFlows) && !allSiblingsArrived(context, token, node)) {
            log.debug("网关 {} 汇合未到齐，token 等待中", node.getId());
            token.setState(WfExecution.State.WAITING);
            return;
        }
        if (isJoin(node, inFlows)) {
            // 汇合通过：结束所有抵达的兄弟 token，执行树在此收敛
            collapseSiblings(context, token);
        }

        // ---- 选线 ----
        List<WfFlow> selected = selectFlows(context, node, definition.outgoingFlows(node.getId()));
        if (selected.isEmpty()) {
            log.warn("网关 {} 无可用出线，token 停留", node.getId());
            token.setState(WfExecution.State.ACTIVE);
            return;
        }

        followFlows(context, node, token, selected, depth + 1);
    }

    /**
     * 是否是汇合点（多条入线且入线来自不同源节点）。
     *
     * <p><b>三种网关的汇合语义并不相同，而先前这条判定压根不看节点类型</b> ——
     * 只要"多条入线 + 多个来源"就一律合并。并行/包容这样合并是对的，
     * 排他网关这样合并是<b>错的</b>，症状是两条并行 token 被压成一条：
     * 「法务审」与「财务审」并行结束后经排他网关进入下一步，
     * 本引擎只建一条待办，而 Camunda 建两条 —— 从 Camunda 导入的模型
     * 会静默少掉一条分支，且流程图上与图上没有任何提示。
     *
     * <p>依据（三个独立来源说法一致，故当成确定语义而不是某一版的实现细节）：
     * <ul>
     *   <li>Camunda 官方：「An exclusive gateway can also be used to join multiple
     *       incoming flows... A joining gateway has a <b>pass-through semantic</b>.
     *       It doesn't merge the incoming concurrent flows like a parallel gateway.」</li>
     *   <li>Camunda 社区版对同一个模型的回答：排他网关处「the tokens will <b>not
     *       join</b>」，各自继续往下。</li>
     *   <li>中文实践总结：排他网关「作为 join 的含义：只要有一个前置分支到达后，
     *       即完成合并，流程继续往下执行」—— 注意「继续往下」是<b>各自</b>往下。</li>
     * </ul>
     *
     * <p>复杂网关没有"必然合并"这一说：Camunda 把它的 join 逻辑留给实现
     * （建模器里的 entering behavior 不导出到 XML），所以本实现<b>默认 joining</b>
     * （保持既有行为不变，改了会让已上线的模型悄悄改语义），
     * 需要穿透的显式写 {@code zifang:complexJoin="competing"}。
     */
    private boolean isJoin(WfNode node, List<WfFlow> inFlows) {
        if (inFlows.size() <= 1) {
            return false;
        }
        if (node.getType() == WfNodeType.EXCLUSIVE_GATEWAY) {
            return false;
        }
        if (node.getType() == WfNodeType.COMPLEX_GATEWAY) {
            // **两条都要在这里给答案，不许落到下面那条常规判定上**：
            // competing 是"穿透"，而下面那条判的是"要不要合并"，
            // 让它落下去等于穿透根本没发生 —— 而那种写法看起来是实现了的。
            return !node.isCompetingJoin();
        }
        Set<String> sources = new HashSet<>();
        for (WfFlow flow : inFlows) {
            sources.add(flow.getSourceRef());
        }
        return sources.size() > 1;
    }

    /**
     * 汇合判定：所有兄弟 token 是否都已抵达本网关。
     *
     * <p>判据是"本轮被激活的分支数"而非"图上入线总数"：
     * 包容网关可能只激活了 2 条线（另外 3 条条件不成立），
     * 此时按入线总数判定会永远等不到那 3 条 ⇒ 流程永久卡死。
     * 所以只等"确实被激活过"的兄弟。
     */
    private boolean allSiblingsArrived(WfContext context, WfExecution token, WfNode gateway) {
        List<WfExecution> siblings = context.getProcessExecutions();
        if (siblings.isEmpty()) {
            return true;
        }
        WfDefinition definition = context.getDefinition();
        // 本 token 的同父兄弟
        List<WfExecution> peers = new ArrayList<>();
        for (WfExecution execution : siblings) {
            if (execution == null || execution.isEnded()) {
                continue;
            }
            // **必须跳过自己**：
            // context.getProcessExecutions() 是推进开始时从存储层载入的快照，
            // 里面本 token 那条记录还是"推进前"的位置（如仍停在 taskFinance）。
            // 若把它算进来，它永远不等于网关 id ⇒ 汇合永远不通过 ⇒ 流程永久卡在 join。
            if (execution.getId() != null && execution.getId().equals(token.getId())) {
                continue;
            }
            if (samePeer(execution, token, definition)) {
                peers.add(execution);
            }
        }
        if (peers.isEmpty()) {
            return true;
        }
        for (WfExecution peer : peers) {
            if (!gateway.getId().equals(peer.getActivityId())) {
                return false; // 还有兄弟没到
            }
        }
        return true;
    }

    /**
     * 是否为"同一批"的 token：同父，或一方是另一方的父。
     *
     * <p><b>同父之前先看是不是同一段图</b>：内联子流程的分支 token 与外层并行分支的
     * token 可能同父（父 token 停在那个 subProcess 节点上），但它们分属两段互不相干的图。
     * 只按 parentId 判定时，内层的 join 会去等外层还没办完的分支，
     * 症状是"流程卡在子流程里，且流程图上看不出任何异常"。
     */
    private boolean samePeer(WfExecution a, WfExecution b, WfDefinition definition) {
        if (a.getId().equals(b.getId())) {
            return true;
        }
        if (!sameInlineScope(a, b, definition)) {
            return false;
        }
        if (a.getParentId() != null && a.getParentId().equals(b.getId())) {
            return true;
        }
        if (b.getParentId() != null && b.getParentId().equals(a.getId())) {
            return true;
        }
        return a.getParentId() != null && a.getParentId().equals(b.getParentId());
    }

    /**
     * 两条 token 是否属于同一段图（同一个内联容器，或都在主图上）。
     *
     * <p>活动节点在定义里找不到时判 false 而不是放行：归属认不出来时把它算进
     * 同一批，只会让某个 join 多等一条本不该等的 token（表现为流程莫名卡住）。
     */
    private boolean sameInlineScope(WfExecution a, WfExecution b, WfDefinition definition) {
        if (definition == null) {
            return false;
        }
        String scopeA = definition.inlineScopeOf(a.getActivityId());
        String scopeB = definition.inlineScopeOf(b.getActivityId());
        if (scopeA == null || scopeB == null) {
            return false;
        }
        return scopeA.equals(scopeB);
    }

    /**
     * 汇合通过后，结束所有已抵达的兄弟 token。
     */
    private void collapseSiblings(WfContext context, WfExecution token) {
        WfDefinition definition = context.getDefinition();
        for (WfExecution execution : context.getProcessExecutions()) {
            if (execution == null || execution.isEnded() || execution == token) {
                continue;
            }
            // 快照里的自己那条不能碰（它不是正在推进的那个对象）
            if (execution.getId() != null && execution.getId().equals(token.getId())) {
                continue;
            }
            if (samePeer(execution, token, definition)) {
                execution.setState(WfExecution.State.ENDED);
                context.markTouched(execution);
            }
        }
    }

    // ==================== 沿连线前进 ====================

    /**
     * 沿选中的出线前进。
     *
     * <p><b>多出线 ⇒ fork 出多个 token</b>（并行/包容网关）：
     * 第一条线复用当前 token，其余每条线新建一个子 token 各自走。
     * 早期版本让同一个 token 依次走完所有出线，导致并行分支共用一个 executionId，
     * 汇合判定与任务归属全部错乱。
     */
    private void followFlows(WfContext context, WfNode from, WfExecution token,
                             List<WfFlow> selected, int depth) {
        WfDefinition definition = context.getDefinition();

        for (int i = 0; i < selected.size(); i++) {
            WfFlow flow = selected.get(i);
            WfNode target = definition.node(flow.getTargetRef());
            if (target == null) {
                log.error("连线 {} 的目标节点 {} 不存在，跳过", flow, flow.getTargetRef());
                continue;
            }

            WfExecution branchToken;
            if (i == 0) {
                branchToken = token;
            } else {
                branchToken = new WfExecution(idGenerator.nextExecutionId(),
                        context.getProcessInstanceId(), target.getId());
                branchToken.setParentId(token.getId());
                branchToken.setChild(true);
                token.getChildren().add(branchToken.getId());
                context.addNewExecution(branchToken);
            }

            // 流转钩子放在"token 改位之后、进入目标之前"：
            // 这时 from/to/flowId 三个值都拿得到，而 token 已经落到目标节点上，
            // 钩子实现方看到的 activeActivityId 与本引擎内部是一致的。
            //
            // 放在 enter 之后触发则不行：目标节点可能自己就往下走了若干步
            // （serviceTask 链、网关），钩子拿到的"到达时状态"与到达那一刻已不相干。
            context.fireTransition(from.getId(), target.getId(), flow.getId());

            branchToken.setActivityId(target.getId());
            branchToken.setEnteredTime(new Date());
            branchToken.setState(WfExecution.State.ACTIVE);

            if (i == 0) {
                // 复用当前 token：直接继续 enter
                context.setCurrentExecution(branchToken);
                enter(context, depth);
            } else {
                // 新 token：进入新分支（当前 token 已在 i==0 分支处理完）
                WfExecution saved = context.getCurrentExecution();
                context.setCurrentExecution(branchToken);
                enter(context, depth);
                context.setCurrentExecution(saved);
            }
        }
    }

    // ==================== 失败处理 ====================

    /**
     * 引擎级失败：记原因并把实例置为内部终止。
     *
     * <p><b>不吞异常</b>：错误原因写进 {@code deleteReason} 并落 ERROR 日志。
     * 静默失败会让"流程卡住"变成排障噩梦 —— 至少要让"内部终止"这个状态可被查询到。
     */
    private void fail(WfContext context, String message) {
        log.error("{}: processInstanceId={}", message, context.getProcessInstanceId());
        context.setFailureMessage(message);
        WfProcessInstance instance = context.getProcessInstance();
        if (instance.getStatus() == null || !instance.getStatus().isTerminal()) {
            instance.setStatus(WfProcessStatus.INTERNALLY_TERMINATED);
            instance.setEndTime(new Date());
            instance.setDeleteReason(message);
        }
    }
}
