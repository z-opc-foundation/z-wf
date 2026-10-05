package com.zifang.z.wf.core.engine;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.engine.expression.WfExpressionEvaluator;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.service.WfDelegateRegistry;

/**
 * 一次推进操作的执行上下文。
 *
 * <p>把"流程定义 + 实例 + 当前 token + 变量"打包传给引擎，引擎内部不持有跨调用的可变状态。
 * 这样引擎可以安全地被多线程调用（每个推进动作一个 context，天然隔离），
 * 也让"回滚/重放"成为可能。
 *
 * <p><b>变量读取顺序</b>：token 局部变量 → 流程实例变量。
 * 写入默认落到<b>流程实例</b>（{@link #setVariable}），
 * 只有显式调 {@link #setLocalVariable} 才写 token 局部 —— 因为审批场景里
 * "审批人填的意见/决定"几乎总是全流程可见的，写局部会"下一个节点看不到"，是常见坑。
 *
 * @author zifang
 */
public class WfContext {

    private final WfDefinition definition;

    private final WfProcessInstance processInstance;

    private WfExecution currentExecution;

    /** 本次推进产生的 token（fork 时可能有多个）。 */
    private final java.util.List<WfExecution> newExecutions = new java.util.ArrayList<>();

    /** 本次推进创建的 task。 */
    private final java.util.List<WfTask> createdTasks =
            new java.util.ArrayList<>();

    /** 本次推进产生的历史记录。 */
    private final java.util.List<WfActivityInstance> activityHistory =
            new java.util.ArrayList<>();

    /** 本次推进产生的结果标记（endEvent 的 resultExpression 求值结果）。 */
    private String processResult;

    /** 本次推进的触发人。 */
    private String authenticatedUserId;

    /** 引擎异常信息（内部终止时填充）。 */
    private String failureMessage;

    /** 条件/脚本表达式求值器（由 WfEngine 注入）。 */
    private WfExpressionEvaluator expressionEvaluator;

    /** delegate 解析器（由 WfEngine 注入）。 */
    private WfDelegateRegistry delegateRegistry;

    /** 子流程启动器（由 WfRuntimeService 注入；为 null 时 callActivity 空转）。 */
    private WfSubProcessLauncher subProcessLauncher;

    /**
     * 本流程实例的<b>全部</b> token 视图。
     *
     * <p>并行/包容网关的汇合判定需要看"所有兄弟 token 到哪了"，
     * 光看本次新建的 token 不够（兄弟可能是在上一次请求里建的）。
     * 因此 runtime service 在推进前把实例的全部 token 装进来。
     */
    private List<WfExecution> processExecutions = new ArrayList<>();

    /**
     * 本次推进中被修改过的既有 token（需要在推进后重新落库）。
     */
    private final List<WfExecution> touchedExecutions = new ArrayList<>();

    /**
     * 装入本实例全部 token（供汇合判定）。
     */
    public void setProcessExecutions(List<WfExecution> executions) {
        this.processExecutions = executions == null ? new ArrayList<WfExecution>() : executions;
    }

    public List<WfExecution> getProcessExecutions() {
        return processExecutions;
    }

    /**
     * 登记一个被修改的既有 token，使其在推进后被重新持久化。
     */
    public void markTouched(WfExecution execution) {
        if (execution != null && !touchedExecutions.contains(execution)) {
            touchedExecutions.add(execution);
        }
    }

    public List<WfExecution> getTouchedExecutions() {
        return touchedExecutions;
    }

    public WfExpressionEvaluator getExpressionEvaluator() {
        return expressionEvaluator;
    }

    public void setExpressionEvaluator(WfExpressionEvaluator expressionEvaluator) {
        this.expressionEvaluator = expressionEvaluator;
    }

    public WfDelegateRegistry getDelegateRegistry() {
        return delegateRegistry;
    }

    public void setDelegateRegistry(WfDelegateRegistry delegateRegistry) {
        this.delegateRegistry = delegateRegistry;
    }

    public WfSubProcessLauncher getSubProcessLauncher() {
        return subProcessLauncher;
    }

    public void setSubProcessLauncher(WfSubProcessLauncher subProcessLauncher) {
        this.subProcessLauncher = subProcessLauncher;
    }

    public WfContext(WfDefinition definition, WfProcessInstance processInstance, WfExecution currentExecution) {
        this.definition = definition;
        this.processInstance = processInstance;
        this.currentExecution = currentExecution;
    }

    public WfDefinition getDefinition() {
        return definition;
    }

    public WfProcessInstance getProcessInstance() {
        return processInstance;
    }

    public WfExecution getCurrentExecution() {
        return currentExecution;
    }

