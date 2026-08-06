---
id: analysis-00037-samples-read-side-caching
type: analysis
status: draft
parent: analysis-00014-ddd-samples-scenario-catalog
---

# S26 读侧加速：缓存与投影的取舍

对应 sample：`aipersimmon-ddd-samples/s26-read-side-caching`（一个部署单元、58 个用例）。
场景清单见 [[analysis-00014-ddd-samples-scenario-catalog]]，与 [[analysis-00034-samples-cqrs-read-model]]（S12）成对。

## 0. 本篇定位

一个数字：某商品近 30 天卖了多少。它有三种算法——每次读都从 `s26_order_line` 现算（慢）、
缓存在 Redis 里（快、会旧）、写时维护在 `s26_product_sales` 里（快、可排序、可重建）。
同一个值，三种代价，三种故障形态。整篇就是这个对照。

库对缓存**什么都没提供**：没有缓存模块，没有缓存要实现的端口，没有能打开它的配置项。这不是缺口，
这是本篇的前提——库只提供了缓存该挂的那个接缝（`QueryInterceptor`），以及缓存绝不能挂的那个地方
（聚合）。两者都是跑出来的，不是断言出来的。

## 1. 判据：不看读频率，看写频率与"能被问什么"

团队通常这样决定："这个读很热，加缓存吧。" 这个判据是错的。两个更有用的：

**(a) 写频率决定缓存是否有意义。** 一个每次写入都要失效的值没有缓存，只有开销。S26 里销量是
系统中写入最频繁的东西，所以**销售不触发失效**——`RecordSale` 一行缓存代码都没有，这是选择而非遗漏
（`RecordSale.java` 的 javadoc 就写着这句）。改名与改价是低频写，所以它们**立即失效**。

**(b) "能被问什么"决定缓存是否够用。** 把 `QueryCache` 和 `SalesBoard` 两个接口放在一起看，
决定就不再是口味问题：

| | `QueryCache` | `SalesBoard`（投影） |
| --- | --- | --- |
| 取一个已知 key | ✅ | ✅ |
| 按任意条件排序取 top N | ❌ 做不到 | ✅ `top(int)` |
| 从源头重算 | ❌ 没有源头，它**就是**副本 | ✅ `rebuild(window)` |
| 有 miss 路径（尾延迟） | 有 | 没有 |
| 写路径代价 | 零 | 每次销售多一次写 + 同一行的争用 |

**需要"按任意条件查询"的，缓存救不了：你得先知道答案才能构造 key。** 缓存只能救"同一个 key 被反复问"。
`ProjectionVersusCacheTest.onlyTheProjectionCanBeAskedForTheBestSellers` 就是这一行的可执行版本。

## 2. 缓存挂在哪：`QueryInterceptor`，不是仓储

库在 issue-00150 那一伞里给读侧加了 `QueryInterceptor`，它的 javadoc 点名了不调用 `proceed()` 的两种
正当用途：*"a cache, an authorization refusal"*。S26 用的就是前者。

挂在拦截器而不是读适配器上，换来三件事：

1. **能短路。** 命中只花一次 Redis 往返，handler 根本不跑。`CacheHitAndMissTest.ahitAnswersWithoutTheHandlerRunningAtAll`
   不是断言计数器，而是**绕过命令通道直接改库**，再断言读到的还是旧值——只有 handler 没跑才可能读到旧值。
2. **handler 保持无知。** `ProductDetailHandler` 与"从没考虑过缓存"时一模一样；删掉一个 bean 就等于关掉缓存。
3. **策略与实现分离。** key、TTL、jitter、single flight、失效时机全在 application 层，只有
   `RedisQueryCache` 知道存储是 Redis。ArchitectureTest 里有一条规则钉住这一点。

`order = 100`：**日志与鉴权必须在它外面**（更小的 order）。反了就是"缓存命中时跳过鉴权"。

**opt-in 而非 opt-out。** 查询要主动戴上 `CachedQuery` 才会被缓存，因为反过来的话，那个必须新鲜的读
（余额、库存）要靠人记得排除，而忘记的后果是静默的。`CacheHitAndMissTest.anuncachedQueryIsUntouchedByTheInterceptor`
断言未标记的查询连计数器都不动。

