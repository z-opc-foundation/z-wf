package com.zifang.z.wf.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfDefinitionValidator;
import com.zifang.z.wf.core.definition.WfValidationIssue;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;

/**
 * 错误边界事件的路由行为。
 *
 * <p>要解决的是"某一步失败了不要让整条流程死掉"：
 * 报错不该只是让流程挂掉或崩掉，而应当沿边界事件的出线走到补偿分支。
 */
class WfBpmnErrorTest {

    /**
     * start → approve(userTask) → end
     * approve 上挂一个捕获 APPROVAL_FAILED 的 boundaryEvent → cleanup(userTask) → end
     */
    private static String withBoundary(String errorCode) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
                + "  <process id=\"errProcess\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"s1\"/>\n"
                + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
                + "    <boundaryEvent id=\"onFail\" name=\"失败补偿\" attachedToRef=\"approve\">\n"
                + "      <errorEventDefinition"
                + (errorCode == null ? "" : " errorRef=\"" + errorCode + "\"")
                + "/>\n"
                + "    </boundaryEvent>\n"
                + "    <userTask id=\"cleanup\" name=\"清理\" zifang:assignee=\"ops\"/>\n"
                + "    <endEvent id=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
                + "    <sequenceFlow id=\"f3\" sourceRef=\"onFail\" targetRef=\"cleanup\"/>\n"
                + "    <sequenceFlow id=\"f4\" sourceRef=\"cleanup\" targetRef=\"e1\"/>\n"
                + "  </process>\n"
                + "</definitions>\n";
    }

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repository = new WfRepositoryService(repo);
        WfEngine engine = new WfEngine();
        runtime = new WfRuntimeService(repository, repo, engine, new WfHookDispatcher());
    }

    private String startRunning() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(
                withBoundary("APPROVAL_FAILED")));
        return runtime.startProcessInstance(definition, "err-" + System.nanoTime(),
                "alice", null, new HashMap<>());
    }

    private WfTask taskAt(String pid, String nodeId) {
        List<WfTask> open = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(50));
        for (WfTask t : open) {
            if (nodeId.equals(t.getDefinitionId())) {
                return t;
            }
        }
        throw new AssertionError("节点 " + nodeId + " 上没有待办，现有: " + open);
    }

    // ==================== 路由 ====================

    @Test
    @DisplayName("错误码匹配时，token 走边界事件到补偿分支")
    void errorRoutesToCompensationBranch() {
        String pid = startRunning();
        WfTask approve = taskAt(pid, "approve");

        WfProcessInstance after = runtime.handleBpmnError(approve.getId(),
                "APPROVAL_FAILED", "审批人查不到档案", new HashMap<>());

        assertEquals(WfProcessStatus.ACTIVE, after.getStatus());
        WfTask cleanup = taskAt(pid, "cleanup");
        assertEquals("ops", cleanup.getAssignee(),
                "应沿边界事件的出线走到补偿节点 cleanup");
    }

    @Test
    @DisplayName("错误码不区分大小写")
    void errorCodeIsCaseInsensitive() {
        String pid = startRunning();
        WfTask approve = taskAt(pid, "approve");
        runtime.handleBpmnError(approve.getId(), "approval_failed", "x", new HashMap<>());
        assertNotNull(taskAt(pid, "cleanup"));
    }

    @Test
    @DisplayName("触发后宿主节点的待办被作废，不留在办理人列表里")
    void hostTaskIsCancelled() {
        String pid = startRunning();
        WfTask approve = taskAt(pid, "approve");
        runtime.handleBpmnError(approve.getId(), "APPROVAL_FAILED", "x", new HashMap<>());

        WfTask after = repo.findTask(approve.getId());
        assertEquals(WfTask.Status.CANCELLED, after.getStatus(),
                "流程已经走补偿分支了，审批待办还挂着就永远办不完");
    }

    @Test
    @DisplayName("错误会留下可查的记录（谁、因什么码、什么消息）")
    void errorIsRecordedAsComment() {
        String pid = startRunning();
        WfTask approve = taskAt(pid, "approve");
        runtime.handleBpmnError(approve.getId(), "APPROVAL_FAILED", "查不到档案", new HashMap<>());

        List<WfComment> comments = runtime.getComments(pid);
        WfComment error = null;
        for (WfComment c : comments) {
            if ("error".equals(c.getType())) {
                error = c;
            }
        }
        assertNotNull(error, "错误必须留下记录，否则排障时无从知道发生过什么");
        assertTrue(error.getContent().contains("APPROVAL_FAILED"), error.getContent());
        assertTrue(error.getContent().contains("查不到档案"), error.getContent());
    }

    @Test
    @DisplayName("没有匹配的边界事件时流程终止并记下原因，绝不静默继续")
    void unmatchedErrorTerminatesWithReason() {
        String pid = startRunning();
        WfTask approve = taskAt(pid, "approve");

        WfProcessInstance after = runtime.handleBpmnError(approve.getId(),
                "SOMETHING_ELSE", "另一个错误", new HashMap<>());

        assertEquals(WfProcessStatus.INTERNALLY_TERMINATED, after.getStatus(),
                "没有边界事件能接住这个错误时，必须让流程失败而不是当作没事发生");
        assertTrue(after.getDeleteReason().contains("SOMETHING_ELSE"),
                "终止原因要带错误码: " + after.getDeleteReason());
        assertEquals(0, repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(50)).size(),
                "终止后不应留下永远办不完的待办");
    }

    @Test
    @DisplayName("空错误码直接拒绝")
    void blankErrorCodeRejected() {
        String pid = startRunning();
        WfTask approve = taskAt(pid, "approve");
        assertThrows(WfEngineException.class, () -> runtime.handleBpmnError(
                approve.getId(), "  ", "无码", new HashMap<>()));
    }

    @Test
    @DisplayName("已作废的任务不能再路由错误 —— 它的 token 早已不在该节点上")
    void cancelledTaskRejectsError() {
        String pid = startRunning();
        WfTask approve = taskAt(pid, "approve");
        runtime.handleBpmnError(approve.getId(), "APPROVAL_FAILED", "x", new HashMap<>());

        // 第一次路由后 approve 已被作废。此时再报错找不到"属于该活动的 token"，
        // 若放行会把流程从 cleanup 节点硬拽回边界事件 —— 比不路由更糟。
        WfEngineException ex = assertThrows(WfEngineException.class,
                () -> runtime.handleBpmnError(approve.getId(), "APPROVAL_FAILED", "y",
                        new HashMap<>()));
        assertTrue(ex.getMessage().contains("作废") || ex.getMessage().contains("办结"),
                ex.getMessage());
    }

    @Test
    @DisplayName("流程已结束时不再接受错误路由")
    void terminalInstanceRejectsError() {
        String pid = startRunning();
        WfTask approve = taskAt(pid, "approve");
        runtime.terminate(pid, "人工终止");
        assertThrows(WfEngineException.class, () -> runtime.handleBpmnError(
                approve.getId(), "APPROVAL_FAILED", "y", new HashMap<>()));
    }

    // ==================== 校验 ====================

    @Test
    @DisplayName("缺 errorCode 报 ERROR —— 宽泛捕获会把不相关异常也吸走")
    void missingErrorCodeIsRejected() {
        WfDefinition parsed = new WfXmlParser().parse(withBoundary(null));
        List<WfValidationIssue> issues = new WfDefinitionValidator().validate(parsed);
        assertTrue(WfDefinitionValidator.hasError(issues),
                "空 errorRef 在 BPMN 里表示捕获所有错误，本实现刻意不支持");
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(parsed));
        assertTrue(ex.getMessage().contains("errorCode"), ex.getMessage());
    }

    @Test
    @DisplayName("缺 attachedToRef 报 ERROR")
    void missingAttachedToRefIsRejected() {
        String xml = withBoundary("X").replace(" attachedToRef=\"approve\"", "");
        WfDefinition parsed = new WfXmlParser().parse(xml);
        assertThrows(WfDefinitionException.class, () -> repository.deploy(parsed));
    }

    @Test
    @DisplayName("挂到不存在的节点上报 ERROR")
    void danglingAttachmentIsRejected() {
        String xml = withBoundary("X").replace("attachedToRef=\"approve\"",
                "attachedToRef=\"noSuchNode\"");
        WfDefinition parsed = new WfXmlParser().parse(xml);
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(parsed));
        assertTrue(ex.getMessage().contains("noSuchNode"), ex.getMessage());
    }

    @Test
    @DisplayName("边界事件有入线时报 ERROR —— 它不是普通流程节点")
    void boundaryWithIncomingFlowIsRejected() {
        String xml = withBoundary("X").replace(
                "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n",
                "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
                + "    <sequenceFlow id=\"f5\" sourceRef=\"s1\" targetRef=\"onFail\"/>\n");
        WfDefinition parsed = new WfXmlParser().parse(xml);
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(parsed));
        assertTrue(ex.getMessage().contains("不该有入线"), ex.getMessage());
    }

    @Test
    @DisplayName("边界事件没有出线时报 ERROR")
    void boundaryWithoutOutgoingIsRejected() {
        String xml = withBoundary("X").replace(
                "    <sequenceFlow id=\"f3\" sourceRef=\"onFail\" targetRef=\"cleanup\"/>\n", "");
        WfDefinition parsed = new WfXmlParser().parse(xml);
        WfDefinitionException ex = assertThrows(WfDefinitionException.class,
                () -> repository.deploy(parsed));
        assertTrue(ex.getMessage().contains("没有出线"), ex.getMessage());
    }
}
