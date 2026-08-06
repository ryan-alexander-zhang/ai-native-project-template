---
id: analysis-00032-samples-eventual-consistency-process-manager
type: analysis
status: draft
informs: [analysis-00014-ddd-samples-scenario-catalog]
---

# S9 最终一致性：process-manager 编排多步流程与补偿

对应 sample：`aipersimmon-ddd-samples/s09-eventual-consistency-process-manager`（单模块，三个聚合，一个流程）。
场景清单见 [[analysis-00014-ddd-samples-scenario-catalog]]。

## 0. 本篇定位

一个业务目标要动三次存储：座位计数、客户余额、订单本身。没有任何事务能一次盖住三者，也没有任何顺序能让失败
不可能发生。所以流程被写成显式的、落库的、可恢复的——并且**用补偿而不是回滚**收敛。

业务域是"卖一张票"：占座 → 扣余额 → 出票。三个聚合都不知道自己在被编排。

## 1. 编排还是协同

本篇选编排（process manager 主动发命令，参与者把结果报回来），理由是一句可检验的话：**补偿有顺序**——
先退钱、再放座、最后取消订单——而**有顺序的东西必须有个地方住**。协同（各服务各自订阅事件）没有那个地方，
顺序就成了八个 handler 互相调用所涌现出来的性质。

代价也不含糊，在 `TicketingProcess` 的 javadoc 里写着：参与者的 handler 因此知道"存在一个协调者"。
换来的是流程是**一个能从头读到尾的对象**，以及"订单 42 卡在哪"是一次查询而不是一场考古。

## 2. 一个流程定义由什么构成

| 组成 | 本篇 | 库的类型 |
| --- | --- | --- |
| 步骤 | 6 个（3 正向 + 3 补偿）+ 2 个终态 | `TicketingState.Step` + `HasStep` |
| 触发输入 | 12 个 sealed record | `ProcessInput` |
| 超时 | 2 个命名 deadline | `ScheduleDeadline` / `CancelDeadline` |
| 补偿动作 | 3 条命令 | `DispatchCommand` |
| 决策 | 纯函数 | `ProcessDefinition.start/react` → `ProcessDecision` |

**定义是纯的**：没有仓储、没有总线、没有时钟、没有 HTTP。这是库的硬要求，回报是
`TicketingDefinitionTest` —— 7 个用例，不用数据库、不用 Spring、毫秒级，还能把流程摆到任何状态。
代价是它**不能作弊**：后面步骤需要的东西必须由 state 带着走（放座要 seatClass，退钱要 customerId + amount +
debitReference），因为没有任何东西可以去查。

一条 ArchUnit 规则钉住这个纯度（`..application.fulfilment..` 不得依赖 domain / infrastructure / jdbc /
runtime），因为运行期没有任何东西拦得住"就查一次订单"这种一行改动。

## 3. 补偿不是回滚——用账本证明

sample 的中心展品是钱包的分录表。退款不是删掉那笔扣款，而是 `credit`：**另一条分录**，有自己的
reference、自己的方向、自己的理由，两条都永久留在流水上。测试直接断言这一点：

```
DEBIT  4500  ticket-debit:<orderId>
CREDIT 4500  refund-of:ticket-debit:<orderId>
```

三个步骤的补偿语义各不相同，且都不是"撤销"：

| 做过什么 | 拿什么补 | 为什么不是撤销 |
| --- | --- | --- |
| 扣了余额 | 一条 credit，引用那笔 debit | 两条分录永远都在 |
| 占了座 | hold 行标记 released | 行保留，带释放时间 |
| 下了单 | 订单取消，带原因 | 取消是业务事实，不是"不存在" |

座位那条最省事也最能说明问题：`released_at` 而不是 `DELETE`，所以计数回到原样而历史没有被改写。

