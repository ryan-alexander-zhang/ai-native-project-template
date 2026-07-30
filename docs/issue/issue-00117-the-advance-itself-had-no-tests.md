---
id: issue-00117-the-advance-itself-had-no-tests
type: issue
role: main
status: resolved
parent: report-00003-ddd-library-review-2026-07-29
---

# 决定每一次流转的那个类，一条测试都没有

## 起点：`issue-00113` 自己留下的那一块

第 12 项补完了 relay 与 deadline worker，并把 `runtime` 明确留在门外，写着
"需要第四个内存 store（transition）与幂等 claim 状态机，是下一块"。这就是那一块。

`DefaultProcessRuntime` 611 行，`ProcessOutcomeWriter` 224 行，`DefaultProcessQuery` 139 行——
**流程管理器里每一次状态推进都要过的那条路**，行覆盖率 **0%**。

## 它承诺两件事，两件都需要一个会拒绝的 store

1. **一个输入只生效一次**——靠 `UNIQUE(instance_id, input_message_id)`；
2. **一次决策要么整体落地要么整体不落地**——快照、转移日志、暂存效果、定时器，同一个事务。

对着"要什么给什么"的桩，这两条**都测不出来**：桩永远接受第二次 append，
桩永远接受一次乐观锁更新，于是"幂等"与"原子"在测试里成立而在生产里不成立。

所以新增的 `InMemoryProcessTransitionStore` 保留两处**拒绝**：

- **去重键**：同一个 `(instance_id, input_message_id)` 第二次 append 抛 `ConcurrentTransitionException`，
  和唯一索引冲突时真 store 做的事一样；
- **每实例序号**：`transition_seq` 按实例单调分配，而不是借用 map 的插入顺序——
  时间线、"最新一条转移"、停泊输入的重放队列**全都按它读**，
  借用插入顺序会在两个实例交错时开始说谎。

`replayed_at` 是转移行唯一一个插入后还会被写的字段，所以也是这里唯一可变的字段。

## 最关键的一处：能被回滚抹掉的冲突，不是冲突

要测"输了竞争会怎样"，得先能**造出**一个竞争。而内存 store 的回滚会把两边的写一起抹掉：
赢家的行随输家的行一起消失，重试于是读回自己出发时的那个状态，**顺顺利利地成功了**。
乐观锁看起来被跑过，实际从没被顶撞过——这正是那种"绿着的空测试"。

所以两个 double 各加一个口子，语义就是它字面的意思：

| | |
|---|---|
| `InMemoryProcessTransitionStore.committedElsewhere` | 另一个**已提交**事务写下的转移行，本事务的回滚不动它 |
| `InMemoryProcessInstanceStore.advancedElsewhere` | 另一个**已提交**事务推进过的修订号，本事务的回滚不动它 |

**能在回滚里活下来的，恰好就是别人已经提交的东西**——这不是为测试开的后门，这是事务的定义。

## 负向对照

把两处拒绝各自改成恒真通过，**四条测试立刻变红**：

```
aConflictInsideSomebodyElsesTransactionIsNotRetried      期望抛出，什么都没抛
aConflictThatOutlastsTheRetryBudgetIsRaised…             期望抛出，什么都没抛
anAdvanceThatLosesTheRevisionRaceIsRetriedAgainst…       期望 react 2 次，实际 1 次
anAdvanceWhoseInputWasRecordedByTheWinnerFoldsIntoIt     期望 duplicate=true，实际 false
```

**顺带一条值得记下的**：普通的"同一个输入送两遍"测试在拒绝被移除后**仍然绿**——
而且这是对的。runtime 先做一次 `findTransitionIdByInput` **读**，正常路径在那里就短路了；
唯一键是**竞争路径**的兜底，不是正常路径的机制。两者各管一段，负向对照恰好把这条边界照出来了。

## 覆盖的行为（runtime 32 + query 9 = 41 条）

