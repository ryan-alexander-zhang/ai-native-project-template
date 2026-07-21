---
id: record-00001-multi-module-ddd-integration-verification
type: record
role: main
status: active
parent: plan-00007
---

# 多模块脚手架 DDD 组件集成验证报告

对 `aipersimmon-ddd-scaffold/multi-module` 样例进行启动 + 端到端测试，验证集成的 aipersimmon-ddd
组件（Web、可观测性、事件、CQRS、Flyway、流程管理器、ArchUnit）是否生效且按预期工作。

- **日期**: 2026-07-21
- **样例**: `aipersimmon-ddd-scaffold/multi-module`（ordering / inventory / payment 三个限界上下文 + `start` 可部署单元）
- **被测库**: `com.aipersimmon.ddd` `0.1.0-SNAPSHOT`（本地 `~/.m2`）
- **提交**: `9a69ec4`（分支 `lang/java/ddd`）
- **验证方式**: (A) 全反应堆构建；(B) Testcontainers 测试套件；(C) docker-compose 真实 Postgres+Kafka 启动 + HTTP/DB/Kafka 实测

## 环境

| 项 | 值 |
| --- | --- |
| JDK | 21.0.7（构建/运行统一锁定到 JDK 21；系统 `mvn` 默认走 JDK 26，故显式 `JAVA_HOME`） |
| Maven | 3.9.16 |
| Docker | 29.4.2 |
| Postgres | `postgres:18.1`（compose）/ Testcontainers |
| Kafka | compose `bitnamilegacy/kafka:3.7.1`；Testcontainers `apache/kafka:3.7.1` |

## 结论速览

| 组件 | 结论 | 依据 |
| --- | --- | --- |
| 构建（20 模块反应堆） | ✅ 通过 | `mvn clean install -DskipTests` → BUILD SUCCESS |
| 测试套件（52 用例） | ✅ 全绿 | `mvn test` → 0 failures / 0 errors |
| Web（REST + RFC 9457） | ✅ 生效 | 201/200/404、400 字段校验、422 领域码，实测 HTTP |
| CQRS（CommandBus/QueryBus） | ✅ 生效 | 下单/确认/取消/查询全走总线 |
| Flyway（多组件独立历史表） | ✅ 生效 | 4 条历史表、按上下文分 schema |
| 流程管理器（durable saga） | ✅ 生效（亮点） | 持久化实例 COMPLETED，正确/补偿两条路径 |
| ArchUnit（分层规则） | ✅ 生效 | `AiPersimmonDddRules.all()` + 仓储 Spring 规则通过 |
| 可观测性（OTel → SigNoz） | ✅ 已在 SigNoz 验证 | service `ordering` + 领域主干 span(command/process.advance/outbox.publish)+ HTTP/JDBC/Kafka 自动埋点,均在 SigNoz ClickHouse 查得;修复 collector 配置见 F-3 |
| **事件 / 出站箱 / 入站箱 / Kafka** | **✅ 已修复** | 曾运行期旁路(F-1 / issue-00044);已修:outbox writer 确定性胜出 + fail-loud 守卫 + 传输日志,实测走通 outbox→Kafka→inbox |
| **Web 未知路由 / actuator 健康探针** | **✅ 已修复** | 曾全部 500（F-2 / issue-00045）；已补齐 404/405/415/406/400 映射 + actuator，回归测试通过 |

> 结论：全部 7 项集成的 DDD 组件能力均已验证生效。过程中发现并修复**三个真实缺陷**:
> - **F-1（issue-00044，已修复）**:可靠集成事件传输（出站箱→Kafka→入站箱，decision-00006 / design-00006）在运行期被进程内发布器抢占而整体旁路。已按方案 3 全量修复(outbox writer 以 `@AutoConfigure beforeName` 确定性胜出、`DurableIntegrationEvents` 标记 + messaging-kafka 启动 fail-loud 守卫、传输可见日志、样例回归测试)。实测下单后 outbox/inbox 各 4 行、Kafka 三主题偏移量 2/1/1(修复前全 0)。
> - **F-2（issue-00045，已修复）**：Web 全局异常处理把未知路由/错误方法兜底成 500（应为 404/405），且无 actuator starter,导致健康探针不可用。已按方案 A 修复(补齐 404/405/415/406/400 映射 + 引入 actuator + 三条回归断言),`status: resolved`。
> - **F-3（本记录,已修复）**:样例 SigNoz `otel-collector-config.yaml` 的 `service.telemetry.metrics.address` 键与固定镜像 v0.144.2 不兼容,collector 启动即 fatal,可观测性栈起不来。改用 `readers` 结构修复。
>
> **可观测性已实跑接入 SigNoz 并在 SigNoz 中验证**:应用经 OTLP 导出到 signoz-otel-collector,SigNoz 遥测存储中查得 service `ordering` 及全部领域主干 + 边界 span。

