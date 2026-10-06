# z-wf — 自研流程引擎

> [English](README.en.md) · [能力盘点：与 Camunda 7 的逐项对照](docs/capability-gap.md)

> 不依赖 Camunda / Flowable / Activiti 等任何第三方工作流引擎。定义层协议与 `z-util-wf-kernel` 共用，
> 运行时层（token 执行树、网关求值、持久化、REST）全部自研。

`z-camuda` 的定位是"在 Camunda 上封装一层我们的 REST 形态"；`z-wf` 的定位是**换掉引擎本身**。
两者模块切分一致（core / web / starter / admin），所以接入侧的认知可以平移，但底层语义不同。

---

## 坐标与版本：为什么本仓是 2.0.0

**坐标沿用 `io.github.yuku123:z-wf`，版本是刻意的破坏性大版本 `2.0.0`。**

这里有一段必须知道的历史，否则会误判本仓：

| 坐标 | Central 上的版本 | 那个坐标是什么 |
|---|---|---|
| `io.github.yuku123:z-wf` | **1.0.4 / 1.0.5 / 1.0.6**（最后更新 2026-09-29） | `z-wf (Workflow Engine)`，描述原文「**基于 Camunda 的工作流引擎**」 |
| `io.github.yuku123:z-wf-core` | **1.0.4 / 1.0.5 / 1.0.6** | 同上（Camunda 版） |
| `io.github.yuku123:z-camuda-core` | 1.0.6（最后更新 2026-10-05） | 改名后的新坐标 |

`z-camuda` 仓的 git 历史显示它**最初就叫 `z-wf`**
（`4f6af6e feat(z-wf-core): 蒸馏 ace-platform-sdk 22 个流程 SPI + WfSpiRegistry` 等 4 次提交，
2026-09-16 ~ 09-23），后来改名为 `z-camuda` 并于 2026-10-05 发布。
**改名不会释放已发布的坐标** —— `io.github.yuku123:z-wf` 至今仍指向那个 Camunda 版引擎。

**所以本仓的坐标是"接续"而非"新占"**，`1.x` 属于 Camunda 线，`2.x` 属于自研线。
用 semver 大版本划这条边界，是因为本仓相对 1.x **不是小改动，是换了整套实现**：

| | 1.x（Camunda 版） | 2.x（本仓，自研） |
|---|---|---|
| 执行模型 | Camunda PVM | 自研 token 执行树（enter/leave 分离、fork/join） |
| 持久化 | Camunda `ACT_*` 表 | 自有 `ZWF_*` 9 张表 + 乐观锁 CAS |
| 条件求值 | Camunda JUEL | `z-util-expr-el` + **未定义变量 fail-closed** |
| 流程定义 | Camunda BPMN 引擎 | 同一套 BPMN 语义，协议层与 `z-util-wf-kernel` 共用 |

**升到 2.0.0 同时解决两件事**：
1. 不低于 Central 上已发布的 1.0.6，**不存在版本倒退**；
2. 用 `[1.0,)` 这类版本区间的仓，会明确跨过一次**破坏性大版本**，
   而不是像"1.0.6 → 1.0.7"那样在版本边界上**静默换掉整套引擎实现** ——
   后者不会有编译错误、不会有行为告警，只会在运行时炸。

> ⚠ **给消费方的提示**：本坐标的 1.x 与 2.x 是**两个不同的引擎**。
> 依赖 `z-wf` 时请写明 `2.x`，并把 1.x 排除在版本区间外
> （例如 `[^1.0.4,1.0.5,1.0.6,2.0.0)` 或干脆锁死 `2.0.0`）。
> 从 1.x 迁移前请读本文第 2 节与第 4 节 —— 持久化表、状态码、分页语义都有不兼容变更。

---

## 1. 模块

| 模块 | 职责 | 备注 |
|---|---|---|
| `z-wf-core` | 定义层 + 运行时 + 持久化 + 服务 + 钩子 | 零 web 依赖，Spring 只用 Boot 自动装配与 JDBC |
| `z-wf-web` | REST API | 审批中心 / 任务操作 / 流程操作 / 分组 / 健康检查 |
| `z-wf-starter` | 自动装配 + `z-config`/`z-rpc` 可选集成 + 示例流程 | 业务方引这个 |
| `z-wf-admin` | 独立可启动应用 | **永不上 Maven Central**（`maven.deploy.skip=true`，对齐 `z-camuda-admin`） |

消费方引 `z-wf-starter` 即可，它会传递 `z-wf-core` + `z-wf-web`。

构建（JDK 8，支持离线）：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
mvn -o install
```

版本治理：parent `z-boot-parent:1.0.21`（`relativePath` 留空，parent 在 repo1）；
兄弟仓版本走 `z-boot-fleet:1.0.1`；`z-util.version` 本仓显式覆盖为 `1.0.18`
（fleet 下发的是 1.0.14，本仓要对齐 `z-util-wf-kernel` 的 revision）。
本仓自己声明 `flatten-maven-plugin`——父链那条是 `<inherited>false</inherited>`，
不开会让 `${revision}` 字面量泄漏到装/发出去的 pom 里，消费方解析必然失败。

---

## 2. z-wf 与 z-util-wf 的关系（这是设计里最容易问错的一题）

两者**共用定义协议，但不是同一个东西**：

| | `z-util/z-util-wf-kernel` | `z-wf` |
|---|---|---|
| 形态 | 内存引擎，`WorkflowNode` + `Connector` 邻接表 | 生产引擎，`WfNode` + `WfFlow` 边列表 |
| 存储 | 无（`FileWorkflowPersistencePlugin` 可选） | SPI：内存 / JDBC（9 张表 + 乐观锁 CAS） |
| 执行模型 | `CountDownLatch` 汇聚 | 自研 token 执行树，enter / leave 严格分离 |
| 并发 | 单机内存态 | 多节点，乐观锁 + 重试语义 |
| 依赖 | 无 Spring / 无 DB | Spring Boot |
| 条件求值 | `GatewayEvaluator`：空表达式→`true`，未定义变量当 `null` 参与比较 | `WfExpressionEvaluator`：**未定义标识符 fail-closed**（判 false，交 `defaultFlow` 兜底） |

依赖方向是 **`z-wf-core` → `z-util-wf-kernel`**（上层依赖下层，反向不成立）。

### 2.1 协议一致性怎么被证明

不是靠注释，是靠两条可执行测试：

1. `WfDefinitionParserTest#parityWithZUtilWfKernel` —— 同一份 BPMN XML 分别喂给
   `z-util-wf-kernel` 的 `BpmnXmlParser` 与 z-wf 的 `WfXmlParser`，逐节点比对类型、比对连线数。
