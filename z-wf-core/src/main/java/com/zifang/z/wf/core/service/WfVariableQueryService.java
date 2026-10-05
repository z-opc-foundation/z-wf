package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.engine.WfMultiInstance;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.persistence.WfVariableInstanceQuery;
import com.zifang.z.wf.core.view.WfVariableInstanceView;

/**
 * 变量实例查询 —— 「某个变量挂在哪一级作用域上、值是多少」。
 *
 * <p><b>它为什么必须存在</b>：本仓的变量散在三个模型各自的 Map 里
 * （流程实例 / token / 任务），此前<b>没有任何接口能按"级别"去看它们</b>。
 * 排障时问的恰恰是级别：「这个 amount 到底挂在哪？为什么这条分支读到的是它？」
 * 只有一个 {@code getVariables(processInstanceId)} 的话，
 * 看到的只是流程级那一层，而分支级与任务级的值根本不在里面。
 *
 * <p><b>它是纯读的</b>：不改变量、不推进流程。
 *
 * <p><b>为什么在内存里过滤</b>：本仓的变量没有独立的行，能下推成 SQL 的
 * 只有"按实例取流程变量 / 按 token 取分支变量 / 按任务取任务变量"这几种。
 * 名字模糊、值等值、作用域这些条件都只能拿回来之后判。
 * 而审批系统里在办的单数与单上的变量数都是几十上百的量级，够用。
 *
 * <p>代价要说清楚：{@code processInstanceId} 之外的过滤都在服务端做，
 * 所以分页切在<b>过滤后</b>的结果上而不是数据库上 ——
 * 条件一变，页码的含义就跟着变。
 *
 * @author zifang
 */
public class WfVariableQueryService {

    private static final Logger log = LoggerFactory.getLogger(WfVariableQueryService.class);

    /**
     * 一次最多扫多少行任务回来。
     *
     * <p>比订阅 / 故障的宽松：变量的行数是<b>随流程走完而累积</b>的
     * （每个走过的节点都留一条任务，各带一个 Map），
     * 跑了一年的实例攒下几百行任务很常见。超量时该报错而不是给一份残缺的清单 ——
     * 而变量清单被截断的后果比订阅更隐蔽：少掉的那个变量可能正是条件表达式在用的那个。
     */
    public static final int MAX_SCAN = 5000;

    private final WfPersistence persistence;
    private final WfRepositoryService repositoryService;

    public WfVariableQueryService(WfPersistence persistence, WfRepositoryService repositoryService) {
        this.persistence = persistence;
        this.repositoryService = repositoryService;
    }

    /**
     * 查变量实例列表。
     *
     * @return 当前页；没有匹配时是<b>空列表</b>而不是 null
     * @throws WfEngineException 一个范围都没给（见 {@code scan}），
     *         或扫描行数超过 {@link #MAX_SCAN} —— 静默截断一份"看起来完整"的
     *         变量清单比报错危险得多
     */
    public List<WfVariableInstanceView> listVariables(WfVariableInstanceQuery query) {
        WfVariableInstanceQuery actual = query == null ? new WfVariableInstanceQuery() : query;
        List<WfVariableInstanceView> matched = matchedAll(actual);
        int from = (actual.normalizedPageNum() - 1) * actual.normalizedPageSize();
        if (from >= matched.size()) {
            return new ArrayList<WfVariableInstanceView>();
        }
        int to = Math.min(from + actual.normalizedPageSize(), matched.size());
        return new ArrayList<WfVariableInstanceView>(matched.subList(from, to));
    }

    /** 匹配总数（不分页）。与 {@link #listVariables} 走同一套扫描与判定。 */
    public int countVariables(WfVariableInstanceQuery query) {
        return matchedAll(query == null ? new WfVariableInstanceQuery() : query).size();
    }

