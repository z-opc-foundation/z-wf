package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.view.WfActivityInstanceView;
import com.zifang.z.wf.core.view.WfTransitionInstanceView;

/**
 * 活动实例树 —— "这条单现在走到哪了，并发分支在哪，各分支停在哪一步"。
 *
 * <p>这组用例盯的是<b>树的形状与真实数据对得上</b>。树是最容易画错的形状：
 * 少一层、多一层、把两条分支并成一条，看上去都还是一棵"树"，
 * 而排障的人只会照着它去找原因 —— 一棵形状错了的树比没有树更坏。
 *
 * <p><b>本类的断言全部来自实测，不是推的。</b>写之前先跑了一个临时探针把引擎
 * 实际产出的树打出来，而它当场推翻了本类注释里的两条说法：
 * <ol>
 *   <li><b>并行网关 fork 出来的是父子链，不是兄弟。</b>
 *       并行网关的第一条出线留在父 token 上继续走，其余出线另起子 token，
 *       所以两条并行分支的 activityId 天然不同。
 *       ⇒ 判"有没有并行"不能看"同一父下有几个兄弟"，那一位永远是 false；
 *       实测取的是<b>未结束 token 总数 &gt; 1</b>。</li>
 *   <li><b>{@code arrivedActivities} 回答不了「join 在等谁」。</b>
 *       fork 出来的子 token，它的 arrived 里<b>不含那个并行网关</b>，
 *       所以从 join 那条 token 自己的 arrived 看不到还差哪条分支。
 *       ⇒ 那个答案在树本身：另有一条 token 还停在别的节点上没结束。</li>
 * </ol>
 */
class WfActivityInstanceTreeTest {

