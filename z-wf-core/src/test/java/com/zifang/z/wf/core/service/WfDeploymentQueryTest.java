package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.JdbcWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfPersistence;

/**
 * 部署历史查询（第 47 轮）。
 *
 * <h3>这一层的主轴：同一批数据灌两套实现，同一组条件跑两遍，比对结果</h3>
 *
 * <p>部署历史补的是这样一个洞：{@code findDefinitions} 每个 key <b>只出最新一版</b>，
 * {@code findDefinitionVersions} 又要<b>先知道 key</b> ——
 * 「上周部署了什么」「哪些是 JSON 部署的（没有原始 XML 可回读）」
 * 这两类问题一条都答不了。
 *
 * <p>本类的比对不是走过场：本次设计把过滤/排序/分页<b>全部收在
 * {@code WfDeploymentQueryService} 一份实现</b>里，存储层只负责"读成投影"。
 * 而<b>投影本身</b>仍有两个实现 —— 两边读出的字段必须逐个相同，
 * 尤其 {@code hasSourceXml}：一边用 {@code CASE WHEN} 算、一边在 Java 里判，
 * 口径一旦分叉，部署历史上就会出现"明明是 XML 部署的却标成不可回读"。
 *
 * <p>core 模块的 test classpath 上就有 H2，<b>不需要起 Spring 上下文</b>。
 *
 * @author zifang
 */
class WfDeploymentQueryTest {

    private static final String NS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"http://zifang.com/wf/bpmn/ext\" targetNamespace=\"x\">\n";

    private static final long T0 = 1_700_000_000_000L;

    /**
     * 造两份实现，共用同一批数据。
     *
     * <p>时间用<b>毫秒整点</b>：{@code DEPLOY_TIME} 精度到毫秒，
     * 测试里若让它们随机撞在同一毫秒，排序类判据会间歇性地绿——
     * 那是数据碰巧没撞上，不是排序对。
     */
    private List<WfPersistence> twoImpls(WfDeploymentData... rows) {
        InMemoryWorkflowPersistence memory = new InMemoryWorkflowPersistence();
        JdbcWorkflowPersistence jdbc = new JdbcWorkflowPersistence(h2DataSource());
        List<WfPersistence> impls = new ArrayList<>();
        impls.add(memory);
        impls.add(jdbc);
        for (WfPersistence persistence : impls) {
            for (WfDeploymentData row : rows) {
                persistence.saveDefinition(row.toDefinition());
            }
        }
        return impls;
    }

    /** 一行部署记录。 */
    private static final class WfDeploymentData {
        private final String key;
        private final int version;
        private final String name;
        private final String category;
        private final long deployTime;
        private final boolean withXml;
        private final boolean suspended;

        WfDeploymentData(String key, int version, String name, String category,
                          long deployTime, boolean withXml, boolean suspended) {
            this.key = key;
            this.version = version;
            this.name = name;
            this.category = category;
            this.deployTime = deployTime;
            this.withXml = withXml;
            this.suspended = suspended;
        }

        WfDefinition toDefinition() {
            WfDefinition definition = new WfXmlParser().parse(NS
                    + "  <process id=\"" + key + "\" name=\"" + name + "\" isExecutable=\"true\">\n"
                    + "    <startEvent id=\"s\"/>\n"
                    + "    <userTask id=\"t\" name=\"审批\" zifang:assignee=\"alice\"/>\n"
                    + "    <endEvent id=\"e\"/>\n"
                    + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t\"/>\n"
                    + "    <sequenceFlow id=\"f2\" sourceRef=\"t\" targetRef=\"e\"/>\n"
                    + "  </process>\n</definitions>\n");
            definition.setCategory(category);
            definition.setVersion(version);
            // **版本号与部署时间由本类显式设定**：saveDefinition 是 INSERT，
            // 不走 WfRepositoryService#deploy 的"取 MAX+1"，所以可以造出任意版本
            definition.setStartTime(new java.util.Date(deployTime));
            if (withXml) {
                definition.setSourceXml(NS + "  <process id=\"" + key + "\"/>\n</definitions>\n");
            }
            if (suspended) {
                definition.setSuspended(true);
            }
            return definition;
        }
    }

