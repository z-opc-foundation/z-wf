package com.zifang.z.wf.core.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.model.WfTask;

/**
 * 任务查询的新条件（第 45 轮）与<b>两套持久化实现的一致性</b>。
 *
 * <h3>本类为什么长这样</h3>
 * 新加的每一条过滤都<b>必须在两个实现里各写一遍</b>
 * （{@code InMemoryWorkflowPersistence#matches} 与
 * {@code JdbcWorkflowPersistence#appendTaskFilters}）。
 * 少写一边就是「开发期查得到、线上查不到」——
 * 而内存模式下<b>完全不可见</b>，本仓这些年每次补条件都栽在这上面。
 *
 * <p>所以本类的主轴不是「条件语义对不对」（那是次要的），
 * 而是<b>同一批数据、同一组条件，两个实现必须给出同一个集合</b>：
 * 数据直接灌进两套实现，条件跑两遍，比对结果。
 * core 模块的 test classpath 上就有 H2，**不需要起 Spring 上下文**。
 *
 * <h3>造数据不用引擎</h3>
 * 引擎实时打点出来的优先级恒为 50、截止时间恒为 null，
 * 区间条件根本无从验起。手工造是唯一能拿到"恰好落在边界上"的数据的办法，
 * 而边界正是这类条件最容易写错的地方。
 */
class WfTaskAdvancedQueryTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    /**
     * 固定基准时刻。
     *
     * <p><b>种子数据与查询窗口必须用同一个基准</b>：造数据时按 {@code NOW}
     * 说「昨天到期、明天到期」，查询窗口却写 {@code new Date()}（真实当前），
     * 两者差一年多时，"明天到期"那条也落进了"已超期"的清单 ——
     * 症状是判据红，而真因在夹具不在实现。
     */
    private static final long NOW = 1_760_000_000_000L;

    private InMemoryWorkflowPersistence memory;
    private JdbcWorkflowPersistence jdbc;

    @BeforeEach
    void setUp() {
        memory = new InMemoryWorkflowPersistence();
        memory.initialize();

        JdbcDataSource ds = new JdbcDataSource();
        String name = "wf_taskq_" + COUNTER.incrementAndGet();
        ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        jdbc = new JdbcWorkflowPersistence(ds);
        jdbc.initialize();

        seed();
    }

    /** 五条任务，**两套实现里逐字段相同**。 */
    private void seed() {
        // 紧急、昨天到期、未办结
        put("T1", 90, shift(-1 * DAY), null, WfTask.Status.ASSIGNED, null);
        // 默认优先级、明天到期、今天上午办结
        put("T2", 50, shift(1 * DAY), shift(0), WfTask.Status.COMPLETED, "alice");
        // 优先级 70、**没有截止时间**、今天办结
        put("T3", 70, null, shift(0), WfTask.Status.COMPLETED, "bob");
        // 低优先级、昨天到期、未办结
        put("T4", 30, shift(-1 * DAY), null, WfTask.Status.ASSIGNED, null);
        // 默认优先级、今天到期、未办结 —— 「今天到期」算不算超期取决于窗口取不取到它
        put("T5", 50, shift(0), null, WfTask.Status.ASSIGNED, null);
        // **办理时间恰好等于基准时刻** —— 边界判据必须有一条数据**落在边界上**。
        // 反验证 M6 把 END_TIME>=? 改成 >? 时全绿，就是因为没有任何一条的 endTime
        // 恰好等于窗口端点：`NOW >= NOW` 与 `NOW > NOW` 的差别在那批数据上看不见。
        put("T6", 50, null, shift(0), WfTask.Status.COMPLETED, "carol");
    }

    private void put(String id, int priority, Date due, Date end, WfTask.Status status,
            String completer) {
        for (WfPersistence repo : new WfPersistence[] {memory, jdbc}) {
            WfTask task = new WfTask();
            task.setId(id);
            task.setProcessInstanceId("pi-" + id);
            task.setDefinitionId("approve");
            task.setName("审批");
            task.setType("USER");
            task.setAssignee("boss");
            task.setPriority(priority);
            task.setDueDate(due);
            task.setCreateTime(shift(-2 * DAY));
            task.setEndTime(end);
            task.setCompleterId(completer);
            task.setStatus(status);
            task.nextRevision();
            repo.saveTask(task);
        }
    }

    private static Date shift(long deltaMillis) {
        return new Date(NOW + deltaMillis);
    }

    // ==================== 比对工具 ====================

    private Set<String> ids(WfPersistence repo, WfTaskQuery query) {
        Set<String> out = new TreeSet<>();
        for (WfTask task : repo.queryTasks(query)) {
            out.add(task.getId());
        }
        return out;
    }

    private Set<String> ids(WfPersistence repo, Function<WfTaskQuery, WfTaskQuery> build) {
        return ids(repo, build.apply(new WfTaskQuery()));
    }

    /**
     * **本类的核心断言**：两个实现必须给同一个答案。
     *
     * <p>不比较顺序 —— 内存实现的排序与 JDBC 的 ORDER BY 规则本就不同
     * （JDBC 侧有 {@code CASE STATUS ...} 与同值兜底），本轮验的是<b>集合</b>。
     * 顺序一致性由既有判据负责。
     */
    private void agree(Function<WfTaskQuery, WfTaskQuery> build) {
        WfTaskQuery probe = build.apply(new WfTaskQuery());
        Set<String> onMemory = ids(memory, probe);
        Set<String> onJdbc = ids(jdbc, probe);
        assertEquals(onMemory, onJdbc,
                "**两个 persistence 实现对同一条查询给出了不同的答案** —— "
                        + "条件是「内存里查得到、库里查不到」，"
                        + "而这个 bug 在内存模式下完全不可见。内存: " + onMemory
                        + " / JDBC: " + onJdbc);
    }

    private static WfTaskQuery page(WfTaskQuery query) {
        return query.setPageNum(1).setPageSize(50);
    }

    // ==================== 优先级区间 ====================

    @Test
    @DisplayName("优先级下界/上界：**两个实现一致**，且闭区间")
    void priorityRangeIsConsistentAndClosed() {
        agree(q -> q.setMinPriority(50));
        agree(q -> q.setMaxPriority(50));
        agree(q -> q.setMinPriority(30).setMaxPriority(70));
        agree(q -> q.setMinPriority(0));
        agree(q -> q.setMaxPriority(0));

        Set<String> middle = ids(memory, q -> q.setMinPriority(50));
        assertEquals(new TreeSet<>(java.util.Arrays.asList("T1", "T2", "T3", "T5", "T6")), middle,
                "T1=90 / T2=50 / T3=70 / T5=50 / T6=50 都在，T4=30 不在。实际: " + middle);
        assertEquals(new TreeSet<>(java.util.Arrays.asList("T2", "T3", "T5", "T6")),
                ids(memory, q -> q.setMinPriority(50).setMaxPriority(70)),
                "**闭区间**：恰好等于上下界的 T2(50) 与 T3(70) 都要算上。实际: "
                        + ids(memory, q -> q.setMinPriority(50).setMaxPriority(70)));
        assertEquals(0, ids(memory, q -> q.setMinPriority(95)).size(),
                "没有一条到得了 95。实际: " + ids(memory, q -> q.setMinPriority(95)));
    }

    // ==================== 办理时间区间 ====================

    @Test
    @DisplayName("办理时间区间：**没办结的任务不参与**（把没办结的当成 0 毫秒最坏）")
    void endTimeRangeIsConsistentAndExcludesOpenTasks() {
        agree(q -> q.setEndTimeFrom(shift(-HOUR)));
        agree(q -> q.setEndTimeTo(shift(HOUR)));
        agree(q -> q.setEndTimeFrom(shift(-HOUR)).setEndTimeTo(shift(HOUR)));

        Set<String> done = ids(memory, q -> q.setEndTimeFrom(shift(-DAY)));
        assertEquals(new TreeSet<>(java.util.Arrays.asList("T2", "T3", "T6")), done,
                "只有 T2/T3/T6 办结过。**T1/T4/T5 的 END_TIME 是空的，"
                        + "不能当成 0 毫秒算进去** —— 那等于回答「他什么时候批的」时"
                        + "说「他秒批了」。实际: " + done);
        assertEquals(0, ids(memory, q -> q.setEndTimeFrom(shift(HOUR))).size(),
                "未来才办结的那一条现在还没有。实际: " + ids(memory, q -> q.setEndTimeFrom(shift(HOUR))));

        // **下界闭区间**：办理时间恰好等于窗口下界的那几条要算上。
        // 反验证 M6 把 `END_TIME>=?` 改成 `>?` 时判据全绿，就是因为
        // **没有任何一条任务的 endTime 恰好等于窗口端点** ——
        // `NOW >= NOW-1d` 与 `NOW > NOW-1d` 结果相同，差别在那批数据上看不见。
        // ⇒ 有了 T6（endTime == NOW）之后，这条才真的咬得住。
        Set<String> onBoundary = ids(memory, q -> q.setEndTimeFrom(shift(0)));
        assertEquals(new TreeSet<>(java.util.Arrays.asList("T2", "T3", "T6")), onBoundary,
                "**endTime 恰好等于窗口下界必须命中**（闭区间）。实际: " + onBoundary);
        agree(q -> q.setEndTimeFrom(shift(0)));
        agree(q -> q.setEndTimeTo(shift(0)));
    }

    // ==================== 截止时间区间（审批超期） ====================

    @Test
    @DisplayName("截止时间区间：**没设截止时间的任务不算超期**")
    void dueDateRangeIsConsistentAndExcludesTasksWithoutDueDate() {
        agree(q -> q.setDueDateFrom(shift(-DAY)));
        agree(q -> q.setDueDateTo(new Date(NOW)));
        agree(q -> q.setDueDateBetween(shift(-DAY), new Date(NOW)));

        Set<String> overdue = ids(memory, q -> q.setDueDateTo(new Date(NOW)));
        assertEquals(new TreeSet<>(java.util.Arrays.asList("T1", "T4", "T5")), overdue,
                "**T3 没有设截止时间，它没有承诺过什么时候办完** —— "
                        + "把它算进超期清单，运维第一件事就是去挨个确认"
                        + "「这条到底该不该管」。实际: " + overdue);
        assertEquals(new TreeSet<>(java.util.Arrays.asList("T1", "T2", "T4", "T5")),
                ids(memory, q -> q.setDueDateTo(shift(2 * DAY))),
                "窗口放宽到两天后，明天到期的 T2 也该进来。实际: "
                        + ids(memory, q -> q.setDueDateTo(shift(2 * DAY))));
    }

    @Test
    @DisplayName("**待办与已办用同一组条件查，答案自洽**（审批最常做的两个动作）")
    void openAndDoneAreBothReachableByDueDate() {
        Set<String> overdueOpen = ids(memory, q -> q.setOpenOnly(true)
                .setDueDateTo(new Date(NOW)).setPageSize(50));
        assertEquals(new TreeSet<>(java.util.Arrays.asList("T1", "T4", "T5")), overdueOpen,
                "未办结且已过期的。实际: " + overdueOpen);
        Set<String> overdueDone = ids(memory, q -> q.setCompletedOnly(true)
                .setDueDateTo(new Date(NOW)).setPageSize(50));
        assertTrue(overdueDone.isEmpty(),
                "办结过的那两条截止时间都在今天或明天，不算已过期。实际: " + overdueDone);
    }

    // ==================== count 与列表同一把尺子 ====================

    @Test
    @DisplayName("**countTasks 与 queryTasks 条件一致**（两边都算各自的，别一边多一条）")
    void countAgreesWithListOnEveryCondition() {
        List<WfTaskQuery> probes = new ArrayList<>();
        probes.add(new WfTaskQuery().setMinPriority(50).setPageSize(50));
        probes.add(new WfTaskQuery().setMinPriority(50).setMaxPriority(70).setPageSize(50));
        probes.add(new WfTaskQuery().setEndTimeFrom(shift(-DAY)).setPageSize(50));
        probes.add(new WfTaskQuery().setDueDateTo(new Date(NOW)).setPageSize(50));
        probes.add(new WfTaskQuery().setDueDateBetween(shift(-DAY), new Date(NOW))
                .setOpenOnly(true).setPageSize(50));

        for (WfTaskQuery probe : probes) {
            for (WfPersistence repo : new WfPersistence[] {memory, jdbc}) {
                long counted = repo.countTasks(probe);
                long listed = repo.queryTasks(probe).size();
                assertEquals(listed, counted,
                        "count 与列表用了不同的尺子 —— 界面上表现为「总共 N 条，"
                                + "翻到第 2 页却是空的」。条件: " + probe);
            }
        }
    }

    // ==================== 区间倒置 ====================

    @Test
    @DisplayName("**三组区间写反都直接拒绝**，不静默返回空集")
    void reversedRangesAreRejected() {
        assertReversed("endTimeFrom", new WfTaskQuery()
                .setEndTimeFrom(new Date()).setEndTimeTo(shift(-DAY)));
        assertReversed("dueDateFrom", new WfTaskQuery()
                .setDueDateFrom(new Date()).setDueDateTo(shift(-DAY)));

        IllegalArgumentException priority = assertThrows(IllegalArgumentException.class,
                () -> new WfTaskQuery().setMinPriority(90).setMaxPriority(10)
                        .assertConsistent());
        assertTrue(priority.getMessage().contains("minPriority"),
                "报错要点名是哪个参数。实际: " + priority.getMessage());
    }

    private void assertReversed(String expectedName, WfTaskQuery query) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> query.assertConsistent());
        assertTrue(ex.getMessage().contains(expectedName),
                "报错要点名是哪个参数出了问题。实际: " + ex.getMessage());
    }

    // ==================== 默认路径零变化 ====================

    @Test
    @DisplayName("**不给新条件时，行为与本轮之前完全一致**")
    void newConditionsDoNotAffectExistingQueries() {
        Set<String> all = ids(memory, page(new WfTaskQuery()));
        Set<String> allOnJdbc = ids(jdbc, page(new WfTaskQuery()));
        assertEquals(6, all.size(), "六条都要在。实际: " + all);
        assertEquals(all, allOnJdbc, "两个实现的默认查询也要一致。实际: " + all);
        assertEquals(6L, memory.countTasks(new WfTaskQuery()));
        assertEquals(6L, jdbc.countTasks(new WfTaskQuery()));
    }

    // ==================== 本轮修掉的真缺陷 ====================

    @Test
    @DisplayName("**改期要真的存进库**（UPDATE 路径漏写 DUE_DATE 的回归）")
    void dueDateSurvivesUpdateOnJdbc() {
        Date newDue = shift(3 * DAY);
        WfTask task = jdbc.findTask("T1");
        assertNotNull(task, "应当能读回 T1");
        task.setDueDate(newDue);
        task.nextRevision();
        jdbc.saveTask(task);

        Date stored = jdbc.findTask("T1").getDueDate();
        assertNotNull(stored, "**改期之后 DUE_DATE 不该是空**");
        assertEquals(newDue.getTime(), stored.getTime(),
                "只写进 INSERT 的话，改期接口返回成功、内存里也是新的，"
                        + "**下一次从库里读出来还是旧日期** —— 而超期查询正是按这个日期算的。"
                        + "症状是「改期没报错、清单上的期限没变、超期统计照旧」，"
                        + "三处对不上账，而人只会怀疑是不是接口没调。");
        assertTrue(!ids(jdbc, q -> q.setDueDateTo(new Date(NOW))).contains("T1"),
                "改到三天之后就不该再出现在超期清单里。实际: "
                        + ids(jdbc, q -> q.setDueDateTo(new Date(NOW))));
    }

    @Test
    @DisplayName("**优先级改完也要存得回去**（同一处 UPDATE 路径的另一个字段）")
    void prioritySurvivesUpdateOnJdbc() {
        WfTask task = jdbc.findTask("T4");
        task.setPriority(95);
        task.nextRevision();
        jdbc.saveTask(task);
        assertEquals(95, jdbc.findTask("T4").getPriority());
        assertTrue(ids(jdbc, q -> q.setMinPriority(90)).contains("T4"),
                "改过之后就该出现在高优先级区间里。实际: " + ids(jdbc, q -> q.setMinPriority(90)));
    }

    @Test
    @DisplayName("改期后**两套实现仍然一致**（内存侧本来就对，要防的是 JDBC 侧单边漂移）")
    void bothImplementationsStillAgreeAfterAnUpdate() {
        WfTask task = jdbc.findTask("T1");
        task.setDueDate(shift(5 * DAY));
        task.nextRevision();
        jdbc.saveTask(task);

        WfTask mirror = memory.findTask("T1");
        mirror.setDueDate(shift(5 * DAY));
        mirror.nextRevision();
        memory.saveTask(mirror);

        agree(q -> q.setDueDateTo(new Date(NOW)));
        agree(q -> q.setDueDateTo(shift(DAY)));
    }

    @Test
    @DisplayName("父任务 id **刻意不提供查询条件**：那一列没有任何写入点")
    void parentTaskColumnHasNoWriterSoItIsNotQueryable() {
        // 这是本轮**撤回**的一组条件，记下来防止下一轮又补回去：
        // ZWF_TASK.PARENT_TASK_ID 这一列存在、模型上也有 parentTaskId 字段，
        // 但**没有任何运行时代码写它**（grep setParentTaskId 只有读回和 setter 本身）。
        // ⇒ 提供查询条件只会永远返回空集，那比没有这个条件更坏：
        // 调用方会以为「查不到 = 没有子任务」，而不是「这个功能不存在」。
        Set<String> idsWithNoCondition = ids(jdbc, page(new WfTaskQuery()));
        assertEquals(6, idsWithNoCondition.size(), "不筛任何条件时六条都在。实际: "
                + idsWithNoCondition);
        assertTrue(jdbc.findTask("T1").getParentTaskId() == null,
                "确认这批数据的 parentTaskId 确实都是空的 —— "
                        + "所以任何按它筛的查询都必然是空集");
        Set<String> actualKeys = new LinkedHashSet<>();
        for (WfTask task : jdbc.queryTasks(new WfTaskQuery().setPageSize(50))) {
            actualKeys.add(task.getId());
        }
        assertEquals(6, actualKeys.size());
    }
}