---
id: us-00002-operation-log-definition-capture
type: us
role: main
status: active
parent: spec-00001-operation-log-component
---

# User Story: Definition 式操作日志捕获

作为消费方开发者，
我想用类型安全 `OperationLogDefinition` 捕获修改前后的业务化变化并注入 read port，
以便记录 opaque id 的人类可读名称、字段级 diff 与条件性"不记录"。

## Requirements (EARS)

- **us-00002-FR-1**（Event）当 Definition 的 `prepare` 在成功路径执行时，系统应在业务事务内**只捕获一次**
  allowlisted before projection。
- **us-00002-FR-2**（Event）当 `complete(result)` 返回 draft 时，系统应经同一 normalize/validate/redact/freeze
  pipeline 落库（与等价注解一致）。
- **us-00002-FR-3**（Optional）当 `complete`/`failed` 返回 empty 时，系统应不记录任何 entry（`RecordResult.SKIPPED`）。
- **us-00002-FR-4**（Unwanted）若同一 input type 同时匹配注解与 Definition、或有重复 Definition、或泛型不可判定，
  则系统应在启动期失败。

## Acceptance (GWT)

- **us-00002-AC-1.1**（us-00002-FR-1, us-00002-FR-2）
  Given 一个改地址的 Definition
  When 命令成功
  Then before projection 只执行一次，entry 的 `changes` 只含 allowlist 的实际变化，并与等价注解走同一 pipeline
- **us-00002-AC-3.1**（us-00002-FR-3）
  Given 一个在无变化时返回 empty 的 Definition
  When 命令成功但无可记录变化
  Then 不产生任何 entry，`record(...)` 结果为 `SKIPPED`
- **us-00002-AC-4.1**（us-00002-FR-4）
  Given 同一 input type 既有注解又有 Definition
  When 应用启动
  Then 启动失败并给出可定位的冲突信息

## Links
- Spec: [[spec-00001-operation-log-component]] · Design: [[design-00008-operation-log-component]] · Plan: [[plan-00010-operation-log-implementation]]
