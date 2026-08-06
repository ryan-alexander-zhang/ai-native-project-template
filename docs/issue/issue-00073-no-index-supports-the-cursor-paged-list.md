---
id: issue-00073-no-index-supports-the-cursor-paged-list
type: issue
status: resolved
parent: report-00002-scaffold-ddd-review
---

# 三个迁移里零 CREATE INDEX：被当作性能特性宣传的游标分页，底下是全表扫

## 问题（现状，file:line 为证）

- **等级：Medium（数据量小时无感；但 scaffold 把游标分页当作**性能**卖点讲了三处，支撑它的索引却不存在——学到的是错的完整做法）**。
- `V1__aggregates.sql` / `V2__multi_tenancy_tenant_id.sql` / `V3__aggregate_version.sql`
  三个文件里 **`CREATE INDEX` 出现 0 次**（`grep -rn "CREATE INDEX" db/migration` 无命中）。
  只有主键隐式索引。
- 而列表查询的谓词是（`OrderListMapper.java:43-49`）：

```sql
FROM ordering.orders o LEFT JOIN ordering.order_lines l ON l.order_id = o.id
WHERE o.customer_id = #{customerId}
  <if test="after != null"> AND o.id &lt; #{after} </if>
GROUP BY o.id, o.status ORDER BY o.id DESC LIMIT #{limit}
```

  租户拦截器还会再拼上 `o.tenant_id = ?` 和 `l.tenant_id = ?`（`OrderListMapper.java:22-25` 说明了这点）。
  `ordering.orders` 的主键是单列 `id`，**`customer_id` 与 `tenant_id` 上没有任何索引**
  ⇒ 每翻一页都是一次全表扫 + 排序。
- `order_lines` 侧同样缺：PostgreSQL **不会**为外键自动建索引，
  而 `MyBatisOrders.saveChildren`（`:56`）每次保存订单都按 `order_id` 执行一次 DELETE，
  `findById`（`:77-81`）也按 `order_id` 查——两条热路径都无索引可用。
- 三处把游标分页作为性能特性宣讲：
  - `FindCustomerOrders.java:10-14`："an offset re-scans everything before it"；
  - `OrderListMapper.java:16-20`："No `created_at` column, no secondary sort to break ties, no offset to re-scan"；
  - `README.md:78`（能力表）与 `README.md:101`（成本表）。

  游标确实消除了 offset 的重扫，但**前提是有一条索引让 `WHERE ... AND id < ? ORDER BY id DESC LIMIT n`
  变成一次索引区间扫描**。没有索引时，游标分页与 offset 分页的代价是同一个数量级的全表扫。

## 根因（第一性）

1. **观察 vs 期望**：期望"游标分页 ⇒ 每页 O(页大小)"；实际"每页 O(该客户全部订单) 甚至 O(全表)"。
2. **最小机制**：查询计划的可用访问路径由索引决定。
   谓词列 `(tenant_id, customer_id)` 与排序列 `id` 上没有共同索引 ⇒ 只能 Seq Scan + Sort。
3. **真根因**：游标分页的正确性（不重不漏）与它的性能（不重扫）是**两件事**，
   前者由 UUIDv7 + `id < ?` 保证、且已被 `OrderListPagingTest` 充分验证；
   后者由索引保证、且**没有任何测试或断言覆盖**。
   项目把两者当成了一件事——因为在功能测试里它们看起来确实一样（都返回正确的页）。
4. **排除的伪根因**：不是 UUIDv7 选错了——恰恰相反，UUIDv7 让**单列 `id` 索引**
   同时充当排序键与游标，这是它最大的收益；缺的只是把 `tenant_id`/`customer_id` 前缀补上。
   也不是 `LEFT JOIN` 的问题——join 侧的 `order_lines(order_id)` 同样缺索引，但那是同一个原因。

## 复现（test-first）

功能测试不会失败，用执行计划断言：

```java
@Test
void theOrderListIsAnsweredByAnIndexScanNotASeqScan() {
  String plan = String.join("\n", jdbc.queryForList(
      "EXPLAIN SELECT o.id FROM ordering.orders o"
    + " WHERE o.tenant_id = ? AND o.customer_id = ? AND o.id < ?"
    + " ORDER BY o.id DESC LIMIT 21", String.class, TENANT, "CUST-1", "ffff"));

  assertFalse(plan.contains("Seq Scan on orders"),
      () -> "列表查询退化成全表扫，游标分页的性能前提不成立：\n" + plan);
}
```

