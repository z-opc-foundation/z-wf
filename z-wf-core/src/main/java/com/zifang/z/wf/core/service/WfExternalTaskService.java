package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.view.WfExternalTaskView;

/**
 * 外部任务服务 —— 流程里"交给外部系统做一步"的那一步由谁领、怎么交差。
 *
 * <p><b>它不自带轮询器</b>。和 {@link WfJobService} 同一个理由：worker 要不要常驻、
 * 拉取间隔多少，取决于业务上"外部动作能容忍多长的延迟"，那是业务决定的事。
 * 引擎只提供 {@link #fetchAndLock} 这一步，谁来调、什么时候调由宿主决定。
 *
 * <p><b>租约而不是永久锁</b>。领活写入的是"我来做，租到 {@code now+lease}"，
 * 到期没交差就自动变成可领。永久锁在 worker 崩溃时会把整条流程永远卡死，
 * 而"卡住"这件事从外面看和"引擎坏了"没有任何区别 —— 排障成本极高。
 * 代价是租约必须<b>盖住一次外部调用耗时</b>：调外部接口花了 3 分钟、租约只有 1 分钟，
 * 就会出现两个 worker 同时做同一件事，而外部动作通常不可重入。
 *
 * <p>本服务只做<b>归因与租约</b>；上下文的构造与落库管线在 {@link WfRuntimeService}，
 * 由 {@link #complete} 委托过去。那条管线（newContext + startFrom + persistAll +
 * resolveCompletion）只有一份实现 —— 复制一份的后果是两条管线各自漂移，
 * 表现是"外部任务完成了但流程没往下走"。
 *
 * @author zifang
 */
public class WfExternalTaskService {

    private static final Logger log = LoggerFactory.getLogger(WfExternalTaskService.class);

    /**
     * 默认租约 5 分钟。
     *
     * <p><b>不要把这个数字说成对齐 Camunda</b> —— Camunda 官方文档里取活时锁时长是
     * {@code .topic("x", 60L * 1000L)} 由调用方<b>显式指定</b>的，
     * 文档中查不到「5 分钟默认」这条规定（第 31 轮查过）。
     * 本仓给出默认值是因为 worker 侧的常见用法是"不传就用默认"，
     * 而 Camunda 要求每次都写死；这是一个**有意的 API 形状差异**，不是对齐结果。
     *
     * <p>取值标准是"盖住一次外部调用"，不是"盖住绝大多数"：
     * 一旦租约短于调用耗时，故障就变成重复执行，那是比卡住严重得多的问题。
     */
    public static final long DEFAULT_LEASE_MILLIS = 5 * 60 * 1000L;

    /**
     * 失败后多久才允许重新领取（默认 0 = 立刻可领）。
     *
     * <p>默认 0 是有意的：退避时长该由 worker 自己决定 —— 不同 topic 的失败模式差别很大
     * （下游在重启 vs 业务数据本身错了），引擎猜一个数出来只会两边都不对。
     * 需要退避的调用方显式传 {@link #fail} 的 retryDelayMillis。
     */
    public static final long DEFAULT_RETRY_DELAY_MILLIS = 0L;

    private final WfPersistence persistence;
    private final WfRuntimeService runtimeService;

    public WfExternalTaskService(WfPersistence persistence, WfRuntimeService runtimeService) {
        this.persistence = persistence;
        this.runtimeService = runtimeService;
    }

    /**
     * 按主题领活。
     *
     * <p><b>选出与上锁是一次原子操作</b>（见 {@link WfPersistence#lockExternalTasks}）：
     * 分成"先查后锁"的话两个 worker 会领到同一件活，而外部动作（调接口、发消息）
     * 通常不可重入，重复执行的后果由外部系统承担。
     *
     * <p>锁过期（{@code now-lease}）的活会被重新领走：worker 崩了不该让流程永远挂着。
     * 代价是"慢 worker 会被抢活"，而它交差时会被 {@link #requireLockHolder} 挡住，
     * 于是慢的那次调用白做 —— 这个取舍是刻意的，因为不这样就是永久卡死。
     *
     * @param topic        主题名，不能为空
     * @param workerId     领活人，写入锁归属
     * @param maxTasks     最多领几件，必须大于 0
     * @param leaseMillis  租约时长；{@code <= 0} 表示用 {@link #DEFAULT_LEASE_MILLIS}
     * @return 已上锁的活；领不到返回空列表（<b>不是</b> null）
     */
    public List<WfExternalTaskView> fetchAndLock(String topic, String workerId,
                                                 int maxTasks, long leaseMillis) {
        requireTopic(topic);
        requireWorker(workerId);
        if (maxTasks <= 0) {
            throw new WfEngineException("领活数量必须大于 0，收到 " + maxTasks
                    + "。传 0 会让 worker 以为没活可领，实际是调用方没配对参数");
        }
        long lease = leaseMillis <= 0 ? DEFAULT_LEASE_MILLIS : leaseMillis;
        Date staleBefore = new Date(System.currentTimeMillis() - lease);
        List<WfJob> jobs = persistence.lockExternalTasks(topic, workerId, maxTasks, staleBefore);
        List<WfExternalTaskView> views = new ArrayList<WfExternalTaskView>(jobs.size());
        for (WfJob job : jobs) {
            views.add(toView(job, System.currentTimeMillis() + lease));
        }
        if (!views.isEmpty()) {
            log.info("worker {} 领取 topic {} 的外部任务 {} 件", workerId, topic, views.size());
        }
        return views;
    }

