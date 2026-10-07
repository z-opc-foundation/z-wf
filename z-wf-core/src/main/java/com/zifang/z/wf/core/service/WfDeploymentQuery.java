package com.zifang.z.wf.core.service;

import java.util.Date;

import com.zifang.z.wf.core.service.WfEngineException;

/**
 * 部署历史查询（第 47 轮）—— 跨<b>所有 key</b> 查全部版本。
 *
 * <p><b>补的是这样一个洞</b>：既有能力里
 * {@code findDefinitions(keyLike, nameLike, suspended)} 每个 key <b>只出最新一版</b>，
 * {@code findDefinitionVersions(key)} 又要<b>先知道 key</b> ——
 * 于是「上周我部署了什么」「哪些流程在 JSON 部署之后又被改过」
 * 「把这段时间内的部署全列出来」这三类问题<b>一条都答不了</b>。
 *
 * <p>Camunda 的 {@code createDeploymentQuery} 答的正是最后一个。
 *
 * <p><b>本引擎没有独立的 deployment 实体</b>，本类查的是
 * {@code ZWF_DEFINITION} 的全部行 —— 一次 deploy 就是一条 {@code (key, version)}。
 * <b>刻意不为此建第二张表</b>：表里会存的每一列都已经在 {@code ZWF_DEFINITION} 上，
 * 复制一份就多出一个必然与原表漂移的真源（这个坑本仓已经踩过两轮：
 * {@code suspended} / {@code isDefault} 都是在图 JSON 与列之间反复横跳才收敛成"单一真源"）。
 *
 * <p><b>过滤、排序、分页全部在
 * {@link WfDeploymentQueryService} 里做，且只有那一份</b> ——
 * 存储层只负责"把行读成 {@link WfDeploymentEntry} 投影"，
 * 两个持久化实现里一行过滤逻辑都没有，结构上就不可能漂。
 *
 * @author zifang
 */
public class WfDeploymentQuery {

    /** key 精确匹配。 */
    private String key;

    /** key 模糊匹配（包含）。 */
    private String keyLike;

    /** 名称模糊匹配（包含）。 */
    private String nameLike;

    /** 分类精确匹配。 */
    private String category;

    /** 停用状态筛选；{@code null} 不限。 */
    private Boolean suspended;

    /** 是否默认流程定义；{@code null} 不限。 */
    private Boolean defaultDefinition;

    /**
     * 有无可回读的原始 BPMN XML；{@code null} 不限。
     *
     * <p>见 {@link WfDeploymentEntry#isHasSourceXml()}：JSON 部署的定义恒为 {@code false}，
     * 这一条是「哪些部署还能交给建模工具改」的唯一入口。
     */
    private Boolean hasSourceXml;

    /** 部署时间下界（含）；{@code null} 不限。 */
    private Date deployedFrom;

    /** 部署时间上界（含）；{@code null} 不限。 */
    private Date deployedTo;

    /** 排序方式，默认 {@link WfDeploymentOrder#DEPLOY_TIME_DESC}。 */
    private WfDeploymentOrder orderBy = WfDeploymentOrder.DEPLOY_TIME_DESC;

    /** 页码，从 1 开始。 */
    private int pageNum = 1;

    /** 每页条数；{@code <= 0} 表示不分页（由 service 侧的扫描上限兜底）。 */
    private int pageSize = 50;

    public String getKey() {
        return key;
    }

    public WfDeploymentQuery setKey(String key) {
        this.key = key;
        return this;
    }

    public String getKeyLike() {
        return keyLike;
    }

    public WfDeploymentQuery setKeyLike(String keyLike) {
        this.keyLike = keyLike;
        return this;
    }

    public String getNameLike() {
        return nameLike;
    }

    public WfDeploymentQuery setNameLike(String nameLike) {
        this.nameLike = nameLike;
        return this;
    }

    public String getCategory() {
        return category;
    }

    public WfDeploymentQuery setCategory(String category) {
        this.category = category;
        return this;
    }

    public Boolean getSuspended() {
        return suspended;
    }

    public WfDeploymentQuery setSuspended(Boolean suspended) {
        this.suspended = suspended;
        return this;
    }

    public Boolean getDefaultDefinition() {
        return defaultDefinition;
    }

    public WfDeploymentQuery setDefaultDefinition(Boolean defaultDefinition) {
        this.defaultDefinition = defaultDefinition;
        return this;
    }

    public Boolean getHasSourceXml() {
        return hasSourceXml;
    }

    public WfDeploymentQuery setHasSourceXml(Boolean hasSourceXml) {
        this.hasSourceXml = hasSourceXml;
        return this;
    }

    public Date getDeployedFrom() {
        return deployedFrom;
    }

    public WfDeploymentQuery setDeployedFrom(Date deployedFrom) {
        this.deployedFrom = deployedFrom;
        return this;
    }

    public Date getDeployedTo() {
        return deployedTo;
    }

    public WfDeploymentQuery setDeployedTo(Date deployedTo) {
        this.deployedTo = deployedTo;
        return this;
    }

    public WfDeploymentOrder getOrderBy() {
        return orderBy;
    }

    public WfDeploymentQuery setOrderBy(WfDeploymentOrder orderBy) {
        this.orderBy = orderBy;
        return this;
    }

    public int getPageNum() {
        return pageNum;
    }

    public WfDeploymentQuery setPageNum(int pageNum) {
        this.pageNum = pageNum;
        return this;
    }

    public int getPageSize() {
        return pageSize;
    }

    public WfDeploymentQuery setPageSize(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    /**
     * 倒置的时间区间当场报，而不是返回空集。
     *
     * <p>区间写成 {@code deployedTo < deployedFrom} 时，"查不到东西"这个答案
     * 有至少两种解释：这段时间真的没部署过，或者调用方把两个值写反了。
     * 返回空集的话，调用方只能看到"没有"，而真相是"你问错了"。
     *
     * <p>与 {@code WfTaskQuery#rejectReversedRange} 同一取舍；
     * 三个时间区间查询共用一条报错路径，漏改一处的症状是
     * "只有某一对区间写反时静默返回空集"，排查时几乎不会怀疑到区间写反。
     */
    public WfDeploymentQuery assertConsistent() {
        if (deployedFrom != null && deployedTo != null && deployedTo.before(deployedFrom)) {
            throw new WfEngineException("部署时间的上界早于下界: from="
                    + deployedFrom.getTime() + " to=" + deployedTo.getTime()
                    + "。上界含端点，请确认两个时间没有写反");
        }
        return this;
    }

    /** 页码规整：小于 1 一律当第一页，而不是报错（翻到第 0 页通常是手滑）。 */
    public int normalizedPageNum() {
        return pageNum < 1 ? 1 : pageNum;
    }

    public int normalizedPageSize() {
        return pageSize;
    }

    public int offset() {
        int size = normalizedPageSize();
        if (size <= 0) {
            return 0;
        }
        return (normalizedPageNum() - 1) * size;
    }
}
