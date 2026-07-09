---
id: analysis-00007-saga-process-manager
type: analysis
role: main
status: active
parent:
---

# 跨聚合长流程怎么封装：Saga / Process Manager 的构件取舍

承接 [[analysis-00006-ddd-building-blocks-library]]：`aipersimmon-ddd-*` 要不要、以什么形态
提供 **saga / process-manager** 抽象?本文回答三件事——(1) choreography 与 orchestration 的选型轴；
(2) 构件库该提供到什么程度；(3) 何时该从"手写事件编排"升级到 **durable-execution 引擎**(Temporal 等)。

配套阅读：[[analysis-00005-structure-2-event-flow-and-cqrs]] §8（s2 **这一条具体** saga 的实现——
choreography + 超时补偿,已闭环）、[[analysis-00006-ddd-building-blocks-library]]（构件库方法论：
纯/脏分离、拓扑无关、参考不依赖）、[[analysis-00002-domain-vs-integration-events]]（跨 BC 用集成事件）、
[[analysis-00001-domain-event-publishing]]（事件发布/outbox）。

## 结论先行

> **默认 choreography(事件编排);把 orchestration 做成一个可选的轻量 `aipersimmon-ddd-saga` 抽象;
> 复杂/长时流程再外接 durable-execution 引擎。三档之间靠"引擎无关的 saga 契约"平滑升级。**

- **默认档 = choreography**：事件 + outbox + 超时补偿,即 s2 现状(analysis-00005 §8)。monolith-first 下够用,**不强制上 saga**。
- **可选档 = 轻量 `aipersimmon-ddd-saga`**：跨 BC 步骤变多、流程需要显式可观测时启用。持久化 saga 状态 + 关联 id +
  deadline + 补偿钩子。**参考 Axon Saga 的形态,但不依赖它**(与 00006 "参考 jMolecules 不依赖"一致)。
- **逃生舱 = durable-execution 引擎**：长时(小时/天)、人工审批、需可视化编排时,升级到 Temporal / Camunda 等;
  库只做**薄适配**,不自己长成迷你工作流引擎。
- **判词不是"哪个更好",而是"简单流程 choreography、复杂长流程 orchestration"**——这也是近年大厂共识(§四)。

## 一、定位:与 00005 §8 / 00006 的边界

- analysis-00005 §8 讲的是 s2 **那一条** saga(`OrderPlaced→预留→StockResult→confirm/cancel`)**怎么实现**,已闭环。
- **本文讲构件库**:这类长流程的**通用抽象**该不该进 `aipersimmon-ddd-*`、以什么形态进。方法论完全承接 00006。

## 二、决策轴一:choreography vs orchestration

| | Choreography(事件编排,去中心) | Orchestration(过程管理器,中心协调) |
| --- | --- | --- |
| 谁推进流程 | 各 BC 听事件**自行**反应 | 一个 **process manager** 持状态,显式发命令、等回复 |
| 流程可见性 | **隐式**——散在各 listener,"走到哪/共几步"难看清 | **显式**——一处状态机,可观测、易改 |
| 耦合 | 低(只认事件) | 协调者认命令/回复,略高 |
| 代表 | s2 现状;Spring Modulith event choreography | Axon `@Saga`;Vernon IDDD 过程管理器 |
| 适用 | 步骤少、线性、无长时等待 | 步骤多、分支/补偿复杂、需超时与可观测 |

**s2 现状复盘(choreography 的代价)**:analysis-00005 §8 已如实标注两处残留边界——多行订单不按行精确释放、
`OrderCancelled` 抢先于 `OrderPlaced` 的跨主题竞态。这正是 choreography 的典型症状:**流程无中心状态,
边界情形只能各 listener 各自兜**。步骤一多就难维护——这就是升级 orchestration 的动机。

## 三、决策轴二:构件库提供到什么程度(三档)

