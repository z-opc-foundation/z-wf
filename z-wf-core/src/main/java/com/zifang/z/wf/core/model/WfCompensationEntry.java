package com.zifang.z.wf.core.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 一条<b>补偿登记</b>（第 37 轮）——「这个活动做过了，将来要退的话退它」。
 *
 * <p>补偿机制分两步，这一行是第一步的产物：
 * <ol>
 *   <li><b>登记</b>：活动 {@code A} 完成且它可补偿（{@code definition.isCompensable(A)}）时，
 *       引擎往这张表里写一行。写的时机是「做完」，不是「打算做」——
 *       所以表里的每一行都对应一件<b>已经发生</b>的事。</li>
 *   <li><b>执行</b>：撤销发生时，按 {@link #seq} <b>逆序</b>取出本作用域内的行，
 *       逐条执行它的补偿处理器（逆序 = 后做的先撤，这是补偿的基本次序约定，
 *       顺序错了就等于用错误的顺序去拆一条已经搭好的东西）。</li>
 * </ol>
 *
 * <p><b>为什么必须落库而不能只放内存</b>：撤销可能发生在几小时后、几天后，
 * 中间隔着若干次重启。只存在内存里的话，重启一次这张表就空了，
 * 而症状是「补偿安静地什么都不做」—— 没有异常、没有日志、流程照常完成。
 *
 * <p>{@link #done} 记的是「这一条补偿过没有」：同一作用域可能被撤销两次
 * （先撤销内层事务，再撤销外层），同一条登记不该退两遍。
 *
 * @author zifang
 */
public class WfCompensationEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 登记 ID。 */
    private String id;

    /** 流程实例 ID。 */
    private String processInstanceId;

    /**
     * <b>被</b>补偿的活动 ID（补偿边界事件的宿主，或 {@code activityRef} 指定的那个）。
     *
     * <p>不是补偿处理器的 ID：处理器是「退的方法」，这个才是「退什么」。
     */
    private String activityId;

    /**
     * 作用域：内联容器 id，<b>空串表示进程级</b>。
     *
     * <p>与 {@link WfExecution#getInlineScopeId()} 同一把尺子 ——
     * 补偿按作用域分别逆序，内层事务的补偿不能把外层已完成的步骤也撤掉。
     */
    private String scope;

    /**
     * 登记次序（同一实例内单调递增）。
     *
     * <p><b>不用时间戳</b>：并行分支上的两个活动可能在同一毫秒完成，
     * 用 {@code System.currentTimeMillis()} 会让逆序变成随机序，
     * 而逆序正是补偿唯一不能错的地方（先退款再退订 vs 反过来，前者会让钱白退一次）。
     * 次序由<b>登记动作本身</b>的产生顺序决定，是确定的。
     */
    private long seq;

    /** 这条登记是否已经补偿过。 */
    private boolean done;

    /** 登记时间。 */
    private Date registeredAt;

    /** 补偿完成时间（{@link #done} 为 true 时有值）。 */
    private Date compensatedAt;

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

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope == null ? "" : scope;
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    public Date getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Date registeredAt) {
        this.registeredAt = registeredAt;
    }

    public Date getCompensatedAt() {
        return compensatedAt;
    }

    public void setCompensatedAt(Date compensatedAt) {
        this.compensatedAt = compensatedAt;
    }

    @Override
    public String toString() {
        return "WfCompensationEntry{" + id + ": " + activityId
                + " scope=" + (scope == null || scope.isEmpty() ? "<进程级>" : scope)
                + " seq=" + seq + (done ? " done" : "") + "}";
    }
}
