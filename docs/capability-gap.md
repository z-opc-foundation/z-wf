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
| `getDefaultProcessDefinition` / `setDefault` | ✅ | **第 15 轮补上**：`WfRepositoryService#setDefaultDefinition(key, version)` / `clearDefaultDefinition` / `getDefaultDefinition`，运行期 `WfRuntimeService#startDefaultProcessInstance`（不传 key 也能发起），REST `GET/POST/DELETE /api/wf/definitions/default` 与 `POST /api/approval-center/processes/start-default`。**三条刻意的设计**：① 默认指向一个**特定的 (key, version)**，不跟最新版本漂 —— 运营在默认流程上做的验证不该被一次无关的重新部署改掉；② 置位**排他**（设新的自动取消旧的），因为"默认是哪个"必须只有一个答案，而库里出现两条时**查询直接报错**而不是返回其中一条；③ **默认必须能启动** —— 设默认时拒掉已停用的版本，否则「不知道 key 时也能发起一个」会变成「不知道 key 时撞上一个出现在别处的报错」。**停用不带走默认标记**（取消默认是一次显式的运营决策）。真源只有 `ZWF_DEFINITION.IS_DEFAULT` 一列，**不进 codec** |
| `createDeploymentQuery`（按部署批次查） | ❌ | `deployAll` 一次部署多个，但没有"部署批次"这个概念。**刻意不造**：一次 deploy 就是一条 (key, version) 定义，Camunda 的 deployment 是「一个包里若干个 BPMN + 若干资源」的**打包单位**，而本引擎不存资源包 —— 为一个查不出来的实体建一张表，只会多出一个永远为空的真源 |

### 1.2 RuntimeService

| Camunda 能力 | z-wf | 说明 |
|---|---|---|
| `startProcessInstanceByKey` / `ById` | ✅ | 三个重载（key / key+version / 定义对象） |
| `startProcessInstanceByMessage` | ✅ | **本轮补上** `WfRuntimeService#startProcessInstanceByMessage` / `#startProcessInstanceBySignal`，REST `POST /api/approval-center/processes/start-by-event`，DTO `WfRequests.StartByEvent`（`messageName` 与 `signalName` **必须且只能填一个**）。前提是定义层**放宽了「恰好一个 startEvent」**这条老规则：BPMN 里「手工发起」与「收到订单才起」是同一流程的两个正常入口，要求唯一等于逼作者把一个流程拆成两个。`WfDefinition#startNode` 只认**无条件**入口（带 `messageRef`/`signalRef` 的不算），跨定义查找走 `eventStartNodes()`。同名事件被多个定义订阅时**报错并点名是哪几个** —— 静默挑一个的后果是「流程起来了但不是预期的那个」，而调用方看不出来 |
| `suspend` / `activate` / `delete` 实例 | ✅ | `terminate` 对应 delete |
| **变量服务** `getVariable(s)` / `setVariable(s)` / `getVariableLocal` / `setVariableLocal` | ✅ | **本轮补上** `WfVariableService`：流程级 get/set/remove/has + 任务级 get/set/remove，批量整批只落一次库，变更留审计 |
| `createProcessInstanceQuery` 流畅查询 | 🟡 | `WfProcessInstanceQuery` 有 10 个条件，但没有 `variableValueEquals`（按变量值查实例，审批系统常用） |
| **`move` / `moveTaskState`**（流程实例迁移） | ✅ | `WfRuntimeService#move` 按 token 粒度迁移，撤掉源节点的待办、该 token 的 job 与到达记录，再在目标节点**重新进入**；给 `sourceActivityId` 就只迁指定源，不给就迁全部未结束 token。REST `POST /api/wf/process/move`。**刻意不检查图上可达性** —— 运营改流程后图往往已对不上，强行校验等于"改一次流程就得重画一遍"，代价是目标节点必须在定义里存在（部署期之外做存在性校验）。**`moveTaskState` 不是缺口**（第 15 轮订正）：它属于 Camunda 的 **standalone task** 体系 —— `TaskService#newTask()` 建的是"不挂任何流程实例的独立任务"，`moveTaskState` 搬的是那种任务在 `Created/Assigned/Completed/Canceled/Failed` 之间的位置。本引擎**没有独立任务**这个概念（`WfTask` 一律由 `WfUserTaskBehavior` 建出，必带 `processInstanceId` + `definitionId`），而"把一个流程内任务换状态"这件事现有能力已经全覆盖：`claim`/`unclaim`/`updateTask`(转办/改责任人)/`delegate`/`resolve`/`complete`/`withdraw`/`force-complete`/`suspend`/`activate`。为对齐一个数字而把 standalone task 这整套引入，代价远大于收益 |
| `createExecutionQuery` | ✅ | **第 20 轮补上** `WfExecutionQueryService` + `WfExecutionQuery`，REST `GET /api/wf/executions` 与 `/executions/count`。与既有 `getExecutions(processInstanceId)` 的差别**只有一个：查之前不必先知道实例 id** —— 排障的第一句常常是「哪个单子卡在审批节点上」，而那时手里还只有节点。能答的三个问题：① 哪些单子的 token 停在节点 X ② 哪些 token 还没结束（`onlyUnfinished`，与订阅查询互补：订阅答「在等什么」，令牌答「停在哪」）③ 哪条 token 的某个变量是这个值（并行分支最常出问题的那一个）。**刻意不提供 `definitionKey`**：令牌表里没这一列，要支持就得 join 实例表，而"某定义下所有活跃 token"用实例查询再逐个 `getExecutions` 就够。**变量过滤与排序都在 Java 里做、只有一份实现** —— 变量存 JSON 文本列，各库写法不同跨库只能回 Java；过滤一旦在分页之后，分页也不能交给 SQL（先 LIMIT 50 再过滤会剩 3 条，而调用方以为"就这些"）。**排序不交给数据库**：`ENTERED_TIME` 可空，而"NULL 排前还是排后"在 H2/PG/MySQL 上结论相反。`count` 与 list **共用同一次扫描**，不走 SQL `COUNT(*)` —— 否则列表 1 条而 count 说 8 条，调用方只会以为自己算错了 |
| `getBusinessKey` / `setProcessInstanceName` | ✅ | businessKey 本就有；**第 20 轮补上 `setProcessInstanceName`**（`WfProcessInstance#name`，`ZWF_PROCESS` 新增 `NAME` 列 + 补列迁移），REST `POST /api/wf/process/name`。name 与 businessKey **不能互相顶替** —— 前者是给人看的可读描述，后者是业务方的单号，拿单号当标题会得到一串没人看得懂的编号。**名字的字段只有一个所有者**：只有 `setProcessInstanceName` 改它，`saveProcessInstance` 的部分更新刻意不含该列 —— 引擎每次推进结束时都会把 `context` 里那个**改名前取的**实例对象写回去，名字一旦进了那条列清单，用户改完名再点一次「通过」就被抹回 null。**改名不走乐观锁也不碰 revision**（名字与状态机无关，让"改标题"和"审批推进"抢同一把锁，冲突时报的还是「乐观锁冲突」）。**空串报错、null 才表示清空**（空标题与没起名字在界面上一样、语义却不同）。**实例不存在报错并点名**，不静默返回 |
| `createVariableInstanceQuery` | ✅ | **本轮补上** `WfVariableQueryService` + `WfVariableInstanceView` + `WfVariableInstanceQuery`，REST `GET /api/wf/variable-instances` 与 `/count`。回答的是**「这个变量挂在哪一级作用域上」** —— 此前 `getVariables(processInstanceId)` 只能看到流程级那一层，分支级与任务级的值根本不在里面，而并行分支排障问的恰恰是级别。视图是**派生**的（变量在本仓没有独立实体，是三个模型上各自的 Map），所以没有自己的 id，只有「作用域:归属 + 变量名」拼成的临时 id，**只在本次查询期间有效**，不该被持久化成订阅条件。两个需要讲清的默认值：`openTasksOnly` 默认 `true`（任务变量在办结后仍然存在，算进「当前变量」会混进十几条历史表单变量）**但显式点名 `taskId` 时不过滤**（那时返回空列表分不清是「没有变量」还是「被过滤了」，而排障查的恰恰多是已办结的任务）；`includeEngineInternal` 默认 `false`（`loopCounter` 混进来只会让人怀疑查错了）。**一个范围都不给直接报错** —— 本仓的「全系统所有变量实例」只能靠全量取回再过滤，只返回一部分比报错坏得多。超过扫描上限（5000）报错而不是给一份看起来完整的清单 |
| 保存筛选器（Camunda `FilterService`） | ✅ | **本轮补上** `WfFilterService` + `WfFilter` + `WfFilterQuery` + `WfFilterResult`，新增 `ZWF_FILTER` 表（内存 / JDBC 两套实现）。REST `GET/POST /api/wf/filters`、`PUT/DELETE /api/wf/filters/{id}`、`GET /api/wf/filters/{id}/results`。见 §1.5 |
| `createEventSubscriptionQuery` | ✅ | **本轮补上** `WfSubscriptionService` + `WfSubscriptionView`（放 core 不放 web：订阅查询通常由独立部署的监控/运维服务消费，放 web 会把它拖进 Spring MVC 运行时）。REST `GET /api/wf/subscriptions` 与 `/subscriptions/count`，并**并进 `GET /api/wf/process/overview`**。回答的是"这条单子怎么不动了"——在等消息的流程没有待办、轨迹没动、也不报错，没有这张表就只能翻 XML 猜。**job 类型归并成"等什么"**（message/signal/timer/external/async）同时**保留原 jobType** 以区分"打断"与"竞速"；竞速分支额外带 `gatewayId`，让人看得出几条是同一次竞速。**超过扫描上限（2000）直接报错**而不是给一份看起来完整的截断列表 |
| `getActivityInstance`（树形活动实例） | ✅ | **第 17 轮补上** `WfActivityInstanceService#getActivityInstance` + `WfActivityInstanceView` / `WfTransitionInstanceView`，REST `GET /api/wf/process/activity-instance` 并**并进 `GET /api/wf/process/overview`**。回答的是「这条单现在走到哪了，并发分支在哪，各分支停在哪一步」。**与扁平 `trail` 是两种切法，不互相推导**：trail 是时间序、这棵树是结构；并行分支跑起来之后 trail 的行在时间上交错、看不出谁是谁的分支，而交错的历史行**推不出**层级，硬推会在并行分支上猜错 —— 猜出来的层级比扁平轨迹更坏，因为它看起来可信。⇒ **树给并发结构（来自执行树），节点内的 `childTransitionInstances` 给这条分支走过的顺序**。**形状上的三处实测结论**（写代码前跑探针打出来，不是推的）：① **并行网关 fork 出来的是父子链不是兄弟** —— 第一条出线留在父 token 上，所以两条并行分支的 activityId 天然不同，**按「同一父下有几个兄弟」判并发永远判不出 true**，判据取「未结束 token 总数 > 1」；② **`arrivedActivities` 回答不了「join 在等谁」** —— fork 出来的子 token 它的 arrived 里**不含那个并行网关**，那个答案在树本身（另有一条 token 还停在别的节点上没结束）；③ **fork 出来的子 token 没走过起始节点**，它的步骤表在离开第一个节点前是空的。**树活多久取决于历史保留多久**：`deleteHistoryBefore` 会把令牌与历史一起清掉，所以历史清理之前树都在，清理之后实例本身就不存在。**实例查不到时返回 400 而不是空树** —— 空树会让调用方分不清「没跑起来」「被清过历史」「真的没有分支」。**父指针与 children 双向自洽**（只填一边等于树是断的）；**挂不上父节点的 token 直接报错**，不静默丢弃 —— 丢一条会让「这单有 3 条分支」变成 2 条且无人察觉 |
| `messageEventReceived` / `signalEventReceived` | ✅ | `triggerMessage`（点对点）/ `broadcastSignal`（广播），**本轮补上 REST**：`POST /api/wf/process/message` 与 `POST /api/wf/process/signal`。此前只有 Java 入口，纯 HTTP 的调用方根本没法投递事件，事件网关等于对它们不存在。一个端点同时能叫醒三种等待者（事件网关分支 / 消息边界订阅 / receiveTask），谁先判决定了这条事件落到哪种语义上 |
| `correlate`（关联消息到执行） | ✅ | **第 16 轮补上** `WfRuntimeService#correlate(WfMessageCorrelation, userId, comment)` + `WfMessageCorrelation`，REST `POST /api/wf/process/message/correlate`。与 `triggerMessage` 的差别**只有一个**：调用方手里只有业务键与业务字段，引擎自己找到那条该被唤醒的单 —— 而"收到 ERP 回执、发一条消息、按单号配"才是消息驱动集成的常态。条件四选：业务键 / 定义 key / 流程级变量 / 执行级变量，全是「与」，不设即不参与。**候选集与 `triggerMessage` 共用同一份定义**（`waitingCandidates`），两者的差别只该是「多几道筛选」，候选定义一旦分叉，"correlate 找不到但 triggerMessage 能触发"就是 bug。**「没人在等」与「有 N 条在等但都没匹配上」分开报**：合并成一句"没找到"会让调用方不知道自己该去调条件还是该去查为什么没人在等；多条时列出每条候选的形态与 id，不静默挑一条。**变量比较用 `Object.equals`，不做类型宽松**（`1` 与 `"1"` 判不等），且**键不存在不等于「值为 null」**。**匹配条件不产生任何副作用**（不回写流程/任务变量）—— 三类候选的触发入口并不都接受变量，只让三分之一的路径生效等于同一次请求因命中形态不同而结果不同；且条件常直接来自外部消息体，把未经校验的外部字段灌进流程状态会让「配错了」从一次报错变成一次数据损坏。**刻意不做 `correlateAll`**（唤醒全部已由 `broadcastSignal` 覆盖）与 `withoutVariables()` 开关 |
| `getBusinessKey` / `setProcessInstanceName` | ✅ | businessKey 本就有；**第 20 轮补上 `setProcessInstanceName`**（`WfProcessInstance#name`，`ZWF_PROCESS` 新增 `NAME` 列 + 补列迁移），REST `POST /api/wf/process/name`。name 与 businessKey **不能互相顶替** —— 前者是给人看的可读描述，后者是业务方的单号，拿单号当标题会得到一串没人看得懂的编号。**名字的字段只有一个所有者**：只有 `setProcessInstanceName` 改它，`saveProcessInstance` 的部分更新刻意不含该列 —— 引擎每次推进结束时都会把 `context` 里那个**改名前取的**实例对象写回去，名字一旦进了那条列清单，用户改完名再点一次「通过」就被抹回 null。**改名不走乐观锁也不碰 revision**（名字与状态机无关，让"改标题"和"审批推进"抢同一把锁，冲突时报的还是「乐观锁冲突」）。**空串报错、null 才表示清空**（空标题与没起名字在界面上一样、语义却不同）。**实例不存在报错并点名**，不静默返回 |

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
| `getTableCount` / `getTableNames` / `getProperties` | ✅ | **第 19 轮补上**。自省挂在 `WfPersistence` SPI 上（`getTableNames` / `getTableCount`），`WfManagementService` 负责视图与措辞，REST `GET /api/wf/management/properties` / `/tables` / `/tables/count?name=`。三条硬规矩：① **未知名抛异常不返回 0** —— 拼错表名得到"这里是空的"会把排障方向从「我拼错了」带偏到「谁把它清空了」；② JDBC 侧 `getTableNames()` **从 `DatabaseMetaData` 真查**而不是报常量 —— 常量回答"打算建哪些"，这里要回答"这个库现在真有哪些"，两者在迁移没跑时会分家；③ 视图**显式带 `kind`（table / collection）** —— 内存实现里根本没有表，不标出来运维看到 `ZWF_TASK` 会跑去数据库里找一圈。属性**刻意不含连接串/账号/口令** |
| 诊断 / 历史级别调整 | ❌ | |

### 1.6 其余服务

