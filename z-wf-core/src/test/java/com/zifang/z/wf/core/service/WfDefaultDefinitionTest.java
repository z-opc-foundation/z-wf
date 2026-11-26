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
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfPersistence;

/**
 * 默认流程定义（对应 Camunda 的 {@code getDefaultProcessDefinition} / {@code setDefaultProcessDefinition}）。
 *
 * <p>存在的理由很朴素：没有它，「发起审批」这个入口就得把某个 key 写死在业务代码里，
 * 换流程要改代码重新发布。
 *
 * <p>本类盯六件错了都不报错的事：
 * <ol>
 *   <li><b>标记必须存得进、读得回</b>。只加一个 {@code defaultDefinition} 字段而不落库，
 *       就是一个"看着能用、重启即丢"的字段。</li>
 *   <li><b>全库至多一条默认</b>。设了新的要自动取消旧的 —— 留下两条时
 *       "默认是哪个"就没有答案，而调用方拿到的可能取决于行返回顺序。</li>
 *   <li><b>默认必须能启动</b>：设默认时拒掉已停用的版本。默认的用途就是
 *       "不知道 key 时也能发起一个"，指向一个起不来的定义只会把错误推迟到更远的地方。</li>
 *   <li><b>默认跟着具体版本走</b>，不跟着 key 的最新版本走 ——
 *       否则运营在默认流程上做的验证会被一次无关的重新部署改掉。</li>
 *   <li><b>没配默认是正常状态</b>，返回 {@code null} 而不是抛异常 ——
 *       入口页要能据此提示"还没配默认"，而不是收到一个 4xx。</li>
 *   <li><b>停用不带走默认标记</b>：取消默认是一次显式的运营决策，
 *       不该由"停用"顺手代办。</li>
 * </ol>
 *
 * <p>持久层语义在内存实现上跑，JDBC 那套（含补列迁移）由
 * {@code JdbcWorkflowPersistenceTest} 在 H2 上对拍。
 */
class WfDefaultDefinitionTest {

    private static final String LEAVE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"leaveProcess\" name=\"请假流程\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"领导审批\" zifang:assignee=\"boss\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 第二个流程，用来验「默认只有一个」。 */
    private static final String EXPENSE_BPMN = LEAVE_BPMN
            .replace("id=\"leaveProcess\" name=\"请假流程\"", "id=\"expenseProcess\" name=\"报销流程\"");

