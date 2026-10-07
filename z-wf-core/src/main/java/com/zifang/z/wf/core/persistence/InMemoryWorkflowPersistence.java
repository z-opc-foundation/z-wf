package com.zifang.z.wf.core.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.dmn.WfDmnDecision;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfBatch;
import com.zifang.z.wf.core.model.WfBatchElement;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfCompensationEntry;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfFilter;
import com.zifang.z.wf.core.model.WfHistoricIncident;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.service.WfVariableService;

/**
 * 内存持久化实现 —— 零依赖默认实现。
 *
 * <p>定位：单元测试、单机小应用、以及"先跑通再接库"的开发阶段。
 * 也正好充当 {@link WfPersistence} 的<b>语义参考实现</b>：
 * 防御性拷贝、乐观锁、分页顺序这些容易写歪的规则在这里定死，
 * {@link JdbcWorkflowPersistence} 照着同一份语义实现。
 *
 * <p><b>实例存储的选择</b>：这里用 {@link ConcurrentHashMap} 存的是<b>对象本身</b>，
 * 每次读写都做深拷贝。理由是 SPI 契约要求"查询返回防御性副本"——
 * 若直接返回内部引用，上层改一下 task 状态就等于绕过了持久化，
 * 换成 JDBC 实现后这类 bug 全部消失（JDBC 天然是拷贝语义），
 * 导致"内存能跑、落库就错"。宁可每次拷贝也不能让内存实现成为乐观规则的漏洞。
 *
 * @author zifang
 */
public class InMemoryWorkflowPersistence implements WfPersistence {

    private static final Logger log = LoggerFactory.getLogger(InMemoryWorkflowPersistence.class);

    /** key → 版本号 → 定义。 */
    private final Map<String, Map<Integer, WfDefinition>> definitions =
            new LinkedHashMap<>();

    private final Map<String, WfProcessInstance> processInstances = new ConcurrentHashMap<>();

    private final Map<String, WfExecution> executions = new ConcurrentHashMap<>();

    private final Map<String, WfTask> tasks = new ConcurrentHashMap<>();

    private final Map<String, List<WfActivityInstance>> activities = new ConcurrentHashMap<>();

    private final Map<String, List<WfComment>> comments = new ConcurrentHashMap<>();

    private final Map<String, WfJob> jobs = new ConcurrentHashMap<>();

    private final Map<String, WfFilter> filters = new ConcurrentHashMap<>();

    /** 决策定义（DMN）。键是 {@code key@version}，与流程定义"多版本并存"同约定。 */
    private final Map<String, WfDmnDecision> decisions = new ConcurrentHashMap<>();

    /** 补偿登记（第 37 轮）。键是登记 id，值是 {@link WfCompensationEntry}。 */
    private final Map<String, WfCompensationEntry> compensations = new ConcurrentHashMap<>();

    /** 批次（第 39 轮）。键是批次 id，值是 {@link WfBatch}。 */
    private final Map<String, WfBatch> batches = new ConcurrentHashMap<>();

    /**
     * 批次明细（第 39 轮）。
     *
     * <p><b>按批次 id 分组而不是平铺</b>：一批 1000 个目标就是 1000 行，
     * 「取某批的全部明细」是这条数据唯一的正常读法，
     * 平铺在 Map 里就得每次全表扫一遍再筛。
     * 组内用 {@link java.util.List} 而不是按 id 索引的 Map：明细只插入不更新，
     * 写入顺序本身就是信息（= 目标命中顺序），List 天然保序且省一个索引层。
     */
    private final Map<String, List<WfBatchElement>> batchElements =
            new ConcurrentHashMap<String, List<WfBatchElement>>();

    /**
     * 历史故障记录（第 40 轮）。<b>键刻意是 jobId 而不是记录 id</b>：
     * 一个 job 永远只有一行（反复失败时累加），所以 jobId 就是这行的天然身份，
     * 按它取是 O(1)。JDBC 那边的主键仍是记录 id、另在 JOB_ID 上建唯一索引 ——
     * 对外语义一致，差的只是索引怎么摆。
     */
    private final Map<String, WfHistoricIncident> historicIncidents =
            new ConcurrentHashMap<String, WfHistoricIncident>();

    @Override
    public void initialize() {
        log.info("InMemoryWorkflowPersistence 初始化完成（无外部依赖）");
    }

    @Override
    public synchronized void saveDefinition(WfDefinition definition) {
        if (definition == null || definition.getKey() == null) {
            return;
        }
        Map<Integer, WfDefinition> versions = definitions.get(definition.getKey());
        if (versions == null) {
            versions = new LinkedHashMap<>();
            definitions.put(definition.getKey(), versions);
        }
        WfDefinition stored = copy(definition);
        // 存进去的这一份必定不是默认：默认只能由 setDefaultDefinition 置位。
        // 不清的话就成了第二个真源 —— 调用方拿一个带标记的定义来 deploy
        // 就把默认改了，而 JDBC 那边的 INSERT 是写死 0 的，两套实现行为还会不一样。
        // （开发期常用内存实现，上线才发现「重新部署一次就换了默认流程」）
        stored.setDefaultDefinition(false);
        versions.put(definition.getVersion(), stored);
    }

    @Override
    public synchronized WfDefinition findLatestDefinition(String key) {
        Map<Integer, WfDefinition> versions = definitions.get(key);
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        int latest = -1;
        for (Integer version : versions.keySet()) {
            if (version > latest) {
                latest = version;
            }
        }
        return copy(versions.get(latest));
    }

    @Override
    public synchronized WfDefinition findDefinition(String key, int version) {
        Map<Integer, WfDefinition> versions = definitions.get(key);
        if (versions == null) {
            return null;
        }
        return copy(versions.get(version));
    }

    @Override
    public synchronized List<WfDefinition> findDefinitionVersions(String key) {
        List<WfDefinition> result = new ArrayList<>();
        Map<Integer, WfDefinition> versions = definitions.get(key);
        if (versions == null) {
            return result;
        }
        for (Map.Entry<Integer, WfDefinition> entry : versions.entrySet()) {
            result.add(copy(entry.getValue()));
        }
        // 版本倒序
        result.sort(Comparator.comparingInt(WfDefinition::getVersion).reversed());
        return result;
    }

    @Override
    public synchronized List<WfDefinition> findAllDefinitions() {
        List<WfDefinition> result = new ArrayList<>();
        for (String key : definitions.keySet()) {
            WfDefinition latest = findLatestDefinition(key);
            if (latest != null) {
                result.add(latest);
            }
        }
        return result;
    }

    @Override
    public synchronized List<WfDefinition> findDefinitionsByCategory(String category) {
        List<WfDefinition> result = new ArrayList<>();
        if (category == null) {
            return result;
        }
        for (WfDefinition definition : findAllDefinitions()) {
            if (category.equals(definition.getCategory())) {
                result.add(definition);
            }
        }
        return result;
    }

    @Override
    public synchronized List<WfJob> lockExternalTasks(String topic, String workerId, int maxTasks,
                                                      java.util.Date staleBefore) {
        List<WfJob> candidates = new ArrayList<>();
        java.util.Date now = new java.util.Date();
        for (WfJob job : jobs.values()) {
            if (job.getType() != WfJobType.EXTERNAL || !topic.equals(job.getTopic())
                    || job.isRetriesExhausted()) {
                continue;
            }
            // 退避窗口，与 JDBC 侧的 (DUEDATE IS NULL OR DUEDATE <= ?) 同口径。
            // 漏了它就是"内存实现下退避不生效、开发测不出来、上线才发现重试瞬间烧光"
            if (job.getDuedate() != null && job.getDuedate().after(now)) {
                continue;
            }
            boolean free = job.getLockedBy() == null || job.getLockedBy().trim().isEmpty();
            boolean stale = staleBefore != null && job.getLockedAt() != null
                    && job.getLockedAt().before(staleBefore);
            if (free || stale) {
                candidates.add(job);
            }
        }
        // 先到先得，与 JDBC 侧 ORDER BY CREATE_TIME ASC, JOB_ID ASC 一致
        candidates.sort(new java.util.Comparator<WfJob>() {
            @Override
            public int compare(WfJob a, WfJob b) {
                Date ca = a.getCreateTime();
                Date cb = b.getCreateTime();
                if (ca == null || cb == null) {
                    return 0;
                }
                int cmp = ca.compareTo(cb);
                return cmp != 0 ? cmp
                        : String.valueOf(a.getId()).compareTo(String.valueOf(b.getId()));
            }
        });
        List<WfJob> locked = new ArrayList<WfJob>();
        for (WfJob raw : candidates) {
            if (locked.size() >= maxTasks) {
                break;
            }
            // 必须在拷贝上改，而不是直接改 jobs.values() 里的内部引用。
            // saveJob 的乐观锁契约是「调用方先 nextRevision()，使传入对象的
            // revision == 存储中对象的 revision + 1」—— 直接改内部引用的话，
            // 存与取是同一个对象，revision 永远是同一个值，检查必然失败，
            // 于是「领一次活」就把 job 永久锁死在乐观锁异常里。
            // JDBC 侧因为 findJob 返回独立对象而天然正确，两套实现只有这里是对齐的。
            WfJob job = copy(raw);
            job.setLockedBy(workerId);
            job.setLockedAt(now);
            job.nextRevision();
            saveJob(job);
            locked.add(job);
        }
        return locked;
    }

