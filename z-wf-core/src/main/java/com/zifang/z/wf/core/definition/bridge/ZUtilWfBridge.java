package com.zifang.z.wf.core.definition.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.zifang.util.wf.kernel.config.Connector;
import com.zifang.util.wf.kernel.config.WorkflowConfiguration;
import com.zifang.util.wf.kernel.config.WorkflowNode;
import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfFlow;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfNodeType;

/**
 * z-util-wf（内存引擎定义）↔ z-wf（生产引擎定义）的协议桥。
 *
 * <p><b>为什么需要它。</b> 两侧共用同一套 BPMN XML 语义（见
 * {@link com.zifang.z.wf.core.definition.WfNodeType#fromBpmn(String)} 的映射表），
 * 但内部模型形状不同：
 * <ul>
 *   <li>z-util-wf：{@link WorkflowNode} + {@link Connector} 的<b>邻接表</b>（pre/post），
 *       没有独立的"连线"对象</li>
 *   <li>z-wf：{@link WfNode} + {@link WfFlow} 的<b>边列表</b>，连线上带条件与默认流标记</li>
 * </ul>
 * 没有桥，两个引擎就是"读同一份 XML、落到不同形状的对象"，协议共用只停在解析层。
 *
 * <p><b>典型用法。</b> 内存里用代码搭好一张图（z-util-wf 的 code-first 风格），
 * 通过 {@link #toWfDefinition} 交给 z-wf 部署成可持久化、可多人并发的生产流程；
 * 反向 {@link #toWorkflowConfiguration} 用于把 z-wf 的定义放回内存引擎做单测/预演。
 *
 * <p><b>条件归属 —— 本桥最容易出错的地方。</b>
 * z-util-wf 的 {@code BpmnModelConverter} 把 sequenceFlow 上的 conditionExpression
 * <b>写在目标节点上</b>（{@code invokeParameter}，若目标已有 documentation 则退到
 * {@code cache["conditionExpression"]}）。这带来一个结构性问题：
 * <b>当一个节点有 ≥2 条入线且带条件时，多个条件写进同一个字段，后写覆盖先写，
 * 到底哪个条件属于哪条线已经无法从模型里还原。</b>
 *
 * <p>本桥对此的处理是<b>不猜</b>：
 * <ul>
 *   <li>{@link #toWorkflowConfiguration} 是<b>无损</b>的：它手里有真实的每条线，
 *       多入线时把条件写进 {@code cache["conditionExpression:<sourceRef>"]}（按源节点分键），
 *       不覆盖、不丢弃；</li>
 *   <li>{@link #toWfDefinition} 遇到<b>无法无歧义归属</b>的历史配置时直接抛
 *       {@link WfDefinitionException}，而不是随便挑一个条件套上去。</li>
 * </ul>
 *
 * <p>为什么不"丢掉条件、让它退化成无条件连线"？因为那是 <b>fail-open</b>：
 * {@code WfEngine.selectFlows} 把 {@code flow.isUnconditional()} 直接当成立，
 * 一条被丢掉条件的排他网关出线会<b>无条件通过</b> —— 本该走人工审批的单据被静默放行。
 * 宁可部署失败，也不能让审批引擎在条件不明时放行。
 *
 * <p><b>已知限制（选型前必读）。</b> z-util-wf 的 {@link Connector} 是邻接表，
 * 它用 <b>post 列表里的重数</b>表达并行边；{@code BpmnModelConverter} 在装配时会去重，
 * 因此经过它转换的图<b>已经丢失了"同一对节点之间有两条并列边"这一信息</b> ——
 * 这是输入侧的信息丢失，桥接无法凭空复原。
 * 典型受影响的图：BPMN 里 {@code gw -[days<=3]-> task} 与 {@code gw(default) -[->] task} 两条并列边，
 * 转换后只剩一条，桥接只能把它标成"带条件 + 默认流"，
 * 后果是<b>两条条件都不成立时会回退到 task 而不是别的分支</b>。
 * 本桥对<b>自己写出</b>的配置是无损的（post 保留重数 + 按源分键存条件）；
 * 带并列边的图请直接用 z-wf 的 {@link WfXmlParser} / JSON 解析器部署，不要走 z-util-wf。
 *
 * <p><b>两个引擎在条件求值上的行为差异（选型时要知道）。</b>
 * z-util-wf 的 {@code GatewayEvaluator} 对空表达式返回 {@code true}，
 * 且底层 {@code ElEvaluator} 会把未定义变量当 {@code null} 参与比较；
 * z-wf 的 {@code WfExpressionEvaluator} 对未定义标识符 <b>fail-closed</b>（判 false）
 * 并交给 {@code defaultFlow} 兜底。带条件的审批流程请用 z-wf 那一侧求值。
 *
 * @author zifang
 */
