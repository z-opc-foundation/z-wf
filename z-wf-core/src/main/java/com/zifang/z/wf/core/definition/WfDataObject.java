package com.zifang.z.wf.core.definition;

import java.io.Serializable;

/**
 * {@code <dataObject>} —— 流程里声明的一份数据（第 46 轮）。
 *
 * <pre>{@code
 * <dataObject id="do1" name="订单" itemSubjectRef="tns:Order"/>
 * }</pre>
 *
 * <p><b>本引擎对它做什么、不做什么。</b>
 *
 * <p><b>做</b>：解析成模型、随定义落库、经 {@code WfDefinitionValidator} 校验
 * 引用完整性、经 REST 暴露给建模与走查工具。
 *
 * <p><b>不做</b>：<b>不做任何数据搬运，也不建任何数据存储。</b>
 * 这一点必须写死而不是含糊过去 —— 本引擎的全部业务数据都走<b>流程变量</b>
 * （{@code WfVariableService}），而 {@code <dataObject>} 在 BPMN 里是一份
 * <b>声明</b>：它说"这个流程要用一份这样的数据"，本身不规定数据存在哪。
 *
 * <p><b>为什么这不算「做了一半」</b>：Camunda 7 同样不执行普通活动上的数据关联，
 * 同样不实现 {@code dataStore} —— 引擎侧只把声明读进模型供校验与展示。
 * 真正搬运数据的是 <b>delegate</b>（通过流程变量读写），
 * 那条路本引擎是通的。换个角度说：即使把 {@code dataObject} 的全部语义都实现了，
 * 业务代码里的数据也不会因此多流一格 —— 它拿不到这个对象。
 *
 * <p><b>改动前这块是静默丢弃</b>：{@code WfXmlParser} 里没有任何一处读
 * {@code dataObject}，它也不在「不支持元素」清单里，于是 BPMN 里写了它与没写完全一样，
 * 校验器一个错都不报。悬空的 {@code <dataObjectReference dataObjectRef="不存在"/>`
 * 同样悄无声息。本轮把它从"读不进来"变成"读得进来、且引用断了会部署失败"。
 *
 * @author zifang
 */
public class WfDataObject implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 声明 id。流程定义内唯一 —— 它同时是数据关联端点的引用键。 */
    private String id;

    /** 显示名。 */
    private String name;

    /**
     * {@code itemSubjectRef} —— 数据结构的类型引用（XML 里是个 QName）。
     *
     * <p><b>原样存成字符串，不解析成 {@code QName}</b>：本引擎没有类型系统，
     * 也没有任何地方拿它做类型校验。解析一个解析不了的东西只会得到
     * "拿到一个 QName 对象却没人用" —— 那比存字符串更糟，因为它看起来像被用过了。
     * 存原文还有一个好处：走查工具要回显"作者到底写了哪个类型"时不用反查。
     */
    private String itemSubjectRef;

    /** 作用域 —— 由声明位置决定，见 {@link WfDataScope}。 */
    private WfDataScope scope = WfDataScope.PROCESS;

    public WfDataObject() {
    }

    public WfDataObject(String id, String name, String itemSubjectRef, WfDataScope scope) {
        this.id = id;
        this.name = name;
        this.itemSubjectRef = itemSubjectRef;
        this.scope = scope;
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

    public String getItemSubjectRef() {
        return itemSubjectRef;
    }

    public void setItemSubjectRef(String itemSubjectRef) {
        this.itemSubjectRef = itemSubjectRef;
    }

    public WfDataScope getScope() {
        return scope;
    }

    public void setScope(WfDataScope scope) {
        this.scope = scope;
    }

    @Override
    public String toString() {
        return "WfDataObject{" + id + ": " + name + ", scope=" + scope + "}";
    }
}
