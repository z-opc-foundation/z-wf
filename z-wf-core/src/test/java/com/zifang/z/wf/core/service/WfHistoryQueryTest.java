package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfHistoricActivityInstanceQuery;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 历史查询与历史清理。
 *
 * <p><b>每个用例都在内存实现上跑一遍</b>（JDBC 那套由
 * {@code JdbcWorkflowPersistenceTest} 在 H2 上覆盖）。加这条规则的理由：
 * 新增的 SPI 方法在两套实现里各写一遍过滤逻辑，只要有一边漏了一个条件，
 * 就会出现"内存里查得到、数据库里查不到"，而开发期几乎总是用内存实现，
 * 上线才发现。
 */
class WfHistoryQueryTest {

    private static final String BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"histProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <userTask id=\"second\" name=\"复核\" zifang:assignee=\"ceo\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"second\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"second\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 含排他网关的流程，用来验证"网关不进活动历史"。 */
    private static final String BRANCHY_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"branchyProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"bs1\"/>\n"
            + "    <exclusiveGateway id=\"gw\" name=\"金额判断\"/>\n"
            + "    <userTask id=\"cheap\" name=\"小额审批\" zifang:assignee=\"clerk\"/>\n"
            + "    <userTask id=\"expensive\" name=\"大额审批\" zifang:assignee=\"cfo\"/>\n"
            + "    <endEvent id=\"be1\"/>\n"
            + "    <sequenceFlow id=\"bf1\" sourceRef=\"bs1\" targetRef=\"gw\"/>\n"
            + "    <sequenceFlow id=\"bf2\" sourceRef=\"gw\" targetRef=\"cheap\">\n"
            + "      <conditionExpression>amount &lt; 1000</conditionExpression>\n"
            + "    </sequenceFlow>\n"
            + "    <sequenceFlow id=\"bf3\" sourceRef=\"gw\" targetRef=\"expensive\""
            + " zifang:defaultFlow=\"true\"/>\n"
            + "    <sequenceFlow id=\"bf4\" sourceRef=\"cheap\" targetRef=\"be1\"/>\n"
            + "    <sequenceFlow id=\"bf5\" sourceRef=\"expensive\" targetRef=\"be1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private WfHistoryService history;
    private WfRuntimeService runtime;
    private WfTaskService taskService;
    private InMemoryWorkflowPersistence repo;

    private void fresh() {
        repo = new InMemoryWorkflowPersistence();
        WfRepositoryService repository = new WfRepositoryService(repo);
        WfEngine engine = new WfEngine();
        WfHookDispatcher hooks = new WfHookDispatcher();
        runtime = new WfRuntimeService(repository, repo, engine, hooks);
        taskService = new WfTaskService(repository, repo, runtime, hooks);
        history = new WfHistoryService(repo);
    }

    /** 跑完一条流程，留下完整的活动历史。 */
    private String runToCompletion(String businessKey) {
        WfDefinition definition = repository();
        String pid = runtime.startProcessInstance(definition, businessKey, "alice", null,
                new HashMap<String, Object>());
        WfTask first = oneTask(pid, "approve");
        runtime.completeTask(first.getId(), "boss", "同意", new HashMap<>());
        WfTask second = oneTask(pid, "second");
        runtime.completeTask(second.getId(), "ceo", "同意", new HashMap<>());
        return pid;
    }

    private WfDefinition repository() {
        WfDefinition definition = new WfXmlParser().parse(BPMN);
        return new WfRepositoryService(repo).deploy(definition);
    }

