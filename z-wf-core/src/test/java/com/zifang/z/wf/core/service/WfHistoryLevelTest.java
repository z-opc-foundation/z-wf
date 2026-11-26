package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.config.WfProperties;
import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.engine.WfHistoryLevel;
import com.zifang.z.wf.core.engine.WfIdGenerator;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 历史级别 {@code z.wf.history-level}（第 42 轮）。
 *
 * <p>要回答的问题是「关掉历史之后，流程还跑不跑得动」——
 * 这个问题的答案必须是<b>跑得动，只是查不到某些东西</b>，
 * 否则它就不是一个性能开关而是一个开关。
 *
 * <p>因此本类里最重要的不是「各档记了什么」，而是那两条否定式断言：
 * <b>关到 {@code none} 时流程照样跑完</b>，
 * <b>而「某个 job 坏过没有」在任何档位下都还查得到</b>。
 */
class WfHistoryLevelTest {

    private static final String NS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n";

    /** 人工审批 → 结束（纯人工，用来验证"没有外部依赖时也能跑完"）。 */
    private static final String APPROVAL_BPMN = NS
            + "  <process id=\"hlApproval\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 外部任务，用来造一次真实的 job 失败（第 40 轮的历史故障）。 */
    private static final String EXTERNAL_BPMN = NS
            + "  <process id=\"hlExternal\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <serviceTask id=\"notify\" name=\"通知下游\""
            + " zifang:type=\"external\" zifang:topic=\"hl.notify\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"notify\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"notify\" targetRef=\"e\"/>\n"
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
    }

    /** 用指定级别重建三个服务。每次都新建，避免上一条用例的级别漏进来。 */
    private void at(WfHistoryLevel level) {
        WfIdGenerator ids = new WfIdGenerator.DefaultWfIdGenerator();
        runtime = new WfRuntimeService(repository, repo, new WfEngine(),
                new WfHookDispatcher(), ids, level);
        variables = new WfVariableService(repo, ids, level);
    }

    private String start(WfHistoryLevel level, String xml) {
        at(level);
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(xml));
        return runtime.startProcessInstance(definition,
                "hl-" + System.nanoTime(), "alice", null, new HashMap<String, Object>());
    }

    private WfTask openTask(String pid, String nodeId) {
        List<WfTask> open = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(50));
        for (WfTask t : open) {
            if (nodeId.equals(t.getDefinitionId())) {
                return t;
            }
        }
        throw new AssertionError("节点 " + nodeId + " 上没有待办，现有: " + open);
    }

    private int activityCount(String pid) {
        return repo.findActivityInstances(pid).size();
    }

    // ==================== 档位语义 ====================

    @Test
    @DisplayName("full：活动轨迹、评论留痕、变量审计三样都在")
    void fullRecordsEverything() {
        String pid = start(WfHistoryLevel.FULL, APPROVAL_BPMN);
        variables.setVariable(pid, "amount", 100, "alice");
        runtime.addComment(pid, null, "alice", "manual", "看一眼");
        runtime.completeTask(openTask(pid, "approve").getId(), "boss", "同意", null);

        assertTrue(activityCount(pid) > 0, "活动轨迹必须有");
        assertFalse(repo.findComments(pid).isEmpty(), "评论与变量审计都要留");
    }

    @Test
    @DisplayName("audit：有活动轨迹与评论留痕，但不记变量的每次变更")
    void auditKeepsCommentsButDropsVariableAudit() {
        String pid = start(WfHistoryLevel.AUDIT, APPROVAL_BPMN);
        variables.setVariable(pid, "amount", 100, "alice");
        runtime.addComment(pid, null, "alice", "manual", "看一眼");
        runtime.completeTask(openTask(pid, "approve").getId(), "boss", "同意", null);

        assertTrue(activityCount(pid) > 0, "活动轨迹必须有");
        List<String> contents = new java.util.ArrayList<String>();
        for (com.zifang.z.wf.core.model.WfComment c : repo.findComments(pid)) {
            contents.add(c.getType() + ":" + c.getContent());
        }
        assertTrue(contents.toString().contains("manual"),
                "人工评论必须留着。实际: " + contents);
        assertEquals(0, variableAudits(pid),
                "**一条变量审计都不该有** —— 半套审计比没有更坏"
                        + "（按变量名查会漏，而调用方以为它全都在）");
        // 关键：关掉的只是痕迹，**变量本身照常读写**
        assertEquals(100, repo.findProcessInstance(pid).getVariables().get("amount"),
                "关掉审计不能连带关掉变量 —— 那是流程正在用的数据");
    }

    private int variableAudits(String pid) {
        int count = 0;
        for (com.zifang.z.wf.core.model.WfComment c : repo.findComments(pid)) {
            if (WfVariableService.COMMENT_TYPE_VARIABLE.equals(c.getType())) {
                count++;
            }
        }
        return count;
    }

    @Test
    @DisplayName("activity：只剩「单子走到哪」，评论与变量审计都没有")
    void activityKeepsOnlyTheTrail() {
        String pid = start(WfHistoryLevel.ACTIVITY, APPROVAL_BPMN);
        variables.setVariable(pid, "amount", 100, "alice");
        runtime.completeTask(openTask(pid, "approve").getId(), "boss", "同意", null);

        assertTrue(activityCount(pid) > 0, "活动轨迹是这个档位的全部内容");
        assertEquals(0, variableAudits(pid));
        assertEquals(0, repo.findComments(pid).size(),
                "activity 档不该留任何评论。实际: " + repo.findComments(pid));
    }

    @Test
    @DisplayName("none：一条历史都不记，但**流程照样跑完**")
    void noneRecordsNothingYetTheProcessStillFinishes() {
        String pid = start(WfHistoryLevel.NONE, APPROVAL_BPMN);
        variables.setVariable(pid, "amount", 100, "alice");
        WfProcessInstance done = runtime.completeTask(
                openTask(pid, "approve").getId(), "boss", "同意", null);

        assertEquals(WfProcessStatus.COMPLETED, done.getStatus(),
                "**历史级别不是开关** —— 关到 none 流程也必须跑完，"
                        + "它只是让「查不到」而不是「跑不动」");
        assertEquals(0, activityCount(pid), "none 档不该有活动轨迹");
        assertEquals(0, repo.findComments(pid).size(), "none 档不该有评论");
        assertEquals(100, repo.findProcessInstance(pid).getVariables().get("amount"),
                "变量照样存着 —— 那是运行期数据，不是历史");
    }

    // ==================== 默认档 ====================

    @Test
    @DisplayName("默认档 = full = 本特性引入之前的基线（加了配置项本身不能丢功能）")
    void defaultLevelIsThePreExistingBehaviour() {
        assertEquals("full", new WfProperties().getHistoryLevel(),
                "默认档必须等于基线：变量审计本来就是开着的，"
                        + "把默认设成低一档等于「加了配置项就丢功能」");
        assertEquals(WfHistoryLevel.FULL, WfHistoryLevel.parse(null));
        assertEquals(WfHistoryLevel.FULL, WfHistoryLevel.parse("  "));
        assertEquals(WfHistoryLevel.FULL, WfHistoryLevel.DEFAULT);
        // 旧构造器（内嵌用法与绝大多数测试都走它）也必须是 full
        assertEquals("full", new WfManagementService(repo).getProperties().get("historyLevel"),
                "自省接口报出来的必须是实际生效的那一档 —— "
                        + "运维看到轨迹是空的，第一个能查的地方就是这里");
        at(null);
        String pid = runtime.startProcessInstance(repository.deploy(
                        new WfXmlParser().parse(APPROVAL_BPMN)),
                "hl-" + System.nanoTime(), "alice", null, new HashMap<String, Object>());
        variables.setVariable(pid, "amount", 100, "alice");
        runtime.completeTask(openTask(pid, "approve").getId(), "boss", "同意", null);
        assertEquals(1, variableAudits(pid),
                "走默认构造器时变量审计必须照旧留着 —— "
                        + "「加了一个配置项」本身不能变成一次功能下线");
    }

    @Test
    @DisplayName("认不出来的档位直接报错并列出合法值，**不回落默认**")
    void unknownLevelIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> WfHistoryLevel.parse("ful"));
        assertTrue(ex.getMessage().contains("history-level"),
                "报错要点名是哪个配置项。实际: " + ex.getMessage());
        for (String legal : new String[]{"none", "activity", "audit", "full"}) {
            assertTrue(ex.getMessage().contains(legal),
                    "报错要列出合法值 " + legal + "。实际: " + ex.getMessage());
        }
        assertEquals(WfHistoryLevel.ACTIVITY, WfHistoryLevel.parse("ACTIVITY"),
                "大小写不敏感");
    }

    // ==================== 不静默降级 ====================

    @Test
    @DisplayName("低档位下显式加评论 ⇒ 报错，不回一句成功却什么都没留")
    void addingCommentBelowAuditIsRejected() {
        for (WfHistoryLevel level : new WfHistoryLevel[]{WfHistoryLevel.NONE,
                WfHistoryLevel.ACTIVITY}) {
            at(level);
            String pid = start(level, APPROVAL_BPMN);
            WfEngineException ex = assertThrows(WfEngineException.class,
                    () -> runtime.addComment(pid, null, "alice", "manual", "看一眼"),
                    "档位 " + level + " 下有人明确要求留痕，回一句成功却没写就是骗人");
            assertTrue(ex.getMessage().contains("history-level"),
                    "报错要告诉人改哪个配置。实际: " + ex.getMessage());
            assertTrue(repo.findComments(pid).isEmpty(),
                    "被拒的调用不能留下半条评论");
        }
    }

    @Test
    @DisplayName("引擎自己的留痕在低档位下安静跳过 —— 它不该把流程执行搞挂")
    void engineBreadcrumbsAreSkippedSilently() {
        String pid = start(WfHistoryLevel.NONE, APPROVAL_BPMN);
        // 实例迁移会写一条 move 留痕（引擎自己写的，不是有人要求留的）
        runtime.move(pid, "approve", null, "ops", "线上单子要人工插队", null);

        assertEquals(0, repo.findComments(pid).size(),
                "引擎留痕被跳过 ⇒ 一条评论都不该有");
        assertEquals(WfProcessStatus.ACTIVE, repo.findProcessInstance(pid).getStatus(),
                "**引擎留痕被跳过 ≠ 操作失败** —— 迁移本身该生效还得生效");
        assertNotNull(openTask(pid, "approve"),
                "迁到 approve 之后那儿应当有一张待办 —— "
                        + "留痕被跳过不影响动作本身");
    }

    // ==================== 刻意不受级别控制的两样东西 ====================

    @Test
    @DisplayName("**任何档位下**「这个 job 坏过没有」都还查得到 —— 关历史不能关掉排障的答案")
    void historicIncidentSurvivesEveryLevel() {
        for (WfHistoryLevel level : WfHistoryLevel.values()) {
            at(level);
            WfDefinition definition = repository.deploy(new WfXmlParser().parse(EXTERNAL_BPMN));
            String pid = runtime.startProcessInstance(definition,
                    "hl-" + System.nanoTime(), "alice", null, new HashMap<String, Object>());
            List<WfJob> jobs = repo.queryJobs(new WfJobQuery()
                    .setProcessInstanceId(pid).setPageNum(1).setPageSize(10));
            assertEquals(1, jobs.size(), "前置条件：应当恰好一只外部任务");
            // fail 校验锁归属（那是第 8 轮就有的规矩），所以先真的领一次
            WfExternalTaskService external = new WfExternalTaskService(repo, runtime);
            List<com.zifang.z.wf.core.view.WfExternalTaskView> claimed =
                    external.fetchAndLock("hl.notify", "w1", 10);
            assertFalse(claimed.isEmpty(), "前置条件：应当能领到活");
            external.fail(jobs.get(0).getId(), "w1", "下游 503");

            assertEquals(1, new WfHistoricIncidentService(repo).incidentsOf(pid).size(),
                    "档位 " + level + " 把历史故障也关了 —— "
                            + "而它回答的是「现在怎么样」，属运行期事实，"
                            + "不是「事情过去之后还能不能查」。Camunda 的 JobLog 同样不受控");
        }
    }

    // ==================== 顺带修掉的缺陷 ====================

    @Test
    @DisplayName("任务没有对应 token 时办结不再 NPE（兜底那条路此前一进去就炸）")
    void completingATaskWithoutItsTokenStillWorks() {
        String pid = start(WfHistoryLevel.FULL, APPROVAL_BPMN);
        WfTask task = openTask(pid, "approve");
        // 造出「任务还在、token 已经查不到」这一对不一致（并发或数据损坏时的现场）
        WfExecution execution = repo.findExecution(task.getExecutionId());
        assertNotNull(execution, "前置条件：本来是有 token 的");
        repo.deleteExecution(execution.getId());

        WfProcessInstance done = runtime.completeTask(task.getId(), "boss", "同意", null);

        assertEquals(WfTask.Status.COMPLETED, repo.findTask(task.getId()).getStatus(),
                "**任务确实完成了**，调用方不该拿到一个 NPE —— "
                        + "那会让人以为办结没发生，于是重办一遍");
        assertNotNull(done);
        assertTrue(activityCount(pid) > 0,
                "这条兜底路径存在的意义就是「没有 token 可推进」时也别丢掉这笔历史，"
                        + "它一炸就等于白写");
        WfActivityInstanceView byExec = null;
        for (com.zifang.z.wf.core.model.WfActivityInstance h : repo.findActivityInstances(pid)) {
            if ("approve".equals(h.getActivityId())) {
                byExec = new WfActivityInstanceView(h.getExecutionId());
            }
        }
        assertNotNull(byExec, "兜底路径也得记下走过审批这一步");
        assertEquals(task.getExecutionId(), byExec.executionId,
                "token 已经查不到行了，但「当时指向哪里」仍是这条历史唯一有价值的信息");
    }

    /** 只为了上面那条断言能读起来像一句话。 */
    private static final class WfActivityInstanceView {
        private final String executionId;

        WfActivityInstanceView(String executionId) {
            this.executionId = executionId;
        }
    }

    // ==================== 自省 ====================

    @Test
    @DisplayName("自省端点带出档位，并说清这一档会少记什么")
    void managementEndpointCarriesTheLevel() {
        java.util.Map<String, Object> properties =
                new WfManagementService(repo, WfHistoryLevel.ACTIVITY).getProperties();
        assertEquals("activity", properties.get("historyLevel"));
        assertNotNull(properties.get("historyLevelDetail"));
        assertTrue(String.valueOf(properties.get("historyLevelDetail")).contains("不记评论"),
                "只给一个档位名的话，看到轨迹里没有变量中间值的人仍然不知道为什么。实际: "
                        + properties.get("historyLevelDetail"));
    }
}