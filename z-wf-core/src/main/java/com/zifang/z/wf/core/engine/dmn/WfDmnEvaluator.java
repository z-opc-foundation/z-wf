package com.zifang.z.wf.core.engine.dmn;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.zifang.z.wf.core.definition.dmn.WfDmnDecision;
import com.zifang.z.wf.core.definition.dmn.WfDmnHitPolicy;
import com.zifang.z.wf.core.engine.expression.WfExpressionEvaluator;

/**
 * 决策表求值器 —— 六种命中策略的唯一实现点。
 *
 * <p><b>为什么规则本身也在这份代码里判</b>：命中与否不是"表"的属性而是"表 + 输入"的
 * 属性，把 {@code matches} 与 {@code applyHitPolicy} 拆成两处看着更干净，
 * 但那样"命中集合"就成了一个可以被两处各改一次的中间量。
 *
 * <p><b>三处不静默的地方</b>（决策表一旦算错，错的是业务决策，不是流程走向，
 * 而错得毫无迹象 —— 所以宁可停下也不猜）：
 * <ol>
 *   <li>输入项引用了未定义变量 ⇒ 该规则<b>不命中</b>。这与本仓条件求值
 *       「fail-closed」的既有约定同源：{@code WfExpressionEvaluator} 对
 *       未定义变量已经判 false，沿用它才能保证"同一条判断在网关和决策表里结论一致"。
 *       记日志，因为"某条规则因为变量没传而不命中"是排障时最该知道的一件事。</li>
 *   <li>输出项求不出值 ⇒ <b>抛异常</b>。输入项算错还可以说"那列不成立"，
 *       输出项算错却会得到一个缺项的结果，而调用方通常<b>不会检查缺不缺</b> ——
 *       它拿到的就是"决策结果"，里面少了一栏。</li>
 *   <li>UNIQUE 命中多条 / ANY 命中结果不一致 ⇒ <b>抛异常</b>。
 *       这是表本身写错了（作者以为规则不重叠），而调用方无法从结果里看出这一点。</li>
 * </ol>
 *
 * @author zifang
 */
public class WfDmnEvaluator {

    private final WfExpressionEvaluator expressionEvaluator;

    public WfDmnEvaluator() {
        this(new WfExpressionEvaluator());
    }

    public WfDmnEvaluator(WfExpressionEvaluator expressionEvaluator) {
        this.expressionEvaluator = expressionEvaluator;
    }

    /**
     * 求值一张决策表。
     *
     * @param table     决策表
     * @param variables 输入变量（通常是流程实例变量）
     * @param key       决策 key，只用于报错信息
     * @throws WfDmnViolationException 命中策略被违反，或输出项求不出值
     */
    public WfDmnDecisionResult evaluate(WfDmnDecision.WfDmnTable table,
                                        Map<String, Object> variables, String key) {
        WfDmnHitPolicy policy = table.getHitPolicy();
        List<WfDmnDecision.WfDmnRule> hits = new ArrayList<>();
        for (WfDmnDecision.WfDmnRule rule : table.getRules()) {
            if (matches(rule, table, variables)) {
                hits.add(rule);
            }
        }
        return new WfDmnDecisionResult(key, policy, outputs(table, hits), hits.size());
    }

