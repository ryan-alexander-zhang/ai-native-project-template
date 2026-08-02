---
id: issue-00154-a-missing-audit-table-rolls-back-every-audited-command
type: issue
role: main
status: open
---

# operation-log 缺启动期表探针，漏配的代价是全量回滚（P1）

2026-08-02 第四轮评审发现（operation-log × flyway 契约）。

## 现象

`AipersimmonFlywayProperties.java:25-26` 承诺组件表缺失 "fails at startup, not at the
first write"。operation-log 没有兑现：没有任何探针查 `aipersimmon_operation_log` 是否存在。

爆炸半径被成功路径的设计放大：成功侧 append **刻意 fail-closed 在业务事务内**
（`CompletedOperationLogInterceptor.java:19-26`，这个设计本身是对的——审计缺失比业务继续
更糟）。于是 `aipersimmon.ddd.flyway.components` 漏列 `operation-log` 时，生产上**每一条
带 `@OperationLog` 的命令都会回滚**，而故障点（一个配置清单）离症状（业务全挂）很远。

## 修复要求

1. sink 构造时做一次 `SELECT` 探针（存在性检查即可），缺表时启动失败并指名
   `aipersimmon.ddd.flyway.components` 这个修复入口——兑现文档已有的承诺，不发明新行为。
2. **顺带核查**其他组件对同一承诺的兑现情况（outbox / inbox / process-manager /
   web-store / tenancy 各自的表校验是否真在启动期跑）；migrator 目前只有 unknown-component
   的 WARN。缺谁补谁，一并在本 issue 内记录核查结论。
