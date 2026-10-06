package com.zifang.z.wf.core.engine;

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
import com.zifang.z.wf.core.definition.WfDefinitionValidator;
import com.zifang.z.wf.core.definition.WfValidationIssue;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;

/**
 * 条件式捕获事件（{@code conditionalEventDefinition}）。
 *
 * <p>它回答的是审批系统里最常见的一个需求：
 * 「<b>超时 3 天 <i>而且</i>金额超过 1 万才提醒</b>」。
 * 这个「而且」在 BPMN 的互斥规则里写不出来 ——
 * {@code <intermediateCatchEvent>} 只挂一种事件定义，
 * 所以本实现把它当成<b>叠加</b>关系：事件类型回答「等什么」，条件回答「够不够格」。
 *
 * <p>本类盯四件错了都不报错的事：
 * <ol>
 *   <li><b>求值点在事件到达那一刻，不在建订阅时</b>。
 *       审批金额、已过天数往往在等待期间才定下来，
 *       建订阅时求值等于用还没发生的事实决定分支，
 *       症状是「金额明明超了却不提醒」。</li>
 *   <li><b>条件不成立时订阅必须还在</b>。删掉它等于让这一格永远等不到下一次 ——
 *       症状是「第一次没提醒，之后永远不提醒」。</li>
 *   <li><b>条件不成立时不得作废兄弟分支</b>：它没赢，不能替别人赢。</li>
 *   <li><b>条件为假要留痕</b>：「提醒没来」必须能从轨迹上读出「条件当时是假的」，
 *       而不是让人自己去猜那个变量当时是多少。</li>
 * </ol>
 */
class WfConditionalEventTest {

    /**
     * 两选一：等主管批（消息，带条件）/ 等回执（信号，不带条件）。
     *
     * <p>主管批这一格要求「金额 > 10000 且还没批过」。这是审批场景的典型形状：
     * 小额的单子等主管批没有意义，但流程得继续等着 ——
     * 条件不成立时**不当作没这一格**，而是等下一次事件。
     */
    private static final String COND_RACE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"condRace\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <eventBasedGateway id=\"eg\"/>\n"
            + "    <intermediateCatchEvent id=\"waitBoss\" name=\"等主管批\">\n"
            + "      <messageEventDefinition messageRef=\"bossApprove\"/>\n"
            + "      <conditionalEventDefinition>\n"
            + "        <condition>${amount &gt; 10000 &amp;&amp; !approved}</condition>\n"
            + "      </conditionalEventDefinition>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <intermediateCatchEvent id=\"waitErp\" name=\"等回执\">\n"
            + "      <signalEventDefinition signalRef=\"erpCallback\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <userTask id=\"doIt\" name=\"办\" zifang:assignee=\"alice\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"eg\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"eg\" targetRef=\"waitBoss\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"eg\" targetRef=\"waitErp\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"waitBoss\" targetRef=\"doIt\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"waitErp\" targetRef=\"doIt\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"doIt\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /**
     * 条件里是一句<b>语法错</b>的表达式 —— 求值必须抛异常，而异常判「不成立」而不是放行。
     *
     * <p><b>刻意用语法错而不是「引用一个不存在的变量」</b>：
     * 后者压根不会抛异常（{@code WfExpressionEvaluator} 内部对未定义变量已经
     * fail-closed，判 false 就返回了），所以那条路上运行时那道 catch 根本进不去 ——
     * 拿它当判据会让「异常时放行」这条变异打不红，而且打不红的原因是
     * 变异与名字不符（上游已经有一道 fail-closed），不是判据没区分力。
     */
    private static final String BAD_COND_BPMN = COND_RACE_BPMN
            .replace("${amount &gt; 10000 &amp;&amp; !approved}", "${amount &gt;}")
            .replace("id=\"condRace\"", "id=\"badCond\"");

