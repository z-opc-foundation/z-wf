package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.model.WfHistoricIncident;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.persistence.WfHistoricIncidentQuery;
import com.zifang.z.wf.core.persistence.WfPersistence;

/**
 * 历史故障（第 40 轮）——「某个 job 曾经失败过」。
 *
 * <p><b>它与 {@link WfIncidentService} 是两套数据，不是一套数据的两个读法。</b>
 * 后者每次都从 {@link WfJob} 现场扫出来，是「<b>现在</b>还卡着的」；
 * 这里是失败那一刻写下的记录，是「<b>曾经</b>发生过」。
 * job 执行成功（或被清理）之后，前者什么都查不到了 —— 这正是补这一套的原因：
 * 「上周三那批单为什么全卡住了」只有这一套能回答。
 *
 * <p><b>每一套各自只有一个数据来源</b>，不做合并也不做去重：
 * 当前故障**全部**来自 job 现场推导（零成本，且永远与 job 状态一致），
 * 历史故障**全部**来自这张表（job 没了它还在）。
 * 混起来做「当前 ∪ 历史」看起来能用，实际会造出「已经恢复的故障还在当前列表里」
 * 这种自相矛盾的结果，而且没有报错。
 *
 * <p><b>为什么只依赖 {@link WfPersistence}、不注入别的东西</b>：
 * 记录失败是<b>热路径</b>（每次 job 失败都要走一遍），而它需要的全部信息
 * 都在 job 与实例上。让 {@code WfJobService} / {@code WfExternalTaskService}
 * 为此多接一个依赖，等于把一个纯辅助动作挂到两条主链路的构造参数上。
 *
 * @author zifang
 */
public class WfHistoricIncidentService {

    private static final Logger log = LoggerFactory.getLogger(WfHistoricIncidentService.class);

    /** 记录 id 前缀。后面跟 job id，见 {@link #recordFailure} 里为什么用确定性 id。 */
    private static final String ID_PREFIX = "hist-";

    private final WfPersistence persistence;

    public WfHistoricIncidentService(WfPersistence persistence) {
        this.persistence = persistence;
    }

    // ==================== 记录 ====================

    /**
     * 记一次 job 失败。
     *
     * <p><b>一个 job 一行，反复失败时累加</b>：{@code failureCount} +1、
     * 刷 {@code lastFailureTime}、换掉 {@code errorMessage}，
     * 而 {@code firstFailureTime} <b>只在第一次写</b>（它答的是"从什么时候开始卡的"）。
     *
     * <p><b>用确定性 id（{@code hist-} + jobId）而不是 ID 生成器</b>：
     * 两个线程同时给同一个 job 记失败时，它们算出的是**同一个主键** ——
     * 于是要么撞上乐观锁、要么撞上 {@code JOB_ID} 上的唯一索引，
     * 两种都是响亮的失败。而用随机 id 的话，两行都能插进去，
     * 留下「一个 job 两行、failureCount 各是 1」，
     * 那正是这张表要回答的问题被自己答错了。
     *
     * @param job 失败的 job
     * @param rawMessage 失败原因原文
     * @return 落库后的那一行（失败原因已刷成这一次）
     */
    public WfHistoricIncident recordFailure(WfJob job, String rawMessage) {
        WfHistoricIncident existing = persistence.findHistoricIncidentByJobId(job.getId());
        WfHistoricIncident incident = existing == null ? newRow(job) : existing;
        incident.recordFailure(rawMessage);
        // 冗余字段每次都刷：job 换过节点之后，最近一次失败发生在哪儿才是有用的那个答案
        denormalize(incident, job);
        // **只在更新时 bump 版本**（插入路径不做版本校验，与 saveJob / saveBatch 同一契约）。
        // 漏掉这一句的现场很典型：第二次失败时传进去的 revision 与库里那份相同，
        // 于是要么撞乐观锁（像这次），要么——如果哪天把乐观锁去掉——
        // 就变成"后写覆盖前写"，悄悄丢掉一次 failureCount。
        if (existing != null) {
            incident.nextRevision();
        }
        persistence.saveHistoricIncident(incident);
        return incident;
    }

    private WfHistoricIncident newRow(WfJob job) {
        WfHistoricIncident incident = new WfHistoricIncident();
        incident.setId(ID_PREFIX + job.getId());
        incident.setJobId(job.getId());
        return incident;
    }

    /**
     * 把「谁的流程、哪个环节」固化到记录上。
     *
     * <p>这两个字段<b>不能等到查的时候再去关联</b>：流程实例会被
     * {@code deleteHistoryBefore} 清掉、定义会被改版，
     * 而故障记录的全部价值就是「事情过去之后还能查」。
     * 查不到实例时留空 —— 记不下就是记不下，
     * 不拿别的信息编一个出来填坑。
     */
    private void denormalize(WfHistoricIncident incident, WfJob job) {
        incident.setProcessInstanceId(job.getProcessInstanceId());
        incident.setExecutionId(job.getExecutionId());
        incident.setElementId(job.getElementId());
        incident.setAttachedToRef(job.getAttachedToRef());
        incident.setJobType(job.getType() == null ? null : job.getType().name());
        incident.setSubscriptionName(job.getSubscriptionName());
        String definitionKey = definitionKeyOf(job);
        incident.setDefinitionKey(definitionKey);
        incident.setActivityName(activityNameOf(definitionKey, job));
    }

