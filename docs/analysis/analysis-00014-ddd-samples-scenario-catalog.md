---
id: analysis-00014-ddd-samples-scenario-catalog
type: analysis
role: main
status: draft
parent:
---

# DDD 示例场景总纲：`aipersimmon-ddd-samples` 规划

## 0. 定位与前提

这是一个**总纲**：穷举 DDD 落地时的典型场景，只回答"有哪些场景、每个场景的边界和要回答的
问题是什么、大致会用到库里的哪些组件"，**不涉及任何实现方式**。

后续工作方式：每个场景各写一篇独立的 `docs/analysis/` 文档，讲清该场景下 DDD 的标准流程写法，
以及 `aipersimmon-ddd` 组件在其中的应用位置和使用方式；每篇场景文档对应
`aipersimmon-ddd-samples/`（待创建）下的一个子目录，用完整可运行的代码演示该文档描述的流程。

### 0.1 前提声明：samples 独立

- **samples 与 `aipersimmon-ddd-scaffold` 无关**。不复用它的模块、不参照它的取舍、不受它已演示
  或未演示的内容影响。scaffold 回答"一个项目起手长什么样"，samples 回答"这类流程怎么写"，
  两者不互为约束。
- **samples 不受 `docs/` 下既有 decision / plan 的约束**。那些文档治理的是库与 scaffold 的演进；
  samples 是示例代码，按示例本身讲清楚为准。
- samples **唯一的对标物是 `aipersimmon-ddd` 库本身的真实行为**：库里有什么类型、什么 SPI、
  什么配置项、什么启动校验，示例就按它实际的样子演示。
- **数据访问一律用 MyBatis-Plus 系组件，不用 JDBC 系**：`-persistence-mybatis-plus`、
  `-outbox-mybatis-plus`、`-inbox-mybatis-plus`、`-process-manager-mybatis-plus`、
  `-operation-log-mybatis-plus`、`-tenancy-mybatis-plus`。`-persistence-jdbc` 一类模块不出现在
  任何 sample 里，也不作对照。**唯一的例外是 web 边界存储**：库只提供
  `aipersimmon-ddd-web-store-jdbc` 与 `-web-store-redis` 两种实现，**没有 mybatis-plus 变体**，
  所以 S2/S7/S22 用到幂等/限流/防重放存储时只能在这两者之间选（它们存的是框架自己的边界表，
  不是业务聚合）。

### 0.2 已拍板事项

| # | 事项 | 结论 |
| --- | --- | --- |
| 1 | 统一业务域 | **不统一**。不为凑统一而让任何场景变牵强；需要新业务域就直接用新的，多个场景天然共用一个域时才共用 |
| 2 | 横切场景（S13/S14/S15）的归宿 | **寄宿**在指定的 P0 sample 内演示 |
| 3 | P1 场景 | **纳入** |
| 4 | 工程形态 | **父 POM 统管**，一次 verify 覆盖全部 sample |
| 5 | S10（Seata） | **完整可运行**，自带 seata-server 与多库编排 |
| 6 | 双服务场景 | 一个 sample 目录内放两个服务模块 |

## 1. 场景总览

优先级：**P0** = 首批必做；**P1** = 纳入本轮，排在 P0 之后；**X** = 横切关注点，寄宿在指定
sample 内演示，不单独建目录。

| # | 场景 | 优先级 | 业务域 | 拟定 sample 目录 |
| --- | --- | --- | --- | --- |
| S1 | HTTP 同步接口：命令与查询 | P0 | 订单 | `http-command-query` |
| S2 | HTTP 写接口的幂等提交与重放防护 | P0 | 订单 | `http-idempotency` |
| S3 | 领域事件的发布与消费（同进程） | P0 | 订单 | `domain-events-in-process` |
| S4 | 集成事件跨服务：outbox + Kafka + inbox | P0 | 订单 + 库存 | `integration-events-cross-service` |
| S5 | 消费外部系统的消息（非本体系格式） | P0 | 商品主数据（上游 ERP） | `consume-foreign-messages` |
| S6 | 服务间同步调用 | P0 | 风控/信用 | `sync-service-call` |
| S7 | 调用外部三方应用（防腐层 + 回调） | P0 | 支付网关 | `third-party-integration` |
| S8 | 本地事务：聚合边界、乐观锁与冲突重试 | P0 | 库存 | `local-transaction-aggregate` |
| S9 | 最终一致性：process-manager 编排与补偿 | P0 | 订单履约 | `eventual-consistency-process-manager` |
| S10 | 强一致性：Seata 跨服务分布式事务 | P0 | 账户 + 积分 | `strong-consistency-seata` |
| S11 | 非 HTTP 入口：定时任务 / 批处理 | P1 | 订单（超时关单/对账） | `scheduled-and-batch-entry` |
| S12 | CQRS 读模型：事件驱动的投影 | P1 | 订单列表 | `cqrs-read-model` |
| S13 | 多租户端到端传播 | X（寄宿 S4） | — | — |
| S14 | 操作日志：注解式与非注解式 | X（寄宿 S1） | — | — |
| S15 | 可观测性：跨边界一条完整 trace | X（寄宿 S4） | — | — |
| S16 | 战术建模：实体、值对象、聚合与规则原语 | P0 | 订单 | `tactical-modelling` |
| S17 | 聚合与数据表的映射 | P0 | 订单 | `aggregate-persistence-mapping` |
| S18 | 分层测试策略 | P0 | 复用 S16/S17 的域 | `testing-strategy` |
| S19 | 校验的三层分工 | P0 | 订单 | `validation-layers` |
| S20 | 读侧查询契约：分页、排序、过滤 | P0 | 订单列表 | `query-contract-paging` |
| S21 | 事件契约演进与多版本共存 | P0 | 订单 + 库存（双服务不同版次） | `event-contract-evolution` |
| S22 | 运维面：死信、重放、保留清理与启动自检 | P0 | 复用 S4 的域 | `operability-deadletters-retention` |
| S23 | Schema 演进与数据迁移 | P1 | 订单 | `schema-migration` |
| S24 | 在既有服务里新建一个限界上下文 | P1 | 新增"优惠券"上下文 | `add-bounded-context` |
| S25 | 遗留系统绞杀式接入 | P1 | 遗留订单单体 | `strangler-legacy-adoption` |
| S26 | 读侧加速：缓存与投影的取舍 | P1 | 商品/订单查询 | `read-side-caching` |
| S27 | 软删除、数据保留与擦除 | P1 | 客户资料 | `soft-delete-and-erasure` |
| S28 | 长耗时与大数据量端点 | P1 | 订单导出/批量导入 | `long-running-endpoints` |

