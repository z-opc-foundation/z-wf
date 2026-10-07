package com.zifang.z.wf.core.service;

import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfPersistence;

/**
 * Job 执行器 —— 定时器到点了由谁去响。
 *
 * <p><b>它刻意不自带定时器。</b> 与 {@link WfOverdueScanner} 同一个理由：
 * 扫多频繁是业务决定的事（30 秒一次还是 5 分钟一次，取决于提醒能不能容忍延迟），
 * 引擎内嵌调度会让"引依赖就跑起来"成为默认行为。引擎只提供
 * {@link #executeDueJobs(Date)}：扫到期的、逐个执行、返回执行了几个。
 *
 * <p>由时间触发的 job 有两种形态，各自对应 BPMN 里两种不同的语义：
 * <ul>
 *   <li><b>定时器边界事件</b>：到点后把 token 从宿主节点挪到边界事件上，
 *       沿它的出线走补偿/升级分支（打断）。</li>
 *   <li><b>事件网关的定时器分支</b>：到点即算这一格赢了，其余分支作废（竞速）。</li>
 * </ul>
 * 两者用不同的 job 类型（{@code TIMER} / {@code EVENT_TIMER}），
 * 见 {@link #TIME_TRIGGERED_TYPES}。
 *
 * @author zifang
 */
public class WfJobService {

    private static final Logger log = LoggerFactory.getLogger(WfJobService.class);

    /**
     * 由时间触发的 job 类型。
     *
     * <p>刻意列成显式清单而不是"凡是带 duedate 的都算"：
     * 消息 / 信号订阅的 duedate 恒为 null，但"恒为 null"是一条约定，
     * 而约定迟早会被某次改动破坏 —— 破坏后的症状是
     * "还没发消息流程自己往前走了"，那比多一次查询贵得多。
     */
    private static final WfJobType[] TIME_TRIGGERED_TYPES = {
            WfJobType.TIMER,
            WfJobType.EVENT_TIMER};

