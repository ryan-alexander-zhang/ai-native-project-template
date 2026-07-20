---
id: issue-00038-mysql-text-payload-column-below-max-bytes
type: issue
role: main
status: open
parent: plan-00003-durable-process-manager-implementation
---

# MySQL 样例 DDL 的 `TEXT` payload 列容量远低于 `payload.max-bytes`,合法 payload 仍会插入失败

## 问题(现状,file:line 为证)

- **等级:Medium**(仅 MySQL;且落在**样例/参考 DDL**上,非框架自有表——见下方准确性修正)。
- **准确性修正(对原 review)**:原 review 引用的
  `.../process-manager-jdbc-spring-boot-starter/.../META-INF/aipersimmon-ddd/process-manager/mysql-schema.sql`
  **不存在**。真实 DDL 位于
  `aipersimmon-ddd-process-manager-jdbc/src/main/resources/aipersimmon/db/migration/process-manager/mysql/V1__aipersimmon_process_manager.sql`,
  且文件头明标 “Sample only ... the starter never executes it”(`:1-2`)。因此这是 shipped **样例/参考 DDL**
  与 shipped 默认配置之间的不匹配,而非框架自有表的必然运行时失败。
- payload 列全部是 `TEXT`(MySQL `TEXT` 上限 **65,535 bytes**):`state_payload TEXT`(`:20`)、
  `input_payload TEXT`(`:34`)、effect `payload TEXT`(`:62`)、deadline `input_payload TEXT`(`:94`)。
- 默认限制为 1 MiB:`ProcessManagerJdbcProperties.java:71` `private long maxBytes = 1_048_576L;`。
- store 先 Base64 再存,膨胀约 +33%:`Payloads.toText` = `Base64.getEncoder().encodeToString(data)`,
  每次写入调用(如 `transitions.append` `:57`);DDL 头亦注 “TEXT holds base64 payloads”(`:3`)。
- 大小校验作用在 **Base64 之前**的原始字节:`enforceSize` 以 `encoded.data().length` 对比 `maxPayloadBytes`
  (`JdbcProcessRuntime.java:479-484`),即 1 MiB,**远高于** `TEXT` 的 64 KB。

结果:一个原始 ~48 KB 的 payload,经 Base64 后越过 65,535 bytes、INSERT 失败,却轻松通过 1 MiB 的 guard——
guard 根本保护不了该列。H2/Postgres 使用无界 `TEXT`/`CLOB`,故此问题**仅 MySQL** 出现。

## 根因(第一性)

1. **观察 vs 期望**:期望"低于 `payload.max-bytes` 的 payload 都能持久化";实际远低于该上限的 payload 在 MySQL 上即插入失败。
2. **最小机制**:校验的口径(Base64 前、1 MiB)与列的真实容量(Base64 后、64 KB TEXT)两处都不对齐:方向不对(前 vs 后),量级也不对(1 MiB vs 64 KB)。
3. **真根因**:样例 MySQL DDL 的列类型选择与默认 `max-bytes` 未协调,且 `max-bytes` 的语义(原始字节 vs 最终持久化字节)未明确。排除:不是校验逻辑本身有 bug(它按设计比原始字节),而是"被校验的量"与"列能容纳的量"不是同一个量。

## 复现(test-first)

提议 MySQL Testcontainers 测试:推进一个原始 payload ~50 KB(Base64 后 >64 KB、但 <1 MiB 默认上限)的实例,断言不抛。
修复前:MySQL 上 `TEXT` 截断 / INSERT 失败(而 H2/PG 通过,故当前测试全绿掩盖了它)。

## 修复

- 样例 DDL 的 payload 列改为 `MEDIUMTEXT`/`LONGTEXT`(或 `BLOB`/`LONGBLOB`,免 Base64 膨胀)。
- 若继续文本存储,把 `payload.max-bytes` 的语义明确定义为"**最终持久化大小**",并与列容量对齐。
- 至少也应在 DDL / 文档加注记,提醒 MySQL `TEXT` 的 64 KB 上限与默认 1 MiB 限制不一致。

## 关联

- [[process-manager-schema-copies]]
- [[issue-00020-ddl-id-column-widths-inconsistent]]
- [[plan-00003-durable-process-manager-implementation]]
