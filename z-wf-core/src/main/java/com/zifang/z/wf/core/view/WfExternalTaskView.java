package com.zifang.z.wf.core.view;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 外部任务 —— 交给外部 worker 做的一步的对外契约。
 *
 * <p><b>放在 core 而不是 web 层</b>：领活的通常不是浏览器里的页面，而是一个独立部署的
 * 消费者服务（对账、催办、发通知）。把它放进 web 模块会让那个消费者被迫依赖 web，
 * 从而把整个 web 层（Spring MVC、序列化配置）拖进它的运行时。
 *
 * <p>它是<b>快照而非视图</b>：{@link #variables} 是领取那一刻从流程实例上读的拷贝，
 * 之后流程实例上的变量再变也不会反映到这个对象上。worker 要据此决定做什么，
 * 拿到的是"当时的业务上下文"，这正是外部动作需要的东西。
 *
 * <p>刻意<b>不</b>暴露 {@code WfJob} 实体：revision / retries / exceptionMessage
 * 那些是引擎内部实现细节，直接抛出去等于把持久化结构变成对外 API。
 *
 * @author zifang
 */
public class WfExternalTaskView implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 外部任务 id（就是 job id），worker 拿着它 complete / fail。 */
    private String id;

    /** 主题名，worker 按它决定这件活该不该我做。 */
    private String topic;

    private String processInstanceId;

    /** token id —— 一个流程实例可能同时在多个外部步骤上等待。 */
    private String executionId;

    /** 流程节点 id，排障时对着 XML 看这一步是什么。 */
    private String activityId;

    /**
     * 领取那一刻的流程变量。
     *
     * <p>给的是拷贝而不是引用：worker 可能把它存起来之后再读，
     * 而引擎随时会往实例上写新值 —— 给引用的话 worker 会看到"我领的时候是 A，
     * 用的时候变成 B 了"，那不是并发问题，是契约没说清。
     */
    private Map<String, Object> variables = new HashMap<>();

    /** 剩余重试次数。worker 可以据此判断"这件活已经被试过几次了"。 */
    private int retries;

    /** 谁领的（就是调用 fetchAndLock 的 workerId）。 */
    private String lockedBy;

    /** 租约到期时刻。超过它没完成，活会被别的 worker 重新领走。 */
    private long lockExpiresAt;

    /** 什么时候挂上来的。 */
    private long createTime;

    /** 最近一次失败原因（重试耗尽后用它排障）。 */
    private String errorMessage;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
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

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables == null ? new HashMap<String, Object>() : variables;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public long getLockExpiresAt() {
        return lockExpiresAt;
    }

    public void setLockExpiresAt(long lockExpiresAt) {
        this.lockExpiresAt = lockExpiresAt;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    @Override
    public String toString() {
        return "WfExternalTaskView{" + id + " topic=" + topic
                + " retries=" + retries + " lockedBy=" + lockedBy + "}";
    }
}
