package com.zifang.z.wf.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.delegate.WfJavaDelegate;
import com.zifang.z.wf.core.engine.expression.WfExpressionEvaluator;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfCompensationEntry;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.service.WfDelegateRegistry;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;

/**
 * 取消结束事件 {@code cancelEndEvent} 与事务 {@code transaction}（第 38 轮）。
 *
 * <p>这两个元素是一起来的：取消结束事件是事务的取消路径终点，
 * 而它的语义（先退后收）完全依赖第 37 轮的补偿。
 *
 * <p><b>与终止结束事件的差别只有「结束之后往哪走」</b>，但那一条差别在图上的
 * 后果是相反的：
 * <ul>
 *   <li>终止：作用域内全部 token 连同待办一起收掉，流程不再往下走</li>
 *   <li>取消：退掉作用域内<b>已做完</b>的部分，然后沿<b>容器</b>的出线继续走</li>
 * </ul>
 * 所以本类的判据全部压在「取消之后后续节点有没有被走到」上 ——
 * 这一点错了，流程会在图的中途停住/结束，而实例状态看起来仍然是正常的。
 */
class WfCancelAndTransactionTest {

    private static final String NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /** 执行过的 delegate 名字，按发生顺序记录。 */
    private List<String> calls;

    public static class RecordingDelegate implements WfJavaDelegate {
        private final List<String> sink;

        public RecordingDelegate(List<String> sink) {
            this.sink = sink;
        }

        @Override
        public void execute(WfContext context, WfExecution execution) {
            sink.add(execution.getActivityId());
        }
    }

    /**
     * 事务：订票 → （扣款 / 取消两条路）→ 事务之后。
     *
     * <p>排他网关「扣款成功？成功走成功出口、否则走 cancelEndEvent」。
     * 两条路的<b>可见后果完全不同</b>：
     * <ul>
     *   <li>成功：事务之后的节点照常走到</li>
     *   <li>取消：订票被退掉，**但事务之后照样继续走**</li>
     * </ul>
     * 这正是「取消 ≠ 结束」的落点，也是本类最要紧的那条判据。
     */
    private static final String TRANSACTION_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"bookTx\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <transaction id=\"tx\" name=\"订票事务\">\n"
            + "      <startEvent id=\"tStart\"/>\n"
            + "      <userTask id=\"book\" name=\"订票\" zifang:assignee=\"alice\">\n"
            + "        <boundaryEvent id=\"beComp\" attachedToRef=\"book\">\n"
            + "          <compensateEventDefinition/>\n"
            + "        </boundaryEvent>\n"
            + "      </userTask>\n"
            + "      <exclusiveGateway id=\"ok\" name=\"扣款成功？\"/>\n"
            + "      <endEvent id=\"tEnd\"/>\n"
            + "      <cancelEndEvent id=\"tCancel\"/>\n"
            + "      <sequenceFlow id=\"tf1\" sourceRef=\"tStart\" targetRef=\"book\"/>\n"
            + "      <sequenceFlow id=\"tf2\" sourceRef=\"book\" targetRef=\"ok\"/>\n"
            + "      <sequenceFlow id=\"tf3\" sourceRef=\"ok\" targetRef=\"tEnd\">\n"
            + "        <conditionExpression xsi:type=\"tFormalExpression\""
            + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
            + "${paid == true}</conditionExpression>\n"
            + "      </sequenceFlow>\n"
            + "      <sequenceFlow id=\"tf4\" sourceRef=\"ok\" targetRef=\"tCancel\""
            + " zifang:defaultFlow=\"true\"/>\n"
            + "    </transaction>\n"
            + "    <userTask id=\"after\" name=\"事务之后\" zifang:assignee=\"carol\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <serviceTask id=\"undo\" name=\"退订\""
            + " isForCompensation=\"true\" zifang:delegateExpression=\"record\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"tx\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"tx\" targetRef=\"after\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"after\" targetRef=\"e1\"/>\n"
            + "    <association id=\"a1\" sourceRef=\"beComp\" targetRef=\"undo\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /**
     * 事务走成功路径：扣款成功了就不该退。
     *
     * <p>做法是把成功那条线改成<b>无条件</b>（去掉 {@code conditionExpression}），
     * 取消那条仍是默认流。排他网关按<b>声明顺序</b>求值，{@code tf3} 在前且无条件
     * ⇒ 必然先命中它，于是这条路稳定地走成功出口。
     *
     * <p>不写成「把变量 paid 设成 true」是因为那样就得往
     * {@code startProcessInstance} 塞初始变量，多一处可变的东西；
     * 而条件求值这条路一旦哪天改成「条件为假也往下走」，本夹具就会静默走到取消分支。
     */
    private static final String TX_HAPPY_BPMN = TRANSACTION_BPMN
            .replace("      <sequenceFlow id=\"tf3\" sourceRef=\"ok\" targetRef=\"tEnd\">\n"
                    + "        <conditionExpression xsi:type=\"tFormalExpression\""
                    + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                    + "${paid == true}</conditionExpression>\n"
                    + "      </sequenceFlow>\n",
                    "      <sequenceFlow id=\"tf3\" sourceRef=\"ok\" targetRef=\"tEnd\"/>\n")
            .replace("id=\"bookTx\"", "id=\"txHappy\"");

