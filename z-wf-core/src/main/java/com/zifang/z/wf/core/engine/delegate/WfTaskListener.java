package com.zifang.z.wf.core.engine.delegate;

import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.model.WfExecution;

/**
 * 任务监听器 —— 在 userTask 的关键时点插业务逻辑（发通知、写台账、改变量）。
 *
 * <p>与 z-camuda 的 {@code CamudaTaskHook} 定位相同，但挂在<b>引擎内部</b>：
 * {@code WfTaskHook} 是全局 Bean（对所有流程生效），
 * 而本接口是<b>按流程定义节点</b>声明的（在 userTask 上配 {@code zifang:taskListeners}），
 * 因此适合"这一类审批节点统一走同一条通知链路"的场景。
 *
 * @author zifang
 */
public interface WfTaskListener {

    /**
     * 任务创建后（任务已落库，token 已挂起）。
     */
    default void onTaskCreated(WfContext context, WfNode node, com.zifang.z.wf.core.model.WfTask task) {
    }

    /**
     * 任务完成前（可通过抛异常或返回 false 阻断）。
     */
    default boolean onBeforeComplete(WfContext context, WfNode node, com.zifang.z.wf.core.model.WfTask task) {
        return true;
    }

    /**
     * 任务完成后。
     */
    default void onAfterComplete(WfContext context, WfNode node, com.zifang.z.wf.core.model.WfTask task) {
    }

    /**
     * 任务被转办/委派/认领/撤回时。
     *
     * @param action {@code transfer} / {@code delegate} / {@code claim} / {@code withdraw}
     */
    default void onAssignmentChanged(WfContext context, WfNode node,
                                     com.zifang.z.wf.core.model.WfTask task, String action) {
    }
}
