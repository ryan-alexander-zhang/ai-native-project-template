---
id: decision-00016-durable-runtime-staged-message-identity
type: decision
role: patch
status: active
parent: decision-00013-command-context-and-causation-propagation
---

# 增补：durable runtime 是 staged effect 的合法消息身份铸造方；`CommandBus` 新增 `sendAs`

本文是对 [[decision-00013-command-context-and-causation-propagation]] 的**增补(patch)**，由
[[design-00004-durable-process-manager-runtime]](durable Process Manager runtime)触发。decision-00013 的核心命题
**全部保留**；本文只放宽其中一条关于「谁铸造消息 id」的表述，并补一个配套派发入口。

## 结论先行

> **消息 id 的合法铸造权威，由「仅 `CommandBus`」扩展为「`CommandBus`(同步根/子命令)+ durable runtime(staged
> effect)」两个。** durable Process Manager 的 effect 在**推进事务内**被创建并持久化，其身份必须在此刻由 runtime
> **确定性铸造**(`transitionId + effectIndex`)并写入 effect 行；relay 之后可能多次发送时，**逐字复用**该身份。为此
> `CommandBus` 新增 `sendAs(command, messageContext)`：逐字使用传入 context、既不铸造新 id 也不 `deriveChild`，仅供
> 基础设施(effect relay / outbox 类 dispatcher)调用。decision-00013 真正的不变式——**业务代码与命令 payload 绝不
> 自造消息 id、绝无 ambient id**——原样成立；本文只是新增第二个**基础设施**铸造权威，不放开业务侧。

## Context

decision-00013 §1 写道:「id 由 bus 铸造(`root(id, trace)` / `deriveChild(childId)`),`CommandContext` 自身不生成 id」。
这条在当时的语境里是对的:一条同步命令的**创建**与**发送**是同一时刻——`CommandBus.send(...)` 那一刻既建立了消息、也
(唯一一次)发送了它,所以「在 send 时铸造 id」天然成立,`RegistryCommandBus` 每次 `idGenerator.get()` 也就够用。

[[design-00004-durable-process-manager-runtime]] 引入了一种此前不存在的消息流:**durable 暂存后重投的命令(staged
effect)**。它打破了「创建 == 发送」的隐含前提:

1. effect 在**推进事务内、决策时刻**被创建并持久化(design-00004 §4.3);
2. effect 在**之后**、由 relay 在**独立事务**里发送,且 at-least-once 下**可能发送多次**(design-00004 §4.4、§九)。

创建与发送在时间上和事务上都分离了。若沿用「发送时铸 id」,relay 每次重投都会拿到一个**新的随机 messageId**,下游会把
同一条 effect 的多次重投看成多条不同命令,去重失效 → 重复扣款/重复扣库存。因此对 staged effect,身份必须在**创建点**
铸造、在每次发送时原样携带。这正是既有 **outbox** 对集成事件早已采用的做法(写行时铸 `eventId`、重投原样复用,见
[[decision-00006-integration-event-transport-selection]]);缺口只在**命令**通道——命令此前从不被 durable 暂存后重投。

## Decision

1. **合法铸造权威扩展为两个。**
   - **同步根/子命令**:身份由 `CommandBus` 在 `send(...)` / `send(..., cause)` 时铸造(decision-00013 §1 不变)。
   - **staged effect**:身份由 **durable runtime 在推进事务内确定性铸造**——`messageId = effectId = f(transitionId,
     effectIndex)`,连同 `correlationId`(继承输入)、`causationId`(= 输入 messageId)、`traceId` 一并持久化到 effect 行。
     `UNIQUE(transition_id, effect_index)` 保证事务重试不产生重复身份。

2. **`CommandBus` 新增逐字派发入口(`aipersimmon-ddd-cqrs`)。** 现有两个入口都无法逐字复用一个已持久化身份
   (`send(cmd)` 自铸根 id;`send(cmd, cause)` 以 cause `deriveChild` **另铸**子 id),故新增第三个:

   ```java
   /**
    * 派发一条已由上游 durable 存储(Process Manager effect relay 或 outbox)赋予身份的命令,以 at-least-once 语义重投。
    * bus 不铸造、不派生:messageContext 被逐字使用,故同一条持久化 effect 的每次重投都在同一 messageId 下到达 handler
    * ——这个稳定身份正是 handler(或其 inbox)去重的依据。
    * 仅供基础设施(relay / dispatcher)调用;application 与 handler 代码只能用 send(...)。
    */
   <R> R sendAs(Command<R> command, CommandContext messageContext);
   ```

   `RegistryCommandBus.sendAs` **不调用 `idGenerator`、不 `deriveChild`**,直接以传入 `messageContext` 走拦截器链和
   handler。语义上它是「重放一条已存在的消息」,不是「创建一条新消息」。

