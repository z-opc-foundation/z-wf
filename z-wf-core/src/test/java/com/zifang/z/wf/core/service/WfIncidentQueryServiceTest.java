package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfIncidentQuery;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.view.WfIncidentView;

/**
 * 运行期故障查询 —— "哪些事情没干成，而且正在为此付出代价"。
 *
 * <p>本类盯六件错了都不报错的事：
 * <ol>
 *   <li><b>失败过的 job 必须查得到</b>：它躺在 {@code ZWF_JOB} 表里，重试耗尽后
 *       也不会自动清理。此前没有任何接口能列出它们，于是"单子不动了"分不清
 *       是在耐心等还是已经炸了。</li>
 *   <li><b>订阅名与失败原因要能同时看到</b>：两者原先挤在同一列，只能显示其中一个。
 *       而"它在等 X，却没等到，报 Y"这句话只有两个值分开才说得出来。</li>
 *   <li><b>从没失败过的 job 不算故障</b>：判据是失败过（{@code lastFailureTime} 有值），
 *       不是"retries 少了" —— 后者会把"创建时就配置成不重试"的 job 也算进来。</li>
 *   <li><b>"还在重试"与"彻底不动了"要分得开</b>：前者可以等，后者必须人去看。</li>
 *   <li><b>流程实例已消失的残留 job 仍要报出来</b>：它是清理漏了一步的证据，
 *       藏起来就永远没人发现。</li>
 *   <li><b>超量要报错而不是静默截断</b>：一份"看起来完整"的故障列表，
 *       比报错危险得多。</li>
 * </ol>
 */
class WfIncidentQueryServiceTest {

