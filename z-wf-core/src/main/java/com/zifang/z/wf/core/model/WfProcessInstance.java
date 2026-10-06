package com.zifang.z.wf.core.model;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 流程实例 —— 一次流程发起的运行时载体。
 *
 * <p>与 {@link WfExecution} 的分工：实例持有<b>全局</b>状态（业务键、发起人、状态、版本），
 * token 状态放在 {@code WfExecution} 上。两者都带一份 {@code variables}，
 * 实例级的是流程级变量，token 级的是分支局部变量 —— 取值时<b>先查 token 再回落到实例</b>
 * （见 {@link WfContext#getVariable}）。
 *
 * @author zifang
 */
public class WfProcessInstance implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实例 ID。 */
    private String id;

    /** 流程定义 key。 */
    private String definitionKey;

    /** 流程定义 ID（含版本，如 {@code leaveProcess:3}）。 */
    private String definitionId;

    /** 流程定义版本。 */
    private int definitionVersion;

    /** 业务键 —— 业务方用它把流程实例挂到自己的单据上（单号）。 */
    private String businessKey;

    /**
     * 实例名称 —— 列表页与通知里给人看的那句话（「张三的请假申请」），不是给程序用的。
     *
     * <p><b>与 {@link #businessKey} 的分工</b>：businessKey 是<b>业务方</b>给的
     * 单号（对外、要做唯一性校验、要能被业务系统查回来），name 是<b>人或业务方</b>
     * 事后补的一句可读描述（对内、进标题、进邮件）。两者都不强制、都不唯一、都可以为空，
     * 但**不能互相顶替** —— 拿 businessKey 去当标题会得到一串没人看得懂的编号，
     * 而那是业务方自己的单号，不该由引擎替他们决定怎么显示。
     *
     * <p><b>字段所有权：只有 {@code setProcessInstanceName} 改它。</b>
     * {@code saveProcessInstance} 的 UPDATE 是<b>部分更新</b>（只更状态、结果、
     * 变量、原因那几个列），刻意<b>不</b>包含本字段 ——
     * 否则任何一处拿一个"手里没有 name 的旧实例对象"回写状态，都会顺手把名字抹成 null，
     * 而症状是"名字莫名其妙没了"：改名那一刻明明成功过。
     */
    private String name;

    /** 发起人。 */
    private String startUserId;

    /** 发起人所属部门（审批链常用）。 */
    private String startDeptId;

    /** 流程分类（定义上的 category 快照）。 */
    private String category;

    /** 状态。 */
    private WfProcessStatus status = WfProcessStatus.ACTIVE;

    /**
     * 流程结果（如 {@code approved} / {@code rejected}）。
     * <p>由 endEvent 的 {@code resultExpression} 求值得到；正常走到 endEvent 但没有结果表达式时为 {@code completed}。
     */
    private String result;

    /** 启动时间。 */
    private Date startTime;

    /** 结束时间（未结束时为 null）。 */
    private Date endTime;

    /** 流程级变量。 */
    private Map<String, Object> variables = new HashMap<>();

    /** 挂起原因。 */
    private String suspendReason;

    /** 终止/异常原因。 */
    private String deleteReason;

    /** 乐观锁版本号 —— 每次状态变更 +1，防止并发覆盖。 */
    private int revision;

    public WfProcessInstance() {
    }

    public WfProcessInstance(String id, String definitionKey, String definitionId) {
        this.id = id;
        this.definitionKey = definitionKey;
        this.definitionId = definitionId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDefinitionKey() {
        return definitionKey;
    }

    public void setDefinitionKey(String definitionKey) {
        this.definitionKey = definitionKey;
    }

    public String getDefinitionId() {
        return definitionId;
    }

    public void setDefinitionId(String definitionId) {
        this.definitionId = definitionId;
    }

    public int getDefinitionVersion() {
        return definitionVersion;
    }

    public void setDefinitionVersion(int definitionVersion) {
        this.definitionVersion = definitionVersion;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public void setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStartUserId() {
        return startUserId;
    }

    public void setStartUserId(String startUserId) {
        this.startUserId = startUserId;
    }

    public String getStartDeptId() {
        return startDeptId;
    }

    public void setStartDeptId(String startDeptId) {
        this.startDeptId = startDeptId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public WfProcessStatus getStatus() {
        return status;
    }

    public void setStatus(WfProcessStatus status) {
        this.status = status;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
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

    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables == null ? new HashMap<String, Object>() : variables;
    }

    public String getSuspendReason() {
        return suspendReason;
    }

    public void setSuspendReason(String suspendReason) {
        this.suspendReason = suspendReason;
    }

    public String getDeleteReason() {
        return deleteReason;
    }

    public void setDeleteReason(String deleteReason) {
        this.deleteReason = deleteReason;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    /**
     * 乐观锁自增，返回新值。
     */
    public int nextRevision() {
        return ++this.revision;
    }

    /**
     * 计算运行耗时（毫秒）。未结束时按 now 计算。
     */
    public long durationMillis() {
        if (startTime == null) {
            return 0L;
        }
        return (endTime == null ? new Date() : endTime).getTime() - startTime.getTime();
    }

    @Override
    public String toString() {
        return "WfProcessInstance{" + id + " " + definitionKey + " v" + definitionVersion
                + " " + status + (result != null ? " result=" + result : "") + "}";
    }
}
