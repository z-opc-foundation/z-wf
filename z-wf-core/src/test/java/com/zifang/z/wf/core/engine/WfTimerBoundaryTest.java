package com.zifang.z.wf.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfDefinitionValidator;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.definition.WfValidationIssue;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.service.WfHistoryService;
import com.zifang.z.wf.core.service.WfJobService;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.service.WfTaskService;

/**
 * 定时器边界事件与 job 机制。
 *
 * <p>这套机制的判据全是<b>行为</b>而不是"字段解析出来了"：
 * 起没起表、撤没撤表、到点响不响、响完流程走没走对分支。
 * 解析正确但引擎不建 job，是本轮开头的探针实测出来的那个形态
 * （定时器边界曾被当成错误边界，报错要求写 errorCode，直接把合法的 BPMN 拒了）。
 *
 * @author zifang
 */
class WfTimerBoundaryTest {

    /** 审批 30 分钟不处理就转"催办"，催办完再结束。 */
    private static final String BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"timerProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <boundaryEvent id=\"timeout\" name=\"超时提醒\" attachedToRef=\"approve\">\n"
            + "      <timerEventDefinition>\n"
            + "        <timeDuration>PT30M</timeDuration>\n"
            + "      </timerEventDefinition>\n"
            + "    </boundaryEvent>\n"
            + "    <userTask id=\"remind\" name=\"催办\" zifang:assignee=\"ceo\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <endEvent id=\"e2\" name=\"超时终止\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"timeout\" targetRef=\"remind\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"remind\" targetRef=\"e2\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 两步流程：办结第一步后流程<b>仍在</b>流转，用来隔离"离开节点就撤表"这条路径。 */
    private static final String TWO_STEP_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"twoStep\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <boundaryEvent id=\"timeout\" name=\"超时提醒\" attachedToRef=\"approve\">\n"
            + "      <timerEventDefinition>\n"
            + "        <timeDuration>PT30M</timeDuration>\n"
            + "      </timerEventDefinition>\n"
            + "    </boundaryEvent>\n"
            + "    <userTask id=\"review\" name=\"复核\" zifang:assignee=\"ceo\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <endEvent id=\"e2\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"review\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"review\" targetRef=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"timeout\" targetRef=\"e2\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfTaskService taskService;
    private WfHistoryService history;
    private WfJobService jobs;

    private void fresh() {
        repo = new InMemoryWorkflowPersistence();
        repository = new WfRepositoryService(repo);
        WfEngine engine = new WfEngine();
        WfHookDispatcher hooks = new WfHookDispatcher();
        runtime = new WfRuntimeService(repository, repo, engine, hooks);
        taskService = new WfTaskService(repository, repo, runtime, hooks);
        history = new WfHistoryService(repo);
        jobs = new WfJobService(repo, runtime);
    }

