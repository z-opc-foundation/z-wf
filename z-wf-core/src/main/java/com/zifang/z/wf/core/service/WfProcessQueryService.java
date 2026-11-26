package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.persistence.WfProcessInstanceQuery;

/**
 * 按变量值查流程实例（第 43 轮）。
 *
 * <p>对应 Camunda {@code ProcessInstanceQuery} 上的
 * {@code variableValueEquals} / {@code variableValueGreaterThan} /
 * {@code variableValueLessThan} / {@code processVariableNames}。
 * 它填的是台账上写着「<b>审批系统常用</b>」的那一格：
 * <b>"找出金额超过 5000 的在途单"</b>、<b>"哪些单子还没填金额"</b>。
 *
 * <h3>为什么这一组不进存储层</h3>
 * 流程变量存在 {@code ZWF_PROCESS.VARIABLES} 这个 <b>JSON 文本列</b>里，
 * 而各库对 JSON 函数的支持与语义都不一样（H2 与 PostgreSQL 能用不同写法，
 * MySQL 又是另一套）。要跨库行为一致就只能回到 Java 里比 ——
 * 与 {@code WfExecutionQueryService}（token 侧）完全同构，见那里的注释。
 *
 * <p><b>更不能拿 {@code LIKE '"amount":100'} 糊弄过去</b>：那样
 * {@code "amount":1000} 会被 {@code "amount":100} 的条件命中，
 * 而屏幕上看起来就是一个正常的过滤 ——
 * 错的是数据，查不出来。与其给一个会骗人的快路径，不如慢一点但对。
 *
 * <h3>为什么分页也必须回到 Java</h3>
 * 过滤发生在分页<b>之后</b>时，把分页交给 SQL 就是错的：
 * 先 {@code LIMIT 20} 再过滤，命中的那几条可能被切在页外 ——
 * 现象是「明明有 3 条符合，翻到第一页却只有 1 条」，
 * 而且分页器算出来的总页数是错的。
 *
 * <h3>慢路径只影响真的用了变量条件的查询</h3>
 * {@link #list} / {@link #count} 的第一件事就是看
 * {@link WfProcessInstanceQuery#hasVariableCondition()}：
 * 没有就把查询<b>原样</b>交给存储层（分页下推 SQL，行为与本特性之前完全一致）。
 * ⇒ 默认路径一行不改，慢路径只在真的用了变量条件时才走。
 *
 * @author zifang
 */
public class WfProcessQueryService {

    private static final Logger log = LoggerFactory.getLogger(WfProcessQueryService.class);

    /**
     * 变量查询一次最多读多少行。
     *
     * <p>比 {@link WfExecutionQueryService#MAX_SCAN} 大一个量级：那边扫的是
     * <b>一条单子的分支</b>（几十条），这边的 {@code ZWF_PROCESS} 是
     * <b>整个系统的单量</b>（审批系统里最坏情况十万级）。
     *
     * <p>超了怎么办：<b>报错，不截断</b>。给一份缺了行的清单比报错更坏 ——
     * 它看起来是完整的，而"第 7 页没有更多了"与"还有，只是没读到"在屏幕上没有区别。
     * 报错的文案要直接告诉人怎么缩小范围。
     */
    public static final int MAX_SCAN = 20000;

    private final WfPersistence persistence;

    /** 实际生效的扫描上限（构造参数没给时是 {@link #MAX_SCAN}）。 */
    private final int maxScan;

    public WfProcessQueryService(WfPersistence persistence) {
        this(persistence, MAX_SCAN);
    }

    /**
     * 换掉扫描上限。
     *
     * <p><b>存在是为了让那道溢出闸门可被验证</b>：造出两万多条流程实例只为证明
     * "超了会报错"，代价是一次几分钟的测试；而闸门恰恰是这条路上最要紧的一条 ——
     * 它一旦失效，返回的就是一份看起来完整的截断清单。
     * 同时它也确实该可配：不同体量的系统对"一次允许扫多少"合理值不一样。
     *
     * @param maxScan 上限，必须大于 0；{@code <= 0} 视为 {@link #MAX_SCAN}
     */
    public WfProcessQueryService(WfPersistence persistence, int maxScan) {
        this.persistence = persistence;
        this.maxScan = maxScan <= 0 ? MAX_SCAN : maxScan;
    }

