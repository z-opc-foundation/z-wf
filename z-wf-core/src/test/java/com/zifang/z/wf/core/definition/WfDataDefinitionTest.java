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

import com.zifang.z.wf.core.persistence.WfDefinitionCodec;

/**
 * {@code dataObject} / {@code dataStore} / 数据关联的定义层验证（第 46 轮）。
 *
 * <h3>本轮改的是什么问题</h3>
 * 改动前这些元素在 {@code WfXmlParser} 里<b>一处都没有</b>，
 * 它们也不在「不支持元素」清单里 ⇒ 写与不写完全一样，
 * 悬空引用连一个错都不报。本类盯的就是<b>每一层往返之后它还在不在</b>，
 * 以及<b>引用断了会不会真的报出来</b>。
 *
 * <p>本引擎<b>不执行</b>数据关联、<b>不碰</b> dataStore（与 Camunda 7 同取舍，
 * 理由见 {@link WfDataObject} / {@link WfDataStore} 类注释）——
 * 所以这里没有一条判据是"数据流过去了"，全部都是"声明没丢、错误没藏"。
 *
 * @author zifang
 */
class WfDataDefinitionTest {

    private static final String NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    private static final String HEAD =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<definitions xmlns=\"" + NS + "\""
                    + " xmlns:zifang=\"http://zifang.com/wf/bpmn/ext\""
                    + " targetNamespace=\"http://zifang.com/wf\">";

    /**
     * 带 delegate 的 serviceTask 开头。
     *
     * <p>数据关联在 BPMN 里通常挂在 serviceTask 上，但裸 serviceTask 会被
     * 「需要 delegateClass 或 delegateExpression 之一」那条<b>既有</b>规则挡下，
     * 于是本类每条判据都会多出一条与本轮无关的 ERROR ——
     * 判据混进噪声之后，"恰好 N 条"这种断言就失去了意义。
     */
    private static final String SERVICE_OPEN =
            "<serviceTask id=\"t1\" zifang:delegateClass=\"com.zifang.demo.Demo\">";

    private static final String SERVICE_SELF_CLOSING =
            "<serviceTask id=\"t1\" zifang:delegateClass=\"com.zifang.demo.Demo\"/>";

    private static final String TAIL = "</definitions>";

    /** 最小的可用图：开始 → 任务 → 结束。 */
    private static String skeleton(String key) {
        return HEAD + "<process id=\"" + key + "\" name=\"数据流程\">"
                + "  <startEvent id=\"s\"/>"
                + "  <userTask id=\"t1\" name=\"审批\"/>"
                + "  <endEvent id=\"e\"/>"
                + "  <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t1\"/>"
                + "  <sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"e\"/>"
                + "</process>" + TAIL;
    }

    private WfDefinition parse(String xml) {
        return new WfXmlParser().parse(xml);
    }

    private List<String> errorsOf(WfDefinition definition) {
        List<String> messages = new java.util.ArrayList<>();
        for (WfValidationIssue issue : new WfDefinitionValidator().validate(definition)) {
            if (issue.getSeverity() == WfValidationIssue.Severity.ERROR) {
                messages.add(issue.getMessage());
            }
        }
        return messages;
    }

    private WfDataObject dataObject(WfDefinition definition, String id) {
        for (WfDataObject candidate : definition.getDataObjects()) {
            if (candidate.getId().equals(id)) {
                return candidate;
            }
        }
        return null;
    }

    private WfDataObjectReference reference(WfDefinition definition, String id) {
        for (WfDataObjectReference candidate : definition.getDataObjectReferences()) {
            if (candidate.getId().equals(id)) {
                return candidate;
            }
        }
        return null;
    }

    private WfDataAssociation association(WfDefinition definition, String id) {
        for (WfDataAssociation candidate : definition.getDataAssociations()) {
            if (candidate.getId() != null && candidate.getId().equals(id)) {
                return candidate;
            }
        }
        return null;
    }

    /** 断言失败时把实际 id 列表打出来（失败消息要打印实际集合，不能只报个数）。 */
    private static List<String> idsOfDataObjects(WfDefinition definition) {
        List<String> ids = new java.util.ArrayList<>();
        for (WfDataObject candidate : definition.getDataObjects()) {
            ids.add(candidate.getId() + "/" + candidate.getScope());
        }
        return ids;
    }

