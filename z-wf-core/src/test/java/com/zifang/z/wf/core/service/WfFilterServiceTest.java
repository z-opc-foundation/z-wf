package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfFilter;
import com.zifang.z.wf.core.model.WfFilterType;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfFilterQuery;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfOptimisticLockException;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.view.WfFilterResult;

/**
 * 保存筛选器 —— 把一组查询条件存起来，反复用、大家共用。
 *
 * <p>本类盯六件，其中前两件是<b>这套功能最容易"看着能用、其实不生效"</b>的地方：
 * <ol>
 *   <li><b>存不进去的条件要当场报错</b>。条件拼错一个字段而被当成没填，
 *       筛出来的是一份看起来完全正常的全量清单 —— 少掉的那部分永远不会有人来报。</li>
 *   <li><b>值按字符串严格解析</b>：{@code openOnly=yes} 不等于 true。
 *       宽松解析在这里的代价是"打错一个字条件就悄悄变了"，
 *       而筛选器是共享资源，悄悄变了之后没人知道。</li>
 *   <li><b>保存与执行走同一条绑定路径</b>：存时合法、跑时不合法这种组合不该存在。
 *       本类用一个"存得进去"当前提去验"跑得起来"，验的正是同一条路径。</li>
 *   <li><b>类型不可混用</b>：{@code status} 在任务查询与实例查询里是两组不同的枚举，
 *       套过去只会查出没人认得的清单。</li>
 *   <li><b>并发改要报冲突</b>：共享资源 + 乐观锁。这里最关键的是
 *       <b>服务层不许悄悄用"库里现在这份"版本号覆盖调用方传进来的</b> ——
 *       那样写乐观锁永远不会冲突，看上去更安全，实际是彻底没有保护。</li>
 *   <li><b>删不存在的要报错</b>：删东西的语义是"保证它没了"，
 *       而"因为 id 写错了所以什么都没删"会被读成"已经删过了"。</li>
 * </ol>
 */
class WfFilterServiceTest {

