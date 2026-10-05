package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfDefinitionValidator;
import com.zifang.z.wf.core.definition.WfJsonParser;
import com.zifang.z.wf.core.definition.WfValidationIssue;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.persistence.WfPersistence;

/**
 * 流程定义仓库服务 —— 流程定义的部署 / 版本 / 查询。
 *
 * <p>对应 z-camuda 侧的 {@code RepositoryService}，但<b>不依赖 Camunda</b>：
 * 部署就是"解析 + 校验 + 分配版本号 + 落库"，没有任何外部引擎参与。
 *
 * <p><b>版本语义</b>：同一 {@code key} 重复部署 ⇒ 版本号 +1，<b>旧版本保留</b>。
 * 保留旧版本不是洁癖：运行中的流程实例引用的是"key:version"，
 * 如果新部署把旧版本覆盖掉，正在跑的实例下次推进就会读到新图 —— 半路换图的审批流是事故。
 *
 * <p><b>部署闸门</b>：{@link WfDefinitionValidator} 报 ERROR 一律拒绝部署，
 * 一次性报出全部问题（见 validator 的注释）。WARN 放行但记日志。
 *
 * @author zifang
 */
public class WfRepositoryService {

    private static final Logger log = LoggerFactory.getLogger(WfRepositoryService.class);

    private final WfPersistence persistence;

    private final WfXmlParser xmlParser = new WfXmlParser();

    private final WfJsonParser jsonParser = new WfJsonParser();

    private final WfDefinitionValidator validator = new WfDefinitionValidator();

    public WfRepositoryService(WfPersistence persistence) {
        this.persistence = persistence;
    }

    /**
     * 部署 BPMN XML 流程定义。
     *
     * @param xml   BPMN 2.0 XML
     * @param key   覆盖 XML 里的 process id（可为 null）
     * @return 部署后的定义（含分配的版本号）
     * @throws WfDefinitionException 解析失败或校验不通过
     */
    public WfDefinition deployXml(String xml, String key) {
        WfDefinition definition = xmlParser.parse(xml);
        if (key != null && !key.trim().isEmpty()) {
            definition.setKey(key.trim());
        }
        definition.setSourceXml(xml);
        return deploy(definition);
    }

    /**
     * 部署 JSON 流程定义（LogicFlow 导出 / z-wf 原生格式）。
     */
    public WfDefinition deployJson(String json, String key) {
        WfDefinition definition = jsonParser.parse(json);
        if (key != null && !key.trim().isEmpty()) {
            definition.setKey(key.trim());
        }
        return deploy(definition);
    }

    /**
     * 部署已构造好的定义对象（测试与编程式建图用）。
     */
    public WfDefinition deploy(WfDefinition definition) {
        if (definition == null) {
            throw new WfDefinitionException("流程定义不能为 null");
        }
        if (definition.getKey() == null || definition.getKey().trim().isEmpty()) {
            throw new WfDefinitionException("流程定义 key 不能为空");
        }

        // ---- 1. 校验 ----
        List<WfValidationIssue> issues = validator.validate(definition);
        if (WfDefinitionValidator.hasError(issues)) {
            throw new WfDefinitionException(WfDefinitionValidator.render(issues));
        }
        for (WfValidationIssue issue : issues) {
            log.warn("流程定义 {} 校验告警: {}", definition.getKey(), issue);
        }

        // ---- 2. 分配版本号 ----
        WfDefinition latest = persistence.findLatestDefinition(definition.getKey());
        int version = latest == null ? 1 : latest.getVersion() + 1;
        definition.setVersion(version);

        // ---- 3. 建索引并落库 ----
        definition.setStartTime(new Date());
        definition.buildIndex();
        persistence.saveDefinition(definition);

        log.info("部署流程定义: key={}, version={}, nodes={}, flows={}",
                definition.getKey(), version,
                definition.getNodes().size(), definition.getFlows().size());
        return definition;
    }

    /**
     * 取最新版本的定义。
     *
     * @throws WfDefinitionException 定义不存在
     */
    public WfDefinition getLatestDefinition(String key) {
        WfDefinition definition = persistence.findLatestDefinition(key);
        if (definition == null) {
            throw new WfDefinitionException("流程定义不存在: " + key);
        }
        return definition;
    }

    /**
     * 取指定版本的定义。
     */
    public WfDefinition getDefinition(String key, int version) {
        WfDefinition definition = persistence.findDefinition(key, version);
        if (definition == null) {
            throw new WfDefinitionException("流程定义版本不存在: " + key + ":" + version);
        }
        return definition;
    }

    /**
     * 取指定版本，不存在时回落到最新版本。
     * <p>给"按 key 启动流程"的主路径用：调用方通常只关心 key。
     */
    public WfDefinition getDefinitionOrLatest(String key, Integer version) {
        if (version != null) {
            WfDefinition definition = persistence.findDefinition(key, version);
            if (definition != null) {
                return definition;
            }
            log.warn("流程定义版本不存在，回落到最新版本: {}:{}", key, version);
        }
        return getLatestDefinition(key);
    }

    /**
     * 全部定义的最新版本。
     */
    public List<WfDefinition> getAllDefinitions() {
        return persistence.findAllDefinitions();
    }

    /**
     * 某定义的全部历史版本（新 → 旧）。
     */
    public List<WfDefinition> getDefinitionVersions(String key) {
        return persistence.findDefinitionVersions(key);
    }

    /**
     * 按分类查定义（流程分组，对应 z-camuda 的 Category）。
     */
    public List<WfDefinition> getDefinitionsByCategory(String category) {
        return persistence.findDefinitionsByCategory(category);
    }

    /**
     * 全部流程分类（去重）。
     * <p>给流程分组管理页提供选项列表。
     */
    public List<String> getAllCategories() {
        List<String> categories = new ArrayList<>();
        for (WfDefinition definition : persistence.findAllDefinitions()) {
            String category = definition.getCategory();
            if (category != null && !category.trim().isEmpty() && !categories.contains(category)) {
                categories.add(category);
            }
        }
        java.util.Collections.sort(categories);
        return categories;
    }

    /**
     * 批量部署（启动时扫描 classpath 流程用）。
     *
     * @return 部署成功的定义数
     */
    public int deployAll(List<WfDefinition> definitions) {
        int count = 0;
        if (definitions == null) {
            return 0;
        }
        for (WfDefinition definition : definitions) {
            try {
                deploy(definition);
                count++;
            } catch (Exception e) {
                // 单个定义失败不阻断其余部署：classpath 里往往混着别的系统的流程文件
                log.error("部署流程定义失败（已跳过）: {}", definition == null ? "null" : definition.getKey(), e);
            }
        }
        return count;
    }
}
