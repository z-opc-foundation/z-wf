package com.zifang.z.wf.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.definition.WfValidationIssue;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.JdbcWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;

import org.h2.jdbcx.JdbcDataSource;

/**
 * 链接事件（{@code linkThrowEvent} / {@code linkCatchEvent}）。
 *
 * <p>它补的是一件别的东西都替代不了的事：<b>跳过一整段图</b>。
 * {@code move} 是运行期的外部 API（要把令牌搬到某个已知节点上，调用方得自己知道位置），
 * 排他网关是<b>分支</b>（在若干出线里选一条）。两者都不等于"从图上某处直接落到另一处"。
 *
 * <p>本类盯四件错了都不报错的事：
 * <ol>
 *   <li><b>改道后 token 沿 catch 自己的出线走</b>。反过来（沿 throw 的出线走）会让
 *       「跳过一整段」变成「跑完整段再跳一遍」，症状是作者以为跳过的那段照跑，
 *       而图上完全看不出异常。<b>这一条与 escalation 恰好相反</b> ——
 *       抛出去的 escalation 会让 throw 自己的出线也被走掉，两处不能互相参照着写。</li>
 *   <li><b>落点找不到必须停，不能挑一个</b>。挑错的症状是流程走进另一条
 *       <b>完全正确</b>的分支并正常结束 —— 没有任何报错。</li>
 *   <li><b>link catch 不是"等一个事件"</b>：它不该建任何事件订阅。
 *       若被实现成挂 {@code EVENT_*} 订阅，那是一个永远等不到、也不报错的哑订阅。</li>
 *   <li><b>link catch 天然无入线这件事不能漏</b>：它一旦被当成"无入线的节点"，
 *       就会凭空多出一个无条件入口，报「存在多个无条件开始节点」——
 *       而作者图上确实只画了一个入口。</li>
 * </ol>
 */
class WfLinkEventTest {

    /**
     * 主用例：提交后直接跳到归档，<b>跳过</b>中间那一整段。
     *
     * <p>{@code jump} 上<b>刻意留了一条出线</b>（指向 skipped 段）——
     * 那正是作者在模型器上很容易顺手拖出来的东西，而它是<b>摆设</b>。
     * 留着它，「出线没被走过」才是一条有区分力的判据：
     * 否则「因为图上没连线所以没走过」会平凡通过，
     * 而真正要防的那个 bug（特判点放在取线之后）恰恰要靠它才打得出红。
     */
    private static final String LINK_SKIP_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"linkSkip\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <userTask id=\"apply\" name=\"提交\" zifang:assignee=\"alice\"/>\n"
            + "    <linkThrowEvent id=\"jump\" name=\"skipToArchive\"/>\n"
            + "    <linkCatchEvent id=\"landing\" name=\"skipToArchive\"/>\n"
            + "    <userTask id=\"archive\" name=\"归档\" zifang:assignee=\"bob\"/>\n"
            + "    <serviceTask id=\"skipped\" name=\"不该跑\""
            + " zifang:delegateExpression=\"${true}\"/>\n"
            + "    <userTask id=\"alsoSkipped\" name=\"更不该跑\" zifang:assignee=\"carol\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"apply\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"apply\" targetRef=\"jump\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"landing\" targetRef=\"archive\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"archive\" targetRef=\"e\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"jump\" targetRef=\"skipped\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"skipped\" targetRef=\"alsoSkipped\"/>\n"
            + "    <sequenceFlow id=\"f7\" sourceRef=\"alsoSkipped\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /**
     * 链接构成循环：网关按 {@code count} 判断再跳回上游，最多回两轮。
     *
     * <p>用 scriptTask 累加 {@code count}，让"跳了几次"变成一个可断言的数字 ——
     * 只断言"回到了 t1"的话，一次就回去也能通过，而死循环也能通过。
     */
    private static final String LINK_LOOP_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\""
            + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" targetNamespace=\"x\">\n"
            + "  <process id=\"linkLoop\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <userTask id=\"t1\" name=\"第一轮\" zifang:assignee=\"alice\"/>\n"
            + "    <exclusiveGateway id=\"again\"/>\n"
            + "    <scriptTask id=\"bump\" zifang:script=\"${count + 1}\""
            + " zifang:resultVariable=\"count\"/>\n"
            + "    <linkThrowEvent id=\"throwBack\" name=\"backToTop\"/>\n"
            + "    <linkCatchEvent id=\"catchTop\" name=\"backToTop\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t1\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"again\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"again\" targetRef=\"bump\">\n"
            + "      <conditionExpression xsi:type=\"tFormalExpression\">"
            + "${count &lt; 2}</conditionExpression>\n"
            + "    </sequenceFlow>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"again\" targetRef=\"e\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"bump\" targetRef=\"throwBack\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"catchTop\" targetRef=\"t1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 两个 throw 汇到同一个 catch（Camunda 允许，只禁止一个 throw 对多个 catch）。 */
    private static final String LINK_FANIN_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"linkFanIn\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <exclusiveGateway id=\"pick\"/>\n"
            + "    <linkThrowEvent id=\"tA\" name=\"toArchive\"/>\n"
            + "    <linkThrowEvent id=\"tB\" name=\"toArchive\"/>\n"
            + "    <linkCatchEvent id=\"cArchive\" name=\"toArchive\"/>\n"
            + "    <userTask id=\"archive\" name=\"归档\" zifang:assignee=\"bob\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"pick\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"pick\" targetRef=\"tA\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"pick\" targetRef=\"tB\">\n"
            + "      <conditionExpression xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
            + " xsi:type=\"tFormalExpression\">${viaB}</conditionExpression>\n"
            + "    </sequenceFlow>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"cArchive\" targetRef=\"archive\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"archive\" targetRef=\"e\"/>\n"
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

