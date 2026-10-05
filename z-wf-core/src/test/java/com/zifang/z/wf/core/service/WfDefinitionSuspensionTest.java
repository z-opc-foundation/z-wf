package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.persistence.WfProcessInstanceQuery;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 定义停用 / 启用 / 模型回读。
 *
 * <p>这个类盯的是三件事，其中第一件最容易做成"有字段没生效"：
 * <ol>
 *   <li><b>停用必须真的挡住启动</b>。只加一个 {@code suspended} 标志位而不去启动路径上读它，
 *       就是一个"看着能用、实际不生效"的字段 —— 业务以为老版本已经下架，
 *       单子照收不误，而且没有任何报错。</li>
 *   <li><b>已在跑的实例不受影响</b>。停用是"下架版本"，不是"终止在跑的单"，
 *       两件事混起来会让停用变成一个破坏性操作。</li>
 *   <li><b>元数据要从库里读得回来</b>。原始 XML 与部署时间存在列里，
 *       而读路径只取图 JSON 时，这两个字段恒为 null，
 *       表现为"getProcessModel 拿不到 XML"，从表结构上完全看不出原因。</li>
 * </ol>
 *
 * <p>服务层语义在内存实现上跑（JDBC 那套由 JdbcWorkflowPersistenceTest 在 H2 上对拍）。
 */
class WfDefinitionSuspensionTest {

