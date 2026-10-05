package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.view.WfExternalTaskView;

/**
 * 外部任务 —— 流程把一步交给外部系统做。
 *
 * <p>本类盯七件最容易做错、且错了都不报错的事：
 * <ol>
 *   <li><b>第一次进入要停下挂 job，外部完成后重入要继续往下走。</b>
 *       不区分这两者的话，每交差一次就又挂一个新 job，流程原地打转 ——
 *       现象是"做完一件又冒出一件，永远做不完"。</li>
 *   <li><b>领活必须是"选出 + 上锁"一次完成。</b>
 *       分成两步的话两个 worker 领到同一件活，而外部动作通常不可重入。</li>
 *   <li><b>锁是租约不是永久锁。</b>
 *       worker 崩了活要能被重新领走，否则整条流程永远卡死，
 *       而"卡住"从外面看和"引擎坏了"没有任何区别。</li>
 *   <li><b>交差要校验锁归属。</b>
 *       慢 worker 的租约过期后活被抢走，它交差时若不被挡住，两个 worker 的结果互相覆盖。</li>
 *   <li><b>失败必须解锁。</b>
 *       外部失败多是瞬时的（下游重启、限流），不解锁则一次失败把活永久锁死，
 *       重试次数一次都用不上。</li>
 *   <li><b>job 完成后要删。</b>
 *       留着就成了"永远领不走、永远显示未完成"的哑表。</li>
 *   <li><b>扫描器不许碰它。</b>
 *       被当成到期定时器消费掉的话，流程会在没人交差时自己往前跑。</li>
 * </ol>
 */
class WfExternalTaskTest {

