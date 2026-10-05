package com.zifang.z.wf.core.persistence;

/**
 * 乐观锁冲突异常。
 *
 * <p>抛出它意味着"另一个线程/节点已经改过这条记录了"。
 * 上层（service）应该<b>重试</b>或把冲突信息回给调用方，
 * <b>绝不能</b>静默覆盖 —— 覆盖会丢掉并发审批人的操作，是审批系统最严重的数据丢失形态。
 *
 * @author zifang
 */
public class WfOptimisticLockException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String entityType;
    private final String entityId;
    private final int expectedRevision;

    public WfOptimisticLockException(String entityType, String entityId, int expectedRevision) {
        super("乐观锁冲突: " + entityType + "[" + entityId + "] 期望 revision="
                + expectedRevision + "，但库中已是更新的版本");
        this.entityType = entityType;
        this.entityId = entityId;
        this.expectedRevision = expectedRevision;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public int getExpectedRevision() {
        return expectedRevision;
    }
}