    // ==================== 运行时：改道 ====================

    @Test
    @DisplayName("token 改道到 catch，中间那一整段真的没跑")
    void jumpSkipsTheSectionInBetween() {
        String pid = start(LINK_SKIP_BPMN);
        completeTask(pid);

        List<String> visited = visitedActivityIds(pid);
        assertTrue(visited.contains("jump"), "轨迹上要有 link 抛出事件: " + visited);
        assertTrue(visited.contains("landing"), "轨迹上要有 link 捕获事件: " + visited);
        assertFalse(visited.contains("skipped"),
                "throw 的出线上那条线**不能**被 token 走过 —— "
                        + "走一遍等于「跑完整段再跳一遍」，那不是跳过，是多跑了一遍。"
                        + "轨迹: " + visited);
        assertFalse(visited.contains("alsoSkipped"), "跳过的段整体都没跑: " + visited);
        // 断「已经沿 catch 自己的出线走出去」而不是断 archive 有历史 ——
        // archive 是 userTask，要等 bob 办结才记历史，现在它只是停在那里等人。
        assertEquals(1, openTaskAssigneeCount(pid, "bob"),
                "改道之后应当落在 catch 出线上的那一步。轨迹: " + visited);
        assertEquals(0, openTaskAssigneeCount(pid, "carol"),
                "被跳过的段里有个 carol 的待办，它不该被建出来");
    }

    @Test
    @DisplayName("link catch 是穿透的：不建待办，也不建任何事件订阅")
    void catchIsTransparentAndBuildsNoSubscription() {
        String pid = start(LINK_SKIP_BPMN);
        completeTask(pid);

        assertEquals(1, openTaskCount(pid), "改道之后应该恰好停在归档那一步");
        assertEquals("bob", assigneeOfOpenTask(pid),
                "落在归档这一步说明 catch 没有变成一个等人办理的待办");
        assertEquals(0, eventSubscriptionCount(pid),
                "link catch 等的是「图上另一个节点」，而那个跳转在引擎内部**同步**完成了，"
                        + "根本没有订阅这回事。挂一个 EVENT_* 订阅得到的是"
                        + "一个永远等不到、也不报错的哑订阅 —— 流程会卡在这一格不再往下走");
        assertFalse(repo.findProcessInstance(pid).getStatus().isTerminal(),
                "流程还应当在跑，而不是停在 catch 上");
    }

