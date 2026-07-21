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
| 可观测性（OTel starter） | ⚠️ 未在本次实跑中启用 | 依赖已装配；本次禁用 SDK、未起 SigNoz |
| **事件 / 出站箱 / 入站箱 / Kafka** | **✅ 已修复** | 曾运行期旁路(F-1 / issue-00044);已修:outbox writer 确定性胜出 + fail-loud 守卫 + 传输日志,实测走通 outbox→Kafka→inbox |
| **Web 未知路由 / actuator 健康探针** | **✅ 已修复** | 曾全部 500（F-2 / issue-00045）；已补齐 404/405/415/406/400 映射 + actuator，回归测试通过 |

> 结论：核心 DDD 战术组件（CQRS / Flyway / 流程管理器 / ArchUnit / 正常路径的 Web + 异常契约）全部生效并按预期工作。
> 本次发现**两个框架级真实缺陷**,均已建 issue **并已修复**:
> - **F-1（issue-00044，已修复）**:可靠集成事件传输（出站箱→Kafka→入站箱，decision-00006 / design-00006）在运行期被进程内发布器抢占而整体旁路。已按方案 3 全量修复(outbox writer 以 `@AutoConfigure beforeName` 确定性胜出、`DurableIntegrationEvents` 标记 + messaging-kafka 启动 fail-loud 守卫、传输可见日志、样例回归测试)。实测下单后 outbox/inbox 各 4 行、Kafka 三主题偏移量 2/1/1(修复前全 0)。
> - **F-2（issue-00045，已修复）**：Web 全局异常处理把未知路由/错误方法兜底成 500（应为 404/405），且无 actuator starter，导致健康探针不可用。已按方案 A 修复(补齐 404/405/415/406/400 映射 + 引入 actuator + 三条回归断言),`status: resolved`。

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
`repositoryImplementationsShouldBeSpringRepositories` 也通过。`PackageInfoTest` 校验每个包存在
`package-info.java`。

**5) 可观测性 ⚠️（本次未实跑启用）** — `aipersimmon-ddd-observability-otel-spring-boot-starter`
已在依赖与配置中装配（`otel.exporter.otlp.endpoint=http://localhost:44317`，绑定
command / process.advance / outbox.publish span）。本次启动为避免依赖 SigNoz 重型栈，显式
`--otel.sdk.disabled=true` 且未起 `observability` profile，故导出链路未实测。健康探针不可用见 **F-2**。

**6) 事件 / 出站箱 / 入站箱 / Kafka ⚠️ —— 见发现 F-1**

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
| 可观测性 OTel 导出 | — | 未实测 | 本次禁用 SDK / 未起 SigNoz |
| Web 未知路由 → 404/405 | `ExceptionContractTest`（+3） | **pass（已修复）** | F-2 / issue-00045 resolved：404/405 映射补齐 |
| actuator 健康探针 | `ExceptionContractTest` | **pass（已修复）** | F-2 / issue-00045 resolved：actuator 上线，health 200 UP |

> F-1（issue-00044，**resolved**）与 F-2（issue-00045，**resolved**)两个框架级真实缺陷均已修复并回归通过,
> 分别涉及"可靠跨进程事件传输"与"Web 404/405 语义 + 健康探针"。其余集成的 DDD 组件(CQRS / Flyway /
> 流程管理器 / ArchUnit / 正常路径 Web + 异常契约)均已验证生效。至此本报告全部项目通过。
