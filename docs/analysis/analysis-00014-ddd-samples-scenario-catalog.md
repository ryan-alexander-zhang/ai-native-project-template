---
id: analysis-00014-ddd-samples-scenario-catalog
type: analysis
status: draft
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
| S15 | 可观测性：跨边界追一次请求（**不是一条 trace**，见 00028） | X（寄宿 S4） | — | — |
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

**文档**：[[analysis-00025-samples-integration-events-across-services]]（已完成）。落地时发现两件事：消费侧的
去重**由库的消费桥负责**（`KafkaIntegrationEventListener:152`），handler 再查一遍会让每条消息静默跳过；以及只
消费的服务会被发布侧的启动检查误报，见
[[issue-00161-the-publisher-guard-misreads-a-consumer-as-a-publisher]]。

**S13 与 S15 已寄宿完成**（同一份代码，各有自己的文档）：
[[analysis-00027-samples-multi-tenancy-end-to-end]]、
[[analysis-00028-samples-one-trace-across-the-boundary]]。

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

**文档**：[[analysis-00029-samples-external-messages-inbound]]（已完成，单模块，**故意不装**
`-starter-messaging-kafka`）。落地时的主要发现：**"幂等键从哪来"这一问要先问这条消息需不需要键**——绝对状态 +
每实体单调 revision 的消息，排序守卫已经让重投按内容成为 no-op，**一个去重键都不需要**（测试断言 inbox 零行）；
只有**相对语义**（降 10%）的消息必须要，而键**只能由上游提供**，payload hash / `(topic,partition,offset)` / 到达
时现铸都恰好在重放时失效——上游给不了就死信，不猜。排序选**每实体单调计数器**而不是上游时间戳（同毫秒即退化为按
到达顺序），且 `upstreamRevision` 是**领域状态**、与本行乐观锁 `version` 分开两列。另有两条测试手艺：死信 topic
名不能用 Spring Kafka 的默认（不是 `.DLT`，实际去了 `<topic>-dlt`）；`untilAsserted` 只重试 `AssertionError`，
所以"等一行出现"的辅助方法必须返回 null 而不能抛 `EmptyResultDataAccessException`。

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

**文档**：[[analysis-00030-samples-synchronous-call-between-services]]（已完成，两个服务模块）。落地时把"何时该同步"
的判据收敛成一句：**同步调用能拿到"判断"，永远拿不到"预留"**——需要对方持有东西就是 S9/S10，不是一次提问。另外
三条上面没问到的：被调方**拒绝必须是 200 + `approved:false`**（用 4xx 会让调用方分不清业务拒绝与自己发错了），
调用方把对方的 **4xx 也翻译成"没有答案"**（4xx 意味着本服务有缺陷，不该说成客户订单被拒），以及 `ErrorCategory`
**没有"依赖不可用"这一档**（`UNEXPECTED`→500 既错在归属又叫客户端别重试），要用 `ProblemCatalog` 按 code 覆盖成
503。precheck 在事务之前这条用探针直接测了（precheck 里无事务、handler 里有）。踩坑三件（都是 sample 的，不是库的）：lambda 写的 precheck
启动期被拒（库的守卫，`PrecheckCommandInterceptor:93-109`；**带 diamond 的匿名类是好的**——查过了）、
`SpringApplicationBuilder.properties()` 优先级低于 `application.yaml`、JDK HttpServer 默认 executor 是单线程
——**这一条是一个 bug 两个症状**：既让"重试没发生"成为假象，又让上一个测试的 sleep 拖到下一个测试超时、风控
precheck 抛异常短路掉它后面的探针 precheck。第二个症状我最初错误归因成"precheck 必须具名类"，是编的；隔离之后
更正。

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

**文档**：[[analysis-00031-samples-third-party-integration]]（已完成，两个模块：我们的支付服务 + 一个不受控的
网关 stub；process-manager 没用上，悬挂态用聚合状态 + 定时对账推进，编排引擎留给 S9）。落地结论：**出站真的走
了库的 outbox**（`@Externalized("gateway:charges")` + 自己的 `EventDestinations` 与 `OutboxDispatcher`），换来
同事务落库、租约、退避、死信；代价三条——一个应用只有一个 dispatcher（所以要自己组合回进程内那条腿，否则 LOCAL
事件被无声标记已发送）、中继是快递员不能记录答案、因此三方契约必须是"同步只回 202"。幂等键用 payment id，并且
**只有"已扣款、响应丢了"这种失败能证明它**（换键重试实测双扣；扣款前失败换键也只扣一次，原来的用例名过度声
明，为此给 stub 加了 `LOSE_FIRST_RESPONSE`）。入站用 web-store-**jdbc**（S2 用 redis，两种都覆盖），核心区分是
**传输重放 ≠ 业务重复**：网关重发换新 nonce 新签名，两条都是真的，去重只能在聚合里。乱序靠状态 rank，不信到达
顺序也不信对方时钟。矛盾终态 / 未知 result code / 未知 payment：前两个一律 2xx + 交人（未知即失败是这类集成最
贵的一行代码），第三个 404。对账通道必须有，并且我第一版写错了：`NoRecord` 立即升级，结果对账定时器比中继早
13ms，把一笔还没发出去的付款永久打标（打标是粘性的）——改设计而不是改测试，**对缺席要有耐心**，由此 `stale-after`
有下界。布局分歧一处：回调 controller 放在 `infrastructure.gateway` 而不是 `interfaces`（它不是我们的 API，是
出站调用的返回路径），换来两个方向共用一张 code 表，整包 package-private。八个负向对照全部单跑实测，其中
`nonce.enabled=false` 那一轮查出库的问题 → [[issue-00162-nonce-dedup-off-makes-a-nonce-bound-signature-unverifiable]]。

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

