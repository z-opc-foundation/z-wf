package com.zifang.z.wf.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.delegate.WfJavaDelegate;
import com.zifang.z.wf.core.engine.expression.WfExpressionEvaluator;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfCompensationEntry;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.service.WfDelegateRegistry;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;

/**
 * 补偿机制的运行期（第 37 轮）—— 登记、逆序执行、以及"绝不能静默不发生"。
 *
 * <p>补偿与本仓其它能力的根本差别是：<b>它不做的时候什么都不会发生</b>。
 * 少了登记，是一次普通流程；少了处理器，是一次普通流程；少了逆序，是一次普通流程 ——
 * 全都「跑通了」，轨迹正常、实例 COMPLETED、没有任何异常。
 * 业务上要等到「该退的款没退」才发现，那时已经没人说得清是哪一步没生效。
 *
 * <p>所以本类的判据盯的都是「本该发生的事发生了没有」，而不是「流程能不能跑完」。
 *
 * <p><b>本轮的实现边界</b>（与 Camunda 的差异写在
 * {@code docs/capability-gap.md}，不是这里）：
 * <ul>
 *   <li>触发点只有<b>作用域终止</b>。Camunda 的 {@code cancelEndEvent} 与
 *       {@code transaction} 也走同一条补偿路径，那两个元素在第 38 轮补，
 *       届时只加触发点，本类的判据原样适用。</li>
 *   <li>补偿处理器限<b>能同步跑完的活动</b>（serviceTask / scriptTask /
 *       businessRuleTask）。人工补偿任务需要「挂起实例等人办结再恢复」的状态机，
 *       本轮没有 —— 部署期直接报错，而不是放行一个补偿到一半就卡住的定义。</li>
 * </ul>
 */
class WfCompensationTest {

    private static final String NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /** 执行过的 delegate 名字，按发生顺序记录 —— 逆序补偿就靠它断言。 */
    private List<String> calls;

    /** 一个把名字记进 calls 的 delegate。 */
    public static class RecordingDelegate implements WfJavaDelegate {
        private final List<String> sink;

        public RecordingDelegate(List<String> sink) {
            this.sink = sink;
        }

        @Override
        public void execute(WfContext context, com.zifang.z.wf.core.model.WfExecution execution) {
            sink.add(execution.getActivityId());
        }
    }

    /**
     * 并行两条支路各做一步，然后任何一条走到终止结束事件就整条撤销。
     *
     * <p>两条支路都挂各自的补偿边界事件、各自关联到退单处理器 ——
     * 这样「逆序」才验得到：甲先完成、乙后完成，撤销时必须**先退乙再退甲**。
     */
    private static final String COMPENSATE_TWO_BRANCHES_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"twoBranches\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <parallelGateway id=\"pga\"/>\n"
            + "    <userTask id=\"win\" name=\"甲\" zifang:assignee=\"alice\">\n"
            + "      <boundaryEvent id=\"beA\" attachedToRef=\"win\">\n"
            + "        <compensateEventDefinition/>\n"
            + "      </boundaryEvent>\n"
            + "    </userTask>\n"
            + "    <userTask id=\"slow\" name=\"乙\" zifang:assignee=\"bob\">\n"
            + "      <boundaryEvent id=\"beB\" attachedToRef=\"slow\">\n"
            + "        <compensateEventDefinition/>\n"
            + "      </boundaryEvent>\n"
            + "    </userTask>\n"
            + "    <endEvent id=\"eWin\"/>\n"
            + "    <terminateEndEvent id=\"eTerm\"/>\n"
            + "    <serviceTask id=\"undoA\" name=\"退甲\""
            + " isForCompensation=\"true\" zifang:delegateExpression=\"record\"/>\n"
            + "    <serviceTask id=\"undoB\" name=\"退乙\""
            + " isForCompensation=\"true\" zifang:delegateExpression=\"record\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"pga\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"pga\" targetRef=\"win\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"pga\" targetRef=\"slow\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"win\" targetRef=\"eWin\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"slow\" targetRef=\"eTerm\"/>\n"
            + "    <association id=\"aA\" sourceRef=\"beA\" targetRef=\"undoA\"/>\n"
            + "    <association id=\"aB\" sourceRef=\"beB\" targetRef=\"undoB\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /**
     * 单条支路：订票 → <b>正常结束</b>。只用来验「登记了但还没退」。
     *
     * <p>刻意<b>不</b>接终止结束事件：接了的话 {@code complete(win)} 之后流程
     * 会立刻终止并触发补偿，登记当场变成 done ——
     * 「登记时还没退过」这句话就永远测不到，只能靠一个会自我否证的断言。
     *
     * <p>写成字面量而不是从双支路版本 {@code replace} 出来：
     * 那种拼法一旦某一行对不上就会<b>静默</b>少删一段，得到的图与意图不同，
     * 而报错要等到部署期才出现，指向的还是一个你没写过的节点 id。
     */
    private static final String COMPENSATE_ONE_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"oneBranch\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"win\" name=\"甲\" zifang:assignee=\"alice\">\n"
            + "      <boundaryEvent id=\"beA\" attachedToRef=\"win\">\n"
            + "        <compensateEventDefinition/>\n"
            + "      </boundaryEvent>\n"
            + "    </userTask>\n"
            + "    <endEvent id=\"eEnd\"/>\n"
            + "    <serviceTask id=\"undoA\" name=\"退甲\""
            + " isForCompensation=\"true\" zifang:delegateExpression=\"record\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"win\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"win\" targetRef=\"eEnd\"/>\n"
            + "    <association id=\"aA\" sourceRef=\"beA\" targetRef=\"undoA\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 活动完成但**不挂**补偿边界事件：它不该出现在补偿登记表里。 */
    private static final String NO_COMPENSATION_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"noCompensation\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"win\" name=\"甲\" zifang:assignee=\"alice\"/>\n"
            + "    <terminateEndEvent id=\"eTerm\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"win\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"win\" targetRef=\"eTerm\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 补偿处理器是人工任务：本轮不支持，必须在部署期挡住。 */
    private static final String HUMAN_HANDLER_BPMN = COMPENSATE_ONE_BPMN
            .replace("<serviceTask id=\"undoA\" name=\"退甲\""
                    + " isForCompensation=\"true\" zifang:delegateExpression=\"record\"/>",
                    "<userTask id=\"undoA\" name=\"人工退单\""
                            + " isForCompensation=\"true\" zifang:assignee=\"carol\"/>")
            .replace("id=\"oneBranch\"", "id=\"humanHandler\"");

