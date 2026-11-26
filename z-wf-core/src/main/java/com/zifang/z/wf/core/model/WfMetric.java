package com.zifang.z.wf.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 引擎指标（第 44 轮）。
 *
 * <p>对应 Camunda {@code ManagementService#createMetricsQuery} 的那批指标，
 * 挑的是审批系统真正常问的四类。它们合起来回答两句话：
 * <b>"上周办了多少单"</b> 与 <b>"平均批了多久、久的那批卡在哪一档"</b>。
 *
 * <h3>为什么只有这四类</h3>
 * 刻意<b>不做</b> Camunda 的 {@code job-executions} 与 {@code unique-task-users}：
 * 前者要一张 job 执行日志表（本仓 {@link WfJob} 的重试计数已在，
 * 按 job 类型与结果分组即可，不需要第二份记录），
 * 后者是 {@link #TASK_USERS} 的一次去重，<b>与其存一份去重结果，不如让调用方对
 * {@link #TASK_USERS} 的结果去重</b> —— 存下来的去重结果会与实时数据漂移，
 * 而那种漂移在指标上表现为「去重用户数莫名变多」，没人查得出来。
 *
 * <h3>刻意不做异步聚合日志</h3>
 * Camunda 的 metrics 走一张独立的 metric 日志表，由 job 异步写。
 * 本引擎<b>不引入那张表</b>：所有指标都从 {@code ZWF_PROCESS} / {@code ZWF_TASK}
 * <b>现算</b>。理由是<b>不要第二个真源</b> ——
 * 引擎自己的数据已经全了，再维护一份派生副本意味着每次改状态机都要记得同步它，
 * 而漏同步的症状是「指标说 100 单、库里其实 98 单」，
 * 那比慢一拍危险得多（慢一拍只是读数旧，数字对不上是数据在骗人）。
 * ⇒ 代价是扫全表，所以 {@link WfMetricsService} 带一道扫描上限闸门。
 *
 * @author zifang
 */
public enum WfMetric {

    /**
     * 启动的流程实例数，可按定义分组。
     *
     * <p>窗口落在 {@code START_TIME} 上 —— 统计的是"<b>启动</b>了多少"，
     * 不是"完成了多少"。这两个数在审批系统里差着一个量级：
     * 一堆单子启动后卡在审批节点上，完成数看着正常，启动数才反映真实积压。
     */
    PROCESS_INSTANCES,

    /**
     * 端到端时长（启动到结束），基于<b>已结束</b>的实例。
     *
     * <p>在途实例没有时长可言，所以这一类只统计终态；
     * 而"平均多久批完"这句话问的本来就只能是已经批完的那些。
     */
    PROCESS_INSTANCE_DURATION,

    /**
     * 单个任务的办理时长（创建到办结），基于<b>已办结</b>的任务。
     *
     * <p>与 {@link #PROCESS_INSTANCE_DURATION} 的差别在多节点流程上很值钱：
     * 端到端一天、单节点两小时，说明单点效率没问题，<b>卡在串行的环节数上</b>；
     * 端到端两小时、单节点两小时，说明问题就在某一个节点。
     */
    TASK_DURATION,

    /**
     * 按<b>办理人</b>分组的办结任务数（工作量口径）。
     *
     * <p>用 {@code completerId} 而不是 {@code assignee}：
     * {@code assignee} 是"派给谁"，{@code completerId} 是"实际是谁点的通过"。
     * 转办之后两者会分叉，而工作量要按实际动手的人算。
     */
    TASK_USERS;

    /**
     * 直方图分桶（毫秒），下界闭、上界开，最后一桶无上界。
     *
     * <p><b>分桶的边界是审批系统真实会卡住的那几个时间尺度</b>，
     * 不是等距切出来的：
     * 5 分钟内是"顺手批"，半小时内是"看完批"，两小时是"压在待办列表里"，
     * 一天以上是"基本忘了"。
     * 等距分桶会让 99% 的数据落进第一格，看不出分布。
     *
     * <p>⚠️ <b>边界成对写</b>：恰好落在下界上属于下一桶（{@code from} 闭），
     * 上一桶因此是 {@code [from, to)}。这个选择必须写出来 ——
     * 两边都闭或都开会让人在边界上数出两条或零条。
     */
    private static final long[][] BUCKETS = {
        {0L, 60_000L},              // [0, 1 分钟)
        {60_000L, 300_000L},        // [1, 5 分钟)
        {300_000L, 1_800_000L},     // [5, 30 分钟)
        {1_800_000L, 7_200_000L},   // [30 分钟, 2 小时)
        {7_200_000L, 28_800_000L},  // [2, 8 小时)
        {28_800_000L, 86_400_000L}, // [8, 24 小时)
        {86_400_000L, 259_200_000L},// [1, 3 天)
        {259_200_000L, Long.MAX_VALUE} // [3 天以上)
    };

    /** 该指标是否返回直方图（false 表示只返回按维度分组的一行行计数）。 */
    public boolean isHistogram() {
        return this == PROCESS_INSTANCE_DURATION || this == TASK_DURATION;
    }

    /** 分桶标签，供展示用；<b>标签里的边界必须与 {@link #BUCKETS} 一致</b>。 */
    public static List<String> bucketLabels() {
        List<String> labels = new ArrayList<>();
        for (long[] bounds : BUCKETS) {
            labels.add(label(bounds[0], bounds[1]));
        }
        return labels;
    }

    /** 某个时长落进第几桶；<b>越界一律夹到最外两桶</b>，不抛异常。 */
    public static int bucketOf(long millis) {
        for (int i = 0; i < BUCKETS.length; i++) {
            long[] bounds = BUCKETS[i];
            if (millis < bounds[1]) {
                return i;
            }
        }
        return BUCKETS.length - 1;
    }

    private static String label(long from, long to) {
        if (to == Long.MAX_VALUE) {
            return humanize(from) + "以上";
        }
        return humanize(from) + " ~ " + humanize(to);
    }

    private static String humanize(long millis) {
        long seconds = millis / 1000L;
        if (seconds < 60L) {
            return seconds + " 秒";
        }
        long minutes = seconds / 60L;
        if (minutes < 60L) {
            return minutes + " 分钟";
        }
        long hours = minutes / 60L;
        if (hours < 24L) {
            return hours + " 小时";
        }
        return (hours / 24L) + " 天";
    }
}