package com.zifang.z.wf.core.engine.behavior;

import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfTask;

/**
 * 结束事件行为 —— 只登记历史，token 收尾由引擎处理。
 *
 * <p>{@link WfNode#getResultExpression()} 的求值同样在引擎里做（它决定流程实例的
 * {@code result} 字段，如 approved / rejected），本 behavior 不重复求值。
 *
 * @author zifang
 */
public class WfEndEventBehavior implements WfActivityBehavior {

    @Override
    public WfTask execute(WfContext context, WfNode node, WfExecution execution) {
        return null;
    }
}
