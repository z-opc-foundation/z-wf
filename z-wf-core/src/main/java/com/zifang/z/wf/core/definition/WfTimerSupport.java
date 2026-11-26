package com.zifang.z.wf.core.definition;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定时器表达式的解析。
 *
 * <p>支持 BPMN 的两种时刻写法：ISO-8601 时长（{@code PT5M}）与 ISO-8601 时刻
 * （{@code 2026-12-31T18:00:00Z}）。两者都可以先写成流程变量
 * （{@code ${timeoutDuration}}），引擎在建立 job 时用实例变量求值 ——
 * 这是"审批时限由发起人按金额档位自己填"这种需求的入口。
 *
 * <p><b>年 / 月 / 周按固定天数换算</b>（1 年 = 365 天，1 月 = 30 天，1 周 = 7 天），
 * 不按日历逐项累加。理由：这里算的是<b>经过的时间</b>而不是日历上的某个点 ——
 * "这一步最多停 1 个月"是时间预算，不是"下个月同一天"。
 * 若按日历加，1 月 31 日 + 1 个月会落在 2 月 28 日或 3 月 3 日，
 * 于是同一份流程定义在不同启动日会得到不同的超时时刻，
 * 超时提醒这种事最怕的就是"有时准有时不准"。
 * Camunda 的定时器也是这么换算的。
 *
 * @author zifang
 */
public final class WfTimerSupport {

    /** ISO-8601 时长：PnYnMnWnDTnHnMnS，年月周与时分秒都可选。 */
    private static final Pattern DURATION = Pattern.compile(
            "^P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?"
                    + "(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?)?$");

    /** 变量引用：{@code ${name}}。 */
    private static final Pattern VARIABLE = Pattern.compile("^\\s*\\$\\{\\s*([\\w.$-]+)\\s*}\\s*$");

    /**
     * 这个表达式是不是一个变量引用。
     *
     * <p>部署期校验要用：{@code ${sla}} 此刻还没有实例、没有变量值，
     * 拿它去 {@code parseDuration} 必然失败，而那个失败与"作者写错了格式"无关。
     * 变量有没有值是运行期的事，那里才该报。
     */
    public static boolean isVariableReference(String expression) {
        return expression != null && VARIABLE.matcher(expression).matches();
    }

    private static final long MILLIS_PER_SECOND = 1000L;
    private static final long MILLIS_PER_MINUTE = 60L * MILLIS_PER_SECOND;
    private static final long MILLIS_PER_HOUR = 60L * MILLIS_PER_MINUTE;
    private static final long MILLIS_PER_DAY = 24L * MILLIS_PER_HOUR;

    private WfTimerSupport() {
    }

    /**
     * 算出触发时刻。
     *
     * @param type       触发类型
     * @param expression  表达式本身（可能是 {@code ${变量}}）
     * @param base       相对时长的起算点（进入宿主节点的时刻）
     * @param variables  流程变量；为 null 时按"表达式里不该有变量"处理
     * @return 触发时刻
     * @throws IllegalArgumentException 表达式无法解析或引用了取不到的变量
     */
    public static Date resolveDueDate(WfTimerType type, String expression, Date base,
                                      Map<String, Object> variables) {
        String resolved = resolveExpression(expression, variables);
        switch (type) {
            case DURATION:
                return new Date(base.getTime() + parseDuration(resolved));
            case DATE:
                return parseInstant(resolved);
            case CYCLE:
                Cycle cycle = parseCycle(resolved);
                // 第一次触发是 anchor + 一个周期，而不是 anchor 本身：
                // R3/PT1H 的语义是"从现在起 1 小时后、2 小时后、3 小时后各触发一次"，
                // 若把 anchor 当成第一次，R3/PT1H 会在 0 时刻就响一下，
                // 而那正是"刚进入节点"的瞬间 —— 等于没有等待
                Date anchor = cycle.getStart() != null ? cycle.getStart() : base;
                return new Date(anchor.getTime() + cycle.getPeriodMillis());
            default:
                throw new IllegalArgumentException("未知的定时器类型: " + type);
        }
    }

