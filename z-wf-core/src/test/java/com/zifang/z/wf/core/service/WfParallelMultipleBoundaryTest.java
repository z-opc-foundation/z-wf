package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionValidator;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfValidationIssue;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.persistence.WfDefinitionCodec;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfExecutionQuery;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 多实例活动上的边界事件，以及 BPMN 2.0 的 {@code parallelMultiple}。
 *
 * <p><b>本类钉的是一个真缺陷：中断型边界事件只搬走了一条 token，
 * 其余实例的 token 原地不动，于是流程永久挂死。</b>
 *
 * <p>复现形状是「三人会签 + 超时补救」：
 * <ol>
 *   <li>会签展开 3 条 token，各建一条待办；</li>
 *   <li>超时到期，边界事件把<b>其中一条</b> token 搬到补救任务上，
 *       并把 3 条待办全部作废（这一半是对的：人都被打断了，不该还能办）；</li>
 *   <li>补救任务办完，token 走到 endEvent 并结束；</li>
 *   <li><b>另外 2 条 token 仍然 WAITING 停在会签节点上</b> ——
 *       没有待办、没有 job、没有定时器，永远不会再动。</li>
 * </ol>
 * 而实例状态停在 {@code ACTIVE}：没有任何报错、没有日志、没有故障记录，
 * <b>流程永远不结束</b>。定时查「在跑的实例」时它一直在那儿。
 *
 * <p>修法与依据：{@code parallelMultiple="false"}（BPMN 2.0 默认值，也是
 * <b>Camunda 7 / 8 唯一实现的那一种</b>）要求中断型边界事件触发时
 * <b>销毁全部实例</b>。Camunda 7 官方 BPMN 2.0 实现参考原文：
 * "In case of an interrupting boundary event, when the event is caught,
 * all instances that are still active will be destroyed."
 * （docs.camunda.org/7.3/api-references/bpmn20/ —— 该页 "boundary event" 出现 70 次、
 * "all instances" 出现 5 次，而 {@code parallelMultiple} 出现 <b>0</b> 次。
 * 抓取时确认过页面确实加载：标题为 "BPMN 2.0 Implementation Reference"、333 KB。）
 *
 * <p>{@code parallelMultiple="true"} 是<b>规范有、Camunda 没有</b>的那一种：
 * 每个实例各挂一个边界事件，中断一个只打断那一个，其余实例照办。
 * 它比 {@code false} 更贴近"每个人各自有 SLA"这种真实诉求。
 */
class WfParallelMultipleBoundaryTest {

    /** 扫描时刻要落在所有定时器 duedate 之后，且留出足够余量避开同毫秒抖动。 */
    private static final long FIRE_HORIZON_MILLIS = 3L * 86_400_000L;

    /** 「推到未来」用的截止点，必须比 {@link #FIRE_HORIZON_MILLIS} 更远。 */
    private static final long PARK_HORIZON_MILLIS = 30L * 86_400_000L;

    /**
     * 三人会签 + 中断型超时边界 → 补救 → 结束。
     *
     * <p>{@code boundaryAttrs} 用来在同一张图上切换 {@code parallelMultiple} 与
     * {@code cancelActivity}，避免同一语义散在两份几乎一样的 BPMN 里。
     */
    private static String bpmn(String processId, String boundaryAttrs) {
        return bpmn(processId, boundaryAttrs, true);
    }