    @Override
    public synchronized boolean deleteDefinition(String key, int version) {
        Map<Integer, WfDefinition> versions = definitions.get(key);
        return versions != null && versions.remove(version) != null;
    }

    @Override
    public synchronized boolean setDefinitionSuspended(String key, int version, boolean suspended) {
        Map<Integer, WfDefinition> versions = definitions.get(key);
        if (versions == null) {
            return false;
        }
        WfDefinition definition = versions.get(version);
        if (definition == null) {
            return false;
        }
        // 改的是存储里那一份：查询一律返回副本，调用方拿到的对象改了不落库
        definition.setSuspended(suspended);
        return true;
    }

    @Override
    public synchronized boolean setDefaultDefinition(String key, int version, boolean isDefault) {
        Map<Integer, WfDefinition> versions = definitions.get(key);
        WfDefinition target = versions == null ? null : versions.get(version);
        if (target == null) {
            return false;
        }
        if (isDefault) {
            // 先清全表再置目标：留下两条默认时，"默认是哪个"就没有答案了
            for (Map<Integer, WfDefinition> each : definitions.values()) {
                for (WfDefinition definition : each.values()) {
                    definition.setDefaultDefinition(false);
                }
            }
        }
        target.setDefaultDefinition(isDefault);
        return true;
    }

    @Override
    public synchronized WfDefinition findDefaultDefinition() {
        WfDefinition found = null;
        java.util.List<String> hits = new ArrayList<>();
        for (Map<Integer, WfDefinition> versions : definitions.values()) {
            for (WfDefinition definition : versions.values()) {
                if (definition.isDefaultDefinition()) {
                    hits.add(definition.getKey() + ":" + definition.getVersion());
                    if (found == null) {
                        found = definition;
                    }
                }
            }
        }
        if (hits.size() > 1) {
            throw new WfPersistenceException(
                    "同时存在多条默认流程定义: " + hits + "。默认标记只能由 setDefaultDefinition 写入，"
                            + "出现多条说明数据被绕过接口直接改过");
        }
        return found == null ? null : copy(found);
    }

    @Override
    public synchronized List<WfDefinition> findDefinitions(String keyLike, String nameLike,
                                                           Boolean suspended) {
        List<WfDefinition> result = new ArrayList<>();
        String keyword = nameLike == null ? null : nameLike.trim();
        String keyWord = keyLike == null ? null : keyLike.trim();
        for (WfDefinition definition : findAllDefinitions()) {
            if (keyWord != null && !keyWord.isEmpty()) {
                String defKey = definition.getKey();
                if (defKey == null || !defKey.contains(keyWord)) {
                    continue;
                }
            }
            if (keyword != null && !keyword.isEmpty()) {
                String name = definition.getName();
                // 与 JDBC 的 LIKE '%x%' 一样是"包含"匹配；名字为 null 时不匹配
                if (name == null || !name.contains(keyword)) {
                    continue;
                }
            }
            if (suspended != null && definition.isSuspended() != suspended.booleanValue()) {
                continue;
            }
            result.add(definition);
        }
        return result;
    }

    @Override
    public void saveProcessInstance(WfProcessInstance instance) {
        if (instance == null || instance.getId() == null) {
            return;
        }
        WfProcessInstance existing = processInstances.get(instance.getId());
        if (existing != null) {
            // 乐观锁：revision 必须是 existing.revision + 1
            int expected = existing.getRevision() + 1;
            if (instance.getRevision() != expected) {
                throw new WfOptimisticLockException("processInstance", instance.getId(), expected);
            }
        }
        WfProcessInstance toStore = copy(instance);
        if (existing != null && !java.util.Objects.equals(toStore.getName(), existing.getName())) {
            // 名字由 setProcessInstanceName 独占，两套实现都不得经 save 改它。
            //
            // 本实现是**整对象覆盖**（JDBC 那侧是部分更新），而调用方手上的实例对象
            // 往往是改名前取的 —— 直接存进去会把名字抹成 null。
            // 后果在内存模式下比真库更刺眼：只要有人改完名再点一次"通过"，
            // 名字立刻消失，而 JDBC 上不会 —— 于是"内存里好好的、一上真库就丢"，
            // 排查的人会先怀疑数据库。
            //
            // 代价是**「用 saveProcessInstance 改名字」这条路两边都不通**了 ——
            // 那正是"字段只有一个所有者"要的效果，不是顺带的限制。
            toStore.setName(existing.getName());
        }
        processInstances.put(instance.getId(), toStore);
    }

    @Override
    public WfProcessInstance findProcessInstance(String id) {
        return copy(processInstances.get(id));
    }

    @Override
    public int setProcessInstanceName(String processInstanceId, String name) {
        WfProcessInstance existing = processInstances.get(processInstanceId);
        if (existing == null) {
            return 0;
        }
        // 复制一份再改再放回，与 saveProcessInstance 同一套做法：
        // 直接改 map 里那个对象会让别处持有的引用（findProcessInstance 之前的调用方
        // 若拿的是同一实例）看到一次"没经过任何写入路径"的突变。
        WfProcessInstance named = copy(existing);
        named.setName(name);
        processInstances.put(processInstanceId, named);
        return 1;
    }

    @Override
    public List<WfProcessInstance> findProcessInstancesByBusinessKey(String businessKey) {
        List<WfProcessInstance> result = new ArrayList<>();
        if (businessKey == null) {
            return result;
        }
        for (WfProcessInstance instance : processInstances.values()) {
            if (businessKey.equals(instance.getBusinessKey())) {
                result.add(copy(instance));
            }
        }
        sortInstances(result);
        return result;
    }

    @Override
    public List<WfProcessInstance> queryProcessInstances(WfProcessInstanceQuery query) {
        if (query != null) {
            query.assertConsistent();
        }
        List<WfProcessInstance> matched = new ArrayList<>();
        for (WfProcessInstance instance : processInstances.values()) {
            if (matches(instance, query)) {
                matched.add(copy(instance));
            }
        }
        sortInstances(matched);
        return paginate(matched, query.getOffset(), query.getPageSize());
    }

    @Override
    public long countProcessInstances(WfProcessInstanceQuery query) {
        long count = 0;
        for (WfProcessInstance instance : processInstances.values()) {
            if (matches(instance, query)) {
                count++;
            }
        }
        return count;
    }

    private boolean matches(WfProcessInstance instance, WfProcessInstanceQuery query) {
        if (query == null) {
            return true;
        }
        if (isNotBlank(query.getDefinitionKey()) && !query.getDefinitionKey().equals(instance.getDefinitionKey())) {
            return false;
        }
        // 版本必须与 JDBC 侧 appendProcessFilters 同步加，否则"老版本还有没有在跑的单"
        // 在内存里查、在库里查答案不一样，而删定义前正是靠这个判断挡不挡
        if (query.getDefinitionVersion() != null
                && query.getDefinitionVersion().intValue() != instance.getDefinitionVersion()) {
            return false;
        }
        if (isNotBlank(query.getBusinessKey()) && !query.getBusinessKey().equals(instance.getBusinessKey())) {
            return false;
        }
        if (isNotBlank(query.getStartUserId()) && !query.getStartUserId().equals(instance.getStartUserId())) {
            return false;
        }
        if (isNotBlank(query.getCategory()) && !query.getCategory().equals(instance.getCategory())) {
            return false;
        }
        if (query.getStatus() != null && query.getStatus() != instance.getStatus()) {
            return false;
        }
        if (query.isFinishedOnly() && !instance.getStatus().isTerminal()) {
            return false;
        }
        if (query.isUnfinishedOnly() && !instance.getStatus().isActive()) {
            return false;
        }
        if (isNotBlank(query.getResult()) && !query.getResult().equals(instance.getResult())) {
            return false;
        }
        Date start = instance.getStartTime();
        if (query.getStartTimeFrom() != null && (start == null || start.before(query.getStartTimeFrom()))) {
            return false;
        }
        if (query.getStartTimeTo() != null && (start == null || start.after(query.getStartTimeTo()))) {
            return false;
        }
        return true;
    }

