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
    private static final com.zifang.z.wf.core.model.WfJobType[] TIME_TRIGGERED_TYPES = {
            com.zifang.z.wf.core.model.WfJobType.TIMER,
            com.zifang.z.wf.core.model.WfJobType.EVENT_TIMER};

    private final WfPersistence persistence;
    private final WfRuntimeService runtimeService;

    public WfJobService(WfPersistence persistence, WfRuntimeService runtimeService) {
        this.persistence = persistence;
        this.runtimeService = runtimeService;
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
        for (com.zifang.z.wf.core.model.WfJobType each : TIME_TRIGGERED_TYPES) {
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
        runtimeService.checkTimerJobDispatch(job);
        persistence.deleteJob(job.getId());
        return runtimeService.fireTimer(job);
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
                .setType(com.zifang.z.wf.core.model.WfJobType.ASYNC_BEFORE)
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
                .setType(com.zifang.z.wf.core.model.WfJobType.ASYNC_AFTER)
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
        return runtimeService.executeAsyncJob(job);
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
