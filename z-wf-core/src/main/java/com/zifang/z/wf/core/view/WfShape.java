package com.zifang.z.wf.core.view;

import java.io.Serializable;

/**
 * 流程图上的一个节点图元（对应 BPMN DI 的 {@code BPMNShape}）。
 *
 * <p><b>为什么解析 BPMN DI 而不是自造一套图元</b>：画图工具（Modeler / Camunda Modeler /
 * Flowable 导出）保存 XML 时都会带 {@code BPMNDiagram} 段，里面是每个节点的<b>真实坐标</b>。
 * 丢掉它等于让用户导入一个模型后手工重画一遍所有方框，而那一步是"模型迁移"里最劝退的环节。
 *
 * <p>坐标只在<b>画图时</b>有意义，引擎推进一律按连线关系走 —— 所以图元信息挂在
 * 定义之外，不进 {@code WfDefinition}，也不进 codec。
 *
 * @author zifang
 */
public class WfShape implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 对应流程节点 id。与流程定义对不上时 {@link WfDiagramInfo} 会列出悬挂项。 */
    private String id;

    private String name;

    /** BPMN 元素类型（如 {@code userTask}），前端按它决定画什么形状。 */
    private String type;

    private double x;

    private double y;

    private double width;

    private double height;

    /** true 时画成菱形（网关）。 */
    private boolean expanded;

    /** true 时画成斜边矩形（事件）。 */
    private boolean isMarkerVisible;

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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getWidth() {
        return width;
    }

    public void setWidth(double width) {
        this.width = width;
    }

    public double getHeight() {
        return height;
    }

    public void setHeight(double height) {
        this.height = height;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }

    public boolean isMarkerVisible() {
        return isMarkerVisible;
    }

    public void setMarkerVisible(boolean markerVisible) {
        this.isMarkerVisible = markerVisible;
    }
}
