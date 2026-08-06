---
id: analysis-00041-samples-add-bounded-context
type: analysis
status: draft
parent: analysis-00014-ddd-samples-scenario-catalog
---

# S24 在既有服务里新建一个限界上下文

对应 sample：`aipersimmon-ddd-samples/s24-add-bounded-context`（一个部署单元、三个上下文 + 共享内核、44 个用例）。
场景清单见 [[analysis-00014-ddd-samples-scenario-catalog]]。

## 0. 本篇定位

清单说得对：读完前面所有示例，团队要做的第一件事就是加一个上下文，而没人讲过这件事的步骤。

本篇的形状与前面几篇不同：**交付物是六条规则，其余都是把规则说清楚的工作示例。** 因为这件事上
"文档能说的"和"两年后还成立的"差得最远——package-info 里写着 coupons 的契约是四个类型，两年后
那个包里会有十一个，而文档还写着四个。

| 问题 | 答案 | 谁在守着 |
| --- | --- | --- |
| 新上下文的模块从哪来 | **一个包，不是一个 Maven 模块** | §1 |
| api 必须暴露什么、不得暴露什么 | 四个类型；机械判据是 **api 是自己上下文里的叶子** | §2（库没有这条） |
| 上下文只经 api 依赖怎么接 | 库的 `BoundedContextRules`，一行 | §2 |
| 共享内核放哪、什么不该进 | `sharedkernel.api`；判据是**它是一片叶子** | §3 |
| 第一条集成用事件还是同步调用 | **两个都要**，按"答案是用来决定还是只用来记录"分 | §5 |
| 何时该独立成部署单元、什么必须先改 | 界面不用改；**事务不能再跨过那次调用**；要有"没答案"的策略 | §6 |

一条库的 issue：[[issue-00170-a-published-value-object-cannot-satisfy-both-archunit-rules]]（P2，
规则集内部冲突，本篇被它逼着做了本地绕法）。

## 1. 新上下文是一个包，不是一个模块

Maven 模块给的是编译期隔离，代价是一个新目录、一个新 pom、一次 reactor 顺序调整，以及从此每次
跨模块改动都要动两个 pom。它买到的东西——"另一个上下文不能碰我的内部"——**一条 ArchUnit 规则也能买到**，
而且那条规则可以在包还是空的时候就装上。

所以顺序是：先包，后模块，模块留到真的要分开部署那天。而让"那天"仍然可能的，是五件在第一天就
免费的事，每一件本篇都在量：

| 第一天做 | 为什么不能等 |
| --- | --- |
| 建 `api` 包，哪怕它是空的 | 最后建等于在耦合已经发生之后建 |
| 装上 `BoundedContextRules`（一行） | 装晚了要先还债才能装上 |
| 表前缀 `s24_<context>_` | 这是"有没有人跨界查过"唯一可机械回答的形式（§4） |
| 自己的 migration 文件 | 列加进既有 migration 的上下文，走的那天没有东西可搬 |
| 每个包一份 package-info | 五个新包，每个存在的理由正是事后没人写下来的东西 |

包结构就是那五个，值得写下的是**创建顺序**：`api` → `domain` → `application` → `infrastructure` →
`interfaces`。api 排第一不是形式主义，它是别人唯一能碰的包。

## 2. api 必须暴露什么，不得暴露什么

coupons 的契约是四个类型：标识 `CouponCode`、同步问答 `CouponQuotes` + `CouponQuote`、外界可以调用的
一个动词 `CouponRedemptions`。

不在里面的，以及理由（每条都写在 `coupons/api/package-info.java` 里）：聚合 `Coupon`（发布它等于发布
它的不变量）、仓库端口 `Coupons`（能 load 到聚合的调用方是在共享模型，不是在用契约）、命令
`IssueCoupon`（那是本上下文的用例，它的字段名和校验注解不是承诺）、`CouponKind`（它决定的算术
`CouponQuote` 已经算完了，导出它就是邀请调用方自己算一遍）。

**机械判据只有一句：api 不依赖自己上下文里的任何东西。它是一片叶子。**

