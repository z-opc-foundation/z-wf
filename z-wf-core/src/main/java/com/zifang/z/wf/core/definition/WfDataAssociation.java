package com.zifang.z.wf.core.definition;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据关联（第 46 轮）—— {@code <dataInputAssociation>} / {@code <dataOutputAssociation>}。
 *
 * <pre>{@code
 * <serviceTask id="t1" name="扣款">
 *   <ioSpecification>
 *     <dataInput  id="din1"  name="订单" dataObjectRef="do1"/>
 *     <dataOutput id="dout1" name="回执" dataObjectRef="do2"/>
 *   </ioSpecification>
 *   <dataInputAssociation  id="dia1" sourceRef="dor1"  targetRef="din1">
 *     <transformation>${order.amount}</transformation>
 *   </dataInputAssociation>
 *   <dataOutputAssociation id="doa1" sourceRef="do2"   targetRef="dout1"/>
 * </serviceTask>
 * }</pre>
 *
 * <p><b>它与 {@link WfFlow} / {@link WfAssociation} 的区别，在本轮之前是个空白</b>：
 * 流程图上的三类边 —— {@code sequenceFlow}（token 沿它走）、
 * {@code association}（补偿专用，第 37 轮）—— 都有模型，
 * 而<b>数据关联此前连解析都没有</b>。它长得像前两者，语义却三者皆不同：
 * <b>token 永远不沿着数据关联走</b>，它描述的是"数据从哪来、到哪去"。
 *
 * <p><b>本引擎不执行数据关联</b>，理由与 {@link WfDataStore} 一致（Camunda 7 同样不执行）。
 * 它在这里的作用是：<b>把建模错误从静默变成部署失败</b>。
 * 一条 {@code targetRef} 指向不存在的数据声明的关联，在改动前是完全看不见的 ——
 * 流程照常部署、照常运行，而作者以为数据在流。
 *
 * <p><b>{@link #getTransformation()} 与 {@link #getAssignments()} 都原样存文本，不求值。</b>
 * 本引擎的表达式求值只发生在 delegate / 条件 / 结果表达式那几处，
 * 数据关联里的 transformation 用的是 BPMN 自己的 formal expression 语法，
 * 两种语言的语义并不相同（后者没有本仓的变量作用域规则）。所以：
 * <b>存原文，不假装求过值</b>。
 *
 * @author zifang
 */
public class WfDataAssociation implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 关联 id。流程定义内唯一。 */
    private String id;

    /** 方向 —— INPUT 是 {@code <dataInputAssociation>}，OUTPUT 是 {@code <dataOutputAssociation>}。 */
    private WfDataDirection direction;

    /**
     * 声明这条关联的元素 id（活动 id）。
     *
     * <p><b>为 {@code null} 表示它写在 {@code <process>} 上</b>（流程级数据关联），
     * 此时它不属于任何活动。BPMN 允许这种写法，本仓同样收下。
     *
     * <p>之所以允许 {@code null} 而不是硬造一个"宿主"：
     * 造一个 {@code ownerId} 指向流程 key 的值，看上去字段总是非空，
     * 实际上是把"流程级"与"某个恰好叫这个 id 的活动"混成同一个字符串 ——
     * 校验器查它时会撞上另一种含义。REST 视图为此另给一个
     * {@code processLevel} 布尔位，让调用方不必靠"是不是 null"去推断。
     */
    private String ownerId;

    /**
     * 源端 {@code sourceRef}。
     *
     * <p>方向决定它该指向什么：OUTPUT 必须是 {@link WfDataObject}，
     * INPUT 可以是 {@code dataObject} 或 {@link WfDataObjectReference}。
     */
    private String sourceRef;

    /**
     * 目标端 {@code targetRef}。
     *
     * <p>方向同样决定它该指向什么：INPUT 必须落在 data object reference 上，
     * OUTPUT 可以是 {@code dataObject} 或 data object reference。
     */
    private String targetRef;

    /**
     * {@code <transformation>} 的<b>原文</b>（{@code null} 表示没写）。
     *
     * <p>见类注释：<b>不求值</b>。存原文而不是丢掉，是为了让走查工具能回显
     * 作者写了什么，也让部署期报语法问题时拿得出原文。
     */
    private String transformation;

    /**
     * {@code <assignment>} 的原文列表（每个元素一条）。
     *
     * <p>BPMN 允许一条关联带多个 assignment。存成列表而不是拼成一段文本，
     * 是因为"有两条 assignment"与"一条 assignment 里写了两个 to"
     * 在语义上不是一回事。
     */
    private List<String> assignments = new ArrayList<String>();

    public WfDataAssociation() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public WfDataDirection getDirection() {
        return direction;
    }

    public void setDirection(WfDataDirection direction) {
        this.direction = direction;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
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

    public String getTransformation() {
        return transformation;
    }

    public void setTransformation(String transformation) {
        this.transformation = transformation;
    }

    public List<String> getAssignments() {
        return assignments;
    }

    public void setAssignments(List<String> assignments) {
        this.assignments = assignments == null ? new ArrayList<String>() : assignments;
    }

    @Override
    public String toString() {
        return "WfDataAssociation{" + id + "(" + direction + ")@" + ownerId
                + ": " + sourceRef + " -> " + targetRef + "}";
    }
}
