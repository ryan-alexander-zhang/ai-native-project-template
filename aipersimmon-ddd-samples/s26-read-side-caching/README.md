# S26 — 读侧加速：缓存与投影的取舍

一个数字，三种算法：现算、缓存、投影。同一个值，三种代价，三种故障形态。

配套分析：`docs/analysis/analysis-00037-samples-read-side-caching.md`。与 S12（CQRS 读模型）成对。

## 跑起来

```bash
# 测试自带 Testcontainers（PostgreSQL + Redis），什么都不用先起
mvn -pl s26-read-side-caching -am test

# 想手工戳 schema 或 keyspace
docker compose -f s26-read-side-caching/docker-compose.yml up -d
mvn -pl s26-read-side-caching spring-boot:run
```

端口块 18260：应用 18260、PostgreSQL 18261、Redis 18262。

## 这个 sample 想说明什么

| 主张 | 在哪 |
| --- | --- |
| 缓存该挂在 `QueryInterceptor` 上，命中时 handler 根本不跑 | `CachingQueryInterceptor`、`CacheHitAndMissTest` |
| 缓存是 opt-in 的：查询要主动戴 `CachedQuery` | `ProductDetailQuery`、`anuncachedQueryIsUntouchedByTheInterceptor` |
| **聚合不能缓存**——坏的不是丢更新，是"成功但什么都没写" | `CachedProducts`（test 作用域）、`AggregateCacheTrapTest` |
| 失效必须在提交**之后**，且那样也没关严 | `ProductCacheInvalidation`、两个 `Invalidation*Test` |
| single flight 与 jitter 修的是同一问题的两半 | `StampedeWith*Test`、`TtlJitterTest` |
| 拼接式 key 的危险是**歧义**，不是顺序 | `CacheKeys`、`CacheKeysTest` |
| 一个条目只能有一种一致性保证 | `ProductDetail`、`BoundedStalenessTest` |
| 缓存不一致时，其他每个指标都往反方向走 | `CacheAudit`、`CacheAuditTest` |
| flush 什么都不恢复，rebuild 恢复一张正确的表 | `CacheOpsController`、`ProjectionVersusCacheTest` |

## 试着弄坏它

每一条都单独跑过并量过（结果见分析文档第 10 节）：

```bash
# 失效挪进事务：陈旧读那条红
#   把 InvalidationInTransactionTest 的属性改成 AFTER_COMMIT → 恰好 1 红
# 聚合缓存的三条：从 AggregateCacheTrapTest 摘掉 CachedProducts → 3 红
# single flight：把 StampedeWithSingleFlightTest 翻成 false → arrivals 1 → 10
# 租户 key：把 CacheKeys.of 里的租户段去掉 → 7 红，含一次跨租户命中
```

## 库的两个 issue（本篇发现，未修）

- **issue-00166**（P2）：三条领域事件订阅者的 ArchUnit 规则看不见 `@TransactionalEventListener`。
  本 sample 的 `ArchitectureTest.everyDomainEventSubscriberIsMarked` 就地写了 meta-annotation 版；
  库修好之后那条可以删。
- **issue-00167**（P3）：`QueryBus` 的 javadoc 说读侧没有拦截器链，而它有。

## 不在本篇范围内

多级缓存、条目版本戳、Micrometer 接线（S15）、租户判别列（S13，寄宿 S4）、负缓存（明确选择不做，
并有断言）。
