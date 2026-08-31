---
id: analysis-00016-samples-tactical-modelling
type: analysis
status: draft
parent: analysis-00014-ddd-samples-scenario-catalog
---

# S16 战术建模：实体、值对象、聚合与规则原语

对应 sample：`aipersimmon-ddd-samples/s16-tactical-modelling`。场景清单见
[analysis-00014-ddd-samples-scenario-catalog](analysis-00014-ddd-samples-scenario-catalog.md)，模板与工程约定见
[analysis-00015-samples-http-command-query](analysis-00015-samples-http-command-query.md)。

## 0. 本篇定位

S1 用了一个刻意笨的聚合，把"为什么这么建模"全部推给了本篇。这里补上：如何区分值对象、实体和
聚合根，聚合边界依据什么划，以及库为建模准备的那一套原语——`Invariant`、`Specification`、
`Transitions`、六个构造块标注、`Identifier`——各自解决什么问题、用错会发生什么。

**这一篇没有 HTTP、没有数据库、没有 Spring。** sample 是一个纯领域模块，编译依赖只有
`aipersimmon-ddd-core`，"跑起来"就是 `mvn test` 全绿。这本身是个演示：库的战术模型层是
framework-free 的，领域代码不必为了用它而引入任何框架。模型怎么落表是 S17，怎么被一个请求驱动
是 S1。

## 1. 三个建模判断

### 1.1 值对象、实体、聚合根

判据只有一个问题：**这个概念需要被追踪，还是只需要被度量/描述？**

| | 判据 | 相等性 | 例子 |
| --- | --- | --- | --- |
| 值对象 | 只描述属性，换一个等价的没人在意 | 按属性 | 金额、SKU、地址 |
| 实体 | 有贯穿生命周期的身份，属性会变而它还是它 | 按身份 | 一条订单行（可以改数量，仍是那一行） |
| 聚合根 | 是一致性边界的唯一入口，外部只能通过它改内部 | 按身份 | 订单 |

库对应的标注是 `@ValueObject` / `@Entity` / `@AggregateRoot`，javadoc 各给了一句判据，其中值对象
那句最实用："Prefer value objects for concepts measured or described rather than tracked."

同一个概念在不同上下文里可以是不同角色——订单行在本篇是实体（能被修改），在 S1 里是值对象
（整批替换）。**这不是矛盾**，是两个示例对"订单行要不要被追踪"给了不同答案；值得注意的是这个
选择直接决定了 S17 里子集合的写策略。

### 1.2 聚合边界：由不变量的作用范围决定

一条规则如果必须**在一次事务内始终成立**，它涉及的所有对象就在同一个聚合里。反过来，能容忍
片刻不一致的规则，就是聚合之间的事（S9 的最终一致性）。

sample 里四条不变量都只涉及订单自身的数据（至少一行、SKU 不重复、总额不超上限、下单后行冻结），所以它们
都在 `Order` 内部；而"客户的信用额度够不够"涉及另一个聚合，故意**不**放进 `Order`——那属于
S8 讨论的跨聚合问题。

### 1.3 聚合之间只按身份引用

`@AggregateRoot` 的 javadoc 写明："Other aggregates reference it only by identity, never by holding
the root instance."sample 里 `Order` 持有的是 `CustomerId`，不是 `Customer`。理由是边界：拿着
另一个根的实例，就等于把它的一致性规则也拉进了本事务。

## 2. 身份

### 2.1 `Identifier` 与那个类型界

```java
public abstract class AbstractAggregateRoot<ID extends Identifier> { ... }
```

`ID` 的界是 `Identifier`（一个纯标记接口），**不能是 `String` 或 `UUID`**。javadoc 解释了为什么
要用类型界而不是靠约定：它让"不同聚合的身份不会被混用"成为编译期事实，而不是纪律问题。所以每个
聚合都有自己的 id 值对象：

```java
@ValueObject
public record OrderId(String value) implements Identifier { ... }
```

代价是多写几个 record，收益是 `findById(customerId)` 这种错误传不进去。

### 2.2 `@Identity`

标在持有身份的字段或访问器上（`@Target({FIELD, METHOD})`），把"哪个是身份"对读者和工具讲明。
聚合根用不着它——`id()` 已经是契约；它对**非根实体**才有意义，sample 里标在 `OrderLine.id()` 上。

