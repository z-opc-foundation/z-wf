package com.zifang.z.wf.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.hook.WfTaskHook;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfProcessInstanceQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.service.WfTaskService;

/**
 * 引擎端到端测试 —— 证明"审批流真的能跑起来"，而不只是"类能编译"。
 *
 * <p>覆盖真实审批链路的四种形态：
 * <ol>
 *   <li>线性审批（发起 → 经理批 → 结束）</li>
 *   <li>排他网关按金额分支</li>
 *   <li>并行网关多支线汇合</li>
 *   <li>条件全不成立时走 default 流（"卡死"是最典型的引擎 bug）</li>
 * </ol>
 *
 * @author zifang
 */
class WfEngineEndToEndTest {

    private InMemoryWorkflowPersistence persistence;
    private WfRepositoryService repositoryService;
    private WfRuntimeService runtimeService;
    private WfTaskService taskService;
    private WfHookDispatcher hookDispatcher;

    @BeforeEach
    void setUp() {
        persistence = new InMemoryWorkflowPersistence();
        persistence.initialize();
        WfEngine engine = new WfEngine();
        repositoryService = new WfRepositoryService(persistence);
        hookDispatcher = new WfHookDispatcher();
        runtimeService = new WfRuntimeService(repositoryService, persistence, engine, hookDispatcher);
        taskService = new WfTaskService(repositoryService, persistence, runtimeService, hookDispatcher);
    }

    // ==================== 1. 线性审批 ====================

    private WfDefinition deployLinearLeave() {
        WfDefinition definition = new WfDefinition("leaveProcess", "请假流程");
        definition.setCategory("审批");
        WfTestFlows.linear(definition, "start1", "task1", "end1", "manager");
        return repositoryService.deploy(definition);
    }

    @Test
    @DisplayName("线性审批：发起后产生经理待办，办结后流程完成")
    void linearApprovalRunsEndToEnd() {
        deployLinearLeave();

        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 3);
        String processId = runtimeService.startProcessInstance("leaveProcess", null, "ORDER-1", "u_alice", null, vars);
        assertNotNull(processId);

        // 运行中，有 1 个待办
        WfProcessInstance running = runtimeService.getProcessInstance(processId);
        assertEquals(WfProcessStatus.ACTIVE, running.getStatus());
        assertEquals("ORDER-1", running.getBusinessKey());
        assertEquals(3, running.getVariables().get("days"));

        List<WfTask> todo = taskService.getTodoList("manager", null, 1, 10);
        assertEquals(1, todo.size());
        WfTask task = todo.get(0);
        assertEquals("经理审批", task.getName());
        assertEquals(WfTask.Status.ASSIGNED, task.getStatus());

        // 办结 → 流程完成
        WfProcessInstance done = runtimeService.completeTask(task.getId(), "manager", "同意", null);
        assertEquals(WfProcessStatus.COMPLETED, done.getStatus());
        assertEquals("completed", done.getResult());
        assertNotNull(done.getEndTime());

