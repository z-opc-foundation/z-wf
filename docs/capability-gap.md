# 能力盘点：z-wf 对 Camunda 7 的覆盖情况

> 这份文档的目的不是自夸覆盖率，而是**把差距摆在明面上**，
> 让"z-wf 能不能替代 z-camuda"这个问题有一个可核对答案，而不是靠感觉。

盘点时间：2.0.0
对照基准：Camunda 7（`org.camunda.bpm.*`）的服务面 + BPMN 2.0 元素面
判定方式：逐条在本仓源码核实，标注了核实方式。**没有实测过的能力一律标"未核实"，
不写"应该支持"。**

---

## 0. 先把"覆盖 Camunda"这个词拆开

上一代实现 `z-camuda` 自己的 README 里写"22 个 SPI 扩展接口"，容易让人以为那是 Camunda
的能力面。**不是。** 核实结果：

| 事实 | 证据 |
|------|------|
| `z-camuda-core` 共 38 个 Java 文件 / 2754 行 | `find z-camuda-core -name '*.java' \| wc -l` |
| 其中 22 个 `spi/Camuda*Spi.java` 是**组织自己的业务扩展点**（表单生命周期、Apex 组织架构、审批前后置），从 `ace-platform-sdk` 蒸馏而来 | 文件名与包路径 |
| Camunda 引擎本身只出现在 `pom.xml` 的依赖里 | 全仓 import 无 `org.camunda` |

所以真正要对齐的是**两件事**，本文档分开列：

- **A. Camunda 7 引擎服务面** —— Repository / Runtime / Task / History / Management 等
- **B. 组织那 22 个 SPI** —— 属于业务扩展点，不是引擎能力，见 §5

`z-camuda` 的 REST 层（34 个端点）`z-wf` 已按同样形状覆盖，这部分不构成差距。

---

## 1. 服务面逐项对照

图例：✅ 已实现 · 🟡 部分实现 · ❌ 未实现 · ⛔ 有意排除（见 §5）

### 1.1 RepositoryService

| Camunda 能力 | z-wf | 说明 |
|---|---|---|
| `deploy` / `undeploy` / `deleteDeployment` | 🟡 | `deployXml/deployJson/deploy`，REST `POST /api/wf/definitions/deploy`。**无撤销部署**。"下架某版本"已由 `suspendDefinition` 覆盖（保留历史、只挡新单）；`deleteDeployment` 是物理删除、语义更重，**未做** |
| `createProcessDefinitionQuery` 流畅查询 | 🟡 | `queryDefinitions(keyLike, nameLike, suspended)`：key 与显示名都能模糊、停用状态可筛、每个 key 只出最新版本。**仍无**按 category / key 精确 / deploymentId 的组合查询 —— 现有 `getDefinitionVersions` / `getDefinitionsByCategory` 覆盖了大部分场景，暂不另造查询 DSL |
| `suspendProcessDefinitionById` / `activate` | ✅ | `WfRepositoryService#suspendDefinition / activateDefinition`，REST `POST /api/wf/definitions/suspend\|activate`。**真源只有 `ZWF_DEFINITION.SUSPENDED` 一列**（不进 codec，列与图 JSON 各存一份必然漂）。闸门在 `startProcessInstance` 上判、且判的是**持久化那份**而不是入参对象 —— 拿入参判的话，调用方手里停用前取的旧定义就能绕过。已在跑的实例完全不受影响：停用是下架版本，不是终止在跑的 |
| `getProcessModel`（回读 BPMN XML） | ✅ | `WfRepositoryService#getProcessModel(key, version)`，REST `GET /api/wf/definitions/model`。顺带修了一个隐藏缺陷：读路径只 `SELECT DEF_GRAPH`，而 `sourceXml` / `startTime` 存在列里从没被取过 —— 两者在 JDBC 读回来的定义上恒为 null，`getProcessModel` 与部署时间一起失效，且从表结构上完全看不出原因 |
| `getProcessModelGraphic`（流程图） | 🟡 | web 层有 `/graph` 端点，但那是 z-wf 自己的图元 JSON，不是 BPMN DI |
| `getDefaultProcessDefinition` / `setDefault` | ❌ | |
| `createDeploymentQuery`（按部署批次查） | ❌ | `deployAll` 一次部署多个，但没有"部署批次"这个概念 |