    /**
     * 三个 key、六个版本，**刻意造出下面四种边界**：
     * <ul>
     *   <li>同一 key 的多版本（v1/v2）—— 验证"不只出最新一版"这条核心承诺</li>
     *   <li>两个 key 部署时间<b>完全相同</b> —— 验证 tiebreaker，
     *       没有它时同毫秒的次序取决于存储层返回顺序</li>
     *   <li>有 XML / 无 XML 两种 —— 验证 hasSourceXml</li>
     *   <li>停用 / 未停用各一</li>
     * </ul>
     */
    private List<WfPersistence> sampleImpls() {
        return twoImpls(
                new WfDeploymentData("leave", 1, "请假", "hr", T0, true, false),
                new WfDeploymentData("leave", 2, "请假申请", "hr", T0 + 1000, true, true),
                // 与上一条同毫秒：跨 key 同刻部署
                new WfDeploymentData("expense", 1, "报销", "finance", T0 + 2000, false, false),
                new WfDeploymentData("expense", 2, "报销单", "finance", T0 + 2000, true, false),
                new WfDeploymentData("purchase", 1, "采购", null, T0 + 3000, true, false),
                new WfDeploymentData("travel", 1, "出差", "hr", T0 + 4000, false, false));
    }

    private List<String> keysOf(List<WfDeploymentEntry> entries) {
        List<String> ids = new ArrayList<>();
        for (WfDeploymentEntry entry : entries) {
            ids.add(entry.getKey() + ":" + entry.getVersion());
        }
        return ids;
    }

    /** 两套实现跑同一个查询，逐条比对 id 列表。 */
    private void assertSameAcrossImpls(WfDeploymentQuery query, String what) {
        List<String> fromMemory = keysOf(new WfDeploymentQueryService(sampleImpls().get(0)).query(query));
        List<String> fromJdbc = keysOf(new WfDeploymentQueryService(sampleImpls().get(1)).query(query));
        assertEquals(fromMemory, fromJdbc,
                "**" + what + "：内存与 JDBC 必须给同一个集合**。"
                        + "两边只差" + (fromMemory.size() == fromJdbc.size() ? "顺序" : "内容")
                        + "，症状是「开发期查得到、线上查不到」，而内存模式下完全不可见。"
                        + "内存=" + fromMemory + " JDBC=" + fromJdbc);
    }

    // ==================== 核心承诺 ====================

    @Test
    @DisplayName("1_ 跨所有 key 返回全部版本（既有 findDefinitions 每个 key 只出最新一版）")
    void returnsEveryVersionOfEveryKey() {
        for (WfPersistence persistence : sampleImpls()) {
            List<WfDeploymentEntry> all =
                    new WfDeploymentQueryService(persistence).query(new WfDeploymentQuery());
            assertEquals(6, all.size(),
                    "**这是本轮存在的全部理由**：跨 key 的全部版本一条都不能少。"
                            + "少一条的症状是「上周部署过的流程从清单上消失了」，"
                            + "而调用方只会以为那天没部署。实际: " + keysOf(all));
        }
    }

    @Test
    @DisplayName("2_ key 精确 / 分类 / 停用 / 默认：两套实现逐条件同集合")
    void filtersMatchAcrossImpls() {
        assertSameAcrossImpls(new WfDeploymentQuery().setKey("leave"), "key 精确");
        assertSameAcrossImpls(new WfDeploymentQuery().setKeyLike("e"), "key 模糊");
        assertSameAcrossImpls(new WfDeploymentQuery().setNameLike("报销"), "名称模糊");
        assertSameAcrossImpls(new WfDeploymentQuery().setCategory("hr"), "分类");
        assertSameAcrossImpls(new WfDeploymentQuery().setSuspended(Boolean.TRUE), "只看停用");
        assertSameAcrossImpls(new WfDeploymentQuery().setHasSourceXml(Boolean.FALSE), "只看无原始 XML");
        // 组合条件：分类 + 无 XML —— 最接近真实运维问法的一类
        assertSameAcrossImpls(new WfDeploymentQuery()
                .setCategory("hr").setHasSourceXml(Boolean.FALSE), "分类 + 无原始 XML");
    }

    @Test
    @DisplayName("3_ hasSourceXml 是真实分界：JSON 部署的定义确实标成不可回读")
    void hasSourceXmlIsRealBoundary() {
        for (WfPersistence persistence : sampleImpls()) {
            WfDeploymentQueryService service = new WfDeploymentQueryService(persistence);
            List<String> withXml = keysOf(service.query(
                    new WfDeploymentQuery().setHasSourceXml(Boolean.TRUE)));
            List<String> withoutXml = keysOf(service.query(
                    new WfDeploymentQuery().setHasSourceXml(Boolean.FALSE)));
            assertEquals(Arrays.asList("purchase:1", "expense:2", "leave:2", "leave:1"), withXml,
                    "默认按时间倒序。实际: " + withXml);
            assertEquals(Arrays.asList("travel:1", "expense:1"), withoutXml,
                    "**expense:1 与 travel:1 是 JSON 部署的，getProcessModel 遇到它们会直接抛错** —— "
                            + "部署历史上标不出来，调用方只能挨个 key 试。实际: " + withoutXml);
        }
    }