    /**
     * 把 {@code ${变量}} 换成实际值。
     *
     * <p>变量取不到时抛错而不是退回字面量：把 {@code ${timeoutDuration}}
     * 当字符串解析必然失败，而失败信息会指向"ISO 格式不对"，
     * 把真正的原因（变量没设）藏起来了 ——
     * 变量没设的典型原因是"某个分支上没赋值"，那属于流程定义的缺陷，必须让部署/运行报出来。
     */
    private static String resolveExpression(String expression, Map<String, Object> variables) {
        if (expression == null || expression.trim().isEmpty()) {
            throw new IllegalArgumentException("定时器表达式为空");
        }
        Matcher matcher = VARIABLE.matcher(expression);
        if (!matcher.matches()) {
            return expression.trim();
        }
        String name = matcher.group(1);
        if (variables == null) {
            // 校验期：变量值此刻还不存在，只确认写法是对的
            return expression.trim();
        }
        Object value = variables.get(name);
        if (value == null) {
            throw new IllegalArgumentException(
                    "定时器表达式引用了流程变量 " + name + "，但该变量没有值。"
                            + "请确认启动流程时赋了值，或改用字面量 ISO-8601 表达式");
        }
        return String.valueOf(value).trim();
    }

    /**
     * 解析 ISO-8601 时长为毫秒数。
     *
     * <p>不用 {@code java.time.Duration.parse}：它不接受 {@code P1Y} / {@code P1M}
     * （里面的 M 是月不是分钟），而这两种在 BPMN 里是常见写法。
     */
    public static long parseDuration(String iso) {
        Matcher matcher = DURATION.matcher(iso.trim().toUpperCase());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "不是合法的 ISO-8601 时长: " + iso + "（形如 PT5M / P1DT2H）");
        }
        long millis = 0L;
        // 固定天数换算，理由见类注释
        millis += group(matcher, 1) * 365L * MILLIS_PER_DAY;
        millis += group(matcher, 2) * 30L * MILLIS_PER_DAY;
        millis += group(matcher, 3) * 7L * MILLIS_PER_DAY;
        millis += group(matcher, 4) * MILLIS_PER_DAY;
        millis += group(matcher, 5) * MILLIS_PER_HOUR;
        millis += group(matcher, 6) * MILLIS_PER_MINUTE;
        String seconds = matcher.group(7);
        if (seconds != null && !seconds.isEmpty()) {
            millis += (long) (Double.parseDouble(seconds) * MILLIS_PER_SECOND);
        }
        if (millis <= 0L) {
            throw new IllegalArgumentException(
                    "时长的值不大于零: " + iso + "，建出来的 job 会立刻到期或永远不到期");
        }
        return millis;
    }

    /**
     * 解析 ISO-8601 时刻。
     *
     * <p>兼容三种写法：带 {@code Z} 的 UTC、带偏移量的本地时间（{@code +08:00}）、
     * 以及不带时区的裸时间（按 UTC 处理 —— 流程定义跨时区部署时，
     * 裸时间本身就没有确定的时刻，必须显式写时区才不会被各人解释成不同结果）。
     */
    public static Date parseInstant(String iso) {
        String text = iso.trim();
        try {
            return Date.from(Instant.parse(text));
        } catch (RuntimeException ignored) {
            // 不是 Instant 的写法，继续试下面两种
        }
        try {
            return Date.from(java.time.OffsetDateTime.parse(
                    text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant());
        } catch (RuntimeException ignored) {
            // 也不是带偏移量的写法，最后试裸时间
        }
        try {
            LocalDateTime local = LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return Date.from(local.toInstant(ZoneOffset.UTC));
        } catch (RuntimeException ignored) {
            throw new IllegalArgumentException(
                    "不是合法的 ISO-8601 时刻: " + iso
                            + "（形如 2026-12-31T18:00:00Z；不带时区按 UTC 处理）");
        }
    }

    /**
     * 循环定时器（{@code timeCycle}）解析出来的规格。
     *
     * <p>刻意只保留三个量：<b>周期</b>、<b>最多触发几次</b>、<b>可选的起始时刻</b>。
     * 每次触发的时刻不存 —— 它就是"上一次的时刻 + 周期"，
     * 所以重新挂下一次定时器不需要知道锚点是什么。
     */
    public static final class Cycle {
        private final long periodMillis;
        private final int maxFires;
        private final boolean unbounded;
        private final Date start;

        Cycle(long periodMillis, int maxFires, boolean unbounded, Date start) {
            this.periodMillis = periodMillis;
            this.maxFires = maxFires;
            this.unbounded = unbounded;
            this.start = start;
        }

        public long getPeriodMillis() {
            return periodMillis;
        }

        /**
         * 最多触发几次。
         *
         * <p>无界写法（{@code R/PT10M}）时这里是 {@link #UNBOUNDED_FIRES}。
         */
        public int getMaxFires() {
            return maxFires;
        }

        public boolean isUnbounded() {
            return unbounded;
        }

        /** 表达式里显式写了起始时刻时用它，否则为 null（由调用方取进入节点的时刻）。 */
        public Date getStart() {
            return start == null ? null : new Date(start.getTime());
        }
    }

    /**
     * 无界写法的触发次数占位值。
     *
     * <p>引擎另有 {@link #MAX_CYCLE_FIRES} 硬上限兜底 —— 无界是合法的 BPMN 写法，
     * 但"每 10 分钟催一次"在一个没人管的单子上会一直催下去，
     * 每次还多出一条并行分支。触顶后停挂并写日志，而不是无限跑。
     */
    public static final int UNBOUNDED_FIRES = -1;

    /** 循环定时器触发次数的硬上限（无界写法的兜底）。 */
    public static final int MAX_CYCLE_FIRES = 100;

    /** ISO-8601 重复间隔的重复次数段：{@code R} 或 {@code R3}。 */
    private static final Pattern REPEAT = Pattern.compile("^R(?:(\\d+))?$");

    /**
     * 解析 ISO-8601 重复间隔。
     *
     * <p>支持的四种写法（都是 BPMN 里真实出现过的）：
     * <ul>
     *   <li>{@code R3/PT1H} —— 一共响 3 次，每隔 1 小时</li>
     *   <li>{@code R/PT10M} —— 不限次数（引擎有硬上限兜底）</li>
     *   <li>{@code P1D/T1H} —— 响到满 1 天为止（换算成 24 次）</li>
     *   <li>{@code 2026-01-01T09:00:00Z/P1D/T1H} —— 显式起始时刻</li>
     * </ul>
     *
     * <p>按 {@code /} 切开再逐段**辨认**（而不是按固定位置取），
     * 因为 ISO 8601 允许省略中间的段：同一份文法在四个写法里
     * 起始时刻既可能在第 1 段也可能在第 2 段，按位置取会认错。
     */
    public static Cycle parseCycle(String iso) {
        String text = iso.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("循环定时器表达式为空");
        }
        String[] parts = text.split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException(
                    "不是合法的 ISO-8601 重复间隔: " + iso
                            + "（形如 R3/PT1H / R/PT10M / P1D/T1H）");
        }
        int index = 0;
        int maxFires = UNBOUNDED_FIRES;
        Matcher repeat = REPEAT.matcher(parts[0]);
        if (repeat.matches()) {
            if (repeat.group(1) != null) {
                maxFires = Integer.parseInt(repeat.group(1));
                if (maxFires <= 0) {
                    throw new IllegalArgumentException(
                            "循环定时器的重复次数不大于零: " + iso + "（R0 一次都不会响）");
                }
            }
            index = 1;
        }
        Date start = null;
        if (index < parts.length - 1 && isDateTime(parts[index])) {
            start = parseInstant(parts[index]);
            index++;
        }
        if (index < parts.length - 1) {
            // 形如 P1D/T1H：前一段是"响到什么时候为止"的总时长
            long boundMillis = parseDuration(parts[index]);
            long period = parsePeriod(parts[parts.length - 1]);
            maxFires = (int) (boundMillis / period);
            if (maxFires <= 0) {
                throw new IllegalArgumentException(
                        "循环定时器的总时长小于一个周期: " + iso
                                + "（等于说一次都不会响）");
            }
        }
        long period = parsePeriod(parts[parts.length - 1]);
        if (period <= 0L) {
            throw new IllegalArgumentException("循环定时器的周期不大于零: " + iso);
        }
        return new Cycle(period, maxFires, maxFires == UNBOUNDED_FIRES, start);
    }

    /**
     * 再响一次的时刻；{@code firedIndex} 已经是最后一次则返回 null。
     *
     * @param firedIndex 刚响过的那一条是<b>第几次</b>触发（1 起）。
     *                   写成"这次是第几次"而不是"已经响过几次"，
     *                   是为了让 {@code R3/PT1H} 恰好响 3 次：
     *                   两种写法差一个 1，而差的这一下会让第 4 次照响，
     *                   表现是"作者写三次、实际催了四次"。
     *
     * <p><b>用上一次的时刻加周期</b>而不是"锚点 + (n+1) × 周期"：
     * 锚点是进入宿主节点的那个时刻，它不在 job 上，而重新挂下一次时
     * 手里的只有上一条 job。两种算法在理想情况下结果相同，
     * 但累加法不依赖任何存下来的锚点，也就不会漂。
     */
    public static Date nextDueDate(Cycle cycle, Date previousDue, int firedIndex) {
        int limit = cycle.isUnbounded() ? MAX_CYCLE_FIRES : cycle.getMaxFires();
        if (firedIndex >= limit) {
            return null;
        }
        return new Date(previousDue.getTime() + cycle.getPeriodMillis());
    }

    /**
     * 解析重复间隔里的<b>周期</b>段。
     *
     * <p>它可能只写时间部分：{@code P1D/T1H} 里的周期是 {@code T1H} 而不是
     * {@code PT1H}。ISO 8601 的完整时长必须带日期部分 {@code P}，
     * 直接丢给 {@link #parseDuration} 会报"不是合法的 ISO-8601 时长"，
     * 而 {@code P1D/T1H} 恰恰是 BPMN 里最常见的写法之一。
     */
    private static long parsePeriod(String part) {
        String text = part.trim().toUpperCase();
        return parseDuration(text.startsWith("T") ? "P" + text : text);
    }

    /** 这一段是不是一个 ISO-8601 时刻（用来在切分后的段里认出起始时刻）。 */
    private static boolean isDateTime(String part) {
        String text = part.trim();
        return text.indexOf('T') > 0 && !text.startsWith("P");
    }

    /** 组序号 1..6 都是整数字符串，缺失时为 null。 */
    private static long group(Matcher matcher, int index) {
        String text = matcher.group(index);
        return text == null || text.isEmpty() ? 0L : Long.parseLong(text);
    }

    /**
     * 算出"进入节点后多久到期"，供界面展示用。
     *
     * <p>超时的可解释性比精确到毫秒重要：审批界面上要写"30 分钟未处理将自动提醒"，
     * 而不是"距 14:23:07 触发"。所以这里把毫秒折算成最大的那个整单位。
     */
    public static String describeDuration(long millis) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(millis);
        long days = cal.get(Calendar.DAY_OF_YEAR) - 1;
        long hours = cal.get(Calendar.HOUR_OF_DAY);
        long minutes = cal.get(Calendar.MINUTE);
        if (days > 0) {
            return days + " 天" + (hours > 0 ? " " + hours + " 小时" : "");
        }
        if (hours > 0) {
            return hours + " 小时" + (minutes > 0 ? " " + minutes + " 分钟" : "");
        }
        if (minutes > 0) {
            return minutes + " 分钟";
        }
        long seconds = millis / MILLIS_PER_SECOND;
        return seconds + " 秒";
    }
}
