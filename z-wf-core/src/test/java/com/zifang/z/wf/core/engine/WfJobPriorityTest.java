package com.zifang.z.wf.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.expression.WfExpressionEvaluator;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.JdbcWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.service.WfDelegateRegistry;
import com.zifang.z.wf.core.service.WfJobService;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;

/**
 * Job 的<b>优先级</b>（{@code zifang:priority} 的第二个出口）。
 *
 * <p>同一个 {@code priority} 现在有两个去处，判据要把它们分开盯：
 * <ul>
 *   <li><b>任务优先级</b>：待办列表里谁排前面，给人看。</li>
 *   <li><b>job 优先级</b>：队列里谁先被取走执行，给执行器看。</li>
 * </ul>
 *
 * <p>本类盯四件错了都不报错的事：
 * <ol>
 *   <li><b>优先级必须从节点拷进 job</b>，而不是在 job 侧另配一个 ——
 *       各配各的会出现「待办里排最前、流程却最后才跑」。</li>
 *   <li><b>两套存储实现必须给出同一个顺序</b>（priority desc, duedate asc, job_id asc）。
 *       两边不一致的症状最阴：开发期跑内存全绿，换 JDBC 之后偶发乱序，日志里没有异常。</li>
 *   <li><b>存量库要补列</b>：{@code CREATE TABLE IF NOT EXISTS} 对已存在的表不加列，
 *       而 2.0.0 已发布，存量库是真实存在的。补出来的列有 NULL 时读成 0，
 *       而默认优先级是 50 —— 于是升级前排队的 job 全变成"最低优先级"，
 *       症状是"加急的单子插到队尾"，且没有任何报错。</li>
 *   <li><b>排序是开关控制的，不是默认行为</b>：定时器要的是"最早到点的先做"，
 *       按优先级排会让靠后的定时器饿死。</li>
 * </ol>
 */
class WfJobPriorityTest {

    private RecordingPersistence recorder;
    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfJobService jobService;

    /**
     * 记录查询与删除的存储层替身。
     *
     * <p>用继承而不是匿名实现：{@code WfPersistence} 有二十多个方法，
     * 匿名实现要么写不全（编译不过），要么就得每个方法转发一遍（读起来比被测逻辑还长）。
     */
    private static final class RecordingPersistence extends InMemoryWorkflowPersistence {

        private final List<WfJob> deleted = new ArrayList<>();

        /** 每次查询记成「类型:是否按优先级排」，如 {@code ASYNC_BEFORE:true}。 */
        private final List<String> queryShapes = new ArrayList<>();

        @Override
        public List<WfJob> queryJobs(WfJobQuery query) {
            queryShapes.add((query == null || query.getType() == null
                    ? "NONE" : query.getType().name())
                    + ":" + (query != null && query.isOrderedByPriority()));
            return super.queryJobs(query);
        }

        @Override
        public void deleteJob(String id) {
            WfJob job = findJob(id);
            if (job != null) {
                deleted.add(job);
            }
            super.deleteJob(id);
        }
    }

    @BeforeEach
    void setUp() {
        recorder = new RecordingPersistence();
        recorder.initialize();
        repo = recorder;
        repository = new WfRepositoryService(repo);
        WfEngine engine = new WfEngine(new WfBehaviorRegistry(),
                new WfExpressionEvaluator(), new WfIdGenerator.DefaultWfIdGenerator(),
                new WfDelegateRegistry());
        runtime = new WfRuntimeService(repository, repo, engine, new WfHookDispatcher());
        jobService = new WfJobService(repo, runtime);
    }

    // ==================== 优先级从哪来 ====================

