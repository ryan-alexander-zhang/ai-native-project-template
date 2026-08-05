# S28 — 长耗时与大数据量端点

同步接口的上限不在任何超时上，在连接池上。

配套分析:`docs/analysis/analysis-00040-samples-long-running-endpoints.md`。

## 跑起来

```bash
mvn -pl s28-long-running-endpoints -am test        # 自带 Testcontainers,什么都不用先起

docker compose -f s28-long-running-endpoints/docker-compose.yml up -d
mvn -pl s28-long-running-endpoints spring-boot:run
```

端口块 18280:应用 18280、PostgreSQL 18281。跑起来后 worker 就在轮询,V2 迁移里有 500 行 `2026-05` 可导。

```bash
curl -X PUT localhost:18280/exports/exp-1 -H 'Content-Type: application/json' -d '{"period":"2026-05"}'
curl localhost:18280/exports/exp-1                  # status / progress / attempt / contentPath
curl localhost:18280/exports/exp-1/content -o out.csv
curl 'localhost:18280/exports/inline?period=2026-05' # 同步版本,拿来对比的
```

## 契约

```
PUT    /exports/{id}          → 202 + Location,两次都一样。id 由客户端给 → 不需要幂等键存储
GET    /exports/{id}          → 唯一要轮询的端点:status / progress / attempt / failure / contentPath
GET    /exports/{id}/content  → 字节,读文件不读表。没做完是 409 而不是 404
DELETE /exports/{id}          → 请它停下,202。不是删除记录
POST   /exports/{id}/retries  → 失败之后再试一次

PUT    /imports/{id}                → 打开,声明分片数
PUT    /imports/{id}/chunks/{n}     → 一片。重发免费,乱序无妨
GET    /imports/{id}                → 还缺哪几片 —— 断点续传就住在这里
POST   /imports/{id}/completion     → 关闭,缺片则点名拒绝
DELETE /imports/{id}                → 放弃,带原因
```

## 主张与它们的位置

| 主张 | 在哪 |
| --- | --- |
| 同步上限是**连接池**,不是超时;库不设任何时间上限 | `SynchronousLimitTest`、`InlineExport` |
| 游标要**同时**有事务与 fetchSize,缺一个静默失效 | `StreamingExportTest`、`ExportSourceMapper` |
| 一次快照与 keyset 分页**不是同一份文件** | `s28.export.read-mode`、`StreamingExportTest` |
| 作业的**生命周期**是聚合,**进度**不是 | `ExportJob`、`ProgressIsNotAnInvariantTest` |
| 进度的**事务**比进度的表更要紧 | `MyBatisProgressBoard`、`ProgressInTheSameTransactionTest` |
| 认领必须是手写 SQL,代价是必须自己 `version = version + 1` | `ExportClaims`、`ExportJobMapper`、`FailureVisibilityTest` |
| 停住的 worker 要靠**显式栅栏**拦,不能靠乐观锁 | `ExportJob.requireHeldBy`、`FailureVisibilityTest` |
| 取消是一个**请求**,不是一个状态 | `ExportJob.requestCancel`、`ExportJobTest` |
| **process-manager 不是作业队列**,四条量出来的理由 | `NotAJobQueueTest`、`ExportAsProcess` |
| 可续传的上传 = 每个请求都能重放 + 服务端说还缺什么 | `ChunkedUploadTest`、`ImportController` |
| 分片收据与进度一样,都不进聚合 | `ChunkTally`、`twentyChunksLeaveTheBatchAggregateUntouched` |

## 试着弄坏它

逐个单跑并量过(结果见分析文档 §11):

```bash
# 认领 SQL 去掉 version = version + 1   → 1 红 / 77,而且是"没有抛异常":过期的取消静默提交
# 去掉租约 owner 栅栏                    → 4 红 / 77,被取代的 worker 那次运行被落盘
# 去掉分片的 requireOpenFor              → 3 红 / 77
# 去掉流式读的事务前置检查                → 1 红 / 77 —— 套件里唯一挡在静默整表缓冲前面的东西
# 从 mapper 删掉 fetchSize               → 1 红 / 77,且行为层面全绿。这个回归没有行为症状
```

## 库的一个 issue(本篇发现,已修)

**issue-00169**(P2,文档):手写 SQL 与版本化聚合共存时必须自己 `version = version + 1`,
库里一处都没写过。`MybatisPlusAggregateRepository` 的类 javadoc 已补上这条前提(以及它的孪生前提
issue-00171 的版本列默认值)。

顺带纠正本篇当初报 issue 时写错的一句:原文说"库自己的租约中继一直这么做"。**不成立**——outbox 与
process-manager 的表根本没有乐观锁版本列(`aipersimmon_outbox` 的 `version` 是事件类型版本,
process-manager 四表零命中),没有列就无所谓遵守。"两个写入者共存于一张带版本列的表"整个仓里只有本篇,
`reconciliation/infrastructure/package-info.java` 说的正是这件事。所以新加的 javadoc 没有拿库自己的
中继当示范。

## 不在本篇范围内

多实例的产物存储(本地目录对两个副本是错的,已在 `FileArtifactStore` 点名)、multipart/content-range、
作业与产物的保留期清理、队列优先级与公平、SSE/WebSocket 推送进度、多租户(S13)。