### 1.2 RuntimeService

| Camunda 能力 | z-wf | 说明 |
|---|---|---|
| `startProcessInstanceByKey` / `ById` | ✅ | 三个重载（key / key+version / 定义对象） |
| `startProcessInstanceByMessage` | ❌ | 消息启动流程 |
| `suspend` / `activate` / `delete` 实例 | ✅ | `terminate` 对应 delete |
| **变量服务** `getVariable(s)` / `setVariable(s)` / `getVariableLocal` / `setVariableLocal` | ✅ | **本轮补上** `WfVariableService`：流程级 get/set/remove/has + 任务级 get/set/remove，批量整批只落一次库，变更留审计 |
| `createProcessInstanceQuery` 流畅查询 | 🟡 | `WfProcessInstanceQuery` 有 10 个条件，但没有 `variableValueEquals`（按变量值查实例，审批系统常用） |
| `createExecutionQuery` | 🟡 | 只有 `getExecutions(processInstanceId)` 列举，没有按条件查 |
| `createVariableInstanceQuery` | ❌ | |
| `createEventSubscriptionQuery` | ❌ | |
| `getActivityInstance`（树形活动实例） | 🟡 | 有扁平轨迹 `getTrail`，没有 Camunda 的树形结构 |
| `messageEventReceived` / `signalEventReceived` | ✅ | **本轮新增** `triggerMessage` / `broadcastSignal` |
| `correlate`（关联消息到执行） | ❌ | |
| `getBusinessKey` / `setProcessInstanceName` | 🟡 | businessKey 有；流程名称没有 |

### 1.3 TaskService

| Camunda 能力 | z-wf | 说明 |
|---|---|---|
| `createTaskQuery` 流畅查询 | 🟡 | `WfTaskQuery` 有 16 个条件，覆盖面不错。缺 `taskParentTaskId`、优先级区间、时间区间之外的高级组合 |
| `claim` / `unclaim` / `complete` | ✅ | |
| `delegateTask` / `resolveTask` | ✅ | `delegate` / `resolve`。**语义已定：委派不转移责任（只改 owner），转办才改 assignee** |
| `setAssignee` / `setOwner` / `setPriority` / `setDueDate` | ✅ | `updateTask` |
| `addComment` / `getProcessInstanceComments` | ✅ | |
| `addIdentityLink` / `deleteIdentityLink` | 🟡 | 候选人用户/组存在 `WfTask.candidateUsers/candidateGroups`，**可运行时增删**（`addCandidateUser/Group` / `removeCandidateUser/Group`，REST `POST /api/wf/task/candidate`），BPMN 部署时写入的与运行时加的走同一份数据。**仍缺** Camunda 那套带 type 的通用关联表（participating / starter 等）—— 刻意不另建：审批场景的判定需求现有字段已覆盖，另建一张表会带来两个真源 |
| `handleBpmnError` | ✅ | **本轮补上**：`BpmnError(code, msg)` 抛错 → 路由到匹配的边界事件 → 走补偿分支；无匹配则流程终止并记错误码 |
| `handleEscalation` | ❌ | |
| **`move` / `moveTaskState`**（流程实例迁移） | ❌ | Camunda 7.15+ 的实例迁移。审批系统改流程时要迁移在途实例，目前只能 `jump` 单个任务 |
| 任务级变量 `setVariableLocal` / `getVariablesLocal` | 🟡 | 本轮已由 `WfVariableService` 覆盖读写，但**没有变量作用域链**：Camunda 的 Local 变量只在当前 execution 可见，z-wf 的任务级变量随任务走、不会下传给子流程 token |
| 任务挂起（suspension state） | ✅ | `WfTaskService#suspendTask / activateTask`，REST `POST /api/wf/task/suspend\|activate`。**挂起后仍留在待办列表并带 `suspended` 标记**（前端显示暂停角标），刻意不隐藏 —— 挂起常是「等条件成立」不是「单子不存在」，藏起来用户的感受是「我那张单不见了」。闸门覆盖认领/办结/转办/委派/撤回/强制完成/跳转**全部七处**，且报错文案与「已结束」分开：挂起能一键恢复，报成结束会让人去查历史而不是恢复 |
| `withdraw` | ✅ | z-wf 扩展，比 Camunda 多 |

