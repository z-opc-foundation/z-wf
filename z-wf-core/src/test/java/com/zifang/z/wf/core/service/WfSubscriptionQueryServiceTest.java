package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfSubscriptionQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.view.WfSubscriptionView;

/**
 * 订阅查询 —— "现在有哪些流程在等什么"。
 *
 * <p>本类盯五件错了都不报错的事：
 * <ol>
 *   <li><b>在等消息的流程必须查得到</b>：它没有待办、轨迹没动、没有任何报错 ——
 *       没有这张表，"这条单子怎么不动了"只能靠翻 XML 猜。</li>
 *   <li><b>job 类型要归并成"等什么"</b>：{@code MESSAGE}（边界打断）与
 *       {@code EVENT_MESSAGE}（网关竞速）都是等消息，但处置方式不同，
 *       归并后要还能看出原类型。</li>
 *   <li><b>竞速订阅要带出网关 id</b>：同一个网关下的几条是"同一次竞速"，
 *       看不到这个 id 就以为它们互不相干。</li>
 *   <li><b>过期与未过期、锁与未锁要分得开</b>：定时器"本该响了却没响"与
 *       外部任务"活没人领"是两种完全不同的故障。</li>
 *   <li><b>已终态流程的残留 job 不计入</b>：那是清理漏了一步，
 *       报出来而不是藏起来 —— 藏起来就没人发现 job 表在涨。</li>
 * </ol>
 */
class WfSubscriptionQueryServiceTest {

