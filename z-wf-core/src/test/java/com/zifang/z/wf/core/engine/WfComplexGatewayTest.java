package com.zifang.z.wf.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;

/**
 * 复杂网关（complexGateway）—— 按流程变量的<b>取值</b>分派。
 *
 * <p>本类盯五件错了都不报错的事：
 * <ol>
 *   <li><b>求值必须用 evalRaw 而不是 evaluate</b>：后者返回 boolean，
 *       会把 {@code approved}/{@code rejected} 都压成 false，
 *       于是所有 caseValue 都匹配不上、流程永远走默认线。</li>
 *   <li><b>只走一条线</b>：即使多条 caseValue 都能匹配，也只有第一条生效 ——
 *       走多条是包容网关的语义。</li>
 *   <li><b>判别变量取不到值时不能静默走默认线</b>：那会让流程"成功地"
 *       走错分支且毫无报错。</li>
 *   <li><b>不匹配时要有可解释的落点</b>：要么走默认流，要么停住并说清楚，
 *       不能悄悄消失。</li>
 *   <li><b>三种写死在部署期挡住</b>：缺判别变量、没配 caseValue、没有默认流。</li>
 * </ol>
 */
class WfComplexGatewayTest {

    /** 审批 → 按结果分派：通过走确认，驳回走驳回页，其余走默认。 */
    private static final String BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"routeProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <complexGateway id=\"gw\" zifang:caseVariable=\"${result}\"/>\n"
            + "    <userTask id=\"confirm\" name=\"确认\" zifang:assignee=\"ceo\"/>\n"
            + "    <userTask id=\"reject\" name=\"驳回通知\" zifang:assignee=\"hr\"/>\n"
            + "    <userTask id=\"manual\" name=\"转人工\" zifang:assignee=\"ops\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <endEvent id=\"e2\"/>\n"
            + "    <endEvent id=\"e3\"/>\n"

            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"gw\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"gw\" targetRef=\"confirm\""
            + " zifang:caseValue=\"approved\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"gw\" targetRef=\"reject\""
            + " zifang:caseValue=\"rejected\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"gw\" targetRef=\"manual\" default=\"true\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"confirm\" targetRef=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f7\" sourceRef=\"reject\" targetRef=\"e2\"/>\n"
            + "    <sequenceFlow id=\"f8\" sourceRef=\"manual\" targetRef=\"e3\"/>\n"

            + "  </process>\n"
            + "</definitions>\n";

