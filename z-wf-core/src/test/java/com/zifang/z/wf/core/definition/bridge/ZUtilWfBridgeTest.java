package com.zifang.z.wf.core.definition.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.util.wf.kernel.bpmn.BpmnDiagram;
import com.zifang.util.wf.kernel.bpmn.BpmnModelConverter;
import com.zifang.util.wf.kernel.config.Connector;
import com.zifang.util.wf.kernel.config.WorkflowConfiguration;
import com.zifang.util.wf.kernel.config.WorkflowNode;
import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfFlow;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.service.WfTaskService;

/**
 * z-util-wf ↔ z-wf 协议桥测试。
 *
 * <p>三件事必须被证明，缺一件"联合"就只是口号：
 * <ol>
 *   <li><b>同一份 BPMN 走两条解析路径 + 桥，落到同一个图</b>
 *       （{@link #parityThroughBridge}）—— 比只比节点类型的 {@code parityWithZUtilWfKernel} 更强，
 *       因为它连上了"邻接表 → 边列表"这一段形状转换。</li>
 *   <li><b>往返无损</b>（{@link #roundTripIsLossless}）—— z-wf 定义转成内存定义再转回来，
 *       条件、默认流、审批字段一个都不能少。</li>
 *   <li><b>歧义条件 fail-closed</b>（{@link #ambiguousConditionIsRejected}）——
 *       这是审批安全红线：条件归属不明时必须拒绝转换，绝不能丢掉条件让它退化成"无条件出线"。</li>
 * </ol>
 *
 * <p>{@link #bridgedInMemoryDefinitionActuallyRuns} 是最终目的的证明：
 * 内存里搭的图，桥进生产引擎后能真的审批完。
 *
 * @author zifang
 */
class ZUtilWfBridgeTest {

    private InMemoryWorkflowPersistence persistence;
    private WfRepositoryService repositoryService;
    private WfRuntimeService runtimeService;
    private WfTaskService taskService;

    @BeforeEach
    void setUp() {
        persistence = new InMemoryWorkflowPersistence();
        persistence.initialize();
        WfEngine engine = new WfEngine();
        repositoryService = new WfRepositoryService(persistence);
        WfHookDispatcher hooks = new WfHookDispatcher();
        runtimeService = new WfRuntimeService(repositoryService, persistence, engine, hooks);
        taskService = new WfTaskService(repositoryService, persistence, runtimeService, hooks);
    }

    // ==================== 1. 协议一致（走完整路径） ====================

    @Test
    @DisplayName("协议一致：同一份 BPMN 经 z-util-wf 解析+桥接，落到与 z-wf 直接解析完全相同的图")
    void parityThroughBridge() {
        BpmnDiagram diagram = new com.zifang.util.wf.kernel.bpmn.BpmnXmlParser().parse(BPMN_SHARED);
        WorkflowConfiguration config = new BpmnModelConverter().convert(diagram);
        WfDefinition bridged = ZUtilWfBridge.toWfDefinition(config, "leaveProcess", "请假流程");
        WfDefinition direct = new WfXmlParser().parse(BPMN_SHARED);

        // 节点集合：id → 类型 必须完全一致
        assertEquals(direct.getNodes().size(), bridged.getNodes().size(),
                "节点数量不一致，说明两边支持的元素集合已分叉");
        for (WfNode expected : direct.getNodes()) {
            WfNode actual = bridged.node(expected.getId());
            assertNotNull(actual, "桥接后缺少节点 " + expected.getId());
            assertEquals(expected.getType(), actual.getType(),
                    "节点 " + expected.getId() + " 两侧类型不一致："
                            + "z-wf=" + expected.getType() + " 桥接后=" + actual.getType());
        }

        // 图结构一致（忽略连线 id 与顺序）
        assertEquals(edges(direct), edges(bridged), "边集合不一致");

        // 默认流与条件也要落到同一条边上
        assertEquals(defaultTarget(direct, "gw1"), defaultTarget(bridged, "gw1"),
                "默认流的落点不一致");
        assertEquals(conditionOf(direct, "gw1", "task2"), conditionOf(bridged, "gw1", "task2"),
                "task2 入线条件不一致");
        assertEquals(conditionOf(direct, "gw1", "task3"), conditionOf(bridged, "gw1", "task3"),
                "task3 入线条件不一致");
    }