public final class ZUtilWfBridge {

    // ==================== cache 键约定 ====================

    /** 目标节点上的入线条件（无歧义时用），与 {@code BpmnModelConverter} 同键。 */
    public static final String KEY_CONDITION = "conditionExpression";

    /**
     * 目标节点上的<b>按源节点分键</b>的入线条件：{@code conditionExpression:<sourceRef>}。
     *
     * <p>{@link BpmnModelConverter} 用单个 {@code invokeParameter} 存条件，
     * 多条入线会互相覆盖。这个分键写法是本桥对 z-util-wf 模型的向后兼容扩展：
     * 内存引擎读不懂它（会忽略），但信息不丢，转回 {@link WfFlow} 时能精确还原。
     */
    public static final String KEY_CONDITION_BY_SOURCE_PREFIX = "conditionExpression:";

    /** 源节点上的默认流出边目标节点 id，与 {@code BpmnModelConverter} 同键。 */
    public static final String KEY_DEFAULT_FLOW = "defaultFlow";

    /** {@link WfNode#getAssignee()}。 */
    public static final String KEY_ASSIGNEE = "assignee";
    /** {@link WfNode#getCandidateUsers()}，逗号分隔。 */
    public static final String KEY_CANDIDATE_USERS = "candidateUsers";
    /** {@link WfNode#getCandidateGroups()}，逗号分隔。 */
    public static final String KEY_CANDIDATE_GROUPS = "candidateGroups";
    /** {@link WfNode#getCategory()}。 */
    public static final String KEY_CATEGORY = "category";
    /** {@link WfNode#getFormKey()}。 */
    public static final String KEY_FORM_KEY = "formKey";
    /** {@link WfNode#getPriority()}。 */
    public static final String KEY_PRIORITY = "priority";
    /** {@link WfNode#getDueDateDuration()}。 */
    public static final String KEY_DUE_DATE_DURATION = "dueDateDuration";
    /** {@link WfNode#getRequiredVariables()}，逗号分隔。 */
    public static final String KEY_REQUIRED_VARIABLES = "requiredVariables";
    /** {@link WfNode#getScript()}。 */
    public static final String KEY_SCRIPT = "script";
    /** {@link WfNode#getMessageName()}。 */
    public static final String KEY_MESSAGE_NAME = "messageName";
    /** {@link WfNode#getCalledElementKey()}。 */
    public static final String KEY_CALLED_ELEMENT = "calledElement";
    /** {@link WfNode#getResultExpression()}。 */
    public static final String KEY_RESULT_EXPRESSION = "resultExpression";
    /** {@link WfNode#getDelegateClass()}。 */
    public static final String KEY_DELEGATE_CLASS = "delegateClass";
    /** {@link WfNode#getDelegateExpression()}。 */
    public static final String KEY_DELEGATE_EXPRESSION = "delegateExpression";
    /** {@link WfDefinition#getCategory()}（定义级分类，落在起始节点上）。 */
    public static final String KEY_DEFINITION_CATEGORY = "zifang.definition.category";

    private ZUtilWfBridge() {
    }

    // ==================== z-util-wf → z-wf ====================

