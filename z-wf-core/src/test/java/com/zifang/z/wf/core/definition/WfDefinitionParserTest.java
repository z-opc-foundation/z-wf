package com.zifang.z.wf.core.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 流程定义解析与校验测试。
 *
 * <p><b>其中 {@link #parityWithZUtilWfKernel} 是本仓最重要的一道测试</b>：
 * 它把同一份 BPMN XML 分别喂给 z-util-wf-kernel 的 {@code BpmnXmlParser}
 * 与 z-wf 的 {@link WfXmlParser}，逐个节点比对类型。
 * 这是"z-util-wf 与 z-wf 共用协议"这条设计约束的<b>可执行证明</b> ——
 * 没有它，"协议一样"只是一句注释，哪天两边各自改了类型映射也没人会知道。
 *
 * @author zifang
 */
class WfDefinitionParserTest {

    // ==================== XML 解析 ====================

    @Test
    @DisplayName("解析标准 BPMN：节点、连线、条件、审批扩展属性")
    void parseStandardBpmn() {
        WfDefinition definition = new WfXmlParser().parse(BPMN_LEAVE);

        assertEquals("leaveProcess", definition.getKey());
        assertEquals("请假流程", definition.getName());
        assertEquals("审批", definition.getCategory());
        assertEquals(6, definition.getNodes().size());
        assertNotNull(definition.node("start1"));
        assertEquals(WfNodeType.START_EVENT, definition.node("start1").getType());
        assertEquals(WfNodeType.USER_TASK, definition.node("task1").getType());
        assertEquals(WfNodeType.EXCLUSIVE_GATEWAY, definition.node("gw1").getType());
        assertEquals(WfNodeType.END_EVENT, definition.node("end1").getType());

        // 审批扩展属性
        assertEquals("manager", definition.node("task1").getAssignee());
        assertEquals("leaveForm", definition.node("task1").getFormKey());
        assertEquals("PT24H", definition.node("task1").getDueDateDuration());
        assertEquals(1, definition.node("task1").getCandidateGroups().size());
        assertEquals("dept-managers", definition.node("task1").getCandidateGroups().get(0));

        // 连线与条件
        List<WfFlow> outs = definition.outgoingFlows("gw1");
        assertEquals(3, outs.size());
        int defaults = 0;
        boolean hasCondition = false;
        for (WfFlow flow : outs) {
            if (flow.isDefaultFlow()) {
                defaults++;
            }
            if (!flow.isUnconditional()) {
                hasCondition = true;
            }
        }
        assertEquals(1, defaults, "应恰好一条 default 流");
        assertTrue(hasCondition, "应至少一条带条件的连线");
    }

    @Test
    @DisplayName("解析无命名空间前缀的扩展写法（zifang_xxx）")
    void parseUnderscoreExtension() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\">"
                + "  <process id=\"p1\" name=\"流程\">"
                + "    <startEvent id=\"s\"/>"
                + "    <userTask id=\"t\" name=\"审批\" zifang_assignee=\"boss\" zifang_priority=\"80\"/>"
                + "    <endEvent id=\"e\"/>"
                + "    <sequenceFlow sourceRef=\"s\" targetRef=\"t\"/>"
                + "    <sequenceFlow sourceRef=\"t\" targetRef=\"e\"/>"
                + "  </process>"
                + "</definitions>";
        WfDefinition definition = new WfXmlParser().parse(xml);
        assertEquals("boss", definition.node("t").getAssignee());
        assertEquals(80, definition.node("t").getPriority());
    }

    @Test
    @DisplayName("XXE 防护：DOCTYPE 被拒绝")
    void rejectsDoctype() {
        String xml = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<definitions><process id=\"p\"/></definitions>";
        org.junit.jupiter.api.Assertions.assertThrows(WfDefinitionException.class,
                () -> new WfXmlParser().parse(xml));
    }

    @Test
    @DisplayName("解析失败要报出原因：空内容 / 无 process 元素")
    void parseFailureIsInformative() {
        assertThrows(WfDefinitionException.class, () -> new WfXmlParser().parse(""));
        assertThrows(WfDefinitionException.class,
                () -> new WfXmlParser().parse(
                        "<definitions><process/></definitions>".replace("<process/>", "")));
    }

    // ==================== 与 z-util-wf 的协议一致性 ====================

    @Test
    @DisplayName("协议一致：同一份 BPMN，z-wf 与 z-util-wf-kernel 解析出完全相同的节点类型")
    void parityWithZUtilWfKernel() {
        com.zifang.util.wf.kernel.bpmn.BpmnDiagram kernelDiagram =
                new com.zifang.util.wf.kernel.bpmn.BpmnXmlParser().parse(BPMN_LEAVE);
        WfDefinition definition = new WfXmlParser().parse(BPMN_LEAVE);

        assertNotNull(kernelDiagram);
        assertEquals(kernelDiagram.getNodes().size(), definition.getNodes().size(),
                "节点数量必须一致，否则说明两边支持的元素集合已分叉");

        for (com.zifang.util.wf.kernel.bpmn.BpmnDiagram.BpmnNode kernelNode : kernelDiagram.getNodes()) {
            WfNode node = definition.node(kernelNode.getId());
            assertNotNull(node, "kernel 有节点 " + kernelNode.getId() + " 而 z-wf 没有");
            WfNodeType expected = WfNodeType.fromBpmn(kernelNode.getType());
            assertEquals(expected, node.getType(),
                    "节点 " + kernelNode.getId() + " 两侧类型不一致：kernel="
                            + kernelNode.getType() + " z-wf=" + node.getType());
        }

        // 连线数量也要一致
        assertEquals(kernelDiagram.getSequenceFlows().size(), definition.getFlows().size(),
                "连线数量必须一致");
    }

    // ==================== JSON 解析 ====================

    @Test
    @DisplayName("解析 LogicFlow 导出：type 别名（user-task / branch）正确归一")
    void parseLogicFlowJson() {
        String json = "{\"logicflow\":{\"nodes\":["
                + "{\"id\":\"s\",\"type\":\"start\",\"name\":\"提交\"},"
                + "{\"id\":\"gw\",\"type\":\"branch\",\"name\":\"判断\"},"
                + "{\"id\":\"t1\",\"type\":\"user-task\",\"name\":\"经理审批\",\"assignee\":\"manager\"},"
                + "{\"id\":\"e\",\"type\":\"end\"}"
                + "],\"edges\":["
                + "{\"id\":\"f1\",\"source\":\"s\",\"target\":\"gw\"},"
                + "{\"id\":\"f2\",\"source\":\"gw\",\"target\":\"t1\",\"condition\":\"amount < 100\"},"
                + "{\"id\":\"f3\",\"source\":\"t1\",\"target\":\"e\"}"
                + "]}}";
        WfDefinition definition = new WfJsonParser().parse(json);
        assertEquals(4, definition.getNodes().size());
        assertEquals(WfNodeType.START_EVENT, definition.node("s").getType());
        assertEquals(WfNodeType.EXCLUSIVE_GATEWAY, definition.node("gw").getType());
        assertEquals(WfNodeType.USER_TASK, definition.node("t1").getType());
        assertEquals("manager", definition.node("t1").getAssignee());
        assertEquals(3, definition.getFlows().size());
        assertEquals("amount < 100", definition.outgoingFlows("gw").get(0).getConditionExpression());
    }

    @Test
    @DisplayName("解析 z-wf 原生 JSON：key/flows/defaultFlow")
    void parseNativeJson() {
        String json = "{\"key\":\"native\",\"name\":\"原生\",\"category\":\"审批\",\"nodes\":["
                + "{\"id\":\"s\",\"type\":\"startEvent\"},"
                + "{\"id\":\"gw\",\"type\":\"exclusiveGateway\"},"
                + "{\"id\":\"a\",\"type\":\"userTask\",\"assignee\":\"u1\"},"
                + "{\"id\":\"b\",\"type\":\"userTask\",\"assignee\":\"u2\"},"
                + "{\"id\":\"e\",\"type\":\"endEvent\"}"
                + "],\"flows\":["
                + "{\"id\":\"f0\",\"sourceRef\":\"s\",\"targetRef\":\"gw\"},"
                + "{\"id\":\"f1\",\"sourceRef\":\"gw\",\"targetRef\":\"a\",\"condition\":\"x == 1\"},"
                + "{\"id\":\"f2\",\"sourceRef\":\"gw\",\"targetRef\":\"b\",\"defaultFlow\":true},"
                + "{\"id\":\"f3\",\"sourceRef\":\"a\",\"targetRef\":\"e\"},"
                + "{\"id\":\"f4\",\"sourceRef\":\"b\",\"targetRef\":\"e\"}"
                + "]}";
        WfDefinition definition = new WfJsonParser().parse(json);
        assertEquals("native", definition.getKey());
        assertEquals("审批", definition.getCategory());
        boolean hasDefault = false;
        for (WfFlow flow : definition.outgoingFlows("gw")) {
            if (flow.isDefaultFlow()) {
                hasDefault = true;
            }
        }
        assertTrue(hasDefault, "defaultFlow 应被解析");
    }

    // ==================== 校验器 ====================

    @Test
    @DisplayName("校验通过：合法定义无 ERROR")
    void validateAcceptsGoodDefinition() {
        WfDefinition definition = new WfXmlParser().parse(BPMN_LEAVE);
        List<WfValidationIssue> issues = new WfDefinitionValidator().validate(definition);
        assertFalse(WfDefinitionValidator.hasError(issues),
                "合法定义不应有 ERROR: " + WfDefinitionValidator.render(issues));
    }

    @Test
    @DisplayName("校验拦下：重复节点 id")
    void validateRejectsDuplicateNodeId() {
        WfDefinition definition = new WfXmlParser().parse(BPMN_LEAVE);
        definition.getNodes().add(new WfNode("task1", "重复", WfNodeType.USER_TASK));
        assertTrue(hasErrorContaining(definition, "重复"));
    }

    @Test
    @DisplayName("校验拦下：连线指向不存在的节点")
    void validateRejectsDanglingFlow() {
        WfDefinition definition = new WfXmlParser().parse(BPMN_LEAVE);
        definition.getFlows().add(new WfFlow("task1", "ghostNode"));
        assertTrue(hasErrorContaining(definition, "不存在的节点"));
    }

    @Test
    @DisplayName("校验拦下：排他网关有条件但无 default 流 ⇒ 会卡死")
    void validateRejectsGatewayWithoutDefault() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\">"
                + "  <process id=\"p1\">"
                + "    <startEvent id=\"s\"/>"
                + "    <exclusiveGateway id=\"gw\"/>"
                + "    <userTask id=\"a\"/><userTask id=\"b\"/>"
                + "    <endEvent id=\"e\"/>"
                + "    <sequenceFlow sourceRef=\"s\" targetRef=\"gw\"/>"
                + "    <sequenceFlow sourceRef=\"gw\" targetRef=\"a\">"
                + "      <conditionExpression>x == 1</conditionExpression></sequenceFlow>"
                + "    <sequenceFlow sourceRef=\"gw\" targetRef=\"b\">"
                + "      <conditionExpression>x == 2</conditionExpression></sequenceFlow>"
                + "    <sequenceFlow sourceRef=\"a\" targetRef=\"e\"/>"
                + "    <sequenceFlow sourceRef=\"b\" targetRef=\"e\"/>"
                + "  </process></definitions>";
        WfDefinition definition = new WfXmlParser().parse(xml);
        assertTrue(hasErrorContaining(definition, "卡死"),
                "无 default 且全是有条件出线 ⇒ 必须报错而不是部署后卡住");
    }

    @Test
    @DisplayName("校验拦下：多个无条件开始节点")
    void validateRejectsMultipleStartNodes() {
        WfDefinition definition = new WfXmlParser().parse(BPMN_LEAVE);
        definition.getNodes().add(new WfNode("start2", "另一个开始", WfNodeType.START_EVENT));
        assertTrue(hasErrorContaining(definition, "多个无条件开始节点"),
                "两个都能被 startProcessInstanceByKey 进入的入口，引擎无法判断从哪进");
    }

    @Test
    @DisplayName("有事件起始也不能放过多个无条件起始 —— 放宽的是事件那个，不是无条件那个")
    void multiplePlainStartsRejectedEvenWithEventStart() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">"
                + "<process id=\"twoPlain\" isExecutable=\"true\">"
                + "<startEvent id=\"p1\"/>"
                + "<startEvent id=\"p2\"/>"
                + "<startEvent id=\"msgStart\">"
                + "<messageEventDefinition messageRef=\"orderCreated\"/></startEvent>"
                + "<userTask id=\"a\" name=\"甲\"/>"
                + "<endEvent id=\"e1\"/>"
                + "<sequenceFlow sourceRef=\"p1\" targetRef=\"a\"/>"
                + "<sequenceFlow sourceRef=\"p2\" targetRef=\"a\"/>"
                + "<sequenceFlow sourceRef=\"msgStart\" targetRef=\"a\"/>"
                + "<sequenceFlow sourceRef=\"a\" targetRef=\"e1\"/>"
                + "</process></definitions>";
        WfDefinition definition = new WfXmlParser().parse(xml);
        // 反向验证时把校验条件改成 size()>1 && eventStarts.isEmpty() 放过来了 ——
        // 那种改法在"只有无条件起始"的老用例上照样绿，是本条用例钉住它的原因
        assertTrue(hasErrorContaining(definition, "多个无条件开始节点"),
                "两个无条件入口并存时引擎无法判断 startProcessInstanceByKey 从哪进，"
                        + "有没有事件起始都一样要报错");
    }

    @Test
    @DisplayName("带 messageRef 的起始事件可以与无条件起始共存 —— 这是 BPMN 的正常写法")
    void messageStartEventCoexistsWithPlainStart() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">"
                + "<process id=\"twoStarts\" isExecutable=\"true\">"
                + "<startEvent id=\"manualStart\"/>"
                + "<startEvent id=\"msgStart\">"
                + "<messageEventDefinition messageRef=\"orderCreated\"/>"
                + "</startEvent>"
                + "<userTask id=\"approve\" name=\"审单\" zifang:assignee=\"ops\"/>"
                + "<endEvent id=\"e1\"/>"
                + "<sequenceFlow sourceRef=\"manualStart\" targetRef=\"approve\"/>"
                + "<sequenceFlow sourceRef=\"msgStart\" targetRef=\"approve\"/>"
                + "<sequenceFlow sourceRef=\"approve\" targetRef=\"e1\"/>"
                + "</process></definitions>";
        WfDefinition definition = new WfXmlParser().parse(xml);
        List<WfValidationIssue> issues = new WfDefinitionValidator().validate(definition);
        for (WfValidationIssue issue : issues) {
            assertFalse(issue.getSeverity() == WfValidationIssue.Severity.ERROR,
                    "手工发起 + 消息启动是同一流程的两个入口，不该报错。实际: " + issue.getMessage());
        }
        assertEquals(1, definition.unconditionalStartNodes().size(),
                "带 messageRef 的那个不算无条件入口");
        assertEquals(1, definition.eventStartNodes().size());
        assertNotNull(definition.messageStartNode("orderCreated"),
                "要能按消息名找到那个入口");
    }

    @Test
    @DisplayName("校验拦下：一条消息对应两个起始节点 —— 引擎无从判断该起哪个流程")
    void rejectsAmbiguousMessageStart() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">"
                + "<process id=\"dupStart\" isExecutable=\"true\">"
                + "<startEvent id=\"s1\">"
                + "<messageEventDefinition messageRef=\"orderCreated\"/></startEvent>"
                + "<startEvent id=\"s2\">"
                + "<messageEventDefinition messageRef=\"orderCreated\"/></startEvent>"
                + "<userTask id=\"a\" name=\"甲\"/>"
                + "<endEvent id=\"e1\"/>"
                + "<sequenceFlow sourceRef=\"s1\" targetRef=\"a\"/>"
                + "<sequenceFlow sourceRef=\"s2\" targetRef=\"a\"/>"
                + "<sequenceFlow sourceRef=\"a\" targetRef=\"e1\"/>"
                + "</process></definitions>";
        WfDefinition definition = new WfXmlParser().parse(xml);
        assertTrue(hasErrorContaining(definition, "orderCreated"),
                "同一条消息对应两个入口必须在部署期就报 —— "
                        + "留到运行时的话，调用方已经在发消息的路上了，"
                        + "错误会出现在一个与配置毫无关系的地方");
    }

    @Test
    @DisplayName("校验拦下：serviceTask 缺 delegate")
    void validateRejectsServiceTaskWithoutDelegate() {
        WfDefinition definition = new WfXmlParser().parse(BPMN_LEAVE);
        definition.getNodes().add(new WfNode("svc", "发通知", WfNodeType.SERVICE_TASK));
        assertTrue(hasErrorContaining(definition, "delegateClass"));
    }

    @Test
    @DisplayName("校验只收问题不中断：一次报全部问题")
    void validateCollectsAllIssues() {
        WfDefinition definition = new WfXmlParser().parse(BPMN_LEAVE);
        definition.getNodes().add(new WfNode("task1", "重复", WfNodeType.USER_TASK));
        definition.getFlows().add(new WfFlow("task1", "ghost"));
        definition.setKey(null);
        List<WfValidationIssue> issues = new WfDefinitionValidator().validate(definition);
        // 至少三条：key 为空 / id 重复 / 连线悬空
        assertTrue(issues.size() >= 3, "应一次性报出全部问题，实际 " + issues.size());
        assertTrue(WfDefinitionValidator.hasError(issues));
    }

    // ==================== 节点类型归一 ====================

    @Test
    @DisplayName("类型归一：三种写法（userTask / user-task / USER_TASK）都识别；未识别退化为 TASK")
    void nodeTypeNormalization() {
        assertEquals(WfNodeType.USER_TASK, WfNodeType.fromBpmn("userTask"));
        assertEquals(WfNodeType.USER_TASK, WfNodeType.fromBpmn("user-task"));
        assertEquals(WfNodeType.USER_TASK, WfNodeType.fromBpmn("USER_TASK"));
        assertEquals(WfNodeType.PARALLEL_GATEWAY, WfNodeType.fromBpmn("parallelgateway"));
        assertEquals(WfNodeType.TASK, WfNodeType.fromBpmn("某设计器的私有节点"));
        assertEquals(WfNodeType.TASK, WfNodeType.fromBpmn(null));
    }

    @Test
    @DisplayName("startNode：无显式 startEvent 时退化为「无入线节点」")
    void startNodeFallsBackToNoIncoming() {
        WfDefinition definition = new WfDefinition("k", "k");
        WfNode a = new WfNode("a", "a", WfNodeType.USER_TASK);
        WfNode b = new WfNode("b", "b", WfNodeType.USER_TASK);
        definition.setNodes(java.util.Arrays.asList(a, b));
        definition.setFlows(java.util.Collections.singletonList(new WfFlow("a", "b")));
        definition.buildIndex();
        assertEquals("a", definition.startNode().getId());
    }

    @Test
    @DisplayName("startNode：多个开始节点时抛错（引擎不能猜入口）")
    void startNodeRejectsMultiple() {
        WfDefinition definition = new WfDefinition("k", "k");
        definition.setNodes(java.util.Arrays.asList(
                new WfNode("s1", "s1", WfNodeType.START_EVENT),
                new WfNode("s2", "s2", WfNodeType.START_EVENT)));
        definition.buildIndex();
        assertThrows(IllegalStateException.class, definition::startNode);
    }

    // ==================== 辅助 ====================

    private boolean hasErrorContaining(WfDefinition definition, String keyword) {
        List<WfValidationIssue> issues = new WfDefinitionValidator().validate(definition);
        for (WfValidationIssue issue : issues) {
            if (issue.getSeverity() == WfValidationIssue.Severity.ERROR
                    && issue.getMessage() != null && issue.getMessage().contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 标准 BPMN 示例：带审批扩展属性与排他网关（金额分流）。
     */
    static final String BPMN_LEAVE =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "             xmlns:zifang=\"http://zifang.com/wf/bpmn/ext\" "
            + "             targetNamespace=\"http://zifang.com/wf\">"
            + "  <process id=\"leaveProcess\" name=\"请假流程\" "
            + "           zifang:category=\"审批\">"
            + "    <startEvent id=\"start1\" name=\"提交申请\"/>"
            + "    <userTask id=\"task1\" name=\"经理审批\" "
            + "              zifang:assignee=\"manager\" zifang:formKey=\"leaveForm\" "
            + "              zifang:candidateGroups=\"dept-managers\" zifang:dueDate=\"PT24H\"/>"
            + "    <exclusiveGateway id=\"gw1\" name=\"天数判断\"/>"
            + "    <userTask id=\"task2\" name=\"HR 备案\" zifang:assignee=\"hr\"/>"
            + "    <userTask id=\"task3\" name=\"总经理审批\" zifang:assignee=\"ceo\"/>"
            + "    <endEvent id=\"end1\" name=\"结束\"/>"
            + "    <sequenceFlow id=\"f0\" sourceRef=\"start1\" targetRef=\"task1\"/>"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"task1\" targetRef=\"gw1\"/>"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"gw1\" targetRef=\"task2\">"
            + "      <conditionExpression>days &lt;= 3</conditionExpression></sequenceFlow>"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"gw1\" targetRef=\"task3\">"
            + "      <conditionExpression>days &gt; 3</conditionExpression></sequenceFlow>"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"gw1\" targetRef=\"task2\" "
            + "                 zifang:defaultFlow=\"true\"/>"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"task2\" targetRef=\"end1\"/>"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"task3\" targetRef=\"end1\"/>"
            + "  </process>"
            + "</definitions>";
}
