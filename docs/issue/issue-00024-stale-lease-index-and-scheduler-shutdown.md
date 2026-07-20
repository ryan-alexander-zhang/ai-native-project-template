---
id: issue-00024-stale-lease-index-and-scheduler-shutdown
type: issue
role: main
status: resolved
parent: plan-00003-durable-process-manager-implementation
---

# 陈旧租约重认领无索引;调度器关停不升级到 shutdownNow

两处低危运维/性能健壮性问题,合并处置。

## 1. 陈旧租约重认领分支未被索引覆盖

- 认领候选查询的 `OR (status = 'IN_FLIGHT' AND lease_until <= ?)` 分支过滤 `lease_until`,但 `idx_process_effect_due`
  只覆盖 `(status, next_attempt_at)`;deadline 同理(`idx_process_deadline_due (status, next_attempt_at, due_at)`)。
  过期租约积压时该分支退化为扫全部 `IN_FLIGHT` 行。
- **修复**:两表各加 `(status, lease_until)` 索引(`idx_process_effect_lease` / `idx_process_deadline_lease`)——
  六份库内 DDL:PG/H2/两测试副本用独立 `CREATE INDEX IF NOT EXISTS`,MySQL 用表内 `KEY`;scaffold multi-module
  `start/schema.sql` 副本同步。

## 2. `ProcessWorkerScheduler.stop()` 超时后不中断卡住的 poll

- `ProcessWorkerScheduler.java:81-93`:`shutdown()` + `awaitTermination(...)` 超时后从不 `shutdownNow()`,卡住的 poll
  永不被中断,`stop()` 却当作已干净停止返回,优雅超时形同虚设(daemon 线程,爆炸半径有限)。
- **修复**:`awaitTermination` 返回 false(或被中断)时对该 executor 调用 `shutdownNow()`。

## 复现 / 验证

- 索引是覆盖性优化,由既有 PostgreSQL/MySQL Testcontainers 并发 gate 验证 DDL 生效且认领语义不变(索引不改结果集)。
- shutdownNow 属超时兜底路径,以代码审阅 + 既有调度器生命周期用例守护(`awaitTermination` false 分支明确升级)。
- 三模块 test 全绿(含 Testcontainers)。

## 关联

- [[plan-00003-durable-process-manager-implementation]]
