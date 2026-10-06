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
import com.zifang.z.wf.core.definition.WfFlow;
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
            + "    <userTask id=\"colSign\" name=\"部门领导会签\""
            + " zifang:assignee=\"${leader}\">\n"
            + "      <multiInstanceLoopCharacteristics isSequential=\"true\">\n"
            + "        <collection>${leaders}</collection>\n"
            + "        <elementVariable>leader</elementVariable>\n"
            + "      </multiInstanceLoopCharacteristics>\n"
            + "    </userTask>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"sub\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"sub\" targetRef=\"colSign\"/>\n"
            + "    <sequenceFlow id=\"f8\" sourceRef=\"colSign\" targetRef=\"call\"/>\n"
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

            WfNode colSign = reloaded.node("colSign");
            assertEquals("${leaders}", colSign.getLoopCollection(), who
                    + ": collection 丢了 ⇒ 取回的定义不知道要按几个人展开，"
                    + "而部署校验会因「既无 loopCardinality 也无 collection」直接拒绝");
            assertEquals("leader", colSign.getLoopElement(), who
                    + ": elementVariable 丢了 ⇒ 办理表达式 ${leader} 取不到值，"
                    + "派出来的待办没有办理人且不报错");
            assertTrue(colSign.isSequential(), who + ": isSequential 丢了");

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

    // ==================== 结构级：防止下次再漏字段 ====================

    /**
     * 实体加了字段、codec 没跟上时，这个用例会红。
     *
     * <p>上面那些"逐字段断言"只能守住<b>当前</b>的字段集合：将来给 {@link WfNode}
     * 加一个字段、忘了加进 {@code GraphNode}，没有任何一个既有用例会红 ——
     * 因为它们断言的是各自认识的那几个字段。
     *
     * <p>所以再加这一条按<b>字段名反射比对</b>的：新字段没进 codec 立刻暴露。
     * 类型不要求一致（{@code type} 实体是枚举、codec 存字符串，是有意的转换）。
     */
    @Test
    @DisplayName("codec 覆盖实体的全部字段：加字段忘了同步会被这里抓住")
    void codecCoversEveryEntityField() {
        assertNoFieldMissing(WfNode.class, WfDefinitionCodec.GraphNode.class);
        assertNoFieldMissing(WfFlow.class, WfDefinitionCodec.GraphFlow.class);
    }

    private void assertNoFieldMissing(Class<?> entity, Class<?> codec) {
        java.util.Set<String> inEntity = businessFieldNames(entity);
        java.util.Set<String> inCodec = businessFieldNames(codec);
        java.util.Set<String> missing = new java.util.LinkedHashSet<String>(inEntity);
        missing.removeAll(inCodec);
        assertTrue(missing.isEmpty(),
                entity.getSimpleName() + " 有这些字段而 " + codec.getSimpleName()
                        + " 没有对应字段。加字段时必须两边都加，"
                        + "否则该字段在内存里活着、在库里静默消失。"
                        + "缺失字段: " + missing);
    }

    private java.util.Set<String> businessFieldNames(Class<?> type) {
        java.util.Set<String> names = new java.util.LinkedHashSet<String>();
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                // serialVersionUID 与计数器之类的静态量不在往返范围内
                continue;
            }
            if (field.isSynthetic()) {
                continue;
            }
            names.add(field.getName());
        }
        return names;
    }
}
