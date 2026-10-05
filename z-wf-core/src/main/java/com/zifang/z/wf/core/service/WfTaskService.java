package com.zifang.z.wf.core.service;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 任务服务 —— 待办的生命周期与流转。
 *
 * <p>对应 z-camuda 的 {@code TaskOperationController} 暴露的六类操作。
 * 这六个操作的<b>语义差别</b>是审批系统最容易做错的地方，本类逐一定死：
 *
 * <table border="1">
 *   <tr><th>操作</th><th>assignee</th><th>owner</th><th>状态</th><th>语义</th></tr>
 *   <tr><td>claim 认领</td><td>→ 认领人</td><td>不变</td><td>ASSIGNED</td>
 *       <td>从候选池中取走活，责任人转移</td></tr>
 *   <tr><td>transfer 转办</td><td>→ 新人</td><td>不变</td><td>ASSIGNED</td>
 *       <td>责任人直接换人（原责任人不再有责）</td></tr>
 *   <tr><td>delegate 委派</td><td><b>不变</b></td><td>→ 被委派人</td><td>DELEGATED</td>
 *       <td>处理权转走但责任不转（让下属代批，事主仍担责）</td></tr>
 *   <tr><td>resolve 收回</td><td>不变</td><td>→ null</td><td>ASSIGNED</td>
 *       <td>被委派人把处理权还给原责任人</td></tr>
 *   <tr><td>withdraw 撤回</td><td>→ null</td><td>→ null</td><td>CREATED</td>
 *       <td>发起人把未办结任务打回候选池</td></tr>
 *   <tr><td>forceComplete 强制完成</td><td>不变</td><td>不变</td><td>COMPLETED</td>
 *       <td>跳过办理人直接办结（管理员/超时兜底）</td></tr>
 * </table>
 *
 * <p><b>delegate 与 transfer 的区别是本类最重要的设计</b>：前者只换 owner 不换 assignee，
 * 所以"已办列表"仍能查到原责任人办过这一条（合规审计需要），
 * 而"待办列表"查的是 effectiveHandler()，被委派人能看到活。
 *
 * @author zifang
 */
public class WfTaskService {

    private static final Logger log = LoggerFactory.getLogger(WfTaskService.class);

    private final WfPersistence persistence;

    private final WfRepositoryService repositoryService;

    private final WfRuntimeService runtimeService;

    private final WfHookDispatcher hookDispatcher;

    public WfTaskService(WfRepositoryService repositoryService,
                         WfPersistence persistence,
                         WfRuntimeService runtimeService,
                         WfHookDispatcher hookDispatcher) {
        this.repositoryService = repositoryService;
        this.persistence = persistence;
        this.runtimeService = runtimeService;
        this.hookDispatcher = hookDispatcher;
    }

    // ==================== 认领 ====================

    /**
     * 认领任务 —— 从候选池取走。
     *
     * @param userId    认领人
     * @param userGroups 认领人所属组（用于候选组判定）
     * @return 任务
     */
    public WfTask claim(String taskId, String userId, List<String> userGroups) {
        WfTask task = requireTask(taskId);
        if (!task.isOpen()) {
            throw new WfEngineException("任务已结束，无法认领: " + taskId);
        }
        if (task.getAssignee() != null && !task.getAssignee().trim().isEmpty()) {
            throw new WfEngineException("任务 " + taskId + " 已有办理人 "
                    + task.getAssignee() + "，请用转办而非认领");
        }
        if (!task.isClaimableBy(userId, userGroups)) {
            throw new WfEngineException("用户 " + userId + " 不在任务 " + taskId + " 的候选人范围内");
        }

        String from = task.getAssignee();
        task.setAssignee(userId);
        task.setStatus(WfTask.Status.ASSIGNED);
        task.nextRevision();
        persistence.saveTask(task);

        hookDispatcher.fireAssigneeChanged(taskId, from, userId, "claim");
        log.info("任务认领: {} → {}", taskId, userId);
        return task;
    }

