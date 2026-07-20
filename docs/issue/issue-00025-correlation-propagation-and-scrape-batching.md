---
id: issue-00025-correlation-propagation-and-scrape-batching
type: issue
role: main
status: resolved
parent: plan-00003-durable-process-manager-implementation
---

# 跨 deadline/replay 的相关性链断裂;健康探针/指标未批量取样

两处低危、非正确性的可观测性/性能缺陷,一并落地。

## 1. 相关性链在 deadline 触发与 parked 重放处断裂

- **现状**:`deadline/JdbcProcessDeadlineWorker` 触发用 `CommandContext.root(deadlineId#gen, null)` 开新 correlation;
  `operation/JdbcProcessOperations.replayParkedInputs` 用 `new CommandContext(replayId, replayId, …)` 亦然。跨这两处后,
  下游 command/event 与原 saga 流的 `correlationId` 断开(纯可观测性,不影响幂等/正确性)。
- **根因(第一性)**:触发/重放所需的因果上下文在调度/停车时未被持久化,只能现造一个新的。
- **修复**:
  - deadline 表增 `correlation_id`/`causation_id`/`trace_id` 列;`JdbcProcessRuntime.scheduleDeadline` 写入触发它的
    `cause` 的 correlation/messageId(作 causation)/trace;worker 触发时用持久化的上下文构造 `CommandContext`
    (不再 `root`)。`armMaxLifetimeBackstop` 也贯穿 start 的 `cause`。
  - transition 表增 `correlation_id`/`trace_id` 列;`appendTransition` 与挂起 park 分支写入 `cause` 的 correlation/trace;
    `ParkedInput`/`findParkedInputs` 带出它们;`replayParkedInputs` 用停车时的 correlation/trace 重放
    (对早于本改动、correlation 为 null 的旧停车行回退到 replayId)。
- **复现(test-first)**:`JdbcProcessDeadlineWorkerTest#aFiredDeadlineKeepsTheCorrelationOfTheFlowThatArmedIt`
  (fire 后的 `deadline-fired` transition 的 correlation/trace = 武装它的流);
  `JdbcProcessOperationsTest#aReplayedParkedInputKeepsItsOriginalCorrelation`(重放 transition 保留停车输入的 correlation/trace)。

## 2. 健康探针/指标每次抓取逐项查库

- **现状**:`ProcessManagerJdbcHealthIndicator.health()` 每次 6 条独立查询;`ProcessManagerJdbcMeterBinder` 每 gauge 一条,
  且 `suspendedInstancesBySource()` 被算两次。大积压时压库、可能阻塞就绪探针。
- **修复**:`JdbcProcessBacklog` 增 `snapshot(stuckThreshold)`——一次一致性读(`suspendedInstancesBySource` 只算一次)返回
  `BacklogSnapshot`。HealthIndicator 每次调 `snapshot` 一次;MeterBinder 以 1s TTL 记忆化 `snapshot`(用注入的
  `Clock`),使一次抓取内的所有 gauge 共享一次取样。

## 验证结果

- 新增/既有回归全绿:core 38、jdbc 53(含 PostgreSQL Testcontainers)、starter 23(含 MySQL Testcontainers)。
- 端到端:scaffold multi-module 履约验收 18 绿(deadline/transition 新列在真实流程中写入无误)。
- 七份 schema 副本(含 scaffold `start/schema.sql`)同步新增列;MySQL 用表内列定义,PG/H2 同构。

## 关联

- [[plan-00003-durable-process-manager-implementation]]、[[process-manager-schema-copies]]。
