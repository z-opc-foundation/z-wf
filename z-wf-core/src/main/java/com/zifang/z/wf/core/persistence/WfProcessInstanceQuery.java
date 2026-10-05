package com.zifang.z.wf.core.persistence;

import java.util.Date;

import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;

/**
 * 流程实例查询条件。
 *
 * <p>所有字段可为空 = 不过滤。分页在内存里做（先过滤后切片），
 * 生产实现应把过滤下推到 SQL，但<b>语义必须一致</b>：先过滤、再按 startTime 倒序、最后分页。
 *
 * @author zifang
 */
public class WfProcessInstanceQuery {

    private String definitionKey;

    private String businessKey;

    private String startUserId;

    private String category;

    private WfProcessStatus status;

    /** 起始时间（含）。 */
    private Date startTimeFrom;

    /** 截止时间（含）。 */
    private Date startTimeTo;

    /** 结果过滤。 */
    private String result;

    private int pageNum = 1;

    private int pageSize = 20;

    public String getDefinitionKey() {
        return definitionKey;
    }

    public WfProcessInstanceQuery setDefinitionKey(String definitionKey) {
        this.definitionKey = definitionKey;
        return this;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public WfProcessInstanceQuery setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
        return this;
    }

    public String getStartUserId() {
        return startUserId;
    }

    public WfProcessInstanceQuery setStartUserId(String startUserId) {
        this.startUserId = startUserId;
        return this;
    }

    public String getCategory() {
        return category;
    }

    public WfProcessInstanceQuery setCategory(String category) {
        this.category = category;
        return this;
    }

    public WfProcessStatus getStatus() {
        return status;
    }

    public WfProcessInstanceQuery setStatus(WfProcessStatus status) {
        this.status = status;
        return this;
    }

    public Date getStartTimeFrom() {
        return startTimeFrom;
    }

    public WfProcessInstanceQuery setStartTimeFrom(Date startTimeFrom) {
        this.startTimeFrom = startTimeFrom;
        return this;
    }

    public Date getStartTimeTo() {
        return startTimeTo;
    }

    public WfProcessInstanceQuery setStartTimeTo(Date startTimeTo) {
        this.startTimeTo = startTimeTo;
        return this;
    }

    public String getResult() {
        return result;
    }

    public WfProcessInstanceQuery setResult(String result) {
        this.result = result;
        return this;
    }

    public int getPageNum() {
        return pageNum;
    }

    public WfProcessInstanceQuery setPageNum(int pageNum) {
        this.pageNum = pageNum < 1 ? 1 : pageNum;
        return this;
    }

    public int getPageSize() {
        return pageSize;
    }

    public WfProcessInstanceQuery setPageSize(int pageSize) {
        this.pageSize = pageSize < 1 ? 20 : pageSize;
        return this;
    }

    /**
     * 分页偏移。
     */
    public int getOffset() {
        return (pageNum - 1) * pageSize;
    }
}
