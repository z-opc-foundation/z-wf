package com.zifang.z.wf.core.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfFlow;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfNodeType;

/**
 * 测试用的流程图构造器。
 *
 * <p>抽出来而不是每个测试各写一遍，是为了让"图"这件事在测试里一眼可读：
 * 看 {@code WfTestFlows.parallel(definition)} 就知道测的是并行语义，
 * 而不用去数几个 startEvent。
 *
 * <p>所有方法都只追加节点与连线，最后统一 {@code buildIndex()}。
 *
 * @author zifang
 */
final class WfTestFlows {

    private WfTestFlows() {
    }

    /**
     * 线性流程：start → task(assignee) → end。
     */
    static WfDefinition linear(WfDefinition definition, String startId, String taskId,
                               String endId, String assignee) {
        addNodes(definition,
                node(startId, "开始", WfNodeType.START_EVENT),
                taskNode(taskId, "经理审批", assignee),
                node(endId, "结束", WfNodeType.END_EVENT));
        addFlows(definition,
                flow("f-" + startId + "-" + taskId, startId, taskId),
                flow("f-" + taskId + "-" + endId, taskId, endId));
        return definition;
    }

    /**
     * 金额分支（排他网关）：
     * <pre>
     * start → gw(排他)
     *          ├─ amount &lt; 1000      → 经理
     *          └─ amount &gt;= 1000 且 &lt; 10000 → 总经理
     *          └─ default               → 财务
     * </pre>
     * 三条支线各自汇聚到同一个 end。
     */
    static WfDefinition amountBranch(WfDefinition definition) {
        WfNode manager = taskNode("taskManager", "经理审批", "manager");
        WfNode ceo = taskNode("taskCeo", "总经理审批", "ceo");
        WfNode finance = taskNode("taskFinance", "财务审批", "finance");

        addNodes(definition,
                node("start", "提交报销", WfNodeType.START_EVENT),
                node("gw", "金额判断", WfNodeType.EXCLUSIVE_GATEWAY),
                manager, ceo, finance,
                node("end", "结束", WfNodeType.END_EVENT));

        WfFlow small = flow("f-small", "gw", "taskManager");
        small.setConditionExpression("amount < 1000");
        small.setId("f-small");

        WfFlow big = flow("f-big", "gw", "taskCeo");
        big.setConditionExpression("amount >= 1000 && amount < 10000");
        big.setId("f-big");

        WfFlow fallback = flow("f-default", "gw", "taskFinance");
        fallback.setDefaultFlow(true);
        fallback.setId("f-default");

        addFlows(definition,
                flow("f-start-gw", "start", "gw"),
                small, big, fallback,
                flow("f-m-end", "taskManager", "end"),
                flow("f-c-end", "taskCeo", "end"),
                flow("f-f-end", "taskFinance", "end"));
        return definition;
    }

    /**
     * 并行会签：
     * <pre>
     * start → fork(并行) → [法务, 财务] → join(并行) → end
     * </pre>
     */
    static WfDefinition parallel(WfDefinition definition) {
        addNodes(definition,
                node("start", "提交", WfNodeType.START_EVENT),
                node("fork", "并行开始", WfNodeType.PARALLEL_GATEWAY),
                taskNode("taskLegal", "法务审批", "legal"),
                taskNode("taskFinance", "财务审批", "finance"),
                node("join", "并行汇合", WfNodeType.PARALLEL_GATEWAY),
                node("end", "结束", WfNodeType.END_EVENT));

        addFlows(definition,
                flow("f-s-fork", "start", "fork"),
                flow("f-fork-legal", "fork", "taskLegal"),
                flow("f-fork-fin", "fork", "taskFinance"),
                flow("f-legal-join", "taskLegal", "join"),
                flow("f-fin-join", "taskFinance", "join"),
                flow("f-join-end", "join", "end"));
        return definition;
    }

    // ==================== 构件 ====================

    private static WfNode node(String id, String name, WfNodeType type) {
        return new WfNode(id, name, type);
    }

    private static WfNode taskNode(String id, String name, String assignee) {
        WfNode node = new WfNode(id, name, WfNodeType.USER_TASK);
        node.setAssignee(assignee);
        return node;
    }

    private static WfFlow flow(String id, String source, String target) {
        WfFlow flow = new WfFlow(source, target);
        flow.setId(id);
        return flow;
    }

    private static void addNodes(WfDefinition definition, WfNode... nodes) {
        List<WfNode> list = definition.getNodes() == null
                ? new ArrayList<WfNode>() : definition.getNodes();
        list.addAll(Arrays.asList(nodes));
        definition.setNodes(list);
    }

    private static void addFlows(WfDefinition definition, WfFlow... flows) {
        List<WfFlow> list = definition.getFlows() == null
                ? new ArrayList<WfFlow>() : definition.getFlows();
        list.addAll(Arrays.asList(flows));
        definition.setFlows(list);
    }

    /**
     * 便捷入口：新建 + 线性 + 建索引。
     */
    static WfDefinition simpleLinear(String key, String assignee) {
        WfDefinition definition = new WfDefinition(key, key);
        linear(definition, "start1", "task1", "end1", assignee);
        definition.buildIndex();
        return definition;
    }
}
