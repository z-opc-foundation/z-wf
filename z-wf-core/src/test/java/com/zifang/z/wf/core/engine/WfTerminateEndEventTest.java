package com.zifang.z.wf.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfDefinitionValidator;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.definition.WfValidationIssue;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.expression.WfExpressionEvaluator;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.service.WfDelegateRegistry;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;

/**
 * 终止结束事件 {@code terminateEndEvent}（第 36 轮）。
 *
 * <p>它是「并行分支里一方成了，另一方就别做了」的唯一写法 ——
 * Camunda 7 原文：「A terminate event ends the complete scope it is raised in and all
 * contained inner scopes … on process instance level terminates the complete instance,
 * on subprocess level the current scope and all contained processes instances will be
 * terminated.」
 *
 * <p>此前它<b>不在</b> {@code WfXmlParser} 的元素表里 ⇒ 走未知元素路径 ⇒
 * 部署期报「不支持」—— 是可见拒绝不是静默丢弃，但真实的 Camunda 导出模型
 * 只要含一个它就部署不了。
 *
 * <p>本类盯六件错了都不报错的事：
 * <ol>
 *   <li><b>另一条分支的待办真的消失</b>。只结束 token 不作废待办的话，
 *       待办会永远挂在办理人列表里 —— 没人能办结它（token 已经没了），
 *       也没人知道它为什么在那儿。</li>
 *   <li><b>子流程里的终止不带走父流程</b>。Camunda 的语义是
 *       「子流程完成，父流程沿出线继续」，
 *       把当前这条 token 一起结束掉的话，症状是「子流程画了终止，
 *       后面主图的节点一个都没跑」。</li>
 *   <li><b>被撤掉的分支上的 job 一起撤</b>。不撤的话事件照样会投递到
 *       一条已经结束的 token 上，「流程早就终止了，之后每收一个定时器
 *       就报一次找不到 token」。</li>
 *   <li><b>实例状态是 COMPLETED 而不是 ACTIVE</b>。
 *       终止清理必须排在「判完成」之前，否则流程停在一个已被终止的实例上：
 *       状态是 ACTIVE、待办没了、没有 job 也不会再推进，永久停死。</li>
 *   <li><b>「普通结束 + 终止结束」并存是合法的</b> ——
 *       那正是它最典型的用法，不该被报成「结束点必须唯一」。</li>
 *   <li><b>终止要留痕</b>：终止在业务上通常看不出是谁触发的。</li>
 * </ol>
 */
class WfTerminateEndEventTest {