这条库里没有。`BoundedContextRules` 管的是"别人从前门进来"，不管 api 自己往回摸什么。一个装着
`Coupon` 的 `CouponQuote` 会**完美通过库的规则**并且把模型发布出去——调用方离聚合只有一次字段访问，
而里面每条不变量都变成了承诺。实测（负向对照 3）：给 `CouponQuote` 加一个吃聚合的静态工厂，
`theapiPackagesAreLeaves` 红 1 个，库的规则全绿。

这也是最值得抄走的一条：它把"契约要小"从愿望变成编译期事实，并且让 api 包变得可评审——里面无论有
什么，它不会拖着别的东西。

还有一条不对称值得注意：ordering 的契约只有一个事件，比 coupons 小得多。**一个上下文发布的是别人
需要的东西，不是自己的投影。** 没人向 ordering 同步提问，所以那里没有端口；没人需要持有类型化的
order id，所以那里没有发布标识。两者都是"等有人要的那天再加"，而那是个值得当场做的决定。

反面样本也留在树里：`inventory.api.StockReserved` **没有任何消费者**。留着它正是为了对照那个诱人的
习惯——先给每个聚合发一整套事件，理由是"总会有人要"。每一个都是一份承诺，而这个 sample 正扛着一份
它并不需要的。诚实的规则是：**一个事实在第二方出现时才成为已发布事件，不是在那之前。**

## 3. 共享内核放哪，什么不该进——以及库规则的一处冲突

`Money` 在 `sharedkernel/api/`。这不是我挑的，是库的规则推出来的：
`BoundedContextRules` 把基础包下每个直接子包都当成一个上下文，`sharedkernel` 也是其中之一，
所以别人只能经 `sharedkernel.api` 用它。

实测（负向对照 2）：把 `Money` 从 `sharedkernel.api` 挪到 `sharedkernel`，
`contextsAreEnteredThroughTheirApi` 红 **82 处**。

而这个被迫的形状恰好是对的：**共享内核是整个代码库里最"已发布"的东西**——几个团队依赖它，谁都不
拥有它，改它是一场谈判。给它一个 published-contract 包正好说明这件事，也留出了共享内核绝不该有的
那个部分的位置：没有 `sharedkernel.domain`，也不会有。有私有内部的共享内核就是共享模型，而共享模型
正是限界上下文用来避免的东西。

**什么不该进（写在 `Money` 的 javadoc 里）**：`CouponCode`（只有 coupons 决定什么是合法码，
ordering 没有发言权——两个上下文都在用，仍然不是共享内核）；`OrderStatus`（ordering 自己的词汇，
共享它就等于 ordering 加一个状态要先问人，而状态正是生命周期会长出来的东西）；`Clock`、id 生成器、
Jackson module（那是基础设施不是语言，属于组合根）；任何背后有仓库的东西。

**判据的机械形式：共享内核不依赖任何上下文，它是一片叶子。** 品味说 `Money` 行 `OrderStatus` 不行；
这条规则不需要谁同意就能说同一句话——它一旦需要知道某个上下文，它就是那个上下文的类型披了个共享的名字。

### 3.1 库规则的冲突（issue-00170）

`CouponCode` 和 `Money` 都是值对象，都必须在 `api`。而
`BuildingBlockRules.domainBuildingBlocksShouldResideInDomain()`（在无参的 `all()` 里）要求每个
`@ValueObject` 住在 `..domain..`。**两条规则同时开着时这两个类型无解**，实测直接红两处。

所以 sample 把 `@ValueObject` 摘掉了，并因此**同时失去** `valueObjectsShouldBeImmutable`——恰好是最
暴露在外的两个类型失去不可变性检查。本地补法是
`ArchitectureTest.thepublishedTypesAreStillImmutable`（`..api..` 的顶层类必须只有 final 字段），
它比注解弱，因为要每个项目自己写一遍。

值得注意的是**库自己已经解过一次同样的问题，只差值对象这一对**：`domainEventsShouldStayInDomain`
（内部事实留在 domain）与 `integrationEventsShouldResideInApi`（已发布的事实住在 api）就是这个区分。
详见 [[issue-00170-a-published-value-object-cannot-satisfy-both-archunit-rules]]。

## 4. 表前缀：唯一能机械回答"分得开吗"的东西

