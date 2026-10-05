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
            // 解析不到 delegate 时**必须让流程失败**，不能当成"这一步没有外部逻辑"放过去。
            //
            // 实测过的后果：delegateClass 写错一个字母 → Class.forName 抛
            // ClassNotFoundException → 旧实现只 log.error 然后返回 null →
            // serviceTask 静默退化成穿透 → 流程一路跑到结束、状态 COMPLETED →
            // 审批记录上"通知 HR""写台账"这些步骤全都显示成功，
            // 而那一步压根没有执行。
            //
            // 对审批系统来说这是最坏的一类错：流程看起来完全正常，
            // 真正缺失的是那一步业务动作，而它往往要到几天后对账才发现。
            //
            // 部署期 WfDefinitionValidator 已对"完全没配 delegate"报 ERROR；
            // 运行期再挡一次，是为了覆盖"配了但解析不出来"——
            // 类名拼错、类没实现 WfJavaDelegate、bean 名没注册，
            // 这三种在校验期都发现不了。
            throw new WfDelegateResolutionException(node.getId(), node);
        }
        delegate.execute(context, execution);
        return null;
    }
}
