package com.zifang.z.wf.admin.audit;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UrlPathHelper;

import com.zifang.z.wf.admin.auth.WfAuthInterceptor;
import com.zifang.z.wf.admin.auth.WfPrincipal;

/**
 * 访问审计日志（可观测性）。
 *
 * <p><b>为什么必须有它</b>：2026-10-22 首次上线后，z-wf 的 Pod 日志里出现了一组
 * 08:58–08:59 的请求（{@code MissingServletRequestParameterException: userId} /
 * {@code 任务已完成或已作废}），形态与部署手册里的验证命令高度吻合，但 z-wf
 * <b>没有任何访问日志</b> ⇒ 「有没有被非授权方用过」当时<b>无法证明也无法排除</b>。
 * 本拦截器让同类问题今后可归因：谁（凭据名）、什么时候、哪个端点、什么结果、多久。
 *
 * <p><b>与 Tomcat access log 的分工</b>：Tomcat access log 记的是「连接层」
 * （来源 IP、UA、状态码、耗时），记不到「用了哪个凭据」；本拦截器补的是
 * <b>应用层主体</b>（{@code STATIC_TOKEN:xxx} / {@code SSO_JWT:yyy}）与<b>业务路径</b>。
 * 两层都要：access log 负责「流量画像」，审计日志负责「归因」。
 *
 * <p><b>不记什么</b>：不记请求体、不记 query 里的业务参数、不记凭据本身。
 * 凭据只以「来源类型 + 名字」出现；SSO 票据在任何情况下都不落日志。
 *
 * @author zifang
 */
public class WfAccessAuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WfAccessAuditInterceptor.class);

    private static final String START_ATTR = "wf.audit.start";
    private static final String RID_ATTR = "wf.audit.rid";

    private final UrlPathHelper urlPathHelper = new UrlPathHelper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTR, System.currentTimeMillis());
        String rid = request.getHeader("X-Request-Id");
        if (rid == null || rid.trim().isEmpty()) {
            rid = Long.toHexString(System.nanoTime());
        }
        request.setAttribute(RID_ATTR, rid);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object start = request.getAttribute(START_ATTR);
        long durMs = (start instanceof Long) ? (System.currentTimeMillis() - (Long) start) : -1L;
        String rid = String.valueOf(request.getAttribute(RID_ATTR));
        String path = pathWithin(request);
        Object principal = request.getAttribute(WfAuthInterceptor.PRINCIPAL_ATTR);
        String who = (principal instanceof WfPrincipal) ? principal.toString() : "anonymous";
        int status = response.getStatus();

        // 静态资源走 DEBUG：SPA 一页要拉十几个 asset，全打 INFO 会把业务日志淹掉
        if (!path.startsWith("/api/")) {
            log.debug("[WF-ACCESS] rid={} method={} path={} status={} dur_ms={} principal={}",
                    rid, request.getMethod(), path, status, durMs, who);
            return;
        }
        if (ex != null) {
            log.warn("[WF-ACCESS] rid={} method={} path={} status={} dur_ms={} principal={} src={} ua={} error={}",
                    rid, request.getMethod(), path, status, durMs, who,
                    request.getRemoteAddr(), userAgent(request), ex.toString());
        } else {
            log.info("[WF-ACCESS] rid={} method={} path={} status={} dur_ms={} principal={} src={} ua={}",
                    rid, request.getMethod(), path, status, durMs, who,
                    request.getRemoteAddr(), userAgent(request));
        }
    }

    private String pathWithin(HttpServletRequest request) {
        String path = urlPathHelper.getPathWithinApplication(request);
        return path == null ? "" : path;
    }

    private String userAgent(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        if (ua == null) {
            return "-";
        }
        // UA 是外部输入，截断一下，别让超长串把日志行撑爆
        return ua.length() > 120 ? ua.substring(0, 120) : ua;
    }
}
