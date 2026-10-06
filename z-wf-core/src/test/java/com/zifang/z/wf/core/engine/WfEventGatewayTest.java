package com.zifang.z.wf.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfDefinitionValidator;
import com.zifang.z.wf.core.definition.WfFlow;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.definition.WfValidationIssue;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfSubscriptionQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.view.WfSubscriptionView;
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.service.WfJobService;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfSubscriptionService;
import com.zifang.z.wf.core.service.WfRuntimeService;

/**
 * 事件网关（eventBasedGateway）—— 分叉出去，谁先来听谁的。
 *
 * <p>本类盯六件错了都不报错的事：
 * <ol>
 *   <li><b>分叉不等于各走各的</b>：三条出线全被激活是分叉那一刻的事，
 *       但只要有一个事件到达，其余分支必须连同各自的订阅一起作废 ——
 *       不作废的话流程会同时跑完三条分支并在汇合点碰头，那是"全都走"不是"三选一"。</li>
 *   <li><b>不能产生人工待办</b>：中间捕获事件等的是消息/信号，不是某个用户。
 *       退化成待办的话事件网关就变成"让 N 个人同时点"。</li>
 *   <li><b>只唤醒被命中的那一条</b>：靠 job 上的 executionId 精确定位，
 *       而不是"某个网关上的某一条"。</li>
 *   <li><b>被唤醒的分支只前进，不重进</b>：用 resumeLeave 而非 startFrom，
 *       否则每触发一次就多挂一条孤儿订阅，竞速永远进行不完。</li>
 *   <li><b>边界订阅与网关分支不能共用类型</b>：前者是打断，后者是竞速，
 *       混用时触发路径必须去猜，而猜错的后果是流程静默走错分支。</li>
 *   <li><b>五种写法在部署期挡住</b>：出线不足两条、出线不指捕获事件、
 *       捕获事件无事件定义、用了定时器捕获、捕获事件入线不唯一。</li>
 * </ol>
 */
class WfEventGatewayTest {

    /** 等主管批 / 等 ERP 回执，谁先来听谁的。 */
    private static final String RACE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"raceProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <eventBasedGateway id=\"eg\"/>\n"
            + "    <intermediateCatchEvent id=\"waitMsg\" name=\"等主管批\">\n"
            + "      <messageEventDefinition messageRef=\"bossApprove\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <intermediateCatchEvent id=\"waitSignal\" name=\"等回执\">\n"
            + "      <signalEventDefinition signalRef=\"erpDone\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <userTask id=\"onApprove\" name=\"批了\" zifang:assignee=\"ops\"/>\n"
            + "    <userTask id=\"onErp\" name=\"回执到了\" zifang:assignee=\"ops\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <endEvent id=\"e2\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"eg\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"eg\" targetRef=\"waitMsg\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"eg\" targetRef=\"waitSignal\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"waitMsg\" targetRef=\"onApprove\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"waitSignal\" targetRef=\"onErp\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"onApprove\" targetRef=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f7\" sourceRef=\"onErp\" targetRef=\"e2\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 事件网关的三条出线都不是捕获事件：分支会在分叉当场直接跑完。 */
    private static final String NOT_CATCH_BPMN = RACE_BPMN
            .replace("<intermediateCatchEvent id=\"waitMsg\" name=\"等主管批\">\n"
                    + "      <messageEventDefinition messageRef=\"bossApprove\"/>\n"
                    + "    </intermediateCatchEvent>",
                    "<userTask id=\"waitMsg\" name=\"等主管批\" zifang:assignee=\"boss\"/>")
            .replace("id=\"raceProcess\"", "id=\"notCatchProcess\"");

    /** 事件网关只有一条出线：不存在竞速。 */
    private static final String SINGLE_OUT_BPMN = RACE_BPMN
            .replace("    <sequenceFlow id=\"f3\" sourceRef=\"eg\" targetRef=\"waitSignal\"/>\n", "")
            .replace("    <sequenceFlow id=\"f5\" sourceRef=\"waitSignal\" targetRef=\"onErp\"/>\n", "")
            .replace("    <userTask id=\"onErp\" name=\"回执到了\" zifang:assignee=\"ops\"/>\n", "")
            .replace("    <endEvent id=\"e2\"/>\n", "")
            .replace("    <sequenceFlow id=\"f7\" sourceRef=\"onErp\" targetRef=\"e2\"/>\n", "")
            .replace("<intermediateCatchEvent id=\"waitSignal\" name=\"等回执\">\n"
                            + "      <signalEventDefinition signalRef=\"erpDone\"/>\n"
                            + "    </intermediateCatchEvent>\n", "")
            .replace("id=\"raceProcess\"", "id=\"singleOutProcess\"");

