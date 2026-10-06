package com.zifang.z.wf.core.service;

/**
 * 条件式事件「这一次不算它赢」的内部信号。
 *
 * <p><b>它是异常而不是返回值，原因在于调用方本来就分不出来。</b>
 * 投递一条消息给事件网关，结果只有三种：某条分支赢了 / 压根没有人在等 /
 * 有人在等但条件不成立。后两种如果都表现为「没反应」，
 * 投递方（人或上游系统）得到的是同一句话："没有等待消息 X" ——
 * 而这两种情况的处置完全相反：前者要先去建流程，后者要去看那个变量当时是多少。
 *
 * <p>⇒ {@code fireEventGatewayBranch} 在条件不成立时抛这个，
 * 由两条调用路径分别处置：
 * <ul>
 *   <li><b>消息 / 信号投递</b>（{@code fireEventGatewayBranches}）：收集起来，
 *       最终抛一条说清"哪些分支、什么条件、为什么没赢"的错。
 *       不能让它落到"没有等待消息"那句话上 ——
 *       那会把排障方向从「条件不满足」带偏到「没人订阅」。</li>
 *   <li><b>定时器到点</b>（{@code fireTimer}）：静默跳过。
 *       定时器是引擎自己在跑，条件不满足就是"这次到点不算数"，
 *       抛出去只会让一个<b>完全正常</b>的定时器 job 变成失败并重试 ——
 *       而它永远不会成功，重试到耗尽后还会被记成一条故障。</li>
 * </ul>
 *
 * <p>它继承 {@link WfEngineException} 是为了让上层已有的兜底
 * （{@code triggerMessageOrNull} 靠消息文本区分"没人等"）仍然能工作 ——
 * 但它的消息<b>刻意不含</b>"没有等待消息"那几个字。
 *
 * @author zifang
 */
public class WfConditionalEventBlockedException extends WfEngineException {

    private static final long serialVersionUID = 1L;

    private final String nodeId;

    private final String condition;

    public WfConditionalEventBlockedException(String nodeId, String condition, String reason) {
        super("事件分支 " + nodeId + " 的条件不成立（" + condition + "），"
                + "本次事件不算它赢，该分支继续等待。触发来源：" + reason);
        this.nodeId = nodeId;
        this.condition = condition;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getCondition() {
        return condition;
    }
}
