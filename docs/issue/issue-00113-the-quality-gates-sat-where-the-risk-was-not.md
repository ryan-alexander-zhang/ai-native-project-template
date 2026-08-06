---
id: issue-00113-the-quality-gates-sat-where-the-risk-was-not
type: issue
status: resolved
blocks: [report-00003-ddd-library-review-2026-07-29]
---

# 覆盖率门禁的分布与风险的分布正好相反

## 症状

JaCoCo 90% + PIT 90% 只装在 6 个纯净层模块上——那里几乎全是 record 与值类型。
而两个 engine：

| 模块 | 主代码行数 | 测试文件 | 门禁 |
|---|---|---|---|
| `-outbox-engine` | 1611 | **0** | 无 |
| `-process-manager-engine` | 5016 | **0** | 无 |

这两个模块恰恰是**每一条判断的所在地**——按聚合顺序、重试预算、什么时候放弃、
"记账失败不是投递失败"、死信搬移失败怎么办——而且一旦判断错了，代价是**消息丢失或重复**。
门禁装在最不需要它的地方。

顺带一条相关的：库自称"契约模块无框架依赖"，但**没有任何东西检查过这句话**。
`LayeringRules.domainShouldBeFrameworkFree()` 按包名段 `..domain..` 匹配（为的是服务消费方的布局），
而库自己的契约模块没有这个段。

## 落地（一半完成，另一半明确留在门外）

### `-outbox-engine`：内存 store + 39 个测试 + 门禁

新增 `InMemoryOutboxStore`（测试树）。**它不是"要什么给什么"的桩**：`claimDue` 按端口 javadoc
逐条实现，**包括队头谓词与租约检查**——一个真 store 不会认可的替身，只会让测试之间互相同意、
与现实无关。

它**刻意不替代**两个 SQL 实现：那两个是否遵守同一份契约是 SQL 的问题，由它们自己对着真实数据库回答。
这一个存在，是为了让端口**之上**的判断以其本来面目被测试——与后端无关的纯逻辑。

门禁：运行期包 JaCoCo 90% line / 80% branch + PIT。`autoconfigure` 包**排除在外**并写明理由——
它是 Spring 装配，唯一有意义的测试是"上下文真的起来了"，而两个存储后端各自已经启动了一个；
在这个刻意不带 Spring 上下文的模块里重造一个，只为重证后端已对着真库证过的事。

**PIT 立刻就赚回了成本**：第一次跑就找出**三条行覆盖率称为"已覆盖"的未测路径**，其中一条
（`beginDispatch` 在传输接手之前就抛异常）是第 10 项**几天前刚写下的**。另外两条是
`span.detach()` 无人断言、`OutboxRelayScheduler` 完全没测。补完这三条 + 批大小边界 + 死信记录内容，
mutation 从 78% → 86%，no-coverage 从 9 → 0。

**PIT 阈值定 85 而不是 90，且写进 pom 说明为什么**：剩下活着的变异体是
`if (deleted > 0)` 这种日志守卫、喂给指标钩子的延迟算术、以及"变异后换条路径失败但落在同一个地方"的
私有 helper 返回值。为杀它们写的断言会把数字抬上去而**什么也不保护**。数字说实话，比数字好看重要。

### `-process-manager-engine`：50 个测试 + 门禁

第一轮（纯函数与聚合读，只需一个时钟和桩）：重试排期（`ExponentialBackoffPolicy`）、
积压读（`ProcessBacklog`）、持久化约定（`ParkedInputs` / `Payloads`）。

**第二轮补上了 store 支撑的那一半**：三个内存 store（instance / effect / deadline）+
`ProcessEffectRelay`（86% line）与 `ProcessDeadlineWorker`（97% line）上门禁。
与 outbox 那份同判据：**每一条完成态写入都被租约令牌栅住**、匹配不上就返回 0，
与 SQL 的 `WHERE lease_token = ?` 一致；**`attempts` 由失败增加、绝不由 claim 增加**，
所以慢 worker 的重领不会烧掉它从未用过的预算。

覆盖的行为：操作员 cancel 对**已在飞**的效果同样生效（不只是"取消还没被领走的"）；
被重排的定时器不会连同它取代的那一代一起触发；claim 与 fire 之间被取消的定时器成为可审计的 no-op；
派发期间绑定的是行上那个租户（relay 线程本身什么都没绑）；
效果/定时器在**已终态**实例上耗尽重试时不去挂起它。

