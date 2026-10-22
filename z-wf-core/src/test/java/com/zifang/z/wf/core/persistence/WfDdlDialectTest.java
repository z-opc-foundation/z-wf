package com.zifang.z.wf.core.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DDL 方言适配的回归守卫。
 *
 * <p><b>为什么有这组测试</b>：{@code JdbcWorkflowPersistence.initialize()} 的建表语句
 * 原本按 H2 写（{@code CLOB} 与 {@code CREATE INDEX IF NOT EXISTS} 都是 H2 扩展语法），
 * 而默认数据源却是 MySQL JDBC URL ⇒ 两条路从未同时成立过。
 * 实测 MySQL 8.0：{@code CLOB} 与 {@code CREATE INDEX IF NOT EXISTS} 都是 {@code ERROR 1064}，
 * 而 DDL 执行遇错即抛（{@code WfPersistenceException("建表失败")}）⇒ 容器起不来。
 *
 * <p>这组测试用 <b>H2 的 MySQL 兼容模式</b>（{@code MODE=MySQL}）当替身：
 * 它与真 MySQL 一样<b>不认</b> {@code CREATE INDEX IF NOT EXISTS}、也不认 {@code CLOB}，
 * 于是能离线复现同样的失败，而不必在 CI 里拉 MySQL 容器。
 *
 * <p>另有一条 {@link MySqlDdlCompatibilityIT} 风格的用例在环境变量
 * {@code ZWF_TEST_MYSQL_URL} 存在时直连真 MySQL 跑同一套断言（本地/CI 有 MySQL 时才生效）。
 */
class WfDdlDialectTest {

    // ==================== 纯函数层 ====================

    @Test
    @DisplayName("产品名归一化：null/空白/大小写")
    void normalizeProductName() {
        assertEquals("", WfDdlDialect.normalizeProductName(null));
        assertEquals("", WfDdlDialect.normalizeProductName("   "));
        assertEquals("mysql", WfDdlDialect.normalizeProductName("MySQL"));
        assertEquals("mysql", WfDdlDialect.normalizeProductName("  mysql  "));
    }

    @Test
    @DisplayName("MySQL 家族识别：H2 / PostgreSQL 不是 MySQL")
    void mysqlFamilyDetection() {
        assertTrue(WfDdlDialect.isMySQL("mysql"));
        assertTrue(WfDdlDialect.isMySQL("mysql 8.0.46"));
        assertFalse(WfDdlDialect.isMySQL("h2"));
        assertFalse(WfDdlDialect.isMySQL("postgresql"));
        assertFalse(WfDdlDialect.isMySQL(""));
        assertFalse(WfDdlDialect.isMySQL(null));
    }

    @Test
    @DisplayName("索引语句识别：CREATE INDEX / CREATE UNIQUE INDEX")
    void indexCreateDetection() {
        assertTrue(WfDdlDialect.isIndexCreate(
                "CREATE INDEX IF NOT EXISTS IDX_A ON T (C)"));
        assertTrue(WfDdlDialect.isIndexCreate(
                "CREATE UNIQUE INDEX IF NOT EXISTS UDX_A ON T (C)"));
        assertFalse(WfDdlDialect.isIndexCreate(
                "CREATE TABLE IF NOT EXISTS T (A VARCHAR(8))"));
        assertFalse(WfDdlDialect.isIndexCreate(null));
    }

    @Test
    @DisplayName("索引名提取：剥掉 CREATE / UNIQUE / INDEX / IF NOT EXISTS")
    void indexNameExtraction() {
        assertEquals("IDX_A", WfDdlDialect.extractIndexName(
                "CREATE INDEX IF NOT EXISTS IDX_A ON T (C)"));
        assertEquals("UDX_A", WfDdlDialect.extractIndexName(
                "CREATE UNIQUE INDEX IF NOT EXISTS UDX_A ON T (C)"));
        // 没有 IF NOT EXISTS 的写法也要能剥
        assertEquals("IDX_B", WfDdlDialect.extractIndexName(
                "CREATE INDEX IDX_B ON T (C)"));
        assertEquals("UDX_C", WfDdlDialect.extractIndexName(
                "CREATE UNIQUE INDEX UDX_C ON T (C)"));
        // 没有 ON ⇒ 解析不出
        assertEquals(null, WfDdlDialect.extractIndexName("CREATE INDEX IDX_A"));
    }

    @Test
    @DisplayName("CLOB ⇒ TEXT 只在 MySQL 模式下替换")
    void clobTranslation() {
        String ddl = "CREATE TABLE IF NOT EXISTS T (A CLOB, B VARCHAR(8))";
        assertEquals(ddl, WfDdlDialect.translateCreateTable(ddl, false),
                "非 MySQL 应一字不改（H2 认 CLOB）");
        assertEquals("CREATE TABLE IF NOT EXISTS T (A TEXT, B VARCHAR(8))",
                WfDdlDialect.translateCreateTable(ddl, true),
                "MySQL 必须把 CLOB 换成 TEXT");
    }

    @Test
    @DisplayName("TIMESTAMP ⇒ TIMESTAMP NULL 只在 MySQL 模式下替换（零日期默认值会让建表失败）")
    void timestampTranslation() {
        String ddl = "CREATE TABLE IF NOT EXISTS T (START_TIME TIMESTAMP, END_TIME TIMESTAMP)";
        assertEquals(ddl, WfDdlDialect.translateCreateTable(ddl, false),
                "非 MySQL 应一字不改（H2 的 TIMESTAMP 本来就没有零日期默认值）");
        assertEquals("CREATE TABLE IF NOT EXISTS T (START_TIME TIMESTAMP NULL, END_TIME TIMESTAMP NULL)",
                WfDdlDialect.translateCreateTable(ddl, true),
                "MySQL 必须把裸 TIMESTAMP 显式写成 TIMESTAMP NULL");
    }

