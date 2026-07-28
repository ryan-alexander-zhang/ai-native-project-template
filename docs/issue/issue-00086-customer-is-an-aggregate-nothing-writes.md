---
id: issue-00086-customer-is-an-aggregate-nothing-writes
type: issue
role: main
status: open
parent: report-00002-scaffold-ddd-review
---

# Customer 被标成聚合根，但没有生命周期、没有写入、没有状态变化

## 问题（现状，file:line 为证）

- **等级：Low（不产生错误行为；但它是 scaffold 里唯一一处战略建模可商榷的地方，且会被当作"聚合怎么建"的样板）**。
- `Customer` 带 `@AggregateRoot`、继承 `AbstractAggregateRoot<CustomerId>`（`Customer.java:9-10`），
  但它：
  - **没有任何状态变更方法**——全部成员 `final`（`:12-14`），
    唯一的行为 `canAfford` 是纯查询（`:23-25`）；
  - **不发任何领域事件**（无 `registerEvent` 调用）；
  - **无法被写入**——`Customers` 端口只有 `findById`（`Customers.java:10`），没有 `save`；
  - **无法被创建**——没有工厂方法、没有 `CreateCustomer` 命令、没有端点。
    仓库里客户只能来自 `V1__aggregates.sql:57-58` 的种子或测试的 `@BeforeEach`；
  - **没有乐观锁版本列**，`V3__aggregate_version.sql:22-23` 明确豁免了它，理由是
    "the Customer aggregate is never written and a version column there would be dead weight"。
- 也就是说：这个"聚合"没有一致性边界要守（没有可变状态）、
  没有生命周期要管（不能生不能改）、没有不变量要强制（构造器里一条校验都没有）。
  它承担的全部职责是**携带一个信用额度供 `PlaceOrderHandler` 读取**（`PlaceOrderHandler.java:107`）。
- `MyBatisCustomers` 的 javadoc 与 `CustomerMapper` 的注释都已经承认了这一点：
  "read-only in this app; seeded by Flyway"（`CustomerMapper.java:6`）。

## 根因（第一性）

1. **观察 vs 期望**：期望"`@AggregateRoot` 标注的类是一个事务一致性单元"；
   实际"它是一个只读投影，恰好用聚合的形状写出来了"。
2. **最小机制**：`@AggregateRoot` 与 `AbstractAggregateRoot` 都不要求类具备可变状态或事件——
   ArchUnit 的 `aggregateRootsShouldExtendAbstractAggregateRoot` 检查的是
   "标了注解就要继承基类"，方向是对的，但它无法检查"这东西配不配当聚合"。
3. **真根因**：`Customer` 的真正归属**不在 ordering 上下文**。
   客户的创建、改名、调额度都属于一个客户/CRM 上下文；
   ordering 需要的只是"下单时这个客户的额度是多少"。
   把外部上下文的实体照搬成本地聚合，是最常见的一种上下文映射失误——
   而这个项目在**另一个**同类问题上做对了：库存不属于 ordering，
   所以它用 `StockAvailabilityGateway` 端口 + ACL 适配器取数
   （`StockAvailabilityGateway.java:5-17`），而不是在 ordering 里建一个 `Stock` 聚合。
   **同一份代码里，同一个问题有正确答案和错误答案各一个。**
4. **排除的伪根因**：不是"因为它简单所以看起来不像聚合"——
   `Stock` 也只有一个 `int` 字段（`Stock.java:13`），但它有 `reserve`/`release`、
   有不变量、有版本列，是货真价实的聚合。区别不在大小，在**有没有要守的东西**。

## 复现（test-first）

行为上无从失败，用建模断言：

```java
@ArchTest
static final ArchRule anAggregateRootMustBeWritable =
    classes().that().areAnnotatedWith(AggregateRoot.class)
        .should(haveAMatchingRepositoryPortWithSave())
        .because("聚合是事务一致性单元；没有写入路径的类型是只读投影，不是聚合");
```

`Customer` 会让它变红（`Customers` 无 `save`）。

## 修复

三条路，取决于本 issue 与
[[issue-00071-credit-limit-is-checked-but-not-enforced]] 的联合决定——**两者应一起定**：

1. **让它成为真聚合**（若采纳 issue-00071 的强一致方案）：
   `Customer` 增加"已用额度"状态与 `reserveCredit` / `releaseCredit` 行为，
   `Customers` 补 `save`，`ordering.customers` 补 version 列（撤销 `V3` 的豁免）。
   一次修两个 issue，且 scaffold 因此获得一个"跨聚合一致性"的完整示范。
2. **降级为只读投影**：去掉 `@AggregateRoot` 与 `AbstractAggregateRoot`，
   改成 `CustomerCreditProfile` 一类的读模型 + `CustomerProfiles` 读端口
   （放 application 层，与 `OrderQueries` 同构）。诚实且便宜。
3. **改为跨上下文查询**：与库存一样，加一个 `CustomerCreditGateway` 端口 + ACL 适配器，
   把客户彻底移出 ordering 的领域层。**建模上最正确**，
   且能让 scaffold 演示"两个不同的外部上下文、同一种 ACL 手法"——
   代价是要么新建一个 customer 上下文，要么承认它是外部系统。

不推荐维持现状：一个不能写的聚合，会让读者以为"聚合"就是"带注解的数据容器"。

## 验证结果

未修。本 issue 由 [[report-00002-scaffold-ddd-review]] 落盘，尚未实施。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00071-credit-limit-is-checked-but-not-enforced]]（同一根因的另一面，应一起决策）
- [[decision-00015-cross-context-sync-query-via-gateway-acl]]（方案 3 的既有范式）
- [[design-00011-aggregate-persistence-contract]]
