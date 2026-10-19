# 项目总览

## 1. 项目简介

z-wf 是 z-opc-foundation 的**自研 BPMN 流程引擎**。它**不依赖 Camunda / Flowable / Activiti 等任何
第三方工作流引擎** —— 定义层协议与 `z-util-wf-kernel` 共用，运行时层（token 执行树、网关求值、
持久化、REST）全部自研。

同族的 `z-camuda` 是「在 Camunda 上封装一层我们的 REST 形态」，`z-wf` 是**换掉引擎本身**。
两者模块切分一致（core / web / starter / admin），接入侧的认知可以平移，但底层语义不同。

**盘面实测：** 仓内共 **5** 个 `pom.xml`（根 1 + 模块 4）；受版本控制的 `.java` 共 **255** 个
（core 212 / web 27 / starter 6 / admin 10），其中 `src/main/java` 下 168 个、`src/test/java` 下 87 个
（87 个测试类）。

**坐标与版本：** `io.github.yuku123:z-wf:${revision}`，根 POM `<revision>2.0.0</revision>`，
`<parent>` 是 `io.github.yuku123:z-boot-parent:1.1.0`。**版本号是刻意的破坏性大版本 2.0.0** ——
Central 上 `io.github.yuku123:z-wf` 的 1.0.4 / 1.0.5 / 1.0.6 属于**另一个引擎**
（Camunda 版，即改名前的 z-wf），1.x 与 2.x 是两套不同的实现。消费方必须写明 `2.x`
并把 1.x 排除在版本区间外。

**技术栈：** Java 8 + Spring Boot + MyBatis（JDBC 持久化）+ z-util 家族
（`z-util-core` / `z-util-wf-kernel` / `z-util-parser-json` / `z-util-expr-el` / `z-util-expr-js` /
`z-util-jdbc`）+ Knife4j 接口文档 + React 19 / Vite 6 / antd 6 / LogicFlow 2（前端设计器）

## 2. 模块架构

```
z-wf/
├── pom.xml                       # 聚合根：io.github.yuku123:z-wf:2.0.0，parent=z-boot-parent:1.1.0
├── Dockerfile                    # 多阶段：maven:3.9.9-eclipse-temurin-8 编译 → temurin 8 JRE 运行
├── docker-compose.yml            # 三服务：mysql + z-config(8848) + z-wf(8080)
├── CHANGELOG.md
├── z-wf-core/                    # 定义层 + 运行时 + 持久化 + 服务 + 钩子（212 个 java）
│   ├── definition/               #   WfDefinition / WfNode / WfFlow / WfXmlParser / WfJsonParser /
│   │   │                         #   WfDiagramParser / WfDefinitionValidator / WfNodeType（25 种）
│   │   ├── bridge/               #   ZUtilWfBridge（与 z-util-wf-kernel 的双向桥）
│   │   └── dmn/                  #   WfDmnParser / WfDmnDecision / WfDmnHitPolicy
│   ├── engine/                   #   WfEngine / WfContext / WfBehaviorRegistry / WfIdGenerator /
│   │   ├── behavior/             #     每种节点一个 behavior（User/Service/Script/Gateway/Event…）
│   │   ├── delegate/             #     WfJavaDelegate / WfTaskListener
│   │   ├── expression/           #     WfExpressionEvaluator（未定义变量 fail-closed）
│   │   └── dmn/                  #     WfDmnEvaluator
│   ├── model/                    #   WfActivityInstance / WfBatch / WfComment / WfCompensationEntry …
│   ├── persistence/              #   WfPersistence / InMemoryWorkflowPersistence /
│   │                             #   JdbcWorkflowPersistence / WfOptimisticLockException / 各类 Query
│   ├── service/                  #   29 个 service：WfRepositoryService / WfRuntimeService / WfTaskService /
│   │                             #   WfHistoryService / WfManagementService / WfMetricsService …
│   ├── hook/                     #   WfHookDispatcher / WfProcessHook / WfTaskHook / WfNotificationHook
│   ├── view/                     #   WfActivityInstanceView / WfIncidentView / WfShape / WfEdge …
│   └── config/
├── z-wf-web/                     # REST API（27 个 java）
│   ├── api/                      #   17 个 Controller：/api/wf/{definitions,executions,task,history,
│   │                             #   process,jobs,incidents,group,management,batches,decisions,
│   │                             #   external-tasks,filters,subscriptions,variable-instances} +
│   │                             #   /api/approval-center + WfExceptionAdvice
│   ├── config/WfWebAutoConfiguration.java
│   ├── dto/{WfRequests, WfViews}.java
│   └── mapper/WfViewMapper.java
├── z-wf-starter/                 # 自动装配（6 个 java）—— 业务方引这个
│   ├── WfStarterAutoConfiguration / WfStarterComponentScan / WfServerLifecycle
│   ├── rpc/WfProcessRpcService(+Impl)      # z-rpc 集成
│   └── sample/FiveLookAggregateDelegate    # 示例 delegate
├── z-wf-admin/                   # 独立可启动应用（10 个 java）—— 永不上 Maven Central
│   └── WfAdminApplication / config/WfWebMvcConfig
├── _frontend/
│   ├── z-wf-component/           # npm 包 @yuku123/z-wf-component（0.1.1，发布）
│   └── z-wf-suit/                # 独立运行壳
└── _doc/                         # 文档，见文末「文档目录」
```