**文档**：[[analysis-00032-samples-eventual-consistency-process-manager]]（已完成，单模块三聚合：座位/钱包/订单，
业务域是卖一张票）。落地结论：选**编排**的判据是"补偿有顺序（先退钱→再放座→最后取消），有顺序的东西必须有个地方
住"，代价是参与者的 handler 知道存在协调者。**补偿不是回滚**用钱包分录表钉死：退款是**另一条 credit**
（`refund-of:<那笔 debit>`），两条永久留在流水上；放座是 `released_at` 而不是 DELETE；出票之后是**不可回头点**，
而且库比我的代码更强——终态实例被 runtime 直接短路（`DefaultProcessRuntime:510`），定义连问都不会被问到。
**谁持有真相**：聚合的 status 是真相，流程的 step 只是"在等什么"，两者按设计会不一致（AWAITING_TICKET 时订单仍
PLACED，有测试断言这个窗口是对的）；判据一句话——**流程可以记事实，不可以记结论**，并有一条反射
`getRecordComponents()` 的结构性测试禁止 state 里出现 OrderStatus。定义是纯的（ArchUnit 钉住），回报是 7 个不用
数据库的单测，代价是后续步骤要的东西必须由 state 带走。**不需要 inbox**：effect 以自己的持久化 message id 投递，
handler 原样交回当 cause，重投产生逐字节相同的 input id 被 runtime 认出——inbox 管的是外部消息，这是同一想法的
另一个边界。**codec 必须显式登记**，实测：删一条登记＋保留 `declaredPayloads` → 启动失败点名；声明改空 → 启动成功
且 24 个用例只有 1 红（那条补偿分支）。运维介入走完整条 DEAD→SUSPENDED→改数据→`redriveEffect`→恢复→出票；
`redriveEffect` 只接受 DEAD（我最初拿它模拟重投，报错才发现）。什么时候该换 Temporal：state 里开始出现计数器和
待办列表就已越界。两个库的问题：
[[issue-00163-process-manager-worker-enabled-removes-the-bean-the-outbox-keeps]]（同名属性在 outbox 是停调度、
在 pm 是删 bean，测试没法手动驱动）、
[[issue-00164-no-port-tells-an-operator-why-an-instance-is-suspended]]（挂起原因只说"哪件事"，"为什么"只在
effect 行的 last_error 里，没有端口能读）。

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

**文档**：[[analysis-00033-samples-strong-consistency-seata]]（已完成，三模块：account-service +
points-service + 一个端到端测试模块，两个库、一个真 seata-server，29 个用例）。**§3.1 的阻塞前提已验证通过**：
AT 与本库的两层 SQL 改写（乐观锁的 `WHERE version = ?` + 租户行的 `AND tenant_id = ?`）能共存，所以主线用 AT，
TCC 作为可度量的对照而不是退路。证据是直接读 `undo_log.rollback_info`：前后镜像都把 `version` 当普通列捕获
（前 1 后 2，所以回滚会还原它——否则数据对而行永久不可写）、`tenant_id` 与 `id` 都是 `PRIMARY_KEY`（从表元数据取的
复合键）、cleared-column 的 `SET ... = NULL` 也进了后镜像。**为什么能共存一句话**：拦截器在 DataSource 之上改写，
Seata 的代理就是 DataSource，等它解析时改写早已完成。**最先撞到的墙是 undo_log 不能由应用迁移**：Seata 在
`DataSourceProxy.init` 里无条件检查这张表，那发生在 DataSource bean 构造期间，而 Flyway / `spring.sql.init` /
本库的 flyway components 全在其后；没有属性能推迟，所以它属于数据库侧，连带要 `baseline-on-migrate: true` +
`baseline-version: 0`。**边界画在命令总线上面一层**（分支＝一次已提交的本地事务＋它的 undo log，所以本地事务必须在
全局事务内部起止），并附 Spring 与 Seata 回滚默认值相反这一条。**"强一致"不等于没有中间态**：扣款真的提交了，
普通读者看得见，实测在事务开着的第 1 秒断言；它保证的是别的**全局事务**碰不到。这既是保证也是账单，实测 AT
锁住整行整个业务事务（锁键 `s10_points_account:acme_shared-loyalty;s10_points_entry:acme_contend-at`——完整复合主键，
且**子行和根行一起锁**）而 TCC 在 Try 提交后就放开。**AT/TCC 判据**：TCC 的三方法是聚合承认"已预留"属于它自己的
语言——业务本来有这个词就该补上，没有就是在造假状态，AT 更诚实；竞争程度是决胜局不是首要问题。对本库使用者的
具体后果：全局锁超时是 `QueryTimeoutException` 不是 `OptimisticLockingFailureException`，`retry-on-conflict`
认不出，本篇显式关掉并写了理由。端到端模块顺带暴露三个"两个 Spring Boot 应用共用一个 classpath"的冲突（base
package 互吞、两个 `application.yaml` 一个赢、两个 `V1__*.sql` 撞版本号），都在服务侧改掉；以及
`SpringApplicationBuilder.properties(...)` 落在 `defaultProperties` 优先级低于应用 yaml，覆盖会被静默忽略。
**本轮没有发现库的问题**，这本身是结论：库的写路径在第三方分布式事务框架下一行都不用改。

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

**文档**：[[analysis-00024-samples-scheduled-and-batch-entries]]（已完成）。落地时修正了上面
"多实例部署时的调度互斥"这条的隐含前提：**互斥不属于调度，属于工作**——库自己的 outbox relay 让每个
实例都跑调度、按行领取，并在 javadoc 里说明了锁调度为何是更差的交换。因此三方调度锁（ShedLock 等）
不是本篇的答案，也不是库需要的东西。

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

**文档**：[[analysis-00034-samples-cqrs-read-model]]（已完成，两个服务：catalog 发布 / ordering 持有投影，
38 个用例，无第三个联测模块——理由写在 sample 父 POM 里：S10 那种 harness 是因为"一个全局事务跨两个资源"没法
单服务观测，而这里被测的是线上记录的形状，两侧各自对着契约测就够，沿用 S4 的做法）。**什么时候不该上投影**：
S20 就是对照且是常见情况（直接查写表 + 游标分页），判据不是"查询变慢"而是**"查询需要本上下文不拥有的数据"**
——自己表上的慢查询是索引问题。**三张表三种生命周期**：写模型（真相）/ 本上下文对目录名字的**副本**（不能删）/
投影（随时能删）；中间那张最容易被省掉，而省掉它投影就不可重建。**跨上下文副本算谁的资产**：副本是持有方的
资产（目录关掉也能查能重建），由此三条——不可读对方的库、任何第三方不可读这张副本表（那是带 schema 的后门）、
发布方不可知道谁在抄（ArchUnit 钉方向）。**最锋利的一处**：`name_at_purchase`（写模型，永不变，发票要的）与
`display_summary`（投影，跟着目录走，列表要的）是同一个值的两个相反要求，一个测试断言两边——**被复制的值要么
是业务事实要么是展示缓存，是哪一种决定用什么机制**。**重建**六行，只因为两个更早的决定：整行重算绝不增量
（事件/改名/重建同一条码路，产出逐字节相同）+ 名字读副本而非从消息抄进投影行（重建的每个输入都是自己的表）；
没有副本重建会产出被冻结的旧名字。**一张表两个时钟**（订单事实进程内零延迟 vs 商品名跨服务要 await），
`projected_at` 是列且返回给调用方。**一次改名的账单**：每个曾包含该 sku 的订单各重算一行，无上界——查询负载
离开读路径、被放大后出现在你控制不了频率的事件的写路径上。**§7 被负向对照改写过**：我原本说"同事务才有
read-your-own-writes"，控制 1 跑出 0 红证明该说法未被测到且在本形状下是错的（总线自己开事务、外无包裹，
`AFTER_COMMIT` 仍在 `send` 返回前同步跑完）；阶段真正决定的是**故障往哪边传**，那 1 红的测试是因此才补的。
库的问题：没有新的，独立复现了 [[issue-00161-the-publisher-guard-misreads-a-consumer-as-a-publisher]]；
另记两条非缺陷交互：两个框架组件 ⇒ 两个 `Clock` bean 无 primary（按类型注入启动失败，应用要自己声明名为
`clock` 的 bean 按名字解析），以及 `Projection` 注解 javadoc 的 "in the same transaction" 比现实窄
（跨服务驱动的投影不可能同事务）。

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