---

## A. 全反应堆构建

```
mvn clean install -DskipTests -T1C   （JAVA_HOME=JDK 21）
→ EXIT 0；20 个模块（ordering/inventory/payment 各层 + start）全部安装到本地仓库
```

## B. 测试套件（Testcontainers）

`mvn test`（多模块反应堆）→ **BUILD SUCCESS**，合计 **52 用例，0 失败 0 错误**。
`start` 模块用例通过 Testcontainers 拉起真实 Postgres + Kafka（`TestInfrastructure` 的 `@ServiceConnection`）。

| 测试类 | 用例 | 覆盖能力 |
| --- | --- | --- |
| `ComplexOrderStateChangeDemoTest` | 10 | 领域聚合状态机、不变式 |
| `PlaceOrderBusValidationTest` | 2 | 命令总线入参校验 |
| `OrderControllerValidationTest` | 2 | Web 层 `@Valid` 切片 |
| `OrderFulfilmentDefinitionTest` | 14 | 流程定义纯函数 + 乱序幂等 |
| `ReserveStockBusValidationTest` | 2 | inventory 命令校验 |
| `ChargePaymentIdempotencyTest` | 3 | payment 幂等 |
| `ApplicationSmokeTest` | 1 | 全上下文 Spring 装配 |
| `ArchitectureTest` | 5 | ArchUnit 分层规则 |
| `ExceptionContractTest` | 6 | RFC 9457 异常契约（HTTP） |
| `OrderingFlowTest` | 3 | 跨上下文正向/失败流程 |
| `OutboxAtomicityTest` | 1 | 聚合与出站箱同事务回滚 |
| `PaymentCompensationFlowTest` | 2 | 支付拒付补偿全链路 |
| `PackageInfoTest` | 1 | 每个包存在 `package-info` |

## C. 真实启动 + 实测（docker-compose Postgres + Kafka）

为规避 8080 端口冲突（`kafka-ui` 默认宿主端口被本机 nginx 占用），仅手动启动 `db` + `kafka`，
关闭 Boot 的 compose 生命周期托管（`--spring.docker.compose.enabled=false`），显式注入数据源与
`bootstrap-servers`，应用监听 8090。

### 组件逐项结论

**1) Flyway ✅** — 冷启动 1.73s 内完成四条相互独立的迁移流，各用独立历史表：

| 组件 | 历史表 | 迁移数 |
| --- | --- | --- |
| 消费方业务表（V1 aggregates） | `flyway_schema_history` | 1 |
| aipersimmon inbox | `flyway_schema_history_aipersimmon_inbox` | 1 |
| aipersimmon outbox | `flyway_schema_history_aipersimmon_outbox` | 2（含 `drop trace id`） |
| aipersimmon process-manager | `flyway_schema_history_aipersimmon_process_manager` | 2 |

业务表按上下文分 schema（`ordering.*` / `inventory.*`），与出站箱/入站箱/流程表在同库，保证
"聚合写 + 出站箱写" 单事务原子（见 `OutboxAtomicityTest`）。

> ⚠️ 运维注意：compose 使用**命名卷** `ordering_db_data`。若上一轮样例的 `V1__aggregates.sql`
> 与当前不一致，重启会因 `flyway_schema_history` 校验和不匹配而启动失败
> （实测：`Applied -167232614 vs Resolved -63302243`）。清理方式：`docker compose down -v` 后重启。
> 这是本机残留卷问题，非代码缺陷（Testcontainers 每次全新卷，故测试套件不受影响）。