2. `ZUtilWfBridgeTest#parityThroughBridge` —— 更强的一层：走完
   `BPMN XML → BpmnXmlParser → BpmnModelConverter → ZUtilWfBridge → WfDefinition` 全链路，
   与 z-wf 直接解析的产物比节点类型、边集合、默认流落点、每条边的条件。

哪天两边各自改了类型映射或默认流归属，测试会红。

### 2.2 桥接：`ZUtilWfBridge`

```java
// 内存里搭的图 → 生产引擎定义
WfDefinition definition = ZUtilWfBridge.toWfDefinition(config, "leaveProcess", "请假流程");
repositoryService.deploy(definition);

// 反向：z-wf 定义 → 内存定义（单测 / 预演）
WorkflowConfiguration back = ZUtilWfBridge.toWorkflowConfiguration(definition);
```

之所以需要桥：两侧内部模型形状不同（邻接表 vs 边列表），没有桥就只是"读同一份 XML、
落到不同形状的对象"，协议共用停在解析层。

**桥接的两条硬规则**（都是审批安全要求，不是洁癖）：

1. **条件归属不明 ⇒ 抛异常，绝不丢弃。** `BpmnModelConverter` 把 conditionExpression
   写在**目标节点**上；一个节点有 ≥2 条带条件入线时，后写覆盖先写，已经无法判断条件属于哪条线。
   丢掉条件的后果是 `WfFlow#isUnconditional()` 为 true ⇒ `WfEngine.selectFlows` 判定通过 ⇒
   **本该走人工审批的单据被静默放行**。所以这里 fail-closed，直接抛 `WfDefinitionException`。
   （这条已由 `ZUtilWfBridgeTest#droppedConditionWouldFailOpen` 跑通证明：条件一丢就会放行。）

2. **写出方向按 (目标, 源) 分键存条件**，用 `cache["conditionExpression:<sourceRef>"]`，
   多入线时互不覆盖。内存引擎读不懂分键（会忽略），但信息不丢，转回来能精确还原 ——
   所以 `toWorkflowConfiguration` → `toWfDefinition` 这一对是**无损**的。

### 2.3 已知限制（选型前必读）

- **并列边会丢。** `BpmnModelConverter` 装配 post 列表时用 `contains` 去重，
  同一对节点之间的两条并列边（典型：`gw -[days<=3]-> task` 与 `gw(default) -> task`）
  在**输入侧**就合并成一条，桥接无法复原。后果是两条条件都不成立时会回退到被合并的那条边。
  桥接对自己写出的配置是无损的（post 保留重数）。
  **带并列边的图请直接用 z-wf 的 `WfXmlParser` / `WfJsonParser` 部署，不要绕 z-util-wf。**
  （`ZUtilWfBridgeTest#parallelEdgesCollapseIsDocumentedLimitation` 钉住了这个事实。）

- **`zifang:*` 私有扩展对 z-util-wf 不可见。** z-util-wf 只读标准 `default` 属性，
  读不到 z-wf 的 `zifang:defaultFlow`，绕一圈后默认流消失（此时条件全不成立会让 token 停住，
  而不是走错分支——不构成越权，但会卡流程）。
  **跨引擎共用的 BPMN 请只用标准写法。**

- **带条件的审批流程请用 z-wf 求值。** z-util-wf 的 `GatewayEvaluator` 把空表达式当 `true`，
  且未定义变量参与比较（`amount` 缺失时被当 `0`）——这正是 z-wf 判 fail-closed 的原因。

### 2.4 怎么选

| 场景 | 用哪个 |
|---|---|
| 单机脚本 / 工具里跑一张简单 DAG | `z-util-wf` |
| 单元测试里预演流程走向 | `z-util-wf`，或 z-wf + `InMemoryWorkflowPersistence` |
| 多人并发、需要落库、需要审批中心 | **`z-wf`** |
| 流程带条件判断、且判断错了会出事故 | **`z-wf`**（fail-closed 是硬要求） |
| 需要在同一份 BPMN 上同时要内存与生产两种执行 | 两侧都留，跨引擎只用标准写法，靠 `ZUtilWfBridge` 对接 |

---

## 3. 定义层

支持两种输入，都落成同一个 `WfDefinition`：

- **BPMN 2.0 XML**（`WfXmlParser`）：标准元素 + `zifang:*` 扩展命名空间，带 XXE 防护
  （DOCTYPE 直接拒绝）。扩展属性不改标准元素名，保证标准工具链仍能识别。
- **JSON**（`WfJsonParser`）：LogicFlow 设计器导出（`type` 别名如 `user-task` / `branch` 归一）
  与 z-wf 原生格式。

`WfDefinitionValidator` 部署前校验，一次报全部问题（重复 id、悬空连线、
排他网关有条件但无 `defaultFlow` ⇒ 会卡死、多个开始节点、`serviceTask` 缺 delegate）。

