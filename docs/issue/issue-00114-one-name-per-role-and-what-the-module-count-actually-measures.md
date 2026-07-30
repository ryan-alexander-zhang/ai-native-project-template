---
id: issue-00114-one-name-per-role-and-what-the-module-count-actually-measures
type: issue
role: main
status: resolved
parent: report-00003-ddd-library-review-2026-07-29
---

# 一个概念一个名字；以及"48 个模块"到底量的是什么

## 一、同名双词汇表（已删）

core 里三个角色各有两套同名声明：

| 角色 | 注解 | 接口 | 保留 |
|---|---|---|---|
| AggregateRoot | `core.annotation.AggregateRoot` | `core.model.AggregateRoot<ID>` | **注解** |
| Entity | `core.annotation.Entity` | `core.model.Entity<ID>` | **注解** |
| DomainEvent | `core.annotation.DomainEvent` | `core.event.DomainEvent` | **接口** |

代价不是抽象的：`EventRules` 为了同时匹配两条路径，**必须把其中一个写成全限定名**
（`com.aipersimmon.ddd.core.annotation.DomainEvent.class`），因为 `DomainEvent` 这个简单名已经被接口占了。

### 判据：留下**承重**的那一边，删掉只是回声的那一边

两个案例的答案**方向相反**，而这正说明判据是真的判据，不是偏好：

**建筑块角色留注解**，因为注解是**唯一覆盖全部角色**的那套：`@ValueObject` / `@Repository` /
`@Service` / `@Identity` 根本没有接口对应物。两个接口只是一套完整词汇表的**局部影子**。
而且它们在实践中不可见——core 之外**零使用**（整个脚手架也是零），
两个仓储基类都绑在 `AbstractAggregateRoot<?>` 上、从不绑接口。
`Entity<ID>` 唯一贡献的 `ID id()` 已挪到 `AbstractAggregateRoot` 上声明为抽象方法。

**领域事件留接口**，理由恰好相反：**它必须是个类型**。
`registerEvent(DomainEvent)` 拿它当参数、`domainEvents()` 返回 `List<DomainEvent>`——
注解在这两个签名里都出现不了。`@DomainEvent` 命名的角色接口已经命名了，而且它干不了那个活。

顺带删掉的：`fixture/annotated` 整个包（存在的唯一目的是证明规则能抓住注解那条路径）、
以及只用来演示"注解路径 + 位置正确"的 `GoodOrderConfirmed`（`GoodOrderPlaced` 已覆盖接口路径）。

**对消费方的迁移成本：零。** 被删的三个类型在库外使用次数都是 0。

## 二、六个模块把 `runtime` 说成 `compile`（已改）

`-cqrs-spring-boot-starter` / `-events-spring-boot-starter` / `-operation-log-engine` /
`-outbox-engine` / `-process-manager-engine` 在 `src/main` 里**只**引用
`com.aipersimmon.ddd.core.id.IdGenerator`——那是 **core** 里的 SPI，不是这个 starter 里的东西。
它们对 starter 一行代码都没引用。

但依赖本身不是多余的：**bean 必须在运行时存在**，否则 id 退回 `UUID.randomUUID()`，
重新引入这个 SPI 当初就是为了消除的随机键写放大，且**没有任何启动信号**（`issue-00053`）。
所以正确的作用域是 `runtime`——它把这件事说准了，而且**照样传递到应用的 classpath**
（已用只依赖 `-outbox-jdbc` 的解析实测：`aipersimmon-ddd-id-spring-boot-starter:runtime`
连同 `java-uuid-generator:runtime`）。`compile` 声称了一个从来不存在的代码依赖。

`-outbox-jdbc` 与 `-outbox-mybatis-plus` 的那条**直接删掉**：它们依赖的 engine 已经带着它。

## 三、`ModuleNamingChecks` 改用 DOM 解析（已改）

报告说它"用正则解析 pom，分不清 `<dependencyManagement>` 与注释掉的 XML"。核实：
`<dependencyManagement>` 那条其实**已经**用 `replaceFirst` 处理了；**注释掉的依赖那条是真的**——
按文本读，一个被注释掉的 `<dependency>` 和一个活的长得一模一样。

改用 JDK 自带的 DOM（与第 11 项 `BomExportsOnlyItsOwnModulesTest` 同法，不引依赖）。
新增两例，其中"注释掉的依赖不算依赖"**在旧实现上按预期失败**（负向对照已跑）。

理由不只是正确性：**一条会报告构建实际没做的事的规则，会教会人不再相信它**——那个代价比规则本身的价值大。

## 四、"48 → 20 个模块"：这个目标本身需要驳回

报告把 47 个 pom 对 2.8 万行称为"过度碎片化"。先把 48 个按角色分类：

| 角色 | 个数 |
|---|---|
| 持久化后端（`-jdbc` / `-mybatis-plus` / `-redis`） | **13** |
| 装配 / 传输（`-spring-boot-starter` / `-messaging-kafka`） | **12** |
| 契约（框架无关） | **12** |
| 工具（bom / quality-config / archunit / test-support） | 4 |
| **打包束**（空 pom，纯聚合） | 4 |
| engine | 3 |

要砍到 20，只有两条路，**两条都会让库变差，而且两条都被本次评审自己新加的门禁禁止**：

1. **合并 jdbc 与 mybatis-plus 后端**（13 → ~6）。后果是**只用 JDBC 的应用被迫背上 MyBatis-Plus**。
   而"消费者自选恰好一个 outbox 存储 starter"是 `design-00001` 反复写下的决定。
2. **把契约与装配合并**（12 + 12 → 12）。后果是 Spring 进入契约模块——
   而第 12 项刚加的 `ContractModulesCarryNoFrameworkTest` 会**按字节码**让构建失败，
   `ModuleNamingChecks.contractModulesNamingAFramework` 也会按 pom 让它失败。
   那两道门禁正是为了守住"应用的内层编译时看不见框架"这件事。

**而且计数所描述的那个问题，4 个打包束早已解决**：消费方按 README 加**一个**依赖
（`aipersimmon-ddd-starter-mybatis-plus`），从不需要认识另外 47 个。
模块数是**发布粒度**，不是消费方的认知负担——把它当后者来读，才会得出 20 这个数字。

**唯一真正的候选**：`aipersimmon-ddd-inbox`（42 行，1 个接口 `Inbox.alreadyProcessed`），
可以并入 `-integration`（inbox 本就是集成事件的关注点，且 4 个依赖方里的 `-messaging-kafka`
已经依赖 `-integration`）。收益是 48 → 47，代价是一个已发布坐标消失。
**这一条留给用户决定**，因为它改的是发布物而不是实现，而 1/48 的收益撑不起我替他做这个决定。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（§2 架构层最后三条、§3 第 13 项）
- 驳回模块合并的直接依据是本次评审自己新加的门禁：
  [[issue-00113-the-quality-gates-sat-where-the-risk-was-not]]
- "消费者自选恰好一个存储后端"：[[design-00001-aipersimmon-ddd-and-scaffold]]
- id SPI 必须响亮而非静默退化：[[issue-00053-id-generator-silently-degrades-to-uuidv4]]
- DOM 解析 pom 的同法先例：[[issue-00112-the-bom-republished-every-pin-the-library-builds-against]]
- 模块命名与 Spring 自由：[[design-00012-module-naming-and-spring-freedom]]
