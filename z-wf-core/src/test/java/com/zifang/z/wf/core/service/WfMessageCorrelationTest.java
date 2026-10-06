package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfMessageCorrelation;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 消息关联 {@code correlate} —— 不给流程实例 id，由引擎自己找到那条该被唤醒的流程。
 *
 * <p>这组用例盯的是<b>关联错了还不报错</b>的几件事。消息点对点，喂错一条单的后果是
 * 一条审批被无声地推进或办结，而调用方看到的是一次成功的 HTTP 200。
 * 所以本类的重心不在"能不能找到"，在<b>分不清的时候有没有把分不清说出来</b>：
 *
 * <ol>
 *   <li><b>三类等待形态必须全在候选集里</b>。漏掉任何一类，表现都是
 *       「correlate 说没人等，但 triggerMessage 能触发」—— 而这正是本轮要消灭的分叉。</li>
 *   <li><b>「没人在等」与「有人在等但不匹配」必须分开报</b>。合并成一句"没找到"，
 *       调用方就不知道自己该去调条件还是该去查为什么没人在等。</li>
 *   <li><b>变量比较必须是精确相等</b>。{@code 1} 与 {@code "1"} 判为不等：
 *       审批变量经 JSON 往返数字变字符串是常态，宽松比较一旦判等，
 *       调用方永远查不出「为什么这条消息配上了另一条单」。</li>
 *   <li><b>键不存在不等于「值为 null」</b>。两种状态在排障时必须分开看。</li>
 *   <li><b>匹配条件不得有副作用</b>。条件常直接来自外部消息体，
 *       把未经校验的外部字段灌进流程状态，等于让「配错了」从一次报错变成一次数据损坏。</li>
 *   <li><b>多条匹配必须报错</b>，且报错要列出候选。点对点消息没有"挑一个"的余地。</li>
 * </ol>
 */
class WfMessageCorrelationTest {

    /** start -> receiveTask(orderPaid) -> end */
    private static final String RECEIVE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"receiveProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <receiveTask id=\"wait1\" name=\"等待付款通知\" zifang:messageName=\"orderPaid\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"wait1\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"wait1\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 审批节点上挂一个「撤销」消息边界：打断语义，走边界自己的出线结束流程。 */
    private static final String BOUNDARY_BPMN =
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

    /**
     * 事件网关：等主管批 / 等 ERP 通知，谁先来听谁的（竞速语义）。
     *
     * <p>第二条出线是信号捕获，不是凑数：事件网关少于两条出线会被校验器拒掉，
     * 而这里如果两条都等消息，关联时会撞上「匹配到多条」报歧义，
     * 测不到「只唤醒被命中的那一条」这件事。
     */
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
            + "    <intermediateCatchEvent id=\"waitSignal\" name=\"等通知\">\n"
            + "      <signalEventDefinition signalRef=\"notify\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <userTask id=\"onApprove\" name=\"批了\" zifang:assignee=\"ops\"/>\n"
            + "    <userTask id=\"onNotify\" name=\"通知到了\" zifang:assignee=\"notify\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <endEvent id=\"e2\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"eg\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"eg\" targetRef=\"waitMsg\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"eg\" targetRef=\"waitSignal\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"waitMsg\" targetRef=\"onApprove\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"waitSignal\" targetRef=\"onNotify\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"onApprove\" targetRef=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f7\" sourceRef=\"onNotify\" targetRef=\"e2\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /**
     * 与 {@link #RECEIVE_BPMN} 只差一个流程 id —— 消息名刻意保持相同。
     *
     * <p>「两个定义等同一个消息名」正是 definitionKey 这条条件存在的理由：
     * 只留一条消息名时，发消息的服务根本分不清该叫醒哪套流程。
     */
    private static final String RECEIVE_TWIN_BPMN = RECEIVE_BPMN
            .replace("id=\"receiveProcess\"", "id=\"receiveProcessTwin\"");

    /**
     * 并行两条分支，<b>各等同一个消息名</b>。
     *
     * <p>判别的关键在「同一个实例里有两条同名等待」：这让执行级变量成为唯一的分法 ——
     * 两条候选同属一个流程实例，流程级变量对它们一视同仁，
     * 任何基于流程变量的实现都会得到"两条都匹配"，进而报歧义。
     */
    private static final String PARALLEL_RECEIVE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"parallelReceiveProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <parallelGateway id=\"pg\"/>\n"
            + "    <receiveTask id=\"waitA\" name=\"A 通道\" zifang:messageName=\"payDone\"/>\n"
            + "    <receiveTask id=\"waitB\" name=\"B 通道\" zifang:messageName=\"payDone\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <endEvent id=\"e2\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"pg\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"pg\" targetRef=\"waitA\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"pg\" targetRef=\"waitB\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"waitA\" targetRef=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"waitB\" targetRef=\"e2\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfVariableService variables;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
        variables = new WfVariableService(repo, new com.zifang.z.wf.core.engine.WfIdGenerator
                .DefaultWfIdGenerator());
    }

