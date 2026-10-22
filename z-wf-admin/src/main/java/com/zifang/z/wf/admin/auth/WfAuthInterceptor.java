package com.zifang.z.wf.admin.auth;

import java.io.IOException;
import java.util.List;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UrlPathHelper;

/**
 * z-wf 入站鉴权拦截器。
 *
 * <p><b>它是 z-wf 唯一的安全边界</b>：本服务不经网关、不挂主壳 SSO 闸门，
 * 2026-10-22 上线时 9 条读端点匿名可读、20 条写端点全部注册。
 * 本拦截器把 {@code /api/**} 全部收回到「必须带凭据」之后。
 *
 * <p><b>凭据通道（与主壳同口径）</b>：
 * <ol>
 *   <li>{@code Authorization: Bearer <token>}（推荐，不进 URL / access log / Referer）；</li>
 *   <li>裸 {@code Authorization: <token>}（主壳历史写法，一并认）；</li>
 *   <li>Cookie {@code sso_token} / {@code zctc_token}（主壳同名的登录票据）。</li>
 * </ol>
 *
 * <p><b>失败语义</b>：
 * <ul>
 *   <li>没带凭据 / 凭据无效 ⇒ <b>401</b> + JSON 信封；</li>
 *   <li>鉴权开着但<b>一个凭据来源都没配</b> ⇒ <b>503</b> + JSON 信封
 *       （这是配置事故，不能伪装成 401，更不能放行）；</li>
 *   <li>响应一律是 JSON（{@code {"success":false,"code":...,"message":...,"data":null}}）——
 *       <b>不是</b> SPA 兜底 HTML。这一点很关键：z-wf 的 {@code WfWebMvcConfig}
 *       会对未知路径返 {@code index.html}，验收时只看 HTTP 200 会假绿。</li>
 * </ul>
 *
 * @author zifang
 */
public class WfAuthInterceptor implements HandlerInterceptor {

    /** 认证通过后把主体放进请求属性，审计日志据此归因。 */
    public static final String PRINCIPAL_ATTR = "wf.principal";

    private static final Logger log = LoggerFactory.getLogger(WfAuthInterceptor.class);

    private final WfAuthProperties props;
    private final List<WfTokenValidator> validators;
    private final AntPathMatcher matcher = new AntPathMatcher();
    private final UrlPathHelper urlPathHelper = new UrlPathHelper();

    public WfAuthInterceptor(WfAuthProperties props, List<WfTokenValidator> validators) {
        this.props = props;
        this.validators = validators;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!props.isEnabled()) {
            return true;
        }
        String path = pathWithin(request);
        if (!isProtected(path) || isExcluded(path)) {
            return true;
        }

        String token = extractToken(request);
        if (token == null || token.trim().isEmpty()) {
            log.warn("[WF-AUTH] 拒绝匿名请求 method={} path={} src={} reason=no_credential",
                    request.getMethod(), path, request.getRemoteAddr());
            reject(request, response, HttpServletResponse.SC_UNAUTHORIZED, "未认证：缺少凭据", "no_credential");
            return false;
        }

        if (validators.isEmpty()) {
            // fail-closed：开着鉴权却没有任何凭据来源 ⇒ 这是配置事故。
            // 返 503 而不是 401：401 会被读成「用户没登录」，503 才读得出「服务没配好」。
            log.error("[WF-AUTH] 鉴权已启用但没有任何凭据来源（z.wf.auth.tokens / z.wf.auth.sso-jwt-secret 都为空）"
                    + " ⇒ 拒绝请求 method={} path={} src={}", request.getMethod(), path, request.getRemoteAddr());
            reject(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "鉴权未配置：服务未下发任何凭据来源", "no_validator");
            return false;
        }

        for (WfTokenValidator validator : validators) {
            WfPrincipal principal = validator.validate(token);
            if (principal != null) {
                request.setAttribute(PRINCIPAL_ATTR, principal);
                return true;
            }
        }

        log.warn("[WF-AUTH] 拒绝无效凭据 method={} path={} src={} reason=invalid_credential",
                request.getMethod(), path, request.getRemoteAddr());
        reject(request, response, HttpServletResponse.SC_UNAUTHORIZED, "未认证：凭据无效", "invalid_credential");
        return false;
    }

    /**
     * 取凭据：Authorization 头优先，其次同名的 SSO Cookie。
     */
    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && !authHeader.trim().isEmpty()) {
            if (authHeader.startsWith("Bearer ")) {
                String t = authHeader.substring(7).trim();
                if (!t.isEmpty()) {
                    return t;
                }
            } else {
                // 主壳历史写法：裸 token 也认（z-ctc 的 SsoInterceptor 同样认）
                return authHeader.trim();
            }
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie == null || cookie.getName() == null || cookie.getValue() == null) {
                    continue;
                }
                for (String name : props.getCookieNames()) {
                    if (name.equals(cookie.getName()) && !cookie.getValue().trim().isEmpty()) {
                        return cookie.getValue().trim();
                    }
                }
            }
        }
        return null;
    }

    private boolean isProtected(String path) {
        for (String pattern : props.getProtectedPaths()) {
            if (matcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExcluded(String path) {
        for (String pattern : props.getExcludePaths()) {
            if (matcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取应用内路径（去掉 context-path）。用 {@code getPathWithinApplication} 而不是
     * {@code getRequestURI}：后者带 context-path，路径口径会与配置里的 pattern 错位。
     */
    private String pathWithin(HttpServletRequest request) {
        String path = urlPathHelper.getPathWithinApplication(request);
        return path == null ? "" : path;
    }

    /**
     * 拒绝响应：一律 JSON，绝不返回 HTML。
     *
     * <p><b>OPTIONS 要额外显式写 Allow 头</b>（这不是洁癖，是验收硬指标）：
     * Spring 的 {@code FrameworkServlet.doOptions} 在 {@code processRequest} 之后会检查
     * 「响应里有没有 Allow 头」——<b>没有</b>就退回 {@code HttpServlet.doOptions}，
     * 而后者会写上 Servlet 规范里的完整方法表
     * {@code "GET, HEAD, POST, PUT, DELETE, TRACE, OPTIONS, PATCH"}。
     * ⇒ 匿名 OPTIONS 被本拦截器 401 掉之后，若不自己写 Allow，
     * 响应里仍然会挂着 <b>POST/PUT/DELETE</b> ⇒ 验收指标 ②（Allow 不得再含写方法）不成立。
     * 这里写 {@code Allow: OPTIONS}：对<b>未认证</b>的调用方，本资源不对外声明任何写方法。
     */
    private void reject(HttpServletRequest request, HttpServletResponse response, int status,
                        String message, String reason) throws IOException {
        response.setStatus(status);
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setHeader("Allow", "OPTIONS");
        }
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"success\":false,\"code\":" + status
                + ",\"message\":\"" + jsonEscape(message) + "\",\"data\":null,\"reason\":\"" + jsonEscape(reason) + "\"}");
        response.getWriter().flush();
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
