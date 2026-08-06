---
id: analysis-00023-samples-query-contract-paging
type: analysis
status: draft
informs: [analysis-00014-ddd-samples-scenario-catalog]
---

# S20 读侧查询契约：分页、排序、过滤

对应 sample：`aipersimmon-ddd-samples/s20-query-contract-paging`。场景清单见
[[analysis-00014-ddd-samples-scenario-catalog]]。

## 0. 本篇定位

列表查询是最高频的读需求。库为它准备了三种形状（`Slice` / `Page` / `Cursor`）和一个游标序列化模块，
但**一个使用示例都没有**——于是每个项目各自发明：页码、`OFFSET`、客户端传排序字段名、没有上限的
`size`。这些不是风格问题，其中至少两条是**正确性问题**，本篇把它们做成会打红的测试。

写侧在本篇只是布景（一个聚合、两个命令），真正的内容全在读侧。

## 1. 三种形状怎么选

| 形状 | 携带 | 成本 | 什么时候用 |
| --- | --- | --- | --- |
| `Slice<T>` | items + `nextCursor` | **一条语句**，最多读 `size + 1` 行 | 默认。信息流、列表、导出、任何机器读的东西 |
| `Page<T>` | items + `nextCursor` + `totalElements` + `totalPages` | **两条语句**，第二条扫全部命中行 | 有人真的要看总数，且过滤后的集合小到值得数 |
| `Cursor` | 一个不透明 token | — | 不是第三种形状，是前两种共用的位置表示 |

库的 `page/package-info.java` 说 `Slice` 是 "the primary shape ... matching the direction large APIs
have moved"，理由是算术而非风尚：**计数是一次全命中行扫描**，而切片只读 `size + 1` 行。

关键一条：**`Page` 是 `Slice` 加总数，不是另一种翻页方式**——它携带的仍然是 `nextCursor` 而不是页码。
所以两种形状之间切换，客户端"怎么要下一页"的方式不变。

成本这条在 sample 里是**被测量的**：一个 test-only 的 `InnerInterceptor`（挂在生产分页拦截器之外的
order）记录真正发出的 SQL，然后断言

```
slice → 1 条语句，含 LIMIT，不含 OFFSET
page  → 2 条语句，其中一条含 count(
```

sample 的两个端点用命名把判据写进了路由：`GET /orders` 给 `Slice`，`GET /admin/orders` 给 `Page`。
**不是 `/orders?withTotals=true`**——一个响应体形状随查询参数变化的端点，既没法定类型、也没法写文档、
也没法缓存；而且一旦是参数，每个客户端都会发现它然后永远带上。

## 2. 游标里装什么

sample 的 `PageCursor` 有四个字段，每一个都有理由：

| 字段 | 为什么在里面 |
| --- | --- |
| `placedAt` | 排序键的首列。seek 谓词要跟它排序用的键比较 |
| `orderId` | 排序键的尾列（打破并列） |
| `sort` | 换了排序方向再拿旧游标来，比较的语义就变了 |
| `queryFingerprint` | 换了过滤条件，这个位置所在的结果集**根本不存在** |

后两个字段是本篇的重点之一。一个在"看 alice 的订单"时发的 token，被拿去"看全部订单"翻页，**照单执行
会返回一个既不是第一页也不是下一页的东西**，而且客户端不会察觉。拒绝它是唯一不错的答案（sample 给
400 + `ordering.cursor-does-not-match-query`）。

三条容易做错的细节：

**指纹不能用 `hashCode()`。** record 的 hash 在一个 JVM 进程内稳定，仅此而已；而**游标的寿命长于一次
部署**。用 `hashCode` 的话，客户端跨一次发布继续翻页会被拒绝，且日志解释不了原因。sample 用规范化字符串
的 SHA-256 前 6 字节，并且**把值钉在测试里**——因为改算法等于拒绝上一版本发出的所有游标。

**精度要对齐列。** `timestamptz` 存到微秒。游标里若把排序键降到毫秒再存回来，边界那一行会被返回两次
或一次都不返回——这个 bug 只在"两笔订单落在同一毫秒"时出现，也就是只在生产出现。sample 在写入时就
`truncatedTo(MICROS)`，游标里编码微秒。

**不透明 ≠ 加密。** Base64url 挡住的是"客户端解析字段"，换来的是**格式随时可改**的自由，不是机密性。
所以里面不放秘密；需要防伪造的 token 要签名（那是 S2 给请求体做的那套）。

