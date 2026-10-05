package com.zifang.z.wf.core.service;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.persistence.WfHistoricActivityInstanceQuery;
import com.zifang.z.wf.core.persistence.WfProcessInstanceQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 历史服务 —— 已结束流程与已完成任务的查询。
 *
 * <p>对应 z-camuda 的 {@code HistoryService}。z-wf 的历史数据<b>不另建表</b>，
 * 而是直接查运行态存储里的"已终止"记录 —— 因为本引擎的历史与运行态在同一个
 * {@link WfPersistence} 里（实例表 + 任务表 + 活动表都是只增不改的终态记录）。
 *
 * <p>这样取舍的理由：审批系统的历史数据量通常不大（单实例几十行），
 * 而"已完成任务"本来就是任务表里 status=COMPLETED 的行。
 * 真正需要独立归档的是<b>运行态 token</b>（token 结束后应清理），本服务不暴露它们。
 *
 * <p>如果未来要接数据仓库做大范围分析，应当在"完成"那一刻把快照写进独立的
 * 归档表，而不是在查询时过滤 —— 那是 {@code WfPersistence} 实现层的职责，
 * 不该渗透到 service 的查询语义里。
 *
 * @author zifang
 */
public class WfHistoryService {

    private final WfPersistence persistence;

    public WfHistoryService(WfPersistence persistence) {
        this.persistence = persistence;
    }

    /**
     * 按条件查询历史活动实例。
     *
     * <p>这是"上个月所有走完的流程里哪一步最慢"这类问题的入口 ——
     * 此前只有 {@code getTrail(processInstanceId)} 一个写死口径，
     * 只能按流程实例取全量，跨流程的统计做不了。
     */
    public List<WfActivityInstance> queryActivities(WfHistoricActivityInstanceQuery query) {
        return persistence.queryActivityInstances(query);
    }

    /** 与 {@link #queryActivities} 同条件的条数。 */
    public long countActivities(WfHistoricActivityInstanceQuery query) {
        return persistence.countActivityInstances(query);
    }

    /**
     * 查历史任务（已办结的任务）。
     *
     * <p>直接复用 {@link WfTaskQuery} 而不是再造一个
     * {@code WfHistoricTaskInstanceQuery}：本引擎的"历史任务"就是任务表里
     * {@code STATUS=COMPLETED} 的行，条件字段与运行态查询完全重合，
     * 另造一个类只会让两套字段各自演化。Camunda 分成两个类是因为它有独立的
     * 历史表；本仓没有那张表。
     *
     * <p>本方法强制补上 {@code completedOnly}，所以调用方传进来的条件里
     * <b>不能</b>带 {@code openOnly} —— 那种条件会与"只看已办结"打架，
     * 静默返回空集。这里直接拒绝。
     */
    public List<WfTask> queryCompletedTasks(WfTaskQuery query) {
        return persistence.queryTasks(asHistoric(query));
    }

    /** 与 {@link #queryCompletedTasks} 同条件的条数。 */
    public long countCompletedTasks(WfTaskQuery query) {
        return persistence.countTasks(asHistoric(query));
    }

    /**
     * 把调用方的条件收敛成"只看已办结"。
     *
     * <p>不改动调用方传进来的对象：{@code WfTaskQuery} 是可变的链式 builder，
     * 复用同一个实例改条件会波及调用方手里还在用的那一份。
     */
    /**
     * 查历史流程实例（已结束的流程）。
     *
     * <p>同样复用 {@link WfProcessInstanceQuery}：本引擎的"历史实例"就是
     * 终态的实例行，没有另一张表可查。
     *
     * <p>"已结束"在本仓有三种终态（正常完成 / 外部终止 / 内部终止），
     * 所以本方法强制 {@code finishedOnly} 而不是把 status 写死成 COMPLETED ——
     * 写死的话，被人工终止和被 BPMN 错误终止的单子会从历史里消失，
     * 而"这单怎么没的"恰恰是事后最常被问的问题。
     */
    public List<WfProcessInstance> queryFinishedProcesses(WfProcessInstanceQuery query) {
        return persistence.queryProcessInstances(asFinished(query));
    }

