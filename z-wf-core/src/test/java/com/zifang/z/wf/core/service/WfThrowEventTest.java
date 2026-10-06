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
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.engine.behavior.WfThrowEventBehavior;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 中间抛出事件 {@code intermediateThrowEvent} —— 流程自己把一条事件发出去。
 *
 * <p>本类盯三件错了都不报错的事：
 * <ol>
 *   <li><b>投递必须发生在落库之后。</b>behavior 处在单实例事务内部，
 *       当场投出去的话，被唤醒的那条读到的是尚未落库的旧状态，
 *       内层推进完又被外层回写覆盖 —— 现象是「事件到了但流程没动」，且无异常。</li>
 *   <li><b>「没人订阅」不是错误，但必须留痕。</b>抛事件是发布式动作，
 *       有没有人听不改变它该继续往下走；但留痕是"不静默"的兑现方式。</li>
 *   <li><b>多条候选必须报错。</b>消息是点对点，投递方必须知道被谁接了；
 *       三个候选时它不知道，静默挑一个等于把消息投给了错误的单。</li>
 * </ol>
 */
class WfThrowEventTest {

    /** 起点 → 抛信号 → 结束。 */
    private static final String THROW_SIGNAL_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"throwSignal\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <intermediateThrowEvent id=\"th\" name=\"广播通过\">\n"
            + "      <signalEventDefinition signalRef=\"approved\"/>\n"
            + "    </intermediateThrowEvent>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"th\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"th\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 起点 → 抛消息 → 结束。 */
    private static final String THROW_MESSAGE_BPMN = THROW_SIGNAL_BPMN
            .replace("id=\"throwSignal\"", "id=\"throwMessage\"")
            .replace("<signalEventDefinition signalRef=\"approved\"/>",
                    "<messageEventDefinition messageRef=\"approvedMsg\"/>");

