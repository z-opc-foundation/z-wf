package com.zifang.z.wf.core.persistence;

/**
 * 筛选器查询条件 —— 用来<b>列出</b>存了哪些筛选器。
 *
 * <p>与 {@code WfFilterService} 内部的"条件解析"是两件事：
 * 这一类管的是"有哪些筛选器"，筛选器<b>里面</b>的条件是另一个 Map。
 *
 * @author zifang
 */
public class WfFilterQuery {

    private String id;

    private String name;

    /** 名字模糊匹配（大小写不敏感）。 */
    private String nameLike;

    private com.zifang.z.wf.core.model.WfFilterType resourceType;

    private String owner;

    private int pageNum = 1;

    private int pageSize = 50;

    public String getId() {
        return id;
    }

    public WfFilterQuery setId(String id) {
        this.id = id;
        return this;
    }

    public String getName() {
        return name;
    }

    public WfFilterQuery setName(String name) {
        this.name = name;
        return this;
    }

    public String getNameLike() {
        return nameLike;
    }

    public WfFilterQuery setNameLike(String nameLike) {
        this.nameLike = nameLike;
        return this;
    }

    public com.zifang.z.wf.core.model.WfFilterType getResourceType() {
        return resourceType;
    }

    public WfFilterQuery setResourceType(com.zifang.z.wf.core.model.WfFilterType resourceType) {
        this.resourceType = resourceType;
        return this;
    }

    public String getOwner() {
        return owner;
    }

    public WfFilterQuery setOwner(String owner) {
        this.owner = owner;
        return this;
    }

    public int getPageNum() {
        return pageNum;
    }

    public WfFilterQuery setPageNum(int pageNum) {
        this.pageNum = pageNum;
        return this;
    }

    public int getPageSize() {
        return pageSize;
    }

    public WfFilterQuery setPageSize(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    public int normalizedPageNum() {
        return pageNum < 1 ? 1 : pageNum;
    }

    public int normalizedPageSize() {
        if (pageSize < 1) {
            return 50;
        }
        return Math.min(pageSize, 500);
    }
}