    @Test
    @DisplayName("链接可以构成循环：跳回上游，循环次数由变量决定")
    void linkCanFormALoop() {
        // count 必须在启动时就存在：`${count + 1}` 在 count 缺失时求不出值，
        // 而「启动时还不存在」这件事本身就是另一条待验证的性质（见
        // WfConditionalEventTest#conditionIsEvaluatedAtDeliveryTimeNotAtSubscribeTime），
        // 混进来会让这条判据分不清失败原因。
        String pid = start(LINK_LOOP_BPMN, countZero());

        completeTask(pid);
        assertEquals(1, numberOf(repo.findProcessInstance(pid).getVariables().get("count")),
                "第 1 轮之后 count 应当是 1 —— 网关判它 < 2，于是跳回上游。"
                        + "读不到说明 scriptTask 的 resultVariable 没写回，"
                        + "而「循环几次」这条判据也随之失去意义");

        completeTask(pid);
        assertEquals(2, numberOf(repo.findProcessInstance(pid).getVariables().get("count")),
                "第 2 轮之后 count 应当是 2。这次网关看的是**办结前**的 count=1，"
                        + "`${count < 2}` 仍成立，所以又跳回上游 —— "
                        + "bump 在网关**之后**，本轮的值要下一轮才被网关看见");

        // 第 3 次：网关看 count=2，条件不成立 ⇒ 走无条件出线 f4 ⇒ 结束
        completeTask(pid);
        assertEquals(WfProcessStatus.COMPLETED, repo.findProcessInstance(pid).getStatus(),
                "循环应当自己收敛，而不是靠 MAX_DEPTH 撞墙停下 —— "
                        + "撞墙停下的实例状态是内部终止");
        assertEquals(2, numberOf(repo.findProcessInstance(pid).getVariables().get("count")),
                "收尾那一轮没有再经过 bump，count 不该继续涨");
        List<String> visited = visitedActivityIds(pid);
        assertEquals(2, countOf(visited, "throwBack"),
                "throwBack 应当恰好被走到两次: " + visited);
        assertEquals(2, countOf(visited, "catchTop"),
                "每次改道都要真的落在 catch 上: " + visited);
    }

    @Test
    @DisplayName("多个 throw 可以汇到同一个 catch（反过来才不行）")
    void severalThrowsShareOneCatch() {
        List<WfValidationIssue> issues = issuesOf(LINK_FANIN_BPMN);
        assertNull(errorOf(LINK_FANIN_BPMN),
                "多个 throw 汇到一个 catch 是合法的，堵它会拦住「三个入口同一段收尾」"
                        + "这类真实图: " + renderAll(issues));

        WfDefinition definition = new WfXmlParser().parse(LINK_FANIN_BPMN);
        assertEquals(1, definition.linkCatchesOf("toArchive").size(),
                "查表要认得出「落点只有一个」");
        assertEquals("cArchive", definition.linkTargetOf("toArchive").getId());

        // 运行时走 tA（f2 无条件）。tB 那条线带条件，viaB 未定义时不成立 ——
        // 这条判据要的是「共存不报错」，不需要两条 throw 都跑一遍。
        String pid = start(LINK_FANIN_BPMN);
        assertEquals(1, openTaskCount(pid));
        assertEquals("bob", assigneeOfOpenTask(pid), "应当落到同一个 catch 上的归档段");
    }

    @Test
    @DisplayName("改道的两个端点在轨迹上各记一条")
    void rerouteRecordsBothEndpoints() {
        String pid = start(LINK_SKIP_BPMN);
        completeTask(pid);

        List<WfActivityInstance> acts = repo.findActivityInstances(pid);
        assertNotNull(activityOf(acts, "jump"),
                "抛出事件被访问过一次就要有一条历史（它是一次真实的节点访问）: " + idsOf(acts));
        assertNotNull(activityOf(acts, "landing"),
                "捕获事件同样要有一条 —— 漏掉它的话轨迹上会凭空少一跳，"
                        + "排障时看起来像「什么都没发生就直接到了归档」。轨迹: " + idsOf(acts));
        // 刻意**不**断两条历史的 startTime 之差：两次 recordActivity 之间隔着
        // 一整套 enter/leave，落在同一毫秒的概率很高，那样的断言是 flaky 的，
        // 而 flaky 判据比没有判据更糟 —— 它会让人怀疑那条规则本身。
    }

    @Test
    @DisplayName("落点找不到时停成内部终止，而不是随便挑一个 catch")
    void missingTargetFailsInsteadOfPickingOne() {
        // 构造「定义绕过了部署期校验」：catch 在部署时确实存在（所以过了闸门），
        // 运行期读回来的定义里却没有它。部署期**不可能**造出这种状态，
        // 所以要用一个改过定义的存储层 —— 直接拿部署期报错来测这条 fail 是测不到的。
        DoctoredPersistence doctored = new DoctoredPersistence(Mode.DROP_ALL_CATCHES);
        doctored.initialize();
        driveUntilTerminated(doctored);
    }