    private void sortInstances(List<WfProcessInstance> list) {
        list.sort((a, b) -> {
            Date ta = a.getStartTime();
            Date tb = b.getStartTime();
            if (ta == null && tb == null) {
                return 0;
            }
            if (ta == null) {
                return 1;
            }
            if (tb == null) {
                return -1;
            }
            return tb.compareTo(ta); // 倒序：最新的在前
        });
    }

    @Override
    public void saveExecution(WfExecution execution) {
        if (execution == null || execution.getId() == null) {
            return;
        }
        executions.put(execution.getId(), copy(execution));
    }

    @Override
    public void deleteExecution(String id) {
        executions.remove(id);
    }

    @Override
    public WfExecution findExecution(String id) {
        return copy(executions.get(id));
    }

    @Override
    public List<WfExecution> findExecutionsByProcessInstance(String processInstanceId) {
        List<WfExecution> result = new ArrayList<>();
        for (WfExecution execution : executions.values()) {
            if (processInstanceId.equals(execution.getProcessInstanceId())) {
                result.add(copy(execution));
            }
        }
        return result;
    }

    @Override
    public List<WfExecution> queryExecutions(WfExecutionQuery query) {
        // 先筛**引用**、排序、分页，最后才对当页的条目做深拷贝：
        // copy 走的是 Java 序列化，逐条做的话一页 50 条要拷全量。
        // 既有 find* 系列是"边匹配边 copy"，在有分页的场景下那一份是白拷的。
        List<WfExecution> matched = new ArrayList<>();
        for (WfExecution execution : executions.values()) {
            if (matchesExecution(execution, query)) {
                matched.add(execution);
            }
        }
        // 与 JdbcWorkflowPersistence#queryExecutions 同口径：这里只保证页内稳定，
        // 业务排序（按进入时间）在 WfExecutionQueryService 里统一做。
        java.util.Collections.sort(matched, new java.util.Comparator<WfExecution>() {
            @Override
            public int compare(WfExecution left, WfExecution right) {
                String leftId = left.getId() == null ? "" : left.getId();
                String rightId = right.getId() == null ? "" : right.getId();
                return rightId.compareTo(leftId);
            }
        });
        int offset = query == null ? 0 : Math.max(0, query.getOffset());
        int size = query == null || query.getPageSize() <= 0 ? 50 : query.getPageSize();
        List<WfExecution> page = paginate(matched, offset, size);
        List<WfExecution> result = new ArrayList<>();
        for (WfExecution execution : page) {
            result.add(copy(execution));
        }
        return result;
    }

    /** 与 {@code JdbcWorkflowPersistence#appendExecutionFilters} 同口径。 */
    private boolean matchesExecution(WfExecution execution, WfExecutionQuery query) {
        if (query == null) {
            return true;
        }
        if (isNotBlank(query.getProcessInstanceId())
                && !query.getProcessInstanceId().equals(execution.getProcessInstanceId())) {
            return false;
        }
        if (isNotBlank(query.getActivityId())
                && !query.getActivityId().equals(execution.getActivityId())) {
            return false;
        }
        if (query.getStates() != null && !query.getStates().isEmpty()
                && (execution.getState() == null || !query.getStates().contains(execution.getState()))) {
            return false;
        }
        return true;
    }

    @Override
    public void saveTask(WfTask task) {
        if (task == null || task.getId() == null) {
            return;
        }
        WfTask existing = tasks.get(task.getId());
        if (existing != null && task.getRevision() != existing.getRevision() + 1) {
            throw new WfOptimisticLockException("task", task.getId(), existing.getRevision() + 1);
        }
        tasks.put(task.getId(), copy(task));
    }

    @Override
    public void deleteTask(String id) {
        tasks.remove(id);
    }

    @Override
    public WfTask findTask(String id) {
        return copy(tasks.get(id));
    }

    @Override
    public List<WfTask> queryTasks(WfTaskQuery query) {
        if (query != null) {
            query.assertConsistent();
        }
        List<WfTask> matched = new ArrayList<>();
        for (WfTask task : tasks.values()) {
            if (matches(task, query)) {
                matched.add(copy(task));
            }
        }
        // 排序：未完成优先 → 优先级高优先 → 创建时间新优先
        matched.sort((a, b) -> {
            if (a.isOpen() != b.isOpen()) {
                return a.isOpen() ? -1 : 1;
            }
            if (a.getPriority() != b.getPriority()) {
                return b.getPriority() - a.getPriority();
            }
            Date ta = a.getCreateTime();
            Date tb = b.getCreateTime();
            if (ta == null || tb == null) {
                return 0;
            }
            return tb.compareTo(ta);
        });
        int offset = query == null ? 0 : query.getOffset();
        int size = query == null || query.getPageSize() <= 0 ? 20 : query.getPageSize();
        return paginate(matched, offset, size);
    }

    // ==================== 变量变更审计 ====================

    @Override
    public List<WfComment> queryVariableAudits(WfVariableAuditQuery query) {
        List<WfComment> matched = new ArrayList<>();
        for (Map.Entry<String, List<WfComment>> entry : comments.entrySet()) {
            for (WfComment comment : entry.getValue()) {
                if (matchesAudit(comment, query)) {
                    matched.add(copy(comment));
                }
            }
        }
        // 时间倒序：审计是"越新越先看"，与 findComments 的正序（轨迹是"从前往后"）相反。
        // 同毫秒用 id 倒序兜底：WfIdGenerator 的序号已补零到定长，字典序 == 插入序，
        // 所以"id 大"就是"写入晚"。兜底必须与 JdbcWorkflowPersistence 的
        // ORDER BY CMT_TIME DESC, CMT_ID DESC 完全一致，否则同一批数据两套实现给出不同顺序。
        matched.sort((a, b) -> {
            Date ta = a.getTime();
            Date tb = b.getTime();
            if (ta == null || tb == null) {
                return 0;
            }
            int cmp = tb.compareTo(ta);
            return cmp != 0 ? cmp : safeId(b).compareTo(safeId(a));
        });
        int offset = query == null ? 0 : query.getOffset();
        int size = query == null || query.getPageSize() <= 0 ? 50 : query.getPageSize();
        return paginate(matched, offset, size);
    }

    @Override
    public long countVariableAudits(WfVariableAuditQuery query) {
        int total = 0;
        for (Map.Entry<String, List<WfComment>> entry : comments.entrySet()) {
            for (WfComment comment : entry.getValue()) {
                if (matchesAudit(comment, query)) {
                    total++;
                }
            }
        }
        return total;
    }

    /**
     * 变量审计的过滤。与 {@link #queryVariableAudits} / {@link #countVariableAudits} 共用。
     *
     * <p>变量名按<b>前缀</b>匹配：审计内容形如 {@code "amount: 1000 -> 500"}，
     * 前缀 {@code "amount: "} 才精确命中这一条。若用子串匹配，
     * 查 {@code amount} 会把 {@code discount_amount} 的记录也带出来 ——
     * 审计给出错的行比不给行更糟。
     */
    private boolean matchesAudit(WfComment comment, WfVariableAuditQuery query) {
        if (comment == null || !WfVariableService.COMMENT_TYPE_VARIABLE.equals(comment.getType())) {
            return false;
        }
        if (query == null) {
            return true;
        }
        if (isNotBlank(query.getProcessInstanceId())
                && !query.getProcessInstanceId().equals(comment.getProcessInstanceId())) {
            return false;
        }
        if (isNotBlank(query.getVariableName())) {
            String prefix = query.getVariableName().trim() + ":";
            if (comment.getContent() == null || !comment.getContent().startsWith(prefix)) {
                return false;
            }
        }
        if (isNotBlank(query.getChangedBy())
                && !query.getChangedBy().equals(comment.getUserId())) {
            return false;
        }
        if (query.getChangedFrom() != null
                && (comment.getTime() == null || comment.getTime().before(query.getChangedFrom()))) {
            return false;
        }
        if (query.getChangedTo() != null
                && (comment.getTime() == null || !comment.getTime().before(query.getChangedTo()))) {
            return false;
        }
        return true;
    }

    private static String safeId(WfComment comment) {
        return comment.getId() == null ? "" : comment.getId();
    }

    @Override
    public long countTasks(WfTaskQuery query) {
        if (query != null) {
            query.assertConsistent();
        }
        // 过滤逻辑复用 matches —— 与 queryTasks 同一个判定，
        // 免得 count 与列表在某个条件上分道扬镳，页面上的"共 N 条"就成了假话
        int total = 0;
        for (WfTask task : tasks.values()) {
            if (matches(task, query)) {
                total++;
            }
        }
        return total;
    }

