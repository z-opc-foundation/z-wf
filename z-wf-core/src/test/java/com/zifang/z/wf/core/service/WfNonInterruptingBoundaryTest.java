package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 非中断型边界事件（{@code cancelActivity="false"}）。
 *
 * <p>它是"提醒但不打断"这件事唯一能写的形态：宿主任务照常办理，
 * 边界那条分支并行跑起来，两边在汇合点碰头。此前部署期报 ERROR，
 * 理由是"需要另一套状态来保持订阅存活" —— 第 12 轮发现那个理由不成立：
 * 边界订阅的存活期本来就是"宿主活跃期间"，中断型与非中断型在这一点上完全一样。
 *
 * <p>本类盯三件容易做错的事：
 * <ol>
 *   <li><b>宿主待办不许被作废</b>。非中断与中断的唯一区别就是这一条，
 *       而它一旦错了，从外面看就是"提醒把人打断了"，且流程仍然跑得通。</li>
 *   <li><b>汇合点要真的等齐</b>。边界分支先到、宿主后到（反过来也一样），
 *       两边都得在汇合点等到对方。只到齐了一半的症状是下游出现两条并行路径。</li>
 *   <li><b>订阅只触发一次</b>。触发后 job 即作废；宿主还在办时重复投递同一条消息
 *       不该再并行出一条分支（那是 {@code parallelMultiple} 的语义，本实现不支持）。</li>
 * </ol>
 */
class WfNonInterruptingBoundaryTest {

    /**
     * start → approve →（非中断消息边界）→ remind → 汇合 → afterApprove → end
     *
     * <p>汇合点用 {@code PARALLEL_GATEWAY} 而不是普通任务：{@code isJoin} 只在
     * {@code handleGateway} 里被调用，挂在任务上的多条入线会变成"进两次、建两条待办"，
     * 那不是汇合。
     */
    private static final String BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"niProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <boundaryEvent id=\"remindBoundary\" attachedToRef=\"approve\""
            + " cancelActivity=\"false\">\n"
            + "      <messageEventDefinition messageRef=\"remind\"/>\n"
            + "    </boundaryEvent>\n"
            + "    <userTask id=\"remind\" name=\"催办\" zifang:assignee=\"ops\"/>\n"
            + "    <parallelGateway id=\"join\"/>\n"
            + "    <userTask id=\"afterApprove\" name=\"办完了\" zifang:assignee=\"ops\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"join\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"remindBoundary\" targetRef=\"remind\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"remind\" targetRef=\"join\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"join\" targetRef=\"afterApprove\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"afterApprove\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 中断型对照：同一个图，只把 cancelActivity 去掉。 */
    private static final String INTERRUPTING_BPMN = BPMN
            .replace(" cancelActivity=\"false\"", "")
            .replace("id=\"niProcess\"", "id=\"intProcess\"");

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

