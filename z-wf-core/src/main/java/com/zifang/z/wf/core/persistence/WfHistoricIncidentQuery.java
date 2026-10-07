package com.zifang.z.wf.core.persistence;

import java.util.Date;

/**
 * 查<b>历史故障</b>的条件（第 40 轮）。
 *
 * <p>与 {@link WfIncidentQuery}（当前故障，从 job 现场推导）刻意分开：
 * 两者的<b>数据来源就不是一回事</b> —— 一个是"现在还卡着的 job"，
 * 一个是"曾经失败过的记录"。用同一个查询类会让调用方以为自己筛的是同一个东西，
 * 而两条数据的覆盖面并不重合（在途但已恢复的只有历史里有；
 * 从未失败过的两处都没有）。
 *
 * <p>全部字段可空 = 该维度不过滤。
 *
 * @author zifang
 */
public class WfHistoricIncidentQuery {

    private String jobId;

    private String processInstanceId;

    private String definitionKey;

    private String activityId;

    /** {@link com.zifang.z.wf.core.model.WfJobType} 的枚举名；空 = 不过滤。 */
    private String jobType;

    /** 错误类型（异常类名），子串匹配。 */
    private String errorType;

    /** 错误消息，子串匹配。 */
    private String errorMessageContains;

    /** 只要在这个时刻<b>之后</b>首次失败过的（"上周三那批"就是这么查的）。 */
    private Date firstFailureFrom;

    /** 只要在这个时刻<b>之前</b>最后失败过的。 */
    private Date lastFailureBefore;

    /** 只要累计失败次数达到该值的。 */
    private Integer minFailureCount;

    private int pageNum = 1;

    private int pageSize = 20;

    public String getJobId() {
        return jobId;
    }

    public WfHistoricIncidentQuery setJobId(String jobId) {
        this.jobId = jobId;
        return this;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public WfHistoricIncidentQuery setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
        return this;
    }

    public String getDefinitionKey() {
        return definitionKey;
    }

    public WfHistoricIncidentQuery setDefinitionKey(String definitionKey) {
        this.definitionKey = definitionKey;
        return this;
    }

    public String getActivityId() {
        return activityId;
    }

    public WfHistoricIncidentQuery setActivityId(String activityId) {
        this.activityId = activityId;
        return this;
    }

    public String getJobType() {
        return jobType;
    }

    public WfHistoricIncidentQuery setJobType(String jobType) {
        this.jobType = jobType;
        return this;
    }

    public String getErrorType() {
        return errorType;
    }

    public WfHistoricIncidentQuery setErrorType(String errorType) {
        this.errorType = errorType;
        return this;
    }

    public String getErrorMessageContains() {
        return errorMessageContains;
    }

    public WfHistoricIncidentQuery setErrorMessageContains(String errorMessageContains) {
        this.errorMessageContains = errorMessageContains;
        return this;
    }

    public Date getFirstFailureFrom() {
        return firstFailureFrom;
    }

    public WfHistoricIncidentQuery setFirstFailureFrom(Date firstFailureFrom) {
        this.firstFailureFrom = firstFailureFrom;
        return this;
    }

    public Date getLastFailureBefore() {
        return lastFailureBefore;
    }

    public WfHistoricIncidentQuery setLastFailureBefore(Date lastFailureBefore) {
        this.lastFailureBefore = lastFailureBefore;
        return this;
    }

    public Integer getMinFailureCount() {
        return minFailureCount;
    }

    public WfHistoricIncidentQuery setMinFailureCount(Integer minFailureCount) {
        this.minFailureCount = minFailureCount;
        return this;
    }

    public int getPageNum() {
        return pageNum;
    }

    public WfHistoricIncidentQuery setPageNum(int pageNum) {
        this.pageNum = pageNum < 1 ? 1 : pageNum;
        return this;
    }

    public int getPageSize() {
        return pageSize;
    }

    public WfHistoricIncidentQuery setPageSize(int pageSize) {
        this.pageSize = pageSize < 1 ? 1 : pageSize;
        return this;
    }

    /**
     * 归一后的页大小。
     *
     * <p>与 {@link WfIncidentQuery} 同一套规则（上限 1000）：
     * 一个不设上限的 {@code pageSize} 会让「传个超大 pageSize 就能全拿」
     * 这种调用悄悄成立，而故障量级通常不大、越界的代价又高（一次拖垮查询）。
     */
    public int normalizedPageSize() {
        return pageSize > 1000 ? 1000 : pageSize;
    }

    public int normalizedPageNum() {
        return pageNum < 1 ? 1 : pageNum;
    }
}