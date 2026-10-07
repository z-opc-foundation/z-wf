# lead —— z-wf 未完成事项交接单

本文件只记**没做完的事**，以及每一件已经查清的原因。
做完了的部分见 `CHANGELOG.md` 与 `docs/capability-gap.md`，不在这里重复。

最后更新：2026-10-07

---

## 0. 仓库状态（写这份文件时的实测）

```
分支      main
HEAD      6f2bc90  feat(wf): dataObject/dataStore/数据关联…（第 46 轮）
origin/main 落后 16 条（第 22–46 轮），本文件提交后一并推送
全量测试  1248 绿（core 1083 / web 41 / admin 124），BUILD SUCCESS
逐类核对  @Test 1248 / surefire 1248 / diff=0
工作区    第 47 轮代码未提交（见下），另有 pom.xml 的外来改动
```

**⚠️ 第 47 轮的代码在工作区里、没有提交**，因为它的反向验证没跑完
（见 §1）。提交它之前必须先把 §1 处理掉。

`pom.xml` 有一处**外来改动**（删了 `swagger-annotations` 依赖与几处空行），
不是本项目任何一轮产生的，一直保持原样未提交，归原作者。

---

## 1. 第 47 轮：部署历史视图 —— **未完成，卡在反向验证**

### 1.1 已经做完并验证过的

| 项 | 状态 |
|---|---|
| `WfDeploymentEntry`（投影，不取 `DEF_GRAPH`/`SOURCE_XML`） | 已实现 |
| `WfDeploymentOrder`（四种排序，无"不排序"） | 已实现 |
| `WfDeploymentQuery`（9 个条件 + 排序 + 分页 + 区间倒置报错） | 已实现 |
| `WfDeploymentQueryService`（**唯一一份**过滤/排序/分页实现） | 已实现 |
| `WfPersistence#findDeploymentEntries()` + 内存 / JDBC 两套实现 | 已实现 |
| `WfRepositoryService#queryDeployments` / `#countDeployments` | 已实现 |
| REST `GET /api/wf/definitions/history`（分页信封） | 已实现 |
| core 判据 `WfDeploymentQueryTest` 12 条 | 全绿 |
| admin 判据 `WfDeploymentHistoryJdbcTest` 8 条 | 全绿 |
| 全量 1248 绿、逐类零差异 | 已实测 |

**设计要点**（已完成，不要重做）：
- 过滤/排序/分页**全部收在 `WfDeploymentQueryService` 一份实现**，
  两套持久化只负责"把行读成投影"、**一行过滤逻辑都没有** ——
  结构上杜绝第 45 轮那类"内存查得到、JDBC 查不到"的漂移。
- `hasSourceXml` 用 `CASE WHEN` 在库里算，**不把 CLOB 拉进内存**。
- 排序**永远追加 tiebreaker**（key 升序 + 版本倒序），同毫秒靠它收口。
- 时间区间**闭**；`deployTime` 为 null **不匹配任何时间窗**，且**排序时恒排最后**。

### 1.2 没做完的：反向验证 21 条里 5 条没红

首跑 16/21 红。5 条逐条查下来，**成因分三类**，全部已定位：

| 编号 | 现象 | 已定位的原因 | 待办 |
|---|---|---|---|
| **M09** | GREEN | **判据本身的结构缺陷**：`assertSameAcrossImpls` 只断言「内存与 JDBC 给同一集合」。`key` 精确条件住在**两边共享的 service** 里，删掉它对两边影响完全相同 ⇒ 两边照样一致。**跨实现一致性判据，原理上抓不到共享代码里的缺陷。** | 补**显式期望值**断言：`setKey("leave")` 必须恰好是 `[leave:1, leave:2]`，而不是只断言两边相等 |
| **M05** | GREEN | 内存侧 `hasSourceXml` 去掉 `!trim().isEmpty()` 的一半，而**夹具里没有"只有空白的 sourceXml"这种数据** ⇒ 变异是空操作 | 往夹具补一条 `sourceXml = "   "` 的定义 |
| **M11** | GREEN | 时间上界放宽 1 毫秒，而**夹具里没有任何数据恰好落在界外 1 毫秒处** | 补一条部署时间恰在 `+1ms` 的数据 |
| **M14** | GREEN | **已手工复现确认**：只有 2 条数据时，`compareTime` 的「left 为 null」与「right 为 null」两个分支**不可分辨** —— 两元素排序只会走到其中一种调用方向，翻转单侧是语义空操作 | 补一条让 **null 处在序列中间**的数据（≥3 条），或改成同时变异两个分支 |
| **M06** | COMPILE-FAIL | **变异本身写错了**：`java.util.Collections.singletonList(...)` 返回 `List`，没有 `.values()` | 改写成对 `byKey.getValue()` 取 `values()` 的合法替换 |