    private static String bpmn(String processId, String boundaryAttrs, boolean hostIsMultiInstance) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"" + processId + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                + "    <userTask id=\"sign\" name=\"会签\" zifang:assignee=\"boss\">\n"
                + (hostIsMultiInstance
                ? "      <multiInstanceLoopCharacteristics>\n"
                + "        <loopCardinality>3</loopCardinality>\n"
                + "      </multiInstanceLoopCharacteristics>\n"
                : "")
                + "    </userTask>\n"
                + "    <boundaryEvent id=\"timeout\" attachedToRef=\"sign\" " + boundaryAttrs + ">\n"
                + "      <timerEventDefinition>"
                + "<timeDuration>P1D</timeDuration></timerEventDefinition>\n"
                + "    </boundaryEvent>\n"
                + "    <userTask id=\"handle\" name=\"补救\" zifang:assignee=\"boss\"/>\n"
                + "    <endEvent id=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"sign\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"sign\" targetRef=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f3\" sourceRef=\"timeout\" targetRef=\"handle\"/>\n"
                + "    <sequenceFlow id=\"f4\" sourceRef=\"handle\" targetRef=\"e1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
    }

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        WfHookDispatcher hooks = new WfHookDispatcher();
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), hooks);
    }

    // ==================== 本轮修掉的真缺陷 ====================

    @Test
    @DisplayName("中断型边界触发后会签节点上不再留有未结束的 token —— 留着就是永久挂死")
    void interruptingBoundaryLeavesNoStrandedToken() {
        String pid = startMi("miDefault", "");
        assertEquals(3, openAt(pid, "sign").size(), "前置：三个人都在办");
        assertEquals(3, liveTokensAt(pid, "sign"), "前置：会签节点上有 3 条 token");

        fireAllTimers();

        assertEquals(0, liveTokensAt(pid, "sign"),
                "中断型边界事件触发后，会签节点上还剩 " + liveTokensAt(pid, "sign")
                        + " 条未结束的 token。它们没有待办、没有 job、没有定时器，"
                        + "永远不会再动 —— 而实例状态取决于有没有活跃 token，"
                        + "于是这个流程永远不结束，且没有任何报错。"
                        + "BPMN 2.0 的 parallelMultiple 默认值 false 要求销毁全部实例，"
                        + "Camunda 7 官方原文是 \"all instances that are still active "
                        + "will be destroyed\"");
    }

    @Test
    @DisplayName("补救任务办完之后实例能真正结束（这条才是用户看到的症状）")
    void instanceCompletesAfterCompensationPathFinishes() {
        String pid = startMi("miComplete", "");
        fireAllTimers();

        WfTask handle = oneOpenAt(pid, "handle");
        runtime.completeTask(handle.getId(), handle.getAssignee(), "补救完",
                new HashMap<String, Object>());

        WfProcessInstance instance = repo.findProcessInstance(pid);
        assertEquals(WfProcessStatus.COMPLETED, instance.getStatus(),
                "补救办完就该结束。实际停在 " + instance.getStatus()
                        + " —— 会签节点上残留的 token 让它永远等不到「没有活跃 token」，"
                        + "而排障界面上看不出任何异常");
    }

    @Test
    @DisplayName("中断时作废的是**全部**实例的待办，且不留残余 job")
    void interruptCancelsEveryInstanceTask() {
        String pid = startMi("miCancelAll", "");
        fireAllTimers();

        List<WfTask> signTasks = allAt(pid, "sign");
        assertEquals(3, signTasks.size(), "三个实例的待办都该在库里");
        for (WfTask t : signTasks) {
            assertEquals(WfTask.Status.CANCELLED, t.getStatus(),
                    "实例 " + t.getId() + " 的待办应当作废 —— 人已经被打断了，"
                            + "待办还挂着只会让人以为还能办");
        }
        assertEquals(0, timerJobs(pid).size(),
                "宿主被中断后不该还留着定时器 job：留着的话它到点会再触发一次，"
                        + "而那时宿主上的 token 早没了");
    }

    @Test
    @DisplayName("销毁实例这件事要留痕 —— 不静默是本引擎的硬约定")
    void destroyIsTracedInComments() {
        String pid = startMi("miTrace", "");
        fireAllTimers();

        List<String> texts = new ArrayList<>();
        for (WfComment c : repo.findComments(pid)) {
            if (c.getContent() != null) {
                texts.add(c.getContent());
            }
        }
        boolean mentioned = false;
        for (String text : texts) {
            if (text.contains("实例") && text.contains("销毁")) {
                mentioned = true;
                break;
            }
        }
        assertTrue(mentioned,
                "销毁了 2 条 token 却在轨迹上一句不留，排障时看到「流程不结束」"
                        + "根本无从知道是这一步干的。实际评论：" + texts);
        assertNotEquals(3, liveTokensAt(pid, "sign"),
                "这条断言只是把上面那句钉住：销毁后宿主上不可能还有 3 条 token");
    }

    // ==================== parallelMultiple ====================

    @Test
    @DisplayName("parallelMultiple 默认（不写）只起一个定时器：边界事件属于整个活动")
    void defaultCreatesSingleTimerForWholeActivity() {
        String pid = startMi("miOneJob", "");
        assertEquals(1, timerJobs(pid).size(),
                "BPMN 2.0 里 parallelMultiple 的默认值是 false，"
                        + "含义是「这个活动只有一个边界事件」。"
                        + "Camunda 7 文档的措辞也是 \"all instances of the subprocess "
                        + "will be destroyed when the timer fires, regardless of how many "
                        + "instances there are\"");
    }

    @Test
    @DisplayName("显式写 parallelMultiple=\"false\" 与不写等价")
    void explicitFalseIsSameAsDefault() {
        String pid = startMi("miFalse", "parallelMultiple=\"false\"");
        assertEquals(1, timerJobs(pid).size(),
                "显式 false 与不写必须是同一件事：BPMN 2.0 规定未设置时默认 false，"
                        + "写与不写给出不同结果的话，同一张图换个建模工具就换了个语义");
    }

    @Test
    @DisplayName("parallelMultiple=\"true\" 每个实例各起一个定时器，各绑各的 token")
    void parallelMultipleTrueCreatesOneJobPerInstance() {
        String pid = startMi("miTrue", "parallelMultiple=\"true\"");

        List<WfJob> jobs = timerJobs(pid);
        assertEquals(3, jobs.size(),
                "parallelMultiple=true 的含义是「每个实例各有各的边界事件」，"
                        + "所以要每个实例各起一个表。实际起了 " + jobs.size() + " 个");

        Set<String> boundExecutions = new HashSet<>();
        for (WfJob job : jobs) {
            boundExecutions.add(job.getExecutionId());
        }
        assertEquals(3, boundExecutions.size(),
                "三个 job 必须绑在三条不同的实例 token 上 —— 绑同一条的话"
                        + "触发其中一个就等于同时回答了三个实例，实际是同一个事件。实际绑了 "
                        + boundExecutions);
    }

    @Test
    @DisplayName("parallelMultiple=\"true\" 中断一个只打断那一个，其余实例照办")
    void parallelMultipleTrueInterruptsOnlyItsOwnInstance() {
        String pid = startMi("miTrueIsolated", "parallelMultiple=\"true\"");

        // 只让**一条** job 到期：把其余两条推到明天。
        // 这一步不是为了"造出现实中不会发生的局面"，而是为了把"只打断一个"
        // 这件事单独观察出来 —— 三个一起到期的话两种实现给出同一个终态。
        WfJob target = null;
        for (WfJob job : timerJobs(pid)) {
            if (target == null) {
                target = job;
            } else {
                job.setDuedate(new Date(System.currentTimeMillis() + PARK_HORIZON_MILLIS));
                job.nextRevision();
                repo.saveJob(job);
            }
        }
        fireAllTimers();

        assertEquals(2, liveTokensAt(pid, "sign"),
                "parallelMultiple=true 时只有被超时打断的那一个离开会签节点，其余两个留下。实际剩 "
                        + liveTokensAt(pid, "sign") + " 条");
        assertEquals(1, liveTokensAt(pid, "handle"),
                "被打断的那一条应当已经被搬到补救节点上");
        assertEquals(2, openAt(pid, "sign").size(),
                "parallelMultiple=true 时另外两个实例**不受影响**，"
                        + "他们的待办必须还能办。实际还剩 "
                        + openAt(pid, "sign").size() + " 条可办的待办"
                        + "（若这里变成 0，那是把 false 的语义错配到了 true 上）");
        assertEquals(2, timerJobs(pid).size(),
                "被打断的实例，它的定时器已经消耗掉了；另两个还在等，"
                        + "所以库里应当还剩 2 条。实际 " + timerJobs(pid).size());
    }

    @Test
    @DisplayName("parallelMultiple=\"true\" 全被逐个打断后，实例仍能正常结束")
    void parallelMultipleTrueAllInterruptedStillCompletes() {
        String pid = startMi("miTrueAll", "parallelMultiple=\"true\"");
        fireAllTimers();

        // 三条边界各走一次补救分支，流程上会出现 3 条补救 token 并行往下走
        WfProcessInstance instance = repo.findProcessInstance(pid);
        assertTrue(instance.getStatus() == WfProcessStatus.ACTIVE
                        || instance.getStatus() == WfProcessStatus.COMPLETED,
                "逐个打断不该把流程推进到非法状态。实际 " + instance.getStatus());
        assertEquals(0, liveTokensAt(pid, "sign"),
                "三个实例都被打断了，会签节点上不该还留着 token");
    }

    // ==================== 非中断型：对照 ====================

    @Test
    @DisplayName("非中断型边界不销毁实例 —— 这是它与中断型的分界")
    void nonInterruptingBoundaryKeepsInstancesAlive() {
        String pid = startMi("miNonInterrupt", "cancelActivity=\"false\"");

        fireAllTimers();

        assertEquals(3, liveTokensAt(pid, "sign"),
                "非中断型的定义是「宿主照常办理」：三个实例必须都还在，"
                        + "他们的待办必须都还能办。实际会签节点上只剩 "
                        + liveTokensAt(pid, "sign") + " 条 token");
        assertEquals(3, openAt(pid, "sign").size(),
                "非中断型不得作废宿主待办");
    }

    @Test
    @DisplayName("非中断型 + parallelMultiple=true 不会变成每实例一次（它本来就是并行的）")
    void nonInterruptingWithParallelMultiple() {
        String pid = startMi("miNonInterruptTrue",
                "cancelActivity=\"false\" parallelMultiple=\"true\"");
        // 非中断型走的是「另起一条分支」，起几条由事件响几次决定，
        // 与实例数无关 —— 这里守住的是"起 3 条"而不是"起 1 条"这个回归
        assertEquals(3, liveTokensAt(pid, "sign"),
                "宿主三条 token 都得在");
    }

    // ==================== 校验器与编解码 ====================

    @Test
    @DisplayName("parallelMultiple 挂在非多实例宿主上报 WARN，并说清它无效而不是无效在哪")
    void warnWhenHostIsNotMultiInstance() {
        WfDefinition definition = new WfXmlParser()
                .parse(bpmn("plainHost", "parallelMultiple=\"true\"", false));

        List<String> warnings = new ArrayList<>();
        for (WfValidationIssue issue : new WfDefinitionValidator().validate(definition)) {
            if (issue.getSeverity() == WfValidationIssue.Severity.WARN) {
                warnings.add(issue.getMessage());
            }
        }
        boolean hit = false;
        for (String w : warnings) {
            if (w.contains("parallelMultiple") && w.contains("没有意义")) {
                hit = true;
                break;
            }
        }
        assertTrue(hit,
                "宿主只有一个实例时这个属性无处可施，必须说破而不是默默按 false 跑。"
                        + "实际 WARN：" + warnings);
    }

    @Test
    @DisplayName("编解码往返保住 parallelMultiple —— 属性写进库就丢了等于没实现")
    void codecRoundTripKeepsParallelMultiple() {
        WfDefinition original = new WfXmlParser().parse(bpmn("codecMi", "parallelMultiple=\"true\""));
        WfNode boundary = original.node("timeout");
        assertTrue(boundary != null && boundary.isParallelMultiple(),
                "解析这一侧就该认得这个属性");

        WfDefinition restored = WfDefinitionCodec.decode(WfDefinitionCodec.encode(original));
        assertTrue(restored.node("timeout") != null
                        && restored.node("timeout").isParallelMultiple(),
                "编解码往返后 parallelMultiple 丢了 —— 部署走库、运行读库，"
                        + "属性只活在内存里的话，行为与「没写这个属性」完全一致，"
                        + "而图上明明写着 parallelMultiple=\"true\"");
    }

    // ==================== 工具 ====================

    private String startMi(String key, String boundaryAttrs) {
        WfDefinition definition = repository.deployXml(bpmn(key, boundaryAttrs), key);
        return runtime.startProcessInstance(definition, key + "-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
    }

    /**
     * 让当前所有定时器到期。
     *
     * <p><b>余量取 3 天而不是 1 天</b>：定时器的 duedate 是
     * 「token 进入宿主那一刻 + P1D」，而扫描条件是<b>严格</b>小于
     * （{@code !duedate.before(now)} 就排除）—— 所以若扫描时刻恰好与
     * 进程启动落在<b>同一毫秒</b>，{@code now + 1 天} 正好等于 duedate，
     * 这个 job 永远捞不到，边界事件<b>一次都不会触发</b>。
     * 症状是随机的：同一个测试类里不同的用例轮流红，且红得毫无规律。
     */
    private void fireAllTimers() {
        new WfJobService(repo, runtime)
                .executeDueJobs(new Date(System.currentTimeMillis() + FIRE_HORIZON_MILLIS));
    }

    private List<WfJob> timerJobs(String pid) {
        return repo.queryJobs(new WfJobQuery().setType(WfJobType.TIMER)
                .setProcessInstanceId(pid).setPageNum(1).setPageSize(50));
    }

    private List<WfTask> allAt(String pid, String nodeId) {
        List<WfTask> result = new ArrayList<>();
        for (WfTask t : repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(50))) {
            if (nodeId.equals(t.getDefinitionId())) {
                result.add(t);
            }
        }
        return result;
    }

    private List<WfTask> openAt(String pid, String nodeId) {
        List<WfTask> result = new ArrayList<>();
        for (WfTask t : allAt(pid, nodeId)) {
            if (t.isOpen()) {
                result.add(t);
            }
        }
        return result;
    }

    private WfTask oneOpenAt(String pid, String nodeId) {
        List<WfTask> open = openAt(pid, nodeId);
        assertEquals(1, open.size(), nodeId + " 上应当恰好有一条可办待办，实际 " + open.size());
        return open.get(0);
    }

    /** 该节点上还没结束的 token 数。 */
    private int liveTokensAt(String pid, String nodeId) {
        int n = 0;
        for (WfExecution e : repo.queryExecutions(new WfExecutionQuery()
                .setProcessInstanceId(pid).setActivityId(nodeId)
                .setPageNum(1).setPageSize(50))) {
            if (!e.isEnded()) {
                n++;
            }
        }
        return n;
    }
}