一处**如实的限制**：`CachedQuery.resultType()` 返回 `Class<R>`，所以结果是泛型集合的查询现在戴不上这个
接口（`List<TopSeller>` 不是 `Class`，需要 Jackson 的 `TypeReference`）。这不是巧合——本篇唯一被缓存的
查询返回单条 record，列表查询由投影回答，而列表本来就该由投影回答。

## 3. 聚合不能缓存——但坏的不是你以为的那件事

清单里这条写的是"`version()` 的语义会被绕过，写入保护随之失效"。**实测下来后半句不成立，而真相更糟。**

乐观锁没有失效：从旧版本发起的写仍然被拒绝，数据库始终一致。`AggregateCacheTrapTest`（测试作用域
的 `CachedProducts` 装饰器，四行 memoisation）测出来的是另外两件事：

**(1) 没保存的修改泄漏给下一个读者。** 一个实例发给所有人。命令改了聚合然后失败（校验不过、
另一个聚合抛异常、回滚），修改留在共享实例里，下一个命令从数据库从未有过的状态出发。**事务回滚了，
对象没有。**

**(2) 一次本该重试成功的冲突，变成一次"成功但什么都没写"。** 完整链条：

- 缓存实例在 v1。别人把行推到 v2。
- 改名把缓存实例改成 `Mechanical Keyboard`，save 匹配到 0 行 → 冲突（正确）。
- `RetryOnConflict` 重跑命令——**这是库文档里"预期会成功"的那一类重试**，因为重跑会重新加载聚合。
- 但重新加载来自那个 map，拿回同一个已经被改过名的实例。`renameTo` 返回 `false`，handler 视为
  "无事可做"，命令**正常返回**。
- 调用方收到成功。数据库还是 `Moved On`。没有异常、没有日志、没有 409。

责任划分是**分别量出来的**，不是推断的：
- 去掉 memoisation 跑同样三个用例 → **三条全红**，且各自红在断言行、各自给出健康值
  （写进去了 `Mechanical Keyboard`、没泄漏 `Half Done`、版本是 2 不是 1）。
- 保留 memoisation 但**关掉 RetryOnConflict** → 冲突照抛（`AggregateCacheTrapWithoutRetryTest`）。

所以：**memoisation 让写入不可能，重试让它安静。** 谁都不该背对方的锅。关掉重试的团队会遇到一场
409 风暴——可见、错误、但好找得多。

这条禁令在 sample 里被钉了两次：ArchitectureTest 禁止任何 `Products` 实现依赖 Redis（构建期），
`AggregateCacheTrapTest` 照做一遍并量出后果（因为看不到后果的规则，总有人会删掉）。

## 4. 失效与提交的时序：两个 orderings，一个修不掉

`s26.cache.invalidate` 有两个值，其中一个是 bug，两个都在，因为跑不出来的 bug 没人信。

**`IN_TRANSACTION`（错的）** ——`@EventListener`，注解更短、立即触发、在任何不并发读的测试里与
正确的那个无法区分。破它的交错：

1. 条目是热的，holding `Keyboard`。
2. 写方改名，eager listener 在事务内删掉条目。
3. 读方 miss，读数据库——**还是 `Keyboard`，因为写方没提交**——存进缓存。
4. 写方提交。

失效已经发生且不会再发生，于是它本该删掉的值成了缓存里的值，**一个 TTL 之内都是错的**。
`InvalidationInTransactionTest` 里最该一起读的两条断言是：**记录了 1 次 eviction，且缓存仍在供旧名字。**
"eviction 计数不为零"不是缓存正确的证据。

同一个类里还有一条兄弟断言：没有并发读者时，错的那个配置**看起来完全正确**。这正是为什么第一条
必须用 latch 写——否则这套测试自己就会把 bug 放过去。

**控制组量过：**把那个类的属性改成 `AFTER_COMMIT` 重跑 → **恰好 1 红，红在第 87 行**（陈旧读那条），
第 86 行的 eviction 计数在两种配置下都是 1。差别只有陈旧性，正是主张。

**`AFTER_COMMIT`（对的）也没关严。** 一个读方的写入落在 eviction **之后**，仍会留下陈旧条目。
`InvalidationAfterCommitTest.anevictionCanStillBeOvertakenByALatePut` 用真实的读、真实的值把它精确
构造出来（`ControllableCache` 在真实路径上把那次 put 停住）。这个窗口**移动失效时机是修不掉的**——
不存在"晚于所有已开始的读"的时刻。要关只能上跨 Postgres 与 Redis 的分布式事务，或给条目加版本戳让
迟到的写能被识别为迟到。两条都是真选项，都比大多数读模型值的钱多。

