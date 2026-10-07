package com.zifang.z.wf.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.service.WfRepositoryService;

/**
 * 历史级别配置的端到端验证（第 42 轮），跑在 {@code history-level=activity} 上。
 *
 * <p><b>这个类存在的理由是 core 与 web 都测不到的那一段</b>：
 * 配置项从 {@code z.wf.history-level} 进 {@link com.zifang.z.wf.core.config.WfProperties}，
 * 经 {@code WfAutoConfiguration} 解析后**注入到三个不同的 bean**（runtime / variable /
 * management）。这三处接线任何一处漏掉，现象都是
 * 「单测全绿、真跑起来该记的没记」——
 * 而 {@code activity} 恰好是<b>默认档之下的第一档</b>，漏接的表现是
 * 「配置写着 activity、实际行为还是 full」，不多不少，正好是本特性要解决的那类问题。
 *
 * <p>所以这里刻意**不复用** {@code WfWebApiTest} 的默认上下文（那是 full），
 * 另起一个带 {@code properties} 的上下文 ——
 * Spring 会为不同配置各建一个上下文，这正是我们要的隔离。
 *
 * @author zifang
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "z.wf.history-level=activity")
@ActiveProfiles("h2-test")
class WfHistoryLevelConfigTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private WfRepositoryService repositoryService;

    @Autowired
    private WfPersistence persistence;

    private static final String APPROVAL_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"histLevelProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"hs\"/>\n"
            + "    <userTask id=\"hlApprove\" name=\"历史级别审批\""
            + " zifang:assignee=\"hl-leader\"/>\n"
            + "    <endEvent id=\"he\"/>\n"
            + "    <sequenceFlow id=\"hf1\" sourceRef=\"hs\" targetRef=\"hlApprove\"/>\n"
            + "    <sequenceFlow id=\"hf2\" sourceRef=\"hlApprove\" targetRef=\"he\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private String startOne() {
        WfDefinition definition = repositoryService.deploy(new WfXmlParser().parse(APPROVAL_BPMN));
        String pid = (String) exchange(HttpMethod.POST, "/api/approval-center/processes/start",
                body("definitionKey", "histLevelProcess",
                        "businessKey", "HL-" + System.nanoTime(),
                        "userId", "hl-alice")).get("data");
        assertNotNull(pid, "应返回流程实例 id");
        return pid;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchange(HttpMethod method, String url, Object request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.exchange(url, method,
                new HttpEntity<Object>(request, headers), Map.class);
        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "调用应当成功，实际 " + response.getStatusCode() + "：" + response.getBody());
        Map<String, Object> body = response.getBody();
        assertNotNull(body, "响应体不能为空");
        return body;
    }

    private Map<String, Object> get(String url) {
        return exchange(HttpMethod.GET, url, null);
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> map = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private static List<Map<String, Object>> asList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    // ==================== 配置真的接上了吗 ====================

    @Test
    @DisplayName("z.wf.history-level=activity ⇒ 自省端点报的就是 activity")
    void configuredLevelReachesTheManagementEndpoint() {
        Map<String, Object> properties = asMap(
                get("/api/wf/management/properties").get("data"));
        assertEquals("activity", properties.get("historyLevel"),
                "**自省接口报的不是实际生效的那一档，比不报还坏** —— "
                        + "运维看到轨迹不对，第一个查的就是这里");
        assertNotNull(properties.get("historyLevelDetail"),
                "只给档位名的话，看到该有的没有仍然不知道为什么");
    }

    @Test
    @DisplayName("activity 档：活动轨迹照常落库（真 JDBC 查得到）")
    void activityTrailIsStillWritten() {
        String pid = startOne();
        List<Map<String, Object>> open = asList(asMap(
                get("/api/wf/process/overview?processInstanceId=" + pid).get("data"))
                .get("openTasks"));
        assertFalse(open.isEmpty(), "流程应当停在审批待办上");

        exchange(HttpMethod.POST, "/api/approval-center/tasks/complete",
                body("taskId", open.get(0).get("id"), "userId", "hl-leader",
                        "comment", "同意"));

        List<Map<String, Object>> trail = asList(
                get("/api/wf/process/trail?processInstanceId=" + pid).get("data"));
        assertFalse(trail.isEmpty(),
                "activity 档的**全部内容就是这条轨迹**，它没了这个档位等于什么都没开");
        boolean sawApprove = false;
        for (Map<String, Object> step : trail) {
            if ("hlApprove".equals(step.get("activityId"))) {
                sawApprove = true;
            }
        }
        assertTrue(sawApprove, "轨迹里应当有走过审批那一步。实际: " + trail);
    }

    @Test
    @DisplayName("activity 档：引擎留痕一条都不写，但迁移本身照样生效")
    void engineBreadcrumbsAreOffButTheActionStillHappens() {
        String pid = startOne();

        exchange(HttpMethod.POST, "/api/wf/process/move",
                body("processInstanceId", pid, "targetActivityId", "hlApprove",
                        "userId", "hl-ops", "reason", "线上单子要人工插队"));

        assertTrue(asList(get("/api/wf/process/comments?processInstanceId=" + pid)
                        .get("data")).isEmpty(),
                "activity 档不该有任何引擎留痕。实际: "
                        + get("/api/wf/process/comments?processInstanceId=" + pid).get("data"));

        List<Map<String, Object>> open = asList(asMap(
                get("/api/wf/process/overview?processInstanceId=" + pid).get("data"))
                .get("openTasks"));
        assertFalse(open.isEmpty(),
                "**留痕被跳过 ≠ 操作没发生** —— 迁完之后那儿应当仍有一张待办。"
                        + "用 `move` 而不是 `complete` 是因为办结人工任务本来就不写评论，"
                        + "那种场景根本区分不出档位（反向验证 M11 抓到的就是这一条）");
    }

    @Test
    @DisplayName("activity 档：变量审计没有，但**变量本身照常读写**")
    void variableAuditIsOffYetVariablesStillWork() {
        String pid = startOne();

        exchange(HttpMethod.POST, "/api/wf/process/variables",
                body("processInstanceId", pid, "userId", "hl-alice",
                        "values", body("amount", 100)));

        Map<String, Object> page = asMap(get("/api/wf/history/variable-changes"
                + "?processInstanceId=" + pid).get("data"));
        assertEquals(0, ((Number) page.get("total")).intValue(),
                "activity 档不该有变量审计。实际: " + page);

        assertEquals(100, asMap(get("/api/wf/process/variables?processInstanceId=" + pid)
                        .get("data")).get("amount"),
                "**关掉审计不能连带关掉变量** —— 那是流程正在用的数据，"
                        + "两者绑在同一个开关上是最危险的一种设计");
    }

    @Test
    @DisplayName("activity 档下显式加评论 ⇒ 400，且不是静默丢")
    void explicitCommentBelowAuditIsRejectedOverHttp() {
        String pid = startOne();
        ResponseEntity<Map> response = rest.exchange(
                "/api/wf/process/comment",
                HttpMethod.POST,
                new HttpEntity<Object>(body("processInstanceId", pid,
                        "userId", "hl-alice", "content", "看一眼"),
                        jsonHeaders()),
                Map.class);
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST,
                response.getStatusCode(),
                "有人明确要求留痕时不能回一句成功却什么都没留。实际: " + response.getBody());
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    @DisplayName("配置写成非法值 ⇒ **启动失败**，不是默默按默认档跑")
    void unknownConfiguredLevelFailsFast() {
        // 走的是同一个解析器：它在 bean 创建期被调用，
        // 所以「配错了」表现为**进程起不来**，而不是运行期才发现少记了历史
        assertThrowsIllegalArgument("ful");
        assertThrowsIllegalArgument("debug");
        // 合法值（含大小写与首尾空白）必须照样起得来
        assertEquals("AUDIT", com.zifang.z.wf.core.engine.WfHistoryLevel.parse(" AUDIT ").name());
        assertEquals("FULL", com.zifang.z.wf.core.engine.WfHistoryLevel.parse("FULL ").name(),
                "**首尾空白要 trim 而不是报错** —— 配置文件与环境变量带上空白是常事，"
                        + "为这个报错等于让人去查一个根本不存在的问题");
        assertEquals("NONE", com.zifang.z.wf.core.engine.WfHistoryLevel.parse("none").name());
    }

    private void assertThrowsIllegalArgument(String bad) {
        try {
            com.zifang.z.wf.core.engine.WfHistoryLevel.parse(bad);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("history-level"),
                    "报错要点名是哪个配置项。实际: " + expected.getMessage());
            return;
        }
        throw new AssertionError("配成 \"" + bad + "\" 竟然没报错 —— "
                + "那意味着引擎默默按默认档跑，而合规审计那天才发现");
    }
}