共 25 个 sample 目录（S13/S14/S15 寄宿，不占目录）。P0 十七个、P1 八个。建议分批交付：
先 P0 中的 S1、S16、S17、S18 四篇立地基，再推其余 P0，最后 P1。

## 2. 逐场景说明

每个场景按固定结构：**场景描述**、**典型例子**、**要回答的关键问题**、**预计涉及的组件**
（写实际 artifactId，不用通配）。

### S1 HTTP 同步接口：命令与查询（P0）

**场景描述**：外部调用方通过 HTTP 发起一次业务操作（写）或一次业务查询（读），同步拿到
结果或结构化错误。这是绝大多数服务的第一入口，也是其他所有场景的"参照系"：**所有入口只负责
把外部请求翻译成命令**这一课由本篇确立，后续入口类场景（S5、S11）引用而不重讲。

**典型例子**：`POST /orders` 下单、`GET /orders/{id}` 查单。

**要回答的关键问题**：

- Controller 里该有什么、不该有什么；HTTP DTO 与 command/query 对象的边界在哪；
- 写请求如何进入 `CommandBus`，读请求如何进入 `QueryBus`，两条路为何分开；
- 业务拒绝（余额不足）、校验失败（参数缺失）、系统故障（DB 挂了）三类失败分别如何映射到
  RFC 9457 problem 响应；调用方拿到的错误码体系长什么样；**为自己的领域新增一个错误时，
  错误码定在哪、谁注册 problem type、消费方看到什么**——错误契约由本篇一次定清，后续场景不再
  各自裁决；
- 库只提供 RFC 9457 的错误契约，**不提供通用成功信封**：成功响应直接返资源 + 正确状态码，
  示例要如实呈现这一点，而不是自造一层 `{code,message,data}`；
- **读侧的返回类型与归属**：query handler 返回什么、住在哪一层，`Projection` 与 `ReadModel`
  两个标记分别什么时候用；
- OpenAPI 文档如何与实际行为保持一致。

**预计涉及的组件**：`aipersimmon-ddd-starter` + `aipersimmon-ddd-persistence-mybatis-plus` +
`aipersimmon-ddd-mybatis-plus-spring-boot-starter`（**不是** `-starter-mybatis-plus`：那个 bundle
会带进四个表结构校验器，`flyway.components` 为空时启动即失败，理由见 analysis-00015 §6.1）、
`aipersimmon-ddd-cqrs`、`aipersimmon-ddd-web`（`ProblemCatalog` / `ProblemRegistry` /
`DefaultProblemFamilies`）、`aipersimmon-ddd-openapi-spring-boot-starter`。**寄宿 S14**。

**文档**：[[analysis-00015-samples-http-command-query]]（模板篇，已完成）。

### S2 HTTP 写接口的幂等提交与重放防护（P0）

**场景描述**：调用方（前端重试、网关超时重发、用户双击）对同一个写操作提交了不止一次；
服务端必须保证业务效果只发生一次，且重复请求拿到与首次一致的响应。附带同族问题：请求签名
防篡改、接口级限流。

**典型例子**：支付下单接口带 `Idempotency-Key` 头重试；开放平台接口验签 + 限流。

**要回答的关键问题**：

- 幂等键由谁生成、作用域是什么、保留多久；
- "首次请求还在处理中，重复请求到达"这个窗口期如何表现；
- **幂等键与业务唯一约束的分工**：`Idempotency-Key` 解决"同一次提交被重发"，业务唯一键解决
  "同一张订单只能存在一份"，两者不可互相替代；后者在库里的表现是 `DuplicateEntityException`
  → 409。两种误用（用幂等键顶替唯一约束，或反之）都会在生产上出事；
- 幂等层放在 HTTP 边界与放在领域层（天然幂等的命令）的分工；
- 存储选型：DB 表与 Redis 各自适用的场合；`allow-in-memory-stores=false` 为何要在生产打开
  （未共享的内存 store 会让一个已启用的防护静默失效）；
- 重放防护、验签、限流与幂等是四个独立开关，关注点不同、不可互相替代。

**预计涉及的组件**：`aipersimmon-ddd-web`（`IdempotencyStore` / `ReplayGuard` /
`RequestSignatureVerifier` / `RateLimiter` / `IdempotencyPrincipalResolver` SPI）、
`aipersimmon-ddd-web-store-redis`（sample 选它：无 DDL、无 Flyway 组件、无清理线程；
`-web-store-jdbc` 作为有库服务的替代在文档里对比）、`aipersimmon-ddd-web-spring-boot-starter`。
**注意 `RequestSignatureVerifier` 库里没有任何实现，必须自己写**，否则防重放静默失效。

**文档**：[[analysis-00017-samples-http-idempotency]]（已完成）。

### S3 领域事件的发布与消费（同进程）（P0）

**场景描述**：一个聚合完成状态变更后，同一个服务内的其他组件需要对此作出反应，但这个反应
不属于该聚合的职责。事件在同一个 JVM 内传递。

**典型例子**：订单创建成功后，同服务内的"欢迎优惠"逻辑给新客户发一张券。

**要回答的关键问题**：

- 事件从哪里冒出来：聚合内注册、仓储保存时排空并发布的完整生命周期；
- 事件该携带什么数据（ID + 关键事实 vs 整个聚合快照）；
- 消费方写在哪一层；监听器在事务的哪个阶段执行（提交前/提交后），选错各会发生什么；
- 监听器自己再修改另一个聚合时，算一个事务还是两个；
- **同进程领域事件是易失的**：不落库、不重试，进程崩溃即丢，提交后监听器失败没人补。
  什么样的"反应"必须升级成 outbox——**即使根本不跨服务**。这是本篇最重要的一问，也是
  "outbox 只在跨服务时才需要"这一常见误解的纠正点；
- 什么样的反应适合领域事件，什么样的其实应该写在同一个命令处理器里。

