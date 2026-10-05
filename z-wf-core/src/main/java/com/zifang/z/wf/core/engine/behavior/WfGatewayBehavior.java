package com.zifang.z.wf.core.engine.behavior;

import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfTask;

/**
 * 网关行为 —— 空实现。
 *
 * <p>网关的"选哪条线"是引擎的职责（{@code WfEngine#processGateway}），
 * 因为选线要同时看条件表达式、fork/join 状态和执行树，而不只是节点自身。
 * 把选线塞进 behavior 会迫使 behavior 去访问执行树，破坏"behavior 只管节点自身"这条边界，
 * 也让新增网关类型时无法复用选线逻辑。
 *
 * @author zifang
 */
public class WfGatewayBehavior implements WfActivityBehavior {

    @Override
    public WfTask execute(WfContext context, WfNode node, WfExecution execution) {
        return null;
    }
}
