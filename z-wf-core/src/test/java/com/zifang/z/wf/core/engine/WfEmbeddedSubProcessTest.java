package com.zifang.z.wf.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.WfDefinitionValidator;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.definition.WfValidationIssue;
import com.zifang.z.wf.core.definition.WfXmlParser;
import com.zifang.z.wf.core.engine.expression.WfExpressionEvaluator;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfProcessStatus;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfTaskQuery;
import com.zifang.z.wf.core.service.WfDelegateRegistry;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;

/**
 * 嵌入式 subProcess —— 画在 {@code <subProcess>} 里的内联子图。
 *
 * <p>与 callActivity 的根本差别：内联子流程<b>展开在父实例的同一棵 token 树里</b>，
 * 不建子实例，所以它不需要独立的 instanceId、父子关联与「子流程结束通知父流程」那条回调链。
 *
 * <p>本类盯七件错了都不报错的事：
 * <ol>
 *   <li><b>内联内容真的执行了</b>：进入 subProcess 时 token 要落到内联起始节点上，
 *       而不是直接穿透到 subProcess 的出线。</li>
 *   <li><b>内联结束事件不结束流程</b>：它是「子流程到此为止」，token 要回到容器
 *       沿容器自己的出线继续走主图。少这一道分派，subProcess 之后的节点一个都不跑，
 *       而实例状态是 COMPLETED。</li>
 *   <li><b>内联的起始/结束不冒充流程级入口</b>：解析结果是扁平表，
 *       「容器内无入线」与「流程内无入线」判据完全一样，不排除的话
 *       画一个内联子流程就会凭空多出第二个无条件开始节点。</li>
 *   <li><b>内层汇合不等外层分支</b>：内联子流程的分支 token 与外层并行分支的 token
 *       可能同父（父 token 停在 subProcess 上），只按 parentId 判汇合会让内层的 join
 *       永远等不齐，流程卡在子流程里且看不出任何异常。</li>
 *   <li><b>变量与流程级共享</b>：内联节点没有独立作用域，
 *       内层 serviceTask 写的变量主图读得到（与 Camunda 的显式差异）。</li>
 *   <li><b>不建新的 execution</b>：内联子流程展开在父 token 树里，
 *       多建一条执行记录会让 token 树的父子关系对不上轨迹。</li>
 *   <li><b>五种写法在部署期挡住</b>：两个内联起始、两个内联结束、嵌套、容器上挂边界事件、
 *       既画内联又配 calledElementKey；另有「容器元素没写 id」这一类归属失效。</li>
 * </ol>
 */
class WfEmbeddedSubProcessTest {

    private static final String NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /**
     * 主流程：发起前审批 → 内联子流程 → 子流程之后 → 结束。
     *
     * <p>子流程内部是 startEvent → userTask → serviceTask（写变量）→ endEvent，
     * 一个最普通的内联子图，够验「进得去、跑得完、出得来」。
     */
    private static final String EMBEDDED_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"embeddedProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <userTask id=\"before\" name=\"发起前审批\" zifang:assignee=\"alice\"/>\n"
            + "    <subProcess id=\"sp\" name=\"内联子流程\">\n"
            + "      <startEvent id=\"iStart\"/>\n"
            + "      <userTask id=\"iTask\" name=\"内联审批\" zifang:assignee=\"bob\"/>\n"
            + "      <serviceTask id=\"iSvc\" name=\"内联写变量\""
            + " zifang:delegateExpression=\"stamp\"/>\n"
            + "      <endEvent id=\"iEnd\"/>\n"
            + "      <sequenceFlow id=\"if1\" sourceRef=\"iStart\" targetRef=\"iTask\"/>\n"
            + "      <sequenceFlow id=\"if2\" sourceRef=\"iTask\" targetRef=\"iSvc\"/>\n"
            + "      <sequenceFlow id=\"if3\" sourceRef=\"iSvc\" targetRef=\"iEnd\"/>\n"
            + "    </subProcess>\n"
            + "    <userTask id=\"after\" name=\"子流程之后\" zifang:assignee=\"carol\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"before\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"before\" targetRef=\"sp\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"sp\" targetRef=\"after\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"after\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /**
     * 外层并行 + 内联子流程内部再并行 —— 汇合判定的分段回归。
     *
     * <p>刻意让内层分支与外层分支<b>同父</b>：外层并行网关分出「走子流程」与「走外面」两条，
     * 走子流程那条（父 token）停在 subProcess 上，内层并行网关再从它分出两条。
     * 于是 ia / ib / outer 三条 token 的 parentId 全都指向同一条父 token。
     * 判据是<b>内层的 join 必须在 ib 还没办结时就等着</b>，而外层那条办没办结都不影响它。
     */
    private static final String NESTED_PARALLEL_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"nestedParallelProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <parallelGateway id=\"pga\"/>\n"
            + "    <subProcess id=\"sp\" name=\"内联子流程\">\n"
            + "      <startEvent id=\"iStart\"/>\n"
            + "      <parallelGateway id=\"iFork\"/>\n"
            + "      <userTask id=\"ia\" name=\"内层甲\" zifang:assignee=\"alice\"/>\n"
            + "      <userTask id=\"ib\" name=\"内层乙\" zifang:assignee=\"bob\"/>\n"
            + "      <parallelGateway id=\"iJoin\"/>\n"
            + "      <endEvent id=\"iEnd\"/>\n"
            + "      <sequenceFlow id=\"if1\" sourceRef=\"iStart\" targetRef=\"iFork\"/>\n"
            + "      <sequenceFlow id=\"if2\" sourceRef=\"iFork\" targetRef=\"ia\"/>\n"
            + "      <sequenceFlow id=\"if3\" sourceRef=\"iFork\" targetRef=\"ib\"/>\n"
            + "      <sequenceFlow id=\"if4\" sourceRef=\"ia\" targetRef=\"iJoin\"/>\n"
            + "      <sequenceFlow id=\"if5\" sourceRef=\"ib\" targetRef=\"iJoin\"/>\n"
            + "      <sequenceFlow id=\"if6\" sourceRef=\"iJoin\" targetRef=\"iEnd\"/>\n"
            + "    </subProcess>\n"
            + "    <userTask id=\"outer\" name=\"外层\" zifang:assignee=\"carol\"/>\n"
            + "    <parallelGateway id=\"pgb\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"pga\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"pga\" targetRef=\"sp\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"pga\" targetRef=\"outer\"/>\n"
            + "    <sequenceFlow id=\"f4\" sourceRef=\"sp\" targetRef=\"pgb\"/>\n"
            + "    <sequenceFlow id=\"f5\" sourceRef=\"outer\" targetRef=\"pgb\"/>\n"
            + "    <sequenceFlow id=\"f6\" sourceRef=\"pgb\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 内联子流程里没有人工任务：一口气跑完，主图后面的节点直接就该到。 */
    private static final String NO_TASK_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"noTaskProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <subProcess id=\"sp\" name=\"内联子流程\">\n"
            + "      <startEvent id=\"iStart\"/>\n"
            + "      <serviceTask id=\"iSvc\" name=\"内联写变量\""
            + " zifang:delegateExpression=\"stamp\"/>\n"
            + "      <endEvent id=\"iEnd\"/>\n"
            + "      <sequenceFlow id=\"if1\" sourceRef=\"iStart\" targetRef=\"iSvc\"/>\n"
            + "      <sequenceFlow id=\"if2\" sourceRef=\"iSvc\" targetRef=\"iEnd\"/>\n"
            + "    </subProcess>\n"
            + "    <userTask id=\"after\" name=\"子流程之后\" zifang:assignee=\"carol\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"sp\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"sp\" targetRef=\"after\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"after\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** subProcess 是流程的最后一步：没有出线，内联跑完 token 就该结束。 */
    private static final String TAIL_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"tailProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <subProcess id=\"sp\" name=\"内联子流程\">\n"
            + "      <startEvent id=\"iStart\"/>\n"
            + "      <serviceTask id=\"iSvc\" name=\"内联写变量\""
            + " zifang:delegateExpression=\"stamp\"/>\n"
            + "      <endEvent id=\"iEnd\"/>\n"
            + "      <sequenceFlow id=\"if1\" sourceRef=\"iStart\" targetRef=\"iSvc\"/>\n"
            + "      <sequenceFlow id=\"if2\" sourceRef=\"iSvc\" targetRef=\"iEnd\"/>\n"
            + "    </subProcess>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"sp\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /** 容器里画了两个开始事件：引擎无从判断该从哪进入。 */
    private static final String TWO_ENTRIES_BPMN = EMBEDDED_BPMN
            .replace("      <startEvent id=\"iStart\"/>\n",
                    "      <startEvent id=\"iStart\"/>\n"
                            + "      <startEvent id=\"iStart2\"/>\n")
            .replace("      <sequenceFlow id=\"if1\" sourceRef=\"iStart\" targetRef=\"iTask\"/>\n",
                    "      <sequenceFlow id=\"if1\" sourceRef=\"iStart\" targetRef=\"iTask\"/>\n"
                            + "      <sequenceFlow id=\"if0\" sourceRef=\"iStart2\" targetRef=\"iTask\"/>\n")
            .replace("id=\"embeddedProcess\"", "id=\"twoEntriesProcess\"");

