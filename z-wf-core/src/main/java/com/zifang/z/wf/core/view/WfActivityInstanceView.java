package com.zifang.z.wf.core.view;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.zifang.z.wf.core.model.WfExecution;

/**
 * 活动实例树的一个节点 —— 对应一条<b>执行令牌</b>（{@link WfExecution}）。
 *
 * <p>对应 Camunda 的 {@code ActivityInstance}，但有一处**刻意的结构差异**，
 * 差在哪儿、为什么，必须写在这里：
 *
 * <p><b>本仓一个 token 会连续穿过多个节点，而 Camunda 的每个活动各有一个实例。</b>
 * 本仓的 {@code leave} 是把<b>同一个</b> token 的 {@code activityId} 往前挪，
 * 直到 endEvent 才把它标 ENDED —— 所以 token 的身份标识的是"一条并发分支"，
 * 不是"一个节点访问"。节点访问是另一样东西（{@code WfActivityInstance} 历史行）。
 * 而 Camunda 的 {@code ActivityInstance} 是按活动建的，它的
 * {@code childTransitionInstances} 挂在<b>各自的活动实例</b>下，天然形成
 * activity-scope 的嵌套。
 *
 * <p>若照搬 Camunda 的嵌套，就得靠历史行的<b>时间序</b>去猜"这一步是不是上一步的孩子"——
 * 单分支下能猜对，<b>并行分支上必然猜错</b>（两条分支的历史行在时间上交错）。
 * 猜出来的层级比扁平轨迹更坏：它看起来可信。
 * ⇒ 本仓的形状是：<b>树给并发结构，节点内的 {@code childTransitionInstances}
 * 给这条分支走过的顺序</b>。两者不互相推导。
 *
 * <p><b>「流程结束」不等于「树会一直在」。</b>流程结束之后 token 连同 {@code parentId}
 * 仍留在库里，所以并发结构继续可读；但它<b>不是</b>一份独立于历史的数据 ——
 * {@code deleteHistoryBefore} 会把 {@code ZWF_EXECUTION} 与历史一并清掉
 * （内存与 JDBC 两套实现都删，本轮才把 JDBC 那边补齐）。
 * 于是边界很干脆：<b>历史清理之前树都在；清理之后实例本身已经不存在，树自然也查不到</b>。
 * ⇒ 调用方只需要记一条：<b>这棵树活多久，取决于历史保留多久</b>。
 *
 * <p>（第 17 轮订正：此前这里写的是「两套独立的生命周期，哪套先没得由调用方知道」。
 * 那是照着 JDBC 清理漏删令牌的<b>缺陷</b>写出来的 ——
 * 缺陷被修掉之后，这个说法就不成立了。）
 *
 * @author zifang
 */
