package com.zifang.z.wf.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.hook.WfTaskHook;
import com.zifang.z.wf.core.persistence.WfOptimisticLockException;
import com.zifang.z.wf.core.persistence.WfPersistenceException;
import com.zifang.z.wf.web.api.WfExceptionAdvice;

/**
 * Web 层端到端测试 —— 走真实 HTTP，跑真正的 Servlet 容器。
 *
 * <p><b>为什么必须单独起容器测。</b> {@link WfAdminEndToEndTest} 用的是默认 MOCK 环境，
 * 它验证的是「service 层 + JDBC 落库 + 示例流程」，
 * <b>整个 {@code z-wf-web} 模块一个端点都没被跑过</b>。
 * 而这一层恰好是"最会静默出错"的地方：
 * <ul>
 *   <li>VO 映射漏字段 / 映射了不该暴露的持久化字段（{@code revision} / {@code arrivedActivities}）</li>
 *   <li>异常 → HTTP 状态码没接上（乐观锁 409 掉成 500，前端就会一律弹"系统错误"而不重试）</li>
 *   <li>分页 {@code total} 报成当前页条数，前端分页器直接错乱</li>
 *   <li>Controller 里 {@code Result.fail()} 走 200，与 advice 的 400 口径不一致</li>
 * </ul>
 * 这些全是"类能编译、接口能调、但线上不对"的类型，靠单测看不出来。
 *
 * <p>断言全部针对<b>正确行为</b>而不是当前实现的行为 —— 让失败直接指向缺陷。
 *
 * @author zifang
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2-test")
class WfWebApiTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private com.zifang.z.wf.core.hook.WfHookDispatcher dispatcher;

    @Autowired
    private com.zifang.z.wf.core.service.WfTaskService taskService;

    @Autowired
    private com.zifang.z.wf.core.service.WfRepositoryService repositoryService;

    private final ObjectMapper json = new ObjectMapper();

    // ==================== 健康检查 ====================

    @Test
    @DisplayName("健康检查：真查一次库，报出持久化实现与定义数")
    void healthReportsRealStorageState() throws Exception {
        Map<String, Object> body = asMap(getOk("/api/wf/health").get("data"));
        assertEquals("UP", body.get("status"));
        assertEquals("z-wf", body.get("engine"));
        assertEquals("JdbcWorkflowPersistence", body.get("persistence"));
        assertTrue(((Number) body.get("definitionCount")).intValue() >= 2,
                "示例流程应已自动部署");
    }

    // ==================== 发起 → 待办 → 办理 ====================

    @Test
    @DisplayName("全链路走 HTTP：发起 → 查待办 → 查详情 → 办理 → 查轨迹")
    void fullApprovalChainOverHttp() throws Exception {
        String businessKey = "WEB-LEAVE-" + System.nanoTime();
        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 5);
        vars.put("leaderId", "web-leader");

        Map<String, Object> start = postOk("/api/approval-center/processes/start",
                body("definitionKey", "leaveProcess", "businessKey", businessKey,
                        "userId", "web-alice", "deptId", "d1", "variables", vars));
        String processId = (String) start.get("data");
        assertNotNull(processId, "应返回流程实例 id");

        // 待办：分页结构 + VO 字段
        Map<String, Object> page = getOk("/api/approval-center/tasks/todo?userId=web-leader");
        Map<String, Object> data = asMap(page.get("data"));
        List<Map<String, Object>> records = asList(data.get("records"));
        assertFalse(records.isEmpty(), "领导应有待办");
        assertEquals(1, records.size());
        Map<String, Object> summary = records.get(0);
        assertEquals("直属领导审批", summary.get("taskName"));
        assertEquals("leaveForm", summary.get("formKey"), "表单编码应经 VO 透出");
        assertEquals("leaveProcess", summary.get("processKey"),
                "processKey 来自所属实例，是 mapper 补的，不是任务表里的列");
        assertEquals(businessKey, summary.get("businessKey"));
        assertEquals("ASSIGNED", summary.get("status"));
        assertTrue(((Number) summary.get("dueDate")).longValue() > 0,
                "zifang:dueDate=PT24H 应换算成到期时间并以 epoch-millis 透出");

        String taskId = (String) summary.get("taskId");

        // 详情：多出意见、变量、轨迹
        Map<String, Object> detail = getOk("/api/approval-center/tasks/get?taskId=" + taskId);
        Map<String, Object> detailData = asMap(detail.get("data"));
        assertEquals(taskId, detailData.get("taskId"));
        assertFalse(asList(detailData.get("trail")).isEmpty(), "详情应带出流程轨迹");

        // 办理：days=5 ⇒ 走总经理
        Map<String, Object> completed = postOk("/api/approval-center/tasks/complete",
                body("taskId", taskId, "userId", "web-leader", "comment", "同意"));
        assertEquals("ACTIVE", asMap(completed.get("data")).get("status"));

        // CEO 是 BPMN 里写死的办理人（zifang:assignee="ceo"），不像领导那样由 ${leaderId} 注入
        Map<String, Object> ceoPage = getOk("/api/approval-center/tasks/todo?userId=ceo");
        List<Map<String, Object>> ceoTodos = asList(asMap(ceoPage.get("data")).get("records"));
        assertTrue(ceoTodos.stream().anyMatch(t -> businessKey.equals(t.get("businessKey"))),
                "days=5 应走总经理审批，且只匹配本单（其它用例也会产生 ceo 待办）");

        // 轨迹：能看到两次流转
        Map<String, Object> trail = getOk("/api/wf/process/trail?processInstanceId=" + processId);
        assertFalse(asList(trail.get("data")).isEmpty(), "轨迹不应为空");

        // 流程详情：实例 + 待办 + 轨迹 + 变量
        Map<String, Object> procDetail =
                getOk("/api/approval-center/processes/get?processInstanceId=" + processId);
        Map<String, Object> pd = asMap(procDetail.get("data"));
        assertEquals(processId, pd.get("processInstanceId"));
        assertFalse(asList(pd.get("openTasks")).isEmpty(), "总经理待办应在流程详情里");
        assertFalse(asList(pd.get("trail")).isEmpty());
        assertTrue(asMap(pd.get("variables")).containsKey("days"),
                "流程变量应透出，前端审批页要靠它渲染表单");
    }

    @Test
    @DisplayName("仪表盘：待办数、已办数、我发起的数")
    void dashboardCounts() throws Exception {
        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 2);
        vars.put("leaderId", "dash-leader");
        postOk("/api/approval-center/processes/start",
                body("definitionKey", "leaveProcess", "businessKey", "WEB-DASH-" + System.nanoTime(),
                        "userId", "dash-alice", "variables", vars));

        // 领导视角：待办数
        Map<String, Object> leaderStats = asMap(
                getOk("/api/approval-center/dashboard?userId=dash-leader").get("data"));
        assertTrue(((Number) leaderStats.get("todoCount")).longValue() >= 1);
        assertTrue(((Number) leaderStats.get("overdueCount")).longValue() >= 0);

        // 发起人视角：myProcessCount 按 startUserId 统计
        Map<String, Object> starterStats = asMap(
                getOk("/api/approval-center/dashboard?userId=dash-alice").get("data"));
        assertTrue(((Number) starterStats.get("myProcessCount")).longValue() >= 1,
                "发起人应能看到自己发起的流程数");
    }

    // ==================== 候选池 / 认领 ====================

    @Test
    @DisplayName("候选池与认领：可认领列表 → 认领 → 任务变为已指派")
    void claimableAndClaim() throws Exception {
        postOk("/api/approval-center/processes/start",
                body("definitionKey", "fiveLookEvaluation",
                        "businessKey", "WEB-FIVE-" + System.nanoTime(),
                        "userId", "web-carol"));

        Map<String, Object> page = getOk("/api/approval-center/tasks/claimable"
                + "?userId=web-biz-1&groups=biz-reviewers");
        List<Map<String, Object>> records = asList(asMap(page.get("data")).get("records"));
        assertEquals(1, records.size(), "业务看应恰好 1 条可认领");

        String taskId = (String) records.get(0).get("taskId");
        Map<String, Object> claimed = postOk("/api/wf/task/claim",
                body("taskId", taskId, "userId", "web-biz-1",
                        "targetGroups", Collections.singletonList("biz-reviewers")));
        Map<String, Object> task = asMap(claimed.get("data"));
        assertEquals("web-biz-1", task.get("assignee"));
        assertEquals("ASSIGNED", task.get("status"));
    }

    // ==================== 转办 / 委派 ====================

    @Test
    @DisplayName("转办改 assignee、委派只改 owner —— 两种语义在 HTTP 上可区分")
    void transferAndDelegateHaveDifferentSemantics() throws Exception {
        // 委派：处理权转移，责任人不变
        String delegateTaskId = startAndGetLeaderTask("flow-delegate");
        Map<String, Object> delegated = asMap(postOk("/api/wf/task/delegate",
                body("taskId", delegateTaskId, "userId", "flow-delegate",
                        "targetUserId", "flow-staff", "comment", "请你处理")).get("data"));
        assertEquals("flow-delegate", delegated.get("assignee"),
                "委派不转移责任人，assignee 不该变");
        assertEquals("flow-staff", delegated.get("owner"));
        assertEquals("flow-staff", delegated.get("handler"), "handler 在委派态取 owner");

        // 转办：责任人真的换人
        String transferTaskId = startAndGetLeaderTask("flow-transfer");
        Map<String, Object> transferred = asMap(postOk("/api/wf/task/transfer",
                body("taskId", transferTaskId, "userId", "flow-transfer",
                        "targetUserId", "flow-newowner")).get("data"));
        assertEquals("flow-newowner", transferred.get("assignee"), "转办必须改 assignee");
        assertEquals(null, transferred.get("owner"), "转办后不应残留 owner");
    }

    @Test
    @DisplayName("委派后只有当前处理人能转办（原责任人已交出处理权）")
    void afterDelegateOnlyHandlerCanTransfer() throws Exception {
        String taskId = startAndGetLeaderTask("flow-handler");
        postOk("/api/wf/task/delegate",
                body("taskId", taskId, "userId", "flow-handler",
                        "targetUserId", "flow-handler-delegate"));

        // 原责任人转办 ⇒ 400。委派 = 处理权转移，他此刻不该还能改责任人。
        ResponseEntity<String> denied = exchange(HttpMethod.POST, "/api/wf/task/transfer",
                body("taskId", taskId, "userId", "flow-handler", "targetUserId", "someone"));
        assertEquals(HttpStatus.BAD_REQUEST, denied.getStatusCode(),
                "原责任人已把处理权交出，不该还能转办");

        // 被委派人转办 ⇒ 200
        postOk("/api/wf/task/transfer",
                body("taskId", taskId, "userId", "flow-handler-delegate", "targetUserId", "flow-final"));
    }

    // ==================== 流程操作 ====================

    @Test
    @DisplayName("流程操作：挂起 → 激活 → 加评论 → 总览 → 执行令牌")
    void processOperations() throws Exception {
        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 4);
        vars.put("leaderId", "ops-leader");
        Map<String, Object> started = postOk("/api/approval-center/processes/start",
                body("definitionKey", "leaveProcess", "businessKey", "WEB-OPS-" + System.nanoTime(),
                        "userId", "ops-alice", "variables", vars));
        String processId = (String) started.get("data");

        postOk("/api/wf/process/suspend",
                body("processInstanceId", processId, "userId", "ops-admin", "reason", "等资料"));
        Map<String, Object> suspended = getOk(
                "/api/approval-center/processes/get?processInstanceId=" + processId);
        assertEquals("SUSPENDED", asMap(suspended.get("data")).get("status"));

        postOk("/api/wf/process/activate", body("processInstanceId", processId));
        Map<String, Object> activated = getOk(
                "/api/approval-center/processes/get?processInstanceId=" + processId);
        assertEquals("ACTIVE", asMap(activated.get("data")).get("status"));

        postOk("/api/wf/process/comment?type=comment",
                body("processInstanceId", processId, "userId", "ops-boss", "content", "已阅"));
        Map<String, Object> comments = getOk(
                "/api/wf/process/comments?processInstanceId=" + processId);
        assertEquals(1, asList(comments.get("data")).size(), "评论应能读回");

        assertNotNull(getOk("/api/wf/process/overview?processInstanceId=" + processId));
        assertNotNull(getOk("/api/wf/process/executions?processInstanceId=" + processId));
    }

    // ==================== 运行期故障查询 ====================

    @Test
    @DisplayName("故障端点：失败上报后查得到，且要同时看得到「在等什么」与「报了什么错」")
    void incidentEndpointOverHttp() throws Exception {
        deployExternalProcess();
        String suffix = String.valueOf(System.nanoTime());
        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webExternalProcess", "businessKey", "WEB-INC-" + suffix,
                        "userId", "inc-owner-" + suffix)).get("data");

        // 前置条件：还没失败过时不该报故障 —— 否则这个端点等于永远有东西，没人再看
        assertEquals(0, count("/api/wf/incidents/count?processInstanceId=" + processId),
                "没失败过就不该是故障");

        String worker = "web-inc-w-" + suffix;
        Map<String, Object> fetched = postOk("/api/wf/external-tasks/fetch",
                body("topic", "web.notify", "workerId", worker, "maxTasks", 10));
        String taskId = null;
        for (Map<String, Object> t : asList(fetched.get("data"))) {
            if (processId.equals(t.get("processInstanceId"))) {
                taskId = (String) t.get("id");
            }
        }
        assertNotNull(taskId, "前置条件：应当领到本实例的活");

        postOk("/api/wf/external-tasks/" + taskId + "/fail",
                body("workerId", worker, "errorMessage", "下游 503"));

        Map<String, Object> page = asMap(getOk(
                "/api/wf/incidents?processInstanceId=" + processId).get("data"));
        List<Map<String, Object>> rows = asList(page.get("records"));
        assertEquals(1, rows.size(), "一条失败就是一条故障。实际 " + rows.size());
        assertEquals(1, ((Number) page.get("total")).intValue(), "total 要与列表一致");

        Map<String, Object> row = rows.get(0);
        assertTrue(String.valueOf(row.get("errorMessage")).contains("下游 503"),
                "要能看到为什么失败。实际 " + row.get("errorMessage"));
        assertEquals("EXTERNAL", row.get("jobType"));
        assertEquals("web.notify", row.get("subscriptionName"),
                "要能看到它在等什么（外部任务的主题）—— 与失败原因分开展示，"
                        + "挤在一列就只能说出一个");
        // 上报一次只扣掉一次重试（3 -> 2），所以它仍在自动重试的范围内。
        // 这一侧与"彻底不动"必须分得开：前者可以等，后者必须人去看
        assertEquals(Boolean.TRUE, row.get("retryable"), "只失败一次，重试还剩着");

        assertEquals(1, count("/api/wf/incidents/count?processInstanceId=" + processId
                + "&retriesExhausted=false"), "还没扣完的应当归到还在重试那一侧");
        assertEquals(0, count("/api/wf/incidents/count?processInstanceId=" + processId
                + "&retriesExhausted=true"), "还没扣完就不该出现在彻底不动里");

        // list 端点的同一个参数要一并验：count 与 list 是两个方法，
        // 只验 count 会漏掉「列表这条路没把参数传下去」这种错 —— 而它照样返回
        // 一份看起来完整的列表，调用方无从察觉筛选压根没生效
        String listBase = "/api/wf/incidents?processInstanceId=" + processId;
        assertEquals(1, asList(asMap(getOk(
                listBase + "&retriesExhausted=false").get("data")).get("records")).size(),
                "list 也要能按还在重试筛出这一条");
        assertEquals(0, asList(asMap(getOk(
                listBase + "&retriesExhausted=true").get("data")).get("records")).size(),
                "list 也不能把还没扣完的混进彻底不动那一侧");

        // 按类型筛：订阅的故障不该混进外部任务的结果
        String base = "/api/wf/incidents/count?processInstanceId=" + processId + "&type=";
        assertEquals(1, count(base + "EXTERNAL"), "外部任务那条要能被筛出来");
        assertEquals(0, count(base + "MESSAGE"),
                "类型筛错了会静默返回空，而调用方会读成「这一类没有故障」");

        // 总览里要能一眼看到故障 —— 排障的人通常先开总览，而不是单独查订阅
        Map<String, Object> overview = asMap(getOk(
                "/api/wf/process/overview?processInstanceId=" + processId).get("data"));
        assertEquals(1, asList(overview.get("incidents")).size(),
                "总览里必须带上 incidents —— 只给订阅的话「单子不动了」"
                        + "分不清是在耐心等还是已经炸了");

        // 拼错类型要报错并列出合法值，不能当没传
        ResponseEntity<String> bad = exchange(HttpMethod.GET,
                "/api/wf/incidents/count?type=NOT_A_TYPE", null);
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode(),
                "拼错的类型必须报错而不是被忽略: " + bad.getBody());
        assertTrue(bad.getBody().contains("EXTERNAL"),
                "报错要列出合法值。实际: " + bad.getBody());
    }

    // ==================== 实例迁移 ====================

    @Test
    @DisplayName("实例迁移端点：等消息的单子没有任务可跳，只有 move 搬得动，且真的建出待办")
    void instanceMigrationEndpoint() throws Exception {
        deployMoveProcess();
        String tag = "WEB-MOVE-" + System.nanoTime();
        String leader = "mv-leader-" + tag;
        Map<String, Object> vars = new HashMap<>();
        vars.put("moveLeader", leader);
        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webMoveProcess", "businessKey", tag,
                        "userId", "mv-owner", "variables", vars)).get("data");

        // 前置条件一：此刻只有两条订阅，一个待办都没有。
        // 这正是 move 存在的理由 —— jump 的入口是任务，没有任务就跳不动
        List<Map<String, Object>> before = asList(asMap(getOk(
                "/api/wf/subscriptions?processInstanceId=" + processId).get("data"))
                .get("records"));
        assertEquals(2, before.size(), "两条分支各一条订阅。实际 " + before.size());
        assertTrue(asList(asMap(getOk("/api/approval-center/tasks/todo?userId=" + leader)
                .get("data")).get("records")).isEmpty(),
                "迁移前不该有待办 —— 有的话这条用例就测不到 move 存在的理由了");

        postOk("/api/wf/process/move",
                body("processInstanceId", processId, "targetActivityId", "mvManual",
                        "sourceActivityId", "mvWaitMsg", "userId", "mv-ops", "reason", "等太久了"));

        // 期望值 1：迁到人工节点要真的建出一个待办。用 leave 推进的话目标节点会被
        // 直接走过，这里就是 0 —— 迁完了却什么都没人可办，等于白迁
        List<Map<String, Object>> todos = asList(asMap(getOk(
                "/api/approval-center/tasks/todo?userId=" + leader).get("data")).get("records"));
        assertEquals(1, todos.size(), "迁到人工节点必须建出一个待办。实际 " + todos.size() + " 个");
        assertEquals("mvManual", todos.get(0).get("definitionId"),
                "待办要挂在目标节点上，而不是留在原地或落到别处");

        // 源 token 的订阅必须随之撤掉：留着的话那条消息照常到达，
        // 会推进一条已经迁走的分支 —— 同一个节点被走两遍
        List<Map<String, Object>> after = asList(asMap(getOk(
                "/api/wf/subscriptions?processInstanceId=" + processId).get("data"))
                .get("records"));
        assertEquals(1, after.size(),
                "迁走一条分支后只剩另一条分支的订阅。实际 " + after.size()
                        + " 条 —— 源订阅没撤掉的话，消息到达会推进一条已经迁走的分支");
        assertEquals("mvWaitSignal", after.get(0).get("activityId"),
                "剩下的是没被迁移的那条分支的订阅");

        // 一路走通：迁过去的待办能正常办结，剩下的那条迁完也能办结，流程最终到终态。
        // 只断言"待办出现了"不够 —— 建出一个办不掉的待办同样是坏的
        postOk("/api/approval-center/tasks/complete",
                body("taskId", todos.get(0).get("taskId"), "userId", leader, "comment", "同意"));
        postOk("/api/wf/process/move",
                body("processInstanceId", processId, "targetActivityId", "mvManual",
                        "userId", "mv-ops", "reason", "另一条也转人工"));
        for (Map<String, Object> record : asList(asMap(getOk(
                "/api/approval-center/tasks/todo?userId=" + leader).get("data")).get("records"))) {
            postOk("/api/approval-center/tasks/complete",
                    body("taskId", record.get("taskId"), "userId", leader, "comment", "同意"));
        }
        assertEquals("COMPLETED", asMap(getOk(
                "/api/approval-center/processes/get?processInstanceId=" + processId)
                .get("data")).get("status"),
                "两条分支都办结后流程应当结束 —— 不结束说明迁移把某条 token 弄丢了");
    }

    // ==================== 变量 ====================

    @Test
    @DisplayName("变量变更审计端点：按流程实例+变量名查，且不把值里的 '-> ' 当分隔符")
    void variableChangeAuditEndpoint() throws Exception {
        String businessKey = "WEB-AUDIT-" + System.nanoTime();
        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put("days", 1);
        vars.put("leaderId", "audit-leader");
        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "leaveProcess", "businessKey", businessKey,
                        "userId", "audit-alice", "variables", vars)).get("data");

        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("days", 5);
        // 值里带 "->"：审计描述用 " -> " 分隔新旧值，引擎不该把它拆成两个字段
        values.put("remark", "紧急 -> 明天再办");
        postOk("/api/wf/process/variables",
                body("processInstanceId", processId, "userId", "audit-admin",
                        "values", values));

        Map<String, Object> listed = getOk("/api/wf/history/variable-changes"
                + "?processInstanceId=" + processId + "&variableName=days");
        Map<String, Object> page = asMap(listed.get("data"));
        List<Map<String, Object>> rows = asList(page.get("records"));
        assertEquals(1, rows.size(), "只该有 days 自己的那一条，实际 " + rows);
        Map<String, Object> row = rows.get(0);
        assertEquals("days", row.get("variableName"));
        assertEquals("audit-admin", row.get("changedBy"), "审计要能回答'谁改的'");
        // 流程启动不写变量审计（启动是"建流程"不是"改变量"），所以 days 只有这一条
        assertEquals("1 -> 5", row.get("change"));

        // 值里含分隔符的那条：change 必须原样保留，引擎不替调用方切
        List<Map<String, Object>> remarks = asList(asMap(getOk(
                "/api/wf/history/variable-changes?processInstanceId=" + processId
                        + "&variableName=remark").get("data")).get("records"));
        assertEquals(1, remarks.size());
        assertEquals("remark", remarks.get(0).get("variableName"));
        assertEquals("(未设置) -> 紧急 -> 明天再办", remarks.get(0).get("change"),
                "值里的 '-> ' 被当分隔符切了，审计给出错的值比不给值更糟");
        assertEquals("remark: (未设置) -> 紧急 -> 明天再办", remarks.get(0).get("content"));

        // total 来自 count 而非当前页长度
        assertEquals(1, ((Number) page.get("total")).intValue());
    }

    @Test
    @DisplayName("删定义端点：有在途实例时拒绝，终止后可删")
    void deleteDefinitionEndpoint() throws Exception {
        String key = "restDel-" + (System.nanoTime() % 100000);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"" + key + "\" name=\"待删流程\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"ds1\"/>\n"
                + "    <userTask id=\"dapprove\" name=\"审批\" zifang:assignee=\"d-boss\"/>\n"
                + "    <endEvent id=\"de1\"/>\n"
                + "    <sequenceFlow id=\"df1\" sourceRef=\"ds1\" targetRef=\"dapprove\"/>\n"
                + "    <sequenceFlow id=\"df2\" sourceRef=\"dapprove\" targetRef=\"de1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        postOk("/api/wf/definitions/deploy", body("key", key, "xml", xml));

        Map<String, Object> start = postOk("/api/approval-center/processes/start",
                body("definitionKey", key, "businessKey", "DEL-BUS-1", "userId", "del-alice"));
        String processId = (String) start.get("data");
        assertNotNull(processId);

        // 有在途实例时必须拒绝
        ResponseEntity<String> refused = exchange(HttpMethod.DELETE,
                "/api/wf/definitions/definition?key=" + key + "&version=1", null);
        assertEquals(statusOf("onDefinition"), refused.getStatusCode(),
                "定义一删在办的实例就推不动了，端点必须拒绝。实际: " + refused.getBody());
        assertTrue(refused.getBody().contains("在途实例"),
                "报错要点明挡它的是在途实例。实际: " + refused.getBody());

        // 终止后可以删
        postOk("/api/wf/process/terminate",
                body("processInstanceId", processId, "userId", "del-admin", "reason", "作废"));
        ResponseEntity<String> removed = exchange(HttpMethod.DELETE,
                "/api/wf/definitions/definition?key=" + key + "&version=1", null);
        assertEquals(HttpStatus.OK, removed.getStatusCode(),
                "终止后应当能删。实际: " + removed.getBody());
        assertTrue(asList(getOk("/api/wf/definitions?keyLike=" + key).get("data")).isEmpty(),
                "删完不该再查得到这个定义");
    }

    @Test
    @DisplayName("候选池端点：加人 → 真的能认领 → 移出 → 认领不了")
    void candidatePoolEndpoints() throws Exception {
        String businessKey = "WEB-CAND-" + System.nanoTime();
        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put("days", 1);
        vars.put("leaderId", "cand-leader");
        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "leaveProcess", "businessKey", businessKey,
                        "userId", "cand-alice", "variables", vars)).get("data");

        List<Map<String, Object>> todos = asList(asMap(
                getOk("/api/approval-center/tasks/todo?userId=cand-leader")
                        .get("data")).get("records"));
        assertEquals(1, todos.size(), "应恰好一张待办。实际 " + todos);
        String taskId = (String) todos.get(0).get("taskId");

        // 加一个候选人。leaveProcess 的审批节点带 ${leaderId} 表达式，
        // 运行时再加一个人属于典型的"领导休假要加派"
        postOk("/api/wf/task/candidate", body("taskId", taskId,
                "type", "candidateUser", "target", "cand-relief", "action", "add"));

        // 判据是行为：这个人应当真的能看到待办、并且真的能认领
        Map<String, Object> reliefPage = asMap(
                getOk("/api/approval-center/tasks/todo?userId=cand-relief").get("data"));
        assertEquals(1, asList(reliefPage.get("records")).size(),
                "加了候选却还是看不到待办，等于加人没生效。实际: " + reliefPage.get("records"));

        // 这里刻意不断言可认领列表：leaveProcess 的审批节点用 ${leaderId} 直接指派了
        // 办理人，任务已分配，而可认领列表按定义只含未分配的任务 ——
        // 那是正确行为，不是缺陷。可认领逻辑的覆盖在 WfCandidatePoolTest 里用
        // 真正未分配的任务做。
        // 移出候选人 —— 必须在转办之前验：待办语义是"或"，
        // 一旦他成了 assignee，再移出候选也照样能看到（那是正确的）
        postOk("/api/wf/task/candidate", body("taskId", taskId,
                "type", "candidateUser", "target", "cand-relief", "action", "remove"));
        assertTrue(asList(asMap(getOk("/api/approval-center/tasks/todo?userId=cand-relief")
                .get("data")).get("records")).isEmpty(),
                "移出候选人后待办里不该还有这张单");

        // 加回来，然后转办：leaveProcess 的节点已指派给 leader，
        // 所以"换人"走转办而不是认领
        postOk("/api/wf/task/candidate", body("taskId", taskId,
                "type", "candidateUser", "target", "cand-relief", "action", "add"));
        Map<String, Object> transferred = postOk("/api/wf/task/transfer",
                body("taskId", taskId, "userId", "cand-leader",
                        "targetUserId", "cand-relief", "comment", "领导休假，加派人接手"));
        assertEquals("cand-relief", asMap(transferred.get("data")).get("assignee"),
                "加派的候选人应当能从原办理人手里把单转过来");

        // type 写错要报出来，不能猜
        ResponseEntity<String> badType = exchange(HttpMethod.POST, "/api/wf/task/candidate",
                body("taskId", taskId, "type", "candidateUsers", "target", "x"));
        assertEquals(statusOf("onEngine"), badType.getStatusCode(),
                "type 拼错应当报错而不是改错列表。实际: " + badType.getBody());
    }

    @Test
    @DisplayName("任务挂起端点：挂起 → 办结被拒 → 待办仍可见 → 恢复 → 办结成功")
    void taskSuspensionEndpoints() throws Exception {
        String businessKey = "WEB-SUSP-" + System.nanoTime();
        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put("days", 1);
        vars.put("leaderId", "susp-leader");
        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "leaveProcess", "businessKey", businessKey,
                        "userId", "susp-alice", "variables", vars)).get("data");

        // 直属领导由 ${leaderId} 决定，先把待办取出来
        List<Map<String, Object>> todos = asList(asMap(
                getOk("/api/approval-center/tasks/todo?userId=susp-leader")
                        .get("data")).get("records"));
        assertEquals(1, todos.size(), "应恰好一张待办。实际 " + todos);
        String taskId = (String) todos.get(0).get("taskId");
        assertEquals(Boolean.FALSE, todos.get(0).get("suspended"), "新建任务不该是挂起态");

        Map<String, Object> suspended = asMap(postOk("/api/wf/task/suspend",
                body("taskId", taskId, "userId", "susp-supervisor")).get("data"));
        assertEquals(Boolean.TRUE, suspended.get("suspended"), "挂起后视图要带 suspended 标记");

        // 办结必须被拒，且报的是"挂起"不是"已结束"
        ResponseEntity<String> rejected = exchange(HttpMethod.POST,
                "/api/approval-center/tasks/complete",
                body("taskId", taskId, "userId", "susp-leader", "comment", "同意"));
        assertEquals(statusOf("onEngine"), rejected.getStatusCode(),
                "挂起的任务还能办结，等于挂起只是个摆设。实际: " + rejected.getBody());
        assertTrue(rejected.getBody().contains("已挂起"),
                "报错要点明挂起。实际: " + rejected.getBody());

        // 仍能在待办里看到，并带上挂起标记（前端显示[暂停]角标而不是把它藏起来）
        List<Map<String, Object>> afterSuspend = asList(asMap(
                getOk("/api/approval-center/tasks/todo?userId=susp-leader")
                        .get("data")).get("records"));
        assertEquals(1, afterSuspend.size(), "挂起后待办不该消失 —— 用户的感受会是[单子丢了]");
        assertEquals(Boolean.TRUE, afterSuspend.get(0).get("suspended"),
                "待办列表要能看出这张被挂起了");

        postOk("/api/wf/task/activate", body("taskId", taskId, "userId", "susp-supervisor"));
        Map<String, Object> done = postOk("/api/approval-center/tasks/complete",
                body("taskId", taskId, "userId", "susp-leader", "comment", "同意"));
        assertNotNull(done.get("data"), "恢复后应当能正常办结");
    }

    @Test
    @DisplayName("定义管理端点：部署 → 停用 → 启动被拒 → 启用 → 模型回读")
    void definitionManagementEndpoints() throws Exception {
        String key = "restDeploy-" + (System.nanoTime() % 100000);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"" + key + "\" name=\"临时流程\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"ds1\"/>\n"
                + "    <userTask id=\"dapprove\" name=\"审批\" zifang:assignee=\"d-boss\"/>\n"
                + "    <endEvent id=\"de1\"/>\n"
                + "    <sequenceFlow id=\"df1\" sourceRef=\"ds1\" targetRef=\"dapprove\"/>\n"
                + "    <sequenceFlow id=\"df2\" sourceRef=\"dapprove\" targetRef=\"de1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";

        Map<String, Object> deployed = asMap(postOk("/api/wf/definitions/deploy",
                body("key", key, "xml", xml)).get("data"));
        assertEquals(key, deployed.get("key"));
        assertEquals(1, ((Number) deployed.get("version")).intValue());
        assertEquals(Boolean.FALSE, deployed.get("suspended"));
        assertEquals(Boolean.TRUE, deployed.get("hasSourceXml"), "刚部署完就该有原始 XML");

        // 模型回读：部署进去的 XML 读得回来，模型编辑器集成靠它
        String model = (String) getOk("/api/wf/definitions/model?key=" + key + "&version=1")
                .get("data");
        assertTrue(model.contains("dapprove"), "回读的应是原始 XML。实际: " + model);

        // 按名称模糊能查到这个定义
        assertEquals(1, asList(getOk("/api/wf/definitions?keyLike=" + key).get("data")).size());

        // 停用
        postOk("/api/wf/definitions/suspend?key=" + key + "&version=1", null);
        List<Map<String, Object>> suspendedList = asList(
                getOk("/api/wf/definitions?keyLike=" + key).get("data"));
        assertEquals(1, suspendedList.size());
        assertEquals(Boolean.TRUE, suspendedList.get(0).get("suspended"));
        // 按停用状态过滤：不再出现在"在用"列表里
        assertTrue(asList(getOk("/api/wf/definitions?suspended=false").get("data")).stream()
                .noneMatch(d -> key.equals(d.get("key"))),
                "停用后不该出现在[在用]列表里");

        // 停用后启动必须被拒 —— 这条是整个功能的关键。
        // 状态码跟着 WfExceptionAdvice 的 @ResponseStatus 走，不写死数值：
        // 状态码是 advice 的职责，本用例只关心"确实被拒了，且拒绝有原因"。
        ResponseEntity<String> rejectedResponse = exchange(HttpMethod.POST,
                "/api/approval-center/processes/start",
                body("definitionKey", key, "businessKey", "SUSP-1", "userId", "alice"));
        assertEquals(statusOf("onDefinition"), rejectedResponse.getStatusCode(),
                "停用的版本还能启动新实例，等于停用只是个摆设。实际: " + rejectedResponse.getBody());
        assertTrue(rejectedResponse.getBody().contains("已停用"),
                "拒绝的原因要点明是停用，调用方才知道该去启用而不是查流程定义。实际: "
                        + rejectedResponse.getBody());

        // 启用后恢复
        postOk("/api/wf/definitions/activate?key=" + key + "&version=1", null);
        Map<String, Object> started = postOk("/api/approval-center/processes/start",
                body("definitionKey", key, "businessKey", "SUSP-2", "userId", "alice"));
        assertNotNull(started.get("data"), "启用后应当能启动");
    }

    @Test
    @DisplayName("变量端点：读 → 批量写 → 删除，全程留审计")
    void variableEndpoints() throws Exception {
        String businessKey = "WEB-VAR-" + System.nanoTime();
        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put("days", 1);
        vars.put("leaderId", "var-leader");
        Map<String, Object> start = postOk("/api/approval-center/processes/start",
                body("definitionKey", "leaveProcess", "businessKey", businessKey,
                        "userId", "var-alice", "variables", vars));
        String processId = (String) start.get("data");

        // 启动时写入的变量读得到
        Map<String, Object> before = getOk("/api/wf/process/variables?processInstanceId=" + processId);
        assertEquals(1, asMap(before.get("data")).get("days"));

        // 批量写
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("days", 5);
        values.put("amount", 3000);
        Map<String, Object> updated = postOk("/api/wf/process/variables",
                body("processInstanceId", processId, "userId", "var-admin",
                        "values", values));
        assertEquals("ACTIVE", asMap(updated.get("data")).get("status"));

        Map<String, Object> after = asMap(
                getOk("/api/wf/process/variables?processInstanceId=" + processId).get("data"));
        assertEquals(5, after.get("days"));
        assertEquals(3000, after.get("amount"));

        // 变量变更必须能从评论里查到（审计留痕）
        List<Map<String, Object>> comments = asList(
                getOk("/api/wf/process/comments?processInstanceId=" + processId).get("data"));
        java.util.Set<String> auditedNames = new java.util.LinkedHashSet<String>();
        int variableAudits = 0;
        for (Map<String, Object> comment : comments) {
            if ("variable".equals(comment.get("type"))) {
                variableAudits++;
                assertEquals("var-admin", comment.get("userId"),
                        "变量变更必须留下操作人，否则无法回答'谁改的'");
                // 批量写是「一个变量一条」而不是整批拼一条（拼一起就没法按变量名精确查，
                // 查 amount 会顺带命中 discount_amount），所以变量名要从各自那条里取
                auditedNames.add(String.valueOf(comment.get("content")).split(":")[0].trim());
            }
        }
        assertTrue(variableAudits > 0, "变量变更应当留下审计记录，评论列表: " + comments);
        assertTrue(auditedNames.contains("amount"),
                "审计应点名 amount，实际点名的变量: " + auditedNames);
        assertTrue(auditedNames.contains("days"),
                "审计应点名 days，实际点名的变量: " + auditedNames);

        // 删除走独立分支，不靠"值为 null"
        postOk("/api/wf/process/variables",
                body("processInstanceId", processId, "userId", "var-admin",
                        "names", java.util.Arrays.asList("amount"), "remove", true));
        Map<String, Object> deleted = asMap(
                getOk("/api/wf/process/variables?processInstanceId=" + processId).get("data"));
        assertFalse(deleted.containsKey("amount"), "amount 应已被删除");
        assertTrue(deleted.containsKey("days"), "days 不该被动到");
    }

    @Test
    @DisplayName("变量值传 null 被拒：400 且不静默变成删除")
    void nullVariableValueRejected() throws Exception {
        String businessKey = "WEB-VARNULL-" + System.nanoTime();
        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put("days", 1);
        vars.put("leaderId", "varnull-leader");
        Map<String, Object> start = postOk("/api/approval-center/processes/start",
                body("definitionKey", "leaveProcess", "businessKey", businessKey,
                        "userId", "varnull-alice", "variables", vars));
        String processId = (String) start.get("data");

        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("days", null);
        ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/wf/process/variables",
                body("processInstanceId", processId, "userId", "var-admin", "values", values));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "null 赋值应当 400，而不是变成一次静默删除: " + response.getBody());

        Map<String, Object> after = asMap(
                getOk("/api/wf/process/variables?processInstanceId=" + processId).get("data"));
        assertEquals(1, after.get("days"), "被拒绝的写入不能有任何效果");
    }

    // ==================== 流程定义 / 分组 ====================

    @Test
    @DisplayName("定义查询：可发起列表、版本列表、分组、图结构")
    void definitionEndpoints() throws Exception {
        List<Map<String, Object>> defs =
                asList(getOk("/api/approval-center/processes/definitions").get("data"));
        assertFalse(defs.isEmpty());
        assertTrue(defs.stream().anyMatch(d -> "leaveProcess".equals(d.get("key"))));
        for (Map<String, Object> d : defs) {
            assertTrue(((Number) d.get("nodeCount")).intValue() > 0, "节点数应透出");
        }

        assertFalse(asList(getOk("/api/approval-center/processes/versions?key=leaveProcess")
                .get("data")).isEmpty(), "应能查到版本列表");

        assertFalse(asList(getOk("/api/wf/group/list").get("data")).isEmpty(), "分组不应为空");
        assertFalse(asList(getOk("/api/wf/group/processes?category=审批").get("data")).isEmpty());

        // 图结构：给设计器渲染用，必须带出节点与连线
        Map<String, Object> graph = asMap(
                getOk("/api/wf/group/detail?key=leaveProcess").get("data"));
        assertEquals("leaveProcess", graph.get("key"));
        assertFalse(asList(graph.get("nodes")).isEmpty(), "设计器要靠 nodes 渲染");
        assertFalse(asList(graph.get("flows")).isEmpty(), "设计器要靠 flows 连线");
    }

    // ==================== 分页契约 ====================

    @Test
    @DisplayName("分页：total 是总条数，不是当前页条数")
    void pageTotalIsTotalNotPageSize() throws Exception {
        // 造 3 个同一发起人的流程，然后只要 1 条
        for (int i = 0; i < 3; i++) {
            Map<String, Object> vars = new HashMap<>();
            vars.put("days", 1);
            vars.put("leaderId", "page-leader-" + i);
            postOk("/api/approval-center/processes/start",
                    body("definitionKey", "leaveProcess",
                            "businessKey", "WEB-PAGE-" + i + "-" + System.nanoTime(),
                            "userId", "page-owner", "variables", vars));
        }

        Map<String, Object> page = getOk(
                "/api/approval-center/my-processes?userId=page-owner&pageNum=1&pageSize=1");
        Map<String, Object> data = asMap(page.get("data"));
        assertEquals(1, asList(data.get("records")).size(), "pageSize=1 应只返回 1 条");
        assertTrue(((Number) data.get("total")).longValue() >= 3,
                "total 应是总条数（≥3），报成 1 会让前端分页器只剩一页 —— "
                        + "实际返回 total=" + data.get("total"));
    }

    @Test
    @DisplayName("分页：搜索流程的 total 同样要是总条数")
    void searchTotalIsTotal() throws Exception {
        // 搜索按发起人过滤（businessKey 是精确匹配，前缀搜不到），
        // 用独有 userId 隔离，避免数到别的用例的数据
        String owner = "search-owner-" + System.nanoTime();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> vars = new HashMap<>();
            vars.put("days", 1);
            vars.put("leaderId", owner + "-leader-" + i);
            postOk("/api/approval-center/processes/start",
                    body("definitionKey", "leaveProcess",
                            "businessKey", owner + "-" + i,
                            "userId", owner, "variables", vars));
        }

        Map<String, Object> found = getOk("/api/approval-center/processes/search?userId="
                + owner + "&pageNum=1&pageSize=1");
        Map<String, Object> data = asMap(found.get("data"));
        assertEquals(1, asList(data.get("records")).size(), "pageSize=1 只应返回 1 条");
        assertEquals(3L, ((Number) data.get("total")).longValue(),
                "total 应是总条数 3；报成 1（当前页条数）会让前端分页器只剩一页。"
                        + "实际返回 total=" + data.get("total"));
    }

    // ==================== VO 边界：不暴露持久化实体 ====================

    @Test
    @DisplayName("VO 边界：任务响应里不得出现持久化内部字段")
    void taskViewMustNotLeakPersistenceInternals() throws Exception {
        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 1);
        vars.put("leaderId", "leak-leader");
        postOk("/api/approval-center/processes/start",
                body("definitionKey", "leaveProcess", "businessKey", "WEB-LEAK-" + System.nanoTime(),
                        "userId", "leak-alice", "variables", vars));
        String taskId = firstTodoId("leak-leader");

        Map<String, Object> detail = asMap(
                getOk("/api/approval-center/tasks/get?taskId=" + taskId).get("data"));
        assertAbsent(detail, "revision", "乐观锁版本号是持久化内部字段");
        assertAbsent(detail, "executionId", "执行令牌 id 属于引擎内部结构");
        assertAbsent(detail, "parentTaskId", "父任务 id 属于持久化结构");
    }

    @Test
    @DisplayName("VO 边界：执行令牌接口不得直接返回持久化实体")
    void executionsMustNotExposePersistenceEntity() throws Exception {
        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 1);
        vars.put("leaderId", "tok-leader");
        Map<String, Object> started = postOk("/api/approval-center/processes/start",
                body("definitionKey", "leaveProcess", "businessKey", "WEB-TOK-" + System.nanoTime(),
                        "userId", "tok-alice", "variables", vars));
        String processId = (String) started.get("data");

        List<Map<String, Object>> tokens = asList(
                getOk("/api/wf/process/executions?processInstanceId=" + processId).get("data"));
        assertFalse(tokens.isEmpty(), "运行中的流程应有执行令牌");
        for (Map<String, Object> token : tokens) {
            assertAbsent(token, "arrivedActivities",
                    "arrivedActivities 是引擎的汇合判据内部状态，暴露出去等于把内部实现变成对外 API");
            assertAbsent(token, "variables", "令牌变量会与流程实例变量重复且含内部中间量");
        }
    }

    // ==================== 异常 → HTTP 状态码 ====================

    @Test
    @DisplayName("业务异常：任务不存在 / 越权认领 / 越权办结 ⇒ 400 而不是 500")
    void businessExceptionsMapTo400() throws Exception {
        // 任务详情查一个不存在的 id
        ResponseEntity<String> missing = exchange(
                HttpMethod.GET, "/api/approval-center/tasks/get?taskId=task-does-not-exist", null);
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode(),
                "查不到任务应返回 400 + 明确消息，返回 500 等于把内部栈暴露给前端");

        // 办结一个不存在的任务
        ResponseEntity<String> completeMissing = exchange(HttpMethod.POST,
                "/api/approval-center/tasks/complete",
                body("taskId", "task-nope", "userId", "u1", "comment", "x"));
        assertEquals(HttpStatus.BAD_REQUEST, completeMissing.getStatusCode());

        // 认领时漏传所属组 ⇒ 引擎无从判定，必须拒绝
        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 1);
        vars.put("leaderId", "err-leader");
        postOk("/api/approval-center/processes/start",
                body("definitionKey", "leaveProcess", "businessKey", "WEB-ERR-" + System.nanoTime(),
                        "userId", "err-alice", "variables", vars));

        ResponseEntity<String> badClaim = exchange(HttpMethod.POST, "/api/wf/task/claim",
                body("taskId", firstTodoId("err-leader"), "userId", "stranger",
                        "targetGroups", Collections.singletonList("no-such-group")));
        assertEquals(HttpStatus.BAD_REQUEST, badClaim.getStatusCode(),
                "非候选组成员认领必须被拒");
    }

    @Test
    @DisplayName("异常映射口径：乐观锁 409 / 存储 503 / 业务 400 写在 advice 上")
    void exceptionAdviceStatusMapping() throws Exception {
        // 乐观锁与存储故障在真实链路上难稳定复现，但状态码是前端重试策略的依据，
        // 必须钉住：409 与 503 不是装饰，改成 500 会让前端一律弹"系统错误"而不重试。
        // 下面 optimisticLockConflictIsReal409 已经把 409 在真链路上跑通了。
        assertEquals(HttpStatus.CONFLICT, statusOf("onOptimisticLock"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, statusOf("onPersistence"));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf("onEngine"));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf("onDefinition"));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf("onIllegalArgument"));

        // 处理器必须覆盖这四类异常，否则映射写了也不会被调用
        assertHandlerDeclared("onOptimisticLock", WfOptimisticLockException.class);
        assertHandlerDeclared("onPersistence", WfPersistenceException.class);
    }

    @Test
    @DisplayName("乐观锁冲突在真链路上返回 409（不是 500），且任务仍可被新办理人正常办结")
    void optimisticLockConflictIsReal409() throws Exception {
        String leaderId = "conflict-leader";
        String taskId = startAndGetLeaderTask(leaderId);

        // 在 onBeforeComplete 里对同一条任务做一次读-改-写（转办），
        // 这就是"另一个节点在我办结的同时改了这条任务"的确定性复现：
        // 钩子把库里的 REVISION 顶高一格，引擎随后拿着旧 REVISION 去 CAS 必然落空。
        // 生产里等价的东西：审计钩子给任务打标、通知钩子指派备办人等任何读-改-写。
        WfTaskHook interferingHook = new WfTaskHook() {
            @Override
            public boolean onBeforeComplete(String hookTaskId, String assignee, Map<String, Object> vars) {
                if (taskId.equals(hookTaskId)) {
                    taskService.transfer(taskId, assignee, "concurrent-writer", "并发修改");
                }
                return true;
            }
        };
        dispatcher.addTaskHook(interferingHook);
        try {
            ResponseEntity<String> response = exchange(HttpMethod.POST,
                    "/api/approval-center/tasks/complete",
                    body("taskId", taskId, "userId", leaderId, "comment", "同意"));
            assertEquals(HttpStatus.CONFLICT, response.getStatusCode(),
                    "乐观锁冲突必须返回 409，前端据此提示'他人正在处理'并刷新重试。"
                            + "返回 500 会让前端一律弹'系统错误'而不再重试。实际返回: "
                            + response.getBody());
            assertTrue(response.getBody().contains("success\":false"),
                    "409 也要带标准 Result 信封，前端才能统一解析");
        } finally {
            // 必须摘掉：Spring 上下文在测试类之间复用，钩子留着会污染后续用例
            assertEquals(1, dispatcher.removeTaskHook(interferingHook));
        }

        // 冲突没有把任务改坏：转办后的新办理人仍能正常办结
        Map<String, Object> after = asMap(postOk("/api/approval-center/tasks/complete",
                body("taskId", taskId, "userId", "concurrent-writer", "comment", "改完再批"))
                .get("data"));
        assertEquals("ACTIVE", after.get("status"), "冲突后任务应仍可继续推进");
    }

    // ==================== 辅助 ====================

    private HttpStatus statusOf(String method) throws Exception {
        Method m = WfExceptionAdvice.class.getMethod(method, exceptionType(method));
        ResponseStatus rs = m.getAnnotation(ResponseStatus.class);
        assertNotNull(rs, method + " 必须声明 @ResponseStatus，否则状态码退化成 200");
        return rs.value();
    }

    private Class<?> exceptionType(String method) {
        if ("onOptimisticLock".equals(method)) {
            return WfOptimisticLockException.class;
        }
        if ("onPersistence".equals(method)) {
            return WfPersistenceException.class;
        }
        if ("onEngine".equals(method)) {
            return com.zifang.z.wf.core.service.WfEngineException.class;
        }
        if ("onDefinition".equals(method)) {
            return WfDefinitionException.class;
        }
        return IllegalArgumentException.class;
    }

    private void assertHandlerDeclared(String method, Class<?> exceptionType) {
        boolean found = false;
        for (Method m : WfExceptionAdvice.class.getDeclaredMethods()) {
            if (!m.getName().equals(method)) {
                continue;
            }
            org.springframework.web.bind.annotation.ExceptionHandler handler =
                    m.getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class);
            assertNotNull(handler, method + " 缺 @ExceptionHandler");
            for (Class<?> value : handler.value()) {
                if (value.isAssignableFrom(exceptionType)) {
                    found = true;
                }
            }
        }
        assertTrue(found, method + " 没有处理 " + exceptionType.getSimpleName());
    }

    /** 某人的第一条待办 id。 */
    private String firstTodoId(String userId) throws Exception {
        Map<String, Object> page = getOk("/api/approval-center/tasks/todo?userId=" + userId);
        List<Map<String, Object>> records = asList(asMap(page.get("data")).get("records"));
        assertFalse(records.isEmpty(), userId + " 应有待办");
        return (String) records.get(0).get("taskId");
    }

    /**
     * 发起一张 days=1 的请假单并返回领导待办 id。
     *
     * <p>每个用例必须用<b>独有的 leaderId</b>：所有用例共享同一个 H2 库与 Spring 上下文，
     * 用同一个办理人时 {@code firstTodoId} 会捞到别的用例的单，断言就变成"碰巧通过"。
     */
    private String startAndGetLeaderTask(String leaderId) throws Exception {
        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 1);
        vars.put("leaderId", leaderId);
        postOk("/api/approval-center/processes/start",
                body("definitionKey", "leaveProcess",
                        "businessKey", "WEB-" + leaderId + "-" + System.nanoTime(),
                        "userId", leaderId + "-alice", "variables", vars));
        return firstTodoId(leaderId);
    }

    private void assertAbsent(Map<String, Object> map, String field, String why) {
        assertFalse(map.containsKey(field),
                "响应里不应出现 " + field + "：" + why + "，实际字段集=" + map.keySet());
    }

    private Map<String, Object> getOk(String url) throws Exception {
        ResponseEntity<String> response = exchange(HttpMethod.GET, url, null);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "GET " + url + " 失败: " + response.getBody());
        return json.readValue(response.getBody(), Map.class);
    }

    private Map<String, Object> postOk(String url, Object request) throws Exception {
        ResponseEntity<String> response = exchange(HttpMethod.POST, url, request);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "POST " + url + " 失败: " + response.getBody());
        return json.readValue(response.getBody(), Map.class);
    }

    // ==================== 历史与 Job 端点 ====================

    @Test
    @DisplayName("历史活动端点：条件可组合，total 是总条数而非当前页")
    void historyActivitiesOverHttp() throws Exception {
        String businessKey = "WEB-HIST-" + System.nanoTime();
        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 1);
        vars.put("leaderId", "hist-leader-" + businessKey);
        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "leaveProcess", "businessKey", businessKey,
                        "userId", "hist-owner-" + businessKey, "variables", vars)).get("data");

        // 按流程实例查：在途流程只有起始那一行 ——
        // 活动历史是"离开节点时"才写的（一次节点访问一条），
        // 领导审批还在等，自然还没行。这正是上一轮修掉"每步记三条"后的语义
        Map<String, Object> page = getOk("/api/wf/history/activities?processInstanceId="
                + processId + "&pageNum=1&pageSize=100");
        Map<String, Object> data = asMap(page.get("data"));
        List<Map<String, Object>> rows = asList(data.get("records"));
        assertFalse(rows.isEmpty(), "至少应有起始那一行");
        assertTrue(((Number) data.get("total")).longValue() >= 1,
                "total 应是总条数。实际 total=" + data.get("total"));

        // 按办理人查历史，只能查到**已办结**的步骤 ——
        // 领导还没批，那一行此刻还不存在。先批了再查。
        assertTrue(asList(asMap(getOk("/api/wf/history/activities?processDefinitionKey="
                + "leaveProcess&assignee=" + vars.get("leaderId")
                + "&pageNum=1&pageSize=100").get("data")).get("records")).isEmpty(),
                "领导还没批，历史里不该有他");

        completeOne((String) vars.get("leaderId"));

        // 条件可组合：定义 + 办理人同时生效
        Map<String, Object> filtered = getOk("/api/wf/history/activities"
                + "?processDefinitionKey=leaveProcess&assignee=" + vars.get("leaderId")
                + "&pageNum=1&pageSize=100");
        List<Map<String, Object>> byLeader = asList(asMap(filtered.get("data")).get("records"));
        assertFalse(byLeader.isEmpty(), "批完之后应能按办理人查到那一步");
        for (Map<String, Object> row : byLeader) {
            assertEquals(vars.get("leaderId"), row.get("assignee"),
                    "AND 语义：两个条件都要成立");
        }

        // 按活动类型收窄
        Map<String, Object> byType = getOk("/api/wf/history/activities"
                + "?processInstanceId=" + processId + "&activityType=userTask&pageSize=100");
        List<Map<String, Object>> userTasks = asList(asMap(byType.get("data")).get("records"));
        for (Map<String, Object> row : userTasks) {
            assertEquals("userTask", row.get("activityType"));
        }
    }

    @Test
    @DisplayName("历史实例端点：已办结的单据查得到，total 是总条数")
    void historyProcessesOverHttp() throws Exception {
        String owner = "hist-proc-owner-" + System.nanoTime();
        String businessKey = "WEB-HP-" + System.nanoTime();
        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 1);
        vars.put("leaderId", "hp-leader-" + businessKey);
        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "leaveProcess", "businessKey", businessKey,
                        "userId", owner, "variables", vars)).get("data");

        // 查已结束：还没办结时应当查不到
        Map<String, Object> before = getOk("/api/wf/history/processes?businessKey=" + businessKey);
        assertTrue(asList(asMap(before.get("data")).get("records")).isEmpty(),
                "在途流程不属于历史");

        // leaveProcess 是多级审批（直属领导 → 天数分支 → HR/CEO），
        // 只办结一级流程还没结束，所以这里必须**一路办到底**才查得到历史
        String leaderId = (String) vars.get("leaderId");
        completeOne(leaderId);
        Map<String, Object> mid = getOk("/api/wf/history/processes?businessKey=" + businessKey);
        assertTrue(asList(asMap(mid.get("data")).get("records")).isEmpty(),
                "只办结一级时流程仍在途，不该出现在历史里");

        completeOne("hr");
        Map<String, Object> after = getOk("/api/wf/history/processes?businessKey=" + businessKey);
        Map<String, Object> data = asMap(after.get("data"));
        assertEquals(1, asList(data.get("records")).size(), "办结后应出现在历史里");
        assertEquals(1L, ((Number) data.get("total")).longValue());
        assertEquals(processId, asList(data.get("records")).get(0).get("processInstanceId"));
    }

    @Test
    @DisplayName("job 端点：带定时器边界的流程会起表，执行端点能把到点的表消费掉")
    void jobsOverHttp() throws Exception {
        deployTimerProcess();

        String businessKey = "WEB-JOB-" + System.nanoTime();
        Map<String, Object> vars = new HashMap<>();
        vars.put("sla", "PT30M");
        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webTimerProcess", "businessKey", businessKey,
                        "userId", "job-owner-" + businessKey, "variables", vars)).get("data");

        Map<String, Object> listed = getOk("/api/wf/history/jobs?processInstanceId=" + processId);
        List<Map<String, Object>> jobs = asList(asMap(listed.get("data")).get("records"));
        assertEquals(1, jobs.size(), "token 停在审批节点上，应当恰好一只表。实际 " + jobs);
        Map<String, Object> job = jobs.get(0);
        assertEquals("webTimeout", job.get("elementId"));
        assertEquals("webApprove", job.get("attachedToRef"));
        assertTrue(((Number) job.get("duedate")).longValue() > System.currentTimeMillis(),
                "还没到点");

        // 未到期不该被消费
        Map<String, Object> notYet = postOk("/api/wf/history/jobs/execute", null);
        assertEquals(0, ((Number) notYet.get("data")).intValue(),
                "没到点时不该触发任何边界事件");

        // 把时钟拨到 1 小时后
        Map<String, Object> fired = postOk("/api/wf/history/jobs/execute?now="
                + (System.currentTimeMillis() + 3600_000L), null);
        assertEquals(1, ((Number) fired.get("data")).intValue(), "到点后应触发一次");

        Map<String, Object> after = getOk("/api/wf/history/jobs?processInstanceId=" + processId);
        assertTrue(asList(asMap(after.get("data")).get("records")).isEmpty(),
                "已触发的 job 应当被消费掉");
    }

    @Test
    @DisplayName("清理端点：只删已结束流程，在途流程的历史留着")
    void historyCleanupOverHttp() throws Exception {
        // 造一条已结束、一条在途
        String doneKey = "WEB-CLEAN-DONE-" + System.nanoTime();
        String doneId = startAndComplete("WEB-CLEAN-DONE", doneKey, "clean-done-leader");

        String openKey = "WEB-CLEAN-OPEN-" + System.nanoTime();
        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 1);
        vars.put("leaderId", "clean-open-leader-" + System.nanoTime());
        String openId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "leaveProcess", "businessKey", openKey,
                        "userId", "clean-open-owner", "variables", vars)).get("data");

        ResponseEntity<String> removed = exchange(HttpMethod.DELETE,
                "/api/wf/history/cleanup?before=" + (System.currentTimeMillis() + 60_000L), null);
        assertEquals(HttpStatus.OK, removed.getStatusCode());
        // 不断言"删了恰好 1 条"：清理的语义是"删掉所有 endTime 早于该时刻的已结束流程"，
        // 同一个库里还躺着别的用例留下的已结束流程，数量本就与本用例无关。
        // 真正该断言的是"我造的两条，一条删掉一条留下"。
        assertTrue(((Number) json.readValue(removed.getBody(), Map.class)
                .get("data")).intValue() >= 1,
                "本用例造的那条已结束流程应当被清掉");

        assertTrue(asList(asMap(getOk("/api/wf/history/activities?processInstanceId="
                + doneId).get("data")).get("records")).isEmpty(),
                "已结束流程的历史应被清掉");
        assertFalse(asList(asMap(getOk("/api/wf/history/activities?processInstanceId="
                + openId).get("data")).get("records")).isEmpty(),
                "在途流程的历史删掉之后轨迹会出洞，而单据还在被人办");
    }

    private String startAndComplete(String tag, String businessKey, String leaderId)
            throws Exception {
        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 1);
        vars.put("leaderId", leaderId);
        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "leaveProcess", "businessKey", businessKey,
                        "userId", tag + "-owner-" + System.nanoTime(), "variables", vars))
                .get("data");
        // 多级审批：一路办到底，否则流程没结束、算"在途"
        completeOne(leaderId);
        completeOne("hr");
        return processId;
    }

    /** 办掉某人当前的第一条待办。 */
    private void completeOne(String userId) throws Exception {
        Map<String, Object> todo = getOk("/api/approval-center/tasks/todo?userId=" + userId);
        List<Map<String, Object>> records = asList(asMap(todo.get("data")).get("records"));
        if (records.isEmpty()) {
            throw new AssertionError(userId + " 名下没有待办可办");
        }
        postOk("/api/approval-center/tasks/complete",
                body("taskId", records.get(0).get("taskId"), "userId", userId,
                        "comment", "同意"));
    }

    // ==================== 外部任务 ====================

    /**
     * 外部任务端点：走完"起流程 → 领活 → 交差 → 流程推进"的完整闭环。
     *
     * <p>这条用例的价值在于它同时压了三层：HTTP 契约、JDBC 落库（含新加的
     * TOPIC/LOCKED_BY/LOCK_AT 三列与迁移）、以及锁归属校验。
     * 只在内存实现上测的话，JDBC 侧那些列的读写对不对根本没人知道。
     */
    @Test
    @DisplayName("外部任务端点：起流程挂活、领活上锁、交差后流程推进到用户任务")
    void externalTaskOverHttp() throws Exception {
        deployExternalProcess();
        String suffix = String.valueOf(System.nanoTime());
        String businessKey = "WEB-EXT-" + suffix;

        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webExternalProcess", "businessKey", businessKey,
                        "userId", "ext-owner-" + suffix,
                        "variables", body("orderNo", "EXT-" + suffix))).get("data");

        // 1) 列表端点能查到这笔活，且 topic 过滤生效
        Map<String, Object> listed = getOk("/api/wf/external-tasks?topic=web.notify");
        List<Map<String, Object>> tasks = asList(listed.get("data"));
        assertFalse(tasks.isEmpty(), "流程停在外部任务上，应当能查到活");
        Map<String, Object> mine = null;
        for (Map<String, Object> t : tasks) {
            if (processId.equals(t.get("processInstanceId"))) {
                mine = t;
                break;
            }
        }
        assertNotNull(mine, "刚起的实例应当在外部任务列表里，实际 " + tasks);
        assertEquals("web.notify", mine.get("topic"));
        assertEquals("EXT-" + suffix, asMap(mine.get("variables")).get("orderNo"),
                "领活时带出的变量应当是起流程时传入的");

        // 2) 领活：带上锁，且能拿到租约
        String taskId = (String) mine.get("id");
        Map<String, Object> fetched = postOk("/api/wf/external-tasks/fetch",
                body("topic", "web.notify", "workerId", "web-w1-" + suffix,
                        "maxTasks", 10, "leaseMillis", 60000));
        List<Map<String, Object>> got = asList(fetched.get("data"));
        boolean found = false;
        for (Map<String, Object> t : got) {
            if (taskId.equals(t.get("id"))) {
                found = true;
                assertEquals("web-w1-" + suffix, t.get("lockedBy"), "返回的活应当已上锁");
                assertTrue(((Number) t.get("lockExpiresAt")).longValue()
                        > System.currentTimeMillis(), "租约到期时刻应在将来");
            }
        }
        assertTrue(found, "应当领到刚才那件活，实际领到 " + got);

        // 3) 同一 worker 再领一次，领不到（锁还在）
        Map<String, Object> again = postOk("/api/wf/external-tasks/fetch",
                body("topic", "web.notify", "workerId", "web-w1-" + suffix,
                        "maxTasks", 10, "leaseMillis", 60000));
        for (Map<String, Object> t : asList(again.get("data"))) {
            assertFalse(taskId.equals(t.get("id")), "锁还在自己手里，不该再领到同一件活");
        }

        // 4) 非锁持有者交差被拒 —— 不能把整条流程推给不相干的 worker
        ResponseEntity<String> stolen = exchange(HttpMethod.POST,
                "/api/wf/external-tasks/" + taskId + "/complete",
                body("workerId", "web-w2-" + suffix, "variables", body("x", 1)));
        assertEquals(HttpStatus.BAD_REQUEST, stolen.getStatusCode(),
                "非锁持有者交差应当被拒： " + stolen.getBody());

        // 5) 锁持有者交差，流程推进到用户任务
        Map<String, Object> done = postOk("/api/wf/external-tasks/" + taskId + "/complete",
                body("workerId", "web-w1-" + suffix,
                        "variables", body("notifyResult", "sent")));
        assertEquals(processId, asMap(done.get("data")).get("processInstanceId"));

        // 6) 交差后 job 消失，列表里再也查不到
        Map<String, Object> after = getOk("/api/wf/external-tasks?topic=web.notify");
        for (Map<String, Object> t : asList(after.get("data"))) {
            assertFalse(taskId.equals(t.get("id")), "交差后 job 应当被删掉，不能留成哑表");
        }

        // 7) 流程真的走到了下一节点（有待办）
        Map<String, Object> todo = getOk("/api/approval-center/tasks/todo?userId=web-ext-leader");
        assertFalse(asList(asMap(todo.get("data")).get("records")).isEmpty(),
                "外部步骤过了，流程应当轮到处在办的用户任务上");
    }

    @Test
    @DisplayName("外部任务端点：失败上报会解锁，别的 worker 能接着领")
    void externalTaskFailUnlocksOverHttp() throws Exception {
        deployExternalProcess();
        String suffix = String.valueOf(System.nanoTime());
        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webExternalProcess", "businessKey", "WEB-EXTF-" + suffix,
                        "userId", "extf-owner-" + suffix)).get("data");

        String worker = "web-wf-" + suffix;
        Map<String, Object> fetched = postOk("/api/wf/external-tasks/fetch",
                body("topic", "web.notify", "workerId", worker, "maxTasks", 10));
        String taskId = null;
        for (Map<String, Object> t : asList(fetched.get("data"))) {
            if (processId.equals(t.get("processInstanceId"))) {
                taskId = (String) t.get("id");
            }
        }
        assertNotNull(taskId, "应当领到本实例的活");

        postOk("/api/wf/external-tasks/" + taskId + "/fail",
                body("workerId", worker, "errorMessage", "下游 503"));

        // 解锁后另一个 worker 应当能领到
        Map<String, Object> retry = postOk("/api/wf/external-tasks/fetch",
                body("topic", "web.notify", "workerId", "web-wr-" + suffix, "maxTasks", 10));
        boolean reclaimed = false;
        for (Map<String, Object> t : asList(retry.get("data"))) {
            if (taskId.equals(t.get("id"))) {
                reclaimed = true;
                assertTrue(((Number) t.get("retries")).intValue()
                        < com.zifang.z.wf.core.model.WfJob.DEFAULT_RETRIES,
                        "重试次数应当被扣过 —— 没扣的话重试等于无限");
            }
        }
        assertTrue(reclaimed, "失败后活应当被解锁，别人能接着领。实际 " + retry.get("data"));
    }

    @Test
    @DisplayName("外部任务端点：参数缺失报 400，不静默当成没活")
    void externalTaskRejectsBadArguments() throws Exception {
        deployExternalProcess();
        ResponseEntity<String> noTopic = exchange(HttpMethod.POST, "/api/wf/external-tasks/fetch",
                body("workerId", "w", "maxTasks", 5));
        assertEquals(HttpStatus.BAD_REQUEST, noTopic.getStatusCode(),
                "缺 topic 应当报 400： " + noTopic.getBody());

        ResponseEntity<String> noWorker = exchange(HttpMethod.POST, "/api/wf/external-tasks/fetch",
                body("topic", "web.notify", "maxTasks", 5));
        assertEquals(HttpStatus.BAD_REQUEST, noWorker.getStatusCode(),
                "缺 workerId 应当报 400： " + noWorker.getBody());

        ResponseEntity<String> badMax = exchange(HttpMethod.POST, "/api/wf/external-tasks/fetch",
                body("topic", "web.notify", "workerId", "w", "maxTasks", 0));
        assertEquals(HttpStatus.BAD_REQUEST, badMax.getStatusCode(),
                "maxTasks=0 应当报 400 而不是返回空： " + badMax.getBody());
    }

    /**
     * 部署一个带外部任务步骤的测试定义。
     *
     * <p>同样不能靠示例流程：现有示例里没有任何 zifang:topic 节点。
     * process id 固定（重复部署会升版本），所以每次起流程都要用独立的 businessKey，
     * 否则断言里按 processInstanceId 过滤会一次比中多条。
     */
    @Test
    @DisplayName("异步端点：起流程挂异步 job，执行端点把它续跑掉")
    void asyncExecutionOverHttp() throws Exception {
        deployAsyncProcess();
        String suffix = String.valueOf(System.nanoTime());
        String businessKey = "WEB-ASYNC-" + suffix;

        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webAsyncProcess", "businessKey", businessKey,
                        "userId", "async-owner-" + suffix)).get("data");

        // 1) 起流程即挂一个异步 job，且它是"异步前置"而不是定时器
        Map<String, Object> listed = getOk("/api/wf/history/jobs?processInstanceId=" + processId);
        List<Map<String, Object>> jobs = asList(asMap(listed.get("data")).get("records"));
        assertEquals(1, jobs.size(), "到达异步节点就应挂一个 job。实际 " + jobs);
        assertEquals("ASYNC_BEFORE", jobs.get(0).get("type"),
                "job 类型要能区分异步前置与定时器，否则执行器会串");

        // 2) 定时器执行端点不该碰它
        Map<String, Object> byTimer = postOk("/api/wf/history/jobs/execute?now="
                + (System.currentTimeMillis() + 3_600_000L), null);
        assertEquals(0, ((Number) byTimer.get("data")).intValue(),
                "定时器执行器只处理 TIMER，不该消费异步 job");

        // 3) 异步执行端点把它续跑掉
        Map<String, Object> resumed = postOk("/api/wf/history/jobs/execute-async?now="
                + (System.currentTimeMillis() + 3_600_000L), null);
        assertEquals(1, ((Number) resumed.get("data")).intValue(), "应当续跑一个异步 job");

        // 4) job 消失，且待办出现（续跑才建出待办）
        Map<String, Object> after = getOk("/api/wf/history/jobs?processInstanceId=" + processId);
        assertTrue(asList(asMap(after.get("data")).get("records")).isEmpty(),
                "续跑过的 job 应当被删掉");
        Map<String, Object> todo = getOk("/api/approval-center/tasks/todo?userId=web-async-leader");
        assertFalse(asList(asMap(todo.get("data")).get("records")).isEmpty(),
                "续跑后待办才该出现");
    }

    @Test
    @DisplayName("异步执行端点：再跑一次不重复推进（不会死循环）")
    void asyncExecutionIsIdempotentOverHttp() throws Exception {
        deployAsyncProcess();
        String suffix = String.valueOf(System.nanoTime());
        postOk("/api/approval-center/processes/start",
                body("definitionKey", "webAsyncProcess", "businessKey", "WEB-ASYNC2-" + suffix,
                        "userId", "async2-owner-" + suffix));

        String future = String.valueOf(System.currentTimeMillis() + 3_600_000L);
        assertEquals(1, ((Number) postOk(
                "/api/wf/history/jobs/execute-async?now=" + future, null).get("data")).intValue());

        // 记下第一次续跑后的待办数。断言的是"数量不变"而不是"不存在"——
        // 第一次续跑本来就会建出一个待办，拿存在性去断言必然失败
        int tasksAfterFirst = asList(asMap(getOk(
                "/api/approval-center/tasks/todo?userId=web-async-leader")
                .get("data")).get("records")).size();

        // 第二次必须为 0：续跑若又挂了新 job，这里会变成 1 并无限循环
        assertEquals(0, ((Number) postOk(
                "/api/wf/history/jobs/execute-async?now=" + future, null).get("data")).intValue(),
                "续跑不能再次排队，否则执行端点会被打爆且流程原地不动");

        assertEquals(tasksAfterFirst, asList(asMap(getOk(
                "/api/approval-center/tasks/todo?userId=web-async-leader")
                .get("data")).get("records")).size(),
                "重复续跑不该让待办变多 —— 变多说明节点被执行了第二遍");
    }

    /**
     * 部署一个带异步前置的测试定义。
     *
     * <p>process id 固定（重复部署会升版本），所以每次起流程都要用独立的 businessKey。
     */
    @Test
    @DisplayName("图元端点：回读节点坐标与连线折点，并带一致性核对")
    void diagramOverHttp() throws Exception {
        // 部署端点回显的是定义摘要对象（key/version/suspended），不是裸字符串，
        // 所以 key 与版本都得从这里取，而不是自己按 process id 拼 —— 拼的那份
        // 未必是服务端认的那份，猜对了也只是碰巧
        Map<String, Object> deployed = asMap(postOk("/api/wf/definitions/deploy",
                body("xml", DIAGRAM_BPMN)).get("data"));
        String key = (String) deployed.get("key");
        assertNotNull(key, "部署端点应回显 key");
        int version = ((Number) deployed.get("version")).intValue();

        Map<String, Object> data = asMap(getOk(
                "/api/wf/definitions/diagram?key=" + key + "&version=" + version).get("data"));
        assertEquals(Boolean.FALSE, data.get("empty"), "带 DI 段的模型不该是 empty");
        assertFalse(asList(data.get("shapes")).isEmpty(), "应当有节点图元");

        boolean sawApprove = false;
        for (Map<String, Object> shape : asList(data.get("shapes"))) {
            if ("approve".equals(shape.get("id"))) {
                sawApprove = true;
                assertEquals("审批", shape.get("name"), "名称要靠流程定义补齐");
                assertEquals("userTask", shape.get("type"), "类型决定前端画什么形状");
                assertTrue(((Number) shape.get("x")).doubleValue() > 0, "坐标应当读出来");
            }
        }
        assertTrue(sawApprove, "审批节点应当有图元");

        assertFalse(asList(data.get("edges")).isEmpty(), "应当有连线图元");
        assertEquals(Boolean.TRUE, data.get("consistent"),
                "自部署的模型图与逻辑必然一致。实际 缺图=" + data.get("missingNodeIds")
                        + " 多框=" + data.get("orphanShapeIds"));
    }

    @Test
    @DisplayName("图元端点：图与逻辑对不上时如实报出来，不返回一个一切正常的图")
    void diagramInconsistencyIsSurfacedOverHttp() throws Exception {
        // 必须在 web 这一层也测一次"对不上"。只测"一致"的话，
        // checkConsistency 整个不跑也是绿的：三个列表都是空的，isConsistent 照样返回 true。
        // 那样这条端点就成了"看起来接好了"，实际把悬挂项全吞了
        String xml = DIAGRAM_BPMN
                .replace("id=\"webDiagram\"", "id=\"webDiagramSkewed\"")
                .replace("    </bpmndi:BPMNPlane>",
                        "      <bpmndi:BPMNShape id=\"WSX\" bpmnElement=\"ghostShape\">\n"
                        + "        <dc:Bounds x=\"10\" y=\"10\" width=\"10\" height=\"10\"/>\n"
                        + "      </bpmndi:BPMNShape>\n"
                        + "      <bpmndi:BPMNEdge id=\"WEX\" bpmnElement=\"ghostFlow\">\n"
                        + "        <di:waypoint x=\"1\" y=\"1\"/>\n"
                        + "      </bpmndi:BPMNEdge>\n"
                        + "    </bpmndi:BPMNPlane>");
        // 字符串手术必须真的落到 XML 上。改缩进这类事很容易让 replace 静默失配，
        // 那样部署的是一份完全一致的图，下面的断言仍然"通过"，但什么都没测到
        assertTrue(xml.contains("ghostShape") && xml.contains("ghostFlow"),
                "幽灵图元必须真的插进 DI 段，否则这条用例测的是上一条");
        assertTrue(xml.contains("webDiagramSkewed"), "process id 必须换掉，否则会顶掉上面那条的版本");
        Map<String, Object> deployed = asMap(postOk("/api/wf/definitions/deploy",
                body("xml", xml)).get("data"));
        String key = (String) deployed.get("key");
        int version = ((Number) deployed.get("version")).intValue();

        Map<String, Object> data = asMap(getOk(
                "/api/wf/definitions/diagram?key=" + key + "&version=" + version).get("data"));
        assertEquals(Boolean.FALSE, data.get("consistent"),
                "图上多了一个框和一根线，接口不能回一个 consistent=true");
        assertTrue(((List<?>) data.get("orphanShapeIds")).contains("ghostShape"),
                "多出来的框要报出来。实际 " + data.get("orphanShapeIds"));
        assertTrue(((List<?>) data.get("orphanEdgeIds")).contains("ghostFlow"),
                "多出来的线要报出来。实际 " + data.get("orphanEdgeIds"));
    }

    @Test
    @DisplayName("订阅端点：在等消息的流程查得到等待状态，且总览里也带得出来")
    void subscriptionsOverHttp() throws Exception {
        postOk("/api/wf/definitions/deploy", body("key", "webSub", "xml", RACE_BPMN));
        String pid = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webSub", "businessKey", "SUB-1",
                        "userId", "web-sub-alice")).get("data");

        // 排障现场最需要的一条信息：这条单子在等谁。
        // 它在待办列表里查不到（没有待办）、轨迹里也没动静、而且没有任何报错
        Map<String, Object> page = asMap(getOk(
                "/api/wf/subscriptions?processInstanceId=" + pid).get("data"));
        List<Map<String, Object>> records = asList(page.get("records"));
        assertEquals(2, records.size(), "两条分支各一条订阅。实际 " + records.size());
        assertEquals(2, ((Number) page.get("total")).intValue(), "总数要与列表一致");

        boolean sawMessage = false;
        for (Map<String, Object> record : records) {
            // 等消息与等信号归并成不同的「等什么」，不要都显示成同一种
            String expectedKind = "bossApprove".equals(record.get("eventName"))
                    ? "message" : "signal";
            assertEquals(expectedKind, record.get("waitingFor"),
                    "等消息与等信号必须分得开。实际 " + record);
            assertEquals("wreg", record.get("gatewayId"),
                    "竞速订阅要带出网关 id，运维才看得出这几条是同一次竞速");
            if ("bossApprove".equals(record.get("eventName"))) {
                sawMessage = true;
            }
        }
        assertTrue(sawMessage, "应当能看到在等主管批的那条");

        // 按类型过滤。必须限定实例：这些用例共享同一个 Spring 容器与持久化，
        // 不限定的话同类型订阅会来自别的用例起的实例，数字随执行顺序变 ——
        // 那种"有时绿有时红"的测试比没有测试更糟
        assertEquals(1, ((Number) asMap(getOk(
                "/api/wf/subscriptions/count?processInstanceId=" + pid
                        + "&type=EVENT_SIGNAL").get("data")).get("count")).intValue());
        assertEquals(2, ((Number) asMap(getOk(
                "/api/wf/subscriptions/count?processInstanceId=" + pid).get("data"))
                .get("count")).intValue());
        // 拼错类型要报错并列出合法值，不能当没传 ——
        // 静默忽略的话调用方会以为"筛过了、没有"，而他正要靠这个结论判断没有等待中的订阅
        ResponseEntity<String> bad = exchange(HttpMethod.GET,
                "/api/wf/subscriptions/count?type=NOT_A_TYPE", null);
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode(),
                "拼错的类型必须报错而不是被忽略: " + bad.getBody());
        assertTrue(bad.getBody().contains("EVENT_MESSAGE"),
                "报错要列出合法值。实际 " + bad.getBody());
    }

    @Test
    @DisplayName("总览端点带出当前等待 —— 不用再开一个页面才能看出卡在哪")
    void overviewCarriesSubscriptionsOverHttp() throws Exception {
        postOk("/api/wf/definitions/deploy", body("key", "webSub2", "xml", RACE_BPMN));
        String pid = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webSub2", "businessKey", "SUB-2",
                        "userId", "web-sub-bob")).get("data");

        Map<String, Object> overview = asMap(getOk(
                "/api/wf/process/overview?processInstanceId=" + pid).get("data"));
        // 拆成独立用例是因为「总览带不带等待」与「订阅端点准不准」是两件事：
        // 混在一条里，订阅端点自己出问题时这条也会红，于是分不清是谁坏了
        List<Map<String, Object>> waiting = asList(overview.get("subscriptions"));
        assertEquals(2, waiting.size(),
                "总览必须带出当前等待，否则「这条单子怎么不动了」还是没有答案。实际 " + waiting);
        assertNotNull(overview.get("openTasks"));
        assertTrue(asList(overview.get("openTasks")).isEmpty(),
                "等事件期间没有待办 —— 这正是为什么必须靠 subscriptions 才能看出它在等什么");
    }

    @Test
    @DisplayName("事件投递端点：消息走到事件网关的对应分支，其余分支被作废")
    void eventGatewayOverHttp() throws Exception {
        postOk("/api/wf/definitions/deploy", body("key", "webRace", "xml", RACE_BPMN));
        // start 端点的 data 是流程实例 id（裸字符串），不是视图对象
        String pid = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webRace", "businessKey", "RACE-1",
                        "userId", "web-race-alice")).get("data");
        assertNotNull(pid);

        // 等事件期间一条待办都不该有：中间捕获事件等的是消息，不是某个人
        assertTrue(asList(asMap(getOk("/api/approval-center/tasks/todo?userId=web-race-ops")
                .get("data")).get("records")).isEmpty(),
                "事件网关分支等的是消息，不该产生任何待办");

        postOk("/api/wf/process/message", body("name", "bossApprove",
                "processInstanceId", pid, "userId", "web-race-boss", "comment", "主管批了"));

        // 走完消息分支后只剩一条待办；落选分支的 token 若没被作废，实例永远停在运行态
        List<Map<String, Object>> records = asList(asMap(getOk(
                "/api/approval-center/tasks/todo?userId=web-race-ops").get("data"))
                .get("records"));
        assertEquals(1, records.size(),
                "竞速之后只应剩命中的那一条分支产生待办，实际 " + records.size());

        Map<String, Object> done = postOk("/api/approval-center/tasks/complete",
                body("taskId", records.get(0).get("taskId"), "userId", "web-race-ops",
                        "comment", "办结"));
        assertEquals("COMPLETED", asMap(done.get("data")).get("status"),
                "落选分支没被作废的话实例永远结束不了 —— 这条断言是竞速是否生效的最终判据");
    }

    /** 带事件网关的测试定义，事件由 REST 端点投递。 */
    private static final String RACE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"webRaceProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"wrs\"/>\n"
            + "    <eventBasedGateway id=\"wreg\"/>\n"
            + "    <intermediateCatchEvent id=\"wrWaitMsg\" name=\"等主管批\">\n"
            + "      <messageEventDefinition messageRef=\"bossApprove\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <intermediateCatchEvent id=\"wrWaitSignal\" name=\"等回执\">\n"
            + "      <signalEventDefinition signalRef=\"erpDone\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <userTask id=\"wrApprove\" name=\"批了\" zifang:assignee=\"web-race-ops\"/>\n"
            + "    <userTask id=\"wrErp\" name=\"回执到了\" zifang:assignee=\"web-race-erp\"/>\n"
            + "    <endEvent id=\"wre1\"/>\n"
            + "    <endEvent id=\"wre2\"/>\n"
            + "    <sequenceFlow id=\"wrf1\" sourceRef=\"wrs\" targetRef=\"wreg\"/>\n"
            + "    <sequenceFlow id=\"wrf2\" sourceRef=\"wreg\" targetRef=\"wrWaitMsg\"/>\n"
            + "    <sequenceFlow id=\"wrf3\" sourceRef=\"wreg\" targetRef=\"wrWaitSignal\"/>\n"
            + "    <sequenceFlow id=\"wrf4\" sourceRef=\"wrWaitMsg\" targetRef=\"wrApprove\"/>\n"
            + "    <sequenceFlow id=\"wrf5\" sourceRef=\"wrWaitSignal\" targetRef=\"wrErp\"/>\n"
            + "    <sequenceFlow id=\"wrf6\" sourceRef=\"wrApprove\" targetRef=\"wre1\"/>\n"
            + "    <sequenceFlow id=\"wrf7\" sourceRef=\"wrErp\" targetRef=\"wre2\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    @Test
    @DisplayName("图元端点：没有 DI 段时返回 empty，而不是报错")
    void diagramWithoutDiSectionOverHttp() throws Exception {
        // key 必须是写进 XML 的那一份。之前这里在拼 XML 时调一次 System.nanoTime()、
        // 查接口时再调一次，两次得到的值不同，查询必然打在不存在的定义上。
        // 变量的作用就是把"部署用的 id"和"查询用的 id"绑成同一个事实。
        String key = "noDi" + System.nanoTime();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"" + key + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"ns\"/>\n"
                + "    <endEvent id=\"ne\"/>\n"
                + "    <sequenceFlow id=\"nf\" sourceRef=\"ns\" targetRef=\"ne\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        postOk("/api/wf/definitions/deploy", body("xml", xml));

        Map<String, Object> data = asMap(getOk(
                "/api/wf/definitions/diagram?key=" + key + "&version=1").get("data"));
        assertEquals(Boolean.TRUE, data.get("empty"), "没有 DI 段时应当是 empty 而不是失败");
    }

    /** 带 BPMN DI 的定义，坐标与折点都要能被读出来。 */
    private static final String DIAGRAM_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\""
            + " xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\""
            + " xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"webDiagram\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"ds\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"web-diagram-leader\"/>\n"
            + "    <endEvent id=\"de\"/>\n"
            + "    <sequenceFlow id=\"df1\" sourceRef=\"ds\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"df2\" sourceRef=\"approve\" targetRef=\"de\"/>\n"
            + "  </process>\n"
            + "  <bpmndi:BPMNDiagram id=\"WD1\">\n"
            + "    <bpmndi:BPMNPlane id=\"WP1\" bpmnElement=\"webDiagram\">\n"
            + "      <bpmndi:BPMNShape id=\"WS1\" bpmnElement=\"ds\">\n"
            + "        <dc:Bounds x=\"100\" y=\"150\" width=\"36\" height=\"36\"/>\n"
            + "      </bpmndi:BPMNShape>\n"
            + "      <bpmndi:BPMNShape id=\"WS2\" bpmnElement=\"approve\">\n"
            + "        <dc:Bounds x=\"220\" y=\"128\" width=\"100\" height=\"80\"/>\n"
            + "      </bpmndi:BPMNShape>\n"
            + "      <bpmndi:BPMNShape id=\"WS3\" bpmnElement=\"de\">\n"
            + "        <dc:Bounds x=\"420\" y=\"150\" width=\"36\" height=\"36\"/>\n"
            + "      </bpmndi:BPMNShape>\n"
            + "      <bpmndi:BPMNEdge id=\"WE1\" bpmnElement=\"df1\">\n"
            + "        <di:waypoint x=\"136\" y=\"168\"/>\n"
            + "        <di:waypoint x=\"220\" y=\"168\"/>\n"
            + "      </bpmndi:BPMNEdge>\n"
            + "      <bpmndi:BPMNEdge id=\"WE2\" bpmnElement=\"df2\">\n"
            + "        <di:waypoint x=\"320\" y=\"168\"/>\n"
            + "        <di:waypoint x=\"380\" y=\"168\"/>\n"
            + "        <di:waypoint x=\"420\" y=\"168\"/>\n"
            + "      </bpmndi:BPMNEdge>\n"
            + "    </bpmndi:BPMNPlane>\n"
            + "  </bpmndi:BPMNDiagram>\n"
            + "</definitions>\n";

    private void deployAsyncProcess() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"webAsyncProcess\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"was\"/>\n"
                + "    <userTask id=\"webAsyncApprove\" name=\"异步之后审批\""
                + " zifang:assignee=\"web-async-leader\" zifang:asyncBefore=\"true\"/>\n"
                + "    <endEvent id=\"wae1\"/>\n"
                + "    <sequenceFlow id=\"waf1\" sourceRef=\"was\" targetRef=\"webAsyncApprove\"/>\n"
                + "    <sequenceFlow id=\"waf2\" sourceRef=\"webAsyncApprove\" targetRef=\"wae1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        repositoryService.deploy(new com.zifang.z.wf.core.definition.WfXmlParser().parse(xml));
    }

    private void deployExternalProcess() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"webExternalProcess\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"wes\"/>\n"
                + "    <serviceTask id=\"webNotify\" name=\"通知下游\""
                + " zifang:topic=\"web.notify\"/>\n"
                + "    <userTask id=\"webExtApprove\" name=\"外部之后审批\""
                + " zifang:assignee=\"web-ext-leader\"/>\n"
                + "    <endEvent id=\"wee1\"/>\n"
                + "    <sequenceFlow id=\"wef1\" sourceRef=\"wes\" targetRef=\"webNotify\"/>\n"
                + "    <sequenceFlow id=\"wef2\" sourceRef=\"webNotify\""
                + " targetRef=\"webExtApprove\"/>\n"
                + "    <sequenceFlow id=\"wef3\" sourceRef=\"webExtApprove\" targetRef=\"wee1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        repositoryService.deploy(new com.zifang.z.wf.core.definition.WfXmlParser().parse(xml));
    }

    /**
     * 部署一个带定时器边界的测试定义。
     *
     * <p>不能靠示例流程：starter 里那两个示例都没有 boundaryEvent，
     * 而 job 端点要有 job 可查，就得有东西能起出 job 来。
     * 测试自己部署自己的前置条件，比改动示例流程更局部。
     */
    /**
     * 部署一个"卡在等消息上"的流程，专门给实例迁移端点当前置。
     *
     * <p>用事件网关而不是现成的示例流程：要测的正是<b>一条待办都没有</b>的单子。
     * jump 的入口是任务，所以这类单子压根跳不动 —— 它就是 move 存在的理由。
     * 办理人走 {@code ${moveLeader}} 变量：所有用例共享同一个 H2 库，
     * 办理人写死会把别的用例的待办一起捞进来，断言就变成碰巧通过。
     */
    private void deployMoveProcess() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"webMoveProcess\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"mvs\"/>\n"
                + "    <eventBasedGateway id=\"mvg\"/>\n"
                + "    <intermediateCatchEvent id=\"mvWaitMsg\" name=\"等消息\">\n"
                + "      <messageEventDefinition messageRef=\"mvMsg\"/>\n"
                + "    </intermediateCatchEvent>\n"
                + "    <intermediateCatchEvent id=\"mvWaitSignal\" name=\"等信号\">\n"
                + "      <signalEventDefinition signalRef=\"mvSig\"/>\n"
                + "    </intermediateCatchEvent>\n"
                + "    <userTask id=\"mvManual\" name=\"转人工\""
                + " zifang:assignee=\"${moveLeader}\"/>\n"
                + "    <endEvent id=\"mve\"/>\n"
                + "    <sequenceFlow id=\"mvf1\" sourceRef=\"mvs\" targetRef=\"mvg\"/>\n"
                + "    <sequenceFlow id=\"mvf2\" sourceRef=\"mvg\" targetRef=\"mvWaitMsg\"/>\n"
                + "    <sequenceFlow id=\"mvf3\" sourceRef=\"mvg\" targetRef=\"mvWaitSignal\"/>\n"
                + "    <sequenceFlow id=\"mvf4\" sourceRef=\"mvWaitMsg\" targetRef=\"mvManual\"/>\n"
                + "    <sequenceFlow id=\"mvf5\" sourceRef=\"mvWaitSignal\" targetRef=\"mvManual\"/>\n"
                + "    <sequenceFlow id=\"mvf6\" sourceRef=\"mvManual\" targetRef=\"mve\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        repositoryService.deploy(new com.zifang.z.wf.core.definition.WfXmlParser().parse(xml));
    }

    private void deployTimerProcess() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"webTimerProcess\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"ws\"/>\n"
                + "    <userTask id=\"webApprove\" name=\"超时测试审批\""
                + " zifang:assignee=\"web-timer-leader\"/>\n"
                + "    <boundaryEvent id=\"webTimeout\" name=\"超时\""
                + " attachedToRef=\"webApprove\">\n"
                + "      <timerEventDefinition>\n"
                + "        <timeDuration>${sla}</timeDuration>\n"
                + "      </timerEventDefinition>\n"
                + "    </boundaryEvent>\n"
                + "    <userTask id=\"webRemind\" name=\"催办\""
                + " zifang:assignee=\"web-timer-ceo\"/>\n"
                + "    <endEvent id=\"we1\"/>\n"
                + "    <endEvent id=\"we2\"/>\n"
                + "    <sequenceFlow id=\"wf1\" sourceRef=\"ws\" targetRef=\"webApprove\"/>\n"
                + "    <sequenceFlow id=\"wf2\" sourceRef=\"webApprove\" targetRef=\"we1\"/>\n"
                + "    <sequenceFlow id=\"wf3\" sourceRef=\"webTimeout\" targetRef=\"webRemind\"/>\n"
                + "    <sequenceFlow id=\"wf4\" sourceRef=\"webRemind\" targetRef=\"we2\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        repositoryService.deploy(new com.zifang.z.wf.core.definition.WfXmlParser().parse(xml));
    }

    private ResponseEntity<String> exchange(HttpMethod method, String url, Object request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url, method, new HttpEntity<Object>(request, headers), String.class);
    }

    /**
     * 读一个只返回 {@code {"count": N}} 的端点。
     *
     * <p>抽出来是因为 {@code data} 还得先过一道 {@code asMap} 才拿得到 {@code count}，
     * 内联写就会出现 {@code ((Number) getOk(...).get("data")).get("count")} 这种
     * 转型套错层级的写法 —— 那个 {@code .get} 落在 Number 上，编译期报错。
     */
    private int count(String url) throws Exception {
        return ((Number) asMap(getOk(url).get("data")).get("count")).intValue();
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertNotNull(value, "响应 data 不应为 null");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        if (value == null) {
            return new ArrayList<Map<String, Object>>();
        }
        return (List<Map<String, Object>>) value;
    }
}
