package com.zifang.z.wf.admin.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 入站鉴权拦截器的边界单测（不启 Spring 容器，直接驱动拦截器）。
 *
 * <p>覆盖的是<b>鉴权语义本身</b>：没凭据 / 凭据无效 / 凭据有效 / 一个凭据来源都没配 /
 * 免鉴权路径 / 非受保护路径。
 *
 * @author zifang
 */
class WfAuthInterceptorTest {

    private static final String TOKEN = "probe-secret-123";

    private WfAuthProperties props() {
        WfAuthProperties p = new WfAuthProperties();
        p.setEnabled(true);
        p.setProtectedPaths(Collections.singletonList("/api/**"));
        p.setExcludePaths(Arrays.asList("/api/wf/health/probe", "/error"));
        return p;
    }

    private WfAuthInterceptor interceptor(WfAuthProperties p, WfTokenValidator... validators) {
        return new WfAuthInterceptor(p, Arrays.asList(validators));
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRequestURI(uri);
        req.setRemoteAddr("192.168.31.139");
        return req;
    }

    @Test
    @DisplayName("匿名请求受保护路径 ⇒ 401 且响应体是 JSON（不是 SPA 兜底 HTML）")
    void anonymousIsRejectedWithJson() throws Exception {
        WfAuthProperties p = props();
        MockHttpServletRequest req = request("GET", "/api/wf/definitions");
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean pass = interceptor(p, new WfStaticTokenValidator(Collections.singletonList("probe:" + TOKEN)))
                .preHandle(req, res, new Object());

        assertTrue(!pass, "匿名请求必须被拦下");
        assertEquals(401, res.getStatus());
        String body = res.getContentAsString();
        assertTrue(body.contains("\"success\":false"), "响应体必须是统一信封 JSON，实际=" + body);
        assertTrue(body.contains("\"code\":401"), "响应体必须带 401 码，实际=" + body);
        assertTrue(!body.contains("<html"), "响应体绝不能是 SPA 兜底 HTML，实际=" + body);
        assertTrue(res.getContentType().contains("application/json"), "Content-Type 必须是 JSON");
    }

    @Test
    @DisplayName("凭据无效 ⇒ 401（不是 500，也不是放行）")
    void invalidCredentialIsRejected() throws Exception {
        WfAuthProperties p = props();
        MockHttpServletRequest req = request("GET", "/api/wf/executions");
        req.addHeader("Authorization", "Bearer wrong-token");
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean pass = interceptor(p, new WfStaticTokenValidator(Collections.singletonList("probe:" + TOKEN)))
                .preHandle(req, res, new Object());

        assertTrue(!pass);
        assertEquals(401, res.getStatus());
    }

    @Test
    @DisplayName("Bearer 令牌有效 ⇒ 放行，且请求属性里带上归因主体")
    void validBearerTokenPasses() throws Exception {
        WfAuthProperties p = props();
        MockHttpServletRequest req = request("GET", "/api/wf/executions");
        req.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean pass = interceptor(p, new WfStaticTokenValidator(Collections.singletonList("probe:" + TOKEN)))
                .preHandle(req, res, new Object());

        assertTrue(pass, "有效令牌必须放行");
        Object principal = req.getAttribute(WfAuthInterceptor.PRINCIPAL_ATTR);
        assertNotNull(principal, "放行后必须留下归因主体");
        assertEquals("STATIC_TOKEN:probe", principal.toString());
    }

    @Test
    @DisplayName("裸 Authorization 头（主壳历史写法）也认")
    void bareAuthorizationHeaderAccepted() throws Exception {
        WfAuthProperties p = props();
        MockHttpServletRequest req = request("GET", "/api/wf/jobs");
        req.addHeader("Authorization", TOKEN);
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean pass = interceptor(p, new WfStaticTokenValidator(Collections.singletonList("probe:" + TOKEN)))
                .preHandle(req, res, new Object());

        assertTrue(pass, "裸令牌必须放行");
    }

    @Test
    @DisplayName("同名 Cookie（sso_token）里的凭据也认")
    void ssoCookieAccepted() throws Exception {
        WfAuthProperties p = props();
        MockHttpServletRequest req = request("GET", "/api/wf/jobs");
        req.setCookies(new jakarta.servlet.http.Cookie("sso_token", TOKEN));
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean pass = interceptor(p, new WfStaticTokenValidator(Collections.singletonList("probe:" + TOKEN)))
                .preHandle(req, res, new Object());

        assertTrue(pass, "Cookie 里的凭据必须放行");
    }

    @Test
    @DisplayName("鉴权开着但一个凭据来源都没配 ⇒ 503（配置事故，不是 401，更不是放行）")
    void noValidatorFailsClosedWith503() throws Exception {
        WfAuthProperties p = props();
        MockHttpServletRequest req = request("GET", "/api/wf/definitions");
        req.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean pass = interceptor(p).preHandle(req, res, new Object());

        assertTrue(!pass, "没有凭据来源时必须 fail-closed");
        assertEquals(503, res.getStatus());
        assertTrue(res.getContentAsString().contains("\"success\":false"));
    }

    @Test
    @DisplayName("探针路径免鉴权（kubelet 探针带不了自定义头）")
    void probePathIsExempt() throws Exception {
        WfAuthProperties p = props();
        MockHttpServletRequest req = request("GET", "/api/wf/health/probe");
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean pass = interceptor(p).preHandle(req, res, new Object());

        assertTrue(pass, "探针路径必须放行");
    }

    @Test
    @DisplayName("静态资源路径不受保护（排障时能直接打开页面）")
    void staticPathNotProtected() throws Exception {
        WfAuthProperties p = props();
        MockHttpServletRequest req = request("GET", "/z-wf/workflow/designer/123");
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean pass = interceptor(p).preHandle(req, res, new Object());

        assertTrue(pass, "静态路径不应被鉴权拦下");
    }

    @Test
    @DisplayName("SSO 票据：签名不对的 JWT 不放行，签名对的放行")
    void ssoJwtValidator() {
        String secret = "unit-test-secret";
        WfSsoJwtValidator validator = new WfSsoJwtValidator(secret);
        // 用同一把密钥签一枚 token：z-util 的 Jwt.builder() 与主壳 z-ctc 同源（HS256）
        String token = com.zifang.util.core.jwt.Jwt.builder()
                .algorithm(com.zifang.util.core.jwt.Jwt.HS256)
                .secret(secret)
                .claims(new com.zifang.util.core.jwt.Claims().put("sub", "ceo"))
                .build();

        WfPrincipal ok = validator.validate(token);
        assertNotNull(ok, "同一密钥签出的票据必须验签通过");
        assertEquals("SSO_JWT:sso:ceo", ok.toString());

        assertNull(validator.validate("not-a-jwt"), "非法票据不得放行");
        assertNull(new WfSsoJwtValidator("another-secret").validate(token), "密钥不符不得放行");
    }
}
