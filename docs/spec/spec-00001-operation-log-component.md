---
id: spec-00001-operation-log-component
type: spec
role: main
status: active
parent:
---

# Spec: 通用操作日志组件（Operation Log）MVP

> 一句话：让消费方以**注解 / 类型安全 Definition / direct-API** 三种方式记录同一种面向业务阅读者的操作日志，
> 归一到统一模型、统一事务/幂等/脱敏语义，并落到可互换的 JDBC / MyBatis-Plus 存储后端。

技术设计在 [[design-00008-operation-log-component]]（本 spec 不内联设计）；决策边界见
[[decision-00017-operation-log-component-boundaries]]；预研见 [[analysis-00013-operation-log-component]]。

**MVP 范围** = design-00008 的 P1 + P1b + P2（三入口闭环 + 双存储后端 + 注解捕获）。查询读端口
（`OperationLogReader`）、method-AOP adapter、中心平台 exporter、Audit Log profile 均**不在本 spec**（P3+）。

## 1. Context

- 采用 [[decision-00017-operation-log-component-boundaries]] 固化的术语：**Operation Log**（业务可读操作历史，≠ Audit
  Log ≠ Technical Log ≠ Domain Event）、**Operation Outcome**（`SUCCEEDED/REJECTED/FAILED`）、**Transaction
  Completion**（`COMMITTED/ROLLED_BACK/NOT_STARTED/UNKNOWN`，与 outcome 正交）、**Actor / Target / OperationChange**。
- 本 spec 落地前，这些术语需并入 `CONTEXT.md`（见 [[plan-00010-operation-log-implementation]] 任务 T0）。
- 受约束于 [[decision-00011-cqrs-write-contracts-as-interfaces-not-annotations]]（注解仅元数据）、
  [[decision-00012-no-ambient-per-command-state]]（无 ambient 状态）、
  [[decision-00013-command-context-and-causation-propagation]]（不扩展 `CommandContext`）、
  [[decision-00016-durable-runtime-staged-message-identity]]（at-least-once 幂等）、[[design-00003-exception-model]]（异常分类）。

## 2. Stories

Story 面向**消费方开发者**（记录侧）；业务查询者（读取侧）在 P3、不在本 spec。

| Story | Value | Delivers |
| --- | --- | --- |
| S1 注解捕获 | 作为消费方开发者，我想在 application `Command` 上声明稳定操作码、目标与安全文案模板，以便无需改 handler 就自动记录一条操作日志 | spec-00001-FR-1 … spec-00001-FR-4 |
| S2 Definition 捕获 | 作为消费方开发者，我想用类型安全 `OperationLogDefinition` 捕获修改前后的业务化变化并注入 read port，以便记录 opaque id 的人类可读名称、字段级 diff 与条件性"不记录" | spec-00001-FR-5 … spec-00001-FR-8 |
| S3 direct-API 记录 | 作为消费方开发者，我想在无 CommandBus 的 batch/scheduler/CLI 中显式记录操作日志，以便系统动作也有可信 actor 与如实的事务完成态 | spec-00001-FR-9 … spec-00001-FR-11 |

## 3. System Requirements

### 3.1 Story requirements

**S1 注解捕获**

- **spec-00001-FR-1**（Event）当带 `@OperationLog` 的 command 正常返回时，系统应恰好记录一条
  `outcome=SUCCEEDED, completion=COMMITTED` 的 entry，且与业务变更同事务提交。
- **spec-00001-FR-2**（Optional）当注解声明了 `rejectedWhen` 谓词且其对结果投影为真时，系统应把该次正常返回记为
  `REJECTED, COMMITTED`。
- **spec-00001-FR-3**（Unwanted）若带 `@OperationLog` 的 command 抛异常且 `recordFailure=true`，则系统应记录一条
  `REJECTED/FAILED`（由 `FailureClassifier` 判定），并**重新抛出原异常**。
- **spec-00001-FR-4**（Ubiquitous）系统应只在启动期编译并校验注解模板；非法模板阻止启动。

**S2 Definition 捕获**

- **spec-00001-FR-5**（Event）当 Definition 的 `prepare` 在成功路径执行时，系统应在业务事务内**只捕获一次**
  allowlisted before projection。
- **spec-00001-FR-6**（Event）当 `complete(result)` 返回 draft 时，系统应经同一 normalize/validate/redact/freeze
  pipeline 落库（与等价注解一致）。
- **spec-00001-FR-7**（Optional）当 `complete`/`failed` 返回 empty 时，系统应不记录任何 entry（`RecordResult.SKIPPED`）。
- **spec-00001-FR-8**（Unwanted）若同一 input type 同时匹配注解与 Definition、或有重复 Definition、或泛型不可判定，
  则系统应在启动期失败。

**S3 direct-API 记录**