清单最后一问是"何时该独立、什么必须先改"，而诚实的答案是：**代码通常在有人问之前就已经决定了，
用一次 join。**

一个跨上下文的 join 能跑、比问一句快、不需要接口，并且**通过所有 ArchUnit 规则**——因为 ArchUnit 读
Java，而表名是一个字符串。

所以 `TableOwnershipTest` 去读那些字符串：走一遍 mapper 接口，从 MyBatis 注解里把 SQL 抠出来，
找出每个 `s24_*` 标识符，拿前缀和 mapper 所在的包比。它也检查 `@TableName`——那是另一处能跨界的地方，
一个行类指向别人的表，哪里都没有写 SQL。

实测（负向对照 5）：给 `CouponMapper` 加一句"就一个 join"去 `s24_ordering_order` 求和折扣，
**`TableOwnershipTest` 红 1 个，10 条 ArchUnit 规则全绿。**

它故意很糙——注解值上的一个正则。XML mapper、字符串常量、运行时拼的 SQL 它都看不见。对这一点的回答
不是把它做聪明：**同样糙的检查用在 migration 文件上、或者用一次仓库级 grep 也能抓到那些，
而一条会跑的糙检查比一条不存在的周密检查值钱。**

它也自带一个"这检查真的看了东西"的断言（`thecheckActuallyReadsSomeSql`），因为一个什么都没扫到的
扫描器永远是绿的。

## 5. 第一条集成：两个都要，按"决定还是记录"分

清单问用事件还是同步调用。有用的问法不是哪个更现代，而是**这个答案是用来做决定的，还是只用来记录的**：

- **报价是调用。** 订单没有折扣就定不了价。答案是一个正在做的决定的输入，所以没有异步版本——一条
  晚到的消息无法参与一个已经做完的选择。而且客户刚敲了一个码，欠他一个和价格同一口气里的回答；
  让他靠比较两个数字发现被拒，是会产生工单的设计。
- **兑换是事件。** 它是一个已经做出的决定的后果，没人在等它，而且**它绝不能让订单失败**。所以它在
  commit 之后发生。

实测的几条：百分比折扣与拒绝理由都在下单响应里；过期码和未知码都是**答案**而不是异常（抛异常会把
客户的手误变成异常路径，更要紧的是会让一个外部上下文的失败决定本上下文的用例成不成）；
报价**不消耗任何东西**（连报四次，`redemptions` still 0——客户改四次购物车不该烧掉一次性券）；
兑换收据记的是**实际减掉的金额**而不是券面值；重投同一个订单只计一次。

还有一条不那么显眼但重要：**重读订单不会重新报价。** 券后来改了 90% off，订单读回来还是当初的 5%。
当初谈成的价格是 ordering 的事实；券现在的状态不是。

### 5.1 一次预测失败，纠正后的结论更有用

进去时我预期：一次性券会给两个订单都打折，因为报价不持有任何东西。**实测不是。**
断言直接红：第二个订单拿到 0 折扣。

原因是每个订单在**自己的命令里**重新报价，而第一个订单的兑换在第二个被定价之前就已经落地了。
所以窗口窄得多——**不是"两个订单之间",而是"一个订单的报价与兑换之间"**。要两个客户同时结账才成立，
一个接一个不会。

纠正后把它拆成两个测试：一个量顺序路径的自我纠正（第二单被报价时就被拒，拿到理由），另一个直接在
边界上量那个真实存在的窗口（报价通过 → 别人先用掉最后一次 → 兑换被 REFUSED，且**什么都没写**，
因为决定在内存里被丢弃了）。

那个窗口的三个处理方式，以及为什么选了第三个：**报价时占用**（对，但把读变成了写，每个被放弃的
购物车都要释放 + 超时，等于在一次定价调用里养一个小流程管理器）；**在订单事务里同步兑换**（也对，
但把两个上下文的聚合放进一个事务，正是边界要防的耦合，而且分开部署那天变成分布式事务）；
**接受它并让它可见**（本篇：拒绝被返回并记日志，收据表里一次兑换对两个打折订单，可对账）。
选哪个取决于一张券值多少钱，那是业务问题。**不可谈判的是知道自己发的是哪个。**

## 6. 何时独立、什么必须先改