**预计涉及的组件**：`aipersimmon-ddd-core`（`AbstractAggregateRoot`）、
`aipersimmon-ddd-events-spring-boot-starter`、`aipersimmon-ddd-persistence-mybatis-plus`。

**文档**：[[analysis-00020-samples-domain-events-in-process]]（已完成）。

### S4 集成事件跨服务：outbox 发布 + Kafka + inbox 消费（P0，双服务）

**场景描述**：一个服务发生的业务事实需要通知**另一个部署单元**。发布方要保证"业务变更落库"
与"事件必达"绑在一起；消费方要在 at-least-once 投递下保证只生效一次。

**典型例子**：订单服务发布"订单已创建"，库存服务消费并预留库存。

**要回答的关键问题**：

- 哪些事件值得跨服务（`@Externalized` 的取舍）；
- 发布侧：outbox 为什么是必须的，事件何时真正离开本服务，顺序保证到什么粒度（聚合分区键）；
- 消费侧：inbox 如何做幂等，重复投递/乱序/毒丸消息分别怎么表现；
- 消费失败的重试与死信策略；
- 本地开发没有 Kafka 时这条链路如何降级（同进程直递）而消费代码不变；缺 outbox 而有
  `@Externalized` 事件时为何应当启动失败，而不是静默发在进程内；
- **新消费方上线 / offset 重置时会发生什么**：inbox 的保留期必须长于最长可能的重投延迟，而
  一次 `reset offset to earliest` 直接打破这个前提，把很久以前的事件当新消息重放。历史事件
  要不要补发、topic 保留期与 inbox 保留期如何对齐；
- **换传输**：团队不用 Kafka（RabbitMQ / RocketMQ）时该实现哪个接缝，而不是误以为库锁死 Kafka；
- 框架表从哪来：`aipersimmon.ddd.flyway.components` 不列就不建表，示例必须显式展示这个开关。

**预计涉及的组件**：`aipersimmon-ddd-integration`、`aipersimmon-ddd-outbox`、
`aipersimmon-ddd-outbox-engine`、`aipersimmon-ddd-outbox-mybatis-plus`、
`aipersimmon-ddd-outbox-spring-boot-starter`、`aipersimmon-ddd-inbox`、
`aipersimmon-ddd-inbox-mybatis-plus`、`aipersimmon-ddd-messaging-kafka`、
`aipersimmon-ddd-starter-messaging-kafka`、`aipersimmon-ddd-flyway-spring-boot-starter`。
**寄宿 S13、S15**。

### S5 消费外部系统的消息（非本体系事件格式）（P0）

**场景描述**：消息来自**不使用本库的外部系统**，格式、语义、投递保证都不受我们控制。与 S4 的
区别：S4 两端都是本体系、信封格式统一；S5 要先把外来消息翻译成本上下文的语言。

**典型例子**：消费上游 ERP 推到 Kafka 的商品变更消息，更新本地商品主数据。

**要回答的关键问题**：

- 外来消息在哪一层落地成本上下文的 command / 事实（防腐翻译放在哪）；
- 外来消息没有本体系的 envelope/事件 ID 时，幂等键从哪来；
- **乱序，而不只是重复**：同一实体的两次变更倒序到达时如何避免旧值覆盖新值（信上游时间戳？
  比对本地版本？直接拒绝更旧的？）。幂等键只挡重复，挡不住乱序；
- 反序列化失败、语义非法的消息如何处置（拒绝 vs 死信 vs 告警）；
- 消息驱动的处理如何复用与 HTTP 入口相同的命令通道（该课在 S1 确立，本篇只演示落地）。

**预计涉及的组件**：`aipersimmon-ddd-inbox`、`aipersimmon-ddd-inbox-mybatis-plus`、
`aipersimmon-ddd-cqrs`、`aipersimmon-ddd-messaging-kafka`（或原生 spring-kafka listener
手动进 inbox）。

### S6 服务间同步调用（P0，双服务）

**场景描述**：处理一个请求的过程中，必须**当场**拿到另一个服务的数据或结果才能继续。调用方与
被调方都是本体系内的服务。

**典型例子**：下单时同步调用风控服务，拒绝则下单失败。

**要回答的关键问题**：

- 什么时候该同步调用、什么时候该事件/数据冗余——判断标准是什么（本篇核心）；
- 同步调用发起的位置：端口在哪层定义、适配器在哪层实现；domain 层能不能调；
- **对方的 API 契约以什么形式到达调用方**：共享 `api` jar、OpenAPI 代码生成、还是手抄 DTO？
  这决定两个服务的编译期耦合强度，是最实际的工程分叉；
- 对方返回的 RFC 9457 错误如何翻译回本上下文的失败语义；
- 超时、重试、降级的责任归属；对方不可用时本方事务是否已经动过手；
- 远程调用不能在事务里等——预检该放在事务之前（见 S19 的 `CommandPrecheck`）；
- 查询型调用与命令型调用（让对方改状态）的风险差异与取舍；
- **调用方自己不拥有数据库时**（纯网关、计算服务）该取哪个 bundle。

**预计涉及的组件**：`aipersimmon-ddd-application`、`aipersimmon-ddd-cqrs`
（`CommandPrecheck`）、`aipersimmon-ddd-web`（problem 的客户端解读）、
`aipersimmon-ddd-starter`（无库服务）；HTTP 客户端为三方选型。

### S7 调用外部三方应用（防腐层 + 出站/回调）（P0）

**场景描述**：与完全不受控的外部系统集成：支付网关、短信服务商、物流平台。通常有"发起调用 →
异步回调通知结果"的双向交互。

**典型例子**：发起支付请求到三方网关，网关异步回调支付结果。

**要回答的关键问题**：

- 防腐层长什么样：三方模型在哪一层被隔离，本上下文暴露什么样的端口；
- 出站调用的可靠性：直接同步调用 vs 先落 outbox/本地任务再由后台外呼；
- 三方回调入站：验签、幂等（三方会重复回调）、回调乱序（成功通知先于受理通知到达）；
- "本地已扣款、三方超时未知结果"这类悬挂状态如何建模；
- **主动对账通道**：回调可能永远不来（三方丢了、我方宕机时它不重试）。除回调外必须有一条
  定时拉取三方状态的兜底路径，否则悬挂态永不收敛；