- **spec-00001-FR-9**（Event）当调用 `OperationLogs.record(draft)` 且存在当前事务时，系统应把 append 加入该事务。
- **spec-00001-FR-10**（Unwanted）若无当前事务，则系统应记 `completion=UNKNOWN`，不冒充原子性。
- **spec-00001-FR-11**（Ubiquitous）系统应要求调用方显式提供 actor、可信 tenant/source、target、outcome；可重试调用须提供稳定 `idempotencyKey`。

**Acceptance（GWT）**

- **spec-00001-AC-1.1**（spec-00001-FR-1）
  Given 一个带 `@OperationLog` 的 command 与已提交的业务变更
  When 它正常返回
  Then 存在恰好一条 `SUCCEEDED+COMMITTED` entry，actor/target/code/causality 正确，且与业务行同事务
- **spec-00001-AC-1.2**（spec-00001-FR-1）
  Given 同上
  When 业务事务因其它原因回滚
  Then 不存在虚假的 `SUCCEEDED` entry
- **spec-00001-AC-2.1**（spec-00001-FR-2）
  Given 一个带 `rejectedWhen` 谓词的注解 command
  When 它正常返回且谓词对结果投影为真
  Then 存在一条 `REJECTED+COMMITTED` entry，与业务事务一起提交
- **spec-00001-AC-3.1**（spec-00001-FR-3）
  Given `recordFailure=true` 的注解 command
  When handler 抛技术异常导致回滚
  Then 存在一条 `FAILED+ROLLED_BACK` entry，且原异常被重新抛出、未被替换
- **spec-00001-AC-4.1**（spec-00001-FR-4）
  Given 一个含非法属性路径 / 未知根对象的注解模板
  When 应用启动
  Then 启动失败并给出可定位的模板编译错误
- **spec-00001-AC-5.1**（spec-00001-FR-5, spec-00001-FR-6）
  Given 一个改地址的 Definition
  When 命令成功
  Then before projection 只执行一次，entry 的 `changes` 只含 allowlist 的实际变化，并与等价注解走同一 pipeline
- **spec-00001-AC-7.1**（spec-00001-FR-7）
  Given 一个在无变化时返回 empty 的 Definition
  When 命令成功但无可记录变化
  Then 不产生任何 entry，`record(...)` 结果为 `SKIPPED`
- **spec-00001-AC-8.1**（spec-00001-FR-8）
  Given 同一 input type 既有注解又有 Definition
  When 应用启动
  Then 启动失败并给出可定位的冲突信息
- **spec-00001-AC-9.1**（spec-00001-FR-9, spec-00001-FR-10）
  Given 一个 `@Transactional` batch 与一个无事务 CLI 动作
  When 各自 `record(draft)`
  Then 前者 `completion=COMMITTED` 与业务同事务，后者 `completion=UNKNOWN`
- **spec-00001-AC-11.1**（spec-00001-FR-11）
  Given 一个会重跑的 batch 动作，为每条记录提供稳定 `idempotencyKey`
  When 该动作重跑
  Then 不产生重复 entry，`record(...)` 返回 `DUPLICATE(existingRecordId)`

### 3.2 Cross-cutting requirements

面向整个组件、不属单个 story 的系统要求（幂等、事务、隐私、租户、尺寸、方言）。

- **spec-00001-FR-12**（Unwanted）若同一 `(tenant, source, messageId, operationCode, outcome, completion)` 被重投，
  则系统应至多产生一条 entry，并返回既有 `recordId`。
- **spec-00001-FR-13**（Complex）当成功路径 append 命中唯一键冲突时（在业务事务内），系统应使用方言原生
  `ON CONFLICT DO NOTHING` / `SAVEPOINT` 收敛而**不 abort 业务事务**；仅失败路径的隔离事务可用 catch-异常收敛。
- **spec-00001-FR-14**（Unwanted）若成功路径 append 发生非重复键（genuine）错误，则系统应回滚业务事务（fail-closed）。
- **spec-00001-FR-15**（Unwanted）若异常/回滚路径记录失败，则系统应保留并重抛原业务异常，并输出 failure-loss metric+alert。
- **spec-00001-FR-16**（Ubiquitous）系统应默认拒绝记录任何字段（消费方逐项 allowlist），且 secret/token/凭据/生物信息
  永不入库；summary/label/value 入库前去除 CR/LF；failure 只存 `code/category/safeSummary`。
- **spec-00001-FR-17**（Where 多租户开启）系统应在写入、唯一键与所有读取强制携带可信 tenant；非多租户模式规范化为 `__root__`。
- **spec-00001-FR-18**（Unwanted）若渲染后的 summary/changes/details/单值/总 payload 超过配置预算，则系统应按策略拒绝或截断并可观测。
- **spec-00001-FR-19**（Ubiquitous）系统应在 `-jdbc` 与 `-mybatis-plus` 两后端 × H2/MySQL/PostgreSQL 三方言下，
  唯一约束、幂等收敛、时间序与分页排序行为等价。
- **spec-00001-FR-20**（Ubiquitous）系统不应引入任何 ambient/ThreadLocal 每命令状态；成功与失败两路各持不可变局部对象。

