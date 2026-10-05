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
 * <p>目前唯一的 job 种类是<b>定时器边界事件</b>：到点后把 token 从宿主节点
 * 挪到边界事件上，沿它的出线走补偿/升级分支。
 *
 * @author zifang
 */
public class WfJobService {

    private static final Logger log = LoggerFactory.getLogger(WfJobService.class);

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
        WfJobQuery query = new WfJobQuery().setDueBefore(now).setRetriesExhausted(Boolean.FALSE);
        int executed = 0;
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
        if (executed > 0) {
            log.info("执行到期 job {} 个（时刻 {}）", executed, now);
        }
        return executed;
    }

    /**
     * 触发一个定时器边界事件。
     *
     * <p>先删 job 再推进流程：边界事件一旦触发就不该再触发第二次。
     * 反过来的话，并发的两个执行器（或同一批里的两次扫描）会各推一次，
     * 补偿分支被执行两遍 —— 那正是超时升级里最不能接受的后果。
     *
     * @return 是否真的触发了 —— 流程已结束、token 已挪走这类"该响没响"要能被
     *         调用方区分开，它们不是失败，也不该被算进执行计数
     */
    private boolean fire(WfJob job) {
        persistence.deleteJob(job.getId());
        return runtimeService.fireTimerBoundary(job);
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
            // 执行到一半自己删掉了（流程已结束），不是错误
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