    /**
     * 内存定义 → 生产引擎定义。
     *
     * @param config z-util-wf 的内存流程配置
     * @param key    流程定义 key（{@code WorkflowConfiguration} 里没有 key 字段，由调用方给）
     * @param name   流程定义名称
     * @return 已建索引、可直接交给 {@code WfRepositoryService.deploy} 的定义
     * @throws WfDefinitionException 存在无法无歧义归属的入线条件（见类注释"条件归属"）
     */
    public static WfDefinition toWfDefinition(WorkflowConfiguration config, String key, String name) {
        if (config == null) {
            throw new WfDefinitionException("z-util-wf 流程配置不能为 null");
        }
        List<WorkflowNode> sourceNodes = config.getWorkflowNodeList();
        if (sourceNodes == null || sourceNodes.isEmpty()) {
            throw new WfDefinitionException("z-util-wf 流程配置没有节点，无法转换为 " + key);
        }

        // ---- 节点 ----
        List<WfNode> nodes = new ArrayList<WfNode>(sourceNodes.size());
        Map<String, WorkflowNode> byId = new LinkedHashMap<String, WorkflowNode>();
        for (WorkflowNode source : sourceNodes) {
            if (source == null || source.getNodeId() == null) {
                continue;
            }
            byId.put(source.getNodeId(), source);
            nodes.add(convertNode(source));
        }

        // ---- 连线：从 post 邻接表还原成有向边 ----
        // 先统计入度：条件在 z-util-wf 里挂在目标节点上，入度 > 1 时才谈得上归属歧义
        Map<String, Integer> inDegree = new HashMap<String, Integer>();
        for (WorkflowNode source : sourceNodes) {
            if (source == null || source.getConnector() == null) {
                continue;
            }
            List<String> post = source.getConnector().getPost();
            if (post == null) {
                continue;
            }
            for (String targetId : post) {
                Integer old = inDegree.get(targetId);
                inDegree.put(targetId, old == null ? 1 : old + 1);
            }
        }

        // 第二遍才建边。分两遍是因为"默认流标在哪条边上"要看完整组才知道：
        // 同一对节点可能有多条边，只有先把整组建出来才能挑对。
        List<WfFlow> flows = new ArrayList<WfFlow>();
        // 已被消费的按源分键条件：同一目标出现多次时（并行边）条件只给第一条，
        // 否则同一个条件会被复制到每条并行边上
        Set<String> consumedConditionKeys = new HashSet<String>();
        for (WorkflowNode source : sourceNodes) {
            if (source == null || source.getConnector() == null) {
                continue;
            }
            List<String> post = source.getConnector().getPost();
            if (post == null) {
                continue;
            }
            for (String targetId : post) {
                flows.add(convertFlow(source.getNodeId(), targetId, source, byId, inDegree,
                        consumedConditionKeys));
            }
        }
        markDefaultFlows(sourceNodes, flows);

        WfDefinition definition = new WfDefinition(key, name);
        definition.setNodes(nodes);
        definition.setFlows(flows);
        // 定义级分类在 z-util-wf 侧没有落点，按约定挂在起始节点的 cache 上，这里读回来
        String startId = findStartId(nodes, flows);
        WorkflowNode startSource = startId == null ? null : byId.get(startId);
        if (startSource != null && startSource.getCache() != null) {
            String category = startSource.getCache().get(KEY_DEFINITION_CATEGORY);
            if (category != null && !category.trim().isEmpty()) {
                definition.setCategory(category);
            }
        }
        return definition.buildIndex();
    }

