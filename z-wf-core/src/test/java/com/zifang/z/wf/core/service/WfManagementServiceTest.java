package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.dmn.WfDmnDecision;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfCompensationEntry;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfFilter;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.JdbcWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.view.WfTableInfo;

/**
 * 引擎自省 —— 回答「我连的是什么、底下有什么、有多少」。
 *
 * <p>这组用例盯的是三件"自省接口骗人"的事：
 * <ol>
 *   <li><b>名字不认识时返回 0</b>。拼错一个表名得到"这里是空的"，
 *       会把排障方向从「我拼错了」带偏到「谁把它清空了」。</li>
 *   <li><b>两套实现报的名单对不上</b>。那意味着某种逻辑实体在一套实现里存得下、
 *       在另一套里存不下，而症状是「内存模式能查、JDBC 下查不到」。</li>
 *   <li><b>报的是"打算建的表"而不是"库里真有的表"</b>。连的是别人的库、
 *       或 initialize 被条件挡掉时，接口仍会说八张表都在，排障时先信了它就找不到北。</li>
 * </ol>
 */
class WfManagementServiceTest {

    // ==================== 属性 ====================

    @Test
    @DisplayName("属性如实说出版本、schema 版本与存储形态，且不含任何凭据")
    void propertiesAreHonest() {
        Map<String, Object> memory = propsOf(memoryPersistence());
        assertEquals("z-wf", memory.get("engine"));
        assertEquals(WfManagementService.VERSION, memory.get("version"));
        assertEquals("in-memory", memory.get("persistence"),
                "内存实现下必须说自己不是 jdbc —— 报错了运维才会跑去数据库里找表");
        assertTrue(String.valueOf(memory.get("storage")).contains("不落库"),
                "内存存储要把「重启即失」写进返回值：这是排障时最先要确认的一条。实际: "
                        + memory.get("storage"));
        assertEquals(10, memory.get("storageCount"));

        Map<String, Object> jdbc = propsOf(jdbcPersistence());
        assertEquals("jdbc", jdbc.get("persistence"));
    }

