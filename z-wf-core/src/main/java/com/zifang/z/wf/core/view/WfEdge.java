package com.zifang.z.wf.core.view;

import java.io.Serializable;

/**
 * 流程图上的一条连线图元（对应 BPMN DI 的 {@code BPMNEdge}）。
 *
 * <p>带<b>全部折点</b>而不是只带起终点：流程图上转弯的线若只画两点会穿过旁边的方框，
 * 而折点信息恰恰只存在于 BPMN DI 里，丢了就再也找不回来。
 *
 * @author zifang
 */
public class WfEdge implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 连线 id，与 {@code WfFlow#getId()} 对应。 */
    private String id;

    private String name;

    private String sourceRef;

    private String targetRef;

    /** 折点序列，每项是 {@code {x, y}}。没有折点时是空列表而不是 null。 */
    private java.util.List<double[]> waypoints = new java.util.ArrayList<double[]>();

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

    public java.util.List<double[]> getWaypoints() {
        return waypoints;
    }

    public void setWaypoints(java.util.List<double[]> waypoints) {
        this.waypoints = waypoints == null
                ? new java.util.ArrayList<double[]>() : waypoints;
    }
}