    /**
     * 某个流程实例<b>当前</b>的变量（三级都算，任务级只看未办结的）。
     *
     * <p>刻意<b>不分页</b>：一个实例的变量数由它的定义与调用方写入量决定，
     * 没有一个"合理上限"可以拿来替调用方做决定。走分页就等于给一个
     * 静默的上限（{@code pageSize} 会被归一到 1000），
     * 于是调用方拿到一份"看起来完整"的清单，而缺的那几条里
     * 可能就有条件表达式正在读的那个变量。
     */
    public List<WfVariableInstanceView> variablesOf(String processInstanceId) {
        return matchedAll(new WfVariableInstanceQuery().setProcessInstanceId(processInstanceId));
    }

    // ==================== 扫描 ====================

    private List<WfVariableInstanceView> matchedAll(WfVariableInstanceQuery query) {
        List<WfVariableInstanceView> matched = new ArrayList<WfVariableInstanceView>();
        for (WfVariableInstanceView view : scan(query)) {
            if (matches(query, view)) {
                matched.add(view);
            }
        }
        return matched;
    }

    private List<WfVariableInstanceView> scan(WfVariableInstanceQuery query) {
        List<WfVariableInstanceView> views = new ArrayList<WfVariableInstanceView>();
        String taskId = trimmedOrNull(query.getTaskId());
        String executionId = trimmedOrNull(query.getExecutionId());
        String pid = trimmedOrNull(query.getProcessInstanceId());

        // 先把流程实例 id 落实出来。只给了 executionId 时必须用它反查：
        // 否则下面按实例捞任务就没有任何下推条件，会退化成**全表**扫任务 ——
        // 在跑得多的系统上撞 MAX_SCAN，报一个跟调用方问的问题毫无关系的错
        WfExecution targetExecution = executionId == null ? null : persistence.findExecution(executionId);
        if (pid == null && targetExecution != null) {
            pid = trimmedOrNull(targetExecution.getProcessInstanceId());
        }

        if (executionId != null && targetExecution == null) {
            // 点名的 token 不存在。返回空而不是把整张表扫一遍：
            // 扫出来的每一条都会在 matches() 里被 executionId 滤掉，纯属白跑
            log.warn("变量查询：executionId={} 在存储中不存在，返回空结果", executionId);
            return views;
        }
        if (pid == null && taskId == null) {
            // 一个范围都不给就意味着"列出全系统所有变量实例"。
            // 本仓做不到这个语义（变量没有独立的行，全系统的变量等于把所有实例、
            // 所有 token、所有历史任务一次取回来），而<b>只返回其中一部分</b>
            // 比报错坏得多：调用方会读成"全系统就这几个变量"
            throw new WfEngineException("变量查询至少要给一个范围：processInstanceId、taskId 或 executionId。"
                    + "本仓的变量没有独立的存储行，'全系统所有变量实例'只能靠把所有实例与历史任务"
                    + "全量取回来再过滤，代价与结果的完整性都不可控，所以这里直接拒绝而不是给一份残缺清单");
        }

        // ---- 1. 任务级 ----
        if (taskId != null) {
            WfTask task = persistence.findTask(taskId);
            if (task == null) {
                log.warn("变量查询：taskId={} 在存储中不存在，返回空结果", taskId);
            } else {
                // 点名的任务不过滤办结态，理由见 WfVariableInstanceQuery#openTasksOnly
                addTaskVariables(views, task, query, true);
            }
        } else {
            // 多读一条：读满 MAX_SCAN 恰好等于上限时无法区分"刚好这么多"与"还有更多"
            WfTaskQuery taskQuery = new WfTaskQuery().setPageNum(1)
                    .setPageSize(MAX_SCAN + 1).setProcessInstanceId(pid);
            if (Boolean.TRUE.equals(query.getOpenTasksOnly())) {
                taskQuery.setOpenOnly(true);
            }
            List<WfTask> tasks = persistence.queryTasks(taskQuery);
            // 判溢出用**读到的原始行数**：下面会丢掉"没有变量的任务"与"被 openTasksOnly
            // 滤掉的已办结任务"，拿过滤后的数去判，恰好赶上任务大多已办结时会误以为没超量
            if (tasks != null && tasks.size() > MAX_SCAN) {
                throw new WfEngineException("该范围内的任务超过 " + MAX_SCAN
                        + " 条，变量清单已被截断。请用 taskId 精确到某张任务，"
                        + "或把 openTasksOnly 设为 false 并分批查 —— "
                        + "跑久了的实例会攒下走过的每一个节点");
            }
            for (WfTask task : nullSafe(tasks)) {
                addTaskVariables(views, task, query, false);
            }
        }

        // ---- 2. 流程级 ----
        if (pid != null) {
            WfProcessInstance instance = persistence.findProcessInstance(pid);
            if (instance == null) {
                // 任务/token 还在而实例没了：变量无从归属（流程级那一层没有容器了），
                // 但必须报出来 —— 它是清理漏了一步的证据，藏起来就永远没人发现
                log.warn("变量查询：流程实例 {} 在存储中不存在，流程级变量无法列出", pid);
            } else {
                addProcessVariables(views, instance, query);
            }
        }

        // ---- 3. 分支级 ----
        if (targetExecution != null) {
            addExecutionVariables(views, targetExecution, query);
        } else if (pid != null) {
            List<WfExecution> executions = persistence.findExecutionsByProcessInstance(pid);
            for (WfExecution execution : nullSafe(executions)) {
                addExecutionVariables(views, execution, query);
            }
        }
        return views;
    }

