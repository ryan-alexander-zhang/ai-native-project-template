# S25 — 遗留系统绞杀式接入

库的默认前提是"你拥有 schema"。这一篇把每个前提都反过来:自增主键、没有版本列、一个指向未切出表的外键、
一套手写 SQL 的 Service。

配套分析:`docs/analysis/analysis-00042-samples-strangler-legacy-adoption.md`。

## 跑起来

```bash
mvn -pl s25-strangler-legacy-adoption -am test    # 自带 Testcontainers,什么都不用先起

docker compose -f s25-strangler-legacy-adoption/docker-compose.yml up -d
mvn -pl s25-strangler-legacy-adoption spring-boot:run
```

端口块 18250:应用 18250、PostgreSQL 18251。

```bash
curl -X POST localhost:18250/refunds -H 'Content-Type: application/json' \
  -d '{"orderId":1,"amountCents":2500,"reason":"damaged"}'
# → publicId(UUID,对外的) + id(遗留 bigint,内部的) + state
curl -X POST localhost:18250/refunds/1/approval -H 'Content-Type: application/json' -d '{"approvedBy":"ops-anna"}'
```

## 结构

```
s25/
  legacy/       ← 单体。JdbcTemplate + 手写 SQL,一个框架模块都不用。这是"遗留"的定义
  refunds/      ← 切出来的第一个聚合,住在 legacy_refunds 这张表上
  acl/          ← 两个类:事实翻译 + 绞杀缝(遗留签名,配置路由)
```

`s25.refunds.route` 是唯一随迁移推进而变的东西:

| | 谁写那张表 | 版本列有意义吗 |
| --- | --- | --- |
| `LEGACY_ONLY` | 单体 | **没有**——第二个写者看不见它 |
| `NEW_WRITES`(默认) | 新上下文;单体只读 | **有** |
| `NEW_ONLY` | 新上下文;遗留入口已删 | 有。这是结论不是设置 |

**没有 `BOTH`。** 双写没有机制支撑,见分析 §4。

## 六问六答

| 问题 | 答案 |
| --- | --- |
| 先切哪个 | **写者最少、规则最多**那张。两个数都从单体源码里算(`LegacyFanInTest`),从叶子往里绞 |
| 没有版本列 | 三个方案(加列/影子表/悲观锁)都不重要——**先停掉第二个写者**。加列用 `DEFAULT 1`,不是 0 |
| 自增主键 | 内部保留 `bigint`,**插入前自己取号**(库不会拦你),对外从第一天发 `public_id` |
| 双写 / outbox | outbox 只覆盖走库事务的写,遗留侧根本用不了 → **一个写者、两个读者** |
| ACL | 一条 ArchUnit 规则:只有 `acl` 能碰 `legacy`。端口声明在新上下文里,不在 ACL 里 |
| 何时算完 | 四个条件,从代码和 schema 算(`DoneCriterionTest`)。**本篇刻意没完成** |

## 试着弄坏它

逐个单跑并量过(结果见分析文档 §8):

```bash
# 版本列 DEFAULT 1 → 0        → 2 红:对任何旧行的第一次写入抛 DuplicateEntityException
# handler 直接依赖单体        → 1 红,2 处违规("先这样,它只要一个字段")
# ACL 不再翻译 JDBC 异常      → 1 红,而所有 ArchUnit 规则全绿
# 路由改回 LEGACY_ONLY        → 12 红:每条规则、每次拒绝、发布的事实,全部消失
```

## 库的一个 issue(本篇发现,未修)

**issue-00171**(P2):两处行为都正确、异常信息都指向错的原因,而这两处恰好是遗留表必然踩的两处。
① 版本列 `DEFAULT 0` 让每一行历史数据都"看起来没保存过",报错却说"并发创建 / 工厂忘了 restoreVersion";
② 自增主键的 INSERT **会通过**(守卫只在 update 路径),错误归属已落库且已流到下游才有人报错。

## 不在本篇范围内

切第二个聚合、CDC/Debezium、影子表、真正删掉旧路径(判据未满足)、几百万行的索引度量(S23)、
遗留侧的租户列(S13)。
