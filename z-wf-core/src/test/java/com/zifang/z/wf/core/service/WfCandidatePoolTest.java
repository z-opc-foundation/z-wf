package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 运行时增删候选池。
 *
 * <p>这个功能最容易做成"接口有了、实际不生效"：加完候选人不报错，
 * 但因为别的什么没存下去，下一次读取就恢复原样。所以本类的断言一律落在
 * <b>行为</b>上 —— 加完之后那个人到底能不能认领，而不是"列表里有这个名字"。
 *
 * <p>服务层在内存实现上跑；JDBC 那侧由 {@code JdbcWorkflowPersistenceTest} 对拍，
 * 因为候选列的读写曾经有洞（只写 INSERT 不写 UPDATE），内存实现天然测不出来。
 */
class WfCandidatePoolTest {

    private static final String BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"candProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:candidateUsers=\"alice\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfTaskService taskService;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        WfHookDispatcher hooks = new WfHookDispatcher();
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), hooks);
        taskService = new WfTaskService(repository, repo, runtime, hooks);
    }

    private String startOne() {
        WfDefinition definition = repository.deployXml(BPMN, "candProcess");
        return runtime.startProcessInstance(definition, "C-1", "bob", null,
                new HashMap<String, Object>());
    }

    private WfTask taskOf(String pid) {
        List<WfTask> tasks = repo.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(pid).setOpenOnly(true).setPageNum(1).setPageSize(20));
        assertEquals(1, tasks.size(), "应当恰好一只待办。实际 " + tasks);
        return tasks.get(0);
    }

    @Test
    @DisplayName("加了候选人就真能认领 —— 判据是行为不是字段")
    void addedCandidateCanActuallyClaim() {
        String pid = startOne();
        WfTask task = taskOf(pid);

        // 加之前：carol 认领不了
        assertFalse(repo.findTask(task.getId()).isClaimableBy("carol",
                new java.util.ArrayList<String>()));
        WfEngineException before = assertThrows(WfEngineException.class,
                () -> taskService.claim(task.getId(), "carol", new java.util.ArrayList<String>()));

        taskService.addCandidateUser(task.getId(), "carol");

        // 加之后：能认领了。这条才是功能生效的证据
        WfTask claimed = taskService.claim(task.getId(), "carol", new java.util.ArrayList<String>());
        assertEquals("carol", claimed.getAssignee());
        assertEquals(WfTask.Status.ASSIGNED, repo.findTask(task.getId()).getStatus(),
                "认领没落库，状态还停在未认领");
        assertTrue(before.getMessage().contains("不在"),
                "认领被拒的报错要说清是候选范围问题。实际: " + before.getMessage());
    }

    @Test
    @DisplayName("候选组通过组成员生效")
    void candidateGroupWorksThroughMembership() {
        String pid = startOne();
        WfTask task = taskOf(pid);
        taskService.addCandidateGroup(task.getId(), "finance");

        assertTrue(repo.findTask(task.getId()).isClaimableBy("dave",
                        Arrays.asList("finance", "hr")),
                "组成员应当能认领");
        // 不在组里的认领不了
        assertFalse(repo.findTask(task.getId()).isClaimableBy("erin",
                Arrays.asList("hr")));
    }

    @Test
    @DisplayName("移出候选人后立刻认领不了")
    void removingCandidateRevokesClaimability() {
        String pid = startOne();
        WfTask task = taskOf(pid);
        assertTrue(repo.findTask(task.getId()).isClaimableBy("alice",
                new java.util.ArrayList<String>()));

        taskService.removeCandidateUser(task.getId(), "alice");
        assertFalse(repo.findTask(task.getId()).isClaimableBy("alice",
                new java.util.ArrayList<String>()));
        assertThrows(WfEngineException.class,
                () -> taskService.claim(task.getId(), "alice", new java.util.ArrayList<String>()),
                "移出后还能认领，说明移出没生效");
    }

    @Test
    @DisplayName("增删幂等：重复加不产生重复项，重复移不报错")
    void addAndRemoveAreIdempotent() {
        String pid = startOne();
        WfTask task = taskOf(pid);

        taskService.addCandidateUser(task.getId(), "carol");
        taskService.addCandidateUser(task.getId(), "carol");
        assertEquals(1, java.util.Collections.frequency(
                repo.findTask(task.getId()).getCandidateUsers(), "carol"),
                "重复加产生了重复项，前端会看到两个同名候选人");

        taskService.removeCandidateUser(task.getId(), "carol");
        taskService.removeCandidateUser(task.getId(), "carol");
        assertFalse(repo.findTask(task.getId()).getCandidateUsers().contains("carol"));

        // 移一个本来就不在的人：幂等，不该炸
        taskService.removeCandidateUser(task.getId(), "nobody");
        taskService.removeCandidateGroup(task.getId(), "noGroup");
    }

    @Test
    @DisplayName("空值被拒，不静默忽略")
    void blankCandidateRejected() {
        String pid = startOne();
        WfTask task = taskOf(pid);
        assertThrows(WfEngineException.class,
                () -> taskService.addCandidateUser(task.getId(), null));
        assertThrows(WfEngineException.class,
                () -> taskService.addCandidateUser(task.getId(), "   "));
        assertThrows(WfEngineException.class,
                () -> taskService.addCandidateGroup(task.getId(), ""));
    }

    @Test
    @DisplayName("挂起的任务允许调候选池 —— 换人正是等待期间最常见的处置")
    void suspendedTaskAllowsCandidateChange() {
        String pid = startOne();
        WfTask task = taskOf(pid);
        taskService.suspendTask(task.getId(), "supervisor");

        // 七个"办理类"闸门拦的是把单办了；换候选不是办理，不该被同一把锁挡住
        taskService.addCandidateUser(task.getId(), "carol");
        assertTrue(repo.findTask(task.getId()).getCandidateUsers().contains("carol"));
        assertTrue(repo.findTask(task.getId()).isSuspended(), "调候选池不该顺手把挂起解了");
    }

    @Test
    @DisplayName("候选人和候选组都能在待办列表里看到这张单")
    void candidatesSeeTaskInTodoList() {
        String pid = startOne();
        WfTask task = taskOf(pid);

        // 结构性回归：待办列表以前只查 assignee/owner，候选池根本不参与，
        // 于是"我是候选人"的人一条待办都看不到 —— BPMN 配得再对也没用。
        // 这里的 alice 是 BPMN 里配的候选人，她必须能看到。
        assertEquals(1, todoOf("alice").size(),
                "BPMN 配的候选人在待办里看不到单：待办查询不查候选池");

        taskService.addCandidateUser(task.getId(), "carol");
        taskService.addCandidateGroup(task.getId(), "finance");

        assertEquals(1, todoOf("carol").size(), "加进去的候选用户应当看到待办");
        assertEquals(1, taskService.getTodoList("dave", Arrays.asList("finance"), 1, 20).size(),
                "候选组成员也应当看到待办");
        assertTrue(todoOf("erin").isEmpty(), "无关的人不该看到");

        // 移出后立刻从待办消失
        taskService.removeCandidateUser(task.getId(), "carol");
        assertTrue(todoOf("carol").isEmpty(), "移出后待办里不该还有这张单");
    }

    @Test
    @DisplayName("我是办理人但不在候选池里 —— 待办仍要能看到")
    void assigneeSeesTaskEvenWithoutBeingCandidate() {
        String pid = startOne();
        WfTask task = taskOf(pid);
        taskService.claim(task.getId(), "alice", new java.util.ArrayList<String>());

        // 这是"四者取或"里最容易被写成"且"的一条：认领后 alice 成了 assignee，
        // 而任务上从没把她加进 candidateUsers
        assertEquals(1, todoOf("alice").size(),
                "认领后办理人在自己的待办里看不到单 —— 身份条件被写成且了");
    }

    @Test
    @DisplayName("可认领列表按人过滤，且 total 与列表同口径")
    void claimableListIsPerUserAndCountMatches() {
        String pid = startOne();
        WfTask task = taskOf(pid);
        taskService.addCandidateUser(task.getId(), "carol");
        taskService.addCandidateGroup(task.getId(), "finance");

        assertEquals(1, taskService.getClaimableList("carol", null, 1, 20).size(),
                "按候选用户配的流程以前谁也认领不了：可认领列表只按候选组过滤");
        assertEquals(1, taskService.getClaimableList("dave",
                Arrays.asList("finance"), 1, 20).size(), "候选组成员也该能看到");

        // 无关的人一条都看不到 —— 旧实现把 userId 丢了，每个人的列表都一样
        assertEquals(0, taskService.getClaimableList("erin", null, 1, 20).size());
        assertEquals(0, taskService.getClaimableList("erin", Arrays.asList("hr"), 1, 20).size());

        // total 必须与列表同条件：旧签名只有 groups，不同用户的 total 相同，
        // 前端翻页时对不上
        assertEquals(1, taskService.countClaimableList("carol", null));
        assertEquals(0, taskService.countClaimableList("erin", null));
        assertEquals(1, taskService.countClaimableList("dave", Arrays.asList("finance")));

        // 认领后不再可认领（已被分配）
        taskService.claim(task.getId(), "carol", new java.util.ArrayList<String>());
        assertEquals(0, taskService.countClaimableList("carol", null),
                "已认领的还在可认领列表里，两个列表会同时出现同一张单");
    }

    private List<WfTask> todoOf(String userId) {
        return taskService.getTodoList(userId, new java.util.ArrayList<String>(), 1, 20);
    }

    @Test
    @DisplayName("已结束的任务不能调候选池")
    void finishedTaskRejectsCandidateChange() {
        String pid = startOne();
        WfTask task = taskOf(pid);
        taskService.claim(task.getId(), "alice", new java.util.ArrayList<String>());
        runtime.completeTask(task.getId(), "alice", "同意", new HashMap<>());

        assertThrows(WfEngineException.class,
                () -> taskService.addCandidateUser(task.getId(), "carol"));
    }
}
