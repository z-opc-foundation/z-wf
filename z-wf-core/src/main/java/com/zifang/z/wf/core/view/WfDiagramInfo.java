package com.zifang.z.wf.core.view;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 流程定义对应的图形信息（对应 BPMN DI 的 {@code BPMNDiagram} / {@code BPMNPlane}）。
 *
 * <p><b>它带一致性核对结果</b>（{@link #getMissingNodeIds()} / {@link #getOrphanShapeIds()}），
 * 而不是只把图元原样倒出来。原因是画图信息与流程逻辑<b>分开存</b>：
 * 有人改了 XML 里的连线却没同步改坐标，于是图上会多出一个框、或少一根线，
 * 而渲染端只看图元，于是"图和实际流程对不上"，且没有任何一处报错。
 * 把悬挂项列出来，调用方至少知道该信哪一边。
 *
 * @author zifang
 */
public class WfDiagramInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String definitionKey;

    private int definitionVersion;

    private List<WfShape> shapes = new ArrayList<WfShape>();

    private List<WfEdge> edges = new ArrayList<WfEdge>();

    /** 流程里有、图上没有的节点 id。 */
    private List<String> missingNodeIds = new ArrayList<String>();

    /** 图上有、流程里没有的图元 id。 */
    private List<String> orphanShapeIds = new ArrayList<String>();

    /** 图上有、流程里没有对应连线的图元 id。 */
    private List<String> orphanEdgeIds = new ArrayList<String>();

    /**
     * 流程里有、图上没有对应连线的连线 id。
     *
     * <p>与 {@link #orphanEdgeIds} 成对存在：只报"图上多一根线"不报"逻辑里多一根线"，
     * 是把同一件事只做了一半 —— 改了 XML 里的连线却没更新坐标，方向反过来了照样漏。
     */
    private List<String> missingFlowIds = new ArrayList<String>();

    /** true = 原始 XML 里根本没有 DI 段（模型是纯手写或被工具剥过）。 */
    private boolean empty;

    public String getDefinitionKey() {
        return definitionKey;
    }

    public void setDefinitionKey(String definitionKey) {
        this.definitionKey = definitionKey;
    }

    public int getDefinitionVersion() {
        return definitionVersion;
    }

    public void setDefinitionVersion(int definitionVersion) {
        this.definitionVersion = definitionVersion;
    }

    public List<WfShape> getShapes() {
        return shapes;
    }

    public void setShapes(List<WfShape> shapes) {
        this.shapes = shapes == null ? new ArrayList<WfShape>() : shapes;
    }

    public List<WfEdge> getEdges() {
        return edges;
    }

    public void setEdges(List<WfEdge> edges) {
        this.edges = edges == null ? new ArrayList<WfEdge>() : edges;
    }

    public List<String> getMissingNodeIds() {
        return missingNodeIds;
    }

    public void setMissingNodeIds(List<String> missingNodeIds) {
        this.missingNodeIds = missingNodeIds == null
                ? new ArrayList<String>() : missingNodeIds;
    }

    public List<String> getOrphanShapeIds() {
        return orphanShapeIds;
    }

    public void setOrphanShapeIds(List<String> orphanShapeIds) {
        this.orphanShapeIds = orphanShapeIds == null
                ? new ArrayList<String>() : orphanShapeIds;
    }

    public List<String> getOrphanEdgeIds() {
        return orphanEdgeIds;
    }

    public void setOrphanEdgeIds(List<String> orphanEdgeIds) {
        this.orphanEdgeIds = orphanEdgeIds == null
                ? new ArrayList<String>() : orphanEdgeIds;
    }

    public List<String> getMissingFlowIds() {
        return missingFlowIds;
    }

    public void setMissingFlowIds(List<String> missingFlowIds) {
        this.missingFlowIds = missingFlowIds == null
                ? new ArrayList<String>() : missingFlowIds;
    }

    public boolean isEmpty() {
        return empty;
    }

    public void setEmpty(boolean empty) {
        this.empty = empty;
    }

    /** 图与流程是否完全对得上。 */
    public boolean isConsistent() {
        return missingNodeIds.isEmpty() && orphanShapeIds.isEmpty()
                && orphanEdgeIds.isEmpty() && missingFlowIds.isEmpty();
    }
}