**文档**：[[analysis-00027-samples-multi-tenancy-end-to-end]]（已完成，寄宿 S4 的两个服务）。落地时补了三条上面
没问到的：**allow-list fail open**，所以库有启动期自检（漏登记一张带 `tenant_id` 的表就一个谓词都不加）；
**隔离要落在唯一键里，不只落在谓词里**——把表从 `tenant-tables` 里删掉后不是静默泄漏而是
`TooManyResultsException` 无限重试导致分区停滞，而这份"响"只因为主键是 `(tenant_id, sku)` 复合的；以及 inbox
的**去重键不含租户**（`MybatisPlusInbox:48-52` 写明），所以生产者的 id 必须在 source 内唯一而不是在
`(source, tenant)` 内唯一。另有一条装配实账：`mybatis-plus-jsqlparser` 是 `provided`，使用方要自己加，漏了在
启动期 `NoClassDefFoundError: TenantLineHandler`。

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

**文档**：[[analysis-00038-samples-operation-log]]（sample：`aipersimmon-ddd-samples/s01-http-command-query`，
S1 原有 15 个用例 + 本篇 21 个 = 36 个）。**本篇是补交**——S13 与 S15 随宿主 S4 交付了，S14 一直没有，
2026-08-04 之前 samples 树里没有任何模块依赖过这个组件。五问全部以实测作答，四点要回填进本清单：

1. **"注解式与非注解式各适用什么"的判据不是"要不要 before/after"。** 更硬的约束是：注解的 `targetId`
   模板只编译在一个 root `input` 上（`AnnotationOperationLogDefinition.java:58`、`:111`），所以**一个在
   handler 里铸造 id 的创建，注解根本无法指向它的目标**——`${input.customerId}` 会编译、会启动，然后把
   每条订单操作记在一个客户 id 名下。由此还推出一条没人会预料的后果：**"能不能审计一次失败"取决于目标
   的身份是否在操作之前就存在**，因为 `Target` 要求非空 id。要审计失败的创建，身份必须在命令之前铸造。
2. **actor 的答案是"绑定 + 清除"，而清除是唯一的安全属性。** 未绑定返回 `Actor.system(应用名)`（抛异常
   会让审计可用性变成业务可用性；空 actor 与 bug 无法区分；回退到上一个 actor 就是那个失败本身）。
   **我为泄漏写的第一个测试是空的**：控制（删掉 filter 的 `finally`）跑出 **0 红**，因为
   `TestRestTemplate` 的请求在容器线程、后续命令在 JUnit 线程，两条线程从来不是同一条。改成在本线程上
   直接驱动 filter 后同一控制 **2 红**。教训：**跨线程泄漏对任何不停在一条线程上的测试都不可见。**
3. **`outcome` 与 `completion` 是两列不是一个状态**，我猜错了两次：领域拒绝与校验失败**都是 `REJECTED`**
   （不是 `FAILED`——那是并发冲突与意料之外的），区分它们的是 `completion`（`ROLLED_BACK` vs
   `NOT_STARTED`）。`failure_code` 直接是本上下文的 `ErrorCode`，与 HTTP problem document 同码。
4. **"不该记什么"的机制是 allowlist，不是事后脱敏。** `OperationLogs.record` 的 javadoc 说它 "redacts"
   容易被读成会替你脱敏 PII；`Redactor` 实际做的只是去 CR/LF + 截断，它的类 javadoc 自己写明不负责判断
   敏感性。**写入之后没有第二道关**——这正是 S27 的合规擦除要面对的前提。模板白名单里的 `mask` 是遮蔽
   不是删除（首字符 + `***` + 末字符）。

库的问题：**一条也没有**。本篇被库纠正了三次（`Actor.system` 的 displayName、两处 outcome），三次都是
库更对。

### S15 可观测性（X，寄宿 S4）

**场景描述**：一次业务请求穿过 HTTP → 命令 → 聚合 → outbox → Kafka → inbox → 下游命令，排障
时需要一条完整 trace 而不是七段孤立日志。

**要回答的关键问题**：trace 上下文如何跨 outbox 的"存储再转发"断点接续；框架各边界（命令、
事件、流程步骤）各自出什么 span/metric；**不接 OTel 时靠什么排障**——日志里必须携带的关联 id
最小集合（traceId / messageId / correlationId / causationId）以及进 MDC 的方式，这条低成本
路径适用于不上 OTel 的团队。

**预计涉及的组件**：`aipersimmon-ddd-observability`、`aipersimmon-ddd-observability-otel`、
`aipersimmon-ddd-observability-otel-spring-boot-starter`。

**文档**：[[analysis-00028-samples-one-trace-across-the-boundary]]（已完成，寄宿 S4 的两个服务）。落地时修正了上面
的标题前提：**它不是"一条完整 trace"，也不该是**——outbox 那跳库开的是一个 link 回去的**新 trace**（库自己的
`ConnectedTraceEndToEndTest` 断言 `assertNotEquals` 两个 trace id），所以下游报出的 trace id 不是那次 HTTP
请求的；端到端逐字节相同的是 **correlationId**，且不需要任何后端。另外两条：调用方手里的 `X-Request-Id` 与消息层
的 `correlationId` 是**两个 id、数据上没有桥**；消费侧四个 MDC 键里**有三个不存在**（都由 servlet 过滤器写），
而租户确实绑上、trace 确实续上——是日志缺口不是传播故障。

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

**文档**：[[analysis-00026-samples-event-contract-evolution]]（已完成）。落地时补了三条上面没问到、
但比已问到的更容易翻车的：**发布方的 outbox 积压带着旧版次继续发**（所以"何时可删 v1"是
`max(topic 保留期, 积压排空, 死信重放窗口)`，中间那项消费方看不见）；**灰度期双发两个版次＝同一事实生效
两次**，inbox 救不了；**只删 upcaster 而留着退役类＝旧记录静默消失**（删类是死信，响）。另外把"加字段"
拆成两种：纯加可选字段**不用升版次**，重构/删字段才要。

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

