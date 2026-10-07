package com.zifang.z.wf.core.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.service.WfRepositoryService;

/**
 * 不支持的 BPMN 元素<b>不许静默退化</b>的回归测试。
 *
 * <p>背景：解析器为了让设计器导出的扩展类型不至于让整份定义解析失败，
 * 会把认不出的元素名退化成 {@link WfNodeType#TASK}。这在 {@code <task>} 上是合理的，
 * 但 {@code <transaction>}（事务子流程）退化过去就不是"少支持一个特性"，
 * 而是把"原子子流程"换成了"建个人工任务等人来点"——流程照跑、部署照过、
 * 作者与实际运行行为之间零提示。
 *
 * <p>本类原先拿 {@code <eventBasedGateway>} 当例子，那是因为它当时尚未实现。
 * 事件网关补上之后，改用仍在退化名单里的 {@code <transaction>}；
 * 另有 {@code eventBasedGatewayIsNowNative} 与 {@code intermediateThrowEventIsNowNative} 守着"已实现的元素不许再被当成退化节点"，
 * 免得有人日后把支持列表改回去时，这里也跟着悄悄失效。
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
    @DisplayName("adHocSubProcess 退化后必须留下原名，并被校验器判为 ERROR")
    void adHocSubProcessIsMarkedAndRejected() {
        // 样本原先是 transaction —— 第 38 轮把 transaction 实现成原生类型后
        // 换成了 adHocSubProcess。留着 transaction 当样本的话，
        // 这条判据会在元素**被正确支持**之后变红，
        // 而它要守的其实是「退化元素必须留名并被挡住」这条规则本身，
        // 与具体是哪个元素无关。
        WfDefinition definition = new WfXmlParser().parse(bpmnWith("adHocSubProcess"));
        WfNode node = nodeOf(definition, "x1");
        assertNotNull(node, "adHocSubProcess 应当被解析出来（解析期要宽松）");
        assertEquals(WfNodeType.TASK, node.getType(), "退化后落成人工任务");
        assertEquals("adHocSubProcess", node.unsupportedBpmnElement(),
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
        assertTrue(issue.getMessage().contains("adHocSubProcess"),
                "报错信息必须点名是哪个元素： " + issue.getMessage());
    }

    @Test
    @DisplayName("transaction 第 38 轮已原生支持，不再被当成退化节点")
    void transactionIsNowNative() {
        // 与 eventBasedGatewayIsNowNative 同理：守着「支持列表不许悄悄缩回去」。
        // transaction 此前是被挡掉的（第 36 轮立项调查确认过它硬依赖补偿，
        // 补偿在第 37 轮就位），所以在这之前一份真实的事务流程在本引擎里部署不了。
        WfDefinition definition = new WfXmlParser().parse(bpmnWith("transaction"));
        WfNode node = nodeOf(definition, "x1");
        assertEquals(WfNodeType.TRANSACTION, node.getType());
        assertNull(node.unsupportedBpmnElement(),
                "事务已是原生类型，不能再被当成退化节点拦下来");
    }

    @Test
    @DisplayName("eventBasedGateway 已原生支持：不再被当成退化节点")
    void eventBasedGatewayIsNowNative() {
        // 这条守着"支持列表不许悄悄缩回去"。写法错了的症状不是测试变红，
        // 而是一条能部署、能跑、但完全不是事件竞速的流程安静上线
        WfDefinition definition = new WfXmlParser().parse(bpmnWith("eventBasedGateway"));
        WfNode node = nodeOf(definition, "x1");
        assertEquals(WfNodeType.EVENT_BASED_GATEWAY, node.getType());
        assertNull(node.unsupportedBpmnElement(),
                "事件网关已经实现，不该再被标成退化节点");
    }

    @Test
    @DisplayName("事件网关写错时也不给替代建议——拿 exclusiveGateway 顶替是换了个更隐蔽的错")
    void eventBasedGatewayGetsNoMisleadingSubstitute() {
        // bpmnWith 的构图是 s1 -> x1 -> e1，出线指向的是 endEvent 而不是中间捕获事件。
        // 这时校验器要报"出线必须是 intermediateCatchEvent"，且不能顺嘴建议改用排他网关：
        // 排他网关是"条件选一条"，事件网关是"事件竞速"，换过去作者得到的是另一个流程
        WfDefinition definition = new WfXmlParser().parse(bpmnWith("eventBasedGateway"));
        String rendered = WfDefinitionValidator.render(
                new WfDefinitionValidator().validate(definition));
        assertTrue(rendered.contains("intermediateCatchEvent"),
                "应当报出线类型不对：" + rendered);
        assertTrue(!rendered.contains("exclusiveGateway"),
                "事件网关没有等价物，不应诱导改写成排他网关：" + rendered);
    }

    @Test
    @DisplayName("intermediateThrowEvent 第 18 轮已原生支持，不再被当成退化节点")
    void intermediateThrowEventIsNowNative() {
        // 这条与 eventBasedGatewayIsNowNative 同理：守着"支持列表不许悄悄缩回去"。
        // 它此前是被当成退化节点挡掉的（第 4 轮的结论）——
        // 也就是说一份真实的 Camunda 流程里出现 throwEvent 时，本引擎**部署不了**。
        // 写法错了的症状不是测试变红，而是一份合法的流程被无理由拒绝。
        WfDefinition definition = new WfXmlParser().parse(bpmnWith("intermediateThrowEvent"));
        assertEquals(WfNodeType.THROW_EVENT, nodeOf(definition, "x1").getType());
        assertNull(nodeOf(definition, "x1").unsupportedBpmnElement(),
                "抛事件已是原生类型，不能再被当成退化节点拦下来");
        // 但它没有事件引用时仍然报 ERROR —— 那是另一回事（抛不出东西），不是"不支持"
        String rendered = WfDefinitionValidator.render(
                new WfDefinitionValidator().validate(definition));
        assertTrue(rendered.contains("没有任何事件定义"),
                "原生但没配事件引用，与不支持是两回事，要分开说：" + rendered);
        // 断言的是「没有把它当成不支持的元素」，**不是**「rendered 里不许出现 sendTask」——
        // 本引擎在"没配事件引用"时本来就会建议改用 sendTask，那是另一条提示，
        // 用字符串包含去判会把自己写的提示当成失败。
        assertTrue(!rendered.contains("暂无等价节点") && !rendered.contains("本引擎不支持"),
                "已有原生实现，不该再按「不支持的元素」处理：" + rendered);
        assertNull(nodeOf(definition, "x1").unsupportedBpmnElement());
    }

    @Test
    @DisplayName("adHocSubProcess 仍给出替代建议 subProcess")
    void unsupportedSubProcessLikeStillSuggestsSubProcess() {
        // 退化名单原先是 {transaction, adHocSubProcess}；第 38 轮 transaction
        // 有了原生实现，名单只剩 adHocSubProcess。
        // 它与 subProcess 的等价关系成立（Camunda 把 ad-hoc 当普通 subProcess 处理），
        // 所以仍要给建议 —— 别把这条一起删掉。
        WfDefinition definition = new WfXmlParser().parse(bpmnWith("adHocSubProcess"));
        assertEquals("adHocSubProcess", nodeOf(definition, "x1").unsupportedBpmnElement(),
                "adHocSubProcess 仍是退化节点");
        String rendered = WfDefinitionValidator.render(
                new WfDefinitionValidator().validate(definition));
        assertTrue(rendered.contains("subProcess"),
                "adHocSubProcess 与 subProcess 等价，应给出替代建议：" + rendered);
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
                + "{\"id\":\"g1\",\"type\":\"adHocSubProcess\"},"
                + "{\"id\":\"e1\",\"type\":\"endEvent\"}],"
                + "\"flows\":[{\"from\":\"s1\",\"to\":\"g1\"},{\"from\":\"g1\",\"to\":\"e1\"}]}";
        WfDefinition definition = new WfJsonParser().parse(json);
        WfNode node = nodeOf(definition, "g1");
        assertNotNull(node);
        assertEquals("adHocSubProcess", node.unsupportedBpmnElement(),
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
        WfDefinition definition = new WfXmlParser().parse(bpmnWith("transaction"));
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> service.deploy(definition),
                "deploy 必须在校验 ERROR 时拒绝");
        assertTrue(ex.getMessage().contains("transaction"),
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
        public java.util.List<com.zifang.z.wf.core.model.WfJob> lockExternalTasks(
                String topic, String workerId, int maxTasks, java.util.Date staleBefore) {
            return null;
        }

        @Override
        public boolean deleteDefinition(String key, int version) {
            return false;
        }

        @Override
        public boolean setDefinitionSuspended(String key, int version, boolean suspended) {
            return false;
        }

        @Override
        public boolean setDefaultDefinition(String key, int version, boolean isDefault) {
            return false;
        }

        @Override
        public WfDefinition findDefaultDefinition() {
            return null;
        }

        @Override
        public List<WfDefinition> findDefinitions(String keyLike, String nameLike, Boolean suspended) {
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
        public List<com.zifang.z.wf.core.model.WfComment> queryVariableAudits(
                com.zifang.z.wf.core.persistence.WfVariableAuditQuery q) {
            return null;
        }

        @Override
        public long countVariableAudits(
                com.zifang.z.wf.core.persistence.WfVariableAuditQuery q) {
            return 0L;
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

        // 补偿登记（第 37 轮）。这个 stub 只关心「退化元素被部署期挡住」，
        // 补偿用不到 —— 返回空而不是抛异常，免得它挡着这条用例真正要测的东西。
        @Override
        public void saveCompensation(com.zifang.z.wf.core.model.WfCompensationEntry entry) {
        }

        @Override
        public List<com.zifang.z.wf.core.model.WfCompensationEntry> findCompensations(String id) {
            return java.util.Collections.emptyList();
        }

        @Override
        public int markCompensated(String id, java.util.Date when) {
            return 0;
        }

        @Override
        public int deleteCompensationsByProcessInstance(String processInstanceId) {
            return 0;
        }

        // 批次（第 39 轮）。理由同上：这个 stub 只关心「退化元素被部署期挡住」，
        // 批次用不到 —— 抛异常会把它挡在真正要测的东西前面
        @Override
        public void saveBatch(com.zifang.z.wf.core.model.WfBatch batch) {
        }

        @Override
        public com.zifang.z.wf.core.model.WfBatch findBatch(String id) {
            return null;
        }

        @Override
        public boolean deleteBatch(String id) {
            return false;
        }

        @Override
        public List<com.zifang.z.wf.core.model.WfBatch> queryBatches(
                com.zifang.z.wf.core.persistence.WfBatchQuery query) {
            return java.util.Collections.emptyList();
        }

        @Override
        public int countBatches(com.zifang.z.wf.core.persistence.WfBatchQuery query) {
            return 0;
        }

        @Override
        public void saveBatchElement(com.zifang.z.wf.core.model.WfBatchElement element) {
        }

        @Override
        public List<com.zifang.z.wf.core.model.WfBatchElement> findBatchElements(String batchId) {
            return java.util.Collections.emptyList();
        }

        @Override
        public List<com.zifang.z.wf.core.model.WfBatchElement> findFailedBatchElements(String batchId) {
            return java.util.Collections.emptyList();
        }

        @Override
        public int countBatchElements(String batchId) {
            return 0;
        }

        @Override
        public int deleteBatchElements(String batchId) {
            return 0;
        }

        @Override
        public int deleteJobsByExecution(String executionId) {
            return 0;
        }

        // 筛选器是这个 stub 用不到的一组方法（它只测解析期的拒绝行为），
        // 但 WfPersistence 是接口，少一个实现编译就过不去。
        // 返回"空"而不是抛异常：stub 的语义是"没实现"，
        // 而真的被调到时抛出的 UnsupportedOperationException 会指向这个测试类，
        // 让人以为是解析期的行为 —— 实际是某个 stub 没跟上接口
        @Override
        public void saveFilter(com.zifang.z.wf.core.model.WfFilter filter) {
        }

        @Override
        public com.zifang.z.wf.core.model.WfFilter findFilter(String id) {
            return null;
        }

        @Override
        public boolean deleteFilter(String id) {
            return false;
        }

        @Override
        public java.util.List<com.zifang.z.wf.core.model.WfFilter> queryFilters(
                com.zifang.z.wf.core.persistence.WfFilterQuery query) {
            return new java.util.ArrayList<>();
        }

        @Override
        public int countFilters(com.zifang.z.wf.core.persistence.WfFilterQuery query) {
            return 0;
        }

        // ---- 决策（DMN）----
        // 这个桩只服务 deploy 的校验路径，决策一行都不会碰到。
        // 留着这几个方法不是为了"让编译过"，而是因为桩里每多一个
        // throw new AssertionError("不该走到")，就多一处「若真走到这里」的说明 ——
        // 全返回 null 的话，将来某个路径真的调到了也只是拿到 null，无从判断。
        @Override
        public void saveDecision(com.zifang.z.wf.core.definition.dmn.WfDmnDecision decision) {
            throw new AssertionError("校验没过就不该走到落库");
        }

        @Override
        public com.zifang.z.wf.core.definition.dmn.WfDmnDecision findDecision(String key, int version) {
            return null;
        }

        @Override
        public com.zifang.z.wf.core.definition.dmn.WfDmnDecision findLatestDecision(String key) {
            return null;
        }

        @Override
        public List<com.zifang.z.wf.core.definition.dmn.WfDmnDecision> findDecisionVersions(String key) {
            return new java.util.ArrayList<>();
        }

        @Override
        public boolean deleteDecision(String key, int version) {
            return false;
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
        public List<String> getTableNames() {
            // 自省接口在这条用例的路径上不该被碰到。真要碰到，
            // 说明"部署被校验拦住"这条约束已经名存实亡了
            throw new AssertionError("校验没过就不该走到存储自省");
        }

        @Override
        public long getTableCount(String name) {
            throw new AssertionError("校验没过就不该走到存储自省");
        }

        @Override
        public int setProcessInstanceName(String processInstanceId, String name) {
            throw new AssertionError("校验没过就不该走到改实例名");
        }

        @Override
        public List<WfExecution> queryExecutions(
                com.zifang.z.wf.core.persistence.WfExecutionQuery query) {
            throw new AssertionError("校验没过就不该走到令牌查询");
        }

        @Override
        public void clear() {
        }
    }
}
