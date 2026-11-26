package com.zifang.z.wf.core.definition;

import java.io.Serializable;

/**
 * 数据对象<b>引用</b>（第 46 轮）—— BPMN 里有三种同源的声明，统一落在这一个类里。
 *
 * <p>三种元素在 XSD 里是同一个类型的三次特化（{@code tDataInput} 与
 * {@code tDataOutput} 都直接 {@code extends tDataObjectReference}），
 * 字段完全一样，只是<b>出处</b>与<b>被允许出现在哪一端</b>不同：
 *
 * <pre>{@code
 * <!-- ① 流程级引用：process 里的 dataObjectReference -->
 * <dataObjectReference id="dor1" name="订单入参" dataObjectRef="do1"/>
 *
 * <!-- ② 活动输入：ioSpecification/dataInput -->
 * <serviceTask id="t1">
 *   <ioSpecification>
 *     <dataInput id="din1" name="订单" dataObjectRef="do1"/>
 *   </ioSpecification>
 * </serviceTask>
 *
 * <!-- ③ 活动输出：ioSpecification/dataOutput -->
 * <serviceTask id="t1">
 *   <ioSpecification>
 *     <dataOutput id="dout1" name="结果" dataObjectRef="do2"/>
 *   </ioSpecification>
 * </serviceTask>
 * }</pre>
 *
 * <p><b>为什么合成一个类而不是三个</b>：BPMN 里 {@code id} 是<b>整个文档唯一</b>的，
 * 而 {@code dataInputAssociation@targetRef} 恰恰就是指向上面②③里那个
 * {@code dataInput} / {@code dataOutput} 的 id。若分成三个列表，
 * 校验器查"这个 targetRef 指向谁"就得在三个列表里挨个找，
 * 而找到了还得再判断它出现在哪个列表才谈得上类型合法性。
 * 平铺成一个列表 + 一个 {@link Kind} 判别位，引用查表与类型判定就都落在同一处。
 *
 * <p><b>代价是 {@code getDataObjectReferences()} 里混着三种东西</b> ——
 * 所以 {@link Kind} 不可省，REST 视图也必须把它一起吐出来（不能让人自己猜）。
 *
 * <p>与 {@link WfDataObject} 的关系：{@code dataObjectRef} 指向 {@link WfDataObject#getId()}。
 * 本引擎不复制数据，引用与被引用对象是<b>同一份数据的两种视图</b>，不是两份副本。
 *
 * @author zifang
 */
public class WfDataObjectReference implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 引用在 BPMN 里的出处。 */
    public enum Kind {

        /** {@code <dataObjectReference>} —— 流程级引用。 */
        REFERENCE,

        /**
         * {@code <ioSpecification>} 里的 {@code <dataInput>}。
         *
         * <p>可以作为 {@code dataInputAssociation} 的 {@code targetRef}，
         * 也可以作为 {@code dataOutputAssociation} 的 {@code targetRef}。
         */
        INPUT,

        /**
         * {@code <ioSpecification>} 里的 {@code <dataOutput>}。
         *
         * <p>只能作为 {@code dataOutputAssociation} 的 {@code targetRef} ——
         * 拿它当输入的落点没有语义。
         */
        OUTPUT
    }

    /** 引用 id。流程定义内唯一。 */
    private String id;

    /** 显示名。 */
    private String name;

    /** 指向的 {@link WfDataObject#getId()}。BPMN 规定这一项必填。 */
    private String dataObjectRef;

    /** {@code itemSubjectRef} —— 同 {@link WfDataObject#getItemSubjectRef()}，同样原样存字符串。 */
    private String itemSubjectRef;

    /** 出处。默认 {@link Kind#REFERENCE}，解析时按所在位置改写。 */
    private Kind kind = Kind.REFERENCE;

    public WfDataObjectReference() {
    }

    public WfDataObjectReference(String id, String name, String dataObjectRef, Kind kind) {
        this.id = id;
        this.name = name;
        this.dataObjectRef = dataObjectRef;
        this.kind = kind;
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

    public String getDataObjectRef() {
        return dataObjectRef;
    }

    public void setDataObjectRef(String dataObjectRef) {
        this.dataObjectRef = dataObjectRef;
    }

    public String getItemSubjectRef() {
        return itemSubjectRef;
    }

    public void setItemSubjectRef(String itemSubjectRef) {
        this.itemSubjectRef = itemSubjectRef;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    @Override
    public String toString() {
        return "WfDataObjectReference{" + id + "(" + kind + "): " + dataObjectRef + "}";
    }
}
