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
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;

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
        versions.put(definition.getVersion(), copy(definition));
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
        processInstances.put(instance.getId(), copy(instance));
    }

    @Override
    public WfProcessInstance findProcessInstance(String id) {
        return copy(processInstances.get(id));
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
        if (isNotBlank(query.getProcessInstanceId())
                && !query.getProcessInstanceId().equals(task.getProcessInstanceId())) {
            return false;
        }
        if (isNotBlank(query.getDefinitionId()) && !query.getDefinitionId().equals(task.getDefinitionId())) {
            return false;
        }
        if (isNotBlank(query.getAssignee()) && !query.getAssignee().equals(task.getAssignee())) {
            return false;
        }
        if (isNotBlank(query.getOwner()) && !query.getOwner().equals(task.getOwner())) {
            return false;
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

    @Override
    public synchronized void clear() {
        definitions.clear();
        processInstances.clear();
        executions.clear();
        tasks.clear();
        activities.clear();
        comments.clear();
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
}