| 服务 | 结论 |
|---|---|
| IdentityService | ⛔ 有意排除，见 §5 |
| FormService | ⛔ 有意排除，见 §5 |
| AuthorizationService | ⛔ 有意排除，见 §5 |
| FilterService（保存的查询） | ✅ | 早已实现：`WfFilterService` + `WfFilter` + `ZWF_FILTER` 表，REST `GET/POST/PUT/DELETE /api/wf/filters` 与 `GET /api/wf/filters/{id}/results`。见 §1.2。**这一行曾经长期挂着 ❌** —— 功能早就有了而能力表没跟上，读表的人会以为"保存筛选条件"得业务方自己存，于是自己又造了一套 |
| ExternalTaskService | ⛔ 有意排除，见 §5 |
| DecisionService（DMN） | ✅ | 第 24 轮起可被 BPMN 的 `businessRuleTask` 直接调用（见 §2）。第 23 轮实现：`WfDecisionService`（`parseDecision` / `deployDecision` / `findDecisionByKey` / `findDecisionsByKey` / `deleteDecision` / `evaluateDecision`）+ `WfDmnParser` + `WfDmnEvaluator` + `ZWF_DECISION` 表（带版本，与流程定义同一套版本语义）+ REST `POST /api/wf/decisions/deploy`、`GET /api/wf/decisions/{key}`、`GET /api/wf/decisions/{key}/versions[/{version}]`、`POST /api/wf/decisions/{key}/evaluate`、`DELETE /api/wf/decisions/{key}/versions/{version}`。**六种 HitPolicy 全支持**：UNIQUE（命中多条直接报违规）/ ANY（多条输出必须一致）/ FIRST / RULE_ORDER（多结果聚合）/ COLLECT（列表）/ OUTPUT_PRIORITY（按 `outputValues` 的先后排序）。聚合器 SUM / MIN / MAX / COUNT。**单目测试补全**：`inputEntry` 省略左操作数时以该列 `inputExpression` 的值为左操作数（`> 5000` 写作 `(amount) > 5000`）；`outputValues` 列表逐项展开。**只支持决策表，不支持决策图**（`informationRequirement` 部署期报错）与 **FEEL**（`[a..b]` 区间、`date(` / `time(` / `duration(`、`@"..."` 上下文 —— 部署期挡下高置信度的那几类，其余留给运行期 fail-closed） |
| CaseService（CMMN） | ⛔ 有意排除，见 §5 |
| Batch | ❌ 未实现 |

---

## 2. BPMN 2.0 元素覆盖

`z-wf` 支持 15 种节点类型（`WfNodeType`）。逐个对照 BPMN 2.0：

