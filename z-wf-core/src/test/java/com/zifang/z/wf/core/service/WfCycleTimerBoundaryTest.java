package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfDefinitionValidator;
import com.zifang.z.wf.core.definition.WfTimerSupport;
import com.zifang.z.wf.core.definition.WfTimerType;
import com.zifang.z.wf.core.definition.WfValidationIssue;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 循环定时器（{@code timeCycle}）—— 每隔一段时间提醒一次，直到宿主办完。
 *
 * <p>这是审批系统里最常被问的一件事（"这张单子卡了 2 小时了，催一下"），
 * 此前部署期报 ERROR。它与非中断型边界事件是一对：<b>只有非中断型才有"下一周期"可提醒</b> ——
 * 中断型第一次响就把宿主 token 搬走了。
 *
 * <p>本类盯四件容易做错的事：
 * <ol>
 *   <li><b>次数要真的数对</b>。{@code R3/PT1H} 响 3 次，第 4 次不再响 ——
 *       少响一次提醒不来，多响一次就是"没完没了"。</li>
 *   <li><b>下一时刻是"上一次 + 周期"</b>，不是"锚点 + (n+1) × 周期"。
 *       两者在理想情况下相同，差别只在锚点存不存在。</li>
 *   <li><b>宿主办完之后就不再响</b>。响过的 job 还在库里是可以接受的，
 *       但不该再冒出新的。</li>
 *   <li><b>格式错要在部署期报</b>。等到第一次触发才发现，
 *       意味着这个提醒从头到尾一次都没响过，而没人会知道它本该响。</li>
 * </ol>
 */
class WfCycleTimerBoundaryTest {

    private static final String BPMN_TEMPLATE =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"%s\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <boundaryEvent id=\"urgeBoundary\" attachedToRef=\"approve\"%s>\n"
            + "      <timerEventDefinition>\n"
            + "        <timeCycle>%s</timeCycle>\n"
            + "      </timerEventDefinition>\n"
            + "    </boundaryEvent>\n"
            + "    <userTask id=\"urge\" name=\"催办\" zifang:assignee=\"ops\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"urgeBoundary\" targetRef=\"urge\"/>\n"
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

    private String bpmn(String processId, String boundaryAttrs, String cycle) {
        return String.format(BPMN_TEMPLATE, processId, boundaryAttrs, cycle);
    }

    /** 默认夹具：非中断型 + R3/PT1H。 */
    private String startCycle(String cycle) {
        return startCycle(cycle, " cancelActivity=\"false\"");
    }

    private String startCycle(String cycle, String boundaryAttrs) {
        WfDefinition definition = repository.deployXml(
                bpmn("cycleProcess" + Math.abs(cycle.hashCode()) + boundaryAttrs.hashCode(),
                        boundaryAttrs, cycle),
                "cycleProcess" + Math.abs(cycle.hashCode()) + boundaryAttrs.hashCode());
        return runtime.startProcessInstance(definition, "CYC-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
    }

    private List<WfJob> timerJobs(String pid) {
        return repo.queryJobs(new WfJobQuery().setType(WfJobType.TIMER)
                .setProcessInstanceId(pid).setPageNum(1).setPageSize(20));
    }

    private List<WfTask> openAt(String pid, String nodeId) {
        List<WfTask> all = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(20));
        List<WfTask> result = new ArrayList<>();
        for (WfTask t : all) {
            if (t.isOpen() && nodeId.equals(t.getDefinitionId())) {
                result.add(t);
            }
        }
        return result;
    }

    private void complete(WfTask task) {
        runtime.completeTask(task.getId(), task.getAssignee(), "办完", new HashMap<>());
    }

    /** 让当前所有 job 都到期。 */
    private int fireAll(String pid) {
        return new WfJobService(repo, runtime)
                .executeDueJobs(new Date(System.currentTimeMillis() + 86_400_000L));
    }

    // ==================== 表达式解析 ====================

    @Test
    @DisplayName("R3/PT1H 解析出周期 1 小时、最多 3 次")
    void repeatedCountIsParsed() {
        WfTimerSupport.Cycle cycle = WfTimerSupport.parseCycle("R3/PT1H");
        assertEquals(3600_000L, cycle.getPeriodMillis());
        assertEquals(3, cycle.getMaxFires());
        assertTrue(!cycle.isUnbounded());
    }

    @Test
    @DisplayName("R/PT10M 是无界的，交给引擎的硬上限兜底")
    void unboundedFormIsParsed() {
        WfTimerSupport.Cycle cycle = WfTimerSupport.parseCycle("R/PT10M");
        assertEquals(600_000L, cycle.getPeriodMillis());
        assertTrue(cycle.isUnbounded());
    }

