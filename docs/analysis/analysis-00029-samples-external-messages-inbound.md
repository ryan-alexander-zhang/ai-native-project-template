---
id: analysis-00029-samples-external-messages-inbound
type: analysis
status: draft
parent: analysis-00014-ddd-samples-scenario-catalog
---

# S5 消费外部系统的消息（非本体系事件格式）

对应 sample：`aipersimmon-ddd-samples/s05-external-messages-inbound`（单模块）。
场景清单见 [[analysis-00014-ddd-samples-scenario-catalog]]；与
[[analysis-00025-samples-integration-events-across-services]] 的对照是本篇的骨架。

## 0. 本篇定位

消息来自**不用这个库的系统**：格式、语义、投递保证都不是我们能定的。与 S4 最大的差别不是"要写个翻译"，而是
**S4 里由库承担的三件事全部回到应用手上**：反序列化、去重、失败分级。

还有一条比"重复"更难、也更容易被跳过的：**乱序**。幂等键只挡重复，挡不住"旧值覆盖新值"。

## 1. 为什么不能用库的消费桥

桥订阅的是本应用 `@Externalized` 事件点名的 topic，并且要求每条记录带齐 `ce_id` / `ce_source` / `ce_type` /
`ce_specversion` / `ce_dataschemaversion`——因为那些正是 inbox 键与 `(type, version)` 目录查找的原料。ERP 一个
都没有，而且不会为我们长出来。

所以 sample 里**故意没有** `aipersimmon-ddd-starter-messaging-kafka`，用的是裸 `@KafkaListener`：

| | S4 里谁做 | S5 里谁做 |
| --- | --- | --- |
| 反序列化 | 桥，按 `ce_type` 查目录 | ACL 手写，映射不了就拒 |
| 去重 | 桥，键 `(ce_source, ce_id)` | **handler**，在命令事务内，且**只在需要的地方** |
| 失败分级 | 桥的三档 | 本 sample 自己的策略，写在一处 |

顺带一个装配上的好处：没有 `@Externalized` 事件，所以
[[issue-00161-the-publisher-guard-misreads-a-consumer-as-a-publisher]] 那条把 outbox 强加给纯消费服务的
启动检查在这里根本不触发——`flyway.components` 只有 `[inbox]`。（该 issue 2026-08-04 已修；S5 本来就
不在它的射程内，因为它订阅的是外系统的消息、不经 `@Externalized` 声明。）

## 2. 防腐翻译放在哪：一个类，一个方向

`ErpProductMessage` 是全服务唯一允许长成那样的类：`sku_id`、`display_name`、价格是个 **decimal 字符串**加币种、
版本叫 `rev`、时间带偏移量、kind 是裸字符串。翻译在 `ErpProductMessageListener` 里一次做完：

- `"9.99"` + `"EUR"` → `999`（cents），币种在此校验，领域里再也见不到币种；
- `sku_id` → `Sku` 值对象，领域从不拿上游的裸字符串；
- kind → 两个**本上下文语言**的命令。

翻译完之后走的是**和 HTTP 入口完全相同的命令通道**：同一条拦截器链（校验、事务、日志、tracing），同一个
handler。这就是"消息驱动与请求驱动只应该在触发方式上不同"，也是不能把 `ErpProductMessage` 往里传的原因。

**未知字段被忽略**（Jackson 默认）是刻意的：上游加字段不该弄坏不读它的消费方。反过来——上游删掉翻译需要的字段
——会变成 null 并被明确拒绝。**这个不对称是对的**。

## 3. 幂等键从哪来：先问这条消息需不需要键

sample 放了两种消息来做对照，因为**需要哪种机制是消息的性质，不是团队的风格**。

| 消息 | 载荷 | 靠什么安全 | 需要去重键吗 |
| --- | --- | --- | --- |
| `PRODUCT_CHANGED` | 绝对状态 + 每商品单调 `rev` | 聚合里 `rev > upstreamRevision` 才应用 | **不需要**（测试断言 inbox 一行都没有） |
| `PRICE_REDUCED` | 相对量（降 10%） | handler 在事务内调 `Inbox` | **必须要**，且只能由上游提供 |