**文档**：[[analysis-00035-samples-operability-deadletters-retention]]（sample：
`aipersimmon-ddd-samples/s22-operability-deadletters-retention`，两个服务，42 个用例）。上面七问全部
以实测作答，另外四点要回填进本清单：

1. **前提是一个 broker 设置**：`KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`。开着自动建 topic 时，"发布进
   没建的 topic"和"缺 `<topic>.DLT`"两个隐患**都不可观察**——前者静默成功，后者的 DLT 被那次投递自己建
   出来。本 sample 是唯一自带 broker 配置的样例，其余样例跑在宽松默认上。
2. **框架表是五个组件不是四个**（`outbox` / `inbox` / `process-manager` / `operation-log` /
   `web-store`），而且默认**不统一**：前四个默认关，**`web-store` 默认开、每小时**
   （`WebStoreCleanupProperties.java:17`）。底下的规则是"行本身写明了自己何时不再重要，才可以默认清"。
   另外 `aipersimmon_dead_letter` **根本不在任何清理里**。
3. **消费侧的第三档**清单里没提，但它决定事故形态：`DataAccessException` 被判为环境故障，**无上界重试、
   永不进 DLT**，分区原地等待。少了这一档，十分钟的数据库抖动会把分区以重试速度排空进 DLT。
4. **"启动失败 vs WARN"没有统一规则，只有统一的判据**：这个缺失之后还有东西能发现它吗？不能就必须是启动
   失败（发布进死胡同就是这类：relay 会把事件标成已发送，没有异常/死信/lag）；能就 WARN 加严格开关。

新开 issue：[[issue-00165-a-dead-letters-last-error-drops-the-only-useful-half]]（死信的 `last_error` 只
记最外层异常，最常见的发布失败因此记成 `KafkaException: Send failed`，topic 名与真因全丢）。

### S23 Schema 演进与数据迁移（P1）

**场景描述**：框架表与业务表的迁移如何共存，以及给已上线的聚合改结构。

**要回答的关键问题**：`aipersimmon.ddd.flyway.components` 与业务自己的 Flyway location 如何
共存、谁先跑；给已上线聚合加列/拆表的不停机步骤；历史数据回填该不该走命令通道（与 S11 呼应）；
多服务各自库的迁移顺序如何与 S21 的契约演进对齐；生产为何禁 `clean`；同一库里多个上下文的
迁移如何隔离编号。

**预计涉及的组件**：`aipersimmon-ddd-flyway-spring-boot-starter`、
`aipersimmon-ddd-persistence-mybatis-plus`。

**文档**：[[analysis-00036-samples-schema-migration]]（sample：`aipersimmon-ddd-samples/s23-schema-migration`，
一个部署单元、两个上下文，26 个用例）。六问全部以实测作答，四点要回填进本清单：

1. **"谁先跑"的答案背后有个坑**：库那个 `FlywayMigrationStrategy` 是 `@ConditionalOnMissingBean`，
   而**第二个上下文逼你定义自己的 strategy**——一定义，框架组件的 migration 就不再运行。底线是启动失败
   （validator 拦住），但**消息指向的修法是错的**（它让你去加 `flyway.components`，那一行通常本来就对）。
   习惯：自己写的 strategy 最后一行必须调 `AipersimmonFlywayMigrator.migrate`。
2. **"不停机步骤"里最关键的一步不是 migration**：扩(V2) / **部署** / 缩(V3)，中间那步是"停止写旧列的
   release"，不在 `db/migration` 里，也正是被跳过的那一步。且缩之前的**等待**以天计——列一删，应用就不能
   回滚了。
3. **回填判据一句话**：把行里已有的字节重新表述是 SQL；**做判断、或必须告知任何人**，是命令。V2 是前者、
   V4 是后者。命令通道额外买到规则单份、公告与变更同事务、聚合级幂等、分页可停。
4. **编号隔离的代价是一行 baseline**：跑在第二位的上下文历史里会多出 `version 0`（schema 已非空必须
   baseline），因此 baseline 版本必须是 `0` —— 写 `1` 会把它的 V1 标成已应用而表永远不建。实测得出。

另记一处**诊断误导**（未开 issue，属文案+一行日志）：框架 strategy 退让时不出声，validator 的建议又指向
一个已经正确的配置项。

### S24 在既有服务里新建一个限界上下文（P1）

**场景描述**：读完前面所有示例后，团队要做的第一件事就是加一个新上下文。没人讲过这件事的步骤。

**典型例子**：在既有订单/库存服务里新增"优惠券"上下文。

**要回答的关键问题**：新上下文的模块从哪来、包结构长什么样；它的 `api` 包必须暴露什么、不得
暴露什么；上下文之间只经 `api` 依赖这条规则如何用 ArchUnit 接上；共享内核（Money 这类）放哪、
什么东西不该进共享内核；新上下文与既有上下文之间第一条集成用事件还是同步调用；这个上下文什么
时候该独立成部署单元、独立时哪些东西必须先改。

**预计涉及的组件**：`aipersimmon-ddd-archunit`（上下文隔离规则）、`aipersimmon-ddd-core`、
`aipersimmon-ddd-integration`。

**文档**：[[analysis-00041-samples-add-bounded-context]]（sample：`s24-add-bounded-context`，三个上下文 +
共享内核，44 个用例）。本条的交付物是**六条规则**，其余都是把规则说清楚的工作示例——因为文档能说的和两年后
还成立的差得最远。

1. **新上下文是一个包，不是一个 Maven 模块。** 模块给编译期隔离，而"别人不能碰我的内部"一条 ArchUnit
   规则就能买到，且能在包还空着时装上。让"以后能拆"仍然成立的是五件第一天免费的事：建 `api`（哪怕空的）、
   装隔离规则、表前缀 `s24_<context>_`、自己的 migration、每包一份 package-info。
2. **api 的机械判据：它不依赖自己上下文里的任何东西，是一片叶子。** 库没有这条——`BoundedContextRules`
   管"别人从前门进"，不管 api 往回摸什么。一个装着聚合的 `CouponQuote` **完美通过库的规则**并把模型发布
   出去（实测 1 红，库全绿）。附带一条不对称：ordering 的契约只有一个事件，比 coupons 小得多，因为
   **上下文发布的是别人需要的东西，不是自己的投影**；`inventory.api` 那个无人消费的事件留着当反面样本。