**2) Web + CQRS + 异常契约 ✅** — 通过真实 HTTP 实测：

| 场景 | 请求 | 结果 |
| --- | --- | --- |
| 下单 | `POST /orders`（CUST-1, SKU-1×2） | `201`，`Location: /orders/{id}` |
| 查询 | `GET /orders/{id}` | `200` 快照（走 QueryBus / FindOrder） |
| 空明细校验 | `POST /orders`（`lines:[]`） | `400`，RFC 9457 含 `errors:[{field:lines,code:NotEmpty}]` |
| 未知 SKU | `POST /orders`（SKU-404） | `422`，`type:/problems/domain-rule-violation`，`code:ordering.stock-unavailable` |
| 缺失订单 | `GET /orders/does-not-exist` | `404` |

命令走 `CommandBus`、查询走 `QueryBus`，Web 适配器不含编排逻辑 —— CQRS 装配生效。

**3) 流程管理器（durable saga）✅ —— 本次亮点** — 通过真实 Kafka/Postgres 环境实测两条路径，
均由持久化流程实例（`aipersimmon_process_instance`）驱动至终态：

| 路径 | 触发 | 订单终态 | 流程实例 |
| --- | --- | --- | --- |
| 正向 | SKU-1×2（充足） | `CONFIRMED` | `COMPLETED` / step `CONFIRMED` / outcome `ORDER_CONFIRMED` / revision 4 |
| 补偿-库存不足 | SKU-1×999 | `FULFILMENT_IN_PROGRESS`→`CANCELLED` | `COMPLETED` / step `CANCELLED` / outcome `ORDER_CANCELLED` / revision 3 |
| 补偿-支付拒付 | 6×10000（超支付上限） | `CANCELLED`（先释放库存再取消） | `COMPLETED` / `ORDER_CANCELLED`（`PaymentCompensationFlowTest` 断言） |

`revision` 计数印证多步状态迁移逐笔持久化；补偿路径遵守"先释放库存、后取消订单"的顺序性，
证据 id 取自触发信封的 `messageId`（见 `OrderFulfilmentDefinition`）。

**4) ArchUnit ✅** — `ArchitectureTest`（5 条 `@ArchTest`）对 `com.example.ordering` +
`com.example.inventory` 编译类生效：`AiPersimmonDddRules.all()` 覆盖分层（domain 不依赖外层、
application 不依赖 infra/interface、domain 无框架依赖）、事件（领域事件驻留、集成事件监听驻留 adapter、
`@EventType` 声明）、CQRS（handler 不互相依赖、不自调 send）、构建块（聚合根/值对象/领域服务）、
仓储端口与实现、不变式/状态机/错误码等；opt-in 的
`RepositoryRules.implementationsShouldBeSpringRepositories` 也通过。`PackageInfoTest` 校验每个包存在
`package-info.java`。

**5) 可观测性 ✅（已接入 SigNoz 实测并在 SigNoz 中验证）** — 起 `observability` profile
(clickhouse + zookeeper + signoz + signoz-otel-collector),应用启用 OTel(去掉 `--otel.sdk.disabled`)
经 OTLP/gRPC 导出到 collector(:44317),下单跑正向 + 补偿 + 422。**在 SigNoz 的遥测存储(ClickHouse
`signoz_traces`)中验证到** service `ordering`、30 分钟内 1360 条 span,且三类 aipersimmon 领域主干 span
齐全:`command PlaceOrder/ReserveStock/RequestPayment/ConfirmOrder/ChargePayment/CancelOrder`、
`process.advance ordering.fulfilment`(15)、`outbox.publish <eventId>`(多条);边界自动埋点也在:
HTTP `POST /orders`、JDBC(`INSERT/SELECT/UPDATE ordering.aipersimmon_outbox/inbox/process_*`、
`SELECT inventory.stocks`)、Kafka `ordering.events publish`/`ordering.events process`(各 7,旁证 F-1)。
单条 trace 的 span 树 `POST /orders(SERVER)→command PlaceOrder(INTERNAL)→SELECT customers/stocks(CLIENT)`
与 SigNoz UI 呈现一致。启动前发现并修复了 collector 配置不兼容(见 **F-3**)。