    @Test
    @DisplayName("job 的优先级取自宿主节点的 zifang:priority")
    void jobPriorityComesFromTheNode() {
        startAsync(bpmn("prioFromNode", " zifang:priority=\"90\""));
        startAsync(bpmn("prioDefault", ""));

        assertEquals(90, firstJobOf("prioFromNode").getPriority(),
                "加急节点的 job 必须带着那个优先级 —— 队列取它靠的就是这个数");
        assertEquals(WfNode.DEFAULT_PRIORITY, firstJobOf("prioDefault").getPriority(),
                "没写就等于默认，而不是 0：0 会让所有没标注的单子排在最后面，"
                        + "而它们本来都是普通单");
    }

    @Test
    @DisplayName("任务与 job 读同一个数字（不另配）")
    void taskAndJobShareTheSameNumber() {
        String pid = startAsync(bpmn("prioShared", " zifang:priority=\"77\""));

        // **顺序不能反**：先断 job，再续跑，再断任务。
        // 反过来写的话，续跑已经把 job 消费掉了，后面那句按 key 查 job 就是空的 ——
        // 而"查不到 job"看起来像是"优先级没存上"，与真正的原因毫无关系。
        assertEquals(77, firstJobOf("prioShared").getPriority(),
                "job 优先级读的是节点上那一个 —— 配两遍的话改了一处就会出现"
                        + "「待办里排最前、流程却最后才跑」");

        // asyncBefore 的待办是**续跑之后**才建的：启动时 token 只是挂在 job 上。
        // 不先跑一次执行器就去查任务，查到的是空列表 —— 而 IndexOutOfBounds
        // 看起来像是"任务优先级没存上"，同样指不到真正的原因
        asyncOrder();

        assertEquals(77, repo.findTask(
                repo.queryTasks(new com.zifang.z.wf.core.persistence.WfTaskQuery()
                        .setProcessInstanceId(pid).setOpenOnly(true)
                        .setPageNum(1).setPageSize(10)).get(0).getId()).getPriority(),
                "待办优先级读的是同一个节点上的同一个数");
    }

    // ==================== 队列顺序 ====================

    @Test
    @DisplayName("加急的 job 先被续跑，哪怕它排在队尾建出来")
    void urgentAsyncJobRunsFirst() {
        String low1 = startAsync(bpmn("prioLow1", " zifang:priority=\"10\""));
        String low2 = startAsync(bpmn("prioLow2", " zifang:priority=\"10\""));
        String urgent = startAsync(bpmn("prioUrgent", " zifang:priority=\"99\""));

        List<String> order = asyncOrder();

        assertEquals(3, order.size(), "三个 job 都该被续跑。实际 " + order);
        assertEquals(instanceIdOf("prioUrgent"), order.get(0),
                "优先级 99 的必须第一个被续跑。实际顺序 " + order
                        + " —— 它建出来最晚却排在最后，就是「加急的单子插到队尾去了」");
        assertTrue(order.contains(instanceIdOf("prioLow1"))
                        && order.contains(instanceIdOf("prioLow2")),
                "低优先级的两个不能被跳过，只是排在后面。实际 " + order);
    }

    @Test
    @DisplayName("同一批里前置与后置合起来仍然按优先级排（拼接会打乱顺序）")
    void beforeAndAfterJobsShareOneOrdering() {
        // 前置与后置分两次查（WfJobQuery 的 type 是单值），直接 addAll 就等于
        // 「前半段有序、后半段有序、合起来乱序」—— 那正是本条要挡的
        String low = startAsync(afterBpmn("prioAfterLow", " zifang:priority=\"1\""));
        startAsync(bpmn("prioBeforeOnly", " zifang:priority=\"80\""));
        String top = startAsync(afterBpmn("prioAfterTop", " zifang:priority=\"95\""));
        // asyncAfter 的 job 是人办完之后才排出来的，不先办掉的话队列里只有前置那一条
        completeAllOpen(low);
        completeAllOpen(top);

        List<String> order = asyncOrder();

        assertEquals(3, order.size(), "三条都该被续跑。实际 " + order);
        // **后置那条必须比前置那条更高优先级**，否则两条路的差别看不见：
        // 不重排时结果是「前置段[80] + 后置段[95,1]」= [80,95,1]，
        // 重排后是 [95,80,1]。若前置恰好最高（80 > 95 不成立的那种配比，
        // 比如前置 95、后置 80），两种实现给出**同一个顺序**，
        // 于是"有没有重排"这条断言因错误的原因通过。
        assertEquals(instanceIdOf("prioAfterTop"), order.get(0),
                "整体重排之后，优先级最高的那条（后置的 95）必须排第一 —— "
                        + "它排在第二说明两次查询的结果只是被拼在一起、没有重排。"
                        + "实际 " + order);
        assertEquals(instanceIdOf("prioBeforeOnly"), order.get(1),
                "前置那条 80 应当夹在 95 与 1 之间。实际 " + order);
    }