    // ==================== 解析 ====================

    @Test
    @DisplayName("1_ dataObject 与 dataStore 读得进来，字段逐个对上")
    void parsesDataObjectAndDataStore() {
        WfDefinition definition = parse(HEAD
                + "<process id=\"p1\">"
                + "  <startEvent id=\"s\"/>"
                + "  <endEvent id=\"e\"/>"
                + "  <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"e\"/>"
                + "  <dataObject id=\"do1\" name=\"订单\" itemSubjectRef=\"tns:Order\"/>"
                + "  <dataStore id=\"ds1\" name=\"订单库\" capacity=\"1000\" isUnlimited=\"true\"/>"
                + "</process>" + TAIL);

        assertEquals(1, definition.getDataObjects().size(), "只声明了一个 dataObject");
        WfDataObject do1 = dataObject(definition, "do1");
        assertNotNull(do1, "dataObject do1 没解析出来");
        assertEquals("订单", do1.getName());
        assertEquals("tns:Order", do1.getItemSubjectRef());
        assertEquals(WfDataScope.PROCESS, do1.getScope(), "直接写在 process 下的应是流程级");

        assertEquals(1, definition.getDataStores().size());
        WfDataStore ds1 = definition.dataStore("ds1");
        assertNotNull(ds1, "dataStore ds1 没解析出来");
        assertEquals("订单库", ds1.getName());
        assertEquals(Integer.valueOf(1000), ds1.getCapacity());
        assertTrue(ds1.isUnlimited());
    }

    @Test
    @DisplayName("2_ 写在 subProcess 内的 dataObject 作用域是 STAGE（不是靠数嵌套层数）")
    void detectsStageScope() {
        WfDefinition definition = parse(HEAD
                + "<process id=\"p1\">"
                + "  <startEvent id=\"s\"/>"
                + "  <subProcess id=\"sub\">"
                + "    <startEvent id=\"s2\"/>"
                + "    <endEvent id=\"e2\"/>"
                + "    <sequenceFlow id=\"f9\" sourceRef=\"s2\" targetRef=\"e2\"/>"
                + "    <dataObject id=\"doStage\" name=\"阶段数据\"/>"
                + "  </subProcess>"
                + "  <endEvent id=\"e\"/>"
                + "  <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"sub\"/>"
                + "  <sequenceFlow id=\"f2\" sourceRef=\"sub\" targetRef=\"e\"/>"
                + "  <dataObject id=\"doProc\" name=\"流程数据\"/>"
                + "</process>" + TAIL);

        assertEquals(WfDataScope.STAGE, dataObject(definition, "doStage").getScope(),
                "嵌在 subProcess 里的 dataObject 活到那个子流程结束");
        assertEquals(WfDataScope.PROCESS, dataObject(definition, "doProc").getScope(),
                "写在 process 下的 dataObject 活到实例结束");
    }

    @Test
    @DisplayName("3_ ioSpecification 的 dataInput-dataOutput 落进同一个引用表并带上 kind")
    void parsesIoSpecification() {
        WfDefinition definition = parse(HEAD
                + "<process id=\"p1\">"
                + "  <startEvent id=\"s\"/>"
                + SERVICE_OPEN
                + "    <ioSpecification>"
                + "      <dataInput id=\"din1\" name=\"订单\" dataObjectRef=\"do1\"/>"
                + "      <dataOutput id=\"dout1\" name=\"回执\" dataObjectRef=\"do2\"/>"
                + "    </ioSpecification>"
                + "  </serviceTask>"
                + "  <endEvent id=\"e\"/>"
                + "  <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t1\"/>"
                + "  <sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"e\"/>"
                + "  <dataObject id=\"do1\" name=\"订单\"/>"
                + "  <dataObject id=\"do2\" name=\"回执\"/>"
                + "</process>" + TAIL);

        assertEquals(2, definition.getDataObjectReferences().size(),
                "dataInput 与 dataOutput 都落进数据引用表");
        WfDataObjectReference din = reference(definition, "din1");
        assertNotNull(din, "dataInput 没解析出来");
        assertEquals(WfDataObjectReference.Kind.INPUT, din.getKind());
        assertEquals("do1", din.getDataObjectRef());
        WfDataObjectReference dout = reference(definition, "dout1");
        assertNotNull(dout, "dataOutput 没解析出来");
        assertEquals(WfDataObjectReference.Kind.OUTPUT, dout.getKind());

        // 端点查表必须同时认得 dataObject 与数据引用，否则数据关联的端点校验会全判悬空
        assertNotNull(definition.dataObject("do1"));
        assertNotNull(definition.dataObjectReference("din1"));
        assertNull(definition.dataObject("din1"), "din1 是引用不是 dataObject，按类型查应查不到");
    }

