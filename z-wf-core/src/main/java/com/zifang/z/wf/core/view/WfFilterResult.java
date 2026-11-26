package com.zifang.z.wf.core.view;

import java.io.Serializable;
import java.util.List;

/**
 * 跑一张筛选器的结果。
 *
 * <p><b>为什么 records 是 {@code List<Object>} 而不是某个具体类型</b>：
 * 结果的类型由筛选器自己的 {@code resourceType} 决定，三种筛选器
 * 分别给出任务、流程实例、故障。<b>让调用方自己按类型去解释</b>，
 * 比在端点层按类型分叉成三个几乎一样的返回结构更不容易出错 ——
 * 分叉之后，"我调的这个端点会返回哪种"就变成了要额外查文档的事。
 *
 * <p>代价是这份结构**没有编译期类型保障**：
 * 拿到一份 {@code resourceType=task} 的结果却按实例去读字段，
 * 拿到的是 null 而不是异常。{@code resourceType} 字段就是为此存在的 ——
 * 它必须和 {@code records} 一起读，单独读 {@code records} 一定读错。
 *
 * @author zifang
 */
public class WfFilterResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String filterId;

    private String filterName;

    /**
     * 结果类型：{@code task} / {@code processInstance} / {@code incident}。
     *
     * <p>与 {@link #records} 必须一起读，见类注释。
     */
    private String resourceType;

    private List<Object> records = new java.util.ArrayList<Object>();

    /** 匹配总数（不是当前页条数）。 */
    private int total;

    public String getFilterId() {
        return filterId;
    }

    public void setFilterId(String filterId) {
        this.filterId = filterId;
    }

    public String getFilterName() {
        return filterName;
    }

    public void setFilterName(String filterName) {
        this.filterName = filterName;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public List<Object> getRecords() {
        return records;
    }

    public void setRecords(List<?> records) {
        this.records = records == null ? new java.util.ArrayList<Object>()
                : new java.util.ArrayList<Object>(records);
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    @Override
    public String toString() {
        return "WfFilterResult{" + filterName + "(" + resourceType + ") "
                + records.size() + "/" + total + "}";
    }
}
