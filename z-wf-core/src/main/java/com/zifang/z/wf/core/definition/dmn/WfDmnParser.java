package com.zifang.z.wf.core.definition.dmn;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.zifang.z.wf.core.definition.WfDefinitionException;

/**
 * DMN XML 解析器 —— 把 {@code <decision>} 里的决策表读成 {@link WfDmnDecision}。
 *
 * <p><b>按 localName 找元素，不按命名空间。</b>DMN 工具链的命名空间版本极其分裂：
 * 1.1 是 {@code .../20151101/MODEL/}、1.3 是 {@code .../20191111/MODEL/}，
 * 各家建模器导出的还不一样，有的干脆不带命名空间。
 * 按 URI 匹配意味着"换个工具导出的文件就读不出来"，而那种失败极难定位 ——
 * 报错只会说找不到元素，不会说你的命名空间版本不对。
 * 元素的<b>局部名</b>才是稳定的那个，所以自递归比 {@code getElementsByTagNameNS} 稳。
 *
 * <p>同时读<b>决策表</b>（{@code <decisionTable>}）与<b>决策图的依赖边</b>
 * （{@code <informationRequirement>} 里指向另一个 {@code <decision>} 的
 * {@code <requiredDecision>}）。依赖边只被解析成 key 列表，
 * 真正按依赖关系递归求值在 {@code WfDecisionService}。
 *
 * <p><b>但一个 decision 仍必须恰好有一张表。</b>DMN 允许决策节点承载
 * 文字表达式（literal expression）而没有表，本实现不支持 ——
 * 那样一个节点求值期拿不到任何东西，只能悄悄返回空结果，
 * 而"这一跳什么都没算出来"正是决策图里最难查的那种错。
 *
 * <p><b>被解析但求值时不参与的</b>：{@code <requiredInput>}（外部输入数据）与
 * {@code <knowledgeSource>}（知识源）。它们在本实现里不参与求值 ——
 * 与 Camunda 一致（引擎不执行输入数据节点与知识源）——
 * 但<b>照 WARN 说出来</b>，否则作者会以为那条依赖真的参与了计算。
 *
 * @author zifang
 */
public class WfDmnParser {

    private static final Logger log = LoggerFactory.getLogger(WfDmnParser.class);

