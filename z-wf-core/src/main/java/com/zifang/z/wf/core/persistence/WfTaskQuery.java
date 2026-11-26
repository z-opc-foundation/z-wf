package com.zifang.z.wf.core.persistence;

import java.util.Date;
import java.util.List;

import com.zifang.z.wf.core.model.WfTask;

/**
 * 任务查询条件。
 *
 * <p>对应审批中心的三张列表：<b>待办</b>（assignee=我 且未完成）、
 * <b>已办</b>（completer=我 且已完成）、<b>我发起的</b>（走实例查询而非任务查询）。
 *
 * <p>待办查询的语义要在实现里保持一致：
 * <ul>
 *   <li>只给 {@link #assignee} 时匹配"责任人"；<b>同时给 {@link #owner} 时是「或」</b>，
 *       不是「且」—— 委派态下活记在 owner 身上、被委派的人认领后才记在 assignee 身上。
 *       按「且」过滤的话，被委派的人在自己的待办里一条都看不到，
 *       而开发期默认用内存实现，这类偏差会一路活到上线</li>
 *   <li>只看 {@link WfTask#isOpen()} 的任务，{@code completedOnly} 时只看已完成的</li>
 *   <li>可按 {@link #candidateUsers} / {@link #candidateGroups} 查"可认领"的任务</li>
 * </ul>
 *
 * @author zifang
 */
public class WfTaskQuery {

    private String processInstanceId;

    private String definitionId;

    private String assignee;

    private String owner;

    private String completerId;

    private String category;

    private List<String> candidateUsers;

    private List<String> candidateGroups;

    private WfTask.Status status;

    /** 只查未完成任务（待办列表用）。 */
    private boolean openOnly;

    /**
     * 只查挂起 / 只查未挂起的任务。
     * {@code null} = 不限（默认）。<b>默认不过滤</b>：挂起常是"等条件成立"而非"单子不存在"，
     * 从待办里藏起来会让人以为单丢了。
     */
    private Boolean suspendedOnly;

    /**
     * 待办语义：assignee / owner / 候选用户 / 候选组<b>四者取或</b>
     * （对应 z-camuda 的 {@code taskCandidateOrAssigned}）。
     *
     * <p>为什么必须单开一个模式，不能靠同时设 assignee 与 candidateUsers 表达：
     * 那几项默认是<b>且</b>（精确筛选语义），"我是办理人但不在候选池里"这种任务
     * 会被候选条件过滤掉 —— 于是待办里反而看不到自己的单。
     * 而"谁跟我有关"这个问题本质是或，不是且。
     *
     * <p>开启后 assignee / owner / candidateUsers / candidateGroups 四组条件合并成
     * 一个括号里的 OR；其余条件（流程实例、状态、挂起…）仍照常 AND。
     */
    private boolean candidateOrAssigned;

    /** 只查已完成任务（已办列表用）。 */
    private boolean completedOnly;

    private boolean unassignedOnly;

    private Date createTimeFrom;

    private Date createTimeTo;

    private int pageNum = 1;

