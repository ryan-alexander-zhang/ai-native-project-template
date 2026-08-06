---
id: analysis-00038-samples-operation-log
type: analysis
status: draft
parent: analysis-00014-ddd-samples-scenario-catalog
---

# S14 操作日志（寄宿 S1）

对应 sample：`aipersimmon-ddd-samples/s01-http-command-query`（S1 原有 15 个用例 + 本篇新增 21 个 = 36 个）。
场景清单见 [[analysis-00014-ddd-samples-scenario-catalog]]；组件设计见 [[analysis-00013-operation-log-component]]。

## 0. 本篇定位与一处进度更正

**本篇是补交。** S13（多租户）与 S15（可观测性）随宿主 S4 一起交付了，S14 没有——直到 2026-08-04 之前
samples 树里没有任何模块依赖 operation-log 组件。补在它原定的宿主 S1 里。

S1 是最小的那个 sample：一个 HTTP 入口、一个聚合、一个查询。**这正是操作日志该出现的地方**——它不需要
消息、不需要投影、不需要两个服务，它需要的只是"有人做了一件事"，而 S1 就是这句话最短的实现。

## 1. 组件不给默认值的那一个 bean，就是本篇的题目

`OperationActorResolver` 没有默认实现，缺了直接启动失败。**实测**（把 `AuditConfiguration` 上的
`@Configuration` 摘掉重跑）：

```
APPLICATION FAILED TO START
Description:
The Operation Log capture layer is active (a storage backend is on the classpath), but no
OperationActorResolver bean is defined. The capture interceptors need it to stamp the actor (who
performed the operation) onto every recorded row, from a trusted scope rather than the command
payload, so the component fails closed instead of recording a missing or forged identity.
```

而 `Actor resolve()` **无参**。这个签名就是全部的论证：一个能由命令提供的 actor 就是一个能由调用方
挑选的 actor，而主体自称的审计记录不值得留。没有参数可传，也就没有"从载荷里取"这条路可走。

于是 actor 必须来自一个**作用域**，而作用域有**生命周期**——本篇余下的内容基本都是这句话的后果。

## 2. actor 从哪来：绑定、清除，以及清除是唯一的安全属性

`CurrentActor`（ThreadLocal）+ `ActorBindingFilter`（HTTP 边界绑定，`finally` 清除）+
`AuditConfiguration`（未绑定时返回 `Actor.system(应用名)`）。

**三件事要说清楚：**

**(a) 身份的来源在本 sample 里是替身，文档里明说而不是含混过去。** filter 读的是一个请求头。头是
客户端给的，所以它本身恰恰是"可信边界"的反面。真实服务里这个 filter 就是 Spring Security 的
context：身份在任何人读它之前已经过认证，filter 的活儿只是把它从 security context 抄到 resolver
能拿到的地方。替身**没有**损害真正要紧的那条性质：actor 在边界建立、从作用域读取，绝不取自命令。
把头换成认证过的 principal，本 sample 其他任何地方都不用改。

**(b) 没绑定意味着"服务自己动的手"，返回 `Actor.system(应用名)`。** 三个替代方案都更差：抛异常 →
每个非 HTTP 入口都无法执行任何被审计的命令，审计的可用性变成应用的可用性；返回匿名/空 actor →
行里写着"没人干的"，与绑定有 bug 无法区分；回退到"上一个已知 actor" → 就是下面 (c) 那个失败。
`Actor.system` 是一个独立的 actor **type**，所以"哪些操作是服务自己做的"是一个查询而不是字符串约定。

顺带实测一处细节：`Actor.system(id)` 把 `displayName` 也设成 id（`Actor.java:27`），所以
`actor_display` 不为 null——渲染出来的审计列表不会出现空白的"操作人"，而空白与"用户名解析失败"是
无法区分的。我原本断言它是 null，被实测纠正。

**(c) 清除是唯一的安全属性，而我为它写的第一个测试是空的。**

服务过请求、被还回池子的线程仍然持有绑定；下一个在该线程上跑的东西——定时清扫、重试、任何不经过
filter 的路径——会把自己的操作记在最后用过这条线程的人头上。行是格式正确的，没有异常、没有日志。

我最初的对照测试是：发一个 HTTP 请求，然后 dispatch 一个命令，断言 actor 是 SYSTEM。
**把 filter 的 `finally` 删掉重跑 → 0 红。** 按既定判据，控制跑出 0 红 = 那条主张没被测到，
先怀疑主张——查下去发现原因：`TestRestTemplate` 的请求跑在容器工作线程上，后续命令跑在 JUnit 线程上，
**两条线程从来不是同一条**，所以清不清除都绿。

改成直接在**本线程**上驱动 filter（`MockHttpServletRequest` + 一个记录链内所见的 chain），断言链内
绑定存在、链外为空，另加一条"handler 抛异常也要解绑"。**同一个控制重跑 → 2 红**，都显示
`Actor[type=USER, id=clerk-7]` 在请求结束后仍然绑着。