**边缘 request id 可检索(issue-00046 补齐)** — 起初 `X-Request-Id` 只在响应头/体与 MDC,没上报到 trace,
SigNoz 里按它搜不到。已在 observability-otel starter 的 `TraceIdMdcFilter` 把边缘 requestId 以 span 属性
`request.id` 打到 server span(读已解析的 MDC 值,客户端传入与服务端生成两种情况都覆盖)。实测:按
`request.id`(客户端传入 `rid-verify-38d801ac…` / 服务端生成 `4fe92345…`)在 SigNoz 均定位到对应 trace
(`2ddb0e38…` / `b7c5af77…`)—— 即"按 X-Request-Id 搜 → 拿 trace → 全局钻取"的标准闭环成立。

**跨 outbox → Kafka → inbox 的 trace 链路端到端连通(实测)** — 对单笔订单在 SigNoz 重建拓扑,确认整条业务流程
可导航连通(非单一 trace-id,而是"段内父子 + 段间 span-link",此为 OTel 对异步/store-and-forward 的标准建模):

```
T1[同步]  POST /orders→command PlaceOrder→JDBC→process.advance(进程内首跳)→INSERT outbox
   └─FOLLOWS_FROM─▶ 段1  outbox.publish(OrderPlaced)→ordering.events publish(生产者)
                          └─parent─▶ ordering.events process(消费者,同 trace 父子)→inbox 去重→command ReserveStock→INSERT outbox
   └─FOLLOWS_FROM─▶ 段2  publish(StockReserved)→…→command RequestPayment/ChargePayment→INSERT outbox
   └─FOLLOWS_FROM─▶ 段3/4 …→command ConfirmOrder→OrderConfirmed(终态)
```

三处连接点均实测成立:①进程内 `process.advance` 与 HTTP 同一条 trace;②Kafka **生产者→消费者同 trace 父子**
(`process` 的 parentSpanID = `publish` 的 spanID);③跨 store-and-forward 边界由 `outbox.publish` 的
`FOLLOWS_FROM` span-link 逐段链回源头(T1→段1→段2→…)。

**日志作为第三支柱进 SigNoz 并与 trace 关联(实测)** — 参考大厂实践(ECS 结构化日志 + OTel logback appender)接入:
- **控制台 ECS 结构化 JSON**:Spring Boot 3.4+ 内建 `StructuredLogEncoder`(`format=ecs`),带 `service.name/environment/node`;实测日志行含 `trace_id` + `requestId`(MDC)。
- **日志经 OTLP 进 SigNoz**:observability-otel starter 提供 `opentelemetry-logback-appender-1.0`(版本随 OTel BOM 对齐)并 `OpenTelemetryAppender.install(openTelemetry)`;样例 `logback-spring.xml` 挂 OTEL appender(`captureMdcAttributes=requestId` + `captureKeyValuePairAttributes`),`otel.logs.exporter=otlp`。
- **闭环实测**:`signoz_logs.distributed_logs_v2` 查到 service=`ordering` 的日志带 `trace_id`(appender 原生打的 span 上下文)+ `requestId` 属性;取其 `trace_id`(`5ed5229c…`)在 `signoz_traces` 命中对应 trace(`POST /orders→command PlaceOrder→JDBC`)—— 日志↔trace 双向可跳。
- 架构落点:日志-trace 桥接(appender + install)在**库侧** observability-otel starter(依赖 + 一次 install,无需强推 xml);控制台格式与 OTEL appender 挂载在**样例** `logback-spring.xml` + `application.yml`(消费方自持)。

> 注:此连通性对**当前 messaging-kafka 版本**(复用 Boot 自动配置的、被 OTel 自动埋点的 `KafkaTemplate`)成立。
> 期间一版"隔离自建 Kafka 工厂"的改动曾使生产者 span 消失、消费者 span 变裸根而**断链**(自建工厂绕过 OTel 自动埋点);
> 回滚后生产者 span 与生产者→消费者父子关系恢复。任何重新隔离 Kafka 基础设施的改动都须复验此 trace 连通性。