    @Test
    @DisplayName("自省结果里不能出现任何看起来像凭据的字段")
    void propertiesCarryNoCredentials() {
        List<Map<String, Object>> allProps = java.util.Arrays.asList(
                propsOf(memoryPersistence()), propsOf(jdbcPersistence()));
        for (Map<String, Object> properties : allProps) {
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                String key = entry.getKey().toLowerCase();
                String value = String.valueOf(entry.getValue()).toLowerCase();
                assertFalse(key.contains("password") || key.contains("secret")
                                || key.contains("jdbc:") || value.contains("jdbc:"),
                        "自省接口常被监控无差别暴露，不能顺带把连接串/凭据摊出去: "
                                + entry.getKey() + "=" + entry.getValue());
            }
        }
    }

    @Test
    @DisplayName("版本常量与 pom 的 <revision> 同步 —— 漂了就变红")
    void versionMatchesPom() throws Exception {
        // 手写常量必然会漂。与其写一句注释说"记得同步"，
        // 不如让测试去读 pom 比一遍：改了 pom 忘了改常量，这里立刻变红。
        // surefire 的工作目录是**模块目录**（z-wf-core），不是仓库根，
        // 所以 `Paths.get("pom.xml")` 找到的是模块自己的 pom —— 里面没有 <revision>。
        // 向上找第一个带 <revision> 的 pom，而不是赌一个固定的相对层数
        // （层数会随模块拆分变，而赌错时报的是"找不到"）。
        Path dir = Paths.get("").toAbsolutePath();
        Path pom = null;
        while (dir != null) {
            Path candidate = dir.resolve("pom.xml");
            if (Files.exists(candidate)
                    && new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8)
                            .contains("<revision>")) {
                pom = candidate;
                break;
            }
            dir = dir.getParent();
        }
        assertNotNull(pom, "从 " + Paths.get("").toAbsolutePath()
                + " 向上找不到带 <revision> 的 pom.xml");
        String xml = new String(Files.readAllBytes(pom), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("<revision>([^<]+)</revision>").matcher(xml);
        assertTrue(matcher.find(), "pom 里找不到 <revision>: " + pom);
        assertEquals(matcher.group(1).trim(), WfManagementService.VERSION,
                "WfManagementService.VERSION 与 pom 的 <revision> 不一致 —— "
                        + "自省接口对外报的版本是错的，运维据此判断兼容性会得出相反结论");
    }

    // ==================== 存储清单 ====================

    @Test
    @DisplayName("两套实现报的存储项名单必须完全一致")
    void bothImplementationsReportTheSameNames() {
        // 这条是本类的核心不变式。名单对不上意味着某种逻辑实体在一套实现里存得下、
        // 在另一套里存不下 —— 而症状是「内存模式能查、JDBC 下查不到」，
        // 两侧各自的测试都不一定碰到那个数据。
        List<String> memory = new java.util.ArrayList<>(memoryPersistence().getTableNames());
        List<String> jdbc = new java.util.ArrayList<>(jdbcPersistence().getTableNames());
        assertEquals(new java.util.TreeSet<>(memory), new java.util.TreeSet<>(jdbc),
                "两套实现的存储项名单不一致。内存=" + memory + " / JDBC=" + jdbc);
        assertEquals(InMemoryWorkflowPersistence.STORAGE_NAMES, memory,
                "内存侧的名单应当直接暴露常量，不要另抄一份");
        // 集合相等即可，**不比顺序**：JDBC 侧从元数据读回来后要排序
        // （数据库返回的顺序由它自己决定），而常量是按 DDL 出现顺序写的。
        // 要比的真正性质是「内容一致」，顺序由各自的实现保证稳定。
        assertEquals(new java.util.TreeSet<>(JdbcWorkflowPersistence.TABLE_NAMES),
                new java.util.TreeSet<>(jdbc),
                "JDBC 侧必须报**从库里查到的**表，不是常量 —— 常量是「打算建哪些」，"
                        + "这里要回答的是「这个库现在真的有哪些」。常量=" 
                        + JdbcWorkflowPersistence.TABLE_NAMES + " 实际=" + jdbc);
        // 顺序稳定性：连查两次要一样，否则运维 diff 两次结果会以为"表变了"
        assertEquals(jdbcPersistence().getTableNames(), jdbcPersistence().getTableNames());
    }

    @Test
    @DisplayName("清单里每项都带类型：内存是集合，JDBC 是表")
    void tablesAreTyped() {
        for (WfTableInfo info : listOf(memoryPersistence())) {
            // 断的是**对外字面量**而不是 WfTableInfo.KIND_COLLECTION 常量本身。
            // 这个字符串会被 REST 原样返回给客户端，是对外契约；
            // 若判据只比常量引用，改了字面量而两边同步变化，断言照样绿
            // —— 变异 M21 打不红就是栽在这里。
            assertEquals("collection", info.getKind(),
                    "内存实现下没有表，不标类型的话运维会跑去数据库里找 " + info.getName());
        }
        for (WfTableInfo info : listOf(jdbcPersistence())) {
            assertEquals("table", info.getKind());
        }
    }

    @Test
    @DisplayName("清单里的行数与单独查同一个名字必须一致")
    void listRowCountsMatchSingleCountQuery() {
        // 这条同时钉住**两个实现点**：
        // ① WfManagementService#getTables 构造 WfTableInfo 时传进去的那个数；
        // ② WfTableInfo#getRowCount 返回的那个字段。
        // 之前的判据只走 getTableCount(name) 这一条路，而**清单是另一条组装路径** ——
        // 两条能各自独立地坏掉（M08 把传入值写死 0、M20 把 getter 写死 0），
        // 而「清单里的行数恒为 0」恰是自省接口最坏的失败模式：
        // 它与「这张表真的是空的」在运维眼里长得一模一样。
        for (WfPersistence persistence : new WfPersistence[]{memoryPersistence(), jdbcPersistence()}) {
            WfManagementService service = new WfManagementService(persistence);
            persistence.saveProcessInstance(new WfProcessInstance("p1", "k", "k:1"));
            for (WfTableInfo info : service.getTables()) {
                assertEquals(service.getTableCount(info.getName()), info.getRowCount(),
                        "清单里 " + info.getName() + " 报 " + info.getRowCount()
                                + " 行，单独查却是 " + service.getTableCount(info.getName())
                                + " —— 同一件事的两条路径给出的答案必须一致");
            }
        }
    }

    @Test
    @DisplayName("行数跟着数据走，不是恒等于 0")
    void countsFollowTheData() {
        WfPersistence memory = memoryPersistence();
        WfManagementService service = new WfManagementService(memory);
        assertEquals(0, service.getTableCount("ZWF_PROCESS"));

        memory.saveProcessInstance(
                new com.zifang.z.wf.core.model.WfProcessInstance("p1", "k", "k:1"));
        memory.saveProcessInstance(
                new com.zifang.z.wf.core.model.WfProcessInstance("p2", "k", "k:1"));
        assertEquals(2, service.getTableCount("ZWF_PROCESS"),
                "行数必须真读当前数据 —— 恒返回 0 的自省接口比没有更坏："
                        + "它会让运维相信「库是空的」");
        assertEquals(0, service.getTableCount("ZWF_TASK"),
                "没建过任务的实例，任务数确实是 0 —— 与上面那条的区别在于数据真的变了");
    }

    @Test
    @DisplayName("名字不认识必须报错，不返回 0")
    void unknownNameFailsLoudly() {
        WfManagementService service = new WfManagementService(memoryPersistence());
        for (String wrong : new String[]{"ZWF_PROCES", "zwf_task", "不存在的表"}) {
            WfEngineException ex = assertThrows(WfEngineException.class,
                    () -> service.getTableCount(wrong), "名字 " + wrong + " 不该被当成 0");
            assertTrue(ex.getMessage().contains(wrong),
                    "报错要点名是哪一项: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("ZWF_TASK"),
                    "报错要把合法的名字列出来，否则调用方无从知道自己能问什么: "
                            + ex.getMessage());
        }
        assertTrue(assertThrows(WfEngineException.class,
                () -> service.getTableCount("  ")).getMessage().contains("不能为空"));
        assertTrue(assertThrows(WfEngineException.class,
                () -> service.getTableCount(null)).getMessage().contains("不能为空"));
    }

    @Test
    @DisplayName("九个存储项各自数自己 —— 造多少条报多少，互不串味")
    void everyStorageEntityIsCountedSeparately() {
        // 内存侧 getTableCount 是八段 if-else 加一条 `return filters.size()` 兜底。
        // 任何一段写错（返回了隔壁的 size、聚合漏了一层、分支条件串了），
        // 症状都是「某一项的数其实来自另一项」—— 那种错在只测单项时完全看不出来，
        // 因为两项都恰好是 0 时，返回谁都一样。
        // 所以这里给九项各造**互不相同**的数量：只要有一项串味，数字立刻对不上。
        WfPersistence memory = memoryPersistence();

        // 定义：同一个 key 的三个版本 → 3 行（**不是** 1 行，也不是 1 行数）
        for (int version = 1; version <= 3; version++) {
            WfDefinition definition = new WfDefinition("dup", "重复发布的流程");
            definition.setVersion(version);
            memory.saveDefinition(definition);
        }
        memory.saveProcessInstance(new WfProcessInstance("p1", "k", "k:1"));          // 1
        memory.saveExecution(new WfExecution("e1", "p1", "a1"));                      // 2
        memory.saveExecution(new WfExecution("e2", "p1", "a2"));
        for (int i = 1; i <= 5; i++) {                                                 // 5
            WfTask task = new WfTask();
            task.setId("t" + i);
            task.setProcessInstanceId("p1");
            memory.saveTask(task);
        }
        for (int i = 1; i <= 2; i++) {                                                 // 2
            WfJob job = new WfJob();
            job.setId("j" + i);
            memory.saveJob(job);
        }
        for (int i = 1; i <= 4; i++) {                                                 // 4
            // 活动历史是追加的：同一个实例下 save 几次就有几行
            WfActivityInstance activity = new WfActivityInstance();
            activity.setProcessInstanceId("p1");
            activity.setActivityId("a" + i);
            memory.saveActivityInstance(activity);
        }
        for (int i = 1; i <= 6; i++) {                                                 // 6
            memory.saveComment(new WfComment("c" + i, "p1", "u", "comment", "内容" + i));
        }
        for (int i = 1; i <= 8; i++) {                                                 // 8
            WfFilter filter = new WfFilter();
            filter.setId("f" + i);
            memory.saveFilter(filter);
        }
        for (int i = 1; i <= 7; i++) {                                                 // 7
            memory.saveDecision(new WfDmnDecision("d" + i, "决策" + i));
        }
        for (int i = 1; i <= 9; i++) {                                                 // 9
            WfCompensationEntry entry = new WfCompensationEntry();
            entry.setId("cmp" + i);
            entry.setProcessInstanceId("p1");
            entry.setActivityId("act" + i);
            entry.setSeq(i);
            memory.saveCompensation(entry);
        }

        Map<String, Long> expected = new java.util.LinkedHashMap<>();
        expected.put("ZWF_DEFINITION", 3L);
        expected.put("ZWF_PROCESS", 1L);
        expected.put("ZWF_EXECUTION", 2L);
        expected.put("ZWF_TASK", 5L);
        expected.put("ZWF_JOB", 2L);
        expected.put("ZWF_ACTIVITY", 4L);
        expected.put("ZWF_COMMENT", 6L);
        expected.put("ZWF_FILTER", 8L);
        expected.put("ZWF_DECISION", 7L);
        expected.put("ZWF_COMPENSATION", 9L);
        assertEquals(new java.util.TreeSet<>(expected.keySet()),
                new java.util.TreeSet<>(InMemoryWorkflowPersistence.STORAGE_NAMES),
                "这里造的十项必须与存储项名单一一对应 —— 名单多一项或少一项，"
                        + "下面的断言就只覆盖了其中一部分。**比集合不比顺序**："
                        + "这里要验的是「覆盖完整」，不是「顺序一致」");

        WfManagementService service = new WfManagementService(memory);
        for (java.util.Map.Entry<String, Long> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), service.getTableCount(entry.getKey()),
                    entry.getKey() + " 数错了。十项各造了互不相同的条数就是为了抓住串味 —— "
                            + "如果这十项里有两项碰巧相等，下面这条断言就抓不住");
        }
    }

    @Test
    @DisplayName("定义数按「每个 key 的每个版本」累加，不是按 key 数")
    void definitionCountSumsEveryVersion() {
        // 九个分支里有三个是「聚合遍历」（定义按版本数、活动与评论按实例分组），
        // 另外六个是直接的 .size()。聚合那三个最容易写成"外层 size"——
        // 而 key 数与版本数在单版本流程上恰好相等，于是**一条版本用例都测不出来**。
        WfPersistence memory = memoryPersistence();
        for (int version = 1; version <= 3; version++) {
            WfDefinition definition = new WfDefinition("dup", "同名流程");
            definition.setVersion(version);
            memory.saveDefinition(definition);
        }
        assertEquals(3, new WfManagementService(memory).getTableCount("ZWF_DEFINITION"),
                "定义行数必须数到每一个版本。写成 definitions.size()（key 数）的话，"
                        + "同一流程升过版之后自省就会少报，而症状是「版本还在、数不见了」");
    }

    // ==================== 夹具 ====================

    private Map<String, Object> propsOf(WfPersistence persistence) {
        return new WfManagementService(persistence).getProperties();
    }

    private List<WfTableInfo> listOf(WfPersistence persistence) {
        return new WfManagementService(persistence).getTables();
    }

    private WfPersistence memoryPersistence() {
        InMemoryWorkflowPersistence repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        return repo;
    }

    private WfPersistence jdbcPersistence() {
        JdbcDataSource ds = new JdbcDataSource();
        // 每次独立库名：表清单是从库里真查的，残留的库会污染断言
        ds.setURL("jdbc:h2:mem:wf_mgmt_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        JdbcWorkflowPersistence repo = new JdbcWorkflowPersistence(ds);
        repo.initialize();
        return repo;
    }

    @Test
    @DisplayName("JDBC 未建表时清单为空 —— 报的是「这个库真有的」而不是「打算建的」")
    void jdbcReportsWhatActuallyExists() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:wf_mgmt_empty_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        JdbcWorkflowPersistence repo = new JdbcWorkflowPersistence(ds);
        // 刻意**不调** initialize：模拟"连上了别人的库但迁移没跑"
        assertTrue(repo.getTableNames().isEmpty(),
                "没建表的库里应当报空清单。报出八张表的话，排障的人会先去查「谁把它清空了」，"
                        + "而真相是「这个库压根没迁移过」");
        assertEquals(0, new WfManagementService(repo)
                .getProperties().get("storageCount"));
    }

    @Test
    @DisplayName("saveProcessInstance 之后 JDBC 的行数接口才看得到那条数据")
    void jdbcCountsFollowRealRows() {
        WfPersistence repo = jdbcPersistence();
        repo.saveProcessInstance(
                new com.zifang.z.wf.core.model.WfProcessInstance("p1", "k", "k:1"));
        assertEquals(1, new WfManagementService(repo).getTableCount("ZWF_PROCESS"));
        Map<String, Object> properties = new WfManagementService(repo).getProperties();
        assertNotNull(properties.get("storageCount"));
        assertEquals(10, properties.get("storageCount"),
                "建完表之后应当报满十张 —— 与「未迁移时报空」那条互为对照");
    }

    @Test
    @DisplayName("行数接口不接受能拼 SQL 的名字")
    void tableNameCannotSmuggleSql() {
        WfManagementService service = new WfManagementService(jdbcPersistence());
        for (String evil : new String[]{
                "ZWF_TASK; DROP TABLE ZWF_PROCESS",
                "ZWF_TASK WHERE 1=1 UNION SELECT 1 FROM ZWF_PROCESS",
                "NOT_A_TABLE"}) {
            assertThrows(WfEngineException.class, () -> service.getTableCount(evil),
                    "名字 " + evil + " 绝不能被拼进 SQL");
        }
        // 确认表还在（上面那串没有造成任何影响）
        assertEquals(10, service.getTables().size());
    }

    @Test
    @DisplayName("两种存储形态下同一批逻辑实体都能被问到")
    void bothStoragesAnswerTheSameEntities() {
        for (WfPersistence persistence : new WfPersistence[]{memoryPersistence(), jdbcPersistence()}) {
            WfManagementService service = new WfManagementService(persistence);
            for (String name : InMemoryWorkflowPersistence.STORAGE_NAMES) {
                assertNotNull(service.getTables().stream()
                                .filter(info -> name.equals(info.getName())).findFirst().orElse(null),
                        "存储形态 " + service.persistenceKind() + " 下少了 " + name);
            }
        }
    }
}