    @Test
    @DisplayName("落点有歧义时同样要停：挑一个跳过去会走进另一条「完全正确」的分支")
    void ambiguousTargetFailsInsteadOfPickingOne() {
        // 与上面是**两种不同的失败**，处置依据也不同：一个是"一个都没有"，
        // 一个是"有多个、跳到哪一个没答案"。后者更危险 —— 挑中的那个落点
        // 在图上完全合法，流程会一路正常跑完，只是走错了地方。
        // 只测前者的话，"多个时挑第一个"这条分支完全没人管。
        DoctoredPersistence doctored = new DoctoredPersistence(Mode.DUPLICATE_CATCHES);
        doctored.initialize();
        driveUntilTerminated(doctored);
    }

    /**
     * 在一份"绕过了部署期校验"的定义上启动并推进一步，
     * 断言它停在内部终止、且理由指向「落点」。
     */
    private void driveUntilTerminated(DoctoredPersistence store) {
        WfRepositoryService doctoredRepository = new WfRepositoryService(store);
        doctoredRepository.deploy(new WfXmlParser().parse(LINK_SKIP_BPMN));
        WfRuntimeService doctoredRuntime = new WfRuntimeService(doctoredRepository, store,
                new WfEngine(), new WfHookDispatcher());
        String pid = doctoredRuntime.startProcessInstance(
                doctoredRepository.getLatestDefinition("linkSkip"), "BIZ-1", "alice", null,
                new HashMap<String, Object>());
        doctoredRuntime.completeTask(firstTaskId(store, pid), "alice", "交",
                new HashMap<String, Object>());

        WfProcessInstance instance = store.findProcessInstance(pid);
        assertEquals(WfProcessStatus.INTERNALLY_TERMINATED, instance.getStatus(),
                "改道落空必须停 —— 挑一个 catch 跳过去的症状是流程走进另一条"
                        + "**完全正确**的分支并正常结束，没有任何报错");
        assertTrue(String.valueOf(instance.getDeleteReason()).contains("找不到落点"),
                "停下来的理由必须指向「落点找不到」，而不是别的: " + instance.getDeleteReason());
    }

    // ==================== 部署期 ====================

    @Test
    @DisplayName("没有 name 在部署期就报 ERROR —— name 就是 link 名")
    void missingNameIsRejectedAtDeployTime() {
        WfValidationIssue issue = errorOf(LINK_SKIP_BPMN
                .replace("id=\"landing\" name=\"skipToArchive\"", "id=\"landing\""));
        assertNotNull(issue, "没写 name 的 link 事件没被拦下来");
        assertTrue(issue.getMessage().contains("name"),
                "报错要点名缺的是 name（它就是 link 名）: " + issue.getMessage());
    }

    @Test
    @DisplayName("同名 catch 多个 ⇒ ERROR（「跳到哪一个」没有答案）")
    void duplicateCatchNamesAreRejected() {
        WfValidationIssue issue = errorOf(LINK_SKIP_BPMN.replace(
                "    <sequenceFlow id=\"f3\" sourceRef=\"landing\" targetRef=\"archive\"/>\n",
                "    <linkCatchEvent id=\"landing2\" name=\"skipToArchive\"/>\n"
                        + "    <sequenceFlow id=\"f3\" sourceRef=\"landing\" targetRef=\"archive\"/>\n"
                        + "    <sequenceFlow id=\"f3b\" sourceRef=\"landing2\" targetRef=\"archive\"/>\n"));
        assertNotNull(issue, "两个同名 catch 没被拦下来");
        assertTrue(issue.getMessage().contains("skipToArchive"),
                "报错要点名是哪个 link 名: " + issue.getMessage());
    }