    /** 容器里画了两个结束事件：token 会在第一个结束处跳出，后半段跑不到。 */
    private static final String TWO_EXITS_BPMN = EMBEDDED_BPMN
            .replace("      <endEvent id=\"iEnd\"/>\n",
                    "      <endEvent id=\"iEnd\"/>\n"
                            + "      <endEvent id=\"iEnd2\"/>\n")
            .replace("      <sequenceFlow id=\"if3\" sourceRef=\"iSvc\" targetRef=\"iEnd\"/>\n",
                    "      <sequenceFlow id=\"if3\" sourceRef=\"iSvc\" targetRef=\"iEnd\"/>\n"
                            + "      <sequenceFlow id=\"if4\" sourceRef=\"iSvc\" targetRef=\"iEnd2\"/>\n")
            .replace("id=\"embeddedProcess\"", "id=\"twoExitsProcess\"");

    /** 容器里又嵌了一个 subProcess：内层子流程的结束点在结构上认不出来。 */
    private static final String NESTED_SUB_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"nestedSubProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <subProcess id=\"sp\" name=\"外层子流程\">\n"
            + "      <startEvent id=\"iStart\"/>\n"
            + "      <subProcess id=\"inner\" name=\"内层子流程\">\n"
            + "        <startEvent id=\"nStart\"/>\n"
            + "        <userTask id=\"nTask\" name=\"内层任务\" zifang:assignee=\"bob\"/>\n"
            + "        <endEvent id=\"nEnd\"/>\n"
            + "        <sequenceFlow id=\"nf1\" sourceRef=\"nStart\" targetRef=\"nTask\"/>\n"
            + "        <sequenceFlow id=\"nf2\" sourceRef=\"nTask\" targetRef=\"nEnd\"/>\n"
            + "      </subProcess>\n"
            + "      <endEvent id=\"iEnd\"/>\n"
            + "      <sequenceFlow id=\"if1\" sourceRef=\"iStart\" targetRef=\"inner\"/>\n"
            + "      <sequenceFlow id=\"if2\" sourceRef=\"inner\" targetRef=\"iEnd\"/>\n"
            + "    </subProcess>\n"
            + "    <userTask id=\"after\" name=\"子流程之后\" zifang:assignee=\"carol\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"sp\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"sp\" targetRef=\"after\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"after\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /**
     * 边界事件挂在 subProcess 上：token 一进容器就被推进到内联起始节点，
     * 此后不再停留在容器上，而 {@code fireEventBoundary} 要求 token 仍停在宿主才触发。
     */
    private static final String BOUNDARY_ON_SUB_BPMN = EMBEDDED_BPMN
            .replace("      <startEvent id=\"iStart\"/>\n",
                    "      <boundaryEvent id=\"onTimeout\" attachedToRef=\"sp\">\n"
                            + "        <timerEventDefinition><timeDuration>PT30M</timeDuration>"
                            + "</timerEventDefinition>\n"
                            + "      </boundaryEvent>\n"
                            + "      <startEvent id=\"iStart\"/>\n")
            .replace("      <sequenceFlow id=\"if3\" sourceRef=\"iSvc\" targetRef=\"iEnd\"/>\n",
                    "      <sequenceFlow id=\"if3\" sourceRef=\"iSvc\" targetRef=\"iEnd\"/>\n"
                            + "      <sequenceFlow id=\"if4\" sourceRef=\"onTimeout\" targetRef=\"iEnd\"/>\n")
            .replace("id=\"embeddedProcess\"", "id=\"boundaryOnSubProcess\"");