### 1.4 HistoryService

| Camunda 能力 | z-wf | 说明 |
|---|---|---|
| `createHistoricActivityInstanceQuery` | ✅ | `WfHistoricActivityInstanceQuery`：流程实例 / 定义 key / 活动 id / 活动类型 / 办理人 / 时间区间 / 最短耗时，可组合（AND）、可分页、可按耗时倒序 |
| `createHistoricTaskInstanceQuery` | ✅ | 复用 `WfTaskQuery` + `WfHistoryService#queryCompletedTasks`（强制只查已办结）。**不另造查询类**：本引擎没有独立历史表，"历史任务"就是 `STATUS=COMPLETED` 的行，字段与运行态查询完全重合。`getDoneList(userId, page)` 作为写死口径的便捷入口保留 |
| `deleteHistoricProcessInstance` / `deleteHistoricData` | ✅ | `deleteHistoryBefore(Date)`：**只删已结束流程**。时间点为 `null` 直接拒绝，不当"清掉全部" |
| 环节平均耗时（Camunda 无对应 API，扩展） | ✅ | `getAverageDurationByActivity` |
| `createHistoricProcessInstanceQuery` | ✅ | 复用 `WfProcessInstanceQuery` + `WfHistoryService#queryFinishedProcesses`。新增 `finishedOnly` / `unfinishedOnly` 开关 —— 终态有三种（正常完成/外部终止/内部终止），单个 `status` 字段表达不了"已结束"；写死成 `COMPLETED` 会让被终止的单子从历史里消失，而"这单怎么没的"恰恰是事后最常被问的问题。旧的 `getCompletedInstances` / `getCompletedInstancesByUser`（零调用方、全量拉取、只认 COMPLETED）已删除 |
| `createHistoricVariableInstanceQuery` | ⚠️ 部分 | 能查**变量最终值**（`WfRuntimeService#getVariables` + 历史流程实例组合），但没有 Camunda 那种"历史变量实例"独立实体。本引擎变量是存在流程实例上的 KV，Camunda 的 HistoricVariableInstance 语义（每变量一行、有独立生命周期）没有一一对应物，**刻意不硬造** |
| `createHistoricDetailQuery`（变量/字段变更明细） | ✅ | `WfVariableAuditQuery` + `WfHistoryService#queryVariableChanges` / `countVariableChanges`，REST `GET /api/wf/history/variable-changes`。条件：流程实例 / 变量名 / 操作人 / 时间区间，可组合 + 真实分页。**批量写是「一个变量一条」审计**而不是整批拼一条 —— 拼一起就没法按变量名精确查（查 `amount` 会顺带命中 `discount_amount`），变量名按 `LIKE 'name:%'` 前缀匹配且对 `%` / `_` 转义（对外承诺精确匹配，且无二次判定兜底，通配符会直接进审计结果）。倒序返回（与轨迹正序相反），同毫秒由 id 兜底；id 序号**定长补零**以保证字典序 == 插入序 |
| `createHistoricIncidentQuery` | ❌ | |

> **本轮修掉的待办/候选相关缺陷**（都是"接口正常返回、内容不对"这一类，最难自查）：
> ① `getTodoList` 的 `groups` 参数接进来就被丢弃，待办列表**只查 assignee/owner，不查候选池**
>    —— BPMN 候选池配得再对，候选人一条待办都看不到；
> ② `getClaimableList` **只按候选组过滤、把 userId 丢了**，于是按 `candidateUsers` 配的流程谁也认领不了，
>    且每个人的可认领列表完全一样；
> ③ `countClaimableList` 连 userId 参数都没有 ⇒ 不同用户的 total 相同、前端翻页对不上。
>
> 根因是同一个：身份关系（办理人/责任人/候选用户/候选组）本该是**或**，
> 以前被当成**且**分别过滤。新增 `WfTaskQuery#setCandidateOrAssigned(true)` 表达"取或"语义，
> 内存与 JDBC 两套实现逐条对应。

### 1.5 ManagementService

