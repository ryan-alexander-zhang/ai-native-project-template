---
id: analysis-00014-ddd-samples-scenario-catalog
type: analysis
role: main
status: draft
parent:
---

# DDD 示例场景总纲：`aipersimmon-ddd-samples` 规划

## 0. 本文档的定位

这是一个**总纲**：穷举 DDD 落地时的典型场景，只回答"有哪些场景、每个场景的边界和要回答的
问题是什么、大致会用到库里的哪些组件"，**不涉及任何实现方式**。

后续工作方式（待本文档 review 通过后启动）：

- 每个场景各写一篇独立的 `docs/analysis/` 文档，讲清该场景下 DDD 的标准流程写法，以及
  `aipersimmon-ddd` 组件在其中的应用位置和使用方式；
- 每篇场景文档对应 `aipersimmon-ddd-samples/`（待创建）下的一个子目录，用完整可运行的代码
  演示该文档描述的流程。

与既有产物的关系：

- `aipersimmon-ddd-scaffold/multi-module` 是"一个完整服务长什么样"的骨架，面向起项目；
  samples 与之互补，**每个 sample 只聚焦一个场景**，面向"这类问题怎么写"。
- `analysis-00001/00002/00005/00011` 等已经从**机制**角度分析过事件发布/消费；本轮场景文档
  是**使用者视角**的流程示范，不重复机制分析，必要时引用它们。

## 1. 场景总览

优先级含义：**P0** = 用户点名必须覆盖；**P1** = 建议补充的常见场景；**X** = 横切关注点，
默认不做独立 sample，而是指定寄宿在某个 P0 sample 里顺带演示（是否独立，review 时定）。

| # | 场景 | 优先级 | 拟定 sample 目录 |
| --- | --- | --- | --- |
| S1 | HTTP 同步接口：命令与查询 | P0 | `http-command-query` |
| S2 | HTTP 写接口的幂等提交与重放防护 | P0 | `http-idempotency` |
| S3 | 领域事件的发布与消费（同进程） | P0 | `domain-events-in-process` |
| S4 | 集成事件跨服务：outbox 发布 + Kafka + inbox 消费 | P0 | `integration-events-cross-service` |
| S5 | 消费外部系统的消息（非本体系事件格式） | P0 | `consume-foreign-messages` |
| S6 | 服务间同步调用（本体系内另一个服务） | P0 | `sync-service-call` |
| S7 | 调用外部三方应用（防腐层 + 出站/回调） | P0 | `third-party-integration` |
| S8 | 本地事务：聚合边界、乐观锁与冲突重试 | P0 | `local-transaction-aggregate` |
| S9 | 最终一致性：process-manager 编排多步流程与补偿 | P0 | `eventual-consistency-process-manager` |
| S10 | 强一致性：Seata 跨服务分布式事务 | P0 | `strong-consistency-seata` |
| S11 | 非 HTTP 入口：定时任务 / 批处理如何走同一条命令通道 | P1 | `scheduled-and-batch-entry` |
| S12 | CQRS 读模型：由事件构建投影供查询侧使用 | P1 | `cqrs-read-model` |
| S13 | 多租户：租户如何随命令/事件/持久化端到端传播 | X（拟寄宿 S4） | — |
| S14 | 操作日志：注解式与非注解式记录业务操作 | X（拟寄宿 S1） | — |
| S15 | 可观测性：一条请求跨 HTTP→命令→outbox→Kafka→inbox 的完整 trace | X（拟寄宿 S4） | — |

依赖关系（影响后续展开顺序，不影响本轮罗列）：S4 依赖 S3 的概念；S9 依赖 S4；S12 依赖 S3/S4。

## 2. 逐场景说明

每个场景按固定结构：**场景描述**（业务上发生了什么）、**典型例子**、**要回答的关键问题**
（该场景文档必须讲清的决策点）、**预计涉及的组件**（只列模块名，不谈用法）。

### S1 HTTP 同步接口：命令与查询

**场景描述**：外部调用方通过 HTTP 发起一次业务操作（写）或一次业务查询（读），同步拿到
结果或结构化错误。这是绝大多数服务的第一入口，也是其他所有场景的"参照系"。

**典型例子**：`POST /orders` 下单、`GET /orders/{id}` 查单。

**要回答的关键问题**：

