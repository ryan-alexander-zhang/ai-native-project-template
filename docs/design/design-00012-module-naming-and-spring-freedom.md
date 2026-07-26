---
id: design-00012-module-naming-and-spring-freedom
type: design
role: main
status: active
parent: plan-00014-adoption-threshold-and-architecture-simplification
---

# 模块命名规则与「Spring 自由」的可执行边界

承接 [[plan-00014-adoption-threshold-and-architecture-simplification]] 的 C5（报告 P1-2）。发布 Maven
archetype 之前是最后一个免费重命名窗口，所以规则必须现在定下来并**变成断言**，否则半年后必然再次漂移。

## 一、报告的规则按字面执行不可行

[[report-00001-ddd-framework-review]] P1-2 提出三段式：

```
aipersimmon-ddd-<domain>                        纯契约，零 Spring
aipersimmon-ddd-<domain>-<backend>              存储/传输适配
aipersimmon-ddd-<domain>-spring-boot-starter    带 AutoConfiguration.imports 的装配层
```

把它当字面规则执行会立刻矛盾。实测 42 个模块中，**每一个后端适配器都同时携带适配器代码和自己的
`AutoConfiguration.imports`**：

| 模块 | 适配器代码 | AutoConfiguration.imports |
| --- | --- | --- |
| `-inbox-jdbc` | `JdbcInbox` | ✓ |
| `-outbox-mybatis-plus` | writer / relay | ✓ |
| `-process-manager-jdbc` | 四个 store | ✓ |
| `-web-store-redis` | 三个 store | ✓ |
| `-messaging-kafka` | dispatcher + listener | ✓ |
| （其余 8 个后端模块同理） | | ✓ |

若坚持「装配层必须是独立的第三段」，这 13 个模块每个都要裂成 `-x-<backend>` + `-x-<backend>-spring-boot-starter`，
**42 个模块变成约 60 个**。而这与同一份报告的 P1-1（把使用者要拼的 17 个依赖降到 2 个）方向相反：使用者手工
挑选时要挑的东西翻倍，starter 的聚合清单也翻倍。

所以规则要重新表述，而不是照抄。

## 二、真正要守的不变量是什么

问「根 pom 那句话是为了什么」——"Only the pluggable modules are permitted to depend on Spring"。它的目的不是
「名字里要有 spring」，而是**领域代码必须能在不引入框架的前提下编译**。这才是不变量：

> **一个领域层可以依赖的模块，必须零 Spring。**

后端适配器不在这个集合里——领域层从不依赖 `-inbox-jdbc`，基础设施层才依赖。它们正是根 pom 说的
"pluggable modules"，携带 Spring 是**被豁免的、正确的**。真正违规的是**契约模块携带 Spring**，因为契约模块
恰恰是领域层要依赖的东西。

按这个标准重测，42 个模块里真正的违规者只有一个：

- **`aipersimmon-ddd-outbox`** —— 它同时是 `OutboxMessage` / `OutboxDispatcher` / `FailureClassifier` 的契约家园
  （`-outbox-jdbc`、`-outbox-mybatis-plus`、`-messaging-kafka` 都依赖它）**和** Spring 装配的家园
  （`AipersimmonDddOutboxAutoConfiguration`、`OutboxProperties`、`InProcessOutboxDispatcher`）。

报告点名的另外三个（`-id`、`-operation-log-engine`、`-process-manager-engine`）**不是**这类违规：

- `-id` 不含任何契约（`IdGenerator` 接口在 `core`），它只是「默认实现 + 装配」。领域层依赖 `core`，不依赖 `-id`。
- 两个 `-engine` 是**存储无关的运行时**，不是契约：契约在 `-operation-log` / `-process-manager`（均零 Spring）。
  领域层依赖后者。

它们的问题不是违反不变量，而是**名字没有传达自己是什么**。这是一个可用性问题，不是正确性问题——要分开处理。

## 三、规则（定稿）

### 3.1 后缀语义（名字回答「这是什么」）

| 后缀 | 含义 | 允许 Spring | 领域层可依赖 |
| --- | --- | --- | --- |
| 无技术后缀（`-core` `-cqrs` `-outbox` `-inbox` `-tenancy` `-web` `-integration` `-application` `-operation-log` `-process-manager` `-observability`） | **纯契约**：端口、值对象、状态机 | **禁止** | ✓ |
| `-<backend>`（`-jdbc` `-mybatis-plus` `-redis` `-kafka`） | **技术适配器**：实现某个契约，含自身自动配置 | 允许 | ✗ |
| `-<engine>` | **存储无关运行时**：调度、租约、重试，跨后端共用 | 允许 | ✗ |
| `-spring-boot-starter` | **纯装配**：不新增契约、不含适配器，只做 bean 装配与依赖聚合 | 允许 | ✗ |

关键区分：`-<backend>` 与 `-spring-boot-starter` 的差别**不是**「有没有自动配置」（两者都有），而是
**有没有实现代码**。适配器带实现；starter 只有 `@AutoConfiguration` 与 pom 聚合。这个区分是可判定的，
所以能变成断言。

### 3.2 重命名清单

**必做 —— 消除双后缀（一个角色两种叫法，使用者无法从名字推断）**

