---
id: issue-00107-silent-degradations-become-loud-failures
type: issue
status: resolved
blocks: [report-00003-ddd-library-review-2026-07-29]
---

# 四处「保证蒸发但什么都不报」：无事务管理器、两个仓储基类无事务、乐观锁静默消失、outbox 行独立提交

## 问题（现状，file:line 为证）

评审的第二个系统性主题是**静默降级**：「文档承诺 A、代码行为 B、且无任何日志」。四处同族缺陷，
共同点是**招牌保证消失时，可观测面上什么都不变**——测试照样绿、日志照样静、编译照样过。

### A. 无 `PlatformTransactionManager` → 每条命令裸跑（Major）

- `AipersimmonDddCqrsAutoConfiguration:41,59` 的 `UnitOfWork` 与 `TransactionCommandInterceptor`
  都挂 `@ConditionalOnBean(PlatformTransactionManager.class)`。
- 没有事务管理器时两个 bean **都不存在**，命令逐条裸跑：聚合写、outbox 行、领域事件各自提交。
- starter 的招牌保证是「一条命令 = 一个事务」。它蒸发了，而**没有一行输出**说过这件事。
  依赖它写的消费方代码照样编译、照样通过测试；变的只是「部分失败会留下部分状态」。
- 反差：同一份代码对缺失 `IdGenerator` 是**启动即失败**的（`issue-00053`）。同一个框架，两种风险姿态。

### B. 两个仓储基类不检查事务（Major，A 的下游）

- `JdbcAggregateRepository.saveAggregate` / `MybatisPlusAggregateRepository.saveAggregate`
  的 javadoc 都写着「Runs in the caller's transaction」，但没有任何一处校验它。
- 无事务时：MyBatis-Plus 版依次提交根行、子表、事件三次；JDBC 版提交行与事件两次。
  中途失败留下半个聚合 + 已发出的事件（或反之），且没有东西可回滚。

### C. 消费方自带 `MybatisPlusInterceptor` → 乐观锁整体消失（Major）

- MyBatis-Plus 只认一个 `MybatisPlusInterceptor` bean；消费方声明自己那一个时，框架的组合器
  **整体退让**（`design-00011` §3 明写这是有意的逃生舱）。
- 但退让之后 `OptimisticLockerInnerInterceptor` 不在，`@Version` 不产生 `WHERE version = ?`，
  `updateById` 恒返回 1 → `saveAggregate` 的受影响行数检查通过 → **陈旧快照覆盖并发修改，全程无错**。
- 唯一的补救是启动日志列出实际安装的拦截器——**而那行日志恰好印在会退让的那个 bean 里**，
  所以真正发生时它也不打印。
- `InnerInterceptorCompositionTest.aConsumerOwnedInterceptorWinsWholesale` 早已把这个失败模式**测出来**，
  却只是把它当作「已知代价」记录，仍然发货。

### D. `OutboxWriter` 不检查事务（Major）

- 两个 `OutboxWriter.write(...)`（jdbc 与 mybatis-plus）直接 `INSERT`，不校验事务。
- outbox 存在的理由**只有一条**：事件与引起它的状态变更一起提交。无事务时这行立刻独立提交，
  随后调用方的工作失败或被上层回滚 → relay 忠实地投递一个**宣告从未发生过的变更**的事件。
- 下游无从分辨，全程无错，而等有人去看时那行已经离开 outbox 了。这比没有 outbox 更糟。

## 根因（第一性）

1. **共同机制**：`@ConditionalOnBean` / `@ConditionalOnMissingBean` 是**装配**语言，
   它表达「有则用之」，不表达「没有意味着什么」。当缺失的那个东西承载的是一条**正确性保证**时，
   「静默不装」就等于「静默取消保证」。缺的是一处**明确表态**的地方。
2. **为什么受影响行数检查救不了 C**：它检查的是「有没有匹配到行」。谓词缺失时**必然**匹配到行——
   检查恰恰因为缺陷存在而通过。要检测缺陷必须找一个**独立的目击者**，而不是加强同一个观察。
3. **为什么这四处都无法靠测试发现**：它们在正常路径上行为完全正确。B 和 D 只在「中途失败」时分叉，
   C 只在「真有并发」时分叉，A 三者皆是。单元测试构造不出这些分叉，也就永远是绿的。
4. **排除的伪根因**：不是文档写错了（四处的 javadoc 都写对了期望），是**期望没有被强制**。

## 复现（test-first）

