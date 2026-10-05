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

    // ==================== 挂起 / 恢复 ====================

    /**
     * 挂起一张待办：之后认领 / 办结 / 转办 / 委派 / 撤回 / 强制完成 / 跳转全部拒绝。
     *
     * <p><b>挂起后仍留在待办列表里</b>，也不改变任务状态。理由：挂起常是
     * "等某个条件成立"（等财务确认、等补材料），不是"这张单不存在"。
     * 把它从列表里藏起来，用户的感受是"我那张单不见了"，只会去问人，
     * 而恢复挂起本来只是一次点击。要只看未挂起的，查询传 {@code suspendedOnly=false}。
     *
     * <p>与流程实例挂起（{@code WfRuntimeService#suspendProcessInstance}）是两件事：
     * 那个停的是整个流程，这个只停这一张待办。
     *
     * @param taskId     任务 id
     * @param operatorId 操作人（记入任务变量，便于事后追"谁挂的"）
     */
    public WfTask suspendTask(String taskId, String operatorId) {
        WfTask task = requireTask(taskId);
        if (!task.isOpen()) {
            throw new WfEngineException("任务已结束，无法挂起: " + taskId);
        }
        if (task.isSuspended()) {
            // 重复挂起返回原任务而不是报错：定时任务/重试场景下重复调用很常见，
            // 报异常会让调用方必须自己判重
            return task;
        }
        task.setSuspended(true);
        task.getVariables().put("suspendedBy", operatorId);
        // 与 claim/transfer 等一致：改完必须 nextRevision 再存。
        // 漏掉这一步不是"少了个版本号"，而是每次挂起都撞乐观锁直接抛异常
        task.nextRevision();
        persistence.saveTask(task);
        return task;
    }

    /**
     * 恢复挂起的任务（对应 {@link #suspendTask}）。
     *
     * <p>{@code suspendedBy} 保留不删：它是"这张单为什么曾经停过"的唯一线索。
     */
    public WfTask activateTask(String taskId, String operatorId) {
        WfTask task = requireTask(taskId);
        if (!task.isSuspended()) {
            return task;
        }
        task.setSuspended(false);
        task.nextRevision();
        persistence.saveTask(task);
        return task;
    }

    // ==================== 候选池（运行时增删） ====================

    /**
     * 加一个候选人 —— 对应 z-camuda 的 {@code addCandidateUser}。
     *
     * <p>BPMN 里写的候选池是"流程画好时定的人",而现实里"这个单该谁批"经常要临时调：
     * 领导休假要加派、金额超了要加个 CFO、原本画错的候选要撤掉。只靠 BPMN 就只能改流程定义。
     *
     * <p><b>挂起的任务也允许改</b>：挂起是"等条件成立"，而换人正是等待期间最常见的处置。
     * 七个"办理类"闸门拦的是<b>把单办了</b>，候选池调整不是办理，不该被同一把锁挡住。
     * 已结束的任务则拒绝 —— 那时候改候选毫无意义。
     *
     * <p>重复加同一个人是幂等的（不报错、不产生重复项）。
     */
    public WfTask addCandidateUser(String taskId, String userId) {
        return addCandidate(taskId, userId, true, true);
    }

    /** 移出一个候选人。移除一个本来就不在池子里的人同样幂等。 */
    public WfTask removeCandidateUser(String taskId, String userId) {
        return addCandidate(taskId, userId, true, false);
    }

    /** 加一个候选组（对标 {@code addCandidateGroup}）。 */
    public WfTask addCandidateGroup(String taskId, String groupId) {
        return addCandidate(taskId, groupId, false, true);
    }

    /** 移出一个候选组。 */
    public WfTask removeCandidateGroup(String taskId, String groupId) {
        return addCandidate(taskId, groupId, false, false);
    }

    /**
     * 候选池增删的实现，用户与组共用。
     *
     * @param userScope {@code true} 动用户列表，{@code false} 动组列表
     * @param add       {@code true} 加入，{@code false} 移出
     */
    private WfTask addCandidate(String taskId, String value, boolean userScope, boolean add) {
        WfTask task = requireTask(taskId);
        if (!task.isOpen()) {
            throw new WfEngineException("任务已结束，无法调整候选池: " + taskId);
        }
        if (value == null || value.trim().isEmpty()) {
            // 空值静默忽略的话，调用方会以为加成功了，
            // 然后花很久去查"为什么这个人还是认领不了"
            throw new WfEngineException((userScope ? "候选人用户" : "候选组")
                    + "不能为空: task=" + taskId);
        }
        String id = value.trim();
        List<String> list = userScope ? task.getCandidateUsers() : task.getCandidateGroups();
        // 判断与执行必须分开写。曾经写成
        //     boolean changed = add ? !list.contains(id) : list.remove(id);
        // 三元里 add 分支只做了 contains 判断、从来没调 list.add(id) ——
        // 于是"加入候选人"是个空操作：不报错、幂等返回、看起来一切正常，
        // 但下一个候选人永远认领不了。探针实测才发现（保存前候选列表原封不动）。
        boolean changed;
        if (add) {
            // contains 判重：重复加不产生重复项
            changed = !list.contains(id);
            if (changed) {
                list.add(id);
            }
        } else {
            // 移一个本来就不在池子里的人是幂等的，不报错
            changed = list.remove(id);
        }
        if (!changed) {
            return task;
        }
        task.nextRevision();
        persistence.saveTask(task);
        log.info("任务 {} 候选池调整: {} {} {}", taskId,
                userScope ? "用户" : "组", add ? "加入" : "移出", id);
        return task;
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
        requireOperable(task, "认领");
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
        // 指派通知：此前只在建任务时发一次，之后谁接手都没人知道。
        // 审批系统里"这单到你手上了"正是最该通知的时刻。
        notifyAssigned(task, userId, from);
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
        requireOperable(task, "转办");
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
        notifyAssigned(task, toUserId, from);
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
        requireOperable(task, "委派");
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
        // 委派后责任仍在 owner 上，但事情在 toUserId 手上，通知要发给他
        notifyAssigned(task, toUserId, from);
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
        notifyAssigned(task, task.getAssignee(), from);
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
        requireOperable(task, "撤回");
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
        requireOperable(task, "强制完成");
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
        requireOperable(task, "跳转");
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
     *
     * <p>一次查询就够：{@link WfTaskQuery} 的 assignee + owner 是「或」语义
     * （委派态下活记在 owner 身上，被委派的人认领后才记在 assignee 身上）。
     *
     * <p>此前这里是"查两次 assignee、查两次 owner、内存去重、内存分页"，
     * 每次各限 1000 条。代价有三个，且都只有跑久了才暴露：
     * 超过 2000 条的待办被静默截断（分页根本翻不到后面）、
     * 页面上的"共 N 条"是去重后的条数而非真实总数、
     * 每翻一页都付两次全量拉取。
     * 绕路的根因是内存实现把 assignee/owner 当成「且」，
     * 表达不出「或」—— 那个不一致已修，现在不需要绕了。
     */
    public List<WfTask> getTodoList(String userId, List<String> groups, int pageNum, int pageSize) {
        return persistence.queryTasks(todoQuery(userId, groups)
                .setPageNum(pageNum).setPageSize(pageSize));
    }

    /**
     * 我的待办条数，与 {@link #getTodoList} 同条件。
     *
     * <p>单独给一个 count：端点要拿真实 total，不能靠"拉全量数长度"——
     * 那既是全表扫描，条数还会因为各处硬编码的上限而失真。
     */
    public long countTodoList(String userId, List<String> groups) {
        return persistence.countTasks(todoQuery(userId, groups));
    }

    /** 待办条件本身。抽出来是因为列表与计数必须同源，分开写迟早只改一处。 */
    /**
     * 待办查询：<b>办理人 / 责任人 / 候选用户 / 候选组，四者取或</b>。
     *
     * <p>以前这里只设了 assignee 与 owner，而 {@code groups} 参数接进来就被丢弃 ——
     * 结果是"我是候选人也看不到这张单"。候选池在 BPMN 里配得再对也没用，
     * 因为待办列表根本不查它：症状是流程配置无误、候选人却不出现，
     * 而开发期用的往往全是带 assignee 的流程，这条路径没人踩到。
     *
     * <p>四者必须是或：assignee/owner 与候选条件默认是且（精确筛选语义），
     * 直接一起设会把"我是办理人但不在候选池里"的任务过滤掉。
     */
    private WfTaskQuery todoQuery(String userId, List<String> groups) {
        WfTaskQuery query = new WfTaskQuery()
                .setCandidateOrAssigned(true)
                .setOpenOnly(true);
        if (userId != null && !userId.trim().isEmpty()) {
            query.setAssignee(userId);
            query.setOwner(userId);
            query.setCandidateUsers(java.util.Collections.singletonList(userId));
        }
        if (groups != null && !groups.isEmpty()) {
            query.setCandidateGroups(groups);
        }
        return query;
    }

    /**
     * 我的已办。
     */
    public List<WfTask> getDoneList(String userId, int pageNum, int pageSize) {
        return persistence.queryTasks(new WfTaskQuery()
                .setCompleterId(userId).setCompletedOnly(true)
                .setPageNum(pageNum).setPageSize(pageSize));
    }

    /** 我的已办条数，与 {@link #getDoneList} 同条件。 */
    public long countDoneList(String userId) {
        return persistence.countTasks(new WfTaskQuery()
                .setCompleterId(userId).setCompletedOnly(true));
    }

    /**
     * 可认领任务。
     */
    /**
     * 可认领列表：未分配、开放，且我在候选池里（按用户或按组）。
     *
     * <p>以前这里<b>只按候选组过滤、把 userId 丢掉了</b>：于是
     * ① 用 {@code candidateUsers} 配的流程谁也认领不了；
     * ② 每个人的可认领列表完全一样（实际是"全组可认领"的并集）。
     * 症状很隐蔽 —— 接口正常返回 200、列表也不空，只是内容不对。
     */
    public List<WfTask> getClaimableList(String userId, List<String> groups, int pageNum, int pageSize) {
        return persistence.queryTasks(claimableQuery(userId, groups)
                .setPageNum(pageNum).setPageSize(pageSize));
    }

    /**
     * 可认领总数。必须与 {@link #getClaimableList} 同条件，且**必须带 userId** ——
     * 旧签名只有 groups，导致不同用户的 total 相同，前端翻页时对不上。
     */
    public long countClaimableList(String userId, List<String> groups) {
        return persistence.countTasks(claimableQuery(userId, groups));
    }

    private WfTaskQuery claimableQuery(String userId, List<String> groups) {
        WfTaskQuery query = new WfTaskQuery()
                .setUnassignedOnly(true)
                .setOpenOnly(true)
                .setCandidateOrAssigned(true);
        if (userId != null && !userId.trim().isEmpty()) {
            query.setAssignee(userId);
            query.setCandidateUsers(java.util.Collections.singletonList(userId));
        }
        if (groups != null && !groups.isEmpty()) {
            query.setCandidateGroups(groups);
        }
        return query;
    }

    /** 可认领任务条数，与 {@link #getClaimableList} 同条件。 */


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

    /**
     * 任务级闸门：已结束与已挂起都拒绝，但<b>报错文案要分开</b>。
     *
     * <p>混成一句"任务已结束"的话，调用方看到会以为单子办完了，
     * 于是去查历史而不是去恢复挂起 —— 而挂起是能一键撤销的。
     *
     * <p>所有会改动任务状态的操作都必须过这一关，不许各自写
     * {@code isOpen()} —— 漏一处就等于挂起可以被绕过，而功能看上去是好的。
     */
    private void requireOperable(WfTask task, String action) {
        if (!task.isOpen()) {
            throw new WfEngineException("任务已结束，无法" + action + ": " + task.getId());
        }
        if (task.isSuspended()) {
            throw new WfEngineException("任务已挂起，无法" + action + ": " + task.getId()
                    + "。如需继续请先调用 WfTaskService#activateTask");
        }
    }

    /**
     * 发出"任务指派"通知。
     *
     * <p>此前 {@code notifyTaskAssigned} 只在建任务时触发一次，
     * 于是认领 / 转办 / 委派之后接手人都收不到通知 ——
     * 而"这单到你手上了"恰恰是审批场景里最该通知的时刻。
     *
     * <p>processKey 与 variables 要从流程实例反查，因为任务本身不携带这两项。
     */
    private void notifyAssigned(WfTask task, String toUserId, String fromUserId) {
        if (toUserId == null) {
            return;
        }
        WfProcessInstance instance = persistence.findProcessInstance(task.getProcessInstanceId());
        hookDispatcher.notifyTaskAssigned(task.getId(), task.getProcessInstanceId(), toUserId,
                instance == null ? null : instance.getDefinitionKey(), fromUserId,
                instance == null ? null : instance.getVariables());
    }
}
