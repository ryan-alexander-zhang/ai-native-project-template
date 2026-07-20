---
id: issue-00034-mysql-deadline-cancel-self-referencing-subquery
type: issue
role: main
status: open
parent: plan-00003-durable-process-manager-implementation
---

# MySQL deadline cancel 的自引用子查询触发 ERROR 1093(cancel/schedule 路径无 MySQL 覆盖故全绿)

## 问题(现状,file:line 为证)

- **等级:High**(任何 MySQL 部署上处理 `CancelDeadline` effect 必现运行时报错)。
- `cancelCurrent()`(`JdbcProcessDeadlineStore.java:66-80`)执行:
  `UPDATE aipersimmon_process_deadline SET status = ?, updated_at = ? WHERE instance_id = ? AND name = ? AND status IN (?, ?) AND generation = (SELECT MAX(generation) FROM aipersimmon_process_deadline WHERE instance_id = ? AND name = ?)`。
  子查询读取的是**同一张**正在被 UPDATE 的表 `aipersimmon_process_deadline`,且**未**包裹进物化派生表(没有 `(SELECT g FROM (SELECT MAX(generation) g FROM ...) x)` 这层)。
- 这正是教科书级 MySQL **ERROR 1093 (HY000)** "You can't specify target table 'aipersimmon_process_deadline' for update in FROM clause"。MySQL 禁止在同一 UPDATE/DELETE 的子查询里读取被改动的目标表——与子查询自带 `WHERE instance_id = ? AND name = ?` 的作用域无关。H2 与 PostgreSQL 不强制此限制,同一语句在它们上运行正常。
- 同文件的 `cancelPending()`(`:230`)、`cancelClaimed()`(`:239`)只引用目标表本身、无子查询,不受影响。生产触达路径:`JdbcProcessRuntime.java:405` `case CancelDeadline cancel -> deadlines.cancelCurrent(...)`。
- **测试盲区**:cancel/schedule 路径只有 H2 覆盖——`JdbcProcessDeadlineWorkerTest`(`EmbeddedDatabaseType.H2` `:54`,dialect `"h2"` `:47`,cancel at `:170-175`),H2 不强制 1093 故全绿;全仓唯一的 MySQL Testcontainers 测试 `EffectRelayMysqlConcurrencyTest`(`mysql:8.0` `:54`)只覆盖 SKIP LOCKED 的 **effect-claim**,构造 `JdbcProcessDeadlineStore` 仅为满足 runtime 构造器(`:84`),**从不**调 `schedule()`/`cancelCurrent()`。全仓无第二个 `MySQLContainer`,`JdbcProcessDeadlineStore` 亦无 MySQL 专属 SQL override。

## 根因(第一性)

1. **观察 vs 期望**:期望"取消当前代 deadline 在所有受支持方言(h2/postgresql/mysql)上都能执行";实际"在 MySQL 上抛 ERROR 1093,cancel 失败"。
2. **最小机制**:`cancelCurrent` 用单条 `UPDATE ... WHERE generation = (SELECT MAX(generation) FROM 同表 ...)` 定位"当前代",而这种自引用子查询恰是 MySQL 明令禁止的形态。
3. **真根因**:该 SQL 未按方言差异化——H2/PG 能吞下的自引用 UPDATE 在 MySQL 上非法。绿灯是因为 cancel/schedule 只有 H2 单测,MySQL 容器测试刻意绕开了 deadline,二者叠加把这条路径的 MySQL 兼容性完全遮蔽。

## 复现(test-first)

提议测试(尚未落地):在 MySQL Testcontainers(`mysql:8.0`)下调度一个 deadline,再对同一实例/保留名调 `cancelCurrent()`;断言不抛 `SQLException`。今日该断言会因 ERROR 1093 失败。此测试同时补上"deadline schedule/cancel 的 MySQL 覆盖"这一现有缺口。

## 修复

将 cancel 变成 **dialect 操作**(提议,未实施),二选一:

1. MySQL 用 UPDATE JOIN 或**物化派生表**:`... AND generation = (SELECT g FROM (SELECT MAX(generation) g FROM aipersimmon_process_deadline WHERE instance_id = ? AND name = ?) x)`——派生表被物化后即绕开 1093。
2. 或先在实例锁保护下 `SELECT` 出当前代的 `deadlineId`,再按主键 `UPDATE`,彻底消除自引用子查询。

任一方案都应补一个覆盖 deadline schedule/cancel 的 MySQL Testcontainers 回归测试。

## 关联

- [[issue-00017-cancelled-deadline-can-still-fire]]
- [[process-manager-schema-copies]]
- [[plan-00003-durable-process-manager-implementation]]
