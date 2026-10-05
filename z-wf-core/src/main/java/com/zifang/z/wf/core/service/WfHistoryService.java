package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.persistence.WfPersistence;
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
     * 已完成的流程实例。
     */
    public List<WfProcessInstance> getCompletedInstances(int pageNum, int pageSize) {
        List<WfProcessInstance> all = new ArrayList<>();
        for (WfProcessInstance instance : persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setPageNum(1).setPageSize(Integer.MAX_VALUE))) {
            if (instance.getStatus() == WfProcessStatus.COMPLETED) {
                all.add(instance);
            }
        }
        return paginate(all, pageNum, pageSize);
    }

    /**
     * 某用户发起的已完成流程。
     */
    public List<WfProcessInstance> getCompletedInstancesByUser(String userId, int pageNum, int pageSize) {
        List<WfProcessInstance> all = new ArrayList<>();
        for (WfProcessInstance instance : persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setStartUserId(userId)
                        .setPageNum(1).setPageSize(Integer.MAX_VALUE))) {
            if (instance.getStatus() == WfProcessStatus.COMPLETED) {
                all.add(instance);
            }
        }
        return paginate(all, pageNum, pageSize);
    }

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

    private <T> List<T> paginate(List<T> list, int pageNum, int pageSize) {
        if (list.isEmpty()) {
            return new ArrayList<>();
        }
        int size = pageSize < 1 ? 20 : pageSize;
        int from = Math.max(0, (pageNum - 1) * size);
        if (from >= list.size()) {
            return new ArrayList<>();
        }
        int to = Math.min(list.size(), from + size);
        return new ArrayList<>(list.subList(from, to));
    }
}
