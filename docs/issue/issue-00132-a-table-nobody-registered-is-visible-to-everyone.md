---
id: issue-00132-a-table-nobody-registered-is-visible-to-everyone
type: issue
role: main
status: open
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

未修复。

## 关联

- 租户信任模型的框架侧问题：[[issue-00133-tenant-isolation-trusts-whoever-is-in-the-process]]
- 同一"fail-open"教训的前一轮：[[issue-00099-tenant-isolation-fails-open-below-the-edge]]
