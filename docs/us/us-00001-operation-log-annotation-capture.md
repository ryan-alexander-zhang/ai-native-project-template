---
id: us-00001-operation-log-annotation-capture
type: us
role: main
status: active
parent: spec-00001-operation-log-component
---

# User Story: 注解式操作日志捕获

作为消费方开发者，
我想在 application `Command` 上声明稳定操作码、目标与安全文案模板，
以便无需改 handler 就自动记录一条操作日志。

## Requirements (EARS)

- **us-00001-FR-1**（Event）当带 `@OperationLog` 的 command 正常返回时，系统应恰好记录一条
  `outcome=SUCCEEDED, completion=COMMITTED` 的 entry，且与业务变更同事务提交。
- **us-00001-FR-2**（Optional）当注解声明了 `rejectedWhen` 谓词且其对结果投影为真时，系统应把该次正常返回记为
  `REJECTED, COMMITTED`。
- **us-00001-FR-3**（Unwanted）若带 `@OperationLog` 的 command 抛异常且 `recordFailure=true`，则系统应记录一条
  `REJECTED/FAILED`（由 `FailureClassifier` 判定），并**重新抛出原异常**。
- **us-00001-FR-4**（Ubiquitous）系统应只在启动期编译并校验注解模板；非法模板阻止启动。

## Acceptance (GWT)

- **us-00001-AC-1.1**（us-00001-FR-1）
  Given 一个带 `@OperationLog` 的 command 与已提交的业务变更
  When 它正常返回
  Then 存在恰好一条 `SUCCEEDED+COMMITTED` entry，actor/target/code/causality 正确，且与业务行同事务
- **us-00001-AC-1.2**（us-00001-FR-1）
  Given 同上
  When 业务事务因其它原因回滚
  Then 不存在虚假的 `SUCCEEDED` entry
- **us-00001-AC-2.1**（us-00001-FR-2）
  Given 一个带 `rejectedWhen` 谓词的注解 command
  When 它正常返回且谓词对结果投影为真
  Then 存在一条 `REJECTED+COMMITTED` entry，与业务事务一起提交
- **us-00001-AC-3.1**（us-00001-FR-3）
  Given `recordFailure=true` 的注解 command
  When handler 抛技术异常导致回滚
  Then 存在一条 `FAILED+ROLLED_BACK` entry，且原异常被重新抛出、未被替换
- **us-00001-AC-4.1**（us-00001-FR-4）
  Given 一个含非法属性路径 / 未知根对象的注解模板
  When 应用启动
  Then 启动失败并给出可定位的模板编译错误

## Links
- Spec: [[spec-00001-operation-log-component]] · Design: [[design-00008-operation-log-component]] · Plan: [[plan-00010-operation-log-implementation]]
