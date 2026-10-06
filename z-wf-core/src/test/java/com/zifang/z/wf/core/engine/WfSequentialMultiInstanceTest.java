package com.zifang.z.wf.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;

/**
 * 多实例的 {@code collection} 集合迭代与 {@code isSequential} 串行。
 *
 * <p>这两项此前是"部署期报 ERROR"（{@link WfMultiInstanceTest#missingCardinalityIsRejected}
 * 之外的另两条负向用例已改成互斥校验）。解除之后风险有两处，都不报错：
 * <ul>
 *   <li><b>串行办完第一个就往下走</b>。办结一个实例之后、下一个还没建的那一刻，
 *       库里的"在办任务数"就是 0 —— 而并行的收口判定恰恰是"在办为 0 就收口"。
 *       套过去的结果是逐级审批直接跳到最后一关，且一切看起来正常。</li>
 *   <li><b>集合取不到当成空集合</b>。空集合让流程"跳过这个会签节点"往下走，
 *       与"确实没人要批"在外部表现上完全一样。</li>
 * </ul>
 * 所以每条用例都断言<b>具体的待办集合</b>（谁、在办还是作废），
 * 断言"流程往下走了"对这两项几乎没有区分力。
 */
class WfSequentialMultiInstanceTest {

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
    }

    /**
     * start → 多实例 userTask → afterSign → end。
     *
     * @param sequential  是否逐个串行
     * @param cardinality 写在 loopCardinality 里的个数；为 null 时改用 collection
     * @param element     elementVariable 的变量名；为 null 时不绑元素
     */
    private String bpmn(boolean sequential, String cardinality, String element,
                        String completionCondition) {
        StringBuilder loop = new StringBuilder();
        loop.append("      <multiInstanceLoopCharacteristics");
        if (sequential) {
            loop.append(" isSequential=\"true\"");
        }
        loop.append(">\n");
        if (cardinality != null) {
            loop.append("        <loopCardinality>").append(cardinality)
                    .append("</loopCardinality>\n");
        } else {
            loop.append("        <collection>${approvers}</collection>\n");
        }
        if (element != null) {
            loop.append("        <elementVariable>").append(element)
                    .append("</elementVariable>\n");
        }
        if (completionCondition != null) {
            loop.append("        <completionCondition>").append(completionCondition)
                    .append("</completionCondition>\n");
        }
        loop.append("      </multiInstanceLoopCharacteristics>\n");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"seqProcess\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                // 办理人来源随写法而变：走 collection 时元素本身派单（loopAssignee 由
                // 引擎从元素取），走 loopCardinality 时唯一的来源是 zifang:loopAssignees，
                // 没有它每个实例都拿不到办理人 —— 那是既有行为，不是本轮要改的
                + "    <userTask id=\"sign\" name=\"逐级审批\""
                + " zifang:assignee=\"${loopAssignee}\""
                + (cardinality == null ? "" : " zifang:loopAssignees=\"${approvers}\"")
                + ">\n"
                + loop
                + "    </userTask>\n"
                + "    <userTask id=\"afterSign\" name=\"办完了\" zifang:assignee=\"ops\"/>\n"
                + "    <endEvent id=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"sign\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"sign\" targetRef=\"afterSign\"/>\n"
                + "    <sequenceFlow id=\"f3\" sourceRef=\"afterSign\" targetRef=\"e1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
    }

    private String start(String xml, Map<String, Object> vars) {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(xml));
        return runtime.startProcessInstance(definition, "seq-" + System.nanoTime(),
                "alice", null, vars);
    }

    private Map<String, Object> approvers(String... names) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("approvers", new ArrayList<>(Arrays.asList(names)));
        return vars;
    }

    private List<WfTask> openAt(String pid, String nodeId) {
        List<WfTask> all = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(50));
        List<WfTask> result = new ArrayList<>();
        for (WfTask t : all) {
            if (t.isOpen() && nodeId.equals(t.getDefinitionId())) {
                result.add(t);
            }
        }
        return result;
    }

    /** 该节点上全部任务（含已办结），按办理人排序 —— 断言"谁办过、谁被作废"用。 */
    private List<String> historyAt(String pid, String nodeId) {
        List<WfTask> all = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(50));
        List<String> result = new ArrayList<>();
        for (WfTask t : all) {
            if (nodeId.equals(t.getDefinitionId())) {
                result.add(t.getAssignee() + ":" + t.getStatus());
            }
        }
        java.util.Collections.sort(result);
        return result;
    }

    private void completeOwn(WfTask task) {
        runtime.completeTask(task.getId(), task.getAssignee(), "同意", new HashMap<>());
    }

    private WfProcessInstance instanceOf(String pid) {
        return repo.findProcessInstance(pid);
    }

    // ==================== collection 集合迭代 ====================

    @Test
    @DisplayName("collection：实例数由集合大小决定，不再由作者写死")
    void collectionSizeDecidesInstanceCount() {
        String pid = start(bpmn(false, null, "approver", null),
                approvers("alice", "bob", "carol", "dave"));
        List<WfTask> open = openAt(pid, "sign");
        assertEquals(4, open.size(), "集合 4 个人就该展开 4 个实例。实际 " + assignees(open));
        assertEquals(Arrays.asList("alice", "bob", "carol", "dave"), assignees(open),
                "每个实例派给集合里对应的那个人");
    }

    @Test
    @DisplayName("collection：空集合按 BPMN 语义跳过该节点，不是卡住也不是报错")
    void emptyCollectionSkipsTheNode() {
        String pid = start(bpmn(false, null, "approver", null), approvers());
        assertTrue(openAt(pid, "sign").isEmpty(), "没有人要批，不该建出待办");
        assertEquals(1, openAt(pid, "afterSign").size(),
                "空集合的 BPMN 语义是这一步直接完成，流程应当已经走到下一节点");
    }

    @Test
    @DisplayName("collection 取不到值必须停成内部终止，而不是当成空集合放行")
    void unresolvableCollectionFailsClosed() {
        Map<String, Object> vars = new HashMap<>();   // 故意不给 approvers
        String pid = start(bpmn(false, null, "approver", null), vars);
        WfProcessInstance instance = instanceOf(pid);
        assertEquals(WfProcessStatus.INTERNALLY_TERMINATED, instance.getStatus(),
                "取不到人 ≠ 没有人。放行的后果是这一步被静默跳过，"
                        + "而外部看到的只是「审批已经过了」");
        assertTrue(String.valueOf(instance.getDeleteReason()).contains("collection"),
                "要把原因写进实例。实际 " + instance.getDeleteReason());
        assertTrue(openAt(pid, "afterSign").isEmpty(),
                "取不到集合时绝不能往下走 —— 那是「跳过审批」");
    }

    @Test
    @DisplayName("collection 指向的不是集合时报错，不按单元素集合凑合")
    void collectionMustBeACollection() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("approvers", "alice,bob");
        String pid = start(bpmn(false, null, "approver", null), vars);
        WfProcessInstance instance = instanceOf(pid);
        assertEquals(WfProcessStatus.INTERNALLY_TERMINATED, instance.getStatus(),
                "字符串不是集合。按字符拆开当集合会让实例数变成字符数，且无人报错");
        assertTrue(String.valueOf(instance.getDeleteReason()).contains("必须是集合"),
                "实际 " + instance.getDeleteReason());
    }

    @Test
    @DisplayName("elementVariable 绑的是元素原值，loopAssignee 绑的是它的字符串形式")
    void elementVariableKeepsTheOriginalObject() {
        // 集合里放对象：绑原值才能读到它的字段
        Map<String, Object> vars = new HashMap<>();
        List<Map<String, Object>> people = new ArrayList<>();
        Map<String, Object> alice = new HashMap<>();
        alice.put("id", "alice");
        people.add(alice);
        Map<String, Object> bob = new HashMap<>();
        bob.put("id", "bob");
        people.add(bob);
        vars.put("approvers", people);

        String pid = start(bpmn(false, null, "approver", null).replace(
                        "${loopAssignee}", "${approver.id}"),
                vars);
        assertEquals(Arrays.asList("alice", "bob"), assignees(openAt(pid, "sign")),
                "办理表达式引用了元素的字段，说明绑的是原值而不是它的 toString");
    }

    // ==================== isSequential 串行 ====================

    @Test
    @DisplayName("串行：任何时刻只有一条待办，办完一个才出现下一个")
    void sequentialRunsOneAtATime() {
        String pid = start(bpmn(true, "3", null, null), approvers("alice", "bob", "carol"));

        assertEquals(1, openAt(pid, "sign").size(), "串行的定义就是同一时刻只有一个人在办");
        assertEquals("alice", openAt(pid, "sign").get(0).getAssignee());

        completeOwn(openAt(pid, "sign").get(0));
        assertEquals(1, openAt(pid, "sign").size(), "第一个办完，第二个接上");
        assertEquals("bob", openAt(pid, "sign").get(0).getAssignee());

        completeOwn(openAt(pid, "sign").get(0));
        assertEquals(1, openAt(pid, "sign").size());
        assertEquals("carol", openAt(pid, "sign").get(0).getAssignee());

        completeOwn(openAt(pid, "sign").get(0));
        assertTrue(openAt(pid, "sign").isEmpty(), "三个都办完，没有人可办了");
        assertEquals(1, openAt(pid, "afterSign").size(),
                "最后一个办完就该往下走 —— 少走一步是最难发现的串行 bug");
    }

    @Test
    @DisplayName("串行：办完第一个不能就往下走（这正是并行判定误用后的症状）")
    void sequentialDoesNotAdvanceAfterTheFirstOne() {
        String pid = start(bpmn(true, "3", null, null), approvers("alice", "bob", "carol"));
        completeOwn(openAt(pid, "sign").get(0));
        assertTrue(openAt(pid, "afterSign").isEmpty(),
                "还剩两个人没批，流程必须停在这一步。"
                        + "库里的在办任务此刻确实是 0，但那是「下一个还没建」，不是「都办完了」");
    }

    @Test
    @DisplayName("串行：办结历史按顺序留痕，被跳过的人一个都不该有待办")
    void sequentialLeavesOneTaskPerPersonInOrder() {
        String pid = start(bpmn(true, null, "approver", null),
                approvers("alice", "bob", "carol"));
        List<WfTask> open = openAt(pid, "sign");
        assertEquals(1, open.size(), "串行下同时只有一条待办");
        completeOwn(open.get(0));
        completeOwn(openAt(pid, "sign").get(0));
        completeOwn(openAt(pid, "sign").get(0));

        assertEquals(Arrays.asList("alice:COMPLETED", "bob:COMPLETED", "carol:COMPLETED"),
                historyAt(pid, "sign"),
                "三个人各办过一条。不该出现第四条待办，也不该有人被作废 —— "
                        + "串行不并行，不存在「没轮到就作废」");
    }

    @Test
    @DisplayName("串行 + collection：个数按进入时冻结，中途变长不再加签")
    void plannedTotalIsFrozenAtEntry() {
        String pid = start(bpmn(true, null, "approver", null), approvers("alice", "bob"));
        assertEquals(1, openAt(pid, "sign").size());
        assertEquals("alice", openAt(pid, "sign").get(0).getAssignee());

        // 审批过程中把第三个人加进集合
        WfProcessInstance instance = instanceOf(pid);
        instance.getVariables().put("approvers",
                new ArrayList<>(Arrays.asList("alice", "bob", "carol")));
        // 乐观锁：外部直接改实例也要自增版本号，否则被正确地拒掉
        instance.nextRevision();
        repo.saveProcessInstance(instance);

        completeOwn(openAt(pid, "sign").get(0));
        assertEquals("bob", openAt(pid, "sign").get(0).getAssignee(),
                "按进入时的 2 个人走，中途加的人不加签");
        completeOwn(openAt(pid, "sign").get(0));
        assertEquals(1, openAt(pid, "afterSign").size(),
                "2 个人办完就往下走 —— 集合变成 3 个人也不改变已经定下的个数");
        assertTrue(!historyAt(pid, "sign").toString().contains("carol"),
                "carol 从头到尾没参与过，不该出现在这个节点的办结历史里。实际 "
                        + historyAt(pid, "sign"));
    }

    @Test
    @DisplayName("串行：集合中途变短要报错停住，不能悄悄少办几个")
    void shrunkenCollectionFailsLoudly() {
        String pid = start(bpmn(true, null, "approver", null),
                approvers("alice", "bob", "carol"));
        completeOwn(openAt(pid, "sign").get(0));

        WfProcessInstance instance = instanceOf(pid);
        instance.getVariables().put("approvers", new ArrayList<>(Arrays.asList("alice")));
        instance.nextRevision();
        repo.saveProcessInstance(instance);

        completeOwn(openAt(pid, "sign").get(0));
        WfProcessInstance after = instanceOf(pid);
        assertEquals(WfProcessStatus.INTERNALLY_TERMINATED, after.getStatus(),
                "实例数在进入时定了 3 个，现在取不到第 3 个 —— "
                        + "悄悄收口的后果是「以为三个人都批了，其实只批了两个」");
        assertTrue(String.valueOf(after.getDeleteReason()).contains("越界"),
                "实际 " + after.getDeleteReason());
        assertTrue(openAt(pid, "afterSign").isEmpty(), "出错时绝不能往下走");
    }

    @Test
    @DisplayName("串行 + 完成条件：条件成立就收口，后面的实例不建也不作废")
    void sequentialHonoursCompletionCondition() {
        String pid = start(bpmn(true, "3", null, "${nrOfCompletedInstances >= 2}"),
                approvers("alice", "bob", "carol"));
        completeOwn(openAt(pid, "sign").get(0));
        assertEquals("bob", openAt(pid, "sign").get(0).getAssignee(),
                "第一个办完，条件还不成立，继续第二个");

        completeOwn(openAt(pid, "sign").get(0));
        assertEquals(1, openAt(pid, "afterSign").size(),
                "两人批完即收口，不该等第三个人");
        assertEquals(Arrays.asList("alice:COMPLETED", "bob:COMPLETED"),
                historyAt(pid, "sign"),
                "第三个人从来没被建出来过，所以既没有待办也没有作废记录 —— "
                        + "串行下不建就是不建，与并行的「建了再作废」不同");
    }

    @Test
    @DisplayName("串行 + collection：循环变量随实例推进，元素不会粘在上一轮")
    void loopVariablesAdvancePerInstance() {
        String pid = start(bpmn(true, null, "approver", null),
                approvers("alice", "bob"));
        WfExecution token = tokenAt(pid);
        assertEquals(0, counterOf(token), "第一个实例的 loopCounter 是 0");
        assertEquals("alice", String.valueOf(token.getVariables().get("approver")),
                "元素绑在 token 的局部变量上");

        completeOwn(openAt(pid, "sign").get(0));
        WfExecution second = tokenAt(pid);
        assertEquals(1, counterOf(second), "同一个 token 推进到第 2 个实例");
        assertEquals("bob", String.valueOf(second.getVariables().get("approver")),
                "第 2 个实例必须看到自己的元素，而不是继续看到 alice");
        assertEquals("bob", openAt(pid, "sign").get(0).getAssignee());
    }

    @Test
    @DisplayName("串行复用同一条 token，不会越积越多")
    void sequentialReusesOneToken() {
        String pid = start(bpmn(true, "3", null, null), approvers("alice", "bob", "carol"));
        assertEquals(1, aliveAt(pid, "sign").size(), "串行下这个节点上永远只有一条活跃 token");
        completeOwn(openAt(pid, "sign").get(0));
        assertEquals(1, aliveAt(pid, "sign").size(), "办完一个再用一个，不叠加");
        completeOwn(openAt(pid, "sign").get(0));
        assertEquals(1, aliveAt(pid, "sign").size());
        completeOwn(openAt(pid, "sign").get(0));
        assertTrue(aliveAt(pid, "sign").isEmpty(), "全部办完，token 离开这个节点");
        assertEquals(1, openAt(pid, "afterSign").size(), "且只有一条后续路径");
    }

    @Test
    @DisplayName("串行 + 数量为 0（loopCardinality=0）直接跳过，不建待办")
    void zeroCardinalitySkips() {
        String pid = start(bpmn(true, "0", null, null), approvers());
        assertTrue(openAt(pid, "sign").isEmpty());
        assertEquals(1, openAt(pid, "afterSign").size());
    }

    @Test
    @DisplayName("串行：运行期算出的实例数超上限时停住，不是一个一个慢慢造")
    void overLimitFailsOnEntry() {
        // 刻意用**表达式**：字面量超限在部署期就被挡了（见 WfMultiInstanceTest），
        // 那条闸门走不到运行期。这里要验的是运行期那条 —— 人数来自变量，
        // 部署期无从知道
        Map<String, Object> vars = new HashMap<>();
        vars.put("approvers", new ArrayList<>(Arrays.asList("alice")));
        vars.put("bigNumber", 99999999);
        String pid = start(bpmn(true, "${bigNumber}", null, null), vars);
        WfProcessInstance instance = instanceOf(pid);
        assertEquals(WfProcessStatus.INTERNALLY_TERMINATED, instance.getStatus(),
                "运行期算出的个数超限必须直接停");
        assertTrue(String.valueOf(instance.getDeleteReason()).contains("超过上限"),
                "实际 " + instance.getDeleteReason());
        assertTrue(openAt(pid, "sign").isEmpty(), "不许已经建出一堆待办才停");
    }

    @Test
    @DisplayName("collection 指向的集合超过上限时同样停住")
    void oversizedCollectionFailsOnEntry() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            many.add("user" + i);
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("approvers", many);
        String pid = start(bpmn(false, null, "approver", null), vars);
        WfProcessInstance instance = instanceOf(pid);
        assertEquals(WfProcessStatus.INTERNALLY_TERMINATED, instance.getStatus(),
                "集合 250 个元素超过上限 200，必须停 —— "
                        + "否则一次性造出 250 条待办与 token");
        assertTrue(openAt(pid, "sign").isEmpty(), "超限时一条待办都不该留下");
    }

    @Test
    @DisplayName("非串行的老写法（loopCardinality + loopAssignees）行为不变")
    void legacyLoopAssigneesStillWorks() {
        String pid = start(bpmn(false, "3", null, null), approvers("alice", "bob", "carol"));
        assertEquals(3, openAt(pid, "sign").size(), "老写法是并行的 3 条待办");
        assertEquals(Arrays.asList("alice", "bob", "carol"), assignees(openAt(pid, "sign")));
    }

    @Test
    @DisplayName("老写法里列表比实例数短时，后面的实例没有办理人（既有行为不变）")
    void legacyShorterListLeavesInstancesWithoutAssignee() {
        String pid = start(bpmn(false, "3", null, null), approvers("alice"));
        List<WfTask> open = openAt(pid, "sign");
        assertEquals(3, open.size(), "实例数由 loopCardinality 定，仍是 3 个");
        assertEquals(2, openWithoutAssignee(open).size(),
                "列表只有 1 个人，第 2、3 个实例没有办理人 —— 这是老写法的既有行为，"
                        + "本轮不改：改了就等于偷偷改变已上线流程的派单结果。实际 "
                        + assignees(open));
        // 不假设派到 alice 的一定排在第 0 条：queryTasks 的返回顺序与实例序号无关，
        // 断言"某一个下标"是在断言一条与本用例无关的性质
        assertEquals(Arrays.asList("alice"),
                assigneesOfNonNull(open),
                "唯一拿到办理人的那条只能是 alice");
    }

    @Test
    @DisplayName("collection / elementVariable 过了存储往返之后仍然生效（不靠内存里那个对象）")
    void collectionSurvivesDefinitionRoundTrip() {
        // 刻意**从持久化把定义读回来再启动**。
        // 直接拿 deploy 时传进去的那个 WfDefinition 对象启动，codec 一次都没跑到 ——
        // 而 codec 是手写 DTO 的逐字段拷贝，漏一个字段编译期不报错。
        // 上一轮就为这件事栽过：测试全绿，JDBC 部署的流程里那个特性静默不生效
        WfDefinition deployed = repository.deploy(new WfXmlParser().parse(
                bpmn(false, null, "approver", null).replace(
                        "${loopAssignee}", "${approver.id}")));
        WfDefinition reloaded = repo.findDefinition(deployed.getKey(), deployed.getVersion());
        assertNotNull(reloaded, "定义应能从持久化读回来");
        assertEquals("${approvers}", reloaded.node("sign").getLoopCollection(),
                "读回来的定义上 collection 必须还在 —— 它决定实例数，"
                        + "丢了就退化成「既无 loopCardinality 也无 collection」");

        Map<String, Object> vars = new HashMap<>();
        List<Map<String, Object>> people = new ArrayList<>();
        for (String id : new String[]{"alice", "bob"}) {
            Map<String, Object> p = new HashMap<>();
            p.put("id", id);
            people.add(p);
        }
        vars.put("approvers", people);

        String pid = runtime.startProcessInstance(reloaded, "seq-" + System.nanoTime(),
                "alice", null, vars);
        assertEquals(Arrays.asList("alice", "bob"), assignees(openAt(pid, "sign")),
                "用读回来的定义启动，集合迭代与元素绑定都应当照常生效");
    }

    // ==================== 辅助 ====================

    /** 办理人列表，null 排在最后 —— 办不成签的实例没有办理人，而 sort 会因 null 抛 NPE。 */
    private List<String> assignees(List<WfTask> tasks) {
        List<String> names = new ArrayList<>();
        for (WfTask t : tasks) {
            names.add(t.getAssignee());
        }
        java.util.Collections.sort(names, (a, b) -> {
            if (a == null) {
                return b == null ? 0 : 1;
            }
            return b == null ? -1 : a.compareTo(b);
        });
        return names;
    }

    private List<WfTask> openWithoutAssignee(List<WfTask> tasks) {
        List<WfTask> result = new ArrayList<>();
        for (WfTask t : tasks) {
            if (t.getAssignee() == null || t.getAssignee().trim().isEmpty()) {
                result.add(t);
            }
        }
        return result;
    }

    /** 有办理人的那些（升序）。 */
    private List<String> assigneesOfNonNull(List<WfTask> tasks) {
        List<String> names = new ArrayList<>();
        for (WfTask t : tasks) {
            if (t.getAssignee() != null && !t.getAssignee().trim().isEmpty()) {
                names.add(t.getAssignee());
            }
        }
        java.util.Collections.sort(names);
        return names;
    }

    private WfExecution tokenAt(String pid) {
        List<WfExecution> all = repo.findExecutionsByProcessInstance(pid);
        for (WfExecution e : all) {
            if (!e.isEnded() && "sign".equals(e.getActivityId())) {
                return e;
            }
        }
        throw new AssertionError("流程 " + pid + " 上没有停在 sign 的 token");
    }

    private int counterOf(WfExecution token) {
        Object counter = token.getVariables().get(WfMultiInstance.LOOP_COUNTER);
        assertNotNull(counter, "串行实例的 token 上必须带 loopCounter");
        return ((Number) counter).intValue();
    }

    private List<WfExecution> aliveAt(String pid, String nodeId) {
        List<WfExecution> result = new ArrayList<>();
        for (WfExecution e : repo.findExecutionsByProcessInstance(pid)) {
            if (!e.isEnded() && nodeId.equals(e.getActivityId())) {
                result.add(e);
            }
        }
        return result;
    }
}
