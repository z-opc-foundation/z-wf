package com.zifang.z.wf.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.service.WfTaskService;

/**
 * 端到端测试 —— 验证 Spring 全栈（自动装配 + JDBC 落库 + 示例流程）真的能跑。
 *
 * <p>覆盖的是<b>组合起来才暴露</b>的问题，各模块单测都测不到：
 * <ul>
 *   <li>自动装配有没有真的把 service 装出来（bean 名、条件注解、扫描路径）</li>
 *   <li>示例 BPMN 能不能通过<b>部署期校验</b>（XML 写错、属性写错在这里才炸）</li>
 *   <li>JDBC 模式下走完整审批链后数据是否真的落库并能查回</li>
 *   <li>并行会签（五看评估）在真库上的 token 汇合是否正确</li>
 * </ul>
 *
 * <p>用 h2-test profile：不依赖外部 MySQL / z-config / z-rpc。
 *
 * @author zifang
 */
@SpringBootTest
@ActiveProfiles("h2-test")
class WfAdminEndToEndTest {

    @Autowired
    private WfRepositoryService repositoryService;

    @Autowired
    private WfRuntimeService runtimeService;

    @Autowired
    private WfTaskService taskService;

    // ==================== 部署期 ====================

    @Test
    @DisplayName("启动后示例流程已自动部署（classpath 下两个 BPMN）")
    void sampleProcessesDeployedOnStartup() {
        List<String> keys = new java.util.ArrayList<>();
        for (com.zifang.z.wf.core.definition.WfDefinition definition
                : repositoryService.getAllDefinitions()) {
            keys.add(definition.getKey());
        }
        assertTrue(keys.contains("leaveProcess"), "leaveProcess 应被自动部署，实际: " + keys);
        assertTrue(keys.contains("fiveLookEvaluation"),
                "fiveLookEvaluation 应被自动部署，实际: " + keys);
    }

    @Test
    @DisplayName("自动装配的 service 都可用（bean 装配链完整）")
    void servicesAutowired() {
        assertNotNull(repositoryService);
        assertNotNull(runtimeService);
        assertNotNull(taskService);
        // h2-test profile 配的是 jdbc，因此必须真的是 JDBC 实现而不是回落内存
        assertEquals("JdbcWorkflowPersistence", persistence.getClass().getSimpleName());
    }

    @Autowired
    private com.zifang.z.wf.core.persistence.WfPersistence persistence;

    // ==================== 完整审批链 ====================

    @Test
    @DisplayName("请假流程全链：提交 → 领导批 → 天数分流 → 结束，数据落库可查回")
    void leaveProcessFullChain() {
        String businessKey = "E2E-LEAVE-" + System.nanoTime();
        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 5);
        vars.put("leaderId", "leader01");

        String processId = runtimeService.startProcessInstance(
                "leaveProcess", null, businessKey, "alice", "d1", vars);
        assertNotNull(processId, "流程应启动成功");

        WfProcessInstance instance = runtimeService.getProcessInstance(processId);
        assertEquals(com.zifang.z.wf.core.model.WfProcessStatus.ACTIVE, instance.getStatus());
        assertEquals(businessKey, instance.getBusinessKey());

        // 领导待办：assignee 来自 zifang:assignee="${leaderId}" 扩展属性
        List<WfTask> leaderTodos = taskService.getTodoList("leader01", null, 1, 10);
        assertEquals(1, leaderTodos.size(), "应有 1 条领导待办");
        WfTask leaderTask = leaderTodos.get(0);
        assertEquals("直属领导审批", leaderTask.getName());
        assertEquals("leaveForm", leaderTask.getFormKey());
        assertNotNull(leaderTask.getDueDate(), "zifang:dueDate=PT24H 应换算成到期时间");

        // 领导批准 → 因 days=5 > 3 应分流到总经理
        runtimeService.completeTask(leaderTask.getId(), "leader01", "同意", null);
        List<WfTask> ceoTodos = taskService.getTodoList("ceo", null, 1, 10);
        assertEquals(1, ceoTodos.size(), "days=5 应走总经理审批");
        assertEquals("总经理审批", ceoTodos.get(0).getName());

        // 总经理批准 → 流程完成
        WfProcessInstance done = runtimeService.completeTask(
                ceoTodos.get(0).getId(), "ceo", "同意", null);
        assertEquals(com.zifang.z.wf.core.model.WfProcessStatus.COMPLETED, done.getStatus());
        assertEquals("approved", done.getResult(), "endEvent 的 resultExpression 应产出 approved");

