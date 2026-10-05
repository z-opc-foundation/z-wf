请先读 [`CONTRIBUTING.md`](../../CONTRIBUTING.md)，尤其是"这个项目最看重什么"那一节。
留空也能提交，但填了会快很多。

## 变更内容

<!-- 改了什么。 -->

## 关联 issue

<!-- Closes #123 -->

## 检查清单

提交前请确认：

- [ ] 跑过 `mvn -o clean install`，全绿
- [ ] 新增的测试做过**反向验证**（摘掉修复确认它会变红）
- [ ] 没有引入静默降级——读不懂、猜不了、读不到的地方一律抛异常
- [ ] 注释解释的是"为什么"，不是"做了什么"
- [ ] 注释描述的行为**真的被实现了**（本项目有过注释描述了一个不存在的实现）
- [ ] 新增公开 API 有 javadoc，且说明了设计理由
- [ ] 改动流程定义解析器时，`WfNodeType` / `WfXmlParser.NODE_ELEMENTS` /
      `WfDefinitionValidator` 三处一起改了
- [ ] 依赖树里没有出现 camunda / flowable / activiti / jbpm / zeebe
- [ ] 能力有增减的话，同步更新了 `docs/capability-gap.md`
