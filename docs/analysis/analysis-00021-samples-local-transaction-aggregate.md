---
id: analysis-00021-samples-local-transaction-aggregate
type: analysis
status: draft
parent: analysis-00014-ddd-samples-scenario-catalog
---

# S8 本地事务：聚合边界、乐观锁与冲突重试

对应 sample：`aipersimmon-ddd-samples/s08-local-transaction-aggregate`。场景清单见
[[analysis-00014-ddd-samples-scenario-catalog]]。

## 0. 本篇定位

单服务、单库内一次业务操作的事务处理。四个问题：事务由谁开、"一个事务一个聚合"在真实压力下怎么
成立、乐观锁保护了什么，以及——本篇最重要的一条——**乐观锁什么时候根本保护不了你**。

最后这一条是最容易被忽略的：`version` 只保护**这个命令读写过的那些行**。一条跨行的规则（"所有 sku
合计不得超过 N"）读的是命令不会写的行，于是两个操作不同 sku 的命令**在任何一行上都不重叠**，两个
version 谁也发现不了谁，两个检查都通过、两个都提交，规则被破坏而全程没有任何冲突。

sample 用两个确定性的交错测试把这条"破"与"立"都跑通了。

## 1. 事务由谁开

`TransactionCommandInterceptor` 在拦截器链的 **order 200**，最靠内的一层。后果：

- **handler 上不写 `@Transactional`**——链已经开好了事务。sample 里一个都没有。
- 没有 `PlatformTransactionManager` 且 `aipersimmon.ddd.cqrs.transaction.required=true`（默认）时
  **启动失败**，抛 `MissingTransactionManagerException`，还有专门的 `FailureAnalyzer` 渲染成可操作
  报告。设成 `false` 则每次启动打 WARN。
- 仓储侧另有一道兜底：`saveAggregate` 在没有活动事务时直接拒绝（S17 已演示）。

**嵌套 dispatch 会加入外层事务**（`UnitOfWork` 默认 `REQUIRED`）。所以"一个命令一个事务"说的是
**根 dispatch**；内层失败会把共享事务标成 rollback-only，外层即使吞掉异常也会以
`UnexpectedRollbackException` 收场。重试也只对根 dispatch 完全成立。

## 2. "一个事务一个聚合"，以及必须动两个时的三条路

这条基线的理由是：聚合是一致性边界，一个事务跨两个边界就等于把两套规则纠缠在一起，还会放大锁竞争。

真的需要同时改两个时有三条路，判据是**业务是否要求全有或全无**：

| 路 | 什么时候选 | 代价 |
| --- | --- | --- |
| 重划聚合边界 | 两者本来就是一个一致性单元 | 边界变大 = 竞争变热，所有操作都排在同一行上 |
| 拆成最终一致 | 能容忍片刻不一致 | 需要事件/流程与补偿（S3/S9） |
| **明确破例** | 业务要求全有或全无，且不值得上流程编排 | 打破基线，必须写清理由与安全前提 |

sample 的 `ReserveStockHandler` 走第三条，并把理由与前提都写进了 javadoc：一次预留半成功会留下
永远无法履约的订单和没人释放的库存；重划边界会造出一个所有预留都要竞争的热行；拆成最终一致要为一条
不需要它的规则引入 S9 的全套机械。

**让破例安全的关键不是事务，而是顺序**：把每个 sku 全部载入、全部判断完，**之后**才写第一行。
`whenTheSecondSkuIsRefusedTheFirstIsNotWrittenEither` 断言了第二个 sku 被拒时第一个的
available 与 version 都没变。

顺带一个真实细节：**同一个命令里两行指向同一个 sku 必须先合并**。否则第二次加载会看到过期状态，
而 version 不会发现——同一行、同一事务、同一个 expected version。`twoLinesNamingOneSkuAreOneReservation`
钉住了它。

## 3. 乐观锁：保护什么，以及两种异常为什么要分开

写入走的是 `WHERE version = ?`。失去竞争的一方**影响 0 行**，仓储抛
`OptimisticLockingFailureException`，消息含 "was modified concurrently (expected version N)"。
`awriteThatLostTheRaceAffectsNoRow` 断言了丢失的更新**真的丢了**（库存停在别人写的值上，而不是被
悄悄覆盖）。

拦截器链上有两处相关（顺序是设计出来的，不是巧合）：

| order | 拦截器 | 作用 |
| --- | --- | --- |
| 75 | `RetryOnConflictCommandInterceptor` | **opt-in**，只重放 `ConcurrencyConflictException` |
| 175 | `ConcurrencyTranslationCommandInterceptor` | `OptimisticLockingFailureException` → `ConcurrencyConflictException`；`DuplicateKeyException` → `DuplicateEntityException` |

**翻译必须在事务之内、重试之外**：异常向外传播，重试要抓到已翻译的类型，翻译就得夹在事务和重试
之间。库的注释记了这条历史——它曾在 order 50，于是"那个 opt-in 的重试在它唯一存在的路径上静默
失效"。

**两个异常类型是刻意分开的**：更新冲突可以重放，**创建冲突绝不能**——重放一个已经发生过的创建，
要么永远撞、要么更糟地造出第二个。

## 4. 重试：哪些命令能重试

配置只有三项：`retry-on-conflict.enabled`（默认 `false`）、`max-attempts`（默认 3，**含首次**）、
`initial-backoff`（默认 50ms，**每次翻倍、无 jitter**）。sample 打开了它。