    @Test
    @DisplayName("执行器的两次查询都带上了优先级开关（排了序没人用等于没排）")
    void executorAsksBothQueriesToSortByPriority() {
        startAsync(bpmn("shapeBefore", " zifang:priority=\"80\""));

        asyncOrder();

        // **两次都要断**：前置与后置是分开查的，只断一次的话，
        // 去掉其中一次的开关照样绿 —— 而那正好是 J09 那条变异打绿的原因
        assertTrue(recorder.queryShapes.contains("ASYNC_BEFORE:true"),
                "异步前置的查询必须带优先级开关。实际发出去的查询: " + recorder.queryShapes);
        assertTrue(recorder.queryShapes.contains("ASYNC_AFTER:true"),
                "异步后置的查询也必须带 —— 它是另一次查询，不受前一次的影响。实际: "
                        + recorder.queryShapes);
    }

    @Test
    @DisplayName("不启用开关时保持既有顺序（定时器仍是「最早到点的先做」）")
    void orderingIsOptIn() {
        // **启动顺序要按「默认排序」来排，不能随手写**：
        // 默认是「最早到点的先做」，所以先启动的那个排前面。
        // 反过来写的话 low 后启动就排后面，默认顺序与优先级顺序恰好一致，
        // 于是「开关到底有没有生效」这条断言两种实现都能过 —— 那是白测。
        startAsync(bpmn("optInLow", " zifang:priority=\"1\""));
        startAsync(bpmn("optInUrgent", " zifang:priority=\"99\""));
        String urgent = instanceIdOf("optInUrgent");
        String low = instanceIdOf("optInLow");

        List<WfJob> plain = repo.queryJobs(new WfJobQuery()
                .setType(WfJobType.ASYNC_BEFORE)
                .setPageNum(1).setPageSize(50));

        List<String> plainOrder = processIdsOf(plain);
        assertTrue(plainOrder.indexOf(low) < plainOrder.indexOf(urgent),
                "不传开关时按到期时刻正序 —— 建出来早的在前。"
                        + "默认就按优先级排的话，「最早到点的先做」这条就没了，"
                        + "而定时器扫描器靠的就是它。实际 " + plainOrder);

        List<WfJob> ordered = repo.queryJobs(new WfJobQuery()
                .setType(WfJobType.ASYNC_BEFORE)
                .setOrderByPriority(Boolean.TRUE)
                .setPageNum(1).setPageSize(50));
        List<String> byPriority = processIdsOf(ordered);
        assertTrue(byPriority.indexOf(urgent) < byPriority.indexOf(low),
                "开了开关才按优先级。实际 " + byPriority);
    }

    @Test
    @DisplayName("同级仍按到期时刻正序（同级不许变成随机顺序）")
    void samePriorityFallsBackToDuedate() {
        WfJob older = newJob("j-older", 50, 1_000L);
        WfJob newer = newJob("j-newer", 50, 2_000L);
        repo.saveJob(older);
        repo.saveJob(newer);

        List<WfJob> got = repo.queryJobs(new WfJobQuery()
                .setType(WfJobType.ASYNC_BEFORE)
                .setOrderByPriority(Boolean.TRUE)
                .setPageNum(1).setPageSize(50));

        assertEquals("j-older", got.get(0).getId(),
                "同优先级时先办早排队的 —— 优先级不该让人插队到同级前面，"
                        + "也不该变成随机（随机的话每次跑的顺序都不一样，没法复现）");
    }

