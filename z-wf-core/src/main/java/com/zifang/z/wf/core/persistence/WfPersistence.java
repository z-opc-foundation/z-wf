package com.zifang.z.wf.core.persistence;

import java.util.Date;
import java.util.List;
import java.util.Map;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.dmn.WfDmnDecision;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfFilter;
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
     * 按 topic 领取外部任务（原子地"选出 + 上锁"）。
     *
     * <p>返回的 job 已带 {@code lockedBy=workerId} 与 {@code lockedAt=now}，
     * 调用方直接存回去即可。<b>必须一次 SQL 完成筛选与上锁</b>：
     * 分成"先查后锁"的话，两个 worker 会领到同一件活，而外部动作（调接口、发消息）
     * 通常不可重入，重复执行的后果由外部系统承担。
     *
     * @param topic           主题名
     * @param workerId        领活人
     * @param maxTasks        最多领几件
     * @param staleBefore     锁定早于该时刻的视为已过期（worker 崩了），可被别人重新领走；
     *                        {@code null} 表示不抢占未过期的锁
     */
    List<WfJob> lockExternalTasks(String topic, String workerId, int maxTasks,
                                  java.util.Date staleBefore);

    /**
     * 改某个版本的停用状态。
     *
     * <p>不存在时返回 {@code false}，由上层决定报什么错 —— 持久层不猜"是不是 key 拼错了"。
     *
     * @return 是否有那一行被改到
     */
    boolean setDefinitionSuspended(String key, int version, boolean suspended);

    /**
     * 改某个版本的「是不是默认流程定义」标记。
     *
     * <p><b>置 true 时会先把全表其它行清成 false</b>，因为「全库至多一条默认」
     * 是这个特性的全部意义：两个默认等于调用方问「默认是哪个」时拿到两个答案，
     * 而没有任何报错能提示他挑错了。
     *
     * <p>置 false 只清目标行（取消默认）。目标不存在时返回 {@code false}，
     * 由上层决定报什么错 —— 持久层不猜「是不是 key 拼错了」。
     *
     * @return 是否有那一行被改到
     */
    boolean setDefaultDefinition(String key, int version, boolean isDefault);

    /**
     * 当前那条默认流程定义。
     *
     * <p><b>没有默认时返回 {@code null}，有两条以上时抛异常</b>：
     * 后者意味着数据被绕过本接口改过（直接改库、或并发执行了两次置位），
     * 此时「随便返回一条」会让调用方以为默认是确定的 ——
     * 而它其实取决于行返回顺序。
     *
     * @return 默认定义；没设过默认时为 {@code null}
     * @throws WfPersistenceException 同时存在两条以上默认定义
     */
    WfDefinition findDefaultDefinition();

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
     * 改实例名称，<b>只改这一个字段</b>。
     *
     * <p><b>刻意不走乐观锁、也不碰 {@code revision}。</b>
     * 名字是给界面看的元数据，与实例的状态机无关 ——
     * 让"改个标题"和"审批推进"抢同一把 revision 锁，冲突时报出来的是
     * 「乐观锁冲突」，而两件事之间根本没有任何因果关系。
     *
     * <p>也正因如此，本方法<b>不能</b>走 {@link #saveProcessInstance}：
     * 那条 UPDATE 是部分更新，调用方手上的实例对象往往在改名之前取的，
     * 把 NAME 放进它的列清单会让每次状态回写顺手把名字抹掉。
     *
     * <p>{@code name} 传 {@code null} 表示清空名字（合法）。
     * 空串与纯空白<b>不</b>在持久层拦 —— 那是服务层的校验，理由见
     * {@code WfRuntimeService#setProcessInstanceName}。
     *
     * @return 受影响行数；{@code 0} 表示没有这个实例
     */
    int setProcessInstanceName(String processInstanceId, String name);

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

    /**
     * 按条件查执行令牌（<b>只下推能下推的那几项</b>：流程实例 / 节点 / 状态）。
     *
     * <p>刻意<b>不</b>包含变量条件与业务排序：变量存在 JSON 文本列里，
     * 各库写法不同，过滤只能回到 Java；过滤在分页之后，于是分页也不能下推。
     * 真正的过滤、排序、分页与扫描上限统一在
     * {@code WfExecutionQueryService} 里做 —— 只有那一份实现，
     * 两套存储不会在这中间分家。
     *
     * <p>这里的排序只保证<b>页内稳定</b>（按主键倒序），不承担业务语义 ——
     * {@code ENTERED_TIME} 可空，而"NULL 排前还是排后"在不同数据库上结论相反。
     *
     * <p><b>已按 query 的分页参数截断</b>（调用方通常会传一个很大的 pageSize，
     * 那是 service 层为了"先读够再过滤"故意为之，不是笔误）。
     */
    List<WfExecution> queryExecutions(WfExecutionQuery query);

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

    // ==================== 补偿登记（第 37 轮） ====================

    /**
     * 追加一条补偿登记（{@link com.zifang.z.wf.core.model.WfCompensationEntry}）。
     *
     * <p><b>只有插入，没有更新</b>：一行登记表示"这件事已经做过"，
     * 它的存在本身就是事实，改写事实会让补偿的幂等性无从谈起。
     * 补偿执行完毕时置 {@code done} 走 {@link #markCompensated}，
     * 它写的是"退过了"这个事实，与"做过"是两条不同的记录。
     */
    void saveCompensation(com.zifang.z.wf.core.model.WfCompensationEntry entry);

    /**
     * 某实例的补偿登记，<b>按 {@code seq} 正序</b>返回。
     *
     * <p>正序而不是逆序：调用方要做的是逆序补偿，正序交出去更不容易用错。
     * 与"按时间排序"相比，{@code seq} 是登记动作本身产生的次序，确定且无并列。
     */
    List<com.zifang.z.wf.core.model.WfCompensationEntry> findCompensations(String processInstanceId);

    /** 把一条登记标记成"已补偿"。返回受影响行数。 */
    int markCompensated(String id, java.util.Date when);

    /** 删掉某实例的全部补偿登记。 */
    int deleteCompensationsByProcessInstance(String processInstanceId);

    // ==================== 批次（第 39 轮） ====================

    /**
     * 保存（新建或覆盖）一个批次。
     *
     * <p><b>契约与 {@link #saveFilter} 一致</b>：{@code id == null} 走 INSERT，
     * 非空走 UPDATE 且带乐观锁 —— 调用方改既有批次前必须先 {@code nextRevision()}。
     *
     * <p>批次在执行期会被改好几遍（{@code EXECUTING} → {@code COMPLETED}），
     * 乐观锁正是为了在这种时候抓住「有人同时动它」。
     */
    void saveBatch(com.zifang.z.wf.core.model.WfBatch batch);

    /** 按 id 取一个批次；不存在返回 {@code null}（调用方自己决定要不要报错）。 */
    com.zifang.z.wf.core.model.WfBatch findBatch(String id);

    /**
     * 物理删除一个批次<b>及其全部明细</b>。
     *
     * <p>明细必须一起删：不删的话批次没了、失败记录还在，
     * 而按 batchId 查明细的接口会返回一批没有主人的行 —— 它们既查不到来源，
     * 也没法让人确认「这些失败的目标后来处理了没有」。
     *
     * @return 是否真的删掉了批次本体（明细删不掉也算成功，本体没了就够）
     */
    boolean deleteBatch(String id);

    List<com.zifang.z.wf.core.model.WfBatch> queryBatches(WfBatchQuery query);

    /** 条数，条件与 {@link #queryBatches} 必须一致，<b>忽略分页</b>。 */
    int countBatches(WfBatchQuery query);

    /**
     * 追加一条批次明细（{@link com.zifang.z.wf.core.model.WfBatchElement}）。
     *
     * <p><b>只有插入，没有更新</b>：一行明细表示「这个目标被处理过，结果是这样」，
     * 这是个已经发生的事实。批次重跑会再写一行新的，不去改上一次的结果 ——
     * 覆盖掉会让「上次那批到底哪些失败了」这个问题彻底失去答案。
     */
    void saveBatchElement(com.zifang.z.wf.core.model.WfBatchElement element);

    /** 某批次的全部明细，<b>按写入顺序</b>返回（与目标命中顺序一致）。 */
    List<com.zifang.z.wf.core.model.WfBatchElement> findBatchElements(String batchId);

    /**
     * 某批次的失败明细。
     *
     * <p>单独给一条入口而不是让调用方自己 filter：运维打开一个失败批次时
     * 几乎只想看失败的那些，而让它下全量再自己筛，
     * 一批 1000 条的批次每次都要多传 999 条数据。
     */
    List<com.zifang.z.wf.core.model.WfBatchElement> findFailedBatchElements(String batchId);

    /** 某批次的明细条数。 */
    int countBatchElements(String batchId);

    /** 删掉某批次的全部明细（{@link #deleteBatch} 内部会调）。 */
    int deleteBatchElements(String batchId);

    /**
     * 删掉挂在某个 token 上的全部 job（token 正常离开节点时调用）。
     *
     * <p>不清理的后果：人已经按时办完了，30 分钟后定时器照样触发，
     * 把一条正常结束的流程拽进超时分支 —— 审批系统里这种 bug 最招骂。
     */
    int deleteJobsByExecution(String executionId);

    // ==================== 保存筛选器 ====================

    /**
     * 保存（新建或覆盖）一个筛选器。
     *
     * <p><b>契约与 {@link #saveTask} / {@link #saveJob} 一致</b>：
     * {@code id == null} 走 INSERT，非空走 UPDATE，
     * 且 UPDATE 带乐观锁 —— 调用方改既有筛选器前必须先
     * {@code nextRevision()}，使传入对象的 revision 恰好是库里那份 +1。
     * 漏了它得到的是乐观锁冲突，而那是那条契约在工作，不是它坏了。
     *
     * <p>筛选器是共享资源，乐观锁不是可选项：
     * 管理员在改它、别人正在用它查，没有版本号时最后写的人赢，
     * 改的人会读成「我明明改了却没生效」。
     */
    void saveFilter(WfFilter filter);

    /** 按 id 取一个筛选器；不存在返回 {@code null}（调用方自己决定要不要报错）。 */
    WfFilter findFilter(String id);

    /** 删一个筛选器。删不存在的 id 返回 {@code false}，不抛异常。 */
    boolean deleteFilter(String id);

    List<WfFilter> queryFilters(WfFilterQuery query);

    /** 条数，条件与 {@link #queryFilters} 必须一致，<b>忽略分页</b>。 */
    int countFilters(WfFilterQuery query);

    // ==================== 决策（DMN 决策表）====================

    /**
     * 保存一份决策定义。
     *
     * <p><b>与流程定义同一套版本语义</b>：version 由 repository 分配后传入，
     * 同一 key 重复部署时旧版本<b>保留</b>。保留的理由也一样 ——
     * 决策表是会迭代的（"金额门槛从 1 万调到 5 万"），而在途的流程实例
     * 引用的是当时的决策；覆盖掉旧版本，那些实例下次求值就用新门槛判了。
     */
    void saveDecision(WfDmnDecision decision);

    /** 按 key + version 取决策；不存在返回 {@code null}（调用方自己决定要不要报错）。 */
    WfDmnDecision findDecision(String key, int version);

    /** 按 key 取<b>最新版本</b>的决策。 */
    WfDmnDecision findLatestDecision(String key);

    /** 全部版本（按 version 倒序），对应"这个决策改过几版、每版长什么样"。 */
    List<WfDmnDecision> findDecisionVersions(String key);

    /**
     * 物理删除某个版本的决策。
     *
     * <p>与 {@link #deleteDefinition} 一样<b>不做在途检查</b>：
     * 持久层没有"哪些流程正在引用这个决策"的口径，猜错比不拦更糟。
     * 删掉之后 {@code evaluateDecisionByKey} 会明确报"决策不存在"，
     * 而不是用同 key 的别的版本悄悄顶上 —— 后者会让同一个决策
     * 在部署前后给出不同结果，且没有任何报错。
     *
     * @return 是否真的删掉了那一行
     */
    boolean deleteDecision(String key, int version);

    // ==================== 生命周期 ====================

    /**
     * 初始化存储（建表等）。重复调用必须幂等。
     */
    void initialize();

    /**
     * 清空全部数据（仅测试用）。
     */
    void clear();

    // ==================== 存储自省 ====================

    /**
     * 引擎持有的<b>逻辑实体</b>名单（对应 Camunda 的 {@code getTableNames}）。
     *
     * <p>为什么叫"表"而内存实现里根本没有表：SPI 描述的是**引擎的持久化存储**，
     * 而两套实现持有的是<b>同一批逻辑实体</b>（定义 / 流程 / 令牌 / 任务 /
     * 作业 / 活动历史 / 评论 / 筛选器），一一对应。
     * 跨实现真正可比的是这层对应关系，而不是"底下是表还是 Map"。
     * 至于内存实现里那层 Map 到底是表还是集合，由
     * {@code WfManagementService} 在视图上显式标出 {@code kind}，不让调用方误认。
     *
     * <p>两套实现报的名单必须一致 —— 不一致意味着某一边漏了某种实体，
     * 而那种漏在功能上通常表现为"这种数据在内存模式下能查、在 JDBC 下查不到"，
     * 且两边测试都不一定碰到。
     */
    List<String> getTableNames();

    /**
     * 某个实体的条数。
     *
     * <p><b>名字不认识时必须抛异常，不能返回 0。</b>返回 0 的话，
     * 拼错一个表名会得到"这张表是空的"，而真相是"没有这张表" ——
     * 这两种情况在排障时的处置完全相反（前者在查谁没清理，后者在自己拼错了）。
     */
    long getTableCount(String name);
}