    /** 与 {@link #queryFinishedProcesses} 同条件的条数。 */
    public long countFinishedProcesses(WfProcessInstanceQuery query) {
        return persistence.countProcessInstances(asFinished(query));
    }

    private WfProcessInstanceQuery asFinished(WfProcessInstanceQuery query) {
        if (query != null && query.isUnfinishedOnly()) {
            throw new IllegalArgumentException(
                    "历史流程实例查询不接受 unfinishedOnly："
                            + "在途的流程不是历史。要查在途请用 WfProcessInstanceQuery#setUnfinishedOnly。");
        }
        WfProcessInstanceQuery copy = query == null ? new WfProcessInstanceQuery() : copyOf(query);
        return copy.setFinishedOnly(true);
    }

    private WfProcessInstanceQuery copyOf(WfProcessInstanceQuery source) {
        WfProcessInstanceQuery copy = new WfProcessInstanceQuery();
        copy.setDefinitionKey(source.getDefinitionKey());
        copy.setBusinessKey(source.getBusinessKey());
        copy.setStartUserId(source.getStartUserId());
        copy.setCategory(source.getCategory());
        copy.setStatus(source.getStatus());
        copy.setStartTimeFrom(source.getStartTimeFrom());
        copy.setStartTimeTo(source.getStartTimeTo());
        copy.setResult(source.getResult());
        copy.setPageNum(source.getPageNum());
        copy.setPageSize(source.getPageSize());
        return copy;
    }

    private WfTaskQuery asHistoric(WfTaskQuery query) {
        if (query != null && query.isOpenOnly()) {
            throw new IllegalArgumentException(
                    "历史任务查询不接受 openOnly：已办结的任务不可能是未完成的。"
                            + "要查待办请用 WfTaskService / WfTaskQuery#setOpenOnly。");
        }
        WfTaskQuery historic = query == null ? new WfTaskQuery() : copyOf(query);
        return historic.setCompletedOnly(true);
    }

    private WfTaskQuery copyOf(WfTaskQuery source) {
        WfTaskQuery copy = new WfTaskQuery();
        copy.setProcessInstanceId(source.getProcessInstanceId());
        copy.setDefinitionId(source.getDefinitionId());
        copy.setAssignee(source.getAssignee());
        copy.setOwner(source.getOwner());
        copy.setCompleterId(source.getCompleterId());
        copy.setCategory(source.getCategory());
        copy.setCandidateUsers(source.getCandidateUsers());
        copy.setCandidateGroups(source.getCandidateGroups());
        copy.setStatus(source.getStatus());
        copy.setUnassignedOnly(source.isUnassignedOnly());
        copy.setCreateTimeFrom(source.getCreateTimeFrom());
        copy.setCreateTimeTo(source.getCreateTimeTo());
        copy.setPageNum(source.getPageNum());
        copy.setPageSize(source.getPageSize());
        return copy;
    }

