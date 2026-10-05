package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.engine.WfIdGenerator;
import com.zifang.z.wf.core.engine.WfMultiInstance;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.persistence.WfVariableInstanceQuery;
import com.zifang.z.wf.core.view.WfVariableInstanceView;

/**
 * 变量实例查询 —— 「某个变量挂在哪一级作用域上、值是多少」。
 *
 * <p>此前 {@code getVariables(processInstanceId)} 只能看到流程级那一层，
 * 分支级与任务级的值根本不在里面，于是"这个 amount 到底挂在哪"无法回答。
 * 本类盯的每一件都是<b>答错比答不出更糟</b>的那些：
 * <ol>
 *   <li><b>三级作用域都要能查到，且能一眼分清</b>：同一个变量名挂在三级上是
 *       三个不同的实例，id 必须不同 —— 否则调用方没法分别引用它们。</li>
 *   <li><b>{@code openTasksOnly} 默认 {@code true}</b>：任务变量在任务办结后
 *       仍然存在，把它算进"当前变量"会得到一份混着历史表单变量的清单。
 *       但<b>显式点名 taskId 时不过滤</b> —— 那时候返回空列表分不清是
 *       "没有变量"还是"被过滤了"，而排障查的恰恰多是已办结的任务。</li>
 *   <li><b>引擎内部变量默认不列</b>：{@code loopCounter} 混进来会让
 *       "这单有 5 个变量"变成 8 个，而其中 3 个没人认得。</li>
 *   <li><b>已结束的分支不列</b>：变量随分支一起失效了，列出来是"曾经存在过"。</li>
 *   <li><b>一个范围都不给要报错</b>：本仓做不到"全系统所有变量实例"，
 *       只返回其中一部分比报错坏得多。</li>
 *   <li><b>超量要报错而不是静默截断</b>：少掉的那个变量可能正是条件表达式在用的那个。</li>
 *   <li><b>count 与 list 走同一套判定</b>，对不上时调用方只会以为自己算错了。</li>
 * </ol>
 */
class WfVariableInstanceQueryServiceTest {

    /**
     * 两条并行分支。
     *
     * <p>并行是关键：单分支的流程只有一个 token，"分支级"这一层会退化成流程级，
     * 三级作用域的差别就测不出来了。
     */
    private static final String PARALLEL_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"viProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <parallelGateway id=\"pg\"/>\n"
            + "    <userTask id=\"branchA\" name=\"甲支线\" zifang:assignee=\"alice\"/>\n"
            + "    <userTask id=\"branchB\" name=\"乙支线\" zifang:assignee=\"bob\"/>\n"
            + "    <endEvent id=\"eA\"/>\n"
            + "    <endEvent id=\"eB\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"pg\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"pg\" targetRef=\"branchA\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"pg\" targetRef=\"branchB\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"branchA\" targetRef=\"eA\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"branchB\" targetRef=\"eB\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 三人会签：用来造出 {@code loopCounter} 这类引擎内部变量。 */
    private static final String COUNTERSIGN_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"viCounterProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"counterSign\" name=\"三人会签\" zifang:assignee=\"ops\">\n"
            + "      <multiInstanceLoopCharacteristics>\n"
            + "        <loopCardinality>3</loopCardinality>\n"
            + "      </multiInstanceLoopCharacteristics>\n"
            + "    </userTask>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"counterSign\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"counterSign\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InflatableTaskPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfVariableService variables;
    private WfVariableQueryService queryService;

    @BeforeEach
    void setUp() {
        repo = new InflatableTaskPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
        variables = new WfVariableService(repo, new WfIdGenerator.DefaultWfIdGenerator());
        queryService = new WfVariableQueryService(repo, repository);
    }

