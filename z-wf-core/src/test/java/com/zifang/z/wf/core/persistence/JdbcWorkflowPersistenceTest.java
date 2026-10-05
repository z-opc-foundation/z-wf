package com.zifang.z.wf.core.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;

/**
 * {@link JdbcWorkflowPersistence} 对真实数据库的验证。
 *
 * <p><b>为什么必须真跑数据库</b>：JDBC 实现里三处"编译通过≠运行正确"的经典陷阱：
 * <ol>
 *   <li><b>DDL 方言</b>：{@code CREATE TABLE IF NOT EXISTS} / {@code TEXT} / {@code CLOB}
 *       在 MySQL 与 H2 上语义不同，索引 DDL 也可能报语法错</li>
 *   <li><b>乐观锁的受影响行数</b>：{@code UPDATE ... WHERE id=? AND revision=?} 在 0 行受影响时
 *       必须抛 {@link WfOptimisticLockException}；用内存 Map 无法验证这一点</li>
 *   <li><b>时间类型映射</b>：{@link Date} ↔ {@code TIMESTAMP} 往返（这正是内存实现
 *       踩过 JsonUtil Date 坑的地方，必须在 JDBC 侧确认干净）</li>
 * </ol>
 *
 * @author zifang
 */
class JdbcWorkflowPersistenceTest {