    @Test
    @DisplayName("已知限制：z-util-wf 邻接表会合并并列边，桥接无法复原（带并列边的图请直接用 z-wf 解析）")
    void parallelEdgesCollapseIsDocumentedLimitation() {
        // 同一对节点之间的两条并列边（一条带条件 + 一条 default），全部用标准写法。
        // BpmnModelConverter 装配 post 时用 contains 去重，边在<b>输入侧</b>就没了，
        // 桥接不可能凭空造回来 —— 这条测试把这个事实钉住，防止有人"顺手修复"成静默合并。
        BpmnDiagram diagram = new com.zifang.util.wf.kernel.bpmn.BpmnXmlParser().parse(BPMN_PARALLEL_EDGES);
        WfDefinition direct = new WfXmlParser().parse(BPMN_PARALLEL_EDGES);
        assertEquals(2, countTarget(direct, "gw1", "task2"),
                "前提：z-wf 侧确实有两条并列边");

        WorkflowConfiguration config = new BpmnModelConverter().convert(diagram);
        WfDefinition bridged = ZUtilWfBridge.toWfDefinition(config, "leaveProcess", "请假流程");

        assertEquals(1, countTarget(bridged, "gw1", "task2"),
                "桥接后只剩一条边 —— 这是邻接表的信息丢失，不是桥接丢的");
        // 合并后的那条边同时带条件与默认流标记：条件成立走它，不成立也回退到它
        WfFlow merged = findFlow(bridged, "gw1", "task2", null);
        assertEquals("days <= 3", merged.getConditionExpression());
        assertTrue(merged.isDefaultFlow(),
                "defaultFlow 标记被合并到幸存的那条边上，行为与原图不同，必须由文档而非静默承担");
    }

    @Test
    @DisplayName("已知限制：z-util-wf 读不到 z-wf 私有扩展 zifang:defaultFlow，绕一圈默认流就没了")
    void zifangDefaultFlowIsInvisibleToZUtilWf() {
        // BPMN_LEAVE 用的是 z-wf 扩展写法 zifang:defaultFlow。
        // z-util-wf 的 BpmnXmlParser 只读标准 default 属性，读不到 ⇒ 桥接后完全没有默认流。
        WfDefinition direct = new WfXmlParser().parse(BPMN_LEAVE);
        assertEquals(1, countDefaults(direct.outgoingFlows("gw1")), "前提：z-wf 侧有默认流");

        BpmnDiagram diagram = new com.zifang.util.wf.kernel.bpmn.BpmnXmlParser().parse(BPMN_LEAVE);
        WfDefinition bridged = ZUtilWfBridge.toWfDefinition(
                new BpmnModelConverter().convert(diagram), "leaveProcess", "请假流程");

        assertEquals(0, countDefaults(bridged.outgoingFlows("gw1")),
                "绕 z-util-wf 一圈后默认流消失；此时条件全不成立会让 token 停住而不是走错分支");
    }

    // ==================== 2. 往返无损 ====================

    @Test
    @DisplayName("往返无损：z-wf → 内存定义 → z-wf，节点/条件/默认流/审批字段全部还原")
    void roundTripIsLossless() {
        WfDefinition original = new WfXmlParser().parse(BPMN_LEAVE);
        WorkflowConfiguration config = ZUtilWfBridge.toWorkflowConfiguration(original);
        WfDefinition back = ZUtilWfBridge.toWfDefinition(config, "leaveProcess", "请假流程");

        for (WfNode expected : original.getNodes()) {
            WfNode actual = back.node(expected.getId());
            assertNotNull(actual, "往返后缺少节点 " + expected.getId());
            assertEquals(expected.getType(), actual.getType(), "节点类型 " + expected.getId());
            assertEquals(expected.getName(), actual.getName(), "节点名称 " + expected.getId());
        }

        // 边集合（含重数：这份 BPMN 里 gw1→task2 有两条并列边）
        assertEquals(edges(original), edges(back), "往返后边集合不一致");

        // 审批语义字段（z-util-wf 没有专有字段，靠 cache 承载）
        assertEquals("manager", back.node("task1").getAssignee());
        assertEquals("leaveForm", back.node("task1").getFormKey());
        assertEquals("PT24H", back.node("task1").getDueDateDuration());
        assertEquals(Arrays.asList("dept-managers"), back.node("task1").getCandidateGroups());
        assertEquals("hr", back.node("task2").getAssignee());
        assertEquals("审批", back.getCategory(), "定义级分类应随起始节点带回来");

        // 条件与默认流
        WfFlow toHr = findFlow(back, "gw1", "task2", "days <= 3");
        WfFlow toCeo = findFlow(back, "gw1", "task3", "days > 3");
        assertNotNull(toHr, "days<=3 这条线的条件应被还原");
        assertNotNull(toCeo, "days>3 这条线的条件应被还原");
        assertEquals(1, countDefaults(back.outgoingFlows("gw1")), "默认流标记应被还原");
        // 并列的两条边不能被合并：一条带条件、一条是默认流
        List<WfFlow> toHrEdges = new ArrayList<WfFlow>();
        for (WfFlow flow : back.outgoingFlows("gw1")) {
            if ("task2".equals(flow.getTargetRef())) {
                toHrEdges.add(flow);
            }
        }
        assertEquals(2, toHrEdges.size(), "两条并列边必须都保住");
        assertEquals(1, countDefaults(toHrEdges), "并列边里只有一条带默认流标记");
    }

