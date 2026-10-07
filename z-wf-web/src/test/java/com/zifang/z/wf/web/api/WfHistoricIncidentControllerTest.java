package com.zifang.z.wf.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.service.WfExternalTaskService;
import com.zifang.z.wf.core.service.WfHistoryService;
import com.zifang.z.wf.core.service.WfHistoricIncidentService;
import com.zifang.z.wf.core.service.WfJobService;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.service.WfTaskService;
import com.zifang.z.wf.web.mapper.WfViewMapper;

/**
 * 历史故障端点的 REST 层。
 *
 * <p>用 {@code standaloneSetup}：这里要断的是路径 / 参数 / 状态码 / 响应字段，
 * 自动装配由 {@code z-wf-admin} 的端到端用例单独钉。
 *
 * <p>最要紧的一条是 {@link #historyOutlivesTheJob()} ——
 * 这套能力存在的理由就是「job 好了之后还能查到它坏过」，
 * 而 REST 层少一次转换、少一个字段映射都可能把它悄悄削掉。
 */
class WfHistoricIncidentControllerTest {

    private static final String ORDER_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"orderProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <serviceTask id=\"notify\" name=\"通知下游\""
            + " zifang:type=\"external\" zifang:topic=\"order.create\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"notify\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"notify\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private MockMvc mockMvc;
    private InMemoryWorkflowPersistence repo;
    private WfRuntimeService runtime;
    private WfExternalTaskService external;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
        external = new WfExternalTaskService(repo, runtime);

