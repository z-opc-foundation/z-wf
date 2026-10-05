package com.zifang.z.wf.core.definition;

import com.zifang.z.wf.core.engine.WfEngine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
                if (isBlank(node.getDelegateClass()) && isBlank(node.getDelegateExpression())) {
                    add(WfValidationIssue.Severity.ERROR, node.getId(),
                            "serviceTask 需要 delegateClass 或 delegateExpression 之一");
                }
            }
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
            // ---- 嵌入式 subProcess：内联内容不会被执行，必须挡住部署 ----
            // 解析器把 <subProcess> 里的内联节点也收进扁平节点表（isDirectChildOf
            // 判的是"祖先链里有 process"，不是"直接子节点"），但引擎不会进入
            // 嵌入式子流程 —— 它没有独立的作用域与 token。结果是流程从 subProcess
            // 节点直接穿到它的出线，内联的那些节点一个都不会执行，
            // 而流程照样跑到结束、状态 COMPLETED。
            // 与其让作者以为"我画了个子流程所以它会跑"，不如部署时就拒绝。
            if (node.getType() == WfNodeType.SUB_PROCESS) {
                List<String> inline = inlineChildIds(definition, node.getId());
                if (!inline.isEmpty()) {
                    add(WfValidationIssue.Severity.ERROR, node.getId(),
                            "嵌入式 subProcess 的内联内容不会被执行（本引擎尚不支持嵌入式子流程）。"
                                    + "被收进节点表但永远跑不到的内联节点: " + inline
                                    + "。请改用 callActivity 指向一个独立的流程定义 key");
                }
            }
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
     * 图形状校验：开始节点唯一性、网关默认流、死节点、死胡同。
     */
    private void validateGraphShape(WfDefinition definition, Set<String> ids) {
        definition.buildIndex();

        // ---- 恰好一个开始节点 ----
        List<WfNode> starts = new ArrayList<>();
        for (String id : ids) {
            WfNode node = definition.node(id);
            if (node != null && node.getType() == WfNodeType.START_EVENT) {
                starts.add(node);
            }
        }
        if (starts.isEmpty()) {
            // 退化判定：无入线节点
            for (String id : ids) {
                if (definition.incomingFlows(id).isEmpty()) {
                    starts.add(definition.node(id));
                }
            }
        }
        if (starts.isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, null, "找不到开始节点（无 startEvent 且无无入线节点）");
        } else if (starts.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (WfNode n : starts) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(n.getId());
            }
            add(WfValidationIssue.Severity.ERROR, null, "存在多个开始节点: " + sb);
        }

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
     * 便捷判定：是否含 ERROR 级问题。
     */
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
     *   <li>缺 errorCode ⇒ 无法决定捕不捕获。这里刻意<b>不接受</b> BPMN 里的
     *       "空 errorRef 表示捕获所有错误"：宽泛捕获会把不相关的异常也吸走，
     *       让本该崩掉的流程继续走下去，而那正是边界事件最该避免的事</li>
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
        if (isBlank(node.getErrorCode())) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "boundaryEvent 缺少 errorCode。本实现刻意不支持"
                            + "「空 errorRef = 捕获所有错误」：宽泛捕获会把不相关的异常也吸走，"
                            + "让本该崩掉的流程继续走下去。请显式写明捕获哪一种错误");
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
     * 多实例节点校验。
     *
     * <p>三条规则都报 ERROR，因为它们各自的失败模式都是"部署成功、运行时出错"：
     * <ul>
     *   <li>没有 loopCardinality ⇒ 不知道要造几个实例，运行时才发现</li>
     *   <li>写了 collection 迭代 ⇒ 本版不支持，运行时才发现</li>
     *   <li>isSequential=true ⇒ 串行与并行的完成判定不同，运行时才发现</li>
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
        Object collection = node.property(WfXmlParser.PROPERTY_LOOP_COLLECTION);
        if (collection != null && !String.valueOf(collection).trim().isEmpty()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "多实例的 collection 集合迭代本版不支持（collection="
                            + collection + "）。本版按 loopCardinality 展开固定个数，"
                            + "请改用 loopCardinality");
        }
        if (isBlank(node.getLoopCardinality())) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "多实例节点缺少 loopCardinality，无法确定要展开几个实例");
        } else if (node.getLoopCardinality().trim().matches("\\d+")) {
            // 字面量个数在部署期就是已知的：超上限要在部署时挡，
            // 而不是等到运行时一次性造出上亿个 token 把库打满
            long literal = Long.parseLong(node.getLoopCardinality().trim());
            if (literal > WfEngine.MAX_LOOP_INSTANCES) {
                add(WfValidationIssue.Severity.ERROR, node.getId(),
                        "多实例 loopCardinality=" + literal + " 超过上限 "
                                + WfEngine.MAX_LOOP_INSTANCES
                                + "，拒绝展开（一个手滑的表达式不该把内存和数据库同时打满）");
            }
        }
        if (node.isSequential()) {
            add(WfValidationIssue.Severity.ERROR, node.getId(),
                    "多实例的 isSequential=true（逐个串行）本版不支持。"
                            + "串行与并行的完成判定不同，半套实现比不做更危险，"
                            + "请改成并行（去掉 isSequential）");
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
     * 找出被嵌在指定容器元素里的节点 id。
     *
     * <p>依赖解析期写下的 {@link WfNode#PROPERTY_NESTED_IN} 标记 ——
     * 解析结果���扁平表，父子关系不靠这个标记无从还原。
     */
    private static List<String> inlineChildIds(WfDefinition definition, String containerId) {
        List<String> inline = new ArrayList<>();
        if (definition == null || definition.getNodes() == null) {
            return inline;
        }
        for (WfNode node : definition.getNodes()) {
            if (node != null && containerId.equals(node.nestedIn())) {
                inline.add(node.getId());
            }
        }
        return inline;
    }

    /**
     * 对已知可替代的元素给出"改用哪个"的建议。
     *
     * <p>只对<b>真的等价</b>的替代关系给建议。{@code eventBasedGateway} 刻意不给：
     * 拿 {@code exclusiveGateway} 顶替它不是简化，是把一个"多路事件竞速"换成
     * "在顺序条件里选一条"，作者照着提示改反而会得到一个更难发现的错误流程。
     * 没有等价物时如实说"暂无"，比给个像模像样的错答案可靠。
     */
    private static String substitutionHint(String elementName) {
        String key = elementName == null ? "" : elementName.trim();
        String replacement;
        if ("intermediateThrowEvent".equals(key)) {
            replacement = "sendTask";
        } else if ("intermediateCatchEvent".equals(key)) {
            replacement = "receiveTask";
        } else if ("transaction".equals(key) || "adHocSubProcess".equals(key)) {
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
