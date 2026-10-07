package com.zifang.z.wf.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.ActiveProfiles;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.service.WfRepositoryService;

/**
 * 任务管理属性与新查询条件的<b>真 JDBC 端到端</b>验证（第 45 轮）。
 *
 * <h3>这一层专门抓一件事</h3>
 * {@code WfTaskService#updateTask} 走的是 JDBC 的 <b>UPDATE</b> 路径，
 * 而那条路径<b>曾经漏写 DUE_DATE</b> ——
 * 症状是「改期接口返回成功、内存里也是新的、库里还是旧日期」，
 * 而超期查询正是按这个日期算的。
 *
 * <p>core 层验不到这个：它的 {@code WfTaskAdvancedQueryTest} 直接调
 * {@code persistence.saveTask}，走的是 INSERT 路径。
 * ⇒ **只有走完整的 HTTP → service → UPDATE → 重读 这条链才验得出来。**
 *
 * <p>另一条：筛选器那六个新键（{@code minPriority} / {@code dueDateTo} 等）
 * 要经过 <b>JSON → 键名白名单 → 查询对象</b> 三道转换，
 * 少改任何一处，筛出来的结果都只是"少了一些"而不是"报错"。
 *
 * @author zifang
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2-test")
class WfTaskManagementJdbcTest {

    private static final AtomicInteger DEF_SEQ = new AtomicInteger();
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    private static final String NS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private WfRepositoryService repositoryService;

    private String deployIsolated() {
        String key = "tmq" + DEF_SEQ.incrementAndGet();
        repositoryService.deploy(new WfXmlParser().parse(NS
                + "  <process id=\"" + key + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"tmq-leader\"/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>\n"
                + "  </process>\n"
                + "</definitions>\n"));
        return key;
    }

    private String startOne(String definitionKey) {
        String pid = (String) exchange(HttpMethod.POST, "/api/approval-center/processes/start",
                body("definitionKey", definitionKey,
                        "businessKey", "TMQ-" + System.nanoTime(),
                        "userId", "tmq-alice")).get("data");
        assertNotNull(pid, "应返回流程实例 id");
        List<Map<String, Object>> open = asList(asMap(
                exchange(HttpMethod.GET, "/api/wf/process/overview?processInstanceId=" + pid, null)
                        .get("data")).get("openTasks"));
        assertEquals(1, open.size(), "应当停在一张审批待办上。实际: " + open);
        return String.valueOf(open.get(0).get("id"));
    }

    // ==================== 一、改期必须真的存进库 ====================

    @Test
    @DisplayName("**改期要真的存进库**（UPDATE 路径漏写 DUE_DATE 的回归）")
    void dueDateChangeIsPersistedOverHttp() {
        String key = deployIsolated();
        String taskId = startOne(key);

        long threeDaysLater = System.currentTimeMillis() + 3 * DAY;
        Map<String, Object> updated = asMap(exchange(HttpMethod.PUT, "/api/wf/task/management",
                body("taskId", taskId, "dueDate", Long.valueOf(threeDaysLater))).get("data"));
        assertEquals(threeDaysLater, numberOf(updated.get("dueDate")),
                "返回体里就该是改后的日期。实际: " + updated);

        // **重新查一遍**：这一步是本条判据的全部意义 ——
        // 它走的是"从库里读"，而漏写 DUE_DATE 的症状恰恰是"读出来还是旧的"
        Map<String, Object> reread = asMap(exchange(HttpMethod.GET,
                "/api/approval-center/tasks/get?taskId=" + taskId, null).get("data"));
        assertEquals(threeDaysLater, numberOf(reread.get("dueDate")),
                "**只写进 INSERT 的话，改期接口返回成功、内存里也是新的，"
                        + "下一次从库里读出来还是旧日期** —— 而超期查询正是按这个日期算的。"
                        + "症状是「改期没报错、清单上的期限没变、超期统计照旧」，"
                        + "三处对不上账，而人只会怀疑是不是接口没调。实际: " + reread);
    }

    @Test
    @DisplayName("**改期之后超期清单要跟着变**（端到端：改期 → 重查 → 结果不同）")
    void overdueListFollowsTheDueDateChange() {
        String key = deployIsolated();
        String taskId = startOne(key);

        long twoDaysAgo = System.currentTimeMillis() - 2 * DAY;
        exchange(HttpMethod.PUT, "/api/wf/task/management",
                body("taskId", taskId, "dueDate", Long.valueOf(twoDaysAgo)));
        assertTrue(overdueTaskIds(key).contains(taskId),
                "改成两天前之后，它就该进超期清单。实际: " + overdueTaskIds(key));

        long thirtyDaysLater = System.currentTimeMillis() + 30 * DAY;
        exchange(HttpMethod.PUT, "/api/wf/task/management",
                body("taskId", taskId, "dueDate", Long.valueOf(thirtyDaysLater)));
        assertTrue(!overdueTaskIds(key).contains(taskId),
                "**改成三十天后就不该再在清单里** —— 清单不跟着变，"
                        + "就说明这次改期压根没存进去。实际: " + overdueTaskIds(key));
    }

    @Test
    @DisplayName("**优先级改完也存得回去**（同一处 UPDATE 路径的另一个字段）")
    void priorityChangeIsPersistedOverHttp() {
        String key = deployIsolated();
        String taskId = startOne(key);

        exchange(HttpMethod.PUT, "/api/wf/task/management",
                body("taskId", taskId, "priority", Integer.valueOf(95)));

        Map<String, Object> reread = asMap(exchange(HttpMethod.GET,
                "/api/approval-center/tasks/get?taskId=" + taskId, null).get("data"));
        assertEquals(95L, numberOf(reread.get("priority")),
                "PRIORITY 本来就在 UPDATE 列表里，这条是防它哪天被挪出去。实际: " + reread);
    }

    // ==================== 二、入参校验 ====================

    @Test
    @DisplayName("四项全空 ⇒ **400**：返回成功而什么都没改比报错难查")
    void emptyManagementRequestIsRejected() {
        String key = deployIsolated();
        String taskId = startOne(key);

        ResponseEntity<Map> response = raw(HttpMethod.PUT, "/api/wf/task/management",
                body("taskId", taskId));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "**调一下管理属性而什么都没发生**，调用方看到 200 就以为改成了。实际: "
                        + response.getBody());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("priority"),
                "报错要点名有哪些可调项。实际: " + response.getBody());
    }

    @Test
    @DisplayName("taskId 为空 ⇒ **400**")
    void missingTaskIdIsRejected() {
        ResponseEntity<Map> response = raw(HttpMethod.PUT, "/api/wf/task/management",
                body("priority", Integer.valueOf(50)));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "实际: " + response.getBody());
    }

    // ==================== 三、筛选器的新键 ====================

    @Test
    @DisplayName("筛选器的六个新键**要真能筛出东西**（JSON → 白名单 → 查询对象 三道转换）")
    void newFilterKeysActuallyFilter() {
        String key = deployIsolated();
        String taskId = startOne(key);
        exchange(HttpMethod.PUT, "/api/wf/task/management",
                body("taskId", taskId, "priority", Integer.valueOf(95)));

        // 一条按「优先级 ≥ 90」的筛选器
        String high = createTaskFilter("高优先级-" + System.nanoTime(),
                body("minPriority", "90"));
        List<String> highIds = runFilterIds(high);
        assertTrue(highIds.contains(taskId),
                "刚调到 95 的那条该在里面。实际: " + highIds);

        // 一条按「优先级 ≥ 99」的筛选器：同一条不该再出现
        String veryHigh = createTaskFilter("超高优先级-" + System.nanoTime(),
                body("minPriority", "99"));
        assertTrue(!runFilterIds(veryHigh).contains(taskId),
                "**条件真的生效了** —— 这条是防「键名没接上、筛选器全量返回」的。实际: "
                        + runFilterIds(veryHigh));
    }

    @Test
    @DisplayName("筛选器的**截止时间键**能查出超期待办（审批最常做的那一次查询）")
    void dueDateFilterFindsOverdueOnes() {
        String key = deployIsolated();
        String taskId = startOne(key);

        String none = createTaskFilter("未设截止-" + System.nanoTime(),
                body("dueDateTo", String.valueOf(System.currentTimeMillis())));
        assertTrue(!runFilterIds(none).contains(taskId),
                "还没设截止时间的任务**不算超期**。实际: " + runFilterIds(none));

        exchange(HttpMethod.PUT, "/api/wf/task/management",
                body("taskId", taskId, "dueDate", Long.valueOf(System.currentTimeMillis() - DAY)));

        String overdue = createTaskFilter("已超期-" + System.nanoTime(),
                body("dueDateTo", String.valueOf(System.currentTimeMillis())));
        assertTrue(runFilterIds(overdue).contains(taskId),
                "设了昨天到期之后就该进了。实际: " + runFilterIds(overdue));
    }

    @Test
    @DisplayName("筛选器里**写错的键名会当场报错**，不静默全量返回")
    void unknownFilterKeyIsRejected() {
        ResponseEntity<Map> response = raw(HttpMethod.POST, "/api/wf/filters",
                body("name", "写错的键-" + System.nanoTime(), "resourceType", "task",
                        "properties", body("priorityGte", "90")));
        // 建筛选器时条件当场验能不能用 —— 错键名不该被存成一张"永远全量"的筛选器
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "**存一张永远返回全量的筛选器，比建不出来坏得多** —— "
                        + "它会在某天被人当成筛选功能坏了。实际: " + response.getBody());
    }

    // ==================== 小工具 ====================

    private List<String> overdueTaskIds(String definitionKey) {
        String filterId = createTaskFilter("超期-" + System.nanoTime(),
                body("dueDateTo", String.valueOf(System.currentTimeMillis())));
        return runFilterIds(filterId);
    }

    private String createTaskFilter(String name, Map<String, Object> properties) {
        Map<String, Object> envelope = exchange(HttpMethod.POST, "/api/wf/filters",
                body("name", name, "resourceType", "task", "owner", "tmq-alice",
                        "properties", properties));
        Object data = envelope.get("data");
        String filterId = data instanceof Map ? String.valueOf(((Map<?, ?>) data).get("id"))
                : String.valueOf(data);
        assertNotNull(filterId, "应返回筛选器 id。实际: " + envelope);
        return filterId;
    }

    @SuppressWarnings("unchecked")
    private List<String> runFilterIds(String filterId) {
        Object data = exchange(HttpMethod.GET, "/api/wf/filters/" + filterId
                + "/results?pageSize=200", null).get("data");
        List<String> ids = new ArrayList<>();
        if (data instanceof Map) {
            Object records = ((Map<String, Object>) data).get("records");
            if (records instanceof List) {
                for (Object record : (List<Object>) records) {
                    ids.add(String.valueOf(((Map<?, ?>) record).get("id")));
                }
            }
        } else if (data instanceof List) {
            for (Object record : (List<Object>) data) {
                ids.add(String.valueOf(((Map<?, ?>) record).get("id")));
            }
        }
        return ids;
    }

    /**
     * 取数值。<b>刻意用 {@code longValue()} 而不是 {@code intValue()}</b>：
     * 毫秒时间戳约 1.79e12，{@code intValue()} 会静默截断成 631870583 ——
     * 症状是「断言红、而返回体里的值明明是对的」，真因在测试自己。
     */
    private long numberOf(Object value) {
        return ((Number) value).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchange(HttpMethod method, String url, Object request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.exchange(url, method,
                new HttpEntity<Object>(request, headers), Map.class);
        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "调用应当成功，实际 " + response.getStatusCode() + "：" + response.getBody());
        assertNotNull(response.getBody(), "响应体不能为空");
        return response.getBody();
    }

    private ResponseEntity<Map> raw(HttpMethod method, String url, Object request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url, method, new HttpEntity<Object>(request, headers), Map.class);
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> map = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}