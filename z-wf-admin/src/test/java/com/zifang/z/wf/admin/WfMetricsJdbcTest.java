package com.zifang.z.wf.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.util.Date;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.service.WfRepositoryService;

/**
 * 引擎指标（第 44 轮）的<b>真 JDBC 端到端</b>验证。
 *
 * <h3>为什么这一层非有不可</h3>
 * core 的 {@code WfMetricsTest} 里，时长类判据是<b>手工 {@code setStartTime/setEndTime}</b>
 * 造出来的 —— 那条路<b>完全绕开了 JDBC 序列化</b>。
 * 而真库上时间要经过 {@code java.sql.Timestamp} 落库、再读回成 {@code Date}，
 * <b>精度可能被数据库截断</b>。
 *
 * <p>截断的症状特别坏：时长平均会差几毫秒，看起来"差不多对"，
 * 没人会去核；而分桶边界（恰好 1 分钟算下一桶）在毫秒级偏差下<b>直接归错桶</b>。
 *
 * <p>另外<b>窗口边界在两套实现里走的是不同代码</b>：
 * {@code InMemoryWorkflowPersistence} 用 Java 比较，
 * {@code JdbcWorkflowPersistence} 用 SQL 的 {@code >=} / {@code <=} ——
 * 两边是不是都闭，只有真库能答。
 *
 * @author zifang
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2-test")
class WfMetricsJdbcTest {

    private static final AtomicInteger DEF_SEQ = new AtomicInteger();
    private static final long HOUR = 3_600_000L;
    private static final long MINUTE = 60_000L;

    private static final String NS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private WfRepositoryService repositoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 每个测试一套独立的流程 key，见 {@code WfProcessVariableQueryJdbcTest} 同款理由。 */
    private String deployIsolated() {
        String key = "mtr" + DEF_SEQ.incrementAndGet();
        repositoryService.deploy(new WfXmlParser().parse(NS
                + "  <process id=\"" + key + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"mtr-leader\"/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>\n"
                + "  </process>\n"
                + "</definitions>\n"));
        return key;
    }

    private String startOne(String definitionKey, String businessKey) {
        String pid = (String) exchange(HttpMethod.POST, "/api/approval-center/processes/start",
                body("definitionKey", definitionKey, "businessKey", businessKey,
                        "userId", "mtr-alice")).get("data");
        assertNotNull(pid, "应返回流程实例 id");
        return pid;
    }

    private void completeOne(String pid) {
        Map<String, Object> overview = asMap(
                exchange(HttpMethod.GET, "/api/wf/process/overview?processInstanceId=" + pid, null)
                        .get("data"));
        List<Map<String, Object>> open = asList(overview.get("openTasks"));
        assertTrue(!open.isEmpty(), "流程应当停在审批待办上。实际: " + overview);
        exchange(HttpMethod.POST, "/api/approval-center/tasks/complete",
                body("taskId", open.get(0).get("id"), "userId", "mtr-leader",
                        "comment", "同意"));
    }

    /**
     * 直接改库里的时间戳。
     *
     * <p><b>这是刻意的白盒操作</b>：引擎实时打点出来的时长恒为几毫秒，
     * 落桶没有区分度；而"毫秒精度会不会被 TIMESTAMP 列截断"
     * 恰恰要求一个<b>精确已知</b>的时长做对照。
     * 绕过引擎改库是这里唯一能做到"精确已知"的办法，
     * 而被验的代码（指标计算）完全不碰这一层。
     */
    private void pinInstanceWindow(String pid, Date start, Date end) {
        int updated = jdbcTemplate.update(
                "UPDATE ZWF_PROCESS SET START_TIME=?, END_TIME=?, STATUS=? WHERE PROC_ID=?",
                Timestamp.from(start.toInstant()), Timestamp.from(end.toInstant()),
                "COMPLETED", pid);
        assertEquals(1, updated, "应当正好改到一行");
    }

    // ==================== 一、时间戳往返 ====================

    @Test
    @DisplayName("**毫秒精度不该被 TIMESTAMP 列截断**（分桶边界靠它）")
    void durationSurvivesTimestampRoundTrip() {
        String key = deployIsolated();
        String pid = startOne(key, "mtr-precise");
        completeOne(pid);

        Date start = new Date(1_760_000_000_000L);   // 固定时刻，不取 now
        Date end = new Date(start.getTime() + 2 * HOUR);
        pinInstanceWindow(pid, start, end);

        Map<String, Object> total = totalRow("/api/wf/management/metrics"
                + "?metric=process-instance-duration&definitionKey=" + key);
        assertEquals(1L, numberOf(total.get("count")));
        assertEquals(2 * HOUR, numberOf(total.get("avgMillis")),
                "**毫秒必须原样回来** —— 差几毫秒看着「差不多对」，"
                        + "没人会去核，而分桶边界在毫秒级偏差下会直接归错桶。实际: " + total);
        assertEquals(2 * HOUR, numberOf(total.get("minMillis")), "实际: " + total);
        assertEquals(2 * HOUR, numberOf(total.get("maxMillis")), "实际: " + total);
    }

    @Test
    @DisplayName("时长恰好落在**分桶下界**上时，落桶结果与 core 层一致")
    void bucketBoundaryIsExactAfterRoundTrip() {
        String key = deployIsolated();
        String pid = startOne(key, "mtr-boundary");
        completeOne(pid);

        // 恰好 5 分钟 —— 边界的那一侧
        Date start = new Date(1_760_000_000_000L);
        pinInstanceWindow(pid, start, new Date(start.getTime() + 5 * MINUTE));

        List<Map<String, Object>> rows = rowsOf("/api/wf/management/metrics"
                + "?metric=process-instance-duration&definitionKey=" + key);
        Map<String, Object> all = totalRow("/api/wf/management/metrics"
                + "?metric=process-instance-duration&definitionKey=" + key);
        assertEquals(5 * MINUTE, numberOf(all.get("avgMillis")),
                "**恰好 5 分钟在真库上也要精确等于 5 分钟** —— "
                        + "差 1 毫秒就落到上一桶去了。实际: " + all);
        // 恰好 5 分钟 = 下一档的**下界** ⇒ 落在 [5, 30 分钟)，
        // 而 [1, 5 分钟) 是**上界开**、不含 5 分钟 —— 两端各归一家，不重不漏
        assertEquals(0, bucketCount(rows, "0 秒 ~ 1 分钟"),
                "实际各桶: " + rows);
        assertEquals(0, bucketCount(rows, "1 分钟 ~ 5 分钟"),
                "**恰好 5 分钟不属于 [1, 5 分钟)** —— 那一档上界是开的。"
                        + "实际各桶: " + rows);
        assertEquals(1, bucketCount(rows, "5 分钟 ~ 30 分钟"),
                "它属于下一档的下界。实际各桶: " + rows);
    }

    // ==================== 二、窗口边界（SQL 侧） ====================

    @Test
    @DisplayName("**时间窗口两端都是闭的**（真库走 SQL，与内存实现的 Java 比较不是同一段代码）")
    void windowIsClosedOnBothEndsOnRealJdbc() {
        String key = deployIsolated();
        String pid = startOne(key, "mtr-window");
        Date exact = new Date(1_760_000_000_000L);
        pinInstanceWindow(pid, exact, new Date(exact.getTime() + MINUTE));

        String fmt = "yyyy-MM-dd'T'HH:mm:ss";
        String from = new java.text.SimpleDateFormat(fmt).format(exact);
        String to = new java.text.SimpleDateFormat(fmt).format(
                new Date(exact.getTime() + MINUTE));

        Map<String, Object> all = totalRow("/api/wf/management/metrics"
                + "?metric=process-instances&definitionKey=" + key
                + "&startDate=" + from + "&endDate=" + to);
        assertEquals(1, numberOf(all.get("count")),
                "**startTime 恰好等于 startDate、endTime 恰好等于 endDate 都算数** —— "
                        + "窗口若写成开区间，看板上「今天办完的单」会在今天查不到，"
                        + "而那是最常被问的一句话。实际: " + all);
    }

    // ==================== 三、REST 接线 ====================

    @Test
    @DisplayName("指标名**大小写与连字符都宽容**（URL 上的参数是人手敲的）")
    void metricNameIsCaseAndDashInsensitive() {
        String key = deployIsolated();
        startOne(key, "mtr-name");

        int dashed = status("/api/wf/management/metrics"
                + "?metric=process-instances&definitionKey=" + key);
        int upper = status("/api/wf/management/metrics"
                + "?metric=PROCESS_INSTANCES&definitionKey=" + key);
        int camel = status("/api/wf/management/metrics"
                + "?metric=processInstances&definitionKey=" + key);
        assertEquals(200, dashed, "连字符写法。");
        assertEquals(upper, dashed, "大小写宽容。");
        assertEquals(camel, dashed, "驼峰写法。");
    }

    @Test
    @DisplayName("**不认识的指标名 ⇒ 400 且列出可选值**，不静默当成 0")
    void unknownMetricNameIsRejectedOverHttp() {
        String key = deployIsolated();
        startOne(key, "mtr-unknown");

        ResponseEntity<Map> response = rawGet("/api/wf/management/metrics"
                + "?metric=avg-time-to-approve&definitionKey=" + key);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "**静默回落到某个指标上，看板上会挂着一份看起来正常却完全不是要的东西的数字**。"
                        + "实际: " + response.getBody());
        String message = String.valueOf(response.getBody().get("message"));
        assertTrue(message.contains("PROCESS_INSTANCES"),
                "**报错要列出全部可选指标名** —— 只说「不认识」的话，"
                        + "调用方得回去翻文档才知道该写什么。实际: " + message);
    }

    @Test
    @DisplayName("时间格式错 ⇒ 400，且**报错点名是哪个参数**")
    void badTimeFormatNamesTheParameter() {
        String key = deployIsolated();
        startOne(key, "mtr-badtime");

        ResponseEntity<Map> response = rawGet("/api/wf/management/metrics"
                + "?metric=process-instances&startDate=2026/10/1&endDate="
                + "2026-10-01T00:00:00");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), "实际: " + response.getBody());
        String message = String.valueOf(response.getBody().get("message"));
        assertTrue(message.contains("startDate"),
                "**三个可选日期参数长得一模一样，报一句「格式不对」等于让人自己猜**。"
                        + "实际: " + message);
    }

    @Test
    @DisplayName("definitionKey 过滤**对任务类指标也生效**（任务表上没有定义 key 可筛）")
    void definitionKeyFilterWorksForTaskMetricsOverHttp() {
        String keyA = deployIsolated();
        String keyB = deployIsolated();
        String pidA = startOne(keyA, "mtr-task-a");
        completeOne(pidA);
        String pidB = startOne(keyB, "mtr-task-b");
        completeOne(pidB);

        assertEquals(1, numberOf(totalRow("/api/wf/management/metrics"
                + "?metric=task-users&definitionKey=" + keyA).get("count")),
                "**筛了就得真的筛掉** —— 两个定义各办了一单，只该数出一单。实际: "
                        + totalRow("/api/wf/management/metrics?metric=task-users&definitionKey=" + keyA));
        assertEquals(1, numberOf(totalRow("/api/wf/management/metrics"
                + "?metric=task-duration&definitionKey=" + keyB).get("count")),
                "任务时长同理。实际: "
                        + totalRow("/api/wf/management/metrics?metric=task-duration&definitionKey=" + keyB));
    }

    // ==================== 小工具 ====================

    private int bucketCount(List<Map<String, Object>> rows, String label) {
        // 桶计数很小，这里 int 够用；只是 numberOf 现在返回 long
        for (Map<String, Object> row : rows) {
            if (label.equals(row.get("name"))) {
                return (int) numberOf(row.get("count"));
            }
        }
        throw new AssertionError("返回里没有名为「" + label + "」的桶。实际: " + rows);
    }

    private List<Map<String, Object>> rowsOf(String url) {
        return asList(exchange(HttpMethod.GET, url, null).get("data"));
    }

    private Map<String, Object> totalRow(String url) {
        for (Map<String, Object> row : rowsOf(url)) {
            if ("__ALL__".equals(row.get("name"))) {
                return row;
            }
        }
        throw new AssertionError("返回里没有 __ALL__ 汇总行: " + url);
    }

    private int status(String url) {
        return rawGet(url).getStatusCodeValue();
    }

    /** 刻意用 {@code longValue()}：毫秒值一大 {@code intValue()} 就会静默截断（第 45 轮踩过）。 */
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
    private static List<Map<String, Object>> asList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}