**依赖方向：** `z-wf-core → z-util-wf-kernel`（上层依赖下层，反向不成立）；
`z-wf-web → z-wf-core`；`z-wf-starter → z-wf-web`（并传递 `z-wf-core`）；
`z-wf-admin → z-wf-starter`。

## 3. 核心功能

### 3.1 定义层：两种输入，同一个 `WfDefinition`

- **BPMN 2.0 XML**（`WfXmlParser`）：标准元素 + `zifang:*` 扩展命名空间，带 XXE 防护
  （DOCTYPE 直接拒绝）；扩展属性不改标准元素名，标准工具链仍能识别。
- **JSON**（`WfJsonParser`）：LogicFlow 设计器导出（`user-task` / `branch` 等别名归一）与本仓原生格式。
- **XML + DMN**（`WfDmnParser`）：决策表单独解析，与流程分开部署。
- `WfDefinitionValidator` 部署前一次报全部问题（重复 id、悬空连线、排他网关有条件但无 `defaultFlow`、
  多个开始节点、`serviceTask` 缺 delegate）。

### 3.2 节点类型与行为

`WfNodeType` 枚举 **25** 种：`START_EVENT` / `END_EVENT` / `TERMINATE_END_EVENT` /
`CANCEL_END_EVENT` / `TRANSACTION` / `USER_TASK` / `SERVICE_TASK` / `SCRIPT_TASK` / `MANUAL_TASK` /
`SEND_TASK` / `RECEIVE_TASK` / `THROW_EVENT` / `EXCLUSIVE_GATEWAY` / `PARALLEL_GATEWAY` /
`INCLUSIVE_GATEWAY` / `COMPLEX_GATEWAY` / `EVENT_BASED_GATEWAY` / `INTERMEDIATE_CATCH_EVENT` /
`LINK_THROW` / `LINK_CATCH` / `BUSINESS_RULE_TASK` / `SUB_PROCESS` / `CALL_ACTIVITY` / `TASK` /
`BOUNDARY_EVENT`。

类型名大小写不敏感；**未识别的类型会被标记并在部署期报 ERROR，不静默放行**。
每种类型在 `WfBehaviorRegistry` 里对应一个 `WfActivityBehavior` 实现（`engine/behavior/` 下 14 个）。

### 3.3 运行时：自研 token 执行树

- `WfEngine` 走自研执行树，**enter / leave 严格分离**，支持 fork / join。
- `WfExpressionEvaluator` 做条件求值：**未定义标识符 fail-closed**（判 false，交 `defaultFlow` 兜底）——
  这是审批安全红线，条件算错会放行本该人工审批的单据。