    // ==================== 排序 ====================

    @Test
    @DisplayName("4_ 默认倒序按部署时间；同一毫秒先按 key 收口、同 key 再按版本倒序")
    void sortsByDeployTimeDescWithTiebreaker() {
        for (WfPersistence persistence : sampleImpls()) {
            List<String> ordered = keysOf(new WfDeploymentQueryService(persistence)
                    .query(new WfDeploymentQuery()));
            assertEquals(Arrays.asList(
                    "travel:1",                 // T0+4000
                    "purchase:1",               // T0+3000
                    "expense:2", "expense:1",   // 同为 T0+2000 —— key 相同，落版本倒序
                    "leave:2",                  // T0+1000
                    "leave:1"),                 // T0
                    ordered,
                    "**expense:2 必须在 expense:1 前面**（同 key 同时刻取新版本）："
                            + "部署历史倒序的用途是「我刚才部署的东西对不对」，"
                            + "同刻时把旧版本排在前面，看到的就正好相反。实际: " + ordered);
        }
    }

    @Test
    @DisplayName("5_ 四种排序两套实现同序；且同刻记录的相对次序不随正/倒序翻转")
    void allOrdersAgreeAcrossImpls() {
        assertSameAcrossImpls(new WfDeploymentQuery()
                .setOrderBy(WfDeploymentOrder.DEPLOY_TIME_ASC), "时间正序");
        assertSameAcrossImpls(new WfDeploymentQuery()
                .setOrderBy(WfDeploymentOrder.KEY_ASC), "key 升序");
        assertSameAcrossImpls(new WfDeploymentQuery()
                .setOrderBy(WfDeploymentOrder.KEY_DESC), "key 降序");

        for (WfPersistence persistence : sampleImpls()) {
            WfDeploymentQueryService service = new WfDeploymentQueryService(persistence);
            List<String> asc = keysOf(service.query(new WfDeploymentQuery()
                    .setOrderBy(WfDeploymentOrder.DEPLOY_TIME_ASC)));
            List<String> desc = keysOf(service.query(new WfDeploymentQuery()
                    .setOrderBy(WfDeploymentOrder.DEPLOY_TIME_DESC)));
            assertEquals(6, asc.size());
            assertEquals(6, desc.size());

            // **刻意不要求「正序是倒序的逆序」**：tiebreaker（key 升序 + 版本倒序）
            // 在两个方向上是同一条规则，所以同刻记录在正序里的相对次序
            // 与倒序里的相对次序**一致**（`expense:2` 在 `expense:1` 前面），
            // 整体并非镜像。
            // 这正是要的：tiebreaker 若跟着主键一起翻转，它就退化成"没有 tiebreaker"——
            // 同一毫秒的两条在两种排序下相对位置相反，翻页时就会漏行。
            assertEquals(Arrays.asList("expense:2", "expense:1"),
                    asc.subList(2, 4), "时间正序里同刻的次序。实际: " + asc);
            assertEquals(Arrays.asList("expense:2", "expense:1"),
                    desc.subList(2, 4), "**时间倒序里同刻的次序必须与正序一致** —— "
                            + "两边跟着主键一起翻转的话，同刻的两条在两种排序下相对位置相反，"
                            + "翻页就会漏行。实际: " + desc);
        }
    }

    // ==================== 分页 ====================

    @Test
    @DisplayName("6_ 翻页不重不漏：两页拼起来等于全量，且没有交集")
    void pagingNeitherDuplicatesNorDrops() {
        for (WfPersistence persistence : sampleImpls()) {
            WfDeploymentQueryService service = new WfDeploymentQueryService(persistence);
            List<String> page1 = keysOf(service.query(new WfDeploymentQuery()
                    .setPageNum(1).setPageSize(4)));
            List<String> page2 = keysOf(service.query(new WfDeploymentQuery()
                    .setPageNum(2).setPageSize(4)));

            assertEquals(4, page1.size(), "实际: " + page1);
            assertEquals(2, page2.size(), "实际: " + page2);

            List<String> combined = new ArrayList<>(page1);
            combined.addAll(page2);
            List<String> all = keysOf(service.query(new WfDeploymentQuery().setPageSize(0)));
            assertEquals(all, combined, "两页拼起来必须正好是全量。all=" + all + " combined=" + combined);

            for (String id : page1) {
                assertTrue(!page2.contains(id),
                        "**同一条出现在两页里**：翻页漏行/重行的症状是"
                                + "「第 2 页里有一条第 1 页也见过」，而没有任何报错。重复项=" + id);
            }
        }
    }

