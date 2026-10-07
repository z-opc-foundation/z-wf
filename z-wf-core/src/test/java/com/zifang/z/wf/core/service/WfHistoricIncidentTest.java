package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.engine.WfIdGenerator;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfHistoricIncident;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfHistoricIncidentQuery;
import com.zifang.z.wf.core.persistence.WfIncidentQuery;
import com.zifang.z.wf.core.view.WfExternalTaskView;
import com.zifang.z.wf.core.view.WfIncidentView;

/**
 * 历史故障的行为约定（第 40 轮）。
 *
 * <p>它存在的理由只有一句：<b>job 好了之后，「它曾经坏过」这件事还得查得到</b>。
 * 所以用例盯的是「好消息之后记录还在」，而不只是「失败之后有记录」。
 *
 * <p>另外三条是本轮特意分开的东西，混起来就会出「看起来能用、其实答的是别的问题」：
 * <ul>
 *   <li><b>两处失败写入点</b>（job 执行器 / 外部任务 fail）都得记 —— 漏一处，
 *       那一类失败在历史里彻底消失，且没有任何报错；</li>
 *   <li><b>两套数据来源</b>（当前故障推导自 job / 历史故障持久化）不能合并 ——
 *       合并会造出「已经恢复的故障还在当前列表里」；</li>
 *   <li><b>冗余字段</b>（definitionKey / activityName）必须在实例没了之后仍然在。</li>
 * </ul>
 */
class WfHistoricIncidentTest {