    private WfTask oneTask(String pid, String nodeId) {
        for (WfTask t : repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(20))) {
            if (nodeId.equals(t.getDefinitionId())) {
                return t;
            }
        }
        throw new AssertionError("节点 " + nodeId + " 上没有待办");
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("按流程定义查历史活动：跨流程聚合的前提")
    void queryByDefinitionKey() {
        fresh();
        runToCompletion("H-1");
        runToCompletion("H-2");

        List<WfActivityInstance> all = history.queryActivities(
                new WfHistoricActivityInstanceQuery()
                        .setProcessDefinitionKey("histProcess")
                        .setPageNum(1).setPageSize(100));

        assertEquals(8, all.size(),
                "两条流程 × (1 起始 + 2 任务 + 1 结束) = 8 条。"
                        + "一次节点访问只应有一条记录 —— 曾经是每步三条，"
                        + "其中两条的办理人还取错了");
        assertEquals(8, history.countActivities(
                new WfHistoricActivityInstanceQuery()
                        .setProcessDefinitionKey("histProcess")));
    }

    @Test
    @DisplayName("按办理人查历史：'某人办过哪些单'")
    void queryByAssignee() {
        fresh();
        runToCompletion("H-1");

        List<WfActivityInstance> boss = history.queryActivities(
                new WfHistoricActivityInstanceQuery().setAssignee("boss")
                        .setPageNum(1).setPageSize(100));
        assertEquals(1, boss.size());
        assertEquals("approve", boss.get(0).getActivityId());

        assertEquals(0, history.queryActivities(new WfHistoricActivityInstanceQuery()
                .setAssignee("nobody").setPageNum(1).setPageSize(100)).size());
    }

    @Test
    @DisplayName("按活动类型查：只看人工审批步骤")
    void queryByActivityType() {
        fresh();
        runToCompletion("H-1");

        List<WfActivityInstance> userTasks = history.queryActivities(
                new WfHistoricActivityInstanceQuery().setActivityType("userTask")
                        .setPageNum(1).setPageSize(100));
        assertEquals(2, userTasks.size(), "本例只跑了一条流程，流程里有 2 个 userTask 节点");
    }

    @Test
    @DisplayName("按活动 id 查：定位某个具体环节")
    void queryByActivityId() {
        fresh();
        runToCompletion("H-1");

        List<WfActivityInstance> second = history.queryActivities(
                new WfHistoricActivityInstanceQuery().setActivityId("second")
                        .setPageNum(1).setPageSize(100));
        assertEquals(1, second.size());
        assertEquals("ceo", second.get(0).getAssignee());
    }

    @Test
    @DisplayName("多个条件是 AND：定义 + 办理人同时生效")
    void conditionsAreAnded() {
        fresh();
        runToCompletion("H-1");

        assertEquals(1, history.queryActivities(new WfHistoricActivityInstanceQuery()
                .setProcessDefinitionKey("histProcess").setAssignee("ceo")
                .setPageNum(1).setPageSize(100)).size());
        assertEquals(0, history.queryActivities(new WfHistoricActivityInstanceQuery()
                .setProcessDefinitionKey("otherProcess").setAssignee("ceo")
                .setPageNum(1).setPageSize(100)).size(),
                "定义不匹配时不该因为办理人匹配就返回");
    }

    @Test
    @DisplayName("分页生效且 total 与条件一致")
    void paginationIsHonoured() {
        fresh();
        runToCompletion("H-1");
        runToCompletion("H-2");
        runToCompletion("H-3");

        WfHistoricActivityInstanceQuery query = new WfHistoricActivityInstanceQuery()
                .setProcessDefinitionKey("histProcess");
        assertEquals(12, history.countActivities(query), "total 必须是全量条数而非当前页");
        assertEquals(2, history.queryActivities(query.setPageNum(1).setPageSize(2)).size());
        assertEquals(2, history.queryActivities(query.setPageNum(2).setPageSize(2)).size());
        assertEquals(2, history.queryActivities(query.setPageNum(3).setPageSize(2)).size());
        assertEquals(2, history.queryActivities(query.setPageNum(4).setPageSize(2)).size());
        assertEquals(2, history.queryActivities(query.setPageNum(5).setPageSize(2)).size());
        assertEquals(2, history.queryActivities(query.setPageNum(6).setPageSize(2)).size());
        assertEquals(0, history.queryActivities(query.setPageNum(7).setPageSize(2)).size(),
                "越界页必须是空列表而不是回绕到第一页");
    }

