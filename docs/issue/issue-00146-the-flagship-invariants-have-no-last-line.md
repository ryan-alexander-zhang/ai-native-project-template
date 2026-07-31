---
id: issue-00146-the-flagship-invariants-have-no-last-line
type: issue
role: main
status: open
---

# 项目自己论证过"约束要放绕不过的层"，旗舰不变量却没有最后一道防线

2026-07-30 全面评审（P2 批量项，schema 兜底 + 时钟）。`V2_4__tenant_scoped_keys_and_indexes.sql:6-9`
为租户外键写下的论证——"拦截器是应用层，psql/脚本绕得过"——对下列各项同样成立，但没有执行。

## 清单

1. **`stocks.available` 无 `CHECK (available >= 0)`**（`V2_1__aggregates.sql:13-16`）：防超卖
   完全押在应用层乐观锁上。同理 `quantity > 0`、`unit_minor >= 0` 均无 CHECK。成本一行。
2. **`order_lines` 允许重复 SKU 行**（`V1_1__aggregates.sql:26-34`，PK 是 `(order_id, line_no)`）：
   `OrderHasDistinctSkus` 只在内存执行且 `reconstitute` 不复跑（立场正确），但 DB 也没有
   镜像——绕过应用的写路径可造出重复 SKU 行集，重载后 `total()`/reserve 语义悄变、无人报错。
   改：`UNIQUE (tenant_id, order_id, sku)`。
3. **`payment_operations` 清理无索引**（`V3_1__payment_operations.sql:31-45` 只有 PK；
   `PaymentOperationMapper.purgeRecordedBefore:69-70` 按 `recorded_at <` 删除）：每小时全表
   顺序扫描。改：`recorded_at` 上加索引。
4. **`payment_operations` 时钟源分裂 + 无时区列型**：写入用 DB 时钟 `CURRENT_TIMESTAMP`
   （`PaymentOperationMapper.java:44-45`；列型 `TIMESTAMP`，`V3_1:43`），清理用 JVM 时钟
   （`PaymentOperationCleanup.java:47`）；且 `PaymentOperationCleanupConfig.java:42` 直接
   `Clock.systemUTC()` 而非注入应用的 `Clock` bean——与 `FulfilmentTrigger` 注入 Clock 的
   自家范式不一致，测试无法冻结。DB 会话时区非 UTC 时 30 天 dedupe 窗整体偏移——窗口短了
   就是"迟到重投第二次授权"。改：应用 Clock 作参数写入 + `TIMESTAMPTZ` + 注入 Clock bean。
5. **业务表无时间戳列**（`ordering.orders` 无 `placed_at`，V1_1:20-24）："时间"寄生在
   UUIDv7 id 上，排序/游标没问题，但审计、BI、对账、客服都要解码 id。改：加 `created_at`
   （应用 Clock 写入），不影响现有游标设计。
6. **同 BC 内跨聚合引用无 FK**（`orders.customer_id` 不引用 `ordering.customers`）：跨 BC
   不加 FK 正确；但 customer 与 order 同 BC 同事务，加 `(tenant_id, customer_id)` FK 的收益
   与 V1_4 论证同构、成本为零。可作取舍保留，但至少在迁移注释里说明为什么同 BC 也不加。

注意（memory 教训）：改 SQL 性能相关项（第 3 条）前先在真库量一遍再下结论。

## 复现（先写失败测试）

第 1、2 条各一条裸 JdbcTemplate 测试（绕过应用写入非法行，断言 DB 拒绝——与
`TwoTenantAcceptanceTest:132-145` 同一手法）。修复前 DB 接受非法行。

## 验证结果

未修复。
