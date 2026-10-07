package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.engine.WfHistoryLevel;
import com.zifang.z.wf.core.persistence.JdbcWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.view.WfTableInfo;

/**
 * 引擎自省 —— "我连的是什么、底下有什么、有多少"。
 *
 * <p>对应 Camunda 的 {@code ManagementService#getProperties} / {@code getTableNames} /
 * {@code getTableCount}。开源引擎要有可运维性，这三个是最低配：
 * 出了事第一句要问的是「你连的是哪个库、哪些表在、里面各多少行」，
 * 而这三句话不该只能靠人手工连上去数。
 *
 * <p><b>存储形状由持久化实现自己说。</b>本类不判断底下是表还是 Map ——
 * {@code WfPersistence} 自己报名字与条数，本类只负责标出
 * {@link WfTableInfo#getKind()}，免得内存模式下的 {@code ZWF_TASK}
 * 被当成"库里真有这张表"。
 *
 * <p><b>版本号是手写常量，所以有一条测试盯着它不漂。</b>
 * 运行时读 pom / manifest 都不可靠（测试跑在 classes 目录里，manifest 里没有版本），
 * 唯一可靠的做法是把版本写死，再用一条断言把它与 pom 的 {@code <revision>} 钉在一起。
 * 注释里写"版本是 2.0.0"而没人守它，是这类接口最常见的静默漂移。
 *
 * @author zifang
 */
public class WfManagementService {

    private static final Logger log = LoggerFactory.getLogger(WfManagementService.class);

    /**
     * 引擎版本。
     *
     * <p>与 pom 的 {@code <revision>} 同步。{@code WfManagementServiceTest} 有一条
     * 断言直接读 pom 的 {@code <revision>} 与本常量比对 —— 改了 pom 忘了改这里会变红。
     */
    public static final String VERSION = "2.0.0";

    /**
     * 存储结构版本。
     *
     * <p><b>它是"建表脚本的形状"的版本号，不是"数据格式"的版本号。</b>
     * 往表里加字段<b>不</b>升它 —— 本仓对加字段的约定是补列迁移（{@code ALTER TABLE ... ADD COLUMN}），
     * 老库不需要重建，因此数据结构上是兼容的。
     * 升它的情况只有一种：某张表被重命名或删除。
     */
    public static final String SCHEMA_VERSION = "1";

    private final WfPersistence persistence;

    /**
     * 当前历史级别（第 42 轮）。自省接口要能回答「这个引擎到底记不记历史」——
     * 否则运维看到轨迹是空的，唯一能做的事是去翻配置文件，
     * 而空轨迹的第一个原因永远是"配错了"。
     */
    private final WfHistoryLevel historyLevel;

    public WfManagementService(WfPersistence persistence) {
        this(persistence, WfHistoryLevel.DEFAULT);
    }

    public WfManagementService(WfPersistence persistence, WfHistoryLevel historyLevel) {
        this.persistence = persistence;
        this.historyLevel = historyLevel == null ? WfHistoryLevel.DEFAULT : historyLevel;
    }

