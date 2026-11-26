package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import com.zifang.z.wf.core.persistence.WfPersistence;

/**
 * 部署历史查询的<b>唯一</b>实现（第 47 轮）—— 过滤、排序、分页都在这里。
 *
 * <p><b>为什么单独一个类，而不是把条件加进两套持久化实现</b>：
 * {@link WfPersistence#findDeploymentEntries()} 在内存与 JDBC 两边都只做
 * 「把一行读成 {@link WfDeploymentEntry} 投影」，
 * <b>一行过滤、一行排序都没有</b>。本仓已经吃过两次亏：
 * 第 45 轮的任务查询条件在 {@code InMemoryWorkflowPersistence#matches} 与
 * {@code JdbcWorkflowPersistence#appendTaskFilters} 各写一遍，
 * 漏一边时症状是「开发期内存全绿、换 JDBC 之后查不到」，而<b>内存模式下完全不可见</b>。
 * ⇒ 这次让两边都<b>没有可漂移的余地</b>，而不是靠"记得两处一起改"。
 *
 * <p>时间区间与排序也一并收在这里，同样是这个理由：
 * {@code DEPLOY_TIME} 可空，而「NULL 排前还是排后」在 H2 / MySQL / PostgreSQL 上结论相反
 * （本仓在 {@code WfExecutionQueryService} 上已经为此把排序从 SQL 收回了 Java）。
 *
 * @author zifang
 */
public class WfDeploymentQueryService {

    /**
     * 单次查询允许扫过的部署记录条数上限。
     *
     * <p><b>超了报错而不是截断</b>：给一份缺了行的部署清单，
     * 而清单里<b>没有任何东西</b>能表明它缺过 ——
     * 运维拿着它会得出"上周只部署了 3 个流程"的结论，而真相是有 5000 个。
     */
    public static final int MAX_SCAN = 5000;

    private final WfPersistence persistence;

    public WfDeploymentQueryService(WfPersistence persistence) {
        this.persistence = persistence;
    }

    /**
     * 查部署历史。
     *
     * @return 排好序、切好页的部署记录；没有命中返回空列表
     * @throws WfEngineException 区间倒置，或扫描量超过 {@link #MAX_SCAN}
     */
    public List<WfDeploymentEntry> query(WfDeploymentQuery query) {
        WfDeploymentQuery effective = (query == null ? new WfDeploymentQuery() : query).assertConsistent();
        List<WfDeploymentEntry> all = persistence.findDeploymentEntries();
        if (all.size() > MAX_SCAN) {
            throw new WfEngineException("部署记录条数 " + all.size() + " 超过单次查询上限 " + MAX_SCAN
                    + "。请用 key / 分类 / 部署时间区间收窄范围，"
                    + "而不是拿一份缺了行的清单当完整结果");
        }

        List<WfDeploymentEntry> matched = new ArrayList<>();
        for (WfDeploymentEntry entry : all) {
            if (matches(entry, effective)) {
                matched.add(entry);
            }
        }
        matched.sort(comparatorOf(effective.getOrderBy()));

        int offset = effective.offset();
        if (offset >= matched.size()) {
            return new ArrayList<>();
        }
        int size = effective.normalizedPageSize();
        if (size <= 0) {
            return matched;
        }
        int end = offset + size;
        return new ArrayList<>(matched.subList(offset, Math.min(end, matched.size())));
    }

    /** 命中的总条数（不分页）。给列表页显示「第 x–y 条，共 n 条」。 */
    public int count(WfDeploymentQuery query) {
        WfDeploymentQuery effective = (query == null ? new WfDeploymentQuery() : query).assertConsistent();
        List<WfDeploymentEntry> all = persistence.findDeploymentEntries();
        if (all.size() > MAX_SCAN) {
            throw new WfEngineException("部署记录条数 " + all.size() + " 超过单次查询上限 " + MAX_SCAN);
        }
        int matched = 0;
        for (WfDeploymentEntry entry : all) {
            if (matches(entry, effective)) {
                matched++;
            }
        }
        return matched;
    }

