package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfDefinitionCodec;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 升级事件（{@code escalationEventDefinition}）—— "这件事不能就这么算了"。
 *
 * <p>第 26 轮补齐。盯的是四件错了都不报错的事：
 * <ol>
 *   <li><b>升级订阅必须是自己的 job 类型。</b>它和信号长得极像（都是广播、都是按名字匹配），
 *       所以一旦并进 {@code SIGNAL}，系统照样跑得通，只有"升级后待办还挂着、人没变"
 *       这个后果与作者意图相反 —— 而且图上看不出任何异常。</li>
 *   <li><b>升级边界上配定时器必须部署期报错。</b>{@code isTimerBoundary()} 只看
 *       {@code timerType} 有没有被设，而运行期升级走的是订阅型分支 ——
 *       两边对同一个节点给出两套判定，于是"超时 3 天自动升级"会变成一个
 *       <b>既不报错、也不到期的哑定时器</b>。</li>
 *   <li><b>捕获事件要明说不支持。</b>捕获到升级时 token 已经停在那个节点上，
 *       而 BPMN 要求的是"在原地再长出一条 token"；现有三种捕获都是"把 token 搬走"，
 *       硬套会得到一个看起来能跑的错语义。</li>
 *   <li><b>零订阅不是错误但必须留痕。</b>与抛信号同一条约定。</li>
 * </ol>
 */
class WfEscalationTest {

    private static final String NS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n";

    private static final String NS_END = "</definitions>\n";

    /**
     * 升级事件定义元素。两个夹具里都出现它，而拼冲突夹具时要用它当锚点 ——
     * 散成两个字面量的话，改了一处就会让 replace 静默不命中。
     */
    private static final String ESCALATION_ELEMENT =
            "<escalationEventDefinition escalationRef=\"overdue\"/>";

    /**
     * 升级边界（中断型）挂在 boss 的审批上：升级后 boss 的待办作废，
     * token 搬到边界、沿出线交给 director。
     *
     * <p>图里特意不放汇合：中断型一旦触发，原宿主那条分支就结束了，
     * 放汇合反而会让"到底该不该等另一条"这件事显得像要判断 —— 它不用判断。
     */
    private static final String INTERRUPTING_BPMN = NS
            + "  <process id=\"escInt\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <boundaryEvent id=\"escBoundary\" attachedToRef=\"approve\">\n"
            + "      " + ESCALATION_ELEMENT + "\n"
            + "    </boundaryEvent>\n"
            + "    <userTask id=\"director\" name=\"总审批\" zifang:assignee=\"director\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"escBoundary\" targetRef=\"director\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"director\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + NS_END;

    /**
     * 非中断型：升级后 boss 的待办还在，director 那边并行开一条单。
     *
     * <p>汇合用 {@code parallelGateway}：挂在任务上的多条入线会变成"进两次、建两条待办"。
     */
    private static final String NON_INTERRUPTING_BPMN = NS
            + "  <process id=\"escNi\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <boundaryEvent id=\"escBoundary\" attachedToRef=\"approve\""
            + " cancelActivity=\"false\">\n"
            + "      " + ESCALATION_ELEMENT + "\n"
            + "    </boundaryEvent>\n"
            + "    <userTask id=\"director\" name=\"总审批\" zifang:assignee=\"director\"/>\n"
            + "    <parallelGateway id=\"join\"/>\n"
            + "    <userTask id=\"done\" name=\"归档\" zifang:assignee=\"ops\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"join\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"escBoundary\" targetRef=\"director\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"director\" targetRef=\"join\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"join\" targetRef=\"done\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"done\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + NS_END;

    /** 起点 → 抛升级 overdue → 结束：升级的发布方。 */
    private static final String THROW_BPMN = NS
            + "  <process id=\"escThrow\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <intermediateThrowEvent id=\"th\" name=\"升级\">\n"
            + "      " + ESCALATION_ELEMENT + "\n"
            + "    </intermediateThrowEvent>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"th\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"th\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + NS_END;

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

    // ==================== 边界语义 ====================

