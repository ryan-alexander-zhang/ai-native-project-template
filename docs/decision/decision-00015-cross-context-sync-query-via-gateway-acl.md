---
id: decision-00015-cross-context-sync-query-via-gateway-acl
type: decision
role: main
status: active
parent:
---

# 跨上下文同步调用:Open Host Service + 消费方 Gateway ACL,且只用于读

固化"一个 BC 需要**当场读到另一个 BC 当前状态**时怎么调"的选型。承接
[[analysis-00004-bounded-context-module-structure]](跨上下文只经发布语言,#5)、
[[analysis-00002-domain-vs-integration-events]](防腐 / Published Language)、
[[decision-00006-integration-event-transport-selection]](异步事件传输)与
[[analysis-00007-saga-process-manager]](长流程编排)。本文只定**同步调用**这一条通道
的形状与边界;异步状态传播仍归上述两篇,不在此重复。

## Context

模板已有两条成熟的跨 BC 通道,都是**异步**的:集成事件(decision-00006)与 saga 编排
(analysis-00007)。但有一类需求它们答不了——**决策当刻需要对方的当前状态**:下单前问库存
"这些 SKU 现在能不能提供",据此**快速失败**,不为一个注定失败的请求创建聚合、起 saga。

这类需求的三条约束:

1. **是读,不是写**:幂等、无副作用、当场要结果——同步请求/响应天然合适,做成事件反而别扭
   (事件无返回值)。
2. **不能直连内部**:跨上下文只能经对方**发布的契约**,不得引用对方 domain/application 内部
   (analysis-00004 #5;ArchUnit `boundedContextsShouldOnlyDependOnEachOthersApi` 编译期/测试期
   守着)。
3. **传输要能演进**:模块化单体内是进程内调用,拆成独立服务后是 HTTP/RPC——业务代码不该因此改动
   (monolith-first,promotion ladder,analysis-00004 #3)。

## Decision

**跨 BC 同步调用走"提供方 Open Host Service 契约 + 消费方持有的 Gateway ACL 端口",
传输中立(进程内 now / HTTP later,切换只改装配),且此通道仅用于读。**

结构分四件,方向 消费方 → 提供方:

```
[消费方]  用例代码 ──▶ XxxGateway (ACL Port，消费方自己的语言，{consumer}-application)
                            │ 实现
                            ▼
              XxxGatewayAdapter (driven adapter，{consumer}-infrastructure) ← 唯一引用对方契约的类
                            │ 依赖(仅 interface) ── {provider}.api ──
[提供方]  XxxApi (契约: interface + 自有 DTO，{provider}-api)
                            │ 实现(inbound adapter，Controller 的兄弟)
                            ▼
              XxxService (inbound adapter，{provider}-adapter/ipc) ── QueryBus.ask ──▶ 应用层查询用例
```

四条约束逐一固化:

1. **契约在提供方 `{provider}-api`**:一个 `XxxApi` 接口 + 它**自有的**请求/响应 DTO。DTO
   **既不是** Controller 的 HTTP DTO(绑 web、绑校验注解),**也不是**内部 Command / 领域类型
   (暴露即泄漏内部模型,对方一重构就破坏下游)。它是扁平、可序列化的**发布语言**,只带 id + 最小
   数据,好让进程内调用平滑升级为 HTTP payload。

2. **提供方实现是 inbound(driving)adapter**,放 `{provider}-adapter`(如 `ipc` 包),
   **与 Controller 同层同类**——只是协议不同(进程内调用 vs HTTP)。它把契约 DTO 翻译成应用层查询、
   经 `QueryBus` 委托,再把读模型翻回契约 DTO。**应用层与领域层不认识契约类型**;提供方侧的防腐翻译
   落在这里。

3. **消费方持有 Port**,定义在 `{consumer}-application`,**用消费方自己的语言和类型**;用例代码只依赖
   这个接口。对方契约(`{provider}-api`)的**唯一引用点**是 `{consumer}-infrastructure` 里实现该
   Port 的 driven adapter——即消费方侧的 **ACL**,在此做契约 DTO ↔ 本上下文语言的翻译。消费方的
   domain/application 全程不出现对方任何类型。

4. **传输中立,切换点是 `{provider}-api` 的 bean**:Port 只有一个实现(那个 ACL adapter),
   **永不改**;变的是 ACL 注入到的 `XxxApi` bean 是谁——单体内是提供方的进程内实现,拆分后是一个
   实现同一 `XxxApi` 接口的 HTTP 客户端桩。二者都在 `start` 装配层决定。业务代码、Port、双方的
   application/domain 一律不动。

## 边界:此通道**只读**,状态变更不走这里

**跨 BC 的状态变更默认走异步(集成事件 / saga),不走同步 Gateway。** 理由:

- 同步跨 BC 写把两个 BC 的**事务与可用性绑死**,且跨进程后拿不到横跨两 BC 的 ACID 事务——只会手搓出
  脆弱的分布式事务,带来双写不一致。
- 模板为**异步**路径准备了 outbox/inbox 的 at-least-once + 幂等兜底(decision-00006);**同步写这条路
  没有等价兜底**,可靠性得使用者自己扛。
- 每个 BC 只改自己的状态。跨 BC 状态变更 = 请 owning BC "改你自己的",由它在自己的事务里执行,触发形式
  是它监听到的**消息**(事件 / 命令消息),而非别的 BC 同步伸手进来写。

按下表选型,**不跳级**:

| 需求 | 通道 | 一致性 |
| --- | --- | --- |
| 读对方当前状态,当场要 | **本决策:同步 Gateway ACL** | 强一致读 |
| 单步、可最终一致的状态变更 | 集成事件,owning BC 监听后用自己的命令改(decision-00006) | 最终一致 |
| 多步、跨多 BC、需协调 + 补偿 | Saga / Process Manager 编排(analysis-00007) | 最终一致 + 补偿 |
| 硬要求同步确认对方写结果 | 同步命令调用对方**发布的**写 API(经 `..api..`) | 使用者自扛双写/幂等/耦合 |

最后一行:**技术上允许**(ArchUnit 只禁伸手进对方非 api 内部,不禁调对方发布的写 API),但作为默认
**不推荐**——需要同步确认、操作天然幂等、且愿付上述代价时才选,即便如此也应配幂等键。

## Consequences

**正向**
- 跨 BC 同步读有唯一正规通道,不再有"直连内部"的诱惑;ArchUnit 把跨界收敛到 `..api..`。
- IPC→HTTP 是**换 bean 的装配动作**,不是重写(与 decision-00006 事件传输"换 starter+属性、业务码不动"
  同构)。
- 两处 ACL(提供方 inbound adapter、消费方 gateway adapter)把双方内部模型互相隔离,任一侧重构不波及对方。
- 契约 DTO 独立于 Controller DTO 与内部 Command,可序列化、可版本化。

**负向 / 注意**
- 同步调用引入**可用性/时序耦合**:提供方不可用会传导给消费方,需按需补超时/重试/熔断(HTTP 阶段尤甚)——
  这些不在本通道的默认兜底内。
- 同步读是**读时快照**:通过检查不等于后续操作仍成立(TOCTOU)。据它做的决定应是"快速失败"性质的,权威结论
  仍由 owning BC 在自己的写事务里给。
- 消费方 `{consumer}-infrastructure` 会**编译依赖** `{provider}-api`(契约 jar)。这是跨 BC 允许的最小耦合面,
  但意味着提供方契约变更需按发布契约管理(向后兼容 / 版本化)。
- 误用风险:把它照搬到**状态变更**上会把事件/saga 专门规避的双写与耦合问题请回来。形状可复用,语义要先问
  "我真的需要同步确认吗"。

## Sources

内部:

- [[analysis-00004-bounded-context-module-structure]] —— 跨上下文只经发布语言(#5)、promotion ladder(#3)、
  Structure 2 模块布局。
- [[analysis-00002-domain-vs-integration-events]] —— 防腐层 / Published Language;契约只带 id + 最小数据、
  不暴露内部类型。
- [[decision-00006-integration-event-transport-selection]] —— 异步状态传播的传输选型与 outbox/inbox 兜底
  (本通道之外的另一条路)。
- [[analysis-00007-saga-process-manager]] —— 多步跨 BC 状态变更的编排 + 补偿。
- [[analysis-00011-event-send-consume-mechanisms]] —— **事件(异步)**收发机制清单;本决策是其对照面的
  **同步(非事件)**跨 BC 通道。
- 已工作实现(演示,非设计权威):`aipersimmon-ddd-scaffold/multi-module` —— ordering 下单前经
  `StockAvailabilityGateway`(Port)/ `StockAvailabilityGatewayAdapter`(消费方 ACL)同步查询 inventory
  的 `StockAvailabilityApi`(提供方契约,`inventory-adapter/ipc` 实现);与既有 `OrderPlaced` → 预约库存 →
  saga 的**异步**写路径并存,恰好对照本决策的读/写分工。

外部:

- Eric Evans, *Domain-Driven Design* —— Open Host Service、Anti-Corruption Layer、Published Language。
- Chris Richardson, microservices.io —— *Saga* 与跨服务一致性(为何跨 BC 写默认异步 + 补偿,而非同步分布式事务)。