    @Test
    @DisplayName("4_ 数据关联读得进来：方向/宿主/两端/transformation/assignment 都在")
    void parsesDataAssociation() {
        WfDefinition definition = parse(HEAD
                + "<process id=\"p1\">"
                + "  <startEvent id=\"s\"/>"
                + SERVICE_OPEN
                + "    <ioSpecification>"
                + "      <dataInput id=\"din1\" dataObjectRef=\"do1\"/>"
                + "      <dataOutput id=\"dout1\" dataObjectRef=\"do2\"/>"
                + "    </ioSpecification>"
                + "    <dataInputAssociation id=\"dia1\" sourceRef=\"dor1\" targetRef=\"din1\">"
                + "      <transformation>${order.amount}</transformation>"
                + "    </dataInputAssociation>"
                + "    <dataOutputAssociation id=\"doa1\" sourceRef=\"do2\" targetRef=\"dor2\">"
                + "      <assignment>from <to>x</to> to <to>y</to></assignment>"
                + "      <assignment>from <to>p</to> to <to>q</to></assignment>"
                + "    </dataOutputAssociation>"
                + "  </serviceTask>"
                + "  <endEvent id=\"e\"/>"
                + "  <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t1\"/>"
                + "  <sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"e\"/>"
                + "  <dataObject id=\"do1\"/>"
                + "  <dataObject id=\"do2\"/>"
                + "  <dataObjectReference id=\"dor1\" dataObjectRef=\"do1\"/>"
                + "  <dataObjectReference id=\"dor2\" dataObjectRef=\"do2\"/>"
                + "</process>" + TAIL);

        assertEquals(2, definition.getDataAssociations().size());
        WfDataAssociation in = association(definition, "dia1");
        assertNotNull(in, "dataInputAssociation 没解析出来");
        assertEquals(WfDataDirection.INPUT, in.getDirection());
        assertEquals("t1", in.getOwnerId(), "宿主是声明它的那个活动");
        assertEquals("dor1", in.getSourceRef());
        assertEquals("din1", in.getTargetRef());
        assertEquals("${order.amount}", in.getTransformation(), "transformation 存原文不求值");
        assertTrue(in.getAssignments().isEmpty());

        WfDataAssociation out = association(definition, "doa1");
        assertNotNull(out, "dataOutputAssociation 没解析出来");
        assertEquals(WfDataDirection.OUTPUT, out.getDirection());
        assertEquals(2, out.getAssignments().size(),
                "两条 assignment 要能分开数 —— 拼成一段文本就分不出「两条」与「一条里的两个 to」");
        assertTrue(out.getAssignments().get(0).contains("x"));
        assertTrue(out.getAssignments().get(1).contains("p"));

        // 两条关联都写在 t1 里，所以按宿主查要得回两条；按流程级查要得回零
        assertEquals(2, definition.dataAssociationsOf("t1").size(),
                "同一个活动上可以有多条数据关联（多输入 / 多输出），按宿主查不能只给一条");
        assertTrue(definition.processLevelDataAssociations().isEmpty());
    }