    @Test
    @DisplayName("中断型升级：宿主待办作废，token 搬到边界后交给边界出线上的处理人")
    void interruptingEscalationCancelsHostTask() {
        String pid = start(INTERRUPTING_BPMN, "escInt");
        assertEquals(1, openAt(pid, "approve").size(), "先有 boss 一条待办");

        runtime.escalate("overdue", "system", "超时未办");

        assertTrue(openAt(pid, "approve").isEmpty(),
                "升级打断的宿主 —— boss 那条待办必须作废。它还挂着的话，人还以为能继续批，"
                        + "而流程其实已经走到别人那儿了");
        assertEquals(1, openAt(pid, "director").size(),
                "边界分支要建出自己的待办；派给谁取决于这条出线上写的 assignee");
        assertEquals(1, aliveAt(pid).size(),
                "中断型的标志是只有一条活跃 token：原来的被搬到了边界上，没有多出一条。实际 "
                        + activityIdsOf(pid));
        assertTrue(aliveAt(pid).stream().anyMatch(e -> "director".equals(e.getActivityId())),
                "token 现在停在 director 上。实际 token 停在 " + activityIdsOf(pid));
    }

    @Test
    @DisplayName("非中断型升级：宿主待办照旧开着，另起一条并行分支")
    void nonInterruptingEscalationKeepsHostTask() {
        String pid = start(NON_INTERRUPTING_BPMN, "escNi");
        String hostTokenId = tokenIdAt(pid, "approve");
        assertNotNull(hostTokenId, "启动后应当有一条 token 停在 approve 上");

        runtime.escalate("overdue", "system", "提醒升级");

        assertEquals(1, openAt(pid, "approve").size(),
                "非中断的关键就在这一条：boss 的待办必须还在。"
                        + "它被作废的话就是「升级把人打断了」，而流程照样跑得通");
        assertEquals(1, openAt(pid, "director").size(), "边界分支要并行建出自己的待办");
        assertEquals(2, aliveAt(pid).size(),
                "此刻应当是两条活跃 token：宿主那条 + 边界分支那条。实际 " + activityIdsOf(pid));
        assertEquals("approve", activityOf(pid, hostTokenId),
                "宿主 token 一步都不动 —— 它不能被搬到边界事件上，那是中断型的做法");
    }

    @Test
    @DisplayName("非中断型升级：两边都在汇合点等到齐才往下走")
    void joinWaitsForBothPaths() {
        String pid = start(NON_INTERRUPTING_BPMN, "escNi");
        runtime.escalate("overdue", "system", "提醒升级");

        complete(theOne(pid, "director"));
        assertTrue(openAt(pid, "done").isEmpty(),
                "宿主还在办，汇合没过，下一步不该出现。"
                        + "只到了一半的症状是下游多出一条并行路径 —— 那比走错更隐蔽");
        assertTrue(aliveAt(pid).stream().anyMatch(e -> "join".equals(e.getActivityId())),
                "先到的那条应当停在汇合点等着。实际 token 停在 " + activityIdsOf(pid));

        complete(theOne(pid, "approve"));
        assertEquals(1, openAt(pid, "done").size(), "两条路径都到了汇合点，只该往下走一条");
    }

    @Test
    @DisplayName("升级按名字点名所有等待者，不是一个")
    void escalationIsBroadcast() {
        String p1 = start(INTERRUPTING_BPMN, "escInt");
        String p2 = start(INTERRUPTING_BPMN, "escInt");

        List<WfProcessInstance> hit = runtime.escalate("overdue", "system", "批量升级");

        assertEquals(2, hit.size(),
                "升级是广播：两个都在等的单子都该被打断。只升级一个的话，"
                        + "另一个会一直停在那儿直到人自己想起来");
        assertTrue(openAt(p1, "director").size() == 1, "第一个单子进入升级处理");
        assertTrue(openAt(p2, "director").size() == 1, "第二个单子也要进入升级处理");
    }