    /** 既画了内联内容又配 calledElementKey：两者互斥，引擎只认内联。 */
    private static final String BOTH_INLINE_AND_KEY_BPMN = EMBEDDED_BPMN
            .replace("<subProcess id=\"sp\" name=\"内联子流程\">",
                    "<subProcess id=\"sp\" name=\"内联子流程\" zifang:calledElement=\"otherProcess\">")
            .replace("id=\"embeddedProcess\"", "id=\"bothInlineProcess\"");

    /**
     * 内联容器元素没写 id：解析器只能记下元素名占位，内联节点从此认不出自己属于谁，
     * 引擎的内联查询按 id 匹配会一个都查不到 ⇒ subProcess 被当成空容器直接穿透。
     */
    private static final String NO_CONTAINER_ID_BPMN = EMBEDDED_BPMN
            .replace("<subProcess id=\"sp\" name=\"内联子流程\">", "<subProcess name=\"内联子流程\">")
            .replace("id=\"embeddedProcess\"", "id=\"noContainerIdProcess\"");

    /**
     * 空 subProcess + calledElementKey —— 回归：这仍然按 callActivity 语义处理，
     * 走的是「启动独立子实例」那条路，不该被内联子流程的规则误伤。
     */
    private static final String EMPTY_SUB_WITH_KEY_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"emptySubProcess\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"s1\"/>\n"
            + "    <subProcess id=\"sp\" name=\"空容器\" zifang:calledElement=\"subFlow\"/>\n"
            + "    <userTask id=\"after\" name=\"子流程之后\" zifang:assignee=\"carol\"/>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"s1\" targetRef=\"sp\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"sp\" targetRef=\"after\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"after\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    /**
     * 边界事件挂在内联的某个活动上 —— 完全正常的写法，恰恰是最容易出错的一种。
     *
     * <p>解析器记的 nestedIn 是"直接父元素"，而 boundaryEvent 在 BPMN 里就写在宿主活动内部，
     * 所以它的容器是 iTask 而<b>不是</b> sp。所有"认不认得出内联归属"的判定都得
     * 在这里多看一眼容器类型，否则每个边界事件都会被当成"嵌在某个 userTask 里的内联节点"。
     */
    private static final String BOUNDARY_ON_INLINE_TASK_BPMN = EMBEDDED_BPMN
            .replace("      <userTask id=\"iTask\" name=\"内联审批\" zifang:assignee=\"bob\"/>\n",
                    "      <userTask id=\"iTask\" name=\"内联审批\" zifang:assignee=\"bob\">\n"
                            + "        <boundaryEvent id=\"beI\" attachedToRef=\"iTask\">\n"
                            + "          <timerEventDefinition><timeDuration>PT30M</timeDuration>"
                            + "</timerEventDefinition>\n"
                            + "        </boundaryEvent>\n"
                            + "      </userTask>\n")
            .replace("      <sequenceFlow id=\"if3\" sourceRef=\"iSvc\" targetRef=\"iEnd\"/>\n",
                    "      <sequenceFlow id=\"if3\" sourceRef=\"iSvc\" targetRef=\"iEnd\"/>\n"
                            + "      <sequenceFlow id=\"if4\" sourceRef=\"beI\" targetRef=\"iEnd\"/>\n")
            .replace("id=\"embeddedProcess\"", "id=\"boundaryOnInlineTaskProcess\"");

    /**
     * 同一个「内联活动挂超时边界」，但 boundaryEvent 写在 <b>{@code <subProcess>} 层级下</b>
     * 而不是宿主活动内部 —— BPMN 两种写法都合法，Camunda Modeler 导出的是这一种。
     *
     * <p>与 {@link #BOUNDARY_ON_INLINE_TASK_BPMN} 唯一的差别就是那个 boundaryEvent 在 XML
     * 里缩在哪一层，{@code attachedToRef} 都是 {@code iTask}。
     * 两条夹具必须一起用：只看其中一条，校验器按哪种位置判都"有测试覆盖"，
     * 而另一种位置会静默判错。
     */
    private static final String BOUNDARY_AT_SUB_LEVEL_BPMN = EMBEDDED_BPMN
            .replace("      <startEvent id=\"iStart\"/>\n",
                    "      <startEvent id=\"iStart\"/>\n"
                            + "      <boundaryEvent id=\"beI\" attachedToRef=\"iTask\">\n"
                            + "        <timerEventDefinition><timeDuration>PT30M</timeDuration>"
                            + "</timerEventDefinition>\n"
                            + "      </boundaryEvent>\n")
            .replace("      <sequenceFlow id=\"if3\" sourceRef=\"iSvc\" targetRef=\"iEnd\"/>\n",
                    "      <sequenceFlow id=\"if3\" sourceRef=\"iSvc\" targetRef=\"iEnd\"/>\n"
                            + "      <sequenceFlow id=\"if4\" sourceRef=\"beI\" targetRef=\"iEnd\"/>\n")
            .replace("id=\"embeddedProcess\"", "id=\"boundaryAtSubLevelProcess\"");

