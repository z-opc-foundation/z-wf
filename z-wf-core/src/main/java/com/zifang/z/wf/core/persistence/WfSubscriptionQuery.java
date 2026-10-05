package com.zifang.z.wf.core.persistence;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import com.zifang.z.wf.core.model.WfJobType;

/**
 * 订阅查询条件 —— "现在有哪些流程在等什么"。
 *
 * <p>用<b>一个</b>查询对象而不是按类型分几个（消息订阅查询 / 定时器订阅查询 / …）：
 * 排障时问的是"这批单子都卡在哪"，那天然是一个横切所有等待类型的问题。
 * 拆开的话调用方得先知道有哪几类、再分别查、最后自己合并，
 * 而"漏查了定时器这一类"恰恰是分查最容易出的错。
 *
 * <p>过滤在内存里做（见 {@code WfSubscriptionService}），所以这里的条件
 * 只影响读多少行，不影响结果正确性 —— 传了 {@link #types} 之外的组合
 * 只会读得多一点，不会给出错的答案。这与 {@link WfJobQuery} 的分工不同：
 * 那个是给执行器用的，必须下推到 SQL 才不至于把几十万行捞进内存。
 *
 * @author zifang
 */
public class WfSubscriptionQuery {

    /** 限定某个流程实例。 */
    private String processInstanceId;

    /** 限定某个流程定义 key。 */
    private String definitionKey;

    /** 只看这些 job 类型；为空表示不限。 */
    private Set<WfJobType> types = new HashSet<WfJobType>();

    /** 只看等这个事件名的（消息名 / 信号名 / topic）。 */
    private String eventName;

    /** 只看卡在这个流程节点上的等待。 */
    private String activityId;

    /**
     * 只看 {@code duedate} 在此之前的（定时器与异步 job 用）。
     * 用来找"本该响了却没响"的等待。
     */
    private Date dueBefore;

    /**
     * 只看已等待超过给定毫秒数的。
     *
     * <p>不叫 {@code waitingLongerThan}：它和 {@link #dueBefore} 量的不是一回事 ——
     * {@code dueBefore} 问的是"该触发了吗"，这个问的是"趴了多久了"。
     * 前者用于定时器，后者用于所有类型。
     */
    private Long waitingLongerThanMillis;

    /** 只看已经被 worker 领走的（外部任务专用）。 */
    private Boolean locked;

    private int pageNum = 1;

    private int pageSize = 50;

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public WfSubscriptionQuery setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
        return this;
    }

    public String getDefinitionKey() {
        return definitionKey;
    }

    public WfSubscriptionQuery setDefinitionKey(String definitionKey) {
        this.definitionKey = definitionKey;
        return this;
    }

    public Set<WfJobType> getTypes() {
        return types;
    }

    public WfSubscriptionQuery setTypes(Set<WfJobType> types) {
        this.types = types == null ? new HashSet<WfJobType>() : types;
        return this;
    }

    /** 只看某一类等待（最常用的过滤）。 */
    public WfSubscriptionQuery addType(WfJobType type) {
        if (type != null) {
            this.types.add(type);
        }
        return this;
    }

    public String getEventName() {
        return eventName;
    }

    public WfSubscriptionQuery setEventName(String eventName) {
        this.eventName = eventName;
        return this;
    }

    public String getActivityId() {
        return activityId;
    }

    public WfSubscriptionQuery setActivityId(String activityId) {
        this.activityId = activityId;
        return this;
    }

    public Date getDueBefore() {
        return dueBefore;
    }

    public WfSubscriptionQuery setDueBefore(Date dueBefore) {
        this.dueBefore = dueBefore;
        return this;
    }

    public Long getWaitingLongerThanMillis() {
        return waitingLongerThanMillis;
    }

    public WfSubscriptionQuery setWaitingLongerThanMillis(Long waitingLongerThanMillis) {
        this.waitingLongerThanMillis = waitingLongerThanMillis;
        return this;
    }

    public Boolean getLocked() {
        return locked;
    }

    public WfSubscriptionQuery setLocked(Boolean locked) {
        this.locked = locked;
        return this;
    }

    public int getPageNum() {
        return pageNum;
    }

    public WfSubscriptionQuery setPageNum(int pageNum) {
        this.pageNum = pageNum;
        return this;
    }

    public int getPageSize() {
        return pageSize;
    }

    public WfSubscriptionQuery setPageSize(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    /** 页码从 1 起；传 0 或负数时按 1 处理 —— 传 0 去查第 0 页只会得到空列表而无从察觉。 */
    public int normalizedPageNum() {
        return pageNum < 1 ? 1 : pageNum;
    }

    /** 上限 1000：调用方拼错 pageSize 时不至于把整张表拉进内存。 */
    public int normalizedPageSize() {
        if (pageSize < 1) {
            return 50;
        }
        return Math.min(pageSize, 1000);
    }
}