    @Test
    @DisplayName("P1D/T1H 换算成 24 次（总时长除以周期）")
    void durationBoundBecomesCount() {
        WfTimerSupport.Cycle cycle = WfTimerSupport.parseCycle("P1D/T1H");
        assertEquals(3600_000L, cycle.getPeriodMillis());
        assertEquals(24, cycle.getMaxFires(), "1 天 / 1 小时 = 24 次");
    }

    @Test
    @DisplayName("显式起始时刻：锚点用它而不是「进入节点的那一刻」")
    void explicitStartIsUsedAsAnchor() {
        WfTimerSupport.Cycle cycle = WfTimerSupport.parseCycle("2026-01-01T09:00:00Z/P1D/T1H");
        assertNotNull(cycle.getStart(), "写了起始时刻就该被认出来");
        assertEquals(24, cycle.getMaxFires(),
                "起始时刻 + P1D/T1H = 一天 24 次。只认出起始时刻而丢掉后两段的话，"
                        + "这里会是 1 —— 也就是「每次触发时都要有人盯着」");
    }

    @Test
    @DisplayName("首次触发 = 锚点 + 一个周期（不是锚点本身）")
    void firstFireIsOnePeriodAfterTheAnchor() {
        Date base = new Date(1_700_000_000_000L);
        Date due = WfTimerSupport.resolveDueDate(WfTimerType.CYCLE, "R3/PT1H", base, null);
        assertEquals(base.getTime() + 3600_000L, due.getTime(),
                "R3/PT1H 的第一次在 1 小时后 —— 若把锚点当第一次，"
                        + "它会在刚进入节点的瞬间就响，等于没有等待");
    }

    @Test
    @DisplayName("响满之后没有下一次")
    void noNextOccurrenceAfterTheLast() {
        WfTimerSupport.Cycle cycle = WfTimerSupport.parseCycle("R3/PT1H");
        Date prev = new Date(0L);
        // 参数是"刚响的那条是第几次触发"（1 起）
        assertNotNull(WfTimerSupport.nextDueDate(cycle, prev, 1), "第 1 次之后还有第 2 次");
        assertNotNull(WfTimerSupport.nextDueDate(cycle, prev, 2), "第 2 次之后还有第 3 次");
        assertNull(WfTimerSupport.nextDueDate(cycle, prev, 3),
                "第 3 次是最后一次：R3 说的是一共响 3 次。"
                        + "这里若放行就是多响一次，作者写三次实际催了四次");
    }

    @Test
    @DisplayName("无界写法有硬上限，不会无限挂下去")
    void unboundedHasAHardCap() {
        WfTimerSupport.Cycle cycle = WfTimerSupport.parseCycle("R/PT10M");
        assertNull(WfTimerSupport.nextDueDate(cycle, new Date(0L), WfTimerSupport.MAX_CYCLE_FIRES),
                "达到硬上限后必须停 —— 否则一个没人管的单子会被无限催，"
                        + "每次还多出一条并行分支");
    }

    // ==================== 运行期行为 ====================

    @Test
    @DisplayName("非中断 + R3/PT1H：响一次后重新挂下一次，且宿主待办不动")
    void rearmHappensAndHostIsUntouched() {
        String pid = startCycle("R3/PT1H");
        assertEquals(1, openAt(pid, "approve").size());
        assertEquals(1, timerJobs(pid).size(), "启动时挂一条");
        assertEquals(1, timerJobs(pid).get(0).getCycleIndex(),
                "初始那条就是第 1 次触发 —— 记成 0 会让 R3 照响第 4 次");

        assertEquals(1, fireAll(pid), "到点应当触发一次");

        assertEquals(1, openAt(pid, "approve").size(), "宿主的待办不动 —— 这是非中断型的全部意义");
        assertEquals(1, openAt(pid, "urge").size(), "催办待办建出来了");
        List<WfJob> pending = timerJobs(pid);
        assertEquals(1, pending.size(), "响过的那条被消费，应当正好挂上新的那一条。实际 " + pending);
        assertEquals(2, pending.get(0).getCycleIndex(),
                "新挂的那条是第 2 次触发");
    }

