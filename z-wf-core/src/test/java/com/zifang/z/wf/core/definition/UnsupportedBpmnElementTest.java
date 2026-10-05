package com.zifang.z.wf.core.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.service.WfRepositoryService;

/**
 * 不支持的 BPMN 元素<b>不许静默退化</b>的回归测试。
 *
 * <p>背景：解析器为了让设计器导出的扩展类型不至于让整份定义解析失败，
 * 会把认不出的元素名退化成 {@link WfNodeType#TASK}。这在 {@code <task>} 上是合理的，
 * 但 {@code <eventBasedGateway>} 退化过去就不是"少支持一个特性"，
 * 而是把"多路事件竞速"换成了"建个人工任务等人来点"——流程照跑、部署照过、
 * 作者与实际运行行为之间零提示。
 *
 * <p>所以契约是：<b>解析期宽松，部署期严格</b>。解析仍要成功（能读进来才能给出有用的诊断），
 * 但节点会带上 {@link WfNode#PROPERTY_UNSUPPORTED_BPMN_ELEMENT} 标记，
 * 校验器据此报 ERROR，{@code WfRepositoryService#deploy} 据此拒绝部署。
 */
class UnsupportedBpmnElementTest {

    private static String bpmnWith(String elementTag) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"x\">\n"
                + "  <process id=\"p1\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                + "    <" + elementTag + " id=\"x1\"/>\n"
                + "    <endEvent id=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"x1\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"x1\" targetRef=\"e1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
    }

    private static WfNode nodeOf(WfDefinition definition, String id) {
        for (WfNode node : definition.getNodes()) {
            if (id.equals(node.getId())) {
                return node;
            }
        }
        return null;
    }

    @Test
    @DisplayName("eventBasedGateway 退化后必须留下原名，并被校验器判为 ERROR")
    void eventBasedGatewayIsMarkedAndRejected() {
        WfDefinition definition = new WfXmlParser().parse(bpmnWith("eventBasedGateway"));
        WfNode gateway = nodeOf(definition, "x1");
        assertNotNull(gateway, "eventBasedGateway 应当被解析出来（解析期要宽松）");
        assertEquals(WfNodeType.TASK, gateway.getType(), "退化后落成人工任务");
        assertEquals("eventBasedGateway", gateway.unsupportedBpmnElement(),
                "必须记录原始元素名，否则 type=TASK 无法与真正的 task 区分");

        List<WfValidationIssue> issues = new WfDefinitionValidator().validate(definition);
        assertTrue(WfDefinitionValidator.hasError(issues),
                "含未支持元素的定义必须判为 ERROR，否则部署会静默放行："
                        + WfDefinitionValidator.render(issues));

        WfValidationIssue issue = issues.stream()
                .filter(i -> "x1".equals(i.getNodeId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未针对 x1 报出问题: " + issues));
        assertEquals(WfValidationIssue.Severity.ERROR, issue.getSeverity());
        assertTrue(issue.getMessage().contains("eventBasedGateway"),
                "报错信息必须点名是哪个元素： " + issue.getMessage());
    }

    @Test
    @DisplayName("eventBasedGateway 不给替代建议——拿 exclusiveGateway 顶替是换了个更隐蔽的错")
    void eventBasedGatewayGetsNoMisleadingSubstitute() {
        WfDefinition definition = new WfXmlParser().parse(bpmnWith("eventBasedGateway"));
        String rendered = WfDefinitionValidator.render(
                new WfDefinitionValidator().validate(definition));
        assertTrue(!rendered.contains("exclusiveGateway"),
                "事件网关没有等价物，不应诱导改写成排他网关：" + rendered);
    }

    @Test
    @DisplayName("intermediateThrowEvent 给出等价替代建议 sendTask")
    void intermediateThrowEventSuggestsSendTask() {
        WfDefinition definition = new WfXmlParser().parse(bpmnWith("intermediateThrowEvent"));
        assertEquals("intermediateThrowEvent", nodeOf(definition, "x1").unsupportedBpmnElement());
        String rendered = WfDefinitionValidator.render(
                new WfDefinitionValidator().validate(definition));
        assertTrue(rendered.contains("sendTask"),
                "抛出型中间事件与 sendTask 确实等价，应给出替代建议：" + rendered);
    }

    @Test
    @DisplayName("原生 task 元素不得被误标为退化节点")
    void nativeTaskIsNotFlagged() {
        WfDefinition definition = new WfXmlParser().parse(bpmnWith("task"));
        WfNode node = nodeOf(definition, "x1");
        assertEquals(WfNodeType.TASK, node.getType());
        assertNull(node.unsupportedBpmnElement(),
                "<task> 是原生类型，不能被当成退化节点拦下来");
        assertTrue(!WfDefinitionValidator.hasError(
                new WfDefinitionValidator().validate(definition)));
    }

    @Test
    @DisplayName("显式 zifang:type 覆盖视为作者拍板，不拦")
    void explicitOverrideIsNotBlocked() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"p1\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                + "    <transaction id=\"x1\" zifang:type=\"userTask\"/>\n"
                + "    <endEvent id=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"x1\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"x1\" targetRef=\"e1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        WfDefinition definition = new WfXmlParser().parse(xml);
        WfNode node = nodeOf(definition, "x1");
        assertEquals(WfNodeType.USER_TASK, node.getType(), "显式覆盖应当生效");
        assertNull(node.unsupportedBpmnElement(),
                "作者已经用 zifang:type 拍板，引擎不该再拦一次");
    }

    @Test
    @DisplayName("JSON 入口必须与 BPMN 入口同等严格——换格式不能绕过去")
    void jsonEntryIsEquallyStrict() {
        String json = "{\"key\":\"p1\",\"startEventId\":\"s1\",\"nodes\":["
                + "{\"id\":\"s1\",\"type\":\"startEvent\"},"
                + "{\"id\":\"g1\",\"type\":\"eventBasedGateway\"},"
                + "{\"id\":\"e1\",\"type\":\"endEvent\"}],"
                + "\"flows\":[{\"from\":\"s1\",\"to\":\"g1\"},{\"from\":\"g1\",\"to\":\"e1\"}]}";
        WfDefinition definition = new WfJsonParser().parse(json);
        WfNode gateway = nodeOf(definition, "g1");
        assertNotNull(gateway);
        assertEquals("eventBasedGateway", gateway.unsupportedBpmnElement(),
                "JSON 定义走的是另一个解析器，必须打同样的标记");
        assertTrue(WfDefinitionValidator.hasError(
                        new WfDefinitionValidator().validate(definition)),
                "JSON 入口也必须挡住部署");
    }

    @Test
    @DisplayName("部署闸门确实拦得住：含未支持元素的定义不能 deploy 成功")
    void deployRejectsUnsupportedElement() {
        InMemoryRepo repo = new InMemoryRepo();
        WfRepositoryService service = new WfRepositoryService(repo);
        WfDefinition definition = new WfXmlParser().parse(bpmnWith("eventBasedGateway"));
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> service.deploy(definition),
                "deploy 必须在校验 ERROR 时拒绝");
        assertTrue(ex.getMessage().contains("eventBasedGateway"),
                "异常信息要能定位到具体元素: " + ex.getMessage());
    }

    /** 只实现 deploy 路径用到的那几个方法的极简持久化桩。 */
    private static final class InMemoryRepo
            implements com.zifang.z.wf.core.persistence.WfPersistence {

        @Override
        public void saveDefinition(WfDefinition d) {
            throw new AssertionError("校验没过就不该走到落库");
        }

        @Override
        public WfDefinition findLatestDefinition(String key) {
            return null;
        }

        @Override
        public WfDefinition findDefinition(String key, int version) {
            return null;
        }

        @Override
        public List<WfDefinition> findDefinitionVersions(String key) {
            return null;
        }

        @Override
        public List<WfDefinition> findAllDefinitions() {
            return null;
        }

        @Override
        public List<WfDefinition> findDefinitionsByCategory(String category) {
            return null;
        }

        @Override
        public void saveProcessInstance(com.zifang.z.wf.core.model.WfProcessInstance i) {
        }

        @Override
        public com.zifang.z.wf.core.model.WfProcessInstance findProcessInstance(String id) {
            return null;
        }

        @Override
        public List<com.zifang.z.wf.core.model.WfProcessInstance> findProcessInstancesByBusinessKey(String b) {
            return null;
        }

        @Override
        public List<com.zifang.z.wf.core.model.WfProcessInstance> queryProcessInstances(
                com.zifang.z.wf.core.persistence.WfProcessInstanceQuery q) {
            return null;
        }

        @Override
        public long countProcessInstances(
                com.zifang.z.wf.core.persistence.WfProcessInstanceQuery q) {
            return 0L;
        }

        @Override
        public void saveExecution(com.zifang.z.wf.core.model.WfExecution e) {
        }

        @Override
        public void deleteExecution(String id) {
        }

        @Override
        public com.zifang.z.wf.core.model.WfExecution findExecution(String id) {
            return null;
        }

        @Override
        public List<com.zifang.z.wf.core.model.WfExecution> findExecutionsByProcessInstance(String p) {
            return null;
        }

        @Override
        public void saveTask(com.zifang.z.wf.core.model.WfTask t) {
        }

        @Override
        public void deleteTask(String id) {
        }

        @Override
        public com.zifang.z.wf.core.model.WfTask findTask(String id) {
            return null;
        }

        @Override
        public List<com.zifang.z.wf.core.model.WfTask> queryTasks(
                com.zifang.z.wf.core.persistence.WfTaskQuery q) {
            return null;
        }

        @Override
        public long countTasks(com.zifang.z.wf.core.persistence.WfTaskQuery q) {
            return 0L;
        }

        @Override
        public void saveJob(com.zifang.z.wf.core.model.WfJob job) {
        }

        @Override
        public void deleteJob(String id) {
        }

        @Override
        public com.zifang.z.wf.core.model.WfJob findJob(String id) {
            return null;
        }

        @Override
        public java.util.List<com.zifang.z.wf.core.model.WfJob> queryJobs(
                com.zifang.z.wf.core.persistence.WfJobQuery q) {
            return null;
        }

        @Override
        public long countJobs(com.zifang.z.wf.core.persistence.WfJobQuery q) {
            return 0L;
        }

        @Override
        public int deleteJobsByProcessInstance(String processInstanceId) {
            return 0;
        }

        @Override
        public int deleteJobsByExecution(String executionId) {
            return 0;
        }

        @Override
        public void saveActivityInstance(com.zifang.z.wf.core.model.WfActivityInstance a) {
        }

        @Override
        public List<com.zifang.z.wf.core.model.WfActivityInstance> findActivityInstances(String p) {
            return null;
        }

        @Override
        public List<com.zifang.z.wf.core.model.WfActivityInstance> queryActivityInstances(
                com.zifang.z.wf.core.persistence.WfHistoricActivityInstanceQuery q) {
            return null;
        }

        @Override
        public long countActivityInstances(
                com.zifang.z.wf.core.persistence.WfHistoricActivityInstanceQuery q) {
            return 0L;
        }

        @Override
        public int deleteHistoryBefore(java.util.Date before) {
            return 0;
        }

        @Override
        public void saveComment(com.zifang.z.wf.core.model.WfComment c) {
        }

        @Override
        public List<com.zifang.z.wf.core.model.WfComment> findComments(String p) {
            return null;
        }

        @Override
        public void initialize() {
        }

        @Override
        public void clear() {
        }
    }
}
