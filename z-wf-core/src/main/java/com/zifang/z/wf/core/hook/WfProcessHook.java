package com.zifang.z.wf.core.hook;

import java.util.Map;

/**
 * 流程生命周期钩子 —— 对齐 z-camuda 的 {@code CamudaProcessHook}。
 *
 * <p>三个方法都是 {@code default}，业务方只覆盖关心的部分。
 * 实现方式为本地 Spring Bean：{@code @Bean} 提供一个实现类即可。
 *
 * <p><b>钩子抛异常的处理约定</b>：
 * <ul>
 *   <li>{@link #onBeforeStart} 返回 false ⇒ 取消本次启动（调用方拿到 null）</li>
 *   <li>{@link #onAfterStart} / {@link #onComplete} 抛异常 ⇒ <b>只记日志，不影响流程状态</b>。
 *       通知类钩子失败不该把已经完成的审批流回滚 —— 审批已经生效，回滚会造成
 *       "待办消失了但流程还在跑"的不一致</li>
 * </ul>
 * 调用方（{@code WfHookDispatcher}）负责隔离异常，钩子实现方可以放心抛。
 *
 * @author zifang
 */
public interface WfProcessHook {

    /**
     * 流程启动前。
     *
     * @param definitionKey 流程定义 key
     * @param variables     流程变量（可修改，修改会带入流程）
     * @return true 继续启动；false 取消启动
     */
    default boolean onBeforeStart(String definitionKey, Map<String, Object> variables) {
        return true;
    }

    /**
     * 流程启动后。
     */
    default void onAfterStart(String definitionKey, String processInstanceId, Map<String, Object> variables) {
    }

    /**
     * 流程完成。
     *
     * @param outcome 流程结果（approved / rejected / completed 等）
     */
    default void onComplete(String definitionKey, String processInstanceId, String outcome) {
    }

    /**
     * token 沿一条连线从 A 走到 B。
     *
     * <p>Camunda 把这一段拆成 {@code transitionStart}（离开 A）与
     * {@code transitionEnd}（到达 B）两个事件。合成一个是因为本引擎的
     * "离开"与"到达"发生在同一次推进里，拆开只能拿到一半信息 ——
     * start 拿不到 to，end 拿不到 flowId，而"这条线上跑了多少单"恰恰是
     * 最想要 flowId 的那类统计。
     *
     * <p><b>通知型，不能改线也不能中断</b>：{@code toActivityId} 到触发时已确定，
     * 实现方在这里抛异常只会让整次推进失败（钩子异常由 dispatcher 记日志后吞掉）。
     * 动态路由请用 {@code serviceTask} + delegate，那是引擎明确支持的扩展点。
     *
     * <p>并行分叉时<b>每条出线触发一次</b>；汇合时不触发 ——
     * 汇合点上一条 token 消失、新 token 出现，不是"沿某条线走过去"。
     */
    default void onTransition(String definitionKey, String processInstanceId,
                              String fromActivityId, String toActivityId, String flowId) {
    }

    /**
     * 排上一个 job 之后。
     *
     * <p>七种 job（定时器 / 消息 / 信号 / 外部 / 异步前置 / 异步后置）都会触发。
     * 做"这一步平均等了多久""哪个节点的定时器最常被撤"这类统计，
     * 不必再改引擎代码。
     *
     * @param jobType job 类型名（TIMER / MESSAGE / SIGNAL / EXTERNAL /
     *               ASYNC_BEFORE / ASYNC_AFTER）
     */
    default void onJobScheduled(String definitionKey, String processInstanceId,
                                String jobId, String jobType, String elementId) {
    }

    /**
     * 一个 job 执行完（无论成功还是失败）。
     *
     * @param success true=触发成功；false=这次没触发（流程已结束、token 已挪走
     *               这类"该做没做"，它们不是失败，但也不该被算成执行成功）
     */
    default void onJobExecuted(String definitionKey, String processInstanceId,
                                String jobId, String jobType, String elementId, boolean success) {
    }

    /**
     * 钩子类型标识。
     */
    default String hookType() {
        return "wf-process";
    }
}
