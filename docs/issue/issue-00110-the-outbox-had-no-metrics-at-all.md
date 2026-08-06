---
id: issue-00110-the-outbox-had-no-metrics-at-all
type: issue
status: resolved
blocks: [report-00003-ddd-library-review-2026-07-29]
---

# outbox 零指标：两条最经典的告警必须手写 SQL 打库才能得到

## 症状

想知道「有多少事件还没发出去」或「最老那条等了多久」，唯一办法是对 `aipersimmon_outbox` 手写
`SELECT COUNT(*)` / `MIN(created_at)`——而那是**消费方并不拥有**的表：列名与谓词是框架的实现细节，
一次迁移就能让告警静默失效。process-manager 早就有完整的 SLI 套件（`oldest_pending_effect_age`、
`claim_latency`、`dispatch_latency` …），outbox 一条都没有。

## 决定

照 process-manager 的形状做，不新造词汇。它已经把这件事拆对了：

| 性质 | 承载 | 为什么不能互换 |
|---|---|---|
| **发生过的事**（push） | `OutboxObserver` | counter 答不了「此刻积压多深」 |
| **当前的量**（pull） | `OutboxBacklog` | gauge 答不了「过去一小时是否放弃过消息」 |

两者都与框架无关（engine 不带 Micrometer 依赖，`micrometer-core` 是 `optional`），
Micrometer 实现只在 `MeterRegistry` 存在时绑定，否则 relay 用 `OutboxObserver.NOOP`，未接线的部署零成本。

### push：`OutboxObserver`

- `claimed(rows, latency)` / `dispatched(success, latency)` —— 延迟分布，按 outcome 打标。
- `deadLettered(reason)` —— **这里最重要的一条**，按 reason 打标。「是否丢过消息」应该是对**事件**告警
  （`increase(...[1h]) > 0`），而不是看死信表的深度：深度只说明「还有多少等人处理」，而一条已被重放的
  死信会让深度回落，告警随之消失。所有 reason 在启动时就注册好，于是仪表盘有一条值为 0 的曲线可以告警，
  而不是等真丢了消息那一刻指标才第一次出现。
- `markSentFailed()` —— broker 已收到但记账失败，会重投一次（消费方 inbox 吸收）。值得看，不值得单独告警。
- `released(rows)` —— 轮询用尽时间预算交还的行。**第 7 项引入的旋钮的唯一反馈信号**：稳定有速率就意味着
  预算撑不住当前的投递速度，该降 `batch-size` 或抬 `relay.lease-duration`。

### pull：`OutboxBacklog`

两条 gauge：`aipersimmon.outbox.pending` 与 `aipersimmon.outbox.oldest.pending.age`。

- **深度单独看是有歧义的**：一千行、每行几秒钟，是个忙系统；五十行、最老的一小时，是 relay 停了。
  所以**年龄才是该告警的那条**，深度回答「有多严重」。
- 「等待中」的定义与 claim 的存活判据**同一条谓词**（未发送 AND `attempts < max`）：已发送的行完事了,
  已放弃的行归死信表管。任何一边算进去，告警都会永远地喊狼来了。
- 空 outbox 读作 `0` / `Duration.ZERO`，不是「无读数」——gauge 需要有值才能告警。

## 端口只加了一个方法，且是一次扫描

`OutboxStore.pendingBacklog(maxAttempts)` 返回 `PendingBacklog(rows, oldestCreatedAt)`。
不是两个方法（`countPending` + `oldestCreatedAt`），因为同一条谓词的 `COUNT` 与 `MIN` 一次扫描就都有了——
一次抓取不该为每个 gauge 付一次往返。`maxAttempts` 由调用方传入，好让「存活」在这里和在
`claimDue` 里是同一个意思：定义归 engine 所有。

MeterBinder 另外把一次抓取内的多个 gauge 读合并成一次查询（1 秒记忆化，与
`ProcessManagerMeterBinder` 同法）。

## 刻意没做：健康检查

process-manager 有 `ProcessManagerHealthIndicator`，outbox **不加**。

一个连不上 broker 的 relay 不是「有病的实例」。把 pod 翻成 DOWN 会因为一个重启修不好的问题把它踢出服务，
而积压还在原地。更直接的证据是本次评审的 C5（[[issue-00104-an-ended-instance-keeps-its-timers-forever]]）：
那正是一个卡在 DEGRADED 上、没有任何东西在排空的健康检查。积压年龄是拿来叫人的 gauge，不是回收 pod 的理由。

