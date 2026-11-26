package com.zifang.z.wf.core.service;

/**
 * 部署历史的排序方式（第 47 轮）。
 *
 * <p><b>只有四个值，且没有"不排序"。</b> 部署历史是一份<b>要翻页的清单</b>，
 * 不排序的清单在翻页时会漏行与重行 —— 同一批数据查两遍，第一页与第二页
 * 交叠或夹走一条，没有任何报错，而调用方看到的是"这条部署记录丢了"。
 *
 * <p><b>排序键后面永远追加一个 tiebreaker</b>（见
 * {@code WfDeploymentQueryService#compareEntries}）：
 * {@code DEPLOY_TIME} 是毫秒精度，同一批部署很可能落在同一毫秒，
 * 此时的相对次序取决于底层返回顺序 —— 换一次查询就可能变。
 *
 * @author zifang
 */
public enum WfDeploymentOrder {

    /**
     * 部署时间倒序（<b>默认</b>）—— 最近部署的排最前。
     *
     * <p>这是部署历史最常用的一个：「我刚才部署的东西对不对」「昨天那次改了什么」。
     */
    DEPLOY_TIME_DESC,

    /** 部署时间正序 —— 最早部署的排最前。 */
    DEPLOY_TIME_ASC,

    /** key 升序。 */
    KEY_ASC,

    /** key 降序。 */
    KEY_DESC
}
