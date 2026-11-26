package com.zifang.z.wf.core.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.persistence.WfDefinitionCodec;

/**
 * 补偿机制的定义层（第 37 轮）—— 解析、持久化往返、部署期校验。
 *
 * <p>补偿在 BPMN 里由三样东西组成，本类盯的就是它们<b>不许悄悄丢</b>：
 * <ol>
 *   <li><b>补偿处理器</b>：{@code isForCompensation="true"} 的活动，正常路径上永远不执行</li>
 *   <li><b>补偿边界事件</b>：{@code <boundaryEvent>} 内含 {@code <compensateEventDefinition/>}</li>
 *   <li><b>关联线</b>：{@code <association>} 把前两者连起来 ——
 *       本引擎此前<b>一次都没解析过</b>这个元素</li>
 * </ol>
 *
 * <p>三者任一丢失的症状都是同一个：<b>补偿不发生，且没有任何报错</b>。
 * 流程照常跑完、轨迹正常、实例状态正常 —— 业务上要等到「该退的款没退」
 * 才有人发现，那时已经没人说得清是哪一步没生效。
 * 所以本类的重点不是「能不能解析出来」，而是<b>每一层往返之后它还在不在</b>。
 *
 * <p>运行期行为（登记、逆序执行、等待完成）在
 * {@code com.zifang.z.wf.core.engine.WfCompensationTest}。
 */
class WfCompensationDefinitionTest {

    private static final String NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /**
     * 最普通的补偿三件套：订票 → 扣款 → 补偿边界事件 + 关联到「退订」处理器。
     *
     * <p>刻意让 handler 挂在 {@code <process>} 层级下且<b>没有任何入线</b> ——
     * 这正是 BPMN 里补偿处理器的正确形态，也正是引擎最容易把它错当成流程入口的地方。
     */
    private static final String BASIC_COMPENSATION_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"bookTrip\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"book\" name=\"订票\" zifang:assignee=\"alice\">\n"
            + "      <boundaryEvent id=\"beComp\" attachedToRef=\"book\">\n"
            + "        <compensateEventDefinition/>\n"
            + "      </boundaryEvent>\n"
            + "    </userTask>\n"
            + "    <serviceTask id=\"pay\" name=\"扣款\""
            + " zifang:delegateExpression=\"charge\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <serviceTask id=\"refund\" name=\"退订\""
            + " isForCompensation=\"true\" zifang:delegateExpression=\"refund\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"book\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"book\" targetRef=\"pay\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"pay\" targetRef=\"e1\"/>\n"
            + "    <association id=\"a1\" sourceRef=\"beComp\" targetRef=\"refund\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** {@code activityRef} 指向别处：补偿目标不是宿主活动。 */
    private static final String ACTIVITY_REF_BPMN = BASIC_COMPENSATION_BPMN
            .replace("<compensateEventDefinition/>",
                    "<compensateEventDefinition activityRef=\"pay\"/>")
            .replace("id=\"bookTrip\"", "id=\"activityRefProcess\"");

    private List<WfValidationIssue> errorsOf(WfDefinition definition) {
        List<WfValidationIssue> errors = new ArrayList<>();
        for (WfValidationIssue issue : new WfDefinitionValidator().validate(definition)) {
            if (issue.getSeverity() == WfValidationIssue.Severity.ERROR) {
                errors.add(issue);
            }
        }
        return errors;
    }

