package com.zifang.z.wf.core.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.view.WfDiagramInfo;
import com.zifang.z.wf.core.view.WfEdge;
import com.zifang.z.wf.core.view.WfShape;

/**
 * 流程图元回读（BPMN DI）。
 *
 * <p>本类盯四件事：
 * <ol>
 *   <li><b>命名空间没被声明时也要能读</b> —— 大量工具保存的 XML 压根不声明 DI 的
 *       xmlns，用 {@code getElementsByTagNameNS} 匹配会返回空，
 *       症状是"流程图解析成功但什么都没有"。</li>
 *   <li><b>折点要全拿</b>：只取起终点的话，转弯的线会穿过旁边的方框，而折点信息
 *       只存在于 DI 里，丢了找不回来。</li>
 *   <li><b>图与逻辑对不上要说出来</b>：多一个框、少一根线都不能悄悄渲染 ——
 *       渲染端只看图元，于是"图和实际流程对不上"且无任何报错。</li>
 *   <li><b>没有 DI 段不抛异常</b>：手写流程本来就没图，把它变成一次失败会让调用方
 *       分不清"没图"与"流程坏了"。</li>
 * </ol>
 */
class WfDiagramParserTest {

    /** 标准写法：声明了 BPMN DI 命名空间。 */
    private static final String WITH_DI =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\""
            + " xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\""
            + " xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"p\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <exclusiveGateway id=\"gw\" name=\"判定\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" name=\"提\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"gw\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"gw\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "  <bpmndi:BPMNDiagram id=\"D1\">\n"
            + "    <bpmndi:BPMNPlane id=\"P1\" bpmnElement=\"p\">\n"
            + "      <bpmndi:BPMNShape id=\"S1\" bpmnElement=\"s1\">\n"
            + "        <dc:Bounds x=\"100\" y=\"150\" width=\"36\" height=\"36\"/>\n"
            + "      </bpmndi:BPMNShape>\n"
            + "      <bpmndi:BPMNShape id=\"S2\" bpmnElement=\"approve\">\n"
            + "        <dc:Bounds x=\"200\" y=\"128\" width=\"100\" height=\"80\"/>\n"
            + "      </bpmndi:BPMNShape>\n"
            + "      <bpmndi:BPMNShape id=\"S3\" bpmnElement=\"gw\" isMarkerVisible=\"true\">\n"
            + "        <dc:Bounds x=\"380\" y=\"135\" width=\"50\" height=\"50\"/>\n"
            + "      </bpmndi:BPMNShape>\n"
            + "      <bpmndi:BPMNShape id=\"S4\" bpmnElement=\"e1\">\n"
            + "        <dc:Bounds x=\"520\" y=\"150\" width=\"36\" height=\"36\"/>\n"
            + "      </bpmndi:BPMNShape>\n"
            + "      <bpmndi:BPMNEdge id=\"E1\" bpmnElement=\"f1\">\n"
            + "        <di:waypoint x=\"136\" y=\"168\"/>\n"
            + "        <di:waypoint x=\"200\" y=\"168\"/>\n"
            + "      </bpmndi:BPMNEdge>\n"
            + "      <bpmndi:BPMNEdge id=\"E2\" bpmnElement=\"f2\">\n"
            + "        <di:waypoint x=\"300\" y=\"168\"/>\n"
            + "        <di:waypoint x=\"380\" y=\"160\"/>\n"
            + "      </bpmndi:BPMNEdge>\n"
            + "      <bpmndi:BPMNEdge id=\"E3\" bpmnElement=\"f3\">\n"
            + "        <di:waypoint x=\"430\" y=\"160\"/>\n"
            + "        <di:waypoint x=\"500\" y=\"168\"/>\n"
            + "        <di:waypoint x=\"520\" y=\"168\"/>\n"
            + "      </bpmndi:BPMNEdge>\n"
            + "    </bpmndi:BPMNPlane>\n"
            + "  </bpmndi:BPMNDiagram>\n"
            + "</definitions>\n";

    /**
     * 标签不带前缀（直接 {@code <BPMNShape>}）：不少模型是这么写的，
     * 此时 DI 元素落在默认命名空间里，未必是规范指定的那个。
     *
     * <p>刻意<b>不</b>用"去掉 xmlns 绑定"那种写法：带前缀却没有 xmlns 绑定是
     * <b>非法 XML</b>，任何规范解析器都会直接拒绝，拿它测不出本地名匹配有没有用。
     */
    private static final String NO_PREFIX_NS =
            WITH_DI.replace("bpmndi:BPMNShape", "BPMNShape")
                    .replace("bpmndi:BPMNEdge", "BPMNEdge")
                    .replace("bpmndi:BPMNDiagram", "BPMNDiagram")
                    .replace("bpmndi:BPMNPlane", "BPMNPlane")
                    .replace("dc:Bounds", "Bounds")
                    .replace("di:waypoint", "waypoint");