- 业务幂等键如何透传给三方，保证重试不重复扣款；
- 三方沙箱不可用时 sample 如何自包含（本地 stub 服务）。

**预计涉及的组件**：`aipersimmon-ddd-application`、`aipersimmon-ddd-outbox`（出站任务化）、
`aipersimmon-ddd-web` + `aipersimmon-ddd-web-store-jdbc`（回调幂等/验签/防重放）、
`aipersimmon-ddd-process-manager`（悬挂态推进，视深度）。

### S8 本地事务：聚合边界、乐观锁与冲突重试（P0）

**场景描述**：单服务、单库内一次业务操作的事务处理。"一个事务只修改一个聚合"在真实业务压力下
如何成立；并发修改同一聚合时如何不丢更新。

**典型例子**：两个并发请求同时扣减同一 SKU 库存。

**要回答的关键问题**：

- 事务边界画在哪一层，由谁开启；
- "一个事务一个聚合"的理由，以及确实要动两个聚合时的三条路（重划边界 / 领域事件最终一致 /
  明确破例）各自的判断标准；
- 乐观锁版本冲突的表现，什么样的命令适合自动重试、什么样的绝不能重试；
- **乐观锁不够用的场合**：`version` 只保护"我读过的那一个聚合"；跨聚合的读-判断-写
  （"这个 SKU 的总预留量超没超"）没有任何保护，必须靠唯一索引、悲观锁或数据库约束。什么时候
  从乐观锁升级到哪一种——本篇最大的缺口；
- 版本推进的围栏：`versionAdvanced()` 之所以是 public，是因为仓储基类在别的包；唯一阻止业务
  代码把锁"解除"的东西是 ArchUnit 规则，所以采用它是这条保证的前提而非可选项；
- 框架自身的落库（outbox 行、操作日志行）如何与业务变更共享同一事务。

**预计涉及的组件**：`aipersimmon-ddd-core`、`aipersimmon-ddd-persistence-mybatis-plus`、
`aipersimmon-ddd-cqrs-spring-boot-starter`（拦截器链、`RetryOnConflict` opt-in）、
`aipersimmon-ddd-archunit`。

### S9 最终一致性：process-manager 编排多步流程与补偿（P0）

**场景描述**：一个业务目标要跨多个聚合/多个服务分多步完成，中间任何一步都可能失败或超时，
整体不追求瞬时一致但必须收敛。流程状态落库、可恢复、可观察。

**典型例子**：下单流程：创建订单 → 预留库存 → 发起支付 → 确认订单；支付超时则释放库存、
取消订单。

**要回答的关键问题**：

- 编排（process manager 主动发命令）与协同（各服务各自订阅事件）的取舍；
- 一个流程定义由什么构成：步骤、触发事件、超时、补偿动作；
- 补偿不是回滚：补偿动作的业务语义如何设计；
- **谁持有真相**：流程实例的 step 与订单聚合的 status 是不是同一件事的两份拷贝？不一致时以谁
  为准？团队普遍在两者之间复制状态然后各自漂移——这是本篇最容易被跳过的一问；
- 流程实例卡住（步骤反复失败、挂起）时运维如何介入；
- 每一步自身的幂等如何与 S4 的 inbox 配合；
- 什么样的流程复杂到不该再用本库的 process-manager，以及此时的替代方向；
- 流程载荷/状态的编解码（`ProcessPayloadCodec` / `ProcessStateCodec`）为何要显式登记。

**预计涉及的组件**：`aipersimmon-ddd-process-manager`、
`aipersimmon-ddd-process-manager-engine`、`aipersimmon-ddd-process-manager-mybatis-plus`、
以及 S4 的全部事件链路组件。

### S10 强一致性：Seata 跨服务分布式事务（P0，双服务，完整可运行）

**场景描述**：业务上无法接受任何中间态窗口，要求跨服务的多个写操作同时成功或同时失败，且调用方
同步拿到最终结果。库本身不含 Seata 集成，本篇演示第三方分布式事务框架如何与本库的命令通道、
聚合持久化共存。

**典型例子**：账户服务扣款 + 积分服务加分，必须同成同败。

**要回答的关键问题**：

- 什么时候才真的需要它：与 S9 的决策边界（一张判断清单），以及强一致的真实代价；
- Seata 模式选择（AT / TCC / XA）的判断标准，DDD 视角下 TCC 的 Try/Confirm/Cancel 与聚合操作
  如何对应；
- **AT 模式与本库拦截器的兼容性**：Seata AT 靠解析被拦截的 SQL 生成前后镜像，而本库的聚合写
  已经被乐观锁与租户拦截器改写过。这两层 SQL 改写能否共存，是本 sample 的**首个待验证前提**
  （见 §3.1）；若不兼容，示例以 TCC 为主线；
- 全局事务边界画在哪一层，与 `@Transactional` 边界、拦截器链的叠加关系；
- 全局事务内产生的领域事件/outbox 行在全局回滚时会发生什么——事件外发必须以全局提交为准；
- 与乐观锁（S8）的相互作用：全局锁与聚合 version 谁先谁后。

**预计涉及的组件**：`aipersimmon-ddd-cqrs-spring-boot-starter`、
`aipersimmon-ddd-persistence-mybatis-plus`、`aipersimmon-ddd-outbox-mybatis-plus`；Seata 为
三方依赖，sample 自带 seata-server + 多库 compose 与 `undo_log` 迁移。

### S11 非 HTTP 入口：定时任务 / 批处理（P1）

**场景描述**：业务动作由时间触发（对账、过期关单、批量重算）。这些入口应与 HTTP 入口收敛到
同一条命令通道，而不是绕过 application 层直改数据库。本篇同时**枚举全部入口形态**（HTTP、
消息、定时、内部运维/CLI），是入口适配器的总论。

**典型例子**：每 5 分钟扫描超时未支付订单并关闭。

**要回答的关键问题**：