    private int pageSize = 20;

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public WfTaskQuery setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
        return this;
    }

    public String getDefinitionId() {
        return definitionId;
    }

    public WfTaskQuery setDefinitionId(String definitionId) {
        this.definitionId = definitionId;
        return this;
    }

    public String getAssignee() {
        return assignee;
    }

    public WfTaskQuery setAssignee(String assignee) {
        this.assignee = assignee;
        return this;
    }

    public String getOwner() {
        return owner;
    }

    public WfTaskQuery setOwner(String owner) {
        this.owner = owner;
        return this;
    }

    public String getCompleterId() {
        return completerId;
    }

    public WfTaskQuery setCompleterId(String completerId) {
        this.completerId = completerId;
        return this;
    }

    public String getCategory() {
        return category;
    }

    public WfTaskQuery setCategory(String category) {
        this.category = category;
        return this;
    }

    public List<String> getCandidateUsers() {
        return candidateUsers;
    }

    public WfTaskQuery setCandidateUsers(List<String> candidateUsers) {
        this.candidateUsers = candidateUsers;
        return this;
    }

    public List<String> getCandidateGroups() {
        return candidateGroups;
    }

    public WfTaskQuery setCandidateGroups(List<String> candidateGroups) {
        this.candidateGroups = candidateGroups;
        return this;
    }

    public WfTask.Status getStatus() {
        return status;
    }

    public WfTaskQuery setStatus(WfTask.Status status) {
        this.status = status;
        return this;
    }

    public boolean isOpenOnly() {
        return openOnly;
    }

    public Boolean getSuspendedOnly() {
        return suspendedOnly;
    }

    public WfTaskQuery setSuspendedOnly(Boolean suspendedOnly) {
        this.suspendedOnly = suspendedOnly;
        return this;
    }

    public boolean isCandidateOrAssigned() {
        return candidateOrAssigned;
    }

    public WfTaskQuery setCandidateOrAssigned(boolean candidateOrAssigned) {
        this.candidateOrAssigned = candidateOrAssigned;
        return this;
    }

    public WfTaskQuery setOpenOnly(boolean openOnly) {
        this.openOnly = openOnly;
        return this;
    }

    public boolean isCompletedOnly() {
        return completedOnly;
    }

    public WfTaskQuery setCompletedOnly(boolean completedOnly) {
        this.completedOnly = completedOnly;
        return this;
    }

    public boolean isUnassignedOnly() {
        return unassignedOnly;
    }

    public WfTaskQuery setUnassignedOnly(boolean unassignedOnly) {
        this.unassignedOnly = unassignedOnly;
        return this;
    }

    public Date getCreateTimeFrom() {
        return createTimeFrom;
    }

    public WfTaskQuery setCreateTimeFrom(Date createTimeFrom) {
        this.createTimeFrom = createTimeFrom;
        return this;
    }

    public Date getCreateTimeTo() {
        return createTimeTo;
    }

    public WfTaskQuery setCreateTimeTo(Date createTimeTo) {
        this.createTimeTo = createTimeTo;
        return this;
    }

    // ==================== 第 45 轮：父子任务 / 优先级 / 办理时间 / 截止时间 ====================
    //
    // 这四组条件对应的列**早就存在**（PARENT_TASK_ID / PRIORITY / END_TIME / DUE_DATE），
    // 模型上也早有字段，缺的只是**过滤层** ——
    // 也就是说它们此前是"存得进、读得出、但筛不出来"。
    //
    // ⚠️ 加任何一条都必须**内存与 JDBC 两个实现一起加**：
    // 少一边就是「开发期（内存）查得到、线上（JDBC）查不到」，
    // 而 count 与列表会跟着对不上 —— 那个 bug 在内存模式下完全不可见。

    /** 优先级下界（含）。{@link WfTask#getPriority()} 默认 50。 */
    private Integer minPriority;

    /** 优先级上界（含）。 */
    private Integer maxPriority;

    /** 办理时间下界（含）—— 与 {@link #createTimeFrom} 的区别是<b>它是办完的那一刻</b>。 */
    private Date endTimeFrom;

    /** 办理时间上界（含）。 */
    private Date endTimeTo;

    /** 截止时间下界（含）。审批超期查询的主要入口。 */
    private Date dueDateFrom;

    /** 截止时间上界（含）。 */
    private Date dueDateTo;

    public Integer getMinPriority() {
        return minPriority;
    }

    /** 优先级下界（<b>含</b>）。默认优先级是 50，所以 {@code >= 50} 约等于"不限"。 */
    public WfTaskQuery setMinPriority(Integer minPriority) {
        this.minPriority = minPriority;
        return this;
    }

    public Integer getMaxPriority() {
        return maxPriority;
    }

    /** 优先级上界（<b>含</b>）。 */
    public WfTaskQuery setMaxPriority(Integer maxPriority) {
        this.maxPriority = maxPriority;
        return this;
    }

    public Date getEndTimeFrom() {
        return endTimeFrom;
    }

    public WfTaskQuery setEndTimeFrom(Date endTimeFrom) {
        this.endTimeFrom = endTimeFrom;
        return this;
    }

    public Date getEndTimeTo() {
        return endTimeTo;
    }

    public WfTaskQuery setEndTimeTo(Date endTimeTo) {
        this.endTimeTo = endTimeTo;
        return this;
    }

    public Date getDueDateFrom() {
        return dueDateFrom;
    }

    public WfTaskQuery setDueDateFrom(Date dueDateFrom) {
        this.dueDateFrom = dueDateFrom;
        return this;
    }

    public Date getDueDateTo() {
        return dueDateTo;
    }

    public WfTaskQuery setDueDateTo(Date dueDateTo) {
        this.dueDateTo = dueDateTo;
        return this;
    }

    /**
     * 截止时间窗口。
     *
     * <p>审批系统的「哪些单子已经超期未批」就靠它：
     * {@code setDueDateTo(现在)} 一条就够了 ——
     * 截止时间早于此刻的全在结果里，<b>没设截止时间的任务不在其中</b>。
     *
     * <p><b>没设 dueDate 的任务不算超期</b>：它没有承诺过什么时候办完，
     * 把它算进超期清单里，运维第一件事就是去挨个确认"这条到底该不该管"。
     */
    public WfTaskQuery setDueDateBetween(Date from, Date to) {
        this.dueDateFrom = from;
        this.dueDateTo = to;
        return this;
    }

    public int getPageNum() {
        return pageNum;
    }

    public WfTaskQuery setPageNum(int pageNum) {
        this.pageNum = pageNum < 1 ? 1 : pageNum;
        return this;
    }

    public int getPageSize() {
        return pageSize;
    }

    public WfTaskQuery setPageSize(int pageSize) {
        this.pageSize = pageSize < 1 ? 20 : pageSize;
        return this;
    }

    public int getOffset() {
        return (pageNum - 1) * pageSize;
    }

    /**
     * 条件自相矛盾时直接拒绝，不让它变成一个空结果。
     *
     * <p>{@code openOnly} 与 {@code completedOnly} 同时为真，两套持久化实现都会拼出
     * 恒假的条件（SQL 侧是 {@code STATUS IN (...) AND STATUS='COMPLETED'}，
     * 内存侧是两次 if 依次过滤）—— 查询照跑，零错误，零结果。
     * 调用方看到空列表时分不清是"确实没有"还是"条件打架"，而这种歧义在线上
     * 通常表现为"待办怎么一条都没有"，排查成本极高。
     *
     * <p>所以在进入持久层之前就把它挡下来。同理把 createTimeFrom / createTimeTo
     * 的倒置也算进去：那会静默返回空集，而写反一个时间下界是极常见的手误。
     */
    public void assertConsistent() {
        if (openOnly && completedOnly) {
            throw new IllegalArgumentException(
                    "任务查询条件矛盾：openOnly 与 completedOnly 不能同时为真"
                            + "（未完成的任务不可能同时是已完成的）。"
                            + "要查已办请只用 completedOnly，要查待办请只用 openOnly。");
        }
        if (createTimeFrom != null && createTimeTo != null
                && createTimeFrom.after(createTimeTo)) {
            throw new IllegalArgumentException(
                    "任务查询条件矛盾：createTimeFrom(" + createTimeFrom
                            + ") 晚于 createTimeTo(" + createTimeTo + ")。");
        }
        // 第 45 轮补的三组区间：**同一条毛病，同样在这里挡下来**。
        // 区间写反的后果与 createTime 那对完全一样——静默返回空集，
        // 而写反一个下界是极常见的手误（尤其是"最近三天"这类口头需求）。
        rejectReversedRange("endTimeFrom", "endTimeTo", endTimeFrom, endTimeTo);
        rejectReversedRange("dueDateFrom", "dueDateTo", dueDateFrom, dueDateTo);
        if (minPriority != null && maxPriority != null
                && minPriority.intValue() > maxPriority.intValue()) {
            throw new IllegalArgumentException(
                    "任务查询条件矛盾：minPriority(" + minPriority
                            + ") 大于 maxPriority(" + maxPriority + ")。");
        }
    }

    /**
     * 区间倒置的统一拒绝。
     *
     * <p><b>单独一个方法而不是三段复制</b>：三处同形状的判断里，
     * 漏改一处的代价是「只有那一对区间写反时静默返回空集」，
     * 而那种 bug 只在特定参数下出现、且症状是"查不到"，
     * 排查时几乎不会怀疑到区间写反。
     */
    private static void rejectReversedRange(String fromName, String toName,
            Date from, Date to) {
        if (from != null && to != null && from.after(to)) {
            throw new IllegalArgumentException(
                    "任务查询条件矛盾：" + fromName + "(" + from
                            + ") 晚于 " + toName + "(" + to + ")。");
        }
    }
}
