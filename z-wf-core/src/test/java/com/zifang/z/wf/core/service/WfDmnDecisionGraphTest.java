package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.dmn.WfDmnDecision;
import com.zifang.z.wf.core.definition.dmn.WfDmnParser;
import com.zifang.z.wf.core.engine.dmn.WfDmnDecisionResult;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.JdbcWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfPersistence;

/**
 * DMN 决策图（第 35 轮）—— {@code informationRequirement} 组成的有向图。
 *
 * <p>第 23 轮只做决策表，部署期直接拒绝决策图，理由写在当时的文档里：
 * 「折成按声明顺序跑一遍，会在依赖顺序与声明顺序不一致的图上算出错误结果，
 * 而这种错误不会报错」。那条理由本身是对的 —— 它说错的只是"折"的实现方式。
 * 本轮改成<b>按依赖关系递归展开</b>，声明顺序于是彻底不再影响结果。
 *
 * <p>本类盯七件错了都不报错的事：
 * <ol>
 *   <li><b>声明顺序不能影响结果</b>。这是本轮存在的理由，
 *       对应判据 {@link #declarationOrderDoesNotMatter()}。</li>
 *   <li><b>依赖边必须活过持久化</b>。丢在持久化上的症状是
 *       「部署正常、求值正常，只是上游那一跳凭空消失」——
 *       算出来的值是错的，而没有任何报错。</li>
 *   <li><b>成环必须在部署期挡掉</b>，且挡掉时<b>一条都不能落库</b>
 *       （留半套比全不部署更难收拾）。</li>
 *   <li><b>跨文件的环也要挡</b>：只看本文件的边会漏掉它，
 *       而那种环单文件测试一条都测不出来。</li>
 *   <li><b>上游 0 行 / 多行都要报错</b>。放行的症状都是一张<b>空结果的表</b>。</li>
 *   <li><b>同名变量取值冲突要报错</b>：两个来源给出不同答案时，
 *       「用哪个」没有答案。</li>
 *   <li><b>返回的结果只属于本决策</b>，上游的输出进的是求值上下文。</li>
 * </ol>
 */
class WfDmnDecisionGraphTest {

    private static final String DEFINITIONS_HEAD =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\">\n";

