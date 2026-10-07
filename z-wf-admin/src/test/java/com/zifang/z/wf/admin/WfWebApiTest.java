package com.zifang.z.wf.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // ==================== 执行（token）级变量 ====================

    @Test
    @DisplayName("局部变量端点：并行分支各改各的，读时不做作用域回退")
    void executionVariableEndpoints() throws Exception {
        // suffix 必须是部署时用的那个：办理人带的是它，两边不是同一个
        // 就会查不到待办，而症状是"待办没建出来"，很难联想到是 tag 对不上
        String suffix = deployParallelProcess();
        String alice = "lp-a-" + suffix;
        String bob = "lp-b-" + suffix;
        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "lpProcess", "businessKey", "WEB-LV-" + suffix,
                        "userId", "alice")).get("data");

        // 端点收 taskId 而不是 executionId：执行树是引擎内部结构，
        // 仓里有测试钉着「任务响应里不得出现 executionId」
        String taskA = openTaskOf(alice, processId).get("taskId").toString();
        String taskB = openTaskOf(bob, processId).get("taskId").toString();
        assertNotEquals(taskA, taskB, "两条分支必须是两个任务，否则本用例测不到分支作用域");

        // 同一个变量名，两个不同的值。写流程级变量的话后写的会覆盖先写的，
        // 两条分支必然走同一边 —— 那正是这里要证伪的
        postOk("/api/wf/process/branch-variables",
                body("taskId", taskA, "userId", "ops", "values", body("amount", 20000)));
        postOk("/api/wf/process/branch-variables",
                body("taskId", taskB, "userId", "ops", "values", body("amount", 500)));

        // 读：只读这一层，不回退到流程级
        Map<String, Object> readA = getOk("/api/wf/process/branch-variables?taskId=" + taskA);
        assertEquals(20000, asMap(readA.get("data")).get("amount"));
        assertNull(asMap(getOk("/api/wf/process/variables?processInstanceId="
                + processId).get("data")).get("amount"),
                "分支变量不得泄漏到流程级 —— 泄漏了别的分支也会跟着变");

        postOk("/api/approval-center/tasks/complete",
                body("taskId", openTaskOf(alice, processId).get("taskId"),
                        "userId", alice, "comment", "办完"));
        postOk("/api/approval-center/tasks/complete",
                body("taskId", openTaskOf(bob, processId).get("taskId"),
                        "userId", bob, "comment", "办完"));

        assertNotNull(openTaskOf("lp-aBig-" + suffix, processId), "甲支线是大额");
        assertNotNull(openTaskOf("lp-bSmall-" + suffix, processId), "乙支线是小额");

        // 删除
        postOk("/api/wf/process/branch-variables",
                body("taskId", taskA, "userId", "ops", "remove", Boolean.TRUE,
                        "names", java.util.Collections.singletonList("amount")));
        assertNull(asMap(getOk("/api/wf/process/branch-variables?taskId=" + taskA)
                .get("data")).get("amount"), "删除后要真的读不到");
    }

    // ==================== 变量实例查询 ====================

    @Test
    @DisplayName("变量实例端点：三级作用域都列得出，默认值与范围过滤都真的生效")
    void variableInstanceEndpoints() throws Exception {
        String suffix = deployParallelProcess();
        String alice = "lp-a-" + suffix;
        String bob = "lp-b-" + suffix;
        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "lpProcess", "businessKey", "WEB-VI-" + suffix,
                        "userId", "alice")).get("data");
        String taskA = openTaskOf(alice, processId).get("taskId").toString();
        String taskB = openTaskOf(bob, processId).get("taskId").toString();

        // 三级作用域各造一个同名变量 —— 同名是最容易串层、也最难看出串错的形态
        postOk("/api/wf/process/variables",
                body("processInstanceId", processId, "userId", "ops", "values", body("amount", 20000)));
        postOk("/api/wf/process/branch-variables",
                body("taskId", taskA, "userId", "ops", "values", body("amount", 500)));
        // 任务级只能靠办结时带的变量产生：任务一办结这张待办就消失了，
        // 而变量还留在那行任务上 —— 正好把「默认只看未办结」这条默认值测到
        postOk("/api/approval-center/tasks/complete",
                body("taskId", taskA, "userId", alice, "comment", "办完",
                        "variables", body("amount", "T-1")));

        Map<String, Object> data = asMap(getOk(
                "/api/wf/variable-instances?processInstanceId=" + processId).get("data"));
        // 默认 openTasksOnly=true：已办结那张任务上的 amount 不该混进「当前变量」
        assertEquals(2, ((Number) data.get("total")).intValue(),
                "默认只有流程级与分支级两条。实际: " + data.get("records"));
        Set<String> scopes = new HashSet<>();
        for (Object record : asList(data.get("records"))) {
            scopes.add(asMap(record).get("scope").toString());
            assertEquals("amount", asMap(record).get("name").toString());
        }
        assertTrue(scopes.contains("process") && scopes.contains("execution"),
                "两个作用域都要在。实际: " + scopes);

        // count 端点与 list 必须是同一个数：对不上时调用方只会以为自己算错了
        assertEquals(2, ((Number) asMap(getOk("/api/wf/variable-instances/count?processInstanceId="
                + processId).get("data")).get("count")).intValue());

        // 点名已办结的那张任务 → 看得见，且带 onClosedTask。
        // 默认值那一侧不列、点名这一侧列，两边必须同时成立才说明规则真的被实现过
        List<Map<String, Object>> closedRecords = asList(asMap(getOk(
                "/api/wf/variable-instances?taskId=" + taskA).get("data")).get("records"));
        assertEquals(1, closedRecords.size(),
                "点名一张已办结的任务必须查得到 —— 返回空列表分不清是没变量还是被滤掉了");
        Map<String, Object> closedView = closedRecords.get(0);
        assertEquals("task", closedView.get("scope").toString());
        assertEquals("T-1", closedView.get("value").toString());
        assertEquals(Boolean.TRUE, closedView.get("onClosedTask"));
        assertNotNull(closedView.get("taskEndTime"), "办结时间让调用方能判断这条是残留还是还在用");
        assertNotNull(closedView.get("activityName"), "要能看出这个变量属于当时图上的哪个节点");

        // 范围过滤：乙支线上什么都没写，点名它必须是空而不是报错
        assertEquals(0, ((Number) asMap(getOk("/api/wf/variable-instances/count?taskId="
                + taskB).get("data")).get("count")).intValue());
        // 作用域过滤：process 那条如果没被剔掉，total 会是 2 而不是 1
        assertEquals(1, ((Number) asMap(getOk("/api/wf/variable-instances?processInstanceId="
                + processId + "&scope=process").get("data")).get("total")).intValue());

        // 按 executionId 查：这是 REST 层唯一露出执行树的地方，
        // 它要能真的定位到那条 token 上的一层，而不是被 processInstanceId 的条件盖过去。
        // 甲支线分支级 amount=500 → 走的是小额，token 停在小额待办上
        String aliceExecution = executionIdAt(processId, "aSmall");
        assertNotNull(aliceExecution, "甲支线办结后应当停在小额待办上");
        Map<String, Object> byExecution = asMap(getOk("/api/wf/variable-instances?executionId="
                + aliceExecution).get("data"));
        assertEquals(1, ((Number) byExecution.get("total")).intValue(),
                "这条 token 上只有分支级那一个 amount。实际: " + byExecution.get("records"));
        assertEquals("execution:" + aliceExecution + "/amount",
                asList(byExecution.get("records")).get(0).get("id").toString(),
                "id 里的归属段必须是真的 token id —— 填成 null 的 id 看着仍然像模像样，"
                        + "但调用方拿它去反查会一无所获");
    }

    @Test
    @DisplayName("变量实例端点：一个范围都不给 / 作用域名拼错，都要报错并说清该给什么")
    void variableInstanceEndpointsRejectBadArguments() throws Exception {
        ResponseEntity<String> noScope = exchange(HttpMethod.GET,
                "/api/wf/variable-instances", null);
        assertEquals(HttpStatus.BAD_REQUEST, noScope.getStatusCode(),
                "本仓做不到「全系统所有变量实例」，只返回一部分比报错坏得多: " + noScope.getBody());

        ResponseEntity<String> badScope = exchange(HttpMethod.GET,
                "/api/wf/variable-instances?processInstanceId=x&scope=porcess", null);
        assertEquals(HttpStatus.BAD_REQUEST, badScope.getStatusCode(),
                "拼错的作用域名要报错: " + badScope.getBody());
        assertTrue(badScope.getBody().contains("process")
                        && badScope.getBody().contains("execution")
                        && badScope.getBody().contains("task"),
                "报错要列出合法值，否则调用方只能猜。实际: " + badScope.getBody());

        // 不存在的目标是空结果而不是 500：排障查的常常正是还没发生的流程
        assertEquals(0, ((Number) asMap(getOk(
                "/api/wf/variable-instances/count?processInstanceId=no-such-pid")
                .get("data")).get("count")).intValue());
    }

    // ==================== 保存筛选器 ====================

    @Test
    @DisplayName("筛选器端点：建 → 列 → 跑 → 改 → 删，results 按筛选器自己的类型返回")
    void filterEndpointsRoundTrip() throws Exception {
        // **所有筛选器名字都带本轮唯一前缀**：这个测试类共享同一个 H2 库，
        // 用例之间、以及同一用例多次运行之间都会互相看见对方的筛选器。
        // 不带前缀时「按名字模糊筛该有 2 条」会因为别人的数据而变成 3 ——
        // 那类失败与被测代码无关，却会把真失败淹掉
        String tag = String.valueOf(System.nanoTime());
        String firstName = "flt" + tag + "-在途的单";
        String secondName = "flt" + tag + "-另一个";
        String user = "flt-alice-" + tag;
        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "leaveProcess", "businessKey", "WEB-FLT-" + tag,
                        "userId", user)).get("data");
        assertNotNull(processId);

        Map<String, Object> created = asMap(postOk("/api/wf/filters",
                body("name", firstName, "resourceType", "processInstance", "owner", "ops",
                        "properties", body("unfinishedOnly", "true", "startUserId", user)))
                .get("data"));
        String filterId = (String) created.get("id");
        assertNotNull(filterId, "新建的筛选器必须给回 id —— 拿不到它就没法改、没法跑、没法删");
        assertEquals("processInstance", created.get("resourceType"),
                "resourceType 统一用短名 —— 它在 /results 里也是短名，"
                        + "同一字段两套拼法的话，拼错那一种不会报错，只是比不上");

        // 第二张：**每个过滤条件都要有一个本该被它剔掉的对象**，
        // 否则「owner=ops 只返回 1 条」这种断言在过滤失效时照样成立
        postOk("/api/wf/filters", body("name", secondName,
                "resourceType", "processInstance", "owner", "someone-else",
                "properties", body("unfinishedOnly", "true")));

        assertEquals(2, countOf("/api/wf/filters/count?nameLike=flt" + tag), "按名字模糊筛");
        assertEquals(1, countOf("/api/wf/filters/count?nameLike=flt" + tag + "&owner=ops"),
                "按创建人筛");
        assertEquals(2, countOf("/api/wf/filters/count?nameLike=flt" + tag
                + "&resourceType=processInstance"), "按类型筛");
        assertEquals(0, countOf("/api/wf/filters/count?name=" + firstName + "2"),
                "精确名字不该命中「" + firstName + "」以外的那张");

        // 跑起来：类型由筛选器自己决定，调用方不再传一遍 ——
        // 传了就会出现「筛选器是 task 的、却按 incident 去解释结果」这种错配
        Map<String, Object> results = asMap(getOk(
                "/api/wf/filters/" + filterId + "/results").get("data"));
        assertEquals("processInstance", results.get("resourceType"));
        assertEquals(1, ((Number) results.get("total")).intValue(),
                "只有本轮起的那一张该命中。实际: " + results.get("records"));

        // 改：名字能改
        String renamed = firstName + "（改）";
        Map<String, Object> updated = readOk(exchange(HttpMethod.PUT,
                "/api/wf/filters/" + filterId,
                body("name", renamed, "owner", "ops",
                        "properties", body("unfinishedOnly", "true", "startUserId", user))));
        assertEquals(renamed, updated.get("name"));
        // 模糊串只能是「（改）」这一段：新名字是 flt<tag>-在途的单（改），
        // tag 与（改）之间还隔着别的字，拼成 tag+（改）那个子串并不存在 ——
        // 一条不存在的子串永远匹配 0 条，而 0 条看起来也很像"改了没生效"
        assertEquals(1, countOf("/api/wf/filters/count?nameLike=（改）"),
                "改完之后按新名字要搜得到 —— 否则读成「改了没生效」");

        assertEquals(HttpStatus.OK,
                exchange(HttpMethod.DELETE, "/api/wf/filters/" + filterId, null).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                exchange(HttpMethod.DELETE, "/api/wf/filters/" + filterId, null).getStatusCode(),
                "删过了要报错 —— 静默成功会让调用方以为「已经删掉了」，"
                        + "而实际上它可能压根没删到东西");
    }

    @Test
    @DisplayName("筛选器端点：改类型要报错 —— 改了等于让所有引用它的地方悄悄换掉查什么")
    void filterTypeIsImmutableOverHttp() throws Exception {
        String filterId = (String) asMap(postOk("/api/wf/filters",
                body("name", "flt-类型不可改-" + System.nanoTime(), "resourceType", "task",
                        "properties", body("openOnly", "true"))).get("data")).get("id");

        // 端点**忽略**请求里的 resourceType、保持库里那份 ——
        // 改了类型就是让所有引用它的地方在下次运行时悄悄换掉查什么
        Map<String, Object> kept = readOk(exchange(HttpMethod.PUT,
                "/api/wf/filters/" + filterId,
                body("name", "flt-类型不可改", "resourceType", "incident",
                        "properties", body("openOnly", "false"))));  // name 无关紧要
        assertEquals("task", kept.get("resourceType"), "类型不能被请求改掉");

        // 顺带钉住一个由此产生的行为：拿 incident 的条件来改一张 task 筛选器会被拒。
        // 这条不是"顺带"—— 它正是「类型保持不变」在调用方看得见的那一面，
        // 而它报出来的那句「task 没有条件 retriesExhausted」就是最有用的一句
        ResponseEntity<String> wrongCondition = exchange(HttpMethod.PUT,
                "/api/wf/filters/" + filterId,
                body("name", "flt-类型不可改", "resourceType", "incident",
                        "properties", body("retriesExhausted", "true")));
        assertEquals(HttpStatus.BAD_REQUEST, wrongCondition.getStatusCode(),
                wrongCondition.getBody());
        assertTrue(wrongCondition.getBody().contains("retriesExhausted")
                        && wrongCondition.getBody().contains("assignee"),
                "报错要点名是哪个条件、以及 task 到底支持哪些: " + wrongCondition.getBody());
    }

    @Test
    @DisplayName("筛选器端点：条件名 / 值 / 类型错了都要 400，且报错说清该给什么")
    void filterEndpointsRejectBadInput() throws Exception {
        String tag = String.valueOf(System.nanoTime());
        // 条件名拼错
        ResponseEntity<String> badKey = exchange(HttpMethod.POST, "/api/wf/filters",
                body("name", "bad" + tag, "resourceType", "task",
                        "properties", body("assigne", "alice")));
        assertEquals(HttpStatus.BAD_REQUEST, badKey.getStatusCode(), badKey.getBody());
        assertTrue(badKey.getBody().contains("assignee"),
                "报错要给出正确拼写: " + badKey.getBody());

        // 布尔值写成 JSON 的 true 而不是字符串 —— REST 层就要拦，不能留到服务层。
        // 放服务层的形态是「存得进去、跑的时候才炸」
        ResponseEntity<String> notString = exchange(HttpMethod.POST, "/api/wf/filters",
                body("name", "bad" + tag, "resourceType", "task",
                        "properties", body("openOnly", Boolean.TRUE)));
        assertEquals(HttpStatus.BAD_REQUEST, notString.getStatusCode(),
                "JSON 里的 true 天生是布尔，但筛选器的值是按字符串严格解析的: "
                        + notString.getBody());
        // 响应体是 JSON，里面的引号是**转义过的**：原始字节里是 \"true\" 而不是 "true"。
        // 断言要按转义后的形态写，否则它匹配不到任何东西 —— 而
        // 「匹配不到」会被误读成「报错里没给例子」
        assertTrue(notString.getBody().contains("\\\"true\\\""),
                "报错要直接给出该写的样子: " + notString.getBody());

        // 值的写法不合法
        assertEquals(HttpStatus.BAD_REQUEST, exchange(HttpMethod.POST, "/api/wf/filters",
                body("name", "bad" + tag, "resourceType", "task",
                        "properties", body("openOnly", "yes"))).getStatusCode(),
                "yes 不是 true —— 宽松解析会让「打错一个字」变成「条件悄悄变了」");

        // 类型不认识
        ResponseEntity<String> badType = exchange(HttpMethod.POST, "/api/wf/filters",
                body("name", "bad" + tag, "resourceType", "taskk"));
        assertEquals(HttpStatus.BAD_REQUEST, badType.getStatusCode());
        assertTrue(badType.getBody().contains("processInstance")
                        && badType.getBody().contains("task"),
                "报错要列出合法类型: " + badType.getBody());

        // 名字为空
        assertEquals(HttpStatus.BAD_REQUEST, exchange(HttpMethod.POST, "/api/wf/filters",
                body("resourceType", "task")).getStatusCode());

        // 上面每一条都必须**什么都没存进去**：
        // 返回 400 但数据落了，是这类接口最常见的"看着拒绝了其实没拒绝"
        assertEquals(0, countOf("/api/wf/filters/count?nameLike=bad" + tag),
                "报错的请求不该留下任何一张筛选器");
    }

    private String idOf(Map<String, Object> filter) {
        return (String) filter.get("id");
    }

    private int countOf(String url) throws Exception {
        return ((Number) asMap(getOk(url).get("data")).get("count")).intValue();
    }

    private Map<String, Object> readOk(ResponseEntity<String> response) throws Exception {
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return asMap(json.readValue(response.getBody(), Map.class).get("data"));
    }

    @Test
    @DisplayName("局部变量端点：token 不存在 / id 为空要报错")
    void branchVariableEndpointsRejectBadArguments() throws Exception {
        ResponseEntity<String> nope = exchange(HttpMethod.POST,
                "/api/wf/process/branch-variables",
                body("taskId", "no-such-task", "userId", "ops", "values", body("k", "v")));
        assertEquals(HttpStatus.BAD_REQUEST, nope.getStatusCode(),
                "任务不存在要报错而不是静默丢弃 —— 否则调用方以为设上了: " + nope.getBody());
        // 断言要能区分「没找到任务」与「没找到 token」：入口的参数校验与下游的校验
        // 是两处，只查「不存在」三个字的话两者都能匹配上 ——
        // 反向验证里摘掉入口那处检查，端点照样 400（下游拦住了），用例照样绿
        assertTrue(nope.getBody().contains("任务不存在"),
                "报错要指出是任务没找到，而不是让它滑到 token 那层才报: " + nope.getBody());

        ResponseEntity<String> blank = exchange(HttpMethod.POST,
                "/api/wf/process/branch-variables",
                body("taskId", "  ", "userId", "ops", "values", body("k", "v")));
        assertEquals(HttpStatus.BAD_REQUEST, blank.getStatusCode(),
                "id 为空要当场报错: " + blank.getBody());
    }

    private Map<String, Object> openTaskOf(String assignee, String processId) throws Exception {
        Map<String, Object> page = getOk("/api/approval-center/tasks/todo?userId=" + assignee);
        for (Map<String, Object> record : asList(asMap(page.get("data")).get("records"))) {
            if (processId.equals(record.get("processInstanceId"))) {
                return record;
            }
        }
        throw new IllegalStateException("办理人 " + assignee + " 在实例 " + processId
                + " 上没有待办");
    }

    /**
     * 停在某个节点上的 token id。
     *
     * <p>{@code /api/wf/process/executions} 刻意不返回 token 的 variables
     * （仓里有测试钉着），所以变量本身只能从变量实例端点拿 ——
     * 这两个端点正好互补，这也是本用例要连着跑的原因。
     */
    private String executionIdAt(String processId, String activityId) throws Exception {
        for (Map<String, Object> token : asList(
                getOk("/api/wf/process/executions?processInstanceId=" + processId).get("data"))) {
            if (activityId.equals(token.get("activityId"))) {
                return token.get("executionId").toString();
            }
        }
        return null;
    }

    /**
     * 两条并行分支，各有自己的排他网关，判别式用<b>同一个变量名</b> {@code amount}。
     *
     * <p>办理人带 {@code nanoTime} 是因为所有用例共享同一个 H2 库。
     */
    private String deployParallelProcess() {
        String tag = String.valueOf(System.nanoTime());
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"lpProcess\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <parallelGateway id=\"pg\"/>\n"
                + "    <userTask id=\"branchA\" name=\"甲支线\""
                + " zifang:assignee=\"lp-a-" + tag + "\"/>\n"
                + "    <userTask id=\"branchB\" name=\"乙支线\""
                + " zifang:assignee=\"lp-b-" + tag + "\"/>\n"
                + "    <exclusiveGateway id=\"gwA\"/>\n"
                + "    <exclusiveGateway id=\"gwB\"/>\n"
                + "    <userTask id=\"aBig\" zifang:assignee=\"lp-aBig-" + tag + "\"/>\n"
                + "    <userTask id=\"aSmall\" zifang:assignee=\"lp-aSmall-" + tag + "\"/>\n"
                + "    <userTask id=\"bBig\" zifang:assignee=\"lp-bBig-" + tag + "\"/>\n"
                + "    <userTask id=\"bSmall\" zifang:assignee=\"lp-bSmall-" + tag + "\"/>\n"
                + "    <endEvent id=\"e1\"/><endEvent id=\"e2\"/>\n"
                + "    <endEvent id=\"e3\"/><endEvent id=\"e4\"/>\n"
                + "    <sequenceFlow sourceRef=\"s\" targetRef=\"pg\"/>\n"
                + "    <sequenceFlow sourceRef=\"pg\" targetRef=\"branchA\"/>\n"
                + "    <sequenceFlow sourceRef=\"pg\" targetRef=\"branchB\"/>\n"
                + "    <sequenceFlow sourceRef=\"branchA\" targetRef=\"gwA\"/>\n"
                + "    <sequenceFlow sourceRef=\"branchB\" targetRef=\"gwB\"/>\n"
                + "    <sequenceFlow sourceRef=\"gwA\" targetRef=\"aBig\">\n"
                + "      <conditionExpression>amount &gt; 10000</conditionExpression>\n"
                + "    </sequenceFlow>\n"
                + "    <sequenceFlow sourceRef=\"gwA\" targetRef=\"aSmall\"/>\n"
                + "    <sequenceFlow sourceRef=\"gwB\" targetRef=\"bBig\">\n"
                + "      <conditionExpression>amount &gt; 10000</conditionExpression>\n"
                + "    </sequenceFlow>\n"
                + "    <sequenceFlow sourceRef=\"gwB\" targetRef=\"bSmall\"/>\n"
                + "    <sequenceFlow sourceRef=\"aBig\" targetRef=\"e1\"/>\n"
                + "    <sequenceFlow sourceRef=\"aSmall\" targetRef=\"e2\"/>\n"
                + "    <sequenceFlow sourceRef=\"bBig\" targetRef=\"e3\"/>\n"
                + "    <sequenceFlow sourceRef=\"bSmall\" targetRef=\"e4\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        repositoryService.deploy(new com.zifang.z.wf.core.definition.WfXmlParser().parse(xml));
        return tag;
    }

    // ==================== 按消息/信号发起 ====================

    @Test
    @DisplayName("按事件发起端点：消息能起流程，信号能起流程，参数互斥要报错")
    void startByEventOverHttp() throws Exception {
        // suffix 必须是部署时用的那个：办理人带的是它，两边不是同一个
        // 就会查不到待办，而症状是"待办没建出来"，很难联想到是 tag 对不上
        String suffix = deployTwoEntryProcess();

        // 消息启动
        String byMsg = (String) postOk("/api/approval-center/processes/start-by-event",
                body("messageName", "orderCreated", "businessKey", "WEB-START-" + suffix,
                        "userId", "erp", "variables", body("amount", 500))).get("data");
        assertNotNull(byMsg, "消息启动应当返回实例 id");
        Map<String, Object> msgInstance = asMap(getOk(
                "/api/approval-center/processes/get?processInstanceId=" + byMsg).get("data"));
        assertEquals("ACTIVE", msgInstance.get("status"));
        assertEquals("WEB-START-" + suffix, msgInstance.get("businessKey"),
                "业务键要透传到实例上 —— 外部系统回调后拿到的是单号，不给它就没法回查");
        assertEquals("checkOrder", firstOpenTaskNodeOf("purchase-" + suffix, byMsg),
                "必须落在消息入口那条线上");

        // 信号启动
        String bySig = (String) postOk("/api/approval-center/processes/start-by-event",
                body("signalName", "orderSynced", "businessKey", "WEB-SIG-" + suffix,
                        "userId", "erp")).get("data");
        assertNotNull(bySig);
        assertEquals("checkSync", firstOpenTaskNodeOf("sync-" + suffix, bySig),
                "信号启动要走信号入口那条线，不能与消息入口混");

        // 两个都填：报错，且要说清都填了什么
        ResponseEntity<String> both = exchange(HttpMethod.POST,
                "/api/approval-center/processes/start-by-event",
                body("messageName", "orderCreated", "signalName", "orderSynced"));
        assertEquals(HttpStatus.BAD_REQUEST, both.getStatusCode(),
                "两个都填时挑一个执行等于替调用方做决定，而它要的结果很可能不是另一个: "
                        + both.getBody());
        assertTrue(both.getBody().contains("只能填一个"),
                "报错要说清约束是什么。实际: " + both.getBody());

        // 两个都不填：同样报错。
        // 断言的是**文案**而不只是状态码：引擎层也有"事件名不能为空"的兜底，
        // 只断言 400 的话，端点层那道检查被摘掉也照样绿 —— 两层检查串在一起
        // 时，状态码是没有区分力的那种断言
        ResponseEntity<String> neither = exchange(HttpMethod.POST,
                "/api/approval-center/processes/start-by-event", body("businessKey", "X"));
        assertEquals(HttpStatus.BAD_REQUEST, neither.getStatusCode(),
                "都不填要报错，而不是去启动一个无条件入口的流程: " + neither.getBody());
        assertTrue(neither.getBody().contains("只能填一个"),
                "端点层要给出「必须且只能填一个」这种能指导调用的提示。"
                        + "只报「信号名不能为空」的话，调用方看不出自己是两个都没填: "
                        + neither.getBody());

        // 没有流程订阅这条消息：400，且点名消息名
        ResponseEntity<String> unknown = exchange(HttpMethod.POST,
                "/api/approval-center/processes/start-by-event",
                body("messageName", "noSuchMessage"));
        assertEquals(HttpStatus.BAD_REQUEST, unknown.getStatusCode(),
                "找不到订阅者要报错: " + unknown.getBody());
        assertTrue(unknown.getBody().contains("noSuchMessage"),
                "报错要点名消息。实际: " + unknown.getBody());

        // 手工发起的那个入口仍然可用
        String plain = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webTwoEntryProcess", "businessKey", "WEB-PLAIN-" + suffix,
                        "userId", "alice", "variables", body("amount", 500))).get("data");
        assertEquals("checkAmount", firstOpenTaskNodeOf("finance-" + suffix, plain),
                "加了消息起始之后手工发起不能被带偏");
    }

    private String firstOpenTaskNodeOf(String assigneeTag, String processId) throws Exception {
        Map<String, Object> page = getOk(
                "/api/approval-center/tasks/todo?userId=" + assigneeTag);
        for (Map<String, Object> record : asList(asMap(page.get("data")).get("records"))) {
            if (processId.equals(record.get("processInstanceId"))) {
                return (String) record.get("definitionId");
            }
        }
        return null;
    }

    /**
     * 部署一个「手工发起 + 消息启动 + 信号启动」三入口的流程。
     *
     * <p>三个入口各走各的第一个待办（{@code checkAmount} / {@code checkOrder} /
     * {@code checkSync}）是刻意的：汇合到同一个待办的话，"走的是哪个入口"就没法
     * 从对外可见的东西上判出来，而那正是这条用例要证明的事。
     * 办理人带 {@code nanoTime} 后缀是因为所有用例共享同一个 H2 库。
     */
    private String deployTwoEntryProcess() {
        String tag = String.valueOf(System.nanoTime());
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"webTwoEntryProcess\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"manualStart\"/>\n"
                + "    <startEvent id=\"orderStart\">\n"
                + "      <messageEventDefinition messageRef=\"orderCreated\"/>\n"
                + "    </startEvent>\n"
                + "    <startEvent id=\"syncStart\">\n"
                + "      <signalEventDefinition signalRef=\"orderSynced\"/>\n"
                + "    </startEvent>\n"
                + "    <userTask id=\"checkAmount\" name=\"核金额\""
                + " zifang:assignee=\"finance-" + tag + "\"/>\n"
                + "    <userTask id=\"checkOrder\" name=\"核订单\""
                + " zifang:assignee=\"purchase-" + tag + "\"/>\n"
                + "    <userTask id=\"checkSync\" name=\"核同步\""
                + " zifang:assignee=\"sync-" + tag + "\"/>\n"
                + "    <endEvent id=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"manualStart\" targetRef=\"checkAmount\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"orderStart\" targetRef=\"checkOrder\"/>\n"
                + "    <sequenceFlow id=\"f3\" sourceRef=\"syncStart\" targetRef=\"checkSync\"/>\n"
                + "    <sequenceFlow id=\"f4\" sourceRef=\"checkAmount\" targetRef=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f5\" sourceRef=\"checkOrder\" targetRef=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f6\" sourceRef=\"checkSync\" targetRef=\"e1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        repositoryService.deploy(new com.zifang.z.wf.core.definition.WfXmlParser().parse(xml));
        return tag;
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
    @DisplayName("默认流程端点：设为默认 → 按默认发起 → 停用后被拒 → 取消默认")
    void defaultDefinitionEndpoints() throws Exception {
        String key = "restDefault-" + (System.nanoTime() % 100000);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"" + key + "\" name=\"默认流程\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"ds1\"/>\n"
                + "    <userTask id=\"dapprove\" name=\"审批\" zifang:assignee=\"d-boss\"/>\n"
                + "    <endEvent id=\"de1\"/>\n"
                + "    <sequenceFlow id=\"df1\" sourceRef=\"ds1\" targetRef=\"dapprove\"/>\n"
                + "    <sequenceFlow id=\"df2\" sourceRef=\"dapprove\" targetRef=\"de1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        postOk("/api/wf/definitions/deploy", body("key", key, "xml", xml));

        // 还没设默认时查得到 null —— 入口页要能据此提示「还没配默认」，而不是收到 4xx
        assertNull(getOk("/api/wf/definitions/default").get("data"),
                "没设过默认是正常状态，查询端点要返回 null 而不是报错");

        // 按默认发起此时必须被拒，并说清怎么修
        ResponseEntity<String> noDefault = exchange(HttpMethod.POST,
                "/api/approval-center/processes/start-default",
                body("businessKey", "DEF-0", "userId", "alice"));
        assertEquals(statusOf("onDefinition"), noDefault.getStatusCode(),
                "没配默认就不能按默认发起。实际: " + noDefault.getBody());
        assertTrue(noDefault.getBody().contains("setDefaultDefinition"),
                "报错要指明怎么修。实际: " + noDefault.getBody());

        // 设为默认
        Map<String, Object> set = asMap(
                postOk("/api/wf/definitions/default?key=" + key + "&version=1", null).get("data"));
        assertEquals(key, set.get("key"));
        assertEquals(Boolean.TRUE, set.get("defaultDefinition"));
        assertEquals(key, asMap(getOk("/api/wf/definitions/default").get("data")).get("key"));

        // 定义列表里要能看出哪条是默认，否则列表页显示不出来
        List<Map<String, Object>> listed = asList(
                getOk("/api/wf/definitions?keyLike=" + key).get("data"));
        assertEquals(1, listed.size());
        assertEquals(Boolean.TRUE, listed.get(0).get("defaultDefinition"),
                "定义列表要带默认标记");

        // 按默认发起：不传 key 也能起
        String pid = (String) postOk("/api/approval-center/processes/start-default",
                body("businessKey", "DEF-1", "userId", "alice")).get("data");
        assertNotNull(pid, "按默认发起应当成功");
        assertEquals(key, asMap(getOk("/api/approval-center/processes/get?processInstanceId=" + pid)
                .get("data")).get("definitionKey"),
                "按默认发起必须起默认那一条 —— 悄悄起别的流程等于「默认」这个配置形同虚设");

        // 停用默认之后：标记还在，但按默认发起被拒
        postOk("/api/wf/definitions/suspend?key=" + key + "&version=1", null);
        assertEquals(key, asMap(getOk("/api/wf/definitions/default").get("data")).get("key"),
                "停用不该顺手把默认标记也清掉 —— 取消默认是一次显式的运营决策");
        ResponseEntity<String> blocked = exchange(HttpMethod.POST,
                "/api/approval-center/processes/start-default",
                body("businessKey", "DEF-2", "userId", "alice"));
        assertEquals(statusOf("onDefinition"), blocked.getStatusCode(),
                "默认指向一个起不来的定义时必须当场报错。实际: " + blocked.getBody());
        assertTrue(blocked.getBody().contains("已停用"),
                "拒绝的原因要点明是停用。实际: " + blocked.getBody());

        // 停用的版本不能被设为默认
        ResponseEntity<String> setSuspended = exchange(HttpMethod.POST,
                "/api/wf/definitions/default?key=" + key + "&version=1", null);
        assertEquals(statusOf("onDefinition"), setSuspended.getStatusCode(),
                "已停用的版本不能设为默认。实际: " + setSuspended.getBody());

        // 取消默认
        postOk("/api/wf/definitions/activate?key=" + key + "&version=1", null);
        exchange(HttpMethod.DELETE, "/api/wf/definitions/default?key=" + key + "&version=1", null);
        assertNull(getOk("/api/wf/definitions/default").get("data"),
                "取消之后应当真的没有默认了");
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
     * 某人的待办条数。
     *
     * <p>阈值汇合那条用例要断「决议待办恰好 1 个」，
     * 而 {@code firstTodoId} 断不出"有几个" —— 它只取第一条。
     */
    private int todoCount(String userId) throws Exception {
        Map<String, Object> page = getOk("/api/approval-center/tasks/todo?userId=" + userId);
        return asList(asMap(page.get("data")).get("records")).size();
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

    // ==================== 批量操作端点（第 39 轮） ====================

    /**
     * 批量端点走真实 HTTP。
     *
     * <p>和 DMN 那条一样的理由：z-wf-web 的单测是 {@code standaloneSetup}
     * （手工 new controller + 注入 service），它<b>断不到自动装配</b>。
     * {@code WfBatchService} 有没有被注册成 Bean、{@code ZWF_BATCH} 表建了没有、
     * 路由有没有挂上，只有起完整上下文才看得见 ——
     * 而"服务写了、REST 层忘了接线"正是最常见的半成品：单测全绿，端点 404。
     *
     * <p>这条还顺带钉住了 JDBC 侧：批次与它的条件/操作都要真的走一趟
     * {@code JSON → TEXT 列 → JSON} 的往返。内存实现下这一步是空气，
     * 所以 JSON 里少写一个字段、枚举怎么存，两套实现就会分家。
     */
    @Test
    @DisplayName("批量端点：创建（不动数据）→ 看命中数 → 执行 → 查明细，全部走 HTTP")
    void batchEndpointsOverHttp() throws Exception {
        String key = deployBatchProcess();
        String tag = String.valueOf(System.nanoTime());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Map<String, Object> start = postOk("/api/approval-center/processes/start",
                    body("definitionKey", key, "businessKey", "BATCH-" + tag + "-" + i,
                            "userId", "batch-alice", "deptId", "d1", "variables", new HashMap<>()));
            ids.add((String) start.get("data"));
        }
        assertEquals(2, ids.size());

        Map<String, Object> created = asMap(postOk("/api/wf/batches", body(
                "batchType", "INSTANCE",
                "criteria", body("processDefinitionKey", key),
                "operations", java.util.Collections.singletonList(
                        body("type", "setVariable", "variable", "migrated", "value", "v2")),
                "operatorId", "admin-web")).get("data"));
        String batchId = (String) created.get("id");
        assertEquals("CREATED", created.get("state"), "刚创建必须是「已创建」");
        assertEquals("已创建", created.get("stateLabel"));
        assertEquals(0, ((Number) created.get("affectedCount")).intValue());
        assertTrue(((String) created.get("criteria")).contains(key),
                "条件必须原样落库并回显 —— 「这批当初是按什么建的」是排障第一个要问的问题");

        // **两段式在 HTTP 上也成立**：创建之后数据一个字节都没动
        for (String id : ids) {
            // 用 assertNull 而不是 assertFalse(value.contains(...))：变量压根不存在时
            // get 返回 null，对 null 调方法抛的是 NPE，报错指向不到真正的原因
            assertNull(instanceVariables(id).get("migrated"),
                    "创建阶段绝不能改数据。实例 " + id + " 的变量: " + instanceVariables(id));
        }

        // 执行前的那一眼
        assertEquals(2, count("/api/wf/batches/" + batchId + "/count"));

        Map<String, Object> done = asMap(postOk("/api/wf/batches/" + batchId + "/execute", null)
                .get("data"));
        assertEquals("COMPLETED", done.get("state"));
        assertEquals(2, ((Number) done.get("affectedCount")).intValue());
        assertEquals(0, ((Number) done.get("failureCount")).intValue());
        assertNotNull(done.get("startTime"), "执行过就要有开始时间");
        assertNotNull(done.get("endTime"), "执行过就要有结束时间");

        for (String id : ids) {
            assertEquals("v2", instanceVariables(id).get("migrated"),
                    "实例 " + id + " 必须真的被改到了。实际变量: " + instanceVariables(id));
        }

        Map<String, Object> elements = asMap(getOk("/api/wf/batches/" + batchId + "/elements")
                .get("data"));
        assertEquals(2, ((Number) elements.get("total")).intValue());
        assertEquals("SUCCESS", asList(elements.get("elements")).get(0).get("state"));

        // 列表：口径一致
        Map<String, Object> list = asMap(getOk("/api/wf/batches?state=COMPLETED").get("data"));
        assertTrue(((Number) list.get("total")).intValue() >= 1);
    }

    @Test
    @DisplayName("批量端点：重复执行 / 挂起后执行 / 空条件 都是 400")
    void batchRejectionsOverHttp() throws Exception {
        String key = deployBatchProcess();
        postOk("/api/approval-center/processes/start",
                body("definitionKey", key, "businessKey", "BATCH-REJ-" + System.nanoTime(),
                        "userId", "batch-alice", "deptId", "d1", "variables", new HashMap<>()));

        String batchId = createBatchOverHttp(key);
        assertEquals(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/wf/batches/" + batchId + "/execute", null).getStatusCode());
        // 批次不做二次执行 —— 点了两次是最容易犯的错
        assertEquals(HttpStatus.BAD_REQUEST, exchange(HttpMethod.POST,
                "/api/wf/batches/" + batchId + "/execute", null).getStatusCode());

        String other = createBatchOverHttp(key);
        assertEquals(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/wf/batches/" + other + "/suspend", null).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, exchange(HttpMethod.POST,
                "/api/wf/batches/" + other + "/execute", null).getStatusCode());

        // 条件为空 = 改全部，必须挡住
        ResponseEntity<String> empty = exchange(HttpMethod.POST, "/api/wf/batches", body(
                "batchType", "INSTANCE", "criteria", body(),
                "operations", java.util.Collections.singletonList(
                        body("type", "setVariable", "variable", "x", "value", 1))));
        assertEquals(HttpStatus.BAD_REQUEST, empty.getStatusCode(),
                "空条件必须挡住，响应: " + empty.getBody());
    }

    /** 部署一个带待办的流程，返回它的 definitionKey（带时间戳，避免跨用例撞 key）。 */
    private String deployBatchProcess() {
        String key = "webBatchProcess-" + System.nanoTime();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"" + key + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"bs\"/>\n"
                + "    <userTask id=\"bapprove\" name=\"批量审批\" zifang:assignee=\"batch-boss\"/>\n"
                + "    <endEvent id=\"be1\"/>\n"
                + "    <sequenceFlow id=\"bf1\" sourceRef=\"bs\" targetRef=\"bapprove\"/>\n"
                + "    <sequenceFlow id=\"bf2\" sourceRef=\"bapprove\" targetRef=\"be1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        repositoryService.deploy(new com.zifang.z.wf.core.definition.WfXmlParser().parse(xml));
        return key;
    }

    private String createBatchOverHttp(String definitionKey) throws Exception {
        Map<String, Object> created = asMap(postOk("/api/wf/batches", body(
                "batchType", "INSTANCE",
                "criteria", body("processDefinitionKey", definitionKey),
                "operations", java.util.Collections.singletonList(
                        body("type", "setVariable", "variable", "migrated", "value", "v2")),
                "operatorId", "admin-web")).get("data"));
        return (String) created.get("id");
    }

    /** 读实例的变量：走 HTTP 读的才是「线上真正看到的那份」。 */
    private Map<String, Object> instanceVariables(String processInstanceId) throws Exception {
        Map<String, Object> detail = asMap(getOk("/api/approval-center/processes/get?processInstanceId="
                + processInstanceId).get("data"));
        Map<String, Object> variables = detail.get("variables") == null
                ? new HashMap<String, Object>() : asMap(detail.get("variables"));
        return variables;
    }

    // ==================== DMN 决策表端点 ====================

    /**
     * DMN 端点走真实 HTTP。
     *
     * <p>z-wf-web 的单元测试是 {@code standaloneSetup}（手工 new controller + 注入 service），
     * 它<b>断不到自动装配</b>：{@code WfDecisionService} 有没有被注册成 Bean、
     * 路由有没有真的挂上，只有在这条起完整上下文的用例里才看得见。
     * 而"服务写了、REST 层忘了接线"正是最常见的那种半成品 ——
     * 单元测试照样全绿，端点 404。
     */
    @Test
    @DisplayName("DMN 端点：部署 → 求值 → 删版本，全部走 HTTP")
    void decisionEndpointsOverHttp() throws Exception {
        String key = "webLevel-" + System.nanoTime();
        String dmn = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\">\n"
                + "  <decision id=\"" + key + "\" name=\"层级\">\n"
                + "    <decisionTable id=\"t\" hitPolicy=\"FIRST\">\n"
                + "      <input id=\"i\"><inputExpression id=\"ie\"><text>amount</text>"
                + "</inputExpression></input>\n"
                + "      <output id=\"o\" name=\"level\"/>\n"
                + "      <rule><inputEntry><text>&gt; 50000</text></inputEntry>"
                + "<outputEntry><text>\"ceo\"</text></outputEntry></rule>\n"
                + "      <rule><inputEntry><text>-</text></inputEntry>"
                + "<outputEntry><text>\"staff\"</text></outputEntry></rule>\n"
                + "    </decisionTable>\n"
                + "  </decision>\n"
                + "</definitions>\n";

        Map<String, Object> deployed = asMap(postOk("/api/wf/decisions/deploy",
                body("dmnXml", dmn)).get("data"));
        List<Map<String, Object>> records = asList(deployed.get("deployed"));
        assertEquals(1, records.size(), "一个文件里的一个 decision 应当只部署出一条");
        assertEquals(key, records.get(0).get("key"));
        assertEquals(1, ((Number) records.get(0).get("version")).intValue());

        Map<String, Object> result = asMap(postOk("/api/wf/decisions/" + key + "/evaluate",
                body("variables", body("amount", 60000))).get("data"));
        assertEquals("ceo", asList(result.get("rows")).get(0).get("level"));
        assertEquals(2, ((Number) result.get("matchedRuleCount")).intValue(),
                "命中的规则数是 2（> 50000 与恒真的 - 都成立），FIRST 取的是第一条");

        ResponseEntity<String> deleted = exchange(HttpMethod.DELETE,
                "/api/wf/decisions/" + key + "/versions/1", null);
        assertEquals(HttpStatus.OK, deleted.getStatusCode(), deleted.getBody());

        // 删掉之后求值必须报"不存在"，而不是拿别的版本悄悄顶上
        ResponseEntity<String> afterDelete = exchange(HttpMethod.POST,
                "/api/wf/decisions/" + key + "/evaluate", body("variables", body("amount", 1)));
        assertEquals(HttpStatus.BAD_REQUEST, afterDelete.getStatusCode(), afterDelete.getBody());
    }

    /**
     * 决策图（第 35 轮）走真实 HTTP：部署两跳依赖 → 求值 → 回读能看到依赖边。
     *
     * <p>它盯的是三件单元测试断不到的事：
     * <ol>
     *   <li><b>依赖边过了 HTTP 往返还活着</b>。若 {@code WfDecisionService} 走的是
     *       另一套装配、或 controller 的 view 漏了这个字段，回读时就看不到图 ——
     *       而排障时"这张决策依赖谁"是最先要看的东西。</li>
     *   <li><b>求值真的按依赖展开</b>：第二跳的输入是第一跳的输出，
     *       中间隔着一次 HTTP，链断在哪一环都看得出来。</li>
     *   <li><b>成环在部署端点上就报 400</b>，而不是部署成功、等到求值才栈溢出。</li>
     * </ol>
     */
    @Test
    @DisplayName("DMN 决策图：跨 HTTP 部署依赖图 → 求值按依赖展开 → 回读能看到依赖边")
    void decisionGraphOverHttp() throws Exception {
        long stamp = System.nanoTime();
        String upstream = "risk" + stamp;
        String downstream = "level" + stamp;
        // 下游**写在前面**：跨 HTTP 往返之后声明次序仍然不该影响结果
        String dmn = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\">\n"
                + "  <decision id=\"" + downstream + "\" name=\"层级\">\n"
                + "    <informationRequirement><requiredDecision href=\"#" + upstream
                + "\"/></informationRequirement>\n"
                + "    <decisionTable id=\"td\" hitPolicy=\"FIRST\">\n"
                + "      <input id=\"i\"><inputExpression id=\"ie\"><text>risk</text>"
                + "</inputExpression></input>\n"
                + "      <output id=\"o\" name=\"level\"/>\n"
                + "      <rule><inputEntry><text>== \"high\"</text></inputEntry>"
                + "<outputEntry><text>\"ceo\"</text></outputEntry></rule>\n"
                + "      <rule><inputEntry><text>-</text></inputEntry>"
                + "<outputEntry><text>\"staff\"</text></outputEntry></rule>\n"
                + "    </decisionTable>\n"
                + "  </decision>\n"
                + "  <decision id=\"" + upstream + "\" name=\"风险\">\n"
                + "    <decisionTable id=\"tu\" hitPolicy=\"FIRST\">\n"
                + "      <input id=\"i\"><inputExpression id=\"ie\"><text>amount</text>"
                + "</inputExpression></input>\n"
                + "      <output id=\"o\" name=\"risk\"/>\n"
                + "      <rule><inputEntry><text>&gt; 10000</text></inputEntry>"
                + "<outputEntry><text>\"high\"</text></outputEntry></rule>\n"
                + "      <rule><inputEntry><text>-</text></inputEntry>"
                + "<outputEntry><text>\"low\"</text></outputEntry></rule>\n"
                + "    </decisionTable>\n"
                + "  </decision>\n"
                + "</definitions>\n";

        List<Map<String, Object>> deployed = asList(asMap(postOk("/api/wf/decisions/deploy",
                body("dmnXml", dmn)).get("data")).get("deployed"));
        assertEquals(2, deployed.size(), "一个决策图文件部署出两个决策");

        // 回读必须能看到依赖边 —— 排障时最先看的就是"它依赖谁"
        Map<String, Object> view = asMap(getOk("/api/wf/decisions/" + downstream).get("data"));
        assertEquals(1, asList(view.get("requiredDecisions")).size(),
                "回读视图里必须有 requiredDecisions，实际视图: " + view);

        // 链断在哪一环都看得出来：amount 只喂给上游，risk 只由上游产出
        Map<String, Object> result = asMap(postOk("/api/wf/decisions/" + downstream + "/evaluate",
                body("variables", body("amount", 50000))).get("data"));
        assertEquals("ceo", asList(result.get("rows")).get(0).get("level"),
                "上游算出 risk=high，下游据此算出 level=ceo");
        assertEquals(2, ((Number) result.get("matchedRuleCount")).intValue(),
                "命中条数只数下游这张表（== high 与恒真的 - 都成立）。"
                        + "上游那张表此刻也是 2 条命中，若两处并起来会是 4 —— "
                        + "上游的输出进的是求值上下文，不是本决策的结果");

        // 成环必须在部署端点上就报出来，而不是部署成功、等求值时栈溢出
        ResponseEntity<String> cyclic = exchange(HttpMethod.POST, "/api/wf/decisions/deploy",
                body("dmnXml", dmn.replace("<requiredDecision href=\"#" + upstream + "\"/>",
                        "<requiredDecision href=\"#" + downstream + "\"/>")));
        assertEquals(HttpStatus.BAD_REQUEST, cyclic.getStatusCode(), cyclic.getBody());
    }

    // ==================== 业务规则任务端到端 ====================

    /**
     * 业务规则任务走真实 HTTP，且必须真的跑出变量。
     *
     * <p>它盯的是<b>接线</b>：业务规则任务要求值决策，而决策服务要由
     * {@code WfAutoConfiguration} 挂到引擎上。漏挂的话节点要么 NPE、
     * 要么落到"穿透但什么都不做"的兜底行为 —— <b>流程照样跑完</b>。
     * 单元测试（手工 new 引擎 + 手工注入）永远看不到这一段，
     * 所以这里必须从 HTTP 走一遍完整链路。
     */
    @Test
    @DisplayName("业务规则任务：部署 BPMN + DMN → 发起 → 变量里真的有决策结论")
    void businessRuleTaskOverHttp() throws Exception {
        String key = "brtFlow-" + System.nanoTime();
        String decisionKey = "brtDecision-" + System.nanoTime();
        String dmn = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\">\n"
                + "  <decision id=\"" + decisionKey + "\" name=\"层级\">\n"
                + "    <decisionTable id=\"t\" hitPolicy=\"FIRST\">\n"
                + "      <input id=\"i\"><inputExpression id=\"ie\"><text>amount</text>"
                + "</inputExpression></input>\n"
                + "      <output id=\"o\" name=\"level\"/>\n"
                + "      <rule><inputEntry><text>&gt; 50000</text></inputEntry>"
                + "<outputEntry><text>\"ceo\"</text></outputEntry></rule>\n"
                + "      <rule><inputEntry><text>-</text></inputEntry>"
                + "<outputEntry><text>\"staff\"</text></outputEntry></rule>\n"
                + "    </decisionTable>\n"
                + "  </decision>\n"
                + "</definitions>\n";
        postOk("/api/wf/decisions/deploy", body("dmnXml", dmn));

        String bpmn = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"" + key + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <businessRuleTask id=\"brt\" zifang:decisionRef=\"" + decisionKey + "\""
                + " zifang:resultVariable=\"level\" zifang:mapDecisionResult=\"singleEntry\"/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"brt\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"brt\" targetRef=\"e\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        postOk("/api/wf/definitions/deploy", body("xml", bpmn, "key", key));

        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", key, "businessKey", "BRT-" + System.nanoTime(),
                        "userId", "brt-alice", "deptId", "d1",
                        "variables", body("amount", 60000))).get("data");
        assertNotNull(processId, "流程应当被启动");

        Map<String, Object> variables = asMap(getOk("/api/wf/process/variables?processInstanceId="
                + processId).get("data"));
        assertEquals("ceo", variables.get("level"),
                "决策结论必须真的落到 resultVariable 上 —— "
                        + "没落的话流程照样跑完，而下游读这个变量拿到的是 null");
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
    @DisplayName("job 手动触发端点：没到期也能提前触发，走打断分支，且只能触发一次")
    void manualJobTriggerOverHttp() throws Exception {
        deployTimerProcess();
        String tag = "WEB-JOBTRIG-" + System.nanoTime();
        Map<String, Object> vars = new HashMap<>();
        vars.put("sla", "PT30M");
        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webTimerProcess", "businessKey", tag,
                        "userId", "jobtrig-owner-" + tag, "variables", vars)).get("data");

        // 前置：领办人真的有待办，且定时器还没到点（到了就不成其为「提前」）
        assertFalse(asList(asMap(getOk("/api/approval-center/tasks/todo?userId=web-timer-leader")
                .get("data")).get("records")).isEmpty(), "前置：领办人应有待办");
        List<Map<String, Object>> jobs = asList(asMap(getOk(
                "/api/wf/history/jobs?processInstanceId=" + processId)
                .get("data")).get("records"));
        assertEquals(1, jobs.size(), "前置：应当恰好一只表。实际 " + jobs);
        String jobId = (String) jobs.get(0).get("jobId");
        assertTrue(((Number) jobs.get(0).get("duedate")).longValue() > System.currentTimeMillis(),
                "前置：还没到点");

        // 触发：没到期也响，且响应里必须能区分「响了」与「没响」
        Map<String, Object> triggered = asMap(postOk(
                "/api/wf/jobs/" + jobId + "/trigger?userId=web-ops", null).get("data"));
        assertEquals(Boolean.TRUE, triggered.get("triggered"), "提前触发应当真的触发。实际 " + triggered);
        assertEquals(jobId, triggered.get("jobId"));

        // 打断：领办人的待办作废，边界那条线走完（催办待办建给 ceo）
        assertTrue(asList(asMap(getOk("/api/approval-center/tasks/todo?userId=web-timer-leader")
                .get("data")).get("records")).isEmpty(),
                "边界触发是打断：宿主上的待办应当作废");
        assertFalse(asList(asMap(getOk("/api/approval-center/tasks/todo?userId=web-timer-ceo")
                .get("data")).get("records")).isEmpty(),
                "催办待办应当被建出来 —— 流程真的走了边界那条线");

        // job 被消费掉
        assertTrue(asList(asMap(getOk("/api/wf/history/jobs?processInstanceId=" + processId)
                .get("data")).get("records")).isEmpty(),
                "已触发的 job 应当被消费掉");

        // 只能触发一次：第二次必须在「找不到」这一层就停住
        ResponseEntity<String> again = exchange(HttpMethod.POST,
                "/api/wf/jobs/" + jobId + "/trigger?userId=web-ops", null);
        assertEquals(HttpStatus.BAD_REQUEST, again.getStatusCode(),
                "重复触发要报错而不是幂等成功。实际: " + again.getBody());
    }

    @Test
    @DisplayName("两个 job 端点的 id 字段名必须一致（视图分叉会让排障界面点不动）")
    void bothJobEndpointsAgreeOnIdFieldName() throws Exception {
        deployTimerProcess();
        String tag = "WEB-JOBID-" + System.nanoTime();
        Map<String, Object> vars = new HashMap<>();
        vars.put("sla", "PT30M");
        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webTimerProcess", "businessKey", tag,
                        "userId", "jobid-owner-" + tag, "variables", vars)).get("data");

        Map<String, Object> fromHistory = asList(asMap(getOk(
                "/api/wf/history/jobs?processInstanceId=" + processId)
                .get("data")).get("records")).get(0);
        // 注意：**ops 端点直接返回 List，不是 PageResult** —— 它没有 total/records 那层包装
        Map<String, Object> fromOps = asList(getOk(
                "/api/wf/jobs?processInstanceId=" + processId).get("data")).get(0);

        // **两边必须用同一个字段名**：运维从其中一个列表复制 id，拿去另一个端点查，
        // 字段名一旦分叉就是「明明有、点进去是空的」，而没有任何报错。
        // 同一个实体两个视图、两个 id 名字，是本轮真的差点写出来的东西
        assertEquals(fromHistory.get("jobId"), fromOps.get("jobId"),
                "两个 job 端点的 id 字段名必须一致。history 端点返回字段集="
                        + fromHistory.keySet() + "，ops 端点返回字段集=" + fromOps.keySet());
        assertNotNull(fromOps.get("priority"),
                "排障要能看出「为什么这条排在那条后面」，而它在图上看不出来");

        // **必须把这条 job 清掉**：/api/wf/history/jobs/execute 是全库扫的，
        // 本类共享同一个 H2 库，留一只没到点的定时器在下面，
        // 别的用例（jobsOverHttp 断言「执行端点消费掉 1 个」）就会数成 2。
        // 留着不清理的话，一条与本用例无关的失败会指向这里。
        postOk("/api/wf/jobs/" + fromOps.get("jobId") + "/trigger?userId=web-cleanup", null);
        assertTrue(asList(asMap(getOk("/api/wf/history/jobs?processInstanceId=" + processId)
                .get("data")).get("records")).isEmpty(),
                "清理动作应当真的把这条 job 消费掉");
    }

    /**
     * 复杂网关的阈值汇合（2/3 会签）走真实 HTTP。
     *
     * <p>与 core 判据分工：core 断的是引擎的判定分支，这里断的是
     * <b>端到端跑通 + 走的是 HTTP 而不是内部调用</b>。
     * 特别地，晚到那条被消费掉之后<b>不该让流程卡住</b> ——
     * 而"卡住"在 HTTP 上是查得到的（本类有流程详情端点），在内部分支上不易察觉。
     */
    @Test
    @DisplayName("阈值汇合端到端：2/3 放行、晚到被消费、流程仍能走完")
    void complexGatewayThresholdOverHttp() throws Exception {
        String key = "restThreshold-" + (System.nanoTime() % 100000);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"" + key + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"ts\"/>\n"
                + "    <parallelGateway id=\"tfork\"/>\n"
                + "    <userTask id=\"tlegal\" name=\"法务审\" zifang:assignee=\"th-legal\"/>\n"
                + "    <userTask id=\"tfinance\" name=\"财务审\" zifang:assignee=\"th-finance\"/>\n"
                + "    <userTask id=\"tcompliance\" name=\"合规审\" zifang:assignee=\"th-compliance\"/>\n"
                + "    <complexGateway id=\"tjoin\" zifang:activationCondition=\"2\"/>\n"
                + "    <userTask id=\"tdecide\" name=\"决议\" zifang:assignee=\"th-ceo\"/>\n"
                + "    <endEvent id=\"te\"/>\n"
                + "    <sequenceFlow id=\"t1\" sourceRef=\"ts\" targetRef=\"tfork\"/>\n"
                + "    <sequenceFlow id=\"t2\" sourceRef=\"tfork\" targetRef=\"tlegal\"/>\n"
                + "    <sequenceFlow id=\"t3\" sourceRef=\"tfork\" targetRef=\"tfinance\"/>\n"
                + "    <sequenceFlow id=\"t4\" sourceRef=\"tfork\" targetRef=\"tcompliance\"/>\n"
                + "    <sequenceFlow id=\"t5\" sourceRef=\"tlegal\" targetRef=\"tjoin\"/>\n"
                + "    <sequenceFlow id=\"t6\" sourceRef=\"tfinance\" targetRef=\"tjoin\"/>\n"
                + "    <sequenceFlow id=\"t7\" sourceRef=\"tcompliance\" targetRef=\"tjoin\"/>\n"
                + "    <sequenceFlow id=\"t8\" sourceRef=\"tjoin\" targetRef=\"tdecide\"/>\n"
                + "    <sequenceFlow id=\"t9\" sourceRef=\"tdecide\" targetRef=\"te\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        repositoryService.deploy(new com.zifang.z.wf.core.definition.WfXmlParser().parse(xml));

        String tag = "WEB-THR-" + System.nanoTime();
        String processId = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", key, "businessKey", tag, "userId", "thr-owner-" + tag))
                .get("data");

        postOk("/api/approval-center/tasks/complete", body("taskId", firstTodoId("th-legal"),
                "userId", "th-legal", "comment", "法务同意"));
        assertEquals(0, todoCount("th-ceo"),
                "只到一条不该放行 —— 会签变成了一张纸");

        postOk("/api/approval-center/tasks/complete", body("taskId", firstTodoId("th-finance"),
                "userId", "th-finance", "comment", "财务同意"));
        assertEquals(1, todoCount("th-ceo"), "凑够 2 条就放行。实际决议待办数: " + todoCount("th-ceo"));
        assertEquals(1, todoCount("th-compliance"),
                "合规还在办，它的待办不该被顺手结束掉");

        // 晚到那条：被消费掉，既不冒出第二个决议待办，也不把流程挂死
        postOk("/api/approval-center/tasks/complete", body("taskId", firstTodoId("th-compliance"),
                "userId", "th-compliance", "comment", "合规同意"));
        assertEquals(1, todoCount("th-ceo"),
                "晚到的那条被消费掉：WCP-30 说后续使能不再把控制权往后传。"
                        + "实际决议待办数: " + todoCount("th-ceo"));

        // 流程必须还能走完 —— 消费掉晚到令牌最怕的就是把实例挂在这儿
        postOk("/api/approval-center/tasks/complete", body("taskId", firstTodoId("th-ceo"),
                "userId", "th-ceo", "comment", "决议通过"));
        Map<String, Object> overview = asMap(getOk(
                "/api/approval-center/processes/get?processInstanceId=" + processId).get("data"));
        assertNotNull(overview.get("status"), "办结后流程详情仍应可查。实际: " + overview);
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

    // ==================== 消息关联端点 ====================

    @Test
    @DisplayName("消息关联端点：不给流程实例 id，按业务变量配到唯一那条")
    void messageCorrelateByVariableOverHttp() throws Exception {
        String tag = "WEB-COR-" + System.nanoTime();
        postOk("/api/wf/definitions/deploy", body("key", "webCorrelate",
                "xml", CORRELATE_RECEIVE_BPMN));

        // 两条单等同一个消息名，业务变量不同 ——
        // 这正是消息驱动集成的常态：ERP 只知道单号，不知道流程实例 id
        Map<String, Object> varsA = new HashMap<>();
        varsA.put("orderNo", tag + "-A");
        Map<String, Object> varsB = new HashMap<>();
        varsB.put("orderNo", tag + "-B");
        String pidA = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webCorrelate", "businessKey", tag + "-A",
                        "userId", "cor-alice", "variables", varsA)).get("data");
        String pidB = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webCorrelate", "businessKey", tag + "-B",
                        "userId", "cor-bob", "variables", varsB)).get("data");

        Map<String, Object> vars = new HashMap<>();
        vars.put("orderNo", tag + "-B");
        Map<String, Object> hit = asMap(postOk("/api/wf/process/message/correlate",
                body("messageName", "erpPaid", "variables", vars,
                        "userId", "erp-callback", "comment", "ERP 回执")).get("data"));

        assertEquals("COMPLETED", hit.get("status"),
                "配到的那条应当已经跑完，实际=" + hit.get("status"));
        assertEquals(pidB, hit.get("processInstanceId"),
                "必须配到 B 单，不能配到 A 单 —— 配错单据是这类端点最贵的故障");

        // A 单必须一动不动：它在 history 里查不到（只有已办结的才在）
        assertTrue(asList(asMap(getOk("/api/wf/history/processes?businessKey=" + tag + "-A")
                .get("data")).get("records")).isEmpty(),
                "A 单不该被这次关联带走 —— 只配了 B 的单号");
        assertEquals(1, asList(asMap(getOk("/api/wf/history/processes?businessKey=" + tag + "-B")
                .get("data")).get("records")).size(), "B 单应当已办结");

        assertEquals("ACTIVE", instanceStatus(pidA), "A 单必须仍在 ACTIVE");
    }

    @Test
    @DisplayName("消息关联端点：配不上与没人在等分开报，且都是 400")
    void messageCorrelateFailuresAreDistinguishable() throws Exception {
        String tag = "WEB-CORF-" + System.nanoTime();
        postOk("/api/wf/definitions/deploy", body("key", "webCorrelateFail",
                "xml", CORRELATE_RECEIVE_BPMN));
        Map<String, Object> vars = new HashMap<>();
        vars.put("orderNo", tag + "-1");
        postOk("/api/approval-center/processes/start",
                body("definitionKey", "webCorrelateFail", "businessKey", tag + "-1",
                        "userId", "corf-alice", "variables", vars));

        Map<String, Object> wrong = new HashMap<>();
        wrong.put("orderNo", tag + "-does-not-exist");
        ResponseEntity<String> filtered = exchange(HttpMethod.POST,
                "/api/wf/process/message/correlate",
                body("messageName", "erpPaid", "variables", wrong, "userId", "erp"));
        assertEquals(HttpStatus.BAD_REQUEST, filtered.getStatusCode(),
                "配不上必须是 400，让 ERP 知道这条回执没送达，而不是 500 让它无限重试");
        assertTrue(filtered.getBody().contains("没有一条满足关联条件"),
                "有人在等但配不上，报错要这么说，否则调用方会去查「为什么没人等」: "
                        + filtered.getBody());

        ResponseEntity<String> none = exchange(HttpMethod.POST,
                "/api/wf/process/message/correlate",
                body("messageName", "nobodyWaitsThis", "userId", "erp"));
        assertEquals(HttpStatus.BAD_REQUEST, none.getStatusCode());
        assertTrue(none.getBody().contains("没有任何流程在等"),
                "没人等是另一种故障，必须与「配不上」分开说: " + none.getBody());

        ResponseEntity<String> blank = exchange(HttpMethod.POST,
                "/api/wf/process/message/correlate",
                body("messageName", "   ", "userId", "erp"));
        assertEquals(HttpStatus.BAD_REQUEST, blank.getStatusCode());
        assertTrue(blank.getBody().contains("消息名不能为空"),
                "消息名是唯一的必填项，漏填要直接点名: " + blank.getBody());
    }

    @Test
    @DisplayName("消息关联端点：执行级变量能把同一个实例里的两条同名等待分开")
    void messageCorrelateByLocalVariablesOverHttp() throws Exception {
        String tag = "WEB-CORL-" + System.nanoTime();
        postOk("/api/wf/definitions/deploy", body("key", "webCorrelateLocal",
                "xml", CORRELATE_PARALLEL_BPMN));
        String pid = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webCorrelateLocal", "businessKey", tag,
                        "userId", "corl-alice")).get("data");

        // 前置条件：一个实例里有两条同名等待，不加条件必然报歧义
        List<Map<String, Object>> waiting = openTasksOf(pid);
        assertEquals(2, waiting.size(), "并行两条分支都该在等消息，实际 " + waiting.size());
        ResponseEntity<String> bare = exchange(HttpMethod.POST,
                "/api/wf/process/message/correlate",
                body("messageName", "channelDone", "userId", "ops"));
        assertEquals(HttpStatus.BAD_REQUEST, bare.getStatusCode(),
                "不加条件时同名等待分不开，必须报错而不是挑一条");

        // 给 A 通道的分支挂一个局部变量：只有执行级变量能把两条分开
        Map<String, Object> branchVars = new HashMap<>();
        branchVars.put("channel", "A");
        postOk("/api/wf/process/branch-variables",
                body("taskId", waiting.get(0).get("id"), "values", branchVars,
                        "userId", "ops"));

        Map<String, Object> local = new HashMap<>();
        local.put("channel", "A");
        Map<String, Object> hit = asMap(postOk("/api/wf/process/message/correlate",
                body("messageName", "channelDone", "localVariables", local,
                        "userId", "ops")).get("data"));
        assertEquals(pid, hit.get("processInstanceId"));

        List<Map<String, Object>> left = openTasksOf(pid);
        assertEquals(1, left.size(), "只该叫醒被命中的那条分支，实际剩 " + left.size());
        assertEquals("waitB", left.get(0).get("definitionId"),
                "剩的应当是没配上的那条分支，实际=" + left.get(0).get("definitionId"));
    }

    @Test
    @DisplayName("消息关联端点：按业务键配")
    void messageCorrelateByBusinessKeyOverHttp() throws Exception {
        String tag = "WEB-CORBK-" + System.nanoTime();
        postOk("/api/wf/definitions/deploy", body("key", "webCorrelateBk",
                "xml", CORRELATE_RECEIVE_BPMN));
        String pidA = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webCorrelateBk", "businessKey", tag + "-A",
                        "userId", "bk-alice")).get("data");
        postOk("/api/approval-center/processes/start",
                body("definitionKey", "webCorrelateBk", "businessKey", tag + "-B",
                        "userId", "bk-bob")).get("data");

        Map<String, Object> hit = asMap(postOk("/api/wf/process/message/correlate",
                body("messageName", "erpPaid", "businessKey", tag + "-B",
                        "userId", "erp")).get("data"));
        assertEquals("COMPLETED", hit.get("status"), "按业务键配的那条应当已跑完");
        assertEquals("ACTIVE", instanceStatus(pidA), "A 单必须仍在 ACTIVE");
    }

    @Test
    @DisplayName("消息关联端点：按流程定义 key 配 —— 两个定义等同一个消息名时")
    void messageCorrelateByDefinitionKeyOverHttp() throws Exception {
        String tag = "WEB-CORDK-" + System.nanoTime();
        postOk("/api/wf/definitions/deploy", body("key", "webCorrelateDk",
                "xml", CORRELATE_RECEIVE_BPMN));
        // 只差流程 id 的孪生定义，消息名刻意相同：
        // 只留一条消息名时，发消息的服务根本分不清该叫醒哪套流程
        String twinXml = CORRELATE_RECEIVE_BPMN.replace("webCorrelateProcess",
                "webCorrelateTwinProcess");
        postOk("/api/wf/definitions/deploy", body("key", "webCorrelateDkTwin",
                "xml", twinXml));
        postOk("/api/approval-center/processes/start",
                body("definitionKey", "webCorrelateDk", "businessKey", tag + "-base",
                        "userId", "dk-base"));
        postOk("/api/approval-center/processes/start",
                body("definitionKey", "webCorrelateDkTwin", "businessKey", tag + "-twin",
                        "userId", "dk-twin"));

        Map<String, Object> hit = asMap(postOk("/api/wf/process/message/correlate",
                body("messageName", "erpPaid", "definitionKey", "webCorrelateDkTwin",
                        "userId", "erp")).get("data"));
        assertEquals("webCorrelateDkTwin", hit.get("definitionKey"),
                "必须配到孪生定义那条流程上");
    }

    @Test
    @DisplayName("消息关联端点：限定流程实例 id 可解歧义")
    void messageCorrelateScopedByProcessInstanceIdOverHttp() throws Exception {
        String tag = "WEB-CORPID-" + System.nanoTime();
        postOk("/api/wf/definitions/deploy", body("key", "webCorrelatePid",
                "xml", CORRELATE_RECEIVE_BPMN));
        String pidA = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webCorrelatePid", "businessKey", tag + "-A",
                        "userId", "pid-alice")).get("data");
        String pidB = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webCorrelatePid", "businessKey", tag + "-B",
                        "userId", "pid-bob")).get("data");

        ResponseEntity<String> ambiguous = exchange(HttpMethod.POST,
                "/api/wf/process/message/correlate",
                body("messageName", "erpPaid", "userId", "erp"));
        assertEquals(HttpStatus.BAD_REQUEST, ambiguous.getStatusCode(),
                "两条同名等待不给限定时必须报歧义，不许挑一条");

        Map<String, Object> hit = asMap(postOk("/api/wf/process/message/correlate",
                body("messageName", "erpPaid", "processInstanceId", pidA,
                        "userId", "erp")).get("data"));
        assertEquals(pidA, hit.get("processInstanceId"));
        assertEquals("ACTIVE", instanceStatus(pidB), "B 单必须仍在 ACTIVE");
    }

    /**
     * 终止结束事件走真实 HTTP（第 36 轮）。
     *
     * <p>它盯的是<b>接线</b>：终止的清理逻辑挂在 {@code WfRuntimeService#finishTransaction} 上，
     * 而这条收口有十来条推进路径都会经过。漏掉任何一条的症状是
     * 「核心单测全绿、但从 HTTP 走进来时另一条分支的待办还挂着」。
     *
     * <p>它还钉住一件在 core 判据里看不见的事：<b>跨 HTTP 之后实例状态是 COMPLETED</b>，
     * 不是 ACTIVE 停在半路。
     */
    @Test
    @DisplayName("terminateEndEvent：部署 → 启动 → 办结一条 → 另一条分支被终止收掉")
    void terminateEndEventOverHttp() throws Exception {
        String key = "term-" + System.nanoTime();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"" + key + "\" name=\"终止\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"ts1\"/>\n"
                + "    <parallelGateway id=\"tpga\"/>\n"
                + "    <userTask id=\"twin\" name=\"甲\" zifang:assignee=\"term-alice\"/>\n"
                + "    <userTask id=\"tslow\" name=\"乙\" zifang:assignee=\"term-bob\"/>\n"
                + "    <endEvent id=\"teWin\"/>\n"
                + "    <terminateEndEvent id=\"teTerm\"/>\n"
                + "    <sequenceFlow id=\"tf1\" sourceRef=\"ts1\" targetRef=\"tpga\"/>\n"
                + "    <sequenceFlow id=\"tf2\" sourceRef=\"tpga\" targetRef=\"twin\"/>\n"
                + "    <sequenceFlow id=\"tf3\" sourceRef=\"tpga\" targetRef=\"tslow\"/>\n"
                + "    <sequenceFlow id=\"tf4\" sourceRef=\"twin\" targetRef=\"teWin\"/>\n"
                + "    <sequenceFlow id=\"tf5\" sourceRef=\"tslow\" targetRef=\"teTerm\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        // 部署期就该放行：此前这个元素不在解析表里，部署期会直接报「不支持」
        postOk("/api/wf/definitions/deploy", body("key", key, "xml", xml));

        String pid = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", key, "businessKey", "TERM-BUS-1", "userId", "term-alice"))
                .get("data");
        assertEquals(2, openTasksOf(pid).size(), "两条并行分支各有一个待办");

        String slowTaskId = null;
        for (Map<String, Object> task : openTasksOf(pid)) {
            if ("tslow".equals(task.get("definitionId"))) {
                slowTaskId = (String) task.get("id");
            }
        }
        assertNotNull(slowTaskId, "找不到乙那条分支的待办: " + openTasksOf(pid));

        postOk("/api/approval-center/tasks/complete",
                body("taskId", slowTaskId, "userId", "term-bob", "comment", "我先办完了"));

        assertTrue(openTasksOf(pid).isEmpty(),
                "乙走到终止结束事件时，甲的待办必须一起作废。实际还开着: " + openTasksOf(pid));
        assertEquals("COMPLETED", instanceStatus(pid),
                "终止是作用域的正常完成 —— 停在 ACTIVE 就是一个被终止却还活着的实例，"
                        + "没有待办也没有 job，永远不会再推进");
    }

    private List<Map<String, Object>> openTasksOf(String processInstanceId) throws Exception {        return asList(asMap(getOk("/api/wf/process/overview?processInstanceId=" + processInstanceId)
                .get("data")).get("openTasks"));
    }

    private String instanceStatus(String processInstanceId) throws Exception {
        return String.valueOf(asMap(asMap(getOk(
                "/api/wf/process/overview?processInstanceId=" + processInstanceId)
                .get("data")).get("process")).get("status"));
    }

    /** 一个 receiveTask：ERP 付款回执。 */
    private static final String CORRELATE_RECEIVE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"webCorrelateProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"wcs\"/>\n"
            + "    <receiveTask id=\"wcWait\" name=\"等 ERP 回执\" zifang:messageName=\"erpPaid\"/>\n"
            + "    <endEvent id=\"wce\"/>\n"
            + "    <sequenceFlow id=\"wcf1\" sourceRef=\"wcs\" targetRef=\"wcWait\"/>\n"
            + "    <sequenceFlow id=\"wcf2\" sourceRef=\"wcWait\" targetRef=\"wce\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /**
     * 并行两条分支等同一个消息名 —— 只有执行级变量能把它们分开。
     *
     * <p>两条候选同属一个实例，流程级变量对它们一视同仁；
     * 任何基于流程变量的实现都会得到「两条都匹配」进而报歧义。
     */
    private static final String CORRELATE_PARALLEL_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"webCorrelateParallelProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"wps\"/>\n"
            + "    <parallelGateway id=\"wpg\"/>\n"
            + "    <receiveTask id=\"waitA\" name=\"A 通道\" zifang:messageName=\"channelDone\"/>\n"
            + "    <receiveTask id=\"waitB\" name=\"B 通道\" zifang:messageName=\"channelDone\"/>\n"
            + "    <endEvent id=\"wpe1\"/>\n"
            + "    <endEvent id=\"wpe2\"/>\n"
            + "    <sequenceFlow id=\"wpf1\" sourceRef=\"wps\" targetRef=\"wpg\"/>\n"
            + "    <sequenceFlow id=\"wpf2\" sourceRef=\"wpg\" targetRef=\"waitA\"/>\n"
            + "    <sequenceFlow id=\"wpf3\" sourceRef=\"wpg\" targetRef=\"waitB\"/>\n"
            + "    <sequenceFlow id=\"wpf4\" sourceRef=\"waitA\" targetRef=\"wpe1\"/>\n"
            + "    <sequenceFlow id=\"wpf5\" sourceRef=\"waitB\" targetRef=\"wpe2\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

