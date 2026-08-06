---
id: analysis-00036-samples-schema-migration
type: analysis
status: draft
parent: analysis-00014-ddd-samples-scenario-catalog
---

# S23 Schema 演进与数据迁移

对应 sample：`aipersimmon-ddd-samples/s23-schema-migration`（一个部署单元、两个限界上下文、26 个用例）。
场景清单见 [[analysis-00014-ddd-samples-scenario-catalog]]。

## 0. 本篇定位

两个上下文共用一个库，框架自己也有针对同一 schema 的 migration，而其中一张表在上线之后改了三次形状。
就这些。

## 1. 三套 migration，一个库

| 归属 | location | 历史表 | 谁跑 |
| --- | --- | --- | --- |
| ordering | `db/migration/ordering` | `flyway_schema_history_ordering` | Boot 自己的 Flyway（`spring.flyway.*`）|
| billing | `db/migration/billing` | `flyway_schema_history_billing` | `MigrationConfiguration`，第二 |
| 框架 | 在 aipersimmon 各 jar 内 | `flyway_schema_history_aipersimmon_outbox` | `MigrationConfiguration`，最后 |

**两个上下文都有 V1**，这就是重点。它们的版本号是两拨人为了互不相干的原因分配的，所以"哪个 V1 在前"
没有答案——Flyway 也这么认为：把 location 指向它们的共同父目录，它直接拒绝并报
*"more than one migration with version 1"*（实测）。共用一个版本空间会让每次改表都变成一次谈判，
也让"只挑一个 release 上"变得不可能。

没人占用默认名 `flyway_schema_history`。留给第一个上下文（它一定会拿到，因为当时只有它），第二个上下文
就会发现最显然的名字已被占用，而第一个上下文的历史与"这个应用的历史"再也分不开。

## 2. 第二个上下文会把你带进的那个坑

库默认装自己的 `FlywayMigrationStrategy`：先跑消费方的 migration，再跑框架组件的。那个 bean 是
`@ConditionalOnMissingBean`。**第二个上下文逼你定义自己的 strategy，而你一定义，库那个就退让，
并把组件 migration 一起带走。**

在犯错的那一刻**没有任何提示**——从库的角度看，提供了 strategy 的消费方就是接手了这件事，这个读法是
合理的。它在一个 bean 之后才被抓住：outbox 的 schema validator 拒绝启动。所以底线是一次启动失败，
而不是一个缺表的生产库。

**但那条消息指向的修法是错的**：它说把 `outbox` 加到 `aipersimmon.ddd.flyway.components`，而本应用
里它本来就在。真因是没有调用 migrator。`StrategyTrapTest` 两半都测了。防它的是一个习惯：
**自己写的任何 `FlywayMigrationStrategy`，最后一行是 `AipersimmonFlywayMigrator.migrate`。**

而且只有框架组件带 validator。**你自己的上下文**如果 migration 悄悄没跑，留下的是一个能启动、
ordering 一切正常、第一个人碰 billing 就 500 的应用。

## 3. 没人预料到的那处不对称：一行 baseline

ordering 的历史里是 `1, 2, 3, 4`。billing 的是 `0, 1` —— 一行 **version 0 的 baseline**，因为轮到它跑
的时候 ordering 的表已经在了、schema 非空。这也正是 baseline 版本必须是 `0` 的原因：写 `1` 会把
billing 的 V1 标成已应用，于是 `s23_invoice` 永远不会被创建——静默地，在一个看起来没问题的 schema 上。

这一条是**先写错、被断言打红才发现的**（原本断言 billing 只有 `1`）。它是"跑在第二位"的性质，不是配置
出来的，所以只有真的跑一次才知道。

## 4. 三步走，一步一步跑

`ship_to` 原本是一个自由文本列，现在是 `ship_to_street` + `ship_to_city`。三个 migration，而
`MigrationStepsTest` 是**在表里已经有数据的情况下**驱动它们的，每次停在一次部署会到达的那个版本。
把四个 migration 一次性应用到空库——正常启动干的事——只能证明最终形状可达，完全不能证明到达它的路径
是安全的。

| 步 | migration | 必须成立的事 |
| --- | --- | --- |
| **扩** | V2：加可空列 + 回填 | 仍在运行的旧代码继续插入成功 |
| **部署** | *不是 migration* | 那个停止写 `ship_to`、开始写拆分列的 release |
| **缩** | V3：填 null、加 NOT NULL、删 `ship_to` | 再没有东西写旧形状 |

中间那步不在 `db/migration` 里，而它正是被跳过的那步：两个 migration 看起来像一对，就被一起部署了
——而那恰是这个模式要避免的故障。

