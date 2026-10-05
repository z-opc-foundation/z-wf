package com.zifang.z.wf.core.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.definition.WfTimerType;
import com.zifang.z.wf.core.definition.WfXmlParser;

/**
 * 定义的<b>存储往返</b>保真度。
 *
 * <p><b>这一类测试存在的理由</b>：{@code WfDefinitionCodec} 是手写 DTO 的逐字段拷贝，
 * 给 {@link WfNode} 加字段时<b>编译器不会提醒</b>你也要改它；
 * 内存实现走 Java 序列化，天然保留所有字段，同样不提醒。
 * 两边都不提醒，于是"内存里对、库里错"，而开发期默认用内存实现 ——
 * 缺陷会一路活到上线，表现为部署校验通过、实例也起得来，
 * 但那个特性<b>就是不生效</b>，且没有任何一处报错。
 *
 * <p>本轮就踩了这个坑：定时器边界的 {@code timerType} / {@code timerExpression}
 * 没进 codec，JDBC 部署的流程里 {@code timerBoundariesOf} 恒返回 0，
 * 于是<b>一个 job 都不会建</b>，超时提醒永远不来。19 个内存实现的测试全绿。
 *
 * @author zifang
 */
class WfDefinitionRoundTripTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static final String TIMER_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"rtp\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"approve\" name=\"审批\" zifang:assignee=\"boss\"/>\n"
            + "    <boundaryEvent id=\"timeout\" name=\"超时\" attachedToRef=\"approve\">\n"
            + "      <timerEventDefinition>\n"
            + "        <timeDuration>PT30M</timeDuration>\n"
            + "      </timerEventDefinition>\n"
            + "    </boundaryEvent>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <endEvent id=\"e2\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"approve\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"timeout\" targetRef=\"e2\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private static final String RICH_BPMN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:zifang=\"https://zifang.com/bpmn\" targetNamespace=\"x\">\n"
            + "  <process id=\"rich\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <subProcess id=\"sub\" name=\"子流程\" zifang:loopAssignees=\"${pool}\">\n"
            + "      <multiInstanceLoopCharacteristics isSequential=\"false\">\n"
            + "        <loopCardinality>3</loopCardinality>\n"
            + "        <completionCondition>${done}</completionCondition>\n"
            + "      </multiInstanceLoopCharacteristics>\n"
            + "      <startEvent id=\"s2\"/>\n"
            + "      <endEvent id=\"e2\"/>\n"
            + "      <sequenceFlow id=\"g1\" sourceRef=\"s2\" targetRef=\"e2\"/>\n"
            + "    </subProcess>\n"
            + "    <serviceTask id=\"call\" name=\"调用\" zifang:delegateClass=\"com.x.Y\""
            + " zifang:dueDate=\"P1D\" zifang:priority=\"7\" zifang:category=\"auto\""
            + " zifang:formKey=\"f1\" zifang:requiredVariables=\"a,b\"/>\n"
            + "    <callActivity id=\"ca\" name=\"子流程调用\" zifang:resultVariable=\"rv\">\n"
            + "      <calledElement>other</calledElement>\n"
            + "    </callActivity>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"sub\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"sub\" targetRef=\"call\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"call\" targetRef=\"ca\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"ca\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private JdbcWorkflowPersistence jdbc;
    private InMemoryWorkflowPersistence memory;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:rtp_" + COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        DataSource dataSource = ds;
        jdbc = new JdbcWorkflowPersistence(dataSource);
        jdbc.initialize();
        memory = new InMemoryWorkflowPersistence();
        memory.initialize();
    }

    /** 存进去再取回来，看字段还在不在。 */
    private WfNode roundTrip(WfPersistence target, String key, WfDefinition parsed) {
        target.saveDefinition(parsed);
        WfDefinition reloaded = target.findDefinition(key, parsed.getVersion());
        assertNotNull(reloaded, key + " 应能取回");
        return reloaded.node("timeout");
    }

    @Test
    @DisplayName("定时器边界落库再取回仍然认得出：否则 JDBC 下超时提醒永远不来")
    void timerBoundarySurvivesRoundTrip() {
        for (WfPersistence target : new WfPersistence[]{jdbc, memory}) {
            WfNode node = roundTrip(target, "rtp", new WfXmlParser().parse(TIMER_BPMN));
            assertTrue(node.isTimerBoundary(),
                    target.getClass().getSimpleName() + " 取回后不是定时器边界");
            assertEquals(WfTimerType.DURATION, node.getTimerType());
            assertEquals("PT30M", node.getTimerExpression());
            assertEquals("approve", node.getAttachedToRef());
            assertEquals(WfNodeType.BOUNDARY_EVENT, node.getType());
        }
    }

    @Test
    @DisplayName("取回的定义里仍能查到宿主节点上的定时器边界")
    void timerBoundariesSurviveRoundTrip() {
        WfDefinition parsed = new WfXmlParser().parse(TIMER_BPMN);
        for (WfPersistence target : new WfPersistence[]{jdbc, memory}) {
            target.saveDefinition(parsed);
            WfDefinition reloaded = target.findDefinition("rtp", parsed.getVersion());
            assertEquals(1, reloaded.timerBoundariesOf("approve").size(),
                    target.getClass().getSimpleName()
                            + " 上 timerBoundariesOf 返回空 ⇒ 引擎不会建任何 job");
        }
    }

    @Test
    @DisplayName("多实例 / 委托 / 子流程等已有字段同样要活过往返")
    void richNodeFieldsSurviveRoundTrip() {
        WfDefinition parsed = new WfXmlParser().parse(RICH_BPMN);
        for (WfPersistence target : new WfPersistence[]{jdbc, memory}) {
            target.saveDefinition(parsed);
            WfDefinition reloaded = target.findDefinition("rich", parsed.getVersion());
            String who = target.getClass().getSimpleName();

            WfNode sub = reloaded.node("sub");
            assertTrue(sub.isMultiInstance(), who + ": multiInstance 丢了");
            assertEquals("3", sub.getLoopCardinality(), who);
            assertEquals("${done}", sub.getCompletionCondition(), who);
            assertEquals("${pool}", sub.getLoopAssignees(), who);
            assertTrue(!sub.isSequential(), who + ": isSequential 丢了");

            WfNode call = reloaded.node("call");
            assertEquals("com.x.Y", call.getDelegateClass(), who);
            assertEquals("P1D", call.getDueDateDuration(), who);
            assertEquals(7, call.getPriority(), who);
            assertEquals("auto", call.getCategory(), who);
            assertEquals("f1", call.getFormKey(), who);
            assertEquals(2, call.getRequiredVariables().size(), who);

            WfNode ca = reloaded.node("ca");
            assertEquals("other", ca.getCalledElementKey(), who);
            assertEquals("rv", ca.getResultVariable(), who);
        }
    }

    @Test
    @DisplayName("非定时器边界取回后仍然是非定时器边界（不能被误判成定时器）")
    void nonTimerBoundaryStaysNonTimer() {
        WfDefinition parsed = new WfXmlParser().parse(RICH_BPMN);
        for (WfPersistence target : new WfPersistence[]{jdbc, memory}) {
            target.saveDefinition(parsed);
            WfDefinition reloaded = target.findDefinition("rich", parsed.getVersion());
            WfNode call = reloaded.node("call");
            assertNull(call.getTimerType(), "普通任务节点不该带定时器类型");
            assertTrue(!call.isTimerBoundary());
        }
    }
}