    /** 带一个 serviceTask（外部任务）的流程 —— 外部任务失败是本轮的第二处写入点。 */
    private static final String ORDER_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"orderProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <serviceTask id=\"notify\" name=\"通知下游\""
            + " zifang:type=\"external\" zifang:topic=\"order.create\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"notify\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"notify\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 异步前置 + 会抛的 delegate —— 让 job 执行器真的失败一次。 */
    private static final String ASYNC_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"asyncProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"as\"/>\n"
            + "    <serviceTask id=\"asyncStep\" name=\"异步步骤\""
            + " zifang:asyncBefore=\"true\" zifang:delegateExpression=\"boom\"/>\n"
            + "    <endEvent id=\"ae\"/>\n"
            + "    <sequenceFlow id=\"af1\" sourceRef=\"as\" targetRef=\"asyncStep\"/>\n"
            + "    <sequenceFlow id=\"af2\" sourceRef=\"asyncStep\" targetRef=\"ae\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 带定时器边界的 userTask —— 边界的 elementId 指事件本身，宿主是 approve。 */
    private static final String TIMER_BOUNDARY_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"timeoutProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"ts\"/>\n"
            + "    <userTask id=\"approve\" name=\"主管审批\" zifang:assignee=\"boss\"/>\n"
            + "    <boundaryEvent id=\"timeout\" attachedToRef=\"approve\">\n"
            + "      <timerEventDefinition><timeDuration xsi:type=\"tFormalExpression\""
            + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">PT10M</timeDuration>"
            + "</timerEventDefinition>\n"
            + "    </boundaryEvent>\n"
            + "    <userTask id=\"remind\" name=\"超时提醒\" zifang:assignee=\"ops\"/>\n"
            + "    <endEvent id=\"te1\"/>\n"
            + "    <endEvent id=\"te2\"/>\n"
            + "    <sequenceFlow id=\"tf1\" sourceRef=\"ts\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"tf2\" sourceRef=\"approve\" targetRef=\"te1\"/>\n"
            + "    <sequenceFlow id=\"tf3\" sourceRef=\"timeout\" targetRef=\"remind\"/>\n"
            + "    <sequenceFlow id=\"tf4\" sourceRef=\"remind\" targetRef=\"te2\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfExternalTaskService external;
    private WfHistoricIncidentService history;
    private WfIncidentService incidents;
    private WfDefinition definition;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
        external = new WfExternalTaskService(repo, runtime);
        history = new WfHistoricIncidentService(repo);
        incidents = new WfIncidentService(repo, repository);
        definition = repository.deploy(new WfXmlParser().parse(ORDER_BPMN));
    }

    private String startOne() {
        return runtime.startProcessInstance(definition,
                "order-" + System.nanoTime(), "alice", null, new HashMap<String, Object>());
    }

    private WfJob theJob(String instanceId) {
        List<WfJob> jobs = repo.queryJobs(
                new com.zifang.z.wf.core.persistence.WfJobQuery()
                        .setProcessInstanceId(instanceId).setPageNum(1).setPageSize(10));
        assertEquals(1, jobs.size(), "前置条件：应当恰好有一个 job，实际 " + jobs.size());
        return jobs.get(0);
    }

    /**
     * 领 → fail 的完整闭环，不先领就 fail 不了（锁不在这个 worker 手上）。
     *
     * <p><b>按 processInstanceId 挑 job，而不是拿领到的第一个</b>：
     * {@code fail} 的默认退避是 {@code 0ms}（{@code DEFAULT_RETRY_DELAY_MILLIS}），
     * 也就是失败之后立刻又能领走。于是「循环三次 fail」里的三次
     * 会全部落在<b>同一个</b> job 上 ——
     * 实测过一次：三个 job 都在库里，却只留下一条故障记录，
     * 而现象是「断言说该有 3 条、实际 1 条」，看不出是夹具挑错了对象。
     */
    private void failOnce(String instanceId, String worker, String reason) {
        List<WfExternalTaskView> got = external.fetchAndLock("order.create", worker, 50);
        WfExternalTaskView target = null;
        for (WfExternalTaskView view : got) {
            if (instanceId.equals(view.getProcessInstanceId())) {
                target = view;
                break;
            }
        }
        assertNotNull(target,
                "前置条件：应当能领到属于 " + instanceId + " 的外部任务。实际领到: " + got);
        external.fail(target.getId(), worker, reason);
    }

    private WfHistoricIncident onlyIncident(String instanceId) {
        List<WfHistoricIncident> rows = history.incidentsOf(instanceId);
        assertEquals(1, rows.size(), "应当恰好一条历史故障记录，实际 " + rows.size());
        return rows.get(0);
    }

    // ==================== 记下来了 ====================

    @Test
    @DisplayName("外部任务失败会留下一条历史故障")
    void externalTaskFailureIsRecorded() {
        String pid = startOne();
        failOnce(pid, "w1", "下游 503");

        WfHistoricIncident incident = onlyIncident(pid);
        assertEquals(WfJobType.EXTERNAL.name(), incident.getJobType(),
                "job 类型必须记下来 —— 按类型复盘是最常见的用法");
        assertEquals(1, incident.getFailureCount(), "第一次失败计 1");
        assertNotNull(incident.getLastFailureTime(), "失败时刻必须记下来");
        assertNotNull(incident.getFirstFailureTime(), "首次失败时刻必须记下来");
        assertTrue(incident.getErrorMessage().contains("下游 503"),
                "失败原因必须原样留下。实际: " + incident.getErrorMessage());
        assertEquals(pid, incident.getProcessInstanceId());
        assertEquals("orderProcess", incident.getDefinitionKey(),
                "定义的 key 要冗余存下来 —— 它是「谁的流程」这个问题的唯一答案");
        assertEquals("通知下游", incident.getActivityName(),
                "节点名也要冗余存 —— 按名字搜流程图是排障的日常");
    }

    @Test
    @DisplayName("反复失败累加在同一行上，不是追加多行")
    void repeatedFailuresAccumulateOnOneRow() {
        String pid = startOne();
        failOnce(pid, "w1", "第一次失败");
        WfHistoricIncident first = onlyIncident(pid);
        Date firstFailureAt = first.getFirstFailureTime();

        failOnce(pid, "w1", "第二次失败");
        failOnce(pid, "w1", "第三次失败");

        List<WfHistoricIncident> rows = history.incidentsOf(pid);
        assertEquals(1, rows.size(),
                "**一个 job 永远只有一行** —— 拆成三行之后「它一共失败了几次」"
                        + "就变成了一个必须自己 group by 才能回答的问题");
        WfHistoricIncident incident = rows.get(0);
        assertEquals(3, incident.getFailureCount(),
                "失败次数要累加。实际: " + incident.getFailureCount());
        assertEquals(firstFailureAt, incident.getFirstFailureTime(),
                "首次失败时刻**不能**跟着刷新 —— 它答的是「从什么时候开始卡的」");
        assertTrue(incident.getErrorMessage().contains("第三次失败"),
                "错误消息应当是最近那一次。实际: " + incident.getErrorMessage());
    }

    // ==================== 本轮最要紧的一条 ====================

    @Test
    @DisplayName("job 好了之后记录仍在 —— 这正是补这一套的理由")
    void recordSurvivesAfterTheJobIsFixed() {
        String pid = startOne();
        failOnce(pid, "w1", "下游 503");
        WfHistoricIncident before = onlyIncident(pid);
        String jobId = before.getJobId();

        // 把活干完：外部任务交差之后 job 被删掉
        List<WfExternalTaskView> got = external.fetchAndLock("order.create", "w2", 10);
        external.complete(got.get(0).getId(), "w2", new HashMap<String, Object>());

        assertNull(repo.findJob(jobId), "前置条件：job 应当已经被执行掉了");
        assertTrue(incidents.listIncidents(new WfIncidentQuery()).isEmpty(),
                "前置条件：当前故障里已经查不到它了 —— 故障是从 job 现场推导的，job 没了就没了");

        List<WfHistoricIncident> after = history.incidentsOf(pid);
        assertEquals(1, after.size(),
                "**「上周三那批单为什么全卡住了」问的就是这一条**。当前故障查不到是正常的，"
                        + "历史故障必须还在");
        assertTrue(after.get(0).getErrorMessage().contains("下游 503"),
                "原因要还在。实际: " + after.get(0).getErrorMessage());
        assertFalse(history.isStillFailing(after.get(0)),
                "job 已经不在了 ⇒ 它现在不卡了。这个标志是推导的，不能当历史事实用");
    }

    @Test
    @DisplayName("两套来源各管各的：当前故障查得到但已恢复的，历史里也还在")
    void currentAndHistoricAreNotTheSameData() {
        String pid = startOne();
        failOnce(pid, "w1", "下游 503");

        // 两边都查得到 —— 现在确实还坏着
        assertEquals(1, incidents.listIncidents(new WfIncidentQuery()
                .setProcessInstanceId(pid)).size(), "当前故障应当查得到");
        assertEquals(1, history.incidentsOf(pid).size(), "历史故障也应当查得到");

        // **不能合并**：job 一修好，当前故障立刻空了，历史不动。
        // 若把两边并成「当前 ∪ 历史」，这个列表就会多出一条"还卡着"的假故障
        List<WfJob> jobs = repo.queryJobs(new com.zifang.z.wf.core.persistence.WfJobQuery()
                .setProcessInstanceId(pid).setPageNum(1).setPageSize(10));
        WfJob job = jobs.get(0);
        job.setExceptionMessage(null);
        job.setLastFailureTime(null);
        job.setRetries(3);
        job.nextRevision();
        repo.saveJob(job);

        assertEquals(0, incidents.listIncidents(new WfIncidentQuery()
                .setProcessInstanceId(pid)).size(), "失败痕迹清掉后当前故障应当为空");
        assertEquals(1, history.incidentsOf(pid).size(),
                "历史故障**不受影响** —— 它记的是发生过的事，不是现在的样子");
        assertFalse(history.isStillFailing(history.incidentsOf(pid).get(0)),
                "失败痕迹没了 ⇒ 现在不卡了");
    }

    // ==================== 第二处写入点 ====================

    @Test
    @DisplayName("job 执行器那条路径也记 —— 真的让执行器抛一次异常，不是直接调记录器")
    void jobExecutorFailurePathIsAlsoRecorded() {
        // 第一版这条用例是**假的**：它直接 new 一个记录器调 recordFailure，
        // 于是「把 WfJobService 里那行调用删掉」这条变异打不动它 ——
        // 变异跑完照样全绿，看起来像判据不敏感，其实是判压根没走那条路。
        //
        // 换成真的让执行器抛：把 job 的 elementId 指向一个不存在的节点，
        // executeAsyncJob 会抛「异步 job 指向的节点不存在」，走的是真·执行失败那条路。
        // （**不是**让 delegate 抛 —— 那条路引擎会把它转成**流程失败**，
        //   见下面 delegateFailureIsAProcessFailure 那条。）
        InMemoryWorkflowPersistence execRepo = new InMemoryWorkflowPersistence();
        WfRepositoryService execRepository = new WfRepositoryService(execRepo);
        WfRuntimeService execRuntime = new WfRuntimeService(execRepository, execRepo,
                new WfEngine(), new WfHookDispatcher());
        WfJobService execJobs = new WfJobService(execRepo, execRuntime);

        String pid = execRuntime.startProcessInstance(
                execRepository.deploy(new WfXmlParser().parse(ASYNC_BPMN)),
                "async-1", "alice", null, new HashMap<String, Object>());
        List<WfJob> pending = execRepo.queryJobs(
                new com.zifang.z.wf.core.persistence.WfJobQuery()
                        .setProcessInstanceId(pid).setPageNum(1).setPageSize(10));
        assertEquals(1, pending.size(), "前置条件：应当恰好有一条待执行的异步 job");

        WfJob job = pending.get(0);
        job.setElementId("no-such-node");
        job.setDuedate(new Date(System.currentTimeMillis() - 1000L));
        job.nextRevision();
        execRepo.saveJob(job);

        int executed = execJobs.executeAsyncJobs(new Date(System.currentTimeMillis() + 1000L));
        assertEquals(0, executed, "前置条件：这一轮不该执行成功");

        WfHistoricIncident incident = new WfHistoricIncidentService(execRepo)
                .findByJobId(job.getId());
        assertNotNull(incident,
                "**执行器这条路也必须留下历史记录**。两处写入点只改一处的话，"
                        + "那一类失败在历史里彻底消失，而且没有任何报错");
        assertEquals(1, incident.getFailureCount());
        assertTrue(incident.getErrorMessage().contains("节点不存在"),
                "失败原因要原样留下。实际: " + incident.getErrorMessage());
        assertEquals(WfJobType.ASYNC_BEFORE.name(), incident.getJobType());

        // job 侧也照常扣重试并留下痕迹 —— 历史故障是在这之后补记的，不替代它
        WfJob reloaded = execRepo.findJob(job.getId());
        assertNotNull(reloaded, "job 应当还在（这条路不是「跑一半 job 被删掉」的那种）");
        assertTrue(reloaded.getRetries() < WfJob.DEFAULT_RETRIES, "执行失败要扣重试次数");
    }

    @Test
    @DisplayName("delegate 抛异常算**流程失败**，不算 job 失败 —— 两件事不能混")
    void delegateFailureIsAProcessFailure() {
        // 这条是钉住一个**看起来像缺陷、其实是对的**的行为。
        // 第一版写这条用例时想用「delegate 抛异常」造 job 失败，
        // 结果探针打出来是：实例停在 INTERNALLY_TERMINATED、deleteReason 带着错、
        // job 被删掉、故障记录为 null。
        // 那是**正确**的：行为（activity）自己失败就是流程失败，
        // 与「job 基础设施出错」是两回事，Camunda 也是这么分的。
        // 写成 job 失败的后果是「实例还在跑，但凭空多一条 job 故障」，
        // 而真正该问的问题（这条流程为什么终止了）在实例的 deleteReason 上。
        InMemoryWorkflowPersistence execRepo = new InMemoryWorkflowPersistence();
        WfRepositoryService execRepository = new WfRepositoryService(execRepo);
        com.zifang.z.wf.core.service.WfDelegateRegistry delegates =
                new com.zifang.z.wf.core.service.WfDelegateRegistry();
        delegates.register("boom", (ctx, ex) -> {
            throw new IllegalStateException("行为自己炸了");
        });
        WfRuntimeService execRuntime = new WfRuntimeService(execRepository, execRepo,
                new WfEngine(new com.zifang.z.wf.core.engine.WfBehaviorRegistry(),
                        new com.zifang.z.wf.core.engine.expression.WfExpressionEvaluator(),
                        new WfIdGenerator.DefaultWfIdGenerator(), delegates),
                new WfHookDispatcher());

        String pid = execRuntime.startProcessInstance(
                execRepository.deploy(new WfXmlParser().parse(ASYNC_BPMN)),
                "async-2", "alice", null, new HashMap<String, Object>());
        new WfJobService(execRepo, execRuntime)
                .executeAsyncJobs(new Date(System.currentTimeMillis() + 1000L));

        WfProcessInstance instance = execRepo.findProcessInstance(pid);
        assertEquals(WfProcessStatus.INTERNALLY_TERMINATED, instance.getStatus(),
                "行为抛异常 ⇒ 流程终止。实际: " + instance.getStatus());
        assertTrue(String.valueOf(instance.getDeleteReason()).contains("行为自己炸了"),
                "失败原因要留在实例的 deleteReason 上 —— 这才是这个问题该去查的地方。"
                        + "实际: " + instance.getDeleteReason());
        assertEquals(0, new WfHistoricIncidentService(execRepo)
                        .countIncidents(new WfHistoricIncidentQuery()),
                "**行为失败不产生任何 job 故障记录** —— 它是流程失败，不是 job 失败。"
                        + "凭空记一条的后果是「实例明明在 deleteReason 上写着为什么终止，"
                        + "故障列表里却还有一条 job 故障」，两个答案互相打架");
        assertEquals(0, execRepo.queryJobs(new com.zifang.z.wf.core.persistence.WfJobQuery()
                .setPageNum(1).setPageSize(10)).size(), "job 已被续跑消费掉");
    }

    @Test
    @DisplayName("边界事件记的是**宿主节点**的名字，不是事件自己")
    void boundaryEventRecordsTheHostNodeName() {
        InMemoryWorkflowPersistence bRepo = new InMemoryWorkflowPersistence();
        WfRepositoryService bRepository = new WfRepositoryService(bRepo);
        WfRuntimeService bRuntime = new WfRuntimeService(bRepository, bRepo,
                new WfEngine(), new WfHookDispatcher());
        WfJobService bJobs = new WfJobService(bRepo, bRuntime);

        String pid = bRuntime.startProcessInstance(
                bRepository.deploy(new WfXmlParser().parse(TIMER_BOUNDARY_BPMN)),
                "timeout-1", "alice", null, new HashMap<String, Object>());
        List<WfJob> timerJobs = bRepo.queryJobs(
                new com.zifang.z.wf.core.persistence.WfJobQuery()
                        .setProcessInstanceId(pid).setPageNum(1).setPageSize(10));
        assertEquals(1, timerJobs.size(), "前置条件：应当恰好有一条定时器 job");
        WfJob timer = timerJobs.get(0);
        assertEquals("approve", timer.getAttachedToRef(),
                "前置条件：这条 job 是挂在 approve 上的边界事件");

        // 让它真的失败：把类型改成 EVENT_TIMER（竞速型），而它指向的是 boundaryEvent（打断型）——
        // checkTimerJobDispatch 会为这种对不上抛出来。
        // 顺带说明为什么**不能**用「把 elementId 指向不存在的节点」那招：
        // 那一处是**静默 return**（注释里明说留给下游自己认），
        // 于是 job 被正常消费掉，什么都不抛，也就什么都不记 ——
        // 症状是「用例 assertNotNull 挂了」，看不出是那招根本不产生异常。
        // 这个错法好在抛在 deleteJob **之前**，job 还在。
        timer.setType(WfJobType.EVENT_TIMER);
        timer.setDuedate(new Date(System.currentTimeMillis() - 1000L));
        timer.nextRevision();
        bRepo.saveJob(timer);
        bJobs.executeDueJobs(new Date(System.currentTimeMillis() + 1000L));

        WfHistoricIncident incident = new WfHistoricIncidentService(bRepo)
                .findByJobId(timer.getId());
        assertNotNull(incident, "边界事件的失败同样要留下历史记录");
        assertEquals("主管审批", incident.getActivityName(),
                "**人拿着节点名去搜流程图才搜得到**。事件自己叫「timeout」，"
                        + "拿它当名字在图上根本找不到");
    }

    @Test
    @DisplayName("错误类型只有一套解析：人写的句子不会被截成类型")
    void errorTypeParsingIsSharedAndConservative() {
        assertEquals("TimeoutException", WfJob.exceptionTypeOf("TimeoutException: 等待超时"),
                "像类名的就该认出来");
        assertNull(WfJob.exceptionTypeOf("连接超时: 连不上 db"),
                "**首字母不是大写的就不是类名** —— 否则按类型筛选会把不同问题混成一类，"
                        + "那比不筛更坏");
        assertNull(WfJob.exceptionTypeOf("下游 503"), "没有冒号就没有类型");
        assertNull(WfJob.exceptionTypeOf(": 前面是空的"), "冒号前为空不认");
        assertNull(WfJob.exceptionTypeOf(null));

        // 两处消费必须给出同一个答案
        String pid = startOne();
        failOnce(pid, "w1", "连接超时: 连不上 db");
        WfHistoricIncident incident = onlyIncident(pid);
        List<WfIncidentView> current = incidents.listIncidents(
                new WfIncidentQuery().setProcessInstanceId(pid));
        assertEquals(1, current.size());
        assertEquals(current.get(0).getErrorType(), incident.getErrorType(),
                "同一条 job，当前故障与历史故障对「类型」的说法必须一致。"
                        + "不一致的话「上周三那批是哪类问题」就有两个答案");
    }

    // ==================== 冗余的价值 ====================

    @Test
    @DisplayName("job 被删之后，定义 key 与节点名仍在记录上")
    void denormalizedFieldsSurviveJobDeletion() {
        String pid = startOne();
        failOnce(pid, "w1", "下游 503");
        WfHistoricIncident incident = onlyIncident(pid);
        String jobId = incident.getJobId();

        // job 是当前故障的推导来源；它没了，当前故障就没了。
        // 历史记录必须不受影响 —— 这是两个数据来源各管各的的直接后果
        repo.deleteJob(jobId);
        assertNull(repo.findJob(jobId), "前置条件：job 应当已经被删");

        WfHistoricIncident after = onlyIncident(pid);
        assertEquals("orderProcess", after.getDefinitionKey(),
                "**这正是冗余存它的理由**：job、实例、定义都会被清理，"
                        + "而「谁的流程、哪个环节」恰恰是这张表最该留住的东西");
        assertEquals("通知下游", after.getActivityName());
    }

    @Test
    @DisplayName("查不到实例时字段留空，不拿别的信息编一个填坑")
    void missingInstanceLeavesFieldsBlank() {
        // 造一个挂在不存在的实例上的 job —— 这是"清理漏了 steps"的现场，
        // WfIncidentService 对它会打一条告警，这里要断的是历史记录这一侧
        WfJob orphan = new WfJob();
        orphan.setId("orphan-job-1");
        orphan.setProcessInstanceId("no-such-instance");
        orphan.setElementId("notify");
        orphan.setType(WfJobType.TIMER);
        repo.saveJob(orphan);

        WfHistoricIncident incident = new WfHistoricIncidentService(repo)
                .recordFailure(orphan, "TimeoutException: 等不到人");
        assertEquals(1, incident.getFailureCount(), "失败本身要记下来");
        assertEquals("TimeoutException", incident.getErrorType());
        assertNull(incident.getDefinitionKey(),
                "**留空就是留空**。凭 job 的 elementId 去猜一个定义 key，"
                        + "会让记录声称自己属于一个从没存在过的流程");
        assertNull(incident.getActivityName(),
                "同理：查不到节点就没有名字，拿空字符串顶上会让「按名字搜」搜出假命中");
    }

    // ==================== 查询与清理 ====================

    @Test
    @DisplayName("按首次失败时间窗查 —— 「上周三那批」就是这么查的")
    void queryByTimeWindow() {
        String pid = startOne();
        failOnce(pid, "w1", "下游 503");
        WfHistoricIncident incident = onlyIncident(pid);
        Date when = incident.getFirstFailureTime();

        assertEquals(1, history.countIncidents(new WfHistoricIncidentQuery()
                .setFirstFailureFrom(new Date(when.getTime() - 1000))), "窗内应当查得到");
        assertEquals(0, history.countIncidents(new WfHistoricIncidentQuery()
                .setFirstFailureFrom(new Date(when.getTime() + 60000))), "窗外查不到");
        assertEquals(1, history.countIncidents(new WfHistoricIncidentQuery()
                .setProcessInstanceId(pid).setMinFailureCount(1)));
        assertEquals(0, history.countIncidents(new WfHistoricIncidentQuery()
                .setProcessInstanceId(pid).setMinFailureCount(99)),
                "只失败过 1 次的记录不该被「至少 99 次」命中");
        assertEquals(1, history.countIncidents(new WfHistoricIncidentQuery()
                .setProcessInstanceId(pid).setErrorMessageContains("503")),
                "按错误消息子串查");
    }

    @Test
    @DisplayName("清理按时间点走，且不并进 deleteHistoryBefore")
    void cleanupIsSeparateFromHistoryDeletion() {
        String pid = startOne();
        failOnce(pid, "w1", "下游 503");
        assertEquals(1, history.countIncidents(new WfHistoricIncidentQuery()));

        // 时间点为 null 一律拒绝 —— 那是"清掉全部"，几乎一定是误用
        assertThrows(WfEngineException.class, () -> history.deleteIncidentsBefore(null));

        // 往前推很久 ⇒ 什么都删不到
        assertEquals(0, history.deleteIncidentsBefore(new Date(System.currentTimeMillis() - 86400000)));
        assertEquals(1, history.countIncidents(new WfHistoricIncidentQuery()),
                "清理早于故障的记录不该动到它");

        // 推到未来 ⇒ 全删
        assertEquals(1, history.deleteIncidentsBefore(new Date(System.currentTimeMillis() + 86400000)));
        assertEquals(0, history.countIncidents(new WfHistoricIncidentQuery()));
    }

    @Test
    @DisplayName("列表与计数口径一致")
    void listAndCountAgree() {
        for (int i = 0; i < 3; i++) {
            failOnce(startOne(), "w1", "失败 " + i);
        }
        WfHistoricIncidentQuery query = new WfHistoricIncidentQuery().setJobType("EXTERNAL");
        assertEquals(3, history.countIncidents(query));
        assertEquals(3, history.listIncidents(query).size(),
                "total 与列表条数对不上时，分页器会以为还有第 2 页");
        assertEquals(3, history.countIncidents(query.setPageSize(2)),
                "计数**忽略分页** —— 它答的是「一共几条」，"
                        + "total 跟着 pageSize 变的话前端分页器会以为只有一页");
        assertEquals(2, history.listIncidents(query).size(), "分页真的生效");
    }
}