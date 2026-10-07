package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.persistence.WfIncidentQuery;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.view.WfIncidentView;

/**
 * 运行期故障查询 —— "哪些事情没干成，而且正在为此付出代价"。
 *
 * <p><b>它为什么必须存在</b>：一个执行失败的 job 会留在 {@code ZWF_JOB} 表里
 * （成功后才会被删），重试耗尽后也不会自动清理。此前<b>没有任何接口能列出它们</b>：
 * 订阅查询按设计只看"还没出错的等待"，job 查询是给执行器用的内部接口。
 * 结果是运维能看见"单子不动了"，却查不出"它到底有没有在挣扎、挣扎到第几次、报的是什么错"。
 *
 * <p><b>故障从 job 派生，不另建表</b>（理由见 {@link WfIncidentView}）：
 * 一件事两处真源是排障时最不能容忍的形态。
 *
 * <p><b>它是纯读的</b>：不重试、不触发、不改任何状态。
 * 注意"纯读"指的是<b>不改 job 的状态</b> —— 底层 {@code updateJob} 自带
 * "查回来就记一次失败"的行为，那是持久化层的既有契约，与查询本身无关。
 *
 * <p><b>为什么在内存里过滤</b>：{@code errorMessageContains} 与订阅名在
 * {@code ZWF_JOB} 上都没有可用的 WHERE 支持（{@code SUBSCRIPTION_NAME} 虽然已拆列，
 * 但引擎没有为它建索引，而故障量级很小）。逐条捞回过滤够用。
 *
 * @author zifang
 */
public class WfIncidentService {

    private static final Logger log = LoggerFactory.getLogger(WfIncidentService.class);

    /**
     * 一次最多捞多少行 job 回来。
     *
     * <p>比订阅的 {@code 2000} 宽松：故障是<b>累积</b>的 —— 清理逻辑没跟上时
     * 会一直涨，而这种积压恰恰是最需要被查出来的场景，早报反而看不见了。
     */
    public static final int MAX_SCAN = 5000;

    private final WfPersistence persistence;
    private final WfRepositoryService repositoryService;

    public WfIncidentService(WfPersistence persistence, WfRepositoryService repositoryService) {
        this.persistence = persistence;
        this.repositoryService = repositoryService;
    }

    /**
     * 查故障列表。
     *
     * @return 当前页；没有匹配时是<b>空列表</b>而不是 null
     * @throws WfEngineException 匹配数超过 {@link #MAX_SCAN} —— 静默截断一份
     *         "看起来完整"的故障列表比报错危险得多：那会让运维以为"全都查过了，没问题"，
     *         而故障恰恰是最不该被"看起来完整"糊弄过去的东西
     */
    public List<WfIncidentView> listIncidents(WfIncidentQuery query) {
        WfIncidentQuery actual = query == null ? new WfIncidentQuery() : query;
        List<WfIncidentView> matched = new ArrayList<>();
        for (WfIncidentView view : scan(actual)) {
            if (matches(actual, view)) {
                matched.add(view);
            }
        }
        int from = (actual.normalizedPageNum() - 1) * actual.normalizedPageSize();
        if (from >= matched.size()) {
            return new ArrayList<WfIncidentView>();
        }
        int to = Math.min(from + actual.normalizedPageSize(), matched.size());
        return new ArrayList<WfIncidentView>(matched.subList(from, to));
    }

