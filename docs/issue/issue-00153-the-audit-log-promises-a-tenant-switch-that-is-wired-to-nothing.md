---
id: issue-00153-the-audit-log-promises-a-tenant-switch-that-is-wired-to-nothing
type: issue
role: main
status: resolved
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

## 解决记录（2026-08-02）

**取删除，且核实后缺陷比评审所述更窄**：评审称"未绑定租户会静默盖 `__root__`"——实际不会。
默认 `OperationTenantResolver` 走 `TenantContext.effective()`，它是 fail-closed 的：tenancy 开
且未绑定时抛 `MissingTenantException`，命令失败而非盖哨兵。所以 design-00008 §6.2 的要求
（写入/唯一键/所有读取都带可信租户）早已由**全局** `aipersimmon.ddd.tenancy.enabled` 实现：
写入经 effective()、唯一键 `(tenant_id, source, idempotency_key)` 恒含租户、`OperationLogCriteria`
的 tenantId 无条件必填。真正的缺陷是配置面上同一问题有两个开关，一真一死，而死的那个
javadoc（"mandatory on write, unique key, and all reads"）与 CONFIGURATION.md（"whether to
record the tenant"）还各说各话——**两份文档对同一个死开关给出两种承诺，本身就是它从未被
实现的旁证**。

- 删除 `OperationLogProperties.Tenant`（全树唯一读者是绑定测试）；类 javadoc 写明"刻意没有
  per-component 租户开关"及理由（同一问题两个开关只会让它们互相矛盾）。
- CONFIGURATION.md 删行，补一段真实机制的说明（enforcement 跟随 tenancy.enabled；读取
  无条件租户限定）。
- 绑定测试三处同步；Spring 对未知属性默认忽略，已设置该属性的消费方不会启动失败。
- operation-log-engine 25 例全绿。
