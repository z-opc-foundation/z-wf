package com.zifang.z.wf.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 入站鉴权的<b>web 边界</b>测试 —— 把 TASK-20261022-024 的验收口径直接写成断言。
 *
 * <p>验收口径（卡 §4 第 4 项）：
 * <ol>
 *   <li>17 条读路径匿名 GET ⇒ <b>匿名可读数 = 0</b>；</li>
 *   <li>20 条写路径的 OPTIONS ⇒ <b>Allow 不得再含 POST/PUT/DELETE</b>
 *       （或该方法需带凭据才注册）；</li>
 *   <li>业务信封判据：响应必须是 <b>JSON</b>，<b>不是</b> SPA 兜底 HTML
 *       （z-wf 的 {@code WfWebMvcConfig} 会对未知路径返 index.html ⇒ 极易假绿）。</li>
 * </ol>
 *
 * <p>本测试是<b>仓内</b>口径（跑在 MockMvc 上）；线上口径（真节点 × 3 次）由
 * {@code evidence/TASK-20261022-024/verify_zwf_auth.py} 在发版后执行。
 *
 * @author zifang
 */
@SpringBootTest(properties = {
        "z.wf.auth.enabled=true",
        "z.wf.auth.tokens=probe:probe-secret-123"
})
@ActiveProfiles("h2-test")
@AutoConfigureMockMvc
class WfAuthWebBoundaryTest {

    private static final String TOKEN = "probe-secret-123";

    /** 卡 §2.2 的 17 条读路径（源码真实路径，逐条拼自各类级 @RequestMapping）。 */
    private static final List<String> READ_PATHS = Arrays.asList(
            "/api/wf/health",
            "/api/wf/definitions",
            "/api/wf/process",
            "/api/wf/executions",
            "/api/wf/jobs",
            "/api/wf/history",
            "/api/wf/incidents",
            "/api/wf/management",
            "/api/wf/subscriptions",
            "/api/wf/group",
            "/api/wf/filters",
            "/api/wf/batches",
            "/api/wf/decisions",
            "/api/wf/task",
            "/api/wf/variable-instances",
            "/api/wf/external-tasks",
            "/api/approval-center");

    /** 卡 §2.3 的 20 条写路径（只探注册性，不下发写请求）。 */
    private static final List<String> WRITE_PATHS = Arrays.asList(
            "/api/wf/process/suspend",
            "/api/wf/process/terminate",
            "/api/wf/process/message",
            "/api/wf/process/signal",
            "/api/wf/process/move",
            "/api/wf/process/advance",
            "/api/wf/process/variables",
            "/api/wf/task/claim",
            "/api/wf/task/force-complete",
            "/api/wf/task/transfer",
            "/api/wf/task/withdraw",
            "/api/wf/task/jump",
            "/api/wf/task/management",
            "/api/wf/jobs/probe-job-id/trigger",
            "/api/wf/decisions/deploy",
            "/api/approval-center/processes/start",
            "/api/approval-center/tasks/complete",
            "/api/wf/batches",
            "/api/wf/filters",
            "/api/approval-center/processes");

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("① 17 条读路径匿名 GET ⇒ 匿名可读数 = 0（每条都 401，且响应是 JSON 不是 HTML）")
    void anonymousReadIsImpossible() throws Exception {
        int readable = 0;
        for (String path : READ_PATHS) {
            MvcResult r = mockMvc.perform(get(path)).andReturn();
            int status = r.getResponse().getStatus();
            String body = r.getResponse().getContentAsString();
            if (status == 200) {
                readable++;
            }
            assertEquals(401, status, "匿名读 " + path + " 必须 401，实际 " + status
                    + " body=" + body);
            assertTrue(body.contains("\"success\":false"), "匿名读 " + path + " 必须回 JSON 信封，实际=" + body);
            assertFalse(body.contains("<html"), "匿名读 " + path + " 不能回 SPA 兜底 HTML，实际=" + body);
        }
        assertEquals(0, readable, "匿名可读数必须为 0");
    }

    @Test
    @DisplayName("② 20 条写路径的 OPTIONS（匿名）⇒ Allow 不得含 POST/PUT/DELETE")
    void anonymousOptionsDoesNotAdvertiseWriteMethods() throws Exception {
        for (String path : WRITE_PATHS) {
            MvcResult r = mockMvc.perform(options(path)).andReturn();
            int status = r.getResponse().getStatus();
            String allow = r.getResponse().getHeader("Allow");
            assertEquals(401, status, "匿名 OPTIONS " + path + " 必须 401，实际 " + status);
            if (allow != null) {
                assertFalse(allow.contains("POST"), "匿名 OPTIONS " + path + " 的 Allow 不得含 POST，实际=" + allow);
                assertFalse(allow.contains("PUT"), "匿名 OPTIONS " + path + " 的 Allow 不得含 PUT，实际=" + allow);
                assertFalse(allow.contains("DELETE"), "匿名 OPTIONS " + path + " 的 Allow 不得含 DELETE，实际=" + allow);
            }
        }
    }

    @Test
    @DisplayName("② 附证：带凭据时写端点仍然注册（Allow 含写方法）⇒ 端点没被误伤，只是要凭据")
    void credentialedOptionsStillAdvertisesWriteMethods() throws Exception {
        MvcResult r = mockMvc.perform(options("/api/wf/process/suspend")
                .header("Authorization", "Bearer " + TOKEN)).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        String allow = r.getResponse().getHeader("Allow");
        assertTrue(allow != null && allow.contains("POST"),
                "带凭据时 Allow 应含 POST，实际=" + allow);
    }

    @Test
    @DisplayName("① 附证：带凭据时读端点可读（不是把接口焊死了）")
    void credentialedReadWorks() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/wf/definitions")
                .header("Authorization", "Bearer " + TOKEN)).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        assertTrue(r.getResponse().getContentAsString().contains("\"success\":true"),
                "带凭据应拿到业务信封，实际=" + r.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("③ 探针端点免鉴权且回 JSON（k3s 探针带不了自定义头，只能单开一条通道）")
    void probeEndpointIsExemptAndJson() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/wf/health/probe")).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        String body = r.getResponse().getContentAsString();
        assertTrue(body.contains("\"success\":true"), "探针端点必须回 JSON 信封，实际=" + body);
        assertFalse(body.contains("<html"), "探针端点不能回 HTML");
    }
}