> 复现:`docker compose -f compose.yaml --profile observability up -d db kafka otel-collector signoz`;
> 应用参数去掉 `--otel.sdk.disabled=true`;SigNoz UI `http://localhost:48080`。验证查询(SigNoz ClickHouse):
> `SELECT name,count(*) FROM signoz_traces.distributed_signoz_index_v3 WHERE serviceName='ordering' GROUP BY name`。

**6) 事件 / 出站箱 / 入站箱 / Kafka ✅（F-1 已修复并验证）** — 见发现 F-1(issue-00044,resolved)。
运行期集成事件现确定性走 durable 出站箱 → Kafka → 入站箱:实测 outbox/inbox 各 4 行、Kafka 三主题偏移量
2/1/1(修复前全 0),SigNoz 中亦见 `ordering.events publish`/`process` 的 Kafka span。

---

## 发现

### F-1 集成事件在运行期被旁路，未经出站箱 → Kafka → 入站箱（→ issue-00044）

**现象** — 多次全新启动下单，业务流程均正确完成（订单 `CONFIRMED`/`CANCELLED`，流程实例
`COMPLETED`），但可靠传输链路完全无活动：

- `aipersimmon_outbox` 行数恒为 **0**（即使把出站箱轮询延时调到 30s 让行"驻留"，仍抓不到任何行）；
- Kafka 从未创建过 Producer 客户端（日志 `Producer clientId` 计数 = 0）；
- 三个主题 `ordering.events` / `inventory.events` / `payment.events` 末端偏移量均为 **0**（无消息产出）；
- `aipersimmon_inbox` 行数为 **0**；
- 出站箱中继线程 `scheduling-1` 正常轮询，但 `OutboxMapper.selectDue` 恒返回 `Total: 0`，
  且 DEBUG 全程无任何 `OutboxWriter` 的 INSERT。

**根因** — `IntegrationEvents` 端口存在三个实现，均带 `@ConditionalOnMissingBean(IntegrationEvents.class)`：
进程内的 `SpringIntegrationEvents`（events-spring）、出站箱写入的 `OutboxWriter`（outbox-jdbc）、
`OutboxWriter`（outbox-mybatis-plus）。本样例依赖 `events-spring` + **`outbox-mybatis-plus`**（无 `outbox-jdbc`）。
而 `events-spring` 的守卫条件（`AipersimmonDddEventsAutoConfiguration#integrationEvents`）为：

```java
@Bean
@ConditionalOnMissingBean(IntegrationEvents.class)
@ConditionalOnMissingClass("com.aipersimmon.ddd.outbox.jdbc.OutboxWriter")   // 只排除 jdbc 版
public IntegrationEvents integrationEvents(...) { return new SpringIntegrationEvents(...); }
```

该 `@ConditionalOnMissingClass` **只检测 jdbc 版 `OutboxWriter`**，检测不到
mybatis-plus 版（`com.aipersimmon.ddd.outbox.mybatisplus.OutboxWriter`，不同类名）。于是
`SpringIntegrationEvents` 仍然合格；`events-spring` 自动配置无 ordering 约束，先于
`outbox-mybatis-plus`（其为 `@AutoConfiguration(after=...)`）注册 `IntegrationEvents` bean，
凭 `@ConditionalOnMissingBean` 抢占，导致 mybatis-plus 的 `OutboxWriter` 退让。
**结果：集成事件退化为进程内同步投递，出站箱/Kafka/入站箱三件套虽全部装配却从不被触发。**

**影响** — 单体内业务流程仍正确（流程管理器直接消费进程内事件）；但一旦上下文拆成独立部署、
或要求"至少一次可靠跨进程投递 + 入站箱去重"（decision-00006 方式三 / design-00006 逐事件路由），
当前样例的运行期行为并不满足，可靠传输能力形同虚设。

**为何测试套件未发现** —
- `OutboxAtomicityTest` 仅断言回滚后 `outbox == 0`；当事件从不写出站箱时该断言天然成立（0==0），
  存在假阴性盲点（其应先断言"提交前出站箱有该行"）。