    /**
     * 匹配总数（不分页）。
     *
     * <p>与 {@link #listIncidents} 走同一套扫描与判定，分开算的话两次查询之间
     * 状态又变了，数字对不上，而调用方只会以为自己算错了。
     */
    public int countIncidents(WfIncidentQuery query) {
        WfIncidentQuery actual = query == null ? new WfIncidentQuery() : query;
        int count = 0;
        for (WfIncidentView view : scan(actual)) {
            if (matches(actual, view)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 某个流程实例当前的故障。
     *
     * <p>刻意<b>不分页</b>：{@code pageSize} 会被 {@code normalizedPageSize()} 归一到
     * 1000，所以"传一个超大 pageSize"实际上是给了调用方一个<b>静默的上限</b> ——
     * 而故障超过 1000 条的实例正是最需要被完整看见的那一种
     * （{@link #MAX_SCAN} 允许扫到 5000，说明这个量级是被承认存在的）。
     * 走匹配全集就没有这个夹层。
     */
    public List<WfIncidentView> incidentsOf(String processInstanceId) {
        WfIncidentQuery actual = new WfIncidentQuery().setProcessInstanceId(processInstanceId);
        List<WfIncidentView> matched = new ArrayList<>();
        for (WfIncidentView view : scan(actual)) {
            if (matches(actual, view)) {
                matched.add(view);
            }
        }
        return matched;
    }

    // ==================== 扫描 ====================

    private List<WfIncidentView> scan(WfIncidentQuery query) {
        List<WfIncidentView> views = new ArrayList<>();
        // 多读一条：读满 MAX_SCAN 恰好等于上限时无法区分"刚好这么多"与"还有更多"
        WfJobQuery jobQuery = new WfJobQuery().setPageNum(1).setPageSize(MAX_SCAN + 1);
        if (query.getProcessInstanceId() != null
                && !query.getProcessInstanceId().trim().isEmpty()) {
            jobQuery.setProcessInstanceId(query.getProcessInstanceId());
        }
        if (query.getActivityId() != null && !query.getActivityId().trim().isEmpty()) {
            jobQuery.setElementId(query.getActivityId());
        }
        List<WfJob> jobs = persistence.queryJobs(jobQuery);
        if (jobs == null || jobs.isEmpty()) {
            return views;
        }
        // 判溢出用**读到的原始行数**：下面会丢掉"从未失败过"的 job，
        // 拿过滤后的数去判，恰好赶上"一半是正常等待"时会误以为没超量而静默截断
        if (jobs.size() > MAX_SCAN) {
            throw new WfEngineException("运行期故障超过 " + MAX_SCAN
                    + " 条，结果已被截断。请先用 processInstanceId 或 definitionKey 缩小范围 —— "
                    + "这个数量本身通常意味着失败清理逻辑没跟上（job 执行成功后本就该被删）");
        }
        for (WfJob job : jobs) {
            if (job == null || !hasFailed(job)) {
                continue;
            }
            WfProcessInstance instance = job.getProcessInstanceId() == null ? null
                    : persistence.findProcessInstance(job.getProcessInstanceId());
            // 流程没了而 job 还在：故障无从处理（没有实例可终止、没人可通知），
            // 但必须报出来 —— 它是清理漏了 steps 的证据，藏起来就永远没人发现
            if (instance == null) {
                log.warn("故障 job {} 属于已不存在的流程实例 {}，可能是清理逻辑漏了一步", job.getId(),
                        job.getProcessInstanceId());
                views.add(toView(job, null, null));
                continue;
            }
            views.add(toView(job, instance, repositoryService.getDefinitionOrLatest(
                    instance.getDefinitionKey(), instance.getDefinitionVersion())));
        }
        return views;
    }

    /**
     * 这个 job 算不算一条故障。
     *
     * <p>判据是<b>失败过</b>（{@code lastFailureTime} 有值），不是"retries 少了"。
     * 后者会把「创建时就显式把 retries 设为 0 的 job」也算成故障，
     * 而那种 job 从没失败过，只是配置成不重试。
     */
    private boolean hasFailed(WfJob job) {
        return job.getLastFailureTime() != null || job.getExceptionMessage() != null;
    }

    private boolean matches(WfIncidentQuery query, WfIncidentView view) {
        if (query.getProcessInstanceId() != null && !query.getProcessInstanceId().trim().isEmpty()
                && !query.getProcessInstanceId().equals(view.getProcessInstanceId())) {
            return false;
        }
        if (query.getDefinitionKey() != null && !query.getDefinitionKey().trim().isEmpty()
                && !query.getDefinitionKey().equals(view.getDefinitionKey())) {
            return false;
        }
        if (!query.getTypes().isEmpty() && view.getJobType() != null
                && !query.getTypes().contains(
                        com.zifang.z.wf.core.model.WfJobType.valueOf(view.getJobType()))) {
            return false;
        }
        if (query.getActivityId() != null && !query.getActivityId().trim().isEmpty()
                && !query.getActivityId().equals(view.getActivityId())) {
            return false;
        }
        // 注意这里取的是**反义**：查询参数 retriesExhausted=false 的含义是
        // 「只看还没耗尽的」，对应 view 上 isRetryable()==true。
        // 直接拿参数与 isRetryable() 比大小会把两侧取反 ——
        // 于是筛"还在重试"时一条都查不出来，而调用方会读成"没有在重试的故障"
        if (query.getRetriesExhausted() != null
                && query.getRetriesExhausted().booleanValue() == view.isRetryable()) {
            return false;
        }
        // 刻意不下推成 SQL 条件：LAST_FAIL_TIME 虽然有列，但为它加一个
        // WfJobQuery 条件就得同步改内存与 JDBC 两套实现，而故障量级很小，
        // 捞回来判一下的代价可以忽略
        if (query.getFailedBefore() != null
                && (view.getLastFailureTime() == null
                || !view.getLastFailureTime().before(query.getFailedBefore()))) {
            return false;
        }
        String contains = query.getErrorMessageContains();
        if (contains != null && !contains.trim().isEmpty()
                && (view.getErrorMessage() == null
                || !view.getErrorMessage().contains(contains.trim()))) {
            return false;
        }
        return true;
    }

    private WfIncidentView toView(WfJob job, WfProcessInstance instance, WfDefinition definition) {
        WfIncidentView view = new WfIncidentView();
        view.setId(job.getId());
        view.setProcessInstanceId(job.getProcessInstanceId());
        view.setExecutionId(job.getExecutionId());
        view.setActivityId(job.getElementId());
        view.setAttachedToRef(job.getAttachedToRef());
        view.setDefinitionKey(instance == null ? null : instance.getDefinitionKey());
        view.setJobType(job.getType() == null ? null : job.getType().name());
        // 订阅名与失败原因各取各的：前者说"它在等什么"，后者说"它报了什么错"。
        // 两者原来挤在一列里，这个视图只能显示其中一个 —— 而只有两个都在，
        // "等 X 却没等到，报 Y"这句话才说得出来
        view.setSubscriptionName(job.getSubscriptionName() != null
                ? job.getSubscriptionName() : job.getTopic());
        view.setErrorMessage(job.getExceptionMessage());
        // 类型解析只有一处实现（第 40 轮收敛到 WfJob#exceptionTypeOf）。
        // 这里曾经有一份逐字相同的私有副本 —— 同一句话两个答案的温床：
        // 新写的那份少了「首字母大写才算类名」这条，于是外部任务报的
        // 「连接超时: 连不上 db」在历史里被当成类型、在当前故障里不是
        view.setErrorType(com.zifang.z.wf.core.model.WfJob.exceptionTypeOf(
                job.getExceptionMessage()));
        view.setLastFailureTime(job.getLastFailureTime());
        view.setFailedMillis(job.getLastFailureTime() == null ? null
                : System.currentTimeMillis() - job.getLastFailureTime().getTime());
        view.setRetries(job.getRetries());
        view.setRetryable(!job.isRetriesExhausted());
        if (definition != null) {
            // 边界事件的 elementId 指事件本身（timerEscalate），人看的是宿主节点的
            // 名字（超时升级）—— 拿事件自己的名字去搜流程图会搜不到
            WfNode node = definition.node(job.getAttachedToRef() != null
                    ? job.getAttachedToRef() : job.getElementId());
            view.setActivityName(node == null ? null : node.getName());
        }
        return view;
    }

}