package com.zifang.z.wf.core.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfFlow;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;

/**
 * {@link InMemoryWorkflowPersistence} 契约测试。
 *
 * <p><b>重点测防御性拷贝</b>：这是整个内存实现的立身之本。
 * 如果查询返回内部引用，内存实现就会比 JDBC 实现少一道"拷贝边界"，
 * 于是出现"内存里能跑、换成落库就错"这类只在生产暴露的 bug。
 * 因此本类显式断言 {@code assertNotSame}：拿到的一定是副本。
 *
 * @author zifang
 */
class InMemoryWorkflowPersistenceTest {

    private InMemoryWorkflowPersistence persistence;

    @BeforeEach
    void setUp() {
        persistence = new InMemoryWorkflowPersistence();
        persistence.initialize();
    }

    // ==================== 深拷贝：所有实体 ====================

    @Test
    @DisplayName("防御性拷贝：流程实例查询返回副本，外部改不影响存储")
    void processInstanceIsDefensivelyCopied() {
        WfProcessInstance instance = new WfProcessInstance("proc-1", "leaveProcess", "leaveProcess:1");
        instance.setStatus(WfProcessStatus.ACTIVE);
        instance.setStartTime(new Date());
        persistence.saveProcessInstance(instance);

        WfProcessInstance loaded = persistence.findProcessInstance("proc-1");
        assertNotNull(loaded);
        assertNotSame(instance, loaded, "查询必须返回副本，不能是内部引用");

        // 改副本不应影响存储
        loaded.setStatus(WfProcessStatus.COMPLETED);
        assertEquals(WfProcessStatus.ACTIVE, persistence.findProcessInstance("proc-1").getStatus());
    }

    @Test
    @DisplayName("深拷贝：Date / Map / List 字段必须完整往返（JSON round-trip 的真正风险点）")
    void deepFieldsSurviveRoundTrip() {
        WfProcessInstance instance = new WfProcessInstance("proc-2", "leaveProcess", "leaveProcess:1");
        Date start = new Date(1700000000000L);
        instance.setStartTime(start);
        instance.setEndTime(new Date(1700000060000L));
        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 3);
        vars.put("reason", "vacation");
        instance.setVariables(vars);
        persistence.saveProcessInstance(instance);