- 定时器里该写什么：找出目标集合，然后逐个发命令，而不是一条 UPDATE 扫全表——理由与例外；
- 批量场景下命令粒度的取舍（一单一命令 vs 一批一命令）；
- 多实例部署时的调度互斥；
- 没有 HTTP 请求上下文时，`CommandContext`（租户、关联 id）与操作者身份从哪来；
- **部分失败与结果可见性**：一轮扫出 1000 个目标、37 个命令失败了会怎样——批次结果向谁报告、
  失败项下一轮会不会重来、已成功的会不会重复处理、以及"扫描时命中的目标在处理到第 500 条时
  已被用户手动关闭"。

**预计涉及的组件**：`aipersimmon-ddd-cqrs`、`aipersimmon-ddd-application`、
`aipersimmon-ddd-operation-log`（非注解入口记录）、`aipersimmon-ddd-tenancy`；调度与互斥为
三方选型。

### S12 CQRS 读模型：事件驱动的投影（P1）

**场景描述**：查询的形状与写模型差异很大（跨聚合汇总、列表页、报表），直接查写模型既慢又扭曲
聚合设计。由事件驱动维护一份专为查询设计的投影。

**典型例子**：订单列表页需要订单 + 商品名 + 支付状态的扁平视图。

**要回答的关键问题**：

- 什么时候"直接查写模型的表"就够了，什么时候才值得引入投影；
- 投影由领域事件（同进程）还是集成事件（跨服务）驱动，一致性时延差异；
- 投影落后于写模型时用户看到什么，"读自己的写"怎么处理；
- 投影坏了/改结构时如何重建；
- **投影的归属与落点**：投影表由谁写、放在哪个服务、与写模型同库还是独立库、跨 BC 的投影
  （订单列表要商品名而商品在另一个 BC）算谁的资产、别的上下文能不能直接查它。这一问决定投影
  是合法读模型还是通往别人数据的后门。

**预计涉及的组件**：`aipersimmon-ddd-cqrs`（`QueryBus`、`Projection`）、
`aipersimmon-ddd-inbox-mybatis-plus`（投影更新的幂等）、S3/S4 的事件链路组件。

### S13 多租户（X，寄宿 S4）

**场景描述**：同一套部署服务多个租户，租户身份要从入口一路传播到持久化行和跨服务事件，任何
一环不得读写别家租户的数据。

**要回答的关键问题**：租户从哪里解析；如何随 `CommandContext` → `EventEnvelope` →
`ce_tenantid` → 耐久列传播；MyBatis-Plus 的 SQL 级自动改写与 JDBC 手写谓词的差别；后台任务
（无请求上下文）如何带租户；自己的 `InnerInterceptor`（分页等）与框架的租户拦截器如何共存于
同一个 `MybatisPlusInterceptor`、顺序为何重要；**合法地不带租户 / 跨租户**：平台级数据、运维
查全租户、租户迁移与合并——旁路一旦存在，任何 bug 都能穿透它，这是最容易做成越权漏洞的地方。

**预计涉及的组件**：`aipersimmon-ddd-tenancy`、`aipersimmon-ddd-tenancy-mybatis-plus`、
`aipersimmon-ddd-tenancy-spring-boot-starter`、`aipersimmon-ddd-mybatis-plus-spring-boot-starter`。

### S14 操作日志（X，寄宿 S1）

**场景描述**：管理后台要求"谁、何时、对什么、做了什么、结果如何"的业务级审计记录，且业务拒绝、
系统失败也要留痕。

**要回答的关键问题**：注解式（命令上声明）与非注解式（类型安全 Definition）各适用什么；成功
日志与业务同事务、失败日志独立事务的语义；不该记什么（完整 command/entity 快照）；**写日志
本身失败时业务怎么办**——吞掉继续等于审计有洞，打断业务等于审计可用性 = 业务可用性，这是合规
决策不是技术偏好；**操作者身份（actor）从哪来**——这一问由本篇自己解决，不是别的场景的前置：
`OperationActorResolver` 是必须由使用方提供的 bean（没有默认实现，缺了启动就失败，且有专门的
FailureAnalyzer 指路），`Actor resolve()` 无参且 javadoc 要求只从可信边界取、**绝不能从命令载荷
取**，所以 HTTP 之外的入口（定时任务、outbox relay、inbox 消费）返回什么、由谁设置，是本篇要
给出的答案。

**预计涉及的组件**：`aipersimmon-ddd-operation-log`、`aipersimmon-ddd-operation-log-engine`、
`aipersimmon-ddd-operation-log-cqrs-spring-boot-starter`、
`aipersimmon-ddd-operation-log-mybatis-plus`。

### S15 可观测性（X，寄宿 S4）

**场景描述**：一次业务请求穿过 HTTP → 命令 → 聚合 → outbox → Kafka → inbox → 下游命令，排障
时需要一条完整 trace 而不是七段孤立日志。

**要回答的关键问题**：trace 上下文如何跨 outbox 的"存储再转发"断点接续；框架各边界（命令、
事件、流程步骤）各自出什么 span/metric；**不接 OTel 时靠什么排障**——日志里必须携带的关联 id
最小集合（traceId / messageId / correlationId / causationId）以及进 MDC 的方式，这条低成本
路径适用于不上 OTel 的团队。

**预计涉及的组件**：`aipersimmon-ddd-observability`、`aipersimmon-ddd-observability-otel`、
`aipersimmon-ddd-observability-otel-spring-boot-starter`。

### S16 战术建模：实体、值对象、聚合与规则原语（P0）

**场景描述**：在写任何管道之前，先把领域模型本身建出来。这是整套 samples 的概念地板：没有它，
读者会造出"outbox 接得很对但领域是贫血的"服务。库为此提供了一整套原语，本篇是它们唯一的家。

**典型例子**：订单聚合：订单行、金额值对象、状态机、下单与取消的规则。

**要回答的关键问题**：

- 实体 / 值对象 / 聚合根如何区分，聚合边界依据什么划（不变量的作用范围）；
- 不变量放在哪：**`Invariant` 抛异常，`Specification` 回答是与否**——用错一个，异常就变成了
  控制流，或者非法状态被写进库；
- 状态机用 `Transitions` 显式声明，与散落的 `if` 相比得到了什么；
- 领域服务、工厂各自解决什么问题，什么时候不需要它们；
- 六个构造块标注（`AggregateRoot` / `Entity` / `ValueObject` / `Identity` / `Repository` /
  `Service`）表达什么，以及 ArchUnit 如何据此约束代码；
