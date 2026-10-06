package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.JdbcWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 改流程实例名称。
 *
 * <p>本轮最容易坏掉、也最难从症状看出来的一条不变式是
 * <b>「名字只有 {@code setProcessInstanceName} 改得动」</b>。
 * 本引擎每次推进结束时都会把 {@code context} 里持有的那个实例对象
 * {@code saveProcessInstance} 回去（事务收口的必经之路），而那个对象是
 * <b>改名前取的</b>。名字一旦出现在那条部分更新的列清单里，
 * 用户改完名再点一次「通过」，名字就被引擎自己抹回 null ——
 * 而症状是「改完名当场能看到，过一会儿自己又没了」，
 * 而且只在<b>改完名之后又推进过流程</b>的那些单子上出现，越查越像偶发。
 */
class WfProcessInstanceNameTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static final String SEQ_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"nameSeq\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <userTask id=\"t1\" name=\"一级\" zifang:assignee=\"alice\"/>\n"
            + "    <userTask id=\"t2\" name=\"二级\" zifang:assignee=\"bob\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t1\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"t2\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"t2\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    // ==================== 两套存储都要成立 ====================

    @Test
    @DisplayName("改名能存能读（内存与 JDBC 两套实现）")
    void renamingRoundTrips() {
        for (WfPersistence persistence : new WfPersistence[]{memory(), jdbc()}) {
            String pid = start(persistence, "round-1");
            WfProcessInstance named = service(persistence).setProcessInstanceName(
                    pid, "张三的请假申请");
            assertEquals("张三的请假申请", named.getName());
            assertEquals("张三的请假申请", persistence.findProcessInstance(pid).getName(),
                    "名字必须真的落库了 —— 只改内存对象的话，"
                            + "换个请求（换了连接池的连接）就又变回空标题");
        }
    }

    @Test
    @DisplayName("改名之后推进流程，名字还在（字段所有权：只有改名这一条路能改它）")
    void nameSurvivesTheNextStateChange() {
        for (WfPersistence persistence : new WfPersistence[]{memory(), jdbc()}) {
            WfRuntimeService runtime = service(persistence);
            String pid = start(persistence, "survive-1");
            runtime.setProcessInstanceName(pid, "活要留着");

            complete(runtime, persistence, pid, "alice", "一级批了");
            assertEquals("活要留着", persistence.findProcessInstance(pid).getName(),
                    "推进一次就把名字抹掉了 —— saveProcessInstance 的部分更新里"
                            + "混进了 NAME。症状在生产上表现为「改完名当场能看到，"
                            + "过一会儿自己没了」，且只发生在改完名又推进过的单子上");

            complete(runtime, persistence, pid, "bob", "二级批了");
            assertEquals("活要留着", persistence.findProcessInstance(pid).getName(),
                    "推进到结束也不能丢 —— 完结的记录仍会被列表页翻到");
        }
    }

    @Test
    @DisplayName("拿一个「手里没有 name 的旧实例对象」回写状态，名字不丢")
    void savingAStaleInstanceDoesNotWipeTheName() {
        for (WfPersistence persistence : new WfPersistence[]{memory(), jdbc()}) {
            WfRuntimeService runtime = service(persistence);
            String pid = start(persistence, "stale-1");
            // 模拟"别的线程在改名前就把实例取走了"：它手上的对象 name 一定是 null
            WfProcessInstance stale = persistence.findProcessInstance(pid);
            runtime.setProcessInstanceName(pid, "别动我");

            assertNull(stale.getName(), "这个夹具前提：改名前取的实例确实没有名字");
            stale.setStatus(WfProcessStatus.ACTIVE);
            stale.nextRevision();
            persistence.saveProcessInstance(stale);

            assertEquals("别动我", persistence.findProcessInstance(pid).getName(),
                    "回写一个改名前取的实例把名字抹掉了 —— "
                            + "NAME 只能由 setProcessInstanceName 写，"
                            + "saveProcessInstance 的部分更新里不能有它");
        }
    }

    @Test
    @DisplayName("改名不动 revision —— 它与状态机无关，不该跟推进抢乐观锁")
    void renamingDoesNotTouchRevision() {
        for (WfPersistence persistence : new WfPersistence[]{memory(), jdbc()}) {
            WfRuntimeService runtime = service(persistence);
            String pid = start(persistence, "revision-1");
            int before = persistence.findProcessInstance(pid).getRevision();
            runtime.setProcessInstanceName(pid, "只是改个标题");
            int after = persistence.findProcessInstance(pid).getRevision();
            assertEquals(before, after,
                    "改名把 revision 推高了的话，恰好在同一时刻推进过流程的那条单子"
                            + "会拿到「乐观锁冲突」—— 而改标题与审批推进之间"
                            + "根本没有任何因果关系");
        }
    }

    // ==================== 校验 ====================

    @Test
    @DisplayName("实例不存在要报错并点名，不能安静地什么都不做")
    void unknownInstanceFailsLoudly() {
        WfRuntimeService runtime = service(memory());
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.setProcessInstanceName("proc-不存在的", "改名"));
        assertTrue(ex.getMessage().contains("proc-不存在的"),
                "报错要点名是哪个实例，否则调用方不知道该去查什么: " + ex.getMessage());
        assertThrows(WfEngineException.class,
                () -> runtime.setProcessInstanceName("  ", "改名"), "空 id 也该报错");
        assertThrows(WfEngineException.class,
                () -> runtime.setProcessInstanceName(null, "改名"));
    }

    @Test
    @DisplayName("空白串报错，null 才表示清空 —— 空标题与没起名字在界面上一样、语义不同")
    void blankNameRejectedButNullClears() {
        for (WfPersistence persistence : new WfPersistence[]{memory(), jdbc()}) {
            WfRuntimeService runtime = service(persistence);
            String pid = start(persistence, "blank-1");
            runtime.setProcessInstanceName(pid, "有名字");
            for (String blank : new String[]{"", "   ", "\t\n"}) {
                WfEngineException ex = assertThrows(WfEngineException.class,
                        () -> runtime.setProcessInstanceName(pid, blank),
                        "空白串 [" + blank + "] 不该被当成合法名字");
                assertTrue(ex.getMessage().contains("清空"),
                        "报错要说清「清空请传 null」: " + ex.getMessage());
            }
            assertEquals("有名字", persistence.findProcessInstance(pid).getName(),
                    "被判失败的改名不该把原名字改掉");
            runtime.setProcessInstanceName(pid, null);
            assertNull(persistence.findProcessInstance(pid).getName(),
                    "传 null 就是清空，这是与「空串」分开的第二件事");
        }
    }

    // ==================== 老库补列 ====================

    @Test
    @DisplayName("老库（没有 NAME 列）经 initialize 补列后可以正常改名")
    void legacyTableGetsNameColumnOnInitialize() {
        Jdbc jdbc = jdbcTyped();
        JdbcWorkflowPersistence repo = jdbc.repo;
        WfRuntimeService runtime = service(repo);
        String pid = start(repo, "legacy-1");
        // 把这列删掉，模拟 2.0.0 之前建的库：
        // CREATE TABLE IF NOT EXISTS 不会触发，缺的这一列只能靠 initialize 末尾的 ALTER 补
        execute(jdbc, "ALTER TABLE ZWF_PROCESS DROP COLUMN NAME");
        assertFalse(columnNames(jdbc, "ZWF_PROCESS").contains("NAME"),
                "夹具前提：这一列确实已经没了");

        repo.initialize();

        assertTrue(columnNames(jdbc, "ZWF_PROCESS").contains("NAME"),
                "initialize 之后 NAME 列必须被补上 —— 缺列的后果是「引擎启动即炸」"
                        + "（每一句 SELECT 都报 column not found），而不是某个功能悄悄坏掉");
        runtime.setProcessInstanceName(pid, "老库改的名");
        assertEquals("老库改的名", repo.findProcessInstance(pid).getName());
    }

    /**
     * <b>这一列无法像 {@code IS_DEFAULT} 那样区分「DDL 建的」与「ALTER 补的」</b> ——
     * 两句定义字面相同（{@code NAME VARCHAR(512)}，都没有 NOT NULL 也没有 DEFAULT），
     * 库上看不出差别。
     * <p>所以这里不假装能验 DDL 完整性：新库上 DDL 就算漏了这一列，
     * 末尾的 ALTER 也会兜住，代价只是多一次 ALTER（不炸、不丢数据），
     * 而漏写 DDL 的那种失败模式在这里<b>不成立</b>。
     * 真正会炸的是「DDL 与 ALTER 都漏」，而那条被上面那条老库用例兜住了。
     */
    @Test
    @DisplayName("新建的 ZWF_PROCESS 当场就带 NAME 列")
    void freshTableAlreadyHasTheNameColumn() {
        Jdbc jdbc = jdbcTyped();
        assertTrue(columnNames(jdbc, "ZWF_PROCESS").contains("NAME"),
                "DDL 漏了这一列的话，新库要等 initialize 末尾的 ALTER 才补上");
    }

    // ==================== 夹具 ====================

    private WfPersistence memory() {
        InMemoryWorkflowPersistence repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        return repo;
    }

    /**
     * {@code JdbcWorkflowPersistence} 刻意不暴露 {@code getDataSource()} ——
     * 持久化实现不负责让调用方绕过它自己写 SQL。测试要直接操作表结构（造老库），
     * 所以在夹具这一侧把 dataSource 一起带出来。
     */
    private static final class Jdbc {

        private final JdbcDataSource dataSource;

        private final JdbcWorkflowPersistence repo;

        Jdbc(String dbName) {
            dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:wf_name_" + dbName + ";DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            repo = new JdbcWorkflowPersistence(dataSource);
            repo.initialize();
        }
    }

    private WfPersistence jdbc() {
        return jdbcTyped().repo;
    }

    private Jdbc jdbcTyped() {
        return new Jdbc(String.valueOf(COUNTER.incrementAndGet()));
    }

    private WfRuntimeService service(WfPersistence persistence) {
        return new WfRuntimeService(new WfRepositoryService(persistence), persistence,
                new WfEngine(), new WfHookDispatcher());
    }

    private String start(WfPersistence persistence, String businessKey) {
        WfRepositoryService repository = new WfRepositoryService(persistence);
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(SEQ_BPMN));
        WfRuntimeService runtime = service(persistence);
        return runtime.startProcessInstance(definition, businessKey, null, null,
                new HashMap<String, Object>());
    }

    private void complete(WfRuntimeService runtime, WfPersistence persistence,
                          String pid, String assignee, String comment) {
        WfTask task = null;
        for (WfTask candidate : persistence.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(pid).setOpenOnly(true).setPageNum(1).setPageSize(20))) {
            if (assignee.equals(candidate.getAssignee())) {
                task = candidate;
                break;
            }
        }
        assertNotNull(task, "流程 " + pid + " 上找不到 " + assignee + " 的待办");
        runtime.completeTask(task.getId(), assignee, comment, new HashMap<String, Object>());
    }

    private void execute(Jdbc jdbc, String sql) {
        try {
            Connection c = jdbc.dataSource.getConnection();
            Statement st = c.createStatement();
            try {
                st.execute(sql);
            } finally {
                st.close();
                c.close();
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("执行 " + sql + " 失败", e);
        }
    }

    private java.util.List<String> columnNames(Jdbc jdbc, String table) {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            Connection c = jdbc.dataSource.getConnection();
            java.sql.PreparedStatement ps = c.prepareStatement(
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME)=?");
            try {
                ps.setString(1, table);
                java.sql.ResultSet rs = ps.executeQuery();
                try {
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                } finally {
                    rs.close();
                }
            } finally {
                ps.close();
                c.close();
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("读列名失败", e);
        }
        return names;
    }
}