### 3.1 `zifang:*` 扩展属性

```xml
<process id="leaveProcess" name="请假流程" zifang:category="审批">
    <startEvent id="start1" name="提交申请"/>
    <userTask id="task1" name="经理审批"
              zifang:assignee="${leaderId}"          <!-- 支持 EL 动态办理人 -->
              zifang:formKey="leaveForm"
              zifang:candidateGroups="dept-managers"
              zifang:candidateUsers="u1,u2"
              zifang:dueDate="PT24H"
              zifang:requiredVariables="opinion,amount"/>
    <exclusiveGateway id="gw1"/>
    <serviceTask id="taskNotify" zifang:delegateClass="com.x.MyDelegate"/>
    <endEvent id="end1" zifang:resultExpression="${conclusion}"/>
    <sequenceFlow id="f2" sourceRef="gw1" targetRef="a">
      <conditionExpression>days &lt;= 3</conditionExpression></sequenceFlow>
    <sequenceFlow id="f3" sourceRef="gw1" targetRef="b" zifang:defaultFlow="true"/>
</process>
```

`zifang:assignee` 支持 EL（如 `${leaderId}`），取不到变量时 fail-closed（不猜办理人）。

### 3.2 节点类型

`START_EVENT` / `END_EVENT` / `USER_TASK` / `SERVICE_TASK` / `SCRIPT_TASK` / `MANUAL_TASK` /
`SEND_TASK` / `RECEIVE_TASK` / `THROW_EVENT` / `EXCLUSIVE_GATEWAY` / `PARALLEL_GATEWAY` /
`INCLUSIVE_GATEWAY` / `COMPLEX_GATEWAY` / `EVENT_BASED_GATEWAY` / `INTERMEDIATE_CATCH_EVENT` /
`LINK_THROW` / `LINK_CATCH` / `BUSINESS_RULE_TASK` / `SUB_PROCESS` / `CALL_ACTIVITY` /
`TASK` / `BOUNDARY_EVENT`（共 22 种）。

类型名大小写不敏感（`userTask` / `user-task` / `USER_TASK` 归一到同一个）。

**未识别的类型会被标记并在部署期报 ERROR，而不是静默放行。**
解析期仍保持宽松（能读进来才给得出有用的诊断），但退化出来的节点会带上
原始元素名，校验器据此拒绝部署。原因是静默退化不是"少支持一个特性"：
`eventBasedGateway` 退化成人工任务，等于把"多路事件竞速"换成了"等人来点"，
而流程照跑、轨迹照记 completed、作者与实际行为之间零提示。

### 3.3 业务规则任务（求值 DMN 决策表）

`businessRuleTask` 直接求值一张已部署的决策表，与 `serviceTask` 的区别是**不用业务方写代码**，
与 `scriptTask` 的区别是**规则与流程分开部署**（改规则不必重新部署流程）：

```xml
<businessRuleTask id="brt" name="算审批层级"
    zifang:decisionRef="approvalLevel"
    zifang:resultVariable="level"
    zifang:mapDecisionResult="singleEntry"/>
```

`camunda:decisionRef` / `camunda:resultVariable` 两种写法一样认，
Camunda 导出的模型可直接部署。

- **`resultVariable` 必填**。本实现的决策结果没有别的出口（不像 Camunda 还有
  `decisionResult` 局部变量 + 输出映射），不给写进哪个变量的话这个节点等于什么都没做：
  流程照常穿透，且没有任何报错。
- **`mapDecisionResult` 四种**，名字与 Camunda 一致，因为它们描述的是结果的**形状**：
  `singleEntry`（唯一那个值）/ `singleResult`（唯一那行的 Map）/
  `collectEntries`（每行的唯一输出）/ `resultList`（默认，全部行）。
  **映射与结果形状对不上时报错，不取第一条** —— 取第一条会让流程带着一个
  「看起来正常」的结论继续走，而那个结论随命中顺序变。
- **`decisionRef` 可以写成 `${变量}`**，在节点执行那一刻求值；
  裸串一律当字面 key（拿裸串去求值会被当成变量名，而未定义变量是 fail-closed 的）。
- **`decisionRefBinding` 只支持 `latest`（默认）与 `version`**（配合 `decisionRefVersion`）。
  Camunda 的 `deployment` / `versionTag` **明确报错**：本仓的流程与决策分别部署，
  没有共享的部署单元，`ZWF_DECISION` 上也没有标签列。
- **部署期不检查决策是否已部署** —— 流程与决策是两条独立的部署路径，先后顺序是自由的。

决策表的部署与求值见 `POST /api/wf/decisions/deploy` 与
`POST /api/wf/decisions/{key}/evaluate`。

### 3.4 多实例（会签 / 或签）

审批系统的默认需求。任务类节点可挂 `multiInstanceLoopCharacteristics`：

```xml
<userTask id="counterSign" zifang:assignee="${loopAssignee}"
          zifang:loopAssignees="${approvers}">
  <multiInstanceLoopCharacteristics>
    <loopCardinality>3</loopCardinality>
    <completionCondition>${nrOfCompletedInstances >= 2}</completionCondition>
  </multiInstanceLoopCharacteristics>
</userTask>
```

| 写法 | 语义 |
|---|---|
| 不写 `completionCondition` | **会签**：3 个人都办完才算通过 |
| `${nrOfCompletedInstances >= 1}` | **或签**：第一个人办完就放行 |
| `${nrOfCompletedInstances >= 2}` | **计数会签**：2/3 即放行 |

可用循环变量：`loopCounter` / `nrOfInstances` / `nrOfActiveInstances` / `nrOfCompletedInstances`，
以及流程本身的全部业务变量（完成条件常写成 `${nrOfCompletedInstances >= 2 && amount > 1000}`）。