**V3 是"等待"所在的地方。** `ship_to` 一删，把**应用**回滚到读它的那个版本就不再可能了——数据没了。
所以次序不是"扩、缩、完成"，而是"扩、部署、**等到你确定不会回滚**、缩"，而那个等待以天计。

两处细节是测试专门钉住的：

- **V2 的回填必须容忍自己的数据。** 拆分故意写得很朴素（第一个逗号之前是街道），因为真实的自由文本列
  里有没人预料过的行。那些行落到显式的 `UNKNOWN` 上——一个记在 schema 里的决定，而不是一次沉默，
  这样以后有人能找到它们并手工修，而他们确实得手工修。
- **V3 先填 null 再加约束。** 扩窗口期间旧代码写的行没有 city。先加 NOT NULL 的 migration 恰好会在
  那些行上失败——而那种失败只在"部署期间有真实流量"的环境里出现。

## 5. 哪种回填走 SQL、哪种走命令通道

判据短到能记住：

> **把行里已经有的字节重新表述一遍是 SQL。做任何判断、或者必须告知任何人，是命令。**

本篇一个一样：

| | V2 的地址拆分 | V4 的 `handling` |
| --- | --- | --- |
| 做什么 | 拆一个已经在行里的字符串 | 应用一条规则 |
| 需要领域知识 | 不 | 要——数量阈值 + 一份"偏远城市"清单 |
| 有人要被告知 | 没有（没有可观察的东西变了）| 有——多年前的行现在有了新含义 |
| 写成 | SQL，在 migration 里 | 命令，走总线 |

所以 V4 加一个可空列然后**停下**。走命令通道买到的四样东西，`BackfillChannelTest` 各测一条：

- **规则只有一份。** 一条回填的历史行与一条同输入的新下单，handling 相同。写在 SQL 里就会是一个
  `CASE WHEN` 带着自己那份拷贝（含城市清单），从承运商新增一个岛的那天开始漂移。
- **公告，与变更同一个事务。** `UPDATE` 没有人可以告知：没有事件、没有版本、消费者无从知道自己的副本
  已经错了。这里 outbox 行与列值一起提交，所以被中断的回填不会"改了没说"也不会"说了没改"。
- **幂等，在聚合里。** `decideHandling()` 无事可做时返回 false，所以跑第二遍不改也不发。这不是锦上添花：
  大表上的回填一定会被重启，而不可重复的步骤会把一次重启变成一个数据问题。
- **分页。** 一次调用 = 一个事务、一页、一个数字；调用方循环到 0。把整表读进内存的回填能工作到表大到
  值得关心为止——而那正是有人去跑它的时候。

聚合的不变量是附赠的：回填写不出领域会拒绝的状态。

### 5.1 `NULL` 表示"还没判定"，不能给默认值

V4 里写 `DEFAULT 'STANDARD'` 只要一行，会在整表上持锁，并且对每一条本该加急的历史订单断言一件假事。
它还会拿掉回填找到自己工作的能力。读端点在未判定时返回 `null` 而不是猜——一个 API 要容忍到回填跑完为止
的状态。

**给新列填一个看起来合理的默认值，是丢掉"本来就不存在的数据"最常见的方式。**

## 6. migration 不是契约变更

`OrderPlaced` 是 version 1，跨四个 migration 一直是 version 1。它的 `shipTo` 仍是一个字符串，尽管表
现在有两列。

这不是懒。**已发布的契约不是表的投影**，所以结构性 migration 不是破坏性变更，V2 之前就在读它的消费者
不受 V2/V3 影响。要是事件是从行生成的（"直接把实体序列化"那条捷径），这次拆分就会静默地变成一次广播给
所有人的破坏性变更，没有版次提升、没有 upcaster，而第一个症状出现在别人的服务里。

反过来也成立，这也是 S21 独立存在的原因：契约变更也不是 migration。两条时间线独立，把它们绑在一起
才是"部署必须同时"这个错觉的来源。

## 7. `clean` 为什么已经是关的

`clean` 会删掉 schema 里的每一个对象。它必须默认关，理由不是"有人会在生产里故意敲它"，而是凌晨三点
"migration 卡住了，我直接重置一下"是人会有的念头，而一个可用的命令终究会被伸手去拿。

值得写成测试而不是一句话，是因为本应用有三份 Flyway 配置，只有一份由 `spring.flyway.*` 配置。
Boot 那份（`clean-disabled: true`）和代码里 new 出来的那份（Flyway 自 9.x 起的自带默认，这也是
`MigrationConfiguration` 和库的 migrator 都不设它的原因）都拒绝。第二条断言的价值在于：
Flyway 哪天改了主意，它会告诉你。