    /**
     * exclusive 互斥用的实例级锁，**只在执行期持有**，不进任何持久化层。
     *
     * <p><b>为什么是进程内锁而不是数据库行锁</b>：排他发生在"推进 token"这一步，
     * 而推进要读实例、读 token、写回，并发做两件事时真正冲突的是内存里的
     * {@code WfContext} 与那条 {@code WfExecution} —— 那是本进程的对象图，
     * 数据库层的锁管不到。而数据库层真要加锁，就得给 job 表加一列"正在被谁执行"
     * 并在崩溃后清理它（Camunda 用 `LOCK_OWNER` / `LOCK_TIME` 与周期续期做这件事），
     * 那是另一个量级的改动，且本仓的 job 本来就不跨 JVM 领取。
     *
     * <p><b>用 {@link java.util.concurrent.locks.ReentrantLock} 而不是 {@code synchronized}</b>：
     * 前者可以 {@code tryLock} 出局（拿到锁就推进、拿不到就留给下一次扫描），
     * {@code synchronized} 做不到 —— 而"等锁"会把一次扫描阻塞成串行的，
     * 恰好把"要不要并发"这件事又变回由调用方的线程数决定。
     *
     * <p><b>跨 JVM 无效</b>：多个应用实例各跑各的执行器时互不感知。
     * 这一点必须写在文档里而不是留着让人以为它是全局保证 ——
     * 见 {@link #withInstanceLock}。
     */
    private final java.util.concurrent.ConcurrentMap<String,
            java.util.concurrent.locks.ReentrantLock> instanceLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 在实例级互斥下推进一条 exclusive job。
     *
     * <p>拿不到锁时<b>返回 false 跳过这一轮</b>，而不是排队等：
     * 等锁会把一次扫描拖成串行，而互斥要保证的只是"不同时执行"。
     * 跳过的那条下一轮扫描还在（job 没被删），届时多半锁已经空了。
     *
     * @return 是否真的推进了（false = 本轮跳过，不算失败）
     */
    private boolean withInstanceLock(WfJob job, java.util.function.Supplier<Boolean> action) {
        if (!job.isExclusive() || job.getProcessInstanceId() == null) {
            return action.get();
        }
        java.util.concurrent.locks.ReentrantLock lock =
                instanceLocks.computeIfAbsent(job.getProcessInstanceId(),
                        k -> new java.util.concurrent.locks.ReentrantLock());
        if (!lock.tryLock()) {
            log.debug("流程 {} 已有 exclusive job 在推进，本轮跳过 {}（互斥）",
                    job.getProcessInstanceId(), job.getId());
            return false;
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private final WfPersistence persistence;    private final WfRuntimeService runtimeService;

    public WfJobService(WfPersistence persistence, WfRuntimeService runtimeService) {
        this.persistence = persistence;
        this.runtimeService = runtimeService;
    }

    /**
     * 手动触发一条 job（不等它到期）。
     *
     * <p>典型用途是"催一下"：超时提醒还差两小时才到，而客户已经等不及了。
     *
     * <h3>哪些 job 允许手动触发</h3>
     * 只有<b>时间触发型</b>（{@link WfJobType#TIMER} / {@link WfJobType#EVENT_TIMER}）
     * 与<b>异步型</b>（{@code ASYNC_BEFORE} / {@code ASYNC_AFTER}）。
     * 其余一律报错，不给"先触发一下看看"。
     *
     * <p>理由逐个说，因为每一种手动触发都会造出"一件没发生的事"：
     * <ul>
     *   <li><b>消息 / 信号 / 升级订阅</b>：它们等的是"某件事发生了"，
     *       不是"时间到了"。手动触发等于替引擎伪造一条消息 ——
     *       流程会以为消息到了，而<b>真的那条消息随后还会再投一次</b>，
     *       于是同一步走两遍。发消息有它自己的入口（triggerMessage / broadcastSignal），
     *       从那里发才有投递记录、有来源。</li>
     *   <li><b>事件网关的消息 / 信号分支</b>：同上，而且它们是<b>竞速</b> ——
     *       手动触发会作废其余兄弟分支，那是一次不可逆的破坏。</li>
     *   <li><b>外部任务</b>：那是 worker 领的活，租约制。
     *       绕过租约去触发等于让一个 worker 正在做的活同时被引擎推进。</li>
     * </ul>
     *
     * <h3>两个刻意的选择</h3>
     * <ol>
     *   <li><b>重试耗尽的 job 允许手动触发</b>：{@link #executeDueJobs} 排除它们，
     *       是为了不让扫描器无限重试；而"运维手工重跑一个失败的任务"是真实需求。
     *       触发之后 job 被删掉，下次失败会重新从 {@code DEFAULT_RETRIES} 计数。</li>
     *   <li><b>没到期的也允许</b>：这正是本方法存在的意义。
     *       到期时刻在 {@code recordManualJobTrigger} 里被单独留痕，
     *       所以"这条本来该什么时候响"仍然查得到。</li>
     * </ol>
     *
     * <p>与扫描器并发触发同一条 job 时，两者都会走到删除那一步，
     * 由 job 的乐观锁（{@code revision}）保证只有一个成功，另一个抛
     * {@link WfOptimisticLockException} —— 这与既有的"校验 → 删 → 推进"契约同源，
     * 不需要额外机制。
     *
     * @return 是否真的触发了：流程已结束、token 已挪走这类"该响没响"返回 {@code false}
     * @throws WfEngineException job 不存在、或是上面那些不该手动触发的类型
     */
    public boolean triggerJob(String jobId, String userId) {
        if (jobId == null || jobId.trim().isEmpty()) {
            throw new WfEngineException("job id 不能为空");
        }
        WfJob job = persistence.findJob(jobId);
        if (job == null) {
            // 报"不存在"而不是"已触发"：扫描器刚消费掉它是最常见的原因，
            // 而返回成功会让调用方以为事情办成了、于是不再重试
            throw new WfEngineException("job [" + jobId + "] 不存在。"
                    + "它可能已经被执行器消费掉了 —— 同一条 job 只允许触发一次，"
                    + "重复触发会被乐观锁挡下。请先 listJobs 确认它还在");
        }
        WfJobType type = job.getType();
        if (type == WfJobType.ASYNC_BEFORE
                || type == WfJobType.ASYNC_AFTER) {
            boolean resumed = resumeAsync(job);
            if (resumed) {
                runtimeService.recordManualJobTrigger(job, userId);
            }
            return resumed;
        }
        for (WfJobType allowed : TIME_TRIGGERED_TYPES) {
            if (type == allowed) {
                boolean fired = fire(job);
                // 只在真的触发了才留痕：返回 false 的那几种是"该响没响"
                // （流程已终态 / token 已挪走），写成"已手动触发"会留下一条假记录
                if (fired) {
                    runtimeService.recordManualJobTrigger(job, userId);
                }
                return fired;
            }
        }
        throw new WfEngineException(rejectionOf(job));
    }

    /**
     * 拒绝手动触发的理由 —— <b>按 job 类型分三类</b>。
     *
     * <p><b>为什么不能合成一条统一文案</b>：三类被拒的 job 后果完全不同，
     * 而统一文案只能说到其中最轻的那一层。真正危险的那类（事件网关的分支）
     * 会被"和普通订阅差不多"这句话稀释掉 —— 而它恰恰是<b>不可逆</b>的。
     *
     * <ul>
     *   <li><b>事件网关的分支</b>：手动触发会<b>作废其余兄弟分支</b>。
     *       不可逆 —— 没有"撤销一次竞速"的接口，而那几条分支上可能已经攒了审批意见。</li>
     *   <li><b>订阅型</b>：会<b>同一步走两遍</b>。伪造的那一次和真的那一次都在等路上。</li>
     *   <li><b>外部任务</b>：会让一个 worker 正在做的活<b>同时</b>被引擎推进。</li>
     * </ul>
     *
     * <p>每条都要给出<b>替代路径</b>：只说"不许"的话，调用方只会以为这条路被整体禁了，
     * 于是回到"那我怎么让流程往下走"——而那个问题的答案本来就在下一句里。
     */
    private String rejectionOf(WfJob job) {
        WfJobType type = job.getType();
        String head = "job [" + job.getId() + "] 是" + type.getLabel() + "，不能手动触发。";
        if (type == WfJobType.EVENT_MESSAGE || type == WfJobType.EVENT_SIGNAL) {
            return head + "它是事件网关上的一格，等待的是「某件事发生了」。"
                    + "这里比普通订阅更危险一层：它是竞速——手动触发会"
                    + "作废其余兄弟分支，那是一次不可逆的破坏"
                    + "（撤销不了，也没有地方能找回那些分支上已攒的审批意见）。"
                    + "要投递请走 triggerMessage / broadcastSignal，"
                    + "由引擎按真实事件来赢这一格";
        }
        if (type == WfJobType.EXTERNAL) {
            return head + "它是等外部 worker 来领的活，租约制。"
                    + "绕过租约去触发，等于让一个 worker 正在做的活同时被引擎推进——"
                    + "两边都会写回结果，冲突的那一边会被静默丢掉。"
                    + "要催外部 worker 请走外部任务的 topic 通道，"
                    + "让 worker 自己来领、由它回交结果";
        }
        return head + "它等的不是「时间到了」而是「某件事发生了」："
                + "手动触发等于替引擎伪造一件没发生的事——流程会以为它发生了，"
                + "而真的那件事随后还会再来一次，于是同一步走两遍。"
                + "要发消息请走 triggerMessage / broadcastSignal / escalate，"
                + "那里才有投递记录与来源";
    }

    /**
     * 执行所有到期且还留有重试次数的 job。
     *
     * @param now 以谁的时间为准（测试里传入过去/未来的时刻来模拟到期）
     * @return 实际触发成功的 job 数
     */
    public int executeDueJobs(Date now) {
        if (now == null) {
            throw new WfEngineException("执行时刻不能为空：没有时间点就没有到点这个概念，"
                    + "传 null 会在下游变成扫描全部 job");
        }
        int executed = 0;
        // 逐个时间触发的类型各扫一遍，而不是一次查两种类型：
        // 「哪些类型由时间触发」是个很小且固定的集合（定时器边界 + 事件网关定时器分支），
        // 为它把 WfJobQuery 扩成多类型要同时改内存与 JDBC 两套实现，
        // 而多一次查询的代价远小于那种改动带来的面。
        // 缺了 EVENT_TIMER 的症状：事件网关的定时器分支**永远不响**，而流程一直等着 ——
        // 没有任何报错，只是那条分支上的待办始终不出现
        for (WfJobType each : TIME_TRIGGERED_TYPES) {
            // 显式限定类型：消息 / 信号订阅不由时间触发。
            // 只靠"duedate 为 null 所以 DUEDATE<? 捞不到"是不够的 —— 哪天谁给订阅填了
            // duedate，"还没发消息流程自己往前走了"就是这么来的
            WfJobQuery query = new WfJobQuery().setDueBefore(now)
                    .setType(each)
                    .setRetriesExhausted(Boolean.FALSE);
            // 分批取而不是一次全取：job 表在跑了一年的系统里可能堆着几十万条历史残留，
            // 一次性读进内存会把它变成一次 OOM
            for (WfJob job : persistence.queryJobs(query.setPageNum(1).setPageSize(200))) {
                try {
                    if (fire(job)) {
                        executed++;
                    }
                } catch (RuntimeException e) {
                    // 一个 job 失败不该让整批停摆 —— 后面还有别的单的提醒要发
                    log.warn("job {} 执行失败: {}", job.getId(), e.getMessage(), e);
                    recordFailure(job, e);
                }
            }
        }
        if (executed > 0) {
            log.info("执行到期 job {} 个（时刻 {}）", executed, now);
        }
        return executed;
    }

    /**
     * 触发一个到期的定时器 job。
     *
     * <p><b>校验 → 删 job → 推进</b>，三步的顺序不能换：
     * <ul>
     *   <li>删在前，是为了让定时器只触发一次。反过来的话，并发的两个执行器
     *       （或同一批里的两次扫描）会各推一次，补偿分支或竞速分支被执行两遍 ——
     *       那正是超时升级里最不能接受的后果。</li>
     *   <li>校验在删之前，是因为校验是<b>抛异常</b>的。抛的时候 job 已经在库里没了，
     *       {@code executeDueJobs} 的兜底 {@link #recordFailure} 回头
     *       {@code findJob} 拿到 null，"执行失败"就只剩一行日志 ——
     *       而故障是从 job 派生的，于是这条失败在故障视图里<b>彻底看不见</b>。</li>
     * </ul>
     *
     * @return 是否真的触发了 —— 流程已结束、token 已挪走这类"该响没响"要能被
     *         调用方区分开，它们不是失败，也不该被算进执行计数
     */
    private boolean fire(WfJob job) {
        return withInstanceLock(job, () -> {
            runtimeService.checkTimerJobDispatch(job);
            persistence.deleteJob(job.getId());
            return runtimeService.fireTimer(job);
        });
    }

    /**
     * 执行所有到期且还留有重试次数的<b>异步</b> job。
     *
     * <p>与 {@link #executeDueJobs} 分开而不是合进同一个方法：两者的触发节奏通常不同 ——
     * 定时器边界是业务节奏（催办每 5 分钟），异步 job 是"队列越快排空越好"，
     * 合并后调用方只能取一个折中的频率，往往两边都不合适。
     *
     * <p>同样<b>不自带定时器</b>：多久扫一次由宿主决定。
     *
     * @return 实际续跑成功的 job 数
     */
    public int executeAsyncJobs(Date now) {
        if (now == null) {
            throw new WfEngineException("执行时刻不能为空：没有时间点就没有到点这个概念，"
                    + "传 null 会在下游变成扫描全部 job");
        }
        int executed = 0;
        for (WfJob job : findAsyncJobs(now, 200)) {
            try {
                if (resumeAsync(job)) {
                    executed++;
                }
            } catch (RuntimeException e) {
                log.warn("异步 job {} 续跑失败: {}", job.getId(), e.getMessage(), e);
                recordFailure(job, e);
            }
        }
        if (executed > 0) {
            log.info("续跑到期异步 job {} 个（时刻 {}）", executed, now);
        }
        return executed;
    }

    /**
     * 到期的异步 job（排序稳定，与内存实现的顺序一致）。
     *
     * <p>一个 SQL 捞两种类型而不是捞出来再过滤：异步 job 的量按"队列深度"涨，
     * 一旦积压就是成千上万条，先捞后滤等于把整条队列读进内存。
     */
    private List<WfJob> findAsyncJobs(Date now, int max) {
        java.util.List<WfJob> due = persistence.queryJobs(new WfJobQuery()
                .setDueBefore(now)
                .setType(WfJobType.ASYNC_BEFORE)
                .setRetriesExhausted(Boolean.FALSE)
                // **只有异步 job 启用优先级排序**：队列积压时"加急的先办"是审批的刚需，
                // 而定时器要的是"最早到点的先做"—— 按优先级排会让靠后的定时器饿死
                .setOrderByPriority(Boolean.TRUE)
                .setPageNum(1).setPageSize(max));
        // 后置的单独查一次再拼上：WfJobQuery 的 type 是单值而不是集合，
        // 加"多类型"支持会让这个查询对象多出一个只在两处用到的字段。
        // **拼接会破坏优先级顺序**：两次查询各自内部有序，合起来就不再是全局有序。
        // 前置与后置各有各的续跑动作，合在一个列表里就意味着同一批里两半的相对顺序是随机的。
        // 所以下面改用"整体重新按同一把尺子排一遍"，而不是直接 addAll。
        java.util.List<WfJob> after = persistence.queryJobs(new WfJobQuery()
                .setDueBefore(now)
                .setType(WfJobType.ASYNC_AFTER)
                .setRetriesExhausted(Boolean.FALSE)
                .setOrderByPriority(Boolean.TRUE)
                .setPageNum(1).setPageSize(max));
        due.addAll(after);
        sortByPriorityThenDuedate(due);
        return due;
    }

    /**
     * 优先级降序、同级按到期时刻正序、仍然同级按 id。
     *
     * <p><b>这条尺子必须与两套存储实现的 {@code queryJobs} 排序逐字一致</b>：
     * 内存与 JDBC 在同一个查询里给出的顺序要是不同，
     * 症状就是「开发期全绿、换 JDBC 之后偶发乱序」，日志里没有任何异常。
     */
    private void sortByPriorityThenDuedate(java.util.List<WfJob> jobs) {
        java.util.Collections.sort(jobs, (a, b) -> {
            int p = Integer.compare(b.getPriority(), a.getPriority());
            if (p != 0) {
                return p;
            }
            java.util.Date ta = a.getDuedate();
            java.util.Date tb = b.getDuedate();
            if (ta == null || tb == null) {
                return a.getId().compareTo(b.getId());
            }
            int cmp = ta.compareTo(tb);
            return cmp != 0 ? cmp : a.getId().compareTo(b.getId());
        });
    }

    /**
     * 续跑一个异步 job。
     *
     * <p><b>不在这里删 job</b>：删除由 {@code WfRuntimeService#executeAsyncJob} 在
     * 全部校验通过之后做。这里先删的话，"token 已经挪到别处、这次不该续跑"那条早退
     * 路径会把 job 一起吞掉 —— 那是把一件还没做的事当成做完了。
     * 与 {@code completeExternalTask} 的处理一致：被忽略的交差/续跑不删 job。
     *
     * <p>（对比 {@link #fire}：那里是"校验 → 删 → 推进"，删在推进之前是为了并发下
     * 只触发一次；代价是校验必须排在删除之前，理由见那里的注释。）
     */
    private boolean resumeAsync(WfJob job) {
        return withInstanceLock(job, () -> runtimeService.executeAsyncJob(job));
    }

    /**
     * 记一次失败并扣重试次数。
     *
     * <p>扣到 0 之后不再重试：一个必然失败的 job 每轮都重试，会把执行器
     * 全部时间耗在它身上。耗尽的事实留在表里（{@code RETRIES <= 0}），
     * 排障界面能查到"它试过几次、为什么失败"。
     */
    private void recordFailure(WfJob job, RuntimeException e) {
        WfJob latest = persistence.findJob(job.getId());
        if (latest == null) {
            // 跑到一半 job 已经不在了：续跑路径（resumeAsync 不删 job，由
            // executeAsyncJob 在校验通过后才删）里，异常可能在删除之后才抛出来。
            // 这时"失败原因"没有载体可写，只能留在日志里 ——
            // 与其凭空造一条 job 出来（那会让一条早已作废的提醒重新出现在
            // 待执行列表里），不如不写
            return;
        }
        latest.recordFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
        latest.nextRevision();
        persistence.saveJob(latest);
    }

    /**
     * 到期但已耗尽重试的 job —— 排障入口。
     *
     * <p>刻意不自动删除：它们代表"有一批提醒没发出去"，
     * 悄悄清掉就再也没人知道漏过哪些单。
     */
    public List<WfJob> findExhaustedJobs(int pageNum, int pageSize) {
        return persistence.queryJobs(new WfJobQuery()
                .setRetriesExhausted(Boolean.TRUE).setPageNum(pageNum).setPageSize(pageSize));
    }

    /** 某个流程实例上还挂着哪些 job（超时提醒/排障用）。 */
    public List<WfJob> findJobsByProcessInstance(String processInstanceId) {
        return persistence.queryJobs(new WfJobQuery()
                .setProcessInstanceId(processInstanceId).setPageNum(1).setPageSize(200));
    }

    /**
     * 按条件查 job（管理端 / REST 列表页用）。
     *
     * <p>暴露原始 {@link WfJobQuery} 而不是再包几个固定方法：排障界面要按的组合
     * （某实例 + 某节点 + 重试耗尽）无法预先枚举，包一层只会让人为了凑合用
     * 而放弃筛选条件 —— 那是分页列表最常见的死法。
     */
    public List<WfJob> listJobs(WfJobQuery query) {
        return persistence.queryJobs(query);
    }

    /** 与 {@link #listJobs} 同条件的条数。 */
    public long countJobs(WfJobQuery query) {
        return persistence.countJobs(query);
    }

    /**
     * 边界事件是否还能按预期触发。
     *
     * <p>job 存在但宿主节点上的边界事件没了（定义被替换）是"永远不响的哑表"，
     * 必须在触发时报出来而不是静默跳过。
     */
    static WfNode resolveBoundary(WfDefinition definition, WfJob job) {
        WfNode boundary = definition.node(job.getElementId());
        if (boundary == null || boundary.getType() != WfNodeType.BOUNDARY_EVENT) {
            throw new WfEngineException("job " + job.getId() + " 指向的边界事件不存在或已不是边界事件: "
                    + job.getElementId() + "。流程定义可能在 job 建立后被替换过");
        }
        return boundary;
    }

    /** 记一条 job 相关的审计，供轨迹上追查。 */
    static WfComment jobComment(String processInstanceId, String userId, String content) {
        return new WfComment(null, processInstanceId, userId, "job", content);
    }
}