    @Test
    @DisplayName("throw 找不到对应的 catch ⇒ ERROR")
    void throwWithoutCatchIsRejected() {
        WfValidationIssue issue = errorOf(LINK_SKIP_BPMN
                .replace("id=\"landing\" name=\"skipToArchive\"", "id=\"landing\" name=\"elsewhere\""));
        assertNotNull(issue, "跳不过去的 link 没被拦下来");
        // 断**判别式**而不是笼统地断 link 名：名字在"重名多个"那条报错里也出现，
        // 只断名字的话，两条不同的诊断会互相顶替。
        assertTrue(issue.getMessage().contains("一个都没有"),
                "报错要分清「一个都没有」与「有多个、跳到哪一个没答案」: "
                        + issue.getMessage());
    }

    @Test
    @DisplayName("catch 没有任何 throw 指向它 ⇒ WARN（不是 ERROR：半成品图还要能部署）")
    void catchWithoutThrowIsWarned() {
        // 刻意**另起一份"只有 catch 没有 throw"的 XML**，而不是把 throw 改名：
        // 改名会同时触发「throw 找不到落点」那条 ERROR，
        // 于是这条判据断的到底是"没人跳只报 WARN"还是"找不到报 ERROR"就分不清了。
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"lonelyCatch\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <userTask id=\"before\" name=\"前段\" zifang:assignee=\"alice\"/>\n"
                + "    <linkCatchEvent id=\"landing\" name=\"orphanLink\"/>\n"
                + "    <userTask id=\"after\" name=\"后段\" zifang:assignee=\"bob\"/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"before\"/>\n"
                + "    <sequenceFlow id=\"f3\" sourceRef=\"landing\" targetRef=\"after\"/>\n"
                + "    <sequenceFlow id=\"f4\" sourceRef=\"after\" targetRef=\"e\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        List<WfValidationIssue> issues = issuesOf(xml);
        assertNull(errorOf(xml),
                "没人跳的 catch 不该堵死部署 —— 设计期「先画好 catch、"
                        + "之后再补 throw」是正常画法。全部诊断: " + renderAll(issues));
        WfValidationIssue warn = firstWarnContaining(issues, "没有任何链接抛出事件");
        assertNotNull(warn,
                "但必须留一条 WARN 说清「它后面的整段永远不执行」—— "
                        + "WARN 只进日志，作者看到的运行结果会是「流程莫名结束」。"
                        + "全部诊断: " + renderAll(issues));
    }

    @Test
    @DisplayName("catch 有入线 ⇒ ERROR（那条连线永远不会被走过，上游整段静默失效）")
    void catchWithIncomingFlowIsRejected() {
        WfValidationIssue issue = errorOf(LINK_SKIP_BPMN.replace(
                "    <sequenceFlow id=\"f3\" sourceRef=\"landing\" targetRef=\"archive\"/>\n",
                "    <sequenceFlow id=\"f0\" sourceRef=\"s\" targetRef=\"landing\"/>\n"
                        + "    <sequenceFlow id=\"f3\" sourceRef=\"landing\" targetRef=\"archive\"/>\n"));
        assertNotNull(issue, "带入线的 link catch 没被拦下来");
        assertTrue(issue.getMessage().contains("永远不会"),
                "报错要说清后果是「整段流程一次都不会执行」，"
                        + "否则作者会以为那条线只是冗余: " + issue.getMessage());
    }

    @Test
    @DisplayName("catch 没有出线 ⇒ ERROR（跳过来之后无处可去）")
    void catchWithoutOutgoingFlowIsRejected() {
        WfValidationIssue issue = errorOf(LINK_SKIP_BPMN.replace(
                "    <sequenceFlow id=\"f3\" sourceRef=\"landing\" targetRef=\"archive\"/>\n", ""));
        assertNotNull(issue, "没有出线的 link catch 没被拦下来");
        assertTrue(issue.getMessage().contains("没有出线"),
                "报错要说清是缺出线: " + issue.getMessage());
    }

    @Test
    @DisplayName("throw 有出线 ⇒ WARN（画得出来、走得不通 —— 与 escalation 相反）")
    void throwWithOutgoingFlowIsWarned() {
        // LINK_SKIP_BPMN 里 jump 就带着一条指向 skipped 的出线。
        List<WfValidationIssue> issues = issuesOf(LINK_SKIP_BPMN);
        assertNull(errorOf(LINK_SKIP_BPMN),
                "出线存在不该挡部署 —— BPMN 允许画出来，BPMN 也允许它不生效");
        WfValidationIssue warn = firstWarnContaining(issues, "token 不会走过它们");
        assertNotNull(warn,
                "但要留痕说清那条线在运行期是摆设 —— 作者在模型器上很容易顺手拖一条出来。"
                        + "全部诊断: " + renderAll(issues));
        assertTrue(warn.getMessage().contains("escalation"),
                "这条 WARN 要点出「与 escalation 恰好相反」，"
                        + "否则下一个人照着 escalation 的实现去改就会写反: " + warn.getMessage());
    }

