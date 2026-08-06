---
id: analysis-00028-samples-one-trace-across-the-boundary
type: analysis
status: draft
parent: analysis-00014-ddd-samples-scenario-catalog
---

# S15 可观测性：跨边界追一次请求（寄宿 S4）

对应 sample：`aipersimmon-ddd-samples/s04-integration-events-across-services`（与
[[analysis-00025-samples-integration-events-across-services]]、
[[analysis-00027-samples-multi-tenancy-end-to-end]] 同一份代码）。场景清单见
[[analysis-00014-ddd-samples-scenario-catalog]]。

## 0. 本篇定位与一处标题修正

一次业务请求穿过 HTTP → 命令 → 聚合 → outbox → Kafka → inbox → 下游命令，排障时要能追完整条链。

场景清单把这件事写成"**一条完整 trace**"。落地后必须修正这条前提：**它不是一条 trace，而且不应该是。**
outbox 那一跳是故意的时间断点，库在那里开的是一个**link 回去的新 trace**，不是同一个 trace 的延续——库自己的
`ConnectedTraceEndToEndTest` 断言的正是 `assertNotEquals(command.traceId, publish.traceId)`。

真正端到端**逐字节相同**的那个标识符是 **correlationId**，而且它不需要任何后端。所以本篇有两个答案，不是一个：

| | 跨 HTTP | 跨 outbox（存储再转发） | 跨 broker |
| --- | --- | --- | --- |
| trace | 同一条（父子） | **新 trace + link** | 同一条（消费方续上记录里的 traceparent） |
| correlationId | 同一个值 | 同一个值 | 同一个值 |

## 1. 为什么 outbox 那一跳只能是 link

环境（thread-local）trace 上下文和自动埋点都跨不过"一个事务里写行、另一个线程另一个事务里发"这道缝：原始
上下文早就没了，而且没有任何埋点认识你自己那张表。库为此专门开了一个 SPI（`StoreAndForwardTracer`），两侧
各一半：

- `captureCurrent()` 在**写入线程、写入事务内**把当前上下文序列化成 `traceparent`/`trace_state` 存到行上；
- `restore(...)` 在**投递线程**重建上下文，开一个**link 回创建 span** 的 span。

为什么是 link 而不是 child：一行可能等几秒也可能等几小时，一次 poll 会一次带走一批。做成 child 的话，那条
trace 的时长就变成"这行等了多久"，而一个批次会让一条 trace 长出无关的枝。link 是这段关系的正确形状。

sample 从**存储与线上两侧**验了同一件事（`TraceAndCorrelationTest`）：

- `theDurableRowCarriesTheTraceContextOfTheRequestThatWroteIt`：行上的 `traceparent` 里含着**处理器内活跃
  span 的 trace id**（探针在拦截器最内层读的 `Span.current()`）；
- `theWireLeavesUnderItsOwnTraceLinkedBackRatherThanContinuingTheRequests`：relay 跑完之后，Kafka 记录上的
  `traceparent` 里的 trace id **不等于**请求的那个，而行上那个仍然等于。

**运维上要记住的一句**：下游服务报出来的 trace id **不是**发起那次 HTTP 请求的 trace id。跨 broker 追一次请求
要走一条 link——后端会替你走，`grep <traceId>` 不会。

这两列在没装 tracer 时是**空的**：capture 走 SPI，默认实现什么都不捕获。**列存在 ≠ 功能开着**，装上
`aipersimmon-ddd-observability-otel-spring-boot-starter` 才填。

## 2. 跨 broker 反而是免费的

消费侧 `InboundTraceTest.theWorkThisServiceDoesJoinsTheTraceTheRecordArrivedOn`：测试自己造一个 traceparent
放进记录头，断言下游**命令处理器内**活跃的 trace id 就是它。

sample 自己的代码一行都没读那个 header——那是传输层埋点的事。这就是两跳的差别：**broker 有标准载体和现成埋点，
outbox 是你自己的表，只能靠那个 SPI。**

（测试自己造 trace id 而不是启动发布方，是为了让断言是"消费方用了到达的那个"，而不是"消费方自己造了一个恰好
存在的"。一个在边界被重新铸造的 id 不叫 correlation id，叫本地 id。）

## 3. 不接 OTel 时靠什么排障：四个 id，四个模块

catalogue 问的"最小关联 id 集合"，答案是这四个 MDC 键，**全部由库写入**，各来自不同模块：

| MDC 键 | 谁写 | 覆盖范围 |
| --- | --- | --- |
| `requestId` | web starter 的 `RequestIdFilter`（也是响应头，调用方能引用） | HTTP 请求 |
| `tenant` | 租户解析过滤器 | HTTP 请求 |
| `trace_id` | observability starter 的 `TraceIdMdcFilter` | HTTP 请求 |
| `correlationId` | cqrs 的 `LoggingCommandInterceptor` | 一次命令处理的全程 |

