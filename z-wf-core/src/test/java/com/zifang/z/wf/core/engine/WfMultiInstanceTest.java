package com.zifang.z.wf.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfDefinitionValidator;
import com.zifang.z.wf.core.definition.WfValidationIssue;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.service.WfTaskService;

/**
 * 多实例（会签 / 或签 / 计数会签）的行为测试。
 *
 * <p>会签是审批系统的默认需求，而它最容易出的错是<b>"看起来跑通了"</b>：
 * 提前放行时流程确实往下走了，只是早了；该等没等时流程确实停着，只是永远。
 * 两种都不报错。所以每条用例都断言<b>具体的实例数与待办状态</b>，
 * 而不只是"流程结束了"。
 */
class WfMultiInstanceTest {

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfTaskService taskService;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repository = new WfRepositoryService(repo);
        WfEngine engine = new WfEngine();
        WfHookDispatcher hooks = new WfHookDispatcher();
        runtime = new WfRuntimeService(repository, repo, engine, hooks);
        taskService = new WfTaskService(repository, repo, runtime, hooks);
    }

    /** start → 会签 userTask(3) → end */
    private String counterSignBpmn(String completionCondition) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"miProcess\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                + "    <userTask id=\"counterSign\" name=\"三人会签\""
                + " zifang:assignee=\"${loopAssignee}\">\n"
                + "      <multiInstanceLoopCharacteristics>\n"
                + "        <loopCardinality>3</loopCardinality>\n"
                + (completionCondition == null ? ""
                : "        <completionCondition>" + completionCondition
                + "</completionCondition>\n")
                + "      </multiInstanceLoopCharacteristics>\n"
                + "    </userTask>\n"
                + "    <endEvent id=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"counterSign\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"counterSign\" targetRef=\"e1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
    }

    private String start(String xml, Map<String, Object> vars) {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(xml));
        return runtime.startProcessInstance(definition, "mi-" + System.nanoTime(),
                "alice", null, vars);
    }

    private List<WfTask> openAt(String pid, String nodeId) {
        List<WfTask> all = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(50));
        List<WfTask> result = new ArrayList<>();
        for (WfTask t : all) {
            if (t.isOpen() && nodeId.equals(t.getDefinitionId())) {
                result.add(t);
            }
        }
        return result;
    }

    private List<String> assigneesOf(List<WfTask> tasks) {
        List<String> names = new ArrayList<>();
        for (WfTask t : tasks) {
            names.add(t.getAssignee());
        }
        java.util.Collections.sort(names);
        return names;
    }

    private void completeAs(WfTask task, String user) {
        runtime.completeTask(task.getId(), user, user + "同意", new HashMap<>());
    }

    /**
     * 用任务<b>自己的</b>办理人办结。
     *
     * <p>刻意不写成"取列表里第一个人"：列表顺序与 assignee 排序后的顺序不对应，
     * 那样写会拿 alice 去办 carol 的任务，引擎正确地拒绝，报错却指不到真正的原因。
     */
    private void completeOwn(WfTask task) {
        completeAs(task, task.getAssignee());
    }

    // ==================== 会签（全部完成） ====================

    @Test
    @DisplayName("会签：展开 3 个实例，3 条待办派给 3 个人")
    void counterSignCreatesThreeInstances() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("approvers", Arrays.asList("alice", "bob", "carol"));
        // loopAssignees 走 zifang 扩展
        String xml = counterSignBpmn(null).replace(
                "zifang:assignee=\"${loopAssignee}\"",
                "zifang:assignee=\"${loopAssignee}\" zifang:loopAssignees=\"${approvers}\"");

        String pid = start(xml, vars);
        List<WfTask> open = openAt(pid, "counterSign");

        assertEquals(3, open.size(), "会签应展开 3 个实例");
        assertEquals(Arrays.asList("alice", "bob", "carol"), assigneesOf(open),
                "每个实例应当派给各自的办理人");
    }

    @Test
    @DisplayName("会签：只办 2 个时流程不往下走")
    void counterSignWaitsForAll() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("approvers", Arrays.asList("alice", "bob", "carol"));
        String xml = counterSignBpmn(null).replace(
                "zifang:assignee=\"${loopAssignee}\"",
                "zifang:assignee=\"${loopAssignee}\" zifang:loopAssignees=\"${approvers}\"");

        String pid = start(xml, vars);
        List<WfTask> open = openAt(pid, "counterSign");
        completeOwn(open.get(0));
        open = openAt(pid, "counterSign");
        completeOwn(open.get(0));

        assertEquals(WfProcessStatus.ACTIVE, repo.findProcessInstance(pid).getStatus(),
                "还差一个人时流程必须继续等 —— 提前放行就是会签失效");
        assertEquals(1, openAt(pid, "counterSign").size(), "应当还剩 1 条待办");
    }

    @Test
    @DisplayName("会签：全部办完才结束，且只走一条后续路径")
    void counterSignCompletesWhenAllDone() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("approvers", Arrays.asList("alice", "bob", "carol"));
        String xml = counterSignBpmn(null).replace(
                "zifang:assignee=\"${loopAssignee}\"",
                "zifang:assignee=\"${loopAssignee}\" zifang:loopAssignees=\"${approvers}\"");

        String pid = start(xml, vars);
        for (WfTask t : openAt(pid, "counterSign")) {
            completeOwn(t);
        }

        WfProcessInstance done = repo.findProcessInstance(pid);
        assertEquals(WfProcessStatus.COMPLETED, done.getStatus(), "3 人全办完应结束");
        assertEquals(0, openAt(pid, "counterSign").size(), "不应留下待办");
        assertEquals(3, ((Number) done.getVariables()
                .get(WfMultiInstance.NR_OF_COMPLETED_INSTANCES)).intValue(),
                "循环变量应反映最终完成数");
    }

    @Test
    @DisplayName("会签：3 个实例产生 3 个独立 token")
    void counterSignForksIndependentTokens() {
        String pid = start(counterSignBpmn(null), new HashMap<>());
        int parked = 0;
        for (WfExecution token : repo.findExecutionsByProcessInstance(pid)) {
            if ("counterSign".equals(token.getActivityId()) && !token.isEnded()) {
                parked++;
            }
        }
        assertEquals(3, parked, "每个会签实例应有自己的 token，实例之间是并行的");
    }

    // ==================== 或签 / 计数会签 ====================

    @Test
    @DisplayName("或签：完成条件 nrOfCompletedInstances >= 1，第一个人办完就放行")
    void anyOneCompletesIt() {
        String pid = start(counterSignBpmn("${nrOfCompletedInstances &gt;= 1}"),
                new HashMap<>());
        List<WfTask> open = openAt(pid, "counterSign");
        assertEquals(3, open.size());

        completeAs(open.get(0), "whoever");

        assertEquals(WfProcessStatus.COMPLETED, repo.findProcessInstance(pid).getStatus(),
                "或签：第一个人办完就应当收口");
        assertEquals(0, openAt(pid, "counterSign").size(), "其余实例应被作废，不留待办");
    }

    @Test
    @DisplayName("或签收口时，剩余实例的任务被作废而不是删除")
    void orSignCancelsRemainingInstances() {
        String pid = start(counterSignBpmn("${nrOfCompletedInstances &gt;= 1}"),
                new HashMap<>());
        List<WfTask> open = openAt(pid, "counterSign");
        completeAs(open.get(0), "whoever");

        List<WfTask> all = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(50));
        long cancelled = all.stream()
                .filter(t -> t.getStatus() == WfTask.Status.CANCELLED).count();
        long completed = all.stream()
                .filter(t -> t.getStatus() == WfTask.Status.COMPLETED).count();
        assertEquals(2, cancelled, "另 2 个实例应作废；删掉的话审计就答不出'曾经有这个会签实例'");
        assertEquals(1, completed);
    }

    @Test
    @DisplayName("计数会签：2/3 放行，1/3 时继续等")
    void twoOfThreeCompletesIt() {
        String pid = start(counterSignBpmn("${nrOfCompletedInstances &gt;= 2}"),
                new HashMap<>());
        List<WfTask> open = openAt(pid, "counterSign");

        completeAs(open.get(0), "u1");
        assertEquals(WfProcessStatus.ACTIVE, repo.findProcessInstance(pid).getStatus(),
                "1/3 时应当继续等");

        open = openAt(pid, "counterSign");
        completeAs(open.get(0), "u2");
        assertEquals(WfProcessStatus.COMPLETED, repo.findProcessInstance(pid).getStatus(),
                "2/3 即满足条件，应当收口");
        assertEquals(0, openAt(pid, "counterSign").size());
    }

    @Test
    @DisplayName("或签收口后，兄弟 token 被结束，不会多出并行路径")
    void siblingsEndedOnCollapse() {
        String pid = start(counterSignBpmn("${nrOfCompletedInstances &gt;= 1}"),
                new HashMap<>());
        List<WfTask> open = openAt(pid, "counterSign");
        completeAs(open.get(0), "u1");

        int parked = 0;
        for (WfExecution token : repo.findExecutionsByProcessInstance(pid)) {
            if ("counterSign".equals(token.getActivityId()) && !token.isEnded()) {
                parked++;
            }
        }
        assertEquals(0, parked,
                "收口后只应有一条 token 离开该节点；残留会让流程多出并行的后续路径");
    }

    // ==================== 循环变量 ====================

    @Test
    @DisplayName("循环变量可被业务变量一起引用（否则业务变量被当未定义而卡死）")
    void completionConditionCanUseBusinessVariables() {
        // 完成条件同时引用循环变量与业务变量：amount 未定义时必须 fail-closed，
        // 但 amount 有值时应当正常放行 —— 验证 loopVariables 是以流程变量打底再覆盖
        String xml = counterSignBpmn(
                "${nrOfCompletedInstances &gt;= 2 &amp;&amp; amount &gt; 1000}");
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", 5000);
        String pid = start(xml, vars);
        List<WfTask> open = openAt(pid, "counterSign");
        completeAs(open.get(0), "u1");
        assertEquals(WfProcessStatus.ACTIVE, repo.findProcessInstance(pid).getStatus());
        open = openAt(pid, "counterSign");
        completeAs(open.get(0), "u2");
        assertEquals(WfProcessStatus.COMPLETED, repo.findProcessInstance(pid).getStatus(),
                "业务变量 amount 有值时，完成条件应当能同时看到它");
    }

    @Test
    @DisplayName("loopCounter 是 0 起的序号，且逐实例不同")
    void loopCounterIsPerInstance() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("approvers", Arrays.asList("alice", "bob", "carol"));
        String xml = counterSignBpmn(null).replace(
                "zifang:assignee=\"${loopAssignee}\"",
                "zifang:assignee=\"${loopAssignee}\" zifang:loopAssignees=\"${approvers}\"");

        String pid = start(xml, vars);
        List<Integer> counters = new ArrayList<>();
        for (WfExecution token : repo.findExecutionsByProcessInstance(pid)) {
            Object v = token.getVariables().get(WfMultiInstance.LOOP_COUNTER);
            if (v != null) {
                counters.add(((Number) v).intValue());
            }
        }
        java.util.Collections.sort(counters);
        assertEquals(Arrays.asList(0, 1, 2), counters,
                "loopCounter 应当是 0/1/2 的逐实例序号");
    }

    // ==================== 校验：挡住会静默卡死的配置 ====================

    @Test
    @DisplayName("完成条件里变量名写错时必须报 ERROR（否则会签永远卡死且无报错）")
    void typoInCompletionConditionIsRejected() {
        WfDefinition parsed = new WfXmlParser().parse(
                counterSignBpmn("${nrOfCompleted &gt;= 1}"));
        List<WfValidationIssue> issues = new WfDefinitionValidator().validate(parsed);
        assertTrue(WfDefinitionValidator.hasError(issues),
                "变量名拼错 → fail-closed 判 false → 会签永远等不到完成。"
                        + "这是最阴的一种配置错误，必须在部署期挡下");
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(parsed));
        assertTrue(ex.getMessage().contains("标准循环变量"), ex.getMessage());
    }

    @Test
    @DisplayName("collection 与 loopCardinality 同时配必须报 ERROR —— 猜不出来该听谁的")
    void collectionAndCardinalityTogetherAreRejected() {
        String xml = counterSignBpmn(null)
                .replace("<multiInstanceLoopCharacteristics>",
                        "<multiInstanceLoopCharacteristics>")
                .replace("<loopCardinality>3</loopCardinality>",
                        "<loopCardinality>3</loopCardinality>\n"
                                + "        <collection>${approvers}</collection>");
        WfDefinition parsed = new WfXmlParser().parse(xml);
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(parsed));
        assertTrue(ex.getMessage().contains("二选一"), ex.getMessage());
    }

    @Test
    @DisplayName("elementVariable 却没有 collection 必须报 ERROR —— 元素永远绑不上")
    void elementVariableWithoutCollectionIsRejected() {
        String xml = counterSignBpmn(null).replace(
                "<multiInstanceLoopCharacteristics>",
                "<multiInstanceLoopCharacteristics>\n"
                        + "        <elementVariable>approver</elementVariable>");
        WfDefinition parsed = new WfXmlParser().parse(xml);
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(parsed));
        assertTrue(ex.getMessage().contains("elementVariable"), ex.getMessage());
    }

    @Test
    @DisplayName("loopCardinality 与 collection 一个都没配必须报 ERROR")
    void missingCardinalityIsRejected() {
        String xml = counterSignBpmn(null).replace(
                "        <loopCardinality>3</loopCardinality>\n", "");
        WfDefinition parsed = new WfXmlParser().parse(xml);
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(parsed));
        assertTrue(ex.getMessage().contains("loopCardinality"), ex.getMessage());
    }

    @Test
    @DisplayName("loopCardinality 超过上限时拒绝展开，而不是造出上亿个任务")
    void cardinalityOverLimitIsRejected() {
        String xml = counterSignBpmn(null).replace("<loopCardinality>3</loopCardinality>",
                "<loopCardinality>99999999</loopCardinality>");
        WfDefinition parsed = new WfXmlParser().parse(xml);
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(parsed));
        assertTrue(ex.getMessage().contains("超过上限"), ex.getMessage());
    }

    @Test
    @DisplayName("非多实例的 userTask 不受影响")
    void plainUserTaskUnaffected() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"plain\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                + "    <userTask id=\"u1\" zifang:assignee=\"boss\"/>\n"
                + "    <endEvent id=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"u1\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"u1\" targetRef=\"e1\"/>\n"
                + "  </process>\n</definitions>\n"));
        String pid = runtime.startProcessInstance(definition, "b", "alice", null,
                new HashMap<>());
        assertEquals(1, openAt(pid, "u1").size());
        List<WfTask> open = openAt(pid, "u1");
        completeAs(open.get(0), "boss");
        assertEquals(WfProcessStatus.COMPLETED, repo.findProcessInstance(pid).getStatus());
        assertNotNull(repo.findProcessInstance(pid));
    }
}
