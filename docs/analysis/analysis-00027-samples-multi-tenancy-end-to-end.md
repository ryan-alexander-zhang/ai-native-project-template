---
id: analysis-00027-samples-multi-tenancy-end-to-end
type: analysis
status: draft
parent: analysis-00014-ddd-samples-scenario-catalog
---

# S13 多租户端到端传播（寄宿 S4）

对应 sample：`aipersimmon-ddd-samples/s04-integration-events-across-services`（两个服务模块，与
[analysis-00025-samples-integration-events-across-services](analysis-00025-samples-integration-events-across-services.md) 同一份代码）。场景清单见
[analysis-00014-ddd-samples-scenario-catalog](analysis-00014-ddd-samples-scenario-catalog.md)。同样寄宿在 S4 的 trace 一篇是
[analysis-00028-samples-one-trace-across-the-boundary](analysis-00028-samples-one-trace-across-the-boundary.md)。

## 0. 本篇定位

一套部署服务多个租户，租户身份要从入口一路传播到**持久化行**和**跨服务事件**，任何一环不得读写别家的数据。
本篇最重要的一句话：**这件事的正确形态是"没有任何业务代码提到租户"**——控制器、命令、处理器、聚合、仓储、
行对象里都没有 tenantId，全部由边界绑定 + SQL 改写完成。凡是把租户当参数传的地方，总有一天会有一个方法忘
记传。

## 1. 租户从哪里来：两个可信边界，不是一个

| 边界 | 谁绑定 | 出处 |
| --- | --- | --- |
| HTTP 入口 | 租户解析过滤器读 `X-Tenant-Id`（或自定义 `TenantResolver`） | 发布方与消费方的读端点 |
| 消息消费 | 消费桥读 `ce_tenantid`，`TenantContext.runAs` 包住整个事务 | 消费方 |

第二条是很多实现漏掉的那条：**消费者没有请求**，所以"从请求里取租户"这条路在这里不存在，租户必须随消息
本身到达。sample 断言了它——`thecommandInheritedTheTenantFromTheEnvelopeAndNotFromAnyRequest`：附近没有任何
HTTP 请求，命令的 `tenantId` 与 `TenantContext.effective()` 都是记录上那个租户。

再往里就不需要边界了：命令总线从环境租户播种 `CommandContext`，而 `TenantContextCommandInterceptor`
（order −90）反向保证——**没有环境租户的线程**（relay、调度器、批处理）在整条链上按命令的租户绑定。

### 1.1 header 为什么默认不可信

`trust-header` 默认 `false`，开着多租户又没有自己的 `TenantResolver` 时**启动直接失败**。理由是 header 由
调用方提供、与认证主体没有任何绑定：信它 = 谁能连上这个服务就能改一个 header 读写任何租户的数据。它只在
"前面有网关/mesh/BFF 认证调用方并**重写**这个 header（丢掉客户端送来的值）"且"服务不可被绕过直连"时成立。

sample 写的是 `trust-header: true` 并把这段前提抄在了 yaml 里。测试恰好是那个组件缺席的场景——所以测试里
一个字符串就能冒充任何租户，这也正是库要求把这件事说出口的原因。

## 2. 传播链：请求 → 行 → 线上 → 下游行

一次 POST 之后，租户出现在四个地方，而代码里一次都没出现：

| 位置 | 怎么到的 |
| --- | --- |
| `s04_order.tenant_id` / `s04_order_line.tenant_id` | 租户行拦截器给 INSERT **加列加值** |
| `aipersimmon_outbox.tenant_id` | outbox writer 从 `CommandContext` 盖章（数据列，不是谓词） |
| `ce_tenantid`（Kafka header） | dispatcher 从行上读 |
| `s04_stock.tenant_id`（另一个服务）、`aipersimmon_inbox.tenant_id` | 消费桥绑定后，同一套改写 |

`theTenantIsStampedOnEveryRowWithoutAnyCodeMentioningIt` 钉住前两跳，`OutboxPublicationTest` 钉住
`ce_tenantid == "acme"`，消费侧 `thetenantOnTheRecordDecidesWhichBucketMoves` 钉住最后一跳。

## 3. SQL 级改写 vs 手写谓词

拦截器改写的是**语句**：SELECT 加 `AND tenant_id = ?`，INSERT 加列加值。差别不在省了几个字符，而在
**没有"每条语句都要记得"这件事，所以没有一条语句能忘记**。

代价有两条，都必须知道：

