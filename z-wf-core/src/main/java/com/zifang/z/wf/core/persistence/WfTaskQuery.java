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
 *   <li>{@link #assignee} 命中"责任人"；委派态额外用 {@link #owner} 匹配
 *       （否则被委派的人在自己的待办里看不到活 —— 这是委派最常见的实现 bug）</li>
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
}