    private String start(String xml, String key) {
        WfDefinition definition = repository.deployXml(xml, key);
        return runtime.startProcessInstance(definition, "NI-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
    }

    private String startNonInterrupting() {
        return start(BPMN, "niProcess");
    }

    private void remind(String pid) {
        runtime.triggerMessage("remind", pid, "system", null, "该催办了");
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

    private List<WfTask> allOpen(String pid) {
        return repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(20));
    }

    private WfTask theOne(String pid, String nodeId) {
        List<WfTask> tasks = openAt(pid, nodeId);
        assertEquals(1, tasks.size(), nodeId + " 上应当恰好一条待办。实际 " + tasks);
        return tasks.get(0);
    }

    /**
     * 某类 job。类型必须显式给：{@code WfJobQuery} 不给类型时会落到默认口径
     * （只捞定时器那一种），于是"这条实例上没有任何 job"会被误读成
     * "消息订阅没挂上" —— 那是两种完全不同的结论。
     */
    private List<WfJob> jobsOf(String pid, WfJobType type) {
        return repo.queryJobs(new WfJobQuery().setType(type)
                .setProcessInstanceId(pid).setPageNum(1).setPageSize(20));
    }

    private void complete(WfTask task) {
        // 走 runtime 而不是 taskService：后者只有 forceComplete（它刻意跳过
        // 办理人校验）。这里要的就是"以本人身份正常办结"的那条路径
        runtime.completeTask(task.getId(), task.getAssignee(), "办完", new HashMap<>());
    }

    private List<String> activityIdsOf(String pid) {
        List<String> ids = new ArrayList<>();
        for (WfExecution e : repo.findExecutionsByProcessInstance(pid)) {
            ids.add(e.getActivityId() + (e.isEnded() ? "(ended)" : ""));
        }
        java.util.Collections.sort(ids);
        return ids;
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

    // ==================== 核心语义 ====================

    @Test
    @DisplayName("非中断触发：宿主待办照旧开着，边界分支并行跑起来")
    void hostTaskSurvivesTheTrigger() {
        String pid = startNonInterrupting();
        assertEquals(1, openAt(pid, "approve").size(), "先有一只 boss 的待办");

        remind(pid);

        assertEquals(1, openAt(pid, "approve").size(),
                "非中断的关键就在这一条：boss 的待办必须还在。"
                        + "它被作废的话就是「提醒把人打断了」，而流程照样跑得通");
        assertEquals(1, openAt(pid, "remind").size(), "边界分支要并行建出自己的待办");
        assertEquals(2, allOpen(pid).size(), "此刻应当同时有两只待办");
        assertEquals(WfProcessStatus.ACTIVE, repo.findProcessInstance(pid).getStatus(),
                "两条分支都在推进，流程不该结束");
    }

    @Test
    @DisplayName("宿主 token 一步都不动，仍停在 approve 上")
    void hostTokenDoesNotMove() {
        String pid = startNonInterrupting();
        String hostTokenId = null;
        for (WfExecution e : repo.findExecutionsByProcessInstance(pid)) {
            if ("approve".equals(e.getActivityId())) {
                hostTokenId = e.getId();
            }
        }
        assertNotNull(hostTokenId, "启动后应当有一条 token 停在 approve 上");

        remind(pid);

        for (WfExecution e : repo.findExecutionsByProcessInstance(pid)) {
            if (hostTokenId.equals(e.getId())) {
                assertEquals("approve", e.getActivityId(),
                        "宿主 token 不能被搬到边界事件上 —— 那是中断型的做法");
                assertTrue(!e.isEnded(), "宿主 token 不该结束");
            }
        }
        assertEquals(2, aliveAt(pid).size(),
                "触发后应当是两条活跃 token：宿主那条 + 边界分支那条");
    }

    @Test
    @DisplayName("汇合点真的等齐：宿主先到要等边界分支，边界先到要等宿主")
    void joinWaitsForBothPaths() {
        String pid = startNonInterrupting();
        remind(pid);

        // 边界分支先到汇合点
        complete(theOne(pid, "remind"));
        assertTrue(openAt(pid, "afterApprove").isEmpty(),
                "宿主还在办，汇合没过，下一步不该出现。"
                        + "只到了一半的症状是下游出现两条并行路径 —— 那比走错更隐蔽");
        // 网关不建待办，所以只能看 token 停在哪儿 —— 用 openAt 查网关恒为空，
        // 那条判据本来就不可能通过
        assertTrue(aliveAt(pid).stream().anyMatch(e -> "join".equals(e.getActivityId())),
                "先到的那条应当停在汇合点等着。实际 token 停在 "
                        + activityIdsOf(pid));

        // 宿主后到，两边都齐了
        complete(theOne(pid, "approve"));
        assertEquals(1, openAt(pid, "afterApprove").size(),
                "两条路径都到了汇合点，只该往下走一条 —— 多出来的说明汇合没收口");
    }

    @Test
    @DisplayName("宿主先办结也一样：汇合要等边界分支，不许提前放行")
    void joinWaitsWhenHostArrivesFirst() {
        String pid = startNonInterrupting();
        remind(pid);

        complete(theOne(pid, "approve"));
        assertTrue(openAt(pid, "afterApprove").isEmpty(),
                "催办那条还没办完，汇合不该通过");

        complete(theOne(pid, "remind"));
        assertEquals(1, openAt(pid, "afterApprove").size(), "两边都到了，只走一条");
    }

    @Test
    @DisplayName("订阅触发一次即作废：宿主还在办时重复投递不再并行出第二条分支")
    void subscriptionIsConsumedOnce() {
        String pid = startNonInterrupting();
        // 先断言订阅在：token 一进入宿主节点就该挂上（漏挂的话消息会被静默丢弃）
        assertEquals(1, jobsOf(pid, WfJobType.MESSAGE).size(),
                "触发前应当有一条消息订阅。实际 " + jobsOf(pid, WfJobType.MESSAGE));
        assertEquals("remindBoundary", jobsOf(pid, WfJobType.MESSAGE).get(0).getElementId());

        remind(pid);
        assertEquals(0, jobsOf(pid, WfJobType.MESSAGE).size(),
                "非中断触发后这条订阅即作废 —— 重复触发是 parallelMultiple 的语义，"
                        + "本实现不支持，所以这里必须已经没有订阅了");

        // 重复投递同一条消息：不得并行出第二条催办待办。
        // 引擎全局契约是「没有等待者就抛错」（否则消息名打错会一直潜伏到
        // 某天没人再等为止），"已经触发过"也是没有等待者的一种 ——
        // 为它开特例反而会让调用方分不清"没人等"与"等过了"。
        // 所以这里断言的是「抛错且流程纹丝不动」，不是「安静地什么都不发生」
        List<WfTask> remindsBefore = openAt(pid, "remind");
        assertThrows(WfEngineException.class,
                () -> runtime.triggerMessage("remind", pid, "system", null, "再来一次"),
                "重复触发属于 parallelMultiple 语义，本实现不支持，"
                        + "所以它应当像「没有等待者」一样报出来，而不是安静地成功");
        assertEquals(remindsBefore.size(), openAt(pid, "remind").size(),
                "抛错的同时不得并行出第二条催办待办。实际 " + openAt(pid, "remind"));
        assertEquals(1, openAt(pid, "approve").size(), "宿主那条也一切如常");
    }

    @Test
    @DisplayName("触发要在评论里留一条：那是审计要问「当时到底发生了什么」的地方")
    void triggerIsRecordedAsAComment() {
        // 与上一条是**两个不同的产物**：轨迹（活动实例）由 leave() 记，
        // 评论（WfComment）由 fireEventBoundary 显式写。
        // 只验轨迹的话，把写评论那行删掉测试照样全绿 —— 那行就成了无人验证的代码
        String pid = startNonInterrupting();
        remind(pid);

        List<WfComment> comments = repo.findComments(pid);
        assertTrue(comments.size() >= 1,
                "触发必须留一条评论。实际 " + comments);
        boolean found = false;
        for (WfComment c : comments) {
            if (String.valueOf(c.getContent()).contains("remindBoundary")) {
                found = true;
            }
        }
        assertTrue(found, "评论里要写明触发的是哪个边界事件。实际 " + comments);
    }

    @Test
    @DisplayName("边界事件在轨迹上留一条，且写明是被什么触发的")
    void boundaryVisitIsRecordedOnTheTrail() {
        String pid = startNonInterrupting();
        remind(pid);

        boolean seen = false;
        for (WfActivityInstance act : runtime.getTrail(pid)) {
            if ("remindBoundary".equals(act.getActivityId())) {
                seen = true;
                assertTrue(String.valueOf(act.getOutcome()).contains("remind")
                                || String.valueOf(act.getOutcome()).contains("催办"),
                        "轨迹上要写得出这次是被什么触发的。实际 " + act.getOutcome());
            }
        }
        assertTrue(seen, "边界事件必须留痕 —— 它是并行分出去的一条分支，"
                + "不留痕的话事后只看轨迹会以为什么都没发生");
    }

    @Test
    @DisplayName("两条分支都办完后流程正常结束")
    void processCompletesAfterBothPaths() {
        String pid = startNonInterrupting();
        remind(pid);
        complete(theOne(pid, "remind"));
        complete(theOne(pid, "approve"));
        complete(theOne(pid, "afterApprove"));

        WfProcessInstance instance = repo.findProcessInstance(pid);
        assertEquals(WfProcessStatus.COMPLETED, instance.getStatus(),
                "两条路径都办完就该结束。实际 " + instance.getStatus());
        assertTrue(aliveAt(pid).isEmpty(), "不该剩下任何活跃 token");
    }

    @Test
    @DisplayName("对照：中断型在同一张图上会作废宿主待办（确认两条路径真的不同）")
    void interruptingStillCancelsTheHostTask() {
        String pid = start(INTERRUPTING_BPMN, "intProcess");
        runtime.triggerMessage("remind", pid, "system", null, "撤销");

        assertTrue(openAt(pid, "approve").isEmpty(),
                "中断型必须作废宿主待办。这条断言同时是「非中断与中断确实走了不同分支」"
                        + "的证据 —— 两者若行为相同，前面那些用例就都失去意义了");
    }

    @Test
    @DisplayName("非中断分支的 token 与宿主是父子：汇合判定据此认它们同批")
    void branchTokenIsParentedToTheHost() {
        String pid = startNonInterrupting();
        remind(pid);

        String hostId = null;
        String branchId = null;
        for (WfExecution e : repo.findExecutionsByProcessInstance(pid)) {
            if ("approve".equals(e.getActivityId())) {
                hostId = e.getId();
            } else if ("remind".equals(e.getActivityId())) {
                branchId = e.getId();
            }
        }
        assertNotNull(hostId);
        assertNotNull(branchId);
        String branchParent = null;
        for (WfExecution e : repo.findExecutionsByProcessInstance(pid)) {
            if (branchId.equals(e.getId())) {
                branchParent = e.getParentId();
            }
        }
        assertEquals(hostId, branchParent,
                "边界分支的 token 必须挂在宿主之下 —— samePeer 把父子算同批，"
                        + "挂成兄弟的话汇合点认不出它们，会各走各的");
    }

    // ==================== 定时器型非中断 ====================

    @Test
    @DisplayName("非中断定时器边界：到点提醒但不打断（最常见的「超时催办」）")
    void nonInterruptingTimerBoundary() {
        String xml = BPMN
                .replace("<messageEventDefinition messageRef=\"remind\"/>",
                        "<timerEventDefinition><timeDuration>PT5M</timeDuration>"
                                + "</timerEventDefinition>")
                .replace("id=\"niProcess\"", "id=\"niTimerProcess\"");
        String pid = start(xml, "niTimerProcess");
        assertEquals(1, openAt(pid, "approve").size());

        int fired = new WfJobService(repo, runtime)
                .executeDueJobs(new Date(System.currentTimeMillis() + 600_000L));
        assertEquals(1, fired, "到点后应当触发一次");

        assertEquals(1, openAt(pid, "approve").size(),
                "到点了 boss 的待办还在 —— 这正是「超时只提醒、不打断」");
        assertEquals(1, openAt(pid, "remind").size(), "催办待办建出来了");

        complete(theOne(pid, "remind"));
        complete(theOne(pid, "approve"));
        assertEquals(1, openAt(pid, "afterApprove").size(), "汇合放行一条");
    }

    @Test
    @DisplayName("没到点的定时器不触发（与中断型共用同一个执行器，不能被提前消费）")
    void nonInterruptingTimerDoesNotFireEarly() {
        String xml = BPMN
                .replace("<messageEventDefinition messageRef=\"remind\"/>",
                        "<timerEventDefinition><timeDuration>PT5M</timeDuration>"
                                + "</timerEventDefinition>")
                .replace("id=\"niProcess\"", "id=\"niTimer2Process\"");
        String pid = start(xml, "niTimer2Process");

        assertEquals(0, new WfJobService(repo, runtime)
                .executeDueJobs(new Date(System.currentTimeMillis() - 600_000L)),
                "没到点就触发那不是定时器");
        assertTrue(openAt(pid, "remind").isEmpty());
        assertEquals(1, openAt(pid, "approve").size());
    }

    // ==================== 部署期 ====================

    @Test
    @DisplayName("非中断边界没有出线时报错：否则那条分支永远停着，没人管")
    void nonInterruptingWithoutOutgoingFlowIsRejected() {
        String noOut = BPMN.replace(
                "    <sequenceFlow id=\"f3\" sourceRef=\"remindBoundary\" targetRef=\"remind\"/>\n", "");
        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> repository.deployXml(noOut, "niProcess"));
        // 断言"非中断"而不是"出线"：没有出线会让若干条别的规则也报错
        // （remind 节点变得不可达之类），而它们的措辞里同样可能出现"出线"。
        // 只有本条规则会说"非中断"，用它才唯一
        assertTrue(e.getMessage().contains("非中断"),
                "报错要说清是非中断型边界缺出线。实际 " + e.getMessage());
    }

    @Test
    @DisplayName("非中断 + parallelMultiple 在非多实例宿主上放行（只报 WARN）")
    void nonInterruptingWithParallelMultipleDeploys() {
        // 第 33 轮改判：原先断言「非中断 + parallelMultiple 报 ERROR（只支持单次触发）」。
        // 那条规则把 parallelMultiple 解释成"重复触发"，而 BPMN 2.0 里它是
        // 「多实例时每个实例各有各的边界事件」—— 拒绝一个规范里真实存在的属性，
        // 而理由还把属性讲反了。宿主 remind 是普通 userTask，只有一个实例可挂，
        // 该属性在这里没有意义、行为与不写一致 ⇒ 放行 + WARN。
        // 非中断型与 parallelMultiple 在多实例宿主上同时成立的语义，
        // 由 WfParallelMultipleBoundaryTest 覆盖。
        String both = BPMN.replace(" cancelActivity=\"false\"", " parallelMultiple=\"true\"");
        WfDefinition deployed = repository.deployXml(both, "niProcess");
        assertNotNull(deployed, "宿主不是多实例时不该挡部署");
        assertTrue(deployed.node("remindBoundary").isParallelMultiple(),
                "属性要保留下来：丢在解析阶段的话，图上写着 parallelMultiple 而引擎当作没写");
    }

    @Test
    @DisplayName("cancelActivity 与 parallelMultiple 解析得对，且深拷贝后不丢")
    void boundaryFlagsSurviveRoundTrip() {
        // 注意这条**验不到 codec**：InMemoryWorkflowPersistence 的深拷贝走
        // Java 序列化（见该类的 copy 方法），绕开了 WfDefinitionCodec。
        // codec 的往返覆盖在 WfDefinitionRoundTripTest 里（那条同时跑 JDBC 与内存
        // 两套实现，JDBC 那套才真的过 codec）。
        WfDefinition parsed = new WfXmlParser().parse(BPMN);
        repo.saveDefinition(parsed);
        WfDefinition reloaded = repo.findDefinition("niProcess", parsed.getVersion());
        assertNotNull(reloaded, "定义应能取回");
        assertTrue(reloaded.node("remindBoundary").isNonInterrupting(),
                "读回后必须仍是非中断型 —— 漏掉它的话 JDBC 部署的流程里"
                        + "这个提醒会变成把人打断，且不报错");
        assertTrue(!reloaded.node("remindBoundary").isParallelMultiple());
    }
}