第一种为什么不需要：revision 比较让重投**按内容**成为 no-op。再加一个去重键就是**用第二个机制守护第一个机制
已经保证的性质**，而两个机制意味着两处都要一直正确。

第二种为什么必须要：`"降 10%"` 应用两次和应用一次是不同的价格，没有内容可比；顺序也救不了它——两次投递携带的是
同一条消息。所以 `AdjustProductPriceHandler` 是全部 sample 里**唯一**自己调 `Inbox` 的 handler，而且必须在
handler 里（事务拦截器 200 之内），这样**去重记录与价格变更同生同死**：写在 listener 里的记录会在回滚后存活下来，
把一次没发生的变更永久抑制掉——那正是安静赔钱的那种故障。

### 3.1 键只能来自生产者，替代品全是错的

`msg_id` 缺失时 sample **直接死信**，不猜。理由摆在代码里：

| 想拿来当键的 | 为什么错 |
| --- | --- |
| payload 的 hash | 把两次合法的相同调价合并成一次；上游换个序列化顺序或空白就变了 |
| `(topic, partition, offset)` | 生产者重试、topic 迁移、重放都会改——而这三件正是去重存在的理由 |
| 到达时现铸一个 id | 每次投递一个新的，什么都抑制不了 |

正解是把消息改成**绝对语义**（像第一种那样），那是一次值得去谈的契约变更。**编造身份不是工程决定，是一个安静的
决定。**

## 4. 乱序：本篇的核心

`alateChangeDoesNotOverwriteANewerOne`：先到 rev 7，再到 rev 5，最终留下 rev 7 的值。

三种可选做法，sample 选了第二种：

1. **信上游时间戳**——弱。ERP 自己的节点之间会有时钟偏斜，两次变更可以落在同一毫秒，格式还允许偏移量让"比较"
   变成一个解析问题。
2. **每实体单调计数器（`rev`）**——全序、不依赖时钟。`sharingATimestampDoesNotMakeTwoChangesUnorderable` 把这条
   钉死：两条消息盖着**同一个瞬间**，时间戳比较只能任意挑一个，而"任意"就是"按到达顺序"——恰好是要防的东西。
3. **比对本地版本**——就是第二种的落地方式：`upstreamRevision` 存在聚合里。

上游只给时间戳时，诚实的选项是**用它并接受同瞬间不可排序**，或者去要一个计数器；**不是**把时间戳当计数器用。

### 4.1 `upstreamRevision` 是领域状态，不是管道

它放在聚合里、也进了表，而不是躺在 listener 或 Redis 里：**"我手上是上游哪一版真相"是这个商品的事实**，比任何
一条消息活得久。放在拥有数据的聚合里，才使排序规则由拥有者强制执行。

表上因此有**两个 version 列，含义不同**：`version` 是本行的乐观锁（我们自己两个线程赛跑），`upstream_revision`
是上游的排序令牌（他们两条消息赛跑）。混成一个，一次重放就会看起来像并发冲突。

### 4.2 一个副产品：被越过的消息不是错误

`ChangeOutcome.SUPERSEDED` 是**成功**的结果：不重试、不死信、不写库（连行都不碰，所以 topic 重放不会放大写入）。
把它建模成异常，就等于把正常运行变成一条错误率曲线。所以它以枚举出现在命令的返回值里，并且被 listener 记进
info 日志——**看不到"superseded"的运维会把安静的消费者读成坏掉的消费者。**

## 5. 失败分级：这次是我们自己的

| 到达的东西 | 处置 |
| --- | --- |
| 处理中抛 `DataAccessException` | 原样抛出让容器重试——数据库不可用不是这条消息的错，因它死信＝把一次故障变成永久数据丢失 |
| JSON 解析失败 | 当场死信（字节不会变好） |
| 未知 `event_kind` | **死信，不是跳过**：它可能是本该支持的流量，丢掉就再也没有发现它的凭据 |
| `PRODUCT_CHANGED` 无 `rev` | 死信——没有排序令牌，早晚有一条被越过的消息覆盖真相 |
| 非本币种 / 亚分精度 | 死信——安静地四舍五入别人的钱，镜像就不再是镜像 |
| 相对变更无 `msg_id` | 死信（§3.1） |
| 本服务自己的代码缺陷 | **也死信**，这是刻意的取舍：死信 topic 上的记录可查可重放，而毒丸无限重试会把它后面所有商品的更新一起堵死 |