    /**
     * 单节点转换：基础字段直映，z-util-wf 没有的审批语义从 {@code cache} 里取。
     */
    private static WfNode convertNode(WorkflowNode source) {
        WfNode node = new WfNode(source.getNodeId(), source.getName(),
                WfNodeType.fromBpmn(source.getType()));

        Map<String, String> cache = source.getCache() == null
                ? new HashMap<String, String>() : source.getCache();

        node.setAssignee(cache.get(KEY_ASSIGNEE));
        node.setCandidateUsers(splitList(cache.get(KEY_CANDIDATE_USERS)));
        node.setCandidateGroups(splitList(cache.get(KEY_CANDIDATE_GROUPS)));
        node.setCategory(cache.get(KEY_CATEGORY));
        node.setFormKey(cache.get(KEY_FORM_KEY));
        node.setRequiredVariables(splitList(cache.get(KEY_REQUIRED_VARIABLES)));
        node.setScript(cache.get(KEY_SCRIPT));
        node.setMessageName(cache.get(KEY_MESSAGE_NAME));
        node.setCalledElementKey(cache.get(KEY_CALLED_ELEMENT));
        node.setResultExpression(cache.get(KEY_RESULT_EXPRESSION));

        // serviceTask 的实现类：BpmnModelConverter 把 BPMN 的 implementation 写进 cache
        String delegate = cache.get(KEY_DELEGATE_CLASS);
        if (delegate == null) {
            delegate = cache.get("implementation");
        }
        if (delegate == null) {
            delegate = source.getInvokeDynamic();
        }
        node.setDelegateClass(delegate);
        node.setDelegateExpression(cache.get(KEY_DELEGATE_EXPRESSION));

        String priority = cache.get(KEY_PRIORITY);
        if (priority != null) {
            try {
                node.setPriority(Integer.parseInt(priority.trim()));
            } catch (NumberFormatException e) {
                throw new WfDefinitionException("节点 " + source.getNodeId()
                        + " 的 " + KEY_PRIORITY + " 不是合法整数: " + priority);
            }
        }
        node.setDueDateDuration(cache.get(KEY_DUE_DATE_DURATION));

        // 未被上面消费的 cache 项原样带进扩展属性，
        // 免得 cache 里业务自己塞的东西在转换中静默蒸发
        for (Map.Entry<String, String> entry : cache.entrySet()) {
            String cacheKey = entry.getKey();
            if (isReservedCacheKey(cacheKey) || entry.getValue() == null) {
                continue;
            }
            node.getProperties().put(cacheKey, entry.getValue());
        }
        return node;
    }

    /**
     * 单条边转换。
     *
     * <p>条件按以下优先级取，任一取到即止：
     * <ol>
     *   <li>{@code cache["conditionExpression:<sourceRef>"]} —— 本桥的无损分键写法，最精确</li>
     *   <li>{@code cache["conditionExpression"]} —— z-util-wf 的单值写法，仅当目标入度为 1 时可信</li>
     *   <li>{@code invokeParameter} —— 同上，且只在它是字符串时才当条件（否则可能是 documentation）</li>
     * </ol>
     *
     * <p>入度 &gt; 1 且只能落到 2/3 两种单值写法时，条件归属不可还原 ⇒ 直接抛异常。
     */
    private static WfFlow convertFlow(String sourceId, String targetId,
                                     WorkflowNode sourceNode, Map<String, WorkflowNode> byId,
                                     Map<String, Integer> inDegree,
                                     Set<String> consumedConditionKeys) {
        WfFlow flow = new WfFlow(sourceId, targetId);
        // z-util-wf 没有连线 id，用 "source->target" 派生：可重现，且人眼可读。
        // 同一对节点有多条边时靠出现次序区分（桥接保序，见 markDefaultFlows 的说明）
        flow.setId(sourceId + "->" + targetId);

        Integer degree = inDegree.get(targetId);
        boolean singleIncoming = degree == null || degree <= 1;

        WorkflowNode targetNode = byId.get(targetId);
        Map<String, String> targetCache = targetNode == null || targetNode.getCache() == null
                ? new HashMap<String, String>() : targetNode.getCache();

        String conditionKey = KEY_CONDITION_BY_SOURCE_PREFIX + sourceId;
        String condition = null;
        if (!consumedConditionKeys.contains(conditionKey)) {
            condition = targetCache.get(conditionKey);
            if (condition != null) {
                consumedConditionKeys.add(conditionKey);
            }
        }
        if (condition == null) {
            condition = targetCache.get(KEY_CONDITION);
            if (condition == null && targetNode != null
                    && targetNode.getInvokeParameter() instanceof String) {
                condition = (String) targetNode.getInvokeParameter();
            }
            if (condition != null && !singleIncoming) {
                // 这是本桥最关键的一处 fail-closed：
                // 条件被覆盖后无法判断属于哪条线，丢弃它会让 WfEngine 把它当无条件出线直接放行。
                throw new WfDefinitionException("节点 " + targetId + " 有 " + degree
                        + " 条入线，但条件只存在单值字段（invokeParameter / "
                        + KEY_CONDITION + "），无法判断条件属于哪条线。"
                        + "请把该节点的条件改写为 " + KEY_CONDITION_BY_SOURCE_PREFIX
                        + "<sourceNodeId> 形式，或直接用 z-wf 的 BPMN/JSON 解析器部署该流程。");
            }
        }
        if (condition != null && condition.trim().isEmpty()) {
            condition = null;
        }
        flow.setConditionExpression(condition);
        return flow;
    }

