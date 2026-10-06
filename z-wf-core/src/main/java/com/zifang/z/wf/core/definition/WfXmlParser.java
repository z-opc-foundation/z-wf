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

    /**
     * {@code timerEventDefinition} 里同时出现了多个子元素时记在这里。
     *
     * <p>解析层不替作者挑一个，交给 {@link WfDefinitionValidator} 在部署期报 ERROR。
     */
    public static final String PROPERTY_TIMER_CONFLICT = "zifang:timerConflict";

    /** messageEventDefinition 与 signalEventDefinition 同时出现。 */
    public static final String PROPERTY_EVENT_CONFLICT = "zifang:eventConflict";


    /** 写了 messageEventDefinition / signalEventDefinition 却没给 name/ref。 */
    public static final String PROPERTY_EVENT_MISSING_REF = "zifang:eventMissingRef";

    /**
     * {@code conditionalEventDefinition} 里的条件表达式。
     *
     * <p>存成 <b>文本</b>而不是编译后的表达式：条件在<b>事件到达那一刻</b>求值，
     * 而那一刻的流程变量与建订阅时不同（审批金额、当前审批人往往在等待期间才定下来）。
     * 存成文本也才留得下"作者到底写了什么"，部署期报语法错时报得出原文。
     */
    public static final String PROPERTY_EVENT_CONDITION = "zifang:eventCondition";

    /** 写了 {@code conditionalEventDefinition} 却没给 {@code <condition>}（或给了空白）。 */
    public static final String PROPERTY_EVENT_CONDITION_EMPTY = "zifang:eventConditionEmpty";

    /** BPMN 2.0 模型命名空间。 */
    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /** 扩展属性前缀（BPMN 文件里声明的 zifang 命名空间）。 */
    public static final String EXT_NS = "http://zifang.com/wf/bpmn/ext";

    /** Camunda 扩展属性命名空间。迁移别人的模型时靠它识别 asyncBefore / caseExpression。 */
    public static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";

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
            {"complexGateway", "complexGateway"},
            {"eventBasedGateway", "eventBasedGateway"},
            // 事件网关的出线只能是中间捕获事件。intermediateThrowEvent 收进来是为了
            // 让校验器报出"抛事件未实现"而不是让它退化成任务 —— 抛事件的语义是
            // "主动打断别人"，退化成"等人来点"是另一个流程
            {"intermediateCatchEvent", "intermediateCatchEvent"},
            {"intermediateThrowEvent", "intermediateThrowEvent"},
            // 链接事件：配对键是 @name，语义是"把 token 从图上一处搬到另一处"。
            // 此前它们不在表里，会走未知元素路径 —— 而未知元素不是报错，是
            // parseNode 里按 TASK 收进来并在部署期报「不支持」。
            {"linkThrowEvent", "linkThrowEvent"},
            {"linkCatchEvent", "linkCatchEvent"},
            {"subProcess", "subProcess"},
            {"transaction", "transaction"},
            {"adHocSubProcess", "adHocSubProcess"},
            {"callActivity", "callActivity"},
            {"boundaryEvent", "boundaryEvent"},
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
        // 被调流程：标准 BPMN 用**子元素** <calledElement>text</calledElement>，
        // z-wf 额外认 zifang:calledElementKey 属性。三者都读，扩展属性优先。
        //
        // 只读扩展属性的后果不是"少个功能"：任何用 Camunda Modeler / Flowable 导出的
        // 流程里 calledElement 都是子元素，导入本引擎后 calledElementKey 为 null，
        // 启动时抛"被调流程不存在" —— 而真正的原因是这一行解析器没读子元素。
        node.setCalledElementKey(firstNonBlank(extension(element, "calledElementKey"),
                firstNonBlank(extension(element, "calledElement"),
                        childText(element, "calledElement"))));
        node.setResultExpression(extension(element, "resultExpression"));
        node.setResultVariable(extension(element, "resultVariable"));

        // ---- 多实例（BPMN 的 multiInstanceLoopCharacteristics 是子元素，不是属性）----
        Element loop = childElement(element, "multiInstanceLoopCharacteristics");
        if (loop != null) {
            node.setMultiInstance(true);
            node.setSequential("true".equalsIgnoreCase(loop.getAttribute("isSequential")));
            node.setLoopCardinality(childText(loop, "loopCardinality"));
            node.setCompletionCondition(childText(loop, "completionCondition"));
            // collection 与 loopCardinality 二选一（校验器会拦下同时配的情况）。
            // elementVariable 只在配了 collection 时才有意义：它给当前元素起名，
            // 办理表达式靠 ${那个名字} 引用。collection 单独配（不配 elementVariable）
            // 是合法的，语义退化成"按集合大小展开"，不绑元素
            node.setLoopCollection(childText(loop, "collection"));
            node.setLoopElement(childText(loop, "elementVariable"));
            // 办理人列表是 zifang 扩展（标准 BPMN 没有对应位置）：
            // 值是流程变量里的一个集合，逐实例派人时用 ${loopAssignee} 引用
            node.setLoopAssignees(extension(element, "loopAssignees"));
        }

        // ---- 错误边界事件：errorRef 取自 errorEventDefinition 子元素 ----
        // 注意不能放进上面的 if (loop != null) 里：边界事件没有多实例子元素，
        // 放进去就意味着它永远解析不到自己挂在谁身上（曾这么错过一次）。
        node.setAttachedToRef(attr(element, "attachedToRef"));
        Element errorDef = childElement(element, "errorEventDefinition");
        if (errorDef != null) {
            node.setErrorCode(errorDef.getAttribute("errorRef"));
        }
        parseTimerDefinition(element, node);
        parseEventDefinition(element, node);

        // ---- 链接事件：配对键取自元素自己的 @name ----
        // BPMN 2.0 的 XSD 里 link 事件的 name 既是显示名也是配对键，没有
        // <linkEventDefinition> 子元素（那是它继承来的抽象基类，实例里不出现）。
        // 所以这里读 @name 而不是探测子元素。
        //
        // 存进 linkName 而不复用上面那个 node.setName(...)：那份是显示用的，
        // 这一份是引擎配对用的，两个用途不能共用一个槽位（理由见 WfNode#linkName）。
        // 只在这两个类型上写，其余节点该字段恒为 null —— "属性必须数据真具备"。
        if (resolved == WfNodeType.LINK_THROW || resolved == WfNodeType.LINK_CATCH) {
            node.setLinkName(attr(element, "name"));
        }

        node.setDueDateDuration(extension(element, "dueDate"));

        String priority = extension(element, "priority");
        if (priority != null) {
            try {
                node.setPriority(Integer.parseInt(priority.trim()));
            } catch (NumberFormatException ignored) {
                // 优先级非法时保留默认值，不让一条脏属性毁掉整份定义
            }
        }

        node.setTopic(extension(element, "topic"));
        // 复杂网关的判别变量。读 zifang:caseVariable 与 camunda:caseExpression 两种写法：
        // Camunda 导出的是后者，照搬过来不被识别的话用户会以为"不支持复杂网关"
        node.setCaseVariable(firstNonBlank(
                extension(element, "caseVariable"),
                camundaAttribute(element, "caseExpression")));
        // 异步两个方向都读 zifang: 与 camunda: 两个前缀：Camunda 导出的模型带的是
        // camunda:asyncBefore，照搬过来却因为前缀不同而不被识别，用户会以为
        // "z-wf 不支持异步" —— 而它其实支持。async 是 Camunda 里最常被直接沿用的扩展之一。
        node.setAsyncBefore(booleanExtension(element, "asyncBefore"));
        node.setAsyncAfter(booleanExtension(element, "asyncAfter"));
        node.setCandidateUsers(splitList(extension(element, "candidateUsers")));
        node.setCandidateGroups(splitList(extension(element, "candidateGroups")));
        node.setRequiredVariables(splitList(extension(element, "requiredVariables")));

        return node;
    }

    /**
     * 解析 {@code conditionalEventDefinition}。
     *
     * <p><b>条件与事件定义是"叠加"关系而不是"二选一"</b>：
     * BPMN 里 {@code <intermediateCatchEvent>} 只挂一种事件定义，
     * 但本仓把它当成「在消息 / 信号 / 定时器之上再加一道门槛」——
     * 条件式事件在真实流程里的用法几乎都是「超时 3 天<b>而且</b>金额超过 1 万才提醒」，
     * 那个「而且」需要事件类型与条件并存，照 BPMN 的互斥规则写不出来。
     *
     * <p>所以挂了两者时**不报错**：报错等于逼作者二选一，而二选一的结果
     * 是"要么没条件、要么没有事件类型" —— 两种都答非所问。
     * 互斥仍然是隐含的（conditional 不带任何事件类型时，部署期由
     * {@code PROPERTY_EVENT_MISSING_REF} 那条路报出来）。
     */
    private void parseConditionalDefinition(Element element, WfNode node) {
        Element conditionalDef = childElement(element, "conditionalEventDefinition");
        if (conditionalDef == null) {
            return;
        }
        String condition = childText(conditionalDef, "condition");
        if (condition == null || condition.trim().isEmpty()) {
            // 留痕而不是留空：条件事件没有条件 = 这一格永远等不到，
            // 而报出来的错必须指向"你忘了写 <condition>"而不是"事件定义不完整"
            node.getProperties().put(PROPERTY_EVENT_CONDITION_EMPTY, "conditionalEventDefinition");
            return;
        }
        node.getProperties().put(PROPERTY_EVENT_CONDITION, condition.trim());
    }

    /**
     * 解析 {@code messageEventDefinition} / {@code signalEventDefinition}。
     *
     * <p>两者互斥，同时出现时按 properties 记下来交校验器报错 —— 静默挑一个
     * 会让作者以为自己写的那条生效了。
     *
     * <p>{@code cancelActivity="false"}（非中断）已支持（第 12 轮）：触发时不搬宿主
     * token，另起一条从边界出发的并行分支，两者在下游汇合点碰头。
     */
    private void parseEventDefinition(Element element, WfNode node) {
        Element messageDef = childElement(element, "messageEventDefinition");
        Element signalDef = childElement(element, "signalEventDefinition");
        parseConditionalDefinition(element, node);
        if (messageDef != null) {
            node.setMessageName(messageDef.getAttribute("messageRef"));
            if (node.getMessageName() == null || node.getMessageName().trim().isEmpty()) {
                // 留痕而不是留空：校验期好报"缺 messageRef"。
                // 只留空值的话节点会被当成"没有触发条件"，报出来的错指向不了
                // 他真正写错的那一处（author 以为自己写了消息边界）
                node.getProperties().put(PROPERTY_EVENT_MISSING_REF,
                        "messageEventDefinition");
            }
        }
        if (signalDef != null) {
            node.setSignalName(signalDef.getAttribute("signalRef"));
            if (node.getSignalName() == null || node.getSignalName().trim().isEmpty()) {
                node.getProperties().put(PROPERTY_EVENT_MISSING_REF,
                        "signalEventDefinition");
            }
        }
        if (messageDef != null && signalDef != null) {
            node.getProperties().put(PROPERTY_EVENT_CONFLICT,
                    "messageEventDefinition 与 signalEventDefinition 同时出现");
        }
        // cancelActivity / parallelMultiple 是**属性**（不是子元素），
        // 默认值分别是 true 与 false，只有显式写了才非默认。
        // 不写就保持默认而不是记成"false"：只有显式写的非默认值才需要被校验器看见
        if ("false".equals(String.valueOf(element.getAttribute("cancelActivity")).trim())) {
            node.setNonInterrupting(true);
        }
        if ("true".equals(String.valueOf(element.getAttribute("parallelMultiple")).trim())) {
            node.setParallelMultiple(true);
        }
    }

    /**
     * 解析定时器边界事件的 {@code timerEventDefinition}。
     *
     * <p>三种子元素在 BPMN 里互斥。这里按固定顺序探测而不是"哪个先出现用哪个"，
     * 是为了让"同时写了两个"这种情况被解析层记下来（存进 properties），
     * 校验器据此报错 —— 静默挑一个会让作者以为自己写的那条生效了。
     */
    private void parseTimerDefinition(Element element, WfNode node) {
        Element timerDef = childElement(element, "timerEventDefinition");
        if (timerDef == null) {
            return;
        }
        WfTimerType found = null;
        String foundText = null;
        for (WfTimerType type : WfTimerType.values()) {
            String text = childText(timerDef, type.getElementName());
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            if (found == null) {
                found = type;
                foundText = text.trim();
            } else {
                // 记下来但不改 node：交给校验器去报，解析层不替作者做选择
                node.getProperties().put(PROPERTY_TIMER_CONFLICT,
                        type.getElementName() + " 与 " + found.getElementName());
            }
        }
        node.setTimerType(found);
        node.setTimerExpression(foundText);
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

        // 复杂网关出线的匹配值
        String caseValue = extension(element, "caseValue");
        if (caseValue != null) {
            flow.setCaseValue(caseValue.trim());
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
    /** 取直接子元素；不存在返回 null。 */
    private Element childElement(Element parent, String tagName) {
        if (parent == null) {
            return null;
        }
        org.w3c.dom.NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node child = children.item(i);
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                    && tagName.equals(child.getNodeName())) {
                return (Element) child;
            }
        }
        return null;
    }

    /** 取子元素文本；不存在或为空返回 null。 */
    private String childText(Element parent, String tagName) {
        Element child = childElement(parent, tagName);
        if (child == null) {
            return null;
        }
        String text = child.getTextContent();
        return text == null || text.trim().isEmpty() ? null : text.trim();
    }

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
     * 读 {@code camunda:} 前缀的属性。
     *
     * <p>与 {@link #extension} 分开而不是合并：{@code extension} 的三条查找路径里
     * 有一条是 {@code zifang_xxx} 下划线写法，camunda 没有对应约定，
     * 混进去会让"zifang:xxx"意外命中 camunda 属性。
     */
    private String camundaAttribute(Element element, String name) {
        if (element == null) {
            return null;
        }
        String namespaced = element.getAttributeNS(CAMUNDA_NS, name);
        if (namespaced != null && !namespaced.trim().isEmpty()) {
            return namespaced.trim();
        }
        String prefixed = element.getAttribute("camunda:" + name);
        return prefixed == null || prefixed.trim().isEmpty() ? null : prefixed.trim();
    }

    /**
     * 读布尔型扩展属性：先试 zifang 前缀，再试 camunda 前缀。
     *
     * <p>只认 {@code "true"}（不分大小写）。其它值一律当没配 —— 写
     * {@code asyncBefore="1"} 或 {@code =""} 的人多半是想开，但如果因此报部署错误，
     * 那会在迁移别人的模型时把一整批本来能跑的流程全挡下来，而挡的理由是
     * 一个拼写。真的想要严格校验应该由 {@code WfDefinitionValidator} 报，而不是解析器抛。
     */
    private boolean booleanExtension(Element element, String name) {
        String value = extension(element, name);
        if (value == null) {
            value = camundaAttribute(element, name);
        }
        return "true".equalsIgnoreCase(value == null ? null : value.trim());
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
