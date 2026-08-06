---
id: analysis-00039-samples-soft-delete-and-erasure
type: analysis
status: draft
parent: analysis-00014-ddd-samples-scenario-catalog
---

# S27 软删除、数据保留与擦除

对应 sample：`aipersimmon-ddd-samples/s27-soft-delete-and-erasure`（一个部署单元、42 个用例）。
场景清单见 [[analysis-00014-ddd-samples-scenario-catalog]]；用到的审计组件见
[[analysis-00038-samples-operation-log]]（S14）。

## 0. 本篇定位

一张表上放三种"删除"，好让它们能被对比而不是被描述：

| | 是什么 | 留下什么 | 可逆 | 谁知道 |
| --- | --- | --- | --- | --- |
| `status = CLOSED` | **领域状态** | 状态 + 原因 | 是，对称 | 聚合、规则、审计行 |
| `deleted`（`@TableLogic`） | **基础设施开关** | 行还在，应用看不见 | 是，但要手写 SQL | 只有审计行 |
| `erased_at` + 墓碑值 | **合规擦除** | 行还在，人不在了 | **不可逆** | 聚合、审计行、下游 |

判据（清单问的第一个问题）不是技术性的：**有没有业务规则读它？有没有人会撤销它？有没有人会要一份
清单？** 三个都否 → 基础设施开关。任何一个是 → 领域状态，进状态机，因为那里能解释。

而这个判断错了有**机械后果**，不只是建模后果——第 2 节量了。

## 1. 擦除不是删除，这是全篇的支点

擦除之后行还在、id 还在、版本还动了。走掉的是个人数据，被换成**模型仍然接受的值**：
`erased+<id>@invalid`（RFC 2606 保留域，永远不可能解析）、`(erased)`、phone 置空、`erased_at` 记下时刻。

为什么行要留：**它的存在本身是证据**。"这个人存在过并且被擦除了"要能被证明，而这只有在 id 是服务自己
铸的代理键时才成立。要是当初拿邮箱做主键，擦除就等于删行，连带删掉每一条审计与账目对它的引用——
义务与"证明履行了义务"直接冲突。**这是能扛住合规审查的代理键论证。**

墓碑必须**按 id 唯一**。邮箱列上有唯一索引，所以常量墓碑会让**第二次**擦除撞唯一键。
**实测**（把墓碑改成常量）：2 红，其中一条正是
`duplicate key value violates unique constraint "uq_s27_customer_email_live"`——一个每个库只能用一次的
合规操作。

墓碑还必须是**领域词汇里能表达的值**，所以 `EmailAddress` 的校验刻意放松。一个严到拒绝墓碑的值对象会
逼着擦除绕过模型去写 SQL，然后聚合的不变式就不再适用于它被留下的那个状态。

## 2. `@TableLogic` 与库的整根覆盖写入：一行从没跑过的代码

`ClearedColumns` 会为 `toRow` 留空的每一列强制写入 `column = null`——因为保存聚合从不是部分更新，
少写一列就是"聚合清空了它"却把旧值留在库里，而且一切都报成功。删除标记恰恰是 `toRow` 永远留空的列
（聚合不拥有它）。两个机制在这里相遇，库对此有**一行**：

```java
|| (tableInfo.isWithLogicDelete() && field.isLogicDelete());
```

本篇之前，**整个仓库里 `@TableLogic` 只出现在这一行**——这个排除从来没被跑过。现在跑了：

- **带注解**：普通保存不碰标记（`anordinarySaveDoesNotTouchTheDeleteFlag`）；而聚合真正清空的列**照旧被
  强制**（擦除把 phone 置空，`theemptiedColumnsAreStillForced`）。排除是窄的，不是"跳过所有 null"。
- **不带注解**（测试作用域的 `HandRolledFlag`，把标记当普通列自己维护）：同一处省略变成
  `SET ..., deleted = null`，撞 `NOT NULL` 失败。

**实测的意外之处：把 `@TableLogic` 从真正的行类上摘掉，42 个用例里 22 个红，且全部红在同一个
`null value in column "deleted" violates not-null constraint`。** 不是"被隐藏的行行为异常"，是
**一次写都成功不了**。这是好消息——损坏是即时且全域的，不可能上线。

危险的是 `NOT NULL` 缺席时：语句提交，标记变成既不是 true 也不是 false，`deleted = false` 的过滤器和
`deleted = true` 的过滤器都看不见它，只有知道要找 null 的 SQL 能捞出来。**这一条是从语句推出来的，没有
实测**（本列是 NOT NULL，不想为了证明它去改自己的 schema）——所以它按惯例被标成推论。它也是"删除标记
在任何 schema 里都该是 `NOT NULL DEFAULT`"的理由，无论当下有没有东西会去强制它。

## 3. 逻辑删除与唯一索引

朴素的 `UNIQUE(email)` 分不出活行与隐藏行，于是**隐藏一个客户就把他的邮箱永久占住，本人回不来**。
这是选择逻辑删除的第二常见后果，而在做选择时从来不会被提到。

