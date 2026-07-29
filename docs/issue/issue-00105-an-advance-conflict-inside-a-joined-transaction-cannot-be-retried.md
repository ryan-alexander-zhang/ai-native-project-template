---
id: issue-00105-an-advance-conflict-inside-a-joined-transaction-cannot-be-retried
type: issue
role: main
status: resolved
parent: report-00003-ddd-library-review-2026-07-29
---

# `withRetry` 在加入外层事务时必然失效；并发首次 start 又漏了唯一键映射

## 问题（现状，file:line 为证）

两个缺陷都落在「一次推进的并发语义」上，且互相加重。

### A. joined 事务下的重试循环不可能成功（Major）

- `DefaultProcessRuntime.start` / `handle` 的形状是
  `withRetry(() -> unitOfWork.execute(() -> doStart/doHandle(...)))`（旧 `DefaultProcessRuntime.java:249-264`），
  而 `SpringTxProcessUnitOfWork` 是 `PROPAGATION_REQUIRED`——**有外层事务就加入它**。
  这正是 javadoc 与 design-00004 §4.8 宣传的组合模式（command handler / Inbox listener 一个事务）。
- 加入之后第一次尝试抛 `StaleProcessRevisionException`：`TransactionTemplate` 对
  participating transaction 的回滚 = 把共享的物理事务标记 **rollback-only**。
- 于是重试循环里的第 2、3 次尝试再怎么跑，最终整体一定回滚；
  `ConcurrentTransitionException` 更糟——它来自真实的唯一键冲突，在 PostgreSQL 上事务已 aborted，
  后续语句直接报 `current transaction is aborted`。
- 净效果：**在框架推荐的组合方式下，`concurrency-max-retries` 完全无效**，且失败时抛出的往往是
  第 N 次尝试的新异常，把第一手原因盖掉了。

### B. 并发首次 start 漏 `DuplicateKeyException` 映射（Major）

- `JdbcProcessInstanceStore.insert`（旧 `JdbcProcessInstanceStore.java:92`）直接 `jdbc.update(...)`，
  不映射 `UNIQUE(tenant_id, process_type, business_key)` 冲突。
- 两个并发首次 start：输家的 `findByBusinessKey` 看不到赢家（尚未提交），于是走到 insert 并撞唯一键，
  抛出 **Spring 原生的 `DuplicateKeyException`**。
- `withRetry` 只捕 `StaleProcessRevisionException | ConcurrentTransitionException`，接不住它。
  于是 `start-duplicate-business-key` 承诺的 `reject`/`fold` 语义被整条绕过，
  消费方拿到一个持久化框架的异常。
- 同族对照：transition store **做了**这个映射（`JdbcProcessTransitionStore.append`），instance store 没做。
  两个 store 面对同一类冲突给出两种契约。

## 根因（第一性）

1. **A 的最小机制**：重试的前提是「失败只作废这一次尝试」。当事务不是我们的，
   这个前提不成立——失败作废的是**调用方的整个工作单元**。代码把「我拥有事务」与
   「我加入了事务」两种情形当成一种处理，而它们对重试的可行性给出相反答案。
2. **A 的正确姿态**：`REQUIRES_NEW` 不是答案——那会毁掉「推进与调用方的业务写入原子提交」
   这条更重要的保证（Inbox 去重行与推进必须同生同死）。答案是**保留 REQUIRED，但只在自己拥有事务时重试**；
   加入外层事务时让冲突上抛，由调用方回滚 + 传输层重投充当那次重试。
3. **B 的最小机制**：`ConcurrentTransitionException` 存在的意义就是「把存储特有的冲突翻译成
   引擎能处理的冲突」。少翻译一处，`withRetry` 就少接住一类，配置项的承诺就少兑现一次。
   映射之后 A 的修复顺带让 B 完整：重试重新读到已提交的实例，策略才有机会执行。

## 复现（test-first）

`JdbcProcessAdvanceContractTest` 三条，均确定性（用委托 store 精确注入一次冲突，不靠线程竞速）：

- `aRevisionConflictIsRetriedWhenTheAdvanceOwnsItsTransaction`：
  `updateSnapshot` 首次返回 0，断言第 2 次成功、共 2 次调用、step 推进到 `S2`。
- `aRevisionConflictInsideACallersTransactionPropagatesInsteadOfBeingRetried`：
  同一个 store，外层包一层 `TransactionTemplate`，断言抛 `StaleProcessRevisionException`
  且 `updateSnapshot` **只被调用一次**。
- `losingTheRaceOnTheBusinessKeyResolvesUnderTheDuplicatePolicy`：
  让首次 `findByBusinessKey` 看不见已存在的实例（即输家的视角），
  断言 REJECT 下抛 `ProcessAlreadyExistsException`、FOLD 下返回 duplicate 且实例仍只有一个。

## 修复

1. **`ProcessUnitOfWork` 新增 `inExistingTransaction()`**，Spring 实现读
   `TransactionSynchronizationManager.isActualTransactionActive()`（不是 `isSynchronizationActive`：
   只有真正绑定了资源的事务才会让下一次 `execute` 变成 participating）。
2. **`withRetry` 分流**：joined 时只尝试一次并让冲突上抛；拥有事务时保持原有的有界重试。
   两种情形的理由写在方法 javadoc 上，因为这是一处「看起来少了重试」的代码。
3. **两个 instance store 补映射**：JDBC 与 MyBatis-Plus 的 `insert` 都把 `DuplicateKeyException`
   翻成 `ConcurrentTransitionException`，消息里带上 tenant / processType / businessKey。

**未做的选择及原因**：没有把推进改成 `REQUIRES_NEW`（见根因 2，会牺牲与 Inbox 的原子性）；
没有在 joined 时静默降级为「不重试也不报错」（那会把一次冲突变成一次静默的 no-op）。

## 验证结果

- 库全量 `mvn verify`（47 模块，含 Testcontainers PG/MySQL 与全部质量门禁）：BUILD SUCCESS。
- 脚手架 `multi-module` 全量 `mvn verify`：BUILD SUCCESS。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（§2 Process Manager 两条 Major）
- 设计：[[design-00004-durable-process-manager-runtime]] §4.8 已同步
- 同批：[[issue-00103-parked-input-replay-is-not-crash-safe]]、
  [[issue-00104-an-ended-instance-keeps-its-timers-forever]]
