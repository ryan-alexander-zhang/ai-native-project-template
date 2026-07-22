---
id: issue-00049-outbox-logging-dispatcher-fail-open-default
type: issue
role: main
status: open
parent: plan-00006-middleware-integration
---

# Outbox 默认 dispatcher 是 fail-open 的 LoggingOutboxDispatcher:无真实传输时消息被"记日志即标 sent",静默不投递

## 问题(现状,file:line 为证)

- **等级:Medium(危险默认值/静默数据丢失;仅在"启用 outbox 但未装配任何真实 dispatcher"时触发)**。
- Outbox 的兜底 dispatcher 是 `LoggingOutboxDispatcher`,由**无条件**的
  `@ConditionalOnMissingBean(OutboxDispatcher.class)` 注册:
  `AipersimmonDddOutboxAutoConfiguration.java:117-120`(无属性开关、无启动 WARN)。
- `LoggingOutboxDispatcher.dispatch()` **只 `log.info(...)` 后正常返回**、不抛异常:
  `LoggingOutboxDispatcher.java:15-24`。
- relay 把"dispatch 正常返回"当作投递成功,随即 `MARK_SENT`:jdbc `OutboxRelay.java:159,176`
  (mybatis 同构)。于是每条消息被**记一行日志 → 标 `sent=true`**,**从未离开进程**,也**不进 DLT**。
- 对照:进程内 dispatcher `InProcessOutboxDispatcher` 是**显式 opt-in**——只有
  `aipersimmon.ddd.outbox.dispatch=in-process` 时才注册(`AipersimmonDddOutboxAutoConfiguration.java:106-115`);
  未 opt-in 且无 messaging starter 时,落到的正是 logging 兜底。
- **既有 guard 不覆盖此情形**:issue-00044 的 `aipersimmonDddDurableTransportGuard`
  (`AipersimmonDddMessagingKafkaAutoConfiguration.java:135-142`)被 `@ConditionalOnBean(KafkaTemplate.class)`
  限定,**只有存在 KafkaTemplate 时**才装配并 fail-loud。若应用**根本没装 messaging-kafka**(无 KafkaTemplate),
  该 guard 不存在,`@Externalized` 事件照样落到 logging 兜底、静默"投递"。

后果:一个**打算**外发、但传输被漏配/错配(未引 messaging starter、未设 `dispatch=in-process`、未自定义
`OutboxDispatcher` bean)的部署,会把每条集成事件当作已投递归档——下游永远收不到,监控也看不到失败(无异常、无
DLT、无 lag),属**静默数据丢失**。

## 根因(第一性)

1. **观察 vs 期望**:期望"未配置真实传输 = 明显失败或至少进程内可达";实际"未配置 = 静默丢弃并标记成功"。
2. **最小机制**:为"开箱即用"提供一个不抛异常的兜底,让 outbox 在无 broker 时也能跑通冒烟;代价是把
   **开发便利默认值**直接暴露成了**生产可用路径**——`@ConditionalOnMissingBean` 无法区分"开发者有意用 logging"
   与"生产漏配 dispatcher"。
3. **真根因**:fail-open。可靠消息链路的兜底应当 fail-closed(要么可达、要么响亮失败),而非"记日志=成功"。
   issue-00044 已为"有 KafkaTemplate 但 publisher 非 durable"补了 fail-loud,但"无 dispatcher → logging 兜底"
   这条路径仍开着。

## 复现(test-first)

- 装配 `aipersimmon-ddd-outbox-jdbc`,**不**引 `messaging-kafka`,**不**设 `aipersimmon.ddd.outbox.dispatch`,
  **不**自定义 `OutboxDispatcher` bean;定义一个 `@Externalized` 事件并 `IntegrationEvents.publish(...)`。
- 断言(现状):outbox 行写入 → 下一轮 relay 只打印 `outbox dispatch (logging only): ...` → 行被标 `sent=true`;
  无异常、无 DLT、无本地重投。即一条本应外发的事件被静默吞掉。
- 断言(修复后):启动即失败(或按所选方案:进程内可达 / 响亮 WARN + guard 拦截),不出现"标 sent 但未投递"。

## 修复/建议

按"影响面小 → 大"排序,择一(或组合):

- **A(推荐,与 issue-00044 同源)扩展 durable/transport guard,去掉 `@ConditionalOnBean(KafkaTemplate)` 依赖。**
  改为在 outbox 侧(不依赖 kafka 存在)判断:**存在 `@Externalized` 事件**但 active `OutboxDispatcher` 是
  `LoggingOutboxDispatcher` → 启动失败(生产)。把 fail-loud 从"kafka 在场"下沉到"outbox 自身"。
- **B 把 logging 兜底改为显式 opt-in。** 默认无真实 dispatcher 时**不**注册 logging,而是启动失败;
  logging 仅当 `aipersimmon.ddd.outbox.dispatch=logging`(或类似)时启用——与 `in-process` 的 opt-in 对称。
- **C 兜底默认改为 in-process。** 至少保证进程内可达(而非静默丢),logging 退化为显式开发档。
  但注意:纯 in-process 对**打算跨进程**的事件仍是错的传输,只是不再静默丢——不如 A 直接。
- 无论哪种,若保留 logging 路径,**启动必须打一条响亮 WARN**指明"集成事件不会离开本进程"。

**注意改动面**:方案 A/B 触及 outbox 自动装配的兜底条件与(可选)一个新的 `@Conditional`/`SmartInitializingSingleton`
guard;不改 schema、不改 `OutboxDispatcher` 接口。需补一个"无真实 dispatcher + 有 `@Externalized` 事件 → 启动失败"
的装配测试。

## 关联

- [[plan-00006-middleware-integration]]
- [[issue-00044-integration-events-bypass-outbox-kafka-at-runtime]](已为"有 KafkaTemplate 但 publisher 非 durable"补 fail-loud guard;本 issue 是其未覆盖的姊妹路径:无 dispatcher → logging 兜底)
- [[design-00006-integration-event-routing]](LOCAL/EXTERNAL 路由;外发 opt-in)
