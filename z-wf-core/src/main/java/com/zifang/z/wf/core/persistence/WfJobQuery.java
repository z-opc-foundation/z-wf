package com.zifang.z.wf.core.persistence;

import java.util.Date;

/**
 * Job 查询条件。
 *
 * <p>{@code dueBefore} 是执行器唯一真正需要的条件："把到点之前的都给我"。
 * 其余字段服务于排障界面（按实例/节点查、看重试耗尽的 job）。
 *
 * @author zifang
 */
public class WfJobQuery {

    private String processInstanceId;

    private String elementId;

    /** 只要到期时刻早于（不含）该值的。 */
    private Date dueBefore;

    /** 只要还没把重试次数耗尽的。 */
    private Boolean retriesExhausted;

    private int pageNum = 1;

    private int pageSize = 50;

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public WfJobQuery setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
        return this;
    }

    public String getElementId() {
        return elementId;
    }

    public WfJobQuery setElementId(String elementId) {
        this.elementId = elementId;
        return this;
    }

    public Date getDueBefore() {
        return dueBefore;
    }

    public WfJobQuery setDueBefore(Date dueBefore) {
        this.dueBefore = dueBefore;
        return this;
    }

    public Boolean getRetriesExhausted() {
        return retriesExhausted;
    }

    public WfJobQuery setRetriesExhausted(Boolean retriesExhausted) {
        this.retriesExhausted = retriesExhausted;
        return this;
    }

    public int getPageNum() {
        return pageNum;
    }

    public WfJobQuery setPageNum(int pageNum) {
        this.pageNum = pageNum < 1 ? 1 : pageNum;
        return this;
    }

    public int getPageSize() {
        return pageSize;
    }

    public WfJobQuery setPageSize(int pageSize) {
        this.pageSize = pageSize < 1 ? 50 : pageSize;
        return this;
    }

    public int getOffset() {
        return (pageNum - 1) * pageSize;
    }
}