## 3. 稳定排序：全序，而不是"时间有序 id"

场景清单原来的问法是"稳定排序为何依赖时间有序 id"。**照库的契约，这个问法要修正。**

`IdGenerator` 的 javadoc 写得很清楚：

> The returned id is opaque: callers must not parse it or depend on the embedded timestamp being
> present, so a v4 value from an environment without the default implementation remains valid.

也就是说，UUIDv7 的时间性是**索引局部性的收益**（顺序键插在 B-tree 尾部），**不是可以拿来当业务排序的
承诺**。`ORDER BY id DESC` 的真实含义是"大概按创建时间，前提是默认实现装上了"。

游标分页真正需要的是**全序**：一个唯一、不可变的排序键，任何两行都有确定先后。理由很直接——两行在排序
键上并列时它们之间没有定义好的顺序，那么"在这一行之后"就无法确定落在并列的哪一侧，页与页之间开始重复和
漏行。

所以 sample 的排序键是 `(placed_at, id)`：业务要的顺序由业务自己拥有的列表达，**id 的职责是打破并列**
（它能胜任，因为它唯一且不变）。这也解释了为什么 `OrderSummary` 必须暴露 `placedAt` 和 `id`——
**结果的形状和游标的形状是同一个决定**，读模型藏起自己的排序键，翻页器就只能回头再查一次刚返回的行。

## 4. seek 谓词 vs `OFFSET`：负向对照

seek 谓词写成 PostgreSQL 的行值比较：

```sql
(placed_at, id) < (CAST(? AS timestamptz), ?)
```

元组从左到右比较，正好是"在这个排序里严格更早"，而且是 `(placed_at DESC, id DESC)` 索引能当**一次范围
扫描**服务的谓词。手写展开成 `placed_at < ? OR (placed_at = ? AND id < ?)` 是同一件事，说得更绕，很多
planner 执行得更差。

`OFFSET n` 的含义是"**此刻**排在它前面的 n 行"。sample 用一个刻意保留的反例（`OffsetPager`，与真实
实现只差这一点）把代价演出来：

| 时刻 | 发生了什么 |
| --- | --- |
| 客户端读第 1 页 | 拿到 6 条开放订单里最新的 3 条 |
| 期间 | 它刚看到的一条被确认，离开了过滤集 |
| 客户端读第 2 页 | `OFFSET 3` 已经越过了那条被顶上来的行 |

那条订单仍然开放、仍然命中过滤、**而这个客户端永远不会被展示它**。不报错、不打日志。第二笔代价才是性能：
`OFFSET 100000` 要求数据库产出并丢弃十万行，而 seek 谓词在第 1 页和第 10000 页一样便宜。

**负向对照做了两次**（都已还原）：

| 破坏 | 结果 |
| --- | --- |
| `hasMore = rows.size() >= size`（去掉 `+1` 技巧） | 恰好一个测试红：整页满的末页发出了通往空页的游标 |
| 删掉 seek 谓词 | 四个测试红——而在加护栏之前，**整个测试套件挂死** |

第二条是本篇最值得记住的发现：**游标坏了不会返回错的一页，而是永远返回同一页**，于是"读完整个列表"
那个导出任务永远跑不完。这也暴露了我自己测试的一个缺陷：无界 `while` 循环。现在 `walk(...)` 自带上限，
失败信息是 *"pagination did not terminate: the cursor is not advancing"*，几毫秒内给出结论——那才是
事故的真实样子。

## 5. 过滤与排序：不拼 SQL 的两个机制

**排序是枚举，不是列名。** `@RequestParam OrderSort sort` 绑定失败发生在类型转换阶段，**查询对象还不
存在**，所以客户端选的字符串永远到不了语句。sample 断言了这一点：发一个 `sort=placed_at desc; drop
table` 得到 400，并且**探针记录到的针对该表的语句为空**。客户端能传列名的设计，最后总会变成两种东西
之一：把字符串拼进 SQL，或者手写一个白名单把这个枚举重新发明一遍。

**过滤是值 + 条件谓词。** `OrderFilter` 的每个成员就是这个端点支持的一个过滤条件，适配器里用
MyBatis-Plus 的 `eq(boolean condition, ...)` 重载：条件不成立那个谓词就不存在。没有任何调用方提供的
片段被拼接，因此没有注入面要评审，也没有一串 `if` 会悄悄漏掉一句。

