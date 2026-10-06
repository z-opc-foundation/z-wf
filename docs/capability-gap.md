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
| `deploy` / `undeploy` / `deleteDeployment` | ✅ | `deployXml/deployJson/deploy`，REST `POST /api/wf/definitions/deploy`；`deleteDefinition(key, version)`（REST `DELETE /api/wf/definitions/definition`）物理删除。本引擎没有独立"部署"实体 —— 一次 deploy 就是一条 (key, version) 定义，所以与 Camunda 的 deployment 单位一致。**有在途实例时直接拒绝、不做级联删除**：每次推进都按 (key, version) 重新载入定义，定义一删那个实例就再也推不动（报"流程定义不存在"且永不自愈）。撤销部署与终止在办的单是两种决策，调用方先 `terminate` 再删；想"下架但保留在途实例"用 `suspendDefinition` |
| `createProcessDefinitionQuery` 流畅查询 | 🟡 | `queryDefinitions(keyLike, nameLike, suspended)`：key 与显示名都能模糊、停用状态可筛、每个 key 只出最新版本。**仍无**按 category / key 精确 / deploymentId 的组合查询 —— 现有 `getDefinitionVersions` / `getDefinitionsByCategory` 覆盖了大部分场景，暂不另造查询 DSL |
| `suspendProcessDefinitionById` / `activate` | ✅ | `WfRepositoryService#suspendDefinition / activateDefinition`，REST `POST /api/wf/definitions/suspend\|activate`。**真源只有 `ZWF_DEFINITION.SUSPENDED` 一列**（不进 codec，列与图 JSON 各存一份必然漂）。闸门在 `startProcessInstance` 上判、且判的是**持久化那份**而不是入参对象 —— 拿入参判的话，调用方手里停用前取的旧定义就能绕过。已在跑的实例完全不受影响：停用是下架版本，不是终止在跑的 |
| `getProcessModel`（回读 BPMN XML） | ✅ | `WfRepositoryService#getProcessModel(key, version)`，REST `GET /api/wf/definitions/model`。顺带修了一个隐藏缺陷：读路径只 `SELECT DEF_GRAPH`，而 `sourceXml` / `startTime` 存在列里从没被取过 —— 两者在 JDBC 读回来的定义上恒为 null，`getProcessModel` 与部署时间一起失效，且从表结构上完全看不出原因 |
| `getProcessModelGraphic`（流程图） | ✅ | `WfRepositoryService#getProcessDiagram(key, version)`，REST `GET /api/wf/definitions/diagram?key=&version=`，返回 `WfDiagramInfo`（shapes/edges + 一致性核对结果）。解析的是 **BPMN DI 标准段**（`BPMNDiagram`/`BPMNPlane`/`BPMNShape`/`BPMNEdge`），不自造图元 —— 坐标只存在于 XML 的 DI 段，自造等于让用户导入模型后手工重画。**按本地名匹配**（`bpmndi:BPMNShape` / 裸 `<BPMNShape>` 两种写法都认得）：用 `getElementsByTagNameNS` 的话，不少工具保存的模型会"解析成功但一个图元都没有"。**不抛异常**：拿不到图不是部署错误，很多流程是手写的没图，返回 `empty=true` 即可。**带一致性核对**（`missingNodeIds`/`orphanShapeIds`/`orphanEdgeIds`/`missingFlowIds`）：图与逻辑分开存，就必然会出现"图上多一个框/少一根线"，而渲染端只看图元、且无任何报错 |
| `getDefaultProcessDefinition` / `setDefault` | ❌ | |
| `createDeploymentQuery`（按部署批次查） | ❌ | `deployAll` 一次部署多个，但没有"部署批次"这个概念 |

### 1.2 RuntimeService