    /**
     * 定时器分支 + 条件：「超时 3 天<b>而且</b>金额超过 1 万才提醒」的形状。
     *
     * <p>用 {@code PT1S} 而不是 {@code PT3D}：这条用例要的是"到点那一刻条件为不为真"，
     * 不是"等了多久"。写长周期就得靠改系统时间来触发，那既慢又不稳定。
     */
    private static final String COND_TIMER_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"condTimer\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <eventBasedGateway id=\"eg\"/>\n"
            + "    <intermediateCatchEvent id=\"waitRemind\" name=\"超时提醒\">\n"
            + "      <timerEventDefinition><timeDuration>PT1S</timeDuration></timerEventDefinition>\n"
            + "      <conditionalEventDefinition>\n"
            + "        <condition>${amount &gt; 10000}</condition>\n"
            + "      </conditionalEventDefinition>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <intermediateCatchEvent id=\"waitErp\" name=\"等回执\">\n"
            + "      <signalEventDefinition signalRef=\"erpCallback\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <userTask id=\"doIt\" name=\"办\" zifang:assignee=\"alice\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"eg\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"eg\" targetRef=\"waitRemind\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"eg\" targetRef=\"waitErp\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"waitRemind\" targetRef=\"doIt\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"waitErp\" targetRef=\"doIt\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"doIt\" targetRef=\"e\"/>\n"
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

    @Test
    @DisplayName("条件不成立：这次事件不算它赢，订阅还在，兄弟分支也没被作废")
    void conditionFalseKeepsTheBranchWaiting() {
        String pid = start(COND_RACE_BPMN, new HashMap<String, Object>());
        // 金额不够 ⇒ 主管批这一格不该赢
        setVariable(pid, "amount", 500);

        // 投递方**必须拿到一条说清原因的错**：它分不出「没人在等」与
        // 「有人在等但条件不成立」，而这两件事的处置完全相反 ——
        // 前者去建流程，后者去看那个变量当时是多少。
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.triggerMessage("bossApprove", pid, "boss", null, "批了"),
                "条件不成立时若安静地返回，投递方无从知道这条消息为什么没起作用");
        assertFalse(ex.getMessage().contains("没有等待消息"),
                "不能落到「没有等待消息」那句上 —— 它把排障方向从「条件不满足」"
                        + "带偏到「没人订阅」: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("条件"),
                "报错要点名是条件的问题: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("amount"),
                "报错要带上条件原文，否则还是得自己去翻 BPMN: " + ex.getMessage());

