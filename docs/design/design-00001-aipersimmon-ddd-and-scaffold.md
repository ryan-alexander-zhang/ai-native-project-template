---
id: design-00001-aipersimmon-ddd-and-scaffold
type: design
status: active
---

# aipersimmon-ddd 库 + Maven-archetype 脚手架：最终形态与 Phase 1 设计

把分析阶段的结论落成**可构建的设计**。承接 [[analysis-00006-ddd-building-blocks-library]]
（构件库按 Layer × 可插拔性切分、参考不依赖、拓扑无关）、[[analysis-00007-saga-process-manager]]
（saga 分档）、[[analysis-00004-bounded-context-module-structure]]（三种拓扑）、
[[decision-00005-package-per-aggregate]]（domain 包结构)。

分析阶段结束,`bc-and-layer-samples/` 是分析期 demo,**最终删除**——由 archetype + scaffold-samples 取代。

## 一、最终形态：mono-repo,三顶层目录,两个独立 reactor

```
<repo root>
├── aipersimmon-ddd/                 [独立 reactor] 发布型 DDD 库(analysis-00006 模块集)
│   ├── pom.xml                      parent + aggregator(framework-free,NOT spring-boot-parent)
│   ├── aipersimmon-ddd-bom/         消费者 import 的 BOM
│   ├── aipersimmon-ddd-core/        纯净:注解 / marker / 基类 / Transitions
│   └── aipersimmon-ddd-archunit/    可复用 ArchUnit 规则(test)
│                                    (-application / -integration / *-starter 后续阶段)
│
├── aipersimmon-ddd-scaffold/        [独立 reactor] 三个**手写参考项目** → 各自派生一个 archetype
│   ├── multi-module/                ← Phase 1:双 BC、可运行的参考项目,create-from-project 的源
│   │   ├── ordering/                BC(多聚合):ordering-{api,domain,application,infrastructure,adapter}
│   │   ├── inventory/               BC(单聚合):inventory-{api,domain,application,infrastructure,adapter}
│   │   └── start/                   @SpringBootApplication 装配双 BC + 架构测试
│   ├── modulith/                    ← Phase 4:单模块 modular monolith(BC/层=包,边界测试期强制)
│   └── microservice/                ← Phase 4:每 BC 独立部署(contracts + 两服务 + e2e),跨服务走 Kafka
│
└── aipersimmon-ddd-scaffold-samples/  一组**聚焦单点 how-to** 的小例子(如"加一个集成事件"
                                        "加 outbox""接一个 saga"),各讲清一件事;不是大而全的应用
```

- **两个 reactor 相互独立**:库与脚手架发布节奏不同,各自 `mvn` 构建;scaffold 通过依赖坐标引用**已发布/已 install** 的库。
- **groupId `com.aipersimmon.ddd`,基础包 `com.aipersimmon.ddd.*`**;生成项目的 groupId/package 由 archetype 属性给(默认 `com.example.app`,创建时可改)。
- **生成的项目"依赖"库 BOM,不拷源码**(analysis-00006 铁律)。
- **CI/CD 发布到 GitHub Packages:后续**,本设计不展开。

## 二、贯穿性设计约束

1. **库 parent 必须 framework-free**:`aipersimmon-ddd/pom.xml` **不继承** `spring-boot-starter-parent`,只设 Java 21 + 插件 + 内部 dependencyManagement。只有后续 `*-spring` starter 才引 Spring。否则 `-core` 不再零依赖,违反 analysis-00006。
2. **版本治理**:`-bom` 管住所有 `aipersimmon-ddd-*` 版本;消费者只 `import` 这一个。
3. **拓扑无关**:同一套库,三种 archetype 复用;差异只在打包与消息传输(analysis-00006 §七)。
6. **参考项目优先用库能力(不手写替代)**:三套 archetype 源(multi-module / modulith / microservice)是"标准范例",凡库已封装的能力一律采用——domain 词汇(`@AggregateRoot`/`@Entity`/`@ValueObject`/`@Repository`/`@Identity`/`Identifier`/`Transitions`)、分层 stereotype(`@*Layer`)、端口(`DomainEvents`/`IntegrationEvents`)、Process Manager、以及 **CQRS**:写用例经 `CommandBus`(具体 `CommandHandler`),读用例经 `QueryBus`(`@ReadModel`)。how-to 反之刻意扁平、聚焦单点,不代表标准结构。
4. **Java 21 / Maven 3.9**;编码 UTF-8;`maven.compiler.release=21`。
5. **每个 Java package 必须有 `package-info.java`**:承载包级 Javadoc 与分层 stereotype 注解(`@DomainLayer` 等标注于此),并让"包意图"显式。由 `-archunit` 校验存在性(§5.4)。适用于库、参考项目与生成项目的所有包。

## 三、库 reactor 模块依赖图(analysis-00006 落地)

```mermaid
flowchart TD
  subgraph pure["纯净层 framework-free"]
    core["aipersimmon-ddd-core"]
    app["aipersimmon-ddd-application"]
    integ["aipersimmon-ddd-integration"]
    cqrs["aipersimmon-ddd-cqrs"]
    processManager["aipersimmon-ddd-process-manager"]
  end
  subgraph starter["可插拔 starter (Spring/JPA/Kafka)"]
    events["-events-spring"]
    outbox["-outbox (contracts) / -outbox-engine<br/>-outbox-mybatis-plus"]
    inbox["-inbox-mybatis-plus"]
    msg["-messaging-kafka / -rabbit"]
    cqrsSpring["-cqrs-spring"]
    processManagerJdbc["-process-manager-engine<br/>/ -process-manager-mybatis-plus"]
  end
  arch["aipersimmon-ddd-archunit (test)"]
  bom["aipersimmon-ddd-bom"]

  app --> core
  integ --> core
  cqrs --> core
  processManager --> core
  processManager --> cqrs
  processManager --> integ
  events --> app
  outbox --> app
  inbox --> app
  cqrsSpring --> cqrs
  processManagerJdbc --> processManager
  processManagerJdbc --> app
  arch --> core
  bom -. 管理版本 .-> core
  bom -. 管理版本 .-> arch

  classDef p1 fill:#dff0d8,stroke:#3c763d;
  class core,arch,bom p1
```