        WfHistoryController controller = new WfHistoryController();
        ReflectionTestUtils.setField(controller, "historyService", new WfHistoryService(repo));
        ReflectionTestUtils.setField(controller, "runtimeService", runtime);
        ReflectionTestUtils.setField(controller, "taskService",
                new WfTaskService(repository, repo, runtime, new WfHookDispatcher()));
        ReflectionTestUtils.setField(controller, "jobService", new WfJobService(repo, runtime));
        ReflectionTestUtils.setField(controller, "viewMapper", new WfViewMapper(runtime));
        ReflectionTestUtils.setField(controller, "historicIncidentService",
                new WfHistoricIncidentService(repo));

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WfExceptionAdvice())
                .build();
    }

    private WfRepositoryService repository;

    private String startOne() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(ORDER_BPMN));
        return runtime.startProcessInstance(definition,
                "order-" + System.nanoTime(), "alice", null, new HashMap<String, Object>());
    }

    /** 领 → fail，让一条真实的历史故障记录产生。 */
    private String failOnce(String instanceId, String reason) {
        List<WfJob> jobs = repo.queryJobs(new WfJobQuery()
                .setProcessInstanceId(instanceId).setPageNum(1).setPageSize(10));
        assertEquals(1, jobs.size(), "前置条件：应当恰好有一个外部任务 job");
        String jobId = jobs.get(0).getId();
        List<com.zifang.z.wf.core.view.WfExternalTaskView> got =
                external.fetchAndLock("order.create", "w1", 50);
        com.zifang.z.wf.core.view.WfExternalTaskView target = null;
        for (com.zifang.z.wf.core.view.WfExternalTaskView view : got) {
            if (jobId.equals(view.getId())) {
                target = view;
                break;
            }
        }
        assertTrue(target != null, "前置条件：应当能领到这个 job。实际领到 " + got);
        external.fail(target.getId(), "w1", reason);
        return jobId;
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("历史故障端点：按流程实例查得到，字段齐全")
    void listHistoricIncidents() throws Exception {
        String pid = startOne();
        failOnce(pid, "下游 503");

        mockMvc.perform(get("/api/wf/history/incidents").param("processInstanceId", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].processInstanceId").value(pid))
                .andExpect(jsonPath("$.data.records[0].jobType").value("EXTERNAL"))
                .andExpect(jsonPath("$.data.records[0].definitionKey").value("orderProcess"))
                .andExpect(jsonPath("$.data.records[0].activityName").value("通知下游"))
                .andExpect(jsonPath("$.data.records[0].failureCount").value(1))
                .andExpect(jsonPath("$.data.records[0].errorMessage").value("下游 503"))
                .andExpect(jsonPath("$.data.records[0].stillFailing").value(true))
                .andExpect(jsonPath("$.data.records[0].firstFailureTime").isNotEmpty());
    }

    @Test
    @DisplayName("job 好了之后历史仍在，而当前故障已经空了 —— 两个端点各答各的")
    void historyOutlivesTheJob() throws Exception {
        String pid = startOne();
        failOnce(pid, "下游 503");

        List<WfJob> jobs = repo.queryJobs(new WfJobQuery()
                .setProcessInstanceId(pid).setPageNum(1).setPageSize(10));
        repo.deleteJob(jobs.get(0).getId());
        assertTrue(repo.queryJobs(new WfJobQuery().setPageNum(1).setPageSize(10)).isEmpty()
                        || repo.queryJobs(new WfJobQuery().setPageNum(1).setPageSize(10)).size() == 0,
                "前置条件：job 已经不在了");

        mockMvc.perform(get("/api/wf/history/incidents").param("processInstanceId", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].errorMessage").value("下游 503"))
                .andExpect(jsonPath("$.data.records[0].stillFailing").value(false));
    }

    @Test
    @DisplayName("按类型与错误消息筛，列表与 total 对得上")
    void filterAndPaginate() throws Exception {
        String first = startOne();
        failOnce(first, "下游 503");
        String second = startOne();
        failOnce(second, "参数不合法");

        mockMvc.perform(get("/api/wf/history/incidents").param("type", "EXTERNAL"))
                .andExpect(jsonPath("$.data.total").value(2));
        mockMvc.perform(get("/api/wf/history/incidents").param("errorMessageContains", "503"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].processInstanceId").value(first));
        mockMvc.perform(get("/api/wf/history/incidents").param("minFailureCount", "5"))
                .andExpect(jsonPath("$.data.total").value(0));
        // total 跟着 pageSize 变的话，前端分页器会以为只有一页
        mockMvc.perform(get("/api/wf/history/incidents")
                        .param("pageNum", "1").param("pageSize", "1"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(1));
    }

    @Test
    @DisplayName("按首次失败时刻切时间窗 —— 「上周三那批」就是这么查的")
    void timeWindow() throws Exception {
        String pid = startOne();
        failOnce(pid, "下游 503");
        long when = new WfHistoricIncidentService(repo)
                .findByJobId(repo.queryJobs(new WfJobQuery()
                        .setProcessInstanceId(pid).setPageNum(1).setPageSize(10))
                        .get(0).getId())
                .getFirstFailureTime().getTime();

        mockMvc.perform(get("/api/wf/history/incidents")
                        .param("firstFailureFrom", String.valueOf(when - 60_000L)))
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/wf/history/incidents")
                        .param("firstFailureFrom", String.valueOf(when + 60_000L)))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    // ==================== 清理 ====================

    @Test
    @DisplayName("清理端点：按时间点走，缺参数返回 400")
    void cleanupEndpoint() throws Exception {
        String pid = startOne();
        failOnce(pid, "下游 503");
        assertEquals(1, countIncidents());

        // 缺 before ⇒ 调不动任何清理能力，必须 400 而不是"清掉全部"
        mockMvc.perform(post("/api/wf/history/incidents/delete-before"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/wf/history/incidents/delete-before")
                        .param("before", String.valueOf(System.currentTimeMillis() - 86_400_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(0));
        assertEquals(1, countIncidents(), "清理早于故障的记录不该动到它");

        mockMvc.perform(post("/api/wf/history/incidents/delete-before")
                        .param("before", String.valueOf(System.currentTimeMillis() + 86_400_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));
        assertEquals(0, countIncidents());
    }

    private int countIncidents() throws Exception {
        String json = mockMvc.perform(get("/api/wf/history/incidents"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String marker = "\"total\":";
        int start = json.indexOf(marker) + marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Integer.parseInt(json.substring(start, end));
    }

    @Test
    @DisplayName("响应里不出现持久化内部字段（revision / executionId 之外的引擎内部量）")
    void responseHasNoEngineInternals() throws Exception {
        String pid = startOne();
        failOnce(pid, "下游 503");
        String json = mockMvc.perform(get("/api/wf/history/incidents"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(json.contains("revision"),
                "乐观锁版本不该出现在对外响应里 —— 它是实现细节，露出去等于承诺了并发语义。实际: " + json);
    }
}