package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.persistence.WfExecutionQuery;
import com.zifang.z.wf.core.persistence.WfPersistence;

/**
 * 执行令牌的条件查询（Camunda {@code createExecutionQuery} 的对应物）。
 *
 * <p>回答排障的第一问：<b>「哪条 token 停在哪」</b>。
 * 现有的 {@code getExecutions(processInstanceId)} 必须先知道流程实例 id，
 * 而"哪个单子卡在审批节点上"这句话里此刻还只有节点、没有单子。
 *
 * <p><b>为什么过滤、排序、分页、上限全在这一层，而不是下推到存储层。</b>
 * 变量条件（{@code VARIABLES} 是 JSON 文本列）在各库上无法用同一段 SQL 表达 ——
 * H2 / MySQL / PostgreSQL 对 JSON 函数的支持与语义各不相同，跨库一致只能回到 Java 里比。
 * 而过滤一旦发生在分页**之后**，把分页交给 SQL 就是错的：先 LIMIT 50 再过滤，
 * 可能只剩 3 条也可能一条不剩，而调用方会以为"就这些了"。
 * 静默截断一份看起来完整的清单，比直接报错坏得多。
 *
 * <p>⇒ 一次查询里只有"能下推的那几项"下推（流程实例 / 节点 / 状态），
 * 剩下的在这里做，<b>只有这一份实现</b>，所以两套存储不可能在这中间分家。
 *
 * <p>排序也在这里做，同一个理由：{@code ENTERED_TIME} 可空，
 * 而"NULL 排前还是排后"在不同数据库上结论相反。
 * 存储层只按主键倒序保证页内稳定，语义排序由这里统一。
 *
 * @author zifang
 */
public class WfExecutionQueryService {

    private static final Logger log = LoggerFactory.getLogger(WfExecutionQueryService.class);

    /**
     * 单次查询最多读多少条原始行。
     *
     * <p>超了直接报错而不是截断：排障的人拿到一份"看起来完整"的短列表时，
     * 会照着它得出"只有这几条有问题"的结论，而真相是还有几千条没被读到。
     * 与 {@code WfSubscriptionService#MAX_SCAN} 同一套取值的理由。
     */
    public static final int MAX_SCAN = 2000;

    private final WfPersistence persistence;

    public WfExecutionQueryService(WfPersistence persistence) {
        this.persistence = persistence;
    }

    /**
     * 查令牌（已按 {@code pageNum/pageSize} 截断）。
     */
    public List<WfExecution> listExecutions(WfExecutionQuery query) {
        List<WfExecution> matched = scan(query);
        WfExecutionQuery actual = query == null ? new WfExecutionQuery() : query;
        int from = (actual.normalizedPageNum() - 1) * actual.normalizedPageSize();
        if (from >= matched.size()) {
            return new ArrayList<>();
        }
        int to = Math.min(matched.size(), from + actual.normalizedPageSize());
        return new ArrayList<>(matched.subList(from, to));
    }

    /**
     * 匹配总数（不分页）。
     *
     * <p>与 {@link #listExecutions} <b>共用同一次扫描</b>，没有单独走 SQL 的 {@code COUNT(*)} ——
     * 变量条件在 Java 里过滤而计数下推到 SQL，两者对不上：
     * 列表显示 3 条而 count 说 8 条时，调用方只会以为自己算错了。
     * 分开算还有第二个问题：两次查询之间流程又推进了，数字本来也对不上。
     */
    public int countExecutions(WfExecutionQuery query) {
        return scan(query).size();
    }

    /**
     * 某个流程实例当前还没结束的令牌 —— 回答「这条单子现在停在哪」。
     *
     * <p>刻意不走 {@link #listExecutions}：那条按页大小截断（上限 1000），
     * 而"这条单子有几个分支"没有翻页的必要、也不该被悄悄截断成 1000 ——
     * 宁可撞上 {@link #MAX_SCAN} 报一个"这个单子的分支数不正常"的错。
     */
    public List<WfExecution> unfinishedOf(String processInstanceId) {
        return scan(new WfExecutionQuery()
                .setProcessInstanceId(processInstanceId)
                .onlyUnfinished()
                .setPageSize(MAX_SCAN + 1));
    }

    // ==================== 扫描 ====================

