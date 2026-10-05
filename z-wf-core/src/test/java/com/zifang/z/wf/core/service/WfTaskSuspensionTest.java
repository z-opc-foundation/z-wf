package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfProcessInstanceQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 任务级挂起 / 恢复。
 *
 * <p>这个功能最容易做成"字段加了、闸门没加"：挂起标志写进任务，所有操作照旧，
 * 于是挂起看着生效（查询里能看到 suspended=true）但办结照样成功。
 * 所以本类对<b>每一个</b>会改动任务状态的操作都断言它被拒 ——
 * 只测 completeTask 的话，claim/transfer/delegate/withdraw/jump 全可以漏掉。
 *
 * <p>服务层语义在内存实现上跑；JDBC 那侧由 {@code JdbcWorkflowPersistenceTest}
 * 用同数据对拍（挂起列必须真落库）。
 */
class WfTaskSuspensionTest {

    private static final String BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"suspProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:candidateUsers=\"boss,carol\"/>\n"
            + "    <userTask id=\"review\" name=\"复核\" zifang:candidateUsers=\"ceo\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"review\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"review\" targetRef=\"e1\"/>\n"
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
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
        taskService = new WfTaskService(repository, repo, runtime, new WfHookDispatcher());
    }

    private String startOne() {
        WfDefinition definition = repository.deployXml(BPMN, "suspProcess");
        return runtime.startProcessInstance(definition, "T-1", "alice", null,
                new HashMap<String, Object>());
    }

    private WfTask taskOf(String pid) {
        List<WfTask> tasks = repo.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(pid).setOpenOnly(true).setPageNum(1).setPageSize(20));
        assertEquals(1, tasks.size(), "应当恰好一只待办。实际 " + tasks);
        return tasks.get(0);
    }

    // ==================== 挂起真的挡住每个操作 ====================

    @Test
    @DisplayName("挂起后办结被拒，且报错要点明是挂起而不是已结束")
    void suspendBlocksComplete() {
        String pid = startOne();
        WfTask task = taskOf(pid);
        taskService.claim(task.getId(), "boss", new java.util.ArrayList<String>());
        taskService.suspendTask(task.getId(), "supervisor");

        WfEngineException e = assertThrows(WfEngineException.class,
                () -> runtime.completeTask(task.getId(), "boss", "同意", new HashMap<>()));
        assertTrue(e.getMessage().contains("已挂起"),
                "挂起是能一键恢复的运营动作，报成[已结束]会让人以为单子办完了，"
                        + "去查历史而不会去恢复。实际: " + e.getMessage());
        assertTrue(e.getMessage().contains("activateTask"), "报错要说清怎么恢复");
    }

    @Test
    @DisplayName("挂起后认领/转办/委派/撤回/强制完成/跳转全被拒")
    void suspendBlocksEveryStateChange() {
        String pid = startOne();
        WfTask task = taskOf(pid);
        taskService.claim(task.getId(), "boss", new java.util.ArrayList<String>());
        taskService.suspendTask(task.getId(), "supervisor");

        // 六个操作**全部**走一遍再统一报：断言在第一个失败处就中断的话，
        // 后面五个根本没验到，反向验证时只会红一条，看不出漏了几处闸门
        List<String> notBlocked = new java.util.ArrayList<String>();
        check("认领", notBlocked, () -> taskService.claim(task.getId(), "boss",
                new java.util.ArrayList<String>()));
        check("转办", notBlocked, () -> taskService.transfer(task.getId(), "boss", "carol", "转"));
        check("委派", notBlocked, () -> taskService.delegate(task.getId(), "boss", "dave", "委"));
        check("撤回", notBlocked, () -> taskService.withdraw(task.getId(), "boss", "撤"));
        check("强制完成", notBlocked, () -> taskService.forceComplete(task.getId(), "boss", "强",
                new HashMap<String, Object>()));
        check("跳转", notBlocked, () -> taskService.jump(task.getId(), "boss", "review",
                "驳回上一环节"));
        assertTrue(notBlocked.isEmpty(),
                "这些操作绕过了挂起闸门（应当被拒却执行成功）: " + notBlocked);

        // 被拒的操作一个都不能留下痕迹
        WfTask after = repo.findTask(task.getId());
        assertEquals("boss", after.getAssignee(), "转办被拒后办理人不能变");
        assertTrue(after.isOpen(), "任务不能被这些操作改掉状态");
    }

    @Test
    @DisplayName("恢复后一切照旧")
    void activateRestoresNormalFlow() {
        String pid = startOne();
        WfTask task = taskOf(pid);
        taskService.suspendTask(task.getId(), "supervisor");
        taskService.activateTask(task.getId(), "supervisor");

        assertFalse(repo.findTask(task.getId()).isSuspended());
        runtime.completeTask(task.getId(), "boss", "同意", new HashMap<>());
        assertEquals(1, repo.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(pid).setOpenOnly(true).setPageNum(1).setPageSize(20)).size(),
                "应当推进到下一个节点");
    }

    @Test
    @DisplayName("挂起不改变任务状态与待办归属，只是挡住操作")
    void suspensionKeepsTaskVisibleAndOpen() {
        String pid = startOne();
        WfTask task = taskOf(pid);
        taskService.claim(task.getId(), "boss", new java.util.ArrayList<String>());
        taskService.suspendTask(task.getId(), "supervisor");

        WfTask after = repo.findTask(task.getId());
        assertTrue(after.isOpen(), "挂起不是结束");
        // 挂起只加一个标志位，不动 status —— 顺带钉住"挂起不是办结也不是撤回"
        assertEquals(WfTask.Status.ASSIGNED, after.getStatus());
        assertEquals("supervisor", after.getVariables().get("suspendedBy"),
                "挂起人要记下来，否则事后没人说得清这张单为什么停过");

        // 默认不过滤：挂起的仍能在待办里看到
        assertEquals(1, repo.queryTasks(new WfTaskQuery()
                .setAssignee("boss").setPageNum(1).setPageSize(20)).size(),
                "挂起常是[等条件成立]而不是[单子不存在]，藏起来只会让人以为单丢了");
        // 候选池视角同样看得到
        assertEquals(1, repo.queryTasks(new WfTaskQuery()
                .setCandidateUsers(java.util.Arrays.asList("carol"))
                .setPageNum(1).setPageSize(20)).size());

        // 显式过滤才分得开，且 count 与列表同口径
        assertEquals(1, repo.countTasks(new WfTaskQuery().setSuspendedOnly(Boolean.TRUE)));
        assertEquals(0, repo.countTasks(new WfTaskQuery().setSuspendedOnly(Boolean.FALSE)));
        assertEquals(1, repo.countTasks(new WfTaskQuery().setSuspendedOnly(null)));

        taskService.activateTask(task.getId(), "supervisor");
        assertEquals(0, repo.countTasks(new WfTaskQuery().setSuspendedOnly(Boolean.TRUE)));
        assertEquals(1, repo.countTasks(new WfTaskQuery().setSuspendedOnly(Boolean.FALSE)));
    }

    @Test
    @DisplayName("重复挂起/恢复不报错，流程不受影响")
    void suspendAndActivateAreIdempotent() {
        String pid = startOne();
        WfTask task = taskOf(pid);

        taskService.suspendTask(task.getId(), "supervisor");
        taskService.suspendTask(task.getId(), "supervisor");
        assertTrue(repo.findTask(task.getId()).isSuspended());
        assertEquals(1, repo.countTasks(new WfTaskQuery().setSuspendedOnly(Boolean.TRUE)),
                "重复挂起不该产生两条");

        taskService.activateTask(task.getId(), "supervisor");
        taskService.activateTask(task.getId(), "supervisor");
        assertFalse(repo.findTask(task.getId()).isSuspended());
    }

    @Test
    @DisplayName("已结束的任务不能挂起")
    void cannotSuspendFinishedTask() {
        String pid = startOne();
        WfTask first = taskOf(pid);
        taskService.claim(first.getId(), "boss", new java.util.ArrayList<String>());
        runtime.completeTask(first.getId(), "boss", "同意", new HashMap<>());
        WfTask second = taskOf(pid);
        taskService.claim(second.getId(), "ceo", new java.util.ArrayList<String>());
        runtime.completeTask(second.getId(), "ceo", "同意", new HashMap<>());

        assertThrows(WfEngineException.class, () -> taskService.suspendTask(second.getId(), "sup"));
    }

    @Test
    @DisplayName("挂起只影响这一张待办，同流程的其它任务照常")
    void suspensionIsPerTask() {
        String pid = startOne();
        WfTask first = taskOf(pid);
        taskService.claim(first.getId(), "boss", new java.util.ArrayList<String>());
        runtime.completeTask(first.getId(), "boss", "同意", new HashMap<>());
        WfTask second = taskOf(pid);

        taskService.suspendTask(second.getId(), "supervisor");
        assertTrue(repo.findTask(second.getId()).isSuspended());
        // 流程本身没被挂起
        assertTrue(repo.findProcessInstance(pid).getStatus().isActive(),
                "任务挂起不该停住整个流程");
    }

    /** 记录"没被拦住"的操作，最后一次性报出来，而不是在第一个就中断。 */
    private void check(String action, List<String> notBlocked, Runnable op) {
        try {
            op.run();
            notBlocked.add(action);
        } catch (WfEngineException e) {
            // 被拒对了，但报错要能让人判断下一步动作：挂起能一键恢复，结束不能
            if (!e.getMessage().contains("已挂起")) {
                notBlocked.add(action + "(报错未点明挂起: " + e.getMessage() + ")");
            }
        }
    }
}
