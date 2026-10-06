package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 消息 / 信号边界事件。
 *
 * <p>与 {@code receiveTask} 的区别是<b>打断</b>：宿主节点上正在办的待办会被作废，
 * token 被拉到边界事件上走补偿分支。典型用途是"撤销申请""加急插队"。
 *
 * <p>本类盯三件容易做错的事：
 * <ol>
 *   <li><b>订阅要早于触发</b>：token 一进入宿主节点就得挂上订阅。
 *       晚挂的话"发消息时还没订阅上"会表现为消息被静默丢弃。</li>
 *   <li><b>订阅不能被扫描器消费</b>：消息订阅没有触发时刻，若被当成到期 job 消费掉，
 *       现象是"还没发消息，流程自己往前走了"。</li>
 *   <li><b>打断要真的打断</b>：待办作废、流程走补偿分支，而不是只留一条评论。</li>
 * </ol>
 */
class WfMessageBoundaryTest {

    /** 审批节点上挂一个"撤销"消息边界，触发后走 endEvent 结束流程。 */
    private static final String BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"cancelProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <boundaryEvent id=\"cancelBoundary\" attachedToRef=\"approve\">\n"
            + "      <messageEventDefinition messageRef=\"cancel\"/>\n"
            + "    </boundaryEvent>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"cancelBoundary\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 信号边界：广播时同时打断两个实例。 */
    private static final String SIGNAL_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"sigProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <boundaryEvent id=\"urgencyBoundary\" attachedToRef=\"approve\">\n"
            + "      <signalEventDefinition signalRef=\"urgent\"/>\n"
            + "    </boundaryEvent>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"urgencyBoundary\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 两条实例各挂一个同名的消息订阅：点对点触发时无法决定是哪一个。 */
    private static final String AMBIGUOUS_BPMN = SIGNAL_BPMN
            .replace("signalEventDefinition signalRef=\"urgent\"",
                    "messageEventDefinition messageRef=\"urgent\"")
            .replace("id=\"sigProcess\"", "id=\"ambProcess\"");

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

    private String startCancel() {
        WfDefinition definition = repository.deployXml(BPMN, "cancelProcess");
        return runtime.startProcessInstance(definition, "C-1", "alice", null,
                new HashMap<String, Object>());
    }

    // ==================== 订阅 ====================

    @Test
    @DisplayName("进入宿主节点就挂上消息订阅 —— 晚挂会让消息被静默丢弃")
    void messageSubscriptionCreatedOnEnter() {
        String pid = startCancel();

        List<com.zifang.z.wf.core.model.WfJob> subs = repo.queryJobs(
                new WfJobQuery().setType(WfJobType.MESSAGE)
                        .setProcessInstanceId(pid).setPageNum(1).setPageSize(20));
        assertEquals(1, subs.size(), "token 一进入审批节点就该挂上消息订阅");
        assertEquals("cancelBoundary", subs.get(0).getElementId());
        assertEquals("approve", subs.get(0).getAttachedToRef());
        assertEquals("cancel", subs.get(0).getSubscriptionName());
        assertEquals(null, subs.get(0).getExceptionMessage(),
                "订阅名不能写进 exceptionMessage —— 那一列是失败原因的位置，"
                        + "两个语义挤在一起时 job 一失败订阅名就没了");
        assertEquals(null, subs.get(0).getDuedate(), "消息订阅不由时间触发");

        // 扫描器不许碰订阅另有一条用例（scannerNeverConsumesSubscriptionsEvenWithDuedate）
    }

    @Test
    @DisplayName("消息到达会打断在办的流程：待办作废、走补偿分支")
    void messageInterruptsRunningInstance() {
        String pid = startCancel();
        WfTask task = oneOpenTask(pid);
        assertEquals(1, openTasks(pid).size(), "打断前宿主上有待办");

        WfProcessInstance instance = runtime.triggerMessage("cancel", pid, "admin",
                new HashMap<String, Object>(), "申请人撤回");

        assertEquals(pid, instance.getId());
        assertTrue(repo.findProcessInstance(pid).getStatus().isTerminal(),
                "打断后流程应走补偿分支到结束");
        // 打断的真身：宿主上的待办必须作废。不作废的话人还能点"同意"，
        // 表现为流程已经结束却还能提交审批
        assertEquals(0, openTasks(pid).size(), "打断后宿主节点上的待办必须作废");
        assertTrue(repo.findTask(task.getId()).getStatus() == WfTask.Status.CANCELLED
                        || repo.findTask(task.getId()).getStatus() == WfTask.Status.COMPLETED,
                "被作废的待办应落到终态，实际: " + repo.findTask(task.getId()).getStatus());
    }