    @Test
    @DisplayName("每一步只记一条，且办理人是真正办结的人")
    void oneRecordPerStepWithCorrectAssignee() {
        fresh();
        String pid = runToCompletion("H-1");

        List<WfActivityInstance> trail = history.queryActivities(
                new WfHistoricActivityInstanceQuery().setProcessInstanceId(pid)
                        .setPageNum(1).setPageSize(50));

        Map<String, Integer> perNode = new java.util.HashMap<>();
        for (WfActivityInstance a : trail) {
            Integer n = perNode.get(a.getActivityId());
            perNode.put(a.getActivityId(), n == null ? 1 : n + 1);
        }
        for (Map.Entry<String, Integer> e : perNode.entrySet()) {
            assertEquals(1, e.getValue(),
                    "节点 " + e.getKey() + " 应当只有一条记录，"
                            + "重复记录会让审批轨迹上同一步骤出现多行");
        }

        // 办理人：审批节点记 boss / ceo，绝不能记成发起人 alice
        for (WfActivityInstance a : trail) {
            if ("approve".equals(a.getActivityId())) {
                assertEquals("boss", a.getAssignee(),
                        "审批节点的办理人应当是真正办结的人。"
                                + "曾经 'entered' 那条取的是 authenticatedUserId，"
                                + "启动时它还是发起人 alice");
                assertEquals("同意", a.getOutcome(), "记录里应当带着审批意见");
            }
        }
    }

    @Test
    @DisplayName("默认按开始时间正序（审批轨迹的自然顺序）")
    void defaultOrderIsByStartTime() {
        fresh();
        String pid = runToCompletion("H-1");

        List<WfActivityInstance> ordered = history.queryActivities(
                new WfHistoricActivityInstanceQuery().setProcessInstanceId(pid)
                        .setPageNum(1).setPageSize(50));
        assertTrue(ordered.size() >= 2);
        for (int i = 1; i < ordered.size(); i++) {
            Date prev = ordered.get(i - 1).getStartTime();
            Date curr = ordered.get(i).getStartTime();
            if (prev != null && curr != null) {
                assertTrue(!curr.before(prev),
                        "默认排序应按开始时间正序，第 " + i + " 条倒退了");
            }
        }
    }

    @Test
    @DisplayName("耗时倒序 + 最短耗时过滤：找瓶颈")
    void durationFiltersFindBottleneck() {
        fresh();
        runToCompletion("H-1");
        runToCompletion("H-2");
        runToCompletion("H-3");

        List<WfActivityInstance> slow = history.queryActivities(
                new WfHistoricActivityInstanceQuery()
                        .setMinDurationMillis(0L)
                        .orderByDurationDesc()
                        .setPageNum(1).setPageSize(50));
        for (int i = 1; i < slow.size(); i++) {
            assertTrue(slow.get(i - 1).getDurationMillis() >= slow.get(i).getDurationMillis(),
                    "耗时倒序排错了");
        }
    }

    @Test
    @DisplayName("按流程实例查：与 getTrail 等价但走可组合查询")
    void queryByProcessInstanceMatchesTrail() {
        fresh();
        String pid = runToCompletion("H-1");

        List<WfActivityInstance> byQuery = history.queryActivities(
                new WfHistoricActivityInstanceQuery().setProcessInstanceId(pid)
                        .setPageNum(1).setPageSize(50));
        List<WfActivityInstance> byTrail = history.getProcessOverview(pid) == null
                ? null : repo.findActivityInstances(pid);
        assertEquals(byTrail.size(), byQuery.size(),
                "同一批数据两条入口必须给出一致的条数");
    }

    // ==================== 哪些节点该进历史 ====================

