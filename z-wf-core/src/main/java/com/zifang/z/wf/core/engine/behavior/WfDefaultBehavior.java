package com.zifang.z.wf.core.engine.behavior;

import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfTask;

/**
 * 兜底行为 —— 直接通过，不建任务。
 *
 * <p>用于未被 {@link com.zifang.z.wf.core.engine.WfBehaviorRegistry} 注册的类型
 * （如 BPMN 里的 transaction / adHocSubProcess / eventBasedGateway）。
 *
 * <p>让未知类型"透明通过"而不是抛异常，是为了让"设计器产出了引擎还不认识的节点"时
 * 流程仍能跑到下一个已支持的节点，而不是整条流程起不来。
 * 代价是未知节点被静默跳过 —— 所以 {@link com.zifang.z.wf.core.engine.WfBehaviorRegistry}
 * 在命中兜底时会打 debug 日志，便于事后发现"这个节点其实没按预期执行"。
 *
 * @author zifang
 */
public class WfDefaultBehavior implements WfActivityBehavior {

    @Override
    public WfTask execute(WfContext context, WfNode node, WfExecution execution) {
        return null;
    }
}