    /** 串行两级审批。 */
    private static final String SEQ_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"treeSeq\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <userTask id=\"t1\" name=\"一级\" zifang:assignee=\"alice\"/>\n"
            + "    <userTask id=\"t2\" name=\"二级\" zifang:assignee=\"bob\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t1\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"t2\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"t2\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 并行两支并行汇合。 */
    private static final String PAR_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"treePar\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <parallelGateway id=\"pg\"/>\n"
            + "    <userTask id=\"a1\" name=\"甲\" zifang:assignee=\"alice\"/>\n"
            + "    <userTask id=\"b1\" name=\"乙\" zifang:assignee=\"bob\"/>\n"
            + "    <parallelGateway id=\"jg\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"pg\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"pg\" targetRef=\"a1\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"pg\" targetRef=\"b1\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"a1\" targetRef=\"jg\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"b1\" targetRef=\"jg\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"jg\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfActivityInstanceService tree;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
        tree = new WfActivityInstanceService(repo, repository);
    }

    // ==================== 串行：一条 token 连续前进 ====================

    @Test
    @DisplayName("串行单步：树是根 + 一条 token，token 上只挂已离开过的那一步")
    void sequentialTreeShape() {
        String pid = startSeq("SEQ-1");
        WfActivityInstanceView root = tree.getActivityInstance(pid);

        assertEquals("process:" + pid, root.getId());
        assertEquals("process", root.getActivityType());
        assertEquals("ACTIVE", root.getExecutionState());
        assertEquals(1, root.getChildActivityInstances().size(),
                "串行单步只有一条 token");

        WfActivityInstanceView node = root.getChildActivityInstances().get(0);
        assertEquals("t1", node.getActivityId());
        assertEquals("一级", node.getActivityName(), "节点名要能从定义里解析出来");
        assertEquals("userTask", node.getActivityType());
        assertEquals("WAITING", node.getExecutionState(), "停在人工节点上是 WAITING 不是 ACTIVE");
        assertNotNull(node.getEnteredTime(), "停在节点上的时间必须给出，否则排障看不出等了多久");
        assertFalse(node.isConcurrent(), "只有一条 token，不算并发");

        assertEquals(1, node.getChildTransitionInstances().size(),
                "只挂离开过的那一步；当前停着的 t1 不在其中（它还没离开）");
        WfTransitionInstanceView start = node.getChildTransitionInstances().get(0);
        assertEquals("s", start.getActivityId());
        assertEquals("startEvent", start.getActivityType());
        assertEquals(node.getId(), start.getParentActivityInstanceId(),
                "步骤要指回它属于哪条 token，否则调用方拿到一个扁平列表无从分组");
        assertEquals(node.getId(), start.getExecutionId(),
                "步骤来自历史行，不该被重新编号 —— 同一个 executionId 才能和 token 对上");
    }

    @Test
    @DisplayName("串行推进是同一条 token 往前走，不是每步新建一条")
    void sequentialAdvancesSameToken() {
        String pid = startSeq("SEQ-2");
        String tokenBefore = tree.getActivityInstance(pid)
                .getChildActivityInstances().get(0).getId();

        complete(pid, "alice", "一级批了");

        WfActivityInstanceView node = tree.getActivityInstance(pid)
                .getChildActivityInstances().get(0);
        assertEquals(tokenBefore, node.getId(),
                "本仓一个 token 连续穿过多个节点（leave 是把同一个 token 的 activityId 往前挪）；"
                        + "若每步新建 token，树会越滚越深，而这与执行树的实际形状不符");
        assertEquals("t2", node.getActivityId());

        List<WfTransitionInstanceView> transitions = node.getChildTransitionInstances();
        assertEquals(2, transitions.size(), "s 与 t1 两步都该挂上");
        assertEquals("s", transitions.get(0).getActivityId());
        assertEquals("t1", transitions.get(1).getActivityId());
        assertEquals("一级批了", transitions.get(1).getOutcome(),
                "步骤的结论直接来自历史，不该被推导或改写");
        assertEquals("alice", transitions.get(1).getAssignee());

        assertEquals("[s, t1, t2]", node.getArrivedActivities().toString(),
                "arrived 是这条 token 自己走过的节点；它回答不了「join 在等谁」—— 见类注释");
    }

    // ==================== 并行：fork 是父子链 ====================

    @Test
    @DisplayName("并行分支是父子链而不是兄弟，且要标出「有并发」")
    void parallelBranchesAreParentChildChain() {
        String pid = startPar("PAR-1");
        WfActivityInstanceView root = tree.getActivityInstance(pid);

        assertEquals(1, root.getChildActivityInstances().size(),
                "并行网关的第一条出线留在父 token 上，所以根下只有一条，"
                        + "第二条是它的子 token —— 按兄弟数去数会得出「没有并发」的错误结论");

        WfActivityInstanceView parent = root.getChildActivityInstances().get(0);
        assertEquals("a1", parent.getActivityId(), "父 token 走并行网关的第一条出线");
        assertEquals(1, parent.getChildActivityInstances().size());

        WfActivityInstanceView child = parent.getChildActivityInstances().get(0);
        assertEquals("b1", child.getActivityId());
        assertEquals(parent.getId(), child.getParentActivityInstanceId());
        assertTrue(child.getChildActivityInstances().isEmpty(), "叶子节点没有子节点");

        assertTrue(parent.isConcurrent(), "两条 token 都没结束 ⇒ 这一单有并行在跑");
        assertTrue(child.isConcurrent(), "并发标志要标在每一个节点上，"
                + "只标根的话前端展开子节点时看不到");
        assertTrue(root.isConcurrent(), "根上也要标：只看根才知道这单有没有并行");

        // 子 token 是在并行网关处 fork 出来的，它**没走过起始节点** ——
        // 起始与并行网关那两步都记在父 token 上。它的步骤表此刻为空。
        assertEquals(0, child.getChildTransitionInstances().size(),
                "fork 出来的子 token 从未经过起始节点，步骤表必须为空；"
                        + "实际挂上来的是 " + child.getChildTransitionInstances());
        assertEquals(1, parent.getChildTransitionInstances().size());
        assertEquals("s", parent.getChildTransitionInstances().get(0).getActivityId());
    }

    @Test
    @DisplayName("父指针与 children 必须双向自洽 —— 只填一边等于树是断的")
    void parentPointerAndChildrenAgree() {
        String pid = startPar("PAR-2");
        WfActivityInstanceView root = tree.getActivityInstance(pid);

        List<WfActivityInstanceView> all = flatten(root);
        for (WfActivityInstanceView node : all) {
            for (WfActivityInstanceView child : node.getChildActivityInstances()) {
                assertEquals(node.getId(), child.getParentActivityInstanceId(),
                        "children 说 b 的父亲是 a，parentActivityInstanceId 却不是同一个 —— "
                                + "调用方没法确定该信哪一个");
            }
        }
        // 根的父必须为空：填一个"看起来像"的值会让调用方拿它去 join 历史行，而 join 不上
        assertNull(root.getParentActivityInstanceId());
        assertNull(root.getExecutionId(), "根不是一条 token，executionId 必须留空而不是编一个");
        assertEquals(3, all.size(), "根 + 两条 token");
    }

    @Test
    @DisplayName("汇合没收齐时，两条分支各停在哪、join 停在哪，都要看得出来")
    void joinWaitingShowsBothBranches() {
        String pid = startPar("PAR-3");
        WfTask a = openTask(pid, "alice");
        runtime.completeTask(a.getId(), "alice", "甲批了", new HashMap<String, Object>());

        WfActivityInstanceView root = tree.getActivityInstance(pid);
        WfActivityInstanceView parent = root.getChildActivityInstances().get(0);
        WfActivityInstanceView child = parent.getChildActivityInstances().get(0);

        assertEquals("jg", parent.getActivityId(), "甲办完后父 token 走到汇合点等着");
        assertEquals("parallelGateway", parent.getActivityType(),
                "网关也要能解析出名字与类型：token 停在网关上等出线是真实存在的状态，"
                        + "类型留空会让排障的人以为这不是节点");
        assertEquals("b1", child.getActivityId());
        assertTrue(parent.isConcurrent() && child.isConcurrent(),
                "两条都没结束，仍是并发");
        assertEquals("WAITING", parent.getExecutionState());
        assertEquals("WAITING", child.getExecutionState());

        // 「join 在等谁」的答案在树上：另有一条 token 还停在别的节点上没结束。
        // 这一条是刻意断言 child 的 arrived **不含** jg —— 它只走自己的路。
        assertFalse(child.getArrivedActivities().contains("jg"),
                "子 token 的 arrived 不含并行网关，这是实测行为；"
                        + "拿它去回答「join 在等谁」是错的");
    }

    // ==================== 结束了也还有树 ====================

    @Test
    @DisplayName("流程结束后树仍然完整 —— token 连同 parentId 都留在库里")
    void finishedProcessStillHasTree() {
        String pid = startPar("PAR-4");
        complete(pid, "alice", "甲批了");
        complete(pid, "bob", "乙批了");

        WfActivityInstanceView root = tree.getActivityInstance(pid);
        assertEquals("COMPLETED", root.getExecutionState());

        List<WfActivityInstanceView> all = flatten(root);
        assertEquals(3, all.size(), "流程结束后 token 仍在，并发结构仍可读");
        boolean allEnded = true;
        int transitions = 0;
        for (WfActivityInstanceView node : all) {
            if (node.getExecutionId() != null) {
                allEnded = allEnded && "ENDED".equals(node.getExecutionState());
            }
            transitions += node.getChildTransitionInstances().size();
        }
        assertTrue(allEnded, "流程结束后每条 token 都该是 ENDED");
        assertEquals(4, transitions, "s + a1 + b1 + e 四步都在树里");

        // **必须逐条断言归属，不能只数总数。** 只断「一共四步」的话，
        // 「把所有步骤挂到同一条 token 上」这种写坏归组的实现照样通过 ——
        // 它只是把四步换了个人保管，总数没变。
        // 而只有当**两条 token 都各自有步骤**时，归组才有得可错：
        // 刚 fork 出来时子 token 一步都没有（它在网关处就被创建了），
        // 那时"挂错 token"根本观察不出来 —— 判据的数据必须造出这个差异。
        WfActivityInstanceView a = root.getChildActivityInstances().get(0);
        WfActivityInstanceView b = a.getChildActivityInstances().get(0);
        assertEquals("[s, a1]", activityIdsOf(a),
                "父 token 走的是起始与甲；把乙或结束挂上来就是归组写坏了");
        assertEquals("[b1, e]", activityIdsOf(b),
                "子 token 走的是乙与结束 —— 它 fork 出来时一步都没有，"
                        + "所以此刻它是**唯一能验出归组写坏的那条**");
        assertFalse(root.isConcurrent(), "没有未结束的 token 了，并发标志应当回落");
    }

    // ==================== 坏数据要报错，不静默 ====================

    @Test
    @DisplayName("父 token 不在库里时必须报错 —— 静默丢弃会让分支凭空消失")
    void orphanTokenFailsLoudly() {
        String pid = startPar("PAR-5");
        String childId = repo.findExecutionsByProcessInstance(pid).stream()
                .filter(e -> e.getParentId() != null)
                .map(WfExecution::getId)
                .findFirst()
                .orElse(null);
        assertNotNull(childId, "前置条件：确实有一条子 token");

        // 造出「父 token 被删、子 token 还在」的破损树：
        // 现实里等价于并发删除或迁移漏了一步
        repo.deleteExecution(
                repo.findExecutionsByProcessInstance(pid).stream()
                        .filter(e -> e.getParentId() == null)
                        .map(WfExecution::getId)
                        .findFirst()
                        .orElse(null));

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> tree.getActivityInstance(pid));
        assertTrue(ex.getMessage().contains("执行令牌树不完整"),
                "树不完整必须说出来，否则拼出来的树会少掉分支且无人察觉。实际: "
                        + ex.getMessage());
        assertTrue(ex.getMessage().contains(childId),
                "报错要点名是哪几条令牌：" + ex.getMessage());
    }

    @Test
    @DisplayName("实例不存在要报错并点名历史清理，不能返回空树")
    void missingInstanceFailsLoudly() {
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> tree.getActivityInstance("no-such-process"));
        assertTrue(ex.getMessage().contains("流程实例不存在"),
                "空树会让调用方分不清「没跑起来」「被清过历史」「真的没有分支」: "
                        + ex.getMessage());
        assertTrue(ex.getMessage().contains("cleanup"),
                "最可能的成因是历史清理，要指路: " + ex.getMessage());

        assertTrue(assertThrows(WfEngineException.class,
                () -> tree.getActivityInstance(null)).getMessage()
                .contains("流程实例 id 不能为空"));
        assertTrue(assertThrows(WfEngineException.class,
                () -> tree.getActivityInstance("  ")).getMessage()
                .contains("流程实例 id 不能为空"));
    }

    @Test
    @DisplayName("定义读不出来要让异常往上抛，不能给一棵「节点全都没名字」的树")
    void missingDefinitionFailsLoudly() {
        String pid = startPar("PAR-6");
        // 物理删掉定义（仓储层没有这个入口，用持久层直接删，模拟定义被清）
        repo.deleteDefinition("treePar", 1);

        assertThrows(WfDefinitionException.class, () -> tree.getActivityInstance(pid));
    }

    // ==================== 夹具 ====================

    private String startSeq(String businessKey) {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(SEQ_BPMN));
        return runtime.startProcessInstance(definition, businessKey, null, null,
                new HashMap<String, Object>());
    }

    private String startPar(String businessKey) {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(PAR_BPMN));
        return runtime.startProcessInstance(definition, businessKey, null, null,
                new HashMap<String, Object>());
    }

    private WfTask openTask(String pid, String assignee) {
        for (WfTask task : repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(20))) {
            if (assignee.equals(task.getAssignee())) {
                return task;
            }
        }
        throw new IllegalStateException("流程 " + pid + " 上找不到 " + assignee + " 的待办");
    }

    private void complete(String pid, String assignee, String comment) {
        WfTask task = openTask(pid, assignee);
        runtime.completeTask(task.getId(), assignee, comment, new HashMap<String, Object>());
    }

    /** 某条 token 挂着的全部步骤的节点 id，按顺序。 */
    private static String activityIdsOf(WfActivityInstanceView node) {
        List<String> ids = new java.util.ArrayList<>();
        for (WfTransitionInstanceView transition : node.getChildTransitionInstances()) {
            ids.add(transition.getActivityId());
        }
        return ids.toString();
    }

    private static List<WfActivityInstanceView> flatten(WfActivityInstanceView root) {
        List<WfActivityInstanceView> all = new java.util.ArrayList<>();
        all.add(root);
        for (WfActivityInstanceView node : flattenChildren(root)) {
            all.add(node);
        }
        return all;
    }

    private static List<WfActivityInstanceView> flattenChildren(WfActivityInstanceView node) {
        List<WfActivityInstanceView> all = new java.util.ArrayList<>();
        for (WfActivityInstanceView child : node.getChildActivityInstances()) {
            all.add(child);
            all.addAll(flattenChildren(child));
        }
        return all;
    }
}