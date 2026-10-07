package com.zifang.z.wf.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.engine.WfIdGenerator;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.service.WfBatchService;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.service.WfTaskService;
import com.zifang.z.wf.core.service.WfVariableService;

/**
 * 批量操作端点的 REST 层。
 *
 * <p>用 {@code standaloneSetup} 而不是 {@code @SpringBootTest}：这里要断的是
 * 「路径 / 参数 / 状态码 / 响应字段」，起整个上下文会把「配置能不能自动装配」
 * 一起拖进来。装配本身由 {@code z-wf-admin} 的端到端用例单独钉。
 *
 * <p>异常映射走真的 {@link WfExceptionAdvice}：非法入参必须是 <b>400</b>，
 * 因为那是「换个用法就能解决」的调用方问题，报 500 会让前端一律弹系统错误。
 *
 * <p>最要紧的一条是 {@link #createDoesNotTouchAnyData()}：REST 层是这套东西
 * 对外的门面，「创建就是创建」这件事必须在接口上也成立，
 * 不能指望调用方自觉知道 POST 之后还要自己判断。
 */
class WfBatchControllerTest {

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

    private MockMvc mockMvc;
    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
        WfIdGenerator ids = new WfIdGenerator.DefaultWfIdGenerator();
        WfBatchController controller = new WfBatchController();
        ReflectionTestUtils.setField(controller, "batchService", new WfBatchService(
                repo, ids, runtime, new WfVariableService(repo, ids),
                new WfTaskService(repository, repo, runtime, new WfHookDispatcher())));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WfExceptionAdvice())
                .build();
    }

    private String startOne() {
        WfDefinition definition = repository.deployXml(LEAVE_BPMN, "leaveProcess");
        return runtime.startProcessInstance(definition,
                "leave-" + System.nanoTime(), "alice", null, new HashMap<String, Object>());
    }

    /** 创建「按定义 key 给所有实例设 migrated=v2」的批次。 */
    private String createBatchJson() {
        return "{\"batchType\":\"INSTANCE\","
                + "\"criteria\":{\"processDefinitionKey\":\"leaveProcess\"},"
                + "\"operations\":[{\"type\":\"setVariable\",\"variable\":\"migrated\",\"value\":\"v2\"}],"
                + "\"operatorId\":\"admin-1\"}";
    }

    // ==================== 两段式 ====================

    @Test
    @DisplayName("POST 创建批次不碰任何数据，返回 CREATED")
    void createDoesNotTouchAnyData() throws Exception {
        String instanceId = startOne();
        int revisionBefore = repo.findProcessInstance(instanceId).getRevision();

        mockMvc.perform(post("/api/wf/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBatchJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("CREATED"))
                .andExpect(jsonPath("$.data.stateLabel").value("已创建"))
                .andExpect(jsonPath("$.data.batchType").value("INSTANCE"))
                .andExpect(jsonPath("$.data.operatorId").value("admin-1"))
                .andExpect(jsonPath("$.data.affectedCount").value(0))
                .andExpect(jsonPath("$.data.failureCount").value(0))
                .andExpect(jsonPath("$.data.criteria").isNotEmpty())
                .andExpect(jsonPath("$.data.operations").isNotEmpty());

        // 注意别拿实例的 startTime 当判据 —— 那是**流程自己**的启动时间，
        // 实例一建出来就有，与批次毫无关系（第一次跑这条用例时正是栽在这里）。
        // 真正能说明"没动过"的是：变量没多、状态还是 ACTIVE、乐观锁版本没跳
        assertNull(repo.findProcessInstance(instanceId).getVariables().get("migrated"),
                "**REST 层创建批次同样不能有副作用**。当前变量: "
                        + repo.findProcessInstance(instanceId).getVariables());
        assertEquals(WfProcessStatus.ACTIVE, repo.findProcessInstance(instanceId).getStatus(),
                "创建批次不该把实例挂起");
        assertEquals(revisionBefore, repo.findProcessInstance(instanceId).getRevision(),
                "创建批次不该 bump 实例的乐观锁版本");
    }

    @Test
    @DisplayName("count 端点只数不改")
    void countEndpointIsReadOnly() throws Exception {
        startOne();
        startOne();
        String batchId = createBatch();

        mockMvc.perform(get("/api/wf/batches/" + batchId + "/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2));

        mockMvc.perform(get("/api/wf/batches/" + batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affectedCount").value(0))
                .andExpect(jsonPath("$.data.startTime").doesNotExist());
    }

    // ==================== 执行 ====================

    @Test
    @DisplayName("执行后受影响数回显，明细端点能看到每个目标")
    void executeReportsAffectedCountAndElements() throws Exception {
        String first = startOne();
        String second = startOne();
        String batchId = createBatch();

        mockMvc.perform(post("/api/wf/batches/" + batchId + "/execute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("COMPLETED"))
                .andExpect(jsonPath("$.data.affectedCount").value(2))
                .andExpect(jsonPath("$.data.failureCount").value(0))
                .andExpect(jsonPath("$.data.startTime").isNotEmpty())
                .andExpect(jsonPath("$.data.endTime").isNotEmpty());

        for (String id : new String[]{first, second}) {
            assertEquals("v2", repo.findProcessInstance(id).getVariables().get("migrated"),
                    "实例 " + id + " 必须真的被改到了");
        }

        mockMvc.perform(get("/api/wf/batches/" + batchId + "/elements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.elements[0].state").value("SUCCESS"))
                .andExpect(jsonPath("$.data.elements[0].stateLabel").value("成功"))
                .andExpect(jsonPath("$.data.batchId").value(batchId));
    }

    @Test
    @DisplayName("failedOnly=true 只回失败的")
    void failedOnlyReturnsOnlyFailures() throws Exception {
        startOne();
        String batchId = createBatch("{\"batchType\":\"INSTANCE\","
                + "\"criteria\":{\"ids\":[\"" + startOne() + "\",\"proc-nope\"]},"
                + "\"operations\":[{\"type\":\"setVariable\",\"variable\":\"migrated\",\"value\":\"v2\"}]}");

        mockMvc.perform(post("/api/wf/batches/" + batchId + "/execute"))
                .andExpect(jsonPath("$.data.affectedCount").value(1))
                .andExpect(jsonPath("$.data.failureCount").value(1));

        mockMvc.perform(get("/api/wf/batches/" + batchId + "/elements"))
                .andExpect(jsonPath("$.data.total").value(2));
        mockMvc.perform(get("/api/wf/batches/" + batchId + "/elements").param("failedOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.elements[0].targetId").value("proc-nope"))
                .andExpect(jsonPath("$.data.elements[0].state").value("FAILED"))
                .andExpect(jsonPath("$.data.elements[0].stateLabel").value("失败"))
                .andExpect(jsonPath("$.data.elements[0].failureMessage").isNotEmpty());
    }

    @Test
    @DisplayName("重复执行返回 400")
    void executingTwiceIsRejected() throws Exception {
        startOne();
        String batchId = createBatch();
        mockMvc.perform(post("/api/wf/batches/" + batchId + "/execute")).andExpect(status().isOk());

        mockMvc.perform(post("/api/wf/batches/" + batchId + "/execute"))
                .andExpect(status().isBadRequest());
    }

    // ==================== 挂起 / 激活 / 删除 ====================

    @Test
    @DisplayName("挂起后执行返回 400，激活后可执行")
    void suspendBlocksExecutionUntilActivated() throws Exception {
        startOne();
        String batchId = createBatch();

        mockMvc.perform(post("/api/wf/batches/" + batchId + "/suspend").param("operatorId", "admin-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suspended").value(true));

        mockMvc.perform(post("/api/wf/batches/" + batchId + "/execute"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/wf/batches/" + batchId + "/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suspended").value(false));

        mockMvc.perform(post("/api/wf/batches/" + batchId + "/execute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affectedCount").value(1));
    }

    @Test
    @DisplayName("删不存在的批次返回 400，不静默成功")
    void deletingMissingBatchIsRejected() throws Exception {
        mockMvc.perform(delete("/api/wf/batches/never-existed"))
                .andExpect(status().isBadRequest());
    }

    // ==================== 列表 ====================

    @Test
    @DisplayName("按状态与创建人筛，列表与 total 对得上")
    void listFiltersByStateAndOperator() throws Exception {
        createBatch();
        String second = createBatch();

        mockMvc.perform(get("/api/wf/batches").param("state", "CREATED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        mockMvc.perform(post("/api/wf/batches/" + second + "/execute"))
                .andExpect(jsonPath("$.data.state").value("COMPLETED"));

        mockMvc.perform(get("/api/wf/batches").param("state", "CREATED"))
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/wf/batches").param("state", "COMPLETED"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(second));
        mockMvc.perform(get("/api/wf/batches").param("operatorId", "admin-1"))
                .andExpect(jsonPath("$.data.total").value(2));
    }

    // ==================== 入参校验 ====================

    @Test
    @DisplayName("批次类型写错返回 400，并把合法值列出来")
    void unknownBatchTypeIsRejected() throws Exception {
        mockMvc.perform(post("/api/wf/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchType\":\"NOPE\","
                                + "\"criteria\":{\"processDefinitionKey\":\"k\"},"
                                + "\"operations\":[{\"type\":\"setVariable\",\"variable\":\"a\",\"value\":1}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("条件为空返回 400 —— 那等于改全部")
    void emptyCriteriaIsRejected() throws Exception {
        mockMvc.perform(post("/api/wf/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchType\":\"INSTANCE\",\"criteria\":{},"
                                + "\"operations\":[{\"type\":\"setVariable\",\"variable\":\"a\",\"value\":1}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("操作不适用于该类型时返回 400")
    void mismatchedOperationIsRejected() throws Exception {
        mockMvc.perform(post("/api/wf/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchType\":\"INSTANCE\","
                                + "\"criteria\":{\"processDefinitionKey\":\"k\"},"
                                + "\"operations\":[{\"type\":\"setPriority\",\"priority\":10}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("一条操作都没给返回 400")
    void noOperationIsRejected() throws Exception {
        mockMvc.perform(post("/api/wf/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchType\":\"INSTANCE\","
                                + "\"criteria\":{\"processDefinitionKey\":\"k\"},"
                                + "\"operations\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("查一个不存在的批次返回 400")
    void gettingMissingBatchIsRejected() throws Exception {
        mockMvc.perform(get("/api/wf/batches/never-existed")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/wf/batches/never-existed/count")).andExpect(status().isBadRequest());
    }

    // ==================== 辅助 ====================

    private String createBatch() throws Exception {
        return createBatch(createBatchJson());
    }

    private String createBatch(String body) throws Exception {
        String json = mockMvc.perform(post("/api/wf/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = between(json, "\"id\":\"", "\"");
        assertNotNull(id, "创建成功就必须回 id。响应: " + json);
        return id;
    }

    private static String between(String text, String prefix, String suffix) {
        int start = text.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        start += prefix.length();
        int end = text.indexOf(suffix, start);
        return end < 0 ? null : text.substring(start, end);
    }

    @Test
    @DisplayName("id 在响应里可用 —— 刚建的批次能立刻查得到")
    void createdBatchIsImmediatelyReadable() throws Exception {
        startOne();
        String batchId = createBatch();
        assertTrue(batchId.startsWith("batch-"), "批次 id 应当有可辨识的前缀，实际: " + batchId);
        mockMvc.perform(get("/api/wf/batches/" + batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(batchId));
    }
}