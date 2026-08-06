---
id: analysis-00022-samples-validation-layers
type: analysis
status: draft
parent: analysis-00014-ddd-samples-scenario-catalog
---

# S19 校验的三层分工

对应 sample：`aipersimmon-ddd-samples/s19-validation-layers`。场景清单见
[[analysis-00014-ddd-samples-scenario-catalog]]。

## 0. 本篇定位

同一个"不允许"，可能是**请求的形状不对**、可能是**跨上下文的前置条件不满足**、也可能是**聚合的不变量
会被破坏**。库为这三件事提供了三个不同机制，放错层会付出真实代价——其中最容易付错的那一笔是：一个
远程调用占着数据库连接等网络。

三层各就各位之后，会看到一个好现象：**命令处理器里一行校验都不剩**。

## 1. 三层，一张表

| 层 | 机制 | 何时运行 | 失败意味着 | 到边界是 |
| --- | --- | --- | --- | --- |
| 1 请求形状 | DTO 上的 Bean Validation | 进入总线之前 | 这还不算一个请求 | 400，`about:blank` + `errors[]` |
| 2 命令契约 | 命令组件上的约束（拦截器 order 100） | 总线内，事务之前 | 调用方给的命令不合法 | 400（code 是约束注解名） |
| 3 前置条件 | `CommandPrecheck`（order 150） | **事务之前** | 这个命令没有希望成功 | 按 `ErrorCode` 的 category |
| 4 不变量 | `Invariant` / `Transitions`（聚合内） | 事务内，写入前 | 会写出非法状态 | 按 `ErrorCode` 的 category |

（第 1 层与第 2 层是同一件事的两个入口，所以下文按"形状 / 前置 / 不变量"三层讲。）

## 2. 第一层：形状，而且**故意重复两遍**

HTTP DTO 上有 `@NotBlank` / `@Positive`，命令上**也有**。这不是浪费：

- DTO 属于 HTTP 契约，可以为兼容老客户端而改字段名、加废弃字段；
- 命令属于应用契约，**每个入口共享它**——消息消费者（S5）、定时任务（S11）不经过 web 层，却应该得到
  同一份检查。

sample 用两个测试分别钉住：HTTP 空 body 被拒时"什么都没被咨询过"（`log.observations()` 为空，说明
它根本没变成命令）；直接 `commandBus.send(new PlaceOrder("", 5))` 则由 order 100 的
`ValidationCommandInterceptor` 抛 `ConstraintViolationException`。

到边界的形状差异要记住（S1 §5.5 已述）：**普通 `@Valid` 失败渲染成 `about:blank`/400**，不走
`/problems/validation-failed` 那个族——那个族只有带 `VALIDATION` category 的 `ErrorCode` 才会走到。

## 3. 第二层：`CommandPrecheck`，本篇的核心

### 3.1 它填的是哪个空

有一类检查在三层里本来无家可归：**跨上下文的咨询式查询**——"这个客户被风控冻结了吗"、"库存那边根本
能不能供这些 SKU"。它应该尽早拒掉没希望的命令，但**对本事务的一致性毫无贡献**。

放在 handler 第一行会怎样？库的 javadoc 说得很直接：

> Placed inside the handler it runs on the handler's first line, which is already inside the write
> transaction; the moment the port behind it becomes a remote client, that transaction (and the
> database connection under it) waits on a remote call, and one slow dependency amplifies into an
> exhausted connection pool.

`CommandPrecheck` 在 order 150，**夹在校验(100)与事务(200)之间**，所以一次拒绝**一个连接都不占**。

### 3.2 这条不是文字，是可断言的事实

sample 没有靠"拦截器 order 是 150 所以它在事务外"来说服读者，而是**在调用发生的那一刻记录事实**：
测试把两个咨询端口换成会记录 `TransactionSynchronizationManager.isActualTransactionActive()` 的
实现，再放一个 order 300 的测试用拦截器代表 handler 侧。断言是：

```
precheck:customer-standing   → insideTransaction = false
precheck:warehouse-calendar  → insideTransaction = false
handler                      → insideTransaction = true
```

顺序也一并断言了。**生产代码里没有任何探针**——观测点就是端口实现本身，这是 S18 那条"用能回答问题的
最便宜手段"的又一次应用。

### 3.3 三条契约

- **只读、只拒绝**。写了就麻烦：那个副作用活在命令事务之外，会在命令回滚后存活下来。
- **每次 dispatch 都跑**，包括 `sendAs` 的重投（S4），所以**必须可重复**。
- **它是咨询性的，不是保证**。检查与提交之间世界会变，所以它筛的那条不变量**仍然必须由它的属主
  执行或补偿**。

### 3.4 多个预检：全部运行，第一个拒绝者胜出

sample 给同一个命令挂了两个预检（`@Order(10)` 与 `@Order(20)`）。三个测试覆盖三种组合：

