package com.zifang.z.wf.core.persistence;

import java.util.HashSet;
import java.util.Set;

import com.zifang.z.wf.core.model.WfExecution;

/**
 * 执行令牌的条件查询（对应 Camunda 的 {@code createExecutionQuery}）。
 *
 * <p><b>与既有 {@code getExecutions(processInstanceId)} 的差别只有一个：
 * 查之前不必先知道流程实例 id。</b>
 * 列举那条路必须先有 instanceId，而排障的第一句常常是
 * 「哪个单子卡在审批节点上」—— 那时你还不知道单子的 id。
 *
 * <p>能回答的三个具体问题：
 * <ol>
 *   <li>「哪些单子的 token 停在节点 X」→ {@link #setActivityId}</li>
 *   <li>「哪些 token 还没结束」→ {@link #setStates} 只给 {@code ACTIVE/WAITING}，
 *       这一条是「单子不动了」的第一道筛子（与订阅查询互补：
 *       订阅答的是「在等一个事件」，令牌答的是「停在哪」）</li>
 *   <li>「哪条 token 的某个变量是这个值」→ {@link #setVariableName} + {@link #setVariableValueEquals}，
 *       并行分支上最常出问题的那一个</li>
 * </ol>
 *
 * <p><b>刻意不提供 {@code definitionKey} 条件。</b>令牌表里没有这一列，
 * 要支持就得 join 实例表；而「某个流程定义下所有活跃 token」这个需求，
 * 用已有的实例查询（它本来就能按 definitionKey 筛）再逐个 {@code getExecutions} 就够，
 * 不会漏。⇒ 这里做只会多出一条两套实现都可能写歪的 join。
 *
 * <p>排序两套实现必须一致：{@code ENTERED_TIME} 倒序（最新进入的节点在前 —— 排障先看最新），
 * 时间相同按 {@code EXEC_ID} 倒序兜底。见 {@code WfExecutionQuery} 在两套实现里的 ORDER BY。
 *
 * @author zifang
 */
public class WfExecutionQuery {

    private String processInstanceId;

    private String activityId;

    private Set<WfExecution.State> states = new HashSet<WfExecution.State>();

    private String variableName;

    private String variableValueEquals;

    private int pageNum = 1;

    private int pageSize = 50;

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public WfExecutionQuery setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
        return this;
    }

    public String getActivityId() {
        return activityId;
    }

    public WfExecutionQuery setActivityId(String activityId) {
        this.activityId = activityId;
        return this;
    }

    public Set<WfExecution.State> getStates() {
        return states;
    }

    public WfExecutionQuery setStates(Set<WfExecution.State> states) {
        this.states = states == null ? new HashSet<WfExecution.State>() : states;
        return this;
    }

    public WfExecutionQuery addState(WfExecution.State state) {
        if (state != null) {
            this.states.add(state);
        }
        return this;
    }

    /** 只看还没结束的令牌（活跃 + 等待人工）—— 排障时最常用的一条。 */
    public WfExecutionQuery onlyUnfinished() {
        return addState(WfExecution.State.ACTIVE).addState(WfExecution.State.WAITING);
    }

    public String getVariableName() {
        return variableName;
    }

    public WfExecutionQuery setVariableName(String variableName) {
        this.variableName = variableName;
        return this;
    }

    public String getVariableValueEquals() {
        return variableValueEquals;
    }

    public WfExecutionQuery setVariableValueEquals(String variableValueEquals) {
        this.variableValueEquals = variableValueEquals;
        return this;
    }

    public int getPageNum() {
        return pageNum;
    }

    public WfExecutionQuery setPageNum(int pageNum) {
        this.pageNum = pageNum;
        return this;
    }

    public int getPageSize() {
        return pageSize;
    }

    public WfExecutionQuery setPageSize(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    public int getOffset() {
        return (pageNum - 1) * pageSize;
    }

    /**
     * 复制一份，<b>只复制参与下推与分页的那几项</b>。
     *
     * <p>存在的理由是 {@code WfExecutionQueryService} 读数据时要把分页改成
     * "一页超大"，而它手上的对象是<b>调用方传进来的那个</b> ——
     * 就地改的话，调用方那个 query 之后再拿去翻页会读到被改过的分页参数。
     * 症状是「第二页和第一页返回一样的东西」，而那看起来像"数据只有一页"。
     *
     * <p><b>刻意不复制 {@link #variableName} 与 {@link #variableValueEquals}</b>：
     * 变量条件<b>不下推</b>（JSON 文本列跨库没法写同一段 SQL），
     * 而 {@code scan} 的过滤读的是<b>原 query</b>、不是这个副本 ——
     * 所以复制它们是死拷贝，而死拷贝会让人误以为"复制了就等于完整"。
     * <p>⇒ 若将来把变量条件下推到 SQL，<b>必须同时把这两个字段加回来</b>；
     * 在那之前它们复制与否都不影响结果。
     */
    public WfExecutionQuery copy() {
        WfExecutionQuery cloned = new WfExecutionQuery();
        cloned.processInstanceId = processInstanceId;
        cloned.activityId = activityId;
        cloned.states = new HashSet<WfExecution.State>(states);
        cloned.pageNum = pageNum;
        cloned.pageSize = pageSize;
        return cloned;
    }

    /**
     * 页码归一。
     *
     * <p>放在查询类上而不是服务层：归一规则必须与"读多少条"用同一处，
     * 否则 {@code pageSize=0} 会在两处得到不同的解释（一个当 0、一个当 50）。
     */
    public int normalizedPageNum() {
        return pageNum < 1 ? 1 : pageNum;
    }

    /**
     * 页大小归一：{@code <1} 当 50，大于 1000 收窄到 1000。
     *
     * <p>收窄是安全的：页大小是调用方<b>看得见</b>的参数，返回条数会直接反映出来；
     * 与之相对，扫描上限那种"看不见的截断"必须报错。
     */
    public int normalizedPageSize() {
        if (pageSize < 1) {
            return 50;
        }
        return Math.min(pageSize, 1000);
    }
}
