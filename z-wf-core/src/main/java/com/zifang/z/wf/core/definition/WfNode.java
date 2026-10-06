package com.zifang.z.wf.core.definition;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程节点定义 —— 流程图的顶点。
 *
 * <p>节点类型决定运行时行为（见 {@link WfNodeType}），字段承载该行为所需的配置：
 * <ul>
 *   <li>{@code userTask}：{@link #assignee} / {@link #candidateGroups} / {@link #candidateUsers} / {@link #formKey}</li>
 *   <li>{@code serviceTask}：{@link #delegateClass} 或 {@link #delegateExpression}</li>
 *   <li>{@code scriptTask}：{@link #script}（EL 表达式）</li>
 *   <li>{@code receiveTask}：{@link #messageName}（关联外部消息）</li>
 *   <li>{@code exclusiveGateway} / {@link #inclusiveGateway}：默认流由连线的 {@code defaultFlow} 决定</li>
 * </ul>
 *
 * @author zifang
 */
public class WfNode implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 节点 ID —— 流程定义内唯一。 */
    private String id;

    /** 节点名称（待办列表、轨迹展示用）。 */
    private String name;

    /** 节点类型。 */
    private WfNodeType type = WfNodeType.TASK;

    /** 节点分类（业务分组，如 "审批" / "通知"），供待办筛选。 */
    private String category;

    /** 表单编码，userTask 关联审批表单。 */
    private String formKey;

    /** 默认办理人（用户 ID）。 */
    private String assignee;

    /** 候选人用户 ID 集合 —— 可认领。 */
    private List<String> candidateUsers = new ArrayList<>();

    /** 候选组集合 —— 可认领。 */
    private List<String> candidateGroups = new ArrayList<>();

    /** 任务创建时的优先级，默认 {@link #DEFAULT_PRIORITY}。 */
    private int priority = DEFAULT_PRIORITY;

    /**
     * 任务相对时长（ISO-8601 duration，如 {@code PT24H}），
     * 相对于流程实例启动时间或上游任务完成时间计算到期时刻；为空表示不限期。
     */
    private String dueDateDuration;

    /** 任务完成所需变量（全部存在才允许完成），用于"填完表单才能提交"。 */
    private List<String> requiredVariables = new ArrayList<>();

    /** serviceTask：delegate 实现类的全限定名。 */
    private String delegateClass;

    /** serviceTask：delegate Bean 的 EL 表达式（优先于 {@link #delegateClass}）。 */
    private String delegateExpression;

    /** scriptTask：EL 表达式脚本。 */
    private String script;

    /** receiveTask：关联的消息名（外部 trigger 时按此匹配）。 */
    private String messageName;

    /**
     * 信号名。信号边界事件（{@code signalEventDefinition}）专用。
     *
     * <p>与 {@link #messageName} 分开而不是共用一个字段：消息是"发给某个实例"的，
     * 信号是"广播给所有订阅者"的，两者匹配规则不同，共用一个字段迟早写混。
     */
    private String signalName;

    /**
     * 外部任务主题名。给了 topic 的 serviceTask 不在引擎里执行，
     * 而是停在这一步等外部 worker 领走。
     *
     * <p>用 serviceTask + 扩展属性而不是新增 BPMN 元素 {@code <externalTask>}：
     * 那不是 BPMN 2.0 的元素，Camunda 也是靠 {@code camunda:type="external"} 标注在
     * serviceTask 上。新造一个元素类型会让从 Camunda/Flowable 导出的 XML 全都认不出。
     */
    private String topic;

    // ==================== 复杂网关 ====================

    /**
     * 复杂网关的判别变量（通常是 {@code ${变量名}}）。
     *
     * <p>引擎取它的<b>值</b>，再去出线上找 {@code caseValue} 相同的线。
     * 留空时部署期报 ERROR —— 没有它就退化成"永远走默认线"的死网关，
     * 而流程照样能跑完，只是永远走同一条路。
     */
    private String caseVariable;

    // ==================== 异步执行（asyncBefore / asyncAfter） ====================

    /**
     * 进入这个节点前先挂起：token 到达即挂 job，节点本身<b>还没执行</b>。
     *
     * <p>续跑时要<b>进入</b>这个节点（把它的行为真正跑一遍），与 {@link #asyncAfter}
     * 的续跑方向正好相反 —— 这是两种模式必须分开的原因，也是它们用不同
     * {@link com.zifang.z.wf.core.model.WfJobType} 的原因。
     */
    private boolean asyncBefore;

    /**
     * 这个节点执行完之后、离开之前挂起：节点已经跑过了，token 停在本节点上等 job。
     *
     * <p>续跑时才真正离开本节点、沿出线前进。挂在 userTask 上时，
     * 意味着"人已经办完了，但流程还要在离开这一步前排一次队"。
     */
    private boolean asyncAfter;

    /** callActivity / subProcess：被引用的流程定义 key。 */
    private String calledElementKey;

    /** 流程结束事件的流程结果表达式（决定流程实例的 outcome，如 approved / rejected）。 */
    private String resultExpression;

    /**
     * 结果写进哪个变量名。
     *
     * <p>与 {@link #resultExpression} 是一对且<b>缺一不可</b>：
     * 前者说"写进哪"，后者说"怎么算"。
     * 不做成一个属性是因为 {@code ${a+b}} 与 {@code a+b} 从字符串形状上
     * 分不出"要算的"和"变量名"，猜错会把值静默写进没人读的变量。
     */
    private String resultVariable;

    /** 扩展属性。 */
    private Map<String, Object> properties = new HashMap<>();

    // ==================== 错误 / 边界事件 ====================

    /**
     * 错误码，对应 BPMN 的 {@code errorEventDefinition/@errorRef}。
     *
     * <p>空字符串在 BPMN 里表示"捕获所有错误"，本实现<b>刻意不支持</b>：
     * 宽泛捕获会把不相关的异常也吸走，让本该崩的流程继续走下去。
     * 必须显式写明捕获哪一种错误。
     */
    private String errorCode;

    /**
     * 非中断型边界事件（{@code cancelActivity="false"}）。
     *
     * <p>区别只有一处，但那一处是全部：触发时<b>宿主 token 不动</b>。
     * 中断型是把宿主那条 token 搬到边界事件上（宿主待办作废、token 走补偿分支），
     * 非中断型是<b>另起一条 token</b>从边界事件出发，宿主照常办理，
     * 两条路径在下游的汇合点碰头。
     *
     * <p>实现上并不需要"另一套状态"：边界订阅的存活期本来就是"宿主活跃期间"，
     * 中断型与非中断型在这一点上完全一样。真正要做的是<b>不搬 token</b>，
     * 以及让汇合判定认得这两条是同一批（靠 parentId，见
     * {@code WfEngine#samePeer}）。
     */
    private boolean nonInterrupting;

    /**
     * {@code parallelMultiple="true"}：同一事件可以重复触发。
     *
     * <p>本实现只支持<b>单次触发</b>，所以识别出来是为了报 ERROR 而不是静默按
     * 单次跑 —— 作者写"每来一次就催一遍"而实际只催一次，是那种几个月后
     * 才被人发现的偏差。
     */
    private boolean parallelMultiple;

    /**
     * 边界事件挂在哪个节点上（{@code attachedToRef}）。
     * 只有 {@link WfNodeType#BOUNDARY_EVENT} 会用。
     */
    private String attachedToRef;

    /**
     * 定时器边界的触发时刻怎么算，见 {@link WfTimerType}。
     *
     * <p>为 null 表示这个边界事件不是定时器边界。
     *
     * <p>与 {@link #errorCode} 是<b>互斥</b>的两条触发路径：BPMN 里一个
     * boundaryEvent 只会挂一种 eventDefinition。解析时若两者都出现，
     * 以 {@code error} 为准并让校验器报 ERROR —— 不静默挑一个。
     */
    private WfTimerType timerType;

    /**
     * 定时器表达式：
     * <ul>
     *   <li>{@link WfTimerType#DURATION} —— ISO-8601 时长，如 {@code PT5M}，
     *       支持 D/H/M/S 与它们的组合；此处也可写 {@code ${变量}} 形式的流程变量</li>
     *   <li>{@link WfTimerType#DATE} —— ISO-8601 时刻，如 {@code 2026-12-31T18:00:00Z}</li>
     *   <li>{@link WfTimerType#CYCLE} —— ISO-8601 循环周期，如 {@code R3/PT10M}；
     *       本实现<b>不支持</b>循环定时器，解析出来只为给出可操作的报错</li>
     * </ul>
     */
    private String timerExpression;

    public WfTimerType getTimerType() {
        return timerType;
    }

    public void setTimerType(WfTimerType timerType) {
        this.timerType = timerType;
    }

    public String getTimerExpression() {
        return timerExpression;
    }

    public void setTimerExpression(String timerExpression) {
        this.timerExpression = timerExpression;
    }

    /** 这个边界事件是否由定时器触发。 */
    public boolean isTimerBoundary() {
        return timerType != null;
    }

    public boolean isNonInterrupting() {
        return nonInterrupting;
    }

    public void setNonInterrupting(boolean nonInterrupting) {
        this.nonInterrupting = nonInterrupting;
    }

    public boolean isParallelMultiple() {
        return parallelMultiple;
    }

    public void setParallelMultiple(boolean parallelMultiple) {
        this.parallelMultiple = parallelMultiple;
    }

    /** 消息边界（{@code messageEventDefinition}）。 */
    public boolean isMessageBoundary() {
        return signalName == null && !isBlank(messageName);
    }

    /** 信号边界（{@code signalEventDefinition}）。 */
    public boolean isSignalBoundary() {
        return !isBlank(signalName);
    }

    /** 三种边界里任意一种（都是"宿主停着时可能被外部打断"）。 */
    public boolean isEventBoundary() {
        return isTimerBoundary() || isMessageBoundary() || isSignalBoundary();
    }

    // ==================== 事件定义（边界事件与中间捕获事件共用） ====================

    /**
     * 消息事件定义（{@code messageEventDefinition}）。
     *
     * <p>与 {@link #isMessageBoundary()} 分开：后者额外要求"没有信号"，那是
     * <b>边界</b>事件的取舍（两种定义同时出现时边界事件只认信号），
     * 而这里要回答的是"这个节点等的是不是一条消息"，与它挂在哪儿无关。
     */
    public boolean isMessageEvent() {
        return !isBlank(messageName);
    }

    /** 信号事件定义（{@code signalEventDefinition}）。 */
    public boolean isSignalEvent() {
        return !isBlank(signalName);
    }

    /** 定时器事件定义（{@code timerEventDefinition}）。 */
    public boolean isTimerEvent() {
        return timerType != null;
    }

    /**
     * 本节点是否带任意一种事件定义 —— 也就是"它在等什么"。
     *
     * <p>中间捕获事件靠它决定该挂哪种订阅；校验器靠它判断一个没有事件定义的
     * 捕获事件是不是"永远等不到"，那等价于一条死路。
     */
    public boolean hasEventDefinition() {
        return isMessageEvent() || isSignalEvent() || isTimerEvent();
    }

    /**
     * 本引擎当前能真的等住的<b>独立</b>捕获事件种类（不是事件网关的分支）。
     *
     * <p><b>只有消息与信号</b>。独立的定时器捕获事件（流程里直接写一个
     * "等 5 分钟再继续"的中间捕获事件）要等 {@code duedate} 到点、由扫描器捞起来，
     * 而那条续跑路径要分清"边界事件的宿主"与"网关分支"，多一个未支持的形态
     * 就多一处可能挂错。与其那样，不如在部署期明确拒绝。
     *
     * <p>定时器<b>作为事件网关的分支</b>是支持的，那是另一个判定：
     * {@link #isSupportedGatewayBranch()}。
     */
    public boolean isSupportedCatchEvent() {
        return isMessageEvent() || isSignalEvent();
    }

    /**
     * 本引擎能真的等住的事件网关分支种类。
     *
     * <p>比 {@link #isSupportedCatchEvent()} 多了定时器：网关分支的定时器到期时
     * 走的是<b>竞速</b>语义（它赢了，其余分支作废），
     * 而不是"到点就往下走" —— 后者要判断这条捕获事件是不是孤立的，
     * 而这个判断在部署期（{@code WfDefinitionValidator}）与引擎侧（{@code WfEngine}）
     * 都要做一遍，放在节点上就等于把"是不是网关分支"这件事藏进了节点类型里。
     *
     * <p>所以这里<b>只回答"事件定义本身认不认识"</b>，
     * "是不是网关分支"由 {@code WfDefinition#gatewayOf} 回答。
     */
    public boolean isSupportedGatewayBranch() {
        return isMessageEvent() || isSignalEvent() || isTimerEvent();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getAttachedToRef() {
        return attachedToRef;
    }

    public void setAttachedToRef(String attachedToRef) {
        this.attachedToRef = attachedToRef;
    }

    /** 是否为该错误码的边界事件；errorCode 不区分大小写。 */
    public boolean catchesError(String code) {
        return errorCode != null && !errorCode.trim().isEmpty()
                && errorCode.trim().equalsIgnoreCase(code == null ? "" : code.trim());
    }

    // ==================== 多实例（会签 / 或签 / 计数） ====================

    /** 是否多实例节点。 */
    private boolean multiInstance;

    /**
     * 实例个数：字面量数字或 {@code ${}} 表达式。
     *
     * <p>与集合迭代（{@link #loopCollection}）<b>二选一</b>，两者都配时报 ERROR。
     * 会签"3 个人"与"3 个候选人"在实现上是同一件事，但写错成另一个的作者
     * 会拿到一个他没预期的流程 —— 而这里猜不出来该听谁的，所以不猜。
     */
    private String loopCardinality;

    /**
     * 集合迭代：指向流程变量里的一个集合，逐个元素展开。
     *
     * <p>与 {@link #loopCardinality} 二选一。配了它之后<b>实例数由集合大小决定</b>，
     * 不再由作者手写 —— 这正是"会签部门所有领导"这类流程需要的写法，
     * 而 {@code loopCardinality} 要求作者先知道人数。
     *
     * <p>值可以是 {@code ${变量}} 表达式，也可以直接写变量名。
     * 求值结果必须<b>是集合或数组</b>；求不出、类型不对都按 fail-closed 报错，
     * 不当空集合处理 —— 空集合会让流程直接跳过整个会签节点往下走，
     * 而"取不到人"和"确实没人"在业务上是两件事。
     */
    private String loopCollection;

    /**
     * 集合迭代时，当前元素绑定的<b>局部变量名</b>。
     *
     * <p>不配就不绑定元素，只按集合大小展开（退化成"按个数展开"，
     * 而那个 {@link #loopCardinality} 就能写，不必绕这一圈）。
     *
     * <p>绑的是<b>元素原值</b>而不是它的字符串形式：集合里放的是对象时，
     * 拿到对象才能引用它的字段。与此同时 {@code loopAssignee} 仍然拿到
     * 字符串形式（{@code String.valueOf(element)}），
     * 于是 {@code zifang:assignee="${loopAssignee}"} 两种写法都能用。
     */
    private String loopElement;

    /**
     * 完成条件（会签的判定式）。
     *
     * <p>为空 = <b>全部实例都办完才算完成</b>（会签）。
     * 写 {@code ${nrOfCompletedInstances >= 1}} = 或签；
     * {@code ${nrOfCompletedInstances >= 2}} = 计数会签。
     *
     * <p>可用变量：{@code loopCounter} / {@code nrOfInstances} /
     * {@code nrOfActiveInstances} / {@code nrOfCompletedInstances}。
     * 校验器会检查表达式里至少出现一个，否则报 ERROR ——
     * 写错变量名（比如 {@code nrOfCompleted}）会因 fail-closed 判为 false，
     * 于是流程永远等不到"完成"而卡死，且没有任何报错。
     */
    private String completionCondition;

    /**
     * 逐个串行执行。
     *
     * <p>本版<b>不支持</b>，配了会在部署期报 ERROR。
     * 原因不是"难做"，而是串行会签与并行会签的完成判定不同
     * （串行每次只激活一个实例），做成半套会比不做更危险。
     */
    private boolean sequential;

    /**
     * 每个实例的办理人列表变量。
     *
     * <p>值为流程变量里的一个集合。分叉第 i 个实例时，
     * 会把 {@code list.get(i)} 写进该 token 的局部变量 {@code loopAssignee}，
     * 于是流程定义里写 {@code zifang:assignee="${loopAssignee}"} 即可逐实例派不同人。
     *
     * <p>为什么不给 {@code ${approvers[loopCounter]}} 这种写法：
     * 实测 z-util 的 EL <b>不支持变量下标</b>（{@code approvers[1]} 可以，
     * {@code approvers[loopCounter]} 抛 ElException）。把索引求值挪到分叉时用
     * Java 做掉，比在表达式引擎里补一个索引解析更可控。
     *
     * <p>与 {@link #loopCollection} 的差别只有一处：{@code collection} 还能通过
     * {@link #loopElement} 把元素<b>原值</b>绑给办理表达式，并且它决定实例数；
     * 这里只取办理人，实例数仍由 {@link #loopCardinality} 给。
     * 保留它是因为已有流程定义在用它。
     */
    private String loopAssignees;

    public String getCaseVariable() {
        return caseVariable;
    }

    public void setCaseVariable(String caseVariable) {
        this.caseVariable = caseVariable;
    }

    public boolean isAsyncBefore() {
        return asyncBefore;
    }

    public void setAsyncBefore(boolean asyncBefore) {
        this.asyncBefore = asyncBefore;
    }

    public boolean isAsyncAfter() {
        return asyncAfter;
    }

    public void setAsyncAfter(boolean asyncAfter) {
        this.asyncAfter = asyncAfter;
    }

    /** 这个节点是否要异步（任一方向）。 */
    public boolean isAsync() {
        return asyncBefore || asyncAfter;
    }

    public boolean isMultiInstance() {
        return multiInstance;
    }

    public void setMultiInstance(boolean multiInstance) {
        this.multiInstance = multiInstance;
    }

    public String getLoopCardinality() {
        return loopCardinality;
    }

    public void setLoopCardinality(String loopCardinality) {
        this.loopCardinality = loopCardinality;
    }

    public String getCompletionCondition() {
        return completionCondition;
    }

    public void setCompletionCondition(String completionCondition) {
        this.completionCondition = completionCondition;
    }

    public boolean isSequential() {
        return sequential;
    }

    public void setSequential(boolean sequential) {
        this.sequential = sequential;
    }

    public String getLoopCollection() {
        return loopCollection;
    }

    public void setLoopCollection(String loopCollection) {
        this.loopCollection = loopCollection;
    }

    public String getLoopElement() {
        return loopElement;
    }

    public void setLoopElement(String loopElement) {
        this.loopElement = loopElement;
    }

    public String getLoopAssignees() {
        return loopAssignees;
    }

    public void setLoopAssignees(String loopAssignees) {
        this.loopAssignees = loopAssignees;
    }

    /** 默认优先级。 */
    public static final int DEFAULT_PRIORITY = 50;

    /**
     * {@link #properties} 里标记"这个 TASK 是从不支持的 BPMN 元素退化来的"的键。
     *
     * <p>值为原始 BPMN 元素名（如 {@code eventBasedGateway}）。
     * 由 {@code WfXmlParser} 在退化时写入，由 {@code WfDefinitionValidator} 读出来报 ERROR。
     *
     * <p>为什么需要这个标记：解析器对认不出的元素名会退化成 {@link WfNodeType#TASK}，
     * 这样设计器导出的扩展类型不会让整份定义解析失败。但"退化成通用任务"对
     * {@code task} 是合理的，对 {@code eventBasedGateway} 却是把流程语义换掉了。
     * 光看 type=TASK 无法区分这两者，所以退化时必须留下原名。
     */
    public static final String PROPERTY_UNSUPPORTED_BPMN_ELEMENT = "zifang:unsupportedBpmnElement";

    /**
     * {@link #properties} 里标记"这个节点不是 {@code <process>} 的直接子节点，
     * 而是被嵌在某个容器元素（目前只有 {@code subProcess}）里"的键。
     *
     * <p>值为外层容器元素的 id，没有则不存在。
     *
     * <p>存在的理由：解析结果是<b>扁平节点表</b>，父子关系在收表那一刻就没了。
     * 而"这个节点嵌在 subProcess 里"恰恰是判断它会不会被执行的关键依据 ——
     * 丢了这条信息，校验器就看不出哪些内联节点永远跑不到。
     */
    public static final String PROPERTY_NESTED_IN = "zifang:nestedIn";

    /**
     * 本节点被嵌在哪个容器元素里。
     *
     * @return 外层容器 id；是 {@code <process>} 的直接子节点则返回 {@code null}
     */
    public String nestedIn() {
        Object value = property(PROPERTY_NESTED_IN);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 本节点是否由不支持的 BPMN 元素退化而来。
     *
     * @return 退化来源的原始元素名；本就是原生类型则返回 {@code null}
     */
    public String unsupportedBpmnElement() {
        Object value = property(PROPERTY_UNSUPPORTED_BPMN_ELEMENT);
        return value == null ? null : String.valueOf(value);
    }

    public WfNode() {
    }

    public WfNode(String id, String name, WfNodeType type) {
        this.id = id;
        this.name = name;
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public WfNodeType getType() {
        return type;
    }

    public void setType(WfNodeType type) {
        this.type = type == null ? WfNodeType.TASK : type;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getFormKey() {
        return formKey;
    }

    public void setFormKey(String formKey) {
        this.formKey = formKey;
    }

    public String getAssignee() {
        return assignee;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }

    public List<String> getCandidateUsers() {
        return candidateUsers;
    }

    public void setCandidateUsers(List<String> candidateUsers) {
        this.candidateUsers = candidateUsers == null ? new ArrayList<String>() : candidateUsers;
    }

    public List<String> getCandidateGroups() {
        return candidateGroups;
    }

    public void setCandidateGroups(List<String> candidateGroups) {
        this.candidateGroups = candidateGroups == null ? new ArrayList<String>() : candidateGroups;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getDueDateDuration() {
        return dueDateDuration;
    }

    public void setDueDateDuration(String dueDateDuration) {
        this.dueDateDuration = dueDateDuration;
    }

    public List<String> getRequiredVariables() {
        return requiredVariables;
    }

    public void setRequiredVariables(List<String> requiredVariables) {
        this.requiredVariables = requiredVariables == null ? new ArrayList<String>() : requiredVariables;
    }

    public String getDelegateClass() {
        return delegateClass;
    }

    public void setDelegateClass(String delegateClass) {
        this.delegateClass = delegateClass;
    }

    public String getDelegateExpression() {
        return delegateExpression;
    }

    public void setDelegateExpression(String delegateExpression) {
        this.delegateExpression = delegateExpression;
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }

    public String getMessageName() {
        return messageName;
    }

    public void setMessageName(String messageName) {
        this.messageName = messageName;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    /** 是否是"交给外部 worker 做的一步"。 */
    public boolean isExternalStep() {
        return topic != null && !topic.trim().isEmpty();
    }

    public String getSignalName() {
        return signalName;
    }

    public void setSignalName(String signalName) {
        this.signalName = signalName;
    }

    public String getCalledElementKey() {
        return calledElementKey;
    }

    public void setCalledElementKey(String calledElementKey) {
        this.calledElementKey = calledElementKey;
    }

    public String getResultExpression() {
        return resultExpression;
    }

    public void setResultExpression(String resultExpression) {
        this.resultExpression = resultExpression;
    }

    public String getResultVariable() {
        return resultVariable;
    }

    public void setResultVariable(String resultVariable) {
        this.resultVariable = resultVariable;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties == null ? new HashMap<String, Object>() : properties;
    }

    /**
     * 读取扩展属性（供 {@link #getDelegateExpression()} 这类"属性即配置"的场景）。
     */
    public Object property(String key) {
        return properties == null ? null : properties.get(key);
    }

    /**
     * 是否可被认领（有候选人）。
     */
    public boolean isClaimable() {
        return !candidateUsers.isEmpty() || !candidateGroups.isEmpty();
    }

    @Override
    public String toString() {
        return "WfNode{" + id + " " + type + " '" + name + "'}";
    }
}