    /**
     * 内联子图里的死胡同：{@code iDead} 有入线、没有出线。
     *
     * <p>它是 userTask 不是 endEvent，所以<b>不是</b>子流程的出口。
     * 判据是「报死胡同，且不要报成结束点不唯一」——
     * 后者是本仓库曾经给出的答案，而它会把作者引向一个不存在的第二个结束节点。
     */
    private static final String DEAD_END_IN_INLINE_BPMN = EMBEDDED_BPMN
            .replace("      <serviceTask id=\"iSvc\" name=\"内联写变量\""
                    + " zifang:delegateExpression=\"stamp\"/>\n",
                    "      <serviceTask id=\"iSvc\" name=\"内联写变量\""
                            + " zifang:delegateExpression=\"stamp\"/>\n"
                            + "      <userTask id=\"iDead\" name=\"死胡同\""
                            + " zifang:assignee=\"dave\"/>\n")
            .replace("      <sequenceFlow id=\"if3\" sourceRef=\"iSvc\" targetRef=\"iEnd\"/>\n",
                    "      <sequenceFlow id=\"if3\" sourceRef=\"iSvc\" targetRef=\"iEnd\"/>\n"
                            + "      <sequenceFlow id=\"if5\" sourceRef=\"iSvc\" targetRef=\"iDead\"/>\n")
            .replace("id=\"embeddedProcess\"", "id=\"deadEndInInlineProcess\"");


    /**
     * 定义里<b>不写</b> startEvent，入口靠「无入线节点」退化判定。
     *
     * <p>两种退化形态要分开验：显式 startEvent 那条路和退化那条路都排除了内联节点，
     * 而画了内联子流程的定义<b>总是</b>能命中第一种，于是退化那条路在别的用例里
     * 永远走不到 —— 少写一个 startEvent 就凭空多出第二个入口。
     * 顺带把一个挂在主图 userTask 上的边界事件也放进来：它同样无入线。
     */
    private static final String NO_START_EVENT_BPMN =
            "<definitions xmlns=\"" + NS + "\" xmlns:zifang=\"https://zifang.com/bpmn\""
            + " targetNamespace=\"x\">\n"
            + "  <process id=\"noStartEventProcess\" isExecutable=\"true\">\n"
            + "    <userTask id=\"before\" name=\"发起前审批\" zifang:assignee=\"alice\">\n"
            + "      <boundaryEvent id=\"beMain\" attachedToRef=\"before\">\n"
            + "        <timerEventDefinition><timeDuration>PT30M</timeDuration>"
            + "</timerEventDefinition>\n"
            + "      </boundaryEvent>\n"
            + "    </userTask>\n"
            + "    <subProcess id=\"sp\" name=\"内联子流程\">\n"
            + "      <startEvent id=\"iStart\"/>\n"
            + "      <serviceTask id=\"iSvc\" name=\"内联写变量\""
            + " zifang:delegateExpression=\"stamp\"/>\n"
            + "      <endEvent id=\"iEnd\"/>\n"
            + "      <sequenceFlow id=\"if1\" sourceRef=\"iStart\" targetRef=\"iSvc\"/>\n"
            + "      <sequenceFlow id=\"if2\" sourceRef=\"iSvc\" targetRef=\"iEnd\"/>\n"
            + "    </subProcess>\n"
            + "    <endEvent id=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f1\" sourceRef=\"before\" targetRef=\"sp\"/>\n"
            + "    <sequenceFlow id=\"f2\" sourceRef=\"sp\" targetRef=\"e1\"/>\n"
            + "    <sequenceFlow id=\"f3\" sourceRef=\"beMain\" targetRef=\"e1\"/>\n"
            + "  </process>\n"
            + "</definitions>\n";