    /**
     * 各环节平均耗时，用于定位瓶颈。
     *
     * <p>返回 {@code activityId -> 平均毫秒}。取 {@code sampleLimit} 条样本算均值
     * （默认 {@link #BOTTLENECK_SAMPLE_LIMIT} 条，够看趋势又不至于把全表拖出来）。
     */
    public Map<String, Long> getAverageDurationByActivity(String definitionKey, int sampleLimit) {
        int limit = sampleLimit <= 0 ? BOTTLENECK_SAMPLE_LIMIT : sampleLimit;
        List<WfActivityInstance> sample = persistence.queryActivityInstances(
                new WfHistoricActivityInstanceQuery()
                        .setProcessDefinitionKey(definitionKey)
                        .orderByDurationDesc()
                        .setPageNum(1).setPageSize(limit));
        Map<String, long[]> sums = new HashMap<>();
        for (WfActivityInstance item : sample) {
            long[] acc = sums.get(item.getActivityId());
            if (acc == null) {
                acc = new long[2];
                sums.put(item.getActivityId(), acc);
            }
            acc[0] += item.getDurationMillis();
            acc[1]++;
        }
        Map<String, Long> averages = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> entry : sums.entrySet()) {
            long[] acc = entry.getValue();
            averages.put(entry.getKey(), acc[1] == 0 ? 0L : acc[0] / acc[1]);
        }
        return averages;
    }

    /**
     * 清理 {@code before} 之前的历史数据。
     *
     * <p>只删<b>已结束</b>流程的历史。在途流程的历史删掉之后，审批轨迹会出现
     * 一个洞，而单据还在被人办 —— 那比表大难解释得多。
     *
     * <p>这是危险操作：{@code before} 传 null 直接拒绝而不是当成"清掉全部"，
     * 因为后者几乎一定是误用；调用方应当确认该时间点早于业务允许保留的期限
     * （通常与审计合规要求一致），并自行决定是否先做备份。
     *
     * @return 被删除的流程实例数
     */
    public int deleteHistoryBefore(Date before) {
        if (before == null) {
            throw new WfEngineException(
                    "清理历史必须给一个时间点。传 null 会被当作'清掉全部历史'，"
                            + "而那几乎一定是误用，所以这里直接拒绝。");
        }
        return persistence.deleteHistoryBefore(before);
    }

    /** 瓶颈分析的默认样本量。 */
    private static final int BOTTLENECK_SAMPLE_LIMIT = 1000;

    /*
     * 关于"已办列表"与"审批轨迹"：
     * 这两件事已有各自唯一的入口，不再在本类重复一份。
     *   已办列表   -> WfTaskService.getDoneList(userId, pageNum, pageSize)
     *   审批轨迹   -> WfRuntimeService.getTrail(processInstanceId)
     * 同一份数据开两条入口，迟早会各自漂移（改了一处忘了另一处），
     * 而调用方无从判断该信哪个 —— 所以这里只保留本类真正独有的
     * "已结束实例"与"统计/总览"能力。
     */

    /**
     * 流程统计：按状态分组计数（仪表盘用）。
     */
    public Map<String, Long> getProcessStatusCounts() {
        Map<String, Long> counts = new HashMap<>();
        for (WfProcessStatus status : WfProcessStatus.values()) {
            counts.put(status.name(), 0L);
        }
        for (WfProcessInstance instance : persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setPageNum(1).setPageSize(Integer.MAX_VALUE))) {
            String key = instance.getStatus() == null ? "UNKNOWN" : instance.getStatus().name();
            Long current = counts.get(key);
            counts.put(key, current == null ? 1L : current + 1L);
        }
        return counts;
    }

    /**
     * 流程总览：实例 + 当前待办 + 轨迹 + 评论，一次返回。
     *
     * <p>前端"流程详情"页需要这四份数据。分成四个接口会让前端发四个请求再自己拼，
     * 而且中间状态可能不一致（轨迹查完任务又被人办了）。这里一次算完。
     */
    public Map<String, Object> getProcessOverview(String processInstanceId) {
        WfProcessInstance instance = persistence.findProcessInstance(processInstanceId);
        Map<String, Object> overview = new HashMap<>();
        if (instance == null) {
            return overview;
        }
        overview.put("process", instance);
        overview.put("openTasks", persistence.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(processInstanceId).setOpenOnly(true)
                .setPageNum(1).setPageSize(Integer.MAX_VALUE)));
        overview.put("trail", persistence.findActivityInstances(processInstanceId));
        overview.put("comments", persistence.findComments(processInstanceId));
        overview.put("durationMillis", instance.durationMillis());
        return overview;
    }
}