    /** 用默认租约领活。 */
    public List<WfExternalTaskView> fetchAndLock(String topic, String workerId, int maxTasks) {
        return fetchAndLock(topic, workerId, maxTasks, DEFAULT_LEASE_MILLIS);
    }

    /**
     * 交差：把外部动作的结果交给流程，token 从这一步继续往下走。
     *
     * <p>委托给 {@link WfRuntimeService#completeExternalTask} 而不是自己推进 ——
     * 上下文的构造与落库管线只有那里有一份。
     *
     * <p><b>重复提交不抛异常</b>：worker 超时重发是常态，而第一次可能已经推进了。
     * 这时返回当前流程实例，让 worker 知道"不用再做了"。
     *
     * @param taskId    领到的外部任务 id
     * @param workerId  交差人，必须是当前锁持有者
     * @param variables 外部返回的变量，会并进流程变量
     */
    public WfProcessInstance complete(String taskId, String workerId,
                                      Map<String, Object> variables) {
        WfJob job = requireLockHolder(taskId, workerId);
        // 先删后推进的顺序在 runtime 里：job 不删会变成"永远领不走、永远显示未完成"的哑表
        return runtimeService.completeExternalTask(job.getId(), workerId, variables);
    }

    /**
     * 交差失败：记原因、扣重试、<b>解锁</b>。
     *
     * <p>解锁是必须的：外部失败大多是瞬时的（下游在重启、限流、网络抖动），
     * 不解锁的话一次失败就把这件活永久锁死，而重试次数一次都没用上。
     * 重试耗尽后 job 留在库里（{@code RETRIES <= 0}），不删 ——
     * 排障要能查到"它试过几次、每次为什么失败"，悄悄清掉就再也没人知道漏过什么。
     *
     * @param retryDelayMillis 多久之后才允许重新领取；{@code <= 0} 立刻可领。
     *                         不做默认退避是有意的：不同 topic 的失败模式差别很大，
     *                         引擎猜一个时长只会两边都不对
     */
    public void fail(String taskId, String workerId, String errorMessage, long retryDelayMillis) {
        WfJob job = requireLockHolder(taskId, workerId);
        job.recordFailure(errorMessage == null ? "未提供失败原因" : errorMessage);
        // 解锁并设定"最早可领时刻"。写 duedate 而不是不写：写 0 延迟时 duedate 就是
        // "现在"，两者在 SQL 里等价，但统一表达成"最早可领时刻"后，
        // 以后加退避策略不需要再动数据模型
        job.setDuedate(retryDelayMillis <= 0
                ? new Date() : new Date(System.currentTimeMillis() + retryDelayMillis));
        job.setLockedBy(null);
        job.setLockedAt(null);
        job.nextRevision();
        persistence.saveJob(job);
        log.info("外部任务 {} 失败（剩余重试 {}）: {}", taskId, job.getRetries(), errorMessage);
    }

    /** 用默认退避（立刻可领）标记失败。 */
    public void fail(String taskId, String workerId, String errorMessage) {
        fail(taskId, workerId, errorMessage, DEFAULT_RETRY_DELAY_MILLIS);
    }

    /**
     * 主动放弃：解锁但<b>不</b>扣重试。
     *
     * <p>与 {@link #fail} 的区别是"这不是失败，是我不该做"—— 例如部署回滚、
     * worker 发现自己认错了 topic。重试次数在这里扣掉的话，
     * 一场发布就能把整批活的重试次数烧光。
     */
    public void release(String taskId, String workerId) {
        WfJob job = requireLockHolder(taskId, workerId);
        job.setLockedBy(null);
        job.setLockedAt(null);
        job.nextRevision();
        persistence.saveJob(job);
        log.info("外部任务 {} 被 {} 主动释放", taskId, workerId);
    }

    /**
     * 查外部任务（管理端 / 排障用）。
     *
     * @param topic 主题名；{@code null} 表示不限主题
     */
    public List<WfExternalTaskView> listTasks(String topic, Integer pageNum, Integer pageSize) {
        WfJobQuery query = new WfJobQuery()
                .setType(com.zifang.z.wf.core.model.WfJobType.EXTERNAL)
                .setTopic(topic)
                .setPageNum(pageNum == null ? 1 : pageNum)
                .setPageSize(pageSize == null ? 50 : pageSize);
        return toViews(persistence.queryJobs(query));
    }

    /** 与 {@link #listTasks} 同条件的条数。 */
    public long countTasks(String topic) {
        return persistence.countJobs(new WfJobQuery()
                .setType(com.zifang.z.wf.core.model.WfJobType.EXTERNAL).setTopic(topic));
    }