- 业务规则任务求值 DMN 决策表，`mapDecisionResult` 支持 `singleEntry` / `singleResult` /
  `collectEntries` / `resultList` 四种，**映射与结果形状对不上时报错，不取第一条**。

### 3.4 持久化：SPI + 内存 / JDBC 两实现

- `WfPersistence` 是 SPI：`InMemoryWorkflowPersistence`（内存）与 `JdbcWorkflowPersistence`（JDBC）。
- JDBC 侧落 **13** 张 `ZWF_*` 表：`ZWF_DEFINITION` / `ZWF_PROCESS` / `ZWF_EXECUTION` /
  `ZWF_ACTIVITY` / `ZWF_TASK` / `ZWF_JOB` / `ZWF_BATCH` / `ZWF_BATCH_ELEMENT` / `ZWF_COMMENT` /
  `ZWF_COMPENSATION` / `ZWF_DECISION` / `ZWF_FILTER` / `ZWF_INCIDENT_HISTORY`。
- 并发用**乐观锁 CAS**（`WfOptimisticLockException`）。

### 3.5 服务层

`WfRepositoryService`（部署 / 查询定义）、`WfRuntimeService`（启动 / 推进实例）、
`WfTaskService`（待办 / 任务操作）、`WfHistoryService`、`WfManagementService`（挂起 / 激活 / 删除定义）、
`WfMetricsService`、`WfIncidentService` / `WfHistoricIncidentService`（异常实例）、
`WfJobService`（定时任务）、`WfSubscriptionService`、`WfFilterService`（过滤器）、
`WfBatchService`（批量操作）、`WfDecisionService`（DMN 决策）、`WfExternalTaskService`、
`WfActivityInstanceService`、`WfProcessQueryService` / `WfExecutionQueryService` /
`WfVariableQueryService` / `WfDeploymentQueryService`。

### 3.6 钩子（3 个接口，全是 `default` 方法）

`WfProcessHook` / `WfTaskHook` / `WfNotificationHook`，由 `WfHookDispatcher` 分发。

### 3.7 与 `z-util-wf-kernel` 的桥接

`ZUtilWfBridge`（`definition/bridge/`）做双向转换：`toWfDefinition`（内存图 → 生产定义）与
`toWorkflowConfiguration`（生产定义 → 内存配置）。桥接有两条硬规则：**条件归属不明 ⇒ 抛异常、
绝不丢弃**（丢条件会让 `isUnconditional()` 为 true 而放行单据）；**写出方向按 (目标, 源) 分键存条件**
（`cache["conditionExpression:<sourceRef>"]`），多入线时互不覆盖。

### 3.8 前端（`@yuku123/z-wf-component` 0.1.1）

- `src/pages/` 下 11 个入口：8 个流程页（`Dashboard` 工作台 / `TodoList` 待办 / `DoneList` 已办 /
  `MyProcesses` 我的流程 / `ProcessList` 流程定义 / `ProcessDetail` / `TaskDetail` /
  `ProcessDesigner` 流程设计器）+ `HomePage.jsx` / `LoginPage.jsx` / `WorkflowApp.jsx`。
- `FlowDesigner` 组件基于 `@logicflow/core` + `@logicflow/extension`。
- 通用 UI 组件 3 个（`EmptyState` / `ErrorState` / `PageHeader`）+ `tokens.js`。
- `exports` 三个子路径：`.` / `./pages` / `./style.css`。

## 4. 对外集成 / 上下游

**本仓提供 HTTP 边界。** 对外是 REST API（`z-wf-web`）与 Maven 坐标（`z-wf-core` / `z-wf-web` /
`z-wf-starter` 上中央；`z-wf-admin` **永不上中央**，POM 里写死 `<maven.deploy.skip>true</maven.deploy.skip>`）。

**REST 前缀（`z-wf-web` 的 Controller 实测）：**