    private InMemoryWorkflowPersistence repo;
    private WfRepositoryService repository;
    private WfRuntimeService runtime;
    private final List<String> delegateCalls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repo = new InMemoryWorkflowPersistence();
        repo.initialize();
        repository = new WfRepositoryService(repo);
        WfDelegateRegistry delegates = new WfDelegateRegistry();
        delegateCalls.clear();
        delegates.register("stamp", (ctx, ex) -> {
            delegateCalls.add(ex.getActivityId());
            ctx.setVariable("stampedBy", ex.getActivityId());
        });
        WfEngine engine = new WfEngine(new WfBehaviorRegistry(),
                new WfExpressionEvaluator(), new WfIdGenerator.DefaultWfIdGenerator(), delegates);
        runtime = new WfRuntimeService(repository, repo, engine, new WfHookDispatcher());
    }

    private String deploy(String xml) {
        return repository.deploy(new WfXmlParser().parse(xml)).getKey();
    }

    private String start(String xml) {
        return runtime.startProcessInstance(repository.deploy(new WfXmlParser().parse(xml)),
                "BIZ-" + System.nanoTime(), "alice", null, new HashMap<String, Object>());
    }

    private List<WfTask> openTasks(String pid) {
        return repo.queryTasks(new WfTaskQuery().setProcessInstanceId(pid)
                .setOpenOnly(true).setPageNum(1).setPageSize(50));
    }

    private WfTask taskAt(String pid, String activityId) {
        for (WfTask task : openTasks(pid)) {
            if (activityId.equals(task.getDefinitionId())) {
                return task;
            }
        }
        return null;
    }

    private void complete(String pid, String activityId, String userId) {
        WfTask task = taskAt(pid, activityId);
        assertNotNull(task, "节点 " + activityId + " 上应当有可办理的待办");
        runtime.completeTask(task.getId(), userId, "办完了", new HashMap<String, Object>());
    }

    /** 轨迹上出现过的 activityId，按出现顺序。 */
    private List<String> trailActivityIds(String pid) {
        List<String> ids = new ArrayList<>();
        for (WfActivityInstance activity : runtime.getTrail(pid)) {
            ids.add(activity.getActivityId());
        }
        return ids;
    }

    // ==================== 跑得起来 ====================

    @Test
    @DisplayName("进入 subProcess 落到内联起始节点，而不是穿透到它的出线")
    void enteringSubProcessRunsTheInlineGraph() {
        String pid = start(EMBEDDED_BPMN);

        complete(pid, "before", "alice");

        WfTask inner = taskAt(pid, "iTask");
        assertNotNull(inner, "进入 subProcess 后应当停在它的内联 userTask 上");
        assertEquals("bob", inner.getAssignee(), "内联节点的办理人应当照常解析");
        assertNull(taskAt(pid, "after"),
                "内联还没跑完，主图后面的节点不该有待办 —— 穿透就说明没执行内联内容");
    }

    @Test
    @DisplayName("内联跑完后回到容器，沿容器的出线继续走主图")
    void inlineEndEventContinuesThroughTheContainer() {
        String pid = start(EMBEDDED_BPMN);
        complete(pid, "before", "alice");

        complete(pid, "iTask", "bob");

        assertNotNull(taskAt(pid, "after"),
                "内联结束后 token 必须回到 subProcess 再沿出线走 —— "
                        + "内联 endEvent 若直接把 token 结束掉，主图后半段一个节点都不会跑");
        assertEquals(WfProcessStatus.ACTIVE, repo.findProcessInstance(pid).getStatus());
    }

    @Test
    @DisplayName("内联结束事件不把流程判为完成")
    void inlineEndDoesNotCompleteTheInstance() {
        String pid = start(EMBEDDED_BPMN);
        complete(pid, "before", "alice");
        complete(pid, "iTask", "bob");

        WfProcessInstance instance = repo.findProcessInstance(pid);
        assertFalse(instance.getStatus().isTerminal(),
                "流程还挂在子流程之后的节点上，实例不该是终态");
    }

    @Test
    @DisplayName("内联里没有人工任务时，一口气跑到主图后面")
    void inlineWithoutTasksRunsThrough() {
        String pid = start(NO_TASK_BPMN);

        assertEquals(1, delegateCalls.size(), "内联 serviceTask 应当被执行一次");
        assertEquals("iSvc", delegateCalls.get(0), "执行 delegate 的应当是内联节点本身");
        assertNotNull(taskAt(pid, "after"),
                "内联一口气跑完就该到主图后面的节点");
        assertEquals("iSvc", repo.findProcessInstance(pid).getVariables().get("stampedBy"),
                "内联节点应当出现在同一条执行上");
    }

    @Test
    @DisplayName("subProcess 是最后一步：内联跑完流程即结束")
    void tailSubProcessEndsTheInstance() {
        String pid = start(TAIL_BPMN);

        WfProcessInstance instance = repo.findProcessInstance(pid);
        assertEquals(WfProcessStatus.COMPLETED, instance.getStatus(),
                "容器没有出线时，内联跑完 token 就该结束 —— 容器自己就是最后一步");
    }

    @Test
    @DisplayName("内联节点与流程级共享变量：内层 serviceTask 写的变量主图读得到")
    void inlineSharesProcessScope() {
        String pid = start(NO_TASK_BPMN);

        Map<String, Object> vars = repo.findProcessInstance(pid).getVariables();
        assertEquals("iSvc", vars.get("stampedBy"),
                "内联节点没有独立作用域，它写的变量必须落在流程级变量上");
    }

    @Test
    @DisplayName("内联子流程不新建 execution：token 树里只有承载它的那一条")
    void inlineSubProcessDoesNotCreateExecutions() {
        String pid = start(NO_TASK_BPMN);

        List<WfExecution> executions = runtime.getExecutions(pid);
        assertEquals(1, executions.size(),
                "内联子流程展开在父 token 树里，不该另起 execution —— "
                        + "多建一条会让轨迹的父子归属对不上");
        assertEquals("after", executions.get(0).getActivityId());
    }

    // ==================== 轨迹 ====================

    @Test
    @DisplayName("容器、内联结束事件各留一条历史")
    void trailCoversContainerAndInlineEnd() {
        String pid = start(NO_TASK_BPMN);
        complete(pid, "after", "carol");

        List<String> ids = trailActivityIds(pid);
        assertTrue(ids.contains("sp"), "容器 subProcess 自己是一次节点访问，轨迹上应当有它: " + ids);
        assertTrue(ids.contains("iEnd"), "内联结束事件也照例记一条: " + ids);
        assertEquals(1, count(ids, "sp"), "容器不该在轨迹上出现两次: " + ids);
        assertEquals(1, count(ids, "iEnd"), "内联结束事件不该出现两次: " + ids);
    }

    @Test
    @DisplayName("内联结束事件的办理人置空：结束不是任何一个人的动作")
    void inlineEndHasNoAssignee() {
        String pid = start(NO_TASK_BPMN);

        WfActivityInstance inlineEnd = null;
        for (WfActivityInstance activity : runtime.getTrail(pid)) {
            if ("iEnd".equals(activity.getActivityId())) {
                inlineEnd = activity;
            }
        }
        assertNotNull(inlineEnd, "内联结束事件应当有一条历史");
        assertNull(inlineEnd.getAssignee(),
                "留着手办人会污染「某人办过哪些单」—— 查 ceo 会把结束事件也捞出来");
    }

    // ==================== 汇合分段 ====================

    @Test
    @DisplayName("内层并行汇合只等内层分支，不等外层那条还没办完的")
    void innerJoinIgnoresOuterBranch() {
        String pid = start(NESTED_PARALLEL_BPMN);

        // ia 办结后，token 到了内层 iJoin；ib 还没办结，所以必须停在这儿等。
        complete(pid, "ia", "alice");
        assertNotNull(taskAt(pid, "ib"), "内层另一条分支还没办完");
        assertTrue(trailActivityIds(pid).indexOf("iJoin") < 0
                        || isWaitingAtInnerJoin(pid),
                "内层 iJoin 应当在等 ib，而不是直接通过");

        // 外层那条先办结 —— 内层的 iJoin 不该因此有任何变化。
        complete(pid, "outer", "carol");
        assertNotNull(taskAt(pid, "ib"), "外层办结不该把内层未完成的分支带走");
        assertTrue(repo.findProcessInstance(pid).getStatus() == WfProcessStatus.ACTIVE,
                "流程仍在进行中");

        // ib 办结后内层才收口，token 回到容器并沿出线走到外层 pgb。
        complete(pid, "ib", "bob");
        assertEquals(WfProcessStatus.COMPLETED, repo.findProcessInstance(pid).getStatus(),
                "内层收口后应当一路走到流程结束");
    }

    @Test
    @DisplayName("内层汇合不误结束外层分支的 token")
    void innerJoinDoesNotCollapseOuterToken() {
        String pid = start(NESTED_PARALLEL_BPMN);

        complete(pid, "ia", "alice");
        complete(pid, "ib", "bob");

        assertNotNull(taskAt(pid, "outer"),
                "内层收口把内层分支合并掉时，不该把外层分支的 token 一起结束掉");
    }

    /** 流程是否停在内层 iJoin 上（还没通过汇合）。 */
    private boolean isWaitingAtInnerJoin(String pid) {
        for (WfExecution execution : runtime.getExecutions(pid)) {
            if (execution.isEnded()) {
                continue;
            }
            if ("iJoin".equals(execution.getActivityId())) {
                return execution.getState() == WfExecution.State.WAITING;
            }
        }
        return false;
    }

    // ==================== 部署期挡掉的写法 ====================

    @Test
    @DisplayName("校验拦下：内联子流程有两个内联起始节点")
    void twoInlineEntriesAreRejected() {
        WfDefinition definition = new WfXmlParser().parse(TWO_ENTRIES_BPMN);
        assertTrue(hasError(definition, "内联起始节点"), "应当报出内联起始节点不唯一");
        assertThrows(WfDefinitionException.class, () -> repository.deploy(definition));
    }

    @Test
    @DisplayName("校验拦下：内联子流程有两个内联结束节点")
    void twoInlineExitsAreRejected() {
        WfDefinition definition = new WfXmlParser().parse(TWO_EXITS_BPMN);
        assertTrue(hasError(definition, "内联结束节点"), "应当报出内联结束节点不唯一");
        assertThrows(WfDefinitionException.class, () -> repository.deploy(definition));
    }

    @Test
    @DisplayName("校验拦下：内联子流程里又嵌了 subProcess")
    void nestedSubProcessIsRejected() {
        WfDefinition definition = new WfXmlParser().parse(NESTED_SUB_BPMN);
        assertTrue(hasError(definition, "又嵌了 subProcess"), "应当报出嵌套内联子流程");
        assertThrows(WfDefinitionException.class, () -> repository.deploy(definition));
    }

    @Test
    @DisplayName("校验拦下：边界事件挂在 subProcess 上")
    void boundaryEventOnSubProcessIsRejected() {
        WfDefinition definition = new WfXmlParser().parse(BOUNDARY_ON_SUB_BPMN);
        assertTrue(hasError(definition, "不能挂边界事件"), "应当报出容器上挂了边界事件");
        assertThrows(WfDefinitionException.class, () -> repository.deploy(definition));
    }

    @Test
    @DisplayName("校验拦下：既画内联内容又配 calledElementKey")
    void inlineWithCalledElementKeyIsRejected() {
        WfDefinition definition = new WfXmlParser().parse(BOTH_INLINE_AND_KEY_BPMN);
        assertTrue(hasError(definition, "calledElementKey"), "应当报出内联与 calledElementKey 互斥");
        assertThrows(WfDefinitionException.class, () -> repository.deploy(definition));
    }

    @Test
    @DisplayName("校验拦下：内联容器元素没写 id")
    void containerWithoutIdIsRejected() {
        WfDefinition definition = new WfXmlParser().parse(NO_CONTAINER_ID_BPMN);
        assertTrue(hasError(definition, "容器元素多半没写 id"),
                "内联节点认不出自己属于谁时必须报错，否则容器会被当成空容器直接穿透");
        assertThrows(WfDefinitionException.class, () -> repository.deploy(definition));
    }

    @Test
    @DisplayName("回归：空 subProcess + calledElementKey 不受内联规则影响")
    void emptySubProcessWithKeyStillDeploys() {
        WfDefinition definition = new WfXmlParser().parse(EMPTY_SUB_WITH_KEY_BPMN);
        List<WfValidationIssue> issues = new WfDefinitionValidator().validate(definition);
        for (WfValidationIssue issue : issues) {
            assertFalse(issue.getSeverity() == WfValidationIssue.Severity.ERROR
                            && joinAll(issue).contains("内联"),
                    "空容器不是内联子流程，不该被内联规则误伤: " + joinAll(issue));
        }
        assertEquals("emptySubProcess", deploy(EMPTY_SUB_WITH_KEY_BPMN));
    }

    // ==================== 边界事件与内联归属 ====================

    @Test
    @DisplayName("内联活动上的边界事件不算内联节点")
    void boundaryEventOnInlineActivityIsNotInline() {
        WfDefinition definition = new WfXmlParser().parse(BOUNDARY_ON_INLINE_TASK_BPMN);

        WfNode boundary = definition.node("beI");
        assertNotNull(boundary, "内联活动上的边界事件应当被解析出来");
        assertEquals("iTask", boundary.nestedIn(),
                "解析器记的是直接父元素，边界事件的容器是宿主活动而不是 subProcess");
        assertFalse(definition.isInline(boundary),
                "容器不是 subProcess ⇒ 它是挂在活动上的边界事件，不是内联子图的一步。"
                        + "当成内联节点会让它在「无入线节点」里被算成流程入口");
    }

    @Test
    @DisplayName("内联活动上的边界事件按宿主算归属，与内层分支同段")
    void boundaryEventInheritsItsHostInlineScope() {
        WfDefinition definition = new WfXmlParser().parse(BOUNDARY_ON_INLINE_TASK_BPMN);

        assertEquals("sp", definition.inlineScopeOf("beI"),
                "宿主 iTask 在内联子流程里，这条边界分支就该属于内层那一段 —— "
                        + "算成主图会让内层的并行汇合等不到它，流程卡在子流程里");
        assertEquals("sp", definition.inlineScopeOf("iTask"), "宿主自己属于内层");
        assertEquals("", definition.inlineScopeOf("before"), "主图节点属于主图");
    }

    @Test
    @DisplayName("主图活动上的边界事件属于主图")
    void boundaryEventOnMainActivityIsInMainScope() {
        WfDefinition definition = new WfXmlParser().parse(NO_START_EVENT_BPMN);

        assertEquals("before", definition.node("beMain").nestedIn());
        assertFalse(definition.isInline(definition.node("beMain")));
        assertEquals("", definition.inlineScopeOf("beMain"));
    }

    // ==================== 边界事件的宿主判定：看 attachedToRef，不看它在 XML 里缩在哪 ====================

    @Test
    @DisplayName("boundaryEvent 写在 subProcess 层级下、attachedToRef 指内层活动 ⇒ 合法")
    void boundaryEventDeclaredAtSubLevelIsNotRejected() {
        // 这条与上面 boundaryEventOnInlineActivityIsNotInline 配对。
        // 判据只看 attachedToRef（挂在谁身上），两种 XML 位置得到的答案必须一致 ——
        // 旧实现按 nestedIn 判，而 nestedIn 记的是「在 XML 里写在谁里面」，
        // 于是这一种写法被报成「嵌入式 subProcess 上不能挂边界事件」，
        // 而把同一个 boundaryEvent 挪到宿主活动内部就能过：图没变、语义没变、结论相反。
        WfDefinition definition = new WfXmlParser().parse(BOUNDARY_AT_SUB_LEVEL_BPMN);
        assertEquals("sp", definition.node("beI").nestedIn(),
                "前置条件：这个 boundaryEvent 确实写在 subProcess 内部 —— "
                        + "本条判据的前提就是「nestedIn 与 attachedToRef 不是同一个值」，"
                        + "两者相等的话它与既有那条判据是同一个用例，"
                        + "什么也证明不了");
        assertEquals("iTask", definition.node("beI").getAttachedToRef(),
                "前置条件：它挂的是内层的 iTask，不是容器 sp");

        for (WfValidationIssue issue : new WfDefinitionValidator().validate(definition)) {
            assertFalse(issue.getSeverity() == WfValidationIssue.Severity.ERROR
                            && joinAll(issue).contains("不能挂边界事件"),
                    "子流程内的活动挂超时边界是正常写法，不该被判成挂在容器上。"
                            + "报错文本: " + joinAll(issue));
        }
        assertEquals("boundaryAtSubLevelProcess", deploy(BOUNDARY_AT_SUB_LEVEL_BPMN),
                "部署期不该报错");
    }

    @Test
    @DisplayName("内联子图里的死胡同要报「没有出线」，不是「结束点不唯一」")
    void deadEndInsideInlineIsNotCountedAsAnExit() {
        // 旧实现把「容器内没有出线的任何节点」都当成内联结束节点，
        // 于是 iDead（userTask，有入线无出线）与 iEnd 一起凑成 2 个「结束点」，
        // 报出来的是「子流程的结束点必须唯一」。作者照着这句话去找第二个
        // endEvent，找遍全图也找不到一个 —— 而真正的问题（iDead 无出线）
        // 一个字都没提。
        WfDefinition definition = new WfXmlParser().parse(DEAD_END_IN_INLINE_BPMN);

        assertTrue(hasError(definition, "有入线却没有出线"),
                "死胡同要按它自己的问题报错。实际报错: " + messagesOf(definition));
        assertFalse(hasError(definition, "内联结束节点"),
                "iDead 不是结束节点，把它算进去会让一条只有一个 endEvent 的子流程"
                        + "被报成有两个结束点，而作者找不到第二个。实际报错: "
                        + messagesOf(definition));
    }

    @Test
    @DisplayName("回归：只有 endEvent 无出线的正常内联子流程不被死胡同检查误伤")
    void normalInlineSubProcessIsNotFlaggedAsDeadEnd() {
        WfDefinition definition = new WfXmlParser().parse(EMBEDDED_BPMN);
        for (WfValidationIssue issue : new WfDefinitionValidator().validate(definition)) {
            assertFalse(issue.getSeverity() == WfValidationIssue.Severity.ERROR
                            && joinAll(issue).contains("没有出线"),
                    "endEvent 本来就没有出线，它不是死胡同。实际报错: " + joinAll(issue));
        }
    }

    @Test
    @DisplayName("inlineEndNode 只认 endEvent：死胡同不算出口（与校验器同一把尺子）")
    void inlineEndNodeIgnoresDeadEnds() {
        // 这条钉的是**两处定义一致**，不是某个运行期行为：
        // WfDefinition#inlineEndNode 与 WfDefinitionValidator 的出口判定，
        // 若一个按「无出线」、另一个按「endEvent」，同一个图会被判出两种结论。
        // 该方法当前没有生产调用点，所以这里不假装它在挡什么 ——
        // 它一旦被接进执行路径，口径不对就会直接变成"子流程提前跳出去了"。
        WfDefinition definition = new WfXmlParser().parse(DEAD_END_IN_INLINE_BPMN);
        assertNotNull(definition.inlineEndNode("sp"),
                "子流程里确实有一个 endEvent，出口应当唯一识别得出来");
        assertEquals("iEnd", definition.inlineEndNode("sp").getId(),
                "把 iDead（死胡同）也算成出口的话，唯一性判定会失败而返回 null —— "
                        + "作者画的是「一个」结束点，被告知的是「没有或多个」");
    }

    // ==================== 退化入口 ====================

    @Test
    @DisplayName("不写 startEvent 的定义：退化入口判定也要排除内联节点")
    void degenerateEntryIgnoresInlineNodes() {
        WfDefinition definition = new WfXmlParser().parse(NO_START_EVENT_BPMN);

        List<WfNode> starts = definition.unconditionalStartNodes();
        assertEquals(1, starts.size(),
                "显式 startEvent 那条路和「无入线节点」那条路都得排除内联节点。"
                        + "本定义没有 startEvent，走的正是后者；不排除的话 iStart 与 beMain "
                        + "都会被算成流程入口: " + idsOf(starts));
        assertEquals("before", starts.get(0).getId(), "退化出来的入口应当是主图上那个节点");
    }

    @Test
    @DisplayName("不写 startEvent 的定义：内联子流程照常执行")
    void degenerateEntryStillRunsInline() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(NO_START_EVENT_BPMN));
        String pid = runtime.startProcessInstance(definition, "BIZ-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());

        assertNotNull(taskAt(pid, "before"),
                "退化出来的入口应当落在主图第一个节点上，而不是内联子流程里");
        assertTrue(delegateCalls.isEmpty(), "内联子流程还没走到，不该已经调过 delegate");

        complete(pid, "before", "alice");

        assertEquals(1, delegateCalls.size(),
                "内联 serviceTask 应当被执行一次 —— 退化入口与内联执行是两条独立的路");
        assertEquals(WfProcessStatus.COMPLETED, repo.findProcessInstance(pid).getStatus());
    }

    // ==================== 流程级入口识别 ====================

    @Test
    @DisplayName("内联的 startEvent 不算流程入口")
    void inlineStartEventIsNotAProcessEntry() {
        WfDefinition definition = new WfXmlParser().parse(EMBEDDED_BPMN);

        List<WfNode> starts = definition.unconditionalStartNodes();
        assertEquals(1, starts.size(),
                "内联子流程的 iStart 与流程级的 s1 都是「无入线」，"
                        + "不排除内联节点的话会凭空多出第二个无条件开始节点: "
                        + idsOf(starts));
        assertEquals("s1", starts.get(0).getId(), "流程入口必须是 <process> 下的那个");
    }

    @Test
    @DisplayName("内联节点带 messageRef 也不算消息启动入口")
    void inlineEventStartIsNotAMessageEntry() {
        String xml = EMBEDDED_BPMN
                .replace("      <startEvent id=\"iStart\"/>\n",
                        "      <startEvent id=\"iStart\">\n"
                                + "        <messageEventDefinition messageRef=\"innerMsg\"/>\n"
                                + "      </startEvent>\n")
                .replace("id=\"embeddedProcess\"", "id=\"inlineMsgStartProcess\"");
        WfDefinition definition = new WfXmlParser().parse(xml);

        assertTrue(definition.eventStartNodes().isEmpty(),
                "内联子流程里的消息起始事件是子流程内部的事，"
                        + "不参与「收到这条消息该起哪个流程」的判定: " + idsOf(definition.eventStartNodes()));
        assertEquals(1, definition.unconditionalStartNodes().size(),
                "带 messageRef 的内联 startEvent 不该被当成第二个无条件入口");
    }

    @Test
    @DisplayName("内联子流程作为流程入口时能正常启动")
    void definitionStartingWithEmbeddedSubProcessStarts() {
        WfDefinition definition = repository.deploy(new WfXmlParser().parse(NO_TASK_BPMN));
        assertEquals("s1", definition.startNode().getId(),
                "startNode() 认的是流程级入口，不是容器内的 iStart");

        String pid = runtime.startProcessInstance(definition, "BIZ-" + System.nanoTime(),
                "alice", null, new HashMap<String, Object>());
        assertNotNull(taskAt(pid, "after"), "流程应当穿过内联子流程走到后面");
    }

    // ==================== 定义层查询 ====================

    @Test
    @DisplayName("内联容器查询：起始/结束各能唯一认出")
    void definitionExposesInlineStartAndEnd() {
        WfDefinition definition = new WfXmlParser().parse(EMBEDDED_BPMN);

        assertTrue(definition.isInlineSubProcess(definition.node("sp")), "sp 是内联子流程");
        assertFalse(definition.isInlineSubProcess(definition.node("before")),
                "普通 userTask 不是内联子流程");
        assertEquals("iStart", definition.inlineStartNode("sp").getId());
        assertEquals("iEnd", definition.inlineEndNode("sp").getId());
        assertEquals("sp", definition.inlineScopeOf("iTask"), "内联节点属于 sp 那一段图");
        assertEquals("", definition.inlineScopeOf("before"), "主图节点属于主图");
        assertNull(definition.inlineScopeOf("noSuchNode"), "认不出来的活动归属必须是 null");
    }

    @Test
    @DisplayName("内联子流程有内联内容；空容器不算")
    void emptyContainerIsNotInline() {
        WfDefinition definition = new WfXmlParser().parse(EMPTY_SUB_WITH_KEY_BPMN);

        assertFalse(definition.isInlineSubProcess(definition.node("sp")),
                "空容器配 calledElementKey 是 callActivity 语义，不该被当成内联子流程");
        assertTrue(definition.inlineChildrenOf("sp").isEmpty());
    }

    @Test
    @DisplayName("嵌套时最内层的容器标记指向直接父容器")
    void nestedContainerIdsAreRecorded() {
        WfDefinition definition = new WfXmlParser().parse(NESTED_SUB_BPMN);

        assertEquals("sp", definition.node("inner").nestedIn(), "inner 直接嵌在 sp 里");
        assertEquals("inner", definition.node("nTask").nestedIn(), "nTask 直接嵌在 inner 里");
    }

    // ==================== 小工具 ====================

    private static boolean hasError(WfDefinition definition, String keyword) {
        for (WfValidationIssue issue : new WfDefinitionValidator().validate(definition)) {
            if (issue.getSeverity() == WfValidationIssue.Severity.ERROR
                    && joinAll(issue).contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String joinAll(WfValidationIssue issue) {
        return (issue.getMessage() == null ? "" : issue.getMessage())
                + " " + (issue.getNodeId() == null ? "" : issue.getNodeId());
    }

    /** 一次校验的全部报错文本 —— 断言失败时要能让人当场看见"实际报了什么"。 */
    private static String messagesOf(WfDefinition definition) {
        StringBuilder sb = new StringBuilder("[");
        for (WfValidationIssue issue : new WfDefinitionValidator().validate(definition)) {
            if (issue.getSeverity() == WfValidationIssue.Severity.ERROR) {
                if (sb.length() > 1) {
                    sb.append(" | ");
                }
                sb.append(joinAll(issue));
            }
        }
        return sb.append("]").toString();
    }

    private static String idsOf(List<WfNode> nodes) {
        Set<String> ids = new LinkedHashSet<>();
        for (WfNode node : nodes) {
            ids.add(node.getId());
        }
        return ids.toString();
    }

    private static int count(List<String> list, String value) {
        int total = 0;
        for (String item : list) {
            if (value.equals(item)) {
                total++;
            }
        }
        return total;
    }
}
