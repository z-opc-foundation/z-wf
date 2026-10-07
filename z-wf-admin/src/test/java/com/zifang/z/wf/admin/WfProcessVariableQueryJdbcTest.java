package com.zifang.z.wf.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfProcessInstanceQuery;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;

/**
 * 按变量值查流程实例（第 43 轮）的<b>真 JDBC 端到端</b>验证。
 *
 * <h3>为什么这一层非有不可</h3>
 * core 那 13 条判据跑在 {@link InMemoryWorkflowPersistence} 上，
 * 而变量在它那儿就是 {@code Map<String, Object>} —— <b>它天然没有"序列化"这一步</b>。
 * 生产与本测试走的 {@code JdbcWorkflowPersistence} 把变量存成
 * {@code ZWF_PROCESS.VARIABLES} 这个 <b>JSON 文本列</b>，
 * 查的时候要先从文本反序列化回对象，<b>类型在这一步可能被改写</b>
 * （{@code 100} 读回来可能是 {@code Integer} / {@code Long} / {@code BigDecimal}）。
 *
 * <p>于是有两件事只有在真库上才验得了：
 * <ul>
 *   <li>JSON 往返之后，{@code >5000} 与 {@code ="100"} 的语义**还成不成立</b>。</li>
 *   <li><b>内存实现与真库实现对同一批数据、同一组条件，必须给出同一个答案</b> ——
 *       这正是 {@code hasVariableCondition()} 那段注释里担心的
 *       「两边给出两个答案而症状只在真库出现」。</li>
 * </ul>
 *
 * <h3>为什么每个测试各部署一个流程定义</h3>
 * {@code search} 的 {@code definitionKey} 过滤<b>不带版本</b>，
 * 而测试上下文是整个类共享的（H2 内存库 {@code DB_CLOSE_DELAY=-1}）。
 * 复用同一个 key 的话，前一个测试造的实例会出现在后一个测试的结果里 ——
 * 症状是「断言偶尔对偶尔错」，而根因在夹具不在实现。
 *
 * @author zifang
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2-test")
class WfProcessVariableQueryJdbcTest {

    private static final AtomicInteger DEF_SEQ = new AtomicInteger();

    private static final String NS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private WfRepositoryService repositoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 每个测试一套独立的流程 key，见类注释。 */
    private String deployIsolated() {
        String key = "varq" + DEF_SEQ.incrementAndGet();
        repositoryService.deploy(new WfXmlParser().parse(NS
                + "  <process id=\"" + key + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>\n"
                + "  </process>\n"
                + "</definitions>\n"));
        return key;
    }

    /** 起一单并写变量；{@code values} 原样进 JSON —— 数字与字符串在这里分道扬镳。 */
    private String startWithAmount(String definitionKey, String businessKey, Object amount) {
        String pid = (String) exchange(HttpMethod.POST, "/api/approval-center/processes/start",
                body("definitionKey", definitionKey, "businessKey", businessKey,
                        "userId", "alice")).get("data");
        assertNotNull(pid, "应返回流程实例 id");
        exchange(HttpMethod.POST, "/api/wf/process/variables",
                body("processInstanceId", pid, "userId", "alice",
                        "values", body("amount", amount)));
        return pid;
    }

    private String startWithoutAmount(String definitionKey, String businessKey) {
        return (String) exchange(HttpMethod.POST, "/api/approval-center/processes/start",
                body("definitionKey", definitionKey, "businessKey", businessKey,
                        "userId", "alice")).get("data");
    }

    // ==================== 一、JSON 文本列那侧 ====================

    @Test
    @DisplayName("变量确实落在 **JSON 文本列**上（后面几条的前提）")
    void variablesAreStoredAsJsonText() {
        String key = deployIsolated();
        String pid = startWithAmount(key, "json-probe", 6000);

        String raw = jdbcTemplate.queryForObject(
                "SELECT VARIABLES FROM ZWF_PROCESS WHERE PROC_ID = ?", String.class, pid);
        assertNotNull(raw, "该行的 VARIABLES 列不应为空");
        assertTrue(raw.contains("\"amount\""),
                "**这一列必须是 JSON 文本**，否则下面几条验的都不是变量查询。"
                        + "若哪天给引擎加了列缓存，这条会先红。实际列内容: " + raw);

        // 这是「变量条件为什么不进 SQL」的全部理由：它是文本，不是可用索引的类型
        assertTrue(raw.indexOf('"') >= 0, "实际列内容: " + raw);
    }

    @Test
    @DisplayName("JSON 往返之后 `>5000` 仍然只命中该命中的")
    void greaterThanSurvivesTheJsonRoundTrip() {
        String key = deployIsolated();
        startWithAmount(key, "g-small", 100);
        startWithAmount(key, "g-big", 6000);
        startWithAmount(key, "g-dirty", "待定");

        Set<String> found = businessKeys(key, "&variableName=amount&variableValueGreaterThan=5000");
        assertEquals(1, found.size(),
                "「填成了文字」的那一条不参与比较。**这一条在 core 上验不了** —— "
                        + "内存实现没有序列化这一步，"
                        + "而真库上「读回来的对象是什么类型」由 JSON 解析决定。实际: " + found);
        assertTrue(found.contains("g-big"), "实际: " + found);
    }

    @Test
    @DisplayName("JSON 里是数字还是字符串，等值两边都命中（**形态比在真库上仍然成立**）")
    void equalsMatchesNumberAndStringAfterRoundTrip() {
        String key = deployIsolated();
        startWithAmount(key, "e-number", 5000);
        startWithAmount(key, "e-string", "5000");

        Set<String> byEquals = businessKeys(key, "&variableName=amount&variableValueEquals=5000");
        assertEquals(2, byEquals.size(),
                "JSON 往返会把 `5000` 解析成 Integer / Long / BigDecimal 里的一种，"
                        + "**而按字符串形态比只看它长什么样** —— 所以两边都该命中。"
                        + "若这条红了，说明比较改成了按类型比，那比值语义"
                        + "在这里就与 core 层不一致了。实际: " + byEquals);

        Set<String> byRange = businessKeys(key,
                "&variableName=amount&variableValueFrom=4000&variableValueTo=6000");
        assertEquals(2, byRange.size(),
                "**而区间那一支是真的当数字算的**，它必须先 parse 出数值，"
                        + "否则字符串那一半会掉出去。与上面那条配对。实际: " + byRange);
    }

    // ==================== 二、分页与计数在真库上自洽 ====================

    @Test
    @DisplayName("真库上：total、records、翻页三者自洽（**不重不漏**）")
    void pagingAndTotalAgreeOnRealJdbc() {
        String key = deployIsolated();
        for (int i = 0; i < 6; i++) {
            startWithAmount(key, "hit-" + i, 6000);
        }
        // 排序键是 START_TIME，两批拉开毫秒差 ——
        // 同一毫秒里 H2 的 ORDER BY 不给稳定次序，翻页就会重或漏，
        // 而那种失败看着像实现错了，实际是夹具没把顺序钉死
        sleepAcrossMillis();
        for (int i = 0; i < 3; i++) {
            startWithAmount(key, "miss-" + i, 1);
        }

        Map<String, Object> first = search(key, "&variableName=amount"
                + "&variableValueGreaterThan=5000&pageNum=1&pageSize=4");
        assertEquals(6, numberOf(first.get("total")),
                "total 必须按**过滤后**的条数算 —— 它是给分页器算总页数用的，"
                        + "拿扫描行数当 total 会凭空多出三页。实际: " + first);
        assertEquals(4, records(first).size(), "第一页 4 条。实际: " + records(first));

        Map<String, Object> second = search(key, "&variableName=amount"
                + "&variableValueGreaterThan=5000&pageNum=2&pageSize=4");
        assertEquals(2, records(second).size(), "第二页 2 条。实际: " + records(second));

        Set<String> paged = new HashSet<>();
        for (Map<String, Object> record : records(first)) {
            paged.add(businessKeyOf(record));
        }
        for (Map<String, Object> record : records(second)) {
            paged.add(businessKeyOf(record));
        }
        assertEquals(6, paged.size(),
                "**两页拼起来必须正好是 6 条且互不重复** —— "
                        + "重复说明翻页排序不稳定（真库 ORDER BY 与 Java 侧排序不一致）。"
                        + "实际: " + paged);
        assertTrue(paged.contains("hit-0") && !paged.contains("miss-0"),
                "拼起来的应当全是命中那批。实际: " + paged);

        assertTrue(records(search(key, "&variableName=amount"
                + "&variableValueGreaterThan=5000&pageNum=3&pageSize=4")).isEmpty(),
                "**超出 total 的页必须是空的**，而不是回退到第一页");
    }

    // ==================== 三、内存实现与真库实现必须给同一个答案 ====================

    @Test
    @DisplayName("**同一批数据、同一组条件：内存实现与真库实现给出同一个集合**")
    void memoryAndJdbcAgreeOnTheSameBatch() {
        String key = deployIsolated();
        Map<String, Object> amounts = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            String businessKey = "agree-" + i;
            // amount 一半给数字、一半给字符串 —— 覆盖两种形态
            Object amount = i % 2 == 0 ? Integer.valueOf(6000 + i) : String.valueOf(6000 + i);
            amounts.put(businessKey, amount);
            startWithAmount(key, businessKey, amount);
        }
        startWithoutAmount(key, "agree-no-key");

        InMemoryWorkflowPersistence memoryRepo = new InMemoryWorkflowPersistence();
        memoryRepo.initialize();
        WfRepositoryService memoryDefinitions =
                new WfRepositoryService(memoryRepo);
        WfRuntimeService memoryRuntime = new WfRuntimeService(memoryDefinitions, memoryRepo,
                new WfEngine(), new WfHookDispatcher());
        WfDefinition sameDefinition = memoryDefinitions.deploy(new WfXmlParser().parse(NS
                + "  <process id=\"" + key + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>\n"
                + "  </process>\n"
                + "</definitions>\n"));
        for (Map.Entry<String, Object> entry : amounts.entrySet()) {
            memoryRuntime.startProcessInstance(sameDefinition, entry.getKey(), "alice", null,
                    body("amount", entry.getValue()));
        }

        List<String> queries = new ArrayList<>();
        queries.add("&variableName=amount&variableValueGreaterThan=5000");
        queries.add("&variableName=amount&variableValueEquals=6002");
        queries.add("&variableName=amount&variableValueFrom=6000&variableValueTo=6002");
        queries.add("&variableName=amount");
        queries.add("&variableName=amount&variableValueLessThan=6002");

        for (String suffix : queries) {
            Set<String> onJdbc = businessKeys(key, suffix);
            Set<String> onMemory = businessKeysInMemory(memoryRuntime, key, suffix);

            assertEquals(onMemory, onJdbc,
                    "**两个 persistence 实现对 `" + suffix + "` 给出了不同的答案** —— "
                            + "这是本特性最危险的一种错法：core 全绿、真库上才不一样，"
                            + "而调用方只会看到" + onJdbc.size() + " 条。"
                            + "内存: " + onMemory + " / JDBC: " + onJdbc);
        }
    }

    private Set<String> businessKeysInMemory(WfRuntimeService memoryRuntime, String key,
            String querySuffix) {
        WfProcessInstanceQuery query = new WfProcessInstanceQuery();
        applySuffix(query, querySuffix);
        query.setDefinitionKey(key);
        Set<String> keys = new TreeSet<>();
        for (WfProcessInstance instance : memoryRuntime.queryProcessInstances(query)) {
            keys.add(instance.getBusinessKey());
        }
        return keys;
    }

    /**
     * 把 REST 那串后缀翻译成查询对象。
     *
     * <p><b>两遍走</b>，不是边扫边设：变量名与值条件是**两个独立的参数</b>
     * （{@code variableName=amount&variableValueGreaterThan=6000}），
     * 边扫边设就得假设「名一定写在值前面」，而 URL 上参数的顺序没有任何保证。
     *
     * <p>手写解析而不是复用 controller 的私有方法：那条方法是 controller 的实现细节，
     * 复用它等于让判据验的是"controller 和它自己一致"。
     */
    private static void applySuffix(WfProcessInstanceQuery query, String suffix) {
        Map<String, String> params = new HashMap<>();
        for (String pair : suffix.replaceFirst("^&", "").split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        String name = params.get("variableName");
        if (name == null) {
            return;
        }
        if (params.containsKey("variableValueEquals")) {
            query.setVariableValueEquals(name, params.get("variableValueEquals"));
        } else if (params.containsKey("variableValueGreaterThan")) {
            query.setVariableValueGreaterThan(name, parse(params.get("variableValueGreaterThan")));
        } else if (params.containsKey("variableValueLessThan")) {
            query.setVariableValueLessThan(name, parse(params.get("variableValueLessThan")));
        } else if (params.containsKey("variableValueFrom") && params.containsKey("variableValueTo")) {
            query.setVariableValueBetween(name, parse(params.get("variableValueFrom")),
                    parse(params.get("variableValueTo")));
        } else if (params.containsKey("variableValueFrom")) {
            query.setVariableValueBetween(name, parse(params.get("variableValueFrom")),
                    Double.MAX_VALUE);
        } else if (params.containsKey("variableValueTo")) {
            query.setVariableValueBetween(name, -Double.MAX_VALUE,
                    parse(params.get("variableValueTo")));
        } else {
            query.setVariableName(name);
        }
    }

    private static double parse(String value) {
        return Double.parseDouble(value);
    }

    // ==================== 四、REST 接线 ====================

    @Test
    @DisplayName("同时给两个比较 ⇒ **400**；只给值不给名 ⇒ **400**")
    void conflictingVariableConditionsAreRejectedOverHttp() {
        String key = deployIsolated();

        assertBadRequest(key, "&variableName=amount"
                + "&variableValueGreaterThan=5000&variableValueLessThan=1000",
                "同时给大于和小于 —— 互斥单值模型在 REST 上也要真的互斥，"
                        + "否则后一个 setter 悄悄盖掉前一个，"
                        + "而调用方以为两个条件都生效了");
        assertBadRequest(key, "&variableValueEquals=5000",
                "只给值不给名 ⇒ 无从知道比哪个变量。"
                        + "悄悄忽略比报错坏：调用方看到的是一份全量清单");
        assertBadRequest(key, "&variableName=amount&variableValueEquals=5000"
                + "&variableValueFrom=1&variableValueTo=2",
                "等值与区间同时给同样是打架");
    }

    @Test
    @DisplayName("只给区间一端 ⇒ 按无界处理，不报错")
    void halfOpenRangeIsTreatedAsUnbounded() {
        String key = deployIsolated();
        startWithAmount(key, "h-small", 10);
        startWithAmount(key, "h-big", 6000);

        Set<String> fromOnly = businessKeys(key,
                "&variableName=amount&variableValueFrom=5000");
        assertEquals(1, fromOnly.size(),
                "`From` 单给就是「≥ From」。实际: " + fromOnly);
        Set<String> toOnly = businessKeys(key,
                "&variableName=amount&variableValueTo=5000");
        assertEquals(1, toOnly.size(),
                "`To` 单给就是「≤ To」。实际: " + toOnly);
    }

    // ==================== 小工具 ====================

    private void assertBadRequest(String definitionKey, String querySuffix, String why) {
        ResponseEntity<Map> response = rawGet(
                "/api/approval-center/processes/search?definitionKey=" + definitionKey
                        + querySuffix);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                why + "。实际: " + response.getBody());
    }

    private Map<String, Object> search(String definitionKey, String querySuffix) {
        Map<String, Object> envelope = exchange(HttpMethod.GET,
                "/api/approval-center/processes/search?definitionKey=" + definitionKey
                        + querySuffix, null);
        return asMap(envelope.get("data"));
    }

    private Set<String> businessKeys(String definitionKey, String querySuffix) {
        Set<String> keys = new TreeSet<>();
        for (Map<String, Object> record : records(search(definitionKey, querySuffix))) {
            keys.add(businessKeyOf(record));
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("records");
    }

    private static String businessKeyOf(Map<String, Object> record) {
        return String.valueOf(record.get("businessKey"));
    }

    private static int numberOf(Object value) {
        return ((Number) value).intValue();
    }

    private void sleepAcrossMillis() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待两批数据的 START_TIME 拉开时被中断", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchange(HttpMethod method, String url, Object request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.exchange(url, method,
                new HttpEntity<Object>(request, headers), Map.class);
        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "调用应当成功，实际 " + response.getStatusCode() + "：" + response.getBody());
        Map<String, Object> envelope = response.getBody();
        assertNotNull(envelope, "响应体不能为空");
        return envelope;
    }

    private ResponseEntity<Map> rawGet(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url, HttpMethod.GET,
                new HttpEntity<Object>(null, headers), Map.class);
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> map = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}