> 绿色 = **Phase 1 交付**(`-bom` / `-core` / `-archunit`);其余为后续阶段。

## 四、分阶段计划

| 阶段 | 交付 | 说明 |
| --- | --- | --- |
| **Phase 1** | `-bom` → `-core` → `-archunit`(**按此序,一个一个做**)+ `multi-module` archetype + scaffold-samples | 先把"库依赖 + 分层 + arch 校验"跑通;archetype 依赖上述库子集 |
| Phase 2 | `-application` / `-integration` + `-events-spring` / `-outbox` / `-inbox` | 事件与 outbox/inbox 上移进库 |
| **Phase 3 ✅** | `-cqrs(+spring)` / legacy `-saga(+spring)` | CQRS 与旧 saga 基线已交付；durable Process Manager 的 clean-slate 目标见 [[design-00004-durable-process-manager-runtime]] |
| **Phase 4 ✅** | `modulith` / `microservice` / CI+GitHub Packages | 全部交付并验证生成 `com.acme.shop` 全绿:`modulith`(单模块 modular monolith,边界测试期强制);`microservice`(`contracts` 共享契约 + `ordering-service`/`inventory-service` 独立部署 + `e2e-tests`,跨服务走 outbox→Kafka→inbox,EmbeddedKafka 端到端 2/2);CI(`ci.yml` 构建库+三脚手架+样例)+ 发布(`publish-library.yml` + `distributionManagement` → GitHub Packages) |

**依赖顺序注意**:archetype 生成的项目要能解析 `aipersimmon-ddd-*`,故库子集必须先 `mvn install` 到本地 `.m2`。Phase 1 内部次序:①库 `bom→core→archunit`;②**手写双 BC 参考项目 `scaffold/multi-module`**(建立在库之上);③从它 `create-from-project` 派生 archetype 并验证生成/回归;④按需补 `scaffold-samples` 的聚焦 how-to 例子。

## 五、库模块详细设计(Phase 1:5.1–5.4;Phase 2:5.5–5.9;Phase 3:5.10–5.13)

### 5.1 `aipersimmon-ddd/pom.xml`(parent + aggregator)

- `groupId=com.aipersimmon.ddd`、`artifactId=aipersimmon-ddd-parent`、`version=0.1.0-SNAPSHOT`、`packaging=pom`。
- **不继承** spring-boot-parent。`properties`:`maven.compiler.release=21`、`project.build.sourceEncoding=UTF-8`、`archunit.version`。
- `dependencyManagement`:声明 `archunit-junit5`(供 `-archunit` 用),内部模块版本用 `${project.version}`。
- `modules`:随阶段追加。Phase 1 逐步为 `aipersimmon-ddd-bom` → `+core` → `+archunit`。

### 5.2 `aipersimmon-ddd-bom`(第一步)

- `packaging=pom`,parent 指向上面的 parent。
- `dependencyManagement` 列出 Phase-1 构件坐标(`-core`、`-archunit`,版本 `${project.version}`);后续模块随阶段追加。
- 消费者(生成项目)`<dependencyManagement><scope>import</scope>` 引它即可对齐版本。

### 5.3 `aipersimmon-ddd-core`(第二步,零依赖)

包结构(承接 analysis-00006 §三 + §十):

```
com.aipersimmon.ddd.core
├── annotation/    @AggregateRoot @Entity @ValueObject @Repository @Identity @DomainEvent @Service
├── architecture/  @DomainLayer @ApplicationLayer @InfrastructureLayer @InterfaceLayer  (hexagonal 可选)
├── model/         AggregateRoot<ID>  Entity<ID>  Identifier  Association<T,ID>  AbstractAggregateRoot
├── event/         DomainEvent (marker)
├── state/         Transitions<S>  IllegalStateTransitionException   (analysis-00006 §十)
└── exception/     DomainException
```

- `AbstractAggregateRoot`:迁移 repo 现有 `shared-kernel/AggregateRoot`(事件登记/清空)并验证零 framework 依赖。
- `Transitions<S>`:analysis-00006 §十 已给出完整实现与 demo,直接落地。
- **`pom.xml` 无任何 `dependencies`**(除测试 `junit-jupiter`)——这是 `-core` 的验收红线。

### 5.4 `aipersimmon-ddd-archunit`(第三步)

- 依赖 `-core`(识别注解/marker)+ `archunit-junit5`(**compile** 依赖,消费者以 test scope 引 `-archunit` 即可传递获得)。
- 提供可复用 `ArchRule` 常量 + 一个无参聚合入口 `AiPersimmonDddRules.all()`(打包所有 framework-agnostic 规则;跨上下文隔离因需基包参数,另走 `BoundedContextRules.dependOnEachOtherOnlyThroughApi(basePackage)`)。规则集示例(权威清单见 `AiPersimmonDddRules` 类级 Javadoc):
  - domain 不得依赖 application / infrastructure / adapter / 任何 framework(已落地,在 `all()`);
  - `IntegrationEvent` 只在 `*-api`(已落地,opt-in `integrationEventsShouldResideInApi`);`DomainEvent` 不得泄漏到 adapter(已落地,在 `all()`);
  - 跨聚合只经 `Association` / `Identifier` 引用聚合根(设计约定,暂未由 ArchUnit 强制)。
  - **每个 package 必须有 `package-info.java`**(§二 规约 5;由 `PackageInfoChecks` 源码级强制)。
