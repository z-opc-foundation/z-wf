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
     * <p>不与集合迭代（{@code collection}）二选一：本版只支持按个数展开。
     * 两者都配时报 ERROR，而不是猜一个用 —— 会签"3 个人"与"3 个候选人"
     * 在实现上是同一件事，但写错成另一个的作者会拿到一个他没预期的流程。
     */
    private String loopCardinality;

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
     */
    private String loopAssignees;

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