| 情形 | 结果 |
| --- | --- |
| 客户被冻结 | 第一个拒绝，**第二个根本没被问**，handler 没跑 |
| 客户正常、仓库关闭 | 第一个通过、第二个拒绝 |
| 两个都该拒绝 | 客户端看到的是**第一个** |

最后一条的含义要写清：**bean 顺序决定了两个都成立的拒绝里客户端被告知哪一个**，所以 `@Order` 是契约
的一部分，不是装饰。

### 3.5 注册的严格程度与 handler 相同

预检也按命令的类型参数索引，**擦除了类型参数会在启动期失败**，消息点名理由："would silently never
run"。这与 handler 的策略一致，理由也一致：一个永远不会被调用的检查比没有检查更危险。

## 4. 第三层：不变量，唯一的保证

聚合内的 `Invariant` 是三层里**唯一的保证**——它在状态即将被写入的地方检查。

sample 用一条内部路径把这件事变成了可执行的证据：`PlaceOrderInternally` 是一个**没有任何预检**的
命令类型（运维工具、数据迁移、后台纠正都是这种路径），它照样被聚合的 `QuantityWithinCap` 拒绝。
`theAggregateRefusesWhatNoPrecheckScreened` 断言了这一点，而 `log.order()` 只有 `handler` 一项。

反过来看也成立：`thesameOverCapOrderIsRefusedOnTheScreenedPathToo` 说明"超上限"这条规则**只在
聚合里实现一次**就够了——预检根本没有筛它，因为它不需要跨上下文的信息。

**一条规则应该实现在哪一层的判据**：

| 规则的性质 | 归属 |
| --- | --- |
| 只看输入的形状 | DTO + 命令的约束 |
| 需要另一个上下文的信息，且只是为了早失败 | 预检 |
| 只看这个聚合自己的数据，且不得被违反 | 聚合的不变量 |
| 跨多个聚合，且不得被违反 | 给规则一个属主聚合（S8），预检**不够** |

## 5. 每一层的失败怎么到客户端

关键一条：**状态码跟着 `ErrorCode` 的 category 走，而不是跟着"哪一层抛的"走。** sample 的
`arefusalFromEachLayerRendersUnderItsOwnCodeOverHttp` 断言了这一点：

| 来自 | code | category | 状态 |
| --- | --- | --- | --- |
| 预检 | `ordering.customer-blocked` | `FORBIDDEN` | **403** |
| 预检 | `ordering.warehouse-closed` | `CONFLICT` | 409 |
| 聚合不变量 | `ordering.quantity-over-cap` | `DOMAIN_RULE` | **422** |

所以设计错误码时该问的是"客户端应该怎么反应"，而不是"这是第几层抛的"。错误契约本身在 S1 定清，
本篇不重复裁决。

## 6. 一个好现象：handler 里没有校验了

sample 的 `PlaceOrderHandler` 只有三行：铸 id、建聚合、保存。形状在边界和命令上查过了，跨上下文的
问题在事务之前问过了，关于自身数据的规则归聚合。**handler 里没有校验，正是另外三层各就各位的标志。**

反过来，如果发现校验都堆在 handler 里，通常意味着：远程调用在事务里（连接池风险）、同一条形状检查在
每个入口重复手写、以及本该属于聚合的规则漏在了聚合外面（于是内部路径会绕过它）。

## 7. 常见错法

| 错法 | 后果 |
| --- | --- |
| 把跨上下文查询写在 handler 第一行 | 远程调用占着数据库连接等网络，一个慢依赖拖垮连接池 |
| 在预检里写东西 | 副作用活在命令事务之外，命令回滚后它还在 |
| 预检里做非幂等的事 | 重投时会重复发生（预检每次 dispatch 都跑） |
| 把预检当保证 | 检查与提交之间世界会变；属主没执行的话规则照样会被破 |
| 只在预检里实现不变量 | 内部/运维路径没有预检，直接绕过 |
| 只在 DTO 上写约束 | 非 HTTP 入口完全没有形状检查 |
| 预检声明成擦除的类型参数 | 启动失败（好在拒绝得明确：它"永远不会被调用"） |
| 不关心 `@Order` | 两个都成立的拒绝里，客户端看到哪一个成了偶然 |
| 按"哪一层抛的"决定状态码 | 状态码其实跟着 `ErrorCode` 的 category 走 |
| 期待 `@Valid` 失败走 `/problems/validation-failed` | 它渲染成 `about:blank`/400 |

## 8. 本篇不覆盖

- 错误契约本身（problem 类型、code 命名、catalog 覆盖）——S1；
- 跨聚合规则真正的执行手段——S8；
- 跨上下文同步调用的端口与防腐层形态——S6；
- 重投与幂等（预检"必须可重复"的那个场合）——S4/S5；
- 认证授权（`FORBIDDEN` 这里只是一个 category，不是鉴权实现）。
