package com.zifang.z.wf.core.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 批次里的<b>一个目标</b>及其成败（第 39 轮）。
 *
 * <p>这一行是「<b>逐个目标独立成败</b>」这条设计的落点。
 * 一批 1000 个实例里失败了 3 个：另外 997 个照常改完，
 * 失败的 3 个各占一行并带上失败原因。没有这张表的话只剩两个选择 ——
 * 整批回滚（可那 997 个其实是对的），或者整批算成功（那 3 个的失败就永远丢了）。
 *
 * <p><b>失败必须逐条落库，不能只记一个总数</b>：
 * 「3 个失败」这个数字没法回答「哪 3 个」「为什么」，
 * 而运维点开一个失败批次时问的恰恰是后两个问题。
 *
 * @author zifang
 */
public class WfBatchElement implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 单个目标的处理结果。 */
    public enum State {
        /** 已改成功。 */
        SUCCESS("成功"),
        /** 改失败，原因见 {@link WfBatchElement#getFailureMessage()}。 */
        FAILED("失败");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private String id;

    private String batchId;

    /** 目标类型（冗余存一份，让「这个批次里失败的都是什么」不必回表）。 */
    private WfBatch.Type type;

    /** 目标的 id（实例 id / 任务 id / job id）。 */
    private String targetId;

    private State state;

    /** 失败原因；{@link State#FAILED} 时有值。 */
    private String failureMessage;

    private Date handledAt;

    public WfBatchElement() {
    }

    public WfBatchElement(String batchId, WfBatch.Type type, String targetId,
                          State state, String failureMessage, Date handledAt) {
        this.batchId = batchId;
        this.type = type;
        this.targetId = targetId;
        this.state = state;
        this.failureMessage = failureMessage;
        this.handledAt = handledAt;
    }

    public boolean isSuccess() {
        return state == State.SUCCESS;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public WfBatch.Type getType() {
        return type;
    }

    public void setType(WfBatch.Type type) {
        this.type = type;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
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

    @Override
    public String toString() {
        return "WfBatchElement{" + targetId + " " + state
                + (failureMessage == null ? "" : " (" + failureMessage + ")") + "}";
    }
}