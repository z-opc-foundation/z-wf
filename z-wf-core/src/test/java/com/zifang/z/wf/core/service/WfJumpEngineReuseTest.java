package com.zifang.z.wf.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.WfBehaviorRegistry;
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.engine.behavior.WfActivityBehavior;
import com.zifang.z.wf.core.engine.expression.WfExpressionEvaluator;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.engine.WfIdGenerator;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;

/**
 * 跳转（jump）必须用<b>运行时那一个</b>引擎。
 *
 * <p>{@code WfTaskService#jump} 曾经自己 {@code new WfEngine()}。那个构造器给的是
 * "默认装配"：自定义的行为注册表、表达式求值器配置（failOpen）、
 * id 生成器、delegate 注册表<b>全都丢</b>。症状不是报错，而是
 * "同一条流程，跳转前的节点走自定义行为、跳转后的节点走默认行为"——
 * 比如业务把 userTask 换成了"自动建任务 + 自动通知"的实现，
 * 跳转过去之后那个实现就没了，待办照建但通知不发，没有任何日志提示。
 *
 * <p>本类盯的就是这一件事：<b>换掉 {@code new WfEngine()} 之后行为要一致</b>。
 */
class WfJumpEngineReuseTest {

    private static final String BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"jumpProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"js\"/>\n"
            + "    <userTask id=\"first\" name=\"第一步\" zifang:assignee=\"alice\"/>\n"
            + "    <userTask id=\"second\" name=\"第二步\" zifang:assignee=\"bob\"/>\n"
            + "    <endEvent id=\"je\"/>\n"
            + "    <sequenceFlow id=\"jf1\" sourceRef=\"js\" targetRef=\"first\"/>\n"
            + "    <sequenceFlow id=\"jf2\" sourceRef=\"first\" targetRef=\"second\"/>\n"
            + "    <sequenceFlow id=\"jf3\" sourceRef=\"second\" targetRef=\"je\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private WfTaskService tasks;

    /** 被替换掉的默认 userTask 行为：建出来的待办名字会被改成"定制"。 */
    private final WfActivityBehavior custom = new WfActivityBehavior() {
        @Override
        public WfTask execute(WfContext context, com.zifang.z.wf.core.definition.WfNode node,
                              WfExecution token) {
            WfTask task = new WfTask();
            task.setProcessInstanceId(context.getProcessInstanceId());
            task.setDefinitionId(node.getId());
            task.setExecutionId(token.getId());
            task.setName("定制-" + node.getId());
            task.setAssignee(node.getAssignee());
            return task;
        }
    };

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        WfBehaviorRegistry behaviors = new WfBehaviorRegistry();
        behaviors.register(WfNodeType.USER_TASK, custom);
        WfEngine engine = new WfEngine(behaviors, new WfExpressionEvaluator(),
                new WfIdGenerator.DefaultWfIdGenerator());
        runtime = new WfRuntimeService(repository, repo, engine, new WfHookDispatcher());
        tasks = new WfTaskService(repository, repo, runtime, new WfHookDispatcher());
    }

    private WfTask openTask(String pid) {
        List<WfTask> open = repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(10));
        assertEquals(1, open.size(), "应当恰好有一个待办。实际 " + open.size());
        return open.get(0);
    }

    @Test
    @DisplayName("启动时走自定义行为，跳转后也必须走同一个")
    void jumpUsesTheSameCustomizedEngine() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(BPMN));
        String pid = runtime.startProcessInstance(definition, "JMP-" + System.nanoTime(),
                "carol", null, new HashMap<String, Object>());

        WfTask first = openTask(pid);
        assertEquals("定制-first", first.getName(),
                "前置条件：自定义行为确实生效了。失效的话这条用例测不到 jump");

        // 跳到 second。若 jump 自己 new 了一个默认引擎，这里的待办名就会是节点原名
        tasks.jump(first.getId(), "ops", "second", "直接跳第二步");

        WfTask second = openTask(pid);
        assertEquals("定制-second", second.getName(),
                "跳转后丢掉了自定义行为注册表：jump 自己 new 了默认引擎，"
                        + "于是同一条流程跳转前后的节点行为不是同一套");
    }

    @Test
    @DisplayName("迁移也用同一个引擎（迁到人工节点要建出定制待办）")
    void moveUsesTheSameCustomizedEngine() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(BPMN));
        String pid = runtime.startProcessInstance(definition, "JMP-" + System.nanoTime(),
                "carol", null, new HashMap<String, Object>());

        runtime.move(pid, "second", null, "ops", "迁到第二步", null);

        WfTask second = openTask(pid);
        assertEquals("定制-second", second.getName(),
                "迁移必须沿用运行时的引擎，否则迁过去之后节点行为换了一套");
    }
}
