---
id: issue-00153-the-audit-log-promises-a-tenant-switch-that-is-wired-to-nothing
type: issue
role: main
status: open
---

# `operation-log.tenant.enabled` 是死配置（P1）

2026-08-02 第四轮评审发现（operation-log）。

## 现象

`OperationLogProperties.java:36-47` 声明了 `aipersimmon.ddd.operation-log.tenant.enabled`，
javadoc 与 `CONFIGURATION.md` 都承诺"开启后租户成为审计行的强制维度"。全树 grep：该属性
**无人读取**。实际行为与开关无关：租户总是被记录，而未绑定租户的操作会静默把 `__root__`
哨兵盖到审计行上——多租户应用里这是审计维度的静默丢失。

## 修复要求

二选一，不许留第三态：

- **接线**：enabled 时对哨兵租户 fail（与 `TenantContext.effective()` 的 fail-closed 立场
  一致——审计行的租户列不该比业务行的宽松）；或
- **删除**：属性、javadoc、`CONFIGURATION.md` 三处一起删，行为文档化为"租户随
  CommandContext 总是记录"。

判据参照库内先例（issue-00120 MariaDB：从未有人声明支持的东西删掉而非补全）：先查该开关
的来历，若从未有过实现意图则删除是对的；若 CONFIGURATION.md 的承诺已被消费方依赖则接线。