（小表上 PostgreSQL 可能仍偏好 Seq Scan，测试里需先灌入足量行或
`SET enable_seqscan = off` 验证索引**可用**；两种写法都能钉住"索引存在"这件事。）

## 修复

新增 `V4__indexes.sql`：

```sql
-- 列表查询：谓词 (tenant_id, customer_id) + 游标/排序 id DESC，一条覆盖三者
CREATE INDEX orders_by_customer_newest_first
    ON ordering.orders (tenant_id, customer_id, id DESC);

-- 子表：saveChildren 的 DELETE 与 findById 的 SELECT 都按 (tenant_id, order_id)
CREATE INDEX order_lines_by_order      ON ordering.order_lines (tenant_id, order_id);
CREATE INDEX reservation_lines_by_res  ON inventory.reservation_lines (tenant_id, reservation_id);

-- 按订单反查预留（补偿路径与运维排查都要用）
CREATE INDEX reservations_by_order     ON inventory.reservations (tenant_id, order_id);
```

并在 `OrderListMapper` 的注释里补一句：游标分页的**正确性**来自 UUIDv7，
**性能**来自这条复合索引——两者缺一不可。这句话是本 issue 真正要留下的东西。

`V4` 可与 [[issue-00091-the-order-lines-foreign-key-omits-the-tenant]] 合并为同一个迁移。

## 验证结果

已修。四条索引落在 `V4__tenant_scoped_keys_and_indexes.sql`（与
[[issue-00091-the-order-lines-foreign-key-omits-the-tenant]] 合并为同一个迁移，如本 issue 所建议）。

- 索引按修复方案原样落地：`orders_by_customer_newest_first (tenant_id, customer_id, id DESC)`、
  `order_lines_by_order`、`reservation_lines_by_reservation`、`reservations_by_order`。
- 断言方式改了。**原设想的 `assertFalse(plan.contains("Seq Scan"))` 会假通过**——这一点是实测出来的：
  拿掉 `V4` 跑负向对照，计划是

  ```
  Limit  (cost=0.13..12.20 rows=3 width=37)
    ->  Index Scan Backward using orders_pkey on orders o
          Index Cond: ((id)::text < 'ffff...'::text)
          Filter: (((tenant_id)::text = 'acme') AND ((customer_id)::text = 'CUST-PLAN'))
  ```

  没有索引时规划器并不退化成 Seq Scan，而是走主键索引再 `Filter` 掉两个谓词——
  代价仍是"扫完该租户全部订单"，但计划里没有 `Seq Scan` 三个字。
  所以测试改为断言**计划点名了那条索引**（`orders_by_customer_newest_first` /
  `order_lines_by_order`），这也更贴合本 issue 要钉的东西：不是"有某条索引可用"，
  而是"有一条索引恰好覆盖了谓词 + 游标 + 排序"。
- 测试落在 `OrderListPagingTest.aPageIsAnsweredByAnIndexRangeScanNotAFullScan`，
  刻意与该类既有的正确性用例同处一室——本 issue 的论点就是两者是两件事，放在一起才看得见。
  用 `ConnectionCallback` 在同一条连接上 `SET enable_seqscan = off` → `EXPLAIN` → `RESET`
  （`JdbcTemplate` 每次调用各借一条连接，SET 会落到别的会话上）。
- 注释按要求补了，且是本 issue 真正的产出：`OrderListMapper` javadoc 新增一段讲
  **正确性来自 UUIDv7、性能来自复合索引，缺任一都不报错**；`FindCustomerOrders` 的
  "游标消除重扫"论断补上了"但只有索引让一页的代价等于一页"。
- 负向对照：移走 `V4` 后该测试红（上面的计划即其失败输出）；恢复后绿。
  注意 `target/classes` 里的旧副本会让对照失效，须一并删除。
- 验证：`mvn -o test -pl start -am` 全绿，`start` 模块 56 个测试 0 失败。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00091-the-order-lines-foreign-key-omits-the-tenant]]（同一个 V4 迁移）
- [[decision-00019-time-ordered-uuidv7-identifiers]]（游标可以是 id 的前提）
- [[issue-00090-order-lines-are-rewritten-on-every-save]]（DELETE 热路径，受本索引直接影响）
