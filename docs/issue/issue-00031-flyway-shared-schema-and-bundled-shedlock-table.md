---
id: issue-00031-flyway-shared-schema-and-bundled-shedlock-table
type: issue
status: resolved
blocks: [plan-00006-middleware-integration]
---

# Flyway starter:多实例共管同一 schema + 默认自动基线 + 通用 shedlock 表塞进 outbox 迁移

## 问题(现状,file:line 为证)

- **等级:Low(运维卫生/归属清晰度)**。两个独立小别扭:

**(a) 多 Flyway 实例共管一个 schema + 默认基线。** `aipersimmon-ddd-flyway` 为 classpath 上每个组件各起一个
Flyway 实例,都打在消费者自己的 schema(本次是 `public`),各用独立历史表
`flyway_schema_history_aipersimmon_{component}`。启动日志实测:`component 'outbox' applied 2 migration(s) ...`、
`component 'process-manager' applied 2 migration(s) ...`,均"to schema public"。`AipersimmonFlywayProperties`
默认 `baseline-on-migrate=true`、`baseline-version=0`。→ 多个"迁移管理者"共享同一 schema 且默认自动基线。

**(b) 通用 `shedlock` 表塞进 outbox 组件迁移。** `aipersimmon-ddd-outbox/.../outbox/postgresql/V1__aipersimmon_outbox.sql:52`
`CREATE TABLE IF NOT EXISTS shedlock (...)`——一张**非 `aipersimmon_` 前缀**的、ShedLock 通用契约表,被并进了
outbox 组件的迁移里(由 outbox 的历史表记账)。

## 根因(第一性)

1. **(a) 便利优先于隔离**:每组件独立历史表让"加/减组件"很顺手,但代价是多实例共享一个 schema 的所有权含糊;
   默认 `baseline-on-migrate=true` 会把"已有对象的 schema"静默基线,可能**掩盖 schema 漂移**(本该报错的初始状态被
   当成 baseline 接受)。
2. **(b) 组件迁移越界**:`shedlock` 是跨切面基础设施(relay 选主用),不是 outbox 的领域表;把它塞进 outbox 迁移,
   使这张通用表的**归属**绑在了某个具体组件上。`IF NOT EXISTS` 避免了硬失败,但若消费者(或另一库)也管理
   `shedlock`,谁拥有它、谁负责演进就说不清了。

## 复现

- (a) n/a(观察性:日志显示多组件各自 apply 到 `public`;默认 baseline 行为可用"预置一张同名表 + 首次迁移"验证其被静默接受)。
- (b) 已直接核验 `V1__aipersimmon_outbox.sql:52` 创建 `shedlock`;`saga-spring` 的迁移**不**建 `shedlock`(已查),故当前无跨组件重复创建,属潜在归属问题而非现网冲突。

## 修复/建议(minor)

- (a) 文档化"每组件独立历史表 + 共享 schema"的取舍;考虑把 `baseline-on-migrate` 改为**opt-in**(或至少在文档里点明
  默认自动基线的漂移风险)。若要更强隔离,可选每组件独立 schema。
- (b) 要么给锁表加前缀(`aipersimmon_shedlock`),要么把 ShedLock 建表从 outbox 迁移中**抽出**到一个显式的共享/锁组件,
  让这张通用表的归属清晰(并让同样需要选主的其它组件复用同一张,而非各自 `IF NOT EXISTS`)。

## 关联

- [plan-00006-middleware-integration](../plan/plan-00006-middleware-integration.md)(flyway.components 装配现场)
- process-manager-schema-copies

## 核查结论(在当前 HEAD 复核)

**两半都成立为"观察",但都不成立为"缺陷";(b) 的原建议若照做反而更糟。** 逐条核过:

**(a) 多实例共管 + 默认基线:所述的"掩盖 schema 漂移"风险不成立。** 原文担心
`baseline-on-migrate=true` 会把已有对象的 schema 静默基线、跳过本该跑的迁移。实际不会,原因有两条且都是既有设计:
- `baseline-version=0` **排在所有组件迁移(V1 起)之前**,基线只写一行 v0 记录,V1+ 照常全部应用——
  `AipersimmonFlywayProperties` 的字段注释本身就写明了这一点;
- 三方言 DDL 全部是 `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS`(MySQL 用内联 KEY),
  即迁移**幂等**,在已有对象上重跑是 no-op 而非硬失败。

所以真实语义是"允许在非空 schema 上被纳入",而不是"跳过迁移"。剩下的确实只是**取舍未被写下来**——
按原文对 (a) 的建议,做了文档化(见下),未改默认值。

**(b) `shedlock` 归属:是归属含糊,不是功能缺陷;原建议(改名 `aipersimmon_shedlock` 或抽出独立组件)已拒绝。**
- 现网无冲突已复核:`@SchedulerLock` 的使用者**只有 outbox**(relay/cleanup,两个后端各一份)。
  process-manager 的 effect-relay 与 deadline-worker **不用 ShedLock**,走自己的 lease 列——
  原文推测的"其它组件各自 `IF NOT EXISTS`"并不存在,也就没有跨组件重复创建。
- **改名会让事情变坏**:`shedlock` 是 ShedLock 的**默认表名**,`JdbcTemplateLockProvider` 未调
  `withTableName()`(已核)。改名就必须同时改 LockProvider 配置,并且让一个**本来已在用 ShedLock**
  的应用凭空多出第二张锁表。`IF NOT EXISTS` 恰恰是让它成为**共享**表而非**声明所有权**的表:
  已自管 `shedlock` 的应用不受影响,没有的则由此获得。这已经是这张通用表最合适的处理方式。
- 抽一个"共享/锁组件"来放**一张表**,成本(新模块 + 新 flyway 组件名 + 消费方多一处配置)远高于收益,
  且在只有一个消费者时没有复用可言。

## 修复(已实施:只做文档,不改行为)

- 三方言 outbox V1 里 `shedlock` 上方的注释从一行扩为完整说明:**为什么不加 `aipersimmon_` 前缀**、
  `IF NOT EXISTS` 意味着共享而非独占、以及**为什么它搭 outbox 的车**(outbox 是目前唯一取 ShedLock 租约的组件)。
- `CONFIGURATION.md` 的 flyway 段补两段:"每组件独立 Flyway 实例 + 独立历史表 + 共享你的 schema"这个取舍;
  以及 `baseline-on-migrate` 为什么开着、为什么它**不跳过**任何迁移(baseline 0 + 幂等 DDL)、
  想让 Flyway 对非空 schema 直接报错就设 `false`。同时点明 outbox 迁移会带出 `shedlock`。

**默认值一个都没动**:`baseline-on-migrate=true` 保留(它是"可被纳入既有库"的前提,而所担心的风险不存在),
表名保留。本 issue 结为"已核查 + 已文档化 + 原建议经论证后拒绝"。
