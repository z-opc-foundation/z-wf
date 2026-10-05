package com.zifang.z.wf.core.engine.behavior;

import java.util.Date;

import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfTask;

/**
 * 接收任务行为 —— 建任务、挂起 token，等外部消息触发。
 *
 * <p>与 {@link WfUserTaskBehavior} 的区别：接收任务<b>没有候选人也没有 assignee</b>，
 * 它等待的是"消息"而不是"人"。所以任务落在 {@link WfTask.Status#CREATED} 态，
 * 由 {@code WfRuntimeService#triggerMessage(messageName, variables)} 唤醒，
 * 按 {@link WfNode#getMessageName()} 匹配。
 *
 * <p>没有 messageName 时校验器会给 WARN（不阻断）—— 任务仍能建出来，
 * 但只能靠 force-complete 推进。这是刻意的：设计器可能还没配消息名就保存了，
 * 阻断部署会让"先画完图再填消息名"这条正常路径走不通。
 *
 * @author zifang
 */
public class WfReceiveTaskBehavior implements WfActivityBehavior {

    @Override
    public WfTask execute(WfContext context, WfNode node, WfExecution execution) {
        WfTask task = new WfTask();
        task.setProcessInstanceId(context.getProcessInstanceId());
        task.setExecutionId(execution.getId());
        task.setDefinitionId(node.getId());
        task.setName(node.getName());
        task.setType(node.getType().bpmnName());
        task.setCategory(node.getMessageName() != null ? node.getMessageName() : node.getCategory());
        task.setCreateTime(new Date());
        task.setStatus(WfTask.Status.CREATED);
        return task;
    }
}