// ==================== 活动实例树端点 ====================

    @Test
    @DisplayName("活动实例树端点：并行分支是父子链，且标出「有并发」")
    void activityInstanceTreeOverHttp() throws Exception {
        String tag = "WEB-TREE-" + System.nanoTime();
        postOk("/api/wf/definitions/deploy", body("key", "webTree",
                "xml", TREE_PARALLEL_BPMN));
        String pid = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webTree", "businessKey", tag, "userId", "tree-alice"))
                .get("data");

        Map<String, Object> root = asMap(getOk(
                "/api/wf/process/activity-instance?processInstanceId=" + pid).get("data"));

        assertEquals("process:" + pid, root.get("id"));
        assertEquals("process", root.get("activityType"));
        assertEquals(Boolean.TRUE, root.get("concurrent"),
                "两条并行分支都没结束，根上必须标出有并发 —— "
                        + "按「同一父下有几个兄弟」去判的话这一位永远是 false");

        List<Map<String, Object>> top = asList(root.get("childActivityInstances"));
        assertEquals(1, top.size(),
                "并行网关的第一条出线留在父 token 上，根下只有一条，第二条是它的子 token");
        Map<String, Object> parent = top.get(0);
        assertEquals("wta", parent.get("activityId"));
        assertEquals(root.get("id"), parent.get("parentActivityInstanceId"),
                "父指针与 children 必须双向自洽，调用方才知道该信哪一个");

        List<Map<String, Object>> children = asList(parent.get("childActivityInstances"));
        assertEquals(1, children.size());
        Map<String, Object> child = children.get(0);
        assertEquals("wtb", child.get("activityId"));
        assertEquals(parent.get("id"), child.get("parentActivityInstanceId"));
        assertEquals(Boolean.TRUE, child.get("concurrent"));

        // fork 出来的子 token 没走过起始节点，它的步骤表为空
        assertTrue(asList(child.get("childTransitionInstances")).isEmpty(),
                "子 token 从未经过起始节点，步骤表必须为空");
        List<Map<String, Object>> parentSteps = asList(parent.get("childTransitionInstances"));
        assertEquals(1, parentSteps.size());
        assertEquals("wts", parentSteps.get(0).get("activityId"));
    }

    @Test
    @DisplayName("活动实例树并进总览：一次请求就能同时拿到轨迹与并发结构")
    void activityInstanceMergedIntoOverview() throws Exception {
        String tag = "WEB-TREE-OV-" + System.nanoTime();
        postOk("/api/wf/definitions/deploy", body("key", "webTreeOverview",
                "xml", TREE_PARALLEL_BPMN));
        String pid = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webTreeOverview", "businessKey", tag,
                        "userId", "tree-ov")).get("data");

        Map<String, Object> overview = asMap(getOk(
                "/api/wf/process/overview?processInstanceId=" + pid).get("data"));
        assertNotNull(overview.get("trail"), "轨迹照旧要有");
        assertNotNull(overview.get("activityTree"),
                "并发结构要并进总览：前端详情页本来就要拿轨迹与订阅，"
                        + "再单发一次只为拿「并行分支在哪」，中间状态就对不上了");
        Map<String, Object> tree = asMap(overview.get("activityTree"));
        assertEquals("process:" + pid, tree.get("id"));
        assertEquals(Boolean.TRUE, tree.get("concurrent"));

        // 不存在的实例：加树之前的既有行为是「没有 process / trail / openTasks，
        // 但 subscriptions 与 incidents 两项在 controller 里是无条件塞的，所以并**不**为空」。
        // 钉住它，是为了保证多挂一棵树没有顺手改掉老接口的响应形状
        Map<String, Object> missingOverview = asMap(getOk(
                "/api/wf/process/overview?processInstanceId=no-such-tree").get("data"));
        assertNull(missingOverview.get("process"),
                "不存在的实例不该有 process");
        assertNull(missingOverview.get("activityTree"),
                "实例不存在就不该构树：getActivityInstance 会抛「流程实例不存在」，"
                        + "而 overview 对这条路径的既有语义是「什么都没查着」");
        assertTrue(missingOverview.containsKey("subscriptions")
                        && missingOverview.containsKey("incidents"),
                "这两项在加树之前就是无条件塞的，响应形状不能因为加树而改变。实际键集="
                        + missingOverview.keySet());

        // 单独的端点则必须报错并点名历史清理 —— 空树会让人分不清三种情况
        ResponseEntity<String> missing = exchange(HttpMethod.GET,
                "/api/wf/process/activity-instance?processInstanceId=no-such-tree", null);
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        assertTrue(missing.getBody().contains("流程实例不存在"),
                "空树会让调用方分不清「没跑起来」「被清过历史」「真的没有分支」: "
                        + missing.getBody());
    }

    /** 并行两支并行汇合，活动实例树用。 */
    private static final String TREE_PARALLEL_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"webTreeProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"wts\"/>\n"
            + "    <parallelGateway id=\"wtpg\"/>\n"
            + "    <userTask id=\"wta\" name=\"甲\" zifang:assignee=\"tree-a\"/>\n"
            + "    <userTask id=\"wtb\" name=\"乙\" zifang:assignee=\"tree-b\"/>\n"
            + "    <parallelGateway id=\"wtjg\"/>\n"
            + "    <endEvent id=\"wte\"/>\n"
            + "    <sequenceFlow id=\"wtf1\" sourceRef=\"wts\" targetRef=\"wtpg\"/>\n"
            + "    <sequenceFlow id=\"wtf2\" sourceRef=\"wtpg\" targetRef=\"wta\"/>\n"
            + "    <sequenceFlow id=\"wtf3\" sourceRef=\"wtpg\" targetRef=\"wtb\"/>\n"
            + "    <sequenceFlow id=\"wtf4\" sourceRef=\"wta\" targetRef=\"wtjg\"/>\n"
            + "    <sequenceFlow id=\"wtf5\" sourceRef=\"wtb\" targetRef=\"wtjg\"/>\n"
            + "    <sequenceFlow id=\"wtf6\" sourceRef=\"wtjg\" targetRef=\"wte\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