    @Test
    @DisplayName("7_ 页码越界返回空列表而不是最后一页（调用方据此知道该停）")
    void pageBeyondEndReturnsEmpty() {
        for (WfPersistence persistence : sampleImpls()) {
            WfDeploymentQueryService service = new WfDeploymentQueryService(persistence);
            assertTrue(service.query(new WfDeploymentQuery().setPageNum(99).setPageSize(10)).isEmpty(),
                    "越界页必须为空 —— 回退到最后一页会让调用方以为「还有更多」");
        }
    }

    // ==================== 边界与拒绝 ====================

    @Test
    @DisplayName("8_ 时间区间含端点（端点恰好落在某条部署时间上）；deployTime 为 null 一律不匹配")
    void timeRangeIsInclusiveAndNullNeverMatches() {
        for (WfPersistence persistence : sampleImpls()) {
            WfDeploymentQueryService service = new WfDeploymentQueryService(persistence);

            // **端点闭合必须用「恰好等于某条部署时间」的值来验**，
            // 而不是拿一个比所有行都早的下界 —— 那样每条都落在窗内，
            // 闭不闭合都给同一个结果，判据看着绿其实什么都没验。
            assertEquals(Arrays.asList("leave:1"),
                    keysOf(service.query(new WfDeploymentQuery()
                            .setDeployedTo(new java.util.Date(T0)))),
                    "**上界恰好等于 leave:1 的部署时间时它必须在** —— "
                            + "闭区间漏掉端点，症状是「按时间窗查部署，昨天刚部署的那条不见了」。"
                            + "实际: " + keysOf(service.query(new WfDeploymentQuery()
                            .setDeployedTo(new java.util.Date(T0)))));
            assertEquals(Arrays.asList("travel:1"),
                    keysOf(service.query(new WfDeploymentQuery()
                            .setDeployedFrom(new java.util.Date(T0 + 4000)))),
                    "**下界恰好等于 travel:1 的部署时间时它必须在**。实际: "
                            + keysOf(service.query(new WfDeploymentQuery()
                            .setDeployedFrom(new java.util.Date(T0 + 4000)))));

            // 中间一窗：leave:2 / expense:1 / expense:2
            List<String> window = keysOf(service.query(new WfDeploymentQuery()
                    .setDeployedFrom(new java.util.Date(T0 + 1000))
                    .setDeployedTo(new java.util.Date(T0 + 2000))));
            assertEquals(Arrays.asList("expense:2", "expense:1", "leave:2"), window,
                    "中间一窗，两端都是闭的。实际: " + window);
        }

        // deployTime 为 null：不匹配任何时间窗
        WfPersistence memory = new InMemoryWorkflowPersistence();
        WfDefinition noTime = new WfXmlParser().parse(NS
                + "  <process id=\"noTime\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/><endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"e\"/>\n"
                + "  </process>\n</definitions>\n");
        noTime.setVersion(1);
        noTime.setStartTime(null);
        memory.saveDefinition(noTime);
        WfDeploymentQueryService service = new WfDeploymentQueryService(memory);
        assertEquals(1, service.query(new WfDeploymentQuery()).size(), "不带时间条件时它该在");
        assertTrue(service.query(new WfDeploymentQuery()
                        .setDeployedFrom(new java.util.Date(0))).isEmpty(),
                "**deployTime 为 null 时不许匹配任何时间窗** —— "
                        + "当 0 毫秒算等于说「它部署在 1970 年」，"
                        + "而它会在任意一窗的清单里出现");
        assertTrue(service.query(new WfDeploymentQuery()
                        .setDeployedTo(new java.util.Date(Long.MAX_VALUE))).isEmpty(),
                "上界给到最大也不该把 null 时间的行捞进来");
        // 排序：null 时间排最后，两种时间序都一样
        WfDefinition withTime = new WfXmlParser().parse(NS
                + "  <process id=\"withTime\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/><endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"e\"/>\n"
                + "  </process>\n</definitions>\n");
        withTime.setVersion(1);
        withTime.setStartTime(new java.util.Date(T0));
        memory.saveDefinition(withTime);
        assertEquals("noTime:1", keysOf(service.query(new WfDeploymentQuery()
                        .setOrderBy(WfDeploymentOrder.DEPLOY_TIME_ASC))).get(1),
                "**null 时间在时间正序里也必须排最后** —— "
                        + "否则「先部署的最早」这个读数会把一条没有部署时间的记录说成第一");
    }

