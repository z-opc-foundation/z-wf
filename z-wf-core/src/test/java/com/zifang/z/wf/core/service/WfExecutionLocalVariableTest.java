package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.engine.WfIdGenerator;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 变量作用域的第三层：执行（token）级 —— {@code setVariableLocal}。
 *
 * <p>本类盯五件，其中前两件是<b>修之前的行为、必须钉住不许回退</b>：
 * <ol>
 *   <li><b>任务级变量对条件表达式刻意不可见</b>。曾经按"设了却不生效"当成缺陷报过，
 *       探针实测才发现 {@code WfVariableServiceTest} 里早有断言：
 *       「任务级变量泄漏到流程级会让条件表达式读到不该读到的值」——
 *       实现与既定意图一致，是我的判断错了。本类把这条意图正式钉住。</li>
 *   <li><b>执行级变量对条件可见</b>，且<b>并行分支互不覆盖</b>。
 *       这才是 {@code setVariableLocal} 存在的理由：写流程级会互相污染，
 *       写任务级对条件完全无效，只剩"污染全局"或"完全无效"两个都不对的选项。</li>
 *   <li><b>读局部变量不做作用域回退</b>：token 上没设就是没有，
 *       哪怕外层有同名值。回退会让"这条分支覆盖了什么"无法回答。</li>
 *   <li><b>token 结束即失效</b>，改一个已经结束的分支的变量等于伪造记录。</li>
 *   <li><b>变更留审计</b>，与另两级同一套 comment 通道。</li>
 * </ol>
 */
class WfExecutionLocalVariableTest {