    /**
     * 事件网关下的<b>定时器分支</b>：等主管批 / 等 5 分钟，谁先来听谁的。
     *
     * <p>这曾经是被拒的写法（"扫描器那条续跑路径认的是宿主节点，捕获事件没有宿主"），
     * 现在支持了 —— 补上的正是那条续跑路径里的分派。
     */
    private static final String TIMER_CATCH_BPMN = RACE_BPMN
            .replace("<messageEventDefinition messageRef=\"bossApprove\"/>",
                    "<timerEventDefinition><timeDuration>PT5M</timeDuration></timerEventDefinition>")
            .replace("id=\"raceProcess\"", "id=\"timerCatchProcess\"");

    /**
     * 三选一：等主管批（消息）/ 等 5 分钟（定时器）/ 等回执（信号）。
     *
     * <p>这才是定时器分支真正要面对的场景 —— 竞速里多了一个"时间也会来抢"的因素。
     * 只测「定时器 vs 消息」两条分支的话，"输掉的分支的 job 有没有被清掉"这类
     * 问题在三条分支上才暴露得出来（中间那条 token 的 job 归属最容易搞错）。
     */
    private static final String TIMER_RACE_BPMN = RACE_BPMN
            .replace("    <sequenceFlow id=\"f3\" sourceRef=\"eg\" targetRef=\"waitSignal\"/>\n",
                    "    <sequenceFlow id=\"f3\" sourceRef=\"eg\" targetRef=\"waitTimer\"/>\n"
                            + "    <sequenceFlow id=\"f8\" sourceRef=\"eg\" targetRef=\"waitSignal\"/>\n")
            .replace("    <intermediateCatchEvent id=\"waitSignal\" name=\"等回执\">\n",
                    "    <intermediateCatchEvent id=\"waitTimer\" name=\"等 5 分钟\">\n"
                            + "      <timerEventDefinition><timeDuration>PT5M</timeDuration>"
                            + "</timerEventDefinition>\n"
                            + "    </intermediateCatchEvent>\n"
                            + "    <intermediateCatchEvent id=\"waitSignal\" name=\"等回执\">\n")
            .replace("id=\"raceProcess\"", "id=\"timerRaceProcess\"");

    /**
     * <b>孤立的</b>定时器捕获事件：流程里直接写一个"等 5 分钟再继续"的节点，
     * 不经事件网关。它仍然被部署期拒绝 —— 理由见 {@link #standaloneTimerCatchIsRejected}。
     */
    private static final String STANDALONE_TIMER_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"standaloneTimerProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <intermediateCatchEvent id=\"justWait\" name=\"干等 5 分钟\">\n"
            + "      <timerEventDefinition><timeDuration>PT5M</timeDuration></timerEventDefinition>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <userTask id=\"afterWait\" name=\"等完了\" zifang:assignee=\"ops\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"justWait\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"justWait\" targetRef=\"afterWait\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"afterWait\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 捕获事件没有任何事件定义：一条永远等不到的死路。 */
    private static final String NO_DEF_BPMN = RACE_BPMN
            .replace("<messageEventDefinition messageRef=\"bossApprove\"/>\n", "")
            .replace("id=\"raceProcess\"", "id=\"noDefProcess\"");

    /** 捕获事件有两条入线：运行时靠唯一入线反查网关，这里被破坏了就查不到。 */
    private static final String TWO_IN_BPMN = RACE_BPMN
            .replace("    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"eg\"/>\n",
                    "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"eg\"/>\n"
                            + "    <sequenceFlow id=\"f0\" sourceRef=\"s1\" targetRef=\"waitMsg\"/>\n")
            .replace("id=\"raceProcess\"", "id=\"twoInProcess\"");

