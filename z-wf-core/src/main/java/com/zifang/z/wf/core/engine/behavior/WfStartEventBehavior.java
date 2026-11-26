package com.zifang.z.wf.core.engine.behavior;

import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfTask;

/**
 * 开始事件行为 —— 进入即通过，不建任务。
 *
 * <p>startEvent 本身没有业务语义，它只是流程入口标记。真正的"发起动作"由
 * {@code WfRuntimeService#startProcessInstance} 承担（写业务键、触发流程钩子）。
 *
 * @author zifang
 */
public class WfStartEventBehavior implements WfActivityBehavior {

    @Override
    public WfTask execute(WfContext context, WfNode node, WfExecution execution) {
        return null;
    }
}