    /**
     * 一条规则是否命中 —— 它的所有输入项都成立。
     *
     * <p>输入项数量与输入列数量不一致时<b>判不命中并报错</b>，不按"有的比有的多"
     * 处理：那是建表时列错了，而现在放行的话，这条规则会依据<b>前几列</b>命中，
     * 后面的列形同虚设 —— 一条看起来有 4 个条件的规则实际只用了 2 个，
     * 而表上完全看不出来。
     */
    private boolean matches(WfDmnDecision.WfDmnRule rule, WfDmnDecision.WfDmnTable table,
                            Map<String, Object> variables) {
        List<String> entries = rule.getInputEntries();
        List<String> inputs = table.getInputExpressions();
        if (entries.size() != inputs.size()) {
            throw new WfDmnViolationException("规则有 " + entries.size() + " 个输入项，"
                    + "而表里有 " + inputs.size() + " 个输入列，两者必须一一对应。"
                    + "数量不符时若按「有的比有的多」处理，这条规则会只依据前几列命中，"
                    + "后面的列形同虚设 —— 一条看起来有 4 个条件的规则实际只用了 2 个");
        }
        // 每个输入列先把自己的表达式求一次值，后面所有规则共用 ——
        // 不共用的话，同一列要在"规则数 × 列数"次里重复求值同一个变量，
        // 而中间若变量被表达式改掉（rare，但有），同一列在不同规则里会看到不同的值。
        List<Object> inputValues = new ArrayList<>();
        for (String expression : inputs) {
            inputValues.add(expressionEvaluator.evalRaw(expression, variables));
        }
        for (int i = 0; i < entries.size(); i++) {
            if (!entryHolds(entries.get(i), inputValues.get(i), variables)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 一个输入项是否成立。
     *
     * <p><b>输入项是"单目测试"（unary test），不是一个完整表达式。</b>
     * 这是决策表最容易被写错的一处：{@code <text>&gt; 5000</text>} 缺了左操作数，
     * 它不是"求值一个叫 {@code > 5000} 的表达式"，而是
     * <b>"这一列的值 &gt; 5000"</b> —— 左操作数隐含为该列 {@code inputExpression}
     * 的求值结果（{@code amount}）。
     *
     * <p>所以这里把测试<b>补全成完整表达式</b>再交给求值器：
     * 以比较符开头就补成 {@code (输入值) > 5000}，
     * 否则（纯值或逗号列表）补成 {@code (输入值) == "x"} —— 后者正是 DMN
     * 「隐含等值」的写法，建模工具导出的文件里到处都是 {@code == "SILVER"} 这种形状。
     *
     * <p>直接拿 {@code > 5000} 去求值会得到「没有左操作数」，
     * 而本仓的 EL 对那种输入<b>不抛异常、只是求不出值</b> ——
     * 于是<b>每一条规则都不命中</b>，决策结果永远是空，且没有任何报错。
     * 这不是"边界情况退化"，是这张表彻底失效。
     *
     * @param inputValue 该列 {@code inputExpression} 的求值结果；为 {@code null}
     *                   表示这一列取不到值（变量没传）
     */
    private boolean entryHolds(String entry, Object inputValue, Map<String, Object> variables) {
        String test = entry == null ? "" : entry.trim();
        if (test.isEmpty() || "-".equals(test)) {
            return true;
        }
        // 输入列取不到值 ⇒ 判不成立（fail-closed）。
        // 当成"成立"会把 amount 未传的单子当成金额为 0 的小额单，
        // 而那是与网关条件求值相反的结论（那边判 false）。
        if (inputValue == null) {
            return false;
        }
        String expression = unaryTest(test, inputValue);
        return expressionEvaluator.evaluate(expression, variables);
    }

    /** 比较符：{@code <=} / {@code >=} 必须在 {@code <} / {@code >} 之前匹配。 */
    private static final java.util.regex.Pattern LEADING_COMPARATOR =
            java.util.regex.Pattern.compile("^\\s*(<=|>=|==|!=|<|>)");

    /**
     * 把单目测试补成完整表达式。
     *
     * <p>输入值渲染成<b>字面量</b>而不是变量名：变量名在这一步已经被求值掉了，
     * 再写回变量名会让同一列在不同规则里重新读一次变量，
     * 而两次之间若变量变了，这条规则的判断就基于了一个与其它规则不同的值。
     *
     * <p>以比较符开头时<b>保留整个测试串</b>（比较符本身也要留），
     * 只在前面拼上输入值：{@code "> 100"} ⇒ {@code (200) > 100}。
     */
    private String unaryTest(String test, Object inputValue) {
        String literal = literalOf(inputValue);
        if (LEADING_COMPARATOR.matcher(test).find()) {
            return "(" + literal + ")" + test;
        }
        return "(" + literal + ") == " + test;
    }

    /** 变量值 → EL 字面量。字符串要带引号并转义，否则含空格的值会被当成多个 token。 */
    private String literalOf(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        String text = String.valueOf(value);
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * 把命中的规则按命中策略折成结果。
     *
     * <p>分派在这一处做，而不是让调用方拿"结果条数"去反推策略：
     * {@code COLLECT} 命中两条时结果本来就是两条，那是正常结果不是违反；
     * 用条数判断会把它误报成冲突。
     */
    private List<WfDmnDecisionResult.Row> outputs(WfDmnDecision.WfDmnTable table,
                                                  List<WfDmnDecision.WfDmnRule> hits) {
        WfDmnHitPolicy policy = table.getHitPolicy();
        switch (policy) {
            case FIRST:
                return hits.isEmpty() ? new ArrayList<WfDmnDecisionResult.Row>()
                        : singleRow(table, hits.get(0));
            case UNIQUE:
                if (hits.size() > 1) {
                    throw new WfDmnViolationException("UNIQUE 策略下命中了 " + hits.size()
                            + " 条规则。UNIQUE 的前提是「规则不重叠」，"
                            + "现在这几条对同一组输入同时成立 —— "
                            + "结果取哪一条没有答案。按规则表逐条检查重叠的那些行");
                }
                return hits.isEmpty() ? new ArrayList<WfDmnDecisionResult.Row>()
                        : singleRow(table, hits.get(0));
            case ANY:
                return anyRows(table, hits);
            case RULE_ORDER:
                return allRows(table, hits, false);
            case COLLECT:
                return allRows(table, hits, true);
            case OUTPUT_PRIORITY:
                return priorityRows(table, hits);
            default:
                throw new WfDmnViolationException("未处理的命中策略: " + policy);
        }
    }

    /** UNIQUE / FIRST 命中一条：产出单行结果。 */
    private List<WfDmnDecisionResult.Row> singleRow(WfDmnDecision.WfDmnTable table,
                                                    WfDmnDecision.WfDmnRule hit) {
        List<WfDmnDecisionResult.Row> rows = new ArrayList<>();
        rows.add(row(table, hit));
        return rows;
    }

    /**
     * ANY：可以多条命中，但<b>所有命中的输出必须完全相同</b>。
     *
     * <p>ANY 的意图是"规则可以重叠，只要结论一致"。结论不一致时报错而不是取第一条 ——
     * 取第一条会让作者以为自己写对了，而不同输入下"取到哪条"会变，
     * 症状是同一类单据两次算出不同结果，且没有任何报错。
     */
    private List<WfDmnDecisionResult.Row> anyRows(WfDmnDecision.WfDmnTable table,
                                                  List<WfDmnDecision.WfDmnRule> hits) {
        List<WfDmnDecisionResult.Row> rows = new ArrayList<>();
        if (hits.isEmpty()) {
            return rows;
        }
        WfDmnDecisionResult.Row first = row(table, hits.get(0));
        for (int i = 1; i < hits.size(); i++) {
            WfDmnDecisionResult.Row other = row(table, hits.get(i));
            if (!sameOutputs(first, other)) {
                throw new WfDmnViolationException("ANY 策略下命中了 " + hits.size()
                        + " 条规则，而它们的输出不一致: " + first.getOutputs()
                        + " vs " + other.getOutputs()
                        + "。ANY 要求「规则可以重叠，但结论一致」");
            }
        }
        rows.add(first);
        return rows;
    }

    /**
     * RULE_ORDER / COLLECT：全部命中都进结果。
     *
     * <p>两条的唯一差别在<b>顺序是否有保证</b>，不在条数 —— 所以只落一个标记，
     * 不写两份几乎一样的循环。
     */
    private List<WfDmnDecisionResult.Row> allRows(WfDmnDecision.WfDmnTable table,
                                                  List<WfDmnDecision.WfDmnRule> hits,
                                                  boolean unordered) {
        List<WfDmnDecisionResult.Row> rows = new ArrayList<>();
        for (WfDmnDecision.WfDmnRule hit : hits) {
            rows.add(row(table, hit));
        }
        if (unordered) {
            rows = aggregate(table, rows);
        }
        return rows;
    }

    /**
     * OUTPUT_PRIORITY：把全部命中产出的值合并，<b>按 {@code outputValues} 的次序排序</b>。
     *
     * <p>它选的是<b>输出值之间的先后</b>，不是"哪条规则赢"，所以结果条数由
     * {@code outputValues} 的长度决定，与命中几条规则无关。
     *
     * <p><b>「按次序排序」不是「依次覆盖」</b>：前一版对 {@code outputValues} 里的
     * 每一项都往同一个 key 上写，于是<b>最后出现</b>的那个值赢 ——
     * {@code outputValues = ["b","a"]} 会返回 {@code a}，恰好与"b 优先"的意图相反。
     * 这个错很安静：结果仍是列表里的某一个值，只是优先级整个反了，且没有任何报错。
     *
     * <p>落在 {@code outputValues} 之外的值<b>追加在末尾</b>而不是丢掉 ——
     * 丢掉会让"输出值写错了"这件事完全不可见。
     */
    private List<WfDmnDecisionResult.Row> priorityRows(WfDmnDecision.WfDmnTable table,
                                                      List<WfDmnDecision.WfDmnRule> hits) {
        List<WfDmnDecisionResult.Row> rows = allRows(table, hits, false);
        if (rows.isEmpty() || table.getOutputs().isEmpty()) {
            return rows;
        }
        WfDmnDecision.WfDmnOutput output = table.getOutputs().get(0);
        String name = output.getName();
        List<String> order = output.getOutputValues();

        Set<String> seen = new LinkedHashSet<String>();
        List<Object> ordered = new ArrayList<>();
        for (String candidate : order) {
            for (WfDmnDecisionResult.Row row : rows) {
                for (Object value : row.valuesOf(name)) {
                    if (candidate.equals(String.valueOf(value)) && seen.add(candidate)) {
                        ordered.add(value);
                    }
                }
            }
        }
        // 没出现在 outputValues 里的值追加在末尾 —— 遍历**展开后的单值**，
        // 不能遍历 getOutputs()：那是对外形状，值落在 outputValues 里时它本身
        // 已经是一个列表，追加进去会得到 [[b, a], ...] 这种套娃。
        for (WfDmnDecisionResult.Row row : rows) {
            for (Object value : row.valuesOf(name)) {
                if (!seen.contains(String.valueOf(value))) {
                    seen.add(String.valueOf(value));
                    ordered.add(value);
                }
            }
        }
        if (ordered.isEmpty()) {
            return rows;
        }
        WfDmnDecisionResult.Row merged = new WfDmnDecisionResult.Row();
        merged.record(name, ordered, ordered);
        List<WfDmnDecisionResult.Row> result = new ArrayList<>();
        if (!merged.getOutputs().isEmpty()) {
            result.add(merged);
        }
        return result;
    }

    /**
     * COLLECT 的聚合器（SUM / MIN / MAX / COUNT）。
     *
     * <p>没有聚合器就是原样返回全部命中。
     */
    private List<WfDmnDecisionResult.Row> aggregate(WfDmnDecision.WfDmnTable table,
                                                   List<WfDmnDecisionResult.Row> rows) {
        String aggregator = table.getAggregator();
        if (aggregator == null || aggregator.trim().isEmpty() || rows.isEmpty()) {
            return rows;
        }
        String op = aggregator.trim().toUpperCase();
        if ("COUNT".equals(op)) {
            WfDmnDecisionResult.Row row = new WfDmnDecisionResult.Row();
            row.getOutputs().put("count", rows.size());
            List<WfDmnDecisionResult.Row> result = new ArrayList<>();
            result.add(row);
            return result;
        }
        WfDmnDecision.WfDmnOutput output = table.getOutputs().isEmpty() ? null
                : table.getOutputs().get(0);
        if (output == null) {
            throw new WfDmnViolationException("聚合器 " + op + " 需要至少一个输出列");
        }
        List<Number> numbers = new ArrayList<>();
        for (WfDmnDecisionResult.Row row : rows) {
            for (Object value : row.valuesOf(output.getName())) {
                if (!(value instanceof Number)) {
                    throw new WfDmnViolationException("聚合器 " + op + " 要求输出 ["
                            + output.getName() + "] 是数值，实际拿到 " + value);
                }
                numbers.add((Number) value);
            }
        }
        if (numbers.isEmpty()) {
            return rows;
        }
        Object computed;
        if ("SUM".equals(op)) {
            double sum = 0;
            for (Number number : numbers) {
                sum += number.doubleValue();
            }
            computed = sum;
        } else if ("MIN".equals(op)) {
            double min = numbers.get(0).doubleValue();
            for (Number number : numbers) {
                min = Math.min(min, number.doubleValue());
            }
            computed = min;
        } else if ("MAX".equals(op)) {
            double max = numbers.get(0).doubleValue();
            for (Number number : numbers) {
                max = Math.max(max, number.doubleValue());
            }
            computed = max;
        } else {
            throw new WfDmnViolationException("未处理的聚合器: " + aggregator
                    + "。认识的只有 SUM / MIN / MAX / COUNT");
        }
        WfDmnDecisionResult.Row row = new WfDmnDecisionResult.Row();
        row.getOutputs().put(output.getName(), computed);
        List<WfDmnDecisionResult.Row> result = new ArrayList<>();
        result.add(row);
        return result;
    }

    /**
     * 一条命中规则的输出。
     *
     * <p>输出项是<b>值</b>不是条件：要真的求值出来，求不出来就抛 ——
     * 见类注释第 ② 条（缺项的结果调用方一般不会检查）。
     *
     * <p>命中值若在该输出的 {@code outputValues} 列表里，就展开成<b>整个列表</b>
     * （DMN 的标准多值输出语义）。展开后调用方拿到的永远是列表，
     * 不用再为"这个输出是单值还是多值"写分支。
     */
    private WfDmnDecisionResult.Row row(WfDmnDecision.WfDmnTable table,
                                        WfDmnDecision.WfDmnRule hit) {
        WfDmnDecisionResult.Row row = new WfDmnDecisionResult.Row();
        List<String> entries = hit.getOutputEntries();
        for (int i = 0; i < table.getOutputs().size(); i++) {
            WfDmnDecision.WfDmnOutput output = table.getOutputs().get(i);
            String entry = i < entries.size() ? entries.get(i).trim() : "";
            if (entry.isEmpty() || "-".equals(entry)) {
                continue;
            }
            Object value = evalOutput(entry, output);
            List<Object> expanded = expand(value, output);
            row.record(output.getName(),
                    expanded.size() == 1 ? expanded.get(0) : expanded, expanded);
        }
        return row;
    }

    /** 刻意传空表：输出项只写字面量，不该看见任何流程变量。见 {@link #evalOutput} 的说明。 */
    private static final Map<String, Object> EMPTY_VARIABLES = new LinkedHashMap<String, Object>();

    private Object evalOutput(String entry, WfDmnDecision.WfDmnOutput output) {
        Object value;
        try {
            // 输出项是**字面量**，不引用流程变量 —— DMN 的决策表里输出写的是结论
            // （"ceo" / 5.0 / true），依据已经在输入项那一侧算完了。
            // 这里传一张**显式的空表**而不是 null：两者今天行为完全相同
            // （求值器只在表非空时才装载变量，空表与 null 一样都跳过），
            // 但写出来空表能让「输出项不引用变量」这件事在调用点上是看得见的，
            // 而不必依赖求值器内部那个 null 判断 —— 那个判断以后若改成别的语义，
            // 这里读代码的人不会因此误判。
            value = expressionEvaluator.evalRaw(entry, EMPTY_VARIABLES);
        } catch (RuntimeException e) {
            throw new WfDmnViolationException("输出项 [" + entry + "]（输出列 ["
                    + output.getName() + "]）求不出值: " + e.getMessage()
                    + "。输出项求不出来时抛异常而不是给一个缺项的结果 —— "
                    + "调用方通常不会检查缺不缺，它拿到的就是「决策结果」", e);
        }
        if (value == null) {
            // 上面那个 catch 断不到这一种：求值器对**未定义变量**是 fail-closed 的
            //（返回 null 而不是抛），而语法错才会抛。
            // 决策表的输出项本来就该写字面量，引用到一个不存在的变量只有一种解释：
            // 作者以为输出项能读流程变量。放过去的话结果是「这一列输出为 null」
            // 而流程照常往下跑 —— 与「语法写错」那条一样，调用方不会去检查它有没有值。
            throw new WfDmnViolationException("输出项 [" + entry + "]（输出列 ["
                    + output.getName() + "]）求出来是 null。输出项写的是结论的字面量"
                    + "（\"ceo\" / 5.0 / true），判断依据在输入项那一侧已经算完；"
                    + "输出项引用一个不存在的变量不会报错，只会得到一个 null");
        }
        return value;
    }

    /** 命中值落在 {@code outputValues} 列表里就展开成整个列表。 */
    private List<Object> expand(Object value, WfDmnDecision.WfDmnOutput output) {
        List<Object> values = new ArrayList<>();
        List<String> allowed = output.getOutputValues();
        if (!allowed.isEmpty()) {
            for (String candidate : allowed) {
                if (candidate.equals(String.valueOf(value))) {
                    values.addAll(allowed);
                    return values;
                }
            }
        }
        values.add(value);
        return values;
    }

    private boolean sameOutputs(WfDmnDecisionResult.Row left, WfDmnDecisionResult.Row right) {
        return String.valueOf(left.getOutputs()).equals(String.valueOf(right.getOutputs()));
    }
}