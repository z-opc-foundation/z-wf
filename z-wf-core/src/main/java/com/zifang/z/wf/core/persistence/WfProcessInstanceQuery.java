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

    /**
     * 只要终态实例（COMPLETED / EXTERNALLY_TERMINATED / INTERNALLY_TERMINATED）。
     *
     * <p>单独给一个开关而不是让调用方把三种状态各查一遍再合并：终态有三种，
     * 用 {@link #status} 单值表达"已结束"是表达不出来的，而"查三种再合并"
     * 还得处理分页 —— 三次查询各自分页，合并出来的第 2 页并不是全量第 2 页。
     */
    private boolean finishedOnly;

    /** 只要仍在流转的实例（ACTIVE / SUSPENDED）。 */
    private boolean unfinishedOnly;

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

    public boolean isFinishedOnly() {
        return finishedOnly;
    }

    public WfProcessInstanceQuery setFinishedOnly(boolean finishedOnly) {
        this.finishedOnly = finishedOnly;
        return this;
    }

    public boolean isUnfinishedOnly() {
        return unfinishedOnly;
    }

    public WfProcessInstanceQuery setUnfinishedOnly(boolean unfinishedOnly) {
        this.unfinishedOnly = unfinishedOnly;
        return this;
    }

    /**
     * 条件自相矛盾时直接拒绝，不让它变成一个空结果。
     *
     * <p>与 {@link WfTaskQuery#assertConsistent()} 同一套理由：矛盾条件叠上去
     * 只会得到一个零结果的列表，调用方却分不清是"确实没有"还是"条件打架"。
     * 这里挡三种打架：
     * <ul>
     *   <li>finishedOnly 与 unfinishedOnly 同时为真</li>
     *   <li>finishedOnly 配上非终态的 status（如 ACTIVE）</li>
     *   <li>startTimeFrom 晚于 startTimeTo</li>
     * </ul>
     * 至于 finishedOnly 配终态 status（两者一致）不在此列 ——
     * 那是冗余条件，不是矛盾条件，照常过滤即可。
     */
    public void assertConsistent() {
        if (finishedOnly && unfinishedOnly) {
            throw new IllegalArgumentException(
                    "流程实例查询条件矛盾：finishedOnly 与 unfinishedOnly 不能同时为真。");
        }
        if (finishedOnly && status != null && !status.isTerminal()) {
            throw new IllegalArgumentException(
                    "流程实例查询条件矛盾：finishedOnly 只收终态实例（"
                            + "COMPLETED / EXTERNALLY_TERMINATED / INTERNALLY_TERMINATED），"
                            + "而 status 却是 " + status + "。");
        }
        if (unfinishedOnly && status != null && status.isTerminal()) {
            throw new IllegalArgumentException(
                    "流程实例查询条件矛盾：unfinishedOnly 只收在途实例（ACTIVE / SUSPENDED），"
                            + "而 status 却是终态 " + status + "。");
        }
        if (startTimeFrom != null && startTimeTo != null
                && startTimeFrom.after(startTimeTo)) {
            throw new IllegalArgumentException(
                    "流程实例查询条件矛盾：startTimeFrom(" + startTimeFrom
                            + ") 晚于 startTimeTo(" + startTimeTo + ")。");
        }
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