## 8. 两个上下文、一个 datasource、零共享 schema

共用一个库是部署事实；共用 schema 是建模错误。所以：

- **没有跨上下文外键。** `s23_invoice.order_id` 不引用任何东西。跨上下文外键是部署期耦合：它让两张表
  再也拆不开、让 billing 的 migration 依赖 ordering 的先跑、并且让一个上下文里的删除被另一个上下文
  拥有的规则拒绝。
- **两个方向都不 import。** billing 把 ordering 的 id 当普通 `String`；import `OrderId` 就会让 billing
  依赖 ordering 的 domain。用库那条 opt-in 的跨上下文 ArchUnit 规则钉住——共用 datasource 恰恰是这条
  规则值得打开的场合，因为编译器不会介意，而数据库会在以后有人想拆它们的时候介意。

## 9. 五个负向对照（逐个单跑，逐个量）

| # | 改动 | 实测 |
| --- | --- | --- |
| 1 | V2 把列加成 `NOT NULL DEFAULT 'UNKNOWN'` | 3 红，**比预期更有意思**：旧代码的插入并没有失败，而是成功并拿到 `UNKNOWN` ——地址被**编造**而不是被拒绝；回填也同时失效（`WHERE ship_to_street IS NULL` 不再匹配任何行）。扩步里的 `NOT NULL DEFAULT` 是静默的数据损失，不是响亮的失败 |
| 2 | V3 去掉 `SET NOT NULL` 之前的填充 | 2 红，migration 以 SQL state 23502 失败在扩窗口产生的那些行上——正是只在有流量的环境里才出现的那种失败 |
| 3 | V4 加 `DEFAULT 'STANDARD'` | 恰好 1 红：migration 的测试抓到了，回填的测试没有——因为它们自己构造未判定行。这个分工是刻意的：一个测 migration，一个测回填 |
| 4 | `MigrationConfiguration` 跳过 billing | 4 红（跨 2 个类），而另外三个测试类全绿——**这恰是发现本身**：除了表级断言，没有任何东西会注意到你自己上下文的 migration 没跑 |
| 5 | 下单路径自己拷一份 handling 规则 | 恰好 1 红，而聚合的单测全绿。**能抓住"规则被复制"的测试，只有用同一组输入跑两条路径的那个** |

## 10. 库的问题：一处诊断误导，没有新缺陷

没有发现新的库缺陷。记一处**诊断误导**（§2）：定义了自己的 `FlywayMigrationStrategy` 之后，
框架组件的 migration 不再运行，而 schema validator 给出的修法是"把组件加进
`aipersimmon.ddd.flyway.components`"——那一行往往已经是对的。真因（strategy 没调 migrator）在消息里
一个字都没提。

严格说这不算缺陷：`@ConditionalOnMissingBean` 的语义就是"你接手了"，而 validator 也不可能知道你的
strategy 里写了什么。但它值得记，因为**代价是排查时间，而承受它的人手里那条线索是错的**。可以改进的
方向有两个，都不大：validator 的消息里加一句"若你定义了自己的 FlywayMigrationStrategy，请确认它调用了
AipersimmonFlywayMigrator"；或者框架那个 strategy bean 在退让时 log 一条 INFO 说明它退让了、以及后果。
后者更便宜，也把提示放在了犯错的那一刻。暂不单独开 issue：它是文案与一行日志，而不是行为缺陷。

## 11. 没做的事

| | |
| --- | --- |
| 跨服务的 migration 顺序 | 两个库、两次部署，规则与 S21 对契约说的同一条：宽容的那一侧先走。推理得出，未实跑——加第二个服务不会比 S4/S21 已经展示的更多 |
| 拆分共享库 | 两个上下文共用 schema 的终局。需要第二个 datasource 与一次数据搬迁，那是项目而不是 sample |
| 在线建索引 | `CREATE INDEX CONCURRENTLY` 不能在 Flyway 的事务里跑，需要 no-transaction 处理。规模上去是真问题，但那是 Flyway 配置题而非 DDD 题 |
| 蓝绿与滚动部署机制 | 扩窗口的**长度**是部署属性。本篇展示的是那期间必须成立的事 |
| 回填吞吐 | 有分页，没有限速、没有进度表。S11 owns 定时与批量入口；跑几个小时的回填希望把可恢复性记在别处，而不是记在它正在填的那一列上 |
| 在既有库上引入框架 | `baseline-on-migrate` 默认开就是为了这个，本篇只是顺带碰到（billing 的 baseline 行）|
