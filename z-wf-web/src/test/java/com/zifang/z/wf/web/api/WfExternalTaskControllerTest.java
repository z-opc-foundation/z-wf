package com.zifang.z.wf.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.service.WfExternalTaskService;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;

/**
 * 外部任务端点的 REST 层（第 41 轮新增 {@code bpmn-error}）。
 *
 * <p>用 {@code standaloneSetup}：这里要断的是路径 / 参数名 / 状态码 / 响应字段，
 * 自动装配与数据库那一层由 {@code z-wf-admin} 的端到端用例单独钉。
 *
 * <p>判据落在<b>对外可见的结果</b>上：响应里那单到哪了、那只活还在不在、
 * 下一步有没有派出来 —— 而不是「服务方法被调到了没有」。
 *
 * <p>两份流程定义<b>各自写成字面量</b>，不用 {@code replace} 从一份拼出另一份：
 * 拼出来的夹具会<b>静默失配</b>（模板改了、被替换的那段没匹配上时拿到的是原串，
 * 测试照样绿），而"没有边界"这件事恰恰是这条用例的全部意义 ——
 * 它悄悄变成"有边界"，症状只是断言的方向反了。
 */
class WfExternalTaskControllerTest {

    /** 外部任务 + 错误边界，出线上接一只普通的退款外部任务。 */
    private static final String ERROR_BOUNDARY_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"payProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <serviceTask id=\"charge\" name=\"扣款\""
            + " zifang:type=\"external\" zifang:topic=\"charge\"/>\n"
            + "    <boundaryEvent id=\"onFail\" attachedToRef=\"charge\">\n"
            + "      <errorEventDefinition errorRef=\"PAYMENT_FAILED\"/>\n"
            + "    </boundaryEvent>\n"
            + "    <serviceTask id=\"refund\" name=\"退款\""
            + " zifang:type=\"external\" zifang:topic=\"refund\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"charge\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"charge\" targetRef=\"e\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"onFail\" targetRef=\"refund\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"refund\" targetRef=\"e2\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <endEvent id=\"e2\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 同样的流程，**不挂错误边界**：同一个错误码无处可捕，只能让流程终止。 */
    private static final String NO_BOUNDARY_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"payNoBoundary\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <serviceTask id=\"charge\" name=\"扣款\""
            + " zifang:type=\"external\" zifang:topic=\"charge\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"charge\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"charge\" targetRef=\"e\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private MockMvc mockMvc;
    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfExternalTaskService external;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
        external = new WfExternalTaskService(repo, runtime);