教训不是"要写单元测试"，是**跨线程的泄漏对任何不停在一条线程上的测试都是不可见的**。

## 3. 注解式与非注解式：判据不是"要不要 before/after"

清单问"各适用什么"。通常的答案是"需要 before/after 就用 Definition"。**这个答案漏掉了更硬的那条约束。**

`AnnotationOperationLogDefinition` 把 `targetId` 模板编译在**唯一一个 root `input`** 上
（`AnnotationOperationLogDefinition.java:58`，成功与失败两条路都用 `renderTargetId(input)`，`:111`）。
于是：

| | `ConfirmOrder`（注解） | `PlaceOrder`（Definition） |
| --- | --- | --- |
| 目标身份在哪 | 在 input 里（`orderId`） | handler 里铸造，只在 result 里 |
| 注解能用吗 | 能 | **不能**——`${input.customerId}` 会编译、会启动，然后把每一条订单操作记在一个客户 id 名下 |
| 失败能审计吗 | 能：目标 id 在 input 里，抛异常时依然已知 | **不能**：`Target` 要求非空 id，而创建失败没有目标 |

**所以"能不能审计一次失败"取决于目标的身份是否在操作之前就存在**——这是"id 在哪里铸造"这个建模决定
的后果，而在审计员问"谁试过"之前不会有人想到。

`PlaceOrderAudit.failed` 返回 `Optional.empty()`，并在 javadoc 里点名修法：**要审计失败的创建，
身份必须在命令之前铸造**（客户端提供，如 S2 的幂等键；或在边界用 `IdGenerator` 盖章）。这条被写成了
断言（`afailedCreateRecordsNothingBecauseItHasNoTarget`），所以哪天有人开始记录失败的创建，这个测试
会红并被读到。

Definition 路另外两项能力（在业务事务内、handler 之前捕获 before 投影；记录 `changes` 列表）本篇
**没有用**，因为创建没有 before 状态——这一点值得写出来，因为"要 before/after 才用 Definition"的通行
总结会让人以为本篇这个命令不需要它。

代价也在文件里看得见：注解六行说完的事，Definition 要一个类，而且操作码与目标类型不再挨着它描述的命令。

## 4. 成功同事务 / 失败独立事务，两者都实测

- **成功行与业务改动同事务**：把 `commandBus.send` 包在一个 rollback-only 的外层事务里，事务内订单和
  审计行都在，事务外两者都没了。审计行能比它描述的改动活得更久的日志比没有日志更糟——每一行在被相信
  之前都得先跟数据核对一遍。
- **失败行独立事务**：重复确认是领域拒绝，命令事务回滚，**审计行还在**。这是日志能回答"谁试图重复确认"
  的唯一原因。

## 5. outcome 与 completion 是两列，不是一个状态——我猜错了两次

我断言重复确认与校验失败都记成 `FAILED`。**实测都是 `REJECTED`**：

| 情形 | outcome | completion | failure_code | failure_category |
| --- | --- | --- | --- | --- |
| 重复确认（领域拒绝） | `REJECTED` | `ROLLED_BACK` | `ordering.order-not-confirmable` | `CONFLICT` |
| 空 orderId（Bean Validation） | `REJECTED` | `NOT_STARTED` | `validation.rejected` | `VALIDATION` |

两者 outcome 相同——**都是拒绝，不是坏掉**——区分它们的是 `completion`：`ROLLED_BACK` 说工作开始过
又被撤销，`NOT_STARTED` 说什么都没试。审计员看到校验失败写着"已回滚"会去找一个从未运行过的东西的
残留效果，这就是库把两列分开而不是合成一个 status 的理由。

`failure_code` 直接是本上下文自己的 `ErrorCode`（`DefaultFailureClassifier` 从 `DomainException` 上读），
所以审计行与 HTTP problem document 携带同一个码，两边能直接对上而不需要映射表。

`FAILED` 是第三种（并发冲突与一切意料之外的：数据库坏了、bug），本篇**刻意没有构造**——为一个枚举值
去把基础设施打坏不值得。要紧的是"一个坏请求不会落到 FAILED"，这一点两条断言都覆盖了。

## 6. 不该记什么：allowlist 就是脱敏策略

一处**用词纠正**：`OperationLogs.record` 的 javadoc 说它 "normalizes, redacts, freezes"，容易读成
"库会替你脱敏 PII"。看 `Redactor` 的实现——它做的是**去 CR/LF（防日志注入）+ 长度截断**，
类 javadoc 自己写明 *"It does not decide what is allowlisted — that is the definition/annotation's job"*。
**所以进不进审计行，由模板/Definition 决定，写入之后没有第二道关。**

模板语言（`RestrictedTemplate`，public，本篇直接编译它做单测）：