**并且存在不可回头点。** 出票之后流程结束：此后到达的取消请求被 `ignored()`，因为退掉一张票是另一个有自己
授权规则的退款流程，不是这个流程的补偿。聚合也大声同意（`TicketOrder.cancel` 在 TICKETED 之后抛异常）。
实测还发现库比我的代码更强：**终态实例被 runtime 直接短路**（`DefaultProcessRuntime:510` 返回上一条
transition 当作 duplicate），定义连问都不会被问到——所以那条 `ignored()` 分支在生产路径上其实不可达，
它存在只因为 Step 的 switch 是穷尽的。这一点最初我写反了（断言"runtime 仍会记一条 transition"），
被测试打红后按代码更正。

## 4. 谁持有真相（本篇最容易被跳过的一问）

**订单聚合持有真相；流程的 step 只是"我在等什么"。**

- `TicketOrder.status` ∈ {PLACED, TICKETED, CANCELLED}——客户看到的、报表统计的、别的上下文会被告知的。
- `TicketingState.Step` ∈ {AWAITING_SEAT, …}——关于协调本身的事实，不是关于订单的。

两者**按设计**会不一致：流程到了 AWAITING_TICKET 而订单还是 PLACED，因为命令已发出、尚未提交。
`thestepsHappenOneAtATimeAndInOrder` 断言的就是这个窗口，并称之为正确。

真正的缺陷是流程存一份 status 的拷贝。所以有一条**结构性**测试：反射
`TicketingState.getRecordComponents()`，断言没有 `OrderStatus` 类型的分量、也没有叫 `status` 的分量。

那为什么流程可以存 customerId / seatClass / amountMinor？判据一句话：**流程可以记事实，不可以记结论**——
这三个是本实例开始时就固定的不可变事实，没有任何东西能在背后改动它们；status 是随时可能变的结论。

顺带一个库的守卫值得记：`HasStep` 让 step 只在 state 里写一次，`ProcessDecision` 的构造器再校验显式传入的
step 与 state 自己的一致——因为 step 是列、state 是 blob，两者一旦分叉，下游没有任何东西能把它们合回去。

## 5. 每一步的幂等，以及为什么本篇不需要 inbox

两层，各管一件事：

1. **参与者幂等**：`SeatClass.hold` 认出已有的 hold 返回 `ALREADY_HELD`；`Wallet.debit` 认出 reference 返回
   `ALREADY_APPLIED`。三个聚合的每个操作都返回**结果**而不是抛异常，因为业务上的"不行"（售罄、余额不足）
   是协调者要据以补偿的答案，抛出去只会被 relay 当失败重试。
2. **runtime 输入幂等**：命令 effect 以 effect 自己持久化的 message id 投递（`CommandBus.sendAs`），
   handler 把同一个 `CommandContext` 原样交回来当 cause，于是重投产生**逐字节相同**的 input message id，
   runtime 认出来并返回原 transition。测试断言重复的 `SeatHeld` 只 stage 出一条 charge-wallet effect。

**这就是为什么本篇没有 inbox**：inbox 存在是为了去重**来自外部**的消息（S4 的课）；这些消息来自协调者自己
的 effect 表，那张表已经给每条消息发过身份。两者是同一个想法用在两个边界上。

## 6. 载荷/状态编解码为什么必须显式登记

`TicketingCodecs` 里 19 行注册。两个理由，第二个用负向对照量过：

**类名不是持久化契约。** 每条登记的是稳定的 logical type + version，Java 类只是当前载体。一个流程实例
很容易活过三次重构（它就是一行在等还没发生的事），反射类名会让每次改名变成一次静默的数据迁移。

**忘了登记不能等到最坏的时刻才发现。** 定义用 `declaredPayloads()` 声明自己的载荷类，启动校验器与 codec
注册表对账。实测（§8 控制 1）：

- 删掉一条 catalog 登记 + 保留声明 → **启动失败**，异常正文点名那个类；
- 删掉同一条 + 声明改空 → **启动成功，24 个用例里只有 1 个红**——就是那个恰好走到补偿分支的流程。
  没有声明时，坏掉的地方藏在你跑得最少的路径里。