- 聚合 id 由谁铸造：`IdGenerator` 与时间有序 UUIDv7 为什么重要（索引局部性、可分页），以及
  怎么替换它；
- 领域模块如何做到不依赖 Spring（只依赖契约模块）。

**预计涉及的组件**：`aipersimmon-ddd-core`（`Invariant`、`Specification`、`Transitions`、
`core/annotation/*`、`IdGenerator`）、`aipersimmon-ddd-archunit`。`-id-spring-boot-starter` 归 S1：
本篇是纯领域模块，不引 Spring。

**文档**：[[analysis-00016-samples-tactical-modelling]]（已完成）。

### S17 聚合与数据表的映射（P0）

**场景描述**：库提供版本校验写入与事件排空，**其余映射 100% 由使用方编写**。这一层是采用本库
的团队实际流血的地方，必须单独讲。

**典型例子**：订单聚合（含订单行子集合、金额值对象）落到 MyBatis-Plus 的两张表。

**要回答的关键问题**：

- 一个富领域模型如何变成行：谁负责组装、读出来时如何还原；
- **重建聚合时忘记还原版本会怎样**：version 为 0 → save 走 insert 分支 → `DuplicateEntityException`。
  这是最高频的踩坑，示例要能复现它；
- 部分字段更新 vs 整根覆盖：后者会把没带上的字段清成 null，如何显式表达"这一列确实要清空"；
- 子集合的写策略（删后重插 vs 差异更新）与各自代价；
- 值对象是扁平成多列还是存 JSON 列；
- 读路径能不能绕过聚合直接查表（与 S12/S20 的边界）；
- 领域声明仓储接口、读方法留在实现类，这种分工的理由。

**预计涉及的组件**：`aipersimmon-ddd-persistence-mybatis-plus`、`aipersimmon-ddd-core`。

**文档**：[[analysis-00018-samples-aggregate-persistence-mapping]]（已完成）。

### S18 分层测试策略（P0）

**场景描述**：库为测试投了三个模块，但没有任何示例告诉读者"聚合怎么测、handler 怎么测、
监听器怎么测、整链路怎么测"。缺这一篇，每个 sample 会各自发明测试风格，读者默认拿
`@SpringBootTest` 测一切。本篇同时**确立后续所有 sample 的测试风格**。

**典型例子**：为 S16/S17 的订单域配齐四层测试。

**要回答的关键问题**：

- 四层各测什么、各用什么：纯领域单测（无 Spring）、application 层用内存替身
  （`RecordingCommandBus` / `RecordingIntegrationEvents` / `InMemoryInbox` /
  `ImmediateUnitOfWork`）、架构规则测试、带容器的集成测试；
- 架构规则如何跑在**自己的**代码上，哪些规则是"不跑就丢保证"的（如版本推进围栏）；
- Testcontainers 如何共享单例容器而不是每个测试类起一个；
- 多租户下的测试如何指定租户；
- 集成测试里事件/流程这类异步链路怎么等待而不用 `sleep`；
- 什么情况下才值得写端到端测试。

**预计涉及的组件**：`aipersimmon-ddd-test`、`aipersimmon-ddd-test-support`、
`aipersimmon-ddd-archunit`。

**文档**：[[analysis-00019-samples-testing-strategy]]（已完成）。

### S19 校验的三层分工（P0）

**场景描述**：同一个"不合法"，可能是参数形状不对、可能是跨上下文的前置条件不满足、也可能是
聚合不变量被破坏。库为这三件事提供了三个不同机制，放错层会付出真实代价。

**典型例子**：下单请求：字段缺失 / 客户已被风控冻结 / 订单行 SKU 重复。

**要回答的关键问题**：

- Bean Validation（HTTP DTO 形状）、`CommandPrecheck`（进入事务之前的前置检查）、
  `Invariant`/`Specification`（聚合内不变量）各管什么；
- **`CommandPrecheck` 为何跑在校验之后、事务拦截器之前**：让跨上下文的咨询式查询不要占着数据库
  连接等远程响应，否则一个慢依赖会放大成连接池耗尽；
- 预检在 at-least-once 重投下会**重复执行**，因此它必须是只读且可重复的；
- 同一个规则在预检与聚合内重复表达时，谁是权威（预检只是提前失败，不是保证）；
- 三层各自的失败如何映射到 S1 定下的错误契约。

**预计涉及的组件**：`aipersimmon-ddd-cqrs`（`CommandPrecheck`）、`aipersimmon-ddd-core`
（`Invariant`、`Specification`）、`aipersimmon-ddd-web`。

**文档**：[[analysis-00022-samples-validation-layers]]（已完成）。

### S20 读侧查询契约：分页、排序、过滤（P0）

**场景描述**：列表查询是最高频的读需求，库为它准备了三种分页形状，但一个都没有示例。

**典型例子**：`GET /orders?customerId=&cursor=&size=` 的完整契约。

**要回答的关键问题**：

- `Page`（要总数）/ `Slice`（只要有没有下一页）/ `Cursor`（深翻页稳定）三选一的判据；
- 游标里编什么、为何应当对客户端不透明、翻页期间数据变动会怎样；
- 稳定排序为何依赖时间有序 id（与 S16 的 UUIDv7 呼应）；
- 过滤条件如何表达而不把查询变成动态 SQL 拼接；
- 查询返回类型：`Projection` 与 `ReadModel` 的分工，以及它们与聚合的距离；
- 什么时候该从"直接查写表"升级到 S12 的投影。

**预计涉及的组件**：`aipersimmon-ddd-cqrs`（`page/Page`、`Slice`、`Cursor`、`Projection`、
`ReadModel`）、`aipersimmon-ddd-web-spring-boot-starter`（游标序列化）、
`aipersimmon-ddd-persistence-mybatis-plus`。

**文档**：[[analysis-00023-samples-query-contract-paging]]（已完成）。落地时修正了上面第三条问法：
按 `IdGenerator` 的不透明契约，稳定排序依赖的是**排序键全序**，时间有序 id 只提供索引局部性，不是
可以拿来当业务排序的承诺。

### S21 事件契约演进与多版本共存（P0，双服务不同版次）

**场景描述**：跨服务事件一旦发出就是公共契约。加字段、删字段、改语义、换 topic，各走哪条路，
以及**两个部署单元处于不同版次**的灰度期如何共存。这是双服务系统最难演练、也最容易在生产
翻车的一件事。

