---
id: issue-00120-mariadb-was-support-nobody-had-declared
type: issue
status: resolved
blocks: [issue-00119-ten-majors-were-never-scheduled]
---

# MariaDB 是一份没有人声明过的"支持"

## 起点：先问它是怎么进来的

`report-00003` 把这条写成"MariaDB 被识别为 mysql 走 SKIP LOCKED，10.6 之前不支持 →
每轮语法错误"，`issue-00119` 据此排在第一档，修法写的是"走 AtomicUpdate，或按版本探测后拒绝启动"。

**动手前先查它的来历，结论把修法整个换掉了。**

## 全树只有三行，而且是同一句话

```
ProcessDialectFactory:48                        contains("mysql") || contains("maria")   → "mysql"
ProcessManagerMybatisPlusAutoConfiguration:185  contains("mysql") || contains("maria")   → "mysql"
AipersimmonFlywayMigrator:171                   contains("mysql") || contains("mariadb") → "mysql"
```

除此之外**零**：没有 MariaDB 迁移目录（只有 `postgresql` / `mysql` / `h2`）、没有 MariaDB 测试、
`SharedContainers` 只有 PG 与 MySQL 两个容器、CI 与 compose 一个字都没有、任何 decision 文档都没提过。

**没有人决定过支持它。** 三处探测各自顺手加了一个别名，依据是"MariaDB 兼容 MySQL"。

## 最能说明问题的是 Flyway 那一处

别名与错误信息**紧挨着，而且互相矛盾**：

```java
if (name.contains("mysql") || name.contains("mariadb")) {
    return "mysql";                                    // ← 悄悄接受
}
throw new IllegalStateException(
    "Unsupported database ... Supported vendors: h2, postgresql, mysql");
                                              // ↑ 明说不支持
```

**框架自己的错误信息否认了它上面三行代码的行为。** 而错误信息是对的。

## 那个"兼容"假设，恰好在它唯一不成立的地方被用上

| | `FOR UPDATE SKIP LOCKED` |
|---|---|
| MySQL | 8.0，2018 |
| MariaDB | **10.6，2021** |

MariaDB 从 MySQL 5.5 分叉，5.7→8.0 那批并发原语没有跟。所以"兼容"在 DDL 层大体成立
（已核实：那些 mysql 迁移里没有 `CHECK` / `JSON` / `GENERATED` / 函数索引这类 MySQL-8 专属语法），
**在 claim 语句这一层不成立**——而方言选择决定的正是这一条语句。

**同一个别名，在 Flyway 里大体无害，在方言选择里是每轮语法错误、effect 永不投递、且不 fail-fast。**
这正说明"X 兼容 Y"不是一个能一次性全局做出的决定：它得逐个能力问。

## 修法：删掉别名（用户 2026-07-30 在 A/B 之间选了 A）

| | 做法 | 代价 |
|---|---|---|
| **A（采用）** | 删掉三处别名，落到已有的 fail-fast | 真在 MariaDB 上跑的人启动失败——但那条路本来就没测过，且 claim 每轮报错 |
| B | 认领它：`maria → AtomicUpdate` + 一个 MariaDB Testcontainers 测试 | CI 永久多一个容器；这是"新增一个受支持的数据库"，是产品决定 |

**版本探测这条在查清来历后被划掉**：`AtomicUpdateProcessDialect` 是方言无关的条件 UPDATE（H2 走的就是它），
在所有 MariaDB 版本上都对；探版本只是为了拿回一点吞吐，而那是一个从未测过的平台上的吞吐。

三处必须**一起**决定：Flyway 建完表、进程管理器再拒绝启动，是**半成功**——
schema 在了、应用起不来，而且没有任何东西说明这两个决定出自不同的代码。

## 顺带合并了两份相同的探测

pm-jdbc 与 pm-mybatis-plus 那两处**逐字节相同**，且两者本就都依赖 engine，
故合并为 engine 里的 `ProcessVendors`。理由不是去重本身：
**两个后端如果对同一个数据库给出不同答案，就会一个正常投递、另一个每轮失败。**
错误信息里的属性名不同，作为参数传入。

## 验证

两条测试专钉**拒绝**——这正是别的测试看不见的部分：被接受的产品会静默拿到一个方言，
而那个方言的 SQL 在它上面能不能解析，是在生产里一次一轮地发现的。

**负向对照**：把任一处别名加回去，对应的测试立刻变红。

**查证时顺带确认的一件事**：Flyway 那处先调 Spring 的 `JdbcUtils.commonDatabaseName`。
若它把 MariaDB 归一成 MySQL，删别名就是空操作。**已反编译 spring-jdbc 6.1.5 核实**：
它只归一 DB2 与 Sybase 家族，MariaDB 原样返回——删除确实生效，不会在下一层被架空。

## 关联

- 父：[issue-00119-ten-majors-were-never-scheduled](issue-00119-ten-majors-were-never-scheduled.md)（第一档）
- 报告原文与"方言化 claim 是负债而非资产"的判断：
  [report-00003-ddd-library-review-2026-07-29](../report/report-00003-ddd-library-review-2026-07-29.md)、[issue-00108-a-killed-relay-instance-stops-all-delivery](issue-00108-a-killed-relay-instance-stops-all-delivery.md)
- 仍未做的另一半（deadline claim 在 PG/MySQL 上零覆盖）：见 `issue-00119` 排期第 7 档