    /** 两条支路都完成后汇到一个网关，再由终止结束事件整条撤销。 */
    private static final String BOTH_DONE_BPMN =
            COMPENSATE_TWO_BRANCHES_BPMN
                    .replace("    <endEvent id=\"eWin\"/>\n",
                            "    <parallelGateway id=\"merge\"/>\n")
                    .replace("    <sequenceFlow id=\"f4\" sourceRef=\"win\" targetRef=\"eWin\"/>\n",
                            "    <sequenceFlow id=\"f4\" sourceRef=\"win\" targetRef=\"merge\"/>\n")
                    .replace("    <sequenceFlow id=\"f5\" sourceRef=\"slow\" targetRef=\"eTerm\"/>\n",
                            "    <sequenceFlow id=\"f5\" sourceRef=\"slow\" targetRef=\"merge\"/>\n"
                            + "    <sequenceFlow id=\"f6\" sourceRef=\"merge\" targetRef=\"eTerm\"/>\n")
                    .replace("id=\"twoBranches\"", "id=\"bothDone\"");

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;

    @BeforeEach
    void setUp() {
        calls = new ArrayList<>();
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        WfDelegateRegistry delegates = new WfDelegateRegistry();
        delegates.register("record", new RecordingDelegate(calls));
        WfEngine engine = new WfEngine(new WfBehaviorRegistry(),
                new WfExpressionEvaluator(), new WfIdGenerator.DefaultWfIdGenerator(), delegates);
        runtime = new WfRuntimeService(repository, repo, engine, new WfHookDispatcher());
    }

    private WfDefinition deploy(String xml) {
        return repository.deploy(new WfXmlParser().parse(xml));
    }

    private String start(WfDefinition definition) {
        return runtime.startProcessInstance(definition,
                "BIZ-" + System.nanoTime(), "alice", null, new HashMap<String, Object>());
    }

