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

    /**
     * 任务消失 —— 被边界事件打断作废、流程终止收尾、会签闭合后作废剩余实例。
     *
     * <p>与 {@link #onAfterComplete} 是<b>互斥</b>的两条路：办结了走前者，
     * 没办结就没了走这条。"这单怎么没的"在审批场景里是最常被追问的事，
     * 而没有这个钩子时唯一的查法是翻轨迹 —— 而轨迹记的是节点不是任务。
     *
     * <p><b>撤回不在这里</b>：撤回把任务放回待办（{@code Status.CREATED}），
     * 任务并没有消失，它走的是 {@link #onAssigneeChanged}。
     * 把它混进来会让"任务消失"这个统计算上大量正常的退回，指标直接失去意义。
     *
     * @param reason 消失的原因（boundary-interrupted / terminated /
     *               multi-instance-closed）
     */
    default void onDeleted(String taskId, String assignee, String processInstanceId,
                           String reason) {
    }

    default String hookType() {
        return "wf-task";
    }
}