三个要点：

- **实例数从任务反推，不另存计数。** 任务本身就是实例，统计是数出来的，
  不会与 `terminate` 作废、`force-complete` 补办、并发修改之间漂移
- **逐实例派不同人**靠 `zifang:loopAssignees` + `${loopAssignee}`，
  不用 `${approvers[loopCounter]}` —— 实测 z-util 的 EL 不支持变量下标
- 完成条件里的变量名拼错会因 fail-closed 判"不成立"，而会签语义下
  "不成立"= "继续等"，于是流程**永远**卡死且无报错。所以校验器要求完成条件
  必须引用标准循环变量，否则部署期就报错

已知限制：不支持 `collection` 集合迭代与 `isSequential` 串行，配置了会在部署期
报 ERROR —— 宁可部署失败，也不给一个半套实现。

### 3.5 错误边界事件

```xml
<userTask id="approve" zifang:assignee="boss"/>
<boundaryEvent id="onFail" attachedToRef="approve">
  <errorEventDefinition errorRef="APPROVAL_FAILED"/>
</boundaryEvent>
<userTask id="cleanup" zifang:assignee="ops"/>
<sequenceFlow id="f3" sourceRef="onFail" targetRef="cleanup"/>
```

```java
runtimeService.handleBpmnError(taskId, "APPROVAL_FAILED", "查不到档案", vars);
// 或 delegate 里 throw new BpmnError("APPROVAL_FAILED", "...");
```

错误码匹配时 token 走到边界事件、沿出线进补偿分支；无匹配时流程终止并把
错误码写进 `deleteReason`，**绝不静默继续**。

刻意**不支持** BPMN 里"空 errorRef = 捕获所有错误"：宽泛捕获会把不相关的异常
也吸走，让本该崩掉的流程继续走下去。必须显式写明捕获哪一种错误。

### 3.6 升级事件（escalation）

```xml
<userTask id="approve" zifang:assignee="boss"/>
<boundaryEvent id="overdue" attachedToRef="approve">
  <escalationEventDefinition escalationRef="overdue"/>
</boundaryEvent>
<userTask id="director" zifang:assignee="director"/>
<sequenceFlow id="f3" sourceRef="overdue" targetRef="director"/>
```

```java
runtimeService.escalate("overdue", "system", "超时未办，升级处理");
```

升级与信号的长相几乎一样（都是广播、都是按名字匹配），但**后果相反**：
信号叫醒一条分支，升级**打断宿主**（待办作废、token 搬到边界上）。
所以两者是**两个独立的 job 类型、两个独立的投递入口**，
而不是同一个入口加一个开关 —— 让调用方在代码里就能看出自己正在做的是"通知"还是"打断"。

**"换给谁办"由边界的出线节点决定**，引擎不替作者决定（Camunda 的 `escalationConfig`
那一层本实现没有，写成"引擎自动换人"会是假的）。

`cancelActivity="false"` 即非中断型：宿主待办照常开着，另起一条并行分支。
**零个订阅者不是错误**（与抛信号同一条约定），但会写评论留痕。

**部署期挡住的**：

- 与 `messageEventDefinition` / `signalEventDefinition` / `timerEventDefinition` 同时配
  ⇒ ERROR。其中**与定时器互斥最要紧**：定时器判定只认 `timerType` 字段，
  而运行期升级走订阅型分支，两边对同一个节点给出两套判定 ——
  混写会得到一个既不报错、也不到期的哑表。
- 中间捕获事件（升级捕获）⇒ ERROR。它缺的是"在停着的 token 上再长出一条"
  这块机制：现有三种捕获都是"把停着的 token 搬走"，硬套会得到
  「原 token 被搬走且没有第二条」的看起来能跑的错语义。
  **升级的抛事件与边界事件都已支持**，缺的只有捕获这一半。
- 不支持 Camunda 的 `escalationTimer`（到期自动升级），也就是第一条里那条
  "超时自动升级"。

---

## 4. 运行时（自研执行树）

`WfEngine` 的核心是**把"进入节点"和"离开节点"拆成两件事**：

```
start()  = 建根 token + enter(token)
advance() = leave(token) + 沿出线 enter(下一个 token)
```

合并这两件事是自研引擎最容易犯、且症状最误导的错误（同一个待办被重建、流程永不完成）。
四种实现细节直接决定正确性：

1. **并行网关必须 fork 出 N 个独立 token**，不能一个 token 依次走完所有出线
   ——否则所有分支共用一个 executionId，汇合判定与任务归属全部错乱。
2. **汇合判定必须跳过自己**：`context.getProcessExecutions()` 是推进开始时载入的**快照**，
   里面"本 token 自己"那条记录还是推进前的位置，算进 peer 判定会永远不等于网关 id ⇒ 永久卡 join。
3. **引擎改过的既有 token 必须在推进后重新落库**：它既不在新建集合也不在汇合折叠集合里，
   漏存症状是"任务已办结但 token 仍停在原节点"。
4. **推进深度上限 512**，防病态图把栈打爆。

### 网关的汇合语义：三种网关本来就不同

| 网关 | 到达的 token 怎么办 | 依据 |
|---|---|---|
| **排他** `exclusiveGateway` | **各自往下走，不合并**（穿透） | Camunda：joining gateway has a pass-through semantic |
| **并行** `parallelGateway` | 等齐再合并成一条 | BPMN 2.0 |
| **包容** `inclusiveGateway` | 等齐**确实激活了**的那些入线再合并 | BPMN 2.0 |
| **复杂** `complexGateway` | 可配：`joining`（默认）/ `competing`（穿透） | Camunda 把这一层交给实现 |

```xml
<complexGateway id="g" zifang:complexJoin="competing"/>
```

