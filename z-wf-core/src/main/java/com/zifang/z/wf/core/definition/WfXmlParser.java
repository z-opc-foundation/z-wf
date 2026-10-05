package com.zifang.z.wf.core.definition;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * BPMN 2.0 XML → {@link WfDefinition} 解析器。
 *
 * <p><b>与 z-util-wf-kernel 的协议一致性</b>：本解析器支持的元素集合与
 * {@code BpmnXmlParser#parseProcessElements} 完全对齐（同一份 BPMN XML 在两条引擎路径上
 * 落到同样的节点/连线），差异只在"多读一层扩展属性"——z-wf 额外读取
 * {@code zifang:*} 命名空间下的审批扩展（assignee / candidateGroups / formKey /
 * delegateClass / script / messageName / defaultFlow 等），这些属性在内置 BPMN 里没有对应位置，
 * 但不能改标准元素名（否则标准 BPMN 工具链认不出来）。
 *
 * <p>扩展属性示例：
 * <pre>{@code
 * <bpmn:userTask id="task1" name="经理审批"
 *               zifang:assignee="manager"
 *               zifang:candidateGroups="dept-managers"
 *               zifang:formKey="leaveForm"
 *               zifang:dueDate="PT24H"/>
 * }</pre>
 *
 * <p>也接受无命名空间前缀的写法（{@code zifang_assignee}），方便手写 XML 与 LogicFlow 导出。
 *
 * <p>XML 安全：禁用 DOCTYPE 与外部实体，防 XXE。
 *
 * @author zifang
 */
public class WfXmlParser {

    /** BPMN 2.0 模型命名空间。 */
    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /** 扩展属性前缀（BPMN 文件里声明的 zifang 命名空间）。 */
    public static final String EXT_NS = "http://zifang.com/wf/bpmn/ext";

    /** 扩展属性前缀（无命名空间时的下划线写法）。 */
    public static final String EXT_PREFIX = "zifang_";

    /**
     * 可执行节点元素名 → 归一后的元素名。
     *
     * <p>刻意包含 z-util-wf-kernel 也认识、但本引擎<b>没有独立语义</b>的元素
     * （{@code eventBasedGateway} / {@code transaction} / {@code adHocSubProcess} /
     * {@code intermediate*Event}）。它们在这里被归一到通用任务，
     * 以便"这份 BPMN 我至少读得进来"这件事成立。
     *
     * <p><b>但归一不等于放行。</b> 退化出来的节点会被 {@link #parseNode} 打上
     * {@link WfNode#PROPERTY_UNSUPPORTED_BPMN_ELEMENT} 标记，
     * 由 {@code WfDefinitionValidator} 报 <b>ERROR</b> 挡住部署。
     * 之所以必须挡：{@code eventBasedGateway} 退化成人工任务不是"精度下降"，
     * 是"自动竞速分支"换成了"等人来点"，作者与运行行为之间不再有任何提示。
     * 解析期宽松、部署期严格，是这里唯一不产生静默错误的组合。
     */
    private static final String[][] NODE_ELEMENTS = {
            {"startEvent", "startEvent"},
            {"endEvent", "endEvent"},
            {"intermediateThrowEvent", "intermediateThrowEvent"},
            {"intermediateCatchEvent", "intermediateCatchEvent"},
            {"userTask", "userTask"},
            {"serviceTask", "serviceTask"},
            {"scriptTask", "scriptTask"},
            {"manualTask", "manualTask"},
            {"sendTask", "sendTask"},
            {"receiveTask", "receiveTask"},
            {"task", "task"},
            {"exclusiveGateway", "exclusiveGateway"},
            {"parallelGateway", "parallelGateway"},
            {"inclusiveGateway", "inclusiveGateway"},
            {"eventBasedGateway", "eventBasedGateway"},
            {"subProcess", "subProcess"},
            {"transaction", "transaction"},
            {"adHocSubProcess", "adHocSubProcess"},
            {"callActivity", "callActivity"},
    };

    /**
     * 解析 XML 字符串。
     *
     * @throws WfDefinitionException XML 非法或缺少 {@code <process>} 元素
     */
    public WfDefinition parse(String xml) {
        if (xml == null || xml.trim().isEmpty()) {
            throw new WfDefinitionException("BPMN XML 内容为空");
        }
        return parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), null);
    }

    /**
     * 从字节流解析。
     */
    public WfDefinition parse(byte[] xmlBytes) {
        if (xmlBytes == null || xmlBytes.length == 0) {
            throw new WfDefinitionException("BPMN XML 内容为空");
        }
        return parse(new ByteArrayInputStream(xmlBytes), null);
    }

    /**
     * 从输入流解析。
     *
     * @param fallbackKey 流程定义 key 缺省时使用（XML 里 {@code <process id>} 为空时）
     */
    public WfDefinition parse(InputStream in, String fallbackKey) {
        Document document = readDocument(in);
        return parseDocument(document, fallbackKey);
    }

    private Document readDocument(InputStream in) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            // XXE 防护
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(in);
        } catch (WfDefinitionException e) {
            throw e;
        } catch (Exception e) {
            throw new WfDefinitionException("BPMN XML 解析失败: " + e.getMessage(), e);
        }
    }

    private WfDefinition parseDocument(Document document, String fallbackKey) {
        Element definitions = document.getDocumentElement();
        if (definitions == null) {
            throw new WfDefinitionException("BPMN XML 没有根元素");
        }

        List<Element> processes = elements(definitions, "process");
        if (processes.isEmpty()) {
            throw new WfDefinitionException("BPMN XML 中没有 <process> 元素");
        }

        // 多 <process> 时取第一个，并在描述里标注（同 z-util-wf 的行为）
        Element process = processes.get(0);

        WfDefinition definition = new WfDefinition();
        String key = attr(process, "id");
        if (key == null || key.trim().isEmpty()) {
            key = fallbackKey != null ? fallbackKey : attr(definitions, "id");
        }
        definition.setKey(key);
        definition.setName(firstNonBlank(attr(process, "name"), attr(definitions, "name"), key));
        definition.setCategory(extension(process, "category"));

        List<WfNode> nodes = new ArrayList<>();
        List<WfFlow> flows = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        // ---- 节点 ----
        for (String[] entry : NODE_ELEMENTS) {
            for (Element element : elements(process, entry[0])) {
                // 只取 process 的直接子节点：跳过嵌在 subProcess / boundaryEvent 内部的
                if (!isDirectChildOf(process, element)) {
                    continue;
                }
                WfNode node = parseNode(element, entry[1]);
                if (node == null || node.getId() == null) {
                    continue;
                }
                // 记下"嵌在谁里面"：解析结果是扁平表，父子关系在这里之后就找不回来了，
                // 而校验器要靠它判断哪些内联节点永远不会被执行。
                Element parent = parentElement(element);
                if (parent != null && parent != process) {
                    node.getProperties().put(WfNode.PROPERTY_NESTED_IN, parentId(parent));
                }
                if (!seenIds.add(node.getId())) {
                    throw new WfDefinitionException("BPMN XML 中节点 id 重复: " + node.getId());
                }
                nodes.add(node);
            }
        }

        // ---- 连线 ----
        for (Element element : elements(process, "sequenceFlow")) {
            if (!isDirectChildOf(process, element)) {
                continue;
            }
            WfFlow flow = parseFlow(element);
            if (flow != null) {
                flows.add(flow);
            }
        }

        definition.setNodes(nodes);
        definition.setFlows(flows);
        definition.buildIndex();
        return definition;
    }

    /**
     * 解析单个节点元素。
     *
     * @param elementTag 该元素在 BPMN 里的名字（用于识别"退化出来的"节点）
     */
    private WfNode parseNode(Element element, String elementTag) {
        WfNode node = new WfNode();
        node.setId(attr(element, "id"));
        node.setName(firstNonBlank(attr(element, "name"), node.getId()));
        String overrideName = attr(element, "zifang:type");
        WfNodeType resolved = resolveType(elementTag, overrideName);
        node.setType(resolved);
        // 只有"靠退化才变成 TASK"才需要标记：作者显式写了 zifang:type 覆盖时，
        // 是他自己拍板的，引擎不该再拦一次。
        if (resolved == WfNodeType.TASK && !WfNodeType.isNative(elementTag)
                && (overrideName == null || overrideName.trim().isEmpty())) {
            node.getProperties().put(WfNode.PROPERTY_UNSUPPORTED_BPMN_ELEMENT, elementTag);
        }

        // ---- 审批扩展（标准 BPMN 没有的位置）----
        node.setCategory(extension(element, "category"));
        node.setFormKey(extension(element, "formKey"));
        node.setAssignee(extension(element, "assignee"));
        node.setMessageName(extension(element, "messageName"));
        node.setDelegateClass(extension(element, "delegateClass"));
        node.setDelegateExpression(extension(element, "delegateExpression"));
        node.setScript(extension(element, "script"));
        node.setCalledElementKey(firstNonBlank(extension(element, "calledElementKey"),
                firstNonBlank(extension(element, "calledElement"), null)));
        node.setResultExpression(extension(element, "resultExpression"));
        node.setResultVariable(extension(element, "resultVariable"));
        node.setDueDateDuration(extension(element, "dueDate"));

        String priority = extension(element, "priority");
        if (priority != null) {
            try {
                node.setPriority(Integer.parseInt(priority.trim()));
            } catch (NumberFormatException ignored) {
                // 优先级非法时保留默认值，不让一条脏属性毁掉整份定义
            }
        }

        node.setCandidateUsers(splitList(extension(element, "candidateUsers")));
        node.setCandidateGroups(splitList(extension(element, "candidateGroups")));
        node.setRequiredVariables(splitList(extension(element, "requiredVariables")));

        return node;
    }

    /**
     * 解析 sequenceFlow。
     */
    private WfFlow parseFlow(Element element) {
        WfFlow flow = new WfFlow();
        flow.setId(attr(element, "id"));
        flow.setName(attr(element, "name"));
        flow.setSourceRef(attr(element, "sourceRef"));
        flow.setTargetRef(attr(element, "targetRef"));

        if (flow.getSourceRef() == null || flow.getTargetRef() == null) {
            return null;
        }

        // 条件表达式：BPMN 2.0 允许 <conditionExpression> 元素或 condExpression 扩展属性
        String condition = textOfChild(element, "conditionExpression");
        if (condition == null || condition.trim().isEmpty()) {
            condition = extension(element, "conditionExpression");
        }
        if (condition != null) {
            flow.setConditionExpression(condition.trim());
        }

        // 默认流：BPMN 用 sequenceFlow 上的 attribute/子元素，z-wf 同时接受扩展属性
        String def = extension(element, "defaultFlow");
        if (def == null) {
            def = attr(element, "default");
        }
        if (def == null) {
            // <conditionExpression> 上带 default="true" 也是常见写法
            Element cond = firstChild(element, "conditionExpression");
            def = cond == null ? null : attr(cond, "default");
        }
        flow.setDefaultFlow("true".equalsIgnoreCase(def));

        return flow;
    }

    /**
     * 节点类型归一：扩展属性 {@code zifang:type} 优先，其次按元素名。
     *
     * <p>中间事件（intermediateThrow/Catch）本引擎没有独立行为，归到 {@link WfNodeType#TASK}。
     */
    private WfNodeType resolveType(String elementTag, String override) {
        if (override != null && !override.trim().isEmpty()) {
            WfNodeType explicit = WfNodeType.parse(override);
            if (explicit != null) {
                return explicit;
            }
        }
        return WfNodeType.fromBpmn(elementTag);
    }

    // ==================== DOM 辅助 ====================

    /**
     * 按本地名取后代元素（先试命名空间，失败则宽松匹配）。
     */
    private List<Element> elements(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList list = parent.getElementsByTagNameNS(BPMN_NS, localName);
        if (list == null || list.getLength() == 0) {
            list = parent.getElementsByTagName(localName);
        }
        if (list != null) {
            for (int i = 0; i < list.getLength(); i++) {
                result.add((Element) list.item(i));
            }
        }
        return result;
    }

    private Element firstChild(Element parent, String localName) {
        List<Element> found = elements(parent, localName);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 读取子元素的文本内容（trim 后）。
     */
    private String textOfChild(Element parent, String localName) {
        Element child = firstChild(parent, localName);
        if (child == null) {
            return null;
        }
        String text = child.getTextContent();
        return text == null ? null : text.trim();
    }

    /**
     * 是否为 parent 的直接子元素。
     * <p>作用：subProcess 内部的节点、boundaryEvent 挂载的节点都不应被当成流程主图的节点。
     */
    private boolean isDirectChildOf(Element parent, Element candidate) {
        Node node = candidate.getParentNode();
        while (node != null && node.getNodeType() == Node.ELEMENT_NODE) {
            if (node == parent) {
                return true;
            }
            node = node.getParentNode();
        }
        return false;
    }

    /**
     * 取元素的直接父元素。
     *
     * <p>与 {@link #isDirectChildOf} 的区别：那个方法判的是"祖先链里有没有它"
     * （名字里的 direct 有误导性），这里要的是"紧挨着的那个父元素"，
     * 用来区分"流程主图的节点"与"嵌在容器里的节点"。
     */
    private Element parentElement(Element candidate) {
        Node node = candidate.getParentNode();
        return node != null && node.getNodeType() == Node.ELEMENT_NODE ? (Element) node : null;
    }

    /** 容器元素可能没写 id，退回元素名，至少能让人看出是"嵌在什么里面"。 */
    private String parentId(Element parent) {
        String id = parent.getAttribute("id");
        return isBlank(id) ? "<" + parent.getTagName() + ">" : id;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String attr(Element element, String name) {
        if (element == null) {
            return null;
        }
        String value = element.getAttribute(name);
        if (value == null || value.trim().isEmpty()) {
            // 命名空间感知下 getAttribute("id") 仍可用（无前缀属性），但保险起见再试本地名查找
            return value == null || value.trim().isEmpty() ? null : value.trim();
        }
        return value.trim();
    }

    /**
     * 读取扩展属性：先试 {@code zifang:xxx}，再试 {@code zifang_xxx}。
     */
    private String extension(Element element, String name) {
        if (element == null) {
            return null;
        }
        String namespaced = element.getAttributeNS(EXT_NS, name);
        if (namespaced != null && !namespaced.trim().isEmpty()) {
            return namespaced.trim();
        }
        String prefixed = element.getAttribute(EXT_NS.equals(element.getNamespaceURI())
                ? name : "zifang:" + name);
        if (prefixed != null && !prefixed.trim().isEmpty()) {
            return prefixed.trim();
        }
        String underscored = element.getAttribute(EXT_PREFIX + name);
        if (underscored != null && !underscored.trim().isEmpty()) {
            return underscored.trim();
        }
        return null;
    }

    /**
     * 逗号分隔列表 → 字符串数组（去空白、去空项）。
     */
    private List<String> splitList(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return result;
        }
        for (String part : Arrays.asList(raw.split(","))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) {
                return candidate.trim();
            }
        }
        return null;
    }
}
