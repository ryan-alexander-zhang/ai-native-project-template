---
id: issue-00157-core-oddments-from-the-2026-08-02-review
type: issue
role: main
status: resolved
---

# 2026-08-02 评审的库核心 P2（伞形清单）

单独立 issue 太碎、又不该丢的 7 项。每项独立可做，做完划掉；膨胀则拆独立 issue。

- [x] **嵌套 dispatch 破坏"一命令一事务"契约**：`TransactionTemplateUnitOfWork.java:22`
  默认 `TransactionTemplate`（REQUIRED），handler 经 `commandBus.send` 组合命令
  （`CqrsRules.java:36` 明确背书的模式）会加入外层事务。issue-00151 修好后叠加更糟：内层
  冲突把共享事务标记 rollback-only，内层重试在注定失败的事务里"成功"，外层 commit 抛
  `UnexpectedRollbackException`。至少在 `RegistryCommandBus` 与 UoW 的 javadoc 写明嵌套
  语义；或让嵌套传播成为显式选择。
- [x] **并发 create 冲突逃出并发词汇表**：insert 路径（`JdbcAggregateRepository.java:59-60`、
  `MybatisPlusAggregateRepository.java:87-89`）的重复键浮出为 `DuplicateKeyException`，
  `ConcurrencyTranslationCommandInterceptor` 不翻译——同一自然键并发两次 create 得到
  500 形状的 `DataIntegrityViolationException` 而非稳定的 CONFLICT 类别错误。翻译成
  conflict（create 不重试是对的，但错误类别要稳定）。
- [x] **handler 注册的启动期严格性弱于 precheck**：`PrecheckCommandInterceptor.commandTypeOf`
  （:93-111）拒绝擦除到 bound 的类型参数并注释了静默失配危害；
  `RegistryCommandBus.commandTypeOf`（:173-183）只查 null——泛型解析到接口/抽象基类的
  handler 注册"成功"，每次真实派发报误导性的 "No command handler registered"。对齐严格性。
- [x] **rehydration 忘 `restoreVersion` 的失败症状离故障点太远**：version-0 聚合走 insert
  分支、死于与真实错误无关的重复键。在重复键 + version==0 的路径上给出指名
  `restoreVersion` 的报错提示。
- [x] **`JdbcAggregateRepository.insert` 返回值声明了但没人看**（契约 :100-104 vs
  `saveAggregate` :59-60）：子类用 `INSERT ... ON CONFLICT DO NOTHING` 会静默"保存"零行
  并照常发事件。检查返回值，0 行时抛。
- [x] **值对象不可变规则的 javadoc 措辞过强**：`BuildingBlockRules.java:99-110` 只查
  `haveOnlyFinalFields`，`final List` 外部可变照样通过；javadoc 却说 "cannot be mutated
  after construction"。措辞降到规则实际检查的范围，指向 record + `List.copyOf` 惯例。
- [x] **`versionAdvanced()` 的防线依赖消费方采纳 ArchUnit**：运行时锁只对跑了
  `AiPersimmonDddRules.all()` 的消费方成立（`BuildingBlockRules.java:122-135`）。README
  把"挂上 archunit 规则"写成硬性采纳前提，不是可选建议。

**在案不做**：`DomainEvent` 保持裸标记（集成事件已有 CloudEvents 全套，域内事件的元数据
留给消费方——设计口味，非缺陷）；`cqrs`→`tenancy` 编译依赖（N=1 租户论证成立，已是不可
移除的 API 面，仅提醒）。
