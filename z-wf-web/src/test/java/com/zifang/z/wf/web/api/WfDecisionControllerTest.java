package com.zifang.z.wf.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.service.WfDecisionService;

/**
 * DMN 决策表 REST 层。
 *
 * <p>用 {@code standaloneSetup} 而不是 {@code @SpringBootTest}：这里要断的是
 * 「路径 / 参数 / 状态码 / 响应字段」这几件事，起整个上下文只会把
 * 「配置能不能自动装配」也一起拖进来 —— 那是另一条轴，混在一起时
 * 上下文起不来的症状（{@code NoSuchBeanDefinitionException}）
 * 会盖掉真正的失败原因。
 *
 * <p>异常映射走真的 {@link WfExceptionAdvice}：
 * 决策表被违反（UNIQUE 命中两条）必须落成 <b>400</b> 而不是 500 ——
 * 那是改表就能解决的调用方问题，报"系统错误"会让前端一律弹错。
 */
class WfDecisionControllerTest {

    private static final String APPROVAL_DMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\">\n"
            + "  <decision id=\"approvalLevel\" name=\"审批层级\">\n"
            + "    <decisionTable id=\"t\" hitPolicy=\"FIRST\">\n"
            + "      <input id=\"i\"><inputExpression id=\"ie\"><text>amount</text>"
            + "</inputExpression></input>\n"
            + "      <output id=\"o\" name=\"level\"/>\n"
            + "      <rule><inputEntry><text>&gt; 50000</text></inputEntry>"
            + "<outputEntry><text>\"ceo\"</text></outputEntry></rule>\n"
            + "      <rule><inputEntry><text>-</text></inputEntry>"
            + "<outputEntry><text>\"staff\"</text></outputEntry></rule>\n"
            + "    </decisionTable>\n"
            + "  </decision>\n"
            + "</definitions>\n";

    private MockMvc mockMvc;
    private WfDecisionService decisionService;

    @BeforeEach
    void setUp() {
        InMemoryWorkflowPersistence persistence = new InMemoryWorkflowPersistence();
        persistence.initialize();
        decisionService = new WfDecisionService(persistence);
        WfDecisionController controller = new WfDecisionController();
        ReflectionTestUtils.setField(controller, "decisionService", decisionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WfExceptionAdvice())
                .build();
    }

