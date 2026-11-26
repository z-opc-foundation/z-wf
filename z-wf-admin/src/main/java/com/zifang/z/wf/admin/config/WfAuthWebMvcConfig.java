package com.zifang.z.wf.admin.config;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.zifang.z.wf.admin.audit.WfAccessAuditInterceptor;
import com.zifang.z.wf.admin.auth.WfAuthInterceptor;
import com.zifang.z.wf.admin.auth.WfAuthProperties;
import com.zifang.z.wf.admin.auth.WfSsoJwtValidator;
import com.zifang.z.wf.admin.auth.WfStaticTokenValidator;
import com.zifang.z.wf.admin.auth.WfTokenValidator;

/**
 * z-wf 的 Web MVC 层装配：入站鉴权 + 访问审计。
 *
 * <p>放在 {@code z-wf-admin} 而不是 {@code z-wf-starter}：鉴权是<b>部署形态</b>的事
 * （独立进程、独立暴露面），写进 starter 会连带给所有宿主套上同一道闸，
 * 而 starter 的其它宿主（如 z-camuda 形态）未必是同样的暴露面。
 *
 * <p>⚠ z-wf-admin 里原本唯一的 WebMvcConfigurer 是 {@code WfWebMvcConfig}
 * （只做 SPA 静态资源回落，与认证无关）—— 本类<b>不改</b>那个回落行为，
 * 只在其前面加一道闸。
 *
 * @author zifang
 */
@Configuration
@EnableConfigurationProperties(WfAuthProperties.class)
public class WfAuthWebMvcConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WfAuthWebMvcConfig.class);

    private final WfAuthProperties props;

    public WfAuthWebMvcConfig(WfAuthProperties props) {
        this.props = props;
    }

    /**
     * 组装凭据来源。<b>顺序有意义</b>：先静态令牌（机器调用），再 SSO 票据（人调用）。
     */
    private List<WfTokenValidator> buildValidators() {
        List<WfTokenValidator> validators = new ArrayList<>();
        WfStaticTokenValidator staticValidator = new WfStaticTokenValidator(props.getTokens());
        if (staticValidator.size() > 0) {
            validators.add(staticValidator);
        }
        String ssoSecret = props.getSsoJwtSecret();
        if (ssoSecret != null && !ssoSecret.trim().isEmpty()) {
            validators.add(new WfSsoJwtValidator(ssoSecret.trim()));
        }
        return validators;
    }

    @Bean
    public WfAuthInterceptor wfAuthInterceptor() {
        List<WfTokenValidator> validators = buildValidators();
        if (!props.isEnabled()) {
            log.error("[WF-AUTH] ⚠ 鉴权被显式关闭（z.wf.auth.enabled=false）⇒ /api/** 全部匿名可读。"
                    + " 这是排障用的临时开关，发版前必须改回 true。");
        } else if (validators.isEmpty()) {
            log.error("[WF-AUTH] ⚠ 鉴权已启用但**没有任何凭据来源**"
                    + "（z.wf.auth.tokens 与 z.wf.auth.sso-jwt-secret 均为空）"
                    + " ⇒ fail-closed：所有 /api/** 请求返回 503，直到补上凭据来源。");
        } else {
            StringBuilder sb = new StringBuilder();
            for (WfTokenValidator v : validators) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(v.source());
            }
            log.info("[WF-AUTH] 入站鉴权已启用：凭据来源 {}（受保护路径 {}，免鉴权 {}）",
                    sb, props.getProtectedPaths(), props.getExcludePaths());
        }
        return new WfAuthInterceptor(props, validators);
    }

    @Bean
    public WfAccessAuditInterceptor wfAccessAuditInterceptor() {
        return new WfAccessAuditInterceptor();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 审计在前：它先起计时，鉴权拒绝时它照样能记录（Spring 只会对
        // preHandle 返回 true 的拦截器回调 afterCompletion，审计在前 ⇒ 拒绝也留痕）
        registry.addInterceptor(wfAccessAuditInterceptor()).addPathPatterns("/**");
        registry.addInterceptor(wfAuthInterceptor()).addPathPatterns("/**");
    }
}