- 消费项目写一个 `ArchitectureTest`,按其分层包命名约定套用规则。

---

**Phase 2 起,parent 的 `dependencyManagement` import `spring-boot-dependencies` BOM(3.5.10)**,让 starter 依赖 Spring/JPA/Jackson 时无需自己钉版本;**纯层不受影响**——import BOM 只管版本、不引入依赖,`-core`/`-application`/`-integration` 仍零框架。

### 5.5 `aipersimmon-ddd-application`(纯,→ `-core`)

- `DomainEvents` 发布 port(`publish` / `publishAll`);`ApplicationException` 基类。零框架(仅 test junit)。
- `DomainEvents` 与参考项目本地内联的同名 port **签名一致**,便于日后回收。

### 5.6 `aipersimmon-ddd-integration`(纯,零依赖)

- `IntegrationEvent` 标记(区别于 `-core` 的 `DomainEvent`);`EventEnvelope<T extends IntegrationEvent>`(`eventId`/`type`/`version`/`occurredAt`/`traceId`/`payload`),**构造即校验**;版本化契约约定写入 Javadoc。
- **纯数据持有**:不做序列化、不取时钟/随机——由 infra starter 在封装时盖章。

> **事件传输总览(需求)**:**领域事件只有同步进程内**;**集成事件三种方式**,共用 `IntegrationEvents` port,换实现即切换:
> - **方式一 进程内同步** → `SpringIntegrationEvents`(§5.7,`ApplicationEventPublisher`,无 outbox)。
> - **方式二 进程内异步 + outbox/inbox** → `OutboxWriter` + **进程内** `OutboxDispatcher`(§5.8)+ `Inbox`(§5.9)。
> - **方式三 broker + outbox/inbox** → `OutboxWriter` + **broker** `OutboxDispatcher`(`-messaging-kafka`,**已交付**,见 §5.14)+ 消费端 `Inbox`。

### 5.7 `aipersimmon-ddd-events-spring`(starter,→ `-application` + `-integration` + Spring)

- **领域事件**:`SpringDomainEvents`(委托 `ApplicationEventPublisher`);Boot 自动装配(`AutoConfiguration.imports`),引入即生效。
- **集成事件·方式一(进程内同步)**:`SpringIntegrationEvents`(同样委托 `ApplicationEventPublisher`)。自动装配用 `@ConditionalOnMissingBean(IntegrationEvents)` + `@ConditionalOnMissingClass(OutboxWriter)` 守卫——**仅当 outbox 不在 classpath 时兜底**,保证"outbox 在→走 outbox"的确定性,用户始终可自定义 bean 覆盖。
- **语义(承接 analysis-00001):默认同步、同线程、同事务**——发布在 `@Transactional` 内调用,`@EventListener` 处理器内联执行、与聚合原子提交。领域事件**此处不可异步**。
- 消费者用 `@EventListener` / `@TransactionalEventListener` 注册处理器。

### 5.8 `aipersimmon-ddd-outbox-jdbc`(starter,→ `-application` + `-integration` + `spring-boot-starter-jdbc` + Jackson)

> **实现阶段发现**:库里放 JPA `@Entity` 有"实体扫描覆盖"陷阱——库的 `@EntityScan` 会让使用者靠默认扫描的自有实体失效。故**先做 `-outbox-jdbc`**（该模块后已删除，只留 `-outbox-mybatis-plus`）(`JdbcTemplate`,无 `@Entity`/`@EntityScan`,零扫描冲突);`-outbox-jpa` 作为后续变体。发布 port `IntegrationEvents` 已加到 `-application`。

> **第二次演进(已交付,[[decision-00020-outbox-engine-over-one-store-port]])**:writer / relay / 调度触发器 /
> 保留期清理与共享的 Spring 装配再从两个后端上抽到 **`aipersimmon-ddd-outbox-engine`**,后端只留一个
> `OutboxStore` 适配器 + 死信 store/读侧 + ShedLock 的 `LockProvider`。此前 relay 在两个后端各一份,
> 而那份代码承载着按聚合顺序、mark-sent 不计重试预算、死信搬移失败不计尝试这三条各自换来一个 issue 的判断——
> 存两份的代价是任何一次修正都可能只落在一半的部署上。与 `-process-manager-engine` / `-operation-log-engine` 同形。

> **第三次演进(已交付,[[issue-00108-a-killed-relay-instance-stops-all-delivery]])**:relay 的互斥从**调度**
> 移到**行**。原来只有 `OutboxRelayScheduler.poll()` 上的 `@SchedulerLock`,而被杀的实例解不开那把锁,
> 其余实例便一路静默跳过轮询——最长 `PT60M` 全线停摆;那 60 分钟又是被最坏批次预算
> (`batch-size × send-timeout`)顶上去的,一个旋钮同时管着"轮询多久"与"崩溃多久恢复"。现在每行带
> `lease_owner`/`lease_token`/`lease_until`,**所有实例并发轮询**各领互不相交的行,`@SchedulerLock` 从 relay
> 撤掉(仅 cleanup 还用 ShedLock)。有序性随之从"批内记账"改为**只有聚合队头可领**的存储谓词——
> 活在单节点内存里的保证撑不住并发 poller。轮询自带"半个租约"的时间预算,于是租约长度只表示
> "崩溃后多久有人接手",而 Kafka 启动守卫的算式里 `batch-size` 整项消失。

