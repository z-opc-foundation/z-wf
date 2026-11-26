package com.zifang.z.wf.core.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.service.WfJobService;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.service.WfTaskService;

/**
 * 流转钩子 / job 生命周期钩子 / 任务消失钩子。
 *
 * <p>本类的存在理由与 {@link WfHookDispatchAuditTest} 相同：
 * <b>"接口在、方法有、测试也过了"不等于"它会触发"</b>。上一轮审计就是在
 * 静态检查全绿的情况下找出三个从不触发的回调。
 *
 * <p>所以这里的每一条断言都要求真的跑一遍流程，而不是只验证方法存在。
 *
 * @author zifang
 */
class WfTransitionAndJobHookTest {

    /** 审批 + 超时边界：流转两次、排一张表、办结、触发。 */
    private static final String BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"hookProc\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <boundaryEvent id=\"timeout\" attachedToRef=\"approve\">\n"
            + "      <timerEventDefinition>\n"
            + "        <timeDuration>${sla}</timeDuration>\n"
            + "      </timerEventDefinition>\n"
            + "    </boundaryEvent>\n"
            + "    <userTask id=\"remind\" name=\"催办\" zifang:assignee=\"ceo\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <endEvent id=\"e2\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"timeout\" targetRef=\"remind\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"remind\" targetRef=\"e2\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 并行网关 + 汇合：用来数流转触发次数。 */
    private static final String PARALLEL_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"parProc\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <parallelGateway id=\"split\"/>\n"
            + "    <userTask id=\"a\" zifang:assignee=\"alice\"/>\n"
            + "    <userTask id=\"b\" zifang:assignee=\"bob\"/>\n"
            + "    <parallelGateway id=\"join\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"split\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"split\" targetRef=\"a\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"split\" targetRef=\"b\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"a\" targetRef=\"join\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"b\" targetRef=\"join\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"join\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 异步前置：用来验证异步 job 的排队与执行钩子。 */
    private static final String ASYNC_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"asyncProc\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" zifang:assignee=\"boss\""
            + " zifang:asyncBefore=\"true\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 数列表里有几项包含给定片段。断言"只发生了一次"时比"发生过"更有区分力。 */
    private static int countContaining(List<String> list, String part) {
        int n = 0;
        for (String item : list) {
            if (item.contains(part)) {
                n++;
            }
        }
        return n;
    }

    /** 或签：第一个人办完就放行，其余实例的任务被作废。 */
    private static final String COUNTER_SIGN_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"counterSign\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"sign\" zifang:assignee=\"u${loopCounter}\">\n"
            + "      <multiInstanceLoopCharacteristics>\n"
            + "        <loopCardinality>3</loopCardinality>\n"
            + "        <completionCondition>${nrOfCompletedInstances &gt;= 1}</completionCondition>\n"
            + "      </multiInstanceLoopCharacteristics>\n"
            + "    </userTask>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"sign\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"sign\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfTaskService taskService;
    private WfJobService jobService;
    private Recorder rec;
    private TaskRecorder taskRec;

    /** 记录所有流转/job/任务消失事件，便于断言"发生了什么"而不只是"有没有发生"。 */
    /**
     * 流程侧记录器。
     *
     * <p>与 {@link TaskRecorder} 分开而不是一个类同时实现两个接口：
     * 两个接口各自有 {@code hookType()} 的 default 实现，Java 不允许
     * 一个类继承两个"无关的默认实现"，编译直接报 unrelated defaults。
     */
    private static final class Recorder implements WfProcessHook {
        final List<String> transitions = new ArrayList<>();
        final List<String> jobsScheduled = new ArrayList<>();
        final List<String> jobsExecuted = new ArrayList<>();

        @Override
        public void onTransition(String definitionKey, String processInstanceId,
                                  String fromActivityId, String toActivityId, String flowId) {
            transitions.add(fromActivityId + "->" + toActivityId + "@" + flowId
                    + "[" + definitionKey + "]");
        }

