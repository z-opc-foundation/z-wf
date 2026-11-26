package com.zifang.z.wf.core.engine.delegate;

import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.model.WfExecution;

/**
 * Java 委托 —— serviceTask 的业务实现契约。
 *
 * <p>对应 z-camuda 侧 Camunda 的 {@code JavaDelegate}，但刻意做小：
 * 只有"执行"一个方法，变量通过 {@link WfContext} 读写。
 *
 * <p>实现类在流程定义里用 {@code zifang:delegateClass} 指定全限定名，
 * 由 {@link com.zifang.z.wf.core.engine.behavior.WfServiceTaskBehavior} 反射实例化。
 *
 * <p>约定：抛异常 ⇒ 节点执行失败 ⇒ 流程被内部终止（{@code INTERNALLY_TERMINATED}）。
 * 想"业务上允许跳过"应该在 delegate 里判断并正常返回，不要靠吞异常。
 *
 * @author zifang
 */
public interface WfJavaDelegate {

    /**
     * 执行业务逻辑。
     *
     * @param context   执行上下文（读写流程变量）
     * @param execution 当前 token
     */
    void execute(WfContext context, WfExecution execution);
}