> ⚠️ **行为变更（第 27 轮）**：此前 `isJoin` 只看「多条入线 + 多个来源」而**不看节点类型**，
> 于是**排他网关也把并行 token 合并了**。症状是从 Camunda 导入的模型静默少掉一条
> 并行分支——「法务审」与「财务审」并行结束后经排他网关进入下一步，本引擎只建**一条**
> 待办而 Camunda 建两条。现在已对齐 Camunda：**靠排他网关"合并"两条并行分支的模型，
> 升级后下游会多出一次办理**。想在 Camunda 里表达合并，请用并行或包容网关——
> 那本来就是它们的作用。

复杂网关默认 `joining` 是刻意的：改默认值等于让已上线的模型悄悄换语义，
而同一个文件在升级前后走出不同的图、没有任何提示。非法取值与「在排他/并行网关上写这个
属性」都在部署期报 ERROR。

**完成判定放在 service 层、基于存储层真实状态**（全部 token ENDED **且** 无未完成任务），
不放引擎内——引擎看不见别的请求建的 token 与任务，而那正是多节点部署的常态。

### 4.1 条件求值：fail-closed 是审批红线

`WfExpressionEvaluator` 求值前先扫出表达式里的未定义标识符（跳过字符串字面量、EL 关键字、
紧跟 `(` 的方法名、`.` 后的属性名），**有则直接判 false** 并 WARN，由 `defaultFlow` 兜底。

原因：z-util 的 EL 在变量缺失时把 `null` 当 `0` 参与比较，实测 `amount < 1000` 在 `amount`
未设时求值为 `true`——"金额未知"被当成"金额 0"，本该升级到大额审批的单据悄悄走了低额分支，
**且无任何报错**。

逃生舱是 `z.wf.fail-open`，**默认 `false`**。只有在明确知道"卡住比放行更糟"的非审批场景
（比如纯自动化流水线）才应该打开；打开它等于明确接受"条件写错会静默通过"。

---

### job 的优先级

`zifang:priority` 有**两个出口**，读的是同一个数字：

```xml
<userTask id="approve" zifang:assignee="boss"
          zifang:asyncBefore="true" zifang:priority="90"/>
```

- **任务优先级** —— 待办列表里谁排前面，给人看。
- **job 优先级** —— 队列里谁先被取走执行，给执行器看。异步 job 积压时
  "加急的先办"是刚需，所以 `WfJobQuery#setOrderByPriority(true)` 打开后按
  **priority desc → due asc → job id asc** 取。

两者不另配：各配各的会出现「待办里排最前、流程却最后才跑」。

**排序是开关控制的，不是默认行为** —— 定时器要的是"最早到点的先做"，
默认就按优先级排会让靠后的定时器饿死。同优先级时仍按到期时刻正序，
同级不变成随机顺序（随机的话每次跑的顺序都不一样，没法复现）。

内存与 JDBC **必须给出同一个顺序**；不一致的症状是"开发期跑内存全绿、
换 JDBC 之后偶发乱序"，日志里没有任何异常。

升级既有库时会自动补建 `ZWF_JOB.PRIORITY` 列（`CREATE TABLE IF NOT EXISTS`
对已存在的表不加列）。补出来的列在存量行上是 NULL，而默认优先级是 50 ——
不补的症状是"升级前排队的 job 全变成最低优先级"。

---

## 5. 持久化

`WfPersistence` SPI（25 个方法）两套实现：

- `InMemoryWorkflowPersistence` — 零依赖，用于测试与语义参考。
  深拷贝用 **Java 原生序列化**，不用 JSON 往返：`JsonUtil.toJson` 把 `Date` 序列化成
  epoch-millis 的 `Long`，`fromJson` 还原不回 `Date` 会抛
  `Can not set java.util.Date field ... to java.lang.Long`；且原生序列化免疫
  "新增字段忘补 copy 一行"这个漏洞面。
- `JdbcWorkflowPersistence` — 9 张表（`ZWF_*`）+ 15 个索引 + 乐观锁 CAS。
  冲突抛 `WfOptimisticLockException`（web 层映射为 HTTP 409），存储故障映射 503。

**持久化形状 ≠ 运行时对象**（`WfDefinitionCodec`）：`WfDefinition` 含 `Date startTime`，
直接 JSON 往返会静默变 null。定义层单独编解码，既避开这个坑，也防止运行时字段被顺手写进库。

---

## 6. 服务与扩展

服务：
- `WfRepositoryService` — `deployXml` / `deployJson` / `deploy`、`getLatestDefinition` /
  `getDefinition`（锁版本）、`getAllDefinitions` / `getDefinitionVersions` /
  `getDefinitionsByCategory` / `getAllCategories`、`deployAll`
- `WfRuntimeService` — `startProcessInstance`（可锁版本）、`completeTask`、`advance`、
  `suspend` / `activate` / `terminate`、`getProcessInstance` / `queryProcessInstances` /
  `getProcessInstancesByBusinessKey`、`getTrail`（轨迹）、`getExecutions`（排障）、
  `addComment` / `getComments`
- `WfTaskService` — `claim` / `unclaim` / `transfer` / `delegate` / `withdraw` / `resolve` /
  `updateTask` / `forceComplete` / `jump` / `getTask`
- `WfHistoryService`、`WfDelegateRegistry`

任务语义上刻意区分了两件事：

- **委派（delegate）不转移责任**——只改 `owner`，`assignee` 不动；
- **转办（transfer）才改 `assignee`**。
  委派链用 `delegateChain` 审计代替父子任务，避免任务数膨胀。

### 6.1 钩子（3 个接口，全部 `default` 方法）

