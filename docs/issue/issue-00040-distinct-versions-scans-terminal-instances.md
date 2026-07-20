---
id: issue-00040-distinct-versions-scans-terminal-instances
type: issue
role: main
status: resolved
parent: plan-00003-durable-process-manager-implementation
---

# `distinctVersionsInUse()` 扫描终态历史实例 → 旧 Definition/codec 版本被永久绑住

## 问题(现状,file:line 为证)

- **等级:Medium**(影响随历史留存量增长:实例表越大越慢、越难下线旧版本)。
- `store/JdbcProcessInstanceStore.java:229-237` 的 `distinctVersionsInUse()`:
  `SELECT DISTINCT process_type, definition_version, state_schema_version FROM aipersimmon_process_instance`
  ——**无 WHERE 子句**,扫全表、不分 lifecycle。对比紧邻的 `findStuck`(`:216`)恰恰正确地过滤了
  `lifecycle IN (RUNNING, COMPENSATING)`,可见同一文件里"只看活动实例"的写法是有先例的,此处却漏掉。
- Starter 把该查询结果当作 **live(活动中)** 实例消费:`ProcessManagerStartupValidator` 遍历
  `instances.distinctVersionsInUse()`(`:67`),其类 javadoc 与启动期报错文案通篇称之为 "live instance"
  (`:23`、`:72`、`:80`,例如 "a live instance of process '...' runs under definition version ... keep the old
  Definition available while instances still use it")。方法自身 javadoc(`:228`)与返回 record 注释(`:239`)
  同样写 "referenced by **live** instances" / "a **live** instance depends on"。
- 于是:已 `COMPLETED`/`FAILED`/`CANCELLED` 的历史实例也被计入,持续强制对应的旧 Definition/codec 版本保留注册
  ——运维想下线一个早已没有任何活动实例在跑的旧版本时,会被历史数据挡住;且每次应用启动的校验扫描都读**全量**
  (随历史无界增长)的实例表。

## 根因(第一性)

1. **观察 vs 期望**:文案与意图是"只有 **live** 实例(仍在推进/补偿/挂起)才拴住其 Definition/codec 版本";
   实际是"任何**曾经存在过**的实例(含所有终态)都拴住"。
2. **最小机制**:唯一的分歧点是 `distinctVersionsInUse()` 的 SQL 缺一个 lifecycle 谓词(`:230-232`),
   把终态行也纳入 DISTINCT 结果。
3. **真根因**:查询语义与其消费方(startup validator)所声明的 "live" 契约不一致——SQL 说"所有实例",
   代码与文案说"活动实例"。不是并发/时钟问题,就是缺一个 `WHERE lifecycle IN (...)` 过滤。

## 复现(已落地)

新增 `JdbcProcessInstanceStoreVersionTest`(store 层,H2):

1. `aCompletedInstancesVersionIsNotReturned`:插入一条 `COMPLETED` 且用旧版本 `v0` 的实例行,容器内无任何
   `RUNNING`/`COMPENSATING`/`SUSPENDED`;断言 `distinctVersionsInUse()` **不含** `v0`,且结果为空。修复前会返回 `v0`。
2. `aLiveInstancesVersionIsStillReturned`:再插入 `RUNNING`(`v1`)与 `SUSPENDED`(`v2`)各一;断言结果恰为
   `{v1, v2}`——挂起实例仍需其 Definition/codec 可恢复,故纳入;终态 `v0` 不返回。

## 修复(已实施)

对症 SQL 与"live instance"文案不一致,库侧一处根治:

1. `distinctVersionsInUse()` 的 SQL 加 `WHERE lifecycle IN (?, ?, ?)`,参数取
   `ProcessLifecycle.RUNNING/COMPENSATING/SUSPENDED.name()`,与 `ProcessManagerStartupValidator` 的 "live instance"
   措辞对齐;`SUSPENDED` 纳入(挂起实例仍需其 Definition/codec 可恢复)。
2. 同步更新该方法 javadoc,写明只有活动实例拴住版本、终态历史不再永久绑住旧 Definition/codec。

> 索引一项属 `*.sql` schema 归他方所有,本次未改;SQL 谓词修正已消除"终态历史绑住旧版本"的功能性缺陷。

## 验证结果

- 两个新回归测试通过;`ProcessManagerStartupValidatorTest`(starter,7 条)不回归。
- `mvn -o -pl aipersimmon-ddd-process-manager-jdbc -am test` 与 `-pl ...-spring-boot-starter -am test` 均 BUILD SUCCESS,失败/错误计数为 0。

## 关联

- [[plan-00003-durable-process-manager-implementation]]
- [[design-00004-durable-process-manager-runtime]]
