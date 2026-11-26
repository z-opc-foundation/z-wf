package com.zifang.z.wf.core.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.service.WfActivityInstanceService;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.view.WfActivityInstanceView;

/**
 * 活动实例树在 <b>JDBC 存储路径</b>上也要成立。
 *
 * <p><b>为什么这条要单独立一个类。</b>活动实例树的服务层是纯内存拼装，
 * 没有 JDBC 与内存两套实现 —— 所以内存用例全绿并不意味着线上也成立。
 * 树完全依赖两列：{@code ZWF_EXECUTION.PARENT_ID}（并发结构）与
 * {@code ZWF_ACTIVITY.EXEC_ID}（步骤挂到哪条 token）。
 * 任何一列<b>写了没读</b>、<b>建表时漏了</b>、<b>行映射漏了</b>，
 * 在内存实现上全都看不出来：内存对象直接持有引用，压根不经过列。
 * ⇒ 判据必须**真的走一遍 JDBC 存取**。
 *
 * <p>另外顺带钉住 token 身份：串行推进必须是<b>同一条</b> token 在换 activityId，
 * 而不是一个看起来像的 id —— id 若在往返中变了，"同一条 token"这个前提就没了，
 * 而树的所有层级判断都建立在它上面。
 */
class JdbcActivityInstanceTreeTest {

    private static final String PAR_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"jdbcTreePar\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <parallelGateway id=\"pg\"/>\n"
            + "    <userTask id=\"a1\" name=\"甲\" zifang:assignee=\"alice\"/>\n"
            + "    <userTask id=\"b1\" name=\"乙\" zifang:assignee=\"bob\"/>\n"
            + "    <parallelGateway id=\"jg\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"pg\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"pg\" targetRef=\"a1\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"pg\" targetRef=\"b1\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"a1\" targetRef=\"jg\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"b1\" targetRef=\"jg\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"jg\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private static final String SEQ_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"jdbcTreeSeq\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s\"/>\n"
            + "    <userTask id=\"t1\" name=\"一级\" zifang:assignee=\"alice\"/>\n"
            + "    <userTask id=\"t2\" name=\"二级\" zifang:assignee=\"bob\"/>\n"
            + "    <endEvent id=\"e\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t1\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"t1\" targetRef=\"t2\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"t2\" targetRef=\"e\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private JdbcWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfActivityInstanceService tree;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:wf_tree_" + COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        repo = new JdbcWorkflowPersistence(ds);
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
        tree = new WfActivityInstanceService(repo, repository);
    }

    @Test
    @DisplayName("JDBC 上并行结构成立 —— PARENT_ID 写了也要读得回来")
    void parallelStructureSurvivesJdbcRoundTrip() {
        String pid = start(PAR_BPMN, "jdbcTreePar", "JDBC-PAR-1");
        WfActivityInstanceView root = tree.getActivityInstance(pid);

        assertEquals(1, root.getChildActivityInstances().size(),
                "PARENT_ID 没读回来的话，两条 token 会各自变成顶层，树就从中间断开");
        WfActivityInstanceView parent = root.getChildActivityInstances().get(0);
        assertEquals("a1", parent.getActivityId());
        assertEquals(root.getId(), parent.getParentActivityInstanceId());

        assertEquals(1, parent.getChildActivityInstances().size(),
                "子 token 在 JDBC 上丢了 —— 读路径的 PARENT_ID 或 IS_CHILD 有问题");
        WfActivityInstanceView child = parent.getChildActivityInstances().get(0);
        assertEquals("b1", child.getActivityId());
        assertEquals(parent.getId(), child.getParentActivityInstanceId());
        assertTrue(parent.isConcurrent() && child.isConcurrent(),
                "两条都没结束，JDBC 上也要标出并发");

        // 节点名与类型要能从库里读回的定义里解析出来（走的是持久化后的定义，不是内存那份）
        assertEquals("甲", parent.getActivityName());
        assertEquals("userTask", parent.getActivityType());
    }

    @Test
    @DisplayName("JDBC 上步骤能挂到对应 token —— 活动历史的 EXEC_ID 要对得上")
    void transitionsAttachToRightTokenOverJdbc() {
        String pid = start(PAR_BPMN, "jdbcTreePar", "JDBC-PAR-2");
        complete(pid, "alice", "甲批了");

        WfActivityInstanceView root = tree.getActivityInstance(pid);
        WfActivityInstanceView parent = root.getChildActivityInstances().get(0);
        WfActivityInstanceView child = parent.getChildActivityInstances().get(0);

        // 父 token 走过的：s（起始）与 a1（甲批了）
        assertEquals(2, parent.getChildTransitionInstances().size(),
                "父 token 应当挂着起始与「甲」两步；少了就是活动历史的 EXEC_ID 对不上 token");
        assertEquals("s", parent.getChildTransitionInstances().get(0).getActivityId());
        assertEquals("a1", parent.getChildTransitionInstances().get(1).getActivityId());
        assertEquals("甲批了", parent.getChildTransitionInstances().get(1).getOutcome());

        // 子 token 是在并行网关处 fork 出来的，**没走过起始节点**：
        // 起始与并行网关那两步都记在父 token 上。所以它的步骤表此刻应当是空的。
        // 这条容易被想当然地写成「子 token 也有 s 那一步」——
        // 那样断言会建立一个数据不具备的属性。
        assertEquals(0, child.getChildTransitionInstances().size(),
                "fork 出来的子 token 从没经过起始节点，它的步骤表此刻必须为空；"
                        + "若这里有内容，说明按 executionId 归组写坏了"
                        + "（实际挂上来的是：" + child.getChildTransitionInstances() + "）");
        assertEquals("b1", child.getActivityId());

        assertEquals("jg", parent.getActivityId());
        assertEquals("parallelGateway", parent.getActivityType(),
                "网关的名字与类型在 JDBC 上同样要解析得出来");
    }

    @Test
    @DisplayName("JDBC 上 token 身份稳定：串行推进是同一条，不是每步换一个 id")
    void tokenIdentityIsStableOverJdbc() {
        String pid = start(SEQ_BPMN, "jdbcTreeSeq", "JDBC-SEQ-1");
        WfActivityInstanceView first = tree.getActivityInstance(pid)
                .getChildActivityInstances().get(0);
        String tokenId = first.getId();
        assertNotNull(tokenId);
        assertEquals("t1", first.getActivityId());

        complete(pid, "alice", "一级批了");

        WfActivityInstanceView second = tree.getActivityInstance(pid)
                .getChildActivityInstances().get(0);
        assertEquals(tokenId, second.getId(),
                "token id 在 JDBC 往返里变了；树的所有层级判断都建立在 id 稳定之上");
        assertNotEquals("t1", second.getActivityId());
        assertEquals("t2", second.getActivityId());
        assertEquals(2, second.getChildTransitionInstances().size(),
                "s 与 t1 两步都该在 JDBC 往返后挂得上");
        assertEquals("一级批了", second.getChildTransitionInstances().get(1).getOutcome());
    }

    // ==================== 夹具 ====================

    private String start(String xml, String key, String businessKey) {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(xml));
        return runtime.startProcessInstance(definition, businessKey, null, null,
                new HashMap<String, Object>());
    }

    private void complete(String pid, String assignee, String comment) {
        WfTask task = null;
        for (WfTask candidate : repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(20))) {
            if (assignee.equals(candidate.getAssignee())) {
                task = candidate;
                break;
            }
        }
        assertNotNull(task, "流程 " + pid + " 上找不到 " + assignee + " 的待办");
        runtime.completeTask(task.getId(), assignee, comment, new HashMap<String, Object>());
    }

}