    private WfPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
    }

    private WfDefinition deploy(String xml, String key) {
        return repository.deployXml(xml, key);
    }

    private WfDefinition deployLeave() {
        return deploy(LEAVE_BPMN, "leaveProcess");
    }

    // ==================== 存得进、读得回 ====================

    @Test
    @DisplayName("设为默认后读得回来")
    void defaultMarkerRoundTrips() {
        WfDefinition definition = deployLeave();
        assertNull(repository.getDefaultDefinition(), "刚部署时不该有默认");

        repository.setDefaultDefinition("leaveProcess", definition.getVersion());

        WfDefinition found = repository.getDefaultDefinition();
        assertNotNull(found, "设为默认后必须读得回来 —— 读不回来的话这个配置重启即丢");
        assertEquals("leaveProcess", found.getKey());
        assertEquals(definition.getVersion(), found.getVersion());
        assertTrue(found.isDefaultDefinition(),
                "默认标记要能从库里读回定义对象上：REST 列表要靠它标出哪条是默认");
    }

    @Test
    @DisplayName("查全部定义时能看出哪条是默认")
    void defaultIsVisibleInDefinitionList() {
        WfDefinition definition = deployLeave();
        deploy(EXPENSE_BPMN, "expenseProcess");
        repository.setDefaultDefinition("leaveProcess", definition.getVersion());

        WfDefinition hit = null;
        for (WfDefinition each : repository.getAllDefinitions()) {
            if (each.isDefaultDefinition()) {
                hit = each;
            }
        }
        assertNotNull(hit, "getAllDefinitions 要能看出默认标记，否则定义列表页显示不出哪条是默认");
        assertEquals("leaveProcess", hit.getKey());
    }

    @Test
    @DisplayName("入参对象不会被回写（拿到的仍是副本）")
    void callerObjectIsNotMutated() {
        WfDefinition definition = deployLeave();
        repository.setDefaultDefinition("leaveProcess", definition.getVersion());

        assertFalse(definition.isDefaultDefinition(),
                "入参对象不该被持久层回写 —— 拿到的是副本就说明拷贝边界是好的");
    }

    @Test
    @DisplayName("查询拿到的是副本：之后改默认，手上那份仍带旧标记")
    void queryResultIsACopyNotTheStoredOne() {
        WfDefinition leave = deployLeave();
        repository.setDefaultDefinition("leaveProcess", leave.getVersion());

        WfDefinition found = repository.getDefaultDefinition();
        assertTrue(found.isDefaultDefinition());

        // 换个 key 当默认：存储里 leave 的标记会被清掉
        WfDefinition expense = deploy(EXPENSE_BPMN, "expenseProcess");
        repository.setDefaultDefinition("expenseProcess", expense.getVersion());

        assertTrue(found.isDefaultDefinition(),
                "查询返回的必须是副本。返回存储里那个对象的话，"
                        + "换默认会把它一并改掉 —— 调用方手上还攥着一个已经过期、"
                        + "却自称是默认的定义对象");
        assertFalse(repository.getDefinition("leaveProcess", leave.getVersion())
                .isDefaultDefinition(), "存储里那条确实已经被清掉了");
    }

    // ==================== 全库至多一条 ====================

    @Test
    @DisplayName("设新的默认会自动取消旧的")
    void settingANewDefaultClearsTheOldOne() {
        WfDefinition leave = deployLeave();
        WfDefinition expense = deploy(EXPENSE_BPMN, "expenseProcess");
        repository.setDefaultDefinition("leaveProcess", leave.getVersion());
        assertEquals("leaveProcess", repository.getDefaultDefinition().getKey());

        repository.setDefaultDefinition("expenseProcess", expense.getVersion());

        WfDefinition found = repository.getDefaultDefinition();
        assertEquals("expenseProcess", found.getKey(),
                "设了新的默认之后旧的那条必须让位 —— 留下两条时「默认是哪个」就没有答案");
        assertFalse(repository.getDefinition("leaveProcess", leave.getVersion()).isDefaultDefinition(),
                "旧默认应当被显式清掉，而不是只在新的一条上打标记");
    }

    @Test
    @DisplayName("取消默认后没有默认了")
    void clearingDefaultLeavesNoDefault() {
        WfDefinition leave = deployLeave();
        repository.setDefaultDefinition("leaveProcess", leave.getVersion());

        repository.clearDefaultDefinition("leaveProcess", leave.getVersion());

        assertNull(repository.getDefaultDefinition(), "取消之后应当真的没有默认了");
    }

    @Test
    @DisplayName("同一版本重复设为默认是幂等的")
    void settingSameDefaultTwiceIsIdempotent() {
        WfDefinition leave = deployLeave();
        repository.setDefaultDefinition("leaveProcess", leave.getVersion());
        repository.setDefaultDefinition("leaveProcess", leave.getVersion());

        assertEquals("leaveProcess", repository.getDefaultDefinition().getKey(),
                "重复设同一个默认不该把自己清掉");
    }

    // ==================== 默认必须能启动 ====================

    @Test
    @DisplayName("已停用的版本不能设为默认")
    void suspendedDefinitionCannotBecomeDefault() {
        WfDefinition leave = deployLeave();
        repository.suspendDefinition("leaveProcess", leave.getVersion());

        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> repository.setDefaultDefinition("leaveProcess", leave.getVersion()),
                "默认的用途是「不知道 key 时也能发起一个」，"
                        + "指向一个起不来的定义只会把这个用途变成一个更晚才爆出来的错");
        assertTrue(e.getMessage().contains("activateDefinition"),
                "报错要说清下一步怎么做: " + e.getMessage());
        assertNull(repository.getDefaultDefinition(), "失败的设置不能留下任何痕迹");
    }

    @Test
    @DisplayName("版本不存在时报错，且报的是「版本不存在」那条")
    void unknownVersionIsRejected() {
        deployLeave();
        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> repository.setDefaultDefinition("leaveProcess", 99));
        // 只断言异常类型不够：这条路径后面还有一道「持久层说没改到」的闸门，
        // 它抛的也是 WfDefinitionException。断言类型的话，
        // 「跳过版本存在性检查」这条变异照样通过 —— 断言了错误类型
        // 不等于断言到了正确的那道闸门。
        assertTrue(e.getMessage().contains("版本不存在"),
                "报错要落在「版本不存在」这道闸门上，而不是后面那道泛化兜底: " + e.getMessage());

        assertThrows(WfDefinitionException.class,
                () -> repository.clearDefaultDefinition("noSuchKey", 1));
    }

    // ==================== 默认跟着具体版本走 ====================

    @Test
    @DisplayName("部署新版本不会让默认跟着漂")
    void deployingANewVersionDoesNotMoveTheDefault() {
        WfDefinition v1 = deployLeave();
        repository.setDefaultDefinition("leaveProcess", v1.getVersion());

        deployLeave();   // 同 key 再部署一次，得到 v2

        WfDefinition found = repository.getDefaultDefinition();
        assertEquals(v1.getVersion(), found.getVersion(),
                "默认指向的是一个特定的版本 —— 跟着最新版本漂的话，"
                        + "运营在默认流程上做的验证会被一次无关的重新部署改掉");
    }

    // ==================== 没配默认是正常状态 ====================

    @Test
    @DisplayName("没配默认时查询返回 null 而不是抛异常")
    void noDefaultIsANormalState() {
        deployLeave();
        assertNull(repository.getDefaultDefinition(),
                "「还没配默认」是正常状态，入口页要能据此提示，"
                        + "而不是收到一个异常");
    }

    @Test
    @DisplayName("没配默认时按默认发起：报错并列出可用的 key")
    void startingWithoutDefaultFailsWithGuidance() {
        deployLeave();
        deploy(EXPENSE_BPMN, "expenseProcess");

        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> runtime.startDefaultProcessInstance("BIZ-1", "alice", null,
                        new HashMap<String, Object>()),
                "没配默认就不能按默认发起");
        assertTrue(e.getMessage().contains("setDefaultDefinition"),
                "报错要指明怎么修: " + e.getMessage());
        assertTrue(e.getMessage().contains("leaveProcess")
                        && e.getMessage().contains("expenseProcess"),
                "报错要列出当前可用的 key，否则调用方只能去翻部署脚本: " + e.getMessage());
    }

    // ==================== 按默认发起 ====================

    @Test
    @DisplayName("按默认发起：起的就是默认那一条")
    void startDefaultProcessUsesTheDefault() {
        deployLeave();
        WfDefinition expense = deploy(EXPENSE_BPMN, "expenseProcess");
        repository.setDefaultDefinition("expenseProcess", expense.getVersion());

        String pid = runtime.startDefaultProcessInstance("BIZ-1", "alice", null,
                new HashMap<String, Object>());

        assertNotNull(pid);
        WfProcessInstance instance = runtime.getProcessInstance(pid);
        assertEquals("expenseProcess", instance.getDefinitionKey(),
                "按默认发起必须起默认那一条 —— 悄悄起别的流程等于「默认」这个配置形同虚设");
        assertEquals(expense.getVersion(), instance.getDefinitionVersion());
    }

    @Test
    @DisplayName("按默认发起：走的是与指定 key 发起完全相同的那条路")
    void startDefaultGoesThroughTheSamePath() {
        deployLeave();
        WfDefinition leave = deployLeave();
        repository.setDefaultDefinition("leaveProcess", leave.getVersion());

        String pid = runtime.startDefaultProcessInstance("BIZ-2", "alice", null,
                new HashMap<String, Object>());

        // 默认流程里的第一个待办出现了 —— 说明真的走进了那张图
        assertEquals(1, repo.queryTasks(new com.zifang.z.wf.core.persistence.WfTaskQuery()
                .setProcessInstanceId(pid).setOpenOnly(true)
                .setPageNum(1).setPageSize(10)).size(),
                "按默认发起应当建出待办 —— 只有实例壳而没走到任务，说明流程压根没跑");
    }

    // ==================== 停用不带走默认 ====================

    @Test
    @DisplayName("停用默认之后，默认标记还在但按默认发起被拒")
    void suspendingTheDefaultKeepsTheMarkerButBlocksStart() {
        WfDefinition leave = deployLeave();
        repository.setDefaultDefinition("leaveProcess", leave.getVersion());

        repository.suspendDefinition("leaveProcess", leave.getVersion());

        assertNotNull(repository.getDefaultDefinition(),
                "停用不该顺手把默认标记也清掉 —— 取消默认是一次显式的运营决策，"
                        + "由停用代办等于「我只是不想接新单，结果默认也没了」");
        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> runtime.startDefaultProcessInstance("BIZ-3", "alice", null,
                        new HashMap<String, Object>()),
                "默认指向一个起不来的定义时必须当场报错，而不是让流程卡住");
        // 两条路径都提到 activateDefinition（startProcessInstance 自己的停用闸门也提），
        // 所以只断言那一句会「因错误的原因通过」—— 摘掉默认这条路径上的检查，
        // 流程照样在下一道闸门被拦住，测试照样绿。
        // 必须断言「只有默认这条路径会说的话」。
        assertTrue(e.getMessage().contains("默认流程定义"),
                "报错要能看出问题出在「默认」上，而不是泛泛的停用: " + e.getMessage());
        assertTrue(e.getMessage().contains("activateDefinition"),
                "报错要说清怎么修: " + e.getMessage());
    }

    @Test
    @DisplayName("启用之后按默认发起恢复")
    void activatingRestoresDefaultStart() {
        WfDefinition leave = deployLeave();
        repository.setDefaultDefinition("leaveProcess", leave.getVersion());
        repository.suspendDefinition("leaveProcess", leave.getVersion());

        repository.activateDefinition("leaveProcess", leave.getVersion());

        assertNotNull(runtime.startDefaultProcessInstance("BIZ-4", "alice", null,
                new HashMap<String, Object>()), "启用后应当恢复");
    }

    // ==================== 删定义不留悬空指针 ====================

    @Test
    @DisplayName("删掉默认定义之后没有默认了，不留悬空指针")
    void deletingTheDefaultLeavesNoDanglingPointer() {
        WfDefinition leave = deployLeave();
        repository.setDefaultDefinition("leaveProcess", leave.getVersion());

        repository.deleteDefinition("leaveProcess", leave.getVersion());

        assertNull(repository.getDefaultDefinition(),
                "默认标记与定义行同生共死 —— 用列而不是用指针，"
                        + "删行即删标记，不该留下一条指向不存在定义的悬空指针");
        assertTrue(repository.getAllDefinitions().isEmpty());
    }

    // ==================== 部署不产生默认 ====================

    @Test
    @DisplayName("部署本身不会让某条定义变成默认")
    void deployDoesNotImplyDefault() {
        deployLeave();
        assertNull(repository.getDefaultDefinition(),
                "部署只是部署 —— 若 deploy 会顺手置默认，"
                        + "那么「默认是哪个」就取决于最后一次部署的顺序，不可预期");
    }

    @Test
    @DisplayName("带 defaultDefinition 标记的定义部署后仍不是默认")
    void deployingAFlaggedDefinitionDoesNotMakeItDefault() {
        WfDefinition definition = new WfXmlParser().parse(LEAVE_BPMN);
        definition.setDefaultDefinition(true);
        repository.deploy(definition);

        assertNull(repository.getDefaultDefinition(),
                "默认标记的真源只有 IS_DEFAULT 那一列。若 INSERT 照抄入参上的标记，"
                        + "就多出第二个真源 —— 之后每次 deploy 都会静默改掉默认");
    }

    // ==================== 多默认 = 数据损坏 ====================

    @Test
    @DisplayName("数据里出现两条默认时查询报错，而不是随便返回一条")
    void multipleDefaultsAreReportedAsCorruption() {
        WfDefinition leave = deployLeave();
        WfDefinition expense = deploy(EXPENSE_BPMN, "expenseProcess");
        repository.setDefaultDefinition("leaveProcess", leave.getVersion());

        // 绕过接口直接改存储：这是「数据被改坏」的真实来源
        markBothAsDefault(leave, expense);

        com.zifang.z.wf.core.persistence.WfPersistenceException e =
                assertThrows(com.zifang.z.wf.core.persistence.WfPersistenceException.class,
                        () -> repository.getDefaultDefinition(),
                        "两条默认时「默认是哪个」没有答案，返回其中一条等于替调用方"
                                + "做了他没授权的选择，而且取决于行返回顺序");
        assertTrue(e.getMessage().contains("leaveProcess")
                        && e.getMessage().contains("expenseProcess"),
                "报错要点名是哪两条，否则不知道该去改哪一行: " + e.getMessage());
    }

    /**
     * 绕过 {@code setDefaultDefinition} 手工把两条都标成默认。
     *
     * <p><b>只能直接改存储那份对象</b>：{@code setDefaultDefinition(..., true)} 自带
     * 「先清全表」，而所有查询一律返回副本 —— 对副本改标记等于什么都没改。
     * 而这正是本用例要模拟的「数据被绕过接口改坏」的情形，用接口造不出来。
     */
    @SuppressWarnings("unchecked")
    private void markBothAsDefault(WfDefinition leave, WfDefinition expense) {
        try {
            java.lang.reflect.Field field =
                    InMemoryWorkflowPersistence.class.getDeclaredField("definitions");
            field.setAccessible(true);
            java.util.Map<String, java.util.Map<Integer, WfDefinition>> stored =
                    (java.util.Map<String, java.util.Map<Integer, WfDefinition>>) field.get(repo);
            for (String key : new String[]{leave.getKey(), expense.getKey()}) {
                for (WfDefinition definition : stored.get(key).values()) {
                    definition.setDefaultDefinition(true);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("构造「两条默认」失败", e);
        }
    }

    // ==================== 与既有查询共存 ====================

    @Test
    @DisplayName("默认定义也能被 key 模糊查到")
    void defaultIsStillQueryableByKey() {
        WfDefinition leave = deployLeave();
        deploy(EXPENSE_BPMN, "expenseProcess");
        repository.setDefaultDefinition("leaveProcess", leave.getVersion());

        List<WfDefinition> hits = repository.queryDefinitions("leave", null, null);
        assertEquals(1, hits.size(), "默认标记不该把定义从正常查询里排除掉");
        assertTrue(hits.get(0).isDefaultDefinition(), "带标记的结果仍要带上标记");
    }
}