    @Test
    @DisplayName("往返无损（并行汇合形态）：分叉与汇合的边一个不少")
    void roundTripKeepsParallelShape() {
        WfDefinition original = new WfXmlParser().parse(BPMN_PARALLEL);
        WfDefinition back = ZUtilWfBridge.toWfDefinition(
                ZUtilWfBridge.toWorkflowConfiguration(original), "parallel", "并行");

        assertEquals(edges(original), edges(back));
        assertEquals(5, back.outgoingFlows("fork").size(), "分叉应有 5 条出线");
        assertEquals(5, back.incomingFlows("join").size(), "汇合应有 5 条入线");
    }

    @Test
    @DisplayName("往返无损（多入线带条件）：按源节点分键保存，条件不互相覆盖")
    void roundTripKeepsConditionsOnMergeNode() {
        // gw1 / gw2 两条线都进 taskMerge，且各自带不同条件。
        // z-util-wf 的单值 invokeParameter 存不下这种图，桥接必须用分键写法保住两条条件。
        WfDefinition original = new WfDefinition("merge", "汇合");
        original.setNodes(Arrays.asList(
                new WfNode("s", "开始", WfNodeType.START_EVENT),
                new WfNode("gw1", "判断1", WfNodeType.EXCLUSIVE_GATEWAY),
                new WfNode("gw2", "判断2", WfNodeType.EXCLUSIVE_GATEWAY),
                new WfNode("taskMerge", "汇合审批", WfNodeType.USER_TASK),
                new WfNode("e", "结束", WfNodeType.END_EVENT)));
        WfFlow f0 = new WfFlow("s", "gw1");
        WfFlow f1 = new WfFlow("gw1", "taskMerge");
        f1.setConditionExpression("role == 'boss'");
        WfFlow f2 = new WfFlow("taskMerge", "gw2");
        WfFlow f3 = new WfFlow("gw2", "taskMerge");
        f3.setConditionExpression("role == 'staff'");
        WfFlow f4 = new WfFlow("taskMerge", "e");
        original.setFlows(Arrays.asList(f0, f1, f2, f3, f4));
        original.buildIndex();

        WfDefinition back = ZUtilWfBridge.toWfDefinition(
                ZUtilWfBridge.toWorkflowConfiguration(original), "merge", "汇合");

        assertEquals("role == 'boss'", findFlow(back, "gw1", "taskMerge", null).getConditionExpression());
        assertEquals("role == 'staff'", findFlow(back, "gw2", "taskMerge", null).getConditionExpression(),
                "两条入线条件必须各自保留，不能互相覆盖");
    }

    // ==================== 3. 歧义条件 fail-closed ====================

