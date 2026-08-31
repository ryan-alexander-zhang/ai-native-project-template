---
id: issue-00054-sample-aggregate-ids-use-random-uuid
type: issue
status: resolved
blocks: [report-00001-ddd-framework-review]
---

# 旗舰样例的聚合主键用 `UUID.randomUUID()`：正是 `IdGenerator` 立项要消除的随机 VARCHAR 主键，且样例是使用者第一个抄的东西

## 问题（现状，file:line 为证）

- **等级：Medium（样例缺陷；本身不影响框架正确性，但它教的是错的写法，会被逐字复制到每个新项目）**。
- `PlaceOrderHandler.java:102`：

  ```java
  OrderId orderId = new OrderId(UUID.randomUUID().toString());
  ```

- 该 id 直接成为主键：`V1__aggregates.sql` 的 `ordering.orders.id VARCHAR(64) PRIMARY KEY`，并被
  `ordering.order_lines.order_id` 以外键引用（复合主键 `(order_id, line_no)`）。
- 同类还有 `inventory` 侧的 reservation id（`inventory.reservations.id VARCHAR(64) PRIMARY KEY`）。
- 框架已提供 `IdGenerator`（`core/id/IdGenerator.java`）且默认实现为 UUIDv7，但**样例的 handler 没有注入它**。
- `IdGenerator` 的 Javadoc 列了明确的排除项——`tenant_id`、web 幂等/nonce 键、`requestId`、lease `WorkerId`——
  **业务聚合主键不在排除列表内**。即这不是「设计上不适用」，而是**样例没跟上**。

后果：聚合表通常是整个库里行数最多、写入最频繁的表，**时间有序 id 的收益在这里最大**，却恰恰没享受到。而使用者
会把这一行当作范式复制到自己所有聚合。

## 根因（第一性）

1. **观察 vs 期望**：期望「框架自己的样例演示框架推荐的做法」；实际「样例演示了框架立项要消除的做法」。
2. **最小机制**：`plan-00012` 的收口范围被定义为「框架铸造点」（command messageId / event_id / process 四类 id /
   operation-log recordId），**聚合主键属于消费方代码**，因此不在那次改造的任务清单里。收口做完后没有人回头看
   「消费方样例是否也该改」。
3. **真根因**：`IdGenerator` 的**适用边界只写了排除项、没写推荐项**。Javadoc 说了"不覆盖 tenant_id / requestId /
   WorkerId"，但没说"业务聚合主键应当用它"。边界的正向一侧缺失，导致样例作者（和未来的使用者）无从判断聚合
   主键属于哪一侧。
4. **排除的伪根因**：不是「样例作者疏忽」。在 Javadoc 没有正面声明聚合主键属于适用范围的情况下，写
   `UUID.randomUUID()` 是可辩护的。修 Javadoc 与修样例同等重要。

## 复现（test-first）

新增 `start/src/test/java/com/example/AggregateIdIsTimeOrderedTest.java`：

1. 通过 `commandBus.send(new PlaceOrder(...))` 下单，取回返回的 orderId。
2. **断言（现状 → 失败）**：`UUID.fromString(orderId).version() == 7`；实际为 `4`。
3. 补充断言：连续下 N 单，其 id 的字典序与创建先后一致（UUIDv7 的时间有序性在 `VARCHAR` 主键上的可观测表现，
   也正是 B-tree 顺序插入的前提）。
4. 修复后两条断言均通过。

## 修复

- `PlaceOrderHandler` 注入 `IdGenerator`，`OrderId` 改由它铸造。inventory 侧 reservation id 同样处理。
- **同步修 `core/id/IdGenerator.java` 的 Javadoc**：把「业务聚合/实体的主键」显式列为**推荐用途**，与既有的
  排除清单对称。这是本 issue 的根因所在，不修则同类偏差会再次发生。
- 不改 `OrderId` / `Orders` / DDL（`VARCHAR(64)` 已足够容纳 36 字符 UUID）。

**注意改动面**：`PlaceOrderHandler`、inventory 侧对应 handler、`IdGenerator` Javadoc。与
[issue-00051-aggregates-have-no-optimistic-locking](issue-00051-aggregates-have-no-optimistic-locking.md) 同批修改样例聚合表，DDL 迁移可合并为一个。

## 验证结果（已修复）

`PlaceOrderHandler`（`OrderId`）与 `ReserveStockHandler`（`ReservationId`）改为由注入的 `IdGenerator` 铸造；
样例业务代码内已无 `UUID.randomUUID()` 用于聚合主键。

根因一并修掉：`core/id/IdGenerator.java` 的 Javadoc 现在把「业务聚合/实体的主键」**显式列为推荐用途**，
与既有排除清单对称，并补上「业务提供的自然键（SKU、客户编码）不由框架铸造」这一边界。

回归守卫：`start/src/test/java/com/example/AggregateIdIsTimeOrderedTest.java` 断言下单返回的 orderId
`UUID.fromString(...).version() == 7`，且连续铸造的 5 个 id 字典序与创建先后一致（时间有序性在 `VARCHAR`
主键上的可观测表现，也即尾部插入的前提）。id 仍视为不透明——不断言内嵌时间戳可读。

## 关联

- [report-00001-ddd-framework-review](../report/report-00001-ddd-framework-review.md)（P0-4，本 issue 的来源）
- [plan-00013-phase-one-correctness-remediation](../plan/plan-00013-phase-one-correctness-remediation.md)
- [issue-00053-id-generator-silently-degrades-to-uuidv4](issue-00053-id-generator-silently-degrades-to-uuidv4.md)（同源：时间有序 id 的覆盖面与可见性）
- [decision-00019-time-ordered-uuidv7-identifiers](../decision/decision-00019-time-ordered-uuidv7-identifiers.md) / [design-00010-time-ordered-identifiers](../design/design-00010-time-ordered-identifiers.md)
- [plan-00012-time-ordered-identifiers-implementation](../plan/plan-00012-time-ordered-identifiers-implementation.md)（其收口范围有意只覆盖框架铸造点，本 issue 补上消费方一侧）
- samples-not-reference（样例不是设计权威——但它**是**使用者的第一手范本，因此仍须正确）