    /**
     * 把源节点上的 {@code cache["defaultFlow"]} 落到具体某条边上。
     *
     * <p>z-util-wf 把默认流记在<b>源节点</b>上（目标节点 id），边上没有这个标记，
     * 所以必须由本桥指认。同一对节点有多条边时优先选<b>无条件</b>的那条 ——
     * 典型形态是 BPMN 里一条带条件的边 + 一条 default 边指向同一个目标；
     * 若所有边都带条件，则标在第一条上（这是"条件不成立就回退到它"的显式写法）。
     *
     * <p>注意：无法区分"两条边被邻接表合并成一条"与"作者本就把条件与默认标在同一条边上"，
     * 两者在 z-util-wf 侧的状态完全相同。详见类注释的"已知限制"。
     */
    private static void markDefaultFlows(List<WorkflowNode> sourceNodes, List<WfFlow> flows) {
        for (WorkflowNode source : sourceNodes) {
            if (source == null || source.getCache() == null) {
                continue;
            }
            String defaultTarget = source.getCache().get(KEY_DEFAULT_FLOW);
            if (defaultTarget == null || defaultTarget.trim().isEmpty()) {
                continue;
            }
            WfFlow chosen = null;
            WfFlow first = null;
            for (WfFlow flow : flows) {
                if (!source.getNodeId().equals(flow.getSourceRef())
                        || !defaultTarget.equals(flow.getTargetRef())) {
                    continue;
                }
                if (first == null) {
                    first = flow;
                }
                if (flow.isUnconditional()) {
                    chosen = flow;
                    break;
                }
            }
            if (chosen == null) {
                chosen = first;
            }
            if (chosen != null) {
                chosen.setDefaultFlow(true);
            }
        }
    }

    // ==================== z-wf → z-util-wf ====================

