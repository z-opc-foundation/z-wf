package com.zifang.z.wf.core.view;

import java.util.Date;

/**
 * 变量实例视图 —— 「某个变量挂在哪一级作用域上、值是多少」。
 *
 * <p>本仓的变量<b>没有独立实体</b>：它们是 {@code WfProcessInstance} /
 * {@code WfExecution} / {@code WfTask} 三个模型上各自的一个 Map。
 * 所以本视图是<b>派生</b>出来的，不是持久化的一等公民 ——
 * 与 {@link WfIncidentView} 同一形态（那个从 job 派生）。
 *
 * <p>派生带来两件必须说清的事：
 * <ul>
 *   <li><b>变量没有自己的 id</b>，只有「作用域 + 变量名」这一对。{@link #id} 由
 *       两者拼成，形式如 {@code process:proc-1/amount}、{@code task:t-1/days}。
 *       这样做的好处是同一个名字在不同作用域上是不同的实例（而 Camunda 允许同名）；
 *       代价是它<b>只在本次查询期间有效</b> —— 变量改名后旧 id 立刻失效，
 *       所以它不该被持久化成订阅条件之类的长期配置。</li>
 *   <li><b>任务变量在任务办结后仍然查得到</b>（值存在那一行任务上）。
 *       那不是"历史"，那是仍然存在的事实；把它们混进"当前变量"才是误导。
 *       由 {@code openTasksOnly} 控制，默认只看未办结任务的 —— 见该字段注释。</li>
 * </ul>
 *
 * @author zifang
 */
public class WfVariableInstanceView {

    /** 作用域：流程级。 */
    public static final String SCOPE_PROCESS = "process";
    /** 作用域：分支（token）级。 */
    public static final String SCOPE_EXECUTION = "execution";
    /** 作用域：任务级。 */
    public static final String SCOPE_TASK = "task";

    /** 「作用域 + 变量名」拼成的临时标识，见类注释。 */
    private String id;

    private String name;

    private String scope;

    /**
     * 值的类型名（{@code String} / {@code Integer} / …）。
     *
     * <p>从运行时对象反推，<b>不落库</b>：落一列类型就要改表结构，
     * 而它只是给调用方一个"这个值能不能直接当数字用"的提示，
     * 由 {@code value} 自己就能看出来。
     */
    private String type;

    private Object value;

    private String processInstanceId;

    /**
     * 所属 token。流程级为 {@code null}；分支级与任务级都有。
     *
     * <p>任务级<b>也</b>填这一列：任务是从某条 token 上长出来的
     * （{@code WfTask.executionId}），而"这条分支上到底有哪些变量"
     * 恰恰是排障问得最多的问题 —— 如果任务级留空，按 {@code executionId} 查
     * 就会把同一个 token 上的任务变量整层漏掉，答案看起来还"挺干净"。
     * 这不是副本：它与 {@link #taskId} 指向的是同一个事实，缺哪一半都会让
     * "变量挂在哪"这个问题有一头答不出来。
     */
    private String executionId;

    /** 任务级变量所属的任务；其余两级为 {@code null}。 */
    private String taskId;

    /** 任务级变量所属节点的显示名，其余为 {@code null}。 */
    private String activityName;

    private String activityId;

    /** 任务办结时间；未办结为 {@code null}。排障时用它判断"这条是残留还是还在用"。 */
    private Date taskEndTime;

    /**
     * 任务级变量是否属于一张<b>已办结</b>的任务。
     *
     * <p>其余两级恒为 {@code false}（流程级没有"办结"这回事，
     * 分支级随分支结束而消失，由执行器自己收走）。
     */
    private boolean onClosedTask;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
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

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getActivityName() {
        return activityName;
    }

    public void setActivityName(String activityName) {
        this.activityName = activityName;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public Date getTaskEndTime() {
        return taskEndTime;
    }

    public void setTaskEndTime(Date taskEndTime) {
        this.taskEndTime = taskEndTime;
    }

    public boolean isOnClosedTask() {
        return onClosedTask;
    }

    public void setOnClosedTask(boolean onClosedTask) {
        this.onClosedTask = onClosedTask;
    }

    @Override
    public String toString() {
        return "WfVariableInstanceView{" + id + " = " + value + "}";
    }
}