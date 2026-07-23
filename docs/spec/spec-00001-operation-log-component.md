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

## 2. User Stories

每个 story 独立成 `docs/us/` 文档，各自拥有 value statement、EARS 需求与 GWT 验收；requirement 全局引用
`us-<n>-FR-<i>`。story 面向**消费方开发者**（记录侧）；业务查询者（读取侧）在 P3、不在本 spec。

| Story | Doc | Status | Summary |
| --- | --- | --- | --- |
| US1 注解捕获 | [[us-00001-operation-log-annotation-capture]] | draft | 在 application `Command` 上加 `@OperationLog` 即自动记录一条操作日志 |
| US2 Definition 捕获 | [[us-00002-operation-log-definition-capture]] | draft | 用类型安全 `OperationLogDefinition` 表达 before/after、diff、id→label、条件与脱敏 |
| US3 direct-API 记录 | [[us-00003-operation-log-direct-api]] | draft | 在 batch/scheduler/CLI 无 CommandBus 场景显式 `OperationLogs.record(draft)` |

## 3. Cross-cutting / System Requirements

面向整个组件、不属单个 story 的系统要求（幂等、事务、隐私、租户、尺寸、方言）。

- **spec-00001-XFR-1**（Unwanted）若同一 `(tenant, source, messageId, operationCode, outcome, completion)` 被重投，
  则系统应至多产生一条 entry，并返回既有 `recordId`。
- **spec-00001-XFR-2**（Complex）当成功路径 append 命中唯一键冲突时（在业务事务内），系统应使用方言原生
  `ON CONFLICT DO NOTHING` / `SAVEPOINT` 收敛而**不 abort 业务事务**；仅失败路径的隔离事务可用 catch-异常收敛。
- **spec-00001-XFR-3**（Unwanted）若成功路径 append 发生非重复键（genuine）错误，则系统应回滚业务事务（fail-closed）。
- **spec-00001-XFR-4**（Unwanted）若异常/回滚路径记录失败，则系统应保留并重抛原业务异常，并输出 failure-loss metric+alert。
- **spec-00001-XFR-5**（Ubiquitous）系统应默认拒绝记录任何字段（消费方逐项 allowlist），且 secret/token/凭据/生物信息
  永不入库；summary/label/value 入库前去除 CR/LF；failure 只存 `code/category/safeSummary`。
- **spec-00001-XFR-6**（Where 多租户开启）系统应在写入、唯一键与所有读取强制携带可信 tenant；非多租户模式规范化为 `GLOBAL`。
- **spec-00001-XFR-7**（Unwanted）若渲染后的 summary/changes/details/单值/总 payload 超过配置预算，则系统应按策略拒绝或截断并可观测。
- **spec-00001-XFR-8**（Ubiquitous）系统应在 `-jdbc` 与 `-mybatis-plus` 两后端 × H2/MySQL/PostgreSQL 三方言下，
  唯一约束、幂等收敛、时间序与分页排序行为等价。
- **spec-00001-XFR-9**（Ubiquitous）系统不应引入任何 ambient/ThreadLocal 每命令状态；成功与失败两路各持不可变局部对象。

**Acceptance（GWT）**
- **spec-00001-XAC-1.1**（spec-00001-XFR-1）
  Given 一条已提交的 `SUCCEEDED+COMMITTED` entry
  When 同 result kind 的命令被重投
  Then 不产生第二条记录，`record(...)` 返回 `DUPLICATE(existingRecordId)`
- **spec-00001-XAC-1.2**（spec-00001-XFR-1）
  Given 一条命令首次 `FAILED+ROLLED_BACK`
  When 重投后 `SUCCEEDED+COMMITTED`
  Then 保留两条各自收敛的 entry（result kind 不同）
- **spec-00001-XAC-2.1**（spec-00001-XFR-2）
  Given PostgreSQL、成功路径、同 idempotency_key 已存在
  When 重投在业务事务内 append
  Then 业务事务成功提交、业务变更不丢失、无虚假 `FAILED`，日志收敛为 DUPLICATE
- **spec-00001-XAC-3.1**（spec-00001-XFR-3）
  Given 成功路径 sink 注入一个 genuine 写错误
  When 命令处理
  Then 业务事务回滚，异常契约稳定
- **spec-00001-XAC-5.1**（spec-00001-XFR-5）
  Given 一个含 token/密码/原始异常的输入
  When 记录
  Then entry 不含 secret/token/stack/SQL/完整对象；只有 allowlist 字段落库
- **spec-00001-XAC-6.1**（spec-00001-XFR-6）
  Given 多租户开启
  When 查询未带 tenant
  Then 请求被拒绝（criteria 强制 tenant），且不存在跨 tenant 结果
- **spec-00001-XAC-8.1**（spec-00001-XFR-8）
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
| validation/authorization 拒绝 | `REJECTED+NOT_STARTED`，Failed 独立事务 | us-00001-FR-3 |
| handler/commit 技术失败 | `FAILED+ROLLED_BACK`，成功日志随之回滚 | us-00001-FR-3 / spec-00001-XFR-3 |
| 成功路径重复键（重投） | `ON CONFLICT DO NOTHING` 收敛，不 abort 事务 | spec-00001-XFR-2 / XAC-2.1 |
| 失败路径重复键 | 隔离事务 catch → DUPLICATE | spec-00001-XFR-1 |
| 记录失败 | 不替换原业务异常，metric+alert | spec-00001-XFR-4 |
| 敏感字段 | 默认拒绝 + 脱敏 | spec-00001-XFR-5 |
| 超预算 payload | 拒绝或截断且可观测 | spec-00001-XFR-7 |

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