    // ==================== 候选集：三类等待形态都要能关联上 ====================

    @Test
    @DisplayName("候选集覆盖接收任务 —— 最常用的一类等待")
    void correlateReachesReceiveTask() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(RECEIVE_BPMN));
        String pid = runtime.startProcessInstance(definition, "order-1", null, null,
                new HashMap<String, Object>());

        WfProcessInstance done = runtime.correlate(
                new WfMessageCorrelation("orderPaid").setBusinessKey("order-1"),
                "system", "ERP 回执");

        assertTrue(done.getStatus().isTerminal(),
                "关联命中后流程应当跑完，实际=" + done.getStatus());
        assertEquals(pid, done.getId());
    }

    @Test
    @DisplayName("候选集覆盖消息边界 —— 打断语义，宿主待办要作废")
    void correlateReachesMessageBoundary() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(BOUNDARY_BPMN));
        String pid = runtime.startProcessInstance(definition, "cancel-1", null, null,
                new HashMap<String, Object>());
        List<WfTask> before = openTasks();
        assertEquals(1, before.size(), "前置条件：审批待办确实在");

        WfProcessInstance done = runtime.correlate(
                new WfMessageCorrelation("cancel").setBusinessKey("cancel-1"),
                "system", "撤回申请");

        assertTrue(done.getStatus().isTerminal(),
                "被消息边界打断后流程应当沿边界出线结束，实际=" + done.getStatus());
        assertEquals(0, openTasks().size(),
                "打断后宿主待办必须作废，否则人还会以为能办");
        assertFalse(repo.findTask(before.get(0).getId()).isOpen());
        assertEquals(pid, done.getId());
    }

    @Test
    @DisplayName("候选集覆盖事件网关分支 —— 竞速语义，被唤醒的分支要真的走完")
    void correlateReachesEventGatewayBranch() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(RACE_BPMN));
        runtime.startProcessInstance(definition, "race-1", null, null, new HashMap<String, Object>());
        // 前置条件：分叉出去的两条都在等事件，还没走到人工节点，所以一条待办都没有。
        // 事件网关退化成人工待办的话，这里会是 1 —— 那正是本轮要排除的写法。
        assertEquals(0, openTasks().size(), "分叉当刻应当谁也没在做");

        WfProcessInstance done = runtime.correlate(
                new WfMessageCorrelation("bossApprove").setBusinessKey("race-1"),
                "system", "主管批了");

        assertFalse(done.getStatus().isTerminal(),
                "被唤醒的分支停在人工节点上，流程不该已经结束，实际=" + done.getStatus());
        List<WfTask> left = openTasks();
        assertEquals(1, left.size(), "只该叫醒被命中的那一条分支，实际=" + left.size());
        assertEquals("onApprove", left.get(0).getDefinitionId(),
                "被唤醒的应当是主管批那条分支，实际=" + left.get(0).getDefinitionId());
    }

    // ==================== 关联条件：四类条件各自有效 ====================

    @Test
    @DisplayName("按业务键能从多条在等的流程里挑出唯一那条")
    void businessKeyPicksTheRightInstance() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(RECEIVE_BPMN));
        runtime.startProcessInstance(definition, "order-A", null, null, new HashMap<String, Object>());
        String wantPid = runtime.startProcessInstance(definition, "order-B", null, null,
                new HashMap<String, Object>());
        runtime.startProcessInstance(definition, "order-C", null, null, new HashMap<String, Object>());

        WfProcessInstance done = runtime.correlate(
                new WfMessageCorrelation("orderPaid").setBusinessKey("order-B"),
                "system", "只配 order-B");

        assertEquals(wantPid, done.getId(), "必须只推进 order-B 那一条");
        assertEquals(2, openTasks().size(), "另两条流程必须仍在等待，一条都不能被带走");
    }

    @Test
    @DisplayName("按流程定义 key 能挑 —— 两个定义等同一个消息名时")
    void definitionKeyPicksTheRightDefinition() {
        repository.deploy(new WfXmlParser().parse(RECEIVE_BPMN));
        WfDefinition twin = repository.deploy(new WfXmlParser().parse(RECEIVE_TWIN_BPMN));
        runtime.startProcessInstance("receiveProcess", "k-1", null, new HashMap<String, Object>());
        runtime.startProcessInstance(twin, "k-2", null, null, new HashMap<String, Object>());
        // 前置条件：两条候选等的是同一个消息名，只靠消息名分不开
        assertTrue(assertThrows(WfEngineException.class,
                () -> runtime.correlate(new WfMessageCorrelation("orderPaid"), "system", "裸配"))
                .getMessage().contains("匹配到 2 条流程"));

        WfProcessInstance done = runtime.correlate(
                new WfMessageCorrelation("orderPaid").setDefinitionKey("receiveProcessTwin"),
                "system", "配到孪生定义上");

        assertEquals("receiveProcessTwin", done.getDefinitionKey());
        assertEquals(1, openTasks().size(), "receiveProcess 那个实例不该被带走");
    }

    @Test
    @DisplayName("按流程级变量能挑 —— 这是消息驱动集成最常用的一招")
    void processVariablePicksTheRightInstance() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(RECEIVE_BPMN));
        runtime.startProcessInstance(definition, "bk-1", null, null, vars("orderNo", "SO-1"));
        String wantPid = runtime.startProcessInstance(definition, "bk-2", null, null,
                vars("orderNo", "SO-2"));

        WfProcessInstance done = runtime.correlate(
                new WfMessageCorrelation("orderPaid").setVariable("orderNo", "SO-2"),
                "system", "按单号配");

        assertEquals(wantPid, done.getId());
        assertEquals(1, openTasks().size());
    }

    @Test
    @DisplayName("按执行级变量能挑 —— 同一个实例里两条分支等同一消息名时")
    void localVariablePicksTheRightBranch() {
        repository.deploy(new WfXmlParser().parse(PARALLEL_RECEIVE_BPMN));
        String pid = runtime.startProcessInstance("parallelReceiveProcess", "pr-1", null,
                new HashMap<String, Object>());
        variables.setVariableLocal(tokenId(pid, "waitA"), "channel", "A", "ops");
        variables.setVariableLocal(tokenId(pid, "waitB"), "channel", "B", "ops");

        // 前置条件：两条候选同属一个实例，流程级变量对它们一视同仁。
        // 因此这一步唯一可能的分法就是执行级变量。
        WfEngineException tooBroad = assertThrows(WfEngineException.class,
                () -> runtime.correlate(new WfMessageCorrelation("payDone"), "system", "裸配"));
        assertTrue(tooBroad.getMessage().contains("匹配到 2 条流程"),
                "不加条件时两条同名候选必须报歧义，实际: " + tooBroad.getMessage());

        runtime.correlate(new WfMessageCorrelation("payDone").setLocalVariable("channel", "A"),
                "system", "只叫醒 A 通道");

        List<WfTask> left = openTasks();
        assertEquals(1, left.size(), "B 通道必须还在等，实际剩 " + left.size());
        assertEquals("waitB", left.get(0).getDefinitionId(),
                "剩的应当是 B 通道，实际=" + left.get(0).getDefinitionId());
    }

    // ==================== 三种"分不清"必须各自说清 ====================

    @Test
    @DisplayName("零候选与全被筛掉必须分开报 —— 前者去查为什么没人在等，后者去调条件")
    void zeroCandidatesAndAllFilteredAreDifferentFailures() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(RECEIVE_BPMN));
        runtime.startProcessInstance(definition, "zf-1", null, null, new HashMap<String, Object>());
        WfTask waiting = openTasks().get(0);

        WfEngineException none = assertThrows(WfEngineException.class,
                () -> runtime.correlate(new WfMessageCorrelation("nobodyWaits"), "system", "空"));
        assertTrue(none.getMessage().contains("没有任何流程在等"),
                "没人等时报「没人在等」，实际: " + none.getMessage());
        assertFalse(none.getMessage().contains("没有一条满足关联条件"),
                "没人等时不该出现「不满足关联条件」—— 那会把排查方向直接带偏: "
                        + none.getMessage());

        WfEngineException filtered = assertThrows(WfEngineException.class,
                () -> runtime.correlate(new WfMessageCorrelation("orderPaid")
                        .setBusinessKey("不存在的单"), "system", "配错"));
        assertTrue(filtered.getMessage().contains("没有一条满足关联条件"),
                "有人在等但配不上时必须这么报，实际: " + filtered.getMessage());
        assertTrue(filtered.getMessage().contains(waiting.getId()),
                "筛不掉时报错要列出候选，让调用方知道到底有哪些单在等: "
                        + filtered.getMessage());
        assertTrue(openTasks().get(0).isOpen(), "配错时不得推进任何流程");
    }

    @Test
    @DisplayName("匹配到多条必须报错并列出全部候选，不静默挑一条")
    void ambiguousCorrelationFailsLoudly() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(RECEIVE_BPMN));
        String first = runtime.startProcessInstance(definition, "am-1", null, null,
                vars("tenant", "t-1"));
        String second = runtime.startProcessInstance(definition, "am-2", null, null,
                vars("tenant", "t-1"));

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.correlate(new WfMessageCorrelation("orderPaid")
                        .setVariable("tenant", "t-1"), "system", "租户相同，分不开"));
        assertTrue(ex.getMessage().contains("匹配到 2 条流程"),
                "应报出匹配数量，实际: " + ex.getMessage());
        // 列出候选不是为了好看：调用方要知道该补哪个条件才能分出这两条。
        assertTrue(ex.getMessage().contains(first) && ex.getMessage().contains(second),
                "报错必须点名两条候选实例 id，否则调用方无从下手: " + ex.getMessage());
        assertEquals(2, openTasks().size(), "歧义时不得推进任何一条流程");
    }

    // ==================== 变量比较的精度 ====================

    @Test
    @DisplayName("数字 1 与字符串 \"1\" 判为不等 —— 宽松比较会把消息配到别的单上")
    void variableMatchIsTypeExact() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(RECEIVE_BPMN));
        runtime.startProcessInstance(definition, "tx-1", null, null, vars("amount", 1));
        // 前置条件：变量确实以 Integer 存着（JSON 往返会把它变成 String）
        assertEquals(Integer.valueOf(1),
                repo.findProcessInstance(openTasksPid("tx-1")).getVariables().get("amount"),
                "前置条件：流程变量里 amount 是 Integer");

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.correlate(new WfMessageCorrelation("orderPaid")
                        .setVariable("amount", "1"), "system", "字符串配数字"));
        assertTrue(ex.getMessage().contains("没有一条满足关联条件"),
                "跨类型必须判为不匹配，实际: " + ex.getMessage());
        assertTrue(openTasks().get(0).isOpen(), "判为不匹配就不得推进");
    }

    @Test
    @DisplayName("键不存在不等于「值为 null」，且条件值为 null 时不得抛 NPE")
    void absentKeyIsNotNullValue() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(RECEIVE_BPMN));
        runtime.startProcessInstance(definition, "nl-1", null, null, new HashMap<String, Object>());

        WfMessageCorrelation condition = new WfMessageCorrelation("orderPaid")
                .setVariable("remark", null);

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.correlate(condition, "system", "条件值给了 null"));
        // 断言的是「不匹配」这个结论，不是「有没有抛」。
        // NPE 也是 assertThrows(WfEngineException.class) 抓不到的（类型不匹配），
        // 所以这里必须显式点名：它得是「没有一条满足关联条件」。
        assertTrue(ex.getMessage().contains("没有一条满足关联条件"),
                "条件值为 null 时必须报不匹配，不能报成引擎内部错误: " + ex.getMessage());
        assertTrue(openTasks().get(0).isOpen());
    }

    @Test
    @DisplayName("条件值为 null 而流程里该键有值时报不匹配，不得抛 NPE")
    void nullConditionValueAgainstPresentKeyFailsAsMismatch() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(RECEIVE_BPMN));
        runtime.startProcessInstance(definition, "nv-1", null, null, vars("remark", "已填"));
        // 前置条件：该键确实存在且非空 —— 键不存在时 containsKey 会先短路，
        // 根本走不到比较那一步，那条路径验不出 NPE（这是第一次反向验证打绿后才补上的）
        assertEquals("已填",
                repo.findProcessInstance(openTasksPid("nv-1")).getVariables().get("remark"));

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.correlate(new WfMessageCorrelation("orderPaid")
                        .setVariable("remark", null), "system", "要求 remark 为空"));
        assertTrue(ex.getMessage().contains("没有一条满足关联条件"),
                "条件值 null 与流程里的实际值不匹配，必须报不匹配而不是 NPE: "
                        + ex.getMessage());
        assertTrue(openTasks().get(0).isOpen());
    }

    @Test
    @DisplayName("执行级变量不回落到流程级 —— 回落会让「这条分支覆盖了什么」无法回答")
    void localVariableDoesNotFallBackToProcessLevel() {
        repository.deploy(new WfXmlParser().parse(PARALLEL_RECEIVE_BPMN));
        String pid = runtime.startProcessInstance("parallelReceiveProcess", "fb-1", null,
                vars("tenant", "t-1"));
        variables.setVariableLocal(tokenId(pid, "waitA"), "channel", "A", "ops");
        variables.setVariableLocal(tokenId(pid, "waitB"), "channel", "B", "ops");

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.correlate(new WfMessageCorrelation("payDone")
                        .setLocalVariable("tenant", "t-1"), "system", "拿流程级变量问分支"));
        assertTrue(ex.getMessage().contains("没有一条满足关联条件"),
                "分支上没有 tenant 就是没有，不能回落到流程级: " + ex.getMessage());
    }

    @Test
    @DisplayName("流程级变量不该读到分支上的同名局部变量")
    void processVariableDoesNotSeeLocalShadow() {
        repository.deploy(new WfXmlParser().parse(PARALLEL_RECEIVE_BPMN));
        String pid = runtime.startProcessInstance("parallelReceiveProcess", "sh-1", null,
                new HashMap<String, Object>());
        variables.setVariableLocal(tokenId(pid, "waitA"), "channel", "A", "ops");
        variables.setVariableLocal(tokenId(pid, "waitB"), "channel", "B", "ops");

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.correlate(new WfMessageCorrelation("payDone")
                        .setVariable("channel", "A"), "system", "拿分支变量问流程级"));
        assertTrue(ex.getMessage().contains("没有一条满足关联条件"),
                "流程级匹配不该看见分支局部变量: " + ex.getMessage());
    }

    // ==================== 匹配条件不得有副作用 ====================

    @Test
    @DisplayName("关联成功后不回写匹配用的变量 —— 条件常直接来自外部消息体")
    void matchVariablesAreNotWrittenBack() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(RECEIVE_BPMN));
        runtime.startProcessInstance(definition, "nb-1", null, null, vars("orderNo", "SO-9"));
        WfTask waiting = openTasks().get(0);

        runtime.correlate(new WfMessageCorrelation("orderPaid")
                .setVariable("orderNo", "SO-9"), "system", "配");

        WfTask after = repo.findTask(waiting.getId());
        assertFalse(after.getVariables().containsKey("orderNo"),
                "匹配条件不得被当作消息载荷写进任务变量，实际: " + after.getVariables());
        assertEquals("SO-9",
                repo.findProcessInstance(after.getProcessInstanceId())
                        .getVariables().get("orderNo"),
                "流程变量值不应因关联而改变");
    }

    // ==================== 选定后推不动，必须说出来 ====================

    @Test
    @DisplayName("选中的候选在触发时已失效（token 离开了宿主）必须报错，不能返回 null")
    void staleBoundarySubscriptionFailsLoudly() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(BOUNDARY_BPMN));
        String pid = runtime.startProcessInstance(definition, "stale-1", null, null,
                new HashMap<String, Object>());

        // 造一条"撤得慢了一步"的订阅：真 job 换成 executionId 指向不存在 token 的副本。
        // 真实场景是清理漏了一步或并发绕过锁校验（见 WfRuntimeService#completeExternalTask
        // 里同一形状的闸门注释）。正常路径下 token 离开宿主与 job 被撤在同一事务里完成，
        // 所以这里只能直接构造，不能靠"跑一遍流程"造出来。
        WfJob real = jobOf(pid, WfJobType.MESSAGE);
        assertNotNull(real, "前置条件：订阅 job 确实存在");
        repo.deleteJob(real.getId());
        WfJob stale = new WfJob();
        stale.setId("stale-" + System.nanoTime());
        stale.setProcessInstanceId(pid);
        stale.setExecutionId("no-such-token");
        stale.setElementId(real.getElementId());
        stale.setAttachedToRef(real.getAttachedToRef());
        stale.setType(WfJobType.MESSAGE);
        stale.setSubscriptionName(real.getSubscriptionName());
        stale.setCreateTime(new java.util.Date());
        repo.saveJob(stale);

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.correlate(new WfMessageCorrelation("cancel")
                        .setBusinessKey("stale-1"), "system", "打在一条已失效的订阅上"));
        assertTrue(ex.getMessage().contains("未发生任何推进"),
                "必须说清这次关联什么都没推进，实际: " + ex.getMessage());
        assertEquals(1, openTasks().size(), "宿主待办不应被这条失效订阅带走");
    }

    // ==================== 入参校验 ====================

    @Test
    @DisplayName("条件为 null / 消息名为空白，直接拒绝")
    void badArgumentsRejected() {
        assertTrue(assertThrows(WfEngineException.class,
                () -> runtime.correlate(null, "system", "空条件")).getMessage()
                .contains("关联条件不能为空"));
        assertTrue(assertThrows(WfEngineException.class,
                () -> runtime.correlate(new WfMessageCorrelation("  "), "system", "空名"))
                .getMessage().contains("消息名不能为空"));
        assertTrue(assertThrows(WfEngineException.class,
                () -> runtime.correlate(new WfMessageCorrelation(), "system", "无名"))
                .getMessage().contains("消息名不能为空"));
    }

    // ==================== 与 triggerMessage 共用候选集的证据 ====================

    @Test
    @DisplayName("不设条件的 correlate 与 triggerMessage 看到的是同一批候选")
    void correlateAndTriggerMessageShareOneCandidateSet() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(RECEIVE_BPMN));
        runtime.startProcessInstance(definition, "share-1", null, null, new HashMap<String, Object>());
        runtime.startProcessInstance(definition, "share-2", null, null, new HashMap<String, Object>());

        // triggerMessage 报歧义
        String fromTrigger = assertThrows(WfEngineException.class,
                () -> runtime.triggerMessage("orderPaid", null, "system",
                        new HashMap<String, Object>(), "歧义")).getMessage();
        // 不设任何附加条件的 correlate 必须给出同样数量级的判断
        String fromCorrelate = assertThrows(WfEngineException.class,
                () -> runtime.correlate(new WfMessageCorrelation("orderPaid"), "system", "歧义"))
                .getMessage();

        assertTrue(fromTrigger.contains("匹配到 2 个"), fromTrigger);
        assertTrue(fromCorrelate.contains("匹配到 2 条流程"),
                "correlate 看到的候选数必须与 triggerMessage 一致（候选集分叉就是 bug），实际: "
                        + fromCorrelate);
        assertEquals(2, openTasks().size(), "两个入口都不得推进任何一条");

        // 限定到一条之后，两个入口都应当成功 —— 证明它们找的是同一条
        List<WfTask> before = openTasks();
        String pid = before.get(0).getProcessInstanceId();
        runtime.correlate(new WfMessageCorrelation("orderPaid").setProcessInstanceId(pid),
                "system", "限定");
        assertEquals(1, openTasks().size(), "correlate 限定后应当只推进一条");
    }

    // ==================== 夹具 ====================

    private Map<String, Object> vars(String key, Object value) {
        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put(key, value);
        return vars;
    }

    private List<WfTask> openTasks() {
        return repo.queryTasks(new WfTaskQuery().setOpenOnly(true).setPageNum(1).setPageSize(50));
    }

    private String openTasksPid(String businessKey) {
        for (WfTask task : openTasks()) {
            WfProcessInstance instance = repo.findProcessInstance(task.getProcessInstanceId());
            if (instance != null && businessKey.equals(instance.getBusinessKey())) {
                return instance.getId();
            }
        }
        throw new IllegalStateException("找不到业务键为 " + businessKey + " 的流程实例");
    }

    /** 停在某个节点上的 token id —— 执行级变量挂在它身上。 */
    private String tokenId(String processInstanceId, String activityId) {
        for (WfExecution execution : repo.findExecutionsByProcessInstance(processInstanceId)) {
            if (!execution.isEnded() && activityId.equals(execution.getActivityId())) {
                return execution.getId();
            }
        }
        throw new IllegalStateException(
                "流程 " + processInstanceId + " 上找不到停在 " + activityId + " 的 token");
    }

    private WfJob jobOf(String processInstanceId, WfJobType type) {
        for (WfJob job : repo.queryJobs(new com.zifang.z.wf.core.persistence.WfJobQuery()
                .setProcessInstanceId(processInstanceId).setType(type)
                .setPageNum(1).setPageSize(50))) {
            return job;
        }
        return null;
    }
}