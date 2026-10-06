package com.zifang.z.wf.core.persistence;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonArray;
import com.zifang.util.json.model.JsonObject;
import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.dmn.WfDmnDecision;
import com.zifang.z.wf.core.definition.dmn.WfDmnDecision;
import com.zifang.z.wf.core.definition.WfFlow;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfFilter;
import com.zifang.z.wf.core.model.WfFilterType;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;

/**
 * JDBC 持久化实现 —— 生产路径。
 *
 * <p><b>表结构（6 张）</b>：
 * <pre>
 *   ZWF_DEFINITION      流程定义（含版本）
 *   ZWF_PROCESS         流程实例
 *   ZWF_EXECUTION       执行令牌
 *   ZWF_TASK            任务
 *   ZWF_ACTIVITY        活动历史
 *   ZWF_COMMENT         评论
 *   ZWF_JOB             job（定时器边界事件）
 * </pre>
 *
 * <p><b>三条设计决策</b>：
 * <ol>
 *   <li><b>复合主键含 revision</b>（{@code UPDATE ... WHERE id=? AND revision=?}）：
 *       乐观锁靠它兜底。0 行受影响就抛 {@link WfOptimisticLockException}，
 *       让上层重试 —— 绝不静默覆盖并发审批人的操作。</li>
 *   <li><b>变量/扩展属性存 JSON 文本</b>：审批变量是动态 schema 的
 *       （今天有 days、明天加 amount），建列就等于给自己挖迁移坑。
 *       代价是按变量查询要全表扫，所以<b>不提供</b>按变量过滤的查询 —— 需要时
 *       业务方应在流程启动时把要查的值抄进 {@code business_key} 或专门的检索列。</li>
 *   <li><b>时间存 TIMESTAMP</b>：{@link Date} 直接映射，避免 epoch-millis 的
 *       时区歧义与可读性问题。（这正是内存实现踩过的 JsonUtil Date 坑的正解。）</li>
 * </ol>
 *
 * <p><b>DDL 兼容性</b>：{@link #initialize()} 内建表，用的是 ANSI SQL 通用子集
 * （{@code VARCHAR/TEXT/TIMESTAMP/INTEGER/BIGINT/CLOB}），MySQL 与 H2 都能跑。
 * 表名加 {@code ZWF_} 前缀避开常见保留字。
 *
 * @author zifang
 */
public class JdbcWorkflowPersistence implements WfPersistence {

    private static final Logger log = LoggerFactory.getLogger(JdbcWorkflowPersistence.class);

    private final DataSource dataSource;