    private boolean hasError(WfDefinition definition, String keyword) {
        for (WfValidationIssue issue : errorsOf(definition)) {
            String text = issue.getMessage() + " " + issue.getNodeId();
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String allErrors(WfDefinition definition) {
        StringBuilder sb = new StringBuilder("[");
        for (WfValidationIssue issue : errorsOf(definition)) {
            if (sb.length() > 1) {
                sb.append(" | ");
            }
            sb.append(issue.getMessage()).append(" (").append(issue.getNodeId()).append(")");
        }
        return sb.append("]").toString();
    }

    // ==================== 解析 ====================

    @Test
    @DisplayName("<association> 此前一次都没解析过 —— 现在它必须进表")
    void associationIsParsed() {
        WfDefinition definition = new WfXmlParser().parse(BASIC_COMPENSATION_BPMN);

        assertEquals(1, definition.getAssociations().size(),
                "association 是补偿的第三条腿，此前解析器完全没读它 —— "
                        + "补偿边界事件能找到宿主，但永远找不到要执行什么");
        WfAssociation association = definition.getAssociations().get(0);
        assertEquals("a1", association.getId());
        assertEquals("beComp", association.getSourceRef(),
                "sourceRef 指的是被触发的补偿边界事件");
        assertEquals("refund", association.getTargetRef(),
                "targetRef 才是要执行的补偿处理器");
    }

    @Test
    @DisplayName("association 不许混进 flows：它不承载 token")
    void associationIsNotAFlow() {
        WfDefinition definition = new WfXmlParser().parse(BASIC_COMPENSATION_BPMN);
        List<String> flowIds = new ArrayList<>();
        for (WfFlow flow : definition.getFlows()) {
            flowIds.add(flow.getId());
        }
        assertFalse(flowIds.contains("a1"),
                "association 一旦混进 flows，每个遍历出线的循环都得再判一次类型，"
                        + "漏一处就会把补偿处理器当成普通后继节点执行 —— "
                        + "而它没有入线，症状是流程里凭空多出一次反向操作");
        assertEquals(3, flowIds.size(), "本定义只有 3 条真正的 sequenceFlow");
    }

    @Test
    @DisplayName("isForCompensation / compensateEventDefinition 都要读出来")
    void compensationFlagsAreParsed() {
        WfDefinition definition = new WfXmlParser().parse(BASIC_COMPENSATION_BPMN);

        WfNode handler = definition.node("refund");
        assertNotNull(handler, "带 isForCompensation 的活动应当被解析出来");
        assertTrue(handler.isForCompensation(), "isForCompensation=\"true\" 必须读出来");

        WfNode boundary = definition.node("beComp");
        assertNotNull(boundary);
        assertTrue(boundary.isCompensationBoundary(),
                "含 <compensateEventDefinition/> 的边界事件要认出它是补偿边界事件");
        assertEquals("book", definition.compensationTargetOf(boundary),
                "没写 activityRef 时补偿目标就是宿主活动");
    }

    @Test
    @DisplayName("activityRef 指向别的活动时，补偿目标是它而不是宿主")
    void activityRefOverridesTheHost() {
        WfDefinition definition = new WfXmlParser().parse(ACTIVITY_REF_BPMN);
        WfNode boundary = definition.node("beComp");
        assertEquals("pay", boundary.compensationActivityRef());
        assertEquals("pay", definition.compensationTargetOf(boundary),
                "写 activityRef 就是「退的是扣款那一笔」，不是「退订票这一笔」");
    }

    @Test
    @DisplayName("association 端点写一半 ⇒ 解析期报错，不静默丢弃")
    void halfAssociationIsRejectedAtParseTime() {
        String xml = BASIC_COMPENSATION_BPMN
                .replace("<association id=\"a1\" sourceRef=\"beComp\" targetRef=\"refund\"/>",
                        "<association id=\"a1\" sourceRef=\"beComp\"/>");
        try {
            new WfXmlParser().parse(xml);
            assertTrue(false, "端点缺失的 association 必须报错 —— "
                    + "丢掉它不会有任何兜底消息，作者只会看到补偿安静地不发生");
        } catch (WfDefinitionException expected) {
            assertTrue(expected.getMessage().contains("targetRef"),
                    "报错要说清缺的是哪一端。实际: " + expected.getMessage());
        }
    }

    // ==================== 定义查询 ====================

    @Test
    @DisplayName("补偿处理器不能被当成流程入口（它天然没有入线）")
    void compensationHandlerIsNotAProcessEntry() {
        // 这一条最容易踩：unconditionalStartNodes 的退化分支判「无入线」，
        // 而补偿处理器**一定**无入线（token 走不到它）。
        // 不排除的话，每个画了补偿的流程都会凭空多出一个无条件入口，
        // 部署期报「存在多个无条件开始节点」—— 而作者图上确实只有一个 startEvent。
        WfDefinition definition = new WfXmlParser().parse(BASIC_COMPENSATION_BPMN);
        List<WfNode> starts = definition.unconditionalStartNodes();
        List<String> ids = new ArrayList<>();
        for (WfNode node : starts) {
            ids.add(node.getId());
        }
        assertEquals(1, starts.size(), "实际算出 " + ids.size() + " 个无条件入口: " + ids);
        assertEquals("s1", ids.get(0), "流程入口必须是 startEvent，不能是补偿处理器");
        assertEquals(0, errorsOf(definition).size(),
                "这套定义本身是合法的，实际报错: " + allErrors(definition));
    }

    @Test
    @DisplayName("退化入口判定也要排除补偿处理器（去掉 startEvent 的那条分支）")
    void compensationHandlerIsNotADegenerateEntry() {
        // 与上面那条测的是**不同的代码分支**：
        // 上面走「有 startEvent」那条，本条走「没有 startEvent ⇒ 按无入线退化判定」。
        // 两条分支在 WfDefinition#unconditionalStartNodes 里是分开写的，
        // 只测一条的话，另一条上忘了排除照样部署不了 —— 而症状完全一样。
        String xml = BASIC_COMPENSATION_BPMN
                .replace("    <startEvent id=\"s1\"/>\n", "")
                .replace("    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"book\"/>\n", "")
                .replace("id=\"bookTrip\"", "id=\"noStartEventProcess\"");
        WfDefinition definition = new WfXmlParser().parse(xml);

        List<String> ids = new ArrayList<>();
        for (WfNode node : definition.unconditionalStartNodes()) {
            ids.add(node.getId());
        }
        assertEquals(1, ids.size(), "退化入口必须唯一，实际: " + ids);
        assertEquals("book", ids.get(0),
                "入口是补款这一步（book），**不是**补偿处理器 refund —— "
                        + "后者在正常路径上永远不会被 token 走到，选它当入口等于把退款当第一步执行");
        assertFalse(ids.contains("refund"), "补偿处理器绝不能成为流程入口");
        assertEquals(0, errorsOf(definition).size(),
                "去掉 startEvent 的定义本身合法（退化入口是 book），实际报错: " + allErrors(definition));
    }

    // ==================== 持久化往返 ====================

    @Test
    @DisplayName("关联线必须活过一次编解码往返")
    void associationSurvivesTheRoundTrip() {
        WfDefinition definition = new WfXmlParser().parse(BASIC_COMPENSATION_BPMN);
        WfDefinition back = WfDefinitionCodec.decode(WfDefinitionCodec.encode(definition));

        assertNotNull(back);
        assertEquals(1, back.getAssociations().size(),
                "关联线不落库的后果是重启后补偿边界事件找不到处理器 —— "
                        + "而症状是「补偿安静地不发生」，没有任何报错");
        assertEquals("beComp", back.getAssociations().get(0).getSourceRef());
        assertEquals("refund", back.getAssociations().get(0).getTargetRef());
        assertEquals(1, back.compensationHandlersOf("beComp").size(),
                "读回来的定义必须还能按边界事件查到处理器");
    }

    @Test
    @DisplayName("补偿标记也要活过往返（它们走 properties）")
    void compensationFlagsSurviveTheRoundTrip() {
        WfDefinition definition = new WfXmlParser().parse(ACTIVITY_REF_BPMN);
        WfDefinition back = WfDefinitionCodec.decode(WfDefinitionCodec.encode(definition));

        assertTrue(back.node("refund").isForCompensation(),
                "往返之后处理器不再被认出来 ⇒ 它会重新变成一个「无入线的普通活动」");
        assertTrue(back.node("beComp").isCompensationBoundary(),
                "往返之后补偿边界事件不再被认出来 ⇒ 补偿永远不触发");
        assertEquals("pay", back.node("beComp").compensationActivityRef(),
                "activityRef 丢了会让补偿退错对象：退订票而不是退款");
    }

    @Test
    @DisplayName("老定义（没有 associations 字段）读回来不能炸")
    void definitionWithoutAssociationsStillDecodes() {
        String json = "{\"key\":\"legacy\",\"name\":\"legacy\",\"version\":1,"
                + "\"nodes\":[{\"id\":\"s1\",\"type\":\"startEvent\"},"
                + "{\"id\":\"e1\",\"type\":\"endEvent\"}],"
                + "\"flows\":[{\"id\":\"f1\",\"sourceRef\":\"s1\",\"targetRef\":\"e1\"}]}";
        WfDefinition back = WfDefinitionCodec.decode(json);
        assertNotNull(back, "升级不能让整批存量定义读不出来");
        assertTrue(back.getAssociations().isEmpty(), "缺这个字段按空处理");
    }

    // ==================== 部署期挡掉 ====================

    @Test
    @DisplayName("补偿处理器接了入线 ⇒ 部署期报错（它会在正轨上真的执行一次）")
    void compensationHandlerWithIncomingFlowIsRejected() {
        String xml = BASIC_COMPENSATION_BPMN
                .replace("<sequenceFlow id=\"f3\" sourceRef=\"pay\" targetRef=\"e1\"/>",
                        "<sequenceFlow id=\"f3\" sourceRef=\"pay\" targetRef=\"e1\"/>"
                                + "<sequenceFlow id=\"f4\" sourceRef=\"pay\" targetRef=\"refund\"/>")
                .replace("id=\"bookTrip\"", "id=\"handlerWithIncomingProcess\"");
        WfDefinition definition = new WfXmlParser().parse(xml);
        assertTrue(hasError(definition, "不能有入线"),
                "实际报错: " + allErrors(definition));
    }

    @Test
    @DisplayName("补偿边界事件没关联处理器 ⇒ 部署期报错（否则触发时无事发生）")
    void compensationBoundaryWithoutHandlerIsRejected() {
        String xml = BASIC_COMPENSATION_BPMN
                .replace("    <association id=\"a1\" sourceRef=\"beComp\" targetRef=\"refund\"/>\n", "")
                .replace("id=\"bookTrip\"", "id=\"noHandlerProcess\"");
        WfDefinition definition = new WfXmlParser().parse(xml);
        assertTrue(hasError(definition, "没有任何关联的补偿处理器"),
                "这是补偿最隐蔽的一种坏法：部署成功、边界事件挂在图上、"
                        + "触发时找不到要执行的东西，于是什么都不发生。实际报错: " + allErrors(definition));
    }

    @Test
    @DisplayName("association 的 target 没标 isForCompensation ⇒ 部署期报错")
    void associationToNonHandlerIsRejected() {
        String xml = BASIC_COMPENSATION_BPMN
                .replace("<association id=\"a1\" sourceRef=\"beComp\" targetRef=\"refund\"/>",
                        "<association id=\"a1\" sourceRef=\"beComp\" targetRef=\"pay\"/>")
                .replace("id=\"bookTrip\"", "id=\"associationToNonHandler\"");
        WfDefinition definition = new WfXmlParser().parse(xml);
        assertTrue(hasError(definition, "isForCompensation"),
                "关联到一个普通活动上，等于把补偿动作接进正轨 —— "
                        + "同一段逻辑会跑两遍。实际报错: " + allErrors(definition));
    }

    @Test
    @DisplayName("activityRef 指向不存在的活动 ⇒ 部署期报错")
    void danglingActivityRefIsRejected() {
        String xml = ACTIVITY_REF_BPMN
                .replace("activityRef=\"pay\"", "activityRef=\"neverDeclared\"")
                .replace("id=\"activityRefProcess\"", "id=\"danglingRefProcess\"");
        WfDefinition definition = new WfXmlParser().parse(xml);
        assertTrue(hasError(definition, "activityRef"),
                "补偿会去找那个活动的处理器，而它不存在。实际报错: " + allErrors(definition));
    }

    @Test
    @DisplayName("补偿处理器上再挂补偿边界事件 ⇒ 部署期报错（否则撤销会递归触发自己）")
    void compensationHandlerWithItsOwnBoundaryIsRejected() {
        // 这条是防**递归**的：处理器完成时会被登记成新的可补偿项，
        // 下次撤销触发它，而它触发的又是自己 —— 每撤销一次就真的执行一遍退款。
        // 而这个循环只在同一作用域被撤销第二次时才显形，排查时极难看出因果。
        String xml = BASIC_COMPENSATION_BPMN
                .replace("<serviceTask id=\"refund\" name=\"退订\""
                        + " isForCompensation=\"true\" zifang:delegateExpression=\"refund\"/>",
                        "<serviceTask id=\"refund\" name=\"退订\""
                                + " isForCompensation=\"true\" zifang:delegateExpression=\"refund\">"
                                + "<boundaryEvent id=\"beRefund\" attachedToRef=\"refund\">"
                                + "<compensateEventDefinition/></boundaryEvent></serviceTask>")
                .replace("<association id=\"a1\" sourceRef=\"beComp\" targetRef=\"refund\"/>",
                        "<association id=\"a1\" sourceRef=\"beComp\" targetRef=\"refund\"/>"
                                + "<association id=\"a2\" sourceRef=\"beRefund\" targetRef=\"refund\"/>")
                .replace("id=\"bookTrip\"", "id=\"handlerWithBoundary\"");
        WfDefinition definition = new WfXmlParser().parse(xml);

        assertEquals(1, definition.compensationBoundariesOf("refund").size(),
                "前置条件：这条补偿边界事件确实挂在处理器上");
        assertTrue(hasError(definition, "不能挂补偿边界事件"),
                "实际报错: " + allErrors(definition));
    }

    @Test
    @DisplayName("补偿处理器标在网关上 ⇒ 部署期报错（网关在补偿时没有可分叉的上下文）")
    void compensationOnGatewayIsRejected() {
        // 前一版这条判据写成「关联端点指向一个没标 isForCompensation 的活动」，
        // 那测的是另一条规则（association 端点类型），字段名和它声称的完全对不上 ——
        // 一条判据钉不住它自己写的那句话，等于没有。
        // 这条要真的把 refund 换成网关：标了 isForCompensation，但类型不是活动。
        String xml = BASIC_COMPENSATION_BPMN
                .replace("<serviceTask id=\"refund\" name=\"退订\""
                        + " isForCompensation=\"true\" zifang:delegateExpression=\"refund\"/>",
                        "<exclusiveGateway id=\"refund\""
                                + " isForCompensation=\"true\"/>")
                .replace("id=\"bookTrip\"", "id=\"handlerIsGateway\"");
        WfDefinition definition = new WfXmlParser().parse(xml);

        assertTrue(definition.node("refund").isForCompensation(),
                "前置条件：这一版里 refund 确实标了 isForCompensation —— "
                        + "否则报错来自「不是处理器」那条规则，本条判据什么也证明不了");
        assertTrue(hasError(definition, "只能标在能一次跑完的活动上"),
                "标在非活动上的处理器必须报错。实际报错: " + allErrors(definition));
    }
}
