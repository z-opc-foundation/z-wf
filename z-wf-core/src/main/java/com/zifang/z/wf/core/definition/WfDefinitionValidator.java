package com.zifang.z.wf.core.definition;

import com.zifang.z.wf.core.engine.WfEngine;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流程定义校验器 —— 部署期的合法性闸门。
 *
 * <p>设计取舍：<b>收集全部问题，一次性报完</b>，而不是遇到第一个就抛。
 * 理由是定义多半来自设计器导出（LogicFlow 等），一次只报一个错会让人反复提交-报错-再改，
 * 而流程图通常同时有好几个问题。诊断信息里带节点/连线 id，可直接定位。
 *
 * <p>校验项：
 * <ol>
 *   <li>key 非空</li>
 *   <li>节点 id 非空且唯一</li>
 *   <li>节点类型可识别</li>
 *   <li>连线的 source/target 指向存在的节点</li>
 *   <li>恰好一个开始节点</li>
 *   <li>userTask/serviceTask 的必要配置（formKey 视业务约定为可选，仅告警）</li>
 *   <li>网关上至多一条 defaultFlow</li>
 *   <li>排他网关若存在多条出线，至少一条须无条件或为 defaultFlow（否则永远走不出去）</li>
 *   <li>无孤立节点（无入线也无出线）—— 明确是死节点</li>
 * </ol>
 *
 * @author zifang
 */
public class WfDefinitionValidator {

    private final List<WfValidationIssue> issues = new ArrayList<>();

