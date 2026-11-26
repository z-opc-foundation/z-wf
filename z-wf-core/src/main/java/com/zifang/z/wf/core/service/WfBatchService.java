package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.define.TypeReference;
import com.zifang.z.wf.core.engine.WfIdGenerator;
import com.zifang.z.wf.core.model.WfBatch;
import com.zifang.z.wf.core.model.WfBatchElement;
import com.zifang.z.wf.core.model.WfBatchOperation;
import com.zifang.z.wf.core.model.WfBatchCriteria;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.WfBatchQuery;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.persistence.WfProcessInstanceQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 批量操作（第 39 轮）—— Camunda 的 {@code Batch}。
 *
 * <p><b>两段式</b>是这一整套东西的骨架：
 * {@link #createBatch} 只把「改哪些」「改什么」<b>记下来</b>，
 * {@link #countTargets} 让你在动手之前先看看会命中多少，
 * {@link #executeBatch} 才真正去改。
 * 分成两段不是为了像谁 —— 是因为「看到要改什么再决定改不改」本身就是需求。
 * 一条「把所有超时任务的 retries 清零」的操作，确认之前不该有任何数据被动。
 *
 * <p><b>逐个目标独立成败</b>：{@link #executeBatch} 内部对每个目标单独
 * try/catch，成功记 {@link WfBatchElement.State#SUCCESS}、失败记
 * {@link WfBatchElement.State#FAILED} 加一句原因。
 * 一批 1000 个失败 3 个，另外 997 个照样改完。
 *
 * <p><b>目标解析在执行时才做，而不是创建时</b>：创建时只记条件，
 * 命中的目标是执行那一刻的状态。这与 Camunda 一致，也是唯一说得通的做法 ——
 * 「三天前符合条件的那些」与「今天符合条件的」在批量改写场景里是不同的东西，
 * 哪种是对的没法猜，而它必须是同一个明确的东西。
 *
 * @author zifang
 */
public class WfBatchService {

    private static final Logger log = LoggerFactory.getLogger(WfBatchService.class);

    /**
     * 遍历目标时的分页大小。
     *
     * <p>分页而不是一次性把十万个目标全装进内存：批量的定义就是「很多个」。
     *
     * <p><b>分页在这里是安全的</b>，前提是<b>没有操作会改变该批次类型的筛选字段</b> ——
     * 实例的筛选看的是定义 / 业务键 / 状态 / 起始时间，任务看的是办理人 / 状态 / 创建时间，
     * job 看的是到期时刻 / 重试 / 类型，而本类提供的操作只改变量、挂起态、重试次数、优先级，
     * 一个都不碰筛选字段。所以翻页时前面那些页的命中集合不会变，不会漏掉目标。
     * <b>新增操作时必须守住这条</b>：加一个改状态的操作会让分页漏目标，
     * 而且症状极隐蔽（改少了，但每一条改的都是对的）。
     */
    private static final int PAGE_SIZE = 200;

    /** 挂起时没有给原因，用这句顶上 —— 悬空的 {@code suspendReason} 在运维界面上读不出任何东西。 */
    private static final String DEFAULT_SUSPEND_REASON = "批量操作挂起";

    private final WfPersistence persistence;

    private final WfIdGenerator idGenerator;

    private final WfRuntimeService runtimeService;

    private final WfVariableService variableService;

    private final WfTaskService taskService;

    public WfBatchService(WfPersistence persistence, WfIdGenerator idGenerator,
                          WfRuntimeService runtimeService, WfVariableService variableService,
                          WfTaskService taskService) {
        this.persistence = persistence;
        this.idGenerator = idGenerator;
        this.runtimeService = runtimeService;
        this.variableService = variableService;
        this.taskService = taskService;
    }

    // ==================== 创建 ====================

    /**
     * 创建一个批次。**什么都不改。**
     *
     * @param batchType 作用对象类型；决定哪些操作合法、哪些筛选字段被认
     * @param criteria 改哪些
     * @param operations 改什么，至少一条
     * @param operatorId 谁建的，可空
     * @return 已落库的批次，状态 {@link WfBatch.State#CREATED}
     */
    public WfBatch createBatch(WfBatch.Type batchType, WfBatchCriteria criteria,
                               List<WfBatchOperation> operations, String operatorId) {
        if (batchType == null) {
            throw new WfEngineException("批次必须有作用对象类型");
        }
        if (criteria == null) {
            throw new WfEngineException("批次必须有筛选条件");
        }
        if (operations == null || operations.isEmpty()) {
            throw new WfEngineException("批次至少要有一条操作");
        }
        // 「这个类型下一个条件都不给」= 改这个类型下的全部东西。在进程实例上，那就是十万条。
        // **按类型判**，不是整体判空：传 processDefinitionKey 配一个 TASK 批次，
        // 整体是有内容的，但任务查询根本不读它 —— 于是命中全部任务，
        // 而记录上写着「按定义 key 筛过」。宁可在这里问一句
        if (criteria.isEmptyFor(batchType)) {
            throw new WfEngineException("对批次类型 " + batchType
                    + " 来说筛选条件是空的，那等于「改这个类型下的全部」；"
                    + "请至少给一个该类型的条件，或直接列出 ids");
        }
        for (WfBatchOperation operation : operations) {
            String error = operation == null ? "操作不能为 null" : operation.validate(batchType);
            if (error != null) {
                throw new WfEngineException("批次操作不合法: " + error);
            }
        }

        WfBatch batch = new WfBatch();
        batch.setId(idGenerator.nextBatchId());
        batch.setBatchType(batchType);
        batch.setCriteria(JsonUtil.toJson(criteria));
        batch.setOperations(JsonUtil.toJson(operations));
        batch.setState(WfBatch.State.CREATED);
        batch.setCreateTime(new Date());
        batch.setOperatorId(operatorId);
        persistence.saveBatch(batch);
        log.info("批次已创建: {}, 类型={}, 条件={}, 操作 {} 条，操作人={}",
                batch.getId(), batchType, criteria, operations.size(), operatorId);
        return batch;
    }

    // ==================== 查询 ====================

    public WfBatch getBatch(String batchId) {
        return persistence.findBatch(batchId);
    }

    private WfBatch requireBatch(String batchId) {
        WfBatch batch = batchId == null ? null : persistence.findBatch(batchId);
        if (batch == null) {
            throw new WfEngineException("批次不存在: " + batchId);
        }
        return batch;
    }

    /**
     * 现在执行会命中多少个目标 —— <b>确认之前的那一眼</b>。
     *
     * <p>不落任何东西：数一遍而已，所以可以随便调。
     */
    public long countTargets(String batchId) {
        WfBatch batch = requireBatch(batchId);
        return countTargets(batch, readCriteria(batch));
    }

    private long countTargets(WfBatch batch, WfBatchCriteria criteria) {
        if (hasExplicitIds(criteria)) {
            // 点名的一律照算，哪怕那个 id 已经不存在了 ——
            // 少报比报错好：执行时那一例会作为失败项列出来，那才是它该出现的地方
            return criteria.getIds().size();
        }
        switch (batch.getBatchType()) {
            case INSTANCE:
                return persistence.countProcessInstances(toInstanceQuery(criteria));
            case TASK:
                return persistence.countTasks(toTaskQuery(criteria));
            case JOB:
                return persistence.countJobs(toJobQuery(criteria));
            default:
                throw new WfEngineException("不支持的批次类型: " + batch.getBatchType());
        }
    }

    /** 某批次的全部明细，按写入顺序（即目标命中顺序）。 */
    public List<WfBatchElement> listElements(String batchId) {
        return persistence.findBatchElements(batchId);
    }

    /** 某批次的失败明细。运维打开一个失败批次时基本只想看这些。 */
    public List<WfBatchElement> listFailedElements(String batchId) {
        return persistence.findFailedBatchElements(batchId);
    }

    public List<WfBatch> queryBatches(WfBatchQuery query) {
        return persistence.queryBatches(query);
    }

    public int countBatches(WfBatchQuery query) {
        return persistence.countBatches(query);
    }

    // ==================== 挂起 / 激活 / 删除 ====================

    /**
     * 挂起一个批次。
     *
     * <p><b>只拦"还没执行"的批次</b>：已经 COMPLETED 的批次挂它没有意义 ——
     * 目标都改完了，「别再改」这句话对着一件已经做完的事说，答案是"已经在做了"。
     * EXECUTING 的批次则明确拒：这个引擎的执行是同步的，
     * 另一个线程正在改的时候说「别改了」，没有任何机制能保证它停在半路。
     */
    public WfBatch suspendBatch(String batchId, String operatorId) {
        WfBatch batch = requireBatch(batchId);
        if (batch.getState() != WfBatch.State.CREATED) {
            throw new WfEngineException("只有未执行的批次可以挂起，当前状态: " + batch.getState());
        }
        batch.setSuspended(true);
        batch.nextRevision();
        persistence.saveBatch(batch);
        log.info("批次已挂起: {}, 操作人={}", batchId, operatorId);
        return batch;
    }

    public WfBatch activateBatch(String batchId, String operatorId) {
        WfBatch batch = requireBatch(batchId);
        if (!batch.isSuspended()) {
            // 不报错：激活一个本来就没被挂起的批次是幂等的，
            // 而运维流程里「确保它没被挂起」这句话会被反复执行
            log.debug("批次本来就没被挂起: {}", batchId);
            return batch;
        }
        batch.setSuspended(false);
        batch.nextRevision();
        persistence.saveBatch(batch);
        log.info("批次已激活: {}, 操作人={}", batchId, operatorId);
        return batch;
    }

    /** 物理删除批次及其全部明细。已执行的批次也允许删 —— 明细留着只会一直占地方。 */
    public boolean deleteBatch(String batchId) {
        return batchId != null && persistence.deleteBatch(batchId);
    }

    // ==================== 执行 ====================

    /**
     * 执行一个批次。<b>逐个目标独立成败</b>。
     *
     * @param batchId 批次 id
     * @param now 本次执行的时刻；显式传入而不是内部取 {@code new Date()}，
     *            是为了让测试能确定时间基准（与 {@code executeDueJobs(Date)} 同一理由）
     * @return 执行后的批次（{@code affectedCount} / {@code failureCount} 已填）
     */
    public WfBatch executeBatch(String batchId, Date now) {
        WfBatch batch = requireBatch(batchId);
        if (batch.isSuspended()) {
            throw new WfEngineException("批次已被挂起，先激活再执行: " + batchId);
        }
        if (batch.getState() != WfBatch.State.CREATED) {
            // 重复执行是运维最容易犯的错（点了两次）。这里必须拦：
            // 幂等的话第二次会安静地什么都不做，看记录的人以为执行过了
            throw new WfEngineException("批次已经执行过，当前状态: " + batch.getState()
                    + "。批次不做二次执行 —— 要重来请新建一个批次");
        }

        WfBatchCriteria criteria = readCriteria(batch);
        List<WfBatchOperation> operations = readOperations(batch);

        batch.setState(WfBatch.State.EXECUTING);
        batch.setStartTime(now);
        batch.nextRevision();
        persistence.saveBatch(batch);

        int affected = 0;
        int failed = 0;
        try {
            List<String> targets = resolveTargets(batch, criteria);
            log.info("批次开始执行: {}, 命中 {} 个目标", batchId, targets.size());
            for (String targetId : targets) {
                if (applyToTarget(batch, targetId, operations)) {
                    affected++;
                } else {
                    failed++;
                }
            }
            batch.setState(WfBatch.State.COMPLETED);
        } catch (RuntimeException e) {
            // 走到这里说明是「循环之外」出了问题（解析条件失败、落库失败）。
            // 已经改掉的目标不会回退 —— 批次没有撤销这回事，
            // 改成什么样照旧，靠 element 表把改过的是谁列出来给运维照着补
            batch.setState(WfBatch.State.FAILED);
            batch.setFailureReason(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.error("批次执行中断: {}, 已改 {} 个、失败 {} 个", batchId, affected, failed, e);
        }
        batch.setAffectedCount(affected);
        batch.setFailureCount(failed);
        batch.setEndTime(now);
        batch.nextRevision();
        persistence.saveBatch(batch);
        return batch;
    }

    /**
     * 对一个目标依次施加全部操作。
     *
     * @return 是否全部成功
     */
    private boolean applyToTarget(WfBatch batch, String targetId, List<WfBatchOperation> operations) {
        String failure = null;
        try {
            switch (batch.getBatchType()) {
                case INSTANCE:
                    applyToInstance(targetId, operations, batch.getOperatorId());
                    break;
                case TASK:
                    applyToTask(targetId, operations, batch.getOperatorId());
                    break;
                case JOB:
                    applyToJob(targetId, operations, batch.getOperatorId());
                    break;
                default:
                    throw new WfEngineException("不支持的批次类型: " + batch.getBatchType());
            }
        } catch (RuntimeException e) {
            // 只抓 RuntimeException：Error（OOM、栈溢出）放它上去，
            // 让整批按 FAILED 中止，而不是把内存耗尽也记成「这一个目标失败了」
            failure = describe(e);
        }
        record(batch, targetId, failure);
        return failure == null;
    }

    private void applyToInstance(String instanceId, List<WfBatchOperation> operations, String operatorId) {
        for (WfBatchOperation operation : operations) {
            switch (operation.getType()) {
                case SET_VARIABLE:
                    variableService.setVariable(instanceId, operation.getVariable(),
                            operation.getValue(), operatorId);
                    break;
                case SET_VARIABLES:
                    variableService.setVariables(instanceId, operation.getVariables(), operatorId);
                    break;
                case REMOVE_VARIABLE:
                    variableService.removeVariable(instanceId, operation.getVariable(), operatorId);
                    break;
                case SUSPEND:
                    runtimeService.suspend(instanceId, suspendReason(operation));
                    break;
                case ACTIVATE:
                    runtimeService.activate(instanceId);
                    break;
                case SET_JOB_RETRIES:
                    setInstanceJobRetries(instanceId, operation.getRetries());
                    break;
                default:
                    throw new WfEngineException("操作 " + operation.getType().getCode()
                            + " 不适用于流程实例");
            }
        }
    }

    private void applyToTask(String taskId, List<WfBatchOperation> operations, String operatorId) {
        for (WfBatchOperation operation : operations) {
            switch (operation.getType()) {
                case SET_VARIABLE:
                    variableService.setTaskVariable(taskId, operation.getVariable(),
                            operation.getValue(), operatorId);
                    break;
                case SUSPEND:
                    taskService.suspendTask(taskId, operatorId);
                    break;
                case ACTIVATE:
                    taskService.activateTask(taskId, operatorId);
                    break;
                default:
                    throw new WfEngineException("操作 " + operation.getType().getCode()
                            + " 不适用于任务");
            }
        }
    }

    private void applyToJob(String jobId, List<WfBatchOperation> operations, String operatorId) {
        // job 的操作只有改属性这一类，所以只在真要改时才会去查这一行
        WfJob job = null;
        for (WfBatchOperation operation : operations) {
            switch (operation.getType()) {
                case SET_JOB_RETRIES:
                    job = job == null ? requireJob(jobId) : job;
                    job.setRetries(operation.getRetries());
                    break;
                case SET_PRIORITY:
                    job = job == null ? requireJob(jobId) : job;
                    job.setPriority(operation.getPriority());
                    break;
                default:
                    throw new WfEngineException("操作 " + operation.getType().getCode()
                            + " 不适用于 job");
            }
        }
        if (job != null) {
            // 一次 save：两条改优先级的操作不该产生两次乐观锁版本推进
            job.nextRevision();
            persistence.saveJob(job);
        }
    }

    /**
     * 把一个实例名下<b>全部</b> job 的重试次数设成同一个值。
     *
     * <p>与 job 级同名操作的差别就在粒度：那个改一个 job，这个改这个实例的每一个。
     * 「昨晚那次发布把三条流程的定时器全打挂了，现在给它们补三次重试」
     * ——按实例点名才是运维脑子里的那个单位。
     */
    private void setInstanceJobRetries(String instanceId, int retries) {
        WfJobQuery query = new WfJobQuery().setProcessInstanceId(instanceId);
        List<WfJob> jobs = persistence.queryJobs(query);
        for (WfJob job : jobs) {
            job.setRetries(retries);
            job.nextRevision();
            persistence.saveJob(job);
        }
        log.debug("实例 {} 的 {} 个 job 重试次数设为 {}", instanceId, jobs.size(), retries);
    }

    private WfJob requireJob(String jobId) {
        WfJob job = persistence.findJob(jobId);
        if (job == null) {
            throw new WfEngineException("job 不存在: " + jobId);
        }
        return job;
    }

    private String suspendReason(WfBatchOperation operation) {
        return isBlank(operation.getReason()) ? DEFAULT_SUSPEND_REASON : operation.getReason();
    }

    /** 落一条明细。<b>失败也要落</b> —— 见 {@link WfBatchElement} 的类注释。 */
    private void record(WfBatch batch, String targetId, String failure) {
        WfBatchElement element = new WfBatchElement(batch.getId(), batch.getBatchType(), targetId,
                failure == null ? WfBatchElement.State.SUCCESS : WfBatchElement.State.FAILED,
                failure, batch.getEndTime());
        element.setId(idGenerator.nextBatchElementId());
        persistence.saveBatchElement(element);
    }

    /**
     * 失败原因压成一行。
     *
     * <p>存的是 {@code 类名: 消息} 而不是只有消息：不同引擎层的异常
     * 消息会长得一模一样，光看消息分不出「是变量服务拒绝的还是任务服务拒绝的」。
     */
    private String describe(Throwable e) {
        String message = e.getMessage();
        String text = e.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message);
        // 明细列宽 2048。异常消息偶尔会很长（内嵌整段 SQL），
        // 截断而不是让它把 INSERT 顶爆 —— 顶爆的话这一批的失败原因一条都存不下来
        return text.length() <= 2000 ? text : text.substring(0, 2000) + "…（已截断）";
    }

    // ==================== 目标解析 ====================

    private List<String> resolveTargets(WfBatch batch, WfBatchCriteria criteria) {
        if (hasExplicitIds(criteria)) {
            // 点名的按原样照办，包括那个其实已经不存在的 id ——
            // 它会在 applyToTarget 里作为一条失败明细列出来，
            // 这正是运维要知道的事（「我点的第三个单子已经没了」）
            return new ArrayList<String>(criteria.getIds());
        }
        List<String> ids = new ArrayList<>();
        int page = 1;
        while (true) {
            List<String> pageIds = queryOnePage(batch, criteria, page);
            ids.addAll(pageIds);
            if (pageIds.size() < PAGE_SIZE) {
                return ids;
            }
            page++;
        }
    }

    private List<String> queryOnePage(WfBatch batch, WfBatchCriteria criteria, int page) {
        switch (batch.getBatchType()) {
            case INSTANCE: {
                WfProcessInstanceQuery query = toInstanceQuery(criteria).setPageNum(page).setPageSize(PAGE_SIZE);
                List<String> ids = new ArrayList<>();
                for (WfProcessInstance instance : persistence.queryProcessInstances(query)) {
                    ids.add(instance.getId());
                }
                return ids;
            }
            case TASK: {
                WfTaskQuery query = toTaskQuery(criteria).setPageNum(page).setPageSize(PAGE_SIZE);
                List<String> ids = new ArrayList<>();
                for (WfTask task : persistence.queryTasks(query)) {
                    ids.add(task.getId());
                }
                return ids;
            }
            case JOB: {
                WfJobQuery query = toJobQuery(criteria).setPageNum(page).setPageSize(PAGE_SIZE);
                List<String> ids = new ArrayList<>();
                for (WfJob job : persistence.queryJobs(query)) {
                    ids.add(job.getId());
                }
                return ids;
            }
            default:
                throw new WfEngineException("不支持的批次类型: " + batch.getBatchType());
        }
    }

    private WfProcessInstanceQuery toInstanceQuery(WfBatchCriteria criteria) {
        WfProcessInstanceQuery query = new WfProcessInstanceQuery();
        if (!isBlank(criteria.getProcessDefinitionKey())) {
            query.setDefinitionKey(criteria.getProcessDefinitionKey());
        }
        if (criteria.getProcessDefinitionVersion() != null) {
            query.setDefinitionVersion(criteria.getProcessDefinitionVersion());
        }
        if (!isBlank(criteria.getProcessBusinessKey())) {
            query.setBusinessKey(criteria.getProcessBusinessKey());
        }
        if (!isBlank(criteria.getProcessStartUserId())) {
            query.setStartUserId(criteria.getProcessStartUserId());
        }
        if (!isBlank(criteria.getProcessCategory())) {
            query.setCategory(criteria.getProcessCategory());
        }
        if (!isBlank(criteria.getStatus())) {
            query.setStatus(parseStatus(criteria.getStatus()));
        }
        if (criteria.getFinishedOnly() != null) {
            query.setFinishedOnly(criteria.getFinishedOnly());
        }
        if (criteria.getUnfinishedOnly() != null) {
            query.setUnfinishedOnly(criteria.getUnfinishedOnly());
        }
        if (criteria.getProcessStartTimeFrom() != null) {
            query.setStartTimeFrom(criteria.getProcessStartTimeFrom());
        }
        if (criteria.getProcessStartTimeTo() != null) {
            query.setStartTimeTo(criteria.getProcessStartTimeTo());
        }
        if (!isBlank(criteria.getProcessResult())) {
            query.setResult(criteria.getProcessResult());
        }
        return query;
    }

    private WfTaskQuery toTaskQuery(WfBatchCriteria criteria) {
        WfTaskQuery query = new WfTaskQuery();
        if (!isBlank(criteria.getTaskProcessInstanceId())) {
            query.setProcessInstanceId(criteria.getTaskProcessInstanceId());
        }
        if (!isBlank(criteria.getTaskDefinitionId())) {
            query.setDefinitionId(criteria.getTaskDefinitionId());
        }
        if (!isBlank(criteria.getTaskAssignee())) {
            query.setAssignee(criteria.getTaskAssignee());
        }
        if (!isBlank(criteria.getTaskOwner())) {
            query.setOwner(criteria.getTaskOwner());
        }
        if (!isBlank(criteria.getTaskCategory())) {
            query.setCategory(criteria.getTaskCategory());
        }
        if (!isBlank(criteria.getTaskStatus())) {
            query.setStatus(parseTaskStatus(criteria.getTaskStatus()));
        }
        if (criteria.getTaskOpenOnly() != null) {
            query.setOpenOnly(criteria.getTaskOpenOnly());
        }
        if (criteria.getTaskUnassignedOnly() != null) {
            query.setUnassignedOnly(criteria.getTaskUnassignedOnly());
        }
        if (criteria.getTaskCreateTimeFrom() != null) {
            query.setCreateTimeFrom(criteria.getTaskCreateTimeFrom());
        }
        if (criteria.getTaskCreateTimeTo() != null) {
            query.setCreateTimeTo(criteria.getTaskCreateTimeTo());
        }
        return query;
    }

    private WfJobQuery toJobQuery(WfBatchCriteria criteria) {
        WfJobQuery query = new WfJobQuery();
        if (!isBlank(criteria.getJobProcessInstanceId())) {
            query.setProcessInstanceId(criteria.getJobProcessInstanceId());
        }
        if (!isBlank(criteria.getJobElementId())) {
            query.setElementId(criteria.getJobElementId());
        }
        if (!isBlank(criteria.getJobType())) {
            query.setType(parseJobType(criteria.getJobType()));
        }
        if (!isBlank(criteria.getJobTopic())) {
            query.setTopic(criteria.getJobTopic());
        }
        if (criteria.getJobDueBefore() != null) {
            query.setDueBefore(criteria.getJobDueBefore());
        }
        if (criteria.getJobRetriesExhausted() != null) {
            query.setRetriesExhausted(criteria.getJobRetriesExhausted());
        }
        return query;
    }

    /**
     * 枚举字段的转换一律<b>转不动就报错</b>，绝不悄悄退化成「不过滤」。
     *
     * <p>退化的后果具体是：条件里写 {@code "status": "ACTVE"}（少个 I），
     * 退化成不过滤就意味着这批改的是<b>全部实例</b>，而记录上写着它带了个状态条件。
     * 报错则只是这一批建不起来。
     */
    private WfProcessStatus parseStatus(String raw) {
        for (WfProcessStatus status : WfProcessStatus.values()) {
            if (status.name().equalsIgnoreCase(raw.trim())) {
                return status;
            }
        }
        throw new WfEngineException("未知的实例状态 [" + raw + "]");
    }

    private WfTask.Status parseTaskStatus(String raw) {
        for (WfTask.Status status : WfTask.Status.values()) {
            if (status.name().equalsIgnoreCase(raw.trim())) {
                return status;
            }
        }
        throw new WfEngineException("未知的任务状态 [" + raw + "]");
    }

    private WfJobType parseJobType(String raw) {
        for (WfJobType type : WfJobType.values()) {
            if (type.name().equalsIgnoreCase(raw.trim())) {
                return type;
            }
        }
        throw new WfEngineException("未知的 job 类型 [" + raw + "]");
    }

    // ==================== 存文本 / 读文本 ====================

    private WfBatchCriteria readCriteria(WfBatch batch) {
        String json = batch.getCriteria();
        if (isBlank(json)) {
            throw new WfEngineException("批次 " + batch.getId() + " 的筛选条件读不出来（内容为空）");
        }
        try {
            WfBatchCriteria criteria = JsonUtil.fromJson(json, WfBatchCriteria.class);
            if (criteria == null) {
                throw new WfEngineException("批次 " + batch.getId() + " 的筛选条件反序列化成了 null");
            }
            return criteria;
        } catch (RuntimeException e) {
            throw new WfEngineException("批次 " + batch.getId()
                    + " 的筛选条件解析失败: " + e.getMessage(), e);
        }
    }

    private List<WfBatchOperation> readOperations(WfBatch batch) {
        String json = batch.getOperations();
        if (isBlank(json)) {
            throw new WfEngineException("批次 " + batch.getId() + " 的操作列表读不出来（内容为空）");
        }
        try {
            List<WfBatchOperation> operations = JsonUtil.fromJson(json,
                    new TypeReference<List<WfBatchOperation>>() {
                    });
            if (operations == null || operations.isEmpty()) {
                throw new WfEngineException("批次 " + batch.getId() + " 的操作列表反序列化后为空");
            }
            return operations;
        } catch (RuntimeException e) {
            throw new WfEngineException("批次 " + batch.getId()
                    + " 的操作列表解析失败: " + e.getMessage(), e);
        }
    }

    private static boolean hasExplicitIds(WfBatchCriteria criteria) {
        return criteria.getIds() != null && !criteria.getIds().isEmpty();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}