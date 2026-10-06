package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.dmn.WfDmnDecision;
import com.zifang.z.wf.core.definition.dmn.WfDmnHitPolicy;
import com.zifang.z.wf.core.definition.dmn.WfDmnParser;
import com.zifang.z.wf.core.engine.dmn.WfDmnDecisionResult;
import com.zifang.z.wf.core.engine.dmn.WfDmnViolationException;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.JdbcWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.service.WfEngineException;

/**
 * DMN 决策表（第 23 轮）。
 *
 * <p>它回答的是审批里最容易被写成"一串 if"的一件事：
 * <b>按金额与单据类型算出审批层级</b>。写进 BPMN 的排他网关，
 * 每加一个门槛就要加一条出线和一张表单；而写成决策表，规则与条件是分开的，
 * 业务方自己能看懂那张表。
 *
 * <p>本类盯五件错了都不报错的事：
 * <ol>
 *   <li><b>命中策略必须真的按策略走</b>。UNIQUE 命中两条时取第一条，
 *       症状是「同一类单据两次算出不同结果」；ANY 输出不一致时放行，
 *       症状是「规则重叠了但没人知道」。两种都必须停下。</li>
 *   <li><b>规则顺序是契约</b>（{@code FIRST} / {@code RULE_ORDER}），
 *       所以表存的是 {@link List} 不是 {@link java.util.Set}。</li>
 *   <li><b>输出值落在 {@code outputValues} 列表里就展开成整个列表</b> ——
 *       这是 DMN 的标准多值输出语义，不是本实现自创的。</li>
 *   <li><b>输入项引用未定义变量 ⇒ 该规则不命中</b>（而不是当它成立），
 *       与网关条件同一条 fail-closed 约定。</li>
 *   <li><b>FEEL 构造在部署期就被挡下</b>。本仓用的是 z-util 的 EL，与 FEEL
 *       不是同一门语言；留着它的话，症状是「那条规则永远不命中」而表上完全看不出异常。</li>
 * </ol>
 */
class WfDecisionServiceTest {

    /**
     * 审批层级决策表 —— 经典形状。
     *
     * <p>{@code FIRST} + 大额规则在前：规则顺序就是优先级，
     * 这正是 {@code FIRST} 与 {@code UNIQUE} 的区别所在
     * （UNIQUE 要求规则不重叠，而金额门槛天然重叠）。
     */
    private static final String APPROVAL_DMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\""
            + " id=\"dmn\" name=\"approval\">\n"
            + "  <decision id=\"approvalLevel\" name=\"审批层级\">\n"
            + "    <decisionTable id=\"dt\" hitPolicy=\"FIRST\">\n"
            + "      <input id=\"i1\" label=\"金额\">\n"
            + "        <inputExpression id=\"ie1\" typeRef=\"number\">\n"
            + "          <text>amount</text>\n"
            + "        </inputExpression>\n"
            + "      </input>\n"
            + "      <input id=\"i2\" label=\"类型\">\n"
            + "        <inputExpression id=\"ie2\" typeRef=\"string\">\n"
            + "          <text>category</text>\n"
            + "        </inputExpression>\n"
            + "      </input>\n"
            + "      <output id=\"o1\" label=\"层级\" name=\"level\" typeRef=\"string\"/>\n"
            + "      <rule id=\"r1\">\n"
            + "        <inputEntry id=\"r1i1\"><text>&gt; 50000</text></inputEntry>\n"
            + "        <inputEntry id=\"r1i2\"><text>-</text></inputEntry>\n"
            + "        <outputEntry id=\"r1o1\"><text>\"ceo\"</text></outputEntry>\n"
            + "      </rule>\n"
            + "      <rule id=\"r2\">\n"
            + "        <inputEntry id=\"r2i1\"><text>&gt; 5000</text></inputEntry>\n"
            + "        <inputEntry id=\"r2i2\"><text>-</text></inputEntry>\n"
            + "        <outputEntry id=\"r2o1\"><text>\"director\"</text></outputEntry>\n"
            + "      </rule>\n"
            + "      <rule id=\"r3\">\n"
            + "        <inputEntry id=\"r3i1\"><text>-</text></inputEntry>\n"
            + "        <inputEntry id=\"r3i2\"><text>== \"normal\"</text></inputEntry>\n"
            + "        <outputEntry id=\"r3o1\"><text>\"staff\"</text></outputEntry>\n"
            + "      </rule>\n"
            + "    </decisionTable>\n"
            + "  </decision>\n"
            + "</definitions>\n";