    /**
     * 某个 worker 当前锁着哪些活（排障："我到底领了什么"）。
     */
    public List<WfExternalTaskView> listLockedBy(String topic, String workerId) {
        List<WfExternalTaskView> all = listTasks(topic, 1, 200);
        List<WfExternalTaskView> mine = new ArrayList<WfExternalTaskView>();
        for (WfExternalTaskView view : all) {
            if (workerId.equals(view.getLockedBy())) {
                mine.add(view);
            }
        }
        return mine;
    }

    // ---------- 内部 ----------

    private List<WfExternalTaskView> toViews(List<WfJob> jobs) {
        List<WfExternalTaskView> views = new ArrayList<WfExternalTaskView>(jobs.size());
        for (WfJob job : jobs) {
            views.add(toView(job, 0L));
        }
        return views;
    }

    /**
     * job → 对外视图。
     *
     * <p>变量在<b>此刻</b>从流程实例上读，而不是从 job 上取：job 本体不存变量，
     * 而 worker 需要的是"干活时的业务上下文"。读的是快照（拷贝），见
     * {@link WfExternalTaskView} 的说明。
     */
    private WfExternalTaskView toView(WfJob job, long lockExpiresAt) {
        WfExternalTaskView view = new WfExternalTaskView();
        view.setId(job.getId());
        view.setTopic(job.getTopic());
        view.setProcessInstanceId(job.getProcessInstanceId());
        view.setExecutionId(job.getExecutionId());
        view.setActivityId(job.getElementId());
        view.setRetries(job.getRetries());
        view.setLockedBy(job.getLockedBy());
        view.setErrorMessage(job.getExceptionMessage());
        view.setCreateTime(job.getCreateTime() == null ? 0L : job.getCreateTime().getTime());
        if (lockExpiresAt <= 0L && job.getLockedAt() != null) {
            lockExpiresAt = job.getLockedAt().getTime();
        }
        view.setLockExpiresAt(lockExpiresAt);
        view.setVariables(snapshotVariables(job.getProcessInstanceId()));
        return view;
    }

    /**
     * 读流程实例变量。
     *
     * <p>实例读不到时返回空 map 而不是抛异常：实例已经结束（或被清掉）时，
     * job 仍可能残留在库里待重试，此时 worker 需要知道"上下文没了"，
     * 拿一个空 map 让它去做不成的事，比整个领活调用崩掉更有用 ——
     * 后者会让一个坏 job 挡住同 topic 的所有其他活。
     * 这个取舍会在 {@code errorMessage} 里留痕，不靠抛异常表达。
     */
    private Map<String, Object> snapshotVariables(String processInstanceId) {
        Map<String, Object> variables = new HashMap<>();
        if (processInstanceId == null) {
            return variables;
        }
        WfProcessInstance instance = persistence.findProcessInstance(processInstanceId);
        if (instance != null && instance.getVariables() != null) {
            variables.putAll(instance.getVariables());
        }
        return variables;
    }

    /**
     * 校验 job 存在且锁在提交人手里。
     *
     * <p>锁归属<b>必须</b>校验：租约过期后活被别人领走时，原 worker 手上那次调用
     * 往往还在跑，它交差的结果与新 worker 的结果会互相覆盖，
     * 而外部动作通常不可重入 —— 重复执行比卡住更难收拾。
     *
     * <p>job 不存在时抛异常而不是当成功：worker 领到过的活凭空消失是数据问题
     * （被手工清了，或清了 job 表），静默返回"成功"会让 worker 认为交差完成，
     * 而流程其实还停在那里等 —— 那是最难查的一类不一致。
     */
    private WfJob requireLockHolder(String taskId, String workerId) {
        requireWorker(workerId);
        if (taskId == null || taskId.trim().isEmpty()) {
            throw new WfEngineException("外部任务 id 不能为空");
        }
        WfJob job = persistence.findJob(taskId);
        if (job == null) {
            throw new WfEngineException("外部任务不存在: " + taskId
                    + "。可能已被完成，或 job 表被清空 —— 前者是正常的，后者要查运维");
        }
        if (workerId.equals(job.getLockedBy())) {
            return job;
        }
        if (job.getLockedBy() == null || job.getLockedBy().isEmpty()) {
            throw new WfEngineException("外部任务 " + taskId + " 当前未被锁定（可能租约已过期被释放），"
                    + "不能由 " + workerId + " 交差。先重新 fetchAndLock 领取再提交");
        }
        throw new WfEngineException("外部任务 " + taskId + " 锁在 " + job.getLockedBy()
                + " 手里，" + workerId + " 不是锁持有者，拒绝交差"
                + "（多半是本次调用超过了租约时长，活已被重新领走）");
    }

    private void requireTopic(String topic) {
        if (topic == null || topic.trim().isEmpty()) {
            throw new WfEngineException("外部任务主题不能为空。不带主题的领活会把"
                    + "所有 worker 的活混进同一个队列，worker 会领到不属于自己的活");
        }
    }

    private void requireWorker(String workerId) {
        if (workerId == null || workerId.trim().isEmpty()) {
            throw new WfEngineException("workerId 不能为空。没有它就无法判定交差人是不是锁持有者，"
                    + "而交差不校验锁归属的后果是重复执行外部动作");
        }
    }
}
