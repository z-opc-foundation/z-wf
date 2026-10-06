package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfDefinitionCodec;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 网关的<b>汇合</b>语义：到达的 token 是被合并成一条，还是各自往下走。
 *
 * <p>本轮修的是一处<b>与 Camunda 不一致且没有任何报错</b>的行为：
 * {@code isJoin} 先前只看「多条入线 + 多个来源」，<b>压根不看节点类型</b>，
 * 于是排他网关也把并行 token 合并了。症状是「法务审」与「财务审」并行结束后
 * 经排他网关进入下一步，本引擎只建**一条**待办，而 Camunda 建两条 ——
 * 从 Camunda 导入的模型会静默少掉一条分支，流程图上看不出任何提示。
 *
 * <p>三种网关的汇合语义本来就不同，判据要把三边都钉住：
 * <ol>
 *   <li><b>排他网关恒为穿透</b>（Camunda 原文：joining gateway has a pass-through
 *       semantic；社区版对同一模型的回答是 "the tokens will <b>not join</b>"）。</li>
 *   <li><b>并行与包容恒为合并</b>，不该被这次改动带跑。</li>
 *   <li><b>复杂网关两边都说得通</b>，所以做成可配：默认 {@code joining}
 *       （保持既有行为，改默认等于改已上线模型），显式 {@code competing} 才是穿透。
 *       Camunda 把复杂网关的 join 逻辑留给实现，建模器上的 entering behavior
 *       不导出到 XML，所以 BPMN 文件本身回答不了这个问题。</li>
 * </ol>
 *
 * <p><b>穿透不等于能收敛</b>：两条 token 各自走到结束事件，流程必须仍能正常终结，
 * 否则就是把「少一条分支」换成了「永远不结束」。
 */
class WfGatewayJoinSemanticsTest {

    private static final String HEAD =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n";

    private static final String TAIL = "</definitions>\n";

