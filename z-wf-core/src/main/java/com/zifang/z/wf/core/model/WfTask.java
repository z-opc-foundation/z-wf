package com.zifang.z.wf.core.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批任务。
 *
 * <p>任务状态机（对应 z-camuda 暴露的 transfer / delegate / claim / withdraw 四类操作）：
 * <pre>
 *                    claim / assign
 *   CREATED ───────────────────────────► ASSIGNED
 *      │                                    │
 *      │ delegate（委派：交出处理权，        │ complete
 *      │ 处理人变 owner，但办结人记 delegate）│
 *      ▼                                    ▼
 *  DELEGATED ────────────────────────► COMPLETED
 *      │ resolve（原处理人收回）
 *      ▼
 *  ASSIGNED
 *
 *   ASSIGNED/DELEGATED ── withdraw（撤回：发起人收回未办结任务）──► CREATED
 *   ASSIGNED/DELEGATED ── cancel（作废）────────────────────────► CANCELLED
 * </pre>
 *
 * <p>与 Camunda 的差异说明：Camunda 的 delegate 是"父任务 + 子任务"两层（父任务委派给 owner，
 * 子任务由 owner 处理），完结子任务后父任务回到 assignee。z-wf 采用<b>单任务 + owner/assignee 双字段</b>：
 * 委派后 owner 拿到处理权但办结人仍是 assignee，责任归属不丢。
 * 好处是待办列表查询只需一张表，坏处是不支持"层层往下委派"——如果业务需要多层委派，
 * 用 {@link #delegateChain} 记录委派链即可，不必引入父子任务表。
 *
 * @author zifang
 */
public class WfTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务状态。
     */
    public enum Status {
        /** 已创建，未分配或待认领。 */
        CREATED,
        /** 已分配，待办理。 */
        ASSIGNED,
        /** 已委派：{@link #owner} 有处理权。 */
        DELEGATED,
        /** 已完成。 */
        COMPLETED,
        /** 已作废（跳转跳过 / 流程终止时清理未办结任务）。 */
        CANCELLED
    }

    private String id;

    /** 所属流程实例。 */
    private String processInstanceId;

    /** 产生该任务的 token。 */
    private String executionId;

    /** 流程定义节点 id。 */
    private String definitionId;

    /** 任务名称。 */
    private String name;

    /** 任务类型（来自节点的 BPMN type）。 */
    private String type;

    /** 表单编码。 */
    private String formKey;

    /** 任务分类。 */
    private String category;

    /** 办理人（责任人，委派时不改变）。 */
    private String assignee;

    /** 当前处理人（委派后是 owner）。 */
    private String owner;

    /** 候选人（可认领）。 */
    private List<String> candidateUsers = new ArrayList<>();

    /** 候选组（可认领）。 */
    private List<String> candidateGroups = new ArrayList<>();

    /** 状态。 */
    private Status status = Status.CREATED;

    /** 优先级。 */
    private int priority = 50;

    /** 创建时间。 */
    private Date createTime;

    /** 到期时间（由节点 dueDate + 流程启动时间算出）。 */
    private Date dueDate;

    /** 完成时间。 */
    private Date endTime;

    /** 办结人。 */
    private String completerId;

    /** 审批意见。 */
    private String comment;

    /** 任务级变量。 */
    private Map<String, Object> variables = new HashMap<>();

    /**
     * 委派链（最近 → 最早），记录每一跳的 (from, to, time)。
     * <p>单任务模型的补偿：让"谁把活转给谁"可审计，而不引入父子任务。
     */
    private List<DelegateHop> delegateChain = new ArrayList<>();

    /**
     * 父任务 id（由子流程/多实例产生时非空）。
     */
    private String parentTaskId;

    /**
     * 是否已挂起。挂起后该任务<b>不能被办理</b>（认领 / 办结 / 转办 / 委派 / 撤回 / 跳转全拒），
     * 但仍留在待办列表里、也仍计入未完成统计。
     *
     * <p>刻意<b>不</b>藏进待办列表：挂起常是"等某个条件成立"而不是"这张单不存在"，
     * 藏起来的话用户会以为单子丢了，反而要去问"我那张单呢"。
     * 要只看未挂起的，查询时显式传 {@code suspendedOnly=false}。
     *
     * <p>与流程实例的挂起是两件事：实例挂起停的是整个流程，任务挂起只停这一张待办。
     */
    private boolean suspended;

    /**
     * 乐观锁版本号。
     */
    private int revision;

    public WfTask() {
    }

    /**
     * 委派一跳的记录。
     */
    public static class DelegateHop implements Serializable {

        private static final long serialVersionUID = 1L;

        private String from;
        private String to;
        private Date time;

        public DelegateHop() {
        }

        public DelegateHop(String from, String to, Date time) {
            this.from = from;
            this.to = to;
            this.time = time;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String to) {
            this.to = to;
        }

        public Date getTime() {
            return time;
        }

        public void setTime(Date time) {
            this.time = time;
        }
    }

    /**
     * 当前可办理人：有 owner 就是 owner（委派态），否则是 assignee。
     * <p>待办列表与权限校验统一走这个方法，避免各处"到底该用哪个字段"判断不一致。
     */
    public String effectiveHandler() {
        return owner != null && !owner.trim().isEmpty() ? owner : assignee;
    }

    /**
     * 是否处于活跃待办态（参与待办统计、可被办理/转办）。
     */
    public boolean isOpen() {
        return status == Status.CREATED || status == Status.ASSIGNED || status == Status.DELEGATED;
    }

    /**
     * 是否被办理：未挂起且处于开放状态。
     * 各处闸门一律用这个判据，别各自写 {@code isOpen() && !isSuspended()} ——
     * 散着写总有一处会漏。
     */
    public boolean isOperable() {
        return isOpen() && !suspended;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public void setSuspended(boolean suspended) {
        this.suspended = suspended;
    }

    public boolean isOverdue() {
        return dueDate != null && isOpen() && new Date().after(dueDate);
    }

    /**
     * 指定用户是否有资格认领本任务。
     */
    public boolean isClaimableBy(String userId, List<String> userGroups) {
        if (!isOpen() || userId == null) {
            return false;
        }
        if (assignee != null && !assignee.trim().isEmpty()) {
            return false;
        }
        if (candidateUsers.contains(userId)) {
            return true;
        }
        if (userGroups != null && !userGroups.isEmpty()) {
            for (String group : candidateGroups) {
                if (userGroups.contains(group)) {
                    return true;
                }
            }
        }
        return false;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public void setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getDefinitionId() {
        return definitionId;
    }

    public void setDefinitionId(String definitionId) {
        this.definitionId = definitionId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getFormKey() {
        return formKey;
    }

    public void setFormKey(String formKey) {
        this.formKey = formKey;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getAssignee() {
        return assignee;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public List<String> getCandidateUsers() {
        return candidateUsers;
    }

    public void setCandidateUsers(List<String> candidateUsers) {
        this.candidateUsers = candidateUsers == null ? new ArrayList<String>() : candidateUsers;
    }

    public List<String> getCandidateGroups() {
        return candidateGroups;
    }

    public void setCandidateGroups(List<String> candidateGroups) {
        this.candidateGroups = candidateGroups == null ? new ArrayList<String>() : candidateGroups;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getDueDate() {
        return dueDate;
    }

    public void setDueDate(Date dueDate) {
        this.dueDate = dueDate;
    }

    public Date getEndTime() {
        return endTime;
    }

    public void setEndTime(Date endTime) {
        this.endTime = endTime;
    }

    public String getCompleterId() {
        return completerId;
    }

    public void setCompleterId(String completerId) {
        this.completerId = completerId;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables == null ? new HashMap<String, Object>() : variables;
    }

    public List<DelegateHop> getDelegateChain() {
        return delegateChain;
    }

    public void setDelegateChain(List<DelegateHop> delegateChain) {
        this.delegateChain = delegateChain == null ? new ArrayList<DelegateHop>() : delegateChain;
    }

    public String getParentTaskId() {
        return parentTaskId;
    }

    public void setParentTaskId(String parentTaskId) {
        this.parentTaskId = parentTaskId;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public int nextRevision() {
        return ++this.revision;
    }

    @Override
    public String toString() {
        return "WfTask{" + id + " " + name + " " + status
                + " assignee=" + assignee + (owner != null ? " owner=" + owner : "") + "}";
    }
}