**start**：首次写入的三样东西与效果 id 的推导（`transitionId#index`，重投时身份不变）；
同 messageId 重复 start 是 no-op 且**决策不会被重取**；同业务键不同 messageId 在 REJECT 下拒绝、
在 FOLD 下折叠；**另一个租户的同名业务键不构成阻碍**（键是 `(tenant, type, businessKey)`，
不带租户的查询不只是会错误拒绝——它会**加载并锁住**别人的实例）；
max-lifetime 兜底定时器的三种情形（配了就armed / 定义自己动了保留名就让位 / 起手即终态就不 arm）；
终态决策仍然 schedule 定时器时**报错而不是静默丢弃**（丢掉了就长得像"超时从没来过"）。

**handle**：推进、重复输入 no-op（且暂存的命令不会被发第二次）、实例不存在、
ref 指向别的流程、非法生命周期迁移、**运行中实例钉住自己那一版定义**（v2 上线不会把在途实例改道）、
payload 超限。

**挂起态**：输入被**停泊**成审计转移而不是弹回消息层；
重放输给了新一轮挂起时**不会二次停泊**（否则既重复了那笔仍然欠着的债，
又开出一条 `parked:parked:…` 的链，最终撑爆 id 列）；重放却找不到停泊行时报错。

**冲突**：输掉修订号竞争 → 对着赢家的状态重取决策；输入已被赢家记下 → 折叠为 duplicate；
**在别人的事务里不重试**（第一次失败已经把那个共享事务判了死刑，
第二次只会再失败一次，并且用一个新冲突顶掉原始原因）；预算耗尽则抛出。

**组合**：加入调用方事务的推进，会随调用方的失败一起回滚——快照、转移、暂存效果，一起。

**读侧（`DefaultProcessQuery`）**：其中两条是承重的而非便利——按业务键解析 ref **只在绑定的租户内**
（业务键是租户相对的，不带租户的读会让一个租户寻址并推进另一个租户的实例），
以及 ref 命中了真实 instanceId 但流程类型/业务键对不上时**拒绝而不是回答另一个实例**。

## 一处 JaCoCo 的性质，值得记下

`withRetry` 里那句"在别人事务里就直接跑一次"曾显示**未覆盖**，尽管测试确实走过它。
原因是 JaCoCo 的探针在指令**之后**：一条一路抛到底的路径，沿途探针一个都不会触发。
于是补了一条**正常返回**的组合测试（上一节最后那条），既盖住了那行，也把那条组合性质钉住了——
比为了数字去改断言强。

## 门禁

`runtime` 从 0% 升到 **100% line / 98.2% branch**，并入 JaCoCo 门禁。

**按名字排除一个类**：`SpringTxProcessUnitOfWork` 六行，全是转发给 Spring 的 `TransactionTemplate`。
对着 mock 的 `PlatformTransactionManager` 写的测试，断言的是那个 mock，不是 Spring 真正做的传播。
它的行为在真的地方被覆盖——脚手架对 PostgreSQL/MySQL 的端到端测试。

**仍在门外**：`replay`（停泊输入的排空）/ `operation`（操作员门面）/ `autoconfigure`（Spring 装配）。
前两个现在**四个 store double 都齐了**，是下一块；`autoconfigure` 要的是 Spring 上下文测试，
和这些不是同一种测试。写在 pom 里，而不是把全模块阈值调低到能过——后者读起来像覆盖率，实际是缺口。

> **后续（[[issue-00118-the-recovery-paths-had-no-tests]]）**：`replay` 与 `operation` 已补完，
> 门外只剩 `autoconfigure`。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（§2 架构层「4567 行零直接测试」那条）
- 本项承接第 12 项自己留下的那一块，三个 store double 与会回滚的 UoW 都来自那里：
  [[issue-00113-the-quality-gates-sat-where-the-risk-was-not]]
- 停泊/重放的持久化约定：[[design-00004-durable-process-manager-runtime]]
- 门禁形状的既有先例：[[design-00007-code-quality-gates]]
