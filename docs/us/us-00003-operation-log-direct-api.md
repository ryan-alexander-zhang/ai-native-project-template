---
id: us-00003-operation-log-direct-api
type: us
role: main
status: active
parent: spec-00001-operation-log-component
---

# User Story: direct-API 记录操作日志

作为消费方开发者，
我想在无 CommandBus 的 batch/scheduler/CLI 中显式记录操作日志，
以便系统动作也有可信 actor 与如实的事务完成态。

## Requirements (EARS)

- **us-00003-FR-1**（Event）当调用 `OperationLogs.record(draft)` 且存在当前事务时，系统应把 append 加入该事务。
- **us-00003-FR-2**（Unwanted）若无当前事务，则系统应记 `completion=UNKNOWN`，不冒充原子性。
- **us-00003-FR-3**（Ubiquitous）系统应要求调用方显式提供 actor、可信 tenant/source、target、outcome；可重试调用须提供稳定 `idempotencyKey`。

## Acceptance (GWT)

- **us-00003-AC-1.1**（us-00003-FR-1, us-00003-FR-2）
  Given 一个 `@Transactional` batch 与一个无事务 CLI 动作
  When 各自 `record(draft)`
  Then 前者 `completion=COMMITTED` 与业务同事务，后者 `completion=UNKNOWN`
- **us-00003-AC-3.1**（us-00003-FR-3）
  Given 一个会重跑的 batch 动作，为每条记录提供稳定 `idempotencyKey`
  When 该动作重跑
  Then 不产生重复 entry，`record(...)` 返回 `DUPLICATE(existingRecordId)`

## Links
- Spec: [[spec-00001-operation-log-component]] · Design: [[design-00008-operation-log-component]] · Plan: [[plan-00010-operation-log-implementation]]