    /**
     * 校验并收集问题。
     *
     * @return 全部问题（空列表 = 合法）
     */
    public List<WfValidationIssue> validate(WfDefinition definition) {
        issues.clear();
        if (definition == null) {
            add(WfValidationIssue.Severity.ERROR, null, "流程定义为 null");
            return result();
        }

        if (isBlank(definition.getKey())) {
            add(WfValidationIssue.Severity.ERROR, null, "流程定义 key 不能为空");
        }

        List<WfNode> nodes = definition.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, null, "流程定义没有任何节点");
            return result();
        }

        // ---- 节点 id 唯一性 ----
        Set<String> ids = new HashSet<>();
        for (WfNode node : nodes) {
            if (node == null) {
                add(WfValidationIssue.Severity.ERROR, null, "存在 null 节点");
                continue;
            }
            if (isBlank(node.getId())) {
                add(WfValidationIssue.Severity.ERROR, null, "存在没有 id 的节点");
                continue;
            }
            if (!ids.add(node.getId())) {
                add(WfValidationIssue.Severity.ERROR, node.getId(), "节点 id 重复: " + node.getId());
            }
        }

        // ---- 节点级配置 ----
        for (WfNode node : nodes) {
            if (node == null || isBlank(node.getId())) {
                continue;
            }
            if (node.getType() == WfNodeType.SERVICE_TASK) {
                // 带 topic 的 serviceTask 交给外部 worker 跑，引擎里没有 delegate 可言；
                // 同时又配 delegate 则两者互斥 —— 不报错的话引擎只会挑 topic 那条，
                // 作者写的 delegate 永远不执行，且没有任何提示
                if (node.isExternalStep()) {
                    if (!isBlank(node.getDelegateClass()) || !isBlank(node.getDelegateExpression())) {
                        add(WfValidationIssue.Severity.ERROR, node.getId(),
                                "serviceTask 同时配了 zifang:topic（外部任务）与 "
                                        + (isBlank(node.getDelegateClass())
                                                ? "delegateExpression" : "delegateClass")
                                        + "，只能留一个。本实现会以 topic 为准，"
                                        + "delegate 永远不会被执行");
                    }
                } else if (isBlank(node.getDelegateClass()) && isBlank(node.getDelegateExpression())) {
                    add(WfValidationIssue.Severity.ERROR, node.getId(),
                            "serviceTask 需要 delegateClass 或 delegateExpression 之一"
                                    + "（或改用 zifang:topic 交给外部 worker）");
                } else if (!isBlank(node.getDelegateClass())
                        && !isBlank(node.getDelegateExpression())) {
                    // **Camunda 明确要求互斥**（官方 BPMN 2.0 参考 Service Task 一节：
                    // 「the three extensions camunda:class, camunda:expression, and
                    // camunda:delegateExpression are mutually exclusive. The process
                    // engine will use only one.」）
                    // 不报的话运行期只会挑其中一个（见 WfDelegateRegistry，
                    // 那里取的是 delegateExpression），于是"我明明写了类名却
                    // 一直没执行"，而图上与轨迹上都看不出原因。
                    // 与上面 topic/delegate、复杂网关 caseValue/conditionExpression
                    // 是同一条纪律，之前唯独漏了这里。
                    add(WfValidationIssue.Severity.ERROR, node.getId(),
                            "serviceTask 同时配了 delegateClass 与 delegateExpression，"
                                    + "只能留一个。Camunda 对 camunda:class / camunda:expression / "
                                    + "camunda:delegateExpression 也是这个要求（三者互斥，"
                                    + "引擎只用其中一个）。本实现会用 delegateExpression，"
                                    + "delegateClass 永远不会被执行");
                }
            }
            // topic 写在非 serviceTask 上：引擎在 enter 里对任何节点都会先看 topic，
            // 于是流程会在一个网关/事件节点上挂起等一个永远不会来的 worker 交差 ——
            // 表现是"流程走到一半停住，且没有任何 job 记录解释为什么"。
            // 部署期挡住，不要留到运行时靠人肉排查。
            if (node.isExternalStep() && node.getType() != WfNodeType.SERVICE_TASK) {
                add(WfValidationIssue.Severity.ERROR, node.getId(),
                        "zifang:topic 只能配在 serviceTask 上，当前节点类型是 " + node.getType()
                                + "。引擎会在进入该节点时挂起等外部交差，"
                                + "而这类节点没有可等待的外部动作语义，流程会停在这里不再前进");
            }
            validateAsync(node);
            if (node.getType() == WfNodeType.SCRIPT_TASK) {
                if (isBlank(node.getScript())) {
                    add(WfValidationIssue.Severity.ERROR, node.getId(), "scriptTask 缺少 script");
                }
            }
            if (node.getType() == WfNodeType.CALL_ACTIVITY) {
                if (isBlank(node.getCalledElementKey())) {
                    add(WfValidationIssue.Severity.ERROR, node.getId(), "callActivity 缺少 calledElementKey");
                }
            }
            // ---- 业务规则任务（第 24 轮）----
            if (node.getType() == WfNodeType.BUSINESS_RULE_TASK) {
                validateBusinessRuleTask(node);
            }
            // ---- 嵌入式 subProcess：内联子图的结构合法性 ----
            validateInlinePlacement(definition, node);
            validateSubProcess(definition, node);
            if (node.getType() == WfNodeType.RECEIVE_TASK) {
                if (isBlank(node.getMessageName())) {
                    add(WfValidationIssue.Severity.WARN, node.getId(),
                            "receiveTask 没有 messageName，该任务将只能被 force-complete 推进");
                }
            }
            // ---- 错误边界事件 ----
            if (node.getType() == WfNodeType.BOUNDARY_EVENT) {
                validateBoundaryEvent(node, definition);
            }
            // ---- 补偿（第 37 轮）----
            if (node.isForCompensation() || node.isCompensationBoundary()) {
                validateCompensationNode(definition, node);
            }
            // ---- 多实例（会签 / 或签）----
            validateMultiInstance(node);
            // ---- 终止结束事件（第 36 轮）----
            if (node.getType() == WfNodeType.TERMINATE_END_EVENT) {
                validateTerminateEndEvent(definition, node);
            }
            // ---- 取消结束事件（第 38 轮）----
            if (node.getType() == WfNodeType.CANCEL_END_EVENT) {
                validateCancelEndEvent(node, definition);
            }
            // ---- 退化出来的节点：语义已被换掉，必须挡住部署 ----
            // 这里报 ERROR 而不是 WARN：WARN 只进日志，部署照过，
            // 于是作者拿到的运行行为（人工任务）与他写的流程（自动分支）永久不一致。
            String unsupported = node.unsupportedBpmnElement();
            if (unsupported != null) {
                add(WfValidationIssue.Severity.ERROR, node.getId(),
                        "BPMN 元素 <" + unsupported + "> 本引擎尚不支持，已被当作人工任务，"
                                + "但两者的运行语义不同（该节点会停在等人办理，而不是按 "
                                + unsupported + " 的规则自动推进）。"
                                + substitutionHint(unsupported));
            }
        }

        // ---- 连线 ----
        List<WfFlow> flows = definition.getFlows();
        if (flows != null) {
            for (WfFlow flow : flows) {
                if (flow == null) {
                    add(WfValidationIssue.Severity.ERROR, null, "存在 null 连线");
                    continue;
                }
                if (isBlank(flow.getSourceRef())) {
                    add(WfValidationIssue.Severity.ERROR, null, "连线缺少 sourceRef");
                } else if (!ids.contains(flow.getSourceRef())) {
                    add(WfValidationIssue.Severity.ERROR, flow.getSourceRef(),
                            "连线 sourceRef 指向不存在的节点: " + flow.getSourceRef());
                }
                if (isBlank(flow.getTargetRef())) {
                    add(WfValidationIssue.Severity.ERROR, null, "连线缺少 targetRef");
                } else if (!ids.contains(flow.getTargetRef())) {
                    add(WfValidationIssue.Severity.ERROR, flow.getTargetRef(),
                            "连线 targetRef 指向不存在的节点: " + flow.getTargetRef());
                }
            }
        }

        // ---- 关联线（第 37 轮）----
        validateAssociations(definition, ids);

        validateGraphShape(definition, ids);

        return result();
    }

    /**
     * 补偿相关节点的结构合法性（第 37 轮）。
     *
     * <p>覆盖两类节点：
     * <ul>
     *   <li><b>补偿处理器</b>（{@code isForCompensation="true"}）</li>
     *   <li><b>补偿边界事件</b>（含 {@code <compensateEventDefinition/>}）</li>
     * </ul>
     *
     * <p>每一条规则挡掉的都是「能部署、补偿静默不发生或发生错」：
     * 补偿不发生时流程照常跑完，<b>全程没有任何报错</b>，
     * 业务上要等到「该退的款没退」才有人发现。
     */
    private void validateCompensationNode(WfDefinition definition, WfNode node) {
        if (node.isForCompensation()) {
            validateCompensationHandler(definition, node);
        }
        if (node.isCompensationBoundary()) {
            validateCompensationBoundary(definition, node);
        }
    }

    /**
     * 补偿处理器必须是「<b>能一次跑完</b>的活动」，且不能挂在流程路径上。
     *
     * <p>三条规则：
     * <ol>
     *   <li><b>类型要是同步可完成的活动</b>。补偿的语义是「退完了才继续」，
     *       所以处理器必须当场跑完；人工任务要等人、子流程要建自己的 token 树、
     *       网关要分叉 —— 这些都会把撤销卡在半路。
     *       放行人工补偿的具体后果：补偿建出一条待办，而实例并没有因此挂起，
     *       于是撤销永远等不到它办结，后续步骤在「退了一半」的状态下继续跑。</li>
     *   <li><b>不能有入线</b>。处理器在正常路径上永远不会被 token 走到；
     *       给它接一条入线，症状是「补偿 handler 出现在正常流程里」——
     *       真的会被执行一次（当它是普通后继节点），于是退款在正轨上先退了一遍。</li>
     *   <li><b>不能有出线</b>。同理，接了出线意味着作者期待它往下走，
     *       而补偿执行完就结束了，那条线永远走不到。</li>
     * </ol>
     */
    private void validateCompensationHandler(WfDefinition definition, WfNode node) {
        WfNodeType type = node.getType();
        // 这四种的 behavior 都是「调一段外部逻辑然后立刻返回」，当场跑完。
        // userTask 建待办、subProcess/callActivity 建 token 树、网关分叉 ——
        // 它们都需要「引擎停在这里等一会儿」，而补偿没有那个等待点。
        boolean executable = type == WfNodeType.SERVICE_TASK
                || type == WfNodeType.SCRIPT_TASK
                || type == WfNodeType.SEND_TASK
                || type == WfNodeType.BUSINESS_RULE_TASK;
        if (!executable) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "isForCompensation=\"true\" 只能标在能一次跑完的活动上"
                            + "（serviceTask / scriptTask / sendTask / businessRuleTask），"
                            + "当前节点类型是 " + type + "。"
                            + (type == WfNodeType.USER_TASK
                                    ? "人工补偿任务要等人办结，而补偿的语义是「退完了才继续」——"
                                    + "本引擎当前没有「实例挂起等人办结补偿后再恢复」的状态机，"
                                    + "放行的话撤销会建出一条待办并停在半路"
                                    : "它需要 token 树或分叉才能执行，"
                                    + "而补偿触发时没有可用的上下文"));
        }
        if (!definition.incomingFlows(node.getId()).isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "补偿处理器 " + node.getId() + " 不能有入线。"
                            + "它在正常路径上不会被 token 走到（只有 <association> 能在补偿时触发它），"
                            + "接了入线却会被当成普通后继节点真的执行一次 —— "
                            + "于是补偿动作在流程正轨上先发生了一遍");
        }
        if (!definition.outgoingFlows(node.getId()).isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "补偿处理器 " + node.getId() + " 不能有出线。"
                            + "补偿执行完就结束了，接的出线永远走不到；"
                            + "要继续往下走请用正常的 sequenceFlow 另起一条路径");
        }
        // 补偿处理器上不得再挂补偿边界事件 —— 这条是<b>防递归</b>，不是防画错。
        // 处理器完成时会被登记成一条新的可补偿登记（它确实是「做完的一件事」），
        // 下次撤销就会再触发它，而它触发的又是自己 ⇒ 每次撤销都真的执行一遍退款，
        // 且这个循环只在同一作用域被撤销第二次时才会显形，排查时极难看出因果。
        List<WfNode> nestedBoundaries = definition.compensationBoundariesOf(node.getId());
        if (!nestedBoundaries.isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (WfNode boundary : nestedBoundaries) {
                ids.add(boundary.getId());
            }
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "补偿处理器 " + node.getId() + " 上不能挂补偿边界事件: " + ids
                            + "。它完成时会被登记成新的可补偿项，下次撤销就会再触发它 —— "
                            + "而它触发的又是自己，于是每撤销一次就真的执行一遍退款。"
                            + "「退什么」写在普通活动的边界事件上，「怎么退」才放在处理器上");
        }
    }

    /**
     * 补偿边界事件：必须能找到它要执行的处理器。
     *
     * <p>「找不到处理器」是本机制最隐蔽的一种坏法：定义部署成功、补偿边界事件挂在图上、
     * 触发时引擎找不到要执行的东西 ⇒ 什么都不发生，且没有报错。
     */
    private void validateCompensationBoundary(WfDefinition definition, WfNode boundary) {
        if (definition.compensationHandlersOf(boundary.getId()).isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, boundary.getId(),
                    "补偿边界事件 " + boundary.getId() + " 没有任何关联的补偿处理器。"
                            + "要用 <association sourceRef=\"" + boundary.getId()
                            + "\" targetRef=\"处理器id\"/> 把它们连起来。"
                            + "不连的话补偿被触发时无事发生，而流程照常跑完，没有任何报错");
        }
        String activityRef = boundary.compensationActivityRef();
        if (activityRef != null && definition.node(activityRef) == null) {
            add(WfValidationIssue.Severity.ERROR, boundary.getId(),
                    "compensateEventDefinition 的 activityRef 指向不存在的活动: " + activityRef
                            + "。补偿会去找那个活动的补偿处理器，而它在这份定义里不存在");
        }
        if (boundary.getType() != WfNodeType.BOUNDARY_EVENT) {
            add(WfValidationIssue.Severity.ERROR, boundary.getId(),
                    "compensateEventDefinition 只能写在 boundaryEvent 上，当前节点类型是 "
                            + boundary.getType() + "。补偿边界事件靠宿主 token 仍在该活动上才触发");
        }
    }

    /**
     * {@code <association>} 的两端必须是补偿结构（第 37 轮）。
     *
     * <p>端点写错的 association 不会被任何其他规则兜住 —— 它既不是节点也不是连线，
     * 连线级校验看不到它，而它静默失效的后果是「补偿不发生」。
     */
    private void validateAssociations(WfDefinition definition, Set<String> ids) {
        List<WfAssociation> associations = definition.getAssociations();
        if (associations == null) {
            return;
        }
        for (WfAssociation association : associations) {
            if (association == null) {
                add(WfValidationIssue.Severity.ERROR, null, "存在 null 关联线");
                continue;
            }
            String sourceId = association.getSourceRef();
            String targetId = association.getTargetRef();
            WfNode source = sourceId == null ? null : definition.node(sourceId);
            WfNode target = targetId == null ? null : definition.node(targetId);
            if (source == null) {
                add(WfValidationIssue.Severity.ERROR, sourceId,
                        "association " + nameOf(association) + " 的 sourceRef 指向不存在的节点: " + sourceId);
                continue;
            }
            if (target == null) {
                add(WfValidationIssue.Severity.ERROR, targetId,
                        "association " + nameOf(association) + " 的 targetRef 指向不存在的节点: " + targetId);
                continue;
            }
            if (!source.isCompensationBoundary()) {
                add(WfValidationIssue.Severity.ERROR, source.getId(),
                        "association " + nameOf(association) + " 的 source 端 " + source.getId()
                                + " 不是补偿边界事件（没有 <compensateEventDefinition/>），"
                                + "类型是 " + source.getType() + "。它不会被补偿机制触发，"
                                + "这条关联等于没有");
            }
            if (!target.isForCompensation()) {
                add(WfValidationIssue.Severity.ERROR, target.getId(),
                        "association " + nameOf(association) + " 的 target 端 " + target.getId()
                                + " 没有标 isForCompensation=\"true\"。"
                                + "补偿执行的是一个反向动作，而这个节点在正常路径上就会被 token 走到 —— "
                                + "把它当补偿处理器会让同一段逻辑跑两遍");
            }
        }
    }

    private String nameOf(WfAssociation association) {
        return association.getId() == null ? "(无 id)" : association.getId();
    }

    /**
     * 同一条消息 / 信号不得对应两个起始节点。     *
     * <p>分两类报：<b>同一个定义内</b>重复是定义本身有问题；
     * <b>同一 key 的不同版本</b>重复则是升级时新增了一个同名起始节点 ——
     * 后者同样要报，因为"按消息启动"是不带版本号找定义的，
     * 两个版本都匹配时调用方没有参数可以消除歧义。
     */
    private void checkEventStartAmbiguity(List<WfNode> eventStarts) {
        Map<String, List<String>> byMessage = new LinkedHashMap<String, List<String>>();
        Map<String, List<String>> bySignal = new LinkedHashMap<String, List<String>>();
        for (WfNode node : eventStarts) {
            if (node.isMessageEvent()) {
                put(byMessage, node.getMessageName(), node.getId());
            }
            if (node.isSignalEvent()) {
                put(bySignal, node.getSignalName(), node.getId());
            }
        }
        reportAmbiguity(byMessage, "消息");
        reportAmbiguity(bySignal, "信号");
    }

    private void put(Map<String, List<String>> map, String name, String id) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        List<String> ids = map.get(name.trim());
        if (ids == null) {
            ids = new ArrayList<String>();
            map.put(name.trim(), ids);
        }
        ids.add(id);
    }

    private void reportAmbiguity(Map<String, List<String>> byEvent, String kind) {
        for (Map.Entry<String, List<String>> entry : byEvent.entrySet()) {
            if (entry.getValue().size() > 1) {
                add(WfValidationIssue.Severity.ERROR, entry.getValue().get(0),
                        kind + " [" + entry.getKey() + "] 对应多个起始节点: " + join(entry.getValue())
                                + "。startProcessInstanceBy" + kind + " 不带版本号，"
                                + "两个都匹配时调用方没有参数能消除歧义");
            }
        }
    }

    private String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(value);
        }
        return sb.toString();
    }

    /**
     * 图形状校验：开始节点唯一性、网关默认流、死节点、死胡同。
     */
    private void validateGraphShape(WfDefinition definition, Set<String> ids) {
        definition.buildIndex();

        // ---- 起始节点：无条件的至多一个，事件起始的名字不得重复 ----
        // 刻意**不**要求"恰好一个 startEvent"：BPMN 里"收到订单才起流程"
        // 与"手工发起"是同一流程的两个入口，共存是正常写法。
        // 要求唯一会把这类定义整条挡在部署期，而它们的作者没有任何办法绕开 ——
        // 只能拆成两个流程，那是把建模限制转嫁到业务上。
        List<WfNode> plainStarts = definition.unconditionalStartNodes();
        List<WfNode> eventStarts = definition.eventStartNodes();
        if (plainStarts.isEmpty() && eventStarts.isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, null, "找不到开始节点（无 startEvent 且无无入线节点）");
        }
        if (plainStarts.size() > 1) {
            List<String> startIds = new ArrayList<String>();
            for (WfNode n : plainStarts) {
                startIds.add(n.getId());
            }
            add(WfValidationIssue.Severity.ERROR, null, "存在多个无条件开始节点: " + join(startIds)
                    + "。引擎无法判断 startProcessInstanceByKey 该从哪进入 —— "
                    + "带 messageRef/signalRef 的起始事件不算在内，那种靠消息启动");
        }
        // 同一条消息对应两个起始节点时，"收到这条消息该起哪个流程"没有答案。
        // 部署期拦掉，而不是等到运行时收到消息才抛 —— 那时候调用方已经在
        // 发消息的路上了，错误会出现在一个与配置毫无关系的地方
        checkEventStartAmbiguity(eventStarts);

        // ---- 网关出线规则 ----
        for (String id : ids) {
            WfNode node = definition.node(id);
            if (node == null || node.getType() == null || !node.getType().isGateway()) {
                continue;
            }
            // complexJoin 要对**每一个网关**跑一次，包括排他与并行 ——
            // 它在那些网关上同样要报错（恒定语义的东西没有可配项），
            // 而挂在 validateComplexGateway 里就只有复杂网关够得着，
            // 于是"我在排他网关上写了穿透"被静默放过。
            // 放在 outs.isEmpty() 的 continue **之前**：连出线都没有的网关
            // 写这个属性同样是无效声明，不该因为它先 WARN 掉了就不看。
            validateComplexJoin(id, node);
            validateActivationCondition(id, node);
            List<WfFlow> outs = definition.outgoingFlows(id);
            if (outs.isEmpty()) {
                add(WfValidationIssue.Severity.WARN, id,
                        node.getType() + " 没有出线，token 抵达后流程将直接结束");
                continue;
            }
            int defaultCount = 0;
            int unconditional = 0;
            for (WfFlow f : outs) {
                if (f.isDefaultFlow()) {
                    defaultCount++;
                }
                if (f.isUnconditional()) {
                    unconditional++;
                }
            }
            if (defaultCount > 1) {
                add(WfValidationIssue.Severity.ERROR, id, "同一网关上有多条 defaultFlow（至多允许 1 条）");
            }
            if (node.getType() == WfNodeType.EXCLUSIVE_GATEWAY && outs.size() > 1
                    && defaultCount == 0 && unconditional == 0) {
                add(WfValidationIssue.Severity.ERROR, id,
                        "排他网关有 " + outs.size() + " 条出线但既无 defaultFlow 也无条件连线，"
                                + "当所有条件都不成立时流程将卡死");
            }
            if (node.getType() == WfNodeType.COMPLEX_GATEWAY) {
                validateComplexGateway(id, node, outs, defaultCount);
            }
            if (node.getType() == WfNodeType.EVENT_BASED_GATEWAY) {
                validateEventGateway(definition, id, outs);
            }
        }

        // ---- 事件网关分支：出线目标必须是中间捕获事件，且入线唯一 ----
        // 这条放在图形状校验里而不是节点校验里，因为它要看线（两个方向都要看），
        // 而"分支的兄弟集合"在运行时正是靠入线反查出来的 —— 部署期不把这条锁死，
        // 触发时就找不到该作废谁。
        for (String id : ids) {
            WfNode node = definition.node(id);
            if (node == null || node.getType() != WfNodeType.INTERMEDIATE_CATCH_EVENT) {
                continue;
            }
            validateCatchEvent(definition, id, node, definition.incomingFlows(id));
        }

        // ---- 中间抛出事件 ----
        // 放在图形状校验这一层：它要同时看节点自身与挂在它上面的边界事件
        for (String id : ids) {
            WfNode node = definition.node(id);
            if (node == null || node.getType() != WfNodeType.THROW_EVENT) {
                continue;
            }
            validateThrowEvent(definition, id, node);
        }

        // ---- 链接事件 ----
        // 同样在这一层：throw 与 catch 的配对是跨节点的，一个循环里看不到对面。
        validateLinkEvents(definition, ids);

        // ---- 孤立节点 ----
        for (String id : ids) {
            WfNode node = definition.node(id);
            if (node == null) {
                continue;
            }
            if (definition.incomingFlows(id).isEmpty() && definition.outgoingFlows(id).isEmpty()) {
                add(WfValidationIssue.Severity.WARN, id, "节点 " + id + " 是孤立节点（无入线也无出线），运行时不可达");
            }
        }
    }

    /**
     * 复杂网关的出线规则。
     *
     * <p>三条规则各自挡掉一种"能部署、永远走不通或永远走错"的写法：
     * 缺判别变量 ⇒ 永远走默认线；一条 caseValue 都没有 ⇒ 永远走默认线；
     * 没有默认流 ⇒ 判别变量的值一变，token 就永久停留。
     */
    /**
     * 复杂网关：两种判定方式，各有各的硬要求。
     *
     * <p><b>取值分派</b>（网关配了判别变量）：走第一条 caseValue 相同的线，只走一条。
     * <b>条件分派</b>（网关不配判别变量）：出线条件成立的线全部激活。
     *
     * <p><b>两种方式不许混用</b>：同一个网关上既有 caseValue 出线又有条件出线时，
     * "走哪几条"没有唯一答案 —— 而猜错的后果是流程走上一组作者没想过的分支。
     * 所以这里报 ERROR 而不是挑一种执行。
     *
     * <p>此前本方法只认取值分派，于是出线上的 {@code <conditionExpression>}
     * 无人问津：一条只带条件的线永远不会被选中，流程静默落到默认线。
 */
    private void validateComplexGateway(String id, WfNode node, List<WfFlow> outs,
                                       int defaultCount) {
        boolean valueMode = !isBlank(node.getCaseVariable());
        int withCaseValue = 0;
        int withCondition = 0;
        for (WfFlow f : outs) {
            boolean hasCase = !isBlank(f.getCaseValue());
            boolean hasCondition = !isBlank(f.getConditionExpression());
            if (hasCase) {
                withCaseValue++;
            }
            if (hasCondition) {
                withCondition++;
            }
            // 两种判定方式都配时，引擎只会用 caseValue。留着 conditionExpression
            // 的人会以为"条件不成立就顺延到下一条"，而实际是根本不看它
            if (hasCase && hasCondition) {
                add(WfValidationIssue.Severity.ERROR, id,
                        "出线 " + f.getId() + " 同时配了 caseValue 与 conditionExpression，"
                                + "只能留一个。复杂网关比的是取值等于，不是条件成立与否");
            }
        }

        if (valueMode) {
            if (withCaseValue == 0) {
                add(WfValidationIssue.Severity.ERROR, id,
                        "complexGateway 配了判别变量（" + node.getCaseVariable() + "），"
                                + "但 " + outs.size() + " 条出线没有一条配 caseValue，"
                                + "无论判别变量是什么值都会走默认线");
            }
            if (withCondition > 0) {
                add(WfValidationIssue.Severity.ERROR, id,
                        "complexGateway 配了判别变量（取值分派），却有出线带 conditionExpression"
                                + "（条件分派）。两种判定方式混用时「走哪几条」没有唯一答案 —— "
                                + "要么去掉判别变量改用条件，要么把出线条件去掉");
            }
        } else {
            if (withCondition == 0 && !singleUnconditionalOut(outs)) {
                add(WfValidationIssue.Severity.ERROR, id,
                        "complexGateway 既没有判别变量（" + node.getCaseVariable() + "），"
                                + "出线也没有一条带条件 —— 两种判定方式都没有，"
                                + "无论变量是什么值都会走默认线。"
                                + "要按取值分派请配 zifang:caseVariable，"
                                + "要按条件分派请给出线加 <conditionExpression>");
            }
            if (withCaseValue > 0) {
                add(WfValidationIssue.Severity.ERROR, id,
                        "complexGateway 没配判别变量，却有出线带 caseValue（取值分派）。"
                                + "要么配上 zifang:caseVariable，要么把 caseValue 换成条件表达式");
            }
        }
        // **默认流只对"可能一条都不成立"的分派有意义**：
        // 纯汇合网关（只有一条无条件出线）永远会走它，不存在"都不成立"这种情况，
        // 而要求它配默认流等于逼作者加一条永远走不到的线。
        if (defaultCount == 0 && !singleUnconditionalOut(outs)) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "complexGateway 没有默认流。判别变量的值不匹配任何 caseValue、"
                            + "或所有出线条件都不成立时，token 会永久停留在这里，"
                            + "而流程不会报任何错");
        }
    }

    /**
     * 复杂网关的汇合方式取值。
     *
     * <p><b>非法取值必须报错而不是退到默认值</b>：兜底方向虽然选了更保守的 joining
     * （理由见 {@code WfNode#isCompetingJoin}），但那是给"绕过校验"兜底的。
     * 部署期直接放过的话，作者把 {@code competing} 拼成 {@code compete}
     * 之后拿到的是"我明明写了穿透，怎么还是合并了"，而报错里只有一条是可选值清单，
     * 没有第二条路径要查。
     *
     * <p><b>单入线的网关不报</b>：它没有"汇合"可言，写什么都不影响行为，
     * 为此挡下部署是误伤。
     */
    private void validateComplexJoin(String id, WfNode node) {
        String raw = node.getComplexJoin();
        if (isBlank(raw)) {
            return;
        }
        String value = raw.trim();
        if (node.getType() != WfNodeType.COMPLEX_GATEWAY) {
            // 只有复杂网关两边都说得通。并行/包容恒为合并、排他恒为穿透，
            // 让人在这些网关上写这个属性，等于写一句与引擎行为相反的话
            add(WfValidationIssue.Severity.ERROR, id,
                    node.getId() + " 是 " + node.getType().bpmnName()
                            + "，没有 complexJoin 这回事。排他网关恒为穿透"
                            + "（对齐 Camunda：joining gateway has a pass-through semantic），"
                            + "并行与包容网关恒为合并。"
                            + "要在合并与穿透之间选，请改用 complexGateway");
            return;
        }
        if (!"joining".equalsIgnoreCase(value) && !"competing".equalsIgnoreCase(value)) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "complexGateway 的 zifang:complexJoin 只能是 joining（默认，"
                            + "等所有到达的 token 再合并成一条）或 competing"
                            + "（穿透，每条到达的 token 各自往下走）。实际写了 " + raw);
        }
    }

    /**
     * 是否是<b>纯汇合</b>的复杂网关：只有一条出线，且它无条件、也不是默认流。
     *
     * <p>这种网关没有分派可做 —— {@code selectFlows} 那条无条件出线永远被选中，
     * 根本走不到「都不成立」的分支。它的复杂之处全在<b>汇合</b>侧
     * （{@code complexJoin} / {@code activationCondition}），
     * 那是 {@link #validateComplexJoin} 与 {@link #validateActivationCondition} 管的。
     *
     * <p><b>不豁免的情形</b>：出线带条件（那确实是分派，条件不成立时 token 会停）、
     * 出线是默认流（语义不同）、出线多于一条。
     */
    private boolean singleUnconditionalOut(List<WfFlow> outs) {
        if (outs.size() != 1) {
            return false;
        }
        WfFlow only = outs.get(0);
        return only.isUnconditional() && !only.isDefaultFlow()
                && isBlank(only.getCaseValue()) && isBlank(only.getConditionExpression());
    }

    /**
     * 复杂网关的汇合阈值 {@code zifang:activationCondition}。
     *
     * <p>三件事必须在部署期问出来，理由逐条说：
     * <ol>
     *   <li><b>只能写在复杂网关上。</b>排他恒穿透、并行/包容恒全到齐 ——
     *       在它们身上写阈值等于写一句与引擎行为相反的话（与 complexJoin 同理）。</li>
     *   <li><b>与 {@code complexJoin="competing"} 互斥。</b>穿透是"每条 token 各自往下"，
     *       根本没有"攒够几条"这个概念。两者同时配时若不报错，运行期二选一，
     *       而作者无从知道引擎选了哪个 —— 症状是"我配了 2 取 3，怎么等齐了才走"。</li>
     *   <li><b>必须是正整数。</b>非正整数（含 0、负数、非数字）一律 ERROR 而不是退到默认值：
     *       退到"等齐"的话症状是"我配了 1 想让第一条到就走，结果还是全等齐"，
     *       而报错里写着"只能是正整数"，比让人自己猜要快得多。</li>
     * </ol>
     *
     * <p><b>不检查阈值是否超过入线数</b>：那是运行期的数据（只激活了 2 条分支时
     * 阈值 3 就永远等不到），而网关的入线里可能有<b>永远不会到达</b>的那几条
     * （条件不成立、被边界事件终止）—— 拿图上的入线数去卡阈值，
     * 会把一个完全正常的 2/3 会签模型挡在部署门外。
     * 那种"永远等不到"的情况在运行期有明确的处理（见 {@code WfEngine}）并留痕。
     */
    private void validateActivationCondition(String id, WfNode node) {
        String raw = node.getActivationCondition();
        if (isBlank(raw)) {
            return;
        }
        if (node.getType() != WfNodeType.COMPLEX_GATEWAY) {
            add(WfValidationIssue.Severity.ERROR, id,
                    node.getId() + " 是 " + node.getType().bpmnName()
                            + "，没有 activationCondition 这回事。排他网关恒为穿透、"
                            + "并行与包容网关恒为全到齐合并，都没有阈值可言。"
                            + "要「N 条到齐就放行」，请改用 complexGateway");
            return;
        }
        if (node.isCompetingJoin()) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "complexGateway 同时配了 zifang:complexJoin=\"competing\" 与 "
                            + "zifang:activationCondition=\"" + raw + "\"，两者互斥："
                            + "competing 是穿透（每条 token 各自往下），"
                            + "而阈值说的是「攒够几条再合并」。要阈值就去掉 competing，"
                            + "要穿透就去掉 activationCondition");
            return;
        }
        if (node.activationThreshold() < 0) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "complexGateway 的 zifang:activationCondition 只能是正整数"
                            + "（表示攒够几条分支的 token 就放行，不必等齐）。实际写了 " + raw
                            + "。0 与负数会让流程永远等不齐；"
                            + "本引擎只收整数，不收 BPMN 规范里的布尔表达式"
                            + "（那样要把引擎内部的计数塞进流程变量命名空间，迟早与业务变量撞名）");
        }
    }

    /**
     * 业务规则任务：它要求值哪张决策表、结果怎么映射。
     *
     * <p><b>不检查决策是否已部署</b> —— 流程与决策是两个服务、两条部署路径，
     * 先后顺序是自由的（先部署流程、补规则后再部署决策表是正常节奏）。
     * 为了一个"迟早会部署"的检查把部署挡在门外，比留到运行期报错更糟。
     * 运行期找不到决策时报的是"决策 [xxx] 不存在"，那一句话已经把话说全了。
     */
    private void validateBusinessRuleTask(WfNode node) {
        String id = node.getId();
        if (isBlank(node.getDecisionRef())) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "businessRuleTask 缺少 decisionRef。它要求值哪张决策表没有答案 —— "
                            + "不会退化成 serviceTask，因为业务方没有写任何 delegate，"
                            + "跑到这个节点必然失败");
        }
        if (isBlank(node.getResultVariable())) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "businessRuleTask 缺少 resultVariable。决策结果在本实现里**没有别的出口**"
                            + "（不像 Camunda 那样还能用 decisionResult 局部变量 + 输出映射），"
                            + "不给写进哪个变量的话这个节点等于什么都没做："
                            + "流程照常穿透，没有任何报错");
        }
        validateDecisionRefBinding(node);
        validateDecisionResultMapper(node);
    }

    /**
     * 版本绑定：只认 {@code latest} 与 {@code version}。
     *
     * <p>Camunda 另外两种写法<b>明确报错</b>而不是悄悄当成 latest：
     * {@code deployment} 需要"BPMN 与 DMN 同属一个部署单元"这个概念，
     * 而本仓两者分别部署（两个服务、两条 REST 端点），没有共享的部署单元；
     * {@code versionTag} 需要 {@code ZWF_DECISION} 上有标签列，而本仓的决策只有版本号。
     * 把它们当成 latest 会让流程在部署期通过、在运行期拿到一个**没人指定的版本**的规则。
     */
    private void validateDecisionRefBinding(WfNode node) {
        String id = node.getId();
        String binding = node.getDecisionRefBinding();
        if (isBlank(binding)) {
            return;
        }
        String normalized = binding.trim();
        if ("latest".equalsIgnoreCase(normalized)) {
            return;
        }
        if ("version".equalsIgnoreCase(normalized)) {
            if (isBlank(node.getDecisionRefVersion())) {
                add(WfValidationIssue.Severity.ERROR, id,
                        "businessRuleTask 用了 decisionRefBinding=\"version\" 却没有 decisionRefVersion —— "
                                + "要哪个版本没有答案，引擎只能去猜，"
                                + "而猜错的后果是同一份流程两次跑出不同结论");
            }
            return;
        }
        if ("deployment".equalsIgnoreCase(normalized)) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "decisionRefBinding=\"deployment\"（求与流程一起部署的那版决策）本实现不支持："
                            + "本仓的流程与决策是**分别部署**的，没有共享的部署单元，"
                            + "「一起部署的那版」这个东西不存在。请改用 latest 或 version");
            return;
        }
        if ("versionTag".equalsIgnoreCase(normalized)) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "decisionRefBinding=\"versionTag\"（按版本标签取决策）本实现不支持："
                            + "ZWF_DECISION 上只有版本号，没有标签列。"
                            + "请改用 decisionRefBinding=\"version\" + decisionRefVersion");
            return;
        }
        add(WfValidationIssue.Severity.ERROR, id,
                "未知的 decisionRefBinding [" + binding + "]。"
                        + "合法值: latest（默认）/ version。deployment 与 versionTag 本实现不支持，"
                        + "见上面各自的说明");
    }

    /** 结果映射：名字照 Camunda（它们描述的是结果的形状），但拼错要报出来。 */
    private void validateDecisionResultMapper(WfNode node) {
        String mapper = node.getMapDecisionResult();
        if (isBlank(mapper)) {
            return;
        }
        String normalized = mapper.trim();
        if ("singleEntry".equalsIgnoreCase(normalized)
                || "singleResult".equalsIgnoreCase(normalized)
                || "collectEntries".equalsIgnoreCase(normalized)
                || "resultList".equalsIgnoreCase(normalized)) {
            return;
        }
        add(WfValidationIssue.Severity.ERROR, node.getId(),
                "未知的 mapDecisionResult [" + mapper + "]。"
                        + "合法值: singleEntry / singleResult / collectEntries / resultList（默认）");
    }

    /**
     * 事件网关：出线必须全部落在中间捕获事件上，且至少两条。
     *
     * <p>出线指向别的节点不是"退化"，是<b>整个竞速机制落空</b>：引擎把 token
     * 分叉过去之后，只有中间捕获事件会挂订阅并停住。指向普通节点的话那些分支
     * 当场就跑完了，等事件到达时一条订阅都找不到，而流程已经走到结束 ——
     * 模型上写着"三选一"，实际是"三条全走"。所以必须在部署期拒绝。
     */
    private void validateEventGateway(WfDefinition definition, String id, List<WfFlow> outs) {
        if (outs.size() < 2) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "事件网关至少要有 2 条出线（BPMN 规范如此），当前只有 " + outs.size()
                            + " 条。少于两条就不存在「竞速」，它退化成了普通网关");
        }
        for (WfFlow flow : outs) {
            WfNode target = definition.node(flow.getTargetRef());
            if (target == null) {
                // 目标节点不存在由连线级校验报，这里不重复
                continue;
            }
            if (target.getType() != WfNodeType.INTERMEDIATE_CATCH_EVENT) {
                add(WfValidationIssue.Severity.ERROR, id,
                        "事件网关的出线 " + flow.getId() + " 指向 " + target.getId()
                                + "（" + target.getType() + "），必须是 intermediateCatchEvent。"
                                + "指向别的节点意味着该分支不会挂订阅、不会等事件，"
                                + "会在分叉当场直接跑完 —— 那是「三条全走」而不是「三选一」");
            }
        }
    }

    /**
     * 中间抛出事件：必须抛得出东西（三选一），且不能挂边界事件。
     *
     * <p><b>必须给事件引用</b>：{@code signalRef} / {@code messageRef} /
     * {@code escalationRef} 三个一个都不给时，节点在运行期会抛异常。
     * 若放到部署期不管，一份"抛了个空"的流程能部署成功，跑到那一步才炸 ——
     * 而作者看到的是"部署没问题"，排查方向会先跑到引擎上去。
     *
     * <p><b>只能给一个</b>：三者后果不同（广播叫醒 / 点对点 / 打断宿主），
     * 挑一个生效等于让作者以为自己写的那条没生效。
     *
     * <p><b>不得挂边界事件</b>：抛事件是<b>穿透</b>的 —— token 抵达即投出去并继续往下走，
     * 它在这个节点上不会停留。BPMN 允许给中间事件挂边界事件，
     * 但挂上去的边界会找一个"宿主 token"，而这条 token 在抛完就走了：
     * 结果是订阅挂上、永远不触发，且流程图上看不出任何异常。
     */
    private void validateThrowEvent(WfDefinition definition, String id, WfNode node) {
        boolean hasSignal = !isBlank(node.getSignalName());
        boolean hasMessage = !isBlank(node.getMessageName());
        boolean hasEscalation = !isBlank(node.getEscalationCode());
        // 三种事件按**配了几个**判，不是一串两两组合：
        // 写成 signal&&message / signal&&escalation / message&&escalation 的话，
        // 三者同时配只会命中第一条，于是"两个都配了升级"这种错法反而漏过去
        int kinds = (hasSignal ? 1 : 0) + (hasMessage ? 1 : 0) + (hasEscalation ? 1 : 0);
        if (kinds == 0) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "中间抛出事件 " + id + " 没有任何事件定义（signalEventDefinition / "
                            + "messageEventDefinition / escalationEventDefinition），"
                            + "它抛不出任何东西。"
                            + "要发通知给业务方，请改用 sendTask + delegate");
        }
        if (kinds > 1) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "中间抛出事件 " + id + " 同时配了多种事件（signalRef=" + node.getSignalName()
                            + " / messageRef=" + node.getMessageName()
                            + " / escalationRef=" + node.getEscalationCode()
                            + "）。三者语义不同（广播叫醒 / 点对点 / 打断宿主），"
                            + "本引擎不挑一个生效 —— 请拆成多个连续节点");
        }
        for (WfNode boundary : definition.eventBoundariesOf(id)) {
            String boundaryId = boundary.getId();
            add(WfValidationIssue.Severity.ERROR, id,
                    "中间抛出事件 " + id + " 上挂了边界事件 " + boundaryId
                            + "。抛事件是**穿透**的：token 抵达即投递并继续往下走，"
                                    + "不会在本节点停留。边界事件需要一个停在宿主上的 token，"
                                    + "挂上去只会得到一个永远不触发的哑订阅，"
                                    + "且流程图上看不出任何异常。"
                                    + "要「抛出去的同时也可能被打断」，"
                                    + "请把抛事件与被打断的节点分开画");
        }
    }

    /**
     * 链接事件（{@code linkThrowEvent} / {@code linkCatchEvent}）的全部部署期校验。
     *
     * <p>放这一层而不是节点遍历里：每一条都要看<b>别的</b>节点 —— throw 要查
     * 有没有对应的 catch，catch 要反查有没有 throw 指向它。同一条问题
     * （比如"三个 catch 抢同一个名字"）在一个循环里只能报出第一个。
     *
     * <p><b>语义基线（与 Camunda 一致）</b>：
     * <ul>
     *   <li>配对键是元素自己的 {@code @name}，作用域<b>限定在本流程定义内</b>；
     *       跨定义的全局匹配会让"跳去哪里"取决于部署里还有哪些别的流程。</li>
     *   <li>一个 throw 只能对应<b>一个</b> catch；多个 throw 可以汇到同一个 catch。</li>
     *   <li>token 从 throw 改道到 catch 后，沿 <b>catch 自己</b>的出线走，
     *       <b>throw 的出线不会被 token 走过</b>。</li>
     * </ul>
     *
     * <p>第三条与 escalation <b>恰好相反</b>（Camunda 的 escalation 文档明写
     * "if the throwing event has any outgoing sequence flows, they will be taken"），
     * 所以这两处不能互相参照着写 —— 一旦照抄，link 的出线会被当成有效路径，
     * 症状是"多跑了一段作者以为跳过了的流程"，且图上完全看不出异常。
     */
    private void validateLinkEvents(WfDefinition definition, Set<String> ids) {
        Map<String, List<WfNode>> catchesByLink = new LinkedHashMap<String, List<WfNode>>();
        List<WfNode> linkThrows = new ArrayList<WfNode>();

        for (String id : ids) {
            WfNode node = definition.node(id);
            if (node == null) {
                continue;
            }
            boolean isThrow = node.getType() == WfNodeType.LINK_THROW;
            boolean isCatch = node.getType() == WfNodeType.LINK_CATCH;
            if (!isThrow && !isCatch) {
                continue;
            }
            String label = (isThrow ? "链接抛出事件 " : "链接捕获事件 ") + id;

            // ---- 缺 name：既跳不过去，也跳不过来 ----
            if (isBlank(node.getLinkName())) {
                add(WfValidationIssue.Severity.ERROR, id,
                        label + " 没有 name。链接事件的 name 就是 link 名 —— "
                                + "throw 靠它找到 catch，catch 靠它被 throw 找到，"
                                + "两者都没有第二个地方可以配对。"
                                + (isThrow
                                ? "token 抵达后无处可去，只能停成内部终止"
                                : "它不会被任何 token 抵达，它后面的整段流程都不会执行"));
                continue;
            }
            String linkName = node.getLinkName().trim();

            // ---- 不许挂边界事件：两个方向都是穿透的 ----
            for (WfNode boundary : definition.eventBoundariesOf(id)) {
                add(WfValidationIssue.Severity.ERROR, id,
                        label + " 上挂了边界事件 " + boundary.getId()
                                + "。链接事件是**穿透**的：catch 不停留，"
                                + "throw 更是瞬间改道。边界事件需要一个停在宿主上的 token，"
                                + "挂上去只会得到一个永远不触发的哑订阅，"
                                + "且流程图上看不出任何异常");
            }

            if (isThrow) {
                linkThrows.add(node);
            } else {
                List<WfNode> bucket = catchesByLink.get(linkName);
                if (bucket == null) {
                    bucket = new ArrayList<WfNode>();
                    catchesByLink.put(linkName, bucket);
                }
                bucket.add(node);
            }
        }

        // ---- 同名 catch 多个 ----
        for (Map.Entry<String, List<WfNode>> entry : catchesByLink.entrySet()) {
            List<WfNode> bucket = entry.getValue();
            if (bucket.size() > 1) {
                add(WfValidationIssue.Severity.ERROR, bucket.get(0).getId(),
                        "link 名 [" + entry.getKey() + "] 对应多个链接捕获事件: "
                                + join(nodeIdsOf(bucket))
                                + "。一个链接抛出事件只能跳到一个落点 —— "
                                + "「跳到哪一个」在此时没有答案，引擎只能去猜，"
                                + "而猜错的表现是流程走进了另一个完全正确的分支。");
            }
        }

        // ---- throw 找不到落点 ----
        for (WfNode throwNode : linkThrows) {
            String linkName = throwNode.getLinkName().trim();
            List<WfNode> bucket = catchesByLink.get(linkName);
            if (bucket == null || bucket.isEmpty()) {
                add(WfValidationIssue.Severity.ERROR, throwNode.getId(),
                        "链接抛出事件 " + throwNode.getId() + " 找不到 link 名 ["
                                + linkName + "] 对应的链接捕获事件。"
                                + "本流程里叫这个名字的 catch 一个都没有，跳过去无处可去 —— "
                                + "这类错误留到运行期只会表现为「某一次执行突然停了」，"
                                + "而真正的原因是流程图上少画了一个点");
            }
        }

        // ---- catch 没人跳过来 ----
        for (Map.Entry<String, List<WfNode>> entry : catchesByLink.entrySet()) {
            boolean jumped = false;
            for (WfNode throwNode : linkThrows) {
                if (entry.getKey().equals(throwNode.getLinkName().trim())) {
                    jumped = true;
                    break;
                }
            }
            if (!jumped) {
                // WARN 不是 ERROR：设计期"先画好 catch、之后再补 throw"是正常画法，
                // 而 link catch 被 token 抵达时是穿透的、它不会等任何人，
                // 所以一个没人跳的 catch 不是"少了一步"，是"后面整段永远不跑"。
                // 但部署这一刻它尚未真的失效，堵死它会拦住正在画的半成品图。
                add(WfValidationIssue.Severity.WARN, entry.getValue().get(0).getId(),
                        "链接捕获事件 " + entry.getValue().get(0).getId()
                                + "（link 名 [" + entry.getKey() + "]）"
                                + "没有任何链接抛出事件指向它，token 永远不会抵达，"
                                + "它后面的整段流程都不会执行。"
                                + "若本意是「用连线走到这里」，那不该用 link catch");
            }
        }

        // ---- catch 的入线 / 出线 ----
        for (String id : ids) {
            WfNode node = definition.node(id);
            if (node == null || node.getType() != WfNodeType.LINK_CATCH) {
                continue;
            }
            String label = "链接捕获事件 " + id;
            List<WfFlow> ins = definition.incomingFlows(id);
            if (ins != null && !ins.isEmpty()) {
                // 报 ERROR 而不是 WARN：token 只由 link 改道进入 catch，
                // 那条连线**永远不会被 token 走过**，于是它上游的整段流程
                // 静默失效 —— 作者以为会跑，实际一次都没跑过。
                // 这比「出线是摆设」严重得多，后者至少不隐藏一整段流程。
                add(WfValidationIssue.Severity.ERROR, id,
                        label + " 有入线（来自 " + join(sourceRefsOf(ins))
                                + "）。token 只能由链接抛出事件改道进入 catch，"
                                + "这条连线永远不会被走过，它上游的整段流程一次都不会执行，"
                                + "且流程图上完全看不出异常。"
                                + "要那条路径真的生效，请直接用 sequenceFlow 接到这个节点上、"
                                + "改用普通任务，而不是保留一个 link catch");
            }
            if (definition.outgoingFlows(id).isEmpty()) {
                add(WfValidationIssue.Severity.ERROR, id,
                        label + " 没有出线。token 从 throw 跳过来后无处可去，"
                                + "流程会在这里直接结束 —— 而作者图上画的是"
                                + "「跳到这一段」，不是「跳到终点」");
            }
        }

        // ---- throw 的出线：允许画着，但不走 ----
        for (String id : ids) {
            WfNode node = definition.node(id);
            if (node == null || node.getType() != WfNodeType.LINK_THROW) {
                continue;
            }
            List<WfFlow> outs = definition.outgoingFlows(id);
            if (outs == null || outs.isEmpty()) {
                continue;
            }
            add(WfValidationIssue.Severity.WARN, id,
                    "链接抛出事件 " + id + " 有 " + outs.size()
                            + " 条出线，但**token 不会走过它们**："
                            + "link 抛出事件是「把 token 改道到 catch」，"
                            + "改道之后就在 catch 处沿 catch 自己的出线继续了。"
                            + "（这与 escalation 恰好相反 —— 抛出去的 escalation "
                            + "会让 throw 自己的出线也被走掉。）"
                            + "若本意是「跳过去之后还继续走这一段」，"
                            + "那不该用 link throw：把这段接在 catch 的出线上");
        }
    }

    private List<String> sourceRefsOf(List<WfFlow> flows) {
        List<String> refs = new ArrayList<String>();
        for (WfFlow flow : flows) {
            refs.add(flow.getSourceRef());
        }
        return refs;
    }

    /** 节点 id 清单（诊断信息用，与 {@code WfEngine#idsOf} 同名不同类 —— 那边是运行期的）。 */
    private List<String> nodeIdsOf(List<WfNode> nodes) {
        List<String> ids = new ArrayList<String>();
        for (WfNode node : nodes) {
            ids.add(node.getId());
        }
        return ids;
    }

    /**
     * 中间捕获事件：必须有本引擎等得住的事件定义，入线必须唯一且来自事件网关。
     */
    private void validateCatchEvent(WfDefinition definition, String id, WfNode node,
                                    List<WfFlow> inFlows) {
        if (!node.hasEventDefinition()) {
            // 带了 conditionalEventDefinition 时要点出「条件不提供事件类型」：
            // 作者很可能以为条件式事件自己就能等，而这句话是唯一能纠正那个误解的地方。
            // 不点的话报错只说"没有任何事件定义"，读起来像"条件式事件在本引擎里完全没被识别"。
            add(WfValidationIssue.Severity.ERROR, id,
                    "中间捕获事件没有任何事件定义（messageEventDefinition / "
                            + "signalEventDefinition / timerEventDefinition），"
                            + "它在图上是一条永远等不到的死路"
                            + (node.getProperties()
                                    .containsKey(WfXmlParser.PROPERTY_EVENT_CONDITION)
                                    ? "。注意它写的是 conditionalEventDefinition —— "
                                    + "条件只是「在某个事件类型之上再加一道门槛」，"
                                    + "**不提供事件类型**：它回答「够不够格」，"
                                    + "不回答「等什么」。要等什么仍要配 message / signal / timer 之一"
                                    : ""));
        } else if (node.isTimerEvent() && !isGatewayBranch(definition, node, inFlows)) {
            // 定时器只有**作为事件网关的分支**时才支持。
            // 孤立的定时器捕获事件（流程里直接写一个"等 5 分钟再继续"的节点）
            // 需要的续跑路径认的是"边界事件的宿主"，而它没有宿主 ——
            // 结果是定时器永远不响且没有任何报错。这类图在部署期就拒掉，
            // 并说清缺的是哪一块。
            add(WfValidationIssue.Severity.ERROR, id,
                    "中间捕获事件 " + id + " 用的是 timerEventDefinition（定时器捕获），"
                            + "但它不是某个事件网关的分支。本引擎的定时器捕获"
                            + "**只支持作为事件网关的分支** —— 那条路径有完整的竞速语义"
                            + "（到点即算它赢了，其余分支作废）。"
                            + "孤立的定时器捕获事件要的是另一条续跑路径，本引擎没有，"
                            + "挂上去会得到一个永不响、也不报错的哑表。"
                            + "要表达「等一会儿再继续」，请把它放在事件网关下，"
                            + "与等消息 / 等信号的那几条分支并列");
        } else if (node.isEscalationEvent()) {
            // 升级抛事件与升级边界都已实现（第 26 轮），**只有捕获这一半没有** ——
            // 它的机制与消息/信号捕获根本不同：捕获到升级时 token 已经停在这个节点上，
            // 而 BPMN 要求的是「在原地**再长出一条** token 沿出线走下去」，
            // 原 token 留在原地继续等它自己的事件。
            // 现有三种捕获都是「把停着的 token 搬走」，直接套用会得到：
            // 原 token 被搬走（它其实该继续等），且没有第二条 token 生成。
            // 与其那样写出一个看起来能跑的错语义，不如部署期说清缺的是哪一块。
            add(WfValidationIssue.Severity.ERROR, id,
                    "中间捕获事件 " + id + " 用的是 escalationEventDefinition（升级捕获），"
                            + "本实现暂不支持。**升级的抛事件与边界事件都已支持**，"
                            + "缺的只有这一半：捕获到升级时 token 已经停在本节点上，"
                            + "而 BPMN 要求的是「在原地再长出一条 token 沿出线走下去」，"
                            + "原 token 留在原地继续等它自己的事件 —— "
                            + "现有三种捕获都是「把停着的 token 搬走」，直接套用会得到"
                            + "「原 token 被搬走且没有第二条」，看起来能跑而语义是错的。"
                            + "要表达「超时未办就升级」，请把升级挂在待办节点的**边界事件**上"
                            + "（cancelActivity=\"false\" 即非中断型：原待办继续办，"
                            + "另起一条升级分支）");
        } else if (!node.isSupportedGatewayBranch()) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "中间捕获事件 " + id + " 用的是 " + describeUnsupportedCatch(node)
                            + "，本引擎目前只支持 messageEventDefinition、"
                            + "signalEventDefinition，以及作为事件网关分支的 "
                            + "timerEventDefinition");
        }
        // 条件式事件的两条硬规矩
        validateConditionalEvent(id, node);
        if (inFlows.size() != 1) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "中间捕获事件必须恰好有 1 条入线，当前有 " + inFlows.size() + " 条。"
                            + "运行时靠这条唯一的入线反查所属事件网关"
                            + "（没有它就找不到该作废哪些兄弟分支）");
            return;
        }
        WfNode source = definition.node(inFlows.get(0).getSourceRef());
        if (source == null || source.getType() != WfNodeType.EVENT_BASED_GATEWAY) {
            // 普通中间捕获事件（流程里直接写、等消息继续）是合法的，运行时只前进自己、
            // 不作废任何兄弟 —— 所以这里只提醒，不报错。
            add(WfValidationIssue.Severity.WARN, id,
                    "中间捕获事件 " + id + " 不是从事件网关进来的，"
                            + "它会一直等到事件到达为止，且不会与任何其他分支互斥");
        }
    }

    /**
     * 条件式捕获事件（{@code conditionalEventDefinition}）的两条硬规矩。
     *
     * <p><b>① 条件为空 ⇒ ERROR。</b>条件式事件没有条件 = 这一格永远等不到 ——
     * 与"没有事件类型"是同一种死路，但指向的错处不同，
     * 所以分开报（作者可能以为 `conditionalEventDefinition` 本身就能等）。
     *
     * <p><b>② 边界事件上不许挂条件 ⇒ ERROR。</b>
     * 边界事件在本引擎里是<b>打断型</b>的（宿主待办作废、token 走补偿分支），
     * 而条件式事件是<b>竞速型</b>的语义（不满足就不算它赢、继续等）。
     * 两套语义叠在一起时，"条件不满足"的边界事件该怎么办没有答案：
     * 按竞速理解，它该继续等 —— 但边界事件此刻正要把宿主打断，
     * "继续等"意味着这次打断被静默取消，而宿主的 token 已经被拉走了。
     * ⇒ 与「抛事件不许挂边界事件」同一条判据：**语义不同的两件事不能叠**。
     */
    private void validateConditionalEvent(String id, WfNode node) {
        if (node.getProperties().containsKey(WfXmlParser.PROPERTY_EVENT_CONDITION_EMPTY)) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "中间捕获事件 " + id + " 写了 conditionalEventDefinition "
                            + "却没给 <condition>（或给了空白）。"
                            + "条件式事件没有条件就是一条永远等不到的死路，"
                            + "而且症状与「没等任何事件」完全一样（都是不走），"
                            + "从轨迹上分不出来。"
                            + "另外 conditional 只是「在某个事件类型之上再加一道门槛」，"
                            + "它自己**不提供事件类型** —— 还得配 messageEventDefinition / "
                            + "signalEventDefinition / timerEventDefinition 之一，"
                            + "否则这一格在等一个谁也说不清的东西");
        }
        if (node.getType() == WfNodeType.BOUNDARY_EVENT
                && node.getProperties().containsKey(WfXmlParser.PROPERTY_EVENT_CONDITION)) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "边界事件 " + id + " 上写了 conditionalEventDefinition，本实现不支持。"
                            + "本实现的边界事件是**打断型**的（宿主待办作废、token 走补偿分支），"
                            + "而条件式事件是**竞速型**语义（条件不满足就不算它赢、继续等）。"
                                    + "两者叠加时「条件不满足」该怎么办没有答案："
                                    + "按竞速理解它该继续等，可边界事件此刻正要打断宿主 —— "
                                    + "「继续等」等于把这次打断静默取消，而宿主 token 已被拉走。"
                                    + "要表达「超时了但金额不大就当没超时」，"
                                    + "请把条件放到事件网关的分支上（那里本来就是竞速语义）");
        }
    }

    /**
     * 这个捕获事件是不是事件网关的分支。
     *
     * <p>用入线参数而不是 {@code definition.gatewayOf(node)}：入线不唯一时
     * 本方法后面会单独报错，这里先按"不是分支"处理，
     * 免得同一条定义上刷出两条互相矛盾的诊断。
     */
    private boolean isGatewayBranch(WfDefinition definition, WfNode node, List<WfFlow> inFlows) {
        if (inFlows.size() != 1) {
            return false;
        }
        WfNode source = definition.node(inFlows.get(0).getSourceRef());
        return source != null && source.getType() == WfNodeType.EVENT_BASED_GATEWAY;
    }

    /**
     * 循环定时器（{@code timeCycle}）的校验。
     *
     * <p>本实现把循环定时器限定在<b>非中断型边界事件</b>上，这不是实现偷懒，
     * 而是「响过之后还有没有东西可以打断」这件事决定的：
     * <ul>
     *   <li><b>非中断型边界</b>：宿主 token 留在原地等人办，下一周期还有宿主
     *       可提醒 —— 这正是「每 2 小时催一次」。</li>
     *   <li><b>中断型边界</b>：第一次响宿主 token 就被搬到边界事件上走了，
     *       后续周期<b>没有宿主可以打断</b>。挂着让它继续响毫无意义。</li>
     * </ul>
     * 放过后一种等于「作者写每次催一次、实际只催一次」，
     * 而流程图上看不出任何异常 —— 与 {@code parallelMultiple} 挡在非多实例宿主上
     * 要报 WARN 是同一类偏差：属性写了，作者以为的那件事没发生。
     */
    private void validateCycleTimer(WfNode node) {
        if (!node.isNonInterrupting()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "循环定时器（timeCycle）只支持用在**非中断型边界事件**上"
                            + "（cancelActivity=false）。"
                            + "中断型边界第一次触发后宿主 token 就被搬到边界事件上，"
                            + "后续周期没有宿主可以打断 —— 照单全收只会在图上写着"
                            + "「每 1 小时一次」而实际只响一次，且没有任何报错。"
                            + "要「每隔一段时间提醒一次」请加 cancelActivity=false；"
                            + "要一次性的超时打断请改用 timeDuration");
            return;
        }
        if (isBlank(node.getTimerExpression())) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "timeCycle 内容为空，循环定时器算不出触发时刻");
            return;
        }
        if (WfTimerSupport.isVariableReference(node.getTimerExpression())) {
            // 变量值要到实例启动时才有，部署期无法判断它是否合法。
            // 取不到变量时 WfContext#startTimerJobs 会抛，那里报得出的原因更准
            return;
        }
        try {
            WfTimerSupport.parseCycle(node.getTimerExpression());
        } catch (IllegalArgumentException e) {
            // 字面量在部署期就能验：等到第一次触发才发现格式错，
            // 意味着这个提醒从头到尾一次都没响过，而没人会知道它本该响
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "timeCycle 表达式无法解析: " + e.getMessage());
        }
    }

    private String describeUnsupportedCatch(WfNode node) {
        if (node.isTimerEvent()) {
            return "timerEventDefinition（定时器捕获）";
        }
        // 落到这里说明 message / signal / timer 三种都没有 ——
        // conditionalEventDefinition **不提供事件类型**（它只是在某个事件类型之上
        // 再加一道门槛，"等什么"仍然得由 message / signal / timer 回答），
        // 所以不能拿它顶替事件类型，报错要说到这一点。
        // 升级捕获在调用方已被单独接住并报得更具体（它有独立的机制说明），
        // 走到这里的"没有事件类型"只可能是 conditionalEventDefinition
        return "只有 conditionalEventDefinition（条件）而没有任何事件类型";
    }

    /**
     * 便捷判定：是否含 ERROR 级问题。     */
    public static boolean hasError(List<WfValidationIssue> issues) {
        if (issues == null) {
            return false;
        }
        for (WfValidationIssue issue : issues) {
            if (issue.getSeverity() == WfValidationIssue.Severity.ERROR) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把问题列表渲染成可读的多行文本（异常消息用）。
     */
    public static String render(List<WfValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("流程定义校验未通过，共 ").append(issues.size()).append(" 个问题:");
        for (WfValidationIssue issue : issues) {
            sb.append("\n  - [").append(issue.getSeverity()).append("] ");
            if (issue.getNodeId() != null) {
                sb.append(issue.getNodeId()).append(": ");
            }
            sb.append(issue.getMessage());
        }
        return sb.toString();
    }

    private void add(WfValidationIssue.Severity severity, String nodeId, String message) {
        issues.add(new WfValidationIssue(severity, nodeId, message));
    }

    private List<WfValidationIssue> result() {
        return new ArrayList<>(issues);
    }

    /**
     * 边界事件校验。
     *
     * <p>四条规则，每条都对应一种"部署通过、运行时行为不是作者以为的那样"：
     * <ul>
     *   <li>缺 attachedToRef ⇒ 不知道挂在谁身上，等于没挂</li>
     *   <li>挂到了不存在的节点 ⇒ 永远不会触发</li>
     *   <li>既没有 {@code errorRef} 也没有 {@code timerEventDefinition}
     *       ⇒ 它没有任何触发条件，永远不会触发</li>
     *   <li>是错误边界却缺 errorCode ⇒ 无法决定捕不捕获。这里刻意<b>不接受</b>
     *       BPMN 里的"空 errorRef 表示捕获所有错误"：宽泛捕获会把不相关的异常
     *       也吸走，让本该崩掉的流程继续走下去，而那正是边界事件最该避免的事</li>
     *   <li>有入线 ⇒ 画错了。边界事件靠宿主节点出错时触发，
     *       被 sequenceFlow 指到它意味着作者以为它是普通流程节点</li>
     * </ul>
     */
    private void validateBoundaryEvent(WfNode node, WfDefinition definition) {
        // 条件式事件在边界上不成立（打断型 vs 竞速型语义互斥），与 catch 事件共用同一段校验
        validateConditionalEvent(node.getId(), node);
        if (isBlank(node.getAttachedToRef())) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "boundaryEvent 缺少 attachedToRef，不知道挂在哪个节点上");
        } else if (definition.node(node.getAttachedToRef()) == null) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "boundaryEvent 挂载的目标节点不存在: " + node.getAttachedToRef()
                            + "，该边界事件永远不会触发");
        }

        // ---- 触发类型：错误边界 / 定时器边界是两条互斥的路径 ----
        // 此前这里不分类型，一律要求 errorCode，于是合法定时器边界
        // 会被报"缺少 errorCode"而部署不了 —— 部署期严格成了部署期误伤。
        Object conflict = node.getProperties() == null
                ? null : node.getProperties().get(WfXmlParser.PROPERTY_TIMER_CONFLICT);
        if (conflict != null) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "timerEventDefinition 里同时出现了多个子元素: " + conflict
                            + "。一个边界事件只能挂一种触发条件");
        }
        Object eventConflict = node.getProperties() == null
                ? null : node.getProperties().get(WfXmlParser.PROPERTY_EVENT_CONFLICT);
        if (eventConflict != null) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "边界事件上: " + eventConflict + "。一个边界事件只能挂一种触发条件");
        }
        // parallelMultiple="true"（第 33 轮起支持）：
        // 它说的是"边界事件按多实例的每个实例各建一个"，**不是"能不能重复触发"**。
        // 这条规则原先写的是"不支持（重复触发）"—— 照着一个错的属性解释去拒绝
        // 一个规范里真实存在的属性，于是任何照 BPMN 写的 parallelMultiple="true"
        // 都部署不了，而挡它的理由还把属性讲反了。
        //
        // 现在只保留一条真问题：宿主不是多实例时，这个属性**无处可施**。
        // 报 WARN 而不是 ERROR：行为完全确定（等同不写），既不会挂死也不会算错，
        // 作者的心愿只是不会实现 —— 挡住不让人部署是过度反应，
        // 而放着不说就是让一个按规范写模型的人以为它生效了。
        if (node.isParallelMultiple()) {
            WfNode host = node.getAttachedToRef() == null
                    ? null : definition.node(node.getAttachedToRef());
            if (host == null || !host.isMultiInstance()) {
                add(WfValidationIssue.Severity.WARN, node.getId(),
                        "parallelMultiple=\"true\" 挂在非多实例节点 "
                                + (host == null ? node.getAttachedToRef() : host.getId())
                                + " 上，这个属性在这里没有意义 —— "
                                + "它的含义是「多实例时每个实例各有各的边界事件」，"
                                + "而宿主只有一个实例可挂。当前按不写处理（行为与 "
                                + "parallelMultiple=\"false\" 一致）。"
                                + "另外这个属性与「能不能重复触发」无关："
                                + "要每隔一段时间提醒一次请用 timeCycle 循环定时器"
                                + "（需配 cancelActivity=\"false\"）");
            }
        }
        // 非中断型没有出线时，新起的那条 token 无处可去：它会永远停在这个边界节点上，
        // 而宿主的流程看起来一切正常 —— 一条永远等下去且没人管的分支
        if (node.isNonInterrupting() && definition.outgoingFlows(node.getId()).isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "非中断型边界事件 " + node.getId() + " 没有任何出线。"
                            + "非中断触发时引擎会另起一条 token 从它出发，"
                            + "没有出线这条 token 会永远停在这里 —— 流程表面正常，"
                            + "实际多了一条没人管、也永远等不到的分支");
        }
        Object missingRef = node.getProperties() == null
                ? null : node.getProperties().get(WfXmlParser.PROPERTY_EVENT_MISSING_REF);
        if (missingRef != null) {
            String kind = String.valueOf(missingRef);
            // 三个事件的 ref 名字各不相同，**不能只分 message 与 signal 两支**：
            // 升级落到 else 分支会被说成"缺 signalRef"，
            // 而作者写的是 escalationRef —— 报错指向了一个他没写过的属性
            String refName = kind.startsWith("message") ? "messageRef"
                    : kind.startsWith("escalation") ? "escalationRef" : "signalRef";
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    kind + " 缺少 " + refName + "，这条边界永远不会触发");
        }
        if (node.isTimerBoundary()) {
            validateTimerBoundary(node);
        } else if (node.isMessageBoundary() || node.isSignalBoundary()
                || node.isEscalationEvent()) {
            // 消息/信号/升级边界本身合法（缺名字的情况上面已单独报过）
        } else if (node.isCompensationBoundary()) {
            // 补偿边界事件（第 37 轮）自身合法：它不是靠「外部发生了什么」触发的，
            // 而是引擎在补偿被触发时由 <association> 找到它再去执行处理器。
            // 掉进下面的 else 会被说成「没有任何触发条件，永远不会触发」——
            // 而作者确实无从判断这句话指哪里：他写的就是 <compensateEventDefinition/>。
            // 与 timer/message/signal 同一支的理由相同：它是「已认出的触发类型」之一，
            // 缺关联处理器的情况已由 validateCompensationBoundary 单独报出。
        } else if (isBlank(node.getErrorCode())) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "boundaryEvent 既没有 errorCode（errorEventDefinition/@errorRef）"
                            + "也没有 timerEventDefinition，没有任何触发条件，永远不会触发。"
                            + "错误边界请显式写明捕获哪一种错误 —— 本实现刻意不支持"
                            + "「空 errorRef = 捕获所有错误」：宽泛捕获会把不相关的异常也吸走");
        }

        if (definition.incomingFlows(node.getId()) != null
                && !definition.incomingFlows(node.getId()).isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "boundaryEvent 不该有入线。它靠宿主节点出错时被触发，"
                            + "被 sequenceFlow 指到说明画成了普通流程节点");
        }
        // 补偿边界事件没有出线（第 37 轮）：它触发之后不是「沿某条线继续走」，
        // 而是「执行 <association> 指向的补偿处理器」，走完补偿就结束了。
        // 要求它有出线，等于要求作者画一条永远走不到的线来消警告。
        if (node.isCompensationBoundary()) {
            return;
        }
        List<WfFlow> outgoing = definition.outgoingFlows(node.getId());
        if (outgoing == null || outgoing.isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "boundaryEvent 没有出线。触发之后将无处可去，流程会直接结束");
        }
    }

    /**
     * 定时器边界事件的校验。
     *
     * <p>只有"触发时刻算不出来"这一类问题会报 ERROR —— 那意味着 job 建不出来，
     * 部署放行等于留一个永远不会响的哑定时器。
     */
    private void validateTimerBoundary(WfNode node) {
        if (node.getTimerType() == WfTimerType.CYCLE) {
            validateCycleTimer(node);
            return;
        }
        if (isBlank(node.getTimerExpression())) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    node.getTimerType().getElementName() + " 内容为空，定时器算不出触发时刻");
            return;
        }
        if (WfTimerSupport.isVariableReference(node.getTimerExpression())) {
            // 变量值要到实例启动时才有，部署期无法判断它是否合法。
            // 这里放过不是放过不管：取不到变量时 WfContext#startTimerJobs 会抛，
            // 流程启动当场失败，不会留下一个算不出时刻的哑表
            return;
        }
        try {
            WfTimerSupport.resolveDueDate(node.getTimerType(), node.getTimerExpression(),
                    new Date(), null);
        } catch (RuntimeException e) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "定时器表达式无法解析: " + node.getTimerExpression() + " —— " + e.getMessage());
        }
    }

    /**
     * 异步配置的校验：异步与外部任务、多实例的互斥关系。
     *
     * <p>都报 ERROR，因为它们共同的失败模式是"部署成功、运行时卡住"：
     * 排了 job 却没有 worker 会来领它，而流程就在那一格等着，不报错也不动。
     */
    private void validateAsync(WfNode node) {
        // exclusive 写在非异步节点上：属性本身对**这个节点**没有意义。
        // Camunda 官方扩展属性表的 Constraints 行原文：
        // "The camunda:exclusive attribute is only evaluated if the attribute
        //  camunda:asyncBefore or camunda:asyncAfter is set to true"
        //
        // 报 WARN 而不是 ERROR：行为完全确定（那个节点根本不会排 job，
        // 没有 job 可谈互斥），既不会挂死也不会算错。
        // 但作者多半是照着别的节点抄了属性名，或者以为"标了就生效"，
        // 而图上看不出任何区别 —— 说出来比默默忽略有用。
        //
        // **这条必须在 `if (!isAsync()) return;` 之前**：本方法开头就有那道早退，
        // 而这里管的恰好是**非**异步节点 —— 放在早退之后它就永远不执行，
        // 而那种"看起来写了其实从不运行"的规则比没有更坏：
        // 它让人以为"非异步节点写 exclusive 会被提醒"，而实际上没人提醒。
        // （第一版就是这么放错的，被本轮新加的判据当场抓到。）
        //
        // **判不出"有没有显式写过"**：解析后只剩一个 boolean，
        // 没写与写了 true 在字段上完全相同（见 WfNode#exclusive 的默认值说明）。
        // 所以这里只能在**非异步**时报 —— 那正是属性必然无效的那一种。
        if (!node.isAsyncBefore() && !node.isAsyncAfter() && exclusiveWasWritten(node)) {
            add(WfValidationIssue.Severity.WARN, node.getId(),
                    "exclusive 写在这个节点上，但它没有 asyncBefore/asyncAfter —— "
                            + "该属性只在异步续跑时才有意义（Camunda 官方约束："
                            + "\"only evaluated if camunda:asyncBefore or camunda:asyncAfter "
                            + "is set to true\"）。当前照常忽略它");
        }
        if (!node.isAsync()) {
            return;
        }
        // 外部任务与异步互斥：外部任务在 enter 里就要挂 job 并 return，
        // 异步前置的判断排在它之前，两者同开时"排队等外部 worker"这个语义
        // 根本轮不到 —— 流程会先挂一个异步 job，而没有人会去领它
        if (node.isExternalStep()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "同一个节点不能既是外部任务（zifang:topic）又配 asyncBefore/asyncAfter。"
                            + "引擎会先排一次异步 job，而没有任何 worker 会去领它，流程永远停在这里");
        }
        // 多实例 + 异步：并行会签的每个实例 token 都会各挂一个异步后置 job，
        // 而"这一步做完没有"是合取语义 —— 要么全部实例都续跑完流程才算走完。
        // 本实现的续跑是单 token 粒度的，没有"等所有实例都离开这个节点"的汇合点，
        // 放行的话会变成"最后一个实例离开时流程先走了，前面的实例还在等"
        if (node.isMultiInstance()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "多实例节点暂不支持 asyncBefore/asyncAfter。本实现的异步续跑是单 token "
                            + "粒度的，而会签要等所有实例都离开这一步才走得通，"
                            + "两者合在一起会让流程在最后一个实例离开时就往前走");
        }
        // endEvent 不走 leave，所以异步后置永远不会被挂出来 ——
        // 写上它的人以为"结束前再排一次队"，实际那行配置毫无作用
        if (node.isAsyncAfter() && node.getType() == WfNodeType.END_EVENT) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "endEvent 不走 leave，asyncAfter 在它上面永远不会被触发。"
                            + "结束节点没有【离开之后】可言，请改用 asyncBefore"
                            + "（它能在进入结束节点前排一次队）");
        }
        // 同样的道理：终止结束事件也不走 leave，asyncAfter 一样挂不出来。
        // 刻意与上面那条分开写而不是把条件并成 `isEndEvent()` ——
        // 并成一条之后，改动 endEvent 的那句话会静默影响到终止结束事件，
        // 而报错文案里一个字都没提它。
        if (node.isAsyncAfter() && node.getType() == WfNodeType.TERMINATE_END_EVENT) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "terminateEndEvent 不走 leave，asyncAfter 在它上面永远不会被触发。"
                            + "它结束的是整个作用域，没有【离开之后】可言，请改用 asyncBefore");
        }
    }

    /**
     * 多实例节点校验。
     *
     * <p>四条规则都报 ERROR，因为它们各自的失败模式都是"部署成功、运行时出错"：
     * <ul>
     *   <li>loopCardinality 与 collection 一个都没配 ⇒ 不知道要造几个实例</li>
     *   <li>两个都配 ⇒ 猜不出来该听谁的，而猜错是"派错了人且不报错"</li>
     *   <li>配了 elementVariable 却没有 collection ⇒ 元素永远绑不上，
     *       办理表达式解析成空办理人，且从头到尾不报错</li>
     *   <li>完成条件里没有标准循环变量 ⇒ 变量名多半写错了 ⇒ fail-closed 判 false
     *       ⇒ 流程<b>永远</b>等不到"完成"而卡死，且没有任何报错</li>
     * </ul>
     * 最后一条是这组规则里最要紧的：它不会立刻失败，它让整个流程静默停住。
     */
    private void validateMultiInstance(WfNode node) {
        if (!node.isMultiInstance()) {
            return;
        }
        if (!node.getType().createsTask()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "多实例目前只支持任务类节点（userTask / manualTask / task / receiveTask），"
                            + "当前节点类型是 " + node.getType().bpmnName());
            return;
        }
        boolean hasCollection = !isBlank(node.getLoopCollection());
        boolean hasCardinality = !isBlank(node.getLoopCardinality());
        // 两者都配时报 ERROR 而不是挑一个用：会签"3 个人"与"3 个候选人"
        // 在实现上是同一件事，作者写错成另一个时猜不出来该听谁的，
        // 而猜错的表现是"跑了 3 个人，其中 2 个没派对人"——不报错、也看不出来
        if (hasCollection && hasCardinality) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "多实例的 collection 与 loopCardinality 只能二选一（当前两个都配了："
                            + "collection=" + node.getLoopCollection().trim()
                            + "，loopCardinality=" + node.getLoopCardinality().trim()
                            + "）。前者让实例数由集合大小决定，后者由作者写死个数，"
                            + "同时给两个无法判断该听谁的");
        } else if (!hasCollection && !hasCardinality) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "多实例节点缺少 loopCardinality 与 collection，无法确定要展开几个实例。"
                            + "「固定 3 个人会签」用 loopCardinality，"
                            + "「部门所有领导会签」用 collection 指向一个人员列表");
        }
        // loopCardinality 是字面量时，实例数在部署期就已知：
        // 超上限要在部署时挡，而不是等运行时一次性造出上亿个 token 把库打满。
        // 走表达式或 collection 的拿不到部署期数值，由运行期的同一道闸门兜
        if (hasCardinality && node.getLoopCardinality().trim().matches("\\d+")) {
            long literal = Long.parseLong(node.getLoopCardinality().trim());
            if (literal > WfEngine.MAX_LOOP_INSTANCES) {
                add(WfValidationIssue.Severity.ERROR, node.getId(),
                        "多实例 loopCardinality=" + literal + " 超过上限 "
                                + WfEngine.MAX_LOOP_INSTANCES
                                + "，拒绝展开（一个手滑的表达式不该把内存和数据库同时打满）");
            }
        }
        // elementVariable 只在配了 collection 时才有意义：没有集合就没有"当前元素"。
        // 放过它的话，办理表达式里的 ${那个名字} 会因 fail-closed 取不到值，
        // 派出来的待办没有办理人，且从头到尾不报错
        if (!isBlank(node.getLoopElement()) && !hasCollection) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "多实例配了 elementVariable=" + node.getLoopElement().trim()
                            + " 却没有 collection：没有集合就没有「当前元素」，"
                            + "这个变量永远取不到值，办理表达式 ${" + node.getLoopElement().trim()
                            + "} 会解析成空办理人且不报错。elementVariable 只能与 collection 搭配");
        }
        String condition = node.getCompletionCondition();
        if (condition != null && !condition.trim().isEmpty()
                && !referencesLoopVariable(condition)) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "完成条件 " + condition + " 里没有引用任何标准循环变量"
                            + "（loopCounter / nrOfInstances / nrOfActiveInstances / "
                            + "nrOfCompletedInstances），多半是变量名拼错了。"
                            + "变量不存在会因 fail-closed 判为条件不成立，"
                            + "结果是这个会签<b>永远</b>等不到完成而卡死，且不会有任何报错");
        }
    }

    /**
     * {@code exclusive} 属性是否被显式写过。
     *
     * <p>解析后字段里「没写」与「写了 true」完全一样（默认值就是 true），
     * 分不出来 —— 解析期在 {@link WfXmlParser#PROPERTY_EXCLUSIVE_WRITTEN}
     * 留了这一个比特，这里只负责读它。
     *
     * <p>注意 WfNode 手工构造（不走解析器）时不会有这个标记，那种节点
     * 一律当作"没写过" —— 与"用默认值"一致，不会误报。
     */
    private boolean exclusiveWasWritten(WfNode node) {
        return node.getProperties() != null
                && node.getProperties().get(WfXmlParser.PROPERTY_EXCLUSIVE_WRITTEN) != null;
    }

    /** 完成条件里是否出现了任一标准循环变量。 */    private static boolean referencesLoopVariable(String condition) {
        String[] names = {"loopCounter", "nrOfInstances", "nrOfActiveInstances",
                "nrOfCompletedInstances"};
        for (String name : names) {
            if (condition.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 内联节点的<b>归属</b>是否站得住 —— 它声称嵌在某个容器里，那个容器得真的存在。
     *
     * <p>报 ERROR 而不是放过：容器认不出来时，引擎的内联查询按 id 匹配，
     * 一个都匹配不到 ⇒ {@code isInlineSubProcess} 判 false ⇒ 该 subProcess 被当成
     * 空容器直接穿透。症状是"内联节点一个都没跑，流程照常走到结束、状态 COMPLETED"，
     * 没有任何日志能解释作者画的东西为什么消失了。
     *
     * <p><b>容器存在但不是 subProcess 时放过</b>：解析器记的 {@code nestedIn} 是
     * "直接父元素"，而 {@code <boundaryEvent>} 在 BPMN 里就写在宿主活动内部 ——
     * 挂在 userTask 上的超时边界事件，它的容器是那个 userTask。
     * 那是完全正常的写法，不能当成"内联节点嵌错了地方"。
     */
    private void validateInlinePlacement(WfDefinition definition, WfNode node) {
        String containerId = node.nestedIn();
        if (containerId == null) {
            return;
        }
        WfNode container = definition.node(containerId);
        if (container == null) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "内联节点 " + node.getId() + " 声称嵌在容器 " + containerId
                            + " 里，但流程定义里没有这个 id 的节点。容器元素多半没写 id 属性"
                            + "（解析器此时只能记下元素名占位，本节点就再也认不出自己属于谁）。"
                            + "内联容器必须写 id");
        }
    }

    /**
     * 嵌入式 subProcess 的结构校验。
     *
     * <p>只在"容器里确实画了节点"时才校验：空的 {@code <subProcess>} + calledElementKey
     * 在 BPMN 里等价于 callActivity，交给 {@code calledElementKey 缺失}那条规则管。
     *
     * <p>四条规则各自挡掉一种"能部署、跑起来不是作者画的那张图"的写法，
     * 共同点是症状都不带任何报错。
     */
    private void validateSubProcess(WfDefinition definition, WfNode node) {
        boolean isTransaction = node.getType() == WfNodeType.TRANSACTION;
        if (!isTransaction && node.getType() != WfNodeType.SUB_PROCESS) {
            return;
        }
        List<WfNode> children = definition.inlineChildrenOf(node.getId());
        if (children.isEmpty()) {
            if (isTransaction) {
                // 与 subProcess 不同：空 transaction **没有**「等价于 callActivity」
                // 这种解读（BPMN 里 transaction 必须画内容才有意义），
                // 所以「空 transaction」不是「等价于调用外部流程」，而是「什么都不会发生」。
                add(WfValidationIssue.Severity.ERROR, node.getId(),
                        "transaction " + node.getId() + " 里没有画任何节点。"
                                + "事务必须有内容才有意义：空的事务什么都不会执行，"
                                + "而 token 经过它会照常往下走 —— 看上去这一步做完了，"
                                + "实际什么都没做");
            }
            return;
        }
        // 后面几条规则的措辞统一叫「内联容器」，两种元素共用
        String label = isTransaction ? "transaction" : "嵌入式 subProcess";

        // ---- 恰好一个内联起始节点 ----
        // 引擎靠"容器内无入线的节点"识别入口。有两个时选谁是猜，猜错的表现是
        // "另一半内联节点一次都没跑，而实例正常结束"；没有时更糟，token 无处可进。
        List<String> entries = inlineEntryIds(definition, children);
        if (entries.size() != 1) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    label + " " + node.getId() + " 需要恰好一个内联起始节点"
                            + "（容器内没有入线的那一个），实际找到 " + entries.size() + " 个: " + entries
                            + "。内联容器的进入点由「容器内无入线的节点」唯一确定，"
                            + "多于一个时引擎无法判断该从哪进入");
        }

        // ---- 至多一个内联结束节点 ----
        // 有两个时 token 会在第一个"结束"处跳出容器，后半段永远跑不到；
        // 一个都没有说明容器内是个环（每个节点都有出线），token 会在里面打转。
        //
        // 终止结束事件（第 36 轮）与取消结束事件（第 38 轮）都不参与这个计数，
        // 但参与"有没有出口"的判定：一个只有 terminate/cancel、没有普通结束节点的
        // 容器是合法的（进入即被收掉，永远走不到正常出口），而三个都没有才是环。
        List<String> exits = inlineExitIds(children);
        List<String> terminates = inlineTerminateExitIds(children);
        List<String> cancels = inlineCancelExitIds(children);
        if (exits.size() > 1) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    label + " " + node.getId() + " 有 " + exits.size()
                            + " 个内联结束节点（容器内的 endEvent）: " + exits
                            + "。容器的结束点必须唯一，否则 token 会在第一个结束处跳出，"
                            + "后面的内联内容永远跑不到");
        } else if (exits.isEmpty() && terminates.isEmpty() && cancels.isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    label + " " + node.getId() + " 没有任何内联结束节点"
                            + "（容器内没有 endEvent，也没有 terminateEndEvent / cancelEndEvent）。"
                            + "这意味着容器内部成环，token 会在里面一直转下去");
        }

        // ---- 事务的取消出口（第 38 轮）----
        // cancelEndEvent 是 transaction 取消路径的**终点**：它触发补偿后
        // token 沿 transaction 自己的出线继续，流程并没有结束。
        // 挂在 transaction 外面就完全没有意义 —— 外面没有任何东西可以取消。
        if (isTransaction) {
            for (String cancelId : cancels) {
                String parent = definition.node(cancelId) == null
                        ? null : definition.node(cancelId).nestedIn();
                if (parent == null || !node.getId().equals(parent)) {
                    add(WfValidationIssue.Severity.ERROR, cancelId,
                            "cancelEndEvent " + cancelId + " 必须在 transaction 内部。"
                                    + "它表示「这个事务被取消」，而 transaction 外部没有"
                                    + "可以被取消的事务");
                }
            }
        }

        // ---- 内联子图里的死胡同 ----
        // 「有入线、无出线、且不是结束事件」的节点。token 抵达它会走 leave 而无路可走，
        // WfEngine 只 warn 一句「节点 X 没有出线，token 结束」就把它结束掉 ——
        // 症状是"单子被正常办掉了，之后什么也没发生"，全程没有任何报错。
        //
        // 这些节点<b>不算出口</b>，出口是 endEvent 的特权。把它们混进 inlineExitIds
        // 的话，一条真正只有一个结束点的容器会被报成"有 N 个内联结束节点" ——
        // 而作者照着提示去找第二个结束节点，根本找不到一个。
        List<String> deadEnds = new ArrayList<>();
        for (WfNode child : children) {
            WfNodeType type = child.getType();
            if (type == WfNodeType.END_EVENT || type == WfNodeType.TERMINATE_END_EVENT
                    || type == WfNodeType.CANCEL_END_EVENT) {
                continue;
            }
            if (!definition.incomingFlows(child.getId()).isEmpty()
                    && definition.outgoingFlows(child.getId()).isEmpty()) {
                deadEnds.add(child.getId());
            }
        }
        if (!deadEnds.isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "嵌入式 subProcess " + node.getId() + " 里有节点有入线却没有出线: " + deadEnds
                            + "。这是死胡同，不是结束节点：结束节点只能是 endEvent / "
                            + "terminateEndEvent。token 办结到这个节点后会直接结束，"
                            + "子流程随后被当成正常完成，而作者画的那条后续路径一次都没跑");
        }

        // ---- 不支持嵌套 ----
        // 理由不是"没实现"这么轻：内层 subProcess 的离开语义与外层不同 ——
        // 它的出线只通向它自己内部，而"子流程结束了"这件事在 BPMN 里
        // 没有任何结构标记能指出是哪一个 endEvent 属于内层、哪一个属于外层。
        // 猜错的后果是内联内容被静默跳过或流程死循环，两者都不报错。
        List<String> nested = new ArrayList<>();
        for (WfNode child : children) {
            if (child.getType() == WfNodeType.SUB_PROCESS
                    || child.getType() == WfNodeType.TRANSACTION) {
                nested.add(child.getId());
            }
        }
        if (!nested.isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    label + " " + node.getId() + " 里又嵌了 subProcess / transaction: " + nested
                            + "。本引擎不支持嵌套的内联容器：内层容器的结束点"
                            + "在 BPMN 里没有结构标记能与其他结束事件区分开，"
                            + "引擎无法判断该在哪一层跳出。请改用 callActivity 指向独立流程定义");
        }

        // ---- 不支持边界事件挂在容器上 ----
        // token 一进入 subProcess 就被推进到内联起始节点，此后它<b>不在</b>
        // 容器节点上。而 WfRuntimeService#fireEventBoundary 有一道硬闸门：
        // token 必须仍停在宿主节点上才触发。放宽它要重新定义"打断"的对象，
        // 语义风险远大于本轮的收益，所以部署期直接挡掉。
        //
        // 判据是 <b>attachedToRef（它挂在谁身上）</b>，不是 nestedIn（它在 XML 里写在谁里面）。
        // 这两者对同一个 boundaryEvent 可以是<b>不同的答案</b>，而那种写法不止一种：
        // BPMN 允许 boundaryEvent 写在宿主活动内部（nestedIn == 宿主），
        // 也允许写在 subProcess 层级下、用 attachedToRef 指回内层的某个活动
        // （nestedIn == 该 subProcess）—— 后者是 Camunda Modeler 导出的常见形状。
        // 按 nestedIn 判的话，子流程里的活动挂超时边界这种完全正常的写法会被判非法，
        // 而同一张图只要把 boundaryEvent 挪到容器外面就又能过了：
        // 图没变、语义没变，结论却相反，作者无从判断该怎么改。
        List<String> boundaries = new ArrayList<>();
        for (WfNode candidate : definition.getNodes()) {
            if (candidate != null
                    && candidate.getType() == WfNodeType.BOUNDARY_EVENT
                    && node.getId().equals(candidate.getAttachedToRef())) {
                boundaries.add(candidate.getId());
            }
        }
        if (!boundaries.isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    label + " 上不能挂边界事件: " + boundaries
                            + "。token 进入容器后立即被推进到内联起始节点，"
                            + "此后不再停留在容器上，而边界事件要求 token 仍停在宿主节点才触发。"
                            + "放到内联的某个具体活动上即可；超时/异常的处理请放到该活动上");
        }

        // ---- 画了内联内容又配 calledElementKey ----
        // 两者语义互斥：内联容器执行的是本图里的节点，calledElementKey 指向另一份定义。
        // 引擎优先走内联（isInlineSubProcess 先判），配了 calledElementKey 的效果是
        // "看着像会调外部流程，实际一次都没调"。
        //
        // transaction 另有一条：它<b>根本没有</b> calledElementKey 这个概念
        // （BPMN 里 transaction 不是引用型容器），配了同样一律拒绝 ——
        // 与其「配了但没报错」，不如直接说这个属性在这里没有意义。
        if (!isBlank(node.getCalledElementKey())) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "subProcess " + node.getId() + " 既画了内联内容，又配了 calledElementKey="
                            + node.getCalledElementKey().trim() + "。两者互斥："
                            + "内联子流程执行的是本图里的节点。引擎会以内联内容为准，"
                            + "calledElementKey 一次都不会生效。要调用独立流程请改用 callActivity");
        }
    }

    /**
     * 容器内"无入线"的节点 id —— 内联起始节点候选。
     *
     * <p><b>排除三类结束事件</b>（第 37/38 轮）。它们<b>天然</b>无入线 ——
     * 不是「没人走到它」，而是「走到它就是终点」，而终点没有入线是常态。
     * 不排除的话，一个「成功出口的线被改成指回 endEvent」的图会被报成
     * 「找到 2 个内联起始节点」，而作者图上明明只有一个 startEvent：
     * 症状是部署期报一个他改过、却与这条提示毫无关系的地方。
     *
     * <p>与 {@link #inlineExitIds} 那次是同一个形状的错误：**按结构特征
     * （有无入线/出线）去代理语义（是不是入口/出口）**，而对结束事件而言
     * 结构特征与语义恰好不相关。
     */
    private static List<String> inlineEntryIds(WfDefinition definition, List<WfNode> children) {
        List<String> ids = new ArrayList<>();
        for (WfNode node : children) {
            if (isInlineEndKind(node)) {
                continue;
            }
            if (definition.incomingFlows(node.getId()).isEmpty()) {
                ids.add(node.getId());
            }
        }
        return ids;
    }

    /** 这三类是容器的出口，不参与入口判定（见 {@link #inlineEntryIds}）。 */
    private static boolean isInlineEndKind(WfNode node) {
        WfNodeType type = node.getType();
        return type == WfNodeType.END_EVENT || type == WfNodeType.TERMINATE_END_EVENT
                || type == WfNodeType.CANCEL_END_EVENT;
    }

    /**
     * 容器内的 {@code endEvent} —— 子流程的正常出口。
     *
     * <p><b>按类型收，不按「无出线」收。</b>（第 37 轮修正）
     * 早先的判据是"容器内没有出线的普通节点"，而<b>没有出线的节点不一定是结束节点</b>：
     * 一条有入线无出线的 userTask 同样是"无出线"，它却不是出口 ——
     * 混进来会让一条真正只有一个 endEvent 的子流程被报成"有 2 个结束点"，
     * 而作者照着提示去找第二个 endEvent，根本找不到。
     * 那类节点由 {@code validateSubProcess} 的死胡同检查单独报出，报的是它自己的问题。
     *
     * <p><b>刻意排除 {@code terminateEndEvent}（第 36 轮）。</b>
     * 终止结束事件同样没有出线，若把它算进来，「一条分支正常结束、另一条分支终止」
     * 就会凑出 2 个"结束点"而被报成「子流程的结束点必须唯一」——
     * 而那恰恰是 Camunda 推荐的写法（两条并行分支，谁先到谁说了算：
     * 到了的那条正常结束，终止事件把另一条上的待办收掉，子流程照常完成）。
     * 把这个最典型的用法判成非法，作者会以为自己画错了。
     *
     * <p>终止结束事件由 {@link #inlineTerminateExitIds} 单独统计，
     * 两处合起来才是"容器里的所有出口"。
     */
    private static List<String> inlineExitIds(List<WfNode> children) {
        List<String> ids = new ArrayList<>();
        for (WfNode node : children) {
            if (node.getType() == WfNodeType.END_EVENT) {
                ids.add(node.getId());
            }
        }
        return ids;
    }

    /** 容器内的 {@code terminateEndEvent}（第 36 轮）。 */
    private static List<String> inlineTerminateExitIds(List<WfNode> children) {
        List<String> ids = new ArrayList<>();
        for (WfNode node : children) {
            if (node.getType() == WfNodeType.TERMINATE_END_EVENT) {
                ids.add(node.getId());
            }
        }
        return ids;
    }

    /** 容器内的 {@code cancelEndEvent}（第 38 轮）。 */
    private static List<String> inlineCancelExitIds(List<WfNode> children) {
        List<String> ids = new ArrayList<>();
        for (WfNode node : children) {
            if (node.getType() == WfNodeType.CANCEL_END_EVENT) {
                ids.add(node.getId());
            }
        }
        return ids;
    }

    /**
     * 取消结束事件校验（第 38 轮）。
     *
     * <p>一条 ERROR：<b>它不许有出线</b>，理由与终止结束事件同源 ——
     * 取消不可撤销，它要退的事已经退了，流程接着沿<b>容器</b>的出线走，
     * 而事件自己的出线永远走不到。
     */
    private void validateCancelEndEvent(WfNode node, WfDefinition definition) {
        if (node.getType() != WfNodeType.CANCEL_END_EVENT) {
            return;
        }
        if (!definition.outgoingFlows(node.getId()).isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "cancelEndEvent " + node.getId() + " 不能有出线。"
                            + "取消结束事件触发补偿之后，token 沿**容器**的出线继续"
                            + "（流程并没有结束），而它自己的出线永远走不到 —— "
                            + "留一条走不到的线，流程会卡在半路且不报错");
        }
        if (node.getType() == WfNodeType.CANCEL_END_EVENT
                && definition.incomingFlows(node.getId()).isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "cancelEndEvent " + node.getId() + " 没有任何入线，"
                            + "token 永远到不了它 —— 那个被写的取消分支一次都不会发生");
        }
    }

    /**
     * 终止结束事件校验（第 36 轮）。
     *
     * <p>只报一条 ERROR：<b>它不许有出线</b>。
     *
     * <p>放行的症状是「图上画了终止，出线一个都没走到」——
     * 而终止不可撤销，另一条分支上的待办已经被收掉了，
     * 结果就是流程既没被终止也没往下走，卡在半路且没有任何报错。
     */
    private void validateTerminateEndEvent(WfDefinition definition, WfNode node) {
        List<WfFlow> outs = definition.outgoingFlows(node.getId());
        if (!outs.isEmpty()) {
            StringBuilder flowIds = new StringBuilder("[");
            for (int i = 0; i < outs.size(); i++) {
                flowIds.append(i > 0 ? ", " : "").append(outs.get(i).getId());
            }
            flowIds.append("]");
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "terminateEndEvent 不能有出线，却接了 " + outs.size() + " 条: "
                            + flowIds + "。它结束的是整个作用域，"
                            + "到达之后本作用域内的 token 全部作废，"
                            + "出线永远不会被走到 —— 而终止不可撤销，"
                            + "另一条分支上的待办那时已经被收掉了，"
                            + "流程会卡在半路且不报错");
        }
    }

    /**
     * 对已知可替代的元素给出"改用哪个"的建议。
     *
     * <p>只对<b>真的等价</b>的替代关系给建议。{@code eventBasedGateway} 刻意不给：
     * 拿 {@code exclusiveGateway} 顶替它不是简化，是把一个"多路事件竞速"换成
     * "在顺序条件里选一条"，作者照着提示改反而会得到一个更难发现的错误流程。
     * 没有等价物时如实说"暂无"，比给个像模像样的错答案可靠。
     *
     * <p><b>这里只列还真的会走到的元素</b>。原名单里的 {@code intermediateThrowEvent}
     * 与 {@code intermediateCatchEvent} 已分别在第 18 / 7 轮有了原生实现，
     * {@code linkThrowEvent} / {@code linkCatchEvent} 也在第 22 轮有了原生实现 ——
     * 原生类型不会再被标记成退化元素，于是这几个分支永远不会被调用。
     * 留着它们不是"以防万一"，而是一条<b>走不到、但一旦被改回标记就会给出错误建议</b>的路径：
     * 对已经原生支持的元素说"请改用 sendTask"，作者照着改就把一份能跑的流程改坏了。
     */
    private static String substitutionHint(String elementName) {
        String key = elementName == null ? "" : elementName.trim();
        String replacement;
        if ("transaction".equals(key) || "adHocSubProcess".equals(key)) {
            replacement = "subProcess";
        } else {
            return " 本引擎暂无等价节点，请改写流程或等待该元素被支持。";
        }
        return " 请改用 <" + replacement
                + ">，或在元素上显式写 zifang:type 声明你真正想要的类型。";
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
