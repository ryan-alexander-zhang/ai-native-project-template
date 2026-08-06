---
id: issue-00154-a-missing-audit-table-rolls-back-every-audited-command
type: issue
status: resolved
---

# operation-log 缺启动期表探针，漏配的代价是全量回滚（P1）

2026-08-02 第四轮评审发现（operation-log × flyway 契约）。

## 现象

`AipersimmonFlywayProperties.java:25-26` 承诺组件表缺失 "fails at startup, not at the
first write"。operation-log 没有兑现：没有任何探针查 `aipersimmon_operation_log` 是否存在。

爆炸半径被成功路径的设计放大：成功侧 append **刻意 fail-closed 在业务事务内**
（`CompletedOperationLogInterceptor.java:19-26`，这个设计本身是对的——审计缺失比业务继续
更糟）。于是 `aipersimmon.ddd.flyway.components` 漏列 `operation-log` 时，生产上**每一条
带 `@OperationLog` 的命令都会回滚**，而故障点（一个配置清单）离症状（业务全挂）很远。

## 修复要求

1. sink 构造时做一次 `SELECT` 探针（存在性检查即可），缺表时启动失败并指名
   `aipersimmon.ddd.flyway.components` 这个修复入口——兑现文档已有的承诺，不发明新行为。
2. **顺带核查**其他组件对同一承诺的兑现情况（outbox / inbox / process-manager /
   web-store / tenancy 各自的表校验是否真在启动期跑）；migrator 目前只有 unknown-component
   的 WARN。缺谁补谁，一并在本 issue 内记录核查结论。

## 解决记录（2026-08-02）

**核查结论：五个建表组件里只有 process-manager 兑现了承诺**（jdbc + mybatis 双探针，
`schema-validation=none` 逃生门）。outbox、inbox、operation-log、web-store 全缺——其中
outbox 的爆炸半径与 operation-log 同级（outbox 写在业务事务内，缺表=每条外发事件命令回滚）。
tenancy 不建表（`TenantTableRegistrationGuard` 守的是消费方的表），不适用。

**七个校验器全部补齐**，一律镜像 pm 形状（`InitializingBean` +
`@DependsOnDatabaseInitialization` + 零行 SELECT 点名最新迁移的列 + 报错同时点名
`aipersimmon.ddd.flyway.components` 修复入口与 `<组件前缀>.schema-validation=none` 逃生门）：

- operation-log：`JdbcOperationLogSchemaValidator` + `MybatisPlusOperationLogSchemaValidator`
  （独立 probe mapper，镜像 pm 的 ProcessSchemaMapper，不污染公开的 sink mapper）。
- outbox：双后端，探 `aipersimmon_outbox(tenant_id, lease_token, destination)`、
  `aipersimmon_dead_letter(tenant_id, destination)`、`shedlock(name, lock_until)`（V1 建的表；
  只有 opt-in 的保留清理真正取锁，探它的理由写在 javadoc：手工建表漏了它会在开启 cleanup
  当天死在后台线程上）。
- inbox：双后端，探 `aipersimmon_inbox(tenant_id)`。
- web-store：`JdbcWebStoreSchemaValidator`（三表，属性前缀 `aipersimmon.ddd.web.store`）。

每个校验器两条测试：缺表→`IllegalStateException` 且消息含修复入口与逃生门；已迁移 schema
→静默通过。CONFIGURATION.md 各组件表补 `schema-validation` 行。**顺带坑**：
`OutboxClockCoexistenceTest` 的 runner 起的是无 schema 的空 H2，默认开启的探针会（正确地）
打死它——该测试显式 `schema-validation=none` 并注释缘由。三个持 JdbcTemplate 的新类进
EI_EXPOSE_REP2 白名单。