    @Test
    @DisplayName("升级只触发一次：订阅被消费掉，再投同一条升级码谁都不动")
    void subscriptionIsConsumed() {
        String pid = start(INTERRUPTING_BPMN, "escInt");
        runtime.escalate("overdue", "system", "第一次");
        assertEquals(1, openAt(pid, "director").size());
        int commentsAfterFirst = repo.findComments(pid).size();

        runtime.escalate("overdue", "system", "第二次");

        assertEquals(1, openAt(pid, "director").size(),
                "边界订阅是一次性的：重复触发要写 parallelMultiple，"
                        + "而那本实现明确不支持");
        assertEquals(commentsAfterFirst, repo.findComments(pid).size(),
                "第二次不该留下任何新记录 —— 它没找到订阅者，就什么也没发生。"
                        + "记录反而变多说明第二次又跑了一遍边界");
        assertFalse(commentsOf(pid).contains("第二次"),
                "第一次的记录要留着，它才是这次升级唯一发生过的事实");
    }

    @Test
    @DisplayName("升级码对不上时谁都不动，且不算错误")
    void unmatchedEscalationCodeIsNotAnError() {
        String pid = start(INTERRUPTING_BPMN, "escInt");

        List<WfProcessInstance> hit = runtime.escalate("别的码", "system", "投错了");

        assertTrue(hit.isEmpty(),
                "没人订阅某个码不是错误：升级是「发出一条事实」，有没有人在等"
                        + "不改变它该继续往下走（与抛信号同一条约定）");
        assertEquals(1, openAt(pid, "approve").size(), "不得动到任何一个在等的单子");
    }

    @Test
    @DisplayName("抛出的升级事件按名字叫醒所有订阅者，投递方自己走完")
    void thrownEscalationWakesEverySubscriber() {
        repository.deployXml(INTERRUPTING_BPMN, "escInt");
        String p1 = start(INTERRUPTING_BPMN, "escInt");
        String p2 = start(INTERRUPTING_BPMN, "escInt");

        WfDefinition thrower = repository.deployXml(THROW_BPMN, "escThrow");
        String throwerPid = runtime.startProcessInstance(thrower, "esc-thrower", null, null,
                new HashMap<String, Object>());

        assertTrue(isTerminal(repo.findProcessInstance(throwerPid)),
                "抛事件是穿透的，投完自己继续往下走。实际="
                        + repo.findProcessInstance(throwerPid).getStatus());
        assertTrue(commentsOf(throwerPid).contains("升级 2 个流程实例"),
                "发布方必须知道自己点名了几个人 —— 不知道的话它没法确认这件事办成了。实际: "
                        + commentsOf(throwerPid));
        assertEquals(1, openAt(p1, "director").size(), "第一个订阅者被打断并走升级分支");
        assertEquals(1, openAt(p2, "director").size(), "第二个订阅者也要被打断");
    }

    @Test
    @DisplayName("没人订阅时不报错，但必须留下投递记录")
    void thrownEscalationWithNoSubscriberIsRecorded() {
        WfDefinition thrower = repository.deployXml(THROW_BPMN, "escThrow");
        String pid = runtime.startProcessInstance(thrower, "esc-lonely", null, null,
                new HashMap<String, Object>());

        assertTrue(isTerminal(repo.findProcessInstance(pid)),
                "有没有人听，不改变它该继续往下走");
        assertTrue(commentsOf(pid).contains("升级 0 个流程实例"),
                "「没人听」必须留痕，否则它就是一次无人知晓的静默。实际评论: " + commentsOf(pid));
        assertTrue(commentsOf(pid).contains("overdue"),
                "记录里要点名升级码是什么: " + commentsOf(pid));
    }

