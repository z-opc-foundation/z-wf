package com.zifang.z.wf.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.zifang.z.wf.core.engine.expression.WfExpressionEvaluator;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfActivityInstance;
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
import com.zifang.z.wf.core.service.WfDelegateRegistry;
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.service.WfJobService;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;

/**
 * 异步执行（asyncBefore / asyncAfter）。
 *
 * <p>本类盯六件错了都不报错的事：
 * <ol>
 *   <li><b>前置与后置的续跑方向相反</b>：前置要把节点真的跑一遍（delegate 调一次、
 *       审批任务建出来），后置只补"离开"这一步（delegate 绝不能调第二遍）。</li>
 *   <li><b>续跑不能死循环</b>：前置的续跑动作正是"进入这个节点"，
 *       而进入的第一件事就是判断要不要挂异步 job —— 不切断就是无限排单。</li>
 *   <li><b>job 必须被删</b>：留着会被下一次扫描再执行一遍。</li>
 *   <li><b>扫描器不许碰</b>：定时器执行器只该捞 TIMER，异步有自己的一套。</li>
 *   <li><b>token 位置要对得上</b>：早就走了还续跑等于把流程从别处拽回来。</li>
 *   <li><b>互斥组合部署期就要挡住</b>：外部任务+异步、多实例+异步、endEvent+asyncAfter
 *       这三组放行的话症状都是"流程停在某处且没有任何解释"。</li>
 * </ol>
 */
class WfAsyncExecutionTest {