3. **共享内核放 `sharedkernel.api`，而这是库的规则推出来的**：`sharedkernel` 也是一个上下文段，挪出 api
   实测红 **82 处**。被迫的形状恰好是对的——共享内核是最"已发布"的东西，且不该有内部（没有
   `sharedkernel.domain`）。**判据的机械形式：共享内核不依赖任何上下文。** `CouponCode` 两个上下文都在用，
   仍然不是共享内核，因为只有 coupons 有发言权。
4. **一次 join 就能悄悄决定"分不开"，而 ArchUnit 看不见**——它读 Java，表名是字符串。所以
   `TableOwnershipTest` 去读 mapper 注解里的 SQL 与 `@TableName`：加一句跨表 join，实测 **1 红，
   10 条 ArchUnit 规则全绿**。它故意很糙，因为一条会跑的糙检查比一条不存在的周密检查值钱。
5. **第一条集成：两个都要，按"答案是用来决定还是只用来记录"分。** 报价是调用（订单没折扣定不了价，
   晚到的消息参与不了已做完的决定），兑换是 commit 之后的事件（是后果，没人在等，绝不能让订单失败）。
   **一次预测失败值得记**：我预期一次性券会给两个订单都打折，实测第二单拿到 0——每个订单在自己的命令里
   重新报价，第一单的兑换已经落地。所以窗口不是"两个订单之间"而是**"一个订单的报价与兑换之间"**，
   要同时结账才成立；纠正后在边界上直接量了那个窗口（报价通过 → 别人用掉最后一次 → 兑换 REFUSED 且
   什么都没写）。三个处理方式（报价时占用 / 同事务兑换 / 接受并可见）各有代价，选哪个是业务问题，
   **不可谈判的是知道自己发的是哪个**。
6. **何时独立、什么必须先改**：边界本身不用改（四行换掉整个 coupons，ordering 毫无察觉）；
   **事务不能再跨过那次调用**（实测报价发生在写事务里——进程内免费，跨网络就是远程调用握着数据库事务，
   即 S28 量过的形状；改法是在开写事务前定价，只动一个类）；**要有"没答案"的策略而现在没有**
   （实测报价不可用 → 整个订单失败且一行未落库，对进程内是对的、跨网络完全错，而"不打折还是不下单"
   是业务决定）；**不能有环、不能有共享表**。
7. **环：库的规则允许，Maven 不允许。** 两个上下文互相依赖对方的 `api` 两边都走前门，库不报警，直到有人
   要把它们做成两个模块。所以异步那半放在 `contextmap`，实测把订阅者搬进 coupons（**通行且站得住的
   重构**）→ 环规则 1 红，**库的每条规则全绿**。
8. **domain 不许知道另一个上下文存在，连 api 也不许**——库允许它。理由与封装无关：**持有别人端口的聚合
   是会超时、会重试、会中途失败的聚合。** 实测给 `Order` 加 `repriceWith(CouponQuotes)`，本篇的规则 1 红，
   库的隔离规则绿。代价是明知的：`Order` 把券码存成 `String`，丢掉了 `CouponCode` 的校验。
9. **五个负向对照里有四个的红来自本篇自己加的规则，库的规则那四次全绿。** 这是本条最该带走的结论：
   **"只经 api 依赖"是必要的，远不是充分的。**

库的问题：[[issue-00170-a-published-value-object-cannot-satisfy-both-archunit-rules]]（P2，规则集内部冲突：
一个发布出去的值对象无法同时满足 `domainBuildingBlocksShouldResideInDomain` 与 `BoundedContextRules`，
于是 `CouponCode` / `Money` 只能不标 `@ValueObject` 并连带失去不可变性检查；库自己在事件上已解过同样的
问题，只差值对象这一对）。

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

**文档**：[[analysis-00042-samples-strangler-legacy-adoption]]（sample：`s25-strangler-legacy-adoption`，
一个单体 + 一个切出来的聚合，48 个用例）。**本条是本清单的最后一个场景。**

**与本条建议组件的一处刻意偏离**：本条建议 `-persistence-jdbc` / `-outbox-jdbc`，理由是"更贴近遗留 SQL"。
sample 的遗留侧**一个框架模块都不用**（纯 `JdbcTemplate` + 手写 SQL，比 JDBC 变体更贴近），新上下文按本系列约束
用 MyBatis-Plus。实测下来有意思的摩擦都在**写入路径与 schema** 上，与 SQL 方言无关。

1. **先切哪个：写者最少、规则最多那张表，两个数都能算。** 写者数是**成本**（每个写者都是一处要改道的调用点，
   四个写者的表不可能在一次改动里获得单一所有者），规则数是**收益**（没有规则的表变成聚合什么都不会得到）。
   实测从单体源码里数出来：orders 写者最多、refunds 最少且**单体表达出来的每条规则都在退款路径上**。
   推广：**从叶子往里绞**。两半各自都不充分——order_items 写者少但规则为零，切它是没有回报的整齐。
2. **没有版本列：三个过渡方案（加列/影子表/悲观锁）都不是答案，顺序才是。** 只要第二个写者还在，三个都保护不了
   任何东西。实测：新上下文读出一行 → 有人用单体界面改了它（版本列**没动**）→ 新上下文写入，
   **校验通过、遗留改动被静默覆盖、全程无异常**，事后从数据上看不出是并发缺陷。悲观锁同理，一句话：
   **每种并发控制都是一份约定，从未同意过它的写者不受约束。** 真正的答案是**先把第二个写者停掉**——
   版本列在遗留路径开始委派的那一刻才有意义。
3. **自增主键要拆成两个问题**：聚合的身份过渡期就用遗留 `bigint`（外键、遗留代码、十年报表都引用它，
   一边换身份一边切聚合是两次迁移套一顶帽子）；进契约的身份**永远不是它**，所以 `public_id` 在 V2 就存在、
   早于任何消费者。**一次预测失败**：以为库会拒绝没有主键的插入，**实测 INSERT 会通过**（守卫只在 update 路径），
   于是 id 由数据库分配、内存里的聚合 id 与行不一致、**事件已按错的 id 发出**，第二次写才报错——比被拒绝更糟。
   所以规则是"自己取号（用表自己的序列，两条路同源不会撞）"，因为**没有东西会拦你**。
4. **版本列默认值必须是 1**：库用 `version == 0` 表示"未持久化"，`DEFAULT 0` 会让**每一行历史数据**都拿到那个值，
   于是对任何旧行的第一次写入都是 INSERT 一个已存在的行。实测 2 红，抛 `DuplicateEntityException`，
   **而它列出的两个原因都不成立**。这一个字符值一段话。