    @Test
    @DisplayName("fail-closed：目标节点多入线且条件只在单值字段 ⇒ 拒绝转换，不丢条件")
    void ambiguousConditionIsRejected() {
        // 手工构造 z-util-wf 的历史形态：条件写在目标节点的 invokeParameter 上，
        // 而该目标有两条入线 —— 条件属于哪条线已经不可还原。
        WorkflowConfiguration config = new WorkflowConfiguration();
        config.setWorkflowNodeList(Arrays.asList(
                node("s", "startEvent", "e1"),
                node("gw1", "exclusiveGateway", "m"),
                node("gw2", "exclusiveGateway", "m"),
                node("m", "userTask", "e2"),
                node("e1", "endEvent", null),
                node("e2", "endEvent", null)));
        ((WorkflowNode) config.getWorkflowNodeList().get(3)).setInvokeParameter("role == 'boss'");

        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> ZUtilWfBridge.toWfDefinition(config, "ambiguous", "歧义"));
        assertTrue(e.getMessage().contains("conditionExpression"),
                "报错要指明改用哪种写法，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("fail-closed 的理由：条件若被丢弃，引擎会把它当无条件出线直接放行")
    void droppedConditionWouldFailOpen() {
        // 这条测试把"为什么桥接必须报错而不是丢条件"钉成可执行事实：
        // 排他网关的一条出线丢了条件后 isUnconditional() 为 true ⇒ 无条件通过。
        WfDefinition definition = new WfDefinition("failopen", "放行");
        WfNode needApprove = new WfNode("needApprove", "需审批", WfNodeType.USER_TASK);
        needApprove.setAssignee("manager");
        definition.setNodes(Arrays.asList(
                new WfNode("s", "开始", WfNodeType.START_EVENT),
                new WfNode("gw", "金额判断", WfNodeType.EXCLUSIVE_GATEWAY),
                needApprove,
                new WfNode("e", "结束", WfNodeType.END_EVENT)));

        // 正常形态：首条出线带条件，amount=0 时不成立 ⇒ 走 default
        WfFlow conditioned = new WfFlow("gw", "needApprove");
        conditioned.setConditionExpression("amount > 1000");
        WfFlow skipped = new WfFlow("gw", "e");
        skipped.setDefaultFlow(true);
        definition.setFlows(Arrays.asList(
                new WfFlow("s", "gw"), conditioned, skipped, new WfFlow("needApprove", "e")));
        definition.buildIndex();
        repositoryService.deploy(definition);

        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", 0);
        runtimeService.startProcessInstance("failopen", null, "BIZ-1", "alice", null, vars);
        assertEquals(0, taskService.getTodoList("manager", null, 1, 10).size(),
                "条件成立判定下 amount=0 不该产生待办");

        // 把条件丢掉（桥接歧义时若"猜一个"或"直接丢"就是这个结果）：
        // 同一条线不再被求值，amount=0 也照样放行 ⇒ 本该审批的单据被静默通过
        WfDefinition lossy = repositoryService.getDefinition("failopen", 1);
        lossy.getFlows().get(1).setConditionExpression(null);
        lossy.buildIndex();
        repositoryService.deploy(lossy);

        runtimeService.startProcessInstance("failopen", null, "BIZ-2", "alice", null,
                new HashMap<String, Object>(vars));
        assertEquals(1, taskService.getTodoList("manager", null, 1, 10).size(),
                "丢掉条件后流程直接走 needApprove —— 这正是 fail-open，也是桥接必须报错的原因");
    }

    // ==================== 4. 桥接的定义能真的跑 ====================

    @Test
    @DisplayName("内存里搭的图桥进生产引擎后能真的审批完（联合的最终目的）")
    void bridgedInMemoryDefinitionActuallyRuns() {
        WfDefinition bridged = ZUtilWfBridge.toWfDefinition(
                buildInMemoryLeave(), "inMemoryLeave", "内存请假");
        repositoryService.deploy(bridged);

        // days=5 ⇒ 走 ceo 分支
        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 5);
        String processId = runtimeService.startProcessInstance(
                "inMemoryLeave", null, "IM-1", "alice", null, vars);
        assertNotNull(processId);

        assertEquals(1, taskService.getTodoList("manager", null, 1, 10).size(), "经理应有待办");
        assertEquals(0, taskService.getTodoList("hr", null, 1, 10).size(), "days=5 不该走 HR");

        WfTask managerTask = taskService.getTodoList("manager", null, 1, 10).get(0);
        assertEquals("leaveForm", managerTask.getFormKey(), "表单编码应从内存定义带过来");
        runtimeService.completeTask(managerTask.getId(), "manager", "同意", null);

        assertEquals(0, taskService.getTodoList("hr", null, 1, 10).size(),
                "条件 days <= 3 在 days=5 时应不成立");
        List<WfTask> ceoTodos = taskService.getTodoList("ceo", null, 1, 10);
        assertEquals(1, ceoTodos.size(), "days=5 应走总经理审批");
        WfProcessInstance done = runtimeService.completeTask(ceoTodos.get(0).getId(), "ceo", "同意", null);
        assertEquals(WfProcessStatus.COMPLETED, done.getStatus());
    }

