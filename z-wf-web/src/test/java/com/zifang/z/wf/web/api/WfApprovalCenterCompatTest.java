package com.zifang.z.wf.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.service.WfTaskService;
import com.zifang.z.wf.web.mapper.WfViewMapper;

/**
 * 审批中心对 z-camuda 前端的兼容契约。
 *
 * <p>断的是三件事：发起请求用 {@code processKey/initiator/title} 别名能成功；
 * 办结只发 {@code approvalResult+comment}（不带 userId/变量）能推进网关；
 * 实例/定义 VO 补出了前端要的 {@code processName/initiator/status/id} 字段。
 */
class WfApprovalCenterCompatTest {

    private static final String NS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n";
    private static final String NS_END = "</definitions>\n";

    private static final String LEAVE_BPMN = NS
            + "  <process id=\"leave\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"admin\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + NS_END;

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
        WfTaskService taskService = new WfTaskService(repository, repo, runtime,
                new WfHookDispatcher());
        repository.deployXml(LEAVE_BPMN, "leave");

        WfApprovalCenterController controller = new WfApprovalCenterController();
        ReflectionTestUtils.setField(controller, "runtimeService", runtime);
        ReflectionTestUtils.setField(controller, "taskService", taskService);
        ReflectionTestUtils.setField(controller, "repositoryService", repository);
        ReflectionTestUtils.setField(controller, "viewMapper", new WfViewMapper(runtime));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WfExceptionAdvice())
                .build();
    }

    @Test
    @DisplayName("发起用 processKey/initiator/title 别名 ⇒ 成功，列表回 processName/initiator/小写 running")
    void startWithCamudaAliases() throws Exception {
        mockMvc.perform(post("/api/approval-center/processes/start")
                        .contentType("application/json")
                        .content("{\"processKey\":\"leave\",\"initiator\":\"zifang\","
                                + "\"title\":\"张三请假\",\"variables\":{\"days\":1}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isNotEmpty());

        mockMvc.perform(get("/api/approval-center/my-processes")
                        .param("userId", "zifang")
                        .param("pageNum", "1").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].processName").value("张三请假"))
                .andExpect(jsonPath("$.data.records[0].initiator").value("zifang"))
                .andExpect(jsonPath("$.data.records[0].status").value("running"));
    }

    @Test
    @DisplayName("发起请求不传 processKey ⇒ 400 提示，而不是 500")
    void startWithoutKeyFailsClearly() throws Exception {
        mockMvc.perform(post("/api/approval-center/processes/start")
                        .contentType("application/json")
                        .content("{\"initiator\":\"zifang\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("definitionKey/processKey 不能为空")));
    }

    @Test
    @DisplayName("办结只发 approvalResult+comment（无 userId）⇒ 走 assignee、变量写回、状态 completed")
    void completeWithApprovalResultOnly() throws Exception {
        String pid = runtime.startProcessInstance("leave", "biz-1", "zifang",
                new HashMap<String, Object>());
        WfTask task = firstOpenTask(pid);

        mockMvc.perform(post("/api/approval-center/tasks/complete")
                        .contentType("application/json")
                        .content("{\"taskId\":\"" + task.getId()
                                + "\",\"approvalResult\":\"approved\",\"comment\":\"同意\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("completed"))
                .andExpect(jsonPath("$.data.result").value("completed"));

        assertEquals("approved",
                runtime.getProcessInstance(pid).getVariables().get("approvalResult"),
                "approvalResult 应并入流程变量，网关条件才能命中");
        assertEquals("approved",
                runtime.getProcessInstance(pid).getVariables().get("processResult"),
                "processResult 是引擎结算实例 result 的变量名，办结时一并写入");
    }

    @Test
    @DisplayName("定义列表补出 id(key:version) 与 status(active/suspended)")
    void definitionsCarryIdAndStatus() throws Exception {
        mockMvc.perform(get("/api/approval-center/processes/definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("leave:1"))
                .andExpect(jsonPath("$.data[0].status").value("active"));
    }

    @Test
    @DisplayName("按前端词表 running 过滤我发起的 ⇒ 命中并转成引擎 ACTIVE")
    void filterByCamudaVocabulary() throws Exception {
        runtime.startProcessInstance("leave", "biz-2", "zifang", new HashMap<String, Object>());
        mockMvc.perform(get("/api/approval-center/my-processes")
                        .param("userId", "zifang")
                        .param("status", "running")
                        .param("pageNum", "1").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].status").value("running"));
    }

    private WfTask firstOpenTask(String processInstanceId) {
        List<WfTask> tasks = repo.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(processInstanceId)
                .setOpenOnly(true).setPageNum(1).setPageSize(10));
        assertEquals(1, tasks.size(), "发起后应恰好一张待办");
        return tasks.get(0);
    }
}