    @Test
    @DisplayName("打断的原因要留在评论里 —— 事后没人说得清单子为什么结束了")
    void interruptReasonIsRecorded() {
        String pid = startCancel();
        runtime.triggerMessage("cancel", pid, "admin", new HashMap<String, Object>(), "申请人撤回");

        boolean saw = false;
        for (WfComment comment : repo.findComments(pid)) {
            if (comment.getContent() != null && comment.getContent().contains("cancel")) {
                saw = true;
            }
        }
        assertTrue(saw, "评论里应当能看到这次是被消息 [cancel] 打断的");
    }

    @Test
    @DisplayName("已结束的订阅不再响应：重复发消息不推进已完结的流程")
    void subscriptionIsConsumedOnce() {
        String pid = startCancel();
        runtime.triggerMessage("cancel", pid, "admin", new HashMap<String, Object>(), "撤回");

        assertEquals(0, repo.queryJobs(new WfJobQuery().setType(WfJobType.MESSAGE)
                .setProcessInstanceId(pid).setPageNum(1).setPageSize(20)).size(),
                "订阅必须被消费掉，不能留着让同一条补偿分支走两遍");
        assertThrows(WfEngineException.class, () -> runtime.triggerMessage("cancel", pid, "admin",
                new HashMap<String, Object>(), "再撤一次"),
                "流程已结束还响应同一条消息，说明订阅没被清干净");
    }

    // ==================== 信号 ====================

    @Test
    @DisplayName("信号广播一次打断全部订阅实例")
    void signalBroadcastInterruptsAll() {
        WfDefinition definition = repository.deployXml(SIGNAL_BPMN, "sigProcess");
        String first = runtime.startProcessInstance(definition, "S-1", "alice", null,
                new HashMap<String, Object>());
        String second = runtime.startProcessInstance(definition, "S-2", "bob", null,
                new HashMap<String, Object>());

        List<WfProcessInstance> advanced = runtime.broadcastSignal("urgent", "admin",
                new HashMap<String, Object>(), "总部通知加急");

        assertEquals(2, advanced.size(), "信号是广播语义，两个订阅者都该被叫醒");
        assertTrue(repo.findProcessInstance(first).getStatus().isTerminal());
        assertTrue(repo.findProcessInstance(second).getStatus().isTerminal());
        assertEquals(0, openTasks(first).size());
        assertEquals(0, openTasks(second).size());
    }

    @Test
    @DisplayName("点对点消息打到多个订阅时报错，不随机挑一个")
    void pointToPointAmbiguousIsRejected() {
        // 必须用【消息】订阅：triggerMessage 只消费 MESSAGE 类型的订阅，
        // 拿信号边界来触发是另一条路径（先用过时才不歧义）
        WfDefinition definition = repository.deployXml(AMBIGUOUS_BPMN, "ambProcess");
        runtime.startProcessInstance(definition, "S-1", "alice", null, new HashMap<String, Object>());
        runtime.startProcessInstance(definition, "S-2", "bob", null, new HashMap<String, Object>());

        WfEngineException e = assertThrows(WfEngineException.class,
                () -> runtime.triggerMessage("urgent", null, "admin",
                        new HashMap<String, Object>(), "x"),
                "匹配到多个时必须让调用方决定，而不是随机挑一个实例打断");
        assertTrue(e.getMessage().contains("广播") || e.getMessage().contains("无法决定"),
                "报错要点明该怎么改。实际: " + e.getMessage());
    }

    @Test
    @DisplayName("点对点消息限定了流程实例就不算歧义")
    void pointToPointScopedToInstanceIsNotAmbiguous() {
        WfDefinition definition = repository.deployXml(AMBIGUOUS_BPMN, "ambProcess");
        String first = runtime.startProcessInstance(definition, "S-1", "alice", null,
                new HashMap<String, Object>());
        runtime.startProcessInstance(definition, "S-2", "bob", null, new HashMap<String, Object>());

        WfProcessInstance instance = runtime.triggerMessage("urgent", first, "admin",
                new HashMap<String, Object>(), "只撤回这一单");
        assertEquals(first, instance.getId());
        // 另一单没被波及
        assertTrue(repo.findProcessInstance(
                repo.queryTasks(new WfTaskQuery().setPageNum(1).setPageSize(1)
                        .setAssignee("boss")).get(0).getProcessInstanceId())
                .getStatus() == WfProcessStatus.ACTIVE);
    }

