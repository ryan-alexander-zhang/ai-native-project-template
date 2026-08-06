---
id: issue-00158-messaging-oddments-from-the-2026-08-02-review
type: issue
status: resolved
---

# 2026-08-02 评审的消息机制 P2（伞形清单）

> **注（2026-08-06 补）**：本记录写于库同时并存 JDBC 与 MyBatis-Plus 两套存储后端的时期。
> `-persistence-jdbc`、`-outbox-jdbc`、`-inbox-jdbc`、`-process-manager-jdbc`、`-operation-log-jdbc`、
> `-web-store-jdbc`、`-starter-jdbc` 已全部删除（库只留 MyBatis-Plus 后端；web 边界存储由
> `-web-store-mybatis-plus` 承接）。因此下文带 `-jdbc` 的模块名、路径与 `file:line`，指的是当时的代码，
> 不是现在的树；它们作为当时的证据保留，未被改写成 MyBatis-Plus 的路径。

每项独立可做，做完划掉；膨胀则拆独立 issue。SQL/性能类主张先测量再改（issue-00146 的规矩）。

## outbox

- [x] **claim SQL 只在 H2 上跑过**：`outbox-jdbc` 测试树只跑 H2 迁移；SELECT-then-CAS
  （`JdbcOutboxStore.java:104-136`，mybatis 侧镜像）与队头 `NOT EXISTS` 谓词依赖引擎级
  单语句 UPDATE 语义，PG/MySQL 迁移从未在本 reactor 里对真库执行。补 Testcontainers
  并发认领测试（参照 pm 的 effect/deadline claim 真库测试形状；deadline 那次的教训：
  "没覆盖"≠"有错"，但要分清得先去跑）。
- [x] **purge DELETE 无批量上限**：`JdbcOutboxStore.java:68-69` 与 `InboxCleanup`（inbox
  全表条件删）都是单语句。首次在积累数月的表上开启=一个巨型事务。LIMIT 循环分批
  （注意 PG 无 `DELETE ... LIMIT`，用 ctid/子查询按 pm retention 的既有形状做）。
- [x] **调低 max-attempts 跨重启后的 stranded 行不可见**：`attempts >= maxAttempts` 的行
  不阻塞不可认领（`JdbcOutboxStore.java:40`，正确），但也不进 backlog gauge（:72-74）、
  不被追溯 dead-letter。加"given-up 在表"gauge 或一次性清扫。
- [x] **死信 replay 丢 trace 且尾排**：`JdbcDeadLetterStore.REQUEUE_OUTBOX`（:31-36）不带
  `traceparent`/`trace_state`，`created_at = now` 使重放事件排在同聚合后续事件之后。
  尾排已文档化为放弃项（可不动）；trace 列补上。

## kafka

- [x] **DLT topic 存在性无人检查**：`deadLetterDestination`
  （`AipersimmonDddMessagingKafkaAutoConfiguration.java:330-332`）避开了分区陷阱，但
  `<topic>.DLT` 不存在时 recoverer 失败→分区永久 seek-back，且该失败非
  `DataAccessException`，`SystemicStallReporter` 沉默。启动期存在性检查或文档写明预置
  要求（择一，成本低者）。

## process-manager

- [x] **by-ref advance 不校验租户相等**：`DefaultProcessRuntime.java:445-450` 验 type +
  business key，不比 `cause.tenantId()` 与行租户——持有外租户 `ProcessRef` 的 confused
  deputy 能推进别人的实例并写出混租转移行。一行相等断言 + 测试。
- [x] **时间戳列依赖 JVM 统一时区**：租约/deadline 用 `Timestamp.from(Instant)` 走应用
  时钟（outbox 同型），混时区节点会把租约到期平移数小时。语义安全（租约围栏 + inbox
  吸收重复），但没有任何文档说"所有节点跑 UTC"。CONFIGURATION.md 补运维前提。
- [x] **单实例 effect 吞吐是每轮一条**：队头认领 + 默认 500ms poll，一次转移暂存 N 个
  effect 要 N × pollDelay 才排空（`SkipLockedProcessDialect.java:43-55` 每批 N+1 次往返）。
  是调优特性不是缺陷，文档化到 pm 的 tuning 段落。

## operation-log

- [x] **审计表无保留/清理故事**（outbox、inbox 都有 purge，唯独审计表没有）。补按时间
  的分批清理，默认关（审计数据删除该是显式决定）。
- [x] **`failureRecordLost` 开箱只是一条 WARN**：`OperationLogMetrics` 默认 no-op，最该
  告警的"审计缺口"信号没有指标。给 micrometer binder 补计数器（形状照 outbox observer）。
- [x] **`Redactor` 截断可切开 surrogate pair**（:57-62 `substring`）：MySQL utf8mb4 拒绝
  半个代理对，而成功路径 insert 失败会回滚业务事务。用 code point 边界截断 + 一条含
  emoji 的测试。
- [x] **PG 迁移里过期的 `'GLOBAL'` 哨兵注释**（`postgresql/V1__aipersimmon_operation_log.sql:5`，
  实际哨兵是 `__root__`）。一行。

**在案不做**：`OperationLogReader` 仍是路线图端口（评审确认现状即立场，消费方自写查询）。

## 解决记录（2026-08-02/03，分四个 commit）

- **pm 租户守卫**（commit `14e34a6`）：by-ref advance 补租户相等断言，外租户 ref 答"not
  found"不答"forbidden"（不向跨租户调用方确认实例存在）；负向对照实跑红。CONFIGURATION.md
  补 UTC 运维前提与单实例 effect 吞吐两段。
- **七项加固**（commit `82b6c4f`）：purge 分页（outbox 按 id 页、inbox 按时间切片——复合主键
  没有便携的 select-then-delete）；`aipersimmon.outbox.given.up` gauge（调低 max-attempts 搁浅
  的行唯一的出口）；死信双跳保 trace（列 V1 就有、store 两跳都在丢，`DeadLetterStore.store`
  签名加 trace 两参）；operation-log Micrometer 桥（`failure.record.lost` 从 WARN 变可告警计数
  器，装配 `before` 引擎的 noOp 兜底）；Redactor 代理对安全截断；DLT 预置要求文档化（不做启动
  探测：会误伤 auto-create 环境、其余环境也只能 WARN——写清失败形状：非 DataAccessException
  所以 stall WARN 沉默，看分区 consumer lag）；PG V1 哨兵注释更正。
- **审计保留清理**：双后端 `*OperationLogCleanup`，默认关且 javadoc 言明双重理由（删数据是
  部署决定 + 删审计行该是可被问责的声明，默认窗口一年）；id 分页；batch-size=1 一轮排空的
  分页测试。
- **claim SQL 真库覆盖**：`OutboxClaimRealDatabaseTest`（Testcontainers PG 18 + MySQL 8）三场
  景×双引擎：并发认领分割行集（无一行被赢两次）、队头规则、未过期租约屏蔽第二认领者。
  **负向对照**：禁用 CAS 复检 → 真库上红。javadoc 点明教训出处：deadline claim 那次"语句
  正确但没人能知道"。mybatis 后端经 wrapper 生成标准 SQL 且行为等价测试已覆盖 H2，真库
  差异集中在手写 SQL 的 jdbc 侧——mybatis 真库镜像在案不做。
