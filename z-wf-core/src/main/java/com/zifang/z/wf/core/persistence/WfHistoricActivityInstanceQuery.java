package com.zifang.z.wf.core.persistence;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 历史活动实例查询。
 *
 * <p>存在的理由：此前只有 {@code getTrail(processInstanceId)} 一个写死口径 ——
 * 只能按流程实例取全量轨迹。而审批系统真正要问的问题往往是
 * "上个月所有走完的流程里，哪一步平均耗时最长"，
 * 这类问题按流程实例逐个查再在内存里聚合是做不到的。
 *
 * <p><b>所有条件之间是 AND</b>，留空即不参与筛选。
 * 没有 OR、没有嵌套、没有子查询 —— 与 {@link WfProcessInstanceQuery} 保持同一套
 * 简单语义。复杂的取数需求应当走数据仓库，不该把查询引擎做进流程引擎。
 *
 * <p>排序默认按开始时间正序（审批轨迹的自然顺序）；调 {@link #orderByDurationDesc}
 * 切成耗时倒序，用于找瓶颈环节。
 *
 * @author zifang
 */
public class WfHistoricActivityInstanceQuery {

    private String processInstanceId;
    private String processDefinitionKey;
    private String activityId;
    private String activityType;
    private String assignee;
    private Date startedAfter;
    private Date startedBefore;
    private Long minDurationMillis;
    private String orderBy = "startTime";
    private int pageNum = 1;
    private int pageSize = 20;

    public WfHistoricActivityInstanceQuery setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
        return this;
    }

    public WfHistoricActivityInstanceQuery setProcessDefinitionKey(String key) {
        this.processDefinitionKey = key;
        return this;
    }

    public WfHistoricActivityInstanceQuery setActivityId(String activityId) {
        this.activityId = activityId;
        return this;
    }

    public WfHistoricActivityInstanceQuery setActivityType(String activityType) {
        this.activityType = activityType;
        return this;
    }

    public WfHistoricActivityInstanceQuery setAssignee(String assignee) {
        this.assignee = assignee;
        return this;
    }

    public WfHistoricActivityInstanceQuery setStartedAfter(Date startedAfter) {
        this.startedAfter = startedAfter;
        return this;
    }

    public WfHistoricActivityInstanceQuery setStartedBefore(Date startedBefore) {
        this.startedBefore = startedBefore;
        return this;
    }

    /** 只看耗时不低于该值的活动（找瓶颈用）。 */
    public WfHistoricActivityInstanceQuery setMinDurationMillis(Long minDurationMillis) {
        this.minDurationMillis = minDurationMillis;
        return this;
    }

    /** 按耗时倒序。 */
    public WfHistoricActivityInstanceQuery orderByDurationDesc() {
        this.orderBy = "duration";
        return this;
    }

    public WfHistoricActivityInstanceQuery setPageNum(int pageNum) {
        this.pageNum = pageNum;
        return this;
    }

    public WfHistoricActivityInstanceQuery setPageSize(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    // ---- 以下供持久化实现读取，不参与业务逻辑 ----

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public String getProcessDefinitionKey() {
        return processDefinitionKey;
    }

    public String getActivityId() {
        return activityId;
    }

    public String getActivityType() {
        return activityType;
    }

    public String getAssignee() {
        return assignee;
    }

    public Date getStartedAfter() {
        return startedAfter;
    }

    public Date getStartedBefore() {
        return startedBefore;
    }

    public Long getMinDurationMillis() {
        return minDurationMillis;
    }

    public String getOrderBy() {
        return orderBy;
    }

    public int getPageNum() {
        return pageNum;
    }

    public int getPageSize() {
        return pageSize;
    }

    public int getOffset() {
        return Math.max(0, (pageNum - 1) * pageSize);
    }

    public List<String> describeConditions() {
        List<String> parts = new ArrayList<>();
        if (processInstanceId != null) {
            parts.add("processInstanceId=" + processInstanceId);
        }
        if (processDefinitionKey != null) {
            parts.add("processDefinitionKey=" + processDefinitionKey);
        }
        if (activityId != null) {
            parts.add("activityId=" + activityId);
        }
        if (activityType != null) {
            parts.add("activityType=" + activityType);
        }
        if (assignee != null) {
            parts.add("assignee=" + assignee);
        }
        if (startedAfter != null) {
            parts.add("startedAfter=" + startedAfter);
        }
        if (startedBefore != null) {
            parts.add("startedBefore=" + startedBefore);
        }
        if (minDurationMillis != null) {
            parts.add("minDuration>=" + minDurationMillis);
        }
        return parts;
    }
}