5. **双写：不要。** outbox 能喂新上下文，**只对走库事务的写**；遗留路径根本用不了它（没有
   `IntegrationEvents`、不参与库事务、没有东西去写那行）。实测三条退款两条路径 → 表里 3 行、feed 里 2 条，
   而这个差**不是延迟**：等下去不会好，消费者无法区分"还没发布"与"永远不会发布"。所以是
   **一个写者、两个读者**，`route` 刻意没有 `BOTH`。顺带：单体的 `updated_at` 手写维护且有方法忘了写，
   实测行变了时间戳没变，**连轮询对账都没有可靠水位线**。
6. **ACL 的答案不是模式名，是一个数字**：整个服务只有一个类可以碰 `legacy`，由一条 ArchUnit 规则钉着。
   实测给 handler 加一句"先这样，它只要一个字段"→ 1 红 2 处违规。三处翻译（状态字符串 → 布尔、
   `EmptyResultDataAccessException` → `Optional`、遗留 record 不出现在返回类型里）里去掉异常翻译 → **1 红而所有
   ArchUnit 规则全绿**：规则管"谁能看见谁"，测试管"看见之后翻译了没有"。两个设计决定：**端口声明在新上下文里
   而不在 ACL 里**（反过来就是遗留词汇决定接口，那不是 ACL 是转发地址）；**委派入口点住在 acl 而不是 legacy 里**
   （装在单体里的 shim 是新代码往旧代码长回去的第一根线）。
7. **完成判据四条，从代码和 schema 算出来**：没有遗留方法写那张表（选第一个聚合的同一个计算，判据是归零）、
   没有东西到得了遗留方法、指向未切出表的**外键消失**（在此之前"已切出"只在一个部署内成立）、
   聚合不拥有的列有了归属。**sample 刻意没有完成**，并用数字说出来——只展示完成态会跳过要花十八个月的那部分。
   判据关于**代码**而非配置，因为配置值可以被没读过这些文件的人改掉。
8. **唯一一处刻意的行为变更被并排断言**：遗留的 approve 咽掉"已批准过"，新的会拒绝。
   **没被写下来的行为变更，六个月后和一次回归无法区分。** 同理 `ALREADY_OPEN` 是单体完全没有的规则，
   旧路径允许它 → 生产库里可能已有违反的行，所以 V2 **故意不加**部分唯一索引：
   **会拒绝现存行的约束，失败的是部署而不是请求。**
9. **把路由改回 `LEGACY_ONLY` → 12 红 / 48**：每条规则、每次拒绝、发布出去的那个事实，全部消失。
   这是整次抽取的价值写成一个数。

库的问题：[[issue-00171-the-write-path-against-a-schema-the-library-did-not-design]]（P2，第 3、4 点那两处：
行为都正确、异常信息都指向错的原因，而这恰好是遗留表必然踩的两处；建议在 insert 路径加同样的前置检查，
并在 `version == 0` 的语义旁补一句"为既有表加列时默认值必须是 1"）。

### S26 读侧加速：缓存与投影的取舍（P1）

**场景描述**："查询太慢"有两条解法：投影（S12）与缓存。库对缓存不提供任何东西，但团队一定会
接 Redis，且很容易接坏。本篇把两条路放在一起比较。

**要回答的关键问题**：什么慢该用投影、什么慢该用缓存；缓存挂在 `QueryBus` 拦截器还是仓储层；
**聚合本身能不能缓存**（不能——`version()` 的语义会被绕过，写入保护随之失效）；写后失效与事务
提交、事件发布的时序；缓存击穿/雪崩在这套结构里的落点；多租户下的 key 前缀（web store 已有
先例）；缓存不一致的可观测性。

**预计涉及的组件**：`aipersimmon-ddd-cqrs`（`QueryInterceptor`）、
`aipersimmon-ddd-web-store-redis`（租户前缀先例）；Redis 客户端为三方选型。

**文档**：[[analysis-00037-samples-read-side-caching]]（sample：`aipersimmon-ddd-samples/s26-read-side-caching`，
一个部署单元、58 个用例）。清单里的问题全部以实测作答，**其中一条问错了、一条我自己写错过**，
连同另外三点要回填进本清单：

1. **"聚合能不能缓存"的理由要改。** 本条原写"`version()` 的语义会被绕过，写入保护随之失效"——
   后半句**实测不成立**：乐观锁没有失效，从旧版本发起的写仍被拒绝，数据库始终一致。真正坏的是两件
   别的事：(a) 一个共享可变实例让**没保存的修改**泄漏给下一个读者（事务回滚了，对象没有）；
   (b) `version()` 变成关于缓存而非关于行的事实，于是**一次本该重试成功的冲突变成"成功但什么都没写"**
   ——重试重新加载聚合，但加载自那个 map，拿回已被改过的实例，`renameTo` 返回 false，handler 视为无事
   可做，命令正常返回。责任分别量过：去掉 memoisation → 三条全红；保留 memoisation 但关掉
   `RetryOnConflict` → 冲突照抛。**memoisation 让写入不可能，重试让它安静。**
2. **"多租户下的 key 前缀"这个提法不够。** 危险不是顺序而是**歧义**：拼接变长片段时只要有两个片段
   可能含分隔符，`join("acme","b:x")` 与 `join("acme:b","x")` 就是同一个串——**租户在前也拦不住**
   （我最初写的理由就是错的，已在文档第 6 节纠正）。修法是让最多一个片段自由：sku 必须自由，
   所以约束落在租户 id 上（库确实不约束它，`Tenants.of` 只拒保留前缀与超长）。租户仍放最前面，
   但理由换成**前缀是廉价指称"一组条目"的唯一方式**，决定了运维能按什么单位 flush。
3. **判据不看读频率，看写频率与"能被问什么"。** 一个每次写入都要失效的值没有缓存只有开销（所以本篇
   销售**不**触发失效，改名改价立即失效）；而**需要按任意条件查询的，缓存救不了——得先知道答案才能
   构造 key**。后者在代码里就是 `QueryCache` 与 `SalesBoard` 两个接口的差集：排序取 top N、从源头
   重建，缓存都给不了。
4. **"写后失效与提交时序"有个下半句本清单没问**：`AFTER_COMMIT` 是对的，但它**也没关严**——读方的
   写入落在 eviction 之后仍留下陈旧条目，而这个窗口**移动失效时机修不掉**（不存在晚于所有已开始的读
   的时刻）。所以 **TTL 不是调优项，是"缓存能错多久"的唯一上界**。
