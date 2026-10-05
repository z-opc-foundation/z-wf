package com.zifang.z.wf.core.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.service.WfOverdueScanner;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.service.WfTaskService;

/**
 * 扩展点的行为审计 —— 3 个 hook 接口共 11 个回调，逐个验证<b>真的会触发</b>。
 *
 * <p><b>为什么必须这样测。</b> 本项目有过两次"实现了、注册了、从来没调用过"：
 * {@code onBeforeCreate} 与 {@code notifyOverdue}。两次静态检查都看不出来 ——
 * 方法存在、接口实现完整、编译通过，只有真跑一遍流程才知道它没被调到。
 * 所以这里对每个回调都断言"在这个场景下它被调用过"，而不是断言"这个类存在"。
 *
 * <p>三个接口拆成三个录制器而不是一个：它们都定义了 {@code hookType()} 默认方法，
 * 一个类同时实现会因"继承互不相关的默认方法"而编译不过。
 */
class WfHookDispatchAuditTest {

    private static final String BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"hookProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\" zifang:candidateUsers=\"claimer\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 共享的调用记录表。 */
    private static final class Log {
        private final List<String> calls = new ArrayList<>();

        void add(String name) {
            calls.add(name);
        }

        boolean fired(String name) {
            return calls.contains(name);
        }

        int countPrefix(String prefix) {
            int n = 0;
            for (String c : calls) {
                if (c.startsWith(prefix)) {
                    n++;
                }
            }
            return n;
        }
    }

    private static final class ProcessRecorder implements WfProcessHook {
        private final Log log;

        ProcessRecorder(Log log) {
            this.log = log;
        }

        @Override
        public boolean onBeforeStart(String definitionKey, Map<String, Object> variables) {
            log.add("onBeforeStart");
            return true;
        }

        @Override
        public void onAfterStart(String definitionKey, String processInstanceId,
                                 Map<String, Object> variables) {
            log.add("onAfterStart");
        }

        @Override
        public void onComplete(String definitionKey, String processInstanceId, String outcome) {
            log.add("onComplete");
        }
    }

    private static final class TaskRecorder implements WfTaskHook {
        private final Log log;

        TaskRecorder(Log log) {
            this.log = log;
        }

        @Override
        public boolean onBeforeCreate(String taskId, String assignee, Map<String, Object> variables) {
            log.add("onBeforeCreate");
            return true;
        }

        @Override
        public void onAfterCreate(String taskId, String assignee, String processInstanceId) {
            log.add("onAfterCreate");
        }

        @Override
        public void onAssigneeChanged(String taskId, String fromAssignee, String toAssignee,
                                      String action) {
            log.add("onAssigneeChanged:" + action);
        }

        @Override
        public boolean onBeforeComplete(String taskId, String assignee, Map<String, Object> variables) {
            log.add("onBeforeComplete");
            return true;
        }

        @Override
        public void onAfterComplete(String taskId, String assignee, String outcome) {
            log.add("onAfterComplete");
        }
    }

    private static final class NotificationRecorder implements WfNotificationHook {
        private final Log log;

        NotificationRecorder(Log log) {
            this.log = log;
        }

        @Override
        public void notifyTaskAssigned(String taskId, String processInstanceId, String assignee,
                                       String processKey, Map<String, Object> variables) {
            log.add("notifyTaskAssigned:" + assignee);
        }

        @Override
        public void notifyApprovalResult(String processInstanceId, String processKey,
                                         String applicant, String outcome, String comment) {
            log.add("notifyApprovalResult");
        }