    @Test
    @DisplayName("5_ 写在 process 上的数据关联 ownerId 为空且被单独归类")
    void parsesProcessLevelAssociation() {
        WfDefinition definition = parse(HEAD
                + "<process id=\"p1\">"
                + "  <startEvent id=\"s\"/>"
                + "  <endEvent id=\"e\"/>"
                + "  <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"e\"/>"
                + "  <dataObject id=\"do1\"/>"
                + "  <dataObjectReference id=\"dor1\" dataObjectRef=\"do1\"/>"
                + "  <dataOutputAssociation id=\"doaP\" sourceRef=\"do1\" targetRef=\"dor1\"/>"
                + "</process>" + TAIL);

        WfDataAssociation association = association(definition, "doaP");
        assertNotNull(association);
        assertNull(association.getOwnerId(), "流程级数据关联不属于任何活动");
        assertEquals(1, definition.processLevelDataAssociations().size());
        assertTrue(definition.dataAssociationsOf("s").isEmpty(),
                "流程级的关联不该出现在任何活动的名下");
    }

    // ==================== 部署期校验 ====================

    @Test
    @DisplayName("6_ 四类声明齐、端点齐的模型零 ERROR")
    void acceptsWellFormedModel() {
        WfDefinition definition = parse(HEAD
                + "<process id=\"p1\">"
                + "  <startEvent id=\"s\"/>"
                + SERVICE_OPEN
                + "    <ioSpecification>"
                + "      <dataInput id=\"din1\" dataObjectRef=\"do1\"/>"
                + "      <dataOutput id=\"dout1\" dataObjectRef=\"do2\"/>"
                + "    </ioSpecification>"
                + "    <dataInputAssociation id=\"dia1\" sourceRef=\"dor1\" targetRef=\"din1\"/>"
                + "    <dataOutputAssociation id=\"doa1\" sourceRef=\"do2\" targetRef=\"dout1\"/>"
                + "  </serviceTask>"
                + "  <endEvent id=\"e\"/>"
                + "  <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t1\"/>"
                + "  <sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"e\"/>"
                + "  <dataObject id=\"do1\"/>"
                + "  <dataObject id=\"do2\"/>"
                + "  <dataObjectReference id=\"dor1\" dataObjectRef=\"do1\"/>"
                + "  <dataStore id=\"ds1\" name=\"库\" capacity=\"10\"/>"
                + "</process>" + TAIL);

        assertEquals("[]", errorsOf(definition).toString(), "端点齐全且类型正确时不该有 ERROR");
    }

    @Test
    @DisplayName("7_ 关联端点悬空必须报 ERROR —— 这是改动前完全静默的那一类")
    void reportsDanglingAssociationEnd() {
        WfDefinition definition = parse(HEAD
                + "<process id=\"p1\">"
                + "  <startEvent id=\"s\"/>"
                + SERVICE_SELF_CLOSING
                + "  <endEvent id=\"e\"/>"
                + "  <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t1\"/>"
                + "  <sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"e\"/>"
                + "  <dataObject id=\"do1\"/>"
                + "  <dataOutputAssociation id=\"doa1\" sourceRef=\"do1\" targetRef=\"不存在\"/>"
                + "</process>" + TAIL);

        List<String> errors = errorsOf(definition);
        assertEquals(1, errors.size(), "应恰好报一条，实际: " + errors);
        String message = errors.get(0);
        assertTrue(message.contains("doa1"), "错在哪条关联上要点名: " + message);
        assertTrue(message.contains("不存在"), "哪个名字解析不到要写出来: " + message);
        // 报错里要带上「本定义实际声明了什么」，否则作者得自己回去数
        assertTrue(message.contains("do1"), "错消息应列出已声明的数据: " + message);
    }

    @Test
    @DisplayName("8_ dataObjectReference 的 dataObjectRef 悬空 / 缺失都报 ERROR")
    void reportsDanglingDataObjectRef() {
        WfDefinition dangling = parse(HEAD
                + "<process id=\"p1\">"
                + "  <startEvent id=\"s\"/><endEvent id=\"e\"/>"
                + "  <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"e\"/>"
                + "  <dataObjectReference id=\"dor1\" dataObjectRef=\"没有这个\"/>"
                + "</process>" + TAIL);
        List<String> errors = errorsOf(dangling);
        assertEquals(1, errors.size(), "实际: " + errors);
        assertTrue(errors.get(0).contains("dataObjectRef"));

        WfDefinition missing = parse(HEAD
                + "<process id=\"p1\">"
                + "  <startEvent id=\"s\"/><endEvent id=\"e\"/>"
                + "  <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"e\"/>"
                + "  <dataObjectReference id=\"dor1\"/>"
                + "</process>" + TAIL);
        List<String> missingErrors = errorsOf(missing);
        assertEquals(1, missingErrors.size(), "实际: " + missingErrors);
        assertTrue(missingErrors.get(0).contains("缺少 dataObjectRef"));
    }