    public void setCurrentExecution(WfExecution currentExecution) {
        this.currentExecution = currentExecution;
    }

    public String getProcessInstanceId() {
        return processInstance == null ? null : processInstance.getId();
    }

    public String getAuthenticatedUserId() {
        return authenticatedUserId;
    }

    public void setAuthenticatedUserId(String authenticatedUserId) {
        this.authenticatedUserId = authenticatedUserId;
    }

    public java.util.List<WfExecution> getNewExecutions() {
        return newExecutions;
    }

    public java.util.List<WfTask> getCreatedTasks() {
        return createdTasks;
    }

    public java.util.List<WfActivityInstance> getActivityHistory() {
        return activityHistory;
    }

    public String getProcessResult() {
        return processResult;
    }

    public void setProcessResult(String processResult) {
        this.processResult = processResult;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    public void addNewExecution(WfExecution execution) {
        if (execution != null) {
            newExecutions.add(execution);
        }
    }

    public void addCreatedTask(WfTask task) {
        if (task != null) {
            createdTasks.add(task);
        }
    }

    public void addActivityHistory(WfActivityInstance instance) {
        if (instance != null) {
            activityHistory.add(instance);
        }
    }

    /**
     * 读取变量：先 token 局部，再流程级。
     */
    public Object getVariable(String name) {
        if (name == null) {
            return null;
        }
        if (currentExecution != null) {
            Object local = currentExecution.getVariables().get(name);
            if (local != null) {
                return local;
            }
        }
        return processInstance == null ? null : processInstance.getVariables().get(name);
    }

    /**
     * 写流程级变量（全流程可见）。
     */
    public void setVariable(String name, Object value) {
        if (name == null || processInstance == null) {
            return;
        }
        if (value == null) {
            processInstance.getVariables().remove(name);
        } else {
            processInstance.getVariables().put(name, value);
        }
    }

    /**
     * 写 token 局部变量（仅本分支可见）。
     */
    public void setLocalVariable(String name, Object value) {
        if (name == null || currentExecution == null) {
            return;
        }
        if (value == null) {
            currentExecution.getVariables().remove(name);
        } else {
            currentExecution.getVariables().put(name, value);
        }
    }

    /**
     * 批量写流程级变量。
     */
    public void setVariables(Map<String, Object> variables) {
        if (variables == null || processInstance == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            setVariable(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 合并视图：流程级变量 + token 局部变量（局部覆盖流程级）。
     * <p>给条件求值与 history 快照用。
     */
    public Map<String, Object> mergedVariables() {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (processInstance != null) {
            merged.putAll(processInstance.getVariables());
        }
        if (currentExecution != null) {
            merged.putAll(currentExecution.getVariables());
        }
        return merged;
    }

    /**
     * 本次节点访问的结论（通常是审批意见）。
     *
     * <p>存在的理由：活动历史此前有<b>三个写入点</b>（引擎的 enter / leave、
     * service 的 recordActivityComplete），而 leave 并不掌握审批意见 ——
     * 于是同一步骤被记了三条，且其中两条的 {@code assignee} 取的是
     * {@code authenticatedUserId}。在"进入审批"那一刻那个值还是<b>发起人</b>，
     * 轨迹上就会显示"alice 开始了审批"，而实际办理人是别人。
     *
     * <p>现在收敛成：{@code completeTask} 把意见放进这里，
     * {@code leave()} 一次性写出唯一一条记录。
     */
    private String pendingActivityOutcome;

    public void setPendingActivityOutcome(String pendingActivityOutcome) {
        this.pendingActivityOutcome = pendingActivityOutcome;
    }

    /** 取走结论；取走后清空，避免下一次节点访问误用上一次的意见。 */
    public String consumePendingActivityOutcome() {
        String value = pendingActivityOutcome;
        pendingActivityOutcome = null;
        return value;
    }

    /**
     * 构造一条活动历史并登记。
     */
    public WfActivityInstance recordActivity(String activityId, String activityName,
                                              String activityType, String outcome) {
        WfActivityInstance instance =
                new WfActivityInstance();
        instance.setProcessInstanceId(getProcessInstanceId());
        instance.setActivityId(activityId);
        instance.setActivityName(activityName);
        instance.setActivityType(activityType);
        instance.setOutcome(outcome);
        instance.setStartTime(new Date());
        instance.setEndTime(new Date());
        instance.setAssignee(authenticatedUserId);
        instance.setExecutionId(currentExecution == null ? null : currentExecution.getId());
        if (processInstance != null) {
            instance.setProcessDefinitionKey(processInstance.getDefinitionKey());
        }
        addActivityHistory(instance);
        return instance;
    }
}