V1 故意写朴素索引，V2 换成部分索引 `... WHERE deleted = FALSE`。两半都量了：部分索引下被隐藏的客户
**释放**地址（两行同邮箱，只有一行可见）；把老索引装回去，同样的序列被拒绝——而且拒绝理由从调用方看
与"别人占了这个地址"**无法区分**：本人被告知自己的旧地址被占用，没有 SQL 权限的人无法解释。

V2 的迁移顺序也是内容：**先建新索引再删旧的**，否则中间有一个窗口列上无约束，两个活行可能拿到同一地址，
而那是没有任何后续迁移能修的。

**可移植性**写在迁移文件里而不是等人踩：部分索引是 PostgreSQL 的。MySQL 没有，替代做法是把删除标记放进
键里——`UNIQUE (email, marker)`，活行用**哨兵值**、删除后放行 id。**不能用 NULL 表示活着**：MySQL 与
PostgreSQL 一样在唯一索引里把 NULL 当互不相同，于是两个活行能拿到同一地址——与意图正好相反，且在只删
一行的测试里全绿。

## 4. 三种删除是正交的，一个 boolean 装不下

一个"已关闭 + 被隐藏 + 已擦除"的客户是自洽状态：业务结束了关系、运维隐藏了行、监管要求数据消失。
把三者建成一个 `deleted` 标记的人必须先决定它指哪一个，另外两件事就无处记录。实测断言的就是三者同时为真
且各自可读。

## 5. 擦除与 outbox：最硬的那一问，没有舒服答案

一条未发出的 outbox 行里有这个人的地址。擦除之后对它只有坏选项：

- **照发**：在"数据本该消失"的时刻之后，创造一份新副本发给所有消费者；
- **删掉**：它宣告的变更真的发生过，于是每个消费者从此永久错误，且**没有任何东西能察觉**——outbox 存在
  的意义正是让这不可能；
- **改写**：发布契约里塞进从未为真的值，比前两个都糟。

**事后没有正确动作，所以顺序必须事先安排**：先排空，再擦除。`EraseCustomerHandler` 用**拒绝**来强制它，
把一个无法回答的问题变成一个可重试的 409——擦除请求不是同步义务，"等队列空了再试"是对它的完整回答。

这是本系列里**唯一一个读 outbox 的业务命令**，破例的理由写在 `OutboxQueue` 的 javadoc 里：
**队列的内容就是个人数据**。ArchUnit 有一条规则把这个破例钉在这一个类上，好让下一个想读 outbox 的
handler 必须先来改规则、顺手读到论证。

拒绝按**主体**而不是整个队列，否则任何有流量的服务队列永不为空，擦除就从"被排序"变成"不可能"。
`subject` 列是这个窄问题能被问出来的原因，而它有索引（`idx_aipersimmon_outbox_subject_order`），因为
relay 为了保序也要问同一个问题。

**实测**：拒绝时行完全没动（不是半个擦除）；排空后成功；另一个客户的队列不阻塞本次擦除；擦除自己发的
`CustomerErased` 里**不含**邮箱、姓名、电话；第二次擦除既不改日期也不再发一次。
**对照**（去掉闸口）：1 红，擦除径直越过还握着地址的队列跑完。

**本设计的一个洞，写出来而不是糊过去**：进了死信表（S22）的公告不再阻塞擦除——它永远不会被投递——
但它**仍然含着那份个人数据**。死信表也在保留策略之下，所以那份数据的寿命由 S22 的 `purge` 决定，
而不是由这次擦除决定。

## 6. 擦除与 inbox：删干净的直觉在这里纯粹是破坏

清单问擦除对 inbox 幂等键意味着什么。答案是**什么都不意味着，而理由值得说准**：

1. **表里没有任何一列指向人。** 实测直接查 `information_schema`：
   `(consumer, message_key, processed_at, source, tenant_id)`。想删"这个人的 inbox 行"根本没有谓词可写。
   键是生产方铸的消息 id——它标识一次投递，不标识一个主体，所以**它不是个人数据**。
2. **按时间窗口"顺手清一下"会打断唯一的正确性机制。** 实测：吸收一条消息 → 删掉 inbox 行 → 同一条消息
   再来一次 → **被处理第二次，consent 行变成两条**。这不是隐私改善（那些行里没有任何个人的东西），
   是静默丢掉了 exactly-once，而症状出现在生产方恰好重投的那一天，可能是几周后。

而**consent 行要删**：它指名一个人，且没人需要证明某个 consent 曾经存在。两张表挨在一起、答案相反，
这就是全部教训：**行的存在本身是证据的，留；只有内容才是重点的，删。** "把关于这个客户的一切都删掉"
必然会把其中一张搞错。

留着键还有一个额外收益：擦除之后重投的信号**无法重建任何东西**（实测 consent 仍为 0）。要是键被清了，
迟到的消息会给一个已擦除的客户重建 consent 行——擦除会被一条晚到的消息撤销。