### 2.3 相等性是 `final` 的，而且有三条容易踩的细节

`AbstractAggregateRoot` 把 `equals`/`hashCode` 声明成 `final`，子类改不了。三点后果：

1. **精确类匹配**，不是 `instanceof`：子类与父类永不相等，因为让它们相等会破坏对称性，而且两种
   不同的聚合即使身份值相同也是不同的实体。
2. **`id()` 为 `null` 的聚合只等于自己**。这一条是防坑：`Objects.equals(null, null)` 为真，没有这个
   兜底，两个"还没分配 id 的新建对象"会相等，`Set` 会静默把它们合成一个。
3. **version 与已记录的事件都不参与相等性**——它们是持久化与生命周期状态，不是身份。

非根实体（`OrderLine`）没有基类可继承，得自己写按身份的 `equals`/`hashCode`，sample 里能看到
它和值对象（record 自动按属性相等）的对照。

### 2.4 谁铸造 id

`IdGenerator` 是 `@FunctionalInterface`，只有 `String newId()`，给的是时间有序的字符串
（默认实现是 UUIDv7）。**框架不会在聚合路径上替你调它**：id 由应用层或领域工厂显式铸造。本篇是
纯领域模块，所以 sample 的测试直接传入固定 id——什么时候用 `IdGenerator`、为什么时间有序重要
（索引局部性、游标分页），在 S1 与 S20。

## 3. 规则原语

### 3.1 `Invariant` 抛，`Specification` 答

这是本篇最重要的一条，库的 javadoc 已经把话说到位：

> `Invariant` answers "this must hold" … It carries an `ErrorCode` because a violation travels to
> the edge. `Specification` answers "does this match?": a caller branches on the result. There is no
> error code, because not matching is an ordinary outcome, not a fault.
>
> Reaching for an `Invariant` where a `Specification` belongs is what produces exceptions used as
> control flow; reaching for a `Specification` where an `Invariant` belongs is what lets an illegal
> state be written.

判断方法：**问"不满足时算不算出错"**。"订单没有行"是不该被写进库的非法状态 → `Invariant`。
"这单够不够免运费"是一个正常的分支 → `Specification`。

### 3.2 `Invariant` 的三个方法，以及什么时候**不该**做成 Invariant

```java
public interface Invariant {
  boolean isBroken();
  String message();
  ErrorCode errorCode();   // 必须有，不是 Optional
}
```

`errorCode()` 是必需的，javadoc 给了取舍标准：值得做成一等对象的不变量，就值得有一个能原样传到
边界的稳定身份；**琐碎的一次性守卫应该留成内联的 coded throw，而不是硬做成 `Invariant`**。
`checkInvariant` 的 javadoc 是同一句话的另一半："Prefer this over inline `if (...) throw` when the
invariant is worth naming and reusing; trivial one-off guards stay as coded throw."

sample 里四条不变量都是可复用、可单测、值得命名的；`OrderId` 里"value 不能空白"这种就是内联
`IllegalArgumentException`，没有包装成 `Invariant`。

另外它**故意不叫 `Validator`**：那个词属于边界输入校验（DTO 上的 Bean Validation），是另一件事，
分工见 S19。

### 3.3 `Specification` 的组合

```java
boolean isSatisfiedBy(T candidate);
default Specification<T> and(Specification<? super T> other);
default Specification<T> or(Specification<? super T> other);
default Specification<T> not();
```

之所以是接口而不是直接用 `Predicate`：规约是**有名字的领域概念**，组合起来在调用点仍然可读。
硬性要求是**无副作用**——"a specification decides, it does not act"。

### 3.4 `Transitions`：状态机以表的形式存在

```java
private static final Transitions<OrderStatus> TRANSITIONS = Transitions.<OrderStatus>of()
    .allow(DRAFT,  PLACED,    OrderingErrorCode.ORDER_NOT_PLACEABLE)
    .allow(PLACED, PAID,      OrderingErrorCode.ORDER_NOT_PAYABLE)
    .allow(DRAFT,  CANCELLED, OrderingErrorCode.ORDER_NOT_CANCELLABLE)
    .allow(PLACED, CANCELLED, OrderingErrorCode.ORDER_NOT_CANCELLABLE);
```