**索引跟着过滤走。** DDL 里两条索引：`(placed_at DESC, id DESC)` 服务无过滤的列表，
`(customer_id, placed_at DESC, id DESC)` 服务按客户过滤的列表——过滤列在前，排序键在后，一次范围扫描
同时满足两者。**给端点加一个过滤条件而不加对应索引，是"原本很快的列表变慢"最常见的原因**；而没有匹配
索引的 keyset 分页并不比 offset 快，它只是更正确。

## 6. `+1` 技巧与那个经典 off-by-one

怎么知道"还有下一页"而不去计数？**多要一行**。多出来那一行的存在本身就是答案。

做错的方式是**页满就发游标**：客户端跟着那个游标拿到一个空页，而所有"有游标就渲染下一页按钮"的客户端
都会显示一个通向虚无的按钮。sample 的 `theLastPageIsExactlyFullAndStillSaysThereIsNoNextPage` 用
6 行 / 每页 3 行钉住它——第二页正好装满，且必须是最后一页。

还有一条：**游标由"最后一条被返回的行"铸造，绝不是那条多出来的行**。多出来的行是证据，不是内容，它会是
下一页的第一行。

## 7. 读侧也有错误契约

`OrderingErrorCode` 五个码里有四个关于**读**。这件事本身值得注意：查询契约的失败方式和命令契约一样多，
而一个以裸 500 到达客户端的读侧失败，等于契约从来没写下来。

| 码 | category | 状态 | 什么时候 |
| --- | --- | --- | --- |
| `ordering.malformed-cursor` | VALIDATION | 400 | token 被截断、被重新编码、或手写的 |
| `ordering.cursor-does-not-match-query` | VALIDATION | 400 | token 解开了，但属于另一个问题 |
| `ordering.page-size-out-of-range` | VALIDATION | 400 | 超出这个端点服务的范围 |

这里有一个和 S19 呼应的细节：这三个都是 `ApplicationException`，而 `ApplicationExceptionAdvice` 给
`ApplicationException` 的**兜底状态是 422**——它们之所以出来是 400，是因为**码的 category 说了话**。
状态码跟着 `ErrorCode` 走，不跟着"哪一层抛的"走（[[analysis-00022-samples-validation-layers]] §5）。

**为什么 `size` 上限在 `PageRequest` 而不是只在 HTTP 参数上。** 命令总线会校验它派发的每一个命令；
**查询总线不装任何拦截器**（库确实提供 `QueryInterceptor` 这个接口，但不注册任何实现——注意
`QueryBus` 的 javadoc 在这点上是过时的，见 [[analysis-00015-samples-http-command-query]] §9 第 1 条）。
所以调用方和查询处理器之间没有任何东西会代替谁做检查：**只在 web 边界执行的读契约，就是定时导出任务、
运维工具和下一个适配器都没有的读契约**。而无上限的列表不是外观问题，它是一次请求读全表。

## 8. 层次分工

| 谁 | 负责 | 不负责 |
| --- | --- | --- |
| 适配器 `MyBatisOrderQueries` | 谓词、排序、上限 | 游标、形状、`+1` |
| `OrderPager`（application） | `+1`、`hasNext`、铸游标、选形状 | SQL |
| 控制器 | 绑定参数、原样返回 | 页码、默认值、看游标里面 |

读侧端口 `OrderQueries` 刻意**不返回** `Slice` / `Page` / `Cursor`：它回答"这些行、按这个顺序、从这个
位置起、最多这么多"。于是"是否还有下一页"和"位置怎么变成 token"只有一份实现，而不是每个存储后端各自
再推导一遍。两个 handler 共用一个 pager，正是为了让**两种形状对"下一页"的理解不可能漂移**。

写侧端口 `Orders` 只有两个方法（`save` / `findById`），没有 `findAll`、没有 `search`。列表用聚合来答，
既重建了渲染永远用不到的状态与不变量，又会把写侧端口长成一个谁都不敢改的通用查询 API。

`OrderSummary` 标了 `@ReadModel` 但**没有任何 `@Projection`**：这里没有独立存储要维护，查询直接读写表。
这是正确的默认，直到它不再是——升级到事件驱动投影的判据是 S12 的题目。sample 另外加了一条 ArchUnit
规则钉住"列表读侧不得依赖聚合"：框架不管这件事（单实体读加载聚合是合法的，`Query` 的 javadoc 明说），
但对**列表**它是错的默认。