    /** 单列单规则，用来单独验证某一条策略，不被上面那张表的规则顺序干扰。 */
    private static String singleRule(String hitPolicy, String entry, String output,
                                     String outputValues) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\"");
        xml.append(" id=\"d\" name=\"d\">\n");
        xml.append("  <decision id=\"solo\" name=\"solo\">\n");
        xml.append("    <decisionTable id=\"t\"");
        if (hitPolicy != null) {
            xml.append(" hitPolicy=\"").append(hitPolicy).append("\"");
        }
        xml.append(">\n");
        xml.append("      <input id=\"i\"><inputExpression id=\"ie\"><text>x</text>");
        xml.append("</inputExpression></input>\n");
        if (outputValues != null) {
            xml.append("      <output id=\"o\" name=\"out\"><outputValues><text>");
            xml.append(outputValues).append("</text></outputValues></output>\n");
        } else {
            xml.append("      <output id=\"o\" name=\"out\"/>\n");
        }
        String[] entries = entry.split("\\|");
        String[] outputs = output.split("\\|");
        for (int i = 0; i < entries.length; i++) {
            xml.append("      <rule><inputEntry><text>").append(entries[i]);
            xml.append("</text></inputEntry><outputEntry><text>");
            xml.append(i < outputs.length ? outputs[i] : outputs[outputs.length - 1]);
            xml.append("</text></outputEntry></rule>\n");
        }
        xml.append("    </decisionTable>\n  </decision>\n</definitions>\n");
        return xml.toString();
    }

    private InMemoryWorkflowPersistence repo;
    private WfDecisionService decisions;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        decisions = new WfDecisionService(repo);
    }

    // ==================== 解析 ====================

    @Test
    @DisplayName("解析：inputs / outputs / rules / hitPolicy 都读对")
    void parsesTheWholeTable() {
        WfDmnDecision decision = new WfDmnParser().parse(APPROVAL_DMN).get(0);
        assertEquals("approvalLevel", decision.getKey(), "key 取自 <decision id>");
        assertEquals("审批层级", decision.getName());

        WfDmnDecision.WfDmnTable table = decision.table();
        assertEquals(2, table.getInputExpressions().size(), "两列输入");
        assertEquals("amount", table.getInputExpressions().get(0));
        assertEquals("category", table.getInputExpressions().get(1));
        assertEquals(1, table.getOutputs().size(), "一个输出列");
        assertEquals("level", table.getOutputs().get(0).getName());
        assertEquals(3, table.getRules().size(), "三条规则");
        assertEquals(2, table.getRules().get(0).getInputEntries().size(),
                "每条规则的输入项数必须与输入列数一致");
        assertEquals(WfDmnHitPolicy.FIRST, table.getHitPolicy());
    }

    @Test
    @DisplayName("hitPolicy 不写时按 UNIQUE —— DMN 的默认值，解析期就补上")
    void defaultHitPolicyIsUnique() {
        WfDmnDecision table = new WfDmnParser()
                .parse(singleRule(null, "> 100", "\"x\"", null)).get(0);
        assertEquals(WfDmnHitPolicy.UNIQUE, table.table().getHitPolicy(),
                "hitPolicy 缺省必须是 UNIQUE 而不是 null —— "
                        + "留 null 会让运行期与校验期各写一次「没写就是 UNIQUE」");
        // 建模工具的符号写法也要认
        assertEquals(WfDmnHitPolicy.COLLECT, new WfDmnParser()
                .parse(singleRule("C", "> 100", "1", null)).get(0).table().getHitPolicy());
    }

    @Test
    @DisplayName("不认识的 hitPolicy 在部署期报错并把认识的都列出来")
    void unknownHitPolicyIsRejected() {
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> decisions.deployDecision(singleRule("MAGIC", "> 100", "\"x\"", null)));
        assertTrue(ex.getMessage().contains("COLLECT"),
                "报错要把认识的策略列出来，否则作者只能去翻规范: " + ex.getMessage());
    }

    @Test
    @DisplayName("决策图（informationRequirement）明确报不支持，不折成顺序求值")
    void decisionGraphIsRejected() {
        String graph = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\">\n"
                + "  <decision id=\"g\" name=\"图\">\n"
                + "    <informationRequirement><requiredInput href=\"#a\"/>"
                + "</informationRequirement>\n"
                + "  </decision>\n"
                + "  <inputData id=\"a\" name=\"a\"/>\n"
                + "</definitions>\n";
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> decisions.deployDecision(graph));
        // 只断「决策图」三个字是**断不住的**：解析器在没有 decisionTable 的兜底分支上
        // 也写了同一句「决策图（informationRequirement）尚未实现」，
        // 于是把那处报错摘掉、让它落到兜底上，这条断言照样成立。
        // 与「删文案类变异要确认删掉的正是判据断的那几个字」同一条纪律：
        // 相邻两句里只要有一句留着同样的关键词，断言就恒成立。
        assertTrue(ex.getMessage().contains("有向关系"),
                "报错要说明决策图要求按有向关系依次求值（决策表那张分支上的话）: "
                        + ex.getMessage());
        assertFalse(ex.getMessage().contains("没有 <decisionTable>"),
                "不能落到「没有 decisionTable」那条兜底上 —— "
                        + "那会让作者以为是漏写表，而不是写了本实现不支持的东西: "
                        + ex.getMessage());
    }

    // ==================== 求值 ====================

    @Test
    @DisplayName("FIRST：大额走高层级，规则顺序就是优先级")
    void firstPolicyWalksRulesInOrder() {
        decisions.deployDecision(APPROVAL_DMN);
        assertEquals("ceo", levelOf(60000, "normal"), "大额应当走第一条规则");
        assertEquals("director", levelOf(10000, "normal"), "中等额走第二条");
        assertEquals("staff", levelOf(100, "normal"), "小额第三条");
    }

    @Test
    @DisplayName("输入项是 `-` 表示「这一列不关心」，恒成立")
    void dashMeansAlwaysTrue() {
        // 这是决策表最常用的一列（"大额规则不管单据类型"就是靠它）。
        // 把它当成语法错误会逼作者去编一个恒真表达式，编出来的还更难懂。
        decisions.deployDecision(APPROVAL_DMN);
        assertEquals("ceo", levelOf(60000, "whatever"),
                "第一条规则的第二列是 `-`，所以单据类型不该影响大额的判定");
    }

    @Test
    @DisplayName("UNIQUE 命中多条 ⇒ 停下：取哪一条没有答案")
    void uniqueViolationStops() {
        // 两条规则都写 "> 100"，UNIQUE 的前提被破坏。
        // 取第一条的症状是「同一类单据两次算出不同结果」且没有任何报错。
        decisions.deployDecision(singleRule("UNIQUE", "> 100|> 50", "\"a\"", null));
        WfDmnViolationException ex = assertThrows(WfDmnViolationException.class,
                () -> decisions.evaluateDecision("solo", x(200)));
        assertTrue(ex.getMessage().contains("UNIQUE"),
                "报错要点名是 UNIQUE 被违反: " + ex.getMessage());
    }

    @Test
    @DisplayName("UNIQUE 一条都不命中是合法结果，不是错误")
    void noMatchIsALegalResult() {
        decisions.deployDecision(singleRule("UNIQUE", "> 1000", "\"a\"", null));
        WfDmnDecisionResult result = decisions.evaluateDecision("solo", x(10));
        assertTrue(result.isNoMatch(), "没有规则成立是 DMN 允许的（UNIQUE 可 0 命中）");
        assertEquals(0, result.getMatchedRuleCount());
        assertTrue(result.singleOutput().isEmpty(), "没有输出，但结果对象本身是正常的");
    }

    @Test
    @DisplayName("ANY：输出一致才放行，不一致就停")
    void anyPolicyChecksOutputAgreement() {
        decisions.deployDecision(singleRule("ANY", "> 100|> 50", "\"a\"", null));
        assertEquals("a", decisions.evaluateDecision("solo", x(200)).singleOutput().get("out"),
                "两条命中但输出相同 ⇒ ANY 成立");

        decisions.deployDecision(singleRule("ANY", "> 100|-", "\"a\"|\"b\"", null));
        WfDmnViolationException ex = assertThrows(WfDmnViolationException.class,
                () -> decisions.evaluateDecision("solo", x(200)));
        assertTrue(ex.getMessage().contains("ANY"),
                "报错要点明 ANY 的前提被破坏: " + ex.getMessage());
    }

    @Test
    @DisplayName("RULE_ORDER / COLLECT：全部命中都进结果，且 RULE_ORDER 保序")
    void multiResultPolicies() {
        decisions.deployDecision(singleRule("RULE_ORDER", "> 100|-|-", "\"a\"|\"b\"|\"c\"", null));
        WfDmnDecisionResult ordered = decisions.evaluateDecision("solo", x(200));
        assertEquals(3, ordered.getRows().size(), "三条都命中就都要在结果里");
        assertEquals("a", ordered.getRows().get(0).getOutputs().get("out"));
        assertEquals("c", ordered.getRows().get(2).getOutputs().get("out"),
                "RULE_ORDER 的结果顺序必须与表内规则顺序一致");

        decisions.deployDecision(singleRule("COLLECT", "> 100|-|-", "\"a\"|\"b\"|\"c\"", null));
        assertEquals(3, decisions.evaluateDecision("solo", x(200)).getRows().size(),
                "COLLECT 同样返回全部命中");
    }

    @Test
    @DisplayName("COLLECT 的聚合器把多条压成一个值")
    void collectAggregators() {
        decisions.deployDecision(withAggregator("SUM", "> 100|-|-", "10|20|30"));
        assertEquals(60.0, ((Number) decisions.evaluateDecision("solo", x(200))
                .singleOutput().get("out")).doubleValue(), 0.0001, "SUM");

        decisions.deployDecision(withAggregator("MAX", "> 100|-|-", "10|20|30"));
        assertEquals(30.0, ((Number) decisions.evaluateDecision("solo", x(200))
                .singleOutput().get("out")).doubleValue(), 0.0001, "MAX");

        decisions.deployDecision(withAggregator("COUNT", "> 100|-|-", "10|20|30"));
        assertEquals(3, ((Number) decisions.evaluateDecision("solo", x(200))
                .singleOutput().get("count")).intValue(), "COUNT 记的是命中条数");
    }

    @Test
    @DisplayName("OUTPUT_PRIORITY：结果按 outputValues 的次序排，而不是按规则顺序")
    void outputPriorityOrdersByOutputValues() {
        // 两条命中，但 outputValues 把 "b" 排在 "a" 前面 ⇒ "b" 优先。
        // 它与 RULE_ORDER 的差别正好在这里：RULE_ORDER 看规则次序，
        // OUTPUT_PRIORITY 看**输出值**在 outputValues 里的位置。
        decisions.deployDecision(singleRule("OUTPUT_PRIORITY", "> 100|-",
                "\"a\"|\"b\"", "\"b\",\"a\""));
        Object out = decisions.evaluateDecision("solo", x(200)).singleOutput().get("out");
        assertEquals(java.util.Arrays.asList("b", "a"), out,
                "命中值 a 与 b 都在 outputValues 里，结果必须按 outputValues 的次序 [b, a]，"
                        + "而不是按哪条规则先命中。若实现成「依次覆盖同一个 key」，"
                        + "赢的会是**最后**出现的那个值，优先级整个反过来却没有任何报错");
    }

    @Test
    @DisplayName("OUTPUT_PRIORITY：命中值落在 outputValues 之外时按优先级在前、其余追加在后")
    void outputPriorityPutsUnknownValuesLast() {
        // 上一条用例里两条命中值都在 outputValues 里，于是"按优先级合并"与
        // "直接取最后一条命中的规则"**给出同一个结果** —— 那种输入断不出差别。
        // 这里让第二条命中一个不在 outputValues 里的值 "z"：
        // 正确的答案是 [b, a, z]，而"取最后一条命中"只会得到 [z]。
        //
        // 为什么 a 会带着 b 一起出现：命中值 a 落在 outputValues 里，
        // 按 DMN 的多值输出语义它**展开成整个列表** [b, a]（上一条用例断的就是这个）。
        // 合并是对**展开后的单值**按 outputValues 次序重排，所以 b 也在里面。
        // 这同时补上「落在 outputValues 之外的值追加在末尾而不是丢掉」这条语义 ——
        // 丢掉的话，"输出值写错了"这件事就完全不可见。
        decisions.deployDecision(singleRule("OUTPUT_PRIORITY", "-|-",
                "\"a\"|\"z\"", "\"b\",\"a\""));
        Object out = decisions.evaluateDecision("solo", x(1)).singleOutput().get("out");
        assertEquals(java.util.Arrays.asList("b", "a", "z"), out,
                "a 在 outputValues 里，展开成整个列表 [b, a] 并按 outputValues 次序排；"
                        + "z 不在列表里，只能追加在末尾。若实现成「依次覆盖同一个 key」，"
                        + "赢的会是**最后**出现的那个值，优先级整个反过来却没有任何报错");
    }

    @Test
    @DisplayName("输出值落在 outputValues 里 ⇒ 展开成整个列表（DMN 标准多值输出）")
    void matchedValueExpandsToTheWholeOutputValuesList() {
        decisions.deployDecision(singleRule("UNIQUE", "> 100", "\"director\"",
                "\"staff\",\"director\",\"ceo\""));
        Object out = decisions.evaluateDecision("solo", x(200)).singleOutput().get("out");
        assertTrue(out instanceof List,
                "命中 \"director\" 时输出必须是列表 [" + out + "] —— "
                        + "DMN 的语义是「该输出的所有值按 outputValues 次序」，"
                        + "不是「命中的那一个」。调用方因此不必为单值/多值写分支");
        assertEquals(java.util.Arrays.asList("staff", "director", "ceo"), out,
                "展开后的顺序必须与 outputValues 一致");
    }

    @Test
    @DisplayName("输出项语法写错 ⇒ 明确报违规，不给一个缺项的结果")
    void unparsableOutputEntryIsAViolation() {
        // 输出项是**值**不是条件：求不出来就必须说出来。
        // 放过去的后果是「这一列输出是 null」而流程照常往下跑 ——
        // 调用方拿到的就是「决策结果」，它不会去检查这一格有没有值。
        // 这里用**语法错**而不是「引用未定义变量」：后者在求值器里是 fail-closed
        //（返回 null 而不是抛），断不到抛异常那条路。
        decisions.deployDecision(singleRule("UNIQUE", "-", "${amount >}", null));
        WfDmnViolationException ex = assertThrows(WfDmnViolationException.class,
                () -> decisions.evaluateDecision("solo", x(1)));
        assertTrue(ex.getMessage().contains("${amount >}"),
                "报错要带上出问题的那个输出项原文: " + ex.getMessage());
    }

    @Test
    @DisplayName("输出项引用不存在的变量 ⇒ 明确报违规（求值器对它 fail-closed，不抛）")
    void outputEntryReferencingUndefinedVariableIsAViolation() {
        // 与上一条是**两种不同的失败**，必须各有一条判据：
        // 语法错会让求值器抛，而未定义变量不会抛、只返回 null，
        // 所以上一条那条 catch 断不到这一种。若这里放过去，
        // 症状与语法错一模一样（输出少一格、无人报错），但成因完全不同 ——
        // 判据只写一条就等于只断住了其中一种。
        decisions.deployDecision(singleRule("UNIQUE", "-", "amount", null));
        WfDmnViolationException ex = assertThrows(WfDmnViolationException.class,
                () -> decisions.evaluateDecision("solo", x(1)));
        assertTrue(ex.getMessage().contains("null"),
                "报错要点明求出来是 null，而不是笼统地说失败: " + ex.getMessage());
    }

    @Test
    @DisplayName("输入项引用未定义变量 ⇒ 该规则不命中（与网关条件同一条约定）")
    void undefinedVariableMeansNoMatch() {
        // 与网关条件求值一致：条件引用了不存在的变量 ⇒ 不成立。
        // 若当成成立，amount 没传的单子会被当成"金额 0"而走成小额分支 —— 没有报错。
        decisions.deployDecision(APPROVAL_DMN);
        WfDmnDecisionResult result = decisions.evaluateDecision("approvalLevel",
                single("category", "normal"));
        // 三条规则里只有第三条只看 category，所以**它照样命中** ——
        // fail-closed 是"这一列取不到值就不成立"，不是"整条规则作废"。
        // 真正要防的是相反的方向：把 amount 未传当成"金额 0"而走成小额分支。
        assertEquals(1, result.getMatchedRuleCount(),
                "amount 没传 ⇒ 前两条规则不命中；只剩第三条（只看 category）");
        assertEquals("staff", result.singleOutput().get("level"));
        assertFalse(result.isNoMatch());
    }

    @Test
    @DisplayName("输入项数与输入列数不符 ⇒ 报错，不按「有的比有的多」处理")
    void entryCountMismatchIsRejected() {
        String xml = singleRule("UNIQUE", "> 100", "\"a\"", null).replace(
                "      <input id=\"i\"><inputExpression id=\"ie\"><text>x</text>"
                        + "</inputExpression></input>\n",
                "      <input id=\"i\"><inputExpression id=\"ie\"><text>x</text>"
                        + "</inputExpression></input>\n"
                        + "      <input id=\"i2\"><inputExpression id=\"ie2\"><text>y</text>"
                        + "</inputExpression></input>\n");
        decisions.deployDecision(xml);
        WfDmnViolationException ex = assertThrows(WfDmnViolationException.class,
                () -> decisions.evaluateDecision("solo", x(200)));
        assertTrue(ex.getMessage().contains("一一对应"),
                "报错要说清是数量不符: " + ex.getMessage());
    }

    @Test
    @DisplayName("决策不存在 ⇒ 报错点名 key，不静默当成 0 命中")
    void unknownDecisionFailsLoudly() {
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> decisions.evaluateDecision("nope", x(1)));
        assertTrue(ex.getMessage().contains("nope"),
                "报错要带上 key —— 调用方传错了 key 与决策本身写错了，处置完全不同: "
                        + ex.getMessage());
    }

    // ==================== 部署与存储 ====================

    @Test
    @DisplayName("同一 key 重复部署 ⇒ 版本 +1 且旧版本保留")
    void redeployKeepsOldVersions() {
        decisions.deployDecision(APPROVAL_DMN);
        decisions.deployDecision(APPROVAL_DMN);

        assertEquals(1, decisions.findDecisionByKey("approvalLevel", 1).getVersion());
        assertEquals(2, decisions.findDecisionByKey("approvalLevel").getVersion(),
                "最新版本应当是 2");
        List<WfDmnDecision> versions = decisions.findDecisionsByKey("approvalLevel");
        assertEquals(2, versions.size(), "旧版本必须保留 —— 决策表是会迭代的，"
                + "在途流程引用的是当时的表");
        assertEquals(2, versions.get(0).getVersion(), "版本列表按 version 倒序");
        assertEquals(1, versions.get(1).getVersion());
    }

    @Test
    @DisplayName("一个文件里的两个 decision 各自成为独立部署单元")
    void twoDecisionsInOneFile() {
        String two = APPROVAL_DMN.replace("</definitions>",
                "  <decision id=\"otherOne\" name=\"另一个\">\n"
                        + "    <decisionTable id=\"t2\" hitPolicy=\"UNIQUE\">\n"
                        + "      <input><inputExpression><text>x</text></inputExpression></input>\n"
                        + "      <output name=\"flag\"/>\n"
                        + "      <rule><inputEntry><text>&gt; 0</text></inputEntry>"
                        + "<outputEntry><text>\"yes\"</text></outputEntry></rule>\n"
                        + "    </decisionTable>\n"
                        + "  </decision>\n"
                        + "</definitions>");
        List<String> keys = decisions.deployDecision(two);
        assertEquals(2, keys.size(), "一个文件里两个 decision 要各自部署");
        assertTrue(keys.contains("approvalLevel") && keys.contains("otherOne"));
        assertNotNull(decisions.findDecisionByKey("otherOne"));
    }

    @Test
    @DisplayName("决策表能活过持久化往返：内存深拷贝与 JDBC codec 两条路径都要断")
    void decisionSurvivesTheRoundTrip() {
        // 与流程定义同一个坑：只测内存的话，codec 里少写一处映射根本不会红，
        // 而症状是「部署没问题、一求值就说没有决策表」。
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dmn_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        JdbcWorkflowPersistence jdbc = new JdbcWorkflowPersistence(ds);
        jdbc.initialize();

        for (WfPersistence target : new WfPersistence[]{repo, jdbc}) {
            String kind = target.getClass().getSimpleName();
            WfDecisionService service = new WfDecisionService(target);
            service.deployDecision(APPROVAL_DMN);

            WfDmnDecision reloaded = target.findLatestDecision("approvalLevel");
            assertNotNull(reloaded, kind + "：决策读不回来");
            assertEquals("FIRST", String.valueOf(reloaded.getTable().getHitPolicy()),
                    kind + "：hitPolicy 在往返后丢了或变了");
            assertEquals(2, reloaded.getTable().getInputExpressions().size(),
                    kind + "：输入列在往返后丢了");
            assertEquals(3, reloaded.getTable().getRules().size(),
                    kind + "：规则在往返后丢了");
            assertEquals("ceo", service.evaluateDecision("approvalLevel",
                    amountAndCategory(60000, "normal")).singleOutput().get("level"),
                    kind + "：往返之后这张表仍然算得对（规则顺序不能被 JSON 打乱）");
        }
    }

    @Test
    @DisplayName("删掉旧版本不影响最新版本；全删光后求值明确报错")
    void deletingAVersionFailsLoudly() {
        decisions.deployDecision(APPROVAL_DMN);
        decisions.deployDecision(APPROVAL_DMN);

        assertTrue(decisions.deleteDecision("approvalLevel", 1), "版本 1 应当被删掉");
        assertNull(decisions.findDecisionByKey("approvalLevel", 1));
        assertEquals(2, decisions.findDecisionByKey("approvalLevel").getVersion(),
                "删掉旧版本之后最新版本不受影响");
        assertEquals("ceo", levelOf(60000, "normal"), "最新版本仍然可用");

        // 全删光：必须明确报"决策不存在"，而不是用别的版本悄悄顶上 ——
        // 后者会让同一个决策在部署前后给出不同结果，且没有任何报错。
        assertTrue(decisions.deleteDecision("approvalLevel", 2));
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> decisions.evaluateDecision("approvalLevel", x(1)));
        assertTrue(ex.getMessage().contains("approvalLevel"),
                "报错要带上 key: " + ex.getMessage());
    }

    @Test
    @DisplayName("按版本删决策：JDBC 的 DELETE 必须带上版本列（内存实现天然对）")
    void deletingOneVersionOnJdbcKeepsTheOther() {
        // 内存实现是整对象覆盖，版本天然只影响自己；
        // JDBC 那条 DELETE 语句漏掉 DECISION_VERSION 条件时，
        // **一次会把该 key 的所有版本一起删光** —— 而内存侧永远是对的，
        // 于是症状是「内存里好好的、一上真库就没了」，排查的人会先怀疑数据库。
        // （与第 17 轮 deleteHistoryBefore 漏删令牌同一类：
        //  一条不变式两处独立实现，只测其中一处就等于没测。）
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dmn_del_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        JdbcWorkflowPersistence jdbc = new JdbcWorkflowPersistence(ds);
        jdbc.initialize();
        WfDecisionService service = new WfDecisionService(jdbc);

        service.deployDecision(APPROVAL_DMN);
        service.deployDecision(APPROVAL_DMN);

        assertTrue(service.deleteDecision("approvalLevel", 1), "版本 1 应当被删掉");
        assertNull(service.findDecisionByKey("approvalLevel", 1), "版本 1 不该还在");
        assertNotNull(service.findDecisionByKey("approvalLevel", 2),
                "版本 2 必须还在 —— DELETE 若漏了版本条件，这行会被一起删掉");
        assertEquals("ceo", service.evaluateDecision("approvalLevel",
                amountAndCategory(60000, "normal")).singleOutput().get("level"),
                "剩下的版本仍然算得对");
    }

    @Test
    @DisplayName("自省清单里有决策这张表 —— 运维看得到它")
    void decisionTableShowsUpInIntrospection() {
        assertTrue(repo.getTableNames().contains("ZWF_DECISION"),
                "内存侧报出的存储项里要有决策表: " + repo.getTableNames());
        decisions.deployDecision(APPROVAL_DMN);
        assertEquals(1L, repo.getTableCount("ZWF_DECISION"));
    }

    // ==================== FEEL 闸门 ====================

    @Test
    @DisplayName("FEEL 的区间 / 内置函数在部署期被挡下，而不是留到运行期静默不命中")
    void feelConstructsAreRejectedAtDeployTime() {
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> decisions.deployDecision(
                        singleRule("UNIQUE", "[1..10].contains(x)", "\"a\"", null)));
        assertTrue(ex.getMessage().contains("区间"),
                "报错要点名是区间: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("FEEL"),
                "报错要说清原因是「本实现的 EL 不是 FEEL」，"
                        + "否则作者会以为自己的 DMN 写错了: " + ex.getMessage());

        assertThrows(WfDefinitionException.class,
                () -> decisions.deployDecision(
                        singleRule("UNIQUE", "date(\"2024-01-01\") < x", "\"a\"", null)),
                "FEEL 的日期函数同样要挡");
    }

    @Test
    @DisplayName("数组下标不会被误当成 FEEL 区间")
    void arrayIndexIsNotMistakenForFeelRange() {
        // `[` 单独出现是本仓 EL 支持的数组下标，不是 FEEL 区间；
        // 闸门若只看 `[` 就会把正常写法一起挡掉。
        String xml = singleRule("UNIQUE", "items[x] > 100", "\"a\"", null)
                .replace("<inputExpression id=\"ie\"><text>x</text>",
                        "<inputExpression id=\"ie\"><text>items</text>");
        assertNotNull(decisions.deployDecision(xml),
                "数组下标是本仓 EL 支持的写法，不该被 FEEL 闸门拦下");
    }

    // ==================== 夹具 ====================

    private String withAggregator(String aggregator, String entry, String output) {
        // entry 与 output 用 | 分隔，**条数必须相等** —— 一条规则一个输出项。
        // 少写一个 | 会让输出项被"沿用最后一条"（见 singleRule），
        // 于是聚合的基数与预期对不上，而症状只是 SUM 算少了一项。
        return singleRule("COLLECT", entry, output, null)
                .replace("<decisionTable id=\"t\" hitPolicy=\"COLLECT\">",
                        "<decisionTable id=\"t\" hitPolicy=\"COLLECT\" aggregator=\""
                                + aggregator + "\">");
    }

    private Object levelOf(Object amount, Object category) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("amount", amount);
        variables.put("category", category);
        return decisions.evaluateDecision("approvalLevel", variables).singleOutput().get("level");
    }

    private Map<String, Object> x(Object value) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("x", value);
        return variables;
    }

    /** APPROVAL_DMN 的两个输入列分别是 amount 与 category，别名表只有 x 时求值会全不命中。 */
    private Map<String, Object> amountAndCategory(Object amount, Object category) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("amount", amount);
        variables.put("category", category);
        return variables;
    }

    private Map<String, Object> single(String name, Object value) {
        Map<String, Object> variables = new HashMap<>();
        variables.put(name, value);
        return variables;
    }
}