**Acceptance（GWT）**
- **spec-00001-AC-12.1**（spec-00001-FR-12）
  Given 一条已提交的 `SUCCEEDED+COMMITTED` entry
  When 同 result kind 的命令被重投
  Then 不产生第二条记录，`record(...)` 返回 `DUPLICATE(existingRecordId)`
- **spec-00001-AC-12.2**（spec-00001-FR-12）
  Given 一条命令首次 `FAILED+ROLLED_BACK`
  When 重投后 `SUCCEEDED+COMMITTED`
  Then 保留两条各自收敛的 entry（result kind 不同）
- **spec-00001-AC-13.1**（spec-00001-FR-13）
  Given PostgreSQL、成功路径、同 idempotency_key 已存在
  When 重投在业务事务内 append
  Then 业务事务成功提交、业务变更不丢失、无虚假 `FAILED`，日志收敛为 DUPLICATE
- **spec-00001-AC-14.1**（spec-00001-FR-14）
  Given 成功路径 sink 注入一个 genuine 写错误
  When 命令处理
  Then 业务事务回滚，异常契约稳定
- **spec-00001-AC-16.1**（spec-00001-FR-16）
  Given 一个含 token/密码/原始异常的输入
  When 记录
  Then entry 不含 secret/token/stack/SQL/完整对象；只有 allowlist 字段落库
- **spec-00001-AC-17.1**（spec-00001-FR-17）
  Given 多租户开启
  When 查询未带 tenant
  Then 请求被拒绝（criteria 强制 tenant），且不存在跨 tenant 结果
- **spec-00001-AC-19.1**（spec-00001-FR-19）
  Given 后端 × 方言 参数化测试矩阵
  When 跑同一组用例
  Then 唯一约束/幂等/排序结果在 6 组合下一致

## 4. Technical Design

默认外置：技术设计见 [[design-00008-operation-log-component]]（模型、端口、生命周期、interceptor 时序、DDL、模板、事务）。
下列仅为 spec 级索引：

### 4.1 API（消费方可见）
- 注解 `@OperationLog(code, targetType, targetId, success, failure, recordFailure, rejectedWhen)`（design §5.5）
- `OperationLogDefinition<I,R>` / `PreparedOperationLog<R>` 生命周期（design §5.3）
- `OperationLogs.record(OperationLogDraft): RecordResult`（design §5.3）
- SPI：`FailureClassifier`、`OperationActorResolver`、`OperationTenantResolver`（design §5.3 / §6.2）

### 4.2 State（outcome × completion）
见 design §8.1 表：`SUCCEEDED/COMMITTED`、`REJECTED/COMMITTED`、`REJECTED/{NOT_STARTED,ROLLED_BACK}`、`FAILED/ROLLED_BACK`。

### 4.3 Data
- 单表 `aipersimmon_operation_log`（design §7.2），DDL 居 `-engine`，`(tenant_id, source, idempotency_key)` 唯一。

### 4.4 Error Handling（映射需求 id）
| 情况 | 处理 | 需求 |
| --- | --- | --- |
| validation/authorization 拒绝 | `REJECTED+NOT_STARTED`，Failed 独立事务 | spec-00001-FR-3 |
| handler/commit 技术失败 | `FAILED+ROLLED_BACK`，成功日志随之回滚 | spec-00001-FR-3 / spec-00001-FR-14 |
| 成功路径重复键（重投） | `ON CONFLICT DO NOTHING` 收敛，不 abort 事务 | spec-00001-FR-13 / AC-13.1 |
| 失败路径重复键 | 隔离事务 catch → DUPLICATE | spec-00001-FR-12 |
| 记录失败 | 不替换原业务异常，metric+alert | spec-00001-FR-15 |
| 敏感字段 | 默认拒绝 + 脱敏 | spec-00001-FR-16 |
| 超预算 payload | 拒绝或截断且可观测 | spec-00001-FR-18 |

## 5. Out of Scope
- `OperationLogReader` 查询、cursor 分页、查询授权示例（P3）
- method-annotation AOP adapter、中心平台 exporter/CDC、Audit Log profile（P3+）
- repeatable annotation / 多记录 / 多 target（需先定义原子 `appendAll`）
- retention/purge 端口（P3 定义，本 MVP 仅遵守"可真正删除"原则）

## 6. Non-Functional
- 无高基数 metric（label 仅 `operationCode`/`outcome`/`sinkType`）；`recordId`/`correlationId` 关联技术日志与 span。
- 质量门：按 `TESTING.md` / [[design-00007-code-quality-gates]]，覆盖率/静态分析/mutation/集成测试达标；core 模块 framework-free（ArchUnit 守护）。

## Links
- Design: [[design-00008-operation-log-component]]
- Decision: [[decision-00017-operation-log-component-boundaries]]
- Plan: [[plan-00010-operation-log-implementation]]
- Analysis: [[analysis-00013-operation-log-component]]