## 9. 库事实与踩到的坑

| 事实 | 影响 |
| --- | --- |
| MyBatis-Plus **3.5.9 起把分页拦截器挪出 starter**，在 `mybatis-plus-jsqlparser` | 不加这个依赖，`new PaginationInnerInterceptor(...)` **编译不过**。这个失败方向是好的：缺的是类而不是行为——若拦截器在运行期缺席，`selectPage` 会**静默返回全部命中行**，因为 LIMIT 来自拦截器而不是 mapper |
| 库自己拥有唯一的 `MybatisPlusInterceptor`，并**预留 order 200 给消费方的分页拦截器** | 贡献一个普通 `InnerInterceptor` bean 就是全部集成。若改为自己声明 `MybatisPlusInterceptor`，库那个会退让，**连 `WHERE version = ?` 一起带走**，且没有报错 |
| `PaginationInnerInterceptor.setMaxLimit` 是**静默截断**，不是拒绝 | 它必须高于契约上限：`size = MAX_SIZE` 会以 `MAX_SIZE + 1` 行去取，若把 maxLimit 设成 `MAX_SIZE`，最大那一页会被悄悄削掉多出来的行，**把"还有下一页"错报成"这是最后一页"** |
| MyBatis-Plus 也有一个 `Page`，与库的 `cqrs.page.Page` 同名 | 同一个文件里两者都要用时只能全限定其一。sample 用 MP 的 `Page(1, limit, false)` 表达"只要 LIMIT、不要计数"，offset 永远是 0——定位是游标的事 |
| `CursorJacksonModule` 把 `Cursor` 序列化成裸 token | 线上形状就是 `{"items":[...],"nextCursor":"..."}`。它是**列表信封**（分页外壳），不是通用成功信封——单个资源仍然直接返回（S1） |

## 10. 常见错法

| 错法 | 后果 |
| --- | --- |
| 默认给 `Page`（带总数） | 每次列表都多一次全命中行扫描，而几乎没人看那个数字 |
| 用 `?withTotals=true` 切换形状 | 响应体形状随参数变，类型、文档、缓存全都无从下手 |
| `OFFSET` 翻页 | 集合变动时**漏行**（不报错），且深页越翻越慢 |
| 排序键不是全序（只按时间列） | 并列行之间无定义顺序，页间重复与漏行 |
| 直接 `ORDER BY id`（依赖 UUIDv7 的时间性） | 违反 `IdGenerator` 的不透明契约；换成 v4 环境后顺序无声改变 |
| 游标只装 id | 无法表达"在这一行之后"（排序键不是 id 定义的） |
| 游标不装排序/过滤 | 旧 token 配新问题，返回既非首页也非下一页的东西 |
| 用 `hashCode()` 做过滤指纹 | 跨一次部署后所有在途游标被拒，日志解释不了 |
| 游标精度低于列的精度 | 边界行重复或消失，只在同毫秒两笔时出现 |
| 把不透明当加密 | 里面放了敏感值，或以为它防伪造 |
| 页满就发 `nextCursor` | 客户端翻到一个空页，"下一页"按钮通向虚无 |
| 客户端传排序字段名 | 要么拼 SQL，要么把枚举用手写白名单重新发明一遍 |
| `size` 上限只写在 HTTP 参数上 | 非 HTTP 入口没有上限——查询总线不装任何拦截器，没人代查 |
| 加过滤条件不加索引 | 原本很快的列表变慢；keyset 没有匹配索引不比 offset 快 |
| 列表读侧加载聚合 | 每行重建状态与不变量，渲染永远用不到 |
| 让读侧失败以裸 500 到达 | 读契约的失败方式和写一样多，只是从没写下来 |

## 11. 本篇不覆盖

- 事件驱动投影，以及何时从"直接查写表"升级过去——S12；
- 深页性能的实测与索引验证——属于运维面，S22；
- 多租户下读侧的自动过滤（tenant line 同样重写 select）——S13，寄宿 S4；
- 游标签名 / 防伪造——游标是位置不是凭据，签名那套在 S2；
- 读侧鉴权（`QueryInterceptor` 正是它该待的地方，但库不提供实现）。