        // 落库校验：按业务键能查回，且轨迹有记录
        List<WfProcessInstance> found = runtimeService.getProcessInstancesByBusinessKey(businessKey);
        assertEquals(1, found.size());
        assertFalse(runtimeService.getTrail(processId).isEmpty(), "审批轨迹必须留痕");
    }

    @Test
    @DisplayName("请假流程：days<=3 走 HR 备案分支")
    void leaveProcessShortPath() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 2);
        vars.put("leaderId", "leader02");
        String processId = runtimeService.startProcessInstance(
                "leaveProcess", null, "E2E-SHORT-" + System.nanoTime(), "bob", "d1", vars);
        assertNotNull(processId);

        WfTask leaderTask = taskService.getTodoList("leader02", null, 1, 10).get(0);
        runtimeService.completeTask(leaderTask.getId(), "leader02", "同意", null);

        List<WfTask> hrTodos = taskService.getTodoList("hr", null, 1, 10);
        assertEquals(1, hrTodos.size(), "days=2 应走 HR 备案");
    }

    // ==================== 并行会签 ====================

    @Test
    @DisplayName("五看评估：5 条并行线各自办结，汇合后跑汇总 delegate 并结束")
    void fiveLookParallelApproval() {
        String businessKey = "E2E-FIVELOOK-" + System.nanoTime();
        String processId = runtimeService.startProcessInstance(
                "fiveLookEvaluation", null, businessKey, "carol", null, null);
        assertNotNull(processId);

        // 5 条候选组任务应同时存在
        List<WfTask> bizTasks = taskService.getClaimableList("biz-reviewer-1",
                java.util.Collections.singletonList("biz-reviewers"), 1, 10);
        assertFalse(bizTasks.isEmpty(), "业务看任务应可认领");
        assertFalse(taskService.getClaimableList("fin-reviewer-1",
                java.util.Collections.singletonList("fin-reviewers"), 1, 10).isEmpty());

        // 认领并办结 5 条线：分数分别为 80/70/90/60/50
        Map<String, Object> scores = new HashMap<>();
        scores.put("scoreBiz", 80);
        scores.put("scoreFin", 70);
        scores.put("scoreLegal", 90);
        scores.put("scoreRisk", 60);
        scores.put("scoreStrategy", 50);

        // 候选组是 fail-closed 的：不在候选组里的人，即使任务在候选池里可见也认领不了。
        // 这一条是审批安全底线 —— 候选池可见性由 SQL 的 LIKE 模糊召回（宁可多召回），
        // 真正的准入判定必须落在 isClaimableBy 上，否则"能看见就能办"等于授权失效。
        WfTask bizTask = bizTasks.get(0);
        assertThrows(com.zifang.z.wf.core.service.WfEngineException.class,
                () -> taskService.claim(bizTask.getId(), "legal-reviewer-1",
                        java.util.Collections.singletonList("legal-reviewers")),
                "非候选组成员认领应被拒绝");
        // 不传组更不能认领：认领人所属组由调用方（REST 的 targetGroups）提供，
        // 缺失时引擎无从判定，必须拒绝而不是"先放行再说"
        assertThrows(com.zifang.z.wf.core.service.WfEngineException.class,
                () -> taskService.claim(bizTask.getId(), "biz-reviewer-1", null),
                "未提供所属组时认领应被拒绝");

        String[][] dims = {
                {"biz-reviewer-1", "biz-reviewers"},
                {"fin-reviewer-1", "fin-reviewers"},
                {"legal-reviewer-1", "legal-reviewers"},
                {"risk-reviewer-1", "risk-reviewers"},
                {"strategy-reviewer-1", "strategy-reviewers"},
        };
        String[] scoreKeys = {"scoreBiz", "scoreFin", "scoreLegal", "scoreRisk", "scoreStrategy"};
        for (int i = 0; i < dims.length; i++) {
            List<WfTask> claimable = taskService.getClaimableList(dims[i][0],
                    java.util.Collections.singletonList(dims[i][1]), 1, 10);
            assertEquals(1, claimable.size(), dims[i][1] + " 应恰好 1 条可认领");
            WfTask task = claimable.get(0);
            // 必须带上认领人所属组：候选组判定完全依赖调用方传入的组，
            // 漏传必然认领失败（这个坑踩过一次：看得见 ≠ 能认领）
            taskService.claim(task.getId(), dims[i][0],
                    java.util.Collections.singletonList(dims[i][1]));
            Map<String, Object> one = new HashMap<>();
            one.put(scoreKeys[i], scores.get(scoreKeys[i]));
            runtimeService.completeTask(task.getId(), dims[i][0], "评分", one);
        }

        WfProcessInstance done = runtimeService.getProcessInstance(processId);
        assertEquals(com.zifang.z.wf.core.model.WfProcessStatus.COMPLETED, done.getStatus(),
                "5 条线全部办结后应完成（并行汇合）");
        assertEquals("approved", done.getResult(),
                "均分 70 ≥ 60，汇总 delegate 应判 approved");
        assertEquals(350, ((Number) done.getVariables().get("scoreTotal")).intValue());
        assertEquals(70, ((Number) done.getVariables().get("scoreAverage")).intValue());
    }

    // ==================== 版本化 ====================

    @Test
    @DisplayName("重复部署同 key 产生新版本，旧版本仍可取（运行中实例不受影响）")
    void redeployKeepsOldVersion() {
        String key = "versioned-" + System.nanoTime();

        com.zifang.z.wf.core.definition.WfDefinition v1 = buildLinear(key, "mgrA");
        com.zifang.z.wf.core.definition.WfDefinition deployedV1 = repositoryService.deploy(v1);
        assertEquals(1, deployedV1.getVersion());

        com.zifang.z.wf.core.definition.WfDefinition v2 = buildLinear(key, "mgrB");
        com.zifang.z.wf.core.definition.WfDefinition deployedV2 = repositoryService.deploy(v2);
        assertEquals(2, deployedV2.getVersion());

        // 旧版本必须还在：否则运行中的 v1 实例下次推进会读到新图（半路换图 = 事故）
        assertNotNull(repositoryService.getDefinition(key, 1));
        assertEquals("mgrA", repositoryService.getDefinition(key, 1)
                .node("t1").getAssignee());
        assertEquals("mgrB", repositoryService.getLatestDefinition(key).node("t1").getAssignee());

        // 不传版本 = 用最新版（v2）
        String latestId = runtimeService.startProcessInstance(
                key, null, "V-LATEST", "alice", null, null);
        assertEquals(2, runtimeService.getProcessInstance(latestId).getDefinitionVersion());

        // 显式传 v1 ⇒ 实例锁定 v1。运行中实例锁版本的意义：v2 上线后，
        // 正在跑的 v1 流程下次推进不会"半路换图"
        String pinnedId = runtimeService.startProcessInstance(
                key, 1, "V-PINNED", "bob", null, null);
        WfProcessInstance pinned = runtimeService.getProcessInstance(pinnedId);
        assertEquals(1, pinned.getDefinitionVersion(), "显式指定版本应锁定在该版本");
        assertNotNull(pinned);
        assertEquals("V-PINNED", pinned.getBusinessKey());

        // v1 的待办人是 mgrA，v2 的是 mgrB —— 锁版本的效果在这里可观测
        assertFalse(taskService.getTodoList("mgrA", null, 1, 50).isEmpty());
    }

    /**
     * 构造 start → t1 → end 的线性流程。
     */
    private com.zifang.z.wf.core.definition.WfDefinition buildLinear(String key, String assignee) {
        com.zifang.z.wf.core.definition.WfDefinition definition =
                new com.zifang.z.wf.core.definition.WfDefinition(key, key);
        com.zifang.z.wf.core.definition.WfNode start =
                new com.zifang.z.wf.core.definition.WfNode("s", "开始",
                        com.zifang.z.wf.core.definition.WfNodeType.START_EVENT);
        com.zifang.z.wf.core.definition.WfNode task =
                new com.zifang.z.wf.core.definition.WfNode("t1", "审批",
                        com.zifang.z.wf.core.definition.WfNodeType.USER_TASK);
        task.setAssignee(assignee);
        com.zifang.z.wf.core.definition.WfNode end =
                new com.zifang.z.wf.core.definition.WfNode("e", "结束",
                        com.zifang.z.wf.core.definition.WfNodeType.END_EVENT);
        definition.setNodes(java.util.Arrays.asList(start, task, end));
        com.zifang.z.wf.core.definition.WfFlow f1 =
                new com.zifang.z.wf.core.definition.WfFlow("s", "t1");
        f1.setId("f1");
        com.zifang.z.wf.core.definition.WfFlow f2 =
                new com.zifang.z.wf.core.definition.WfFlow("t1", "e");
        f2.setId("f2");
        definition.setFlows(java.util.Arrays.asList(f1, f2));
        definition.buildIndex();
        return definition;
    }
}
