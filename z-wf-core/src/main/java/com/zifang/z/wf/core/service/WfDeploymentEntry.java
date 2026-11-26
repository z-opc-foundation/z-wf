package com.zifang.z.wf.core.service;

import java.io.Serializable;
import java.util.Date;

/**
 * 一次部署的<b>元数据投影</b>（第 47 轮）—— 一条 {@code (key, version)} 定义的部署记录。
 *
 * <p><b>本引擎没有独立的 deployment 实体。</b> 一次 {@code deploy} 就是一条
 * {@code (key, version)} 定义落库，所以「部署记录」与「定义版本」是同一行 ——
 * 本类是那一行的<b>元数据视图</b>，不来自第二张表。
 * 类名对齐 Camunda 的 {@code createDeploymentQuery} 是为了迁移时能一眼对上，
 * 但它查的是 {@code ZWF_DEFINITION} 的全部行。
 *
 * <h3>为什么是投影而不是直接返回 {@link com.zifang.z.wf.core.definition.WfDefinition}</h3>
 *
 * <p>{@code DEF_GRAPH} 存的是整张流程图的 JSON，{@code SOURCE_XML} 存的是原始 BPMN ——
 * 部署历史列表要的是「哪个 key、哪一版、什么时候、能不能回读」，
 * 逐行把图和 XML 都反序列化一遍是纯粹的浪费，
 * 而这张表是全库最容易被误当成"小表"的地方（每部署一次就多一行，且永不删除）。
 *
 * <p>顺带避开一个更隐蔽的坑：{@code WfDefinition} 里的 {@code category} /
 * {@code description} 来自图 JSON，而它们<b>同时</b>存在于独立的列里。
 * 走投影就只读列，两条来源不会在这一层被混用。
 *
 * @author zifang
 */
public class WfDeploymentEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 定义 key。 */
    private String key;

    /** 版本号。 */
    private int version;

    /** 定义名称。 */
    private String name;

    /** 分类。 */
    private String category;

    /** 描述。 */
    private String description;

    /** 该版本是否已停用。 */
    private boolean suspended;

    /** 该版本是否是默认流程定义。 */
    private boolean defaultDefinition;

    /** 部署时间。 */
    private Date deployTime;

    /**
     * 有没有可回读的原始 BPMN XML。
     *
     * <p><b>它是一个真实存在的分界，不是装饰字段</b>：
     * 只有 {@code deployXml} 部署的定义才有 {@code SOURCE_XML}，
     * 而 {@code deployJson}（LogicFlow 导出的流程图）部署的<b>永远没有</b> ——
     * {@code WfRepositoryService#getProcessModel} 遇到这种定义会直接抛错。
     * ⇒ 部署历史里没有这一列，调用方只能挨个 key 试一遍才知道哪个还能回读给建模工具。
     *
     * <p>它是<b>算出来的布尔</b>而不是 XML 本身：
     * 把整段 XML 拉进内存只为回答"有没有"，代价与收益不成比例。
     */
    private boolean hasSourceXml;

    public WfDeploymentEntry() {
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public void setSuspended(boolean suspended) {
        this.suspended = suspended;
    }

    public boolean isDefaultDefinition() {
        return defaultDefinition;
    }

    public void setDefaultDefinition(boolean defaultDefinition) {
        this.defaultDefinition = defaultDefinition;
    }

    public Date getDeployTime() {
        return deployTime;
    }

    public void setDeployTime(Date deployTime) {
        this.deployTime = deployTime;
    }

    public boolean isHasSourceXml() {
        return hasSourceXml;
    }

    public void setHasSourceXml(boolean hasSourceXml) {
        this.hasSourceXml = hasSourceXml;
    }

    @Override
    public String toString() {
        return "WfDeploymentEntry{" + key + ":" + version
                + (hasSourceXml ? " 有原始XML" : " 无原始XML") + " @" + deployTime + "}";
    }
}