    /** 事件网关：等主管批 / 等 ERP 回执。 */
    private static final String RACE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"subRaceProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <eventBasedGateway id=\"eg\"/>\n"
            + "    <intermediateCatchEvent id=\"waitMsg\" name=\"等主管批\">\n"
            + "      <messageEventDefinition messageRef=\"bossApprove\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <intermediateCatchEvent id=\"waitSignal\" name=\"等回执\">\n"
            + "      <signalEventDefinition signalRef=\"erpDone\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <userTask id=\"onApprove\" name=\"批了\" zifang:assignee=\"ops\"/>\n"
            + "    <userTask id=\"onErp\" name=\"回执到了\" zifang:assignee=\"erp\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <endEvent id=\"e2\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"eg\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"eg\" targetRef=\"waitMsg\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"eg\" targetRef=\"waitSignal\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"waitMsg\" targetRef=\"onApprove\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"waitSignal\" targetRef=\"onErp\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"onApprove\" targetRef=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f7\" sourceRef=\"onErp\" targetRef=\"e2\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 带消息边界（打断）与外部任务（租约）的定义。 */
    private static final String MIXED_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"subMixedProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"ms1\"/>\n"
            + "    <userTask id=\"host\" name=\"在办\" zifang:assignee=\"ops\">\n"
            + "      <boundaryEvent id=\"beMsg\" name=\"撤销\" attachedToRef=\"host\">\n"
            + "        <messageEventDefinition messageRef=\"cancelIt\"/>\n"
            + "      </boundaryEvent>\n"
            + "    </userTask>\n"
            + "    <serviceTask id=\"notify\" name=\"通知下游\" zifang:topic=\"erp.push\"/>\n"
            + "    <endEvent id=\"me1\"/>\n"
            + "    <endEvent id=\"me2\"/>\n"
            + "    <sequenceFlow id=\"mf1\" sourceRef=\"ms1\" targetRef=\"host\"/>\n"
            + "    <sequenceFlow id=\"mf2\" sourceRef=\"host\" targetRef=\"me1\"/>\n"
            + "    <sequenceFlow id=\"mf3\" sourceRef=\"beMsg\" targetRef=\"notify\"/>\n"
            + "    <sequenceFlow id=\"mf4\" sourceRef=\"notify\" targetRef=\"me2\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfExternalTaskService externalTasks;
    private WfSubscriptionService subscriptions;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
        externalTasks = new WfExternalTaskService(repo, runtime);
        subscriptions = new WfSubscriptionService(repo, repository);
    }

    private String startRace() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(RACE_BPMN));
        return runtime.startProcessInstance(definition, "BIZ-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
    }

    private String startMixed() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(MIXED_BPMN));
        return runtime.startProcessInstance(definition, "BIZ-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
    }

    private WfSubscriptionView byId(List<WfSubscriptionView> views, String id) {
        for (WfSubscriptionView view : views) {
            if (id.equals(view.getActivityId())) {
                return view;
            }
        }
        return null;
    }

    // ==================== 基本可见性 ====================

    @Test
    @DisplayName("在等消息的流程查得到：没有待办，但等待状态必须看得见")
    void waitingOnMessageIsVisible() {
        String pid = startRace();
        List<WfSubscriptionView> views = subscriptions.subscriptionsOf(pid);
        assertEquals(2, views.size(), "两条分支各一条订阅。实际 " + views.size());

        WfSubscriptionView msg = byId(views, "waitMsg");
        assertNotNull(msg);
        assertEquals("message", msg.getWaitingFor(), "等的是消息");
        assertEquals("bossApprove", msg.getEventName());
        assertEquals("等主管批", msg.getActivityName(), "节点名要能对上 XML，排障时全靠它");
        assertEquals("subRaceProcess", msg.getDefinitionKey());
        assertNotNull(msg.getDefinitionName(),
                "流程名要带上：排障时只看到 key 得再去翻哪个模板，对不上 XML 就是白查");
        assertNull(msg.getDuedate(), "等事件不由时间触发，不该有触发时刻");
        assertNotNull(msg.getWaitingMillis(), "已等待时长是排障时最常问的");
    }

    @Test
    @DisplayName("竞速订阅带出网关 id —— 让人看得出这几条是同一次竞速")
    void raceSubscriptionsCarryGatewayId() {
        String pid = startRace();
        List<WfSubscriptionView> views = subscriptions.subscriptionsOf(pid);
        for (WfSubscriptionView view : views) {
            assertEquals("eg", view.getGatewayId(),
                    "同一个网关下的订阅是互相排斥的，看不到网关 id 就以为它们互不相干");
        }
        // 两条分支的原始类型不同：等消息与等信号，归并成「等什么」之后仍要分得开
        assertEquals("EVENT_MESSAGE", byId(views, "waitMsg").getJobType());
        assertEquals("EVENT_SIGNAL", byId(views, "waitSignal").getJobType());
    }

    @Test
    @DisplayName("job 类型归并成「等什么」，但原类型要留着 —— 打断与竞速处置不同")
    void jobTypesAreGroupedButKept() {
        String pid = startRace();
        WfSubscriptionView race = byId(subscriptions.subscriptionsOf(pid), "waitMsg");
        assertEquals("message", race.getWaitingFor());
        assertEquals("EVENT_MESSAGE", race.getJobType(), "竞速订阅的原始类型");

        // 边界订阅也是等消息，但那是「打断」：宿主待办作废、走补偿分支
        String mixed = startMixed();
        WfSubscriptionView boundary = byId(subscriptions.subscriptionsOf(mixed), "beMsg");
        assertNotNull(boundary, "消息边界也是一条订阅");
        assertEquals("message", boundary.getWaitingFor(), "两者都归并成 message");
        assertEquals("MESSAGE", boundary.getJobType(), "但原类型要能区分打断与竞速");
        assertNull(boundary.getGatewayId(), "边界订阅不属于任何竞速");
    }

    // ==================== 过滤 ====================

    @Test
    @DisplayName("按流程实例 / 事件名 / 节点 / 类型过滤")
    void filtersNarrowTheResult() {
        String pid = startRace();
        assertEquals(2, subscriptions.listSubscriptions(
                new WfSubscriptionQuery().setProcessInstanceId(pid)).size());
        assertEquals(1, subscriptions.listSubscriptions(
                new WfSubscriptionQuery().setEventName("erpDone")).size());
        assertEquals(1, subscriptions.listSubscriptions(
                new WfSubscriptionQuery().setActivityId("waitSignal")).size());
        assertEquals(1, subscriptions.listSubscriptions(
                new WfSubscriptionQuery().addType(WfJobType.EVENT_SIGNAL)).size());
        assertEquals(0, subscriptions.listSubscriptions(
                new WfSubscriptionQuery().setProcessInstanceId("proc-does-not-exist")).size());
    }

    @Test
    @DisplayName("按定义 key 查：只给模板不知道实例 id 时也能用")
    void filtersByDefinitionKey() {
        startRace();
        startRace();
        // 另一个模板也在等（消息边界），它的订阅不该被 subRaceProcess 的查询捞出来。
        // 没有它的话，过滤失效与生效的结果一模一样 —— 那条断言就测不到任何东西
        startMixed();
        assertEquals(5, subscriptions.listSubscriptions(new WfSubscriptionQuery()).size(),
                "不筛选时三个实例的订阅全在");
        assertEquals(4, subscriptions.listSubscriptions(
                new WfSubscriptionQuery().setDefinitionKey("subRaceProcess")).size(),
                "按定义筛完只剩 subRaceProcess 的 4 条");
        assertEquals(4, subscriptions.subscriptionsOfDefinition("subRaceProcess").size());
    }

    @Test
    @DisplayName("按已等待时长过滤：找「趴了很久还没动」的那批")
    void filtersByWaitingLongerThan() {
        String pid = startRace();
        assertEquals(0, subscriptions.listSubscriptions(new WfSubscriptionQuery()
                .setProcessInstanceId(pid)
                .setWaitingLongerThanMillis(60_000L)).size(), "刚起的流程不该被算成等了很久");
        assertEquals(2, subscriptions.listSubscriptions(new WfSubscriptionQuery()
                .setProcessInstanceId(pid)
                .setWaitingLongerThanMillis(0L)).size());
    }

    @Test
    @DisplayName("按是否上锁过滤：外部任务「活没人领」与「已被人领走」是两回事")
    void filtersByLock() {
        // 外部任务那一步在消息边界的补偿分支上，所以得先把边界打断，
        // token 才会走到 serviceTask(topic) 上挂出外部活
        String pid = startMixed();
        runtime.triggerMessage("cancelIt", pid, "boss", null, "撤销");
        assertEquals(1, subscriptions.listSubscriptions(
                new WfSubscriptionQuery().setProcessInstanceId(pid)
                        .addType(WfJobType.EXTERNAL).setLocked(Boolean.FALSE)).size());

        externalTasks.fetchAndLock("erp.push", "worker-1", 1, 60_000L);
        assertEquals(0, subscriptions.listSubscriptions(
                new WfSubscriptionQuery().setProcessInstanceId(pid)
                        .addType(WfJobType.EXTERNAL).setLocked(Boolean.FALSE)).size());
        assertEquals(1, subscriptions.listSubscriptions(
                new WfSubscriptionQuery().setProcessInstanceId(pid)
                        .addType(WfJobType.EXTERNAL).setLocked(Boolean.TRUE)).size());

        WfSubscriptionView locked = subscriptions.listSubscriptions(
                new WfSubscriptionQuery().setProcessInstanceId(pid)
                        .addType(WfJobType.EXTERNAL)).get(0);
        assertEquals("worker-1", locked.getLockedBy(), "被谁领走了是排障的第一现场");
        assertEquals("external", locked.getWaitingFor());
        assertEquals("erp.push", locked.getEventName());
    }

    @Test
    @DisplayName("按 duedate 过滤：找「本该响了却没响」的定时器")
    void filtersByDueBefore() {
        String pid = startMixed();
        // 在办节点上挂一个过去就该响的定时器
        WfJob timer = new WfJob();
        timer.setProcessInstanceId(pid);
        timer.setElementId("host");
        timer.setAttachedToRef("host");
        timer.setType(WfJobType.TIMER);
        timer.setDuedate(new java.util.Date(System.currentTimeMillis() - 60_000L));
        timer.setCreateTime(new java.util.Date());
        // id 必须给：saveJob 遇到 null id 是静默 return（内存实现），
        // 塞进去的东西根本没进表，后面的断言就会一直 0 —— 看起来像"查不到逾期定时器"
        timer.setId("job-manual-overdue");
        repo.saveJob(timer);
        // findJob 返回的是副本（内存实现走序列化拷贝），所以只能按 id 断言
        assertNotNull(repo.findJob("job-manual-overdue"),
                "手工造的 job 必须真的进了表，否则下面的断言测不到任何东西");

        assertEquals(1, subscriptions.listSubscriptions(new WfSubscriptionQuery()
                .setProcessInstanceId(pid)
                .addType(WfJobType.TIMER)
                .setDueBefore(new java.util.Date())).size());
        assertEquals(0, subscriptions.listSubscriptions(new WfSubscriptionQuery()
                .addType(WfJobType.TIMER)
                .setDueBefore(new java.util.Date(System.currentTimeMillis() - 120_000L)))
                .size(), "还没到点的不算逾期");
    }

    // ==================== 分页与计数 ====================

    @Test
    @DisplayName("分页与总数对得上，且页码从 1 起")
    void pagingMatchesCount() {
        startRace();
        startRace();
        WfSubscriptionQuery query = new WfSubscriptionQuery()
                .setDefinitionKey("subRaceProcess").setPageNum(1).setPageSize(3);
        List<WfSubscriptionView> first = subscriptions.listSubscriptions(query);
        assertEquals(3, first.size());
        assertEquals(4, subscriptions.countSubscriptions(query), "总数不受分页影响");

        query.setPageNum(2);
        assertEquals(1, subscriptions.listSubscriptions(query).size());
        query.setPageNum(99);
        assertTrue(subscriptions.listSubscriptions(query).isEmpty(),
                "越界的页码给空列表，不该报错");
    }

    // ==================== 脏数据 ====================

    @Test
    @DisplayName("流程已结束却还留着 job：不计入订阅，但要留痕")
    void terminalInstanceSubscriptionsAreExcluded() {
        String pid = startRace();
        runtime.triggerMessage("bossApprove", pid, "boss", null, "批了");
        WfTask task = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(5)).get(0);
        runtime.completeTask(task.getId(), "ops", "办结", null);

        // 手工塞一条属于这个已完结实例的 job —— 模拟清理逻辑漏了一步
        WfJob stale = new WfJob();
        // id 必须给：saveJob 遇到 null id 是静默 return，塞进去的东西根本没进表，
        // 后面的断言会一直绿 —— 看起来像"终态排除生效了"，其实什么都没测
        stale.setId("job-stale-after-terminal");
        stale.setProcessInstanceId(pid);
        stale.setElementId("waitSignal");
        stale.setType(WfJobType.EVENT_SIGNAL);
        stale.setExceptionMessage("erpDone");
        stale.setCreateTime(new java.util.Date());
        repo.saveJob(stale);
        assertNotNull(repo.findJob("job-stale-after-terminal"), "残留 job 必须真的进了表");

        assertTrue(subscriptions.subscriptionsOf(pid).isEmpty(),
                "已终态的实例不该还报出等待 —— 那会让人以为它在等人，而它早就结束了");
    }

    @Test
    @DisplayName("超过扫描上限时报错，而不是给一份看起来完整的截断列表")
    void oversizedResultIsReportedNotTruncated() {
        // 静默截断是这类接口最坏的一种错：调用方要的结论恰恰是"全都查过了，没有漏"。
        // 所以超量必须炸，且报错要说清该怎么缩小范围
        String pid = startRace();
        for (int i = 0; i < WfSubscriptionService.MAX_SCAN + 5; i++) {
            WfJob job = new WfJob();
            job.setId("job-flood-" + i);
            job.setProcessInstanceId(pid);
            job.setElementId("waitMsg");
            job.setType(WfJobType.EVENT_MESSAGE);
            job.setExceptionMessage("bossApprove");
            job.setCreateTime(new java.util.Date());
            repo.saveJob(job);
        }
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> subscriptions.subscriptionsOf(pid));
        assertTrue(ex.getMessage().contains(String.valueOf(WfSubscriptionService.MAX_SCAN)),
                "报错要给出上限这个数，调用方才知道自己差多少。实际 " + ex.getMessage());
        assertTrue(ex.getMessage().contains("缩小范围"),
                "报错要告诉调用方下一步怎么办。实际 " + ex.getMessage());

        // 按实例下推的价值：另一个实例（B）没有被淹没，就不该被 A 的量级连累。
        // 不下推的话每次都全表扫，B 也会跟着报"超量" —— 那等于这条查询在大实例量下
        // 完全不可用，而这恰恰是它最需要好用的场景
        String other = startMixed();
        assertEquals(1, subscriptions.subscriptionsOf(other).size(),
                "没被淹没的实例必须照常查得到，不能被别的实例的量级连累");

        // 缩小范围之后要能正常查 —— 报错不能是死路。
        // 洪水 job 挂在同一个实例上，不清掉的话连"按实例查"也会超量，
        // 于是这条断言验证不到"报错之后还能继续用"这件事
        for (int i = 0; i < WfSubscriptionService.MAX_SCAN + 5; i++) {
            repo.deleteJob("job-flood-" + i);
        }
        assertEquals(2, subscriptions.subscriptionsOf(pid).size(),
                "清掉超量的 job 之后必须能正常查到结果，否则报错就是死路");
    }

    @Test
    @DisplayName("空结果给空列表而不是 null")
    void emptyResultIsEmptyList() {
        assertTrue(subscriptions.listSubscriptions(new WfSubscriptionQuery()).isEmpty());
        assertEquals(0, subscriptions.countSubscriptions(null));
    }

    @Test
    @DisplayName("定义 key 为空时报错，而不是当没传")
    void definitionKeyMustNotBeBlank() {
        assertThrows(WfEngineException.class, () -> subscriptions.subscriptionsOfDefinition(" "));
    }

    @Test
    @DisplayName("人工任务本身不算订阅 —— 它在等某个具体的人，不是等事件")
    void userTaskIsNotASubscription() {
        String pid = startMixed();
        for (WfSubscriptionView view : subscriptions.subscriptionsOf(pid)) {
            assertTrue(!"host".equals(view.getActivityId()),
                    "人工待办在任务列表里查得到，不需要也不该出现在订阅里");
        }
    }
}