    /** start → 外部任务(order.create) → 人工审批 → end。 */
    private static final String BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"orderProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <serviceTask id=\"notify\" name=\"通知下游\" zifang:topic=\"order.create\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"notify\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"notify\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 只有一个外部步骤，流程从 start 直达 end —— 用来断言"没有用户任务但流程没结束"。 */
    private static final String ONLY_EXTERNAL_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"notifyOnly\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <serviceTask id=\"notify\" name=\"通知下游\" zifang:topic=\"order.create\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"notify\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"notify\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 主题配在 gateway 上：引擎会在进入网关时挂起，而网关没有"等外部交差"的语义。 */
    private static final String TOPIC_ON_GATEWAY_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"badProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <exclusiveGateway id=\"gw\" zifang:topic=\"order.create\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"gw\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"gw\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** topic 与 delegate 同时配：两者互斥，引擎只会用 topic。 */
    private static final String TOPIC_AND_DELEGATE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"bothProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <serviceTask id=\"notify\" name=\"通知\" zifang:topic=\"order.create\""
            + " zifang:delegateClass=\"com.example.Notifier\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"notify\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"notify\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 两个主题，用来验证按主题隔离。 */
    private static final String TWO_TOPICS_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"twoTopic\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <serviceTask id=\"n1\" name=\"通知\" zifang:topic=\"order.create\"/>\n"
            + "    <serviceTask id=\"n2\" name=\"对账\" zifang:topic=\"order.recon\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"n1\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"n1\" targetRef=\"n2\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"n2\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfTaskService taskService;
    private WfExternalTaskService external;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        WfHookDispatcher hooks = new WfHookDispatcher();
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), hooks);
        taskService = new WfTaskService(repository, repo, runtime, hooks);
        external = new WfExternalTaskService(repo, runtime);
    }

    private String startOrder(Map<String, Object> variables) {
        WfDefinition definition = repository.deployXml(BPMN, "orderProcess");
        return runtime.startProcessInstance(definition, "ORD-1", "alice", null,
                variables == null ? new HashMap<String, Object>() : variables);
    }

    private List<WfJob> jobsOf(String pid) {
        return repo.queryJobs(new WfJobQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(50));
    }

    private List<WfTask> openTasks(String pid) {
        return repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(50));
    }

    // ==================== 挂起 ====================

    @Test
    @DisplayName("进入外部步骤：挂 EXTERNAL job、token 转 WAITING、流程不往下走")
    void enteringExternalStepSuspendsAndCreatesJob() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("orderNo", "A-1001");
        String pid = startOrder(vars);

        List<WfJob> jobs = jobsOf(pid);
        assertEquals(1, jobs.size(), "进入外部步骤就该挂一个 job");
        WfJob job = jobs.get(0);
        assertEquals(WfJobType.EXTERNAL, job.getType());
        assertEquals("order.create", job.getTopic());
        assertEquals("notify", job.getElementId());
        assertNull(job.getDuedate(), "外部任务不由时间触发");
        assertNull(job.getLockedBy(), "刚挂上时没人领");
        assertEquals(WfJob.DEFAULT_RETRIES, job.getRetries());

        assertTrue(openTasks(pid).isEmpty(),
                "外部步骤不建待办 —— 它等的是 worker，不是人");
        List<WfExecution> tokens = repo.findExecutionsByProcessInstance(pid);
        boolean waiting = false;
        for (WfExecution token : tokens) {
            if (WfExecution.State.WAITING == token.getState()) {
                waiting = true;
            }
        }
        assertTrue(waiting, "token 应当停在外部步骤上等待");
    }

    @Test
    @DisplayName("扫描器不消费外部任务：否则没人交差流程就自己往前跑了")
    void scannerNeverConsumesExternalJobs() {
        String pid = startOrder(null);
        // 人为填 duedate：外部任务的 duedate 本来是 null，SQL 里 NULL 比较恒不成立，
        // 断言会"因为别的原因"通过而没有区分力。填上之后这条断言才真的在测扫描器的闸门。
        for (WfJob job : jobsOf(pid)) {
            job.setDuedate(new Date());
            // nextRevision 不能省：saveJob 的乐观锁契约要求传入对象的 revision
            // 恰好比存储里的大 1，省掉就是拿一个"版本没变过"的对象去覆盖
            job.nextRevision();
            repo.saveJob(job);
        }

        WfJobService jobs = new WfJobService(repo, runtime);
        int executed = jobs.executeDueJobs(new Date(System.currentTimeMillis() + 60000));

        assertEquals(0, executed, "扫描器只该处理 TIMER，不该动外部任务");
        assertEquals(1, jobsOf(pid).size(), "外部任务 job 必须还在");
        assertTrue(openTasks(pid).isEmpty(), "流程不该自己往前走 —— 还没人交差");
    }

    // ==================== 领活 ====================

    @Test
    @DisplayName("领活带出变量、租约与锁归属")
    void fetchAndLockCarriesVariablesAndLease() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("orderNo", "A-1001");
        vars.put("amount", 500);
        String pid = startOrder(vars);

        List<WfExternalTaskView> got = external.fetchAndLock("order.create", "w1", 10, 60000);

        assertEquals(1, got.size());
        WfExternalTaskView view = got.get(0);
        assertEquals("order.create", view.getTopic());
        assertEquals("notify", view.getActivityId());
        assertEquals(pid, view.getProcessInstanceId());
        assertEquals("w1", view.getLockedBy());
        assertEquals("A-1001", view.getVariables().get("orderNo"));
        assertEquals(500, view.getVariables().get("amount"));
        assertTrue(view.getLockExpiresAt() > System.currentTimeMillis(),
                "租约到期时刻应当是将来的某一刻");
    }

    @Test
    @DisplayName("锁未过期时别人领不到 —— 否则同一件活被执行两遍")
    void lockedTaskIsNotHandedToSecondWorker() {
        String pid = startOrder(null);

        assertEquals(1, external.fetchAndLock("order.create", "w1", 10, 600000).size());
        List<WfExternalTaskView> second = external.fetchAndLock("order.create", "w2", 10, 600000);

        assertTrue(second.isEmpty(), "锁还在 w1 手里，w2 不该领到");
        assertEquals("w1", jobsOf(pid).get(0).getLockedBy(), "锁归属不能被覆盖");
    }

    @Test
    @DisplayName("租约过期后活能被重新领走 —— worker 崩了不该让流程永远卡住")
    void staleLockIsReclaimedByAnotherWorker() {
        String pid = startOrder(null);
        external.fetchAndLock("order.create", "w1", 10, 600000);

        // 租约 1ms，等它自然过期
        sleep(5);
        List<WfExternalTaskView> reclaimed = external.fetchAndLock("order.create", "w2", 10, 1);

        assertEquals(1, reclaimed.size(), "租约已过期的活应当可以被重新领走");
        assertEquals("w2", reclaimed.get(0).getLockedBy());
        assertEquals("w2", jobsOf(pid).get(0).getLockedBy());
    }

    @Test
    @DisplayName("按主题隔离：领 order.create 不会碰到 order.recon")
    void topicsAreIsolated() {
        WfDefinition definition = repository.deployXml(TWO_TOPICS_BPMN, "twoTopic");
        runtime.startProcessInstance(definition, "T-1", "alice", null,
                new HashMap<String, Object>());

        List<WfExternalTaskView> got = external.fetchAndLock("order.create", "w1", 10);

        assertEquals(1, got.size());
        assertEquals("order.create", got.get(0).getTopic());
        // 第二步还没到（token 停在第一步），但过滤条件必须是对的
        assertEquals(1, external.listTasks("order.create", null, null).size());
        assertEquals(0, external.listTasks("order.recon", null, null).size(),
                "另一个主题下不该有活 —— 混在一起会让 worker 领到不属于自己的活");
    }

    @Test
    @DisplayName("参数缺失要报错而不是猜：topic/workerId/maxTasks 都不能为空")
    void missingArgumentsAreRejected() {
        assertThrows(WfEngineException.class,
                () -> external.fetchAndLock(null, "w1", 10));
        assertThrows(WfEngineException.class,
                () -> external.fetchAndLock("  ", "w1", 10));
        assertThrows(WfEngineException.class,
                () -> external.fetchAndLock("order.create", null, 10));
        assertThrows(WfEngineException.class,
                () -> external.fetchAndLock("order.create", "w1", 0));
    }

    // ==================== 交差 ====================

    @Test
    @DisplayName("交差后 token 继续往下走，并删掉 job")
    void completeAdvancesTokenAndRemovesJob() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("orderNo", "A-1001");
        String pid = startOrder(vars);

        List<WfExternalTaskView> got = external.fetchAndLock("order.create", "w1", 10);
        assertEquals(1, got.size());
        assertTrue(openTasks(pid).isEmpty(), "交差前不该有待办");

        Map<String, Object> result = new HashMap<>();
        result.put("notifyResult", "sent");
        WfProcessInstance instance = external.complete(got.get(0).getId(), "w1", result);

        assertEquals(pid, instance.getId());
        assertEquals("sent", instance.getVariables().get("notifyResult"),
                "外部返回的变量要并进流程变量");
        assertTrue(jobsOf(pid).isEmpty(),
                "job 必须删掉 —— 留着就是永远领不走、永远显示未完成的哑表");

        List<WfTask> tasks = openTasks(pid);
        assertEquals(1, tasks.size(), "外部步骤过了就该轮到处在办的用户任务");
        assertEquals("approve", tasks.get(0).getDefinitionId());
    }

    @Test
    @DisplayName("交差后不会再挂新 job —— 不区分首次进入与重入就会原地打转")
    void completeDoesNotCreateAnotherJob() {
        String pid = startOrder(null);
        List<WfExternalTaskView> got = external.fetchAndLock("order.create", "w1", 10);

        external.complete(got.get(0).getId(), "w1", new HashMap<String, Object>());

        assertTrue(jobsOf(pid).isEmpty(),
                "如果重入时又挂了一个 job，这里会是 1 —— 表现为做完一件又冒出一件");
    }

    @Test
    @DisplayName("只有一个外部步骤的流程：交差后流程才结束")
    void processCompletesOnlyAfterExternalStepDone() {
        WfDefinition definition = repository.deployXml(ONLY_EXTERNAL_BPMN, "notifyOnly");
        String pid = runtime.startProcessInstance(definition, "E-1", "alice", null,
                new HashMap<String, Object>());

        WfProcessInstance before = repo.findProcessInstance(pid);
        assertFalse(before.getStatus().isTerminal(),
                "还没交差就结束的话，这一步等于没做");

        List<WfExternalTaskView> got = external.fetchAndLock("order.create", "w1", 10);
        external.complete(got.get(0).getId(), "w1", null);

        assertTrue(repo.findProcessInstance(pid).getStatus().isTerminal(),
                "交差后流程应当走到 endEvent 结束");
    }

    @Test
    @DisplayName("token 已被推进到别处时，交差不生效 —— 推进等于把流程从别处拽回来")
    void completeOnMovedTokenIsIgnored() {
        String pid = startOrder(null);
        List<WfExternalTaskView> got = external.fetchAndLock("order.create", "w1", 10);
        String taskId = got.get(0).getId();

        // 模拟"撤表慢了一步"：token 已经被推到下一节点，job 还挂着。
        // 与 WfTimerBoundaryTest#staleJobOnMovedTokenIsIgnored 同一种构造。
        WfExecution moved = null;
        for (WfExecution execution : repo.findExecutionsByProcessInstance(pid)) {
            if ("notify".equals(execution.getActivityId())) {
                execution.setActivityId("approve");
                repo.saveExecution(execution);
                moved = execution;
            }
        }
        assertNotNull(moved, "token 应当在 notify 上");

        WfProcessInstance instance = external.complete(taskId, "w1",
                new HashMap<String, Object>());

        assertEquals(pid, instance.getId(), "应当返回当前实例而不是抛异常");
        assertEquals("approve", moved.getActivityId(),
                "token 不在这一步上了，不能再被推进一次");
        assertEquals(1, jobsOf(pid).size(),
                "被忽略的交差不能把 job 删掉 —— 交差没生效，删了等于吞掉一件还没办的事");
        // 流程没有多走一格：notify 之后是 approve，approve 之后才是 end
        assertEquals(WfProcessStatus.ACTIVE, repo.findProcessInstance(pid).getStatus(),
                "被忽略的交差不该把流程推进到结束");
    }

    @Test
    @DisplayName("重复交差报「外部任务不存在」而不是当成成功")
    void duplicateCompleteOnDeletedJobIsRejected() {
        String pid = startOrder(null);
        List<WfExternalTaskView> got = external.fetchAndLock("order.create", "w1", 10);
        String taskId = got.get(0).getId();
        external.complete(taskId, "w1", new HashMap<String, Object>());

        // 第二次：job 已被删，这里走的是「不存在」而不是 token 位置校验 ——
        // 两者是不同的闸门，前者测「活已经不在了」，那个由 duplicateCompleteIsTolerated 覆盖
        WfEngineException e = assertThrows(WfEngineException.class,
                () -> external.complete(taskId, "w1", new HashMap<String, Object>()));
        assertTrue(e.getMessage().contains("不存在"), "应当报 job 不存在：" + e.getMessage());

        assertEquals(1, openTasks(pid).size(), "重复交差不该把流程多推一格");
    }

    @Test
    @DisplayName("非锁持有者交差被拒 —— 否则两个 worker 的结果互相覆盖")
    void completeByNonHolderIsRejected() {
        String pid = startOrder(null);
        List<WfExternalTaskView> got = external.fetchAndLock("order.create", "w1", 10);
        String taskId = got.get(0).getId();

        WfEngineException e = assertThrows(WfEngineException.class,
                () -> external.complete(taskId, "w2", new HashMap<String, Object>()));
        assertTrue(e.getMessage().contains("w1"), "报错要点明锁在谁手里：" + e.getMessage());

        assertEquals(1, jobsOf(pid).size(), "被拒的交差不该动 job");
        assertTrue(openTasks(pid).isEmpty(), "被拒的交差不该推进流程");
    }

    @Test
    @DisplayName("交差要留痕：写一条 external 类型评论")
    void completeRecordsComment() {
        String pid = startOrder(null);
        List<WfExternalTaskView> got = external.fetchAndLock("order.create", "w1", 10);
        external.complete(got.get(0).getId(), "w1", new HashMap<String, Object>());

        List<WfComment> comments = repo.findComments(pid);
        boolean found = false;
        for (WfComment c : comments) {
            if ("external".equals(c.getType())) {
                found = true;
            }
        }
        assertTrue(found, "外部任务交差要在轨迹上留痕，否则排障看不出这一步是谁做的");
    }

    // ==================== 失败与重试 ====================

    @Test
    @DisplayName("失败必须解锁 —— 否则一次瞬时失败就把活永久锁死，重试次数一次都用不上")
    void failUnlocksSoRetryIsPossible() {
        String pid = startOrder(null);
        List<WfExternalTaskView> got = external.fetchAndLock("order.create", "w1", 10);
        String taskId = got.get(0).getId();

        external.fail(taskId, "w1", "下游 503");

        WfJob job = jobsOf(pid).get(0);
        assertNull(job.getLockedBy(), "失败后必须解锁");
        assertEquals(WfJob.DEFAULT_RETRIES - 1, job.getRetries(), "失败要扣重试次数");
        assertTrue(job.getExceptionMessage().contains("下游 503"), "失败原因要留下来");

        List<WfExternalTaskView> retry = external.fetchAndLock("order.create", "w2", 10);
        assertEquals(1, retry.size(), "解锁后应当能被重新领走");
    }

    @Test
    @DisplayName("重试耗尽后 job 留在库（retries<=0）不删 —— 排障要查得到漏过什么")
    void exhaustedJobStaysForTroubleshooting() {
        String pid = startOrder(null);

        // 同一个 worker 来回领：fail 会解锁，所以下一次一定领得到。
        // 不能预先领一次再用不同 worker 领 —— 那样锁还挂在第一个 worker 身上
        // （租约 5 分钟），后面每轮都领不到，fail 一次都不会发生。
        // 这条断言的价值恰恰在于它必须是"领 → fail → 再领"的闭环。
        // 循环 DEFAULT_RETRIES 次而不是 +1 次：第 3 次 fail 时 retries 归零，
        // 第 4 次就已经领不到了（那正是本用例最后要断言的行为，不该在循环里）
        for (int i = 0; i < WfJob.DEFAULT_RETRIES; i++) {
            List<WfExternalTaskView> got = external.fetchAndLock("order.create", "w1", 10);
            assertFalse(got.isEmpty(), "第 " + i + " 次应当能领到（上一轮 fail 已解锁）");
            external.fail(got.get(0).getId(), "w1", "第 " + i + " 次失败");
        }

        assertEquals(1, jobsOf(pid).size(), "耗尽的 job 不该被自动删掉");
        WfJob job = jobsOf(pid).get(0);
        assertTrue(job.isRetriesExhausted());
        assertEquals(1, new WfJobService(repo, runtime).findExhaustedJobs(1, 10).size(),
                "排障入口要能查到它");
        assertTrue(external.fetchAndLock("order.create", "w9", 10).isEmpty(),
                "耗尽的活不该再被领走");
    }

    @Test
    @DisplayName("带退避的失败：退避窗口内领不到，到点后可领")
    void failWithBackoffHonoursRetryDelay() {
        String pid = startOrder(null);
        List<WfExternalTaskView> got = external.fetchAndLock("order.create", "w1", 10);

        external.fail(got.get(0).getId(), "w1", "下游在重启", 60_000);

        assertTrue(external.fetchAndLock("order.create", "w2", 10).isEmpty(),
                "退避窗口内不该被领走 —— 不加这个条件重试次数会在几毫秒内烧光");
        assertNotNull(jobsOf(pid).get(0).getDuedate(), "退避窗口应当记在 duedate 上");

        // 把窗口调到过去，验证到点后能领
        WfJob job = jobsOf(pid).get(0);
        job.setDuedate(new Date(System.currentTimeMillis() - 1000));
        job.nextRevision();
        repo.saveJob(job);
        assertEquals(1, external.fetchAndLock("order.create", "w2", 10).size(),
                "退避到点后应当可以被领走");
    }

    @Test
    @DisplayName("主动释放：解锁但不扣重试 —— 一场发布不该烧光整批活的重试次数")
    void releaseUnlocksWithoutConsumingRetries() {
        String pid = startOrder(null);
        List<WfExternalTaskView> got = external.fetchAndLock("order.create", "w1", 10);

        external.release(got.get(0).getId(), "w1");

        WfJob job = jobsOf(pid).get(0);
        assertNull(job.getLockedBy());
        assertEquals(WfJob.DEFAULT_RETRIES, job.getRetries(), "释放不是失败，不该扣重试");
        assertEquals(1, external.fetchAndLock("order.create", "w2", 10).size());
    }

    @Test
    @DisplayName("失败也要校验锁归属 —— 否则能操作别人的活")
    void failByNonHolderIsRejected() {
        String pid = startOrder(null);
        String taskId = external.fetchAndLock("order.create", "w1", 10).get(0).getId();

        assertThrows(WfEngineException.class, () -> external.fail(taskId, "w2", "抢别人的活"));
        assertEquals("w1", jobsOf(pid).get(0).getLockedBy());
        assertEquals(WfJob.DEFAULT_RETRIES, jobsOf(pid).get(0).getRetries(),
                "被拒的失败不该扣掉重试次数");
    }

    // ==================== 部署期校验 ====================

    @Test
    @DisplayName("topic 配在非 serviceTask 上：部署期 ERROR —— 否则流程停在网关上再不动")
    void topicOnNonServiceTaskIsRejectedAtDeployTime() {
        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> repository.deployXml(TOPIC_ON_GATEWAY_BPMN, "badProcess"));
        String all = joinAll(e);
        assertTrue(all.contains("topic"), "报错要点明是 topic 的问题：" + all);
        assertTrue(all.contains("serviceTask"), "报错要说明只能配在 serviceTask 上：" + all);
    }

    @Test
    @DisplayName("topic 与 delegate 同时配：部署期 ERROR —— 引擎只会用 topic，delegate 永不执行")
    void topicAndDelegateConflictIsRejectedAtDeployTime() {
        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> repository.deployXml(TOPIC_AND_DELEGATE_BPMN, "bothProcess"));
        String all = joinAll(e);
        assertTrue(all.contains("topic") && all.contains("delegate"),
                "报错要点明两者冲突：" + all);
    }

    @Test
    @DisplayName("serviceTask 只有 delegate、没有 topic：仍是原来的要求，不受影响")
    void plainServiceTaskStillNeedsDelegate() {
        String plain =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"plain\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                + "    <serviceTask id=\"st\" name=\"服务\"/>\n"
                + "    <endEvent id=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"st\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"st\" targetRef=\"e1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> repository.deployXml(plain, "plain"));
        assertTrue(joinAll(e).contains("delegateClass"), joinAll(e));
    }

    // ==================== 编解码往返 ====================

    @Test
    @DisplayName("topic 经部署回读后仍在 —— 丢了的话第二次部署就变成普通 serviceTask")
    void topicSurvivesDeployRoundTrip() {
        WfDefinition definition = repository.deployXml(BPMN, "orderProcess");
        WfDefinition reloaded = repository.getDefinition("orderProcess", definition.getVersion());

        assertNotNull(reloaded);
        assertEquals("order.create", reloaded.node("notify").getTopic(),
                "模型回读拿不到 topic，第二次部署就会把它当成普通 serviceTask 要 delegate");
    }

    @Test
    @DisplayName("XML 解析器能直接读出 topic")
    void parserReadsTopic() {
        WfDefinition definition = new WfXmlParser().parse(BPMN);
        assertEquals("order.create", definition.node("notify").getTopic());
        assertTrue(definition.node("notify").isExternalStep());
        assertFalse(definition.node("approve").isExternalStep(),
                "没有 topic 的节点不该被当成外部步骤");
    }

    @Test
    @DisplayName("外部任务的变量是快照：实例上后续改动不反映到已领到的活上")
    void variablesAreSnapshotNotReference() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("orderNo", "A-1001");
        String pid = startOrder(vars);

        List<WfExternalTaskView> got = external.fetchAndLock("order.create", "w1", 10);
        WfProcessInstance instance = repo.findProcessInstance(pid);
        instance.getVariables().put("orderNo", "CHANGED");
        instance.nextRevision();
        repo.saveProcessInstance(instance);

        assertEquals("A-1001", got.get(0).getVariables().get("orderNo"),
                "worker 拿到的是领活那一刻的上下文，不能被后续改动改掉");
    }

    @Test
    @DisplayName("listTasks 走 EXTERNAL 类型过滤：定时器与订阅不该混进外部任务列表")
    void listTasksFiltersByExternalType() {
        String pid = startOrder(null);
        // 同一实例上再挂一个定时器 job
        WfJob timer = new WfJob();
        timer.setId("j-timer");
        timer.setProcessInstanceId(pid);
        timer.setElementId("approve");
        timer.setType(WfJobType.TIMER);
        timer.setDuedate(new Date());
        timer.setCreateTime(new Date());
        repo.saveJob(timer);

        List<WfExternalTaskView> views = external.listTasks(null, null, null);
        assertEquals(1, views.size(), "定时器不属于外部任务");
        assertEquals("order.create", views.get(0).getTopic());
        assertEquals(1, external.countTasks(null));
    }

    @Test
    @DisplayName("领活返回空列表而不是 null —— 让 worker 能直接遍历")
    void fetchReturnsEmptyListNotNull() {
        assertNotNull(external.fetchAndLock("no.such.topic", "w1", 10));
        assertTrue(external.fetchAndLock("no.such.topic", "w1", 10).isEmpty());
    }

    @Test
    @DisplayName("maxTasks 限制生效：一次只领 N 件，剩下的留在库里")
    void fetchRespectsMaxTasks() {
        WfDefinition definition = repository.deployXml(TWO_TOPICS_BPMN, "twoTopic");
        // 连启三个实例，各挂一个 order.create
        List<String> pids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            pids.add(runtime.startProcessInstance(definition, "T-" + i, "alice", null,
                    new HashMap<String, Object>()));
        }
        assertEquals(3, external.countTasks("order.create"));

        List<WfExternalTaskView> got = external.fetchAndLock("order.create", "w1", 2);

        assertEquals(2, got.size(), "maxTasks 限制了领活数量");
        assertEquals(1, external.fetchAndLock("order.create", "w2", 2).size(),
                "剩下的仍可被别人领走");
        assertEquals(0, external.fetchAndLock("order.create", "w3", 2).size());
    }

    @Test
    @DisplayName("释放后锁信息回到 job 上，但 listLockedBy 只认当前锁者")
    void listLockedByReflectsCurrentHolder() {
        String pid = startOrder(null);
        external.fetchAndLock("order.create", "w1", 10);

        assertEquals(1, external.listLockedBy(null, "w1").size());
        assertEquals(0, external.listLockedBy(null, "w2").size());

        external.release(jobsOf(pid).get(0).getId(), "w1");
        assertEquals(0, external.listLockedBy(null, "w1").size(),
                "释放后不再算 w1 锁着的");
        assertEquals(1, external.fetchAndLock("order.create", "w2", 10).size());
        assertEquals(1, external.listLockedBy(null, "w2").size());
    }

    // ==================== 工具 ====================

    private String joinAll(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        while (cur != null) {
            sb.append(cur.getMessage()).append(" | ");
            cur = cur.getCause();
        }
        return sb.toString();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