| Camunda 能力 | z-wf | 说明 |
|---|---|---|
| **Job / 定时器** `createJobQuery` / `executeJob` / `setJobRetries` | 🟡 | **本轮补上 job 机制**：`WfJob` + `ZWF_JOB` 表 + `WfJobService#executeDueJobs`（重试计数、耗尽可查）。**剩余**：只有定时器边界一种 job，异步执行（`asyncBefore/asyncAfter`）、外部任务（`externalTask`）、`jobPriority`/定时器事件订阅都没做；也没有 `createJobQuery` 的 REST 入口 |
| `createIncidentQuery` | ❌ | 无运行期故障的概念 |
| `createMetricQuery`（引擎指标） | 🟡 | 只有 `getProcessStatusCounts` 一个自定义统计 |
| `getTableCount` / `getTableNames` / `getProperties` | ❌ | |
| 诊断 / 历史级别调整 | ❌ | |

### 1.6 其余服务

| 服务 | 结论 |
|---|---|
| IdentityService | ⛔ 有意排除，见 §5 |
| FormService | ⛔ 有意排除，见 §5 |
| AuthorizationService | ⛔ 有意排除，见 §5 |
| FilterService（保存的查询） | ❌ 未实现。管理台"保存筛选条件"这类需求目前要业务方自己存 |
| ExternalTaskService | ⛔ 有意排除，见 §5 |
| DecisionService（DMN） | ❌ 未实现。规则判断目前靠条件表达式 |
| CaseService（CMMN） | ⛔ 有意排除，见 §5 |
| Batch | ❌ 未实现 |

---

## 2. BPMN 2.0 元素覆盖

`z-wf` 支持 14 种节点类型（`WfNodeType`）。逐个对照 BPMN 2.0：

| BPMN 元素 | z-wf | 备注 |
|---|---|---|
| `startEvent` / `endEvent` | ✅ | |
| `userTask` / `serviceTask` / `scriptTask` / `manualTask` | ✅ | |
| `receiveTask` | ✅ | 等待语义本轮才真正修好（此前建了任务却被丢弃） |
| `sendTask` | ✅ | 与 `serviceTask` 共用行为，即同步跑一个 delegate。**没有 delegate 会让流程失败**——因为它不是 BPMN 那种抛消息 |
| `task` | ✅ | |
| `exclusiveGateway` / `parallelGateway` / `inclusiveGateway` | ✅ | |
| `callActivity` | ✅ | 本轮修好 `resultExpression` 死字段（解析了但从不求值），并拆出 `resultVariable`；被调流程启动失败不再被吞掉 |
| **嵌入式 `subProcess`** | ❌ | **内联内容永远不执行。** 解析器把内联节点收进扁平表，引擎却直接穿透。现在部署期报 ERROR 挡住，可用 callActivity 代替 |
| **`multiInstance`**（会签/或签/计数） | 🟡 | **本轮补上**：`loopCardinality` + `completionCondition`，任务类节点并行展开。缺 `collection` 集合迭代与 `isSequential` 串行（部署期报 ERROR 挡住，不做半套） |
| `boundaryEvent` | 🟡 | **本轮支持错误边界**：`<errorEventDefinition errorRef>` + `WfRuntimeService#handleBpmnError` + `BpmnError`。超时/消息/信号边界仍不支持 |
| **`intermediateCatchEvent` / `intermediateThrowEvent`** | ❌ | 中间事件 |
| `eventBasedGateway` | ❌ | 现在会被校验器**报错挡住**（见 §4），不会静默退化 |
| `complexGateway` | ❌ | |
| `transaction` / `adHocSubProcess` | ❌ | 同上，报错挡住 |
| **定时器** `timerEventDefinition` | ✅ | `timeDuration`（PT5M / P1DT2H / P1Y）与 `timeDate`（2026-12-31T18:00:00Z）已实现，可写 `${变量}` 由流程实例决定时限。**`timeCycle` 循环定时器刻意不支持**，部署期报 ERROR |
| **异步** `asyncBefore` / `asyncAfter` | ❌ | |
| `errorRef` / `errorEventDefinition` | ✅ | 见上。**刻意不支持「空 errorRef = 捕获所有错误」**——宽泛捕获会把不相关异常也吸走，让本该崩的流程继续走 |
| `escalationCode` / `compensation` | ❌ | |
| `dataObject` / `dataStore` / 数据关联 | ❌ | |
| `linkEvent` | ❌ | |

