# 更新日志

本文件记录 z-wf 的重要变更。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [2.0.0] — 2026-10-05

首个自研版本。**与 1.x 无任何兼容性**：1.x 是基于 Camunda 的那一代实现。

### 坐标说明

`io.github.yuku123:z-wf` 的 `1.0.4` / `1.0.5` / `1.0.6` 已被 Camunda 版占用。
改名不释放已发布坐标，因此本仓沿用坐标、版本升到 `2.0.0`，
用破坏性大版本显式标出 1.x（Camunda 线）与 2.x（自研线）的边界。

### 新增

- **完全自研的运行时引擎**：14 种节点类型、10 个 behavior、
  token 执行树（enter/leave 严格分离、fork/join、汇合跳过快照里的自己、
  改动过的 token 重新落库、深度上限 512）
- **定义层**：BPMN XML 与 JSON 双解析器 + 校验器 + `zifang:*` 扩展命名空间
- **持久化抽象为 SPI**（25 个方法）：内存实现（Java 原生序列化深拷贝）
  与 JDBC 实现（6 张 `ZWF_*` 表 + 10 索引 + 乐观锁 CAS）
- **5 个 service** + 34 个 REST 端点（审批中心 13 / 任务操作 7 / 流程操作 9 / 分组 3 / 健康 1）
- **消息与信号唤醒**：`triggerMessage`（点对点，歧义时报错）与
  `broadcastSignal`（广播）
- 与 `z-util-wf-kernel` 的双向协议桥 `ZUtilWfBridge`
- 4 个模块：`z-wf-core` / `z-wf-web` / `z-wf-starter` / `z-wf-admin`
- 开源基座：LICENSE、CONTRIBUTING、SECURITY、CHANGELOG、CI 工作流、issue 模板
- 能力盘点文档 `docs/capability-gap.md`

### 修复

这一版的修复项全部来自测试与审计逼出的**真实缺陷**，不是打磨。
其中 4 项属于"能力看着在、实际不生效"：

- **未支持的 BPMN 元素静默退化成人工任务**。`eventBasedGateway` / `transaction` /
  `intermediateCatchEvent` / `adHocSubProcess` 一律退化为 `TASK`，
  校验器一条 issue 都不报，部署照过。作者写事件网关，跑出来的是"等人来点"。
  现在解析期仍宽松，但退化节点会带上原始元素名，校验器报 **ERROR** 挡住部署
- **`receiveTask` 从来没有等待过**。`WfNodeType#createsTask()` 漏了它，
  引擎把行为建好的任务**原样丢弃**，token 直接走到结束事件。
  流程"跑通了"、节点还留了 completed 记录，但什么都没等
- **未部署的定义可以启动流程**。实例建成、待办建出，看起来一切正常，
  但之后每推进一步都报"流程定义不存在"，且报错出现在完全不同的调用点上。
  现在启动即失败
- **`onBeforeCreate` 钩子从未被触发**。实现了、注册了、从来没调用过

其余包括：`/processes/search` 的 `total` 恒等于当前页条数；
`/process/executions` 直接返回持久化实体抖出 `arrivedActivities`/`variables`；
`POST /comment` 与 `GET /comments` 返回形状不一致；
委派链 `delegateChain` 从不落库（DDL 无列、UPDATE 不写、查询不读）；
z-util 版本覆盖失效；`WfHistoryService` 两个与既有路径逐字重复的方法。

### 变更

- 许可证由 Apache-2.0 改为 **MIT**，与 `z-boot-parent` 及 z-camuda 对齐
  （此前那个 Apache-2.0 是本仓早期脚手架的模板默认值，从未与组织统一过）
- 流程定义校验对未支持元素由静默放行改为 **ERROR 拒绝部署**（见上）

### 已知限制

见 [`docs/capability-gap.md`](docs/capability-gap.md)。最要紧的几条：

- **不支持会签/或签**（BPMN multiInstance）—— 审批系统的默认需求，当前只能手写多个节点
- **没有变量服务 API** —— 变量已持久化，但没有 Camunda 那套
  `getVariable`/`setVariable` 读写入口
- **没有 Job/定时器/异步执行** —— 超时提醒、超时升级、异步调用外部系统都做不了
- **没有 BPMN 错误事件** —— `serviceTask` 抛异常只能整体失败，走不了补偿分支
- **扩展面比 Camunda 窄**：3 个 hook 对比 Camunda 的几十个监听点

身份、表单、鉴权、CMMN **有意不做**，理由见能力盘点文档 §5。

---

[2.0.0]: https://github.com/yuku123/z-opc-foundation/tree/main/z-wf
