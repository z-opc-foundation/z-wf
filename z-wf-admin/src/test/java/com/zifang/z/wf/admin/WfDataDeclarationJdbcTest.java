package com.zifang.z.wf.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.service.WfRepositoryService;

/**
 * 数据声明的<b>真 JDBC 端到端</b>验证（第 46 轮）。
 *
 * <h3>这一层专门抓 core 层抓不到的两件事</h3>
 *
 * <p><b>① 数据声明必须真的落库。</b> core 的判据走的是
 * {@code WfDefinitionCodec} 内存往返，而线上走的是
 * <b>HTTP 部署 → 序列化进库 → 从库里读回 → 再序列化</b>这条链。
 * 少写一个列表的症状是「部署时看得到、重启后查不到」——
 * 而这正是本轮要消灭的那一类静默，只是换了个位置出现。
 * 所以本类<b>部署完之后必须重新从库里把定义读一遍</b>，而不是复用部署时的返回对象。
 *
 * <p><b>② 悬空引用要在 HTTP 入口就被挡住。</b> 校验器报 ERROR 之后
 * {@code deployXml} 会抛，异常 advice 把它翻成 4xx ——
 * 这一步是 core 单测碰不到的（core 里没有 HTTP 层，也没有异常翻译）。
 *
 * <p>另一条：本仓<b>不执行</b>数据关联、<b>不碰</b> dataStore，
 * 所以这里<b>没有一条判据是「数据流过去了」</b>。
 * 全部判据都是「声明没丢、错误没藏、事实说清楚了」。
 *
 * @author zifang
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2-test")
class WfDataDeclarationJdbcTest {

    private static final AtomicInteger DEF_SEQ = new AtomicInteger();

    private static final String NS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"http://zifang.com/wf/bpmn/ext\" targetNamespace=\"x\">\n";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private WfRepositoryService repositoryService;

    // ==================== 夹具 ====================

    /** 四类声明齐全、端点齐全的一份定义。 */
    private String fullDataXml(String key) {
        return NS
                + "  <process id=\"" + key + "\" name=\"数据流程\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <serviceTask id=\"t1\" name=\"扣款\" zifang:delegateClass=\"com.zifang.demo.Demo\">\n"
                + "      <ioSpecification>\n"
                + "        <dataInput id=\"din1\" name=\"订单\" dataObjectRef=\"do1\"/>\n"
                + "        <dataOutput id=\"dout1\" name=\"回执\" dataObjectRef=\"do2\"/>\n"
                + "      </ioSpecification>\n"
                + "      <dataInputAssociation id=\"dia1\" sourceRef=\"dor1\" targetRef=\"din1\">\n"
                + "        <transformation>${order.amount}</transformation>\n"
                + "      </dataInputAssociation>\n"
                + "      <dataOutputAssociation id=\"doa1\" sourceRef=\"do2\" targetRef=\"dout1\">\n"
                + "        <assignment>from <to>x</to> to <to>y</to></assignment>\n"
                + "      </dataOutputAssociation>\n"
                + "    </serviceTask>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t1\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"e\"/>\n"
                + "    <dataObject id=\"do1\" name=\"订单\" itemSubjectRef=\"tns:Order\"/>\n"
                + "    <dataObject id=\"do2\" name=\"回执\"/>\n"
                + "    <dataObjectReference id=\"dor1\" name=\"入参\" dataObjectRef=\"do1\"/>\n"
                + "    <dataStore id=\"ds1\" name=\"订单库\" capacity=\"1000\" isUnlimited=\"true\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
    }

    private String nextKey() {
        return "ddq" + DEF_SEQ.incrementAndGet();
    }

    // ==================== 一、落库往返 ====================

    @Test
    @DisplayName("**数据声明部署后要从库里读得回来**（少落一个列表的症状是「部署看得到、重启查不到」）")
    void dataDeclarationsSurviveRealDatabase() {
        String key = nextKey();
        // 走 HTTP 部署，与线上同一条路
        Map<String, Object> deployed = asMap(exchange(HttpMethod.POST, "/api/wf/definitions/deploy",
                body("xml", fullDataXml(key))).get("data"));
        assertEquals(1, intOf(deployed.get("version")), "首次部署应是版本 1");

        // **重新从库里读**——这一步是本条判据的全部意义。
        // 复用部署时的返回对象等于绕开了序列化/反序列化，
        // 而漏写某个列表的症状恰恰只出现在这两步之间。
        WfDefinition reread = repositoryService.getDefinition(key, 1);
        assertNotNull(reread, "部署完必须能从库里读回来");

        assertEquals(2, reread.getDataObjects().size(), "两个 dataObject 应从库里读回来。实际 id 列表: "
                + idsOf(reread.getDataObjects()));
        assertNotNull(reread.dataObject("do1"), "do1 读不回来");
        assertEquals("订单", reread.dataObject("do1").getName());
        assertEquals("tns:Order", reread.dataObject("do1").getItemSubjectRef());

        assertEquals(3, reread.getDataObjectReferences().size(),
                "dor1 + ioSpecification 的 din1/dout1 都该在。实际: "
                        + reread.getDataObjectReferences());
        assertNotNull(reread.dataObjectReference("din1"), "ioSpecification 的 dataInput 读不回来");

        assertEquals(1, reread.getDataStores().size(), "dataStore 应从库里读回来");
        assertEquals(Long.valueOf(1000L), ((Number) reread.dataStore("ds1").getCapacity()).longValue());
        assertTrue(reread.dataStore("ds1").isUnlimited());

        assertEquals(2, reread.getDataAssociations().size(),
                "数据关联应从库里读回来 —— **读不回来的话，重启后部署期那道端点校验会全判悬空**，"
                        + "而症状是「同一个模型重启前后表现不同」，重启是所有偶发问题的经典替罪羊。"
                        + "实际: " + reread.getDataAssociations());
        assertEquals("${order.amount}", reread.getDataAssociations().get(0).getTransformation());
    }

    @Test
    @DisplayName("**重启后端点校验仍给出同样的结论**（落库只改存储形态，不改语义）")
    void reloadedDefinitionValidatesIdentically() {
        String key = nextKey();
        exchange(HttpMethod.POST, "/api/wf/definitions/deploy", body("xml", fullDataXml(key)));
        // 部署成功本身就说明部署期校验放行了；再从库里读一遍确认它不会读出别的东西
        WfDefinition reread = repositoryService.getDefinition(key, 1);
        assertNotNull(reread);
        assertEquals("[]", new com.zifang.z.wf.core.definition.WfDefinitionValidator()
                .validate(reread).stream()
                .filter(issue -> issue.getSeverity()
                        == com.zifang.z.wf.core.definition.WfValidationIssue.Severity.ERROR)
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toList()).toString());
    }

    // ==================== 二、HTTP 暴露 ====================

    @Test
    @DisplayName("**REST 要把四类声明与端点原样吐出来**")
    void restExposesAllFourKinds() {
        String key = nextKey();
        exchange(HttpMethod.POST, "/api/wf/definitions/deploy", body("xml", fullDataXml(key)));

        Map<String, Object> data = asMap(exchange(HttpMethod.GET,
                "/api/wf/definitions/data?key=" + key + "&version=1", null).get("data"));
        assertNotNull(data, "应返回数据声明视图");
        assertEquals(key, data.get("key"));
        assertEquals(1, intOf(data.get("version")));

        List<Map<String, Object>> dataObjects = asList(data.get("dataObjects"));
        assertEquals(2, dataObjects.size(), "实际: " + dataObjects);
        assertEquals("PROCESS", dataObjects.get(0).get("scope"),
                "作用域要随响应给出，不能让人靠数嵌套层数自己算");

        List<Map<String, Object>> references = asList(data.get("dataObjectReferences"));
        assertEquals(3, references.size(), "实际: " + references);
        // 这份列表里混着三种出处，kind 必须一起给，否则调用方只能靠 id 猜
        List<String> kinds = new ArrayList<>();
        for (Map<String, Object> reference : references) {
            kinds.add(String.valueOf(reference.get("kind")));
        }
        assertTrue(kinds.contains("REFERENCE"), "实际 kinds: " + kinds);
        assertTrue(kinds.contains("INPUT"), "实际 kinds: " + kinds);
        assertTrue(kinds.contains("OUTPUT"), "实际 kinds: " + kinds);

        List<Map<String, Object>> stores = asList(data.get("dataStores"));
        assertEquals(1, stores.size(), "实际: " + stores);
        assertEquals(1000L, numberOf(stores.get(0).get("capacity")));
        assertEquals(Boolean.TRUE, stores.get(0).get("unlimited"));

        List<Map<String, Object>> associations = asList(data.get("dataAssociations"));
        assertEquals(2, associations.size(), "实际: " + associations);
        Map<String, Object> first = associations.get(0);
        assertEquals("INPUT", first.get("direction"));
        assertEquals("t1", first.get("ownerId"));
        assertEquals(Boolean.FALSE, first.get("processLevel"));
        assertEquals("${order.amount}", first.get("transformation"));
    }

    @Test
    @DisplayName("**REST 响应里要明说本引擎不执行数据关联**（只列声明不说这点 = 写成「引擎在管的」）")
    void restStatesEngineDoesNotExecuteThem() {
        String key = nextKey();
        exchange(HttpMethod.POST, "/api/wf/definitions/deploy", body("xml", fullDataXml(key)));

        Map<String, Object> data = asMap(exchange(HttpMethod.GET,
                "/api/wf/definitions/data?key=" + key, null).get("data"));
        assertEquals(Boolean.FALSE, data.get("engineReadsData"),
                "本引擎不执行数据关联、不对 dataStore 做存取 —— 这条事实必须出现在响应体里，"
                        + "只写在文档里它就会在某次改版后与实现脱节");
        assertNotNull(data.get("engineReadsDataReason"), "要给出一句能直接给人看的原因");
        assertTrue(String.valueOf(data.get("engineReadsDataReason")).contains("流程变量"),
                "实际: " + data.get("engineReadsDataReason"));
    }

    @Test
    @DisplayName("**没配 capacity 时返回 null 而不是 0**（0 读起来是「容量为零」）")
    void missingCapacityIsNullNotZero() {
        String key = nextKey();
        exchange(HttpMethod.POST, "/api/wf/definitions/deploy", body("xml",
                NS + "  <process id=\"" + key + "\" isExecutable=\"true\">\n"
                        + "    <startEvent id=\"s\"/><endEvent id=\"e\"/>\n"
                        + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"e\"/>\n"
                        + "    <dataStore id=\"ds1\" name=\"库\"/>\n"
                        + "  </process>\n</definitions>\n"));

        Map<String, Object> data = asMap(exchange(HttpMethod.GET,
                "/api/wf/definitions/data?key=" + key, null).get("data"));
        List<Map<String, Object>> stores = asList(data.get("dataStores"));
        assertEquals(1, stores.size());
        assertNull(stores.get(0).get("capacity"),
                "作者没写 capacity 就是「没配」，显示成 0 会被读成「容量为零」。实际: " + stores);
    }

    @Test
    @DisplayName("**查一个没部署过的 key 拿 4xx**（与 /model、/diagram 同一取舍：问错了就是问错了）")
    void unknownKeyFailsLoudly() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/wf/definitions/data?key=根本没部署过" + System.nanoTime(),
                HttpMethod.GET, new HttpEntity<Object>(null, jsonHeaders()), Map.class);
        assertFalse(response.getStatusCode().is2xxSuccessful(),
                "「这个 key 从没部署过」不是正常状态，回 data=null 会让调用方"
                        + "以为「部署了但没有任何数据声明」—— 那是两个完全不同的结论。实际: "
                        + response.getStatusCode() + " " + response.getBody());
    }

    @Test
    @DisplayName("**指定一个不存在的版本要报错，不能悄悄回落到最新版本**")
    void unknownVersionFailsLoudly() {
        String key = nextKey();
        exchange(HttpMethod.POST, "/api/wf/definitions/deploy", body("xml", fullDataXml(key)));

        ResponseEntity<Map> response = rest.exchange(
                "/api/wf/definitions/data?key=" + key + "&version=99",
                HttpMethod.GET, new HttpEntity<Object>(null, jsonHeaders()), Map.class);
        assertFalse(response.getStatusCode().is2xxSuccessful(),
                "问一个不存在的版本是真的问错了，回落成最新版本等于回答了另一个模型的问题，"
                        + "而响应里的 version 还会照实写出来，更难发现。实际: "
                        + response.getStatusCode());
    }

    // ==================== 三、悬空引用必须在部署期被挡 ====================

    @Test
    @DisplayName("**悬空的数据关联端点部署不了**（改动前它能部署，而且一个错都不报）")
    void danglingAssociationBlocksDeployment() {
        String key = nextKey();
        String xml = NS
                + "  <process id=\"" + key + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <serviceTask id=\"t1\" zifang:delegateClass=\"com.zifang.demo.Demo\"/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t1\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"e\"/>\n"
                + "    <dataObject id=\"do1\"/>\n"
                + "    <dataOutputAssociation id=\"doa1\" sourceRef=\"do1\" targetRef=\"根本没有这个\"/>\n"
                + "  </process>\n</definitions>\n";

        WfDefinitionException error = assertThrows(WfDefinitionException.class,
                () -> repositoryService.deployXml(xml, null),
                "端点悬空必须挡住部署。**改动前这份模型能部署成功且零报错**，"
                        + "而作者以为数据在流");
        assertTrue(error.getMessage().contains("根本没有这个"),
                "错消息要写清哪个名字解析不到: " + error.getMessage());
    }

    @Test
    @DisplayName("**悬空引用经 HTTP 部署也拿不到 2xx**")
    void danglingAssociationBlockedOverHttp() {
        String key = nextKey();
        String xml = NS
                + "  <process id=\"" + key + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s\"/>\n"
                + "    <serviceTask id=\"t1\" zifang:delegateClass=\"com.zifang.demo.Demo\"/>\n"
                + "    <endEvent id=\"e\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t1\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"e\"/>\n"
                + "    <dataObjectReference id=\"dor1\" dataObjectRef=\"查无此数据\"/>\n"
                + "  </process>\n</definitions>\n";

        ResponseEntity<Map> response = rest.exchange("/api/wf/definitions/deploy",
                HttpMethod.POST, new HttpEntity<Object>(body("xml", xml), jsonHeaders()), Map.class);
        assertFalse(response.getStatusCode().is2xxSuccessful(),
                "HTTP 入口同样要挡。实际: " + response.getStatusCode() + " " + response.getBody());
    }

    // ==================== 辅助 ====================

    private static List<String> idsOf(List<?> items) {
        List<String> ids = new ArrayList<>();
        for (Object item : items) {
            ids.add(String.valueOf(
                    item instanceof Map ? ((Map<?, ?>) item).get("id") : item));
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

    private long numberOf(Object value) {
        return ((Number) value).longValue();
    }

    private int intOf(Object value) {
        return ((Number) value).intValue();
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
