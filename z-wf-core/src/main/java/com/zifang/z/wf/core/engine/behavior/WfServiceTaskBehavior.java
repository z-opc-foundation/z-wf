package com.zifang.z.wf.core.engine.behavior;

import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.engine.delegate.WfJavaDelegate;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.service.WfDelegateRegistry;

/**
 * 服务任务 / 发送任务行为 —— 反射调 {@link WfJavaDelegate}。
 *
 * <p>{@code sendTask}（发通知）与 {@code serviceTask}（调业务）在本引擎里共用实现：
 * 两者都是"同步执行一段外部逻辑然后往下走"，差别只是业务含义。
 * 业务方要做的是实现 {@link WfJavaDelegate}（发钉钉、发短信、写台账都算），
 * 而不是让引擎为 sendTask 单独开一条执行路径。
 *
 * @author zifang
 */
public class WfServiceTaskBehavior implements WfActivityBehavior {

    @Override
    public WfTask execute(WfContext context, WfNode node, WfExecution execution) {
        WfJavaDelegate delegate = context.getDelegateRegistry().resolve(context, node);
        if (delegate == null) {
            // 无 delegate 配置：直接通过。
            // 部署期 WfDefinitionValidator 已对 serviceTask 缺 delegate 报 ERROR，
            // 这里仍不抛异常是为了让"跳过校验、纯开发态"也能跑起来。
            return null;
        }
        delegate.execute(context, execution);
        return null;
    }
}