public class WfActivityInstanceView implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 本节点 id。取 token id —— token 就是分支的身份，没有另造编号。 */
    private String id;

    private String parentActivityInstanceId;

    private String processInstanceId;

    private String processDefinitionKey;

    private String processDefinitionVersion;

    private String executionId;

    /** token 当前停在哪。已结束的 token 停在它最后走到的那个节点上。 */
    private String activityId;

    private String activityName;

    private String activityType;

    /** token 状态原样带出：ACTIVE / WAITING / ENDED。 */
    private String executionState;

    /**
     * 该 token 进入当前节点的时间。
     *
     * <p>只有"进入当前节点"这一刻有意义：历史行的 {@code durationMillis}
     * 才是"这一步花了多久"，两者不能混 —— 一个是"现在停在这儿多久了"，
     * 一个是"这一步总共花了多久"，而对还在等的节点，前者还没有答案。
     */
    private Long enteredTime;

    /**
     * 这条 token 自己走过的节点（token 自带的 {@code arrivedActivities}）。
     *
     * <p><b>它只说得清"这条分支到哪了"，说不到"汇合在等谁"。</b>
     * 这一点是实测出来的，不是推的：并行网关 fork 出来的第二条分支，
     * 它的 {@code arrivedActivities} 里<b>不含那个并行网关</b>，
     * 所以汇合处那条 token 停在 join 上时，从它自己的 arrived 看不到"还差哪条分支"。
     * ⇒ 「join 在等谁」的答案在<b>树本身</b>：另有一条 token 还停在别的节点上没有结束。
     * 别拿这一行去回答那个问题。
     */
    private List<String> arrivedActivities = new ArrayList<>();

    /**
     * 本流程<b>当前是否有多条未结束的 token</b>（也就是"有没有并行分支在跑"）。
     *
     * <p>刻意不按"同一父节点下有几个兄弟"来判：本仓的 fork 是<b>父子链</b>而不是兄弟 ——
     * 并行网关的第一条出线留在父 token 上继续走，其余出线另起子 token。
     * 实测两条并行分支分别是"父 token 停在 a1、子 token 停在 b1"，
     * activityId 天然不同 ⇒ 按兄弟数判，这个位永远是 false，
     * 而"这单有两步在并行"恰恰是调用方最想知道的那件事。
     * ⇒ 判据取<b>活跃 token 总数 &gt; 1</b>，与节点在树上的位置无关。
     */
    private boolean concurrent;

    /** 本 token 作为分支身份；Camunda 的 branchId 在本仓没有对应概念，直接给 token id。 */
    private String branchId;

    /** 并发结构：子令牌。 */
    private List<WfActivityInstanceView> childActivityInstances = new ArrayList<>();

    /** 该 token 已经离开过的节点（按发生顺序）。 */
    private List<WfTransitionInstanceView> childTransitionInstances = new ArrayList<>();

    public WfActivityInstanceView() {
    }

    /**
     * 由一条 token 建节点。
     *
     * @param node 该 token 当前所在节点；{@code null} 表示还没落到任何节点上
     *             （流程刚建出来、还没 enter 任何活动的瞬间）
     */
    public WfActivityInstanceView(WfExecution execution, String activityName,
                                  String activityType) {
        this.executionId = execution.getId();
        this.id = execution.getId();
        this.branchId = execution.getId();
        this.processInstanceId = execution.getProcessInstanceId();
        this.activityId = execution.getActivityId();
        this.activityName = activityName;
        this.activityType = activityType;
        this.executionState = execution.getState() == null ? null
                : execution.getState().name();
        this.enteredTime = execution.getEnteredTime() == null ? null
                : execution.getEnteredTime().getTime();
        this.arrivedActivities = new ArrayList<>(new LinkedHashSet<>(
                execution.getArrivedActivities() == null
                        ? new ArrayList<String>() : execution.getArrivedActivities()));
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getParentActivityInstanceId() {
        return parentActivityInstanceId;
    }

    public void setParentActivityInstanceId(String parentActivityInstanceId) {
        this.parentActivityInstanceId = parentActivityInstanceId;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public void setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
    }

    public String getProcessDefinitionKey() {
        return processDefinitionKey;
    }

    public void setProcessDefinitionKey(String processDefinitionKey) {
        this.processDefinitionKey = processDefinitionKey;
    }

    public String getProcessDefinitionVersion() {
        return processDefinitionVersion;
    }

    public void setProcessDefinitionVersion(String processDefinitionVersion) {
        this.processDefinitionVersion = processDefinitionVersion;
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

    public String getActivityName() {
        return activityName;
    }

    public void setActivityName(String activityName) {
        this.activityName = activityName;
    }

    public String getActivityType() {
        return activityType;
    }

    public void setActivityType(String activityType) {
        this.activityType = activityType;
    }

    public String getExecutionState() {
        return executionState;
    }

    public void setExecutionState(String executionState) {
        this.executionState = executionState;
    }

    public Long getEnteredTime() {
        return enteredTime;
    }

    public void setEnteredTime(Long enteredTime) {
        this.enteredTime = enteredTime;
    }

    public List<String> getArrivedActivities() {
        return arrivedActivities;
    }

    public void setArrivedActivities(List<String> arrivedActivities) {
        this.arrivedActivities = arrivedActivities;
    }

    public boolean isConcurrent() {
        return concurrent;
    }

    public void setConcurrent(boolean concurrent) {
        this.concurrent = concurrent;
    }

    public String getBranchId() {
        return branchId;
    }

    public void setBranchId(String branchId) {
        this.branchId = branchId;
    }

    public List<WfActivityInstanceView> getChildActivityInstances() {
        return childActivityInstances;
    }

    public void setChildActivityInstances(List<WfActivityInstanceView> childActivityInstances) {
        this.childActivityInstances = childActivityInstances;
    }

    public List<WfTransitionInstanceView> getChildTransitionInstances() {
        return childTransitionInstances;
    }

    public void setChildTransitionInstances(List<WfTransitionInstanceView> transitions) {
        this.childTransitionInstances = transitions;
    }

    @Override
    public String toString() {
        return "ActivityInstance{" + id + " " + activityId + " " + executionState + "}";
    }
}