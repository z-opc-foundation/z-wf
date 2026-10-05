package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfFlow;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.persistence.WfProcessInstanceQuery;
import com.zifang.z.wf.core.persistence.WfSubscriptionQuery;
import com.zifang.z.wf.core.view.WfSubscriptionView;

/**
 * 订阅查询 —— "现在有哪些流程在等什么"。
 *
 * <p><b>它为什么必须存在</b>：一个在等消息的流程实例，在待办列表里没有任务、
 * 在轨迹里没有新记录、在评论里没有动静，而且<b>没有任何报错</b>。没有这张表，
 * 排障时唯一的办法是去翻 XML 猜"它大概停在哪个事件上"，而事件的投递方在别的系统里。
 *
 * <p><b>它是纯读的</b>：不推进流程、不投递事件、不改任何状态。
 * 唯一"有副作用"的地方是它会因为重试把有问题的 job 记成失败 —— 那是持久化层的
 * {@code updateJob} 自带的行为，与查询本身无关。
 *
 * <p><b>为什么在内存里过滤</b>：条件里有一半（事件名、已等待时长）在本仓的
 * {@code ZWF_JOB} 表上<b>没有对应列</b> —— 事件名与失败原因同列是订阅型 job 的
 * 历史决定（现已拆成 {@code SUBSCRIPTION_NAME} 与 {@code EXCEPTION_MSG} 两列，
 * 但仍没有"事件名等值"的索引与 WHERE 支持，见 {@code WfJob#subscriptionName}）。
 * 要把它们变成 WHERE 条件，就得改表结构并同步两套实现；而订阅的量级是"在办的单数"
 * （审批系统里通常只有同时在办的那几十上百条），全捞回来过滤完全够用。
 * 真正会堆积的是 job 的<b>历史</b>，而那些已经被执行器消费掉了。
 *
 * <p>代价要说清楚：{@code definitionKey} 之外的过滤在服务端做，
 * 所以分页是在<b>过滤后</b>的结果上切片的，而不是在数据库上。
 * 调用方拿到"第 2 页"时，前面几页必须已经用同一组条件查过 ——
 * 条件一变，页码的含义就变了。
 *
 * @author zifang
 */
