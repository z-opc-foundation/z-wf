package com.zifang.z.wf.starter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * z-wf Starter 的组件扫描入口。
 *
 * <p>只扫 {@code com.zifang.z.wf.starter}（RPC 暴露 + 示例 delegate），
 * 不重复扫 core / web（它们各自用 {@code spring.factories} 自注册）。
 *
 * @author zifang
 */
@Configuration
@ConditionalOnClass(name = "com.zifang.z.wf.core.service.WfRuntimeService")
@ComponentScan(basePackages = "com.zifang.z.wf.starter")
public class WfStarterComponentScan {
}
