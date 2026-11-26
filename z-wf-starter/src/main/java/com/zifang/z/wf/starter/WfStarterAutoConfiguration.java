package com.zifang.z.wf.starter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.zifang.z.wf.core.config.WfProperties;

/**
 * z-wf Starter 自动装配入口。
 *
 * <p>core 与 web 各自通过 {@code META-INF/spring.factories} 自注册，本类只做两件事：
 * <ol>
 *   <li>声明本 starter 存在（供条件装配与文档可读性）</li>
 *   <li>注册 {@link WfServerLifecycle}（就绪自检）</li>
 * </ol>
 * 引擎与 REST 层不重复注册 —— 保持"谁的能力谁注册"，避免双份 Bean 定义。
 *
 * @author zifang
 */
@Configuration
@ConditionalOnClass(name = "com.zifang.z.wf.core.service.WfRuntimeService")
@ConditionalOnProperty(prefix = "z.wf", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import(WfServerLifecycle.class)
public class WfStarterAutoConfiguration {

    /**
     * 暴露配置 Bean，便于业务方注入读取（如只想要 z.wf.* 而不开引擎）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "z.wf", name = "enabled", havingValue = "false")
    public WfProperties wfPropertiesOnly() {
        return new WfProperties();
    }
}
