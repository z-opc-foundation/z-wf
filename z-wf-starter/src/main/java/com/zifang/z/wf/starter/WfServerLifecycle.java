package com.zifang.z.wf.starter;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.service.WfTaskService;

/**
 * 服务就绪后的自检与部署。
 *
 * <p>挂在 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}：
 * 容器完全就绪后再部署流程，才能保证所有 {@code WfXxxService} Bean 都已注入完成。
 * 在 {@code @PostConstruct} 里跑，很容易拿到还没初始化的 service。
 *
 * <p>部署行为本身在 {@code WfProcessDeployer}（core 层）里，
 * 这里是 starter 层的触发点 + 额外的自检日志。
 *
 * @author zifang
 */
@ConditionalOnProperty(prefix = "z.wf", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WfServerLifecycle implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(WfServerLifecycle.class);

    @Resource
    private WfRepositoryService repositoryService;

    @Resource
    private WfRuntimeService runtimeService;

    @Resource
    private WfTaskService taskService;

    @Resource
    private WfPersistence persistence;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("persistence", persistence.getClass().getSimpleName());
            summary.put("definitionCount", repositoryService.getAllDefinitions().size());
            log.info("z-wf 引擎就绪: {}", summary);

            // 把已部署的定义列出来：出问题时第一眼就能看出"流程有没有装上"
            repositoryService.getAllDefinitions().forEach(definition ->
                    log.info("  已加载流程: key={}, v{}, nodes={}, flows={}",
                            definition.getKey(), definition.getVersion(),
                            definition.getNodes().size(), definition.getFlows().size()));
        } catch (Exception e) {
            // 自检失败不能让应用起不来：流程引擎是业务能力之一，不是应用可用性的前提
            log.error("z-wf 引擎自检失败（应用继续启动）", e);
        }
    }
}