| 档 | 提供物 | 何时用 | 出处 |
| --- | --- | --- | --- |
| **0 不提供** | 只靠 `-events` + `-outbox` + 定时超时扫描 | 线性短流程(s2 现状) | Richardson *Saga*(choreography 变体) |
| **1 轻量 saga(本文采纳)** | `aipersimmon-ddd-saga`(纯契约)+ `-saga-spring`(实现) | 跨 BC 步骤增多、需显式流程/超时/补偿 | Axon `@Saga`；Vernon 过程管理器 |
| **2 外接引擎** | 只做 Temporal/Camunda 薄适配层 | 长时(小时/天)、人工节点、可视化编排 | Temporal / Camunda 8(§四) |

**为什么不默认给档 1?** 命令总线在 00006 已定为可选(YAGNI);saga 同理——`domain-driven-hexagon`、
`ddd-by-examples/library` 都无 saga 也成立。**默认档 0,档 1 作为可选构件按需引入。**

## 四、大厂最近走向:从手写 choreography 到 durable execution

明显趋势:**复杂长流程正从"手写事件编排"转向"orchestration + durable execution"**——由引擎替你持久化
流程状态、重试、定时器与补偿,正好消掉 s2 那些"残留边界":

- **Temporal**(脱胎自 Uber **Cadence**)—— workflow-as-code,状态/超时/重试/补偿由引擎持久化;内置 **SAGA** 补偿助手。近年采用度最高的 durable-execution 引擎。
- **Netflix Conductor**(现由 **Orkes** 维护)、**AWS Step Functions** —— 托管/自托管编排引擎。
- **Camunda 8 / Zeebe** —— BPMN 可视化编排,云原生。
- **Restate、DBOS**(近两年新入场)—— 轻量 durable-execution,降低引擎门槛。
- **思想领袖判词**:Bernd Rücker(Camunda)反复点名 choreography 的 **"where is the process?"** 问题,
  主张复杂长流程用 orchestration;Chris Richardson 的 *Saga* 模式则并列 choreography / orchestration 两种变体。
  **共识是分场景,不是二选一。**

**对本模板的启示**:monolith-first 项目**默认不该**一上来引 Temporal(运维 + lock-in 成本高;
`docs/reference/axon-framework/` 对"全套框架带来的 ES/token store/运维负担"已明确 caveat)。
但要把 saga 契约做成**引擎无关**,以便日后平滑换引擎——这正是档 1 的价值:一个**可被替换的协调抽象**。

## 五、参考 Axon Saga:借形态,不依赖

沿用 00006 的取舍(`docs/reference/axon-framework/` §"Sagas / process managers"):

| Axon 提供 | `aipersimmon-ddd-saga` 对应 | 重写成本 |
| --- | --- | --- |
| `@Saga`(标注长流程协调者) | `ProcessManager` 标记 + `SagaState` | **低** |
| `@SagaEventHandler(associationProperty)` 按关联属性路由到实例 | 按 **correlation id**(如 orderId)关联 + 路由 | **中** |
| `@StartSaga` / `@EndSaga` / `SagaLifecycle.end()` | `start()` / `end()` 生命周期 | **低** |
| `DeadlineManager` / `EventScheduler`(超时 + 补偿) | `Deadline` 抽象 + `-saga-spring` 调度实现 | **中** |
| Axon Server / token store / event sourcing | **放弃**——不做 ES、不做 token store,落在关系库 | 放弃 |

> 一句话:借"标记 + 关联路由 + 生命周期 + deadline"这四样**形态**,放弃 ES/Server 那套重运维基建。

## 六、提议模块:`aipersimmon-ddd-saga` / `-saga-spring`(纯/脏分离)

严守 00006 的"纯契约 vs 可插拔实现"。两者都**可选**。

### `aipersimmon-ddd-saga`(纯契约,application 层,可选)

- 依赖:`-core`(+ 可选 `-cqrs` 以复用 `Command`)。**framework-free。**
- 内容：`ProcessManager` / `Saga` 标记；`SagaState`(持 correlation id、当前步/状态、流程数据)；
  关联路由约定(按 correlation id)；`Deadline`(登记一个"到点回调");补偿钩子 / 步骤模型；
  生命周期 `start / on(event) / end`。

### `aipersimmon-ddd-saga-spring`(starter,infrastructure 层,可选)

