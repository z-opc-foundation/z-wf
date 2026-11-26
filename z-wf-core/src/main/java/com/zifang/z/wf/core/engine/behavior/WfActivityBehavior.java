package com.zifang.z.wf.core.engine.behavior;

import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfTask;

/**
 * 节点行为 —— 一类节点类型在运行时的执行契约。
 *
 * <p>引擎（{@link com.zifang.z.wf.core.engine.WfEngine}）只认这个接口，不认具体节点类型。
 * 新增节点类型 = 实现本接口 + 在 {@link WfBehaviorRegistry} 注册，<b>不需要改引擎一行代码</b>。
 *
 * <p>返回值约定：
 * <ul>
 *   <li>{@code userTask/manualTask} 必须返回创建好的 {@link WfTask}（引擎据此挂起 token）</li>
 *   <li>其他类型返回 {@code null}（引擎继续沿出线推进）</li>
 * </ul>
 *
 * <p>实现必须是<b>幂等或可容忍重复调用</b>：审批任务完成后可能因跳转（jump）被重新激活。
 *
 * @author zifang
 */
public interface WfActivityBehavior {

    /**
     * 执行节点。
     *
     * @param context   执行上下文（变量读写、任务/历史登记）
     * @param node      流程定义节点
     * @param execution 当前 token
     * @return 创建的任务（仅任务类节点非 null）
     */
    WfTask execute(WfContext context, WfNode node, WfExecution execution);
}