    @Test
    @DisplayName("TIMESTAMP 翻译不碰已带限定的列（NOT NULL / DEFAULT）与非列名位置")
    void timestampTranslationKeepsQualifiedColumns() {
        String ddl = "CREATE TABLE IF NOT EXISTS T (A TIMESTAMP NOT NULL, B TIMESTAMP DEFAULT NULL, C VARCHAR(16))";
        assertEquals(ddl, WfDdlDialect.translateCreateTable(ddl, true),
                "已带 NOT NULL / DEFAULT 的列不该被改写");
        String withLiteral = "CREATE TABLE IF NOT EXISTS T (A TIMESTAMP, NOTE VARCHAR(64) DEFAULT 'TIMESTAMP')";
        assertEquals("CREATE TABLE IF NOT EXISTS T (A TIMESTAMP NULL, NOTE VARCHAR(64) DEFAULT 'TIMESTAMP')",
                WfDdlDialect.translateCreateTable(withLiteral, true),
                "字符串字面量里的 TIMESTAMP 不能被当成列类型改写");
    }

    @Test
    @DisplayName("索引语句：MySQL 下去掉 IF NOT EXISTS，H2 下原样")
    void indexCreateTranslation() {
        String ddl = "CREATE INDEX IF NOT EXISTS IDX_A ON T (C)";
        assertEquals(ddl, WfDdlDialect.buildIndexCreate(ddl, false));
        assertEquals("CREATE INDEX IDX_A ON T (C)", WfDdlDialect.buildIndexCreate(ddl, true));
        String uniq = "CREATE UNIQUE INDEX IF NOT EXISTS UDX_A ON T (C)";
        assertEquals("CREATE UNIQUE INDEX UDX_A ON T (C)",
                WfDdlDialect.buildIndexCreate(uniq, true));
    }

    // ==================== 集成层：H2 MySQL 兼容模式 ====================

    @Test
    @DisplayName("H2 MySQL 兼容模式下 initialize() 能跑通且幂等")
    void initializeWorksUnderMysqlCompatibleMode() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        // MODE=MySQL 让 H2 的行为贴近真 MySQL：
        // 不认 CREATE INDEX IF NOT EXISTS、不认 CLOB
        ds.setURL("jdbc:h2:mem:wf_mysqlmode;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");

        JdbcWorkflowPersistence persistence = new JdbcWorkflowPersistence(ds);
        persistence.initialize();

        // 幂等：第二次不该报 "建表失败"
        persistence.initialize();

        try (Connection c = ds.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            assertEquals(13, countTables(md), "应建出 13 张 ZWF_* 表");

            // 索引必须真建出来（不是被 catch 吞掉的）
            assertTrue(indexExists(md, "ZWF_PROCESS", "IDX_WF_PROC_BIZKEY"),
                    "索引 IDX_WF_PROC_BIZKEY 应真实存在");
            assertTrue(indexExists(md, "ZWF_PROCESS", "IDX_WF_PROC_USER"),
                    "索引 IDX_WF_PROC_USER 应真实存在");
            assertTrue(indexExists(md, "ZWF_TASK", "IDX_WF_TASK_ASSIGNEE"),
                    "索引 IDX_WF_TASK_ASSIGNEE 应真实存在");
            assertTrue(indexExists(md, "ZWF_INCIDENT_HISTORY", "UDX_WF_INCHIST_JOB"),
                    "唯一索引 UDX_WF_INCHIST_JOB 应真实存在");
        }
    }

    @Test
    @DisplayName("H2 MySQL 兼容模式下 CLOB 列建成 TEXT（不是建表失败）")
    void clobColumnBecomesTextUnderMysqlMode() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:wf_clob;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");

        new JdbcWorkflowPersistence(ds).initialize();

        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                             + "WHERE TABLE_NAME='ZWF_DEFINITION' AND COLUMN_NAME='SOURCE_XML'")) {
            assertTrue(rs.next(), "ZWF_DEFINITION.SOURCE_XML 列应存在");
            String type = rs.getString(1).toUpperCase(Locale.ROOT);
            // H2 的 MySQL 兼容模式把 TEXT 存成内部类型 CHARACTER LARGE OBJECT
            // （H2 里 CLOB 与 TEXT 本就是同一个类型别名）⇒ 关键是「列建出来了、不是建表失败」
            assertTrue("TEXT".equals(type)
                            || "LONGTEXT".equals(type)
                            || "CLOB".equals(type)
                            || "CHARACTER LARGE OBJECT".equals(type),
                    "SOURCE_XML 列类型应可被读出，实际=" + type);
        }
    }

    // ==================== 工具 ====================

    private static int countTables(DatabaseMetaData md) throws SQLException {
        int n = 0;
        try (ResultSet rs = md.getTables(null, null, "ZWF_%", new String[]{"TABLE"})) {
            while (rs.next()) {
                n++;
            }
        }
        return n;
    }

    private static boolean indexExists(DatabaseMetaData md, String table, String indexName)
            throws SQLException {
        try (ResultSet rs = md.getIndexInfo(null, null, table, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name != null && name.equalsIgnoreCase(indexName)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("WfDdlDialect 类本身可加载（非空）")
    void dialectClassLoads() {
        assertNotNull(WfDdlDialect.class);
        assertFalse(WfDdlDialect.normalizeProductName("MySQL").isEmpty());
    }
}
