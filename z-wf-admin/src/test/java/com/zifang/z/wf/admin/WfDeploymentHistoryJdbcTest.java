package com.zifang.z.wf.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.service.WfRepositoryService;

/**
 * 部署历史的<b>真 JDBC 端到端</b>验证（第 47 轮）。
 *
 * <h3>这一层专门抓 core 层抓不到的三件事</h3>
 *
 * <p><b>① 投影是从真库读出来的。</b> core 的比对用的是一个刚
 * {@code saveDefinition} 进去的库，而线上是「部署 → 进程重启 → 别的 JVM 读」。
 * {@code hasSourceXml} 尤其可疑：它两边口径不同（内存侧在 Java 里判空、
 * JDBC 侧用 {@code CASE WHEN}），而它决定 {@code getProcessModel} 会不会抛错。
 *
 * <p><b>② {@code CASE WHEN} 是不是真的能在真 H2 上跑。</b>
 * 那个表达式是本轮唯一一处"为了不把 CLOB 拉进内存"而写的 SQL，
 * 它若在某个库上不成立，症状是<b>整条查询抛 SQLException</b>——
 * 而 core 层的 H2 与 admin 的 H2 版本未必一致。
 *
 * <p><b>③ 分页信封。</b> {@code total} 与 {@code records} 走的是同一套过滤，
 * 少写一处就是「页面写着共 6 条、下面列出 50 条」。
 *
 * @author zifang
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2-test")
class WfDeploymentHistoryJdbcTest {

    private static final AtomicInteger DEF_SEQ = new AtomicInteger();

    private static final String NS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"http://zifang.com/wf/bpmn/ext\" targetNamespace=\"x\">\n";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private WfRepositoryService repositoryService;

    private static String nextKey() {
        return "dhq" + DEF_SEQ.incrementAndGet();
    }

    /**
     * 走 {@code deployXml} 部署 —— <b>有</b>原始 XML。
     *
     * <p><b>本方法刻意不接受"指定部署时间"的参数。</b>
     * 最初它是 {@code deployAt(key, deployTime)}，参数被静默忽略了 ——
     * {@code WfRepositoryService#deploy} 落库前一定会执行
     * {@code definition.setStartTime(new Date())}。
     *
     * <p><b>那个覆盖是对的，不该改</b>：部署时间记的是"它是什么时候被部署的"，
     * 让调用方能指定，等于允许把一条部署记录伪造成三个月前的，
     * 而部署历史正是用来回答「上周到底改了什么」的地方。
     * ⇒ 夹具改成<b>读 {@code deployXml} 返回的真实值</b>，
     * 顺带记一笔：<b>参数写在那里却不起作用，是最难查的一类夹具缺陷</b> ——
     * 它不报错，症状是「时间窗判据莫名其妙全空」。
     */
    private WfDefinition deployXmlNow(String key) {
        WfDefinition deployed = repositoryService.deployXml(xmlOf(key), null);
        assertNotNull(deployed.getStartTime(), "deploy() 一定会写部署时间");
        return deployed;
    }

    /**
     * 走「先 parse 再 deploy」部署 —— <b>没有</b>原始 XML。
     *
     * <p>与 {@link #deployXmlNow} 并列存在，是因为这正是 {@code hasSourceXml}
     * 那一列要区分的两种真实路径：只有 {@code deployXml} 会把 BPMN 存进
     * {@code SOURCE_XML}，而 {@code getProcessModel} 遇到其余定义会直接抛错。
     *
     * <p>最初这条路径与 XML 路径混在同一个夹具里，判据因此报了
     * 「deployXml 部署的 hasSourceXml 却是 false」——
     * <b>那次是夹具写错，不是实现错</b>：实现把没有 XML 的定义标成 false 恰恰是对的。
     */
    private WfDefinition deployWithoutXmlNow(String key) {
        WfDefinition definition = new com.zifang.z.wf.core.definition.WfXmlParser().parse(xmlOf(key));
        definition.setCategory("r47");
        WfDefinition deployed = repositoryService.deploy(definition);
        assertNotNull(deployed.getStartTime());
        return deployed;
    }

    /**
     * 一份带分类的 BPMN。
     *
     * <p>分类走 {@code zifang:category} 扩展属性（{@code WfXmlParser} 从 {@code <process>}
     * 上读它）—— 所以<b>只有走 {@code deployXml} 的路径才带得上分类</b>，
     * 程序化部署要自己 {@code setCategory}。这正是「分类存在 {@code DEF_CATEGORY} 列
     * 与图 JSON 两处」这件事在本夹具里的体现，也是投影只读列的意义所在。
     */
    private static String xmlOf(String key) {
        return NS
                + "  <process id=\"" + key + "\" name=\"" + key + "流程\""
                + " zifang:category=\"r47\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <userTask id=\"t\" name=\"审批\" zifang:assignee=\"alice\"/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"t\" targetRef=\"e\"/>\n"
                + "  </process>\n</definitions>\n";
    }

    /** 拉开发送时间，让相邻两次部署落在不同毫秒。 */
    private static void ensureNextMillisecond() {
        try {
            Thread.sleep(5L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 一、投影真的从真库读出来 ====================

    @Test
    @DisplayName("**部署历史的每一列都要来自真库**（投影漏读一列，症状是清单上少一栏而没人发现）")
    void everyColumnComesFromRealDatabase() {
        String key = nextKey();
        WfDefinition first = deployXmlNow(key);
        long firstTime = first.getStartTime().getTime();
        repositoryService.suspendDefinition(key, first.getVersion());
        ensureNextMillisecond();
        WfDefinition second = deployXmlNow(key);

        Map<String, Object> data = asMap(exchange(HttpMethod.GET,
                "/api/wf/definitions/history?key=" + key
                        + "&orderBy=KEY_ASC&pageSize=50", null).get("data"));
        List<Map<String, Object>> records = asList(data.get("records"));
        assertEquals(2, records.size(),
                "**同一 key 的两个版本都要在** —— 这正是本轮存在的理由，"
                        + "而既有的 findDefinitions 每个 key 只出最新一版。实际: " + records);

        Map<String, Object> older = byVersion(records, 1);
        Map<String, Object> newer = byVersion(records, 2);

        assertEquals(key, older.get("key"));
        assertEquals(key + "流程", older.get("name"));
        assertEquals("r47", older.get("category"), "分类是从 DEF_CATEGORY 列读的，漏读就是 null");
        assertEquals(firstTime, numberOf(older.get("deployTime")),
                "**部署时间来自 DEPLOY_TIME 列** —— 漏读时它恒为 null，"
                        + "而「上周部署了什么」这个问题就答不了了");
        assertEquals(Boolean.TRUE, older.get("suspended"), "停用标记来自 SUSPENDED 列");
        assertEquals(Boolean.FALSE, older.get("defaultDefinition"));
        assertEquals(Boolean.TRUE, older.get("hasSourceXml"),
                "**deployXml 部署的必然有原始 XML** —— 这一列决定 getProcessModel 会不会抛错");

        assertEquals(Boolean.FALSE, newer.get("suspended"));
        assertTrue(numberOf(newer.get("deployTime")) > firstTime,
                "第二版的部署时间必须晚于第一版。实际: "
                        + newer.get("deployTime") + " vs " + firstTime);
    }

    @Test
    @DisplayName("**没有原始 XML 的定义在部署历史里标成不可回读**（该标记必须与 getProcessModel 的实际行为一致）")
    void definitionsWithoutXmlAreMarkedUnreadable() {
        // 两条都没有原始 XML 的真实路径：JSON 部署（LogicFlow 导出）与程序化部署
        String jsonKey = nextKey();
        WfDefinition fromJson = new com.zifang.z.wf.core.definition.WfJsonParser().parse(
                "{\"key\":\"" + jsonKey + "\",\"name\":\"JSON流程\","
                        + "\"nodes\":[{\"id\":\"s\",\"type\":\"startEvent\"},"
                        + "{\"id\":\"t\",\"type\":\"userTask\"},"
                        + "{\"id\":\"e\",\"type\":\"endEvent\"}],"
                        + "\"flows\":[{\"id\":\"f1\",\"sourceRef\":\"s\",\"targetRef\":\"t\"},"
                        + "{\"id\":\"f2\",\"sourceRef\":\"t\",\"targetRef\":\"e\"}]}");
        repositoryService.deploy(fromJson);

        String programmaticKey = nextKey();
        deployWithoutXmlNow(programmaticKey);

        for (String key : new String[]{jsonKey, programmaticKey}) {
            Map<String, Object> data = asMap(exchange(HttpMethod.GET,
                    "/api/wf/definitions/history?key=" + key, null).get("data"));
            List<Map<String, Object>> records = asList(data.get("records"));
            assertEquals(1, records.size(), "key=" + key);
            assertEquals(Boolean.FALSE, records.get(0).get("hasSourceXml"),
                    "**没有 SOURCE_XML 的定义恒为 false**（key=" + key + "）—— "
                            + "部署历史上标不出这一条，调用方只能挨个 key 试过去"
                            + "撞 getProcessModel 的异常");

            // 顺带证一下这个标记不是摆设：它标 false 的定义，回读原始 XML 真的会抛
            boolean threw = false;
            try {
                repositoryService.getProcessModel(key, 1);
            } catch (RuntimeException expected) {
                threw = true;
            }
            assertTrue(threw, "hasSourceXml=false 必须与 getProcessModel 的实际行为一致（key="
                    + key + "），否则这一列是骗人的");
        }
    }

    // ==================== 二、跨 key 与时间窗 ====================

    @Test
    @DisplayName("**跨所有 key 列出全部版本**（既有能力一条都答不了这个问题）")
    void listsAcrossAllKeys() {
        String keyA = nextKey();
        String keyB = nextKey();
        deployXmlNow(keyA);
        ensureNextMillisecond();
        deployXmlNow(keyA);
        ensureNextMillisecond();
        deployXmlNow(keyB);

        // 不带任何 key 条件：这两个 key 的三条都要在
        List<String> all = idsOf(asMap(exchange(HttpMethod.GET,
                "/api/wf/definitions/history?category=r47&pageSize=2000", null)
                .get("data")).get("records"));
        assertTrue(all.contains(keyA + ":1"), "实际: " + all);
        assertTrue(all.contains(keyA + ":2"), "实际: " + all);
        assertTrue(all.contains(keyB + ":1"), "实际: " + all);
    }

    @Test
    @DisplayName("**时间窗含端点**（漏掉端点，症状是「刚部署的那条不见了」）")
    void timeWindowIsInclusive() {
        String key = nextKey();
        WfDefinition first = deployXmlNow(key);
        long firstTime = first.getStartTime().getTime();
        ensureNextMillisecond();
        WfDefinition second = deployXmlNow(key);
        long secondTime = second.getStartTime().getTime();
        assertTrue(secondTime > firstTime, "两次部署必须落在不同毫秒，否则本条判据验不到端点");

        // 上界恰好等于第二版的部署时间：两版都要
        List<String> window = idsOf(asMap(exchange(HttpMethod.GET,
                "/api/wf/definitions/history?key=" + key + "&deployedTo=" + secondTime
                        + "&orderBy=KEY_ASC", null).get("data")).get("records"));
        assertEquals(2, window.size(), "**上界闭区间**：端点那条必须在。实际: " + window);

        // 上界比第二版早一毫秒：只剩第一版
        List<String> narrow = idsOf(asMap(exchange(HttpMethod.GET,
                "/api/wf/definitions/history?key=" + key + "&deployedTo=" + (secondTime - 1)
                        + "&orderBy=KEY_ASC", null).get("data")).get("records"));
        assertEquals(1, narrow.size(), "早一毫秒就该排除掉第二版。实际: " + narrow);
        assertEquals(key + ":" + first.getVersion(), narrow.get(0));

        // 下界同理：恰好等于第一版的部署时间时它在
        List<String> fromFirst = idsOf(asMap(exchange(HttpMethod.GET,
                "/api/wf/definitions/history?key=" + key + "&deployedFrom=" + firstTime
                        + "&orderBy=KEY_ASC", null).get("data")).get("records"));
        assertEquals(2, fromFirst.size(), "**下界闭区间**：端点那条必须在。实际: " + fromFirst);
    }

    // ==================== 三、分页信封 ====================

    @Test
    @DisplayName("**分页信封的 total 与 records 必须对得上**（少写一处就是「写着共 6 条、列出 50 条」）")
    void pagingEnvelopeIsConsistent() {
        String key = nextKey();
        for (int i = 0; i < 5; i++) {
            deployXmlNow(key);
            ensureNextMillisecond();
        }

        Map<String, Object> data = asMap(exchange(HttpMethod.GET,
                "/api/wf/definitions/history?key=" + key + "&pageNum=1&pageSize=2", null)
                .get("data"));
        assertEquals(2, asList(data.get("records")).size());
        assertEquals(5, numberOf(data.get("total")), "**total 是命中总条数，不受分页影响**");
        assertEquals(1, numberOf(data.get("pageNum")));
        assertEquals(2, numberOf(data.get("pageSize")));

        Map<String, Object> lastPage = asMap(exchange(HttpMethod.GET,
                "/api/wf/definitions/history?key=" + key + "&pageNum=3&pageSize=2", null)
                .get("data"));
        assertEquals(1, asList(lastPage.get("records")).size(), "第 3 页只剩 1 条");
        assertEquals(5, numberOf(lastPage.get("total")));

        // 越界页：空列表而不是回退到最后一页
        Map<String, Object> beyond = asMap(exchange(HttpMethod.GET,
                "/api/wf/definitions/history?key=" + key + "&pageNum=99&pageSize=2", null)
                .get("data"));
        assertTrue(asList(beyond.get("records")).isEmpty(),
                "越界页必须为空 —— 回退到最后一页会让调用方以为「还有更多」");
    }

    // ==================== 四、参数错误要报出来 ====================

    @Test
    @DisplayName("**不认识的排序值报 4xx 并列出可选值**（静默回落成默认排序 = 排序功能时灵时不灵）")
    void unknownOrderFailsLoudly() {
        String key = nextKey();
        deployXmlNow(key);

        ResponseEntity<Map> response = rest.exchange(
                "/api/wf/definitions/history?key=" + key + "&orderBy=随便写的",
                HttpMethod.GET, new HttpEntity<Object>(null, jsonHeaders()), Map.class);
        assertFalse(response.getStatusCode().is2xxSuccessful(),
                "静默回落的话，调用方以为是自己选错了顺序，"
                        + "而实际是接口把它吞了。实际: " + response.getStatusCode());
        String body = String.valueOf(response.getBody());
        assertTrue(body.contains("DEPLOY_TIME_DESC"),
                "错消息要列出可选值，否则调用方只能猜。实际: " + body);
    }

    @Test
    @DisplayName("**时间区间写反报 4xx**（返回空集 = 分不清「没部署过」与「你写反了」）")
    void reversedRangeFailsLoudly() {
        String key = nextKey();
        WfDefinition deployed = deployXmlNow(key);
        long now = deployed.getStartTime().getTime();

        ResponseEntity<Map> response = rest.exchange(
                "/api/wf/definitions/history?key=" + key
                        + "&deployedFrom=" + (now + 100000) + "&deployedTo=" + now,
                HttpMethod.GET, new HttpEntity<Object>(null, jsonHeaders()), Map.class);
        assertFalse(response.getStatusCode().is2xxSuccessful(),
                "实际: " + response.getStatusCode() + " " + response.getBody());
    }

    @Test
    @DisplayName("**排序参数宽容连字符与大小写**（前端传 deploy-time-desc 不该报 400）")
    void orderParamIsLenient() {
        String key = nextKey();
        deployXmlNow(key);
        for (String order : new String[]{"deploy-time-desc", "DEPLOY_TIME_DESC", "Deploy-Time-Desc"}) {
            Map<String, Object> data = asMap(exchange(HttpMethod.GET,
                    "/api/wf/definitions/history?key=" + key + "&orderBy=" + order, null)
                    .get("data"));
            assertEquals(1, asList(data.get("records")).size(), "orderBy=" + order + " 应当被接受");
        }
    }

    // ==================== 辅助 ====================

    private static Map<String, Object> byVersion(List<Map<String, Object>> records, int version) {
        for (Map<String, Object> record : records) {
            if (numberOf(record.get("version")) == version) {
                return record;
            }
        }
        throw new AssertionError("清单里没有版本 " + version + "，实际: " + records);
    }

    private static List<String> idsOf(Object value) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> record : asList(value)) {
            ids.add(record.get("key") + ":" + numberOf(record.get("version")));
        }
        return ids;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Map<String, Object> exchange(HttpMethod method, String url, Object request) {
        ResponseEntity<Map> response = rest.exchange(url, method,
                new HttpEntity<Object>(request, jsonHeaders()), Map.class);
        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "调用应当成功，实际 " + response.getStatusCode() + "：" + response.getBody());
        assertNotNull(response.getBody(), "响应体不能为空");
        return response.getBody();
    }

    private static long numberOf(Object value) {
        return ((Number) value).longValue();
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