**剩下的就是 TTL。所以 TTL 不是调优项，它是"缓存能错多久"的唯一上界**；没有 TTL 的缓存迟早会在
某件事上永久错误。`RedisQueryCache.put` 因此把 TTL 作为参数而没有默认值——没有哪条路径能忘记它。

## 5. 击穿与雪崩：两个半问题，互不替代

**击穿（stampede）**：冷的热 key 上 N 个并发 miss = N 次源读。`CachingQueryInterceptor` 用
`ConcurrentHashMap<String, CompletableFuture<?>>` 做 single flight——follower 等 leader 的**值**，
而不是等一把锁（等锁的话每个 follower 醒来还要各自再读一次缓存）。

实测：10 个并发 caller，single flight 开 → **arrivals = 1**；关 → **arrivals = 10**（
`StampedeWithSingleFlightTest` / `StampedeWithoutSingleFlightTest` 互为兄弟）。控制组：把开的那个类
翻成关 → 恰好 1 红，`1 → 10`；热 key 那条仍绿，所以断言测的是合并而不是并发。

**它是进程内的，这是取舍不是修复。** 两个实例各自 miss 同一个冷 key，所以 single flight 把风暴
限到"实例数"而不是 1。跨实例锁能限到 1，代价是引入一个自身故障形态（没人释放的锁会卡住该 key 的
所有读者）。这个规模上按实例数封顶更便宜。

**雪崩**是把其他事都做对之后的后果：条目按需填充，所以部署后的一波流量会在一秒内填几千个 key，
固定 TTL 就让它们在一秒内同时过期——每个 TTL 一轮击穿，永远同步。**single flight 管每个 key 只跑
一次，jitter 管每个 key 不选同一时刻。两者修的是同一个问题的两半，谁也替不了谁。**

`TtlJitterTest` 量分布而不是推断：500 次抽样全在 ±10% 带内、去重后超过一半不同（常量函数会被这条
打红）、ratio=0 精确等于原值、TTL 短到 ratio 抽不动时原样返回（10% 的 5ms 是 0，随机区间会抛）。

## 6. 多租户的 key：危险在**歧义**，不在顺序

这一条我写错过一次，纠正记在这里。

我最初的说法是"租户放前面所以别的租户拼不出你的 key"。**不成立。** 真正的危险是：用分隔符拼接
变长片段，只要**有两个**片段可能含分隔符，这个串就能被两种方式解析：

```
join("acme",   "b:product-detail:sku-x")   ==   "s26:q:acme:b:product-detail:sku-x"
join("acme:b", "product-detail:sku-x")     ==   "s26:q:acme:b:product-detail:sku-x"
```

两个不同租户、两个不同 query key、同一个串。**租户在前也没能阻止它**——上面的例子租户就在前面。
`CacheKeysTest.arawJoinLetsOneTenantReadAnothersEntry` 就是这个碰撞，写成可运行的对照，因为
"这不可能发生"单独断言等于"我没试过"。

修法是**让最多一个片段自由**。sku 必须自由（别人家目录里有什么不是本篇能管的），所以约束落在
部署方能控制的租户上：`CacheKeys` 拒绝含 `:` 的租户 id。库确实不约束它——`Tenants.of` 只拒绝保留
前缀 `__` 和超长——而租户 id 常常来自客户选的 slug 或子域，所以这不是杞人忧天。

租户**仍然放在最前面**，但理由换了、也更简单：**前缀是廉价地指称"一组条目"的唯一方式**，所以
领头的片段决定了运维能按什么单位 flush。租户在前让"删掉 t1 的缓存"可表达；查询类型在前会让
"删掉所有租户的商品详情"可表达，而后者用处更小。

租户从 `TenantContext.effective()` 来，这是库明说的读侧来源（`TenantContext` 的 javadoc：
*"read by the read side and by infrastructure where no CommandContext is threaded"*）——写侧权威是
`CommandContext`，而查询没有 `CommandContext`，`QueryInterceptor` 的契约按设计就不带。多租户关闭时
`effective()` 返回 `__root__` 哨兵，所以单租户是 N=1 而不是另一条代码路径，key 的形状只有一种。