- Controller 里该有什么、不该有什么；HTTP DTO 与 command/query 对象的边界在哪；
- 写请求如何从 Controller 进入 `CommandBus`，读请求如何进入 `QueryBus`，两条路为何分开；
- 业务拒绝（余额不足）、校验失败（参数缺失）、系统故障（DB 挂了）三类失败分别如何映射到
  RFC 9457 problem 响应；调用方拿到的错误码体系长什么样；
- 返回体信封的约定（成功/失败的统一形状）；
- OpenAPI 文档如何与实际行为保持一致。

**预计涉及的组件**：`aipersimmon-ddd-starter-mybatis-plus`（或 `-jdbc`）、
`aipersimmon-ddd-web-spring-boot-starter`、`aipersimmon-ddd-cqrs`、
`aipersimmon-ddd-openapi-spring-boot-starter`。

### S2 HTTP 写接口的幂等提交与重放防护

**场景描述**：调用方（前端重试、网关超时重发、用户双击）对同一个写操作提交了不止一次；
服务端必须保证业务效果只发生一次，且重复请求拿到与首次一致的响应。附带同族问题：请求
签名防篡改、接口级限流。

**典型例子**：支付下单接口带 `Idempotency-Key` 头重试；开放平台接口验签 + 限流。

**要回答的关键问题**：

- 幂等键由谁生成、作用域是什么（per-principal？per-endpoint？）、保留多久；
- "首次请求还在处理中，重复请求到达"这个窗口期如何表现；
- 幂等层放在 HTTP 边界与放在领域层（天然幂等的命令）的分工；
- 存储选型：DB 表与 Redis 各自适用的场合；
- 重放防护、验签、限流与幂等是四个独立开关还是一套东西。

**预计涉及的组件**：`aipersimmon-ddd-web`（`IdempotencyStore` / `ReplayGuard` /
`RequestSignatureVerifier` / `RateLimiter` SPI）、`aipersimmon-ddd-web-store-jdbc`、
`aipersimmon-ddd-web-store-redis`、`aipersimmon-ddd-web-spring-boot-starter`。

### S3 领域事件的发布与消费（同进程）

**场景描述**：一个聚合完成状态变更后，同一个服务内的其他组件需要对此作出反应，但这个
反应不属于该聚合的职责。事件在同一个 JVM、通常同一个事务内传递。

**典型例子**：订单创建成功后，同服务内的"欢迎优惠"逻辑给新客户发一张券。

**要回答的关键问题**：

- 事件从哪里冒出来：聚合内注册、仓储保存时发布的完整生命周期；
- 事件该携带什么数据（ID + 关键事实 vs 整个聚合快照）；
- 消费方写在哪一层；监听器在事务的哪个阶段执行（提交前/提交后），选错各会发生什么；
- 监听器自己再修改另一个聚合时，算一个事务还是两个；
- 什么样的反应适合领域事件，什么样的反应其实应该写在同一个命令处理器里。

**预计涉及的组件**：`aipersimmon-ddd-core`（`AbstractAggregateRoot`）、
`aipersimmon-ddd-events-spring-boot-starter`、`aipersimmon-ddd-persistence-mybatis-plus`
（或 `-jdbc`）。

### S4 集成事件跨服务：outbox 发布 + Kafka + inbox 消费

**场景描述**：一个服务发生的业务事实需要通知**另一个部署单元**。发布方要保证"业务变更
落库"与"事件必达"绑在一起（不丢不假发）；消费方要在 at-least-once 投递下保证只生效一次。
这是本体系内跨服务协作的默认方式。

**典型例子**：订单服务发布"订单已创建"，库存服务消费并预留库存。双服务 sample。

**要回答的关键问题**：

- 哪些事件值得跨服务（`@Externalized` 的取舍），事件契约如何演进不破坏消费方；
- 发布侧：outbox 为什么是必须的，事件何时真正离开本服务，顺序保证到什么粒度；
- 消费侧：inbox 如何做幂等，重复投递/乱序/毒丸消息分别怎么表现；
- 消费失败的重试与死信策略；
- 本地开发没有 Kafka 时这条链路如何降级（同进程直递）而消费代码不变。

**预计涉及的组件**：`aipersimmon-ddd-integration`、`aipersimmon-ddd-outbox-*`、
`aipersimmon-ddd-inbox-*`、`aipersimmon-ddd-starter-messaging-kafka`、
`aipersimmon-ddd-messaging-kafka`。

### S5 消费外部系统的消息（非本体系事件格式）

