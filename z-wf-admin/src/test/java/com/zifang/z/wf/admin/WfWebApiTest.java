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

    private ResponseEntity<String> exchange(HttpMethod method, String url, Object request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url, method, new HttpEntity<Object>(request, headers), String.class);
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
