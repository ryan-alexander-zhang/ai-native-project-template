---
id: issue-00118-the-recovery-paths-had-no-tests
type: issue
status: resolved
blocks: [report-00003-ddd-library-review-2026-07-29]
---

# 两条恢复路径，正是修 critical 的那些代码，一条测试都没有

## 起点

`issue-00117` 补完 `runtime` 后，pm-engine 门外还剩 `replay` / `operation` / `autoconfigure`。
这一项做掉前两个。

值得先说清楚它们是什么：

| | 行数 | 覆盖率 | 来历 |
|---|---|---|---|
| `replay/ParkedInputWorker` | 181 | **0%** | 第 4 项为修 **critical #4** 新写的（[issue-00103-parked-input-replay-is-not-crash-safe](issue-00103-parked-input-replay-is-not-crash-safe.md)） |
| `operation/ProcessOperations` | 213 | **0%** | 同一项改的操作员门面 |

也就是说，**"修好 critical 的那段代码"本身从未被任何测试执行过**，当时只有脚手架端到端的间接证明。

## 测的是恢复能不能扛住，不是happy path

`ParkedInputWorker` 的全部意义是那笔**债**——每一行停泊输入欠一次重放。所以断言集中在三条：

1. **债只在重放自己的事务提交之后才结清**。`markParkedReplayed` 刻意写在 `runtime.handle` **之外**：
   中间崩溃只是让输入留在队列里再来一次（runtime 以 duplicate no-op 应答），
   而**先记账再重放**会在崩溃时把输入彻底丢掉——正是 critical #4 的形状。
2. **按到达顺序结清**，失败停在原地而不是让下一条越过去。跳过失败那条继续，
   等于把 m-2 应用到 m-1 从未到达的状态上——那恰是停泊机制要防的重排。
3. **无法应用的重放不消费债**：runtime 答 SUSPENDED（它拒绝了二次停泊）时，
   输入根本没被消费，此处结清就是静默丢弃。

外加：重放身份是 `parked:<原id>`、causation 是原输入、correlation 沿用原链（为空则用重放 id 起一条）；
派发期间绑定行上那个租户（worker 线程什么都没绑）；消化不了的输入把实例挂起为
`suspensionSource=PARKED_INPUT` 并把失败原因截断到 512 写下；
一个实例卡住不影响别的实例（顺序是**每实例**的承诺）。

`ProcessOperations` 有两件绝不能做的事，各自成测：
**留下一个 SUSPENDED 但已无可 redrive 的实例**（就再没人回来看它了），
以及**在仍有 dead work 时把它 resume**（流程会从一个失败副作用从未产生的状态继续往下走）。
另外每个动作都留一条带 operator/reason 的审计转移，且**要么整体发生要么完全不发生**——
半途生效的 redrive 比被拒绝的更糟，因为它看起来成功了。

## 负向对照（三条，共 7 条测试变红）

| 破坏 | 变红 |
|---|---|
| 结清提前到重放之前 | **4** |
| resume 不再检查剩余 dead work | **2** |
| UoW 不再回滚 | **1** |

第三条最值得说：`redriveEffect` 的顺序是 `load → redrive(**已写入**) → findForUpdate → appendOperator`。
所以当 `findForUpdate` 落空（"effect without instance"）时，效果**已经**被写回 PENDING，
是回滚把它放回 DEAD 的。少了回滚，一次被拒绝的 redrive 会留下半个已生效的动作。

## 顺带更正了一条生产注释

`ParkedInputWorker.drain` 原注释写"suspended or ended again between the scan and now"，
但 `isActive()` **不排除 SUSPENDED**——扫描后才挂起的实例会一路走到重放，
由 runtime 答 SUSPENDED、`replay` 返回 false、债留在原地。

**这是对的，而且是有意的**：只有 runtime 知道输入到底有没有被应用，drain 这一层判断不了。
所以改的是注释而不是代码，并各补一条测试钉住两条路径（`BetweenTransactions` 这个测试用 UoW
让测试能站在两次提交之间——扫描与排空本来就是两个事务，那道缝隙正是第二次检查存在的理由）。

## 门禁

`replay` / `operation` 双双 **100% line**，并入 JaCoCo 门禁。pm-engine 现共 129 例。

**门外只剩 `autoconfigure`**：它是 Spring 装配，唯一有意义的测试是"上下文真的起来了"，
而两个存储后端已各自对着真库启动了一个；在这个**刻意不带 Spring 上下文**的模块里重造一个，
只为重证后端已经证过的事。理由写在 pom 里。

## 关联

- 父：[report-00003-ddd-library-review-2026-07-29](../report/report-00003-ddd-library-review-2026-07-29.md)（§3 第 12 项）
- 前一块（`runtime`），以及"能被回滚抹掉的冲突不是冲突"：[issue-00117-the-advance-itself-had-no-tests](issue-00117-the-advance-itself-had-no-tests.md)
- 门禁从"层"挪到"风险"的原始一项：[issue-00113-the-quality-gates-sat-where-the-risk-was-not](issue-00113-the-quality-gates-sat-where-the-risk-was-not.md)
- 被测代码的来历，critical #4：[issue-00103-parked-input-replay-is-not-crash-safe](issue-00103-parked-input-replay-is-not-crash-safe.md)
- 门禁形状的既有先例：[design-00007-code-quality-gates](../design/design-00007-code-quality-gates.md)
