package com.zifang.z.wf.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfDefinitionCodec;
import com.zifang.z.wf.core.persistence.WfHistoricActivityInstanceQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;

/**
 * 复杂网关的阈值汇合：N 取 M 到齐就放行（WCP-30 Structured Partial Join）。
 *
 * <p>典型场景是 2/3 会签：法务、财务、合规三路并行，凑够两条就继续。
 * 不配 {@code zifang:activationCondition} 时是"全到齐"，那是既有行为，本类不碰。
 *
 * <p>本类盯五件错了都不报错的事：
 * <ol>
 *   <li><b>没到阈值不往下走。</b>少一条就放行等于会签形同虚设。</li>
 *   <li><b>到阈值只合并"已抵达"的那几条</b>，不能顺手把还停在上游用户任务上的
 *       第三条结束掉 —— 症状是「合规的待办刚建出来就没了」。</li>
 *   <li><b>晚到的那条被消费掉，不穿过去。</b>穿过去等于下游被跑第二遍
 *       （"决策已作出，合规批完又触发一次"）；一直等则把流程挂死。</li>
 *   <li><b>消费必须留痕</b>：不写的话那条分支在轨迹上凭空消失，
 *       排障的人看到"合规这条怎么没结果"，而图上明明有它。</li>
 *   <li><b>配置非法部署期就报错</b>：非正整数、与 complexJoin="competing" 互斥、
 *       写在非复杂网关上。</li>
 * </ol>
 */
class WfComplexGatewayThresholdTest {

    private static final String NS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n";

    private static final String NS_END = "</definitions>\n";

    /** 2/3 会签：三条分支汇聚到阈值网关，网关合流到「决议」待办。 */
    private static final String TWO_OF_THREE = NS
            + "  <process id=\"thr2of3\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <parallelGateway id=\"fork\"/>\n"
            + "    <userTask id=\"legal\" name=\"法务审\" zifang:assignee=\"legal\"/>\n"
            + "    <userTask id=\"finance\" name=\"财务审\" zifang:assignee=\"finance\"/>\n"
            + "    <userTask id=\"compliance\" name=\"合规审\" zifang:assignee=\"compliance\"/>\n"
            + "    <complexGateway id=\"join\" zifang:activationCondition=\"2\"/>\n"
            + "    <userTask id=\"decide\" name=\"决议\" zifang:assignee=\"ceo\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"fork\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"fork\" targetRef=\"legal\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"fork\" targetRef=\"finance\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"fork\" targetRef=\"compliance\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"legal\" targetRef=\"join\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"finance\" targetRef=\"join\"/>\n"
            + "    <sequenceFlow id=\"f7\" sourceRef=\"compliance\" targetRef=\"join\"/>\n"
            + "    <sequenceFlow id=\"f8\" sourceRef=\"join\" targetRef=\"decide\"/>\n"
            + "    <sequenceFlow id=\"f9\" sourceRef=\"decide\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + NS_END;

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

    // ==================== 放行 ====================

    @Test
    @DisplayName("只批一条不放行，凑够两条立刻往下走")
    void releasesOnlyWhenThresholdReached() {
        String pid = start(TWO_OF_THREE, "thr2of3");
        assertEquals(3, openTasks(pid).size(), "前置：三条分支并行，各一个待办");

        completeOne(pid, "legal");
        assertTrue(openTasks(pid, "decide").isEmpty(),
                "只到一条不该放行 —— 会签变成了一张纸。实际已有决议待办");
        assertEquals(2, openTasks(pid).size(), "还剩财务与合规在办");

        completeOne(pid, "finance");
        assertEquals(1, openTasks(pid, "decide").size(),
                "凑够 2 条就该放行。实际: " + openTasks(pid, "decide"));
    }

    @Test
    @DisplayName("放行只合并已抵达的，**不误杀还在跑的第三条**")
    void doesNotKillTheStillRunningBranch() {
        String pid = start(TWO_OF_THREE, "thr2of3");
        completeOne(pid, "legal");
        completeOne(pid, "finance");

        // **这条是本类最要紧的断言**：阈值语义下若复用 collapseSiblings，
        // 它会结束"所有同批 peer"，包括还停在 compliance 任务上的那条 ——
        // 症状是合规的待办凭空消失，而 token 也被结束，图上完全看不出发生了什么
        assertEquals(1, openTasks(pid, "compliance").size(),
                "合规还没走到的合规分支，它的待办必须还在。"
                        + "实际: " + openTasks(pid, "compliance"));
        assertEquals(1, openTasks(pid, "decide").size(), "决议待办已建出");
    }

