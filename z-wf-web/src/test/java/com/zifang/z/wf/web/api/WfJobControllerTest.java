package com.zifang.z.wf.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.service.WfJobService;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.web.mapper.WfViewMapper;

/**
 * Job 运维端点的 REST 层。
 *
 * <p>用 {@code standaloneSetup} 而不是 {@code @SpringBootTest}：这里要断的是
 * 「路径 / 参数 / 状态码 / 响应字段」，起整个上下文会把「配置能不能自动装配」
 * 一起拖进来 —— 那是另一条轴，混在一起时上下文起不来的症状会盖掉真正的失败原因。
 * 装配本身由 {@code z-wf-admin} 的端到端用例单独钉。
 *
 * <p>异常映射走真的 {@link WfExceptionAdvice}：手动触发被拒必须是 <b>400</b>，
 * 因为那是"换个用法就能解决"的调用方问题，报 500 会让前端一律弹系统错误。
 */
class WfJobControllerTest {

    private static final String NS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n";

    private static final String NS_END = "</definitions>\n";

    private static final String TIMER_BPMN = NS
            + "  <process id=\"apiTimer\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <boundaryEvent id=\"overdue\" attachedToRef=\"approve\">\n"
            + "      <timerEventDefinition><timeDuration>P3D</timeDuration></timerEventDefinition>\n"
            + "    </boundaryEvent>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"overdue\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + NS_END;

    private static final String MESSAGE_BPMN = NS
            + "  <process id=\"apiMsg\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <boundaryEvent id=\"cancelBoundary\" attachedToRef=\"approve\">\n"
            + "      <messageEventDefinition messageRef=\"cancel\"/>\n"
            + "    </boundaryEvent>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"cancelBoundary\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + NS_END;

    private static final String EVENT_GATEWAY_BPMN = NS
            + "  <process id=\"apiEg\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <eventBasedGateway id=\"eg\"/>\n"
            + "    <intermediateCatchEvent id=\"waitA\">\n"
            + "      <signalEventDefinition signalRef=\"sigA\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <intermediateCatchEvent id=\"waitB\">\n"
            + "      <signalEventDefinition signalRef=\"sigB\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"eg\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"eg\" targetRef=\"waitA\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"eg\" targetRef=\"waitB\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"waitA\" targetRef=\"e\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"waitB\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + NS_END;