## 7. 卡住之后运维怎么介入

`StuckFlowTest` 走完整条：订单指向一个不存在的座位等级 → 参与者抛异常 → `max-attempts=1` 下 effect 变
DEAD → 实例 SUSPENDED → 修数据 → `redriveEffect` → 实例恢复 RUNNING → 流程照常走完出票。
**订单不需要重下、也不需要人工对账**，这就是持久化协调者相对一串监听器的实际好处。

三件事值得记下来：

- **库只给三个运维动作**（redrive effect / redrive deadline / cancel instance），**没有** `setState` /
  `forceStep`。状态只能经由定义的决策改变。一个能被手工改状态的协调者，它的不变量就等于最后那个运维
  相信的东西。
- `redriveEffect` **只接受 DEAD 的 effect**——我最初拿它去模拟"重投一个已投递的 effect"，报
  `effect ... is not DEAD`。重投的语义因此改成在端口层驱动同一个输入两次（§5），那才是真正要证的性质。
- **诊断要看两行**：实例说"哪件事挂住了它"（`effect ... exhausted retries`），effect 行的 `last_error`
  才说"为什么"。而后者没有任何端口能给 → 已开
  [[issue-00164-no-port-tells-an-operator-why-an-instance-is-suspended]]。

## 8. 负向对照（五个，逐个单跑）

| # | 改动 | 实测 |
| --- | --- | --- |
| 1a | 删一条 codec 登记（保留 `declaredPayloads`） | 启动失败，异常点名 `TicketingInput$WalletRefunded` |
| 1b | 同上 + `declaredPayloads` 改空 | **启动成功**，仅 1 红（走到补偿分支的那个流程） |
| 2 | 去掉 `SeatClass.hold` 的 ALREADY_HELD 判断 | 恰好 1 红：一张票占了两个座 |
| 3 | 补偿顺序改成先放座再退钱 | 恰好 1 红——而且退款根本不会发生（流程直接走向取消） |
| 4 | `default -> ignored(...)` 改成抛异常 | 2 红（单测 + 集成），即重复事实会打断流程 |

## 9. 什么时候不该再用本库的 process-manager

判据不是步骤数，是这三条里中了任何一条：

- **需要人参与的等待**（审批、排队几天几周）：流程会长期占着实例行，而 deadline 只有一个 dueAt，
  没有日历、没有升级链、没有任务列表；
- **需要动态拓扑**（步骤数由数据决定、并行扇出再汇合）：`ProcessDecision` 一次只能表达一个步骤的转移，
  fan-out/join 得自己在 state 里手写计数器——那就是在库里重写一个工作流引擎；
- **需要跨语言/跨团队的可视化编排**：库没有流程图、没有版本迁移工具（只有"旧版本继续服务在跑的实例"）。

库自己的 javadoc 把替代方向写成 Temporal，本篇同意，并补一句判据：**当 state 里开始出现"计数器"和
"待办列表"时，就已经越界了**。

## 10. 没做的事

| | |
| --- | --- |
| `PublishIntegrationEvent` effect | 本篇的参与者都在同一进程，所以用 `DispatchCommand`。跨服务把这一种换成那一种，定义的其余部分不变——那需要 outbox + broker，是 S4 的课 |
| 定义版本共存 | `definitionVersion` / `activeForNewInstances` 默认成单版本；两版并存是库的能力，本篇没演示 |
| state schema 升级 | 同上：`StateSchemaVersion` + 新 codec 的路子只在文档里 |
| fan-out / join | 见 §9，本库不该做的形态 |
| 多租户下的流程 | 实例表有 tenant_id，S13 在 S4 里演示租户传播；两者正交 |
| 保留期清理 | `cleanup.enabled` 默认关，本篇没开——一张永远增长的 transition 表是真实成本，值得单独一篇 |