| Camunda 能力 | z-wf | 说明 |
|---|---|---|
| `startProcessInstanceByKey` / `ById` | ✅ | 三个重载（key / key+version / 定义对象） |
| `startProcessInstanceByMessage` | ✅ | **本轮补上** `WfRuntimeService#startProcessInstanceByMessage` / `#startProcessInstanceBySignal`，REST `POST /api/approval-center/processes/start-by-event`，DTO `WfRequests.StartByEvent`（`messageName` 与 `signalName` **必须且只能填一个**）。前提是定义层**放宽了「恰好一个 startEvent」**这条老规则：BPMN 里「手工发起」与「收到订单才起」是同一流程的两个正常入口，要求唯一等于逼作者把一个流程拆成两个。`WfDefinition#startNode` 只认**无条件**入口（带 `messageRef`/`signalRef` 的不算），跨定义查找走 `eventStartNodes()`。同名事件被多个定义订阅时**报错并点名是哪几个** —— 静默挑一个的后果是「流程起来了但不是预期的那个」，而调用方看不出来 |
| `suspend` / `activate` / `delete` 实例 | ✅ | `terminate` 对应 delete |
| **变量服务** `getVariable(s)` / `setVariable(s)` / `getVariableLocal` / `setVariableLocal` | ✅ | **本轮补上** `WfVariableService`：流程级 get/set/remove/has + 任务级 get/set/remove，批量整批只落一次库，变更留审计 |
| `createProcessInstanceQuery` 流畅查询 | 🟡 | `WfProcessInstanceQuery` 有 10 个条件，但没有 `variableValueEquals`（按变量值查实例，审批系统常用） |
| **`move` / `moveTaskState`**（流程实例迁移） | 🟡 | **本轮补上 `move`**：`WfRuntimeService#move` 按 token 粒度迁移，撤掉源节点的待办、该 token 的 job 与到达记录，再在目标节点**重新进入**；给 `sourceActivityId` 就只迁指定源，不给就迁全部未结束 token。REST `POST /api/wf/process/move`。**刻意不检查图上可达性** —— 运营改流程后图往往已对不上，强行校验等于"改一次流程就得重画一遍"，代价是目标节点必须在定义里存在（部署期之外做存在性校验）。**仍缺** Camunda 的 `moveTaskState`（按任务状态筛选迁移）与迁移过程自身的历史记录类型 |
| `createExecutionQuery` | 🟡 | 只有 `getExecutions(processInstanceId)` 列举，没有按条件查 |
| `createVariableInstanceQuery` | ✅ | **本轮补上** `WfVariableQueryService` + `WfVariableInstanceView` + `WfVariableInstanceQuery`，REST `GET /api/wf/variable-instances` 与 `/count`。回答的是**「这个变量挂在哪一级作用域上」** —— 此前 `getVariables(processInstanceId)` 只能看到流程级那一层，分支级与任务级的值根本不在里面，而并行分支排障问的恰恰是级别。视图是**派生**的（变量在本仓没有独立实体，是三个模型上各自的 Map），所以没有自己的 id，只有「作用域:归属 + 变量名」拼成的临时 id，**只在本次查询期间有效**，不该被持久化成订阅条件。两个需要讲清的默认值：`openTasksOnly` 默认 `true`（任务变量在办结后仍然存在，算进「当前变量」会混进十几条历史表单变量）**但显式点名 `taskId` 时不过滤**（那时返回空列表分不清是「没有变量」还是「被过滤了」，而排障查的恰恰多是已办结的任务）；`includeEngineInternal` 默认 `false`（`loopCounter` 混进来只会让人怀疑查错了）。**一个范围都不给直接报错** —— 本仓的「全系统所有变量实例」只能靠全量取回再过滤，只返回一部分比报错坏得多。超过扫描上限（5000）报错而不是给一份看起来完整的清单 |
| 保存筛选器（Camunda `FilterService`） | ✅ | **本轮补上** `WfFilterService` + `WfFilter` + `WfFilterQuery` + `WfFilterResult`，新增 `ZWF_FILTER` 表（内存 / JDBC 两套实现）。REST `GET/POST /api/wf/filters`、`PUT/DELETE /api/wf/filters/{id}`、`GET /api/wf/filters/{id}/results`。见 §1.5 |
| `createEventSubscriptionQuery` | ✅ | **本轮补上** `WfSubscriptionService` + `WfSubscriptionView`（放 core 不放 web：订阅查询通常由独立部署的监控/运维服务消费，放 web 会把它拖进 Spring MVC 运行时）。REST `GET /api/wf/subscriptions` 与 `/subscriptions/count`，并**并进 `GET /api/wf/process/overview`**。回答的是"这条单子怎么不动了"——在等消息的流程没有待办、轨迹没动、也不报错，没有这张表就只能翻 XML 猜。**job 类型归并成"等什么"**（message/signal/timer/external/async）同时**保留原 jobType** 以区分"打断"与"竞速"；竞速分支额外带 `gatewayId`，让人看得出几条是同一次竞速。**超过扫描上限（2000）直接报错**而不是给一份看起来完整的截断列表 |
| `getActivityInstance`（树形活动实例） | 🟡 | 有扁平轨迹 `getTrail`，没有 Camunda 的树形结构 |
| `messageEventReceived` / `signalEventReceived` | ✅ | `triggerMessage`（点对点）/ `broadcastSignal`（广播），**本轮补上 REST**：`POST /api/wf/process/message` 与 `POST /api/wf/process/signal`。此前只有 Java 入口，纯 HTTP 的调用方根本没法投递事件，事件网关等于对它们不存在。一个端点同时能叫醒三种等待者（事件网关分支 / 消息边界订阅 / receiveTask），谁先判决定了这条事件落到哪种语义上 |
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
| 任务级变量 `setVariableLocal` / `getVariablesLocal` | ✅ | **本轮补上第三层作用域**：`WfVariableService#setVariableLocal(executionId, …) / getVariableLocal / getVariablesLocal / hasVariableLocal / removeVariableLocal`，REST `GET/POST /api/wf/process/branch-variables`（**入参用 `taskId` 而不是 `executionId`** —— 执行树是引擎内部结构，仓里有测试钉着「任务响应里不得出现 executionId」；服务层 `executionIdOfTask` 负责换算）。它填的是**并行分支真正的需求**：两条分支各自要不同的局部值时，写流程级会互相覆盖（后写的赢），写任务级则对条件表达式**完全不可见** —— 只剩「污染全局」或「完全无效」两个都不对的选项。它对条件可见靠的是 `WfContext#mergedVariables()` 早就把当前 token 的变量并了进去（引擎内部量 `loopCounter`/`loopAssignee` 就是走这条路生效的），缺的从来不是求值，而是一个**能让业务方写进去的入口**。**读时不做作用域回退**：token 上没设就是没有，哪怕外层有同名值 —— 回退会让「这条分支覆盖了什么」无法回答，而并行分支排障问的正是这个 |
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
| `createHistoricIncidentQuery` | ❌ | 仍缺。`createIncidentQuery` 只覆盖**当前**故障：job 一旦执行成功就被删掉，"上周三那批单为什么全卡住了"这类事后复盘查不到。要做就得给故障建独立的持久化记录 —— 那正是本轮刻意不建独立表所换来的代价，两者不能各要一半 |

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
| **Job / 定时器** `createJobQuery` / `executeJob` / `setJobRetries` | 🟡 | **本轮补上 job 机制**：`WfJob` + `ZWF_JOB` 表 + `WfJobService#executeDueJobs`（重试计数、耗尽可查）。**本轮再补外部任务**（`externalTask`）：`WfExternalTaskService` + `TOPIC`/`LOCKED_BY`/`LOCK_AT` 三列，租约制领活、`fail` 解锁+退避、重试耗尽留档不删。**异步执行也已补上**（`asyncBefore`/`asyncAfter`，`camunda:` 前缀同样识别）。**剩余**：`jobPriority`、循环定时器、异步 job 的优先级与手动触发 REST 入口 |
| `createIncidentQuery` | 🟡 | **本轮补上** `WfIncidentService` + `WfIncidentView` + `WfIncidentQuery`，REST `GET /api/wf/incidents` 与 `/incidents/count`，并进 `GET /api/wf/process/overview`。回答的是订阅回答不了的那一半：「在等什么」与「已经没干成」必须一起给 —— 只有订阅时，"单子不动了"分不清是在耐心等还是已经炸了，而这两者处置完全不同。**故障从 job 派生，不建 Camunda 那张独立 incident 表**：故障的定义完全由 job 的 `retries` + `lastFailureTime` 决定，另存一份就多一处可能与 job 对不上，而排障时最不能容忍的就是对不上。代价见 `createHistoricIncidentQuery` 那行 |
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
| `boundaryEvent` | 🟡 | **错误边界**：`<errorEventDefinition errorRef>` + `WfRuntimeService#handleBpmnError` + `BpmnError`。**定时器边界**：`<timerEventDefinition>` + Job 执行器。**消息/信号边界**：`<messageEventDefinition messageRef>` / `<signalEventDefinition signalRef>`，token 一进入宿主节点就作为订阅挂在 `ZWF_JOB` 上，`triggerMessage`（点对点）/ `broadcastSignal`（广播）到达时**打断**在办的流程：宿主待办作废、token 走补偿分支。非中断型（`cancelActivity="false"`）**部署期报 ERROR**（需要另一套订阅存活状态，不做半套） |
| **`intermediateCatchEvent`** | 🟡 | ✅ 已实现（消息 / 信号 / **定时器**三种事件定义）：token 停在该节点挂一条 `EVENT_MESSAGE` / `EVENT_SIGNAL` / `EVENT_TIMER` 订阅，**不建人工待办** —— 它等的是消息不是某个人，退化成待办的话事件网关就变成"让 N 个人同时点"。**也支持不经网关的普通用法**（流程里直接写、等消息继续），此时只前进自己不与任何分支互斥。**定时器捕获只支持作为事件网关的分支**（**本轮补上**）：孤立的定时器捕获事件仍报 ERROR —— 它要的是另一条"到点就往下走"的续跑路径，本引擎没有，挂上去会得到永不响也不报错的哑表。**剩余**：`conditionalEventDefinition` / `escalationEventDefinition` / `linkEventDefinition` |
| `intermediateThrowEvent` | ❌ | **部署期报 ERROR**（见 §4），并建议改用等价的 `sendTask` |
| `eventBasedGateway` | 🟡 | ✅ 已实现：token 分叉到各中间捕获事件，**谁的事件先到就走谁，其余分支连同各自的订阅一并作废**（落选分支在轨迹上留 `eventGatewayLost` 一条）。竞速的兄弟集合从**流程定义**反查（捕获事件唯一入线的源头就是网关），不另存副本。订阅用 `EVENT_MESSAGE`/`EVENT_SIGNAL`/**`EVENT_TIMER`** 三种 job 类型，与消息/信号/定时器**边界**订阅分开 —— 后者是打断，前者是竞速，混用时触发路径必须去猜而猜错的后果是流程静默走错分支。**定时器分支本轮补上**：到点即算它赢，其余分支作废。**剩余**：`conditionalEventDefinition` 分支 |
| `complexGateway` | 🟡 | ✅ 已实现：按变量**取值**分派（`zifang:caseVariable` + 出线 `zifang:caseValue`），`camunda:caseExpression` 同样识别。**剩余**：Camunda 侧的后置条件（`condition` 元素）、配对/非配对语义差异 |
| `transaction` / `adHocSubProcess` | ❌ | 同上，报错挡住 |
| **定时器** `timerEventDefinition` | ✅ | `timeDuration`（PT5M / P1DT2H / P1Y）与 `timeDate`（2026-12-31T18:00:00Z）已实现，可写 `${变量}` 由流程实例决定时限。**边界定时器（打断）与事件网关定时器分支（竞速）都已接上执行器**（`TIMER` / `EVENT_TIMER` 两种 job 类型，`WfJobService#executeDueJobs` 逐类型各扫一遍）。**`timeCycle` 循环定时器刻意不支持**，部署期报 ERROR |
| **异步** `asyncBefore` / `asyncAfter` | 🟡 | ✅ 已实现：`zifang:` 与 `camunda:` 双前缀；`ASYNC_BEFORE`/`ASYNC_AFTER` 两个 job 类型 + `WfJobService#executeAsyncJobs`。**剩余**：异步 job 的优先级（`asyncBefore` 配 exclusive/priority）、`timeCycle` 循环定时器、多实例+异步（部署期已挡） |
| **外部任务** `externalTask` / `ExternalTaskService` | ✅ | `serviceTask` + `zifang:topic` 标注（`<externalTask>` 不是 BPMN 2.0 元素，Camunda 同样靠标注在 serviceTask 上）。原子"选出+上锁"、租约制、`fail` 解锁+退避、重试耗尽留档。REST 7 端点在 `/api/wf/external-tasks` |
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

**关于异步的现状**：载体（`WfJob` / `ZWF_JOB` / `WfJobService`）从定时器边界开始就是通用的，
现在定时器、消息、信号、外部任务、异步前置、异步后置七种都落在同一张表上，靠 `JOB_TYPE` 区分。
异步执行做完了，两个方向共用一个执行器但走**相反的续跑动作**：前置 `resumeEnter`（把节点真的跑一遍），
后置 `resumeLeave`（只补"离开"这一步，绝不重跑节点行为）—— 共用一个的话就会在重跑 delegate 和不执行之间二选一。

**剩余的异步缺口**：`asyncBefore` 的 exclusive（互斥，多实例里只跑一个）、优先级，
以及 `timeCycle` 循环定时器。多实例 + 异步已在部署期挡住：单 token 粒度的续跑没有
"等所有实例都离开"的汇合点，放行会让流程在最后一个实例离开时就往前走。

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
| 生命周期监听器 | ExecutionListener / TaskListener，按事件类型注册，几十个事件点 | 3 个 hook 接口共 15 个回调（`WfHookDispatcher`）。本轮做完行为级审计后修掉 3 处失效回调，并补了流转 / job 生命周期 / 任务消失三类事件点，详见下文 |
| 表达式 | JUEL（`${}` / `#{}`） | z-util EL（`${}`） |
| Java Delegate | `JavaDelegate` / `DelegateExpression` / `ClassDelegate` | `WfJavaDelegate` + `WfDelegateRegistry` |
| 外部任务 Worker | `ExternalTaskService` | ✅ `WfExternalTaskService`（fetchAndLock / complete / fail / release / list）。**剩余**：Camunda 侧的 `handleBpmnError` / `handleEscalation` 交回流程、`setVariableLocal`、优先级与批量操作 |

### 3.2 本轮补的事件点

Camunda 差距最大的是细粒度事件点。本轮补了三个（`WfTransitionAndJobHookTest` 逐条证明真触发）：

| 回调 | 触发时机 | 为什么必要 |
|---|---|---|
| `onTransition(from, to, flowId)` | 每次 token 沿连线移动 | Camunda 拆成 transitionStart/End 两个事件；合成一个是因为本引擎"离开"与"到达"发生在同一次推进里，拆开只能拿到一半信息 —— start 拿不到 to，end 拿不到 flowId，而"这条线上跑了多少单"最想要 flowId。**通知型，不能改线**（能改线的扩展点是 serviceTask + delegate） |
| `onJobScheduled` / `onJobExecuted` | job 落库后 / 执行完 | 现在有七种 job，但此前没有任何对外入口。做"这一步平均等了多久""哪个节点的定时器最常被撤"只能改引擎代码。`onJobExecuted` 带 `success`，残留 job 发 false —— 报 true 会让执行成功率凭空好看 |
| `onDeleted(reason)` | 任务被终止 / 被边界打断 / 会签收口时 | 与 `onAfterComplete` **互斥**：办结了走前者，没办结就没了走这条。**撤回不在这里** —— 撤回把任务放回待办，任务并没消失，混进来会让"任务消失率"这个指标彻底失去意义 |

> `onDeleted` 补上后顺带修掉一个语义错误：终止路径原来对被作废的任务发的是
> `fireAfterComplete`（"某人办结了这单"），据此发通知的接入方会发出一条假消息。

**扩展面比 Camunda 窄**，但差距已从"整个生命周期只有 11 个点"缩到
"缺少消息/信号到达、任务字段更新、流程图绘制等少数点"。

---

## 4. 一条重要的行为变更

**不支持的 BPMN 元素现在会在部署时报 ERROR，而不是静默退化成人工任务。**

改之前：解析器把认不出的元素名（`transaction` / `intermediateThrowEvent` /
`adHocSubProcess`）一律退化成 `TASK`，
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
等价替代关系会给出建议（`intermediateThrowEvent` → `sendTask`），但
`eventBasedGateway` 刻意不给——拿 `exclusiveGateway` 顶替它不是简化，
是把"多路竞速"换成"顺序选一"，照着改会得到更难发现的错流程。

> 本节原先还把 `eventBasedGateway` 与 `intermediateCatchEvent` 列为退化元素。
> 这两者已实现（见 §2），退化名单换成了 `transaction` / `intermediateThrowEvent` /
> `adHocSubProcess`；`UnsupportedBpmnElementTest` 里另有一条
> `eventBasedGatewayIsNowNative` 守着"已实现的元素不许再被当成退化节点"。

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
| 4 | ~~**边界事件 + 定时器 + Job 执行器**~~ | ✅ **已实现**：定时器 / 错误 / 消息 / 信号 / 外部 / 异步前置 / 异步后置七种共用 `ZWF_JOB` 一个载体，靠 `JOB_TYPE` 区分。**剩余**：循环定时器、异步 job 优先级 |

### P1 —— 引擎成熟度

历史查询体系（活动 / 任务 / 流程实例 / **变量变更审计** + 历史清理已实现）·
~~Repository 完整化~~（定义停用/启用 + 模型回读 + 定义查询 + 物理删除已实现）·
~~任务挂起~~（suspend/activate + 七处闸门 + 查询过滤 + REST 已实现）·
~~运行时增删候选人~~（含 `candidateOrAssigned` 待办或语义 + 可认领列表按人过滤）· ~~复杂网关~~ · ~~事件网关~~（**消息 / 信号 / 定时器三种分支均已实现**，见 §2；仅缺 `conditionalEventDefinition` 分支）· ~~实例迁移~~（`move` 已实现，见 §1.2；`moveTaskState` 仍缺）· ~~Filter~~（**第 9 轮补上**，见 §1.5）· ~~中间捕获事件的定时器分支~~（**本轮补上**，见 §2）

### P2 —— 管理便利

引擎指标 · ~~流程模型图形回读~~（BPMN DI 解析 + REST 已实现，见 §1.1）

---

## 7. 当前状态小结

- 引擎骨架（token 执行树、汇合、乐观锁、持久化抽象）**扎实**，有 586 个测试兜着
- 从测试与审计中逼出并修复的**真实缺陷 33 项**，其中 4 项属于"能力看着在、实际不生效"：
  未支持元素静默退化、`receiveTask` 不等待、未部署定义启动、`onBeforeCreate` 从未触发
- **本轮（变量实例查询）写出 3 个自己造的缺陷，都在流出前抓住**，但其中两个的形态值得记：
  ① **派生视图的 id 在 setter 之前就拼好了**。`base()` 回头去读视图上的
     `processInstanceId` / `executionId` / `taskId`，而这些字段是逐个 setter 填的，
     于是 id 全成了 `process:null/amount` 这种。**它不报错、三段结构齐全、彼此仍然不同** ——
     而我最初的判据只断言了"三个 id 互不相同"，scope 前缀不同就恒成立，
     判据对这一整类缺陷零区分力。修法是让归属由调用方显式传进 `base()`，
     不依赖赋值顺序；判据改成断言**精确的 id 字符串**。
     ⇒ **「A、B、C 互不相同」这个断言，永远抓不住「A、B、C 全都错成同一个形状」**；
     要钉住取值就得钉住取值本身。
  ② **控制器把可选参数的默认值抹成了 null**。`@RequestParam(required = false)` 缺省给
     `null`，而无条件 `setOpenTasksOnly(null)` 会覆盖掉查询对象里 `TRUE` 的默认值 ——
     端点看上去支持这个开关，实际上「不传」与「传 false」走同一条路，
     **而接口文档里写的默认值是假的**。判据是 REST 层的"默认只列两条"那条断言，
     它在控制器这层变红时立刻就抓到了（core 层测不出来：core 不经过控制器）。
  ③ 改测试时才发现的**同类潜伏项**：`WfIncidentService#incidentsOf` 传
     `pageSize = Integer.MAX_VALUE` 想表示"不分页"，而 `normalizedPageSize()` 会把它
     归一到 1000 —— 于是"这个方法不分页"这句话是假的。故障超过 1000 条的实例
     恰恰是最需要被完整看见的那一种（`MAX_SCAN` 本身就承认了 5000 这个量级）。
     本轮连同 `variablesOf` 一起改走匹配全集，并各补了一条**跨过 1000 的判据**
     （原有用例量级太小，删掉不分页也照样绿）。
- **本轮（运行期故障查询）没有发现已发布的真缺陷，这一点要照实说**：缺陷计数仍是 31。
  本轮消除了一个**潜伏隐患**并当场抓住一个自己写出来的 bug：
  - 潜伏隐患：`WfJob.exceptionMessage` 一列两义 —— 订阅名（消息/信号/边界/网关分支）
    与失败原因共用一列。**当前引擎路径上触发不到**（三处 `recordFailure` 分别只作用于
    `TIMER` / `ASYNC` / `EXTERNAL`，都不写订阅名），但正确性此前**依赖**这条分流一直成立。
    一旦哪条路径对订阅型 job 调了 `recordFailure`，订阅名会被失败信息覆盖，
    之后按名字匹配再也匹配不上，那条订阅等于从引擎里消失且不报错。
    本轮拆成 `SUBSCRIPTION_NAME` 两列（含 JDBC 补列 + 存量回填），
    正确性不再依赖任何分流约定 —— 这是做故障视图的**前置条件**：
    视图要同时说清「它在等什么」与「它报了什么错」，挤在一列就只能二选一
  - 当场抓住：`retriesExhausted` 过滤写反了（拿参数与 `isRetryable()` 直接比大小，
    于是筛"还在重试"时一条都查不出来，而调用方会读成"没有在重试的故障"）。
    写测试时第一轮就红了，未流出
- **本轮实例迁移时逼出 `jump` 的两个同源缺陷**，两个都是"接口返回成功、效果却不对"：
  ① `jump` 推进时调的是 `advance`（语义为"这个节点已经执行过了"，内部走 `leave`），
  于是跳到人工节点时**不建待办、流程一路跑到结束** —— 而"这个审批人不管了，直接跳给总经理"
  恰恰是 `jump` 最主要的用法，跳过去没有待办等于这个操作白做；
  ② `jump` 自己 `new WfEngine()`，丢掉自定义的行为注册表、表达式求值器配置、id 生成器与
  delegate 注册表，表现为"同一条流程跳转前后的节点行为不是同一套"，且全程无任何日志提示。
  修法是让 `jump` 与 `move` 共用一条 `migrateToken` 路径（context 构造、hookDispatcher、
  `persistAll`、终态判定全在 `WfRuntimeService` 内完成），并新增 `WfEngine#enterAt`
  —— 迁移目标**一次都没执行过**，必须走纯 `enter`，复用 `advance`/`startFrom` 都会沿出线跳过它
- **一处"共用一段代码"时最容易踩的坑，本轮主动避开了**：撤源节点待办天然是**按节点**的动作，
  而 `jump` 只针对一条 token。若让 `jump` 也走那条路，多实例节点（同节点 N 条 token N 个待办）
  上跳走一条会把**别人的待办一起撤掉**，那些分支停在原地却没有任何入口能推进它们 ——
  既办不完也查不出来。所以待办撤销留在 `move` 的循环前，`migrateToken` 不碰待办
- **本轮（消息 / 信号启动）同样没有发现已发布的真缺陷**，但有两处工程决定要记：
  ① **放宽了「恰好一个 startEvent」这条老规则**。BPMN 里「手工发起」与「收到订单才起」
     是同一流程的两个正常入口，要求唯一等于逼作者把一个流程拆成两个 ——
     那是把建模限制转嫁到业务上。`startNode()` 现在只认无条件入口，
     带 `messageRef`/`signalRef` 的走 `eventStartNodes()`；
  ② 写完发现消息启动入口里自己查了一遍停用是**冗余**的 —— 真正的闸门在
     `startProcessInstance` 内（所有启动路径共用）。已删掉并把闸门位置写进注释：
     两道一样的闸门不只是冗余，还会让人以为某条路径有它自己的一道
- **本轮（分支级变量）差点报一个不存在的缺陷，值得记**：读代码时发现
  `setTaskVariable` 只写 `task.variables`，而条件求值读 `mergedVariables()`
  （只含流程实例 + 当前 token），于是判定「任务变量对条件不可见」是缺陷。
  写探针实测后发现 `WfVariableServiceTest` 里早有断言 ——
  「任务级变量泄漏到流程级会让条件表达式读到不该读到的值」，
  **实现与既定意图完全一致**，是我的判断错了。
  真缺口在另一头：`mergedVariables()` 早就含 token 变量（引擎内部量就是走这条路），
  缺的只是**一个能让业务方写进去的入口**。
  ⇒ **「读代码看出一处不一致」时，先去找它是不是某条既定契约的体现**；
  找到了就把那条契约补一条护栏测试，找不到再当缺陷报。

- **一处注释里的因果句被探针证伪，已改正**：`arrivedActivities`（token 的到达记录）
  全仓**只写不读** —— 汇合判定 `allSiblingsArrived` 比的是兄弟 token 的 `activityId`，
  并不查这份列表。原先"迁移不清到达记录会导致汇合误判"的说法不成立
  （反向验证摘掉那一行，17 条用例全绿）。仍然清，但注释已改为如实记录现状
- **两处"两套实现语义不一致"值得单独记**：内存版 `lockExternalTasks` 直接改内部引用，
  绕过了 `saveJob` 的乐观锁契约（表现为"领一次活就把 job 永久锁死在乐观锁异常里"）；
  `job INSERT` 写了 15 列却只给 14 个占位符，只有 JDBC 路径能触发 ——
  而开发期默认用内存实现，于是这类问题要么炸在开发期、要么全炸在生产期
- **扩展面明显比 Camunda 窄**（3 个 hook vs 几十个监听点），这是与 Camunda 差距最大、
  也最难靠"补功能"追平的一项
- **本轮新逼出的一处隐性契约**：改一个<b>既有</b> token 的状态（`setState`）必须同时
  `markTouched`，否则 `persistAll` 不会把它写回库。症状是"内存里作废了、库里还是 WAITING" ——
  事件网关的落选分支因此永远留在那儿，流程再也结束不了，而引擎日志一片正常
- **`broadcastSignal` 曾只推进不返回**：三类等待者里，边界订阅那一路的实例没进返回值，
  调用方拿到空列表以为没人订阅，而流程其实已经被打断了
- **一处已登记的潜伏分歧**：`WfPersistence#saveJob` 收到 `id == null` 的 job 时，
  内存实现**静默丢弃**，JDBC 实现会**走 INSERT 插进一行 JOB_ID 为 null 的记录**。
  引擎正常路径总在 `persistAll` 里先分配 id，所以现在触发不到；
  但两套实现的语义必须一样，否则换存储那天才会暴露
- 引擎面缺口按上面 P0/P1 排期推进；身份/表单/鉴权/CMMN 有意不做

> 维护约定：新增或移除一项能力时，**同步改这份文档**。
> 一份会过期的能力表比没有更糟——它会让读者以为"没提到就是不支持"。

### 本轮反向验证记录（分支级变量）

10 条变异：**8 条由绿转红**，1 条保持绿且已如实标注，1 条首轮误判为绿、查实是**流程错误**。

| 变异 | 结果 | 说明 |
| --- | --- | --- |
| 局部变量写进流程级 | 🔴 红 | 两条分支会互相污染 |
| 读局部变量做作用域回退 | 🔴 红 | 「这条分支覆盖了什么」会无法回答 |
| 已结束 token 不拦 / token 不存在不报错 / 删除不生效 | 🔴 红 ×3 | — |
| 审计不记 token | 🔴 红 | 并行分支下没有它就分不清谁改的 |
| REST 忽略 taskId→token 换算 / 删改变成写 | 🔴 红 ×2 | — |
| `taskId` 换算不校验任务存在 | 🔴 红 | **首轮误判为绿**：见下 |
| `getVariablesLocal` 直接返回内部 map | ⚪ 绿 | 如实标注：两套持久化返回的本来就是副本，**服务层拷不拷贝当前测不出差别**。仍保留该写法与断言 —— 它钉的是服务层不把内部引用交出去，而一旦存储层改成缓存同一份对象就会立刻变红 |

**一条流程教训（比上面任何一条判据都值钱）**：验证**跨模块**的变异时，
「跑了目标模块的测试」**不等于**「变异生效」。
本轮改的是 core 里的 `executionIdOfTask`，判据在 admin 的 web 用例里；
只跑 `mvn -pl z-wf-core test` 就得出"没拦住"的结论，而 admin 读的是
`.m2` 里的旧 core jar —— 变异压根没进到被测代码里。
改成先 `install -pl z-wf-core` 再跑 admin 用例，立刻转红。
⇒ 凡是**变异目标模块 ≠ 断言所在模块**，脚本必须先 install 中间模块；
否则"绿"这个信号是假的，而它看起来与"判据漏写"完全一样。

**另一条判据教训**：web 端「任务不存在要报错」最初只断言响应体含「不存在」三个字，
而下游的 token 校验报的是「token 不存在」—— 也含这三个字，于是端点层那道
检查被摘掉照样绿。断言要能区分**两处检查各自负责的那一段文案**。

---

### 本轮反向验证记录（消息 / 信号启动流程）

12 条变异，最终全部消解：**11 条由绿转红**，1 条经查证是**冗余实现**、直接删掉。

| 变异 | 结果 | 说明 |
| --- | --- | --- |
| `startNode` 不排除带触发条件的起始 | 🔴 红 | 否则 `startProcessInstanceByKey` 会走进消息入口那条线 |
| `startAt` 忽略传入的起始节点 | 🔴 红 | **首轮绿**：变异打在了 `start()` 上，而实际走的是 `startAt()` |
| RuntimeService 丢弃算出的起始节点 | 🔴 红 | **首轮 PATCH-NOT-FOUND**：锚点取到了两个同形调用里的另一个 |
| 歧义时静默挑第一个定义 | 🔴 红 | — |
| 跨定义查找不跳过停用版本 | 🔴 红 | — |
| 显式指定定义时不查停用 | ⚪→删 | 真正的闸门在 `startProcessInstance` 的 0.5 步，是所有启动路径共用的那一条；这道检查是**冗余**的，删掉行为不变，留着反而让人以为消息启动有自己一道独立闸门 |
| 找不到订阅者不报错 | 🔴 红 | — |
| 事件名为空不报错 | 🔴 红 | 否则要去全表扫一遍才回来说没有 |
| 定义内不查同消息多起始 | 🔴 红 | 部署期比运行时早 |
| 校验「有事件起始就放过多个无条件起始」 | 🔴 红 | **首轮绿**：老用例里没有事件起始，条件恒真 —— 已补「事件起始 + 两个无条件起始」那条组合用例 |
| REST 两个都填不报错 | 🔴 红 | **首轮绿**：变异只影响「都不填」，而引擎层兜住了照样 400；断言只看状态码时两层检查串在一起没有区分力，改成断言报错文案后转红 |
| REST 绕过引擎改走无条件启动 | 🔴 红 | **首轮绿**：本轮当时还没有 web 端点测试，已补 |

---

### 本轮反向验证记录（运行期故障查询）

15 条变异，**15 条全部由绿转红**。其中 3 条首轮未转红或不可信，查下来是判据缺口，已补测试后复验：

| 变异 | 结果 | 说明 |
| --- | --- | --- |
| 故障判据改成"什么都算" / 改按 `retries` 判 | 🔴 红 ×2 | 后者会把"创建时就配置成不重试"的 job 全算成故障 |
| `retriesExhausted` 不过滤 | 🔴 红 | 两侧必须分得开 |
| 视图改读 `exceptionMessage` | 🔴 红 | 拆列的核心判据 |
| 超量护栏、类型过滤、错误子串过滤失效 | 🔴 红 ×3 | — |
| 实例已消失的 job 跳过不报 | 🔴 红 | 那是清理漏了一步的证据 |
| 列拆分回退：写回旧列 / 匹配读旧列 | 🔴 红 ×2 | — |
| JDBC INSERT / UPDATE 漏新列 | 🔴 红 ×2 | 漏 UPDATE 会把订阅名每次更新都抹成 null |
| 拆掉存量回填 | 🔴 红 | **首轮绿**：原测试库每次都是新建 schema，回填面对的是空的库 —— 已补一条在"老库"上跑的用例 |
| REST `list` 端点丢 `retriesExhausted` | 🔴 红 | **首轮绿**：测试只用了 `count` 端点，两者是两个方法，参数根本没传到 `list` 那条路，而它照样返回一份看起来完整的列表 |
| REST 吞掉拼错的类型 | 🔴 红 | **首轮不可信**：变异串只替换了 `throw` 的前半句，留下悬空拼接导致编译失败 —— 是变异写错，不是代码问题 |

---

### 本轮反向验证记录（实例迁移 `move`）

18 条变异，**16 条由绿转红**；2 条保持绿且各有实证，不是判据漏写：

| 变异 | 结果 | 说明 |
| --- | --- | --- |
| `enterAt` → `advance` | 🔴 红 | 跳/迁到人工节点不建待办 |
| 撤掉源 token 的 job | 🔴 红 | 事件照常到达会推进已迁走的分支 |
| 迁移轨迹记录 | 🔴 红 | 轨迹凭空少一次访问 |
| 撤源节点待办 | 🔴 红 | 源待办与新待办并存 |
| 终态 / 目标存在 / 同源同目标 / 无 token 四处守卫 | 🔴 红 ×4 | 各有专测 |
| 评论记原因 | 🔴 红 | 无法追责 |
| 迁移带变量 | 🔴 红 | 变量合并那行（首次变异打偏到 `startProcessInstance`，换唯一锚点后转红） |
| `persistAll` | 🔴 红 | 迁过去的东西不落库 |
| `jump` 不办结源待办 / 目标存在校验 | 🔴 红 ×2 | — |
| 把 `cancelOpenTasksOn` 塞回 `migrateToken` | 🔴 红 | **故意重现本轮避开的那类回归**：多实例同节点上别人的待办被撤 |
| 换成 `new WfEngine()` | 🔴 红 | 丢掉自定义行为注册表 |
| REST 丢 `sourceActivityId` | 🔴 红 | 变成迁全部 token |
| 清到达记录 | ⚪ 绿 | `arrivedActivities` 只写不读，见 §7 上文 |
| `resolveCompletion` | ⚪ 绿 | 正常路径上 `leave` 已兜住，功能测试对它天然无区分（该结论原就写在 `resolveCompletion` 的注释里，本轮探针复现确认） |

---

### 本轮反向验证记录（变量实例查询）

16 条变异，**16 条由绿转红**（首轮 15 红 1 绿，那 1 条是判据缺陷，已补测试后转红）。
全部在独立 worktree 里跑（`git worktree add` + 逐文件 cp 同步）。

| 变异 | 结果 | 说明 |
| --- | --- | --- |
| `internal()` 不再隐藏引擎内部变量 | 🔴 红 | `loopCounter` 混进「当前变量」 |
| 已结束的分支照样列出局部变量 | 🔴 红 | 终态实例返回一份"曾经存在过" |
| `base()` 的归属取错字段 | 🔴 红 | **复现本轮修掉的 id 缺陷**：`process:null/amount` |
| 控制器把 `openTasksOnly` 默认值抹成 null | 🔴 红 | **复现本轮修掉的第二个缺陷**：默认过滤形同虚设 |
| 取消「一个范围都不给就报错」 | 🔴 红 | 变成"全系统就这几个变量" |
| 显式 `taskId` 不再绕过办结态过滤 | 🔴 红 | 点名已办结的任务返回空 |
| `openTasksOnly` 默认值翻转 | 🔴 红 | 历史表单变量混进当前变量 |
| 摘掉超量护栏 | 🔴 红 | 静默截断一份看起来完整的清单 |
| `matches()` 的作用域过滤失效 | 🔴 红 | 4 条全出 |
| 只给 `executionId` 时不反查实例范围 | 🔴 红 | 退化成全表扫任务，撞上限报无关的错 |
| 任务级变量不再带出节点名 | 🔴 红 | "这个变量属于图上哪个节点"答不出 |
| `countVariables` 不再走过滤 | 🔴 红 | **首轮绿**，见下 |
| `variablesOf` 退回走分页 | 🔴 红 | 1001 条被静默截成 1000 |
| `incidentsOf` 退回走分页 | 🔴 红 | 同上（这是上一轮留下、本轮顺带修掉的） |
| 点名的 token 不存在时不再直接返回空 | 🔴 红 | 变成全表扫 |
| 任务级视图不带 `executionId` | 🔴 红 ×2 | 「按 token 查」漏掉同一层的任务变量 |

**这一轮最值钱的一条判据教训**：`countVariables` 不走过滤这条变异**首轮是绿的**，
查下来是我在 `countMatchesList` 里那条查询**一个过滤条件都没带**（只有 `processInstanceId`），
于是"过滤"在这条查询上是恒等的 —— count 数匹配数与数扫到行数**都等于 4**。
补了一条 `setName("amount")` 的查询（匹配 3、扫到 4）之后立刻转红。

⇒ 与前几轮那条「过滤类断言的期望值必须只有一个取值」是同一条，但这次栽在**另一头**：
不是期望值有两个取值，而是**这条查询压根没有能被过滤掉的差异**。
判据要能区分两个数，前提是这两个数**本来就是不同的**。
一个什么条件都不带的查询，永远区分不出"过滤了"与"没过滤"。

**另一条**：`variablesOf` / `incidentsOf` 的"不分页"断言最初**也没有区分力** ——
用例里的变量数与故障数都是个位数，而分页归一化的上限是 1000。
补成各造 1001 条（`variablesOf` 那条还顺带加了一条对照：
同一批数据走 `listVariables` 确实给 1000）之后才真正钉住。
⇒ **验证"没有上限"的判据，用例的数据量必须越过那个上限**；
否则它验证的是"上限够大"，不是"没有上限"。

---

### §1.5 保存筛选器（本轮）

把一组查询条件存起来，反复用、大家共用。它解决的是"条件输错"这件事：
待办、实例、故障三个列表的筛选条件此前每次都要现输，而输错一个字段的代价是
**少掉的那部分永远没有人会来报**。

**三个设计决定，每个都是对着一种"看着能用、其实不生效"的形态去的**：

1. **存与执行走同一条绑定路径**。`createFilter` / `updateFilter` 在存之前
   就把条件绑进一个真的查询对象里跑一遍（`validate`），绑不动的当场报错。
   分成"存时校验一份、执行时解析另一份"的话，两边迟早漂移，
   而漂移的形态是「存得进去、跑的时候那一条悄悄不生效」——
   本仓反复出现过这个形态（`job` 的一列两义、两套持久化语义差异都是）。
2. **值按字符串严格解析**。`openOnly=yes` 不等于 `true`（也不认 `1/0`、`Y/N`）。
   宽松解析在这里的代价是"打错一个字条件就悄悄变了"，而筛选器是**共享**资源，
   悄悄变了之后没人知道。REST 层还额外拦一道：JSON 里的 `true` / `500`
   天生是布尔与数字，声明成 `Map<String, Object>` 让类型错误在反序列化时就暴露，
   顺手 `String.valueOf` 一下就会把它推迟成"存得进去、跑的时候才炸"。
3. **类型显式存进筛选器且不可改**。`status` 在任务查询与实例查询里是两组不同的枚举，
   跨类型套用只会查出没人认得的清单。Camunda 的 filter 不存类型，
   靠调用方选工厂决定 —— 代价是类型错配时那条条件被**静默忽略**；
   本仓选择存下来并校验：一次错配让整张筛选器用不了，而"用不了"是看得见的。

**刻意不支持的**：`pageNum` / `pageSize` 不可保存。它们是"这一次取第几页"，
不是"要查什么"，存进去会让"翻页"变成"改筛选器"。

**与 Camunda 的差异**：Camunda 的 `Filter` 还带 `authorizationRevisions`
（授权修订号，用于在重启后判定这份筛选器的授权是否仍然有效）。本仓**不做授权**，
所以没有这个字段 —— 加一个恒为 0 的字段只是让接口"看起来像"，
而它意味着什么没人说得清。

**一处刻意的诚实标注**：`WfFilterServiceTest#listOrderIsStable`
（两次列出来的顺序必须一致）对内存实现**没有区分力**，反向验证把 `matched.sort(...)`
整段摘掉它照样绿 —— `ConcurrentHashMap` 对一组固定 key 的遍历顺序是稳定的。
真正有区分力的排序判据放在 `JdbcWorkflowPersistenceTest#filterOrderIsDeterministic`：
SQL 不写 `ORDER BY` 时顺序由存储引擎决定，那里才真的能观察到顺序变了。
留前一条是因为它钉的是**服务层对调用方的承诺**，而一旦内存实现换成顺序不确定的容器
它会立刻变红。

### 本轮反向验证记录（保存筛选器）

19 条变异：**18 条由绿转红**，1 条保持绿且已如实标注（见上）。
首轮有 1 条 PATCH NOT FOUND、1 条构建失败、1 条误报 GREEN，返工后才拿到可信结果 ——
返工原因见文末的「一条比判据本身更值钱的教训」。

| 变异 | 结果 | 说明 |
| --- | --- | --- |
| 布尔放宽成认 `yes/no/1/0` | 🔴 红 | 打错一个字条件就悄悄变了 |
| 不认识的条件名被静默跳过 | 🔴 红 ×2 | 筛出来是一份看起来正常的全量清单 |
| `createFilter` 不校验条件 | 🔴 红 | **首轮 PATCH NOT FOUND**（改过方法名），返工后 4 条判据齐红 |
| `updateFilter` 不比对版本号 | 🔴 红 | 最后写的人赢，改的人读成「我明明改了却没生效」 |
| 改筛选器不拦类型变更 / 删不存在的静默成功 | 🔴 红 ×2 | — |
| 三态布尔空串被当成 `false` | 🔴 红 | 「所有待办」那一档会悄悄只剩未挂起的 |
| 内存实现不按 owner / 类型 / 名字 / 精确名过滤 | 🔴 红 ×4 | — |
| 内存实现不拦过期版本号 | 🔴 红 | 服务层那道是第二道，绕过服务层的调用方要自己也有 |
| JDBC 更新不带 `AND REV=?` / 条件列读回空 | 🔴 红 ×2 | 前者锁形同虚设，后者症状是「明明配了却搜不到」 |
| REST 把 JSON 布尔自动转字符串 | 🔴 红 | **首轮构建失败**（补丁插在不合法位置），改插点后转红 |
| PUT 不传版本号 | 🔴 红 | 第二次改必然冲突，而症状像并发问题、实际是端点没传并发信息 |
| `resourceType` 吐枚举名而非短名 | 🔴 红 ×2 | 同一字段在 `/results` 里是短名，两套拼法只有拼错那一种会出事 |
| 内存实现不定序 | ⚪ 绿 | 如实标注：见 §1.5 上文，判据已挪到 JDBC 侧 |
| JDBC 排序只按 id | 🔴 红 | 见文末：这条判据自己返工过两轮 |

**本轮自己写出来、并当场抓到的三个缺陷**：

1. **`updateFilter` 里把版本号覆盖成"库里现在这份"** ——
   那等于乐观锁永远不会冲突，看上去"更安全"（永远不报错），实际是彻底没有保护。
   这是乐观锁最常见的失效方式。
2. **PUT 端点没把 `existing.getRevision()` 传下去** ——
   症状是「第一次能改、之后全报乐观锁冲突」，看起来像并发问题，
   实际是这个端点压根没把并发信息传下去。
3. **同一个字段名两套词汇**：`resourceType` 在筛选器对象里是 `TASK`、
   在 `/results` 里是 `task`。同一个 REST 层、同一个字段名、两种拼法，
   而拼错的那一种不会报错，只会让筛选器的类型比不上。

**一条比判据本身更值钱的教训（本轮最贵的 20 分钟）**：
反向验证脚本里我用 `grep -qF` 判断"补丁打上了"，再用 Python 做替换。
**`grep` 对多行模式是逐行匹配的** —— 一段并不存在的多行补丁，
只要其中任意一行在文件里存在，`grep` 就返回成功；
而 Python 的 `str.replace` 要求**整段**精确命中，不命中就**静默不改**。
于是变异压根没发生，测试在未变异的代码上通过，被我读成「这条判据没区分力」。

⇒ 「变异没打红」有三种成因：**判据没区分力**、**变异没编译过**、**变异压根没发生**。
后两种看起来与第一种**完全一样**。所以变异脚本里必须有一句
"必须真的改到了"的断言（本轮改成 Python 侧 `assert a in t` 才暴露出来），
`grep` 只能用来做"文件在不在"这类粗筛，**不能用来确认多行补丁打上了**。

### 本轮反向验证记录（事件网关的定时器分支）

10 条变异，**10 红**（`WfEventGatewayTest`，27 条）。逐条都在**独立 worktree**（`/tmp/z-wf-rv10`）里跑，
跑完逐文件 `diff` 确认无残留。

| 变异 | 结果 | 被哪条判据抓住 |
|---|---|---|
| M1 引擎侧去掉 `isEventGatewayBranch` 闸门 | 🔴 1 红 | `runtimeRefusesStandaloneTimerCatch` |
| M2 网关定时器分支改用 `TIMER` 而非 `EVENT_TIMER` | 🔴 3 红 | 落选分支清理 / 订阅视图 / 到点触发 |
| M3 `isEventGatewayJob` 漏掉 `EVENT_TIMER` | 🔴 2 红 | 同上 |
| M4 `fire` 里把校验挪到删除之后 | 🔴 1 红 | `timerJobPointingAtWrongNodeTypeIsRejected` |
| M5 类型/节点类型对不上时不抛 | 🔴 1 红 | 同上（且拿到的是另一条报错文案） |
| M6 `executeDueJobs` 漏扫 `EVENT_TIMER` | 🔴 1 红 | `timerBranchWinsWhenDue` |
| M7 算不出触发时刻时兜一个时刻而不抛 | 🔴 1 红 | `timerBranchWithUnresolvableVariableFails` |
| M8 校验器放行孤立的定时器捕获事件 | 🔴 1 红 | `standaloneTimerCatchIsRejected` |
| M9 `duedate` 算成永不到点的常数 | 🔴 2 红 | `timerBranchWinsWhenDue`（补断言后） |
| M10 `duedate` 时长算错（5 分钟当 1 小时） | 🔴 2 红 | 同上 |

**本轮自己写出来、并被反向验证抓住的两个缺陷**：

1. **落选分支的定时器 job 不被清理**。第一版让网关定时器分支复用 `TIMER`，
   而落选分支的清理（`deleteCatchJobsOf`）是**按类型白名单**删的 ——
   于是消息分支赢的时候，输掉的定时器 job 还挂在库里，到点会去触发一条
   **已经不在那一格上的 token**，把流程静默地多推一遍，且无人报错。
   ⇒ 这直接推翻了"复用 `TIMER`、靠节点类型分派"的设计。**宁可多一个枚举值**，
   也不要让触发路径和清理路径各自去猜"这条是不是竞速"。

2. **`fire` 先删 job 再校验，失败因此记不上**。
   `WfJobService#fire` 的形状是"先删 job 再推进"（并发投递时后到的那次
   根本查不到 job，于是同一条分支不会被走两遍）。可交叉校验是**抛异常**的，
   抛的时候 job 已经不在库里了 —— `recordFailure` 回头 `findJob` 拿到 `null`，
   "执行失败"就只剩一行日志。**而故障是从 job 派生的**，
   于是这条失败在故障视图里**彻底看不见**：一条永远等不到提醒的定时器，
   连报错线索都没有。
   ⇒ 修法不是把校验挪到删除之后（那会把并发保护拆掉），而是把校验
   **提到删除之前**：拆出 `WfRuntimeService#checkTimerJobDispatch`，
   执行器 `fire` 先问"这条 job 到底该按哪条路走"，答不上来就当场失败、
   job 完好地留在原地被记成失败。`fireTimer` 自己也调一次（纯读，无代价），
   直接调它的调用方同样拿到这道闸门。

**两条判据缺口，都在写完测试后由变异暴露**：

- **M5 抓出的是"文案不对"而不是"没拦"**：把 `if (!nodeLooksRight) throw` 改成
  `if (false) throw` 之后，异常确实还是抛了 —— 只是从另一条路径抛的
  （`WfJobService#resolveBoundary`），消息是"指向的边界事件不存在"而不是"对不上"。
  判据断言的是**具体文案**，所以照样转红。
  ⇒ 教训：**判据要断言错误消息本身，不能只断言"抛了"** ——
  后者对"从哪条路抛的"零区分力，而那正是这类交叉校验最容易被绕开的地方。

- **M9 一开始打不红，暴露的是判据本身的形状**。
  `timerBranchWinsWhenDue` 里两处 `executeDueJobs` 都拿 `timerJob.getDuedate()`
  **自己**当基准（早一秒 / 晚一秒）。于是 `duedate` 无论被改成什么 ——
  常数、远期时刻 —— "到点就触发"都照样成立。
  **它量的是自洽，不是正确。**
  ⇒ 补了一条独立基准的断言：`duedate` 必须落在「实例开始时间 + PT5M」的 5 秒容差内
  （base 是 token 的 `enteredTime`，与实例开始相差毫秒级）。
  补完之后 M9、M10 立刻双双转红。
  ⇒ 这与"用 `duedate` 当基准去验 `duedate`"是同一族：
  **断言的基准不能是被断言对象自己导出的量。**

**一条跨轮仍然成立的纪律**（本轮又验证了一次）：
引擎侧那道闸门判的是**启动时手里那个 `WfDefinition` 对象**，不是自己去仓储重读。
而 `InMemoryWorkflowPersistence#findDefinition` 返回的是**副本**（走 codec），
所以「部署时传进去的那个原对象」是干净的，直接拿来启动**等于没绕过部署期**。
⇒ 这条用例必须 `doctored.findDefinition(key, version)` **把定义读回来再启动**，
并用 `assertNotSame` 把"读回来的确实是副本"钉住 ——
否则判据会在一个根本没被扰动的定义上运行，而它看起来是在测引擎侧闸门。

**本轮唯一一处推翻自己此前决定的地方**：
`gatewayOf` 的判定此前在三处各抄一份，理由是"逻辑一眼看得见，抽工具类的收益抵不上"。
本轮它出现在**建 job / 触发 / 部署期校验 / 订阅视图**四处，
三份副本的漂移代价已经超过抽出来的收益，于是收进 `WfDefinition`。
判据是**调用点数**，不是"这段逻辑复不复杂"。