    /**
     * 一次扫描：下推条件 → 读够 → 判溢出 → 变量过滤 → 语义排序。
     *
     * <p>「读够」是刻意传一个很大的 pageSize 给存储层：过滤在后面做，
     * 先按调用方要的那一页截断，会把命中的那几条切在页外 ——
     * 表现是「明明有 3 条符合，翻到第一页却只有 1 条」。
     */
    private List<WfExecution> scan(WfExecutionQuery query) {
        WfExecutionQuery actual = query == null ? new WfExecutionQuery() : query;
        // **在副本上**改分页：actual 是调用方传进来的那个对象，
        // 就地改会让它之后再拿去翻页时读到被改过的参数（详见 WfExecutionQuery#copy）。
        WfExecutionQuery fetch = actual.copy().setPageNum(1).setPageSize(MAX_SCAN + 1);
        List<WfExecution> fetched = persistence.queryExecutions(fetch);
        if (fetched == null || fetched.isEmpty()) {
            return new ArrayList<>();
        }
        // 判溢出用**读到的原始行数**，不是过滤后的条数 ——
        // 拿过滤后的数去判，恰好赶上"大半被变量条件滤掉"时会误以为没超量。
        // 判据是"还可能没读完"这件事本身，与之后怎么过滤无关。
        if (fetched.size() > MAX_SCAN) {
            throw new WfEngineException("匹配的令牌超过 " + MAX_SCAN
                    + " 条，没有读完。缩小范围（比如限定流程实例或节点）再查。"
                    + "给一份截断的清单比报错更坏：它看起来是完整的。");
        }
        List<WfExecution> matched = new ArrayList<>();
        for (WfExecution execution : fetched) {
            if (matches(execution, actual)) {
                matched.add(execution);
            }
        }
        sortForDisplay(matched);
        return matched;
    }

    /**
     * 变量条件（与存储层下推的那几项分开，只有这里做这一件事）。
     *
     * <h3>⚠️ 比较用 {@code String.valueOf}，所以这一支是「类型宽松」的（第 43 轮更正）</h3>
     * 实现落在 {@code Objects.equals(String.valueOf(value), wanted)} 上，
     * 于是数字 {@code 1} 与字符串 {@code "1"} <b>判相等</b>。
     * 本方法原先的注释写着「不做类型宽松（{@code 1} 与 {@code "1"} 判不等）」——
     * 那句话与紧跟着的实现<b>正好相反</b>，而且被流程级
     * {@code WfProcessQueryService} 照抄了一遍。
     *
     * <p><b>行为是对的，错的是注释</b>，所以这里只改注释、不动实现：
     * 审批金额在表单里进来是字符串 {@code "5000"}，另一条路径可能存成数字
     * {@code 5000}；按类型严格比，{@code =5000} 会查不到字符串那一批
     * <b>而且不报任何错</b>，调用方只看到"就是没有"。
     * 「查不出来」比「多查出几条形态相同的」危险得多。
     * 流程级那边的 {@code WfProcessQueryService#matches} 与此保持同一把尺子。
     * （写纯文本而不是 {@code @link}：{@code matches} 是 private，链接过去是坏链。）
     *
     * <p><b>键不存在不等于「值为 null」</b>：先 {@code containsKey} 再比，
     * 否则一条没有 {@code approveFlag} 的 token 会被 {@code approveFlag=null} 这个条件命中。
     */
    private boolean matches(WfExecution execution, WfExecutionQuery query) {
        String name = query.getVariableName();
        if (name == null || name.trim().isEmpty()) {
            return true;
        }
        String wanted = query.getVariableValueEquals();
        if (wanted == null) {
            return true;
        }
        Map<String, Object> variables = execution.getVariables();
        if (variables == null || !variables.containsKey(name.trim())) {
            return false;
        }
        return Objects.equals(String.valueOf(variables.get(name.trim())), wanted);
    }

    /**
     * 按进入时间倒序（最新进入的节点在前 —— 排障先看最新），空值排最后。
     *
     * <p>时间相同按 id 倒序兜底：id 里带进程随机数，<b>跨实例不可比</b>
     * （两个进程各写一批，字典序与时间序无关），所以它只用来在同一时刻的若干条之间
     * 给出稳定顺序、让翻页不重不漏，不承担"更晚的排前面"的责任。
     */
    private void sortForDisplay(List<WfExecution> list) {
        java.util.Collections.sort(list, new Comparator<WfExecution>() {
            @Override
            public int compare(WfExecution left, WfExecution right) {
                Date leftTime = left.getEnteredTime();
                Date rightTime = right.getEnteredTime();
                if (leftTime == null || rightTime == null) {
                    if (leftTime == null && rightTime == null) {
                        return idDesc(left, right);
                    }
                    // 没进入时间的排最后：它要么是刚建出来还没落时间、
                    // 要么是从别处灌进来的脏数据，两种都不该被当成"最新"
                    return leftTime == null ? 1 : -1;
                }
                int cmp = rightTime.compareTo(leftTime);
                return cmp != 0 ? cmp : idDesc(left, right);
            }

            private int idDesc(WfExecution left, WfExecution right) {
                String leftId = left.getId() == null ? "" : left.getId();
                String rightId = right.getId() == null ? "" : right.getId();
                return rightId.compareTo(leftId);
            }
        });
    }

}
