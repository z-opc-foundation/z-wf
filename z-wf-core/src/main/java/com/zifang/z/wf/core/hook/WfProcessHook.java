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
     * 钩子类型标识。
     */
    default String hookType() {
        return "wf-process";
    }
}