    private static final String NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /**
     * 进程级：两条并行分支，谁先办完谁说了算。
     *
     * <p>甲办完 → 正常结束（流程完成）；乙办完 → 终止结束事件（把整条一起收掉）。
     * 这是「两条路都可行，先办成的那条定了结论，另一条不必再等」的标准写法。
     */
    private static final String PROCESS_TERMINATE_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"processTerminate\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <parallelGateway id=\"pga\"/>\n"
            + "    <userTask id=\"win\" name=\"甲\" zifang:assignee=\"alice\"/>\n"
            + "    <userTask id=\"slow\" name=\"乙\" zifang:assignee=\"bob\"/>\n"
            + "    <endEvent id=\"eWin\"/>\n"
            + "    <terminateEndEvent id=\"eTerm\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"pga\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"pga\" targetRef=\"win\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"pga\" targetRef=\"slow\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"win\" targetRef=\"eWin\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"slow\" targetRef=\"eTerm\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /**
     * 子流程级：终止发生在内联子流程内部，父流程必须继续往下走。
     *
     * <p>这条是本类最要紧的判据。子流程里的终止结束的是<b>子流程这个作用域</b>，
     * 不是整个实例 —— 画它的人要的是「子流程里两条分支谁先成谁说了算，
     * 定下来之后主图照常往下走」。
     */
    private static final String SUBPROCESS_TERMINATE_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"subProcessTerminate\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <subProcess id=\"sp\" name=\"内联子流程\">\n"
            + "      <startEvent id=\"iStart\"/>\n"
            + "      <parallelGateway id=\"iFork\"/>\n"
            + "      <userTask id=\"iWin\" name=\"内层甲\" zifang:assignee=\"alice\"/>\n"
            + "      <userTask id=\"iSlow\" name=\"内层乙\" zifang:assignee=\"bob\"/>\n"
            + "      <endEvent id=\"iEnd\"/>\n"
            + "      <terminateEndEvent id=\"iTerm\"/>\n"
            + "      <sequenceFlow id=\"if1\" sourceRef=\"iStart\" targetRef=\"iFork\"/>\n"
            + "      <sequenceFlow id=\"if2\" sourceRef=\"iFork\" targetRef=\"iWin\"/>\n"
            + "      <sequenceFlow id=\"if3\" sourceRef=\"iFork\" targetRef=\"iSlow\"/>\n"
            + "      <sequenceFlow id=\"if4\" sourceRef=\"iWin\" targetRef=\"iEnd\"/>\n"
            + "      <sequenceFlow id=\"if5\" sourceRef=\"iSlow\" targetRef=\"iTerm\"/>\n"
            + "    </subProcess>\n"
            + "    <userTask id=\"after\" name=\"子流程之后\" zifang:assignee=\"carol\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"sp\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"sp\" targetRef=\"after\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"after\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 子流程里只有终止结束事件、没有正常结束节点 —— 合法（进入即可能被终止）。 */
    private static final String ONLY_TERMINATE_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"onlyTerminate\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <subProcess id=\"sp\" name=\"只有终止出口\">\n"
            + "      <startEvent id=\"iStart\"/>\n"
            + "      <userTask id=\"iTask\" name=\"内层\" zifang:assignee=\"alice\"/>\n"
            + "      <terminateEndEvent id=\"iTerm\"/>\n"
            + "      <sequenceFlow id=\"if1\" sourceRef=\"iStart\" targetRef=\"iTask\"/>\n"
            + "      <sequenceFlow id=\"if2\" sourceRef=\"iTask\" targetRef=\"iTerm\"/>\n"
            + "    </subProcess>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"sp\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"sp\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 甲那条支路上挂一个 3 天超时边界，验证终止会把它一并撤掉。 */
    private static final String TERMINATE_WITH_TIMER_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"terminateWithTimer\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <parallelGateway id=\"pga\"/>\n"
            + "    <userTask id=\"win\" name=\"甲\" zifang:assignee=\"alice\"/>\n"
            + "    <userTask id=\"slow\" name=\"乙\" zifang:assignee=\"bob\"/>\n"
            + "    <boundaryEvent id=\"bTimer\" attachedToRef=\"win\">\n"
            + "      <timerEventDefinition><timeDuration>P3D</timeDuration></timerEventDefinition>\n"
            + "    </boundaryEvent>\n"
            + "    <userTask id=\"late\" name=\"超时补救\" zifang:assignee=\"carol\"/>\n"
            + "    <endEvent id=\"eWin\"/>\n"
            + "    <terminateEndEvent id=\"eTerm\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"pga\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"pga\" targetRef=\"win\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"pga\" targetRef=\"slow\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"win\" targetRef=\"eWin\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"slow\" targetRef=\"eTerm\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"bTimer\" targetRef=\"late\"/>\n"
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
        WfEngine engine = new WfEngine(new WfBehaviorRegistry(),
                new WfExpressionEvaluator(),
                new WfIdGenerator.DefaultWfIdGenerator(), new WfDelegateRegistry());
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
        WfTaskQuery query = new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(Integer.MAX_VALUE);
        List<WfTask> all = new ArrayList<>(repo.queryTasks(query));
        return all;
    }

    private WfTask taskOf(String pid, String definitionId) {
        for (WfTask task : openTasks(pid)) {
            if (definitionId.equals(task.getDefinitionId())) {
                return task;
            }
        }
        return null;
    }

    /**
     * 办结一个待办，**用该任务自己的办理人身份**。
     *
     * <p>刻意不写死 alice：这几条用例里被办掉的是「乙」的单子（bob），
     * 写死 alice 会让它们以「无权以 alice 的身份办结」失败 ——
     * 那种失败与被测的终止行为毫无关系，红了也说明不了任何问题。
     */
    private void complete(WfTask task) {
        runtime.completeTask(task.getId(), task.getAssignee(), "办结",
                new HashMap<String, Object>());
    }

    // ==================== 解析 ====================

    @Test
    @DisplayName("terminateEndEvent 进真实类型表，不是「归一到任务再报错」")
    void terminateEndEventIsARealNodeType() {
        WfDefinition definition = deploy(PROCESS_TERMINATE_BPMN);
        assertNotNull(definition.node("eTerm"), "解析期应当认出 terminateEndEvent");
        assertEquals(WfNodeType.TERMINATE_END_EVENT, definition.node("eTerm").getType(),
                "它是独立类型而不是 END_EVENT 上的标志 —— "
                        + "做成标志的话，任何一处 type == END_EVENT 都会把它当普通结束事件");
        assertTrue(definition.node("eTerm").unsupportedBpmnElement() == null,
                "它不能被打上「不支持的元素」标记，否则部署期照样挡住");
    }

    // ==================== 进程级 ====================

    @Test
    @DisplayName("进程级终止：另一条分支的待办消失、token 结束、实例 COMPLETED")
    void processScopeTerminateEndsEverything() {
        WfDefinition definition = deploy(PROCESS_TERMINATE_BPMN);
        String pid = start(definition);
        assertNotNull(taskOf(pid, "win"), "甲先有单子");
        assertNotNull(taskOf(pid, "slow"), "乙先有单子");

        complete(taskOf(pid, "slow"));

        assertEquals(null, taskOf(pid, "win"),
                "乙走到终止结束事件时，甲的待办必须同时作废 —— "
                        + "只结束 token 不作废待办的话，它会永远挂在 alice 的列表里，"
                        + "而 nobody 能办结它（token 没了）");
        assertTrue(openTasks(pid).isEmpty(), "终止之后不该还剩任何未办结的待办，实际: "
                + openTasks(pid));
        WfProcessInstance instance = repo.findProcessInstance(pid);
        assertEquals(WfProcessStatus.COMPLETED, instance.getStatus(),
                "终止是作用域的正常完成，不是失败 —— "
                        + "判成 INTERNALLY_TERMINATED 会让「谁把它收掉的」在轨迹上变成一个错误");
        for (WfExecution execution : repo.findExecutionsByProcessInstance(pid)) {
            assertEquals(WfExecution.State.ENDED, execution.getState(),
                    "终止后不该还有存活的 token: " + execution.getActivityId());
        }
    }

    @Test
    @DisplayName("终止要留痕：业务上通常看不出是谁触发的")
    void terminateLeavesATrace() {
        WfDefinition definition = deploy(PROCESS_TERMINATE_BPMN);
        String pid = start(definition);
        complete(taskOf(pid, "slow"));

        StringBuilder contents = new StringBuilder();
        repo.findComments(pid).forEach(c -> contents.append(c.getContent()).append("\n"));
        assertTrue(contents.toString().contains("terminateEndEvent"),
                "终止必须留一条评论 —— 「谁在什么时候把哪一片一起收掉了」"
                        + "是排障时第一个要问的问题，而终止在业务上通常看不出是谁触发的，"
                        + "实际评论: " + contents);
    }

    // ==================== 子流程级 ====================

    @Test
    @DisplayName("子流程里的终止只收子流程，主流程照常往下走")
    void subprocessTerminateDoesNotKillTheParent() {
        WfDefinition definition = deploy(SUBPROCESS_TERMINATE_BPMN);
        String pid = start(definition);
        assertNotNull(taskOf(pid, "iWin"), "内层甲先有单子");
        assertNotNull(taskOf(pid, "iSlow"), "内层乙先有单子");

        complete(taskOf(pid, "iSlow"));

        assertEquals(null, taskOf(pid, "iWin"),
                "内层乙走到终止结束事件时，内层甲的待办必须作废");
        // 这条是本类最要紧的判据：终止结束的是**子流程这个作用域**，
        // 子流程完成后父流程要沿子流程的出线继续走。
        // 把当前这条 token 一起结束掉的话，carol 永远收不到单子，
        // 而实例状态看起来是正常的（没有异常、没有失败原因）。
        WfTask after = taskOf(pid, "after");
        assertNotNull(after, "子流程完成之后，主流程必须继续走到「子流程之后」这个节点，"
                + "实际未办结的待办: " + openTasks(pid));
        assertEquals("carol", after.getAssignee(), "后续节点按图上的配置派给 carol");

        complete(after);
        assertEquals(WfProcessStatus.COMPLETED, repo.findProcessInstance(pid).getStatus(),
                "一路走完之后实例正常完成");
    }

    @Test
    @DisplayName("子流程里「普通结束 + 终止结束」并存是合法的（不得被报成结束点不唯一）")
    void normalAndTerminateEndCoexist() {
        // 这正是终止结束事件最典型的用法：两条分支谁先成谁说了算。
        // 若 inlineExitIds 把终止事件也算成「结束点」，这里会被报成
        // 「子流程的结束点必须唯一」—— 把最典型的用法判成非法。
        WfDefinition definition = deploy(SUBPROCESS_TERMINATE_BPMN);
        List<WfValidationIssue> issues = new WfDefinitionValidator().validate(definition);
        for (WfValidationIssue issue : issues) {
            assertTrue(!issue.getMessage().contains("结束点必须唯一"),
                    "普通 endEvent 与 terminateEndEvent 并存应当合法，"
                            + "实际报错: " + issue.getMessage());
        }
    }

    @Test
    @DisplayName("子流程里只有终止结束事件也算合法（有出口）")
    void onlyTerminateEndIsAnExit() {
        WfDefinition definition = deploy(ONLY_TERMINATE_BPMN);
        for (WfValidationIssue issue : new WfDefinitionValidator().validate(definition)) {
            assertTrue(issue.getSeverity() != WfValidationIssue.Severity.ERROR
                            || !issue.getMessage().contains("没有任何内联结束节点"),
                    "只有 terminateEndEvent 的子流程是有出口的，不能被报成内部成环，"
                            + "实际报错: " + issue.getMessage());
        }
        String pid = start(definition);
        complete(taskOf(pid, "iTask"));
        assertEquals(WfProcessStatus.COMPLETED, repo.findProcessInstance(pid).getStatus(),
                "内层办完后到达终止结束事件，子流程完成，主流程照常走完");
    }

    @Test
    @DisplayName("被终止分支上的定时器要一起撤掉（否则事件会投递到已结束的 token 上）")
    void terminateClearsJobsOfTheOtherBranch() {
        // 甲那条支路上挂一个 3 天的超时边界定时器。乙终止之后：
        // 不撤的话，3 天后这个 job 照样到期并被扫描到，
        // 而它要投递的宿主 token 早就 ENDED 了 ——
        // 症状是「流程终止三天后开始持续报错，报的是找不到那条分支」。
        WfDefinition definition = deploy(TERMINATE_WITH_TIMER_BPMN);
        String pid = start(definition);
        assertEquals(1, countJobs(pid), "甲的待办应当挂着一个超时定时器");

        complete(taskOf(pid, "slow"));

        assertEquals(0, countJobs(pid),
                "终止必须把被收掉那条支路上的 job 一并撤掉 —— "
                        + "留着的话它到期时会被投递到一条已经 ENDED 的 token 上，"
                        + "而流程早就结束了，没人看得懂那是什么错");
    }

    private int countJobs(String pid) {
        return repo.queryJobs(new com.zifang.z.wf.core.persistence.WfJobQuery()
                .setProcessInstanceId(pid).setPageNum(1).setPageSize(Integer.MAX_VALUE)).size();
    }

    // ==================== 部署期挡住 ====================
    @Test
    @DisplayName("terminateEndEvent 有出线 ⇒ 部署期报错")
    void terminateWithOutgoingFlowIsRejected() {
        String xml = "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
                + " targetNamespace=\"x\">\n"
                + "  <process id=\"badTerminate\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                + "    <terminateEndEvent id=\"eTerm\"/>\n"
                + "    <userTask id=\"after\" name=\"不该走到\" zifang:assignee=\"alice\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"eTerm\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"eTerm\" targetRef=\"after\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        WfDefinition definition = new WfXmlParser().parse(xml);
        List<WfValidationIssue> errors = errorsOf(definition);
        boolean found = false;
        for (WfValidationIssue issue : errors) {
            if (issue.getMessage().contains("不能有出线")) {
                found = true;
            }
        }
        assertTrue(found, "terminateEndEvent 接了出线必须报错 —— 终止不可撤销，"
                + "另一条分支上的待办那时已经被收掉了，而出线永远走不到，"
                + "流程会卡在半路且不报错。实际报错: " + errors);
    }

    @Test
    @DisplayName("terminateEndEvent 上写 asyncAfter ⇒ 部署期报错（它不走 leave）")
    void asyncAfterOnTerminateIsRejected() {
        String xml = "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"asyncTerminate\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <terminateEndEvent id=\"eTerm\" zifang:asyncAfter=\"true\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"eTerm\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";
        List<WfValidationIssue> errors = errorsOf(new WfXmlParser().parse(xml));
        boolean found = false;
        for (WfValidationIssue issue : errors) {
            if (issue.getMessage().contains("asyncAfter")) {
                found = true;
            }
        }
        assertTrue(found,
                "终止结束事件不走 leave，asyncAfter 挂不出来。写它的人以为"
                        + "「终止前再排一次队」，实际那行配置毫无作用。实际报错: " + errors);
    }

    private List<WfValidationIssue> errorsOf(WfDefinition definition) {
        List<WfValidationIssue> errors = new ArrayList<>();
        for (WfValidationIssue issue : new WfDefinitionValidator().validate(definition)) {
            if (issue.getSeverity() == WfValidationIssue.Severity.ERROR) {
                errors.add(issue);
            }
        }
        return errors;
    }
}