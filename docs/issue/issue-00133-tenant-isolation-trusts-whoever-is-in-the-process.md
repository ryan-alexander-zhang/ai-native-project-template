---
id: issue-00133-tenant-isolation-trusts-whoever-is-in-the-process
type: issue
status: resolved
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

2026-07-31 修复，三个软点取前两项（可组合项中最强的两个）：

1. **`setRequired` 收窄为包私有**（commit caa86da）：`TenantEnforcement`——生命周期与应用上下文
   绑定的那个 bean——成为唯一 sanctioned mover；库内与 scaffold 的测试全部改经它切换模式。
   `TenantContextTest.theEnforcementFlagCannotBeMovedFromOutsideThePackage` 以反射钉住可见性
   （修复前红：方法是 public）。
2. **`CommandContext.tenantId` 类型化为 `TenantId`**：伪造租户从"传任何能过 isBlank 的字符串"
   变成"必须显式调用 `Tenants.of`（拒绝 `__` 前缀，哨兵不可随手命名）或 `Tenants.fromValue`
   （声明'我在信任边界'的动作）"。旧测试 `rejectsBlankTenantId` 在新世界不可表达——空白租户
   无法成为 `TenantId`，与 issue-00134 同款论证。转换纪律：来自 `TenantContext` 的直接传递
   （bus、interceptor 不再 `.value()` 往返）；进 String 槽位（outbox 行、信封、span 属性）在
   槽位处 `.value()`；从持久化/wire 字符串重建（`InboundEvents`、effect/deadline/parked 行
   重建）一律 `Tenants.fromValue`。`aipersimmon-ddd-application` 显式声明 tenancy 依赖。

**第三项（`sendAs` 能力令牌）经权衡不取**，作为在案取舍而非遗漏：(a) 类型化后经 `sendAs`
伪造租户已必须先执行上述显式信任动作，收益大头已拿到；(b) `CqrsRules` 的 ArchUnit 规则
仍守住 `sendAs` 调用方集合。若未来出现进程内不可信插件代码的场景再升级。

验证：库全 reactor `mvn -o clean test` BUILD SUCCESS；scaffold `mvn -o clean test -pl start
-am` BUILD SUCCESS（含双租户验收与全部流程测试）。改动波及库 14 个模块 + scaffold 6 处。

## 关联

- allow-list 缺守卫（scaffold 侧）：[issue-00132-a-table-nobody-registered-is-visible-to-everyone](issue-00132-a-table-nobody-registered-is-visible-to-everyone.md)
