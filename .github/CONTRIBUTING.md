# 贡献指南

感谢你愿意为 z-wf 花时间。这份文档写的是**这个项目特有的规矩**，
通用开源礼仪请自行发挥。

---

## 这个项目最看重什么

一句话：**引擎宁可停下报错，也不能猜着跑。**

审批引擎的错误代价不对称——流程静默走错一条分支，业务上可能几周后
才被发现，而那时已经产生了对外可见的错误决策。所以下面几条规则
都比"让它跑起来"优先。

### 1. 永远不要静默降级

发现不了、猜不了、读不到的时候，**抛异常**。

反面例子（本项目真实踩过的）：

- 解析器把不认识的 BPMN 元素退化成 `TASK` → 事件网关变成"等人来点"，无任何提示
- `WfReceiveTaskBehavior` 建好的任务被引擎丢弃 → 流程"跑通了"，但什么都没等
- 条件表达式引用未定义变量时把 `null` 当 `0` 参与比较 → 金额未知被当成金额 0，
  本该走大额审批的单据悄悄走了低额分支

正面的处理方式：

```java
// 不这样
if (value == null) { value = 0; }

// 而这样
if (!isDefined(expression, "amount")) {
    log.warn("条件引用了未定义变量 amount，按条件不成立处理，转 defaultFlow");
    return false;
}
```

### 2. 注释写"为什么"，不写"是什么"

```java
// 无用
// 遍历任务列表
for (WfTask task : tasks) { ... }

// 有用
// category 是复合字段：receiveTask 把 messageName 存在这里，
// 而人工任务也用它做审批分类。所以唤醒时必须再按 type 过滤一遍，
// 否则一批在等人的普通任务会被消息一起带走。
if (WfNodeType.RECEIVE_TASK.bpmnName().equals(task.getType())) { ... }
```

判断标准：删掉这行注释，代码是否变难懂？不变难懂 ⇒ 注释是废话。

### 3. 注释不许撒谎

本项目的注释里有过一句"记录在 properties 里，便于诊断"——而那个记录动作
**从来没被写出来**。注释描述了一个不存在的实现，读代码的人会以为诊断信息可用，
于是不会再去查。

写注释时按"这段代码真的这么做"来写。写完回头核对一遍。

### 4. 测试要能证明它自己有效

新增测试后，做一次**反向验证**：把修复临时摘掉，确认测试由绿转红。

```bash
# 1. 正常跑，全绿
mvn -o -pl z-wf-core test -Dtest=YourTest

# 2. 临时改坏（把 ERROR 降成 WARN、把条件取反……），重跑，必须变红
# 3. 改回来，确认又变绿
```

没有第 2 步的测试可能是空转断言——它在你摘掉修复之后依然会绿，
说明它压根没测到那个东西。

### 5. 缺陷要写清"怎么被发现的"

commit message 里说明探针/测试是怎么暴露它的。写清楚能防止同类问题复发时
没人认得出来。

---

## 构建与测试

```bash
# JDK 8，离线构建
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
mvn -o clean install

# 单个测试类
mvn -o -pl z-wf-core test -Dtest=WfEngineEndToEndTest

# 反向验证单个用例
mvn -o -pl z-wf-core test -Dtest='YourTest#yourCase'
```

**注意 `-am`**：跑 web/admin 模块的测试时必须带 `-am`，
否则会用到本地仓库里的旧 jar，测的不是你刚改的代码。

```bash
mvn -o -pl z-wf-admin -am test -Dtest=WfWebApiTest
```

---

## 代码约定

- 语言：Java 8 编译口径（`maven.compiler.source/target=8`）
- 注释：中文
- 缩进：4 空格，不混用 tab
- import：不使用通配符
- 新增公开 API：必须有 javadoc，且要说明**为什么这样设计**而不只是参数含义

### 本仓特有的几条

- **持久化形状 ≠ 运行时对象**。落库走 `WfDefinitionCodec` 转换，
  不要让持久化实体直接当运行时对象用
- **service 是单例且多线程共享**。任何"本次请求的临时状态"必须放方法局部变量，
  放字段里会让 A 请求的数据混进 B 请求
- **完成判定放 service 层、基于存储层真实状态**。引擎看不见别的请求创建的
  token 与任务，而那正是多节点部署的常态
- **鉴权不在本层**。`force-complete` / `jump` 不做办理人校验是刻意的，
  权限判断属于 z-ctc

---

## 提交信息

用中文，遵循 Conventional Commits 前缀：

```
feat(z-wf): 支持多实例会签
fix(z-wf): 委派链从不落库
refactor(z-wf): 收敛条件表达式求值逻辑
docs(z-wf): 补能力盘点文档
test(z-wf): 乐观锁 409 真链路验证
```

`fix` 的正文请包含：

1. **探针/测试实测到了什么**（贴关键输出，别只写结论）
2. **为什么这是缺陷**（后果是什么，不只是"行为不符预期"）
3. **为什么这样修**，尤其是"为什么不用更简单的办法"

---

## 改流程定义解析器时特别注意

新增 BPMN 元素支持时，三处要一起改，缺一处就会退化成静默错误：

| 位置 | 作用 |
|---|---|
| `WfNodeType` | 加枚举值 + `createsTask()` / `isGateway()` 判定 |
| `WfXmlParser.NODE_ELEMENTS` | 加元素名映射 |
| `WfDefinitionValidator` | 加配置校验规则 |

漏掉第 3 处 ⇒ 配错了也部署得进去。
漏掉第 1 处的 `createsTask()` ⇒ 行为建的任务被丢弃（`receiveTask` 就这样坏过）。