        assertEquals(2, eventCatchJobsOf(pid).size(),
                "条件不成立时把订阅删了 —— 这一格就永远等不到下一次了。"
                        + "症状是「第一次没提醒，之后永远不提醒」");
        assertEquals(0, openTaskCount(pid),
                "条件不成立时流程还停在竞速里，一个待办都不该有 —— "
                        + "多出来的那个待办意味着有人替它赢了");
        assertTrue(hasCommentContaining(pid, "条件不成立"),
                "「提醒没来」必须能从轨迹上读出「条件当时是假的」，"
                        + "而不是让人自己去猜那个变量当时是多少。评论: " + commentsOf(pid));
    }

    @Test
    @DisplayName("条件成立：这一格赢，其余分支作废")
    void conditionTrueLetsTheBranchWin() {
        String pid = start(COND_RACE_BPMN, new HashMap<String, Object>());
        setVariable(pid, "amount", 20000);

        runtime.triggerMessage("bossApprove", pid, "boss", null, "批了");

        assertEquals(0, eventCatchJobsOf(pid).size(),
                "赢的那一条与落选分支的订阅都应清空 —— 留着的分支会在下一次事件到达时"
                        + "把流程静默地多推一遍");
        assertEquals(1, openTaskCount(pid), "条件成立就该推进到 userTask");
        assertTrue(hasCommentContaining(pid, "走事件网关分支 waitBoss"),
                "轨迹上要写清是哪一格赢的。评论: " + commentsOf(pid));
    }

    @Test
    @DisplayName("条件在等待期间变化 ⇒ 按到达那一刻的值判，而不是建订阅时的值")
    void conditionIsEvaluatedAtDeliveryTimeNotAtSubscribeTime() {
        // 这是条件式事件全部价值所在：金额是流程启动**之后**才定的。
        // 若实现改成建订阅时求值，这里启动时 amount 还不存在 ⇒ 判不成立 ⇒ 永远不提醒。
        String pid = start(COND_RACE_BPMN, new HashMap<String, Object>());
        setVariable(pid, "amount", 20000);

        runtime.triggerMessage("bossApprove", pid, "boss", null, "批了");

        assertEquals(1, openTaskCount(pid),
                "启动时还没有 amount，到达时已经是 20000 —— "
                        + "按到达那一刻的值判才走得通。症状是「金额明明超了却不提醒」");
    }

    @Test
    @DisplayName("不带条件的那一格行为不变 —— 条件是叠加的，不是必需的")
    void branchesWithoutConditionStillWin() {
        String pid = start(COND_RACE_BPMN, new HashMap<String, Object>());
        setVariable(pid, "amount", 500);

        runtime.broadcastSignal("erpCallback", "system", null, "回执来了");

        assertEquals(1, openTaskCount(pid),
                "没写条件的那一格必须照常赢 —— 否则「加了条件」会连累整个网关");
        assertEquals(0, eventCatchJobsOf(pid).size(), "赢完之后订阅清空");
    }

    @Test
    @DisplayName("条件求值失败（变量不存在）判「不成立」而不是放行")
    void brokenConditionFailsClosed() {
        String pid = start(BAD_COND_BPMN, new HashMap<String, Object>());

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.triggerMessage("bossApprove", pid, "boss", null, "批了"));
        assertTrue(ex.getMessage().contains("条件"),
                "求值失败也要说清是条件的问题，而不是别的: " + ex.getMessage());

        assertEquals(0, openTaskCount(pid),
                "条件写错时放行 = 把一条本该继续等的分支提前推进去了，"
                        + "而流程就此走错、没有任何报错。判「不成立」至少还有迹可循");
        assertEquals(2, eventCatchJobsOf(pid).size(),
                "判不成立时订阅要留着（虽然这条定义本身有错，"
                        + "但那属于部署期该拦的写法，不是运行期该静默放行的事）");
    }

    // ==================== 部署期 ====================

    @Test
    @DisplayName("条件为空在部署期就报 ERROR —— 条件式事件没有条件是条死路")
    void emptyConditionIsRejectedAtDeployTime() {
        String xml = COND_RACE_BPMN
                .replace("<condition>${amount &gt; 10000 &amp;&amp; !approved}</condition>", "  ")
                .replace("id=\"condRace\"", "id=\"emptyCond\"");
        WfValidationIssue issue = errorOf(xml);
        assertNotNull(issue, "空的 <condition> 没被拦下来");
        assertTrue(issue.getMessage().contains("condition")
                        || issue.getMessage().contains("条件"),
                "报错要说清缺的是 <condition>，而不是笼统的「事件定义不完整」: "
                        + issue.getMessage());
    }

    @Test
    @DisplayName("只有条件、没有任何事件类型 ⇒ 报错（条件不提供「等什么」）")
    void conditionWithoutEventTypeIsRejected() {
        String xml = COND_RACE_BPMN
                .replace("<messageEventDefinition messageRef=\"bossApprove\"/>\n", "")
                .replace("id=\"condRace\"", "id=\"condOnly\"");
        WfValidationIssue issue = errorOf(xml);
        assertNotNull(issue, "没有事件类型的条件式事件没被拦下来");
        // 断**判别式**而不是"消息里有没有'条件'两个字"：
        // 那两个字在报错里到处都是，删掉纠正性那句话照样能匹配上。
        // 真正要断的是「它点明了条件不提供事件类型」——
        // 作者的误解恰恰是"条件式事件自己就能等"，只有这句话能纠正它。
        assertTrue(issue.getMessage().contains("不提供事件类型"),
                "报错要明确点出「条件不提供事件类型」（它答「够不够格」不答「等什么」），"
                        + "否则作者会一直以为条件式事件自己就能等: " + issue.getMessage());
    }

    @Test
    @DisplayName("边界事件上不许挂条件：打断型与竞速型语义互斥")
    void boundaryWithConditionIsRejected() {
        // 边界事件在本实现里是打断型（宿主待办作废、token 走补偿分支），
        // 条件式事件是竞速型（不满足就继续等）。叠加时"条件不满足"没有答案：
        // 按竞速理解该继续等，可边界此刻正要打断 —— 等于把打断静默取消，
        // 而宿主 token 已被拉走。
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"condBoundary\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <userTask id=\"t\" name=\"审批\" zifang:assignee=\"alice\"/>\n"
                + "    <boundaryEvent id=\"b\" attachedToRef=\"t\">\n"
                + "      <signalEventDefinition signalRef=\"bigDeal\"/>\n"
                + "      <conditionalEventDefinition>\n"
                + "        <condition>${amount &gt; 10000}</condition>\n"
                + "      </conditionalEventDefinition>\n"
                + "    </boundaryEvent>\n"
                + "    <userTask id=\"boss\" name=\"主管\" zifang:assignee=\"boss\"/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"t\" targetRef=\"e\"/>\n"
                + "    <sequenceFlow id=\"f3\" sourceRef=\"b\" targetRef=\"boss\"/>\n"
                + "    <sequenceFlow id=\"f4\" sourceRef=\"boss\" targetRef=\"e\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        WfValidationIssue issue = errorOf(xml);
        assertNotNull(issue, "边界事件上的条件没被拦下来");
        assertTrue(issue.getMessage().contains("conditionalEventDefinition"),
                "报错要点名是哪个元素: " + issue.getMessage());
    }

    @Test
    @DisplayName("条件式事件可以从库里读回来（codec 那一段不丢 properties）")
    void conditionSurvivesTheDefinitionRoundTrip() {
        // codec 丢 properties 是本仓踩过的坑（M11 丢 parallelMultiple）。
        // 条件是「在事件到达时才求值」的，所以它必须能一路活到那一刻 ——
        // 走的是从库里读回来的定义（内存实现对定义是深拷贝，对象引用不共享）。
        InMemoryWorkflowPersistence other = new InMemoryWorkflowPersistence();
        other.initialize();
        WfRepositoryService otherRepository = new WfRepositoryService(other);
        otherRepository.deploy(new WfXmlParser().parse(COND_RACE_BPMN));
        WfDefinition reloaded = otherRepository.getLatestDefinition("condRace");
        assertNotNull(reloaded);
        Object condition = reloaded.node("waitBoss").getProperties()
                .get(WfXmlParser.PROPERTY_EVENT_CONDITION);
        assertNotNull(condition, "条件在持久化往返后丢了 —— "
                + "症状是「部署没问题、跑到那一步就变成没有条件」");
        assertTrue(String.valueOf(condition).contains("amount"),
                "读回来的条件原文: " + condition);
    }

    @Test
    @DisplayName("定时器到点但条件不成立：静默跳过，不变成一条失败并重试的 job")
    void timerWithFalseConditionJustSkips() {
        // 定时器与消息/信号是**相反**的处置：它是引擎自己在跑，
        // 条件不满足就是「这次到点不算数」，那完全正常。
        // 抛出去会让一个正常到点的定时器 job 变成失败并重试 ——
        // 而它永远不会成功，重试耗尽后还会被记成一条故障，
        // 于是「条件不满足」变成了「流程出故障」，两件不相干的事。
        String pid = start(COND_TIMER_BPMN, new HashMap<String, Object>());
        setVariable(pid, "amount", 500);
        WfJob job = timerJobOf(pid);
        assertNotNull(job, "定时器分支的 job 应当存在");

        boolean fired = runtime.fireTimer(job);

        assertFalse(fired, "条件不成立时不该触发这一格");
        assertEquals(0, openTaskCount(pid), "不该推进到 userTask");
        assertEquals(1, timerJobCount(pid),
                "这一格还在等下一次到点 —— 把 job 删掉的话它就永远不会再被评估");
        assertTrue(hasCommentContaining(pid, "条件不成立"),
                "留痕由条件判定那一步写（评论里带条件原文）。评论: " + commentsOf(pid));
    }

    @Test
    @DisplayName("定时器到点且条件成立：正常赢，其余分支作废")
    void timerWithTrueConditionWins() {
        String pid = start(COND_TIMER_BPMN, new HashMap<String, Object>());
        setVariable(pid, "amount", 20000);
        WfJob job = timerJobOf(pid);
        assertNotNull(job);

        assertTrue(runtime.fireTimer(job), "条件成立就该让这一格赢");
        assertEquals(1, openTaskCount(pid), "该推进到 userTask");
    }

    // ==================== 夹具 ====================

    /**
     * 部署期 ERROR 的第一条（按节点顺序）。
     *
     * <p>直接调校验器而不是 {@code deploy}：deploy 抛的
     * {@code WfDefinitionException} 只带一句汇总消息，
     * 拿不到「是哪一条 issue、说到哪个节点」，而那正是部署期诊断要给出的东西。
     */
    private WfValidationIssue errorOf(String xml) {
        for (WfValidationIssue issue : new WfDefinitionValidator()
                .validate(new WfXmlParser().parse(xml))) {
            if (issue.getSeverity() == WfValidationIssue.Severity.ERROR) {
                return issue;
            }
        }
        return null;
    }

    private String start(String xml, Map<String, Object> variables) {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(xml));
        return runtime.startProcessInstance(definition, "BIZ-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>(variables));
    }

    private void setVariable(String pid, String name, Object value) {
        WfProcessInstance instance = repo.findProcessInstance(pid);
        instance.getVariables().put(name, value);
        instance.nextRevision();
        repo.saveProcessInstance(instance);
    }

    private List<WfJob> eventCatchJobsOf(String pid) {
        List<WfJob> found = new ArrayList<>();
        for (WfJob job : repo.queryJobs(new WfJobQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(50))) {
            if (job.getType() == WfJobType.EVENT_MESSAGE
                    || job.getType() == WfJobType.EVENT_SIGNAL
                    || job.getType() == WfJobType.EVENT_TIMER) {
                found.add(job);
            }
        }
        return found;
    }

    private WfJob timerJobOf(String pid) {
        for (WfJob job : repo.queryJobs(new WfJobQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(50))) {
            if (job.getType() == WfJobType.EVENT_TIMER) {
                return job;
            }
        }
        return null;
    }

    private int timerJobCount(String pid) {
        int count = 0;
        for (WfJob job : repo.queryJobs(new WfJobQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(50))) {
            if (job.getType() == WfJobType.EVENT_TIMER) {
                count++;
            }
        }
        return count;
    }

    private int openTaskCount(String pid) {
        return repo.queryTasks(new com.zifang.z.wf.core.persistence.WfTaskQuery()
                .setProcessInstanceId(pid).setOpenOnly(true)
                .setPageNum(1).setPageSize(20)).size();
    }

    private List<String> commentsOf(String pid) {
        List<String> texts = new ArrayList<>();
        for (WfComment comment : repo.findComments(pid)) {
            texts.add(comment.getContent());
        }
        return texts;
    }

    private boolean hasCommentContaining(String pid, String fragment) {
        for (String text : commentsOf(pid)) {
            if (text != null && text.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
