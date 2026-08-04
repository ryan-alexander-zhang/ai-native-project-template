# S27 — 软删除、数据保留与擦除

三件事都叫"删除",只有一件真的移除了什么。

配套分析:`docs/analysis/analysis-00039-samples-soft-delete-and-erasure.md`。

## 跑起来

```bash
mvn -pl s27-soft-delete-and-erasure -am test        # 自带 Testcontainers,什么都不用先起

docker compose -f s27-soft-delete-and-erasure/docker-compose.yml up -d
mvn -pl s27-soft-delete-and-erasure spring-boot:run
```

端口块 18270:应用 18270、PostgreSQL 18271。

## 三种删除

| | URL | 留下什么 | 可逆 |
| --- | --- | --- | --- |
| **领域状态** `status=CLOSED` | `POST/DELETE /customers/{id}/closure` | 状态 + 原因,完全可读 | 是,对称 |
| **基础设施开关** `deleted`(`@TableLogic`) | `PUT/DELETE /customers/{id}/suppression` | 行还在,应用看不见 | 是,但要手写 SQL |
| **合规擦除** 墓碑 + `erased_at` | `POST /customers/{id}/erasure` | 行还在,人不在了 | **不可逆** |

三个 URL 而不是一个 `DELETE`:否则三件事对每个调用方和每条日志都无法区分,而其中不可逆的那个会是最容易
误触的。

## 主张与它们的位置

| 主张 | 在哪 |
| --- | --- |
| 判据:有人问为什么 / 有人要撤销 / 有人要清单 → 领域状态 | `package-info`、`ThreeKindsOfDeleteTest` |
| 三种删除正交,一个 boolean 装不下 | `allThreeCanBeTrueAtOnceAndMeanDifferentThings` |
| **擦除不是删除**:行是证据,只有内容被覆写 | `Customer.erase`、`CustomerId` 的 javadoc |
| 墓碑必须按 id 唯一,否则第二次擦除撞唯一键 | `UniqueEmailTest.thetombstoneHasToBeUniquePerCustomer` |
| 逻辑删除让邮箱永久被占;部分索引修它 | `V2__email_unique_among_the_living.sql`、`UniqueEmailTest` |
| **`@TableLogic` 与库的整根覆盖写入**(一行从没跑过的代码) | `ClearedColumnsTest`、`HandRolledFlag` |
| 擦除必须**排在** outbox 排空之后,不能事后补救 | `EraseCustomerHandler`、`ErasureAndOutboxTest` |
| inbox 里没有人,删它只会打断 exactly-once | `ErasureAndInboxTest` |
| 审计行有什么,写下的那刻就定了(没有 update 端口) | `ErasureAndAuditTest` |
| 用基础设施开关的**条件**是它被审计 | `SuppressCustomer` 的 javadoc、`suppressingArowIsAudited` |

## 试着弄坏它

逐个单跑并量过(结果见分析文档 §9):

```bash
# 摘掉真实行类的 @TableLogic          → 22 红 / 42,全部 null value in column "deleted"
#                                       不是"隐藏的行行为异常",是一次写都不成
# 去掉擦除的排空闸口                  → 1 红,擦除径直越过还握着地址的队列
# 墓碑改成常量(去掉 id)              → 2 红,含 duplicate key ... uq_s27_customer_email_live
```

## 库的一个 issue(本篇发现,未修)

**issue-00168**(P2):`DefaultFailureClassifier` 的 `instanceof` 链没有 `ApplicationException` 分支,
所以每一次 application 层的业务拒绝(404、409)在审计表里都是 `FAILED` / `unexpected`,自带的 `ErrorCode`
被丢掉。`ErasureAndAuditTest.arefusedErasureIsAudited` 断言的是缺陷现状,修好会打红。

## 不在本篇范围内

MySQL(部分索引的替代方案是推理,已标注)、死信表里的个人数据(§5 点名的洞)、actor 从哪来(S14)、
读侧查询契约(S20)、保留期清理的实际运行(S22 已量过 purge)。