    /**
     * 两条并行分支，各有自己的排他网关。
     *
     * <p>判别的关键在"两条分支的判别式用<b>同一个变量名</b> {@code amount}"：
     * 只有局部作用域能让两条分支给出不同答案。用流程级变量时后写的会覆盖先写的，
     * 两条分支必然走同一边 —— 那正是本类要证伪的。
     */
    private static final String PARALLEL_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"localVarProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <parallelGateway id=\"pg\"/>\n"
            + "    <userTask id=\"branchA\" name=\"甲支线\" zifang:assignee=\"alice\"/>\n"
            + "    <userTask id=\"branchB\" name=\"乙支线\" zifang:assignee=\"bob\"/>\n"
            + "    <exclusiveGateway id=\"gwA\"/>\n"
            + "    <exclusiveGateway id=\"gwB\"/>\n"
            + "    <userTask id=\"aBig\" name=\"甲-大额\" zifang:assignee=\"aBig\"/>\n"
            + "    <userTask id=\"aSmall\" name=\"甲-小额\" zifang:assignee=\"aSmall\"/>\n"
            + "    <userTask id=\"bBig\" name=\"乙-大额\" zifang:assignee=\"bBig\"/>\n"
            + "    <userTask id=\"bSmall\" name=\"乙-小额\" zifang:assignee=\"bSmall\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <endEvent id=\"e2\"/>\n"
            + "    <endEvent id=\"e3\"/>\n"
            + "    <endEvent id=\"e4\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"pg\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"pg\" targetRef=\"branchA\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"pg\" targetRef=\"branchB\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"branchA\" targetRef=\"gwA\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"branchB\" targetRef=\"gwB\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"gwA\" targetRef=\"aBig\">\n"
            + "      <conditionExpression>amount &gt; 10000</conditionExpression>\n"
            + "    </sequenceFlow>\n"
            + "    <sequenceFlow id=\"f7\" sourceRef=\"gwA\" targetRef=\"aSmall\"/>\n"
            + "    <sequenceFlow id=\"f8\" sourceRef=\"gwB\" targetRef=\"bBig\">\n"
            + "      <conditionExpression>amount &gt; 10000</conditionExpression>\n"
            + "    </sequenceFlow>\n"
            + "    <sequenceFlow id=\"f9\" sourceRef=\"gwB\" targetRef=\"bSmall\"/>\n"
            + "    <sequenceFlow id=\"f10\" sourceRef=\"aBig\" targetRef=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f11\" sourceRef=\"aSmall\" targetRef=\"e2\"/>\n"
            + "    <sequenceFlow id=\"f12\" sourceRef=\"bBig\" targetRef=\"e3\"/>\n"
            + "    <sequenceFlow id=\"f13\" sourceRef=\"bSmall\" targetRef=\"e4\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfVariableService variables;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
        variables = new WfVariableService(repo, new WfIdGenerator.DefaultWfIdGenerator());
    }

    private String start() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(PARALLEL_BPMN));
        return runtime.startProcessInstance(definition, "LV-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
    }

    private WfTask openTask(String pid, String assignee) {
        List<WfTask> tasks = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(20));
        for (WfTask task : tasks) {
            if (assignee.equals(task.getAssignee())) {
                return task;
            }
        }
        return null;
    }

    /** 停在某个节点上的 token —— 局部变量挂在它身上。 */
    private WfExecution tokenAt(String pid, String activityId) {
        for (WfExecution execution : repo.findExecutionsByProcessInstance(pid)) {
            if (!execution.isEnded() && activityId.equals(execution.getActivityId())) {
                return execution;
            }
        }
        return null;
    }

    // ==================== 护栏：任务级对条件不可见是刻意的 ====================

    @Test
    @DisplayName("任务级变量对条件不可见是刻意的 —— 泄漏到流程级会让条件读到不该读的值")
    void taskVariableStaysOutOfConditions() {
        String pid = start();
        WfTask branchA = openTask(pid, "alice");
        assertNotNull(branchA);
        variables.setTaskVariable(branchA.getId(), "amount", 99999, "alice");
        assertEquals(99999, variables.getTaskVariable(branchA.getId(), "amount"),
                "前置条件：任务变量确实存下来了");

        runtime.completeTask(branchA.getId(), "alice", "办完", null);

        // 甲支线的网关此时没有任何可见的 amount（流程级没有、它自己的 token 上也没有），
        // 条件必然不成立 → 走小额分支
        assertNotNull(openTask(pid, "aSmall"),
                "任务变量泄漏到流程级的话这里会走大额分支 —— 那是刻意要避免的");
        assertNull(openTask(pid, "aBig"),
                "任务级变量不得影响流程走向");
    }

    // ==================== 执行级对条件可见 ====================

    @Test
    @DisplayName("执行级变量对条件可见：设在大额那条分支上就走大额")
    void executionVariableDrivesCondition() {
        String pid = start();
        WfExecution tokenA = tokenAt(pid, "branchA");
        assertNotNull(tokenA, "前置条件：甲支线的 token 应当停在它的任务上");
        variables.setVariableLocal(tokenA.getId(), "amount", 20000, "ops");

        runtime.completeTask(openTask(pid, "alice").getId(), "alice", "办完", null);

        assertNotNull(openTask(pid, "aBig"),
                "token 上设了 20000，条件 amount > 10000 应当成立");
        assertNull(openTask(pid, "aSmall"));
    }

    @Test
    @DisplayName("并行分支的局部值互不覆盖 —— 这是 setVariableLocal 存在的理由")
    void parallelBranchesKeepTheirOwnValues() {
        String pid = start();
        WfExecution tokenA = tokenAt(pid, "branchA");
        WfExecution tokenB = tokenAt(pid, "branchB");
        assertNotNull(tokenA);
        assertNotNull(tokenB);

        // 同一个变量名，两个不同的值。若作用域是流程级，后写的会覆盖先写的，
        // 两条分支必然走同一边 —— 那正是本用例要证伪的
        variables.setVariableLocal(tokenA.getId(), "amount", 20000, "ops");
        variables.setVariableLocal(tokenB.getId(), "amount", 500, "ops");

        runtime.completeTask(openTask(pid, "alice").getId(), "alice", "办完", null);
        runtime.completeTask(openTask(pid, "bob").getId(), "bob", "办完", null);

        assertNotNull(openTask(pid, "aBig"), "甲支线是大额");
        assertNotNull(openTask(pid, "bSmall"), "乙支线是小额");
        assertNull(openTask(pid, "aSmall"), "两条分支必须给出不同答案，否则局部作用域没起作用");
        assertNull(openTask(pid, "bBig"));
    }

    @Test
    @DisplayName("执行级变量不写进流程级命名空间")
    void executionVariableDoesNotLeakToInstance() {
        String pid = start();
        WfExecution tokenA = tokenAt(pid, "branchA");
        variables.setVariableLocal(tokenA.getId(), "amount", 20000, "ops");

        assertNull(variables.getVariable(pid, "amount"),
                "局部变量泄漏到流程级的话，别的分支也会跟着变 —— "
                        + "而那正是局部作用域存在的理由");
        assertFalse(variables.hasVariable(pid, "amount"));
    }

    // ==================== 读：不做作用域回退 ====================

    @Test
    @DisplayName("读局部变量不回退到流程级：token 上没设就是没有")
    void readDoesNotFallBackToInstance() {
        String pid = start();
        WfExecution tokenA = tokenAt(pid, "branchA");
        variables.setVariable(pid, "amount", 20000, "ops");

        assertEquals(20000, variables.getVariable(pid, "amount"), "流程级那个值确实在");
        assertNull(variables.getVariableLocal(tokenA.getId(), "amount"),
                "回退会让「这条分支覆盖了什么」无法回答 —— 而并行分支排障问的正是这个");
        assertFalse(variables.hasVariableLocal(tokenA.getId(), "amount"));
    }

    @Test
    @DisplayName("局部变量的增删查")
    void localVariableCrud() {
        String pid = start();
        WfExecution tokenA = tokenAt(pid, "branchA");

        assertFalse(variables.hasVariableLocal(tokenA.getId(), "k"));
        variables.setVariableLocal(tokenA.getId(), "k", "v1", "ops");
        assertTrue(variables.hasVariableLocal(tokenA.getId(), "k"));
        assertEquals("v1", variables.getVariableLocal(tokenA.getId(), "k"));

        Map<String, Object> all = variables.getVariablesLocal(tokenA.getId());
        assertEquals("v1", all.get("k"));
        all.put("injected", "x");
        // 这条断言对**当前两套持久化实现都没有区分力**，如实说明：
        // findExecution 返回的本来就是副本（内存走序列化拷贝、JDBC 走逐字段重建），
        // 所以即便服务层把内部 map 交出去，往返回对象里 put 也进不了存储 ——
        // 反向验证摘掉服务层那层拷贝，本用例照样绿，证实了这点。
        // 仍然保留是因为它钉的是**服务层自己的契约**（不把内部引用交出去），
        // 而一旦哪天存储层改成缓存同一份对象，这条就会立刻变红。
        assertFalse(variables.hasVariableLocal(tokenA.getId(), "injected"),
                "返回的必须是副本 —— 直接给内部 map 的话，调用方改一下就绕过了落库与审计");

        variables.removeVariableLocal(tokenA.getId(), "k", "ops");
        assertFalse(variables.hasVariableLocal(tokenA.getId(), "k"));

        // 删不存在的变量不报错：删除的语义是"保证它不存在"
        variables.removeVariableLocal(tokenA.getId(), "k", "ops");
    }

    // ==================== 拒绝 ====================

    @Test
    @DisplayName("已结束的 token 不能改局部变量")
    void endedTokenRejectsMutation() {
        String pid = start();
        WfTask branchA = openTask(pid, "alice");
        variables.setVariableLocal(branchA.getExecutionId(), "amount", 20000, "ops");
        runtime.completeTask(branchA.getId(), "alice", "办完", null);

        // 该 token 已进入 aBig/aSmall，尚未结束，所以还能改 ——
        // 真正要挡住的是分支彻底走完之后
        WfExecution alive = repo.findExecution(branchA.getExecutionId());
        assertNotNull(alive);
        for (WfTask task : repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(20))) {
            runtime.completeTask(task.getId(), task.getAssignee(), "办完", null);
        }

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> variables.setVariableLocal(branchA.getExecutionId(), "k", "v", "ops"));
        assertTrue(ex.getMessage().contains("已结束"),
                "分支走完之后改它的局部变量等于伪造记录。实际 " + ex.getMessage());
    }

    @Test
    @DisplayName("token 不存在 / id 为空 / 变量名为空 / 值为 null：都要当场报错")
    void badArgumentsRejected() {
        String pid = start();
        WfExecution tokenA = tokenAt(pid, "branchA");

        assertTrue(assertThrows(WfEngineException.class,
                () -> variables.setVariableLocal("no-such-token", "k", "v", "ops"))
                .getMessage().contains("不存在"));
        assertTrue(assertThrows(WfEngineException.class,
                () -> variables.setVariableLocal("  ", "k", "v", "ops"))
                .getMessage().contains("不能为空"));
        assertTrue(assertThrows(WfEngineException.class,
                () -> variables.setVariableLocal(tokenA.getId(), "  ", "v", "ops"))
                .getMessage().contains("不能为空"));
        assertTrue(assertThrows(WfEngineException.class,
                () -> variables.setVariableLocal(tokenA.getId(), "k", null, "ops"))
                .getMessage().contains("removeVariableLocal"),
                "null 值的报错要直接告诉调用方改用哪个方法");
    }

    // ==================== 审计 ====================

    @Test
    @DisplayName("局部变量变更留审计，且写明是哪条 token")
    void localVariableChangeIsAudited() {
        String pid = start();
        WfExecution tokenA = tokenAt(pid, "branchA");
        variables.setVariableLocal(tokenA.getId(), "amount", 20000, "ops");
        variables.setVariableLocal(tokenA.getId(), "amount", 500, "ops2");
        variables.removeVariableLocal(tokenA.getId(), "amount", "ops3");

        boolean sawSet = false;
        boolean sawRemove = false;
        for (WfComment comment : repo.findComments(pid)) {
            if (!WfVariableService.COMMENT_TYPE_VARIABLE.equals(comment.getType())) {
                continue;
            }
            String text = comment.getContent();
            if (text.contains("branchA(" + tokenA.getId() + ")") && text.contains("20000")
                    && "ops".equals(comment.getUserId())) {
                sawSet = true;
            }
            if (text.contains("已删除") && "ops3".equals(comment.getUserId())) {
                sawRemove = true;
            }
        }
        assertTrue(sawSet, "变更要留审计，并写明是哪条 token —— 并行分支下没有这个就分不清谁改的");
        assertTrue(sawRemove, "删除也要留审计");
    }
}