        WfProcessInstance loaded = persistence.findProcessInstance("proc-2");
        assertNotNull(loaded.getStartTime(), "Date 字段必须在往返后非空");
        assertEquals(start.getTime(), loaded.getStartTime().getTime());
        assertNotNull(loaded.getEndTime());
        assertEquals(3, loaded.getVariables().get("days"));
        assertEquals("vacation", loaded.getVariables().get("reason"));
        assertEquals(0, loaded.getRevision());
    }

    @Test
    @DisplayName("深拷贝：任务实体（含内嵌 DelegateHop 列表 + Date）往返")
    void taskSurvivesRoundTrip() {
        WfTask task = new WfTask();
        task.setId("task-1");
        task.setName("经理审批");
        task.setAssignee("u1");
        task.setOwner("u2");
        task.setStatus(WfTask.Status.DELEGATED);
        task.setCreateTime(new Date());
        task.setCandidateUsers(java.util.Arrays.asList("u1", "u3"));
        task.setCandidateGroups(java.util.Arrays.asList("g1"));
        task.getDelegateChain().add(new WfTask.DelegateHop("u1", "u2", new Date()));
        persistence.saveTask(task);

        WfTask loaded = persistence.findTask("task-1");
        assertNotNull(loaded);
        assertEquals("经理审批", loaded.getName());
        assertEquals(WfTask.Status.DELEGATED, loaded.getStatus());
        assertEquals("u2", loaded.effectiveHandler());
        assertEquals(2, loaded.getCandidateUsers().size());
        assertEquals(1, loaded.getCandidateGroups().size());
        assertEquals(1, loaded.getDelegateChain().size());
        assertEquals("u1", loaded.getDelegateChain().get(0).getFrom());
        assertNotNull(loaded.getCreateTime());
    }

    @Test
    @DisplayName("深拷贝：执行令牌（含 arrivedActivities / state）往返")
    void executionSurvivesRoundTrip() {
        WfExecution execution = new WfExecution("exe-1", "proc-1", "task1");
        execution.setState(WfExecution.State.WAITING);
        execution.setChild(true);
        execution.setParentId("exe-root");
        execution.arriveAt("gw1");
        execution.getVariables().put("amount", 500);
        persistence.saveExecution(execution);

        WfExecution loaded = persistence.findExecution("exe-1");
        assertNotNull(loaded);
        assertEquals(WfExecution.State.WAITING, loaded.getState());
        assertTrue(loaded.isChild());
        assertEquals("exe-root", loaded.getParentId());
        assertEquals(1, loaded.getArrivedActivities().size());
        assertEquals(500, loaded.getVariables().get("amount"));
    }

    @Test
    @DisplayName("深拷贝：活动历史与评论往返")
    void historyAndCommentSurviveRoundTrip() {
        WfActivityInstance activity = new WfActivityInstance();
        activity.setProcessInstanceId("proc-3");
        activity.setActivityName("经理审批");
        activity.setStartTime(new Date());
        activity.setOutcome("completed");
        persistence.saveActivityInstance(activity);

        WfComment comment = new WfComment("cmt-1", "proc-3", "u1", "comment", "同意");
        persistence.saveComment(comment);

        assertEquals(1, persistence.findActivityInstances("proc-3").size());
        assertEquals("经理审批", persistence.findActivityInstances("proc-3").get(0).getActivityName());
        assertEquals(1, persistence.findComments("proc-3").size());
        assertEquals("同意", persistence.findComments("proc-3").get(0).getContent());
    }

    @Test
    @DisplayName("深拷贝：流程定义（含节点类型枚举 + 连线）往返后仍可建索引与取 startNode")
    void definitionSurvivesRoundTripAndStillWorks() {
        WfDefinition definition = new WfDefinition("leaveProcess", "请假流程");
        definition.setVersion(1);
        definition.setCategory("审批");

        WfNode start = new WfNode("start1", "开始", WfNodeType.START_EVENT);
        WfNode task = new WfNode("task1", "经理审批", WfNodeType.USER_TASK);
        task.setAssignee("manager");
        WfNode end = new WfNode("end1", "结束", WfNodeType.END_EVENT);
        definition.setNodes(java.util.Arrays.asList(start, task, end));
        definition.setFlows(java.util.Arrays.asList(
                new WfFlow("start1", "task1"),
                new WfFlow("task1", "end1")));
        definition.getFlows().get(0).setId("f1");
        definition.buildIndex();

        persistence.saveDefinition(definition);

        WfDefinition loaded = persistence.findLatestDefinition("leaveProcess");
        assertNotNull(loaded);
        assertEquals(3, loaded.getNodes().size());
        assertEquals(WfNodeType.USER_TASK, loaded.node("task1").getType(),
                "枚举必须在 JSON 往返后保持同一类型");
        assertEquals("manager", loaded.node("task1").getAssignee());
        // 索引是 transient 的，反序列化后必须能惰性重建
        assertNotNull(loaded.startNode(), "反序列化后 startNode() 必须仍可用");
        assertEquals("start1", loaded.startNode().getId());
        assertEquals(1, loaded.outgoingFlows("start1").size());
    }

    // ==================== 乐观锁 ====================

    @Test
    @DisplayName("乐观锁：revision 不连续时抛冲突，不静默覆盖")
    void optimisticLockRejectsStaleRevision() {
        WfProcessInstance instance = new WfProcessInstance("proc-lr", "leaveProcess", "leaveProcess:1");
        persistence.saveProcessInstance(instance); // revision 0

        // 正确的下一版本
        WfProcessInstance good = persistence.findProcessInstance("proc-lr");
        good.setStatus(WfProcessStatus.ACTIVE);
        good.nextRevision();
        persistence.saveProcessInstance(good);

        // 用过期的 revision 再写 → 必须抛
        WfProcessInstance stale = persistence.findProcessInstance("proc-lr");
        stale.setStatus(WfProcessStatus.SUSPENDED);
        // 故意不 nextRevision()，模拟"拿着旧对象直接改"
        org.junit.jupiter.api.Assertions.assertThrows(WfOptimisticLockException.class,
                () -> persistence.saveProcessInstance(stale));
    }

    // ==================== 查询语义 ====================

    @Test
    @DisplayName("任务查询：待办只看未完成，已办只看已完成，排序为未完成优先")
    void taskQueryFiltersAndOrders() {
        WfTask open = task("t1", "u1", WfTask.Status.ASSIGNED, 30);
        WfTask done = task("t2", "u1", WfTask.Status.COMPLETED, 50);
        done.setCompleterId("u1");
        persistence.saveTask(open);
        persistence.saveTask(done);

        List<WfTask> todo = persistence.queryTasks(new WfTaskQuery()
                .setAssignee("u1").setOpenOnly(true));
        assertEquals(1, todo.size());
        assertEquals("t1", todo.get(0).getId());

        List<WfTask> finished = persistence.queryTasks(new WfTaskQuery()
                .setCompleterId("u1").setCompletedOnly(true));
        assertEquals(1, finished.size());
        assertEquals("t2", finished.get(0).getId());

        List<WfTask> all = persistence.queryTasks(new WfTaskQuery().setAssignee("u1"));
        assertEquals(2, all.size());
        assertEquals("t1", all.get(0).getId(), "未完成任务必须排在已完成之前");
    }

    @Test
    @DisplayName("任务查询：分页切片正确")
    void taskQueryPaginates() {
        for (int i = 1; i <= 5; i++) {
            persistence.saveTask(task("tp-" + i, "u1", WfTask.Status.ASSIGNED, 50));
        }
        List<WfTask> page1 = persistence.queryTasks(new WfTaskQuery()
                .setAssignee("u1").setPageNum(1).setPageSize(2));
        List<WfTask> page3 = persistence.queryTasks(new WfTaskQuery()
                .setAssignee("u1").setPageNum(3).setPageSize(2));
        assertEquals(2, page1.size());
        assertEquals(1, page3.size());
    }

    @Test
    @DisplayName("定义版本：latest 取最大版本，versions 倒序")
    void definitionVersionsWork() {
        for (int v = 1; v <= 3; v++) {
            WfDefinition definition = new WfDefinition("k", "n" + v);
            definition.setVersion(v);
            definition.setNodes(java.util.Arrays.asList(new WfNode("s", "开始", WfNodeType.START_EVENT)));
            definition.buildIndex();
            persistence.saveDefinition(definition);
        }
        assertEquals(3, persistence.findLatestDefinition("k").getVersion());
        List<WfDefinition> versions = persistence.findDefinitionVersions("k");
        assertEquals(3, versions.size());
        assertEquals(3, versions.get(0).getVersion(), "版本列表应倒序");
        assertEquals(2, persistence.findDefinition("k", 2).getVersion());
        assertNull(persistence.findDefinition("k", 99));
    }

    @Test
    @DisplayName("按业务键查实例（审批主查询路径）")
    void findByBusinessKey() {
        for (int i = 0; i < 3; i++) {
            WfProcessInstance instance = new WfProcessInstance("p" + i, "leaveProcess", "leaveProcess:1");
            instance.setBusinessKey("ORDER-" + i);
            instance.setStartTime(new Date(1000L * i));
            persistence.saveProcessInstance(instance);
        }
        List<WfProcessInstance> found = persistence.findProcessInstancesByBusinessKey("ORDER-1");
        assertEquals(1, found.size());
        assertEquals("p1", found.get(0).getId());
    }

    // ==================== 查询条件自相矛盾时必须拒绝 ====================

    @Test
    @DisplayName("openOnly 与 completedOnly 同时为真直接抛错，不返回空列表")
    void contradictoryTaskFlagsAreRejected() {
        persistence.saveTask(task("t1", "u1", WfTask.Status.CREATED, 1));
        persistence.saveTask(task("t2", "u1", WfTask.Status.COMPLETED, 1));

        // 不加检查时，这里会静默返回空集：调用方看到"没有待办"，
        // 实际是条件打架，线上表现为"待办怎么一条都没有"，排查成本极高
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> persistence.queryTasks(new WfTaskQuery()
                        .setOpenOnly(true).setCompletedOnly(true)));
        assertTrue(ex.getMessage().contains("openOnly"), ex.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> persistence.countTasks(new WfTaskQuery()
                        .setOpenOnly(true).setCompletedOnly(true)));
    }

    @Test
    @DisplayName("创建时间区间倒置直接抛错")
    void invertedTimeRangeIsRejected() {
        persistence.saveTask(task("t1", "u1", WfTask.Status.CREATED, 1));
        assertThrows(IllegalArgumentException.class,
                () -> persistence.queryTasks(new WfTaskQuery()
                        .setCreateTimeFrom(new Date(2000L))
                        .setCreateTimeTo(new Date(1000L))));
    }

    @Test
    @DisplayName("countTasks 与 queryTasks 同口径，且忽略分页")
    void countTasksMatchesList() {
        for (int i = 0; i < 7; i++) {
            persistence.saveTask(task("t" + i, i % 2 == 0 ? "u1" : "u2",
                    i < 3 ? WfTask.Status.COMPLETED : WfTask.Status.CREATED, 1));
        }
        // i=0,1,2 已完成；i=3..6 未完成。i%2==0 的落在 u1（i=0,2,4,6），
        // 所以 u1 的未完成任务是 i=4 与 i=6 两条
        assertEquals(3, persistence.queryTasks(new WfTaskQuery()
                .setCompletedOnly(true).setPageNum(1).setPageSize(50)).size());
        assertEquals(3, persistence.countTasks(new WfTaskQuery().setCompletedOnly(true)));

        assertEquals(7, persistence.countTasks(new WfTaskQuery()));
        // 带了分页参数也必须返回全量条数，否则"共 N 条"会随翻页变化
        assertEquals(7, persistence.countTasks(new WfTaskQuery().setPageNum(2).setPageSize(3)));

        assertEquals(2, persistence.countTasks(new WfTaskQuery().setAssignee("u1")
                .setOpenOnly(true)));
        assertEquals(2, persistence.queryTasks(new WfTaskQuery().setAssignee("u1")
                .setOpenOnly(true).setPageNum(1).setPageSize(50)).size());
    }

    private WfTask task(String id, String assignee, WfTask.Status status, int priority) {
        WfTask task = new WfTask();
        task.setId(id);
        task.setAssignee(assignee);
        task.setStatus(status);
        task.setPriority(priority);
        task.setCreateTime(new Date());
        return task;
    }
}