1. **它是一张 allow-list，而且 fail open。** 没登记的表**一个谓词都不加**，读写都不加，而且不报错。
2. **它是全局的**（一个 `SqlSessionFactory` 一个拦截器），所以不能默认全表打开——会打到没有该列的业务表，
   和框架那些被后台线程无租户轮询的表。

对第 1 条，库的答案是**启动期自检**：任何带 `tenant_id` 的基表必须出现在 `tenant-tables` 或
`exempt-tables`，否则拒绝启动。`anUnregisteredTenantCarryingTableIsRefusedByTheStartupGuard` 直接构造
`TenantTableRegistrationGuard` 跑了两遍——故意漏掉 `s04_order_line` 时按名字报错，服务实际发布的那份列表通过。
**allow-list 的完备性是机器该检查的，不是人该记得的。**

### 3.1 隔离要落在约束里，不只落在谓词里

消费侧 `s04_stock` 的主键是 `(tenant_id, sku)`，不是 `sku`。这是"加一列"覆盖不到的那部分：sku 只在租户内
唯一，单列主键会让两个租户抢同一行，而拦截器救不了一个键。

这条是**量出来的**，不是推的：把 `s04_stock` 从 `tenant-tables` 里删掉（并关掉启动自检，否则根本起不来），
`selectById` 退化成 `WHERE sku = ?` → 命中两行 → MyBatis 抛 `TooManyResultsException` → 属 systemic 失败被
无限重试 → **分区原地停滞**，五个测试里四个红。

响。而且**只因为主键是复合的才响**——单列主键下，同样的漏登记会安静地返回别家那一行并从上面预留。这就是
"隔离要放在应用绕不过去的地方"的全部论证。

## 4. 合法地不带租户：三条旁路，每条都要说清

catalogue 说这里最容易做成越权漏洞。sample 把三种"合法的无租户"分开钉住：

| 情形 | 行为 | 为什么是对的 |
| --- | --- | --- |
| 后台轮询（outbox relay、清理任务） | **不做租户 scope**，一次 poll 排空所有租户 | 框架表的租户是**盖章的数据列**，不是查询谓词；把它们列进 `tenant-tables` 会让每次 poll 都抛异常 |
| 请求解析不出租户 | 400 拒绝（`missing-policy: REJECT` 默认） | 没有安全的默认解释；`SYSTEM`（绑 `__root__`）会把数据写进那个在"从单租户迁移过来"的部署里装着生产数据的桶 |
| 无绑定线程调框架端口 | 抛 `MissingTenantException` | fail closed：SELECT 返回空与"这个租户没有数据"无法区分，INSERT 落进共享哨兵桶 |

`therelayIsNotTenantScopedAndDrainsEveryTenant`、`arequestThatResolvesNoTenantIsRejectedAtTheEdge`、
`aTenantLessThreadFailsClosedRatherThanReadingTheSentinelBucket`、`theSamePortOnATenantLessThreadFailsClosed`
分别是这三条（最后一条在消费侧的 inbox 上再验一次）。

### 4.1 `__root__` 是一个桶，不是通配符

`theRootSentinelIsABucketNotAWildcard`：以 root 身份读**不会**读到所有租户——`__root__` 就是那一列里的一个
普通值，scope 到它就匹配盖着它的行（这里是零行）。

所以**拦截器里没有"全租户"模式**，而这是故意的。真正的跨租户读只能是**另一条查询路径**（sample 里那条无谓
词的裸 SQL），而它是多租户服务里最危险的代码：上面所有隔离保证在它内部一律失效。它必须

- 单独授权（平台角色，不是租户用户的角色）；
- 不可从任何面向租户的端点到达；
- 被明确记账——"当时支持同学要用"就是它最后如何长在一个端点后面的经过。

## 5. 一个租户读不到别人的 id：404，不是 403

`aforeignTenantsOrderIdReadsAsNotFound`。403 会**确认这个 id 存在**，而猜到别家 id 的调用方同样无权得到这个
确认。"不存在"和"不是你的"必须是同一个答案——而免费拿到这个性质的办法，是让查询本身看不见它。

这也是为什么读端口的签名里没有租户参数（`Orders.find(OrderId)`）：能传的参数就能传错。

## 6. 与自己的 InnerInterceptor 共存

MyBatis-Plus 只认**一个** `MybatisPlusInterceptor` bean。所以两个自动配置各注册一个的结果不是叠加，而是
`@ConditionalOnMissingBean` 里输的那个**静默退让**——丢掉租户拦截器就是不再隔离租户，丢掉乐观锁拦截器就是
`@Version` 不再加 `WHERE version = ?`、每次 update 都报成功。

