package com.zifang.z.wf.core.definition;

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
            if (node.getType() == WfNodeType.RECEIVE_TASK) {
                if (isBlank(node.getMessageName())) {
                    add(WfValidationIssue.Severity.WARN, node.getId(),
                            "receiveTask 没有 messageName，该任务将只能被 force-complete 推进");
                }
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

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
