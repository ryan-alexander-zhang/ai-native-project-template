---
id: issue-00130-two-of-three-effect-handlers-forgot-the-redelivery
type: issue
role: main
status: resolved
---

# 同一批 effect 派发的三个命令，一个防了重投，两个没防

2026-07-30 全面评审（P0）。

## 问题

流程管理器 effect relay 派发的三个 ordering 内部命令，对重投的容忍三缺二：

- `BeginFulfilmentHandler.java:54-57` **有**状态容忍分支（`FULFILMENT_IN_PROGRESS` 或
  `CANCELLED` 时 no-op），javadoc（:22-25）点名理由："否则每次重复投递都变成 relay 重试到
  死信的毒丸"。
- `ConfirmOrderHandler.java:35` 直接 `order.confirm()`——`Order.java:178-182` 只允许表内迁移，
  重投抛 `DomainException`。
- `CancelOrderHandler.java:41` 直接 `order.cancel(...)`——`OrderLifecyclePolicy.java:71-101`
  对已 `CANCELLED` 的订单抛异常。

而框架契约两处写明了重投必然存在：`CommandBus.sendAs` javadoc（`aipersimmon-ddd-cqrs/.../
CommandBus.java:50-52`）说稳定 messageId 是 handler 去重的前提；`ProcessEffectRelay.java:29-32`
说 crash 在 delivered 标记前 → 同 id 重投。

## 根因（第一性）

- 期望：effect 命令的 handler 全部幂等（框架契约要求）。
- 分歧机制：命令提交与 `markDelivered` 是两次独立提交，窗口内崩溃 → 重投；重投的
  `ConfirmOrder`/`CancelOrder` 抛 `DomainException` → relay 按 `ProcessEffectRelay.java:218-245`
  重试耗尽 → `markDead`。不腐蚀状态（流程已 COMPLETED），但制造死信噪声，且操作员 redrive
  该 effect 永远失败。
- 真根因：幂等被当作逐个 handler 的自觉，而不是"由 relay 派发"这一身份自带的义务。

连带的领域侧问题：`OrderLifecyclePolicy.java:38-52` 只前置拦 `SHIPPED`，对已 `CANCELLED`
订单的取消落进 :64-68，报 `CUSTOMER_CANCELLATION_WINDOW_CLOSED`（"已进入履约"）——对一个
已取消的订单这是错误的事实陈述。

## 复现（先写失败测试）

对同一订单先正常送达一次 `ConfirmOrder`，再以同一 messageId 重投一次，断言第二次 no-op 而非
抛异常；`CancelOrder` 同理，并额外断言 no-op 路径**没有**再次调用 `credit.releaseFor`。

## 改法

- `ConfirmOrderHandler`：`status == CONFIRMED` 时直接返回。
- `CancelOrderHandler`：`status == CANCELLED` 时直接返回，且必须跳过 `CustomerCredit.releaseFor`
  （首次投递已释放，再释放就是双倍返还）。
- `OrderLifecyclePolicy` 开头加 `CANCELLED` 分支，给 `ALREADY_CANCELLED` 错误码，让非 relay
  路径（如人工重复取消）也拿到正确的事实陈述。
- `CancelOwnOrderHandler` 不需要改：它不走 relay。

## 验证结果

2026-07-31 修复。先写测试确认红（红的方式与本 issue 的预测一致）：

- `EffectRedeliveryToleranceTest.aRedeliveredConfirmOrderIsANoOpOnceTheOrderIsConfirmed` —
  修复前死于 `IllegalStateTransitionException: CONFIRMED -> CONFIRMED`；
- `EffectRedeliveryToleranceTest.aRedeliveredCancelOrderIsANoOpAndReleasesCreditOnlyOnce` —
  修复前死于误导性的 "an inventory failure only cancels an order that was cleared for
  fulfilment"，且断言了 no-op 路径不再次释放信用；
- `OrderCancellationPolicyTest.aCancelledOrderRefusesFurtherCancellationAsAlreadyCancelled` —
  修复前拿到的是 `CUSTOMER_CANCELLATION_WINDOW_CLOSED`，修复后是 `ALREADY_CANCELLED`。

改动：`ConfirmOrderHandler` 容忍 `CONFIRMED`/`SHIPPED`（SHIPPED 只能经确认到达，同样证明
本命令已落地）；`CancelOrderHandler` 容忍 `CANCELLED` 且 no-op 跳过 `credit.releaseFor`；
`OrderLifecyclePolicy` 在 SHIPPED 检查后加 reason 无关的 `ALREADY_CANCELLED` 分支。
`ordering-domain`（92）+ `ordering-application`（7）全绿；三条新测试即回归守卫。

## 关联

- 传输级去重的缺口：[[issue-00129-in-process-redelivery-was-not-deduplicated]]