public class WfSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(WfSubscriptionService.class);

    /**
     * 一次最多捞多少行 job 回来过滤。
     *
     * <p>与 {@code WfJobService#executeDueJobs} 同一个量级考量。超出这个数说明
     * 积压已经不正常（要么清理逻辑没跟上，要么有流程设计成永远等下去），
     * 那时候应当报"结果可能被截断"而不是悄悄给一份不完整的列表。
     */
    public static final int MAX_SCAN = 2000;

    private final WfPersistence persistence;
    private final WfRepositoryService repositoryService;

    public WfSubscriptionService(WfPersistence persistence, WfRepositoryService repositoryService) {
        this.persistence = persistence;
        this.repositoryService = repositoryService;
    }

    /**
     * 查订阅列表。
     *
     * @return 当前页的订阅；没有匹配时是<b>空列表</b>而不是 null
     * @throws com.zifang.z.wf.core.service.WfEngineException 匹配数超过
     *         {@link #MAX_SCAN} —— 静默截断一份"看起来完整"的列表，
     *         比报错危险得多：那会让运维以为"全都查过了，没有问题"
     */
    public List<WfSubscriptionView> listSubscriptions(WfSubscriptionQuery query) {
        WfSubscriptionQuery actual = query == null ? new WfSubscriptionQuery() : query;
        List<WfSubscriptionView> matched = new ArrayList<>();
        for (WfSubscriptionView view : scan(actual)) {
            if (matches(actual, view)) {
                matched.add(view);
            }
        }
        int from = (actual.normalizedPageNum() - 1) * actual.normalizedPageSize();
        if (from >= matched.size()) {
            return new ArrayList<WfSubscriptionView>();
        }
        int to = Math.min(from + actual.normalizedPageSize(), matched.size());
        return new ArrayList<>(matched.subList(from, to));
    }

    /**
     * 匹配总数（不分页）。
     *
     * <p>与 {@link #listSubscriptions} 共用同一次扫描逻辑，分开算一次的结果是
     * 两次查询之间流程又推进了，数字对不上，而调用方只会以为自己算错了。
     */
    public int countSubscriptions(WfSubscriptionQuery query) {
        WfSubscriptionQuery actual = query == null ? new WfSubscriptionQuery() : query;
        int count = 0;
        for (WfSubscriptionView view : scan(actual)) {
            if (matches(actual, view)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 某个流程实例当前在等什么。
     *
     * <p>刻意不设上限返回：一条流程同时等着的订阅数量是有限的（网关分支数、边界事件数），
     * 超过这个数的定义本身就是坏的，而那种情况由部署期校验拦。
     */
    public List<WfSubscriptionView> subscriptionsOf(String processInstanceId) {
        return listSubscriptions(new WfSubscriptionQuery()
                .setProcessInstanceId(processInstanceId)
                .setPageNum(1)
                .setPageSize(Integer.MAX_VALUE));
    }

    // ==================== 扫描 ====================

    /**
     * 把 job 翻成视图并带上流程与节点信息。
     *
     * <p>流程实例与定义逐条现查而不是批量灌 —— 订阅的量级就是"同时在办的单数"，
     * 逐条查的开销远小于为它写一套批量装载，而批量装载一旦漏了某个条件就会
     * 静默返回错的 key，比慢更糟。
     */
    private List<WfSubscriptionView> scan(WfSubscriptionQuery query) {
        List<WfSubscriptionView> views = new ArrayList<>();
        // 多读一条：读满 MAX_SCAN 恰好等于上限时无法区分"刚好这么多"与"还有更多"，
        // 而这两种情况下"结果是否完整"的结论完全相反
        WfJobQuery jobQuery = new WfJobQuery()
                .setPageNum(1)
                .setPageSize(MAX_SCAN + 1);
        if (query.getProcessInstanceId() != null
                && !query.getProcessInstanceId().trim().isEmpty()) {
            jobQuery.setProcessInstanceId(query.getProcessInstanceId());
        }
        if (query.getDueBefore() != null) {
            jobQuery.setDueBefore(query.getDueBefore());
        }
        if (query.getActivityId() != null && !query.getActivityId().trim().isEmpty()) {
            jobQuery.setElementId(query.getActivityId());
        }
        List<WfJob> jobs = persistence.queryJobs(jobQuery);
        if (jobs == null || jobs.isEmpty()) {
            return views;
        }
        // 判溢出用**读到的原始行数**，不是过滤后的条数：终态实例的 job 会被跳过，
        // 拿过滤后的数去判，恰好赶上"一半是脏数据"时会误以为没超量而静默截断。
        // 判据是"还可能没读完"这件事本身，与之后怎么过滤无关。
        if (jobs.size() > MAX_SCAN) {
            throw new WfEngineException("在等的订阅超过 " + MAX_SCAN
                    + " 条，结果已被截断。请先用 processInstanceId 或 definitionKey 缩小范围 —— "
                    + "这个数量本身通常意味着有清理逻辑没跟上，或者某条流程被设计成永远等下去");
        }
        for (WfJob job : jobs) {
            if (job == null) {
                continue;
            }
            WfProcessInstance instance = job.getProcessInstanceId() == null ? null
                    : persistence.findProcessInstance(job.getProcessInstanceId());
            // 流程已结束而 job 还在：这是清理漏了一步，订阅已经没有意义。
            // 报出来而不是藏起来 —— 藏起来的话"job 表在涨"这件事就没人发现了
            if (instance == null) {
                log.warn("job {} 属于已不存在的流程实例 {}, 可能是清理逻辑漏了一步", job.getId(),
                        job.getProcessInstanceId());
                continue;
            }
            if (instance.getStatus() != null && instance.getStatus().isTerminal()) {
                log.warn("流程 {} 已是终态 {}, 却仍有等待中的 job {}，不计入订阅", instance.getId(),
                        instance.getStatus(), job.getId());
                continue;
            }
            views.add(toView(job, instance, repositoryService.getDefinitionOrLatest(
                    instance.getDefinitionKey(), instance.getDefinitionVersion())));
        }
        return views;
    }

    private WfSubscriptionView toView(WfJob job, WfProcessInstance instance, WfDefinition definition) {
        WfSubscriptionView view = new WfSubscriptionView();
        view.setId(job.getId());
        view.setProcessInstanceId(instance.getId());
        view.setExecutionId(job.getExecutionId());
        // activityId 必须带上：按节点过滤、按节点看"name 是哪一步"全靠它。
        // 漏掉的话 view 上的 activityId 恒为 null，节点过滤会静默返回空列表 ——
        // 而"查不到"与"没有"在调用方眼里长得一模一样
        view.setActivityId(job.getElementId());
        view.setDefinitionKey(instance.getDefinitionKey());
        view.setJobType(job.getType() == null ? null : job.getType().name());
        view.setWaitingFor(waitingFor(job.getType()));
        view.setEventName(job.getSubscriptionName() != null
                ? job.getSubscriptionName() : job.getTopic());
        view.setDuedate(job.getDuedate());
        view.setLockedBy(job.getLockedBy());
        view.setRetries(job.getRetries());
        view.setWaitingMillis(waitedMillis(job.getCreateTime()));
        if (definition != null) {
            view.setDefinitionName(definition.getName());
            WfNode node = definition.node(job.getElementId());
            if (node != null) {
                view.setActivityName(node.getName());
                // 竞速分支带上网关 id：同一个网关下的若干条订阅会一起被唤醒/作废，
                // 运维看得到这个 id 才知道"这几条是同一次竞速"
                view.setGatewayId(gatewayOf(definition, node));
            }
        }
        return view;
    }

    /**
     * job 类型归并成"等什么"。
     *
     * <p>不把 {@code MESSAGE} / {@code EVENT_MESSAGE} 一起并成 {@code message} 就够了 ——
     * 还要能区分<b>打断</b>与<b>竞速</b>，那是两种处置方式，所以原类型另外用
     * {@code jobType} 带出。
     */
    private String waitingFor(WfJobType type) {
        if (type == null) {
            return null;
        }
        switch (type) {
            case TIMER:
                return "timer";
            case MESSAGE:
            case EVENT_MESSAGE:
                return "message";
            case SIGNAL:
            case EVENT_SIGNAL:
                return "signal";
            case EXTERNAL:
                return "external";
            case ASYNC_BEFORE:
            case ASYNC_AFTER:
                return "async";
            default:
                return type.name();
        }
    }

    /**
     * 捕获事件所属的事件网关 —— 与运行期 {@code WfRuntimeService#gatewayOf} 同一套反查。
     *
     * <p>刻意不抽成公共方法：一个在 core/service 一个在 core/service 的另一处，
     * 跨类抽工具类的收益抵不上"两边各自一行、逻辑一眼看得见"。
     * 代价是这两处若有一处改了另一处不会跟着改 —— 所以这里的注释指明了另一处。
     */
    private String gatewayOf(WfDefinition definition, WfNode node) {
        if (node == null || node.getType() != WfNodeType.INTERMEDIATE_CATCH_EVENT) {
            return null;
        }
        List<WfFlow> inFlows = definition.incomingFlows(node.getId());
        if (inFlows.size() != 1) {
            return null;
        }
        WfNode source = definition.node(inFlows.get(0).getSourceRef());
        return source != null && source.getType() == WfNodeType.EVENT_BASED_GATEWAY
                ? source.getId() : null;
    }

    private Long waitedMillis(Date since) {
        return since == null ? null : System.currentTimeMillis() - since.getTime();
    }

    // ==================== 内存过滤 ====================

    private boolean matches(WfSubscriptionQuery query, WfSubscriptionView view) {
        if (query.getDefinitionKey() != null && !query.getDefinitionKey().trim().isEmpty()
                && !query.getDefinitionKey().equals(view.getDefinitionKey())) {
            return false;
        }
        if (!query.getTypes().isEmpty()
                && (view.getJobType() == null
                || !query.getTypes().contains(WfJobType.valueOf(view.getJobType())))) {
            return false;
        }
        if (query.getEventName() != null && !query.getEventName().trim().isEmpty()
                && !query.getEventName().equals(view.getEventName())) {
            return false;
        }
        if (query.getActivityId() != null && !query.getActivityId().trim().isEmpty()
                && !query.getActivityId().equals(view.getActivityId())) {
            return false;
        }
        if (query.getDueBefore() != null) {
            if (view.getDuedate() == null
                    || !view.getDuedate().before(query.getDueBefore())) {
                return false;
            }
        }
        if (query.getWaitingLongerThanMillis() != null
                && (view.getWaitingMillis() == null
                || view.getWaitingMillis() < query.getWaitingLongerThanMillis())) {
            return false;
        }
        if (query.getLocked() != null
                && query.getLocked() != (view.getLockedBy() != null
                && !view.getLockedBy().trim().isEmpty())) {
            return false;
        }
        return true;
    }

    /**
     * 按流程定义 key 反查它当前在办的实例。
     *
     * <p>给"某个模板最近都卡在什么上"这类问题用：只给了定义 key、不知道实例 id 时，
     * 总不能把全表 job 捞出来再按 key 过滤。
     */
    public List<WfSubscriptionView> subscriptionsOfDefinition(String definitionKey) {
        if (definitionKey == null || definitionKey.trim().isEmpty()) {
            throw new WfEngineException("流程定义 key 不能为空");
        }
        List<WfSubscriptionView> views = new ArrayList<>();
        List<WfProcessInstance> instances = persistence.queryProcessInstances(
                new WfProcessInstanceQuery().setDefinitionKey(definitionKey)
                        .setUnfinishedOnly(true)
                        .setPageNum(1).setPageSize(MAX_SCAN));
        if (instances == null) {
            return views;
        }
        for (WfProcessInstance instance : instances) {
            views.addAll(subscriptionsOf(instance.getId()));
        }
        return views;
    }
}