- 未知 root / 未知函数 / 未闭合占位符 **在 compile 期失败**，对注解而言就是启动失败。所以模板虽是字符串
  却仍可安全重构：改字段名换来的是启不来，而不是几个月后被人发现的空摘要。
- 白名单函数三个：`mask` / `truncate` / `defaultValue`。**`mask` 是遮蔽不是删除**：首字符 + `***` +
  末字符（≤2 字符则整体 `**`）。够客服确认"档案里的号码与您念的一致"，不够拿来当那个号码；但要精确说
  它还泄露什么：首末字符，以及"长度大于 2"。用在电话或账号引用上合理，用在首末字符就是大半个秘密的东西
  上不合理。
- 未解析的路径渲染成空而不抛。这是语言唯一宽容的地方，取舍是刻意的：摘要里缺一段比命令因为记不了
  日志而失败要好。代价是"合法但写错"的路径是静默的——compile 期的 root 检查把这个风险压在"已知对象里
  的拼写错误"范围内。

本篇的具体选择：审计行记**行数不记明细**（`detail("lineCount", ...)`，摘要写 `2 line(s)`），
并断言 `summary`/`details` 里**没有** `SKU-1`。整份请求快照是会被顺手选中的默认值，代价三样：审计表
变成 schema 里最大的表；请求里恰好带的任何东西都落进为审计数据写的保留策略；日志开始回答一个属于
请求日志的问题。审计行欠审计员的是：哪个操作、对什么、谁做的、什么结果。

## 7. 写日志本身失败时怎么办

清单把这条标成"合规决策不是技术偏好"，本篇给出的是**结构性答案而不是一个开关**：

- **成功路径**：sink 的 insert 与业务同事务，所以写日志失败 = 业务失败。这是"审计有洞不可接受"这一侧，
  代价明码标价：审计存储的可用性成为业务可用性的一部分。
- **失败路径**：独立事务，且日志写失败不能把原始异常吃掉——调用方要看到的是他们那个失败。
- 幂等键（`uq_operation_log_idempotency (tenant_id, source, idempotency_key)`）让重试收敛到
  `AppendResult.Duplicate` 而不是第二行。

本篇**没有**去把 sink 打坏来观测第一条，所以这一节是读代码 + 读 schema 的结论，不是实测——按 §9 的
惯例，没测的就说没测。

## 8. 每一行都带因果 id 与 source

实测：`source = s01-http-command-query`、`tenant_id = __root__`、`message_id`/`correlation_id` 非空、
`idempotency_key` 是 64 位 hex（SHA-256）、`schema_version = 1`。`source` 是行的逻辑写入者，也是幂等键
作用域的一部分——两个服务共用一个库时，审计轨迹自动分开而不需要谁跟谁约定。

## 9. 负向对照（逐个单跑，逐个量）

| # | 改动 | 预期 | 实测 |
| --- | --- | --- | --- |
| C1a | 删掉 filter 的 `finally { clear() }`（对**旧**测试） | 泄漏被打红 | **0 红** → 测试是空的，见 §2 |
| C1b | 同一改动（对**改写后**的测试） | 泄漏被打红 | **2 红**，都显示 `Actor[USER, clerk-7]` 仍绑着 |
| C2 | 摘掉 `PlaceOrderAudit` 的 `@Component` | Definition 路的行消失 | **4 红**（3× `found 0`，1× 越界） |
| C3 | 摘掉 `ConfirmOrder` 的 `@OperationLog` | 注解路 + 两条失败路的行消失 | **3 红**，均 `found 0` |
| C4 | 摘掉 `AuditConfiguration` 的 `@Configuration` | 启动失败并给出可执行指引 | **启动拒绝**，FailureAnalyzer 原文见 §1 |

## 10. 库的问题：没有

一条也没有。这个组件是本系列到目前为止**踩不出问题的一个**：需要使用方决定的东西没有默认值且失败
指路清楚（C4）、模板在启动期校验、outcome/completion 的划分比我猜的更细、`Redactor` 的 javadoc 明确
自己不负责判断敏感性（所以 §6 那处误读是我读 `OperationLogs` 的 javadoc 太快，不是文档错）。

本篇被库纠正了三次（`Actor.system` 的 displayName、两处 outcome），三次都是库更对。

## 11. 没做的事

- **没把 sink 打坏**去观测"写日志失败时业务怎么办"的第一种情形（§7）。
- **没用 `changes` 列表**：创建没有 before 状态。带 before/after 的审计行留给 S27——那里"把旧邮箱写进
  `changes.before`"正是合规擦除要面对的东西。
- **没做保留期清理**：`MybatisPlusOperationLogCleanup` 存在且 opt-in，保留期与法务对齐是 S27 的题目。
- **没做读侧**：`OperationLogReader` 的分页与条件是查询契约（S20）的形状，本篇的断言直接读列，因为列
  才是审计工具会写在上面的契约。
- **actor 的真实来源仍是替身**（§2a）。接 Spring Security 是使用方的选择，不是本 sample 要示范的东西。
