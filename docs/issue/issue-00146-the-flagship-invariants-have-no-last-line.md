---
id: issue-00146-the-flagship-invariants-have-no-last-line
type: issue
status: resolved
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

2026-07-31 修复，六项全做。新测试 `SchemaBackstopTest`（start，加入既有 @SpringBootTest
属性组不增上下文，全部用 raw JdbcTemplate 写入——它就是约束要防的那条旁路）落地当刻
**8 条全红**（5 失败 + 3 错误，逐条对应缺失的约束/列/索引），三个迁移 + 代码改动后 8/8 绿。

1. **CHECK 约束**（inventory V2_6 + ordering V1_7）：`stocks.available >= 0`（防超卖规则
   本体）、`reservation_lines.quantity > 0`、`order_lines.quantity > 0`、
   `order_lines.unit_minor >= 0`——与 issue-00145 补的构造器守卫互为镜像：构造器管应用路径，
   CHECK 管绕过应用的路径。
2. **`UNIQUE (tenant_id, order_id, sku)`**（V1_7）：`OrderHasDistinctSkus` 内存执行、
   reconstitute 不复跑（立场保留），DB 键是它在绕不过的层的镜像。**一个全量验收才暴露的
   交互**：该唯一索引前缀 `(tenant_id, order_id)` 完全覆盖 V1_4 子侧索引
   `order_lines_by_order` 的职责，planner 当即改选它，`OrderListPagingTest` 钉旧索引名的
   计划断言变红——正确修法与 V2_5 同构：**替换而非并存**（两个索引干一件事 = 写放大 +
   planner 掷硬币），V1_7 顺带 DROP 旧索引，计划断言改钉唯一索引。
3. **清理索引——先测量后下结论（memory 教训执行）**：一次性 PG 18.1 容器造 200 万行
   （保留 100 万），`EXPLAIN (ANALYZE, BUFFERS)`：无索引时每小时 purge 全表顺扫
   **8,345 buffers / ~86ms**，且**空转轮（零行可删）同样 86ms**——成本跟着保留历史涨；
   加 `recorded_at` 索引后典型轮 **18 buffers / 0.36ms**、空转轮 **3 buffers / 0.009ms**
   ——成本只跟到期工作量走。主张成立，V3_2 建 `payment_operations_by_recorded_at`，
   测试钉索引存在（量得的性质靠它存续）。
4. **时钟统一**：`recorded_at` 改 **TIMESTAMPTZ**（V3_2，`USING ... AT TIME ZONE 'UTC'`——
   旧值由 UTC 会话的 CURRENT_TIMESTAMP 写下，USING 是陈述不是假设）；写入从 DB 的
   `CURRENT_TIMESTAMP` 改为 mapper 参数 + `MyBatisPaymentOperations` 注入 **Clock**；
   `PaymentOperationCleanupConfig` 从 `Clock.systemUTC()` 硬造改为注入应用 Clock bean——
   开窗与关窗同一只钟，且测试可冻结。列型由测试断言 `information_schema` =
   'timestamp with time zone'。
5. **`orders.created_at`**（V1_7，TIMESTAMPTZ NOT NULL；DEFAULT 只为回填、随手 DROP）：
   落在**适配层**而非领域——`OrderDo.createdAt` + `FieldStrategy.NEVER`（创建时刻是事实
   不是状态，任何后续 save 不碰它）+ `MyBatisOrders` 注入 Clock 在 toRow 盖章。**没有改
   `Order.place` 签名**（在案）：issue 的诉求是审计/BI/对账可读的行级时间，适配层列完整
   满足且领域与全部既有测试零波及。测试：真下单后断言列非空且≈当前时刻。
6. **同 BC FK 取舍：加**（V1_7）：`orders (tenant_id, customer_id) REFERENCES customers`
   ——同上下文同 schema 同事务，V1_4 的论证在边界内无反例；子侧索引 V1_4 的
   `orders_by_customer_newest_first` 已覆盖。迁移注释同时写明**跨 BC 依旧不加 FK**（那条
   线就是上下文边界）。全部 raw 造单的测试只 seed customers、订单走应用，无一受累。

验证：`SchemaBackstopTest` 红→绿 8/8；scaffold 全量验收 `clean test -pl start -am`
BUILD SUCCESS。