一处**藏在同步性里的坑**：`AFTER_COMMIT` 的投递是**提交线程上的同步调用**，而 key 来自线程绑定的
`TenantContext`。把失效挪到线程池上会算出哨兵租户的 key 然后欢快地删掉零个条目——静默失败，测试全绿。

**控制组量过：**把 `CacheKeys.of` 里的租户段摘掉 → **7 红**，其中
`TenantIsolationTest.thesameSkuUnderTwoTenantsIsTwoEntries` 红在 `hits: expected 0 but was 1`——
租户 B 实打实地被喂了租户 A 的条目。

## 7. 一个条目、两种一致性保证

`ProductDetail` 里 `name`/`priceCents` 是目录自己的事实，改了就失效 → **一次提交内正确**。
`soldRecently` 派生自销售流水，没人为它失效 → **一个 TTL 内正确**。

于是整个条目只和它最弱的那一半一样可靠。这是要**明说**而不是等人撞见的取舍：单个缓存值不可能提供
两种保证，要么最弱的那个对每个字段都可接受，要么把值拆成两个条目、两个 TTL、两套失效规则——两倍的
key、两倍往返、一个要重新拼装的页面。本篇选了一个条目 + 有界陈旧，因为销量是装饰而价格不是，
而对价格失效保护的正是要紧的那一半。

`BoundedStalenessTest`（TTL 设为 1s 以便量得到）四条：销售后缓存说 0 而**同一瞬间投影已经说 5**
（这一条断言就是整个缓存/投影取舍的数字版）；它会在 TTL 内自己追上；改名不等 TTL；改价同样，
且只花一次 eviction（条目按聚合而不是按字段建 key）。

## 8. 不一致的可观测性：唯一会往反方向走的指标

**缓存悄悄停止被失效时，其他每个信号都指向错误的方向。** 它不报错、不变慢、不打日志；命中率
**上升**（条目不再被删），延迟**改善**。仪表盘上，一个在供一个月前价格的缓存看起来像一个终于开始
起作用的缓存。

所以命中率单独看什么都决定不了。可行动的是 hits 对 `databaseReads`（真正省下的工作量）、
`coalesced`（single flight 吸收了多少风暴），以及**必须挂告警的 `divergences`**。

`CacheAudit.check(sku)` 读条目、算真值、比对。生产里它是抽样后台作业，输出一个挂告警的数字。
它**不做判断**：销量允许陈旧（第 7 节的决定），所以报告只说哪里不同——会替人判断哪些差异合法的比对，
要么每次销售都喊狼来了，要么把陈旧策略再实现一遍。运维盯的是 `name`/`priceCents` 的差异。

`CacheAuditTest` 四条含两条控制：一致的条目不报（否则计数器就是在数"检查了几次"）；**经命令通道
改名不产生 divergence**（所以审计测的是"失效缺失"而不是"发生了改名"）。

## 9. 运维面：flush 与 rebuild 并排放

`/ops` 上四个端点。最有意思的是 `DELETE /ops/cache` 与 `POST /ops/projection/rebuild` 并排——
整个缓存/投影之争缩成两个端点：

- **flush 缓存**：瞬时、永远安全、**什么都没恢复**。之后的读是慢的、正确的。
- **rebuild 投影**：耗时正比于源表大小、必须原子、**恢复出一张正确的表**。

事故里该按哪个，答案就在这两句话的差别里。`ProjectionVersusCacheTest.aflushLeavesNothingAndArebuildLeavesATable`
是它的可执行版。

两处运维细节值得记：

- **flush 必须 `SCAN` 不能 `KEYS`。** 两者都回答"哪些 key 匹配"。`KEYS` 一条命令走完整个 keyspace 并
  占住 Redis 的单线程，在共享实例上百万级 key 时是对**所有**使用者的一次故障；`SCAN` 用游标分批、
  与真实流量交错，代价是更弱的保证（运行期间增删的 key 可能出现也可能不出现）——对一次清扫来说这个
  弱化不花钱。客户端 API 上两者差一个方法调用，生产上差几分钟停机。
- **flush 按租户，不按全库。** 只有租户领头才做得到；否则唯一可用的爆炸半径是整个 keyspace。

## 10. 负向对照（逐个单跑，逐个量）