**仍在门外**：`runtime`（`DefaultProcessRuntime` 611 行）/ `replay` / `operation` / `autoconfigure`。
`runtime` 需要第四个内存 store（transition）与幂等 claim 状态机，是下一块。

> **后续（`issue-00117`）**：`runtime` 那一块已补完——第四个内存 store 落地，
> `runtime` 0% → 100% line / 98.2% branch 并入门禁。那里还多学到一件事：
> **能被回滚抹掉的冲突不是冲突**，所以两个 double 各加了一个"另一个已提交事务写下的"口子。
> 现在门外只剩 `replay` / `operation` / `autoconfigure`。
>
> **再后续（`issue-00118`）**：`replay` 与 `operation` 也补完了（各 100% line）——
> 那两个正是第 4 项为修 critical #4/#5 写下的代码。门外只剩 `autoconfigure`。

### 契约模块无框架：按字节码查

新增 `ContractModulesCarryNoFrameworkTest`（在 `-archunit` 测试树，不随包发布）：
把 11 个契约模块作为 **test scope** 依赖挂上来，用 ArchUnit 读**字节码**——
pom 说的是"声明了什么"，字节码说的是"实际够到了什么"，而落到消费方 classpath 上的是后者。
（读 classpath 而不是读 `target/` 目录：reactor 的依赖顺序保证它们已经构建，按路径读则不保证。）

**用白名单而非禁用名单**：新加的依赖无论有没有人事先想到禁它，都会让构建失败。
当前白名单只有两条，各自附理由：
- `org.slf4j` —— 日志**门面**，其全部意义就是不把实现强加给依赖它的人；
- `com.fasterxml.jackson.core` —— 只有一个异常类型 `JsonProcessingException`，
  outbox 默认分类器判它为永久失败。按**类型**而非按名字匹配，正是为了不误伤同名类——
  这个错本库犯过（`issue-00102` 的 `BeanValidationFailures`）。

第二条断言防"空规则恒真"：任何一个契约模块若不在被检查的 classpath 上，构建失败——
否则从 pom 里删掉一个依赖就会悄悄停止检查它，与最初让这些模块无人检查的是同一种静默收缩。

**负向对照**：把 `org.slf4j` 从白名单移除，规则立刻点名三处 `LoggingOutboxDispatcher` 的引用——
证明它确实在看字节码，而不是在空集上恒真。

## 最有价值的发现：内存 UoW **必须**会回滚

第一版 `DirectUnitOfWork` 是直通的，结果两个 deadline 测试失败——**看起来像 engine 的 bug，实际不是**。

`ProcessDeadlineWorker` 在跑 advance **之前**就 `markFired` 并清空租约（这是刻意的：
否则 advance 若使流程终结，终态决策会取消所有活着的定时器，把一个**确实触发过**的定时器改写成 CANCELLED）。
而它的重试路径要求行回到 `IN_FLIGHT` 且租约仍在自己手里——真实 SQL 写的是
`WHERE lease_token = ? AND status = 'IN_FLIGHT'`（已回读 `JdbcProcessDeadlineStore` 核实）。
**两者能对上，只因为抛异常把那次 markFired 回滚掉了。**

所以内存 store 加了 `Snapshottable`，UoW 换成 `RollingBackUnitOfWork`（内层 execute 加入外层事务，
对应 `REQUIRED`）。直通的替身会让这条路径**看起来是坏的**；而反过来——一个悄悄丢弃写入的替身——
会让**真正的原子性 bug 看起来是好的**。后者更危险。

现有一条测试专门钉住这件事，因为它在任何**单个**文件里都看不出来。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（§2 架构层「4567 行零直接测试」那条、§3 第 12 项）
- PIT 找出的三条未测路径中，有一条是第 10 项刚引入的：
  [[issue-00111-the-relay-waited-for-each-send-in-turn]]
- 内存 store 的"不替代 SQL 实现"边界，与端口 javadoc 里那条"没消除的重复"同源：
  [[decision-00020-outbox-engine-over-one-store-port]]
- 按类型而非按名字匹配异常的教训：[[issue-00102-failed-operations-are-not-recorded-under-an-outer-transaction]]
- 门禁形状的既有先例：[[design-00007-code-quality-gates]]
