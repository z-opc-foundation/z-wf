package com.zifang.z.wf.core.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
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
 *   <li><b>占位符个数与 set 次数</b>：{@code INSERT INTO T (a,b,c) VALUES (?,?,?)}
 *       里少 set 一个参数，javac 一声不吭，只有真跑才报
 *       {@code Parameter "#3" is not set}。写 ZWF_JOB 的 INSERT 时就是这样漏了 REV 一列</li>
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
    @DisplayName("定义的元数据读得回来：原始 XML 与部署时间存在列里，不读就等于没存")
    void definitionMetadataSurvivesRoundTrip() {
        WfDefinition definition = new WfDefinition("metaProcess", "元数据流程");
        WfNodeBundle.of(definition);
        definition.buildIndex();
        definition.setSourceXml("<definitions>原始XML</definitions>");
        definition.setStartTime(new Date(1700000000000L));
        definition.setDescription("描述");
        persistence.saveDefinition(definition);

        WfDefinition loaded = persistence.findDefinition("metaProcess", 1);
        // 读路径以前只 SELECT DEF_GRAPH，sourceXml / startTime 恒为 null
        assertEquals("<definitions>原始XML</definitions>", loaded.getSourceXml());
        assertNotNull(loaded.getStartTime(), "部署时间存了 DEPLOY_TIME 列却读不回来");
        assertEquals(1700000000000L, loaded.getStartTime().getTime());
        assertEquals("描述", loaded.getDescription());
    }

    @Test
    @DisplayName("停用标志真落库：改的是列，不是内存里的对象")
    void definitionSuspensionIsPersisted() {
        WfDefinition definition = new WfDefinition("suspProcess", "停用流程");
        WfNodeBundle.of(definition);
        definition.buildIndex();
        persistence.saveDefinition(definition);

        assertFalse(persistence.findDefinition("suspProcess", 1).isSuspended());
        assertTrue(persistence.setDefinitionSuspended("suspProcess", 1, true));
        assertTrue(persistence.findDefinition("suspProcess", 1).isSuspended(),
                "停用状态存进内存对象但没落库的话，重启后就自动复活了");

        assertTrue(persistence.setDefinitionSuspended("suspProcess", 1, false));
        assertFalse(persistence.findDefinition("suspProcess", 1).isSuspended());

        // 不存在的版本必须返回 false，让上层报错而不是静默成功
        assertFalse(persistence.setDefinitionSuspended("suspProcess", 99, true));
        assertFalse(persistence.setDefinitionSuspended("noSuchKey", 1, true));
    }

    @Test
    @DisplayName("按名称模糊 + 停用状态过滤，且名字里的通配符被转义")
    void definitionQueryByNameAndSuspension() {
        WfDefinition leave = new WfDefinition("q_leave", "请假流程");
        WfNodeBundle.of(leave);
        leave.buildIndex();
        persistence.saveDefinition(leave);

        WfDefinition expense = new WfDefinition("q_expense", "报销流程");
        WfNodeBundle.of(expense);
        expense.buildIndex();
        persistence.saveDefinition(expense);

        assertEquals(2, persistence.findDefinitions(null, null, null).size());
        assertEquals(1, persistence.findDefinitions(null, "请假", null).size());
        assertEquals(0, persistence.findDefinitions(null, "出差", null).size());
        assertEquals(2, persistence.findDefinitions(null, "流程", null).size(), "两个名字都含[流程]");

        // 通配符必须转义：查 "请%" 若不转义，LIKE '%请%%' 会把所有含"请"的都捞出来
        assertEquals(0, persistence.findDefinitions(null, "请%", null).size(),
                "名字里的 % 被当通配符了");
        assertEquals(0, persistence.findDefinitions(null, "请_", null).size(),
                "名字里的 _ 被当通配符了");

        assertEquals(2, persistence.findDefinitions(null, null, Boolean.FALSE).size());
        assertEquals(0, persistence.findDefinitions(null, null, Boolean.TRUE).size());
        assertTrue(persistence.setDefinitionSuspended("q_leave", 1, true));
        assertEquals(1, persistence.findDefinitions(null, null, Boolean.FALSE).size());
        assertEquals(1, persistence.findDefinitions(null, null, Boolean.TRUE).size());
        assertEquals(0, persistence.findDefinitions(null, "请假", Boolean.FALSE).size());
        assertEquals(1, persistence.findDefinitions(null, "请假", Boolean.TRUE).size());
    }

    @Test
    @DisplayName("任务挂起状态真落库，且 count 与列表同口径")
    void taskSuspensionIsPersistedAndFiltered() {
        WfTask first = newTask("t1", "boss", WfTask.Status.ASSIGNED, 1, 1000L);
        WfTask second = newTask("t2", "boss", WfTask.Status.ASSIGNED, 1, 2000L);
        second.setSuspended(true);
        persistence.saveTask(first);
        persistence.saveTask(second);

        assertTrue(persistence.findTask("t2").isSuspended(),
                "挂起状态存进内存对象但没落库的话，重启后挂起就自动消失了");
        assertFalse(persistence.findTask("t1").isSuspended());

        // 查询过滤
        assertEquals(1, persistence.countTasks(new WfTaskQuery().setSuspendedOnly(Boolean.TRUE)));
        assertEquals(1, persistence.countTasks(new WfTaskQuery().setSuspendedOnly(Boolean.FALSE)));
        assertEquals(2, persistence.countTasks(new WfTaskQuery().setSuspendedOnly(null)));
        assertEquals("t2", persistence.queryTasks(new WfTaskQuery()
                .setSuspendedOnly(Boolean.TRUE).setPageNum(1).setPageSize(10)).get(0).getId());

        // 更新路径也必须带着挂起列：只写 INSERT 的话，UPDATE 会把状态悄悄抹回未挂起
        first.setSuspended(true);
        first.nextRevision();
        persistence.saveTask(first);
        assertTrue(persistence.findTask("t1").isSuspended(),
                "UPDATE 漏了 SUSPENDED 列：挂起状态在库里被抹回默认");
        assertEquals(2, persistence.countTasks(new WfTaskQuery().setSuspendedOnly(Boolean.TRUE)));
    }

    @Test
    @DisplayName("job 类型落库，且按类型过滤与内存实现同口径")
    void jobTypeIsPersistedAndFiltered() {
        WfJob timer = newJob("j-timer", "p1", "e1", "b1", 1000L);

        // 类型是改出来的（newJob 建的行默认 TIMER）⇒ 必须走一次 UPDATE 存回去，
        // 这也正是"UPDATE 漏了 JOB_TYPE 列"的暴露点
        WfJob message = newJob("j-msg", "p1", "e1", "b2", 0L);
        message.setType(WfJobType.MESSAGE);
        message.setDuedate(null);
        message.setExceptionMessage("cancel");
        message.nextRevision();
        persistence.saveJob(message);

        WfJob signal = newJob("j-sig", "p2", "e1", "b3", 0L);
        signal.setType(WfJobType.SIGNAL);
        signal.setDuedate(null);
        signal.nextRevision();
        persistence.saveJob(signal);

        assertEquals(WfJobType.TIMER, persistence.findJob("j-timer").getType(),
                "默认应当是定时器，存量行的语义不能变");
        assertEquals(WfJobType.MESSAGE, persistence.findJob("j-msg").getType());
        assertEquals(WfJobType.SIGNAL, persistence.findJob("j-sig").getType());
        assertEquals("cancel", persistence.findJob("j-msg").getExceptionMessage(),
                "订阅名存在这一列里");

        assertEquals(1, persistence.countJobs(new WfJobQuery().setType(WfJobType.TIMER)));
        assertEquals(1, persistence.countJobs(new WfJobQuery().setType(WfJobType.MESSAGE)));
        assertEquals(1, persistence.countJobs(new WfJobQuery().setType(WfJobType.SIGNAL)));
        assertEquals(3, persistence.countJobs(new WfJobQuery().setType(null)));

        // 带 duedate 的订阅也捞不到：类型闸门是第二道保险，
        // 消息订阅的 duedate 本来就是 null（SQL 里 NULL < ? 恒不成立），
        // 但哪天谁给订阅填了 duedate，就靠这条拦住
        WfJob risky = persistence.findJob("j-msg");
        risky.setDuedate(new Date(1000L));
        risky.nextRevision();
        persistence.saveJob(risky);
        assertEquals(0, persistence.countJobs(new WfJobQuery()
                .setType(WfJobType.TIMER).setDueBefore(new Date(5000L))),
                "扫描器把消息订阅当成到期 job 了：还没发消息，流程自己往前走了");
    }

    @Test
    @DisplayName("候选池跟着 UPDATE 走：只写 INSERT 的话运行时加人就静默失效")
    void candidatePoolSurvivesUpdate() {
        WfTask task = newTask("c1", null, WfTask.Status.CREATED, 1, 1000L);
        task.setCandidateUsers(new ArrayList<String>(java.util.Arrays.asList("alice")));
        persistence.saveTask(task);

        // 运行时加派：BPMN 里画的候选池是部署时定的，现实里经常要临时加人
        WfTask loaded = persistence.findTask("c1");
        loaded.getCandidateUsers().add("carol");
        loaded.getCandidateGroups().add("finance");
        loaded.nextRevision();
        persistence.saveTask(loaded);

        WfTask after = persistence.findTask("c1");
        assertTrue(after.getCandidateUsers().contains("carol"),
                "候选列表存回去了却读不回来：UPDATE 漏了 CANDIDATE_USERS 列。"
                        + "症状是加人不报错、内存里也对，但换一次读取就消失");
        assertTrue(after.getCandidateGroups().contains("finance"), "同样漏了 CANDIDATE_GROUPS 列");
        assertTrue(after.getCandidateUsers().contains("alice"), "原有的候选不能被覆盖掉");

        // 移出也要落库
        after.getCandidateUsers().remove("alice");
        after.nextRevision();
        persistence.saveTask(after);
        assertFalse(persistence.findTask("c1").getCandidateUsers().contains("alice"));

        // 候选查询能按新候选人查到
        assertEquals(1, persistence.countTasks(new WfTaskQuery()
                .setCandidateUsers(java.util.Arrays.asList("carol"))));
    }

    @Test
    @DisplayName("待办语义：办理人/责任人/候选用户/候选组取或，不是且")
    void candidateOrAssignedIsDisjunctive() {
        WfTask byCandidate = newTask("x1", null, WfTask.Status.CREATED, 1, 1000L);
        byCandidate.setCandidateUsers(new ArrayList<String>(java.util.Arrays.asList("carol")));
        persistence.saveTask(byCandidate);

        WfTask byGroup = newTask("x2", null, WfTask.Status.CREATED, 1, 2000L);
        byGroup.setCandidateGroups(new ArrayList<String>(java.util.Arrays.asList("finance")));
        persistence.saveTask(byGroup);

        WfTask byAssignee = newTask("x3", "dave", WfTask.Status.ASSIGNED, 1, 3000L);
        byAssignee.setCandidateUsers(new ArrayList<String>(java.util.Arrays.asList("erin")));
        persistence.saveTask(byAssignee);

        WfTask unrelated = newTask("x4", null, WfTask.Status.CREATED, 1, 4000L);
        persistence.saveTask(unrelated);

        // 候选用户命中
        assertEquals(1, persistence.countTasks(new WfTaskQuery()
                .setCandidateOrAssigned(true).setAssignee("carol")
                .setCandidateUsers(java.util.Arrays.asList("carol"))
                .setOpenOnly(true)));
        // 候选组命中
        assertEquals(1, persistence.countTasks(new WfTaskQuery()
                .setCandidateOrAssigned(true)
                .setCandidateGroups(java.util.Arrays.asList("finance"))
                .setOpenOnly(true)));
        // 办理人命中 —— 关键：dave 不是任何一张单的候选人，
        // 若把身份条件写成"且"，他这条会被候选条件过滤掉，待办里看不到自己的单
        assertEquals(1, persistence.countTasks(new WfTaskQuery()
                .setCandidateOrAssigned(true).setAssignee("dave")
                .setCandidateUsers(java.util.Arrays.asList("dave"))
                .setOpenOnly(true)));
        // 无关的人一条都看不到
        assertEquals(0, persistence.countTasks(new WfTaskQuery()
                .setCandidateOrAssigned(true).setAssignee("zoe")
                .setCandidateUsers(java.util.Arrays.asList("zoe"))
                .setCandidateGroups(java.util.Arrays.asList("nobody"))
                .setOpenOnly(true)));

        // 一个身份条件都不给：恒假而不是恒真。恒真会把整张待办表倒出去
        assertEquals(0, persistence.countTasks(new WfTaskQuery()
                .setCandidateOrAssigned(true).setOpenOnly(true)));

        // 精确筛选语义不受影响：默认仍是"且"
        assertEquals(1, persistence.countTasks(new WfTaskQuery()
                .setAssignee("dave").setCandidateUsers(java.util.Arrays.asList("erin"))));
        assertEquals(0, persistence.countTasks(new WfTaskQuery()
                .setAssignee("dave").setCandidateUsers(java.util.Arrays.asList("carol"))));
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

    // ==================== 历史流程实例条件 ====================

    /**
     * 造一条指定状态的流程实例。
     *
     * <p>五种状态全都要用上：finishedOnly / unfinishedOnly 各自要能把另外几种分出去，
     * 少造一种就可能让过滤条件里的枚举列表写错却测不出来。
     */
    private void saveProcess(String id, WfProcessStatus status, long startOffsetMillis) {
        WfProcessInstance instance = new WfProcessInstance(id, "defA", "bk-" + id);
        instance.setStatus(status);
        instance.setStartTime(new Date(BASE + startOffsetMillis));
        if (status.isTerminal()) {
            instance.setEndTime(new Date(BASE + startOffsetMillis + 100));
        }
        persistence.saveProcessInstance(instance);
    }

    @Test
    @DisplayName("finishedOnly / unfinishedOnly 在真库上把五种状态分干净")
    void finishedAndUnfinishedFiltersInSql() {
        saveProcess("p-active", WfProcessStatus.ACTIVE, 0);
        saveProcess("p-susp", WfProcessStatus.SUSPENDED, 100);
        saveProcess("p-done", WfProcessStatus.COMPLETED, 200);
        saveProcess("p-ext", WfProcessStatus.EXTERNALLY_TERMINATED, 300);
        saveProcess("p-int", WfProcessStatus.INTERNALLY_TERMINATED, 400);

        List<WfProcessInstance> finished = persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setFinishedOnly(true)
                        .setPageNum(1).setPageSize(50));
        assertEquals(3, finished.size(), "三种终态都算已结束");
        for (WfProcessInstance i : finished) {
            assertTrue(i.getStatus().isTerminal(), "混入非终态 " + i.getStatus());
        }
        assertEquals(3, persistence.countProcessInstances(
                new WfProcessInstanceQuery().setFinishedOnly(true)));

        List<WfProcessInstance> running = persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setUnfinishedOnly(true)
                        .setPageNum(1).setPageSize(50));
        assertEquals(2, running.size(), "ACTIVE 与 SUSPENDED 都在途");
        for (WfProcessInstance i : running) {
            assertTrue(i.getStatus().isActive(), "混入终态 " + i.getStatus());
        }

        // 与其它条件叠加
        assertEquals(1, persistence.queryProcessInstances(new WfProcessInstanceQuery()
                .setFinishedOnly(true).setDefinitionKey("defA")
                .setStatus(WfProcessStatus.COMPLETED)
                .setPageNum(1).setPageSize(50)).size());
        assertEquals(0, persistence.queryProcessInstances(new WfProcessInstanceQuery()
                .setFinishedOnly(true).setDefinitionKey("nope")
                .setPageNum(1).setPageSize(50)).size());
    }

    @Test
    @DisplayName("流程实例条件矛盾时 JDBC 侧直接抛错")
    void contradictoryProcessFlagsAreRejected() {
        saveProcess("p1", WfProcessStatus.ACTIVE, 0);
        assertThrows(IllegalArgumentException.class, () -> persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setFinishedOnly(true).setUnfinishedOnly(true)));
        assertThrows(IllegalArgumentException.class, () -> persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setFinishedOnly(true)
                        .setStatus(WfProcessStatus.ACTIVE)));
        assertThrows(IllegalArgumentException.class, () -> persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setUnfinishedOnly(true)
                        .setStatus(WfProcessStatus.COMPLETED)));
        assertThrows(IllegalArgumentException.class, () -> persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setStartTimeFrom(new Date(2000L))
                        .setStartTimeTo(new Date(1000L))));
    }

    @Test
    @DisplayName("流程实例分页下推到 SQL 后各页不重不漏")
    void processPaginationIsPushedDown() {
        // 5 条在途 + 2 条终态，按 START_TIME 倒序
        for (int i = 0; i < 5; i++) {
            saveProcess("p-open" + i, WfProcessStatus.ACTIVE, 1000L * (i + 1));
        }
        saveProcess("p-done1", WfProcessStatus.COMPLETED, 6000);
        saveProcess("p-done2", WfProcessStatus.EXTERNALLY_TERMINATED, 7000);

        java.util.Set<String> seen = new java.util.LinkedHashSet<String>();
        for (int page = 1; page <= 2; page++) {
            List<WfProcessInstance> rows = persistence.queryProcessInstances(
                    new WfProcessInstanceQuery().setPageNum(page).setPageSize(3));
            assertEquals(3, rows.size(), "第 " + page + " 页应满 3 条");
            for (WfProcessInstance i : rows) {
                assertTrue(seen.add(i.getId()), "第 " + page + " 页出现重复行 " + i.getId());
            }
        }
        List<WfProcessInstance> last = persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setPageNum(3).setPageSize(3));
        assertEquals(1, last.size(), "7 条分 3 页，最后一页只剩 1 条");
        for (WfProcessInstance i : last) {
            assertTrue(seen.add(i.getId()), "第 3 页出现重复行 " + i.getId());
        }
        assertEquals(7, seen.size(), "三页合起来必须正好覆盖 7 条");
        assertEquals(0, persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setPageNum(4).setPageSize(3)).size());
        assertEquals(7, persistence.countProcessInstances(new WfProcessInstanceQuery()),
                "total 不受分页影响");
    }

    // ==================== Job 存储 ====================

    @Test
    @DisplayName("待办查询里 assignee 与 owner 是「或」：与内存实现语义必须一致")
    void assigneeAndOwnerAreOrNotAnd() {
        // 与 InMemoryWorkflowPersistenceTest 里的同名用例用完全相同的数据与期望。
        // 放两遍不是冗余：这类"同一条件两套语义"的分歧只有两边都钉住才暴露，
        // 而开发期默认用内存实现，分歧会一路活到上线才发现
        persistence.saveTask(taskWithOwnership("t1", "me", null));
        persistence.saveTask(taskWithOwnership("t2", null, "me"));
        persistence.saveTask(taskWithOwnership("t3", "other", "me"));
        persistence.saveTask(taskWithOwnership("t4", "me", "other"));

        WfTaskQuery query = new WfTaskQuery().setAssignee("me").setOwner("me");
        assertEquals(4, persistence.queryTasks(query.setPageNum(1).setPageSize(50)).size(),
                "SQL 侧是 (ASSIGNEE=? OR OWNER=?)，内存侧也必须是「或」");
        assertEquals(4, persistence.countTasks(query));

        assertEquals(2, persistence.countTasks(new WfTaskQuery().setAssignee("me")));
        assertEquals(2, persistence.countTasks(new WfTaskQuery().setOwner("me")));
        assertEquals(4, persistence.countTasks(new WfTaskQuery()
                .setAssignee("me").setOwner("me").setOpenOnly(true)));
    }

    private WfTask taskWithOwnership(String id, String assignee, String owner) {
        WfTask t = new WfTask();
        t.setId(id);
        t.setAssignee(assignee);
        t.setOwner(owner);
        t.setStatus(WfTask.Status.CREATED);
        t.setPriority(1);
        t.setCreateTime(new Date(BASE));
        t.nextRevision();
        return t;
    }

    private WfJob newJob(String id, String procId, String execId, String element,
                         long dueOffsetMillis) {
        WfJob job = new WfJob();
        job.setId(id);
        job.setProcessInstanceId(procId);
        job.setExecutionId(execId);
        job.setElementId(element);
        job.setAttachedToRef("approve");
        job.setCreateTime(new Date(BASE));
        job.setDuedate(new Date(BASE + dueOffsetMillis));
        persistence.saveJob(job);
        return job;
    }

    @Test
    @DisplayName("job 往返：到期时刻、重试次数、失败信息都不丢")
    void jobRoundTrip() {
        WfJob job = newJob("j1", "p1", "e1", "timeout", 60000L);
        job.recordFailure("执行器炸了");
        job.nextRevision();
        persistence.saveJob(job);

        WfJob loaded = persistence.findJob("j1");
        assertNotNull(loaded);
        assertEquals("p1", loaded.getProcessInstanceId());
        assertEquals("e1", loaded.getExecutionId());
        assertEquals("timeout", loaded.getElementId());
        assertEquals("approve", loaded.getAttachedToRef());
        assertNotNull(loaded.getDuedate());
        assertEquals(60000L, loaded.getDuedate().getTime() - BASE);
        assertEquals(WfJob.DEFAULT_RETRIES - 1, loaded.getRetries());
        assertEquals("执行器炸了", loaded.getExceptionMessage());
        assertNotNull(loaded.getLastFailureTime());
    }

    @Test
    @DisplayName("job 乐观锁：版本对不上直接抛，不静默覆盖执行器的重试计数")
    void jobOptimisticLock() {
        WfJob job = newJob("j1", "p1", "e1", "timeout", 1000L);
        // 拿一份过期的副本再存一次（库里已经是 revision=1）
        WfJob stale = persistence.findJob("j1");
        job.recordFailure("第一次失败");
        job.nextRevision();
        persistence.saveJob(job);

        stale.recordFailure("基于旧版本的写入");
        stale.nextRevision();
        assertThrows(WfOptimisticLockException.class, () -> persistence.saveJob(stale));
    }

    @Test
    @DisplayName("按到期时刻取 job：严格早于边界，并按到期正序")
    void queryJobsByDueDate() {
        newJob("j-late", "p1", "e1", "timeout", 5000L);
        newJob("j-early", "p2", "e2", "timeout", 1000L);
        newJob("j-mid", "p3", "e3", "timeout", 3000L);

        List<WfJob> due = persistence.queryJobs(new WfJobQuery()
                .setDueBefore(new Date(BASE + 3001L)).setPageNum(1).setPageSize(10));
        assertEquals(2, due.size(), "只有到期时刻严格早于边界的两只");
        assertEquals("j-early", due.get(0).getId(), "最早到点的排第一");
        assertEquals("j-mid", due.get(1).getId());

        assertEquals(0, persistence.queryJobs(new WfJobQuery()
                        .setDueBefore(new Date(BASE + 1000L)).setPageNum(1).setPageSize(10)).size(),
                "恰好等于边界的算下一轮（SQL 是严格小于），"
                        + "避免同一秒被两个执行器各处理一次");
        assertEquals(3, persistence.countJobs(new WfJobQuery()));
        assertEquals(1, persistence.countJobs(new WfJobQuery().setProcessInstanceId("p2")));
        assertEquals(1, persistence.countJobs(new WfJobQuery().setElementId("timeout")
                .setProcessInstanceId("p1")));
        assertEquals(0, persistence.countJobs(new WfJobQuery().setElementId("nope")));
    }

    @Test
    @DisplayName("重试耗尽的能被单独查出来 —— 漏发的提醒必须查得到")
    void queryExhaustedJobs() {
        WfJob ok = newJob("j-ok", "p1", "e1", "timeout", 1000L);
        WfJob bad = newJob("j-bad", "p2", "e2", "timeout", 2000L);
        for (int i = 0; i < WfJob.DEFAULT_RETRIES; i++) {
            bad.recordFailure("第 " + (i + 1) + " 次");
        }
        bad.nextRevision();
        persistence.saveJob(bad);

        List<WfJob> exhausted = persistence.queryJobs(new WfJobQuery()
                .setRetriesExhausted(Boolean.TRUE).setPageNum(1).setPageSize(10));
        assertEquals(1, exhausted.size());
        assertEquals("j-bad", exhausted.get(0).getId());
        assertEquals(1, persistence.countJobs(new WfJobQuery()
                .setRetriesExhausted(Boolean.FALSE)));
        assertEquals(1, persistence.countJobs(new WfJobQuery()
                .setRetriesExhausted(Boolean.FALSE).setProcessInstanceId("p1")));
        assertEquals(ok.getId(), persistence.queryJobs(new WfJobQuery()
                .setRetriesExhausted(Boolean.FALSE).setPageNum(1).setPageSize(10))
                .get(0).getId());
    }

    @Test
    @DisplayName("按实例/按 token 清 job，两条清理路径互不误伤")
    void deleteJobsByScope() {
        newJob("j1", "p1", "e1", "timeout", 1000L);
        newJob("j2", "p1", "e1", "remind", 2000L);
        newJob("j3", "p1", "e2", "timeout", 3000L);
        newJob("j4", "p2", "e3", "timeout", 4000L);

        // j1/j2 都挂在 e1 上，j3 在 e2，j4 在 e3
        assertEquals(2, persistence.deleteJobsByExecution("e1"));
        assertEquals(2, persistence.countJobs(new WfJobQuery()));
        assertEquals(0, persistence.deleteJobsByExecution("e1"),
                "已经清空的 token 再删一次不该报错，直接返回 0");

        // 上一行清完 e1 后 p1 名下只剩 j3 一条
        assertEquals(1, persistence.deleteJobsByProcessInstance("p1"));
        List<WfJob> left = persistence.queryJobs(new WfJobQuery().setPageNum(1).setPageSize(10));
        assertEquals(1, left.size());
        assertEquals("j4", left.get(0).getId(), "别的实例的 job 不能被误删");
    }

    @Test
    @DisplayName("job 表在 initialize 后可重复初始化，老库升级不炸")
    void jobTableIsIdempotent() {
        persistence.initialize();
        persistence.initialize();
        newJob("j1", "p1", "e1", "timeout", 1000L);
        assertEquals(1, persistence.countJobs(new WfJobQuery()));
    }

    // ==================== 变量变更审计 ====================

    private WfComment variableAudit(String id, String procId, String userId, String content) {
        WfComment comment = new WfComment(id, procId, userId, "variable", content);
        persistence.saveComment(comment);
        return comment;
    }

    /**
     * 时间区间断言必须能卡住边界，而 {@link WfComment} 的五参构造器把 time 设成
     * {@code new Date()}（毫秒精度）—— 连着建三条几乎必然落在同一毫秒，
     * 于是 oldest == newest，"含 changedFrom / 不含 changedTo" 这两条断言
     * 无论实现对错都不成立。所以需要卡边界的地方一律显式给时间。
     */
    private WfComment variableAuditAt(String id, String procId, String userId, String content, long time) {
        WfComment comment = new WfComment(id, procId, userId, "variable", content);
        comment.setTime(new Date(time));
        persistence.saveComment(comment);
        return comment;
    }

    @Test
    @DisplayName("变量名走前缀匹配：amount 不该命中 discount_amount")
    void variableAuditMatchesByPrefix() {
        variableAudit("v1", "p1", "admin-1", "amount: (未设置) -> 1000");
        variableAudit("v2", "p1", "admin-1", "discount_amount: (未设置) -> 50");
        variableAudit("v3", "p1", "admin-2", "amount: 1000 -> 500");

        assertEquals(2, persistence.countVariableAudits(new WfVariableAuditQuery()
                .setProcessInstanceId("p1").setVariableName("amount")),
                "子串匹配会把 discount_amount 带进来，而审计给出错的行比不给行更糟");

        List<WfComment> rows = persistence.queryVariableAudits(new WfVariableAuditQuery()
                .setProcessInstanceId("p1").setVariableName("amount")
                .setPageNum(1).setPageSize(10));
        assertEquals(2, rows.size());
        for (WfComment c : rows) {
            assertTrue(c.getContent().startsWith("amount:"),
                    "实际取到: " + c.getContent());
        }
        // 倒序：v3 是最后一次变更，应排第一
        assertEquals("v3", rows.get(0).getId());

        assertEquals(1, persistence.countVariableAudits(new WfVariableAuditQuery()
                .setVariableName("discount_amount")));
        assertEquals(0, persistence.countVariableAudits(new WfVariableAuditQuery()
                .setVariableName("amountt")), "不该做前缀之外的部分匹配");
    }

    @Test
    @DisplayName("变量名里的 _ 与 % 不当通配符：查 a_b 只该命中 a_b")
    void variableAuditEscapesLikeWildcards() {
        // 取名有个坑：_ 只匹配"一个字符"，所以反例必须与查询名<b>等长</b>才撞得上。
        // 早先写成 disc_ount(9) / discount(8)，长度对不上，不转义也命中不了，
        // 于是这条用例看着在测转义、实际对转义没有区分力。
        variableAudit("v1", "p1", "admin-1", "a_b: 1 -> 2");
        variableAudit("v2", "p1", "admin-1", "axb: 1 -> 2");
        variableAudit("v3", "p1", "admin-1", "a%b: 1 -> 2");
        variableAudit("v4", "p1", "admin-1", "axyzb: 1 -> 2");

        // 不转义时 _ 匹配任意单字符，a_b 会把 axb 一并带出来
        assertEquals(1, persistence.countVariableAudits(new WfVariableAuditQuery()
                .setVariableName("a_b")), "下划线被当通配符了");
        assertEquals(1, persistence.countVariableAudits(new WfVariableAuditQuery()
                .setVariableName("axb")), "这条是给 a_b 当反例的，不该被自己命中");

        // % 同理：不转义时 a%b 会匹配 axyzb
        assertEquals(1, persistence.countVariableAudits(new WfVariableAuditQuery()
                .setVariableName("a%b")), "百分号被当通配符了");
    }

    @Test
    @DisplayName("审计查询只认 variable 类型，人工评论不混进来")
    void variableAuditExcludesOtherTypes() {
        variableAudit("v1", "p1", "admin-1", "amount: 1 -> 2");
        persistence.saveComment(new WfComment("c1", "p1", "boss", "comment", "同意"));
        persistence.saveComment(new WfComment("c2", "p1", "system", "job", "停留超时"));

        List<WfComment> audits = persistence.queryVariableAudits(new WfVariableAuditQuery()
                .setPageNum(1).setPageSize(10));
        assertEquals(1, audits.size(), "只该返回 variable 类型的");
        assertEquals("v1", audits.get(0).getId());
    }

    @Test
    @DisplayName("按操作人与时间区间筛审计，count 与列表同口径")
    void variableAuditFiltersAndCount() {
        long t0 = 1700000000000L;
        variableAuditAt("v1", "p1", "admin-1", "amount: 1 -> 2", t0);
        variableAuditAt("v2", "p1", "admin-2", "amount: 2 -> 3", t0 + 1000L);
        variableAuditAt("v3", "p2", "admin-1", "amount: 3 -> 4", t0 + 2000L);

        assertEquals(2, persistence.countVariableAudits(new WfVariableAuditQuery()
                .setChangedBy("admin-1")));
        assertEquals(2, persistence.queryVariableAudits(new WfVariableAuditQuery()
                .setChangedBy("admin-1").setPageNum(1).setPageSize(10)).size());
        assertEquals(1, persistence.countVariableAudits(new WfVariableAuditQuery()
                .setProcessInstanceId("p1").setChangedBy("admin-1")));
        assertEquals(1, persistence.countVariableAudits(new WfVariableAuditQuery()
                .setProcessInstanceId("p1").setChangedBy("admin-1")
                .setVariableName("amount")));

        // 时间区间卡边界：changedFrom 含、changedTo 不含。行在 t0 / t0+1s / t0+2s
        assertEquals(2, persistence.countVariableAudits(new WfVariableAuditQuery()
                .setChangedFrom(new Date(t0 + 1000L))), "changedFrom 含边界");
        assertEquals(1, persistence.countVariableAudits(new WfVariableAuditQuery()
                .setChangedTo(new Date(t0 + 1000L))), "changedTo 不含边界");
        assertEquals(1, persistence.countVariableAudits(new WfVariableAuditQuery()
                .setChangedFrom(new Date(t0 + 1000L)).setChangedTo(new Date(t0 + 2000L))),
                "[t0+1s, t0+2s) 只该有 v2 一条");
    }

    @Test
    @DisplayName("审计查询分页不重不漏")
    void variableAuditPaginates() {
        for (int i = 0; i < 7; i++) {
            variableAudit("v" + i, "p1", "admin", "amount: " + i + " -> " + (i + 1));
        }
        WfVariableAuditQuery query = new WfVariableAuditQuery().setProcessInstanceId("p1");
        assertEquals(7, persistence.countVariableAudits(query));
        // count 不该被分页参数影响：否则前端翻页会看到 total 越翻越小
        assertEquals(7, persistence.countVariableAudits(query.setPageNum(2).setPageSize(3)));

        java.util.Set<String> seen = new java.util.LinkedHashSet<String>();
        // 7 条按 3 条一页翻三遍：每页 3/3/1 —— 末页只有 1 条是必然的，
        // 写死"每页都该有 3 条"是把页大小当成了行数
        int[] expectPerPage = {3, 3, 1};
        for (int page = 1; page <= expectPerPage.length; page++) {
            List<WfComment> rows = persistence.queryVariableAudits(
                    query.setPageNum(page).setPageSize(3));
            assertEquals(expectPerPage[page - 1], rows.size(), "第 " + page + " 页行数不对");
            for (WfComment c : rows) {
                assertTrue(seen.add(c.getId()), "第 " + page + " 页出现重复行 " + c.getId());
            }
        }
        assertEquals(7, seen.size(), "三页并起来必须正好是 7 条，不重不漏");
        assertEquals(0, persistence.queryVariableAudits(
                query.setPageNum(4).setPageSize(3)).size());
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

    // ==================== 任务 count 与分页 ====================

    @Test
    @DisplayName("条件自相矛盾时 JDBC 侧同样直接抛错，不拼恒假 SQL")
    void contradictoryTaskFlagsAreRejected() {
        persistence.saveTask(newTask("t1", "u1", WfTask.Status.CREATED, 1000L));
        persistence.saveTask(newTask("t2", "u1", WfTask.Status.COMPLETED, 2000L));

        // SQL 侧不加检查会变成 STATUS IN (...) AND STATUS='COMPLETED'，
        // 恒假、零行、零错误 —— 与内存实现同一种歧义，两边都得挡
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> persistence.queryTasks(new WfTaskQuery()
                        .setOpenOnly(true).setCompletedOnly(true)));
        assertTrue(ex.getMessage().contains("openOnly"), ex.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> persistence.countTasks(new WfTaskQuery()
                        .setOpenOnly(true).setCompletedOnly(true)));
        assertThrows(IllegalArgumentException.class,
                () -> persistence.queryTasks(new WfTaskQuery()
                        .setCreateTimeFrom(new Date(2000L)).setCreateTimeTo(new Date(1000L))));
    }

    @Test
    @DisplayName("countTasks 与 queryTasks 同口径，分页不影响 total")
    void countTasksMatchesList() {
        for (int i = 0; i < 7; i++) {
            persistence.saveTask(newTask("t" + i, i % 2 == 0 ? "u1" : "u2",
                    i < 3 ? WfTask.Status.COMPLETED : WfTask.Status.CREATED, 1000L * (i + 1)));
        }
        assertEquals(3, persistence.queryTasks(new WfTaskQuery()
                .setCompletedOnly(true).setPageNum(1).setPageSize(50)).size());
        assertEquals(3, persistence.countTasks(new WfTaskQuery().setCompletedOnly(true)));
        assertEquals(7, persistence.countTasks(new WfTaskQuery()));
        assertEquals(7, persistence.countTasks(new WfTaskQuery().setPageNum(2).setPageSize(3)));
        // 7 条里 i%2==0 的落在 u1（i=0,2,4,6），其中 i<3 的是已完成，
        // 所以 u1 的未完成任务是 i=4 与 i=6 两条
        assertEquals(2, persistence.countTasks(
                new WfTaskQuery().setAssignee("u1").setOpenOnly(true)));
    }

    @Test
    @DisplayName("分页下推到 SQL 后各页不重不漏，越界页为空")
    void paginationIsPushedDownWithoutGapsOrOverlaps() {
        for (int i = 0; i < 7; i++) {
            persistence.saveTask(newTask("t" + i, "u1", WfTask.Status.CREATED, 1000L * (i + 1)));
        }
        // 7 条、每页 3 条 ⇒ 第 1/2 页各 3 条，第 3 页只剩 1 条
        java.util.Set<String> seen = new java.util.LinkedHashSet<String>();
        for (int page = 1; page <= 2; page++) {
            List<WfTask> rows = persistence.queryTasks(
                    new WfTaskQuery().setAssignee("u1").setPageNum(page).setPageSize(3));
            assertEquals(3, rows.size(), "第 " + page + " 页应满 3 条");
            for (WfTask t : rows) {
                assertTrue(seen.add(t.getId()), "第 " + page + " 页出现了重复行 " + t.getId());
            }
        }
        List<WfTask> last = persistence.queryTasks(
                new WfTaskQuery().setAssignee("u1").setPageNum(3).setPageSize(3));
        assertEquals(1, last.size(), "最后一页不满时按实际条数返回，不能补行也不能空");
        for (WfTask t : last) {
            assertTrue(seen.add(t.getId()), "第 3 页出现了重复行 " + t.getId());
        }
        assertEquals(7, seen.size(), "三页合起来必须正好覆盖全部 7 条");
        assertEquals(0, persistence.queryTasks(
                new WfTaskQuery().setAssignee("u1").setPageNum(4).setPageSize(3)).size());
    }

    private WfTask newTask(String id, String assignee, WfTask.Status status, long createTime) {
        WfTask t = new WfTask();
        t.setId(id);
        t.setAssignee(assignee);
        t.setStatus(status);
        t.setPriority(1);
        t.setCreateTime(new Date(createTime));
        t.nextRevision();
        return t;
    }
}