    @Test
    @DisplayName("9_ 端点类型写反报 ERROR（input 的 target 必须落引用、output 的 source 必须是 dataObject）")
    void reportsWrongEndpointType() {
        WfDefinition input = parse(HEAD
                + "<process id=\"p1\">"
                + "  <startEvent id=\"s\"/>"
                + SERVICE_SELF_CLOSING
                + "  <endEvent id=\"e\"/>"
                + "  <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t1\"/>"
                + "  <sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"e\"/>"
                + "  <dataObject id=\"do1\"/>"
                + "  <dataInputAssociation id=\"dia1\" sourceRef=\"do1\" targetRef=\"do1\"/>"
                + "</process>" + TAIL);
        List<String> errors = errorsOf(input);
        assertEquals(1, errors.size(), "实际: " + errors);
        assertTrue(errors.get(0).contains("targetRef"),
                "要说清是 targetRef 这一端类型不对: " + errors.get(0));

        WfDefinition output = parse(HEAD
                + "<process id=\"p1\">"
                + "  <startEvent id=\"s\"/>"
                + SERVICE_SELF_CLOSING
                + "  <endEvent id=\"e\"/>"
                + "  <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t1\"/>"
                + "  <sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"e\"/>"
                + "  <dataObject id=\"do1\"/>"
                + "  <dataObjectReference id=\"dor1\" dataObjectRef=\"do1\"/>"
                + "  <dataOutputAssociation id=\"doa1\" sourceRef=\"dor1\" targetRef=\"dor1\"/>"
                + "</process>" + TAIL);
        List<String> outputErrors = errorsOf(output);
        assertEquals(1, outputErrors.size(), "实际: " + outputErrors);
        assertTrue(outputErrors.get(0).contains("sourceRef"),
                "要说清是 sourceRef 这一端类型不对: " + outputErrors.get(0));
    }

    @Test
    @DisplayName("10_ 关联缺端点报 ERROR（少一端丢掉不会有任何兜底消息）")
    void reportsMissingEndpoint() {
        WfDefinition definition = parse(HEAD
                + "<process id=\"p1\">"
                + "  <startEvent id=\"s\"/>"
                + SERVICE_SELF_CLOSING
                + "  <endEvent id=\"e\"/>"
                + "  <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t1\"/>"
                + "  <sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"e\"/>"
                + "  <dataObject id=\"do1\"/>"
                + "  <dataOutputAssociation id=\"doa1\" targetRef=\"do1\"/>"
                + "</process>" + TAIL);

        List<String> errors = errorsOf(definition);
        assertEquals(1, errors.size(), "实际: " + errors);
        assertTrue(errors.get(0).contains("缺少 sourceRef"), "实际: " + errors);
    }

    @Test
    @DisplayName("11_ 数据声明 id 与流程节点撞车报 ERROR（BPMN 要求 id 文档级唯一）")
    void reportsIdCollisionWithNode() {
        WfDefinition definition = parse(HEAD
                + "<process id=\"p1\">"
                + "  <startEvent id=\"s\"/>"
                + "  <userTask id=\"t1\"/>"
                + "  <endEvent id=\"e\"/>"
                + "  <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t1\"/>"
                + "  <sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"e\"/>"
                + "  <dataObject id=\"t1\"/>"
                + "  <dataStore id=\"s\"/>"
                + "</process>" + TAIL);

        List<String> errors = errorsOf(definition);
        assertEquals(2, errors.size(), "dataObject 与 dataStore 各撞一次，实际: " + errors);
        assertTrue(errors.toString().contains("重名"), "实际: " + errors);
    }