    private InMemoryWorkflowPersistence repo;
    private WfDecisionService decisions;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        decisions = new WfDecisionService(repo);
    }

    private Map<String, Object> x(Object value) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("x", value);
        return variables;
    }

    // ==================== 解析 ====================

    @Test
    @DisplayName("informationRequirement/requiredDecision 的 href=\"#id\" 被读成依赖边")
    void requiredDecisionBecomesAnEdge() {
        String xml = file(
                decision("down", ids("up"), "UNIQUE", cols("x"), rules("-"), "out", out("\"v\"")),
                decision("up", null, "UNIQUE", cols("x"), rules("-"), "out", out("\"u\"")));
        List<WfDmnDecision> parsed = new WfDmnParser().parse(xml);
        assertEquals(2, parsed.size());
        assertEquals(Arrays.asList("up"), parsed.get(0).getRequiredDecisions(),
                "down 的依赖边应当是 [up]（只读 requiredDecision，不读别的）");
        assertTrue(parsed.get(1).getRequiredDecisions().isEmpty(),
                "up 没有 informationRequirement，依赖边必须是空列表而不是 null");
    }

    @Test
    @DisplayName("requiredInput / knowledgeSource 不进依赖边（引擎不执行它们），也不报错")
    void nonDecisionRequirementsAreIgnored() {
        // 与 Camunda 一致：输入数据节点与知识源没有执行语义。
        // 但它们**不能**被当成决策依赖塞进图里 —— 那样求值会去查一个
        // 永远查不到的决策，报错还指向一个作者从没写过的 key。
        String xml = DEFINITIONS_HEAD
                + "  <decision id=\"solo\" name=\"solo\">\n"
                + "    <informationRequirement><requiredInput href=\"#data\"/>"
                + "</informationRequirement>\n"
                + "    <informationRequirement><knowledgeSource href=\"#ks\"/>"
                + "</informationRequirement>\n"
                + "    <decisionTable id=\"t\" hitPolicy=\"UNIQUE\">\n"
                + "      <input id=\"i\"><inputExpression id=\"ie\"><text>x</text>"
                + "</inputExpression></input>\n"
                + "      <output id=\"o\" name=\"out\"/>\n"
                + "      <rule><inputEntry><text>-</text></inputEntry>"
                + "<outputEntry><text>\"v\"</text></outputEntry></rule>\n"
                + "    </decisionTable>\n"
                + "  </decision>\n"
                + "  <inputData id=\"data\" name=\"data\"/>\n"
                + "  <knowledgeSource id=\"ks\" name=\"ks\"/>\n"
                + "</definitions>\n";
        WfDmnDecision parsed = new WfDmnParser().parse(xml).get(0);
        assertTrue(parsed.getRequiredDecisions().isEmpty(),
                "requiredInput/knowledgeSource 不是决策依赖，实际拿到: "
                        + parsed.getRequiredDecisions());
    }

    @Test
    @DisplayName("href 不是 #id 形式 ⇒ 解析期报错，不能拖到求值期变成「依赖不存在」")
    void nonHashHrefIsRejectedAtParseTime() {
        // 拖到求值期的话，那句报错是「决策 [riskTier] 不存在（没有任何版本）」，
        // 作者会去部署它 —— 而它本来就在文件里，只是引用形式读不出来。
        // 注意断的是 href **不带 #** 的那种：href="#urn:dmn:x" 是合法的
        // （id 就叫 urn:dmn:x），拿它当反例会得到一条恒绿的断言。
        String xml = DEFINITIONS_HEAD
                + "  <decision id=\"down\" name=\"down\">\n"
                + "    <informationRequirement><requiredDecision href=\"riskTier\"/>"
                + "</informationRequirement>\n"
                + "    <decisionTable id=\"t\" hitPolicy=\"UNIQUE\">\n"
                + "      <output id=\"o\" name=\"out\"/>\n"
                + "      <rule><outputEntry><text>\"v\"</text></outputEntry></rule>\n"
                + "    </decisionTable>\n"
                + "  </decision>\n"
                + "</definitions>\n";
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> new WfDmnParser().parse(xml));
        assertTrue(ex.getMessage().contains("riskTier"),
                "报错要点名那个读不出来的引用: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("#决策id"),
                "报错要说清本实现只认哪种形式，否则作者不知道该改成什么: " + ex.getMessage());
    }

    // ==================== 求值：链 ====================

    /**
     * 三级链：{@code category(x) -> tier(category) -> level(tier)}。
     *
     * <p>文件次序是上游优先；反向的那一条在 {@link #declarationOrderDoesNotMatter()}。
     */
    @Test
    @DisplayName("三级决策链：上游的输出逐级成为下游的输入")
    void threeLevelChainResolvesUpstreamOutputs() {
        decisions.deployDecision(chainFile("", 0, 1, 2));
        assertEquals("ceo", decisions.evaluateDecision("level", x(1))
                .singleOutput().get("level"),
                "x>0 ⇒ category=A ⇒ tier=gold ⇒ level=ceo");
        assertEquals("staff", decisions.evaluateDecision("level", x(-1))
                .singleOutput().get("level"),
                "x<=0 ⇒ category=B ⇒ tier=silver ⇒ level=staff");
    }

    /**
     * 本轮存在的理由：声明顺序与依赖顺序不一致时结果仍然要对。
     *
     * <p>文件里<b>先写最下游</b>。任何"按声明顺序跑一遍"的实现都会在
     * {@code level} 那一步读到空的 {@code tier} —— 而按本仓 fail-closed 约定，
     * 读不到输入的列<b>一条规则都不命中</b>，于是结果是空表、无报错。
     * 这就是第 23 轮拒绝决策图时写下的那条理由。
     */
    @Test
    @DisplayName("依赖顺序与声明顺序相反 ⇒ 结果不变（这条是本轮存在的理由）")
    void declarationOrderDoesNotMatter() {
        String xml = chainFile("", 2, 1, 0);
        // 先把"文件次序真的是反的"钉在断言里。不断这一句的话，
        // 将来有人把次序改回上游优先，这条判据会**因错误的原因继续绿** ——
        // 它断的就不再是本轮要断的那件事了。
        List<WfDmnDecision> parsed = new WfDmnParser().parse(xml);
        assertEquals("level", parsed.get(0).getKey(),
                "前置条件：文件里第一个 decision 必须是最下游的 level，实际次序: "
                        + parsed);

        decisions.deployDecision(xml);
        WfDmnDecisionResult result = decisions.evaluateDecision("level", x(1));
        assertEquals("ceo", result.singleOutput().get("level"),
                "上游虽然写在后面，递归展开仍要先算它 —— 实际结果: " + result.singleOutput());
    }

    @Test
    @DisplayName("菱形依赖：公共上游只被展开一次，下游拿得到两条分支的输出")
    void diamondDependencyResolvesBothBranches() {
        // a -> b -> d ；a -> c -> d （d 同时依赖 b 与 c）
        decisions.deployDecision(file(
                decision("a", null, "UNIQUE", cols("x"), rules("-"), "a", out("\"A\"")),
                decision("b", ids("a"), "UNIQUE", cols("a"), rules("-"), "b", out("\"B\"")),
                decision("c", ids("a"), "UNIQUE", cols("a"), rules("-"), "c", out("\"C\"")),
                decision("d", ids("b", "c"), "UNIQUE", cols("b", "c"),
                        grid(new String[]{"== \"B\"", "== \"C\""}), "d", out("\"BC\""))));
        assertEquals("BC", decisions.evaluateDecision("d", x(1)).singleOutput().get("d"),
                "d 的规则要求 b==B 且 c==C —— 少一条分支就不会命中");
    }

    @Test
    @DisplayName("返回值只属于本决策：命中条数是本表的，不是整条链累加的")
    void resultBelongsToTheEvaluatedDecisionOnly() {
        decisions.deployDecision(chainFile("", 0, 1, 2));
        WfDmnDecisionResult result = decisions.evaluateDecision("level", x(1));
        assertEquals("level", result.getDecisionKey());
        // level 表只有两条规则，tier=gold 时两条都命中；
        // 把上游的命中数并进来会是 4 或 5
        assertEquals(2, result.getMatchedRuleCount(),
                "matchedRuleCount 必须是本表的命中数 —— 上游的输出进的是求值上下文，不是结果");
        assertEquals(1, result.getRows().size(), "结果行只来自本决策的表");
    }

    // ==================== 成环 ====================

    @Test
    @DisplayName("同一文件内成环 ⇒ 部署失败，且一条都不能落库")
    void intraFileCycleIsRejectedAndNothingIsSaved() {
        String xml = file(
                decision("p", ids("q"), "UNIQUE", cols("x"), rules("-"), "out", out("\"p\"")),
                decision("q", ids("p"), "UNIQUE", cols("x"), rules("-"), "out", out("\"q\"")));
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> decisions.deployDecision(xml));
        assertTrue(ex.getMessage().contains("成环"),
                "报错要点明是成环（症状是栈溢出，不是「依赖不存在」）: " + ex.getMessage());
        // 先存后查的话，这里会留下已落库的 p —— 而它引用了一个永远算不出来的 q
        assertNull(repo.findLatestDecision("p"), "环被挡下时一个决策都不能落库");
        assertNull(repo.findLatestDecision("q"), "环被挡下时一个决策都不能落库");
    }

    @Test
    @DisplayName("跨文件成环 ⇒ 第二次部署失败（只看本文件的边会漏掉它）")
    void crossFileCycleIsRejected() {
        // 第一份：p 依赖 q，而 q 这时还不存在 —— 依赖允许后于被依赖者部署，不是环
        decisions.deployDecision(file(
                decision("p", ids("q"), "UNIQUE", cols("x"), rules("-"), "out", out("\"p\""))));
        assertNotNull(repo.findLatestDecision("p"), "依赖还没部署不是环，第一份应当成功");

        // 第二份：q 依赖 p。单看这一份没有环，凑上库里那条边就有了
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> decisions.deployDecision(file(
                        decision("q", ids("p"), "UNIQUE", cols("x"), rules("-"),
                                "out", out("\"q\"")))));
        assertTrue(ex.getMessage().contains("成环"), "报错要点明是成环: " + ex.getMessage());
        assertNull(repo.findLatestDecision("q"), "成环的那一份不能落库");
    }

    @Test
    @DisplayName("决策依赖自己 ⇒ 部署期报错")
    void selfDependencyIsRejected() {
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> decisions.deployDecision(file(
                        decision("loop", ids("loop"), "UNIQUE", cols("x"), rules("-"),
                                "out", out("\"v\"")))));
        assertTrue(ex.getMessage().contains("成环"), "自依赖是最短的环: " + ex.getMessage());
    }

    @Test
    @DisplayName("绕过部署塞进库的环 ⇒ 求值期报错（运行期最后一道防线）")
    void runtimeCycleGuardCatchesBypassedDeployment() {
        // 直接改持久化里的依赖边，绕开 deployDecision 的环检测。
        // 这不是"重复检查部署期"，而是自定义装配 / 外部灌数据的兜底。
        decisions.deployDecision(file(
                decision("p", null, "UNIQUE", cols("x"), rules("-"), "out", out("\"p\""))));
        decisions.deployDecision(file(
                decision("q", null, "UNIQUE", cols("x"), rules("-"), "out", out("\"q\""))));
        repo.saveDecision(withRequires(repo.findLatestDecision("p"), "q"));
        repo.saveDecision(withRequires(repo.findLatestDecision("q"), "p"));

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> decisions.evaluateDecision("p", x(1)));
        assertTrue(ex.getMessage().contains("环"),
                "运行期兜底要说清是环（症状是栈溢出）: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("绕过"),
                "报错要指出部署被绕过了，否则作者会去查部署而那里什么也没有: "
                        + ex.getMessage());
    }

    // ==================== 求值：不停下来的那些情形 ====================

    @Test
    @DisplayName("上游一条都没命中 ⇒ 报错，不是一张空结果的表")
    void upstreamWithNoMatchStops() {
        // 放行的症状：上游 0 行 ⇒ 下游读不到输入 ⇒ 下游也 0 行 ⇒
        // 调用方拿到一张**空结果**的表，而整条链没有任何报错。
        decisions.deployDecision(file(
                decision("up", null, "UNIQUE", cols("x"), rules("> 1000"),
                        "tier", out("\"gold\"")),
                decision("down", ids("up"), "UNIQUE", cols("tier"), rules("-"),
                        "level", out("\"ceo\""))));
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> decisions.evaluateDecision("down", x(1)));
        assertTrue(ex.getMessage().contains("[up]"),
                "报错要点名是哪个上游没命中: " + ex.getMessage());
    }

    @Test
    @DisplayName("上游多结果 ⇒ 报错，不替你挑一行")
    void upstreamWithMultipleRowsStops() {
        // COLLECT 命中两条产两行。下游需要的是一个值 —— 挑第一行就是在编数据。
        decisions.deployDecision(file(
                decision("up", null, "COLLECT", cols("x"), rules("-", "-"),
                        "tier", out("\"gold\"", "\"silver\"")),
                decision("down", ids("up"), "UNIQUE", cols("tier"), rules("-"),
                        "level", out("\"ceo\""))));
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> decisions.evaluateDecision("down", x(1)));
        assertTrue(ex.getMessage().contains("COLLECT"),
                "报错要点名是哪条策略产出了多行: " + ex.getMessage());
    }

    @Test
    @DisplayName("调用方传了同名变量且取值不同 ⇒ 报错（两个来源，用哪个没有答案）")
    void conflictingVariableFromCallerStops() {
        decisions.deployDecision(file(
                decision("up", null, "UNIQUE", cols("x"), rules("-"), "tier", out("\"gold\"")),
                decision("down", ids("up"), "UNIQUE", cols("tier"), rules("-"),
                        "level", out("\"ceo\""))));
        Map<String, Object> variables = x(1);
        variables.put("tier", "silver");
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> decisions.evaluateDecision("down", variables));
        assertTrue(ex.getMessage().contains("tier"), "报错要点名冲突的变量: " + ex.getMessage());
    }

    @Test
    @DisplayName("同名变量取值相同 ⇒ 不报错（求值出的 5.0 与传入的 5 是同一个数）")
    void sameNumericValueFromCallerIsNotAConflict() {
        decisions.deployDecision(file(
                decision("up", null, "UNIQUE", cols("x"), rules("-"), "n", out("5.0")),
                decision("down", ids("up"), "UNIQUE", cols("n"), rules("-"),
                        "level", out("\"ceo\""))));
        Map<String, Object> variables = x(1);
        variables.put("n", 5);

        // 前置条件：两侧**类型真的不同**，否则这条用例走不到数值比较那一支 ——
        // 实测字面量 5 求出来是 Integer，而调用方传的也是 Integer，
        // 用 equals 判照样相等，于是这条判据会**因错误的原因保持绿**。
        // 反向验证 M7（把数值比较换成 equals）就是靠这一句才能变红。
        Object upstream = decisions.evaluateDecision("up", x(1)).singleOutput().get("n");
        assertFalse(upstream instanceof Integer,
                "前置条件：上游产出必须是 Double，实际拿到 " + upstream
                        + "（类型 " + (upstream == null ? "?" : upstream.getClass().getName())
                        + "）。若它与调用方传入的是同一个类型，本用例证明不了跨类型比较");

        // 上游算出的是 Double 5.0，调用方传的是 Integer 5。
        // 用 equals 判它们不等的话，作者会看到一个自己没写过的报错。
        assertEquals("ceo", decisions.evaluateDecision("down", variables)
                .singleOutput().get("level"), "同一个数不该被判成冲突");
    }

    @Test
    @DisplayName("依赖的决策没部署 ⇒ 报错要点名那个 key，并说清部署期为什么不拦")
    void missingRequiredDecisionStops() {
        decisions.deployDecision(file(
                decision("down", ids("neverDeployed"), "UNIQUE", cols("tier"), rules("-"),
                        "level", out("\"ceo\""))));
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> decisions.evaluateDecision("down", x(1)));
        assertTrue(ex.getMessage().contains("neverDeployed"),
                "报错要点名缺的是哪个 key: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("后于被依赖者部署"),
                "要说清依赖为何不在部署期报错，否则作者会去部署期找: " + ex.getMessage());
    }

    // ==================== 持久化往返 ====================

    @Test
    @DisplayName("依赖边要活过持久化往返：内存深拷贝与 JDBC 两条路径都要断")
    void requiredDecisionsSurviveTheRoundTrip() {
        // 丢在持久化上的症状最安静：部署正常、求值正常，只是上游那一跳
        // 凭空消失 —— 算出来的值是错的，没有任何报错。
        // 而"决策图悄悄退化成单表"在只测一种持久化时根本看不出来。
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:drg_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        JdbcWorkflowPersistence jdbc = new JdbcWorkflowPersistence(ds);
        jdbc.initialize();

        for (WfPersistence target : new WfPersistence[]{repo, jdbc}) {
            String kind = target.getClass().getSimpleName();
            // 两种实现各有自己那份已部署的决策，key 要分开
            String suffix = kind.substring(0, 3);
            WfDecisionService service = new WfDecisionService(target);
            service.deployDecision(chainFile(suffix, 0, 1, 2));

            assertEquals(Arrays.asList("category" + suffix),
                    target.findLatestDecision("tier" + suffix).getRequiredDecisions(),
                    kind + "：依赖边在往返后丢了 —— 决策图会静默退化成单表");
            assertEquals(Arrays.asList("tier" + suffix),
                    target.findLatestDecision("level" + suffix).getRequiredDecisions(),
                    kind + "：依赖边在往返后丢了");
            assertTrue(target.findLatestDecision("category" + suffix)
                            .getRequiredDecisions().isEmpty(),
                    kind + "：没有依赖的决策读回来应是空列表而不是 null");

            assertEquals("ceo", service.evaluateDecision("level" + suffix, x(1))
                    .singleOutput().get("level"),
                    kind + "：往返之后整条链仍然算得对");
        }
    }

    // ==================== 夹具 ====================

    private static String[] cols(String... names) {
        return names;
    }

    /** 每条规则一个输入项（表只有一列输入时用这个）。 */
    private static String[][] rules(String... entries) {
        String[][] grid = new String[entries.length][];
        for (int i = 0; i < entries.length; i++) {
            grid[i] = new String[]{entries[i]};
        }
        return grid;
    }

    /**
     * 表有多列输入时用这个：每条规则给一个数组，数组里每个输入项对应一列。
     *
     * <p>不能拿 {@link #rules} 顶替 —— 它把每个入参当成一条规则，
     * 于是「两列输入、一条规则」会被铺成两条规则，各缺一半输入项，
     * 报的是数组越界而不是任何与决策图有关的事。
     */
    private static String[][] grid(String[]... oneRuleEach) {
        return oneRuleEach;
    }

    private static String[] out(String... values) {
        return values;
    }

    /**
     * 造一条三级链的三个 decision，按<b>依赖次序</b>返回：上游、中游、下游。
     *
     * <p>返回次序与文件次序是两件事 —— 文件次序由 {@link #chainFile} 决定。
     * 把这两者拆开写，是因为"次序反了"正是本轮要断的那件事；
     * 夹具里一旦把它写死成上游优先，那条判据就会变成对另一件事绿灯。
     *
     * @param suffix 追加到 key 后面，这样同一份链可以在两种持久化里各部署一次
     */
    private static String[] chainDecisions(String suffix) {
        String category = "category" + suffix;
        String tier = "tier" + suffix;
        String level = "level" + suffix;
        return new String[]{
                decision(category, null, "FIRST", cols("x"), rules("> 0", "-"),
                        "category", out("\"A\"", "\"B\"")),
                decision(tier, ids(category), "FIRST", cols("category"),
                        rules("== \"A\"", "-"), "tier", out("\"gold\"", "\"silver\"")),
                decision(level, ids(tier), "FIRST", cols("tier"),
                        rules("== \"gold\"", "-"), "level", out("\"ceo\"", "\"staff\""))
        };
    }

    /**
     * 按<b>文件里出现的次序</b>把三级链拼成一个 definitions。
     *
     * @param order 0/1/2 分别指上游/中游/下游，可任意排列。
     *              {@code 0,1,2} 是上游优先，{@code 2,1,0} 是把依赖写在后面那种反序
     */
    private static String chainFile(String suffix, int... order) {
        String[] parts = chainDecisions(suffix);
        StringBuilder xml = new StringBuilder(DEFINITIONS_HEAD);
        for (int index : order) {
            xml.append(parts[index]);
        }
        return xml.append("</definitions>\n").toString();
    }

    private static String file(String... decisions) {
        StringBuilder xml = new StringBuilder(DEFINITIONS_HEAD);
        for (String one : decisions) {
            xml.append(one);
        }
        return xml.append("</definitions>\n").toString();
    }

    private static List<String> ids(String... keys) {
        return Arrays.asList(keys);
    }

    /**
     * 造一个 {@code <decision>}。
     *
     * @param inputColumns 输入列的 {@code inputExpression} 文本
     * @param entries      每条规则一个输入项，写法沿用决策表那条单目测试补全约定
     * @param outputs      每条规则一个输出项
     */
    private static String decision(String id, List<String> requires, String hitPolicy,
                                   String[] inputColumns, String[][] ruleEntries,
                                   String outputColumn, String[] outputs) {
        StringBuilder xml = new StringBuilder();
        xml.append("  <decision id=\"").append(id).append("\" name=\"").append(id).append("\">\n");
        if (requires != null) {
            for (String required : requires) {
                xml.append("    <informationRequirement><requiredDecision href=\"#")
                        .append(required).append("\"/></informationRequirement>\n");
            }
        }
        xml.append("    <decisionTable id=\"t").append(id).append("\" hitPolicy=\"")
                .append(hitPolicy).append("\">\n");
        if (inputColumns != null) {
            for (String column : inputColumns) {
                xml.append("      <input id=\"i").append(column).append("\">")
                        .append("<inputExpression id=\"ie").append(column).append("\"><text>")
                        .append(column).append("</text></inputExpression></input>\n");
            }
        }
        xml.append("      <output id=\"o\" name=\"").append(outputColumn).append("\"/>\n");
        for (int i = 0; i < ruleEntries.length; i++) {
            xml.append("      <rule>");
            for (String entry : ruleEntries[i]) {
                xml.append("<inputEntry><text>").append(entry).append("</text></inputEntry>");
            }
            xml.append("<outputEntry><text>").append(outputs[i])
                    .append("</text></outputEntry></rule>\n");
        }
        xml.append("    </decisionTable>\n  </decision>\n");
        return xml.toString();
    }

    /** 给一个已加载的决策加上依赖边（用来绕过部署，测运行期防线）。 */
    private static WfDmnDecision withRequires(WfDmnDecision source, String requiredKey) {
        source.setRequiredDecisions(Arrays.asList(requiredKey));
        return source;
    }
}