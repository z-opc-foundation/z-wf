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
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.persistence.WfProcessInstanceQuery;

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
     * 这个 key/version 是否<b>已经落库</b>，落库了就返回它，没落库返回 {@code null}。
     *
     * <p>与 {@link #getDefinitionOrLatest(String, Integer)} 的区别是<b>不抛异常</b>：
     * 这里要回答的是"在不在"，不是"给我一个"，调用方需要自己决定怎么处置。
     * 启动流程前的落库前置检查就靠它。
     */
    public WfDefinition findPersisted(String key, int version) {
        WfDefinition definition = persistence.findDefinition(key, version);
        if (definition != null) {
            return definition;
        }
        return persistence.findLatestDefinition(key);
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
     * 物理删除某个版本的流程定义。
     *
     * <p>对标 z-camuda 的 {@code deleteDeployment}。本引擎没有独立的"部署"实体 ——
     * 一次 {@code deployXml} 就是一条 (key, version) 定义记录，所以两者的单位一致。
     *
     * <p><b>有在跑的实例时直接拒绝，不做级联删除。</b> 原因是硬约束不是选择：
     * 每次推进都按 {@code (key, version)} 重新载入定义，定义一删，那个实例就再也
     * 推不动了 —— 下一次 {@code completeTask} 报"流程定义不存在"，而且永远不会自愈。
     * 报错里带上在途实例的 businessKey，调用方先 {@code terminate} 再回来删，
     * 这比"顺手把在办的单一起干掉"安全得多：撤销部署与终止在办的单是两种决策。
     *
     * <p>想"下架但保留历史"请用 {@link #suspendDefinition}，它保留在跑的实例。
     *
     * @throws WfDefinitionException 定义不存在，或仍有在途实例
     */
    public void deleteDefinition(String key, int version) {
        if (persistence.findDefinition(key, version) == null) {
            throw new WfDefinitionException("流程定义版本不存在，删除失败: " + key + ":" + version);
        }
        WfProcessInstanceQuery running = new WfProcessInstanceQuery()
                .setDefinitionKey(key)
                .setDefinitionVersion(version)
                .setUnfinishedOnly(true)
                .setPageNum(1).setPageSize(MAX_RUNNING_SAMPLE);
        List<WfProcessInstance> instances = persistence.queryProcessInstances(running);
        if (!instances.isEmpty()) {
            List<String> details = new ArrayList<>();
            for (WfProcessInstance instance : instances) {
                details.add(instance.getBusinessKey() == null
                        ? instance.getId() : instance.getBusinessKey() + "(" + instance.getId() + ")");
            }
            boolean truncated = instances.size() >= MAX_RUNNING_SAMPLE;
            throw new WfDefinitionException("流程定义 [" + key + ":" + version + "] 还有 "
                    + (truncated ? "至少 " : "") + instances.size() + " 个在途实例，不能删除: "
                    + details + (truncated ? " …" : "")
                    + "。定义一删，这些实例就再也推不动了（每次推进都按 key:version 重新载入定义）。"
                    + "请先调用 WfRuntimeService#terminate 终止它们，或改用 suspendDefinition 下架"
                    + "（保留在途实例）");
        }
        if (!persistence.deleteDefinition(key, version)) {
            // 上一步刚确认过它在，这一步却说没删掉：并发部署/删除导致的竞态。
            // 不静默返回 —— 调用方需要知道这次删除没生效
            throw new WfDefinitionException("删除流程定义失败（可能已被并发删除）: "
                    + key + ":" + version);
        }
        log.info("流程定义已删除: {}:{}", key, version);
    }

    /** 在途实例报错时最多列出这么多个 —— 报全量会让异常消息长到没人愿意读。 */
    private static final int MAX_RUNNING_SAMPLE = 10;

    /**
     * 停用某个版本：之后不能再启动新实例，<b>已在跑的实例完全不受影响</b>。
     *
     * <p>这是"老版本流程停止接受新申请"的正解。以前想停用只能改别的地方绕，
     * 而绕的方式通常是把分类改掉 —— 改分类会连带影响按分类的列表和审批中心的分组展示，
     * 副作用比它解决的问题大。
     *
     * @throws WfDefinitionException 版本不存在
     */
    public void suspendDefinition(String key, int version) {
        if (!persistence.setDefinitionSuspended(key, version, true)) {
            throw new WfDefinitionException(
                    "流程定义版本不存在，停用失败: " + key + ":" + version);
        }
        log.info("流程定义已停用: {}:{}", key, version);
    }

    /**
     * 启用某个版本（对应 {@link #suspendDefinition}）。
     *
     * @throws WfDefinitionException 版本不存在
     */
    public void activateDefinition(String key, int version) {
        if (!persistence.setDefinitionSuspended(key, version, false)) {
            throw new WfDefinitionException(
                    "流程定义版本不存在，启用失败: " + key + ":" + version);
        }
        log.info("流程定义已启用: {}:{}", key, version);
    }

    /**
     * 按名称模糊 + 停用状态查定义（最新版本）。
     *
     * @param suspended {@code null} 不限
     */
    public List<WfDefinition> queryDefinitions(String keyLike, String nameLike, Boolean suspended) {
        return persistence.findDefinitions(keyLike, nameLike, suspended);
    }

    /**
     * 回读部署时留存的原始 BPMN XML（对应 z-camuda 的 {@code getProcessModel}）。
     *
     * <p>模型编辑器集成靠它：没有它就只剩引擎解析后的图结构，回显时排版已经丢了。
     *
     * @throws WfDefinitionException 版本不存在，或当初不是用 {@code deployXml} 部署的
     *         （JSON 部署与内存里的测试定义都没有原始 XML）
     */
    public String getProcessModel(String key, int version) {
        WfDefinition definition = getDefinition(key, version);
        String xml = definition.getSourceXml();
        if (xml == null || xml.trim().isEmpty()) {
            // 静默返回空串会让编辑器弹出一个空白画布，用户以为是流程本身没画东西
            throw new WfDefinitionException("流程定义没有原始 XML（" + key + ":" + version
                    + "），可能不是通过 deployXml 部署的");
        }
        return xml;
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