**⇒ 第 47 轮的收尾清单**（做完这五件才能提交）：
1. M09：给每个条件补显式期望值断言（**最重要**，它是判据体系的结构问题）
2. M05 / M11 / M14：补三类缺失的夹具数据形状
3. M06：改写成能编译的变异
4. 重跑 harness，目标是 **21/21**
5. 全量绿 + 逐类零差异 + 写台账第 47 轮小节 + 提交

### 1.3 本轮顺带做的台账订正（已在工作区，未提交）

- **第 47 轮小节尚未写入** `docs/capability-gap.md`（第 165 / 47 / 40 行三处**表格行**已改，小节没写）
- **订正了 `adHocSubProcess` 那一行的事实错误**（见 §2）
- 连带改了 `WfDefinitionValidator#substitutionHint`：
  删掉了「请改用 `<subProcess>`」这条**已被证伪的建议**，
  换成说清为什么不等的价（`activeElementsCollection` 内部不得有 start/end event，
  而本仓内联 `subProcess` 要求恰好一个内联结束节点）。
  对应判据 `UnsupportedBpmnElementTest#adHocSubProcessMustNotSuggestSubProcess` 已改写。

---

## 2. 已查清但**不需要做**的（避免下一轮重新怀疑）

### 2.1 `adHocSubProcess`：台账原文有一处事实错误，已订正

原文写「Camunda 把它当普通 subProcess 处理」——**这句是错的**。查证结果：

1. **Camunda 7 根本不支持它**（社区结论原话：
   "camunda BPM currently does not support the BPMN 2.0 AdHocSubProcess"；
   `BpmnParse#parseSubProcess` 只处理 `subProcess` 元素）
2. **Camunda 8 有一整套独立语义**：`activeElementsCollection` 表达式 + job worker 驱动，
   内层元素**任意顺序 / 可跳过 / 可重复**
3. Camunda 8 文档明写 ad-hoc 子流程 **"must not have start events or end events"**，
   而本仓内联 `subProcess` 要求**恰好一个内联结束节点**

⇒ **直接映射成 `SUB_PROCESS` 是错的**：作者会撞上「结束点必须唯一」，
把一条看得懂的拒绝换成一条更费解的拒绝。
⇒ **保持拒绝是对的**，但理由要改成真的那个（已改代码与台账）。
⇒ 这是第 38 轮教训的**第三种形态**：
**那一轮订正了「不该照抄元素名」，但顺手给出的替代方案从未被验证过。**

### 2.2 `deploy()` 覆盖 `startTime` 是对的，不要改

`WfRepositoryService#deploy` 落库前一定执行 `definition.setStartTime(new Date())`，
调用方预设的部署时间**一定被忽略**。

**这是对的**：部署时间记的是"它什么时候被部署的"，
让调用方能指定 = 允许把一条部署记录伪造成三个月前的，
而部署历史正是用来回答「上周到底改了什么」的地方。
⇒ **写夹具时不要试图控制它**，读 `deploy()` 的返回值即可。

---

## 3. 能力缺口（`docs/capability-gap.md` 剩余项）

按台账当前状态：