- 依赖:`-saga` + Spring(+ `-cqrs-spring` / `-outbox-*` / `-events-spring`)。
- 内容：JPA `SagaStore`(持久化 saga 实例,含乐观锁防并发推进);`TaskScheduler` / `@Scheduled` 支撑的
  **DeadlineManager**(承接 s2 的 `PendingOrderTimeoutScanner` 思路,升级为通用超时);
  经 `-cqrs` 的 `CommandBus` 发命令、经 `-outbox` 可靠外发(与 analysis-00001/00005 的 outbox 语义一致)。

> 归属小结:saga **契约**在 `-saga`(纯、可选);saga **状态存储 + deadline 调度 + 命令派发**在 `-saga-spring`
> (脏、可选);具体某条 saga 的业务规则在各 BC 的 application 层。**引擎升级只换实现,不换契约。**

## 七、升级判据:choreography → 轻量 saga → durable execution

| 信号 | 停在 choreography(档 0) | 升级轻量 saga(档 1) | 外接引擎(档 2) |
| --- | --- | --- | --- |
| 跨 BC 步骤数 | ≤ 2~3、线性 | 增多、有分支/补偿 | 很多、复杂拓扑 |
| 等待时长 | 秒级(定时扫描够) | 分钟级 | **小时/天级**(需持久定时器) |
| 人工节点/审批 | 无 | 少量 | 有,且需 SLA |
| 流程可视化需求 | 无 | 代码级状态机够 | **需业务方看 BPMN** |
| 补偿复杂度 | 单步幂等释放 | 多步、有序补偿 | 复杂补偿 + 重试策略 |
| 运维承受力 | 只有关系库 | 关系库 + 调度 | 能运维/托管引擎 |

**原则:按信号逐级升级,不跳级;契约保持引擎无关,让升级是"换实现"而非"重写流程"。**

## 八、落地建议

1. **默认不动 s2**:choreography + 超时补偿(analysis-00005 §8)保持为默认参考实现。
2. **档 1 作为可选构件**:先出 `aipersimmon-ddd-saga` 纯契约 + `-saga-spring`;把 s2 的
   `PendingOrderTimeoutScanner` 思路抽象成通用 `DeadlineManager` 作为第一个落地验证。
3. **脚手架**:saga 模块默认**不引入**;文档给出"出现§七信号时如何加 `-saga`,以及何时该外接引擎"。
4. **引擎适配留接口不留实现**:档 2 只在契约上预留"协调者可替换",不在模板里内置 Temporal 依赖(避免 lock-in)。

## Sources

内部(蒸馏笔记 `docs/reference/` 与既有分析):

- [[analysis-00005-structure-2-event-flow-and-cqrs]] §8 —— s2 choreography saga + 超时补偿(已闭环)与残留边界。
- [[analysis-00006-ddd-building-blocks-library]] —— 构件库纯/脏分离、参考不依赖方法论。
- [[analysis-00001-domain-event-publishing]] —— 事件发布/outbox(saga 可靠推进的底座)。
- `docs/reference/axon-framework/` —— `@Saga` / `@SagaEventHandler` / `DeadlineManager`;及"全套框架运维负担"caveat。

外部(大厂 / 权威):

- Chris Richardson, microservices.io —— *Saga pattern*(choreography vs orchestration 两变体、补偿事务)。https://microservices.io/patterns/data/saga.html
- Hector Garcia-Molina & Kenneth Salem, *Sagas*, ACM SIGMOD 1987 —— saga / 补偿事务理论源头。
- Vaughn Vernon, *Implementing Domain-Driven Design* —— 过程管理器(process manager)与长流程建模。
- Temporal —— durable execution / workflow-as-code,内置 SAGA 补偿(源自 Uber Cadence）。https://temporal.io · https://docs.temporal.io/encyclopedia/what-is-temporal
- Netflix Conductor / Orkes —— 编排引擎。https://conductor-oss.org
- AWS Step Functions —— 托管编排。https://docs.aws.amazon.com/step-functions/
- Camunda 8 / Zeebe —— BPMN 可视化编排,云原生。https://camunda.com
- Bernd Rücker, *Practical Process Automation*(O'Reilly)及其文章 —— choreography vs orchestration、"where is the process?"。https://processautomationbook.com