        // 轨迹有记录
        assertTrue(runtimeService.getTrail(processId).size() >= 1, "审批轨迹必须留痕");
    }

    @Test
    @DisplayName("线性审批：办结后 token 结束，任务不可重复完成")
    void cannotCompleteSameTaskTwice() {
        deployLinearLeave();
        String processId = runtimeService.startProcessInstance("leaveProcess", null, "ORDER-2", "u_alice", null, null);
        WfTask task = taskService.getTodoList("manager", null, 1, 10).get(0);
        runtimeService.completeTask(task.getId(), "manager", "同意", null);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.zifang.z.wf.core.service.WfEngineException.class,
                () -> runtimeService.completeTask(task.getId(), "manager", "再批一次", null));
    }

    @Test
    @DisplayName("线性审批：非办理人办结被拒（越权防护）")
    void cannotCompleteByNonAssignee() {
        deployLinearLeave();
        String processId = runtimeService.startProcessInstance("leaveProcess", null, "ORDER-3", "u_alice", null, null);
        WfTask task = taskService.getTodoList("manager", null, 1, 10).get(0);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.zifang.z.wf.core.service.WfEngineException.class,
                () -> runtimeService.completeTask(task.getId(), "u_intruder", "我来批", null),
                "非办理人必须被拒绝");
    }

    // ==================== 2. 排他网关 ====================

    @Test
    @DisplayName("排他网关：按金额分流，大额走总经理")
    void exclusiveGatewayRoutesByAmount() {
        WfDefinition definition = new WfDefinition("expense", "报销流程");
        WfTestFlows.amountBranch(definition);
        repositoryService.deploy(definition);

        // 小额 → 经理
        String smallId = runtimeService.startProcessInstance("expense", null, "E-1", "u_alice", null,
                vars("amount", 500));
        List<WfTask> smallTasks = taskService.getTodoList("manager", null, 1, 10);
        assertEquals(1, smallTasks.size());
        assertTrue(taskService.getTodoList("ceo", null, 1, 10).isEmpty(),
                "小额时总经理不应有待办");

        // 大额 → 总经理
        runtimeService.startProcessInstance("expense", null, "E-2", "u_alice", null, vars("amount", 5000));
        assertTrue(taskService.getTodoList("ceo", null, 1, 10).size() >= 1,
                "大额时总经理应有待办");
        assertNotNull(smallId);
    }

    @Test
    @DisplayName("排他网关：条件全不成立时走 default 流（不能卡死）")
    void exclusiveGatewayFallsBackToDefault() {
        WfDefinition definition = new WfDefinition("expense2", "报销流程2");
        WfTestFlows.amountBranch(definition);
        repositoryService.deploy(definition);

        // amount 缺失 ⇒ 两条条件都不成立 ⇒ 必须落 default 流（财务）
        String processId = runtimeService.startProcessInstance("expense2", null, "E-3", "u_alice", null, null);
        List<WfTask> finance = taskService.getTodoList("finance", null, 1, 10);
        assertEquals(1, finance.size(), "条件都不成立时应走 default 流（财务），而不是卡死");
        assertTrue(taskService.getTodoList("manager", null, 1, 10).isEmpty());
        assertNotNull(processId);
    }

    // ==================== 3. 并行网关 ====================

    @Test
    @DisplayName("并行网关：多支线同时激活，各自独立办结")
    void parallelGatewayForksIntoTwoTasks() {
        WfDefinition definition = new WfDefinition("parallel", "并行会签");
        WfTestFlows.parallel(definition);
        repositoryService.deploy(definition);

        String processId = runtimeService.startProcessInstance("parallel", null, "P-1", "u_alice", null, null);

        // 两条支线都产生待办
        assertEquals(1, taskService.getTodoList("legal", null, 1, 10).size(), "法务支线应有待办");
        assertEquals(1, taskService.getTodoList("finance", null, 1, 10).size(), "财务支线应有待办");

        // 只办一条 → 流程仍在运行（另一条未完）
        WfTask legalTask = taskService.getTodoList("legal", null, 1, 10).get(0);
        WfProcessInstance afterOne = runtimeService.completeTask(legalTask.getId(), "legal", "同意", null);
        assertEquals(WfProcessStatus.ACTIVE, afterOne.getStatus(),
                "只完成一条支线时流程不应结束");

        // 两条都办完 → 流程完成
        WfTask financeTask = taskService.getTodoList("finance", null, 1, 10).get(0);
        WfProcessInstance afterBoth = runtimeService.completeTask(financeTask.getId(), "finance", "同意", null);
        assertEquals(WfProcessStatus.COMPLETED, afterBoth.getStatus(),
                "两条支线都完成后流程应结束");
        assertNotNull(processId);
    }

    // ==================== 4. 版本 / 挂起 / 终止 ====================

    @Test
    @DisplayName("版本化：同 key 二次部署产生 v2，v1 仍可查（旧版本不被覆盖）")
    void redeployCreatesNewVersionAndKeepsOld() {
        deployLinearLeave();
        WfDefinition v2 = new WfDefinition("leaveProcess", "请假流程v2");
        WfTestFlows.linear(v2, "start1", "task1", "end1", "director");
        repositoryService.deploy(v2);

        assertEquals(2, repositoryService.getLatestDefinition("leaveProcess").getVersion());
        assertEquals(1, repositoryService.getDefinition("leaveProcess", 1).getVersion(),
                "旧版本必须保留，否则运行中实例下次推进会读到新图");

        // 新版本用新办理人
        String processId = runtimeService.startProcessInstance("leaveProcess", null, "ORDER-9", "u_alice", null, null);
        assertEquals(1, taskService.getTodoList("director", null, 1, 10).size());
        assertTrue(taskService.getTodoList("manager", null, 1, 10).isEmpty());
        assertNotNull(processId);
    }

    @Test
    @DisplayName("挂起后无法推进；激活后恢复")
    void suspendBlocksAdvanceUntilActivated() {
        deployLinearLeave();
        String processId = runtimeService.startProcessInstance("leaveProcess", null, "S-1", "u_alice", null, null);

        runtimeService.suspend(processId, "领导出差");
        assertEquals(WfProcessStatus.SUSPENDED,
                runtimeService.getProcessInstance(processId).getStatus());
        org.junit.jupiter.api.Assertions.assertThrows(
                com.zifang.z.wf.core.service.WfEngineException.class,
                () -> runtimeService.advance(processId, null));

        runtimeService.activate(processId);
        assertEquals(WfProcessStatus.ACTIVE,
                runtimeService.getProcessInstance(processId).getStatus());
    }

    @Test
    @DisplayName("终止流程会作废未完成任务（否则待办永远挂着）")
    void terminateCancelsOpenTasks() {
        deployLinearLeave();
        String processId = runtimeService.startProcessInstance("leaveProcess", null, "T-1", "u_alice", null, null);
        assertEquals(1, taskService.getTodoList("manager", null, 1, 10).size());

        runtimeService.terminate(processId, "单据作废");

        WfProcessInstance instance = runtimeService.getProcessInstance(processId);
        assertEquals(WfProcessStatus.EXTERNALLY_TERMINATED, instance.getStatus());
        assertEquals("单据作废", instance.getDeleteReason());
        assertTrue(taskService.getOpenTasks(processId).isEmpty(),
                "终止后不得残留未办结任务");
    }

    // ==================== 5. 任务流转 ====================

    @Test
    @DisplayName("委派不转移责任：owner 变、assignee 不变，已办仍查到原责任人")
    void delegateKeepsAssignee() {
        deployLinearLeave();
        runtimeService.startProcessInstance("leaveProcess", null, "D-1", "u_alice", null, null);
        WfTask task = taskService.getTodoList("manager", null, 1, 10).get(0);

        taskService.delegate(task.getId(), "manager", "u_staff", "我出差");

        WfTask delegated = taskService.getTask(task.getId());
        assertEquals("manager", delegated.getAssignee(), "委派不应转移责任人");
        assertEquals("u_staff", delegated.getOwner());
        assertEquals(WfTask.Status.DELEGATED, delegated.getStatus());
        assertEquals("u_staff", delegated.effectiveHandler(), "待办应落在被委派人");
        assertEquals(1, delegated.getDelegateChain().size());

        // 被委派人在待办里能看到
        assertEquals(1, taskService.getTodoList("u_staff", null, 1, 10).size());

        // 被委派人办结后，已办查到的是"责任人 manager"（合规审计需要）
        runtimeService.completeTask(task.getId(), "u_staff", "代批同意", null);
        List<WfTask> doneByManager = persistence.queryTasks(new WfTaskQuery()
                .setCompleterId("u_staff").setCompletedOnly(true));
        assertEquals(1, doneByManager.size());
        assertEquals("u_staff", doneByManager.get(0).getCompleterId());
        assertEquals("manager", doneByManager.get(0).getAssignee(), "责任人应仍是 manager");
    }

    @Test
    @DisplayName("转办转移责任：assignee 直接换人")
    void transferMovesAssignee() {
        deployLinearLeave();
        runtimeService.startProcessInstance("leaveProcess", null, "TR-1", "u_alice", null, null);
        WfTask task = taskService.getTodoList("manager", null, 1, 10).get(0);

        taskService.transfer(task.getId(), "manager", "director", "请代批");

        WfTask transferred = taskService.getTask(task.getId());
        assertEquals("director", transferred.getAssignee());
        assertNull(transferred.getOwner());
        assertTrue(taskService.getTodoList("manager", null, 1, 10).isEmpty());
        assertEquals(1, taskService.getTodoList("director", null, 1, 10).size());
    }

    @Test
    @DisplayName("撤回任务放回候选池，可重新认领")
    void withdrawThenClaim() {
        // 用带候选人的流程：撤回后 assignee 为空，才能走认领
        WfDefinition definition = new WfDefinition("candidateLeave", "候选人请假");
        WfTestFlows.linear(definition, "start1", "task1", "end1", "manager");
        definition.getNodes().get(1).setCandidateUsers(new java.util.ArrayList<>(
                java.util.Arrays.asList("u_a", "director")));
        definition.buildIndex();
        repositoryService.deploy(definition);

        runtimeService.startProcessInstance("candidateLeave", null, "W-1", "u_alice", null, null);
        WfTask task = taskService.getTodoList("manager", null, 1, 10).get(0);

        taskService.withdraw(task.getId(), "u_alice", "指派错了");
        WfTask withdrawn = taskService.getTask(task.getId());
        assertNull(withdrawn.getAssignee());
        assertEquals(WfTask.Status.CREATED, withdrawn.getStatus());

        taskService.claim(task.getId(), "director", null);
        assertEquals("director", taskService.getTask(task.getId()).getAssignee());
    }

    @Test
    @DisplayName("认领越权被拒：非候选人不能认领")
    void cannotClaimByNonCandidate() {
        WfDefinition definition = new WfDefinition("candidateLeave2", "候选人请假2");
        WfTestFlows.linear(definition, "start1", "task1", "end1", null);
        definition.getNodes().get(1).setCandidateUsers(new java.util.ArrayList<>(
                java.util.Arrays.asList("u_a")));
        definition.buildIndex();
        repositoryService.deploy(definition);

        runtimeService.startProcessInstance("candidateLeave2", null, "C-1", "u_alice", null, null);
        WfTask task = persistence.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(runtimeService.getProcessInstancesByBusinessKey("C-1").get(0).getId())
                .setOpenOnly(true)).get(0);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.zifang.z.wf.core.service.WfEngineException.class,
                () -> taskService.claim(task.getId(), "u_intruder", null));
    }

    // ==================== 6. 查询 ====================

    @Test
    @DisplayName("按业务键查实例（审批主查询路径）")
    void queryByBusinessKey() {
        deployLinearLeave();
        runtimeService.startProcessInstance("leaveProcess", null, "ORDER-X", "u_alice", null, null);
        List<WfProcessInstance> found =
                runtimeService.getProcessInstancesByBusinessKey("ORDER-X");
        assertEquals(1, found.size());
        assertEquals("u_alice", found.get(0).getStartUserId());
    }

    @Test
    @DisplayName("我发起的：按发起人过滤")
    void queryMyStartedProcesses() {
        deployLinearLeave();
        runtimeService.startProcessInstance("leaveProcess", null, "O-1", "u_alice", null, null);
        runtimeService.startProcessInstance("leaveProcess", null, "O-2", "u_bob", null, null);

        List<WfProcessInstance> mine = persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setStartUserId("u_alice"));
        assertEquals(1, mine.size());
        assertEquals("O-1", mine.get(0).getBusinessKey());
    }

    // ==================== 钩子：扩展点必须真的被触发 ====================

    @Test
    @DisplayName("onBeforeCreate 钩子会被真的触发（不是只声明不调）")
    void beforeCreateHookActuallyFires() {
        deployLinearLeave();
        final List<String> fired = new ArrayList<>();
        hookDispatcher.addTaskHook(new WfTaskHook() {
            @Override
            public boolean onBeforeCreate(String taskId, String assignee, Map<String, Object> variables) {
                fired.add(taskId + "@" + assignee);
                return true;
            }
        });

        runtimeService.startProcessInstance("leaveProcess", null, "HOOK-1", "u_alice", null, null);

        assertEquals(1, fired.size(), "建任务时必须触发一次 onBeforeCreate");
        assertTrue(fired.get(0).endsWith("@manager"),
                "钩子应拿到已解析好的办理人（而不是字面量 ${leaderId}），实际: " + fired.get(0));
    }

    @Test
    @DisplayName("onBeforeCreate 钩子可否决：抛 WfEngineException 且一个字节都没落库")
    void beforeCreateHookCanVetoWithoutTornWrite() {
        deployLinearLeave();
        hookDispatcher.addTaskHook(new WfTaskHook() {
            @Override
            public boolean onBeforeCreate(String taskId, String assignee, Map<String, Object> variables) {
                return false;
            }
        });

        WfEngineException e = assertThrows(WfEngineException.class,
                () -> runtimeService.startProcessInstance(
                        "leaveProcess", null, "HOOK-VETO", "u_alice", null, null));
        assertTrue(e.getMessage().contains("任务创建被钩子否决"), "实际: " + e.getMessage());

        // 关键：否决发生在 persistAll 的最前面，所以不能留下"token 落了、instance 没落"的撕裂写
        assertEquals(0, persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setBusinessKey("HOOK-VETO")).size(),
                "被否决的流程不应留下流程实例");
        assertEquals(0, persistence.queryTasks(new WfTaskQuery()).size(),
                "被否决的流程不应留下任务");
        assertEquals(0, persistence.findExecutionsByProcessInstance("HOOK-VETO").size(),
                "被否决的流程不应留下孤儿 token —— 这是把校验放在落库前的原因");
    }

    @Test
    @DisplayName("钩子抛异常不否决流程（只记日志），返回 false 才否决")
    void hookExceptionDoesNotBlockButFalseDoes() {
        deployLinearLeave();

        WfTaskHook throwing = new WfTaskHook() {
            @Override
            public boolean onBeforeCreate(String taskId, String assignee, Map<String, Object> variables) {
                throw new IllegalStateException("通知服务临时不可用");
            }
        };
        hookDispatcher.addTaskHook(throwing);
        String processId = runtimeService.startProcessInstance(
                "leaveProcess", null, "HOOK-THROW", "u_alice", null, null);
        assertNotNull(processId, "钩子抛异常表达的是'我没意见但出错了'，不该阻断审批");
        assertEquals(1, taskService.getTodoList("manager", null, 1, 10).size(),
                "任务照常创建，待办照常产生");

        // 摘掉抛异常的钩子，换成明确否决的
        assertEquals(1, hookDispatcher.removeTaskHook(throwing));
        hookDispatcher.addTaskHook(new WfTaskHook() {
            @Override
            public boolean onBeforeCreate(String taskId, String assignee, Map<String, Object> variables) {
                return false;
            }
        });
        assertThrows(WfEngineException.class, () -> runtimeService.startProcessInstance(
                "leaveProcess", null, "HOOK-FALSE", "u_bob", null, null),
                "显式 return false 才是否决");
    }

    private Map<String, Object> vars(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }
}