    /**
     * 两条并行分支汇合到 <b>单个网关</b>，网关只有一条出线。
     *
     * <p>汇合点只有一条出线是有意的：它把「几条 token 过了汇合点」与
     * 「下游会分叉成几条」这两件事分开，于是 token 数与待办数一一对应，
     * 判据不需要再推理分叉。
     *
     * @param gatewayTag 网关元素（含属性），例如 {@code <exclusiveGateway id="g"/>}
     */
    private static String twoPathsInto(String processId, String gatewayTag,
                                       String afterFlowExtra) {
        return HEAD
                + "  <process id=\"" + processId + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <parallelGateway id=\"pg\"/>\n"
                + "    <userTask id=\"taskA\" zifang:assignee=\"a\"/>\n"
                + "    <userTask id=\"taskB\" zifang:assignee=\"b\"/>\n"
                + "    " + gatewayTag + "\n"
                + "    <userTask id=\"after\" zifang:assignee=\"ops\"/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"pg\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"pg\" targetRef=\"taskA\"/>\n"
                + "    <sequenceFlow id=\"f3\" sourceRef=\"pg\" targetRef=\"taskB\"/>\n"
                + "    <sequenceFlow id=\"f4\" sourceRef=\"taskA\" targetRef=\"g\"/>\n"
                + "    <sequenceFlow id=\"f5\" sourceRef=\"taskB\" targetRef=\"g\"/>\n"
                + "    <sequenceFlow id=\"f6\" sourceRef=\"g\" targetRef=\"after\"" + afterFlowExtra
                + "/>\n"
                + "    <sequenceFlow id=\"f7\" sourceRef=\"after\" targetRef=\"e\"/>\n"
                + "  </process>\n"
                + TAIL;
    }

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
    }

    // ==================== 排他网关（本轮修的那条） ====================

    @Test
    @DisplayName("排他网关汇合是穿透：两条并行 token 各自往下，不合并")
    void exclusiveGatewayJoinIsPassThrough() {
        String pid = startTwoPath(twoPathsInto("xgPass",
                "<exclusiveGateway id=\"g\"/>", ""), "xgPass");

        assertEquals(2, aliveAt(pid).size(),
                "排他网关必须让两条 token 各自通过。先前它把两条合并成一条，"
                        + "症状是从 Camunda 导入的模型少掉一条并行分支，"
                        + "而图上看不出任何异常。实际 token: " + activityIdsOf(pid));
        assertEquals(2, openAt(pid, "after").size(),
                "两条 token ⇒ 两条待办。合成一条的话下游就少了一次办理");
    }

    @Test
    @DisplayName("穿透之后流程仍能正常收敛到终态")
    void passThroughStillTerminates() {
        String pid = startTwoPath(twoPathsInto("xgEnd",
                "<exclusiveGateway id=\"g\"/>", ""), "xgEnd");
        assertFalse(repo.findProcessInstance(pid).getStatus().isTerminal(),
                "前置条件：两条都还停着，流程没结束");

        completeAllOpen(pid);

        assertTrue(repo.findProcessInstance(pid).getStatus().isTerminal(),
                "穿透不是「少合并一次」，更不能换成「永远不结束」。实际状态: "
                        + repo.findProcessInstance(pid).getStatus());
        assertEquals(0, aliveAt(pid).size(),
                "两条 token 都该终结。残留活跃 token 的症状是流程状态已经 COMPLETED "
                        + "而库里还有没走完的执行 —— 统计与实际对不上");
    }

    // ==================== 并行 / 包容：不该被带跑 ====================

    @Test
    @DisplayName("并行网关汇合仍然合并成一条")
    void parallelGatewayStillMerges() {
        String pid = startTwoPath(twoPathsInto("pgJoin",
                "<parallelGateway id=\"g\"/>", ""), "pgJoin");

        assertEquals(1, aliveAt(pid).size(),
                "并行网关的 join 语义是「等到齐再合并」，这次改动不许碰到它。实际 token: "
                        + activityIdsOf(pid));
        assertEquals(1, openAt(pid, "after").size());
    }

    @Test
    @DisplayName("包容网关汇合仍然合并成一条")
    void inclusiveGatewayStillMerges() {
        String pid = startTwoPath(twoPathsInto("igJoin",
                "<inclusiveGateway id=\"g\"/>", ""), "igJoin");

        assertEquals(1, aliveAt(pid).size(),
                "包容网关的 join 是「等所有确实激活了的入线」，合并成一条。实际 token: "
                        + activityIdsOf(pid));
    }

    // ==================== 复杂网关：可配 ====================

    @Test
    @DisplayName("复杂网关默认 joining（合并），保持既有行为不变")
    void complexGatewayDefaultsToJoining() {
        String pid = startTwoPath(complexXml("cgDefault", ""), "cgDefault", conditionTrue());

        assertEquals(1, aliveAt(pid).size(),
                "默认必须是 joining —— 改默认值等于让已上线的模型悄悄换语义。"
                        + "实际 token: " + activityIdsOf(pid));
    }

    @Test
    @DisplayName("复杂网关 competing 是穿透，两条 token 各自往下")
    void complexGatewayCompetingIsPassThrough() {
        String pid = startTwoPath(complexXml("cgCompeting",
                " zifang:complexJoin=\"competing\""), "cgCompeting", conditionTrue());

        assertEquals(2, aliveAt(pid).size(),
                "competing 写下去却没有穿透，等于这个属性不生效 —— "
                        + "而症状与「没写这个属性」完全一样，看不出来。实际 token: "
                        + activityIdsOf(pid));
        assertEquals(2, openAt(pid, "after").size());
    }

    @Test
    @DisplayName("joining 与 competing 的差别来自网关自己，与出线和分支数无关")
    void competingIsDecidedByTheGatewayAlone() {
        // 同一个夹具、同一批出线，只差网关上的一个属性 ——
        // 这样「差别」就不可能被出线条件或分支激活数带偏
        int joining = aliveAt(startTwoPath(complexXml("cmpOff",
                " zifang:complexJoin=\"joining\""), "cmpOff", conditionTrue())).size();
        int competing = aliveAt(startTwoPath(complexXml("cmpOn",
                " zifang:complexJoin=\"competing\""), "cmpOn", conditionTrue())).size();

        assertEquals(1, joining, "joining 合并成一条");
        assertEquals(2, competing, "competing 穿透成两条");
    }

    // ==================== 部署期挡住 ====================

    @Test
    @DisplayName("complexJoin 取值非法必须报错，而不是退到默认值")
    void invalidComplexJoinIsRejected() {
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> deploy(complexXml("cgBad", " zifang:complexJoin=\"compete\"")));
        assertTrue(ex.getMessage().contains("competing"),
                "报错里要列出可选值 —— 作者拼错时只有一条可选清单可查。实际: "
                        + ex.getMessage());
    }

    @Test
    @DisplayName("在排他/并行网关上写 complexJoin 必须报错（那句话与引擎行为相反）")
    void complexJoinOnOtherGatewaysIsRejected() {
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> deploy(twoPathsInto("xgJoinAttr",
                        "<exclusiveGateway id=\"g\" zifang:complexJoin=\"competing\"/>", "")));
        assertTrue(ex.getMessage().contains("pass-through"),
                "要点明排他网关本来是什么样：写这句话的人以为自己在改行为，"
                        + "而引擎压根不读。实际: " + ex.getMessage());

        WfDefinitionException pg = assertThrows(WfDefinitionException.class,
                () -> deploy(twoPathsInto("pgJoinAttr",
                        "<parallelGateway id=\"g\" zifang:complexJoin=\"joining\"/>", "")));
        assertTrue(pg.getMessage().contains("complexGateway"),
                "要指出想配这个属性该用哪种网关。实际: " + pg.getMessage());
    }

    // ==================== 字段与持久化 ====================

    @Test
    @DisplayName("只认 competing 这一个值，其余一律当 joining（兜底方向要保守）")
    void onlyCompetingTriggersPassThrough() {
        WfNode node = new WfNode("g", "网关", WfNodeType.COMPLEX_GATEWAY);
        assertFalse(node.isCompetingJoin(), "没配就是 joining");

        node.setComplexJoin("competing");
        assertTrue(node.isCompetingJoin());
        node.setComplexJoin("  COMPETING  ");
        assertTrue(node.isCompetingJoin(), "大小写与空白都该被容忍，判据不能只在完美输入上绿");

        for (String typo : new String[]{"compete", "nonJoining", "true", ""}) {
            node.setComplexJoin(typo);
            assertFalse(node.isCompetingJoin(),
                    "拼错（" + typo + "）时必须落回 joining —— 那是本引擎的既有行为，"
                            + "而 competing 是新的那个");
        }
    }

    @Test
    @DisplayName("complexJoin 经 codec 存进定义再读回来")
    void complexJoinSurvivesCodecRoundTrip() {
        // 必须走 codec 而不是只 parse：内存实现走 Java 序列化、保留全部字段，
        // 而 codec 是手写 DTO 的逐字段拷贝，漏一个字段编译器不会提醒。
        // 「字段声明在」也不等于「值被搬过去了」——反射护栏只查前者。
        WfDefinition original = new WfXmlParser().parse(complexXml("cgCodec",
                " zifang:complexJoin=\"competing\""));

        WfDefinition back = WfDefinitionCodec.decode(WfDefinitionCodec.encode(original));

        assertEquals("competing", back.node("g").getComplexJoin(),
                "丢了的话重新部署出来的网关会悄悄变回 joining —— "
                        + "同一个文件升级前后走出不同的图，且没有任何报错");
        assertTrue(back.node("g").isCompetingJoin(), "读回来之后还得被认成 competing");
    }

    // ==================== 夹具 ====================

    /**
     * 复杂网关的夹具：两条出线，其中一条带条件、另一条是默认流。
     *
     * <p>复杂网关的校验器要求「要么判别变量 + caseValue，要么出线带条件」
     * 且「必须有默认流」，所以汇合点只有一个出线的那种夹具它部署不了 ——
     * 硬套会得到一条与汇合语义无关的报错，然后白排查一轮。
     */
    private static String complexXml(String processId, String gatewayExtraAttr) {
        return HEAD
                + "  <process id=\"" + processId + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <parallelGateway id=\"pg\"/>\n"
                + "    <userTask id=\"taskA\" zifang:assignee=\"a\"/>\n"
                + "    <userTask id=\"taskB\" zifang:assignee=\"b\"/>\n"
                + "    <complexGateway id=\"g\"" + gatewayExtraAttr + "/>\n"
                + "    <userTask id=\"after\" zifang:assignee=\"ops\"/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <endEvent id=\"e2\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"pg\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"pg\" targetRef=\"taskA\"/>\n"
                + "    <sequenceFlow id=\"f3\" sourceRef=\"pg\" targetRef=\"taskB\"/>\n"
                + "    <sequenceFlow id=\"f4\" sourceRef=\"taskA\" targetRef=\"g\"/>\n"
                + "    <sequenceFlow id=\"f5\" sourceRef=\"taskB\" targetRef=\"g\"/>\n"
                + "    <sequenceFlow id=\"f6\" sourceRef=\"g\" targetRef=\"after\">\n"
                + "      <conditionExpression>${ok == true}</conditionExpression>\n"
                + "    </sequenceFlow>\n"
                + "    <sequenceFlow id=\"f7\" sourceRef=\"g\" targetRef=\"e2\" default=\"true\"/>\n"
                + "    <sequenceFlow id=\"f8\" sourceRef=\"after\" targetRef=\"e\"/>\n"
                + "  </process>\n"
                + TAIL;
    }

    /** 只为"能部署"服务：本类的部署期用例断的是**报错**，不是部署结果。 */
    private void deploy(String xml) {
        repository.deployXml(xml, "k" + System.nanoTime());
    }

    /**
     * 启动并**把上游两条分支办完**，让 token 抵达汇合网关。
     *
     * <p>办完这一步不是可有可无的：夹具是「并行 → 两个任务 → 汇合网关 → after」，
     * 不办上游的话 token 还停在 taskA/taskB，而那里恰好也是两条 ——
     * 于是 {@code aliveAt()==2} 会**因为错误的原因通过**，
     * 而"汇合后有几条 token"这个问题根本没被问到。
     */
    private String startTwoPath(String xml, String key) {
        return startTwoPath(xml, key, new HashMap<String, Object>());
    }

    /**
     * @param vars 启动变量。复杂网关那条夹具必须给 {@code ok=true}：
     * 它的出线条件不成立时会走默认流直接结束，{@code after} 压根不会被走到 ——
     * 于是「汇合后有几条 token」这个问题被换成了「流程有没有走那条线」，
     * 而后者与本类要钉的东西无关。
     */
    private String startTwoPath(String xml, String key, Map<String, Object> vars) {
        WfDefinition definition = repository.deployXml(xml, key);
        String pid = runtime.startProcessInstance(definition, key + "-" + System.nanoTime(),
                "alice", null, vars);
        completeAllOpen(pid);
        return pid;
    }

    /** {@code ok=true} —— 让复杂网关那条带条件的出线真的成立。 */
    private static Map<String, Object> conditionTrue() {
        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put("ok", Boolean.TRUE);
        return vars;
    }

    private List<WfTask> openAt(String pid, String nodeId) {
        List<WfTask> result = new java.util.ArrayList<>();
        for (WfTask t : repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(50))) {
            if (t.isOpen() && nodeId.equals(t.getDefinitionId())) {
                result.add(t);
            }
        }
        return result;
    }

    private void completeAllOpen(String pid) {
        // **要循环而不是只办一次**：穿透之后会有多条同节点待办，
        // 只办一条会把「穿透后流程能否收敛」误测成「穿透后流程卡住」
        for (WfTask t : repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(50))) {
            runtime.completeTask(t.getId(), t.getAssignee(), "办完", new HashMap<String, Object>());
        }
    }

    private List<WfExecution> aliveAt(String pid) {
        List<WfExecution> result = new java.util.ArrayList<>();
        for (WfExecution e : repo.findExecutionsByProcessInstance(pid)) {
            if (!e.isEnded()) {
                result.add(e);
            }
        }
        return result;
    }

    private List<String> activityIdsOf(String pid) {
        List<String> ids = new java.util.ArrayList<>();
        for (WfExecution e : repo.findExecutionsByProcessInstance(pid)) {
            ids.add(e.getActivityId() + (e.isEnded() ? "(ended)" : ""));
        }
        java.util.Collections.sort(ids);
        return ids;
    }
}
