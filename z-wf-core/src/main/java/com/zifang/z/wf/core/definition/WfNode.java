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

    /** 扩展属性。 */
    private Map<String, Object> properties = new HashMap<>();

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