    /** 纯逻辑，没有 DI 段。 */
    private static final String NO_DI =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"p\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /**
     * <b>一张完整的图，外加一个 DOCTYPE 声明。</b>
     *
     * <p>带图不是为了凑数，是为了让"被拒绝"和"被放行"落不到同一个结果上：
     * 只断言 {@code empty} 的话，"DOCTYPE 被拒"和"根本没有 DI 段"都会是 empty=true，
     * 摘掉 XXE 防护测试照样是绿的。带上图之后，被放行就意味着 4 个图元、empty=false。
     */
    private static final String DIAGRAM_WITH_DOCTYPE =
            WITH_DI.replace("?>\n<definitions", "?>\n"
                    + "<!DOCTYPE definitions [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n"
                    + "<definitions");

    /**
     * 属性带前缀（{@code dc:x} / {@code di:x}）。
     *
     * <p>规范里 Bounds/waypoint 的 x/y/width/height 是<b>无前缀</b>的本地属性，
     * 但取属性那一步得按本地名兜一遍才稳 —— 这里的写法不是规范要求的，
     * 是一条"万一遇到也不至于把坐标读成 0"的兜底路径，得有测试钉住它真的兜得住。
     */
    private static final String PREFIXED_ATTRS =
            WITH_DI.replace("<dc:Bounds x=\"200\" y=\"128\" width=\"100\" height=\"80\"/>",
                            "<dc:Bounds dc:x=\"200\" dc:y=\"128\""
                                    + " dc:width=\"100\" dc:height=\"80\"/>")
                    .replace("<di:waypoint x=\"136\" y=\"168\"/>",
                            "<di:waypoint di:x=\"136\" di:y=\"168\"/>");

    private WfShape shapeOf(WfDiagramInfo info, String id) {
        for (WfShape shape : info.getShapes()) {
            if (id.equals(shape.getId())) {
                return shape;
            }
        }
        return null;
    }

    // ==================== 坐标 ====================

    @Test
    @DisplayName("节点坐标原样取出")
    void shapesCarryCoordinates() {
        WfDiagramInfo info = WfDiagramParser.parse(WITH_DI, "p", 1);
        assertFalse(info.isEmpty(), "带 DI 段的 XML 必须解析出图元");

        WfShape approve = shapeOf(info, "approve");
        assertNotNull(approve, "审批节点应当有图元");
        assertEquals(200D, approve.getX(), 0.001);
        assertEquals(128D, approve.getY(), 0.001);
        assertEquals(100D, approve.getWidth(), 0.001);
        assertEquals(80D, approve.getHeight(), 0.001);
    }

    @Test
    @DisplayName("标记位（网关画菱形）要读出来，否则前端只能画方框")
    void markerVisibleIsRead() {
        WfDiagramInfo info = WfDiagramParser.parse(WITH_DI, "p", 1);
        assertTrue(shapeOf(info, "gw").isMarkerVisible(), "网关的 isMarkerVisible 应为 true");
        assertFalse(shapeOf(info, "approve").isMarkerVisible());
    }

    // ==================== 连线 ====================

    @Test
    @DisplayName("连线折点全部取出 —— 只取起终点会让转弯的线穿过方框")
    void edgesCarryAllWaypoints() {
        WfDiagramInfo info = WfDiagramParser.parse(WITH_DI, "p", 1);
        assertEquals(3, info.getEdges().size());

        WfEdge f3 = null;
        for (WfEdge edge : info.getEdges()) {
            if ("f3".equals(edge.getId())) {
                f3 = edge;
            }
        }
        assertNotNull(f3);
        assertEquals(3, f3.getWaypoints().size(),
                "f3 有三个折点，少一个就会画成一条直线穿过网关。实际 " + f3.getWaypoints().size());
        assertEquals(500D, f3.getWaypoints().get(1)[0], 0.001);
    }

    // ==================== 命名空间 ====================

    @Test
    @DisplayName("DI 标签不带前缀时照样读得出 —— 否则图元会静默变成空")
    void worksWithUnprefixedTags() {
        WfDiagramInfo info = WfDiagramParser.parse(NO_PREFIX_NS, "p", 1);
        assertFalse(info.isEmpty(),
                "标签不带前缀时必须仍能按本地名匹配到图元");
        assertEquals(4, info.getShapes().size());
        assertNotNull(shapeOf(info, "approve"));
        assertEquals(200D, shapeOf(info, "approve").getX(), 0.001);
        assertEquals(3, info.getEdges().size());
        assertEquals(2, info.getEdges().get(0).getWaypoints().size());
    }

