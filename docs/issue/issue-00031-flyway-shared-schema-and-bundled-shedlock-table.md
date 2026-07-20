---
id: issue-00031-flyway-shared-schema-and-bundled-shedlock-table
type: issue
role: main
status: open
parent: plan-00006-middleware-integration
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

- [[plan-00006-middleware-integration]](flyway.components 装配现场)
- [[process-manager-schema-copies]]