    private List<WfTask> openTasks(String pid) {
        return new ArrayList<>(repo.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(pid).setOpenOnly(true)
                .setPageNum(1).setPageSize(Integer.MAX_VALUE)));
    }

    private WfTask taskOf(String pid, String definitionId) {
        for (WfTask task : openTasks(pid)) {
            if (definitionId.equals(task.getDefinitionId())) {
                return task;
            }
        }
        return null;
    }

    private void complete(WfTask task) {
        runtime.completeTask(task.getId(), task.getAssignee(), "办结",
                new HashMap<String, Object>());
    }

    private List<WfCompensationEntry> registrations(String pid) {
        return repo.findCompensations(pid);
    }

    // ==================== 登记 ====================

    @Test
    @DisplayName("活动完成时登记一条补偿（时机是「做完」不是「打算做」）")
    void completingACompensableActivityRegistersIt() {
        WfDefinition definition = deploy(COMPENSATE_ONE_BPMN);
        String pid = start(definition);
        assertEquals(0, registrations(pid).size(), "刚启动时什么都没做，不该有补偿登记");

        complete(taskOf(pid, "win"));

        List<WfCompensationEntry> entries = registrations(pid);
        assertEquals(1, entries.size(),
                "订票这一步做完了，将来撤销时要能退它 —— 少这一行就是「撤销时什么都不做」");
        assertEquals("win", entries.get(0).getActivityId());
        assertEquals("", entries.get(0).getScope(), "主图上的活动属于进程级作用域");
        assertTrue(!entries.get(0).isDone(), "登记时还没退过");
    }

    @Test
    @DisplayName("不可补偿的活动不登记（否则撤销时会退一堆没挂边界的活动）")
    void activityWithoutCompensationBoundaryIsNotRegistered() {
        WfDefinition definition = deploy(NO_COMPENSATION_BPMN);
        String pid = start(definition);

        complete(taskOf(pid, "win"));

        assertEquals(0, registrations(pid).size(),
                "没有补偿边界事件 = 没有说它可退，登记它等于撤销时去执行一个"
                        + "图上根本没画的处理器");
    }

    @Test
    @DisplayName("补偿处理器自己不登记（否则退一次就把 handler 变成新的可补偿项）")
    void compensationHandlerIsNotRegisteredItself() {
        // 这是会无限循环的那种错：补偿执行 handler → handler 完成 → 又登记 →
        // 下次补偿再触发 handler。每一轮都真的执行了一次退款。
        WfDefinition definition = deploy(BOTH_DONE_BPMN);
        String pid = start(definition);
        complete(taskOf(pid, "win"));
        complete(taskOf(pid, "slow"));

        List<String> ids = new java.util.ArrayList<>();
        for (WfCompensationEntry entry : registrations(pid)) {
            ids.add(entry.getActivityId());
        }
        assertEquals(2, calls.size(), "前置条件：两次补偿都跑过了，实际调用: " + calls);
        assertEquals(2, ids.size(), "前置条件：两条登记。实际: " + ids);
        assertTrue(!ids.contains("undoA") && !ids.contains("undoB"),
                "补偿处理器不得出现在补偿登记表里 —— 它是「退的方法」，不是「退什么」。"
                        + "一旦被登记，下次撤销会再触发它，退款就会重复发生。"
                        + "实际登记: " + ids);
    }

    // ==================== 执行 ====================

    @Test
    @DisplayName("终止时先退后撤：补偿处理器真的被调用了")
    void terminationRunsTheCompensationHandler() {
        WfDefinition definition = deploy(COMPENSATE_TWO_BRANCHES_BPMN);
        String pid = start(definition);
        complete(taskOf(pid, "win"));
        assertEquals(1, registrations(pid).size(), "前置条件：甲做完就登记");
        assertEquals(0, calls.size(), "登记本身不执行任何东西");

        complete(taskOf(pid, "slow"));

        // **两次** —— 这是我一开始写错的地方，值得记下来：
        // 走到 terminateEndEvent 的那条 token 自己也算「已完成」。
        // 终止结束事件的语义是「整个作用域结束」，作用域里包含乙自己，
        // 所以乙做完的那一步同样要退。只退甲等于把这个作用域留在半退的状态。
        assertEquals(2, calls.size(),
                "撤销退的是整个作用域：甲、乙两步都要退。实际调用记录: " + calls);
        assertEquals("undoB", calls.get(0),
                "后做的先撤 —— 乙后完成。实际调用顺序: " + calls);
        assertEquals("undoA", calls.get(1), "甲先做的后撤。实际调用顺序: " + calls);
    }

    @Test
    @DisplayName("逆序执行：后做的先撤")
    void compensationRunsInReverseOrder() {
        WfDefinition definition = deploy(BOTH_DONE_BPMN);
        String pid = start(definition);

        complete(taskOf(pid, "win"));
        complete(taskOf(pid, "slow"));

        List<String> registered = new java.util.ArrayList<>();
        for (WfCompensationEntry entry : registrations(pid)) {
            registered.add(entry.getActivityId());
        }
        assertEquals(2, registered.size(), "两步都做完了，两条登记都在。实际: " + registered);
        assertEquals("win", registered.get(0), "先完成的在前（seq 升序）");
        assertEquals("slow", registered.get(1), "后完成的在后");

        // 并行汇合在 slow 完成时自动发生 → 一路走到 eTerm → 撤销。
        // 刻意不手动推 merge：它是网关不是任务，测它推不动 —— 而这正是
        // 「判据的每一步都得是真的能被推进的那一步」的自我约束。
        assertEquals(2, calls.size(),
                "两步都要退。实际调用记录: " + calls);
        assertEquals("undoB", calls.get(0),
                "**后做的先撤** —— 乙后完成，必须先退乙。"
                        + "顺序反了会先退甲，而甲的退款往往要靠乙那张单才找得到。"
                        + "实际调用顺序: " + calls);
        assertEquals("undoA", calls.get(1), "甲后撤。实际调用顺序: " + calls);
    }

    @Test
    @DisplayName("退过的登记会被标记 done，不会被退第二遍")
    void compensationIsMarkedDone() {
        WfDefinition definition = deploy(BOTH_DONE_BPMN);
        String pid = start(definition);
        complete(taskOf(pid, "win"));
        complete(taskOf(pid, "slow"));
        assertEquals(2, calls.size(), "前置条件：退过两次");

        List<WfCompensationEntry> entries = registrations(pid);
        for (WfCompensationEntry entry : entries) {
            assertTrue(entry.isDone(),
                    "退过的登记要标记 done —— 真实场景里同一个作用域可能被撤销两次"
                            + "（先撤内层事务、再撤外层），不标记就会退两遍，也就是退两次款");
            assertNotNull(entry.getCompensatedAt(), "要留下退的时间");
        }
    }

    @Test
    @DisplayName("补偿要留痕：退过的那一步在轨迹上看得见")
    void compensationIsRecordedInHistory() {
        WfDefinition definition = deploy(BOTH_DONE_BPMN);
        String pid = start(definition);
        complete(taskOf(pid, "win"));
        complete(taskOf(pid, "slow"));

        boolean seen = false;
        for (com.zifang.z.wf.core.model.WfActivityInstance activity
                : repo.findActivityInstances(pid)) {
            if ("undoA".equals(activity.getActivityId())) {
                seen = true;
            }
        }
        assertTrue(seen,
                "补偿处理器跑过就该在轨迹上留一条 —— 排障时第一个要回答的问题是"
                        + "「这一步到底退过没有」");
    }

    @Test
    @DisplayName("撤销之后实例仍然是 COMPLETED（补偿不改变终止的结论）")
    void compensationDoesNotChangeTerminationOutcome() {
        WfDefinition definition = deploy(BOTH_DONE_BPMN);
        String pid = start(definition);
        complete(taskOf(pid, "win"));
        complete(taskOf(pid, "slow"));

        WfProcessInstance instance = repo.findProcessInstance(pid);
        assertEquals(WfProcessStatus.COMPLETED, instance.getStatus(),
                "终止的结论是 COMPLETED，不是失败 —— 补偿是正常收尾的一部分，"
                        + "判成 INTERNALLY_TERMINATED 会让「谁把它收掉的」在轨迹上变成一个错误");
        assertEquals(2, calls.size(), "补偿照做");
    }

    @Test
    @DisplayName("补偿留下的 token 已结束，不会把实例挂在 ACTIVE 上")
    void compensationTokensAreEnded() {
        WfDefinition definition = deploy(BOTH_DONE_BPMN);
        String pid = start(definition);
        complete(taskOf(pid, "win"));
        complete(taskOf(pid, "slow"));

        for (com.zifang.z.wf.core.model.WfExecution execution
                : repo.findExecutionsByProcessInstance(pid)) {
            assertTrue(execution.isEnded(),
                    "补偿用的临时 token 跑完必须自己结束，否则它会一直挂在库里，"
                            + "而 resolveCompletion 会永远判不出完成 —— 活动: "
                            + execution.getActivityId());
        }
    }

    @Test
    @DisplayName("没有登记过的活动不触发任何 handler")
    void terminationWithoutRegistrationRunsNothing() {
        WfDefinition definition = deploy(NO_COMPENSATION_BPMN);
        String pid = start(definition);
        complete(taskOf(pid, "win"));

        assertEquals(0, calls.size(),
                "图上没有任何补偿配置，撤销时不该有任何外部动作被调用。实际: " + calls);
        assertNull(taskOf(pid, "win"), "待办照常作废");
    }

    // ==================== 部署期挡住 ====================

    @Test
    @DisplayName("人工补偿处理器 ⇒ 部署期报错（补偿到一半会卡住流程）")
    void humanCompensationHandlerIsRejected() {
        // 本轮只支持能同步跑完的活动。放行人工补偿的话，
        // 补偿会建出一条待办然后流程停在那儿等人 —— 而撤销的语义是
        // 「等退完了再继续」，没有那个挂起/恢复的状态机就只会停死。
        try {
            deploy(HUMAN_HANDLER_BPMN);
            assertTrue(false,
                    "人工补偿处理器必须被部署期挡住：放行的话撤销会建出一条待办，"
                            + "而本引擎当前没有「实例挂起等人办结补偿后再恢复」的状态机，"
                            + "流程会永久停在那一条待办上");
        } catch (com.zifang.z.wf.core.definition.WfDefinitionException expected) {
            assertTrue(expected.getMessage().contains("isForCompensation")
                            || expected.getMessage().contains("可执行的活动"),
                    "报错要说清是补偿处理器的问题。实际: " + expected.getMessage());
        }
    }
}