## 落地

- 新增 `outbox.engine.observe` 包：`OutboxObserver`（含 `NOOP`）、`OutboxBacklog`（含 `Snapshot`）。
- `outbox.engine.autoconfigure`：`OutboxMeters`（名字，前缀 `aipersimmon.outbox.`）、
  `MicrometerOutboxObserver`、`OutboxMeterBinder`、`OutboxObservabilityConfiguration`
  （注册进 `AutoConfiguration.imports`，排在 engine autoconfig 之后）。
- `OutboxRelay` 多一个 11 参构造（observer 在最后），9/10 参重载沿用 NOOP——既有调用点不受影响。
  上报点：claim 后、dispatch 前后、mark-sent 失败、release、死信搬移成功。
- 两个 store 各实现 `pendingBacklog`；MyBatis 侧多一个 `PendingBacklogRow`（MyBatis 按 setter 映射，
  故用 bean 而非 engine 的 record）。
- **无新配置项**。

## 验证

`OutboxMetricsTest`（jdbc 6 例）：积压只算 relay 仍打算投递的行（已发送的、已耗尽尝试的都不算）；
年龄取自最老的等待行；空 outbox 读作 0 而非无读数；有 `MeterRegistry` 时 starter 绑定 Micrometer 观察者
且 binder 是个 `MeterBinder`；binder 手动绑到一个 `SimpleMeterRegistry` 后两条 gauge 读得到真值；
relay 把 claim / 成功与失败的 dispatch / 放弃及其 reason 都上报出来。

指标测试放在 `-outbox-jdbc` 而非 engine，与 `ProcessManagerJdbcObservabilityTest` 同址同因：
engine 自身没有测试基架（那是报告第 12 项的事），Micrometer 在 engine 是 optional、在这里是 test scope。

**踩坑一**：测试里自己注册裸 `SimpleMeterRegistry` 时，Boot 的 `MeterBinder` 后处理器（需要 actuator 的
metrics autoconfiguration）不在场，gauge 不会自动绑上。所以 gauge 的断言改为手动 `bindTo` 一个新 registry——
测的是 binder 自己的契约（注册什么名字、读出什么值），并在测试里注明了原因。

**踩坑二（先例存在的原因）**：Micrometer bean **必须**收在由 `@ConditionalOnClass` 守卫的嵌套
`@Configuration` 里，不能摊平成方法级条件。我第一版摊平了，结果 MyBatis 那侧（测试 classpath 上没有
Micrometer）**每一个** Spring 上下文都起不来：Spring 为 `outboxBacklog` 推导 bean 类型时会反射内省整个配置类，
而类里其他方法签名引用了 `MeterRegistry` → `NoClassDefFoundError`。`ProcessManagerObservabilityConfiguration`
写成嵌套类正是为此。顺带说明：MyBatis 测试模块**不**加 Micrometer 反而更好——它证明了 engine 在没有
Micrometer 时照样工作。

**踩坑三（同一个先例的第二半）**：`OutboxBacklog` 这个 bean 必须由**主** autoconfig 提供，不能和
Micrometer bean 放在同一个配置类里。`@ConditionalOnBean` 只看得见**已经注册**的 bean，而 Spring 处理配置类时
先递归处理成员类、再注册外层的 `@Bean` 方法——所以嵌套类里 `@ConditionalOnBean(OutboxBacklog.class)` 评估时
外层的 `outboxBacklog` 还不存在，binder 静默消失。process-manager 的 `ProcessBacklog` 由主 autoconfig 提供，
天然避开了这一点。这也正好符合它该在的位置：backlog 读与框架无关，运维端点或自定义探针不装任何指标库也能用它。

库 + 脚手架两个 reactor `mvn clean verify` 全绿。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（§2 Outbox「零 Micrometer 指标」那条、§3 第 9 项）
- 同形先例：[[design-00004-durable-process-manager-runtime]] §5.3（push/pull 分家、MeterBinder 记忆化）
- 相邻接缝：`StoreAndForwardTracer`（一条消息的旅程） vs 本项（整个群体的形状），见
  [[design-00005-observability-and-distributed-tracing]] §10.2
- `released` 是第 7 项那个旋钮的反馈信号：[[issue-00108-a-killed-relay-instance-stops-all-delivery]]
- 不加健康检查的直接依据：[[issue-00104-an-ended-instance-keeps-its-timers-forever]]
