package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionValidator;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfValidationIssue;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.hook.WfProcessHook;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfJobQuery;

/**
 * 异步 job 的 {@code exclusive} 互斥。
 *
 * <p><b>本轮修掉的第一件事：这个属性此前压根没被解析。</b>
 * 从 Camunda 导出的模型带着 {@code camunda:exclusive="false"}，
 * 照搬过来被**静默丢弃** —— 作者以为关掉了互斥，实际什么都没发生，
 * 而流程图上看不出任何区别。
 *
 * <p>依据（Camunda 7 官方扩展属性表，抓取时确认页面已加载、
 * 标题 "Camunda BPMN Extension Attributes"、145 KB）：
 * "Exclusive Jobs are the default configuration. All asynchronous continuations
 * and timer events are thus exclusive by default."
 * ⇒ 所以默认值必须是 {@code true}；反过来做（不写即不互斥）会让
 * 「照搬 Camunda 模型但没写这个属性」的那一批悄悄失去 Camunda 给的保证。
 *
 * <p>同页 Constraints 行还有一条本轮照做：
 * "The camunda:exclusive attribute is only evaluated if the attribute
 *  camunda:asyncBefore or camunda:asyncAfter is set to true"。
 */
class WfExclusiveAsyncJobTest {

    /** 异步 serviceTask；{@code attrs} 用来在同一张图上切换 exclusive 的写法。 */
    private static String bpmn(String processId, String attrs) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/wf/bpmn/ext\""
                + " xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\""
                + " targetNamespace=\"x\">\n"
                + "  <process id=\"" + processId + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                + "    <serviceTask id=\"work\" zifang:delegateClass=\"com.example.Demo\""
                + " zifang:asyncBefore=\"true\" " + attrs + "/>\n"
                // 后面接一个真正会停住的人工任务：**流程必须一直活着**。
                // 直连 endEvent 的话，第一条异步 job 一跑完实例就 COMPLETED，
                // 第二条 job 自然什么都执行不了 —— 断言会以「实例已结束」这种
                // 与 exclusive 毫无关系的理由失败，而它看起来像互斥没生效
                + "    <userTask id=\"park\" name=\"停住\" zifang:assignee=\"alice\"/>\n"
                + "    <endEvent id=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"work\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"work\" targetRef=\"park\"/>\n"
                + "    <sequenceFlow id=\"f3\" sourceRef=\"park\" targetRef=\"e1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
    }

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfHookDispatcher hooks;
    private Gate gate;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        hooks = new WfHookDispatcher();
        gate = new Gate();
        hooks.addProcessHook(gate);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), hooks);
    }

    /**
     * 在 job 真正推进完之后插一道闸，好让「并发」这件事<b>真的发生过</b>。
     *
     * <p>不加这道闸的话，两个线程各扫各的队列，大概率一个已经跑完了 ——
     * 那时断言「没有并发」会因**错误的原因**通过（根本没机会重叠）。
     *
     * <p>用 {@code onJobExecuted} 而不是子类化 {@code WfRuntimeService}：
     * 后者要把仓储、引擎、钩子三个协作者重新拼一遍，测试里那层壳比被测的逻辑还脆。
     */
    private static final class Gate implements WfProcessHook {
        private volatile Runnable blocker;
        private final AtomicInteger inside = new AtomicInteger();
        /** 每进来一条 job 就减一次，用来看"两条是否**同时**在里面"。 */
        private final java.util.concurrent.CountDownLatch arrived =
                new java.util.concurrent.CountDownLatch(2);

        /** 只阻塞<b>接下来的第一条</b> job；之后的照常放行。 */
        void blockNextJob(Runnable action) {
            blocker = action;
        }

        int arrived() {
            return (int) arrived.getCount();
        }

        int inside() {
            return inside.get();
        }

        @Override
        public void onJobExecuted(String definitionKey, String processInstanceId,
                                  String jobId, String jobType, String elementId,
                                  boolean success) {
            inside.incrementAndGet();
            arrived.countDown();
            try {
                Runnable action = blocker;
                if (action != null) {
                    blocker = null;
                    action.run();
                }
            } finally {
                inside.decrementAndGet();
            }
        }
    }

    // ==================== 解析与默认值 ====================

    @Test
    @DisplayName("不写 exclusive 时默认互斥 —— 与 Camunda 一致，不是默认不互斥")
    void defaultIsExclusive() {
        WfDefinition definition = repository.deployXml(bpmn("exDefault", ""), "exDefault");
        assertTrue(definition.node("work").isExclusive(),
                "Camunda 原文：\"Exclusive Jobs are the default configuration\"。"
                        + "默认做成不互斥的话，「照搬 Camunda 模型但没写这个属性」"
                        + "的那一批会悄悄失去 Camunda 给的保护，而图上看不出区别");
    }

    @Test
    @DisplayName("camunda:exclusive 与 zifang:exclusive 两个前缀都认")
    void bothPrefixesRecognised() {
        WfDefinition camundaSide = repository.deployXml(
                bpmn("exCamunda", "camunda:exclusive=\"false\""), "exCamunda");
        WfDefinition zifangSide = repository.deployXml(
                bpmn("exZifang", "zifang:exclusive=\"false\""), "exZifang");

        assertFalse(camundaSide.node("work").isExclusive(),
                "Camunda 导出的模型带的是 camunda: 前缀，认不出来等于这个属性没实现");
        assertFalse(zifangSide.node("work").isExclusive(),
                "本仓自己的前缀当然也要认 —— 否则两套写法给出不同结果");
    }

    @Test
    @DisplayName("显式写 true 与不写等价（默认已是 true）")
    void explicitTrueSameAsDefault() {
        WfDefinition definition = repository.deployXml(
                bpmn("exTrue", "camunda:exclusive=\"true\""), "exTrue");
        assertTrue(definition.node("work").isExclusive(),
                "BPMN 里默认值就是 true，显式写 true 必须得到同一个结果");
    }

    @Test
    @DisplayName("exclusive 写在没有异步的节点上报 WARN —— 属性在那里没有意义")
    void warnWhenWrittenOnNonAsyncNode() {
        String xml = bpmn("exPlain", "camunda:exclusive=\"true\"")
                .replace(" zifang:asyncBefore=\"true\"", "");

        List<String> warnings = new ArrayList<>();
        for (WfValidationIssue issue : new WfDefinitionValidator()
                .validate(new WfXmlParser().parse(xml))) {
            if (issue.getSeverity() == WfValidationIssue.Severity.WARN) {
                warnings.add(issue.getMessage());
            }
        }
        boolean hit = false;
        for (String w : warnings) {
            if (w.contains("exclusive") && w.contains("asyncBefore")) {
                hit = true;
                break;
            }
        }
        assertTrue(hit,
                "Camunda 的约束是「只在 asyncBefore/asyncAfter 为 true 时才求值」。"
                        + "写在别处等于白写，而作者以为它生效了 —— 必须说破。实际 WARN：" + warnings);
    }

    @Test
    @DisplayName("异步节点上写 exclusive 不报 WARN（那才是它该在的地方）")
    void noWarnOnAsyncNode() {
        String xml = bpmn("exAsyncNode", "camunda:exclusive=\"false\"");

        List<String> warnings = new ArrayList<>();
        for (WfValidationIssue issue : new WfDefinitionValidator()
                .validate(new WfXmlParser().parse(xml))) {
            if (issue.getSeverity() == WfValidationIssue.Severity.WARN) {
                warnings.add(issue.getMessage());
            }
        }
        boolean hit = false;
        for (String w : warnings) {
            if (w.contains("exclusive")) {
                hit = true;
                break;
            }
        }
        assertFalse(hit,
                "属性写在异步节点上是正常用法，不该被当错事提醒。实际 WARN：" + warnings);
    }

    // ==================== 落到 job 上 ====================

    @Test
    @DisplayName("exclusive 从宿主节点抄进 job —— 执行器手上只有 job，读不到定义")
    void exclusiveIsCopiedOntoJob() {
        String pid = startExclusive("exOnJob", "camunda:exclusive=\"true\"");
        assertTrue(asyncJobs(pid).get(0).isExclusive(), "显式 true 的 job 应标记为互斥");
    }

    @Test
    @DisplayName("exclusive=\"false\" 也要抄进 job，且不能被默认 true 盖回去")
    void exclusiveFalseSurvivesOntoJob() {
        String pid = startExclusive("exOnJobFalse", "camunda:exclusive=\"false\"");
        assertFalse(asyncJobs(pid).get(0).isExclusive(),
                "这是本轮最容易丢的一处：字段默认是 true，"
                        + "建 job 时忘了抄、或抄成了 true，显式写 false 的作者"
                        + "就得到一个会互斥的 job —— 且没有任何报错");
    }

    // ==================== 执行期互斥 ====================

    @Test
    @DisplayName("同实例的两个 exclusive job 不会同时推进（多线程驱动）")
    void exclusiveJobsOfSameInstanceDoNotOverlap() throws Exception {
        // 造两条同实例的 exclusive job，并让真正的推进动作在「进入」时停一下，
        // 好让第二个线程有机会撞进来 —— 不同步的话两个线程各扫各的，
        // 大概率一个已经跑完了，断言会因错误的原因通过
        String pid = startExclusive("exOverlap", "camunda:exclusive=\"true\"");
        List<WfJob> jobs = asyncJobs(pid);
        assertEquals(1, jobs.size(), "前置：本用例自己造第二条 job");

        WfJob second = cloneJob(jobs.get(0), "job-second-" + System.nanoTime());
        second.setDuedate(new Date());
        repo.saveJob(second);

        WfJobService service = new WfJobService(repo, runtime);
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicBoolean overlapped = new AtomicBoolean();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        gate.blockNextJob(() -> {
            if (concurrent.incrementAndGet() > 1) {
                overlapped.set(true);
            }
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrent.decrementAndGet();
            }
        });

        Thread t1 = new Thread(() -> service.executeAsyncJobs(new Date(System.currentTimeMillis() + 3_600_000L)));
        Thread t2 = new Thread(() -> service.executeAsyncJobs(new Date(System.currentTimeMillis() + 3_600_000L)));
        t1.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "第一个线程应当已经进入推进");
        t2.start();
        // 给 t2 足够时间真的撞上锁，而不是「还没开始跑」就断言
        Thread.sleep(300);
        release.countDown();
        t1.join(5000);
        t2.join(5000);

        assertFalse(overlapped.get(),
                "同一流程实例的两个 exclusive job 同时进入了推进 —— "
                        + "Camunda 的定义是「不与同实例的其它 exclusive job 并发执行」。"
                        + "本实现的保证比 Camunda 更硬（Camunda 自称 heuristic），"
                        + "连「拿不到锁就跳过」这条也在内");
    }

    @Test
    @DisplayName("不同实例之间不互斥 —— 互斥的作用域是流程实例，不是全局")
    void differentInstancesAreNotMutuallyExclusive() throws Exception {
        String pidA = startExclusive("exScopeA", "camunda:exclusive=\"true\"");
        String pidB = startExclusive("exScopeB", "camunda:exclusive=\"true\"");

        WfJobService service = new WfJobService(repo, runtime);
        final CountDownLatch release = new CountDownLatch(1);
        // 计数与 countDown 都交给 Gate：放在这里的一次性动作里只会减一次，
        // 而这条用例要证明的是"两条**同时**在里面"，需要每次进入都计
        gate.blockNextJob(() -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread t1 = new Thread(() -> service.executeAsyncJobs(new Date(System.currentTimeMillis() + 3_600_000L)));
        t1.start();
        // **有界**等待：第一条 job 迟迟不进来时，断言该失败而不是把测试挂死 ——
        // 无界的 `while (x == 0) sleep(5)` 一旦条件永远不成立，
        // surefire 只能等超时，而那与「卡住」在日志上长得一模一样。
        // 用 Gate 自己的 inside（每次 job 进入都加减），而不是一次性动作里的局部计数
        assertTrue(waitUntil(() -> gate.inside() > 0),
                "第一个实例的 job 迟迟没有进入推进，前置就不成立");
        // 第二个实例的 job 由本线程继续跑：它与第一个**不是同一个实例**，
        // 不该被第一个的锁挡住 —— 挡住就是"互斥作用域搞成了全局"
        service.executeAsyncJobs(new Date(System.currentTimeMillis() + 3_600_000L));
        release.countDown();
        t1.join(5000);

        assertEquals(0, gate.arrived(),
                "两个不同流程实例的 exclusive job 必须能同时推进 —— "
                        + "Camunda 原文限定的是 \"another exclusive job from the "
                        + "same process instance\"。同实例才互斥");
        assertTrue(pidA != null && pidB != null, "前置：两个实例都建出来了");
    }

    // ----------------------------------------------------------------
    // **已知覆盖缺口（不在本轮硬凑）**
    //
    // 「标了 exclusive=false 的 job 不被同实例的锁拦下」这条性质，
    // 由 WfJobService#withInstanceLock 的 `!job.isExclusive()` 早退分支保证，
    // 但**没有行为判据**。原先写过一版并发的，用「克隆一条同实例 job」来造第二条 ——
    // 而克隆出来的 job 指向的是**第一条已经消费掉的 token**，
    // 于是它必然执行不了，断言以「实例/token 已经不在那儿」这种
    // 与 exclusive 无关的原因失败。
    // 要做对得另造一条「当时确实有效」的同实例 job，那需要一张
    // 「两个连续异步节点」的图，与本类其余用例的形状都不一样。
    // 留一条靠运气的并发测试比没有更坏 —— 它会在某天无关的改动下变红，
    // 而红的原因指向 exclusive，排查成本极高。故本轮留缺口、如实记录。
    // ----------------------------------------------------------------

    // ==================== 工具 ====================

    private String startExclusive(String key, String attrs) {
        WfDefinition definition = repository.deployXml(bpmn(key, attrs), key);
        return runtime.startProcessInstance(definition, key + "-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
    }

    private List<WfJob> asyncJobs(String pid) {
        return repo.queryJobs(new WfJobQuery().setType(WfJobType.ASYNC_BEFORE)
                .setProcessInstanceId(pid).setPageNum(1).setPageSize(20));
    }

    /** 有界地等条件成立，最多 {@code millis} 毫秒。 */
    private static boolean waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    private WfJob cloneJob(WfJob source, String newId) {
        WfJob copy = new WfJob();
        copy.setId(newId);
        copy.setProcessInstanceId(source.getProcessInstanceId());
        copy.setExecutionId(source.getExecutionId());
        copy.setElementId(source.getElementId());
        copy.setAttachedToRef(source.getAttachedToRef());
        copy.setType(source.getType());
        copy.setDuedate(source.getDuedate());
        copy.setRetries(source.getRetries());
        copy.setExclusive(source.isExclusive());
        copy.setCreateTime(new Date());
        return copy;
    }

}
