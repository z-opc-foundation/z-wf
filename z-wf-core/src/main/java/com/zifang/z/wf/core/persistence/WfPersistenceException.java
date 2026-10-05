package com.zifang.z.wf.core.persistence;

/**
 * 持久化层异常（SQL 执行失败等）。
 *
 * <p>与 {@link WfOptimisticLockException} 分开：前者是"存储不可用/语句错"（该重试或告警），
 * 后者是"并发冲突"（该重新读取后重试）。合并会让上层无法区分"该重试"与"该报警"。
 *
 * @author zifang
 */
public class WfPersistenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WfPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public WfPersistenceException(String message) {
        super(message);
    }
}