- `CommandTransactionGuardTest`（新增，3 例）：无事务管理器 → 启动失败于
  `MissingTransactionManagerException` 且消息含逃生舱键名；显式 `required=false` → 启动成功，
  并断言 `UnitOfWork` 与 `TransactionCommandInterceptor` **确实不在**（保证真的没了，这正是要被说出口的事）；
  有事务管理器 → 两者都在。
- `JdbcAggregateRepositoryTest.writesOutsideATransactionAreRefused`、
  `MybatisPlusAggregateRepositoryTest.writesOutsideATransactionAreRefused`：断言拒绝，且**什么都没写、什么都没发**。
- `MybatisPlusAggregateRepositoryTest.anUpdateWhoseVersionWasNotCheckedIsRefusedRatherThanTrusted`：
  mock 的 update 返回 1 但不推进版本——即缺少拦截器 / 漏标 `@Version` 的形状——断言拒绝且不发事件、不写子表。
- 两个 outbox 模块与 `ConnectedTraceEndToEndTest` 的既有用例：**原本在事务外发布**，
  现在必须包一层事务才通过。这不是测试的让步，是把它们改成生产唯一允许的形状。

## 修复

1. **A**：新增 `CommandTransactionGuard`（`InitializingBean`）+ `MissingTransactionManagerException`
   + `MissingTransactionManagerFailureAnalyzer`（经 `META-INF/spring.factories`，给出可执行的启动报告）
   + `AipersimmonDddCqrsProperties.transaction.required`（默认 `true`）。
   关掉它是**合法的部署形态**（只读服务、handler 不碰库），所以是配置项而非硬性要求——
   但必须被选择，且每次启动 WARN 一次，免得它变成某个日后长出数据库的服务的默认状态。
2. **B**：两个 `saveAggregate` 首行加 `TransactionSynchronizationManager.isActualTransactionActive()` 断言。
   消息直接给出两条出路（走 CommandBus / 给应用服务加 `@Transactional`）。
3. **C**：`requireVersionWasChecked` —— `updateById` 成功后断言 `row.getVersion() == expected + 1`。
   依据是拦截器留下的目击者：它改写语句之后会把自增后的版本**写回实体**
   （`OptimisticLockerInnerInterceptor` 内 `versionField.set(et, updatedVersionVal)`）。
   顺带覆盖第二条静默路径：行对象漏标 `@Version`。
4. **D**：两个 `OutboxWriter.write` 加同样的事务断言。

**未做的选择及原因**：没有为四处抽一个共享的 `ActiveTransaction` 工具类——四个模块没有一个共同的、
允许携带 Spring 的上游模块（`application` 是经字节码规则验证的纯净层），而为此新增模块与
「47 个模块已过度碎片化」相悖；四处各自内联，消息也因此能说清**该处**具体丢的是什么原子性
（CPD 门禁阈值 250 token，远够不上）。没有把 C 改成「框架强行把自己的 inner interceptor 合并进消费方的
interceptor」——那会让「自定义」名不副实，逃生舱的语义保持不变，只是它的代价现在会响。

## 验证结果

- 库全量 `mvn verify`（47 模块，含 Testcontainers PG/MySQL 与全部质量门禁）：BUILD SUCCESS。
- 脚手架 `multi-module` 全量 `mvn verify`：BUILD SUCCESS，**且一行业务代码未改**——
  参考应用本来就在事务内写、本来就装着拦截器。这既是「断言不误伤正确用法」的证据，
  也是 C 的**端到端验证**：`ConcurrentApprovalTest` 用真实 HTTP + 真实数据库跑并发审批，
  若真实拦截器不写回版本，每一次审批都会撞上新断言。
- 顺带修正了一处测试脆弱性：`MybatisPlusAggregateRepositoryTest` 原先从 Mockito captor 事后读版本，
  而 captor 持有的是引用而非快照；现在在 answer 内读取。

## 关联

- 父：[report-00003-ddd-library-review-2026-07-29](../report/report-00003-ddd-library-review-2026-07-29.md)（§0 系统性主题 2、§3 第 5 项）
- 设计：[design-00011-aggregate-persistence-contract](../design/design-00011-aggregate-persistence-contract.md) 开篇与 §3 已同步
- 先例：[issue-00053-id-generator-silently-degrades-to-uuidv4](issue-00053-id-generator-silently-degrades-to-uuidv4.md)（缺失 `IdGenerator` 即启动失败，本批照它办）
- 同族：[issue-00051-aggregates-have-no-optimistic-locking](issue-00051-aggregates-have-no-optimistic-locking.md)（能力静默缺席的第一例）、
  [issue-00106-an-empty-flyway-component-list-created-every-table](issue-00106-an-empty-flyway-component-list-created-every-table.md)（同一主题里需要用户定契约的那条）
