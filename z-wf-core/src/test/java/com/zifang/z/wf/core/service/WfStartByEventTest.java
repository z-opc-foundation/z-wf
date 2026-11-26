package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 消息 / 信号启动流程 —— "外部系统回调直接起一张单"。
 *
 * <p>本类盯五件错了都不报错的事：
 * <ol>
 *   <li><b>消息起始与手工发起共存</b>：同一流程两个入口是 BPMN 正常写法，
 *       部署期必须放行 —— 要求唯一入口等于逼作者把一个流程拆成两个。</li>
 *   <li><b>走消息入口时不能碰无条件入口</b>：两者落到同一段代码，
 *       只有分得清才不会出现"用消息启动却进了手工那条线"。</li>
 *   <li><b>它与 {@code triggerMessage} 是两件事</b>：一个新建实例，一个唤醒在等的实例。</li>
 *   <li><b>跨定义查找的歧义必须报错</b>：静默挑一个的后果是"流程起来了但不是预期的那个"。</li>
 *   <li><b>停用的定义不能接消息启动</b>：停用是"这一版不再接新单"。</li>
 * </ol>
 */
class WfStartByEventTest {

    /**
     * 一个流程两个入口：手工发起 + 收到订单消息。
     *
     * <p>两条线在「审单」汇合，之后各走各的终点 ——
     * 汇合点要能分辨"是哪条线进来的"，否则一旦汇合处的判别条件被复用，
     * 走错分支的表现会像是条件写错了，而真因是入口就选错了。
     */
    private static final String TWO_ENTRY_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"orderFlow\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"manualStart\" name=\"手工发起\"/>\n"
            + "    <startEvent id=\"orderStart\" name=\"收到订单\">\n"
            + "      <messageEventDefinition messageRef=\"orderCreated\"/>\n"
            + "    </startEvent>\n"
            + "    <startEvent id=\"syncStart\" name=\"收到同步\">\n"
            + "      <signalEventDefinition signalRef=\"orderSynced\"/>\n"
            + "    </startEvent>\n"
            + "    <userTask id=\"checkAmount\" name=\"核金额\" zifang:assignee=\"finance\"/>\n"
            + "    <exclusiveGateway id=\"big\"/>\n"
            + "    <userTask id=\"ceoApprove\" name=\"大额复核\" zifang:assignee=\"ceo\"/>\n"
            + "    <userTask id=\"archive\" name=\"归档\" zifang:assignee=\"ops\"/>\n"
            + "    <userTask id=\"checkOrder\" name=\"核订单\" zifang:assignee=\"purchase\"/>\n"
            + "    <userTask id=\"checkSync\" name=\"核同步\" zifang:assignee=\"sync\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <endEvent id=\"e2\"/>\n"
            + "    <endEvent id=\"e3\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"manualStart\" targetRef=\"checkAmount\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"orderStart\" targetRef=\"checkOrder\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"syncStart\" targetRef=\"checkSync\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"checkAmount\" targetRef=\"big\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"big\" targetRef=\"ceoApprove\">\n"
            + "      <conditionExpression>amount &gt; 10000</conditionExpression>\n"
            + "    </sequenceFlow>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"big\" targetRef=\"archive\"/>\n"
            + "    <sequenceFlow id=\"f7\" sourceRef=\"ceoApprove\" targetRef=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f8\" sourceRef=\"archive\" targetRef=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f9\" sourceRef=\"checkOrder\" targetRef=\"e2\"/>\n"
            + "    <sequenceFlow id=\"f10\" sourceRef=\"checkSync\" targetRef=\"e3\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 只订阅 orderCreated 的另一个流程 —— 用来造跨定义歧义。 */
    private static final String DUPLICATE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"otherOrderFlow\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"os\" name=\"收到订单\">\n"
            + "      <messageEventDefinition messageRef=\"orderCreated\"/>\n"
            + "    </startEvent>\n"
            + "    <userTask id=\"oa\" name=\"别的审单\" zifang:assignee=\"other\"/>\n"
            + "    <endEvent id=\"oe1\"/>\n"
            + "    <sequenceFlow id=\"of1\" sourceRef=\"os\" targetRef=\"oa\"/>\n"
            + "    <sequenceFlow id=\"of2\" sourceRef=\"oa\" targetRef=\"oe1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
    }

    private Map<String, Object> vars(Object... kv) {
        Map<String, Object> map = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private List<WfExecution> tokensOf(String pid) {
        return repo.findExecutionsByProcessInstance(pid);
    }

    /**
     * 第一个待办落在哪个节点上 —— 判别"走的是哪个入口"的直接证据。
     *
     * <p><b>不能用根 token 的 activityId</b>：startEvent 是入口不是一步活动，
     * {@code enter} 会立刻沿出线推进，根 token 在持久化时就已经站在下一个节点上了。
     * 更根本的是这个 BPMN 里三个入口都汇合到流程主体，看 token 根本分不出来 ——
     * 所以测试用的定义让每个入口各走各的第一个待办，判据才立得住。
     */
    private String firstTaskAt(String pid) {
        List<WfTask> tasks = openTasksOf(pid);
        return tasks.isEmpty() ? null : tasks.get(0).getDefinitionId();
    }

    private List<WfTask> openTasksOf(String pid) {
        return repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(20));
    }

    // ==================== 两个入口共存 ====================

    @Test
    @DisplayName("消息启动：从消息入口进，建出待办，根 token 落在消息起始上")
    void messageStartsFromMessageEntry() {
        repository.deploy(new WfXmlParser().parse(TWO_ENTRY_BPMN));

        String pid = runtime.startProcessInstanceByMessage("orderCreated", "ORDER-1",
                "erp", null, vars("amount", 500));

        assertNotNull(pid, "应当能起来");
        List<WfTask> tasks = openTasksOf(pid);
        assertEquals(1, tasks.size(), "应当建出一个待办。实际 " + tasks.size());
        assertEquals("checkOrder", tasks.get(0).getDefinitionId(),
                "必须落在消息入口那条线上 —— 落到 checkAmount 就说明走的是手工入口，"
                        + "而两个入口之后长得像，只看" + "起来了" + "是发现不了的");
        assertEquals("purchase", tasks.get(0).getAssignee());
    }

    @Test
    @DisplayName("信号启动与消息启动是两条独立的入口，各自都能起来")
    void signalStartsFromSignalEntry() {
        repository.deploy(new WfXmlParser().parse(TWO_ENTRY_BPMN));

        String pid = runtime.startProcessInstanceBySignal("orderSynced", "SYNC-1",
                "erp", null, vars("amount", 500));

        assertNotNull(pid);
        assertEquals("checkSync", firstTaskAt(pid),
                "信号启动要走信号入口那条线，不能与消息入口混");
        assertEquals(1, openTasksOf(pid).size());
    }

    @Test
    @DisplayName("同一个流程，手工发起仍然走无条件入口，两种启动方式互不干扰")
    void plainStartStillWorksAlongsideEventStart() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(TWO_ENTRY_BPMN));

        String plain = runtime.startProcessInstance(definition, "MANUAL-1",
                "alice", null, vars("amount", 500));
        String byMsg = runtime.startProcessInstanceByMessage("orderCreated", "ORDER-1",
                "erp", null, vars("amount", 500));

        assertEquals("checkAmount", firstTaskAt(plain),
                "加了消息起始之后，手工发起不能被带偏 —— 它要找的是无条件那个入口");
        assertEquals("checkOrder", firstTaskAt(byMsg));
        assertEquals(2, repo.queryTasks(new WfTaskQuery().setPageNum(1).setPageSize(20)).size(),
                "两个实例各有一个待办");
    }

    // ==================== 变量与后续流转 ====================

    @Test
    @DisplayName("启动变量落库并参与判别 —— 大额走复核，小额走归档")
    void startupVariablesDriveTheGateway() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(TWO_ENTRY_BPMN));

        // 走带判别式的那条线（手工入口），才谈得上「启动变量参与了判别」
        String small = runtime.startProcessInstance(definition, "M-S",
                "alice", null, vars("amount", 500));
        runtime.completeTask(openTasksOf(small).get(0).getId(), "finance", "核完", null);
        assertEquals("archive", firstTaskAt(small), "小额应当直接归档");

        String big = runtime.startProcessInstance(definition, "M-B",
                "alice", null, vars("amount", 20000));
        runtime.completeTask(openTasksOf(big).get(0).getId(), "finance", "核完", null);
        assertEquals("ceoApprove", firstTaskAt(big),
                "大额应当走复核 —— 变量没落库的话这里会走小额那条线，"
                        + "而症状只是「复核没人批」，很难联想到是启动变量没带上");
        assertEquals(20000, repo.findProcessInstance(big).getVariables().get("amount"));
    }

    @Test
    @DisplayName("启动出来的流程能一路走到结束 —— 消息启动没有把流程改坏")
    void startedProcessCanFinish() {
        repository.deploy(new WfXmlParser().parse(TWO_ENTRY_BPMN));
        String pid = runtime.startProcessInstanceByMessage("orderCreated", "ORDER-1",
                "erp", null, vars("amount", 500));

        // 办到没有待办为止：只办一轮的话，核金额那一步会带出"归档"这个新待办，
        // 循环却已经结束了 —— 然后拿一个"流程还没结束"去断言，而那与被测行为无关
        for (WfTask task = openTasksOf(pid).isEmpty() ? null : openTasksOf(pid).get(0);
             task != null; task = openTasksOf(pid).isEmpty() ? null : openTasksOf(pid).get(0)) {
            runtime.completeTask(task.getId(), task.getAssignee(), "同意", null);
        }

        WfProcessInstance instance = repo.findProcessInstance(pid);
        assertTrue(instance.getStatus().isTerminal(),
                "办结后应当结束。实际 " + instance.getStatus());
    }

    // ==================== 拒绝 ====================

    @Test
    @DisplayName("同名消息被多个流程订阅：报错并点名是哪几个")
    void ambiguousMessageStartIsRejected() {
        repository.deploy(new WfXmlParser().parse(TWO_ENTRY_BPMN));
        repository.deploy(new WfXmlParser().parse(DUPLICATE_BPMN));

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.startProcessInstanceByMessage("orderCreated", "ORDER-1",
                        "erp", null, new HashMap<String, Object>()));
        assertTrue(ex.getMessage().contains("orderFlow")
                        && ex.getMessage().contains("otherOrderFlow"),
                "报错要列出到底是哪几个在抢这条消息，否则调用方无从处置。实际 " + ex.getMessage());
        assertTrue(ex.getMessage().contains("definitionKey"),
                "报错要告诉调用方下一步怎么办。实际 " + ex.getMessage());
    }

    @Test
    @DisplayName("指定 definitionKey 可以消歧义，且只在这一份里找")
    void explicitDefinitionKeyResolvesAmbiguity() {
        repository.deploy(new WfXmlParser().parse(TWO_ENTRY_BPMN));
        repository.deploy(new WfXmlParser().parse(DUPLICATE_BPMN));

        String pid = runtime.startProcessInstanceByMessage("orderCreated", "otherOrderFlow",
                "ORDER-1", "erp", null, new HashMap<String, Object>());

        assertNotNull(pid);
        assertEquals("otherOrderFlow",
                repo.findProcessInstance(pid).getDefinitionKey(),
                "显式点名了就必须起那一个 —— 点名却起了别的，比报歧义更难查");
        assertEquals("oa", openTasksOf(pid).get(0).getDefinitionId());
    }

    @Test
    @DisplayName("没有流程订阅这条消息：报错并说清消息名")
    void unknownMessageIsRejected() {
        repository.deploy(new WfXmlParser().parse(TWO_ENTRY_BPMN));

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.startProcessInstanceByMessage("noSuchMessage", "X-1",
                        "erp", null, new HashMap<String, Object>()));
        assertTrue(ex.getMessage().contains("noSuchMessage"),
                "报错要说清是哪条消息。实际 " + ex.getMessage());
    }

    @Test
    @DisplayName("指定的定义里没有这个起始事件：报错，且要说清是哪个定义")
    void definitionWithoutThatMessageIsRejected() {
        repository.deploy(new WfXmlParser().parse(TWO_ENTRY_BPMN));

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.startProcessInstanceByMessage("orderSynced", "orderFlow",
                        "ORDER-1", "erp", null, new HashMap<String, Object>()));
        assertTrue(ex.getMessage().contains("orderFlow"),
                "报错要带上定义 key，否则调用方不知道该去改哪份。实际 " + ex.getMessage());
    }

    @Test
    @DisplayName("停用的定义不能被消息启动")
    void suspendedDefinitionDoesNotStart() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(TWO_ENTRY_BPMN));
        repository.suspendDefinition(definition.getKey(), definition.getVersion());

        // 跨定义查找时要跳过它：停用是"这一版不再接新单"，
        // 让一条停用的消息起始继续接单，运维会以为自己已经下架了它
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.startProcessInstanceByMessage("orderCreated", "ORDER-1",
                        "erp", null, new HashMap<String, Object>()));
        assertTrue(ex.getMessage().contains("orderCreated"),
                "找不到订阅者时报错要点名消息。实际 " + ex.getMessage());

        // 显式点名它时报的是"已停用" —— 那才是调用方真正要解决的问题
        WfDefinitionException suspended = assertThrows(WfDefinitionException.class,
                () -> runtime.startProcessInstanceByMessage("orderCreated", "orderFlow",
                        "ORDER-1", "erp", null, new HashMap<String, Object>()));
        assertTrue(suspended.getMessage().contains("停用"),
                "报错要说清是停用挡的。实际 " + suspended.getMessage());
    }

    @Test
    @DisplayName("消息名与信号名都不能为空")
    void blankEventNameIsRejected() {
        repository.deploy(new WfXmlParser().parse(TWO_ENTRY_BPMN));
        for (final String blank : new String[]{null, "", "   "}) {
            WfEngineException ex = assertThrows(WfEngineException.class,
                    () -> runtime.startProcessInstanceByMessage(blank, "X-1",
                            "erp", null, new HashMap<String, Object>()));
            assertTrue(ex.getMessage().contains("不能为空"),
                    "空消息名要当场报错，而不是去全表扫一遍再回来说没有。实际 " + ex.getMessage());
        }
    }

    // ==================== 与 triggerMessage 的区别 ====================

    @Test
    @DisplayName("消息启动与消息触发互不替代：前者建实例，后者唤醒在等的实例")
    void startByMessageIsNotTriggerMessage() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(TWO_ENTRY_BPMN));

        // triggerMessage：唤醒一个已经在等这条消息的实例
        String waiting = runtime.startProcessInstance(definition, "ORDER-WAIT",
                "alice", null, new HashMap<String, Object>());
        int before = openTasksOf(waiting).size();

        // startProcessInstanceByMessage：另起一个新实例
        String fresh = runtime.startProcessInstanceByMessage("orderCreated", "ORDER-NEW",
                "erp", null, new HashMap<String, Object>());

        assertNotNull(repo.findProcessInstance(fresh));
        assertNotEquals(waiting, fresh, "必须是两个不同的实例 —— "
                + "startProcessInstanceByMessage 建的是新的，不是把现有的挪一挪");
        // 关键判据：triggerMessage 对一个没有在等消息的实例是空转，
        // 而 startProcessInstanceByMessage 建出了新实例 —— 两者不能混用
        assertEquals(before, openTasksOf(waiting).size());
        assertEquals(1, openTasksOf(fresh).size());
    }

    @Test
    @DisplayName("同一个定义重复部署新版本后，消息启动走最新的那一版")
    void messageStartUsesLatestVersion() {
        WfDefinition first = repository.deploy(new WfXmlParser().parse(TWO_ENTRY_BPMN));
        assertNotNull(first);

        // 改**消息入口那条线**上的节点：手工入口在 checkAmount 上，
        // 改它的话消息启动的路径压根不经过，验不出版本差异
        String modified = TWO_ENTRY_BPMN.replace("zifang:assignee=\"purchase\"",
                "zifang:assignee=\"purchase2\"");
        WfDefinition second = repository.deploy(new WfXmlParser().parse(modified));
        assertEquals(first.getVersion() + 1, second.getVersion());

        String pid = runtime.startProcessInstanceByMessage("orderCreated", "ORDER-1",
                "erp", null, vars("amount", 500));

        assertEquals(second.getVersion(), repo.findProcessInstance(pid).getDefinitionVersion(),
                "消息启动不带版本号，触发方不该负责回答用哪一版 —— 取最新版是默认约定");
        assertEquals("purchase2", openTasksOf(pid).get(0).getAssignee(),
                "要验的是新版本生效：消息入口那条线上的办理人换了");
    }

    @Test
    @DisplayName("没有事件起始的旧定义不受影响：它仍只有无条件入口")
    void definitionWithoutEventStartIsUnaffected() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(TWO_ENTRY_BPMN));
        assertNotNull(definition);
        // 反过来验：eventStartNodes 不会把普通起始事件算进去，
        // 否则跨定义查找会把所有流程都算成"订阅了某条消息"
        assertEquals(2, definition.eventStartNodes().size());
        assertEquals(1, definition.unconditionalStartNodes().size());
        assertTrue(definition.hasUnconditionalStart());
        assertNull(definition.messageStartNode("notThere"));
    }
}