**关于 `multiInstance`**：会签是审批场景的默认需求。本轮已实现并行会签，
但仍有两处已知限制：

- 不支持 `collection` 集合迭代与 `isSequential` 串行，配置了会在部署期报 ERROR
- **EL 不支持变量下标**：实测 `${approvers[loopCounter]}` 抛 ElException
  （`${approvers[1]}` 可以）。所以逐实例派不同人靠 `zifang:loopAssignees="${approvers}"`
  + `zifang:assignee="${loopAssignee}"`，索引在分叉时用 Java 取，不在表达式里做

**关于异步为什么仍是缺口**：job 载体本身已经在位（`WfJob` / `ZWF_JOB` / `WfJobService`），
定时器边界事件也已经跑通"超时提醒/超时升级"。但**异步执行**（`asyncBefore` / `asyncAfter`
把这一步丢到 job 队列）与**外部任务**（`externalTask` 把这一步交给业务系统领走）
还没有对应实现 —— 两者都只差"谁去执行这一步"，载体却已通用。
先做定时器是因为它有确定的到期时刻、不需要外部系统参与。

---

## 3. 扩展点


### 3.1 扩展点审计结果（本轮）

对 11 个回调逐个做了行为级验证（`WfHookDispatchAuditTest`），
找出 **3 处"实现了但从不触发"**——静态检查全看不出来，因为方法存在、
接口实现完整、编译通过，只有真跑一遍流程才知道：

| 回调 | 问题 | 影响 |
|---|---|---|
| `notifyOverdue` | **全仓零调用点**，是死钩子 | 想接超时提醒的团队发现自己的实现永远不会被调，而从代码上看一切齐全 |
| `notifyTaskAssigned` | 只在建任务时触发一次 | 认领/转办/委派之后接手人收不到通知——而"这单到你手上了"恰恰是审批场景最该通知的时刻 |
| `onComplete`（终止路径） | `terminate()` 一个钩子都不发 | 流程被终止后审批人永远不知道这单已作废，待办消失但对方只当是自己被收回了权限 |

现在补了 `WfOverdueScanner`（扫描超期待办并触发通知，**刻意不自带定时器**——
频率是业务决定的，不该由引擎猜）、4 处指派通知、以及终止路径的收尾钩子。

> 这一节的教训与 §4 相同：**"接口存在 + 方法实现完整"不构成"它会触发"。**

| 维度 | Camunda 7 | z-wf |
|---|---|---|
| 生命周期监听器 | ExecutionListener / TaskListener，按事件类型注册，几十个事件点 | 3 个 hook 接口共 11 个回调（`WfHookDispatcher`）。本轮做完行为级审计后修掉 3 处失效回调，详见下文 |
| 表达式 | JUEL（`${}` / `#{}`） | z-util EL（`${}`） |
| Java Delegate | `JavaDelegate` / `DelegateExpression` / `ClassDelegate` | `WfJavaDelegate` + `WfDelegateRegistry` |
| 外部任务 Worker | `ExternalTaskService` | ⛔ 无（用 `serviceTask` + delegate 代替） |

**扩展面比 Camunda 窄很多**，这是实话。Camunda 的监听器可以挂在
"任务创建前/后、实例启动/结束、变量更新、流程图绘制"等几十个点上；
z-wf 的 3 个 hook 覆盖不到那么多细粒度的时机。

---

## 4. 一条重要的行为变更

**不支持的 BPMN 元素现在会在部署时报 ERROR，而不是静默退化成人工任务。**

改之前：解析器把认不出的元素名（`eventBasedGateway` / `transaction` /
`intermediateCatchEvent` / `adHocSubProcess`）一律退化成 `TASK`，
校验器一条 issue 都不报，部署照过。探针实测：

```
eventBasedGateway -> TASK    transaction -> TASK
intermediateCatchEvent -> TASK   adHocSubProcess -> TASK
校验器 hasError=false，issues 为空
```

作者写的是事件网关，部署出去的是"建个人工任务等人来点"——流程语义整个被换掉，
且没有任何提示。这比"部署失败"危险得多。

