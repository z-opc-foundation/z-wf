# z-wf — 自研流程引擎

> 不依赖 Camunda / Flowable / Activiti 等任何第三方工作流引擎。定义层协议与 `z-util-wf-kernel` 共用，
> 运行时层（token 执行树、网关求值、持久化、REST）全部自研。

`z-camuda` 的定位是"在 Camunda 上封装一层我们的 REST 形态"；`z-wf` 的定位是**换掉引擎本身**。
两者模块切分一致（core / web / starter / admin），所以接入侧的认知可以平移，但底层语义不同。

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
| 存储 | 无（`FileWorkflowPersistencePlugin` 可选） | SPI：内存 / JDBC（6 张表 + 乐观锁 CAS） |
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
`SEND_TASK` / `RECEIVE_TASK` / `EXCLUSIVE_GATEWAY` / `PARALLEL_GATEWAY` / `INCLUSIVE_GATEWAY` /
`SUB_PROCESS` / `CALL_ACTIVITY` / `TASK`。

类型名大小写不敏感（`userTask` / `user-task` / `USER_TASK` 归一到同一个），
未识别的类型退化为 `TASK` 而不是让整份定义解析失败。

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

## 5. 持久化

`WfPersistence` SPI（25 个方法）两套实现：

- `InMemoryWorkflowPersistence` — 零依赖，用于测试与语义参考。
  深拷贝用 **Java 原生序列化**，不用 JSON 往返：`JsonUtil.toJson` 把 `Date` 序列化成
  epoch-millis 的 `Long`，`fromJson` 还原不回 `Date` 会抛
  `Can not set java.util.Date field ... to java.lang.Long`；且原生序列化免疫
  "新增字段忘补 copy 一行"这个漏洞面。
- `JdbcWorkflowPersistence` — 6 张表（`ZWF_*`）+ 10 个索引 + 乐观锁 CAS。
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
`POST /suspend`、`/activate`、`/terminate`、`/comment`、`/advance`、
`GET /comments`、`/trail`、`/overview`、`/executions`

**分组 / 图** `/api/wf/group`：`GET /list`、`/processes`、`/detail`（供设计器渲染）
**健康检查** `/api/wf/health`

鉴权不在本层（由 z-ctc 统一拦截）。但 `force-complete` 与 `jump` **不做办理人校验**，
必须由网关层限制访问——这一点写进了方法的 javadoc 与本 README。

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

90 个测试，全绿。

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `WfDefinitionParserTest` | 17 | XML/JSON 解析、校验器、类型归一、与 z-util-wf 的解析 parity |
| `ZUtilWfBridgeTest` | 14 | 协议往返、fail-closed、已知限制、桥接定义真的能跑完审批 |
| `JdbcWorkflowPersistenceTest` | 13 | H2 上的建表 / CRUD / 乐观锁 / 查询 |
| `InMemoryWorkflowPersistenceTest` | 11 | 内存存储语义、深拷贝隔离 |
| `WfEngineEndToEndTest` | 15 | 线性 / 排他 / 并行 / 走默认流 四种审批链 |
| `WfAdminEndToEndTest` | 6 | Spring 全栈 + JDBC 落库 + 示例流程端到端 |
| `WfWebApiTest` | 14 | **真实 HTTP**（`RANDOM_PORT` 起容器）跑 34 个端点：VO 边界、分页 total、异常→状态码 |

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
| `/processes/search` 的 `total` 恒等于当前页条数 | 拿**已分页**的 `queryProcessInstances().size()` 当总数。已加 `countProcessInstances` SPI，列表与计数共用同一段 WHERE |
| `/process/executions` 把 `arrivedActivities` / `variables` 抖给前端 | 直接返回了 `WfExecution` 持久化实体，违反本仓"VO 边界"约定。已补 `WfViews.ExecutionView` |
| `POST /comment` 与 `GET /comments` 返回字段不一致 | 新建返回 `WfComment` 实体、列表返回 Map，前端会先按一个渲染再被另一个打脸。已统一 |

**一条元教训**（这轮踩了两遍）：改完 `-pl <module>` 只装**当前模块**，
被依赖的兄弟模块跑的是本地仓里的**旧 jar** —— 我的 web 层修复第一次"没生效"就是这么来的。
验证跨模块改动必须 `mvn -pl <module> -am`（`-am` = 连带构建依赖），或先 `install` 上游模块。