**场景描述**：消息来自**不使用本库的外部系统**（别的团队、遗留系统、三方推送到 MQ），
格式、语义、投递保证都不受我们控制。与 S4 的区别：S4 两端都是本体系，信封格式统一；
S5 要先把外来消息翻译成本上下文的语言再处理。

**典型例子**：消费上游 ERP 系统推到 Kafka 的商品变更消息，更新本地商品快照。

**要回答的关键问题**：

- 外来消息在哪一层落地成本上下文的 command / 事实（防腐翻译放在哪）；
- 外来消息没有本体系的 envelope/事件 ID 时，幂等键从哪来；
- 消息驱动的处理如何复用与 HTTP 入口相同的命令通道（同一个 handler 两个入口）；
- 反序列化失败、语义非法的消息如何处置（拒绝 vs 死信 vs 告警）。

**预计涉及的组件**：`aipersimmon-ddd-inbox-*`、`aipersimmon-ddd-cqrs`、
`aipersimmon-ddd-messaging-kafka`（或原生 spring-kafka listener + 手动进 inbox）。

### S6 服务间同步调用（本体系内另一个服务）

**场景描述**：处理一个请求的过程中，必须**当场**拿到另一个服务的数据或结果才能继续
（事件的异步性满足不了）。调用方与被调方都是本体系内的服务。

**典型例子**：下单时同步调用风控服务，拒绝则下单失败。

**要回答的关键问题**：

- 什么时候该同步调用、什么时候该事件/数据冗余——判断标准是什么（本场景文档的核心）；
- 同步调用发起的位置：domain 层能不能调？application 层怎么调？端口在哪层定义、适配器在
  哪层实现；
- 对方返回的 RFC 9457 错误如何翻译回本上下文的失败语义（不能把对方的错误码直接漏给自己
  的调用方）；
- 超时、重试、降级的责任归属；对方不可用时本方事务是否已经动过手；
- 查询型调用与命令型调用（让对方改状态）的不同风险等级。

**预计涉及的组件**：`aipersimmon-ddd-application`（端口定义位置）、`aipersimmon-ddd-web`
（problem 契约的客户端解读）、HTTP 客户端为三方选型（RestClient 等，库不提供）。

### S7 调用外部三方应用（防腐层 + 出站/回调）

**场景描述**：与完全不受控的外部系统集成：支付网关、短信/邮件服务商、物流平台。对方的
模型、可用性、幂等语义都不由我们定义，且通常有"发起调用 → 异步回调通知结果"的双向交互。

**典型例子**：支付：发起支付请求到三方网关，网关异步回调支付结果。

**要回答的关键问题**：

- 防腐层长什么样：三方的模型在哪一层被隔离，本上下文暴露什么样的端口；
- 出站调用的可靠性：直接同步调用 vs 先落 outbox/本地任务再由后台外呼——各适用什么场合；
- 三方回调入站：验签、幂等（三方会重复回调）、回调乱序（成功通知先于受理通知到达）；
- "本地已扣款、三方超时未知结果"这类悬挂状态如何建模（与 S9 衔接）；
- 三方沙箱不可用时 sample 如何自包含（本地 stub 服务）。

**预计涉及的组件**：`aipersimmon-ddd-application`、`aipersimmon-ddd-outbox-*`（出站任务
化）、`aipersimmon-ddd-web` + `aipersimmon-ddd-web-store-*`（回调入站幂等/验签）、
`aipersimmon-ddd-process-manager-*`（悬挂态推进，视展开深度）。

### S8 本地事务：聚合边界、乐观锁与冲突重试

**场景描述**：单服务、单库内一次业务操作的事务处理。DDD 的基线规则——一个事务只修改
一个聚合——在真实业务"一个动作要动好几张表"的压力下如何成立；并发修改同一聚合时如何
不丢更新。

**典型例子**：两个并发请求同时扣减同一 SKU 库存；下单时既建订单又记流水。

**要回答的关键问题**：

- 事务边界画在哪一层，由谁开启（命令处理器？拦截器？）；
- "一个事务一个聚合"的理由，以及确实要动两个聚合时的三条路（重划聚合边界 / 领域事件
  最终一致 / 明确破例）各自的判断标准；
- 乐观锁版本冲突的表现，什么样的命令适合自动重试、什么样的绝不能重试；
- 框架自身的落库（outbox 行、操作日志行）如何与业务变更共享同一事务——"共享一个
  DataSource"这一前提意味着什么。

