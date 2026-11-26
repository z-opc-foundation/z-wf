package com.zifang.z.wf.admin.auth;

/**
 * 一次已认证调用的主体。审计日志靠它归因（谁在什么时候调了哪个端点）。
 *
 * @author zifang
 */
public class WfPrincipal {

    /** 服务令牌来源。 */
    public static final String SOURCE_STATIC_TOKEN = "STATIC_TOKEN";

    /** 主壳 SSO 票据来源。 */
    public static final String SOURCE_SSO_JWT = "SSO_JWT";

    /** 归因名（服务令牌名 / SSO 用户名）。 */
    private final String name;

    /** 凭据来源类型。 */
    private final String source;

    /** 可选的更细主体（如 SSO 的 sub）。 */
    private final String subject;

    public WfPrincipal(String name, String source, String subject) {
        this.name = name;
        this.source = source;
        this.subject = subject;
    }

    public String getName() {
        return name;
    }

    public String getSource() {
        return source;
    }

    public String getSubject() {
        return subject;
    }

    @Override
    public String toString() {
        return source + ":" + name;
    }
}
