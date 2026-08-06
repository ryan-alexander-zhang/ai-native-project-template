---
id: issue-00132-a-table-nobody-registered-is-visible-to-everyone
type: issue
status: resolved
---

# 忘记登记的租户表，对所有租户可见——而且没人会发现

2026-07-30 全面评审（P0）。

## 问题

`tenant-tables` 是手工维护的 allow-list（scaffold `start/src/main/resources/application.yml:222-228`，
六张表），而拦截器对**不在**集合内的表默认不改写：
`aipersimmon-ddd-tenancy-mybatis-plus/.../TenantContextTenantLineHandler.java:47-49` 的
`ignoreTable` 对未登记表返回 true。

消费者新增一张域表、忘记加进 allow-list，则该表所有 SELECT/UPDATE/DELETE 都没有
`tenant_id = ?` 谓词。列上的 `DEFAULT '__root__'` 救不了读路径——所有租户互相可见可改。

## 根因（第一性）

- 期望：漏配置的失败方向是"关"（查不到东西），不是"开"（看见所有人的东西）。
- 分歧机制：opt-in allow-list + 默认不改写，失败方向天然是全开；而全库没有任何启动检查或
  测试对账"带 tenant_id 列的消费者表都已登记"（已 grep 核实；`TwoTenantAcceptanceTest`
  只验证已登记的表）。
- 真根因：项目自己在 `V1_4__tenant_scoped_keys_and_indexes.sql` 用"拦截器只是应用层，约束要
  放在绕不过的地方"论证过复合租户外键，却没有把同一论证用到 allow-list 本身——清单正确性
  完全靠人的记性，且错了无症状。

附带：`normalize()`（`TenantContextTenantLineHandler.java:52-58`）剥掉 schema 前缀，allow-list
按裸表名匹配所有 schema——两个上下文若出现同名表将无法只登记其一。

## 复现（先写失败测试）

新建一张带 `tenant_id` 列、不登记进 `tenant-tables` 的测试表，断言启动（或守卫测试）fail-loud。
修复前它安静通过——这正是问题本身。

## 改法

框架提供启动期（或 test-support）守卫：扫描消费者 schema 中含 `tenant_id` 列的表，减去框架
自有表集合（outbox/inbox/process/operation-log 有意不在谓词管辖内），断言余集 ⊆
`tenant-tables`，差集即启动失败。scaffold 至少加一个 `MigrationContentTest` 式对账测试。
`normalize` 支持 `schema.table` 精确匹配。

## 验证结果

2026-07-31 修复。设计比 issue 原方案多一个关键概念：守卫要求的不是"每张表都走拦截器"，而是
**"每张表的租户策略都是一个在案的决定"**——带 `tenant_id` 的基表必须出现在 `tenant-tables`
（拦截器管）或新增的 `exempt-tables`（仓储自管，如 dedupe 日志从无租户绑定的路径也要写入）
二者之一，否则启动失败。豁免的第三类是结构性的：`aipersimmon_*` 框架表（relay 无租户扫描、
租户是数据列）与视图（拦截器改写的是基表语句）按构造排除，不占用配置。

- 库侧：`TenantTableRegistrationGuard`（查 `information_schema.columns ⋈ tables`，
  `BASE TABLE` 限定，系统 schema 排除）+ `guard-tables` 属性（默认开）+
  `SmartInitializingSingleton` 装配（迁移之后执行）；`TenantContextTenantLineHandler` 支持
  schema 限定条目（`ordering.orders` 只圈定该 schema；裸条目保持历史语义）。测试红在先
  （类不存在），`TenantTableRegistrationGuardTest` 三条 + handler 限定条目一条为回归守卫。
- **真实负向对照**：装库后未改 scaffold 配置直接跑 `ApplicationSmokeTest`，守卫当场拒绝启动，
  报文精确命名 `payment.payment_operations`——正是本仓库里那张已知的手管租户表。加
  `exempt-tables: [payment_operations]`（含理由注释）后 ApplicationSmokeTest +
  TwoTenantAcceptanceTest 绿，全量 start 套件绿。

`normalize` 剥 schema 的附带问题一并解决（见上 handler 限定条目）。

## 关联

- 租户信任模型的框架侧问题：[[issue-00133-tenant-isolation-trusts-whoever-is-in-the-process]]
- 同一"fail-open"教训的前一轮：[[issue-00099-tenant-isolation-fails-open-below-the-edge]]