    /** camunda:caseExpression 写法：迁移 Camunda 模型时不被识别就没意义。 */
    private static final String CAMUNDA_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"camundaRoute\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <complexGateway id=\"gw\" camunda:caseExpression=\"${result}\"/>\n"
            + "    <userTask id=\"a\" zifang:assignee=\"u1\"/>\n"
            + "    <userTask id=\"b\" zifang:assignee=\"u2\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <endEvent id=\"e2\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"gw\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"gw\" targetRef=\"a\""
            + " zifang:caseValue=\"approved\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"gw\" targetRef=\"b\" default=\"true\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"a\" targetRef=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"b\" targetRef=\"e2\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(),
                new WfHookDispatcher());
    }

    private String startAndComplete(String xml, String key, String result) {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(xml));
        String pid = runtime.startProcessInstance(definition, "BIZ-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
        WfTask task = openTask(pid);
        Map<String, Object> vars = new HashMap<String, Object>();
        if (result != null) {
            vars.put("result", result);
        }
        runtime.completeTask(task.getId(), task.getAssignee(), "办结", vars);
        return pid;
    }

    private WfTask openTask(String pid) {
        List<WfTask> open = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(10));
        assertEquals(1, open.size(), "应当恰好有一个待办，实际 " + open.size());
        return open.get(0);
    }

    private String handlerOf(String pid) {
        WfTask task = openTask(pid);
        return task.getAssignee();
    }

    // ==================== 选线 ====================

    @Test
    @DisplayName("值匹配到 caseValue：走对应的那一条")
    void valuePicksMatchingFlow() {
        assertEquals("ceo", handlerOf(startAndComplete(BPMN, "routeProcess", "approved")),
                "result=approved 应当走 confirm");
        assertEquals("hr", handlerOf(startAndComplete(BPMN, "routeProcess", "rejected")),
                "result=rejected 应当走 reject");
    }

    @Test
    @DisplayName("值匹配不到任何 caseValue：走默认线")
    void unmatchedValueFallsBackToDefault() {
        assertEquals("ops", handlerOf(startAndComplete(BPMN, "routeProcess", "unknown")),
                "匹配不上应当走默认线转人工");
    }

    @Test
    @DisplayName("判别变量根本没赋值：token 停在网关上，绝不静默走默认线")
    void missingVariableStopsInsteadOfDefaulting() {
        String pid = startAndComplete(BPMN, "routeProcess", null);

        // 静默走默认线的话，待办会出现在 ops 名下
        List<WfTask> open = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(10));
        assertTrue(open.isEmpty(),
                "判别变量没值时不能走默认线 —— 那是让流程【成功地】走错分支。"
                        + "实际待办 " + open);
        assertTrue(repo.findProcessInstance(pid).getStatus().isActive(),
                "流程仍处于活动态，只是停在网关上没有出线的去路");
    }

    @Test
    @DisplayName("只走一条线：即使多条 caseValue 都写同一个值")
    void onlyOneFlowIsTaken() {
        WfDefinition definition = new WfXmlParser().parse(
                BPMN.replace("zifang:caseValue=\"rejected\"", "zifang:caseValue=\"approved\""));
        String pid = runtime.startProcessInstance(
                repository.deploy(definition), "BIZ-1", "alice", null,
                new HashMap<String, Object>());
        WfTask task = openTask(pid);
        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put("result", "approved");
        runtime.completeTask(task.getId(), task.getAssignee(), "办结", vars);

        List<WfTask> open = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(10));
        assertEquals(1, open.size(),
                "两条 caseValue 都能匹配时也只能走一条 —— 走多条是包容网关的语义");
        assertEquals("ceo", open.get(0).getAssignee(), "走的是第一条匹配的线");
    }

    @Test
    @DisplayName("排他网关不受 complexGateway 改动影响：两条线按条件各走一条")
    void exclusiveGatewayStillWorks() {
        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"ex\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                + "    <exclusiveGateway id=\"gw\"/>\n"
                + "    <userTask id=\"a\" zifang:assignee=\"u1\"/>\n"
                + "    <userTask id=\"b\" zifang:assignee=\"u2\"/>\n"
                + "    <endEvent id=\"e1\"/>\n"
                + "    <endEvent id=\"e2\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"gw\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"gw\" targetRef=\"a\">\n"
                + "      <conditionExpression>${ok}</conditionExpression>\n"
                + "    </sequenceFlow>\n"
                + "    <sequenceFlow id=\"f3\" sourceRef=\"gw\" targetRef=\"b\" default=\"true\"/>\n"
                + "    <sequenceFlow id=\"f4\" sourceRef=\"a\" targetRef=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f5\" sourceRef=\"b\" targetRef=\"e2\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(xml));
        String pid = runtime.startProcessInstance(definition, "BIZ-EX", "alice", null,
                new HashMap<String, Object>());
        assertTrue(definition.node("gw").getType() == WfNodeType.EXCLUSIVE_GATEWAY,
                "这条定义里写的是 exclusiveGateway，解析成复杂网关说明类型解析被改坏了");
        List<WfTask> open = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(10));
        assertEquals(1, open.size(), "排他网关应当恰好放行一条线");
        assertEquals("u2", open.get(0).getAssignee(),
                "没有 ok 变量时第一条条件不成立，应当走默认线到 u2");
    }

    // ==================== 解析 ====================

    @Test
    @DisplayName("解析器把 complexGateway 认成网关而不是未知元素")
    void parserRecognizesComplexGateway() {
        WfDefinition definition = new WfXmlParser().parse(BPMN);
        WfNode gw = definition.node("gw");
        assertEquals(WfNodeType.COMPLEX_GATEWAY, gw.getType());
        assertEquals("${result}", gw.getCaseVariable());
        assertEquals("approved", definition.outgoingFlows("gw").get(0).getCaseValue());
    }

    @Test
    @DisplayName("camunda:caseExpression 同样被识别")
    void camundaCaseExpressionIsRecognized() {
        WfDefinition definition = new WfXmlParser().parse(CAMUNDA_BPMN);
        assertEquals("${result}", definition.node("gw").getCaseVariable(),
                "camunda 导出模型带的是 caseExpression，不认它等于不支持复杂网关");
    }

    @Test
    @DisplayName("caseVariable / caseValue 经 codec 往返后仍在")
    void fieldsSurviveCodecRoundTrip() {
        // 刻意直接调 codec 而不是走 repository.getDefinition：
        // 内存实现持有的是同一个对象引用，根本不经过序列化 ——
        // 拿 repository 往返来测会得到"永远通过"的假绿，而真正要守的是
        // JDBC 那条路径（WfDefinitionRoundTripTest 的类注释记着同一个坑：
        // 定时器字段没进 codec，内存里全绿、生产上一个 job 都不建）。
        WfDefinition definition = new WfXmlParser().parse(BPMN);
        WfDefinition reloaded = com.zifang.z.wf.core.persistence.WfDefinitionCodec.decode(
                com.zifang.z.wf.core.persistence.WfDefinitionCodec.encode(definition));

        assertNotNull(reloaded);
        assertEquals("${result}", reloaded.node("gw").getCaseVariable(),
                "codec 不存 caseVariable 的话，JDBC 部署的流程里这个网关没有判别变量，"
                        + "而内存实现一切正常 —— 只在生产上表现为永远走默认线");
        assertEquals("approved", reloaded.outgoingFlows("gw").get(0).getCaseValue(),
                "codec 不存 caseValue 的话所有分支都匹配不上，同样只剩默认线");
    }

    // ==================== 部署期校验 ====================

    @Test
    @DisplayName("缺判别变量：部署期 ERROR")
    void missingCaseVariableIsRejected() {
        WfDefinitionException e = assertThrowsDeploy(
                BPMN.replace(" zifang:caseVariable=\"${result}\"", ""));
        assertTrue(e.getMessage().contains("caseVariable"), e.getMessage());
    }

    @Test
    @DisplayName("一条 caseValue 都没有：部署期 ERROR —— 永远走默认线的死网关")
    void noCaseValueIsRejected() {
        String xml = BPMN
                .replace(" zifang:caseValue=\"approved\"", "")
                .replace(" zifang:caseValue=\"rejected\"", "");
        WfDefinitionException e = assertThrowsDeploy(xml);
        assertTrue(e.getMessage().contains("caseValue"), e.getMessage());
    }

    @Test
    @DisplayName("没有默认流：部署期 ERROR —— 值一变 token 就永久停留")
    void noDefaultFlowIsRejected() {
        String xml = BPMN.replace(" default=\"true\"", "");
        WfDefinitionException e = assertThrowsDeploy(xml);
        assertTrue(e.getMessage().contains("默认流"), e.getMessage());
    }

    @Test
    @DisplayName("同一条出线同时配 caseValue 与 conditionExpression：部署期 ERROR")
    void caseValueAndConditionTogetherIsRejected() {
        String xml = BPMN.replace(" zifang:caseValue=\"approved\"",
                " zifang:caseValue=\"approved\" zifang:conditionExpression=\"${x}\"");
        WfDefinitionException e = assertThrowsDeploy(xml);
        assertTrue(e.getMessage().contains("caseValue"), e.getMessage());
    }

    @Test
    @DisplayName("复杂网关不是排他网关：选线语义不同")
    void complexGatewayIsNotExclusive() {
        WfDefinition definition = new WfXmlParser().parse(BPMN);
        WfNode gw = definition.node("gw");
        assertTrue(gw.getType().isGateway());
        assertTrue(gw.getType() != WfNodeType.EXCLUSIVE_GATEWAY,
                "复用排他网关会让 caseValue 被当成 conditionExpression 处理");
    }

    @Test
    @DisplayName("条件分派：两条分支在包容网关汇合时不会等第三条（否则流程卡死）")
    void multiActivationStillJoinsWithoutDeadlock() {
        // 这是「多条激活不会让汇合死锁」那条断言的判据 ——
        // 汇合有 4 条入线而只激活了 2 条；汇合判定若数的是"图上入线总数"而不是"确实激活过的兄弟 token"，
        // 这里就会永远等那条没被选中的 archive 分支，流程停在汇合处不动。
        // 注释里写了"不会死锁"，就得有判据钉住它。
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"joinRoute\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                + "    <complexGateway id=\"gw\"/>\n"
                + "    <userTask id=\"audit\" zifang:assignee=\"audit\"/>\n"
                + "    <userTask id=\"notify\" zifang:assignee=\"notify\"/>\n"
                + "    <userTask id=\"archive\" zifang:assignee=\"archive\"/>\n"
                + "    <userTask id=\"manual\" zifang:assignee=\"ops\"/>\n"
                + "    <inclusiveGateway id=\"join\"/>\n"
                + "    <userTask id=\"done\" zifang:assignee=\"ops\"/>\n"
                + "    <endEvent id=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"gw\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"gw\" targetRef=\"audit\">\n"
                + "      <conditionExpression>${amount &gt; 10000}</conditionExpression>\n"
                + "    </sequenceFlow>\n"
                + "    <sequenceFlow id=\"f3\" sourceRef=\"gw\" targetRef=\"notify\">\n"
                + "      <conditionExpression>${urgent}</conditionExpression>\n"
                + "    </sequenceFlow>\n"
                + "    <sequenceFlow id=\"f4\" sourceRef=\"gw\" targetRef=\"archive\">\n"
                + "      <conditionExpression>${archived}</conditionExpression>\n"
                + "    </sequenceFlow>\n"
                + "    <sequenceFlow id=\"fa\" sourceRef=\"gw\" targetRef=\"manual\" default=\"true\"/>\n"
                + "    <sequenceFlow id=\"f5\" sourceRef=\"audit\" targetRef=\"join\"/>\n"
                + "    <sequenceFlow id=\"f6\" sourceRef=\"notify\" targetRef=\"join\"/>\n"
                + "    <sequenceFlow id=\"f7\" sourceRef=\"archive\" targetRef=\"join\"/>\n"
                + "    <sequenceFlow id=\"fb\" sourceRef=\"manual\" targetRef=\"join\"/>\n"
                + "    <sequenceFlow id=\"f8\" sourceRef=\"join\" targetRef=\"done\"/>\n"
                + "    <sequenceFlow id=\"f9\" sourceRef=\"done\" targetRef=\"e1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
        Map<String, Object> vars = new HashMap<String, Object>();
        vars.put("amount", 20000);
        vars.put("urgent", true);
        vars.put("archived", false);
        String pid = runtime.startProcessInstance(
                repository.deploy(new WfXmlParser().parse(xml)),
                "JOIN-" + System.nanoTime(), "alice", null, vars);

        assertEquals(2, openTasks(pid).size(), "应当只有两条分支被激活");
        // 把两条分支都办掉，汇合处必须放行到 done
        for (WfTask t : openTasks(pid)) {
            runtime.completeTask(t.getId(), t.getAssignee(), "办结", new HashMap<String, Object>());
        }
        List<WfTask> after = openTasks(pid);
        assertEquals(1, after.size(), "两条分支办完后应当只剩汇合后的那一个待办，实际 " + after.size());
        assertEquals("ops", after.get(0).getAssignee(), "放行的是汇合之后的收尾任务");
        assertTrue(repo.findProcessInstance(pid).getStatus().isActive(),
                "流程应当还在等收尾任务办结，而不是卡在汇合处或提前结束");
    }

    private WfDefinitionException assertThrowsDeploy(String xml) {
        try {
            repository.deploy(new WfXmlParser().parse(xml));
        } catch (WfDefinitionException e) {
            return e;
        }
        throw new AssertionError("复杂网关的这段写法本该在部署期被 ERROR 挡住");
    }

    // ==================== 条件分派（第 25 轮） ====================

    /**
     * 条件分派：一个网关三条出线，各带条件，两条不成立的走默认。
     *
     * <p>没有 {@code zifang:caseVariable} ⇒ 走 BPMN 2.0 对复杂网关的定义
     * （出线带条件，成立的都激活）。此前这种写法里的条件<b>完全不生效</b>，
     * 三条线一条都选不中，流程静默走默认 —— 而校验器还会在
     * 「caseValue 与 condition 同时配」时报错，让人以为条件是有意义的。
     */
    private static final String CONDITION_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"condRoute\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <complexGateway id=\"gw\"/>\n"
            + "    <userTask id=\"audit\" name=\"审计\" zifang:assignee=\"audit\"/>\n"
            + "    <userTask id=\"notify\" name=\"通知\" zifang:assignee=\"notify\"/>\n"
            + "    <userTask id=\"archive\" name=\"归档\" zifang:assignee=\"archive\"/>\n"
            + "    <userTask id=\"manual\" name=\"转人工\" zifang:assignee=\"ops\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <endEvent id=\"e2\"/>\n"
            + "    <endEvent id=\"e3\"/>\n"
            + "    <endEvent id=\"e4\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"gw\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"gw\" targetRef=\"audit\">\n"
            + "      <conditionExpression xsi:type=\"tFormalExpression\""
            + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
            + "${amount &gt; 10000}</conditionExpression>\n"
            + "    </sequenceFlow>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"gw\" targetRef=\"notify\">\n"
            + "      <conditionExpression xsi:type=\"tFormalExpression\""
            + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
            + "${urgent}</conditionExpression>\n"
            + "    </sequenceFlow>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"gw\" targetRef=\"archive\">\n"
            + "      <conditionExpression xsi:type=\"tFormalExpression\""
            + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
            + "${archived}</conditionExpression>\n"
            + "    </sequenceFlow>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"gw\" targetRef=\"manual\" default=\"true\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"audit\" targetRef=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f7\" sourceRef=\"notify\" targetRef=\"e2\"/>\n"
            + "    <sequenceFlow id=\"f8\" sourceRef=\"archive\" targetRef=\"e3\"/>\n"
            + "    <sequenceFlow id=\"f9\" sourceRef=\"manual\" targetRef=\"e4\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private List<WfTask> openTasks(String pid) {
        return repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(10));
    }

    private String pidWith(Object... kv) {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(CONDITION_BPMN));
        Map<String, Object> vars = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            vars.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return runtime.startProcessInstance(definition, "COND-" + System.nanoTime(),
                "alice", null, vars);
    }

    @Test
    @DisplayName("条件分派：条件成立的线全部激活（复杂网关可以同时走多条）")
    void conditionsActivateEveryMatchingFlow() {
        // 两条都成立 ⇒ 两条都被激活。若实现成"第一条成立就停"，
        // 走的就是排他网关的语义，「复杂」两个字就没有意义了
        String pid = pidWith("amount", 20000, "urgent", true, "archived", false);
        List<WfTask> open = openTasks(pid);
        assertEquals(2, open.size(), "两条条件都成立，必须同时走两条，实际 " + open.size());
        List<String> assignees = new ArrayList<String>();
        for (WfTask t : open) {
            assignees.add(t.getAssignee());
        }
        assertTrue(assignees.contains("audit") && assignees.contains("notify"),
                "应当是审计与通知两条，实际 " + assignees);
    }

    @Test
    @DisplayName("条件分派：一条都不成立时走默认线")
    void noConditionHoldsFallsBackToDefault() {
        String pid = pidWith("amount", 1, "urgent", false, "archived", false);
        List<WfTask> open = openTasks(pid);
        assertEquals(1, open.size(), "都不成立时应当只走默认线");
        assertEquals("ops", open.get(0).getAssignee());
    }

    @Test
    @DisplayName("条件分派：引用未定义变量的线判「不成立」而不是放行")
    void undefinedVariableMeansNotMatched() {
        // 与排他/包容网关同一条约定：未定义变量 fail-closed。
        // 放行的话「金额未知」会被当成「金额大于一万」
        String pid = pidWith("urgent", false);
        List<WfTask> open = openTasks(pid);
        assertEquals(1, open.size(), "amount / archived 都没传，只有默认线该走");
        assertEquals("ops", open.get(0).getAssignee());
    }

    @Test
    @DisplayName("条件分派：三条都成立 ⇒ 三条都激活（不封顶）")
    void allThreeConditionsHold() {
        String pid = pidWith("amount", 20000, "urgent", true, "archived", true);
        assertEquals(3, openTasks(pid).size(),
                "复杂网关不限制激活几条 —— 限制成一条就退化成排他网关了");
    }

    @Test
    @DisplayName("条件分派：两种判定方式混用 ⇒ 部署期 ERROR（走哪几条没有唯一答案）")
    void mixingBothModesIsRejected() {
        String xml = CONDITION_BPMN
                .replace("<complexGateway id=\"gw\"/>",
                        "<complexGateway id=\"gw\" zifang:caseVariable=\"${result}\"/>");
        WfDefinitionException e = assertThrowsDeploy(xml);
        assertTrue(e.getMessage().contains("conditionExpression"),
                "要说清是「出线带条件但网关在按取值分派」: " + e.getMessage());
    }

    @Test
    @DisplayName("条件分派：出线带 caseValue 却没配判别变量 ⇒ 部署期 ERROR")
    void caseValueWithoutCaseVariableIsRejected() {
        String xml = CONDITION_BPMN.replace(
                "${amount &gt; 10000}</conditionExpression>",
                "</conditionExpression>").replace(
                "<sequenceFlow id=\"f3\"", "<sequenceFlow zifang:caseValue=\"x\" id=\"f3\"");
        WfDefinitionException e = assertThrowsDeploy(xml);
        assertTrue(e.getMessage().contains("caseVariable"),
                "要说清是「写了取值匹配却没告诉网关读哪个变量」: " + e.getMessage());
    }

    @Test
    @DisplayName("复杂网关既不配判别变量、出线也没条件 ⇒ 部署期 ERROR")
    void neitherModeConfiguredIsRejected() {
        // 此前这条会以「缺少 caseVariable」被拒；现在条件分派也是一条路，
        // 所以理由要改成「两种方式都没有」——否则报错在替一条已经不存在的规则说话
        WfDefinitionException e = assertThrowsDeploy(CONDITION_BPMN
                .replaceAll("<conditionExpression[^>]*>[^<]*</conditionExpression>", ""));
        assertTrue(e.getMessage().contains("两种判定方式都没有"),
                "要说清是两种分派方式都没配: " + e.getMessage());
        assertFalse(e.getMessage().contains("缺少 zifang:caseVariable"),
                "不要再拿「缺少 caseVariable」当理由 —— "
                        + "那个理由在有条件分派之后已经不成立了: " + e.getMessage());
    }
}
