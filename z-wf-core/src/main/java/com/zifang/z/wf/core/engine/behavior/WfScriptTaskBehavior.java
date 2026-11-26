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
 * <p><b>读的是字段 {@code getResultVariable()} 而不是 {@code property("resultVariable")}</b>
 * —— 后者读的是 {@code properties} 这个扩展 Map，而解析器（XML / JSON 两侧）
 * 与持久化 codec 写的都是<b>字段</b>。两者分家的症状极隐蔽：脚本照常求值、
 * 流程照常穿透过去，只有"结果写到哪"这一件事静默失效，
 * 表现为下游读那个变量时拿到 null，且没有任何报错。
 * （第 22 轮修：这里是全仓唯一读 {@code property("resultVariable")} 的地方。）
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
        Object target = node.getResultVariable();
        if (target != null) {
            context.setVariable(String.valueOf(target), result);
        }
        return null;
    }
}