**每一次重试是一次完整的新 dispatch**：新事务、重新加载聚合、prechecks 重跑。这正是重试能成立的
理由——第二次尝试是对**已提交状态**做决定，而不是对第一次读到的状态。

sample 用两个确定性的测试分别钉住两侧：

| 测试 | 断言 |
| --- | --- |
| `aconflictIsTranslatedAndReplayedByTheRetryInterceptor` | 首次抛冲突的 handler，dispatch 仍然成功，返回值说明用了 2 次尝试 |
| `acreateThatCollidedIsNotReplayed` | 抛 `DuplicateEntityException` 的 handler **只被调用 1 次** |

判断一个命令能不能重试：

- **能**：读-改-写型、幂等的更新（预留库存、改状态、加计数）；
- **不能**：创建（会造出第二个）、已经产生过外部副作用的（钱扣了、短信发了）、非幂等的追加；
- **注意**：只有根 dispatch 完全安全；嵌套 dispatch 共享外层事务，重试它没有意义。

## 5. 乐观锁不够用的时候（本篇核心）

### 5.1 破：跨行规则没有任何保护

`perAggregateVersionsDoNotProtectARuleThatSpansThem` 的交错是确定性的：

1. 事务 A 打开：读 SKU-A，读遍所有 sku 算出"已预留合计 = 0"，判断 `0 + 15 <= 20` 通过；
2. **另一个线程**上一个完整的命令预留 SKU-B 15 个并提交（必须是另一个线程——嵌套 dispatch 会加入
   外层事务，那就不是交错了）；
3. 事务 A 继续：`skuA.reserve(15)`、save——**没有冲突**，因为这一行的 version 正是它读到的值，
   而另一个命令写的东西与它读的东西毫无重叠。

结果：两个命令都以为自己在 20 的限额内，**合计 30**。测试直接断言了这一点。

这就是为什么"我加了乐观锁"不等于"我的规则安全了"。version 是**行级**的乐观并发控制，不是跨行
不变量的执行器。

### 5.2 立：给规则一个属主行

修法不是加锁范围，而是**让这条规则有一个自己的聚合**。sample 的 `ReservationBudget` 就是一行，
每次预留都会写它，于是**它的 version 成了序列化点**——即使两个命令碰的是不同的 sku。

`givingTheRuleAnOwnerRowMakesTheVersionProtectItAgain` 用同样的交错，断言这次被拒绝了
（"was modified concurrently"），限额守住，合计 15。

### 5.3 什么时候升级到哪一种

| 手段 | 适用 | 代价 |
| --- | --- | --- |
| 行级乐观锁（默认） | 规则只涉及一个聚合自己的数据 | 无 |
| **给规则一个属主聚合** | 规则跨若干聚合，且需要立即一致 | 那一行成为竞争热点；写放大 |
| 唯一索引 / CHECK 约束 | 规则能表达成一个数据库约束（"同一 clientReference 只能有一份"） | 错误来自数据库，需要翻译（S2 演示过 → 409） |
| 悲观锁（`SELECT ... FOR UPDATE`） | 需要在读之后就阻止别人 | 持锁时间 = 事务时长，容易连锁等待 |
| 提升隔离级别 | 少数确实需要的场合 | 序列化失败要重试，吞吐代价明显 |
| **改成最终一致** | 规则本可容忍片刻不一致 | 需要补偿（S9） |

顺序建议：先问"这条规则真的必须立即一致吗"（很多"必须"其实不是），再问"能不能表达成约束"，最后
才考虑造属主行或上悲观锁。

## 6. 框架自己的写入与业务共享事务

outbox 行、操作日志行都在**同一个事务**里落库——这正是事务性 outbox 的全部意义（S4）。前提是它们
与聚合共用**同一个 `DataSource`**。审计的两半分居事务两侧（提交内/重试外）是 S14 的取舍。

## 7. 常见错法

| 错法 | 后果 |
| --- | --- |
| handler 上加 `@Transactional` | 与链上的事务叠加，语义变得难以推断 |
| 一个命令里分两次加载同一个聚合 | 后一次覆盖前一次的决定，version 不会发现 |
| 先写一部分再判断剩下的 | 破例不再安全，部分提交成为可能 |
| 打开重试却让创建型命令也走它 | 撞死循环，或造出第二个实体 |
| 打开重试却在 handler 里做了外部副作用 | 副作用被重放（扣两次款、发两条短信） |
| 以为"加了乐观锁规则就安全" | 跨行规则完全没有保护，且不会有任何冲突提示 |
| 用扩大聚合边界来解决跨行规则 | 造出所有操作都竞争的热行 |
| 自己声明 `MybatisPlusInterceptor` bean | **整体替换**框架装配，乐观锁拦截器一起消失（S17 记了这条） |
| 期望嵌套 dispatch 有自己的事务 | 它加入外层；内层失败会让外层以 `UnexpectedRollbackException` 收场 |

## 8. 本篇不覆盖

- 跨服务的一致性（S4 最终一致 / S10 强一致）；
- 多步流程的编排与补偿（S9）；
- 聚合怎么落表、`restoreVersion` 的坑（S17）；
- 领域事件的相位与易失性（S3）；
- 幂等键与业务唯一约束的分工（S2）。