    // ==================== 两套实现同一把尺子 ====================

    @Test
    @DisplayName("内存与 JDBC 给出同一个优先级顺序")
    void bothImplementationsAgreeOnTheOrder() {
        String[] ids = new String[]{"z-c", "z-a", "z-b"};
        int[] priorities = new int[]{50, 99, 99};
        for (int i = 0; i < ids.length; i++) {
            WfJob job = newJob(ids[i], priorities[i], 1_000L + i);
            repo.saveJob(job);
            WfJob copy = newJob(ids[i], priorities[i], 1_000L + i);
            jdbc().saveJob(copy);
        }

        List<String> inMemory = idsOf(repo.queryJobs(new WfJobQuery()
                .setOrderByPriority(Boolean.TRUE).setPageNum(1).setPageSize(50)));
        List<String> inJdbc = idsOf(jdbc().queryJobs(new WfJobQuery()
                .setOrderByPriority(Boolean.TRUE).setPageNum(1).setPageSize(50)));

        assertEquals(inMemory, inJdbc,
                "两套实现的顺序必须逐条一致 —— 不一致的症状是「开发期跑内存全绿，"
                        + "换 JDBC 之后偶发乱序」，而日志里没有任何异常。内存 " + inMemory
                        + " / JDBC " + inJdbc);
        assertEquals("z-a", inMemory.get(0),
                "同优先级的按 id 排（这里 z-a 早于 z-b），实际 " + inMemory);
    }

    @Test
    @DisplayName("优先级能存进 JDBC 再读回来，且更新时不被抹掉")
    void prioritySurvivesJdbcRoundTripAndUpdate() {
        WfJob job = newJob("j-prio-rt", 88, 1_000L);
        job.setElementId("e1");
        job.setProcessInstanceId("p1");
        jdbc().saveJob(job);

        assertEquals(88, jdbc().findJob("j-prio-rt").getPriority(),
                "写进去要读得回来 —— 读不回来的话优先级只对内存实现有效");

        WfJob loaded = jdbc().findJob("j-prio-rt");
        loaded.setRetries(2);
        loaded.nextRevision();
        jdbc().saveJob(loaded);

        assertEquals(88, jdbc().findJob("j-prio-rt").getPriority(),
                "更新时必须带上 PRIORITY 列 —— 流程推进会把 job 查出来改一改再存回去，"
                        + "漏掉这一列等于每次更新都把优先级抹回默认值，"
                        + "而症状是「第一次是对的、推进一次就失效了」");
    }

    @Test
    @DisplayName("存量库没有 PRIORITY 列时 initialize 补上，且读出是默认 50 而不是 0")
    void legacyTableGetsTheColumn() {
        DataSource ds = jdbcDataSource();
        // 先用一个**不含 PRIORITY 的**旧 DDL 建表，模拟 2.0.0 已部署的库
        createLegacyTable(ds);

        JdbcWorkflowPersistence fresh = new JdbcWorkflowPersistence(ds);
        fresh.initialize();

        WfJob job = newJob("j-legacy", 0, 1_000L);
        job.setProcessInstanceId("p1");
        // 绕开 saveJob 直接往老表插一行，模拟升级前排队的 job
        insertLegacyRow(ds, "j-legacy");

        WfJob loaded = fresh.findJob("j-legacy");
        assertNotNull(loaded, "补列后必须读得回来");
        assertEquals(WfNode.DEFAULT_PRIORITY, loaded.getPriority(),
                "补出来的列在存量行上是 NULL，`rs.getInt` 读成 0 —— "
                        + "而默认优先级是 50，于是升级前排队的 job 全变成「最低优先级」，"
                        + "症状是加急的单子插到队尾，且没有任何报错");
    }

    // ==================== 夹具 ====================

