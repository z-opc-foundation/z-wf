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

    /**
     * 是否是<b>默认流程定义</b> —— 同一时刻全库至多一条为真。
     *
     * <p>对应 Camunda 的 {@code RepositoryService#setDefaultProcessDefinition}：
     * 调用方不知道 key 时可以问"默认那个是哪个"，不用在业务代码里硬编码一个 key。
     *
     * <p><b>默认指向的是一个特定的 (key, version)，不是"某个 key 的最新版本"</b> ——
     * 部署新版本时默认不跟着漂。理由是"默认指向哪儿"必须可预期：
     * 运营在默认流程上做的验证不该被一次无关的重新部署改掉。
     *
     * <p><b>真源只有 {@code ZWF_DEFINITION.IS_DEFAULT} 这一列</b>，理由与
     * {@link #suspended} 完全相同：列与图 JSON 各存一份必然漂。
     * 连带的好处是"删掉默认定义"不需要额外清理 —— 标记与行同生共死，
     * 不会留下一条指向不存在定义的指针。
     */
    private boolean defaultDefinition;

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
    /**
     * 挂在某节点上的<b>全部</b>边界事件（定时器 / 消息 / 信号）。
     *
     * <p>token 一进入宿主节点就要把三种订阅一起挂上：消息与信号订阅必须早于
     * 任何外部触发存在，否则"发消息时还没订阅上"会表现为消息被静默丢弃。
     */
    public List<WfNode> eventBoundariesOf(String hostNodeId) {
        List<WfNode> result = new ArrayList<>();
        if (hostNodeId == null || getNodes() == null) {
            return result;
        }
        for (WfNode candidate : getNodes()) {
            if (candidate.getType() == WfNodeType.BOUNDARY_EVENT
                    && candidate.isEventBoundary()
                    && hostNodeId.equals(candidate.getAttachedToRef())) {
                result.add(candidate);
            }
        }
        return result;
    }

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
     * 中间捕获事件所属的事件网关 —— 顺着它唯一的入线找源头。
     *
     * <p>入线不唯一时返回 {@code null}（并由调用方决定要不要报错）：
     * 认不出网关就不知道该作废哪些兄弟分支，而"把全部分支都跑一遍"
     * 正是这个功能存在理由被推翻的那种形态。
     *
     * <p><b>这里曾是三份各自抄一遍的实现</b>（校验器、运行期、订阅视图），
     * 当时注释写着"跨类抽工具类的收益抵不上逻辑一眼看得见"。
     * 加定时器分支时那份理由不再成立：判定要出现在<b>建 job</b>、<b>触发</b>、
     * <b>部署期校验</b>、<b>订阅视图</b>四处，四份副本里只要有一处改了判定，
     * 症状是"某一处认得出网关、另一处认不出"，而那不会报错，
     * 只表现为某一条分支的兄弟没被作废。所以收在这里。
     *
     * @return 所属网关；入线不唯一、或源头不是事件网关时为 {@code null}
     */
    public WfNode gatewayOf(WfNode catchEvent) {
        if (catchEvent == null
                || catchEvent.getType() != WfNodeType.INTERMEDIATE_CATCH_EVENT) {
            return null;
        }
        List<WfFlow> inFlows = incomingFlows(catchEvent.getId());
        if (inFlows.size() != 1) {
            return null;
        }
        WfNode source = node(inFlows.get(0).getSourceRef());
        return source != null && source.getType() == WfNodeType.EVENT_BASED_GATEWAY ? source : null;
    }

    /** 该捕获事件是不是一个事件网关的分支（{@link #gatewayOf} 认得出网关）。 */
    public boolean isEventGatewayBranch(WfNode catchEvent) {
        return gatewayOf(catchEvent) != null;
    }

    /**
     * 找出<b>无条件</b>的开始节点 —— 即 {@code startProcessInstanceByKey} 的入口。
     *
     * <p>判定顺序：显式 {@link WfNodeType#START_EVENT} 优先；没有则退化为"无入线的节点"
     * —— 后者让最小可用定义（只写节点不写 startEvent）也能跑起来。
     *
     * <p><b>带触发条件的起始事件不算数</b>：{@code <startEvent><messageEventDefinition
     * messageRef="orderCreated"/></startEvent>} 是"收到订单才起流程"的入口，
     * 拿它当无条件入口会让调用 {@code startProcessInstanceByKey} 的那条路
     * 也需要先满足那个条件 —— 而那条路上根本没有消息可发。
     * 所以这里按 {@link WfNode#isMessageEvent()} / {@link WfNode#isSignalEvent()} 排除，
     * 退化路径同样排除（否则一个手写的"无入线 messageEventDefinition 节点"
     * 会被当成无条件入口）。
     *
     * @throws IllegalStateException 没有无条件起始节点，或有多个 ——
     *         前者说明这个定义只能用消息/信号启动，该在部署期就说清而不是启动时炸；
     *         后者引擎无法判断入口，同样必须由校验器在部署期拦掉
     */
    public WfNode startNode() {
        List<WfNode> starts = unconditionalStartNodes();
        if (starts.isEmpty()) {
            List<WfNode> triggered = eventStartNodes();
            if (!triggered.isEmpty()) {
                throw new IllegalStateException("流程定义 " + key + " 没有无条件的开始节点，"
                        + "只能用消息/信号启动。带触发条件的起始事件: " + idsOf(triggered));
            }
            throw new IllegalStateException("流程定义 " + key + " 没有开始节点（无 startEvent 且无无入线节点）");
        }
        if (starts.size() > 1) {
            throw new IllegalStateException("流程定义 " + key + " 存在多个无条件开始节点: "
                    + idsOf(starts));
        }
        return starts.get(0);
    }

    /** 有无条件入口吗 —— {@link #startNode()} 会不会抛。 */
    public boolean hasUnconditionalStart() {
        return unconditionalStartNodes().size() == 1;
    }

    /**
     * 全部<b>无条件</b>起始节点（不加"至多一个"的约束，供校验器用）。
     *
     * <p>返回列表而不是单个：校验期要拿它和"事件起始节点"分开比对，
     * 只拿到一个的话，"有三个无条件起始"这个错误本身就没法报出来。
     *
     * <p><b>内联节点一律不算流程入口</b>（{@link #isInline}）：嵌入式
     * subProcess 里的 startEvent 是"子流程的起点"，它的入口是外层那个
     * subProcess 节点而不是这条开始事件。解析结果是扁平表，两者在"无入线"
     * 这一项上完全一样，不排除的话画一个内联子流程就会凭空多出第二个
     * 无条件入口，部署期报「存在多个无条件开始节点」——而作者无从下手，
     * 因为图上确实只有一个 process 级 startEvent。
     */
    public List<WfNode> unconditionalStartNodes() {
        List<WfNode> starts = new ArrayList<>();
        for (WfNode node : nodeNodes()) {
            if (isInline(node)) {
                continue;
            }
            if (node.getType() == WfNodeType.START_EVENT && !isEventTriggered(node)) {
                starts.add(node);
            }
        }
        if (starts.isEmpty()) {
            for (WfNode node : nodeNodes()) {
                if (isInline(node)) {
                    continue;
                }
                // 退化分支只认「无入线」，而 boundaryEvent <b>天然</b>无入线 ——
                // 它靠宿主出错 / 超时时被触发，不是流程的一步。
                // 不排除的话，任何一个带边界事件、又不写 startEvent 的定义
                // 都会凭空多出第二个无条件入口，部署期报「存在多个无条件开始节点」，
                // 而作者图上确实只画了一个入口。
                if (node.getType() == WfNodeType.BOUNDARY_EVENT) {
                    continue;
                }
                if (incomingFlows(node.getId()).isEmpty() && !isEventTriggered(node)) {
                    starts.add(node);
                }
            }
        }
        return starts;
    }

    /**
     * 全部<b>带触发条件</b>的起始节点（消息起始 / 信号起始）。
     *
     * <p>{@code startProcessInstanceByMessage} 跨定义找入口时遍历的就是它 ——
     * 走这个列表而不是全表扫节点，是为了让"哪些流程能被这条消息启动"这件事
     * 在定义上就是可枚举的，而不是靠扫描时现猜。
     */
    public List<WfNode> eventStartNodes() {
        List<WfNode> starts = new ArrayList<>();
        for (WfNode node : nodeNodes()) {
            if (isInline(node)) {
                continue;
            }
            if (node.getType() == WfNodeType.START_EVENT && isEventTriggered(node)) {
                starts.add(node);
            }
        }
        return starts;
    }

    /** 这个起始节点是被消息还是信号触发的；两者都没写才算无条件。 */
    private boolean isEventTriggered(WfNode node) {
        return node.isMessageEvent() || node.isSignalEvent();
    }

    /**
     * 按消息名找消息起始节点。
     *
     * @return 匹配的那一个；没有匹配时是 {@code null}（调用方要区分"没有这种启动方式"
     *         与"有但被歧义拦下了"，所以不抛）
     * @throws IllegalStateException 同一条消息对应了多个起始节点 ——
     *         那时"收到这条消息该起哪个流程"没有答案，必须由校验器在部署期拦掉
     */
    public WfNode messageStartNode(String messageName) {
        return findEventStartNode(messageName, true);
    }

    /** 按信号名找信号起始节点。语义与 {@link #messageStartNode} 相同。 */
    public WfNode signalStartNode(String signalName) {
        return findEventStartNode(signalName, false);
    }

    private WfNode findEventStartNode(String eventName, boolean message) {
        if (eventName == null || eventName.trim().isEmpty()) {
            return null;
        }
        String wanted = eventName.trim();
        WfNode found = null;
        List<WfNode> hits = new ArrayList<>();
        for (WfNode node : eventStartNodes()) {
            String actual = message ? node.getMessageName() : node.getSignalName();
            if (wanted.equals(actual == null ? null : actual.trim())) {
                found = node;
                hits.add(node);
            }
        }
        if (hits.size() > 1) {
            throw new IllegalStateException("流程定义 " + key + " 中消息/信号 [" + wanted
                    + "] 对应多个起始节点: " + idsOf(hits) + "。引擎无法判断该启动哪一个");
        }
        return found;
    }

    private static String idsOf(List<WfNode> nodes) {
        StringBuilder ids = new StringBuilder();
        for (WfNode node : nodes) {
            if (ids.length() > 0) {
                ids.append(", ");
            }
            ids.append(node.getId());
        }
        return ids.toString();
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

    // ==================== 嵌入式 subProcess ====================

    /**
     * 这个节点是否<b>内联</b>在某个子流程里 —— 即它属于嵌入式 subProcess 的内联子图，
     * 而不是 {@code <process>} 主图的节点。
     *
     * <p>判定依据是解析期写下的 {@link WfNode#PROPERTY_NESTED_IN}：解析结果是扁平表，
     * 父子关系不靠它无从还原。
     *
     * <p><b>但 {@code nestedIn} 不为空不等于内联</b>：解析器记的是"直接父元素"，
     * 而 {@code <boundaryEvent>} 在 BPMN 里就写在宿主活动<b>内部</b> ——
     * 挂在 userTask 上的超时边界事件，它的 nestedIn 指向那个 userTask。
     * 所以必须再看一眼容器是不是 subProcess，否则每个边界事件都会被当成
     * "嵌在某个 userTask 里的内联节点"。
     */
    public boolean isInline(WfNode node) {
        return node != null && node.nestedIn() != null
                && isSubProcessContainer(node.nestedIn());
    }

    private boolean isSubProcessContainer(String containerId) {
        WfNode container = node(containerId);
        return container != null && container.getType() == WfNodeType.SUB_PROCESS;
    }

    /**
     * 某个容器里的内联节点。
     *
     * <p><b>排除 {@link WfNodeType#BOUNDARY_EVENT}</b>：boundaryEvent 在 BPMN 里是
     * 挂在活动元素<b>内部</b>的，解析期同样给它标了 nestedIn，但它不是子流程的一步 ——
     * 它既没有入线也没有出线，算进"内联起始/结束节点"就会把子流程的入口认成超时规则。
     */
    public List<WfNode> inlineChildrenOf(String containerId) {
        List<WfNode> children = new ArrayList<>();
        if (containerId == null) {
            return children;
        }
        for (WfNode node : nodeNodes()) {
            if (node == null || node.getType() == WfNodeType.BOUNDARY_EVENT) {
                continue;
            }
            if (containerId.equals(node.nestedIn())) {
                children.add(node);
            }
        }
        return children;
    }

    /**
     * 嵌入式子流程的<b>内联起始节点</b> —— 该容器内无入线的那个节点。
     *
     * <p>内联子流程没有连线把它接到容器上（BPMN 里 {@code <sequenceFlow>} 不跨容器），
     * 引擎靠"容器内无入线的节点"来认出该从哪进入。这与 {@link #unconditionalStartNodes()}
     * 判定"流程从哪进入"用的是同一条规则，区别只在于本方法按<b>容器</b>限定范围。
     *
     * <p>刻意不返回"第一个"而返回 {@code null}：容器内出现两个无入线节点时
     * （= 两个并排的子流程入口）选哪一个都是猜，而猜错的表现是流程跑完了
     * 另一半内联节点一次都没跑。由校验器在部署期报 ERROR 挡掉。
     */
    public WfNode inlineStartNode(String containerId) {
        WfNode found = null;
        for (WfNode node : inlineChildrenOf(containerId)) {
            if (!incomingFlows(node.getId()).isEmpty()) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = node;
        }
        return found;
    }

    /**
     * 嵌入式子流程的<b>内联结束节点</b> —— 该容器内无出线的那个节点。
     *
     * <p>与 {@link #inlineStartNode} 同样按"唯一"判定：容器内出现两个无出线节点时
     * 返回 {@code null}，交由校验器报错，绝不静默挑一个（挑中的后果是
     * token 在第一个"结束"处就跳出子流程，后半段永远跑不到）。
     */
    public WfNode inlineEndNode(String containerId) {
        WfNode found = null;
        for (WfNode node : inlineChildrenOf(containerId)) {
            if (!outgoingFlows(node.getId()).isEmpty()) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = node;
        }
        return found;
    }

    /**
     * 这个节点是不是一个<b>承载内联内容</b>的嵌入式子流程。
     *
     * <p>与"类型是 SUB_PROCESS"分开：解析器把两种东西都归成 subProcess ——
     * 一种是内联的（{@code <subProcess>} 里真画了节点），另一种是空的容器配
     * {@code calledElementKey}，按 Camunda 语义等价于 callActivity。
     * 引擎只在有内联内容时改走内联执行路径，另一种继续交给
     * {@link WfNodeType#CALL_ACTIVITY} 那套行为。
     */
    public boolean isInlineSubProcess(WfNode node) {
        return node != null && node.getType() == WfNodeType.SUB_PROCESS
                && !inlineChildrenOf(node.getId()).isEmpty();
    }

    /**
     * 某个活动属于哪一段图 —— 返回它所在的内联容器 id，主图节点返回空串。
     *
     * <p>内联子流程是<b>图上的分界线</b>：内层的并行分支与外层的并行分支可能共用
     * 同一条父 token（父 token 停在那个 subProcess 节点上），但它们分属两段互不相干的图。
     * 汇合判定若只看"同父"，内层的 join 就会去等外层还没办完的分支 ——
     * 症状是"流程卡在子流程里，且看不出任何异常"。
     *
     * @return 容器 id；主图节点是空串（不是 {@code null}，好让"同属主图"是一个可比较的值）；
     *         <b>活动节点在定义里找不到时返回 {@code null}</b> —— 归属认不出来，
     *         调用方必须显式处理，不能默认它属于主图
     */
    public String inlineScopeOf(String activityId) {
        WfNode node = node(activityId);
        if (node == null) {
            return null;
        }
        String nested = node.nestedIn();
        if (nested == null) {
            return "";
        }
        WfNode container = node(nested);
        if (container == null) {
            // 认不出容器：归属不明，返回 null 让调用方显式处理，
            // 不能默认它属于主图（那会让它与主图上所有 token 混成同一批）
            return null;
        }
        if (container.getType() == WfNodeType.SUB_PROCESS) {
            return nested;
        }
        // 容器不是 subProcess ⇒ 这是挂在某个活动上的 boundaryEvent，归属按<b>宿主</b>算。
        //
        // 必须递归问宿主本人落在哪一段，而不能直接判成主图：宿主同样可能嵌在
        // 另一个 subProcess 里（"内联子流程内的活动上挂超时边界"是完全正常的写法）。
        // 直接返回 "" 会让这条边界分支与内层的其它分支判成不同段 ——
        // 症状是内层的并行汇合等不到这条分支，流程卡在子流程里。
        //
        // 递归不会成环：父链来自 DOM 的元素嵌套，XML 结构上不可能自指。
        return inlineScopeOf(container.getId());
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

    public boolean isDefaultDefinition() {
        return defaultDefinition;
    }

    public void setDefaultDefinition(boolean defaultDefinition) {
        this.defaultDefinition = defaultDefinition;
    }

    @Override
    public String toString() {
        return "WfDefinition{" + key + " v" + version
                + " nodes=" + (nodes == null ? 0 : nodes.size())
                + " flows=" + (flows == null ? 0 : flows.size()) + "}";
    }
}
