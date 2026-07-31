---
id: issue-00133-tenant-isolation-trusts-whoever-is-in-the-process
type: issue
role: main
status: open
---

# 租户隔离信任进程内的所有代码——而框架其余部分谁都不信

2026-07-30 全面评审（P0）。

## 问题

三处相互放大的软点：

1. `TenantContext.setRequired(boolean)` 是 public static（`aipersimmon-ddd-tenancy/.../
   TenantContext.java:125-127`）——任何进程内代码可在运行期把 fail-closed 翻成哨兵回退，
   守卫只有一句 javadoc（"Bootstrap only"）。
2. `CommandContext` 构造器公开且 `tenantId` 是裸 `String`（`aipersimmon-ddd-cqrs/.../
   CommandContext.java:37-38`）；`sendAs` 在 `RegistryCommandBus.java:145-147` 无任何运行时
   守卫。任何持有 `CommandBus` 的 bean 可用伪造的 cause 调 `send(command, cause)`，
   `TenantContextCommandInterceptor.java:31` 随后忠实地把伪造租户绑定到整个处理链。
3. 唯一防线是 ArchUnit 规则（`aipersimmon-ddd-archunit/.../CqrsRules.java:48-70`），而它只在
   消费方主动运行 archunit 测试时生效——该模块是可选 test 依赖。

## 根因（第一性）

- 期望：数据隔离是最不该靠纪律维持的性质。
- 分歧机制："进程内代码可信"是一个可辩护的立场，但与框架其余部分"不信任、启动即验证"的
  姿态（`CommandTransactionGuard`、`DurableIntegrationEvents` 标记、乐观锁版本证人检查）
  不一致——同一框架里最贵的性质拿到的守卫反而最弱。
- 真根因：租户身份在 cqrs 边界处从 `TenantId` 值对象降级为裸字符串，绕过 `Tenants.of` 的
  前缀校验伪造 `__root__` 不需要经过任何显式"我在信任边界"的动作。

## 复现（先写失败测试）

测试直接 `new CommandContext(...)` 伪造 `__root__` 租户并经 `sendAs` 派发，断言被拒绝。
修复前它畅通无阻。

## 改法

任选其强（可组合）：

- `setRequired` 收窄为包私有 + auto-configuration 经一次性 binding 对象设置，设置后冻结，
  二次调用抛异常。
- `CommandContext.tenantId` 类型化为 `TenantId`（cqrs 已依赖 tenancy，零新增耦合）——伪造
  至少要经过 `Tenants.fromValue` 这个显式信任边界动作。pre-production，破坏性改动可接受。
- `sendAs` 默认关闭，由 relay 经构造器注入的能力令牌开启，而不是 public override。

## 验证结果

未修复。

## 关联

- allow-list 缺守卫（scaffold 侧）：[[issue-00132-a-table-nobody-registered-is-visible-to-everyone]]
