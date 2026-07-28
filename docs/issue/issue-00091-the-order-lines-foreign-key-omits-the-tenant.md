---
id: issue-00091-the-order-lines-foreign-key-omits-the-tenant
type: issue
role: main
status: resolved
parent: report-00002-scaffold-ddd-review
---

# 子表外键不含 tenant_id：数据库层面允许一条订单行指向另一个租户的订单

## 问题（现状，file:line 为证）

- **等级：Low（应用层拦得住；但多租户隔离的最后一道防线本应在数据库，而这道防线上有个洞）**。
- 两张子表的外键都是**单列**，建于多租户之前（`V1__aggregates.sql:26,49`）：

```sql
CREATE TABLE ordering.order_lines (
    order_id VARCHAR(64) NOT NULL REFERENCES ordering.orders (id), ...);

CREATE TABLE inventory.reservation_lines (
    reservation_id VARCHAR(64) NOT NULL REFERENCES inventory.reservations (id), ...);
```

- `V2` 给两张子表都加了 `tenant_id`（`V2__multi_tenancy_tenant_id.sql:14,19`），
  但**没有把它纳入外键**。父表 `orders` / `reservations` 的主键也保持单列
  （`V2:25-27` 明确说明："orders.id and reservations.id are framework-side globally-unique UUIDs,
  so their key is left single-column and tenant_id is a plain data column there"）。
- 结果：数据库允许 `order_lines(tenant_id='acme', order_id='X')` 与
  `orders(tenant_id='globex', id='X')` 共存——**一条属于 acme 的行挂在 globex 的订单下**。
- 实践中拦得住，靠两层：
  - 行 id 是全局唯一的 UUIDv7，跨租户撞 id 概率可忽略；
  - MyBatis-Plus 的 tenant-line 拦截器给两张表都加了 `tenant_id = ?` 谓词
    （两表都在 `application.yml:163-169` 的 `tenant-tables` 里）。
- 但这两层都是**应用层**的。`V2` 自己的论证（`:21-27`）明确区分了
  "consumer-provided natural keys"（`customers.id` / `stocks.sku`，必须复合）与
  "framework-side globally-unique UUIDs"（`orders.id` / `reservations.id`，可单列）——
  这个区分对**主键**成立，但它被顺带套用到了**外键**上，而外键要防的是不同的东西：
  主键防的是"两个租户用同一个键"，外键防的是"引用跨越了租户边界"。
- 对比：`design-00009` §6 特别记了一条"unique-key trap"（`V2:24-26` 引用了它），
  说明这类陷阱在设计时被想过；只是想的范围是主键。

## 根因（第一性）

1. **观察 vs 期望**：期望"租户隔离在数据库层面是可强制的"；
   实际"隔离完全依赖应用层拦截器，数据库不参与"。
2. **最小机制**：`REFERENCES ordering.orders (id)` 只约束 `order_id` 的存在性。
   `tenant_id` 是 `V2` 之后才有的列，外键定义没有被重新审视——
   `ALTER TABLE ... ADD COLUMN` 不会提示"你可能还要改外键"。
3. **真根因**：多租户是**后加**的（`V1` 建表，`V2` 加租户），
   而加租户时的检查表是按"主键会不会撞"列的，不是按"哪些约束现在需要多带一列"列的。
   外键、唯一约束、检查约束都属于后者，只有主键被覆盖到了。
4. **排除的伪根因**：不是拦截器不可靠——它工作正常，且
   `TwoTenantAcceptanceTest` / `OrderListPagingTest.anotherTenantsOrdersAreNotListed`
   都验证过。问题在于**除了它没有别的**：任何绕过 MyBatis 的写入
   （数据迁移脚本、运维手工修数据、以及测试里已经在用的裸 `JdbcTemplate`——
   见 `TwoTenantAcceptanceTest.java:66-75`）都不受任何约束。

## 复现（test-first）

```java
@Test
void anOrderLineCannotReferenceAnotherTenantsOrder() {
  String orderId = TenantContext.runAs(ACME, this::place);      // acme 的订单

  assertThrows(DataIntegrityViolationException.class, () ->
      jdbc.update("INSERT INTO ordering.order_lines"
                + " (order_id, line_no, sku, quantity, unit_minor, currency, tenant_id)"
                + " VALUES (?, 99, 'SKU-X', 1, 1, 'USD', 'globex')", orderId),
      "数据库必须拒绝跨租户的子行引用");
}
```