**典型例子**：订单事件从 v1 演进到 v2，库存服务先于订单服务上线（以及反过来）。

**要回答的关键问题**：

- 兼容与不兼容变更如何界定，各自的发布顺序（发布方先上还是消费方先上）；
- upcaster 链（v1→v2→v3）如何写，以及铁律：**旧版次没携带的信息，upcast 不得凭空编造**；
- 事件类型的逻辑名与版本如何登记；未登记的版本为何应当进死信而不是猜；
- 灰度期两个版本共存多久、何时可以删掉 v1；
- 事件契约演进与数据库 migration（S23）的顺序如何对齐；
- 契约测试放在哪一侧、由谁维护。

**预计涉及的组件**：`aipersimmon-ddd-integration`（`EventUpcaster`、`EventType`、
事件目录登记）、S4 的全部链路组件。

### S22 运维面：死信、重放、保留清理与启动自检（P0）

**场景描述**：示例通常只演示顺利路径，但真正决定一个系统能不能上生产的是这些：表会不会无限
长大、毒丸消息怎么处理、坏消息如何重放、部署时表结构不对能不能提前发现。

**典型例子**：为 S4 的双服务补一套运维端点与配置。

**要回答的关键问题**：

- 发布侧死信：多少次尝试后进死信表，永久性失败（未知类型、载荷损坏）为何直接进死信；
- 运维如何看到死信、如何重放，重放的幂等靠什么保证；
- 消费侧死信主题必须**预先建好**：缺 `<topic>.DLT` 时毒丸记录会让分区在原地无限重试；
- 四类框架表（outbox / inbox / process / operation-log）的保留与清理为何默认关闭，打开时
  retention 该怎么定（inbox 的保留期必须长于最长重投延迟）；
- 启动时的表结构自检：缺表/缺列为何应当拒绝启动并指名迁移路径与开关；
- 能力降级必须出声：缺 id 生成器、`@Externalized` 无耐久 outbox、防护跑在内存 store 上，
  各自是启动失败还是 WARN；
- 这些组件的 `enabled` / `poll-delay` 在多实例部署下怎么配。

**预计涉及的组件**：`aipersimmon-ddd-outbox`（`DeadLetters` / `DeadLetterStore`）、
`aipersimmon-ddd-outbox-spring-boot-starter`、`aipersimmon-ddd-inbox-mybatis-plus`、
`aipersimmon-ddd-flyway-spring-boot-starter`、`aipersimmon-ddd-web-store-jdbc`（清理调度）。

### S23 Schema 演进与数据迁移（P1）

**场景描述**：框架表与业务表的迁移如何共存，以及给已上线的聚合改结构。

**要回答的关键问题**：`aipersimmon.ddd.flyway.components` 与业务自己的 Flyway location 如何
共存、谁先跑；给已上线聚合加列/拆表的不停机步骤；历史数据回填该不该走命令通道（与 S11 呼应）；
多服务各自库的迁移顺序如何与 S21 的契约演进对齐；生产为何禁 `clean`；同一库里多个上下文的
迁移如何隔离编号。

**预计涉及的组件**：`aipersimmon-ddd-flyway-spring-boot-starter`、
`aipersimmon-ddd-persistence-mybatis-plus`。

### S24 在既有服务里新建一个限界上下文（P1）

**场景描述**：读完前面所有示例后，团队要做的第一件事就是加一个新上下文。没人讲过这件事的步骤。

**典型例子**：在既有订单/库存服务里新增"优惠券"上下文。

**要回答的关键问题**：新上下文的模块从哪来、包结构长什么样；它的 `api` 包必须暴露什么、不得
暴露什么；上下文之间只经 `api` 依赖这条规则如何用 ArchUnit 接上；共享内核（Money 这类）放哪、
什么东西不该进共享内核；新上下文与既有上下文之间第一条集成用事件还是同步调用；这个上下文什么
时候该独立成部署单元、独立时哪些东西必须先改。

**预计涉及的组件**：`aipersimmon-ddd-archunit`（上下文隔离规则）、`aipersimmon-ddd-core`、
`aipersimmon-ddd-integration`。

### S25 遗留系统绞杀式接入（P1）

**场景描述**：采用本库的团队几乎没有绿地项目，而库的默认前提是"你拥有 schema"：version 列、
UUIDv7 主键、租户判别列、框架表。遗留表是自增主键、没有版本列、几百万行时该怎么办。

**典型例子**：从一个 Service + Mapper 的遗留订单单体里切出第一个聚合。

**要回答的关键问题**：如何从泥球里选出第一个聚合、按什么顺序切；遗留表没有 version 列时的
过渡方案（加列 / 影子表 / 悲观锁）；自增主键与 UUIDv7 并存期怎么处理；双写期间新旧两条路如何
不打架，outbox 能不能用来把事实喂给新上下文；遗留代码如何被 ACL 包住而不是被到处直接调用；
迁移完成的判据是什么、旧路径何时删除。

**预计涉及的组件**：`aipersimmon-ddd-persistence-jdbc`（更贴近遗留 SQL）、
`aipersimmon-ddd-outbox-jdbc`、`aipersimmon-ddd-core`、`aipersimmon-ddd-application`。

### S26 读侧加速：缓存与投影的取舍（P1）

**场景描述**："查询太慢"有两条解法：投影（S12）与缓存。库对缓存不提供任何东西，但团队一定会
接 Redis，且很容易接坏。本篇把两条路放在一起比较。

**要回答的关键问题**：什么慢该用投影、什么慢该用缓存；缓存挂在 `QueryBus` 拦截器还是仓储层；
**聚合本身能不能缓存**（不能——`version()` 的语义会被绕过，写入保护随之失效）；写后失效与事务
提交、事件发布的时序；缓存击穿/雪崩在这套结构里的落点；多租户下的 key 前缀（web store 已有
先例）；缓存不一致的可观测性。

**预计涉及的组件**：`aipersimmon-ddd-cqrs`（`QueryInterceptor`）、
`aipersimmon-ddd-web-store-redis`（租户前缀先例）；Redis 客户端为三方选型。

### S27 软删除、数据保留与擦除（P1）