    private static final String SIMPLE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"fltProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"alice\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfFilterService filters;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
        filters = new WfFilterService(repo,
                new WfIncidentService(repo, repository));
    }

    private String start() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(SIMPLE_BPMN));
        return runtime.startProcessInstance(definition, "FLT-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
    }

    private Map<String, String> props(String... pairs) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    // ==================== 增删改查 ====================

    @Test
    @DisplayName("新建 → 读回 → 改 → 删，条件原样往返")
    void crudRoundTrip() {
        WfFilter created = filters.createFilter("我的长期待办", WfFilterType.TASK, "alice",
                props("assignee", "alice", "openOnly", "true"));

        WfFilter loaded = filters.getFilter(created.getId());
        assertEquals("我的长期待办", loaded.getName());
        assertEquals(WfFilterType.TASK, loaded.getResourceType());
        assertEquals("alice", loaded.getOwner());
        assertEquals("alice", loaded.getProperty("assignee"));
        assertEquals("true", loaded.getProperty("openOnly"));
        assertNotNull(loaded.getCreateTime());
        assertEquals(0, loaded.getRevision(), "新建出来的版本号必须是 0");

        loaded.setName("我的待办（改）");
        loaded.putProperty("category", "finance");
        filters.updateFilter(loaded);

        WfFilter updated = filters.getFilter(created.getId());
        assertEquals("我的待办（改）", updated.getName());
        assertEquals("finance", updated.getProperty("category"));
        assertEquals("alice", updated.getProperty("assignee"), "改一条不该动到别的");
        assertEquals(1, updated.getRevision(), "改一次版本号 +1");

        filters.deleteFilter(created.getId());
        assertThrows(WfEngineException.class, () -> filters.getFilter(created.getId()),
                "删完就该读不到");
    }

    @Test
    @DisplayName("存进去的条件必须真能绑成查询 —— 保存与执行走同一条路径")
    void savedFilterIsUsable() {
        WfFilter created = filters.createFilter("大额单", WfFilterType.PROCESS_INSTANCE, "ops",
                props("definitionKey", "fltProcess", "finishedOnly", "false"));
        WfProcessInstanceQueryBound bound = new WfProcessInstanceQueryBound(
                filters.processInstanceQuery(created.getId()));
        assertEquals("fltProcess", bound.definitionKey);
        assertFalse(bound.finishedOnly, "finishedOnly=false 必须被解析成 false，"
                + "而不是被当成没填 —— 那会让「在途的单」这条筛选器查出全部单子");
    }

    /** 只为让上面那条断言读起来清楚一点。 */
    private static class WfProcessInstanceQueryBound {
        final String definitionKey;
        final boolean finishedOnly;

        WfProcessInstanceQueryBound(com.zifang.z.wf.core.persistence.WfProcessInstanceQuery query) {
            this.definitionKey = query.getDefinitionKey();
            this.finishedOnly = query.isFinishedOnly();
        }
    }

    // ==================== 值严格解析 ====================

    @Test
    @DisplayName("布尔只认 true / false：yes、1、Y 一律报错并说明")
    void booleansAreStrict() {
        for (String bad : new String[] {"yes", "1", "Y", "on", "TRUE ", ""}) {
            if (bad.trim().isEmpty()) {
                continue;
            }
            WfEngineException ex = assertThrows(WfEngineException.class,
                    () -> filters.createFilter("x", WfFilterType.TASK, "ops",
                            props("openOnly", bad)),
                    "openOnly=" + bad + " 不该被接受");
            assertTrue(ex.getMessage().contains("true") && ex.getMessage().contains("false"),
                    "报错要说清只认什么。实际 " + ex.getMessage());
        }
        // 大小写不敏感是对的：手输 TRUE 不该被当成手误
        assertEquals(Boolean.TRUE, filters.taskQuery(filters.createFilter("a",
                WfFilterType.TASK, "ops", props("openOnly", "TRUE")).getId()).isOpenOnly());
    }

    @Test
    @DisplayName("条件名拼错要报错，并列出该类型全部合法条件名")
    void unknownKeyIsRejected() {
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> filters.createFilter("x", WfFilterType.TASK, "ops",
                        props("assigne", "alice")));
        assertTrue(ex.getMessage().contains("assigne"), "报错要点名是哪个条件: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("assignee"),
                "报错要给出正确拼写。实际 " + ex.getMessage());
        // **各类型的合法条件名不同**才是这条报错的价值所在：
        // 用实例查询的条件名去存一张任务筛选器，报错里就不该出现它
        assertFalse(ex.getMessage().contains("startUserId"),
                "任务筛选器不该被告知实例查询的条件名: " + ex.getMessage());
    }

    @Test
    @DisplayName("枚举 / 整数 / 时间戳解析不了都要报错")
    void enumIntAndTimeAreStrict() {
        assertTrue(assertThrows(WfEngineException.class,
                () -> filters.createFilter("x", WfFilterType.TASK, "ops",
                        props("status", "FINISHED")))
                .getMessage().contains("COMPLETED"),
                "status 要列出合法的任务状态（COMPLETED 才是任务那个含义）");
        // 同一个名字在实例查询里是另一组值 —— 这正是不许跨类型混用的原因。
        // 用 ASSIGNED（只有任务状态里才有）来证：它在任务筛选器里合法、在实例筛选器里不合法
        WfFilter taskWithAssigned = filters.createFilter("合法", WfFilterType.TASK, "ops",
                props("status", "ASSIGNED"));
        assertNotNull(taskWithAssigned, "ASSIGNED 是合法的任务状态");
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> filters.createFilter("x", WfFilterType.PROCESS_INSTANCE, "ops",
                        props("status", "ASSIGNED")));
        // 判据要看**列出的合法值列表本身**，不能只看"消息里有没有某个词" ——
        // 消息本来就会把写错的那个值原样回显，contains 一下永远为真
        assertTrue(ex.getMessage().contains("期望: [ACTIVE"),
                "要列出实例自己的那组状态（第一个是 ACTIVE），而不是任务那组: " + ex.getMessage());
        // 对照：同一类错误在任务筛选器上，列出来的第一个是 CREATED
        assertTrue(assertThrows(WfEngineException.class,
                () -> filters.createFilter("x", WfFilterType.TASK, "ops",
                        props("status", "ACTIVE")))
                .getMessage().contains("期望: [CREATED"),
                "任务那组的第一个是 CREATED —— 两个列表必须真的不同，"
                        + "否则「类型不许混用」就只剩一句口号");

        assertTrue(assertThrows(WfEngineException.class,
                () -> filters.createFilter("x", WfFilterType.PROCESS_INSTANCE, "ops",
                        props("definitionVersion", "v3")))
                .getMessage().contains("整数"), "版本号要整数");
        assertTrue(assertThrows(WfEngineException.class,
                () -> filters.createFilter("x", WfFilterType.TASK, "ops",
                        props("createTimeFrom", "2026-01-01")))
                .getMessage().contains("毫秒时间戳"),
                "日期字符串在不同机器上按本地时区解释，同一张筛选器换个环境就查出一批不同的单");
        assertTrue(assertThrows(WfEngineException.class,
                () -> filters.createFilter("x", WfFilterType.INCIDENT, "ops",
                        props("types", "MESSAGE,NOT_A_TYPE")))
                .getMessage().contains("MESSAGE"), "types 要列出合法的 job 类型");
    }

    // ==================== 类型隔离 ====================

    @Test
    @DisplayName("类型不许混用：拿任务筛选器去当故障查询用要报错并说清它是什么类型")
    void typesAreNotInterchangeable() {
        WfFilter taskFilter = filters.createFilter("我的待办", WfFilterType.TASK, "ops",
                props("assignee", "alice"));

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> filters.incidentQuery(taskFilter.getId()));
        assertTrue(ex.getMessage().contains("我的待办") && ex.getMessage().contains("task"),
                "报错要点名这张筛选器是什么类型，否则调用方不知道自己拿错了什么: " + ex.getMessage());
        // 条件名不通用：types 只在故障查询里合法
        assertTrue(assertThrows(WfEngineException.class,
                () -> filters.createFilter("x", WfFilterType.TASK, "ops", props("types", "MESSAGE")))
                .getMessage().contains("types"), "任务筛选器不认 types");
    }

    @Test
    @DisplayName("改筛选器不许改类型 —— 改了等于让所有引用它的地方悄悄换掉查什么")
    void resourceTypeIsImmutable() {
        WfFilter created = filters.createFilter("x", WfFilterType.TASK, "ops",
                props("assignee", "alice"));
        WfFilter loaded = filters.getFilter(created.getId());
        loaded.setResourceType(WfFilterType.INCIDENT);
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> filters.updateFilter(loaded));
        assertTrue(ex.getMessage().contains("不能改"), ex.getMessage());
        assertEquals(WfFilterType.TASK, filters.getFilter(created.getId()).getResourceType());
    }

    // ==================== 并发改 ====================

    @Test
    @DisplayName("两个人同时改同一张筛选器：后到的那个要撞乐观锁")
    void concurrentUpdateConflicts() {
        WfFilter created = filters.createFilter("x", WfFilterType.TASK, "ops",
                props("assignee", "alice"));
        WfFilter first = filters.getFilter(created.getId());
        WfFilter second = filters.getFilter(created.getId());

        first.setName("先改的");
        filters.updateFilter(first);

        // second 是改之前读的那份，它的版本号已经过期
        second.setName("后改的");
        assertThrows(WfOptimisticLockException.class, () -> filters.updateFilter(second),
                "覆盖掉了先改的那个人 —— 而先改的人会读成「我明明改了却没生效」");
        assertEquals("先改的", filters.getFilter(created.getId()).getName());
    }

    @Test
    @DisplayName("版本号由服务层归一：调用方自己调过 nextRevision 也不会跳版本")
    void revisionIsNormalizedByService() {
        WfFilter created = filters.createFilter("x", WfFilterType.TASK, "ops",
                props("assignee", "alice"));
        WfFilter loaded = filters.getFilter(created.getId());
        // 调用方按 WfTask 的习惯自己先自增了一次
        loaded.nextRevision();
        loaded.setName("改过的");
        filters.updateFilter(loaded);
        assertEquals(1, filters.getFilter(created.getId()).getRevision(),
                "跳到 2 的话，下一个改的人会撞上一个莫名其妙的冲突");
    }

    // ==================== 拒绝 ====================

    @Test
    @DisplayName("不存在的筛选器：读 / 改 / 删 / 跑都报错，且报错要点名 id")
    void missingFilterIsRejected() {
        for (Runnable each : new Runnable[] {
                () -> filters.getFilter("no-such"),
                () -> filters.run("no-such", 1, 10),
                () -> filters.taskQuery("no-such"),
                () -> filters.deleteFilter("no-such")}) {
            assertTrue(assertThrows(WfEngineException.class, each::run)
                    .getMessage().contains("no-such"),
                    "报错要点名 id，否则调用方不知道是自己写错了还是被谁删了");
        }
        // 改不存在的要单独说：那条路径要先判"不存在"再判"版本对不上"
        WfFilter ghost = WfFilter.of("no-such", "x", WfFilterType.TASK, "ops");
        assertTrue(assertThrows(WfEngineException.class, () -> filters.updateFilter(ghost))
                .getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("名字为空要报错；名字是这张筛选器在列表里给人挑的那个")
    void blankNameIsRejected() {
        assertTrue(assertThrows(WfEngineException.class,
                () -> filters.createFilter("  ", WfFilterType.TASK, "ops", null))
                .getMessage().contains("不能为空"));
    }

    @Test
    @DisplayName("删不存在的要报错，不静默返回成功")
    void deletingMissingFilterFails() {
        // 静默返回的话，"因为 id 写错了所以什么都没删"会被读成"已经删过了"，
        // 而那个 id 上可能还挂着一张正被别人用的筛选器
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> filters.deleteFilter("no-such"));
        assertTrue(ex.getMessage().contains("no-such"), ex.getMessage());
    }

    // ==================== 执行 ====================

    @Test
    @DisplayName("跑一张任务筛选器：结果条数与条件真的对上")
    void runTaskFilter() {
        String mine = start();
        start();
        // 把别人那张改成不是 alice 的办理人，制造一个「本该被排除掉的对象」
        List<WfTask> open = repo.queryTasks(new WfTaskQuery().setOpenOnly(true)
                .setPageNum(1).setPageSize(20));
        assertEquals(2, open.size(), "前置条件：两张待办");
        WfTask notMine = null;
        for (WfTask task : open) {
            if (!mine.equals(task.getProcessInstanceId())) {
                notMine = task;
            }
        }
        assertNotNull(notMine);
        notMine.setAssignee("bob");
        notMine.nextRevision();
        repo.saveTask(notMine);

        WfFilter filter = filters.createFilter("我的待办", WfFilterType.TASK, "alice",
                props("assignee", "alice", "openOnly", "true"));
        WfFilterResult result = filters.run(filter.getId(), 1, 20);

        assertEquals("task", result.getResourceType());
        assertEquals(1, result.getTotal(), "只该有我那一张 —— bob 那张就是被排除掉的对象");
        assertEquals(1, result.getRecords().size());
        WfTask record = (WfTask) result.getRecords().get(0);
        assertEquals("alice", record.getAssignee());
        assertEquals(mine, record.getProcessInstanceId(),
                "必须真的被筛过 —— 漏筛与筛对的结果集在只有一个待办时长得一样");
    }

    @Test
    @DisplayName("跑一张实例筛选器：total 是匹配总数，不是当前页条数")
    void runInstanceFilterPages() {
        start();
        start();
        WfFilter filter = filters.createFilter("在途的单", WfFilterType.PROCESS_INSTANCE, "ops",
                props("definitionKey", "fltProcess", "unfinishedOnly", "true"));

        WfFilterResult firstPage = filters.run(filter.getId(), 1, 1);
        assertEquals("processInstance", firstPage.getResourceType());
        assertEquals(1, firstPage.getRecords().size(), "每页 1 条");
        assertEquals(2, firstPage.getTotal(), "total 是匹配总数 —— 拿页大小当总数会让分页器算不出还有几页");
        assertEquals(1, filters.run(filter.getId(), 2, 1).getRecords().size());
    }

    @Test
    @DisplayName("跑一张故障筛选器：结果类型是 incident，条件真的生效")
    void runIncidentFilter() {
        String pid = start();
        WfJob job = new WfJob();
        job.setId("flt-job-1");
        job.setProcessInstanceId(pid);
        job.setElementId("approve");
        job.setType(WfJobType.TIMER);
        job.recordFailure("IllegalStateException: 炸了");
        job.nextRevision();
        repo.saveJob(job);
        // 另一个本该被排除掉的对象：没失败过的 job
        WfJob healthy = new WfJob();
        healthy.setId("flt-job-2");
        healthy.setProcessInstanceId(pid);
        healthy.setElementId("approve");
        healthy.setType(WfJobType.TIMER);
        healthy.nextRevision();
        repo.saveJob(healthy);

        // 探针实测：默认 retries=3，失败一次后 retries=2，属于「还在重试」，
        // 所以 retriesExhausted=true（彻底不动了）正确地一条都不给。
        // 这条用例真正要验的是**极性** —— 这个布尔在 WfIncidentService 里
        // 曾经被写成拿参数与 isRetryable() 直接比大小，两侧取反，
        // 于是筛「还在重试」时一条都查不出来。走筛选器验它才有意义：
        // 绑反了两个方向的断言会同时变红
        WfFilter retrying = filters.createFilter("还在重试", WfFilterType.INCIDENT, "ops",
                props("retriesExhausted", "false"));
        WfFilterResult result = filters.run(retrying.getId(), 1, 20);
        assertEquals("incident", result.getResourceType());
        assertEquals(1, result.getTotal(),
                "只失败过一次的 job 属于「还在重试」；从没失败过的那条根本不是故障");

        WfFilter stuck = filters.createFilter("彻底不动了", WfFilterType.INCIDENT, "ops",
                props("retriesExhausted", "true"));
        assertEquals(0, filters.run(stuck.getId(), 1, 20).getTotal(),
                "还没重试耗尽的就不是「彻底不动了」");
    }

    // ==================== 列出筛选器 ====================

    @Test
    @DisplayName("列筛选器：每个过滤条件都真的会剔掉东西，count 与 list 一致")
    void listFiltersActuallyExclude() {
        WfFilter taskOfAlice = filters.createFilter("alice 的待办", WfFilterType.TASK, "alice",
                props("assignee", "alice"));
        filters.createFilter("alice 的单", WfFilterType.PROCESS_INSTANCE, "alice", null);
        filters.createFilter("bob 的待办", WfFilterType.TASK, "bob", props("assignee", "bob"));

        // 三个筛选器都在，**每个过滤条件都有可以被它剔掉的对象**
        assertEquals(3, filters.listFilters(new WfFilterQuery()).size());
        assertEquals(3, filters.countFilters(new WfFilterQuery()));

        assertEquals(1, filters.listFilters(new WfFilterQuery().setId(taskOfAlice.getId())).size());
        assertEquals(2, filters.listFilters(new WfFilterQuery().setOwner("alice")).size(),
                "按创建人筛");
        assertEquals(2, filters.listFilters(
                new WfFilterQuery().setResourceType(WfFilterType.TASK)).size(),
                "按类型筛");
        assertEquals(2, filters.listFilters(
                new WfFilterQuery().setNameLike("待办")).size(),
                "「alice 的待办」与「bob 的待办」都该命中，而「alice 的单」不该 —— "
                        + "三条数据里那个被排除的必须真的在，才能证明模糊匹配干了活");
        // 精确匹配要能在一组**互相包含**的名字里挑出那一个。
        // 少了这一对，上面那条 nameLike 的判据验不掉「其实在做精确匹配」这个缺陷 ——
        // 「alice 的待办」当模糊模式用也只命中它自己，两条路结果一样
        WfFilter old = filters.createFilter("我的待办", WfFilterType.TASK, "ops", null);
        WfFilter older = filters.createFilter("我的待办（旧）", WfFilterType.TASK, "ops", null);
        assertEquals(2, filters.listFilters(
                new WfFilterQuery().setNameLike("我的待办")).size(),
                "模糊匹配两个都该命中");
        List<WfFilter> exact = filters.listFilters(new WfFilterQuery().setName("我的待办"));
        assertEquals(1, exact.size(), "精确匹配只能命中那一个 —— 命中两个就是偷偷做成了模糊匹配");
        assertEquals(old.getId(), exact.get(0).getId());
        assertNotNull(old);
        assertNotNull(older);
    }

    @Test
    @DisplayName("翻页时顺序稳定 —— 否则同一页两次列出来不一样")
    void listOrderIsStable() {
        // **这条断言对内存实现没有区分力**，如实说明：反向验证把 `matched.sort(...)`
        // 整段摘掉，它照样绿。原因是 `ConcurrentHashMap` 对一组**固定的 key**
        // 遍历顺序是稳定的 —— 摘掉排序之后两次查询仍然一致，
        // 所以"两次一致"这件事根本证明不了"排过序"。
        // 真正有区分力的排序判据放在 JdbcWorkflowPersistenceTest#filterOrderIsDeterministic：
        // SQL 不写 ORDER BY 时顺序由存储引擎决定，那里才真的能观察到顺序变了。
        // 本条保留是因为它钉的是**服务层对调用方的承诺**（同一条件下两次列出来一样），
        // 而一旦哪天内存实现换成顺序不确定的容器，它会立刻变红
        for (int i = 0; i < 5; i++) {
            filters.createFilter("筛选器-" + i, WfFilterType.TASK, "ops", null);
        }
        List<String> first = namesOf(filters.listFilters(new WfFilterQuery().setPageSize(3)));
        List<String> second = namesOf(filters.listFilters(new WfFilterQuery().setPageSize(3)));
        assertEquals(first, second, "同样条件下两次列出来必须一模一样");
        assertEquals(3, first.size());
        assertEquals(2, namesOf(filters.listFilters(
                new WfFilterQuery().setPageSize(3).setPageNum(2))).size());
    }

    @Test
    @DisplayName("三态布尔：空串 = 不限（前端「不限」下拉框传的就是空串）")
    void nullableBooleanTreatsBlankAsUnset() {
        WfFilter created = filters.createFilter("含空串", WfFilterType.TASK, "ops",
                props("suspendedOnly", ""));
        assertNull(filters.taskQuery(created.getId()).getSuspendedOnly(),
                "空串要当成「不限」而不是 false —— 当成 false 的话，"
                        + "「所有待办」这一档会悄悄只剩未挂起的那些");
    }

    @Test
    @DisplayName("内存实现自己也拦乐观锁 —— 服务层那道是第二道，不是唯一一道")
    void inMemoryPersistenceAlsoLocks() {
        WfFilter created = filters.createFilter("x", WfFilterType.TASK, "ops", null);
        WfFilter first = repo.findFilter(created.getId());
        WfFilter stale = repo.findFilter(created.getId());
        first.setName("改过的");
        first.nextRevision();
        repo.saveFilter(first);

        stale.setName("过期的");
        stale.nextRevision();
        // 绕过服务层直接打存储层：服务层会先替你对一遍版本号，
        // 存储层这道要是没有，绕开服务层的调用方（未来的定时任务、脚本）就会静默覆盖
        assertThrows(WfOptimisticLockException.class, () -> repo.saveFilter(stale));
        assertEquals("改过的", repo.findFilter(created.getId()).getName());
    }

    private List<String> namesOf(List<WfFilter> list) {
        List<String> names = new java.util.ArrayList<String>();
        for (WfFilter each : list) {
            names.add(each.getName());
        }
        return names;
    }

    @Test
    @DisplayName("空条件是合法的：一张什么都不筛的筛选器就是「全部」")
    void emptyPropertiesIsLegal() {
        WfFilter created = filters.createFilter("全部待办", WfFilterType.TASK, "ops", null);
        assertNull(filters.taskQuery(created.getId()).getAssignee(),
                "空条件就是什么都不筛，不该报错也不该被填上任何值");
        start();
        assertEquals(1, filters.run(created.getId(), 1, 20).getTotal());
    }
}