    @Test
    @DisplayName("桥接的定义里，未定义变量让条件 fail-closed（审批不会被静默放行）")
    void bridgedDefinitionFailsClosedOnUndefinedVariable() {
        WfDefinition bridged = ZUtilWfBridge.toWfDefinition(buildInMemoryLeave(), "inMemoryLeave2", "内存请假");
        repositoryService.deploy(bridged);

        // 不传 days 变量 ⇒ 两条条件都引用了未定义变量 ⇒ 都不成立 ⇒ 走 defaultFlow(ceo)
        String processId = runtimeService.startProcessInstance(
                "inMemoryLeave2", null, "IM-2", "alice", null, new HashMap<String, Object>());
        assertNotNull(processId);
        WfTask managerTask = taskService.getTodoList("manager", null, 1, 10).get(0);
        runtimeService.completeTask(managerTask.getId(), "manager", "同意", null);

        assertEquals(0, taskService.getTodoList("hr", null, 1, 10).size(),
                "未定义变量时 days<=3 必须判不成立");
        assertEquals(1, taskService.getTodoList("ceo", null, 1, 10).size(),
                "应落到 default 流而不是卡死或乱走");
    }

    // ==================== 5. 边界 ====================

    @Test
    @DisplayName("空/坏输入报出可诊断的原因")
    void badInputIsDiagnosable() {
        assertThrows(WfDefinitionException.class,
                () -> ZUtilWfBridge.toWfDefinition(null, "k", "k"));
        assertThrows(WfDefinitionException.class,
                () -> ZUtilWfBridge.toWfDefinition(new WorkflowConfiguration(), "k", "k"));
        assertThrows(WfDefinitionException.class,
                () -> ZUtilWfBridge.toWorkflowConfiguration(null));
    }