    @Test
    @DisplayName("R3/PT1H 正好响 3 次，第 4 次不再挂")
    void firesExactlyThreeTimes() {
        String pid = startCycle("R3/PT1H");
        assertEquals(1, fireAll(pid));
        assertEquals(1, fireAll(pid));
        assertEquals(1, fireAll(pid));
        assertEquals(0, fireAll(pid), "响满 3 次之后不再触发");

        assertEquals(3, openAt(pid, "urge").size(),
                "三次催办各建一条待办 —— 少一条就是漏响，多一条就是没数对");
        assertTrue(timerJobs(pid).isEmpty(),
                "响满之后不该再留下待触发的 job。实际 " + timerJobs(pid));
    }

    @Test
    @DisplayName("下一时刻是「上一次 + 周期」，不是重新从进入节点算")
    void nextDueIsPreviousPlusOnePeriod() {
        String pid = startCycle("R3/PT1H");
        fireAll(pid);
        Date firstPending = timerJobs(pid).get(0).getDuedate();
        fireAll(pid);
        Date secondPending = timerJobs(pid).get(0).getDuedate();

        assertEquals(3600_000L, secondPending.getTime() - firstPending.getTime(),
                "两次触发之间恰好一个周期。若重新从「进入节点」算，"
                        + "执行器的扫描时刻会把它顶到下一轮，节奏就不对了");
    }

    @Test
    @DisplayName("宿主办完之后不再冒出新的提醒")
    void stopsAfterTheHostIsDone() {
        String pid = startCycle("R/PT1M");
        fireAll(pid);
        assertEquals(1, timerJobs(pid).size(), "无界写法会一直挂 —— 这正是它被限次数的原因");

        complete(openAt(pid, "approve").get(0));
        // 宿主 token 走了，剩下那条必然撞上「token 不在宿主节点上」的闸门
        int fired = fireAll(pid);
        assertEquals(0, fired, "宿主办完之后不该再触发任何提醒。实际触发了 " + fired);
        assertEquals(1, openAt(pid, "urge").size(), "只催过那一次");
    }

    @Test
    @DisplayName("无界写法在没人管时会停在硬上限，不会无限催")
    void unboundedStopsAtTheCap() {
        // 周期取 PT1S，一次执行器扫描触发一轮，循环到硬上限
        String pid = startCycle("R/PT1S");
        int total = 0;
        for (int i = 0; i < WfTimerSupport.MAX_CYCLE_FIRES + 5; i++) {
            total += fireAll(pid);
        }
        assertEquals(WfTimerSupport.MAX_CYCLE_FIRES, total,
                "无界写法也要有硬上限：一个没人管的单子被无限催下去，"
                        + "每次还多出一条并行分支，比不响更糟");
        assertTrue(timerJobs(pid).isEmpty(), "触顶后不再挂下一次");
    }

    // ==================== 部署期 ====================

    @Test
    @DisplayName("中断型边界上的循环定时器报 ERROR：响过之后没有宿主可打断")
    void cycleOnInterruptingBoundaryIsRejected() {
        String xml = bpmn("intCycle", "", "R3/PT1H");
        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> repository.deployXml(xml, "intCycle"));
        assertTrue(e.getMessage().contains("非中断"),
                "报错要说清是要配 cancelActivity=false。实际 " + e.getMessage());
    }

    @Test
    @DisplayName("timeCycle 表达式格式错在部署期就报：否则它一次都不会响且没人知道")
    void malformedCycleIsRejectedAtDeployTime() {
        List<WfValidationIssue> issues = new WfDefinitionValidator()
                .validate(new WfXmlParser().parse(bpmn("badCycle", " cancelActivity=\"false\"",
                        "每 1 小时一次")));
        boolean found = false;
        for (WfValidationIssue issue : issues) {
            if (issue.getSeverity() == WfValidationIssue.Severity.ERROR
                    && issue.getMessage().contains("timeCycle")) {
                found = true;
            }
        }
        assertTrue(found, "中文写法必须在部署期报出来。实际 " + issues);
    }

    @Test
    @DisplayName("R0/PT1H 报 ERROR：一次都不会响的循环等于哑表")
    void zeroRepeatsIsRejected() {
        List<WfValidationIssue> issues = new WfDefinitionValidator()
                .validate(new WfXmlParser().parse(bpmn("zeroCycle", " cancelActivity=\"false\"",
                        "R0/PT1H")));
        assertTrue(WfDefinitionValidator.hasError(issues), "实际 " + issues);
    }

    @Test
    @DisplayName("循环定时器要能过部署期（非中断型 + 合法表达式）")
    void validCycleDeploysFine() {
        WfDefinition definition = repository.deployXml(
                bpmn("okCycle", " cancelActivity=\"false\"", "R3/PT1H"), "okCycle");
        assertNotNull(definition);
        assertEquals(WfTimerType.CYCLE, definition.node("urgeBoundary").getTimerType());
    }
}
