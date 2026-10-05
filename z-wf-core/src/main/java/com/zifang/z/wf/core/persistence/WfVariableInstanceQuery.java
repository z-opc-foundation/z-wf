package com.zifang.z.wf.core.persistence;

import java.util.HashSet;
import java.util.Set;

/**
 * 变量实例查询条件 —— 「某个变量挂在哪一级作用域上、值是多少」。
 *
 * <p>过滤全在内存里做（见 {@code WfVariableQueryService}）：本仓的变量是三个模型
 * 上各自的 Map，<b>没有独立的行</b>，能下推成 SQL 的只有"按实例/任务取出来"这几种。
 *
 * @author zifang
 */
public class WfVariableInstanceQuery {

    private String processInstanceId;

    /** 精确到某条 token 上的变量。给了它就只查这一层，不做作用域回退。 */
    private String executionId;

    /** 精确到某个任务上的变量。 */
    private String taskId;

    private String name;

    /** 名字模糊匹配（大小写不敏感）。与 {@link #name} 同时给时按"都要满足"处理。 */
    private String nameLike;

    /** 值的等值匹配。用字符串比较 —— Map 里取出来的是 Object，没有统一类型。 */
    private String valueEquals;

    /** 只看这些作用域（{@code process} / {@code execution} / {@code task}）；为空表示不限。 */
    private Set<String> scopes = new HashSet<String>();

    /**
     * 任务级变量是否只看<b>未办结</b>的任务的。
     *
     * <p><b>默认 {@code true}</b>，这是本查询最需要讲清的一个默认值。
     * 任务变量存在那一行任务上，办结之后行还在、变量也还在 ——
     * 那是"仍然存在的事实"，不是"当前状态"。
     * 默认把它们算进来，「这单现在有哪些变量」会得到一份混着十几条历史表单变量的清单，
     * 而排障的人问的从来是"现在的"。
     * 要看历史的传 {@code false}；已办结任务的那些行带
     * {@code onClosedTask=true} 与办结时间，调用方能自己再筛。
     *
     * <p><b>唯一的例外是显式给了 {@link #taskId}</b>：点名某张任务时
     * 不套这个过滤。理由是那样做出来的结果<b>没法区分</b> ——
     * 「这张已办结的任务上一个变量都没有」和「这个过滤把你点的任务滤掉了」
     * 返回的都是空列表，而排障的人几乎一定会去查一张已经办结的任务
     * （问题正是"当时填的什么"）。是否已办结仍然写在视图的
     * {@code onClosedTask} 上，调用方想筛随时能筛。
     */
    private Boolean openTasksOnly = Boolean.TRUE;

    /**
     * 是否把<b>引擎内部变量</b>（{@code loopCounter} / {@code loopAssignee}）也列出来。
     *
     * <p>默认 {@code false}：它们是多实例节点自己用的中间量，不是业务变量。
     * 混进来会让"这单有 5 个变量"变成 7 个，而其中 2 个没人认得，
     * 于是排障的人开始怀疑是不是查错了。
     */
    private Boolean includeEngineInternal = Boolean.FALSE;

    private int pageNum = 1;

    private int pageSize = 50;

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public WfVariableInstanceQuery setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
        return this;
    }

    public String getExecutionId() {
        return executionId;
    }

    public WfVariableInstanceQuery setExecutionId(String executionId) {
        this.executionId = executionId;
        return this;
    }

    public String getTaskId() {
        return taskId;
    }

    public WfVariableInstanceQuery setTaskId(String taskId) {
        this.taskId = taskId;
        return this;
    }

    public String getName() {
        return name;
    }

    public WfVariableInstanceQuery setName(String name) {
        this.name = name;
        return this;
    }

    public String getNameLike() {
        return nameLike;
    }

    public WfVariableInstanceQuery setNameLike(String nameLike) {
        this.nameLike = nameLike;
        return this;
    }

    public String getValueEquals() {
        return valueEquals;
    }

    public WfVariableInstanceQuery setValueEquals(String valueEquals) {
        this.valueEquals = valueEquals;
        return this;
    }

    public Set<String> getScopes() {
        return scopes;
    }

    public WfVariableInstanceQuery setScopes(Set<String> scopes) {
        this.scopes = scopes == null ? new HashSet<String>() : scopes;
        return this;
    }

    public WfVariableInstanceQuery addScope(String scope) {
        if (scope != null) {
            this.scopes.add(scope);
        }
        return this;
    }

    public Boolean getOpenTasksOnly() {
        return openTasksOnly;
    }

    public WfVariableInstanceQuery setOpenTasksOnly(Boolean openTasksOnly) {
        this.openTasksOnly = openTasksOnly;
        return this;
    }

    public Boolean getIncludeEngineInternal() {
        return includeEngineInternal;
    }

    public WfVariableInstanceQuery setIncludeEngineInternal(Boolean includeEngineInternal) {
        this.includeEngineInternal = includeEngineInternal;
        return this;
    }

    public int getPageNum() {
        return pageNum;
    }

    public WfVariableInstanceQuery setPageNum(int pageNum) {
        this.pageNum = pageNum;
        return this;
    }

    public int getPageSize() {
        return pageSize;
    }

    public WfVariableInstanceQuery setPageSize(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    public int normalizedPageNum() {
        return pageNum < 1 ? 1 : pageNum;
    }

    public int normalizedPageSize() {
        if (pageSize < 1) {
            return 50;
        }
        return Math.min(pageSize, 1000);
    }
}