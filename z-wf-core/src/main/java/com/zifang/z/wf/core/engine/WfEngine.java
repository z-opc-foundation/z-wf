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
        WfDefinition definition = context.getDefinition();
        WfNode startNode = definition.startNode();

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
        if (!node.getType().isGateway()) {
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