// ==================== 引擎自省端点 ====================

    @Test
    @DisplayName("自省端点：属性如实说出版本与存储形态，且不带任何凭据")
    void managementPropertiesOverHttp() throws Exception {
        Map<String, Object> properties = asMap(getOk("/api/wf/management/properties").get("data"));
        assertEquals("z-wf", properties.get("engine"));
        assertNotNull(properties.get("version"));
        assertNotNull(properties.get("schemaVersion"));
        // 共享 H2 ⇒ 一定是 jdbc。若这里报 in-memory，说明它根本没问持久层就答了
        assertEquals("jdbc", properties.get("persistence"),
                "连的是 H2，却报成不是 jdbc —— 属性是写死的而不是问出来的");

        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String key = entry.getKey().toLowerCase();
            String value = String.valueOf(entry.getValue()).toLowerCase();
            assertFalse(key.contains("password") || key.contains("secret")
                            || value.contains("jdbc:") || value.contains("password"),
                    "自省接口常被监控无差别暴露，不能顺带把连接串/凭据摊出去: "
                            + entry.getKey() + "=" + entry.getValue());
        }
    }

    @Test
    @DisplayName("自省端点：存储清单带类型与行数，未知名字报 400 而不是 0")
    void managementTablesOverHttp() throws Exception {
        List<Map<String, Object>> tables = asList(getOk("/api/wf/management/tables").get("data"));
        assertFalse(tables.isEmpty(), "清单不该是空的");
        for (Map<String, Object> table : tables) {
            assertEquals("table", table.get("kind"),
                    "连的是 H2，底下就是表；标成 collection 会让人跑去别处找表: " + table);
            assertNotNull(table.get("rowCount"));
        }
        String taskTable = String.valueOf(tables.get(0).get("name"));
        ResponseEntity<String> count = exchange(HttpMethod.GET,
                "/api/wf/management/tables/count?name=" + taskTable, null);
        assertEquals(HttpStatus.OK, count.getStatusCode());

        // 拼错名字必须是 400。返回 0 会把排障方向从「我拼错了」
        // 带偏到「谁把它清空了」—— 这两种情况的处置完全相反
        ResponseEntity<String> typo = exchange(HttpMethod.GET,
                "/api/wf/management/tables/count?name=ZWF_PROCES", null);
        assertEquals(HttpStatus.BAD_REQUEST, typo.getStatusCode(),
                "拼错表名报 400，而不是安静地返回 0");
        assertTrue(typo.getBody().contains("ZWF_PROCESS"),
                "报错要列出合法的名字，否则调用方不知道自己还能问什么: " + typo.getBody());
    }

