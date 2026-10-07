package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 外部任务把「外部世界出的结果」交回流程（第 41 轮）。
 *
 * <p>补的是两条：{@code handleBpmnError}（真的缺）与
 * {@code handleEscalation}（**经核实不是缺口** —— 原因见下面那条用例的注释）。
 *
 * <p>在补上之前，worker 只有 {@code complete} 与 {@code fail} 两个选择：
 * 成功交差，或者失败重试到死。而"外部系统抛了一个带错误码的错误、
 * 流程上配了对应的错误边界希望走补偿分支"这种最常见的情形**根本无法表达** ——
 * 只能一路 {@code fail} 到重试耗尽，然后 job 躺在库里等人。
 */
class WfExternalErrorHandbackTest {

    private static final String NS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n";

    private static final String NS_END = "</definitions>\n";

    /**
     * 外部任务 + 错误边界：外部动作失败并带错误码时，token 应沿边界事件的出线走补救分支。
     *
     * <p><b>边界出线上是普通的外部任务（退款），不是 {@code isForCompensation="true"} 的补偿处理器。</b>
     * 这两件事是<b>不同的机制</b>，早先在这里写成后者是错的：
     * <ul>
     *   <li><b>错误边界</b>：token 被搬到边界上，然后<b>沿出线走</b>。到达的是出线的目标节点。</li>
     *   <li><b>补偿</b>：撤销作用域时按登记逆序执行补偿处理器，
     *       那些处理器在正常路径上<b>没有入线</b>（{@code WfDefinitionValidator} 明确禁止，
     *       否则它们会在正轨上先执行一遍）。</li>
     * </ul>
     * 给边界出线接一个补偿处理器，会同时踩中那两条部署期规则
     * （「补偿处理器不能有入线 / 不能有出线」）—— 这份定义根本部署不上去。
     */
    private static final String ERROR_BOUNDARY_BPMN = NS
            + "  <process id=\"extErr\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <serviceTask id=\"charge\" name=\"调用扣款接口\""
            + " zifang:type=\"external\" zifang:topic=\"charge\"/>\n"
            + "    <boundaryEvent id=\"onFail\" attachedToRef=\"charge\">\n"
            + "      <errorEventDefinition errorRef=\"PAYMENT_FAILED\"/>\n"
            + "    </boundaryEvent>\n"
            + "    <serviceTask id=\"refund\" name=\"退款\""
            + " zifang:type=\"external\" zifang:topic=\"refund\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"charge\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"charge\" targetRef=\"e\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"onFail\" targetRef=\"refund\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"refund\" targetRef=\"e2\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <endEvent id=\"e2\"/>\n"
            + "  </process>\n"
            + NS_END;

    /** 没有错误边界：同一个错误码无处可捕时，流程应当以内部终止收场并记下错误码。 */
    private static final String NO_BOUNDARY_BPMN = NS
            + "  <process id=\"extNoErr\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <serviceTask id=\"charge\" name=\"调用扣款接口\""
            + " zifang:type=\"external\" zifang:topic=\"charge\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"charge\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"charge\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + NS_END;

