---
id: issue-00160-the-edge-store-breaks-the-choose-one-backend-instruction
type: issue
status: open
---

# 边界存储让"只选一种后端"这条指令无法执行（P2，一致性/文档）

> **注（2026-08-06 补）**：本记录写于库同时并存 JDBC 与 MyBatis-Plus 两套存储后端的时期。
> `-persistence-jdbc`、`-outbox-jdbc`、`-inbox-jdbc`、`-process-manager-jdbc`、`-operation-log-jdbc`、
> `-web-store-jdbc`、`-starter-jdbc` 已全部删除（库只留 MyBatis-Plus 后端；web 边界存储由
> `-web-store-mybatis-plus` 承接）。因此下文带 `-jdbc` 的模块名、路径与 `file:line`，指的是当时的代码，
> 不是现在的树；它们作为当时的证据保留，未被改写成 MyBatis-Plus 的路径。

2026-08-03 写 samples 时撞到（S2 幂等与重放防护那一篇）。**不是缺陷，是矩阵与自家建议冲突，需要一个
书面裁决。**

## 现象

库为五个组件各提供 `-jdbc` / `-mybatis-plus` 一对：

```
aipersimmon-ddd-persistence-{jdbc,mybatis-plus}
aipersimmon-ddd-outbox-{jdbc,mybatis-plus}
aipersimmon-ddd-inbox-{jdbc,mybatis-plus}
aipersimmon-ddd-operation-log-{jdbc,mybatis-plus}
aipersimmon-ddd-process-manager-{jdbc,mybatis-plus}
```

web 边界存储是唯一的例外：只有 `aipersimmon-ddd-web-store-jdbc` 与
`aipersimmon-ddd-web-store-redis`，**没有 MyBatis-Plus 变体**。

而 `CHOOSING-MODULES.md:43` 那一节（"MyBatis-Plus or plain JdbcTemplate?"）在第 56 行写着：

> Mixing the two backends for different components works but buys nothing, and doubles what you have
> to reason about. **Choose one.**

对一个选了 MyBatis-Plus 的团队，这条指令在边界防护上**无法执行**：`CONFIGURATION.md:122` 说得很清楚，
幂等 / 重放 / 限流三者任一开启都需要一个 `-web-store-jdbc` / `-web-store-redis` 模块（或自己实现），
于是它只能

- 混用 JdbcTemplate（正是文档劝阻的），或
- 引入 Redis（第三套栈），或
- 自己实现三个 SPI。

`CHOOSING-MODULES.md` 通篇**没有一处提到边界存储**（grep `idempot|rate.?limit|replay|edge` 零命中），
所以消费者去做选型的那份文档，恰好不覆盖这个选择。

## 为什么值得一个 issue

不是因为缺功能，是因为**文档的指令与模块矩阵互相矛盾，而矛盾点在一个安全相关的表面上**。中级团队照
"Choose one" 做，然后发现做不到，只能自己猜一个方向——猜错的那个方向是"那就别开幂等了"。

samples 里的实际后果（可查）：S2 用了 Redis，并在 `s02-http-idempotency/pom.xml:31` 注明了理由。这是
本项目"DB 一律 MyBatis-Plus"这条约束下唯一的例外，而它是被库的模块矩阵逼出来的。

## 反方（很可能就是裁决）

**边界存储用不上 MyBatis-Plus 的任何长处。** 看它实际需要的 SQL：

- `JdbcIdempotencyStore.java:23` 的策略是 "claim is an `INSERT` of a `PENDING` row, so exactly one
  instance can win it"——靠主键冲突仲裁，不是靠版本谓词；
- `JdbcRateLimiter.java:52` / `:66` 是 `UPDATE ... SET count = count + 1` 的条件自增；
- 清理是 `DELETE ... WHERE expires_at <= ?`（`JdbcWebStoreCleanup.java:55/57/82`）；
- 只有一处 `SELECT`，取回已存响应（`JdbcIdempotencyStore.java:133`）。

实体映射、条件构造器、`@Version` 谓词、逻辑删除——一个都用不上。而且 MyBatis-Plus 的 starter 本身就
带着 `JdbcTemplate`，同一个 `DataSource`、同一个事务管理器，所以这里"混用"的**实际成本是零**：没有
多一个依赖，没有运行期冲突，只有"我们说过全用 MyBatis-Plus"这句话上的不一致。

按这个理由，新增一个 `-web-store-mybatis-plus` 是纯粹的维护负担换零收益，**"不做"是合理裁决**。

## 修复要求

任选其一，但要留下裁决：

**(A) 推荐——不新增模块，补上缺的那句话。** `CHOOSING-MODULES.md` 的 "MyBatis-Plus or plain
JdbcTemplate?" 一节加一条例外说明，口吻与 `README.md` 的 declared-debts 一致，至少说清：

- 边界存储（幂等票据 / nonce / 限流桶）**不在"只选一种"的范围内**，因为它不是聚合持久化，是 KV + TTL；
- 两个变体的判据：单实例或小规模→`-web-store-jdbc`（省一套基础设施，且 cleanup 与 schema 校验只有它
  有，见 `CONFIGURATION.md:126/142`）；多实例高频边界→`-web-store-redis`（TTL 是原生语义）；
- 明确写出"用 `-web-store-jdbc` 不算违反 Choose one"，因为 MyBatis-Plus starter 已经带着
  `JdbcTemplate`，同一个 `DataSource` 与事务。

**(B) 若认为一致性本身值钱**，则新增 `aipersimmon-ddd-web-store-mybatis-plus`，用 mapper 实现三个
SPI。代价要认：三张表的 DDL 与清理/校验逻辑多一份复制（参照
[[process-manager-schema-copies]] 那类必须同步改动的复制点），收益仅为命名一致。

无论选哪个，`CHOOSING-MODULES.md` 都必须**提到边界存储的存在**——今天它一个字都没有。

## 附：与本 issue 无关但同批发现的三条（均已判定不是问题，不需修）

记在这里免得日后重复提出：

1. **查询侧没有校验闸口**：命令总线有 validation 拦截器，查询总线不注册任何实现。这是有意的，
   `QueryBus` 与 `QueryInterceptor` 的 javadoc 都说了"deliberately lighter"，缝留着、实现交给应用。
2. **聚合重建要 public 工厂**：`README.md:100` 的示例本身就是
   `public static Order reconstitute(OrderId, OrderStatus, long)`——库自己文档里的既定写法。
3. **批次的因果根**：`CommandBus.send(Command, CommandContext)` 的 `@param cause` 里那句
   "(a parent command, or an inbound integration event...)" 是举例不是封闭清单；哪几条命令算一个逻辑
   单元只有应用知道，用 `CommandContext.root(...)` 拼出来就是应用层的事。
