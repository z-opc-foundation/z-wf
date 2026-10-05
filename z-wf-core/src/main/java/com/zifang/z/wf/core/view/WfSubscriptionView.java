package com.zifang.z.wf.core.view;

import java.io.Serializable;
import java.util.Date;

/**
 * 一条"流程正在等什么"的订阅 —— 对应 Camunda 的 {@code EventSubscription}。
 *
 * <p><b>它回答的是"现在卡在哪"这个问题</b>。一个在等消息的流程实例，
 * 在待办列表、轨迹、评论里都看不到任何东西：没有待办（它不是在等人），
 * 没有新轨迹（它还什么都没做），没有报错（一切正常）。
 * 排障时最常见的一句话是"这条单子怎么不动了"，而没有这张表就只能去翻 XML 猜。
 *
 * <p><b>刻意区分"等外部事件"与"等时间"与"等外部 worker"</b>：
 * 三者的处置完全不同 —— 事件没来要找投递方，时间没到要调快时钟或缩短时限，
 * 活没人领要找 worker。而 {@code WfJob} 上只有一个 {@code JOB_TYPE} 枚举，
 * 直接抛出去等于把持久化结构变成对外 API。
 *
 * <p><b>竞速订阅带 {@link #gatewayId}</b>。同一个事件网关下的几条分支订阅是
 * "互相排斥"的关系：一条被唤醒，其余几条会同时作废。把网关 id 带出来，
 * 运维才看得出"这几条是同一次竞速"，而不是几个互不相干的等待。
 *
 * <p>它是<b>快照</b>：不是实时视图，查询那一刻的等待状态，之后流程继续走就不再成立。
 *
 * @author zifang
 */
public class WfSubscriptionView implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订阅 id（就是 job id），投递事件时按它或按事件名定位。 */
    private String id;

    private String processInstanceId;

    /** token id —— 一个流程实例可能同时在多个节点上等待。 */
    private String executionId;

    private String definitionKey;

    private String definitionName;

    /** 流程节点 id，排障时对着 XML 看这一步是什么。 */
    private String activityId;

    private String activityName;

    /**
     * 等的是什么：{@code message} / {@code signal} / {@code timer} /
     * {@code external} / {@code async}。
     *
     * <p>用<b>归并后的词</b>而不是 job 类型：调用方要回答的是
     * "这条在等谁来"，而 job 类型会把"等消息"和"等消息"拆成四种
     * （边界订阅 / 网关分支 / 接收任务 / …），那是引擎内部的区分方式。
     * 原始 job 类型放在 {@link #jobType} 里，需要时再看。
     */
    private String waitingFor;

    /** 归并前的 job 类型，排障时用来判断是打断还是竞速。 */
    private String jobType;

    /**
     * 事件名 / 主题名 / 定时器说明，按 {@link #waitingFor} 取不同含义。
     */
    private String eventName;

    /**
     * 所属事件网关 id；<b>只有竞速分支有值</b>。
     *
     * <p>同一个网关下的若干条订阅是互相排斥的，一条被唤醒其余同时作废。
     */
    private String gatewayId;

    /** 触发时刻；{@code null} = 不由时间触发（等消息/信号/外部 worker）。 */
    private Date duedate;

    /** 已等待多久毫秒。调用方最常问的其实是"它在这儿趴了多久了"。 */
    private Long waitingMillis;

    /** 已被 worker 领走时给出占用者；没被领时为 null。 */
    private String lockedBy;

    /** 剩余重试次数。定时器/异步 job 反复失败时这是主要线索。 */
    private int retries;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public void setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getDefinitionKey() {
        return definitionKey;
    }

    public void setDefinitionKey(String definitionKey) {
        this.definitionKey = definitionKey;
    }

    public String getDefinitionName() {
        return definitionName;
    }

    public void setDefinitionName(String definitionName) {
        this.definitionName = definitionName;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getActivityName() {
        return activityName;
    }

    public void setActivityName(String activityName) {
        this.activityName = activityName;
    }

    public String getWaitingFor() {
        return waitingFor;
    }

    public void setWaitingFor(String waitingFor) {
        this.waitingFor = waitingFor;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public String getGatewayId() {
        return gatewayId;
    }

    public void setGatewayId(String gatewayId) {
        this.gatewayId = gatewayId;
    }

    public Date getDuedate() {
        return duedate;
    }

    public void setDuedate(Date duedate) {
        this.duedate = duedate;
    }

    public Long getWaitingMillis() {
        return waitingMillis;
    }

    public void setWaitingMillis(Long waitingMillis) {
        this.waitingMillis = waitingMillis;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }
}