与库的三档（毒丸立即死信 / `DataAccessException` 无限重试永不死信 / 其余有界退避后死信）**不同**，而且不同得
有理由：库的桥不知道你的消息语义，本 sample 知道。愿意"宁可停滞也不死信"的部署可以反过来配；**不可以做的是让
这件事保持隐式**——Spring Kafka 的默认是"无退避重试十次，然后记一行日志继续",对主数据来说就是静默丢失。

分级本身也放在**一个地方**：ACL 把永久性失败包成 `UntranslatableMessageException`，容器的 error handler 只按类型
路由。分类由"知道自己在翻译什么"的那层做,不是从堆栈形状去猜。

## 6. 这次踩到的三件事，都写进了代码

1. **死信 topic 的名字是个默认值，而默认值不是 `.DLT`。** 六个测试都在找已经被投到 `<topic>-dlt` 的记录。现在
   recoverer 显式命名目的地，与库的约定一致——**名字靠继承的死信 topic，就是会有人找不到的死信 topic**。
2. **会抛异常的 await 不是 await。** `untilAsserted` 只重试 `AssertionError`，别的直接穿出去；于是基于
   `queryForObject` 的辅助方法在行还没到时第一轮就用 `EmptyResultDataAccessException` 把整个等待判死，而被测代码
   是对的。**说不出"还没到"的等待不是等待。**
3. **两个机制都是靠删掉来验证的。** 关掉 revision 比较 → `alateChange...` 与 `sharingATimestamp...` 变红；关掉
   inbox 检查 → `aredelivered...` 变红。**恰好这三条**，这才使它们是在测那两个机制，而不是在测顺利路径。

## 7. 常见错法

| 错法 | 后果 |
| --- | --- |
| 把外来 DTO 往领域里传 | 上游的字段名、单位、币种永久住进你的模型 |
| 只做幂等、不做排序 | 重复挡住了，但一条迟到的消息会覆盖新值——而且没有任何报错 |
| 用上游时间戳当排序键 | 时钟偏斜与同毫秒下退化为"按到达顺序" |
| 排序令牌放在 listener/缓存里 | 拥有数据的聚合无法强制规则；重启即失忆 |
| `upstream_revision` 与乐观锁 `version` 合并 | 一次重放看起来像并发冲突 |
| 把"被越过"当异常 | 正常运行变成错误率；重放会刷出一片告警 |
| 给绝对语义的消息也配去重键 | 两个机制守一个性质，两处都要一直对 |
| 给相对语义的消息不配去重键 | 一次重投就是第二次折扣，而且看不出来 |
| 用 payload hash / offset 当去重键 | 恰好在重放时失效——就是它本该生效的时刻 |
| 去重记录写在 listener 里（事务外） | 回滚后记录存活，永久抑制一次没发生的变更 |
| 未知 kind 静默跳过 | 集成少了一半流量，没有任何凭据 |
| 不设 recoverer | Spring Kafka 默认"重试十次后记日志继续"＝主数据静默丢失 |
| 死信 topic 用默认名 | 记录去了别处，没人在看（本篇实际踩到） |
| `DataAccessException` 也死信 | 一次数据库抖动变成永久陈旧的商品 |

## 8. 本篇不覆盖

- 非 Kafka 的入站（SFTP 落文件、HTTP webhook、JMS）——形状一样（边界翻译、就地分级、只在需要处去重）；回调形态的
  入站边归 S7；
- 我们自己就此对外发事件——本服务没有任何 `@Externalized`，那是 S4；
- 死信重放通道与运维端点——S22；
- 上游格式的演进：他们不会为我们加版本字段。additive 那一档覆盖了
  （`anExtraFieldTheUpstreamAddedIsIgnored`），其余是把 S21 的机械动作用在一个没有版本号的格式上。