        @Override
        public void onJobScheduled(String definitionKey, String processInstanceId,
                                   String jobId, String jobType, String elementId) {
            jobsScheduled.add(jobType + ":" + elementId + "@" + jobId);
        }

        @Override
        public void onJobExecuted(String definitionKey, String processInstanceId,
                                  String jobId, String jobType, String elementId,
                                  boolean success) {
            jobsExecuted.add(jobType + ":" + elementId + "@" + jobId + "=" + success);
        }

    }

    /** 任务侧记录器。 */
    private static final class TaskRecorder implements WfTaskHook {
        final List<String> deleted = new ArrayList<>();

        @Override
        public void onDeleted(String taskId, String assignee, String processInstanceId,
                              String reason) {
            deleted.add(reason + ":" + assignee);
        }
    }

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        WfHookDispatcher dispatcher = new WfHookDispatcher();
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), dispatcher);
        taskService = new WfTaskService(repository, repo, runtime, dispatcher);
        jobService = new WfJobService(repo, runtime);
        rec = new Recorder();
        taskRec = new TaskRecorder();
        dispatcher.addProcessHook(rec);
        dispatcher.addTaskHook(taskRec);
    }

    private String start(String xml, String key, Map<String, Object> vars) {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(xml));
        return runtime.startProcessInstance(definition, "BIZ-" + System.nanoTime(),
                "alice", null, vars == null ? new HashMap<String, Object>() : vars);
    }

    private WfTask theTask(String pid) {
        List<WfTask> open = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(10));
        return open.isEmpty() ? null : open.get(0);
    }

    // ==================== 流转钩子 ====================

    @Test
    @DisplayName("流转钩子每走一步触发一次，并带出 from/to/flowId/定义 key")
    void transitionFiresOnEveryHop() {
        String pid = start(BPMN, "hookProc", vars("PT30M"));

        // 起流程只走一步：token 停在审批节点上，边界还没到期
        assertEquals(1, rec.transitions.size(),
                "起流程只该走到审批。实际 " + rec.transitions);
        assertEquals("s1->approve@f1[hookProc]", rec.transitions.get(0));

        // 边界到期后被拉到催办
        jobService.executeDueJobs(new Date(System.currentTimeMillis() + 3_600_000L));
        assertEquals(2, rec.transitions.size(),
                "触发边界应当再触发一次流转。实际 " + rec.transitions);
        assertEquals("timeout->remind@f3[hookProc]", rec.transitions.get(1));

        // 办结催办再走一步
        runtime.completeTask(theTask(pid).getId(), "ceo", "已催办", new HashMap<String, Object>());
        assertEquals(3, rec.transitions.size(),
                "办结催办后应当走到 e2。实际 " + rec.transitions);
        assertEquals("remind->e2@f4[hookProc]", rec.transitions.get(2));
    }

    @Test
    @DisplayName("并行分叉：每条出线各触发一次 —— 不然统计会漏掉分支")
    void transitionFiresPerOutgoingFlow() {
        start(PARALLEL_BPMN, "parProc", null);

        // s1->split, split->a, split->b：三条出线三次流转
        assertEquals(3, rec.transitions.size(), "实际 " + rec.transitions);
        assertEquals(1, countContaining(rec.transitions, "split->a@f2"));
        assertEquals(1, countContaining(rec.transitions, "split->b@f3"));
    }

    @Test
    @DisplayName("没装配钩子时流转照常，不许 NPE")
    void transitionWorksWithoutHooks() {
        InMemoryWorkflowPersistence bare = new InMemoryWorkflowPersistence();
        bare.initialize();
        WfRepositoryService bareRepo = new WfRepositoryService(bare);
        WfRuntimeService bareRuntime = new WfRuntimeService(bareRepo, bare,
                new WfEngine(), new WfHookDispatcher());
        WfDefinition d = bareRepo.deploy(new WfXmlParser().parse(BPMN));
        String pid = bareRuntime.startProcessInstance(d, "B1", "alice", null,
                vars("PT30M"));

        assertEquals(1, bare.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(10)).size(),
                "没有钩子时流程本身必须照常推进");
    }

    // ==================== job 生命周期钩子 ====================

    @Test
    @DisplayName("排上定时器时发 onJobScheduled，带 job 类型与节点")
    void jobScheduledFiresWithType() {
        String pid = start(BPMN, "hookProc", vars("PT30M"));

        assertEquals(1, rec.jobsScheduled.size(), "实际 " + rec.jobsScheduled);
        assertTrue(rec.jobsScheduled.get(0).startsWith("TIMER:timeout@"),
                "钩子必须带上 job 类型与元素 id：" + rec.jobsScheduled);
        // jobId 必须能查到 —— 钩子实现方常拿它去查表
        WfJob job = repo.queryJobs(new WfJobQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(10)).get(0);
        assertTrue(rec.jobsScheduled.get(0).endsWith("@" + job.getId()),
                "onJobScheduled 触发时 job 应当已落库可查，实际 " + rec.jobsScheduled);
    }

    @Test
    @DisplayName("定时器触发时发 onJobExecuted 且 success=true")
    void jobExecutedFiresOnTimerFire() {
        start(BPMN, "hookProc", vars("PT30M"));

        jobService.executeDueJobs(new Date(System.currentTimeMillis() + 3_600_000L));

        assertEquals(1, rec.jobsExecuted.size(), "实际 " + rec.jobsExecuted);
        assertTrue(rec.jobsExecuted.get(0).startsWith("TIMER:timeout@"),
                rec.jobsExecuted.get(0));
        assertTrue(rec.jobsExecuted.get(0).endsWith("=true"),
                "真的推进了，success 应当为 true：" + rec.jobsExecuted);
    }

    @Test
    @DisplayName("job 被撤掉时不再发 onJobExecuted —— 它根本没被执行过")
    void withdrawnJobFiresNoExecutedEvent() {
        String pid = start(BPMN, "hookProc", vars("PT30M"));
        WfTask task = theTask(pid);
        runtime.completeTask(task.getId(), "boss", "同意", new HashMap<String, Object>());

        // token 离开审批节点时清掉了它起过的表
        assertTrue(repo.queryJobs(new WfJobQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(10)).isEmpty(), "定时器应随离开节点被撤");
        assertTrue(rec.jobsExecuted.isEmpty(),
                "没响过的 job 不该发执行事件，否则【执行了 N 次】会虚高。实际 "
                        + rec.jobsExecuted);
    }

    @Test
    @DisplayName("流程已结束时残留的 job 发 success=false，不虚报成功")
    void residualJobFiresWithSuccessFalse() {
        String pid = start(BPMN, "hookProc", vars("PT30M"));
        WfTask task = theTask(pid);
        runtime.completeTask(task.getId(), "boss", "同意", new HashMap<String, Object>());

        // 人为造一个残留 job：token 已走，但表还在
        WfJob residual = new WfJob();
        residual.setId("j-residual");
        residual.setProcessInstanceId(pid);
        residual.setElementId("timeout");
        residual.setAttachedToRef("approve");
        residual.setType(WfJobType.TIMER);
        residual.setDuedate(new Date(0L));
        residual.setCreateTime(new Date());
        repo.saveJob(residual);

        jobService.executeDueJobs(new Date(System.currentTimeMillis() + 3_600_000L));

        assertEquals(1, countContaining(rec.jobsExecuted, "j-residual"),
                "残留 job 也该发事件，否则【执行次数】漏计。实际 " + rec.jobsExecuted);
        assertEquals(1, countContaining(rec.jobsExecuted, "j-residual=false"),
                "这次没真的推进，success 必须为 false：" + rec.jobsExecuted);
    }

    @Test
    @DisplayName("异步 job 的排队与执行都发钩子")
    void asyncJobHooksFire() {
        start(ASYNC_BPMN, "asyncProc", null);

        assertEquals(1, countContaining(rec.jobsScheduled, "ASYNC_BEFORE"),
                "异步前置排单时应当发钩子。实际 " + rec.jobsScheduled);
        assertTrue(rec.jobsExecuted.isEmpty(), "还没执行不该有执行事件");

        jobService.executeAsyncJobs(new Date(System.currentTimeMillis() + 1000L));

        assertEquals(1, countContaining(rec.jobsExecuted, "ASYNC_BEFORE"),
                "续跑后应当发执行事件。实际 " + rec.jobsExecuted);
        assertTrue(countContaining(rec.jobsExecuted, "ASYNC_BEFORE") == 1);
    }

    // ==================== 任务消失钩子 ====================

    @Test
    @DisplayName("流程终止：任务消失发 onDeleted，原因是 terminated")
    void terminateFiresDeleted() {
        String pid = start(BPMN, "hookProc", vars("PT30M"));
        assertEquals(1, theTask(pid) == null ? 0 : 1);

        runtime.terminate(pid, "领导撤回申请");

        assertEquals(1, taskRec.deleted.size(), "实际 " + taskRec.deleted);
        assertEquals("terminated:boss", taskRec.deleted.get(0));
    }

    @Test
    @DisplayName("被边界事件打断：发 onDeleted 而不是 onAfterComplete")
    void boundaryInterruptFiresDeleted() {
        start(BPMN, "hookProc", vars("PT30M"));

        jobService.executeDueJobs(new Date(System.currentTimeMillis() + 3_600_000L));

        assertEquals(1, taskRec.deleted.size(), "实际 " + taskRec.deleted);
        assertEquals("boundary-interrupted:boss", taskRec.deleted.get(0),
                "被打断不是办结，reason 必须能区分");
    }

    @Test
    @DisplayName("撤回不发 onDeleted —— 任务没消失，它回到待办了")
    void withdrawDoesNotFireDeleted() {
        String pid = start(BPMN, "hookProc", vars("PT30M"));
        WfTask task = theTask(pid);
        // approve 已由 zifang:assignee="boss" 分配，不能 claim（那是无主任务的入口），
        // 直接撤回即可 —— 撤回对已分配的任务同样成立
        taskService.withdraw(task.getId(), "boss", "先放一放");

        assertTrue(taskRec.deleted.isEmpty(),
                "撤回把任务放回待办，任务没消失。混进 fireDeleted 会让"
                        + "\"任务消失率\"这个指标彻底失去意义。实际 " + taskRec.deleted);
        assertEquals(1, theTask(pid) == null ? 0 : 1, "任务还在待办里");
    }

    @Test
    @DisplayName("正常办结不发 onDeleted —— 它走 onAfterComplete")
    void normalCompleteDoesNotFireDeleted() {
        String pid = start(BPMN, "hookProc", vars("PT30M"));
        WfTask task = theTask(pid);
        runtime.completeTask(task.getId(), "boss", "同意", new HashMap<String, Object>());

        assertTrue(taskRec.deleted.isEmpty(),
                "办结不是消失。两者混在一起，接入方无法区分【办完】与【没了】。实际 "
                        + taskRec.deleted);
    }

    @Test
    @DisplayName("会签收口：剩余实例的任务消失发 onDeleted，原因是 multi-instance-closed")
    void multiInstanceCloseFiresDeleted() {
        String pid = start(COUNTER_SIGN_BPMN, "counterSign", null);
        List<WfTask> open = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(10));
        assertEquals(3, open.size(), "或签应展开 3 个实例，实际 " + open.size());

        // 第一个人办完即放行，另两个实例的任务被作废
        runtime.completeTask(open.get(0).getId(), open.get(0).getAssignee(), "同意",
                new HashMap<String, Object>());

        assertEquals(2, taskRec.deleted.size(),
                "另 2 个实例的任务消失时各发一次。实际 " + taskRec.deleted);
        assertEquals(2, countContaining(taskRec.deleted, "multi-instance-closed"),
                "reason 必须标明是会签收口，接入方才能与【被终止/被打断】分开处理。"
                        + "实际 " + taskRec.deleted);
    }

    private Map<String, Object> vars(String sla) {
        Map<String, Object> m = new HashMap<>();
        m.put("sla", sla);
        return m;
    }
}