    /**
     * 单条记录的逐条件判定。
     *
     * <p><b>部署时间为 null 一律不匹配时间区间</b>，与本仓 {@code WfTaskQuery}
     * 对 {@code END_TIME} / {@code DUE_DATE} 的处理同一条纪律：
     * {@code deploy()} 一定会写 {@code DEPLOY_TIME}，但<b>库里可能有更早的、
     * 在这一列存在之前写进去的行</b>（{@code CREATE TABLE IF NOT EXISTS} 不给存量表补列）。
     * 把 null 当 0 毫秒算，等于回答「这条什么时候部署的」时说「1970 年 1 月 1 日」，
     * 而它在按时间窗筛部署的清单里会**混进任意一窗**。
     */
    private boolean matches(WfDeploymentEntry entry, WfDeploymentQuery query) {
        if (entry == null) {
            return false;
        }
        if (notBlank(query.getKey()) && !query.getKey().trim().equals(entry.getKey())) {
            return false;
        }
        if (notBlank(query.getKeyLike())
                && (entry.getKey() == null || !entry.getKey().contains(query.getKeyLike().trim()))) {
            return false;
        }
        if (notBlank(query.getNameLike())
                && (entry.getName() == null || !entry.getName().contains(query.getNameLike().trim()))) {
            return false;
        }
        if (notBlank(query.getCategory()) && !query.getCategory().trim().equals(entry.getCategory())) {
            return false;
        }
        if (query.getSuspended() != null
                && entry.isSuspended() != query.getSuspended().booleanValue()) {
            return false;
        }
        if (query.getDefaultDefinition() != null
                && entry.isDefaultDefinition() != query.getDefaultDefinition().booleanValue()) {
            return false;
        }
        if (query.getHasSourceXml() != null
                && entry.isHasSourceXml() != query.getHasSourceXml().booleanValue()) {
            return false;
        }
        Date deployedFrom = query.getDeployedFrom();
        Date deployedTo = query.getDeployedTo();
        if ((deployedFrom != null || deployedTo != null)) {
            Date deployTime = entry.getDeployTime();
            if (deployTime == null) {
                return false;
            }
            if (deployedFrom != null && deployTime.before(deployedFrom)) {
                return false;
            }
            if (deployedTo != null && deployTime.after(deployedTo)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 排序，并<b>永远追加 tiebreaker</b>。
     *
     * <p>{@code DEPLOY_TIME} 是毫秒精度，一次导入几十个流程时它们很可能落在同一毫秒。
     * 不追加 tiebreaker 时同毫秒的相对次序取决于存储层返回顺序，
     * 表现是「同一页翻两次，顺序不一样」「第 2 页里有一条第 1 页也出现过」，
     * 而这一切<b>没有任何报错</b>。追加的 {@code key + version} 是唯一的、不会变的。
     */
    private Comparator<WfDeploymentEntry> comparatorOf(WfDeploymentOrder order) {
        final WfDeploymentOrder effective = order == null ? WfDeploymentOrder.DEPLOY_TIME_DESC : order;
        return new Comparator<WfDeploymentEntry>() {
            @Override
            public int compare(WfDeploymentEntry left, WfDeploymentEntry right) {
                int result;
                if (effective == WfDeploymentOrder.KEY_ASC) {
                    result = compareKey(left, right);
                } else if (effective == WfDeploymentOrder.KEY_DESC) {
                    result = -compareKey(left, right);
                } else if (effective == WfDeploymentOrder.DEPLOY_TIME_ASC) {
                    result = compareTime(left, right);
                } else {
                    result = -compareTime(left, right);
                }
                if (result != 0) {
                    return result;
                }
                // tiebreaker：key 升序 + 版本号降序。
                // **时间相同不是"这两条一样"** —— 同一毫秒内先后部署的两个版本
                // 必须在翻页时保持一个稳定次序，否则第二页会漏掉其中一条。
                int byKey = compareKey(left, right);
                if (byKey != 0) {
                    return byKey;
                }
                return Integer.compare(right.getVersion(), left.getVersion());
            }
        };
    }

    private int compareKey(WfDeploymentEntry left, WfDeploymentEntry right) {
        String leftKey = left.getKey() == null ? "" : left.getKey();
        String rightKey = right.getKey() == null ? "" : right.getKey();
        return leftKey.compareTo(rightKey);
    }

    /**
     * 时间为 null 的排在<b>最后</b>（无论正序倒序）。
     *
     * <p>刻意不依赖数据库的 NULL 序：H2 / MySQL / PostgreSQL 在这一点上结论相反，
     * 而本仓判据有一半跑在 H2 上 —— 依赖它就等于「开发期看着对、换库就乱序」。
     */
    private int compareTime(WfDeploymentEntry left, WfDeploymentEntry right) {
        Date leftTime = left.getDeployTime();
        Date rightTime = right.getDeployTime();
        if (leftTime == null && rightTime == null) {
            return 0;
        }
        if (leftTime == null) {
            return 1;
        }
        if (rightTime == null) {
            return -1;
        }
        return leftTime.compareTo(rightTime);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