当前这条 INSERT 会**成功**，测试红。

## 修复

新增（或并入 [[issue-00073-no-index-supports-the-cursor-paged-list]] 的）`V4`：

```sql
-- 父表主键纳入 tenant_id，使外键可以带上它
ALTER TABLE ordering.orders        DROP CONSTRAINT orders_pkey;
ALTER TABLE ordering.orders        ADD PRIMARY KEY (tenant_id, id);
ALTER TABLE inventory.reservations DROP CONSTRAINT reservations_pkey;
ALTER TABLE inventory.reservations ADD PRIMARY KEY (tenant_id, id);

-- 子表外键改为复合
ALTER TABLE ordering.order_lines DROP CONSTRAINT order_lines_order_id_fkey;
ALTER TABLE ordering.order_lines
  ADD FOREIGN KEY (tenant_id, order_id) REFERENCES ordering.orders (tenant_id, id);

ALTER TABLE inventory.reservation_lines DROP CONSTRAINT reservation_lines_reservation_id_fkey;
ALTER TABLE inventory.reservation_lines
  ADD FOREIGN KEY (tenant_id, reservation_id)
      REFERENCES inventory.reservations (tenant_id, id);
```

注意两点：

- 这会改动 `orders` / `reservations` 的主键形状。
  `@TableId(type = IdType.INPUT)` 标在单列 `id` 上（`OrderDo.java:13-14`、
  `ReservationDo.java:13-14`），而 MyBatis-Plus 的 `selectById` / `updateById`
  仍按该列走，**由 tenant-line 拦截器补 `tenant_id` 谓词**——
  与 `customers` / `stocks` 已经在做的完全一样（它们 `V2` 就是复合主键）。
  所以这一步是把 `orders`/`reservations` 拉齐到另外两张表的既有形态，不是新形态。
- `V2:25-27` 那段"UUID 全局唯一所以主键可单列"的注释要一并更新：
  论断没错，但它解释的是**为什么不会撞键**，而不是**为什么外键不需要租户**。

## 验证结果

已修。落在 `V4__tenant_scoped_keys_and_indexes.sql`，与
[[issue-00073-no-index-supports-the-cursor-paged-list]] 合并为同一个迁移。

- **修复方案里的语句顺序是错的，已调整**：原稿先 `DROP CONSTRAINT orders_pkey`，
  但子表外键正依赖该主键背后的唯一索引，PostgreSQL 会拒绝
  （`cannot drop constraint ... because other objects depend on it`）。
  正确顺序是 **先删子表外键 → 再换父表主键 → 最后加复合外键**，迁移里按此写，
  并把"外键依赖它所指向的键的唯一索引"这句话留在注释里。
- 两处父表主键改为 `(tenant_id, id)`，两处子表外键改为复合，约束显式命名
  （`order_lines_order_fkey` / `reservation_lines_reservation_fkey`），不再依赖 PostgreSQL 的
  自动命名——V1 的 `order_lines_order_id_fkey` 这类名字正是这次要靠猜的东西。
- 主键形状变更对持久层无影响，如本 issue 所预判：`@TableId(type = IdType.INPUT)` 仍标在单列，
  `StockDo` 早就是这个形态（`stocks` 自 V2 起就是复合主键）。全量测试确认。
  另核对了全仓 `ON CONFLICT` 用法，14 处全部针对 `customers` / `stocks`，无一涉及本次改动的两张表。
- `V2:25-27` 那段注释按要求更新：论断保留（它对主键成立），补一句它被误读到了外键上，
  并指向 V4。这正是根因分析第 3 条要留下的东西。
- 测试落在 `TwoTenantAcceptanceTest.anOrderLineCannotBeFiledUnderAnotherTenantsOrder`，
  与该类既有的拦截器隔离用例并列——一条证明应用层拦得住，一条证明绕过应用层也拦得住。
- 负向对照：移走 `V4` 后该测试红
  （`Expected DataIntegrityViolationException to be thrown, but nothing was thrown`），
  即本 issue 复现步骤所述的"当前这条 INSERT 会成功"；恢复后绿。
- 验证：`mvn -o test -pl start -am` 全绿，`start` 模块 56 个测试 0 失败。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00073-no-index-supports-the-cursor-paged-list]]（同一个 V4 迁移）
- [[decision-00018-multi-tenancy-boundaries]]
- [[design-00009-multi-tenancy-tenant-id]]（§6 unique-key trap；本 issue 是它在外键上的对应物）
