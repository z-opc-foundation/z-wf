package com.zifang.z.wf.core.service;

/**
 * 引擎运行时异常 —— 状态非法（实例已结束、任务已完成、越权办结等）。
 *
 * <p>与 {@link com.zifang.z.wf.core.definition.WfDefinitionException}（定义期问题）
 * 和 {@link com.zifang.z.wf.core.persistence.WfOptimisticLockException}（并发冲突）分开，
 * 因为三者的处置方式不同：定义异常 ⇒ 改流程图；本异常 ⇒ 改调用代码；锁异常 ⇒ 重试。
 *
 * @author zifang
 */
public class WfEngineException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WfEngineException(String message) {
        super(message);
    }

    public WfEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
