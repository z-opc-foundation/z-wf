package com.zifang.z.wf.core.model;

import java.io.Serializable;
import java.util.Date;

/**
 * 一个<b>批量操作批次</b>（第 39 轮）—— Camunda 的 {@code Batch}。
 *
 * <p>它解决的是一类本仓此前完全没有的运维需求：<b>数据迁移</b>。
 * 典型场景是「旧流程用 {@code approved} 布尔判断，新流程要读 {@code status} 枚举」——
 * 一次要把十万个在跑的实例改过来。逐个开 API 改是不可能的，
 * 而这些实例此刻还在被人正常办理，改量必须可控、可回查、单个失败不影响其余。
 *
 * <p><b>两段式</b>：创建（把「改哪些」与「改什么」固化下来）与执行（真正动手）。
 * 分成两段不是为了学 Camunda，而是因为<b>看到要改什么、再决定改不改</b>本身是需求 ——
 * 一条「删掉所有超时任务」的操作，确认之前不该有任何数据被动。
 *
 * <p>执行时<b>逐个目标独立成败</b>（{@link WfBatchElement}），一批 1000 个里
 * 失败了 3 个，另外 997 个照样改完，且失败原因逐条可查。
 * 不这么做的后果是运维只能整批回滚 —— 而其中 997 个其实是对的。
 *
 * <p><b>执行是同步的</b>（Camunda 的 {@code executeBatch} 默认异步落一条 batch job）。
 * 这里不跟它对齐，原因是本仓的 {@code ZWF_JOB.PROC_ID} 是 {@code NOT NULL} ——
 * job 必须挂在某个流程实例上，而批次不属于任何流程实例，把它塞进去只能靠一个假的实例 id，
 * 那是造假而不是实现。要么把该列放宽成可空并为已建表做一次改写，成本与风险都远超这一轮该付的。
 * 代价如实记着：目标极多时单次执行会长时间占着调用线程。缺口记在
 * {@code docs/capability-gap.md}。
 *
 * @author zifang
 */
public class WfBatch implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 批次状态。 */
    public enum State {
        /** 已创建、尚未执行。 */
        CREATED("已创建"),
        /** 执行中。 */
        EXECUTING("执行中"),
        /** 执行完毕；成败看 {@link WfBatchElement}。 */
        COMPLETED("已完成"),
        /**
         * 执行期出错，整个批次中止。
         *
         * <p><b>已经改掉的目标不会回退</b>：中止发生在中途，而批次没有「撤销」这回事。
         * 改成什么样照旧，改成功了几个、以及它们是谁，都留在 {@link WfBatchElement} 里 ——
         * 运维据此手工补齐剩下的，而不是靠状态去猜。
         */
        FAILED("失败");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** 批次作用的对象类型。 */
    public enum Type {
        /** 流程实例。 */
        INSTANCE("流程实例"),
        /** 任务。 */
        TASK("任务"),
        /** Job（定时器 / 消息订阅 / 外部任务）。 */
        JOB("job");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        /**
         * 按名字解析。
         *
         * <p><b>不认得就抛</b>，与 {@link WfFilterType#parse} 同一把尺子。
         * 这条方法有两条调用路径，两条都要求"不认得"必须暴露：
         * 一条是 REST 入参（报错总比"当成不过滤"好，后者会让一个 typo
         * 悄悄变成"改全部"）；另一条是<b>从库里读回一行</b>——
         * 那里遇到认不出的值是数据已损坏，返回 null 会让这行在后面某处
         * 变成一个查不出批次、也查不出实例的空壳。
         *
         * <p>只有 {@code null}（压根没给）才返回 {@code null}。
         */
        public static Type fromName(String name) {
            if (name == null || name.trim().isEmpty()) {
                return null;
            }
            String normalized = name.trim();
            for (Type type : values()) {
                if (type.name().equalsIgnoreCase(normalized)) {
                    return type;
                }
            }
            // 允许 "processInstance" 这种更长的写法：REST 的入参往往照抄 Camunda 文档
            if ("processinstance".equalsIgnoreCase(normalized)
                    || "instance".equalsIgnoreCase(normalized)) {
                return INSTANCE;
            }
            throw new IllegalArgumentException(
                    "未知的批次类型 [" + name + "]。合法值: " + allNames());
        }

        private static String allNames() {
            StringBuilder sb = new StringBuilder();
            for (Type type : values()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(type.name());
            }
            return sb.append("（另接受 processInstance / instance）").toString();
        }
    }

    private String id;

    private Type batchType;

    /**
     * 筛选条件（{@link WfBatchCriteria} 的 JSON 形式）。
     *
     * <p><b>存成文本而不是引用一个筛选器</b>：批次是「一次性的决定」，
     * 复用可变的筛选器会让「三天后执行这批」按三天后的定义去找目标。
     */
    private String criteria;

    /**
     * 操作列表（{@link WfBatchOperation} 数组的 JSON 形式）。
     *
     * <p>同样存文本：批次一旦创建，「改什么」就不该再随别处改动而变。
     */
    private String operations;

    private State state = State.CREATED;

    /** 命中并处理过的目标数。 */
    private int affectedCount;

    /** 其中失败的目标数。 */
    private int failureCount;

    private Date createTime;

    private Date startTime;

    private Date endTime;

    /** 排队期间置 true 可阻止执行器接手。 */
    private boolean suspended;

    private String operatorId;

    /** 批次整体失败的原因（单个目标失败记在 {@link WfBatchElement} 上）。 */
    private String failureReason;

    private int revision;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Type getBatchType() {
        return batchType;
    }

    public void setBatchType(Type batchType) {
        this.batchType = batchType;
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

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
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

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    /**
     * 乐观锁版本 +1，语义与 {@code WfTask} / {@code WfJob} 完全一致：
     * <b>改既有批次前必须先调它</b>，使传入对象的 revision 恰好是库里那份 +1。
     * 漏调得到的是乐观锁冲突 —— 那是契约在工作，不是它坏了。
     *
     * <p>批次执行期会被改两遍（{@code EXECUTING} → {@code COMPLETED}），
     * 版本号正是为了在这种时候抓住「有人同时动它」。
     */
    public void nextRevision() {
        this.revision = revision + 1;
    }

    @Override
    public String toString() {
        return "WfBatch{" + id + ": " + batchType + " " + state
                + " affected=" + affectedCount + " failed=" + failureCount + "}";
    }
}