| # | 改动 | 预期 | 实测 |
| --- | --- | --- | --- |
| C1 | `InvalidationInTransactionTest` 的属性改 `AFTER_COMMIT` | 陈旧读那条红 | **1 红，第 87 行**；eviction 计数两种配置都是 1 |
| C2 | 从 `AggregateCacheTrapTest` 摘掉 `CachedProducts` | 三条全红 | **3 红**，各在断言行，值分别为 `Mechanical Keyboard` / `Keyboard` / 2 |
| C3 | `StampedeWithSingleFlightTest` 关掉 single flight | arrivals 断言红 | **1 红**，`1 → 10`；热 key 那条仍绿 |
| C5 | **只**从 `AfterCommit` 摘掉 `@DomainEventHandler` | 库规则应当红 | **库规则绿**，只有 sample 那条 meta 版红 → issue-00166 |
| C6 | `CacheKeys.of` 去掉租户段 | 跨租户命中 | **7 红**，含 `hits: expected 0 but was 1` |

C4（"缓存到底有没有被查"）没有单独跑：`ahitAnswersWithoutTheHandlerRunningAtAll` 自带对照——
它绕过命令通道改库后断言读到旧值，缓存没生效就必然读到新值。

C5 用的是"一进一出"的天然对照：同一个文件里两个订阅者，同包、同事件、只差注解拼法。

## 11. 库的问题：两个新 issue，都不是正确性缺陷

**[[issue-00166-the-event-listener-rules-do-not-see-transactionaleventlistener]]（P2）** ——
`EventRules.areEventListenersHandling` 用 `isAnnotatedWith` 只匹配**直接**标注，而
`@TransactionalEventListener` 把 `@EventListener` 作为 meta-annotation 携带，所以**共用这个谓词的
三条规则**（订阅者放对层 ×2、订阅者可按注解检索 ×1）对所有"提交后才跑"的订阅者一概不检查——
而那正是发通知、调外部、清缓存这一类**最需要**被管的订阅者，也正是库自己文档推荐的写法。
存量查过了：全仓三处 `@TransactionalEventListener` 都已带标记，所以这是个**改完就绿**的一行修法；
也正因为大家一直恰好记得加，这个洞从来没有被任何一次构建暴露过。

**[[issue-00167-the-querybus-javadoc-denies-the-interceptor-chain-it-has]]（P3）** ——
`QueryBus`（端口，最先被读的那个文件）的 javadoc 写着 *"there is no transaction or interceptor chain
here"*，而 `QueryInterceptor`、`RegistryQueryBus` 和装配代码三处都说有。想给读侧加横切关注点的人
从端口文档得到"读侧没有接缝"的结论，于是把它写进每个 handler——正是 `QueryInterceptor` 说它要消除的
局面。一句话可修。

除此之外**没有别的**。缓存这条路上库没有可指摘之处，因为它在这条路上没有代码；`QueryInterceptor`
这个接缝够用，`TenantContext` 恰好就是读侧该问的地方，`MybatisPlusAggregateRepository` 的乐观锁守卫
在聚合被缓存时依然生效（只是在跟一个编造的版本号较劲）。

## 12. 没做的事

- **不缓存 not-found（负缓存）。** 能挡住对不存在 sku 的洪水，也能让任何人用自选的 key 填满 keyspace。
  没有对后者的约束，留在数据库里的失败更便宜。这个选择是被断言的（`anotFoundIsNotStored`），不是
  从"没有代码"推断的。
- **不做多级缓存**（本地 + Redis）。它把"进程内 single flight 的残留"和"实例间不一致"都放大一层，
  值得单独一篇。
- **不给条目加版本戳。** 它能关掉第 4 节那个残留窗口，是真选项；本篇选择记录 TTL 是唯一上界。
- **计数器是裸 `AtomicLong` 而不是 Micrometer meter。** 观测接线是 S15 的题目，本篇不重教一遍；
  生产里每一个都是带 query type 标签的 meter。
- **行不是租户隔离的**（判别列是 S13 在 S4 里的题目）。所以两个租户在这里读到相同的答案——这不削弱
  第 6 节：被检验的是**缓存**有没有把他们分开，而那正是缓存会自己搞错的部分。数据库里隔离、缓存里
  不隔离的读模型，是"处处隔离，除了没人看的那一处"。
