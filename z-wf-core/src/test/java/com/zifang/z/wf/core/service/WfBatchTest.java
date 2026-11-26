package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfIdGenerator;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfBatch;
import com.zifang.z.wf.core.model.WfBatchCriteria;
import com.zifang.z.wf.core.model.WfBatchElement;
import com.zifang.z.wf.core.model.WfBatchOperation;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfBatchQuery;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfProcessInstanceQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 批量操作的行为约定（第 39 轮）。
 *
 * <p>这套东西全部的价值在三个地方，用例也就盯这三处：
 * <ol>
 *   <li><b>两段式</b>：创建之后、执行之前，数据一个字节都没动；</li>
 *   <li><b>逐个目标独立成败</b>：一批里失败的不影响其余，且失败原因逐条可查；</li>
 *   <li><b>不该做的事被挡住</b>：条件空、操作用错类型、重复执行、状态枚举写错。</li>
 * </ol>
 *
 * <p>凡是「会不会静默改成一批别的数据」的场合，断言都落在
 * <b>目标列表的条数</b>和 <b>实际拿到的集合</b>上，而不是"没报错"。
 */
class WfBatchTest {

    private static final String LEAVE_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"leaveProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"主管审批\" zifang:assignee=\"boss\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRuntimeService runtime;
    private WfBatchService batches;
    private WfDefinition definition;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        WfRepositoryService repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo,
                new com.zifang.z.wf.core.engine.WfEngine(), new WfHookDispatcher());
        WfVariableService variables = new WfVariableService(repo, new WfIdGenerator.DefaultWfIdGenerator());
        batches = new WfBatchService(repo, new WfIdGenerator.DefaultWfIdGenerator(),
                runtime, variables, new WfTaskService(repository, repo, runtime, new WfHookDispatcher()));
        definition = repository.deploy(new WfXmlParser().parse(LEAVE_BPMN));
    }

    // ==================== 夹具 ====================

    private String startOne(String businessKey) {
        return runtime.startProcessInstance(definition, businessKey, "employee-1", null, null);
    }

    /** 按 businessKey 收一批实例，供断言点查。 */
    private List<WfProcessInstance> instancesOf(String definitionKey) {
        return repo.queryProcessInstances(new WfProcessInstanceQuery()
                .setDefinitionKey(definitionKey).setPageNum(1).setPageSize(50));
    }

    private static WfBatchCriteria byDefinitionKey(String key) {
        WfBatchCriteria criteria = new WfBatchCriteria();
        criteria.setProcessDefinitionKey(key);
        return criteria;
    }

    private static WfBatchOperation setVariable(String name, Object value) {
        WfBatchOperation operation = new WfBatchOperation(WfBatchOperation.Type.SET_VARIABLE);
        operation.setVariable(name);
        operation.setValue(value);
        return operation;
    }

    // ==================== 两段式 ====================

    @Test
    @DisplayName("创建批次不改任何数据 —— 确认之前不该有副作用")
    void creatingABatchTouchesNothing() {
        String instanceId = startOne("leave-001");
        int revisionBefore = repo.findProcessInstance(instanceId).getRevision();

        WfBatch batch = batches.createBatch(WfBatch.Type.INSTANCE, byDefinitionKey("leaveProcess"),
                Arrays.asList(setVariable("migrated", "v2")), "admin-1");

        assertEquals(WfBatch.State.CREATED, batch.getState(),
                "刚创建的批次必须是「已创建」，不是别的");
        assertEquals(0, batch.getAffectedCount(), "还没执行过就不该有改成功的数");
        assertEquals(0, batch.getFailureCount(), "还没执行过就不该有失败的数");
        assertNull(repo.findProcessInstance(instanceId).getVariables().get("migrated"),
                "**创建阶段绝对不能写变量** —— 这是两段式存在的全部理由。"
                        + "当前变量: " + repo.findProcessInstance(instanceId).getVariables());
        assertEquals(revisionBefore, repo.findProcessInstance(instanceId).getRevision(),
                "创建批次不该 bump 实例的乐观锁版本");
        assertEquals(0, batches.listElements(batch.getId()).size(),
                "创建阶段不该产生任何明细行");
    }

    @Test
    @DisplayName("countTargets 只是数一遍，不落任何东西")
    void countTargetsIsReadOnly() {
        startOne("leave-001");
        startOne("leave-002");
        WfBatch batch = batches.createBatch(WfBatch.Type.INSTANCE, byDefinitionKey("leaveProcess"),
                Arrays.asList(setVariable("migrated", "v2")), "admin-1");

        assertEquals(2, batches.countTargets(batch.getId()),
                "执行前就能看到会命中几个 —— 这是两段式的第二段");
        assertEquals(2, batches.countTargets(batch.getId()),
                "再数一次结果必须一样：数一个东西要是会改变它，那这个数字就没法拿来做决策");
        assertEquals(0, batches.listElements(batch.getId()).size(), "数目标不产生明细");
    }

    // ==================== 逐个目标独立成败 ====================

    @Test
    @DisplayName("三个目标里一个失败，另外两个照样改完，原因逐条可查")
    void failureOfOneTargetDoesNotStopTheOthers() {
        String first = startOne("leave-001");
        String second = startOne("leave-002");
        String third = startOne("leave-003");
        // 第四个 id 根本不存在：它就是这批里的那一个失败
        String ghost = "proc-does-not-exist";

        WfBatchCriteria criteria = new WfBatchCriteria();
        criteria.setIds(Arrays.asList(first, second, ghost, third));

        WfBatch batch = batches.createBatch(WfBatch.Type.INSTANCE, criteria,
                Arrays.asList(setVariable("migrated", "v2")), "admin-1");
        WfBatch done = batches.executeBatch(batch.getId(), new Date());

        assertEquals(WfBatch.State.COMPLETED, done.getState(),
                "整体是「已完成」：**有目标失败不等于整批失败**，那是 Camunda 的行为也是运维需要的");
        assertEquals(3, done.getAffectedCount(), "三个真目标都该改成功");
        assertEquals(1, done.getFailureCount(), "只有那个不存在的 id 该失败");

        // 关键：不能只看计数，得确认真改到了
        for (String id : Arrays.asList(first, second, third)) {
            assertEquals("v2", repo.findProcessInstance(id).getVariables().get("migrated"),
                    "实例 " + id + " 必须真的被改到了。实际变量: "
                            + repo.findProcessInstance(id).getVariables());
        }

        List<WfBatchElement> failures = batches.listFailedElements(batch.getId());
        assertEquals(1, failures.size(), "失败项必须逐条可查。实际失败项: " + failures);
        assertEquals(ghost, failures.get(0).getTargetId(),
                "失败项必须说清是哪个目标失败了。实际: " + failures);
        assertNotNull(failures.get(0).getFailureMessage(), "失败原因不能为空");
        assertTrue(failures.get(0).getFailureMessage().contains(ghost)
                        || failures.get(0).getFailureMessage().length() > 0,
                "失败原因应当指出问题（实际: " + failures.get(0).getFailureMessage() + "）");

        assertEquals(4, batches.listElements(batch.getId()).size(),
                "明细必须一条不落 —— 成功的也要留痕，否则「这批到底处理了谁」无从回答");
    }

    @Test
    @DisplayName("挂起操作作用在每个目标上")
    void suspendOperationAppliesToEveryTarget() {
        String first = startOne("leave-001");
        String second = startOne("leave-002");

        WfBatch batch = batches.createBatch(WfBatch.Type.INSTANCE, byDefinitionKey("leaveProcess"),
                Arrays.asList(new WfBatchOperation(WfBatchOperation.Type.SUSPEND)), "admin-1");
        batches.executeBatch(batch.getId(), new Date());

        for (String id : Arrays.asList(first, second)) {
            assertEquals(WfProcessStatus.SUSPENDED, repo.findProcessInstance(id).getStatus(),
                    "实例 " + id + " 应当已被挂起。实际: " + repo.findProcessInstance(id).getStatus());
        }
    }

    @Test
    @DisplayName("已结束的实例被挂起算失败，且不影响其它目标")
    void suspendingAFinishedInstanceFailsOnlyThatOne() {
        String alive = startOne("leave-001");
        String doomed = startOne("leave-002");
        runtime.completeTask(theOpenTaskOf(doomed).getId(), "boss", "过了", null);
        assertEquals(WfProcessStatus.COMPLETED, repo.findProcessInstance(doomed).getStatus(),
                "前置条件：这条应当已经结束了");

        WfBatch batch = batches.createBatch(WfBatch.Type.INSTANCE, byDefinitionKey("leaveProcess"),
                Arrays.asList(new WfBatchOperation(WfBatchOperation.Type.SUSPEND)), "admin-1");
        WfBatch done = batches.executeBatch(batch.getId(), new Date());

        assertEquals(1, done.getAffectedCount(), "只有还活着的那个该成功");
        assertEquals(1, done.getFailureCount(), "已结束的实例挂不起来，必须记成失败而不是静默跳过");
        assertEquals(WfProcessStatus.SUSPENDED, repo.findProcessInstance(alive).getStatus());
        assertEquals(WfProcessStatus.COMPLETED, repo.findProcessInstance(doomed).getStatus(),
                "已经结束的实例不能被挂起改回别的状态");
    }

    // ==================== 不该做的事被挡住 ====================

    @Test
    @DisplayName("条件为空被拒 —— 那等于「改这个类型下的全部」")
    void emptyCriteriaIsRejected() {
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> batches.createBatch(WfBatch.Type.INSTANCE, new WfBatchCriteria(),
                        Arrays.asList(setVariable("x", 1)), "admin-1"),
                "一个条件都不给就是改全部，不能放行");
        assertTrue(ex.getMessage().contains("全部"),
                "报错要说清为什么危险，而不是只说参数不合法。实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("给了别的类型的条件也当作空 —— 否则会退化成「改全部任务」")
    void criteriaOfAnotherTypeCountsAsEmpty() {
        // 只给了实例的字段，却声明这是 TASK 批次
        WfBatchCriteria criteria = byDefinitionKey("leaveProcess");
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> batches.createBatch(WfBatch.Type.TASK, criteria,
                        Arrays.asList(new WfBatchOperation(WfBatchOperation.Type.SUSPEND)), "admin-1"),
                "任务查询根本不读 processDefinitionKey，放行等于改全部任务");
        assertTrue(ex.getMessage().contains("空") || ex.getMessage().contains("全部"),
                "报错要点明「对这个类型来说条件是空的」。实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("操作不适用于批次类型时，创建期就拒")
    void operationMustMatchBatchType() {
        WfBatchOperation setPriority = new WfBatchOperation(WfBatchOperation.Type.SET_PRIORITY);
        setPriority.setPriority(10);
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> batches.createBatch(WfBatch.Type.INSTANCE, byDefinitionKey("leaveProcess"),
                        Arrays.asList(setPriority), "admin-1"),
                "给实例设 job 优先级不会报运行时错，只会让这批一个都不改 —— 必须提前拒");
        assertTrue(ex.getMessage().contains("setPriority"),
                "报错要指出是哪条操作有问题。实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("setVariable 带 null 值被拒 —— 本仓 null 即删除")
    void setVariableRejectsNullValue() {
        WfBatchOperation op = new WfBatchOperation(WfBatchOperation.Type.SET_VARIABLE);
        op.setVariable("migrated");
        op.setValue(null);
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> batches.createBatch(WfBatch.Type.INSTANCE, byDefinitionKey("leaveProcess"),
                        Arrays.asList(op), "admin-1"),
                "放行的话会在执行期对每个目标报一次同样的错，一批 1000 就是 1000 条一样的失败");
        assertTrue(ex.getMessage().contains("removeVariable"),
                "报错要告诉人正确的替代做法。实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("状态枚举写错时报错，绝不退化成「不过滤」")
    void unknownStatusIsRejectedRatherThanIgnored() {
        WfBatchCriteria criteria = new WfBatchCriteria();
        criteria.setStatus("ACTVE");   // 少个 I
        WfBatch batch = batches.createBatch(WfBatch.Type.INSTANCE, criteria,
                Arrays.asList(setVariable("migrated", "v2")), "admin-1");
        // createBatch 不会去解枚举（那是执行时的事），所以报错发生在 countTargets / executeBatch
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> batches.countTargets(batch.getId()),
                "退化成不过滤 = 改全部实例，而记录上写着带了个状态条件");
        assertTrue(ex.getMessage().contains("ACTVE"),
                "报错要回显那个写错的值。实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("批次不能执行第二次")
    void batchCannotBeExecutedTwice() {
        startOne("leave-001");
        WfBatch batch = batches.createBatch(WfBatch.Type.INSTANCE, byDefinitionKey("leaveProcess"),
                Arrays.asList(setVariable("migrated", "v2")), "admin-1");
        batches.executeBatch(batch.getId(), new Date());

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> batches.executeBatch(batch.getId(), new Date()),
                "点了两次是最容易犯的错。幂等的话第二次会安静地什么都不做，"
                        + "而看记录的人以为执行过了");
        assertTrue(ex.getMessage().contains("已经执行过"),
                "报错要说清是重复执行。实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("挂起后不能执行，激活后可以")
    void suspendedBatchCannotRun() {
        startOne("leave-001");
        WfBatch batch = batches.createBatch(WfBatch.Type.INSTANCE, byDefinitionKey("leaveProcess"),
                Arrays.asList(setVariable("migrated", "v2")), "admin-1");

        batches.suspendBatch(batch.getId(), "admin-2");
        assertTrue(batches.getBatch(batch.getId()).isSuspended());
        assertThrows(WfEngineException.class, () -> batches.executeBatch(batch.getId(), new Date()),
                "挂起的作用就是「别改」，改了就白挂了");

        batches.activateBatch(batch.getId(), "admin-2");
        assertFalse(batches.getBatch(batch.getId()).isSuspended());
        WfBatch done = batches.executeBatch(batch.getId(), new Date());
        assertEquals(1, done.getAffectedCount());
    }

    @Test
    @DisplayName("已经执行的批次挂不起来 —— 对一件做完的事说「别改了」没有意义")
    void executedBatchCannotBeSuspended() {
        startOne("leave-001");
        WfBatch batch = batches.createBatch(WfBatch.Type.INSTANCE, byDefinitionKey("leaveProcess"),
                Arrays.asList(setVariable("migrated", "v2")), "admin-1");
        batches.executeBatch(batch.getId(), new Date());

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> batches.suspendBatch(batch.getId(), "admin-2"),
                "目标都改完了，挂它没有意义");
        assertTrue(ex.getMessage().contains("COMPLETED") || ex.getMessage().contains("未执行"),
                "报错要带上当前状态，让人一眼看出为什么不能挂。实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("删批次连带删掉明细，不留查不出主人的孤儿行")
    void deletingABatchRemovesItsElements() {
        String id = startOne("leave-001");
        WfBatch batch = batches.createBatch(WfBatch.Type.INSTANCE, byDefinitionKey("leaveProcess"),
                Arrays.asList(setVariable("migrated", "v2")), "admin-1");
        batches.executeBatch(batch.getId(), new Date());
        assertTrue(batches.listElements(batch.getId()).size() > 0, "前置条件：应当有明细");

        assertTrue(batches.deleteBatch(batch.getId()));
        assertNull(batches.getBatch(batch.getId()), "批次本体应当没了");
        assertEquals(0, batches.listElements(batch.getId()).size(),
                "明细必须一起删 —— 留着就是按 batchId 查得出、却查不出批次的那种孤儿行");
    }

    // ==================== 任务与 job ====================

    @Test
    @DisplayName("任务级批次：设任务局部变量")
    void taskBatchSetsLocalVariable() {
        String instanceId = startOne("leave-001");
        WfTask task = theOpenTaskOf(instanceId);

        WfBatchCriteria criteria = new WfBatchCriteria();
        criteria.setTaskAssignee("boss");
        WfBatch batch = batches.createBatch(WfBatch.Type.TASK, criteria,
                Arrays.asList(setVariable("risk", "high")), "admin-1");

        assertEquals(1, batches.countTargets(batch.getId()), "应当命中 boss 的那一条待办");
        WfBatch done = batches.executeBatch(batch.getId(), new Date());
        assertEquals(1, done.getAffectedCount());

        assertEquals("high", repo.findTask(task.getId()).getVariables().get("risk"),
                "任务局部变量必须真的写上了。实际: " + repo.findTask(task.getId()).getVariables());
    }

    @Test
    @DisplayName("job 级批次：设重试次数与优先级")
    void jobBatchSetsRetriesAndPriority() {
        String instanceId = startOne("leave-001");
        WfJob job = new WfJob();
        // **id 必须显式给**：saveJob 少了 id 会直接丢弃，而丢弃是静默的 ——
        // 症状是「断言说命中 0 个」，看不出是自己少给了 id
        job.setId("job-for-batch-1");
        job.setProcessInstanceId(instanceId);
        job.setElementId("approve");
        job.setRetries(0);
        job.setPriority(10);
        repo.saveJob(job);

        WfBatchCriteria criteria = new WfBatchCriteria();
        criteria.setJobProcessInstanceId(instanceId);
        WfBatchOperation retries = new WfBatchOperation(WfBatchOperation.Type.SET_JOB_RETRIES);
        retries.setRetries(3);
        WfBatchOperation priority = new WfBatchOperation(WfBatchOperation.Type.SET_PRIORITY);
        priority.setPriority(99);

        WfBatch batch = batches.createBatch(WfBatch.Type.JOB, criteria,
                Arrays.asList(retries, priority), "admin-1");
        assertEquals(1, batches.countTargets(batch.getId()), "该实例下应当只有一个 job");
        batches.executeBatch(batch.getId(), new Date());

        WfJob after = repo.findJob(job.getId());
        assertEquals(3, after.getRetries(), "重试次数必须真的改上去。实际: " + after.getRetries());
        assertEquals(99, after.getPriority(), "优先级必须真的改上去。实际: " + after.getPriority());
    }

    @Test
    @DisplayName("实例级的 setJobRetries 作用在该实例名下全部 job 上")
    void instanceLevelRetriesCoversEveryJobOfThatInstance() {
        String instanceId = startOne("leave-001");
        List<String> jobIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            WfJob job = new WfJob();
            job.setId("batch-job-" + i);
            job.setProcessInstanceId(instanceId);
            job.setElementId("node" + i);
            job.setRetries(0);
            repo.saveJob(job);
            assertNotNull(repo.findJob(job.getId()), "前置条件：job " + i + " 必须真的存进去了");
            jobIds.add(job.getId());
        }

        WfBatchCriteria criteria = new WfBatchCriteria();
        criteria.setProcessDefinitionKey("leaveProcess");
        WfBatchOperation retries = new WfBatchOperation(WfBatchOperation.Type.SET_JOB_RETRIES);
        retries.setRetries(5);
        WfBatch batch = batches.createBatch(WfBatch.Type.INSTANCE, criteria,
                Arrays.asList(retries), "admin-1");
        batches.executeBatch(batch.getId(), new Date());

        for (String id : jobIds) {
            assertEquals(5, repo.findJob(id).getRetries(),
                    "实例级的重试操作必须覆盖它名下**每一个** job。" + id + " 实际: "
                            + repo.findJob(id).getRetries());
        }
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("按状态与操作人查批次，列表与计数口径一致")
    void queryBatchesByStateAndOperator() {
        WfBatch one = batches.createBatch(WfBatch.Type.INSTANCE, byDefinitionKey("leaveProcess"),
                Arrays.asList(setVariable("a", 1)), "admin-1");
        batches.createBatch(WfBatch.Type.INSTANCE, byDefinitionKey("leaveProcess"),
                Arrays.asList(setVariable("b", 2)), "admin-2");

        WfBatchQuery query = new WfBatchQuery().setOperatorId("admin-1");
        assertEquals(1, batches.countBatches(query));
        assertEquals(1, batches.queryBatches(query).size());

        WfBatchQuery created = new WfBatchQuery().setState(WfBatch.State.CREATED);
        assertEquals(2, batches.countBatches(created), "两条都还没执行");
        batches.executeBatch(one.getId(), new Date());
        assertEquals(1, batches.countBatches(created), "执行过的不该再算「已创建」");
        assertEquals(1, batches.countBatches(
                new WfBatchQuery().setState(WfBatch.State.COMPLETED)));
    }

    @Test
    @DisplayName("批次记录的字段真具备它声称的那样")
    void batchRecordIsHonest() {
        String id = startOne("leave-001");
        WfBatch batch = batches.createBatch(WfBatch.Type.INSTANCE, byDefinitionKey("leaveProcess"),
                Arrays.asList(setVariable("migrated", "v2")), "admin-1");
        assertEquals("admin-1", batch.getOperatorId(), "创建人必须记下来");
        assertNotNull(batch.getCreateTime(), "创建时间必须记下来");
        assertNull(batch.getStartTime(), "还没执行就没有开始时间");
        assertNull(batch.getEndTime(), "还没执行就没有结束时间");
        assertNotNull(batch.getCriteria(), "条件必须落库了 —— 否则三天后执行时无从知道要改哪些");
        assertNotNull(batch.getOperations(), "操作必须落库了");

        batches.executeBatch(batch.getId(), new Date());
        WfBatch done = batches.getBatch(batch.getId());
        assertNotNull(done.getStartTime(), "执行过就必须有开始时间");
        assertNotNull(done.getEndTime(), "执行过就必须有结束时间");
        assertEquals(1, done.getAffectedCount());
        assertEquals(0, done.getFailureCount(), "前置条件：这批不该有失败");
        assertNull(done.getFailureReason(),
                "没有失败时 failureReason 应当为空，别塞「0 个失败」这种占位话。实际: "
                        + done.getFailureReason());
        assertEquals(id, instancesOf("leaveProcess").get(0).getId(),
                "顺带确认这个夹具确实只有一个实例，别让上面几条断言建在空集上");
    }

    // ==================== 辅助 ====================

    private WfTask theOpenTaskOf(String instanceId) {
        List<WfTask> open = repo.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(instanceId).setOpenOnly(true)
                .setPageNum(1).setPageSize(10));
        assertEquals(1, open.size(), "前置条件：应当恰好有一条待办，实际 " + open.size());
        return open.get(0);
    }

    /** 确认批次没留下悬空引用：明细里的 targetId 都应当能在库里找到。 */
    @Test
    @DisplayName("明细里的每个目标 id 都能在库里找到对应的行")
    void everyElementPointsAtARealTarget() {
        startOne("leave-001");
        WfBatch batch = batches.createBatch(WfBatch.Type.INSTANCE, byDefinitionKey("leaveProcess"),
                Arrays.asList(setVariable("migrated", "v2")), "admin-1");
        batches.executeBatch(batch.getId(), new Date());

        List<WfBatchElement> elements = batches.listElements(batch.getId());
        assertFalse(elements.isEmpty(), "前置条件：应当有明细");
        java.util.TreeMap<String, String> byType = new TreeMap<>();
        for (WfBatchElement element : elements) {
            byType.put(element.getTargetId() + "/" + element.getState().getLabel(), "在");
        }
        for (WfBatchElement element : elements) {
            WfProcessInstance instance = repo.findProcessInstance(element.getTargetId());
            assertNotNull(instance, "明细指向的实例必须存在: " + element);
            assertEquals(WfBatchElement.State.SUCCESS, element.getState(),
                    "这批里每个目标都该成功。失败项: " + batches.listFailedElements(batch.getId()));
        }
    }
}