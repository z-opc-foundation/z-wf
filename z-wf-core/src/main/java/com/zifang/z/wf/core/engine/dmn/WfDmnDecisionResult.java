package com.zifang.z.wf.core.engine.dmn;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.zifang.z.wf.core.definition.dmn.WfDmnHitPolicy;

/**
 * 一次决策求值的结果。
 *
 * <p><b>「命中了 0 条」与「命中了但输出为空」是两件事</b>，所以命中条数单独给一个入口：
 * 前者通常意味着<b>输入给错了</b>（没有一条规则的条件成立），
 * 而后者意味着<b>输出项写漏了</b>。两者如果都表现成"结果是个空 Map"，
 * 排障的人就只能挨个去猜是哪一种。
 *
 * @author zifang
 */
public class WfDmnDecisionResult {

    private final String decisionKey;
    private final WfDmnHitPolicy hitPolicy;
    private final List<Row> rows;
    private final int matchedRuleCount;

    public WfDmnDecisionResult(String decisionKey, WfDmnHitPolicy hitPolicy,
                               List<Row> rows, int matchedRuleCount) {
        this.decisionKey = decisionKey;
        this.hitPolicy = hitPolicy;
        this.rows = rows == null ? new ArrayList<Row>() : rows;
        this.matchedRuleCount = matchedRuleCount;
    }

    public String getDecisionKey() {
        return decisionKey;
    }

    public WfDmnHitPolicy getHitPolicy() {
        return hitPolicy;
    }

    /** 命中的规则条数 —— 折成结果之前的原始命中数（COLLECT 聚合后结果可能只剩一条）。 */
    public int getMatchedRuleCount() {
        return matchedRuleCount;
    }

    /** 折成结果之后的输出行。多结果策略下可能多于一条。 */
    public List<Row> getRows() {
        return rows;
    }

    /** 没有任何规则命中。调用方据此区分「输入不对」与「输出写漏了」。 */
    public boolean isNoMatch() {
        return matchedRuleCount == 0;
    }

    /**
     * 单结果输出 —— 给 {@link WfDmnHitPolicy#isSingle()} 为真的策略用。
     *
     * <p>结果多于一条时<b>抛异常而不是返回第一条</b>：那说明调用方按单结果用的，
     * 而这张表是多结果的。返回第一条会让它拿到一个看起来正常的值，
     * 而这个值随命中顺序变 —— 症状是"同一类单据两次算出不同结果"。
     *
     * @return 命中时是输出名 → 值；一条都没命中时是空 Map
     */
    public Map<String, Object> singleOutput() {
        if (rows.size() > 1) {
            throw new IllegalStateException("决策 [" + decisionKey + "] 命中 "
                    + matchedRuleCount + " 条规则并产生了 " + rows.size()
                    + " 行结果（策略 " + hitPolicy + "），不能按单结果读。"
                    + "要拿全部结果请改用 getRows()");
        }
        return rows.isEmpty() ? new LinkedHashMap<String, Object>() : rows.get(0).getOutputs();
    }

    @Override
    public String toString() {
        return "WfDmnDecisionResult{key=" + decisionKey + ", hitPolicy=" + hitPolicy
                + ", matched=" + matchedRuleCount + ", rows=" + rows + "}";
    }

    /**
     * 一行决策结果：输出名 → 值。
     *
     * <p>值可能是标量，也可能是 {@link List}（命中值落在该输出的
     * {@code outputValues} 列表里时会展开成整个列表）——
     * 这不是实现的偷懒，是 DMN 语义本身如此，所以<b>调用方必须能处理两种形状</b>。
     *
     * <p>展开后的多值另存一份：{@code OUTPUT_PRIORITY} 要按它去 {@code outputValues}
     * 里查优先级、{@code COLLECT} 的聚合器要对它做 SUM / MIN / MAX。
     * 只保留"对外形状"那一份的话，这两处都得先把列表重新拆出来才能用。
     */
    public static class Row implements Serializable {

        private static final long serialVersionUID = 1L;

        private final Map<String, Object> outputs = new LinkedHashMap<>();

        /** 展开后的多值，供 OUTPUT_PRIORITY 与聚合器使用。 */
        private final Map<String, List<Object>> values = new LinkedHashMap<>();

        public Map<String, Object> getOutputs() {
            return outputs;
        }

        /**
         * 记一个输出列。
         *
         * @param primary  对外暴露的形状（标量，或展开后的列表）
         * @param expanded 展开后的全部值（标量会被包成单元素列表）
         */
        void record(String outputName, Object primary, List<Object> expanded) {
            outputs.put(outputName, primary);
            values.put(outputName, expanded);
        }

        /** 该输出列上的全部值（标量也会被包成单元素列表）。 */
        public List<Object> valuesOf(String outputName) {
            List<Object> found = values.get(outputName);
            if (found != null) {
                return found;
            }
            Object single = outputs.get(outputName);
            List<Object> wrapped = new ArrayList<>();
            if (single != null) {
                wrapped.add(single);
            }
            return wrapped;
        }

        @Override
        public String toString() {
            return outputs.toString();
        }
    }
}