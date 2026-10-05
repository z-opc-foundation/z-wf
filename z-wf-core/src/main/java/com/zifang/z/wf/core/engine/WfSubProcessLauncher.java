package com.zifang.z.wf.core.engine;

import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.model.WfExecution;

/**
 * 子流程启动器契约 —— 由 {@code WfRuntimeService} 实现。
 *
 * <p>抽成接口是为了打破依赖环：engine 层要能调用"启动子流程"，
 * 但启动子流程需要 repository（查被调用定义）和持久化（建子实例），
 * 而这些都在 service 层。接口让 engine 只依赖契约。
 *
 * @author zifang
 */
public interface WfSubProcessLauncher {

    /**
     * 启动子流程。
     *
     * @param context   父流程上下文（用于继承变量、记录父子关联）
     * @param node      callActivity / subProcess 节点
     * @param execution 父流程当前 token
     * @return 子流程实例 ID；无法启动返回 {@code null}
     */
    String launch(WfContext context, WfNode node, WfExecution execution);
}
