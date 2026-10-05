package com.zifang.z.wf.core.persistence;

import java.util.List;
import java.util.Map;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;

/**
 * 持久化 SPI —— z-wf 的存储抽象层。
 *
 * <p><b>为什么把存储抽象成接口，而不是直接写 JDBC：</b>
 * z-wf 需要同时服务两类形态，二者对"事务边界"的要求完全不同：
 * <ul>
 *   <li><b>内存实现</b>（测试 / 单机小应用 / z-util-wf 那种"开箱即用"场景）：
 *       要的是零依赖、零配置、启动就能跑</li>
 *   <li><b>JDBC 实现</b>（生产多实例）：要的是乐观锁、索引、跨节点一致</li>
 * </ul>
 * 如果把 JDBC 写死成唯一实现，测试就得起数据库（CI 慢且脆）；
 * 如果只提供内存实现，生产就得接受"重启丢流程"。
 *
 * <p><b>事务语义约定</b>（所有实现必须遵守，否则上层乐观锁失效）：
 * <ol>
 *   <li>一次"推进"（start / complete / trigger / jump）里的所有写操作
 *       应当是<b>原子的</b>：要么全成功，要么全回滚</li>
 *   <li>{@link #saveProcessInstance} 带 {@link WfProcessInstance#getRevision()}，
 *       实现必须做 compare-and-set：revision 不匹配则抛
 *       {@link WfOptimisticLockException}，<b>不得静默覆盖</b></li>
 *   <li>查询方法返回<b>防御性副本</b>或只读视图。实现返回内部对象引用会被上层
 *       绕过持久化直接改内存态，是最难查的一类 bug</li>
 * </ol>
 *
 * <p>默认内存实现见 {@link InMemoryWorkflowPersistence}，JDBC 见 {@link JdbcWorkflowPersistence}。
 *
 * @author zifang
 */
public interface WfPersistence {

    // ==================== 流程定义 ====================

    /**
     * 保存流程定义。
     *
     * @param definition 定义（version 由 repository 分配后传入）
     */
    void saveDefinition(WfDefinition definition);

    /**
     * 按 key 取<b>最新版本</b>的定义。
     */
    WfDefinition findLatestDefinition(String key);

    /**
     * 按 key + version 取定义。
     */
    WfDefinition findDefinition(String key, int version);

    /**
     * 全部定义版本（按 version 倒序）。
     */
    List<WfDefinition> findDefinitionVersions(String key);

    /**
     * 全部定义（最新版本）。
     */
    List<WfDefinition> findAllDefinitions();

    /**
     * 按分类查定义（对应 z-camuda 的 Category 分组）。
     */
    List<WfDefinition> findDefinitionsByCategory(String category);

    // ==================== 流程实例 ====================

    /**
     * 保存/更新流程实例（乐观锁）。
     *
     * @throws WfOptimisticLockException revision 不匹配
     */
    void saveProcessInstance(WfProcessInstance instance);

    /**
     * 按 ID 取实例。
     */
    WfProcessInstance findProcessInstance(String id);

    /**
     * 按业务键查实例（审批场景的主查询路径：单号 → 流程）。
     */
    List<WfProcessInstance> findProcessInstancesByBusinessKey(String businessKey);

    /**
     * 查实例列表（<b>已按 query 的分页参数截断</b>）。
     *
     * @param query 查询条件（各字段可为空表示不过滤）
     */
    List<WfProcessInstance> queryProcessInstances(WfProcessInstanceQuery query);

    /**
     * 统计符合条件的实例<b>总条数</b>（不分页）。
     *
     * <p>与 {@link #queryProcessInstances} 成对存在，不是冗余：列表方法返回的是<b>当前页</b>，
     * 直接用它的 {@code size()} 当 total 会让前端分页器认为"只有一页"，
     * 翻到第 2 页就是空列表。分页组件要的是总数，必须单独问。
     *
     * <p>实现上必须复用列表查询的<b>同一段过滤条件</b>，否则 total 与列表必然对不上。
     *
     * @param query 查询条件（各字段可为空表示不过滤）
     * @return 命中总条数；无命中返回 0
     */
    long countProcessInstances(WfProcessInstanceQuery query);

    // ==================== 执行令牌 ====================

    void saveExecution(WfExecution execution);

    void deleteExecution(String id);

    WfExecution findExecution(String id);

    /**
     * 查某流程实例的全部 token。
     */
    List<WfExecution> findExecutionsByProcessInstance(String processInstanceId);

    // ==================== 任务 ====================

    void saveTask(WfTask task);

    void deleteTask(String id);

    WfTask findTask(String id);

    /**
     * 查任务列表。
     */
    List<WfTask> queryTasks(WfTaskQuery query);

    // ==================== 历史 ====================

    void saveActivityInstance(WfActivityInstance instance);

    List<WfActivityInstance> findActivityInstances(String processInstanceId);

    void saveComment(WfComment comment);

    List<WfComment> findComments(String processInstanceId);

    // ==================== 生命周期 ====================

    /**
     * 初始化存储（建表等）。重复调用必须幂等。
     */
    void initialize();

    /**
     * 清空全部数据（仅测试用）。
     */
    void clear();
}
