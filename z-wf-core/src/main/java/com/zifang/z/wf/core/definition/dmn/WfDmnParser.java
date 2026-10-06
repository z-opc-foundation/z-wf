package com.zifang.z.wf.core.definition.dmn;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

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
 * <p>只读<b>决策表</b>（{@code <decisionTable>}）。决策图（{@code <decision>} 里引
 * {@code requiredDecision} 组成的有向无环图）本实现不支持，部署期报 ERROR ——
 * 理由见 {@code WfDecisionService}：那需要一套 FEEL 求值，而 FEEL 与本仓的 EL
 * 不是同一门语言，半套图执行只会给出看似能跑、实际算错的结果。
 *
 * @author zifang
 */
public class WfDmnParser {

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
                    + "本实现只支持决策表（<decision><decisionTable>），"
                    + "决策图（由 requiredDecision 组成的有向图）尚未实现");
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

        // <decision> 可能带 <variable> 声明与 <informationRequirement>，
        // 两者都不是本实现要的（前者是类型声明，后者构成决策图）。
        // 有 informationRequirement 时明确报错，见类注释。
        List<Element> tableElements = byLocalName(element, "decisionTable");
        if (tableElements.isEmpty()) {
            if (!byLocalName(element, "informationRequirement").isEmpty()) {
                throw new WfDefinitionException("决策 " + key
                        + " 用的是**决策图**（带 informationRequirement），本实现不支持。"
                        + "决策图要求按有向关系依次求值多个决策，"
                        + "每一跳的输入都可能是上一跳的输出 —— "
                        + "那是一条与决策表完全不同的执行路径，"
                        + "把它折成「按顺序跑一遍」会让拓扑顺序与依赖顺序不一致的图算出错误结果，"
                        + "而这类错误不会报错。请改成单张决策表。");
            }
            throw new WfDefinitionException("决策 " + key + " 没有 <decisionTable>。"
                    + "本实现只支持决策表；决策图（informationRequirement）尚未实现");
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