**预计涉及的组件**：`aipersimmon-ddd-core`（聚合、版本）、
`aipersimmon-ddd-persistence-mybatis-plus`（或 `-jdbc`，版本检查仓储）、
`aipersimmon-ddd-cqrs-spring-boot-starter`（拦截器链、RetryOnConflict opt-in）。

### S9 最终一致性：process-manager 编排多步流程与补偿

**场景描述**：一个业务目标要跨多个聚合/多个服务分多步完成，中间任何一步都可能失败或
超时，整体不追求瞬时一致，但必须**收敛**：要么全部完成，要么已完成的步骤被补偿。流程
状态本身要落库、可恢复、可观察。

**典型例子**：下单流程：创建订单 → 预留库存 → 发起支付 → 确认订单；支付超时则释放库存、
取消订单。

**要回答的关键问题**：

- 编排（process manager 主动发命令）与协同（各服务各自订阅事件）的取舍，各自适合什么
  流程形态；
- 一个流程定义由什么构成：步骤、触发事件、超时、补偿动作；
- 补偿不是回滚：补偿动作的业务语义如何设计（释放预留 vs 删记录）；
- 流程实例卡住（步骤反复失败、挂起）时运维如何介入；
- 每一步自身的幂等如何与 S4 的 inbox 配合；
- 什么样的流程复杂到不该再用本库的 process-manager（复杂度上限与指路）。

**预计涉及的组件**：`aipersimmon-ddd-process-manager`、
`aipersimmon-ddd-process-manager-engine`、`aipersimmon-ddd-process-manager-jdbc`（或
`-mybatis-plus`）、S4 的全部事件链路组件。

### S10 强一致性：Seata 跨服务分布式事务

**场景描述**：业务上无法接受任何中间态窗口，要求跨服务的多个写操作**同时成功或同时
失败**，且调用方同步拿到最终结果。用 Seata 实现分布式事务。本库不含 Seata 集成，此场景
演示第三方分布式事务框架如何与本库的命令通道、聚合持久化共存。

**典型例子**：账户服务扣款 + 积分服务加分，必须同成同败，调用方同步得到结果。

**要回答的关键问题**：

- 首要问题是**什么时候才真的需要它**：与 S9 的决策边界（一张判断清单），以及强一致的
  真实代价（可用性、吞吐、锁持有时间）；
- Seata 模式选择（AT / TCC / XA）的判断标准，DDD 视角下 TCC 的 Try/Confirm/Cancel 与聚合
  操作如何对应；
- 全局事务边界画在哪一层（application 层编排处），与本库 `@Transactional` 边界、拦截器链
  的叠加关系；
- 全局事务内产生的领域事件/outbox 行在全局回滚时会发生什么——事件外发必须以全局提交为
  准的约束；
- 与乐观锁（S8）的相互作用：Seata AT 的全局锁与聚合 version 谁先谁后。

**预计涉及的组件**：`aipersimmon-ddd-cqrs-spring-boot-starter`、
`aipersimmon-ddd-persistence-*`、`aipersimmon-ddd-outbox-*`（用于演示约束）；Seata 为
三方依赖，sample 需自带 seata-server 的 compose 编排。

### S11 非 HTTP 入口：定时任务 / 批处理（P1，建议补充）

**场景描述**：业务动作不由外部请求触发，而由时间触发（对账、过期关单、批量重算）。这些
入口应当与 HTTP 入口收敛到同一条命令通道，而不是绕过 application 层直改数据库。

**典型例子**：每 5 分钟扫描超时未支付订单并关闭。

**要回答的关键问题**：

- 定时器里该写什么：找出目标集合，然后逐个发命令，而不是一条 UPDATE 扫全表——理由与
  例外；
- 批量场景下命令粒度的取舍（一单一命令 vs 一批一命令）；
- 多实例部署时的调度互斥（ShedLock 等三方件的位置）；
- 没有 HTTP 请求上下文时，`CommandContext`（操作者、租户）从哪来。

**预计涉及的组件**：`aipersimmon-ddd-cqrs`、`aipersimmon-ddd-application`、
`aipersimmon-ddd-operation-log-*`（非注解入口记录）、调度与互斥为三方选型。

### S12 CQRS 读模型：由事件构建投影（P1，建议补充）

**场景描述**：查询的形状与写模型差异很大（跨聚合汇总、列表页、报表），直接查写模型
既慢又扭曲聚合设计。由事件驱动维护一份专为查询设计的投影。

**典型例子**：订单列表页需要订单 + 商品名 + 支付状态的扁平视图。