    @Test
    @DisplayName("带前缀却没 xmlns 绑定：非法 XML，如实标 empty 而不是假装解析成功")
    void undeclaredPrefixIsRejectedAsIllegalXml() {
        String illegal = WITH_DI
                .replace(" xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\"", "")
                .replace(" xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\"", "")
                .replace(" xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\"", "");
        WfDiagramInfo info = WfDiagramParser.parse(illegal, "p", 1);
        // 这份 XML 里有 4 个图元和 3 条线：只要读出来一个，就说明它被当成合法 XML 放行了。
        // 只断言 empty 的话，"解析器拒绝了"与"解析器没找到图"会落成同一个结果
        assertTrue(info.isEmpty(),
                "未绑定的前缀是非法 XML，解析器会拒绝；本类兜不住也不该假装能兜");
        assertTrue(info.getShapes().isEmpty(),
                "非法 XML 里一个图元都不该读出来 —— 读出来就意味着它被放行了");
    }

    @Test
    @DisplayName("属性带 dc:/di: 前缀时照样读出坐标 —— 否则整张图塌到原点")
    void prefixedAttributesAreRead() {
        WfDiagramInfo info = WfDiagramParser.parse(PREFIXED_ATTRS, "p", 1);
        WfShape approve = shapeOf(info, "approve");
        assertNotNull(approve);
        assertEquals(200D, approve.getX(), 0.001, "dc:x 应当被按本地名取到");
        assertEquals(128D, approve.getY(), 0.001);
        assertEquals(100D, approve.getWidth(), 0.001);
        assertEquals(80D, approve.getHeight(), 0.001);
        assertEquals(136D, info.getEdges().get(0).getWaypoints().get(0)[0], 0.001,
                "di:x 应当被按本地名取到");
    }

    // ==================== 一致性 ====================

    @Test
    @DisplayName("图与流程对得上时一致性通过")
    void consistentDiagramPasses() {
        WfDefinition definition = new WfXmlParser().parse(WITH_DI);
        WfDiagramInfo info = WfDiagramParser.parse(WITH_DI, "p", 1);
        WfDiagramParser.checkConsistency(info, definition);

        assertTrue(info.isConsistent(),
                "应当一致。实际 缺图=" + info.getMissingNodeIds()
                        + " 多框=" + info.getOrphanShapeIds()
                        + " 多线=" + info.getOrphanEdgeIds()
                        + " 缺线=" + info.getMissingFlowIds());
    }

    @Test
    @DisplayName("逻辑里多了一个节点：列出它，不悄悄少画一个框")
    void nodeWithoutShapeIsReported() {
        WfDefinition definition = new WfXmlParser().parse(
                WITH_DI.replace("    <endEvent id=\"e1\"/>",
                        "    <userTask id=\"extra\" zifang:assignee=\"x\"/>\n"
                                + "    <endEvent id=\"e1\"/>")
                        .replace(" targetRef=\"e1\"/>\n  </process>",
                                " targetRef=\"e1\"/>\n"
                                + "    <sequenceFlow id=\"f9\" sourceRef=\"e1\""
                                + " targetRef=\"extra\"/>\n  </process>"));
        WfDiagramInfo info = WfDiagramParser.parse(WITH_DI, "p", 1);
        WfDiagramParser.checkConsistency(info, definition);

        assertTrue(info.getMissingNodeIds().contains("extra"),
                "逻辑里有、图上没有的节点必须列出来。实际 " + info.getMissingNodeIds());
        assertFalse(info.isConsistent());
    }

    @Test
    @DisplayName("图上多了一个框：列出它，否则渲染出来会凭空多一步")
    void shapeWithoutNodeIsReported() {
        WfDiagramInfo info = WfDiagramParser.parse(WITH_DI, "p", 1);
        info.getShapes().add(new WfShape());
        info.getShapes().get(info.getShapes().size() - 1).setId("ghost");
        WfDiagramParser.checkConsistency(info, new WfXmlParser().parse(WITH_DI));

        assertTrue(info.getOrphanShapeIds().contains("ghost"),
                "图上有、流程里没有的图元必须列出来");
    }

    @Test
    @DisplayName("图上多了一条线：列出它，否则渲染出来会凭空多一根线")
    void edgeWithoutFlowIsReported() {
        WfDiagramInfo info = WfDiagramParser.parse(WITH_DI, "p", 1);
        info.getEdges().add(new WfEdge());
        info.getEdges().get(info.getEdges().size() - 1).setId("ghostFlow");
        WfDiagramParser.checkConsistency(info, new WfXmlParser().parse(WITH_DI));

        assertTrue(info.getOrphanEdgeIds().contains("ghostFlow"),
                "图上有、流程里没有对应连线的线必须列出来。实际 " + info.getOrphanEdgeIds());
        assertFalse(info.isConsistent());
    }