    private static final String BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"leaveProcess\" name=\"请假流程\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"领导审批\" zifang:assignee=\"boss\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private WfPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        runtime = new WfRuntimeService(repository, repo, new WfEngine(), new WfHookDispatcher());
    }

    private WfDefinition deploy() {
        return repository.deployXml(BPMN, "leaveProcess");
    }

    // ==================== 停用真的挡住启动 ====================

    @Test
    @DisplayName("停用后启动被拒，启用后又能启动")
    void suspendedDefinitionRejectsNewInstances() {
        WfDefinition definition = deploy();
        assertNotNull(runtime.startProcessInstance(definition, "OK-1", "alice", null,
                new HashMap<String, Object>()), "停用前应当能启动");

        repository.suspendDefinition("leaveProcess", definition.getVersion());
        assertTrue(repository.getDefinition("leaveProcess", definition.getVersion()).isSuspended(),
                "停用状态必须读得回来。读不回来的话启动闸门形同虚设");

        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> runtime.startProcessInstance(definition, "OK-2", "alice", null,
                        new HashMap<String, Object>()),
                "停用的版本必须拒绝新实例");
        assertTrue(e.getMessage().contains("已停用"),
                "报错要说清是停用，调用方才知道该去启用而不是去查流程定义。实际: " + e.getMessage());

        repository.activateDefinition("leaveProcess", definition.getVersion());
        assertNotNull(runtime.startProcessInstance(definition, "OK-3", "alice", null,
                new HashMap<String, Object>()), "启用后应当恢复");
    }

    @Test
    @DisplayName("闸门判的是库里那份，不是调用方手里那份")
    void gateUsesPersistedStateNotCallerObject() {
        WfDefinition definition = deploy();
        repository.suspendDefinition("leaveProcess", definition.getVersion());

        // 停用之前取到的那份定义对象，suspended 仍是 false。
        // 如果闸门拿入参判断，这就是一个"能停用但停不住"的功能。
        assertFalse(definition.isSuspended(),
                "入参对象不该被持久层回写 —— 拿到的是副本就说明拷贝边界是好的");
        assertThrows(WfDefinitionException.class,
                () -> runtime.startProcessInstance(definition, "STALE-1", "alice", null,
                        new HashMap<String, Object>()),
                "拿着停用前的旧定义对象也必须被拦住");
    }

    @Test
    @DisplayName("停用不影响已在跑的实例：待办还在，还能继续办")
    void suspensionDoesNotTouchRunningInstances() {
        WfDefinition definition = deploy();
        String pid = runtime.startProcessInstance(definition, "RUN-1", "alice", null,
                new HashMap<String, Object>());
        String taskId = firstTaskId(pid);

        repository.suspendDefinition("leaveProcess", definition.getVersion());

        WfProcessInstance stillThere = repo.findProcessInstance(pid);
        assertNotNull(stillThere, "停用是下架版本，不是终止在跑的单");
        assertTrue(stillThere.getStatus().isActive());

        // 待办仍在，办理仍能推进 —— 这是"下架"与"终止"的分界线
        runtime.completeTask(taskId, "boss", "同意", new HashMap<String, Object>());
        assertTrue(repo.findProcessInstance(pid).getStatus().isTerminal(),
                "已在跑的实例应当能正常走完");
    }

    @Test
    @DisplayName("停用不存在的版本直接报错，不静默成功")
    void suspendingUnknownVersionFails() {
        deploy();
        assertThrows(WfDefinitionException.class,
                () -> repository.suspendDefinition("leaveProcess", 99));
        assertThrows(WfDefinitionException.class,
                () -> repository.suspendDefinition("noSuchProcess", 1));
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("按名称模糊 + 停用状态过滤")
    void queryDefinitionsByNameAndSuspension() {
        deploy();
        deploy();

        assertEquals(1, repository.queryDefinitions(null, null, null).size(),
                "同一 key 的多个版本只出现最新一条");
        assertEquals(1, repository.queryDefinitions(null, "请假", null).size(),
                "按中文名模糊");
        assertEquals(0, repository.queryDefinitions(null, "报销", null).size());
        assertEquals(1, repository.queryDefinitions(null, null, Boolean.FALSE).size());
        assertEquals(0, repository.queryDefinitions(null, null, Boolean.TRUE).size());

        repository.suspendDefinition("leaveProcess", repository
                .getLatestDefinition("leaveProcess").getVersion());
        assertEquals(0, repository.queryDefinitions(null, null, Boolean.FALSE).size(),
                "停用后不该出现在[在用]列表里");
        assertEquals(1, repository.queryDefinitions(null, null, Boolean.TRUE).size());
        // 不限状态时两边的并集就是全部 —— 漏一个条件时这条会立刻炸
        assertEquals(1, repository.queryDefinitions(null, null, null).size());
    }

    // ==================== 模型回读 ====================

    @Test
    @DisplayName("回读原始 BPMN XML：从库里读回来的定义也要拿得到")
    void processModelRoundTrips() {
        WfDefinition definition = deploy();
        String xml = repository.getProcessModel("leaveProcess", definition.getVersion());
        assertTrue(xml.contains("userTask"), "回读的应是原始 XML。实际: " + xml);

        // 关键：再从持久层取一次定义，看 sourceXml 有没有被读回来。
        // 读路径只取图 JSON 时，这里恒为 null。
        WfDefinition reloaded = repo.findDefinition("leaveProcess", definition.getVersion());
        assertNotNull(reloaded.getSourceXml(),
                "sourceXml 存在 SOURCE_XML 列里，但读路径不取它 —— "
                        + "于是 getProcessModel 在真实部署上永远拿不到 XML");
        assertNotNull(reloaded.getStartTime(), "部署时间同样读不回来");
    }

    @Test
    @DisplayName("没有原始 XML 时报错，不返回空串")
    void processModelWithoutSourceFails() {
        // 直接构造的定义（不经过 deployXml）没有原始 XML
        WfDefinition definition = new WfXmlParser().parse(BPMN);
        repository.deploy(definition);
        WfDefinitionException e = assertThrows(WfDefinitionException.class,
                () -> repository.getProcessModel("leaveProcess", definition.getVersion()));
        assertTrue(e.getMessage().contains("原始 XML"),
                "报错要点明缺的是原始 XML。实际: " + e.getMessage());
    }

    private String firstTaskId(String pid) {
        List<WfTask> tasks = repo.queryTasks(new WfTaskQuery()
                .setProcessInstanceId(pid).setOpenOnly(true).setPageNum(1).setPageSize(20));
        assertEquals(1, tasks.size(), "应当恰好一只待办。实际 " + tasks);
        return tasks.get(0).getId();
    }
}