    private DataSource dataSource;
    private JdbcWorkflowPersistence persistence;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        // 每轮用独立库名，避免表残留影响断言
        String name = "wf_test_" + (COUNTER.incrementAndGet());
        ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        this.dataSource = ds;
        this.persistence = new JdbcWorkflowPersistence(ds);
        this.persistence.initialize();
    }

    private static final AtomicInteger COUNTER = new AtomicInteger();

    // ==================== DDL ====================

    @Test
    @DisplayName("initialize 幂等：重复调用不报错（表已存在）")
    void initializeIsIdempotent() {
        persistence.initialize();
        // 第二次建表若 DDL 不可重复执行，H2 会在此抛 JdbcSQLSyntaxErrorException
        persistence.initialize();
        // 幂等之后表仍可用：写入并读回
        WfProcessInstance instance = new WfProcessInstance("idem", "k", "k:1");
        persistence.saveProcessInstance(instance);
        assertNotNull(persistence.findProcessInstance("idem"));
    }

    // ==================== 定义 ====================

    @Test
    @DisplayName("定义往返：图结构（节点/连线/类型）落库后不丢失，索引可重建")
    void definitionRoundTrip() {
        WfDefinition definition = new WfDefinition("leaveProcess", "请假流程");
        definition.setCategory("审批");
        definition.setDescription("带网关的审批");
        WfNodeBundle bundle = WfNodeBundle.of(definition);
        definition.buildIndex();

        persistence.saveDefinition(definition);

        WfDefinition loaded = persistence.findLatestDefinition("leaveProcess");
        assertNotNull(loaded);
        assertEquals("请假流程", loaded.getName());
        assertEquals("审批", loaded.getCategory());
        assertEquals(3, loaded.getNodes().size());
        assertEquals(2, loaded.getFlows().size());
        assertEquals(com.zifang.z.wf.core.definition.WfNodeType.USER_TASK,
                loaded.node("task1").getType());
        assertEquals("manager", loaded.node("task1").getAssignee());
        // 索引从库里读回后必须可用
        assertNotNull(loaded.startNode());
        assertEquals(1, loaded.outgoingFlows("start1").size());
        assertNotNull(bundle);
    }

    @Test
    @DisplayName("定义版本：latest 取最大版本，versions 倒序，按分类过滤可用")
    void definitionVersionsAndCategory() {
        for (int v = 1; v <= 3; v++) {
            WfDefinition definition = new WfDefinition("k", "n" + v);
            definition.setVersion(v);
            definition.setCategory("审批");
            WfNodeBundle.of(definition);
            definition.buildIndex();
            persistence.saveDefinition(definition);
        }
        assertEquals(3, persistence.findLatestDefinition("k").getVersion());
        assertEquals(2, persistence.findDefinition("k", 2).getVersion());
        assertEquals(3, persistence.findDefinitionVersions("k").size());
        assertEquals(3, persistence.findDefinitionVersions("k").get(0).getVersion());
        assertEquals(1, persistence.findAllDefinitions().size(), "每个 key 只取最新版本");
        assertEquals(1, persistence.findDefinitionsByCategory("审批").size());
        assertTrue(persistence.findDefinitionsByCategory("不存在").isEmpty());
    }

    // ==================== 流程实例 ====================

    @Test
    @DisplayName("实例往返：Time / Map / enum 全部正确（Date 是 JDBC 侧的重点风险）")
    void processInstanceRoundTrip() {
        WfProcessInstance instance = new WfProcessInstance("p1", "leaveProcess", "leaveProcess:1");
        instance.setDefinitionVersion(1);
        instance.setBusinessKey("ORDER-1");
        instance.setStartUserId("u1");
        instance.setStartDeptId("d1");
        instance.setCategory("审批");
        instance.setStatus(WfProcessStatus.ACTIVE);
        instance.setStartTime(new Date(1700000000000L));
        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 3);
        vars.put("reason", "vacation");
        instance.setVariables(vars);
        persistence.saveProcessInstance(instance);

        WfProcessInstance loaded = persistence.findProcessInstance("p1");
        assertNotNull(loaded);
        assertEquals(WfProcessStatus.ACTIVE, loaded.getStatus());
        assertEquals("ORDER-1", loaded.getBusinessKey());
        assertEquals("u1", loaded.getStartUserId());
        assertNotNull(loaded.getStartTime(), "Date 必须能往返，不能变 null");
        assertEquals(1700000000000L, loaded.getStartTime().getTime());
        assertEquals(3, loaded.getVariables().get("days"));
        assertEquals("vacation", loaded.getVariables().get("reason"));
        assertEquals(0, loaded.getRevision());
    }

    @Test
    @DisplayName("乐观锁：UPDATE 受影响 0 行时抛冲突（内存实现无法覆盖这一点）")
    void optimisticLockOnUpdate() {
        WfProcessInstance instance = new WfProcessInstance("p2", "k", "k:1");
        instance.setStatus(WfProcessStatus.ACTIVE);
        persistence.saveProcessInstance(instance);

        // 正确的一步
        WfProcessInstance good = persistence.findProcessInstance("p2");
        good.setStatus(WfProcessStatus.ACTIVE);
        good.nextRevision();
        persistence.saveProcessInstance(good);

        // 拿着旧 revision 再写 ⇒ 必须抛
        WfProcessInstance stale = persistence.findProcessInstance("p2");
        stale.setStatus(WfProcessStatus.SUSPENDED);
        assertThrows(WfOptimisticLockException.class, () -> persistence.saveProcessInstance(stale));
    }

    @Test
    @DisplayName("实例查询：按业务键 / 发起人 / 状态过滤 + 排序")
    void processInstanceQueries() {
        for (int i = 0; i < 3; i++) {
            WfProcessInstance instance = new WfProcessInstance("p" + i, "k", "k:1");
            instance.setBusinessKey("BIZ" + i);
            instance.setStartUserId(i == 0 ? "alice" : "bob");
            instance.setStatus(i == 0 ? WfProcessStatus.COMPLETED : WfProcessStatus.ACTIVE);
            instance.setStartTime(new Date(1000L * i));
            instance.setResult(i == 0 ? "approved" : null);
            persistence.saveProcessInstance(instance);
        }
        assertEquals(1, persistence.findProcessInstancesByBusinessKey("BIZ1").size());
        assertEquals(1, persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setStartUserId("alice")).size());
        assertEquals(2, persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setStatus(WfProcessStatus.ACTIVE)).size());
        assertEquals(1, persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setResult("approved")).size());
        // 按 START_TIME 倒序：p2 在最前
        assertEquals("p2", persistence.queryProcessInstances(new WfProcessInstanceQuery())
                .get(0).getId());
        // 分页
        assertEquals(2, persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setPageNum(1).setPageSize(2)).size());
        assertEquals(1, persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setPageNum(2).setPageSize(2)).size());
    }

    // ==================== token ====================

    @Test
    @DisplayName("token 往返：state / arrived 列表 / 父子关系不丢失")
    void executionRoundTrip() {
        WfExecution execution = new WfExecution("e1", "p1", "task1");
        execution.setParentId("e0");
        execution.setState(WfExecution.State.WAITING);
        execution.setChild(true);
        execution.setEnteredTime(new Date(1700000000000L));
        execution.getVariables().put("amount", 500);
        execution.arriveAt("gw1");
        execution.arriveAt("gw2");
        persistence.saveExecution(execution);

        WfExecution loaded = persistence.findExecution("e1");
        assertNotNull(loaded);
        assertEquals("e0", loaded.getParentId());
        assertEquals(WfExecution.State.WAITING, loaded.getState());
        assertTrue(loaded.isChild());
        assertNotNull(loaded.getEnteredTime());
        assertEquals(2, loaded.getArrivedActivities().size());
        assertEquals(500, loaded.getVariables().get("amount"));

        assertEquals(1, persistence.findExecutionsByProcessInstance("p1").size());
        persistence.deleteExecution("e1");
        assertNull(persistence.findExecution("e1"));
    }

    @Test
    @DisplayName("token 更新走 UPDATE 分支（幂等，不报主键冲突）")
    void executionUpdate() {
        WfExecution execution = new WfExecution("e2", "p1", "task1");
        persistence.saveExecution(execution);
        execution.setActivityId("task2");
        execution.setState(WfExecution.State.ENDED);
        persistence.saveExecution(execution);
        assertEquals("task2", persistence.findExecution("e2").getActivityId());
        assertEquals(WfExecution.State.ENDED, persistence.findExecution("e2").getState());
    }

    // ==================== 任务 ====================

    @Test
    @DisplayName("任务往返：候选项 / Date / 委派字段全部保真")
    void taskRoundTrip() {
        WfTask task = new WfTask();
        task.setId("t1");
        task.setProcessInstanceId("p1");
        task.setExecutionId("e1");
        task.setDefinitionId("task1");
        task.setName("经理审批");
        task.setType("userTask");
        task.setFormKey("leaveForm");
        task.setCategory("审批");
        task.setAssignee("manager");
        task.setOwner("staff");
        task.setCandidateUsers(java.util.Arrays.asList("u1", "u2"));
        task.setCandidateGroups(java.util.Arrays.asList("g1"));
        task.setStatus(WfTask.Status.DELEGATED);
        task.setPriority(70);
        task.setCreateTime(new Date(1700000000000L));
        task.setDueDate(new Date(1700003600000L));
        task.getVariables().put("k", "v");
        persistence.saveTask(task);

        WfTask loaded = persistence.findTask("t1");
        assertNotNull(loaded);
        assertEquals("经理审批", loaded.getName());
        assertEquals("leaveForm", loaded.getFormKey());
        assertEquals("manager", loaded.getAssignee());
        assertEquals("staff", loaded.getOwner());
        assertEquals("staff", loaded.effectiveHandler());
        assertEquals(WfTask.Status.DELEGATED, loaded.getStatus());
        assertEquals(70, loaded.getPriority());
        assertEquals(2, loaded.getCandidateUsers().size());
        assertEquals(1, loaded.getCandidateGroups().size());
        assertNotNull(loaded.getCreateTime());
        assertNotNull(loaded.getDueDate());
        assertEquals("v", loaded.getVariables().get("k"));
    }

    @Test
    @DisplayName("任务乐观锁：0 行受影响抛冲突")
    void taskOptimisticLock() {
        WfTask task = new WfTask();
        task.setId("t2");
        task.setStatus(WfTask.Status.ASSIGNED);
        persistence.saveTask(task);

        WfTask good = persistence.findTask("t2");
        good.setStatus(WfTask.Status.COMPLETED);
        good.nextRevision();
        persistence.saveTask(good);

        WfTask stale = persistence.findTask("t2");
        stale.setStatus(WfTask.Status.CANCELLED);
        assertThrows(WfOptimisticLockException.class, () -> persistence.saveTask(stale));
    }

    @Test
    @DisplayName("任务查询：待办（assignee/owner 合并）/ 已办 / 分页 / 排序")
    void taskQueries() {
        WfTask t1 = newTask("t-a", "u1", WfTask.Status.ASSIGNED, 30, 1000L);
        WfTask t2 = newTask("t-b", "u1", WfTask.Status.ASSIGNED, 50, 2000L);
        WfTask delegated = newTask("t-c", "u1", WfTask.Status.DELEGATED, 50, 3000L);
        delegated.setOwner("u2");
        WfTask done = newTask("t-d", "u1", WfTask.Status.COMPLETED, 50, 4000L);
        done.setCompleterId("u1");
        for (WfTask t : new WfTask[]{t1, t2, delegated, done}) {
            persistence.saveTask(t);
        }

        // 待办：未完成的 3 个，未完成优先 + 优先级高优先
        List<WfTask> todo = persistence.queryTasks(new WfTaskQuery()
                .setAssignee("u1").setOpenOnly(true));
        assertEquals(3, todo.size());
        // t-b / t-c 优先级都是 50，t-a 是 30 ⇒ 两个 50 的排在前面，但彼此按创建时间倒序
        // （t-c 建于 3000 > t-b 建于 2000 ⇒ t-c 在前）
        assertEquals(50, todo.get(0).getPriority());
        assertEquals(50, todo.get(1).getPriority());
        assertEquals(30, todo.get(2).getPriority());
        assertEquals("t-c", todo.get(0).getId());
        assertEquals("t-b", todo.get(1).getId());
        assertEquals("t-a", todo.get(2).getId());
        // 已完成（t-d）必须被 openOnly 排除
        assertTrue(todo.stream().noneMatch(t -> "t-d".equals(t.getId())));

        // owner 查询命中委派态
        assertEquals(1, persistence.queryTasks(new WfTaskQuery()
                .setOwner("u2").setOpenOnly(true)).size());

        // 已办
        List<WfTask> finished = persistence.queryTasks(new WfTaskQuery()
                .setCompleterId("u1").setCompletedOnly(true));
        assertEquals(1, finished.size());
        assertEquals("t-d", finished.get(0).getId());

        // 分页
        assertEquals(2, persistence.queryTasks(new WfTaskQuery()
                .setPageNum(1).setPageSize(2)).size());
        assertEquals(2, persistence.queryTasks(new WfTaskQuery()
                .setPageNum(2).setPageSize(2)).size());
    }

    // ==================== 历史 ====================

    @Test
    @DisplayName("活动历史与评论：按时间正序返回（轨迹顺序是审批页的核心）")
    void historyAndComments() {
        for (int i = 0; i < 3; i++) {
            WfActivityInstance activity = new WfActivityInstance();
            activity.setId("a" + i);
            activity.setProcessInstanceId("p1");
            activity.setActivityName("节点" + i);
            // 故意倒着插：1500 / 2000 / 1000 毫秒相对 startTime=0
            // ⇒ 只有真正按 START_TIME 排序才能拿到 节点0 → 节点2 → 节点1
            activity.setStartTime(new Date(1000L * (3 - i)));
            activity.setOutcome("completed");
            activity.getVariables().put("i", i);
            persistence.saveActivityInstance(activity);
        }
        List<WfActivityInstance> trail = persistence.findActivityInstances("p1");
        assertEquals(3, trail.size());
        assertEquals("节点2", trail.get(0).getActivityName(), "必须按时间正序，而非插入序");
        assertEquals("节点1", trail.get(1).getActivityName());
        assertEquals("节点0", trail.get(2).getActivityName());
        assertEquals(0, trail.get(2).getVariables().get("i"));

        persistence.saveComment(new WfComment("c1", "p1", "u1", "comment", "同意"));
        persistence.saveComment(new WfComment("c2", "p1", "u1", "comment", "已阅"));
        List<WfComment> comments = persistence.findComments("p1");
        assertEquals(2, comments.size());
        // 两条评论时间戳几乎相同（毫秒精度内），顺序不保证，断言集合而非顺序
        assertTrue(comments.stream().anyMatch(c -> "同意".equals(c.getContent())));
        assertTrue(comments.stream().anyMatch(c -> "已阅".equals(c.getContent())));
    }

    @Test
    @DisplayName("clear 清空全部 6 张表")
    void clearWipesAll() {
        WfProcessInstance instance = new WfProcessInstance("p9", "k", "k:1");
        persistence.saveProcessInstance(instance);
        persistence.clear();
        assertNull(persistence.findProcessInstance("p9"));
        assertTrue(persistence.queryProcessInstances(new WfProcessInstanceQuery()).isEmpty());
    }

    private WfTask newTask(String id, String assignee, WfTask.Status status, int priority, long time) {
        WfTask task = new WfTask();
        task.setId(id);
        task.setAssignee(assignee);
        task.setStatus(status);
        task.setPriority(priority);
        task.setCreateTime(new Date(time));
        return task;
    }

    /**
     * 最小图：start1 → task1 → end1。
     */
    static final class WfNodeBundle {
        static WfNodeBundle of(WfDefinition definition) {
            definition.setNodes(new java.util.ArrayList<com.zifang.z.wf.core.definition.WfNode>(
                    java.util.Arrays.asList(
                            new com.zifang.z.wf.core.definition.WfNode("start1", "开始",
                                    com.zifang.z.wf.core.definition.WfNodeType.START_EVENT),
                            userTask("task1"),
                            new com.zifang.z.wf.core.definition.WfNode("end1", "结束",
                                    com.zifang.z.wf.core.definition.WfNodeType.END_EVENT))));
            com.zifang.z.wf.core.definition.WfFlow f1 =
                    new com.zifang.z.wf.core.definition.WfFlow("start1", "task1");
            f1.setId("f1");
            com.zifang.z.wf.core.definition.WfFlow f2 =
                    new com.zifang.z.wf.core.definition.WfFlow("task1", "end1");
            f2.setId("f2");
            definition.setFlows(new java.util.ArrayList<com.zifang.z.wf.core.definition.WfFlow>(
                    java.util.Arrays.asList(f1, f2)));
            return new WfNodeBundle();
        }

        private static com.zifang.z.wf.core.definition.WfNode userTask(String id) {
            com.zifang.z.wf.core.definition.WfNode node =
                    new com.zifang.z.wf.core.definition.WfNode(id, "经理审批",
                            com.zifang.z.wf.core.definition.WfNodeType.USER_TASK);
            node.setAssignee("manager");
            return node;
        }
    }

    // ==================== 委派链持久化 ====================

    @Test
    @DisplayName("委派链能落库并在重读后还原（审计能力必须真的存得住）")
    void delegateChainSurvivesReload() {
        WfTask task = new WfTask();
        task.setId("t-chain-1");
        task.setProcessInstanceId("p1");
        task.setName("经理审批");
        task.setAssignee("manager");
        task.setStatus(WfTask.Status.ASSIGNED);
        task.setCreateTime(new Date());
        task.getDelegateChain().add(new WfTask.DelegateHop("manager", "staff1", new Date(1700000000000L)));
        task.getDelegateChain().add(new WfTask.DelegateHop("staff1", "staff2", new Date(1700000001000L)));
        task.nextRevision();
        persistence.saveTask(task);

        // 关键：重新从库里读，而不是复用内存里那个对象
        WfTask reloaded = persistence.findTask("t-chain-1");
        assertNotNull(reloaded, "任务应能读回");
        assertEquals(2, reloaded.getDelegateChain().size(),
                "委派链必须跨重读存活 —— 以前这一列没建，读回来是空的，"
                        + "症状是 delegate() 看着记了审计、实际一重读就没");
        assertEquals("manager", reloaded.getDelegateChain().get(0).getFrom());
        assertEquals("staff1", reloaded.getDelegateChain().get(0).getTo());
        assertEquals("staff2", reloaded.getDelegateChain().get(1).getTo());
        assertNotNull(reloaded.getDelegateChain().get(0).getTime(), "时间也要存住");
    }

    @Test
    @DisplayName("委派链在 UPDATE 路径上也能续写（delegate 走的是已存在任务的更新）")
    void delegateChainAppendedOnUpdate() {
        WfTask task = new WfTask();
        task.setId("t-chain-2");
        task.setProcessInstanceId("p1");
        task.setName("经理审批");
        task.setAssignee("manager");
        task.setStatus(WfTask.Status.ASSIGNED);
        task.setCreateTime(new Date());
        task.nextRevision();
        persistence.saveTask(task);
        assertEquals(0, persistence.findTask("t-chain-2").getDelegateChain().size());

        // 再委派一次：走 UPDATE 分支
        WfTask again = persistence.findTask("t-chain-2");
        again.setOwner("staff1");
        again.setStatus(WfTask.Status.DELEGATED);
        again.getDelegateChain().add(new WfTask.DelegateHop("manager", "staff1", new Date()));
        again.nextRevision();
        persistence.saveTask(again);

        WfTask after = persistence.findTask("t-chain-2");
        assertEquals("staff1", after.getOwner());
        assertEquals(1, after.getDelegateChain().size(),
                "UPDATE 分支若漏了这一列，委派链会在第二次委派后丢失");
    }

    @Test
    @DisplayName("没委派过时链为空（不会因空值解析出脏数据）")
    void emptyDelegateChainIsClean() {
        WfTask task = new WfTask();
        task.setId("t-chain-3");
        task.setProcessInstanceId("p1");
        task.setName("审批");
        task.setStatus(WfTask.Status.CREATED);
        task.setCreateTime(new Date());
        task.nextRevision();
        persistence.saveTask(task);

        WfTask reloaded = persistence.findTask("t-chain-3");
        assertNotNull(reloaded.getDelegateChain());
        assertEquals(0, reloaded.getDelegateChain().size());
    }

    @Test
    @DisplayName("既有表（2.0.0 之前建的、没有 DELEGATE_CHAIN 列）能被补列后正常读写")
    void legacyTableGetsColumnOnInitialize() {
        // 模拟老库：setUp() 已经建过带 DELEGATE_CHAIN 的表，
        // 这里先 DROP 再按 2.0.0 之前的结构建一张缺列的（CREATE TABLE IF NOT EXISTS
        // 不会触发，正好复现真实升级场景）
        try {
            java.sql.Connection c = dataSource.getConnection();
            java.sql.Statement st = c.createStatement();
            try {
                st.execute("DROP TABLE ZWF_TASK");
                st.execute("CREATE TABLE ZWF_TASK ("
                        + "TASK_ID VARCHAR(128) NOT NULL PRIMARY KEY,"
                        + "PROC_ID VARCHAR(128), EXEC_ID VARCHAR(128), DEF_ID VARCHAR(128),"
                        + "TASK_NAME VARCHAR(512), TASK_TYPE VARCHAR(64), FORM_KEY VARCHAR(128),"
                        + "CATEGORY VARCHAR(128), ASSIGNEE VARCHAR(128), OWNER VARCHAR(128),"
                        + "CANDIDATE_USERS TEXT, CANDIDATE_GROUPS TEXT, STATUS VARCHAR(16),"
                        + "PRIORITY INTEGER, CREATE_TIME TIMESTAMP, DUE_DATE TIMESTAMP,"
                        + "END_TIME TIMESTAMP, COMPLETER_ID VARCHAR(128),"
                        + "COMMENT_TEXT VARCHAR(2048), VARIABLES TEXT,"
                        + "PARENT_TASK_ID VARCHAR(128), REVISION INTEGER NOT NULL)");
            } finally {
                st.close();
                c.close();
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("构造老库失败", e);
        }

        // 再跑一次 initialize：CREATE TABLE IF NOT EXISTS 不会补列，必须靠 ALTER 兜底
        persistence.initialize();

        WfTask task = new WfTask();
        task.setId("t-legacy-1");
        task.setProcessInstanceId("p1");
        task.setName("审批");
        task.setAssignee("manager");
        task.setStatus(WfTask.Status.DELEGATED);
        task.setCreateTime(new Date());
        task.getDelegateChain().add(new WfTask.DelegateHop("manager", "staff", new Date()));
        task.nextRevision();
        persistence.saveTask(task);

        assertEquals(1, persistence.findTask("t-legacy-1").getDelegateChain().size(),
                "老库补列后应能正常存读委派链，否则升级后引擎直接报 column not found");
    }

    // ==================== 历史查询（可组合 Query） ====================

    /**
     * 落一条活动历史。
     *
     * <p>时间用固定的毫秒偏移而不是 {@code new Date()}：SQL 里
     * {@code START_TIME > ?} 传的是 {@link Date}，只有边界值明确时，
     * 毫秒精度被 TIMESTAMP 截断之类的问题才会暴露成断言失败而不是偶发通过。
     */
    private void saveActivity(String id, String procId, String defKey, String activityId,
                              String type, String assignee, long startOffsetMillis,
                              long durationMillis) {
        WfActivityInstance a = new WfActivityInstance();
        a.setId(id);
        a.setProcessInstanceId(procId);
        a.setProcessDefinitionKey(defKey);
        a.setActivityId(activityId);
        a.setActivityType(type);
        a.setAssignee(assignee);
        long start = BASE + startOffsetMillis;
        a.setStartTime(new Date(start));
        a.setEndTime(new Date(start + durationMillis));
        a.setDurationMillis(durationMillis);
        a.setOutcome("completed");
        persistence.saveActivityInstance(a);
    }

    private static final long BASE = 1_700_000_000_000L;

    @Test
    @DisplayName("活动查询：六个过滤条件在真库上逐个生效")
    void activityQueryFiltersAllApply() {
        saveActivity("a1", "p1", "defA", "approve", "userTask", "boss", 0, 100);
        saveActivity("a2", "p1", "defA", "second", "userTask", "ceo", 1000, 5000);
        saveActivity("a3", "p2", "defB", "approve", "userTask", "boss", 2000, 10);
        saveActivity("a4", "p2", "defB", "e1", "endEvent", null, 3000, 0);

        assertEquals(2, persistence.queryActivityInstances(new WfHistoricActivityInstanceQuery()
                .setProcessInstanceId("p1").setPageNum(1).setPageSize(50)).size());
        assertEquals(2, persistence.queryActivityInstances(new WfHistoricActivityInstanceQuery()
                .setProcessDefinitionKey("defB").setPageNum(1).setPageSize(50)).size());
        assertEquals(2, persistence.queryActivityInstances(new WfHistoricActivityInstanceQuery()
                .setActivityId("approve").setPageNum(1).setPageSize(50)).size(),
                "a1 与 a3 同名节点但分属 p1/p2，靠 activityId 查会同时命中");
        assertEquals(3, persistence.queryActivityInstances(new WfHistoricActivityInstanceQuery()
                .setActivityType("userTask").setPageNum(1).setPageSize(50)).size());
        assertEquals(2, persistence.queryActivityInstances(new WfHistoricActivityInstanceQuery()
                .setAssignee("boss").setPageNum(1).setPageSize(50)).size());
        // AND：defA 里 ceo 只在 second 这一步出现过
        assertEquals(1, persistence.queryActivityInstances(new WfHistoricActivityInstanceQuery()
                .setProcessDefinitionKey("defA").setAssignee("ceo")
                .setPageNum(1).setPageSize(50)).size());
    }

    @Test
    @DisplayName("时间与耗时过滤：Date 绑定成 TIMESTAMP 后比较依然正确，且卡在边界上")
    void timeAndDurationFilters() {
        // 数据刻意贴着阈值排：n2 的起点恰好等于时间阈值，n3 的耗时恰好等于耗时阈值。
        //
        // 这条纪律是被反向验证逼出来的：最初的版本三条耗时是 100/5000/10，
        // 阈值 1000，于是把 SQL 里的 >= 改成 > 测试照样全绿 ——
        // 一条不卡边界的断言，压根分不出 >= 和 >，它证明不了自己在验什么。
        saveActivity("a1", "p1", "defA", "n1", "userTask", "u", 0, 100);
        saveActivity("a2", "p1", "defA", "n2", "userTask", "u", 1000, 3000);
        saveActivity("a3", "p1", "defA", "n3", "userTask", "u", 2000, 1000);
        saveActivity("a4", "p1", "defA", "n4", "userTask", "u", 3000, 50);

        assertEquals(2, persistence.queryActivityInstances(new WfHistoricActivityInstanceQuery()
                .setStartedAfter(new Date(BASE + 1000)).setPageNum(1).setPageSize(50)).size(),
                "严格大于：起点恰好等于阈值的 n2 不在内");
        assertEquals(1, persistence.queryActivityInstances(new WfHistoricActivityInstanceQuery()
                .setStartedBefore(new Date(BASE + 1000)).setPageNum(1).setPageSize(50)).size(),
                "严格小于：同样不含恰好等于阈值的 n2");
        assertEquals(2, persistence.queryActivityInstances(new WfHistoricActivityInstanceQuery()
                .setMinDurationMillis(1000L).setPageNum(1).setPageSize(50)).size(),
                "含边界：耗时恰好 1000ms 的 n3 必须在内，"
                        + "这正是 >= 与 > 的分水岭");
    }

    @Test
    @DisplayName("办理人为空的行不会被按办理人查询捞出（结束节点正是这种）")
    void nullAssigneeIsNotMatchedByAssigneeFilter() {
        saveActivity("a1", "p1", "defA", "approve", "userTask", "ceo", 0, 10);
        saveActivity("a2", "p1", "defA", "e1", "endEvent", null, 20, 0);

        List<WfActivityInstance> byCeo = persistence.queryActivityInstances(
                new WfHistoricActivityInstanceQuery().setAssignee("ceo")
                        .setPageNum(1).setPageSize(50));
        assertEquals(1, byCeo.size(),
                "结束节点不记办理人，若被空值比较误伤，"
                        + "『某人办过哪些单』会凭空少掉整条流程");
        assertEquals("approve", byCeo.get(0).getActivityId());
        assertEquals(2, persistence.queryActivityInstances(new WfHistoricActivityInstanceQuery()
                .setPageNum(1).setPageSize(50)).size(), "不加条件时它仍然在库里");
    }

    @Test
    @DisplayName("count 与列表口径一致，total 不受分页影响")
    void countMatchesList() {
        for (int i = 0; i < 5; i++) {
            saveActivity("a" + i, "p1", "defA", "n" + i, "userTask", "u", i * 100, 10);
        }
        WfHistoricActivityInstanceQuery query = new WfHistoricActivityInstanceQuery()
                .setProcessDefinitionKey("defA");
        assertEquals(5, persistence.countActivityInstances(query));
        assertEquals(5, persistence.countActivityInstances(query.setPageNum(1).setPageSize(2)),
                "count 传了分页参数也必须返回全量条数");
        assertEquals(2, persistence.queryActivityInstances(
                query.setPageNum(1).setPageSize(2)).size());
        assertEquals(2, persistence.queryActivityInstances(
                query.setPageNum(2).setPageSize(2)).size());
        assertEquals(1, persistence.queryActivityInstances(
                query.setPageNum(3).setPageSize(2)).size());
        assertEquals(0, persistence.queryActivityInstances(
                query.setPageNum(4).setPageSize(2)).size());
    }

    @Test
    @DisplayName("耗时倒序排序在真库上生效")
    void durationOrderInSql() {
        saveActivity("a1", "p1", "defA", "n1", "userTask", "u", 0, 100);
        saveActivity("a2", "p1", "defA", "n2", "userTask", "u", 200, 9000);
        saveActivity("a3", "p1", "defA", "n3", "userTask", "u", 400, 500);

        List<WfActivityInstance> slow = persistence.queryActivityInstances(
                new WfHistoricActivityInstanceQuery().orderByDurationDesc()
                        .setPageNum(1).setPageSize(50));
        assertEquals(3, slow.size());
        assertEquals("n2", slow.get(0).getActivityId(), "耗时最长的排第一");
        assertEquals("n3", slow.get(1).getActivityId());
        assertEquals("n1", slow.get(2).getActivityId());
    }

    @Test
    @DisplayName("清理只删已结束流程：在途流程的活动/任务/评论全部留存")
    void deleteHistoryBeforeKeepsRunning() {
        WfProcessInstance finished = new WfProcessInstance("p-done", "defA", "k:1");
        finished.setStatus(WfProcessStatus.COMPLETED);
        finished.setEndTime(new Date(BASE));
        persistence.saveProcessInstance(finished);

        WfProcessInstance running = new WfProcessInstance("p-open", "defA", "k:2");
        running.setStatus(WfProcessStatus.ACTIVE);
        persistence.saveProcessInstance(running);

        saveActivity("a1", "p-done", "defA", "approve", "userTask", "boss", 0, 10);
        saveActivity("a2", "p-open", "defA", "approve", "userTask", "boss", 0, 10);

        WfTask t1 = new WfTask();
        t1.setId("t1");
        t1.setProcessInstanceId("p-done");
        t1.setName("审批");
        t1.setCreateTime(new Date(BASE));
        t1.nextRevision();
        persistence.saveTask(t1);
        WfTask t2 = new WfTask();
        t2.setId("t2");
        t2.setProcessInstanceId("p-open");
        t2.setName("审批");
        t2.setCreateTime(new Date(BASE));
        t2.nextRevision();
        persistence.saveTask(t2);

        persistence.saveComment(new WfComment("c1", "p-done", "u1", "comment", "同意"));
        persistence.saveComment(new WfComment("c2", "p-open", "u1", "comment", "同意"));

        int removed = persistence.deleteHistoryBefore(new Date(BASE + 60_000L));
        assertEquals(1, removed, "返回值是删掉的流程条数");

        assertEquals(0, persistence.findActivityInstances("p-done").size());
        assertEquals(0, persistence.findTask("t1") == null ? 0 : 1, "已结束流程的任务也应清掉");
        assertEquals(0, persistence.findComments("p-done").size());

        assertEquals(1, persistence.findActivityInstances("p-open").size(),
                "在途流程的历史删掉之后轨迹会出洞，而单据还在被人办");
        assertNotNull(persistence.findTask("t2"));
        assertEquals(1, persistence.findComments("p-open").size());
    }
}
