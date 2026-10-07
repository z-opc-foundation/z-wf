package com.zifang.z.wf.core.definition;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 流程节点类型枚举 —— z-wf 自研的节点语义全集。
 *
 * <p>与 z-util-wf-kernel {@code BpmnModelConverter} 的 {@code switch} 分支一一对应
 * （BPMN 2.0 元素小写化后的名字），保证"同一份 BPMN XML 在内存引擎与生产引擎里落到同一种节点类型"，
 * 这是 z-util-wf 与 z-wf 共用协议层的第一条硬约束。
 *
 * <p>新增类型时必须同步三处：
 * <ol>
 *   <li>本枚举</li>
 *   <li>{@link WfNodeType#fromBpmn(String)} 的映射表</li>
 *   <li>{@code WfBehaviorRegistry} 里对应的 {@link com.zifang.z.wf.core.engine.behavior.WfActivityBehavior} 实现</li>
 * </ol>
 *
 * @author zifang
 */
public enum WfNodeType {

    /** 开始事件。流程实例的唯一入口，一个流程有且仅有一个 start 节点。 */
    START_EVENT("startEvent"),

    /** 结束事件。token 抵达即结束；可携带 {@code resultExpression} 决定流程结果。 */
    END_EVENT("endEvent"),

    /**
     * 终止结束事件 {@code terminateEndEvent}（第 36 轮）。
     *
     * <p><b>与 {@link #END_EVENT} 的差别不在「结束」，而在「结束谁」。</b>
     * 普通结束事件结束的是<b>这一条 token</b>；终止结束事件结束的是
     * <b>它所在作用域里的全部 token</b>及其内部所有嵌套作用域。
     *
     * <p>Camunda 7 原文（{@code manual/develop/reference/bpmn20/events/terminate-event/}）：
     * 「A terminate event ends the complete scope it is raised in and all contained
     * inner scopes … A terminate event on process instance level terminates the
     * complete instance. On subprocess level the current scope and all contained
     * processes instances will be terminated.」
     *
     * <p><b>它是最常见的「并行分支里一方成了，另一方就别做了」的写法</b>：
     * 两条并行分支各有一个结束事件，其中一条是 terminate 到达时，
     * 另一条分支上的待办会立即消失、子流程照常完成并沿出线继续 ——
     * 而这两件事用普通 endEvent + 人工作废是做不到的。
     *
     * <p>刻意做成<b>独立类型而不是 END_EVENT 上的一个标志</b>：
     * 本类型在各处都按「与 END_EVENT 无关」处理，若改成标志位，
     * 任何一处 {@code type == END_EVENT} 的判断都会把它当成普通结束事件，
     * 症状是「图上画了终止，图上什么都没发生」。
     */
    TERMINATE_END_EVENT("terminateEndEvent"),

    /**
     * 取消结束事件 {@code cancelEndEvent}（第 38 轮）。
     *
     * <p>它与 {@link #TERMINATE_END_EVENT} 的差别只有一条，但那条是本质的：
     * <b>先补偿，再结束</b>。
     *
     * <p>Camunda 7（{@code manual/7.9/reference/bpmn20/events/cancel-and-compensation-events/}）：
     * 「A cancel end event ends the transaction … compensation is triggered …
     * the token continues along the outgoing sequence flow of the transaction.」
     *
     * <p>所以它<b>不</b>像终止结束事件那样把整个作用域拆掉：
     * 在容器内，它只退掉这个作用域里已经做完的事，然后照常沿容器的出线往下走 ——
     * 这正是「事务失败 ⇒ 撤销已做的部分 ⇒ 流程继续」这条语义的落点。
     *
     * <p>放在主图上（没有容器）时它没有出线可沿，按 Camunda 的
     * 「process level」语义处理：退掉整个实例已做的部分，然后实例结束。
     */
    CANCEL_END_EVENT("cancelEndEvent"),

    /**
     * 事务 {@code transaction}（第 38 轮）。
     *
     * <p>执行路径与内联 {@link #SUB_PROCESS} <b>完全相同</b>（token 进入后跑内联子图，
     * 到达内联结束事件时回到容器、沿容器出线继续），差别全在「结束时的语义」：
     * <ul>
     *   <li>走到 {@link #CANCEL_END_EVENT} ⇒ 触发该作用域的补偿，再沿出线继续</li>
     *   <li>内部未被捕获的 error ⇒ Camunda 走 <b>hazard</b>（事务挂起等人处理，<b>不补偿</b>）。
     *       本引擎当前没有 hazard 状态机，这类 error 走既有路径把实例停成
     *       {@code INTERNALLY_TERMINATED} 并记错误码 —— <b>这一条与 Camunda 不一致</b>，
     *       已记进 {@code docs/capability-gap.md} 的缺口清单，不是遗漏而是取舍。</li>
     * </ul>
     *
     * <p>刻意<b>不</b>做成 {@link #SUB_PROCESS} 上的标志位：容器的出口规则不一样
     * （subProcess 要求恰好一个内联结束节点，transaction 允许「只有 cancel 出口」），
     * 而一处 {@code type == SUB_PROCESS} 的判断会把它当普通子流程放行。
     */
    TRANSACTION("transaction"),

    /** 用户任务：创建 {@link com.zifang.z.wf.core.model.WfTask} 并挂起等待人工处理。 */
    USER_TASK("userTask"),

    /** 服务任务：调用 {@link com.zifang.z.wf.core.engine.delegate.WfJavaDelegate}。 */
    SERVICE_TASK("serviceTask"),

    /** 脚本任务：执行 EL 表达式。 */
    SCRIPT_TASK("scriptTask"),

    /** 手工任务：创建任务但不预分配办理人，由认领（claim）产生办理人。 */
    MANUAL_TASK("manualTask"),

    /** 发送任务：把 payload 交给 {@link com.zifang.z.wf.core.engine.behavior.WfSendTaskBehavior} 的 SPI 实现。 */
    SEND_TASK("sendTask"),

    /** 接收任务：等待外部消息触发后继续。 */
    RECEIVE_TASK("receiveTask"),

    /**
     * 中间抛出事件：token 抵达即把一条信号/消息投递出去，然后<b>自己继续往下走</b>。
     *
     * <p>它是<b>发布方</b>，与 {@link #RECEIVE_TASK}（订阅方）、消息/信号边界（拦截方）
     * 是三种不同的等待语义。穿透是它与 {@code serviceTask} 的共同点，
     * 区别在于 {@code serviceTask} 要业务方实现 delegate，而抛事件由引擎自己投递。
     *
     * <p>此前本引擎把它当"不支持的元素"在部署期挡掉（第 4 轮改的：宁可部署失败，
     * 也不要静默退化成人工任务）。现在它有了引擎侧的真实语义 ——
     * 一份真实的 Camunda 流程里出现 throwEvent 时，本引擎能直接部署并正确执行。
     */
    THROW_EVENT("intermediateThrowEvent"),

    /** 排他网关：按顺序求值条件，只走第一条成立的连线。 */
    EXCLUSIVE_GATEWAY("exclusiveGateway"),

    /** 并行网关：无条件激活全部出线；汇合时等待所有入线到齐。 */
    PARALLEL_GATEWAY("parallelGateway"),

    /** 包容网关：激活所有条件成立的出线；汇合时等待"仍可能到达"的分支。 */
    INCLUSIVE_GATEWAY("inclusiveGateway"),

    /**
     * 复杂网关：按<b>流程变量的取值</b>走对应的出线，而不是按布尔条件。
     *
     * <p>与排他网关的关系：排他是"哪个条件为真走哪条线"（条件可以是任意表达式），
     * 复杂是"这个值等于几就走哪条线"。后者在"按单据状态分派"这种场景里更直白 ——
     * {@code status} 等于 {@code approved} 走审批通过、{@code rejected} 走驳回，
     * 不必为每个状态写一条比较表达式。
     *
     * <p>与包容网关不同：它<b>只走一条</b>线，即使多条 caseValue 都能匹配，
     * 也只有第一条生效。走多条是包容网关的语义。
     */
    COMPLEX_GATEWAY("complexGateway"),

    /**
     * 事件网关：把 token <b>分叉</b>到若干个中间捕获事件上，谁先来就走谁。
     *
     * <p>与并行网关的差别不在"分叉"，在<b>分叉之后各分支互相排斥</b>：
     * 并行网关的 N 个分支最终要汇合，而事件网关只要有一个事件到达，
     * <b>其余分支连同它们各自的等待一并作废</b>。典型用法是
     * "等主管批 / 等超时 / 等业务系统回执，谁先来听谁的"。
     */
    EVENT_BASED_GATEWAY("eventBasedGateway"),

    /**
     * 中间捕获事件：<b>停在该节点等一个外部事件</b>，事件到了才沿出线离开。
     *
     * <p>与 {@link #RECEIVE_TASK} 的差别是<b>不产生人工待办</b> —— 它等的人不是某个用户，
     * 而是一条消息、一个信号或一次超时。把两者混为一谈的代价是：
     * 事件网关的出线全是中间捕获事件，退化成人工任务的话
     * 网关就从"自动竞速"变成"让 N 个人同时点"，而作者不会收到任何提示。
     */
    INTERMEDIATE_CATCH_EVENT("intermediateCatchEvent"),

    /**
     * 链接抛出事件：token 抵达即被<b>改道</b>到同名的 {@link #LINK_CATCH}，
     * 自己不再沿出线前进。
     *
     * <p>它解决的是"跳过一整段图"：{@code move} 是运行期的外部 API（要把令牌
     * 搬到一个已知节点上），排他网关是<b>分支</b>（在若干出线里选一条），
     * 两者都替代不了"从图上某处直接落到另一处"。典型用法是循环重试
     * 与"审批通过就走归档、否则整段跳过"。
     *
     * <p><b>与 {@link #THROW_EVENT} 的根本差别</b>：中间抛出事件把事件<b>投出去</b>
     * 然后自己继续往下走，投递对象是别处；链接抛出事件是把<b>自己这条 token</b>
     * 搬走，投递对象就是它自己。因此它的出线不会被 token 走过 —— 出线在
     * BPMN 里允许画出来，但只是图上的摆设。
     */
    LINK_THROW("linkThrowEvent"),

    /**
     * 链接捕获事件：link 的<b>落点</b>，token 从 {@link #LINK_THROW} 跳过来后，
     * 沿<b>自己</b>的出线继续走。它本身不执行任何动作，也不等待。
     *
     * <p>与 {@link #INTERMEDIATE_CATCH_EVENT} 不能合并：后者会建
     * {@code EVENT_*} 订阅等外部事件，而 link catch 等的是"图上另一个节点"
     * —— 那个跳转在引擎内部同步完成，根本没有"订阅"这回事。
     * 复用它会得到一个永远等不到、也不报错的哑订阅。
     */
    LINK_CATCH("linkCatchEvent"),

    /**
     * 业务规则任务：同步求值一张 DMN 决策表，把结论写进流程变量。
     *
     * <p>它与 {@link #SERVICE_TASK} 的区别是<b>不需要业务方写任何代码</b>：
     * {@code serviceTask} 要实现 delegate，业务规则任务只要指一张决策表的 key。
     * 与 {@link #SCRIPT_TASK} 的区别是<b>规则与流程分开部署</b>：
     * 决策表有独立的版本与生命周期（见 {@code WfDecisionService}），
     * 改规则不必重新部署流程。
     *
     * <p>此前它不在解析器的元素表里，会走"未知元素"路径 ——
     * 而未知元素会被 {@code WfDefinitionValidator} 当作"不支持的元素"报 ERROR 挡住，
     * 于是<b>一份用业务规则任务的真实流程在本引擎里部署不了</b>。
     */
    BUSINESS_RULE_TASK("businessRuleTask"),

    /** 子流程：内嵌一个流程定义（或内联子图）。 */
    SUB_PROCESS("subProcess"),

    /** 调用活动：引用外部流程定义 key。 */
    CALL_ACTIVITY("callActivity"),

    /** 通用任务：无类型约束，行为等同 userTask 但不要求 assignee。 */
    TASK("task"),
    /**
     * 边界事件。
     *
     * <p><b>它不是普通节点</b>：没有入线，靠宿主节点出错/超时/收消息时被触发。
     * 所以 {@link #createsTask()} 为 false（不该建任务），
     * 也不该被任何 sequenceFlow 指到 —— 校验器会挡住"有入线"的写法。
     */
    BOUNDARY_EVENT("boundaryEvent");

    private final String bpmnName;

    WfNodeType(String bpmnName) {
        this.bpmnName = bpmnName;
    }

    /**
     * BPMN 2.0 XML 里的元素名（小写驼峰）。
     */
    public String bpmnName() {
        return bpmnName;
    }

    /**
     * 是否为网关类型 —— 网关由 {@link com.zifang.z.wf.core.engine.WfEngine} 单独求值，
     * 不走 delegate 调用路径。
     */
    public boolean isGateway() {
        return this == EXCLUSIVE_GATEWAY || this == PARALLEL_GATEWAY
                || this == INCLUSIVE_GATEWAY || this == COMPLEX_GATEWAY
                || this == EVENT_BASED_GATEWAY;
    }

    /**
     * 是否会创建 {@link com.zifang.z.wf.core.model.WfTask} 并挂起等待人工。
     */
    /**
     * 本节点是否会创建一条任务并<b>停在该节点等外部动作</b>。
     *
     * <p>返回 true 时，{@code WfEngine} 会执行行为、登记任务、把 token 置 WAITING，
     * 直到 {@code completeTask}（人办结）或 {@code triggerMessage}（消息到达）才继续。
     *
     * <p><b>为什么含 {@link #RECEIVE_TASK}：</b> 接收任务的全部意义就是"停住等消息"，
     * 它等的人不是某个具体用户，而是一条消息。若不把它算进来，引擎会走
     * "执行完行为直接离开"分支，{@code WfReceiveTaskBehavior} 建出来的
     * {@link com.zifang.z.wf.core.model.WfTask} 会被<b>原样丢弃</b>——
     * 流程一路穿到结束事件，节点却留下"entered/completed"的活动记录，
     * 看上去跑通了，实际什么都没等。
     *
     * <p><b>为什么不含 {@link #SEND_TASK}：</b> 发送任务按 BPMN 语义是穿透的——
     * 它把消息发出去就往下走，不等任何人回复。把它算进来会让流程停在一个
     * 没有任何人能办结的任务上，整条流程死锁。两者一个都不能加错。
     */
    public boolean createsTask() {
        return this == USER_TASK || this == MANUAL_TASK || this == TASK
                || this == RECEIVE_TASK;
    }

    /**
     * 是否为流程边界（抵达即改变流程实例状态）。
     *
     * <p>含 {@link #TERMINATE_END_EVENT}：它同样在抵达时改变实例状态，
     * 而且改得更彻底（结束整个作用域）。虽然本方法目前<b>主代码里没有调用点</b>
     * （只有定义），仍然按语义补齐 —— 留着它只对 {@link #END_EVENT} 为真的话，
     * 将来第一个用它做分支的人会漏掉终止结束事件。
     */
    public boolean isBoundary() {
        return this == START_EVENT || this == END_EVENT || this == TERMINATE_END_EVENT
                || this == CANCEL_END_EVENT;
    }

    /**
     * BPMN 元素名（忽略大小写）→ 节点类型。
     *
     * <p>大小写不敏感是有意的：BPMN XML 里元素名是 {@code userTask}，
     * 但 LogicFlow 之类的设计器导出的 JSON 常写成 {@code userTask} / {@code USER_TASK} / {@code usertask}，
     * 三者在本引擎里必须归一到同一个类型，否则"设计器导出"这条路会在解析期就断掉。
     *
     * @param bpmnName BPMN 元素名，允许 {@code null}
     * @return 对应类型；未识别时返回 {@link #TASK}（而非抛异常 —— 设计器会产出扩展类型，
     *         让它退化成通用任务比让整份定义解析失败更可用）
     */
    public static WfNodeType fromBpmn(String bpmnName) {
        if (bpmnName == null) {
            return TASK;
        }
        String normalized = bpmnName.trim()
                .replace("_", "")
                .replace("-", "")
                .toLowerCase();
        for (WfNodeType type : values()) {
            if (type.bpmnName.toLowerCase().equals(normalized)) {
                return type;
            }
        }
        return TASK;
    }

    /**
     * 宽松解析：额外接受 {@code USER_TASK} / {@code user-task} / {@code userTask} 三种写法。
     *
     * @return 识别成功返回类型，否则 {@code null}（调用方决定是报错还是退化）
     */
    public static WfNodeType parse(String raw) {
        if (raw == null) {
            return null;
        }
        WfNodeType hit = INDEX.get(normalize(raw));
        return hit;
    }

    /**
     * 这个 BPMN 元素名是否被本引擎<b>原生支持</b>。
     *
     * <p>存在的理由：{@link #fromBpmn(String)} 对认不出的名字一律退化成
     * {@link #TASK}，让"能否退化"和"退化得对不对"这两件事无法区分。
     * 而这两者恰恰是最容易出事的地方 ——
     * {@code eventBasedGateway}（事件竞速）和 {@code transaction}（事务子流程）
     * 退化成"人工任务"都不是一个<b>较小</b>的错误，而是一个<b>完全不同</b>的流程：
     * 作者以为自己写了自动分支，实际部署出去的是"建个任务等人来点"。
     *
     * <p>所以解析期用它把"退化出来的 TASK"标记出来，交给校验器报错，
     * 而不是让退化悄无声息地进入运行态。
     *
     * @param bpmnName BPMN 元素名，允许 {@code null}
     */
    public static boolean isNative(String bpmnName) {
        return bpmnName != null && INDEX.containsKey(normalize(bpmnName));
    }

    /** 归一化：去下划线/连字符并转小写，让 {@code user-task} 与 {@code userTask} 等价。 */
    private static String normalize(String raw) {
        return raw.trim().replace("_", "").replace("-", "").toLowerCase();
    }

    /** 未识别类型名的登记表，用于诊断信息。 */
    private static final Map<String, WfNodeType> INDEX;

    static {
        Map<String, WfNodeType> index = new HashMap<>();
        for (WfNodeType type : values()) {
            index.put(type.bpmnName.toLowerCase(), type);
        }
        INDEX = Collections.unmodifiableMap(index);
    }

    /**
     * 全部已登记的 BPMN 名（小写）→ 类型 的只读视图，供校验器生成诊断信息。
     */
    public static Map<String, WfNodeType> index() {
        return INDEX;
    }

    /**
     * 全部 BPMN 名的只读列表（诊断信息用）。
     */
    public static java.util.List<String> names() {
        return Collections.unmodifiableList(Arrays.asList(
                START_EVENT.bpmnName, END_EVENT.bpmnName, TERMINATE_END_EVENT.bpmnName,
                CANCEL_END_EVENT.bpmnName, TRANSACTION.bpmnName,
                USER_TASK.bpmnName,
                SERVICE_TASK.bpmnName, SCRIPT_TASK.bpmnName, MANUAL_TASK.bpmnName,
                SEND_TASK.bpmnName, RECEIVE_TASK.bpmnName, EXCLUSIVE_GATEWAY.bpmnName,
                PARALLEL_GATEWAY.bpmnName, INCLUSIVE_GATEWAY.bpmnName,
                COMPLEX_GATEWAY.bpmnName, EVENT_BASED_GATEWAY.bpmnName,
                INTERMEDIATE_CATCH_EVENT.bpmnName, THROW_EVENT.bpmnName,
                LINK_THROW.bpmnName, LINK_CATCH.bpmnName,
                SUB_PROCESS.bpmnName, CALL_ACTIVITY.bpmnName,
                TASK.bpmnName,
                BOUNDARY_EVENT.bpmnName));
    }
}
