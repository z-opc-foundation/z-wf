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
                throw new IllegalArgumentException(
                        "循环定时器（timeCycle）本实现不支持: " + resolved);
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
