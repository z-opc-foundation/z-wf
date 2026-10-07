package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfMetric;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 引擎指标（第 44 轮）。
 *
 * <p>指标最要紧的判据不是「算得对」——那太容易了——而是三件
 * <b>错了也没人看得出来</b>的事：
 * <ul>
 *   <li><b>在途的东西不能混进时长统计</b>：把没结束的当成 0 秒，平均时长瞬间塌掉</li>
 *   <li><b>扫描超量必须报错</b>：一份少算的指标会被原样摆在看板上</li>
 *   <li><b>分桶边界只有一个归属</b>：两端都闭会让人数出两条，都开会数出零条</li>
 * </ul>
 *
 * <p>时长类判据<b>不靠引擎实时打点</b>（那几乎恒为 0 毫秒，落桶没有区分度），
 * 而是手工造出已知时长的实例与任务 ——
 * 分桶边界只有用<b>恰好落在边界上</b>的数据才验得出来。
 */
class WfMetricsTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    private static final String NS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfMetricsService metrics;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
        metrics = new WfMetricsService(repo);
    }

    // ==================== 夹具 ====================

    private static String bpmnOf(String key) {
        return NS
                + "  <process id=\"" + key + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
    }

    private WfDefinition deploy(String key) {
        return repository.deploy(new WfXmlParser().parse(bpmnOf(key)));
    }

    /** 手工造一条实例，<b>时长完全由参数决定</b>（分桶边界只有这样才验得出来）。 */
    private WfProcessInstance insertInstance(String id, String definitionKey,
            Date startTime, Date endTime) {
        WfProcessInstance instance = new WfProcessInstance();
        instance.setId(id);
        instance.setDefinitionKey(definitionKey);
        instance.setBusinessKey(id);
        instance.setStartTime(startTime);
        instance.setEndTime(endTime);
        instance.setStatus(endTime == null ? WfProcessStatus.ACTIVE : WfProcessStatus.COMPLETED);
        repo.saveProcessInstance(instance);
        return instance;
    }

    /** 手工造一条任务，带确定的创建/办结时刻与办理人。 */
    private WfTask insertTask(String id, String processInstanceId,
            Date createTime, Date endTime, String completerId, String assignee) {
        WfTask task = new WfTask();
        task.setId(id);
        task.setProcessInstanceId(processInstanceId);
        task.setDefinitionId("approve");
        task.setName("审批");
        task.setType("USER");
        task.setAssignee(assignee);
        task.setCompleterId(completerId);
        task.setCreateTime(createTime);
        task.setEndTime(endTime);
        task.setStatus(endTime == null ? WfTask.Status.ASSIGNED : WfTask.Status.COMPLETED);
        task.nextRevision();
        repo.saveTask(task);
        return task;
    }

    private WfMetricRow all(List<WfMetricRow> rows) {
        for (WfMetricRow row : rows) {
            if (WfMetricRow.ALL.equals(row.getName())) {
                return row;
            }
        }
        throw new AssertionError("返回里没有 " + WfMetricRow.ALL + " 汇总行。实际: " + rows);
    }

    private WfMetricRow named(List<WfMetricRow> rows, String name) {
        for (WfMetricRow row : rows) {
            if (name.equals(row.getName())) {
                return row;
            }
        }
        throw new AssertionError("返回里没有名为「" + name + "」的行。实际: " + rows);
    }

    private List<WfMetricRow> run(WfMetric metric) {
        return metrics.query(new WfMetricsQuery(metric));
    }

    // ==================== 实例数 ====================

    @Test
    @DisplayName("实例数按定义分组，末尾带一行 ALL 汇总")
    void instanceCountGroupsByDefinition() {
        deploy("leave");
        deploy("expense");
        Date now = new Date();
        insertInstance("i-1", "leave", now, null);
        insertInstance("i-2", "leave", now, null);
        insertInstance("i-3", "expense", now, null);

        List<WfMetricRow> rows = run(WfMetric.PROCESS_INSTANCES);
        assertEquals(2, named(rows, "leave").getCount(), "leave 两单。实际: " + rows);
        assertEquals(1, named(rows, "expense").getCount(), "expense 一单。实际: " + rows);
        assertEquals(3, all(rows).getCount(),
                "**ALL 那一行必须现成给好**：让调用方自己相加，"
                        + "而「忘了相加」是这类接口最常见的用法错误。实际: " + rows);
    }

    @Test
    @DisplayName("实例数只看启动、不看结束：在途的那些也要算进来")
    void instanceCountIncludesUnfinishedOnes() {
        deploy("pending");
        Date now = new Date();
        insertInstance("done", "pending", now, new Date(now.getTime() + HOUR));
        insertInstance("still-open", "pending", now, null);

        List<WfMetricRow> rows = run(WfMetric.PROCESS_INSTANCES);
        assertEquals(2, all(rows).getCount(),
                "**统计的是「启动了多少」，不是「完成了多少」**——"
                        + "审批系统里这两个数差着一个量级：一堆单子启动后卡在审批节点上，"
                        + "完成数看着正常，启动数才反映真实积压。实际: " + rows);
    }

    @Test
    @DisplayName("在途实例不进时长统计（把没结束的当成 0 秒会把平均塌掉）")
    void onlyFinishedInstancesCountTowardsDuration() {
        deploy("mixed");
        Date now = new Date();
        insertInstance("finished", "mixed", now, new Date(now.getTime() + 2 * HOUR));
        insertInstance("open", "mixed", now, null);

        List<WfMetricRow> rows = run(WfMetric.PROCESS_INSTANCE_DURATION);
        assertEquals(1, all(rows).getCount(),
                "在途实例没有时长可言，不参与统计。实际: " + all(rows));
        assertEquals(2 * HOUR, all(rows).getAvgMillis().longValue(),
                "平均值只按那一条算。实际: " + all(rows));
    }

    // ==================== 时长直方图 ====================

    @Test
    @DisplayName("每个时长落进它该在的那一桶")
    void durationLandsInTheRightBucket() {
        deploy("spread");
        Date now = new Date();
        insertInstance("s-30s", "spread", now, new Date(now.getTime() + 30_000L));
        insertInstance("m-2min", "spread", now, new Date(now.getTime() + 2 * MINUTE));
        insertInstance("m-10min", "spread", now, new Date(now.getTime() + 10 * MINUTE));
        insertInstance("h-5h", "spread", now, new Date(now.getTime() + 5 * HOUR));
        insertInstance("d-3d", "spread", now, new Date(now.getTime() + 3 * DAY));

        List<String> labels = WfMetric.bucketLabels();
        List<WfMetricRow> rows = run(WfMetric.PROCESS_INSTANCE_DURATION);
        assertEquals(1, named(rows, labels.get(0)).getCount(), "30 秒。实际: " + rows);
        assertEquals(1, named(rows, labels.get(1)).getCount(), "2 分钟。实际: " + rows);
        assertEquals(1, named(rows, labels.get(2)).getCount(), "10 分钟。实际: " + rows);
        assertEquals(1, named(rows, labels.get(4)).getCount(), "5 小时。实际: " + rows);
        assertEquals(1, named(rows, labels.get(7)).getCount(), "3 天。实际: " + rows);
        assertEquals(5, all(rows).getCount(), "五条都在。实际: " + rows);
    }

    @Test
    @DisplayName("分桶边界只有一个归属：恰好等于下界算下一桶")
    void bucketBoundariesAreLeftClosedRightOpen() {
        deploy("edge");
        Date now = new Date();
        // **恰好等于每一个下界**——用「差一点」的数据验不出来
        insertInstance("e-1min", "edge", now, new Date(now.getTime() + 1 * MINUTE));
        insertInstance("e-5min", "edge", now, new Date(now.getTime() + 5 * MINUTE));
        insertInstance("e-30min", "edge", now, new Date(now.getTime() + 30 * MINUTE));

        List<String> labels = WfMetric.bucketLabels();
        Map<String, Long> counts = new HashMap<>();
        for (WfMetricRow row : run(WfMetric.PROCESS_INSTANCE_DURATION)) {
            counts.put(row.getName(), Long.valueOf(row.getCount()));
        }
        assertEquals(0, counts.get(labels.get(0)).longValue(),
                "恰好 1 分钟**不属于**第一桶（那一桶是 [0, 1 分钟)）。实际: " + counts);
        assertEquals(1, counts.get(labels.get(1)).longValue(),
                "恰好 1 分钟属于 [1, 5 分钟)。实际: " + counts);
        assertEquals(1, counts.get(labels.get(2)).longValue(),
                "恰好 5 分钟属于下一桶。两端都闭会数出两条，都开会数出零条。实际: " + counts);
        assertEquals(1, counts.get(labels.get(3)).longValue(),
                "恰好 30 分钟属于 [30 分钟, 2 小时)。实际: " + counts);
    }

    @Test
    @DisplayName("负时长夹到 0，不当成「秒批」")
    void negativeDurationIsClampedToZero() {
        deploy("clock");
        Date now = new Date();
        // 结束早于开始：改过系统时间或数据被手工改过都会这样
        insertInstance("backwards", "clock", now, new Date(now.getTime() - 5 * HOUR));

        WfMetricRow total = all(run(WfMetric.PROCESS_INSTANCE_DURATION));
        assertEquals(0L, total.getMinMillis().longValue(),
                "夹到 0 是最小的撒谎——真值对不上这件事没有第二个更好的出口。"
                        + "而放任负数会落到第 0 桶里冒充「秒批」。实际: " + total);
        assertEquals(0L, total.getMaxMillis().longValue(), "实际: " + total);
    }

    @Test
    @DisplayName("空桶也要出一行（count=0）：看板上「这一档是 0」要一眼看得出来")
    void emptyBucketsStillAppearAsZero() {
        deploy("empty");
        List<WfMetricRow> rows = run(WfMetric.PROCESS_INSTANCE_DURATION);
        assertEquals(WfMetric.bucketLabels().size() + 1, rows.size(),
                "**桶数固定，空桶也占一行**：少一格与「这一格是 0」在视觉上不一样。实际: " + rows);
        for (int i = 0; i < WfMetric.bucketLabels().size(); i++) {
            assertEquals(0, named(rows, WfMetric.bucketLabels().get(i)).getCount());
        }
        assertEquals(0, all(rows).getCount(), "一条数据都没有时 ALL 也是 0");
        assertNull(all(rows).getAvgMillis(),
                "**空集合的平均值是 null 而不是 0**——"
                        + "0 毫秒是一个看起来很像真值的答案");
    }

    @Test
    @DisplayName("ALL 那一行带总数、平均、最小、最大")
    void allRowCarriesSummary() {
        deploy("summary");
        Date now = new Date();
        insertInstance("a", "summary", now, new Date(now.getTime() + MINUTE));
        insertInstance("b", "summary", now, new Date(now.getTime() + 3 * MINUTE));

        WfMetricRow total = all(run(WfMetric.PROCESS_INSTANCE_DURATION));
        assertEquals(2, total.getCount());
        assertEquals(4 * MINUTE, total.getSumMillis().longValue(), "1 + 3 分钟。实际: " + total);
        assertEquals(2 * MINUTE, total.getAvgMillis().longValue(), "实际: " + total);
        assertEquals(MINUTE, total.getMinMillis().longValue(), "实际: " + total);
        assertEquals(3 * MINUTE, total.getMaxMillis().longValue(), "实际: " + total);
        assertTrue(total.hasDuration(), "时长类指标必须能一眼认出自己带时长");
    }

    // ==================== 任务类 ====================

    @Test
    @DisplayName("任务时长按创建到办结算")
    void taskDurationUsesCreateToEnd() {
        deploy("taskdur");
        Date now = new Date();
        WfProcessInstance instance = insertInstance("pi", "taskdur", now, null);
        insertTask("t-1", instance.getId(), now, new Date(now.getTime() + 20 * MINUTE),
                "alice", "alice");
        insertTask("t-open", instance.getId(), now, null, null, "bob");

        WfMetricRow total = all(run(WfMetric.TASK_DURATION));
        assertEquals(1, total.getCount(),
                "**在途任务没有办理时长可言**，那一条不参与统计。实际: " + total);
        assertEquals(20 * MINUTE, total.getAvgMillis().longValue(), "实际: " + total);
    }

    @Test
    @DisplayName("工作量按实际办理人分组，不是按 assignee（转办之后两者分叉）")
    void taskUsersGroupsByCompleterNotAssignee() {
        deploy("handover");
        Date now = new Date();
        WfProcessInstance instance = insertInstance("pi", "handover", now, null);
        // 派给 alice，实际是 bob 点的通过（转办 / 代办）
        insertTask("t-1", instance.getId(), now, new Date(now.getTime()), "bob", "alice");
        insertTask("t-2", instance.getId(), now, new Date(now.getTime()), "bob", "alice");
        insertTask("t-3", instance.getId(), now, new Date(now.getTime()), "carol", "bob");

        List<WfMetricRow> rows = run(WfMetric.TASK_USERS);
        assertEquals(2, named(rows, "bob").getCount(), "bob 实际办了两件。实际: " + rows);
        assertEquals(1, named(rows, "carol").getCount(), "实际: " + rows);
        assertEquals(3, all(rows).getCount());
    }

    @Test
    @DisplayName("给 definitionKey 时任务类指标也跟着筛（任务表上没有定义 key 可筛）")
    void definitionKeyAlsoFiltersTaskMetrics() {
        deploy("leave");
        deploy("expense");
        Date now = new Date();
        WfProcessInstance leave = insertInstance("pi-leave", "leave", now, null);
        WfProcessInstance expense = insertInstance("pi-expense", "expense", now, null);
        insertTask("t-leave", leave.getId(), now, new Date(now.getTime()), "alice", "alice");
        insertTask("t-expense", expense.getId(), now, new Date(now.getTime()), "bob", "bob");

        List<WfMetricRow> filtered = metrics.query(new WfMetricsQuery(WfMetric.TASK_USERS)
                .setDefinitionKey("leave"));
        assertEquals(1, all(filtered).getCount(),
                "**任务表上没有 DEF_KEY**（它的 DEF_ID 存的是节点 id），"
                        + "所以只能先圈实例再取任务——但筛了就得真的筛掉。实际: " + filtered);
    }

    // ==================== 闸门与条件 ====================

    @Test
    @DisplayName("时间窗口把窗口外的数据剔掉")
    void timeWindowExcludesOutsideInstances() {
        deploy("window");
        Date now = new Date();
        Date yesterday = new Date(now.getTime() - 3 * DAY);
        insertInstance("old", "window", yesterday, null);
        insertInstance("today", "window", now, null);

        List<WfMetricRow> rows = metrics.query(new WfMetricsQuery(WfMetric.PROCESS_INSTANCES)
                .setStartDate(new Date(now.getTime() - HOUR)));
        assertEquals(1, all(rows).getCount(),
                "窗口是**闭区间**，昨天那一条要剔掉。实际: " + rows);
    }

    @Test
    @DisplayName("扫描超上限：报错说清怎么缩小范围，不给一份少算的指标")
    void scanOverLimitIsRejected() {
        // 用可配的上限造溢出，而不是真起十万条——
        // 那道闸门是这条路上最要紧的一条（指标会被原样摆在看板上）
        WfMetricsService limited = new WfMetricsService(repo, 3);
        deploy("bulk");
        Date now = new Date();
        for (int i = 0; i < 5; i++) {
            insertInstance("bulk-" + i, "bulk", now, null);
        }

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> limited.query(new WfMetricsQuery(WfMetric.PROCESS_INSTANCES)),
                "给一份少算的指标比报错更坏：它会被原样摆在看板上，而没人会去核对总数");
        assertTrue(ex.getMessage().contains("缩小范围"),
                "报错要直接告诉人怎么办。实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("恰好等于上限不算超量，上限 +1 才报")
    void exactlyAtLimitIsNotOverflow() {
        WfMetricsService limited = new WfMetricsService(repo, 3);
        deploy("edge-scan");
        Date now = new Date();
        for (int i = 0; i < 3; i++) {
            insertInstance("edge-" + i, "edge-scan", now, null);
        }

        assertEquals(3, all(limited.query(new WfMetricsQuery(WfMetric.PROCESS_INSTANCES))).getCount(),
                "**单数正好等于上限时要给出答案，不是报错**——"
                        + "报错文案写着「超过 3 条」，而实际是 3 条，"
                        + "一条对不上的报错比不报错更难查");

        insertInstance("edge-4", "edge-scan", now, null);
        assertThrows(WfEngineException.class,
                () -> limited.query(new WfMetricsQuery(WfMetric.PROCESS_INSTANCES)),
                "超出一条就该报。边界判据成对写，少任何一条都钉不住 `>` 还是 `>=`");
    }

    @Test
    @DisplayName("窗口写反直接拒绝；没给指标也直接拒绝")
    void inconsistentConditionsAreRejected() {
        Date now = new Date();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new WfMetricsQuery(WfMetric.PROCESS_INSTANCES)
                        .setStartDate(now).setEndDate(new Date(now.getTime() - HOUR))
                        .assertConsistent());
        assertTrue(ex.getMessage().contains("startDate"),
                "报错要点名是哪个参数出了问题。实际: " + ex.getMessage());

        IllegalArgumentException noMetric = assertThrows(IllegalArgumentException.class,
                () -> new WfMetricsQuery().assertConsistent());
        assertTrue(noMetric.getMessage().contains("metric"),
                "实际: " + noMetric.getMessage());
    }

    @Test
    @DisplayName("一条数据都没有时也只有 ALL 一行（前端不用判空）")
    void emptyResultStillCarriesAllRow() {
        deploy("nothing");
        List<WfMetricRow> rows = run(WfMetric.PROCESS_INSTANCES);
        assertNotNull(all(rows), "没有数据也要给 ALL 行");
        assertEquals(1, rows.size(), "没有任何实例时只有 ALL 一行。实际: " + rows);
        assertEquals(0, all(rows).getCount());
    }

    @Test
    @DisplayName("分组同数时按名字排：两页看板的排序不许跳")
    void tiesAreBrokenByName() {
        deploy("ties");
        Date now = new Date();
        insertInstance("i-a", "zeta", now, null);
        insertInstance("i-b", "alpha", now, null);
        insertInstance("i-c", "mid", now, null);

        List<WfMetricRow> rows = new ArrayList<>();
        for (WfMetricRow row : run(WfMetric.PROCESS_INSTANCES)) {
            if (!WfMetricRow.ALL.equals(row.getName())) {
                rows.add(row);
            }
        }
        assertEquals(3, rows.size());
        assertEquals("alpha", rows.get(0).getName(),
                "三个都是 1 单，只能按名字定序。实际: " + rows);
        assertEquals("mid", rows.get(1).getName(), "实际: " + rows);
        assertEquals("zeta", rows.get(2).getName(), "实际: " + rows);
    }

    @Test
    @DisplayName("走真实引擎起单并办结，四类指标都能算出东西（夹具本身的连通性）")
    void endToEndThroughTheEngine() {
        WfDefinition definition = deploy("real");
        String pid = runtime.startProcessInstance(definition, "real-1", "alice", null, null);
        List<WfTask> open = repo.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(pid).setOpenOnly(true));
        assertEquals(1, open.size(), "应停在审批待办上。实际: " + open);
        runtime.completeTask(open.get(0).getId(), "boss", null, new HashMap<String, Object>());

        assertEquals(1, all(run(WfMetric.PROCESS_INSTANCES)).getCount(),
                "起了一单就应当数出一单。实际: " + run(WfMetric.PROCESS_INSTANCES));
        assertEquals(1, all(run(WfMetric.PROCESS_INSTANCE_DURATION)).getCount(),
                "办结之后才有端到端时长。实际: " + run(WfMetric.PROCESS_INSTANCE_DURATION));
        assertEquals(1, all(run(WfMetric.TASK_DURATION)).getCount(),
                "实际: " + run(WfMetric.TASK_DURATION));
        assertEquals(1, named(run(WfMetric.TASK_USERS), "boss").getCount(),
                "办结人就是 boss。实际: " + run(WfMetric.TASK_USERS));
    }
}