    private boolean matches(WfTask task, WfTaskQuery query) {
        if (query == null) {
            return true;
        }
        // 与 JdbcWorkflowPersistence 的 appendTaskFilters 同口径：
        // 少一个条件就是"内存查得到、库里查不到"，count 与列表也会跟着对不上
        if (query.getSuspendedOnly() != null
                && task.isSuspended() != query.getSuspendedOnly().booleanValue()) {
            return false;
        }
        if (isNotBlank(query.getProcessInstanceId())
                && !query.getProcessInstanceId().equals(task.getProcessInstanceId())) {
            return false;
        }
        if (isNotBlank(query.getDefinitionId()) && !query.getDefinitionId().equals(task.getDefinitionId())) {
            return false;
        }
        // assignee 与 owner 是**或**的关系，不是且：
        // 委派态下活同时记在 owner（责任人）身上，owner 认领后 assignee 才变成他。
        // 若按"且"过滤，被委派的人在自己的待办里一条都看不到 ——
        // 而开发期默认用内存实现，这个 bug 会一路活到上线。
        // JDBC 侧拼的是 (ASSIGNEE=? OR OWNER=?)，两边必须一致。
        // candidateOrAssigned 模式下这段整段跳过：assignee/owner 被折进下面的或组，
        // 在这里先按"且"判一次的话，候选人（assignee 与 owner 都为空）的任务会被直接淘汰，
        // 待办列表里一条都不剩 —— 而两边都看不到症状。
        if (!query.isCandidateOrAssigned()
                && (isNotBlank(query.getAssignee()) || isNotBlank(query.getOwner()))) {
            boolean hitAssignee = isNotBlank(query.getAssignee())
                    && query.getAssignee().equals(task.getAssignee());
            boolean hitOwner = isNotBlank(query.getOwner())
                    && query.getOwner().equals(task.getOwner());
            if (!hitAssignee && !hitOwner) {
                return false;
            }
        }
        if (isNotBlank(query.getCompleterId()) && !query.getCompleterId().equals(task.getCompleterId())) {
            return false;
        }
        if (isNotBlank(query.getCategory()) && !query.getCategory().equals(task.getCategory())) {
            return false;
        }
        if (query.getStatus() != null && query.getStatus() != task.getStatus()) {
            return false;
        }
        if (query.isOpenOnly() && !task.isOpen()) {
            return false;
        }
        if (query.isCompletedOnly() && task.getStatus() != WfTask.Status.COMPLETED) {
            return false;
        }
        if (query.isUnassignedOnly() && isNotBlank(task.getAssignee())) {
            return false;
        }
        // 待办语义：assignee / owner / 候选用户 / 候选组取或。
        // 与 JdbcWorkflowPersistence 的 candidateOrAssigned 分支逐条对应 ——
        // 两边必须一致，否则会出现"内存里看得到待办、库里查不到"。
        if (query.isCandidateOrAssigned()) {
            boolean related = false;
            if (isNotBlank(query.getAssignee())
                    && query.getAssignee().equals(task.getAssignee())) {
                related = true;
            }
            if (!related && isNotBlank(query.getOwner())
                    && query.getOwner().equals(task.getOwner())) {
                related = true;
            }
            if (!related && query.getCandidateUsers() != null) {
                for (String user : query.getCandidateUsers()) {
                    if (task.getCandidateUsers().contains(user)) {
                        related = true;
                        break;
                    }
                }
            }
            if (!related && query.getCandidateGroups() != null) {
                for (String group : query.getCandidateGroups()) {
                    if (task.getCandidateGroups().contains(group)) {
                        related = true;
                        break;
                    }
                }
            }
            if (!related) {
                return false;
            }
        } else {
            if (query.getCandidateUsers() != null && !query.getCandidateUsers().isEmpty()
                    && !task.getCandidateUsers().containsAll(query.getCandidateUsers())) {
                // 任一候选人命中即可
                boolean hit = false;
                for (String user : query.getCandidateUsers()) {
                    if (task.getCandidateUsers().contains(user)) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    return false;
                }
            }
            if (query.getCandidateGroups() != null && !query.getCandidateGroups().isEmpty()) {
                boolean hit = false;
                for (String group : query.getCandidateGroups()) {
                    if (task.getCandidateGroups().contains(group)) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    return false;
                }
            }
        }
        Date create = task.getCreateTime();
        if (query.getCreateTimeFrom() != null && (create == null || create.before(query.getCreateTimeFrom()))) {
            return false;
        }
        if (query.getCreateTimeTo() != null && (create == null || create.after(query.getCreateTimeTo()))) {
            return false;
        }
        return true;
    }

    @Override
    public void saveActivityInstance(WfActivityInstance instance) {
        if (instance == null || instance.getProcessInstanceId() == null) {
            return;
        }
        List<WfActivityInstance> list = activities.get(instance.getProcessInstanceId());
        if (list == null) {
            list = new ArrayList<>();
            activities.put(instance.getProcessInstanceId(), list);
        }
        synchronized (list) {
            list.add(copy(instance));
        }
    }

    @Override
    public List<WfActivityInstance> findActivityInstances(String processInstanceId) {
        List<WfActivityInstance> list = activities.get(processInstanceId);
        if (list == null) {
            return new ArrayList<>();
        }
        List<WfActivityInstance> result = new ArrayList<>();
        synchronized (list) {
            for (WfActivityInstance instance : list) {
                result.add(copy(instance));
            }
        }
        // 按开始时间正序 = 审批轨迹的自然顺序
        result.sort((a, b) -> {
            Date ta = a.getStartTime();
            Date tb = b.getStartTime();
            if (ta == null || tb == null) {
                return 0;
            }
            return ta.compareTo(tb);
        });
        return result;
    }

    @Override
    public List<WfActivityInstance> queryActivityInstances(
            WfHistoricActivityInstanceQuery query) {
        List<WfActivityInstance> matched = new ArrayList<>();
        for (List<WfActivityInstance> list : activities.values()) {
            synchronized (list) {
                for (WfActivityInstance item : list) {
                    if (matches(item, query)) {
                        matched.add(copy(item));
                    }
                }
            }
        }
        sortHistoric(matched, query);
        return paginate(matched, query == null ? 0 : query.getOffset(),
                query == null || query.getPageSize() <= 0 ? 20 : query.getPageSize());
    }