    /**
     * 生产引擎定义 → 内存定义。
     *
     * <p><b>本方向是无损的</b>：每条 {@link WfFlow} 都有真实的源/目标，
     * 条件按 (目标, 源) 分键写入，{@link #toWfDefinition} 能完整还原。
     * 内存引擎的 {@code executeGateway} 只读 {@code invokeParameter}，
     * 所以<b>入度为 1 的条件同时写进 {@code invokeParameter}</b>（与
     * {@code BpmnModelConverter} 产出同形），入度 &gt; 1 时只写分键，
     * 避免覆盖造成信息丢失 —— 内存引擎此时会忽略这些条件，
     * 属于"该引擎能力所限"，不是静默按无条件放行。
     *
     * @param definition z-wf 流程定义
     * @return z-util-wf 内存流程配置
     */
    public static WorkflowConfiguration toWorkflowConfiguration(WfDefinition definition) {
        if (definition == null) {
            throw new WfDefinitionException("流程定义不能为 null");
        }
        List<WfNode> wfNodes = new ArrayList<WfNode>(definition.nodeNodes());
        Map<String, WfNode> wfById = new HashMap<String, WfNode>();
        for (WfNode node : wfNodes) {
            wfById.put(node.getId(), node);
        }

        // 目标节点入度：决定条件写 invokeParameter 还是写分键
        Map<String, Integer> inDegree = new HashMap<String, Integer>();
        for (WfFlow flow : definition.getFlows()) {
            Integer old = inDegree.get(flow.getTargetRef());
            inDegree.put(flow.getTargetRef(), old == null ? 1 : old + 1);
        }

        List<WorkflowNode> nodes = new ArrayList<WorkflowNode>(wfNodes.size());
        Map<String, WorkflowNode> byId = new HashMap<String, WorkflowNode>();
        for (WfNode wfNode : wfNodes) {
            WorkflowNode node = new WorkflowNode();
            node.setNodeId(wfNode.getId());
            node.setName(wfNode.getName());
            node.setType(wfNode.getType().bpmnName());
            node.setGroupId(wfNode.getCategory());
            Connector connector = new Connector(new ArrayList<String>(), new ArrayList<String>());
            node.setConnector(connector);
            node.setServiceUnit(serviceUnitOf(wfNode.getType()));
            HashMap<String, String> cache = new HashMap<String, String>();
            putIfPresent(cache, KEY_ASSIGNEE, wfNode.getAssignee());
            putIfPresent(cache, KEY_CANDIDATE_USERS, join(wfNode.getCandidateUsers()));
            putIfPresent(cache, KEY_CANDIDATE_GROUPS, join(wfNode.getCandidateGroups()));
            putIfPresent(cache, KEY_CATEGORY, wfNode.getCategory());
            putIfPresent(cache, KEY_FORM_KEY, wfNode.getFormKey());
            putIfPresent(cache, KEY_PRIORITY, String.valueOf(wfNode.getPriority()));
            putIfPresent(cache, KEY_DUE_DATE_DURATION, wfNode.getDueDateDuration());
            putIfPresent(cache, KEY_REQUIRED_VARIABLES, join(wfNode.getRequiredVariables()));
            putIfPresent(cache, KEY_SCRIPT, wfNode.getScript());
            putIfPresent(cache, KEY_MESSAGE_NAME, wfNode.getMessageName());
            putIfPresent(cache, KEY_CALLED_ELEMENT, wfNode.getCalledElementKey());
            putIfPresent(cache, KEY_RESULT_EXPRESSION, wfNode.getResultExpression());
            putIfPresent(cache, KEY_DELEGATE_CLASS, wfNode.getDelegateClass());
            putIfPresent(cache, KEY_DELEGATE_EXPRESSION, wfNode.getDelegateExpression());
            node.setCache(cache);
            nodes.add(node);
            byId.put(node.getNodeId(), node);
        }

        // 定义级分类：按约定挂到起始节点，转回来时能读回 definition.category
        WfNode start = findStartNode(definition);
        if (start != null && definition.getCategory() != null) {
            byId.get(start.getId()).getCache().put(KEY_DEFINITION_CATEGORY, definition.getCategory());
        }

        // ---- 连线：写出线到 post、入线到 pre ----
        for (WfFlow flow : definition.getFlows()) {
            WorkflowNode source = byId.get(flow.getSourceRef());
            WorkflowNode target = byId.get(flow.getTargetRef());
            if (source == null || target == null) {
                throw new WfDefinitionException("流程 " + definition.getKey() + " 的连线 "
                        + flow.getId() + " 指向不存在的节点: " + flow.getSourceRef()
                        + " -> " + flow.getTargetRef());
            }
            // post 必须允许重复：BPMN 允许同一对节点之间有多条并列的 sequenceFlow
            // （典型是"一条带条件的边 + 一条 default 边"），邻接表用重数承载这个信息。
            // 这里若去重，往返就会把一条边悄悄吞掉 —— 拓扑变了而没人知道。
            source.getConnector().getPost().add(flow.getTargetRef());
            if (!target.getConnector().getPre().contains(flow.getSourceRef())) {
                target.getConnector().getPre().add(flow.getSourceRef());
            }

            if (flow.getConditionExpression() != null
                    && !flow.getConditionExpression().trim().isEmpty()) {
                Map<String, String> cache = target.getCache();
                Integer degree = inDegree.get(flow.getTargetRef());
                if (degree != null && degree <= 1) {
                    // 内存引擎只认 invokeParameter：单入度时两处都写，兼顾引擎兼容与本桥还原
                    target.setInvokeParameter(flow.getConditionExpression());
                    cache.put(KEY_CONDITION, flow.getConditionExpression());
                } else {
                    cache.put(KEY_CONDITION_BY_SOURCE_PREFIX + flow.getSourceRef(),
                            flow.getConditionExpression());
                }
            }
            if (flow.isDefaultFlow()) {
                source.getCache().put(KEY_DEFAULT_FLOW, flow.getTargetRef());
            }
        }

        WorkflowConfiguration config = new WorkflowConfiguration();
        config.setWorkflowNodeList(nodes);
        return config;
    }

