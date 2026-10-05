package com.zifang.z.wf.core.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 钩子分发器 —— 隔离钩子异常的唯一入口。
 *
 * <p><b>核心规则：钩子的异常绝不能影响流程状态。</b>
 * 所有 {@code after*}/{@code notify*} 类回调都包在 try-catch 里，只记日志。
 * 只有两个"前置校验"类回调允许影响流程：
 * <ul>
 *   <li>返回 {@code false} ⇒ 取消本次动作（这是<b>显式</b>否决，语义清晰）</li>
 *   <li>抛异常 ⇒ <b>按"不否决"处理</b>，只记日志</li>
 * </ul>
 *
 * <p>为什么异常不否决：如果钩子抛异常就阻断流程，那么"通知服务临时不可用"会
 * 让用户连审批都提交不了。业务上"宁可少一条通知，不能少一次审批"。
 * 钩子实现方要否决某个动作，应当<b>返回 false</b> 而不是抛异常 ——
 * 抛异常表达的是"我没意见，但出错了"，不是"我反对"。
 *
 * <p>钩子按注册顺序串行执行；前一个返回 false 即短路，不再执行后续钩子。
 *
 * @author zifang
 */
public class WfHookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WfHookDispatcher.class);

    private final List<WfProcessHook> processHooks = new ArrayList<>();

    private final List<WfTaskHook> taskHooks = new ArrayList<>();

    private final List<WfNotificationHook> notificationHooks = new ArrayList<>();

    // ==================== 注册 ====================

    public void addProcessHook(WfProcessHook hook) {
        if (hook != null) {
            processHooks.add(hook);
        }
    }

    public void addTaskHook(WfTaskHook hook) {
        if (hook != null) {
            taskHooks.add(hook);
        }
    }

    public void addNotificationHook(WfNotificationHook hook) {
        if (hook != null) {
            notificationHooks.add(hook);
        }
    }

    public List<WfProcessHook> getProcessHooks() {
        return new ArrayList<>(processHooks);
    }

    public List<WfTaskHook> getTaskHooks() {
        return new ArrayList<>(taskHooks);
    }

    /**
     * 注销任务钩子。
     *
     * <p>只增不减的注册表会出两类问题：一是长期运行的进程里反复注册同一个钩子导致回调翻倍；
     * 二是无法在运行期切换实现（比如按配置决定是否启用某个审计钩子）。
     *
     * @return 确实被移除的钩子个数
     */
    public int removeTaskHook(WfTaskHook hook) {
        return taskHooks.remove(hook) ? 1 : 0;
    }

    /** 清空任务钩子（测试收尾用）。 */
    public void clearTaskHooks() {
        taskHooks.clear();
    }

    // ==================== 流程钩子 ====================

    /**
     * 流程启动前。任一钩子返回 false 即取消。
     */
    public boolean fireBeforeStart(String definitionKey, Map<String, Object> variables) {
        for (WfProcessHook hook : processHooks) {
            try {
                if (!hook.onBeforeStart(definitionKey, variables)) {
                    log.info("流程启动被 {} 钩子否决: {}", hook.getClass().getSimpleName(), definitionKey);
                    return false;
                }
            } catch (Exception e) {
                // 异常 ≠ 否决：记日志后继续
                log.error("流程启动前钩子异常（已忽略）: {}", hook.getClass().getName(), e);
            }
        }
        return true;
    }

    public void fireAfterStart(String definitionKey, String processInstanceId, Map<String, Object> variables) {
        for (WfProcessHook hook : processHooks) {
            try {
                hook.onAfterStart(definitionKey, processInstanceId, variables);
            } catch (Exception e) {
                log.error("流程启动后钩子异常（已忽略）: {}", hook.getClass().getName(), e);
            }
        }
    }

    public void fireComplete(String definitionKey, String processInstanceId, String outcome) {
        for (WfProcessHook hook : processHooks) {
            try {
                hook.onComplete(definitionKey, processInstanceId, outcome);
            } catch (Exception e) {
                log.error("流程完成钩子异常（已忽略）: {}", hook.getClass().getName(), e);
            }
        }
    }

    // ==================== 任务钩子 ====================

    public boolean fireBeforeCreate(String taskId, String assignee, Map<String, Object> variables) {
        for (WfTaskHook hook : taskHooks) {
            try {
                if (!hook.onBeforeCreate(taskId, assignee, variables)) {
                    log.info("任务创建被 {} 钩子否决: {}", hook.getClass().getSimpleName(), taskId);
                    return false;
                }
            } catch (Exception e) {
                log.error("任务创建前钩子异常（已忽略）: {}", hook.getClass().getName(), e);
            }
        }
        return true;
    }

    public void fireAfterCreate(String taskId, String assignee, String processInstanceId) {
        for (WfTaskHook hook : taskHooks) {
            try {
                hook.onAfterCreate(taskId, assignee, processInstanceId);
            } catch (Exception e) {
                log.error("任务创建后钩子异常（已忽略）: {}", hook.getClass().getName(), e);
            }
        }
    }

    public void fireAssigneeChanged(String taskId, String from, String to, String action) {
        for (WfTaskHook hook : taskHooks) {
            try {
                hook.onAssigneeChanged(taskId, from, to, action);
            } catch (Exception e) {
                log.error("办理人变更钩子异常（已忽略）: {}", hook.getClass().getName(), e);
            }
        }
    }

    public boolean fireBeforeComplete(String taskId, String assignee, Map<String, Object> variables) {
        for (WfTaskHook hook : taskHooks) {
            try {
                if (!hook.onBeforeComplete(taskId, assignee, variables)) {
                    log.info("任务完成被 {} 钩子否决: {}", hook.getClass().getSimpleName(), taskId);
                    return false;
                }
            } catch (Exception e) {
                log.error("任务完成前钩子异常（已忽略）: {}", hook.getClass().getName(), e);
            }
        }
        return true;
    }

    public void fireAfterComplete(String taskId, String assignee, String outcome) {
        for (WfTaskHook hook : taskHooks) {
            try {
                hook.onAfterComplete(taskId, assignee, outcome);
            } catch (Exception e) {
                log.error("任务完成后钩子异常（已忽略）: {}", hook.getClass().getName(), e);
            }
        }
    }

    // ==================== 通知钩子 ====================

    public void notifyTaskAssigned(String taskId, String processInstanceId, String assignee,
                                   String processKey, Map<String, Object> variables) {
        for (WfNotificationHook hook : notificationHooks) {
            try {
                hook.notifyTaskAssigned(taskId, processInstanceId, assignee, processKey, variables);
            } catch (Exception e) {
                log.error("任务分配通知失败（已忽略）: {}", hook.getClass().getName(), e);
            }
        }
    }

    public void notifyApprovalResult(String processInstanceId, String processKey, String applicant,
                                     String outcome, String comment) {
        for (WfNotificationHook hook : notificationHooks) {
            try {
                hook.notifyApprovalResult(processInstanceId, processKey, applicant, outcome, comment);
            } catch (Exception e) {
                log.error("审批结果通知失败（已忽略）: {}", hook.getClass().getName(), e);
            }
        }
    }

    public void notifyOverdue(String taskId, String processInstanceId, String assignee,
                              String processKey, long overdueMinutes) {
        for (WfNotificationHook hook : notificationHooks) {
            try {
                hook.notifyOverdue(taskId, processInstanceId, assignee, processKey, overdueMinutes);
            } catch (Exception e) {
                log.error("超时通知失败（已忽略）: {}", hook.getClass().getName(), e);
            }
        }
    }
}
