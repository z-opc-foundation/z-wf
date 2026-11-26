package com.zifang.z.wf.core.model;

import java.io.Serializable;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;

/**
 * 保存筛选器 —— 一组可以存起来反复用的查询条件。
 *
 * <p>它解决的是这么一件事：「我的长期待办」「超过三天没人动的单」
 * 「所有重试耗尽的故障」这类条件，运营每天都要输一遍，
 * 输错一个字段就得到一份看起来正常、其实少了一大截的清单。
 * 存下来之后条件只有一份、所有人共用。
 *
 * <p><b>条件一律存成字符串</b>，不是各自类型的对象。理由是持久化形态
 * （JSON 一列）必须与调用形态解耦 —— 存 `Map&lt;String, Object&gt;` 的话
 * `true` 与 `"true"`、`500` 与 `"500"` 在 JSON 里长得一样，
 * 读回来时类型已经丢了，谁在什么时候转的再也说不清。
 * 类型转换全部发生在 {@code WfFilterService} 里，且**转不动就报错**。
 *
 * <p><b>刻意不保存的</b>：{@code pageNum} / {@code pageSize}。
 * 它们是"这一次取第几页"，不是"要查什么"，存进去会让"翻页"变成"改筛选器"。
 *
 * @author zifang
 */
public class WfFilter implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String name;

    /** 适用的资源类型，见 {@link WfFilterType}。 */
    private WfFilterType resourceType;

    /** 谁建的。可空 —— 有些筛选器是系统预置的，不属于任何个人。 */
    private String owner;

    /**
     * 条件：名字 → 值（都是字符串）。
     *
     * <p>用 {@link TreeMap} 而不是 {@link HashMap}：
     * 排序后的遍历顺序让"这个筛选器到底存了哪几条"在任何一次调用里都一样，
     * 而 {@code HashMap} 的顺序会随 key 的哈希变化 ——
     * 排查"两条条件顺序不同导致行为不同"时，稳定的顺序是省时间的前提。
     */
    private Map<String, String> properties = new TreeMap<String, String>();

    private Date createTime;

    private Date updateTime;

    /**
     * 乐观锁版本号。
     *
     * <p>筛选器是<b>共享</b>的：管理员在改它，别人正在用它查。
     * 没有版本号的话最后写的人赢，改的人会读成"我明明改了却没生效"。
     * 与 {@link WfTask} / {@link WfJob} 同一套约定：
     * 改之前先 {@link #nextRevision()}，让传入对象的 revision 恰好是库里那份 +1。
     */
    private int revision;

    public WfFilter() {
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

    public WfFilterType getResourceType() {
        return resourceType;
    }

    public void setResourceType(WfFilterType resourceType) {
        this.resourceType = resourceType;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties == null ? new TreeMap<String, String>() : properties;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    /** 自增版本号。改既有筛选器时**必须**先调它，见 {@link #revision}。 */
    public void nextRevision() {
        this.revision++;
    }

    /** 放一条条件。空名字直接拒绝 —— 空名字的条件在查询时会被静默忽略。 */
    public WfFilter putProperty(String name, String value) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("筛选条件的名字不能为空");
        }
        properties.put(name.trim(), value);
        return this;
    }

    public String getProperty(String name) {
        return properties == null ? null : properties.get(name);
    }

    @Override
    public String toString() {
        return "WfFilter{" + id + " " + name + " " + resourceType + " " + properties + "}";
    }

    /** 新建时用：一份空条件的骨架。 */
    public static WfFilter of(String id, String name, WfFilterType resourceType, String owner) {
        WfFilter filter = new WfFilter();
        filter.setId(id);
        filter.setName(name);
        filter.setResourceType(resourceType);
        filter.setOwner(owner);
        // TreeMap 的有序性是刻意选的：见 properties 字段注释
        filter.setProperties(new TreeMap<String, String>());
        return filter;
    }
}
