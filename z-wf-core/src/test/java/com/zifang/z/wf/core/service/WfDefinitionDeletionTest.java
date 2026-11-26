package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfProcessInstanceQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 物理删除流程定义（对标 z-camuda 的 deleteDeployment）。
 *
 * <p>本引擎没有独立的"部署"实体 —— 一次 deployXml 就是一条 (key, version) 定义，
 * 所以两者的单位一致。
 *
 * <p>最要紧的一条是<b>有在途实例时必须拒绝</b>，而且拒绝的理由是硬约束不是选择：
 * 每次推进都按 {@code (key, version)} 重新载入定义，定义一删，那个实例就再也推不动了
 * —— 下一次 completeTask 报"流程定义不存在"，且永远不会自愈。
 * 悄悄删掉的表现是"在办的单第二天突然办不动了"，极难定位。
 */
class WfDefinitionDeletionTest {

    private static final String BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"delProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfTaskService taskService;

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        WfHookDispatcher hooks = new WfHookDispatcher();
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), hooks);
        taskService = new WfTaskService(repository, repo, runtime, hooks);
    }

    private WfDefinition deploy() {
        return repository.deployXml(BPMN, "delProcess");
    }

    @Test
    @DisplayName("没有在途实例时可以删，删完确实读不到了")
    void deletesDefinitionWithoutRunningInstances() {
        WfDefinition definition = deploy();
        assertTrue(repository.getDefinitionVersions("delProcess").size() == 1);

        repository.deleteDefinition("delProcess", definition.getVersion());

        assertEquals(0, repository.getDefinitionVersions("delProcess").size(),
                "定义应当真的没了");
        assertThrows(WfDefinitionException.class,
                () -> repository.getDefinition("delProcess", definition.getVersion()),
                "删掉的定义再取应当报错，而不是返回 null");
    }

    @Test
    @DisplayName("有在途实例时拒绝删除，并说清该怎么处理")
    void refusesWhenInstancesAreRunning() {
        WfDefinition definition = deploy();
        String pid = runtime.startProcessInstance(definition, "DEL-1", "alice", null,
                new HashMap<String, Object>());

        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> repository.deleteDefinition("delProcess", definition.getVersion()),
                "定义一删在办的实例就推不动了，必须挡住");
        assertTrue(e.getMessage().contains("在途实例"),
                "报错要点明挡它的是在途实例。实际: " + e.getMessage());
        assertTrue(e.getMessage().contains("DEL-1"),
                "报错要带上 businessKey，调用方才知道该先处理哪一单。实际: " + e.getMessage());
        assertTrue(e.getMessage().contains("terminate") || e.getMessage().contains("suspend"),
                "报错要给下一步动作。实际: " + e.getMessage());

        // 拒绝之后什么都没变
        assertTrue(repository.getDefinition("delProcess", definition.getVersion()) != null);
        assertTrue(repo.findProcessInstance(pid).getStatus().isActive());
    }

    @Test
    @DisplayName("终止在途实例后可以删 —— 挡的是状态，不是这个 key")
    void allowsDeleteAfterTerminating() {
        WfDefinition definition = deploy();
        String pid = runtime.startProcessInstance(definition, "DEL-2", "alice", null,
                new HashMap<String, Object>());
        assertThrows(WfDefinitionException.class,
                () -> repository.deleteDefinition("delProcess", definition.getVersion()));

        runtime.terminate(pid, "作废");

        repository.deleteDefinition("delProcess", definition.getVersion());
        assertEquals(0, repository.getDefinitionVersions("delProcess").size());
    }

    @Test
    @DisplayName("只卡同一版本：老版本有在跑的，新版本照样能删")
    void onlyTheExactVersionIsBlocked() {
        WfDefinition v1 = deploy();
        runtime.startProcessInstance(v1, "OLD-1", "alice", null, new HashMap<String, Object>());

        WfDefinition v2 = deploy();
        assertEquals(2, v2.getVersion());

        // 只按 key 判断会误伤：v1 有在跑的就把 v2 也挡住
        repository.deleteDefinition("delProcess", v2.getVersion());

        // 用版本列表判断而不是 findPersisted —— 后者取不到指定版本时会**回落到最新版**，
        // 删掉 v2 之后它会返回 v1，用它断言"v2 没了"必然失败。
        List<Integer> left = new java.util.ArrayList<Integer>();
        for (WfDefinition d : repository.getDefinitionVersions("delProcess")) {
            left.add(d.getVersion());
        }
        assertEquals(1, left.size(), "应当只剩 v1 一条。实际: " + left);
        assertEquals(Integer.valueOf(1), left.get(0), "剩下的应当是 v1 而不是 v2");
    }

    @Test
    @DisplayName("删不存在的版本直接报错，不静默成功")
    void deletingUnknownVersionFails() {
        deploy();
        assertThrows(WfDefinitionException.class,
                () -> repository.deleteDefinition("delProcess", 99));
        assertThrows(WfDefinitionException.class,
                () -> repository.deleteDefinition("noSuchKey", 1));
    }

    @Test
    @DisplayName("删定义要停用优先：suspend 保留在途实例，delete 才物理删")
    void suspendAndDeleteAreDifferentTools() {
        WfDefinition definition = deploy();
        String pid = runtime.startProcessInstance(definition, "DEL-3", "alice", null,
                new HashMap<String, Object>());

        // 下架：保留在途实例，实例还能继续走完
        repository.suspendDefinition("delProcess", definition.getVersion());
        List<WfTask> todo = repo.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(pid).setOpenOnly(true).setPageNum(1).setPageSize(20));
        assertEquals(1, todo.size(), "下架不该影响在途实例");
        runtime.completeTask(todo.get(0).getId(), "boss", "同意", new HashMap<String, Object>());
        assertTrue(repo.findProcessInstance(pid).getStatus().isTerminal());
    }

    @Test
    @DisplayName("只给版本号不给 key 的查询直接拒绝，不返回'所有流程的 v1'")
    void versionOnlyQueryRejected() {
        deploy();
        // assertConsistent 是由持久层在拼 SQL 前调的，所以要走真实查询路径
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> repo.queryProcessInstances(new WfProcessInstanceQuery()
                        .setDefinitionVersion(1).setPageNum(1).setPageSize(10)),
                "只给版本会命中所有流程的同版本，返回一批无关实例");
        assertTrue(e.getMessage().contains("definitionKey"),
                "报错要点明缺什么。实际: " + e.getMessage());
    }

    @Test
    @DisplayName("按 key + 版本查实例，两套实现同口径")
    void queryInstancesByDefinitionKeyAndVersion() {
        WfDefinition v1 = deploy();
        runtime.startProcessInstance(v1, "Q-1", "alice", null, new HashMap<String, Object>());
        WfDefinition v2 = deploy();
        String pid2 = runtime.startProcessInstance(v2, "Q-2", "bob", null,
                new HashMap<String, Object>());

        WfProcessInstanceQuery query = new WfProcessInstanceQuery()
                .setDefinitionKey("delProcess").setDefinitionVersion(2)
                .setPageNum(1).setPageSize(10);
        List<WfProcessInstance> v2Instances = repo.queryProcessInstances(query);
        assertEquals(1, v2Instances.size(), "只该命中 v2 的那一单");
        assertEquals(pid2, v2Instances.get(0).getId());

        assertEquals(1, repo.countProcessInstances(query), "count 与列表必须同口径");
        assertEquals(1, repo.countProcessInstances(query.setUnfinishedOnly(true)));
    }
}