    private void addProcessVariables(List<WfVariableInstanceView> views,
                                     WfProcessInstance instance, WfVariableInstanceQuery query) {
        for (Map.Entry<String, Object> entry : instance.getVariables().entrySet()) {
            if (internal(entry.getKey(), query)) {
                continue;
            }
            WfVariableInstanceView view = view(entry.getKey(), entry.getValue());
            view.setProcessInstanceId(instance.getId());
            views.add(base(view, WfVariableInstanceView.SCOPE_PROCESS, instance.getId()));
        }
    }

    private void addExecutionVariables(List<WfVariableInstanceView> views,
                                       WfExecution execution, WfVariableInstanceQuery query) {
        // 已结束的分支：变量随分支一起失效了，列出来是"曾经存在过"而不是"现在有"。
        // 终态实例上的 token 全是这种，所以这一条同时兜住了"流程跑完了还列出一堆分支变量"
        if (execution.isEnded()) {
            return;
        }
        for (Map.Entry<String, Object> entry : execution.getVariables().entrySet()) {
            if (internal(entry.getKey(), query)) {
                continue;
            }
            WfVariableInstanceView view = view(entry.getKey(), entry.getValue());
            view.setProcessInstanceId(execution.getProcessInstanceId());
            view.setExecutionId(execution.getId());
            view.setActivityId(execution.getActivityId());
            views.add(base(view, WfVariableInstanceView.SCOPE_EXECUTION, execution.getId()));
        }
    }

    /**
     * @param ignoreOpenFilter 显式点名任务时为 {@code true}（见
     *        {@code WfVariableInstanceQuery#openTasksOnly} 里为什么这条要例外）
     */
    private void addTaskVariables(List<WfVariableInstanceView> views, WfTask task,
                                  WfVariableInstanceQuery query, boolean ignoreOpenFilter) {
        boolean closed = !task.isOpen();
        if (closed && !ignoreOpenFilter && Boolean.TRUE.equals(query.getOpenTasksOnly())) {
            return;
        }
        if (task.getVariables() == null || task.getVariables().isEmpty()) {
            return;
        }
        // 节点名整条任务只查一次：同一条任务的变量都在同一个节点上，
        // 逐条查会把定义读取放大到"变量条数"倍
        String activityName = activityNameOf(task);
        for (Map.Entry<String, Object> entry : task.getVariables().entrySet()) {
            if (internal(entry.getKey(), query)) {
                continue;
            }
            WfVariableInstanceView view = view(entry.getKey(), entry.getValue());
            view.setProcessInstanceId(task.getProcessInstanceId());
            view.setExecutionId(task.getExecutionId());
            view.setTaskId(task.getId());
            view.setActivityId(task.getDefinitionId());
            view.setActivityName(activityName);
            view.setOnClosedTask(closed);
            view.setTaskEndTime(task.getEndTime());
            views.add(base(view, WfVariableInstanceView.SCOPE_TASK, task.getId()));
        }
    }

