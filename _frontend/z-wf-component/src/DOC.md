# z-wf-component

z-wf（流程中心 / 审批中心）跨仓唯一前端面：把「工作台 / 待办 / 已办 / 我的流程 / 流程定义 /
LogicFlow 流程设计器 / 流程详情 / 任务详情」这一整套流程页面收敛成一个可复用的 React 组件包。

- **npm 包名**：`@yuku123/z-wf-component`
- **宿主接入**：`@yuku123/z-wf-component/pages`（manifest + API），主壳可只取 `ComponentDocPage`
- **后端**：审批中心适配层 `/api/approval-center/**`（Camunda BPM）+ 流程轨迹 `/api/wf/process/trail`
- **技术栈**：React 19 + antd 6 + react-router-dom 7 + LogicFlow 2 + vite（library mode，双入口 `.` / `./pages`）

---

## 一、能力概览

路由前缀 `/z-wf`，菜单与路由都在 `src/pages-manifest.jsx` 里声明，宿主读数组自己组装。

| 页面 | 路由（相对 `/z-wf/*`） | 源文件 | 能力 |
|------|--------------------------|--------|------|
| 首页 | `home` | `pages/HomePage.jsx` | 入口卡片页 |
| 工作台 | `workflow/dashboard` | `wf/pages/Dashboard/index.tsx` | 待办 / 已办 / 我的流程统计卡片 + 最近任务 |
| 待办任务 | `workflow/todo` | `wf/pages/TodoList/index.tsx` | 待办列表，支持分页与空态 |
| 已办任务 | `workflow/done` | `wf/pages/DoneList/index.tsx` | 已办列表，支持分页与空态 |
| 我的流程 | `workflow/my-processes` | `wf/pages/MyProcesses/index.tsx` | 我发起的流程实例列表 |
| 流程定义 | `workflow/processes` | `wf/pages/ProcessList/index.tsx` | 流程定义列表，可按名称搜索 |
| 流程设计 | `workflow/designer/:id` | `wf/pages/ProcessDesigner/index.tsx` | LogicFlow 画布编辑节点/审批人，保存设计稿并发布 |
| 流程详情 | `workflow/process/:processInstanceId` | `wf/pages/ProcessDetail/index.tsx` | 流程实例详情 + 审批历史 |
| 任务详情 | `workflow/task/:taskId` | `wf/pages/TaskDetail/index.tsx` | 任务详情 + 审批操作（同意 / 驳回 / 转交 + 审批意见） |
| 组件文档 | `docs` | `pages/ComponentDoc.tsx` | 本文件渲染页（左侧 240px TOC + 右侧正文） |

`src/pages/WorkflowApp.jsx` 是本包自己的兜底路由组件，被 `routes` 里的 `/z-wf/:rest/*` 承接，
同时从包根具名导出（z-team 的 SystemShell 按这个名字引它挂 `/workflow/*`）。

## 二、导出面

```js
// 主入口（. 桶）
import {WorkflowApp, ComponentDocPage} from '@yuku123/z-wf-component'

// 宿主组装面（./pages 桶）：manifest + 页面组件 + API
import {menuItems, routes, Dashboard, TodoList, ProcessList} from '@yuku123/z-wf-component/pages'
import {approvalApi, designerApi, configureWf} from '@yuku123/z-wf-component/pages'
```

`./pages` 桶 = `export * from './pages-manifest.jsx'` + API 再导出，**不要再手挑名字** ——
手挑过一次导致 manifest 新增的页面没被转发，rollup 直接报 `not exported by dist/pages.js`。

## 三、API 端点

