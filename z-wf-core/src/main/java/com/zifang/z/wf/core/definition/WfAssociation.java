package com.zifang.z.wf.core.definition;

import java.io.Serializable;

/**
 * 关联线（association）—— 补偿机制的那条连线（第 37 轮）。
 *
 * <p>BPMN 里它<b>不是</b> {@link WfFlow}：{@code sequenceFlow} 是流程图上的有向边，
 * token 沿着它走；而 {@code <association>} 把两个节点"关联"起来，**token 永远不沿着它走**。
 * 在补偿里它的作用只有一句话 —— 指出
 * 「这条补偿边界事件被触发时，要执行哪个补偿处理器」：
 *
 * <pre>{@code
 * <serviceTask id="undo" isForCompensation="true" .../>
 * <userTask id="book" ...>
 *   <boundaryEvent id="beComp" attachedToRef="book">
 *     <compensateEventDefinition/>
 *   </boundaryEvent>
 * </userTask>
 * <association id="a1" sourceRef="beComp" targetRef="undo"/>
 * }</pre>
 *
 * <p><b>为什么必须单独建模、不能并进 {@code WfFlow}</b>：并进去就分不出
 * 「token 沿这条边走」与「这条路只在补偿时走」，
 * 而两者在引擎里的处理完全不同（一条进 {@code leave} 的出线选择，
 * 一条进补偿登记）。放进 {@code WfFlow} 会让每个遍历出线的循环都得再判一次类型 ——
 * 漏一处就把补偿处理器当成普通后继节点执行了，而它是没有入线的，
 * 症状是「流程跑着跑着凭空多了一步反向操作」。
 *
 * <p>Camunda 7 只支持一种关联（数据关联另有独立元素），本仓同样只解析补偿这一种；
 * 遇到 {@code <association>} 但两端不是补偿结构时报部署期 ERROR，不静默忽略。
 *
 * @author zifang
 */
public class WfAssociation implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 关联线 ID。 */
    private String id;

    /**
     * 源节点 ID —— 补偿边界事件。
     *
     * <p>方向与直觉相反：{@code sourceRef} 指的是<b>被触发的那条边界事件</b>，
     * {@code targetRef} 才是要执行的补偿处理器。补偿的因果是「事件触发处理器」。
     */
    private String sourceRef;

    /** 目标节点 ID —— 补偿处理器（{@code isForCompensation="true"} 的活动）。 */
    private String targetRef;

    public WfAssociation() {
    }

    public WfAssociation(String id, String sourceRef, String targetRef) {
        this.id = id;
        this.sourceRef = sourceRef;
        this.targetRef = targetRef;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    @Override
    public String toString() {
        return "WfAssociation{" + id + ": " + sourceRef + " -> " + targetRef + "}";
    }
}
