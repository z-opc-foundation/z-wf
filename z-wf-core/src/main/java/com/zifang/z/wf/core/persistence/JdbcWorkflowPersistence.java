package com.zifang.z.wf.core.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonArray;
import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfFlow;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
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
                + "PRIMARY KEY (DEF_KEY, DEF_VERSION))");

        ddl.add("CREATE TABLE IF NOT EXISTS ZWF_PROCESS ("
                + "PROC_ID VARCHAR(128) NOT NULL,"
                + "DEF_KEY VARCHAR(128),"
                + "DEF_ID VARCHAR(256),"
                + "DEF_VERSION INTEGER,"
                + "BUSINESS_KEY VARCHAR(256),"
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
                + "PARENT_TASK_ID VARCHAR(128),"
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
            log.info("JDBC 持久化初始化完成（{} 张表）", ddl.size() - 5);
        } catch (SQLException e) {
            throw new WfPersistenceException("建表失败", e);
        } finally {
            close(connection);
        }
    }

    @Override
    public void clear() {
        String[] tables = {"ZWF_COMMENT", "ZWF_ACTIVITY", "ZWF_TASK", "ZWF_EXECUTION",
                "ZWF_PROCESS", "ZWF_DEFINITION"};
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

    // ==================== 定义 ====================

    @Override
    public void saveDefinition(WfDefinition definition) {
        // 图结构序列化：走专用编解码（不直接 toJson(definition)）
        String graph = WfDefinitionCodec.encode(definition);
        String sql = "INSERT INTO ZWF_DEFINITION "
                + "(DEF_KEY, DEF_VERSION, DEF_NAME, DEF_CATEGORY, DEF_DESCRIPTION, "
                + " DEF_GRAPH, SOURCE_XML, DEPLOY_TIME) VALUES (?,?,?,?,?,?,?,?)";
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

    @Override
    public WfDefinition findLatestDefinition(String key) {
        String sql = "SELECT DEF_VERSION, DEF_GRAPH FROM ZWF_DEFINITION "
                + "WHERE DEF_KEY=? ORDER BY DEF_VERSION DESC";
        return queryOne(sql, key, new RowMapper<WfDefinition>() {
            @Override
            public WfDefinition map(ResultSet rs) throws SQLException {
                return deserializeDefinition(rs.getString("DEF_GRAPH"));
            }
        });
    }

    @Override
    public WfDefinition findDefinition(String key, int version) {
        String sql = "SELECT DEF_VERSION, DEF_GRAPH FROM ZWF_DEFINITION "
                + "WHERE DEF_KEY=? AND DEF_VERSION=?";
        return queryOne(sql, new Object[]{key, version}, new RowMapper<WfDefinition>() {
            @Override
            public WfDefinition map(ResultSet rs) throws SQLException {
                return deserializeDefinition(rs.getString("DEF_GRAPH"));
            }
        });
    }

    @Override
    public List<WfDefinition> findDefinitionVersions(String key) {
        String sql = "SELECT DEF_GRAPH FROM ZWF_DEFINITION WHERE DEF_KEY=? ORDER BY DEF_VERSION DESC";
        return queryList(sql, new Object[]{key}, new RowMapper<WfDefinition>() {
            @Override
            public WfDefinition map(ResultSet rs) throws SQLException {
                return deserializeDefinition(rs.getString("DEF_GRAPH"));
            }
        });
    }

    @Override
    public List<WfDefinition> findAllDefinitions() {
        // 每个 key 只取最大版本
        String sql = "SELECT DEF_GRAPH FROM ZWF_DEFINITION D WHERE DEF_VERSION = "
                + "(SELECT MAX(DEF_VERSION) FROM ZWF_DEFINITION WHERE DEF_KEY = D.DEF_KEY)";
        return queryList(sql, new Object[0], new RowMapper<WfDefinition>() {
            @Override
            public WfDefinition map(ResultSet rs) throws SQLException {
                return deserializeDefinition(rs.getString("DEF_GRAPH"));
            }
        });
    }

    @Override
    public List<WfDefinition> findDefinitionsByCategory(String category) {
        String sql = "SELECT D.DEF_GRAPH FROM ZWF_DEFINITION D "
                + "WHERE D.DEF_CATEGORY=? AND D.DEF_VERSION = "
                + "(SELECT MAX(DEF_VERSION) FROM ZWF_DEFINITION WHERE DEF_KEY = D.DEF_KEY)";
        return queryList(sql, new Object[]{category}, new RowMapper<WfDefinition>() {
            @Override
            public WfDefinition map(ResultSet rs) throws SQLException {
                return deserializeDefinition(rs.getString("DEF_GRAPH"));
            }
        });
    }

    private WfDefinition deserializeDefinition(String graph) {
        // 走专用编解码：直接 JsonUtil.fromJson(definition) 会因 Date 字段静默返回 null
        // （详见 WfDefinitionCodec 的类注释）
        return WfDefinitionCodec.decode(graph);
    }

    // ==================== 流程实例 ====================

    @Override
    public void saveProcessInstance(WfProcessInstance instance) {
        boolean exists = exists("SELECT 1 FROM ZWF_PROCESS WHERE PROC_ID=?", instance.getId());
        if (exists) {
            // 乐观锁：只允许 revision 恰好 +1 的那次写入
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
                + "START_USER_ID, START_DEPT_ID, CATEGORY, STATUS, RESULT, START_TIME, END_TIME, "
                + "SUSPEND_REASON, DELETE_REASON, VARIABLES, REVISION) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
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
                ps.setString(6, instance.getStartUserId());
                ps.setString(7, instance.getStartDeptId());
                ps.setString(8, instance.getCategory());
                ps.setString(9, instance.getStatus() == null ? null : instance.getStatus().name());
                ps.setString(10, instance.getResult());
                ps.setTimestamp(11, timestamp(instance.getStartTime()));
                ps.setTimestamp(12, timestamp(instance.getEndTime()));
                ps.setString(13, instance.getSuspendReason());
                ps.setString(14, instance.getDeleteReason());
                ps.setString(15, JsonUtil.toJson(instance.getVariables()));
                ps.setInt(16, instance.getRevision());
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
        List<WfProcessInstance> all = queryList(sql.toString(), args.toArray(), instanceMapper());
        int from = query == null ? 0 : query.getOffset();
        int size = query == null || query.getPageSize() <= 0 ? 20 : query.getPageSize();
        return from >= all.size() ? new ArrayList<WfProcessInstance>()
                : new ArrayList<>(all.subList(from, Math.min(all.size(), from + size)));
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
        appendIfNotBlank(sql, args, " AND DEF_KEY=?", query.getDefinitionKey());
        appendIfNotBlank(sql, args, " AND BUSINESS_KEY=?", query.getBusinessKey());
        appendIfNotBlank(sql, args, " AND START_USER_ID=?", query.getStartUserId());
        appendIfNotBlank(sql, args, " AND CATEGORY=?", query.getCategory());
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
                            + "END_TIME=?, COMPLETER_ID=?, COMMENT_TEXT=?, VARIABLES=?, REVISION=? "
                            + "WHERE TASK_ID=? AND REVISION=?",
                    task.getStatus() == null ? null : task.getStatus().name(),
                    task.getAssignee(), task.getOwner(), task.getPriority(),
                    timestamp(task.getEndTime()), task.getCompleterId(), task.getComment(),
                    JsonUtil.toJson(task.getVariables()), task.getRevision(),
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
                            + "COMMENT_TEXT, VARIABLES, PARENT_TASK_ID, REVISION) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
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
                ps.setString(i++, task.getParentTaskId());
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
        if (query != null) {
            appendIfNotBlank(sql, args, " AND PROC_ID=?", query.getProcessInstanceId());
            appendIfNotBlank(sql, args, " AND DEF_ID=?", query.getDefinitionId());
            appendIfNotBlank(sql, args, " AND COMPLETER_ID=?", query.getCompleterId());
            appendIfNotBlank(sql, args, " AND CATEGORY=?", query.getCategory());
            if (query.getAssignee() != null || query.getOwner() != null) {
                // 待办 = assignee 命中 或 owner 命中（委派态）
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
            // 候选人过滤：CANDIDATE_* 存的是 JSON 数组文本，用 LIKE 做子串匹配。
            // 精度有限（"u1" 会命中 "u12"），但审批候选池通常是几十人的量级，
            // 且"多召回几个再由 service 层用 isClaimableBy 精确判定"比"漏召回"安全 ——
            // 反过来（SQL 过滤过严导致可认领的人看不到）才是真问题。
            if (query.getCandidateUsers() != null && !query.getCandidateUsers().isEmpty()) {
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
            if (query.getCandidateGroups() != null && !query.getCandidateGroups().isEmpty()) {
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
        // 与内存实现同序：未完成优先 → 优先级高优先 → 新建优先
        sql.append(" ORDER BY CASE STATUS WHEN 'COMPLETED' THEN 1 ELSE 0 END, PRIORITY DESC, CREATE_TIME DESC");
        List<WfTask> all = queryList(sql.toString(), args.toArray(), new RowMapper<WfTask>() {
            @Override
            public WfTask map(ResultSet rs) throws SQLException {
                return mapTask(rs);
            }
        });
        int from = query == null ? 0 : query.getOffset();
        int size = query == null || query.getPageSize() <= 0 ? 20 : query.getPageSize();
        return from >= all.size() ? new ArrayList<WfTask>()
                : new ArrayList<>(all.subList(from, Math.min(all.size(), from + size)));
    }

    private WfTask mapTask(ResultSet rs) throws SQLException {
        WfTask task = new WfTask();
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
        task.setParentTaskId(rs.getString("PARENT_TASK_ID"));
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
    public List<WfActivityInstance> findActivityInstances(String processInstanceId) {
        return queryList("SELECT * FROM ZWF_ACTIVITY WHERE PROC_ID=? ORDER BY START_TIME ASC",
                new Object[]{processInstanceId}, new RowMapper<WfActivityInstance>() {
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
                });
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
}
