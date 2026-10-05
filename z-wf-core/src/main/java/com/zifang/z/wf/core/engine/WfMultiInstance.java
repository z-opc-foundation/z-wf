package com.zifang.z.wf.core.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.model.WfTask;

/**
 * 多实例（会签 / 或签 / 计数会签）的常量与完成判定。
 *
 * <p><b>实例数从任务反推，不另存计数。</b> 这一点是本实现最重要的取舍：
 * 计数如果存在流程变量里，就必须保证"变量里的数字"和"库里真实存在的任务数"
 * 永远一致，而这两者分属两条写路径 —— 任务可能被 {@code terminate} 作废、
 * 被 {@code force-complete} 补办、并发下可能被两个请求同时改。
 * 任何一处不同步，计数就会漂移，表现为"会签说还差一个人，但那个人没有待办"。
 *
 * <p>任务本身就是实例：{@code definitionId == 节点 id} 且未作废的任务，
 * 就是这个多实例节点还没走完的实例。所以 {@code nrOfCompletedInstances}
 * 是数出来的，不是存出来的，漂移无从发生。
 *
 * @author zifang
 */
public final class WfMultiInstance {

    /** 当前实例的序号（0 起），token 局部变量。 */
    public static final String LOOP_COUNTER = "loopCounter";

    /** 当前实例的办理人，token 局部变量。流程定义里用 {@code ${loopAssignee}} 引用。 */
    public static final String LOOP_ASSIGNEE = "loopAssignee";

    /** 实例总数，流程级变量。 */
    public static final String NR_OF_INSTANCES = "nrOfInstances";

    /** 仍在办的实例数，流程级变量。 */
    public static final String NR_OF_ACTIVE_INSTANCES = "nrOfActiveInstances";

    /** 已办结的实例数，流程级变量。 */
    public static final String NR_OF_COMPLETED_INSTANCES = "nrOfCompletedInstances";

    private WfMultiInstance() {
    }

    /**
     * 统计结果。
     */
    public static final class Stats {
        private final int total;
        private final int active;
        private final int completed;

        Stats(int total, int active, int completed) {
            this.total = total;
            this.active = active;
            this.completed = completed;
        }

        public int getTotal() {
            return total;
        }

        public int getActive() {
            return active;
        }

        public int getCompleted() {
            return completed;
        }
    }

    /**
     * 从任务集合统计多实例进度。
     *
     * @param nodeTasks 该多实例节点的全部任务
     */
    public static Stats stats(List<WfTask> nodeTasks) {
        int active = 0;
        int completed = 0;
        if (nodeTasks != null) {
            for (WfTask task : nodeTasks) {
                if (task == null) {
                    continue;
                }
                if (task.getStatus() == WfTask.Status.COMPLETED) {
                    completed++;
                } else if (task.getStatus() == WfTask.Status.CANCELLED) {
                    // 作废（会签被完成条件提前收口、或流程被终止）不算办结
                    continue;
                } else {
                    active++;
                }
            }
        }
        return new Stats(active + completed, active, completed);
    }

    /**
     * 该多实例节点是否算完成。
     *
     * <p>两条路径：
     * <ul>
     *   <li>完成条件成立 ⇒ 收口（剩余实例作废）</li>
     *   <li>已无在办实例 ⇒ 收口（否则会永远等下去）</li>
     * </ul>
     * 没有完成条件时 {@code evaluate} 判为不成立，于是走第二条：
     * 全部办完才算完成 —— 这就是会签。
     *
     * @param completionCondition 完成条件表达式；为空表示没配（即会签：全办完才算）
     * @param evaluator 表达式求值器
     * @param variables 循环变量视图
     */
    public static boolean isComplete(String completionCondition,
                                     com.zifang.z.wf.core.engine.expression.WfExpressionEvaluator evaluator,
                                     Stats stats, Map<String, Object> variables) {
        if (completionCondition != null && !completionCondition.trim().isEmpty()
                && evaluator.evaluateForLoop(variables, completionCondition)) {
            return true;
        }
        return stats.getActive() == 0;
    }

    /**
     * 组装循环变量视图。
     *
     * <p>用<b>流程变量打底再覆盖循环变量</b>：完成条件里往往还要引用业务变量
     * （{@code ${nrOfCompletedInstances >= 2 && amount > 1000}}），
     * 只给循环变量会让业务变量被当成"未定义"而 fail-closed 判 false，
     * 于是会签卡死且看不出原因。
     */
    public static Map<String, Object> loopVariables(Map<String, Object> processVariables,
                                                    Stats stats, int loopCounter) {
        Map<String, Object> merged = new java.util.HashMap<>();
        if (processVariables != null) {
            merged.putAll(processVariables);
        }
        merged.put(NR_OF_INSTANCES, stats.getTotal());
        merged.put(NR_OF_ACTIVE_INSTANCES, stats.getActive());
        merged.put(NR_OF_COMPLETED_INSTANCES, stats.getCompleted());
        merged.put(LOOP_COUNTER, loopCounter);
        return merged;
    }

    /** 该节点上尚未完成、且尚未作废的任务 —— 会签被收口时要把它们作废。 */
    public static List<WfTask> cancellable(List<WfTask> nodeTasks) {
        List<WfTask> pending = new ArrayList<>();
        if (nodeTasks != null) {
            for (WfTask task : nodeTasks) {
                if (task != null && task.isOpen()) {
                    pending.add(task);
                }
            }
        }
        return pending;
    }

    /** 给流程级变量视图补上循环统计，供外部（如结果表达式）引用。 */
    public static void publishStats(WfContext context, Stats stats) {
        context.setVariable(NR_OF_INSTANCES, stats.getTotal());
        context.setVariable(NR_OF_ACTIVE_INSTANCES, stats.getActive());
        context.setVariable(NR_OF_COMPLETED_INSTANCES, stats.getCompleted());
    }

    /** 仅为可读性：把完成条件表达式与统计拼成一句日志文本。 */
    public static String describe(WfNode node, Stats stats) {
        return "节点 " + node.getId() + " 完成条件=" + node.getCompletionCondition()
                + " 实例=" + stats.getTotal() + " 在办=" + stats.getActive()
                + " 已办=" + stats.getCompleted();
    }
}