`theLogLineCarriesFourIdsFromFourDifferentModules` 在发布方把四个都断言了一遍。

### 3.1 两个必须知道的缺口

**缺口一：调用方手里的 id 与消息层的 correlationId 是两个 id，数据上没有任何桥。** 根命令的
`correlationId` 等于它自己的 `messageId`（总线铸造），跟边缘的 `X-Request-Id` 毫无关系——sample 直接断言了
`mdcRequestId != mdcCorrelationId`。

所以一张引用了 `X-Request-Id` 的工单，没法直接变成一次 correlationId 检索。两个出路：走 trace（observability
starter 会把 `request.id` 作为属性盖在 server span 上，正是为这个），或者自己写一层桥，让命令上下文从边缘 id
播种。

**缺口二：消费侧四个键里有三个不存在。** 三个都由 **servlet 过滤器**写，而消费者没有请求。
`aconsumersLogLineCarriesOneOfTheFourIdsAndNotThreeOfThem` 钉住了这条：消费方的日志行只有 `correlationId`，
**而租户确实绑上了、trace 确实续上了**——所以这是**日志缺口，不是传播故障**。

在操作员到消费方日志里 grep 租户或 trace id 之前知道这件事，比事后怀疑传播坏了要省很多时间。补的代价：入站
适配器里一行 `MDC.put`，或者用一个从活跃上下文盖 `trace_id`/`span_id` 的 OTLP 日志 appender（observability
starter 装了一个）。选哪个取决于这些日志是在文件里读还是在后端里读。

## 4. correlationId 与 causationId：两个 id 干两件事

`theCorrelationIdIsTheOneIdentifierThatIsIdenticalEndToEnd` 与消费侧的
`thecorrelationIdCrossesTheBrokerUnchangedAndTheCausationChainAdvances` 一起钉住：

| | 语义 | 跨跳行为 |
| --- | --- | --- |
| `correlationId` | 这是哪一条流 | **不变**：命令 → outbox 行 → `ce_correlationid` → 下游命令，同一个值 |
| `causationId` | 直接原因是谁 | **前进一格**：事件的 cause 是发它的命令，下游命令的 cause 是那个事件 |

根命令没有 cause（`causationId == null`），它的 correlationId 就是自己的 messageId——所以从第一跳起就有流的
身份，不需要谁额外分配。

追因果链是逐跳走 causation；一次拿到整条流是按 correlation 检索。两者都不进 payload。

## 5. 装配与配置

发布方与消费方各加一条依赖（`aipersimmon-ddd-observability-otel-spring-boot-starter`，它顺带带来 OTel 自己的
Spring Boot starter：HTTP/JDBC/Kafka 埋点与 `OpenTelemetry` bean）。

sample 的 yaml 里把三个 exporter 设成 `none`，理由写在旁边：示例没有 collector 可连，而一个对着空气重试的 OTLP
exporter 会污染每份测试日志。**span 照样创建、照样采样**，store-and-forward 的 capture 要的就是这个。真实部署
改成 `traces.exporter: otlp` 加 endpoint。

这也顺带说明本篇能这么便宜地测出来的原因：**要验的是"上下文被搬到了正确的载体上"，那是行里的一列和记录上的一个
header，不需要后端。**

## 6. 常见错法

| 错法 | 后果 |
| --- | --- |
| 期待一条 trace 端到端 | outbox 那跳是 link 不是延续；下游的 trace id 不是请求的 trace id |
| 把 outbox 的投递做成 child span | trace 时长变成"行等了多久"，一批投递会让一条 trace 长出无关枝 |
| 以为 `traceparent` 列存在就有值 | 没装 tracer 时永远是 NULL（capture 走 SPI，默认什么都不捕获） |
| 用 `X-Request-Id` 去搜 correlationId | 两个 id，数据上没有桥；要么走 trace 的 `request.id` 属性，要么自己搭桥 |
| 在消费方 grep `tenant` / `trace_id` | 那两个 MDC 键由 HTTP 过滤器写，消费侧没有——但传播是好的 |
| 在边界重新铸造 correlationId | 那不是 correlation id，是换了名字的本地 id |
| 把 trace id 塞进事件 payload | 载体是 `traceparent`（out of band）；payload 里的 trace id 会跟着契约一起演进 |
| 只装 exporter 不管采样 | 采不到的请求排不了障；但本篇的两跳断言与采样无关（上下文照样搬） |

## 7. 本篇不覆盖

- 指标（metric）与告警面：relay 积压、消费延迟、stuck 实例阈值——S22；
- 真实后端里的展示与关联（SigNoz/OTLP 落库、日志↔trace 关联的观感）——需要 collector，示例不带；
- process-manager / 定时任务的 span 形状（库为此留了 `Tracer` SPI，本篇只用到命令那一个）；
- 采样策略与成本；
- java agent 方式的埋点（本篇用的是 OTel Spring Boot starter）。
