package com.zifang.z.wf.core.persistence;

import com.zifang.z.wf.core.model.WfBatch;

/**
 * 查批次列表的条件。
 *
 * <p>与 {@link WfProcessInstanceQuery} / {@link WfTaskQuery} 同一套约定：
 * 字段可空 = 该维度不过滤，分页只在最后一步生效。
 *
 * @author zifang
 */
public class WfBatchQuery {

    private WfBatch.Type batchType;

    private WfBatch.State state;

    /** 只取被挂起的批次；{@code false} 则只取没被挂起的。空 = 不过滤。 */
    private Boolean suspendedOnly;

    private String operatorId;

    private java.util.Date createTimeFrom;

    private java.util.Date createTimeTo;

    private int pageNum = 1;

    private int pageSize = 20;

    public WfBatch.Type getBatchType() {
        return batchType;
    }

    public WfBatchQuery setBatchType(WfBatch.Type batchType) {
        this.batchType = batchType;
        return this;
    }

    public WfBatch.State getState() {
        return state;
    }

    public WfBatchQuery setState(WfBatch.State state) {
        this.state = state;
        return this;
    }

    public Boolean getSuspendedOnly() {
        return suspendedOnly;
    }

    public WfBatchQuery setSuspendedOnly(Boolean suspendedOnly) {
        this.suspendedOnly = suspendedOnly;
        return this;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public WfBatchQuery setOperatorId(String operatorId) {
        this.operatorId = operatorId;
        return this;
    }

    public java.util.Date getCreateTimeFrom() {
        return createTimeFrom;
    }

    public WfBatchQuery setCreateTimeFrom(java.util.Date createTimeFrom) {
        this.createTimeFrom = createTimeFrom;
        return this;
    }

    public java.util.Date getCreateTimeTo() {
        return createTimeTo;
    }

    public WfBatchQuery setCreateTimeTo(java.util.Date createTimeTo) {
        this.createTimeTo = createTimeTo;
        return this;
    }

    public int getPageNum() {
        return pageNum;
    }

    public WfBatchQuery setPageNum(int pageNum) {
        this.pageNum = pageNum < 1 ? 1 : pageNum;
        return this;
    }

    public int getPageSize() {
        return pageSize;
    }

    public WfBatchQuery setPageSize(int pageSize) {
        this.pageSize = pageSize < 1 ? 1 : pageSize;
        return this;
    }
}