    /**
     * 解析 DMN XML，返回其中<b>全部</b>决策。
     *
     * <p>一个文件里可以有多个 {@code <decision>}：它们各自是独立的部署单元
     * （key 取各自的 {@code id}），不是打包在一起的版本。
     *
     * @throws WfDefinitionException XML 非法，或一个 decision 都没找到
     */
    public List<WfDmnDecision> parse(String xml) {
        if (xml == null || xml.trim().isEmpty()) {
            throw new WfDefinitionException("DMN XML 内容为空");
        }
        return parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), xml);
    }

    public List<WfDmnDecision> parse(byte[] xmlBytes) {
        if (xmlBytes == null || xmlBytes.length == 0) {
            throw new WfDefinitionException("DMN XML 内容为空");
        }
        return parse(new ByteArrayInputStream(xmlBytes), null);
    }

    /**
     * @param sourceXml 留存的原文（可为 null；为 null 时决策上的原文字段为空）
     */
    private List<WfDmnDecision> parse(InputStream in, String sourceXml) {
        Element root = readRoot(in);
        List<WfDmnDecision> decisions = new ArrayList<>();
        for (Element decisionElement : byLocalName(root, "decision")) {
            WfDmnDecision decision = parseDecision(decisionElement, sourceXml);
            if (decision != null) {
                decisions.add(decision);
            }
        }
        if (decisions.isEmpty()) {
            throw new WfDefinitionException("DMN XML 中没有 <decision> 元素。"
                    + "一个决策节点必须写成 <decision id=\"...\">…</decision>，"
                    + "且必须带一张 <decisionTable>。"
                    + "（<inputData> / <knowledgeSource> 只是被引用的数据与知识节点，"
                    + "不是决策节点；一份只画了需求图、没有内嵌决策的文件里就只有它们。）");
        }
        return decisions;
    }

    private WfDmnDecision parseDecision(Element element, String sourceXml) {
        String key = firstNonBlank(attr(element, "id"), attr(element, "name"));
        if (key == null) {
            // 没有 id 的 decision 没法成为部署单元（调用方按 key 求值）。
            // 跳过而不是造一个匿名 key：那个 key 在任何地方都查不回来。
            return null;
        }
        WfDmnDecision decision = new WfDmnDecision(key,
                firstNonBlank(attr(element, "name"), key));
        decision.setDmnXml(sourceXml);
        parseInformationRequirements(element, decision);

        List<Element> tableElements = byLocalName(element, "decisionTable");
        if (tableElements.isEmpty()) {
            // 决策**节点**必须落到表上才求得出值。带 informationRequirement
            // 又不带表的节点在 DMN 里是"文字表达式"（literal expression），
            // 本实现不支持那种求值方式 —— 而这里放行的症状是
            // 「这一跳什么都没算出来，下游拿到空结果」，不会报错。
            throw new WfDefinitionException("决策 " + key + " 没有 <decisionTable>。"
                    + "每个决策节点都必须带一张决策表（本实现不支持 DMN 的"
                    + "文字表达式 literal expression）"
                    + (decision.getRequiredDecisions().isEmpty() ? ""
                            : "。注意它还依赖了 " + decision.getRequiredDecisions()
                            + " —— 依赖一个算不出东西的决策，下游拿到的也是空结果"));
        }
        if (tableElements.size() > 1) {
            throw new WfDefinitionException("决策 " + key + " 有 " + tableElements.size()
                    + " 张 decisionTable。本实现要求一个 decision 恰好一张表 —— "
                    + "「求值时用哪一张」没有答案，引擎只能去猜，"
                    + "而猜错的结果是每次调用可能落到不同的表上");
        }
        decision.setTable(parseTable(tableElements.get(0), key));
        return decision;
    }

    /**
     * 解析 {@code <informationRequirement>}，把指向别的决策的那些边收进来。
     *
     * <p><b>只认 {@code <requiredDecision href="#id"/>} 一种边</b>。
     * {@code href} 去掉开头的 {@code #} 就是 key —— key 取自 {@code <decision id>}，
     * 所以 {@code #} 不能省：DMN 里 href 还可能是 URN，
     * 而本实现按 id 索引，遇到非 {@code #} 形式要**当场说清**，
     * 不能等到求值期报"依赖的决策不存在"（那句话会被理解成"没部署"，
     * 而实际是"这个引用形式我读不出来"）。
     *
     * <p>重复的依赖边<b>保留不去重</b>：多写一次不影响结果（求值期按 key 收敛），
     * 而默默去重会让"模型里有两条一样的边"这件事在回读时看不见。
     */
    private void parseInformationRequirements(Element element, WfDmnDecision decision) {
        Set<String> ignored = new LinkedHashSet<>();
        for (Element requirement : byLocalName(element, "informationRequirement")) {
            for (Element requiredDecision : byLocalName(requirement, "requiredDecision")) {
                decision.getRequiredDecisions().add(decisionKeyOf(decision, requiredDecision));
            }
            // requiredInput（外部输入数据）与 authorityRequirement（权威来源）
            // 在本实现里没有执行语义 —— 与 Camunda 一致。
            // 不报错（那些节点是合法的 DMN），但必须说出来，
            // 否则作者以为那一跳参与了计算。
            for (Element requiredInput : byLocalName(requirement, "requiredInput")) {
                ignored.add("requiredInput -> " + hrefOf(requiredInput));
            }
            for (Element authority : byLocalName(requirement, "authorityRequirement")) {
                ignored.add("authorityRequirement -> " + hrefOf(authority));
            }
        }
        // knowledgeSource 可以在 <decision> 下，也可以在 <informationRequirement> 下，
        // 所以这里从整个 decision 元素起递归找（byLocalName 找的是后代）——
        // 若在上面的循环里也找一遍，同一个节点会被记两次。
        for (Element knowledge : byLocalName(element, "knowledgeSource")) {
            ignored.add("knowledgeSource -> " + hrefOf(knowledge));
        }
        if (!ignored.isEmpty()) {
            log.warn("决策 [{}] 的这些信息需求在本实现里不参与求值（与 Camunda 一致，"
                    + "引擎不执行输入数据节点与知识源）: {}。"
                    + "如果指望它们参与计算，请改成 <requiredDecision href=\"#另一个决策的id\"/>",
                    decision.getKey(), ignored);
        }
    }

    private String decisionKeyOf(WfDmnDecision decision, Element requiredDecision) {
        String href = attr(requiredDecision, "href");
        if (href == null) {
            throw new WfDefinitionException("决策 " + decision.getKey()
                    + " 的 <informationRequirement> 里有一条 <requiredDecision> 没有 href。"
                    + "没有 href 就指不出依赖谁，求值期只能靠猜"
                    + "（而猜错的表现是「这一跳没算，下游拿到空结果」）");
        }
        if (!href.startsWith("#")) {
            throw new WfDefinitionException("决策 " + decision.getKey()
                    + " 的 <requiredDecision href=\"" + href + "\"> 不是 #id 形式。"
                    + "本实现按 <decision id> 索引决策，只能读 \"#决策id\"；"
                    + "DMN 还允许 URN 形式的引用，那种引用本实现读不出来 —— "
                    + "要报错在这里报，而不是等到求值期变成「依赖的决策不存在」，"
                    + "那句话会被读成「你没部署它」");
        }
        String key = href.substring(1).trim();
        if (key.isEmpty()) {
            throw new WfDefinitionException("决策 " + decision.getKey()
                    + " 的 <requiredDecision href=\"#\"> 后面没有决策 id");
        }
        return key;
    }

    private String hrefOf(Element element) {
        String href = attr(element, "href");
        return href == null ? "(无 href)" : href;
    }

    private WfDmnDecision.WfDmnTable parseTable(Element table, String decisionKey) {
        WfDmnDecision.WfDmnTable result = new WfDmnDecision.WfDmnTable();
        result.setId(firstNonBlank(attr(table, "id"), decisionKey + "Table"));

        // hitPolicy 不写时按 UNIQUE —— DMN 的默认值。
        // 刻意**不留 null**：解析期补上默认，运行期与部署期看到的都是确定值，
        // 两边不必各写一次"没写就是 UNIQUE"。
        String rawPolicy = attr(table, "hitPolicy");
        WfDmnHitPolicy policy = WfDmnHitPolicy.parse(rawPolicy);
        if (policy == null) {
            if (rawPolicy == null) {
                policy = WfDmnHitPolicy.UNIQUE;
            } else {
                throw new WfDefinitionException("决策 " + decisionKey + " 的 hitPolicy ["
                        + rawPolicy + "] 不认识。认识的只有：" + knownPolicies()
                        + "（建模工具里的符号 U / A / F / P / R / C 也接受）");
            }
        }
        result.setHitPolicy(policy);
        result.setAggregator(trimToNull(attr(table, "aggregator")));

        for (Element input : byLocalName(table, "input")) {
            result.getInputExpressions().add(parseInputExpression(input, decisionKey));
        }
        for (Element output : byLocalName(table, "output")) {
            result.getOutputs().add(parseOutput(output));
        }
        for (Element rule : byLocalName(table, "rule")) {
            result.getRules().add(parseRule(rule));
        }
        return result;
    }

    /**
     * 一个输入列的取值表达式。
     *
     * <p>{@code <input>} 下有两种写法：{@code <inputExpression><text>expr</text></inputExpression>}
     * 与 {@code <inputExpression>expr</inputExpression>}。两种都读 ——
     * 前者是新版本规范，后者是建模工具的实际输出，只认一种等于让另一半文件读不出来。
     */
    private String parseInputExpression(Element input, String decisionKey) {
        List<Element> expressions = byLocalName(input, "inputExpression");
        if (expressions.isEmpty()) {
            throw new WfDefinitionException("决策 " + decisionKey + " 有一个 <input> 没有 inputExpression。"
                    + "它回答不了「这一列读哪个变量」，规则无法求值");
        }
        Element expression = expressions.get(0);
        String text = firstNonBlank(textOfChild(expression, "text"), expression.getTextContent());
        if (text == null) {
            throw new WfDefinitionException("决策 " + decisionKey + " 的 inputExpression ["
                    + attr(expression, "id") + "] 内容为空");
        }
        return text;
    }

    private WfDmnDecision.WfDmnOutput parseOutput(Element output) {
        WfDmnDecision.WfDmnOutput result = new WfDmnDecision.WfDmnOutput();
        // 没有 name 就没有输出变量名 —— 调用方拿不到结果。
        // 没有 name 的 output 直接不收（result 保持 name=null，求值时报错），
        // 而不是拿 label 顶替：label 是给人看的，可能重复、可能为空。
        result.setName(trimToNull(attr(output, "name")));
        result.setTypeRef(trimToNull(attr(output, "typeRef")));
        List<Element> valueLists = byLocalName(output, "outputValues");
        if (!valueLists.isEmpty()) {
            String text = firstNonBlank(textOfChild(valueLists.get(0), "text"),
                    valueLists.get(0).getTextContent());
            result.setOutputValues(splitOutputValues(text));
        }
        return result;
    }

    /**
     * 拆 {@code outputValues} 的取值列表。
     *
     * <p>它的写法是<b>字符串字面量的逗号列表</b>：{@code "gold","silver","bronze"}。
     * 拆的时候要处理转义与空项 —— 空项是合法的（字面量 {@code ""}），
     * 一律跳过会把"输出就是空串"这条规则变成"输出缺失"。
     */
    private List<String> splitOutputValues(String text) {
        List<String> values = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return values;
        }
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean sawString = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString && ch == '\\' && i + 1 < text.length()) {
                current.append(text.charAt(++i));
                continue;
            }
            if (ch == '"' || ch == '\'') {
                inString = !inString;
                sawString = true;
                continue;
            }
            if (ch == ',' && !inString) {
                values.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty() || (sawString && !values.isEmpty())) {
            values.add(tail);
        }
        return values;
    }

    private WfDmnDecision.WfDmnRule parseRule(Element rule) {
        WfDmnDecision.WfDmnRule result = new WfDmnDecision.WfDmnRule();
        for (Element entry : byLocalName(rule, "inputEntry")) {
            String text = firstNonBlank(textOfChild(entry, "text"), entry.getTextContent());
            // 空白项等同于 "-"，两者在求值时都按"恒真"处理
            result.getInputEntries().add(text == null ? "-" : text.trim());
        }
        for (Element entry : byLocalName(rule, "outputEntry")) {
            String text = firstNonBlank(textOfChild(entry, "text"), entry.getTextContent());
            result.getOutputEntries().add(text == null ? "" : text.trim());
        }
        return result;
    }

    // ==================== XML 基础设施 ====================

    private Element readRoot(InputStream in) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            // XXE 防护，与 WfXmlParser 同一套配置
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(in);
            Element root = document.getDocumentElement();
            if (root == null) {
                throw new WfDefinitionException("DMN XML 没有根元素");
            }
            return root;
        } catch (WfDefinitionException e) {
            throw e;
        } catch (Exception e) {
            throw new WfDefinitionException("DMN XML 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按<b>局部名</b>收集后代元素（忽略命名空间）。
     *
     * <p>见类注释：DMN 的命名空间版本在工具链里是分裂的，局部名才是稳定的那个。
     */
    private List<Element> byLocalName(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        collect(parent, localName, result);
        return result;
    }

    private void collect(Node node, String localName, List<Element> result) {
        if (node == null) {
            return;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            if (localName.equals(localNameOf((Element) child))) {
                result.add((Element) child);
            }
            collect(child, localName, result);
        }
    }

    private String localNameOf(Element element) {
        String local = element.getLocalName();
        if (local != null) {
            return local;
        }
        // 没开命名空间感知时 getLocalName() 返回 null，退回按 ':' 截断
        String name = element.getNodeName();
        int colon = name == null ? -1 : name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private Element firstChild(Element parent, String localName) {
        List<Element> found = byLocalName(parent, localName);
        return found.isEmpty() ? null : found.get(0);
    }

    private String textOfChild(Element parent, String localName) {
        Element child = firstChild(parent, localName);
        return child == null ? null : trimToNull(child.getTextContent());
    }

    private String attr(Element element, String name) {
        if (element == null) {
            return null;
        }
        String value = element.getAttribute(name);
        return trimToNull(value);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String knownPolicies() {
        StringBuilder sb = new StringBuilder();
        for (WfDmnHitPolicy policy : WfDmnHitPolicy.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(policy.name());
        }
        return sb.toString();
    }
}