    @Test
    @DisplayName("网关不记进活动历史：轨迹上是审批步骤，不是路由")
    void gatewaysAreNotRecorded() {
        fresh();
        WfDefinition branchy = new WfRepositoryService(repo)
                .deploy(new WfXmlParser().parse(BRANCHY_BPMN));
        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put("amount", 500);
        String pid = runtime.startProcessInstance(branchy, "B-1", "alice", null, vars);
        runtime.completeTask(oneTask(pid, "cheap").getId(), "clerk", "同意", new HashMap<>());

        List<WfActivityInstance> trail = history.queryActivities(
                new WfHistoricActivityInstanceQuery().setProcessInstanceId(pid)
                        .setPageNum(1).setPageSize(50));

        for (WfActivityInstance a : trail) {
            assertTrue(!"gw".equals(a.getActivityId()),
                    "网关是路由而不是活动，出现在审批轨迹上对审批人没有意义。实际记录: " + ids(trail));
        }
        assertEquals(3, trail.size(), "起始 + 小额审批 + 结束；网关不占名额。实际: " + ids(trail));
    }

    @Test
    @DisplayName("结束事件不记办理人：结束不是某一个人的动作")
    void endEventCarriesNoAssignee() {
        fresh();
        String pid = runToCompletion("H-1");

        List<WfActivityInstance> trail = history.queryActivities(
                new WfHistoricActivityInstanceQuery().setProcessInstanceId(pid)
                        .setPageNum(1).setPageSize(50));

        WfActivityInstance end = null;
        for (WfActivityInstance a : trail) {
            if ("endEvent".equals(a.getActivityType())) {
                end = a;
            }
        }
        assertTrue(end != null, "流程跑完应当有一条结束节点记录，实际: " + ids(trail));
        assertNull(end.getAssignee(),
                "结束节点记上最后一个办理人，会让『某人办过哪些单』凭空多出一条结束行");
    }

