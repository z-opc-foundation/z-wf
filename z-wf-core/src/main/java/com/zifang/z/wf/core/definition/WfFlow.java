package com.zifang.z.wf.core.definition;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 流程连线（sequence flow）—— 流程图的有向边。
 *
 * <p>连线携带条件表达式（{@link #conditionExpression}）与默认流标记（{@link #defaultFlow}）。
 * 二者共同决定排他/包容网关的走向：
 * <ul>
 *   <li>排他网关：按声明顺序求值，第一条条件为真的连线被激活；无一条为真时取 {@code defaultFlow}</li>
 *   <li>包容网关：所有条件为真的连线被激活；无一条为真时取 {@code defaultFlow}</li>
 *   <li>并行网关：忽略条件，全部激活</li>
 * </ul>
 *
 * <p>与 z-util-wf-kernel 的 {@code BpmnSequenceFlow} 字段语义完全对齐
 * （{@code sourceRef} / {@code targetRef} / {@code conditionExpression} / {@code default}），
 * 这是两条引擎路径共用协议的字段级保证。
 *
 * @author zifang
 */
public class WfFlow implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 连线 ID（缺省时由两端节点 id 派生，保证可重现）。 */
    private String id;

    /** 连线名称（审批轨迹里展示）。 */
    private String name;

    /** 源节点 ID。 */
    private String sourceRef;

    /** 目标节点 ID。 */
    private String targetRef;

    /**
     * 条件表达式，支持两种写法：
     * <ul>
     *   <li>裸 EL：{@code amount > 1000 && level >= 2}</li>
     *   <li>带壳：{@code ${amount > 1000}}</li>
     * </ul>
     * 由 {@link com.zifang.z.wf.core.engine.expression.WfExpressionEvaluator} 归一化后求值。
     */
    private String conditionExpression;

    /** 是否为默认流。同一网关上至多一条为 true。 */
    private boolean defaultFlow;

    /** 扩展属性（如 LogicFlow 导出的 style / label 等，业务方自定义）。 */
    private Map<String, Object> properties = new HashMap<>();

    public WfFlow() {
    }

    public WfFlow(String sourceRef, String targetRef) {
        this.sourceRef = sourceRef;
        this.targetRef = targetRef;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public String getTargetRef() {
        return targetRef;
    }

    public void setTargetRef(String targetRef) {
        this.targetRef = targetRef;
    }

    public String getConditionExpression() {
        return conditionExpression;
    }

    public void setConditionExpression(String conditionExpression) {
        this.conditionExpression = conditionExpression;
    }

    public boolean isDefaultFlow() {
        return defaultFlow;
    }

    public void setDefaultFlow(boolean defaultFlow) {
        this.defaultFlow = defaultFlow;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties == null ? new HashMap<String, Object>() : properties;
    }

    /**
     * 条件为空时视为"恒成立"。
     *
     * <p>注意：这不是"空条件等于 false"。在排他网关上，把"无条件连线"当 false 会让
     * 只配了一条无条件连线的网关永远走不出去 —— 除非显式声明 defaultFlow。
     */
    public boolean isUnconditional() {
        return conditionExpression == null || conditionExpression.trim().isEmpty();
    }

    @Override
    public String toString() {
        return "WfFlow{" + (id != null ? id : sourceRef + "->" + targetRef)
                + (conditionExpression != null ? " [" + conditionExpression + "]" : "")
                + (defaultFlow ? " (default)" : "") + "}";
    }
}