    @Test
    @DisplayName("升级码为空必须报错，不能当成广播到全体")
    void blankEscalationCodeIsRejected() {
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.escalate("  ", "system", "空码"));
        assertTrue(ex.getMessage().contains("升级码不能为空"),
                "空码如果被当成「升级所有人」，那是一次无差别的全量打断。实际: "
                        + ex.getMessage());
    }

    // ==================== 订阅载体的形状 ====================

    @Test
    @DisplayName("升级订阅用的是自己的 job 类型，不是信号")
    void escalationSubscriptionHasItsOwnJobType() {
        String pid = start(INTERRUPTING_BPMN, "escInt");

        List<WfJob> escJobs = jobsOf(pid, WfJobType.ESCALATION);
        assertEquals(1, escJobs.size(),
                "升级边界上应当恰好挂一条升级订阅。实际 " + escJobs);
        assertEquals("overdue", escJobs.get(0).getSubscriptionName(),
                "匹配键是升级码本身");
        assertTrue(jobsOf(pid, WfJobType.SIGNAL).isEmpty(),
                "并进信号类型的话，投递方只能靠名字猜该不该打断 —— "
                        + "而猜错的后果是「升级被当成通知」，待办还挂着而作者以为已升级");
        assertTrue(jobsOf(pid, WfJobType.TIMER).isEmpty(),
                "升级订阅不是定时器：它的触发时刻由「谁投了这条升级」决定，"
                        + "而定时器那条路上跑的是「到点即响」的执行器");
    }

    // ==================== 部署期挡住 ====================

    @Test
    @DisplayName("升级捕获事件部署期就报错，并说清缺的是哪一块")
    void escalationCatchIsRejectedAtDeployTime() {
        String xml = NS
                + "  <process id=\"escCatch\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <intermediateCatchEvent id=\"wait\">\n"
                + "      " + ESCALATION_ELEMENT + "\n"
                + "    </intermediateCatchEvent>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"wait\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"wait\" targetRef=\"e\"/>\n"
                + "  </process>\n"
                + NS_END;

        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deployXml(xml, "escCatch"));
        assertTrue(ex.getMessage().contains("暂不支持"),
                "捕获必须明说不支持 —— 硬套现有三种捕获会得到一个看起来能跑的错语义。实际: "
                        + ex.getMessage());
        assertTrue(ex.getMessage().contains("边界事件"),
                "报错要指出能用的那条路：升级的抛事件与边界事件都已支持，"
                        + "作者要表达「超时未办就升级」该挂在哪。实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("升级边界上配定时器必须报错 —— 否则得到一个永不响的哑表")
    void escalationWithTimerIsRejectedAtDeployTime() {
        String xml = NS
                + "  <process id=\"escTimer\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <userTask id=\"approve\" zifang:assignee=\"boss\"/>\n"
                + "    <boundaryEvent id=\"escBoundary\" attachedToRef=\"approve\">\n"
                + "      " + ESCALATION_ELEMENT + "\n"
                + "      <timerEventDefinition><timeDuration>P3D</timeDuration></timerEventDefinition>\n"
                + "    </boundaryEvent>\n"
                + "    <userTask id=\"director\" zifang:assignee=\"director\"/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>\n"
                + "    <sequenceFlow id=\"f3\" sourceRef=\"escBoundary\" targetRef=\"director\"/>\n"
                + "    <sequenceFlow id=\"f4\" sourceRef=\"director\" targetRef=\"e\"/>\n"
                + "  </process>\n"
                + NS_END;

        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deployXml(xml, "escTimer"));
        assertTrue(ex.getMessage().contains("escalationTimer"),
                "要点名缺的是哪一块：作者写的是「超时自动升级」。实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("升级与消息 / 信号互斥，同时配必须报错")
    void escalationConflictsWithMessageAndSignal() {
        assertTrue(conflictReported("<messageEventDefinition messageRef=\"msgX\"/>"),
                "升级与消息同时配必须报错：消息点对点、升级广播，挑一个生效就是猜");
        assertTrue(conflictReported("<signalEventDefinition signalRef=\"sigX\"/>"),
                "升级与信号同时配必须报错：两者长得最像，而后果相反（打断 vs 叫醒）");
    }

    @Test
    @DisplayName("升级没写 escalationRef 部署期就报错，且说的是 escalationRef")
    void escalationWithoutRefIsRejected() {
        String xml = NS
                + "  <process id=\"escNoRef\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <userTask id=\"approve\" zifang:assignee=\"boss\"/>\n"
                + "    <boundaryEvent id=\"escBoundary\" attachedToRef=\"approve\">\n"
                + "      <escalationEventDefinition/>\n"
                + "    </boundaryEvent>\n"
                + "    <userTask id=\"director\" zifang:assignee=\"director\"/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>\n"
                + "    <sequenceFlow id=\"f3\" sourceRef=\"escBoundary\" targetRef=\"director\"/>\n"
                + "    <sequenceFlow id=\"f4\" sourceRef=\"director\" targetRef=\"e\"/>\n"
                + "  </process>\n"
                + NS_END;

        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deployXml(xml, "escNoRef"));
        assertTrue(ex.getMessage().contains("escalationRef"),
                "报错必须点名作者该写的那个属性。把它说成 signalRef 等于指向一个他没写过的属性，"
                        + "而他会去改那个属性 —— 改完还是错的。实际: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("signalRef"),
                "升级不能落到 signalRef 那一支上");
    }

    @Test
    @DisplayName("抛升级事件不能挂边界事件 —— 它不等待任何人")
    void escalationThrowCannotHaveBoundary() {
        String xml = NS
                + "  <process id=\"escThrowBad\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <intermediateThrowEvent id=\"th\">\n"
                + "      " + ESCALATION_ELEMENT + "\n"
                + "    </intermediateThrowEvent>\n"
                + "    <boundaryEvent id=\"b\" attachedToRef=\"th\">\n"
                + "      " + ESCALATION_ELEMENT + "\n"
                + "    </boundaryEvent>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"th\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"th\" targetRef=\"e\"/>\n"
                + "    <sequenceFlow id=\"f3\" sourceRef=\"b\" targetRef=\"e\"/>\n"
                + "  </process>\n"
                + NS_END;

        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deployXml(xml, "escThrowBad"));
        assertTrue(ex.getMessage().contains("穿透"),
                "要说明为什么不能挂：抛事件不停留，边界只会得到一个永不触发的哑订阅。实际: "
                        + ex.getMessage());
    }

    @Test
    @DisplayName("升级算事件边界，也算升级事件 —— 两处判别式缺一个，订阅就建不起来")
    void escalationIsRecognisedAsBothBoundaryAndEvent() {
        WfDefinition definition = new WfXmlParser().parse(INTERRUPTING_BPMN);
        WfNode boundary = definition.node("escBoundary");

        assertTrue(boundary.isEscalationEvent(), "它用的是 escalationEventDefinition");
        assertTrue(boundary.isEventBoundary(),
                "不算事件边界的话 WfContext#startTimerJobs 根本不会给它建订阅 —— "
                        + "而症状是「escalate 成功、零个流程实例被动」，看起来像订阅写错了");
        // 独立字段的判据：复用 signalName 的话这两条同时为真，
        // 于是"收到升级的那条"与"收到信号的那条"在引擎眼里完全一样
        assertFalse(boundary.isSignalBoundary(), "升级不能同时被当成信号");
        assertFalse(boundary.isMessageBoundary(), "升级不能同时被当成消息");
        assertFalse(boundary.isTimerBoundary(),
                "纯升级边界不该被当成定时器边界 —— 它没有触发时刻，"
                        + "而运行期也确实不建定时器 job（配了 timer 会部署期报错）");

        WfNode thrown = new WfXmlParser().parse(THROW_BPMN).node("th");
        assertTrue(thrown.isEscalationEvent(), "抛事件那侧的判别式也要认升级");
        assertFalse(thrown.isSignalBoundary(), "抛升级事件不能被读成抛信号");
    }

    @Test
    @DisplayName("升级码经 codec 存进定义再读回来（内存实现天然保真，codec 不然）")
    void escalationCodeSurvivesCodecRoundTrip() {
        // **必须走 codec 而不是只 parse 一次**：本类的其余用例都跑在内存实现上，
        // 而内存实现走 Java 序列化、保留全部字段；codec 是手写 DTO 的逐字段拷贝，
        // 漏一个字段编译器不会提醒。先前那版 codec 往返的断言是自造的（用了不存在的
        // WfXmlParser#toXml），换掉之后如果只断解析结果，这条判据在 codec 漏字段时
        // 仍然是绿的 —— 变异验证里 E17/E18 两条 codec 变异正是这样打绿的。
        WfDefinition original = new WfXmlParser().parse(INTERRUPTING_BPMN);

        WfDefinition back = WfDefinitionCodec.decode(WfDefinitionCodec.encode(original));

        assertEquals("overdue", back.node("escBoundary").getEscalationCode(),
                "升级码丢了的话，重新部署出来的流程会挂上一条没有匹配键的哑订阅 —— "
                        + "而它永远不会被任何 escalate 叫醒，且没有任何一处报错");
        assertTrue(back.node("escBoundary").isEscalationEvent(),
                "读回来之后它还得被认成升级边界，否则存进去的是一份读不出来的定义");
    }

    // ==================== 夹具 ====================

    private String start(String xml, String key) {
        WfDefinition definition = repository.deployXml(xml, key);
        return runtime.startProcessInstance(definition, "ESC-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
    }

    /**
     * 在升级边界上再挂一个事件定义，看部署期是否报"只能挂一种触发条件"。
     *
     * <p>整个元素串当参数传进来，而不是"元素名 + ref"两个 —— 拆成两个的那版
     * 在调用处把 {@code "<messageEventDefinition"} 和 {@code "msgX"} 传反了位，
     * 拼出 {@code <messageEventDefinition msgX="x"/>} 这种非法标签 ——
     * 解析阶段就炸，判据于是返回 false，而它失败的原因跟它要测的东西毫无关系。
     */
private boolean conflictReported(String extraElement) {
        String xml = INTERRUPTING_BPMN.replace(ESCALATION_ELEMENT,
                ESCALATION_ELEMENT + "\n      " + extraElement);
        assertTrue(xml.contains(ESCALATION_ELEMENT + "\n      " + extraElement),
                "夹具没拼进去：目标串在源 XML 里必须恰好出现一次。实际拼出 " + xml);
        try {
            repository.deployXml(xml, "conf-" + extraElement.hashCode());
            return false;
        } catch (WfDefinitionException ex) {
            return ex.getMessage().contains("只能挂一种触发条件");
        }
    }

    private List<WfTask> openAt(String pid, String nodeId) {
        List<WfTask> result = new ArrayList<>();
        for (WfTask t : repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(20))) {
            if (t.isOpen() && nodeId.equals(t.getDefinitionId())) {
                result.add(t);
            }
        }
        return result;
    }

    private WfTask theOne(String pid, String nodeId) {
        List<WfTask> tasks = openAt(pid, nodeId);
        assertEquals(1, tasks.size(), nodeId + " 上应当恰好一条待办。实际 " + tasks);
        return tasks.get(0);
    }

    /** 类型必须显式给：{@code WfJobQuery} 不给类型时会落到默认口径（只捞定时器）。 */
    private List<WfJob> jobsOf(String pid, WfJobType type) {
        return repo.queryJobs(new WfJobQuery().setType(type)
                .setProcessInstanceId(pid).setPageNum(1).setPageSize(20));
    }

    private void complete(WfTask task) {
        runtime.completeTask(task.getId(), task.getAssignee(), "办完", new HashMap<>());
    }

    private String tokenIdAt(String pid, String nodeId) {
        for (WfExecution e : repo.findExecutionsByProcessInstance(pid)) {
            if (nodeId.equals(e.getActivityId())) {
                return e.getId();
            }
        }
        return null;
    }

    private String activityOf(String pid, String tokenId) {
        for (WfExecution e : repo.findExecutionsByProcessInstance(pid)) {
            if (tokenId.equals(e.getId())) {
                return e.getActivityId();
            }
        }
        return null;
    }

    private List<WfExecution> aliveAt(String pid) {
        List<WfExecution> result = new ArrayList<>();
        for (WfExecution e : repo.findExecutionsByProcessInstance(pid)) {
            if (!e.isEnded()) {
                result.add(e);
            }
        }
        return result;
    }

    private List<String> activityIdsOf(String pid) {
        List<String> ids = new ArrayList<>();
        for (WfExecution e : repo.findExecutionsByProcessInstance(pid)) {
            ids.add(e.getActivityId() + (e.isEnded() ? "(ended)" : ""));
        }
        java.util.Collections.sort(ids);
        return ids;
    }

    private static boolean isTerminal(WfProcessInstance instance) {
        return instance != null && instance.getStatus() != null
                && instance.getStatus().isTerminal();
    }

    private String commentsOf(String pid) {
        StringBuilder text = new StringBuilder();
        for (WfComment comment : repo.findComments(pid)) {
            text.append(comment.getContent()).append(" | ");
        }
        return text.toString();
    }
}