package com.zifang.z.wf.core.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 一个批次的<b>筛选条件</b>（第 39 轮）——「改哪些」。
 *
 * <p>字段按批次类型分组：{@link WfBatch.Type#INSTANCE} 用 {@code processInstance*}，
 * {@link WfBatch.Type#TASK} 用 {@code task*}，{@link WfBatch.Type#JOB} 用 {@code job*}。
 * <b>不属于该类型的字段一律忽略</b>，而不是报错 —— 条件是对外的入参，
 * 同一份 JSON 里带上别的类型的字段是自然发生的（前端复用了同一个表单），
 * 为此拒绝整批创建比忽略掉多出来的字段要糟得多。
 *
 * <p><b>为什么条件存成文本而不是引用一个可复用的筛选器</b>：
 * 批次是「一次性的决定」。引用 {@link WfFilter} 的话，「三天后执行这批」
 * 会按<b>三天后</b>那个筛选器的定义去找目标 —— 中间谁改过筛选器，
 * 这批要改的东西就悄悄变了，而记录上只写着「按我的待办筛选器」。
 * 存文本则把「改哪些」钉死在创建那一刻。
 *
 * <p><b>枚举一律存字符串</b>（{@link #status} / {@link #taskStatus} / {@link #jobType}）。
 * 与 {@link WfFilter} 同一条理由：JSON 里 {@code 3} 与 {@code "3"} 长得一样，
 * 类型在读回来时已经丢了。转换发生在
 * {@code WfBatchService} 里，且<b>转不动就报错</b>，绝不悄悄退化成「不过滤」。
 *
 * <p>所有字段可空 = 该维度不过滤。但<b>一个条件都不给是被拒绝的</b>：
 * 那等价于「改这个类型下的全部东西」，在运维场景里几乎总是误操作。
 *
 * @author zifang
 */
public class WfBatchCriteria implements Serializable {

    private static final long serialVersionUID = 1L;

    // ==================== 按 id 点名 ====================

    /**
     * 直接点名要改的目标 id。
     *
     * <p><b>给了它就完全覆盖其它条件</b>，不做「与」也不做「或」。
     * 「这 3 个实例」这句话里本来就没有「定义 key 是什么」的意思，
     * 再拿条件去筛一遍只会制造一种更难解释的结果 ——
     * 点名的 3 个里少改了 1 个，而记录上写着「按 id 执行」，
     * 看的人根本猜不到还有另一套条件在后面筛。
     */
    private List<String> ids = new ArrayList<String>();

    // ==================== 流程实例条件 ====================

    private String processDefinitionKey;

    private Integer processDefinitionVersion;

    private String processBusinessKey;

    private String processStartUserId;

    private String processCategory;

    /** {@link WfProcessStatus} 的枚举名；空 = 不过滤。 */
    private String status;

    /** 只取已结束的实例（{@code setFinishedOnly}）。 */
    private Boolean finishedOnly;

    /** 只取未结束的实例（{@code setUnfinishedOnly}）。 */
    private Boolean unfinishedOnly;

    private Date processStartTimeFrom;

    private Date processStartTimeTo;

    private String processResult;

    // ==================== 任务条件 ====================

    /** 任务的流程实例 id。 */
    private String taskProcessInstanceId;

    private String taskDefinitionId;

    private String taskAssignee;

    private String taskOwner;

    private String taskCategory;

    /** {@link WfTask.Status} 的枚举名；空 = 不过滤。 */
    private String taskStatus;

    /** 只取未办结的任务（{@code setOpenOnly}）。 */
    private Boolean taskOpenOnly;

    /** 只取没有办理人的任务（{@code setUnassignedOnly}）。 */
    private Boolean taskUnassignedOnly;

    private Date taskCreateTimeFrom;

    private Date taskCreateTimeTo;

    // ==================== job 条件 ====================

    private String jobProcessInstanceId;

    private String jobElementId;

    /** {@link WfJobType} 的枚举名；空 = 不过滤。 */
    private String jobType;

    /** 只取已经到期时刻早于该值的 job（{@code setDueBefore}）。 */
    private Date jobDueBefore;

    /** 只取重试耗尽的（{@code setRetriesExhausted}）。 */
    private Boolean jobRetriesExhausted;

    private String jobTopic;

    // ==================== getter / setter ====================

    public List<String> getIds() {
        return ids;
    }

    public void setIds(List<String> ids) {
        this.ids = ids == null ? new ArrayList<String>() : ids;
    }

    public String getProcessDefinitionKey() {
        return processDefinitionKey;
    }

    public void setProcessDefinitionKey(String processDefinitionKey) {
        this.processDefinitionKey = processDefinitionKey;
    }

    public Integer getProcessDefinitionVersion() {
        return processDefinitionVersion;
    }

    public void setProcessDefinitionVersion(Integer processDefinitionVersion) {
        this.processDefinitionVersion = processDefinitionVersion;
    }

    public String getProcessBusinessKey() {
        return processBusinessKey;
    }

    public void setProcessBusinessKey(String processBusinessKey) {
        this.processBusinessKey = processBusinessKey;
    }

    public String getProcessStartUserId() {
        return processStartUserId;
    }

    public void setProcessStartUserId(String processStartUserId) {
        this.processStartUserId = processStartUserId;
    }

    public String getProcessCategory() {
        return processCategory;
    }

    public void setProcessCategory(String processCategory) {
        this.processCategory = processCategory;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getFinishedOnly() {
        return finishedOnly;
    }

    public void setFinishedOnly(Boolean finishedOnly) {
        this.finishedOnly = finishedOnly;
    }

    public Boolean getUnfinishedOnly() {
        return unfinishedOnly;
    }

    public void setUnfinishedOnly(Boolean unfinishedOnly) {
        this.unfinishedOnly = unfinishedOnly;
    }

    public Date getProcessStartTimeFrom() {
        return processStartTimeFrom;
    }

    public void setProcessStartTimeFrom(Date processStartTimeFrom) {
        this.processStartTimeFrom = processStartTimeFrom;
    }

    public Date getProcessStartTimeTo() {
        return processStartTimeTo;
    }

    public void setProcessStartTimeTo(Date processStartTimeTo) {
        this.processStartTimeTo = processStartTimeTo;
    }

    public String getProcessResult() {
        return processResult;
    }

    public void setProcessResult(String processResult) {
        this.processResult = processResult;
    }

    public String getTaskProcessInstanceId() {
        return taskProcessInstanceId;
    }

    public void setTaskProcessInstanceId(String taskProcessInstanceId) {
        this.taskProcessInstanceId = taskProcessInstanceId;
    }

    public String getTaskDefinitionId() {
        return taskDefinitionId;
    }

    public void setTaskDefinitionId(String taskDefinitionId) {
        this.taskDefinitionId = taskDefinitionId;
    }

    public String getTaskAssignee() {
        return taskAssignee;
    }

    public void setTaskAssignee(String taskAssignee) {
        this.taskAssignee = taskAssignee;
    }

    public String getTaskOwner() {
        return taskOwner;
    }

    public void setTaskOwner(String taskOwner) {
        this.taskOwner = taskOwner;
    }

    public String getTaskCategory() {
        return taskCategory;
    }

    public void setTaskCategory(String taskCategory) {
        this.taskCategory = taskCategory;
    }

    public String getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(String taskStatus) {
        this.taskStatus = taskStatus;
    }

    public Boolean getTaskOpenOnly() {
        return taskOpenOnly;
    }

    public void setTaskOpenOnly(Boolean taskOpenOnly) {
        this.taskOpenOnly = taskOpenOnly;
    }

    public Boolean getTaskUnassignedOnly() {
        return taskUnassignedOnly;
    }

    public void setTaskUnassignedOnly(Boolean taskUnassignedOnly) {
        this.taskUnassignedOnly = taskUnassignedOnly;
    }

    public Date getTaskCreateTimeFrom() {
        return taskCreateTimeFrom;
    }

    public void setTaskCreateTimeFrom(Date taskCreateTimeFrom) {
        this.taskCreateTimeFrom = taskCreateTimeFrom;
    }

    public Date getTaskCreateTimeTo() {
        return taskCreateTimeTo;
    }

    public void setTaskCreateTimeTo(Date taskCreateTimeTo) {
        this.taskCreateTimeTo = taskCreateTimeTo;
    }

    public String getJobProcessInstanceId() {
        return jobProcessInstanceId;
    }

    public void setJobProcessInstanceId(String jobProcessInstanceId) {
        this.jobProcessInstanceId = jobProcessInstanceId;
    }

    public String getJobElementId() {
        return jobElementId;
    }

    public void setJobElementId(String jobElementId) {
        this.jobElementId = jobElementId;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public Date getJobDueBefore() {
        return jobDueBefore;
    }

    public void setJobDueBefore(Date jobDueBefore) {
        this.jobDueBefore = jobDueBefore;
    }

    public Boolean getJobRetriesExhausted() {
        return jobRetriesExhausted;
    }

    public void setJobRetriesExhausted(Boolean jobRetriesExhausted) {
        this.jobRetriesExhausted = jobRetriesExhausted;
    }

    public String getJobTopic() {
        return jobTopic;
    }

    public void setJobTopic(String jobTopic) {
        this.jobTopic = jobTopic;
    }

    /**
     * 对<b>某个批次类型</b>而言，这个条件是否等于「没给条件」。
     *
     * <p>必须按类型判定，不能只判「整体是不是空」：传
     * {@code processDefinitionKey} 配一个 {@code TASK} 批次，
     * 整体看是有内容的，但 {@code toTaskQuery} 根本不读这个字段 ——
     * 于是查询条件全空，命中<b>全部任务</b>，而记录上写着「按定义 key 筛过」。
     * 这不是理论风险，前端复用了同一个表单就会这么发。
     *
     * <p>判定口径刻意<b>保守</b>：只认「明确写了值」的字段，
     * {@code false} 也算写了（它排除掉了一整类目标），
     * 而 {@link #ids} 非空对三种类型都算。
     *
     * @param type 批次作用的对象类型
     * @return true 表示对该类型来说什么条件都没给（等价于「全改」）
     */
    public boolean isEmptyFor(WfBatch.Type type) {
        if (type == null) {
            return true;
        }
        if (ids != null && !ids.isEmpty()) {
            return false;
        }
        switch (type) {
            case INSTANCE:
                return allBlank(processDefinitionKey, processBusinessKey, processStartUserId,
                        processCategory, status, processResult)
                        && processDefinitionVersion == null
                        && processStartTimeFrom == null && processStartTimeTo == null
                        && !TRUE.equals(finishedOnly) && !TRUE.equals(unfinishedOnly);
            case TASK:
                return allBlank(taskProcessInstanceId, taskDefinitionId, taskAssignee,
                        taskOwner, taskCategory, taskStatus)
                        && taskCreateTimeFrom == null && taskCreateTimeTo == null
                        && !TRUE.equals(taskOpenOnly) && !TRUE.equals(taskUnassignedOnly);
            case JOB:
                return allBlank(jobProcessInstanceId, jobElementId, jobType, jobTopic)
                        && jobDueBefore == null && !TRUE.equals(jobRetriesExhausted);
            default:
                return true;
        }
    }

    private static boolean allBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static final Boolean TRUE = Boolean.TRUE;

    @Override
    public String toString() {
        return "WfBatchCriteria{ids=" + (ids == null ? 0 : ids.size())
                + ", definitionKey=" + processDefinitionKey
                + ", businessKey=" + processBusinessKey + "}";
    }
}