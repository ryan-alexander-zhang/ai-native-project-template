---
id: issue-00020-ddl-id-column-widths-inconsistent
type: issue
role: main
status: resolved
parent: plan-00003-durable-process-manager-implementation
---

# PostgreSQL/H2 的 id 列宽比其承载的 id 源列窄,长 id 会溢出回滚

## 问题(现状,file:line 为证)

- **等级:Medium(潜伏)**。
- id 源列 `effect_id`/`message_id` 全方言均为 `VARCHAR(96)`,但承载它们派生值的列在 PostgreSQL/H2 只有 `VARCHAR(64)`:
  - `suspending_work_id VARCHAR(64)`(写入 `effect.effectId()`,见 `relay/JdbcProcessEffectRelay.java` 的 suspend 调用)——
    `postgresql-schema.sql:14`、`h2-schema.sql:15`。
  - `input_message_id VARCHAR(64)`(写入 `cause.messageId()`,见 `runtime/JdbcProcessRuntime.java` appendTransition)——
    `postgresql-schema.sql:29`、`h2-schema.sql:30`。
  - `transition_kind VARCHAR(32)`(`postgresql-schema.sql:38` 等)。
- MySQL 已把这三列分别放宽到 96/96/48;PostgreSQL/H2 未同步。默认 UUID id(~38 字符)不触发,故测试测不到;自定义 id
  `Supplier` 或外部注入的 65–96 字符 message id 会在 PostgreSQL 上 insert 溢出 → advance/suspend 事务回滚 → effect 永远无法落定。

## 根因(第一性)

1. **观察 vs 期望**:期望"能承载任何合法 id(≤96)的派生值";实际"PG/H2 的派生列比源列窄"。
2. **最小机制**:三方言 DDL 手工维护,MySQL 放宽时 PG/H2 漏改,列宽约束与 id 长度约束脱节。
3. **真根因**:派生列宽未与其数据来源(id 列)对齐;应以 id 列宽为准。

## 复现(test-first)

`JdbcProcessRuntimeLongIdTest#acceptsAMessageIdUpToTheIdColumnWidth`:先正常 start,再以携带 80 字符 message id 的**无 effect**
输入(`Finish`)推进,断言 transition 的 `input_message_id` 落库为该长 id。用无 effect 的 `Finish` 是为了只压 `input_message_id`,
不牵动 effect 表的 `causation_id`。修复前 H2 `input_message_id VARCHAR(64)` 抛"value too long";修复后 `VARCHAR(96)` 接受。

**范围界定**:effect 表的 `correlation_id`/`causation_id` 仍为 `VARCHAR(64)`——三方言一致、承载框架默认的 UUID 型关联/因果 id。
它们同样承载 message id、理论上也可达 96,但本 issue 只收口 agent 指出的、比其**同表 id 源列(96)**更窄的
`suspending_work_id`/`input_message_id`;若将来使用 >64 字符的外部 message id 作因果链,再单独放宽这两列。

## 修复

PostgreSQL 与 H2 的两份 DDL(starter 主 + 各自测试副本,共 5 文件)将 `suspending_work_id`/`input_message_id` 放宽到
`VARCHAR(96)`、`transition_kind` 到 `VARCHAR(48)`,与 MySQL 及 id 源列对齐;scaffold multi-module `start/schema.sql`
副本同步。

## 验证结果

- 新回归测试通过;既有用例不回归。
- jdbc + starter 模块 test 全绿(含 Testcontainers)。

## 关联

- [[plan-00003-durable-process-manager-implementation]]