    @Test
    @DisplayName("9_ 区间写反当场报错，不返回空集")
    void reversedRangeThrows() {
        for (WfPersistence persistence : sampleImpls()) {
            WfEngineException error = assertThrows(WfEngineException.class,
                    () -> new WfDeploymentQueryService(persistence).query(new WfDeploymentQuery()
                            .setDeployedFrom(new java.util.Date(T0 + 5000))
                            .setDeployedTo(new java.util.Date(T0))),
                    "写反了要报错。返回空集的话，「查不到」这个答案有至少两种解释："
                            + "这段时间没部署过，或者你把两个值写反了");
            assertTrue(error.getMessage().contains("上界"),
                    "错消息要说清是哪一端: " + error.getMessage());
        }
    }

    @Test
    @DisplayName("10_ count 与 list 走同一套过滤（否则列表 3 条而 count 说 6 条）")
    void countAgreesWithList() {
        for (WfPersistence persistence : sampleImpls()) {
            WfDeploymentQueryService service = new WfDeploymentQueryService(persistence);
            WfDeploymentQuery query = new WfDeploymentQuery()
                    .setCategory("hr").setHasSourceXml(Boolean.FALSE).setPageSize(1);
            List<WfDeploymentEntry> page = service.query(query);
            assertEquals(1, page.size());
            assertEquals(service.count(query), service.query(
                            new WfDeploymentQuery().setCategory("hr")
                                    .setHasSourceXml(Boolean.FALSE).setPageSize(0)).size(),
                    "**count 必须与「不分页的 list」一致** —— "
                            + "不一致时调用方只会以为自己算错了。");
            assertEquals(1, service.count(query), "hr + 无 XML 只有 travel:1 这一条");
        }
    }

    @Test
    @DisplayName("11_ 没有任何数据时返回空列表而不是 null")
    void emptyWhenNothingDeployed() {
        for (WfPersistence persistence : Arrays.asList(
                new InMemoryWorkflowPersistence(), new JdbcWorkflowPersistence(h2DataSource()))) {
            WfDeploymentQueryService service = new WfDeploymentQueryService(persistence);
            assertNotNull(service.query(new WfDeploymentQuery()));
            assertTrue(service.query(new WfDeploymentQuery()).isEmpty());
            assertEquals(0, service.count(new WfDeploymentQuery()));
        }
    }

    @Test
    @DisplayName("12_ 扫描量超上限报错，不给一份缺行的清单")
    void scanOverflowThrowsInsteadOfTruncating() {
        WfPersistence memory = new InMemoryWorkflowPersistence();
        for (int i = 0; i <= WfDeploymentQueryService.MAX_SCAN; i++) {
            WfDefinition definition = new WfXmlParser().parse(NS
                    + "  <process id=\"k" + i + "\" isExecutable=\"true\">\n"
                    + "    <startEvent id=\"s\"/><endEvent id=\"e\"/>\n"
                    + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"e\"/>\n"
                    + "  </process>\n</definitions>\n");
            definition.setVersion(1);
            definition.setStartTime(new java.util.Date(T0 + i));
            memory.saveDefinition(definition);
        }
        WfEngineException error = assertThrows(WfEngineException.class,
                () -> new WfDeploymentQueryService(memory).query(new WfDeploymentQuery()),
                "**截断的部署清单比报错坏得多**：它看起来就是全部，"
                        + "而运维会据此得出「上周只部署了 3 个流程」");
        assertTrue(error.getMessage().contains(String.valueOf(WfDeploymentQueryService.MAX_SCAN)),
                "错消息要写出上限是多少: " + error.getMessage());
    }

    // ==================== 夹具 ====================

    /**
     * 一个<b>全新的</b> H2 库。
     *
     * <p>每次都新建（URL 带 nanoTime）而不是复用：本类的判据要断言
     * "全部版本都在" 与 "没有任何数据时是空的"，共用一个库会互相污染 ——
     * 前一条留了 6 行，后一条的"空集合"就成了 6 行，
     * 而症状是"偶发地不空"，排查时极难想到是夹具共享。
     */
    private static DataSource h2DataSource() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:deploy" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        JdbcWorkflowPersistence persistence = new JdbcWorkflowPersistence(ds);
        persistence.initialize();
        return ds;
    }
}