四条要点，每条都有实际后果：

- **拒绝码属于目的状态**，不属于来源。理由是 javadoc 里那句："'not in a state to be confirmed' is
  about where the caller tried to go, not where the object happened to be." 所以进入同一个目的
  状态的每条边必须用同一个码——**不一致会在声明期就抛 `IllegalArgumentException`**，不是运行时
  才发现。sample 里 `CANCELLED` 有两条入边，共用一个码。
- **它不是引擎、也不是基类**：领域对象"用"它，不"继承"它。`confirm()` / `pay()` 这些语言方法留在
  表面，转换表集中在一处。
- **不是线程安全的**：必须在类初始化时建完（`private static final`）然后当作冻结的。之后再
  `allow(...)` 是数据竞争。
- 两参 `allow` 也合法，只是拒绝时不带 `ErrorCode`，到边界就只有 409 而没有可分支的 code
  （S1 §5）。除了确实不需要对外区分的转换，都该用三参版本。

`permits(from, to)` 用于询问，`check(from, to)` 用于断言并抛
`IllegalStateTransitionException`。

### 3.5 异常只能从这两个入口出来

两条 ArchUnit 规则把入口收窄了：

| 规则 | 禁止 |
| --- | --- |
| `invariantViolationsShouldOnlyComeFromCheckInvariant` | 自己 `new InvariantViolationException(...)` |
| `illegalStateTransitionsShouldOnlyComeFromTransitions` | 自己 `new IllegalStateTransitionException(...)` |

意义在于：这两个异常携带的 `ErrorCode` 是对外契约的一部分，手工构造就绕过了"码从不变量/转换表
上取"这条保证。另有 `invariantsShouldNotBeSpringComponents`（不变量不是 bean）和
`errorCodesShouldBeEnums`（错误码必须是枚举，一个上下文一份目录）。

## 4. 领域服务与工厂

### 4.1 领域服务：无自然归属的行为

`@Service`（**是库的，不是 Spring 的**）标记"不天然属于某一个实体或值对象的、无状态的领域
行为"。判据是"这段逻辑放进哪个对象都别扭"，最典型的是**同时涉及两个对象、却不属于其中任何一个**。

sample 里 `LoyaltyDiscount` 就是这种：折扣要同时看订单金额和客户的会员等级，塞进 `Order` 会让
订单知道会员制度，塞进 `Customer` 会让客户知道订单结构，所以它是第三个东西。它只操作领域对象，
不碰仓储、不碰应用层——这也是它能留在纯领域模块里的原因。

反面：只读一个聚合自己的数据就能算出来的东西，不要做成领域服务，那是聚合自己的方法。

### 4.2 工厂

默认形态就是**聚合上的静态工厂方法**（`Order.draft(...)`）：它有一个说得出名字的意图，能在返回
之前检查不变量、注册领域事件。sample 用的是这一种。

需要独立工厂对象的只有两种情况：构造需要协作者（另一个服务、一份配置），或者要按输入在多个子类
之间选择。都不属于本篇。

第三种工厂是**重建工厂**（`Order.reconstitute(..., version)`）：它不是业务动作，不注册事件，而且
必须调 `restoreVersion(...)`——`restoreVersion` 是 `protected`，仓储调不到，只能由聚合自己的重建
工厂调。这条设计的理由与后果在 S17。

## 5. 领域事件在建模里的位置

建模阶段只需要知道两件事：聚合在行为方法里 `registerEvent(...)`，而**发布不是聚合的事**——
仓储保存后统一排空发布。`domainEvents()` 给的是不可变快照（不是活视图），`drainDomainEvents()`
取走并清空，两者都是为了应对"发布过程中监听器又往同一个聚合上记了一个事件"这种情况：返回活视图
会在发布者内部炸 `ConcurrentModificationException`，先拷后清则会静默丢掉那个新事件。

事件的语义、易失性、什么时候必须升级成 outbox，全在 S3。

## 6. 六个标注与背后的规则

