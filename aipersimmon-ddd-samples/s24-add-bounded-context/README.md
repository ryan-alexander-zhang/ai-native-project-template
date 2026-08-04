# S24 — 在既有服务里新建一个限界上下文

新上下文是一个**包**,不是一个 Maven 模块。让"以后能拆"仍然成立的,是几条第一天就免费的规则。

配套分析:`docs/analysis/analysis-00041-samples-add-bounded-context.md`。

## 跑起来

```bash
mvn -pl s24-add-bounded-context -am test        # 自带 Testcontainers,什么都不用先起

docker compose -f s24-add-bounded-context/docker-compose.yml up -d
mvn -pl s24-add-bounded-context spring-boot:run
```

端口块 18240:应用 18240、PostgreSQL 18241。

```bash
curl -X PUT localhost:18240/coupons/SAVE10 -H 'Content-Type: application/json' \
  -d '{"percentOff":10,"currency":"GBP","validFrom":"2026-01-01T00:00:00Z","validUntil":"2027-01-01T00:00:00Z","maxRedemptions":5}'

curl -X POST localhost:18240/orders -H 'Content-Type: application/json' \
  -d '{"orderId":"ord-1","customerId":"cust-1","currency":"GBP","couponCode":"SAVE10",
       "lines":[{"sku":"SKU-1","quantity":2,"unitMinor":1500}]}'
# → grossMinor 3000 / discountMinor 300 / totalMinor 2700,拒绝的话 couponRefusal 里有理由
```

## 三个上下文 + 一个共享内核

```
s24/
  S24Application          ← 组合根,直接坐在基础包里(规则会跳过没有上下文段的类)
  sharedkernel/api/       ← Money。在 api 下是库的规则推出来的,不是审美
  ordering/               ← 既有。加了新上下文之后:请求多一个可选字段,响应多一个可空字段
  inventory/              ← 既有。一个字都没改,两个方向都没有依赖
  coupons/                ← 新的。api / domain / application / infrastructure / interfaces
  contextmap/adapter/     ← 关系住的地方:依赖两边的契约,两边都不依赖它。拆分那天它消失
```

## 六条规则(本篇的交付物)

| 规则 | 来处 | 补的洞 |
| --- | --- | --- |
| `BoundedContextRules.dependOnEachOtherOnlyThroughApi` | 库,一行 | 别人只能从前门进 |
| `integrationEventListenersShouldResideInAdapter` / `integrationEventsShouldResideInApi` | 库,opt-in | 订阅者是入站适配器;已发布的事实住契约里 |
| **`theapiPackagesAreLeaves`** | 本篇 | 契约不能拖着模型 |
| **`nodomainKnowsAnotherContextExists`** | 本篇 | 聚合不能持有别人的端口 |
| **`thecontextsFormNoCycle`** | 本篇 | 环:Maven 会拒,ArchUnit 不会 |
| **`thesharedKernelIsALeaf`** | 本篇 | 共享内核的机械判据 |
| **`TableOwnershipTest`** | 本篇 | 一次 join,ArchUnit 看不见(它读 Java,表名是字符串) |

## 第一条集成:两个都要

按**答案是用来决定还是只用来记录**分:

| | 机制 | 为什么 |
| --- | --- | --- |
| 报价 | **同步调用** `CouponQuotes` | 订单没有折扣定不了价。晚到的消息参与不了已做完的决定 |
| 兑换 | **事件**,commit 之后 | 是后果,没人在等,而且绝不能让订单失败 |

## 试着弄坏它

逐个单跑并量过(结果见分析文档 §8)。**五个里有四个的红来自本篇自己加的规则,库的规则那四次全绿**:

```bash
# 订阅者搬进 coupons(通行重构)   → 1 红:thecontextsFormNoCycle。库全绿
# Money 挪出 sharedkernel.api    → 1 红:库的隔离规则,82 处违规
# CouponQuote 加一个吃聚合的工厂  → 1 红:theapiPackagesAreLeaves。库全绿
# Order 加 repriceWith(CouponQuotes) → 1 红:nodomainKnowsAnotherContextExists。库全绿
# CouponMapper 加一句跨表 join    → 1 红:TableOwnershipTest。10 条 ArchUnit 规则全绿
```

结论:**"只经 api 依赖"是必要的,远不是充分的。**

## 库的一个 issue(本篇发现,未修)

**issue-00170**(P2,规则集):一个发布出去的值对象无法同时满足
`domainBuildingBlocksShouldResideInDomain`(必须在 domain)与 `BoundedContextRules`(必须在 api)。
所以 `CouponCode` 与 `Money` 都不标 `@ValueObject`,连带失去 `valueObjectsShouldBeImmutable`——
`thepublishedTypesAreStillImmutable` 手写补回。库自己在事件上已经解过同样的问题
(`domainEventsShouldStayInDomain` vs `integrationEventsShouldResideInApi`),只差值对象这一对。

## 不在本篇范围内

真的拆成两个模块/两个服务(§1 的立场是留到那天)、ACL 防腐层(S25)、context map 的其它模式
(conformist / separate ways / partnership)、多租户(S13)、券的读侧(S20)。