    /** 不经网关的普通中间捕获事件：等消息继续，不与任何分支互斥。 */
    private static final String PLAIN_CATCH_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"plainCatchProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <intermediateCatchEvent id=\"waitAny\" name=\"等外部回执\">\n"
            + "      <messageEventDefinition messageRef=\"anyNews\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <userTask id=\"after\" name=\"收到之后\" zifang:assignee=\"ops\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"waitAny\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"waitAny\" targetRef=\"after\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"after\" targetRef=\"e1\"/>\n"
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
        runtime = new WfRuntimeService(repository, repo, new WfEngine(),
                new WfHookDispatcher());
    }

    private String startRace() {
        return startRace(RACE_BPMN);
    }

    private String startRace(String xml) {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(xml));
        return runtime.startProcessInstance(definition, "BIZ-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
    }

    private List<WfJob> catchJobsOf(String pid) {
        List<WfJob> found = new ArrayList<>();
        for (WfJob job : repo.queryJobs(new WfJobQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(50))) {
            if (job.getType() == WfJobType.EVENT_MESSAGE
                    || job.getType() == WfJobType.EVENT_SIGNAL) {
                found.add(job);
            }
        }
        return found;
    }

    // ==================== 定时器分支 ====================

    /**
     * 该实例上还挂着的定时器 job。
     *
     * <p>分派成两种筛选器：{@code elementId == 捕获事件 id} 的是网关分支的，
     * {@code attachedToRef != null} 的是边界事件的。前者是本轮新加的形态。
     */
    private List<WfJob> timerJobsOf(String pid) {
        List<WfJob> found = new ArrayList<>();
        for (WfJob job : repo.queryJobs(new WfJobQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(50))) {
            if (job.getType() == WfJobType.TIMER || job.getType() == WfJobType.EVENT_TIMER) {
                found.add(job);
            }
        }
        return found;
    }

    /**
     * 定时器分支 job：{@code EVENT_TIMER} 类型且没有宿主（它自己就是那一格）。
     *
     * <p>类型是 {@code EVENT_TIMER} 而不是 {@code TIMER}：竞速与边界打断共用一个类型时，
     * 落选分支的清理（按类型白名单删 job）就会漏掉它 ——
     * 那是本轮真的踩到的一个缺陷，见 {@code WfJobType#EVENT_TIMER} 的注释。
     */
    private List<WfJob> timerBranchJobsOf(String pid) {
        List<WfJob> found = new ArrayList<>();
        for (WfJob job : timerJobsOf(pid)) {
            if (job.getAttachedToRef() == null) {
                found.add(job);
            }
        }
        return found;
    }

    @Test
    @DisplayName("定时器分支：到点即算它赢了，其余分支作废，流程沿它自己的出线走")
    void timerBranchWinsWhenDue() {
        String pid = startRace(TIMER_CATCH_BPMN);
        List<WfJob> branches = timerBranchJobsOf(pid);
        assertEquals(1, branches.size(),
                "网关的两条分支里应当只有一条是定时器。实际 " + timerJobsOf(pid));
        WfJob timerJob = branches.get(0);
        assertEquals("waitMsg", timerJob.getElementId(), "elementId 记的是捕获事件本身");
        assertNotNull(timerJob.getDuedate(), "定时器分支必须有触发时刻 —— 没有它就永远是哑表");
        assertNull(timerJob.getSubscriptionName(),
                "定时器不是在等某个事件名，留个名字只会让人以为它在等消息");

        // 触发时刻必须真的是「进入这一格 + PT5M」，而不能只是"某个和 duedate 自洽的假值"。
        // 下面两处 executeDueJobs 都拿 duedate 自己当基准（早一点/晚一点），
        // 如果这里不钉住绝对值，duedate 被改成常数或远期时刻时测试照样全绿 ——
        // 量的是自洽，不是正确。基准取实例开始时间：base 是 token 的 enteredTime，
        // 与它相差毫秒级（同一轮分叉里建的），所以 5 秒的容差只够放下这点抖动
        Date expected = new Date(repo.findProcessInstance(pid).getStartTime().getTime()
                + TimeUnit.MINUTES.toMillis(5));
        assertTrue(Math.abs(timerJob.getDuedate().getTime() - expected.getTime()) < 5_000L,
                "触发时刻应当是进入该分支后 5 分钟。实际 " + timerJob.getDuedate()
                        + "，期望约 " + expected);

        // 还没到点：什么都不该发生
        assertEquals(0, new WfJobService(repo, runtime)
                .executeDueJobs(new Date(timerJob.getDuedate().getTime() - 1000)),
                "没到点就触发，那不是定时器");
        assertTrue(openTasks(pid).isEmpty());

        // 到点了：定时器分支赢
        assertEquals(1, new WfJobService(repo, runtime)
                .executeDueJobs(new Date(timerJob.getDuedate().getTime() + 1000)));
        List<WfTask> open = openTasks(pid);
        assertEquals(1, open.size(),
                "赢的那条应当建出待办，输的那条不许有 —— 实际 " + open.size());
        assertEquals("onApprove", open.get(0).getDefinitionId(),
                "沿定时器分支自己的出线走（等主管批 → 批了）");
        assertTrue(timerJobsOf(pid).isEmpty(),
                "竞速结束，所有分支的等待都要清掉 —— 留着的话到点会再响一次");
    }

    @Test
    @DisplayName("消息先到：定时器分支连同它的 job 一起作废，到点后不会再响")
    void timerBranchLosesToMessage() {
        String pid = startRace(TIMER_RACE_BPMN);
        WfJob timerJob = timerBranchJobsOf(pid).get(0);

        runtime.triggerMessage("bossApprove", pid, "boss", null, "批了");
        assertEquals("onApprove", openTasks(pid).get(0).getDefinitionId());

        // 关键：定时器分支的 job 必须**连同 token 一起**被清掉。
        // 只清 token 的话 job 还在，到点会再触发一次 —— 而那时候 token 已经不在
        // waitMsg 上了，于是"定时器把流程从别处拽回来"，或者更糟：静默什么都不发生
        assertTrue(timerBranchJobsOf(pid).isEmpty(),
                "输掉的分支的定时器 job 必须被删掉 —— 实际还挂着 " + timerBranchJobsOf(pid));
        assertEquals(0, new WfJobService(repo, runtime)
                        .executeDueJobs(new Date(timerJob.getDuedate().getTime() + 60_000)),
                "已经输掉的定时器到点时不得再推进流程");
        assertEquals(1, openTasks(pid).size(),
                "到点后仍然只有一条待办 —— 多出来的说明定时器把流程又推了一遍");
        // 三条分支时，落选的是**两条**，中间那条（信号）的 job 也要跟着清掉
        assertTrue(catchJobsOf(pid).isEmpty(),
                "输掉的分支（含信号那条）的订阅都要清掉 —— 实际还挂着 " + catchJobsOf(pid));
        assertEquals(2, endedCatchEvents(pid).size(),
                "两条落选分支各记一条 eventGatewayLost。实际 " + endedCatchEvents(pid));
    }

    @Test
    @DisplayName("定时器分支在订阅视图里带上网关 id，看得出这是同一次竞速")
    void timerBranchAppearsInSubscriptions() {
        String pid = startRace(TIMER_RACE_BPMN);
        WfSubscriptionService subscriptions = new WfSubscriptionService(repo, repository);
        String timerBranchId = null;
        for (WfSubscriptionView view : subscriptions.listSubscriptions(
                new WfSubscriptionQuery().setProcessInstanceId(pid))) {
            if (WfJobType.EVENT_TIMER.name().equals(view.getJobType())) {
                timerBranchId = view.getId();
                assertEquals("waitTimer", view.getActivityId());
                assertEquals("timer", view.getWaitingFor());
                assertNotNull(view.getDuedate());
                // 竞速分支带上网关 id：运维看得到这个 id 才知道
                // "这几条是同一次竞速"，而定时器分支原先压根不存在
                assertEquals("eg", view.getGatewayId(),
                        "定时器分支也是竞速分支，必须与消息分支一样带上网关 id");
            }
        }
        assertNotNull(timerBranchId, "定时器分支应当出现在订阅列表里 —— "
                + "它正在等一个将来会到来的时刻，却查不到，那就是个没人管的状态");
    }

    @Test
    @DisplayName("定时器表达式的变量取不到值要当场失败，不能建出一个永不响的哑表")
    void timerBranchWithUnresolvableVariableFails() {
        String xml = TIMER_CATCH_BPMN
                .replace("<timeDuration>PT5M</timeDuration>", "<timeDuration>${waitMinutes}</timeDuration>")
                .replace("id=\"timerCatchProcess\"", "id=\"timerVarProcess\"");
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(xml));
        assertThrows(WfEngineException.class,
                () -> runtime.startProcessInstance(definition, "BIZ-" + System.nanoTime(),
                        "alice", null, new HashMap<String, Object>()),
                "算不出触发时刻就启动失败，好过挂一个永远不会响的定时器");
    }

    @Test
    @DisplayName("绕过部署期时，孤立的定时器捕获事件在引擎侧被拦下，且不挂出哑定时器")
    void runtimeRefusesStandaloneTimerCatch() {
        // 直接 startProcessInstance 验不到这一点：它自己也跑校验器，抛的是
        // WfDefinitionException，与引擎侧那道闸门不是同一条路径。
        // 真正要模拟的是「部署期看到的是好的、运行期看到的是坏的」——
        // 与下面那条多入线的用例同一手法：持久化装饰器在每次读取时把网关换成普通节点，
        // 于是部署期通过、运行期那个捕获事件不再是网关分支
        NonGatewayFlowRepo doctored = new NonGatewayFlowRepo();
        doctored.initialize();
        WfRepositoryService repo2 = new WfRepositoryService(doctored);
        WfRuntimeService runtime2 = new WfRuntimeService(repo2, doctored, new WfEngine(),
                new WfHookDispatcher());
        WfDefinition deployed = repo2.deploy(new WfXmlParser().parse(TIMER_CATCH_BPMN));
        // 关键：**从持久化里把定义读回来再启动**，传进 startProcessInstance 的必须
        // 是运行期真正会看到的那一份。引擎那道闸门（eventCatchJob）判的是
        // 启动时手里的 WfDefinition 对象，不是自己去仓储重读 ——
        // 而 InMemoryWorkflowPersistence#findDefinition 返回的是副本（走 codec），
        // 所以部署时传进去的那个原对象是"干净"的，直接拿来启动等于没绕过部署期
        WfDefinition atRuntime = doctored.findDefinition(
                deployed.getKey(), deployed.getVersion());
        assertNotSame(deployed, atRuntime,
                "读回来的必须是副本：InMemoryWorkflowPersistence#findDefinition 走 codec，"
                        + "返回的就是同一个对象就说明这条用例根本没绕过部署期");
        String pid = runtime2.startProcessInstance(atRuntime, "BIZ-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());

        // 引擎侧那道闸门是 WfEngine#fail：把实例停成「内部终止 + 写明原因」，
        // 而不是抛 —— 它守的是运行期，运行期不该让整批任务崩掉
        WfProcessInstance instance = doctored.findProcessInstance(pid);
        assertEquals(WfProcessStatus.INTERNALLY_TERMINATED, instance.getStatus(),
                "绕过了部署期也必须在引擎侧被拦下。实际 " + instance.getStatus());
        assertTrue(String.valueOf(instance.getDeleteReason())
                        .contains("不是本引擎等得住的事件网关分支"),
                "要把原因写进实例：运维在列表里看到这条单子停了，得知道卡在哪。实际 "
                        + instance.getDeleteReason());
        // 关键：**不许留下任何等待中的 job**。
        // 挂一个永不响的定时器的话，流程不是"停"，而是"永远等下去且没人管" ——
        // 后者比前者难查得多，因为它连一条报错线索都不留
        assertTrue(timerJobsOf(pid).isEmpty(),
                "被拦下时不得挂出哑定时器。实际 " + timerJobsOf(pid));
    }

    @Test
    @DisplayName("job 类型与它指向的节点类型对不上时要报错，不能猜一个继续跑")
    void timerJobPointingAtWrongNodeTypeIsRejected() {
        String pid = startRace(TIMER_RACE_BPMN);
        WfJob dirty = dirtyTimerJob(pid);

        // 直接调 fireTimer 才验得到那道闸门：走 executeDueJobs 的话异常会被
        // 按设计吞掉并记成 job 失败（一个 job 失败不该让整批停摆）
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.fireTimer(dirty));
        assertTrue(ex.getMessage().contains("对不上"),
                "报错要说清是类型与节点类型对不上。实际 " + ex.getMessage());

        // 走执行器时也不能"安静地什么都没发生"：那条 job 应当被记成失败，
        // 于是它会出现在故障视图里等人来看
        repo.saveJob(dirty);
        new WfJobService(repo, runtime)
                .executeDueJobs(new Date(System.currentTimeMillis() + 60_000));
        WfJob failed = repo.findJob("dirty-timer");
        assertNotNull(failed, "执行器吞掉异常后应当把 job 记成失败，而不是删掉就不管了");
        assertNotNull(failed.getExceptionMessage(),
                "失败原因要留下来，它要能在故障视图里被看到。实际 " + failed.getExceptionMessage());
    }

    /** 类型是边界打断、却指向网关分支捕获事件的脏 job。 */
    private WfJob dirtyTimerJob(String pid) {
        WfJob dirty = new WfJob();
        dirty.setId("dirty-timer");
        dirty.setProcessInstanceId(pid);
        dirty.setType(WfJobType.TIMER);
        dirty.setElementId("waitTimer");
        dirty.setDuedate(new Date(System.currentTimeMillis() + 1000));
        dirty.setCreateTime(new Date());
        dirty.setRetries(WfJob.DEFAULT_RETRIES);
        dirty.nextRevision();
        return dirty;
    }

    private List<WfTask> openTasks(String pid) {
        return repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(10));
    }

    private String handlerOf(String pid) {
        List<WfTask> open = openTasks(pid);
        assertEquals(1, open.size(), "应当恰好有一个待办，实际 " + open.size());
        return open.get(0).getAssignee();
    }

    private List<String> endedCatchEvents(String pid) {
        List<String> ended = new ArrayList<>();
        for (WfExecution execution : repo.findExecutionsByProcessInstance(pid)) {
            if (execution.isEnded()
                    && (execution.getActivityId().startsWith("wait"))) {
                ended.add(execution.getActivityId());
            }
        }
        return ended;
    }

    // ==================== 分叉 ====================

    @Test
    @DisplayName("到达事件网关：不产生任何人工待办，只挂出两条订阅")
    void gatewayForksWithoutCreatingTasks() {
        String pid = startRace();
        assertTrue(openTasks(pid).isEmpty(),
                "中间捕获事件等的是消息/信号，不是某个人 —— 出现待办就说明它退化成人工任务了");
        List<WfJob> jobs = catchJobsOf(pid);
        assertEquals(2, jobs.size(), "两条出线各挂一条订阅，实际 " + jobs.size());
    }

    @Test
    @DisplayName("订阅 job 记的是捕获事件本身、且不带触发时刻")
    void subscriptionPointsAtTheCatchEvent() {
        String pid = startRace();
        for (WfJob job : catchJobsOf(pid)) {
            assertTrue("waitMsg".equals(job.getElementId())
                            || "waitSignal".equals(job.getElementId()),
                    "elementId 应当是捕获事件 id（竞速的粒度在这一格上），实际 " + job.getElementId());
            assertNotNull(job.getExecutionId(), "必须能定位到具体哪一条 token");
            assertTrue(job.getDuedate() == null,
                    "订阅型 job 不由时间触发，带了 duedate 会被定时器扫描器提前捞走");
        }
    }

    // ==================== 竞速 ====================

    @Test
    @DisplayName("消息到达：走消息分支，信号分支连同它的订阅一起作废")
    void messageWinsAndCancelsSiblings() {
        String pid = startRace();
        runtime.triggerMessage("bossApprove", pid, "boss", null, "批了");

        assertEquals(1, openTasks(pid).size());
        assertEquals("ops", handlerOf(pid));
        assertTrue(endedCatchEvents(pid).contains("waitSignal"),
                "没被命中的分支必须作废。实际已结束的分支 " + endedCatchEvents(pid));
        assertTrue(catchJobsOf(pid).isEmpty(),
                "作废分支的订阅必须一起删掉，否则事件下次来会命中一条已死的 token");
    }

    @Test
    @DisplayName("信号到达：走信号分支，消息分支被作废")
    void signalWinsOverMessage() {
        String pid = startRace();
        runtime.broadcastSignal("erpDone", "erp", null, "回执到了");

        assertEquals(1, openTasks(pid).size());
        assertTrue(endedCatchEvents(pid).contains("waitMsg"),
                "没被命中的分支必须作废。实际已结束的分支 " + endedCatchEvents(pid));
        assertTrue(catchJobsOf(pid).isEmpty());
    }

    @Test
    @DisplayName("只唤醒被命中的那一条 —— 不误伤同一实例上的其它 token")
    void onlyTheHitBranchIsResumed() {
        String pid = startRace();
        runtime.triggerMessage("bossApprove", pid, "boss", null, "批了");
        WfProcessInstance instance = repo.findProcessInstance(pid);

        // 走到 onApprove 之后应该只剩一条活跃 token：那一条分支一路走到底
        int alive = 0;
        for (WfExecution execution : repo.findExecutionsByProcessInstance(pid)) {
            if (!execution.isEnded()) {
                alive++;
                assertEquals("onApprove", execution.getActivityId());
            }
        }
        assertEquals(1, alive, "竞速之后应当只剩命中的那一条 token，实例 " + instance.getId());
    }

    @Test
    @DisplayName("竞速后流程能一路走完，不会卡在已作废的分支上")
    void raceWinnerRunsToTheEnd() {
        String pid = startRace();
        runtime.triggerMessage("bossApprove", pid, "boss", null, "批了");
        WfTask task = openTasks(pid).get(0);
        runtime.completeTask(task.getId(), "ops", "办结", null);

        assertTrue(repo.findProcessInstance(pid).getStatus().isTerminal(),
                "命中的分支应当能把流程走完");
    }

    @Test
    @DisplayName("同一个事件投递两次：第二次不生效，也不报错")
    void secondDeliveryIsANoop() {
        String pid = startRace();
        runtime.triggerMessage("bossApprove", pid, "boss", null, "第一次");
        assertEquals(1, openTasks(pid).size());

        // 第二条订阅（waitSignal 等的是 erpDone）已随竞速删除，所以这里匹配不到任何分支
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.triggerMessage("bossApprove", pid, "boss", null, "第二次"));
        assertTrue(ex.getMessage().contains("bossApprove"),
                "报错要说清楚是哪条消息没等到。实际 " + ex.getMessage());
        assertEquals(1, openTasks(pid).size(), "重复投递不能让流程多走一格");
    }

    @Test
    @DisplayName("被作废的分支在轨迹上留痕 —— 否则事后看不出走过哪条")
    void losingBranchIsRecordedInHistory() {
        String pid = startRace();
        runtime.triggerMessage("bossApprove", pid, "boss", null, "批了");

        List<String> outcomes = new ArrayList<>();
        for (WfActivityInstance activity : repo.findActivityInstances(pid)) {
            if ("waitSignal".equals(activity.getActivityId())) {
                outcomes.add(activity.getOutcome());
            }
        }
        assertEquals(1, outcomes.size(),
                "落选分支应当有一条历史记录，实际 " + repo.findActivityInstances(pid).size());
        assertEquals("eventGatewayLost", outcomes.get(0),
                "落选要能一眼认出来，而不是和正常完成混在一起");
    }

    @Test
    @DisplayName("不经网关的中间捕获事件：等消息继续，不作废任何东西")
    void plainCatchEventJustWaits() {
        String pid = startRace(PLAIN_CATCH_BPMN);
        assertTrue(openTasks(pid).isEmpty());
        assertEquals(1, catchJobsOf(pid).size());

        runtime.triggerMessage("anyNews", pid, "ops", null, "有消息了");
        assertEquals("ops", handlerOf(pid), "普通捕获事件被唤醒后应当继续往下走");
    }

    // ==================== 部署期 ====================

    @Test
    @DisplayName("出线不足两条：部署期报错，它退化成了普通网关")
    void singleOutgoingIsRejected() {
        List<WfValidationIssue> issues = validate(SINGLE_OUT_BPMN);
        assertTrue(hasErrorAbout(issues, "至少要有 2 条出线"),
                "应当报出线不足。实际 " + messages(issues));
    }

    @Test
    @DisplayName("出线不指中间捕获事件：部署期报错，分支会当场跑完而不是等事件")
    void outgoingToNonCatchEventIsRejected() {
        List<WfValidationIssue> issues = validate(NOT_CATCH_BPMN);
        assertTrue(hasErrorAbout(issues, "必须是 intermediateCatchEvent"),
                "应当报出线类型不对。实际 " + messages(issues));
    }

    @Test
    @DisplayName("事件网关下的定时器分支：部署期放行")
    void timerBranchDeploysFine() {
        List<WfValidationIssue> issues = validate(TIMER_CATCH_BPMN);
        for (WfValidationIssue issue : issues) {
            assertTrue(issue.getSeverity() != WfValidationIssue.Severity.ERROR,
                    "定时器作为网关分支是合法写法，不该报 ERROR：" + issue.getMessage());
        }
    }

    @Test
    @DisplayName("孤立的定时器捕获事件：部署期仍报错，并说清差的是哪一块")
    void standaloneTimerCatchIsRejected() {
        List<WfValidationIssue> issues = validate(STANDALONE_TIMER_BPMN);
        assertTrue(hasErrorAbout(issues, "只支持作为事件网关的分支"),
                "应当报「不是网关分支」。实际 " + messages(issues));
        assertTrue(hasErrorAbout(issues, "永不响"),
                "要说出后果：挂上去会得到一个永不响、也不报错的哑表。实际 " + messages(issues));
    }

    @Test
    @DisplayName("捕获事件没有事件定义：部署期报错，它是一条永远等不到的死路")
    void catchEventWithoutDefinitionIsRejected() {
        List<WfValidationIssue> issues = validate(NO_DEF_BPMN);
        assertTrue(hasErrorAbout(issues, "永远等不到的死路"),
                "应当报缺事件定义。实际 " + messages(issues));
    }

    @Test
    @DisplayName("捕获事件入线不唯一：部署期报错，运行时靠它反查网关")
    void catchEventWithTwoIncomingFlowsIsRejected() {
        List<WfValidationIssue> issues = validate(TWO_IN_BPMN);
        assertTrue(hasErrorAbout(issues, "必须恰好有 1 条入线"),
                "应当报入线不唯一。实际 " + messages(issues));
    }

    @Test
    @DisplayName("普通中间捕获事件部署得过去，只给一条提醒")
    void plainCatchEventDeploysWithWarning() {
        WfDefinition definition = new WfXmlParser().parse(PLAIN_CATCH_BPMN);
        List<WfValidationIssue> issues = new WfDefinitionValidator().validate(definition);
        for (WfValidationIssue issue : issues) {
            assertTrue(issue.getSeverity() != WfValidationIssue.Severity.ERROR,
                    "普通捕获事件是合法写法，不该报 ERROR：" + issue.getMessage());
        }
    }

    @Test
    @DisplayName("intermediateThrowEvent 被挡在部署期，不许退化成人工任务")
    void throwEventIsRejected() {
        String xml = RACE_BPMN.replace(
                "<intermediateCatchEvent id=\"waitMsg\" name=\"等主管批\">\n"
                        + "      <messageEventDefinition messageRef=\"bossApprove\"/>\n"
                        + "    </intermediateCatchEvent>",
                "<intermediateThrowEvent id=\"waitMsg\" name=\"抛事件\"/>")
                .replace("id=\"raceProcess\"", "id=\"throwProcess\"");
        List<WfValidationIssue> issues = validate(xml);
        assertTrue(hasErrorAbout(issues, "intermediateThrowEvent"),
                "抛事件的语义是主动打断别人，退化成等人来点是另一个流程。实际 " + messages(issues));
    }

    @Test
    @DisplayName("命中的分支在轨迹上留一条，并写明是被哪个事件选中的")
    void hitCatchEventIsRecordedOnce() {
        String pid = startRace();
        runtime.triggerMessage("bossApprove", pid, "boss", null, "批了");

        List<String> outcomes = new ArrayList<>();
        for (WfActivityInstance activity : repo.findActivityInstances(pid)) {
            if ("waitMsg".equals(activity.getActivityId())) {
                outcomes.add(activity.getOutcome());
            }
        }
        // 赢的这条与输的那些要在轨迹上对称：各一条，各说各的。
        // 只给落选方记一条的话，"这次竞速到底选中了谁"就只能靠猜 ——
        // 而这恰恰是事后复盘事件网关时第一个要回答的问题
        assertEquals(1, outcomes.size(),
                "命中的捕获事件应当只有一条活动记录。实际 " + outcomes);
        assertTrue(outcomes.get(0).contains("bossApprove"),
                "结果里要能看出是被哪条消息选中的。实际 " + outcomes.get(0));
    }

    @Test
    @DisplayName("token 真的停在捕获事件上 —— 流程仍在跑，只是停在等事件")
    void tokenWaitsAtTheCatchEvent() {
        String pid = startRace();
        // 只断言"没有待办""有订阅"是不够的：把 token 的 WAITING 去掉，
        // 流程会卡在捕获事件上但状态仍是 ACTIVE，订阅也照样挂着，两条断言都过得去。
        // 真正能分开的是"流程还活着，且两条 token 都停在各自的捕获事件上"
        assertFalse(repo.findProcessInstance(pid).getStatus().isTerminal(),
                "等事件期间流程必须是活跃的 —— 结束了就说明它根本没等，直接穿过去了");
        List<String> waiting = new ArrayList<>();
        for (WfExecution execution : repo.findExecutionsByProcessInstance(pid)) {
            assertTrue(execution.isWaiting(),
                    "每条分支的 token 都应当是等待态，实际 " + execution.getActivityId()
                            + " 是 " + execution.getState());
            waiting.add(execution.getActivityId());
        }
        assertEquals(2, waiting.size());
        assertTrue(waiting.contains("waitMsg") && waiting.contains("waitSignal"),
                "两条分支各自停在自己的捕获事件上，实际 " + waiting);
    }

    @Test
    @DisplayName("绕过部署期时，入线不唯一会当场炸 —— 不能安静地一个兄弟都不作废")
    void runtimeRefusesAmbiguousCatchEvent() {
        // 正常路径进不来这个状态：deploy 先过校验，startProcessInstance 也拒绝未部署的定义。
        // 这里模拟的是"运行期读到的定义有问题"——用持久化装饰器在每次读取时多塞一条入线，
        // 因为 InMemoryWorkflowPersistence#findDefinition 返回的是<b>副本</b>（走 codec），
        // 直接改部署时传进去的那个对象，运行期是看不到的
        AmbiguousFlowRepo doctored = new AmbiguousFlowRepo();
        doctored.initialize();
        WfRepositoryService repo2 = new WfRepositoryService(doctored);
        WfRuntimeService runtime2 = new WfRuntimeService(repo2, doctored, new WfEngine(),
                new WfHookDispatcher());
        WfDefinition definition = repo2.deploy(new WfXmlParser().parse(RACE_BPMN));
        String pid = runtime2.startProcessInstance(definition, "BIZ-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());

        // 若 gatewayOf 安静返回 null，这一轮竞速会因为"没有网关"而谁都不作废，
        // 流程把两条分支全跑一遍 —— 正是这个功能存在的理由被推翻，且毫无报错
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime2.triggerMessage("bossApprove", pid, "boss", null, "批了"));
        assertTrue(ex.getMessage().contains("2 条入线"),
                "报错要说清是入线不唯一。实际 " + ex.getMessage());
    }

    /**
     * 每次读取定义时把网关换成普通节点，让那个定时器捕获事件不再是网关分支。
     *
     * <p>模拟的是"部署期校验看到的是好的、运行期看到的是坏的"。
     */
    private static final class NonGatewayFlowRepo extends InMemoryWorkflowPersistence {

        @Override
        public synchronized WfDefinition findDefinition(String key, int version) {
            WfDefinition definition = super.findDefinition(key, version);
            if (definition != null && "timerCatchProcess".equals(key)) {
                WfNode gateway = definition.node("eg");
                if (gateway != null) {
                    gateway.setType(WfNodeType.PARALLEL_GATEWAY);
                }
                definition.buildIndex();
            }
            return definition;
        }
    }

    /** 每次读取定义时给捕获事件多塞一条入线，模拟"运行期看到的定义被改坏"。 */
    private static final class AmbiguousFlowRepo extends InMemoryWorkflowPersistence {

        @Override
        public synchronized WfDefinition findDefinition(String key, int version) {
            WfDefinition definition = super.findDefinition(key, version);
            if (definition != null && "raceProcess".equals(key)) {
                WfFlow extra = new WfFlow();
                extra.setId("fExtra");
                extra.setSourceRef("s1");
                extra.setTargetRef("waitMsg");
                definition.getFlows().add(extra);
                definition.buildIndex();
            }
            return definition;
        }
    }

    @Test
    @DisplayName("部署 eventBasedGateway 会真的抛出来，不是只记一条日志")
    void deployThrowsOnInvalid() {
        WfDefinition definition = new WfXmlParser().parse(SINGLE_OUT_BPMN);
        assertThrows(WfDefinitionException.class, () -> repository.deploy(definition));
    }

    // ==================== 辅助 ====================

    private List<WfValidationIssue> validate(String xml) {
        return new WfDefinitionValidator().validate(new WfXmlParser().parse(xml));
    }

    private boolean hasErrorAbout(List<WfValidationIssue> issues, String fragment) {
        for (WfValidationIssue issue : issues) {
            if (issue.getSeverity() == WfValidationIssue.Severity.ERROR
                    && issue.getMessage().contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private String messages(List<WfValidationIssue> issues) {
        List<String> out = new ArrayList<>();
        for (WfValidationIssue issue : issues) {
            out.add(issue.getSeverity() + " " + issue.getMessage());
        }
        return out.toString();
    }
}