**要回答的关键问题**：

- 什么时候"直接查写模型的表"就够了，什么时候才值得引入投影（本场景文档的第一节）；
- 投影由领域事件（同进程）还是集成事件（跨服务）驱动，两者的一致性时延差异；
- 投影落后于写模型时用户看到什么，"读自己的写"怎么处理；
- 投影坏了/改结构时如何重建。

**预计涉及的组件**：`aipersimmon-ddd-cqrs`（QueryBus）、S3/S4 的事件链路组件、
`aipersimmon-ddd-inbox-*`（投影更新的幂等）。

### S13 多租户（横切，拟寄宿 S4）

**场景描述**：同一套部署服务多个租户，租户身份要从入口（HTTP/消息）一路传播到持久化行
和跨服务事件，且任何一环不得读写别家租户的数据。

**要回答的关键问题**：租户从哪里解析；如何随 `CommandContext` → `EventEnvelope` →
`ce_tenantid` → 耐久列传播；MyBatis-Plus 的 SQL 级自动改写与 JDBC 手写谓词的差别；后台
任务（无请求上下文）如何带租户。

**预计涉及的组件**：`aipersimmon-ddd-tenancy`、`aipersimmon-ddd-tenancy-mybatis-plus`、
`aipersimmon-ddd-tenancy-spring-boot-starter`。

**寄宿理由**：租户传播的价值恰好在"跨命令、跨事件、跨服务仍不串号"，单独造一个 sample
反而演示不出端到端；放进 S4 的双服务链路一并演示最完整。

### S14 操作日志（横切，拟寄宿 S1）

**场景描述**：管理后台要求"谁、何时、对什么、做了什么、结果如何"的业务级审计记录，
且业务拒绝、系统失败也要留痕。

**要回答的关键问题**：注解式（命令上声明）与非注解式（类型安全 Definition）各适用什么；
成功日志与业务同事务、失败日志独立事务的语义；不该记什么（完整 command/entity 快照）。

**预计涉及的组件**：`aipersimmon-ddd-operation-log`、
`aipersimmon-ddd-operation-log-cqrs-spring-boot-starter`、
`aipersimmon-ddd-operation-log-jdbc`（或 `-mybatis-plus`）。

### S15 可观测性（横切，拟寄宿 S4）

**场景描述**：一次业务请求穿过 HTTP → 命令 → 聚合 → outbox → Kafka → inbox → 下游命令，
排障时需要一条完整 trace 而不是七段孤立日志。

**要回答的关键问题**：trace 上下文如何跨 outbox 的"存储再转发"断点接续；框架各边界
（命令、事件、流程步骤）各自出什么 span/metric；不接 OTel 时这些 SPI 的成本。

**预计涉及的组件**：`aipersimmon-ddd-observability`、`aipersimmon-ddd-observability-otel`、
`aipersimmon-ddd-observability-otel-spring-boot-starter`。

**寄宿理由**：与 S13 同理，价值在跨边界串联，S4 的链路最长、最能演示。

## 3. 需要 review 时拍板的开放问题

1. **统一业务域**：建议所有 sample 共用一个"订单—库存—支付—通知"的电商域，人物和名词
   一致，读者跨 sample 不用切换脑子；反对意见是个别场景（如 S5 消费 ERP 消息）会显得
   牵强。是否统一？
2. **横切场景的归宿**：S13/S14/S15 按上文寄宿，还是也各给一个独立目录？独立目录更好检索，
   寄宿更能演示端到端价值。
3. **P1 场景（S11/S12）本轮是否纳入**，还是先只做 P0 的十个？
4. **`aipersimmon-ddd-samples` 的工程形态**：每个子目录独立 Maven reactor（互不牵连、可
   单独打开），还是一个父 POM 统管（一次 verify 全绿）？倾向后者，便于 CI 保鲜。
5. **S10（Seata）的深度**：完整可运行（需 seata-server、多库 compose，重）还是"可运行
   的最小骨架 + 决策文档为主"（轻）？
6. 双服务场景（S4/S6/S9/S10）一个 sample 目录里放两个服务模块，目录命名与端口分配需要
   一个统一约定，随第一个双服务文档一起定。

## 4. 本轮明确不覆盖

- 各场景的实现细节、代码组织、配置项——留给逐场景文档；
- 安全/认证授权（库无此域组件，且 design-00013 冻结中）；
- 前端交互、BFF 形态；
- 性能压测与容量规划。