    @Test
    @DisplayName("没有订阅也没有接收任务时报错，不静默丢弃")
    void noSubscriberFails() {
        assertThrows(WfEngineException.class, () -> runtime.triggerMessage("nobody", null,
                "admin", new HashMap<String, Object>(), "x"));
        assertThrows(WfEngineException.class, () -> runtime.broadcastSignal("nobody", "admin",
                new HashMap<String, Object>(), "x"));
    }

    // ==================== 部署期 ====================

    @Test
    @DisplayName("并行多触发（parallelMultiple）仍被拒：只支持单次触发，不能静默按单次跑")
    void parallelMultipleRejectedAtDeployTime() {
        String repeated = BPMN.replace(
                "<boundaryEvent id=\"cancelBoundary\" attachedToRef=\"approve\">",
                "<boundaryEvent id=\"cancelBoundary\" attachedToRef=\"approve\" parallelMultiple=\"true\">");
        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> repository.deployXml(repeated, "cancelProcess"));
        assertTrue(e.getMessage().contains("parallelMultiple"),
                "报错要说清是重复触发。实际: " + e.getMessage());
    }

    @Test
    @DisplayName("消息边界缺 messageRef 在部署期被拒：否则它永远不触发")
    void missingMessageRefRejectedAtDeployTime() {
        String noRef = BPMN.replace("messageRef=\"cancel\"", "");
        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> repository.deployXml(noRef, "cancelProcess"));
        assertTrue(e.getMessage().contains("messageRef"),
                "报错要点明缺 messageRef。实际: " + e.getMessage());
    }

    @Test
    @DisplayName("解析器读得到 messageRef / signalRef，以及 cancelActivity / parallelMultiple")
    void parserReadsEventDefinitions() {
        WfDefinition cancel = new WfXmlParser().parse(BPMN);
        assertEquals("cancel", cancel.node("cancelBoundary").getMessageName());
        assertTrue(cancel.node("cancelBoundary").isMessageBoundary());
        assertFalse(cancel.node("cancelBoundary").isTimerBoundary());
        // 不写 cancelActivity 就是中断型：不写与写 true 必须落在同一个值上，
        // 否则「作者没写」和「作者显式写了 false」会在下游被当成两回事
        assertFalse(cancel.node("cancelBoundary").isNonInterrupting(),
                "没写 cancelActivity 时默认为中断型");
        assertFalse(cancel.node("cancelBoundary").isParallelMultiple(),
                "没写 parallelMultiple 时默认为单次触发");

        WfDefinition sig = new WfXmlParser().parse(SIGNAL_BPMN);
        assertEquals("urgent", sig.node("urgencyBoundary").getSignalName());
        assertTrue(sig.node("urgencyBoundary").isSignalBoundary());
    }

    // ==================== 辅助 ====================

    private List<WfTask> openTasks(String pid) {
        return repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(20));
    }

    private WfTask oneOpenTask(String pid) {
        List<WfTask> tasks = openTasks(pid);
        assertEquals(1, tasks.size(), "应当恰好一只待办。实际 " + tasks);
        return tasks.get(0);
    }

    /**
     * 扫描器不许碰订阅。
     *
     * <p>这里刻意<b>给订阅填一个 duedate</b>：正常流程下订阅的 duedate 是 null，
     * 而 SQL 里 {@code NULL < ?} 恒不成立，扫描器本来就捞不到 —— 用正常数据测，
     * 这条断言会因为"别的原因"通过，摘掉类型闸门照样全绿（实测过）。
     *
     * <p>填上 duedate 复现的正是注释里担心的那个场景："哪天谁给订阅填了 duedate"，
     * 于是类型闸门成为唯一拦得住的东西，这条断言才真正有区分力。
     */
    @Test
    @DisplayName("扫描器只捞定时器：订阅即便带 duedate 也不该被执行")
    void scannerNeverConsumesSubscriptionsEvenWithDuedate() {
        String pid = startCancel();
        WfJob subscription = repo.queryJobs(new WfJobQuery().setType(WfJobType.MESSAGE)
                .setProcessInstanceId(pid).setPageNum(1).setPageSize(20)).get(0);
        // 人为造出"订阅有了触发时刻"的危险状态
        subscription.setDuedate(new java.util.Date(1000L));
        subscription.nextRevision();
        repo.saveJob(subscription);

        WfJobService jobService = new WfJobService(repo,
                new WfRuntimeService(new WfRepositoryService(repo), repo,
                        new WfEngine(), new WfHookDispatcher()));
        int executed = jobService.executeDueJobs(new java.util.Date());
        assertEquals(0, executed,
                "扫描器把消息订阅当到期 job 执行了：还没发消息，流程自己往前走了");
        assertEquals(WfProcessStatus.ACTIVE, repo.findProcessInstance(pid).getStatus(),
                "流程不该被扫描器推走");
    }
}