| 前缀 | 覆盖内容 |
|---|---|
| `/api/wf/definitions` | 流程定义部署 / 查询 / 挂起 |
| `/api/wf/executions` | 流程实例 |
| `/api/wf/task` | 任务操作 |
| `/api/wf/process` | 流程操作 |
| `/api/wf/history` | 历史 |
| `/api/wf/jobs` | 定时任务 |
| `/api/wf/incidents` | 异常实例 |
| `/api/wf/group` | 分组 |
| `/api/wf/management` | 管理操作 |
| `/api/wf/batches` | 批量操作 |
| `/api/wf/decisions` | DMN 决策 |
| `/api/wf/external-tasks` | 外部任务 |
| `/api/wf/filters` | 过滤器 |
| `/api/wf/subscriptions` | 订阅 |
| `/api/wf/variable-instances` | 变量 |
| `/api/wf` | 健康检查（`WfHealthController`） |
| `/api/approval-center` | 审批中心（`WfApprovalCenterController`） |

**上游依赖：** `z-util-{core, wf-kernel, parser-json, expr-el, expr-js, jdbc}`；
starter 另引 `z-config-spring-boot-starter` 与 `z-rpc-spring-boot-starter`（**可选集成**）；
web 引 `spring-boot-starter-web` + `swagger-annotations`（Knife4j 文档）。

**端口（本仓可 grep 到的）：**

| 端口 | 出处 | 含义 |
|---|---|---|
| `8080` | `z-wf-admin/src/main/resources/application.properties` 的 `server.port=${SERVER_PORT:8080}`；`Dockerfile` 的 `EXPOSE 8080`；`docker-compose.yml` 的 `"8080:8080"` | 审批中心 REST API |
| `20880` | 同 properties 的 `z.rpc.server-port=${Z_RPC_SERVER_PORT:20880}` | z-rpc 服务端端口 |
| `8848` | `docker-compose.yml` 的 z-config 服务 | 配置/注册中心（compose 依赖） |
| `3306` | `docker-compose.yml` 的 mysql 服务 | MySQL 8.0（与 z-config 共用） |

**容器：** `Dockerfile` 是多阶段构建（`maven:3.9.9-eclipse-temurin-8` 编译 →
`z-wf-admin` 的 `-exec.jar` → 运行镜像），非 root 用户 `zwf`（uid 10001），
JVM 默认 `-Xms512m -Xmx1024m -XX:+UseG1GC`；healthcheck 打 `/api/wf/health`，
**该端点会真查一次库**，存储不可用时 status 变 DOWN。

**下游消费方：** 业务方引 `z-wf-starter`（它会传递 `z-wf-core` + `z-wf-web`）；
`z-wf-admin` 是独立部署的完整应用。

## 5. 技术特点

1. **不依赖任何第三方工作流引擎**：Camunda / Flowable / Activiti 一概不引，引擎、索引与持久化三层自研 ——
   与 `z-camuda`（在 Camunda 上封装）形成同一组织的两条路线。
2. **坐标"接续"而非"新占"，版本是刻意的破坏性大版本 2.0.0**：Central 上 `io.github.yuku123:z-wf`
   的 1.x 是 Camunda 版（改名 z-camuda 前的旧坐标），2.x 才是自研线；用大版本划线是为了让
   `[1.0,)` 这类版本区间的消费方**明确跨过一次破坏性变更**，而不是在版本边界上静默换掉整套引擎。
3. **条件求值 fail-closed 是审批红线**：`WfExpressionEvaluator` 对未定义标识符判 false（走
   `defaultFlow` 兜底），与 `z-util-wf-kernel` 的 `GatewayEvaluator`（空表达式当 true、
   未定义变量参与比较）方向相反。
4. **协议共用有可执行证据**：同一份 BPMN XML 分别喂 `z-util-wf-kernel` 的 `BpmnXmlParser` 与本仓的
   `WfXmlParser`，由 `WfDefinitionParserTest#parityWithZUtilWfKernel` 逐节点比对类型与连线数；
   桥接全链路由 `ZUtilWfBridgeTest#parityThroughBridge` 钉住。
5. **持久化是 SPI，内存与 JDBC 两实现**：单机/测试用 `InMemoryWorkflowPersistence`，
   生产用 `JdbcWorkflowPersistence`（13 张 `ZWF_*` 表 + 乐观锁 CAS）。