| 现名 | 新名 | 理由 |
| --- | --- | --- |
| `-cqrs-spring` | `-cqrs-spring-boot-starter` | 纯装配（`RegistryCommandBus` 是实现，但它装配的是 `-cqrs` 的契约；见下方裁定） |
| `-events-spring` | `-events-spring-boot-starter` | 纯装配 |
| `-tenancy-spring` | `-tenancy-spring-boot-starter` | Web 边界过滤器 + 装配 |
| `-web-spring` | `-web-spring-boot-starter` | HTTP 适配 + 装配 |
| `-flyway` | `-flyway-spring-boot-starter` | 纯装配（无契约、无实现，只按 classpath 应用迁移）；现名甚至看不出它是 Spring 的 |
| `-id` | `-id-spring-boot-starter` | 默认 `IdGenerator` 实现 + 装配；契约在 `core` |
| `-operation-log-cqrs-spring` | `-operation-log-cqrs-spring-boot-starter` | 同上；本表初稿遗漏了它，改为按 `artifactId` 全量扫描 `-spring$` 得出清单后补入 |
| `-mybatis-plus` | `-mybatis-plus-spring-boot-starter` | 多组件共享的 `InnerInterceptor` 装配座；现名读起来像「什么都不适配的 mybatis-plus 适配器」|

`-observability-otel-spring-boot-starter` 与 `-openapi-spring-boot-starter` 已符合，不动。

**必做 —— 拆分唯一的真违规者**

| 动作 | 内容 |
| --- | --- |
| `-outbox` 保留 | `OutboxMessage`、`OutboxDispatcher`、`FailureClassifier`、`DefaultFailureClassifier`、`RetryBackoff`、`DeadLetterStore`、`OutboxProperties`（纯配置载体）→ **零 Spring** |
| 新建 `-outbox-spring-boot-starter` | `AipersimmonDddOutboxAutoConfiguration`、`LoggingOutboxDispatcher`、`InProcessOutboxDispatcher`、`IntegrationEventScanner`（依赖 `BeanFactory`）|

**不做 —— 并说明理由**

- `-persistence-jdbc` / `-persistence-mybatis-plus`：已符合 `<domain>-<backend>`。它们携带 Spring 是被豁免的。
- 13 个后端适配器一律不拆 starter：见第一节，那是与 P1-1 相反的方向。
- 两个 `-engine`：`-engine` 是第四种后缀，但它表达的东西（存储无关运行时）真实存在且没有更好的词；
  改成 `-spring-boot-starter` 是错的（它们含大量实现代码），改成 `-<backend>` 也是错的（它们与后端无关）。
  **保留 `-engine`，并把它写进 3.1 的后缀表**，使其从「例外」变成「规则的一部分」。

### 3.3 一个需要裁定的边界：`-cqrs-spring` 含实现代码

`RegistryCommandBus` / 四个 `CommandInterceptor` 是实现，不是纯装配。按 3.1 严格判定，它该叫
`-cqrs-<backend>`——但「backend」是 Spring 本身，写成 `-cqrs-spring` 就回到了要消除的双后缀。

裁定：**`-spring-boot-starter` 涵盖「以 Spring 为实现技术的适配 + 装配」**，即把 Spring 视为一种 backend 时，
其适配器与 starter 合并为同一个模块。理由：Spring 是本框架的**唯一**装配技术（不像存储有 jdbc/mybatis-plus 两种），
为它单独区分「适配器」与「starter」不会带来任何选择自由，只会多一层模块。这条裁定必须写进后缀表的脚注，
否则下一个人会重新纠结同一件事。

## 四、断言（否则半年后再次漂移）

在 `aipersimmon-ddd-archunit` 之外增加一条**构建期**断言，因为要检查的是 **pom 依赖**而非字节码：Maven Enforcer
的 `banTransitiveDependencies` 不适用，改用 `enforcer` 的 `evaluateBeanshell` 或一个专用测试。

选定方案：在 `aipersimmon-ddd-archunit` 里加一个**读 pom 的测试**（`ModuleNamingRulesTest`），断言：

1. artifactId 不含 `-jdbc` / `-mybatis-plus` / `-redis` / `-kafka` / `-engine` / `-spring-boot-starter`
   后缀的模块，其 `compile`/`provided` 依赖中**不得出现 `org.springframework*` 或 `com.baomidou`**。
2. 不存在以 `-spring` 结尾（而非 `-spring-boot-starter`）的 artifactId —— 双后缀不得复活。

第 1 条是不变量，第 2 条是命名纪律。两条都要能在故意破坏时变红——这是验收条件的一部分。

## 五、影响面

- 8 个模块目录重命名 + artifactId（`-cqrs-spring`、`-events-spring`、`-tenancy-spring`、`-web-spring`、
  `-operation-log-cqrs-spring`、`-flyway`、`-id`、`-mybatis-plus`）
- 1 个模块拆分（`-outbox`）
- 反应堆 `<modules>`、BOM 全量条目、模块间依赖引用、样例 `start/pom.xml` 与各上下文 pom
- `AutoConfiguration.imports` 的**内容不变**（类的全限定名不变——Java 包名不随 artifactId 改）
- 数据库迁移路径不变（`aipersimmon/db/migration/...` 与 artifactId 无关）

**Java 包名不改**：`com.aipersimmon.ddd.cqrs.spring` 等保持原样。artifactId 是分发单元的名字，包名是代码的名字，
二者不必一致；同时改会把一次可机械验证的重命名变成一次大规模源码改动，风险不成比例。

## 关联

- [[plan-00014-adoption-threshold-and-architecture-simplification]]（C5）
- [[report-00001-ddd-framework-review]]（P1-2，本设计修正了其规则表述）
- [[design-00011-aggregate-persistence-contract]]（`-mybatis-plus` 装配座的来源）