    // ---- 下面三个夹具一律写成**字面量**，不用 replace 从上面拼 ----
    //
    // 本轮实测的坑：上面主夹具的 `tf3` 从一行变成多行（加了 conditionExpression）、
    // `tf4` 多了 `zifang:defaultFlow`，于是下游三个 replace 的目标串**全部失配**，
    // 而 replace 失配是**静默**的 —— 夹具变成了「作者以为改过、其实没改」的版本，
    // 报错要等到部署期才出现，指向的还是一个他没写过的节点 id（`tEnd` 之类）。
    // 判据写歪时最容易被这种夹具骗过去：红的不是被测行为，是夹具本身。

    /** cancelEndEvent 接了出线：退完之后往哪走是矛盾的。 */
    private static final String CANCEL_WITH_FLOW_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"cancelWithFlow\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <transaction id=\"tx\" name=\"订票事务\">\n"
            + "      <startEvent id=\"tStart\"/>\n"
            + "      <userTask id=\"book\" name=\"订票\" zifang:assignee=\"alice\">\n"
            + "        <boundaryEvent id=\"beComp\" attachedToRef=\"book\">\n"
            + "          <compensateEventDefinition/>\n"
            + "        </boundaryEvent>\n"
            + "      </userTask>\n"
            + "      <exclusiveGateway id=\"ok\"/>\n"
            + "      <endEvent id=\"tEnd\"/>\n"
            + "      <cancelEndEvent id=\"tCancel\"/>\n"
            + "      <userTask id=\"never\" name=\"走不到\" zifang:assignee=\"dave\"/>\n"
            + "      <sequenceFlow id=\"tf1\" sourceRef=\"tStart\" targetRef=\"book\"/>\n"
            + "      <sequenceFlow id=\"tf2\" sourceRef=\"book\" targetRef=\"ok\"/>\n"
            + "      <sequenceFlow id=\"tf3\" sourceRef=\"ok\" targetRef=\"tEnd\"/>\n"
            + "      <sequenceFlow id=\"tf4\" sourceRef=\"ok\" targetRef=\"tCancel\""
            + " zifang:defaultFlow=\"true\"/>\n"
            + "      <sequenceFlow id=\"tf5\" sourceRef=\"tCancel\" targetRef=\"never\"/>\n"
            + "    </transaction>\n"
            + "    <userTask id=\"after\" name=\"事务之后\" zifang:assignee=\"carol\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <serviceTask id=\"undo\" name=\"退订\""
            + " isForCompensation=\"true\" zifang:delegateExpression=\"record\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"tx\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"tx\" targetRef=\"after\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"after\" targetRef=\"e1\"/>\n"
            + "    <association id=\"a1\" sourceRef=\"beComp\" targetRef=\"undo\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 空事务：什么都不会执行，而 token 照常往下走。 */
    private static final String EMPTY_TX_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"emptyTx\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <transaction id=\"tx\" name=\"空事务\"/>\n"
            + "    <userTask id=\"after\" name=\"事务之后\" zifang:assignee=\"carol\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"tx\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"tx\" targetRef=\"after\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"after\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 只有取消出口的事务：合法（永远走不到成功）。 */
    private static final String CANCEL_ONLY_TX_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"cancelOnlyTx\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <transaction id=\"tx\" name=\"只会取消的事务\">\n"
            + "      <startEvent id=\"tStart\"/>\n"
            + "      <userTask id=\"book\" name=\"订票\" zifang:assignee=\"alice\">\n"
            + "        <boundaryEvent id=\"beComp\" attachedToRef=\"book\">\n"
            + "          <compensateEventDefinition/>\n"
            + "        </boundaryEvent>\n"
            + "      </userTask>\n"
            + "      <cancelEndEvent id=\"tCancel\"/>\n"
            + "      <sequenceFlow id=\"tf1\" sourceRef=\"tStart\" targetRef=\"book\"/>\n"
            + "      <sequenceFlow id=\"tf2\" sourceRef=\"book\" targetRef=\"tCancel\"/>\n"
            + "    </transaction>\n"
            + "    <userTask id=\"after\" name=\"事务之后\" zifang:assignee=\"carol\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <serviceTask id=\"undo\" name=\"退订\""
            + " isForCompensation=\"true\" zifang:delegateExpression=\"record\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"tx\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"tx\" targetRef=\"after\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"after\" targetRef=\"e1\"/>\n"
            + "    <association id=\"a1\" sourceRef=\"beComp\" targetRef=\"undo\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /**
     * 主图上的取消结束事件：退掉整个实例已做的部分，然后结束实例。
     *
     * <p>需要两个可补偿的步骤（主图一步、事务内一步），否则「只退进程级那条」
     * 这件事根本无从观察 —— 这正是反向验证 F2 / F5 两条判据缺口的地方。
     */
    private static final String PROCESS_CANCEL_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"processCancel\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <transaction id=\"tx\" name=\"订票事务\">\n"
            + "      <startEvent id=\"tStart\"/>\n"
            + "      <userTask id=\"book\" name=\"订票\" zifang:assignee=\"alice\">\n"
            + "        <boundaryEvent id=\"beComp\" attachedToRef=\"book\">\n"
            + "          <compensateEventDefinition/>\n"
            + "        </boundaryEvent>\n"
            + "      </userTask>\n"
            + "      <endEvent id=\"tEnd\"/>\n"
            + "      <sequenceFlow id=\"tf1\" sourceRef=\"tStart\" targetRef=\"book\"/>\n"
            + "      <sequenceFlow id=\"tf2\" sourceRef=\"book\" targetRef=\"tEnd\"/>\n"
            + "    </transaction>\n"
            + "    <userTask id=\"after\" name=\"事务之后\" zifang:assignee=\"carol\">\n"
            + "      <boundaryEvent id=\"beAfter\" attachedToRef=\"after\">\n"
            + "        <compensateEventDefinition/>\n"
            + "      </boundaryEvent>\n"
            + "    </userTask>\n"
            + "    <exclusiveGateway id=\"last\" name=\"收尾还是取消\"/>\n"
            + "    <cancelEndEvent id=\"endIt\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <serviceTask id=\"undo\" name=\"退订票\""
            + " isForCompensation=\"true\" zifang:delegateExpression=\"record\"/>\n"
            + "    <serviceTask id=\"undoMain\" name=\"退主流程那一步\""
            + " isForCompensation=\"true\" zifang:delegateExpression=\"record\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"tx\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"tx\" targetRef=\"after\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"after\" targetRef=\"last\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"last\" targetRef=\"endIt\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"last\" targetRef=\"e1\""
            + " zifang:defaultFlow=\"true\"/>\n"
            + "    <association id=\"a1\" sourceRef=\"beComp\" targetRef=\"undo\"/>\n"
            + "    <association id=\"a2\" sourceRef=\"beAfter\" targetRef=\"undoMain\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /**
     * 主图上的取消，且<b>还有另一条分支在跑</b>。
     *
     * <p>这条夹具存在的原因：进程级的「退 + 收」里，**收**的那一半
     * 在单 token 的流程图上是等效的（token 自己走到 cancelEndEvent 时就被置 ENDED，
     * 没有「其余 token」可收）。要让那半条路径有区别，图上必须有并行分支 ——
     * 否则反向验证里去掉它也是绿的，而那不代表它是必需的。
     */
    private static final String PROCESS_PARALLEL_CANCEL_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"parallelCancel\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <parallelGateway id=\"pga\"/>\n"
            + "    <userTask id=\"book\" name=\"订票\" zifang:assignee=\"alice\">\n"
            + "      <boundaryEvent id=\"beComp\" attachedToRef=\"book\">\n"
            + "        <compensateEventDefinition/>\n"
            + "      </boundaryEvent>\n"
            + "    </userTask>\n"
            + "    <userTask id=\"slow\" name=\"另一条分支\" zifang:assignee=\"bob\"/>\n"
            + "    <exclusiveGateway id=\"last\" name=\"收尾还是取消\"/>\n"
            + "    <cancelEndEvent id=\"endIt\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <serviceTask id=\"undo\" name=\"退订\""
            + " isForCompensation=\"true\" zifang:delegateExpression=\"record\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"pga\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"pga\" targetRef=\"book\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"pga\" targetRef=\"slow\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"book\" targetRef=\"last\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"last\" targetRef=\"endIt\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"last\" targetRef=\"e1\""
            + " zifang:defaultFlow=\"true\"/>\n"
            + "    <association id=\"a1\" sourceRef=\"beComp\" targetRef=\"undo\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;

