package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import com.zifang.z.wf.core.model.WfFilter;
import com.zifang.z.wf.core.model.WfFilterType;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.WfFilterQuery;
import com.zifang.z.wf.core.persistence.WfIncidentQuery;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.persistence.WfProcessInstanceQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.view.WfFilterResult;

/**
 * 保存筛选器 —— 把一组查询条件存起来，反复用、大家共用。
 *
 * <p><b>它解决的是"条件输错"这件事</b>：运营每天都要输一遍
 * 「我的长期待办」「超过三天没人动的单」「重试耗尽的故障」，
 * 输错一个字段就得到一份看起来正常、其实少了一大截的清单 ——
 * 而少掉的那部分永远不会有人来报。
 *
 * <p><b>为什么保存与执行走同一条绑定路径</b>：存的时候就把条件绑进一个
 * 真的查询对象里跑一遍，绑不动的当场报错。
 * 分成"存时校验一份、执行时解析另一份"的话，两边迟早漂移，
 * 而漂移的形态是「存得进去、跑的时候那一条悄悄不生效」——
 * 那是本仓反复出现过的失败形态（已登记的 job 列、两套持久化的语义差异都是）。
 *
 * <p><b>值一律按字符串严格解析</b>：{@code openOnly=yes} 不会被当成 true。
 * 宽松解析在这里的代价是"打错一个字条件就悄悄变了"，
 * 而筛选器是共享资源，悄悄变了之后没人知道。
 *
 * <p><b>刻意不支持的</b>：{@code pageNum} / {@code pageSize}。
 * 它们是"这一次取第几页"，存进去会让"翻页"变成"改筛选器"。
 *
 * @author zifang
 */
public class WfFilterService {

    // ==================== 合法条件名 ====================
    //
    // 这三张表是"哪些名字认识"的**唯一真源**：绑定逻辑按这里判断，
    // 报错信息也按这里列。两个地方各写一份的话，迟早漂移 ——
    // 而漂移的形态是"报错说这个条件合法、绑定却不认"，排查时会绕很远。

    private static final Set<String> TASK_KEYS = keys(
            "processInstanceId", "definitionId", "assignee", "owner", "completerId", "category",
            "candidateUsers", "candidateGroups", "status",
            "openOnly", "suspendedOnly", "candidateOrAssigned", "completedOnly", "unassignedOnly",
            "createTimeFrom", "createTimeTo",
            // 第 45 轮：优先级区间 / 办理时间 / 截止时间。
            // 键名与 WfTaskQuery 的 setter **一一同名** —— 筛选器是共享资源，
            // 同一个意思在两层用两个名字，迟早有人只改一处
            "minPriority", "maxPriority",
            "endTimeFrom", "endTimeTo",
            "dueDateFrom", "dueDateTo");

    private static final Set<String> INSTANCE_KEYS = keys(
            "definitionKey", "definitionVersion", "businessKey", "startUserId", "category",
            "status", "result",
            "finishedOnly", "unfinishedOnly", "startTimeFrom", "startTimeTo");

    private static final Set<String> INCIDENT_KEYS = keys(
            "processInstanceId", "definitionKey", "activityId", "types",
            "retriesExhausted", "failedBefore", "errorMessageContains");

