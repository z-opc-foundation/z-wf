package com.zifang.z.wf.web.mapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.web.dto.WfViews;

/**
 * 引擎实体 → 视图对象 的转换。
 *
 * <p>独立成类而不是塞在 Controller 里：任务摘要转换要用到"所属流程实例"（取 processKey /
 * startUserId），而这些只有 repository / runtime service 才有。
 * 把"查实例 + 转换"放一处，Controller 保持薄。
 *
 * @author zifang
 */
public class WfViewMapper {

    private final com.zifang.z.wf.core.service.WfRuntimeService runtimeService;

    public WfViewMapper(com.zifang.z.wf.core.service.WfRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    /**
     * 任务 → 摘要。
     */
    public WfViews.TaskSummary toSummary(WfTask task) {
        WfViews.TaskSummary view = new WfViews.TaskSummary();
        view.setTaskId(task.getId());
        view.setTaskName(task.getName());
        view.setProcessInstanceId(task.getProcessInstanceId());
        view.setDefinitionId(task.getDefinitionId());
        view.setFormKey(task.getFormKey());
        view.setCategory(task.getCategory());
        view.setAssignee(task.getAssignee());
        view.setOwner(task.getOwner());
        view.setHandler(task.effectiveHandler());
        view.setStatus(task.getStatus() == null ? null : task.getStatus().name());
        view.setPriority(task.getPriority());
        view.setCreateTime(time(task.getCreateTime()));
        view.setDueDate(time(task.getDueDate()));
        view.setOverdue(task.isOverdue());

        WfProcessInstance instance = runtimeService.getProcessInstance(task.getProcessInstanceId());
        if (instance != null) {
            view.setProcessKey(instance.getDefinitionKey());
            view.setBusinessKey(instance.getBusinessKey());
            view.setStartUserId(instance.getStartUserId());
        }
        return view;
    }

    public List<WfViews.TaskSummary> toSummaries(List<WfTask> tasks) {
        List<WfViews.TaskSummary> result = new ArrayList<>();
        if (tasks == null) {
            return result;
        }
        for (WfTask task : tasks) {
            result.add(toSummary(task));
        }
        return result;
    }

    /**
     * 任务 → 详情（附带意见、变量、所属流程轨迹）。
     */
    public WfViews.TaskDetail toDetail(WfTask task) {
        WfViews.TaskDetail detail = new WfViews.TaskDetail();
        WfViews.TaskSummary summary = toSummary(task);
        copy(summary, detail);

        detail.setComment(task.getComment());
        detail.setCompleterId(task.getCompleterId());
        detail.setEndTime(time(task.getEndTime()));
        detail.setVariables(new LinkedHashMap<>(task.getVariables()));
        detail.setTrail(toTrail(task.getProcessInstanceId()));
        return detail;
    }

    /**
     * 流程实例 → 摘要。
     */
    public WfViews.ProcessInstanceView toProcessView(WfProcessInstance instance) {
        if (instance == null) {
            return null;
        }
        WfViews.ProcessInstanceView view = new WfViews.ProcessInstanceView();
        view.setProcessInstanceId(instance.getId());
        view.setDefinitionKey(instance.getDefinitionKey());
        view.setDefinitionId(instance.getDefinitionId());
        view.setDefinitionVersion(instance.getDefinitionVersion());
        view.setBusinessKey(instance.getBusinessKey());
        view.setStartUserId(instance.getStartUserId());
        view.setStartDeptId(instance.getStartDeptId());
        view.setCategory(instance.getCategory());
        view.setStatus(instance.getStatus() == null ? null : instance.getStatus().name());
        view.setResult(instance.getResult());
        view.setStartTime(time(instance.getStartTime()));
        view.setEndTime(time(instance.getEndTime()));
        view.setDurationMillis(instance.durationMillis());
        return view;
    }

    public List<WfViews.ProcessInstanceView> toProcessViews(List<WfProcessInstance> instances) {
        List<WfViews.ProcessInstanceView> result = new ArrayList<>();
        if (instances == null) {
            return result;
        }
        for (WfProcessInstance instance : instances) {
            result.add(toProcessView(instance));
        }
        return result;
    }

    /**
     * 轨迹（活动历史）→ 可序列化 Map 列表，时间用 epoch-millis。
     */
    public List<Map<String, Object>> toTrail(String processInstanceId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (WfActivityInstance activity
                : runtimeService.getTrail(processInstanceId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("activityId", activity.getActivityId());
            row.put("activityName", activity.getActivityName());
            row.put("activityType", activity.getActivityType());
            row.put("assignee", activity.getAssignee());
            row.put("outcome", activity.getOutcome());
            row.put("detail", activity.getDetail());
            row.put("startTime", time(activity.getStartTime()));
            row.put("endTime", time(activity.getEndTime()));
            row.put("durationMillis", activity.getDurationMillis());
            result.add(row);
        }
        return result;
    }

    /**
     * 评论 → 可序列化 Map。
     *
     * <p>抽成单条方法是为了让 {@code POST /comment} 与 {@code GET /comments} 走同一套字段 ——
     * 两个端点返回形状不一致时，前端会先按"新建返回"处理再被"列表返回"打脸。
     */
    public Map<String, Object> toComment(WfComment comment) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", comment.getId());
        row.put("taskId", comment.getTaskId());
        row.put("userId", comment.getUserId());
        row.put("type", comment.getType());
        row.put("content", comment.getContent());
        row.put("time", time(comment.getTime()));
        return row;
    }

    /**
     * 评论 → Map 列表。
     */
    public List<Map<String, Object>> toComments(String processInstanceId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (WfComment comment : runtimeService.getComments(processInstanceId)) {
            result.add(toComment(comment));
        }
        return result;
    }

    /**
     * 执行令牌 → 排障视图。
     *
     * <p>刻意不透出 {@code arrivedActivities} / {@code variables}：
     * 前者是汇合判据的内部状态，后者是引擎中间量，暴露即固化成对外契约。
     * 排障要的是"卡在哪个节点、什么状态"，这些由 state + activityId 给出。
     */
    public WfViews.ExecutionView toExecutionView(
            com.zifang.z.wf.core.model.WfExecution execution) {
        if (execution == null) {
            return null;
        }
        WfViews.ExecutionView view = new WfViews.ExecutionView();
        view.setExecutionId(execution.getId());
        view.setProcessInstanceId(execution.getProcessInstanceId());
        view.setParentExecutionId(execution.getParentId());
        view.setActivityId(execution.getActivityId());
        view.setState(execution.getState() == null ? null : execution.getState().name());
        view.setEnteredTime(time(execution.getEnteredTime()));
        view.setChild(execution.isChild());
        view.setActive(execution.isActive());
        view.setWaiting(execution.isWaiting());
        view.setEnded(execution.isEnded());
        return view;
    }

    public List<WfViews.ExecutionView> toExecutionViews(
            List<com.zifang.z.wf.core.model.WfExecution> executions) {
        List<WfViews.ExecutionView> result = new ArrayList<>();
        if (executions == null) {
            return result;
        }
        for (com.zifang.z.wf.core.model.WfExecution execution : executions) {
            result.add(toExecutionView(execution));
        }
        return result;
    }

    /**
     * 历史活动实例 → view。
     *
     * <p>与 {@link #toTrail} 的字段完全一致 —— 两处各写一遍的话，
     * 前端拼出来的表就会因为少一个字段而对不上。
     */
    public WfViews.ActivityInstanceView toActivityView(
            WfActivityInstance activity) {
        WfViews.ActivityInstanceView view = new WfViews.ActivityInstanceView();
        view.setProcessInstanceId(activity.getProcessInstanceId());
        view.setProcessDefinitionKey(activity.getProcessDefinitionKey());
        view.setActivityId(activity.getActivityId());
        view.setActivityName(activity.getActivityName());
        view.setActivityType(activity.getActivityType());
        view.setAssignee(activity.getAssignee());
        view.setOutcome(activity.getOutcome());
        view.setDetail(activity.getDetail());
        view.setStartTime(time(activity.getStartTime()));
        view.setEndTime(time(activity.getEndTime()));
        view.setDurationMillis(activity.getDurationMillis());
        return view;
    }

    public List<WfViews.ActivityInstanceView> toActivityViews(
            List<WfActivityInstance> activities) {
        List<WfViews.ActivityInstanceView> result = new ArrayList<>();
        for (WfActivityInstance activity : activities) {
            result.add(toActivityView(activity));
        }
        return result;
    }

    /**
     * job → view。
     *
     * <p>带出 {@code retriesExhausted} 而不只给 {@code retries}：
     * 重试次数归零后引擎会把它钉在 -1，只给数字的话
     * 排障界面得自己判断"0 是什么、-1 又是什么"。
     */
    public WfViews.JobView toJobView(WfJob job) {
        WfViews.JobView view = new WfViews.JobView();
        view.setJobId(job.getId());
        view.setProcessInstanceId(job.getProcessInstanceId());
        view.setExecutionId(job.getExecutionId());
        view.setElementId(job.getElementId());
        view.setAttachedToRef(job.getAttachedToRef());
        view.setDuedate(time(job.getDuedate()));
        view.setRetries(job.getRetries());
        view.setRetriesExhausted(job.isRetriesExhausted());
        view.setExceptionMessage(job.getExceptionMessage());
        view.setCreateTime(time(job.getCreateTime()));
        view.setLastFailureTime(time(job.getLastFailureTime()));
        return view;
    }

    public List<WfViews.JobView> toJobViews(List<WfJob> jobs) {
        List<WfViews.JobView> result = new ArrayList<>();
        for (WfJob job : jobs) {
            result.add(toJobView(job));
        }
        return result;
    }

    public List<WfViews.VariableChangeView> toVariableChangeViews(List<WfComment> audits) {
        List<WfViews.VariableChangeView> result = new ArrayList<>();
        for (WfComment comment : audits) {
            result.add(toVariableChangeView(comment));
        }
        return result;
    }

    /**
     * 拆 {@code "变量名: 变更描述"}。拆点取<b>第一个</b> {@code ": "}：
     * 变量名由调用方给（理论上能含 ": "），碰上这种名字时这里给出的是短一截的名字，
     * 原始 {@code content} 一并透出，调用方能自己判断，不靠这个字段猜。
     */
    private WfViews.VariableChangeView toVariableChangeView(WfComment comment) {
        WfViews.VariableChangeView view = new WfViews.VariableChangeView();
        view.setAuditId(comment.getId());
        view.setProcessInstanceId(comment.getProcessInstanceId());
        view.setTaskId(comment.getTaskId());
        view.setChangedBy(comment.getUserId());
        view.setContent(comment.getContent());
        view.setTime(time(comment.getTime()));
        String content = comment.getContent();
        int idx = content == null ? -1 : content.indexOf(": ");
        if (idx >= 0) {
            view.setVariableName(content.substring(0, idx));
            view.setChange(content.substring(idx + 2));
        } else {
            // 没有冒号就不是引擎自己写的行（理论上不会发生），不替它编一个变量名
            view.setVariableName(null);
            view.setChange(content);
        }
        return view;
    }

    private long time(Date date) {
        return date == null ? 0L : date.getTime();
    }

    /**
     * 把摘要字段拷进详情（详情继承摘要，不能用序列化拷贝 —— 那样太慢且脆）。
     */
    private void copy(WfViews.TaskSummary from, WfViews.TaskDetail to) {
        to.setTaskId(from.getTaskId());
        to.setTaskName(from.getTaskName());
        to.setProcessInstanceId(from.getProcessInstanceId());
        to.setProcessKey(from.getProcessKey());
        to.setBusinessKey(from.getBusinessKey());
        to.setDefinitionId(from.getDefinitionId());
        to.setFormKey(from.getFormKey());
        to.setCategory(from.getCategory());
        to.setAssignee(from.getAssignee());
        to.setOwner(from.getOwner());
        to.setHandler(from.getHandler());
        to.setStatus(from.getStatus());
        to.setPriority(from.getPriority());
        to.setCreateTime(from.getCreateTime());
        to.setDueDate(from.getDueDate());
        to.setOverdue(from.isOverdue());
        to.setStartUserId(from.getStartUserId());
    }
}