    @Test
    @DisplayName("link catch 不会被当成无条件入口（它天然没有入线）")
    void linkCatchIsNotTreatedAsAStartNode() {
        // 这份定义**故意不写 startEvent**，靠「无入线的节点」当入口 ——
        // 而无入线的节点有**两个**：entry（真的入口）与 link catch（天然没入线）。
        // 若不把 link catch 排除掉，引擎会报「存在多个无条件开始节点」，
        // 而作者图上确实只画了一个入口。
        //
        // 刻意不放 startEvent：写了它就走了 plainStarts 那一轮、退化分支根本不执行，
        // 这条判据会平凡通过。
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"noStart\" isExecutable=\"true\">\n"
                + "    <manualTask id=\"entry\" name=\"入口\" zifang:assignee=\"alice\"/>\n"
                + "    <linkCatchEvent id=\"landing\" name=\"skipToArchive\"/>\n"
                + "    <userTask id=\"archive\" name=\"归档\" zifang:assignee=\"bob\"/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f3\" sourceRef=\"landing\" targetRef=\"archive\"/>\n"
                + "    <sequenceFlow id=\"f4\" sourceRef=\"archive\" targetRef=\"e\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        assertNull(errorOf(xml),
                "无 startEvent 的定义不该被 link catch 顶出「多个无条件入口」: "
                        + renderAll(issuesOf(xml)));
        assertEquals(1, new WfXmlParser().parse(xml).unconditionalStartNodes().size(),
                "入口只能有 entry 一个 —— link catch 等的是图上另一个节点，"
                        + "而那个跳转在引擎内部同步完成，根本没有「谁会先到」的问题");
    }

    @Test
    @DisplayName("linkName 能活过持久化往返 —— 内存深拷贝与 JDBC codec 两条路径都要断")
    void linkNameSurvivesTheDefinitionRoundTrip() {
        // **两套实现都要测**：内存实现对定义是深拷贝（对象引用不共享，走不到 codec），
        // 而 JDBC 那边要经过 JSON 编解码。只测内存的话，codec 里少写一行映射
        // （node.setLinkName(gn.getLinkName())）根本不会红 ——
        // 那种缺陷的症状是「部署没问题、一跑到抛出事件就停成内部终止」，
        // 而真正的原因在 codec 上，排查要跨三层。
        // （本仓踩过同款：WfDefinitionRoundTripTest 的反射护栏断的是"字段在 GraphNode
        //  里存在"，它断不住"字段存在但映射语句漏调"——那是另一段代码。）
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:linkevt_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        JdbcWorkflowPersistence jdbc = new JdbcWorkflowPersistence(ds);
        jdbc.initialize();

        for (WfPersistence target : new WfPersistence[]{repo, jdbc}) {
            String kind = target.getClass().getSimpleName();
            WfDefinition parsed = new WfXmlParser().parse(LINK_SKIP_BPMN);
            target.saveDefinition(parsed);
            WfDefinition reloaded = target.findDefinition(parsed.getKey(), parsed.getVersion());
            assertNotNull(reloaded, kind + "：定义应能取回");

            assertEquals("skipToArchive", reloaded.node("landing").getLinkName(),
                    kind + "：linkName 在持久化往返后丢了 —— 症状是「部署没问题、"
                            + "一跑到抛出事件就停成内部终止」，而真正的原因在 codec 上");
            assertEquals("landing", reloaded.linkTargetOf("skipToArchive").getId(),
                    kind + "：读回来的定义上查表也要能找到落点");
        }
    }

    @Test
    @DisplayName("只有 link 事件的 linkName 非空 —— 别的节点不许被写上")
    void linkNameIsOnlySetOnLinkEvents() {
        WfDefinition definition = new WfXmlParser().parse(LINK_SKIP_BPMN);
        assertEquals("skipToArchive", definition.node("jump").getLinkName());
        assertEquals("skipToArchive", definition.node("landing").getLinkName());
        // 这两个节点**都有 name**（"提交" / "归档"），
        // 而它们的 linkName 必须是 null —— 属性必须数据真具备。
        // 若解析改成"无条件把 @name 抄进 linkName"，这里就打红：
        // 那会让每个普通节点都自称一个 link 落点，而 linkTargetOf 查表时
        // 仍按类型过滤，所以症状是"字段看起来有值、实际语义是空的"。
        assertNull(definition.node("apply").getLinkName(),
                "userTask 的 linkName 应当是 null");
        assertNull(definition.node("archive").getLinkName(),
                "userTask 的 linkName 应当是 null");
    }

    @Test
    @DisplayName("link 事件上挂边界事件 ⇒ ERROR（两个方向都是穿透的，没有可打断的宿主）")
    void boundaryEventOnLinkEventIsRejected() {
        // catch 不停留、throw 是瞬间改道 —— 两者都不会"停在本节点上"，
        // 而边界事件需要一个停在宿主上的 token。挂上去只会得到一个
        // 永远不触发的哑订阅，且图上看不出任何异常。
        String xml = LINK_SKIP_BPMN.replace(
                "    <sequenceFlow id=\"f3\" sourceRef=\"landing\" targetRef=\"archive\"/>\n",
                "    <boundaryEvent id=\"b\" attachedToRef=\"landing\">\n"
                        + "      <signalEventDefinition signalRef=\"bigDeal\"/>\n"
                        + "    </boundaryEvent>\n"
                        + "    <userTask id=\"notify\" name=\"通知\" zifang:assignee=\"dave\"/>\n"
                        + "    <sequenceFlow id=\"f3\" sourceRef=\"landing\" targetRef=\"archive\"/>\n"
                        + "    <sequenceFlow id=\"f8\" sourceRef=\"b\" targetRef=\"notify\"/>\n");
        List<WfValidationIssue> issues = issuesOf(xml);
        WfValidationIssue issue = firstErrorContaining(issues, "挂了边界事件");
        assertNotNull(issue,
                "link 事件上挂的边界事件没被拦下来。全部诊断: " + renderAll(issues));
        assertTrue(issue.getMessage().contains("穿透"),
                "报错要说清原因是「穿透」（不提供可停留的宿主 token），"
                        + "否则作者会以为那个订阅只是还没到触发条件: " + issue.getMessage());
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

    private List<WfValidationIssue> issuesOf(String xml) {
        return new WfDefinitionValidator().validate(new WfXmlParser().parse(xml));
    }

    private WfValidationIssue firstWarnContaining(List<WfValidationIssue> issues, String fragment) {
        for (WfValidationIssue issue : issues) {
            if (issue.getSeverity() == WfValidationIssue.Severity.WARN
                    && issue.getMessage().contains(fragment)) {
                return issue;
            }
        }
        return null;
    }

    /**
     * 按文案挑 ERROR 而不是取第一条 ——
     * 这份 XML 里同时还有别的问题（边界事件挂在 link catch 上会连累它自己那几条校验），
     * 取第一条会拿到一条与本条判据无关的诊断，于是"这条校验在不在"就分不清了。
     */
    private WfValidationIssue firstErrorContaining(List<WfValidationIssue> issues, String fragment) {
        for (WfValidationIssue issue : issues) {
            if (issue.getSeverity() == WfValidationIssue.Severity.ERROR
                    && issue.getMessage().contains(fragment)) {
                return issue;
            }
        }
        return null;
    }

    private String renderAll(List<WfValidationIssue> issues) {
        StringBuilder sb = new StringBuilder("[");
        for (WfValidationIssue issue : issues) {
            sb.append(issue.getSeverity()).append(" ").append(issue.getMessage()).append(" | ");
        }
        return sb.append("]").toString();
    }

    private String start(String xml) {
        return start(xml, new HashMap<String, Object>());
    }

    private String start(String xml, Map<String, Object> variables) {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(xml));
        return runtime.startProcessInstance(definition, "BIZ-" + System.nanoTime(),
                "alice", null, variables);
    }

    private Map<String, Object> countZero() {
        Map<String, Object> variables = new HashMap<String, Object>();
        variables.put("count", 0);
        return variables;
    }

    private void completeTask(String pid) {
        runtime.completeTask(firstTaskId(repo, pid), "alice", "办完", new HashMap<String, Object>());
    }

    private String firstTaskId(InMemoryWorkflowPersistence store, String pid) {
        List<WfTask> tasks = store.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(20));
        assertFalse(tasks.isEmpty(), "流程 " + pid + " 上应当有一个待办才能办结");
        return tasks.get(0).getId();
    }

    private int openTaskCount(String pid) {
        return repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(20)).size();
    }

    private int openTaskAssigneeCount(String pid, String assignee) {
        int count = 0;
        for (WfTask task : repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(20))) {
            if (assignee.equals(task.getAssignee())) {
                count++;
            }
        }
        return count;
    }

    private String assigneeOfOpenTask(String pid) {
        List<WfTask> tasks = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(20));
        return tasks.isEmpty() ? null : tasks.get(0).getAssignee();
    }

    /** 事件订阅型 job 的条数 —— link catch 若被实现成"等一个事件"就会在这里露出来。 */
    private int eventSubscriptionCount(String pid) {
        int count = 0;
        for (WfJob job : repo.queryJobs(new WfJobQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(50))) {
            if (job.getType() == WfJobType.EVENT_MESSAGE
                    || job.getType() == WfJobType.EVENT_SIGNAL
                    || job.getType() == WfJobType.EVENT_TIMER) {
                count++;
            }
        }
        return count;
    }

    private List<String> visitedActivityIds(String pid) {
        List<String> ids = new ArrayList<>();
        for (WfActivityInstance act : repo.findActivityInstances(pid)) {
            ids.add(act.getActivityId());
        }
        return ids;
    }

    private WfActivityInstance activityOf(List<WfActivityInstance> acts, String activityId) {
        for (WfActivityInstance act : acts) {
            if (activityId.equals(act.getActivityId())) {
                return act;
            }
        }
        return null;
    }

    private List<String> idsOf(List<WfActivityInstance> acts) {
        List<String> ids = new ArrayList<>();
        for (WfActivityInstance act : acts) {
            ids.add(act.getActivityId());
        }
        return ids;
    }

    private int countOf(List<String> values, String value) {
        int count = 0;
        for (String each : values) {
            if (value.equals(each)) {
                count++;
            }
        }
        return count;
    }

    private int numberOf(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    /**
     * 篡改 link 捕获事件的存储层 ——
     * 用来构造「定义绕过了部署期校验」这一种部署期不可能造出的状态。
     *
     * <p>两种篡改对应<b>两种不同的失败</b>，而引擎对它们的处置依据也必须不同：
     * 一个落点都找不到（跳不过去），与落点有多个（跳到哪一个没答案）。
     * 只测前者的话，"多个时挑第一个"这条分支就完全没人管 ——
     * 而它恰恰是最危险的一处，因为挑中的那个落点在图上完全合法。
     */
    private enum Mode {
        DROP_ALL_CATCHES, DUPLICATE_CATCHES
    }

    private static final class DoctoredPersistence extends InMemoryWorkflowPersistence {

        private final Mode mode;

        DoctoredPersistence(Mode mode) {
            this.mode = mode;
        }

        @Override
        public WfDefinition findDefinition(String key, int version) {
            WfDefinition original = super.findDefinition(key, version);
            if (original == null) {
                return null;
            }
            List<WfNode> nodes = new ArrayList<>();
            for (WfNode node : original.getNodes()) {
                if (node.getType() != WfNodeType.LINK_CATCH) {
                    nodes.add(node);
                    continue;
                }
                if (mode == Mode.DROP_ALL_CATCHES) {
                    continue;
                }
                nodes.add(node);
                // 复制一份同名 catch：配对键相同、id 不同，
                // 于是「跳到哪一个」在数据上真的没有答案了
                WfNode twin = new WfNode();
                twin.setId(node.getId() + "-twin");
                twin.setName(node.getName());
                twin.setType(node.getType());
                twin.setLinkName(node.getLinkName());
                nodes.add(twin);
            }
            WfDefinition doctored = new WfDefinition(original.getKey(), original.getName());
            doctored.setVersion(original.getVersion());
            doctored.setNodes(nodes);
            doctored.setFlows(original.getFlows());
            doctored.buildIndex();
            return doctored;
        }
    }
}