    /**
     * 取消认领 —— 放回候选池。
     */
    public WfTask unclaim(String taskId, String userId) {
        WfTask task = requireTask(taskId);
        if (!userId.equals(task.getAssignee())) {
            throw new WfEngineException("只有办理人本人可取消认领，当前办理人为 " + task.getAssignee());
        }
        String from = task.getAssignee();
        task.setAssignee(null);
        task.setStatus(WfTask.Status.CREATED);
        task.nextRevision();
        persistence.saveTask(task);
        hookDispatcher.fireAssigneeChanged(taskId, from, null, "unclaim");
        return task;
    }

    // ==================== 转办 ====================

    /**
     * 转办 —— 责任人直接换人（原责任人不再有责）。
     */
    public WfTask transfer(String taskId, String fromUserId, String toUserId, String comment) {
        WfTask task = requireTask(taskId);
        if (!task.isOpen()) {
            throw new WfEngineException("任务已结束，无法转办: " + taskId);
        }
        if (fromUserId != null && !fromUserId.equals(task.effectiveHandler())) {
            throw new WfEngineException("只有当前处理人可转办，当前处理人为 "
                    + task.effectiveHandler());
        }
        if (toUserId == null || toUserId.trim().isEmpty()) {
            throw new WfEngineException("转办目标人不能为空");
        }
        String from = task.effectiveHandler();
        task.setAssignee(toUserId);
        task.setOwner(null);
        task.setStatus(WfTask.Status.ASSIGNED);
        if (comment != null) {
            task.getVariables().put("transferComment", comment);
        }
        task.nextRevision();
        persistence.saveTask(task);

        hookDispatcher.fireAssigneeChanged(taskId, from, toUserId, "transfer");
        log.info("任务转办: {} → {} ({})", from, toUserId, taskId);
        return task;
    }

    // ==================== 委派 ====================

    /**
     * 委派 —— 处理权转走但责任不转。
     *
     * <p>{@code assignee} 保持不变，{@code owner} 变成被委派人。
     * 于是：被委派人在待办里看得到（effectiveHandler 走 owner），
     * 而"已办/审计"里记录的仍是原责任人。
     */
    public WfTask delegate(String taskId, String fromUserId, String toUserId, String comment) {
        WfTask task = requireTask(taskId);
        if (!task.isOpen()) {
            throw new WfEngineException("任务已结束，无法委派: " + taskId);
        }
        if (fromUserId != null && !fromUserId.equals(task.effectiveHandler())) {
            throw new WfEngineException("只有当前处理人可委派，当前处理人为 "
                    + task.effectiveHandler());
        }
        if (toUserId == null || toUserId.trim().isEmpty()) {
            throw new WfEngineException("被委派人不能为空");
        }
        if (toUserId.equals(task.getAssignee())) {
            throw new WfEngineException("不能委派给责任人本人");
        }

        String from = task.effectiveHandler();
        task.getDelegateChain().add(new WfTask.DelegateHop(from, toUserId, new Date()));
        task.setOwner(toUserId);
        // assignee 不动 —— 责任不转移
        task.setStatus(WfTask.Status.DELEGATED);
        if (comment != null) {
            task.getVariables().put("delegateComment", comment);
        }
        task.nextRevision();
        persistence.saveTask(task);

        hookDispatcher.fireAssigneeChanged(taskId, from, toUserId, "delegate");
        log.info("任务委派: {} 委派给 {}（责任人仍为 {}）, taskId={}",
                from, toUserId, task.getAssignee(), taskId);
        return task;
    }

    /**
     * 收回委派 —— 被委派人把处理权还给原责任人。
     */
    public WfTask resolve(String taskId, String userId) {
        WfTask task = requireTask(taskId);
        if (task.getStatus() != WfTask.Status.DELEGATED) {
            throw new WfEngineException("任务不在委派态，无法收回: " + taskId);
        }
        if (userId != null && !userId.equals(task.getOwner())) {
            throw new WfEngineException("只有被委派人可收回委派，当前被委派人为 " + task.getOwner());
        }
        String from = task.getOwner();
        task.setOwner(null);
        task.setStatus(WfTask.Status.ASSIGNED);
        task.nextRevision();
        persistence.saveTask(task);

        hookDispatcher.fireAssigneeChanged(taskId, from, task.getAssignee(), "resolve");
        return task;
    }

    // ==================== 撤回 ====================