**场景描述**：删除在 DDD 里几乎从不是"删掉一行"。它可能是领域状态、可能是基础设施开关、也
可能是一次合规擦除；三者的处理方式完全不同。

**要回答的关键问题**：软删除是领域状态（"已作废"，进状态机）还是基础设施开关（逻辑删除标记）
——判据是什么；逻辑删除与唯一索引的冲突怎么解；整根覆盖写入遇上逻辑删除列的相互作用；一次
合规擦除对**已发出的事件、inbox 幂等键、审计行**分别意味着什么（这条最难，且最容易被无视）；
保留期与法务/审计要求如何对齐；被擦除实体的历史事件重放会怎样。

**预计涉及的组件**：`aipersimmon-ddd-persistence-mybatis-plus`、
`aipersimmon-ddd-operation-log-mybatis-plus`、`aipersimmon-ddd-inbox-mybatis-plus`。

### S28 长耗时与大数据量端点（P1）

**场景描述**：文件上传、导出百万行、耗时数分钟的作业。库只给了分页，团队会误把
process-manager 当作业队列用。

**要回答的关键问题**：同步接口的时间上限在哪，超过之后 `202 + 作业资源 + 进度查询` 的契约怎么
定；作业状态该不该是一个聚合；**为什么不该用 process-manager 当作业队列**，以及正面的替代
形态是什么（S9 只问了上限，没给替代）；大导出如何流式输出而不撑爆内存与事务；上传的分片、
断点与幂等（与 S2 呼应）；作业失败的可见性与重试。

**预计涉及的组件**：`aipersimmon-ddd-cqrs`、`aipersimmon-ddd-web-spring-boot-starter`、
`aipersimmon-ddd-core`（作业聚合）；对照 `aipersimmon-ddd-process-manager`。

## 3. 待验证的技术前提

这两条不是文档选择，是会决定 sample 能否写成的事实，需在对应场景动工前先验证。

### 3.1 Seata AT 与本库拦截器能否共存（阻塞 S10）

Seata AT 通过解析它拦截到的 SQL 生成 before/after 镜像来构造 `undo_log`；而本库的聚合写入
已经被乐观锁的版本条件与租户改写过。两层 SQL 改写叠加是否仍能正确回滚，**目前无人验证**。
S10 要求完整可运行，所以这条必须先跑通一个最小验证：一个带 version 列的聚合在 AT 模式下被
全局回滚，检查数据与 `undo_log` 是否正确。若不兼容，S10 主线改用 TCC（Try/Confirm/Cancel 与
聚合方法一一对应，本身也更贴 DDD），AT 作为"为什么不用"的对照写进文档。

### 3.2 从未被任何场景覆盖的库能力（本轮已全部认领）

初版总纲有九个模块无人使用，现已分别落到：`-test` / `-test-support` / `-archunit` → S18；
`-flyway-spring-boot-starter` → S4 + S22 + S23；`-id-spring-boot-starter` → S16；
`-mybatis-plus-spring-boot-starter`（拦截器排序）→ S13；`-starter`（无库服务）→ S6；
`-bom` → samples 父 POM 的导入顺序说明。`-quality-config` 是构建期插件配置，不是消费方依赖，
**无法也不需要由 sample 演示**。

同时补上了六个此前不被任何场景触及的库类型：`Invariant` / `Specification` / `Transitions`
（S16）、`CommandPrecheck`（S19）、`Page` / `Slice` / `Cursor`（S20）、`EventUpcaster`（S21）、
`DeadLetterStore`（S22）、六个构造块标注（S16）。

## 4. 写作与实现顺序

写作顺序与阅读顺序不同：先立地基与模板，再铺场景。

1. **地基（先做，且必须先冻结）**：S1（模板：目录布局、父 POM、错误契约、
   README 结构、"怎么跑起来"）→ S16 → S17 → S18。S18 必须排在这里而不是最后，因为它确立
   测试风格；晚写就要回头翻修所有 sample。
2. **一次命令的内部**：S3 → S8 → S19。
3. **入站边界**：S2 → S20 → S11。
4. **跨服务**：S4 → S21 → S5 → S6 → S7。
5. **协调与一致性**：S9 → S10（先做 §3.1 的验证）。
6. **读侧与运维**：S12 → S22 → S23。
7. **P1 其余**：S26 → S27 → S28。
8. **接入既有系统（最后）**：S24 → S25。放在最后是因为它们要用到前面全部词汇。

依赖关系（比初版更正）：**所有场景都依赖 S16/S17**；**S8 是 S4 的前置**（"业务行与 outbox 行
同一事务"是 S8 的课，S4 消费它）；S2 与 S7 的回调入站共用一套 `web/spi` 机制；S13/S15 依赖
S4 的 sample 代码存在；S14 自己要选定操作者身份（actor）的可信来源，并覆盖没有 HTTP 请求的入口；S21 依赖 S4；S26 与 S12 成对。

## 5. 工程约定（随第一篇 S1 一并冻结）

- **父 POM 统管**：`aipersimmon-ddd-samples/pom.xml` 聚合全部子目录，一次 `verify` 覆盖所有
  sample；CI 每次全构建，避免示例悄悄烂掉。
- **BOM 导入顺序**：父 POM 需说明 `aipersimmon-ddd-bom` 与 `spring-boot-dependencies` 的导入
  次序要求。
- **双服务 sample 的布局**：一个目录内放两个服务模块（如 `ordering-service/` +
  `inventory-service/`）加一个 `docker-compose.yml`；端口分配需一个全局约定（按 sample 分段），
  随 S1 一并定。
- **业务域按需选取**：不追求全局统一域；天然共用的场景共用（订单域被多篇复用），需要新域的
  场景直接用新域（S5 用商品主数据、S10 用账户+积分、S27 用客户资料），不为统一而牵强。
- **每个 sample 自带 README**：场景一句话、怎么跑、看哪几个测试验证了哪个断言。

## 6. 本轮明确不覆盖

- 各场景的实现细节与代码组织——留给逐场景文档；
- 安全、认证与授权；
- 特性开关与灰度发布（交付关注点，与 DDD 无关；其中"灰度期事件契约如何对齐"这一片段归 S21）；
- 前端交互与 BFF 形态；
- 性能压测与容量规划。