"何时"是容量与归属的决定，没有测试能替谁做。"什么必须先改"是当下代码的性质，可测量，而且趁修起来
还便宜的时候量出来，比在拆的过程中发现值钱。四个答案：

1. **边界本身不用改。** 它已经是接口，实现可以换。`SplittingOutTest` 用四行把整个 coupons 上下文换掉，
   ordering 毫无察觉（拿到的是 stub 的固定折扣，而且一张券都没被签发过）。
2. **事务不能再跨过那次调用。** 实测：报价发生在 ordering 的**写事务里面**。今天不是 bug——进程内它就是
   一次方法调用，加入调用方的事务不花钱。跨网络之后它是一次远程调用握着一个数据库事务，正是 S28 量过的
   那种"一个慢依赖变成连接池故障"的形状。要改的不是接口，是**在开写事务之前给购物车定价**,
   只动 `PlaceOrderHandler` 一个地方。
3. **调用方需要一个"没答案"的策略,而现在没有。** 实测：报价不可用 → **整个订单失败,而且一行都没落库**。
   对进程内调用这是对的（同 JVM 的 bean 抛异常是 bug 不是天气），跨网络之后完全错。接口已经为修它留好
   形状（`quote` 返回拒绝而不抛），缺的是策略本身,而那是业务决定：coupons 挂了意味着"不打折"还是
   "不下单"？sample 不替谁编这个决定,它量出这个决定**还没做**——这正是拆之前该知道的事。
4. **不能有环,不能有共享表。** 两者已经被守着（§4 与下面），这也正是它们值得在**上下文诞生那天**装上
   而不是在它离开那天装上的原因。

### 6.1 环：库的规则允许它，Maven 不允许

库的规则允许 ordering 依赖 `coupons.api` **并且** coupons 依赖 `ordering.api`——两边都走前门。什么都不
报警，什么都不坏，而两个上下文从此分不开：**有人要把它们做成两个 Maven 模块的那天,Maven 拒绝循环依赖,
而且没有增量的出路。**

所以本篇把异步那半放在 `s24.contextmap` 而不是放在 coupons 里。这个位置不常见，负向对照 1 量的正是它
买到了什么：把订阅者搬进 coupons（**那个通行、且完全站得住的重构**）→ `thecontextsFormNoCycle` 红 1 个，
**库的每一条规则全绿。**

`contextmap` 在库规则下完全合法，理由很平常：它只碰 `..api..`。它不是限界上下文，不持有模型；
如果哪天里面出现了一条规则，那条规则属于某个上下文，问题只是属于哪个。它也是**拆分那天会消失的包**——
里面的东西变成一边的 broker 订阅和另一边的 HTTP 客户端。

### 6.2 domain 不许知道另一个上下文存在——库允许，本篇不允许

`ordering.domain` → `coupons.api` **通过库的规则**。它确实是走前门。而它仍然是错的依赖，理由和封装
无关：**一个持有别的上下文端口的聚合，是一个会超时、会重试、会中途失败的聚合。** 跨上下文协作属于
application 层，因为那是唯一存在事务边界与失败策略可供推理的地方。

实测（负向对照 4）：给 `Order` 加一个 `repriceWith(CouponQuotes)`，
`nodomainKnowsAnotherContextExists` 红 1 个，`contextsAreEnteredThroughTheirApi` **绿**。

代价是可见的，而且 sample 把它写出来：`Order` 把券码存成 `String` 而不是发布出去的 `CouponCode`，
因此丢掉了那个类型自带的校验，只能相信 application 层在入口处校验过（`PlaceOrderHandler` 里确实做了）。
这个交易是明知的，不是疏忽。

好处也是可测的：`OrderTest` 与 `CouponTest` 各自在**完全没有对方上下文**的情况下跑——折扣是手写的值，
金额是参数。"模型需要一个 stub 才能测"是边界不再是边界的征兆。

## 7. 六条规则，抄走的顺序

