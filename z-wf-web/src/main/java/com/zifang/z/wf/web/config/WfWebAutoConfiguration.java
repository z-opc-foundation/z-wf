package com.zifang.z.wf.web.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.web.mapper.WfViewMapper;

/**
 * z-wf-web 自动装配 —— 注册 REST 层。
 *
 * <p>只扫 {@code com.zifang.z.wf.web}，不碰业务方包：避免把业务 Controller
 * 意外纳入，也避免与业务方的组件扫描重叠。
 *
 * <p>{@link WfViewMapper} 声明成 Bean 而不是每处 new：它持有 runtimeService 引用，
 * 让容器管生命周期；也方便业务方注册一个子类覆盖转换逻辑。
 *
 * @author zifang
 */
@Configuration
@ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
@ComponentScan(basePackages = "com.zifang.z.wf.web")
public class WfWebAutoConfiguration {

    @Bean
    @Autowired
    public WfViewMapper wfViewMapper(WfRuntimeService runtimeService) {
        return new WfViewMapper(runtimeService);
    }
}
