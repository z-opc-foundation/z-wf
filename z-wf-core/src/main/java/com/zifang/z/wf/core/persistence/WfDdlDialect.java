package com.zifang.z.wf.core.persistence;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

/**
 * DDL 方言适配 —— 让同一套 DDL 能在 H2 与 MySQL 上都跑。
 *
 * <p><b>为什么需要它</b>：{@link JdbcWorkflowPersistence#initialize()} 里的建表语句
 * 原本按 H2 写（{@code CREATE INDEX IF NOT EXISTS} 与 {@code CLOB} 都是 H2 扩展语法），
 * 而 {@code application.properties} 的默认数据源却是 MySQL JDBC URL。
 * ⇒ 这两条路<b>从未同时成立过</b>：H2 跑得通（全部单测都建在 H2 上），
 * MySQL 则在第一条 {@code CLOB} 上就报语法错，整个容器起不来。
 *
 * <p><b>实测</b>（MySQL 8.0.36 实跑）：
 * <ul>
 *   <li>{@code CLOB} ⇒ MySQL 8.0 <b>语法错</b>（{@code ERROR 1064}）；等价写法是 {@code TEXT}。</li>
 *   <li>{@code CREATE INDEX IF NOT EXISTS} ⇒ MySQL 8.0 <b>语法错</b>（{@code ERROR 1064}）；
 *       MySQL 只支持 {@code CREATE [UNIQUE] INDEX name ON ...}，且<b>没有</b> IF NOT EXISTS。</li>
 * </ul>
 *
 * <p><b>本类的策略是「翻译」而不是「二选一」</b>：
 * 保持 H2 侧一字不改（现有单测全部跑在 H2 上，改 H2 路径等于改已验证行为），
 * 只在识别为 MySQL 时把两条语法翻译成 MySQL 能吃的写法。
 *
 * <p><b>为什么索引用「先查后建」而不是「捕获异常忽略」</b>：
 * MySQL 没有 {@code CREATE INDEX IF NOT EXISTS}，而「索引已存在」在 MySQL 上是
 * {@code ERROR 1061 Duplicate key name}。直接 catch 忽略会把<b>其它</b>建索引失败
 * （如列名写错）一起吞掉 ⇒ 索引悄悄没建 ⇒ 后续查询全表扫而没有任何报错。
 * 正确做法是<b>先查 {@code DatabaseMetaData.getIndexInfo}</b>，不存在才建 ——
 * 「已存在」与「建失败」因此走两条不同的路，不会混淆。
 */
final class WfDdlDialect {

    /** MySQL 家族的产品名关键字（大小写无关）。 */
    private static final String MYSQL = "mysql";

    private WfDdlDialect() {
    }