    /** 事件网关 + 消息边界 + 外部任务，用来造出几种不同类型的 job。 */
    private static final String MIXED_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"incProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <eventBasedGateway id=\"eg\"/>\n"
            + "    <intermediateCatchEvent id=\"waitMsg\" name=\"等主管批\">\n"
            + "      <messageEventDefinition messageRef=\"bossApprove\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <intermediateCatchEvent id=\"waitSignal\" name=\"等回执\">\n"
            + "      <signalEventDefinition signalRef=\"erpDone\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <userTask id=\"host\" name=\"在办\" zifang:assignee=\"ops\"/>\n"
            + "    <serviceTask id=\"notify\" name=\"通知下游\" zifang:topic=\"erp.push\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"eg\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"eg\" targetRef=\"waitMsg\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"eg\" targetRef=\"waitSignal\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"waitMsg\" targetRef=\"host\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"waitSignal\" targetRef=\"host\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"host\" targetRef=\"notify\"/>\n"
            + "    <sequenceFlow id=\"f7\" sourceRef=\"notify\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfIncidentService incidents;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
        incidents = new WfIncidentService(repo, repository);
    }

    private String start() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(MIXED_BPMN));
        return runtime.startProcessInstance(definition, "INC-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
    }

    /** 找出某实例下某个类型的 job —— 直接查存储层，绕开被测服务。 */
    private WfJob jobOf(String pid, WfJobType type) {
        List<WfJob> jobs = repo.queryJobs(new WfJobQuery().setType(type)
                .setProcessInstanceId(pid).setPageNum(1).setPageSize(20));
        return jobs == null || jobs.isEmpty() ? null : jobs.get(0);
    }

    /**
     * 把流程推进到外部任务节点，造出一条 EXTERNAL job。
     *
     * <p>单独一个实例跑：事件网关的分支一旦被唤醒，输的那条订阅连同 job 一起没了，
     * 于是"订阅型故障"与"外部任务型故障"没法在同一个实例上共存 ——
     * 那是 BPMN 的真实形态，不是测试的将就。
     */
    private void advanceToExternal(String pid) {
        runtime.triggerMessage("bossApprove", pid, "boss", null, "批了");
        WfTask task = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(10)).get(0);
        runtime.completeTask(task.getId(), "ops", "同意", null);
    }

    /**
     * 让一个 job 失败并存回去。
     *
     * <p>{@code nextRevision()} 不能省：{@code saveJob} 的乐观锁契约是
     * 「调用方先自增，使传入对象的 revision 恰好是库里那份的 +1」。
     * 漏了它得到的是乐观锁冲突 —— 这正是那条契约在工作，不是它坏了。
     */
    private WfJob fail(WfJob job, String message) {
        job.recordFailure(message);
        job.nextRevision();
        repo.saveJob(job);
        return job;
    }

    private WfIncidentView byId(List<WfIncidentView> views, String id) {
        for (WfIncidentView view : views) {
            if (id.equals(view.getId())) {
                return view;
            }
        }
        return null;
    }

    // ==================== 基本可见性 ====================

    @Test
    @DisplayName("什么都没出错的流程查不到故障 —— 否则这个端点等于永远有东西")
    void healthyInstanceHasNoIncident() {
        String pid = start();
        assertEquals(0, incidents.incidentsOf(pid).size(),
                "刚起来、只在正常等待的流程不该报故障");
        assertEquals(0, incidents.countIncidents(null), "全库也不该有故障");
    }

    @Test
    @DisplayName("失败过的 job 查得到：带错误信息、失败时间与剩余重试")
    void failedJobIsVisible() {
        String pid = start();
        WfJob job = jobOf(pid, WfJobType.EVENT_MESSAGE);
        assertNotNull(job, "前置条件：事件网关应当起出一条消息订阅");
        fail(job, "IllegalStateException: 下游超时");

        List<WfIncidentView> views = incidents.incidentsOf(pid);
        assertEquals(1, views.size(), "一条订阅失败就是一条故障。实际 " + views.size());

        WfIncidentView view = byId(views, job.getId());
        assertNotNull(view, "故障列表里必须能按 job id 找到它");
        assertTrue(view.getErrorMessage().contains("下游超时"),
                "要能看到为什么失败。实际 " + view.getErrorMessage());
        assertEquals("IllegalStateException", view.getErrorType(),
                "异常类名要能单独取出来，否则没法按类型归类。实际 " + view.getErrorType());
        assertNotNull(view.getLastFailureTime());
        assertNotNull(view.getFailedMillis(), "要能一眼看出失败多久了");
        assertEquals("incProcess", view.getDefinitionKey());
        assertEquals("EVENT_MESSAGE", view.getJobType());
        // 重试还没扣完 —— recordFailure 一次，只剩 2 次
        assertTrue(view.isRetryable(), "还能重试就不是彻底不动");
    }

    // ==================== 两列并存（拆列的核心判据） ====================

    @Test
    @DisplayName("订阅名与失败原因同时可见 —— 一个都不能被另一个顶掉")
    void subscriptionNameAndErrorCoexist() {
        String pid = start();
        WfJob job = jobOf(pid, WfJobType.EVENT_MESSAGE);
        assertNotNull(job);
        assertEquals("bossApprove", job.getSubscriptionName(), "前置条件：订阅名确实记着");

        fail(job, "IllegalStateException: 炸了");

        WfJob reloaded = repo.findJob(job.getId());
        assertEquals("bossApprove", reloaded.getSubscriptionName(),
                "失败一次不能把订阅名顶掉 —— 它挤在 exceptionMessage 里时正是这么发生的，"
                        + "结果是那条订阅再也匹配不到事件、从此从引擎里消失且不报错");
        assertTrue(reloaded.getExceptionMessage().contains("炸了"),
                "失败原因要落在自己的位置上");

        WfIncidentView view = byId(incidents.incidentsOf(pid), job.getId());
        assertNotNull(view);
        assertEquals("bossApprove", view.getSubscriptionName(),
                "故障视图要能说出「它在等什么」");
        assertTrue(view.getErrorMessage().contains("炸了"),
                "也要能说出「它报了什么错」。两个值必须都在 —— 挤在一列就只能显示一个");
    }

    @Test
    @DisplayName("失败之后那条订阅仍能被事件正常唤醒 —— 名字没丢就还认得")
    void subscriptionStillFiresAfterFailure() {
        String pid = start();
        WfJob job = jobOf(pid, WfJobType.EVENT_MESSAGE);
        assertNotNull(job);

        // 订阅型 job 失败一次（引擎当前不对订阅型调 recordFailure，但这条路径
        // 一旦被接上就会走到，所以必须验得出来）
        fail(job, "IllegalStateException: 第一次没干成");

        runtime.triggerMessage("bossApprove", pid, "boss", null, "批了");
        WfIncidentView view = byId(incidents.incidentsOf(pid), job.getId());
        assertNull(view, "订阅被唤醒后 job 就没了，不该还留在故障列表里。实际 " + view);
    }

    // ==================== 判据 ====================

    @Test
    @DisplayName("从没失败过的 job 不算故障 —— 即使它的 retries 一开始就是 0")
    void neverFailedIsNotIncident() {
        String pid = start();
        WfJob job = jobOf(pid, WfJobType.EVENT_MESSAGE);
        assertNotNull(job);
        // 配置成"不重试"，但它一次都没失败过
        job.setRetries(0);
        job.nextRevision();
        repo.saveJob(job);

        assertEquals(0, incidents.incidentsOf(pid).size(),
                "retries 少不等于失败过。按 retries 判会把「创建时就配置成不重试」的"
                        + "job 全算成故障，而这个端点于是永远有东西、没人再看它");
    }

    @Test
    @DisplayName("还在重试与彻底不动要分得开")
    void retryableAndExhaustedAreDistinguishable() {
        String pid = start();
        WfJob job = jobOf(pid, WfJobType.EVENT_MESSAGE);
        assertNotNull(job);
        fail(job, "IllegalStateException: 第一次");

        assertEquals(1, incidents.countIncidents(new WfIncidentQuery().setRetriesExhausted(false)),
                "还能重试的应当归到 false 那一侧");
        assertEquals(0, incidents.countIncidents(new WfIncidentQuery().setRetriesExhausted(true)),
                "还没扣完，不该出现在已耗尽里");

        // 扣到耗尽
        WfJob again = repo.findJob(job.getId());
        while (!again.isRetriesExhausted()) {
            fail(again, "IllegalStateException: 又失败一次");
            again = repo.findJob(job.getId());
        }

        assertEquals(1, incidents.countIncidents(new WfIncidentQuery().setRetriesExhausted(true)),
                "扣完了就该出现在已耗尽里 —— 这一侧才是必须人去看的那批");
        assertEquals(0, incidents.countIncidents(new WfIncidentQuery().setRetriesExhausted(false)));
    }

    @Test
    @DisplayName("按类型筛：定时器的故障与外部任务的故障不能混在一起")
    void filtersByJobType() {
        String pid = start();
        WfJob subscription = jobOf(pid, WfJobType.EVENT_MESSAGE);
        assertNotNull(subscription);
        fail(subscription, "IllegalStateException: 订阅炸了");

        String other = start();
        advanceToExternal(other);
        WfJob external = jobOf(other, WfJobType.EXTERNAL);
        assertNotNull(external, "前置条件：应当有一条外部任务");
        fail(external, "IllegalStateException: 通知失败");

        assertEquals(1, incidents.countIncidents(
                new WfIncidentQuery().addType(WfJobType.EXTERNAL)),
                "只筛外部任务时不该把订阅的故障算进来 —— 两者的处置人根本不是同一个");
        assertEquals(1, incidents.countIncidents(
                new WfIncidentQuery().addType(WfJobType.EVENT_MESSAGE)));
        assertEquals(2, incidents.countIncidents(null));
    }

    @Test
    @DisplayName("按错误信息子串筛：用于按下游系统归类故障")
    void filtersByErrorMessageSubstring() {
        WfJob sub = jobOf(start(), WfJobType.EVENT_MESSAGE);
        assertNotNull(sub);
        fail(sub, "IllegalStateException: 审批服务不可用");

        String other = start();
        advanceToExternal(other);
        WfJob erp = jobOf(other, WfJobType.EXTERNAL);
        assertNotNull(erp);
        fail(erp, "SocketTimeoutException: ERP 超时");

        assertEquals(1, incidents.countIncidents(
                new WfIncidentQuery().setErrorMessageContains("ERP")),
                "只该命中 ERP 那条。子串而非等值：recordFailure 存的是「类名: 消息」，"
                        + "等值匹配几乎永远匹配不上");
    }

    // ==================== 异常数据不能静默丢 ====================

    @Test
    @DisplayName("流程实例已消失的残留 job 仍要报出来 —— 那是清理漏了一步的证据")
    void orphanJobIsStillReported() {
        String pid = start();
        WfJob job = jobOf(pid, WfJobType.EVENT_MESSAGE);
        assertNotNull(job);
        fail(job, "IllegalStateException: 炸了");

        // 造一条实例已不存在的 job：清理顺序不一致（先删实例后删 job，
        // 且中途崩溃）时真会留下这种行
        WfJob orphan = new WfJob();
        orphan.setId("orphan-1");
        orphan.setProcessInstanceId("ghost-pid");
        orphan.setElementId("waitMsg");
        orphan.setType(WfJobType.EVENT_MESSAGE);
        orphan.setSubscriptionName("bossApprove");
        fail(orphan, "IllegalStateException: 实例没了还炸了");

        List<WfIncidentView> views = incidents.incidentsOf("ghost-pid");
        assertEquals(1, views.size(),
                "没有实例可查也要报出来。藏起来的话「job 表在涨」这件事就永远没人发现");
        assertEquals("waitMsg", views.get(0).getActivityId());
        assertNull(views.get(0).getDefinitionKey(), "实例没了，定义自然取不到");
        assertTrue(views.get(0).getErrorMessage().contains("炸了"), "失败原因仍要能看到");
    }

    // ==================== 拒绝 ====================

    @Test
    @DisplayName("超量报错而不是静默截断")
    void overflowIsRejected() {
        assertEquals(5000, WfIncidentService.MAX_SCAN, "判据要用读到的原始行数");

        String pid = start();
        for (int i = 0; i < WfIncidentService.MAX_SCAN + 5; i++) {
            WfJob job = new WfJob();
            job.setId("flood-" + i);
            job.setProcessInstanceId(pid);
            job.setElementId("waitMsg");
            job.setType(WfJobType.EVENT_MESSAGE);
            job.setSubscriptionName("bossApprove");
            fail(job, "IllegalStateException: 批量失败 " + i);
        }
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> incidents.countIncidents(new WfIncidentQuery()));
        assertTrue(ex.getMessage().contains(String.valueOf(WfIncidentService.MAX_SCAN)),
                "报错要说出截断到了多少。实际 " + ex.getMessage());
        assertTrue(ex.getMessage().contains("缩小范围"),
                "报错要告诉调用方下一步怎么办。实际 " + ex.getMessage());

        // 报错不能是死路：清掉超量的之后必须能正常查到结果
        for (int i = 0; i < WfIncidentService.MAX_SCAN + 5; i++) {
            repo.deleteJob("flood-" + i);
        }
        assertEquals(0, incidents.countIncidents(null),
                "清掉超量的 job 之后必须能正常查，否则报错就是死路");
    }

    @Test
    @DisplayName("空查询参数按不筛处理，返回空列表而不是 null")
    void emptyQueryReturnsEmptyList() {
        String pid = start();
        List<WfIncidentView> views = incidents.incidentsOf(pid);
        assertNotNull(views, "没有匹配时给空列表；给 null 会让每个调用方都要判一次空");
        assertTrue(views.isEmpty());
        assertFalse(incidents.incidentsOf("no-such-instance").iterator().hasNext());
    }
}