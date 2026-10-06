package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfJob;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 手动触发 job（{@code WfJobService#triggerJob}）。
 *
 * <p>用途是"催一下"：超时提醒还差两小时才到，而人已经等不及了。
 *
 * <p>本类盯四件错了都不报错的事：
 * <ol>
 *   <li><b>只有时间触发型与异步型能手动触发。</b>订阅型等的是"某件事发生了"，
 *       手动触发等于替引擎伪造一件没发生的事 —— 流程会以为它发生了，
 *       而真的那件事随后还会再来一次，于是同一步走两遍。
 *       事件网关的消息 / 信号分支更糟：它们是<b>竞速</b>，手动触发会作废兄弟分支。</li>
 *   <li><b>job 不存在要报错而不是返回成功。</b>最常见的原因是扫描器刚消费掉它，
 *       而返回成功会让调用方以为事情办成了、于是不再重试。</li>
 *   <li><b>提前触发要留痕，而且要说清"轨迹上那条「停留超时」不适用于本次"。</b>
 *       {@code fireTimer} 写下的 reason 是超时语义，对提前触发来说是<b>假话</b> ——
 *       排查的人会顺着"为什么提前超时"找半天。</li>
 *   <li><b>「该响没响」不许写成"已手动触发"。</b>流程已结束 / token 已挪走时
 *       {@code fire} 返回 false，那不是失败，但也绝不是"触发了"。</li>
 * </ol>
 */
class WfJobTriggerTest {

    private static final String NS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n";

    private static final String NS_END = "</definitions>\n";

    /** 定时器边界挂在审批上：还有 3 天才到，边界触发后走 endEvent。 */
    private static final String TIMER_BPMN = NS
            + "  <process id=\"trigTimer\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <boundaryEvent id=\"overdue\" attachedToRef=\"approve\">\n"
            + "      <timerEventDefinition><timeDuration>P3D</timeDuration></timerEventDefinition>\n"
            + "    </boundaryEvent>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"overdue\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + NS_END;

    /** 异步前置：手动触发等于"现在就做这一步"，续跑后才建出待办。 */
    private static final String ASYNC_BPMN = NS
            + "  <process id=\"trigAsync\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\""
            + " zifang:asyncBefore=\"true\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + NS_END;

    /** 消息边界：不能手动触发。 */
    private static final String MESSAGE_BPMN = NS
            + "  <process id=\"trigMsg\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <boundaryEvent id=\"cancelBoundary\" attachedToRef=\"approve\">\n"
            + "      <messageEventDefinition messageRef=\"cancel\"/>\n"
            + "    </boundaryEvent>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"cancelBoundary\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + NS_END;

    /** 事件网关的信号分支：竞速，手动触发更危险。 */
    private static final String EVENT_GATEWAY_BPMN = NS
            + "  <process id=\"trigEg\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <eventBasedGateway id=\"eg\"/>\n"
            + "    <intermediateCatchEvent id=\"waitA\">\n"
            + "      <signalEventDefinition signalRef=\"sigA\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <intermediateCatchEvent id=\"waitB\">\n"
            + "      <signalEventDefinition signalRef=\"sigB\"/>\n"
            + "    </intermediateCatchEvent>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"eg\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"eg\" targetRef=\"waitA\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"eg\" targetRef=\"waitB\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"waitA\" targetRef=\"e\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"waitB\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + NS_END;

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfJobService jobService;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
        jobService = new WfJobService(repo, runtime);
    }

    // ==================== 能触发的那几类 ====================

    @Test
    @DisplayName("没到期的定时器 job 也能提前触发（这正是这个方法存在的意义）")
    void timerJobCanBeTriggeredBeforeDue() {
        String pid = start(TIMER_BPMN, "trigTimer");
        WfJob job = firstJob(pid);
        assertNotNull(job.getDuedate(), "前置条件：它有到期时刻");
        assertTrue(job.getDuedate().after(new Date()),
                "前置条件：**还没到期** —— 到期了的话这条就不成其为「提前」");
        assertEquals(1, openAt(pid, "approve").size(), "前置条件：boss 还在办");

        assertTrue(jobService.triggerJob(job.getId(), "ops"), "提前触发要真的触发");

        assertTrue(openAt(pid, "approve").isEmpty(), "boss 的待办作废（打断）");
        assertTrue(repo.findProcessInstance(pid).getStatus().isTerminal(),
                "走完边界那条线就结束了。实际: " + repo.findProcessInstance(pid).getStatus());
    }

    @Test
    @DisplayName("异步 job 提前触发 = 现在就做这一步")
    void asyncJobCanBeTriggeredEarly() {
        String pid = start(ASYNC_BPMN, "trigAsync");
        WfJob job = firstJob(pid);
        assertTrue(openAt(pid, "approve").isEmpty(),
                "前置条件：还没续跑，所以待办不存在");

        assertTrue(jobService.triggerJob(job.getId(), "ops"), "异步 job 也要能手动催");

        assertEquals(1, openAt(pid, "approve").size(),
                "续跑之后待办才建出来 —— 手动触发的语义就是「现在就做这一步」");
    }

    @Test
    @DisplayName("重试耗尽的 job 也允许手动触发（运维要手工重跑失败的任务）")
    void exhaustedJobCanBeTriggered() {
        String pid = start(TIMER_BPMN, "trigTimer");
        WfJob job = firstJob(pid);
        job.setRetries(WfJob.RETRIES_EXHAUSTED);
        // 改完再存要走既有的乐观锁契约：saveJob 校验 revision 必须是库里那份 +1，
        // 不 nextRevision 就会抛 WfOptimisticLockException —— 那不是本条要验的东西
        job.nextRevision();
        repo.saveJob(job);

        assertTrue(job.isRetriesExhausted(), "前置条件：重试已耗尽");
        assertTrue(jobService.triggerJob(job.getId(), "ops"),
                "扫描器不重试它，是为了不让它被无限重试；"
                        + "人手重跑一次是正当需求，不该被那条规则挡住");
    }

    // ==================== 不许触发的那几类 ====================

    @Test
    @DisplayName("消息 / 信号订阅不许手动触发，并说清为什么")
    void subscriptionJobsAreRejected() {
        String pid = start(MESSAGE_BPMN, "trigMsg");
        WfJob job = firstJob(pid);
        assertEquals(WfJobType.MESSAGE, job.getType(), "前置条件：这是一条消息订阅");

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> jobService.triggerJob(job.getId(), "ops"));
        assertTrue(ex.getMessage().contains("走两遍"),
                "要说出后果而不只是「不许」：手动触发等于伪造一条消息，"
                        + "而真的那条随后还会来一次。实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("triggerMessage"),
                "要指出替代路径 —— 否则调用方只会以为这条路被禁了。实际: " + ex.getMessage());
        assertEquals(1, openAt(pid, "approve").size(), "不得动到流程");
        assertNotNull(jobService.findJobsByProcessInstance(pid).get(0), "订阅必须还在");
    }

    @Test
    @DisplayName("事件网关的分支不许手动触发（竞速，作废兄弟分支不可逆）")
    void eventGatewayJobsAreRejected() {
        String pid = start(EVENT_GATEWAY_BPMN, "trigEg");
        WfJob job = firstJob(pid);
        assertTrue(job.getType() == WfJobType.EVENT_SIGNAL
                        || job.getType() == WfJobType.EVENT_MESSAGE,
                "前置条件：这是事件网关的分支，类型为 " + job.getType());

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> jobService.triggerJob(job.getId(), "ops"));
        assertTrue(ex.getMessage().contains("兄弟分支"),
                "事件网关的分支是竞速：手动触发会作废其余分支，"
                        + "那是不可逆的破坏，理由要说出来。实际: " + ex.getMessage());
    }

    // ==================== 幂等与留痕 ====================

    @Test
    @DisplayName("job 不存在要报错，绝不返回成功")
    void missingJobFailsLoudly() {
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> jobService.triggerJob("j-does-not-exist", "ops"));
        assertTrue(ex.getMessage().contains("不存在"),
                "返回成功会让调用方以为事情办成了、于是不再重试。实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("同一条 job 只能触发一次，第二次报错")
    void secondTriggerFailsLoudly() {
        String pid = start(TIMER_BPMN, "trigTimer");
        WfJob job = firstJob(pid);
        assertTrue(jobService.triggerJob(job.getId(), "ops"));

        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> jobService.triggerJob(job.getId(), "ops"));
        assertTrue(ex.getMessage().contains("不存在"),
                "第一次已经把它消费掉了，第二次要在「找不到」这一层就停住 —— "
                        + "而不是让它走完整条推进再发现走不了。实际: " + ex.getMessage());
        assertTrue(isTerminal(pid), "而且只能走一遍：两次触发会让补偿分支跑两遍");
    }

    @Test
    @DisplayName("提前触发要留痕，并说清「停留超时」那句不适用于本次")
    void manualTriggerIsRecorded() {
        String pid = start(TIMER_BPMN, "trigTimer");
        WfJob job = firstJob(pid);
        jobService.triggerJob(job.getId(), "ops");

        String comments = commentsOf(pid);
        assertTrue(comments.contains("手动提前触发"),
                "提前触发必须留痕 —— 否则事后从轨迹上看到的只有「停留超时」，"
                        + "而那句话对本次触发是假的。实际评论: " + comments);
        assertTrue(comments.contains("不适用于本次触发"),
                "必须点破轨迹上那条措辞是定时器的 —— 不点破的话，"
                        + "排查的人会顺着「为什么提前超时」找半天。实际: " + comments);
        // **断结构化字段，不在评论正文里搜「ops」**：
        // userId 存在 WfComment 自己的列上，正文里从来没有它。
        // 在正文里搜人名会恒 0 命中，而那种恒不成立的断言比没有断言更危险 ——
        // 它看上去在钉「谁点的」，实际什么都没钉，且没人会去改那条评论的正文
        // 来让它变红。
        WfComment mark = findManualTriggerComment(pid);
        assertNotNull(mark, "前置条件：找得到那条手动触发的留痕");
        assertEquals("ops", mark.getUserId(),
                "要记下是谁点的：不知道是谁就查不出是谁的误操作");
    }

    @Test
    @DisplayName("「该响没响」时不写「已手动触发」的假记录")
    void noFalseRecordWhenNothingFired() {
        String pid = start(TIMER_BPMN, "trigTimer");
        WfJob job = firstJob(pid);
        // 先把流程直接办结，让边界那条路走不了 —— token 已经不在宿主节点上
        for (WfTask task : repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(10))) {
            runtime.completeTask(task.getId(), task.getAssignee(), "抢在超时之前办完",
                    new HashMap<String, Object>());
        }
        assertTrue(isTerminal(pid), "前置条件：流程已结束");

        // **必须重插一条残留 job**：正常办结时定时器 job 会被 clearJobsOf 一并撤掉，
        // 于是 triggerJob 停在「job 不存在」那一层，根本走不到 fire ——
        // 那条判据问的「该响没响」压根没被问到，而且会以"报错"这个
        // 与被问无关的结果蒙混过关。
        // 残留 job 不是臆造的：checkTimerJobDispatch 的注释明写着
        // 「流程结束了还有残留定时器是清理没做干净」，那就把它造出来。
        repo.saveJob(job);
        assertNotNull(repo.findJob(job.getId()), "前置条件：这条残留 job 还在");

        boolean fired = jobService.triggerJob(job.getId(), "ops");

        assertFalse(fired, "流程已结束时不该报触发成功");
        assertNull(findManualTriggerComment(pid),
                "没真的触发却写一条「已手动触发」，是比不写更糟的记录 —— "
                        + "排障的人会顺着一条根本没发生过的事去查。实际评论: " + commentsOf(pid));
    }

    // ==================== 夹具 ====================

    private String start(String xml, String key) {
        WfDefinition definition = repository.deployXml(xml, key);
        return runtime.startProcessInstance(definition, key + "-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
    }

    private WfJob firstJob(String pid) {
        List<WfJob> jobs = repo.queryJobs(new WfJobQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(20));
        assertFalse(jobs.isEmpty(), "该实例上应当至少有一条 job");
        return jobs.get(0);
    }

    private List<WfTask> openAt(String pid, String nodeId) {
        List<WfTask> result = new java.util.ArrayList<>();
        for (WfTask t : repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setPageNum(1).setPageSize(20))) {
            if (t.isOpen() && nodeId.equals(t.getDefinitionId())) {
                result.add(t);
            }
        }
        return result;
    }

    private boolean isTerminal(String pid) {
        WfProcessInstance instance = repo.findProcessInstance(pid);
        return instance != null && instance.getStatus() != null
                && instance.getStatus().isTerminal();
    }

    private String commentsOf(String pid) {
        StringBuilder text = new StringBuilder();
        for (WfComment comment : repo.findComments(pid)) {
            text.append(comment.getContent()).append(" | ");
        }
        return text.toString();
    }

    /** 那条「手动提前触发」的留痕（没有则返回 null）。 */
    private WfComment findManualTriggerComment(String pid) {
        for (WfComment comment : repo.findComments(pid)) {
            if (comment.getContent() != null
                    && comment.getContent().contains("手动提前触发")) {
                return comment;
            }
        }
        return null;
    }
}