| 标注 | 标在 | ArchUnit 规则 |
| --- | --- | --- |
| `@AggregateRoot` | 聚合根类型 | `domainBuildingBlocksShouldResideInDomain`、`aggregateRootsShouldExtendAbstractAggregateRoot` |
| `@Entity` | 有身份的领域对象 | `domainBuildingBlocksShouldResideInDomain` |
| `@ValueObject` | 无身份、不可变 | 同上 + `valueObjectsShouldBeImmutable` |
| `@Identity` | 身份字段/访问器 | 无（纯文档意图） |
| `@Repository` | 仓储**端口** | `portsShouldBeInterfacesInDomain`、`implementationsShouldResideInInfrastructure` |
| `@Service` | 领域服务 | `domainServicesShouldResideInDomain` |

两点提醒：

- `valueObjectsShouldBeImmutable` 是**浅层检查**——只看字段是不是 `final`。一个 `final List` 里装着
  可变 list 照样过，所以 record 的构造器里还是要 `List.copyOf(...)`。
- `@AggregateRoot` 与 `AbstractAggregateRoot` 是"角色声明"与"行为供给"的分工：前者是覆盖全部
  构造块角色的那套词汇，后者提供 version 与事件记录，规则保证两者同时出现。基类的 javadoc 明确
  说了没有第二个 marker 接口要实现。

## 7. version 才让聚合成为一致性单元

`version()` 常被当成持久化细节，但基类的 javadoc 把它抬到了建模层面：没有它，"两个命令各自都通过
了聚合自身的状态守卫，于是都写成功，后写的静默覆盖先写的"。也就是说，**聚合边界要靠乐观锁才真的
成立**，否则它只是个代码组织方式。

`versionAdvanced()` 是 `public` 的（仓储基类在别的包），但领域与应用层调它就等于在没有写入的情况下
推进见证值、把锁解除，所以有 `versionWitnessIsAdvancedOnlyByPersistenceAdapters` 在构建期拦住。
**这条规则不跑，这个保证就不存在。**

## 8. sample 的形状

```
s16-tactical-modelling/
├── pom.xml                 编译依赖只有 aipersimmon-ddd-core；测试只有 JUnit + AssertJ + archunit
└── src
    ├── main/java/com/example/samples/s16/ordering/domain/
    │     Order, OrderLine, OrderId, LineId, Money, Sku, CustomerId, LoyaltyTier,
    │     OrderStatus, Orders(端口), OrderingErrorCode,
    │     不变量 ×4, 规约 ×2, 领域服务 ×1, 领域事件 ×3
    └── test/java/...       值对象/实体/聚合相等性、不变量、转换表、规约组合、领域服务 + 架构规则
```

**故意没有的东西**：没有 `spring-boot-starter-*`，没有 `@Component`，没有数据库，没有
`main` 方法。这是"领域模块能不依赖框架编译"这一承诺的可执行证明——真装了 Spring，
`domainShouldBeFrameworkFree` 会打红。

## 9. 常见错法

| 错法 | 会发生什么 |
| --- | --- |
| 聚合根用 `String` 当 id | 编译不过（类型界是 `Identifier`） |
| 用 `Invariant` 表达"要不要打折" | 异常变控制流；调用方被迫 try/catch 走正常分支 |
| 用 `Specification` 表达"总额不能超限" | 有人忘了问，非法状态就落库了 |
| 手工 `new InvariantViolationException(...)` | ArchUnit 打红；错误码脱离不变量，边界契约漂移 |
| 同一目的状态两条边给了不同拒绝码 | 类初始化时就抛 `IllegalArgumentException`（好事：早于任何请求） |
| 在静态初始化之后再 `allow(...)` | 数据竞争，且不会有任何报错 |
| 值对象只把字段设成 `final` 就算完 | 规则能过，但内部集合仍可被外部改；要 `List.copyOf` |
| 在领域/应用层调 `versionAdvanced()` | ArchUnit 打红；否则乐观锁被静默解除 |
| 聚合持有另一个聚合根的实例 | 事务边界被拉大，两个聚合的规则纠缠 |

## 10. 本篇不覆盖

- 聚合怎么变成表、部分更新、子集合写策略、`restoreVersion` 的踩坑（S17）；
- 一个请求怎么驱动这个模型、错误码怎么变成 problem 响应（S1）；
- 事务边界、跨聚合一致性、乐观锁冲突的处理（S8）；
- 领域事件的消费与易失性（S3）；
- 三层校验分工（S19）；
- 测试的正式分层（S18）——本篇的测试只是把上面每条断言钉住。
