package com.zifang.z.wf.admin.auth;

import com.zifang.util.core.jwt.Claims;
import com.zifang.util.core.jwt.Jwt;

/**
 * 主壳 SSO 票据（HS256 JWT）校验器 —— <b>本地离线验签</b>。
 *
 * <p>与主壳（z-ctc 的 {@code JwtUtil}）同一套口径：HS256、密钥相同 ⇒
 * 主壳发的 {@code sso_token} / {@code zctc_token} Cookie、{@code Authorization: Bearer <jwt>}
 * 在 z-wf 侧可以直接用。
 *
 * <p><b>为什么是离线验签而不是回调 z-ctc</b>：z-ctc 自己也提供了
 * {@code GET /api/ctc/authn/verify}（服务端只验签、不查库），本可以远端调用。
 * 但实测该集群的 NetworkPolicy <b>已经生效</b>：z-wf 的 Pod 到 z-ctc 的 ClusterIP
 * （{@code z-ctc-admin.z-ctc.svc.cluster.local:8888}）请求<b>超时</b>，而同 ns 自己的
 * ClusterIP 正常 ⇒ z-ctc ns 的 netpol 没有放行 z-wf 的 Pod。
 * ⇒ 远端验签在当前拓扑下不可用；离线验签还顺带去掉了「4A 中心抖一下 ⇒ 流程引擎全挂」
 * 这个故障域。
 *
 * <p>密钥由环境变量注入（{@code Z_WF_SSO_JWT_SECRET}），来自 k8s Secret，
 * <b>不进镜像层、不进 git</b>。
 *
 * @author zifang
 */
public class WfSsoJwtValidator implements WfTokenValidator {

    private final String secret;

    public WfSsoJwtValidator(String secret) {
        this.secret = secret;
    }

    @Override
    public String source() {
        return WfPrincipal.SOURCE_SSO_JWT;
    }

    @Override
    public WfPrincipal validate(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        try {
            Claims claims = Jwt.parser()
                    .algorithm(Jwt.HS256)
                    .secret(secret)
                    .parse(token.trim());
            String user = pickUser(claims);
            return new WfPrincipal("sso:" + user, source(), user);
        } catch (Throwable e) {
            // 验签失败 / 过期 / 格式非法 —— 一律当「没凭据」，绝不放行
            return null;
        }
    }

    /**
     * 从 claims 里取用户名。取不到就退化成 {@code unknown}（仍算通过验签，
     * 因为签名对就说明是主壳签发的票）。
     */
    private String pickUser(Claims claims) {
        String[] candidates = {"sub", "username", "userName", "userId", "loginName", "name", "account"};
        for (String key : candidates) {
            Object v = claims.get(key);
            if (v != null && !String.valueOf(v).trim().isEmpty() && !"null".equals(String.valueOf(v))) {
                return String.valueOf(v);
            }
        }
        return "unknown";
    }
}
