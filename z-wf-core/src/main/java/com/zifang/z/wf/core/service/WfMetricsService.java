package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.zifang.z.wf.core.model.WfMetric;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.persistence.WfProcessInstanceQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 引擎指标（第 44 轮）。
 *
 * <p>回答审批系统真正常问的两句话：<b>"上周办了多少单"</b>、<b>"平均批了多久"</b>。
 * 对应 Camunda 的 {@code ManagementService#createMetricsQuery}，
 * 刻意只取四类，理由见 {@link WfMetric}。
 *
 * <h3>为什么在 Java 里算而不是 SQL 聚合</h3>
 * 直方图要对 {@code END_TIME - START_TIME} 分桶，而<b>时间差没有跨库统一的写法</b>：
 * H2 与 MySQL 有 {@code TIMESTAMPDIFF}，PostgreSQL 只有
 * {@code EXTRACT(EPOCH FROM (b - a))}。
 * ⇒ 与 {@link WfProcessQueryService}（变量条件）、{@link WfExecutionQueryService}
 * （token 条件）完全同构：**过滤能下推的条件下推，时长一律回 Java 算**。
 * 分组计数同样在 Java 里做，不因为"COUNT 跨库安全"就分两套 ——
 * 两套实现迟早漂移，而漂移的症状是「总数对得上、分组对不上」。
 *
 * <h3>代价与闸门</h3>
 * 现算意味着要扫全表，所以这里带一道 {@link #MAX_SCAN} 上限：
 * <b>超了报错，不返回一份少算了的指标</b> —— 指标被摆在看板上，
 * 而一个偏小的数字比一个报错危险得多（没人会去核对看板上的总数）。
 * 报错文案要说清怎么缩小范围（限定 definitionKey 或收窄时间窗口）。
 *
 * <h3>与 {@link WfHistoryService#getProcessStatusCounts()} 的关系</h3>
 * 那也是一个 Java 侧聚合，本轮<b>刻意没动它</b>（改了要重跑既有判据，
 * 且它已经稳定）。两个入口的数据同源，不冲突；
 * 但它们各自会漂 —— 所以新指标在注释里点名了它，
 * 将来要把它们收敛成一处时，知道该往哪儿并。
 *
 * @author zifang
 */
public class WfMetricsService {

    /**
     * 一次指标查询最多扫多少行。
     *
     * <p>比 {@link WfProcessQueryService#MAX_SCAN} 大：那边是"按变量查某一类单子"，
     * 这边是"全量统计"，本仓审批场景的实例量级在十万级，
     * 而看板本来就是低频查询。
     *
     * <p>超了怎么办：<b>报错，不截断</b>。
     */
    public static final int MAX_SCAN = 100000;

    private final WfPersistence persistence;
    private final int maxScan;

    /**
     * 只依赖 {@link WfPersistence}，不依赖 {@link WfRepositoryService}。
     *
     * <p>理由：查定义版本这件事 {@code WfPersistence.findDefinitionVersions} 本来就有，
     * 多引一个 service 只是让 {@link WfManagementService} 的构造也得跟着改 ——
     * 而那个构造有既有调用点，为一个新能力动它不值当。
     */
    public WfMetricsService(WfPersistence persistence) {
        this(persistence, MAX_SCAN);
    }

    /**
     * 换掉扫描上限。
     *
     * <p>存在是为了让那道闸门<b>可被验证</b>：造十万条实例只为证明
     * "超了会报错"，代价是一次几分钟的测试，而闸门恰恰是本轮最要紧的一条。
     *
     * @param maxScan 上限；{@code <= 0} 视为 {@link #MAX_SCAN}
     */
    public WfMetricsService(WfPersistence persistence, int maxScan) {
        this.persistence = persistence;
        this.maxScan = maxScan <= 0 ? MAX_SCAN : maxScan;
    }

    /**
     * 算指标。
     *
     * <p>返回的列表<b>末尾总是带一行 {@link WfMetricRow#ALL}</b> ——
     * 直方图类指标靠它给总数与最值，分组类指标靠它给总计。
     * 调用方不必自己相加，而"忘了相加"是这类接口最常见的用法错误。
     */
    public List<WfMetricRow> query(WfMetricsQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("指标查询不能为 null。");
        }
        query.assertConsistent();
        switch (query.getMetric()) {
            case PROCESS_INSTANCES:
                return countInstances(query);
            case PROCESS_INSTANCE_DURATION:
                return instanceDuration(query);
            case TASK_DURATION:
                return taskDuration(query);
            case TASK_USERS:
                return taskUsers(query);
            default:
                // 新加枚举值时忘了实现 —— 那是一条编译期抓不到的洞（switch 不是穷尽检查）
                throw new IllegalStateException("指标 " + query.getMetric() + " 尚未实现。");
        }
    }

    // ==================== 实例数（按定义分组） ====================

    private List<WfMetricRow> countInstances(WfMetricsQuery query) {
        Map<String, Long> byDefinition = new LinkedHashMap<>();
        long total = 0L;
        for (WfProcessInstance instance : scanInstances(query)) {
            // 启动时间落在窗口内由 SQL 保证；这里只补 definitionKey 这一道
            // （窗口条件下推了，但分组键必须自己数）
            String key = instance.getDefinitionKey() == null
                    ? "(无定义)" : instance.getDefinitionKey();
            Long current = byDefinition.get(key);
            byDefinition.put(key, current == null ? 1L : current + 1L);
            total++;
        }
        return withTotal(byDefinition, total);
    }

    // ==================== 端到端时长直方图 ====================

    private List<WfMetricRow> instanceDuration(WfMetricsQuery query) {
        List<Long> durations = new ArrayList<>();
        for (WfProcessInstance instance : scanInstances(query)) {
            Long millis = elapsed(instance.getStartTime(), instance.getEndTime());
            if (millis != null) {
                durations.add(millis);
            }
        }
        return histogram(durations);
    }

    // ==================== 任务时长直方图 ====================

    private List<WfMetricRow> taskDuration(WfMetricsQuery query) {
        List<Long> durations = new ArrayList<>();
        for (WfTask task : scanTasks(query)) {
            Long millis = elapsed(task.getCreateTime(), task.getEndTime());
            if (millis != null) {
                durations.add(millis);
            }
        }
        return histogram(durations);
    }

    // ==================== 按办理人分组 ====================

    private List<WfMetricRow> taskUsers(WfMetricsQuery query) {
        Map<String, Long> byUser = new LinkedHashMap<>();
        long total = 0L;
        for (WfTask task : scanTasks(query)) {
            // **按实际办理人分组**：转办之后 assignee（派给谁）与
            // completerId（谁点的通过）会分叉，而工作量要按动手的人算
            String who = task.getCompleterId() == null
                    ? "(未办结)" : task.getCompleterId();
            Long current = byUser.get(who);
            byUser.put(who, current == null ? 1L : current + 1L);
            total++;
        }
        return withTotal(byUser, total);
    }

    // ==================== 聚合与直方图 ====================

    /** 倒序排（多的在前），同数按名字排 —— <b>同数时也要有确定顺序</b>，否则两页看板排序会跳。 */
    private List<WfMetricRow> withTotal(Map<String, Long> counts, long total) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Long>>() {
            @Override
            public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                int byCount = b.getValue().compareTo(a.getValue());
                return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
            }
        });
        List<WfMetricRow> rows = new ArrayList<>();
        for (Map.Entry<String, Long> entry : entries) {
            rows.add(new WfMetricRow(entry.getKey(), entry.getValue().longValue()));
        }
        rows.add(new WfMetricRow(WfMetricRow.ALL, total));
        return rows;
    }

    /**
     * 摊成直方图：每桶一行 + 一行 {@link WfMetricRow#ALL}。
     *
     * <p><b>空桶也要出一行（count=0）</b>：看板上少一格与"这一档是 0"
     * 在视觉上不一样，而"这一档真的没有"是要能一眼看出来的。
     */
    private List<WfMetricRow> histogram(List<Long> durations) {
        long[] buckets = new long[WfMetric.bucketLabels().size()];
        long sum = 0L;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (Long millis : durations) {
            buckets[WfMetric.bucketOf(millis.longValue())]++;
            sum += millis.longValue();
            min = Math.min(min, millis.longValue());
            max = Math.max(max, millis.longValue());
        }
        List<String> labels = WfMetric.bucketLabels();
        List<WfMetricRow> rows = new ArrayList<>();
        for (int i = 0; i < buckets.length; i++) {
            rows.add(new WfMetricRow(labels.get(i), buckets[i]));
        }
        WfMetricRow all = new WfMetricRow(WfMetricRow.ALL, durations.size());
        if (!durations.isEmpty()) {
            all.setSumMillis(Long.valueOf(sum));
            all.setAvgMillis(Long.valueOf(sum / durations.size()));
            all.setMinMillis(Long.valueOf(min));
            all.setMaxMillis(Long.valueOf(max));
        }
        rows.add(all);
        return rows;
    }

    /**
     * 时长。
     *
     * <p>返回 {@code null} 表示<b>这条没有时长可言</b>（在途、没有结束时间），
     * 那一行不参与统计 —— 把在途当成"时长 0"会让平均时长被拉低，
     * 而"在途"与"瞬间批完"是两回事。
     *
     * <p><b>负时长夹到 0</b>：时序库之外改过系统时间、或数据被手工改过，
     * 都可能让结束时间早于开始时间。
     * 负数会让平均值失真、还会落到第 0 桶里冒充"秒批"。
     * 夹到 0 是最小的撒谎 —— 真值对不上这件事本身没有第二个更好的出口。
     */
    private Long elapsed(Date from, Date to) {
        if (from == null || to == null) {
            return null;
        }
        long millis = to.getTime() - from.getTime();
        return Long.valueOf(millis < 0L ? 0L : millis);
    }

    // ==================== 扫描（含闸门） ====================

    private List<WfProcessInstance> scanInstances(WfMetricsQuery query) {
        WfProcessInstanceQuery inner = new WfProcessInstanceQuery()
                .setPageNum(1).setPageSize(maxScan + 1)
                .setStartTimeFrom(query.getStartDate())
                .setStartTimeTo(query.getEndDate());
        if (query.getDefinitionKey() != null) {
            inner.setDefinitionKey(query.getDefinitionKey());
        }
        List<WfProcessInstance> rows = persistence.queryProcessInstances(inner);
        if (rows == null) {
            return new ArrayList<>();
        }
        if (rows.size() > maxScan) {
            throw overflow(query.getMetric());
        }
        return rows;
    }

    /**
     * 扫任务。
     *
     * <h3>任务表上没有「流程定义 key」可筛</h3>
     * {@code WfTaskQuery#setDefinitionId} 这个名字有误导：任务上的
     * {@code DEF_ID} 存的其实是<b>节点 id</b>（引擎里
     * {@code setDefinitionId(nodeId)} 就是这么用的），
     * 拿它按定义 key 去筛会得到一批毫不相关的任务。
     *
     * <p>而 {@code WfTask} 上确实没有 {@code DEF_KEY}。
     * ⇒ 给了 {@code definitionKey} 时只能<b>先圈出实例、再按实例取任务</b>；
     * 没给时一次查完（窗口按任务的 {@code createTime}）。
     *
     * <p><b>不 join 实例表</b>：本仓在查询里刻意不 join（理由见
     * {@code createExecutionQuery} 那条）—— 一旦 join，
     * 实例表的状态过滤就会变成任务查询的隐含条件，
     * 而那种耦合在"只想按时间窗口查任务"时会悄悄过滤掉实例已删、任务仍在的行。
     * 慢一点比给出一个口径不明的数字好。
     */
    private List<WfTask> scanTasks(WfMetricsQuery query) {
        List<WfTask> all = new ArrayList<>();
        if (query.getDefinitionKey() == null) {
            WfTaskQuery inner = new WfTaskQuery()
                    .setPageNum(1).setPageSize(maxScan + 1)
                    .setCreateTimeFrom(query.getStartDate())
                    .setCreateTimeTo(query.getEndDate());
            return guardTasks(all, inner, query.getMetric());
        }
        for (WfProcessInstance instance : scanInstances(query)) {
            WfTaskQuery inner = new WfTaskQuery()
                    .setPageNum(1).setPageSize(maxScan + 1)
                    .setProcessInstanceId(instance.getId())
                    .setCreateTimeFrom(query.getStartDate())
                    .setCreateTimeTo(query.getEndDate());
            all = guardTasks(all, inner, query.getMetric());
        }
        return all;
    }

    private List<WfTask> guardTasks(List<WfTask> soFar, WfTaskQuery inner, WfMetric metric) {
        List<WfTask> rows = persistence.queryTasks(inner);
        if (rows == null || rows.isEmpty()) {
            return soFar;
        }
        if (soFar.size() + rows.size() > maxScan) {
            throw overflow(metric);
        }
        soFar.addAll(rows);
        return soFar;
    }

    private WfEngineException overflow(WfMetric metric) {
        return new WfEngineException("算指标 " + metric + " 时扫到的行超过 " + maxScan
                + " 条，没有算完。缩小范围（限定 definitionKey，或收窄 startDate/endDate）再算。"
                + "给一份少算了的指标比报错更坏：它会被原样摆在看板上，而没人会去核对总数。");
    }
}