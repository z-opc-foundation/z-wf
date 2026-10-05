package com.zifang.z.wf.core.engine.behavior;

import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfTask;

/**
 * 脚本任务行为 —— 执行节点上的 EL 脚本。
 *
 * <p>求值走 z-util 自研的 EL 引擎（{@code WfExpressionEvaluator}），不依赖
 * {@code javax.script} / Nashorn：JDK8 之后 Nashorn 被标记弃用、JDK15 起被移除，
 * 把流程脚本能力绑在它上面等于给自己埋一颗定时炸弹。
 *
 * <p>结果写回：脚本求值结果写入节点属性 {@code resultVariable} 指定的流程变量。
 * 没有该属性时结果丢弃（脚本只用于副作用，如 {@code variables.put(...)} 式赋值）。
 *
 * @author zifang
 */
public class WfScriptTaskBehavior implements WfActivityBehavior {

    @Override
    public WfTask execute(WfContext context, WfNode node, WfExecution execution) {
        String script = node.getScript();
        if (script == null || script.trim().isEmpty()) {
            return null;
        }
        Object result = context.getExpressionEvaluator()
                .evalRaw(script, context.mergedVariables());
        Object target = node.property("resultVariable");
        if (target != null) {
            context.setVariable(String.valueOf(target), result);
        }
        return null;
    }
}
