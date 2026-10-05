package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 实例迁移（move）—— 把在途流程的 token 挪到指定节点。
 *
 * <p>本类盯五件错了都不报错的事：
 * <ol>
 *   <li><b>等事件 / 等定时器的流程也能迁</b>：它们没有待办，
 *       {@code WfTaskService#jump} 的入口是任务 id，对它们完全无效 ——
 *       而"这条单等太久了，改走人工"恰恰最常发生在这种单上。</li>
 *   <li><b>迁走之后源节点上的等待必须撤干净</b>：待办、该 token 上的 job、
 *       到达记录，三样缺一样就会留下"一个 token 被两样东西盯着"的状态 ——
 *       事件照常到达会把已迁走的分支再走一遍，同一个节点被走两遍。</li>
 *   <li><b>指定源节点时只迁那一条</b>：并行分支走到一半时把整棵执行树端掉
 *       通常不是调用方的本意。</li>
 *   <li><b>迁移必须留痕</b>：评论 + 轨迹写清"从哪迁到哪、谁迁的、为什么"，
 *       否则事后看到一条不连续的轨迹无从判断发生了什么。</li>
 *   <li><b>三种拒绝要报得能操作</b>：终态实例、目标节点不存在、没找到可迁的 token。</li>
 * </ol>
 */
class WfInstanceMigrationTest {

    /**
     * 事件网关 + 定时器边界 + 人工节点混在一起。
     *
     * <p>三种"等着"的状态各占一段：等消息（网关分支）、等超时（定时器边界）、
     * 人工待办。这样每种等待被迁移时的处理都被覆盖到，而不是只测最容易的那种。
     */
    private static final String BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"migProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <eventBasedGateway id=\"eg\"/>\n"
            + "    <intermediateCatchEvent id=\"waitMsg\" name=\"等主管批\">\n"
            + "      <messageEventDefinition messageRef=\"bossApprove\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <intermediateCatchEvent id=\"waitSignal\" name=\"等回执\">\n"
            + "      <signalEventDefinition signalRef=\"erpDone\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <userTask id=\"onApprove\" name=\"批了\" zifang:assignee=\"ops\"/>\n"
            + "    <userTask id=\"onErp\" name=\"回执到了\" zifang:assignee=\"erp\"/>\n"
            + "    <userTask id=\"manual\" name=\"转人工\" zifang:assignee=\"ceo\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <endEvent id=\"e2\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"eg\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"eg\" targetRef=\"waitMsg\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"eg\" targetRef=\"waitSignal\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"waitMsg\" targetRef=\"onApprove\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"waitSignal\" targetRef=\"onErp\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"onApprove\" targetRef=\"manual\"/>\n"
            + "    <sequenceFlow id=\"f7\" sourceRef=\"onErp\" targetRef=\"manual\"/>\n"
            + "    <sequenceFlow id=\"f8\" sourceRef=\"manual\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /**
     * 三人会签 + 一条出口。
     *
     * <p>存在的意义是造出<b>同一个节点上有多条 token</b> 的局面。
     * 这不是边角：多实例节点每次循环展开一条独立 token（见 WfEngine#enterMultiInstance），
     * 而「撤销源节点上的待办」这个动作天然是按节点做的 ——
     * 一旦 jump 也走那条路，它撤掉的就不只是自己那一条。
     */
    private static final String MI_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"miJumpProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"ms\"/>\n"
            + "    <userTask id=\"counterSign\" name=\"三人会签\" zifang:assignee=\"${loopAssignee}\">\n"
            + "      <multiInstanceLoopCharacteristics>\n"
            + "        <loopCardinality>3</loopCardinality>\n"
            + "      </multiInstanceLoopCharacteristics>\n"
            + "    </userTask>\n"
            + "    <userTask id=\"escalate\" name=\"升级处理\" zifang:assignee=\"boss\"/>\n"
            + "    <endEvent id=\"me\"/>\n"
            + "    <sequenceFlow id=\"mf1\" sourceRef=\"ms\" targetRef=\"counterSign\"/>\n"
            + "    <sequenceFlow id=\"mf2\" sourceRef=\"counterSign\" targetRef=\"escalate\"/>\n"
            + "    <sequenceFlow id=\"mf3\" sourceRef=\"escalate\" targetRef=\"me\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfTaskService tasks;
    private WfSubscriptionService subscriptions;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        WfEngine engine = new WfEngine();
        runtime = new WfRuntimeService(repository, repo, engine, new WfHookDispatcher());
        tasks = new WfTaskService(repository, repo, runtime, new WfHookDispatcher());
        subscriptions = new WfSubscriptionService(repo, repository);
    }

    private String start() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(BPMN));
        return runtime.startProcessInstance(definition, "MIG-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
    }

    /**
     * 办结该实例上全部待办。
     *
     * <p>为什么这两条"流程能走完"的用例必须<b>先迁全部 token</b>：只迁一条分支时，
     * 另一条还停在捕获事件上等信号 —— 它没有待办，办结任务碰不到它，
     * 流程当然不会结束。那个不结束是<b>正确</b>行为，与迁移无关；
     * 直接拿它断言会得到一个与被测行为无关的红。
     */
    private void completeAll(String pid, String userId) {
        for (WfTask task : openTasks(pid)) {
            runtime.completeTask(task.getId(), userId, "办结", null);
        }
    }

    private List<WfTask> openTasks(String pid) {
        return repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(50));
    }

    // ==================== 等事件时也能迁 ====================

    @Test
    @DisplayName("等消息的流程能迁走 —— 它没有待办，jump 对它完全无效")
    void waitingOnMessageCanBeMigrated() {
        String pid = start();
        assertTrue(openTasks(pid).isEmpty(), "起点就没有待办");
        assertEquals(2, subscriptions.subscriptionsOf(pid).size());

        runtime.move(pid, "manual", "waitMsg", "boss", "等太久了，改人工", null);

        assertEquals(1, openTasks(pid).size(), "迁到人工节点就该有一条待办");
        assertEquals("ceo", openTasks(pid).get(0).getAssignee());
    }

    @Test
    @DisplayName("迁走之后源节点上的订阅必须撤掉")
    void sourceSubscriptionsAreCancelled() {
        String pid = start();
        runtime.move(pid, "manual", "waitMsg", "boss", "改人工", null);

        List<String> waitingOn = new java.util.ArrayList<>();
        for (com.zifang.z.wf.core.view.WfSubscriptionView view : subscriptions.subscriptionsOf(pid)) {
            waitingOn.add(view.getActivityId());
        }
        // 只迁了 waitMsg，waitSignal 那条分支根本没被碰过，必须继续等着。
        // 断言 waitMsg 不在里面 —— 它若还在，迟到的消息就会把已迁走的分支再走一遍
        assertFalse(waitingOn.contains("waitMsg"),
                "迁走的分支不能再有人等。实际还在等的是 " + waitingOn);
        assertTrue(waitingOn.contains("waitSignal"), "没被迁走的分支必须继续等");
        assertEquals("ceo", openTasks(pid).get(0).getAssignee());
    }

    @Test
    @DisplayName("只迁指定的源 token，另一条分支继续等 —— 不能把整棵执行树端掉")
    void onlySpecifiedSourceIsMigrated() {
        String pid = start();
        runtime.move(pid, "manual", "waitMsg", "boss", "只迁这一条", null);

        // 另一条分支还在等信号：它既没被迁走，也没被作废
        assertEquals(1, subscriptions.subscriptionsOf(pid).size(),
                "只迁了 waitMsg，waitSignal 应当继续等着");
        assertEquals("waitSignal", subscriptions.subscriptionsOf(pid).get(0).getActivityId());
        // 迁走的那条建了人工待办
        assertEquals(1, openTasks(pid).size());
    }

    @Test
    @DisplayName("不给源就迁全部 token —— 并行分支的常规用法")
    void allTokensAreMigratedWhenSourceIsAbsent() {
        String pid = start();
        runtime.move(pid, "manual", null, "boss", "整棵都迁", null);

        assertTrue(subscriptions.subscriptionsOf(pid).isEmpty(),
                "不给源表示全迁，两条分支的订阅都该撤掉");
        // 两条分支都落到 manual，于是有两条待办
        assertEquals(2, openTasks(pid).size(),
                "两条分支都迁到同一个人工节点，应当各有一条待办。实际 " + openTasks(pid).size());
    }

    // ==================== 事件不能把已迁走的分支走第二遍 ====================

    @Test
    @DisplayName("迁走之后再投递原来的事件：不生效，也不会把流程走第二遍")
    void staleEventDoesNotAdvanceMigratedBranch() {
        String pid = start();
        runtime.move(pid, "manual", "waitMsg", "boss", "改人工", null);
        int tasksAfterMove = openTasks(pid).size();

        // 订阅已在迁移时撤掉，所以这条消息匹配不到任何等待者 —— 必须报"没有等待"，
        // 而不是静默地把已迁走的那条分支再走一遍
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.triggerMessage("bossApprove", pid, "boss", null, "迟到的消息"));
        assertTrue(ex.getMessage().contains("bossApprove"),
                "报错要说清是哪条消息没等到。实际 " + ex.getMessage());
        assertEquals(tasksAfterMove, openTasks(pid).size(),
                "迟到的消息不能让流程多走一格 —— 那个节点已经被迁到 manual 上去了");
    }

    // ==================== 人工待办上迁移 ====================

    @Test
    @DisplayName("待办上迁移：源待办作废，不留一条还挂着的假待办")
    void openTaskOnSourceIsCancelled() {
        String pid = start();
        runtime.move(pid, "manual", "waitMsg", "boss", "改人工", null);
        WfTask source = openTasks(pid).get(0);
        assertEquals("manual", source.getDefinitionId());

        runtime.move(pid, "onErp", "manual", "boss", "再换一步", null);

        List<WfTask> open = openTasks(pid);
        assertEquals(1, open.size(), "源待办必须作废，不能与新待办并存");
        assertEquals("erp", open.get(0).getAssignee());
        // 作废的那条要留在库里且状态是 CANCELLED：审批方据此知道"这单被谁改过"
        WfTask cancelled = repo.findTask(source.getId());
        assertNotNull(cancelled, "作废的待办不能被删掉");
        assertEquals(WfTask.Status.CANCELLED, cancelled.getStatus());
    }

    // ==================== 留痕 ====================

    @Test
    @DisplayName("迁移要留评论与轨迹，且写清从哪迁到哪、谁迁的")
    void migrationIsTraceable() {
        String pid = start();
        runtime.move(pid, "manual", "waitMsg", "boss", "等太久了", null);

        boolean sawComment = false;
        for (WfComment comment : repo.findComments(pid)) {
            if ("move".equals(comment.getType())) {
                sawComment = true;
                assertEquals("boss", comment.getUserId(), "谁迁的要能查到");
                assertTrue(comment.getContent().contains("manual"),
                        "评论要写清迁到哪。实际 " + comment.getContent());
                assertTrue(comment.getContent().contains("等太久了"), "原因要一起记，否则无法追责");
            }
        }
        assertTrue(sawComment, "迁移必须留一条 move 评论。实际 " + repo.findComments(pid));

        boolean sawTrail = false;
        for (WfActivityInstance activity : repo.findActivityInstances(pid)) {
            if ("waitMsg".equals(activity.getActivityId())
                    && activity.getOutcome() != null
                    && activity.getOutcome().contains("manual")) {
                sawTrail = true;
            }
        }
        assertTrue(sawTrail, "轨迹上要能看出这一步是迁移来的、迁去了哪");
    }

    // ==================== 拒绝 ====================

    @Test
    @DisplayName("终态实例不可迁移")
    void terminalInstanceCannotBeMigrated() {
        String pid = start();
        runtime.move(pid, "manual", null, "boss", "整棵都迁", null);
        completeAll(pid, "ceo");
        assertTrue(repo.findProcessInstance(pid).getStatus().isTerminal(),
                "两条分支都办结后流程应当结束 —— 它不结束说明还有 token 卡在别处");

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.move(pid, "onErp", "manual", "boss", "再迁", null));
        assertTrue(ex.getMessage().contains("终态"), "报错要说清为什么不能迁。实际 " + ex.getMessage());
    }

    @Test
    @DisplayName("目标节点不在定义里：报错并指出是哪个定义")
    void unknownTargetIsRejected() {
        String pid = start();
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.move(pid, "noSuchNode", "waitMsg", "boss", "乱填", null));
        assertTrue(ex.getMessage().contains("noSuchNode"));
        assertTrue(ex.getMessage().contains("migProcess"), "报错要带上定义 key，否则无从判断查的是哪份流程");
    }

    @Test
    @DisplayName("源节点上已经没有活 token：报错，不静默什么都不做")
    void noLiveTokenAtSourceIsRejected() {
        String pid = start();
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.move(pid, "manual", "onApprove", "boss", "源写错了", null));
        assertTrue(ex.getMessage().contains("onApprove"),
                "报错要说清找的是哪个节点。实际 " + ex.getMessage());
    }

    @Test
    @DisplayName("目标与当前节点相同：报错并点出多半是漏传了源")
    void sameSourceAndTargetIsRejected() {
        String pid = start();
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.move(pid, "waitMsg", "waitMsg", "boss", "没想清楚", null));
        assertTrue(ex.getMessage().contains("漏传"), "报错要给可操作的提示。实际 " + ex.getMessage());
    }

    @Test
    @DisplayName("迁移后的流程能一路走到结束 —— 迁移没有把流程改坏")
    void migratedProcessCanStillFinish() {
        String pid = start();
        runtime.move(pid, "manual", null, "boss", "整棵都迁", null);
        completeAll(pid, "ceo");

        WfProcessInstance instance = repo.findProcessInstance(pid);
        assertTrue(instance.getStatus().isTerminal(),
                "迁到 manual 之后应当能正常走完。实际 " + instance.getStatus());
    }

    // ==================== 迁移带过去的上下文 ====================

    @Test
    @DisplayName("迁移可以顺带带变量过去 —— 不带的话新节点的判别式多半还停在旧值")
    void migrationCanCarryVariables() {
        String pid = start();
        Map<String, Object> carried = new HashMap<String, Object>();
        carried.put("days", 7);
        carried.put("reason", "改流程");

        runtime.move(pid, "manual", "waitMsg", "boss", "连同上下文一起迁", carried);

        Map<String, Object> stored = repo.findProcessInstance(pid).getVariables();
        // 不是查 context.getProcessInstance()（那只是内存对象，合并了不等于落了库），
        // 而是回读一次：迁移改了变量却没存，症状是"接口刚返回是对的，刷新就没了"
        assertEquals(7, stored.get("days"), "迁移带过去的变量必须落库。实际 " + stored.get("days"));
        assertEquals("改流程", stored.get("reason"));
    }

    @Test
    @DisplayName("整棵迁到结束节点：流程当场结束，不留 token 悬在图外")
    void moveEverythingToEndEventCompletesTheProcess() {
        String pid = start();
        // 两条 token 都迁到 e1。结束事件不带待办也不带 job，
        // 所以这里唯一能让流程进终态的路径就是 resolveCompletion 那道兜底
        runtime.move(pid, "e1", null, "boss", "整单作废", null);

        WfProcessInstance instance = repo.findProcessInstance(pid);
        assertTrue(instance.getStatus().isTerminal(),
                "两条 token 都迁到结束事件后流程应当完成。实际 " + instance.getStatus());
        for (WfExecution execution : repo.findExecutionsByProcessInstance(pid)) {
            assertTrue(execution.isEnded(),
                    "迁到结束事件的 token 必须已结束，不能还挂在图外。实际 activityId="
                            + execution.getActivityId() + " state=" + execution.getState());
        }
    }

    // ==================== 跳转 ====================
    //
    // 下面两条刻意**不注册任何自定义行为**：jump 与 move 共用 migrateToken，
    // 而「引擎复用」那件事已由 WfJumpEngineReuseTest 单独盯着。
    // 混在一起会让同一个用例同时依赖两个机制，修好一个坏掉另一个时分不清是谁的锅。

    @Test
    @DisplayName("跳到人工节点必须真的建出待办 —— 否则这次跳转等于白做")
    void jumpToManualNodeCreatesTodo() {
        String pid = start();
        runtime.move(pid, "onApprove", "waitMsg", "boss", "先转人工", null);
        WfTask first = openTasks(pid).get(0);
        assertEquals("onApprove", first.getDefinitionId(), "前置条件：待办确实在 onApprove 上");

        tasks.jump(first.getId(), "boss", "manual", "这一步我不管了");

        List<WfTask> open = openTasks(pid);
        // 期望值 1 与 0 都被这条卡住：用 leave 推进的话 manual 会被直接走过，
        // 流程一路到结束，这里就是 0 —— 而「跳过去没有待办」正是 jump 最主要的用法失效
        assertEquals(1, open.size(),
                "跳到人工节点必须留下一个待办。实际 " + open.size()
                        + " 个（用 leave 推进会直接跳过目标节点，不建待办）");
        assertEquals("manual", open.get(0).getDefinitionId());
        assertEquals("ceo", open.get(0).getAssignee(), "待办要派给目标节点的办理人，不是给操作人");

        WfTask jumped = repo.findTask(first.getId());
        assertNotNull(jumped, "原待办不能被删掉，审批方要能看到它被谁改过");
        assertEquals(WfTask.Status.COMPLETED, jumped.getStatus(),
                "原待办是被人办掉的（jump 语义），不是被作废的 —— "
                        + "作废发的是「被边界事件打断」通知，记成办结会让通知内容撒谎");
    }

    @Test
    @DisplayName("跳转只带走自己那条 token —— 同节点上别人的待办不能被一起撤掉")
    void jumpKeepsSiblingTasksOfSameNode() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(MI_BPMN));
        String pid = runtime.startProcessInstance(definition, "MIJ-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
        List<WfTask> before = openTasks(pid);
        assertEquals(3, before.size(),
                "前置条件：多实例应当展开成 3 条 token，各带一个待办。实际 " + before.size());
        WfTask mine = before.get(0);

        tasks.jump(mine.getId(), "boss", "escalate", "我这轮不用签了");

        List<WfTask> after = openTasks(pid);
        // 期望值 3：跳走的那条去了 escalate 留下一个新待办，另外两条仍留在 counterSign。
        // 少一个就说明按节点撤待办时把别人的也撤了 —— 那两条 token 会永远停在原地，
        // 既办不完也查不出来（它们没有待办，没有任何入口能推进它们）
        assertEquals(3, after.size(),
                "跳走一条之后应当剩 3 条待办。实际 " + after.size()
                        + " 个 —— 少于 3 说明把同节点上别人的待办一起撤了");
        assertEquals(1, countAt(after, "escalate"), "跳过去的那条应当在 escalate 上留一个待办");
        assertEquals(2, countAt(after, "counterSign"), "留在原节点的两条待办必须都还在");

        // 逐条核对而不是只数个数：数对了但人错了（比如撤的是别人的、留的是我的）
        // 同样是一种错，而聚合值对此没有区分力
        List<String> keptExecutionIds = new ArrayList<String>();
        for (WfTask task : after) {
            if ("counterSign".equals(task.getDefinitionId())) {
                keptExecutionIds.add(task.getExecutionId());
            }
        }
        assertFalse(keptExecutionIds.contains(mine.getExecutionId()),
                "我跳走的那条不该还留在 counterSign 上");
        for (WfTask task : before) {
            if (!task.getId().equals(mine.getId())) {
                assertTrue(keptExecutionIds.contains(task.getExecutionId()),
                        "别人的待办被撤了：token " + task.getExecutionId() + " 上没有待办了");
            }
        }
    }

    private int countAt(List<WfTask> taskList, String activityId) {
        int count = 0;
        for (WfTask task : taskList) {
            if (activityId.equals(task.getDefinitionId())) {
                count++;
            }
        }
        return count;
    }

    // ==================== 跳转的拒绝 ====================

    @Test
    @DisplayName("跳转目标不在定义里：报错并点名是哪个节点")
    void jumpToUnknownTargetIsRejected() {
        String pid = start();
        runtime.move(pid, "onApprove", "waitMsg", "boss", "先转人工", null);
        WfTask first = openTasks(pid).get(0);

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> tasks.jump(first.getId(), "boss", "noSuchNode", "手滑填错了"));
        assertTrue(ex.getMessage().contains("noSuchNode"),
                "报错要点名是哪个节点。实际 " + ex.getMessage());
        // 报错必须发生在动状态之前：源待办若已被办结，这条调用就等于"报错但操作已生效"
        assertEquals(WfTask.Status.ASSIGNED, repo.findTask(first.getId()).getStatus(),
                "校验没过就不该改动任何状态 —— 报错却已把待办办结，"
                        + "调用方只会看到 500，而那条待办再也点不开了");
    }

}
