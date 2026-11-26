package com.zifang.z.wf.core.definition;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.zifang.z.wf.core.view.WfDiagramInfo;
import com.zifang.z.wf.core.view.WfEdge;
import com.zifang.z.wf.core.view.WfShape;

/**
 * 解析 BPMN DI（{@code BPMNDiagram}）里的图形信息。
 *
 * <p><b>按本地名匹配，不依赖命名空间声明</b>。BPMN 规范里 DI 段用的是
 * {@code http://www.omg.org/spec/BPMN/20100524/DI}，而不少模型是<b>不带前缀</b>写出来的
 * （直接 {@code <BPMNDiagram>} / {@code <BPMNShape>}），此时它们落在默认命名空间里，
 * 未必是 DI 那个。用 {@code getElementsByTagNameNS(DI_NS, "BPMNShape")} 匹配就会返回空 ——
 * 流程图悄悄变成空，而调用方拿到的是一个"成功但为空"的结果。
 *
 * <p><b>注意区分两种"没有声明"</b>：带前缀但<b>没有 xmlns 绑定</b>是<b>非法 XML</b>，
 * 任何符合规范的解析器都会直接拒绝（SAX 解析错误），本类会捕获取解并标 empty ——
 * 那不是本类能兜住的场景。真正要兜的是"标签没带前缀"。
 *
 * <p>属性同理：{@code x} / {@code width} 这些可能写成 {@code dc:x}，所以取属性时
 * 也按本地名兜一遍。
 *
 * @author zifang
 */
public final class WfDiagramParser {

    private WfDiagramParser() {
    }

    /**
     * 从 BPMN XML 里取出图形信息并与流程逻辑核对一致性。
     *
     * @param xml       原始 BPMN XML；为 null 或解析失败时返回 {@link WfDiagramInfo}
     *                  且 {@code empty=true}（<b>不抛异常</b>：拿不到图元不是部署错误，
     *                  很多流程本来就没有图）
     * @param key       流程定义 key，写进结果便于调用方回查
     * @param version   流程定义版本
     */
    public static WfDiagramInfo parse(String xml, String key, int version) {
        WfDiagramInfo info = new WfDiagramInfo();
        info.setDefinitionKey(key);
        info.setDefinitionVersion(version);
        if (xml == null || xml.trim().isEmpty()) {
            info.setEmpty(true);
            return info;
        }
        Document doc;
        try {
            doc = newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
        } catch (Exception e) {
            // 拿不到图不等于流程坏了。原始 XML 存的是部署时留存的原文，
            // 理论上应当总能解析；解析不了就如实标 empty 并让调用方决定怎么办，
            // 而不是抛异常把"回读流程图"这个只读操作变成一次失败
            info.setEmpty(true);
            return info;
        }
        Element plane = findElement(doc, "BPMNPlane");
        if (plane == null) {
            info.setEmpty(true);
            return info;
        }
        for (Element shape : findElements(plane, "BPMNShape")) {
            WfShape parsed = parseShape(shape);
            if (parsed != null) {
                info.getShapes().add(parsed);
            }
        }
        for (Element edge : findElements(plane, "BPMNEdge")) {
            WfEdge parsed = parseEdge(edge);
            if (parsed != null) {
                info.getEdges().add(parsed);
            }
        }
        if (info.getShapes().isEmpty() && info.getEdges().isEmpty()) {
            info.setEmpty(true);
        }
        return info;
    }

    /**
     * 用流程定义把图元的名称与类型补齐。
     *
     * <p>BPMN DI 里只存 id 与坐标，<b>不存名字和类型</b>（那是流程元素自己的属性）。
     * 渲染端要画成菱形还是方框、要不要显示"审批"两个字，都得从这里补 ——
     * 不补的话前端只能画出一堆同样大小的空框。
     */
    public static void fillNodeInfo(WfDiagramInfo info, WfDefinition definition) {
        if (info == null || definition == null) {
            return;
        }
        for (WfShape shape : info.getShapes()) {
            WfNode node = definition.node(shape.getId());
            if (node == null) {
                continue;
            }
            shape.setName(node.getName());
            shape.setType(node.getType() == null ? null : node.getType().bpmnName());
        }
        for (WfEdge edge : info.getEdges()) {
            for (WfFlow flow : definition.getFlows()) {
                if (flow != null && edge.getId().equals(flow.getId())) {
                    edge.setName(flow.getName());
                    edge.setSourceRef(flow.getSourceRef());
                    edge.setTargetRef(flow.getTargetRef());
                    break;
                }
            }
        }
    }