| BPMN 元素 | z-wf | 备注 |
|---|---|---|
| `startEvent` / `endEvent` | ✅ | |
| `userTask` / `serviceTask` / `scriptTask` / `manualTask` | ✅ | **`scriptTask` 的 `zifang:resultVariable` 第 22 轮修好**：此前 `WfScriptTaskBehavior` 读的是 `node.property("resultVariable")`（`properties` 这个扩展 Map），而 XML / JSON 两个解析器与持久化 codec 写的都是**字段** `resultVariable` —— 两者分家，于是脚本照常求值、流程照常穿透，**只有「结果写到哪」静默失效**，症状是下游读那个变量拿到 null 且没有任何报错。现在读的是字段 |
| `receiveTask` | ✅ | 等待语义本轮才真正修好（此前建了任务却被丢弃） |
| **`businessRuleTask`** | ✅ | **第 24 轮原生实现** `WfNodeType.BUSINESS_RULE_TASK` + `WfBusinessRuleTaskBehavior`。此前它不在解析器的元素表里，会走未知元素路径并被校验器当「不支持的元素」挡掉 —— 也就是说**一份用业务规则任务的真实流程在本引擎里部署不了**，而决策表第 23 轮就补上了、接头却一直空着。它与 `serviceTask` 的区别是**不用业务方写代码**，与 `scriptTask` 的区别是**规则与流程分开部署**（决策表有独立版本，改规则不必重部署流程）。`zifang:decisionRef`（或 `camunda:decisionRef`，可写成 `${变量}` 在执行那一刻求值）指定求值哪张决策表，`resultVariable` 指定结论写到哪个变量，**缺任一个都报 ERROR** ——本实现的决策结果**没有别的出口**（不像 Camunda 还有 `decisionResult` 局部变量 + 输出映射），不给写进哪个变量的话这个节点等于什么都没做：流程照常穿透且无报错。`mapDecisionResult` 四种映射（名字与 Camunda 一致，因为它们描述的是结果的**形状**）：`singleEntry`（唯一那个值）/ `singleResult`（唯一那行的 Map）/ `collectEntries`（每行的唯一输出）/ `resultList`（默认，全部行）。**映射不适用时报错而不是取第一条** —— 取第一条会让流程带着一个「看起来正常」的结论继续走，而那个结论随命中顺序变。`decisionRefBinding` 只支持 `latest`（默认）与 `version`；Camunda 的 `deployment` 与 `versionTag` **明确报错**而不是悄悄当成 latest：前者要「BPMN 与 DMN 同属一个部署单元」，而本仓两者分别部署（两个服务、两条 REST 端点），后者要 `ZWF_DECISION` 上有标签列。**部署期不检查决策是否已部署** —— 先后顺序是自由的，运行期找不到时报的是「决策 [xxx] 不存在」 |
| `sendTask` | ✅ | 与 `serviceTask` 共用行为，即同步跑一个 delegate。**没有 delegate 会让流程失败**——因为它不是 BPMN 那种抛消息 |
| `task` | ✅ | |
| `exclusiveGateway` / `parallelGateway` / `inclusiveGateway` | ✅ | |
| `callActivity` | ✅ | 本轮修好 `resultExpression` 死字段（解析了但从不求值），并拆出 `resultVariable`；被调流程启动失败不再被吞掉 |
| **嵌入式 `subProcess`** | 🟡 | **第 14 轮补上**：容器里画了节点就走内联执行 —— token 进入时落到**内联起始节点**（容器内无入线者），跑到**内联结束事件**（容器内无出线者）时回到容器、沿容器自己的出线继续走主图。内联子图展开在**父实例的同一棵 token 树里**（不建子实例），所以并行汇合、轨迹父子归属沿用主图机制。**两个刻意的限制**（部署期报 ERROR）：① 边界事件不许挂在容器上（token 一进容器就被推进走，而 `fireEventBoundary` 要求 token 仍停在宿主才触发）；② 不支持嵌套（内层子流程的结束点在 BPMN 里没有结构标记能与其他结束事件区分开）。**与 Camunda 的显式差异**：内联子流程**没有独立作用域**，内层节点与主流程共享流程级变量 |
| **`multiInstance`**（会签/或签/计数） | ✅ | 并行与串行都已实现，三种展开方式二选一或组合：`loopCardinality`（作者写死个数）/ **`collection` 集合迭代（第 11 轮补上，实例数由集合大小决定）** / **`isSequential="true"` 逐个串行（第 11 轮补上）**，加 `completionCondition` 即或签与计数会签。`elementVariable` 把集合当前元素绑成局部变量（绑**原值**，`loopAssignee` 仍给字符串形式）。**两个易错处都做了 fail-closed**：集合取不到 / 不是集合 ⇒ 停成内部终止而不是当空集合放行（后者会让整个会签节点被静默跳过）；串行下集合中途变短 ⇒ 报错停住而不是少办几个人。**剩余**：`collection` 的下标 EL（`${approvers[loopCounter]}`，见下）、多实例嵌套 |
| `boundaryEvent` | 🟡 | **错误边界**：`<errorEventDefinition errorRef>` + `WfRuntimeService#handleBpmnError` + `BpmnError`。**定时器边界**：`<timerEventDefinition>` + Job 执行器。**消息/信号边界**：`<messageEventDefinition messageRef>` / `<signalEventDefinition signalRef>`，token 一进入宿主节点就作为订阅挂在 `ZWF_JOB` 上，`triggerMessage`（点对点）/ `broadcastSignal`（广播）到达时**打断**在办的流程：宿主待办作废、token 走补偿分支。**非中断型（`cancelActivity="false"`）第 12 轮补上**：宿主 token 一步不动、待办不撤，**另起一条 token** 从边界出发走补偿分支，两条在下游汇合点碰头 —— 这就是"超时只提醒、不打断审批"。**剩余**：`parallelMultiple="true"`（重复触发，仍报 ERROR，见下） |
| **`intermediateCatchEvent`** | 🟡 | ✅ 已实现（消息 / 信号 / **定时器**三种事件定义）：token 停在该节点挂一条 `EVENT_MESSAGE` / `EVENT_SIGNAL` / `EVENT_TIMER` 订阅，**不建人工待办** —— 它等的是消息不是某个人，退化成待办的话事件网关就变成"让 N 个人同时点"。**也支持不经网关的普通用法**（流程里直接写、等消息继续），此时只前进自己不与任何分支互斥。**定时器捕获只支持作为事件网关的分支**：孤立的定时器捕获事件仍报 ERROR —— 它要的是另一条"到点就往下走"的续跑路径，本引擎没有，挂上去会得到永不响也不报错的哑表。**`conditionalEventDefinition`（第 21 轮补上）**：条件**叠加**在事件类型之上而不是二选一，回答的是「超时 3 天<b>而且</b>金额超过 1 万才提醒」—— BPMN 的互斥规则写不出这个「而且」，报错二选一等于逼作者要么没条件、要么没有事件类型。**求值在「事件到达那一刻」而不是建订阅时**（审批金额、已过天数往往在等待期间才定下来；建订阅时求值等于用还没发生的事实决定分支，症状是「金额明明超了却不提醒」）。**条件不成立时不删订阅、不推进、不作废兄弟分支**，并写一条带条件原文的评论留痕（排障要能直接读出「条件当时是假的」）。**投递方拿到的报错要与「没人订阅」分开**——两者的处置完全相反（前者去看变量，后者去建流程），合成一句"没反应"会把人带偏。**求值异常判「不成立」而不是放行**：放行等于把一条本该继续等的分支提前推进、流程就此走错且无报错。**定时器与消息/信号相反**：它是引擎自己在跑，条件不满足就静默跳过，抛出去会让一个正常到点的 job 变成失败并重试、而它永远不会成功，重试耗尽后还被记成一条故障。**条件不提供事件类型**（答「够不够格」不答「等什么」），只有条件没有 message/signal/timer 仍报 ERROR。**边界事件上不许挂条件**（打断型与竞速型语义互斥，同「抛事件不许挂边界事件」）。**剩余**：`escalationEventDefinition` |
| `intermediateThrowEvent` | ✅ | **第 18 轮原生实现** `WfNodeType.THROW_EVENT` + `WfThrowEventBehavior`。此前它是「不支持的元素」、部署期报 ERROR（见 §4）—— 也就是说**一份真实的 Camunda 流程里只要出现 throwEvent，本引擎就部署不了**。语义：**token 抵达即把事件投出去，自己继续往下走**（穿透、不建待办、不等待）。与 `serviceTask`/`sendTask` 的共同点是穿透，区别是它由引擎自己投递、不需要业务方实现 delegate。`signalRef` 走广播、`messageRef` 走点对点。**投递必须发生在落库之后** —— behavior 处在单实例事务内部，当场投出去的话，被唤醒的那条读到的是尚未落库的旧状态，内层推进完又被外层回写覆盖，现象是「事件到了但流程没动」且无异常（典型丢更新）。为此把十来条推进路径的收口统一到 `finishTransaction`：判完成 → 投递 → 必要时刷新调用方持有的实例快照。**「没人订阅」不是错误**（抛事件是发布式动作，有没有人听不改变它该继续往下走），但会写一条投递记录（唤醒 N 个）——留痕是「不静默」的兑现方式；**多条候选仍然报错**（点对点必须知道被谁接了）。**支持自唤醒**（流程给自己发信号唤醒自己的另一条分支），靠的是投递后刷新返回的实例快照。部署期三条硬规矩：必须给事件引用、不能同时给两个、**不能挂边界事件**（抛事件是穿透的，边界只会得到一个永不触发的哑订阅） |
| `eventBasedGateway` | 🟡 | ✅ 已实现：token 分叉到各中间捕获事件，**谁的事件先到就走谁，其余分支连同各自的订阅一并作废**（落选分支在轨迹上留 `eventGatewayLost` 一条）。竞速的兄弟集合从**流程定义**反查（捕获事件唯一入线的源头就是网关），不另存副本。订阅用 `EVENT_MESSAGE`/`EVENT_SIGNAL`/**`EVENT_TIMER`** 三种 job 类型，与消息/信号/定时器**边界**订阅分开 —— 后者是打断，前者是竞速，混用时触发路径必须去猜而猜错的后果是流程静默走错分支。**定时器分支已实现**：到点即算它赢，其余分支作废。**条件化分支（第 21 轮补上）**：某一格可以带条件，条件为假时这一格不算它赢、继续等 —— 与「这一格没订阅」是两件不同的事，引擎必须分得出来 |
| `complexGateway` | ✅ | **第 25 轮补上条件分派**。两种判定方式，**由网关自己有没有判别变量决定**，混用部署期报错：**取值分派**（配了 `zifang:caseVariable`，出线带 `zifang:caseValue`，`camunda:caseExpression` 同样识别）走第一条匹配的线、**只走一条**；**条件分派**（不配判别变量，出线带 BPMN 的 `<conditionExpression>`）按 **BPMN 2.0 对复杂网关的定义**，**条件成立的线全部激活**，一条都不成立才走默认流。此前出线上的 `<conditionExpression>` **被完全忽略** —— 一条只带条件的线永远选不中、流程静默落到默认线，而校验器还会在「它与 caseValue 同时配」时报错，让人以为这个属性是有意义的（这是本轮修掉的真缺陷）。**多条激活不会让汇合死锁**：汇合判定数的是"确实已激活的兄弟 token"而非图上入线总数，没被选中的线根本不产生 token。求值沿用 fail-closed（引用未定义变量判不成立）。**剩余**：配对/非配对（compete）语义差异 |
| `transaction` / `adHocSubProcess` | ❌ | 同上，报错挡住 |
| **定时器** `timerEventDefinition` | ✅ | 三种都实现了：`timeDuration`（PT5M / P1DT2H / P1Y）、`timeDate`（2026-12-31T18:00:00Z）、**`timeCycle`（第 13 轮补上）**，都可写 `${变量}` 由流程实例决定时限。**边界定时器（打断）与事件网关定时器分支（竞速）都已接上执行器**（`TIMER` / `EVENT_TIMER` 两种 job 类型，`WfJobService#executeDueJobs` 逐类型各扫一遍）。**`timeCycle` 的限制**：只支持用在**非中断型边界事件**上（`R3/PT1H` / `R/PT10M` / `P1D/T1H` / 带显式起始时刻的写法都支持），因为只有非中断型才有"下一周期可以提醒"的宿主；无界写法 `R/PT10M` 有 100 次的硬上限兜底 |
| **异步** `asyncBefore` / `asyncAfter` | 🟡 | ✅ 已实现：`zifang:` 与 `camunda:` 双前缀；`ASYNC_BEFORE`/`ASYNC_AFTER` 两个 job 类型 + `WfJobService#executeAsyncJobs`。**剩余**：异步 job 的优先级（`asyncBefore` 配 exclusive/priority）、多实例+异步（部署期已挡）。~~`timeCycle` 循环定时器~~ —— 已于第 13 轮实现，见上一行 |
| **外部任务** `externalTask` / `ExternalTaskService` | ✅ | `serviceTask` + `zifang:topic` 标注（`<externalTask>` 不是 BPMN 2.0 元素，Camunda 同样靠标注在 serviceTask 上）。原子"选出+上锁"、租约制、`fail` 解锁+退避、重试耗尽留档。REST 7 端点在 `/api/wf/external-tasks` |
| `errorRef` / `errorEventDefinition` | ✅ | 见上。**刻意不支持「空 errorRef = 捕获所有错误」**——宽泛捕获会把不相关异常也吸走，让本该崩的流程继续走 |
| `escalationCode` / `compensation` | ❌ | |
| **`linkEvent`**（`linkThrowEvent` / `linkCatchEvent`） | ✅ | **第 22 轮原生实现** `WfNodeType.LINK_THROW` / `LINK_CATCH` + `WfLinkCatchBehavior`。此前两者都不在解析器的元素表里，会退化成人工任务并在部署期报「不支持」。它补的是别的东西都替代不了的事：**跳过一整段图** —— `move` 是运行期外部 API（调用方得自己知道位置），排他网关是**分支**（在若干出线里选一条），两者都不等于"从图上某处直接落到另一处"。配对键是元素自己的 `@name`（`WfNode#linkName`，**不复用显示名 `name`**：两者恰好都来自 `@name`，但一个是给人看的、一个是给引擎配对的，共用一个槽位的话将来补个显示名就会把配对关系改掉）。**改道后 token 沿 catch 自己的出线走，throw 自己的出线不会被走过** —— 特判点放在 `leave` 的「取出线」**之前**（放之后会让「跳过一整段」变成「跑完整段再跳一遍」，而图上看不出异常）。**这一条与 escalation 恰好相反**（Camunda 的 escalation 文档明写 "if the throwing event has any outgoing sequence flows, they will be taken"），所以两处**不能互相参照着写**；throw 上画了线不报错，但报 WARN 说清那是摆设。**作用域限定在同一流程定义内**，不照抄 Camunda 的引擎级全局匹配 —— 全局匹配下"跳去哪里"取决于部署里还有哪些别的流程，删掉那个流程这条就断了，而图上没有任何东西能提示。**catch 是穿透的**：不建待办也不建任何事件订阅（它等的是"图上另一个节点"，而跳转在引擎内部同步完成）。**不用 `INTERMEDIATE_CATCH_EVENT` 实现** —— 那个会建 `EVENT_*` 订阅等外部事件，得到的是一个永远等不到、也不报错的哑订阅。部署期八条：缺 name / 同名 catch 多个 / throw 找不到落点（三个 ERROR），catch 有人跳过来（入线）、无出线（两个 ERROR），catch 没人跳、throw 有出线（两个 WARN），加上 link 事件不许挂边界事件（ERROR，两个方向都是穿透的）。其中 **catch 有入线报 ERROR 而不是 WARN**：token 只由 link 改道进入，那条连线永远不会被走过，它上游的整段流程静默失效 —— 这比"出线是摆设"严重得多，后者至少不隐藏一整段流程。`LINK_THROW` 刻意**不注册**行为（它的语义全在 `leave` 的改道里，进入阶段本就不该有动作），`LINK_CATCH` 显式注册而非落兜底 —— 两者运行结果相同，但排障时含义不同 |
| `dataObject` / `dataStore` / 数据关联 | ❌ | |

**关于 `multiInstance`**：会签是审批场景的默认需求，并行与串行都已实现。
三处需要讲清的设计：

- **串行复用同一条 token**（第 11 轮）。任何时刻只有一条实例在办，办结一次就把
  这条 token 的循环变量推进一格再建下一条任务。为什么不每轮新建 token：串行下
  「这个节点总共几个实例」**从任务数不出来**（同一时刻只有一条任务，办结的那条
  还没被下一条取代），计划数只能存在驱动循环的那条 token 上，而它正是随后要
  继续往下走的那个「当前 token」——换一条就得把它的身份在多处传递下去。
  由此也拆出了 `isComplete` 里那条「没有在办实例就收口」的兜底：串行套用它会
  **办完第一个就往下走**。
- **实例数在进入时冻结，集合本身不冻结**。串行每建一个实例都重新求值一次
  `collection`，所以冻结一份集合快照会让元素走一遍 JSON 往返
  （BigDecimal 变 Double、Date 变字符串），第 2 个人拿到的东西和第 1 个人
  在内存里看到的不一样。代价是「中途加签不加进来」——文档里明确写了这一点。
  集合中途**变短**则报错停住：实例数定了 3 个却取不到第 3 个，悄悄收口的后果是
  「以为三个人都批了，其实只批了两个」。
- **EL 不支持变量下标**：实测 `${approvers[loopCounter]}` 抛 ElException
  （`${approvers[1]}` 可以）。所以逐实例派不同人靠 `collection` + `elementVariable`
  （或旧写法 `zifang:loopAssignees`）+ `zifang:assignee="${loopAssignee}"`，
  索引在分叉时用 Java 取，不在表达式里做

**关于异步的现状**：载体（`WfJob` / `ZWF_JOB` / `WfJobService`）从定时器边界开始就是通用的，
现在定时器、消息、信号、外部任务、异步前置、异步后置七种都落在同一张表上，靠 `JOB_TYPE` 区分。
异步执行做完了，两个方向共用一个执行器但走**相反的续跑动作**：前置 `resumeEnter`（把节点真的跑一遍），
后置 `resumeLeave`（只补"离开"这一步，绝不重跑节点行为）—— 共用一个的话就会在重跑 delegate 和不执行之间二选一。

**剩余的异步缺口**：`asyncBefore` 的 exclusive（互斥，多实例里只跑一个）与优先级。
多实例 + 异步已在部署期挡住：单 token 粒度的续跑没有
"等所有实例都离开"的汇合点，放行会让流程在最后一个实例离开时就往前走。

（~~`timeCycle` 循环定时器~~ 这条曾长期挂在这里，但第 13 轮就实现了，
且已接上执行器 —— 见 §2 定时器那一行。留着会让人以为"循环定时器还没做"。）

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
等价替代关系会给出建议（`transaction` / `adHocSubProcess` → `subProcess`），但
`eventBasedGateway` 刻意不给——拿 `exclusiveGateway` 顶替它不是简化，
是把"多路竞速"换成"顺序选一"，照着改会得到更难发现的错流程。

> **第 18 轮更新**：`intermediateThrowEvent` 已原生实现（见 §2），不在退化名单里；
> 退化名单现在只剩 `transaction` / `adHocSubProcess`。
> 与之配套，`substitutionHint` 里"抛事件 → 请改用 sendTask"那条分支也一并删掉了 ——
> 原生类型不会再被标记成退化元素，那条分支**永远走不到**，
> 而留着它不是"以防万一"，是一条一旦被改回标记就会给出错误建议的路径：
> 对已经原生支持的元素说"请改用 sendTask"，作者照着改就把一份能跑的流程改坏了。
> （`intermediateCatchEvent` → `receiveTask` 那条同理，第 7 轮就已成死代码。）

> 本节原先还把 `eventBasedGateway` 与 `intermediateCatchEvent` 列为退化元素。
> 这两者已实现（见 §2）；`intermediateThrowEvent` 也在第 18 轮补上，
> 退化名单现在只剩 `transaction` / `adHocSubProcess`。
> `UnsupportedBpmnElementTest` 里另有 `eventBasedGatewayIsNowNative` 与
> `intermediateThrowEventIsNowNative` 两条守着"已实现的元素不许再被当成退化节点"。

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
| 1 | ~~**多实例（会签/或签/计数）**~~ | ✅ **并行 + 串行 + `collection` 集合迭代均已实现**（第 11 轮）。剩余：变量下标 EL（引擎侧限制）、多实例嵌套 |
| 2 | ~~**变量服务**~~ | ✅ 本轮已补（`WfVariableService` + REST `GET/POST /api/wf/process/variables`）。剩余缺口：变量实例查询、类型化变量、变量作用域链（execution 级） |
| 3 | ~~**BPMN 错误事件 + `handleBpmnError`**~~ | ✅ 本轮已实现（错误边界）。剩余：escalation / compensation |
| 4 | ~~**边界事件 + 定时器 + Job 执行器**~~ | ✅ **已实现**：定时器 / 错误 / 消息 / 信号 / 外部 / 异步前置 / 异步后置七种共用 `ZWF_JOB` 一个载体，靠 `JOB_TYPE` 区分；事件网关的定时器分支（第 10 轮）与循环定时器 `timeCycle`（第 13 轮）也接上了同一个执行器。**剩余**：异步 job 优先级 |
| 5 | ~~**嵌入式 `subProcess`**~~ | ✅ **第 14 轮补上**（见 §2）。剩余：嵌套内联子流程、容器上的边界事件、多实例 subProcess |

### P1 —— 引擎成熟度

历史查询体系（活动 / 任务 / 流程实例 / **变量变更审计** + 历史清理已实现）·
~~Repository 完整化~~（定义停用/启用 + 模型回读 + 定义查询 + 物理删除 + **默认流程定义（第 15 轮）**已实现）·
~~任务挂起~~（suspend/activate + 七处闸门 + 查询过滤 + REST 已实现）·
~~运行时增删候选人~~（含 `candidateOrAssigned` 待办或语义 + 可认领列表按人过滤）· ~~复杂网关~~ · ~~事件网关~~（**消息 / 信号 / 定时器三种分支均已实现**，见 §2）· ~~条件化事件分支 `conditionalEventDefinition`~~（**第 21 轮补上**，见 §2）· ~~实例迁移~~（`move` 已实现；`moveTaskState` **经核实不是缺口**，见 §1.2）· ~~Filter~~（**第 9 轮补上**，见 §1.5）· ~~中间捕获事件的定时器分支~~（**本轮补上**，见 §2）

**P1 已全部清空。**

### P2 —— 管理便利

引擎指标 · ~~流程模型图形回读~~（BPMN DI 解析 + REST 已实现，见 §1.1）· ~~消息关联 `correlate`~~（**第 16 轮补上**，见 §1.2）· ~~`getActivityInstance` 树形活动实例~~（**第 17 轮补上**，见 §1.2）· ~~`getTableCount` / `getTableNames` / `getProperties`~~（**第 19 轮补上**，见 §1.5）· ~~`createExecutionQuery` 令牌条件查询 + `setProcessInstanceName`~~（**第 20 轮补上**，见 §1.2）· ~~链接事件 `linkEventDefinition`~~（**第 22 轮补上**，见 §2）· ~~`DecisionService`（DMN 决策表）~~（**第 23 轮补上**，见 §1）

> 第 24 轮（业务规则任务 `businessRuleTask`）顺带修掉一个**已发布真缺陷**：
> 解析器读扩展属性只认 zifang 那三条路径（命名空间 / `zifang:` / `zifang_`），
> 而 **Camunda 导出的模型写的是 `camunda:` 前缀**。
> 于是 `<scriptTask camunda:resultVariable="x">` 会被读成"没配"——
> 脚本照常求值、流程照常穿透，**只有「结果写到哪」静默失效**，
> 症状与第 22 轮修的那个缺陷一模一样，而第 22 轮修的是另一头（字段 vs 属性 Map）。
> ⇒ 与第 20 轮「两套实现分家」同源：**同一个东西的两种写法，只修了一种**。
>
> 第 23 轮（DMN 决策表）**没有新缺陷，但改了三处"文档比代码乐观/悲观"**：
> ① 记忆里"`createHistoricIncidentQuery` 是缺口"**已经不成立** —— `WfIncidentService`
>     与 `/incidents/count`、`/process/overview`、`/dashboard` 早就在了。
>     所以本轮开工先做文档-代码核对，而不是照着上一轮列的缺口直接做；
> ② `timeCycle` 在表格里写"第 13 轮已实现"，却在两处「剩余缺口」里还挂着 ——
>     **同一份文档内部自相矛盾时，读的人只会挑对自己有利的那一半**；
> ③ DMN 整块缺失与"有意排除"是两回事：§5 的五条有意排除里没有 DMN，
>     所以它是**真的没做**，不是设计决策 —— 这也是本轮选它的理由；
> ④ README 三处的表数量停在 **6 张**、索引数停在 **10 个**，
>     而 `STORAGE_NAMES` 早已是 9 张、DDL 里数出来是 15 个索引。
>     这类数字每加一张表就漂一次，**要订正就顺手数一遍，不要照抄上一处的数字**。

> 第 22 轮（链接事件 `linkEventDefinition`）顺带修掉一个**已发布真缺陷**：
> `scriptTask` 的 `zifang:resultVariable` 是死字段 —— `WfScriptTaskBehavior` 读的是
> `node.property("resultVariable")`（properties Map），而三处写入写的都是**字段**。
> 属性名完全一致，所以没有任何报错，只是这个功能从未生效过。
> 同时订正 `README.en.md`：它把 `intermediateCatchEvent` 列为不支持，
> 而那个元素第 7 轮就实现了。

> 第 19 轮没有新缺陷，但逼出两处**当初差点漏掉的东西**：
> ① **自省能力挂在哪一层**，一开始想放在 `WfManagementService` 里 `instanceof` 判断存储形态，
>     那样"底层有什么"就得由服务层去问实现类的私有字段。放到 SPI 上之后，
>     "底下是表还是 Map"由实现自己如实说，服务层只负责措辞 ——
>     代价是 SPI 从 48 涨到 50 个方法，**两套实现加一个测试替身都要跟上**
>     （编译器会替你点名所有实现者，包括测试里的替身）。
> ② **视图必须带 `kind`**。自省接口报出 `ZWF_TASK` 而不说它是进程内集合的话，
>     运维看到名字就会跑去数据库里找一圈，然后开始怀疑数据库。
>     写这条注释之前我自己先踩了一次：拿 `KIND_TABLE`（"table"）去比
>     `persistenceKind()`（"jdbc"），永远不成立，于是 **JDBC 也被标成 collection**。

> 第 21 轮（条件式事件 `conditionalEventDefinition`）**没有新缺陷**，
> 但定下一条与既有约定同源的分界：**同一个"不算它赢"，投递方与定时器的处置必须相反**。
> 前者要把"条件挡住了"说清楚（否则落到「没有等待消息」那句上，排障方向被带偏到「没人订阅」），
> 后者必须静默跳过（定时器是引擎自己在跑，抛出去会让一个正常到点的 job 变成失败并重试，
> 而它永远不会成功，重试耗尽后还被记成一条故障 ——
> 于是「条件不满足」变成了「流程出故障」，两件不相干的事）。

> 第 20 轮**自己判据抓到一个两套实现分家的真缺陷**，两套都不曾单独出错：
> 「实例名只能由改名那一路写」这条不变式在 **JDBC 上天然成立**（`saveProcessInstance`
> 是部分更新，列清单里没有 `NAME`），而在**内存实现上不成立** ——
> 它是整对象覆盖，而调用方手上那份实例往往是改名前取的，回写就把名字抹成 null。
> 症状是「内存里好好的、一上真库就丢」，排查的人会先怀疑数据库。

> 第 17 轮顺带修掉一个不属于本轮范围、但由本轮挖出来的**已发布真缺陷**：
> `deleteHistoryBefore` 在 JDBC 上漏删执行令牌（内存实现一直会删），
> 而令牌没有别的清理路径 —— 真库上 `ZWF_EXECUTION` 只增不减。
> 它之所以属于「一条不变式两处独立实现」，且**只有真库抓得到**，见文末本轮小节。

---

## 7. 当前状态小结

- 引擎骨架（token 执行树、汇合、乐观锁、持久化抽象）**扎实**，有 872 个测试兜着
- 从测试与审计中逼出并修复的**真实缺陷 49 项**（43 项截至第 21 轮 + 第 22 轮的
  `zifang:resultVariable` 读错载体 1 项 + 第 23 轮 DMN 的 3 项
  + 第 24 轮的 `camunda:resultVariable` 前缀读不到 1 项
  + 第 25 轮复杂网关出线条件被忽略 1 项），
  其中 **7 项**属于"能力看着在、实际不生效"：
  未支持元素静默退化、`receiveTask` 不等待、未部署定义启动、`onBeforeCreate` 从未触发、
  嵌入式 `subProcess` 的内联内容永远不执行、默认流程标记两套实现不一致、
  **复杂网关出线的 `<conditionExpression>` 被读进来却无人使用**
- **第 20 轮（令牌查询 + 实例改名）自己判据抓到一个"两套实现分家"的真缺陷**：
  新增一个字段时，**JDBC 侧的"部分更新"天然守住了字段所有权，内存侧的"整对象覆盖"没有**，
  于是同一条不变式在一套上成立、在另一套上不成立。
  详见 §6 与文末本轮小节。
- **第 18 轮（`intermediateThrowEvent` 原生实现）没有新缺陷，但逼出两处"够不着的防御"与一处死代码**：
  ① **投递队列的 `while` 循环原本不承重**。第一版是"共享队列 + 每层都完整 drain"，
     于是最内层那次调用会把整个队列清空，外层循环第二次迭代时队列必为空 ——
     「只发一批」与「发到空」两种写法给出**完全一样**的结果。
     改成"**drainer 必须唯一**（按线程记嵌套深度，最外层当唯一 drainer）"之后，
     循环才真的决定发不发得完，M05 才转红。
  ② **投递上限 `MAX_PENDING_EVENT_DRAIN` 目前够不到**。写它的理由是
     "两个流程互相抛事件会无限触发"，写完才发现本引擎**没有常驻订阅** ——
     消息 / 信号 / 事件网关分支的订阅在事件到达时就被消费，消息边界的订阅在
     token 离开宿主节点时就被撤，所以互抛会自然收敛。
     ⇒ 上限作为安全阀保留（将来若引入常驻订阅会用上），但它**没有判据，也不假装自己有**；
     判据改成钉住它够不到的那个原因：「互抛在有限步内收敛，最后一轮落在没人订阅上」。
  ③ `substitutionHint` 里"抛事件 → 请改用 sendTask"已成**死代码**
     （原生类型不会再被标记成退化元素），连同 `intermediateCatchEvent` 那条一并删掉。
     ⇒ 留着它们不是"以防万一"，而是一条**走不到、但一旦被改回标记就会给出错误建议**的路径。
- **第 17 轮（活动实例树）写出 1 个自己造的缺陷、挖出 1 个已发布的真缺陷，都在流出前抓住**：
  ① **顶层 token 挂到根下时忘了写 `parentActivityInstanceId`** ——
     `children` 说"你是根的孩子"、`parentActivityInstanceId` 却是 null，
     调用方没法确定该信哪一个，而往下走树时两种走法会给出不同结果。
     它是自己写的判据 `parentPointerAndChildrenAgree` 抓到的。
  ② **`deleteHistoryBefore` 在 JDBC 上漏删执行令牌**（内存实现一直会删）。
     令牌没有别的清理路径（主代码里 `deleteExecution` 从不被调用），
     于是 `ZWF_EXECUTION` 在真库上只增不减，且每一行都属于一个已经查不到的流程实例。
     **这类分歧只有真库能抓到** —— 内存实现的清理是对的，跑内存用例永远看不出来。
  另有两处**关于既有行为的错误判断**，都是自己查出来自己改掉的：
  ③ 我在注释里写「overview 对不存在的实例返回空」—— **假的**，
     `subscriptions` 与 `incidents` 两项在 controller 里是无条件塞的，那个响应本来就有两个键；
  ④ 我在注释里写「令牌与历史是两套独立的生命周期，哪套先没得由调用方知道」——
     **那是照着 ② 这个缺陷写出来的**，缺陷修掉之后这个说法就不成立了，已在三处订正。
  ⇒ 与第 6 轮那条同源：**照着一个缺陷写出来的设计说明，在缺陷修好之后会变成新的错误文档。**
- **第 6 轮（变量实例查询）写出 3 个自己造的缺陷，都在流出前抓住**，其中两个的形态值得记：
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

### 本轮反向验证记录（多实例 collection + 串行）

17 条变异，**17 红**。逐条在**独立 worktree**（`/tmp/z-wf-rv11`）里跑，跑完逐文件 `diff`
确认无残留（`WfDefinitionRoundTripTest` 那一轮备份失败，改为直接与源目录比对确认）。

| 变异 | 结果 | 被哪条判据抓住 |
|---|---|---|
| M1 串行误用并行的「在办为 0 就收口」 | 🔴 4+4 | `sequentialDoesNotAdvanceAfterTheFirstOne` 等 |
| M2 计划实例数读不到就退回按任务数算 | 🔴 4+4 | 同上 |
| M3 `collection` 取不到时当成空集合 | 🔴 1 | `unresolvableCollectionFailsClosed` |
| M4 `collection` 不是集合时按单元素凑合 | 🔴 1 | `collectionMustBeACollection` |
| M5 串行改成每轮新建 token | 🔴 3+1 | `sequentialReusesOneToken` |
| M6 `elementVariable` 绑字符串而非元素原值 | 🔴 1 | `elementVariableKeepsTheOriginalObject` |
| M7 上限放大 100 倍 | 🔴 1 | `oversizedCollectionFailsOnEntry` |
| M8 集合中途变短时悄悄收口 | 🔴 1 | `shrunkenCollectionFailsLoudly` |
| M9 串行忽略完成条件 | 🔴 1 | `sequentialHonoursCompletionCondition` |
| M10 `collection` 与 `loopCardinality` 同时配不报错 | 🔴 1 | `collectionAndCardinalityTogetherAreRejected` |
| M11 `elementVariable` 没有 `collection` 不报错 | 🔴 1 | `elementVariableWithoutCollectionIsRejected` |
| M12 codec 丢掉 `loopCollection` 的拷贝 | 🔴 1 | `richNodeFieldsSurviveRoundTrip`（**首轮绿**） |
| M12b codec 丢掉 `loopElement` 的拷贝 | 🔴 1 | 同上（**首轮绿**） |
| M13 parser 漏读 `collection` | 🔴 11 errors | 全部 `collection` 用例 |
| M14 parser 漏读 `elementVariable` | 🔴 3 | 元素绑定三条 |

**本轮自己写出来、并被反向验证抓住的一个缺陷**：

**`closeMultiInstanceIfDone` 重新读了一遍 execution，于是同一轮推进里出现了两个
不同的 execution 对象。** `findExecution` 返回**副本**（与 `findDefinition` 同样），
调用方手里是副本 A、这个方法里又读了副本 B；而落库写的是 A。于是串行推进时写在
B 上的 `loopCounter` 被丢掉，**每一轮都读到 0、每轮都建第 1 个实例**。
症状是「三个人都办了一遍 alice」，且每一步单看都正常。

⇒ 修法是把**对象**传进去而不是传 id 再读一遍。
⇒ 这与第 10 轮那条「引擎侧闸门判的是启动时手里那个定义对象」是同一族的两个面：
**副本语义不只影响「读到的内容」，还影响「改了以后谁能落库」。**
凡是「传 id 进去、在里面重新查」的地方，都要问一句：调用方是不是已经拿着一个对象了？

**两条判据缺口，其中一条是我在上一轮刚学过一次又踩的**：

- **M12/M12b 首轮打不红**：`codecCoversEveryEntityField` 那个反射守卫能抓
  「字段没进 DTO」，抓不到「字段在 DTO 里、**拷贝语句**没了」——
  它比的是字段名，而 M12 是把 `gn.setLoopCollection(node.getLoopCollection())`
  改成传 `null`。⇒ 反射守卫要配**值级断言**才闭环。
  已在 `RICH_BPMN` 里加了一个用 `collection` + `elementVariable` 的节点并断言取值。
- **M13 首轮是 `PATCH NOT FOUND`（归因③ 变异压根没打上）**，不是 GREEN：
  脚本里把 `node.setLoopCollection(...)` 的位置写成了 `WfNode`，
  而它其实在 `WfXmlParser` 里。补丁没打上时测试当然全绿 ——
  **这正是「首轮绿」必须逐条归因、不能直接当成判据没区分力的原因。**

**又一条关于「怎么验」的收获**（M6）：

`elementVariable` 该绑**元素原值**还是**字符串**？变异把原值换成
`String.valueOf(element)`，判据是「用 `${approver.id}` 引用元素字段后，
待办办理人应当是 alice/bob」。若只断言办理人**非空**或**互不相同**，
这个变异照样绿 —— 因为字符串形式非空、且各实例仍然不同。
⇒ 判据必须引用**元素的字段**，才能区分「原值」与「字符串形式」。

**新补的一条自检**：断言里凡是给「互不相同 / 非空 / 大于 0」的，
先问「把取值换成它的字符串形式，这个断言还会过吗」。
会过 ⇒ 它分不清「拿到了对象」与「拿到了对象的描述」。

### 本轮反向验证记录（非中断型边界事件）

13 条变异，**13 红**。逐条在**独立 worktree**（`/tmp/z-wf-rv12`）里跑，跑完逐文件与源目录
`diff` 确认无残留。

| 变异 | 结果 | 被哪条判据抓住 |
|---|---|---|
| M1 非中断退化成中断 | 🔴 8 | `hostTaskSurvivesTheTrigger` / `hostTokenDoesNotMove` |
| M2 非中断也作废宿主待办 | 🔴 6 | 同上 + 两条汇合用例 |
| M3 分支 token 与宿主无父子关系 | 🔴 5 | `branchTokenIsParentedToTheHost` + 两条汇合用例 |
| M4 分支 token 不登记进 context | 🔴 3+3 | `hostTokenDoesNotMove` 等 |
| M5 分支 token 落在宿主节点上 | 🔴 7 | `boundaryVisitIsRecordedOnTheTrail` 等 |
| M6 触发后不重判实例终态 | ⚪ **绿 → 见下** | — |
| M7 `parallelMultiple` 不再报 ERROR | 🔴 2 | 两条部署期用例 |
| M8 非中断没有出线不再报 ERROR | ⚪ **绿 → 已修判据** | — |
| M9 parser 漏读 `cancelActivity` | 🔴 9 | 全部非中断用例 |
| M10 codec 丢掉 `nonInterrupting` 的拷贝 | ⚪ **绿 → 已修判据** | — |
| M11 codec 丢掉 `parallelMultiple` 的拷贝 | ⚪ **绿 → 已修判据** | — |
| M12 触发时不写评论 | ⚪ **绿 → 已修判据** | — |

**M6 那一格：该调用可证明是空转，所以改的是代码不是判据。**
反向验证把我引向 `resolveCompletion`，读它发现开头就是"还有任何一条活跃 token 就早退" ——
而这条路径上宿主那条**必然**还活着（前面那道闸门刚确认过它停在宿主节点上，
否则这里已经 `return` 了）。所以那个调用在任何输入下都不可能改变实例状态。
⇒ 删掉它，并把注释改成**真实的**理由（为什么这条路径不需要重判、中断型为什么需要）。
⇒ 这是「变异打不红」的第四种成因：**被测代码本身没有可观测行为**。
与前三种（判据没区分力 / 没编译过 / 没打上）并列，归因时别忘了先问一句
「这段代码在这个输入下**可能**有效果吗」。

**另外三格都是判据缺口，其中两格是同一类**：

- **M8**：断言写的是"报错里含'出线'"。可删掉 f3 之后**别的**规则也会报错
  （`remind` 变得不可达之类），于是这条断言因错误的原因通过。
  ⇒ 断言要挑**只有这一条规则会说**的词：改成"非中断"。
  **「断言错误消息」不等于「断言到了正确的那条错误」** ——
  多条规则共用的措辞会让判据对错误来源没有区分力。
- **M10 / M11**：`WfDefinitionCodec` 的缺口在**内存实现上验不到** ——
  `InMemoryWorkflowPersistence` 的深拷贝走的是 **Java 序列化**（`copy` 方法），
  压根不经过 codec。而本轮新写的 `boundaryFlagsSurviveRoundTrip` 用的是内存实现，
  于是它对 codec 缺口**零区分力**。
  ⇒ codec 往返的断言只能放在 `WfDefinitionRoundTripTest`（那里同时跑 JDBC 与内存，
  **JDBC 那套才真的过 codec**）。已在 `RICH_BPMN` 里加了一个
  `cancelActivity="false" parallelMultiple="true"` 的边界并断言两个取值。
  ⇒ 与第 11 轮同一条教训的延续：**任何"存进去再取回来"的判据，都要先确认
  被测的那条存储路径真的被走到了**。内存实现能过不代表 JDBC 能过，反之亦然。
  新测试的 `@DisplayName` 与注释已改成如实说明"这条验不到 codec"，并指向正确的地方。
- **M12**：轨迹断言查的是**活动实例**（由 `leave()` 记），而 `WfComment` 是另一个产物 ——
  把写评论那行删掉，轨迹照样在，测试照样全绿，那行就成了无人验证的代码。
  ⇒ 补了一条独立断言（评论里要出现边界事件 id）。
  ⇒ **同一个动作留下的每一种产物都要各有一条断言**：轨迹、评论、job 状态、
  token 状态是四条独立的数据，验了一条不等于验了其余。

**判据设计上的一处补强**（M1 一条就抓了 8 条用例）：

"宿主待办还在"这件事，除断言"待办数 == 1"外，还断言了**待办的办理人没变**、
**宿主 token 仍停在 `approve` 上**、以及一条**对照用例**（同一张图去掉
`cancelActivity`，中断型必须作废宿主待办）。最后那条最要紧：
它证明非中断与中断**确实走了不同的分支** ——
若两者行为相同，前面所有断言就都只是在复述"流程跑通了"。

M12 之外还发现并修掉一处**注释说了假话**：`fireNonInterruptingBoundary` 末尾原本写着
"汇合判定可能因为这条分支到达而放行后续，实例状态要跟着重新判一次"，
而那个重判在这条路径上永远早退。

### 本轮反向验证记录（循环定时器 `timeCycle`）

15 条变异，**15 红**。逐条在**独立 worktree**（`/tmp/z-wf-rv13`）里跑，跑完逐文件与源目录
`diff` 确认无残留。

| 变异 | 结果 | 被哪条判据抓住 |
|---|---|---|
| M1 不重新挂下一次 | 🔴 4+1 | `rearmHappensAndHostIsUntouched` 等 |
| M2 次数上限差一个（`>=` 变 `>`） | 🔴 4 | `firesExactlyThreeTimes` / `noNextOccurrenceAfterTheLast` |
| M3 无界写法的硬上限失效 | 🔴 4 | `unboundedStopsAtTheCap` 等 |
| M4 首次触发用锚点本身 | 🔴 1 | `firstFireIsOnePeriodAfterTheAnchor` |
| M5 下一时刻按次数累加 | 🔴 1 | `nextDueIsPreviousPlusOnePeriod` |
| M6 周期段不补 `P`（`P1D/T1H` 解析不了） | 🔴 2 errors | `durationBoundBecomesCount` 等 |
| M7 显式起始时刻不被认出 | 🔴 1 error | `explicitStartIsUsedAsAnchor` |
| M8 新 job 不记 cycleIndex | 🔴 3 | `rearmHappensAndHostIsUntouched` 等 |
| M9 新 job 不设 id | 🔴 4+1 | 同 M1 |
| M10 中断型也放行循环定时器 | 🔴 1 | `cycleOnInterruptingBoundaryIsRejected` |
| M11 部署期不验表达式格式 | 🔴 2 | `malformedCycleIsRejectedAtDeployTime` 等 |
| M12 老库不补 `CYCLE_INDEX` 列 | 🔴 1 error | `legacySubscriptionNameIsBackfilled` |
| M13 行映射漏读 cycleIndex | ⚪ **绿 → 已修判据** | — |
| M14 UPDATE 不带 `CYCLE_INDEX` | 🔴 4 | `cycleIndexSurvivesBothWrites` 等 |
| M15 初始 job 的 cycleIndex 不置 1 | 🔴 3 | 同 M2 |

**M12 抓到的不是"我漏写了一列"，而是"加列这件事本身有没有被做"**：
`CREATE TABLE IF NOT EXISTS` **不会**给已存在的表补列，所以线上跑着 2.0.0 之前建的库时，
新列压根不存在，`findJob` 直接报 `Column "CYCLE_INDEX" not found` ——
表现为"引擎启动即炸"，而不是某个功能悄悄坏掉。
⇒ 补了 `addJobColumnIfMissing(connection, "CYCLE_INDEX INT")`，走的是本仓既有的补列惯例
（`TOPIC` / `LOCKED_BY` / `SUBSCRIPTION_NAME` 当年都是这么加的）。
判据是既有的 `legacySubscriptionNameIsBackfilled` —— 它手工造了一张"老版本的表"再调
`initialize()`，正好是这个场景的现成夹具。

**M13 是真判据缺口，且它是 M12 的孪生兄弟**：列加了、行也映射了，可**没有任何测试断言
`cycleIndex` 真的活过了 INSERT / UPDATE**。两段都要断：
`INSERT` 漏了读回来是 0（循环把自己当成第 0 次），`UPDATE` 漏了每次重新挂下一次都把计数
抹掉（永远停在同一个数上，循环变成无限）。
⇒ 在 `JdbcWorkflowPersistenceTest` 里补了两条，分别断这两段。
⇒ 这与第 11、12 轮那条同源：**一条链路上每一段都要各有一条断言**，
"保存 + 读取 + 更新 + 再读取"是四段，不是两段。

**本轮自己写出来、被变异抓到的两个真 bug**：

1. **新 job 忘了设 id**（M9）。`rearmCycleTimer` 里把各个字段都填了，唯独漏了
   `setId` —— 而 `WfJob` 的主键是调用方给的，引擎不补。存进去的是一条 id 为 null 的行，
   症状是「响过之后库里查不到任何待触发的 job」，
   而所有「响了几次」的断言都还在前面通过了。
2. **周期段没补 `P`**（M6）。`P1D/T1H` 里的周期写的是 `T1H` 而不是 `PT1H` ——
   ISO 8601 的完整时长必须带日期部分，而 `P1D/T1H` 恰恰是 BPMN 里最常见的写法之一。
   直接丢给 `parseDuration` 会报"不是合法的 ISO-8601 时长"，
   于是**所有带"总时长上限"的循环定时器都部署不了**，而 `R3/PT1H` 那条路完全正常 ——
   也就是说这个 bug 只在一条分支上出现，很容易被漏掉。
   ⇒ 加了 `parsePeriod`：以 `T` 开头就补上 `P`。

**一个 off-by-one，是判据逼出来的**（M2 / M8 / M15 三条一起指向它）：
`cycleIndex` 最初我记的是「已经响过几次」，初始 job 记 0。
可那样算下来 `R3/PT1H` 会响 **4** 次 —— 差的那一个正好是"第 4 次照响"，
而症状是「作者写三次、实际催了四次」，从图上看不出任何异常。
⇒ 把语义改成「这是第几次触发」（1 起），初始 job 记 1。
⇒ 三条变异都断在同一个数上，是本轮最有说服力的一组：
**同一个 off-by-one 可以从三个方向被测出来**，
所以判据里"响了几次"和"下一次记着第几"要各断一次，缺一个就留下一半。

**一个必须靠对照才成立的限制**（M10）：
`timeCycle` 只放行**非中断型**边界。理由不是"没实现"，而是**响过之后还有没有宿主
可以打断**：中断型第一次响就把宿主 token 搬到边界事件上走了。
放行它等于"作者写每次催一次、实际只催一次"，
所以部署期报 ERROR 而不是让它跑起来再让人发现。

**无界写法的处理**（M3）：`R/PT10M` 是合法 BPMN 写法，直接拒等于做半套；
而完全放行则会让一个没人管的单子被无限催下去、每次还多出一条并行分支。
⇒ 引擎侧 100 次硬上限，触顶后停挂并写日志。判据真的循环到上限（造满 105 轮扫描），
不是只试两下就断言。

### 本轮反向验证记录（嵌入式 `subProcess`）

本轮把最后一处 P0 结构性缺口补上：`subProcess` 里画了节点就会执行。
做法是**认出内联子图的起止**并把 token 在两处改道：
进入 `subProcess` 时落到**内联起始节点**（容器内无入线者），
跑到**内联结束事件**（容器内无出线者）时回到容器、沿容器自己的出线继续走主图。
内联子图展开在**父实例的同一棵 token 树里**（不建子实例），
所以并行汇合、轨迹父子归属全部沿用主图机制。

**24 条变异，24 红**（另有 2 条对照变异确认脚本不误报红）。
判据的核心是把"分界线"钉死：`unconditionalStartNodes` / `eventStartNodes` 都不把内联节点
算成流程入口（否则画一个内联子流程就凭空多出第二个无条件入口），
`inlineStartNode` / `inlineEndNode` 在"零个或多于一个"时返回 `null` 而不是挑一个。

**一个新概念，及其配套的整条判据链：内联容器分段**（`WfDefinition#inlineScopeOf`）。
这是本轮最容易漏掉的一块，症状是"流程卡在子流程里，且流程图上看不出任何异常"：

内联子流程的分支 token 与**外层并行分支**的 token 可能**同父** ——
外层并行网关分出「走子流程」与「走外面」两条，走子流程那条（父 token）停在
`subProcess` 节点上，内层并行网关再从它分出两条。于是 ia / ib / outer 三条
`parentId` 全都指向同一条父 token。而 `samePeer` 原本只按 `parentId` 判「同一批」，
于是内层的 join 会去等外层还没办完的分支 ⇒ 永远等不齐。
⇒ **内联子流程是图上的分界线**：同父之前先看两条 token 是否属于同一段图。
判据是 M07（摘掉分段）/ M08（分段恒真）/ M09（容器类型判错）三条，
它们全部断在同一个 `innerJoinIgnoresOuterBranch` 上。

**判据补了三轮才补对，形态值得记**（M11 / M13 首轮全绿）：

`unconditionalStartNodes` 有**两条**路 —— 显式 `startEvent` 优先，
没有则退化成「无入线节点」。而画了内联子流程的定义**总是**能命中第一条，
⇒ 退化那条路在本轮的全部用例里**从来没被执行过**。
所以「退化分支也要排除内联节点」这道 guard 写了却没判据：
摘掉它，22 条里没有一条会红。
⇒ 补了一个**不写 startEvent** 的定义当夹具。
**同一个"没被走到"还会连累隔壁**：M13 摘掉 `isInline` 的容器类型判断也打不红，
因为 `isInline` 的两个调用点上 `BOUNDARY_EVENT` 都不满足前置条件 ——
**这道 guard 在当前调用点上可证明没有可观测行为**。
⇒ 处置不是删它（`isInline` 的定义本来就该含容器类型判断），而是补一条
直接断言 `isInline(boundary) == false` 的判据。
⇒ **「guard 写了却没判据」和「guard 写了但当前调用点上用不到」是两回事**，
前者靠补夹具，后者靠补精确取值断言；都不能靠"读一遍代码觉得它在"来判断。

**三个真 bug，本轮全部自己写出来又在流出前抓住**：

1. **`nestedIn` 不为空 ≠ 内联**（本轮踩得最深的一次）。
   解析器记的是"直接父元素"，而 `<boundaryEvent>` 在 BPMN 里就写在**宿主活动内部** ——
   挂在 userTask 上的超时边界事件，它的容器是那个 userTask。
   我第一版校验规则写成"容器不是 subProcess 就报内联节点嵌错地方"，
   结果 `WfSubscriptionQueryServiceTest` 整个挂掉（6 个 error）。
   ⇒ 这不是"测试没跟上"，是**规则本身写错了**：边界事件挂宿主是完全正常的写法。
   ⇒ `isInline` 与 `inlineScopeOf` 都要多看一眼容器类型。
2. **边界事件的归属应当递归问宿主**（M21）。
   第一版 `inlineScopeOf` 遇到"容器不是 subProcess"直接返回主图（`""`），
   注释里还写了一句「宿主在主图上」——**这句话是假的**：
   宿主完全可能嵌在另一个 `subProcess` 里（「内联子流程内的活动上挂超时边界」
   是完全正常的写法）。判错的后果是这条边界分支与内层分支判成不同段，
   内层的并行汇合等不到它。
   ⇒ 改成 `return inlineScopeOf(container.getId())`。
   **注释里出现了一个可被用例证伪的断言，而当时没有任何用例去证它。**
3. **边界事件被算成流程级入口**（M22）——这条**在本轮改动之前就存在**。
   退化入口判定只认「无入线」，而 boundaryEvent **天然**无入线。
   ⇒ 任何"带边界事件、又不写 startEvent"的定义都会凭空多出第二个无条件入口，
   部署期报「存在多个无条件开始节点」，而作者图上确实只画了一个入口。
   ⇒ 补了排除。它是被新夹具（不写 startEvent 的定义）照出来的，
   **不是被本轮的新功能照出来的** —— 加新能力顺带修掉的老账，这类最容易被归错因。

**两条限制的取舍，都写进了部署期而不是"让它跑起来再说"**：

- **边界事件不许挂在容器上**。token 一进 `subProcess` 就被推进走，此后不再停留在容器上，
  而 `fireEventBoundary` 有一道硬闸门：token 必须仍停在宿主节点才触发。
  放宽它要重新定义"打断"的对象，语义风险远大于本轮收益。
- **不支持嵌套**。理由不是"没实现"这么轻：内层 `subProcess` 的离开语义与外层不同 ——
  它的出线只通向它自己内部，而"子流程结束了"这件事在 BPMN 里
  **没有任何结构标记**能指出是哪一个 `endEvent` 属于内层、哪一个属于外层。
  猜错的后果是内联内容被静默跳过或流程死循环，两者都不报错。
- 顺带挡掉「既画内联内容又配 `calledElementKey`」：引擎优先走内联，
  配了 `calledElementKey` 的效果是"看着像会调外部流程，实际一次都没调"。

**一个对照纪律**（G01 / G02）：我原本把
「`isInlineSubProcess` 忽略容器有没有内联内容」设成**对照组**（预期仍绿），
结果它打红了。查下来是**我把它归错了类** —— 它恰恰是本轮设计决策的判据
（"空容器配 `calledElementKey` 按 callActivity 语义处理"），本就该打红。
⇒ 改成 M20 重新跑，并另加两条真对照组（改日志措辞 / 改校验文案）确认脚本不误报红。
**对照组的价值不在于"绿"，而在于"绿能说明什么"** ——
一条变异打红了，先问它归不归这条判据管，再问判据有没有区分力。

**变异脚本自己出错的两种形态，本轮各撞一次**（都伪装成"判据没区分力"）：

1. **打完补丁没恢复**。`run_tests` 抛异常时跳过了恢复文件，
   后面每条变异都跑在被污染的基线上 ⇒ **22 条全部报 RED，结论整体作废**。
   症状极具迷惑性：看起来是"判据太好用"，实际是基线本身坏了。
   ⇒ 修法两条：恢复放进 `finally`；**每轮跑之前先自检基线全绿，不绿就退出**。
2. **补丁文本没跟上代码变化**（M09 / M11 第二轮 PATCH NOT FOUND）。
   我在补判据时顺手改了 `WfDefinition`，两条变异的 `old` 文本就失配了。
   ⇒ `str.replace` 的 PATCH NOT FOUND 检查救了我一次：**它把"没打上"与"打上了没打红"
   这两件长得完全一样的事区分开了**。
   这也说明**归因四分类里的第 ③ 类不是"脚本偶尔出错"，而是每轮都会发生的常态** ——
   只要一轮里既有改代码又有跑变异，补丁文本就必然有几条会过期。

### 本轮反向验证记录（默认流程定义 `getDefaultProcessDefinition` / `setDefault`）

本轮把 P1 收尾。先**订正了一条自己写错的记录**：此前把 `moveTaskState` 记成
「按任务状态筛选迁移」。查 Camunda 7 的 javadoc 与 Task Lifecycle 文档后确认，
它属于 **standalone task** 体系 —— `TaskService#newTask()` 建的是
「不挂任何流程实例的独立任务」，`moveTaskState` 搬的是那种任务在
`Created/Assigned/Completed/Canceled/Failed` 之间的位置。
本引擎**没有独立任务**这个概念（`WfTask` 一律由 `WfUserTaskBehavior` 建出，
必带 `processInstanceId` + `definitionId`），而"把一个流程内任务换状态"
现有能力已全覆盖（`claim`/`unclaim`/`updateTask`/`delegate`/`resolve`/
`complete`/`withdraw`/`force-complete`/`suspend`/`activate`）。
⇒ **一个写错的能力描述，比一个真缺口更容易误导后来人**：
它会让人以为"只差这一个 API"，于是把 standalone task 整套引入引擎。
本轮 P1 的实际内容因此变成 `getDefaultProcessDefinition` / `setDefault`。

实现上值得记的有四件。

**一、同一不变式在两套实现里各有一份，判据也必须两份**（M01 / M07）。
`deploy` 出来的定义一律不是默认 —— 这条不变式写在两个地方：
内存实现的 `saveDefinition` 与 JDBC 的 `INSERT`。
我先写完 JDBC（`ps.setInt(10, 0)` 写死），内存实现照抄了入参上的标记，
判据也只写了 JDBC 那条，于是「内存实现会把默认改掉」这件事零反应。
⇒ 「deploy 不产生默认」与第 10 轮的「落选分支清理要覆盖新枚举值」是同一条：
**凡是一条不变式有 N 处实现，判据必须 N 条**。
补上内存那条判据后，M01、M07 立刻双双转红。

**二、补列迁移会完全掩盖 DDL 的缺列**（M05，本轮最有价值的一条发现）。
把 DDL 里的 `IS_DEFAULT INTEGER NOT NULL DEFAULT 0` 删掉之后，
**全套 20 条测试里没有任何一条会红** —— 因为 `initialize()` 末尾的
`addDefaultColumnIfMissing` 会在建表之后立刻把它 `ALTER` 补上。
补列迁移本来是给「2.0.0 之前建的库」用的，对新库是多余的一步，
而它顺带把 DDL 的缺失也一起盖住了。
⇒ 判据必须**直接查 `INFORMATION_SCHEMA`**，绕开后面所有补救步骤。
**一般形式：凡是有「补列 / 补表 / 懒初始化」这类补救步骤的地方，
它会掩盖的正是「本来就该有」的那部分缺陷** ——
补救步骤跑得越成功，缺失越看不见。

**三、两处"因错误的原因通过"，都是同一句话被两道闸门共用**（M15 / M17）。
M15：`setDefaultDefinition` 里有两道闸门都会抛 `WfDefinitionException`
——「版本不存在」与「持久层说没改到」。我只断言异常类型，
于是「跳过版本存在性检查」这条变异照样通过，因为它是**另一道闸门抛的**。
M17：`startDefaultProcessInstance` 的停用检查与 `startProcessInstance`
自己的停用闸门，错误消息**都含 `activateDefinition`**，
只断言那一句同样放过。
⇒ 错误消息要挑**只有目标路径会说**的词（M17 补的是「默认流程定义」）。
这与第 12 轮那条同源：**断言了错误消息 ≠ 断言到了正确的那条错误。**

**四、判据收紧后逼出来的一个真 bug：`findPersisted` 会回落**。
M15 的判据从"断言类型"收紧到"断言消息落在版本不存在那道闸门上"之后，
测试转红并暴露出：`findPersisted(key, version)` 在指定版本不存在时
**回落到该 key 的最新版本**（那是「按 key 启动流程」主路径需要的行为）。
而"设为默认"拿它判存在性，于是
① `isSuspended()` 读的是**别的版本**的停用状态 —— 拿 A 的状态判 B 的合法性；
② 版本号打错时报的是「可能已被并发删除」，把调用方引去查并发与删除，
根本想不到是自己写错了版本号。
⇒ 改用 `getDefinition`（精确取 + 报「版本不存在」）。
**这个 bug 只断言异常类型时是查不出来的。**

另有一处**静默降级**由老库夹具逼出来：`findDefaultDefinition` 原先走
`DEF_GRAPH` 读法，而图解不出来时 `readDefinition` 返回 `null`、
`queryList` 会把 `null` 原样收进结果集 ⇒ `hits.size()==1` 而 `hits.get(0)==null`
—— 库里明明有一行默认，接口却答「还没配默认」，没有任何报错。
⇒ 改成**先只按列数与 key/version 判定，不碰 `DEF_GRAPH`**，
图解不出来时报错并点名是哪一行。
这一条也顺带解释了为什么那条老库夹具一开始失败：
我手写的 `DEF_GRAPH='{}'` 不是合法图 JSON，
而**判据的夹具数据不合法时，测的就不是你想测的那件事** ——
先存一条真定义再把编出来的 JSON 抄进老表，才是"老库里的一行"。

**变异脚本自己出错的第三种形态：改的模块不在编译路径里**（M19）。
脚本原本只跑 `mvn -pl z-wf-core,z-wf-admin test`，而 web 层的控制器改动
没有进 reactor —— 变异**改了但没跑起来**，症状与「判据没区分力」完全一样。
⇒ 改跑 `mvn install` 全反应堆。
⇒ 与第 14 轮那两种（打完补丁没恢复 / 补丁文本没跟上代码变化）并列，
**「变异没生效」有三个来源：没打上、没恢复、没进编译路径**，
而三者在结果上都长得像"判据没区分力"。

### 本轮反向验证记录（消息关联 `correlate`）

**20 条变异全红 + 2 条对照全绿**，720 个测试，逐类 `@Test` 与 surefire 零差异。

**一、写代码时自己先证伪了一次：「匹配条件回写」是不是死代码**（对应第 0 问）。
初版把 `criteria.getVariables()` 传给了 `completeTask`。第一反应是
"匹配条件要求每个键都存在于流程变量且值相等，所以回写必然是 no-op"——
**这个推理是错的**，而且错在一个我本该先查的地方：
`completeTask` 写的是 `task.getVariables()`（**任务级**）与执行上下文，
不是 `instance.getVariables()`（流程级）。两个不同的 Map，回写有真实副作用。
⇒ 由此定下本轮最要紧的一条设计：**匹配条件一律不回写**。
理由有两条，第二条是主因：① 三类候选的触发入口里**只有接收任务那条接受变量**，
让"条件顺便写回去"只对三分之一的路径生效，同一次关联请求会因命中形态不同
而产生不同的副作用 —— 调用方无法预判，也无法在只跑过一种形态的测试里发现；
② 条件常直接来自外部消息体（ERP 回执、MQ payload），
把未经校验的外部字段灌进流程状态，等于让「配错了」从一次可回滚的报错
变成一次已经落库的数据损坏。
⇒ 与第 10 轮那条「基准不能是被断言对象自己导出的量」同源：
**一个动作若在"匹配成功"这个前提下可证明无效果，它就不是实现，是幻觉。**

**二、判据的「短路」把一条缺陷藏住了（M07，首轮打绿）**。
`valuesEqual` 写成 `!actual.containsKey(k) || !Objects.equals(...)` 之后，
我把变异改成 `entry.getValue().equals(...)`（条件值为 null 时 NPE），
**全套测试照样绿**。查下来是我的判据数据不对：
`absentKeyIsNotNullValue` 造的是"流程里**没有** remark"，
而 `containsKey` 返回 false 时 `||` **短路**，`entry.getValue().equals(...)`
根本不会被求值 ⇒ NPE 那条路径在这个输入下**可证明不可达**。
补的判据是**另一种输入**：流程里**有** `remark="已填"`，条件却要 `remark=null` ——
此时 `containsKey` 为 true，才真的走到比较那一步。
⇒ 这正是第 0 问的教科书形态，而且**它发生在自己写的判据上，不是在别人写的代码上**：
断言里那个"看起来覆盖了 null"的用例，实际只覆盖了 null 走不到的分支。
⇒ **补一条判据之前先问「这条路径在这个输入下可达吗」**；
造完判据数据后，**把被测代码的那条实际求值路径再走一遍**，而不是凭字段名想当然。

**三、变异 harness 自己污染基线：还原时用错了来源**。
第一次全量跑，`M15` 因为补丁串里引号写成 `「」`（代码里是 `"`）
而 `PATCH NOT FOUND` 退出 —— **退出前没还原 worktree**。
下一次运行开头做 `snapshot()`，而它是**从 worktree 自己**拷"原始"，
于是把上一轮的变异当成了基线。症状是**基线红**，
看起来像"尺子坏了"，实际上尺子好好的、上一轮已经跑完了。
⇒ 两处修正：**`snapshot()` 改从主仓取 pristine**（worktree 永远是派生物），
失败路径也必须还原再退出。
⇒ 这是「变异没生效」三个来源（没打上 / 没恢复 / 没进编译路径）之外的**第四种**：
**没还原，而且还原的来源本身是脏的**。它不表现为"某条变异打不红"，
而表现为"基线红" ⇒ **基线红必须单开一条归因分支，不能并进"判据没区分力"。**
同轮还因 worktree 里残留的旧 `.class` 撞出一次同样的假象，
给每条变异的命令加上 `clean` 才把它从归因里彻底排除。

**四、判据配平：同一条不变式在 N 处独立实现，判据必须 N 处**。
Controller 里把 DTO 字段拷进 `WfMessageCorrelation` 的那段有 **6 个独立拷贝点**
（messageName / processInstanceId / businessKey / definitionKey / variables / localVariables），
而我第一版 REST 用例只走了其中 3 个。
⇒ 若把 `setLocalVariables(...)` 改成 `setLocalVariables(null)`，
核心层的 18 条用例**一条都不会红**（它们不走 Controller），
只跑到 3 个字段的 web 用例也照样绿。
⇒ 补了 3 条 web 用例（businessKey / definitionKey / processInstanceId），
最终 M16–M20 五条"Controller 不传某个字段"的变异全部转红。
⇒ 与第 9 轮那条（内存 / JDBC 双实现必须各有判据）同源，
**这次的 N 是"同一个 DTO 的 N 个字段"** ——
字段越多的映射层，越容易只测自己最先想到的那几个。

**五、被测代码在这个输入下可证明无效果的那一段，测试怎么写**。
`correlate` 里"选定后推不动必须报错"那个分支（`fireEventBoundary` 返回 null），
在正常链路上**构造不出来**：token 离开宿主节点与订阅 job 被撤是同一笔事务里的事。
⇒ 判据里**直接造那条"撤得慢了一步"的 job**（复制真 job，把 `executionId`
指向一个不存在的 token），并把这件事写进注释 ——
否则下一个读代码的人会以为这条分支是随手加的。
⇒ 与第 15 轮那条同族，但形态更极端：那里是"变异打不红所以要问可达性"，
这里是**"要测一条不可达分支，只能从持久层直接造状态"**。
可造（因为 `saveJob` 是公开的）就要造；不可造就要明说没覆盖，不要假装覆盖了。

### 本轮反向验证记录（活动实例树 `getActivityInstance`）

**17 条变异全红 + 2 条对照全绿**，734 个测试，逐类 `@Test` 与 surefire 零差异。

**一、写测试之前先跑探针，它当场推翻了本类注释里的两条说法**。
活动实例树是最容易画错的形状 —— 少一层、多一层、把两条分支并成一条，
看上去都还是一棵"树"，而排障的人只会照着它去找原因。
所以我没直接照 Camunda 的形状写，而是先写了个临时探针把引擎**实际产出**的树打出来。
两条设计当场作废：

1. **并行网关 fork 出来的是父子链，不是兄弟。**
   并行网关的第一条出线留在父 token 上继续走，其余出线另起子 token ——
   所以两条并行分支分别停在 `a1` 与 `b1`，**activityId 天然不同**。
   我原本把「有没有并发」定义成「同一父下停在同一 activityId 的兄弟数 > 1」，
   按实测数据这一位**永远是 false**：并行分支怎么数都不是兄弟。
   ⇒ 改成「未结束 token 总数 > 1」，与节点在树上的位置无关。
2. **`arrivedActivities` 回答不了「join 在等谁」。**
   fork 出来的子 token，它的 arrived 里**不含那个并行网关**
   （实测 `arrived=[b1]`，父 token 是 `[s, pg, a1]`）。
   ⇒ 从 join 那条 token 自己的 arrived 看不到还差哪条分支。
   那个答案在**树本身**：另有一条 token 还停在别的节点上没结束。
   我原先在注释里把它写成"唯一能看出汇合在等谁的东西"，**数据不支持，已改掉**。
   ⇒ 与第 5 轮那条同源：**注释描述的属性必须由数据真的具备**。
   而"数据真具备"的唯一可靠办法是先跑一遍看，不是照着别家的语义推。

**二、第 0 问第二次落在我自己的判据数据上（M06，首轮打绿）**。
变异是「把所有步骤挂到同一条 token 上」（按 executionId 归组被写坏），首轮**打绿**。
补丁打上了（否则 exit 3）、编译过了（否则 RED），所以是判据没区分力。
查下来是**判据造不出差异**：刚 fork 出来的子 token **一步历史都没有** ——
它是在并行网关处被创建的，起始与网关那两步都记在父 token 上。
于是"挂错 token"在这个输入下**可证明不可观察**。
我原来的判据只断「一共四步」，而错挂只是把四步换了个人保管，**总数没变**。
⇒ 补的判据是**逐条断言归属**：父 token `[s, a1]`、子 token `[b1, e]`。
必须先把两条分支**都办完**，子 token 才会有步骤，归组才有的可错。
⇒ **只断言聚合量时，"整齐地一起错"是抓不住的**（第 2 轮那条），
这次的形态是"四步没错，只是都挂到了同一个人身上"。

**三、harness 又踩了一次第 15 轮那条：判据根本没被执行**（M16 / M17 首轮打绿）。
变异是「JDBC 清理不删执行令牌」，首轮打绿。补丁打上、编译通过，
但 `CORE_CMD` 只跑两个**新**测试类，而我加断言的 `JdbcWorkflowPersistenceTest`
根本不在选择里 —— **判据写了但没被跑到**。
⇒ 与第 15 轮「改的模块不在编译路径里」完全同源，只是这次不是 reactor 而是 `-Dtest` 选择。
⇒ 症状依旧是"这条判据没区分力"。
⇒ **凡是新断言加在既有用例里，harness 的测试选择必须同步扩**，
否则新断言在验证阶段等于不存在。

**四、挖出一个已发布的真缺陷：JDBC 清理漏删执行令牌**。
`deleteHistoryBefore` 在 JDBC 上删 ACTIVITY / TASK / COMMENT / PROCESS，**唯独不删 EXECUTION**；
而内存实现一直会删。令牌没有别的清理路径（主代码里 `deleteExecution` 从不被调用），
于是真库上这张表**只增不减**，且每一行都属于一个已经查不到的流程实例。
⇒ 典型的「一条不变式两处独立实现」，且**只有真库能抓到** ——
内存实现的清理本来就是对的，跑内存用例永远看不出来。
⇒ 补齐时特意钉了两条：已结束流程的令牌要删、**在途流程的令牌绝不能删**
（删了这条单就再也推进不动）。M16 / M17 两条变异分别覆盖这两侧。
⇒ 与第 9 轮那条（内存 / JDBC 双实现必须各有判据）同源；
差别在于这次**判据必须写在 JDBC 那个类里**，写在别处也验不到。

**五、照着缺陷写出来的设计说明，在缺陷修好之后会变成新的错误文档**。
我在 `WfActivityInstanceView` 写的是「令牌与历史是两套独立的生命周期，
哪套先没得由调用方知道」，又在 controller 上写「历史会被清理、执行树不会」。
**这两句都是在照着上面那个缺陷说话** —— 缺陷修掉之后它们就成了错的。
⇒ 已连同 service 的报错文案在三处订正为：
**这棵树活多久取决于历史保留多久；清理之前树都在，清理之后实例本身就不存在**。
⇒ 一般形式：**先发现缺陷、再写文档的顺序是危险的** ——
按当前（错误）行为写下的说明，会在修复之后悄悄变成误导。
**判据要钉住"应该是什么样"，注释要跟着判据走，而不是跟着当前实现走。**

**六、Controller 层差点犯的低级错，顺手记一笔**。
把树并进 `overview` 时我先写成了「判断实例存在 → 再调一次 `getProcessOverview`」，
理由是"实例不存在时那个 map 是空的"。**两个错**：
① 那个 map 并不空 —— `subscriptions` 与 `incidents` 是 controller 里无条件塞的，
我的前提本身就错，还写进了注释；
② 就算前提没错，**也不该再调一次** —— `getProcessOverview` 存在的理由
恰恰是"一次算完、避免中间状态不一致"，算完又调一次等于把它的理由作废还多一次查库。
⇒ 改成复用已经取到的 map，并把注释里那句错误前提一并改成实测行为。
⇒ **写注释时顺手断言的"既有行为"，要真的去看一眼** ——
我这条是跑 web 测试时（`不存在的实例，overview 仍返回空对象` 直接红了）才发现的。

### 本轮反向验证记录（`intermediateThrowEvent` 原生实现）

**14 条变异全红 + 2 条对照全绿**，745 个测试，逐类 `@Test` 与 surefire 零差异。

**一、变异脚本自己写了一条"没有实现它名字说的那件事"的变异**（M01 首轮打绿）。
首轮那条叫「投递发生在落库之前（丢更新）」，实际只调换了
「投递 vs 完成判定」的先后 —— 而这两步**都在 `persistAll` 之后**，
压根没碰到它声称要测的「投递早于落库」。
⇒ 这是归因清单之外的第五种：**变异与它的名字不符**。
症状和「判据没区分力」一模一样（GREEN），但原因既不是判据也不是实现，
是补丁本身。
⇒ 与第 15 轮那条（打完补丁没恢复）同族，归因时**先把补丁和它的描述对一遍**。
本轮换成真正可测的「投递之后不判定完成」，转红。

**二、判据没区分力，根因是"实现里那段循环不承重"**（M05）。
变异「队列只发一批」打绿。查下来不是判据弱，是**实现有冗余**：
第一版是"共享队列 + 每一层都完整 drain"，于是最内层那次调用会把整个队列清空，
外层 `while` 第二次迭代时队列必为空 —— 两种写法给出完全一样的结果。
⇒ 修法不是补判据，是**去掉冗余**：改成"drainer 必须唯一"
（`ThreadLocal` 记嵌套深度，最外层当唯一 drainer），循环才真的决定发不发得完。
⇒ **打不红时先问「这段代码承重吗」**。不承重的实现不该靠判据救 ——
判据越补越像是给一段装饰做的见证。

**三、第 0 问第三次落在我自己写的"防御"上**（M06 / M08 / M09 首轮打绿）。
投递上限、以及 behavior 里"没事件引用 / 同时配两个就报错"那两道闸门，首轮全绿。
逐条问「这段代码在这个输入下**可能**有效吗」：
① **上限够不到**：本引擎**没有常驻订阅** —— 消息 / 信号 / 事件网关分支的订阅
在事件到达时就被消费，消息边界的订阅在 token 离开宿主节点时就被撤。
所以"两个流程互抛事件"会自然收敛：ping 走过 catch 的那一刻 sigA 就没人等了。
⇒ 上限作为安全阀保留（将来若引入常驻订阅会用上），**但它没有判据，也不假装自己有**；
判据改成钉住它够不到的**那个原因**：「互抛在有限步内收敛，最后一轮落在没人订阅上」。
② **behavior 那两道闸门在端到端路径上不可达**（部署期已经挡住）。
⇒ 判据改成**直接调 behavior**。这两道闸门与 `WfServiceTaskBehavior` 的
delegate 解析闸门是同一类：部署期挡住一份，配置期挡住另一份，
运行期那道挡的是"定义从别处进来、或校验规则以后放宽"。

**四、夹具用链式 replace 写，失败模式是"静默产出一份意思相近但不对的流程"**。
PONG 夹具原本是从 PING 链式 replace 出来的：第二个 replace 把 catch 与 throw
里的 `sigA` 一起换掉，第三个又因为实际文本里 `sigB` 后面跟的是 `"/>`
而不是换行而没匹配上 —— 结果 PONG 变成「等 sigB、抛 sigB」的自环，
**照样收敛、所有断言照样过**，而它已经不再是对面那个流程了。
⇒ 显式写出来，并把这段教训写进夹具注释。
⇒ 与第 15 轮「判据的夹具数据不合法时，测的就不是你想测的那件事」同族。

**五、一个断言自己把提示文本当成了失败**。
「已有原生实现，不该再劝作者改写」我写成 `!rendered.contains("sendTask")`，
而我自己的新校验文案里恰恰建议"改用 sendTask"—— 被自己写的提示判失败。
⇒ 改成断言**语义**（「没有把它当成不支持的元素」）而不是某个字符串不出现。
⇒ 与第 12 / 15 轮那条「断言错误消息 ≠ 断言到了正确的那条错误」同族：
**断言的是判别式，不是文案。**

**六、两条既有用例的前提随实现一起失效了，改而不是删**。
`UnsupportedBpmnElementTest.intermediateThrowEventSuggestsSendTask` 与
`WfEventGatewayTest.throwEventIsRejected` 断言的都是"抛事件被当成不支持的元素"，
而这正是本轮改掉的东西。
⇒ 前者改写成 `intermediateThrowEventIsNowNative`（与 `eventBasedGatewayIsNowNative` 同职责：
守着"支持列表不许悄悄缩回去"）；后者保留**同一条约束**但换掉理由 ——
出线指向抛事件仍然报 ERROR，只是现在报的是"它不会挂订阅"与"它没配事件引用"。
⇒ **约束没变、理由变了**的时候，要改的是断言的理由，不是把约束一起删掉。

---

### 本轮反向验证记录（引擎自省 `getTableCount` / `getTableNames` / `getProperties`）

本轮补的是自省 —— 出了事第一句要问的是「你连的是哪个库、哪些表在、里面各多少行」，
而这三句话不该只能靠人手工连上去数。`25` 条变异全红，`4` 条对照全绿。

自省能力挂在 `WfPersistence` SPI 上（`getTableNames` / `getTableCount`），
`WfManagementService` 只负责视图与措辞。代价是 SPI 从 48 涨到 50 个方法 ——
**两套实现加一个测试替身都要跟上**，好消息是编译器会替你点名所有实现者，包括测试里的替身。

**一、自己写出的一个真 bug，流出前抓住**。
`getTables()` 里判断存储形态时我写成 `WfTableInfo.KIND_TABLE.equals(persistenceKind())`
—— 拿 `"table"` 去比 `"jdbc"`，永远不成立，于是 **JDBC 侧也被标成 collection**，
而那恰好是这一层存在的全部理由："自省接口骗人"。
⇒ 这类「拿 X 的字面量去比 Y」的写法，编译期与运行期都不报错，只有真值对得上才看得出来。

**二、M08 与 M20 首轮打绿 —— 同一条不变式有两处实现，我只判了一处**。
清单里的行数有**两个**实现点：`getTables()` 构造 `WfTableInfo` 时传进去的数、
以及 `WfTableInfo#getRowCount()` 返回的字段。而我当时**全部判据都走 `getTableCount(name)`**，
清单这条组装路径一个断言都没有 —— 两条能各自独立地坏掉（把传入值写死 0、把 getter 写死 0）。
⇒ 补 `listRowCountsMatchSingleCountQuery`：清单里每一项的 `rowCount`
必须等于单独查同一个名字的结果。两条变异随即一起转红。
⇒ 这是「**一条不变式在 N 处独立实现，判据必须 N 处**」的第 N 次复现。
值得单独记的是它的失败模式：「清单里的行数恒为 0」与「这张表真的是空的」
在运维眼里**长得一模一样** —— 也就是说，缺判据的那条路一旦坏掉，
自省接口会主动把人往错误方向带。
⇒ 与第 15 轮「没进编译路径」、第 17 轮「`-Dtest` 没覆盖到断言所在类」同属一类：
**判据必须落在每一条会被执行到的路径上，而不是落在"我测的那个方法"上。**

**三、M21 首轮打绿 —— 断的是常量引用，不是对外的字面量**。
`tablesAreTyped` 原来写 `assertEquals(WfTableInfo.KIND_COLLECTION, info.getKind())`。
把常量的值从 `"collection"` 改成 `"collections"`，**判据与实现同步变化**，照样绿。
⇒ 改成断字面量 `"collection"`。理由是它**对外是契约**：REST 把这个字符串原样返回给客户端，
而共享 H2 的 Web 测试跑不到 collection 分支（它是 JDBC），所以 core 侧必须断字面量。
⇒ 通用形式：**断言要落在对外可见的那个值上，不要落在双方共用的那个符号上**，
否则符号改了什么，断言就跟着改什么。

**四、M17 是「变异与名字不符」，第五次**。
我把它命名为"内存侧名单倒序返回"，实际改的是 `STORAGE_NAMES` **常量内部**的排列 ——
而 `getTableNames()` 返回的就是那个常量本身，两边同步变化，**任何判据都不可能观测到**。
⇒ 重写成真正想测的东西："返回处反转一份副本（不再直接暴露常量）"，随即转红。
判据 `assertEquals(STORAGE_NAMES, memory)` 真正保护的是
**"内存侧不许另抄一份名单"**（另抄就迟早漂），而不是顺序稳定本身 ——
后者由不可变常量天然保证，本来就不需要判据。

**五、harness 漏了一个文件，基线编译不过，而我只看到一句 `BASELINE INSTALL FAILED`**。
worktree 的 `FILES` 清单里漏了 `WfPersistence.java`（SPI 接口），
于是 worktree 里的 `WfManagementService` 编译报 `cannot find symbol: getTableNames()`。
排查这一条花的时间，比把输出打出来多花的时间长得多。
⇒ harness 在基线 install / 用例失败时**打印 mvn 输出的尾部**；
每条变异没按预期变红时也打前几行 `[ERROR]`。
⇒ 与第 16 轮「`PATCH NOT FOUND` 失败路径也要还原再退出」同源：
**harness 的失败路径本身不该是信息黑洞**，否则每次排障都要手工再跑一遍构建。

**六、`SCHEMA_VERSION` 的值没有判据，这是有意的**。
`VERSION` 有（一条测试去读 pom 的 `<revision>` 钉住它，因为手写常量必然会漂），
`SCHEMA_VERSION` 没有 —— 它的值是**人定的迁移契约**，硬编码断言只会在正常升版时制造假红。
⇒ 把它判据缺省这件事写进注释，而不是留一条以后有人"顺手补上"的假判据。
⇒ 它的真正不变式是"往表里加字段**不**升它，只有表被重命名或删除才升"，
而这条只能靠跨版本比对来验，本仓没有那个设施。**承重但当前无法自证**，如实记下。

**七、JDBC 侧两处刻意的选择**。
① `getTableNames()` **从 `DatabaseMetaData` 真查**而不是报常量：
常量回答"打算建哪些"，这里要回答"这个库现在真有哪些"，两者在迁移没跑时会分家；
用元数据接口而不是 `INFORMATION_SCHEMA`，是因为后者的表名大小写与 schema 过滤规则跨库不一致。
② `getTableCount` 先用 `getTableNames()` 白名单校验再拼标识符：
**表名不可参数化**（`?` 只对值有效），而表名来自 REST 查询参数，
所以校验来源必须是**数据库自己报的清单**，不是请求参数。
变异 M19（去掉白名单）转红，那串 `ZWF_TASK; DROP TABLE ZWF_PROCESS` 立刻现形。

---

### 本轮反向验证记录（令牌条件查询 + 流程实例改名，第 20 轮）

本轮补两块：`createExecutionQuery` 的对应物（令牌条件查询）与
`setProcessInstanceName`。`20` 条变异全红，`3` 条对照全绿。
**一条两套实现分家的真缺陷在流出前被抓到。**

**一、自己判据抓到的真缺陷：「字段只有一个所有者」这条不变式只在 JDBC 上成立**。
新增 `WfProcessInstance#name` 时我判断得很清楚：名字只能由 `setProcessInstanceName` 写，
所以 `saveProcessInstance` 的部分更新刻意不含该列。
**而内存实现压根不是部分更新 —— 它是整对象覆盖**
（`processInstances.put(id, copy(instance))`），调用方手上那份实例往往是改名前取的，
回写就把名字抹成 null。
⇒ 症状是「内存里好好的、一上真库就丢」，而排查的人会先怀疑数据库。
两套单独看都没错，错的是**同一个不变式在两处的实现方式不同**。
⇒ 修法是让内存实现也守住这条边界（存之前把原有的 name 拷回副本）。
代价是「用 `saveProcessInstance` 改名字」这条路**两边都不通**了 ——
那正是"字段只有一个所有者"要的效果，不是顺带的限制。
⇒ 与第 17 轮 `deleteHistoryBefore` 同族：**只有真库抓得到的那一类，
反过来也可能是"只有内存抓得到"的那一类**，别默认问题总在存储那一侧。

**二、`ENTERED_TIME` 排序刻意不交给数据库**。
`enteredTime` 可空（外部写入 / 脏数据），而"NULL 排前还是排后"在
H2 / MySQL / PostgreSQL 上**结论相反**：
`ORDER BY t DESC` 在 H2 把 NULL 排最后、在 PostgreSQL 排最前；
用 `ORDER BY t IS NULL` 显式控制也不行 —— H2 / PG 上它是布尔（false < true，于是 NULL 排前），
MySQL 上是 0/1（0 在前，于是 NULL 排后）。
⇒ 存储层只按主键倒序保证**页内稳定**，语义排序统一在服务层做（两套实现跑同一段 Java）。
同理，id 里带进程随机数、**跨实例不可比**，所以它只用来在同一时刻的若干条之间给稳定顺序。

**三、变量过滤与排序都在 Java 里做，所以分页不能下推**。
`VARIABLES` 是 JSON 文本列，跨库写不出同一段 SQL；过滤一旦发生在分页**之后**，
把分页交给 SQL 就是错的：先 LIMIT 50 再过滤，可能只剩 3 条，也可能一条不剩，
而调用方会以为"就这些了"。
⇒ `scan()` 故意传一个"一页超大"的 pageSize 给存储层，过滤后再分页。
`count` 与 list **共用同一次扫描**，不走 SQL `COUNT(*)` —— 两者一旦分开，
列表 1 条而 count 说 8 条时，调用方只会以为自己算错了。

**四、M07 打红前打了两次绿，两次都不是"判据没区分力"**。
① **判据因错误的原因通过**。排序那条我原先只造了引擎自己建的两条令牌，
它们的时间戳**落在同一毫秒** ⇒ 比较器走 id 兜底；再加上原始顺序恰好已经是
「非 null 在前」，排序**根本没做功**，于是「前两条非 null」平凡成立。
⇒ 改成造时间明显递增的三条、且要求顺序与插入顺序不同，排序才非做功不可。
② **判据在测一个造不出来的状态**。造"没有进入时间的脏数据"时我用了
`new WfExecution(id, pid, activityId)`，而**那个三参构造器里有一句
`this.enteredTime = new Date()`** —— 造出来的是"刚刚进入的令牌"，照样有时间戳。
⇒ 必须显式 `setEnteredTime(null)`。
⇒ 这两条合起来是「第 0 问」的又一次：断言打不红 / 平凡通过时，
先问「这个输入下**被测代码可能**产生这个效果吗」，以及「这个夹具造出来的**真是**我要的数据吗」。

**五、M09 是"变异与名字不符"，而它指的是一段死拷贝**。
变异改的是 `copy()` 里的 `variableName`，但 `scan()` 的过滤读的是**原 query** 而非副本 ——
变量条件压根不下推，所以那两行复制是死的。
⇒ 不是补判据（那是"为将来准备的测试"），而是**把那两行删掉并写明原因**：
`copy()` 只复制参与下推与分推的字段；若将来把变量条件下推到 SQL，必须同时加回来。
⇒ 与第 18 轮 `MAX_PENDING_EVENT_DRAIN` 同一处置原则：**不承重的实现不靠判据救，也不假装有**。

**六、`scan()` 一度就地改调用方的 query，翻页因此失效**。
写的时候想的是"读数据时把分页调大"，改的却是**调用方传进来的那个对象** ——
之后 `listExecutions` 再用它算分页，读到的是被改过的参数，
于是第二页永远等于第一页，而那看起来像"数据只有一页"。
⇒ 加 `WfExecutionQuery#copy()`，并在它的注释里写明"存在就是为了不在调用方的对象上就地改"。
变异 E05 立刻转红。

**七、harness 的 FILES 清单连续三轮漏文件，本轮改成从 git 自动生成**。
第 19 轮漏 SPI 接口（编译报 `cannot find symbol`），
第 20 轮先漏 DTO（`setName` 找不到）、修完又漏 `WfAutoConfiguration`
（web 全红 `NoSuchBeanDefinitionException`）。
**三次的症状都长得极像「代码写错了」**，每次都要多花一轮排查。
⇒ 清单不手写，`git status --porcelain` 逐行取（目录要 `rglob` 展开并 `is_file()` 过滤）。
⇒ 配套：基线失败时**打印 mvn 输出尾部**；上下文起不来时**真因只在 surefire 报告的
`Caused by` 里**，外层的 `IllegalStateException: Failed to load ApplicationContext` 不含根因。
⇒ 与第 16/17 轮「`PATCH NOT FOUND` 失败路径也要还原再退出」同源：
**harness 自己的失败路径是最容易被误判成"被测代码有问题"的地方**。

**八、顺手订正了一行长期过时的能力表**。
`FilterService`（保存的查询）那一行长期挂着 ❌，而功能早已实现
（`WfFilterService` + `ZWF_FILTER` 表 + 一整套 REST）。
读表的人会以为"保存筛选条件"得业务方自己存，于是自己又造了一套。
⇒ 这就是第 17 轮那条「照着一个缺陷写出来的设计说明，在缺陷修好后会变成新的错误文档」的
另一个方向：**已经修好的缺陷，会在别的文档里留下"还没修"的痕迹**，
而那些痕迹没人会去核对，因为它们看起来只是文档。

---

### 本轮反向验证记录（条件式事件 `conditionalEventDefinition`，第 21 轮）

补的是审批系统里最常见的一个需求的缺口：「**超时 3 天<i>而且</i>金额超过 1 万才提醒**」。
`12` 条变异全红，`3` 条对照全绿，**没有新缺陷**。

**一、条件叠加在事件类型之上，而不是二选一**。
BPMN 里 `intermediateCatchEvent` 只挂一种事件定义，所以本实现把
`conditionalEventDefinition` 当成**叠加**关系：事件类型回答「等什么」，
条件回答「够不够格」。同时挂两者**不报错** ——
报错等于逼作者二选一，而二选一的结果是「要么没条件、要么没有事件类型」，两种都答非所问。
部署期仍守住一条：**只有条件、没有 message / signal / timer 之一 ⇒ ERROR**，
且报错必须点明「条件不提供事件类型」——作者的误解恰恰是"条件式事件自己就能等"。

**二、求值点在「事件到达那一刻」，不在建订阅时**。
这是条件式事件的全部价值所在：审批金额、已过天数往往在**等待期间**才定下来，
建订阅时求值等于用还没发生的事实决定分支，症状是「金额明明超了却不提醒」。
判据专门造了「启动时还没有 amount，到达时已经是 20000」这一条来钉它。

**三、插入点只有一个：`fireEventGatewayBranch`**。
消息、信号、定时器三条投递路径**全部**经过它，条件闸门加在那里就一次覆盖三条。
（与第 18 轮「十来条推进路径统一收口」同一种做法：
逐条各加一道迟早会漏，而漏掉的那条症状是"这个事件类型下条件不生效"。）

**四、同一个「不算它赢」，投递方与定时器的处置必须相反** ——
这是本轮最需要写下来的分界，也是首次由判据逼出来的：

- **消息 / 信号**：要**说清**。条件挡住时抛 `WfConditionalEventBlockedException`，
  由 `fireEventGatewayBranches` 收集后抛一条"没有一条分支的条件成立"的错。
  绝不能让它落到 `triggerMessage` 的「没有等待消息」那句上 ——
  「有人在等但条件不成立」与「压根没人等」的处置完全相反
  （前者去看那个变量当时是多少，后者去建流程），合成一句"没反应"会把人带偏。
  ⇒ 与第 16 轮 `correlate` 那条「没人在等」与「有 N 条在等但都没匹配上」分开报**同一条判据**。
- **定时器**：要**静默跳过**。它是引擎自己在跑，条件不满足就是「这次到点不算数」，
  那完全正常。抛出去会让一个正常到点的 job 变成失败并重试 ——
  而它永远不会成功，重试耗尽后还会被记成一条故障，
  于是「条件不满足」变成了「流程出故障」，两件不相干的事。

**五、条件不成立时：订阅留着、兄弟分支不动、写一条带条件原文的评论**。
删掉订阅的后果是「第一次没提醒，之后永远不提醒」；
作废兄弟分支的后果是「它没赢却替别人赢了」；
不留痕的后果是排障的人看到「提醒没来」时，得自己去猜那个变量当时是多少。

**六、判据写错的三次，一次比一次隐蔽**。
① **挑了上游已经管过的输入**。我用「引用一个不存在的变量」当"条件求值失败"的用例，
而 `WfExpressionEvaluator` 内部对未定义变量**已经 fail-closed**、压根不抛异常，
所以运行时那道 `catch` 根本进不去 ⇒ 变异打不红。
⇒ 换成**语法错**（`${amount >}`，真会抛 `ElParseException`）才断到运行时那道闸门。
⇒ 这与第 20 轮那条「判据在测一个造不出来的状态」是同一条纪律的两个方向：
**先问「这个输入下被测代码真的会产生这个效果吗」，上游已经拦下的就不算**。
② **断言写反了三次**（赢的分支清完订阅后剩几条、条件不成立时该有几个待办），
每次都要先想清"正确行为是什么"再写数字。
③ **变异与名字不符两次**：一条把 catch 改成了逻辑等价的写法（我以为改的是"不接条件"），
一条只删了报错的**后半句**而判别式在**前一句**，于是照样绿。
⇒ 后者尤其值得记：**「删掉一段文案」这类变异，要确认被删的正是判据断的那几个字**，
相邻两句里只要有一个还留着同样的关键词，断言就恒成立。

**七、一次未留档的失败**。
`mvn clean install` 出现过一次失败（`-rf :z-wf-admin` 提示失败在 admin 模块），
**当时没有把输出留档**，随后连续 3 次全量复跑全绿，无法定位到具体用例。
本轮改动不含时序成分（无共享状态、无 ThreadLocal、无时间依赖），
故倾向既有的偶发，但**这是个未查清的账**：下次遇到疑似 flaky，
先 `tee` 留档再复跑，否则复跑再多次也说不清是哪条。

### 本轮反向验证记录（链接事件 `linkEventDefinition`，第 22 轮）

补的是 `move` 与排他网关都替代不了的那件事（详见 §2 那一行）。
`21` 条变异全红，`4` 条对照全绿，**顺带修掉一个已发布的真缺陷**（`zifang:resultVariable`）。

**一、两条"看着一样、必须反过来写"的语义，不能互相参照**。
改道后 token 沿 **catch 自己的出线**走，throw 自己的出线不会被走过；
而 escalation 恰好相反 —— Camunda 文档明写 "outgoing sequence flows will be taken"。
两处只差一个"从谁身上出线"，参照着写必错一条。
⇒ 特判点因此放在 `leave` 的**取出线之前**：放之后，"跳过一整段"会变成"跑完整段再跳一遍"，
而图上看不出任何异常。

**二、"报错"与"报 WARN"的分界按「静默失效的范围」划，不按严重程度划**。
throw 有出线 → WARN（那是摆设，作者自己看得见）；catch 有入线 → **ERROR**
（token 只由 link 改道进入，那条连线**永远不会被走过**，它上游的整段流程静默失效）。
后者藏着的东西多得多，所以不能因为"同样是连线画错"就一视同仁。

**三、四次"变异没打红"，四个不同的成因**。

| 现象 | 真实成因 | 判据侧的修法 |
|---|---|---|
| M05 打绿 | 「多个命中时挑第一个」这条**运行期不可达** —— 部署期已经挡住重名 catch 了 | 用 `DoctoredPersistence` 篡改定义，把这个状态造出来 |
| M20 打绿 | 往返测试只用了内存实现，它**深拷贝、走不到 codec** | 内存 + JDBC 两套都测 |
| M19 打绿 | 变异与名字不符：判据断的是 `escalation` 这个词，我却删了报错的后半句，前半句里它还在 | 重写变异，确认删掉的正是判据断的那几个字 |
| G01 补不上 | 变异文本写的是块注释 `*`，文件里是行注释 `//` | 对齐注释形态 |

M05 那条最值得记：**部署期挡住的状态，不能直接拿运行期的正常路径去测它**——
判据得自己把那个状态造出来（篡改持久化里的定义），
否则测的只是一个永远到不了的分支，测绿了也说明不了什么。

**四、往返断言必须两套实现都测**。
codec 的往返断言只在内存实现上跑过，而内存实现是**深拷贝**，
序列化那一层根本没被走到 —— 表面上"往返测试通过"，实际一行 codec 都没执行。

### 本轮反向验证记录（DMN 决策表 `DecisionService`，第 23 轮）

补的是整块缺失的 `DecisionService`（见 §1 那一行）。
核心层 `27` 条变异全红、`6` 条对照全绿；REST 层 `12` 条变异全红、`2` 条对照全绿。
实现过程中被自己的判据逼出 **4 个真缺陷**（3 个在求值器、1 个在 REST 层的异常映射）。

**一、开工先做文档-代码核对，而不是照着上一轮列的缺口直接做**。
记忆里"`createHistoricIncidentQuery` 是缺口"**已经不成立** ——
`WfIncidentService` 与 `/incidents/count`、`/process/overview`、`/dashboard` 早就在了。
核对 §1（DecisionService 标 ❌）与 §5（五条有意排除**不含** DMN）之后才敢确定：
DMN 是**真缺失**，不是设计决策。

**二、只做决策表，不做决策图**。
决策图（`informationRequirement`）要求按**拓扑顺序**求值多个决策、每跳输入是上一跳输出。
折成"按声明顺序跑一遍"，在依赖顺序与声明顺序不一致的图上会**算出错误结果且不报错** ——
所以部署期直接报错，而不是降级。

**三、按 `localName` 找元素，不按命名空间 URI**。
DMN 工具链的命名空间版本在 1.1 / 1.2 / 1.3 之间极度分裂，各家建模器导出的还不一样。
按 URI 匹配意味着"换个工具导出的文件就读不出来"，而报错只会说"找不到元素"。

**四、判据抓出来的四个真缺陷**（三个在 `WfDmnEvaluator`）：

1. **`inputEntry` 是单目测试，不是完整表达式** —— 隐含的左操作数是该列 `inputExpression` 的值。
   初版当独立表达式求值，于是**每一条带比较符的规则都不命中，且不报错**。
   表看着能跑，实际只按 `-` 那几列在筛。
2. **补全单目测试时把比较符切掉了** —— 写成 `test.substring(matcher.end())`，
   `> 5000` 于是被补成 `(amount) 5000`。与上一条叠加：表能跑、结果全错、无报错。
3. **`OUTPUT_PRIORITY` 依次覆盖同一个 key** —— 赢的是**最后**出现的值，
   优先级整个反过来却没有报错；且第二个循环遍历 `getOutputs()`（对外形状）而不是展开后的值，
   产出 `[[b, a], ...]` 这种套娃。
4. **REST 层：`WfDmnViolationException` 挂在 `RuntimeException` 上** ——
   统一异常映射里没有它的 handler，于是"这张表两条规则重叠"落成 **500**。
   那是调用方改表就能解决的 400 级问题，报 500 会让前端一律弹"系统错误"。
   改成继承 `WfEngineException` 之后自动走 400。

**五、六条"变异没打红"，六个不同的成因**（这是本轮最值得记的部分）：

| 现象 | 真实成因 | 判据侧的修法 |
|---|---|---|
| D04 打绿 | 判据只断报错里有「决策图」三个字，而**同一文件里"没有 decisionTable"那条兜底分支也写着「决策图」** | 两侧都断：既要有决策图那段独有的措辞，也要 `assertFalse` 不含兜底那句 |
| D15 打绿 | 夹具只造了"两条命中值都在 outputValues 里"——那种输入下"按优先级合并"与"取最后一条命中"**结果完全相同** | 补一条命中值落在 `outputValues` 之外的 |
| D17 打绿 | **等价变异**：探针实测 `evalRaw(expr, null)` 与 `evalRaw(expr, 空表)` 完全等价（求值器只在表非空时装载变量） | 改判为对照，并在注释里写明为什么等价 |
| D18 打绿 | 夹具只有字面量输出项，而字面量两边都求得出 —— 断不到"求不出值"那条路 | 补语法错 `${amount >}` 的输出项 |
| D22 打绿 | 内存实现天然按版本删，**JDBC 那条 DELETE 语句从头到尾没被任何用例跑到** | 补一条真库的按版本删除 |
| G01 补不上 | 变异文本按块注释 `*` 写的，文件里是 `**` 包裹的行文 | 对齐注释形态 |

D04 那条与第 21 轮记过的「删文案类变异要确认删掉的正是判据断的那几个字」同源，
但方向相反：那次是**变异删多了**，这次是**判据的关键词在另一条分支里也出现**。

**六、探针实测推翻了自己写的一条注释**。
`evalOutput` 上原本注释说"传 null 时求值器跳过变量装载，纯字面量会求不出值"——
探针实测 `evalRaw("\"ceo\"", null)` 返回 `"ceo"`，**注释描述的行为根本不存在**。
据此改正文：两者今天等价，写空表是为了让"输出项不引用变量"在调用点上看得见，
而不是依赖求值器内部那个 null 判断。
⇒ 与「注释里描述的属性必须数据真具备」同源：**注释也会错，而且错得比代码更难发现**。

**七、REST 层必须有，判据也得有两处**。
本仓每个 service 都有对应 controller，只写服务不接 REST 就是半套。
但 z-wf-web 的单测是 `standaloneSetup`（手工 new controller + 注入 service），
**它断不到自动装配** —— 服务注册成 Bean 没有、路由没挂上，单测照样全绿、端点全 404。
所以判据分两处：`z-wf-web` 的 `WfDecisionControllerTest`（断路径/字段/状态码）
与 `z-wf-admin` 的 `@SpringBootTest` 端到端（断 bean 注册与真实路由），一起跑。

**八、"变异没打红"的第三类：变异没进编译路径**。
REST 层的 harness 一开始写成 `-pl z-wf-web,z-wf-admin`，
结果改 **core** 源码的两条变异（异常继承、bean 注册）全绿 ——
web/admin 链接的是本地仓库里**已安装**的那份 core jar，那份 class 从来没被重建。
症状与"判据没区分力"一模一样，但修法完全不同：命令要把 `z-wf-core` 一起列进 reactor。

### 本轮反向验证记录（业务规则任务 `businessRuleTask`，第 24 轮）

补的是「流程里直接求值一张决策表」这个接头（详见 §2 那一行）。
`27` 条变异全红、`3` 条对照全绿；顺带修掉一个**已发布的真缺陷**（`camunda:resultVariable` 读不到）。

**一、它此前既没实现，也没被记成缺口**。
`businessRuleTask` 不在 §2 的元素表里，也不在代码里 —— 不是"文档漏写"这么简单：
它在解析器的元素表里也没有，于是走未知元素路径、被校验器当"不支持的元素"挡掉，
**一份用业务规则任务的真实流程在本引擎里部署不了**。
而决策表第 23 轮刚补上、REST 也通了，接头却一直空着 ——
一个能力做完之后要回头看"**谁会用它、怎么用**"，否则就是半套。

**二、四条语义决定**：

1. **`resultVariable` 必填**（部署期 ERROR）。Camunda 里结果可以不落变量
   （还能用 `decisionResult` 局部变量 + 输出映射），本实现**没有那条路** ——
   不给写进哪个变量的话，这个节点等于什么都没做：流程照常穿透，且没有任何报错。
   与其留一个"看起来在工作、实际什么都没发生"的节点，不如部署期就挡。
2. **`decisionRef` 只有带 `${}` / `#{}` 前缀的才当表达式**。
   拿裸串去求值的话，`approvalLevel` 会被当成变量名，而求值器对未定义变量
   fail-closed 返回 null ⇒ **每张决策都变成"决策 null 不存在"** ——
   报的还是一句把人带去查决策表的话，而真正的原因是"这个裸串不是表达式"。
3. **Camunda 的 `deployment` / `versionTag` 绑定明确报错**，不悄悄当成 `latest`。
   前者要求"BPMN 与 DMN 同属一个部署单元"，而本仓两者分别部署（两个服务、两条 REST 端点）；
   后者要求 `ZWF_DECISION` 上有标签列，而本仓的决策只有版本号。
   放行的后果是流程在运行期拿到一个**谁都没指定过的版本**的规则。
4. **部署期不检查决策是否已部署**。先后顺序是自由的（先部署流程、补规则后再部署决策表
   是正常节奏），为一个"迟早会部署"的检查把流程挡在门外，比留到运行期报错更糟。

**三、顺带修的真缺陷：`camunda:resultVariable` 读不到**。
解析器读扩展属性只认 zifang 那三条路径（命名空间 / `zifang:` / `zifang_`），
而 **Camunda 导出的模型写的是 `camunda:` 前缀**。
于是 `<scriptTask camunda:resultVariable="x">` 被读成"没配"：
脚本照常求值、流程照常穿透，**只有「结果写到哪」静默失效**。
第 22 轮修的是同一个东西的另一头（读字段 vs 读属性 Map）——
**同一个不变式的两种写法，只修了一种**，而症状一模一样。
⇒ 与第 20 轮「两套实现分家」同源：真正要问的是"这条不变式**有几处**在独立实现"，
而不是"我这次看到的那一处"。

**四、五条判据先写错了，错的方式只有一种**。
它们一开始都断"行为抛了 `WfEngineException`"，
而**引擎不会把行为里的异常抛给调用方** —— 它统一转成"内部终止 + 写明理由"
（`WfEngine.fail`）。五条于是全绿地"什么都没测到"。
改法是断**实例停在哪 + 理由说的是哪件事**，后者才是排障时真正读得到的东西。
⇒ 与第 22 轮 M05「判据在测一个到不了的状态」同族：
**先问「这段判据想断的那个东西，在这个引擎里到底以什么形态出现」。**

**五、B09 / B10 又踩了第 23 轮 D04 那个坑 —— 而且是刚修完它的下一个功能**。
两条变异都是"把专属分支放行"，判据只断报错里有 `deployment` / `versionTag` 三个字；
放行后报错落到「未知的 decisionRefBinding」兜底上，而**兜底那句话里同样列着这两个词**。
⇒ 判据改成两侧都断（要专属措辞 + `assertFalse` 不含兜底那句）。
⇒ 记这条不是因为它新鲜，而是因为它证明**「关键词在另一条分支里也出现」是个高复发缺陷**：
上一轮刚在解析器上修过，这一轮立刻在校验器上重犯。
**凡是有"兜底分支 + 专属分支"两条路的校验，判据必须两侧都断**——
只断关键词时，被挡掉的恰恰是「说清是哪一种不支持」那部分。

### 本轮反向验证记录（复杂网关条件分派，第 25 轮）

补的是复杂网关的 **BPMN 侧语义**：出线带 `<conditionExpression>`，
条件成立的线全部激活（`10` 条变异全红、`2` 条对照全绿）。
本轮修掉的**不是缺口，是一个"能力看着在、实际不生效"的已发布缺陷**。

**一、先确认汇合侧承重，再动手**。
复杂网关一旦能激活多条，分支后面必然要汇合 —— 所以动手前先读了汇合判定：
它数的是**"确实已激活的兄弟 token"**（`allSiblingsArrived`）而不是图上入线总数，
没被选中的线根本不产生 token ⇒ **不会死锁**。
这条是整个改动成立的前提，所以它同时被写成注释**并配了一条判据**
（汇合有 4 条入线、只激活 2 条，两条都办完后必须放行到收尾任务）。
⇒ 「注释里断言的属性必须有判据钉住」，否则那句注释本身就成了没人验证的承诺。

**二、这个缺陷的形状比"忘了实现"更糟**。
出线上的 `<conditionExpression>` 被解析器**读进来了**、被 codec 存下来了、
校验器还会在"它与 caseValue 同时配"时**报错** ——
也就是说这个属性"看起来是有意义的"，而运行期压根不看它。
于是一条只带条件的出线永远不会被选中，流程**静默落到默认线**且没有任何报错。
⇒ 与第 6 轮「未支持元素静默退化」同族，但更隐蔽：
那一条是"这个元素退化成人工任务"，这一条是"这个属性被读进来了却没人用"。

**三、两种判定方式不许混，混了要在部署期挡**。
同一个网关上既有 `caseValue` 出线又有条件出线时，"走哪几条"**没有唯一答案** ——
而猜错的后果是流程走上一组作者没想过的分支。
所以不是"挑一种执行"，而是报 ERROR 并说清两种改法。
同理，出线带 `caseValue` 却没配判别变量、两种方式都没配，也都挡掉。

**四、错误文案要跟着语义一起改**。
判别变量缺失那条原来写的是「缺少 `zifang:caseVariable`」，在引入条件分派之后
**这个理由已经不成立了**（有条件分派这条路）。若照抄原文，
一条真正写错的模型会收到一句在替一条已不存在的规则说话的话。
判据因此加了 `assertFalse`：报错里不许再出现那句旧措辞。
⇒ 与第 24 轮「关键词在另一条分支里也出现」同族：
**文案也是契约的一部分，改了语义就要同步改文案，并让判据钉住。**
