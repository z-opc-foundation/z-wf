package com.zifang.z.wf.core.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行令牌（token）—— z-wf 引擎的执行树节点。
 *
 * <p><b>为什么用执行树而不是 z-util-wf-kernel 的 CountDownLatch：</b>
 * kernel 那套"每个节点一个 CountDownLatch、凑齐就执行"只适用于<b>单次线性推进 + 内存内</b>。
 * 生产审批引擎必须支持：
 * <ul>
 *   <li><b>分支隔离</b>：并行网关 fork 出的两个 token 变量不该互相污染
 *       （审批常见：A 支线改金额、B 支线改日期）</li>
 *   <li><b>汇合判定</b>：并行网关 join 要等"所有<b>被激活过</b>的入线"到齐，
 *       而不是"所有存在的入线"——存在但本次没被激活的分支不能永久阻塞</li>
 *   <li><b>循环</b>：节点跳回上游（驳回重提）时执行树要能重新展开</li>
 *   <li><b>持久化恢复</b>：token 状态要能落库、能在进程重启后重建</li>
 * </ul>
 * CountDownLatch 三条都做不到（状态在 latch 里、不可序列化、汇合语义错）。
 *
 * <p>执行树语义：
 * <pre>
 *  进程实例 (WfProcessInstance)
 *   └── token A ──┐
 *                 ├── 并行网关 fork 产生两个 token，各自带局部变量
 *   └── token B ──┘
 * </pre>
 *
 * @author zifang
 */
public class WfExecution implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * token 状态。
     */
    public enum State {
        /** 活跃：正在推进或等待。 */
        ACTIVE,
        /** 已在用户任务上等待（不可自动推进，需人工完成）。 */
        WAITING,
        /** 已结束。 */
        ENDED
    }

    private String id;

    /** 所属流程实例。 */
    private String processInstanceId;

    /** 父 token id（fork 的来源）。根 token 的父为 null。 */
    private String parentId;

    /** 子 token id 列表。 */
    private List<String> children = new ArrayList<>();

    /** 当前所在的流程定义节点 id。 */
    private String activityId;

    /** token 状态。 */
    private State state = State.ACTIVE;

    /** 局部变量（分支级）。 */
    private Map<String, Object> variables = new HashMap<>();

    /**
     * 本 token 已经过、且"可能还会再来一次"的节点 id。
     * <p>并行/包容网关汇合时用它判断"还在等谁"：只有被激活过的入线才算数。
     * 循环重入时清空，保证第二轮能重新汇合。
     */
    private List<String> arrivedActivities = new ArrayList<>();

    /** 进入当前节点的时间。 */
    private Date enteredTime;

    /** 是否为 fork 出来的子 token（根 token 为 false）。 */
    private boolean child;

    public WfExecution() {
    }

    public WfExecution(String id, String processInstanceId, String activityId) {
        this.id = id;
        this.processInstanceId = processInstanceId;
        this.activityId = activityId;
        this.enteredTime = new Date();
    }

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

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public List<String> getChildren() {
        return children;
    }

    public void setChildren(List<String> children) {
        this.children = children == null ? new ArrayList<String>() : children;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables == null ? new HashMap<String, Object>() : variables;
    }

    public List<String> getArrivedActivities() {
        return arrivedActivities;
    }

    public void setArrivedActivities(List<String> arrivedActivities) {
        this.arrivedActivities = arrivedActivities == null ? new ArrayList<String>() : arrivedActivities;
    }

    public Date getEnteredTime() {
        return enteredTime;
    }

    public void setEnteredTime(Date enteredTime) {
        this.enteredTime = enteredTime;
    }

    public boolean isChild() {
        return child;
    }

    public void setChild(boolean child) {
        this.child = child;
    }

    public boolean isActive() {
        return state == State.ACTIVE;
    }

    public boolean isEnded() {
        return state == State.ENDED;
    }

    public boolean isWaiting() {
        return state == State.WAITING;
    }

    /**
     * 记录一次"到达某节点"（汇合判定用；幂等）。
     */
    public void arriveAt(String activityId) {
        if (activityId != null && !arrivedActivities.contains(activityId)) {
            arrivedActivities.add(activityId);
        }
    }

    /**
     * 清空到达记录（token 跳回上游时调用，让本轮能重新汇合）。
     */
    public void clearArrived() {
        arrivedActivities.clear();
    }

    @Override
    public String toString() {
        return "WfExecution{" + id + " @ " + activityId + " " + state + "}";
    }
}