| 项 | 状态 | 说明 |
|---|---|---|
| `createDeploymentQuery` | 🟡 | 第 47 轮补了"查得到"，仍缺：一次 deploy 多条定义的**部署包**语义（本仓 `deployXml` 一次只产出一条定义）、`DELETE /deployment` 按包级联删、`getResources()` 资源列表（现只有 `hasSourceXml` 一个布尔） |
| `adHocSubProcess` | ❌ | **保持拒绝是对的**（见 §2.1），理由已订正 |
| `IdentityService` / `FormService` / `AuthorizationService` / `CaseService` | ⛔ | §5「有意排除」，**不是待办**。注意 `WfCapabilityDocConsistencyTest` 守着「同一个服务不得既标已实现又列排除」这条 |

**下一轮的建议入口**：先从台账里找 **🟡 但没有小节说明的**行，
以及那四个 ⛔ 之外**尚未评估过**的能力。

---

## 4. 本项目沉淀的通用纪律（下一轮开工前值得读一遍）

这些是 22–47 轮反复用到的，**不是本轮新增**，但每次新写判据都会撞上：

1. **判据两处覆盖，且两处覆盖的不是同一批**（core 单测 + admin `@SpringBootTest` 端到端）
2. **两套持久化实现必须同时改** —— 少一边 = 「开发期查得到、线上查不到」，**内存模式下完全不可见**
3. **判据里混进无关噪声后，「恰好 N 条」这种断言会失效** —— 夹具里的裸 `<serviceTask>` 会触发既有的
   「需要 delegateClass」规则，每条断言都多出一条与当轮无关的 ERROR
4. **参数写在那里却不起作用，是最难查的一类夹具缺陷**（§2.2）
5. **跨实现一致性判据抓不到共享代码里的缺陷**（§1.2 的 M09）——
   条件住在共享 service 时，"两边一致"和"两边都对"是两件事
6. **给枚举/布尔/状态字段写往返判据前，先确认夹具里至少有两个值**
7. **边界判据必须成对写，且数据里必须有一条恰好落在边界上**
8. **时间列 null 一律不匹配**；区间一律闭；倒置当场报错不返回空集
9. **纯声明元素上的脏属性当场抛**（不像有真实默认值的字段那样"保留默认值继续"）
10. **注释描述的代码路径必须真的存在** —— 写了一段永远走不到的 null 分支，
    注释却在解释它为什么对
11. **`mvn ... | tail && echo 成功` 会骗人**（退出码被管道吃掉）⇒ 用 `set -o pipefail` 或看 `BUILD` 行
12. **测试 helper 取数值一律 `longValue()`**，毫秒时间戳约 1.79e12，`intValue()` 会静默截断
13. **写 Java 时中文短语引用用 `「」`，禁用 ASCII 双引号**（否则截断字符串定界符）
14. **commit message 用 `write` 落 `/tmp/*.txt` → 扫 U+FFFD → `git commit -F`**
15. **harness 七条**：pristine 快照 / 每条命令带 `clean` / 从 surefire XML 读且用
    `testsuite` 属性交叉验证（`PARSE-MISS` 不计入结论）/ 锚点唯一 + 回读自检内容变化 /
    **变异所在模块 ≠ 测试层** / install 与还原**各一次** / INCONCLUSIVE 打印真实 argv
16. **锚点必须覆盖完整语句，替换体必须能编译** ——
    `if (false) { throw ... }` 会被 javac 判 `unreachable statement`；
    正确做法是整块替换 catch/if，而不是插一个编译期就能折叠的假条件
17. **变异可能语义上是空操作**（如 `WHERE a IS NOT NULL AND a<=?` 改成 `WHERE a<=?`
    —— SQL 三值逻辑下 `NULL <= ?` 是 UNKNOWN，结果完全一样）

---

## 5. 交付纪律（每次提交前照着走）

- [ ] `mvn -o clean install` 全绿，并**记住总条数**
- [ ] `python3 /tmp/verify_tests.py` 逐类 `@Test` vs surefire **diff=0**
- [ ] 反向验证 harness **全部红**；每条未红都要查清是"判据不敏感"还是"变异写错了"，
      **并把结论写进本文件**
- [ ] 台账对应行 + 本轮小节都更新
- [ ] `pom.xml` 的外来改动**不提交**
- [ ] commit message 落 `/tmp/*.txt`，**扫 U+FFFD** 再 `git commit -F`
- [ ] 推送前确认分支与领先条数