    /**
     * 一条「用户任务 + 异步」的单节点流程。
     *
     * @param extraAttrs 加在 userTask 上的额外属性（优先级 / asyncBefore / asyncAfter）
     */
    private static String bpmn(String processId, String extraAttrs) {
        return bpmnWithAttrs(processId, " zifang:asyncBefore=\"true\"" + extraAttrs);
    }

    private static String afterBpmn(String processId, String extraAttrs) {
        return bpmnWithAttrs(processId, " zifang:asyncAfter=\"true\"" + extraAttrs);
    }

    private static String bpmnWithAttrs(String processId, String attrs) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"" + processId + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\""
                + attrs + "/>\n"
                + "    <endEvent id=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
    }

    private String startAsync(String xml) {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(xml));
        // businessKey 直接用 processId：判据要按 key 反查实例，
        // 换成别的东西（比如 xml 长度）之后就查不到了，而症状是
        // "夹具里没有那条数据"，看起来像实现没建出 job
        return runtime.startProcessInstance(definition, definition.getKey(),
                "alice", null, new HashMap<String, Object>());
    }

    /**
     * 把某实例上所有待办办掉。
     *
     * <p>asyncAfter 的 job 是<b>人办完之后</b>才排出来的：启动后 token 停在
     * userTask 上，一个 job 都没有。不先办掉就断言队列顺序，
     * 断言问的其实是"队列是不是空的"。
     */
    private void completeAllOpen(String pid) {
        for (com.zifang.z.wf.core.model.WfTask task : repo.queryTasks(
                new com.zifang.z.wf.core.persistence.WfTaskQuery()
                        .setProcessInstanceId(pid).setOpenOnly(true)
                        .setPageNum(1).setPageSize(50))) {
            runtime.completeTask(task.getId(), task.getAssignee(), "办完",
                    new HashMap<String, Object>());
        }
    }

    /**
     * 跑一次异步执行器，返回**被消费的 job 的业务 key 先后顺序**。
     *
     * <p>顺序从 {@code deleteJob} 的调用序列里取：执行器续跑成功后一定会删那条 job，
     * 所以这个序列就是"实际被执行的顺序"，而不是"查出来的顺序"。
     * 断后者的话，即使执行器拿到列表后自己又换了个顺序，这条判据照样绿。
     */
    private List<String> asyncOrder() {
        recorder.deleted.clear();
        jobService = new WfJobService(recorder, runtime);
        jobService.executeAsyncJobs(new Date(System.currentTimeMillis() + 1000L));
        List<String> keys = new ArrayList<>();
        for (WfJob job : recorder.deleted) {
            keys.add(job.getProcessInstanceId());
        }
        return keys;
    }

    private WfJob firstJobOf(String businessKey) {
        for (com.zifang.z.wf.core.model.WfProcessInstance instance
                : repo.queryProcessInstances(new com.zifang.z.wf.core.persistence
                .WfProcessInstanceQuery().setBusinessKey(businessKey)
                .setPageNum(1).setPageSize(10))) {
            List<WfJob> jobs = repo.queryJobs(new WfJobQuery()
                    .setProcessInstanceId(instance.getId()).setPageNum(1).setPageSize(10));
            if (!jobs.isEmpty()) {
                return jobs.get(0);
            }
        }
        throw new AssertionError("没有找到 job: " + businessKey);
    }

    /** 按 businessKey 反查实例 id —— 夹具的 businessKey 就是 processId。 */
    private String instanceIdOf(String businessKey) {
        for (com.zifang.z.wf.core.model.WfProcessInstance instance
                : repo.queryProcessInstances(
                        new com.zifang.z.wf.core.persistence.WfProcessInstanceQuery()
                                .setBusinessKey(businessKey).setPageNum(1).setPageSize(10))) {
            return instance.getId();
        }
        throw new AssertionError("没有找到实例: " + businessKey);
    }

    private List<String> processIdsOf(List<WfJob> jobs) {
        List<String> ids = new ArrayList<>();
        for (WfJob job : jobs) {
            ids.add(job.getProcessInstanceId());
        }
        return ids;
    }

    private WfJob newJob(String id, int priority, long duedateOffset) {
        WfJob job = new WfJob();
        job.setId(id);
        job.setType(WfJobType.ASYNC_BEFORE);
        job.setProcessInstanceId("p-" + id);
        job.setExecutionId("e-" + id);
        job.setPriority(priority);
        job.setDuedate(new Date(1_700_000_000_000L + duedateOffset));
        job.setCreateTime(new Date(1_700_000_000_000L + duedateOffset));
        job.setRetries(WfJob.DEFAULT_RETRIES);
        return job;
    }

    private List<String> idsOf(List<WfJob> jobs) {
        List<String> ids = new ArrayList<>();
        for (WfJob job : jobs) {
            ids.add(job.getId());
        }
        return ids;
    }

    // ==================== JDBC 夹具 ====================

    private DataSource jdbcDs;
    private JdbcWorkflowPersistence jdbcPersistence;

    private DataSource jdbcDataSource() {
        if (jdbcDs == null) {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:jobprio" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
            ds.setUser("sa");
            ds.setPassword("");
            jdbcDs = ds;
        }
        return jdbcDs;
    }

    private JdbcWorkflowPersistence jdbc() {
        if (jdbcPersistence == null) {
            jdbcPersistence = new JdbcWorkflowPersistence(jdbcDataSource());
            jdbcPersistence.initialize();
        }
        return jdbcPersistence;
    }

    private void createLegacyTable(DataSource ds) {
        Statement st = null;
        Connection c = null;
        try {
            c = ds.getConnection();
            st = c.createStatement();
            st.execute("CREATE TABLE IF NOT EXISTS ZWF_JOB ("
                    + "JOB_ID VARCHAR(128) NOT NULL,"
                    + "PROC_ID VARCHAR(128) NOT NULL,"
                    + "EXEC_ID VARCHAR(128),"
                    + "ELEMENT_ID VARCHAR(128),"
                    + "ATTACHED_TO VARCHAR(128),"
                    + "JOB_TYPE VARCHAR(16) NOT NULL DEFAULT 'TIMER',"
                    + "TOPIC VARCHAR(128),"
                    + "LOCKED_BY VARCHAR(128),"
                    + "LOCK_AT TIMESTAMP,"
                    + "DUEDATE TIMESTAMP,"
                    + "RETRIES INT,"
                    + "EXCEPTION_MSG VARCHAR(2048),"
                    // **没有 SUBSCRIPTION_NAME / CYCLE_INDEX / PRIORITY**：
                    // 只建最小集，才能验证 initialize 真的是去"补"而不是靠新 DDL 建全
                    + "CREATE_TIME TIMESTAMP,"
                    + "LAST_FAIL_TIME TIMESTAMP,"
                    + "REV INT)");
        } catch (SQLException e) {
            throw new IllegalStateException("构造老库失败", e);
        } finally {
            close(st);
            close(c);
        }
    }

    private void insertLegacyRow(DataSource ds, String jobId) {
        Statement st = null;
        Connection c = null;
        try {
            c = ds.getConnection();
            st = c.createStatement();
            st.execute("INSERT INTO ZWF_JOB (JOB_ID, PROC_ID, JOB_TYPE, RETRIES, REV)"
                    + " VALUES ('" + jobId + "','p-legacy','TIMER',3,0)");
        } catch (SQLException e) {
            throw new IllegalStateException("插入老库数据失败", e);
        } finally {
            close(st);
            close(c);
        }
    }

    private static void close(Statement st) {
        if (st != null) {
            try {
                st.close();
            } catch (SQLException ignored) {
                // 关不掉就随它去：close 的失败不该盖掉真正的断言结果
            }
        }
    }

    private static void close(Connection c) {
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // 同上
            }
        }
    }
}