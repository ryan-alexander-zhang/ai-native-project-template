---
id: issue-00028-broker-transport-on-single-deployable-monolith
type: issue
role: main
status: open
parent: plan-00006-middleware-integration
---

# 单可部署单体上采用 broker+outbox(方式三):最重传输的定位/认知风险

> **性质说明:这不是代码缺陷,而是架构定位/文档项。** 记录于此以便决策是"接受并文档化"还是调整默认。

## 问题(现状)

- **等级:Low(非 correctness;认知与成本风险)**。
- `multi-module` 是**一个可部署单元**(`start`),内含 ordering/inventory/payment 三个 BC,聚合为内存实现。
  本次把跨 BC 集成事件的传输设为方式三(broker+outbox)。实测一次下单产生 8 条集成事件,**全部**
  `outbox → Kafka → inbox → 进程内消费桥重投`,而生产者与消费者在**同一 JVM**。
- `decision-00006` 选型表按部署形态选传输:单进程→方式一,模块化单体→**方式二**,独立部署多服务→方式三。
  当前拓扑对应方式二,却用了方式三。

## 根因(第一性)

1. **观察 vs 期望**:期望"传输按部署拓扑选择";实际"传输由'把所有组件都用上'这个目标反向决定"。
2. **最小机制**:目标是能力展示(用上 `messaging-kafka`),于是把三种合法传输里最重的一种装到了最轻的拓扑上。
3. **风险不在 correctness,而在**:(a) **认知**——容易固化"跨 BC ⟹ 必须 Kafka"的错误等式,而 `decision-00006`
   的立场恰是"跨 BC ⟹ 集成事件,但集成事件 ≠ 必须 broker";(b) **延迟**——两个独立轮询器(PM effect relay +
   outbox relay)叠加 Kafka,实测下单到确认 ~6s;(c) **成本**——为纯本地流付了 at-least-once + 强制 inbox 去重 +
   运维 broker 的代价,而此拓扑用不上其换来的运行时/部署解耦。
4. **注意**:方式三**并非跑不起来或错误**——消费桥让本地消费者从 broker 读回,拓扑被完整覆盖;这只是"三个
   合法选项里最重的一个"。

## 复现

n/a(观察性:实测 8 事件全绕 broker、端到端 ~6s)。

## 修复/建议(非代码修复)

二选一,均可:
1. **接受并文档化**:在 [[design-00001-aipersimmon-ddd-and-scaffold]] 或 [[plan-00006-middleware-integration]] 明确
   "本示例的方式三是**能力演示 / broker-ready 预备拆分**,不是该拓扑的推荐架构;真实单体默认应为方式二"。附上
   `decision-00006` 的选型信号,破除"跨 BC ⟹ Kafka"的等式。
2. **调整默认为方式二**(in-process async + outbox),把方式三留作"拆分服务时切换"的演示分支(切换只改依赖/属性,
   不改业务代码——正是 port 三传输可切换的设计红利)。

无论选哪个,都应与 [[issue-00027-outbox-atomicity-broken-by-in-memory-aggregate]] 一起看:方式二/三的 outbox 价值都以
"聚合落同一事务库"为前提。

## 关联

- [[plan-00006-middleware-integration]]
- [[decision-00006-integration-event-transport-selection]](选型信号表)
- [[issue-00027-outbox-atomicity-broken-by-in-memory-aggregate]]
