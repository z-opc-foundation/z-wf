package com.zifang.z.wf.core.engine.behavior;

import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfTask;

/**
 * 链接捕获事件行为 —— 什么都不做，直接通过。
 *
 * <p>它与 {@link WfDefaultBehavior} 的差别只有一处，但那一处值得单独一个类：
 * <b>显式注册</b>与<b>落到兜底</b>在运行结果上完全一样（都返回 {@code null}），
 * 而排障时的含义完全不同。落到兜底意味着"这个类型没人管"，症状是
 * 流程图上有个节点的行为从没被实现过；显式注册则说明"这个节点就是穿透，
 * 这是它的全部语义"。前者会让人去查引擎，后者不用查。
 *
 * <p><b>为什么它真的是穿透，而不是"等一个事件"</b>：link catch 等的是
 * 图上另一个节点，而那个跳转由 {@code WfEngine#jumpToLinkCatch} 在引擎内部
 * <b>同步</b>完成 —— token 抵达这一格的那一刻，跳转已经发生了。
 * 把它做成 {@link WfIntermediateCatchEvent} 那种"停下挂订阅"的形态，
 * 得到的会是一个永远等不到触发、也不报错的哑订阅。
 *
 * <p>返回 {@code null} 的语义在 {@code WfEngine#enter} 的「其他节点」分支里是
 * 「行为方决定不建任务、直接通过」，随后 token 沿本节点自己的出线离开。
 *
 * @author zifang
 */
public class WfLinkCatchBehavior implements WfActivityBehavior {

    @Override
    public WfTask execute(WfContext context, WfNode node, WfExecution execution) {
        return null;
    }
}