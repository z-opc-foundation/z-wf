package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 超期待办扫描 —— 让 {@code notifyOverdue} 这个钩子有地方被调用。
 *
 * <p><b>为什么需要它。</b> {@code notifyOverdue} 此前是全仓零调用点的死钩子：
 * 接口有 default 实现、dispatcher 有触发方法、{@code WfTask} 也有 {@code dueDate} 字段，
 * 但没有任何代码去判断"哪个任务超期了"。结果是想接超时提醒的团队会发现
 * 自己的实现永远不会被调，而从代码上看一切齐全。
 *
 * <p><b>它刻意不做的事：自己不排程。</b> 扫描的触发时机交给调用方
 * （Spring 的 {@code @Scheduled}、组织自己的调度中心、或者测试里直接调）。
 * 理由：引擎内嵌一个定时器会让"库引进来就跑起来了"变成默认行为，
 * 而多数部署场景里超时提醒的频率是业务决定的，不该由引擎猜。
 * 引擎只提供"哪些超期了"这个判断。
 *
 * <p>复杂度说明：{@link WfTaskQuery} 目前没有 dueDate 过滤条件（加它要给
 * 内存实现与 JDBC 实现各写一遍索引与 SQL），所以这里是
 * "取出全部未办任务、在内存里按 dueDate 筛"。未办任务的量级与在途审批单数同阶，
 * 通常是百到千级，一次全表扫可接受。等真的成为瓶颈了再把它下沉到查询条件里。
 *
 * @author zifang
 */
public class WfOverdueScanner {

    private static final Logger log = LoggerFactory.getLogger(WfOverdueScanner.class);

    private final WfPersistence persistence;
    private final WfHookDispatcher hookDispatcher;

    public WfOverdueScanner(WfPersistence persistence, WfHookDispatcher hookDispatcher) {
        this.persistence = persistence;
        this.hookDispatcher = hookDispatcher;
    }

    /**
     * 扫描超期待办并逐个通知。
     *
     * @return 本次通知的条数（便于调用方记指标）
     */
    public int scanOverdue() {
        return scanOverdueAt(new Date());
    }

    /**
     * 以指定时刻为基准扫描。
     *
     * <p>把"现在几点"做成参数，是为了让调用方能测"昨天那条是否算超期"这类场景，
     * 而不必真的去等一个时钟。
     */
    public int scanOverdueAt(Date now) {
        List<WfTask> open = persistence.queryTasks(new WfTaskQuery()
                .setOpenOnly(true)
                .setPageNum(1)
                .setPageSize(Integer.MAX_VALUE));
        if (open == null || open.isEmpty()) {
            return 0;
        }
        List<WfTask> overdue = new ArrayList<>();
        for (WfTask task : open) {
            if (isOverdue(task, now)) {
                overdue.add(task);
            }
        }
        for (WfTask task : overdue) {
            WfProcessInstance instance =
                    persistence.findProcessInstance(task.getProcessInstanceId());
            hookDispatcher.notifyOverdue(task.getId(), task.getProcessInstanceId(),
                    task.effectiveHandler(),
                    instance == null ? null : instance.getDefinitionKey(),
                    overdueMinutes(task, now));
        }
        if (!overdue.isEmpty()) {
            log.info("超期待办扫描：{} 条（基准时间 {}）", overdue.size(), now);
        }
        return overdue.size();
    }

    /**
     * 是否超期。
     *
     * <p>没设 dueDate 的任务<b>不算超期</b>——没有截止时间的任务没有"超时"可言。
     * 把它算成超期会让所有没配截止时间的单据天天发提醒，
     * 几天之后没人再看得���那条通知，于是真超期的也一起被无视了。
     */
    /** ���了多少分钟，供通知正文用；下界取 0，避免 dueDate 恰好等于 now 时算出负数。 */
    private long overdueMinutes(WfTask task, Date now) {
        Date due = task.getDueDate();
        if (due == null) {
            return 0L;
        }
        return Math.max(0L, (now.getTime() - due.getTime()) / 60_000L);
    }

    private boolean isOverdue(WfTask task, Date now) {
        Date due = task.getDueDate();
        return due != null && due.before(now);
    }
}