    @Test
    @DisplayName("逻辑里多了一条线：也要列出来 —— 只报一半等于没报")
    void flowWithoutEdgeIsReported() {
        // 反方向：改了 XML 里的连线却忘了更新坐标。图上少一根线，
        // 渲染端照样画得出来，只是和实际流程对不上
        WfDefinition definition = new WfXmlParser().parse(
                WITH_DI.replace(" targetRef=\"e1\"/>\n  </process>",
                        " targetRef=\"e1\"/>\n"
                                + "    <sequenceFlow id=\"f9\" sourceRef=\"e1\""
                                + " targetRef=\"e1\"/>\n  </process>"));
        WfDiagramInfo info = WfDiagramParser.parse(WITH_DI, "p", 1);
        WfDiagramParser.checkConsistency(info, definition);

        assertTrue(info.getMissingFlowIds().contains("f9"),
                "流程里有、图上没有对应连线的线必须列出来。实际 " + info.getMissingFlowIds());
        assertFalse(info.isConsistent());
    }

    // ==================== 补齐名称与类型 ====================

    @Test
    @DisplayName("名称与类型用流程定义补齐 —— DI 段里没有这两样")
    void nodeInfoIsFilledFromDefinition() {
        WfDefinition definition = new WfXmlParser().parse(WITH_DI);
        WfDiagramInfo info = WfDiagramParser.parse(WITH_DI, "p", 1);
        WfDiagramParser.fillNodeInfo(info, definition);

        WfShape approve = shapeOf(info, "approve");
        assertEquals("审批", approve.getName());
        assertEquals("userTask", approve.getType());
        assertEquals("exclusiveGateway", shapeOf(info, "gw").getType(),
                "前端按 type 决定画菱形还是方框");

        for (WfEdge edge : info.getEdges()) {
            if ("f1".equals(edge.getId())) {
                assertEquals("提", edge.getName());
                assertEquals("s1", edge.getSourceRef());
                assertEquals("approve", edge.getTargetRef());
            }
        }
    }

    // ==================== 没有图 ====================

    @Test
    @DisplayName("没有 DI 段：返回 empty 而不是抛异常")
    void noDiagramSectionIsNotAnError() {
        WfDiagramInfo info = WfDiagramParser.parse(NO_DI, "p", 1);
        assertTrue(info.isEmpty(), "手写的流程本来就没有图");
        assertNotNull(info.getShapes());
        assertTrue(info.getShapes().isEmpty());
    }

    @Test
    @DisplayName("XML 为空 / 非法：同样不抛异常")
    void badXmlIsNotAnError() {
        assertTrue(WfDiagramParser.parse(null, "p", 1).isEmpty());
        assertTrue(WfDiagramParser.parse("   ", "p", 1).isEmpty());
        assertTrue(WfDiagramParser.parse("<not-closed", "p", 1).isEmpty());
    }

    @Test
    @DisplayName("带 DOCTYPE 的 XML 被拒绝（XXE）—— 部署内容是外部输入")
    void doctypeIsRejected() {
        WfDiagramInfo info = WfDiagramParser.parse(DIAGRAM_WITH_DOCTYPE, "p", 1);
        // 这份 XML 带着一张完整的图，所以"被拒绝"与"被放行"不会落成同一个结果：
        // 放行的话会读出 4 个图元、empty=false。只断言 empty 的话，
        // "DOCTYPE 被拒"与"根本没有 DI 段"分不开，摘掉 XXE 防护照样是绿的
        assertTrue(info.isEmpty(), "带外部实体声明的 XML 应当被拒绝而不是解析");
        assertTrue(info.getShapes().isEmpty(),
                "拒绝之后不该留下任何图元 —— 留下就说明 DOCTYPE 被放行了");
    }

    @Test
    @DisplayName("没有 bpmnElement 的图元被跳过，不造出空 id")
    void shapeWithoutRefIsSkipped() {
        String xml = WITH_DI.replace("bpmnElement=\"s1\"", "idOnly=\"s1\"");
        WfDiagramInfo info = WfDiagramParser.parse(xml, "p", 1);
        for (WfShape shape : info.getShapes()) {
            assertNotNull(shape.getId());
            assertFalse(shape.getId().trim().isEmpty());
        }
        assertEquals(3, info.getShapes().size(), "没有 bpmnElement 的那个应被跳过");
    }

    @Test
    @DisplayName("重复解析同一份 XML 结果稳定")
    void parseIsDeterministic() {
        List<WfShape> first = WfDiagramParser.parse(WITH_DI, "p", 1).getShapes();
        List<WfShape> second = WfDiagramParser.parse(WITH_DI, "p", 1).getShapes();
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).getId(), second.get(i).getId());
        }
    }
}