> **第四次演进(已交付,[[issue-00109-a-vanished-route-turned-an-externalized-event-local]])**:
> 事件的**目的地**也成为行上的一列。原来 reach 在派发时按当前 `@Externalized` 注解重新判定,
> 于是版本升级漏标或滚动发布期间路由表 miss → 落进程内腿 → 正常返回 → 标记已发送,永不到 broker 且
> 全程无迹象。现在 writer 在写入事务里解析并落 `destination`(NULL = 进程内),`aipersimmon_dead_letter`
> 同样带这一列(否则重放会把外发事件复活成本地投递)。查询端口 `EventDestinations` 在 outbox core,
> `ExternalizedRoutes` 实现之;relay 另守一条:带目的地的行不得交给到不了外部的 dispatcher。

> **第五次演进(已交付,[[issue-00111-the-relay-waited-for-each-send-in-turn]])**:一轮 poll 的代价从
> **往返之和**降到**一次往返**。原来每条消息 `send.get(timeout)`——写一条等一条 ack,单实例上限约 100 msg/s,
> 且 producer 缓冲区里永远只有一条记录,自带的批处理形同虚设。现在"交出去"与"等回执"是两件事
> (`OutboxDispatcher.beginDispatch` 返回 `InFlightDispatch`),relay 把**整批**交给传输再逐个等,
> 确认下来的行**一条语句**记账(`OutboxStore.markSent` 收 id 列表)。默认实现仍是同步 `dispatch`,
> 自定义传输零改动。**关键前提是第三次演进**:队头 claim 使得一批 claim 出来的行两两不同 subject,
> 于是批内根本没有需要保序的两条消息——报告原本要求的"按序等 + 首个失败 fail-fast"因此是多余的,
> 而且有害(会丢下已交给 broker 的 send 不等,凭空造重复)。`sendTimeout` 的起算点改为**交出去那一刻**,
> 整批停摆只花一个 timeout,第三次演进立下的租约算式原样成立。追踪侧因此给
> `StoreAndForwardTracer.Scope` 加了 `detach()`(离开当前线程但不结束 span),否则要么交出去就结束 span
> (失败的投递在链路里显示成功),要么 N 个 scope 同时开着按 FIFO 关闭(OTel 上下文错乱)。

> **后续演进(已交付)**:抽出与存储无关的 **`aipersimmon-ddd-outbox`(core)**——投递契约 `OutboxDispatcher`、存储消息 `OutboxMessage`、两个默认 dispatcher(logging / in-process)及其选择用的 `AipersimmonDddOutboxAutoConfiguration`,全无持久化。`-outbox-jdbc` 与新增的 **`-outbox-mybatis-plus`** 都依赖该 core,各自只提供 writer + relay(`-outbox-mybatis-plus` 用 MyBatis-Plus `BaseMapper`,`@TableName` 而非 JPA `@Entity`,且只经 `MapperFactoryBean` 注册自己的 mapper,不触发/劫持消费者 `@MapperScan`,同表结构可与 jdbc 互换)。**消费者需自选恰好一个 outbox 存储 starter**。broker starter(§5.14)改为依赖 core,故可与任一存储后端组合。

- **事务性 outbox**:集成事件与聚合变更**同事务**写入 `aipersimmon_outbox` 表;relay 轮询未发送行,发到 broker,置 `sent`。**at-least-once**(dispatch 后置 sent 前崩溃会重投 → 消费方需幂等)。
- 组件:
  - 表 `aipersimmon_outbox`:`id`/`event_id`(唯一)/`type`/`version`/`payload`(JSON)/`occurred_at`/`trace_id`/`sent`/`sent_at`/`attempts`/`created_at`。建表由消费者(Flyway/Liquibase)负责。全库各存储组件的 DDL 以**分方言 Flyway migration** 为**单一来源**(`aipersimmon/db/migration/{component}/{vendor}`,h2/postgresql/mysql),既是可执行迁移也是参考 DDL。outbox 的 migration 放在 `aipersimmon-ddd-outbox` core,`-outbox-jdbc` 与 `-outbox-mybatis-plus` 共享同一份(两者表结构一致)。可选模块 **`aipersimmon-ddd-flyway`**(与 schema 无关的共享 starter)在启动时扫描 classpath,为发现的每个组件用**独立历史表**(`flyway_schema_history_aipersimmon_{component}`)自动应用(见其 README),或复制进消费者自己的 Flyway/Liquibase;各模块测试直接复用对应 H2 migration(不再单独维护 `schema.sql`)。
  - `OutboxWriter implements IntegrationEvents`:盖章 `EventEnvelope`(eventId=UUID、type=类全名、version=1、occurredAt=now)→ Jackson 序列化 payload → **当前事务** `JdbcTemplate` 插入一行。
  - `OutboxRelay`:`@Scheduled` 轮询 → **claim** 可投递的行(每行打租约,每个聚合只取队头)→ **整批**交给 **broker 发布 port `OutboxDispatcher`**(`beginDispatch`)再逐个等回执 → 确认下来的一批**一条语句**置 `sent`(同时清租约);失败留待下轮 + `attempts++`,且只算那一行。所有实例都轮询,互斥靠行租约。
  - `OutboxDispatcher` port,三个实现选一(决定方式二/三):
    - **默认 `LoggingOutboxDispatcher`**(`@ConditionalOnMissingBean`,开箱即用,只记日志)。
    - **`InProcessOutboxDispatcher`(方式二)**:属性 `aipersimmon.ddd.outbox.dispatch=in-process` 启用;按 `type` 反序列化 payload 后 `ApplicationEventPublisher.publishEvent`,投递给进程内 `@EventListener`——outbox 变成"进程内异步"传输(生产者只在其事务里写 outbox,relay 异步投递本地消费者;配 `Inbox` 幂等)。
    - **broker dispatcher(方式三)**:由后续 `-messaging-kafka` 提供,覆盖默认。
  - **dispatcher 选择在 core**:`AipersimmonDddOutboxAutoConfiguration`(在 `aipersimmon-ddd-outbox`,与存储无关)注册 logging / in-process dispatcher;broker starter 排在它之前顶替。**存储装配在 `AipersimmonDddOutboxJdbcAutoConfiguration`**:`@AutoConfiguration(after={JdbcTemplateAutoConfiguration, 上述 core dispatch autoconfig})` + `@EnableScheduling`,只提供 writer + relay + clock,各 bean 用 `@ConditionalOnBean(JdbcTemplate)` / `@ConditionalOnMissingBean` 守卫。`-outbox-mybatis-plus` 同构(`after` MyBatis-Plus 的 `MybatisPlusAutoConfiguration` + core dispatch)。
