package com.zifang.z.wf.core.engine.behavior;

import com.zifang.z.wf.core.definition.WfNode;

/**
 * delegate 解析不出来。
 *
 * <p>单独一个异常类型，而不是复用 {@code WfEngineException}，是为了让
 * {@code WfEngine} 能把"这一步没做"和"这一步的业务代码抛了"分开记：
 * 前者是<b>部署配置问题</b>，应当立刻去改 BPMN；后者是业务运行问题，
 * 可能是数据不对、重试能解决。混成一个异常，日志里就分不出这两种了。
 *
 * @author zifang
 */
public class WfDelegateResolutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WfDelegateResolutionException(String nodeId, WfNode node) {
        super("节点 " + nodeId + "（" + node.getType().bpmnName() + "）的 delegate 解析不到："
                + describe(node)
                + "。这一类错误不会被当成'没有外部逻辑'放过去 —— 放过去意味着这一步"
                + "静默跳过而流程照常完成。请检查类名拼写、类是否实现 WfJavaDelegate、"
                + "以及 delegateExpression 指向的 bean 是否已注册到 WfDelegateRegistry");
    }

    private static String describe(WfNode node) {
        boolean hasExpression = node.getDelegateExpression() != null
                && !node.getDelegateExpression().trim().isEmpty();
        boolean hasClass = node.getDelegateClass() != null
                && !node.getDelegateClass().trim().isEmpty();
        if (hasExpression && hasClass) {
            return "同时配了 delegateExpression=" + node.getDelegateExpression()
                    + " 与 delegateClass=" + node.getDelegateClass();
        }
        if (hasExpression) {
            return "delegateExpression=" + node.getDelegateExpression() + " 未在 WfDelegateRegistry 中注册";
        }
        if (hasClass) {
            return "delegateClass=" + node.getDelegateClass()
                    + " 不是可加载且实现 WfJavaDelegate 的类";
        }
        return "未配置 delegateClass 或 delegateExpression";
    }
}
