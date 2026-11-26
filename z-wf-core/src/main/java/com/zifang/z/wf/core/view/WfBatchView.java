package com.zifang.z.wf.core.view;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 一个批次的对外视图（第 39 轮）。
 *
 * <p><b>不复用 {@link WfBatch} 实体本身</b>：实体里的
 * {@code criteria} / {@code operations} 是<b>给引擎自己再读回去</b>的 JSON 原文。
 * 把它原样吐到接口上，等于把持久化格式变成了对外契约 ——
 * 以后为了加一个字段改一下 JSON 结构，外部调用方的解析就跟着坏。
 * 视图只给「运维需要看到的那几项」。
 *
 * <p>{@code criteria} / {@code operations} 两个字段<b>仍然返回</b>，
 * 因为「这批当初到底是按什么条件、什么操作建的」正是排障时第一个要问的问题，
 * 而且答错的代价很大（看着条件 A，实际改的却是条件 B）。
 *
 * @author zifang
 */
public class WfBatchView implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String batchType;

    private String state;

    /** 状态的中文说法，省得前端各写一份映射表。 */
    private String stateLabel;

    private String criteria;

    private String operations;

    /** 改成功的目标数。 */
    private int affectedCount;

    /** 失败的目标数。 */
    private int failureCount;

    private Date createTime;

    private Date startTime;

    private Date endTime;

    private boolean suspended;

    private String operatorId;

    /** 整批失败的原因；逐个目标的失败原因在 {@link WfBatchElementView} 上。 */
    private String failureReason;

    /**
     * 现在执行会命中多少个目标。
     *
     * <p>可空 —— 它是<b>当场数出来的</b>，要付一次查询的代价，
     * 而列表页不需要每条都数一遍。查这个端点时才有值：
     * {@code GET /api/wf/batches/{id}/count}。
     */
    private Long targetCount;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getBatchType() {
        return batchType;
    }

    public void setBatchType(String batchType) {
        this.batchType = batchType;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getStateLabel() {
        return stateLabel;
    }

    public void setStateLabel(String stateLabel) {
        this.stateLabel = stateLabel;
    }

    public String getCriteria() {
        return criteria;
    }

    public void setCriteria(String criteria) {
        this.criteria = criteria;
    }

    public String getOperations() {
        return operations;
    }

    public void setOperations(String operations) {
        this.operations = operations;
    }

    public int getAffectedCount() {
        return affectedCount;
    }

    public void setAffectedCount(int affectedCount) {
        this.affectedCount = affectedCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getStartTime() {
        return startTime;
    }

    public void setStartTime(Date startTime) {
        this.startTime = startTime;
    }

    public Date getEndTime() {
        return endTime;
    }

    public void setEndTime(Date endTime) {
        this.endTime = endTime;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public void setSuspended(boolean suspended) {
        this.suspended = suspended;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(String operatorId) {
        this.operatorId = operatorId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Long getTargetCount() {
        return targetCount;
    }

    public void setTargetCount(Long targetCount) {
        this.targetCount = targetCount;
    }

    /** 一条批次明细的对外视图。 */
    public static class WfBatchElementView implements Serializable {

        private static final long serialVersionUID = 1L;

        private String id;

        private String targetId;

        private String state;

        private String stateLabel;

        private String failureMessage;

        private Date handledAt;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTargetId() {
            return targetId;
        }

        public void setTargetId(String targetId) {
            this.targetId = targetId;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public String getStateLabel() {
            return stateLabel;
        }

        public void setStateLabel(String stateLabel) {
            this.stateLabel = stateLabel;
        }

        public String getFailureMessage() {
            return failureMessage;
        }

        public void setFailureMessage(String failureMessage) {
            this.failureMessage = failureMessage;
        }

        public Date getHandledAt() {
            return handledAt;
        }

        public void setHandledAt(Date handledAt) {
            this.handledAt = handledAt;
        }
    }

    /** 一个批次的明细页：全量与只失败两种。 */
    public static class WfBatchElementList implements Serializable {

        private static final long serialVersionUID = 1L;

        private String batchId;

        private List<WfBatchElementView> elements = new ArrayList<WfBatchElementView>();

        private int total;

        public String getBatchId() {
            return batchId;
        }

        public void setBatchId(String batchId) {
            this.batchId = batchId;
        }

        public List<WfBatchElementView> getElements() {
            return elements;
        }

        public void setElements(List<WfBatchElementView> elements) {
            this.elements = elements == null ? new ArrayList<WfBatchElementView>() : elements;
        }

        public int getTotal() {
            return total;
        }

        public void setTotal(int total) {
            this.total = total;
        }
    }
}