    private String ids(List<WfActivityInstance> trail) {
        StringBuilder sb = new StringBuilder("[");
        for (WfActivityInstance a : trail) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(a.getActivityId()).append('/').append(a.getActivityType())
                    .append('/').append(a.getAssignee());
        }
        return sb.append(']').toString();
    }

    // ==================== 瓶颈统计 ====================

    @Test
    @DisplayName("各环节平均耗时：能指出瓶颈在哪个节点")
    void averageDurationByActivity() {
        fresh();
        runToCompletion("H-1");
        runToCompletion("H-2");

        Map<String, Long> averages = history.getAverageDurationByActivity("histProcess", 100);
        assertTrue(averages.containsKey("approve") && averages.containsKey("second"),
                "两个任务节点各一组，实际: " + averages);
    }

    // ==================== 历史任务 ====================

    @Test
    @DisplayName("历史任务查询：只有已办结的，与 count 口径一致")
    void completedTasksMatchCount() {
        fresh();
        String pid = runToCompletion("H-1");
        // 再起一条不办结，它有一个未完成任务
        WfDefinition definition = new WfXmlParser().parse(BPMN);
        new WfRepositoryService(repo).deploy(definition);
        String running = runtime.startProcessInstance(definition, "H-2", "alice", null,
                new HashMap<String, Object>());

        List<WfTask> done = history.queryCompletedTasks(
                new WfTaskQuery().setProcessInstanceId(pid).setPageNum(1).setPageSize(50));
        assertEquals(2, done.size(), "一条流程办结后产生两个已办结任务");
        for (WfTask t : done) {
            assertEquals(WfTask.Status.COMPLETED, t.getStatus());
        }
        assertEquals(2, history.countCompletedTasks(
                new WfTaskQuery().setProcessInstanceId(pid)),
                "count 必须与列表同口径");

        assertEquals(0, history.queryCompletedTasks(
                new WfTaskQuery().setProcessInstanceId(running)
                        .setPageNum(1).setPageSize(50)).size(),
                "在途流程的任务还没办结，不该出现在已办列表里");
    }

    @Test
    @DisplayName("按办理人查已办：'我办过哪些'")
    void completedTasksByCompleter() {
        fresh();
        runToCompletion("H-1");
        runToCompletion("H-2");

        assertEquals(2, history.queryCompletedTasks(new WfTaskQuery()
                .setCompleterId("boss").setPageNum(1).setPageSize(50)).size());
        assertEquals(2, history.queryCompletedTasks(new WfTaskQuery()
                .setCompleterId("ceo").setPageNum(1).setPageSize(50)).size());
        assertEquals(0, history.queryCompletedTasks(new WfTaskQuery()
                .setCompleterId("alice").setPageNum(1).setPageSize(50)).size(),
                "alice 只是发起人，没有办结任何步骤");
        assertEquals(2, history.countCompletedTasks(new WfTaskQuery().setCompleterId("boss")));
    }

    @Test
    @DisplayName("历史任务查询拒绝 openOnly：条件打架时抛错，不返回空集")
    void historicTaskQueryRejectsOpenOnly() {
        fresh();
        runToCompletion("H-1");

        WfTaskQuery contradictory = new WfTaskQuery().setOpenOnly(true).setPageNum(1).setPageSize(50);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> history.queryCompletedTasks(contradictory));
        assertTrue(ex.getMessage().contains("openOnly"), ex.getMessage());

        assertThrows(IllegalArgumentException.class, () -> history.countCompletedTasks(contradictory));
    }

    @Test
    @DisplayName("历史任务查询不改调用方的条件对象")
    void historicTaskQueryDoesNotMutateCallerQuery() {
        fresh();
        runToCompletion("H-1");

        WfTaskQuery caller = new WfTaskQuery().setProcessInstanceId(null)
                .setCompleterId("boss").setPageNum(1).setPageSize(50);
        int firstPass = history.queryCompletedTasks(caller).size();
        assertTrue(firstPass > 0, "前置：跑完的那条流程里 boss 确实办结过步骤");

        assertTrue(!caller.isCompletedOnly(),
                "把调用方传进来的 builder 改了条件，会波及它手里还在用的那一份；"
                        + "下一次复用同一个对象查待办就会莫名其妙少一条");
        assertEquals(firstPass, history.queryCompletedTasks(caller).size(),
                "同一个查询对象连用两次，结果必须一致");
    }

    // ==================== 清理 ====================

    @Test
    @DisplayName("清理只删已结束流程，在途流程的历史完好无损")
    void cleanupOnlyTouchesFinishedProcesses() throws Exception {
        fresh();
        String finished = runToCompletion("H-1");
        // 再起一条但不办结
        WfDefinition definition = new WfXmlParser().parse(BPMN);
        new WfRepositoryService(repo).deploy(definition);
        String running = runtime.startProcessInstance(definition, "H-2", "alice", null,
                new HashMap<String, Object>());
        assertEquals(1, oneTask(running, "approve") != null ? 1 : 0);

        // 用一个"将来"的时间点清理 ⇒ 已结束的也不该被删（END_TIME 在将来之前？）
        int removed = history.deleteHistoryBefore(new Date(System.currentTimeMillis() + 60_000L));
        assertEquals(1, removed, "只该删掉已结束的那条");

        assertEquals(0, repo.findActivityInstances(finished).size(),
                "已结束流程的历史应被清掉");
        assertTrue(repo.findActivityInstances(running).size() > 0,
                "在途流程的历史必须保留 —— 删掉之后轨迹会出洞，而单据还在被人办");
    }

    @Test
    @DisplayName("清理时点之前的在途流程同样不能删")
    void cleanupKeepsRunningEvenIfOld() throws Exception {
        fresh();
        WfDefinition definition = new WfXmlParser().parse(BPMN);
        new WfRepositoryService(repo).deploy(definition);
        String running = runtime.startProcessInstance(definition, "H-1", "alice", null,
                new HashMap<>());

        history.deleteHistoryBefore(new Date(System.currentTimeMillis() + 60_000L));
        assertTrue(repo.findActivityInstances(running).size() > 0,
                "在途流程的历史与 END_TIME 无关，永远不参与清理");
    }

    @Test
    @DisplayName("时间点为 null 直接拒绝，不当成'清掉全部'")
    void nullCleanupPointRejected() {
        fresh();
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> history.deleteHistoryBefore(null));
        assertTrue(ex.getMessage().contains("必须给一个时间点"), ex.getMessage());
    }
}
