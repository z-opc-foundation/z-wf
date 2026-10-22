package com.zifang.z.wf.admin.auth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * z-wf 入站鉴权配置。
 *
 * <p><b>为什么 z-wf 需要这一层</b>：z-wf 是 2026-10-22 08:45 首次上线的独立部署服务，
 * 通过 NodePort 31887 裸暴露在集群内网，而它**从设计上就没有任何认证层**
 * （全仓 grep：{@code HandlerInterceptor} / {@code OncePerRequestFilter} /
 * {@code SecurityFilterChain} / {@code @NoLogin} 均为 0 命中，唯一的 WebMvcConfigurer
 * {@code WfWebMvcConfig} 只做 SPA 静态资源回落）。
 * ⇒ 9 条读端点匿名可读、20 条写端点全部已注册。
 *
 * <p><b>为什么不走主壳 SSO 闸门</b>：主壳闸门（z-ctc 的 {@code SsoInterceptor}）是
 * <b>进程内</b>拦截器，挂在 z-ops / z-opc 自己的应用里；z-wf 是独立进程，不经网关、
 * 不挂那个闸门 ⇒ 「把路径加进 {@code sso.intercept-paths}」对它**无效**。
 *
 * <p><b>凭据来源（两个都可选，但至少要有一个）</b>：
 * <ol>
 *   <li><b>服务令牌</b>（{@link #tokens}）：机器对机器调用（z-team AI 领活、z-ops 派发）
 *       用。格式 {@code name:secret} 或裸 {@code secret}。</li>
 *   <li><b>主壳 SSO 票据</b>（{@link #ssoJwtSecret}）：与 z-ctc 同一枚 HS256 密钥，
 *       z-wf **本地离线验签**（不回调 z-ctc —— 实测该集群 NetworkPolicy 已生效，
 *       z-wf 的 Pod 到 z-ctc 的 ClusterIP 是通的反面：被 z-ctc 的 netpol 拦掉，
 *       跨 ns 的 pod→pod 不通 ⇒ 远端验签不可用）。
 *       浏览器侧凭据通道与主壳同口径：{@code Authorization: Bearer <jwt>} 或 Cookie
 *       {@code sso_token} / {@code zctc_token}。</li>
 * </ol>
 *
 * <p><b>fail-closed 是硬要求</b>：{@link #enabled} 默认 true；开了却一个凭据来源都没有
 * ⇒ 所有 {@code /api/**} 请求返 <b>503</b>（配置事故），<b>不是</b> 401（那会把
 * 「没配凭据」伪装成「没登录」），更<b>不是</b> 放行。
 *
 * @author zifang
 */
@ConfigurationProperties(prefix = "z.wf.auth")
public class WfAuthProperties {

    /**
     * 鉴权总开关。默认 <b>true</b>。
     *
     * <p>「默认开着」是这类改动的唯一安全默认值：任何一次「忘了配」的部署，
     * 表现都是「接口 503 / 401」而不是「接口裸奔」。
     */
    private boolean enabled = true;

    /**
     * 受保护路径（Ant 风格，{@code AntPathMatcher}）。
     *
     * <p>默认只保 {@code /api/**}：静态资源、SPA 回落页、接口文档不承载业务数据，
     * 排障时能直接打开页面看报错；把它们也锁上只会逼着排障的人先搞凭据。
     */
    private List<String> protectedPaths = new ArrayList<>(Arrays.asList("/api/**"));

    /**
     * 免鉴权路径（平台必需、且不返回业务数据的路径）。
     *
     * <p>{@code /api/wf/health/probe} 是 k3s 探针专用端点：kubelet 的 httpGet 探针
     * <b>带不了自定义请求头</b>（{@code httpHeaders} 只能写字面量，不能引用 Secret），
     * ⇒ 探针路径无法用 Authorization 保护，只能单开一条免鉴权通道。
     * 它只回 {@code status/engine} 两个字段，<b>不含</b> 流程定义数、实例列表等业务数据。
     *
     * <p>{@code /error} 必须放行：Spring 出错后 forward 到 {@code /error}，
     * 锁上它会让所有错误响应变成 404/白屏，排障时连错误页都看不到。
     */
    private List<String> excludePaths = new ArrayList<>(Arrays.asList("/api/wf/health/probe", "/error"));

    /**
     * 服务令牌白名单。元素格式：{@code name:secret}（名字用于审计日志归因）
     * 或裸 {@code secret}（名字自动取 {@code token#N}）。
     */
    private List<String> tokens = new ArrayList<>();

    /**
     * 主壳 SSO 票据的 HS256 密钥（与 z-ctc 同一枚）。留空 = 不启用票据校验。
     */
    private String ssoJwtSecret = "";

    /**
     * 允许携带 SSO 票据的 Cookie 名。与主壳 {@code sso.token-cookie-names} 同口径。
     */
    private List<String> cookieNames = new ArrayList<>(Arrays.asList("sso_token", "zctc_token"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getProtectedPaths() {
        return protectedPaths;
    }

    public void setProtectedPaths(List<String> protectedPaths) {
        this.protectedPaths = protectedPaths;
    }

    public List<String> getExcludePaths() {
        return excludePaths;
    }

    public void setExcludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths;
    }

    public List<String> getTokens() {
        return tokens;
    }

    public void setTokens(List<String> tokens) {
        this.tokens = tokens;
    }

    public String getSsoJwtSecret() {
        return ssoJwtSecret;
    }

    public void setSsoJwtSecret(String ssoJwtSecret) {
        this.ssoJwtSecret = ssoJwtSecret;
    }

    public List<String> getCookieNames() {
        return cookieNames;
    }

    public void setCookieNames(List<String> cookieNames) {
        this.cookieNames = cookieNames;
    }
}