- 决策:序列化 = Jackson;relay = `@Scheduled`(可配 `poll-delay-ms`/`batch-size`);broker = port;暂无 DLQ/最大重试(留 `attempts` 观测)。

### 5.9 `aipersimmon-ddd-inbox-jdbc`(starter,→ `-application` + `spring-boot-starter-jdbc`)

> 与 outbox 同理,做成 JDBC(无 `@Entity`/`@EntityScan`,零扫描冲突);`-inbox-jpa` 后续变体。**已交付 MyBatis-Plus 变体 `-inbox-mybatis-plus`**:`Inbox` port 的 MyBatis-Plus 实现(`BaseMapper` insert,重复 key → 已处理),只经 `MapperFactoryBean` 注册自己的 mapper、不劫持消费者 `@MapperScan`,无 JPA `@Entity`,与 jdbc 变体同表结构可互换。`Inbox` 契约在 `-application`,故 inbox 两变体彼此独立、无需 outbox core。

- **幂等消费**:`aipersimmon_inbox` 表以 `message_key` 为唯一主键记录已处理消息;消费在**同事务**内先调 `Inbox.alreadyProcessed(key)`——首次插入成功(返回 false,继续处理),重投时唯一键冲突(返回 true,跳过)。失败回滚则记录一并回滚,可重试。
- 组件:`Inbox` port(放 `-application`);`JdbcInbox`(靠唯一键 + `DuplicateKeyException` 判重);`AipersimmonDddInboxAutoConfiguration`(`@ConditionalOnBean(JdbcTemplate)`/`@ConditionalOnMissingBean`)。建表由消费者负责。inbox 无存储无关的 core,故新增 **`aipersimmon-ddd-inbox`** 模块承载共享的 inbox 表 DDL(`aipersimmon/db/migration/inbox/{vendor}` migration,单一来源),`-inbox-jdbc` 与 `-inbox-mybatis-plus` 都依赖它;由 `aipersimmon-ddd-flyway` 自动应用。
- 去重键 = 集成事件 `eventId`(来自 `EventEnvelope`)。

> **参考项目采纳(留待决定,倾向)**:`multi-module` base 保持内存 + 进程内(精简、可跑);starter 的用法由 `scaffold-samples` 的聚焦 how-to 演示("迁移到 outbox / events / inbox"),不把 base 参考项目复杂化。

### 5.10 `aipersimmon-ddd-cqrs`(纯,可选,→ `-core`)

承接 [[analysis-00006-ddd-building-blocks-library]] §五(纯/脏分离、CQRS 整体可选)。framework-free,只依赖 `-core`。

- **写侧**:`Command<R>` / `CommandHandler<C,R>`(薄 handler)/ `CommandBus.send`;`CommandInterceptor` 环绕 SPI(`Invocation<R>.proceed()` + `order()`,越小越外层)。
- **读侧**:`Query<R>` / `QueryHandler<Q,R>` / `QueryBus.ask`;`@ReadModel` / `@Projection` stereotype。
- **横切抽象**:`UnitOfWork`(事务边界 port)。领域事件不经旁路收集器:聚合自带事件,由保存它的一方在 save 处、同事务内 `DomainEvents.publishAndClear(root)` 排空(见 [[decision-00012-no-ambient-per-command-state]];**替代**原 `AggregateCollector`,补 JDBC/MyBatis 无 ChangeTracker 的同一问题)。
- 测试:契约级(泛型可组合 + 拦截器环绕/排序 + `UnitOfWork` 默认重载),3/3。

### 5.11 `aipersimmon-ddd-cqrs-spring`(starter,可选,→ `-cqrs` + `-application` + Spring)

analysis-00006 §五的实现侧(装饰器链 Logging→Validation→Transaction,`TransactionTemplate` 接管 UnitOfWork)。

- `RegistryCommandBus` / `RegistryQueryBus`:按 handler 泛型签名(`ResolvableType`)索引命令/查询类型;**handler 须是具体类**(lambda 会擦除泛型,无法索引)。
- 内置拦截器:`LoggingCommandInterceptor`(order 0)、`ValidationCommandInterceptor`(order 100,`@ConditionalOnClass/Bean(Validator)`,Bean Validation 存在才装配)、`TransactionCommandInterceptor`(order 200,只提供事务边界;领域事件由聚合在 save 处 `DomainEvents.publishAndClear` 同事务排空,拦截器不再集中 drain,见 [[decision-00012-no-ambient-per-command-state]])。
- `TransactionTemplateUnitOfWork`(领域事件排空不再依赖任何线程域收集器)。
- `AipersimmonDddCqrsAutoConfiguration`:`@ConditionalOnMissingBean` 全可覆盖;`@AutoConfiguration(after = {DataSourceTransactionManager/Transaction/ValidationAutoConfiguration})` 以正确评估 `@ConditionalOnBean`。测试:端到端(happy / 失败回滚且不投递事件 / 校验先于事务拒绝 / 查询侧),4/4。

