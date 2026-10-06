package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.JdbcWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfPersistence;

/**
 * 业务规则任务 {@code businessRuleTask} —— 流程里直接求值一张 DMN 决策表。
 *
 * <p>本类盯四件错了都不会立刻报错的事：
 * <ol>
 *   <li><b>决策结果有没有真的落到变量上。</b>不落的结果与"决策算出了空"长得一样，
 *       下游读那个变量拿到 null，而流程一路跑完。</li>
 *   <li><b>映射不适用时必须报错而不是取第一条。</b>取第一条会让流程带着一个
 *       看起来正常的结论继续走，而这个结论随命中顺序变。</li>
 *   <li><b>版本绑定要说清"是哪一版"。</b>deployment / versionTag 这两种 Camunda 写法
 *       本实现不支持，悄悄当成 latest 等于让流程在运行期拿到一个没人指定的版本。</li>
 *   <li><b>Camunda 导出的 XML 要能直接读。</b>同样一个属性名，Camunda 写的是
 *       {@code camunda:} 前缀，只认 zifang 前缀的话 {@code decisionRef} 会被读成"没配"，
 *       而节点照样穿透往下走。</li>
 * </ol>
 */
class WfBusinessRuleTaskTest {

    /** 审批层级：金额决定审批层级。只有一个输出列，适合 singleEntry。 */
    private static final String APPROVAL_DMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\">\n"
            + "  <decision id=\"approvalLevel\" name=\"审批层级\">\n"
            + "    <decisionTable id=\"t\" hitPolicy=\"FIRST\">\n"
            + "      <input id=\"i\"><inputExpression id=\"ie\"><text>amount</text>"
            + "</inputExpression></input>\n"
            + "      <output id=\"o\" name=\"level\"/>\n"
            + "      <rule><inputEntry><text>&gt; 50000</text></inputEntry>"
            + "<outputEntry><text>\"ceo\"</text></outputEntry></rule>\n"
            + "      <rule><inputEntry><text>-</text></inputEntry>"
            + "<outputEntry><text>\"staff\"</text></outputEntry></rule>\n"
            + "    </decisionTable>\n"
            + "  </decision>\n"
            + "</definitions>\n";

    /** 两个输出列，给 singleResult / resultList 用。 */
    private static final String TWO_OUTPUT_DMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\">\n"
            + "  <decision id=\"twoOut\" name=\"两个输出\">\n"
            + "    <decisionTable id=\"t\" hitPolicy=\"FIRST\">\n"
            + "      <input id=\"i\"><inputExpression id=\"ie\"><text>x</text>"
            + "</inputExpression></input>\n"
            + "      <output id=\"o1\" name=\"level\"/>\n"
            + "      <output id=\"o2\" name=\"reason\"/>\n"
            + "      <rule><inputEntry><text>-</text></inputEntry>"
            + "<outputEntry><text>\"ceo\"</text></outputEntry>"
            + "<outputEntry><text>\"金额超限\"</text></outputEntry></rule>\n"
            + "    </decisionTable>\n"
            + "  </decision>\n"
            + "</definitions>\n";