    /** 起点 → 等信号 approved → 结束：被抛事件唤醒的那条。 */
    private static final String WAIT_SIGNAL_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"waitSignal\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <intermediateCatchEvent id=\"wait\" name=\"等广播\">\n"
            + "      <signalEventDefinition signalRef=\"approved\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"wait\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"wait\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 等消息 approvedMsg 的接收任务。 */
    private static final String WAIT_MESSAGE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"waitMessage\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <receiveTask id=\"wait\" name=\"等消息\" zifang:messageName=\"approvedMsg\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"wait\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"wait\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 没有事件定义的抛事件。 */
    private static final String THROW_BARE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"throwBare\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <intermediateThrowEvent id=\"th\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"th\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"th\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 同时配了信号与消息。 */
    private static final String THROW_BOTH_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"throwBoth\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <intermediateThrowEvent id=\"th\">\n"
            + "      <signalEventDefinition signalRef=\"sigX\"/>\n"
            + "      <messageEventDefinition messageRef=\"msgX\"/>\n"
            + "    </intermediateThrowEvent>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"th\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"th\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 抛事件上挂一个定时器边界。 */
    private static final String THROW_WITH_BOUNDARY_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"throwBoundary\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <intermediateThrowEvent id=\"th\">\n"
            + "      <signalEventDefinition signalRef=\"sigX\"/>\n"
            + "    </intermediateThrowEvent>\n"
            + "    <boundaryEvent id=\"bnd\" attachedToRef=\"th\">\n"
            + "      <timerEventDefinition><timeDuration>PT5M</timeDuration></timerEventDefinition>\n"
            + "    </boundaryEvent>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"th\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"th\" targetRef=\"e\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"bnd\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 两条并行分支：一条等信号，一条抛信号 —— 自唤醒。 */
    private static final String SELF_WAKE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"selfWake\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <parallelGateway id=\"pg\"/>\n"
            + "    <intermediateCatchEvent id=\"wait\" name=\"等广播\">\n"
            + "      <signalEventDefinition signalRef=\"ping\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <userTask id=\"fire\" name=\"手工触发\" zifang:assignee=\"op\"/>\n"
            + "    <intermediateThrowEvent id=\"th\" name=\"回声\">\n"
            + "      <signalEventDefinition signalRef=\"ping\"/>\n"
            + "    </intermediateThrowEvent>\n"
            + "    <parallelGateway id=\"jg\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"pg\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"pg\" targetRef=\"wait\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"pg\" targetRef=\"fire\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"fire\" targetRef=\"th\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"wait\" targetRef=\"jg\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"th\" targetRef=\"jg\"/>\n"
            + "    <sequenceFlow id=\"f7\" sourceRef=\"jg\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 等 approved 之后**自己再抛** ping：验证链式投递不会在第二层被丢掉。 */
    private static final String CHAIN_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"chain\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <intermediateCatchEvent id=\"wait\" name=\"等广播\">\n"
            + "      <signalEventDefinition signalRef=\"approved\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <intermediateThrowEvent id=\"th\" name=\"转发\">\n"
            + "      <signalEventDefinition signalRef=\"ping\"/>\n"
            + "    </intermediateThrowEvent>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"wait\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"wait\" targetRef=\"th\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"th\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 两个流程互抛对方的信号：不设上限就会永远互相唤醒。 */
    private static final String PING_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"ping\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <intermediateCatchEvent id=\"wait\" name=\"等来\">\n"
            + "      <signalEventDefinition signalRef=\"sigA\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <intermediateThrowEvent id=\"th\" name=\"回抛\">\n"
            + "      <signalEventDefinition signalRef=\"sigB\"/>\n"
            + "    </intermediateThrowEvent>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"wait\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"wait\" targetRef=\"th\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"th\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /**
     * PING 的镜像：等 sigB、抛回 sigA。
     *
     * <p><b>显式写出来而不是从 PING 链式 replace。</b>原先那版链式 replace 有两处
     * 同时匹配：第二个 replace 把 catch 与 throw 里的 {@code sigA} 一起换掉，
     * 第三个又因为实际文本里 {@code sigB} 后面跟的是 {@code "/&gt;} 而不是换行而没匹配上 ——
     * 结果 PONG 变成「等 sigB、抛 sigB」，自环，**看起来照样收敛**，
     * 而它已经不再是对面那个流程了。
     * 链式 replace 的失败模式是"静默产出一份意思相近但不对的夹具"。
     */
    private static final String PONG_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"pong\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <intermediateCatchEvent id=\"wait\" name=\"等来\">\n"
            + "      <signalEventDefinition signalRef=\"sigB\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <intermediateThrowEvent id=\"th\" name=\"回抛\">\n"
            + "      <signalEventDefinition signalRef=\"sigA\"/>\n"
            + "    </intermediateThrowEvent>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"wait\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"wait\" targetRef=\"th\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"th\" targetRef=\"e\"/>\n"
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

    // ==================== 广播 ====================

    @Test
    @DisplayName("抛出的信号唤醒全部等待者，且抛事件自己不等待")
    void thrownSignalWakesAllSubscribers() {
        repository.deploy(new WfXmlParser().parse(WAIT_SIGNAL_BPMN));
        String w1 = start("waitSignal", "sub-1");
        String w2 = start("waitSignal", "sub-2");

        WfDefinition thrower = repository.deploy(new WfXmlParser().parse(THROW_SIGNAL_BPMN));
        String throwerPid = runtime.startProcessInstance(thrower, "thrower-1", null, null,
                new HashMap<String, Object>());

        assertTrue(isTerminal(statusOf(throwerPid)),
                "抛事件是穿透的，抛完自己继续走到结束。实际=" + statusOf(throwerPid).getStatus());
        assertTrue(isTerminal(statusOf(w1)), "等信号的子流程应当被唤醒并跑完");
        assertTrue(isTerminal(statusOf(w2)), "广播要唤醒**全部**等待者");
    }

    @Test
    @DisplayName("没人订阅不算错，但必须留下投递记录")
    void noSubscriberIsNotAnErrorButIsRecorded() {
        WfDefinition thrower = repository.deploy(new WfXmlParser().parse(THROW_SIGNAL_BPMN));
        String lonelyPid = runtime.startProcessInstance(thrower, "lonely", null, null,
                new HashMap<String, Object>());

        assertTrue(isTerminal(statusOf(lonelyPid)),
                "有没有人听，不改变它该继续往下走 —— 抛事件是发布式动作，不是查找式调用");

        String comment = commentsOf(lonelyPid);
        assertTrue(comment.contains("唤醒 0 个订阅者"),
                "「没人听」必须留痕，否则它就是一次无人知晓的静默。实际评论: " + comment);
        assertTrue(comment.contains("approved"), "记录里要点名投的是什么事件: " + comment);
    }