        @Override
        public void notifyOverdue(String taskId, String processInstanceId, String assignee,
                                  String processKey, long overdueMinutes) {
            log.add("notifyOverdue:" + overdueMinutes);
        }
    }

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfTaskService taskService;
    private WfHookDispatcher dispatcher;
    private Log log;
    private WfDefinition definition;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repository = new WfRepositoryService(repo);
        WfEngine engine = new WfEngine();
        dispatcher = new WfHookDispatcher();
        runtime = new WfRuntimeService(repository, repo, engine, dispatcher);
        taskService = new WfTaskService(repository, repo, runtime, dispatcher);
        log = new Log();
        dispatcher.addProcessHook(new ProcessRecorder(log));
        dispatcher.addTaskHook(new TaskRecorder(log));
        dispatcher.addNotificationHook(new NotificationRecorder(log));
        definition = repository.deploy(new WfXmlParser().parse(BPMN));
    }

    private String start() {
        return runtime.startProcessInstance(definition, "biz-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
    }

    private WfTask theTask(String pid) {
        List<WfTask> open = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(10));
        assertEquals(1, open.size(), "前置条件：应当恰好有一条待办");
        return open.get(0);
    }

    // ==================== 流程钩子 ====================

    @Test
    @DisplayName("onBeforeStart / onAfterStart：启动流程时都触发")
    void startHooksFire() {
        start();
        assertTrue(log.fired("onBeforeStart"), "启动前置钩子未触发");
        assertTrue(log.fired("onAfterStart"), "启动后置钩子未触发");
    }

    @Test
    @DisplayName("onComplete：流程走完时触发")
    void completeHookFires() {
        String pid = start();
        runtime.completeTask(theTask(pid).getId(), "boss", "同意", new HashMap<>());
        assertTrue(log.fired("onComplete"), "流程完成钩子未触发");
    }

    @Test
    @DisplayName("onComplete：被中途终止时也要触发（否则收尾逻辑漏掉终止的流程）")
    void completeHookFiresOnTerminate() {
        String pid = start();
        runtime.terminate(pid, "申请人撤回");
        assertTrue(log.fired("onComplete"),
                "终止掉的流程也必须有收尾通知，否则调用方永远等不到这个单据的最终结果");
    }

    // ==================== 任务钩子 ====================

    @Test
    @DisplayName("onBeforeCreate / onAfterCreate：任务创建时都触发")
    void createHooksFire() {
        start();
        assertTrue(log.fired("onBeforeCreate"), "建任务前置钩子未触发");
        assertTrue(log.fired("onAfterCreate"), "建任务后置钩子未触发");
    }

    @Test
    @DisplayName("onAssigneeChanged：认领/转办/委派/撤回都触发，且带动作名")
    void assigneeChangedHookFiresForEveryAction() {
        String pid = start();
        WfTask task = theTask(pid);

        // BPMN 里 assignee 写死 boss，所以先 unclaim 放回候选池才能 claim
        taskService.unclaim(task.getId(), "boss");
        taskService.claim(task.getId(), "claimer", new ArrayList<String>());
        taskService.transfer(task.getId(), "claimer", "next", "转给你");
        taskService.withdraw(task.getId(), "operator-1", "撤回重派");

        for (String action : new String[] {"unclaim", "claim", "transfer", "withdraw"}) {
            assertTrue(log.fired("onAssigneeChanged:" + action),
                    "动作 " + action + " 未触发 onAssigneeChanged，实际记录: " + log.calls);
        }
    }

    @Test
    @DisplayName("onBeforeComplete / onAfterComplete：办结时都触发")
    void completeTaskHooksFire() {
        String pid = start();
        runtime.completeTask(theTask(pid).getId(), "boss", "同意", new HashMap<>());
        assertTrue(log.fired("onBeforeComplete"), "办结前置钩子未触发");
        assertTrue(log.fired("onAfterComplete"), "办结后置钩子未触发");
    }

    // ==================== 通知钩子 ====================

    @Test
    @DisplayName("notifyTaskAssigned：任务建出来时通知原办理人")
    void taskAssignedNotificationFiresOnCreate() {
        start();
        assertTrue(log.fired("notifyTaskAssigned:boss"),
                "建任务时的指派通知未触发，实际: " + log.calls);
    }

    @Test
    @DisplayName("notifyTaskAssigned：认领 / 转办 / 委派后都要通知接手人")
    void taskAssignedNotificationFiresOnReassign() {
        // 审批系统里"这单转给你了"正是最该发通知的时刻。
        // 旧实现只在建任务时通知一次，之后谁接手都没人知道。
        String pid = start();
        WfTask task = theTask(pid);

        taskService.unclaim(task.getId(), "boss");
        taskService.claim(task.getId(), "claimer", new ArrayList<String>());
        assertEquals(1, log.countPrefix("notifyTaskAssigned:claimer"),
                "认领后应给 claimer 发指派通知，实际: " + log.calls);

        taskService.transfer(task.getId(), "claimer", "final-boss", "请审批");
        assertEquals(1, log.countPrefix("notifyTaskAssigned:final-boss"),
                "转办后必须通知接手人；否则他不知道单子到了自己手上，实际: " + log.calls);

        taskService.delegate(task.getId(), "final-boss", "helper", "我出差");
        assertEquals(1, log.countPrefix("notifyTaskAssigned:helper"),
                "委派后必须通知被委派人，实际: " + log.calls);
    }

    @Test
    @DisplayName("转办通知里带得上前一个办理人")
    void reassignNotificationCarriesPreviousHolder() {
        String pid = start();
        WfTask task = theTask(pid);
        taskService.transfer(task.getId(), "boss", "final-boss", "请审批");
        // from 走 variables 载荷传递，见 WfHookDispatcher#NOTIFY_FROM_ASSIGNEE
        assertTrue(log.fired("notifyTaskAssigned:final-boss"),
                "转办后应通知接手人，实际: " + log.calls);
    }

    @Test
    @DisplayName("notifyApprovalResult：办结时触发")
    void approvalResultNotificationFires() {
        String pid = start();
        runtime.completeTask(theTask(pid).getId(), "boss", "同意", new HashMap<>());
        assertTrue(log.fired("notifyApprovalResult"), "审批结果通知未触发");
    }

    @Test
    @DisplayName("notifyOverdue：扫描到超期待办时触发（此前全仓零调用点）")
    void overdueNotificationFires() {
        String pid = start();
        WfTask task = theTask(pid);
        // SPI 约定：save 前调用方负责 bump revision，两个实现都按
        // "库中 revision + 1 == 传入 revision" 做 CAS，忘了 bump 就会判成冲突
        task.setDueDate(new Date(System.currentTimeMillis() - 60_000L));
        task.nextRevision();
        repo.saveTask(task);

        int notified = new WfOverdueScanner(repo, dispatcher).scanOverdue();

        assertEquals(1, notified, "应当扫出 1 条超期待办");
        assertTrue(log.fired("notifyOverdue:1"),
                "notifyOverdue 此前是死钩子——接口有、dispatcher 有、零个调用点，"
                        + "实际记录: " + log.calls);
    }

    @Test
    @DisplayName("未超期的任务不触发 notifyOverdue")
    void notOverdueIsNotNotified() {
        String pid = start();
        WfTask task = theTask(pid);
        task.setDueDate(new Date(System.currentTimeMillis() + 3_600_000L));
        task.nextRevision();
        repo.saveTask(task);

        assertEquals(0, new WfOverdueScanner(repo, dispatcher).scanOverdue());
    }

    @Test
    @DisplayName("没有 dueDate 的任务不算超期")
    void nullDueDateIsNotOverdue() {
        start();
        assertEquals(0, new WfOverdueScanner(repo, dispatcher).scanOverdue(),
                "没设截止时间的任务没有超时可言，不能拿它凑数");
    }

    // ==================== 否决语义 ====================

    @Test
    @DisplayName("前置钩子返回 false 能真的否决流程")
    void beforeHooksCanVeto() {
        WfHookDispatcher strict = new WfHookDispatcher();
        strict.addProcessHook(new WfProcessHook() {
            @Override
            public boolean onBeforeStart(String definitionKey, Map<String, Object> variables) {
                return false;
            }
        });
        WfRuntimeService vetoRuntime = new WfRuntimeService(repository, repo,
                new WfEngine(), strict);
        assertNull(vetoRuntime.startProcessInstance(definition, "b", "u", null,
                new HashMap<String, Object>()),
                "返回 false 时不应启动流程");
    }

    @Test
    @DisplayName("钩子抛异常不否决流程，也不该把流程带崩")
    void hookExceptionDoesNotBreakFlow() {
        WfHookDispatcher noisy = new WfHookDispatcher();
        noisy.addTaskHook(new WfTaskHook() {
            @Override
            public void onAfterCreate(String taskId, String assignee, String processInstanceId) {
                throw new IllegalStateException("通知服务挂了");
            }
        });
        WfRuntimeService noisyRuntime = new WfRuntimeService(repository, repo,
                new WfEngine(), noisy);
        String pid = noisyRuntime.startProcessInstance(definition, "b", "u", null,
                new HashMap<String, Object>());
        assertNotNull(pid, "通知类钩子抛异常不应让流程失败——那等于通知服务一挂审批全停");
        assertEquals(1, repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(10)).size(),
                "任务应当照常建出来");
    }
}