    public JdbcWorkflowPersistence(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource 不能为 null");
        }
        this.dataSource = dataSource;
    }

    // ==================== 生命周期 ====================

    @Override
    public void initialize() {
        List<String> ddl = new ArrayList<>();
        ddl.add("CREATE TABLE IF NOT EXISTS ZWF_DEFINITION ("
                + "DEF_KEY VARCHAR(128) NOT NULL,"
                + "DEF_VERSION INTEGER NOT NULL,"
                + "DEF_NAME VARCHAR(512),"
                + "DEF_CATEGORY VARCHAR(128),"
                + "DEF_DESCRIPTION VARCHAR(2048),"
                + "DEF_GRAPH TEXT,"
                + "SOURCE_XML CLOB,"
                + "DEPLOY_TIME TIMESTAMP,"
                + "SUSPENDED INTEGER NOT NULL DEFAULT 0,"
                + "IS_DEFAULT INTEGER NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (DEF_KEY, DEF_VERSION))");

        ddl.add("CREATE TABLE IF NOT EXISTS ZWF_PROCESS ("
                + "PROC_ID VARCHAR(128) NOT NULL,"
                + "DEF_KEY VARCHAR(128),"
                + "DEF_ID VARCHAR(256),"
                + "DEF_VERSION INTEGER,"
                + "BUSINESS_KEY VARCHAR(256),"
                + "NAME VARCHAR(512),"
                + "START_USER_ID VARCHAR(128),"
                + "START_DEPT_ID VARCHAR(128),"
                + "CATEGORY VARCHAR(128),"
                + "STATUS VARCHAR(32),"
                + "RESULT VARCHAR(64),"
                + "START_TIME TIMESTAMP,"
                + "END_TIME TIMESTAMP,"
                + "SUSPEND_REASON VARCHAR(1024),"
                + "DELETE_REASON VARCHAR(2048),"
                + "VARIABLES TEXT,"
                + "REVISION INTEGER NOT NULL,"
                + "PRIMARY KEY (PROC_ID))");

        ddl.add("CREATE TABLE IF NOT EXISTS ZWF_EXECUTION ("
                + "EXEC_ID VARCHAR(128) NOT NULL,"
                + "PROC_ID VARCHAR(128) NOT NULL,"
                + "PARENT_ID VARCHAR(128),"
                + "ACTIVITY_ID VARCHAR(128),"
                + "STATE VARCHAR(16),"
                + "IS_CHILD INTEGER,"
                + "ENTERED_TIME TIMESTAMP,"
                + "VARIABLES TEXT,"
                + "ARRIVED TEXT,"
                + "PRIMARY KEY (EXEC_ID))");

        ddl.add("CREATE TABLE IF NOT EXISTS ZWF_TASK ("
                + "TASK_ID VARCHAR(128) NOT NULL,"
                + "PROC_ID VARCHAR(128),"
                + "EXEC_ID VARCHAR(128),"
                + "DEF_ID VARCHAR(128),"
                + "TASK_NAME VARCHAR(512),"
                + "TASK_TYPE VARCHAR(64),"
                + "FORM_KEY VARCHAR(128),"
                + "CATEGORY VARCHAR(128),"
                + "ASSIGNEE VARCHAR(128),"
                + "OWNER VARCHAR(128),"
                + "CANDIDATE_USERS TEXT,"
                + "CANDIDATE_GROUPS TEXT,"
                + "STATUS VARCHAR(16),"
                + "PRIORITY INTEGER,"
                + "CREATE_TIME TIMESTAMP,"
                + "DUE_DATE TIMESTAMP,"
                + "END_TIME TIMESTAMP,"
                + "COMPLETER_ID VARCHAR(128),"
                + "COMMENT_TEXT VARCHAR(2048),"
                + "VARIABLES TEXT,"
                // 委派链（JSON 数组）。必须单独一列而不是塞进 VARIABLES：
                // VARIABLES 是业务变量命名空间，会原样透出给前端（/processes/get 返回它），
                // 把审计数据混进去会污染业务方看到的变量集合。
                // 漏了这一列的症状：delegate() 在内存里链是全的，但任务一旦从库里重读
                // （而每次操作都会重读）链就空了 —— 审计能力看着在、实际不存。
                + "DELEGATE_CHAIN TEXT,"
                + "PARENT_TASK_ID VARCHAR(128),"
                // 挂起状态。默认 0 = 未挂起：存量行升级后行为不变
                + "SUSPENDED INTEGER NOT NULL DEFAULT 0,"
                + "REVISION INTEGER NOT NULL,"
                + "PRIMARY KEY (TASK_ID))");

        ddl.add("CREATE TABLE IF NOT EXISTS ZWF_ACTIVITY ("
                + "ACT_ID VARCHAR(128) NOT NULL,"
                + "PROC_ID VARCHAR(128) NOT NULL,"
                + "DEF_KEY VARCHAR(128),"
                + "ACTIVITY_ID VARCHAR(128),"
                + "ACTIVITY_NAME VARCHAR(512),"
                + "ACTIVITY_TYPE VARCHAR(64),"
                + "EXEC_ID VARCHAR(128),"
                + "ASSIGNEE VARCHAR(128),"
                + "DURATION_MS BIGINT,"
                + "START_TIME TIMESTAMP,"
                + "END_TIME TIMESTAMP,"
                + "OUTCOME VARCHAR(1024),"
                + "DETAIL VARCHAR(2048),"
                + "VARIABLES TEXT,"
                + "PRIMARY KEY (ACT_ID))");

        ddl.add("CREATE TABLE IF NOT EXISTS ZWF_COMMENT ("
                + "CMT_ID VARCHAR(128) NOT NULL,"
                + "PROC_ID VARCHAR(128) NOT NULL,"
                + "TASK_ID VARCHAR(128),"
                + "USER_ID VARCHAR(128),"
                + "CMT_TYPE VARCHAR(64),"
                + "CONTENT VARCHAR(2048),"
                + "CMT_TIME TIMESTAMP,"
                + "PRIMARY KEY (CMT_ID))");

        ddl.add("CREATE TABLE IF NOT EXISTS ZWF_JOB ("
                + "JOB_ID VARCHAR(128) NOT NULL,"
                + "PROC_ID VARCHAR(128) NOT NULL,"
                + "EXEC_ID VARCHAR(128),"
                + "ELEMENT_ID VARCHAR(128),"
                + "ATTACHED_TO VARCHAR(128),"
                // 显式记种类。靠 duedate 为空来区分消息/信号订阅是个隐式约定，
                // 任何人写 saveJob 都可能漏 —— 漏了的消息订阅会被扫描器当成到期 job
                // 消费掉，表现为"还没发消息流程自己往前走了"
                + "JOB_TYPE VARCHAR(16) NOT NULL DEFAULT 'TIMER',"
                // 外部任务用：worker 按 TOPIC 领活，LOCKED_BY/LOCK_AT 是租约
                + "TOPIC VARCHAR(128),"
                + "LOCKED_BY VARCHAR(128),"
                + "LOCK_AT TIMESTAMP,"
                + "DUEDATE TIMESTAMP,"
                + "RETRIES INT,"
                // 循环定时器已响过几次。与 RETRIES 分列的理由见 WfJob#cycleIndex
                + "CYCLE_INDEX INT,"
                + "EXCEPTION_MSG VARCHAR(2048),"
                // 订阅型 job 在等什么。与 EXCEPTION_MSG 分列：排障视图要同时
                // 看到「在等什么」与「错在哪」，挤在一列只能二选一（见 WfJob#subscriptionName）
                + "SUBSCRIPTION_NAME VARCHAR(128),"
                + "CREATE_TIME TIMESTAMP,"
                + "LAST_FAIL_TIME TIMESTAMP,"
                + "REV INT,"
                + "PRIMARY KEY (JOB_ID))");

        // 审批中心的三条主查询路径都走这些索引
        ddl.add("CREATE INDEX IF NOT EXISTS IDX_WF_PROC_BIZKEY ON ZWF_PROCESS (BUSINESS_KEY)");
        ddl.add("CREATE INDEX IF NOT EXISTS IDX_WF_PROC_USER ON ZWF_PROCESS (START_USER_ID)");
        ddl.add("CREATE INDEX IF NOT EXISTS IDX_WF_PROC_STATUS ON ZWF_PROCESS (STATUS)");
        ddl.add("CREATE INDEX IF NOT EXISTS IDX_WF_EXEC_PROC ON ZWF_EXECUTION (PROC_ID)");
        ddl.add("CREATE INDEX IF NOT EXISTS IDX_WF_TASK_ASSIGNEE ON ZWF_TASK (ASSIGNEE, STATUS)");
        ddl.add("CREATE INDEX IF NOT EXISTS IDX_WF_TASK_OWNER ON ZWF_TASK (OWNER, STATUS)");
        ddl.add("CREATE INDEX IF NOT EXISTS IDX_WF_TASK_COMPLETER ON ZWF_TASK (COMPLETER_ID)");
        ddl.add("CREATE INDEX IF NOT EXISTS IDX_WF_TASK_PROC ON ZWF_TASK (PROC_ID, STATUS)");
        ddl.add("CREATE INDEX IF NOT EXISTS IDX_WF_ACT_PROC ON ZWF_ACTIVITY (PROC_ID, START_TIME)");
        ddl.add("CREATE INDEX IF NOT EXISTS IDX_WF_CMT_PROC ON ZWF_COMMENT (PROC_ID)");
        // 执行器每轮都按"到期时刻 <= now"扫，DUEDATE 上没有索引就是全表扫
        ddl.add("CREATE INDEX IF NOT EXISTS IDX_WF_JOB_DUEDATE ON ZWF_JOB (DUEDATE)");
        ddl.add("CREATE INDEX IF NOT EXISTS IDX_WF_JOB_PROC ON ZWF_JOB (PROC_ID)");
        ddl.add("CREATE INDEX IF NOT EXISTS IDX_WF_JOB_EXEC ON ZWF_JOB (EXEC_ID)");

        // 筛选器。整张表都是新增的，不需要补列 ——
        // CREATE TABLE IF NOT EXISTS 对"从来没有这张表"的库直接建，
        // 对"已经有"的库原样跳过，两条路径都安全
        ddl.add("CREATE TABLE IF NOT EXISTS ZWF_FILTER ("
                + "FILTER_ID VARCHAR(128) NOT NULL,"
                + "NAME VARCHAR(256) NOT NULL,"
                // 类型存短名（task / processInstance / incident）而不是枚举全名：
                // 存储列宽只有 VARCHAR(16)，而且短名是 REST 层对外的那一套，
                // 两处用同一个词才不会出现"库里写的和接口里填的对不上"
                + "RESOURCE_TYPE VARCHAR(16) NOT NULL,"
                + "OWNER_ID VARCHAR(128),"
                // 条件整体存一列 JSON。逐列存要跟着每个查询类加字段，
                // 而筛选器的本质就是"一组会变的条件"，它的形状还没稳定下来
                + "PROPERTIES TEXT,"
                + "CREATE_TIME TIMESTAMP,"
                + "UPDATE_TIME TIMESTAMP,"
                + "REV INT,"
                + "PRIMARY KEY (FILTER_ID))");
        ddl.add("CREATE INDEX IF NOT EXISTS IDX_WF_FILTER_OWNER ON ZWF_FILTER (OWNER_ID)");
        ddl.add("CREATE INDEX IF NOT EXISTS IDX_WF_FILTER_TYPE ON ZWF_FILTER (RESOURCE_TYPE)");

        // ---- DMN 决策 ----
        // 决策表结构存一列 JSON（TABLE_JSON），与流程定义把图存进 DEF_GRAPH 同一做法：
        // 决策表是"一组会变的列与规则"，逐列拆成关系表要跟着每种 HitPolicy 改 schema，
        // 而它只会被整体读一次、不会按列查。
        //
        // HIT_POLICY 另存一列：它是运维第一眼要看的东西（这张表允许多条命中吗），
        // 解 JSON 才能看到这一点就太不划算。与 ZWF_FILTER.RESOURCE_TYPE 同理 ——
        // 库里写的和接口里填的必须是同一个词。
        ddl.add("CREATE TABLE IF NOT EXISTS ZWF_DECISION ("
                + "DECISION_KEY VARCHAR(128) NOT NULL,"
                + "DECISION_VERSION INT NOT NULL,"
                + "DECISION_NAME VARCHAR(256),"
                + "HIT_POLICY VARCHAR(16),"
                + "TABLE_JSON TEXT,"
                + "DMN_XML TEXT,"
                + "DEPLOY_TIME TIMESTAMP,"
                + "PRIMARY KEY (DECISION_KEY, DECISION_VERSION))");

        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            try {
                for (String sql : ddl) {
                    statement.execute(sql);
                }
            } finally {
                statement.close();
            }
            // 表数自己数，而不是 ddl.size() 减一个写死的偏移：
            // 那个偏移是当初按"只有索引没有表"倒推出来的，
            // 每加一张表就得记得改一次，忘了就只是日志里少一个数字 —— 不报错
            int tableCount = 0;
            for (String sql : ddl) {
                if (sql.startsWith("CREATE TABLE")) {
                    tableCount++;
                }
            }
            log.info("JDBC 持久化初始化完成（{} 张表，{} 条索引）", tableCount, ddl.size() - tableCount);
            addDelegateChainColumnIfMissing(connection);
            addProcessNameColumnIfMissing(connection);
            addTaskSuspendedColumnIfMissing(connection);
            addJobTypeColumnIfMissing(connection);
            addExternalTaskColumnsIfMissing(connection);
            addSuspendedColumnIfMissing(connection);
            addDefaultColumnIfMissing(connection);
        } catch (SQLException e) {
            throw new WfPersistenceException("建表失败", e);
        } finally {
            close(connection);
        }
    }

    /**
     * 给已存在的 ZWF_TASK 补 DELEGATE_CHAIN 列。
     *
     * <p>{@code CREATE TABLE IF NOT EXISTS} <b>不会</b>给已存在的表补列。
     * 2.0.0 之前建过 dev 库（ZWF_TASK 里没有这一列）的实例，升级后
     * 每一句 INSERT/UPDATE/SELECT 都会报 column not found —— 表现为"引擎启动即炸"，
     * 而不是某个功能悄悄坏掉。
     *
     * <p>用 try-catch 而不是 {@code ADD COLUMN IF NOT EXISTS}：H2 / PostgreSQL 支持该语法，
     * MySQL 8 不支持。跨库兼容下只能"试一下，失败说明已存在或方言不支持"。
     * 这里失败是安全的：列已存在时报的是重复列错误，忽略即可；
     * 真正缺列的话后续 SQL 会显式报错，不会静默丢数据。
     */
    private void addDelegateChainColumnIfMissing(Connection connection) {        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.execute("ALTER TABLE ZWF_TASK ADD COLUMN DELEGATE_CHAIN TEXT");
            log.info("已为既有 ZWF_TASK 补建 DELEGATE_CHAIN 列");
        } catch (SQLException e) {
            // 列已存在（或方言不支持该写法）——正常路径
            log.debug("DELEGATE_CHAIN 列已存在或无需补建: {}", e.getMessage());
        } finally {
            closeQuietly(statement);
        }
    }

    /**
     * 给已存在的 ZWF_TASK 补 SUSPENDED 列，理由同 {@link #addDelegateChainColumnIfMissing}。
     *
     * <p>存量行默认 0 = 未挂起，即升级前后行为完全一致 —— 挂起是新增能力，
     * 不该让任何存量待办在升级后突然不能办。
     */
    /**
     * 给已存在的 ZWF_JOB 补外部任务三列。
     *
     * <p>合成一条 ALTER：逐条 ADD 在大表上会拿三次表锁。
     */
    private void addExternalTaskColumnsIfMissing(Connection connection) {
        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.execute("ALTER TABLE ZWF_JOB ADD COLUMN TOPIC VARCHAR(128)");
            log.info("已为既有 ZWF_JOB 补建 TOPIC 列");
        } catch (SQLException e) {
            log.debug("TOPIC 列已存在或无需补建: {}", e.getMessage());
        } finally {
            closeQuietly(statement);
        }
        addJobColumnIfMissing(connection, "LOCKED_BY VARCHAR(128)");
        addJobColumnIfMissing(connection, "LOCK_AT TIMESTAMP");
        // 拆列不是新增信息：老库里订阅名就存在 EXCEPTION_MSG 里。
        // 补列时顺手搬过来，否则升级之后老的订阅型 job 全部匹配不上事件 ——
        // 症状是"部署升级后所有等消息的流程都不再被触发"，且没有任何报错
        addJobColumnIfMissing(connection, "SUBSCRIPTION_NAME VARCHAR(128)");
        backfillSubscriptionName(connection);
        // 循环定时器已响过几次。缺这一列的后果比订阅名还隐蔽：
        // 老库里全是 NULL，而读出来是 0 —— 对非循环 job 恰好是对的值，
        // 对循环 job 则是「从头响过 0 次」，于是 R3/PT1H 会一直以为还能再响两次。
        // 所以必须补列，而不是靠 NULL 兜。
        addJobColumnIfMissing(connection, "CYCLE_INDEX INT");
    }

    /**
     * 把老库 {@code EXCEPTION_MSG} 里的订阅名搬到 {@code SUBSCRIPTION_NAME}。
     *
     * <p>不做这一步的代价很具体：升级之后，存量订阅型 job 的订阅名留在旧列里，
     * 而匹配逻辑已经改读新列 —— 于是<b>所有等消息/等信号的流程都不再被触发</b>，
     * 且没有任何报错，只是流程静静地停在原地。
     *
     * <p>限定在这四个类型上：只有它们当初把订阅名写进了 {@code EXCEPTION_MSG}。
     * {@code TIMER} / {@code EXTERNAL} / {@code ASYNC_*} 那一列存的是失败原因，
     * 搬过去等于把一句报错当成订阅名。虽然匹配不上任何事件名（与升级前同样匹配不上），
     * 但那会让排障视图显示出一个莫名其妙的"订阅名"，比空值更难解释。
     *
     * <p>已知的历史遗留：若某个订阅型 job 在升级前就已失败，它的订阅名当时
     * 已被失败原因覆盖，搬过来的会是那句报错。这种行本来就匹配不上了，
     * 搬家不改变它的行为，只是不让它继续伪装成订阅名。
     */
    private void backfillSubscriptionName(Connection connection) {
        Statement statement = null;
        try {
            statement = connection.createStatement();
            int moved = statement.executeUpdate(
                    "UPDATE ZWF_JOB SET SUBSCRIPTION_NAME = EXCEPTION_MSG "
                            + "WHERE JOB_TYPE IN ('MESSAGE','SIGNAL','EVENT_MESSAGE','EVENT_SIGNAL','EVENT_TIMER') "
                            + "AND SUBSCRIPTION_NAME IS NULL AND EXCEPTION_MSG IS NOT NULL");
            if (moved > 0) {
                log.info("已把 {} 条存量订阅的等待事件名搬到 SUBSCRIPTION_NAME 列", moved);
            }
        } catch (SQLException e) {
            // 搬不动不是致命错误：新流程照常工作，只是升级前建的订阅仍匹配不上，
            // 与升级前的行为一致。不能因此让整个 initialize 失败
            log.warn("存量订阅名回填失败，升级前创建的消息/信号订阅将仍匹配不到事件: {}",
                    e.getMessage());
        } finally {
            closeQuietly(statement);
        }
    }

    private void addJobColumnIfMissing(Connection connection, String column) {
        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.execute("ALTER TABLE ZWF_JOB ADD COLUMN " + column);
            log.info("已为既有 ZWF_JOB 补建 {} 列", column);
        } catch (SQLException e) {
            log.debug("{} 列已存在或无需补建: {}", column, e.getMessage());
        } finally {
            closeQuietly(statement);
        }
    }

    /**
     * 给已存在的 ZWF_PROCESS 补 NAME 列，理由同 {@link #addDelegateChainColumnIfMissing}。
     *
     * <p>存量行补出来是 NULL，也就是"没起名"—— 与升级前压根没有这个概念等价。
     * 刻意<b>不</b>回填一个占位串（如流程 key）：升级后每条存量单子突然多出一个
     * 看起来像业务数据的标题，而它其实只是引擎编的，比空着更容易误导人。
     *
     * <p>{@code NAME} 在 {@code ZWF_FILTER} 上已经是同名同类型的列，
     * 所以这条 SQL 在方言（保留字、大小写）上不用再冒一次险。
     */
    private void addProcessNameColumnIfMissing(Connection connection) {
        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.execute("ALTER TABLE ZWF_PROCESS ADD COLUMN NAME VARCHAR(512)");
            log.info("已为既有 ZWF_PROCESS 补建 NAME 列");
        } catch (SQLException e) {
            log.debug("NAME 列已存在或无需补建: {}", e.getMessage());
        } finally {
            closeQuietly(statement);
        }
    }

    private void addJobTypeColumnIfMissing(Connection connection) {
        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.execute("ALTER TABLE ZWF_JOB ADD COLUMN JOB_TYPE VARCHAR(16) DEFAULT 'TIMER'");
            log.info("已为既有 ZWF_JOB 补建 JOB_TYPE 列");
        } catch (SQLException e) {
            log.debug("JOB_TYPE 列已存在或无需补建: {}", e.getMessage());
        } finally {
            closeQuietly(statement);
        }
    }

    /** 库里出现未知类型时退回 TIMER 并留下痕迹，不让一条脏数据让整个 job 扫描炸掉。 */
    private WfJobType parseJobType(String raw) {
        try {
            return WfJobType.valueOf(raw);
        } catch (RuntimeException e) {
            log.warn("未知的 job 类型 [{}]，按定时器处理", raw);
            return WfJobType.TIMER;
        }
    }

    private void addTaskSuspendedColumnIfMissing(Connection connection) {
        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.execute("ALTER TABLE ZWF_TASK ADD COLUMN SUSPENDED INTEGER DEFAULT 0");
            log.info("已为既有 ZWF_TASK 补建 SUSPENDED 列");
        } catch (SQLException e) {
            log.debug("SUSPENDED 列已存在或无需补建: {}", e.getMessage());
        } finally {
            closeQuietly(statement);
        }
    }

    /**
     * 给已存在的 ZWF_DEFINITION 补 SUSPENDED 列，理由同 {@link #addDelegateChainColumnIfMissing}。
     *
     * <p>这里<b>不能</b>复用 {@code ADD COLUMN IF NOT EXISTS}：MySQL 8 不支持。
     * 存量行的默认值 0 = 未停用，也就是升级后所有老流程定义仍然可启动 ——
     * 这是正确的默认值，反过来（默认停用）会让升级即停服。
     */
    private void addSuspendedColumnIfMissing(Connection connection) {
        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.execute("ALTER TABLE ZWF_DEFINITION "
                    + "ADD COLUMN SUSPENDED INTEGER DEFAULT 0");
            log.info("已为既有 ZWF_DEFINITION 补建 SUSPENDED 列");
        } catch (SQLException e) {
            log.debug("SUSPENDED 列已存在或无需补建: {}", e.getMessage());
        } finally {
            closeQuietly(statement);
        }
    }

    /**
     * 给已存在的 ZWF_DEFINITION 补 IS_DEFAULT 列，理由同 {@link #addDelegateChainColumnIfMissing}。
     *
     * <p>存量行默认值 0 = 都不是默认，即升级后行为与升级前完全一致 ——
     * 没有默认时 {@code findDefaultDefinition} 返回 {@code null}，
     * 调用方拿到的是「还没设过默认」而不是「默认丢了」。
     */
    private void addDefaultColumnIfMissing(Connection connection) {
        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.execute("ALTER TABLE ZWF_DEFINITION "
                    + "ADD COLUMN IS_DEFAULT INTEGER DEFAULT 0");
            log.info("已为既有 ZWF_DEFINITION 补建 IS_DEFAULT 列");
        } catch (SQLException e) {
            log.debug("IS_DEFAULT 列已存在或无需补建: {}", e.getMessage());
        } finally {
            closeQuietly(statement);
        }
    }

    @Override
    public void clear() {
        String[] tables = {"ZWF_FILTER", "ZWF_JOB", "ZWF_COMMENT", "ZWF_ACTIVITY", "ZWF_TASK",
                "ZWF_EXECUTION", "ZWF_PROCESS", "ZWF_DEFINITION"};
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            try {
                for (String table : tables) {
                    statement.execute("DELETE FROM " + table);
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            throw new WfPersistenceException("清空数据失败", e);
        } finally {
            close(connection);
        }
    }

    // ==================== 保存筛选器 ====================

    @Override
    public void saveFilter(WfFilter filter) {
        boolean exists = exists("SELECT 1 FROM ZWF_FILTER WHERE FILTER_ID=?", filter.getId());
        if (exists) {
            int affected = update("UPDATE ZWF_FILTER SET NAME=?, RESOURCE_TYPE=?, OWNER_ID=?, "
                            + "PROPERTIES=?, UPDATE_TIME=?, REV=? WHERE FILTER_ID=? AND REV=?",
                    filter.getName(), filter.getResourceType().getCode(), filter.getOwner(),
                    JsonUtil.toJson(filter.getProperties()),
                    timestamp(filter.getUpdateTime()),
                    filter.getRevision(), filter.getId(), filter.getRevision() - 1);
            if (affected == 0) {
                throw new WfOptimisticLockException("filter", filter.getId(), filter.getRevision() - 1);
            }
            return;
        }
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ZWF_FILTER (FILTER_ID, NAME, RESOURCE_TYPE, OWNER_ID, PROPERTIES, "
                            + "CREATE_TIME, UPDATE_TIME, REV) VALUES (?,?,?,?,?,?,?,?)");
            try {
                int i = 1;
                ps.setString(i++, filter.getId());
                ps.setString(i++, filter.getName());
                ps.setString(i++, filter.getResourceType().getCode());
                ps.setString(i++, filter.getOwner());
                ps.setString(i++, JsonUtil.toJson(filter.getProperties()));
                ps.setTimestamp(i++, timestamp(filter.getCreateTime()));
                ps.setTimestamp(i++, timestamp(filter.getUpdateTime()));
                ps.setInt(i++, filter.getRevision());
                ps.executeUpdate();
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            throw new WfPersistenceException("保存筛选器失败: " + filter.getId(), e);
        } finally {
            close(connection);
        }
    }

    @Override
    public WfFilter findFilter(String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        return queryOne("SELECT * FROM ZWF_FILTER WHERE FILTER_ID=?", id, filterMapper());
    }

    @Override
    public boolean deleteFilter(String id) {
        if (id == null || id.trim().isEmpty()) {
            return false;
        }
        return update("DELETE FROM ZWF_FILTER WHERE FILTER_ID=?", id) > 0;
    }

    @Override
    public List<WfFilter> queryFilters(WfFilterQuery query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ZWF_FILTER");
        List<Object> args = new ArrayList<>();
        appendFilterFilters(sql, args, query);
        // 创建时间倒序 + id 兜底，与内存实现同一套次序：
        // 两套实现返回顺序不同的话，同一份数据在开发期和生产期翻页的结果就不一样
        sql.append(" ORDER BY CREATE_TIME DESC, FILTER_ID ASC LIMIT ? OFFSET ?");
        args.add(query == null || query.getPageSize() <= 0 ? 50 : query.getPageSize());
        args.add(query == null ? 0
                : (query.normalizedPageNum() - 1) * query.normalizedPageSize());
        return queryList(sql.toString(), args.toArray(), filterMapper());
    }

    @Override
    public int countFilters(WfFilterQuery query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ZWF_FILTER");
        List<Object> args = new ArrayList<>();
        appendFilterFilters(sql, args, query);
        Long count = queryOne(sql.toString(), args.toArray(), COUNT_MAPPER);
        return count == null ? 0 : count.intValue();
    }

    private void appendFilterFilters(StringBuilder sql, List<Object> args, WfFilterQuery query) {
        if (query == null) {
            return;
        }
        List<String> parts = new ArrayList<>();
        if (query.getId() != null && !query.getId().trim().isEmpty()) {
            parts.add("FILTER_ID=?");
            args.add(query.getId());
        }
        if (query.getName() != null && !query.getName().trim().isEmpty()) {
            parts.add("NAME=?");
            args.add(query.getName());
        }
        if (query.getNameLike() != null && !query.getNameLike().trim().isEmpty()) {
            parts.add("LOWER(NAME) LIKE ?");
            args.add("%" + query.getNameLike().toLowerCase() + "%");
        }
        if (query.getResourceType() != null) {
            parts.add("RESOURCE_TYPE=?");
            args.add(query.getResourceType().getCode());
        }
        if (query.getOwner() != null && !query.getOwner().trim().isEmpty()) {
            parts.add("OWNER_ID=?");
            args.add(query.getOwner());
        }
        if (!parts.isEmpty()) {
            sql.append(" WHERE ").append(join(parts, " AND "));
        }
    }

    private RowMapper<WfFilter> filterMapper() {
        return new RowMapper<WfFilter>() {
            @Override
            public WfFilter map(ResultSet rs) throws SQLException {
                WfFilter filter = new WfFilter();
                filter.setId(rs.getString("FILTER_ID"));
                filter.setName(rs.getString("NAME"));
                filter.setResourceType(WfFilterType.parse(rs.getString("RESOURCE_TYPE")));
                filter.setOwner(rs.getString("OWNER_ID"));
                Map<String, Object> raw = fromJsonMap(rs.getString("PROPERTIES"));
                // 条件读回来仍然是**字符串**：存的时候就是字符串，JSON 往返
                // 不会把它变成别的类型，这里只做一次显式收敛，
                // 免得 JSON 里出现数字时 Map 的 value 变成 Integer，
                // 而后面 toString 比较出来还是一样的 —— 那样"存的是不是字符串"
                // 就再也没法验了
                Map<String, String> properties = new TreeMap<String, String>();
                for (Map.Entry<String, Object> entry : raw.entrySet()) {
                    properties.put(entry.getKey(),
                            entry.getValue() == null ? null : String.valueOf(entry.getValue()));
                }
                filter.setProperties(properties);
                filter.setCreateTime(date(rs.getTimestamp("CREATE_TIME")));
                filter.setUpdateTime(date(rs.getTimestamp("UPDATE_TIME")));
                filter.setRevision(rs.getInt("REV"));
                return filter;
            }
        };
    }

    // ==================== 定义 ====================

    @Override
    public void saveDefinition(WfDefinition definition) {
        // 图结构序列化：走专用编解码（不直接 toJson(definition)）
        String graph = WfDefinitionCodec.encode(definition);
        String sql = "INSERT INTO ZWF_DEFINITION "
                + "(DEF_KEY, DEF_VERSION, DEF_NAME, DEF_CATEGORY, DEF_DESCRIPTION, "
                + " DEF_GRAPH, SOURCE_XML, DEPLOY_TIME, SUSPENDED, IS_DEFAULT) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?)";
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            PreparedStatement ps = connection.prepareStatement(sql);
            try {
                ps.setString(1, definition.getKey());
                ps.setInt(2, definition.getVersion());
                ps.setString(3, definition.getName());
                ps.setString(4, definition.getCategory());
                ps.setString(5, definition.getDescription());
                ps.setString(6, graph);
                ps.setString(7, definition.getSourceXml());
                ps.setTimestamp(8, timestamp(definition.getStartTime()));
                ps.setInt(9, definition.isSuspended() ? 1 : 0);
                // 走 deploy 建出来的定义一律不是默认：默认只能由 setDefaultDefinition 置位。
                // 这里若照抄 definition.isDefaultDefinition()，就多出一个能在图 JSON
                // 之外改动默认标记的入口 —— 那正是"列与 JSON 两个真源"的老问题。
                ps.setInt(10, 0);
                ps.executeUpdate();
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            throw new WfPersistenceException("保存流程定义失败: " + definition.getKey(), e);
        } finally {
            close(connection);
        }
    }

    /**
     * 定义的读路径统一走这里。
     *
     * <p><b>为什么不能只 SELECT DEF_GRAPH</b>：图 JSON 里没有 {@code sourceXml} 与
     * {@code startTime}（XML 体积大，不适合塞进图结构）。只取 DEF_GRAPH 的话，
     * 从库里读回来的定义这两个字段恒为 null —— 表现为"getProcessModel 拿不到 XML"
     * 和"部署时间一直是 null"，两处都很难从表结构上看出原因。
     */
    private WfDefinition readDefinition(ResultSet rs) throws SQLException {
        WfDefinition definition = deserializeDefinition(rs.getString("DEF_GRAPH"));
        if (definition == null) {
            return null;
        }
        definition.setSourceXml(rs.getString("SOURCE_XML"));
        Timestamp deployTime = rs.getTimestamp("DEPLOY_TIME");
        definition.setStartTime(deployTime == null ? null : new Date(deployTime.getTime()));
        definition.setSuspended(rs.getInt("SUSPENDED") != 0);
        definition.setDefaultDefinition(rs.getInt("IS_DEFAULT") != 0);
        return definition;
    }

    /**
     * 只带元数据列的查询（不带 DEF_GRAPH），供按分类/停用状态过滤时用。
     *
     * <p><b>目前没有调用方</b>，保留是因为它是「只取元数据」这条路子的现成落点。
     * 留着一个不用的方法不是问题，<b>留着它却让它的列清单与 {@code DEF_SELECT_ALL}
     * 悄悄分叉才是</b>：上面读 {@code IS_DEFAULT}，将来有人给它配一条不带该列的
     * SELECT，结果集里没有这列，{@code getInt} 会抛 SQLException，
     * 症状是「一条查询莫名其妙失败」而不是「少读了某个字段」。
     */
    private WfDefinition readDefinitionMeta(ResultSet rs) throws SQLException {
        WfDefinition definition = new WfDefinition();
        definition.setKey(rs.getString("DEF_KEY"));
        definition.setVersion(rs.getInt("DEF_VERSION"));
        definition.setName(rs.getString("DEF_NAME"));
        definition.setCategory(rs.getString("DEF_CATEGORY"));
        definition.setDescription(rs.getString("DEF_DESCRIPTION"));
        Timestamp deployTime = rs.getTimestamp("DEPLOY_TIME");
        definition.setStartTime(deployTime == null ? null : new Date(deployTime.getTime()));
        definition.setSuspended(rs.getInt("SUSPENDED") != 0);
        definition.setDefaultDefinition(rs.getInt("IS_DEFAULT") != 0);
        return definition;
    }

    private static final String DEF_SELECT_ALL =
            "SELECT DEF_KEY, DEF_VERSION, DEF_GRAPH, SOURCE_XML, DEPLOY_TIME, SUSPENDED, IS_DEFAULT "
                    + "FROM ZWF_DEFINITION ";

    @Override
    public WfDefinition findLatestDefinition(String key) {
        String sql = DEF_SELECT_ALL + "WHERE DEF_KEY=? ORDER BY DEF_VERSION DESC";
        return queryOne(sql, key, new RowMapper<WfDefinition>() {
            @Override
            public WfDefinition map(ResultSet rs) throws SQLException {
                return readDefinition(rs);
            }
        });
    }

    @Override
    public WfDefinition findDefinition(String key, int version) {
        String sql = DEF_SELECT_ALL + "WHERE DEF_KEY=? AND DEF_VERSION=?";
        return queryOne(sql, new Object[]{key, version}, new RowMapper<WfDefinition>() {
            @Override
            public WfDefinition map(ResultSet rs) throws SQLException {
                return readDefinition(rs);
            }
        });
    }

    @Override
    public List<WfDefinition> findDefinitionVersions(String key) {
        String sql = DEF_SELECT_ALL + "WHERE DEF_KEY=? ORDER BY DEF_VERSION DESC";
        return queryList(sql, new Object[]{key}, new RowMapper<WfDefinition>() {
            @Override
            public WfDefinition map(ResultSet rs) throws SQLException {
                return readDefinition(rs);
            }
        });
    }

    @Override
    public List<WfDefinition> findAllDefinitions() {
        // 每个 key 只取最大版本
        String sql = DEF_SELECT_ALL + "D WHERE DEF_VERSION = "
                + "(SELECT MAX(DEF_VERSION) FROM ZWF_DEFINITION WHERE DEF_KEY = D.DEF_KEY)";
        return queryList(sql, new Object[0], new RowMapper<WfDefinition>() {
            @Override
            public WfDefinition map(ResultSet rs) throws SQLException {
                return readDefinition(rs);
            }
        });
    }

    @Override
    public List<WfDefinition> findDefinitionsByCategory(String category) {
        String sql = DEF_SELECT_ALL + "D "
                + "WHERE D.DEF_CATEGORY=? AND D.DEF_VERSION = "
                + "(SELECT MAX(DEF_VERSION) FROM ZWF_DEFINITION WHERE DEF_KEY = D.DEF_KEY)";
        return queryList(sql, new Object[]{category}, new RowMapper<WfDefinition>() {
            @Override
            public WfDefinition map(ResultSet rs) throws SQLException {
                return readDefinition(rs);
            }
        });
    }

    @Override
    public boolean deleteDefinition(String key, int version) {
        String sql = "DELETE FROM ZWF_DEFINITION WHERE DEF_KEY=? AND DEF_VERSION=?";
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            PreparedStatement ps = connection.prepareStatement(sql);
            try {
                ps.setString(1, key);
                ps.setInt(2, version);
                return ps.executeUpdate() > 0;
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            throw new WfPersistenceException("删除流程定义失败: " + key + ":" + version, e);
        } finally {
            close(connection);
        }
    }

    // ==================== 决策（DMN）====================

    private static final String DECISION_SELECT_ALL =
            "SELECT DECISION_KEY, DECISION_VERSION, DECISION_NAME, HIT_POLICY, "
                    + "TABLE_JSON, DMN_XML, DEPLOY_TIME FROM ZWF_DECISION ";

    @Override
    public void saveDecision(WfDmnDecision decision) {
        if (decision == null || decision.getKey() == null) {
            return;
        }
        String sql = "INSERT INTO ZWF_DECISION "
                + "(DECISION_KEY, DECISION_VERSION, DECISION_NAME, HIT_POLICY, "
                + " TABLE_JSON, DMN_XML, DEPLOY_TIME) VALUES (?,?,?,?,?,?,?)";
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            PreparedStatement ps = connection.prepareStatement(sql);
            try {
                ps.setString(1, decision.getKey());
                ps.setInt(2, decision.getVersion());
                ps.setString(3, decision.getName());
                // HIT_POLICY 从**解析后的表**取，而不是重新去读 XML：
                // 两处解析一遍的话，某天解析器改了而这行没改，库里记的策略
                // 就会与真正求值时用的策略不一致，而查库的人看不出来。
                ps.setString(4, decision.getTable() == null ? null
                        : String.valueOf(decision.getTable().getHitPolicy()));
                ps.setString(5, decision.getTable() == null ? null
                        : JsonUtil.toJson(decision.getTable()));
                ps.setString(6, decision.getDmnXml());
                ps.setTimestamp(7, timestamp(decision.getDeployTime()));
                ps.executeUpdate();
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            throw new WfPersistenceException("保存决策定义失败: " + decision.getKey(), e);
        } finally {
            close(connection);
        }
    }

    @Override
    public WfDmnDecision findDecision(String key, int version) {
        String sql = DECISION_SELECT_ALL + "WHERE DECISION_KEY=? AND DECISION_VERSION=?";
        return queryOne(sql, new Object[]{key, version}, new RowMapper<WfDmnDecision>() {
            @Override
            public WfDmnDecision map(ResultSet rs) throws SQLException {
                return readDecision(rs);
            }
        });
    }

    @Override
    public WfDmnDecision findLatestDecision(String key) {
        // 不走 MAX(VERSION) 聚合：那样取回来的会是"某一行"，要靠再查一次才知道
        // 哪个版本。更要紧的是 MAX() 在没有行时返回 NULL 且不带行 —— 那与
        // "有一行但版本为 NULL"分不开，而这里要的是"查不到就是查不到"。
        String sql = DECISION_SELECT_ALL + "WHERE DECISION_KEY=? "
                + "ORDER BY DECISION_VERSION DESC";
        List<WfDmnDecision> found = queryList(sql, new Object[]{key},
                new RowMapper<WfDmnDecision>() {
                    @Override
                    public WfDmnDecision map(ResultSet rs) throws SQLException {
                        return readDecision(rs);
                    }
                });
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public List<WfDmnDecision> findDecisionVersions(String key) {
        String sql = DECISION_SELECT_ALL + "WHERE DECISION_KEY=? ORDER BY DECISION_VERSION DESC";
        return queryList(sql, new Object[]{key}, new RowMapper<WfDmnDecision>() {
            @Override
            public WfDmnDecision map(ResultSet rs) throws SQLException {
                return readDecision(rs);
            }
        });
    }

    @Override
    public boolean deleteDecision(String key, int version) {
        String sql = "DELETE FROM ZWF_DECISION WHERE DECISION_KEY=? AND DECISION_VERSION=?";
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            PreparedStatement ps = connection.prepareStatement(sql);
            try {
                ps.setString(1, key);
                ps.setInt(2, version);
                return ps.executeUpdate() > 0;
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            throw new WfPersistenceException("删除决策定义失败: " + key + ":" + version, e);
        } finally {
            close(connection);
        }
    }

    /**
     * 一行 → 一个决策。
     *
     * <p>{@code TABLE_JSON} 解不开时<b>抛异常而不是返回 null 表</b>：
     * 返回 null 表会让上层报"这个决策没有决策表"，把"库里那列坏了"
     * 说成"作者没写表"，排障方向从数据问题被带到设计问题。
     */
    private WfDmnDecision readDecision(ResultSet rs) throws SQLException {
        WfDmnDecision decision = new WfDmnDecision(rs.getString("DECISION_KEY"),
                rs.getString("DECISION_NAME"));
        decision.setVersion(rs.getInt("DECISION_VERSION"));
        decision.setDmnXml(rs.getString("DMN_XML"));
        decision.setDeployTime(date(rs.getTimestamp("DEPLOY_TIME")));
        String json = rs.getString("TABLE_JSON");
        if (json != null && !json.trim().isEmpty()) {
            try {
                decision.setTable(JsonUtil.fromJson(json, WfDmnDecision.WfDmnTable.class));
            } catch (RuntimeException e) {
                throw new WfPersistenceException("决策 " + decision.getKey()
                        + " 的 TABLE_JSON 解不开: " + e.getMessage()
                        + "。这一列坏了，不是这张表没写规则 —— "
                        + "报成「没有决策表」会把排障方向从数据问题带到设计问题", e);
            }
        }
        return decision;
    }

    @Override
    public boolean setDefinitionSuspended(String key, int version, boolean suspended) {
        String sql = "UPDATE ZWF_DEFINITION SET SUSPENDED=? WHERE DEF_KEY=? AND DEF_VERSION=?";
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            PreparedStatement ps = connection.prepareStatement(sql);
            try {
                ps.setInt(1, suspended ? 1 : 0);
                ps.setString(2, key);
                ps.setInt(3, version);
                return ps.executeUpdate() > 0;
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            throw new WfPersistenceException(
                    "更新流程定义停用状态失败: " + key + ":" + version, e);
        } finally {
            close(connection);
        }
    }

    @Override
    public boolean setDefaultDefinition(String key, int version, boolean isDefault) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            // 先确认目标行在不在：不存在时直接返回 false。
            // 不能"先清全表再发现没改到" —— 那会把原有的默认悄悄清掉，
            // 症状是「调了两次，第二次的 key 打错了，于是默认没了」而调用方毫无察觉。
            PreparedStatement check = connection.prepareStatement(
                    "SELECT 1 FROM ZWF_DEFINITION WHERE DEF_KEY=? AND DEF_VERSION=?");
            try {
                bind(check, new Object[]{key, version});
                try (ResultSet rs = check.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                }
            } finally {
                check.close();
            }
            if (isDefault) {
                // 「全库至多一条默认」由这里维持。两步之间没有事务包住，
                // 并发两次置位理论上会留下两条 —— findDefaultDefinition 会当场报出来，
                // 而不是让调用方拿到一个取决于行返回顺序的答案。
                Statement clear = connection.createStatement();
                try {
                    clear.executeUpdate(
                            "UPDATE ZWF_DEFINITION SET IS_DEFAULT=0 WHERE IS_DEFAULT=1");
                } finally {
                    clear.close();
                }
            }
            PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ZWF_DEFINITION SET IS_DEFAULT=? WHERE DEF_KEY=? AND DEF_VERSION=?");
            try {
                bind(ps, new Object[]{isDefault ? 1 : 0, key, version});
                return ps.executeUpdate() > 0;
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            throw new WfPersistenceException(
                    "设置默认流程定义失败: " + key + ":" + version, e);
        } finally {
            close(connection);
        }
    }

    @Override
    public WfDefinition findDefaultDefinition() {
        // 先只按列数与 key/version 判定，不碰 DEF_GRAPH。
        // 走 DEF_GRAPH 的那条路有个静默失败：图解不出来时 readDefinition 返回 null，
        // 库里明明有一行 IS_DEFAULT=1，接口却答"没有默认" —— 没有任何报错，
        // 而调用方据此判定"还没配默认"，把入口页显示成未配置状态。
        List<String[]> keys = queryList(
                "SELECT DEF_KEY, DEF_VERSION FROM ZWF_DEFINITION "
                        + "WHERE IS_DEFAULT=1 ORDER BY DEF_KEY, DEF_VERSION",
                new Object[0], new RowMapper<String[]>() {
                    @Override
                    public String[] map(ResultSet rs) throws SQLException {
                        return new String[]{
                                rs.getString("DEF_KEY"), String.valueOf(rs.getInt("DEF_VERSION"))};
                    }
                });
        if (keys.isEmpty()) {
            return null;
        }
        if (keys.size() > 1) {
            List<String> ids = new ArrayList<>();
            for (String[] each : keys) {
                ids.add(each[0] + ":" + each[1]);
            }
            throw new WfPersistenceException("同时存在多条默认流程定义: " + ids
                    + "。默认标记只能由 setDefaultDefinition 写入，"
                    + "出现多条说明数据被绕过接口直接改过");
        }
        WfDefinition definition =
                findDefinition(keys.get(0)[0], Integer.parseInt(keys.get(0)[1]));
        if (definition == null) {
            // 行在、图解不出来：这不是"没有默认"，是数据坏了
            throw new WfPersistenceException("默认流程定义 " + keys.get(0)[0] + ":"
                    + keys.get(0)[1] + " 的 DEF_GRAPH 解析失败，"
                    + "该行的图结构已损坏（可能被绕过引擎直接改过库）");
        }
        return definition;
    }

    @Override
    public List<WfDefinition> findDefinitions(String keyLike, String nameLike, Boolean suspended) {
        StringBuilder sql = new StringBuilder(DEF_SELECT_ALL + "D WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (keyLike != null && !keyLike.trim().isEmpty()) {
            sql.append(" AND D.DEF_KEY LIKE ? ESCAPE '\\'");
            args.add("%" + escapeLike(keyLike.trim()) + "%");
        }
        if (nameLike != null && !nameLike.trim().isEmpty()) {
            // 与审计的变量名同一个坑：用户给的名字里的 % / _ 必须转义，
            // 否则一次"模糊查"静默变成"全都能查出来"
            sql.append(" AND D.DEF_NAME LIKE ? ESCAPE '\\'");
            args.add("%" + escapeLike(nameLike.trim()) + "%");
        }
        if (suspended != null) {
            sql.append(" AND D.SUSPENDED=?");
            args.add(suspended ? 1 : 0);
        }
        sql.append(" AND D.DEF_VERSION = "
                + "(SELECT MAX(DEF_VERSION) FROM ZWF_DEFINITION WHERE DEF_KEY = D.DEF_KEY)");
        return queryList(sql.toString(), args.toArray(), new RowMapper<WfDefinition>() {
            @Override
            public WfDefinition map(ResultSet rs) throws SQLException {
                return readDefinition(rs);
            }
        });
    }

    private WfDefinition deserializeDefinition(String graph) {        // 走专用编解码：直接 JsonUtil.fromJson(definition) 会因 Date 字段静默返回 null
        // （详见 WfDefinitionCodec 的类注释）
        return WfDefinitionCodec.decode(graph);
    }

    // ==================== 流程实例 ====================

    @Override
    public void saveProcessInstance(WfProcessInstance instance) {
        boolean exists = exists("SELECT 1 FROM ZWF_PROCESS WHERE PROC_ID=?", instance.getId());
        if (exists) {
            // 乐观锁：只允许 revision 恰好 +1 的那次写入
            // **刻意不含 NAME**。这条 UPDATE 是部分更新（只更状态、结果、变量、原因），
            // 而调用方手上的实例对象往往是在改名之前取的、压根没有 name ——
            // 把 NAME 放进列清单，任何一次回写都会顺手把名字抹成 null，
            // 症状是「改名明明成功了，名字过一会儿自己没了」。
            // 名字由 setProcessInstanceName 独占，理由见 WfProcessInstance#name。
            String sql = "UPDATE ZWF_PROCESS SET STATUS=?, RESULT=?, END_TIME=?, SUSPEND_REASON=?, "
                    + "DELETE_REASON=?, VARIABLES=?, REVISION=? WHERE PROC_ID=? AND REVISION=?";
            int affected = update(sql, instance.getStatus() == null ? null : instance.getStatus().name(),
                    instance.getResult(), timestamp(instance.getEndTime()),
                    instance.getSuspendReason(), instance.getDeleteReason(),
                    JsonUtil.toJson(instance.getVariables()), instance.getRevision(),
                    instance.getId(), instance.getRevision() - 1);
            if (affected == 0) {
                throw new WfOptimisticLockException("processInstance", instance.getId(),
                        instance.getRevision() - 1);
            }
            return;
        }
        String sql = "INSERT INTO ZWF_PROCESS (PROC_ID, DEF_KEY, DEF_ID, DEF_VERSION, BUSINESS_KEY, "
                + "NAME, START_USER_ID, START_DEPT_ID, CATEGORY, STATUS, RESULT, START_TIME, END_TIME, "
                + "SUSPEND_REASON, DELETE_REASON, VARIABLES, REVISION) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            PreparedStatement ps = connection.prepareStatement(sql);
            try {
                ps.setString(1, instance.getId());
                ps.setString(2, instance.getDefinitionKey());
                ps.setString(3, instance.getDefinitionId());
                ps.setInt(4, instance.getDefinitionVersion());
                ps.setString(5, instance.getBusinessKey());
                ps.setString(6, instance.getName());
                ps.setString(7, instance.getStartUserId());
                ps.setString(8, instance.getStartDeptId());
                ps.setString(9, instance.getCategory());
                ps.setString(10, instance.getStatus() == null ? null : instance.getStatus().name());
                ps.setString(11, instance.getResult());
                ps.setTimestamp(12, timestamp(instance.getStartTime()));
                ps.setTimestamp(13, timestamp(instance.getEndTime()));
                ps.setString(14, instance.getSuspendReason());
                ps.setString(15, instance.getDeleteReason());
                ps.setString(16, JsonUtil.toJson(instance.getVariables()));
                ps.setInt(17, instance.getRevision());
                ps.executeUpdate();
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            throw new WfPersistenceException("插入流程实例失败: " + instance.getId(), e);
        } finally {
            close(connection);
        }
    }

    @Override
    public int setProcessInstanceName(String processInstanceId, String name) {
        // 只更这一列，且**不带上 REVISION**：见 WfPersistence#setProcessInstanceName
        return update("UPDATE ZWF_PROCESS SET NAME=? WHERE PROC_ID=?", name, processInstanceId);
    }

    @Override
    public WfProcessInstance findProcessInstance(String id) {
        return queryOne("SELECT * FROM ZWF_PROCESS WHERE PROC_ID=?", id,
                new RowMapper<WfProcessInstance>() {
                    @Override
                    public WfProcessInstance map(ResultSet rs) throws SQLException {
                        return mapInstance(rs);
                    }
                });
    }

    @Override
    public List<WfProcessInstance> findProcessInstancesByBusinessKey(String businessKey) {
        return queryList("SELECT * FROM ZWF_PROCESS WHERE BUSINESS_KEY=? ORDER BY START_TIME DESC",
                new Object[]{businessKey}, instanceMapper());
    }

    @Override
    public List<WfProcessInstance> queryProcessInstances(WfProcessInstanceQuery query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ZWF_PROCESS WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendProcessFilters(sql, args, query);
        sql.append(" ORDER BY START_TIME DESC");
        // 分页下推到 SQL。此前是把全表拉进内存再 subList，
        // 流程实例表是审批系统里单量最大的一张（每个单据一条），每翻一页付一次全表拉取。
        sql.append(" LIMIT ? OFFSET ?");
        args.add(query == null || query.getPageSize() <= 0 ? 20 : query.getPageSize());
        args.add(query == null ? 0 : query.getOffset());
        return queryList(sql.toString(), args.toArray(), instanceMapper());
    }

    @Override
    public long countProcessInstances(WfProcessInstanceQuery query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ZWF_PROCESS WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendProcessFilters(sql, args, query);
        Long count = queryOne(sql.toString(), args.toArray(), COUNT_MAPPER);
        return count == null ? 0L : count;
    }

    /**
     * 流程实例查询条件。列表与计数共用同一段 WHERE —— 拆成两处的话，
     * 迟早会只改一处，然后 {@code total} 和实际列表对不上。
     */
    private void appendProcessFilters(StringBuilder sql, List<Object> args,
                                      WfProcessInstanceQuery query) {
        if (query == null) {
            return;
        }
        // 条件自相矛盾时在这里就抛，而不是拼出恒假的 SQL 返回空集
        query.assertConsistent();
        appendIfNotBlank(sql, args, " AND DEF_KEY=?", query.getDefinitionKey());
        if (query.getDefinitionVersion() != null) {
            // 版本是 Integer，不走 appendIfNotBlank（它只收 String）
            sql.append(" AND DEF_VERSION=?");
            args.add(query.getDefinitionVersion());
        }
        appendIfNotBlank(sql, args, " AND BUSINESS_KEY=?", query.getBusinessKey());
        appendIfNotBlank(sql, args, " AND START_USER_ID=?", query.getStartUserId());
        appendIfNotBlank(sql, args, " AND CATEGORY=?", query.getCategory());
        // 终态/在途是两组状态，枚举字面量在这里显式列出：
        // 与 WfProcessStatus.isTerminal()/isActive() 一一对应，改枚举时这两行必须一起改
        if (query.isFinishedOnly()) {
            sql.append(" AND STATUS IN ('COMPLETED','EXTERNALLY_TERMINATED',"
                    + "'INTERNALLY_TERMINATED')");
        }
        if (query.isUnfinishedOnly()) {
            sql.append(" AND STATUS IN ('ACTIVE','SUSPENDED')");
        }
        if (query.getStatus() != null) {
            sql.append(" AND STATUS=?");
            args.add(query.getStatus().name());
        }
        appendIfNotBlank(sql, args, " AND RESULT=?", query.getResult());
        if (query.getStartTimeFrom() != null) {
            sql.append(" AND START_TIME>=?");
            args.add(timestamp(query.getStartTimeFrom()));
        }
        if (query.getStartTimeTo() != null) {
            sql.append(" AND START_TIME<=?");
            args.add(timestamp(query.getStartTimeTo()));
        }
    }

    /** {@code SELECT COUNT(*)} 的行映射。 */
    /**
     * 活动历史行映射。
     *
     * <p>抽成常量而不是在 {@code findActivityInstances} 里就地 new：
     * 新增的历史查询与老方法查的是同一张表、映射同一批列，
     * 两处各写一份的话，加字段时只改一处就会让另一个查询静默少字段。
     */
    private final RowMapper<WfActivityInstance> activityMapper =
            new RowMapper<WfActivityInstance>() {
                @Override
                public WfActivityInstance map(ResultSet rs) throws SQLException {
                    WfActivityInstance instance = new WfActivityInstance();
                    instance.setId(rs.getString("ACT_ID"));
                    instance.setProcessInstanceId(rs.getString("PROC_ID"));
                    instance.setProcessDefinitionKey(rs.getString("DEF_KEY"));
                    instance.setActivityId(rs.getString("ACTIVITY_ID"));
                    instance.setActivityName(rs.getString("ACTIVITY_NAME"));
                    instance.setActivityType(rs.getString("ACTIVITY_TYPE"));
                    instance.setExecutionId(rs.getString("EXEC_ID"));
                    instance.setAssignee(rs.getString("ASSIGNEE"));
                    instance.setDurationMillis(rs.getLong("DURATION_MS"));
                    instance.setStartTime(date(rs.getTimestamp("START_TIME")));
                    instance.setEndTime(date(rs.getTimestamp("END_TIME")));
                    instance.setOutcome(rs.getString("OUTCOME"));
                    instance.setDetail(rs.getString("DETAIL"));
                    instance.setVariables(fromJsonMap(rs.getString("VARIABLES")));
                    return instance;
                }
            };

    /** 拼 AND 条件。SQL 片段来自本类的固定模板，不含用户输入。 */
    private static String join(List<String> parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static final RowMapper<Long> COUNT_MAPPER = new RowMapper<Long>() {
        @Override
        public Long map(ResultSet rs) throws SQLException {
            return rs.getLong(1);
        }
    };

    private RowMapper<WfProcessInstance> instanceMapper() {
        return new RowMapper<WfProcessInstance>() {
            @Override
            public WfProcessInstance map(ResultSet rs) throws SQLException {
                return mapInstance(rs);
            }
        };
    }

    private WfProcessInstance mapInstance(ResultSet rs) throws SQLException {
        WfProcessInstance instance = new WfProcessInstance(
                rs.getString("PROC_ID"), rs.getString("DEF_KEY"), rs.getString("DEF_ID"));
        instance.setDefinitionVersion(rs.getInt("DEF_VERSION"));
        instance.setBusinessKey(rs.getString("BUSINESS_KEY"));
        instance.setName(rs.getString("NAME"));
        instance.setStartUserId(rs.getString("START_USER_ID"));
        instance.setStartDeptId(rs.getString("START_DEPT_ID"));
        instance.setCategory(rs.getString("CATEGORY"));
        String status = rs.getString("STATUS");
        instance.setStatus(status == null ? WfProcessStatus.ACTIVE : WfProcessStatus.valueOf(status));
        instance.setResult(rs.getString("RESULT"));
        instance.setStartTime(date(rs.getTimestamp("START_TIME")));
        instance.setEndTime(date(rs.getTimestamp("END_TIME")));
        instance.setSuspendReason(rs.getString("SUSPEND_REASON"));
        instance.setDeleteReason(rs.getString("DELETE_REASON"));
        instance.setVariables(fromJsonMap(rs.getString("VARIABLES")));
        instance.setRevision(rs.getInt("REVISION"));
        return instance;
    }

    // ==================== token ====================

    @Override
    public void saveExecution(WfExecution execution) {
        boolean exists = exists("SELECT 1 FROM ZWF_EXECUTION WHERE EXEC_ID=?", execution.getId());
        if (exists) {
            update("UPDATE ZWF_EXECUTION SET PARENT_ID=?, ACTIVITY_ID=?, STATE=?, IS_CHILD=?, "
                            + "ENTERED_TIME=?, VARIABLES=?, ARRIVED=? WHERE EXEC_ID=?",
                    execution.getParentId(), execution.getActivityId(),
                    execution.getState() == null ? null : execution.getState().name(),
                    execution.isChild() ? 1 : 0, timestamp(execution.getEnteredTime()),
                    JsonUtil.toJson(execution.getVariables()),
                    JsonUtil.toJson(execution.getArrivedActivities()), execution.getId());
            return;
        }
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ZWF_EXECUTION (EXEC_ID, PROC_ID, PARENT_ID, ACTIVITY_ID, STATE, "
                            + "IS_CHILD, ENTERED_TIME, VARIABLES, ARRIVED) VALUES (?,?,?,?,?,?,?,?,?)");
            try {
                ps.setString(1, execution.getId());
                ps.setString(2, execution.getProcessInstanceId());
                ps.setString(3, execution.getParentId());
                ps.setString(4, execution.getActivityId());
                ps.setString(5, execution.getState() == null ? null : execution.getState().name());
                ps.setInt(6, execution.isChild() ? 1 : 0);
                ps.setTimestamp(7, timestamp(execution.getEnteredTime()));
                ps.setString(8, JsonUtil.toJson(execution.getVariables()));
                ps.setString(9, JsonUtil.toJson(execution.getArrivedActivities()));
                ps.executeUpdate();
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            throw new WfPersistenceException("保存 token 失败: " + execution.getId(), e);
        } finally {
            close(connection);
        }
    }

    @Override
    public void deleteExecution(String id) {
        update("DELETE FROM ZWF_EXECUTION WHERE EXEC_ID=?", id);
    }

    @Override
    public WfExecution findExecution(String id) {
        return queryOne("SELECT * FROM ZWF_EXECUTION WHERE EXEC_ID=?", id,
                new RowMapper<WfExecution>() {
                    @Override
                    public WfExecution map(ResultSet rs) throws SQLException {
                        return mapExecution(rs);
                    }
                });
    }

    @Override
    public List<WfExecution> findExecutionsByProcessInstance(String processInstanceId) {
        return queryList("SELECT * FROM ZWF_EXECUTION WHERE PROC_ID=?",
                new Object[]{processInstanceId}, new RowMapper<WfExecution>() {
                    @Override
                    public WfExecution map(ResultSet rs) throws SQLException {
                        return mapExecution(rs);
                    }
                });
    }

    @Override
    public List<WfExecution> queryExecutions(WfExecutionQuery query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ZWF_EXECUTION WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendExecutionFilters(sql, args, query);
        // 只用主键兜底排序，**不按 ENTERED_TIME 排**。
        //
        // 为什么不在这里按进入时间排：ENTERED_TIME 可空（手工写入 / 脏数据 / 外部导入），
        // 而"NULL 排前还是排后"在 H2 / MySQL / PostgreSQL 上**结论相反**
        // —— `ORDER BY t DESC` 在 H2 把 NULL 排最后，在 PostgreSQL 排最前。
        // 用 `ORDER BY t IS NULL` 去显式控制同样不行：H2 / PG 上它是布尔
        // （false < true，于是 NULL 排前），MySQL 上是 0/1（0 在前，于是 NULL 排后）。
        // 排序交给 WfExecutionQueryService 统一做（它两套实现跑同一段 Java），
        // 这里的顺序只保证同一页内稳定、不保证业务语义。
        sql.append(" ORDER BY EXEC_ID DESC LIMIT ? OFFSET ?");
        args.add(query == null || query.getPageSize() <= 0 ? 50 : query.getPageSize());
        args.add(query == null ? 0 : Math.max(0, query.getOffset()));
        return queryList(sql.toString(), args.toArray(), new RowMapper<WfExecution>() {
            @Override
            public WfExecution map(ResultSet rs) throws SQLException {
                return mapExecution(rs);
            }
        });
    }

    /**
     * 令牌查询里<b>能下推到 SQL</b> 的那几项。
     *
     * <p>刻意<b>不</b>含变量条件：{@code VARIABLES} 是 JSON 文本列，
     * 各库对 JSON 函数的支持与语义都不一样（H2 与 PostgreSQL 能用不同写法，
     * MySQL 又是另一套），要跨库行为一致就只能回到 Java 里比 ——
     * 那就意味着过滤发生在分页<b>之后</b>，所以分页也不能交给 SQL，
     * 由 {@code WfExecutionQueryService} 统一做（见那里的注释）。
     */
    private void appendExecutionFilters(StringBuilder sql, List<Object> args, WfExecutionQuery query) {
        if (query == null) {
            return;
        }
        if (isNotBlank(query.getProcessInstanceId())) {
            sql.append(" AND PROC_ID=?");
            args.add(query.getProcessInstanceId().trim());
        }
        if (isNotBlank(query.getActivityId())) {
            sql.append(" AND ACTIVITY_ID=?");
            args.add(query.getActivityId().trim());
        }
        if (query.getStates() != null && !query.getStates().isEmpty()) {
            sql.append(" AND STATE IN (");
            boolean first = true;
            for (WfExecution.State state : query.getStates()) {
                if (!first) {
                    sql.append(",");
                }
                sql.append("?");
                args.add(state.name());
                first = false;
            }
            sql.append(")");
        }
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private WfExecution mapExecution(ResultSet rs) throws SQLException {
        WfExecution execution = new WfExecution(rs.getString("EXEC_ID"),
                rs.getString("PROC_ID"), rs.getString("ACTIVITY_ID"));
        execution.setParentId(rs.getString("PARENT_ID"));
        String state = rs.getString("STATE");
        execution.setState(state == null ? WfExecution.State.ACTIVE : WfExecution.State.valueOf(state));
        execution.setChild(rs.getInt("IS_CHILD") == 1);
        execution.setEnteredTime(date(rs.getTimestamp("ENTERED_TIME")));
        execution.setVariables(fromJsonMap(rs.getString("VARIABLES")));
        execution.setArrivedActivities(fromJsonList(rs.getString("ARRIVED")));
        return execution;
    }

    // ==================== 任务 ====================

    @Override
    public void saveTask(WfTask task) {
        boolean exists = exists("SELECT 1 FROM ZWF_TASK WHERE TASK_ID=?", task.getId());
        if (exists) {
            // 乐观锁
            int affected = update("UPDATE ZWF_TASK SET STATUS=?, ASSIGNEE=?, OWNER=?, PRIORITY=?, "
                            + "END_TIME=?, COMPLETER_ID=?, COMMENT_TEXT=?, VARIABLES=?, "
                            + "CANDIDATE_USERS=?, CANDIDATE_GROUPS=?, "
                            + "DELEGATE_CHAIN=?, SUSPENDED=?, REVISION=? "
                            + "WHERE TASK_ID=? AND REVISION=?",
                    task.getStatus() == null ? null : task.getStatus().name(),
                    task.getAssignee(), task.getOwner(), task.getPriority(),
                    timestamp(task.getEndTime()), task.getCompleterId(), task.getComment(),
                    JsonUtil.toJson(task.getVariables()),
                    JsonUtil.toJson(task.getCandidateUsers()),
                    JsonUtil.toJson(task.getCandidateGroups()),
                    // 候选池必须跟着 UPDATE 走。只在 INSERT 里写的话，
                    // 任何运行时调整候选人（加派/改派）存回去都不生效 —— 症状是
                    // addCandidateUser 不报错、内存里也对，但换一次读取就没了。
                    delegateChainJson(task),
                    // 挂起列必须跟着 UPDATE 走：只写进 INSERT 的话，
                    // suspendTask 改了内存对象存回去，挂起状态在库里永远是初始值
                    task.isSuspended() ? 1 : 0,
                    task.getRevision(),
                    task.getId(), task.getRevision() - 1);
            if (affected == 0) {
                throw new WfOptimisticLockException("task", task.getId(), task.getRevision() - 1);
            }
            return;
        }
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ZWF_TASK (TASK_ID, PROC_ID, EXEC_ID, DEF_ID, TASK_NAME, TASK_TYPE, "
                            + "FORM_KEY, CATEGORY, ASSIGNEE, OWNER, CANDIDATE_USERS, CANDIDATE_GROUPS, "
                            + "STATUS, PRIORITY, CREATE_TIME, DUE_DATE, END_TIME, COMPLETER_ID, "
                            + "COMMENT_TEXT, VARIABLES, DELEGATE_CHAIN, PARENT_TASK_ID, SUSPENDED, "
                            + "REVISION) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
            try {
                int i = 1;
                ps.setString(i++, task.getId());
                ps.setString(i++, task.getProcessInstanceId());
                ps.setString(i++, task.getExecutionId());
                ps.setString(i++, task.getDefinitionId());
                ps.setString(i++, task.getName());
                ps.setString(i++, task.getType());
                ps.setString(i++, task.getFormKey());
                ps.setString(i++, task.getCategory());
                ps.setString(i++, task.getAssignee());
                ps.setString(i++, task.getOwner());
                ps.setString(i++, JsonUtil.toJson(task.getCandidateUsers()));
                ps.setString(i++, JsonUtil.toJson(task.getCandidateGroups()));
                ps.setString(i++, task.getStatus() == null ? null : task.getStatus().name());
                ps.setInt(i++, task.getPriority());
                ps.setTimestamp(i++, timestamp(task.getCreateTime()));
                ps.setTimestamp(i++, timestamp(task.getDueDate()));
                ps.setTimestamp(i++, timestamp(task.getEndTime()));
                ps.setString(i++, task.getCompleterId());
                ps.setString(i++, task.getComment());
                ps.setString(i++, JsonUtil.toJson(task.getVariables()));
                ps.setString(i++, delegateChainJson(task));
                ps.setString(i++, task.getParentTaskId());
                ps.setInt(i++, task.isSuspended() ? 1 : 0);
                ps.setInt(i, task.getRevision());
                ps.executeUpdate();
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            throw new WfPersistenceException("保存任务失败: " + task.getId(), e);
        } finally {
            close(connection);
        }
    }

    @Override
    public void deleteTask(String id) {
        update("DELETE FROM ZWF_TASK WHERE TASK_ID=?", id);
    }

    @Override
    public WfTask findTask(String id) {
        return queryOne("SELECT * FROM ZWF_TASK WHERE TASK_ID=?", id,
                new RowMapper<WfTask>() {
                    @Override
                    public WfTask map(ResultSet rs) throws SQLException {
                        return mapTask(rs);
                    }
                });
    }

    @Override
    public List<WfTask> queryTasks(WfTaskQuery query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ZWF_TASK WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendTaskFilters(sql, args, query);
        // 与内存实现同序：未完成优先 -> 优先级高优先 -> 新建优先
        sql.append(" ORDER BY CASE STATUS WHEN 'COMPLETED' THEN 1 ELSE 0 END, PRIORITY DESC, CREATE_TIME DESC");
        // 分页下推到 SQL。此前是把全表拉进内存再 subList，
        // 任务表是审批系统里增长最快的一张（每步一个待办），每翻一页都付一次全表拉取。
        sql.append(" LIMIT ? OFFSET ?");
        args.add(query == null || query.getPageSize() <= 0 ? 20 : query.getPageSize());
        args.add(query == null ? 0 : query.getOffset());
        return queryList(sql.toString(), args.toArray(), new RowMapper<WfTask>() {
            @Override
            public WfTask map(ResultSet rs) throws SQLException {
                return mapTask(rs);
            }
        });
    }

    @Override
    public long countTasks(WfTaskQuery query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ZWF_TASK WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendTaskFilters(sql, args, query);
        Long count = queryOne(sql.toString(), args.toArray(), COUNT_MAPPER);
        return count == null ? 0L : count;
    }

    /**
     * 任务查询的条件拼装；{@link #queryTasks} 与 {@link #countTasks} 共用。
     *
     * <p>共用不是为了少写几行：两处各拼一遍条件，迟早有一边漏掉一个过滤项，
     * 于是列表页显示"共 3 条"而实际列出来 5 条，或者翻到第二页冒出重复的条目。
     * count 与列表口径必须同源，否则那个"共 N 条"就是在骗人。
     */
    private void appendTaskFilters(StringBuilder sql, List<Object> args, WfTaskQuery query) {
        if (query == null) {
            return;
        }
        // 条件自相矛盾时在这里就抛，而不是拼出恒假的 SQL 返回空集
        query.assertConsistent();
        appendIfNotBlank(sql, args, " AND PROC_ID=?", query.getProcessInstanceId());
        appendIfNotBlank(sql, args, " AND DEF_ID=?", query.getDefinitionId());
        appendIfNotBlank(sql, args, " AND COMPLETER_ID=?", query.getCompleterId());
        appendIfNotBlank(sql, args, " AND CATEGORY=?", query.getCategory());
        if (query.isCandidateOrAssigned()) {
            // 待办语义：四种身份关系取或。拆成 AND 的话，
            // "我是办理人但不在候选池里"的任务会被候选条件过滤掉，
            // 结果是待办里看不到自己的单
            sql.append(" AND (");
            boolean firstOr = true;
            if (query.getAssignee() != null) {
                sql.append("ASSIGNEE=?");
                args.add(query.getAssignee());
                firstOr = false;
            }
            if (query.getOwner() != null) {
                if (!firstOr) {
                    sql.append(" OR ");
                }
                sql.append("OWNER=?");
                args.add(query.getOwner());
                firstOr = false;
            }
            if (query.getCandidateUsers() != null) {
                for (String candidate : query.getCandidateUsers()) {
                    if (!firstOr) {
                        sql.append(" OR ");
                    }
                    // 存的是 JSON 数组文本，两侧补引号做整项匹配：
                    // 裸 %u% 会让 "u1" 命中 "u12"
                    sql.append("CANDIDATE_USERS LIKE ?");
                    args.add("%\"" + candidate + "\"%");
                    firstOr = false;
                }
            }
            if (query.getCandidateGroups() != null) {
                for (String group : query.getCandidateGroups()) {
                    if (!firstOr) {
                        sql.append(" OR ");
                    }
                    sql.append("CANDIDATE_GROUPS LIKE ?");
                    args.add("%\"" + group + "\"%");
                    firstOr = false;
                }
            }
            if (firstOr) {
                // 一个身份条件都没给：拼出恒假而不是恒真。
                // 恒真会把整张待办表倒给调用方，恒假则让它去补参数
                sql.append("1=0");
            }
            sql.append(")");
        } else if (query.getAssignee() != null || query.getOwner() != null) {
            // 精确筛选语义：待办 = assignee 命中 或 owner 命中（委派态）
            sql.append(" AND (");
            boolean first = true;
            if (query.getAssignee() != null) {
                sql.append("ASSIGNEE=?");
                args.add(query.getAssignee());
                first = false;
            }
            if (query.getOwner() != null) {
                if (!first) {
                    sql.append(" OR ");
                }
                sql.append("OWNER=?");
                args.add(query.getOwner());
            }
            sql.append(")");
        }
        if (query.getStatus() != null) {
            sql.append(" AND STATUS=?");
            args.add(query.getStatus().name());
        }
        if (query.isOpenOnly()) {
            sql.append(" AND STATUS IN ('CREATED','ASSIGNED','DELEGATED')");
        }
        if (query.isCompletedOnly()) {
            sql.append(" AND STATUS='COMPLETED'");
        }
        if (query.isUnassignedOnly()) {
            sql.append(" AND (ASSIGNEE IS NULL OR ASSIGNEE='')");
        }
        if (query.getSuspendedOnly() != null) {
            sql.append(" AND SUSPENDED=?");
            args.add(query.getSuspendedOnly() ? 1 : 0);
        }
        // 候选人过滤：CANDIDATE_* 存的是 JSON 数组文本，用 LIKE 做子串匹配。
        // 精度有限（"u1" 会命中 "u12"），但审批候选池通常是几十人的量级，
        // 且"多召回几个再由 service 层用 isClaimableBy 精确判定"比"漏召回"安全 ——
        // 反过来（SQL 过滤过严导致可认领的人看不到）才是真问题。
        if (!query.isCandidateOrAssigned()
                && query.getCandidateUsers() != null && !query.getCandidateUsers().isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < query.getCandidateUsers().size(); i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append("CANDIDATE_USERS LIKE ?");
                args.add("%\"" + query.getCandidateUsers().get(i) + "\"%");
            }
            sql.append(")");
        }
        if (!query.isCandidateOrAssigned()
                && query.getCandidateGroups() != null && !query.getCandidateGroups().isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < query.getCandidateGroups().size(); i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append("CANDIDATE_GROUPS LIKE ?");
                args.add("%\"" + query.getCandidateGroups().get(i) + "\"%");
            }
            sql.append(")");
        }
        if (query.getCreateTimeFrom() != null) {
            sql.append(" AND CREATE_TIME>=?");
            args.add(timestamp(query.getCreateTimeFrom()));
        }
        if (query.getCreateTimeTo() != null) {
            sql.append(" AND CREATE_TIME<=?");
            args.add(timestamp(query.getCreateTimeTo()));
        }
    }

    /**
     * 委派链 → JSON 数组文本。空链写 {@code []} 而不是 null：
     * 写 null 会让"没委派过"和"表结构老版本没有这一列"两种情况在读回时无法区分。
     */
    private String delegateChainJson(WfTask task) {
        List<WfTask.DelegateHop> chain = task.getDelegateChain();
        return chain == null || chain.isEmpty() ? "[]" : JsonUtil.toJson(chain);
    }

    /**
     * JSON 数组文本 → 委派链。
     *
     * <p>逐个字段手工读，而不是 {@code JsonUtil.fromJson(json, List.class)}：
     * 后者对无值类型信息的 {@code List.class} 取不到任何元素且不报错（实测返回空），
     * 症状是"委派过但链读回来是空的" —— 审计能力看着在、实际静默失效。
     */
    private List<WfTask.DelegateHop> parseDelegateChain(String json) {
        List<WfTask.DelegateHop> result = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return result;
        }
        try {
            JsonArray array = JsonUtil.parseArray(json);
            if (array == null) {
                return result;
            }
            for (int i = 0; i < array.size(); i++) {
                Object item = array.get(i);
                String from = null;
                String to = null;
                Date time = null;
                if (item instanceof JsonObject) {
                    // JsonObject 是 z-util-json 的自定义类型，**不是 java.util.Map**
                    // （它 implements Iterable 之外的独立类，字段是私有 Map）。
                    // 用 `instanceof Map` 判会全部落空 —— 症状是"委派过但链读回来是空的"，
                    // 不报错、不抛异常。必须走 getJsonObject / getString 这套 API。
                    JsonObject object = (JsonObject) item;
                    from = object.getString("from");
                    to = object.getString("to");
                    Object raw = object.get("time");
                    if (raw instanceof Number) {
                        time = new Date(((Number) raw).longValue());
                    }
                } else if (item instanceof Map) {
                    Map<?, ?> row = (Map<?, ?>) item;
                    from = row.get("from") == null ? null : String.valueOf(row.get("from"));
                    to = row.get("to") == null ? null : String.valueOf(row.get("to"));
                    Object raw = row.get("time");
                    if (raw instanceof Number) {
                        time = new Date(((Number) raw).longValue());
                    }
                } else {
                    log.warn("委派链第 {} 项不是对象，跳过: {}", i, item);
                    continue;
                }
                WfTask.DelegateHop hop = new WfTask.DelegateHop();
                hop.setFrom(from);
                hop.setTo(to);
                hop.setTime(time);
                result.add(hop);
            }
        } catch (Exception e) {
            log.warn("委派链 JSON 解析失败，按空处理: {}", json, e);
        }
        return result;
    }

    private WfTask mapTask(ResultSet rs) throws SQLException {        WfTask task = new WfTask();
        task.setId(rs.getString("TASK_ID"));
        task.setProcessInstanceId(rs.getString("PROC_ID"));
        task.setExecutionId(rs.getString("EXEC_ID"));
        task.setDefinitionId(rs.getString("DEF_ID"));
        task.setName(rs.getString("TASK_NAME"));
        task.setType(rs.getString("TASK_TYPE"));
        task.setFormKey(rs.getString("FORM_KEY"));
        task.setCategory(rs.getString("CATEGORY"));
        task.setAssignee(rs.getString("ASSIGNEE"));
        task.setOwner(rs.getString("OWNER"));
        task.setCandidateUsers(fromJsonList(rs.getString("CANDIDATE_USERS")));
        task.setCandidateGroups(fromJsonList(rs.getString("CANDIDATE_GROUPS")));
        String status = rs.getString("STATUS");
        task.setStatus(status == null ? WfTask.Status.CREATED : WfTask.Status.valueOf(status));
        task.setPriority(rs.getInt("PRIORITY"));
        task.setCreateTime(date(rs.getTimestamp("CREATE_TIME")));
        task.setDueDate(date(rs.getTimestamp("DUE_DATE")));
        task.setEndTime(date(rs.getTimestamp("END_TIME")));
        task.setCompleterId(rs.getString("COMPLETER_ID"));
        task.setComment(rs.getString("COMMENT_TEXT"));
        task.setVariables(fromJsonMap(rs.getString("VARIABLES")));
        task.setDelegateChain(parseDelegateChain(rs.getString("DELEGATE_CHAIN")));
        task.setParentTaskId(rs.getString("PARENT_TASK_ID"));
        // 读挂起列：漏了的话 suspendTask 在内存里生效、存回去就丢，
        // 而开发期常用内存实现，上线才发现挂起功能形同虚设
        task.setSuspended(rs.getInt("SUSPENDED") != 0);
        task.setRevision(rs.getInt("REVISION"));
        return task;
    }

    // ==================== 历史 ====================

    @Override
    public void saveActivityInstance(WfActivityInstance instance) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ZWF_ACTIVITY (ACT_ID, PROC_ID, DEF_KEY, ACTIVITY_ID, ACTIVITY_NAME, "
                            + "ACTIVITY_TYPE, EXEC_ID, ASSIGNEE, DURATION_MS, START_TIME, END_TIME, "
                            + "OUTCOME, DETAIL, VARIABLES) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
            try {
                int i = 1;
                ps.setString(i++, instance.getId());
                ps.setString(i++, instance.getProcessInstanceId());
                ps.setString(i++, instance.getProcessDefinitionKey());
                ps.setString(i++, instance.getActivityId());
                ps.setString(i++, instance.getActivityName());
                ps.setString(i++, instance.getActivityType());
                ps.setString(i++, instance.getExecutionId());
                ps.setString(i++, instance.getAssignee());
                ps.setLong(i++, instance.getDurationMillis());
                ps.setTimestamp(i++, timestamp(instance.getStartTime()));
                ps.setTimestamp(i++, timestamp(instance.getEndTime()));
                ps.setString(i++, instance.getOutcome());
                ps.setString(i++, instance.getDetail());
                ps.setString(i, JsonUtil.toJson(instance.getVariables()));
                ps.executeUpdate();
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            throw new WfPersistenceException("保存活动历史失败: " + instance.getId(), e);
        } finally {
            close(connection);
        }
    }

    @Override
    public List<WfActivityInstance> queryActivityInstances(
            WfHistoricActivityInstanceQuery query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ZWF_ACTIVITY");
        List<Object> args = new ArrayList<>();
        appendActivityFilters(sql, args, query);
        sql.append(" ORDER BY ")
                .append(query != null && "duration".equals(query.getOrderBy())
                        ? "DURATION_MS DESC" : "START_TIME ASC");
        int size = query == null || query.getPageSize() <= 0 ? 20 : query.getPageSize();
        int offset = query == null ? 0 : query.getOffset();
        // 分页参数放在末尾：bind 是按顺序填 ? 的
        sql.append(" LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return queryList(sql.toString(), args.toArray(), activityMapper);
    }

    @Override
    public long countActivityInstances(WfHistoricActivityInstanceQuery query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ZWF_ACTIVITY");
        List<Object> args = new ArrayList<>();
        appendActivityFilters(sql, args, query);
        Long count = queryOne(sql.toString(), args.toArray(), COUNT_MAPPER);
        return count == null ? 0L : count;
    }

    /**
     * 历史清理只删<b>已结束</b>流程的数据。
     *
     * <p>在途流程的历史删掉之后，审批轨迹会出现一个洞，而单据还在被人办 ——
     * 那比表大更难解释。
     *
     * <p>{@code END_TIME IS NOT NULL} 是显式写出来的，不是冗余。实测：把这句去掉
     * 之后，{@code deleteHistoryBeforeKeepsRunning} 依然全绿 —— 因为 SQL 三值逻辑下
     * {@code NULL < ?} 求值为 UNKNOWN，本来就匹配不到在途流程。也就是说"在途流程
     * 不会被删"这件事当时完全依赖数据库对 NULL 的处理，删不删得对都看不出区别。
     * 一旦有人把条件改成 {@code (END_TIME < ? OR END_TIME IS NULL)}，
     * 在途流程的历史当场被删光，而那条测试原本抓不住。显式写出这一句，等于把
     * "在途"这个前提固定下来，改坏时立刻变红。
     */
    /**
     * 引擎自建的全部表名（按 DDL 出现顺序）。
     *
     * <p>与 {@code InMemoryWorkflowPersistence#STORAGE_NAMES} <b>必须完全一致</b> ——
     * 两侧对不上意味着某种逻辑实体在一套实现里存得下、在另一套里存不下，
     * 而那种不一致在功能上通常表现为「内存模式能查、JDBC 下查不到」。
     * {@code JdbcWorkflowPersistenceTest} 里有一条断言把两边钉在一起。
     */
    public static final List<String> TABLE_NAMES = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(
                    "ZWF_DEFINITION", "ZWF_PROCESS", "ZWF_EXECUTION", "ZWF_TASK",
                    "ZWF_JOB", "ZWF_ACTIVITY", "ZWF_COMMENT", "ZWF_FILTER",
                    "ZWF_DECISION"));

    /**
     * 表名清单，<b>从库里真查</b>而不是直接返回常量。
     *
     * <p>为什么不直接报常量：常量回答的是"引擎打算建哪些表"，
     * 这里要回答的是"<b>这个库现在真的有哪些表</b>"。
     * 两者在两种情形下会分家，而那两种都不是"常量写错了"：
     * ① 有人手工建过部分表、{@code initialize} 被 {@code @ConditionalOnProperty} 挡掉；
     * ② 连的是别人的库、迁移脚本只建了其中几张。
     * 报常量会让自省接口在这种时候仍然说"八张表都在"，排障时先信了它就找不到北。
     *
     * <p>用 {@code DatabaseMetaData} 而不是 {@code INFORMATION_SCHEMA}：
     * 后者的表名大小写与 schema 过滤规则在 H2 / MySQL / PostgreSQL 上各不相同，
     * 换库就得改这段 SQL；元数据接口是 JDBC 标准，跨库一致。
     */
    @Override
    public List<String> getTableNames() {
        Connection connection = null;
        List<String> names = new ArrayList<>();
        try {
            connection = dataSource.getConnection();
            DatabaseMetaData metaData = connection.getMetaData();
            ResultSet rs = metaData.getTables(connection.getCatalog(), null,
                    "ZWF%", new String[]{"TABLE"});
            try {
                while (rs.next()) {
                    names.add(rs.getString("TABLE_NAME"));
                }
            } finally {
                rs.close();
            }
        } catch (SQLException e) {
            throw new WfPersistenceException("读取表清单失败", e);
        } finally {
            close(connection);
        }
        // 排序：元数据返回的顺序由数据库决定，飘一次就让人以为"表变了"
        Collections.sort(names);
        return names;
    }

    /**
     * 某张表的行数。
     *
     * <p><b>标识符不能参数化</b>，所以这里必须先把名字<b>校验成白名单里的一个</b>，
     * 再拿它去拼 SQL。校验的来源是 {@link #getTableNames()} —— 即数据库自己报的清单，
     * 不是请求参数。少这一步就是一个能拼任意 SQL 的口子：
     * 表名来自 REST 查询参数，而 {@code ?} 占位符只对值有效、对表名无效。
     */
    @Override
    public long getTableCount(String name) {
        if (name == null || !getTableNames().contains(name)) {
            throw new com.zifang.z.wf.core.service.WfEngineException(
                    "库里没有这张表: " + name + "。现有的: " + getTableNames()
                            + "。若刚改了 DDL，请确认目标库跑过 initialize()");
        }
        return queryOne("SELECT COUNT(*) FROM " + name, null,
                new RowMapper<Long>() {
                    @Override
                    public Long map(ResultSet rs) throws SQLException {
                        return rs.getLong(1);
                    }
                });
    }

    @Override
    public int deleteHistoryBefore(Date before) {
        String sql = "DELETE FROM ZWF_ACTIVITY WHERE PROC_ID IN ("
                + "SELECT PROC_ID FROM ZWF_PROCESS WHERE END_TIME IS NOT NULL AND END_TIME < ?"
                + ")";
        int activities = update(sql, before);
        String taskSql = "DELETE FROM ZWF_TASK WHERE PROC_ID IN ("
                + "SELECT PROC_ID FROM ZWF_PROCESS WHERE END_TIME IS NOT NULL AND END_TIME < ?"
                + ")";
        int tasks = update(taskSql, before);
        String commentSql = "DELETE FROM ZWF_COMMENT WHERE PROC_ID IN ("
                + "SELECT PROC_ID FROM ZWF_PROCESS WHERE END_TIME IS NOT NULL AND END_TIME < ?"
                + ")";
        int comments = update(commentSql, before);
        // 执行令牌也必须一起删，否则 ZWF_PROCESS 那批行没了、令牌还留着 ——
        // 它们没有别的清理路径（主代码里 deleteExecution 从不被调用），
        // 于是这张表只增不减，而每一行都属于一个已经查不到的流程实例。
        //
        // 与内存实现的差别：InMemoryWorkflowPersistence 一直会删令牌。
        // 两套实现对"清理要清到什么程度"的口径本来就该一致，
        // 而这类分歧只有真库上才验得到 —— 内存里不留痕迹。
        //
        // 放在删流程**之前**：子查询要靠 ZWF_PROCESS 里的 END_TIME 圈定目标。
        String executionSql = "DELETE FROM ZWF_EXECUTION WHERE PROC_ID IN ("
                + "SELECT PROC_ID FROM ZWF_PROCESS WHERE END_TIME IS NOT NULL AND END_TIME < ?"
                + ")";
        int executions = update(executionSql, before);
        String processSql = "DELETE FROM ZWF_PROCESS "
                + "WHERE END_TIME IS NOT NULL AND END_TIME < ?";
        int processes = update(processSql, before);
        log.info("清理 {} 之前的历史: 流程 {} 条 / 活动 {} 条 / 任务 {} 条 / 评论 {} 条"
                        + " / 令牌 {} 条",
                before, processes, activities, tasks, comments, executions);
        return processes;
    }

    /** 活动历史查询的条件拼装；内存与 JDBC 两套实现共用同一份条件语义。 */
    private void appendActivityFilters(StringBuilder sql, List<Object> args,
                                        WfHistoricActivityInstanceQuery q) {
        if (q == null) {
            return;
        }
        List<String> parts = new ArrayList<>();
        if (q.getProcessInstanceId() != null) {
            parts.add("PROC_ID=?");
            args.add(q.getProcessInstanceId());
        }
        if (q.getProcessDefinitionKey() != null) {
            parts.add("DEF_KEY=?");
            args.add(q.getProcessDefinitionKey());
        }
        if (q.getActivityId() != null) {
            parts.add("ACTIVITY_ID=?");
            args.add(q.getActivityId());
        }
        if (q.getActivityType() != null) {
            parts.add("ACTIVITY_TYPE=?");
            args.add(q.getActivityType());
        }
        if (q.getAssignee() != null) {
            parts.add("ASSIGNEE=?");
            args.add(q.getAssignee());
        }
        if (q.getStartedAfter() != null) {
            parts.add("START_TIME > ?");
            args.add(timestamp(q.getStartedAfter()));
        }
        if (q.getStartedBefore() != null) {
            parts.add("START_TIME < ?");
            args.add(timestamp(q.getStartedBefore()));
        }
        if (q.getMinDurationMillis() != null) {
            parts.add("DURATION_MS >= ?");
            args.add(q.getMinDurationMillis());
        }
        if (!parts.isEmpty()) {
            sql.append(" WHERE ").append(join(parts, " AND "));
        }
    }

    @Override
    public List<WfActivityInstance> findActivityInstances(String processInstanceId) {
        return queryList("SELECT * FROM ZWF_ACTIVITY WHERE PROC_ID=? ORDER BY START_TIME ASC",
                new Object[]{processInstanceId}, activityMapper);
    }

    @Override
    public void saveComment(WfComment comment) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ZWF_COMMENT (CMT_ID, PROC_ID, TASK_ID, USER_ID, CMT_TYPE, "
                            + "CONTENT, CMT_TIME) VALUES (?,?,?,?,?,?,?)");
            try {
                ps.setString(1, comment.getId());
                ps.setString(2, comment.getProcessInstanceId());
                ps.setString(3, comment.getTaskId());
                ps.setString(4, comment.getUserId());
                ps.setString(5, comment.getType());
                ps.setString(6, comment.getContent());
                ps.setTimestamp(7, timestamp(comment.getTime()));
                ps.executeUpdate();
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            throw new WfPersistenceException("保存评论失败: " + comment.getId(), e);
        } finally {
            close(connection);
        }
    }

    // ==================== 变量变更审计 ====================

    @Override
    public List<WfComment> queryVariableAudits(WfVariableAuditQuery query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ZWF_COMMENT");
        List<Object> args = new ArrayList<>();
        appendAuditFilters(sql, args, query);
        // 时间倒序 + id 兜底：审计是"越新越先看"；同一毫秒的多条要有稳定顺序，
        // 否则同一页两次查询会给出不同的行
        sql.append(" ORDER BY CMT_TIME DESC, CMT_ID DESC LIMIT ? OFFSET ?");
        args.add(query == null || query.getPageSize() <= 0 ? 50 : query.getPageSize());
        args.add(query == null ? 0 : query.getOffset());
        return queryList(sql.toString(), args.toArray(), new RowMapper<WfComment>() {
            @Override
            public WfComment map(ResultSet rs) throws SQLException {
                return mapComment(rs);
            }
        });
    }

    @Override
    public long countVariableAudits(WfVariableAuditQuery query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ZWF_COMMENT");
        List<Object> args = new ArrayList<>();
        appendAuditFilters(sql, args, query);
        Long count = queryOne(sql.toString(), args.toArray(), COUNT_MAPPER);
        return count == null ? 0L : count;
    }

    /**
     * 变量审计的 WHERE；列表与计数共用，条件拼两遍必然漂移。
     *
     * <p>变量名走 {@code CONTENT LIKE 'name:%'}：内容形如 {@code "amount: 1000 -> 500"}，
     * 冒号后面的 {@code %} 是给"旧值 -> 新值"那段留的通配。
     * 用子串匹配（{@code '%name%'}）的话，查 {@code amount} 会把
     * {@code discount_amount} 的记录也带出来 —— 审计给出错的行比不给行更糟。
     */
    private void appendAuditFilters(StringBuilder sql, List<Object> args,
                                    WfVariableAuditQuery query) {
        List<String> parts = new ArrayList<>();
        parts.add("CMT_TYPE=?");
        args.add(com.zifang.z.wf.core.service.WfVariableService.COMMENT_TYPE_VARIABLE);
        if (query != null) {
            if (query.getProcessInstanceId() != null) {
                parts.add("PROC_ID=?");
                args.add(query.getProcessInstanceId());
            }
            if (query.getVariableName() != null && !query.getVariableName().trim().isEmpty()) {
                // ESCAPE 是必须的：变量名由用户给，"disc_ount" 里的 _ 会被当成单字符通配，
                // 于是查 disc_ount 顺带命中 discount —— 本过滤对外承诺的就是精确匹配，
                // 且没有 service 层二次判定兜底，多召回的行会直接进审计结果。
                parts.add("CONTENT LIKE ? ESCAPE '\\'");
                args.add(escapeLike(query.getVariableName().trim()) + ":%");
            }
            if (query.getChangedBy() != null && !query.getChangedBy().trim().isEmpty()) {
                parts.add("USER_ID=?");
                args.add(query.getChangedBy());
            }
            if (query.getChangedFrom() != null) {
                parts.add("CMT_TIME>=?");
                args.add(timestamp(query.getChangedFrom()));
            }
            if (query.getChangedTo() != null) {
                parts.add("CMT_TIME<?");
                args.add(timestamp(query.getChangedTo()));
            }
        }
        sql.append(" WHERE ").append(join(parts, " AND "));
    }

    /**
     * 转义 LIKE 模式里的通配符。用户给的变量名里出现 {@code %} / {@code _} 时不转义，
     * 一次精确查询会静默变成模糊查询。
     */
    private static String escapeLike(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 4);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%' || c == '_' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private WfComment mapComment(ResultSet rs) throws SQLException {
        WfComment comment = new WfComment();
        comment.setId(rs.getString("CMT_ID"));
        comment.setProcessInstanceId(rs.getString("PROC_ID"));
        comment.setTaskId(rs.getString("TASK_ID"));
        comment.setUserId(rs.getString("USER_ID"));
        comment.setType(rs.getString("CMT_TYPE"));
        comment.setContent(rs.getString("CONTENT"));
        comment.setTime(date(rs.getTimestamp("CMT_TIME")));
        return comment;
    }

    @Override
    public List<WfComment> findComments(String processInstanceId) {
        return queryList("SELECT * FROM ZWF_COMMENT WHERE PROC_ID=? ORDER BY CMT_TIME ASC",
                new Object[]{processInstanceId}, new RowMapper<WfComment>() {
                    @Override
                    public WfComment map(ResultSet rs) throws SQLException {
                        WfComment comment = new WfComment();
                        comment.setId(rs.getString("CMT_ID"));
                        comment.setProcessInstanceId(rs.getString("PROC_ID"));
                        comment.setTaskId(rs.getString("TASK_ID"));
                        comment.setUserId(rs.getString("USER_ID"));
                        comment.setType(rs.getString("CMT_TYPE"));
                        comment.setContent(rs.getString("CONTENT"));
                        comment.setTime(date(rs.getTimestamp("CMT_TIME")));
                        return comment;
                    }
                });
    }

    // ==================== JDBC 辅助 ====================

    /** 行映射。 */
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private <T> T queryOne(String sql, Object arg, RowMapper<T> mapper) {
        return queryOne(sql, new Object[]{arg}, mapper);
    }

    private <T> T queryOne(String sql, Object[] args, RowMapper<T> mapper) {
        List<T> list = queryList(sql, args, mapper);
        return list.isEmpty() ? null : list.get(0);
    }

    private <T> List<T> queryList(String sql, Object[] args, RowMapper<T> mapper) {
        Connection connection = null;
        List<T> result = new ArrayList<>();
        try {
            connection = dataSource.getConnection();
            PreparedStatement ps = connection.prepareStatement(sql);
            try {
                bind(ps, args);
                ResultSet rs = ps.executeQuery();
                try {
                    while (rs.next()) {
                        result.add(mapper.map(rs));
                    }
                } finally {
                    rs.close();
                }
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            throw new WfPersistenceException("查询失败: " + sql, e);
        } finally {
            close(connection);
        }
        return result;
    }

    // ==================== Job ====================

    @Override
    public void saveJob(WfJob job) {
        boolean exists = exists("SELECT 1 FROM ZWF_JOB WHERE JOB_ID=?", job.getId());
        if (exists) {
            int affected = update("UPDATE ZWF_JOB SET RETRIES=?, CYCLE_INDEX=?, EXCEPTION_MSG=?, "
                            + "LAST_FAIL_TIME=?, DUEDATE=?, JOB_TYPE=?, TOPIC=?, LOCKED_BY=?, "
                            + "LOCK_AT=?, SUBSCRIPTION_NAME=?, REV=? WHERE JOB_ID=? AND REV=?",
                    job.getRetries(), job.getCycleIndex(), job.getExceptionMessage(),
                    timestamp(job.getLastFailureTime()), timestamp(job.getDuedate()),
                    // 类型必须跟着 UPDATE 走。只写 INSERT 的话，任何对已有 job 的
                    // 类型调整存回去都会被抹回 TIMER —— 而 job 的类型决定扫描器
                    // 捞不捞它，抹错的直接后果是"消息订阅被当成到期 job 执行"
                    job.getType() == null ? "TIMER" : job.getType().name(),
                    // 租约三列必须跟着 UPDATE 走：worker 领活就是一次 UPDATE，
                    // 漏了的话领活不生效 —— 表现为"两个 worker 领到同一件活"
                    job.getTopic(), job.getLockedBy(), timestamp(job.getLockedAt()),
                    // 订阅名必须跟着 UPDATE 走：新建 job 时它写在 INSERT 上，
                    // 而流程推进会先把 job 查出来改一改再存回去，
                    // 漏掉这一列等于每次更新都把订阅名抹成 null
                    job.getSubscriptionName(),
                    job.getRevision(), job.getId(), job.getRevision() - 1);
            if (affected == 0) {
                throw new WfOptimisticLockException("job", job.getId(), job.getRevision() - 1);
            }
            return;
        }
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ZWF_JOB (JOB_ID, PROC_ID, EXEC_ID, ELEMENT_ID, ATTACHED_TO, "
                            + "JOB_TYPE, TOPIC, LOCKED_BY, LOCK_AT, DUEDATE, RETRIES, "
                            + "CYCLE_INDEX, EXCEPTION_MSG, SUBSCRIPTION_NAME, CREATE_TIME, "
                            + "LAST_FAIL_TIME, REV) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
            try {
                int i = 1;
                ps.setString(i++, job.getId());
                ps.setString(i++, job.getProcessInstanceId());
                ps.setString(i++, job.getExecutionId());
                ps.setString(i++, job.getElementId());
                ps.setString(i++, job.getAttachedToRef());
                ps.setString(i++, job.getType() == null ? "TIMER" : job.getType().name());
                ps.setString(i++, job.getTopic());
                ps.setString(i++, job.getLockedBy());
                ps.setTimestamp(i++, timestamp(job.getLockedAt()));
                ps.setTimestamp(i++, timestamp(job.getDuedate()));
                ps.setInt(i++, job.getRetries());
                ps.setInt(i++, job.getCycleIndex());
                ps.setString(i++, job.getExceptionMessage());
                ps.setString(i++, job.getSubscriptionName());
                ps.setTimestamp(i++, timestamp(job.getCreateTime()));
                ps.setTimestamp(i++, timestamp(job.getLastFailureTime()));
                ps.setInt(i, job.getRevision());
                ps.executeUpdate();
            } finally {
                closeQuietly(ps);
            }
        } catch (SQLException e) {
            throw new WfPersistenceException("保存 job 失败: " + job.getId(), e);
        } finally {
            close(connection);
        }
    }

    @Override
    public void deleteJob(String id) {
        update("DELETE FROM ZWF_JOB WHERE JOB_ID=?", id);
    }

    @Override
    public WfJob findJob(String id) {
        return queryOne("SELECT * FROM ZWF_JOB WHERE JOB_ID=?", id, jobMapper);
    }

    @Override
    public List<WfJob> lockExternalTasks(String topic, String workerId, int maxTasks,
                                         java.util.Date staleBefore) {
        StringBuilder select = new StringBuilder(
                "SELECT JOB_ID FROM ZWF_JOB WHERE JOB_TYPE='EXTERNAL' AND TOPIC=? AND RETRIES > 0");
        List<Object> args = new ArrayList<>();
        args.add(topic);
        // 退避窗口：失败后 duedate 被写成"最早可领时刻"，到点前不给人领。
        // 不加这个条件的话 fail 里的退避时长就是摆设 —— 解锁即被立刻领走，
        // 重试次数在几毫秒内烧光，而瞬时故障（下游重启）本来重试一次就能过。
        // NULL 表示"刚挂上、立即可领"，与外部任务首次创建时 duedate 为空一致
        select.append(" AND (DUEDATE IS NULL OR DUEDATE <= ?)");
        args.add(timestamp(new java.util.Date()));
        // 锁条件：没锁的，或者锁已过期的。锁没过期就不给抢 ——
        // 外部动作通常不可重入，抢别人的活等于让同一件事做两遍
        if (staleBefore == null) {
            select.append(" AND (LOCKED_BY IS NULL OR LOCKED_BY='')");
        } else {
            select.append(" AND (LOCKED_BY IS NULL OR LOCKED_BY='' OR LOCK_AT < ?)");
            args.add(timestamp(staleBefore));
        }
        select.append(" ORDER BY CREATE_TIME ASC, JOB_ID ASC LIMIT ?");
        args.add(maxTasks);

        List<String> ids = queryList(select.toString(), args.toArray(),
                new RowMapper<String>() {
                    @Override
                    public String map(ResultSet rs) throws SQLException {
                        return rs.getString("JOB_ID");
                    }
                });
        List<WfJob> locked = new ArrayList<WfJob>();
        java.util.Date now = new java.util.Date();
        for (String id : ids) {
            WfJob job = findJob(id);
            if (job == null) {
                continue;
            }
            // 逐条乐观锁上锁：并发领活时只有一方成功，
            // 失败的那些跳过（对方已经领走了）—— 这也顺带实现了"同一件活只被领一次"
            job.setLockedBy(workerId);
            job.setLockedAt(now);
            job.nextRevision();
            try {
                saveJob(job);
                locked.add(job);
            } catch (WfOptimisticLockException e) {
                log.debug("外部任务 {} 已被别的 worker 领走", id);
            }
        }
        return locked;
    }

    @Override
    public List<WfJob> queryJobs(WfJobQuery query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ZWF_JOB");
        List<Object> args = new ArrayList<>();
        appendJobFilters(sql, args, query);
        // 到期时刻正序 + id 兜底：执行器要"最早到点的先做"，
        // 同一时刻的多个 job 顺序随机会让日志对不上，也不好复现
        sql.append(" ORDER BY DUEDATE ASC, JOB_ID ASC LIMIT ? OFFSET ?");
        args.add(query == null || query.getPageSize() <= 0 ? 50 : query.getPageSize());
        args.add(query == null ? 0 : query.getOffset());
        return queryList(sql.toString(), args.toArray(), jobMapper);
    }

    @Override
    public long countJobs(WfJobQuery query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ZWF_JOB");
        List<Object> args = new ArrayList<>();
        appendJobFilters(sql, args, query);
        Long count = queryOne(sql.toString(), args.toArray(), COUNT_MAPPER);
        return count == null ? 0L : count;
    }

    @Override
    public int deleteJobsByProcessInstance(String processInstanceId) {
        return update("DELETE FROM ZWF_JOB WHERE PROC_ID=?", processInstanceId);
    }

    @Override
    public int deleteJobsByExecution(String executionId) {
        return update("DELETE FROM ZWF_JOB WHERE EXEC_ID=?", executionId);
    }

    /**
     * job 过滤。与 {@link #queryJobs} / {@link #countJobs} 共用 ——
     * 执行器日志说"处理了 N 个"而 count 是另一个数时，这份日志就没法用了。
     */
    private void appendJobFilters(StringBuilder sql, List<Object> args, WfJobQuery query) {
        if (query == null) {
            return;
        }
        List<String> parts = new ArrayList<>();
        if (query.getProcessInstanceId() != null) {
            parts.add("PROC_ID=?");
            args.add(query.getProcessInstanceId());
        }
        if (query.getElementId() != null) {
            parts.add("ELEMENT_ID=?");
            args.add(query.getElementId());
        }
        if (query.getDueBefore() != null) {
            // 严格小于：边界时刻的 job 归下一轮，避免同一秒被两个执行器各处理一次
            parts.add("DUEDATE < ?");
            args.add(timestamp(query.getDueBefore()));
        }
        if (query.getType() != null) {
            // 扫描器只捞定时器。消息 / 信号订阅的 duedate 是 null，
            // SQL 里 NULL 比较恒不成立，本来就捞不到 —— 但显式按类型过滤是第二道保险：
            // 哪天谁给订阅填了个 duedate，"还没发消息流程自己往前走了"就是这么来的
            parts.add("JOB_TYPE=?");
            args.add(query.getType().name());
        }
        if (query.getRetriesExhausted() != null) {
            if (query.getRetriesExhausted()) {
                parts.add("RETRIES <= 0");
            } else {
                parts.add("RETRIES > 0");
            }
        }
        if (query.getTopic() != null && !query.getTopic().trim().isEmpty()) {
            parts.add("TOPIC=?");
            args.add(query.getTopic());
        }
        if (!parts.isEmpty()) {
            sql.append(" WHERE ").append(join(parts, " AND "));
        }
    }

    private final RowMapper<WfJob> jobMapper = new RowMapper<WfJob>() {
        @Override
        public WfJob map(ResultSet rs) throws SQLException {
            WfJob job = new WfJob();
            job.setId(rs.getString("JOB_ID"));
            job.setProcessInstanceId(rs.getString("PROC_ID"));
            job.setExecutionId(rs.getString("EXEC_ID"));
            job.setElementId(rs.getString("ELEMENT_ID"));
            job.setAttachedToRef(rs.getString("ATTACHED_TO"));
            String jobType = rs.getString("JOB_TYPE");
            // 读不到就当定时器：存量行的语义不会变，而误判成订阅会让
            // 到期定时器永远不被扫描器捞到
            job.setType(WfJobType.TIMER.name().equals(jobType)
                    ? WfJobType.TIMER : parseJobType(jobType));
            job.setTopic(rs.getString("TOPIC"));
            job.setLockedBy(rs.getString("LOCKED_BY"));
            job.setLockedAt(date(rs.getTimestamp("LOCK_AT")));
            job.setDuedate(date(rs.getTimestamp("DUEDATE")));
            job.setRetries(rs.getInt("RETRIES"));
            job.setCycleIndex(rs.getInt("CYCLE_INDEX"));
            job.setExceptionMessage(rs.getString("EXCEPTION_MSG"));
            job.setSubscriptionName(rs.getString("SUBSCRIPTION_NAME"));
            job.setCreateTime(date(rs.getTimestamp("CREATE_TIME")));
            job.setLastFailureTime(date(rs.getTimestamp("LAST_FAIL_TIME")));
            job.setRevision(rs.getInt("REV"));
            return job;
        }
    };

    private boolean exists(String sql, Object arg) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            PreparedStatement ps = connection.prepareStatement(sql);
            try {
                bind(ps, new Object[]{arg});
                ResultSet rs = ps.executeQuery();
                try {
                    return rs.next();
                } finally {
                    rs.close();
                }
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            throw new WfPersistenceException("存在性检查失败: " + sql, e);
        } finally {
            close(connection);
        }
    }

    private int update(String sql, Object... args) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            PreparedStatement ps = connection.prepareStatement(sql);
            try {
                bind(ps, args);
                return ps.executeUpdate();
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            throw new WfPersistenceException("更新失败: " + sql, e);
        } finally {
            close(connection);
        }
    }

    private void bind(PreparedStatement ps, Object[] args) throws SQLException {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                ps.setObject(i + 1, null);
            } else if (arg instanceof String) {
                ps.setString(i + 1, (String) arg);
            } else if (arg instanceof Integer) {
                ps.setInt(i + 1, (Integer) arg);
            } else if (arg instanceof Long) {
                ps.setLong(i + 1, (Long) arg);
            } else if (arg instanceof Timestamp) {
                ps.setTimestamp(i + 1, (Timestamp) arg);
            } else {
                ps.setObject(i + 1, arg);
            }
        }
    }

    private void appendIfNotBlank(StringBuilder sql, List<Object> args, String clause, String value) {
        if (value != null && !value.trim().isEmpty()) {
            sql.append(clause);
            args.add(value);
        }
    }

    private Timestamp timestamp(Date date) {
        return date == null ? null : new Timestamp(date.getTime());
    }

    private Date date(Timestamp ts) {
        return ts == null ? null : new Date(ts.getTime());
    }

    /**
     * JSON 文本 → Map。
     * <p>用 {@code JsonUtil.parseToMap} 而非 {@code fromJsonQuietly(json, Map.class)}：
     * 后者对无值类型信息的 {@code Map.class} 会返回空/失败（实测取不到任何 key），
     * 症状是"变量列写进去了、读出来全没了" —— 且不报错，正是最难查的一类。
     */
    private java.util.Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new java.util.HashMap<>();
        }
        try {
            java.util.Map<String, Object> map = JsonUtil.parseToMap(json);
            return map == null ? new java.util.HashMap<String, Object>() : map;
        } catch (Exception e) {
            log.warn("变量 JSON 解析失败，按空处理: {}", json, e);
            return new java.util.HashMap<>();
        }
    }

    /**
     * JSON 数组文本 → {@code List<String>}。
     * <p>同样<b>不能</b>用 {@code fromJsonQuietly(json, List.class)}：它对无值类型信息的
     * {@code List.class} 取不到任何元素且不报错（实测返回 null/空）。
     * 症状是"候选组/候选人在库里明明有，读出来是空" ⇒ 所有人都不满足认领条件 ⇒
     * 可认领任务看得见却谁也认领不了。
     */
    private List<String> fromJsonList(String json) {
        List<String> result = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return result;
        }
        try {
            JsonArray array = JsonUtil.parseArray(json);
            if (array == null) {
                return result;
            }
            for (int i = 0; i < array.size(); i++) {
                Object item = array.get(i);
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        } catch (Exception e) {
            log.warn("列表 JSON 解析失败，按空处理: {}", json, e);
        }
        return result;
    }

    private void close(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("关闭连接失败", e);
            }
        }
    }

    /**
     * 关闭 {@link Statement}，失败只记 debug。
     *
     * <p>与 {@link #close(Connection)} 分开是因为语义不同：连接关不掉是资源泄漏要 warn，
     * 而 Statement 通常在异常路径上关闭，它关不掉说明主异常已经报过了，
     * 再 warn 一次只会把真正的堆栈淹掉。
     */
    private void closeQuietly(Statement statement) {
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException e) {
                log.debug("关闭 Statement 失败: {}", e.getMessage());
            }
        }
    }
}
