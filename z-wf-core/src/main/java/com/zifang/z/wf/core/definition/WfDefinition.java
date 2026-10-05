package com.zifang.z.wf.core.definition;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程定义 —— 一张完整的流程图（节点 + 连线 + 索引）。
 *
 * <p><b>这是 z-util-wf 与 z-wf 共用的协议载体。</b>
 * z-util-wf 侧（内存引擎）把它转成 {@code WorkflowConfiguration} + {@code WorkflowNode} DAG；
 * z-wf 侧（生产引擎）保留本对象并挂上 repository/history。
 * 两条路径读同一份 {@link #toXml()} / {@link #toJson()} 产物，落到同一套节点语义。
 *
 * <p>构造后索引（{@link #nodeMap} / {@link #outgoing} / {@link #incoming}）一次性建好并保持不变 ——
 * 流程定义是<b>不可变</b>的。改定义必须走"部署新版本"而不是原地改，否则运行中的实例
 * 会看到半路变化的图。版本化由 {@code WfRepositoryService} 负责。
 *
 * @author zifang
 */
public class WfDefinition implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 定义 key —— 业务侧启动流程用的稳定标识（如 {@code leaveProcess}）。 */
    private String key;

    /** 定义名称。 */
    private String name;

    /** 版本号，由 repository 分配。 */
    private int version = 1;

    /** 分类（对应 z-camuda 的 Category，用于流程分组）。 */
    private String category;

    /** 描述。 */
    private String description;

    /** 流程图上的节点（保持声明顺序，便于设计器回显）。 */
    private List<WfNode> nodes = new ArrayList<>();

    /** 流程图上的连线。 */
    private List<WfFlow> flows = new ArrayList<>();

    /** 原始 XML（部署时留存，供导出与审计）。 */
    private String sourceXml;

    /** 部署时间。 */
    private java.util.Date startTime;

    /**
     * 是否已停用。停用后<b>不能启动新实例</b>，已在跑的实例不受影响。
     *
     * <p>对应 Camunda 的 {@code suspensionState}。语义刻意收窄成布尔而不是三态
     * （ACTIVE / SUSPENDED / SUSPENDED_EXTERNALLY）：本引擎没有"由外部系统挂起"的
     * 概念，硬凑三态只会给出一个永远没人写的值。
     *
     * <p><b>真源只有 ZWF_DEFINITION.SUSPENDED 这一列</b>，不进
     * {@code WfDefinitionCodec}：列与图 JSON 各存一份必然会漂，而漂了以后
     * "停用了还能启动"这种问题极难定位。
     */
    private boolean suspended;

    // ---- 索引（构造时建立，序列化时忽略） ----

    private transient Map<String, WfNode> nodeMap;
    private transient Map<String, List<WfFlow>> outgoing;
    private transient Map<String, List<WfFlow>> incoming;

    public WfDefinition() {
    }

    public WfDefinition(String key, String name) {
        this.key = key;
        this.name = name;
    }

    /**
     * 建索引。节点 / 连线列表变更后必须重新调用。
     *
     * <p>只做结构建索引，不做合法性校验 —— 校验是 {@link WfDefinitionValidator} 的职责，
     * 这里保持"能建索引就能用"，避免解析期就要求图已经合法（设计器保存半成品图时也允许 parse 成功）。
     */
    public WfDefinition buildIndex() {
        this.nodeMap = new LinkedHashMap<>();
        this.outgoing = new HashMap<>();
        this.incoming = new HashMap<>();

        if (nodes != null) {
            for (WfNode node : nodes) {
                if (node != null && node.getId() != null) {
                    nodeMap.put(node.getId(), node);
                }
            }
        }
        if (flows != null) {
            for (WfFlow flow : flows) {
                if (flow == null || flow.getSourceRef() == null || flow.getTargetRef() == null) {
                    continue;
                }
                List<WfFlow> out = outgoing.get(flow.getSourceRef());
                if (out == null) {
                    out = new ArrayList<>();
                    outgoing.put(flow.getSourceRef(), out);
                }
                out.add(flow);

                List<WfFlow> in = incoming.get(flow.getTargetRef());
                if (in == null) {
                    in = new ArrayList<>();
                    incoming.put(flow.getTargetRef(), in);
                }
                in.add(flow);
            }
        }
        return this;
    }

    /**
     * 取索引（必要时惰性建立，使反序列化后的对象可直接使用）。
     */
    private Map<String, WfNode> nodeMap() {
        if (nodeMap == null) {
            buildIndex();
        }
        return nodeMap;
    }

    private Map<String, List<WfFlow>> outgoing() {
        if (outgoing == null) {
            buildIndex();
        }
        return outgoing;
    }

    private Map<String, List<WfFlow>> incoming() {
        if (incoming == null) {
            buildIndex();
        }
        return incoming;
    }

    /**
     * 按 ID 取节点。
     *
     * @return 节点；不存在返回 {@code null}
     */
    public WfNode node(String nodeId) {
        return nodeId == null ? null : nodeMap().get(nodeId);
    }

    /**
     * 挂在某个节点上的<b>定时器</b>边界事件。
     *
     * <p>与 {@code WfRuntimeService#findErrorBoundary}（按错误码找单个）是两类触发：
     * 错误边界靠出错时路由，定时器边界靠时间到了自动触发。
     *
     * <p>返回列表而非单个：同一个节点上可以挂多个定时器边界
     * （比如"1 小时提醒"和"24 小时升级"），它们各自起表、各自到期。
     * 解析期不限制数量 —— 数量该不该有限制是业务问题，引擎只负责都做对。
     */
    public List<WfNode> timerBoundariesOf(String hostNodeId) {
        List<WfNode> result = new ArrayList<>();
        if (hostNodeId == null || getNodes() == null) {
            return result;
        }
        for (WfNode candidate : getNodes()) {
            if (candidate.getType() == WfNodeType.BOUNDARY_EVENT
                    && candidate.isTimerBoundary()
                    && hostNodeId.equals(candidate.getAttachedToRef())) {
                result.add(candidate);
            }
        }
        return result;
    }

    /**
     * 出线（保持声明顺序 —— 排他网关的求值顺序依赖它）。
     */
    public List<WfFlow> outgoingFlows(String nodeId) {
        List<WfFlow> list = outgoing().get(nodeId);
        return list == null ? Collections.<WfFlow>emptyList() : list;
    }

    /**
     * 入线。并行/包容网关的汇合判断依赖它。
     */
    public List<WfFlow> incomingFlows(String nodeId) {
        List<WfFlow> list = incoming().get(nodeId);
        return list == null ? Collections.<WfFlow>emptyList() : list;
    }

    /**
     * 找出开始节点。
     *
     * <p>判定顺序：显式 {@link WfNodeType#START_EVENT} 优先；没有则退化为"无入线的节点"
     * —— 后者让最小可用定义（只写节点不写 startEvent）也能跑起来。
     *
     * @throws IllegalStateException 存在多个开始节点 —— 引擎无法判断入口，必须由校验器在部署期拦掉
     */
    public WfNode startNode() {
        List<WfNode> starts = new ArrayList<>();
        for (WfNode node : nodeNodes()) {
            if (node.getType() == WfNodeType.START_EVENT) {
                starts.add(node);
            }
        }
        if (starts.isEmpty()) {
            for (WfNode node : nodeNodes()) {
                if (incomingFlows(node.getId()).isEmpty()) {
                    starts.add(node);
                }
            }
        }
        if (starts.isEmpty()) {
            throw new IllegalStateException("流程定义 " + key + " 没有开始节点（无 startEvent 且无无入线节点）");
        }
        if (starts.size() > 1) {
            StringBuilder ids = new StringBuilder();
            for (WfNode node : starts) {
                if (ids.length() > 0) {
                    ids.append(", ");
                }
                ids.append(node.getId());
            }
            throw new IllegalStateException("流程定义 " + key + " 存在多个开始节点: " + ids);
        }
        return starts.get(0);
    }

    /**
     * 全部结束节点。
     */
    public List<WfNode> endNodes() {
        List<WfNode> ends = new ArrayList<>();
        for (WfNode node : nodeNodes()) {
            if (node.getType() == WfNodeType.END_EVENT || outgoingFlows(node.getId()).isEmpty()) {
                ends.add(node);
            }
        }
        return ends;
    }

    /**
     * 全部节点（只读的稳定视图）。
     */
    public Collection<WfNode> nodeNodes() {
        return nodes == null ? Collections.<WfNode>emptyList() : nodes;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<WfNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<WfNode> nodes) {
        this.nodes = nodes == null ? new ArrayList<WfNode>() : nodes;
        this.nodeMap = null;
        this.outgoing = null;
        this.incoming = null;
    }

    public List<WfFlow> getFlows() {
        return flows;
    }

    public void setFlows(List<WfFlow> flows) {
        this.flows = flows == null ? new ArrayList<WfFlow>() : flows;
        this.nodeMap = null;
        this.outgoing = null;
        this.incoming = null;
    }

    public String getSourceXml() {
        return sourceXml;
    }

    public void setSourceXml(String sourceXml) {
        this.sourceXml = sourceXml;
    }

    public java.util.Date getStartTime() {
        return startTime;
    }

    public void setStartTime(java.util.Date startTime) {
        this.startTime = startTime;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public void setSuspended(boolean suspended) {
        this.suspended = suspended;
    }

    @Override
    public String toString() {
        return "WfDefinition{" + key + " v" + version
                + " nodes=" + (nodes == null ? 0 : nodes.size())
                + " flows=" + (flows == null ? 0 : flows.size()) + "}";
    }
}
