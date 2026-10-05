package com.zifang.z.wf.core.hook;

import java.util.Map;

/**
 * 任务生命周期钩子 —— 对齐 z-camuda 的 {@code CamudaTaskHook}。
 *
 * <p>比流程钩子多一个 {@link #onAssigneeChanged}，因为审批系统里
 * "任务落到谁头上"的变更是业务上最需要感知的事件（要发通知、要改组织架构缓存）。
 *
 * <p>异常处理约定同 {@link WfProcessHook}：返回 {@code false} 是<b>唯一的阻断方式</b>，
 * 抛异常只记日志。除 {@code onBeforeCreate} / {@code onBeforeComplete} 外都无条件放行。
 *
 * @author zifang
 */
public interface WfTaskHook {

    /**
     * 任务创建前。
     *
     * @return true 继续创建；false 取消创建
     */
    default boolean onBeforeCreate(String taskId, String assignee, Map<String, Object> variables) {
        return true;
    }

    /**
     * 任务创建后。
     */
    default void onAfterCreate(String taskId, String assignee, String processInstanceId) {
    }

    /**
     * 办理人变更（转办 / 委派 / 认领 / 撤回都会触发）。
     *
     * @param action transfer / delegate / claim / withdraw
     */
    default void onAssigneeChanged(String taskId, String fromAssignee, String toAssignee, String action) {
    }

    /**
     * 任务完成前。
     *
     * @return true 继续完成；false 取消完成
     */
    default boolean onBeforeComplete(String taskId, String assignee, Map<String, Object> variables) {
        return true;
    }

    /**
     * 任务完成后。
     */
    default void onAfterComplete(String taskId, String assignee, String outcome) {
    }

    default String hookType() {
        return "wf-task";
    }
}