库的做法是 `aipersimmon-ddd-mybatis-plus-spring-boot-starter` **独占那一个 bean**，其余模块只贡献
`InnerInterceptor`，按 `@Order` 组装：租户 100 → 分页 200（留给使用方）→ 乐观锁 300，即 MyBatis-Plus 文档的
顺序。要加分页就发一个 `PaginationInnerInterceptor` bean（S20 的做法），不要自己造 `MybatisPlusInterceptor`
——真造了，这一个就整体退让，框架那些内层拦截器得自己加回去，而启动日志里那行"installed"是唯一能看出来的地方。

**顺序为什么重要**：租户要在分页改写之前加谓词，否则被分页包裹后的语句上再加条件，加的位置就可能不是你想的
那个子查询。

## 7. 装配上的一条实账

`aipersimmon-ddd-tenancy-mybatis-plus` 把 `mybatis-plus-jsqlparser` 声明为 `provided`（3.5.9 之后 SQL 解析
类拆到了那个 artifact），理由写在 pom 里：不该把 jsqlparser 强加给不解析 SQL 的使用方。所以**使用方要自己加
这一条**，两个服务的 pom 里都写了。

漏了不是静默的：`NoClassDefFoundError: TenantLineHandler`，启动即炸。只是 `CHOOSING-MODULES.md` 里没提这条
——崩溃本身就是文档，代价可以接受，但值得知道。

## 8. 常见错法

| 错法 | 后果 |
| --- | --- |
| 把租户当方法参数往下传 | 总有一个方法忘记传，而它不报错 |
| 新增带 `tenant_id` 的表却忘了登记 | allow-list fail open：**一个谓词都不加**（启动自检存在就是为了这条） |
| 只加列、不改唯一键 | 两个租户抢同一行；单列主键下会静默读到别家那一行 |
| 把框架的 `aipersimmon_*` 表列进 `tenant-tables` | 后台轮询线程无租户 → 每次 poll 抛 `MissingTenantException` |
| `missing-policy: SYSTEM` 图省事 | 无租户请求写进 `__root__` 桶，那里可能是迁移前的生产数据 |
| 以为 `runAs(ROOT)` 能读全租户 | 它是一个桶：读到零行，而不是所有行 |
| 跨租户读做成一个普通端点 | 上面所有隔离在它内部失效；这是最容易做成越权的一处 |
| 外键不带租户 | 应用层可被绕过，约束层才拦得住 |
| `trust-header: true` 而边界可被绕过 | 改一个 header 即读写任意租户 |
| 自己造 `MybatisPlusInterceptor` | 框架那一个整体退让，租户与乐观锁一起静默消失 |
| 跨租户复用同一个 `ce_id` 且同 `ce_source` | inbox 去重键**不含租户**，第二条被当重复丢掉（见 §9） |
| `@Async`/自建线程池里访问租户数据 | ThreadLocal 不跨线程；`TaskDecorator` 只覆盖 Boot 自动配置的执行器 |
| 消费方日志里 grep 租户 | MDC 的 `tenant` 由 HTTP 过滤器写，消费侧没有（见 S15 一篇） |

## 9. 一条前提，不是缺陷

inbox 的去重键是 `(consumer, source, message_key)`，**租户是盖章的数据列**——`MybatisPlusInbox:48-52` 明确
写了这句。于是同 `ce_source`、同 `ce_id`、不同租户的两条消息里，第二条会被当成重复静默丢弃。
`thededupKeyDeliberatelyExcludesTheTenant` 把这个行为做成了测试。

这不是 bug，是一条**必须知道的前提**：生产者的 id 要在 **source 内**唯一，而不是在 `(source, tenant)` 内唯一。
用 UUID 时白送；哪天有人在同一个 `ce_source` 下按租户编号事件，当场出事。真要那样分片 id，就把 source 一起
分片。

## 10. 本篇不覆盖

- 租户迁移与合并（把一个租户的数据搬到另一个 id 下）——数据操作，且必须走上面那条跨租户路径；
- 每租户一库 / 每租户一 schema——本篇只演 pool 判别列这一种；
- 从单租户存量库迁移到带 `tenant_id`（回填、加 NOT NULL、扩唯一键的不停机顺序）——S23；
- `@Async` 与自建执行器下的租户传播（`TaskDecorator` 的边界）——只在 §8 点名；
- 认证与授权本身：本篇假设租户已由可信边界解析出来，谁能扮演哪个租户不在范围内。
