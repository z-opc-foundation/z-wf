package com.zifang.z.wf.core.engine;

import java.util.ArrayList;
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
     * 构造带协作组件的上下文。
     */
    public WfContext newContext(WfDefinition definition, WfProcessInstance instance,
                                WfExecution execution) {
        WfContext context = new WfContext(definition, instance, execution);
        context.setExpressionEvaluator(expressionEvaluator);
        context.setDelegateRegistry(delegateRegistry);
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
     * 进入多实例节点：按 {@code loopCardinality} 展开 N 个实例。
     *
     * <p>每个实例是一个<b>独立的 token</b>，各自停在同一节点上、各自建一条任务。
     * 之所以用独立 token 而不是一条 token 记计数：实例之间会真的并行存在
     * （三个人同时在办），用一条 token 就没法表达"谁办完了谁没办"。
     *
     * <p>每个 token 带两个<b>局部</b>变量：{@code loopCounter}（0 起的序号）
     * 与 {@code loopAssignee}（该实例的办理人）。
     * 用局部而非流程级，是因为它们属于单个实例——
     * 放进流程变量会互相覆盖，最后一个实例的值会把前面的盖掉。
     *
     * <p>办理人列表在<b>分叉时用 Java 取</b>，而不是在表达式里写
     * {@code ${approvers[loopCounter]}}：实测 z-util 的 EL 不支持变量下标
     * （{@code approvers[1]} 可以，{@code approvers[loopCounter]} 抛 ElException）。
     */
    private void enterMultiInstance(WfContext context, WfNode node, WfExecution token) {
        int count = resolveLoopCardinality(context, node);
        if (count <= 0) {
            // 展开 0 个实例：BPMN 语义是直接完成该节点，往下走
            leave(context, 0);
            return;
        }
        if (count > MAX_LOOP_INSTANCES) {
            fail(context, "多实例节点 " + node.getId() + " 的 loopCardinality=" + count
                    + " 超过上限 " + MAX_LOOP_INSTANCES + "，拒绝展开");
            return;
        }

        List<?> assignees = resolveLoopAssignees(context, node);
        for (int i = 0; i < count; i++) {
            WfExecution branch = i == 0 ? token : new WfExecution(
                    idGenerator.nextExecutionId(), context.getProcessInstanceId(), node.getId());
            branch.setActivityId(node.getId());
            branch.setEnteredTime(new Date());
            branch.setState(WfExecution.State.ACTIVE);
            branch.getVariables().put(WfMultiInstance.LOOP_COUNTER, i);
            if (assignees != null && i < assignees.size()) {
                branch.getVariables().put(WfMultiInstance.LOOP_ASSIGNEE,
                        String.valueOf(assignees.get(i)));
            }
            if (i > 0) {
                branch.setParentId(token.getId());
                branch.setChild(true);
                token.getChildren().add(branch.getId());
                context.addNewExecution(branch);
            }

            WfExecution saved = context.getCurrentExecution();
            context.setCurrentExecution(branch);
            try {
                WfTask task = behaviorRegistry.getBehavior(node.getType())
                        .execute(context, node, branch);
                if (task != null) {
                    context.addCreatedTask(task);
                    branch.setState(WfExecution.State.WAITING);
                }
            } catch (Exception e) {
                fail(context, "多实例第 " + i + " 个实例创建任务失败: " + e.getMessage());
            }
            context.setCurrentExecution(saved);
        }
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
     */
    private boolean isJoin(WfNode node, List<WfFlow> inFlows) {
        if (inFlows.size() <= 1) {
            return false;
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
            if (samePeer(execution, token)) {
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
     */
    private boolean samePeer(WfExecution a, WfExecution b) {
        if (a.getId().equals(b.getId())) {
            return true;
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
     * 汇合通过后，结束所有已抵达的兄弟 token。
     */
    private void collapseSiblings(WfContext context, WfExecution token) {
        for (WfExecution execution : context.getProcessExecutions()) {
            if (execution == null || execution.isEnded() || execution == token) {
                continue;
            }
            // 快照里的自己那条不能碰（它不是正在推进的那个对象）
            if (execution.getId() != null && execution.getId().equals(token.getId())) {
                continue;
            }
            if (samePeer(execution, token)) {
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
