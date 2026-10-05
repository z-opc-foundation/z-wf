package com.zifang.z.wf.core.engine.expression;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.util.expr.el.ElEvaluator;

/**
 * 条件表达式求值器 —— 网关分支与任务完成条件的唯一求值入口。
 *
 * <p><b>为什么自己再包一层，而不是直接用 z-util-wf-kernel 的 {@code GatewayEvaluator}：</b>
 * kernel 那份的实现策略是"把标识符前加 {@code #} 变成 SpEL 风格再喂 EL"，它是为
 * 内存引擎的单点网关设计的。z-wf 侧多出三个真实需求：
 * <ol>
 *   <li><b>失败策略可配</b>：条件求值抛异常时，生产引擎不能默认当 false（会把审批流静默卡死），
 *       也不能默认当 true（会把审批流静默放行）。默认必须 <b>fail-closed 并落日志</b>，
 *       而 fail-open 要由业务方显式开启。</li>
 *   <li><b>空值语义</b>：{@code ${amount > 1000}} 在 amount 缺失时必须给出确定答案并可被观测到，
 *       而不是把 NPE 冒泡到引擎层。</li>
 *   <li><b>求值轨迹</b>：排他网关走错分支时，需要能回答"当时变量是什么、条件求成了什么"。</li>
 * </ol>
 *
 * <p>表达式语法沿用 z-util-expr-el：比较 / 逻辑 / 三目 / 括号 / 字符串字面量 / 方法调用。
 * 同时接受带壳 {@code ${...}} 与裸表达式两种写法（与 kernel 一致）。
 *
 * @author zifang
 */