    /**
     * 识别数据源产品名。
     *
     * @param productName JDBC {@code DatabaseMetaData.getDatabaseProductName()}
     * @return 归一化后的小写产品名；识别不出时返回空串（按标准 SQL 处理）
     */
    static String normalizeProductName(String productName) {
        if (productName == null) {
            return "";
        }
        return productName.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 判断是否为 MySQL 家族（H2 的 MySQL 兼容模式不算 —— 那种模式仍支持原语法）。
     */
    static boolean isMySQL(String normalizedProductName) {
        return normalizedProductName != null && normalizedProductName.contains(MYSQL);
    }

    /**
     * 从 {@link DatabaseMetaData} 读产品名。
     *
     * <p>读失败时返回空串（⇒ 按标准 SQL 处理），**不抛异常**：
     * 识别不出方言顶多让 DDL 走标准路径，不该把整个引擎的启动打断。
     */
    static String detectProductName(DatabaseMetaData metaData) {
        if (metaData == null) {
            return "";
        }
        try {
            return normalizeProductName(metaData.getDatabaseProductName());
        } catch (SQLException e) {
            return "";
        }
    }

    /**
     * 把一条建表语句翻译成目标方言可执行的写法。
     *
     * <p>只处理两处已知不兼容点，其余语句原样返回：
     * <ul>
     *   <li>{@code CLOB} ⇒ {@code TEXT}（MySQL 把 CLOB 当 TEXT 的同义词，但 8.0 的
     *       解析器在 {@code CLOB} 上直接报 1064 —— 不能依赖同义词，必须换词）。</li>
     * </ul>
     *
     * <p>索引语句不在这里翻译 —— 见 {@link #isIndexCreate} 与
     * {@link #buildIndexCreate}：MySQL 需要「先查后建」两段式，无法靠字符串替换完成。
     */
    static String translateCreateTable(String sql, boolean mysql) {
        if (sql == null || !mysql) {
            return sql;
        }
        // 只替换列定义里的 CLOB 类型；不动字符串字面量里的 "CLOB"
        return sql.replace(" CLOB,", " TEXT,").replace(" CLOB)", " TEXT)");
    }

    /**
     * 判断一条 DDL 是否是「建索引」语句。
     *
     * <p>用于把索引从「批量 execute」里分出来，走「先查后建」的独立路径。
     */
    static boolean isIndexCreate(String sql) {
        if (sql == null) {
            return false;
        }
        String s = sql.trim().toUpperCase(Locale.ROOT);
        return s.startsWith("CREATE INDEX") || s.startsWith("CREATE UNIQUE INDEX");
    }

    /**
     * 从建索引语句里取出索引名。
     *
     * <p>支持两种写法：
     * <pre>
     *   CREATE INDEX IF NOT EXISTS IDX_X ON T (C)        -- H2
     *   CREATE UNIQUE INDEX IF NOT EXISTS UDX_X ON T (C)  -- H2
     * </pre>
     *
     * <p>剥除顺序：{@code CREATE} → {@code UNIQUE} → {@code INDEX} → {@code IF NOT EXISTS}
     * ⇒ 剩下裸索引名。
     *
     * @return 索引名；解析不出时返回 {@code null}
     */
    static String extractIndexName(String sql) {
        if (sql == null) {
            return null;
        }
        String s = sql.trim();
        String upper = s.toUpperCase(Locale.ROOT);
        int onIdx = upper.indexOf(" ON ");
        if (onIdx < 0) {
            return null;
        }
        String head = s.substring(0, onIdx).trim();
        // 逐级剥除前缀：CREATE → [UNIQUE] → INDEX → [IF NOT EXISTS]
        if (head.toUpperCase(Locale.ROOT).startsWith("CREATE ")) {
            head = head.substring("CREATE ".length()).trim();
        }
        if (head.toUpperCase(Locale.ROOT).startsWith("UNIQUE ")) {
            head = head.substring("UNIQUE ".length()).trim();
        }
        if (head.toUpperCase(Locale.ROOT).startsWith("INDEX ")) {
            head = head.substring("INDEX ".length()).trim();
        }
        if (head.toUpperCase(Locale.ROOT).startsWith("IF NOT EXISTS ")) {
            head = head.substring("IF NOT EXISTS ".length()).trim();
        }
        return head.isEmpty() ? null : head;
    }

    /**
     * 生成 MySQL 可执行的建索引语句（去掉 IF NOT EXISTS）。
     *
     * <p>MySQL 8.0 <b>不支持</b> {@code CREATE INDEX IF NOT EXISTS}，
     * 「已存在」要靠调用方先查元数据来判（{@link #indexExists}）。
     */
    static String buildIndexCreate(String sql, boolean mysql) {
        if (sql == null) {
            return null;
        }
        if (!mysql) {
            return sql;
        }
        // CREATE [UNIQUE] INDEX IF NOT EXISTS name  →  CREATE [UNIQUE] INDEX name
        return sql.replace("INDEX IF NOT EXISTS ", "INDEX ");
    }

    /**
     * 判断索引是否已存在。
     *
     * <p>用 {@code DatabaseMetaData.getIndexInfo} 查，**不靠捕获异常**：
     * MySQL 上「索引已存在」是 1061 Duplicate key name，而「建索引失败」可能是
     * 1064 语法错或 1072 列不存在。两者若都 catch 忽略 ⇒ 建索引真的失败时
     * 没有任何提示，索引悄悄缺失，之后所有相关查询退化成全表扫。
     *
     * @param metaData     连接元数据
     * @param catalog      目录（{@code null} = 当前）
     * @param schema       schema（{@code null} = 当前）
     * @param table        表名
     * @param indexName    索引名
     * @return 存在返回 true；查不了（元数据异常）返回 false（⇒ 尝试创建，让真实错误浮出来）
     */
    static boolean indexExists(DatabaseMetaData metaData,
                               String catalog,
                               String schema,
                               String table,
                               String indexName) {
        if (metaData == null || table == null || indexName == null) {
            return false;
        }
        try (java.sql.ResultSet rs = metaData.getIndexInfo(catalog, schema, table, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name != null && name.equalsIgnoreCase(indexName)) {
                    return true;
                }
            }
            return false;
        } catch (SQLException e) {
            // 查不了就当不存在 ⇒ 会去建 ⇒ 若真已存在会在 DDL 处得到明确错误，
            // 好过「静默吞掉一个真的建索引失败」
            return false;
        }
    }
}