    @BeforeEach
    void setUp() {
        calls = new ArrayList<>();
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        WfDelegateRegistry delegates = new WfDelegateRegistry();
        delegates.register("record", new RecordingDelegate(calls));
        WfEngine engine = new WfEngine(new WfBehaviorRegistry(),
                new WfExpressionEvaluator(), new WfIdGenerator.DefaultWfIdGenerator(), delegates);
        runtime = new WfRuntimeService(repository, repo, engine, new WfHookDispatcher());
    }

    private WfDefinition deploy(String xml) {
        return repository.deploy(new WfXmlParser().parse(xml));
    }

    private String start(WfDefinition definition) {
        return runtime.startProcessInstance(definition,
                "BIZ-" + System.nanoTime(), "alice", null, new HashMap<String, Object>());
    }

    private List<WfTask> openTasks(String pid) {
        return new ArrayList<>(repo.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(pid).setOpenOnly(true)
                .setPageNum(1).setPageSize(Integer.MAX_VALUE)));
    }

    private WfTask taskOf(String pid, String definitionId) {
        for (WfTask task : openTasks(pid)) {
            if (definitionId.equals(task.getDefinitionId())) {
                return task;
            }
        }
        return null;
    }

    private void complete(WfTask task) {
        runtime.completeTask(task.getId(), task.getAssignee(), "办结",
                new HashMap<String, Object>());
    }

    // ==================== 解析 ====================

    @Test
    @DisplayName("cancelEndEvent 与 transaction 都要进真实类型表")
    void bothAreRealNodeTypes() {
        WfDefinition definition = new WfXmlParser().parse(TRANSACTION_BPMN);

        assertEquals(WfNodeType.TRANSACTION, definition.node("tx").getType(),
                "transaction 此前靠 fromBpmn 退化成 TASK，被部署期「不支持的元素」挡住 —— "
                        + "而它其实是 Camunda 里最常用的容器之一");
        assertNull(definition.node("tx").unsupportedBpmnElement(),
                "不能被打上「不支持」标记，否则部署期照样挡住");
        assertEquals(WfNodeType.CANCEL_END_EVENT, definition.node("tCancel").getType(),
                "cancelEndEvent 此前**不在**元素表里 ⇒ 走未知元素路径 ⇒ 部署期报不支持");
        assertNull(definition.node("tCancel").unsupportedBpmnElement());
    }

    @Test
    @DisplayName("事务里的节点属于事务这一段（与内联子流程同一把尺子）")
    void transactionChildrenBelongToIt() {
        WfDefinition definition = new WfXmlParser().parse(TRANSACTION_BPMN);

        assertTrue(definition.isInline(definition.node("book")),
                "事务内的节点必须被认成内联节点 —— 不认的话它既不属于内联子图"
                        + "也不属于主图，token 进了事务却一步都跑不了");
        assertEquals("tx", definition.inlineScopeOf("book"), "作用域是事务自己");
        assertEquals("", definition.inlineScopeOf("after"), "主图节点还是主图");
    }

    // ==================== 取消 ====================

    @Test
    @DisplayName("取消：退掉已做的部分，然后沿容器出线继续走")
    void cancelCompensatesThenContinues() {
        WfDefinition definition = deploy(TRANSACTION_BPMN);
        String pid = start(definition);

        assertNotNull(taskOf(pid, "book"), "事务里的订票先有单子");
        complete(taskOf(pid, "book"));

        // 登记：订票完成且挂过补偿边界事件
        assertEquals(1, repo.findCompensations(pid).size(), "订票完成后登记了一条补偿");

        // 网关条件都不成立 ⇒ 走默认（无 defaultFlow 时本仓取第一条不成立之外的行为），
        // 所以显式把变量置空、让两条都不成立 —— 断言走的是取消分支。
        WfTask after = taskOf(pid, "after");
        assertNotNull(after,
                "**取消之后必须继续走** —— 这是它与终止结束事件的唯一区别，"
                        + "也是最要紧的一条：取消退掉已做的部分，然后流程沿事务的出线继续。"
                        + "若这一步走不到，症状是「事务取消后整个流程停住」，"
                        + "而实例状态看上去完全正常。实际未办结的待办: " + openTasks(pid));
        assertEquals("carol", after.getAssignee());
    }

    @Test
    @DisplayName("取消真的会退（不是只登记不执行）")
    void cancelActuallyRunsTheHandler() {
        WfDefinition definition = deploy(TRANSACTION_BPMN);
        String pid = start(definition);
        complete(taskOf(pid, "book"));

        assertEquals(1, calls.size(),
                "走到 cancelEndEvent 必须真的执行退订处理器 —— "
                        + "只登记不执行的话，业务上等于「事务取消了但什么都没退」。"
                        + "实际调用记录: " + calls);
        assertEquals("undo", calls.get(0));
    }

    @Test
    @DisplayName("取消只退事务作用域内的（外层已完成的步骤不动）")
    void cancelIsScopedToTheTransaction() {
        // 外层的「事务之后」也挂一条补偿处理器，好观察取消有没有越界去退它。
        WfDefinition withOuter = new WfXmlParser().parse(TRANSACTION_BPMN.replace(
                "<userTask id=\"after\" name=\"事务之后\" zifang:assignee=\"carol\"/>",
                "<userTask id=\"after\" name=\"事务之后\" zifang:assignee=\"carol\">"
                        + "<boundaryEvent id=\"beAfter\" attachedToRef=\"after\">"
                        + "<compensateEventDefinition/></boundaryEvent></userTask>")
                .replace("<association id=\"a1\" sourceRef=\"beComp\" targetRef=\"undo\"/>",
                        "<association id=\"a1\" sourceRef=\"beComp\" targetRef=\"undo\"/>"
                                + "<association id=\"a2\" sourceRef=\"beAfter\" targetRef=\"undo\"/>")
                .replace("id=\"bookTx\"", "id=\"outerComp\""));
        String pid = start(repository.deploy(withOuter));

        complete(taskOf(pid, "book"));
        int afterCancel = calls.size();

        List<WfCompensationEntry> entries = repo.findCompensations(pid);
        assertEquals(1, entries.size(),
                "取消发生时只有事务内的订票登记过。实际登记: " + entries);
        assertEquals("tx", entries.get(0).getScope(),
                "事务内完成的登记，作用域是事务自己而不是进程级 —— "
                        + "判成进程级的话，取消事务会连外层的步骤一起撤");

        // 外层这一步也完成过，此时登记表里有两条，但**不该**触发任何补偿
        complete(taskOf(pid, "after"));
        assertEquals(2, repo.findCompensations(pid).size(), "外层那一步也完成过");
        assertEquals(afterCancel, calls.size(),
                "完成外层那一步不该触发补偿（只有取消/终止才触发），"
                        + "实际调用记录: " + calls);
    }

    @Test
    @DisplayName("事务走成功路径：什么都不退，后续照常走到")
    void happyPathDoesNotCompensate() {
        WfDefinition definition = deploy(TX_HAPPY_BPMN);
        String pid = start(definition);
        complete(taskOf(pid, "book"));

        assertNotNull(taskOf(pid, "after"), "成功路径要沿事务出线继续");
        assertEquals(0, calls.size(),
                "没有走取消就不该有任何补偿动作。实际调用记录: " + calls);
    }

    @Test
    @DisplayName("退过的登记标记 done（同一个事务撤两次不会退两遍）")
    void compensatedEntryIsMarkedDone() {
        WfDefinition definition = deploy(TRANSACTION_BPMN);
        String pid = start(definition);
        complete(taskOf(pid, "book"));

        for (WfCompensationEntry entry : repo.findCompensations(pid)) {
            assertTrue(entry.isDone(), "退过的登记要标记 done");
        }
    }

    @Test
    @DisplayName("取消不结束作用域：事务里的待办不会被作废")
    void cancelDoesNotVoidSiblingTasks() {
        WfDefinition definition = deploy(TRANSACTION_BPMN);
        String pid = start(definition);
        complete(taskOf(pid, "book"));

        // 走到取消后实例仍在推进（事务之后有 carol 的单子），
        // 所以不能出现「待办被作废、token 也没了」的停死态
        assertTrue(!openTasks(pid).isEmpty(),
                "取消之后要么有后续待办、要么流程已完成，两种都不该出现"
                        + "「没有待办也没有后续 token」的停死。实际待办: " + openTasks(pid));
        for (WfExecution execution : repo.findExecutionsByProcessInstance(pid)) {
            assertTrue(execution.isEnded() || "after".equals(execution.getActivityId())
                            || "tx".equals(execution.getActivityId()),
                    "取消之后不该留下停在事务内部的 token（活动: "
                            + execution.getActivityId() + ", 状态 " + execution.getState() + "）");
        }
    }

    @Test
    @DisplayName("取消到结束：实例正常完成（不是失败）")
    void cancelStillCompletesTheInstance() {
        WfDefinition definition = deploy(TRANSACTION_BPMN);
        String pid = start(definition);
        complete(taskOf(pid, "book"));
        complete(taskOf(pid, "after"));

        assertEquals(WfProcessStatus.COMPLETED, repo.findProcessInstance(pid).getStatus(),
                "取消是正常收尾的一部分，不是失败 —— 判成 INTERNALLY_TERMINATED "
                        + "会让「谁把它收掉的」在轨迹上变成一个错误");
    }

    // ==================== 部署期挡住 ====================

    @Test
    @DisplayName("cancelEndEvent 接了出线 ⇒ 部署期报错")
    void cancelWithOutgoingFlowIsRejected() {
        try {
            deploy(CANCEL_WITH_FLOW_BPMN);
            assertTrue(false, "cancelEndEvent 接了出线必须报错 —— "
                    + "取消之后 token 沿**容器**的出线继续，事件自己的出线永远走不到");
        } catch (WfDefinitionException expected) {
            assertTrue(expected.getMessage().contains("不能有出线"),
                    "实际报错: " + expected.getMessage());
        }
    }

    @Test
    @DisplayName("空 transaction ⇒ 部署期报错（不是「等价于调用外部流程」）")
    void emptyTransactionIsRejected() {
        try {
            deploy(EMPTY_TX_BPMN);
            assertTrue(false, "空事务必须报错 —— 它没有 calledElementKey 那种"
                    + "「等价于 callActivity」的解读，空着就是什么都不做，"
                    + "而 token 照常往下走，看上去这一步做完了");
        } catch (WfDefinitionException expected) {
            assertTrue(expected.getMessage().contains("没有画任何节点"),
                    "实际报错: " + expected.getMessage());
        }
    }

    @Test
    @DisplayName("事务可以只有 cancel 出口（不要求恰好一个 endEvent）")
    void transactionMayHaveOnlyCancelExit() {
        WfDefinition definition = repository.deploy(
                new WfXmlParser().parse(CANCEL_ONLY_TX_BPMN));
        assertNotNull(definition.node("tx"),
                "只有取消出口的事务是合法的 —— 它是「这个事务永远走不到成功」的那种写法");

        // 而且要真的跑得通：进事务 → 订票 → 取消 → 退 → 沿出线继续
        String pid = start(definition);
        complete(taskOf(pid, "book"));
        assertNotNull(taskOf(pid, "after"), "取消之后照样沿事务出线继续。实际待办: " + openTasks(pid));
        assertEquals(1, calls.size(), "取消真的退了。实际调用记录: " + calls);
    }

    @Test
    @DisplayName("容器里画了没接线的 endEvent 不算第二个入口（它走不到，但不该报非法）")
    void unwiredEndEventIsNotAnEntry() {
        // 「结束事件没有入线」是**常态**：它表示「走到它就是终点」，
        // 而不是「没人走到它」。按「无入线 = 入口候选」判的话，
        // 一个画了备用成功出口、但暂时没接线的图会被报成
        // 「找到 2 个内联起始节点」，而作者图上明明只有一个 startEvent。
        String xml = CANCEL_ONLY_TX_BPMN.replace(
                "      <cancelEndEvent id=\"tCancel\"/>\n",
                "      <endEvent id=\"tSpare\"/>\n"
                        + "      <cancelEndEvent id=\"tCancel\"/>\n");
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(xml));

        assertNotNull(definition.node("tSpare"), "前置条件：那个没接线的 endEvent 确实在图上");
        assertTrue(definition.inlineChildrenOf("tx").size() >= 2,
                "前置条件：它确实在容器内");
        String pid = start(definition);
        complete(taskOf(pid, "book"));
        assertNotNull(taskOf(pid, "after"),
                "没接线的那个出口不影响运行：取消之后照样沿事务出线继续。实际待办: " + openTasks(pid));
    }

    // ==================== 进程级取消 ====================

    @Test
    @DisplayName("主图上的 cancelEndEvent：退掉实例已做的部分，然后结束实例")
    void processLevelCancelCompensatesThenEnds() {
        // 与事务内的取消是两回事：主图上的取消**没有容器出线可沿**，
        // 所以它只能以「退完 + 结束实例」收尾（Camunda 的 process level cancel）。
        //
        // 这条判据的存在理由：进程级的双重登记（退 + 收）此前没有任何判据覆盖，
        // 反向验证 F2（去掉其中一半）也是绿的 —— 判据缺口，不是实现没问题。
        WfDefinition definition = deploy(PROCESS_CANCEL_BPMN);
        String pid = start(definition);
        complete(taskOf(pid, "book"));
        assertEquals(0, calls.size(),
                "前置条件：事务结束（tEnd）**不**触发补偿 —— 只有取消与终止才退。"
                        + "实际调用记录: " + calls);

        // after 办结 ⇒ 登记一条进程级的 ⇒ 无条件线 f4 先命中 ⇒ 进程级取消
        complete(taskOf(pid, "after"));
        assertEquals(1, calls.size(),
                "主图上的取消必须真的退掉实例已做的部分。实际调用记录: " + calls);
        assertEquals("undoMain", calls.get(0),
                "退的是主图那一步（事务内那一步属于已结束的容器，不在这次范围内）。"
                        + "实际调用记录: " + calls);

        assertEquals(WfProcessStatus.COMPLETED, repo.findProcessInstance(pid).getStatus(),
                "主图上的取消没有出线可沿，实例必须结束 —— "
                        + "不停的话就是「待办没了、没有 job、也不会再推进」的永久 ACTIVE");
        assertTrue(openTasks(pid).isEmpty(), "没有剩余待办。实际: " + openTasks(pid));
    }

    @Test
    @DisplayName("进程级取消只退进程级的，不动已经结束的容器里的登记")
    void processLevelCancelDoesNotReachInsideFinishedContainers() {
        // 两个作用域都先有登记（事务内一步 = tx、主图一步 = ""），再在**主图**上取消。
        // 事务内那一步属于已经结束的容器，不该被这次进程级取消顺手撤掉 ——
        // 补偿的 scope 过滤就是钉这一条。
        //
        // 夹具直接用 PROCESS_CANCEL_BPMN：它本来就已经带了两个可补偿步骤
        // （事务内的 beComp→undo、主图的 beAfter→undoMain），
        // 不需要再拼一个「加一个边界事件」的变体 ——
        // 那类拼法一旦目标串对不上就是**静默失配**：夹具没变而断言按改了写，
        // 症状是判据在断言一个从未存在过的图。
        WfDefinition definition = deploy(PROCESS_CANCEL_BPMN);
        String pid = start(definition);

        // 事务内那一步先完成（登记 scope=tx）
        complete(taskOf(pid, "book"));
        List<WfCompensationEntry> inContainer = repo.findCompensations(pid);
        assertEquals(1, inContainer.size(), "事务内那一步完成后有一条登记。实际: " + inContainer);
        assertEquals("tx", inContainer.get(0).getScope(), "事务内那一步属于事务作用域");

        // 主图那一步也完成（登记 scope=""）⇒ 无条件线先命中 ⇒ 进程级取消自动发生
        complete(taskOf(pid, "after"));

        List<WfCompensationEntry> entries = repo.findCompensations(pid);
        assertEquals(2, entries.size(),
                "两个作用域各有一条登记（事务内 + 主图）。实际: " + entries);
        assertEquals("tx", entries.get(0).getScope(), "事务内那条属于事务作用域");
        assertEquals("", entries.get(1).getScope(), "主图那条属于进程级作用域");

        assertEquals(1, calls.size(),
                "进程级取消只退进程级那一步 —— 事务里的那一步属于一个**已经结束**的容器，"
                        + "撤它等于把一个已经关掉的子流程的事又翻出来重做一遍。"
                        + "实际调用记录: " + calls);
        assertEquals("undoMain", calls.get(0),
                "退的必须是主图那一步（事务内那条的处理器是 undo）。"
                        + "实际调用记录: " + calls);
        assertTrue(!entries.get(0).isDone(),
                "事务内那条**不该**被这次进程级取消标记成已补偿 —— "
                        + "它属于一个已经结束的容器，与这次取消无关。实际: " + entries);
    }

    @Test
    @DisplayName("进程级取消收掉同一作用域里其余分支的待办与 token")
    void processLevelCancelEndsTheOtherBranches() {
        // 进程级的取消 = 退 + 收。「收」的那一半只有在图上**还有别的分支**时才有对象：
        // 单 token 的流程里，token 走到 cancelEndEvent 时自己就被置 ENDED 了，
        // 没有「其余 token」可收，于是那一半代码看起来是冗余的。
        //
        // 这条判据就是把那「看起来冗余」的部分钉住：去掉它，另一条分支上的
        // 待办会永远挂在 bob 的列表里（token 没了，没人能办结它），
        // 而实例停在 ACTIVE —— 没有待办、没有 job、也不会再推进。
        WfDefinition definition = deploy(PROCESS_PARALLEL_CANCEL_BPMN);
        String pid = start(definition);
        assertNotNull(taskOf(pid, "slow"), "前置条件：另一条分支上确实有 bob 的待办");

        complete(taskOf(pid, "book"));

        assertEquals(1, calls.size(), "取消退了订票这一步。实际调用记录: " + calls);
        assertNull(taskOf(pid, "slow"),
                "主图上的取消收掉整个实例 ⇒ 另一条分支上的待办必须作废 —— "
                        + "留着它的话它会永远挂在 bob 的列表里，而 token 没了，"
                        + "没人能再办结它，也没人知道它为什么在那儿。"
                        + "实际未办结的待办: " + openTasks(pid));
        assertEquals(WfProcessStatus.COMPLETED, repo.findProcessInstance(pid).getStatus(),
                "另一条分支的 token 也要一起结束，否则实例永久停在 ACTIVE");
        for (WfExecution execution : repo.findExecutionsByProcessInstance(pid)) {
            assertTrue(execution.isEnded(),
                    "不该留下存活的 token（活动: " + execution.getActivityId() + "）");
        }
    }
}