    private String deployAndStart(String businessKey, Map<String, Object> vars) {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(BPMN));
        return runtime.startProcessInstance(definition, businessKey, "alice", null,
                vars == null ? new HashMap<String, Object>() : vars);
    }

    private WfTask taskOn(String processInstanceId, String nodeId) {
        for (WfTask task : repo.queryTasks(new com.zifang.z.wf.core.persistence.WfTaskQuery()
                .setProcessInstanceId(processInstanceId).setOpenOnly(true)
                .setPageNum(1).setPageSize(20))) {
            if (nodeId.equals(task.getDefinitionId())) {
                return task;
            }
        }
        return null;
    }

    // ==================== 解析与校验 ====================

    @Test
    @DisplayName("定时器边界不再被当成错误边界要求写 errorCode")
    void timerBoundaryDeploysWithoutErrorCode() {
        fresh();
        WfDefinition parsed = new WfXmlParser().parse(BPMN);
        List<WfValidationIssue> issues = new WfDefinitionValidator().validate(parsed);
        assertTrue(!WfDefinitionValidator.hasError(issues),
                "合法的定时器边界不该被拒。实际问题: "
                        + WfDefinitionValidator.render(issues));

        WfNode boundary = parsed.node("timeout");
        assertNotNull(boundary);
        assertEquals(WfNodeType.BOUNDARY_EVENT, boundary.getType());
        assertTrue(boundary.isTimerBoundary(), "应当被识别为定时器边界");
        assertEquals("PT30M", boundary.getTimerExpression());
        assertEquals("approve", boundary.getAttachedToRef());
    }

    @Test
    @DisplayName("循环定时器在部署期被明确拒绝，不留一个永远不响的哑表")
    void cycleTimerIsRejected() {
        fresh();
        String xml = BPMN.replace("<timeDuration>PT30M</timeDuration>",
                "<timeCycle>R3/PT10M</timeCycle>");
        WfDefinition parsed = new WfXmlParser().parse(xml);
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(parsed));
        assertTrue(ex.getMessage().contains("timeCycle"), ex.getMessage());
    }

    @Test
    @DisplayName("定时器表达式非法时报出的是表达式的问题，不是别的")
    void malformedTimerIsRejected() {
        fresh();
        String xml = BPMN.replace("<timeDuration>PT30M</timeDuration>",
                "<timeDuration>半小时</timeDuration>");
        WfDefinition parsed = new WfXmlParser().parse(xml);
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(parsed));
        assertTrue(ex.getMessage().contains("半小时"), ex.getMessage());
    }

    @Test
    @DisplayName("既没有 errorRef 也没有 timerEventDefinition 仍被拒 —— 触发条件为零")
    void boundaryWithoutAnyTriggerIsRejected() {
        fresh();
        String xml = BPMN.replace(
                "      <timerEventDefinition>\n"
                        + "        <timeDuration>PT30M</timeDuration>\n"
                        + "      </timerEventDefinition>\n", "");
        WfDefinition parsed = new WfXmlParser().parse(xml);
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(parsed));
        assertTrue(ex.getMessage().contains("永远不会触发"), ex.getMessage());
    }

    // ==================== 起表 ====================

    @Test
    @DisplayName("进入宿主节点就起表，到期时刻 = 进入时刻 + 30 分钟")
    void timerStartsWhenEnteringHostNode() {
        fresh();
        long before = System.currentTimeMillis();
        String pid = deployAndStart("T-1", null);
        long after = System.currentTimeMillis();

        List<WfJob> pending = jobs.findJobsByProcessInstance(pid);
        assertEquals(1, pending.size(), "token 停在审批节点上，应当恰好一只表");
        WfJob job = pending.get(0);
        assertEquals("timeout", job.getElementId());
        assertEquals("approve", job.getAttachedToRef());
        assertEquals(pid, job.getProcessInstanceId());
        assertTrue(job.getDuedate().getTime() >= before + 30 * 60 * 1000L,
                "到期时刻应当是进入时刻 + 30 分钟");
        assertTrue(job.getDuedate().getTime() <= after + 30 * 60 * 1000L);
        assertEquals(WfJob.DEFAULT_RETRIES, job.getRetries());
    }

    @Test
    @DisplayName("定时器表达式可以引用流程变量：审批时限由发起人自己填")
    void timerCanReadProcessVariable() {
        fresh();
        String xml = BPMN.replace("<timeDuration>PT30M</timeDuration>",
                "<timeDuration>${sla}</timeDuration>");
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(xml));
        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put("sla", "PT2H");
        String pid = runtime.startProcessInstance(definition, "T-2", "alice", null, vars);

        List<WfJob> pending = jobs.findJobsByProcessInstance(pid);
        assertEquals(1, pending.size());
        long slack = pending.get(0).getDuedate().getTime()
                - pending.get(0).getCreateTime().getTime();
        assertTrue(Math.abs(slack - 2 * 60 * 60 * 1000L) < 5000L,
                "应当按变量里的 PT2H 算，实际相差 " + slack + "ms");
    }

    @Test
    @DisplayName("变量没赋值时报出来，不按字面量硬算")
    void missingTimerVariableIsReported() {
        fresh();
        String xml = BPMN.replace("<timeDuration>PT30M</timeDuration>",
                "<timeDuration>${sla}</timeDuration>");
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(xml));
        // 校验期放过变量（此刻还没有实例），运行期必须报出来
        assertThrows(WfEngineException.class, () -> runtime.startProcessInstance(
                definition, "T-3", "alice", null, new HashMap<String, Object>()));
    }

    // ==================== 撤表 ====================

    @Test
    @DisplayName("按时办结后表被撤掉 —— 不撤的话 30 分钟后会响，把办完的单拽进超时分支")
    void jobIsClearedWhenTaskCompleted() {
        fresh();
        // 特意用"办结后流程还没结束"的两步流程：
        // 一步流程里 resolveCompletion 的实例级清理会顺手把 job 也删掉，
        // 那样这条断言就分不出"离开节点就撤表"到底有没有生效 ——
        // 两个清理路径互为掩护时，测试必须挑一条唯一会走的那条路
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(TWO_STEP_BPMN));
        String pid = runtime.startProcessInstance(definition, "T-4", "alice", null,
                new HashMap<String, Object>());
        assertEquals(1, jobs.findJobsByProcessInstance(pid).size());

        runtime.completeTask(taskOn(pid, "approve").getId(), "boss", "同意",
                new HashMap<String, Object>());

        assertEquals(0, jobs.findJobsByProcessInstance(pid).size(),
                "人已经按时办完，定时器必须一起撤");
        assertEquals(WfProcessStatus.ACTIVE, repo.findProcessInstance(pid).getStatus(),
                "流程还在复核节点上，还没结束 —— 所以这一步的清理只可能来自 leave");
        assertNotNull(taskOn(pid, "review"), "应当已经推进到复核");
    }

    @Test
    @DisplayName("流程正常结束时，实例名下的 job 一个不剩")
    void jobsAreClearedWhenProcessCompletes() {
        fresh();
        String pid = deployAndStart("T-5", null);
        runtime.completeTask(taskOn(pid, "approve").getId(), "boss", "同意",
                new HashMap<String, Object>());
        assertEquals(0, repo.countJobs(new WfJobQuery().setProcessInstanceId(pid)));
    }

    @Test
    @DisplayName("强制终止后 job 一并撤掉，不留会去找不存在实例的残留")
    void jobsAreClearedOnTerminate() {
        fresh();
        String pid = deployAndStart("T-6", null);
        assertEquals(1, jobs.findJobsByProcessInstance(pid).size());

        runtime.terminate(pid, "领导说不用批了");

        assertEquals(0, jobs.findJobsByProcessInstance(pid).size());
        assertEquals(WfProcessStatus.EXTERNALLY_TERMINATED,
                repo.findProcessInstance(pid).getStatus());
    }

    // ==================== 触发 ====================

    @Test
    @DisplayName("到点后触发边界事件：token 走超时分支，宿主待办作废")
    void dueJobFiresBoundary() {
        fresh();
        String pid = deployAndStart("T-7", null);
        WfJob job = jobs.findJobsByProcessInstance(pid).get(0);

        // 未到期不响
        assertEquals(0, jobs.executeDueJobs(new Date(job.getDuedate().getTime() - 1000L)));
        assertEquals(1, jobs.findJobsByProcessInstance(pid).size(), "没到点不该被清");

        int fired = jobs.executeDueJobs(new Date(job.getDuedate().getTime() + 1000L));
        assertEquals(1, fired);

        // token 已经挪到边界事件上，并沿出线走到"催办"
        WfTask remind = taskOn(pid, "remind");
        assertNotNull(remind, "应当走超时分支到催办节点");
        assertEquals("ceo", remind.getAssignee());
        assertNull(taskOn(pid, "approve"), "宿主节点上的待办应作废 —— 人已超时");

        // job 本身已被消费
        assertEquals(0, jobs.findJobsByProcessInstance(pid).size());

        // 轨迹上能看到"超时"，这是事后追责的唯一依据
        boolean sawTimeout = false;
        for (com.zifang.z.wf.core.model.WfActivityInstance a
                : history.queryActivities(new com.zifang.z.wf.core.persistence
                .WfHistoricActivityInstanceQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(50))) {
            if ("timeout".equals(a.getActivityId())) {
                sawTimeout = true;
                assertTrue(a.getOutcome().startsWith("timer:"),
                        "记录里应当写明是超时触发，实际: " + a.getOutcome());
            }
        }
        assertTrue(sawTimeout, "轨迹上必须有超时边界事件这一行");
    }

    @Test
    @DisplayName("触发后走完补偿分支，流程正常结束")
    void timeoutBranchCanRunToCompletion() {
        fresh();
        String pid = deployAndStart("T-8", null);
        WfJob job = jobs.findJobsByProcessInstance(pid).get(0);
        jobs.executeDueJobs(new Date(job.getDuedate().getTime() + 1000L));

        WfTask remind = taskOn(pid, "remind");
        assertNotNull(remind);
        runtime.completeTask(remind.getId(), "ceo", "已催办", new HashMap<String, Object>());

        WfProcessInstance instance = repo.findProcessInstance(pid);
        assertEquals(WfProcessStatus.COMPLETED, instance.getStatus());
        assertEquals(0, jobs.findJobsByProcessInstance(pid).size());
    }

    @Test
    @DisplayName("同一批 job 只触发一次，不会把补偿分支走两遍")
    void jobIsFiredOnlyOnce() {
        fresh();
        String pid = deployAndStart("T-9", null);
        WfJob job = jobs.findJobsByProcessInstance(pid).get(0);
        Date after = new Date(job.getDuedate().getTime() + 1000L);

        assertEquals(1, jobs.executeDueJobs(after));
        assertEquals(0, jobs.executeDueJobs(after), "已经触发过的 job 不能再响一次");
    }

    @Test
    @DisplayName("实例已结束时残留的 job 只记日志，不当作失败反复重试")
    void leftoverJobOnFinishedProcessIsIgnored() {
        fresh();
        String pid = deployAndStart("T-10", null);
        WfJob job = jobs.findJobsByProcessInstance(pid).get(0);

        // 绕过引擎直接改库终止：模拟"清理逻辑漏了"的那种残留
        WfProcessInstance instance = repo.findProcessInstance(pid);
        instance.setStatus(WfProcessStatus.EXTERNALLY_TERMINATED);
        instance.setEndTime(new Date());
        instance.nextRevision();
        repo.saveProcessInstance(instance);

        assertEquals(0, jobs.executeDueJobs(new Date(job.getDuedate().getTime() + 1000L)),
                "流程已结束，触发没有意义且重试永远不会有用");
    }

    @Test
    @DisplayName("执行时刻为 null 直接拒绝，不当成'执行全部'")
    void nullExecutionTimeIsRejected() {
        fresh();
        deployAndStart("T-11", null);
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> jobs.executeDueJobs(null));
        assertTrue(ex.getMessage().contains("不能为空"), ex.getMessage());
    }

    // ==================== 排障入口 ====================

    @Test
    @DisplayName("耗尽重试的 job 留在表里可查，不悄悄清掉")
    void exhaustedJobsRemainQueryable() {
        fresh();
        String pid = deployAndStart("T-12", null);
        WfJob job = jobs.findJobsByProcessInstance(pid).get(0);

        // 手动把它打成耗尽：retries 扣到 0 再往下就钉死在 -1
        for (int i = 0; i < WfJob.DEFAULT_RETRIES + 2; i++) {
            job.recordFailure("第 " + (i + 1) + " 次失败");
        }
        job.nextRevision();
        repo.saveJob(job);
        assertTrue(job.isRetriesExhausted());

        List<WfJob> exhausted = jobs.findExhaustedJobs(1, 20);
        assertEquals(1, exhausted.size(), "漏发的提醒必须查得到");
        assertEquals("第 5 次失败", exhausted.get(0).getExceptionMessage());
        assertNotNull(exhausted.get(0).getLastFailureTime());

        // 耗尽的不会被执行器再捞起来
        assertEquals(0, jobs.executeDueJobs(new Date(System.currentTimeMillis() + 86_400_000L)));
    }

    @Test
    @DisplayName("到点查询与 count 口径一致，按到期时刻正序")
    void jobQueryOrdersByDuedate() {
        fresh();
        String pid = deployAndStart("T-13", null);
        WfJob job = jobs.findJobsByProcessInstance(pid).get(0);

        WfJobQuery query = new WfJobQuery().setProcessInstanceId(pid);
        assertEquals(1, repo.countJobs(query));
        assertEquals(0, repo.countJobs(query.setDueBefore(
                new Date(job.getDuedate().getTime() - 1L))), "严格早于，不含边界本身");
        assertEquals(1, repo.countJobs(query.setDueBefore(
                new Date(job.getDuedate().getTime() + 1L))));

        List<WfJob> due = repo.queryJobs(query.setDueBefore(
                new Date(job.getDuedate().getTime() + 1L)).setPageNum(1).setPageSize(10));
        assertEquals(1, due.size());
        assertEquals("timeout", due.get(0).getElementId());
    }

    @Test
    @DisplayName("token 已被推进到别处时，过期的表不触发")
    void staleJobOnMovedTokenIsIgnored() {
        fresh();
        String pid = deployAndStart("T-14", null);
        WfJob job = jobs.findJobsByProcessInstance(pid).get(0);

        // 模拟"撤表慢了一步"：token 已经被推到提醒节点
        for (WfExecution execution : repo.findExecutionsByProcessInstance(pid)) {
            if ("approve".equals(execution.getActivityId())) {
                execution.setActivityId("remind");
                repo.saveExecution(execution);
            }
        }
        assertEquals(0, jobs.executeDueJobs(new Date(job.getDuedate().getTime() + 1000L)),
                "token 不在宿主节点上了，触发它等于把流程从别处拽走");
    }

    @Test
    @DisplayName("超时触发在轨迹旁留下一条可追查的审计")
    void timeoutLeavesAuditTrail() {
        fresh();
        String pid = deployAndStart("T-15", null);
        WfJob job = jobs.findJobsByProcessInstance(pid).get(0);
        jobs.executeDueJobs(new Date(job.getDuedate().getTime() + 1000L));

        List<WfComment> comments = repo.findComments(pid);
        boolean sawJobNote = false;
        for (WfComment c : comments) {
            if ("job".equals(c.getType()) && c.getContent().contains("停留超时")) {
                sawJobNote = true;
                assertEquals("system", c.getUserId(),
                        "超时不是任何人干的，记成具体某人会误导追责");
            }
        }
        assertTrue(sawJobNote, "超时必须留审计，实际: " + comments);
    }
}
