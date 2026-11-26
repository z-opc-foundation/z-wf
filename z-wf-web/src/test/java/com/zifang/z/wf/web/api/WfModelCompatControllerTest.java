package com.zifang.z.wf.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.service.WfRepositoryService;

/**
 * 流程设计兼容端点（z-camuda ProcessModelController 契约）。
 *
 * <p>设计稿按流程 key 覆盖式保存，发布包装成 {@code {"logicflow":...}} 走
 * {@code deployJson} 产生新版本。这里断读/存/发三条路 + 「条件/抄送」节点
 * 类型别名能落地部署（前端设计器画布会拖这两种节点）。
 */
class WfModelCompatControllerTest {

    private static final String NS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n";
    private static final String NS_END = "</definitions>\n";

    private static final String SIMPLE_BPMN = NS
            + "  <process id=\"xmlFlow\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\">\n"
            + "      <outgoing>f1</outgoing>\n"
            + "    </startEvent>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"admin\">\n"
            + "      <incoming>f1</incoming><outgoing>f2</outgoing>\n"
            + "    </userTask>\n"
            + "    <endEvent id=\"e\">\n"
            + "      <incoming>f2</incoming>\n"
            + "    </endEvent>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + NS_END;

    private static final String GRAPH = "{\"nodes\":["
            + "{\"id\":\"start-1\",\"type\":\"start\",\"x\":200,\"y\":240,\"text\":{\"value\":\"开始\"}},"
            + "{\"id\":\"n1\",\"type\":\"approval\",\"x\":360,\"y\":240,\"text\":{\"value\":\"审批\"}},"
            + "{\"id\":\"end-1\",\"type\":\"end\",\"x\":560,\"y\":240,\"text\":{\"value\":\"结束\"}}],"
            + "\"edges\":["
            + "{\"id\":\"e1\",\"sourceNodeId\":\"start-1\",\"targetNodeId\":\"n1\",\"type\":\"polyline\"},"
            + "{\"id\":\"e2\",\"sourceNodeId\":\"n1\",\"targetNodeId\":\"end-1\",\"type\":\"polyline\"}]}";

    /** 设计器画布会拖到的 condition/copy 节点 —— 解析别名必须落地。 */
    private static final String GRAPH_WITH_PALETTE_TYPES = "{\"nodes\":["
            + "{\"id\":\"start-1\",\"type\":\"start\",\"x\":200,\"y\":240,\"text\":{\"value\":\"开始\"}},"
            + "{\"id\":\"n1\",\"type\":\"approval\",\"x\":340,\"y\":200,\"text\":{\"value\":\"审批\"}},"
            + "{\"id\":\"g1\",\"type\":\"condition\",\"x\":340,\"y\":320,\"text\":{\"value\":\"条件\"}},"
            + "{\"id\":\"n2\",\"type\":\"copy\",\"x\":500,\"y\":200,\"text\":{\"value\":\"抄送\"}},"
            + "{\"id\":\"end-1\",\"type\":\"end\",\"x\":500,\"y\":320,\"text\":{\"value\":\"结束\"}}],"
            + "\"edges\":["
            + "{\"id\":\"e1\",\"sourceNodeId\":\"start-1\",\"targetNodeId\":\"n1\",\"type\":\"polyline\"},"
            + "{\"id\":\"e2\",\"sourceNodeId\":\"n1\",\"targetNodeId\":\"g1\",\"type\":\"polyline\"},"
            + "{\"id\":\"e3\",\"sourceNodeId\":\"g1\",\"targetNodeId\":\"n2\",\"type\":\"polyline\"},"
            + "{\"id\":\"e4\",\"sourceNodeId\":\"n2\",\"targetNodeId\":\"end-1\",\"type\":\"polyline\"}]}";

    private MockMvc mockMvc;
    private WfRepositoryService repository;

    @BeforeEach
    void setUp() {
        InMemoryWorkflowPersistence repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        WfModelCompatController controller = new WfModelCompatController();
        ReflectionTestUtils.setField(controller, "repositoryService", repository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WfExceptionAdvice())
                .build();
    }

    @Test
    @DisplayName("无设计稿无定义 ⇒ source=empty + 骨架图")
    void emptyReturnsStarterGraph() throws Exception {
        mockMvc.perform(get("/api/approval-center/processes/model/graph")
                        .param("processKey", "ghost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("empty"))
                .andExpect(jsonPath("$.data.graph").value(
                        org.hamcrest.Matchers.containsString("\"start-1\"")));
    }

    @Test
    @DisplayName("存设计稿 → 取回即 designed，keys 列出 designed")
    void saveThenGetReturnsDesigned() throws Exception {
        mockMvc.perform(post("/api/approval-center/processes/model/graph")
                        .contentType("application/json")
                        .content("{\"processKey\":\"abc\",\"graph\":" + jsonString(GRAPH) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/approval-center/processes/model/graph")
                        .param("processKey", "abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("designed"))
                .andExpect(jsonPath("$.data.graph").value(GRAPH));

        mockMvc.perform(get("/api/approval-center/processes/model/keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.designed[0]").value("abc"));
    }

    @Test
    @DisplayName("发布设计稿 ⇒ 新定义版本 1，再发一次到版本 2")
    void deployIncrementsVersion() throws Exception {
        mockMvc.perform(post("/api/approval-center/processes/model/graph")
                        .contentType("application/json")
                        .content("{\"processKey\":\"draft\",\"graph\":" + jsonString(GRAPH) + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/approval-center/processes/model/deploy")
                        .contentType("application/json")
                        .content("{\"processKey\":\"draft\",\"name\":\"新流程\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.processDefinitionId").value("draft:1"))
                .andExpect(jsonPath("$.data.processKey").value("draft"));

        mockMvc.perform(post("/api/approval-center/processes/model/deploy")
                        .contentType("application/json")
                        .content("{\"processKey\":\"draft\",\"name\":\"新流程\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));

        assertEquals("新流程", repository.getLatestDefinition("draft").getName(),
                "发布时的 name 应落到定义名");
    }

    @Test
    @DisplayName("没存设计稿就发布 ⇒ success=false + 提示先保存")
    void deployWithoutDesignFails() throws Exception {
        mockMvc.perform(post("/api/approval-center/processes/model/deploy")
                        .contentType("application/json")
                        .content("{\"processKey\":\"nodraft\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("还没有设计稿，请先保存流程图")));
    }

    @Test
    @DisplayName("画布拖了 condition/copy 节点 ⇒ 别名解析并可部署")
    void deployWithPaletteNodeTypes() throws Exception {
        mockMvc.perform(post("/api/approval-center/processes/model/graph")
                        .contentType("application/json")
                        .content("{\"processKey\":\"palette\",\"graph\":"
                                + jsonString(GRAPH_WITH_PALETTE_TYPES) + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/approval-center/processes/model/deploy")
                        .contentType("application/json")
                        .content("{\"processKey\":\"palette\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    @DisplayName("已部署的 XML 定义无设计稿 ⇒ source=definition 且图能转回 LogicFlow")
    void getGraphFromDeployedXmlDefinition() throws Exception {
        repository.deployXml(SIMPLE_BPMN, "xmlFlow");

        mockMvc.perform(get("/api/approval-center/processes/model/graph")
                        .param("processKey", "xmlFlow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("definition"))
                .andExpect(jsonPath("$.data.graph").value(
                        org.hamcrest.Matchers.containsString("\"type\":\"start\"")))
                .andExpect(jsonPath("$.data.graph").value(
                        org.hamcrest.Matchers.containsString("\"type\":\"approval\"")));
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}