        WfExternalTaskController controller = new WfExternalTaskController();
        ReflectionTestUtils.setField(controller, "externalTaskService", external);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WfExceptionAdvice())
                .build();
    }

    private String startOne(String xml) {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(xml));
        return runtime.startProcessInstance(definition,
                "pay-" + System.nanoTime(), "alice", null, new HashMap<String, Object>());
    }

    /**
     * 领活并返回<b>这一单</b>那只 job 的 id。
     *
     * <p>刻意不直接取查询结果的第一个：同一个 repo 里若还有别的单子，
     * "第一个"未必是本单，而拿错了对象会让后面每条断言都变成在测另一条流程。
     */
    private String claimOf(String instanceId, String topic, String worker) {
        List<WfJob> before = repo.queryJobs(new WfJobQuery()
                .setProcessInstanceId(instanceId).setPageNum(1).setPageSize(20));
        assertEquals(1, before.size(),
                "前置条件：应当恰好一只外部任务 job。实际: " + before.size() + " 个");
        String jobId = before.get(0).getId();
        List<com.zifang.z.wf.core.view.WfExternalTaskView> got =
                external.fetchAndLock(topic, worker, 50);
        for (com.zifang.z.wf.core.view.WfExternalTaskView view : got) {
            if (jobId.equals(view.getId())) {
                return jobId;
            }
        }
        throw new AssertionError("没领到 " + jobId + "，实际领到 " + got);
    }

    /** 该实例当前所有 job 的 topic；内部 job 显示成 {@code "null"}，不被静默滤掉。 */
    private List<String> topicsOf(String instanceId) {
        List<WfJob> jobs = repo.queryJobs(new WfJobQuery()
                .setProcessInstanceId(instanceId).setPageNum(1).setPageSize(50));
        List<String> topics = new ArrayList<String>();
        for (WfJob job : jobs) {
            topics.add(String.valueOf(job.getTopic()));
        }
        return topics;
    }

    // ==================== 报错交回 ====================

    @Test
    @DisplayName("bpmn-error：token 沿错误边界走到补救分支，原 job 被消费掉")
    void bpmnErrorRoutesToRemediationBranch() throws Exception {
        String pid = startOne(ERROR_BOUNDARY_BPMN);
        String jobId = claimOf(pid, "charge", "w1");

        mockMvc.perform(post("/api/wf/external-tasks/{taskId}/bpmn-error", jobId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());   // 顺带钉住：缺 workerId 不会静默成功

        mockMvc.perform(post("/api/wf/external-tasks/{taskId}/bpmn-error", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(errorBody("w1", "PAYMENT_FAILED", "扣款接口返回 502")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processInstanceId").value(pid))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.terminal").value(false));

        assertNull(repo.findJob(jobId), "job 必须被消费掉，否则会被重新领一遍");
        assertTrue(topicsOf(pid).contains("refund"),
                "补救分支上的活应当已经派出来。实际: " + topicsOf(pid));
    }

    @Test
    @DisplayName("bpmn-error：没有边界可接时 terminal=true，绝不静默继续")
    void bpmnErrorWithoutBoundaryTerminates() throws Exception {
        String pid = startOne(NO_BOUNDARY_BPMN);
        String jobId = claimOf(pid, "charge", "w1");

        mockMvc.perform(post("/api/wf/external-tasks/{taskId}/bpmn-error", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(errorBody("w1", "PAYMENT_FAILED", "扣款接口返回 502")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.terminal").value(true))
                .andExpect(jsonPath("$.data.status").value("INTERNALLY_TERMINATED"));

        assertNull(repo.findJob(jobId));
        assertTrue(topicsOf(pid).isEmpty(),
                "终止之后不该还留着活。实际: " + topicsOf(pid));
    }

    @Test
    @DisplayName("bpmn-error：缺 errorCode 返回 400 且点名是哪个参数，什么都不改")
    void missingErrorCodeIsRejected() throws Exception {
        String pid = startOne(ERROR_BOUNDARY_BPMN);
        String jobId = claimOf(pid, "charge", "w1");

        mockMvc.perform(post("/api/wf/external-tasks/{taskId}/bpmn-error", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(errorBody("w1", null, "忘了填码")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("errorCode")));

        assertEquals(1, topicsOf(pid).size(),
                "被拒的调用不能留下任何痕迹。实际: " + topicsOf(pid));
    }

    @Test
    @DisplayName("bpmn-error：不是锁持有者 ⇒ 400，什么都不改")
    void wrongWorkerIsRejected() throws Exception {
        String pid = startOne(ERROR_BOUNDARY_BPMN);
        String jobId = claimOf(pid, "charge", "w1");

        mockMvc.perform(post("/api/wf/external-tasks/{taskId}/bpmn-error", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(errorBody("w2", "PAYMENT_FAILED", "我抢的")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("锁")));

        assertTrue(topicsOf(pid).contains("charge"),
                "原来的活必须还在原地等真正的持有者。实际: " + topicsOf(pid));
    }

    @Test
    @DisplayName("bpmn-error：响应是实例摘要，不把 variables 整包塞回去")
    void responseIsInstanceSummaryOnly() throws Exception {
        String pid = startOne(ERROR_BOUNDARY_BPMN);
        String jobId = claimOf(pid, "charge", "w1");

        String content = mockMvc.perform(
                        post("/api/wf/external-tasks/{taskId}/bpmn-error", jobId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(errorBody("w1", "PAYMENT_FAILED", "扣款接口返回 502")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(content.contains("\"variables\""),
                "交差响应不该回吐整包流程变量 —— 它随业务时长膨胀，"
                        + "而 worker 交差后真正需要的只是「我这一步过了没有」。实际: " + content);
        assertTrue(content.contains("\"processInstanceId\""),
                "worker 要靠它确认交差的是哪一单。实际: " + content);
    }

    private String errorBody(String workerId, String code, String message) throws Exception {
        HashMap<String, Object> req = new HashMap<String, Object>();
        req.put("workerId", workerId);
        req.put("errorCode", code);
        req.put("errorMessage", message);
        return json.writeValueAsString(req);
    }
}