    private static Set<String> keys(String... names) {
        // LinkedHashSet：报错里列合法值时按"声明顺序"输出，
        // 比 HashSet 的哈希序更适合当文档看
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(names)));
    }

    private final WfPersistence persistence;
    private final WfIncidentService incidentService;

    /**
     * 筛选器 id 的实例标签。
     *
     * <p>没有复用 {@code WfIdGenerator}：它是个公开 SPI，
     * 往里加 {@code nextFilterId()} 会让所有外部实现类编译不过。
     * 这一点上"不动别人的接口"比"id 风格统一"重要 ——
     * 所以这里照着 {@code DefaultWfIdGenerator} 的形态自己生成一份。
     */
    private final String instanceTag = Long.toHexString(System.currentTimeMillis())
            + Integer.toHexString((int) (System.nanoTime() & 0xFFFFFF));

    private final AtomicLong filterCounter = new AtomicLong();

    public WfFilterService(WfPersistence persistence, WfIncidentService incidentService) {
        this.persistence = persistence;
        this.incidentService = incidentService;
    }

    // ==================== 增删改查 ====================

    /**
     * 新建一个筛选器。
     *
     * <p>条件在存之前就会被绑进一个真的查询对象里跑一遍：
     * 存不进去的条件当场报错，不留给"某天有人跑它的时候炸"。
     */
    public WfFilter createFilter(String name, WfFilterType resourceType, String owner,
                                 Map<String, String> properties) {
        if (name == null || name.trim().isEmpty()) {
            throw new WfEngineException("筛选器名称不能为空 —— "
                    + "它是要出现在列表里给人挑的那个名字");
        }
        if (resourceType == null) {
            throw new WfEngineException("筛选器类型不能为空。合法值: " + WfFilterType.allCodes());
        }
        WfFilter filter = WfFilter.of("filter-" + instanceTag + "-"
                + String.format("%012d", filterCounter.incrementAndGet()),
                name.trim(), resourceType, trimToNull(owner));
        if (properties != null) {
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                filter.putProperty(entry.getKey(), entry.getValue());
            }
        }
        // 存进去的条件必须至少能绑上一次，见 validate()
        validate(filter);
        Date now = new Date();
        filter.setCreateTime(now);
        filter.setUpdateTime(now);
        persistence.saveFilter(filter);
        return filter;
    }

    /**
     * 改一个既有筛选器。
     *
     * <p><b>并发保护靠"调用方传进来的 revision"</b>：
     * 传进来的必须是你<b>读到</b>的那一份的版本号（也就是
     * {@link #getFilter} 返回的那个值）。服务层会再读一次做校验，
     * 两个版本对不上就报乐观锁冲突。
     *
     * <p>刻意<b>不</b>在这里悄悄把版本号覆盖成"库里现在这份"：
     * 那样写的话乐观锁永远不冲突 —— 两个人同时改，第二个人直接覆盖第一个，
     * 而第一个人会读成"我明明改了却没生效"。
     * 覆盖掉版本号是乐观锁最常见的失效方式，因为它看上去"更安全"（永远不报错）。
     */
    public WfFilter updateFilter(WfFilter filter) {
        if (filter == null || filter.getId() == null || filter.getId().trim().isEmpty()) {
            throw new WfEngineException("要改的筛选器必须有 id");
        }
        WfFilter existing = persistence.findFilter(filter.getId());
        if (existing == null) {
            throw new WfEngineException("筛选器不存在: " + filter.getId()
                    + "。先确认 id 没写错、或者它已经被别人删了");
        }
        // 版本号比对：**认两种写法** —— 调用方传"我读到的那个版本"，
        // 或者按本仓 saveTask/saveJob 的老习惯已经自己调过一次 nextRevision()。
        // 容忍后者是因为它是本仓既有的约定，让人踩一次的代价
        // （一次莫名其妙的乐观锁冲突，而报错说的是"冲突"、实际是"你调多了"）
        // 远大于容忍的成本。真正过期的版本（比库里小）仍然照拦。
        if (filter.getRevision() == existing.getRevision() + 1) {
            filter.setRevision(existing.getRevision());
        } else if (filter.getRevision() != existing.getRevision()) {
            throw new com.zifang.z.wf.core.persistence.WfOptimisticLockException(
                    "filter", filter.getId(), existing.getRevision());
        }
        if (filter.getName() == null || filter.getName().trim().isEmpty()) {
            throw new WfEngineException("筛选器名称不能为空");
        }
        // 类型不允许改：改了等于"这张筛选器原来查的东西没了"。
        // 想换类型就新建一张 —— 改类型会让所有引用它的地方（书签、定时导出）
        // 在下一次运行时悄悄换掉查的东西
        if (existing.getResourceType() != filter.getResourceType()) {
            throw new WfEngineException("筛选器类型不能改。原类型 "
                    + existing.getResourceType().getCode() + "，请求改为 "
                    + (filter.getResourceType() == null ? "null"
                    : filter.getResourceType().getCode())
                    + "。类型决定这张筛选器查什么，改了等于让所有引用它的地方"
                    + "在下次运行时悄悄换掉查的东西；请新建一张");
        }
        validate(filter);
        // 归一一次再自增：调用方可能已经自己调过 nextRevision()，
        // 那样 revision 会变成 expected+2，写进去就变成"跳了一个版本"，
        // 之后所有人的下一次改都会撞上莫名其妙的冲突
        filter.setRevision(existing.getRevision());
        filter.setCreateTime(existing.getCreateTime());
        filter.setUpdateTime(new Date());
        filter.nextRevision();
        persistence.saveFilter(filter);
        return filter;
    }

    /**
     * 删一个筛选器。
     *
     * <p>不存在就<b>报错</b>而不是静默返回：删东西的语义是"保证它没了"，
     * 而"因为 id 写错了所以什么都没删"会被读成"已经删过了" ——
     * 那个 id 上可能还挂着一张正被别人用的筛选器。
     */
    public void deleteFilter(String filterId) {
        if (filterId == null || filterId.trim().isEmpty()) {
            throw new WfEngineException("筛选器 id 不能为空");
        }
        if (!persistence.deleteFilter(filterId)) {
            throw new WfEngineException("筛选器不存在: " + filterId
                    + "。先确认 id 没写错、或者它已经被别人删了");
        }
    }

    public WfFilter getFilter(String filterId) {
        if (filterId == null || filterId.trim().isEmpty()) {
            throw new WfEngineException("筛选器 id 不能为空");
        }
        WfFilter filter = persistence.findFilter(filterId);
        if (filter == null) {
            throw new WfEngineException("筛选器不存在: " + filterId);
        }
        return filter;
    }

    public List<WfFilter> listFilters(WfFilterQuery query) {
        WfFilterQuery actual = query == null ? new WfFilterQuery() : query;
        return persistence.queryFilters(actual);
    }

    public int countFilters(WfFilterQuery query) {
        return persistence.countFilters(query == null ? new WfFilterQuery() : query);
    }

    // ==================== 执行 ====================

    /**
     * 跑一张筛选器，按它自己的类型返回对应的结果。
     *
     * @param pageNum  第几页，从 1 起
     * @param pageSize 每页条数，<b>不落进筛选器</b>：翻页是这一次的事
     */
    public WfFilterResult run(String filterId, int pageNum, int pageSize) {
        WfFilter filter = getFilter(filterId);
        WfFilterResult result = new WfFilterResult();
        result.setFilterId(filter.getId());
        result.setFilterName(filter.getName());
        result.setResourceType(filter.getResourceType().getCode());
        if (filter.getResourceType() == WfFilterType.TASK) {
            WfTaskQuery taskQuery = bindTask(new WfTaskQuery(), filter.getProperties());
            taskQuery.setPageNum(pageNum).setPageSize(pageSize);
            result.setRecords(persistence.queryTasks(taskQuery));
            result.setTotal((int) persistence.countTasks(taskQuery));
            return result;
        }
        if (filter.getResourceType() == WfFilterType.PROCESS_INSTANCE) {
            WfProcessInstanceQuery instanceQuery =
                    bindInstance(new WfProcessInstanceQuery(), filter.getProperties());
            instanceQuery.setPageNum(pageNum).setPageSize(pageSize);
            result.setRecords(persistence.queryProcessInstances(instanceQuery));
            result.setTotal((int) persistence.countProcessInstances(instanceQuery));
            return result;
        }
        WfIncidentQuery incidentQuery = bindIncident(new WfIncidentQuery(), filter.getProperties());
        incidentQuery.setPageNum(pageNum).setPageSize(pageSize);
        result.setRecords(incidentService.listIncidents(incidentQuery));
        result.setTotal(incidentService.countIncidents(incidentQuery));
        return result;
    }

    /** 绑成任务查询。给 {@link WfFilterType#TASK} 以外的筛选器用会报错。 */
    public WfTaskQuery taskQuery(String filterId) {
        return bindTask(new WfTaskQuery(), requireType(filterId, WfFilterType.TASK).getProperties());
    }

    /** 绑成流程实例查询。给 {@link WfFilterType#PROCESS_INSTANCE} 以外的筛选器用会报错。 */
    public WfProcessInstanceQuery processInstanceQuery(String filterId) {
        return bindInstance(new WfProcessInstanceQuery(),
                requireType(filterId, WfFilterType.PROCESS_INSTANCE).getProperties());
    }

    /** 绑成故障查询。给 {@link WfFilterType#INCIDENT} 以外的筛选器用会报错。 */
    public WfIncidentQuery incidentQuery(String filterId) {
        return bindIncident(new WfIncidentQuery(),
                requireType(filterId, WfFilterType.INCIDENT).getProperties());
    }

    /** 某个类型下合法的条件名，报错与文档都以它为准。 */
    public static Set<String> legalKeys(WfFilterType type) {
        if (type == WfFilterType.TASK) {
            return TASK_KEYS;
        }
        if (type == WfFilterType.PROCESS_INSTANCE) {
            return INSTANCE_KEYS;
        }
        return INCIDENT_KEYS;
    }

    private WfFilter requireType(String filterId, WfFilterType expected) {
        WfFilter filter = getFilter(filterId);
        if (filter.getResourceType() != expected) {
            throw new WfEngineException("筛选器 " + filter.getName() + "(" + filter.getId()
                    + ") 是 " + filter.getResourceType().getCode() + " 类型的，"
                    + "不能当 " + expected.getCode() + " 用。类型的条件名互不通用，"
                    + "套过去只会查出没人认得的清单");
        }
        return filter;
    }

    /**
     * 往一个临时筛选器上跑一次绑定 —— 存之前的体检。
     *
     * <p>复用 {@link #validate(WfFilter)} 而不是另写一份校验：
     * 两条路一旦分开，"存时合法、跑时非法"这种组合迟早出现。
     */
    private void validate(WfFilter filter) {
        if (filter.getResourceType() == WfFilterType.TASK) {
            bindTask(new WfTaskQuery(), filter.getProperties());
        } else if (filter.getResourceType() == WfFilterType.PROCESS_INSTANCE) {
            bindInstance(new WfProcessInstanceQuery(), filter.getProperties());
        } else {
            bindIncident(new WfIncidentQuery(), filter.getProperties());
        }
    }

    // ==================== 绑定 ====================

    private WfTaskQuery bindTask(WfTaskQuery query, Map<String, String> properties) {
        for (Map.Entry<String, String> entry : sorted(properties)) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!TASK_KEYS.contains(key)) {
                throw unknownKey(WfFilterType.TASK, key);
            }
            if ("processInstanceId".equals(key)) {
                query.setProcessInstanceId(value);
            } else if ("definitionId".equals(key)) {
                query.setDefinitionId(value);
            } else if ("assignee".equals(key)) {
                query.setAssignee(value);
            } else if ("owner".equals(key)) {
                query.setOwner(value);
            } else if ("completerId".equals(key)) {
                query.setCompleterId(value);
            } else if ("category".equals(key)) {
                query.setCategory(value);
            } else if ("candidateUsers".equals(key)) {
                query.setCandidateUsers(csv(value));
            } else if ("candidateGroups".equals(key)) {
                query.setCandidateGroups(csv(value));
            } else if ("status".equals(key)) {
                query.setStatus(enumValue(WfTask.Status.class, key, value, taskStatuses()));
            } else if ("openOnly".equals(key)) {
                query.setOpenOnly(bool(key, value));
            } else if ("suspendedOnly".equals(key)) {
                query.setSuspendedOnly(nullableBool(key, value));
            } else if ("candidateOrAssigned".equals(key)) {
                query.setCandidateOrAssigned(bool(key, value));
            } else if ("completedOnly".equals(key)) {
                query.setCompletedOnly(bool(key, value));
            } else if ("unassignedOnly".equals(key)) {
                query.setUnassignedOnly(bool(key, value));
            } else if ("createTimeFrom".equals(key)) {
                query.setCreateTimeFrom(millis(key, value));
            } else if ("createTimeTo".equals(key)) {
                query.setCreateTimeTo(millis(key, value));
            } else if ("minPriority".equals(key)) {
                query.setMinPriority(priority(key, value));
            } else if ("maxPriority".equals(key)) {
                query.setMaxPriority(priority(key, value));
            } else if ("endTimeFrom".equals(key)) {
                query.setEndTimeFrom(millis(key, value));
            } else if ("endTimeTo".equals(key)) {
                query.setEndTimeTo(millis(key, value));
            } else if ("dueDateFrom".equals(key)) {
                query.setDueDateFrom(millis(key, value));
            } else if ("dueDateTo".equals(key)) {
                query.setDueDateTo(millis(key, value));
            } else {
                throw unhandled(WfFilterType.TASK, key);
            }
        }
        return query;
    }

    private WfProcessInstanceQuery bindInstance(WfProcessInstanceQuery query,
                                                Map<String, String> properties) {
        for (Map.Entry<String, String> entry : sorted(properties)) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!INSTANCE_KEYS.contains(key)) {
                throw unknownKey(WfFilterType.PROCESS_INSTANCE, key);
            }
            if ("definitionKey".equals(key)) {
                query.setDefinitionKey(value);
            } else if ("definitionVersion".equals(key)) {
                query.setDefinitionVersion(integer(key, value));
            } else if ("businessKey".equals(key)) {
                query.setBusinessKey(value);
            } else if ("startUserId".equals(key)) {
                query.setStartUserId(value);
            } else if ("category".equals(key)) {
                query.setCategory(value);
            } else if ("status".equals(key)) {
                query.setStatus(enumValue(WfProcessStatus.class, key, value, instanceStatuses()));
            } else if ("result".equals(key)) {
                query.setResult(value);
            } else if ("finishedOnly".equals(key)) {
                query.setFinishedOnly(bool(key, value));
            } else if ("unfinishedOnly".equals(key)) {
                query.setUnfinishedOnly(bool(key, value));
            } else if ("startTimeFrom".equals(key)) {
                query.setStartTimeFrom(millis(key, value));
            } else if ("startTimeTo".equals(key)) {
                query.setStartTimeTo(millis(key, value));
            } else {
                throw unhandled(WfFilterType.PROCESS_INSTANCE, key);
            }
        }
        return query;
    }

    private WfIncidentQuery bindIncident(WfIncidentQuery query, Map<String, String> properties) {
        for (Map.Entry<String, String> entry : sorted(properties)) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!INCIDENT_KEYS.contains(key)) {
                throw unknownKey(WfFilterType.INCIDENT, key);
            }
            if ("processInstanceId".equals(key)) {
                query.setProcessInstanceId(value);
            } else if ("definitionKey".equals(key)) {
                query.setDefinitionKey(value);
            } else if ("activityId".equals(key)) {
                query.setActivityId(value);
            } else if ("types".equals(key)) {
                for (String each : csv(value)) {
                    query.addType(enumValue(WfJobType.class, key, each, jobTypes()));
                }
            } else if ("retriesExhausted".equals(key)) {
                query.setRetriesExhausted(nullableBool(key, value));
            } else if ("failedBefore".equals(key)) {
                query.setFailedBefore(millis(key, value));
            } else if ("errorMessageContains".equals(key)) {
                query.setErrorMessageContains(value);
            } else {
                throw unhandled(WfFilterType.INCIDENT, key);
            }
        }
        return query;
    }

    // ==================== 值解析（全部严格） ====================

    private boolean bool(String key, String value) {
        if (value == null) {
            throw badValue(key, value, "true 或 false");
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        // 刻意不认 1/0、yes/no、Y/N：这些在不同人手里意思不同，
        // 而筛选器是共享资源，写错一个字母的条件悄悄变成 false 比报错坏得多
        throw badValue(key, value, "true 或 false（不认 1/0、yes/no）");
    }

    private Boolean nullableBool(String key, String value) {
        // 三态：null = 不限。空串当成"不限"而不是报错，
        // 因为前端表单里"不限"那个下拉框传的就是空串
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return Boolean.valueOf(bool(key, value.trim()));
    }

    private Integer integer(String key, String value) {
        try {
            return Integer.valueOf(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            throw badValue(key, value, "一个整数");
        }
    }

    /**
     * 优先级解析。
     *
     * <p><b>刻意不接受区间字面量</b>（{@code "50-90"} 那种）：
     * 那是给「下限」「上限」两个独立键起名字时省事的写法，
     * 但解析出来的区间在出错时只能报一句"格式不对"，
     * 调用方不知道是下限写错了还是上限写错了。
     */
    private Integer priority(String key, String value) {
        try {
            return Integer.valueOf(Integer.parseInt(value == null ? "" : value.trim()));
        } catch (NumberFormatException e) {
            throw badValue(key, value, "一个整数（优先级，闭区间；不认 50-90 这种写法）");
        }
    }

    private Date millis(String key, String value) {
        try {
            return new Date(Long.parseLong(value == null ? "" : value.trim()));
        } catch (NumberFormatException e) {
            throw badValue(key, value,
                    "毫秒时间戳（不是日期字符串）—— 存字符串的日期在不同库/不同机器上"
                            + "会按本地时区解释，同一张筛选器换个环境就查出一批不同的单");
        }
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String key, String value, List<String> legal) {
        if (value == null || value.trim().isEmpty()) {
            throw badValue(key, value, legal.toString());
        }
        String text = value.trim();
        for (String each : legal) {
            if (each.equalsIgnoreCase(text)) {
                return Enum.valueOf(type, each);
            }
        }
        throw badValue(key, value, legal.toString() + "（大小写不敏感）");
    }

    private List<String> taskStatuses() {
        List<String> names = new ArrayList<String>();
        for (WfTask.Status each : WfTask.Status.values()) {
            names.add(each.name());
        }
        return names;
    }

    private List<String> instanceStatuses() {
        List<String> names = new ArrayList<String>();
        for (WfProcessStatus each : WfProcessStatus.values()) {
            names.add(each.name());
        }
        return names;
    }

    private List<String> jobTypes() {
        List<String> names = new ArrayList<String>();
        for (WfJobType each : WfJobType.values()) {
            names.add(each.name());
        }
        return names;
    }

    // ==================== 报错 ====================

    private WfEngineException unknownKey(WfFilterType type, String key) {
        return new WfEngineException("筛选器类型 [" + type.getCode() + "] 没有条件 [" + key
                + "]。该类型支持的条件: " + legalKeys(type)
                + "。写错的条件名如果被当成没填，筛出来的会是一份看起来正常的全量清单"
                + "—— 那比报错难查得多");
    }

    /**
     * 条件在合法名列表里、但绑定分支没覆盖到。
     *
     * <p>它只应该由"改了一张合法名表却忘了加绑定"造成，
     * 所以报的是内部错误而不是用户错误。
     */
    private WfEngineException unhandled(WfFilterType type, String key) {
        return new WfEngineException("条件 [" + key + "] 在 " + type.getCode()
                + " 的合法名列表里，但绑定逻辑没有覆盖它。这是引擎自身的缺陷，"
                + "请把这条筛选器的条件一并报上来");
    }

    private WfEngineException badValue(String key, String value, String expected) {
        return new WfEngineException("条件 [" + key + "] 的值 [" + value
                + "] 不合法。期望: " + expected
                + "。解析不了的值会被当成没填，筛出来的就是一份看起来正常的全量清单");
    }

    // ==================== 工具 ====================

    private List<Map.Entry<String, String>> sorted(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return Collections.emptyList();
        }
        // 按名字排序再逐条绑：报错永远是"第一条有问题的条件"，
        // 而不跟着 Map 的哈希序乱跳 —— 否则同一份配置两次运行报出不同的条件
        return new ArrayList<Map.Entry<String, String>>(new TreeMap<String, String>(properties)
                .entrySet());
    }

    private List<String> csv(String value) {
        List<String> list = new ArrayList<String>();
        if (value == null) {
            return list;
        }
        for (String each : value.split(",")) {
            String text = each.trim();
            if (!text.isEmpty()) {
                list.add(text);
            }
        }
        return list;
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