    @Test
    @DisplayName("晚到的那条被消费掉：决策已作出，下游不被跑第二遍")
    void lateArrivalIsConsumedNotPassedThrough() {
        String pid = start(TWO_OF_THREE, "thr2of3");
        completeOne(pid, "legal");
        completeOne(pid, "finance");
        assertEquals(1, openTasks(pid, "decide").size(), "前置：已放行一次");

        completeOne(pid, "compliance");

        // **下游只该有一次决议待办**：晚到的那条如果穿过去，decide 会变成 2 个
        assertEquals(1, openTasks(pid, "decide").size(),
                "晚到的那条必须被消费掉：WCP-30 说后续使能不再把控制权往后传。"
                        + "穿过去的后果是「决策已作出，合规批完又触发一次」。"
                        + "实际决议待办数: " + openTasks(pid, "decide"));
        assertTrue(openTasks(pid, "compliance").isEmpty(), "合规待办已办结");
    }

    @Test
    @DisplayName("消费要留痕：那条分支不能从轨迹上凭空消失")
    void consumptionLeavesATrail() {
        String pid = start(TWO_OF_THREE, "thr2of3");
        completeOne(pid, "legal");
        completeOne(pid, "finance");
        completeOne(pid, "compliance");

        boolean recorded = false;
        for (WfActivityInstance activity : activitiesOf(pid)) {
            if (activity.getOutcome() != null
                    && activity.getOutcome().contains("threshold-consumed")) {
                recorded = true;
                break;
            }
        }
        assertTrue(recorded,
                "被消费掉的到达必须写进活动轨迹 —— 不写的话排障的人看到"
                        + "「合规这条怎么没结果」，而图上明明有它。实际轨迹: "
                        + activitiesOf(pid));
    }

    // ==================== 不配阈值时行为不变 ====================

    @Test
    @DisplayName("不配阈值仍是全到齐（既有行为不许被改）")
    void withoutThresholdStillWaitsForAll() {
        String xml = TWO_OF_THREE.replace(" zifang:activationCondition=\"2\"", "");
        String pid = start(xml, "thrAll");
        completeOne(pid, "legal");
        completeOne(pid, "finance");
        assertTrue(openTasks(pid, "decide").isEmpty(),
                "没配阈值就是全到齐 —— 改默认值等于让已上线的模型悄悄换语义。实际已放行");

        completeOne(pid, "compliance");
        assertEquals(1, openTasks(pid, "decide").size(), "三条齐了才放行");
    }

    @Test
    @DisplayName("阈值 1 = 每条到都放行（退化；真要这个语义请用 complexJoin=\"competing\"）")
    void thresholdOfOneReleasesEveryArrival() {
        String xml = TWO_OF_THREE.replace("activationCondition=\"2\"", "activationCondition=\"1\"");
        String pid = start(xml, "thr1");
        completeOne(pid, "legal");
        assertEquals(1, openTasks(pid, "decide").size(),
                "阈值 1 时第一条到就该放行。实际: " + openTasks(pid, "decide"));

        // **阈值 1 是退化配置**：每条到达时"已抵达数"恒 ≥ 1，于是每条都放行 ——
        // 效果与穿透完全一样。写这条不是断言它该这样，而是把这条边界钉住：
        // 作者想要"各走各的"时，正确写法是 complexJoin="competing"，
        // 而 activationCondition="1" 看上去像是在做同一件事。
        completeOne(pid, "finance");
        completeOne(pid, "compliance");
        assertEquals(3, openTasks(pid, "decide").size(),
                "阈值 1 下每条到达都放行 —— 实际: " + openTasks(pid, "decide"));
    }

    // ==================== 部署期必须挡住 ====================