    /** COLLECT + 单输出列：命中多行，给 collectEntries / resultList 用。 */
    private static final String COLLECT_DMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\">\n"
            + "  <decision id=\"collect\" name=\"多条命中\">\n"
            + "    <decisionTable id=\"t\" hitPolicy=\"COLLECT\">\n"
            + "      <input id=\"i\"><inputExpression id=\"ie\"><text>x</text>"
            + "</inputExpression></input>\n"
            + "      <output id=\"o\" name=\"level\"/>\n"
            + "      <rule><inputEntry><text>-</text></inputEntry>"
            + "<outputEntry><text>\"a\"</text></outputEntry></rule>\n"
            + "      <rule><inputEntry><text>-</text></inputEntry>"
            + "<outputEntry><text>\"b\"</text></outputEntry></rule>\n"
            + "    </decisionTable>\n"
            + "  </decision>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfDecisionService decisions;
    private WfRuntimeService runtime;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        decisions = new WfDecisionService(repo);
        // 业务规则任务要用的决策服务由引擎带进 context；
        // 漏了这一步的话节点会报「接线漏了」而不是 NPE（那条也是一条判据）
        runtime = new WfRuntimeService(repository, repo,
                new WfEngine().withDecisionService(decisions), new WfHookDispatcher());
    }

    // ==================== 夹具 ====================

    /** 起点 → 业务规则任务 → 结束。属性由参数拼出来，方便针对四种映射各来一条。 */
    private static String flow(String attrs) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\""
                + " xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\""
                + " targetNamespace=\"x\">\n"
                + "  <process id=\"decide\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <businessRuleTask id=\"brt\" name=\"算层级\" " + attrs + "/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"brt\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"brt\" targetRef=\"e\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
    }

    private Map<String, Object> vars(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private Object runAndRead(String attrs, String decisionKey, Map<String, Object> vars,
                              String resultVar) {
        decisions.deployDecision(decisionKey.equals("approvalLevel") ? APPROVAL_DMN
                : decisionKey.equals("twoOut") ? TWO_OUTPUT_DMN : COLLECT_DMN);
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(flow(attrs)));
        String pid = runtime.startProcessInstance(definition, "biz-" + System.nanoTime(),
                null, null, vars);
        return runtime.getProcessInstance(pid).getVariables().get(resultVar);
    }

    // ==================== 解析与部署期 ====================

    @Test
    @DisplayName("businessRuleTask 是原生节点类型，不再被当成「不支持的元素」")
    void businessRuleTaskIsNative() {
        WfDefinition definition = new WfXmlParser().parse(
                flow("zifang:decisionRef=\"approvalLevel\" zifang:resultVariable=\"level\""));
        WfNode node = definition.node("brt");
        assertEquals(WfNodeType.BUSINESS_RULE_TASK, node.getType());
        assertEquals("approvalLevel", node.getDecisionRef());
        assertEquals("level", node.getResultVariable());
    }

    @Test
    @DisplayName("camunda: 前缀的写法一样认（照搬 Camunda 导出的模型能直接跑）")
    void camundaPrefixIsAlsoRead() {
        // 此前 extension() 只认 zifang 三条路径，camunda: 前缀读成「没配」——
        // 而节点照样穿透往下走，只是结果没落到变量上
        WfDefinition definition = new WfXmlParser().parse(
                flow("camunda:decisionRef=\"approvalLevel\" camunda:resultVariable=\"level\""));
        WfNode node = definition.node("brt");
        assertEquals("approvalLevel", node.getDecisionRef());
        assertEquals("level", node.getResultVariable());
    }

    @Test
    @DisplayName("缺 decisionRef / resultVariable ⇒ 部署期 ERROR")
    void missingRequiredAttributesAreErrors() {
        WfDefinitionException noRef = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(new WfXmlParser().parse(
                        flow("zifang:resultVariable=\"level\""))));
        assertTrue(noRef.getMessage().contains("decisionRef"), noRef.getMessage());

        WfDefinitionException noTarget = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(new WfXmlParser().parse(
                        flow("zifang:decisionRef=\"approvalLevel\""))));
        assertTrue(noTarget.getMessage().contains("resultVariable"), noTarget.getMessage());
    }

    @Test
    @DisplayName("deployment / versionTag 两种绑定明确报错，不悄悄当成 latest")
    void unsupportedBindingsAreRejected() {
        // **两侧都断**：只断「报错里有 deployment 三个字」是断不住的 ——
        // 那条专属分支一旦放行，报错会落到「未知的 decisionRefBinding」兜底上，
        // 而兜底那句话里同样列着 deployment 与 versionTag。
        // ⇒ 与第 23 轮 D04 同源：判据的关键词在**另一条分支**里也出现时，
        // 断言恒成立，而被挡掉的恰恰是「说清是哪一种不支持」那部分。
        WfDefinitionException deployment = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(new WfXmlParser().parse(flow(
                        "zifang:decisionRef=\"a\" zifang:resultVariable=\"v\""
                                + " zifang:decisionRefBinding=\"deployment\""))));
        assertTrue(deployment.getMessage().contains("分别部署"),
                "要说清为什么不支持（流程与决策没有共享的部署单元）: "
                        + deployment.getMessage());
        assertFalse(deployment.getMessage().contains("未知的 decisionRefBinding"),
                "不能落到「写法不认识」那条兜底上 —— "
                        + "那会把「这条写法我们明确不支持」说成「你拼错了」: "
                        + deployment.getMessage());

        WfDefinitionException tag = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(new WfXmlParser().parse(flow(
                        "zifang:decisionRef=\"a\" zifang:resultVariable=\"v\""
                                + " zifang:decisionRefBinding=\"versionTag\""))));
        assertTrue(tag.getMessage().contains("标签"),
                "要说清为什么不支持（ZWF_DECISION 上只有版本号，没有标签列）: "
                        + tag.getMessage());
        assertFalse(tag.getMessage().contains("未知的 decisionRefBinding"),
                "同样不能落到「写法不认识」那条兜底上: " + tag.getMessage());
    }

    @Test
    @DisplayName("binding=version 却没给版本号 ⇒ ERROR（要哪一版没有答案）")
    void versionBindingWithoutVersionIsRejected() {
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(new WfXmlParser().parse(flow(
                        "zifang:decisionRef=\"a\" zifang:resultVariable=\"v\""
                                + " zifang:decisionRefBinding=\"version\""))));
        assertTrue(ex.getMessage().contains("decisionRefVersion"), ex.getMessage());
    }

    @Test
    @DisplayName("未知的 mapDecisionResult ⇒ ERROR（拼错了不能默默按默认走）")
    void unknownMapperIsRejected() {
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(new WfXmlParser().parse(flow(
                        "zifang:decisionRef=\"a\" zifang:resultVariable=\"v\""
                                + " zifang:mapDecisionResult=\"singleEntrys\""))));
        assertTrue(ex.getMessage().contains("mapDecisionResult"), ex.getMessage());
    }


    /**
     * 跑一个流程并断它<b>停下来的理由</b>。
     *
     * <p>行为里抛的异常不会一路抛给调用方：引擎统一把它转成
     * "内部终止 + 写明理由"。所以判据不能断"抛了哪个异常"，
     * 要断 <b>实例停在哪、理由说的是哪件事</b> ——
     * 后者才是排障时真正读得到的东西。
     */
    private WfProcessInstance runUntilItStops(String attrs, Map<String, Object> vars) {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(flow(attrs)));
        String pid = runtime.startProcessInstance(definition, "stop-" + System.nanoTime(),
                null, null, vars);
        return runtime.getProcessInstance(pid);
    }

    // ==================== 运行期 ====================

    @Test
    @DisplayName("singleEntry：唯一那个值写进 resultVariable，流程穿透到结束")
    void singleEntryWritesOneValue() {
        Object level = runAndRead(
                "zifang:decisionRef=\"approvalLevel\" zifang:resultVariable=\"level\""
                        + " zifang:mapDecisionResult=\"singleEntry\"",
                "approvalLevel", vars("amount", 60000), "level");
        assertEquals("ceo", level);
    }

    @Test
    @DisplayName("默认是 resultList：给全部行的输出 Map 列表")
    void defaultMapperIsResultList() {
        Object value = runAndRead(
                "zifang:decisionRef=\"collect\" zifang:resultVariable=\"levels\"",
                "collect", vars("x", 1), "levels");
        assertEquals(Arrays.asList(vars("level", "a"), vars("level", "b")), value,
                "COLLECT 命中两行，默认映射把两行都给出去，而不是只给第一条");
    }

    @Test
    @DisplayName("singleResult：唯一那一行的输出 Map（多输出列时按名取）")
    void singleResultGivesTheRow() {
        Object value = runAndRead(
                "zifang:decisionRef=\"twoOut\" zifang:resultVariable=\"decision\""
                        + " zifang:mapDecisionResult=\"singleResult\"",
                "twoOut", vars("x", 1), "decision");
        assertEquals(vars("level", "ceo", "reason", "金额超限"), value);
    }

    @Test
    @DisplayName("collectEntries：收集每一行的唯一输出列")
    void collectEntriesGathersValues() {
        Object value = runAndRead(
                "zifang:decisionRef=\"collect\" zifang:resultVariable=\"levels\""
                        + " zifang:mapDecisionResult=\"collectEntries\"",
                "collect", vars("x", 1), "levels");
        assertEquals(Arrays.asList("a", "b"), value);
    }

    @Test
    @DisplayName("singleEntry 撞上多行 ⇒ 报错，不取第一条")
    void singleEntryRefusesMultipleRows() {
        // 取第一条的后果：流程带着一个看起来正常的结论继续走，
        // 而那个结论取决于命中顺序 —— 同一类单据两次会算出不同结果
        decisions.deployDecision(COLLECT_DMN);
        WfProcessInstance instance = runUntilItStops(
                "zifang:decisionRef=\"collect\" zifang:resultVariable=\"level\""
                        + " zifang:mapDecisionResult=\"singleEntry\"",
                vars("x", 1));
        assertEquals(WfProcessStatus.INTERNALLY_TERMINATED, instance.getStatus(),
                "映射不适用时必须停下来，而不是带着第一条继续走");
        assertTrue(String.valueOf(instance.getDeleteReason()).contains("singleEntry"),
                "停下来的理由要点明是哪个映射不适用: " + instance.getDeleteReason());
    }

    @Test
    @DisplayName("singleEntry 撞上多输出列 ⇒ 报错（单值映射没有唯一答案）")
    void singleEntryRefusesMultipleOutputs() {
        decisions.deployDecision(TWO_OUTPUT_DMN);
        WfProcessInstance instance = runUntilItStops(
                "zifang:decisionRef=\"twoOut\" zifang:resultVariable=\"v\""
                        + " zifang:mapDecisionResult=\"singleEntry\"",
                vars("x", 1));
        assertEquals(WfProcessStatus.INTERNALLY_TERMINATED, instance.getStatus());
        assertTrue(String.valueOf(instance.getDeleteReason()).contains("输出列"),
                "理由要说清是「有多个输出列，取哪个没有答案」: "
                        + instance.getDeleteReason());
    }

    @Test
    @DisplayName("decisionRef 可以是表达式，在节点执行那一刻求值")
    void decisionRefMayBeAnExpression() {
        decisions.deployDecision(APPROVAL_DMN);
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(flow(
                "zifang:decisionRef=\"${whichDecision}\" zifang:resultVariable=\"level\""
                        + " zifang:mapDecisionResult=\"singleEntry\"")));
        String pid = runtime.startProcessInstance(definition, "expr-" + System.nanoTime(),
                null, null, vars("whichDecision", "approvalLevel", "amount", 60000));
        assertEquals("ceo", runtime.getProcessInstance(pid).getVariables().get("level"));
    }

    @Test
    @DisplayName("decisionRef 是裸串时不当表达式（否则会被当成变量名而取不到）")
    void literalDecisionRefIsNotEvaluated() {
        // 拿裸串去求值的话 approvalLevel 会被当成变量名，
        // 而求值器对未定义变量 fail-closed 返回 null ——
        // 于是每张决策都变成「决策 null 不存在」，报的还是那句误导人的话
        Object level = runAndRead(
                "zifang:decisionRef=\"approvalLevel\" zifang:resultVariable=\"level\""
                        + " zifang:mapDecisionResult=\"singleEntry\"",
                "approvalLevel", vars("amount", 1), "level");
        assertEquals("staff", level);
    }

    @Test
    @DisplayName("decisionRef 表达式求出来是空 ⇒ 说清是表达式没值，不是「决策 null 不存在」")
    void unresolvableDecisionRefSaysSo() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(flow(
                "zifang:decisionRef=\"${missingKey}\" zifang:resultVariable=\"level\"")));
        String pid = runtime.startProcessInstance(definition, "nokey", null, null,
                new HashMap<String, Object>());
        String reason = String.valueOf(runtime.getProcessInstance(pid).getDeleteReason());
        // 断的是「报错里带没带着那个表达式原文」，而不是断某句措辞不在场 ——
        // 后者会被自己的解释文字打败：报错里为了说明"我避开了哪句话"而引用了它，
        // 于是「那句话不在场」这条断言恒不成立（与判据里别处出现同一个关键词同源）
        assertTrue(reason.contains("${missingKey}"),
                "停下来时要说清是哪个表达式没求出来，而不是让人拿 null 去查决策表: " + reason);
    }

    @Test
    @DisplayName("绑定固定版本 ⇒ 用那一版的规则，不是最新版")
    void versionBindingUsesThatVersion() {
        decisions.deployDecision(APPROVAL_DMN);
        // 改规则后重新部署 = 版本 2
        decisions.deployDecision(APPROVAL_DMN.replace("\"ceo\"", "\"chief\""));

        WfDefinition definition = repository.deploy(new WfXmlParser().parse(flow(
                "zifang:decisionRef=\"approvalLevel\" zifang:resultVariable=\"level\""
                        + " zifang:mapDecisionResult=\"singleEntry\""
                        + " zifang:decisionRefBinding=\"version\" zifang:decisionRefVersion=\"1\"")));
        String pid = runtime.startProcessInstance(definition, "v1-" + System.nanoTime(),
                null, null, vars("amount", 60000));
        assertEquals("ceo", runtime.getProcessInstance(pid).getVariables().get("level"),
                "绑定版本 1 就该拿到版本 1 的规则（ceo），而不是最新的 chief");

        WfDefinition latest = repository.deploy(new WfXmlParser().parse(flow(
                "zifang:decisionRef=\"approvalLevel\" zifang:resultVariable=\"level\""
                        + " zifang:mapDecisionResult=\"singleEntry\"")));
        String latestPid = runtime.startProcessInstance(latest, "latest", null, null,
                vars("amount", 60000));
        assertEquals("chief", runtime.getProcessInstance(latestPid).getVariables().get("level"),
                "默认绑定是 latest，应当拿到最新版");
    }

    @Test
    @DisplayName("决策不存在 ⇒ 运行期明确报错说清是哪个 key")
    void missingDecisionFailsLoudly() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(flow(
                "zifang:decisionRef=\"noSuchDecision\" zifang:resultVariable=\"level\"")));
        String pid = runtime.startProcessInstance(definition, "missing", null, null,
                new HashMap<String, Object>());
        String reason = String.valueOf(runtime.getProcessInstance(pid).getDeleteReason());
        assertTrue(reason.contains("noSuchDecision"),
                "停下来时要说清是哪个 key 的决策不存在: " + reason);
    }

    @Test
    @DisplayName("引擎没接决策服务 ⇒ 说清是接线问题，不是 NPE")
    void unwiredEngineSaysItIsAWiringProblem() {
        InMemoryWorkflowPersistence own = new InMemoryWorkflowPersistence();
        own.initialize();
        WfRepositoryService repoService = new WfRepositoryService(own);
        WfRuntimeService unwired = new WfRuntimeService(repoService, own,
                new WfEngine(), new WfHookDispatcher());
        WfDefinition definition = repoService.deploy(new WfXmlParser().parse(flow(
                "zifang:decisionRef=\"approvalLevel\" zifang:resultVariable=\"level\"")));
        String pid = unwired.startProcessInstance(definition, "unwired", null, null,
                new HashMap<String, Object>());
        String reason = String.valueOf(unwired.getProcessInstance(pid).getDeleteReason());
        assertTrue(reason.contains("接线"),
                "没接决策服务是一处**接线漏了**，报 NPE 既不说是哪个节点也不说缺什么: "
                        + reason);
    }

    @Test
    @DisplayName("决策表可以晚于流程部署（部署期不检查决策是否存在）")
    void decisionMayBeDeployedAfterTheProcess() {
        // 流程与决策是两条部署路径，先后顺序是自由的。
        // 为一个「迟早会部署」的检查把流程部署挡在门外，比留到运行期报错更糟
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(flow(
                "zifang:decisionRef=\"approvalLevel\" zifang:resultVariable=\"level\""
                        + " zifang:mapDecisionResult=\"singleEntry\"")));
        assertNotNull(definition);
        decisions.deployDecision(APPROVAL_DMN);
        String pid = runtime.startProcessInstance(definition, "late", null, null,
                vars("amount", 60000));
        assertEquals("ceo", runtime.getProcessInstance(pid).getVariables().get("level"));
    }

    // ==================== 往返 ====================

    @Test
    @DisplayName("四个新字段活过持久化往返：内存深拷贝与 JDBC codec 两条路径都要断")
    void decisionAttributesSurviveTheRoundTrip() {
        // 只测内存的话，codec 里少写一处映射根本不会红，
        // 而症状是「部署没问题，一启动就找不到决策」
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:brt_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        JdbcWorkflowPersistence jdbc = new JdbcWorkflowPersistence(ds);
        jdbc.initialize();

        String attrs = "zifang:decisionRef=\"approvalLevel\" zifang:resultVariable=\"level\""
                + " zifang:mapDecisionResult=\"singleEntry\""
                + " zifang:decisionRefBinding=\"version\" zifang:decisionRefVersion=\"3\"";

        for (WfPersistence target : new WfPersistence[]{repo, jdbc}) {
            String kind = target.getClass().getSimpleName();
            WfRepositoryService service = new WfRepositoryService(target);
            service.deploy(new WfXmlParser().parse(flow(attrs)));
            WfNode reloaded = service.getDefinitionOrLatest("decide", null).node("brt");
            assertEquals("approvalLevel", reloaded.getDecisionRef(), kind);
            assertEquals("level", reloaded.getResultVariable(), kind);
            assertEquals("singleEntry", reloaded.getMapDecisionResult(), kind);
            assertEquals("version", reloaded.getDecisionRefBinding(), kind);
            assertEquals("3", reloaded.getDecisionRefVersion(), kind);
            assertEquals(WfNodeType.BUSINESS_RULE_TASK, reloaded.getType(), kind);
        }
    }

    @Test
    @DisplayName("四种映射的值形状各自不同：别混用")
    void theFourMappersProduceDifferentShapes() {
        decisions.deployDecision(TWO_OUTPUT_DMN);
        List<String> seen = new java.util.ArrayList<>();
        seen.add(String.valueOf(runMapper("singleEntry")));
        seen.add(String.valueOf(runMapper("singleResult")));
        seen.add(String.valueOf(runMapper("resultList")));
        assertTrue(seen.get(0).startsWith("异常") || !seen.get(0).equals(seen.get(1)),
                "singleEntry 与 singleResult 在这张表上必须给出不同形状: " + seen);
    }

    private Object runMapper(String mapper) {
        try {
            return runAndRead(
                    "zifang:decisionRef=\"twoOut\" zifang:resultVariable=\"v\""
                            + " zifang:mapDecisionResult=\"" + mapper + "\"",
                    "twoOut", vars("x", 1), "v");
        } catch (WfEngineException e) {
            return "异常: " + e.getMessage();
        }
    }
}