---
id: issue-00040-distinct-versions-scans-terminal-instances
type: issue
role: main
status: open
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

## 复现(test-first)

提议回归单测(store 层):

1. 插入一条 `COMPLETED` 且使用**旧** `(process_type, definition_version, state_schema_version)` 的实例行;
   容器内不存在任何 `RUNNING`/`COMPENSATING`/`SUSPENDED` 实例。
2. 断言 `distinctVersionsInUse()` **不**返回该旧版本三元组。
3. 修复前该断言失败(今日会返回,从而在 `ProcessManagerStartupValidator` 里阻止下线旧 Definition);
   修复后通过。可再补一条:插入一条 `RUNNING` 的当前版本实例,断言其版本仍被返回。

## 修复

对症 SQL 与文案不一致,库侧一处根治:

1. 给 `distinctVersionsInUse()` 加 `WHERE lifecycle IN ('RUNNING','COMPENSATING','SUSPENDED')`,与
   `ProcessManagerStartupValidator` 的 "live instance" 措辞对齐(是否纳入 `SUSPENDED` 取决于挂起实例是否仍需其
   Definition/codec 可恢复——按当前语义应纳入)。
2. 为该 live-version 查询加合适索引(如 `(lifecycle, process_type, definition_version, state_schema_version)`
   或至少 `lifecycle`),避免启动扫描随实例表增长而线性变慢。

## 关联

- [[plan-00003-durable-process-manager-implementation]]
- [[design-00004-durable-process-manager-runtime]]