| 接口 | 方法 |
|---|---|
| `WfProcessHook` | `onBeforeStart`（可否决）/ `onAfterStart` / `onComplete` |
| `WfTaskHook` | `onBeforeCreate`（可否决）/ `onAfterCreate` / `onAssigneeChanged` / `onBeforeComplete`（可否决）/ `onAfterComplete` |
| `WfNotificationHook` | `notifyTaskAssigned` / `notifyApprovalResult` / `notifyOverdue` |

**钩子异常一律不否决流程**，只有显式 `return false` 才否决。
钩子里的异常会被 `WfHookDispatcher` 捕获并 WARN——一个坏的通知插件不该让整个审批停摆。

**扩展点必须真的被调用。** `onBeforeCreate`（建任务前置校验，可否决）曾长期只声明不触发：
`fireBeforeCreate` 没有任何调用方。业务方按接口 javadoc 实现一条校验规则，
会得到"看起来实现了、实际永不执行"的结果，且没有任何报错。
现在它在 `persistAll` 的**最前面**触发 —— 早于任何持久化，
否则会留下"token 已落库、任务没落、实例没落"的撕裂写。
`WfHookDispatcher.removeTaskHook` 与 `getTaskHooks` 同批补上（此前也是零调用方的死 API）。

> 范围说明：`z-camuda` 那边有 22 个 SPI 接口 + 3 个 hook。`z-wf` 的扩展模型目前以
> **3 个 hook + `WfPersistence` SPI + `WfJavaDelegate` + `WfActivityBehavior` 注册表** 的形态落地，
> 22 个 SPI 尚未逐个实现。这是当前真实范围，不是遗漏声明。

---

## 7. REST API

统一前缀 `/api`，返回 `Result<T>`（z-util-core）。异常映射：乐观锁 409、存储 503、业务 400。

**审批中心** `/api/approval-center`
`GET /dashboard`、`/tasks/todo`、`/tasks/done`、`/tasks/get`、`/tasks/claimable`、
`POST /tasks/complete`、`GET /my-processes`、`/processes/get`、`/processes/definitions`、
`/processes/versions`、`/processes/search`、`POST /processes/start`、`DELETE /processes`

**任务操作** `/api/wf/task`
`POST /transfer`、`/delegate`、`/claim`、`/unclaim`、`/withdraw`、`/force-complete`、`/jump`

**流程操作** `/api/wf/process`
`POST /suspend`、`/activate`、`/terminate`、`/comment`、`/advance`、`/name`、
`GET /comments`、`/trail`、`/overview`、`/executions`

**令牌查询** `/api/wf/executions`：`GET /`（按 `processInstanceId` / `activityId` /
`state` / `variableName` / `variableValue` / `unfinishedOnly` 查「这条 token 停在哪」）、
`GET /count`

> 令牌查询与订阅查询（`/api/wf/subscriptions`）回答的是同一个问题的两半：
> 订阅答「它在等一个事件」，令牌答「它停在哪一步」。只给一半时排障会得出错误结论。

**分组 / 图** `/api/wf/group`：`GET /list`、`/processes`、`/detail`（供设计器渲染）
**引擎自省** `/api/wf/management`：`GET /properties`（版本 / schema 版本 / 存储形态）、
`GET /tables`（名字 + 类型 + 行数）、`GET /tables/count?name=`（名字不存在报 400，不返回 0）
**健康检查** `/api/wf/health`

**Job 运维** `/api/wf/jobs`：`POST /{jobId}/trigger`（没到期也能催，`userId` 可选）、
`GET /`、`GET /count`、`GET /exhausted`

> `trigger` **只对时间触发型（定时器边界 / 事件网关定时器分支）与异步型开放**。
> 订阅型（消息 / 信号 / 升级）手动触发等于替引擎伪造一件没发生的事 ——
> 流程会以为它发生了，而真的那件事随后还会再来一次，**于是同一步走两遍**；
> 事件网关的分支是**竞速**，手动触发会**作废兄弟分支**且不可逆；
> 外部任务是 worker 领的活，绕过租约触发会让 worker 正在做的活同时被引擎推进。
> 三类被拒的**理由文案分别给出**（`GET` 那条错误里就写着），因为它们的后果不同。
> 返回的 `triggered` 布尔用来区分"触发了"与"**该响没响**"（流程已结束 / token 已挪走）——
> 后者不是失败，但也绝不是成功。

鉴权不在本层（由 z-ctc 统一拦截）。但 `force-complete`、`jump` 与 `jobs/{jobId}/trigger`
**不做办理人 / 角色校验**，必须由网关层限制访问——这一点写进了方法的 javadoc 与本 README。
（`trigger` 内部有类型白名单挡掉不该手动的三类，但"谁有权催"是业务问题，引擎不替你定。）

`/api/wf/management` 这组**不返回任何凭据**（连接串、账号、口令一律不给），
但它会把**行数**摊开给调用方——多租户场景里「某租户的单据占多少行」本身就是敏感信息。
生产部署应当按运维角色限制这个路径，别因为「它不给凭据」就当成无害接口。

---

## 8. 配置

```properties
z.wf.enabled=true                    # 总开关
z.wf.persistence=memory              # memory | jdbc
z.wf.deploy-on-startup=true          # 启动时自动扫描并部署
z.wf.process-path=classpath*:processes/*.bpmn
z.wf.fail-open=false                 # 条件求值失败时是否放行（默认 false = fail-closed）
z.wf.auto-initialize-schema=true     # jdbc 模式下自动建表
z.wf.approved-result=approved        # 结果为该值视为"通过"
```

自动部署示例（`z-wf-starter`）：

```
已部署流程定义: key=leaveProcess, version=1, nodes=6, flows=7
已部署流程定义: key=fiveLookEvaluation, version=1, nodes=10, flows=13
启动部署流程定义: 扫描 2 个，成功 2 个
```

---

