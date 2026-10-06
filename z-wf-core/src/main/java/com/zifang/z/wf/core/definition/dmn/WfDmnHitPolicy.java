package com.zifang.z.wf.core.definition.dmn;

/**
 * DMN 决策表的命中策略。
 *
 * <p>它回答的是"**多条规则同时成立时，结果算哪一个**"—— 这正是决策表存在的理由：
 * 规则一旦多了，重叠是常态而不是意外。
 *
 * <p>分两大类。{@link #single()} 为真的是单结果策略（最多返回一条），
 * 否则是多结果策略（返回全部命中）。
 *
 * <p>{@link #UNIQUE} 是 DMN 的默认值 —— {@code hitPolicy} 属性不写时按它处理。
 * 这里<b>不留 null</b>：解析期就把缺省补上，运行期与校验期看到的都是确定值。
 */
public enum WfDmnHitPolicy {

    /** 至多一条命中；命中多条 ⇒ 违反（表本身写错了，不是调用方的输入问题）。 */
    UNIQUE("U", true),

    /** 可命中多条，但<b>所有命中规则的输出必须完全相同</b>；不同 ⇒ 违反。 */
    ANY("A", true),

    /** 可命中多条，返回<b>表内第一条</b>。规则顺序因此是契约的一部分。 */
    FIRST("F", true),

    /**
     * 按输出值的优先级返回：把全部命中规则产出的值合并，**按该输出
     * {@code outputValues} 列表的次序排序**（越靠前优先级越高），并去重。
     *
     * <p><b>它是多结果策略，与另外五种都不同</b>：其余五种选的是「哪几条规则命中」，
     * 而它选的是「输出值之间的先后」—— 所以结果条数由 {@code outputValues}
     * 的长度决定，与命中几条规则无关。把它当成单结果，会在"三条规则命中、
     * 两个不同输出值"时把本该并存的两个值砍成一个。
     */
    OUTPUT_PRIORITY("P", false),

    /** 返回全部命中，按<b>表内规则顺序</b>。 */
    RULE_ORDER("R", false),

    /** 返回全部命中，<b>顺序不保证</b>；带聚合器时压成一个值。 */
    COLLECT("C", false);

    private final String symbol;
    private final boolean single;

    WfDmnHitPolicy(String symbol, boolean single) {
        this.symbol = symbol;
        this.single = single;
    }

    /** 建模工具在表格左上角显示的符号（U / A / F / P / R / C）。 */
    public String symbol() {
        return symbol;
    }

    /**
     * 是否为<b>单结果</b>策略。
     *
     * <p>它决定两件事：违反规则怎么报（UNIQUE / ANY 违反要抛，FIRST 不算违反），
     * 以及调用方该调 {@code singleOutput()} 还是 {@code outputs()}。
     * 这两件事必须由<b>策略自己</b>回答 —— 让调用方拿"结果条数 > 1"去反推，
     * 就会出现"COLLECT 命中两条时该不算违反"被误判成违反。
     */
    public boolean isSingle() {
        return single;
    }

    /**
     * 宽松解析：接受全名（{@code UNIQUE}）与符号（{@code U}），大小写不敏感。
     *
     * @return 识别成功返回策略；未识别返回 {@code null}（由调用方决定报错还是取默认）
     */
    public static WfDmnHitPolicy parse(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toUpperCase();
        for (WfDmnHitPolicy policy : values()) {
            if (policy.name().equals(key) || policy.symbol.equals(key)) {
                return policy;
            }
        }
        return null;
    }
}