    private String deploy(String dmn) throws Exception {
        return mockMvc.perform(post("/api/wf/decisions/deploy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dmnXml\":" + quote(dmn) + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("部署 → 列出 → 求值 → 删除，一条链路走通")
    void fullChain() throws Exception {
        String deployed = deploy(APPROVAL_DMN);
        assertTrue(deployed.contains("\"key\":\"approvalLevel\""), deployed);
        assertTrue(deployed.contains("\"version\":1"), deployed);

        mockMvc.perform(get("/api/wf/decisions/approvalLevel"))
                .andExpect(status().isOk())
                // hitPolicy 给枚举名；符号另给一份，不必让调用方自己记映射
                .andExpect(jsonPath("$.data.table.hitPolicy").value("FIRST"))
                .andExpect(jsonPath("$.data.table.hitPolicySymbol").value("F"))
                .andExpect(jsonPath("$.data.table.rules.length()").value(2));

        mockMvc.perform(post("/api/wf/decisions/approvalLevel/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variables\":{\"amount\":60000}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].level").value("ceo"))
                // 命中的**规则数**是 2（&gt; 50000 与恒真的 - 都成立），
                // FIRST 取的是第一条 —— 计数报命中数、不是报结果条数，
                // 这两个数在这里不一样，混起来就看不出「为什么结果只有一条」。
                .andExpect(jsonPath("$.data.matchedRuleCount").value(2))
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.noMatch").value(false));

        // 再部署一次 → 版本 2，且新版本在前
        deploy(APPROVAL_DMN);
        mockMvc.perform(get("/api/wf/decisions/approvalLevel/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].version").value(2))
                .andExpect(jsonPath("$.data.records[1].version").value(1));

        mockMvc.perform(delete("/api/wf/decisions/approvalLevel/versions/1"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/wf/decisions/approvalLevel/versions/1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/wf/decisions/approvalLevel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    @DisplayName("一条都没命中是合法结果：200 + noMatch，而不是报错")
    void noMatchIsAResultNotAnError() throws Exception {
        // 表只有 > 50000 与 -（恒真）两条 ⇒ 传 1 也会命中 staff，
        // 所以这里改用一张两列都不成立的表：单目测试引用不存在的变量。
        String dmn = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\">\n"
                + "  <decision id=\"never\" name=\"永不命中\">\n"
                + "    <decisionTable id=\"t\" hitPolicy=\"UNIQUE\">\n"
                + "      <input id=\"i\"><inputExpression id=\"ie\"><text>missing</text>"
                + "</inputExpression></input>\n"
                + "      <output id=\"o\" name=\"level\"/>\n"
                + "      <rule><inputEntry><text>&gt; 1</text></inputEntry>"
                + "<outputEntry><text>\"ceo\"</text></outputEntry></rule>\n"
                + "    </decisionTable>\n"
                + "  </decision>\n"
                + "</definitions>\n";
        deploy(dmn);
        mockMvc.perform(post("/api/wf/decisions/never/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.noMatch").value(true))
                .andExpect(jsonPath("$.data.matchedRuleCount").value(0))
                .andExpect(jsonPath("$.data.rows.length()").value(0));
    }

    @Test
    @DisplayName("表被违反（UNIQUE 命中两条）⇒ 400，不是 500")
    void violationIsBadRequest() throws Exception {
        String dmn = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\">\n"
                + "  <decision id=\"overlap\" name=\"重叠\">\n"
                + "    <decisionTable id=\"t\" hitPolicy=\"UNIQUE\">\n"
                + "      <input id=\"i\"><inputExpression id=\"ie\"><text>x</text>"
                + "</inputExpression></input>\n"
                + "      <output id=\"o\" name=\"level\"/>\n"
                + "      <rule><inputEntry><text>&gt; 1</text></inputEntry>"
                + "<outputEntry><text>\"ceo\"</text></outputEntry></rule>\n"
                + "      <rule><inputEntry><text>&gt; 2</text></inputEntry>"
                + "<outputEntry><text>\"staff\"</text></outputEntry></rule>\n"
                + "    </decisionTable>\n"
                + "  </decision>\n"
                + "</definitions>\n";
        deploy(dmn);
        // 报错正文必须透出来：调用方要知道是"哪张表的两条规则重叠"，
        // 只给一个 400 而没有 message，排查的人得先反编译才知道出了什么。
        mockMvc.perform(post("/api/wf/decisions/overlap/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variables\":{\"x\":100}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("UNIQUE")));
    }

    @Test
    @DisplayName("决策不存在 / 版本不存在 ⇒ 400 并说清怎么部署，不静默返回空")
    void missingDecisionFailsLoudly() throws Exception {
        mockMvc.perform(get("/api/wf/decisions/nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("/api/wf/decisions/deploy")));
        mockMvc.perform(get("/api/wf/decisions/nope/versions"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/wf/decisions/nope/versions/3"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/wf/decisions/nope/versions/3"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/wf/decisions/nope/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("部署内容为空 ⇒ 400，且说的是人话而不是解析器的报错")
    void emptyDeployIsRejected() throws Exception {
        mockMvc.perform(post("/api/wf/decisions/deploy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dmnXml\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("部署内容不能为空")));
    }

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r") + "\"";
    }

    @Test
    @DisplayName("一个文件里的多个 decision 全部部署（只回第一个是回归）")
    void deploysEveryDecisionInTheFile() throws Exception {
        String two = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d\">\n"
                + decisionXml("first") + decisionXml("second")
                + "</definitions>\n";
        String body = deploy(two);
        assertTrue(body.contains("\"first\""), body);
        assertTrue(body.contains("\"second\""), body);
        assertEquals(2, decisionService.findDecisionsByKey("first").size()
                + decisionService.findDecisionsByKey("second").size());
    }

    private static String decisionXml(String key) {
        return "  <decision id=\"" + key + "\" name=\"" + key + "\">\n"
                + "    <decisionTable id=\"t\" hitPolicy=\"FIRST\">\n"
                + "      <input id=\"i\"><inputExpression id=\"ie\"><text>x</text>"
                + "</inputExpression></input>\n"
                + "      <output id=\"o\" name=\"level\"/>\n"
                + "      <rule><inputEntry><text>-</text></inputEntry>"
                + "<outputEntry><text>\"v\"</text></outputEntry></rule>\n"
                + "    </decisionTable>\n"
                + "  </decision>\n";
    }
}