package com.zifang.z.wf.core.persistence;

import java.util.Date;

/**
 * 变量变更审计的查询条件。
 *
 * <p>对应 Camunda 的 {@code createHistoricDetailQuery}（变量/字段变更明细）。
 * 本仓不另造审计表，而是复用 {@code WfComment(type=variable)} ——
 * 变更内容写成 {@code "变量名: 旧值 -> 新值"}，所以按变量名查是对内容做<b>前缀</b>匹配。
 *
 * <p>前缀匹配而不是子串：{@code amount} 用子串匹配会命中 {@code discount_amount}，
 * 审计查询给出错的行比不给行更糟。
 *
 * @author zifang
 */
public class WfVariableAuditQuery {

    private String processInstanceId;

    /** 只要这一个变量的变更。 */
    private String variableName;

    /** 只要这个人改的。 */
    private String changedBy;

    /** 变更时间不早于（含）。 */
    private Date changedFrom;

    /** 变更时间早于（不含）。 */
    private Date changedTo;

    private int pageNum = 1;

    private int pageSize = 50;

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public WfVariableAuditQuery setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
        return this;
    }

    public String getVariableName() {
        return variableName;
    }

    public WfVariableAuditQuery setVariableName(String variableName) {
        this.variableName = variableName;
        return this;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public WfVariableAuditQuery setChangedBy(String changedBy) {
        this.changedBy = changedBy;
        return this;
    }

    public Date getChangedFrom() {
        return changedFrom;
    }

    public WfVariableAuditQuery setChangedFrom(Date changedFrom) {
        this.changedFrom = changedFrom;
        return this;
    }

    public Date getChangedTo() {
        return changedTo;
    }

    public WfVariableAuditQuery setChangedTo(Date changedTo) {
        this.changedTo = changedTo;
        return this;
    }

    public int getPageNum() {
        return pageNum;
    }

    public WfVariableAuditQuery setPageNum(int pageNum) {
        this.pageNum = pageNum < 1 ? 1 : pageNum;
        return this;
    }

    public int getPageSize() {
        return pageSize;
    }

    public WfVariableAuditQuery setPageSize(int pageSize) {
        this.pageSize = pageSize < 1 ? 50 : pageSize;
        return this;
    }

    public int getOffset() {
        return (pageNum - 1) * pageSize;
    }
}
