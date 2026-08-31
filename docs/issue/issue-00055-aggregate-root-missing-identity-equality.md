---
id: issue-00055-aggregate-root-missing-identity-equality
type: issue
status: resolved
blocks: [report-00001-ddd-framework-review]
---

# `AbstractAggregateRoot` 未实现基于身份的 `equals`/`hashCode`：与 `Entity` 的文档契约直接矛盾，同一聚合加载两次即被视为两个对象

## 问题（现状，file:line 为证）

- **等级：Medium（契约违背；在集合语义下产生错误结果，但样例当前未依赖该语义，故尚未表现为故障）**。
- `core/model/Entity.java` 的 Javadoc **明确声明**身份相等语义：

  > "Two entities are equal when their identities are equal, not when their attribute values match."

- 但 `core/model/AbstractAggregateRoot.java`（类体 `:21-51`）**没有实现 `equals`/`hashCode`**，
  于是继承 `Object` 的**引用相等**。
- 消费方也没有补上：样例 4 个聚合 `Order` / `Customer` / `Reservation` / `Stock` 全部继承
  `AbstractAggregateRoot` 且**均未声明 `equals`/`hashCode`**（`grep` 全域零命中）。
- 现有 ArchUnit 规则不覆盖此点：`BuildingBlockRules.java:74-88` 的
  `aggregateRootsShouldExtendAbstractAggregateRoot` 只要求继承基类；
  `valueObjectsShouldBeImmutable`（`:95-`）只管 `@ValueObject`。**没有规则守护实体的相等语义**。

后果（当前潜伏，一旦依赖集合语义即显现）：

```java
Order a = orders.findById(id).get();
Order b = orders.findById(id).get();
a.equals(b);                       // false —— 同一个订单
Set.of(a, b).size();               // 2    —— 同一个订单占两格
List.of(a).contains(b);            // false
```

任何按聚合去重、`Map` 键、`removeIf`、断言 `assertThat(list).contains(expectedOrder)` 的代码都会得到错误结果，
而错误表现为「查得到但比不上」，极易被误判为数据问题。

## 根因（第一性）

1. **观察 vs 期望**：期望「实体按身份相等（文档如此声明，且这是 DDD 实体的定义）」；实际「按引用相等」。
2. **最小机制**：相等语义被写在 `Entity` 接口的 **Javadoc** 里，而 Java 的 `equals`/`hashCode` 契约**无法由接口
   施加**——接口不能提供 `equals` 的默认实现（`Object` 的方法不可被 `default` 覆盖）。因此「声明」与「实施」之间
   存在一道语言层面的鸿沟，`Entity` 只能声明，必须由 `AbstractAggregateRoot` 这个**基类**实施。基类没做，鸿沟就
   一直开着。
3. **真根因**：契约的实施点缺失。不是 Javadoc 写错（它写对了），不是子类偷懒（框架从未要求它们实现），而是
   **框架把一个只能由基类兑现的承诺，写在了无法兑现它的接口上，然后基类没有接手**。
4. **排除的伪根因**：不是「应该让每个聚合自己实现 `equals`」。那会把同一段样板复制到每个聚合，且无法防止遗漏——
   正是基类存在的理由。

## 复现（test-first）

在 `aipersimmon-ddd-core` 的 `AbstractAggregateRootTest` 增加：

1. 同一 id 的两个不同实例 `a`、`b`（模拟「同一聚合加载两次」）。
2. **断言（现状 → 失败）**：`assertThat(a).isEqualTo(b)`；`assertThat(Set.of(a, b)).hasSize(1)`；
   `assertThat(a.hashCode()).isEqualTo(b.hashCode())`。
3. 反向断言（修复后须仍成立）：不同 id 不相等；**不同具体类型**即使 id 相同也不相等
   （避免 `OrderId("X")` 的 `Order` 等于同 id 的其它聚合）；`null` 与异类不相等。
4. 新建（未持久化）聚合之间的相等性以 id 为准——框架不引入「瞬态对象按引用相等」的特例，因为
   `AbstractAggregateRoot` 的子类在构造时即已持有 id（`Entity.id()` 无 `null` 语义）。

## 修复

在 `AbstractAggregateRoot` 实现：

- `equals`：用 `getClass() != other.getClass()` 而非 `instanceof`——`instanceof` 会让父/子类型互相相等，破坏
  对称性；聚合类型不同即不同实体。随后比较 `id()`。
- `hashCode`：`Objects.hashCode(id())`。
- 两者声明为 `final`，防止子类覆写后再次漂移。
- ~~新增 ArchUnit 规则 `aggregateRootsShouldNotOverrideEquality()`~~ —— **实施中判定为冗余，已取消**：
  `final` 使覆写成为**编译期错误**，而既有 `aggregateRootsShouldExtendAbstractAggregateRoot()` 已强制
  `@AggregateRoot` 继承基类；两者叠加后该规则永远不可能命中。一条不可能失败的规则不提供任何保护，只增加噪声。

**注意 `version` 字段不参与相等**（见 [issue-00051-aggregates-have-no-optimistic-locking](issue-00051-aggregates-have-no-optimistic-locking.md)）：版本是持久化
并发控制的元数据，不是身份的一部分；同一订单的 v3 与 v5 仍是同一个订单。`domainEvents` 同理不参与。

**注意改动面**：仅 `aipersimmon-ddd-core`（一个类）+ 一条 ArchUnit 规则 + 单测。样例无需改动（它们本就没有
自己的 `equals`，继承基类即获得正确语义）。

## 验证结果（已修复）

`AbstractAggregateRoot` 实现了 `final equals`（`getClass()` 精确比较 + `Objects.equals(id(), ...)`）与
`final hashCode`（`Objects.hashCode(id())`）。`version` 与 `domainEvents` 均不参与。

`AbstractAggregateRootTest` 覆盖：同 id 两实例相等且 `hashCode` 一致、`HashSet` 去重为 1、自反、不同 id 不等、
**不同聚合类型同 id 不等（双向）**、`null` 与异类不等、版本与已记录事件不影响相等。
（`Set.of()` 遇重复元素是抛异常而非去重，故去重测试改用可变 `HashSet`，否则会因错误的原因通过。）

`mvn -f aipersimmon-ddd/pom.xml -pl aipersimmon-ddd-core verify` 通过，含 JaCoCo 分支覆盖率门禁——
补 `equals` 自反分支的测试后达标，未调低阈值。

计划中的 ArchUnit 规则经判定冗余后取消，理由见上文「修复」一节。

## 关联

- [report-00001-ddd-framework-review](../report/report-00001-ddd-framework-review.md)（P2-5，本 issue 的来源）
- [plan-00013-phase-one-correctness-remediation](../plan/plan-00013-phase-one-correctness-remediation.md)
- [issue-00051-aggregates-have-no-optimistic-locking](issue-00051-aggregates-have-no-optimistic-locking.md)（同在 `AbstractAggregateRoot`，同批修改；`version` 不参与相等）
- [analysis-00006-ddd-building-blocks-library](../analysis/analysis-00006-ddd-building-blocks-library.md)（构件库的基类职责范围）