    @Test
    @DisplayName("非法 priority 直接报错，不静默退回默认值")
    void invalidPriorityIsRejected() {
        WorkflowNode task = node("t", "userTask", null);
        task.getCache().put(ZUtilWfBridge.KEY_PRIORITY, "not-a-number");
        WorkflowConfiguration config = new WorkflowConfiguration();
        config.setWorkflowNodeList(Arrays.asList(task));
        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> ZUtilWfBridge.toWfDefinition(config, "k", "k"));
        assertTrue(e.getMessage().contains("not-a-number"), "报错应带上原值，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("post 的重数就是并列边条数；pre 的双向冗余不会凭空造边")
    void postMultiplicityIsAuthoritative() {
        // post 里重复出现的目标 = 两条并列边。桥接不去重：
        // 去重会把手工写出的并列边吞掉，拓扑悄悄变了却没人知道。
        WorkflowConfiguration config = new WorkflowConfiguration();
        WorkflowNode a = node("a", "userTask", null);
        WorkflowNode b = node("b", "userTask", null);
        a.getConnector().getPost().add("b");
        a.getConnector().getPost().add("b");
        b.getConnector().getPre().add("a");
        config.setWorkflowNodeList(Arrays.asList(a, b));
        WfDefinition definition = ZUtilWfBridge.toWfDefinition(config, "dup", "并列边");
        assertEquals(2, definition.getFlows().size(), "post 里的两次登记 = 两条边");

        // 只登记 pre 不登记 post ⇒ 不产生边（前向是双向冗余，后向才是权威）
        WorkflowConfiguration preOnly = new WorkflowConfiguration();
        WorkflowNode c = node("c", "userTask", null);
        WorkflowNode d = node("d", "userTask", null);
        d.getConnector().getPre().add("c");
        preOnly.setWorkflowNodeList(Arrays.asList(c, d));
        assertEquals(0, ZUtilWfBridge.toWfDefinition(preOnly, "preonly", "只有pre")
                .getFlows().size(), "pre 列表不产生连线");
    }

    private static int countTarget(WfDefinition definition, String source, String target) {
        int count = 0;
        for (WfFlow flow : definition.outgoingFlows(source)) {
            if (target.equals(flow.getTargetRef())) {
                count++;
            }
        }
        return count;
    }

    @Test
    @DisplayName("cache 里的业务自定义键原样带进扩展属性，不静默蒸发")
    void unknownCacheKeysSurvive() {
        WorkflowNode task = node("t", "userTask", null);
        task.getCache().put("业务方自定义键", "值");
        WorkflowConfiguration config = new WorkflowConfiguration();
        config.setWorkflowNodeList(Arrays.asList(task));
        WfDefinition definition = ZUtilWfBridge.toWfDefinition(config, "k", "k");
        assertEquals("值", definition.node("t").property("业务方自定义键"));
    }

    // ==================== 辅助 ====================

    /**
     * 造一个 z-util-wf 节点，并接上 {@code post} 出线。
     */
    private static WorkflowNode node(String id, String type, String postTo) {
        WorkflowNode node = new WorkflowNode();
        node.setNodeId(id);
        node.setType(type);
        Connector connector = new Connector(new ArrayList<String>(), new ArrayList<String>());
        if (postTo != null) {
            connector.getPost().add(postTo);
        }
        node.setConnector(connector);
        node.setCache(new HashMap<String, String>());
        return node;
    }

    private static WorkflowConfiguration buildInMemoryLeave() {
        WorkflowConfiguration config = new WorkflowConfiguration();
        WorkflowNode start = node("s", "startEvent", "t1");
        WorkflowNode task = node("t1", "userTask", "gw");
        task.getCache().put(ZUtilWfBridge.KEY_ASSIGNEE, "manager");
        task.getCache().put(ZUtilWfBridge.KEY_FORM_KEY, "leaveForm");
        // 两条出线都要在 post 里：default 标记只说"回退到谁"，
        // 不会自动建边。漏掉 ceoTask 就是一条不可达的默认流（写测试时踩过）
        WorkflowNode gw = node("gw", "exclusiveGateway", null);
        gw.getConnector().getPost().add("hrTask");
        gw.getConnector().getPost().add("ceoTask");
        gw.getCache().put(ZUtilWfBridge.KEY_DEFAULT_FLOW, "ceoTask");
        WorkflowNode hrTask = node("hrTask", "userTask", "e");
        hrTask.getCache().put(ZUtilWfBridge.KEY_ASSIGNEE, "hr");
        WorkflowNode ceoTask = node("ceoTask", "userTask", "e");
        ceoTask.getCache().put(ZUtilWfBridge.KEY_ASSIGNEE, "ceo");
        WorkflowNode end = node("e", "endEvent", null);
        config.setWorkflowNodeList(Arrays.asList(start, task, gw, hrTask, ceoTask, end));
        // 条件按内存引擎的约定挂在目标节点上：两条线各有一个目标，无歧义
        hrTask.setInvokeParameter("days <= 3");
        ceoTask.setInvokeParameter("days > 3");
        return config;
    }

    /** 定义图的边集合（"source->target"），与连线顺序无关。 */
    private static List<String> edges(WfDefinition definition) {
        List<String> result = new ArrayList<String>();
        for (WfFlow flow : definition.getFlows()) {
            result.add(flow.getSourceRef() + "->" + flow.getTargetRef());
        }
        java.util.Collections.sort(result);
        return result;
    }

    private static WfFlow findFlow(WfDefinition definition, String source, String target, String condition) {
        for (WfFlow flow : definition.outgoingFlows(source)) {
            if (!target.equals(flow.getTargetRef())) {
                continue;
            }
            if (condition == null || condition.equals(flow.getConditionExpression())) {
                return flow;
            }
        }
        return null;
    }

    private static int countDefaults(List<WfFlow> flows) {
        int count = 0;
        for (WfFlow flow : flows) {
            if (flow.isDefaultFlow()) {
                count++;
            }
        }
        return count;
    }

    /** 某网关上带默认流标记的边的目标节点 id。 */
    private static String defaultTarget(WfDefinition definition, String gatewayId) {
        for (WfFlow flow : definition.outgoingFlows(gatewayId)) {
            if (flow.isDefaultFlow()) {
                return flow.getTargetRef();
            }
        }
        return null;
    }

    /** 某条边上的条件表达式（按 source→target 精确匹配）。 */
    private static String conditionOf(WfDefinition definition, String source, String target) {
        WfFlow flow = findFlow(definition, source, target, null);
        return flow == null ? null : flow.getConditionExpression();
    }

    /**
     * 跨引擎共享夹具：只用两个解析器都认得的写法。
     *
     * <p>刻意避开两处不可比的地方：
     * <ul>
     *   <li>默认流用标准 {@code default="true"} 属性 —— z-util-wf 的 {@code BpmnXmlParser}
     *       只读这个属性，读不到 z-wf 的 {@code zifang:defaultFlow} 扩展</li>
     *   <li>没有同一对节点之间的并列边 —— {@code BpmnModelConverter} 装配 post 时会去重，
     *       拓扑天然对不上（见 {@link #parallelEdgesCollapseIsDocumentedLimitation}）</li>
     * </ul>
     * 拿带 z-wf 私有扩展的图去比"协议一致"，比的是两套方言而不是同一份协议。
     */
    static final String BPMN_SHARED =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "             xmlns:zifang=\"http://zifang.com/wf/bpmn/ext\" "
            + "             targetNamespace=\"http://zifang.com/wf\">"
            + "  <process id=\"leaveProcess\" name=\"请假流程\" zifang:category=\"审批\">"
            + "    <startEvent id=\"start1\" name=\"提交申请\"/>"
            + "    <userTask id=\"task1\" name=\"经理审批\" "
            + "              zifang:assignee=\"manager\" zifang:formKey=\"leaveForm\" "
            + "              zifang:candidateGroups=\"dept-managers\" zifang:dueDate=\"PT24H\"/>"
            + "    <exclusiveGateway id=\"gw1\" name=\"天数判断\"/>"
            + "    <userTask id=\"task2\" name=\"HR 备案\" zifang:assignee=\"hr\"/>"
            + "    <userTask id=\"task3\" name=\"总经理审批\" zifang:assignee=\"ceo\"/>"
            + "    <endEvent id=\"end1\" name=\"结束\"/>"
            + "    <sequenceFlow id=\"f0\" sourceRef=\"start1\" targetRef=\"task1\"/>"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"task1\" targetRef=\"gw1\"/>"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"gw1\" targetRef=\"task2\">"
            + "      <conditionExpression>days &lt;= 3</conditionExpression></sequenceFlow>"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"gw1\" targetRef=\"task3\" default=\"true\">"
            + "      <conditionExpression>days &gt; 3</conditionExpression></sequenceFlow>"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"task2\" targetRef=\"end1\"/>"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"task3\" targetRef=\"end1\"/>"
            + "  </process>"
            + "</definitions>";

    /**
     * 并列边夹具：gw1 → task2 有两条边（一条带条件、一条是默认流），全部用标准写法。
     *
     * <p>z-wf 能完整表达，z-util-wf 的邻接表只能存成一条 —— 两者的差集就是
     * {@link #parallelEdgesCollapseIsDocumentedLimitation} 钉住的那个事实。
     */
    static final String BPMN_PARALLEL_EDGES =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "             targetNamespace=\"http://zifang.com/wf\">"
            + "  <process id=\"leaveProcess\" name=\"请假流程\">"
            + "    <startEvent id=\"start1\"/>"
            + "    <exclusiveGateway id=\"gw1\"/>"
            + "    <userTask id=\"task2\" name=\"HR 备案\"/>"
            + "    <userTask id=\"task3\" name=\"总经理审批\"/>"
            + "    <endEvent id=\"end1\"/>"
            + "    <sequenceFlow id=\"f0\" sourceRef=\"start1\" targetRef=\"gw1\"/>"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"gw1\" targetRef=\"task2\">"
            + "      <conditionExpression>days &lt;= 3</conditionExpression></sequenceFlow>"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"gw1\" targetRef=\"task3\">"
            + "      <conditionExpression>days &gt; 3</conditionExpression></sequenceFlow>"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"gw1\" targetRef=\"task2\" default=\"true\"/>"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"task2\" targetRef=\"end1\"/>"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"task3\" targetRef=\"end1\"/>"
            + "  </process>"
            + "</definitions>";

    /**
     * 请假流程 BPMN（z-wf 原生形态）：带 z-wf 私有扩展 + 同一对节点之间的并列边。
     *
     * <p>用于往返测试（走本桥自己写出的配置，能保住并列边）。
     */
    static final String BPMN_LEAVE =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "             xmlns:zifang=\"http://zifang.com/wf/bpmn/ext\" "
            + "             targetNamespace=\"http://zifang.com/wf\">"
            + "  <process id=\"leaveProcess\" name=\"请假流程\" zifang:category=\"审批\">"
            + "    <startEvent id=\"start1\" name=\"提交申请\"/>"
            + "    <userTask id=\"task1\" name=\"经理审批\" "
            + "              zifang:assignee=\"manager\" zifang:formKey=\"leaveForm\" "
            + "              zifang:candidateGroups=\"dept-managers\" zifang:dueDate=\"PT24H\"/>"
            + "    <exclusiveGateway id=\"gw1\" name=\"天数判断\"/>"
            + "    <userTask id=\"task2\" name=\"HR 备案\" zifang:assignee=\"hr\"/>"
            + "    <userTask id=\"task3\" name=\"总经理审批\" zifang:assignee=\"ceo\"/>"
            + "    <endEvent id=\"end1\" name=\"结束\"/>"
            + "    <sequenceFlow id=\"f0\" sourceRef=\"start1\" targetRef=\"task1\"/>"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"task1\" targetRef=\"gw1\"/>"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"gw1\" targetRef=\"task2\">"
            + "      <conditionExpression>days &lt;= 3</conditionExpression></sequenceFlow>"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"gw1\" targetRef=\"task3\">"
            + "      <conditionExpression>days &gt; 3</conditionExpression></sequenceFlow>"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"gw1\" targetRef=\"task2\" "
            + "                 zifang:defaultFlow=\"true\"/>"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"task2\" targetRef=\"end1\"/>"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"task3\" targetRef=\"end1\"/>"
            + "  </process>"
            + "</definitions>";

    /**
     * 并行会签 BPMN：一个分叉 5 条线、一个汇合 5 条入线。
     */
    static final String BPMN_PARALLEL =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "             xmlns:zifang=\"http://zifang.com/wf/bpmn/ext\" "
            + "             targetNamespace=\"http://zifang.com/wf\">"
            + "  <process id=\"parallelProcess\" name=\"并行会签\">"
            + "    <startEvent id=\"s\"/>"
            + "    <parallelGateway id=\"fork\"/>"
            + "    <userTask id=\"t1\"/><userTask id=\"t2\"/><userTask id=\"t3\"/>"
            + "    <userTask id=\"t4\"/><userTask id=\"t5\"/>"
            + "    <parallelGateway id=\"join\"/>"
            + "    <endEvent id=\"e\"/>"
            + "    <sequenceFlow id=\"a\" sourceRef=\"s\" targetRef=\"fork\"/>"
            + "    <sequenceFlow id=\"b\" sourceRef=\"fork\" targetRef=\"t1\"/>"
            + "    <sequenceFlow id=\"c\" sourceRef=\"fork\" targetRef=\"t2\"/>"
            + "    <sequenceFlow id=\"d\" sourceRef=\"fork\" targetRef=\"t3\"/>"
            + "    <sequenceFlow id=\"e1\" sourceRef=\"fork\" targetRef=\"t4\"/>"
            + "    <sequenceFlow id=\"f\" sourceRef=\"fork\" targetRef=\"t5\"/>"
            + "    <sequenceFlow id=\"g\" sourceRef=\"t1\" targetRef=\"join\"/>"
            + "    <sequenceFlow id=\"h\" sourceRef=\"t2\" targetRef=\"join\"/>"
            + "    <sequenceFlow id=\"i\" sourceRef=\"t3\" targetRef=\"join\"/>"
            + "    <sequenceFlow id=\"j\" sourceRef=\"t4\" targetRef=\"join\"/>"
            + "    <sequenceFlow id=\"k\" sourceRef=\"t5\" targetRef=\"join\"/>"
            + "    <sequenceFlow id=\"l\" sourceRef=\"join\" targetRef=\"e\"/>"
            + "  </process>"
            + "</definitions>";
}