请求实例由基座 `createRequest` 工厂创建（`src/wf/services/api.js`），`baseURL` 默认空串，
token 走 `Authorization: Bearer`（token 键 `zwf_token`），`unwrap` 把后端
`{code, success, data, message}` 解包成内层 `data`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/approval-center/dashboard` | 工作台统计（query `userId`） |
| GET | `/approval-center/tasks/todo` | 待办列表（query `userId` / `pageNum` / `pageSize`） |
| GET | `/approval-center/tasks/done` | 已办列表（同上） |
| GET | `/approval-center/tasks/get` | 任务详情（query `taskId`） |
| POST | `/approval-center/tasks/complete` | 完成任务（query `taskId` + body 审批意见） |
| GET | `/approval-center/my-processes` | 我的流程实例列表 |
| GET | `/approval-center/processes/get` | 流程实例详情（query `processInstanceId`） |
| POST | `/approval-center/processes/start` | 发起流程 |
| GET | `/approval-center/processes/definitions` | 流程定义列表 |
| DELETE | `/approval-center/processes` | 删除流程实例（query `processInstanceId`） |
| GET | `/approval-center/processes/model/graph` | 取流程设计稿（query `processKey`） |
| POST | `/approval-center/processes/model/graph` | 保存流程设计稿（body `processKey` + `graph`） |
| POST | `/approval-center/processes/model/deploy` | 发布设计稿为新的流程定义版本 |
| GET | `/wf/process/trail` | 审批轨迹（query `processInstanceId`） |

## 四、页面说明

### 4.1 工作台

统计卡片（待办 / 已办 / 我的流程）+ 最近任务列表。无待办时展示空态。

### 4.2 待办任务 / 已办任务

分页列表，空态文案分别是「暂无待办任务」「暂无已办任务」。

### 4.3 我的流程

我发起的流程实例分页列表，空态「暂无流程实例」。

### 4.4 流程定义

流程定义列表，支持按流程名称搜索（`placeholder="搜索流程名称"`），无数据时展示「当前没有流程定义」。

### 4.5 流程设计（LogicFlow）

画布编辑流程节点、设置审批人类型与审批人、填写节点说明；支持保存设计稿与发布。
发布前有二次确认弹窗（「部署后新的流程实例将使用此版本，确定部署吗？」）。

### 4.6 流程详情

展示流程实例详情与审批历史。审批历史数据由 `designerApi.getApprovalHistory` 做了字段归一化
（引擎的 `activityId/activityName/startTime/endTime/duration` → 页面的 `id/taskName/time/duration`）。

### 4.7 任务详情

展示任务详情并提供审批操作（同意 / 驳回 / 转交 + 审批意见）。转交支持按人选择。

### 4.8 组件文档

本文件。内容以 `?raw` 在编译期内联，零额外请求；左侧 240px TOC 随滚动联动。

## 五、配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `configureWf({apiBase})` | `''` | 改写请求实例 `baseURL`，宿主换挂载前缀时用 |
| token 存储键 | `zwf_token` | 与基座默认 `token` 不同，独立命名避免与其他套件串号 |
| LogicFlow 依赖 | `@logicflow/core` / `@logicflow/extension` ^2.0.0 | 设计器页使用，随包一起发布 |

## 六、已知限制

- `src/pages/*` 与 `src/wf/pages/*` 是两套几乎相同的页面副本：`pages-manifest.jsx` 引用的是
  `wf/pages/`（`src/pages/` 那套 import 走 `@/wf/services/api`）。**改动请落在 `wf/` 这一套**，
  否则 manifest 引不到。
- `src/index.ts` 不是构建入口（vite 的 lib entry 是 `src/index.js`），它对 `src/pages/` 的
  具名再导出**不会进 dist**。要对外暴露页面组件，走 `./pages` 桶。
- `getApprovalHistory` 的参数是**流程实例 id**，不是任务 id —— 传 taskId 会导致「审批历史恒空」。
- 审批轨迹端点不返回 `reject` / `terminate` 动作类型，页面只能按 activity 有无 `endTime`
  区分「已完成 / 进行中」，不编造审批动作。
- `deleteProcessInstance` 的后端签名是 `@RequestParam String processInstanceId`，
  早期发 `?id=` 会 400。
- 设计器保存与发布的路径挂在 `/approval-center/processes/model/**`，由 z-camuda 侧的
  `ProcessModelController` 提供 —— 只挂 z-wf 而不挂 z-camuda 时这两个端点 404。

## 七、变更记录

| 版本 | 变更 |
|---|---|
| 0.1.2 | 新增组件文档页 `ComponentDocPage`（lead 008 §7）：`src/DOC.md` + 基座 `ComponentDoc` 薄包装 |
| 0.1.2 | 补 `src/z-frontend-common-ui.d.ts` 与 `*.md?raw` 模块声明，供 TS 侧引用基座与文档内容 |
| 0.1.1 | 流程设计 API 从通用垫壳换成 `designerApi`（画稿存取 + 发布 + 审批轨迹） |
| 0.1.0 | 首版：流程中心页面集 + manifest + 自洽 request |