    @Test
    @DisplayName("非正整数 / 非数字 ⇒ 部署期 ERROR，不退到「等齐」")
    void invalidThresholdFailsAtDeployTime() {
        for (String bad : new String[]{"0", "-1", "abc", "2.5"}) {
            String xml = TWO_OF_THREE.replace("activationCondition=\"2\"",
                    "activationCondition=\"" + bad + "\"");
            WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                    () -> repository.deployXml(xml, "thrBad-" + bad.hashCode()),
                    "阈值 " + bad + " 必须部署期报错");
            assertTrue(ex.getMessage().contains("正整数"),
                    "报错要说清只能填什么。实际: " + ex.getMessage());
        }
    }

    @Test
    @DisplayName("与 complexJoin=\"competing\" 互斥")
    void competingConflictsWithThreshold() {
        String xml = TWO_OF_THREE.replace("zifang:activationCondition=\"2\"",
                "zifang:activationCondition=\"2\" zifang:complexJoin=\"competing\"");
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deployXml(xml, "thrConflict"));
        assertTrue(ex.getMessage().contains("互斥"),
                "两者同时配时若不报错，运行期二选一而作者无从知道选了哪个。实际: "
                        + ex.getMessage());
    }

    @Test
    @DisplayName("写在非复杂网关上 ⇒ 部署期 ERROR")
    void thresholdOnOtherGatewayIsRejected() {
        String xml = TWO_OF_THREE
                .replace("<parallelGateway id=\"fork\"/>",
                        "<parallelGateway id=\"fork\" zifang:activationCondition=\"2\"/>");
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deployXml(xml, "thrWrongGateway"));
        assertTrue(ex.getMessage().contains("activationCondition"),
                "要指出这个属性在那类网关上根本没有。实际: " + ex.getMessage());
    }

    // ==================== codec 往返 ====================

    @Test
    @DisplayName("阈值经 codec 往返不丢（codec 少写一处字段 = 部署后阈值消失且无人报错）")
    void thresholdSurvivesCodecRoundTrip() {
        WfDefinition definition = new WfXmlParser().parse(TWO_OF_THREE);
        WfDefinition back = WfDefinitionCodec.decode(WfDefinitionCodec.encode(definition));
        assertEquals("2", back.node("join").getActivationCondition(),
                "往返之后阈值必须还在。实际: " + back.node("join").getActivationCondition());
        assertEquals(2, back.node("join").activationThreshold(), "且要被解析成整数");
    }

    // ==================== 夹具 ====================

    private String start(String xml, String key) {
        WfDefinition definition = repository.deployXml(xml, key);
        return runtime.startProcessInstance(definition, key + "-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
    }

    private void completeOne(String pid, String nodeId) {
        for (WfTask task : openTasks(pid, nodeId)) {
            runtime.completeTask(task.getId(), task.getAssignee(), "批了",
                    new HashMap<String, Object>());
        }
    }

    private List<WfActivityInstance> activitiesOf(String pid) {
        return repo.queryActivityInstances(new WfHistoricActivityInstanceQuery()
                .setProcessInstanceId(pid).setPageNum(1).setPageSize(50));
    }

    private List<WfTask> openTasks(String pid) {
        List<WfTask> result = new java.util.ArrayList<>();
        for (WfTask t : repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(50))) {
            if (t.isOpen()) {
                result.add(t);
            }
        }
        return result;
    }

    private List<WfTask> openTasks(String pid, String nodeId) {
        List<WfTask> result = new java.util.ArrayList<>();
        for (WfTask t : openTasks(pid)) {
            if (nodeId.equals(t.getDefinitionId())) {
                result.add(t);
            }
        }
        return result;
    }

    @Test
    @DisplayName("阈值读的是 zifang: 与 camunda: 两个前缀")
    void bothPrefixesAreRead() {
        // 必须补 xmlns:camunda 声明：漏了的话 XML 本身就不合法，
        // 而报错说的是"解析失败"，会让人以为是解析器的问题
        String camunda = TWO_OF_THREE
                .replace("xmlns:zifang=\"https://zifang.com/bpmn\"",
                        "xmlns:zifang=\"https://zifang.com/bpmn\" "
                                + "xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\"")
                .replace("zifang:activationCondition=\"2\"",
                        "camunda:activationCondition=\"2\"");
        WfDefinition definition = new WfXmlParser().parse(camunda);
        assertEquals("2", definition.node("join").getActivationCondition(),
                "漏认一个前缀的症状是「配了不生效」，而作者看不出哪里错了");
        assertFalse(definition.node("join").isCompetingJoin(), "两个前缀不互相污染");
    }
}