    /** 查一页。{@code total} 请走 {@link #count}，两者必须用同一把尺子。 */
    public List<WfProcessInstance> list(WfProcessInstanceQuery query) {
        WfProcessInstanceQuery actual = query == null ? new WfProcessInstanceQuery() : query;
        actual.assertConsistent();
        if (!actual.hasVariableCondition()) {
            // **快路径一行不改**：没有变量条件就原路下推，SQL 分页照旧
            return persistence.queryProcessInstances(actual);
        }
        List<WfProcessInstance> matched = filter(scan(actual), actual);
        int offset = actual.getOffset();
        if (offset >= matched.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(matched.subList(offset,
                Math.min(offset + actual.getPageSize(), matched.size())));
    }

    /**
     * 命中总数（不分页）。
     *
     * <p><b>与 {@link #list} 走的是同一条过滤路径</b> —— 两边各算各的话，
     * 分页器的 total 与 records 对不上，而那个错在界面上表现为
     * "总共 128 条，翻到第 5 页却是空的"。
     */
    public long count(WfProcessInstanceQuery query) {
        WfProcessInstanceQuery actual = query == null ? new WfProcessInstanceQuery() : query;
        actual.assertConsistent();
        if (!actual.hasVariableCondition()) {
            return persistence.countProcessInstances(actual);
        }
        return filter(scan(actual), actual).size();
    }

    /**
     * 读一遍：其余条件下推 → 读够 → 判溢出 → 按 startTime 倒序。
     *
     * <p>「读够」是刻意传一个很大的 pageSize 给存储层：过滤在后面做，
     * 先按调用方要的那一页截断会把命中的那几条切在页外（见类注释）。
     *
     * <p>判溢出用<b>读到的原始行数</b>，不是过滤后的条数 ——
     * 拿过滤后的数去判，恰好赶上"大半被变量条件滤掉"时会误以为没超量。
     */
    private List<WfProcessInstance> scan(WfProcessInstanceQuery actual) {
        WfProcessInstanceQuery fetch = actual.copy().setPageNum(1).setPageSize(maxScan + 1);
        // **先把变量条件摘掉**：它们不是存储层的过滤条件，
        // 留着的话"读到的行数"就变成了"读到的命中行数"，
        // 溢出判断会拿一个已经被滤过的数去比 —— 恰好赶上"大半不命中"时反而误判没超量
        fetch.clearVariableConditions();
        List<WfProcessInstance> fetched = persistence.queryProcessInstances(fetch);
        if (fetched == null || fetched.isEmpty()) {
            return new ArrayList<>();
        }
        if (fetched.size() > maxScan) {
            throw new WfEngineException("按变量查流程实例时匹配到的单超过 " + maxScan
                    + " 条，没有读完。缩小范围（限定 definitionKey、businessKey "
                    + "或加 startTimeFrom/To）再查。"
                    + "给一份截断的清单比报错更坏：它看起来是完整的。");
        }
        sortForDisplay(fetched);
        return fetched;
    }

    /** 变量条件：<b>只有这里做这一件事</b>（存储层那侧刻意不含变量条件）。 */
    private List<WfProcessInstance> filter(List<WfProcessInstance> fetched,
                                           WfProcessInstanceQuery query) {
        List<WfProcessInstance> matched = new ArrayList<>();
        for (WfProcessInstance instance : fetched) {
            if (matches(instance, query)) {
                matched.add(instance);
            }
        }
        return matched;
    }

    /**
     * 判定一条实例是否命中变量条件。
     *
     * <p>四条规矩，每条都和 {@code WfExecutionQueryService#matches} 同一把尺子：
     * <ul>
     *   <li><b>键不存在一律不匹配</b>，连"存在性"这一支也是 ——
     *       先 {@code containsKey} 再比，否则一条没设过 {@code approveFlag} 的单子
     *       会被 {@code =null} 命中。</li>
     *   <li><b>等值按 {@code String.valueOf} 的字符串形态比</b>，
     *       所以数字 {@code 100} 与字符串 {@code "100"} <b>判相等</b> ——
     *       <b>这一支就是宽松的</b>，且是有意的：审批金额在表单里进来是字符串，
     *       按类型严格比会让 {@code =5000} 查不到一批单子而且不报错。
     *       注意它<b>不是</b>「严格」：本方法早先的注释照抄了 token 侧
     *       「{@code 1} 与 {@code "1"} 判不等」那句，而那正是错的（第 43 轮改正）。
     *       真正严格的是下面这一支：数值比较要 {@code parseDouble}，
     *       <b>所以 {@code >} / {@code <} / 区间与等值走的不是同一把尺子</b>。</li>
     *   <li><b>数值比较遇到非数值一律不匹配，不抛异常</b>：变量里可能有
     *       {@code "待定"}、{@code null}、别的对象。让"查 5000 以上的单"因为某个
     *       单的金额填成了文字就整个查询失败，比查不全更糟 ——
     *       而漏掉的那一条，本该由业务的填单校验去管，不该由查询去拦。</li>
     *   <li><b>存在性是 {@code comparison == null} 的那一支</b>，
     *       不是一个独立的开关 —— 那样会让"既问存在又比值"变成第三种组合。</li>
     * </ul>
     */
    private boolean matches(WfProcessInstance instance, WfProcessInstanceQuery query) {
        String name = query.getVariableName();
        if (name == null) {
            return true;
        }
        Map<String, Object> variables = instance.getVariables();
        if (variables == null || !variables.containsKey(name)) {
            return false;
        }
        WfProcessInstanceQuery.VariableComparison comparison = query.getComparison();
        if (comparison == null) {
            return true;
        }
        Object value = variables.get(name);
        if (comparison == WfProcessInstanceQuery.VariableComparison.EQUALS) {
            return String.valueOf(value).equals(query.getEqualsValue());
        }
        Double actual = toNumber(value);
        if (actual == null) {
            return false;
        }
        switch (comparison) {
            case GREATER_THAN:
                return actual.doubleValue() > query.getNumberFrom().doubleValue();
            case LESS_THAN:
                return actual.doubleValue() < query.getNumberTo().doubleValue();
            case BETWEEN:
                return actual.doubleValue() >= query.getNumberFrom().doubleValue()
                        && actual.doubleValue() <= query.getNumberTo().doubleValue();
            default:
                return false;
        }
    }

    /**
     * 转数字。
     *
     * <p>{@code Integer} / {@code Long} / {@code BigDecimal} 这类 {@link Number}
     * 直接取；字符串先试 {@code parseDouble}。
     * <b>不接受的形态一律返回 {@code null}（不匹配），不抛异常</b>，理由见
     * {@link #matches} 的第三条。
     */
    private Double toNumber(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return Double.valueOf(((Number) value).doubleValue());
        }
        if (value instanceof Boolean) {
            // 布尔当数字是一种"帮用户猜"，而 amount=true 这种数据本身就说明填错了，
            // 猜出来的排序没有任何业务含义
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(Double.parseDouble(text));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 按起始时间倒序（最新在前），空值排最后。
     *
     * <p><b>必须与存储层的排序给出同一个顺序</b>：慢路径读完是在 Java 里排的，
     * 排序规则一旦与 {@code sortInstances} 不同，
     * 同一个查询翻两页就会看到重复或漏掉的单子。
     */
    private void sortForDisplay(List<WfProcessInstance> list) {
        Collections.sort(list, new Comparator<WfProcessInstance>() {
            @Override
            public int compare(WfProcessInstance a, WfProcessInstance b) {
                Date ta = a.getStartTime();
                Date tb = b.getStartTime();
                if (ta == null && tb == null) {
                    return 0;
                }
                if (ta == null) {
                    return 1;
                }
                if (tb == null) {
                    return -1;
                }
                return tb.compareTo(ta);
            }
        });
    }
}