public class WfExpressionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(WfExpressionEvaluator.class);

    /** 条件求值失败时的默认行为：判为 false（卡住流程），并记日志。 */
    public static final boolean DEFAULT_FAIL_OPEN = false;

    private final boolean failOpen;

    public WfExpressionEvaluator() {
        this(DEFAULT_FAIL_OPEN);
    }

    /**
     * @param failOpen true = 条件求值异常时判为 true（放行）；false = 判为 false（卡住）
     */
    public WfExpressionEvaluator(boolean failOpen) {
        this.failOpen = failOpen;
    }

    /**
     * 求值为布尔。
     *
     * <p>空表达式返回 <b>true</b>（恒成立）—— 这是"无条件连线"的语义。
     * 反过来想：如果空表达式返回 false，那么"排他网关接了一条无条件默认线"会永远走不出去，
     * 而这是最常见的建图方式。默认值选 true 与 BPMN 的"无 conditionExpression 即视为真"一致。
     *
     * @param expression 条件表达式（可带 ${} 壳）
     * @param variables  变量表
     * @return 求值结果；求值异常时按 {@link #failOpen} 决定
     */
    public boolean evaluate(String expression, Map<String, Object> variables) {
        if (expression == null || expression.trim().isEmpty()) {
            return true;
        }
        // ---- 未定义变量：fail-closed ----
        // 这一步不能省。z-util 的 EL 在变量缺失时把 null 当 0 参与比较（实测
        // `amount < 1000` 在 amount 未设时求值为 true）。对审批流来说这是危险的：
        // "金额未知" 会被当成 "金额 0"，于是本该升级到总经理的大额单据
        // 悄悄走了低额审批分支 —— 而且没有任何报错。
        // 正确语义是：条件引用了不存在的变量 ⇒ 条件不成立 ⇒ 由 default 流兜底
        //（这正是 BPMN 允许配 defaultFlow 的意义）。
        List<String> undefined = undefinedIdentifiers(expression, variables);
        if (!undefined.isEmpty()) {
            log.warn("条件表达式引用了未定义变量，按不成立处理: expression={}, undefined={}, variables={}",
                    expression, undefined, variables == null ? null : variables.keySet());
            return false;
        }
        try {
            return toBoolean(evalRaw(expression, variables));
        } catch (Exception e) {
            log.error("条件表达式求值失败，按 failOpen={} 处理: expression={}, variables={}",
                    failOpen, expression, variables, e);
            return failOpen;
        }
    }

    /**
     * 找出表达式里引用了、但变量表中不存在的标识符。
     *
     * <p>跳过的三类 token（否则会误判）：
     * <ul>
     *   <li>字符串字面量（{@code 'x'} / {@code "x"}）里的字符</li>
     *   <li>关键字（{@code true/false/null/and/or/not/...}）</li>
     *   <li>方法调用名（标识符紧跟 {@code (}）与点号后的属性名（{@code obj.field}）</li>
     * </ul>
     * 最后一条是刻意的宽松处理：{@code user.dept} 里的 {@code dept} 不算未定义变量，
     * 因为它是对象属性而非流程变量，取不到时由 EL 自己处理。
     */
    private List<String> undefinedIdentifiers(String expression, Map<String, Object> variables) {
        List<String> undefined = new ArrayList<>();
        String expr = stripBraces(expression.trim());
        int len = expr.length();
        int i = 0;
        // 记录"上一个非空白字符"，用于判断标识符后面是否紧跟 '.' 或 '('
        while (i < len) {
            char ch = expr.charAt(i);
            if (ch == '\'' || ch == '"') {
                char quote = ch;
                i++;
                while (i < len && expr.charAt(i) != quote) {
                    if (expr.charAt(i) == '\\') {
                        i++; // 跳过转义字符
                    }
                    i++;
                }
                i++;
                continue;
            }
            if (Character.isLetter(ch) || ch == '_') {
                int start = i;
                while (i < len && (Character.isLetterOrDigit(expr.charAt(i)) || expr.charAt(i) == '_')) {
                    i++;
                }
                String ident = expr.substring(start, i);
                // 前面紧跟 '.' ⇒ 属性名，不是变量
                if (start > 0 && expr.charAt(start - 1) == '.') {
                    continue;
                }
                // 后面紧跟 '(' ⇒ 方法调用
                int j = i;
                while (j < len && Character.isWhitespace(expr.charAt(j))) {
                    j++;
                }
                if (j < len && expr.charAt(j) == '(') {
                    continue;
                }
                if (isKeyword(ident)) {
                    continue;
                }
                boolean defined = false;
                if (variables != null) {
                    defined = variables.containsKey(ident)
                            // 允许 EL 风格 #name
                            || variables.containsKey(ident.startsWith("#") ? ident.substring(1) : ident);
                }
                if (!defined && !undefined.contains(ident)) {
                    undefined.add(ident);
                }
                continue;
            }
            i++;
        }
        return undefined;
    }

    private boolean isKeyword(String word) {
        return "true".equals(word) || "false".equals(word) || "null".equals(word)
                || "and".equals(word) || "or".equals(word) || "not".equals(word)
                || "div".equals(word) || "mod".equals(word)
                || "instanceof".equals(word) || "matches".equals(word)
                || "between".equals(word) || "empty".equals(word);
    }

    /**
     * 求值为任意对象（给 scriptTask / resultExpression 用）。
     */
    public Object evalRaw(String expression, Map<String, Object> variables) {
        if (expression == null || expression.trim().isEmpty()) {
            return null;
        }
        String normalized = stripBraces(expression.trim());
        ElEvaluator evaluator = new ElEvaluator();
        if (variables != null && !variables.isEmpty()) {
            evaluator.setVariables(variables);
        }
        return evaluator.eval(normalized);
    }

    /**
     * 严格模式求值：异常直接抛出，不做 fail-open/fail-closed 兜底。
     *
     * <p>给"条件写错了必须立刻发现"的场景（如单元测试、流程定义 CI 校验）用。
     */
    public boolean evaluateStrict(String expression, Map<String, Object> variables) {
        if (expression == null || expression.trim().isEmpty()) {
            return true;
        }
        return toBoolean(evalRaw(expression, variables));
    }

    /**
     * 去掉 {@code ${...}} 壳。
     */
    private String stripBraces(String expression) {
        if (expression.startsWith("${") && expression.endsWith("}")) {
            return expression.substring(2, expression.length() - 1).trim();
        }
        return expression;
    }

    /**
     * 结果转布尔。
     *
     * <p>数值按"非 0 即真"，字符串按"非空即真"（与 EL 规范一致）。
     */
    private boolean toBoolean(Object result) {
        if (result instanceof Boolean) {
            return (Boolean) result;
        }
        if (result == null) {
            return false;
        }
        if (result instanceof Number) {
            return ((Number) result).doubleValue() != 0.0d;
        }
        if (result instanceof String) {
            return !((String) result).trim().isEmpty();
        }
        if (result instanceof Collection) {
            return !((Collection<?>) result).isEmpty();
        }
        if (result instanceof Map) {
            return !((Map<?, ?>) result).isEmpty();
        }
        return Boolean.parseBoolean(String.valueOf(result));
    }
}