    // ==================== 点对点 ====================

    @Test
    @DisplayName("抛出的消息只被一个接收任务接走")
    void thrownMessageHitsExactlyOneReceiver() {
        repository.deploy(new WfXmlParser().parse(WAIT_MESSAGE_BPMN));
        String waiterPid = start("waitMessage", "msg-1");

        WfDefinition thrower = repository.deploy(new WfXmlParser().parse(THROW_MESSAGE_BPMN));
        String throwerPid = runtime.startProcessInstance(thrower, "msg-thrower", null, null,
                new HashMap<String, Object>());

        assertTrue(isTerminal(statusOf(waiterPid)),
                "抛出的消息应当被那条接收任务接走并让它跑完");
        assertTrue(isTerminal(statusOf(throwerPid)), "投递方自己也要走完");
        assertTrue(commentsOf(throwerPid).contains("已被 " + waiterPid + " 接走"),
                "投递方必须知道消息被谁接了 —— 不知道的话它没法确认这件事办成了。实际: "
                        + commentsOf(throwerPid));
    }

    @Test
    @DisplayName("消息有两个候选时必须报错，不静默挑一个")
    void ambiguousMessageThrowFailsLoudly() {
        WfDefinition waiter = repository.deploy(new WfXmlParser().parse(WAIT_MESSAGE_BPMN));
        start("waitMessage", "amb-1");
        start("waitMessage", "amb-2");

        WfDefinition thrower = repository.deploy(new WfXmlParser().parse(THROW_MESSAGE_BPMN));
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.startProcessInstance(thrower, "amb-thrower", null, null,
                        new HashMap<String, Object>()));
        assertTrue(ex.getMessage().contains("匹配到 2 个"),
                "投递方必须知道被谁接了，而三个候选时它不知道。实际: " + ex.getMessage());
        assertEquals(2, repo.queryTasks(new WfTaskQuery().setOpenOnly(true)
                .setPageNum(1).setPageSize(20)).size(),
                "歧义时不得推进任何一个接收任务");
        assertNotNull(waiter);
    }

    // ==================== 自唤醒 ====================

    @Test
    @DisplayName("流程给自己发信号能唤醒自己的另一条分支，且返回的实例状态是最新的")
    void processCanWakeItsOwnBranch() {
        repository.deploy(new WfXmlParser().parse(SELF_WAKE_BPMN));
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(SELF_WAKE_BPMN));
        String pid = start("selfWake", "self-1");

        WfTask fire = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(10)).get(0);
        assertEquals("fire", fire.getDefinitionId(), "前置条件：等的是手工触发那一步");

        WfProcessInstance after = runtime.completeTask(fire.getId(), "op", "触发",
                new HashMap<String, Object>());
        assertNotNull(after);

        assertTrue(isTerminal(statusOf(pid)),
                "抛出的 ping 应当唤醒本实例里等信号的那条分支，两条分支随后汇合结束");
        // 自唤醒时上下文里那份实例是**投递前**的快照。
        // 不刷新的话，调用方拿到的 status 会是投递前的值 —— 看起来"办完了但单子还在跑"
        assertEquals(WfProcessStatus.COMPLETED, after.getStatus(),
                "返回给调用方的实例必须是投递后的状态，不能是投递前的快照");
        assertNotNull(after.getEndTime(), "刷新过的实例应当带结束时间");
        assertNotNull(definition);
    }

    // ==================== 链式与上限 ====================

    @Test
    @DisplayName("被唤醒的流程在下游再抛一次，那一条也会被投出去")
    void chainedThrowIsAlsoDelivered() {
        // 关键在数据：被唤醒的那条**下游还有一次抛事件**。
        // 只发一批的话，第二层的 ping 就没人接 —— 而外层看起来完全成功。
        repository.deploy(new WfXmlParser().parse(CHAIN_BPMN));
        repository.deploy(new WfXmlParser().parse(SELF_WAKE_BPMN));
        String chainPid = start("chain", "chain-1");
        // 自己那条实例的 fire 分支会抛 ping：先把它准备好
        WfDefinition selfWake = repository.getLatestDefinition("selfWake");
        String selfPid = runtime.startProcessInstance(selfWake, "chain-self", null, null,
                new HashMap<String, Object>());

        WfDefinition thrower = repository.deploy(new WfXmlParser().parse(THROW_SIGNAL_BPMN));
        runtime.startProcessInstance(thrower, "chain-thrower", null, null,
                new HashMap<String, Object>());

        // 第一层：approved 唤醒 chain 那条，它走到 th 又抛出 ping
        assertTrue(isTerminal(statusOf(chainPid)),
                "被唤醒的流程应当一路跑到结束");
        // 第二层：ping 应当被 selfWake 那条接走（它的 wait 分支正等 ping）
        assertTrue(commentsOf(selfPid).contains("投递信号 ping"),
                "链式的第二层也必须投出去 —— 少发一批的症状是外层全部成功、"
                        + "而下游什么都没发生。实际评论: " + commentsOf(selfPid));
        assertTrue(commentsOf(chainPid).contains("ping"),
                "转发那条自己也要留下投递记录");
    }

    @Test
    @DisplayName("两个流程互抛事件会自然收敛 —— 每个订阅都是一次性的")
    void mutualThrowConverges() {
        // 这条**不是**测"上限挡住了"，而是测"根本用不上上限"。
        //
        // 起因是一个够不着的防御：投递队列里写了 MAX_PENDING_EVENT_DRAIN 上限，
        // 理由是"两个流程互相抛事件会无限触发"。写完才想起来——
        // 本引擎**没有常驻订阅**：消息 / 信号 / 事件网关分支的订阅在事件到达时就被消费掉，
        // 消息边界上的订阅在 token 离开宿主节点时也被撤掉。
        // 于是 ping 走过 catch 事件的那一刻，sigA 就已经没人等了，
        // pong 再抛回来时命中 0 个订阅，链路到此为止。
        //
        // ⇒ 上限目前**够不到**。这里断言的是它够不到的那个原因：
        // 互抛会在有限步内收敛，两条流程都跑完。
        // 上限本身作为安全阀保留（将来若引入常驻订阅就会用上），
        // 但它**没有判据**，也不假装自己有。
        repository.deploy(new WfXmlParser().parse(PING_BPMN));
        repository.deploy(new WfXmlParser().parse(PONG_BPMN));
        String pingPid = start("ping", "ping-1");
        String pongPid = start("pong", "pong-1");

        runtime.broadcastSignal("sigA", "op", null, "起头");

        assertTrue(isTerminal(statusOf(pingPid)),
                "ping 应当走到结束而不是无限往返。实际: " + statusOf(pingPid).getStatus());
        assertTrue(isTerminal(statusOf(pongPid)),
                "pong 也应当走到结束。实际: " + statusOf(pongPid).getStatus());
        // 最后一轮落在 pong 上：它抛回 sigA 时，ping 早已走过 catch 事件，没人再等
        assertTrue(commentsOf(pongPid).contains("唤醒 0 个订阅者"),
                "最后一轮应当落在「没人订阅」上 —— 这正是它收敛的直接证据。实际: "
                        + commentsOf(pongPid));
        assertTrue(commentsOf(pingPid).contains("唤醒 1 个订阅者"),
                "第一轮 ping 应当被 pong 接住。实际: " + commentsOf(pingPid));
    }

    // ==================== 运行期闸门（部署期已挡，这里再挡一次） ====================

    @Test
    @DisplayName("绕过校验直接跑行为：没事件引用 / 同时配两个，都必须报错")
    void behaviorGuardsSurviveBypassingTheValidator() {
        // 部署期已经挡住这两种，可运行期那道闸门不能省 ——
        // 它挡的是「定义从别处进来、或校验规则以后放宽」的情况。
        // 判据只能直接调 behavior，因为走 deploy 的路径根本到不了这两个分支。
        // **两个分支都是"正常路径下不可达"的防御**，所以这里必须直呼，不能靠端到端。
        WfThrowEventBehavior behavior = new WfThrowEventBehavior();
        WfNode bare = new WfNode("th", "抛事件", WfNodeType.THROW_EVENT);
        WfNode both = new WfNode("th", "抛事件", WfNodeType.THROW_EVENT);
        both.setSignalName("sigX");
        both.setMessageName("msgX");

        WfEngineException noRef = assertThrows(WfEngineException.class,
                () -> behavior.execute(contextOf(), bare, token()));
        assertTrue(noRef.getMessage().contains("没有配 signalRef 或 messageRef"),
                "静默放过会让这一步看起来执行成功了，而它其实什么也没发。实际: "
                        + noRef.getMessage());

        WfEngineException conflict = assertThrows(WfEngineException.class,
                () -> behavior.execute(contextOf(), both, token()));
        assertTrue(conflict.getMessage().contains("不能挑一个生效"),
                "挑一个生效会让作者以为自己写的那条生效了。实际: " + conflict.getMessage());
    }

    // ==================== 部署期挡住 ====================

    @Test
    @DisplayName("没有事件定义 / 同时配两个事件 / 挂边界事件，部署期就报错")
    void badThrowEventsRejectedAtDeployTime() {
        WfDefinitionException bare = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(new WfXmlParser().parse(THROW_BARE_BPMN)));
        assertTrue(bare.getMessage().contains("没有任何事件定义"),
                "部署期就要说清它抛不出东西 —— 跑起来才炸的话，作者第一反应是怀疑引擎。实际: "
                        + bare.getMessage());

        WfDefinitionException both = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(new WfXmlParser().parse(THROW_BOTH_BPMN)));
        assertTrue(both.getMessage().contains("signalRef"),
                "同时配两个要点名其中一个，且说清语义不同: " + both.getMessage());

        WfDefinitionException boundary = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(new WfXmlParser().parse(THROW_WITH_BOUNDARY_BPMN)));
        assertTrue(boundary.getMessage().contains("穿透"),
                "要说明为什么不能挂：抛事件不停留，边界只会得到一个永不触发的哑订阅。实际: "
                        + boundary.getMessage());
    }

    @Test
    @DisplayName("抛事件是本引擎的一等节点类型，不再是退化节点")
    void throwEventIsNative() {
        WfDefinition definition = new WfXmlParser().parse(THROW_SIGNAL_BPMN);
        WfNodeType type = definition.node("th").getType();
        assertEquals(WfNodeType.THROW_EVENT, type,
                "它此前是「不支持的元素」，部署期报 ERROR —— "
                        + "也就是说一份真实的 Camunda 流程里出现 throwEvent 时本引擎部署不了");
        assertEquals("approved", definition.node("th").getSignalName());
        assertTrue(WfNodeType.isNative("intermediateThrowEvent"));
    }

    // ==================== 夹具 ====================

    private String start(String key, String businessKey) {
        WfDefinition definition = repository.getLatestDefinition(key);
        return runtime.startProcessInstance(definition, businessKey, null, null,
                new HashMap<String, Object>());
    }

    private com.zifang.z.wf.core.engine.WfContext contextOf() {
        WfProcessInstance instance = new WfProcessInstance("ctx-pid", "k", "k:1");
        return new com.zifang.z.wf.core.engine.WfContext(
                new WfDefinition("k", "k"), instance, token());
    }

    private static WfExecution token() {
        return new WfExecution("ctx-token", "ctx-pid", "th");
    }

    private static boolean isTerminal(WfProcessInstance instance) {
        return instance.getStatus() != null && instance.getStatus().isTerminal();
    }

    private WfProcessInstance statusOf(String pid) {
        WfProcessInstance instance = repo.findProcessInstance(pid);
        assertNotNull(instance, "流程实例不存在: " + pid);
        return instance;
    }

    private boolean terminalOf(String businessKey) {
        for (WfProcessInstance instance : repo.queryProcessInstances(
                new com.zifang.z.wf.core.persistence.WfProcessInstanceQuery()
                        .setBusinessKey(businessKey).setPageNum(1).setPageSize(10))) {
            if (instance.getStatus() != null && instance.getStatus().isTerminal()) {
                return true;
            }
        }
        return false;
    }

    private String commentsOf(String pid) {
        StringBuilder text = new StringBuilder();
        List<WfComment> comments = repo.findComments(pid);
        for (WfComment comment : comments) {
            text.append(comment.getContent()).append(" | ");
        }
        return text.toString();
    }
}