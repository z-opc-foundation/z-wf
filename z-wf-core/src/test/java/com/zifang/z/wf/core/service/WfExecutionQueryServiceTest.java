package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.JdbcWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfExecutionQuery;
import com.zifang.z.wf.core.persistence.WfPersistence;

/**
 * 执行令牌的条件查询。
 *
 * <p>这组用例盯的是四件"看起来对、其实不对"的事：
 * <ol>
 *   <li><b>两套存储给出不同的答案</b>。下游过滤在 Java 里做，排序也在 Java 里做，
 *       所以服务层天然只有一份实现；真正会分家的是<b>下推的那几项</b> ——
 *       内存侧漏接一个 {@code states} 条件时，JDBC 侧会筛、内存侧不会，
 *       而开发期默认用内存实现，问题到生产才暴露。</li>
 *   <li><b>翻页翻不动</b>。分页参数一度被服务层就地改掉（读数据时为了"先读够再过滤"
 *       把 pageSize 调大），改的是调用方传进来的那个对象 —— 结果第二页与第一页一样，
 *       而那看起来像"数据只有一页"。</li>
 *   <li><b>变量条件的键不存在被当成值为 null</b>：一条没有 {@code approveFlag} 的
 *       令牌会被 {@code approveFlag=null} 这个条件命中。</li>
 *   <li><b>排序里 {@code enteredTime} 为空的行</b>（手工写入 / 脏数据 / 外部导入）。
 *       它必须<b>稳定地排最后</b>，否则翻页会在它身上重复或漏掉。</li>
 * </ol>
 */
class WfExecutionQueryServiceTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static final String PAR_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"qPar\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <parallelGateway id=\"pg\"/>\n"
            + "    <userTask id=\"a1\" name=\"甲\" zifang:assignee=\"alice\"/>\n"
            + "    <userTask id=\"b1\" name=\"乙\" zifang:assignee=\"bob\"/>\n"
            + "    <parallelGateway id=\"jg\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"pg\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"pg\" targetRef=\"a1\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"pg\" targetRef=\"b1\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"a1\" targetRef=\"jg\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"b1\" targetRef=\"jg\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"jg\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    // ==================== 下推的条件 ====================

    @Test
    @DisplayName("按流程实例 / 节点 / 状态查，两套存储给出同一批令牌")
    void pushedDownFiltersAgreeAcrossImplementations() {
        java.util.Map<String, List<String>> byImplementation = new java.util.LinkedHashMap<>();
        for (WfPersistence persistence : new WfPersistence[]{memory(), jdbc()}) {
            WfExecutionQueryService service = new WfExecutionQueryService(persistence);
            String first = startParallel(persistence, "PUSH-1");
            startParallel(persistence, "PUSH-2");

            assertEquals(2, service.listExecutions(
                    new WfExecutionQuery().setProcessInstanceId(first)).size(),
                    "一条并行流程应有两条活跃令牌");
            assertEquals(0, service.listExecutions(
                    new WfExecutionQuery().setProcessInstanceId("proc-不存在")).size());

            // 按节点跨实例查 —— 这正是 getExecutions(processInstanceId) 答不了的那一问：
            // 「哪个单子卡在「乙」这个节点上」，而此刻还不知道单子的 id
            List<WfExecution> atB1 = service.listExecutions(
                    new WfExecutionQuery().setActivityId("b1"));
            assertEquals(2, atB1.size(),
                    "两个实例各有一条令牌停在 b1；查不到就是下推漏了 ACTIVITY_ID 条件");
            assertTrue(idsOf(atB1).stream().allMatch(id -> id.startsWith("b1#")),
                    "按 b1 查出来的却混进了别的节点 —— ACTIVITY_ID 条件没接上："
                            + idsOf(atB1));

            assertEquals(2, service.countExecutions(new WfExecutionQuery().setActivityId("b1")));
            assertEquals(4, service.countExecutions(new WfExecutionQuery()),
                    "两个实例共四条活跃令牌");
            byImplementation.put(persistenceKindOf(persistence), shapeOf(service.listExecutions(
                    new WfExecutionQuery().setPageSize(500))));
        }
        // 两套存储对同一批数据必须给出同一批令牌 ——
        // 下游的过滤与排序在服务层只有一份，真正会分家的是下推的那几项，
        // 而开发期默认用内存实现，分家要到生产才暴露。
        assertEquals(byImplementation.get("memory"), byImplementation.get("jdbc"),
                "两套存储对同一批数据给出了不同的令牌：" + byImplementation);
    }

    @Test
    @DisplayName("状态条件只认未结束的；已结束的令牌不该混进「这单子停在哪」")
    void stateFilterExcludesEnded() {
        for (WfPersistence persistence : new WfPersistence[]{memory(), jdbc()}) {
            WfExecutionQueryService service = new WfExecutionQueryService(persistence);
            String pid = startParallel(persistence, "STATE-1");
            assertEquals(2, service.listExecutions(new WfExecutionQuery()
                    .setProcessInstanceId(pid).onlyUnfinished()).size());

            WfExecution ended = new WfExecution("exe-done-" + COUNTER.incrementAndGet(),
                    pid, null);
            ended.setActivityId("b1");
            ended.setState(WfExecution.State.ENDED);
            ended.setEnteredTime(new Date(1000L));
            persistence.saveExecution(ended);

            assertEquals(2, service.listExecutions(new WfExecutionQuery()
                    .setProcessInstanceId(pid).onlyUnfinished()).size(),
                    "已结束的令牌被算进「没结束」了 —— 状态条件没接上");
            assertEquals(3, service.listExecutions(new WfExecutionQuery()
                    .setProcessInstanceId(pid)).size(), "不筛状态时三条都要");
            assertEquals(1, service.listExecutions(new WfExecutionQuery()
                    .setProcessInstanceId(pid).addState(WfExecution.State.ENDED)).size(),
                    "只查已结束时应当只得到那一条 —— states 没下推就会连 ACTIVE 的也一起捞出来");
        }
    }

    // ==================== 变量条件 ====================

    @Test
    @DisplayName("按令牌上的变量查 —— 键不存在不等于「值为 null」")
    void variableFilterTreatsMissingKeyAsNotMatching() {
        for (WfPersistence persistence : new WfPersistence[]{memory(), jdbc()}) {
            WfExecutionQueryService service = new WfExecutionQueryService(persistence);
            String pid = startParallel(persistence, "VAR-1");
            WfExecution target = firstUnfinished(service, pid, "a1");
            target.getVariables().put("branch", "urgent");
            persistence.saveExecution(target);

            WfExecution other = firstUnfinished(service, pid, "b1");
            // other 没有 branch 这个键 —— 它必须**不**被 "branch=null" 这个条件命中

            assertEquals(1, service.listExecutions(new WfExecutionQuery()
                    .setVariableName("branch").setVariableValueEquals("urgent")).size(),
                    "变量条件没生效或两套实现口径不同");
            assertEquals(0, service.listExecutions(new WfExecutionQuery()
                    .setVariableName("branch").setVariableValueEquals("normal")).size());
            // 这一条是本用例的核心：containsKey 短路漏掉时它会变成 1
            assertEquals(0, service.listExecutions(new WfExecutionQuery()
                    .setVariableName("branch").setVariableValueEquals("null")).size(),
                    "没带 branch 键的令牌被「branch=null」命中了 —— "
                            + "键不存在与值为 null 是两回事，前者是这条令牌压根没这个变量");
            assertNotNull(other);
        }
    }

    @Test
    @DisplayName("count 与 list 口径一致（含变量条件），不能一个 3 条一个 8 条")
    void countAgreesWithList() {
        for (WfPersistence persistence : new WfPersistence[]{memory(), jdbc()}) {
            WfExecutionQueryService service = new WfExecutionQueryService(persistence);
            startParallel(persistence, "COUNT-1");
            WfExecutionQuery query = new WfExecutionQuery()
                    .setActivityId("a1")
                    .setVariableName("branch")
                    .setVariableValueEquals("urgent");
            assertEquals(0, service.countExecutions(query));

            for (WfExecution execution : persistence.queryExecutions(
                    new WfExecutionQuery().setActivityId("a1"))) {
                execution.getVariables().put("branch", "urgent");
                persistence.saveExecution(execution);
            }
            assertEquals(service.listExecutions(query).size(), service.countExecutions(query),
                    "count 走了另一条路（漏掉变量过滤），调用方会以为是自己算错了");
        }
    }

    // ==================== 翻页与排序 ====================

    @Test
    @DisplayName("翻页真的能翻到下一页，且不重不漏")
    void pagingActuallyPages() {
        for (WfPersistence persistence : new WfPersistence[]{memory(), jdbc()}) {
            WfExecutionQueryService service = new WfExecutionQueryService(persistence);
            startParallel(persistence, "PAGE-1");
            startParallel(persistence, "PAGE-2");
            List<String> all = idsOf(service.listExecutions(
                    new WfExecutionQuery().setPageSize(500)));
            // 夹具前提：数据非空。少了这一条，下面所有断言都会在空列表上空转 ——
            // 两个空列表是相等的，「第二页等于第一页」也就恒成立
            assertEquals(4, all.size(), "两个并行实例共四条令牌；为空说明夹具没造上数据");

            List<String> paged = new ArrayList<>();
            WfExecutionQuery reusable = new WfExecutionQuery().setPageSize(2);
            for (int page = 1; page <= 2; page++) {
                List<String> onPage = idsOf(service.listExecutions(reusable.setPageNum(page)));
                assertEquals(2, onPage.size(), "第 " + page + " 页应当有两条");
                paged.addAll(onPage);
            }
            assertEquals(all, paged,
                    "两页拼起来与一次性取全部必须完全一致 —— "
                            + "翻页参数被服务层就地改掉的话，这里会是「第二页等于第一页」");

            // 越界页返回空，而不是把第一页再给一遍
            assertEquals(0, service.listExecutions(reusable.setPageNum(99)).size());
            // 同一个 query 对象反复用不会互相污染
            assertEquals(2, service.listExecutions(reusable.setPageNum(1)).size());
        }
    }

    @Test
    @DisplayName("按进入时间倒序，没时间的排最后 —— 两套存储顺序必须一致")
    void orderingIsStableAndAgreesAcrossImplementations() {
        for (WfPersistence persistence : new WfPersistence[]{memory(), jdbc()}) {
            WfExecutionQueryService service = new WfExecutionQueryService(persistence);
            // **刻意不启动流程**：排序要用可控的时间戳断，
            // 而引擎自己建的令牌带的是 now，与手工造的时间戳没法比大小
            // （第一版把两者混在一起断，拿到的是 b1 而不是 c —— 断的不是排序）
            String pid = "proc-order-only";
            for (String node : new String[]{"a", "b", "c"}) {
                WfExecution stamped = new WfExecution(
                        "exe-stamp-" + node + "-" + COUNTER.incrementAndGet(), pid, null);
                stamped.setActivityId(node);
                // c 最新、a 最旧；与插入顺序一致，排序必须真的做功
                stamped.setEnteredTime(new Date(3000L - "cba".indexOf(node) * 1000L));
                persistence.saveExecution(stamped);
            }
            // 再造两条没有 enteredTime 的：模拟"从别处灌进来的脏数据"。
            // **必须显式 setEnteredTime(null)** —— WfExecution 的三参构造器
            // 里有一句 this.enteredTime = new Date()，不显式置空造出来的
            // 是"刚刚进入的令牌"，而那种数据照样有 enteredTime，
            // 于是断言平凡通过、什么也没测到（第一版就栽在这里）。
            for (int i = 0; i < 2; i++) {
                WfExecution dirty = new WfExecution("exe-dirty-" + COUNTER.incrementAndGet(),
                        pid, null);
                dirty.setActivityId("dirty");
                dirty.setEnteredTime(null);
                persistence.saveExecution(dirty);
            }

            List<WfExecution> ordered = service.listExecutions(
                    new WfExecutionQuery().setProcessInstanceId(pid).setPageSize(500));
            assertEquals(5, ordered.size(), "手工造的令牌应当都在。实际: " + activityIdsOf(ordered));
            // 真正断「按进入时间倒序」的那一段。
            // **这一段第一版是断不到的**：当时只造了引擎自己建的 a1 / b1，
            // 而这两条的时间戳落在同一毫秒 ⇒ 比较器走 id 兜底，
            // 加上原始顺序恰好已经是「非 null 在前」，排序**根本没做功**，
            // 于是「前两条非 null」平凡成立 —— 判据因错误的原因通过。
            assertEquals("c", ordered.get(0).getActivityId());
            assertEquals("b", ordered.get(1).getActivityId());
            assertEquals("a", ordered.get(2).getActivityId());
            for (int i = 3; i < 5; i++) {
                assertNull(ordered.get(i).getEnteredTime(),
                        "没进入时间的令牌排到了第 " + i + " 位 —— 排序里 null 的处理变了，"
                                + "而翻页会因此在它们身上重复或漏掉。实际顺序: " + activityIdsOf(ordered));
            }
        }
    }

    // ==================== 上限 ====================

    @Test
    @DisplayName("页大小归一：小于 1 当 50，超过 1000 收窄 —— 护栏不能被悄悄拆掉")
    void pageSizeIsNormalized() {
        assertEquals(50, new WfExecutionQuery().setPageSize(0).normalizedPageSize());
        assertEquals(50, new WfExecutionQuery().setPageSize(-5).normalizedPageSize());
        assertEquals(1000, new WfExecutionQuery().setPageSize(1000000).normalizedPageSize(),
                "调用方拼错 pageSize 时不该把整张令牌表拉进内存 —— "
                        + "这一条与「扫描上限」不是一回事：页大小是调用方**看得见**的参数"
                                + "（会体现在返回条数上），收窄是安全的；"
                                + "而扫描上限那种看不见的截断必须报错");
        assertEquals(1, new WfExecutionQuery().setPageNum(0).normalizedPageNum());
        assertEquals(3, new WfExecutionQuery().setPageNum(3).normalizedPageNum());
    }

    @Test
    @DisplayName("扫描到超过上限的量就报错，不给一份看起来完整的截断清单")
    void tooManyMatchesFailsLoudly() {
        WfPersistence persistence = memory();
        WfExecutionQueryService service = new WfExecutionQueryService(persistence);
        for (int i = 0; i <= WfExecutionQueryService.MAX_SCAN; i++) {
            WfExecution execution = new WfExecution("exe-bulk-" + i, "proc-bulk", null);
            execution.setActivityId("bulk");
            execution.setEnteredTime(new Date(1000L + i));
            persistence.saveExecution(execution);
        }
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> service.listExecutions(new WfExecutionQuery().setActivityId("bulk")),
                "超过上限却不报错，等于给出一份看起来完整的截断清单 —— "
                        + "调用方会据此得出「就这些有问题」");
        assertTrue(ex.getMessage().contains(String.valueOf(WfExecutionQueryService.MAX_SCAN)),
                "报错要说清上限是多少: " + ex.getMessage());
    }

    // ==================== 夹具 ====================

    /**
     * 令牌集合的「形状」：按实例分组、每组内的 activityId 排序。
     *
     * <p>刻意<b>不比实例 id</b>：两套存储各跑一遍时，实例 id 由各自的
     * {@code WfIdGenerator} 生成（前缀里带进程随机数），压根不可能相等 ——
     * 直接比 id 只能得到一个恒红的断言。
     * 而"两条并行分支分别停在 a1 与 b1"这件事在两套实现上必须是同一个答案。
     */
    private List<String> shapeOf(List<WfExecution> executions) {
        java.util.Map<String, List<String>> byInstance = new java.util.TreeMap<>();
        for (WfExecution execution : executions) {
            List<String> nodes = byInstance.get(execution.getProcessInstanceId());
            if (nodes == null) {
                nodes = new ArrayList<>();
                byInstance.put(execution.getProcessInstanceId(), nodes);
            }
            nodes.add(execution.getActivityId());
        }
        List<String> shapes = new ArrayList<>();
        for (List<String> nodes : byInstance.values()) {
            java.util.Collections.sort(nodes);
            shapes.add(String.join(",", nodes));
        }
        java.util.Collections.sort(shapes);
        return shapes;
    }

    private List<String> activityIdsOf(List<WfExecution> executions) {
        List<String> ids = new ArrayList<>();
        for (WfExecution execution : executions) {
            ids.add(execution.getActivityId());
        }
        return ids;
    }

    private String persistenceKindOf(WfPersistence persistence) {
        return persistence instanceof JdbcWorkflowPersistence ? "jdbc" : "memory";
    }

    private List<String> idsOf(List<WfExecution> executions) {
        List<String> ids = new ArrayList<>();
        for (WfExecution execution : executions) {
            ids.add(execution.getActivityId() + "#" + execution.getProcessInstanceId());
        }
        return ids;
    }

    private WfExecution firstUnfinished(WfExecutionQueryService service, String pid,
                                        String activityId) {
        for (WfExecution execution : service.listExecutions(new WfExecutionQuery()
                .setProcessInstanceId(pid).onlyUnfinished().setPageSize(50))) {
            if (activityId.equals(execution.getActivityId())) {
                return execution;
            }
        }
        throw new IllegalStateException("流程 " + pid + " 上找不到停在 " + activityId + " 的令牌");
    }

    private WfPersistence memory() {
        InMemoryWorkflowPersistence repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        return repo;
    }

    private WfPersistence jdbc() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:wf_execq_" + COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        JdbcWorkflowPersistence repo = new JdbcWorkflowPersistence(ds);
        repo.initialize();
        return repo;
    }

    private String startParallel(WfPersistence persistence, String businessKey) {
        WfRepositoryService repository = new WfRepositoryService(persistence);
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(PAR_BPMN));
        WfRuntimeService runtime = new WfRuntimeService(repository, persistence,
                new WfEngine(), new WfHookDispatcher());
        return runtime.startProcessInstance(definition, businessKey, null, null,
                new HashMap<String, Object>());
    }
}