### 5.12 `aipersimmon-ddd-process-manager`（纯契约）

`aipersimmon-ddd-saga`、`SagaState`、`SagaStore` 与独立 `DeadlineScheduler` 只代表已经交付的历史基线，不再是目标 API，
也不提供兼容迁移约束。新的 framework-free 核心以 `ProcessDefinition`、`ProcessDecision`、`ProcessEffect`、
`ProcessRuntime` port 和显式 codec SPI 为边界；规范设计见 [[design-00004-durable-process-manager-runtime]] §三。

### 5.13 JDBC Runtime 与 Spring Boot Starter

生产目标拆为 `aipersimmon-ddd-process-manager-jdbc`（后改名 `-engine` + `-mybatis-plus`）与
`aipersimmon-ddd-process-manager-jdbc-spring-boot-starter`：前者负责四表持久化、原子推进、effect relay、durable deadline、
租约/围栏、幂等与运维能力；后者只负责 Boot 自动装配、配置、worker 生命周期、可观测性和启动校验。两者的完整边界、
Temporal/Seata provider 策略及非规范性订单 Sample 统一以 [[design-00004-durable-process-manager-runtime]] 为准。

### 5.14 `aipersimmon-ddd-messaging-kafka`(starter,可选,→ `-outbox`(core)+ `-application` + spring-kafka)

集成事件**方式三(broker)**的落地(承接 §5.6 传输总览、[[analysis-00002-domain-vs-integration-events]])。**建立在 outbox 之上,不替代它**;仅依赖**存储无关的 outbox core**(`OutboxDispatcher`/`OutboxMessage` + dispatch autoconfig),因此可与任一存储后端(`-outbox-jdbc` / `-outbox-mybatis-plus`)组合——**消费者需另行自选一个 outbox 存储 starter**(此前经 `-outbox-jdbc` 传递依赖隐式带入,现改为显式)。

- **生产侧** `KafkaOutboxDispatcher implements OutboxDispatcher`:把 outbox 行发到 Kafka topic(key=eventId,value=payload JSON,信封元数据走 headers `IntegrationEventHeaders`);**阻塞等 broker ack** 才返回,失败抛错 → relay 不标记已发 → 下轮重试(at-least-once)。作为 `OutboxDispatcher` bean **自动顶替**日志默认实现(autoconfig `before` **core dispatch autoconfig** + `@ConditionalOnMissingBean`)。
- **消费侧(可选,`consumer.enabled=true`)** `KafkaIntegrationEventListener`:`@KafkaListener` 消费 topic,按 eventId 经 `Inbox` 去重(**同事务**),再用 type header + payload 重建事件、经 `ApplicationEventPublisher` **进程内重投**给本地 `@EventListener`——即"message 的 inbox"半边。无 `Inbox` 时不去重(要求处理器自身幂等)。
- 配置 `KafkaMessagingProperties`(`aipersimmon.ddd.messaging.kafka.topic` / `consumer.enabled`)。测试**不依赖实时 broker**:验证 dispatch 映射与失败传播、消费端重建+幂等去重、autoconfig 装配(7/7)。实时 broker 的端到端集成测试归消费方应用。

### 5.15 Web 层构件族(`-web` / `-web-spring` / `-web-store-{redis,jdbc}`)

interface(入站 HTTP)层构件族,详见 [[design-00002-web-layer]]:纯契约 `-web`(ProblemDescriptor/ProblemRegistry/ApiError/Page/Cursor +
横切 SPI)+ Spring starter `-web-spring`(异常→RFC 9457 ProblemDetail、traceId、分页、i18n、幂等/防重放/限流,
逐项 opt-in)+ 可换存储 `-web-store-redis`/`-web-store-mybatis-plus`(与 outbox 存储后端同构;后者当时叫 `-web-store-jdbc`)。策略见
[[decision-00007-web-api-response-envelope]],证据见 [[analysis-00008-web-api-response-envelope]]。

## 六、脚手架设计(`multi-module`)

**约定:archetype 从我们自己手写的参考项目 `aipersimmon-ddd-scaffold/multi-module` 派生,不碰只读的 `bc-and-layer-samples`(后者可读作知识参考,但不作输入、不提炼、不复制)。**

```mermaid
flowchart LR
  mm["scaffold/multi-module<br/>手写双 BC 参考项目(建立在库之上)"]
  mm -->|archetype:create-from-project<br/>-Darchetype.properties| arch["persimmon-scaffold-archetype 工件(派生物)"]
  arch -->|mvn archetype:generate| gen["生成的骨架项目"]
  gen -.->|回归:重生成 + 比对| arch
  samples["scaffold-samples<br/>聚焦单点 how-to 例子"] -.->|各自演示一项技术| mm
```

