package com.zifang.z.wf.core.persistence;

import java.util.Date;
import java.util.List;
import java.util.Map;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfJob;
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

    /**
     * 物理删除某个版本的定义。
     *
     * <p><b>调用方必须先确认没有在跑的实例</b>：本引擎每次推进都按
     * {@code (definitionKey, version)} 重新载入定义，定义一删，那个实例就再也推不动了
     * （下一次 completeTask 报"流程定义不存在"，且永远不会自愈）。
     * 持久层不做这个判断 —— 它没有"实例是否在途"的口径，猜错比不拦更糟。
     *
     * @return 是否真的删掉了那一行
     */
    boolean deleteDefinition(String key, int version);

    /**
     * 改某个版本的停用状态。
     *
     * <p>不存在时返回 {@code false}，由上层决定报什么错 —— 持久层不猜"是不是 key 拼错了"。
     *
     * @return 是否有那一行被改到
     */
    boolean setDefinitionSuspended(String key, int version, boolean suspended);

    /**
     * 按 key / name 模糊 + 停用状态查定义（最新版本，每个 key 一行）。
     *
     * <p>key 与 name <b>都</b>能筛：调用方手里通常只有 key（其他所有端点都以 key 为准），
     * 只给 name 的话每个列表调用都得先查一次名称，很别扭。
     *
     * @param keyLike     key 模糊匹配，{@code null} / 空表示不限
     * @param nameLike    显示名模糊匹配，{@code null} / 空表示不限
     * @param suspended   {@code null} 表示不限；{@code TRUE} 只看已停用，{@code FALSE} 只看在用
     */
    List<WfDefinition> findDefinitions(String keyLike, String nameLike, Boolean suspended);

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

    /**
     * 保存任务。
     *
     * <p><b>调用前必须先 {@code nextRevision()}。</b> 两个实现都按
     * "库中 revision + 1 == 传入的 revision"做 CAS，忘记 bump 会被判成冲突。
     * 这一点没有在接口上写明过，实现者与直接使用 SPI 的人都会踩，
     * 所以在这里明确：<b>本 SPI 不自动改 revision，改版本号是调用方的责任。</b>
     *
     * <p>取出来的对象<b>不要就地改完直接存</b>：查询实现返回的是副本，
     * 而副本的 revision 可能已经落后于库里（别的请求改过）。
     * 正确做法是重新查询、确认 revision、再改再存。
     */
    void saveTask(WfTask task);

    void deleteTask(String id);

    WfTask findTask(String id);

    /**
     * 查任务列表。
     *
     * <p>条件自相矛盾（{@code openOnly} 与 {@code completedOnly} 同时为真、
     * 创建时间区间倒置）时实现必须抛 {@link IllegalArgumentException}，
     * 不允许拼出恒假条件后返回一个空列表。
     */
    List<WfTask> queryTasks(WfTaskQuery query);

    /**
     * 任务条数，与 {@link #queryTasks} 的条件必须一致，<b>忽略分页</b>。
     *
     * <p>单独给一个 count 而不是"把 pageSize 设成最大再数长度"：
     * 后者在 JDBC 上就是一次全表拉取，待办列表页每翻一页都付一次。
     */
    long countTasks(WfTaskQuery query);

    // ==================== 历史 ====================

    void saveActivityInstance(WfActivityInstance instance);

    List<WfActivityInstance> findActivityInstances(String processInstanceId);

    /**
     * 按条件查询历史活动实例。
     *
     * <p>此前只有"按流程实例取全量轨迹"这一个口径，导致"上个月所有走完的流程里
     * 哪一步最慢"这类问题只能逐个流程查完再在内存里聚合。
     *
     * @param query 条件；{@code null} 表示查全部
     */
    List<WfActivityInstance> queryActivityInstances(WfHistoricActivityInstanceQuery query);

    /** 历史活动实例条数，与 {@link #queryActivityInstances} 的条件必须一致。 */
    long countActivityInstances(WfHistoricActivityInstanceQuery query);

    /**
     * 删除 {@code endTime} 早于 {@code before} 的<b>已结束</b>流程实例的全部历史。
     *
     * <p>只清理已结束的：在途流程的历史删掉之后，审批轨迹会出现一个空洞，
     * 而单据还在被人办 —— 那比历史表大得多更难解释。
     *
     * @return 被删除的流程实例数
     */
    int deleteHistoryBefore(Date before);

    void saveComment(WfComment comment);

    List<WfComment> findComments(String processInstanceId);

    /**
     * 按条件查<b>变量变更</b>审计（{@code WfComment.type = "variable"}）。
     *
     * <p>单列一个方法而不是让调用方拿 {@code findComments} 自己过滤：
     * 过滤要按"内容以变量名开头"来做（不是子串匹配），还要按操作人、
     * 时间区间筛，最后按时间倒序 —— 这些规则写在两套实现里各一遍必然漂移，
     * 而漂移的表现是"审计少了一条"，那种漏检最难被发现。
     */
    List<WfComment> queryVariableAudits(WfVariableAuditQuery query);

    /** 变更条数，与 {@link #queryVariableAudits} 的条件必须一致。 */
    long countVariableAudits(WfVariableAuditQuery query);

    // ==================== Job ====================

    /**
     * 保存 job。
     *
     * <p>契约与 {@link #saveTask} 一致：<b>调用方必须自己先 {@code nextRevision()}</b>，
     * 实现按"库里 revision + 1 == 传入 revision"做 CAS，0 行受影响就抛
     * {@link WfOptimisticLockException}。
     */
    void saveJob(WfJob job);

    void deleteJob(String id);

    WfJob findJob(String id);

    /**
     * 查 job 列表。
     *
     * <p>默认按到期时刻正序：执行器要的是"最早到点的先做"，
     * 顺序错了会让一批同时到点的 job 里靠后的被饿死。
     */
    List<WfJob> queryJobs(WfJobQuery query);

    long countJobs(WfJobQuery query);

    /**
     * 删掉某个流程实例下的全部 job。
     *
     * <p>实例终止/删历史时必须调用，否则残留的 job 会在到期时去找一个
     * 已经不存在的实例，把"流程早就结束了"变成一条莫名其妙的执行失败。
     */
    int deleteJobsByProcessInstance(String processInstanceId);

    /**
     * 删掉挂在某个 token 上的全部 job（token 正常离开节点时调用）。
     *
     * <p>不清理的后果：人已经按时办完了，30 分钟后定时器照样触发，
     * 把一条正常结束的流程拽进超时分支 —— 审批系统里这种 bug 最招骂。
     */
    int deleteJobsByExecution(String executionId);

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
