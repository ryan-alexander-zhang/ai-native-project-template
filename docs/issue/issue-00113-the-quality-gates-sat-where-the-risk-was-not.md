---
id: issue-00113-the-quality-gates-sat-where-the-risk-was-not
type: issue
role: main
status: partially-resolved
parent: report-00003-ddd-library-review-2026-07-29
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

### `-process-manager-engine`：24 个测试 + 门禁，但只覆盖一半

已测并已上门禁：重试排期（`ExponentialBackoffPolicy`）、积压读（`ProcessBacklog`）、
持久化约定（`ParkedInputs` / `Payloads`）。这些是纯函数与几个聚合读，只需要一个时钟和桩。

**明确留在门外**（写在 pom 里，不是省略）：`runtime` / `relay` / `deadline` / `replay` /
`operation` / `autoconfigure`，约 1300 行，其推理**同时跨四个 store 端口**
（instance / transition / effect / deadline）。要测它，需要四个都遵守各自 claim 语义的内存实现，
不是桩——那是这项工作剩下的一半，**值得做**。
在 pom 里点名，好过给整个模块设一个低到能过的阈值：后者读起来像"有覆盖"，实际是"有缺口"。

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

## 剩余工作（不是遗漏，是显式的下一步）

`-process-manager-engine` 的 store 支撑部分（约 1300 行）仍无测试基架。
要做的是四个内存 store（各自遵守 claim / lease / 代际 语义），
然后是 `DefaultProcessRuntime` 的精确一次推进、effect relay 的队头与租约、deadline 的代际栅栏、
parked-input 的持久化重放。这是本项真正剩下的一半。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（§2 架构层「4567 行零直接测试」那条、§3 第 12 项）
- PIT 找出的三条未测路径中，有一条是第 10 项刚引入的：
  [[issue-00111-the-relay-waited-for-each-send-in-turn]]
- 内存 store 的"不替代 SQL 实现"边界，与端口 javadoc 里那条"没消除的重复"同源：
  [[decision-00020-outbox-engine-over-one-store-port]]
- 按类型而非按名字匹配异常的教训：[[issue-00102-failed-operations-are-not-recorded-under-an-outer-transaction]]
- 门禁形状的既有先例：[[design-00007-code-quality-gates]]
