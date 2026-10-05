package com.zifang.z.wf.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.engine.expression.WfExpressionEvaluator;
import com.zifang.z.wf.core.service.WfDelegateRegistry;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;

/**
 * 14 种节点类型的端到端体检。
 *
 * <p><b>这个类存在的理由。</b> {@code receiveTask} 曾长期"实现完整但完全不生效"：
 * {@code WfReceiveTaskBehavior} 把任务对象建得字段齐全，
 * 而 {@code WfNodeType#createsTask()} 漏了它，引擎把返回的任务原样丢弃、
 * token 继续往下走。当时 98 个测试全绿。
 *
 * <p>结论是：<b>"解析得出来"和"类存在"都不能证明功能可用</b>。
 * 所以这里对每一种节点类型都断言它的<b>实际运行结果</b>——
 * 停不停、任务建不建、分支怎么走、token 落在哪。
 * 任何一种退化都"看起来跑通了"，本类都会红。
 */
class WfNodeTypeCoverageTest {

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfEngine engine;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repository = new WfRepositoryService(repo);
        engine = new WfEngine();
        runtime = new WfRuntimeService(repository, repo, engine, new WfHookDispatcher());
    }

    private WfDefinition deploy(String body) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"p\" isExecutable=\"true\">\n"
                + body
                + "  </process>\n"
                + "</definitions>\n";
        return repository.deploy(new WfXmlParser().parse(xml));
    }

    private String start(WfDefinition definition, Map<String, Object> vars) {
        return runtime.startProcessInstance(
                definition, "biz-" + System.nanoTime(), "u1", null, vars);
    }

    private List<WfTask> openTasks(String pid) {
        return repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(50));
    }

    private List<WfExecution> tokens(String pid) {
        return repo.findExecutionsByProcessInstance(pid);
    }

    // ==================== 1. 开始 / 结束事件 ====================

    @Test
    @DisplayName("startEvent：能启动，token 落到下一个节点")
    void startEventWorks() {
        WfDefinition definition = deploy("<startEvent id=\"s1\"/>\n"
                + "<userTask id=\"u1\" zifang:assignee=\"a\"/>\n<endEvent id=\"e1\"/>\n"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"u1\"/>\n"
                + "<sequenceFlow id=\"f2\" sourceRef=\"u1\" targetRef=\"e1\"/>");
        String pid = start(definition, new HashMap<>());
        assertEquals(1, openTasks(pid).size());
        assertTrue(openTasks(pid).get(0).getDefinitionId().equals("u1"));
    }

    @Test
    @DisplayName("endEvent：流程走到这里就结束，且带 resultExpression 结果")
    void endEventTerminatesWithResult() {
        WfDefinition definition = deploy("<startEvent id=\"s1\"/>\n"
                + "<endEvent id=\"e1\" zifang:resultExpression=\"${amount &gt; 100 ? 'big' : 'small'}\"/>\n"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"e1\"/>");
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", 500);
        String pid = start(definition, vars);
        WfProcessInstance done = repo.findProcessInstance(pid);
        assertEquals(WfProcessStatus.COMPLETED, done.getStatus());
        assertEquals("big", done.getResult(), "endEvent 的 resultExpression 应写入流程结果");
    }

    // ==================== 2. 任务类 ====================

    @Test
    @DisplayName("userTask：建任务、停住、指派给 assignee")
    void userTaskWaits() {
        WfDefinition definition = deploy("<startEvent id=\"s1\"/>\n"
                + "<userTask id=\"u1\" zifang:assignee=\"alice\"/>\n<endEvent id=\"e1\"/>\n"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"u1\"/>\n"
                + "<sequenceFlow id=\"f2\" sourceRef=\"u1\" targetRef=\"e1\"/>");
        String pid = start(definition, new HashMap<>());
        WfTask task = openTasks(pid).get(0);
        assertEquals("alice", task.getAssignee());
        assertTrue(task.isOpen());
        assertEquals(1, liveTokens(pid), "token 应停在 userTask 上等待");
    }

    @Test
    @DisplayName("userTask：${变量} 动态指派能解析")
    void userTaskResolvesAssigneeExpression() {
        WfDefinition definition = deploy("<startEvent id=\"s1\"/>\n"
                + "<userTask id=\"u1\" zifang:assignee=\"${leader}\"/>\n<endEvent id=\"e1\"/>\n"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"u1\"/>\n"
                + "<sequenceFlow id=\"f2\" sourceRef=\"u1\" targetRef=\"e1\"/>");
        Map<String, Object> vars = new HashMap<>();
        vars.put("leader", "boss-from-var");
        String pid = start(definition, vars);
        assertEquals("boss-from-var", openTasks(pid).get(0).getAssignee());
    }

    @Test
    @DisplayName("manualTask：建任务停住，与 userTask 同为等待态")
    void manualTaskWaits() {
        WfDefinition definition = deploy("<startEvent id=\"s1\"/>\n"
                + "<manualTask id=\"m1\" zifang:assignee=\"ops\"/>\n<endEvent id=\"e1\"/>\n"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"m1\"/>\n"
                + "<sequenceFlow id=\"f2\" sourceRef=\"m1\" targetRef=\"e1\"/>");
        String pid = start(definition, new HashMap<>());
        assertEquals(1, openTasks(pid).size(), "manualTask 应当建任务并等待");
        assertEquals("ops", openTasks(pid).get(0).getAssignee());
    }

    @Test
    @DisplayName("task（通用任务）：建任务停住")
    void genericTaskWaits() {
        WfDefinition definition = deploy("<startEvent id=\"s1\"/>\n"
                + "<task id=\"t1\" zifang:assignee=\"ops\"/>\n<endEvent id=\"e1\"/>\n"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"t1\"/>\n"
                + "<sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"e1\"/>");
        String pid = start(definition, new HashMap<>());
        assertEquals(1, openTasks(pid).size());
    }

    @Test
    @DisplayName("receiveTask：建任务并停住，等待消息（本轮修复项）")
    void receiveTaskWaits() {
        WfDefinition definition = deploy("<startEvent id=\"s1\"/>\n"
                + "<receiveTask id=\"r1\" zifang:messageName=\"paid\"/>\n<endEvent id=\"e1\"/>\n"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"r1\"/>\n"
                + "<sequenceFlow id=\"f2\" sourceRef=\"r1\" targetRef=\"e1\"/>");
        String pid = start(definition, new HashMap<>());
        assertEquals(1, openTasks(pid).size(),
                "receiveTask 必须建任务并停住；曾经它建了任务又被引擎丢弃");
        assertEquals("paid", openTasks(pid).get(0).getCategory());
        assertEquals(WfProcessStatus.ACTIVE, repo.findProcessInstance(pid).getStatus(),
                "等待消息期间流程应是 ACTIVE 而不是已完成");
    }

    @Test
    @DisplayName("sendTask：同步跑 delegate 后穿透，不建任务不等待")
    void sendTaskRunsDelegateAndPassesThrough() {
        // 契约澄清：z-wf 的 sendTask 与 serviceTask 共用 WfServiceTaskBehavior，
        // 即"同步执行一段外部逻辑然后往下走"。它不是 BPMN 那种"抛一条消息"，
        // 因为本引擎还没有消息抛出能力。所以 sendTask 必须配 delegate。
        final List<String> sent = new ArrayList<>();
        WfDelegateRegistry delegates = new WfDelegateRegistry();
        delegates.register("notifier", (ctx, ex) -> sent.add("notified"));
        InMemoryWorkflowPersistence localRepo = new InMemoryWorkflowPersistence();
        WfRepositoryService localRepository = new WfRepositoryService(localRepo);
        WfEngine localEngine = new WfEngine(new WfBehaviorRegistry(),
                new WfExpressionEvaluator(), new WfIdGenerator.DefaultWfIdGenerator(), delegates);
        WfRuntimeService localRuntime = new WfRuntimeService(
                localRepository, localRepo, localEngine, new WfHookDispatcher());

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"p\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                + "    <sendTask id=\"s2\" zifang:delegateExpression=\"notifier\"/>\n"
                + "    <endEvent id=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"s2\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"s2\" targetRef=\"e1\"/>\n"
                + "  </process>\n</definitions>\n";
        WfDefinition definition = localRepository.deploy(new WfXmlParser().parse(xml));
        String pid = localRuntime.startProcessInstance(
                definition, "b1", "u1", null, new HashMap<>());

        assertEquals(1, sent.size(), "sendTask 的 delegate 应当被调用");
        assertEquals(0, localRepo.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(pid).setOpenOnly(true)
                .setPageNum(1).setPageSize(10)).size(),
                "sendTask 不建任务、不等待");
        assertEquals(WfProcessStatus.COMPLETED, localRepo.findProcessInstance(pid).getStatus());
    }

    // ==================== 3. 服务 / 脚本 ====================

    @Test
    @DisplayName("serviceTask：执行 delegate 后穿透")
    void serviceTaskRunsDelegate() {
        final List<String> invoked = new ArrayList<>();
        WfDelegateRegistry delegates = new WfDelegateRegistry();
        delegates.register("recorder", (context, execution) -> {
            invoked.add("sv1");
            context.setVariable("touched", true);
        });
        InMemoryWorkflowPersistence localRepo = new InMemoryWorkflowPersistence();
        WfRepositoryService localRepository = new WfRepositoryService(localRepo);
        WfEngine localEngine = new WfEngine(new WfBehaviorRegistry(),
                new WfExpressionEvaluator(), new WfIdGenerator.DefaultWfIdGenerator(), delegates);
        WfRuntimeService localRuntime = new WfRuntimeService(
                localRepository, localRepo, localEngine, new WfHookDispatcher());

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"p\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                + "    <serviceTask id=\"sv1\" zifang:delegateExpression=\"recorder\"/>\n"
                + "    <endEvent id=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"sv1\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"sv1\" targetRef=\"e1\"/>\n"
                + "  </process>\n</definitions>\n";
        WfDefinition definition = localRepository.deploy(new WfXmlParser().parse(xml));
        String pid = localRuntime.startProcessInstance(
                definition, "b1", "u1", null, new HashMap<>());

        assertEquals(1, invoked.size(), "delegate 应当被调用一次");
        assertEquals("sv1", invoked.get(0));
        assertEquals(true, localRepo.findProcessInstance(pid).getVariables().get("touched"),
                "delegate 写入的变量应当落在流程实例上");
        assertEquals(WfProcessStatus.COMPLETED, localRepo.findProcessInstance(pid).getStatus());
    }

    @Test
    @DisplayName("scriptTask：执行脚本后穿透")
    void scriptTaskRuns() {
        // 注意：scriptTask 的 script 是"求值一个 EL 表达式"，不是"执行一段脚本"。
        // 它拿不到 context 引用，因此写不进变量，除非配 resultVariable。
        WfDefinition definition = deploy("<startEvent id=\"s1\"/>\n"
                + "<scriptTask id=\"sc1\" zifang:script=\"${amount * 2}\"/>\n"
                + "<endEvent id=\"e1\"/>\n"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"sc1\"/>\n"
                + "<sequenceFlow id=\"f2\" sourceRef=\"sc1\" targetRef=\"e1\"/>");
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", 21);
        String pid = start(definition, vars);
        WfProcessInstance done = repo.findProcessInstance(pid);
        assertEquals(WfProcessStatus.COMPLETED, done.getStatus(),
                "scriptTask 之后应当直接走到结束；INTERNALLY_TERMINATED 说明脚本求值失败");
        assertEquals(0, openTasks(pid).size(), "scriptTask 不该建任务");
    }

    @Test
    @DisplayName("serviceTask：delegate 解析不到时流程必须失败，不能静默跳过这一步")
    void unresolvableDelegateFailsTheProcess() {
        WfDefinition definition = deploy("<startEvent id=\"s1\"/>\n"
                + "<serviceTask id=\"sv1\" zifang:delegateClass=\"com.acme.NoSuchDelegate\"/>\n"
                + "<userTask id=\"u1\" zifang:assignee=\"boss\"/>\n"
                + "<endEvent id=\"e1\"/>\n"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"sv1\"/>\n"
                + "<sequenceFlow id=\"f2\" sourceRef=\"sv1\" targetRef=\"u1\"/>\n"
                + "<sequenceFlow id=\"f3\" sourceRef=\"u1\" targetRef=\"e1\"/>");
        String pid = start(definition, new HashMap<>());
        WfProcessInstance instance = repo.findProcessInstance(pid);
        assertEquals(WfProcessStatus.INTERNALLY_TERMINATED, instance.getStatus(),
                "delegate 类名写错时流程必须终止。旧实现只 log.error 后放行，"
                        + "于是'通知 HR''写台账'这一步静默跳过而流程照报完成");
        assertEquals(0, openTasks(pid).size(), "失败后不应留下待办，那会让人以为还能继续办");
    }

    // ==================== 4. 网关 ====================

    @Test
    @DisplayName("exclusiveGateway：条件成立才走对应分支")
    void exclusiveGatewayRoutes() {
        WfDefinition definition = deploy("<startEvent id=\"s1\"/>\n"
                + "<exclusiveGateway id=\"g1\" zifang:defaultFlow=\"f3\"/>\n"
                + "<userTask id=\"big\" zifang:assignee=\"ceo\"/>\n"
                + "<userTask id=\"small\" zifang:assignee=\"mgr\"/>\n"
                + "<endEvent id=\"e1\"/>\n"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"g1\"/>\n"
                + "<sequenceFlow id=\"f2\" sourceRef=\"g1\" targetRef=\"big\""
                + " zifang:conditionExpression=\"${amount &gt; 100}\"/>\n"
                + "<sequenceFlow id=\"f3\" sourceRef=\"g1\" targetRef=\"small\"/>");
        Map<String, Object> big = new HashMap<>();
        big.put("amount", 500);
        String pid1 = start(definition, big);
        assertEquals("big", openTasks(pid1).get(0).getDefinitionId());

        Map<String, Object> small = new HashMap<>();
        small.put("amount", 5);
        String pid2 = start(definition, small);
        assertEquals("small", openTasks(pid2).get(0).getDefinitionId(),
                "条件不成立应落到 defaultFlow");
    }

    @Test
    @DisplayName("parallelGateway：fork 出多个 token，汇合点等齐才继续")
    void parallelGatewayForksAndJoins() {
        WfDefinition definition = deploy("<startEvent id=\"s1\"/>\n"
                + "<parallelGateway id=\"split\"/>\n"
                + "<userTask id=\"a\" zifang:assignee=\"a1\"/>\n"
                + "<userTask id=\"b\" zifang:assignee=\"b1\"/>\n"
                + "<parallelGateway id=\"join\"/>\n"
                + "<endEvent id=\"e1\"/>\n"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"split\"/>\n"
                + "<sequenceFlow id=\"f2\" sourceRef=\"split\" targetRef=\"a\"/>\n"
                + "<sequenceFlow id=\"f3\" sourceRef=\"split\" targetRef=\"b\"/>\n"
                + "<sequenceFlow id=\"f4\" sourceRef=\"a\" targetRef=\"join\"/>\n"
                + "<sequenceFlow id=\"f5\" sourceRef=\"b\" targetRef=\"join\"/>\n"
                + "<sequenceFlow id=\"f6\" sourceRef=\"join\" targetRef=\"e1\"/>");
        String pid = start(definition, new HashMap<>());
        assertEquals(2, openTasks(pid).size(), "并行网关应当 fork 出两条待办");
        assertEquals(2, liveTokens(pid), "应当有两个活跃 token");

        WfTask taskA = null;
        WfTask taskB = null;
        for (WfTask task : openTasks(pid)) {
            if ("a".equals(task.getDefinitionId())) {
                taskA = task;
            } else {
                taskB = task;
            }
        }
        runtime.completeTask(taskA.getId(), "a1", "同意", new HashMap<>());
        assertEquals(WfProcessStatus.ACTIVE, repo.findProcessInstance(pid).getStatus(),
                "只完成一条时汇合点不应放行");
        runtime.completeTask(taskB.getId(), "b1", "同意", new HashMap<>());
        assertEquals(WfProcessStatus.COMPLETED, repo.findProcessInstance(pid).getStatus(),
                "两条都完成才应结束；汇合判定若漏算会在这里提前或永不结束");
    }

    @Test
    @DisplayName("inclusiveGateway：命中的分支全走")
    void inclusiveGatewayTakesAllMatching() {
        WfDefinition definition = deploy("<startEvent id=\"s1\"/>\n"
                + "<inclusiveGateway id=\"g1\"/>\n"
                + "<userTask id=\"a\" zifang:assignee=\"a1\"/>\n"
                + "<userTask id=\"b\" zifang:assignee=\"b1\"/>\n"
                + "<endEvent id=\"e1\"/>\n"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"g1\"/>\n"
                + "<sequenceFlow id=\"f2\" sourceRef=\"g1\" targetRef=\"a\""
                + " zifang:conditionExpression=\"${x}\"/>\n"
                + "<sequenceFlow id=\"f3\" sourceRef=\"g1\" targetRef=\"b\""
                + " zifang:conditionExpression=\"${y}\"/>");
        Map<String, Object> vars = new HashMap<>();
        vars.put("x", true);
        vars.put("y", true);
        String pid = start(definition, vars);
        assertEquals(2, openTasks(pid).size(), "两个条件都成立时应当走两条分支");
    }

    // ==================== 5. 子流程 / 调用 ====================

    @Test
    @DisplayName("subProcess：内联内容当前不执行，部署期就报 ERROR 挡住")
    void embeddedSubProcessIsRejectedAtDeployTime() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"p\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                + "    <subProcess id=\"sp1\"><userTask id=\"inner\" zifang:assignee=\"i1\"/>"
                + "<endEvent id=\"ie\"/></subProcess>\n"
                + "    <endEvent id=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"sp1\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"sp1\" targetRef=\"e1\"/>\n"
                + "  </process>\n</definitions>\n";
        WfDefinition parsed = new WfXmlParser().parse(xml);

        // 解析期仍要宽松：先证明内联节点确实被收进了扁平表
        assertNotNull(parsed.node("inner"),
                "内联节点会被收进扁平节点表——这正是危险之处：它在表里，却永远跑不到");
        assertEquals("sp1", parsed.node("inner").nestedIn(),
                "必须记下嵌在谁里面，否则校验器无从发现它不可达");

        // 部署期必须挡住
        com.zifang.z.wf.core.definition.WfDefinitionException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.zifang.z.wf.core.definition.WfDefinitionException.class,
                        () -> repository.deploy(parsed),
                        "嵌入式 subProcess 的内联节点永远不会被执行，"
                                + "放行等于让作者以为'画了子流程它就会跑'");
        assertTrue(ex.getMessage().contains("inner"),
                "报错要点名哪些内联节点跑不到：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("callActivity"),
                "报错应给出可行替代：" + ex.getMessage());
    }

    @Test
    @DisplayName("callActivity：调用另一个已部署流程，结果回填 resultVariable")
    void callActivityInvokesOtherProcess() {
        WfDefinition caller = deploy("<startEvent id=\"s1\"/>\n"
                + "<callActivity id=\"call1\" zifang:calledElementKey=\"childProcess\""
                + " zifang:resultVariable=\"callResult\"/>\n"
                + "<endEvent id=\"e1\"/>\n"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"call1\"/>\n"
                + "<sequenceFlow id=\"f2\" sourceRef=\"call1\" targetRef=\"e1\"/>");
        // 被调流程必须用与主流程不同的 key：deploy() 固定 process id="p"，
        // 同名会让 callActivity 调到自己，无限递归直到 StackOverflow。
        WfDefinition child = new WfXmlParser().parse(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"childProcess\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"cs\"/>\n"
                + "    <endEvent id=\"ce\" zifang:resultExpression=\"'CHILD_OK'\"/>\n"
                + "    <sequenceFlow id=\"cf\" sourceRef=\"cs\" targetRef=\"ce\"/>\n"
                + "  </process>\n</definitions>\n");
        repository.deploy(child);

        String pid = start(caller, new HashMap<>());
        WfProcessInstance done = repo.findProcessInstance(pid);
        assertEquals(WfProcessStatus.COMPLETED, done.getStatus(),
                "被调流程同步执行完，主流程应当结束");
        assertEquals("CHILD_OK", done.getVariables().get("callResult"),
                "被调流程的结果应当经 resultVariable 回填到主流程变量；"
                        + "此前 resultExpression 是死字段，永远拿不到");
        assertNotNull(done.getVariables().get("__wf_sub_instance"),
                "子实例 id 应当可见，否则子流程出问题无从定位");
    }

    /**
     * 尚未结束的 token 数。
     *
     * <p>刻意不用 isActive()：它只认 State.ACTIVE，而停在 userTask 上的 token
     * 是 WAITING —— 正是"流程停住等人工"的证据，用 isActive() 数会永远得 0，
     * 从而把"流程没停住"这个真缺陷伪装成通过。
     */
    private long liveTokens(String pid) {
        long count = 0;
        for (WfExecution token : tokens(pid)) {
            if (!token.isEnded()) {
                count++;
            }
        }
        return count;
    }
}
