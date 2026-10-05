package com.zifang.z.wf.starter.sample;

import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.engine.delegate.WfJavaDelegate;
import com.zifang.z.wf.core.model.WfExecution;

/**
 * 五看评估的汇总结论 delegate（示例实现）。
 *
 * <p>演示 serviceTask 的标准写法：读流程变量 → 算 → 写回变量。
 * endEvent 的 {@code resultExpression="${conclusion}"} 会读这里的输出。
 *
 * <p>变量来源约定：每条评分线办结时把 {@code scoreBiz}/{@code scoreFin}/… 写进流程变量
 * （前端办结请求里带 {@code variables} 即可）。
 *
 * @author zifang
 */
public class FiveLookAggregateDelegate implements WfJavaDelegate {

    /** 及格线。 */
    private static final int PASS_LINE = 60;

    @Override
    public void execute(WfContext context, WfExecution execution) {
        int total = 0;
        int count = 0;
        for (String dimension : new String[]{"Biz", "Fin", "Legal", "Risk", "Strategy"}) {
            Object score = context.getVariable("score" + dimension);
            if (score instanceof Number) {
                total += ((Number) score).intValue();
                count++;
            }
        }

        int average = count == 0 ? 0 : total / count;
        context.setVariable("scoreTotal", total);
        context.setVariable("scoreCount", count);
        context.setVariable("scoreAverage", average);
        // endEvent 的 resultExpression 读这个变量
        context.setVariable("conclusion", average >= PASS_LINE ? "approved" : "rejected");
    }
}