    /**
     * 撤回任务 —— 放回候选池等重新认领。
     *
     * <p>与"取消流程"是两件事：撤回只回收<b>这一个任务</b>，流程继续挂在原地。
     * 典型场景是"审批人填错了，要重新指派"。
     */
    public WfTask withdraw(String taskId, String operatorId, String reason) {
        WfTask task = requireTask(taskId);
        if (!task.isOpen()) {
            throw new WfEngineException("任务已结束，无法撤回: " + taskId);
        }
        String from = task.effectiveHandler();
        task.setAssignee(null);
        task.setOwner(null);
        task.setStatus(WfTask.Status.CREATED);
        if (reason != null) {
            task.getVariables().put("withdrawReason", reason);
        }
        task.nextRevision();
        persistence.saveTask(task);

        hookDispatcher.fireAssigneeChanged(taskId, from, null, "withdraw");
        log.info("任务撤回: {} (原处理人 {}), 原因={}", taskId, from, reason);
        return task;
    }

    // ==================== 强制完成 ====================

    /**
     * 强制完成任务 —— 绕过办理人权限。
     *
     * <p>用于管理员兜底与超时自动办结。<b>不做办理人身份校验</b>，
     * 这是刻意的（它存在的意义就是绕过校验），但调用方（web 层）必须自行限制
     * 该接口的访问权限。
     */
    public WfProcessInstance forceComplete(String taskId, String operatorId, String comment,
                                           Map<String, Object> variables) {
        WfTask task = requireTask(taskId);
        if (!task.isOpen()) {
            throw new WfEngineException("任务已结束，无法强制完成: " + taskId);
        }
        // 强制完成时跳过"办结人必须是处理人"的校验：把办结人记为操作人
        Map<String, Object> merged = variables == null
                ? new HashMap<String, Object>() : new HashMap<>(variables);
        merged.put("forceCompletedBy", operatorId);

        log.info("任务强制完成: {} (操作人 {}), 流程={}", taskId, operatorId, task.getProcessInstanceId());
        return runtimeService.completeTask(taskId, task.effectiveHandler(), comment, merged);
    }

    // ==================== 跳转 ====================

    /**
     * 任务跳转 —— 把当前 token 强行移到指定节点。
     *
     * <p>这是审批里最"脏"但也最常用的操作（"这个审批人不管了，直接跳给总经理"）。
     * 实现方式是：结束当前任务 + 把 token 移到目标节点 + 推进。
     *
     * <p>注意：跳转<b>不检查图上是否可达</b>。这是刻意的 ——
     * 跳过的图结构往往是"设计时没考虑到但业务现在需要"的场景，
     * 如果引擎强行校验可达性，运营改一次流程就得重画图。
     * 代价是可能跳出到非法的图外节点，因此目标节点必须在定义里存在。
     *
     * @param targetActivityId 目标节点 ID
     */
    public WfProcessInstance jump(String taskId, String operatorId, String targetActivityId,
                                  String comment) {
        WfTask task = requireTask(taskId);
        if (!task.isOpen()) {
            throw new WfEngineException("任务已结束，无法跳转: " + taskId);
        }
        WfProcessInstance instance = runtimeService.getProcessInstance(task.getProcessInstanceId());
        if (instance == null) {
            throw new WfEngineException("流程实例不存在: " + task.getProcessInstanceId());
        }

        WfDefinition definition =
                repositoryService.getDefinitionOrLatest(
                        instance.getDefinitionKey(), instance.getDefinitionVersion());
        if (definition.node(targetActivityId) == null) {
            throw new WfEngineException("目标节点不存在于流程定义中: " + targetActivityId);
        }

        // ---- 1. 结束当前任务（记为"跳过"）----
        task.setStatus(WfTask.Status.COMPLETED);
        task.setCompleterId(operatorId);
        task.setComment(comment);
        task.setEndTime(new Date());
        task.getVariables().put("jumpedBy", operatorId);
        task.getVariables().put("jumpedTo", targetActivityId);
        task.nextRevision();
        persistence.saveTask(task);

        // ---- 2. 移动 token ----
        WfExecution execution = task.getExecutionId() != null
                ? persistence.findExecution(task.getExecutionId()) : null;
        if (execution == null) {
            for (WfExecution candidate
                    : persistence.findExecutionsByProcessInstance(instance.getId())) {
                if (task.getDefinitionId() != null
                        && task.getDefinitionId().equals(candidate.getActivityId())) {
                    execution = candidate;
                    break;
                }
            }
        }
        if (execution == null) {
            throw new WfEngineException("任务 " + taskId + " 找不到对应 token，无法跳转");
        }

        execution.setActivityId(targetActivityId);
        execution.setState(WfExecution.State.ACTIVE);
        execution.setEnteredTime(new Date());
        // 清空到达记录：跳转后要能重新汇合
        execution.clearArrived();
        persistence.saveExecution(execution);

        // ---- 3. 从新位置推进 ----
        WfEngine engine = new WfEngine();
        WfContext context = engine.newContext(definition, instance, execution);
        context.setAuthenticatedUserId(operatorId);
        engine.advance(context);

        log.info("任务跳转: taskId={} → 节点 {}, 操作人={}", taskId, targetActivityId, operatorId);
        return instance;
    }