| 规则 | 来处 | 补的洞 |
| --- | --- | --- |
| `AiPersimmonDddRules.all()` | 库 | 分层与构造块，与上下文无关 |
| `BoundedContextRules.dependOnEachOtherOnlyThroughApi(base)` | 库，一行 | 别人只能从前门进 |
| `EventRules.integrationEventListenersShouldResideInAdapter()` | 库，opt-in | 事件订阅者是入站适配器——**即使今天没有线** |
| `EventRules.integrationEventsShouldResideInApi()` | 库，opt-in | 已发布的事实住在契约里 |
| **`theapiPackagesAreLeaves`** | 本篇 | 契约不能拖着模型（§2） |
| **`nodomainKnowsAnotherContextExists`** ×2 | 本篇 | 聚合不能持有别人的端口（§6.2） |
| **`thecontextsFormNoCycle`** | 本篇 | 环，Maven 会拒而 ArchUnit 不会（§6.1） |
| **`thesharedKernelIsALeaf`** | 本篇 | 共享内核的机械判据（§3） |
| **`thepublishedTypesAreStillImmutable`** | 本篇，被 issue-00170 逼出来 | 补回摘掉注解后丢失的不可变性 |
| **`TableOwnershipTest`** | 本篇 | 一次 join，ArchUnit 看不见（§4） |

顺序上最值得先装的是 `theapiPackagesAreLeaves` 与 `thecontextsFormNoCycle`：一个让契约保持可评审，
一个保住"以后能拆"这个选项，而两者装上的成本都是几行。

顺带一条：组合根 `S24Application` 直接坐在基础包里而不是某个子包里，这不是审美——库的规则跳过没有
上下文段的类。放在 `s24.boot` 会变成一个叫 `boot` 的上下文，依赖所有人，而规则会理直气壮地报它。

## 8. 负向对照（逐个单跑，逐个量）

一次只改一处，跑完量红数，从 scratchpad 备份还原（不用 `git checkout --`），还原后逐个 `diff -q` 核对。

| 改动 | 实测 | 值得注意的是 |
| --- | --- | --- |
| 订阅者搬进 coupons（通行重构） | **1 红 / 44** — `thecontextsFormNoCycle` | **库的每条规则全绿**。这就是 §6.1 那个位置的全部理由 |
| `Money` 挪出 `sharedkernel.api` | **1 红 / 44**，`contextsAreEnteredThroughTheirApi`，**82 处违规** | 共享内核为什么必须有 api 层 |
| `CouponQuote` 加一个吃聚合的工厂 | **1 红 / 44** — `theapiPackagesAreLeaves` | 库全绿；契约发布模型是库看不见的 |
| `Order` 加 `repriceWith(CouponQuotes)` | **1 红 / 44** — `nodomainKnowsAnotherContextExists` | 库全绿；聚合持有别人的端口是合法的 |
| `CouponMapper` 加一句跨表 join | **1 红 / 44** — `TableOwnershipTest` | **10 条 ArchUnit 规则全绿**。ArchUnit 读 Java，表名是字符串 |

五个对照里有**四个**的红都来自本篇自己加的规则，而库的规则在那四次里全绿。这不是说库的规则不好——
它们各自都对，且都在守着别的东西。它是这一篇最该带走的结论：**"只经 api 依赖"是必要的，远不是充分的。**

## 9. 没做的事

| | |
| --- | --- |
| 真的拆成两个模块 | §1 的立场就是"留到那天"。拆的机械过程（pom、reactor、共享内核变成第三个模块）是 scaffold 的 multi-module 已经展示的形状 |
| 拆成两个服务 | §6 量的是"什么必须先改"，不是改完之后。跨进程那一侧是 S4/S6/S21 的题目 |
| ACL / 防腐层 | 本篇三个上下文是同时被设计的，语言相容。语言不相容时要防腐层，那是 S25 的题目 |
| 上下文映射的其它模式 | 只演示了 customer-supplier（ordering 用 coupons）与 shared kernel。conformist、separate ways、partnership 没碰 |
| 多租户、审计、缓存 | 与本篇正交，各自在 S13 / S14 / S26 |
| 券的读侧 | 没有"列出我的券"这类查询，因为读侧契约是 S20 的题目，加进来只会稀释本篇 |
| 并发下的报价—兑换窗口 | §5.1 在边界上直接量了那个窗口；用两个线程真并发地复现它没做，因为那会是一个时序测试，而窗口本身已经被确定性地量到了 |