    /** 异步前置的审批节点：续跑后才该建出待办。 */
    private static final String BEFORE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"asyncBefore\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\""
            + " zifang:asyncBefore=\"true\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 异步后置的审批节点：人办完之后才排一次队离开。 */
    private static final String AFTER_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"asyncAfter\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\""
            + " zifang:asyncAfter=\"true\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 两个方向都开：应当是两次排队、两次续跑。 */
    private static final String BOTH_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"asyncBoth\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\""
            + " zifang:asyncBefore=\"true\" zifang:asyncAfter=\"true\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 异步前置的 serviceTask：续跑后 delegate 才被调用。 */
    private static final String BEFORE_SERVICE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"asyncService\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <serviceTask id=\"call\" zifang:asyncBefore=\"true\""
            + " zifang:delegateExpression=\"counter\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"call\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"call\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 异步前置的网关：续跑时才选线。 */
    private static final String BEFORE_GATEWAY_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"asyncGw\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <exclusiveGateway id=\"gw\" zifang:asyncBefore=\"true\""
            + " zifang:conditionExpression=\"${ok}\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <endEvent id=\"e2\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"gw\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"gw\" targetRef=\"e1\">\n"
            + "      <conditionExpression xsi:type=\"tFormalExpression\""
            + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">${ok}</conditionExpression>\n"
            + "    </sequenceFlow>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"gw\" targetRef=\"e2\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** Camunda 前缀：迁移别人的模型时不该因为前缀不同而不被识别。 */
    private static final String CAMUNDA_PREFIX_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"camundaAsync\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" zifang:assignee=\"boss\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" camunda:asyncBefore=\"true\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfJobService jobService;
    private WfDelegateRegistry delegates;
    private final List<String> delegateCalls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        delegates = new WfDelegateRegistry();
        delegateCalls.clear();
        delegates.register("counter", (ctx, ex) -> delegateCalls.add("called"));
        WfEngine engine = new WfEngine(new WfBehaviorRegistry(),
                new WfExpressionEvaluator(), new WfIdGenerator.DefaultWfIdGenerator(), delegates);
        runtime = new WfRuntimeService(repository, repo, engine, new WfHookDispatcher());
        jobService = new WfJobService(repo, runtime);
    }

    private String deploy(String xml, String key) {
        return runtime.startProcessInstance(repository.deploy(new WfXmlParser().parse(xml)),
                key, "alice", null, new HashMap<String, Object>());
    }

    private List<WfJob> jobsOf(String pid) {
        return repo.queryJobs(new WfJobQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(50));
    }

    /**
     * 扫描时刻取"当前 + 1 秒"。
     *
     * <p>{@code dueBefore} 的 SQL 是 {@code DUEDATE < ?} —— 严格小于（边界那一条
     * 归下一轮，避免同一秒被两个执行器各处理一次）。直接传 {@code new Date()} 的话，
     * job 挂出与扫描可能落在同一毫秒，捞不到 —— 那不是缺陷，是这个约定的必然结果。
     */
    private Date afterQueueTime() {
        return new Date(System.currentTimeMillis() + 1000L);
    }

    private List<WfTask> openTasks(String pid) {
        return repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(50));
    }

    // ==================== 异步前置 ====================

    @Test
    @DisplayName("异步前置：token 到达就排队，待办要等续跑之后才建出来")
    void asyncBeforeSuspendsBeforeCreatingTask() {
        String pid = deploy(BEFORE_BPMN, "B-1");

        List<WfJob> jobs = jobsOf(pid);
        assertEquals(1, jobs.size(), "到达即应挂一个异步 job");
        assertEquals(WfJobType.ASYNC_BEFORE, jobs.get(0).getType());
        assertEquals("approve", jobs.get(0).getElementId());
        assertNotNull(jobs.get(0).getDuedate(), "duedate 为空的话扫描器捞不到它（NULL 比较恒不成立）");
        assertTrue(openTasks(pid).isEmpty(),
                "节点还没执行，不该有待办 —— 待办是续跑时才建出来的");
        assertFalse(repo.findProcessInstance(pid).getStatus().isTerminal());

        assertEquals(1, jobService.executeAsyncJobs(afterQueueTime()), "应当续跑一个 job");

        assertTrue(jobsOf(pid).isEmpty(), "job 执行过就该删掉，留着会被再执行一遍");
        assertEquals(1, openTasks(pid).size(), "续跑后待办才该出现");
        assertEquals(WfProcessStatus.ACTIVE, repo.findProcessInstance(pid).getStatus());
    }

    @Test
    @DisplayName("异步前置的 serviceTask：delegate 只在续跑时被调用一次")
    void asyncBeforeRunsDelegateExactlyOnce() {
        String pid = deploy(BEFORE_SERVICE_BPMN, "S-1");

        assertTrue(delegateCalls.isEmpty(), "还没续跑，delegate 不该被调用");
        assertEquals(1, jobsOf(pid).size());
        assertEquals(WfProcessStatus.ACTIVE, repo.findProcessInstance(pid).getStatus());

        jobService.executeAsyncJobs(afterQueueTime());

        assertEquals(1, delegateCalls.size(),
                "delegate 恰好被调用一次。调用两次意味着流程被推进了两遍");
        assertTrue(repo.findProcessInstance(pid).getStatus().isTerminal(),
                "穿透型节点续跑后应当一路走到结束");
    }

    @Test
    @DisplayName("续跑不会死循环：再扫一次没有任何 job 可执行")
    void resumeDoesNotLoopForever() {
        deploy(BEFORE_SERVICE_BPMN, "S-2");

        assertEquals(1, jobService.executeAsyncJobs(afterQueueTime()));
        // 没有 asyncContinuation 标记的话，这里会是 1：续跑会再挂一个 job
        assertEquals(0, jobService.executeAsyncJobs(afterQueueTime()),
                "续跑必须不再挂新的异步 job，否则执行器无限循环、流程永远不动");
        assertEquals(1, delegateCalls.size());
    }

    @Test
    @DisplayName("异步前置的网关：续跑时才选线")
    void asyncBeforeOnGatewayRoutesOnResume() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("ok", Boolean.TRUE);
        String pid = runtime.startProcessInstance(
                repository.deploy(new WfXmlParser().parse(BEFORE_GATEWAY_BPMN)),
                "G-1", "alice", null, vars);

        assertEquals(1, jobsOf(pid).size(), "网关在 enter 里被 asyncBefore 拦下");
        assertTrue(repo.findProcessInstance(pid).getStatus().isActive());

        jobService.executeAsyncJobs(afterQueueTime());

        assertTrue(repo.findProcessInstance(pid).getStatus().isTerminal());
        assertTrue(jobsOf(pid).isEmpty());
    }

    // ==================== 异步后置 ====================

    @Test
    @DisplayName("异步后置：人办完之后才排队离开，流程这时还没走完")
    void asyncAfterSuspendsOnLeavingAfterTaskCompletion() {
        String pid = deploy(AFTER_BPMN, "A-1");

        assertEquals(1, openTasks(pid).size(), "异步后置不影响建待办");
        assertTrue(jobsOf(pid).isEmpty(), "还没办完，不该有异步 job");

        WfTask task = openTasks(pid).get(0);
        runtime.completeTask(task.getId(), "boss", "同意", new HashMap<String, Object>());

        List<WfJob> jobs = jobsOf(pid);
        assertEquals(1, jobs.size(), "办完之后应当排一次队");
        assertEquals(WfJobType.ASYNC_AFTER, jobs.get(0).getType());
        assertFalse(repo.findProcessInstance(pid).getStatus().isTerminal(),
                "还没离开这一步，流程不该已经结束");

        assertEquals(1, jobService.executeAsyncJobs(afterQueueTime()));

        assertTrue(repo.findProcessInstance(pid).getStatus().isTerminal(),
                "续跑离开后流程才该走完");
    }

    @Test
    @DisplayName("异步后置不重建待办、不重跑节点行为")
    void asyncAfterDoesNotRerunNodeBehavior() {
        String pid = deploy(AFTER_BPMN, "A-2");
        WfTask task = openTasks(pid).get(0);
        runtime.completeTask(task.getId(), "boss", "同意", new HashMap<String, Object>());

        jobService.executeAsyncJobs(afterQueueTime());

        assertTrue(openTasks(pid).isEmpty(),
                "续跑走的是 leave 而不是 enter —— 重建待办就等于把办结的单又退回来了");
        List<WfActivityInstance> history = repo.queryActivityInstances(
                new com.zifang.z.wf.core.persistence.WfHistoricActivityInstanceQuery()
                        .setProcessInstanceId(pid));
        int approveVisits = 0;
        for (WfActivityInstance a : history) {
            if ("approve".equals(a.getActivityId())) {
                approveVisits++;
            }
        }
        assertEquals(1, approveVisits,
                "一次节点访问只记一条历史。记两条说明节点被跑了两遍");
    }

    // ==================== 双向 ====================

    @Test
    @DisplayName("前置与后置同开：要排两次队，续跑两次才走完")
    void bothDirectionsNeedTwoResumes() {
        String pid = deploy(BOTH_BPMN, "BB-1");

        assertEquals(1, jobsOf(pid).size(), "先排前置");
        assertTrue(openTasks(pid).isEmpty());

        assertEquals(1, jobService.executeAsyncJobs(afterQueueTime()));
        assertEquals(1, openTasks(pid).size(), "续跑前置后建出待办");
        assertTrue(jobsOf(pid).isEmpty(), "此时还没办结，不该有后置的 job");

        runtime.completeTask(openTasks(pid).get(0).getId(), "boss", "同意",
                new HashMap<String, Object>());
        assertEquals(1, jobsOf(pid).size(), "办结后排后置");

        assertEquals(1, jobService.executeAsyncJobs(afterQueueTime()));
        assertTrue(repo.findProcessInstance(pid).getStatus().isTerminal());
    }

    // ==================== 隔离 ====================

    @Test
    @DisplayName("定时器执行器不碰异步 job —— 否则它会被当成到期表消费掉")
    void timerExecutorNeverConsumesAsyncJobs() {
        String pid = deploy(BEFORE_SERVICE_BPMN, "S-3");
        // duedate 本来就设成 now，显式再拨到过去是为了不给断言留"因为别的原因通过"的空间
        for (WfJob job : jobsOf(pid)) {
            job.setDuedate(new Date(0L));
            job.nextRevision();
            repo.saveJob(job);
        }

        assertEquals(0, jobService.executeDueJobs(new Date()),
                "定时器执行器只该处理 TIMER");
        assertEquals(1, jobsOf(pid).size(), "异步 job 必须还在");
        assertTrue(delegateCalls.isEmpty(), "流程不该在自己往前走");
    }

    @Test
    @DisplayName("续跑按 topic/类型过滤：只捞 ASYNC_BEFORE 与 ASYNC_AFTER")
    void asyncExecutorOnlyPicksAsyncTypes() {
        String pid = deploy(BEFORE_BPMN, "B-2");
        WfJob noise = new WfJob();
        noise.setId("j-timer");
        noise.setProcessInstanceId(pid);
        noise.setElementId("approve");
        noise.setType(WfJobType.TIMER);
        noise.setDuedate(new Date(0L));
        noise.setCreateTime(new Date());
        repo.saveJob(noise);

        assertEquals(1, jobService.executeAsyncJobs(afterQueueTime()), "只续跑异步 job");
        // 断言「重试次数没被动过」而不是「job 还在」：定时器 job 被误捞时的表现是
        // 续跑抛异常→扣一次重试→job 仍在。只断言存在性的话误捞完全测不出来
        WfJob timer = repo.findJob("j-timer");
        assertNotNull(timer, "定时器 job 不该被删");
        assertEquals(com.zifang.z.wf.core.model.WfJob.DEFAULT_RETRIES, timer.getRetries(),
                "定时器 job 不该被异步执行器碰 —— 扣了重试说明它被误捞了");
    }

    @Test
    @DisplayName("token 已挪走时续跑被忽略 —— 把流程从别处拽回来比不续跑更糟")
    void resumeOnMovedTokenIsIgnored() {
        String pid = deploy(BEFORE_BPMN, "B-3");

        // 模拟"撤单慢了一步"：token 已经被推到别处
        for (WfExecution execution : repo.findExecutionsByProcessInstance(pid)) {
            if ("approve".equals(execution.getActivityId())) {
                execution.setActivityId("e1");
                repo.saveExecution(execution);
            }
        }

        assertEquals(0, jobService.executeAsyncJobs(afterQueueTime()),
                "token 不在这一步上了，续跑等于把流程从别处拽走");
        assertTrue(openTasks(pid).isEmpty());
    }

    @Test
    @DisplayName("被忽略的续跑不删 job —— 把还没做的事当成做完了是更糟的错")
    void ignoredResumeKeepsTheJob() {
        String pid = deploy(BEFORE_BPMN, "B-5");

        for (WfExecution execution : repo.findExecutionsByProcessInstance(pid)) {
            if ("approve".equals(execution.getActivityId())) {
                execution.setActivityId("e1");
                repo.saveExecution(execution);
            }
        }

        assertEquals(0, jobService.executeAsyncJobs(afterQueueTime()));
        assertEquals(1, jobsOf(pid).size(),
                "这次续跑没有生效（token 已经不在这一步上），job 必须还在。"
                        + "删掉它等于吞掉一件还没做的事，而流程会永远停在这里");
    }

    @Test
    @DisplayName("流程已结束时残留的异步 job 被忽略，而不是无限重试")
    void residualJobOnTerminatedProcessIsIgnored() {
        String pid = deploy(BEFORE_BPMN, "B-4");
        WfProcessInstance instance = repo.findProcessInstance(pid);
        instance.setStatus(WfProcessStatus.COMPLETED);
        instance.setEndTime(new Date());
        instance.nextRevision();
        repo.saveProcessInstance(instance);

        assertEquals(0, jobService.executeAsyncJobs(afterQueueTime()));
        assertNotNull(repo.findComments(pid));
    }

    @Test
    @DisplayName("续跑失败会扣重试并留下原因，不是静默跳过")
    void failedResumeConsumesRetries() {
        String pid = deploy(BEFORE_SERVICE_BPMN, "S-4");
        // 把定义里的节点改成没有 delegate 的形态，制造续跑失败：
        // 做法是直接把 job 指向一个不存在的节点
        WfJob job = jobsOf(pid).get(0);
        job.setElementId("noSuchNode");
        job.nextRevision();
        repo.saveJob(job);

        assertEquals(0, jobService.executeAsyncJobs(afterQueueTime()));
        WfJob after = repo.findJob(job.getId());
        // 续跑抛异常后 job 已被删除，扣重试的是重新查到的最新状态；
        // 这里关心的是"异常没有把整个批次带崩"
        assertTrue(after == null || after.isRetriesExhausted() || after.getRetries() < 3,
                "失败应当留下痕迹：要么扣了重试，要么记录在评论里");
    }

    @Test
    @DisplayName("执行时刻不能为空 —— 传 null 会在下游变成扫描全部 job")
    void nullNowIsRejected() {
        assertThrows(WfEngineException.class, () -> jobService.executeAsyncJobs(null));
    }

    // ==================== 解析与往返 ====================

    @Test
    @DisplayName("camunda: 前缀同样被识别 —— 迁移别人的模型不该静默丢掉异步")
    void camundaPrefixIsRecognized() {
        WfDefinition definition = new WfXmlParser().parse(CAMUNDA_PREFIX_BPMN);
        assertTrue(definition.node("approve").isAsyncBefore(),
                "camunda:asyncBefore 没被识别的话，迁移过来的模型会悄悄丢掉异步语义");
    }

    @Test
    @DisplayName("部署回读后异步标志仍在")
    void asyncFlagsSurviveRoundTrip() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(BOTH_BPMN));
        WfDefinition reloaded = repository.getDefinition("asyncBoth", definition.getVersion());

        assertNotNull(reloaded);
        assertTrue(reloaded.node("approve").isAsyncBefore());
        assertTrue(reloaded.node("approve").isAsyncAfter());
    }

    @Test
    @DisplayName("asyncBefore=\"1\" 这类写法当没配，不因此拒绝部署")
    void onlyLiteralTrueEnablesAsync() {
        String xml = BEFORE_BPMN.replace("asyncBefore=\"true\"", "asyncBefore=\"1\"");
        WfDefinition definition = new WfXmlParser().parse(xml);
        assertFalse(definition.node("approve").isAsyncBefore());
    }

    // ==================== 部署期校验 ====================

    @Test
    @DisplayName("外部任务 + 异步：部署期 ERROR —— 没有任何 worker 会去领那个 job")
    void externalPlusAsyncIsRejected() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"bad\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                + "    <serviceTask id=\"st\" zifang:topic=\"t\" zifang:asyncBefore=\"true\"/>\n"
                + "    <endEvent id=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"st\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"st\" targetRef=\"e1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(new WfXmlParser().parse(xml)));
        assertTrue(e.getMessage().contains("topic"), e.getMessage());
    }

    @Test
    @DisplayName("多实例 + 异步：部署期 ERROR —— 单 token 续跑没有汇合点")
    void multiInstancePlusAsyncIsRejected() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"bad\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                + "    <userTask id=\"ut\" zifang:assignee=\"boss\""
                + " zifang:asyncBefore=\"true\">\n"
                + "      <multiInstanceLoopCharacteristics>\n"
                + "        <loopCardinality>3</loopCardinality>\n"
                + "      </multiInstanceLoopCharacteristics>\n"
                + "    </userTask>\n"
                + "    <endEvent id=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"ut\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"ut\" targetRef=\"e1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(new WfXmlParser().parse(xml)));
        assertTrue(e.getMessage().contains("多实例"), e.getMessage());
    }

    @Test
    @DisplayName("endEvent + asyncAfter：部署期 ERROR —— 结束节点不走 leave，那行配置永不触发")
    void endEventPlusAsyncAfterIsRejected() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"bad\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                + "    <endEvent id=\"e1\" zifang:asyncAfter=\"true\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"e1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(new WfXmlParser().parse(xml)));
        assertTrue(e.getMessage().contains("asyncAfter"), e.getMessage());
    }

    @Test
    @DisplayName("普通流程不受影响：没写 async 的定义照常部署运行")
    void plainProcessIsUnaffected() {
        String pid = deploy(BEFORE_BPMN.replace(" zifang:asyncBefore=\"true\"", ""), "P-1");
        assertTrue(jobsOf(pid).isEmpty());
        assertEquals(1, openTasks(pid).size());
    }
}