## 9. 测试

931 个测试，全绿（core 845 / web 16 / admin 70）。

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `WfDefinitionParserTest` | 17 | XML/JSON 解析、校验器、类型归一、与 z-util-wf 的解析 parity |
| `ZUtilWfBridgeTest` | 14 | 协议往返、fail-closed、已知限制、桥接定义真的能跑完审批 |
| `JdbcWorkflowPersistenceTest` | 17 | H2 上的建表 / CRUD / 乐观锁 / 查询 |
| `InMemoryWorkflowPersistenceTest` | 11 | 内存存储语义、深拷贝隔离 |
| `WfEngineEndToEndTest` | 18 | 线性 / 排他 / 并行 / 走默认流 四种审批链 |
| **`WfNodeTypeCoverageTest`** | **16** | **14 种节点类型的实际运行行为**（不是"能解析"） |
| **`WfHookDispatchAuditTest`** | **15** | **11 个扩展点回调逐个验证真的会触发** |
| **`WfMultiInstanceTest`** | **16** | 会签 / 或签 / 计数会签、逐实例派人、收口作废 |
| **`WfBpmnErrorTest`** | **13** | 错误边界路由、作废待办、5 类必须被挡住的配置 |
| **`WfMessageTriggerTest`** | **9** | 消息唤醒 / 信号广播 / 歧义报错 / 不误伤人工任务 |
| **`WfEscalationTest`** | **17** | 升级的中断 / 非中断边界、广播、订阅一次性、零订阅留痕、5 类必须被挡住的配置、codec 往返 |
| **`WfGatewayJoinSemanticsTest`** | **11** | 排他网关穿透、并行/包容仍合并、复杂网关 joining vs competing、穿透后流程仍收敛、部署期挡住、codec 往返 |
| **`WfJobPriorityTest`** | **10** | job 优先级从节点拷贝、两套存储实现同一把尺子、存量库补列、更新时不抹掉、排序是开关 |
| **`WfJobTriggerTest`** | **9** | 只有时间触发型/异步型可提前触发；订阅型、事件网关竞速分支、外部任务三类**分别**说清为什么不行；job 不存在要报错；留痕要点破「停留超时」不适用、且「该响没响」不写假记录 |
| **`WfVariableServiceTest`** | **12** | 变量读写、批量原子性、审计留痕、终态拒绝 |
| **`UnsupportedBpmnElementTest`** | **7** | 未支持元素不许静默退化（XML + JSON 两条入口） |
| `WfAdminEndToEndTest` | 6 | Spring 全栈 + JDBC 落库 + 示例流程端到端 |
| `WfWebApiTest` | 64 | **真实 HTTP**（`RANDOM_PORT` 起容器）：VO 边界、分页 total、异常→状态码、变量端点 |
| `WfJobControllerTest` | 10 | job 运维端点的路径/参数/状态码/响应字段；`triggered=false` 仍是 200；两个 job 端点的 id 字段名一致 |

> 加粗的那几个是**行为审计**而非功能测试。本项目有过三次"实现了、注册了、
> 从来没触发"，静态检查全都看不出来：未支持元素静默退化、`receiveTask` 不等待、
> `notifyOverdue` 零调用点。所以每种节点类型、每个扩展点回调都单独写了
> 断言"它真的会跑"的用例，并且每次修复都做**反向验证**：
> 临时摘掉修复，确认测试由绿转红。

`z-wf-admin` 用 `h2-test` profile，不依赖外部 MySQL / z-config / z-rpc。

> `WfWebApiTest` 单独存在的理由：`WfAdminEndToEndTest` 跑在默认 MOCK 环境，
> 它验证的是 service 层 + 落库，**整个 `z-wf-web` 模块一个端点都没被跑到**。
> 而 web 层恰好是"类能编译、接口能调、线上不对"的高发区
> （VO 漏字段、状态码没接上、`total` 报成当前页条数）。
> 它的断言全部针对**正确行为**而非当前实现，让失败直接指向缺陷。

---

## 10. 示例流程

`z-wf-starter/src/main/resources/processes/`：

- `leaveProcess.bpmn` — 线性审批 + 天数排他分流（≤3 天走 HR，>3 天走总经理）
- `fiveLookEvaluation.bpmn` — 五看评估：并行 fork 出 5 条评分线 → 汇合 → delegate 汇总判定

---

## 11. 开发时被测试逼出来的真实 bug（留档，避免重犯）

这些都是"类能编译、接口能调，但行为是错的"类型，靠肉眼 review 基本发现不了：

| 症状 | 根因 |
|---|---|
| 同一个待办被重建 / 流程永不完成 | `advance()` 把 enter/leave 合成了一步 |
| 汇合永远不通过 | 汇合判定没跳过"快照里的自己" |
| 任务办结了流程还 ACTIVE | `leave()` 改过的既有 token 没落库 |
| 并行分支任务归属错乱 | 并行网关共用单 token，未 fork |
| 大额审批走了低额分支，无报错 | 未定义变量在 EL 里被当 0 |
| 内存存储第一次拷贝就炸 | `Date` 走 JSON 深拷贝变 `Long` |
| 候选人在库里明明有，认领却说不在范围内 | `fromJsonQuietly(json, List.class)` 静默返回空 |
| 流程定义反序列化后 `startTime` 为 null | `WfDefinition` 含 `Date`，JSON 往返静默失败 |
| admin 启动就 `NoClassDefFoundError: DataAccessException` | `optional` 依赖不传递，admin 缺显式 jdbc starter |
| 条件丢失 ⇒ 该审批的单被静默放行 | 桥接丢弃了无法归属的条件（已改为 fail-closed） |
| `WfTaskHook.onBeforeCreate` 实现了但**从没被触发** | `fireBeforeCreate` 无人调用。业务方按接口 javadoc 实现"建任务前置校验"会**静默永不生效且无任何报错** —— 比死代码严重：API 看起来是活的 |
| 委派链（`delegateChain`）**从不落库** | `ZWF_TASK` 表没有这一列，`mapTask` 也不读。`delegate()` 在内存里链是全的，但**每次操作都会从库里重读** ⇒ 链立刻变空。README 却声称它是审计手段。已补列 + 补编解码 + 老库 ALTER 补列 |
| `/processes/search` 的 `total` 恒等于当前页条数 | 拿**已分页**的 `queryProcessInstances().size()` 当总数。已加 `countProcessInstances` SPI，列表与计数共用同一段 WHERE |
| `/process/executions` 把 `arrivedActivities` / `variables` 抖给前端 | 直接返回了 `WfExecution` 持久化实体，违反本仓"VO 边界"约定。已补 `WfViews.ExecutionView` |
| `POST /comment` 与 `GET /comments` 返回字段不一致 | 新建返回 `WfComment` 实体、列表返回 Map，前端会先按一个渲染再被另一个打脸。已统一 |