    @Override
    public long countActivityInstances(WfHistoricActivityInstanceQuery query) {
        long count = 0;
        for (List<WfActivityInstance> list : activities.values()) {
            synchronized (list) {
                for (WfActivityInstance item : list) {
                    if (matches(item, query)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * 逻辑实体名单，**与 {@code JdbcWorkflowPersistence} 的表名一一对应**。
     *
     * <p>顺序固定（不是按 Map 的迭代顺序）：自省接口的输出要能直接拿去 diff，
     * 顺序飘一次就让人以为"表变了"。
     */
    public static final List<String> STORAGE_NAMES = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(
                    "ZWF_DEFINITION", "ZWF_PROCESS", "ZWF_EXECUTION", "ZWF_TASK",
                    "ZWF_JOB", "ZWF_ACTIVITY", "ZWF_COMMENT", "ZWF_FILTER",
                    "ZWF_DECISION", "ZWF_COMPENSATION", "ZWF_BATCH", "ZWF_BATCH_ELEMENT",
                    "ZWF_INCIDENT_HISTORY"));

    @Override
    public List<String> getTableNames() {
        return STORAGE_NAMES;
    }

    /**
     * 条数。名字不认识就报错 —— 返回 0 会把"拼错了名字"说成"这里是空的"。
     */
    @Override
    public long getTableCount(String name) {
        if (name == null || !STORAGE_NAMES.contains(name)) {
            throw new com.zifang.z.wf.core.service.WfEngineException(
                    "内存存储里没有这个实体: " + name + "。共有: " + STORAGE_NAMES);
        }
        if ("ZWF_DEFINITION".equals(name)) {
            long total = 0;
            for (Map<Integer, WfDefinition> versions : definitions.values()) {
                total += versions.size();
            }
            return total;
        }
        if ("ZWF_PROCESS".equals(name)) {
            return processInstances.size();
        }
        if ("ZWF_EXECUTION".equals(name)) {
            return executions.size();
        }
        if ("ZWF_TASK".equals(name)) {
            return tasks.size();
        }
        if ("ZWF_JOB".equals(name)) {
            return jobs.size();
        }
        if ("ZWF_ACTIVITY".equals(name)) {
            long total = 0;
            for (List<WfActivityInstance> list : activities.values()) {
                total += list.size();
            }
            return total;
        }
        if ("ZWF_COMMENT".equals(name)) {
            long total = 0;
            for (List<WfComment> list : comments.values()) {
                total += list.size();
            }
            return total;
        }
        if ("ZWF_FILTER".equals(name)) {
            return filters.size();
        }
        if ("ZWF_DECISION".equals(name)) {
            return decisions.size();
        }
        if ("ZWF_COMPENSATION".equals(name)) {
            return compensations.size();
        }
        if ("ZWF_BATCH".equals(name)) {
            return batches.size();
        }
        if ("ZWF_BATCH_ELEMENT".equals(name)) {
            long total = 0;
            for (List<WfBatchElement> list : batchElements.values()) {
                total += list.size();
            }
            return total;
        }
        if ("ZWF_INCIDENT_HISTORY".equals(name)) {
            return historicIncidents.size();
        }
        // 刻意**不兜底返回某一张表的行数**（第 37 轮）。
        // 原来这里最后一句是 `return decisions.size()`：名字在 STORAGE_NAMES 里、
        // 但上面没列到，就默默返回了决策数 —— 而 STORAGE_NAMES 同时也是
        // 「JDBC 会建哪些表」的唯一名单，所以往那名单里加一项而忘了在这里加分支，
        // 症状是「自省面板上某张表的行数等于决策数」，**没有任何报错**。
        // 宁可抛：这一行的存在本身就是为了让那种漏改立刻暴露。
        throw new com.zifang.z.wf.core.service.WfEngineException(
                "实体 " + name + " 在名单里但没写计数分支。补一个分支，"
                        + "不要靠兜底返回别人的行数 —— 那会让自省面板上的数字静默错掉");
    }

    @Override
    public int deleteHistoryBefore(Date before) {
        int removed = 0;
        List<String> doomed = new ArrayList<>();
        for (Map.Entry<String, WfProcessInstance> entry : processInstances.entrySet()) {
            WfProcessInstance instance = entry.getValue();
            if (instance.getStatus() == null || !instance.getStatus().isTerminal()) {
                continue;   // 在途流程的历史不能删
            }
            Date end = instance.getEndTime();
            if (end != null && end.before(before)) {
                doomed.add(entry.getKey());
            }
        }
        for (String id : doomed) {
            activities.remove(id);
            comments.remove(id);
            // 只删已结束流程相关的任务；任务表是流程实例的子表，一并清掉
            tasks.values().removeIf(task -> id.equals(task.getProcessInstanceId()));
            executions.values().removeIf(token -> id.equals(token.getProcessInstanceId()));
            processInstances.remove(id);
            removed++;
        }
        if (removed > 0) {
            log.info("清理 {} 之前的历史: 删除 {} 个流程实例", before, removed);
        }
        return removed;
    }

    private boolean matches(WfActivityInstance item, WfHistoricActivityInstanceQuery q) {
        if (q == null) {
            return true;
        }
        if (isNotBlank(q.getProcessInstanceId())
                && !q.getProcessInstanceId().equals(item.getProcessInstanceId())) {
            return false;
        }
        if (isNotBlank(q.getProcessDefinitionKey())
                && !q.getProcessDefinitionKey().equals(item.getProcessDefinitionKey())) {
            return false;
        }
        if (isNotBlank(q.getActivityId()) && !q.getActivityId().equals(item.getActivityId())) {
            return false;
        }
        if (isNotBlank(q.getActivityType())
                && !q.getActivityType().equals(item.getActivityType())) {
            return false;
        }
        if (isNotBlank(q.getAssignee()) && !q.getAssignee().equals(item.getAssignee())) {
            return false;
        }
        if (q.getStartedAfter() != null
                && (item.getStartTime() == null
                || !item.getStartTime().after(q.getStartedAfter()))) {
            return false;
        }
        if (q.getStartedBefore() != null
                && (item.getStartTime() == null
                || !item.getStartTime().before(q.getStartedBefore()))) {
            return false;
        }
        return q.getMinDurationMillis() == null
                || item.getDurationMillis() >= q.getMinDurationMillis();
    }

    private void sortHistoric(List<WfActivityInstance> list,
                              WfHistoricActivityInstanceQuery query) {
        if (query != null && "duration".equals(query.getOrderBy())) {
            list.sort((a, b) -> Long.compare(b.getDurationMillis(), a.getDurationMillis()));
            return;
        }
        list.sort((a, b) -> {
            if (a.getStartTime() == null || b.getStartTime() == null) {
                return 0;
            }
            return a.getStartTime().compareTo(b.getStartTime());
        });
    }

    public void saveComment(WfComment comment) {
        if (comment == null || comment.getProcessInstanceId() == null) {
            return;
        }
        List<WfComment> list = comments.get(comment.getProcessInstanceId());
        if (list == null) {
            list = new ArrayList<>();
            comments.put(comment.getProcessInstanceId(), list);
        }
        synchronized (list) {
            list.add(copy(comment));
        }
    }

    @Override
    public List<WfComment> findComments(String processInstanceId) {
        List<WfComment> list = comments.get(processInstanceId);
        if (list == null) {
            return new ArrayList<>();
        }
        List<WfComment> result = new ArrayList<>();
        synchronized (list) {
            for (WfComment comment : list) {
                result.add(copy(comment));
            }
        }
        result.sort((a, b) -> {
            Date ta = a.getTime();
            Date tb = b.getTime();
            if (ta == null || tb == null) {
                return 0;
            }
            return ta.compareTo(tb);
        });
        return result;
    }

    // ==================== Job ====================

    @Override
    public void saveJob(WfJob job) {
        if (job == null || job.getId() == null) {
            return;
        }
        WfJob existing = jobs.get(job.getId());
        if (existing != null && job.getRevision() != existing.getRevision() + 1) {
            throw new WfOptimisticLockException("job", job.getId(), existing.getRevision() + 1);
        }
        jobs.put(job.getId(), copy(job));
    }

    @Override
    public void deleteJob(String id) {
        jobs.remove(id);
    }

    @Override
    public WfJob findJob(String id) {
        return copy(jobs.get(id));
    }

    @Override
    public List<WfJob> queryJobs(WfJobQuery query) {
        List<WfJob> matched = new ArrayList<>();
        for (WfJob job : jobs.values()) {
            if (matches(job, query)) {
                matched.add(copy(job));
            }
        }
        // 到期时刻正序：执行器要的是"最早到点的先做"，顺序错了会饿死靠后的 job。
        // 到期时刻相同时按 id 排，否则 ConcurrentHashMap 的遍历顺序会随机化，
        // 同一批 job 在不同 JVM 上得到不同的执行顺序。
        //
        // 优先级排序是**开关控制的叠加**：开了它之后同级仍按到期时刻正序，
        // 再相同才按 id —— 两套实现必须给出同一个顺序，
        // 而"同级随机"会让同一批 job 在内存与 JDBC 上跑出不同结果，
        // 那是最难查的一类不一致（开发期内存全绿，上线 JDBC 偶发乱序）。
        final boolean byPriority = query != null && query.isOrderedByPriority();
        matched.sort((a, b) -> {
            if (byPriority) {
                int p = Integer.compare(b.getPriority(), a.getPriority());
                if (p != 0) {
                    return p;
                }
            }
            Date ta = a.getDuedate();
            Date tb = b.getDuedate();
            if (ta == null || tb == null) {
                return a.getId().compareTo(b.getId());
            }
            int cmp = ta.compareTo(tb);
            return cmp != 0 ? cmp : a.getId().compareTo(b.getId());
        });
        int offset = query == null ? 0 : query.getOffset();
        int size = query == null || query.getPageSize() <= 0 ? 50 : query.getPageSize();
        return paginate(matched, offset, size);
    }

    @Override
    public long countJobs(WfJobQuery query) {
        int total = 0;
        for (WfJob job : jobs.values()) {
            if (matches(job, query)) {
                total++;
            }
        }
        return total;
    }

    @Override
    public int deleteJobsByProcessInstance(String processInstanceId) {
        int removed = 0;
        for (WfJob job : jobs.values()) {
            if (processInstanceId.equals(job.getProcessInstanceId())) {
                jobs.remove(job.getId());
                removed++;
            }
        }
        return removed;
    }

    // ==================== 补偿登记（第 37 轮） ====================

    @Override
    public void saveCompensation(WfCompensationEntry entry) {
        if (entry == null || entry.getId() == null) {
            throw new WfPersistenceException("补偿登记必须有 id");
        }
        compensations.put(entry.getId(), copy(entry));
    }

    @Override
    public List<WfCompensationEntry> findCompensations(String processInstanceId) {
        List<WfCompensationEntry> found = new ArrayList<>();
        for (WfCompensationEntry entry : compensations.values()) {
            if (processInstanceId.equals(entry.getProcessInstanceId())) {
                found.add(copy(entry));
            }
        }
        // 按 seq 正序：调用方要做的是逆序补偿，正序交出去更不容易用错。
        Collections.sort(found, new Comparator<WfCompensationEntry>() {
            @Override
            public int compare(WfCompensationEntry left, WfCompensationEntry right) {
                return Long.compare(left.getSeq(), right.getSeq());
            }
        });
        return found;
    }

    @Override
    public int markCompensated(String id, Date when) {
        WfCompensationEntry entry = compensations.get(id);
        if (entry == null) {
            return 0;
        }
        entry.setDone(true);
        entry.setCompensatedAt(when);
        compensations.put(id, entry);
        return 1;
    }

    @Override
    public int deleteCompensationsByProcessInstance(String processInstanceId) {
        int removed = 0;
        for (WfCompensationEntry entry : compensations.values()) {
            if (processInstanceId.equals(entry.getProcessInstanceId())) {
                compensations.remove(entry.getId());
                removed++;
            }
        }
        return removed;
    }

    /** 深拷贝：调用方拿到的是快照，改它不会污染存储里的那一份。 */
    private static WfCompensationEntry copy(WfCompensationEntry source) {
        if (source == null) {
            return null;
        }
        WfCompensationEntry target = new WfCompensationEntry();
        target.setId(source.getId());
        target.setProcessInstanceId(source.getProcessInstanceId());
        target.setActivityId(source.getActivityId());
        target.setScope(source.getScope());
        target.setSeq(source.getSeq());
        target.setDone(source.isDone());
        target.setRegisteredAt(source.getRegisteredAt());
        target.setCompensatedAt(source.getCompensatedAt());
        return target;
    }

    // ==================== 批次（第 39 轮） ====================

    @Override
    public void saveBatch(WfBatch batch) {
        if (batch == null || batch.getId() == null) {
            return;
        }
        WfBatch existing = batches.get(batch.getId());
        // 与 saveTask / saveJob / saveFilter 同一套乐观锁契约，见 WfPersistence#saveBatch
        if (existing != null && batch.getRevision() != existing.getRevision() + 1) {
            throw new WfOptimisticLockException("batch", batch.getId(), existing.getRevision() + 1);
        }
        batches.put(batch.getId(), copy(batch));
    }

    @Override
    public WfBatch findBatch(String id) {
        return copy(batches.get(id));
    }

    @Override
    public boolean deleteBatch(String id) {
        if (id == null) {
            return false;
        }
        // 先删明细再删本体：反过来万一中途抛异常，会留下"本体没了、明细还在"的孤儿行，
        // 而那种行按 batchId 查得出来、查不出批次，排查时最难认
        deleteBatchElements(id);
        return batches.remove(id) != null;
    }

    @Override
    public List<WfBatch> queryBatches(WfBatchQuery query) {
        List<WfBatch> matched = new ArrayList<>();
        for (WfBatch raw : batches.values()) {
            if (matchesBatch(raw, query)) {
                matched.add(copy(raw));
            }
        }
        // 与 queryFilters 同一把尺子：创建时间新的在前，同一毫秒按 id 排。
        // 次序不确定的话，分页会漏条目、两次列出来 diff 也没法比
        matched.sort((a, b) -> {
            Date ta = a.getCreateTime();
            Date tb = b.getCreateTime();
            if (ta != null && tb != null && !ta.equals(tb)) {
                return tb.compareTo(ta);
            }
            return a.getId().compareTo(b.getId());
        });
        if (query == null) {
            return matched;
        }
        return paginate(matched, (query.getPageNum() - 1) * query.getPageSize(),
                query.getPageSize());
    }

    @Override
    public int countBatches(WfBatchQuery query) {
        int count = 0;
        for (WfBatch raw : batches.values()) {
            if (matchesBatch(raw, query)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 批次过滤。与 {@link #queryBatches} / {@link #countBatches} 共用同一个判定 ——
     * 列表说 3 条而 total 说 5 条的话，前端分页器会以为还有第 2 页，
     * 翻过去是空的，且没人知道该信哪个。
     */
    private boolean matchesBatch(WfBatch batch, WfBatchQuery query) {
        if (query == null) {
            return true;
        }
        if (query.getBatchType() != null && query.getBatchType() != batch.getBatchType()) {
            return false;
        }
        if (query.getState() != null && query.getState() != batch.getState()) {
            return false;
        }
        if (query.getSuspendedOnly() != null
                && query.getSuspendedOnly() != batch.isSuspended()) {
            return false;
        }
        if (isNotBlank(query.getOperatorId()) && !query.getOperatorId().equals(batch.getOperatorId())) {
            return false;
        }
        if (query.getCreateTimeFrom() != null
                && (batch.getCreateTime() == null || batch.getCreateTime().before(query.getCreateTimeFrom()))) {
            return false;
        }
        return query.getCreateTimeTo() == null || batch.getCreateTime() == null
                || !batch.getCreateTime().after(query.getCreateTimeTo());
    }

    @Override
    public void saveBatchElement(WfBatchElement element) {
        if (element == null || element.getBatchId() == null || element.getId() == null) {
            throw new WfPersistenceException("批次明细必须有 id 和 batchId");
        }
        List<WfBatchElement> list = batchElements.get(element.getBatchId());
        if (list == null) {
            // computeIfAbsent 里的动作必须是纯的：这里的 putIfAbsent 冲突时什么都不做，
            // 不会把别人的 List 换掉
            list = new java.util.concurrent.CopyOnWriteArrayList<WfBatchElement>();
            List<WfBatchElement> prev = batchElements.putIfAbsent(element.getBatchId(), list);
            if (prev != null) {
                list = prev;
            }
        }
        list.add(copy(element));
    }

    @Override
    public List<WfBatchElement> findBatchElements(String batchId) {
        List<WfBatchElement> list = batchElements.get(batchId);
        if (list == null) {
            return new ArrayList<WfBatchElement>();
        }
        List<WfBatchElement> result = new ArrayList<>();
        for (WfBatchElement item : list) {
            result.add(copy(item));
        }
        return result;
    }

    @Override
    public List<WfBatchElement> findFailedBatchElements(String batchId) {
        List<WfBatchElement> result = new ArrayList<>();
        for (WfBatchElement item : findBatchElements(batchId)) {
            if (!item.isSuccess()) {
                result.add(item);
            }
        }
        return result;
    }

    @Override
    public int countBatchElements(String batchId) {
        List<WfBatchElement> list = batchElements.get(batchId);
        return list == null ? 0 : list.size();
    }

    @Override
    public int deleteBatchElements(String batchId) {
        List<WfBatchElement> removed = batchElements.remove(batchId);
        return removed == null ? 0 : removed.size();
    }

    /** 深拷贝：调用方拿到的是快照，改它不会污染存储里的那一份。 */
    private static WfBatch copy(WfBatch source) {
        if (source == null) {
            return null;
        }
        WfBatch target = new WfBatch();
        target.setId(source.getId());
        target.setBatchType(source.getBatchType());
        target.setCriteria(source.getCriteria());
        target.setOperations(source.getOperations());
        target.setState(source.getState());
        target.setAffectedCount(source.getAffectedCount());
        target.setFailureCount(source.getFailureCount());
        target.setCreateTime(source.getCreateTime());
        target.setStartTime(source.getStartTime());
        target.setEndTime(source.getEndTime());
        target.setSuspended(source.isSuspended());
        target.setOperatorId(source.getOperatorId());
        target.setFailureReason(source.getFailureReason());
        target.setRevision(source.getRevision());
        return target;
    }

    private static WfBatchElement copy(WfBatchElement source) {
        if (source == null) {
            return null;
        }
        WfBatchElement target = new WfBatchElement();
        target.setId(source.getId());
        target.setBatchId(source.getBatchId());
        target.setType(source.getType());
        target.setTargetId(source.getTargetId());
        target.setState(source.getState());
        target.setFailureMessage(source.getFailureMessage());
        target.setHandledAt(source.getHandledAt());
        return target;
    }

    // ==================== 历史故障（第 40 轮） ====================

    @Override
    public void saveHistoricIncident(WfHistoricIncident incident) {
        if (incident == null || incident.getJobId() == null) {
            throw new WfPersistenceException("历史故障记录必须有 jobId —— 它是这行的天然身份");
        }
        WfHistoricIncident existing = historicIncidents.get(incident.getJobId());
        // 与 saveTask / saveJob / saveBatch 同一套乐观锁契约，见 WfPersistence#saveHistoricIncident。
        // 一次重试链里可能有两个执行器线程先后记失败，没有版本号会丢掉一次 failureCount
        if (existing != null && incident.getRevision() != existing.getRevision() + 1) {
            throw new WfOptimisticLockException("historicIncident",
                    incident.getJobId(), existing.getRevision() + 1);
        }
        historicIncidents.put(incident.getJobId(), copy(incident));
    }

    @Override
    public WfHistoricIncident findHistoricIncidentByJobId(String jobId) {
        // null 直接返回 null：**两套实现必须对 null 输入给出同一个答案**。
        // ConcurrentHashMap.get(null) 抛 NPE，而 JDBC 那侧本来就返回 null ——
        // 不加这道防护的话，同一个查询在内存模式下崩、在生产模式下返回空，
        // 而开发期常用的恰恰是内存模式。
        if (jobId == null || jobId.trim().isEmpty()) {
            return null;
        }
        return copy(historicIncidents.get(jobId));
    }

    @Override
    public List<WfHistoricIncident> queryHistoricIncidents(WfHistoricIncidentQuery query) {
        List<WfHistoricIncident> matched = new ArrayList<>();
        for (WfHistoricIncident raw : historicIncidents.values()) {
            if (matches(raw, query)) {
                matched.add(copy(raw));
            }
        }
        // 最近一次失败在前：**历史故障查询的默认意图就是「刚刚发生了什么」**。
        // 同一毫秒内按 jobId 兜底保证次序确定 —— 不确定的话分页会漏条目
        matched.sort((a, b) -> {
            Date ta = a.getLastFailureTime();
            Date tb = b.getLastFailureTime();
            if (ta != null && tb != null && !ta.equals(tb)) {
                return tb.compareTo(ta);
            }
            return String.valueOf(a.getJobId()).compareTo(String.valueOf(b.getJobId()));
        });
        if (query == null) {
            return matched;
        }
        return paginate(matched, (query.normalizedPageNum() - 1) * query.normalizedPageSize(),
                query.normalizedPageSize());
    }

    @Override
    public int countHistoricIncidents(WfHistoricIncidentQuery query) {
        int count = 0;
        for (WfHistoricIncident raw : historicIncidents.values()) {
            if (matches(raw, query)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int deleteHistoricIncidentsBefore(Date before) {
        if (before == null) {
            throw new WfPersistenceException("清理历史故障必须给一个时间点");
        }
        int removed = 0;
        for (Map.Entry<String, WfHistoricIncident> entry : historicIncidents.entrySet()) {
            WfHistoricIncident incident = entry.getValue();
            if (incident.getLastFailureTime() != null && incident.getLastFailureTime().before(before)) {
                historicIncidents.remove(entry.getKey());
                removed++;
            }
        }
        return removed;
    }

    /**
     * 历史故障过滤。与 {@link #queryHistoricIncidents} / {@link #countHistoricIncidents}
     * 共用同一段判定：列表说 3 条而 total 说 5 条的话，分页器会以为还有第 2 页。
     */
    private boolean matches(WfHistoricIncident incident, WfHistoricIncidentQuery query) {
        if (query == null) {
            return true;
        }
        if (isNotBlank(query.getJobId()) && !query.getJobId().equals(incident.getJobId())) {
            return false;
        }
        if (isNotBlank(query.getProcessInstanceId())
                && !query.getProcessInstanceId().equals(incident.getProcessInstanceId())) {
            return false;
        }
        if (isNotBlank(query.getDefinitionKey())
                && !query.getDefinitionKey().equals(incident.getDefinitionKey())) {
            return false;
        }
        if (isNotBlank(query.getActivityId())
                && !query.getActivityId().equals(incident.getElementId())) {
            return false;
        }
        if (isNotBlank(query.getJobType())
                && !query.getJobType().equalsIgnoreCase(incident.getJobType())) {
            return false;
        }
        if (isNotBlank(query.getErrorType()) && (incident.getErrorType() == null
                || !incident.getErrorType().contains(query.getErrorType()))) {
            return false;
        }
        if (isNotBlank(query.getErrorMessageContains()) && (incident.getErrorMessage() == null
                || !incident.getErrorMessage().contains(query.getErrorMessageContains()))) {
            return false;
        }
        if (query.getFirstFailureFrom() != null && (incident.getFirstFailureTime() == null
                || incident.getFirstFailureTime().before(query.getFirstFailureFrom()))) {
            return false;
        }
        if (query.getLastFailureBefore() != null && (incident.getLastFailureTime() == null
                || !incident.getLastFailureTime().before(query.getLastFailureBefore()))) {
            return false;
        }
        return query.getMinFailureCount() == null
                || incident.getFailureCount() >= query.getMinFailureCount();
    }

    private static WfHistoricIncident copy(WfHistoricIncident source) {
        if (source == null) {
            return null;
        }
        WfHistoricIncident target = new WfHistoricIncident();
        target.setId(source.getId());
        target.setJobId(source.getJobId());
        target.setProcessInstanceId(source.getProcessInstanceId());
        target.setExecutionId(source.getExecutionId());
        target.setElementId(source.getElementId());
        target.setAttachedToRef(source.getAttachedToRef());
        target.setDefinitionKey(source.getDefinitionKey());
        target.setActivityName(source.getActivityName());
        target.setJobType(source.getJobType());
        target.setSubscriptionName(source.getSubscriptionName());
        target.setErrorType(source.getErrorType());
        target.setErrorMessage(source.getErrorMessage());
        target.setFailureCount(source.getFailureCount());
        target.setFirstFailureTime(source.getFirstFailureTime());
        target.setLastFailureTime(source.getLastFailureTime());
        target.setRevision(source.getRevision());
        return target;
    }

    @Override
    public int deleteJobsByExecution(String executionId) {
        int removed = 0;
        for (WfJob job : jobs.values()) {
            if (executionId.equals(job.getExecutionId())) {
                jobs.remove(job.getId());
                removed++;
            }
        }
        return removed;
    }

    /**
     * job 过滤。与 {@link #queryJobs} / {@link #countJobs} 共用同一个判定 ——
     * count 与列表口径必须一致，否则执行器日志里"处理了 5 个"和实际影响到的流程对不上。
     */
    private boolean matches(WfJob job, WfJobQuery query) {
        if (query == null) {
            return true;
        }
        if (isNotBlank(query.getProcessInstanceId())
                && !query.getProcessInstanceId().equals(job.getProcessInstanceId())) {
            return false;
        }
        if (isNotBlank(query.getElementId())
                && !query.getElementId().equals(job.getElementId())) {
            return false;
        }
        if (query.getDueBefore() != null
                && (job.getDuedate() == null || !job.getDuedate().before(query.getDueBefore()))) {
            return false;
        }
        // 与 JdbcWorkflowPersistence 的 appendJobFilters 同口径。
        // 漏了这个条件两套实现就在"带 duedate 的订阅"上分歧：JDBC 侧捞不到，
        // 内存侧捞得到 —— 而开发期默认用内存实现，于是问题到生产才暴露，
        // 现象是"扫描器把消息订阅执行了，流程自己往前走了"
        if (query.getType() != null && job.getType() != query.getType()) {
            return false;
        }
        if (query.getRetriesExhausted() != null
                && job.isRetriesExhausted() != query.getRetriesExhausted()) {
            return false;
        }
        // topic 过滤同理：不接的话管理端在内存实现下看到"所有主题的活"，
        // 而 JDBC 侧是空的
        if (isNotBlank(query.getTopic()) && !query.getTopic().equals(job.getTopic())) {
            return false;
        }
        return true;
    }

    @Override
    public synchronized void clear() {
        definitions.clear();
        processInstances.clear();
        executions.clear();
        tasks.clear();
        activities.clear();
        comments.clear();
        jobs.clear();
        filters.clear();
        decisions.clear();
        // compensations 原来漏在这里（第 39 轮补）。后果不是"测试之间互相污染"这么轻：
        // clear() 是"内存存储现在空的"这句话的兑现处，漏一项就会出现
        // 「刚 clear 完，自省面板上补偿登记还有 37 条」——而其它九张表都是 0。
        compensations.clear();
        batches.clear();
        batchElements.clear();
        historicIncidents.clear();
    }

    // ==================== 保存筛选器 ====================

    @Override
    public void saveFilter(WfFilter filter) {
        if (filter == null || filter.getId() == null) {
            return;
        }
        WfFilter existing = filters.get(filter.getId());
        // 与 saveTask / saveJob 同一套乐观锁契约，见 WfPersistence#saveFilter
        if (existing != null && filter.getRevision() != existing.getRevision() + 1) {
            throw new WfOptimisticLockException("filter", filter.getId(), existing.getRevision() + 1);
        }
        filters.put(filter.getId(), copy(filter));
    }

    @Override
    public WfFilter findFilter(String id) {
        return copy(filters.get(id));
    }

    @Override
    public boolean deleteFilter(String id) {
        return id != null && filters.remove(id) != null;
    }

    @Override
    public List<WfFilter> queryFilters(WfFilterQuery query) {
        List<WfFilter> matched = new ArrayList<>();
        for (WfFilter raw : filters.values()) {
            if (matchesFilter(raw, query)) {
                matched.add(copy(raw));
            }
        }
        // 排序：创建时间新的在前，同一毫秒内按 id 排。
        // **必须有确定的次序**——同一批筛选器两次列出来顺序不同的话，
        // 翻页会漏条目、diff 两次结果也读不出区别
        matched.sort((a, b) -> {
            Date ta = a.getCreateTime();
            Date tb = b.getCreateTime();
            if (ta != null && tb != null && !ta.equals(tb)) {
                return tb.compareTo(ta);
            }
            return a.getId().compareTo(b.getId());
        });
        if (query == null) {
            return matched;
        }
        return paginate(matched, (query.normalizedPageNum() - 1) * query.normalizedPageSize(),
                query.normalizedPageSize());
    }

    @Override
    public int countFilters(WfFilterQuery query) {
        int count = 0;
        for (WfFilter raw : filters.values()) {
            if (matchesFilter(raw, query)) {
                count++;
            }
        }
        return count;
    }

    private boolean matchesFilter(WfFilter filter, WfFilterQuery query) {
        if (query == null) {
            return true;
        }
        if (isNotBlank(query.getId()) && !query.getId().equals(filter.getId())) {
            return false;
        }
        if (isNotBlank(query.getName()) && !query.getName().equals(filter.getName())) {
            return false;
        }
        if (isNotBlank(query.getNameLike())) {
            if (filter.getName() == null || !filter.getName().toLowerCase()
                    .contains(query.getNameLike().toLowerCase())) {
                return false;
            }
        }
        if (query.getResourceType() != null && query.getResourceType() != filter.getResourceType()) {
            return false;
        }
        return !(isNotBlank(query.getOwner()) && !query.getOwner().equals(filter.getOwner()));
    }

    // ==================== 拷贝 ====================

    /**
     * 深拷贝。
     *
     * <p>用 <b>Java 原生序列化</b>（所有 model 都实现了 {@link java.io.Serializable}），
     * 而不是 JSON 往返或手写逐字段拷贝。三个原因，按重要性排：
     * <ol>
     *   <li><b>JSON 往返在本仓已被证伪</b>：{@code JsonUtil.toJson} 把 {@link Date}
     *       序列化成 epoch-millis 的 Long，而 {@code fromJson} 无法把 Long 还原成 Date
     *       （实测抛 {@code Can not set java.util.Date field ... to java.lang.Long}）。
     *       任何带时间戳的实体（流程实例、任务、活动历史）都会在第一次拷贝时炸掉。</li>
     *   <li><b>手写逐字段拷贝会漏字段</b>：新增一个成员变量却忘了在 copy 里补一行，
     *       症状是该字段在内存实现下永远是默认值，而 JDBC 实现正常 ——
     *       这类 bug 只在换存储后才暴露，极难定位。原生序列化不存在这个漏洞面。</li>
     *   <li>原生序列化对 {@link WfDefinition} 的 transient 索引也安全：
     *       读回来时索引为 null，由 {@code buildIndex()} / 惰性索引重建。</li>
     * </ol>
     *
     * <p>代价是比 JSON 慢。内存实现本来就不是生产路径，这个开销可以接受；
     * 需要更快时也应该去优化 JDBC 实现，而不是改这里的拷贝语义。
     */
    @SuppressWarnings("unchecked")
    private static <T> T copy(T source) {
        if (source == null) {
            return null;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            try {
                oos.writeObject(source);
            } finally {
                oos.close();
            }
            ObjectInputStream ois = new ObjectInputStream(
                    new ByteArrayInputStream(bos.toByteArray()));
            try {
                return (T) ois.readObject();
            } finally {
                ois.close();
            }
        } catch (Exception e) {
            log.error("内存实现深拷贝失败: {}", source.getClass().getName(), e);
            throw new IllegalStateException("内存实现深拷贝失败: " + source.getClass().getName(), e);
        }
    }

    private static <T> List<T> paginate(List<T> list, int offset, int size) {
        if (list.isEmpty() || offset >= list.size()) {
            return new ArrayList<>();
        }
        int from = Math.max(0, offset);
        int to = Math.min(list.size(), from + size);
        return new ArrayList<>(list.subList(from, to));
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    // ==================== 决策（DMN）====================

    @Override
    public void saveDecision(WfDmnDecision decision) {
        if (decision == null || decision.getKey() == null) {
            return;
        }
        decisions.put(decisionKey(decision.getKey(), decision.getVersion()), copy(decision));
    }

    @Override
    public WfDmnDecision findDecision(String key, int version) {
        return copy(decisions.get(decisionKey(key, version)));
    }

    @Override
    public WfDmnDecision findLatestDecision(String key) {
        WfDmnDecision latest = null;
        for (WfDmnDecision candidate : decisions.values()) {
            if (key == null ? candidate.getKey() == null : key.equals(candidate.getKey())
                    && (latest == null || candidate.getVersion() > latest.getVersion())) {
                latest = candidate;
            }
        }
        return copy(latest);
    }

    @Override
    public List<WfDmnDecision> findDecisionVersions(String key) {
        List<WfDmnDecision> found = new ArrayList<>();
        for (WfDmnDecision candidate : decisions.values()) {
            if (key == null ? candidate.getKey() == null : key.equals(candidate.getKey())) {
                found.add(copy(candidate));
            }
        }
        // 按 version **倒序**（与 findDefinitionVersions 同约定）：
        // "这个决策改过几版、每版长什么样"要的是从新往旧看
        Collections.sort(found, new Comparator<WfDmnDecision>() {
            @Override
            public int compare(WfDmnDecision left, WfDmnDecision right) {
                return right.getVersion() - left.getVersion();
            }
        });
        return found;
    }

    @Override
    public boolean deleteDecision(String key, int version) {
        return key != null && decisions.remove(decisionKey(key, version)) != null;
    }

    /** 决策的存储键。版本是 key 的一部分 —— 同一 key 的多版本必须能并存。 */
    private static String decisionKey(String key, int version) {
        return key + "@" + version;
    }

    /**
     * 决策的深拷贝。
     *
     * <p>与其他实体同一套理由：内存实现对外不共享引用，调用方拿到的那份改了
     * 不该影响库里那份 —— 否则"改一下变量"就会把已部署的决策表改掉。
     */
    private static WfDmnDecision copy(WfDmnDecision source) {
        if (source == null) {
            return null;
        }
        WfDmnDecision target = new WfDmnDecision(source.getKey(), source.getName());
        target.setVersion(source.getVersion());
        target.setDmnXml(source.getDmnXml());
        target.setDeployTime(source.getDeployTime());
        target.setTable(copy(source.getTable()));
        // 依赖边必须拷。漏这一行的话，内存实现部署一张决策图之后
        // 读回来的是"没有依赖"的同一条决策 —— 求值时于是不跑上游，
        // 结果是**静默地按单表算**，而且只在内存实现上出现，
        // 与 JDBC 实现行为不一致，正是最难查的那种分歧。
        target.setRequiredDecisions(new ArrayList<>(source.getRequiredDecisions()));
        return target;
    }

    private static WfDmnDecision.WfDmnTable copy(WfDmnDecision.WfDmnTable source) {
        if (source == null) {
            return null;
        }
        WfDmnDecision.WfDmnTable target = new WfDmnDecision.WfDmnTable();
        target.setId(source.getId());
        target.setHitPolicy(source.getHitPolicy());
        target.setAggregator(source.getAggregator());
        target.setInputExpressions(new ArrayList<>(source.getInputExpressions()));
        List<WfDmnDecision.WfDmnOutput> outputs = new ArrayList<>();
        for (WfDmnDecision.WfDmnOutput output : source.getOutputs()) {
            WfDmnDecision.WfDmnOutput copy = new WfDmnDecision.WfDmnOutput();
            copy.setName(output.getName());
            copy.setTypeRef(output.getTypeRef());
            copy.setOutputValues(new ArrayList<>(output.getOutputValues()));
            outputs.add(copy);
        }
        target.setOutputs(outputs);
        List<WfDmnDecision.WfDmnRule> rules = new ArrayList<>();
        for (WfDmnDecision.WfDmnRule rule : source.getRules()) {
            WfDmnDecision.WfDmnRule copy = new WfDmnDecision.WfDmnRule();
            copy.setInputEntries(new ArrayList<>(rule.getInputEntries()));
            copy.setOutputEntries(new ArrayList<>(rule.getOutputEntries()));
            rules.add(copy);
        }
        target.setRules(rules);
        return target;
    }
}