改之后：解析期仍然宽松（能读进来才给得出有用诊断），但退化出的节点会带上
原始元素名，校验器报 **ERROR**，`deploy` 据此拒绝部署。
等价替代关系会给出建议（`intermediateThrowEvent` → `sendTask`），
但 `eventBasedGateway` 刻意不给——拿 `exclusiveGateway` 顶替它不是简化，
是把"多路竞速"换成"顺序选一"，照着改会得到更难发现的错流程。

---

## 5. 有意排除的部分

以下不是"没来得及做"，是**设计上决定不做**。理由写在这里，避免后来者误以为是遗漏。

| 服务 | 为什么排除 |
|---|---|
| **IdentityService** | 用户与组织架构由 z-ctc 统一管。引擎里存 `userId` 字符串，不建用户表。理由：身份数据是全组织共享的，流程引擎不该是它的第二个来源 |
| **FormService** | 表单是独立系统的事。引擎只透传 `formKey`，由前端/表单服务解释。理由同上 |
| **AuthorizationService** | 鉴权在 z-ctc 统一拦截。`force-complete` / `jump` 这类高危操作**不做办理人校验**是刻意的——它们是管理端操作，权限判断属于调用方职责 |
| **ExternalTaskService** | z-wf 已有 `serviceTask` + delegate 覆盖同样的场景，且不需要额外的拉取协议。Camunda 的 worker 模型适合"外部系统主动来领活"，审批系统不是这个形态 |
| **CaseService（CMMN）** | 审批是确定性的流程编排，不是探索式案例管理。引入 CMMN 会让定义层复杂度翻倍而用不上 |

这五条的共同点：**它们在组织里已经有更合适的归属**。
z-wf 的定位是"审批流程引擎"，不是"Camunda 的完整复刻"。

---

## 6. 优先级与排期

### P0 —— 缺了就是不能替代

| # | 项目 | 理由 |
|---|---|---|
| 1 | ~~**多实例（会签/或签/计数）**~~ | ✅ 本轮已实现（并行）。剩余：`collection` 迭代、串行、变量下标 EL |
| 2 | ~~**变量服务**~~ | ✅ 本轮已补（`WfVariableService` + REST `GET/POST /api/wf/process/variables`）。剩余缺口：变量实例查询、类型化变量、变量作用域链（execution 级） |
| 3 | ~~**BPMN 错误事件 + `handleBpmnError`**~~ | ✅ 本轮已实现（错误边界）。剩余：escalation / compensation / 超时与消息边界 |
| 4 | ~~**边界事件 + 定时器 + Job 执行器**~~ | ✅ **本轮已实现**：定时器边界事件（PT 时长 / ISO 时刻 / `${变量}`）+ Job 存储 + `WfJobService` 执行器 + 重试与耗尽可查。**剩余**：异步执行（`asyncBefore/asyncAfter`）、外部任务（`externalTask`）、消息/信号边界事件、循环定时器 |

### P1 —— 引擎成熟度

历史查询体系（活动 / 任务 / 流程实例 / **变量变更审计** + 历史清理已实现）·
~~Repository 完整化~~（定义停用/启用 + 模型回读 + 定义查询已实现；**剩余**：deleteDeployment 物理撤销）·
~~任务挂起~~（suspend/activate + 七处闸门 + 查询过滤 + REST 已实现）·
~~运行时增删候选人~~（含 `candidateOrAssigned` 待办或语义 + 可认领列表按人过滤）· 复杂网关 · Filter

### P2 —— 管理便利

引擎指标 · 实例迁移（`move`）· 流程模型图形回读

---

## 7. 当前状态小结

- 引擎骨架（token 执行树、汇合、乐观锁、持久化抽象）**扎实**，有 311 个测试兜着
- 本轮从测试与审计中逼出并修复的**真实缺陷 18 项**，其中 4 项属于"能力看着在、实际不生效"：
  未支持元素静默退化、`receiveTask` 不等待、未部署定义启动、`onBeforeCreate` 从未触发
- **扩展面明显比 Camunda 窄**（3 个 hook vs 几十个监听点），这是与 Camunda 差距最大、
  也最难靠"补功能"追平的一项
- 引擎面缺口按上面 P0/P1 排期推进；身份/表单/鉴权/CMMN 有意不做

> 维护约定：新增或移除一项能力时，**同步改这份文档**。
> 一份会过期的能力表比没有更糟——它会让读者以为"没提到就是不支持"。
