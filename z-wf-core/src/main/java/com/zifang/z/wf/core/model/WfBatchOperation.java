package com.zifang.z.wf.core.model;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 批次里的一条<b>操作</b>（第 39 轮）——「改什么」。
 *
 * <p>形状与 Camunda 的 {@code BatchOperationDto} 一致：一个带若干可空字段的袋子，
 * 只有 {@link #type} 决定哪些字段有意义。不按 type 拆成七个子类，
 * 是因为这批操作全部是「一个动词 + 少量参数」，拆开之后七份类里各有八九个空字段，
 * 而空字段从不会报错 —— 读代码的人看不出 {@code setPriority} 到底用没用到 {@link #retries}。
 * 用可空字段 + 一处集中校验（{@link #validate}）至少能把「这个字段没填」报成一条明确的话。
 *
 * <p><b>一条操作对每个目标独立成立</b>：{@code setVariable} 里带的是「值」而不是「表达式」，
 * 同一个值写进所有命中目标。Camunda 的批次同样不支持表达式 ——
 * 批次是运维手段（「把这个标记从 approved 换成 status」），不是业务逻辑入口。
 *
 * @author zifang
 */
public class WfBatchOperation implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 支持哪些操作。 */
    public enum Type {

        /** 设一个变量（实例级 / 任务局部）。 */
        SET_VARIABLE("setVariable"),

        /** 设一组变量（仅实例级）。 */
        SET_VARIABLES("setVariables"),

        /** 删一个变量（仅实例级）。 */
        REMOVE_VARIABLE("removeVariable"),

        /** 挂起。 */
        SUSPEND("suspend"),

        /** 激活。 */
        ACTIVATE("activate"),

        /** 设重试次数（实例级 = 该实例名下全部 job；job 级 = 该 job 自身）。 */
        SET_JOB_RETRIES("setJobRetries"),

        /** 设优先级（仅 job 级）。 */
        SET_PRIORITY("setPriority");

        private final String code;

        Type(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }

        public static Type fromName(String name) {
            if (name == null) {
                return null;
            }
            String normalized = name.trim();
            for (Type type : values()) {
                if (type.code.equals(normalized) || type.name().equalsIgnoreCase(normalized)) {
                    return type;
                }
            }
            return null;
        }
    }

    private Type type;

    /** {@link Type#SET_VARIABLE} 的变量名。 */
    private String variable;

    /** {@link Type#SET_VARIABLE} 的变量值。 */
    private Object value;

    /** {@link Type#SET_VARIABLES} 的变量表。用 {@link TreeMap} 让落库的 JSON 字段顺序稳定。 */
    private Map<String, Object> variables = new TreeMap<String, Object>();

    /** {@link Type#SET_JOB_RETRIES} 的目标重试次数。 */
    private Integer retries;

    /** {@link Type#SET_PRIORITY} 的目标优先级。 */
    private Integer priority;

    /** {@link Type#SUSPEND} 的挂起原因。 */
    private String reason;

    public WfBatchOperation() {
    }

    public WfBatchOperation(Type type) {
        this.type = type;
    }

    /**
     * 校验这条操作是否完整，并<b>在用错批次类型时立刻拒绝</b>。
     *
     * <p>为什么必须在创建期就拒：「给 1000 个实例设 job 优先级」这种操作不会报运行时错误 ——
     * 批次会老老实实跑完，一个目标都不改，{@code affectedCount = 0}，
     * 而看记录的人只会以为「这批没命中」。真正拦住它的地方是这里。
     *
     * @param batchType 批次作用的对象类型
     * @return 出错的话（{@code null} 表示没问题）
     */
    public String validate(WfBatch.Type batchType) {
        if (type == null) {
            return "操作缺少 type";
        }
        if (!isApplicableTo(type, batchType)) {
            return "操作 " + type.getCode() + " 不适用于批次类型 " + batchType;
        }
        switch (type) {
            case SET_VARIABLE:
                if (isBlank(variable)) {
                    return "setVariable 缺少 variable";
                }
                // null 在本仓**等于删除**（见 WfVariableService#setVariable）：
                // 放它进来只会在执行期对每一个目标报一次同样的错，
                // 一批 1000 个就是 1000 条一模一样的失败。这里拦住，
                // 让人在创建时就知道该改用 removeVariable
                if (value == null) {
                    return "setVariable 不接受 null 值（本仓 null 即删除），"
                            + "想删掉请用 removeVariable: " + variable;
                }
                return null;
            case SET_VARIABLES:
                if (variables == null || variables.isEmpty()) {
                    return "setVariables 缺少 variables";
                }
                for (Map.Entry<String, Object> entry : variables.entrySet()) {
                    if (isBlank(entry.getKey())) {
                        return "setVariables 里有空变量名";
                    }
                    if (entry.getValue() == null) {
                        return "setVariables 不接受 null 值（本仓 null 即删除）: " + entry.getKey();
                    }
                }
                return null;
            case REMOVE_VARIABLE:
                return isBlank(variable) ? "removeVariable 缺少 variable" : null;
            case SET_JOB_RETRIES:
                if (retries == null) {
                    return "setJobRetries 缺少 retries";
                }
                // 负数重试会让执行器在扫到它时永远失败且不再重试，
                // 而"永久失败"在批次场景下几乎总是笔误 —— 0 才是"不再重试"的正解
                if (retries < 0) {
                    return "setJobRetries 的 retries 不能为负: " + retries;
                }
                return null;
            case SET_PRIORITY:
                if (priority == null) {
                    return "setPriority 缺少 priority";
                }
                // 优先级允许 0（JDK 的 Thread.MIN_PRIORITY），但不允许负数：
                // 它会被原样塞进排序键里，越靠前越先执行，负数会让这一批永远排在最前面
                if (priority < 0) {
                    return "setPriority 的 priority 不能为负: " + priority;
                }
                return null;
            case SUSPEND:
            case ACTIVATE:
            default:
                return null;
        }
    }

    /**
     * 某操作能否作用在某批次类型上。
     *
     * <p>实例级的 {@code setJobRetries} 指「该实例名下<b>全部</b> job」，
     * 与 job 级的「就这一个 job」语义不同，所以两边都合法而行为不同 ——
     * 这不是重复，是两个不同的操作落在两个不同的粒度上。
     */
    private static boolean isApplicableTo(Type operation, WfBatch.Type batchType) {
        if (batchType == null) {
            return true;
        }
        switch (operation) {
            case SET_VARIABLE:
                return batchType == WfBatch.Type.INSTANCE || batchType == WfBatch.Type.TASK;
            case SET_VARIABLES:
            case REMOVE_VARIABLE:
            case SET_JOB_RETRIES:
                // SET_JOB_RETRIES 在实例级也合法（对该实例全部 job 生效）
                return batchType == WfBatch.Type.INSTANCE || batchType == WfBatch.Type.JOB;
            case SUSPEND:
            case ACTIVATE:
                return batchType == WfBatch.Type.INSTANCE || batchType == WfBatch.Type.TASK;
            case SET_PRIORITY:
                return batchType == WfBatch.Type.JOB;
            default:
                return false;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getVariable() {
        return variable;
    }

    public void setVariable(String variable) {
        this.variable = variable;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables == null ? new TreeMap<String, Object>() : variables;
    }

    public Integer getRetries() {
        return retries;
    }

    public void setRetries(Integer retries) {
        this.retries = retries;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    /** 仅供测试与日志：把变量表打成不可变视图，避免调用方改到已入库的批次里。 */
    public Set<String> variableNames() {
        return Collections.unmodifiableSet(
                new HashSet<String>(variables == null
                        ? Collections.<String>emptyList() : variables.keySet()));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("WfBatchOperation{").append(type);
        switch (type == null ? Type.ACTIVATE : type) {
            case SET_VARIABLE:
                sb.append(' ').append(variable).append('=').append(value);
                break;
            case SET_VARIABLES:
                sb.append(' ').append(variables == null ? 0 : variables.size()).append(" 个变量");
                break;
            case REMOVE_VARIABLE:
                sb.append(' ').append(variable);
                break;
            case SET_JOB_RETRIES:
                sb.append(" retries=").append(retries);
                break;
            case SET_PRIORITY:
                sb.append(" priority=").append(priority);
                break;
            default:
                if (isBlank(reason)) {
                    break;
                }
        }
        return sb.append('}').toString();
    }
}