package com.zifang.z.wf.core.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 流程实例状态。
 *
 * <p>与 z-camuda 侧 Camunda 的 ProcessInstance 状态语义对齐（ACTIVE / SUSPENDED / COMPLETED / EXTERNALLY_TERMINATED），
 * 便于业务方从 Camunda 迁移到 z-wf 时查询代码不改。
 *
 * @author zifang
 */
public enum WfProcessStatus {

    /** 运行中（至少有一个活跃 token）。 */
    ACTIVE,

    /** 已挂起（人工或 API 挂起，不推进）。 */
    SUSPENDED,

    /** 正常结束（token 全部抵达 endEvent）。 */
    COMPLETED,

    /** 被外部强制终止。 */
    EXTERNALLY_TERMINATED,

    /**
     * 内部异常终止（引擎执行出错）。
     * <p>与 {@link #EXTERNALLY_TERMINATED} 分开的原因：排障时要能一眼区分
     * "人把它停掉了" 与 "流程定义写错了/服务抛异常"。
     */
    INTERNALLY_TERMINATED;

    /**
     * 是否为终态（不可再推进）。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == EXTERNALLY_TERMINATED || this == INTERNALLY_TERMINATED;
    }

    /**
     * 是否仍在流转（含挂起）。
     */
    public boolean isActive() {
        return this == ACTIVE || this == SUSPENDED;
    }
}
