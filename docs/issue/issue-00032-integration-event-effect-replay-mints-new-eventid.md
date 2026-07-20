---
id: issue-00032-integration-event-effect-replay-mints-new-eventid
type: issue
role: main
status: resolved
parent: plan-00003-durable-process-manager-implementation
---

# IntegrationEvent 效果重投铸造新 eventId → 下游 Inbox 去重失效,同一 effect 被当两条

## 问题(现状,file:line 为证)

- **等级:Critical**(违反核心幂等契约:不可逆业务事件可被下游重复消费)。
- process manager 的效果中继在投递 `PublishIntegrationEvent` 效果时,走的是**无 id** 的发布路径:
  `relay/IntegrationEventEffectDispatcher.java:29` 调 `integrationEvents.publish(effect.payload(), context)`,不传任何稳定 id。
- outbox 写入端每次都**现铸随机 eventId**:`outbox-jdbc/.../OutboxWriter.java:60-61` 用 `UUID.randomUUID()` 作 `eventId`;
  持久化在效果行上的稳定 `effectId`(`context.messageId()`)只被写成 `causationId`(`:68`/`:79`),**未用作 eventId**。
- 效果投递的 outbox INSERT 与 `markDelivered` 是**两次独立提交**:`relay/JdbcProcessEffectRelay.java:146-172` 的 `deliver()`
  先 dispatch(内部 outbox INSERT 自动提交),再单独 `markDelivered`;`JdbcProcessUnitOfWork` javadoc 明言 "effect delivery
  deliberately happens outside this transaction"。故存在故障窗口:

  ```text
  effect relay
    -> publish -> INSERT outbox(eventId=A) 提交
    -> 进程在 markDelivered 前崩溃
    -> 租约到期,同一 effectId 被重新认领
    -> 再次 publish -> INSERT outbox(eventId=B) 提交
  ```

- 下游按 eventId 去重:`messaging-kafka/.../KafkaIntegrationEventListener.java:88` 取 header `ID`,
  `inbox-jdbc/.../JdbcInbox.java:43-49` 按该 key(按 consumer 作用域)去重。A≠B → 被当作两条不同消息 → **重复消费**。

## 根因(第一性)

1. **观察 vs 期望**:期望"同一持久化 effectId 无论重投多少次,对下游只呈现为同一条消息"(design-00004:256/278 的同 effectId
   重投契约);实际"每次重投都得到一个新的随机 eventId,下游去重键随之变化"。
2. **最小机制**:稳定身份 `effectId` 在发布点其实**已在 `context.messageId()` 中就位**,但 `OutboxWriter` 把它路由给了
   `causationId`,而 `eventId` 一律 `UUID.randomUUID()`(`:60-61`);`IntegrationEvents` 接口也**没有**接收稳定 id 的入口。
3. **真根因**:decision-00016 "outbox 已负责身份" 的假设**只对 outbox→broker 这一跳成立**(同一行、`OutboxRelay` 复用既有
   `event_id` 重发),却**漏了 effect-relay→outbox 这一跳**——每次至少一次重投都执行一次全新的 `publish()`、写入带新随机 eventId
   的新行。command 效果路径已用 `sendAs`(稳定 `messageId==effectId`)加固,integration-event 效果路径仍停在裸 `publish()`。
   排除的伪根因:不是 outbox→broker 不幂等(那一跳正确),也不是 Inbox 去重逻辑有误(去重正确,只是键被上游污染)。

## 复现(test-first)

两个回归测试(先写、复现失败,再随修复转绿):

1. `OutboxJdbcTest#publishAsReusesTheEffectIdAsEventIdAndIsIdempotentAcrossRedelivery`(`aipersimmon-ddd-outbox-jdbc`):
   以效果 context(`messageId = "txn-1#0"`,即持久化 effectId)对同一 context **连投两次** `publishAs`(模拟租约到期重认领 /
   崩溃在 `markDelivered` 前重投),断言 outbox **只有一行**、其 `event_id` **逐字等于** effectId,且 `correlation_id`/
   `causation_id` 逐字取自 context。裸 `publish()` 路径下两次投递会写两行、各持一个 `UUID.randomUUID()` eventId。
2. `IntegrationEventEffectDispatcherTest#dispatchesThroughPublishAsUnderTheEffectContextVerbatim`
   (`aipersimmon-ddd-process-manager-jdbc`):记录型 `IntegrationEvents` 在 `publish(...)` 被调用时 `fail(...)`,断言中继走
   `publishAs` 且逐字透传效果 context。

## 修复(已实施)

对症"稳定身份未逐字传递"这一根因,与 command 效果路径的 `sendAs` 对齐:

1. **`IntegrationEvents` 新增 `publishAs(event, context)` 默认方法**(`aipersimmon-ddd-application`):默认抛
   `UnsupportedOperationException`(基础设施专用,镜像 `CommandBus#sendAs`),把 context 的持久化身份**逐字**印到 envelope——
   `eventId = context.messageId()`(= effectId)、`correlationId`/`causationId` 取自 context(即 `CommandContext.of(envelope)`
   的逆映射),不再 mint 随机 id。
2. **效果中继改调 `publishAs`**:`IntegrationEventEffectDispatcher.dispatch` 由 `publish(...)` 改为 `publishAs(...)`。
3. **`OutboxWriter`(jdbc + mybatis-plus)幂等插入**:`event_id` 早有 `UNIQUE` 约束;`publishAs` 走同一 INSERT 但**吞掉
   `DuplicateKeyException`**——重投重插同一 `event_id` 即坍缩到既有行(无需 dialect 特定的 `ON CONFLICT`,H2/PG/MySQL 通用)。
   这一步是必需而非可选:`eventId = effectId` 使重投 INSERT 命中唯一键,不吞掉会让投递永久失败。`publish()`(随机 id)保持严格、
   不吞。
4. **`SpringIntegrationEvents` 同步 `publishAs`**:进程内传输亦以稳定 id + 逐字因果链发布,契约完整(无插入、无需吞异常)。

## 验证结果

- 两个新回归测试通过;`OutboxJdbcTest` 既有断言(`publish()` 的 `causationId = "cmd-1"` 等)不回归,证明 `publish()` 行为逐字保留。
- 受影响 reactor `mvn -o -am test` 全绿:application / outbox-jdbc(含新测试)/ outbox-mybatis-plus / events-spring /
  process-manager-jdbc(含新测试)/ process-manager-jdbc-spring-boot-starter,失败与错误计数均为 0。

## 关联

- [[decision-00016-durable-runtime-staged-message-identity]] —— "outbox 已负责身份" 假设的出处,本条证伪其覆盖范围。
- [[design-00004-durable-process-manager-runtime]] —— 同 effectId 重投契约(:256/:278)。
- [[issue-00003-messaging-delivery-reliability]] —— 同属投递可靠性/去重族。
- [[plan-00003-durable-process-manager-implementation]]
