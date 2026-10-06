package com.zifang.z.wf.core.engine.dmn;

import com.zifang.z.wf.core.service.WfEngineException;

/**
 * 决策表<b>被违反</b> —— 表本身写错了，或者输出项求不出值。
 *
 * <p><b>为什么不并进普通运行时异常</b>：调用方拿到"决策算不出来"时，
 * 第一反应会去看输入数据；而这里的真相通常是"这张表的两条规则互相重叠了"，
 * 去看输入永远看不出来。分一个类型就是为了让它在日志和接口里都能被单独认出来。
 *
 * <p>与"没有任何规则命中"不同：那是<b>合法结果</b>（UNIQUE 允许 0 命中），
 * 由 {@link WfDmnDecisionResult#isNoMatch()} 表达，不抛异常。
 *
 * <p><b>但它挂在 {@link WfEngineException} 底下</b>：REST 层按异常类型映射
 * 状态码，没有 handler 的类型会落成 500 —— 而"这张表写错了"是调用方改表就能解决的
 * 400 级问题，报 500 会让前端一律弹"系统错误"。
 * 继承同时也让任何 catch {@code WfEngineException} 的调用方天然接得住它。
 *
 * @author zifang
 */
public class WfDmnViolationException extends WfEngineException {

    private static final long serialVersionUID = 1L;

    public WfDmnViolationException(String message) {
        super(message);
    }

    public WfDmnViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}