3. **`CommandContext` 保持纯值,不承担铸造。** 身份来自 effect 行,由 runtime 组装成 `CommandContext` 传入 `sendAs`;
   `CommandContext` 自身仍不生成 id(decision-00013 §1 后半句不变)。

4. **边界由 ArchUnit 守卫。** 只有基础设施包(effect relay / dispatcher / outbox)可调用 `sendAs(...)`;任何
   `CommandHandler` 或 application 类调用即测试失败,防止它退化成绕过因果链、伪造消息身份的后门。

5. **传输身份 ≠ 业务幂等键(互补)。** `messageId = effectId` 是**传输层**身份,让 relay 的重投对每种 effect 统一、廉价
   地可去重;不可逆业务动作(扣款、扣库存)仍必须由聚合持有的**业务操作 id**(如 `paymentOperationId`)自行幂等——即便
   经另一路径触达也不重复。两层并存,互不替代(承接 [[decision-00011-cqrs-write-contracts-as-interfaces-not-annotations]]
   「元数据在 payload 之外」:effectId 走 context、业务操作 id 才是 payload 里的领域字段)。

## Rationale

- **命题一 —— 身份属于「创建」,不属于「发送」。** at-least-once 下一条消息只创建一次却可能发送多次;身份是创建的属性。
  同步命令「创建 == 发送」使旧表述成立,staged effect 打破它,故必须把铸造点移回「创建」(推进事务内),发送点只搬运。
- **命题二 —— 这是把 outbox 的既有范式推广到命令通道,不是发明新机制。** 集成事件早已「写行铸 id、重投复用」;
  `sendAs` 只是补齐命令侧的对等入口,使两条 effect 通路(§design-00004 §3.5)对称。
- **命题三 —— decision-00013 的精神不变。** 那条决策要防的是 payload 污染与 ambient id(承接
  [[decision-00012-no-ambient-per-command-state]])。runtime 确定性铸造 + 持久化 + 逐字搬运,既非 ambient(显式随
  `CommandContext` 传递)、也不进 payload;业务 Definition 更不创建 id(runtime 按 transition 派生)。故不变式原样成立。
- **为何不复用 `send(cmd, cause)`。** 它的语义是「以 cause 派生**新**子消息」,会 `deriveChild(idGenerator.get())`,
  无法令 `messageId = effectId`;且同签名不同语义会造成重载陷阱。必须是独立命名的 `sendAs`。

## Consequences

- `aipersimmon-ddd-cqrs`:`CommandBus` 增 `sendAs(Command, CommandContext)`;`RegistryCommandBus` 实现为「逐字派发、
  不铸不派生」。现有 `send(cmd)` / `send(cmd, cause)` 与 `LoggingCommandInterceptor`(仍从 `correlationId` 播 MDC)不变。
- `aipersimmon-ddd-archunit`:新增规则,禁止 `CommandHandler`/application 调用 `sendAs(...)`。
- `aipersimmon-ddd-process-manager-jdbc`:effect relay 的 `CommandEffectDispatcher` 从 effect 行重建 `CommandContext`
  并经 `sendAs` 派发;`IntegrationEventEffectDispatcher` 沿用 `IntegrationEvents.publish`(其身份 outbox 早已负责)。
- 下游去重:以 `messageId = effectId` 作 inbox 键(接收端)与/或业务操作 id(发送端)幂等,二者按 §Decision 5 分层。
- 与 [[decision-00013-command-context-and-causation-propagation]] 的关系:本文**只增补 §1 的「唯一铸造方」表述**,其余
  命题(handle/send 显式携带 context、出站盖章、入站交付完整信封、不新增 handler 契约、模块依赖无环)全部继续有效。

## Sources

内部：

- [[decision-00013-command-context-and-causation-propagation]] —— 被增补的父决策。
- [[design-00004-durable-process-manager-runtime]] §3.5 —— 触发本增补的派发身份契约。
- [[decision-00006-integration-event-transport-selection]] —— outbox「写行铸 id、重投复用」的既有范式。
- [[decision-00011-cqrs-write-contracts-as-interfaces-not-annotations]]、[[decision-00012-no-ambient-per-command-state]]
  —— 元数据在 payload 之外、禁 ambient(本文不变式的来源)。
</content>
