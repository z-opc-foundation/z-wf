package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfProcessInstanceQuery;

/**
 * 按变量值查流程实例（第 43 轮）。
 *
 * <p>要回答的是审批系统里最常问的两句话：
 * <b>"找出金额超过 5000 的在途单"</b> 与 <b>"哪些单子还没填金额"</b>。
 *
 * <p>本类里最要紧的不是"四种比较各能选中什么"，而是三条<b>否定式</b>断言：
 * 分页不能被切掉命中的那几条、count 与 list 必须一致、
 * 非数值变量不能让整个查询失败。
 * —— 这三条任何一条错了，返回的都是一份<b>看起来完整</b>的错清单。
 */
class WfProcessVariableQueryTest {

    private static final String NS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n";

    private static final String APPROVAL_BPMN = NS
            + "  <process id=\"vqProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
    }

    /**
     * 起一单，变量原样写入（含 null 值 —— "没这个键"与"这个键是 null"是两件事）。
     *
     * <p>返回<b>自己传进去的 businessKey</b> 而不是 {@code startProcessInstance}
     * 的返回值 —— 后者是流程实例 id，混进断言里只会让人分不清"标签是我给的"
     * 还是"引擎给的"。单子有没有起成功，由后面按 businessKey 查出来断。
     */
    private String start(String businessKey, Map<String, Object> variables) {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(APPROVAL_BPMN));
        runtime.startProcessInstance(definition, businessKey, "alice", null, variables);
        return businessKey;
    }

    /**
     * 起一单，只写 {@code amount} 一个变量。
     *
     * @param amount {@code null} 表示<b>压根不写这个键</b>，而不是"写一个值为 null 的键"
     */
    private String startAmount(String businessKey, Object amount) {
        Map<String, Object> vars = new HashMap<>();
        if (amount != null) {
            vars.put("amount", amount);
        }
        return start(businessKey, vars);
    }

    private Map<String, Object> single(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    private List<String> businessKeys(WfProcessInstanceQuery query) {
        List<String> keys = new ArrayList<>();
        for (WfProcessInstance instance : runtime.queryProcessInstances(query)) {
            keys.add(instance.getBusinessKey());
        }
        return keys;
    }

    private int countOf(WfProcessInstanceQuery query) {
        return (int) runtime.countProcessInstances(query);
    }

    /**
     * 把两批数据的 startTime 拉开。
     *
     * <p><b>不是为了让测试慢</b>，是因为排序键 {@code startTime} 只到毫秒：
     * 连着起两批单很可能落在同一毫秒里，而 {@code Collections.sort} 是稳定的 ——
     * 键相同时退化成插入顺序，于是"命中的那批"又跑回最前面，
     * 分页断言就变成了一个<b>恒绿</b>的假判据。
     * 20ms 足够跨过任何一次毫秒进位。
     */
    private void sleepForDistinctStartTime() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待两批数据的 startTime 拉开时被中断", ex);
        }
    }

    // ==================== 四种比较 ====================

    @Test
    @DisplayName("按变量的值查：等于 / 大于 / 小于 / 区间")
    void compareByVariableValue() {
        startAmount("v-100", "100");
        startAmount("v-5000", "5000");
        startAmount("v-6000", "6000");

        assertEquals(1, businessKeys(new WfProcessInstanceQuery()
                .setVariableValueEquals("amount", "5000")).size(),
                "等值应当只选中那一个。实际: "
                        + businessKeys(new WfProcessInstanceQuery().setVariableValueEquals("amount", "5000")));
        assertEquals(1, businessKeys(new WfProcessInstanceQuery()
                .setVariableValueGreaterThan("amount", 5000)).size(),
                "**严格大于**：5000 那一条不算（阈值语义就是「超过」），只有 6000 命中。"
                        + "写成 >= 是另一回事 —— 那是区间查询的闭区间那一支。实际: "
                        + businessKeys(new WfProcessInstanceQuery().setVariableValueGreaterThan("amount", 5000)));
        assertEquals(1, businessKeys(new WfProcessInstanceQuery()
                .setVariableValueLessThan("amount", 5000)).size());
        assertEquals(2, businessKeys(new WfProcessInstanceQuery()
                .setVariableValueBetween("amount", 1000, 6000)).size(),
                "**闭区间**：5000 与 6000 都算（审批的阈值说的就是包含边界）。实际: "
                        + businessKeys(new WfProcessInstanceQuery().setVariableValueBetween("amount", 1000, 6000)));
        assertEquals(3, businessKeys(new WfProcessInstanceQuery()
                .setVariableValueBetween("amount", 100, 6000)).size());
    }

    @Test
    @DisplayName("只给变量名 = 问「有没有这个变量」，对应「哪些单子还没填金额」")
    void existenceOnly() {
        String filled = startAmount("filled", "100");
        startAmount("never-set", null);

        List<String> found = businessKeys(new WfProcessInstanceQuery()
                .setVariableName("amount").setPageSize(50));
        assertEquals(1, found.size(),
                "只该选中填过的那一单。实际: " + found);
        assertEquals(filled, found.get(0));

        assertEquals(1, businessKeys(new WfProcessInstanceQuery()
                .setVariableValueEquals("amount", "100")).size(),
                "填过且值相同的也命中 —— 这是与「存在性」共用一个名字的两种查法");
    }

    @Test
    @DisplayName("等值按**字符串形态**比：数字 100 与字符串 \"100\" 判相等")
    void equalsComparesByStringForm() {
        startAmount("as-number", 100);
        startAmount("as-string", "100");

        assertEquals(2, businessKeys(new WfProcessInstanceQuery()
                .setVariableValueEquals("amount", "100")).size(),
                "**这两条判相等，而这是对的**：金额在表单里填进来就是字符串 \"100\"，"
                        + "后端另一条路径又可能存成数字 100 —— 按类型严格比的话，"
                        + "=100 查不到字符串那一批，而且**不报任何错**。"
                        + "「查不出来」比「多查出几条形态相同的」危险得多。实际: "
                        + businessKeys(new WfProcessInstanceQuery().setVariableValueEquals("amount", "100")));
        assertEquals(2, businessKeys(new WfProcessInstanceQuery()
                .setVariableValueGreaterThan("amount", 50)).size(),
                "**数值比较也必须 parse 字符串** —— 上面那条是形态比，这条是真的当数字算，"
                        + "两个 setter 走的**不是同一把尺子**。实际: "
                        + businessKeys(new WfProcessInstanceQuery().setVariableValueGreaterThan("amount", 50)));
    }

    @Test
    @DisplayName("**键不存在 ≠ 值为 null**：没设过 amount 的单子不该被任何值条件命中")
    void missingKeyIsNotNullValue() {
        startAmount("no-key", null);
        start("explicit-null", single("amount", null));

        List<String> nullForm = businessKeys(new WfProcessInstanceQuery()
                .setVariableValueEquals("amount", "null").setPageSize(50));
        assertEquals(1, nullForm.size(),
                "先 containsKey 再比。若不查键就直接比值，「压根没设过 amount」"
                        + "会被 `=null` 命中 —— 那会让「还没填金额的单」和"
                        + "「金额显式填成 null 的单」混成一批，"
                        + "而后者是有人明确填过的，缺审与已填是两回事。实际: " + nullForm);
        assertEquals("explicit-null", nullForm.get(0),
                "命中的应当是**显式写成 null 的那一单**，不是压根没设过的。实际: " + nullForm);
        assertEquals(1, businessKeys(new WfProcessInstanceQuery()
                .setVariableName("amount").setPageSize(50)).size(),
                "存在性那一支也必须先 containsKey —— 实际单号: "
                        + businessKeys(new WfProcessInstanceQuery().setVariableName("amount").setPageSize(50)));
    }

    @Test
    @DisplayName("数值比较遇到非数值：不命中那一条，**但不整个查询失败**")
    void nonNumericVariableDoesNotBreakTheQuery() {
        startAmount("v-good", "6000");
        startAmount("v-dirty", "待定");
        startAmount("v-small", "1");

        List<String> found = businessKeys(new WfProcessInstanceQuery()
                .setVariableValueGreaterThan("amount", 5000).setPageSize(50));
        assertEquals(1, found.size(),
                "「填成了文字」的那一条不参与比较，其余照常查。"
                        + "让整个查询因为一个脏数据失败，比查不全更糟 —— "
                        + "而漏掉的那条本该由业务的填单校验管，不该由查询拦。实际: " + found);
    }

    @Test
    @DisplayName("布尔当数值**不算**（不做「帮用户猜」）")
    void booleanIsNotANumber() {
        startAmount("flag", Boolean.TRUE);
        startAmount("amount", "6000");

        // 阈值取 0.5 而不是 1：反验证 M5 把 Boolean 变成 1.0，
        // 而 `1.0 > 1` 为假 —— 阈值挑 1 的话那条变异压根打不动这条判据，
        // 屏幕上是「判据不敏感」，真因是场景没卡在边界上
        List<String> found = businessKeys(new WfProcessInstanceQuery()
                .setVariableValueGreaterThan("amount", 0.5).setPageSize(50));
        assertEquals(1, found.size(),
                "把 true 当 1 是一种替用户猜，而 amount=true 这种数据本身就说明填错了。"
                        + "阈值必须低于 1 才能抓住「true 被当成 1」。实际: " + found);
    }

    // ==================== 分页与计数 ====================

    /**
     * 分页必须发生在<b>过滤之后</b>。
     *
     * <p>数据是这么摆的：<b>先 30 单命中、后 30 单不命中</b>，
     * 加上按 startTime 倒序 ⇒ 不命中的那批排在最前。
     * 于是"先 LIMIT 10 再过滤"必然取到 10 条不命中的、过滤完一条不剩 ——
     * 这正是"变量条件不能下推 SQL"在分页上的那份代价。
     */
    @Test
    @DisplayName("**分页不能把命中的那几条切在页外**（这正是不能下推 SQL 的原因）")
    void paginationCutsAfterFiltering() {
        for (int i = 0; i < 30; i++) {
            startAmount("hit-" + i, 100);
        }
        // 两批之间必须拉开时间差：排序键是 startTime，
        // 而 startTime 只到毫秒 —— 同毫秒时稳定排序退化成插入顺序，
        // 那就变成命中批次在前，下面那条断言会**恒绿**而抓不住任何东西
        sleepForDistinctStartTime();
        for (int i = 0; i < 30; i++) {
            startAmount("miss-" + i, 1);
        }

        // 前置断言：把排序顺序钉死。它不是走过场 ——
        // 若存储层排序与 sortForDisplay 不同（类注释里明写必须一致），
        // 后面那条会红，但原因会被这条先说清楚。
        List<String> rawFirstPage = businessKeys(new WfProcessInstanceQuery().setPageSize(10));
        assertTrue(rawFirstPage.get(0).startsWith("miss-"),
                "**前置条件**：startTime 倒序 ⇒ 不命中的那批（后起）必须在最前。"
                        + "若这里不过，后面的分页断言会恒绿。实际第一页: " + rawFirstPage);

        List<String> firstPage = businessKeys(new WfProcessInstanceQuery()
                .setVariableValueGreaterThan("amount", 50).setPageNum(1).setPageSize(10));
        assertEquals(10, firstPage.size(),
                "过滤要发生在分页之前。若先 LIMIT 10 再过滤，"
                        + "这一页很可能一条命中的都没有（现象是「明明有 30 条符合、"
                        + "翻到第一页却是空的」）。实际: " + firstPage);
        assertTrue(firstPage.get(0).startsWith("hit-"),
                "过滤后剩下的应当全是命中那批。实际: " + firstPage);

        assertEquals(30, countOf(new WfProcessInstanceQuery()
                .setVariableValueGreaterThan("amount", 50)),
                "count 也要按同一把尺子算 —— 两边各算各的话分页器会算出错的总页数");

        assertEquals(10, businessKeys(new WfProcessInstanceQuery()
                .setVariableValueGreaterThan("amount", 50).setPageNum(3).setPageSize(10)).size(),
                "最后一页也该是 10 条");
        assertTrue(businessKeys(new WfProcessInstanceQuery()
                .setVariableValueGreaterThan("amount", 50).setPageNum(4).setPageSize(10)).isEmpty(),
                "超出 total 的页应当是空的");
    }

    @Test
    @DisplayName("翻页不重不漏：把三页的单号拼起来应当正好等于全部命中")
    void pagesDoNotOverlapOrSkip() {
        for (int i = 0; i < 30; i++) {
            startAmount("hit-" + i, 100);
        }
        sleepForDistinctStartTime();
        for (int i = 0; i < 30; i++) {
            startAmount("miss-" + i, 1);
        }

        List<String> all = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            all.addAll(businessKeys(new WfProcessInstanceQuery()
                    .setVariableValueGreaterThan("amount", 50).setPageNum(page).setPageSize(10)));
        }
        assertEquals(30, new java.util.HashSet<>(all).size(),
                "**必须无重复**：同一个查询翻两页看到同一单，说明翻页排序不稳定。"
                        + "而排序规则与存储层不一致时，症状正是这个。实际条数: " + all.size()
                        + " 去重后: " + new java.util.HashSet<>(all).size());
    }

    @Test
    @DisplayName("**慢路径自己排的那一遍方向要对**（倒序：最新在前）")
    void slowPathSortsNewestFirst() {
        // **这条不能靠「第一页全是命中的」那条间接抓**：
        // 反验证 M9 把 sortForDisplay 改成升序时，那条仍然绿 ——
        // 变的是顺序，而命中批次内部谁在前它根本看不见。
        // 所以这里直接把「最新」钉成一个具体的单号。
        startAmount("oldest", 100);
        sleepForDistinctStartTime();
        startAmount("middle", 100);
        sleepForDistinctStartTime();
        startAmount("newest", 100);

        List<String> page = businessKeys(new WfProcessInstanceQuery()
                .setVariableName("amount").setPageNum(1).setPageSize(1));
        assertEquals(1, page.size());
        assertEquals("newest", page.get(0),
                "**慢路径读完是在 Java 里重排的（sortForDisplay）**，"
                        + "这一遍的方向必须与存储层的 ORDER BY 一致，"
                        + "否则同一个查询翻两页会看到顺序跳动。实际第一页: " + page);
        assertEquals("oldest", businessKeys(new WfProcessInstanceQuery()
                        .setVariableName("amount").setPageNum(3).setPageSize(1)).get(0),
                "最后一页是最早起的那一单");
    }

    @Test
    @DisplayName("没带变量条件 ⇒ 原路下推，行为与本特性之前完全一致")
    void fastPathIsUntouched() {
        startAmount("f-1", 100);
        startAmount("f-2", 6000);

        assertEquals(2, runtime.queryProcessInstances(
                new WfProcessInstanceQuery().setPageNum(1).setPageSize(10)).size());
        assertEquals(2, countOf(new WfProcessInstanceQuery().setPageNum(1).setPageSize(10)),
                "**total 不能跟着 pageSize 变** —— 前端分页器会以为只有一页");
    }

    @Test
    @DisplayName("扫描超过上限 ⇒ 报错说清怎么缩小范围，**不返回一份截断的清单**")
    void oversizedScanIsRejected() {
        // **用可配的上限造溢出**，而不是真起两万多单 ——
        // 那道闸门是这条路上最要紧的一条，用两分钟去证明它会报错不划算
        WfProcessQueryService service = new WfProcessQueryService(repo, 2);
        startAmount("o-1", 100);
        startAmount("o-2", 100);
        startAmount("o-3", 100);

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> service.list(new WfProcessInstanceQuery()
                        .setVariableName("amount").setPageSize(1)),
                "给一份缺了行的清单比报错更坏 —— 它看起来是完整的");
        assertTrue(ex.getMessage().contains("缩小范围"),
                "报错要直接告诉人怎么办。实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("溢出判断按**读到的原始行数**，不是过滤后的条数")
    void overflowUsesRawRowCountNotMatchedCount() {
        // 上限 5、共 6 单，但只有 2 单命中变量条件。
        // 拿过滤后的 2 去比上限 5 就判不出溢出 ⇒ 悄悄返回一份截断清单
        WfProcessQueryService service = new WfProcessQueryService(repo, 5);
        for (int i = 0; i < 6; i++) {
            startAmount("r-" + i, i < 2 ? 100 : 1);
        }

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> service.count(new WfProcessInstanceQuery()
                        .setVariableValueGreaterThan("amount", 50)));
        assertTrue(ex.getMessage().contains("缩小范围"),
                "实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("**恰好等于上限不算超量**，上限 +1 才报（两条配对锁住边界）")
    void exactlyAtLimitIsNotOverflow() {
        // 上限 3、单数 3 ⇒ 全部读到了，**必须能正常返回**。
        // scan() 读的是 maxScan+1 行再用 `>` 去判，所以 ">=" 是错的：
        // 它会让「正好读满」也报溢出，而报错文案说的是"超过 3 条"——
        // 一条对不上的报错比不报错更难查。
        WfProcessQueryService service = new WfProcessQueryService(repo, 3);
        startAmount("at-1", 100);
        startAmount("at-2", 100);
        startAmount("at-3", 100);

        assertEquals(3, service.count(new WfProcessInstanceQuery().setVariableName("amount")),
                "**单数正好等于上限时要给出答案，不是报错** —— "
                        + "报错文案写着「超过 3 条」，而实际是 3 条，"
                        + "一条对不上的报错比不报错更难查。");
        assertEquals(3, service.list(new WfProcessInstanceQuery()
                        .setVariableName("amount").setPageSize(10)).size());

        // 再加一单 ⇒ 同一把尺子就该报
        startAmount("at-4", 100);
        assertThrows(WfEngineException.class,
                () -> service.count(new WfProcessInstanceQuery().setVariableName("amount")),
                "超出一条就该报。边界判据成对写，少任何一条都钉不住 `>` 还是 `>=`");
    }

    @Test
    @DisplayName("区间下界大于上界直接拒绝，**不静默交换两端**")
    void reversedRangeIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new WfProcessInstanceQuery().setVariableValueBetween("amount", 5000, 1000));
        assertTrue(ex.getMessage().contains("下界"),
                "报错要点名是哪个参数出了问题。实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("先设比较再设存在性 ⇒ 前一个被清干净（互斥单值模型）")
    void eachSetterReplacesThePrevious() {
        startAmount("m-only", 100);
        startAmount("m-both", 1);

        WfProcessInstanceQuery query = new WfProcessInstanceQuery()
                .setVariableValueGreaterThan("amount", 5000)
                .setVariableName("amount");
        assertEquals(2, businessKeys(query).size(),
                "两个 setter 叠在一起必然有一个被静默丢掉 —— "
                        + "这里的结果应当是「存在性」，也就是 2 条（命中与否都算）。实际: "
                        + businessKeys(query));
        assertEquals(null, query.getComparison(),
                "存在性那一支必须把比较方式一并清掉，不能留个比较方式残骸");
    }
}