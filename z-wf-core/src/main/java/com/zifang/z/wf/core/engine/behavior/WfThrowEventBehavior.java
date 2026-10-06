package com.zifang.z.wf.core.engine.behavior;

import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.engine.WfPendingEvent;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;

/**
 * 中间抛出事件行为 —— 登记一条待投递事件，<b>不建任务、不等待，token 直接往下走</b>。
 *
 * <p>它与 {@link WfServiceTaskBehavior} 的共同点是"穿透"，区别在于本行为由引擎自己投递，
 * 不需要业务方实现 delegate。典型用途是「审批通过时广播一条 {@code approved} 信号」，
 * 让所有在等这个信号的其他流程继续走 —— 这在审批系统里是"一单批准、连带放行"的形态。
 *
 * <p><b>登记而不是当场投递</b>，理由写在 {@link WfPendingEvent} 的类注释里：
 * behavior 处在单实例事务内部，当场投出去会被外层的回写覆盖（丢更新，且不报错）。
 *
 * <p><b>signal 与 message 只能给一个。</b>两个都写时校验器已报 ERROR，
 * 这里仍要再挡一次 —— 那是"配了但绕过了校验"的路径，
 * 而"挑一个生效"会让作者以为自己写的那条生效了。
 *
 * @author zifang
 */
public class WfThrowEventBehavior implements WfActivityBehavior {

    @Override
    public WfTask execute(WfContext context, WfNode node, WfExecution execution) {
        boolean hasSignal = notBlank(node.getSignalName());
        boolean hasMessage = notBlank(node.getMessageName());
        if (hasSignal && hasMessage) {
            throw new WfEngineException("抛事件节点 " + node.getId()
                    + " 同时配了信号 " + node.getSignalName() + " 与消息 " + node.getMessageName()
                    + "。两者语义不同（广播 / 点对点），不能挑一个生效 —— "
                    + "请拆成两个节点");
        }
        if (!hasSignal && !hasMessage) {
            // 不静默当穿透放过：那会让这一步看起来执行成功了，而它其实什么也没发。
            // 现象是"流程跑完了但下游没动"，排查时最难想到的是这一步本身没配事件名
            throw new WfEngineException("抛事件节点 " + node.getId()
                    + " 没有配 signalRef 或 messageRef，抛不出任何东西。"
                    + "请补上事件引用，或改用 sendTask 调业务方实现");
        }
        WfPendingEvent.Kind kind = hasSignal
                ? WfPendingEvent.Kind.SIGNAL : WfPendingEvent.Kind.MESSAGE;
        String eventName = hasSignal ? node.getSignalName() : node.getMessageName();
        WfProcessInstance instance = context.getProcessInstance();
        context.addPendingEvent(new WfPendingEvent(kind, eventName, node.getId(),
                instance == null ? null : instance.getId(),
                instance == null ? null : instance.getVariables()));
        // 返回 null = 不建任务 = 穿透。抛事件不等待任何人。
        return null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}