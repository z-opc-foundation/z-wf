package com.zifang.z.wf.core.engine.behavior;

import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfTask;

/**
 * 用户任务行为 —— 创建审批任务并返回，引擎据此挂起 token。
 *
 * <p>同时服务 {@code userTask} / {@code manualTask} / {@code task} 三种类型：
 * 它们在<b>引擎语义上完全相同</b>（都要人处理），差别只在"是否要求办理人"，
 * 而那是 {@link WfNode#getAssignee()} 为空与否的运行时事实，不是类型事实。
 * 拆成三个 behavior 只会让同一段逻辑出现三份。
 *
 * <p>到期的计算基准是<b>流程启动时间 + 节点相对时长</b>（{@code dueDateDuration}，
 * ISO-8601 duration，如 {@code PT24H} / {@code P2D}）。不用"当前时间 + 时长"是刻意的：
 * 流程中途补签时，每个节点的到期时间不应该随补签时刻一起漂移，
 * 否则同一张单据的不同节点到期时间会互相矛盾。
 *
 * @author zifang
 */
public class WfUserTaskBehavior implements WfActivityBehavior {

    @Override
    public WfTask execute(WfContext context, WfNode node, WfExecution execution) {
        WfTask task = new WfTask();
        task.setProcessInstanceId(context.getProcessInstanceId());
        task.setExecutionId(execution.getId());
        task.setDefinitionId(node.getId());
        task.setName(node.getName());
        task.setType(node.getType().bpmnName());
        task.setFormKey(node.getFormKey());
        task.setCategory(node.getCategory());
        // 办理人支持 ${var} 动态解析：设计器里写 zifang:assignee="${leaderId}" 时，
        // 真实办理人取自流程变量（谁提交的申请就派给谁的上级）。
        // 不解析的话 assignee 会变成字面量串 "${leaderId}"，待办落到一个不存在的用户头上 ——
        // 症状是"流程发起了但没人收到待办"，且不报错。
        task.setAssignee(resolveAssignee(node.getAssignee(), context));
        task.setCandidateUsers(node.getCandidateUsers());
        task.setCandidateGroups(node.getCandidateGroups());
        task.setPriority(node.getPriority());
        task.setCreateTime(new Date());
        // 有默认办理人 ⇒ 直接 ASSIGNED（进待办）；无 ⇒ CREATED（等认领）
        task.setStatus(node.getAssignee() != null && !node.getAssignee().trim().isEmpty()
                ? WfTask.Status.ASSIGNED : WfTask.Status.CREATED);
        task.setDueDate(computeDueDate(context, node));
        return task;
    }

    /**
     * 解析办理人：支持 {@code ${var}}（取流程变量）与 {@code ${expr}}（EL 求值）。
     *
     * <p><b>fail-closed</b>：变量缺失时返回 {@code null}（任务进候选池待认领），
     * 而不是保留字面量串 —— 后者会让待办挂在一个不存在的用户名下。
     *
     * @param raw     节点上配置的 assignee
     * @param context 执行上下文
     * @return 解析后的办理人；无配置或解析失败返回 {@code null}
     */
    private String resolveAssignee(String raw, WfContext context) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String value = raw.trim();
        if (!value.startsWith("${") || !value.endsWith("}")) {
            return value;   // 字面量办理人
        }
        String expression = value.substring(2, value.length() - 1).trim();
        try {
            Object resolved = context.getExpressionEvaluator()
                    .evalRaw(expression, context.mergedVariables());
            return resolved == null ? null : String.valueOf(resolved).trim();
        } catch (Exception e) {
            // 解析失败宁可交给候选人，也不要把字面量 "${leaderId}" 当成用户名
            org.slf4j.LoggerFactory.getLogger(WfUserTaskBehavior.class)
                    .warn("办理人表达式解析失败，回落到候选池: assignee={}, expression={}",
                            raw, expression, e);
            return null;
        }
    }

    /**
     * 流程启动时间 + 节点相对时长。
     */
    private Date computeDueDate(WfContext context, WfNode node) {
        String duration = node.getDueDateDuration();
        if (duration == null || duration.trim().isEmpty()) {
            return null;
        }
        Date base = context.getProcessInstance() != null && context.getProcessInstance().getStartTime() != null
                ? context.getProcessInstance().getStartTime()
                : new Date();
        Long millis = WfDurationUtil.parse(duration);
        if (millis == null) {
            return null;
        }
        return new Date(base.getTime() + millis);
    }

    /**
     * ISO-8601 duration 子集解析。
     *
     * <p>支持：{@code PnYnMnD} / {@code PTnHnMnS}（可组合，如 {@code P1DT2H}）。
     * 年按 365 天、月按 30 天折算 —— 审批时长不会精确到"这个月 28 天还是 31 天"，
     * 真要精确到期时间应该用绝对时间表达式而不是相对时长。
     *
     * <p>不引第三方库的理由：{@link java.time.Duration#parse} 只吃 {@code PT...} 形式，
     * 不认 {@code P1D}（日期部分）与 {@code P1M}（月）；而流程设计器输出的就是后者。
     */
    static final class WfDurationUtil {

        private static final Pattern PATTERN = Pattern.compile(
                "^P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)D)?"
                        + "(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$");

        private static final long MILLIS_PER_SECOND = 1000L;
        private static final long MILLIS_PER_MINUTE = 60L * MILLIS_PER_SECOND;
        private static final long MILLIS_PER_HOUR = 60L * MILLIS_PER_MINUTE;
        private static final long MILLIS_PER_DAY = 24L * MILLIS_PER_HOUR;
        private static final long MILLIS_PER_YEAR = 365L * MILLIS_PER_DAY;
        private static final long MILLIS_PER_MONTH = 30L * MILLIS_PER_DAY;

        private WfDurationUtil() {
        }

        /**
         * 解析 ISO-8601 duration。
         *
         * @return 毫秒数；格式不合法返回 {@code null}（调用方决定是忽略到期时间还是报错）
         */
        static Long parse(String duration) {
            if (duration == null) {
                return null;
            }
            Matcher matcher = PATTERN.matcher(duration.trim().toUpperCase());
            if (!matcher.matches()) {
                return null;
            }
            long total = 0L;
            total += group(matcher, 1) * MILLIS_PER_YEAR;
            total += group(matcher, 2) * MILLIS_PER_MONTH;
            total += group(matcher, 3) * MILLIS_PER_DAY;
            total += group(matcher, 4) * MILLIS_PER_HOUR;
            total += group(matcher, 5) * MILLIS_PER_MINUTE;
            total += group(matcher, 6) * MILLIS_PER_SECOND;
            return total;
        }

        /**
         * 相对时间相加。
         */
        static Date plus(Date base, String duration) {
            Long millis = parse(duration);
            return millis == null ? null : new Date(base.getTime() + millis);
        }

        private static long group(Matcher matcher, int index) {
            String value = matcher.group(index);
            if (value == null || value.isEmpty()) {
                return 0L;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
    }
}