    /**
     * 引擎属性。
     *
     * <p>刻意<b>不含</b>连接串、账号、口令：自省接口常被监控无差别地暴露出去，
     * 而 {@code getProperties} 的用途（回答"这是哪个引擎、什么版本"）
     * 完全不需要凭据。真的要看连接信息，从部署配置里看。
     */
    public Map<String, Object> getProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("engine", "z-wf");
        properties.put("version", VERSION);
        properties.put("schemaVersion", SCHEMA_VERSION);
        properties.put("persistence", persistenceKind());
        properties.put("storage", storageHint());
        // 级别与它「会少记什么」一起给：只给一个 "audit"，
        // 看到轨迹里没有变量中间值的人仍然不知道为什么
        properties.put("historyLevel", historyLevel.name().toLowerCase());
        properties.put("historyLevelDetail", historyLevel.describe());
        List<String> names = persistence.getTableNames();
        properties.put("storageCount", names.size());
        return properties;
    }

    /**
     * 存储清单：每项的名字、类型与行数。
     *
     * <p>逐个取条数而不是让持久层一次给全 —— SPI 上就是这两个方法，
     * 强行加一个"批量版"会让两套实现各写一遍取行数的分支，
     * 而那两处一旦漂移，症状是"清单里有一项永远是 0"。
     */
    public List<WfTableInfo> getTables() {
        // 注意比的是 "jdbc"（持久化形态），**不是** WfTableInfo.KIND_TABLE（"table"）——
        // 拿 KIND_TABLE 去比 persistenceKind() 永远不成立，结果是 JDBC 也会被标成 collection，
        // 而那正好是本类要防的那类"自省接口骗人"。
        String kind = "jdbc".equals(persistenceKind())
                ? WfTableInfo.KIND_TABLE : WfTableInfo.KIND_COLLECTION;
        List<WfTableInfo> tables = new ArrayList<>();
        for (String name : persistence.getTableNames()) {
            tables.add(new WfTableInfo(name, kind, persistence.getTableCount(name)));
        }
        return tables;
    }

    /**
     * 某一项的条数。
     *
     * <p>名字不认识时<b>抛异常</b>，不返回 0：拼错一个表名得到"这里是空的"，
     * 会把排障方向从"我拼错了"带偏到"谁把它清空了"。
     */
    public long getTableCount(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new WfEngineException("存储项名不能为空。要看全部请调用 listTables");
        }
        return persistence.getTableCount(name.trim());
    }

    /** {@code jdbc} 或 {@code in-memory}。 */
    public String persistenceKind() {
        return persistence instanceof JdbcWorkflowPersistence ? "jdbc" : "in-memory";
    }

    /**
     * 引擎指标（第 44 轮）。
     *
     * <p>对应 Camunda 的 {@code ManagementService#createMetricsQuery} ——
     * metrics 本来就属于"运维自诊断"这一面，放进自省服务而不是新开一个入口，
     * 调用方也少一次依赖。
     *
     * <p><b>刻意委派而不是把实现搬进来</b>：指标有扫描闸门、分桶与跨库时长处理，
     * 混进这个只做"报数"的类会让它长到五百行，而两者的变化节奏完全不同
     * （自省接口的形状很稳定，指标口径会随业务反复调）。
     *
     * <p>与 {@link WfHistoryService#getProcessStatusCounts()} 的分工：
     * 那一个只按状态分组、<b>不带时间窗口</b>；这一个带窗口与定义过滤，
     * 是"上周办了多少"。两者数据同源、口径不同，<b>刻意不合并</b> ——
     * 合并会让看板上「现在的在途分布」与「上周的完成情况」挤进同一个接口。
     */
    public List<WfMetricRow> queryMetrics(WfMetricsQuery query) {
        return metricsService().query(query);
    }

    /**
     * 指标服务。
     *
     * <p><b>刻意就地 new 而不是构造注入</b>：它只有一条出参路径、
     * 不持有任何可变状态、也不需要按历史级别分档，
     * 为此给本类的构造加参数会波及既有调用点，而收益为零。
     */
    private WfMetricsService metricsService() {
        return new WfMetricsService(persistence);
    }

    /**
     * 人能看懂的一句说明。
     *
     * <p>内存实现下刻意写"进程内集合，<b>不落库</b>"——
     * 重启即失、且多实例之间不共享，这两条是排障时最先要确认的，
     * 写在返回值里比让人自己猜要快得多。
     */
    private String storageHint() {
        if ("jdbc".equals(persistenceKind())) {
            return "关系库中的表";
        }
        return "进程内集合，不落库：重启即失，多实例之间不共享";
    }

    static {
        log.debug("引擎自省可用：{} v{}，schema v{}", "z-wf", VERSION, SCHEMA_VERSION);
    }
}