5. **"缓存不一致的可观测性"的要点是方向**：缓存悄悄停止被失效时，命中率**上升**、延迟**改善**——
   仪表盘上它看起来像终于开始起作用。所以必须有一个刻意的比对（读条目、算真值、比对）并给它挂告警，
   命中率单独看什么都决定不了。

另发现库的两个问题：[[issue-00166-the-event-listener-rules-do-not-see-transactionaleventlistener]]（P2，
三条 ArchUnit 规则看不见 `@TransactionalEventListener`，一进一出天然对照量出）与
[[issue-00167-the-querybus-javadoc-denies-the-interceptor-chain-it-has]]（P3，端口 javadoc 否认自己有
拦截器链）。缓存本身无从指摘库——库在这条路上没有代码。

### S27 软删除、数据保留与擦除（P1）

**场景描述**：删除在 DDD 里几乎从不是"删掉一行"。它可能是领域状态、可能是基础设施开关、也
可能是一次合规擦除；三者的处理方式完全不同。

**要回答的关键问题**：软删除是领域状态（"已作废"，进状态机）还是基础设施开关（逻辑删除标记）
——判据是什么；逻辑删除与唯一索引的冲突怎么解；整根覆盖写入遇上逻辑删除列的相互作用；一次
合规擦除对**已发出的事件、inbox 幂等键、审计行**分别意味着什么（这条最难，且最容易被无视）；
保留期与法务/审计要求如何对齐；被擦除实体的历史事件重放会怎样。

**预计涉及的组件**：`aipersimmon-ddd-persistence-mybatis-plus`、
`aipersimmon-ddd-operation-log-mybatis-plus`、`aipersimmon-ddd-inbox-mybatis-plus`。

**文档**：[[analysis-00039-samples-soft-delete-and-erasure]]（sample：`aipersimmon-ddd-samples/s27-soft-delete-and-erasure`，
一个部署单元、42 个用例）。六问全部以实测作答，五点要回填进本清单：

1. **判据不是技术性的**：有没有业务规则读它？有没有人会撤销它？有没有人会要一份清单？三个都否 → 基础设施
   开关；任一为是 → 领域状态。而**判断错了有机械后果**：把标记当普通列自己维护，`ClearedColumns` 会把它
   连同其他未映射列一起强制写 null。实测把 `@TableLogic` 从行类上摘掉，**42 个用例里 22 个红，全部红在
   `null value in column "deleted"`——不是"隐藏的行行为异常",是一次写都不成**。这是好结果；危险的是
   `NOT NULL` 缺席时它会静默提交成"既非 true 也非 false"，两种过滤器都看不见（推论，未实测）。
2. **"整根覆盖写入遇上逻辑删除列"库已经处理过，但那一行从没被跑过**：`ClearedColumns.isEmittedAnyway` 里
   `tableInfo.isWithLogicDelete() && field.isLogicDelete()` 是全仓**唯一**提到 `@TableLogic` 的地方。本篇
   跑了它，并同时断言排除是**窄的**（聚合真正清空的 phone 照旧被强制写 null）。
3. **唯一索引的冲突有第二半**：部分索引（`WHERE deleted = FALSE`）修好"被隐藏的人永久占住邮箱"，
   而**擦除的墓碑也占着这个索引**，所以墓碑必须按 id 唯一——常量墓碑让第二次擦除撞唯一键（实测 2 红，
   含 `duplicate key ... uq_s27_customer_email_live`）。MySQL 无部分索引，替代做法里**不能用 NULL 表示
   活着**，否则两个活行能同邮箱（与意图相反，且只删一行的测试全绿）。
4. **"合规擦除对已发事件/inbox/审计行分别意味着什么"——三个答案完全不同，这是本篇的核心**：
   - **outbox**：事后没有正确动作（照发＝造新副本；删掉＝下游永久错误；改写＝契约撒谎），所以
     **顺序必须事先安排**：排空后才擦除，擦除在队列非空时**拒绝**并给可重试的 409。这是本系列唯一读
     outbox 的业务命令。洞：进了死信表的公告不再阻塞擦除，但仍握着那份数据。
   - **inbox**：**什么都不做**。表里没有任何一列指向人（实测查 `information_schema`），键是消息 id 不是
     主体，所以既无法定向也无需定向；而"按时间窗口顺手清一下"会**打断 exactly-once**（实测：同一条消息
     被处理第二次）。留着键还让擦除对迟到的重投免疫。
   - **审计行**：**留，且它的内容在写下的那刻就定了**——组件没有 update 端口也没有按 id 删除，`Redactor`
     只做去控制字符与截断。所以正确做法是设计时就别写进去：擦除自己的行只提 id 与工单号，改邮箱的行用
     `mask()`。通行写法"从 A 改到 B" 会把旧邮箱永久留在一张多年保留的 append-only 表里。
5. **"擦除"这个词本身要改**：它不是删除。行留着（**它的存在是证据**），个人数据被覆写成模型仍接受的墓碑
   值。这也是能扛住合规审查的**代理键论证**：拿邮箱做主键的话擦除就等于删行，连带删掉每条审计与账目对它
   的引用，义务与"证明履行了义务"直接冲突。

库的问题：[[issue-00168-the-audit-classifier-records-every-application-refusal-as-unexpected]]（P2，
`DefaultFailureClassifier` 没有 `ApplicationException` 分支，于是每一次 404/409 在审计表里都是
`FAILED`/`unexpected` 且自带的 `ErrorCode` 被丢掉；三行可修）。

### S28 长耗时与大数据量端点（P1）

**场景描述**：文件上传、导出百万行、耗时数分钟的作业。库只给了分页，团队会误把
process-manager 当作业队列用。

**要回答的关键问题**：同步接口的时间上限在哪，超过之后 `202 + 作业资源 + 进度查询` 的契约怎么
定；作业状态该不该是一个聚合；**为什么不该用 process-manager 当作业队列**，以及正面的替代
形态是什么（S9 只问了上限，没给替代）；大导出如何流式输出而不撑爆内存与事务；上传的分片、
断点与幂等（与 S2 呼应）；作业失败的可见性与重试。

**预计涉及的组件**：`aipersimmon-ddd-cqrs`、`aipersimmon-ddd-web-spring-boot-starter`、
`aipersimmon-ddd-core`（作业聚合）；对照 `aipersimmon-ddd-process-manager`。

**文档**：[[analysis-00040-samples-long-running-endpoints]]（sample：`s28-long-running-endpoints`，
77 个用例）。