    @Test
    @DisplayName("12_ 多个问题一次报完（部署期要能一次改完，不是改一个提交一次）")
    void reportsAllIssuesAtOnce() {
        WfDefinition definition = parse(HEAD
                + "<process id=\"p1\">"
                + "  <startEvent id=\"s\"/>"
                + SERVICE_SELF_CLOSING
                + "  <endEvent id=\"e\"/>"
                + "  <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t1\"/>"
                + "  <sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"e\"/>"
                + "  <dataObjectReference id=\"dor1\" dataObjectRef=\"没有1\"/>"
                + "  <dataObjectReference id=\"dor2\" dataObjectRef=\"没有2\"/>"
                + "  <dataOutputAssociation id=\"doa1\" sourceRef=\"也没有\" targetRef=\"dor1\"/>"
                + "</process>" + TAIL);

        List<String> errors = errorsOf(definition);
        assertEquals(3, errors.size(), "三个独立问题应一次报齐，实际: " + errors);
    }

    // ==================== 解析期硬错 ====================

    @Test
    @DisplayName("13_ 数据声明 id 互相重复在解析期就抛（端点解析只能任选一个，没有理由可讲）")
    void throwsOnDuplicateDataId() {
        WfDefinitionException error = assertThrows(WfDefinitionException.class, () -> parse(HEAD
                + "<process id=\"p1\">"
                + "  <startEvent id=\"s\"/><endEvent id=\"e\"/>"
                + "  <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"e\"/>"
                + "  <dataObject id=\"dup\"/>"
                + "  <dataStore id=\"dup\"/>"
                + "</process>" + TAIL));
        assertTrue(error.getMessage().contains("dup"), error.getMessage());
    }

    @Test
    @DisplayName("14_ capacity 写错当场抛（纯声明元素，写错就该说，不能悄悄丢成「没配容量」）")
    void throwsOnBadCapacity() {
        WfDefinitionException error = assertThrows(WfDefinitionException.class, () -> parse(HEAD
                + "<process id=\"p1\">"
                + "  <startEvent id=\"s\"/><endEvent id=\"e\"/>"
                + "  <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"e\"/>"
                + "  <dataStore id=\"ds1\" capacity=\"很多\"/>"
                + "</process>" + TAIL));
        assertTrue(error.getMessage().contains("capacity"), error.getMessage());
        assertTrue(error.getMessage().contains("很多"), "错消息要带出作者写的原文: " + error.getMessage());
    }

    // ==================== 持久化往返 ====================