    private String definitionKeyOf(WfJob job) {
        if (job.getProcessInstanceId() == null) {
            return null;
        }
        WfProcessInstance instance = persistence.findProcessInstance(job.getProcessInstanceId());
        return instance == null ? null : instance.getDefinitionKey();
    }

    private String activityNameOf(String definitionKey, WfJob job) {
        if (definitionKey == null || job.getProcessInstanceId() == null) {
            return null;
        }
        WfProcessInstance instance = persistence.findProcessInstance(job.getProcessInstanceId());
        if (instance == null) {
            return null;
        }
        WfDefinition definition = persistence.findDefinition(definitionKey,
                instance.getDefinitionVersion());
        if (definition == null) {
            return null;
        }
        // 边界事件取**宿主节点**的名字而不是事件自己的：elementId 指的是事件本身
        // （timeoutEscalate），人拿着「超时升级」去搜流程图才搜得到，
        // 拿事件自己的名字去搜是搜不到的。与 WfIncidentService 同一把尺子
        WfNode node = definition.node(job.getAttachedToRef() != null
                ? job.getAttachedToRef() : job.getElementId());
        return node == null ? null : node.getName();
    }

    // ==================== 查询 ====================

    public List<WfHistoricIncident> listIncidents(WfHistoricIncidentQuery query) {
        return persistence.queryHistoricIncidents(query);
    }

    /**
     * 这个 job 失败过吗；没有则返回 {@code null}。
     *
     * <p>它是「某个 job 曾经坏过没有」这个问题的<b>唯一</b>入口 ——
     * 与 {@link WfIncidentService} 那边「现在还卡着吗」是同一个 job 上的两个不同问题，
     * 刻意不合成一个方法：合成之后调用方必须自己判断返回值指的是哪一个语义。
     */
    public WfHistoricIncident findByJobId(String jobId) {
        return persistence.findHistoricIncidentByJobId(jobId);
    }

    public int countIncidents(WfHistoricIncidentQuery query) {
        return persistence.countHistoricIncidents(query);
    }

    /**
     * 某条流程实例的历史故障（倒序）。
     *
     * <p>与 {@link WfIncidentService#incidentsOf} 刻意<b>分页行为不同</b>：
     * 那边的条数等于「还卡着的 job 数」，天然很小；
     * 这边是「一次失败链记一行」，条数同样受控，
     * 所以也走分页而不是全量。
     */
    public List<WfHistoricIncident> incidentsOf(String processInstanceId) {
        return persistence.queryHistoricIncidents(
                new WfHistoricIncidentQuery().setProcessInstanceId(processInstanceId));
    }

    /**
     * 这条记录对应的 job <b>现在还在卡着吗</b>。
     *
     * <p><b>推导出来的，不是存下来的</b>：job 存在且带失败痕迹 = 还在卡；
     * job 没了或失败痕迹被清了 = 已经好了。
     * 之所以不落库，是因为「恢复」这件事只有 job 那侧知道，
     * 而 job 会被删 —— 在它被删的那一刻同步一遍这个标志，
     * 意味着给执行成功、办结、终止、清理四条路径都挂一个回调，
     * 漏掉一条的后果是「早就修好了的单子还挂着故障」。推导则天然不会漏。
     *
     * <p><b>因此它不能下推成 SQL 条件</b>，只在这里算。
     */
    public boolean isStillFailing(WfHistoricIncident incident) {
        if (incident.getJobId() == null) {
            return false;
        }
        WfJob job = persistence.findJob(incident.getJobId());
        if (job == null) {
            return false;
        }
        return job.getLastFailureTime() != null || job.getExceptionMessage() != null;
    }

    /** 现在仍然在失败的记录（内存过滤，与 {@link #isStillFailing} 同一把尺子）。 */
    public List<WfHistoricIncident> stillFailingIncidents(WfHistoricIncidentQuery query) {
        List<WfHistoricIncident> rows = persistence.queryHistoricIncidents(query);
        List<WfHistoricIncident> result = new ArrayList<>();
        for (WfHistoricIncident incident : rows) {
            if (isStillFailing(incident)) {
                result.add(incident);
            }
        }
        return result;
    }

    // ==================== 清理 ====================

    /**
     * 删掉「最后一次失败」早于给定时刻的记录。
     *
     * <p><b>刻意不并进 {@code deleteHistoryBefore}</b>：那个方法只清已结束流程，
     * 而故障记录可能属于一条还在跑的流程。按时间一刀切会把
     * 「三天前卡住、今天才发现」的那条删掉 —— 那恰恰是本表最该留住的东西。
     */
    public int deleteIncidentsBefore(Date before) {
        if (before == null) {
            throw new WfEngineException(
                    "清理历史故障必须给一个时间点。传 null 会被当作'清掉全部'，"
                            + "而那几乎一定是误用，所以这里直接拒绝。");
        }
        int removed = persistence.deleteHistoricIncidentsBefore(before);
        log.info("清理 {} 之前的历史故障: 删除 {} 条", before, removed);
        return removed;
    }
}