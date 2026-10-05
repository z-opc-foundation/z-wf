package com.zifang.z.wf.core.config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.service.WfRepositoryService;

/**
 * 启动时流程定义部署器 —— 扫描 classpath 并部署 BPMN。
 *
 * <p><b>单个定义失败不阻断启动</b>：{@code classpath*:processes/*.bpmn} 这种通配路径
 * 很容易扫到别的模块的流程文件，其中一个写错就让整个服务起不来是不可接受的。
 * 正确行为是"能装的全装上，坏的逐个打出来"。
 *
 * @author zifang
 */
public class WfProcessDeployer {

    private static final Logger log = LoggerFactory.getLogger(WfProcessDeployer.class);

    private final WfRepositoryService repositoryService;

    private final WfProperties properties;

    public WfProcessDeployer(WfRepositoryService repositoryService, WfProperties properties) {
        this.repositoryService = repositoryService;
        this.properties = properties;
    }

    /**
     * 从 classpath 扫描并部署。
     *
     * @return 成功部署的定义数
     */
    public int deployFromClasspath() {
        List<Resource> resources = scan();
        if (resources.isEmpty()) {
            log.info("未在 {} 下找到流程定义", properties.getProcessPath());
            return 0;
        }
        int deployed = 0;
        for (Resource resource : resources) {
            try {
                deployed += deployResource(resource);
            } catch (Exception e) {
                log.error("部署流程定义失败（已跳过）: {}", resource.getDescription(), e);
            }
        }
        log.info("启动部署流程定义: 扫描 {} 个，成功 {} 个", resources.size(), deployed);
        return deployed;
    }

    /**
     * 部署单个资源。
     *
     * @return 1 = 成功，0 = 跳过
     */
    public int deployResource(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null) {
            return 0;
        }
        // 只处理 BPMN；同目录下的其它文件（README、json 等）跳过而不是报错
        if (!filename.toLowerCase().endsWith(".bpmn")
                && !filename.toLowerCase().endsWith(".bpmn20.xml")) {
            return 0;
        }
        InputStream in = null;
        try {
            in = resource.getInputStream();
            String xml = readAll(in);
            WfDefinition definition = repositoryService.deployXml(xml, null);
            log.info("已部署流程定义: key={}, version={}, name={}",
                    definition.getKey(), definition.getVersion(), definition.getName());
            return 1;
        } catch (Exception e) {
            log.error("流程定义 {} 部署失败: {}", filename, e.getMessage());
            return 0;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // 关闭失败无需处理
                }
            }
        }
    }

    /**
     * 扫描流程定义资源。
     */
    public List<Resource> scan() {
        List<Resource> result = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(properties.getProcessPath());
            for (Resource resource : resources) {
                if (resource.exists() && resource.isReadable()) {
                    result.add(resource);
                }
            }
        } catch (Exception e) {
            log.error("扫描流程定义失败: {}", properties.getProcessPath(), e);
        }
        return result;
    }

    private String readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