**一条元教训**（这轮踩了两遍）：改完 `-pl <module>` 只装**当前模块**，
被依赖的兄弟模块跑的是本地仓里的**旧 jar** —— 我的 web 层修复第一次"没生效"就是这么来的。
验证跨模块改动必须 `mvn -pl <module> -am`（`-am` = 连带构建依赖），或先 `install` 上游模块。

---

## 12. 依赖审计

原则：**领域与工具逻辑全部走自研仓（`io.github.yuku123`）**，第三方只承担平台职责。

### 12.1 自研依赖（直接声明）

| 模块 | 自研依赖 |
|---|---|
| `z-wf-core` | `z-util-core`（Result/PageResult）、`z-util-wf-kernel`（协议层）、`z-util-parser-json`（JSON 定义解析）、`z-util-expr-el`（网关条件求值）、`z-util-expr-js`（脚本任务）、`z-util-jdbc`（JDBC 支撑，optional） |
| `z-wf-web` | `z-wf-core`、`z-util-core` |
| `z-wf-starter` | `z-wf-web`、`z-config-spring-boot-starter`、`z-rpc-spring-boot-starter` |
| `z-wf-admin` | `z-wf-starter`/`z-wf-web`/`z-wf-core`、`z-config`/`z-rpc` starter |

**没有** fastjson / gson / dom4j / jdom / javax.el 这类替代品。
JSON 走 `z-util-parser-json`，条件求值走 `z-util-expr-el`，BPMN 语义与 z-util-wf 共用。

### 12.2 第三方依赖（仅平台职责）

Spring Boot（DI + 自动装配 + Web MVC）、Jackson（经 `z-util-core` 传递）、
Log4j2（经 `z-util-core` 传递）、Druid（经 `z-util-jdbc` 传递）、
MySQL 驱动 / H2（admin 运行时）、Knife4j（admin 的接口文档 UI）、
swagger-annotations（web 层 `provided`，只取注解）。

### 12.3 版本口径：一个必须记住的坑

**只定义 `<z-util.version>` 属性是盖不住 `import` 进来的 BOM 的。**

`z-boot-parent:1.0.21` 引入 `z-boot-fleet:1.0.1`，而 fleet 的 `dependencyManagement` 里写的是
`${z-util.version}`。**import 进来的 BOM 是用它自己 pom 的属性上下文解析的**
（fleet 1.0.1 里该属性 = 1.0.14），消费方再定义同名属性不会生效。

实测症状：pom 里属性明明写着 1.0.18，`dependency:tree` 仍然显示 `z-util-core:1.0.14`，
classpath 上同时存在 `z-util-core 1.0.14` 与 `z-util-wf-kernel 1.0.18` —— 同一个仓两个版本。
而协议层（kernel）与解析器分属两个版本，正是"同一套 BPMN 语义出现两份实现"的温床。

正确做法是在**本仓 `dependencyManagement`** 里把用到的 z-util 坐标逐个显式钉住
（本仓的 dependencyManagement 优先级高于 import 的 BOM），**传递依赖也要钉**
（dependencyManagement 对传递依赖同样生效，否则 z-util-jdbc / z-config / z-rpc
拖进来的 z-util-aop、z-util-bc、z-util-expr-sql 会停在 1.0.14）。
本仓已钉 12 个 z-util 坐标，全部解析到 1.0.18。

**已知钉不到的两项**：`z-util-dsl`、`z-util-proxy` 的 1.0.18 **z-util 侧尚未发布**。
它们只出现在 `z-util-jdbc`（optional）分支下，不影响 z-wf 的核心链路，
但这是 z-util 的发布缺口，补齐后本仓的钉版列表可再收紧。

### 12.4 XML 解析为什么没用 z-util-parser-xml

`z-util-parser-xml` 的 `XmlUtil` **没有 XXE 防护**（无 DOCTYPE 拒绝、无 `setFeature`、
无外部实体禁用）。BPMN 定义由业务方上传，是典型的 XXE 攻击面（本地文件读取、
外部实体 SSRF），因此 `WfXmlParser` 保留了带防护的 DOM 实现，并有一条
`rejectsDoctype` 测试守着这个安全属性。

**若要改用 z-util 的 XML 解析，建议先给 `z-util-parser-xml` 补上 XXE 硬化**
（在 z-util 侧改一次、全组织受益），而不是在 z-wf 侧加一层前置 DOCTYPE 拦截 ——
后者只在 z-wf 生效，其他用 `XmlUtil` 的仓仍然是敞开的。

### 12.5 死依赖

`z-wf-web` 的 `lombok` 已移除：全仓零 `lombok` import，VO 全是手写 getter/setter。