    /**
     * 核对图与流程逻辑是否对得上。
     *
     * <p>分开成独立方法而不是在解析里顺手做：解析只认 XML，
     * 而"图上多了一个框"这件事只有对照 {@link WfDefinition} 才知道。
     */
    public static void checkConsistency(WfDiagramInfo info, WfDefinition definition) {
        if (info == null || definition == null || info.isEmpty()) {
            return;
        }
        Set<String> nodeIds = new HashSet<String>();
        if (definition.getNodes() != null) {
            for (WfNode node : definition.getNodes()) {
                if (node != null && node.getId() != null) {
                    nodeIds.add(node.getId());
                }
            }
        }
        Set<String> flowIds = new HashSet<String>();
        if (definition.getFlows() != null) {
            for (WfFlow flow : definition.getFlows()) {
                if (flow != null && flow.getId() != null) {
                    flowIds.add(flow.getId());
                }
            }
        }
        for (WfShape shape : info.getShapes()) {
            // BPMN 里图元引用的是流程元素 id，与节点 id 一致
            if (!nodeIds.contains(shape.getId())) {
                info.getOrphanShapeIds().add(shape.getId());
            }
        }
        for (WfEdge edge : info.getEdges()) {
            if (!flowIds.contains(edge.getId())) {
                info.getOrphanEdgeIds().add(edge.getId());
            }
        }
        Set<String> drawn = new HashSet<String>();
        for (WfShape shape : info.getShapes()) {
            drawn.add(shape.getId());
        }
        for (String nodeId : nodeIds) {
            if (!drawn.contains(nodeId)) {
                info.getMissingNodeIds().add(nodeId);
            }
        }
        // 连线同理：只报"图上多一根"而不报"逻辑里多一根"，是把同一件事只做了一半
        Set<String> drawnFlows = new HashSet<String>();
        for (WfEdge edge : info.getEdges()) {
            drawnFlows.add(edge.getId());
        }
        for (String flowId : flowIds) {
            if (!drawnFlows.contains(flowId)) {
                info.getMissingFlowIds().add(flowId);
            }
        }
    }

    private static WfShape parseShape(Element element) {
        String ref = attr(element, "bpmnElement");
        if (ref == null || ref.trim().isEmpty()) {
            // 没有 bpmnElement 的图元无法对应到流程节点，跳过而不是造一个空 id
            return null;
        }
        WfShape shape = new WfShape();
        shape.setId(ref.trim());
        shape.setExpanded(boolAttr(element, "isExpanded"));
        shape.setMarkerVisible(boolAttr(element, "isMarkerVisible"));
        Element bounds = firstChild(element, "dc:Bounds");
        if (bounds == null) {
            bounds = firstChild(element, "Bounds");
        }
        if (bounds != null) {
            shape.setX(doubleAttr(bounds, "x"));
            shape.setY(doubleAttr(bounds, "y"));
            shape.setWidth(doubleAttr(bounds, "width"));
            shape.setHeight(doubleAttr(bounds, "height"));
        }
        return shape;
    }

    private static WfEdge parseEdge(Element element) {
        String ref = attr(element, "bpmnElement");
        if (ref == null || ref.trim().isEmpty()) {
            return null;
        }
        WfEdge edge = new WfEdge();
        edge.setId(ref.trim());
        // findElements 按本地名匹配，di:waypoint 已经能命中，
        // 不需要先按带前缀的名字找一遍再在内部找一遍（那会把每个折点数两遍）
        for (Element wp : findElements(element, "waypoint")) {
            edge.getWaypoints().add(new double[]{
                    doubleAttr(wp, "x"), doubleAttr(wp, "y")});
        }
        return edge;
    }

    // ==================== DOM 辅助 ====================

    private static DocumentBuilder newDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // 关闭外部实体：部署 XML 是外部输入，带 DTD/外部引用的 XML
        // 应当被拒绝而不是被解析（XXE）
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    private static List<Element> findElements(Element parent, String localName) {
        List<Element> result = new ArrayList<Element>();
        NodeList all = parent.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node node = all.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && matches(node, localName)) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static Element findElement(Document doc, String localName) {
        List<Element> all = new ArrayList<Element>();
        NodeList nodes = doc.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && matches(node, localName)) {
                all.add((Element) node);
            }
        }
        return all.isEmpty() ? null : all.get(0);
    }

    private static Element firstChild(Element parent, String localName) {
        for (Element child : findElements(parent, localName)) {
            return child;
        }
        return null;
    }

    /**
     * 本地名匹配：容忍带前缀、不带前缀、任意命名空间三种写法。
     */
    private static boolean matches(Node node, String localName) {
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        String local = colon >= 0 ? name.substring(colon + 1) : name;
        return localName.equals(local);
    }

    private static String attr(Element element, String name) {
        if (element == null) {
            return null;
        }
        String direct = element.getAttribute(name);
        if (direct != null && !direct.trim().isEmpty()) {
            return direct.trim();
        }
        // 属性也可能带前缀（如 dc:x）
        org.w3c.dom.NamedNodeMap attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            int colon = a.getNodeName().indexOf(':');
            String local = colon >= 0 ? a.getNodeName().substring(colon + 1) : a.getNodeName();
            if (name.equals(local)) {
                String v = a.getNodeValue();
                return v == null ? null : v.trim();
            }
        }
        return null;
    }

    private static double doubleAttr(Element element, String name) {
        String raw = attr(element, name);
        if (raw == null) {
            return 0D;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            // 坐标写坏了不等于没有坐标：取 0 让渲染端仍能画出一个框，
            // 而静默丢掉整个图元会让人以为是"模型没画图"
            return 0D;
        }
    }

    private static boolean boolAttr(Element element, String name) {
        return "true".equalsIgnoreCase(attr(element, name));
    }
}