    private String start() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(PARALLEL_BPMN));
        return runtime.startProcessInstance(definition, "VI-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
    }

    private String startCountersign() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(COUNTERSIGN_BPMN));
        return runtime.startProcessInstance(definition, "VIC-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
    }

    private WfTask openTask(String pid, String assignee) {
        for (WfTask task : repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(20))) {
            if (assignee.equals(task.getAssignee())) {
                return task;
            }
        }
        return null;
    }

    /** 停在某个节点上的 token —— 分支级变量挂在它身上。 */
    private WfExecution tokenAt(String pid, String activityId) {
        for (WfExecution execution : repo.findExecutionsByProcessInstance(pid)) {
            if (!execution.isEnded() && activityId.equals(execution.getActivityId())) {
                return execution;
            }
        }
        return null;
    }

    private WfVariableInstanceView named(List<WfVariableInstanceView> views, String name, String scope) {
        for (WfVariableInstanceView view : views) {
            if (name.equals(view.getName()) && scope.equals(view.getScope())) {
                return view;
            }
        }
        return null;
    }

    private List<WfVariableInstanceView> query(String pid) {
        return queryService.listVariables(new WfVariableInstanceQuery()
                .setProcessInstanceId(pid).setPageSize(1000));
    }

    private Set<String> idsOf(List<WfVariableInstanceView> views) {
        Set<String> ids = new HashSet<String>();
        for (WfVariableInstanceView view : views) {
            ids.add(view.getId());
        }
        return ids;
    }

    /** 把一个实例的三级作用域都写上<b>同名</b>的 amount —— 同名是最容易串层的形态。 */
    private String startWithAllThreeScopes() {
        String pid = start();
        variables.setVariable(pid, "amount", 20000, "ops");
        WfExecution tokenA = tokenAt(pid, "branchA");
        variables.setVariableLocal(tokenA.getId(), "amount", 500, "ops");
        variables.setTaskVariable(openTask(pid, "alice").getId(), "amount", "T-1", "ops");
        return pid;
    }

    // ==================== 三级作用域 ====================

    @Test
    @DisplayName("三级作用域都能查到，且同名变量的 id 互不相同")
    void threeScopesAreDistinguishable() {
        String pid = startWithAllThreeScopes();

        List<WfVariableInstanceView> views = query(pid);
        assertEquals(3, views.size(), "应当恰好三条。实际: " + views);

        WfVariableInstanceView processView = named(views, "amount", WfVariableInstanceView.SCOPE_PROCESS);
        WfVariableInstanceView executionView = named(views, "amount", WfVariableInstanceView.SCOPE_EXECUTION);
        WfVariableInstanceView taskView = named(views, "amount", WfVariableInstanceView.SCOPE_TASK);
        assertNotNull(processView, "流程级那条没查到: " + views);
        assertNotNull(executionView, "分支级那条没查到: " + views);
        assertNotNull(taskView, "任务级那条没查到: " + views);

        assertEquals(20000, processView.getValue());
        assertEquals(500, executionView.getValue());
        assertEquals("T-1", taskView.getValue());

        assertEquals(pid, processView.getProcessInstanceId());
        assertNull(processView.getExecutionId(), "流程级不属于任何一条 token");
        assertNull(processView.getTaskId());
        assertNotNull(executionView.getExecutionId(), "分支级必须带出它挂在哪条 token 上");
        assertNull(executionView.getTaskId(), "分支级不属于任何一张任务");
        assertNotNull(taskView.getTaskId());
        // 任务级也带 executionId：任务是从某条 token 上长出来的，缺了它
        // 「按 token 查」会把同一层的任务变量整层漏掉，而那份清单看起来还很干净
        assertEquals(executionView.getExecutionId(), taskView.getExecutionId(),
                "同一个 token 上的分支级与任务级变量应当指回同一条 token");

        // 断言**精确的 id 字符串**，不能只断言"三者互不相同"：
        // 曾经 base() 在归属字段被 setter 填上之前就跑，id 全成了
        // process:null/amount 这种，而它照样三段齐全、照样互不相同 ——
        // 只测互不相同的判据对这一类缺陷完全没有区分力
        assertEquals("process:" + pid + "/amount", processView.getId());
        assertEquals("execution:" + executionView.getExecutionId() + "/amount", executionView.getId());
        assertEquals("task:" + taskView.getTaskId() + "/amount", taskView.getId());
        assertEquals(3, idsOf(views).size(),
                "同名不同作用域的 id 必须各不相同（" + views + "），否则调用方没法分别引用它们");
    }

    @Test
    @DisplayName("任务级变量带出节点显示名与值类型")
    void taskVariableCarriesActivityName() {
        String pid = start();
        variables.setTaskVariable(openTask(pid, "alice").getId(), "days", 3, "ops");

        WfVariableInstanceView view = named(query(pid), "days", WfVariableInstanceView.SCOPE_TASK);
        assertNotNull(view, "任务级变量没查到: " + query(pid));
        assertEquals("甲支线", view.getActivityName(),
                "「这个变量属于当时图上的哪个节点」正是排障要的答案");
        assertEquals("Integer", view.getType(), "类型由运行时对象反推，供调用方判断能不能当数字用");
        assertFalse(view.isOnClosedTask(), "任务还没办结");
        assertNull(view.getTaskEndTime());
    }

    // ==================== openTasksOnly 的默认值与例外 ====================

    @Test
    @DisplayName("openTasksOnly 默认 true：已办结任务的变量不进「当前变量」")
    void closedTaskVariablesAreHiddenByDefault() {
        String pid = start();
        WfTask taskA = openTask(pid, "alice");
        variables.setTaskVariable(taskA.getId(), "days", 3, "ops");
        runtime.completeTask(taskA.getId(), "alice", "办完", null);

        assertNull(named(query(pid), "days", WfVariableInstanceView.SCOPE_TASK),
                "默认就该看不见已办结任务的变量 —— 「仍然存在的事实」不等于「当前状态」。实际: " + query(pid));

        WfVariableInstanceView historical = named(queryService.listVariables(
                new WfVariableInstanceQuery().setProcessInstanceId(pid)
                        .setOpenTasksOnly(Boolean.FALSE).setPageSize(1000)),
                "days", WfVariableInstanceView.SCOPE_TASK);
        assertNotNull(historical, "显式要历史就必须查得到");
        assertTrue(historical.isOnClosedTask(), "它属于一张已办结的任务，这要写在结果上");
        assertNotNull(historical.getTaskEndTime(), "办结时间让调用方能判断这条是残留还是还在用");
    }

    @Test
    @DisplayName("显式点名 taskId 时不过滤办结态 —— 返回空分不清是「没变量」还是「被滤掉」")
    void explicitTaskIdSeesClosedTask() {
        String pid = start();
        WfTask taskA = openTask(pid, "alice");
        variables.setTaskVariable(taskA.getId(), "days", 3, "ops");
        runtime.completeTask(taskA.getId(), "alice", "办完", null);

        List<WfVariableInstanceView> views = queryService.listVariables(
                new WfVariableInstanceQuery().setTaskId(taskA.getId()));
        WfVariableInstanceView view = named(views, "days", WfVariableInstanceView.SCOPE_TASK);
        assertNotNull(view, "排障查的恰恰多是已办结的任务，返回空列表等于把问题藏起来。实际: " + views);
        assertTrue(view.isOnClosedTask());
        // 仍然只有这一张任务上的东西：taskId 是精确到任务，不是范围
        assertEquals(1, views.size(), "taskId 精确查询不该带出别的作用域。实际: " + views);
    }

    // ==================== 引擎内部变量 ====================

    @Test
    @DisplayName("loopCounter 默认不列，显式打开才列")
    void engineInternalVariablesAreHiddenByDefault() {
        String pid = startCountersign();
        assertEquals(3, repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(20)).size(),
                "前置条件：会签展开成三张待办");

        assertNull(named(query(pid), WfMultiInstance.LOOP_COUNTER,
                WfVariableInstanceView.SCOPE_EXECUTION),
                "loopCounter 是引擎自己的中间量，混进来只会让人怀疑查错了。实际: " + query(pid));

        List<WfVariableInstanceView> withInternal = queryService.listVariables(
                new WfVariableInstanceQuery().setProcessInstanceId(pid)
                        .setIncludeEngineInternal(Boolean.TRUE).setPageSize(1000));
        int counters = 0;
        for (WfVariableInstanceView each : withInternal) {
            if (WfMultiInstance.LOOP_COUNTER.equals(each.getName())) {
                counters++;
            }
        }
        assertEquals(3, counters, "三个会签分支各有一个 loopCounter。实际: " + withInternal);
    }

    // ==================== 已结束的分支 ====================

    @Test
    @DisplayName("已结束的分支不再列出其局部变量，流程级不受影响")
    void endedBranchIsNotListed() {
        String pid = start();
        WfExecution tokenA = tokenAt(pid, "branchA");
        variables.setVariableLocal(tokenA.getId(), "branchOnly", "v", "ops");
        // 流程级那个必须**在分支走完之前**写好：终态实例的变量是改不动的
        // （引擎会拒绝，那是另一条闸门），走完之后还能看到它才是本用例要的证据
        variables.setVariable(pid, "stillHere", 1, "ops");
        assertNotNull(named(query(pid), "branchOnly", WfVariableInstanceView.SCOPE_EXECUTION),
                "前置条件：分支还在时查得到");

        runtime.completeTask(openTask(pid, "alice").getId(), "alice", "办完", null);
        runtime.completeTask(openTask(pid, "bob").getId(), "bob", "办完", null);
        assertTrue(repo.findProcessInstance(pid).getStatus().isTerminal(),
                "前置条件：整个实例已经走完");

        assertNull(named(query(pid), "branchOnly", WfVariableInstanceView.SCOPE_EXECUTION),
                "变量随分支一起失效了，继续列出来是「曾经存在过」而不是「现在有」。实际: " + query(pid));
        assertNotNull(named(query(pid), "stillHere", WfVariableInstanceView.SCOPE_PROCESS),
                "流程级变量挂在实例上，实例还在就该看得见 —— "
                        + "把两级一起滤掉的话，终态实例会返回一份空清单，"
                        + "而调用方会读成「这单什么都没有」");
    }

    // ==================== 过滤 ====================

    @Test
    @DisplayName("按名字 / 模糊名字 / 值 / 作用域过滤，每个条件都真的会剔掉东西")
    void filtersActuallyExclude() {
        String pid = startWithAllThreeScopes();
        // region 是"本该被下面每个过滤条件排除掉的对象"。
        // 没有它的话，把 name 条件整段摘掉，断言照样成立 ——
        // 那种判据验的不是过滤，只是"结果条数没变"
        variables.setVariable(pid, "region", "east", "ops");
        assertNotNull(named(query(pid), "region", WfVariableInstanceView.SCOPE_PROCESS),
                "前置条件：被排除的对象得先真的在");

        List<WfVariableInstanceView> byName = queryService.listVariables(
                new WfVariableInstanceQuery().setProcessInstanceId(pid)
                        .setName("amount").setPageSize(1000));
        assertEquals(3, byName.size(), "amount 有三条（流程/分支/任务级）。实际: " + byName);
        assertNull(named(byName, "region", WfVariableInstanceView.SCOPE_PROCESS),
                "name 过滤没生效：region 混进来了");

        List<WfVariableInstanceView> byLike = queryService.listVariables(
                new WfVariableInstanceQuery().setProcessInstanceId(pid)
                        .setNameLike("AMO").setPageSize(1000));
        assertEquals(3, byLike.size(), "模糊匹配要忽略大小写。实际: " + byLike);

        List<WfVariableInstanceView> byValue = queryService.listVariables(
                new WfVariableInstanceQuery().setProcessInstanceId(pid)
                        .setValueEquals("500").setPageSize(1000));
        assertEquals(1, byValue.size(), "只有分支级那个值是 500。实际: " + byValue);
        assertEquals(WfVariableInstanceView.SCOPE_EXECUTION, byValue.get(0).getScope());

        List<WfVariableInstanceView> byScope = queryService.listVariables(
                new WfVariableInstanceQuery().setProcessInstanceId(pid)
                        .addScope(WfVariableInstanceView.SCOPE_TASK).setPageSize(1000));
        assertEquals(1, byScope.size(), "任务级只有一条。实际: " + byScope);
        assertEquals("T-1", byScope.get(0).getValue());

        List<WfVariableInstanceView> both = queryService.listVariables(
                new WfVariableInstanceQuery().setProcessInstanceId(pid)
                        .setName("amount").setNameLike("reg").setPageSize(1000));
        assertTrue(both.isEmpty(), "两个名字条件互相矛盾时不该凑出结果。实际: " + both);
    }

    @Test
    @DisplayName("按 executionId 查：定位到那条 token 上的两层变量，不牵出别的 token")
    void queryByExecutionId() {
        String pid = startWithAllThreeScopes();
        WfExecution tokenA = tokenAt(pid, "branchA");
        String tokenB = tokenAt(pid, "branchB").getId();

        List<WfVariableInstanceView> views = queryService.listVariables(
                new WfVariableInstanceQuery().setExecutionId(tokenA.getId()).setPageSize(1000));
        assertEquals(2, views.size(), "这条 token 上有分支级与任务级各一条。实际: " + views);
        for (WfVariableInstanceView view : views) {
            assertEquals(tokenA.getId(), view.getExecutionId(), "不该带出别的 token 的变量: " + view);
        }

        // 只给 executionId 时服务必须能自己把范围缩到那个实例 ——
        // 否则会退化成全表扫任务，在跑得多的系统上撞上限报一个无关的错
        assertTrue(queryService.listVariables(new WfVariableInstanceQuery()
                .setExecutionId(tokenB).setPageSize(1000)).isEmpty(),
                "乙支线上没写任何变量，但它上面也确实有一张待办 —— 说明范围确实缩到了那个实例");
    }

    // ==================== 拒绝与边界 ====================

    @Test
    @DisplayName("一个范围都不给要报错，不能返回一份「全系统只有这几个变量」")
    void noScopeIsRejected() {
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> queryService.listVariables(new WfVariableInstanceQuery()));
        assertTrue(ex.getMessage().contains("processInstanceId"),
                "报错要直接告诉调用方该给什么。实际: " + ex.getMessage());
        assertThrows(WfEngineException.class,
                () -> queryService.countVariables(new WfVariableInstanceQuery()),
                "count 也走同一条闸门 —— 只在 list 上拦的话，总数会变成 0 而列表报错，对不上");
    }

    @Test
    @DisplayName("点名的 token / 任务 / 实例不存在时返回空结果，而不是抛异常")
    void missingTargetsReturnEmpty() {
        assertTrue(queryService.listVariables(
                new WfVariableInstanceQuery().setExecutionId("no-such-token")).isEmpty());
        assertTrue(queryService.listVariables(
                new WfVariableInstanceQuery().setTaskId("no-such-task")).isEmpty());
        assertTrue(queryService.listVariables(
                new WfVariableInstanceQuery().setProcessInstanceId("no-such-pid")).isEmpty());
        assertEquals(0, queryService.countVariables(
                new WfVariableInstanceQuery().setProcessInstanceId("no-such-pid")));
    }

    @Test
    @DisplayName("count 与 list 走同一套判定：total 是匹配总数，分页切在过滤后")
    void countMatchesList() {
        String pid = startWithAllThreeScopes();
        variables.setVariable(pid, "region", "east", "ops");

        WfVariableInstanceQuery query = new WfVariableInstanceQuery()
                .setProcessInstanceId(pid).setPageSize(2);
        assertEquals(2, queryService.listVariables(query).size(), "每页 2 条");
        assertEquals(4, queryService.countVariables(query),
                "total 是匹配总数而不是当前页 —— 拿页大小当总数会让分页器算不出还有几页");

        assertEquals(2, queryService.listVariables(query).size());
        assertEquals(2, queryService.listVariables(
                new WfVariableInstanceQuery().setProcessInstanceId(pid)
                        .setNameLike("amount").setPageSize(2)).size());
        assertEquals(1, queryService.listVariables(new WfVariableInstanceQuery()
                .setProcessInstanceId(pid).setNameLike("amount")
                .setPageSize(2).setPageNum(2)).size(),
                "过滤后只剩 3 条，第二页应只剩 1 条");
        assertTrue(queryService.listVariables(new WfVariableInstanceQuery()
                .setProcessInstanceId(pid).setNameLike("amount")
                .setPageSize(2).setPageNum(9)).isEmpty(), "越界的页码给空列表而不是异常");

        // 上面那条查询**一个过滤条件都没有**（只有 processInstanceId），
        // 在它上面断言 count 区分不了「数的是匹配的那些」与「数的是扫到的全部」——
        // 两者都等于 4。这条才是有区分力的：name 一加上去，
        // 匹配数是 3 而扫到的行数是 4（region 只在流程级、名字对不上）
        WfVariableInstanceQuery filtered = new WfVariableInstanceQuery()
                .setProcessInstanceId(pid).setName("amount");
        assertEquals(3, queryService.countVariables(filtered),
                "count 必须数的是**匹配后**的那部分，而不是扫到几行");
        assertEquals(3, queryService.listVariables(filtered).size(),
                "list 与 count 对同一个带过滤的查询必须给出同一个数");
    }

    @Test
    @DisplayName("variablesOf 不分页 —— 走分页就等于给了一个静默的 1000 上限")
    void variablesOfIsNotPaged() {
        String pid = startWithAllThreeScopes();
        variables.setVariable(pid, "region", "east", "ops");
        assertEquals(4, queryService.variablesOf(pid).size(), "这几个变量一个都不能少");
        // 反向验证摘掉不分页那段（改回走 listVariables）时，
        // 4 条仍会全在 —— 说明这条断言在 4 条这个量级上没有区分力。
        // 它钉的是"没有夹一个 MAX_VALUE→1000 的归一化"这条契约，
        // 而那个归一化正是当初会静默截断的地方，保留它是为了让改动立刻变红
    }

    @Test
    @DisplayName("variablesOf 跨过分页上限仍要全给 —— 走分页就是给了一个静默的 1000 上限")
    void variablesOfIsNotCappedAtOnePage() {
        String pid = start();
        int total = 1001;
        Map<String, Object> batch = new HashMap<String, Object>();
        for (int i = 0; i < total; i++) {
            batch.put("k" + i, i);
        }
        variables.setVariables(pid, batch, "ops");

        // pageSize 传 Integer.MAX_VALUE 时会被 normalizedPageSize() 归一到 1000，
        // 于是"这个方法不分页"这句话是假的 —— 而少掉的那几条里
        // 可能就有条件表达式正在读的那个变量
        assertEquals(total, queryService.variablesOf(pid).size(),
                "少掉的那几条会让调用方以为「这单就这么多变量」");
        // 对照：走分页的 listVariables 在这个量级上确实会切页，
        // 两条路给出的条数不同才是这条断言有区分力的原因
        assertEquals(1000, queryService.listVariables(new WfVariableInstanceQuery()
                .setProcessInstanceId(pid).setPageSize(Integer.MAX_VALUE)).size(),
                "分页路径的归一化上限就是 1000 —— variablesOf 走的是另一条路");
    }

    // ==================== 超量护栏 ====================

    @Test
    @DisplayName("扫到的原始行数超过上限要报错；恰好等于上限不算超")
    void overflowIsReported() {
        String pid = start();
        // 判据必须是**读到的原始行数**，所以这里控制的是"读回来的行数"这个量本身。
        // 造一万条真任务做不到（多实例上限 200），而护栏要验的只是
        // "读超了会不会报" —— 顺带造别的差异只会让失败原因变得不可读
        repo.inflateTasksTo = WfVariableQueryService.MAX_SCAN + 1;
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> queryService.listVariables(new WfVariableInstanceQuery().setProcessInstanceId(pid)));
        assertTrue(ex.getMessage().contains(String.valueOf(WfVariableQueryService.MAX_SCAN)),
                "报错要说清上限是多少，否则调用方不知道该缩到什么范围。实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("taskId"), "报错要给出缩范围的办法。实际: " + ex.getMessage());
        assertThrows(WfEngineException.class,
                () -> queryService.countVariables(new WfVariableInstanceQuery().setProcessInstanceId(pid)),
                "count 也要被拦 —— 只拦 list 的话，总数会变成一个看起来正常的数字");

        // 恰好等于上限：不能拦。护栏把合法的完整结果也拦掉，就再也没人敢信它了
        repo.inflateTasksTo = WfVariableQueryService.MAX_SCAN;
        assertEquals(0, queryService.countVariables(
                new WfVariableInstanceQuery().setProcessInstanceId(pid)),
                "恰好等于上限时不该报错；这批造出来的任务身上没有变量，所以计数是 0");
    }

    /**
     * 只多一个可控量的存储层：把 {@code queryTasks} 的返回行数撑到指定条数。
     *
     * <p>其余行为一律走父类的真实实现 —— 所以它就是那个跑流程的同一份存储，
     * {@code findProcessInstance} / {@code findExecution} 拿到的仍然是真数据，
     * 护栏测试失败时读到的原因也只有"行数"这一个。
     */
    private static class InflatableTaskPersistence extends InMemoryWorkflowPersistence {

        /** 小于 0 表示不干预。 */
        private int inflateTasksTo = -1;

        @Override
        public List<WfTask> queryTasks(WfTaskQuery query) {
            List<WfTask> real = super.queryTasks(query);
            if (inflateTasksTo < 0 || real == null || real.size() >= inflateTasksTo) {
                return real;
            }
            List<WfTask> inflated = new ArrayList<WfTask>(real);
            for (int i = real.size(); i < inflateTasksTo; i++) {
                WfTask synthetic = new WfTask();
                synthetic.setId("synthetic-" + i);
                synthetic.setProcessInstanceId(real.get(0).getProcessInstanceId());
                synthetic.setStatus(WfTask.Status.COMPLETED);
                inflated.add(synthetic);
            }
            return inflated;
        }
    }
}
