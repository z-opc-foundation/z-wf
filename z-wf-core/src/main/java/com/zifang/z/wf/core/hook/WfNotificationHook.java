package com.zifang.z.wf.core.hook;

/**
 * 通知钩子 —— 对齐 z-camuda 的 {@code CamudaNotificationHook}。
 *
 * <p>与任务钩子的分工：任务钩子是"流程状态变了"的信号（要保证执行），
 * 通知钩子是"要发消息给人"的信号（失败只影响送达，不影响流程）。
 * 所以通知钩子的实现<b>应该</b>自己吞掉发送失败（重试/落死信），
 * 而不是让异常冒到引擎 —— 否则一条短信网关抖动会让整条审批流卡住。
 *
 * @author zifang
 */
public interface WfNotificationHook {

    /**
     * 任务分配通知。
     */
    default void notifyTaskAssigned(String taskId, String processInstanceId, String assignee,
                                    String processKey, java.util.Map<String, Object> variables) {
    }

    /**
     * 审批结果通知。
     *
     * @param outcome approved / rejected
     */
    default void notifyApprovalResult(String processInstanceId, String processKey, String applicant,
                                      String outcome, String comment) {
    }

    /**
     * 流程超时通知。
     *
     * @param overdueMinutes 超时分钟数
     */
    default void notifyOverdue(String taskId, String processInstanceId, String assignee,
                               String processKey, long overdueMinutes) {
    }

    default String hookType() {
        return "wf-notification";
    }
}