- `OrderingFlowTest` / `PaymentCompensationFlowTest` 只断言业务终态，进程内投递即可满足；
  其 Javadoc 描述了"出站箱→Kafka→入站箱"链路，但**无任何断言校验消息真的过了 Kafka、或入站箱去重生效**。

**修复(已实施 · 方案 3 全量,issue-00044 resolved)** — 三层:
(a) 删除 `events-spring` 硬编码类名守卫,由两个 outbox autoconfig 以 `@AutoConfiguration(beforeName=events)` 确定性抢占 `IntegrationEvents` 端口——基础层不再认识具体 outbox 类;
(b) `application` 加能力标记 `DurableIntegrationEvents`(两 writer 实现),`messaging-kafka` 加启动 fail-loud 守卫(有 `@Externalized`+Kafka 但活跃发布器非 durable → 启动即抛),三处 `@Bean` 打传输可见日志;
(c) 样例加 `IntegrationEventTransportTest`(断言 durable + inbox 有行)、强化 `OutboxAtomicityTest`(先断言 durable,消除 0==0 假阴性)。
库侧 6 模块 + 样例全反应堆全绿;实测下单后 outbox/inbox 各 4 行、Kafka 偏移量 2/1/1(修复前全 0)。

### F-2 Web 全局异常处理把未知路由/错误方法兜底成 500，健康探针不可用（→ issue-00045）

（上一版本曾推测为"actuator 渲染/序列化冲突"，经 DEBUG 抓取已定位真实根因，纠正如下。）

**现象** — 任何未映射路径/错误方法都返回 **500**（应为 404/405）：`GET /nope` → 500、
`GET /totally-unmapped-path` → 500、`GET /orders`（集合仅 POST，应 405）→ 500、`GET /actuator/health` → 500。

**根因** — 服务端 DEBUG 显示 `Resolved [NoResourceFoundException: No static resource actuator/health.]`
被 `AipersimmonDddWebExceptionHandler#handleUnexpected(Exception)`（catch-all）吞成 500。该 advice 只把
JDK `NoSuchElementException` 映射为 404（`:93-96`），未处理 Spring 路由级异常
`NoResourceFoundException`（应 404）/ `HttpRequestMethodNotSupportedException`（应 405），也未继承
`ResponseEntityExceptionHandler`，故它们全落到兜底 `Exception → 500`（`:98-102`）。这违反 design-00003
"NOT_FOUND→404 一等族"意图。次生问题：`spring-boot-starter-actuator` 不在类路径，`/actuator/**` 根本无映射，
`/actuator/health` 的 500 只是"未知路由→500"的一个实例 —— 结果 liveness/readiness/health 探针完全不可用。

**影响** — 每一个打错的 URL、每一次错误 HTTP 方法都变成 500，掩盖真实 404/405；健康探针缺失叠加此 bug，
在 k8s 等编排环境下会直接导致就绪/存活探测失败。

**修复(已实施 · 方案 A,issue-00045 resolved)** — `AipersimmonDddWebExceptionHandler` 在兜底之前补齐
`NoResourceFoundException`→404 / `HttpRequestMethodNotSupportedException`→405 /
`HttpMediaTypeNotSupportedException`→415 / `HttpMediaTypeNotAcceptableException`→406 /
`HttpMessageNotReadableException`→400(均走同一 `ProblemDetailFactory`,shape 一致,未继承
`ResponseEntityExceptionHandler`);样例 `start` 引入 `spring-boot-starter-actuator`;`ExceptionContractTest`
新增 3 条断言。库侧 18 tests + 样例全反应堆 `mvn test`(含 `ExceptionContractTest` 9 tests)全绿。

### F-3 SigNoz otel-collector 配置与其固定镜像不兼容,`observability` profile 起不来(已修复)

**现象** — `docker compose --profile observability up` 时 `otel-collector` 崩溃重启,日志:
`'service.telemetry.metrics' decoding failed … 'migration.MetricsConfigV030' has invalid keys: address`。
collector 无法启动 → 应用的 OTLP 导出无处接收 → 可观测性链路整体不可用。