    /** 外部任务 + 升级边界：用来核实「升级是不是真的不用 handleEscalation」。 */
    private static final String ESCALATION_BOUNDARY_BPMN = NS
            + "  <process id=\"extEsc\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <serviceTask id=\"charge\" name=\"调用扣款接口\""
            + " zifang:type=\"external\" zifang:topic=\"charge\"/>\n"
            + "    <boundaryEvent id=\"escBoundary\" attachedToRef=\"charge\">\n"
            + "      <escalationEventDefinition escalationRef=\"overdue\"/>\n"
            + "    </boundaryEvent>\n"
            + "    <userTask id=\"director\" name=\"总审批\" zifang:assignee=\"director\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <endEvent id=\"e2\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"charge\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"charge\" targetRef=\"e\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"escBoundary\" targetRef=\"director\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"director\" targetRef=\"e2\"/>\n"
            + "  </process>\n"
            + NS_END;

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfExternalTaskService external;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
        external = new WfExternalTaskService(repo, runtime);
    }

    private String start(String xml) {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(xml));
        return runtime.startProcessInstance(definition,
                "biz-" + System.nanoTime(), "alice", null, new HashMap<String, Object>());
    }

    private WfJob theJob(String instanceId) {
        List<WfJob> jobs = repo.queryJobs(new WfJobQuery()
                .setProcessInstanceId(instanceId).setPageNum(1).setPageSize(20));
        assertEquals(1, jobs.size(),
                "前置条件：应当恰好一个 job。实际 " + jobs.size() + " 个: " + jobs);
        return jobs.get(0);
    }

    /** 领 → 拿到锁，返回 job id。锁不在 worker 手上时后续调用一律报错。 */
    private String claim(String topic, String worker) {
        List<com.zifang.z.wf.core.view.WfExternalTaskView> got =
                external.fetchAndLock(topic, worker, 20);
        assertFalse(got.isEmpty(), "前置条件：应当能领到外部任务");
        return got.get(0).getId();
    }

    /**
     * 该实例当前所有 job 的 topic。
     *
     * <p>用 {@code String.valueOf} 而不是只收非空的：内部 job（定时器、边界订阅）
     * 的 topic 是 null，把它们静默滤掉的话，"报错之后还剩一只定时器"这类问题就看不见了。
     * 显示成 {@code "null"} 正是我们要的 —— 它让清单不会因为过滤而失真。
     */
    private List<String> topicsOf(String instanceId) {
        List<WfJob> jobs = repo.queryJobs(new WfJobQuery()
                .setProcessInstanceId(instanceId).setPageNum(1).setPageSize(50));
        List<String> topics = new ArrayList<String>();
        for (WfJob job : jobs) {
            topics.add(String.valueOf(job.getTopic()));
        }
        return topics;
    }

    // ==================== handleBpmnError ====================

    @Test
    @DisplayName("外部任务报 BPMN 错误 ⇒ token 沿错误边界走到补救分支，job 被消费掉")
    void externalBpmnErrorRoutesToErrorBoundary() {
        String pid = start(ERROR_BOUNDARY_BPMN);
        String jobId = claim("charge", "w1");

        external.handleBpmnError(jobId, "w1", "PAYMENT_FAILED", "扣款接口返回 502", null);

        assertNull(repo.findJob(jobId),
                "**job 必须被消费掉** —— 外部动作已经给出了确定结果（失败+错误码），"
                        + "留着它会被下一轮领取重新做一遍");

        List<String> topics = topicsOf(pid);
        assertTrue(topics.contains("refund"),
                "**走了补救分支**才是这条路的核心目的。剩下的 job: " + topics);
        assertFalse(topics.contains("charge"),
                "原来的扣款 job 必须消失 —— 它是「还没做」时的表，"
                        + "错误交回之后它就是「做过了且失败了」。剩下的 job: " + topics);
    }

    @Test
    @DisplayName("没有匹配的边界事件 ⇒ 内部终止并记下错误码，不静默继续")
    void uncaughtExternalErrorTerminatesInstance() {
        String pid = start(NO_BOUNDARY_BPMN);
        String jobId = claim("charge", "w1");

        external.handleBpmnError(jobId, "w1", "PAYMENT_FAILED", "扣款接口返回 502", null);

        WfProcessInstance instance = repo.findProcessInstance(pid);
        assertEquals(WfProcessStatus.INTERNALLY_TERMINATED, instance.getStatus(),
                "**静默继续等于「这一步没做但看起来做了」**。实际: " + instance.getStatus());
        assertTrue(String.valueOf(instance.getDeleteReason()).contains("PAYMENT_FAILED"),
                "错误码要留在 deleteReason 上。实际: " + instance.getDeleteReason());
        assertNull(repo.findJob(jobId), "job 同样要被消费掉");
    }

    @Test
    @DisplayName("错误码为空直接报错 —— 没有码就没有边界事件能捕获它")
    void emptyErrorCodeIsRejected() {
        start(NO_BOUNDARY_BPMN);
        String jobId = claim("charge", "w1");
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> external.handleBpmnError(jobId, "w1", "  ", "忘了填码", null));
        assertTrue(ex.getMessage().contains("errorCode"),
                "报错要点名是哪个参数。实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("锁不在自己手上 ⇒ 报错，且什么都不改")
    void wrongWorkerCannotReportError() {
        String pid = start(ERROR_BOUNDARY_BPMN);
        String jobId = claim("charge", "w1");

        assertThrows(WfEngineException.class,
                () -> external.handleBpmnError(jobId, "w2", "PAYMENT_FAILED", "我抢的", null),
                "别人领的活不能替他报错 —— 报错是要把流程推进补偿分支的，那不是误操作");
        assertNotNull(repo.findJob(jobId), "失败的调用必须一点痕迹都不留");
        assertEquals(WfProcessStatus.ACTIVE, repo.findProcessInstance(pid).getStatus(),
                "流程状态不该被一次失败的调用改动");
        assertEquals(1, topicsOf(pid).size(),
                "也不能凭空多出一只 job。实际: " + topicsOf(pid));
        assertTrue(topicsOf(pid).contains("charge"),
                "领到的那只还在原地等着 w1 来报错。实际: " + topicsOf(pid));
    }

    @Test
    @DisplayName("非外部任务的 job 不能走这条路")
    void nonExternalJobIsRejected() {
        String pid = start(ERROR_BOUNDARY_BPMN);
        WfJob job = theJob(pid);
        job.setType(WfJobType.TIMER);
        job.setLockedBy("w1");
        job.nextRevision();
        repo.saveJob(job);

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> external.handleBpmnError(job.getId(), "w1", "PAYMENT_FAILED", "x", null));
        assertTrue(ex.getMessage().contains("外部任务"),
                "报错要说明这条 job 是什么。实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("失败那次也留下历史故障记录 —— 外部动作失败是排障时最常问的事之一")
    void reportingAnErrorAlsoRecordsIt() {
        String pid = start(ERROR_BOUNDARY_BPMN);
        String jobId = claim("charge", "w1");

        external.handleBpmnError(jobId, "w1", "PAYMENT_FAILED", "扣款接口返回 502", null);

        assertEquals(1, new WfHistoricIncidentService(repo).incidentsOf(pid).size(),
                "job 删了之后就没人知道它坏过 —— 历史故障记录正是为此存在（第 40 轮）");
        assertEquals(1, new WfHistoricIncidentService(repo).findByJobId(jobId).getFailureCount());
    }

    // ==================== handleEscalation：经核实不是缺口 ====================

    @Test
    @DisplayName("**经核实**：外部任务上的升级边界已被 escalate() 覆盖，不必再造 handleEscalation")
    void externalEscalationIsAlreadyCoveredByEscalate() {
        // 台账上 `handleEscalation` 一直挂着 ❌ 且备注为空（从没被调查过）。
        // 这次的结论是：**它不是缺口**。
        //
        // 理由：升级在本引擎里是**订阅型**的 —— WfContext#createBoundaryJobs
        // 给每个边界建 job，升级边界建的是 ESCALATION 型订阅，
        // 而那个方法**不看宿主节点的类型**（userTask / serviceTask / subProcess 一视同仁）。
        // 于是外部 serviceTask 上的升级边界照样订阅了，
        // 而 escalate(code) 是**按码广播**给所有订阅者，本来就不针对某一个任务。
        //
        // 真正该问的差别只有一个：Camunda 的 handleEscalation 是「针对 taskId 这一条」，
        // 本引擎的 escalate 是「按码广播」。对 worker 而言这不是缺陷 ——
        // 它手里没有 taskId（外部任务在引擎里没有 WfTask），只有 job，
        // 而"我要报一条 overdue 升级"这句话本身就是按码说的。
        // ⇒ 再造一个 handleEscalation 只会给同一件事第二个入口。
        String pid = start(ESCALATION_BOUNDARY_BPMN);

        List<WfJob> subs = repo.queryJobs(new WfJobQuery()
                .setProcessInstanceId(pid).setType(WfJobType.ESCALATION)
                .setPageNum(1).setPageSize(20));
        assertEquals(1, subs.size(),
                "前置条件：外部 serviceTask 上的升级边界**确实建了订阅**。实际: " + subs);
        assertEquals("overdue", subs.get(0).getSubscriptionName(),
                "订阅名就是升级码 —— escalate() 靠它匹配");

        List<WfProcessInstance> escalated = runtime.escalate("overdue", "ops", "扣款卡住了");
        assertEquals(1, escalated.size(), "按码广播应当打到这条。实际: " + escalated.size());

        // token 应当已经被搬到边界上，沿着出线派给 director
        WfProcessInstance instance = repo.findProcessInstance(pid);
        List<WfExecution> tokens = repo.findExecutionsByProcessInstance(pid);
        List<String> places = new java.util.ArrayList<>();
        for (WfExecution token : tokens) {
            places.add(String.valueOf(token.getActivityId()));
        }
        assertFalse(places.contains("charge"),
                "**升级必须打断宿主** —— token 还停在扣款节点上说明它没被升级。实际 token 位置: " + places);
        assertEquals(1, repo.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(pid).setAssignee("director")
                .setOpenOnly(true).setPageNum(1).setPageSize(10)).size(),
                "沿升级出线应当给 director 派了待办");
        assertEquals(WfProcessStatus.ACTIVE, instance.getStatus());
    }
}