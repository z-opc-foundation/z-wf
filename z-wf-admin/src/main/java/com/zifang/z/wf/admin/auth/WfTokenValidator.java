package com.zifang.z.wf.admin.auth;

/**
 * 凭据校验器。
 *
 * <p>实现必须<b>永不抛异常</b>：校验失败一律返 {@code null}，
 * 避免把「凭据不对」变成 500（500 会掩盖「鉴权在工作」这个事实）。
 *
 * @author zifang
 */
public interface WfTokenValidator {

    /**
     * 凭据来源标识（写进审计日志）。
     */
    String source();

    /**
     * 校验凭据。
     *
     * @param token 调用方带来的原始票据
     * @return 校验通过返回主体，否则返回 {@code null}
     */
    WfPrincipal validate(String token);
}