## 7. 擦除与审计：两个义务方向相反

审计要留（保留期通常以年计），擦除要删。解法不是聪明的 purge，而是**审计行里有什么，在它被写下的那一刻
就定了**：组件的管道只做去控制字符与长度截断（`Redactor` 的 javadoc 自己说 allowlist 是 definition 的
事），而且**没有 update 端口、没有按 id 删除**——`OperationLogs` 只有 `record`，`OperationLogReader` 只有
`find`。写进去的就在那儿，直到保留期到。

所以本篇的写法是设计时就不写进去：

- 擦除自己的审计行只提 **id 与工单号**，实测不含邮箱与姓名。它必须活过擦除——它是义务被履行的证据——
  所以它绝不能含有擦除本该移除的东西。
- 改邮箱的审计行用 `${mask(input.email)}`，实测记的是 `a***m`。**通行写法"从 A 改到 B" 会把这个人的
  旧邮箱写进一张 append-only、多年保留、没有 update 端口的表**，然后擦除只能去它最不受欢迎的地方伸手。
  真要 before/after，那个地方是审计日志（保留期就是为它写的、内容没人复制），前提是它被 mask 过。
- 隐藏行**必须**被审计，这是使用基础设施开关的**条件**：领域状态自带两条痕迹（状态与原因），而逻辑删除
  什么也不留——没有事件、没有字段、行本身从每次读里消失。这一行不存在，"谁在什么时候隐藏了这个客户"
  就无法回答。

## 8. 库的问题：一条，量出来的

**[[issue-00168-the-audit-classifier-records-every-application-refusal-as-unexpected]]（P2）** ——
擦除被拒（`ApplicationException` + 本上下文的 `ErrorCode`，category `CONFLICT`）在审计行里记成
`outcome=FAILED`、`failure_code=unexpected`、`failure_category=UNEXPECTED`。而 S14 里的领域拒绝
（`DomainException` 子类）记的是 `REJECTED` + 本上下文的码 + `CONFLICT`。**同样是"业务不允许"，只因为抛的
基类不同就分到两个桶。**

原因是 `DefaultFailureClassifier` 的 `instanceof` 链里没有 `ApplicationException` 分支——而它正是库自己为
"a missing aggregate or a conflicting request"（它的 javadoc 原话）准备的基类，也带 `ErrorCode`。后果：
**每一个 `EntityNotFoundException`（→ 404）在审计表里都是 `unexpected`**，挂在 `FAILED` 上的告警被 404
淹没，`failure_code` 与 HTTP problem document 对不上。分类器自己的注释为 Bean Validation 写下过同样的
理由（"inflate the FAILED counter with every bad request"），只是没把这一类算进去。三行可修，模块依赖已
具备（`operation-log-engine/pom.xml:51`）。

`ErasureAndAuditTest.arefusedErasureIsAudited` **断言的是缺陷现状**并指向该 issue——与 S22 的 issue-00165
同一手法：修好就打红，不靠人记得回来收尾。

## 9. 负向对照（逐个单跑，逐个量）

| # | 改动 | 预期 | 实测 |
| --- | --- | --- | --- |
| C1 | 摘掉真实行类的 `@TableLogic` | 删除标记被清 / 相关用例红 | **22 红 / 42**，全部 `null value in column "deleted"` ——不是行为异常，是一次写都不成 |
| C2 | 去掉擦除的排空闸口 | 拒绝那条红 | **1 红**，擦除径直越过还握着地址的队列 |
| C4 | 墓碑改成常量（去掉 id） | 第二次擦除撞唯一键 | **2 红**，含 `duplicate key ... uq_s27_customer_email_live` |

C3（去掉 `consents.forget`）没有单跑：`theerasureForgetsTheConsentsAndKeepsTheKeys` 断言的就是那一次调用的
结果，归因是直接的。

另记一处**过程失误**：`ClearedColumnsTest` 的第一版在事务外调 `save`，被库的
`requireActiveTransaction` 挡住，于是测试从未到达要测的行为——报的是"no active transaction"而不是删除
标记的事。守卫做了它该做的；是我把探测点放错了。包进 `TransactionTemplate` 才量到真结果。

## 10. 没做的事

- **没测 MySQL**（部分索引那段的替代方案是读文档 + 推理，标在第 3 节）。
- **没做保留期清理的实际运行**：`MybatisPlusOperationLogCleanup` 存在且 opt-in，S22 已经量过 purge 的
  分页与索引行为，这里只用到"没有 update 端口"这一事实。
- **没处理死信表里的个人数据**（第 5 节点名的洞）。
- **actor 是固定的服务身份**：actor 从哪来是 S14 的题目，本篇需要的是"审计行里有什么"。
- **没有读侧**：查询契约是 S20 的形状；本篇所有断言直接读列，因为被擦除/被隐藏的行正是端口按设计看不见的。