- **参考项目 `multi-module`(archetype 的源,真相源)**:一个**手写、可运行的双 BC** 多模块 DDD 项目,建立在 `aipersimmon-ddd-*`(BOM + core + archunit)之上,遵循 [[decision-00005-package-per-aggregate]] 的包结构。它**先由人手写**(可参考只读的 `bc-and-layer-samples`,但不复制),是 `create-from-project` 的**唯一输入**。
  - **两个 BC,至少一个多聚合**:`ordering`(多聚合:`Order` + `Customer`)、`inventory`(单聚合:`Stock`)。单 BC 不足以表达 BC 边界与跨 BC 协作。
  - **目录嵌套 `<bc>/<bc>-<layer>`,且 BC 目录是聚合 pom**:每个 BC 一个目录含一个聚合 `pom.xml`(`packaging=pom`,列出该 BC 的五层模块),根 pom 只列 `ordering` / `inventory` / `start`。**不可**用扁平的 `ordering-adapter`(退化的单 BC 写法)。BC 目录必须是**有 pom 的聚合模块**——否则 `create-from-project` 无法处理"无 pom 的分组目录"(见下)。
  - **内部模块依赖用 `${project.groupId}` / `${project.version}`**(reactor groupId 无关),而非写死 `com.example`——否则派生出的 archetype 生成项目时内部依赖仍指向 `com.example`(见下)。
  - **跨 BC 走进程内集成事件 + orchestration saga**(模块化单体,一个可部署单元):`ordering` 下单→发 `OrderPlaced`(ordering-api)→`inventory` 进程内预留并回报结果事件(`StockReserved` / `StockReservationFailed`,inventory-api)→`ordering` 的 **order-fulfilment 过程管理器**据此**确认**或**补偿(取消订单)**。**协调策略与投递机制分层**:编排逻辑 `OrderFulfilmentProcessManager`(`@ProcessManager`)落 **application 层**,持 `OrderFulfilmentSaga` 中心状态、经 `SagaStore` port(内存实现)存取、经 `CommandBus` 发下一步命令,入参只吃 correlation id(order id)故不依赖任何 `*-api` 契约、可脱离 transport 单测;`adapter/messaging` 的 `OrderFulfilment` 仅是**瘦投递壳**——只绑定 `@EventListener`、把事件拆成 order id 转交过程管理器,换 transport(如 Kafka consumer)只动此壳。此归属对齐 Vernon/Eventuate/Grzybek 实践(协调在 application、投递在 edge),并由 ArchUnit `applicationShouldNotDependOnInfrastructureOrInterface` 守卫。跨 BC **只经对方 `*-api`**。相较早期的无状态 `StockReservedListener`(choreography),此为**编排**:中心状态 + 显式补偿分支;deadline/超时不在同步进程内演示(见 scaffold-samples `orchestrate-with-saga`)。broker/outbox/幂等留后续阶段。**写/读用例经 CQRS 总线**:控制器与 saga 经 `CommandBus` 发 `PlaceOrder`/`ConfirmOrder`/`CancelOrder`、inventory 监听器发 `ReserveStock`(各具体 `CommandHandler`),读经 `QueryBus`(`FindOrder`→`@ReadModel` `OrderSnapshot`)。multi-module 无事务管理器(内存仓储)→ 命令总线仅路由+日志、无事务/嵌套隐患;microservice 有 DataSource → 命令总线含事务拦截器,跨服务异步(Kafka)天然无嵌套。三套拓扑一致。
- **archetype 工件(派生物,已验证)**:`mvn archetype:create-from-project -Darchetype.properties=./archetype.properties` 从 `multi-module` 派生;`archetype.properties` 指定派生工件坐标 `com.ryan.persimmon:persimmon-scaffold-archetype:0.0.1-SNAPSHOT` 与 `excludePatterns`(`**/target/**` 等)。随后 `mvn -f target/generated-sources/archetype/pom.xml install`。**`multi-module` 是真相源,archetype 是派生物**——改流程是改 `multi-module` 后重派生(archetype 产物在 `target/`,不提交)。
  - **四处 create-from-project 落地发现**:①它无法处理"无 pom 的分组目录"→ 把 BC 目录做成聚合 pom(上一条),派生的 `archetype-metadata.xml` 才正确嵌套。②它**不模板化依赖里的 groupId** → 内部依赖改用 `${project.groupId}`(上一条),生成项目才在使用者的 groupId 下解析成功。③**文件名里含根 artifactId 的路径段会被改写成 `__artifactId__`**,生成时展开成使用者的 artifactId;而 Markdown 不过滤,指向它的链接仍是旧字面名 → `docs/multi-module-event-storming.json` 生成为 `docs/shop-event-storming.json`、`docs/README.md` 的链接悬空(已改名 `docs/event-storming.json`)。**子模块** artifactId 的同名段是安全的(模块名由描述符固定,`start/` 下的 `ReadmeQuick__artifactId__Test.java` 原样往返)。④**只有 `.java`/`.xml`/`.properties` 走 Velocity 过滤**,`.md`/`.yml`/`.sql`/`.json` 原样复制 → md 里的字面 `com.example` 会随项目发出去(改写成"本项目的基础包");且**不能**把 `md` 加进 `archetype.filteredExtensions` 绕过——Velocity 里行首 `##` 是注释,会吃掉每一个 Markdown 标题。
  - **端到端验证(2026-08-06 按三 BC + MyBatis/PostgreSQL + Kafka/outbox + Flyway 的当前形态重新派生)**:从 archetype 生成 `com.acme:shop`(package `com.acme.shop`)后 `mvn test` 全绿(341 个用例,含 `OrderingFlowTest` 跨 BC 闭环与补偿分支),无 `com.example` 残留;生成树与源树**逐文件一致**(仅少 `archetype.properties`)。**重新派生查出一处只在本仓库成立的真缺陷**:`ShippedCommentsAreSelfContainedTest` 用 `archetype.properties` 当 reactor 根标记,而该文件恰恰不进 archetype → 在每个生成项目里报错、在本仓库却常绿(已改判 `pom.xml` + `start/pom.xml`)。派生/验证流程与"源须守的五条规则"见 `aipersimmon-ddd-scaffold/README.md`。
- **`scaffold-samples`**:一组**聚焦单点 how-to** 的小例子,各只讲清一件事(如"加一个集成事件与进程内处理""加 outbox""接一个 saga""加 CQRS 读模型"),便于查阅与复制,而非再造一个大而全的应用。完整的双 BC 结构表达已由 `multi-module` 承担。

## 七、后果与开放项