// ==================== 令牌查询与实例改名 ====================

    /** 一条串行流程：起步就停在 userTask 上，因而一定有一条活跃令牌。 */
    private static final String NAME_SEQ_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"webNameSeq\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"wns\"/>\n"
            + "    <userTask id=\"wnt1\" name=\"一级\" zifang:assignee=\"alice\"/>\n"
            + "    <endEvent id=\"wne\"/>\n"
            + "    <sequenceFlow id=\"wnf1\" sourceRef=\"wns\" targetRef=\"wnt1\"/>\n"
            + "    <sequenceFlow id=\"wnf2\" sourceRef=\"wnt1\" targetRef=\"wne\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    @Test
    @DisplayName("令牌端点：按节点查得到，且 count 与列表口径一致")
    void executionsOverHttp() throws Exception {
        postOk("/api/wf/definitions/deploy", body("key", "webExec", "xml", NAME_SEQ_BPMN));
        String pid = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webExec", "businessKey", "EXEC-1",
                        "userId", "web-exec-alice")).get("data");

        // 关键一问：**还不知道单子 id，只知道节点** 时能不能查
        Map<String, Object> byNode = asMap(getOk("/api/wf/executions?activityId=wnt1").get("data"));
        List<Map<String, Object>> records = asList(byNode.get("records"));
        assertFalse(records.isEmpty(), "按节点查不到 —— 排障的第一问就答不了");
        assertEquals(pid, String.valueOf(records.get(0).get("processInstanceId")));
        assertEquals("wnt1", records.get(0).get("activityId"));
        // count 必须与列表同口径（都算上变量过滤），否则列表 1 条 count 却说 5 条
        assertEquals(records.size(), ((Number) byNode.get("total")).intValue(),
                "列表条数与 total 对不上：count 走了另一条路（漏掉过滤）");

        // 按实例 + 状态筛。注意它停在 userTask 上 ⇒ 令牌是 **WAITING**（等人工），
        // 不是 ACTIVE。这两个状态混为一谈的话，「有没有流程在等人工」就答错了 ——
        // 而那正是订阅查询答不了的那一半
        Map<String, Object> waiting = asMap(getOk(
                "/api/wf/executions?processInstanceId=" + pid + "&state=WAITING").get("data"));
        assertEquals(1, asList(waiting.get("records")).size());
        assertEquals(0, asList(asMap(getOk(
                        "/api/wf/executions?processInstanceId=" + pid + "&state=ACTIVE")
                .get("data")).get("records")).size(),
                "它在等人工办，却被算成了 ACTIVE");

        // 未知状态必须 400，并列出合法值 —— 静默忽略一个拼错的状态，
        // 调用方会拿到一份"看起来筛过了、其实没筛"的结果
        ResponseEntity<String> typo = exchange(HttpMethod.GET,
                "/api/wf/executions?state=ACTIE", null);
        assertEquals(HttpStatus.BAD_REQUEST, typo.getStatusCode(),
                "拼错状态报 400，而不是当没传");
        assertTrue(typo.getBody().contains("ACTIVE"),
                "报错要列出合法的状态值: " + typo.getBody());
    }

    @Test
    @DisplayName("改名端点：改名后看得到，空白串报 400，实例不存在报 400")
    void setProcessInstanceNameOverHttp() throws Exception {
        postOk("/api/wf/definitions/deploy", body("key", "webRename", "xml", NAME_SEQ_BPMN));
        String pid = (String) postOk("/api/approval-center/processes/start",
                body("definitionKey", "webRename", "businessKey", "RN-1",
                        "userId", "web-rename-alice")).get("data");

        Map<String, Object> renamed = asMap(postOk("/api/wf/process/name",
                body("processInstanceId", pid, "name", "张三的请假申请")).get("data"));
        assertEquals("张三的请假申请", renamed.get("name"),
                "改名端点返回的实例里必须带 name —— 返回里没有，前端改完只能自己再查一次");
        assertNotNull(renamed.get("businessKey"),
                "businessKey 是业务方的单号，与 name 是两回事，两个都要在");

        // 列表页拿到的实例也要带 name，否则列表标题永远是空的
        Map<String, Object> overview = asMap(getOk(
                "/api/wf/process/overview?processInstanceId=" + pid).get("data"));
        assertNotNull(overview);

        // 空白串报 400，并说清「清空请传 null」
        ResponseEntity<String> blank = exchange(HttpMethod.POST, "/api/wf/process/name",
                body("processInstanceId", pid, "name", "   "));
        assertEquals(HttpStatus.BAD_REQUEST, blank.getStatusCode(),
                "空白串被当成合法名字存了进去 —— 空标题与没起名字在界面上一样、语义却不同");
        assertTrue(blank.getBody().contains("清空"), "报错要说清怎么清空: " + blank.getBody());

        // 实例不存在报 400 并点名，不能安静地什么都不做
        ResponseEntity<String> missing = exchange(HttpMethod.POST, "/api/wf/process/name",
                body("processInstanceId", "proc-压根不存在", "name", "改名"));
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        assertTrue(missing.getBody().contains("proc-压根不存在"),
                "报错要点名是哪个实例: " + missing.getBody());
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