    /** 异步前置，且宿主节点配了非默认优先级 —— 用来断 priority 真的被映射出来。 */
    private static final String ASYNC_PRIO_BPMN = NS
            + "  <process id=\"apiAsyncPrio\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <userTask id=\"approve\" name=\"加急审批\" zifang:assignee=\"boss\""
            + " zifang:asyncBefore=\"true\" zifang:priority=\"90\"/>\n"
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
        WfJobController controller = new WfJobController();
        ReflectionTestUtils.setField(controller, "jobService",
                new WfJobService(repo, runtime));
        ReflectionTestUtils.setField(controller, "viewMapper", new WfViewMapper(runtime));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WfExceptionAdvice())
                .build();
    }

    // ==================== trigger ====================

    @Test
    @DisplayName("提前触发成功 ⇒ 200 + triggered=true + 回显 jobId")
    void triggerReturnsTriggeredTrue() throws Exception {
        String pid = start(TIMER_BPMN, "apiTimer");
        String jobId = firstJob(pid).getId();

        mockMvc.perform(post("/api/wf/jobs/" + jobId + "/trigger").param("userId", "ops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.triggered").value(true))
                .andExpect(jsonPath("$.data.jobId").value(jobId))
                .andExpect(jsonPath("$.data.note").value("已触发"));

        assertTrue(repo.findJob(jobId) == null, "触发后 job 应当被消费掉，不能再触发第二次");
    }

    @Test
    @DisplayName("「该响没响」⇒ 仍是 200 且 triggered=false，不许变成异常")
    void notFiredIsStillTwoHundred() throws Exception {
        String pid = start(TIMER_BPMN, "apiTimer");
        WfJob job = firstJob(pid);
        // 流程先办结：token 离开了宿主，边界那条路走不了
        for (WfTask task : repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(10))) {
            runtime.completeTask(task.getId(), task.getAssignee(), "办完了",
                    new HashMap<String, Object>());
        }
        // 残留 job 是真实场景（清理没做干净），把它放回去
        repo.saveJob(job);
        assertNotNull(repo.findJob(job.getId()), "前置条件：残留 job 还在");

        mockMvc.perform(post("/api/wf/jobs/" + job.getId() + "/trigger").param("userId", "ops"))
                // **不能是 4xx/5xx**：它不是失败，是"这次没轮到它响"
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.triggered").value(false))
                .andExpect(jsonPath("$.data.note").value(
                        org.hamcrest.Matchers.containsString("该响没响")));
    }

    @Test
    @DisplayName("job 不存在 ⇒ 400，且说清可能被谁消费掉了")
    void missingJobIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/wf/jobs/j-nope/trigger").param("userId", "ops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("不存在")));
    }

    @Test
    @DisplayName("订阅型 job 手动触发 ⇒ 400，且说清「同一步走两遍」")
    void subscriptionJobIsBadRequest() throws Exception {
        String pid = start(MESSAGE_BPMN, "apiMsg");
        String jobId = firstJob(pid).getId();

        mockMvc.perform(post("/api/wf/jobs/" + jobId + "/trigger").param("userId", "ops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("走两遍")))
                // 要指出替代路径，否则调用方只以为这条路被整体禁了
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("triggerMessage")));
        assertNotNull(repo.findJob(jobId), "被拒的 job 必须原封不动留在库里");
    }

    @Test
    @DisplayName("事件网关的分支手动触发 ⇒ 400，且点破会作废兄弟分支")
    void eventGatewayBranchIsBadRequest() throws Exception {
        String pid = start(EVENT_GATEWAY_BPMN, "apiEg");
        String jobId = firstJob(pid).getId();

        mockMvc.perform(post("/api/wf/jobs/" + jobId + "/trigger").param("userId", "ops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("兄弟分支")));
        assertNotNull(repo.findJob(jobId), "被拒的竞速分支不能被消费掉");
    }

    @Test
    @DisplayName("不传 userId ⇒ 留痕记成 system，不留成空")
    void userIdDefaultsToSystem() throws Exception {
        String pid = start(TIMER_BPMN, "apiTimer");
        String jobId = firstJob(pid).getId();

        mockMvc.perform(post("/api/wf/jobs/" + jobId + "/trigger"))
                .andExpect(status().isOk());

        WfComment mark = null;
        for (WfComment c : repo.findComments(pid)) {
            if (c.getContent() != null && c.getContent().contains("手动提前触发")) {
                mark = c;
            }
        }
        assertNotNull(mark, "前置条件：留痕存在");
        assertEquals("system", mark.getUserId(),
                "userId 缺省要落成 system —— 留成空的话，"
                        + "「这条是谁点的」在轨迹上就成了没答案的问题");
    }

    // ==================== 列表 / 计数 / 失败清单 ====================

    @Test
    @DisplayName("列表给出排障要看的字段，且与既有 job 视图用同一个 id 字段名")
    void listExposesDiagnosticsWithCanonicalFields() throws Exception {
        String pid = start(TIMER_BPMN, "apiTimer");
        String jobId = firstJob(pid).getId();

        String body = mockMvc.perform(get("/api/wf/jobs")
                        .param("processInstanceId", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                // **字段名必须是 jobId**：/api/wf/history/jobs 早就是这个名。
                // 另起一个视图用 id 的话，调用方从这边取 id、拿到那边去查会得到 null，
                // 而症状是「列表里明明有、点进去却是空的」—— 极难联想到是字段名分叉
                .andExpect(jsonPath("$.data[0].jobId").value(jobId))
                // 排障要对着 XML 看：这一格挂在谁身上
                .andExpect(jsonPath("$.data[0].elementId").value("overdue"))
                .andExpect(jsonPath("$.data[0].attachedToRef").value("approve"))
                // type 给**枚举名**而不是中文标签：运维要拿它下查询参数（type=TIMER），
                // 给标签的话那个过滤参数就成了摆设
                .andExpect(jsonPath("$.data[0].type").value("TIMER"))
                // 时间是 epoch 毫秒的 long，与既有视图一致 ——
                // 直接序列化 Date 会让时区与毫秒格式变成对外契约的一部分
                .andExpect(jsonPath("$.data[0].duedate").isNumber())
                .andExpect(jsonPath("$.data[0].priority").value(50))
                .andReturn().getResponse().getContentAsString();

        // revision 是乐观锁的实现细节：抛出去等于把持久化结构变成对外 API，
        // 而它一旦被外部读走，改乐观锁的实现就要先跟外面打招呼
        assertFalse(body.contains("revision"),
                "不该把 revision 抛给调用方。实际返回: " + body);
    }

    @Test
    @DisplayName("priority 要断**具体值**：断 exists() 对 primitive 字段恒成立")
    void priorityIsMappedWithItsRealValue() throws Exception {
        String pid = start(ASYNC_PRIO_BPMN, "apiAsyncPrio");
        WfJob job = firstJob(pid);
        assertEquals(90, job.getPriority(),
                "前置条件：宿主节点的 zifang:priority 真的拷进了 job。"
                        + "断 exists() 是不够的 —— priority 是 int，"
                        + "mapper 不赋值时 Jackson 照样序列化出 0，断言照样绿");

        mockMvc.perform(get("/api/wf/jobs").param("processInstanceId", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].jobId").value(job.getId()))
                .andExpect(jsonPath("$.data[0].priority").value(90));
    }

    @Test
    @DisplayName("按 type 过滤与 count 一致；重试耗尽的进失败清单")
    void countAndExhausted() throws Exception {
        String pid = start(TIMER_BPMN, "apiTimer");
        WfJob job = firstJob(pid);
        job.setRetries(WfJob.RETRIES_EXHAUSTED);
        job.nextRevision();
        repo.saveJob(job);

        mockMvc.perform(get("/api/wf/jobs/count").param("processInstanceId", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));
        // 传了别的类型就该是 0 —— 顺带钉住「过滤真的生效」
        mockMvc.perform(get("/api/wf/jobs/count")
                        .param("processInstanceId", pid).param("type", "MESSAGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(0));

        mockMvc.perform(get("/api/wf/jobs/exhausted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].jobId").value(job.getId()))
                .andExpect(jsonPath("$.data[0].retriesExhausted").value(true));

        // 而且耗尽的那条仍然可以手动触发（运维手工重跑失败的任务）
        mockMvc.perform(post("/api/wf/jobs/" + job.getId() + "/trigger").param("userId", "ops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.triggered").value(true));
    }

    @Test
    @DisplayName("默认分页不返回全表（pageSize 缺省要有上限）")
    void listIsPaged() throws Exception {
        start(TIMER_BPMN, "apiTimer");
        mockMvc.perform(get("/api/wf/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/wf/jobs").param("pageSize", "0"))
                .andExpect(status().isOk());
    }

    // ==================== 夹具 ====================

    private String start(String xml, String key) {
        WfDefinition definition = repository.deployXml(xml, key);
        return runtime.startProcessInstance(definition, key + "-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
    }

    private WfJob firstJob(String pid) {
        List<WfJob> jobs = repo.queryJobs(new WfJobQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(20));
        assertFalse(jobs.isEmpty(), "该实例上应当至少有一条 job");
        return jobs.get(0);
    }
}
