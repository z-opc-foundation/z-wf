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
| `deploy` / `undeploy` / `deleteDeployment` | 🟡 | 有 `deployXml/deployJson/deploy`，**无撤销部署**；已跑的实例不受影响，但"下架一个流程版本"做不到 |
| `createProcessDefinitionQuery` 流畅查询 | 🟡 | 只有 `getLatestDefinition / getDefinition / getAllDefinitions / getDefinitionVersions / getDefinitionsByCategory`。**无按名称模糊、无 suspended 过滤、无 latestVersion 组合** |
| `suspendProcessDefinitionById` / `activate` | ❌ | 无法停用某个版本。业务含义：老版本流程要停止接受新申请时，现在只能改别的办法绕 |
| `getProcessModel`（回读 BPMN XML） | ❌ | 部署进去读不回原始 XML。**影响模型编辑器集成** |
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
| `addIdentityLink` / `deleteIdentityLink` | 🟡 | 候选人用户/组存在 `WfTask.candidateUsers/candidateGroups` 字段里，但没有 Camunda 那套 identity link（无类型、无用户/组混合的通用关联） |
| **`handleBpmnError`** | ❌ | **P0 缺口。** 没有 BPMN 错误事件，`serviceTask` 抛异常只能整体失败，无法路由到补偿分支 |
| `handleEscalation` | ❌ | |
| **`move` / `moveTaskState`**（流程实例迁移） | ❌ | Camunda 7.15+ 的实例迁移。审批系统改流程时要迁移在途实例，目前只能 `jump` 单个任务 |
| 任务级变量 `setVariableLocal` / `getVariablesLocal` | 🟡 | 本轮已由 `WfVariableService` 覆盖读写，但**没有变量作用域链**：Camunda 的 Local 变量只在当前 execution 可见，z-wf 的任务级变量随任务走、不会下传给子流程 token |
| 任务挂起（suspension state） | ❌ | |
| `withdraw` | ✅ | z-wf 扩展，比 Camunda 多 |

### 1.4 HistoryService

**这是 Camunda 与 z-wf 差距最大的一个服务。**

| Camunda 能力 | z-wf | 说明 |
|---|---|---|
| `createHistoricProcessInstanceQuery` | 🟡 | 只有 `getCompletedInstances` / `getCompletedInstancesByUser` 两个写死口径的方法，**没有可组合的查询对象** |
| `createHistoricTaskInstanceQuery` | 🟡 | 只有 `getDoneList(userId, page)` |
| `createHistoricActivityInstanceQuery` | 🟡 | 只有 `getTrail(processInstanceId)` |
| `createHistoricVariableInstanceQuery` | ❌ | |
| `createHistoricDetailQuery`（变量/字段变更明细） | ❌ | 审计场景常需要"这个变量什么时候被谁改的" |
| `createHistoricIncidentQuery` | ❌ | |
| `deleteHistoricProcessInstance` / `deleteHistoricData` | ❌ | **无历史清理**。长期运行后表只增不减 |

### 1.5 ManagementService

| Camunda 能力 | z-wf | 说明 |
|---|---|---|
| **Job / 定时器** `createJobQuery` / `executeJob` / `setJobRetries` | ❌ | **重大缺口**，见 §2 |
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
| **`multiInstance`**（会签/或签） | ❌ | **P0 缺口。** 审批系统最核心的需求之一——"3 个人都批才算通过"目前只能拆成 3 个节点手写 |
| **`boundaryEvent`** | ❌ | 边界事件。错误/超时/消息边界都挂不了 |
| **`intermediateCatchEvent` / `intermediateThrowEvent`** | ❌ | 中间事件 |
| `eventBasedGateway` | ❌ | 现在会被校验器**报错挡住**（见 §4），不会静默退化 |
| `complexGateway` | ❌ | |
| `transaction` / `adHocSubProcess` | ❌ | 同上，报错挡住 |
| **定时器** `timerEventDefinition` | ❌ | |
| **异步** `asyncBefore` / `asyncAfter` | ❌ | |
| `errorRef` / `escalationCode` / `compensation` | ❌ | |
| `dataObject` / `dataStore` / 数据关联 | ❌ | |
| `linkEvent` | ❌ | |

**关于 `multiInstance` 为什么排 P0**：会签是审批场景的默认需求，不是高级特性。
目前只能把"3 人会签"画成 3 个串行 userTask，一旦有人驳回就无法区分
"这一个人驳回了"还是"会签整体驳回"，审计也说不清。

**关于定时器与异步为什么是"重大缺口"**：没有 Job 就没有
"超时自动提醒""超时自动升级""这一步异步调用外部系统"。
这不是少一个特性，是缺一整条执行机制——需要 Job 存储、执行器、调度器接入。

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
| 1 | **多实例（会签/或签/计数）** | 审批系统默认需求 |
| 2 | ~~**变量服务**~~ | ✅ 本轮已补（`WfVariableService` + REST `GET/POST /api/wf/process/variables`）。剩余缺口：变量实例查询、类型化变量、变量作用域链（execution 级） |
| 3 | **BPMN 错误事件 + `handleBpmnError`** | `serviceTask` 失败目前只能整体崩，无法走补偿分支 |
| 4 | **边界事件 + 定时器 + Job 执行器** | 缺一整条机制：超时提醒/超时升级/异步调用都做不了 |

### P1 —— 引擎成熟度

历史查询体系（四个 Query + 历史清理）· Repository 完整化（定义挂起/撤销/模型回读）·
identity link 与任务挂起 · 复杂网关 · Filter

### P2 —— 管理便利

引擎指标 · 实例迁移（`move`）· 流程模型图形回读

---

## 7. 当前状态小结

- 引擎骨架（token 执行树、汇合、乐观锁、持久化抽象）**扎实**，有 114 个测试兜着
- 本轮从测试与审计中逼出并修复的**真实缺陷 17 项**，其中 4 项属于"能力看着在、实际不生效"：
  未支持元素静默退化、`receiveTask` 不等待、未部署定义启动、`onBeforeCreate` 从未触发
- **扩展面明显比 Camunda 窄**（3 个 hook vs 几十个监听点），这是与 Camunda 差距最大、
  也最难靠"补功能"追平的一项
- 引擎面缺口按上面 P0/P1 排期推进；身份/表单/鉴权/CMMN 有意不做

> 维护约定：新增或移除一项能力时，**同步改这份文档**。
> 一份会过期的能力表比没有更糟——它会让读者以为"没提到就是不支持"。