6. **业务方只引 starter**：`z-wf-starter` 传递 core + web，并提供 `WfStarterAutoConfiguration` /
   `WfStarterComponentScan` / `WfServerLifecycle`；z-config / z-rpc 集成是可选的。
7. **admin 永不上中央**：POM 写死 `maven.deploy.skip=true`，对齐 `z-camuda-admin` 模式 ——
   独立可启动应用与库构件的发布通道分开。

## 6. 目录结构

Java 侧按「core / web / starter / admin」四个 Maven 模块组织
（包根 `com.zifang.z.wf.core` / `com.zifang.z.wf.web` / `com.zifang.z.wf.starter` /
`com.zifang.z.wf.admin`）：

```
z-wf/
├── pom.xml
├── z-wf-core/src/main/java/com/zifang/z/wf/core/
│   ├── definition/          # WfDefinition / WfNode / WfFlow / WfNodeType(25) / WfXmlParser /
│   │   │                    #   WfJsonParser / WfDiagramParser / WfDefinitionValidator / 校验问题模型
│   │   ├── bridge/ZUtilWfBridge.java
│   │   └── dmn/             # WfDmnParser / WfDmnDecision / WfDmnHitPolicy / WfDecisionDependency
│   ├── engine/
│   │   ├── WfEngine / WfContext / WfBehaviorRegistry / WfIdGenerator / WfHistoryLevel / …
│   │   ├── behavior/        # 14 个节点行为实现
│   │   ├── delegate/        # WfJavaDelegate / WfTaskListener
│   │   ├── expression/WfExpressionEvaluator.java
│   │   └── dmn/             # WfDmnEvaluator / WfDmnDecisionResult
│   ├── model/               # WfActivityInstance / WfBatch / WfComment / WfCompensationEntry …
│   ├── persistence/         # WfPersistence / InMemory / Jdbc / WfOptimisticLockException / 各类 Query
│   ├── service/             # 29 个 service（Repository / Runtime / Task / History / Management / …）
│   ├── hook/                # WfHookDispatcher / WfProcessHook / WfTaskHook / WfNotificationHook
│   ├── view/                # Wf*View 视图模型
│   └── config/
├── z-wf-web/src/main/java/com/zifang/z/wf/web/
│   ├── api/                 # 17 个 Controller + WfExceptionAdvice
│   ├── config/WfWebAutoConfiguration.java
│   ├── dto/{WfRequests.java, WfViews.java}
│   └── mapper/WfViewMapper.java
├── z-wf-starter/src/main/java/com/zifang/z/wf/starter/
│   ├── WfStarterAutoConfiguration / WfStarterComponentScan / WfServerLifecycle
│   ├── rpc/{WfProcessRpcService, WfProcessRpcServiceImpl}.java
│   └── sample/FiveLookAggregateDelegate.java
├── z-wf-admin/src/main/java/com/zifang/z/wf/admin/
│   ├── WfAdminApplication.java
│   └── config/WfWebMvcConfig.java
│   （+ src/main/resources/application.properties）
├── _frontend/
│   ├── z-wf-component/src/
│   │   ├── index.ts / index.js / pages.js / pages-manifest.jsx / types.ts
│   │   ├── pages/           # Dashboard / TodoList / DoneList / MyProcesses / ProcessList /
│   │   │                    #   ProcessDetail / TaskDetail / ProcessDesigner / HomePage / LoginPage
│   │   ├── components/FlowDesigner/    # LogicFlow 设计器
│   │   ├── common/components/ui/       # EmptyState / ErrorState / PageHeader + tokens.js
│   │   ├── services/api.js
│   │   └── wf/              # 与 pages/ components/ 平行的第二份实现（同一套页面）
│   └── z-wf-suit/{index.html, src/{App.jsx, main.jsx}, public/}
├── Dockerfile  docker-compose.yml  CHANGELOG.md
└── _doc/
    ├── 001_arch/{01-capability-gap.md, 02-readme-en.md}
    └── 007_backlog/01-lead-handoff.md
```
