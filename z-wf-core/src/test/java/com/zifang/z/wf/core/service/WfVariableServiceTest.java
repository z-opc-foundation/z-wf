package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfIdGenerator;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfVariableAuditQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 变量服务的行为约定。
 *
 * <p>这个服务的价值全在"把绕过服务直接改实体的那条路收回来"上，
 * 所以用例盯的是那条路上最容易出事的地方：乐观锁有没有被绕过、
 * 变更有没有留痕、null 会不会被当成删除。
 */
class WfVariableServiceTest {

    private static final String LEAVE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"leaveProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"主管审批\" zifang:assignee=\"boss\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfVariableService variables;
    private WfHistoryService history;
    private String processInstanceId;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo,
                new com.zifang.z.wf.core.engine.WfEngine(),
                new com.zifang.z.wf.core.hook.WfHookDispatcher());
        variables = new WfVariableService(repo, new WfIdGenerator.DefaultWfIdGenerator());
        history = new WfHistoryService(repo);

        WfDefinition definition = repository.deploy(new WfXmlParser().parse(LEAVE_BPMN));
        Map<String, Object> initial = new HashMap<>();
        initial.put("days", 3);
        processInstanceId = runtime.startProcessInstance(
                definition, "leave-001", "employee-1", null, initial);
    }

    private WfTask theTask() {
        List<WfTask> open = repo.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(processInstanceId).setOpenOnly(true)
                .setPageNum(1).setPageSize(10));
        assertEquals(1, open.size(), "前置条件：应当恰好有一条待办");
        return open.get(0);
    }

    @Test
    @DisplayName("读得到启动时传入的变量")
    void readsExistingVariable() {
        assertEquals(3, variables.getVariable(processInstanceId, "days"));
        assertTrue(variables.hasVariable(processInstanceId, "days"));
        assertFalse(variables.hasVariable(processInstanceId, "amount"));
        assertEquals(3, variables.getVariables(processInstanceId).get("days"));
    }

    @Test
    @DisplayName("写入后 bump revision —— 乐观锁不能被绕过")
    void setVariableBumpsRevision() {
        int before = repo.findProcessInstance(processInstanceId).getRevision();
        variables.setVariable(processInstanceId, "amount", 1000, "admin-1");
        int after = repo.findProcessInstance(processInstanceId).getRevision();
        assertTrue(after > before,
                "改变量必须 bump revision，否则并发改同一单据时乐观锁形同虚设。before="
                        + before + " after=" + after);
    }

    @Test
    @DisplayName("每次变更都留审计痕迹，带操作人")
    void everyChangeLeavesAuditTrail() {
        variables.setVariable(processInstanceId, "amount", 1000, "admin-1");
        variables.setVariable(processInstanceId, "amount", 500, "admin-2");
        variables.removeVariable(processInstanceId, "amount", "admin-3");

        List<WfComment> comments = runtime.getComments(processInstanceId);
        List<WfComment> variableComments = new java.util.ArrayList<>();
        for (WfComment comment : comments) {
            if (WfVariableService.COMMENT_TYPE_VARIABLE.equals(comment.getType())) {
                variableComments.add(comment);
            }
        }
        assertEquals(3, variableComments.size(), "三次变更应当留下三条审计：" + comments);

        assertEquals("admin-1", variableComments.get(0).getUserId());
        assertTrue(variableComments.get(0).getContent().contains("amount: (未设置) -> 1000"),
                "首条应记录变更前是未设置：" + variableComments.get(0).getContent());
        assertTrue(variableComments.get(1).getContent().contains("amount: 1000 -> 500"),
                "第二条应记录前后值：" + variableComments.get(1).getContent());
        assertTrue(variableComments.get(2).getContent().contains("已删除"),
                "删除应有明确标记，不能只写个 null：" + variableComments.get(2).getContent());
    }

    // ==================== 变更查询 ====================

    @Test
    @DisplayName("按变量名查变更：前缀精确匹配，amount 不该命中 discount_amount")
    void queryChangesByVariableName() {
        variables.setVariable(processInstanceId, "amount", 1000, "admin-1");
        variables.setVariable(processInstanceId, "discount_amount", 50, "admin-1");
        variables.setVariable(processInstanceId, "amount", 500, "admin-2");

        List<WfComment> amountChanges = history.queryVariableChanges(
                new WfVariableAuditQuery().setProcessInstanceId(processInstanceId)
                        .setVariableName("amount").setPageNum(1).setPageSize(50));

        assertEquals(2, amountChanges.size(),
                "只该返回 amount 自己的两条。子串匹配会把 discount_amount 带进来，"
                        + "而审计给出错的行比不给行更糟");
        for (WfComment c : amountChanges) {
            assertTrue(c.getContent().startsWith("amount:"),
                    "每条都应精确以 amount: 开头，实际: " + c.getContent());
        }
        assertEquals(2, history.countVariableChanges(
                new WfVariableAuditQuery().setProcessInstanceId(processInstanceId)
                        .setVariableName("amount")));

        assertEquals(1, history.queryVariableChanges(
                new WfVariableAuditQuery().setProcessInstanceId(processInstanceId)
                        .setVariableName("discount_amount").setPageNum(1).setPageSize(50))
                .size());
    }

    @Test
    @DisplayName("按操作人查变更 + 倒序返回")
    void queryChangesByOperatorAndOrder() {
        variables.setVariable(processInstanceId, "amount", 1000, "admin-1");
        variables.setVariable(processInstanceId, "amount", 800, "admin-2");
        variables.setVariable(processInstanceId, "level", 3, "admin-1");

        List<WfComment> byAdmin1 = history.queryVariableChanges(
                new WfVariableAuditQuery().setProcessInstanceId(processInstanceId)
                        .setChangedBy("admin-1").setPageNum(1).setPageSize(50));
        assertEquals(2, byAdmin1.size());
        for (WfComment c : byAdmin1) {
            assertEquals("admin-1", c.getUserId());
        }
        // 倒序：最后写入的排在最前
        assertTrue(byAdmin1.get(0).getContent().contains("level"),
                "审计是『越新越先看』，与轨迹的正序相反。实际首条: "
                        + byAdmin1.get(0).getContent());
        assertEquals(2, history.countVariableChanges(
                new WfVariableAuditQuery().setProcessInstanceId(processInstanceId)
                        .setChangedBy("admin-1")));
    }

    @Test
    @DisplayName("批量写入：每个变量一条审计，不拼成一整段文本")
    void batchWriteLeavesOneAuditPerVariable() {
        Map<String, Object> batch = new LinkedHashMap<String, Object>();
        batch.put("amount", 1000);
        batch.put("level", 3);
        batch.put("remark", "加急");
        variables.setVariables(processInstanceId, batch, "admin-batch");

        List<WfComment> all = history.queryVariableChanges(
                new WfVariableAuditQuery().setProcessInstanceId(processInstanceId)
                        .setPageNum(1).setPageSize(50));
        assertEquals(3, all.size(),
                "三个变量三条审计。拼成一条的话，按变量名查就只能拿 LIKE 猜，"
                        + "而 amount 会命中 discount_amount。实际: " + all.size() + " " + all);
        for (String name : new String[]{"amount", "level", "remark"}) {
            assertEquals(1, history.queryVariableChanges(
                    new WfVariableAuditQuery().setProcessInstanceId(processInstanceId)
                            .setVariableName(name).setPageNum(1).setPageSize(50)).size(),
                    "变量 " + name + " 应当恰好有一条审计");
        }
    }

    @Test
    @DisplayName("批量写入跨过两位数时，倒序仍然按真实写入顺序")
    void batchWriteAcrossDigitBoundaryKeepsOrder() {
        // 这一条盯的是 WfIdGenerator：审计在同一毫秒内只能靠 id 兜底排序，
        // 而未补零的 "cmt-x-9" 在字符串比较里大于 "cmt-x-10"（'9' > '1'），
        // 于是批量写 10 个以上变量时，"越新越先看"会在第 9/10 条之间整个翻掉。
        // 12 个变量确保一定跨过这条边界（10 个时 id 只到两位，测不出来）。
        Map<String, Object> batch = new LinkedHashMap<String, Object>();
        for (int i = 0; i < 12; i++) {
            batch.put("v" + i, i);
        }
        variables.setVariables(processInstanceId, batch, "admin-batch");

        List<WfComment> rows = history.queryVariableChanges(
                new WfVariableAuditQuery().setProcessInstanceId(processInstanceId)
                        .setPageNum(1).setPageSize(50));
        assertEquals(12, rows.size());

        // 倒序 = 写入顺序的严格逆序：v11 在最前，v0 在最后
        for (int i = 0; i < 12; i++) {
            String expected = "v" + (11 - i) + ":";
            assertTrue(rows.get(i).getContent().startsWith(expected),
                    "第 " + i + " 条应为 " + expected + "（写入顺序的逆序），实际: "
                            + rows.get(i).getContent());
        }
    }

    @Test
    @DisplayName("变更查询只看变量审计，人工评论不会混进来")
    void queryChangesExcludesHumanComments() {
        variables.setVariable(processInstanceId, "amount", 1000, "admin-1");
        runtime.addComment(processInstanceId, null, "boss", "comment", "同意，走加急");

        List<WfComment> changes = history.queryVariableChanges(
                new WfVariableAuditQuery().setProcessInstanceId(processInstanceId)
                        .setPageNum(1).setPageSize(50));
        assertEquals(1, changes.size(), "人工评论不该被当成变量变更");
        assertEquals(WfVariableService.COMMENT_TYPE_VARIABLE, changes.get(0).getType());
    }

    @Test
    @DisplayName("分页：total 是全量条数，且各页不重不漏")
    void queryChangesPaginates() {
        for (int i = 0; i < 7; i++) {
            variables.setVariable(processInstanceId, "amount", 1000 + i, "admin-" + i);
        }
        WfVariableAuditQuery query = new WfVariableAuditQuery()
                .setProcessInstanceId(processInstanceId).setVariableName("amount");
        assertEquals(7, history.countVariableChanges(query));
        assertEquals(7, history.countVariableChanges(query.setPageNum(2).setPageSize(3)),
                "带分页参数也必须返回全量");

        java.util.Set<String> seen = new java.util.LinkedHashSet<String>();
        int total = 0;
        for (int page = 1; page <= 3; page++) {
            List<WfComment> rows = history.queryVariableChanges(
                    query.setPageNum(page).setPageSize(3));
            for (WfComment c : rows) {
                assertTrue(seen.add(c.getId()), "第 " + page + " 页出现重复行");
            }
            total += rows.size();
        }
        assertEquals(7, total, "三页合起来正好覆盖 7 条，最后一页按实际条数返回");
        assertTrue(history.queryVariableChanges(
                query.setPageNum(4).setPageSize(3)).isEmpty(), "越界页为空");
    }

    @Test
    @DisplayName("批量写入只 bump 一次 revision —— 部分成功比全不成更糟")
    void batchWriteIsAtomic() {
        int before = repo.findProcessInstance(processInstanceId).getRevision();
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("a", 1);
        batch.put("b", 2);
        batch.put("c", 3);
        variables.setVariables(processInstanceId, batch, "admin-1");

        WfProcessInstance after = repo.findProcessInstance(processInstanceId);
        assertEquals(before + 1, after.getRevision(),
                "整批应当只落一次库、只 bump 一次；逐个保存会留下部分更新的实例");
        assertEquals(1, after.getVariables().get("a"));
        assertEquals(3, after.getVariables().get("c"));
    }

    @Test
    @DisplayName("批量里含 null 值时整批不生效 —— 校验先于写入")
    void batchWithNullValueIsRejectedWholesale() {
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("ok", 1);
        batch.put("bad", null);

        assertThrows(WfEngineException.class,
                () -> variables.setVariables(processInstanceId, batch, "admin-1"));

        WfProcessInstance after = repo.findProcessInstance(processInstanceId);
        assertFalse(after.getVariables().containsKey("ok"),
                "校验没过就一个都不能写，否则调用方拿到异常却不知道已经改了哪些");
    }

    @Test
    @DisplayName("setVariable 传 null 被拒绝，不会静默变成删除")
    void nullValueIsRejectedNotSilentlyRemoved() {
        variables.setVariable(processInstanceId, "amount", 1000, "admin-1");
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> variables.setVariable(processInstanceId, "amount", null, "admin-1"));
        assertTrue(ex.getMessage().contains("removeVariable"),
                "报错要指路 removeVariable：" + ex.getMessage());
        assertEquals(1000, variables.getVariable(processInstanceId, "amount"),
                "被拒绝的写入不能有任何效果");
    }

    @Test
    @DisplayName("重复删除不报错，删不存在的变量也不报错")
    void removingAbsentVariableIsNoop() {
        variables.removeVariable(processInstanceId, "neverExisted", "admin-1");
        variables.setVariable(processInstanceId, "x", 1, "admin-1");
        variables.removeVariable(processInstanceId, "x", "admin-1");
        variables.removeVariable(processInstanceId, "x", "admin-1");
        assertFalse(variables.hasVariable(processInstanceId, "x"));
    }

    @Test
    @DisplayName("流程结束后拒绝改变量")
    void terminalInstanceRejectsMutation() {
        WfTask task = theTask();
        runtime.completeTask(task.getId(), "boss", "同意", new HashMap<>());

        assertTrue(repo.findProcessInstance(processInstanceId).getStatus().isTerminal());
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> variables.setVariable(processInstanceId, "amount", 1, "admin-1"));
        assertTrue(ex.getMessage().contains("已结束"),
                "报错要说明是终态：" + ex.getMessage());
    }

    @Test
    @DisplayName("任务级变量不进流程命名空间，因此不会被条件表达式当成流程变量")
    void taskVariableIsNotProcessVariable() {
        WfTask task = theTask();
        variables.setTaskVariable(task.getId(), "localOnly", "v", "boss");

        assertEquals("v", variables.getTaskVariable(task.getId(), "localOnly"));
        assertFalse(variables.hasVariable(processInstanceId, "localOnly"),
                "任务级变量泄漏到流程级会让条件表达式读到不该读到的值");
    }

    @Test
    @DisplayName("任务办结后不能改它的变量")
    void completedTaskRejectsMutation() {
        WfTask task = theTask();
        runtime.completeTask(task.getId(), "boss", "同意", new HashMap<>());
        assertThrows(WfEngineException.class,
                () -> variables.setTaskVariable(task.getId(), "k", "v", "boss"));
    }

    @Test
    @DisplayName("变量变更会被后续的引擎操作看到")
    void engineSeesLaterVariableChanges() {
        // 启动时只有 days；这里补一个 amount，再由引擎推进。
        // 验证的是"变量服务写的值"和"引擎读的实例"确实是同一份数据，
        // 而不是各写各的。
        variables.setVariable(processInstanceId, "amount", 888, "admin-1");
        WfTask task = theTask();
        Map<String, Object> completion = new HashMap<>();
        completion.put("approved", true);
        runtime.completeTask(task.getId(), "boss", "同意", completion);

        WfProcessInstance done = repo.findProcessInstance(processInstanceId);
        assertEquals(888, done.getVariables().get("amount"),
                "变量服务写入的值应当出现在引擎最终落库的实例上");
        assertEquals(true, done.getVariables().get("approved"));
    }

    @Test
    @DisplayName("不存在的实例/任务报明确的错，不返回 null 让调用方自己猜")
    void missingEntitiesFailLoudly() {
        assertTrue(assertThrows(WfEngineException.class,
                () -> variables.getVariable("proc-nope", "x")).getMessage().contains("不存在"));
        assertTrue(assertThrows(WfEngineException.class,
                () -> variables.getTaskVariable("task-nope", "x")).getMessage().contains("不存在"));
        assertNotNull(assertThrows(WfEngineException.class,
                () -> variables.setVariable(processInstanceId, "  ", 1, "admin-1")));
    }
}
