package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 消息唤醒（receiveTask）与信号广播的端到端行为。
 *
 * <p>这组用例盯的是三个容易做错、且错了都不报错的点：
 * <ol>
 *   <li>消息名存在 category 里，而 category 也是人工任务在用的通用字段 ——
 *       唤醒时若只按 category 匹配，会把一批在等人的普通任务一起带走</li>
 *   <li>点对点消息匹配到多个时，不能"挑一个"执行</li>
 *   <li>一个都没匹配到时不能静默返回空</li>
 * </ol>
 */
class WfMessageTriggerTest {

    /** start -> receiveTask(messageName=orderPaid) -> end */
    private static final String RECEIVE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"receiveProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <receiveTask id=\"wait1\" name=\"等待付款通知\" zifang:messageName=\"orderPaid\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"wait1\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"wait1\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** start -> userTask(category=orderPaid) -> end：category 撞名，但它是人工任务 */
    private static final String MANUAL_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"manualProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"人工审批\""
            + " zifang:category=\"orderPaid\" zifang:assignee=\"biz-1\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo,
                new com.zifang.z.wf.core.engine.WfEngine(),
                new com.zifang.z.wf.core.hook.WfHookDispatcher());
    }

    @Test
    @DisplayName("消息唤醒接收任务，流程跑完")
    void triggerMessageAdvancesReceiveTask() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(RECEIVE_BPMN));
        runtime.startProcessInstance(definition, "biz-1", null, null, new HashMap<>());

        WfTask waiting = repo.queryTasks(new WfTaskQuery().setOpenOnly(true)
                .setPageNum(1).setPageSize(10)).get(0);
        assertEquals(WfNodeType.RECEIVE_TASK.bpmnName(), waiting.getType());
        assertEquals("orderPaid", waiting.getCategory());

        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", 128.5);
        WfProcessInstance done = runtime.triggerMessage(
                "orderPaid", null, "system", vars, "收到付款通知");

        assertNotNull(done);
        assertTrue(done.getStatus().isTerminal(), "消息到达后流程应当结束，实际=" + done.getStatus());
        assertEquals(128.5, done.getVariables().get("amount"), "消息变量应当并入流程变量");
    }

    @Test
    @DisplayName("人工任务 category 撞名时，不能被消息一起唤醒")
    void messageDoesNotSwallowManualTaskWithSameCategory() {
        repository.deploy(new WfXmlParser().parse(MANUAL_BPMN));
        runtime.startProcessInstance("manualProcess", "biz-9", null, new HashMap<>());

        WfTask manual = repo.queryTasks(new WfTaskQuery().setOpenOnly(true)
                .setPageNum(1).setPageSize(10)).get(0);
        assertEquals(WfNodeType.USER_TASK.bpmnName(), manual.getType());
        assertEquals("orderPaid", manual.getCategory(), "前置条件：category 确实撞名");

        // 消息名同名，但这个任务是人工任务 —— 必须报"没匹配到"，而不是把它带走
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.triggerMessage("orderPaid", null, "system",
                        new HashMap<>(), "不该命中"));
        assertTrue(ex.getMessage().contains("没有等待消息"),
                "应报未匹配到，实际: " + ex.getMessage());

        WfTask still = repo.findTask(manual.getId());
        assertTrue(still.isOpen(), "人工任务必须仍然待办，不能被消息唤醒");
    }

    @Test
    @DisplayName("点对点消息匹配到多个时必须报错，不挑一个执行")
    void ambiguousMessageFailsLoudly() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(RECEIVE_BPMN));
        runtime.startProcessInstance(definition, "biz-1", null, null, new HashMap<>());
        runtime.startProcessInstance(definition, "biz-2", null, null, new HashMap<>());

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.triggerMessage("orderPaid", null, "system",
                        new HashMap<>(), "歧义"));
        assertTrue(ex.getMessage().contains("匹配到 2 个"),
                "应报出匹配数量与候选，实际: " + ex.getMessage());

        // 关键：报错之后两条流程都必须还停在等待态，一条都不能被偷偷推进
        List<WfTask> open = repo.queryTasks(new WfTaskQuery().setOpenOnly(true)
                .setPageNum(1).setPageSize(10));
        assertEquals(2, open.size(), "歧义时不得推进任何一条流程");
    }

    @Test
    @DisplayName("限定 processInstanceId 后歧义消失，可正常触发")
    void scopedTriggerDisambiguates() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(RECEIVE_BPMN));
        String first = runtime.startProcessInstance(definition, "biz-1", null, null, new HashMap<>());
        runtime.startProcessInstance(definition, "biz-2", null, null, new HashMap<>());

        WfProcessInstance done = runtime.triggerMessage(
                "orderPaid", first, "system", new HashMap<>(), "只触发这一条");
        assertTrue(done.getStatus().isTerminal());
        assertEquals(1, repo.queryTasks(new WfTaskQuery().setOpenOnly(true)
                .setPageNum(1).setPageSize(10)).size(), "另一条流程应仍在等待");
    }

    @Test
    @DisplayName("信号广播唤醒全部订阅者")
    void signalBroadcastWakesAll() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(RECEIVE_BPMN));
        runtime.startProcessInstance(definition, "biz-1", null, null, new HashMap<>());
        runtime.startProcessInstance(definition, "biz-2", null, null, new HashMap<>());
        runtime.startProcessInstance(definition, "biz-3", null, null, new HashMap<>());

        List<WfProcessInstance> advanced = runtime.broadcastSignal(
                "orderPaid", "system", new HashMap<>(), "批量通知");
        assertEquals(3, advanced.size());
        for (WfProcessInstance instance : advanced) {
            assertTrue(instance.getStatus().isTerminal());
        }
        assertEquals(0, repo.queryTasks(new WfTaskQuery().setOpenOnly(true)
                .setPageNum(1).setPageSize(10)).size(), "广播后不应再有等待中的任务");
    }

    @Test
    @DisplayName("一个都没匹配到时抛错，不静默返回空列表")
    void unmatchedMessageFailsLoudly() {
        assertThrows(WfEngineException.class,
                () -> runtime.triggerMessage("nobodyWaits", null, "system",
                        new HashMap<>(), "空"));
        assertThrows(WfEngineException.class,
                () -> runtime.broadcastSignal("nobodyWaits", "system",
                        new HashMap<>(), "空"));
    }

    @Test
    @DisplayName("空消息名直接拒绝")
    void blankMessageNameRejected() {
        assertThrows(WfEngineException.class,
                () -> runtime.triggerMessage("  ", null, "system", new HashMap<>(), "空名"));
    }

    @Test
    @DisplayName("未部署的定义不许启动——否则会造出永远推不动的死实例")
    void startWithUndeployedDefinitionFailsFast() {
        WfDefinition never = new WfXmlParser().parse(RECEIVE_BPMN);
        com.zifang.z.wf.core.definition.WfDefinitionException ex = assertThrows(
                com.zifang.z.wf.core.definition.WfDefinitionException.class,
                () -> runtime.startProcessInstance(never, "biz-1", null, null, new HashMap<>()),
                "未部署就启动，会留下一个之后每推进一步都报'流程定义不存在'的死实例");
        assertTrue(ex.getMessage().contains("尚未部署"),
                "报错要说清是未部署，并指路 deploy：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("deploy"),
                "报错里应直接给出解决办法：" + ex.getMessage());
    }

    @Test
    @DisplayName("没有 messageName 的 receiveTask 只能被 force-complete 推进")
    void receiveTaskWithoutMessageNameNeedsForceComplete() {
        String xml = RECEIVE_BPMN.replace(" zifang:messageName=\"orderPaid\"", "");
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(xml));
        runtime.startProcessInstance(definition, "biz-1", null, null, new HashMap<>());

        WfTask waiting = repo.queryTasks(new WfTaskQuery().setOpenOnly(true)
                .setPageNum(1).setPageSize(10)).get(0);
        // category 落回 null，消息无从匹配
        assertThrows(WfEngineException.class,
                () -> runtime.triggerMessage("orderPaid", null, "system",
                        new HashMap<>(), "无从匹配"));
        assertTrue(repo.findTask(waiting.getId()).isOpen());
    }
}