    @Test
    @DisplayName("15_ 编解码往返后四类声明与字段全在（只落一半的症状是「重启前后表现不同」）")
    void survivesCodecRoundTrip() {
        WfDefinition original = parse(HEAD
                + "<process id=\"p1\">"
                + "  <startEvent id=\"s\"/>"
                // 特意塞一个嵌在 subProcess 里的 dataObject：作用域是解析出来的，
                // 而**只跑 PROCESS 的数据时这一项恒为同一个值**，往返判据会变成一条假断言
                + "  <subProcess id=\"sub\">"
                + "    <startEvent id=\"s2\"/><endEvent id=\"e2\"/>"
                + "    <sequenceFlow id=\"f8\" sourceRef=\"s2\" targetRef=\"e2\"/>"
                + "    <dataObject id=\"doStage\" name=\"阶段数据\"/>"
                + "  </subProcess>"
                + SERVICE_OPEN
                + "    <ioSpecification>"
                + "      <dataInput id=\"din1\" name=\"订单\" dataObjectRef=\"do1\"/>"
                + "      <dataOutput id=\"dout1\" name=\"回执\" dataObjectRef=\"do2\"/>"
                + "    </ioSpecification>"
                + "    <dataInputAssociation id=\"dia1\" sourceRef=\"dor1\" targetRef=\"din1\">"
                + "      <transformation>${a}</transformation>"
                + "    </dataInputAssociation>"
                + "    <dataOutputAssociation id=\"doa1\" sourceRef=\"do2\" targetRef=\"dout1\">"
                + "      <assignment>from <to>a</to> to <to>b</to></assignment>"
                + "    </dataOutputAssociation>"
                + "  </serviceTask>"
                + "  <endEvent id=\"e\"/>"
                + "  <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t1\"/>"
                + "  <sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"e\"/>"
                + "  <dataObject id=\"do1\" name=\"订单\" itemSubjectRef=\"tns:Order\"/>"
                + "  <dataObject id=\"do2\" name=\"回执\"/>"
                + "  <dataObjectReference id=\"dor1\" name=\"入参\" dataObjectRef=\"do1\"/>"
                + "  <dataStore id=\"ds1\" name=\"库\" capacity=\"50\" isUnlimited=\"true\"/>"
                + "</process>" + TAIL);

        WfDefinition restored = WfDefinitionCodec.decode(WfDefinitionCodec.encode(original));
        assertNotNull(restored);

        assertEquals(3, restored.getDataObjects().size(),
                "两个流程级的加一个阶段级的。实际: " + idsOfDataObjects(restored));
        WfDataObject do1 = restored.dataObject("do1");
        assertNotNull(do1, "do1 往返后没了");
        assertEquals("订单", do1.getName());
        assertEquals("tns:Order", do1.getItemSubjectRef());
        assertEquals(WfDataScope.PROCESS, do1.getScope());
        // 这一条才是 scope 字段的判据：只跑 PROCESS 的数据时它恒为同一个值，
        // 往返判据会看着绿，其实对「作用域有没有被存下来」零区分力
        assertEquals(WfDataScope.STAGE, restored.dataObject("doStage").getScope(),
                "**嵌在 subProcess 里的 dataObject 往返后必须仍是阶段级** —— "
                        + "存成 PROCESS 的话，读回来它就变成活到实例结束，"
                        + "而 XML 里它明明只活到那个子流程结束");

        assertEquals(3, restored.getDataObjectReferences().size());
        assertEquals(WfDataObjectReference.Kind.REFERENCE, restored.dataObjectReference("dor1").getKind());
        assertEquals(WfDataObjectReference.Kind.INPUT, restored.dataObjectReference("din1").getKind());
        assertEquals(WfDataObjectReference.Kind.OUTPUT, restored.dataObjectReference("dout1").getKind());

        assertEquals(1, restored.getDataStores().size());
        assertEquals(Integer.valueOf(50), restored.dataStore("ds1").getCapacity());
        assertTrue(restored.dataStore("ds1").isUnlimited());

        assertEquals(2, restored.getDataAssociations().size());
        WfDataAssociation in = association(restored, "dia1");
        assertNotNull(in, "数据关联往返后没了 —— 重启后部署期那道端点校验会全判悬空");
        assertEquals(WfDataDirection.INPUT, in.getDirection());
        assertEquals("t1", in.getOwnerId());
        assertEquals("${a}", in.getTransformation());
        WfDataAssociation out = association(restored, "doa1");
        assertNotNull(out);
        assertEquals(WfDataDirection.OUTPUT, out.getDirection());
        assertEquals(1, out.getAssignments().size());

        // 往返之后端点校验仍要给出同样的结论 —— 落库只改存储形态，不改语义
        assertEquals("[]", errorsOf(restored).toString());
    }

    @Test
    @DisplayName("16_ 存量定义（没有数据声明字段）读回来是空列表而不是崩")
    void readsLegacyGraphWithoutData() {
        WfDefinition definition = parse(skeleton("legacy"));
        WfDefinition restored = WfDefinitionCodec.decode(WfDefinitionCodec.encode(definition));
        assertNotNull(restored, "存量定义必须照常读得出来");
        assertTrue(restored.getDataObjects().isEmpty());
        assertTrue(restored.getDataObjectReferences().isEmpty());
        assertTrue(restored.getDataStores().isEmpty());
        assertTrue(restored.getDataAssociations().isEmpty());
        assertEquals(3, restored.getNodes().size());
    }

    // ==================== 回归 ====================

    @Test
    @DisplayName("17_ 不含任何数据声明的 BPMN 与改动前逐项一致（老模型零影响）")
    void plainModelUnchanged() {
        WfDefinition definition = parse(skeleton("plain"));
        assertTrue(definition.getDataObjects().isEmpty());
        assertTrue(definition.getDataObjectReferences().isEmpty());
        assertTrue(definition.getDataStores().isEmpty());
        assertTrue(definition.getDataAssociations().isEmpty());
        assertEquals(3, definition.getNodes().size());
        assertEquals(2, definition.getFlows().size());
        assertEquals(0, definition.getAssociations().size());
        assertEquals("[]", errorsOf(definition).toString());
    }
}