    /**
     * 任务所在节点的显示名。
     *
     * <p>刻意用<b>实例的 key + version</b>去拿定义，而不是最新版本：
     * 定义可能已被换成新版本，按实例那一版拿到的才是它当时跑的那份，
     * 而"这个变量属于当时图上的哪个节点"正是排障要的答案。
     * 拿不到就返回 {@code null}（不抛）—— 变量本身还在，
     * 缺一个显示名不该让整份清单查不出来；但这条 warn 会把"定义已丢"这件事留下来。
     */
    private String activityNameOf(WfTask task) {
        WfProcessInstance instance = persistence.findProcessInstance(task.getProcessInstanceId());
        if (instance == null) {
            return null;
        }
        WfDefinition definition = repositoryService.getDefinitionOrLatest(
                instance.getDefinitionKey(), instance.getDefinitionVersion());
        WfNode node = definition == null ? null : definition.node(task.getDefinitionId());
        return node == null ? null : node.getName();
    }

    private WfVariableInstanceView view(String name, Object value) {
        WfVariableInstanceView view = new WfVariableInstanceView();
        view.setName(name);
        view.setValue(value);
        view.setType(value == null ? null : value.getClass().getSimpleName());
        return view;
    }

    /**
     * 贴上作用域并生成临时 id。
     *
     * <p><b>owner 必须由调用方传进来</b>，不能回头去读视图上的字段：
     * 视图上的 {@code processInstanceId} / {@code executionId} / {@code taskId}
     * 是逐个 setter 填的，一旦 {@code base()} 在 setter 之前跑，
     * id 就会静默变成 {@code process:null/amount} 这种
     * —— 而它看上去仍然像模像样（三段结构齐全、彼此可区分），
     * 正是最难被发现的形态。所以归属在这里显式传，不依赖赋值顺序。
     */
    private WfVariableInstanceView base(WfVariableInstanceView view, String scope, String owner) {
        view.setScope(scope);
        // id 是「作用域:归属 + 变量名」，只在本次查询期间有效（见视图类注释）
        view.setId(scope + ":" + owner + "/" + view.getName());
        return view;
    }

    /** 引擎内部变量默认不列；同名但非内部的照列（那是业务自己起的名）。 */
    private boolean internal(String name, WfVariableInstanceQuery query) {
        if (Boolean.TRUE.equals(query.getIncludeEngineInternal())) {
            return false;
        }
        return WfMultiInstance.LOOP_COUNTER.equals(name)
                || WfMultiInstance.LOOP_ASSIGNEE.equals(name);
    }

    private boolean matches(WfVariableInstanceQuery query, WfVariableInstanceView view) {
        String pid = trimmedOrNull(query.getProcessInstanceId());
        if (pid != null && !pid.equals(view.getProcessInstanceId())) {
            return false;
        }
        String executionId = trimmedOrNull(query.getExecutionId());
        if (executionId != null && !executionId.equals(view.getExecutionId())) {
            return false;
        }
        // taskId 是"精确到这张任务上的变量"而不是范围：
        // 给了它就只该看到这条任务上的东西，流程级与分支级的变量挂不到任务上
        String taskId = trimmedOrNull(query.getTaskId());
        if (taskId != null && !taskId.equals(view.getTaskId())) {
            return false;
        }
        if (!query.getScopes().isEmpty() && !query.getScopes().contains(view.getScope())) {
            return false;
        }
        String name = trimmedOrNull(query.getName());
        if (name != null && !name.equals(view.getName())) {
            return false;
        }
        String like = trimmedOrNull(query.getNameLike());
        if (like != null && (view.getName() == null
                || !view.getName().toLowerCase().contains(like.toLowerCase()))) {
            return false;
        }
        String value = query.getValueEquals();
        if (value != null && (view.getValue() == null
                || !value.equals(String.valueOf(view.getValue())))) {
            return false;
        }
        return true;
    }

    private <T> List<T> nullSafe(List<T> list) {
        return list == null ? new ArrayList<T>() : list;
    }

    private String trimmedOrNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