1. **同步接口的上限不在任何超时上，在连接池上。** 通常被引用的是负载均衡器的 60 秒或客户端读超时，
   而那些是最后才咬到的。实测（池子调成 2、获取超时 1 秒）：两个并发的流式导出各持一条连接，一个
   **与导出毫无关系**的 `SELECT 1` 就拿不到连接而失败。算式是**并发 × 时长对上池子大小**，十对十、
   五十对五十同理——那个四分钟的端点不需要受欢迎，只需要和池子一样宽。失败的形状也值得记：坏掉的端点
   永远不是有问题的那个。另外实测**库对 command / query / handler 不设任何时间上限**。
2. **本条原写"库只给了分页"，实测更要紧的是分页之外那两个静默条件。** 一个"看起来在流式"的读要真的
   流式，需要 `ResultHandler`、`fetchSize`、**以及调用点的一个事务**；PostgreSQL 驱动在 autocommit 下
   完全忽略 `fetchSize`，把每行读完再交出第一行，而**什么都不会失败**。确定性量法：让最后一行必然报错，
   数错误到达前收到几行——事务+fetchSize=500 收到 **4500**（整批交付，一个整数同时证明"在流式"与
   "批大小被采纳"），autocommit 收到 **0**，无 fetchSize 收到 **0**。
3. **作业状态该不该是聚合：生命周期该，进度不该。** 生命周期上每一条都是有人会被拒绝的事；进度什么都
   不决定却一场跑变几千次。实测 1000 次 tick **聚合 version 一动不动**；反过来，**一次**顶替"经过聚合的
   tick"的 version 自增就足以让一次已在途的取消被乐观锁拒绝。判据可推广：**问的是"有没有规则读它"，
   不是"它属于谁"**——进度、分片收据都属于这个作业，都不进它的一致性边界。还有一层：**进度的事务比它的表
   更要紧**，写在导出自己事务里的 tick 对所有其他人在提交前都不可见，而那正是进度不再有趣的时刻。
4. **为什么不该用 process-manager 当作业队列**，四条对着真引擎量的：载荷上限 **1 MiB**（一个月对账行不是
   1 MiB，实测 `ProcessPayloadTooLargeException`）；**每次进度 tick = 一行持久 transition + 一次 revision
   自增**（20 次 → 21 行 / revision 21，对照本篇设计的 1 行原地覆盖 + 完全不碰作业）；**进度与取消是同一条
   revision 车道**，20 次 tick 之后的取消是 **revision 22 而不是 2**；**除了喂输入没有别的办法让它停**
   （`ProcessRuntime` 只有 `start` 与 `handle`）。加上 `ProcessDefinition` 不做 I/O，所以真正的导出根本
   不可能在定义里。**正面替代**：认领 + 租约（照库自己中继的形状）、生命周期进聚合、计数器留在聚合外、
   产物按引用存、`202` + 可轮询的作业资源。一句话分野：**流程管理器协调别人的活；作业本身就是那件活。**
5. **认领是全篇唯一故意不走聚合的写入**，因为乐观锁是为"本不该有人进入的竞争"造的，而 N 个 worker 本来
   就该抢。代价：手写 SQL 与版本化写入共存的**前提**是那条 SQL 必须自己 `version = version + 1`——否则
   一次在认领前读到作业的取消照样提交，作业变 CANCELLED 而 worker 正把它跑完，**而且不抛任何异常**。
   这条前提库里一处都没写过 → [[issue-00169-nothing-warns-that-a-hand-written-write-must-advance-the-version]]
   （P2，文档，三句话）。另外：停住的 worker 要靠**显式 owner 栅栏**拦而不能靠乐观锁（版本冲突会被重试，
   而重试会覆盖新主人的成果）；而心跳**不能**动 version，否则 worker 会把自己栅在外面。
6. **取消是一个请求，不是一个状态。** 从外面强加会产出"产物在磁盘上而状态是 CANCELLED"的作业。推论是
   `succeeded` 在取消已被请求之后仍然允许，本篇把这句写出来而不藏着。也因此没有 `CANCELLING` 状态。
7. **`202` 的契约**：`PUT` + **客户端提供的 id**，于是提交天然幂等且不需要幂等键存储——顺手绕开一个错配，
   幂等结果的保留窗口比作业寿命短时，重试会拿到**第二个作业**。同 id 换 period 是拒绝（幂等存储的
   `Mismatch`，由资源自己表达）。两次都是 202 且都带 `Location`；**202 上不放 `Retry-After`**，诚实的节奏
   提示是客户端马上要轮询的进度读数。下载读文件不读表，否则客户端网速决定事务开多久。
8. **可续传上传只有一个想法：每个请求都能重放**，加上服务端回答"你还缺什么"。分片收据与进度同构地留在
   聚合外（实测 20 片之后批次 version 未变），批次在完成时**被告知计数**。校验和与主键给的是两种不同的
   保证：主键说"已经有第 7 片"，校验和说"而且是你想要的那个第 7 片"。

## 3. 待验证的技术前提

这两条不是文档选择，是会决定 sample 能否写成的事实，需在对应场景动工前先验证。

### 3.1 Seata AT 与本库拦截器能否共存（曾阻塞 S10；**2026-08-04 已验证通过**）

原始疑问：Seata AT 通过解析它拦截到的 SQL 生成 before/after 镜像来构造 `undo_log`；而本库的聚合写入
已经被乐观锁的版本条件与租户改写过。两层 SQL 改写叠加是否仍能正确回滚。

**结论：能，库一行都不用改。** 先用一个独立探针跑最小验证（一个带 version 列、带 tenant 列、复合主键的
聚合在 AT 模式下被全局回滚），再把结论落成 S10 里的常驻测试。判据不是"测试绿了"，而是直接读
`undo_log.rollback_info`：前后镜像把 `version` 当普通列捕获（前 1 后 2 → 回滚会还原它；若留在 2，
数据是对的而任何持 version=1 快照的写入者永久失败）、`tenant_id` 与 `id` 都标 `PRIMARY_KEY`（Seata 从表
元数据取复合键，不从被改写的谓词里猜）、框架 cleared-column 强制的 `SET ... = NULL` 也进了后镜像。

**为什么能共存**：拦截器在 DataSource **之上**改写 SQL，而 Seata 的代理**就是** DataSource——等 Seata
解析时改写早已完成，它看到的是普通最终 SQL。

所以 S10 主线用 AT；TCC 仍然写进 sample，但作为**可度量的对照**（AT 锁住行整个业务事务 vs TCC 在 Try
提交后放开）而不是退路。详见 [[analysis-00033-samples-strong-consistency-seata]] §1。

顺带确定的一条部署约束：**`undo_log` 不能由应用迁移创建**——Seata 在 `DataSourceProxy.init` 里无条件检查
它，而那发生在 DataSource bean 构造期间，早于 Flyway / `spring.sql.init` / 本库的 flyway components。

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