    // ==================== 查询 ====================

    /**
     * 我的待办。
     */
    public List<WfTask> getTodoList(String userId, List<String> groups, int pageNum, int pageSize) {
        List<WfTask> result = new java.util.ArrayList<>();
        // 待办 = 指派给我的 + 委派给我的
        result.addAll(persistence.queryTasks(new WfTaskQuery()
                .setAssignee(userId).setOpenOnly(true)
                .setPageNum(1).setPageSize(1000)));
        result.addAll(persistence.queryTasks(new WfTaskQuery()
                .setOwner(userId).setOpenOnly(true)
                .setPageNum(1).setPageSize(1000)));
        // 去重（既是 assignee 又是 owner 的会重复）
        Map<String, WfTask> dedup = new HashMap<>();
        for (WfTask task : result) {
            dedup.put(task.getId(), task);
        }
        List<WfTask> all = new java.util.ArrayList<>(dedup.values());
        all.sort((a, b) -> {
            if (a.getPriority() != b.getPriority()) {
                return b.getPriority() - a.getPriority();
            }
            Date ca = a.getCreateTime();
            Date cb = b.getCreateTime();
            if (ca == null || cb == null) {
                return 0;
            }
            return cb.compareTo(ca);
        });
        int from = Math.max(0, (pageNum - 1) * pageSize);
        int to = Math.min(all.size(), from + pageSize);
        return from >= all.size() ? new java.util.ArrayList<WfTask>() : all.subList(from, to);
    }

    /**
     * 我的已办。
     */
    public List<WfTask> getDoneList(String userId, int pageNum, int pageSize) {
        return persistence.queryTasks(new WfTaskQuery()
                .setCompleterId(userId).setCompletedOnly(true)
                .setPageNum(pageNum).setPageSize(pageSize));
    }

    /**
     * 可认领任务。
     */
    public List<WfTask> getClaimableList(String userId, List<String> groups, int pageNum, int pageSize) {
        return persistence.queryTasks(new WfTaskQuery()
                .setUnassignedOnly(true).setOpenOnly(true)
                .setCandidateGroups(groups)
                .setPageNum(pageNum).setPageSize(pageSize));
    }

    public WfTask getTask(String taskId) {
        return requireTask(taskId);
    }

    /**
     * 某流程实例的全部未完成任务（待办详情页用）。
     */
    public List<WfTask> getOpenTasks(String processInstanceId) {
        return persistence.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(processInstanceId).setOpenOnly(true));
    }

    /**
     * 设置优先级 / 到期时间 / 候选组等管理属性。
     */
    public WfTask updateTask(String taskId, Integer priority, Date dueDate,
                             List<String> candidateUsers, List<String> candidateGroups) {
        WfTask task = requireTask(taskId);
        if (priority != null) {
            task.setPriority(priority);
        }
        if (dueDate != null) {
            task.setDueDate(dueDate);
        }
        if (candidateUsers != null) {
            task.setCandidateUsers(candidateUsers);
        }
        if (candidateGroups != null) {
            task.setCandidateGroups(candidateGroups);
        }
        task.nextRevision();
        persistence.saveTask(task);
        return task;
    }

    private WfTask requireTask(String id) {
        WfTask task = persistence.findTask(id);
        if (task == null) {
            throw new WfEngineException("任务不存在: " + id);
        }
        return task;
    }
}