- **后果**:分析→设计落地;库与脚手架解耦;生成项目靠 BOM 版本升级;`bc-and-layer-samples` 在 scaffold-samples 就绪后删除。
- **开放项**:
  1. ~~archetype 骨架产出几个 BC~~ **已定:双 BC(ordering 多聚合 + inventory 单聚合),嵌套目录,跨 BC 走进程内集成事件**。单 BC 表达力不足;完整结构由 multi-module 承担,scaffold-samples 转为聚焦单点 how-to。
  2. `-archunit` 的规则如何参数化消费者的分层包命名(约定 vs 显式传参)?Phase 1 落地时定。
  3. ~~GitHub Packages 发布与 CI/CD 的具体形态(Phase 4)~~ **已交付**:`.github/workflows/ci.yml`(装库→建脚手架/样例)、`publish-library.yml`(release/手动触发 → `mvn deploy` 库到 GitHub Packages,`setup-java` 配 `github` server + `GITHUB_TOKEN`)、库 parent `distributionManagement`(repo id `github`)。消费者需在自己的 Maven 配置加同一 GitHub Packages 仓库并鉴权。
  4. ~~**集成事件方式三(broker)**:`-messaging-kafka`~~ **已交付(§5.14)**:Kafka `OutboxDispatcher` + inbox 守卫的进程内消费桥;实时 broker 端到端集成测试归消费方应用,库内为无 broker 的单元/装配测试。
  5. **Process Manager 生产化(Phase 3 之后)**:旧 `-saga/-saga-spring`、JDBC deadline 与 command/outbox 样例均作为已交付基线保留，但不继续扩展 `-saga-jpa`；后续采用 [[design-00004-durable-process-manager-runtime]] 的 clean-slate 三模块设计。JPA 变体 `-outbox-jpa` / `-inbox-jpa` 仍是独立的消息存储开放项，不属于 Process Manager backend。
  7. **`microservice` 拓扑落地要点(已交付)**:每 BC 独立部署,跨服务**只经 Kafka**(outbox→Kafka→inbox 守卫的消费桥→进程内重投)。选**共享 `contracts` 模块**(两服务同依赖 → 事件类 FQN 一致 → 库的消费桥 `Class.forName` 直接可用,无需库改动)。**落地发现**:e2e 同一 JVM 启两服务时,两服务 jar 的 `application.properties` 在同一 classpath 根**冲突**(后启的服务错读前者配置);修法 = e2e 用各服务专属 `spring.config.name`(`ordering-e2e`/`inventory-e2e`),各服务自身的 `application.properties` 保持不变(独立部署/生成项目仍正确)。单主题 + 各服务独立消费组:每方收到全量、只对自己关心的事件反应。
  6. ~~**scaffold-samples 补 how-to**:"接一个 saga""加 CQRS 读模型""集成事件走 Kafka"~~ **已交付**:`add-cqrs-read-model`(命令管道+读模型)、`orchestrate-with-saga`(process manager + deadline + 补偿)、`integration-events-over-kafka`(outbox→Kafka→inbox→进程内,EmbeddedKafka 端到端)。**落地时修了库两处装配缺陷**:saga-spring 的 `DeadlineScheduler`↔`DeadlineHandler` 构造循环(改 scheduler 惰性 `Supplier<DeadlineHandler>` 解析);messaging-kafka autoconfig 未 `after = KafkaAutoConfiguration`,致 `@ConditionalOnBean(KafkaTemplate)` 早评估、Kafka dispatcher 未顶替日志默认(已修)。**`multi-module` 已改为 orchestration saga**(`OrderFulfilment` 过程管理器 + `OrderFulfilmentSaga` 中心状态 + 内存 `SagaStore`,`StockReserved` 确认 / `StockReservationFailed` 补偿取消);archetype 重新派生并验证生成 `com.acme.shop` 全绿(含补偿分支)。**后续再拆分层**:把过程管理器的协调策略下沉到 application 层(`OrderFulfilmentProcessManager`,`@ProcessManager`,依赖 `SagaStore`/`CommandBus` port、只吃 order id),`adapter/messaging` 的 `OrderFulfilment` 收缩为仅绑 `@EventListener` 的瘦投递壳——对齐主流实践(协调在 application、投递在 edge),消除"编排逻辑住在 interface 层"的分层张力。**三套拓扑(`multi-module` / `modulith` / `microservice`)一致落地**,各自测试全绿(microservice 含真 Kafka 的 `MicroserviceFlowE2eTest`)。~~**archetype 尚未就此改动重新派生**(留待下次重派生一并验证)~~ **已于 2026-08-06 重新派生并端到端验证**(见 §六 archetype 条目)。

## Sources

内部:
- [[analysis-00006-ddd-building-blocks-library]] —— 模块切分、参考不依赖、CQRS、§十 `Transitions<S>`。
- [[analysis-00007-saga-process-manager]] —— saga 分档(Phase 3)。
- [[design-00004-durable-process-manager-runtime]] —— durable Process Manager 三模块的当前生产目标与迁移边界。
- [[analysis-00004-bounded-context-module-structure]] —— 三种拓扑与 "ship one worked BC"。
- [[decision-00005-package-per-aggregate]] —— domain 包结构(archetype 骨架遵循)。
- [[design-00003-exception-model]] —— `-core`/`-application` 异常体系增量(`ErrorCode`/`Invariant` + 语义子类),扩展本文 §5.3/§5.5。

外部:
- Maven Archetype —— Guide to Creating Archetypes / `archetype:create-from-project`。https://maven.apache.org/guides/mini/guide-creating-archetypes.html
- Maven —— Introduction to the Dependency Mechanism(BOM / `import` scope)。https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html
- GitHub Packages —— Apache Maven registry。https://docs.github.com/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry
