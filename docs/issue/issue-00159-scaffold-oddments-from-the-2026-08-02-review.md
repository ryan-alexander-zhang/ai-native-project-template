---
id: issue-00159-scaffold-oddments-from-the-2026-08-02-review
type: issue
role: main
status: open
---

# 2026-08-02 评审的 scaffold P2（伞形清单）

每项独立可做，做完划掉。

- [ ] **`Stock.release` 在聚合处无上界**（`Stock.java:57-62`）：只有 `available`，任意正数
  都加得回去；"只有预留过的才回来"的不变式整个活在 `Reservation` + `ReleaseStockHandler`。
  边界选择是可辩护的（reserved 计数会把 Stock 耦到预留概念上），但在"聚合不信任调用方"
  主题的评审轮之后，这是唯一一处域内 bug 能静默注水的变更。二选一：加 `reserved` 计数守卫，
  或在 `Stock` javadoc 声明"守卫刻意在别处"的立场与理由。
- [ ] **OpenAPI 注解落在 application 模块，未声明取舍**：`ordering-application/pom.xml:68-69`
  依赖 `swagger-annotations-jakarta`，`OrderSnapshot` 带 `@Schema` 展示文案。springdoc 的
  务实选择，但 scaffold 其余处处讲层纯净，唯独这里静默——照抄者学到"传输文档可以漏进
  application"。按其他取舍的惯例写一段注释（为什么不在 adapter 层套 DTO：一层纯转发映射
  的成本 vs 一行注解的泄漏，选了后者）。
- [ ] **232 处悬空 `issue-000xx` 引用（133 文件）**：生成的工程里这些 ID 什么都解析不到
  （`DOCS.md` 只映射外部文档），读起来是出处噪音。库树已按同一约定清过并有门禁
  （issue-00149 的 `LibraryCommentsAreSelfContainedTest`）；scaffold 树照做——清理 +
  把门禁的扫描范围扩到 scaffold 源码，注释重写为自含（保留推理，去掉 ID）。
- [ ] **`OrderFulfilmentStarter` 的事务耦合没写进 javadoc**：同步 `@EventListener` 在下单
  事务内启动持久流程，正确性依赖库的 drain-on-save 时序；换成异步应用事件会静默破坏
  "事实先于流程"。javadoc 现在只解释了层的摆放，补上这层耦合与"为什么必须同步"。