**根因** — `start/docker/signoz/otel-collector-config.yaml` 用了 `service.telemetry.metrics.address: 0.0.0.0:8888`
(collector 自身指标的 Prometheus 端点)。该键在固定镜像 `signoz/signoz-otel-collector:v0.144.2` 中已被移除,
改用 OpenTelemetry 的 `readers` 结构 → 配置解析失败,启动即 fatal。属脚手架**提交进仓库的配置**与其**固定镜像版本**的真实不兼容。

**修复(已实施)** — 改为 `metrics.readers: [{ pull: { exporter: { prometheus: { host: 0.0.0.0, port: 8888 }}}}]`,
保留 :8888 自监控端点。重建 collector 后 "Everything is ready. Begin running and processing data",
OTLP gRPC/HTTP(4317/4318)正常收流,SigNoz 成功入库(见第 5 项)。

> 仅改样例的 `docker/signoz/otel-collector-config.yaml` 一处;不涉及库代码。这是运行 SigNoz 栈的前置阻断项,
> 建议纳入下游 SigNoz 兼容性维护(与 [[downstream-scaffolds-migration-debt]] 同类)。

---

## 复现命令

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
cd aipersimmon-ddd-scaffold/multi-module

# A. 构建
mvn clean install -DskipTests

# B. 测试套件（需 Docker）
mvn test

# C. 真实启动（仅起 db+kafka，避开 8080 端口冲突）
cd start
docker compose -f compose.yaml up -d db kafka
mvn spring-boot:run -Dspring-boot.run.arguments="\
  --spring.docker.compose.enabled=false \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/ordering \
  --spring.datasource.username=postgres --spring.datasource.password=postgres \
  --spring.kafka.bootstrap-servers=localhost:9092 --otel.sdk.disabled=true"
# 冒烟：
curl -i -XPOST localhost:8090/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"CUST-1","lines":[{"sku":"SKU-1","quantity":2,"unitAmountMinor":100,"currency":"USD"}]}'
curl -s localhost:8090/orders/<id>        # → CONFIRMED
docker compose -f compose.yaml down -v     # 清理（连卷一起删，避免 Flyway 校验和残留）
```

## 验收清单

| 需求 | 验证 | 结果 | 证据 |
| --- | --- | --- | --- |
| Web + RFC 9457 异常契约 | 真实 HTTP + `ExceptionContractTest` | pass | 201/400/422/404 实测 |
| CQRS 命令/查询总线 | HTTP + `*BusValidationTest` | pass | 下单/查询走总线 |
| Flyway 多组件迁移 | 启动日志 + DB 历史表 | pass | 4 张独立历史表 |
| 流程管理器 durable saga | HTTP + DB + 补偿测试 | pass | 实例 COMPLETED，两条路径 |
| ArchUnit 分层规则 | `ArchitectureTest`（5） | pass | 反应堆测试全绿 |
| 可靠集成事件（出站箱→Kafka→入站箱） | `IntegrationEventTransportTest` + 实测 | **pass（已修复）** | F-1 / issue-00044 resolved：outbox/inbox 各 4 行、Kafka 偏移量 2/1/1 |
| 可观测性 OTel → SigNoz | 实跑接入 SigNoz + ClickHouse 查询 | **pass（已在 SigNoz 验证）** | service `ordering` 1360 span;command/process.advance/outbox.publish + HTTP/JDBC/Kafka 自动埋点均入库(F-3 修复 collector 配置后) |
| Web 未知路由 → 404/405 | `ExceptionContractTest`（+3） | **pass（已修复）** | F-2 / issue-00045 resolved：404/405 映射补齐 |
| actuator 健康探针 | `ExceptionContractTest` | **pass（已修复）** | F-2 / issue-00045 resolved：actuator 上线，health 200 UP |

> 三个缺陷均已修复:F-1（issue-00044，**resolved**,可靠跨进程事件传输)、F-2（issue-00045，**resolved**,
> Web 404/405 + 健康探针)、F-3（本记录,SigNoz collector 配置不兼容,已修)。可观测性已**实跑接入 SigNoz
> 并在 SigNoz 中验证**(service `ordering` + 领域主干/边界 span 均入库)。其余集成的 DDD 组件(CQRS / Flyway /
> 流程管理器 / ArchUnit / 正常路径 Web + 异常契约)均已验证生效。**至此本报告全部 7 项组件能力通过。**
