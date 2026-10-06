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
            // ---- 多实例（会签 / 或签）----
            validateMultiInstance(node);
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

        validateGraphShape(definition, ids);

        return result();
    }

    /**
     * 同一条消息 / 信号不得对应两个起始节点。
     *
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
    private void validateComplexGateway(String id, WfNode node, List<WfFlow> outs,
                                       int defaultCount) {
        if (isBlank(node.getCaseVariable())) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "complexGateway 缺少 zifang:caseVariable（或 camunda:caseExpression）。"
                            + "没有它就永远走默认线，而流程照样能跑完 —— "
                            + "排查时看不出它是个坏网关");
            return;
        }
        int withCase = 0;
        for (WfFlow f : outs) {
            if (!isBlank(f.getCaseValue())) {
                withCase++;
            }
            // 两条判定方式都配时，引擎只会用 caseValue。留着 conditionExpression
            // 的人会以为"条件不成立就顺延到下一条"，而实际是根本不看它
            if (!isBlank(f.getCaseValue()) && !isBlank(f.getConditionExpression())) {
                add(WfValidationIssue.Severity.ERROR, id,
                        "出线 " + f.getId() + " 同时配了 caseValue 与 conditionExpression，"
                                + "只能留一个。复杂网关比的是取值等于，不是条件成立与否");
            }
        }
        if (withCase == 0) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "complexGateway 的 " + outs.size() + " 条出线没有一条配 caseValue，"
                            + "无论判别变量是什么值都会走默认线");
        }
        if (defaultCount == 0) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "complexGateway 没有默认流。判别变量的值不匹配任何 caseValue 时，"
                            + "token 会永久停留在这里，而流程不会报任何错");
        }
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
     * 中间抛出事件：必须抛得出东西，且不能挂边界事件。
     *
     * <p><b>必须给事件引用</b>：没有 {@code signalRef} 也没有 {@code messageRef} 时，
     * 节点在运行期会抛异常。若放到部署期不管，一份"抛了个空"的流程能部署成功，
     * 跑到那一步才炸 —— 而作者看到的是"部署没问题"，排查方向会先跑到引擎上去。
     *
     * <p><b>不得挂边界事件</b>：抛事件是<b>穿透</b>的 —— token 抵达即投出去并继续往下走，
     * 它在这个节点上不会停留。BPMN 允许给中间事件挂边界事件，
     * 但挂上去的边界会找一个"宿主 token"，而这条 token 在抛完就走了：
     * 结果是订阅挂上、永远不触发，且流程图上看不出任何异常。
     */
    private void validateThrowEvent(WfDefinition definition, String id, WfNode node) {
        boolean hasSignal = !isBlank(node.getSignalName());
        boolean hasMessage = !isBlank(node.getMessageName());
        if (!hasSignal && !hasMessage) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "中间抛出事件 " + id + " 没有任何事件定义（signalEventDefinition / "
                            + "messageEventDefinition），它抛不出任何东西。"
                            + "要发通知给业务方，请改用 sendTask + delegate");
        }
        if (hasSignal && hasMessage) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "中间抛出事件 " + id + " 同时配了 signalRef=" + node.getSignalName()
                            + " 与 messageRef=" + node.getMessageName()
                            + "。两者语义不同（广播 / 点对点），本引擎不挑一个生效 —— "
                            + "请拆成两个连续节点");
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
     * 中间捕获事件：必须有本引擎等得住的事件定义，入线必须唯一且来自事件网关。
     */
    private void validateCatchEvent(WfDefinition definition, String id, WfNode node,
                                    List<WfFlow> inFlows) {
        if (!node.hasEventDefinition()) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "中间捕获事件没有任何事件定义（messageEventDefinition / "
                            + "signalEventDefinition / timerEventDefinition），"
                            + "它在图上是一条永远等不到的死路");
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
        } else if (!node.isSupportedGatewayBranch()) {
            add(WfValidationIssue.Severity.ERROR, id,
                    "中间捕获事件 " + id + " 用的是 " + describeUnsupportedCatch(node)
                            + "，本引擎目前只支持 messageEventDefinition、"
                            + "signalEventDefinition，以及作为事件网关分支的 "
                            + "timerEventDefinition");
        }
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
     * 而流程图上看不出任何异常 —— 与 {@code parallelMultiple} 是同一类偏差。
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
        return "conditionalEventDefinition / escalationEventDefinition 等未支持的捕获事件";
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
        // 重复触发：识别出来是为了报 ERROR，不是静默按单次跑。
        // 作者写"每来一次就催一遍"而实际只催一次 —— 那种偏差几个月后才被发现，
        // 而且从流程图上看不出任何异常
        if (node.isParallelMultiple()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "边界事件的 parallelMultiple=\"true\"（重复触发）本实现不支持。"
                            + "本实现只支持单次触发的边界事件：触发一次后订阅即作废。"
                            + "静默按单次跑的后果是「每来一次就催一遍」变成「只催一遍」，"
                            + "且流程图上看不出任何异常。要重复提醒请用循环定时器"
                            + "（timeCycle），或在外部按周期重复投递消息");
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
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    kind + " 缺少 " + (kind.startsWith("message") ? "messageRef" : "signalRef")
                            + "，这条边界永远不会触发");
        }
        if (node.isTimerBoundary()) {
            validateTimerBoundary(node);
        } else if (node.isMessageBoundary() || node.isSignalBoundary()) {
            // 消息/信号边界本身合法（缺名字的情况上面已单独报过）
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

    /** 完成条件里是否出现了任一标准循环变量。 */
    private static boolean referencesLoopVariable(String condition) {
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
        if (node.getType() != WfNodeType.SUB_PROCESS) {
            return;
        }
        List<WfNode> children = definition.inlineChildrenOf(node.getId());
        if (children.isEmpty()) {
            return;
        }

        // ---- 恰好一个内联起始节点 ----
        // 引擎靠"容器内无入线的节点"识别入口。有两个时选谁是猜，猜错的表现是
        // "另一半内联节点一次都没跑，而实例正常结束"；没有时更糟，token 无处可进。
        List<String> entries = inlineEntryIds(definition, children);
        if (entries.size() != 1) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "嵌入式 subProcess " + node.getId() + " 需要恰好一个内联起始节点"
                            + "（容器内没有入线的那一个），实际找到 " + entries.size() + " 个: " + entries
                            + "。内联子流程的进入点由「容器内无入线的节点」唯一确定，"
                            + "多于一个时引擎无法判断该从哪进入");
        }

        // ---- 至多一个内联结束节点 ----
        // 有两个时 token 会在第一个"结束"处跳出子流程，后半段永远跑不到；
        // 一个都没有说明容器内是个环（每个节点都有出线），token 会在里面打转。
        List<String> exits = inlineExitIds(definition, children);
        if (exits.size() > 1) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "嵌入式 subProcess " + node.getId() + " 有 " + exits.size()
                            + " 个内联结束节点（容器内没有出线的节点）: " + exits
                            + "。子流程的结束点必须唯一，否则 token 会在第一个结束处跳出，"
                            + "后面的内联内容永远跑不到");
        } else if (exits.isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "嵌入式 subProcess " + node.getId() + " 没有任何内联结束节点"
                            + "（容器内每个节点都有出线）。这意味着子流程内部成环，"
                            + "token 会在里面一直转下去");
        }

        // ---- 不支持嵌套 ----
        // 理由不是"没实现"这么轻：内层 subProcess 的离开语义与外层不同 ——
        // 它的出线只通向它自己内部，而"子流程结束了"这件事在 BPMN 里
        // 没有任何结构标记能指出是哪一个 endEvent 属于内层、哪一个属于外层。
        // 猜错的后果是内联内容被静默跳过或流程死循环，两者都不报错。
        List<String> nested = new ArrayList<>();
        for (WfNode child : children) {
            if (child.getType() == WfNodeType.SUB_PROCESS) {
                nested.add(child.getId());
            }
        }
        if (!nested.isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "嵌入式 subProcess 里又嵌了 subProcess: " + nested
                            + "。本引擎不支持嵌套的内联子流程：内层子流程的结束点"
                            + "在 BPMN 里没有结构标记能与其他结束事件区分开，"
                            + "引擎无法判断该在哪一层跳出。请改用 callActivity 指向独立流程定义");
        }

        // ---- 不支持边界事件挂在容器上 ----
        // token 一进入 subProcess 就被推进到内联起始节点，此后它<b>不在</b>
        // 容器节点上。而 WfRuntimeService#fireEventBoundary 有一道硬闸门：
        // token 必须仍停在宿主节点上才触发。放宽它要重新定义"打断"的对象，
        // 语义风险远大于本轮的收益，所以部署期直接挡掉。
        List<String> boundaries = new ArrayList<>();
        for (WfNode candidate : definition.getNodes()) {
            if (candidate != null
                    && node.getId().equals(candidate.nestedIn())
                    && candidate.getType() == WfNodeType.BOUNDARY_EVENT) {
                boundaries.add(candidate.getId());
            }
        }
        if (!boundaries.isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "嵌入式 subProcess 上不能挂边界事件: " + boundaries
                            + "。token 进入 subProcess 后立即被推进到内联起始节点，"
                            + "此后不再停留在容器上，而边界事件要求 token 仍停在宿主节点才触发。"
                            + "放到内联的某个具体活动上即可；超时/异常的处理请放到该活动上");
        }

        // ---- 画了内联内容又配 calledElementKey ----
        // 两者语义互斥：内联子流程执行的是本图里的节点，calledElementKey 指向另一份定义。
        // 引擎优先走内联（isInlineSubProcess 先判），配了 calledElementKey 的效果是
        // "看着像会调外部流程，实际一次都没调"。
        if (!isBlank(node.getCalledElementKey())) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "subProcess " + node.getId() + " 既画了内联内容，又配了 calledElementKey="
                            + node.getCalledElementKey().trim() + "。两者互斥："
                            + "内联子流程执行的是本图里的节点。引擎会以内联内容为准，"
                            + "calledElementKey 一次都不会生效。要调用独立流程请改用 callActivity");
        }
    }

    /** 容器内"无入线"的节点 id —— 内联起始节点候选。 */
    private static List<String> inlineEntryIds(WfDefinition definition, List<WfNode> children) {
        List<String> ids = new ArrayList<>();
        for (WfNode node : children) {
            if (definition.incomingFlows(node.getId()).isEmpty()) {
                ids.add(node.getId());
            }
        }
        return ids;
    }

    /** 容器内"无出线"的节点 id —— 内联结束节点候选。 */
    private static List<String> inlineExitIds(WfDefinition definition, List<WfNode> children) {
        List<String> ids = new ArrayList<>();
        for (WfNode node : children) {
            if (definition.outgoingFlows(node.getId()).isEmpty()) {
                ids.add(node.getId());
            }
        }
        return ids;
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
     * 与 {@code intermediateCatchEvent} 已分别在第 18 / 7 轮有了原生实现 ——
     * 原生类型不会再被标记成退化元素，于是这两个分支永远不会被调用。
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