    // ==================== 内部工具 ====================

    private static String findStartId(List<WfNode> nodes, List<WfFlow> flows) {
        Set<String> targets = new HashSet<String>();
        for (WfFlow flow : flows) {
            targets.add(flow.getTargetRef());
        }
        for (WfNode node : nodes) {
            if (node.getType() == WfNodeType.START_EVENT) {
                return node.getId();
            }
        }
        for (WfNode node : nodes) {
            if (!targets.contains(node.getId())) {
                return node.getId();
            }
        }
        return null;
    }

    private static WfNode findStartNode(WfDefinition definition) {
        String id = findStartId(new ArrayList<WfNode>(definition.nodeNodes()),
                definition.getFlows());
        return id == null ? null : definition.node(id);
    }

    /**
     * 节点类型 → 内存引擎的 serviceUnit 名。沿用 {@code BpmnModelConverter.getServiceUnit}
     * 的取值，保证两侧派发到同一个 handler。
     */
    private static String serviceUnitOf(WfNodeType type) {
        if (type == null) {
            return "empty";
        }
        switch (type) {
            case USER_TASK:
                return "userTaskHandler";
            case SERVICE_TASK:
                return "serviceTaskHandler";
            case SCRIPT_TASK:
                return "scriptTaskHandler";
            case MANUAL_TASK:
                return "manualTaskHandler";
            case SEND_TASK:
                return "sendTaskHandler";
            case RECEIVE_TASK:
                return "receiveTaskHandler";
            case EXCLUSIVE_GATEWAY:
            case INCLUSIVE_GATEWAY:
                return "gatewayHandler";
            case PARALLEL_GATEWAY:
                return "parallelGatewayHandler";
            case CALL_ACTIVITY:
                return "callActivityHandler";
            case START_EVENT:
            case END_EVENT:
                return "eventHandler";
            default:
                return "empty";
        }
    }

    private static boolean isReservedCacheKey(String key) {
        return KEY_ASSIGNEE.equals(key)
                || KEY_CANDIDATE_USERS.equals(key)
                || KEY_CANDIDATE_GROUPS.equals(key)
                || KEY_CATEGORY.equals(key)
                || KEY_FORM_KEY.equals(key)
                || KEY_PRIORITY.equals(key)
                || KEY_DUE_DATE_DURATION.equals(key)
                || KEY_REQUIRED_VARIABLES.equals(key)
                || KEY_SCRIPT.equals(key)
                || KEY_MESSAGE_NAME.equals(key)
                || KEY_CALLED_ELEMENT.equals(key)
                || KEY_RESULT_EXPRESSION.equals(key)
                || KEY_DELEGATE_CLASS.equals(key)
                || KEY_DELEGATE_EXPRESSION.equals(key)
                || KEY_CONDITION.equals(key)
                || KEY_DEFAULT_FLOW.equals(key)
                || KEY_CONDITION_BY_SOURCE_PREFIX.equals(key)
                || "implementation".equals(key)
                || KEY_DEFINITION_CATEGORY.equals(key);
    }

    private static void putIfPresent(Map<String, String> cache, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            cache.put(key, value);
        }
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(value.trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static List<String> splitList(String csv) {
        List<String> result = new ArrayList<String>();
        if (csv == null || csv.trim().isEmpty()) {
            return result;
        }
        for (String item : Arrays.asList(csv.split(","))) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
