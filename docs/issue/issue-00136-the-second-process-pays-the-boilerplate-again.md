---
id: issue-00136-the-second-process-pays-the-boilerplate-again
type: issue
role: main
status: open
---

# 第二条流程会把这些样板全付一遍：process-manager 消费方工效学欠费

2026-07-30 全面评审（P1）。一条流程 ≈ definition + state + input 密封族 + 14 行 codec 注册 +
8 方法 runtime 桥 + 手写 helper——其中约三分之一可以由框架收走。四个欠费点：

## 1. `ProcessQuery` 缺按 business-key 寻址，消费方被迫 import engine 内部类

端口只有 `find(ProcessRef)`（`aipersimmon-ddd-process-manager/.../runtime/ProcessQuery.java:12-16`）。
scaffold 于是直接注入 **engine 具体类** `DefaultProcessQuery`
（`ordering-process/.../RuntimeOrderFulfilmentProcess.java:5, 33-38, 93, 109` 的
`query.findRef(TYPE, new ProcessBusinessKey(orderId))`）——依赖倒置被破坏；该端口 javadoc
自称支持 Temporal/Seata 替换，换 provider 时这行就断。而"按 business key 找实例再喂输入"
是每一个消费方的第一需求，不是引擎内部细节。

**改法**：端口加 `Optional<ProcessRef> findRef(ProcessType, ProcessBusinessKey)`；更进一步给
`ProcessRuntime` 加 `handle(type, businessKey, input, context)` 重载，
`RuntimeOrderFulfilmentProcess` 的 8 个方法立减一半样板。

## 2. `ProcessDecision` 构造样板 + step 双写无检查

`OrderFulfilmentDefinition.java:502-549` 的 `running/compensating/completed/ignore` 四个私有
工厂纯属样板，任何第二条流程都会重抄；且每个决策把 step 传两遍（`state.withStep(X)` 与
参数 `X`，如 :304-307），一旦发散，持久化的 step 与 state 内的 step 不一致而无任何检查；
`ignore` 还需要 `context.currentLifecycle().orElseThrow()`（:540-548）这种消费方不该操心的仪式。

**改法**：框架提供静态工厂 `ProcessDecision.running(state, step, code, effects...)` 等四件套
（`ignored(context, state)` 自动取 lifecycle、自动生成 code）；定义可选接口
`interface HasStep { ProcessStep step(); }`，state 实现后 step 参数省略，双写消失。

## 3. 手写 codec 的根因是假两难，连同两个真 bug 一起可删

`OrderFulfilmentCodecs.java` 存在的理由是：`CancelOrder` 携带 sealed 域类型
`CancellationReason`，团队正确地拒绝在 domain 上加 `@JsonTypeInfo`，于是只剩手写。但
Jackson 的 mixin / `registerSubtypes` 可以零 domain 污染地声明多态映射——两难是假的。
手写残余的脆弱面：:227-237 两个变体分支 `split` 后未校验字段数，畸形载荷直接
`ArrayIndexOutOfBoundsException`（发生在 relay 里即毒丸）；:170-195 `String.join` 的 null
元素被写成 `"null"` 字面量。

**改法**：框架 `JacksonProcessCodecConfiguration` 开放 mixin/subtype 注册入口，整个手写
codec 删除。次选：`CancelOrder` 扁平化为原始字段，handler 内重建 `CancellationReason`。

## 4. codec 注册漏一个，错误后置为运行期毒丸

一条流程 13 个 `.payload(...)` + 1 个 `.state(...)`（`OrderFulfilmentCodecs.java:101-131`）。
新增 input/载荷忘注册，首次 encode 在 advance/消费事务里抛 `ProcessSerializationException`。
`ProcessManagerStartupValidator.java:17-33` 只对账活实例引用的 codec，不覆盖"definition 可能
用到的 payload 都有 codec"。

**改法**：`ProcessDefinition` 增加可选 `Set<Class<?>> declaredPayloads()`（默认空 = 不校验），
startup validator 对账 catalog；纯增益 fail-fast。

## 复现（先写失败测试）

1/4 各有自然的失败测试：mock 一个非 Default 的 `ProcessQuery` 实现看 scaffold 能否编译运行；
对声明了 payload 但未注册 codec 的 definition 断言启动失败。2/3 以"第二条最小流程的代码
行数"作验收标尺。

## 验证结果

未修复。

## 关联

- codec null 字面量与失败码为空的叠加：[[issue-00131-one-side-allowed-null-the-other-side-threw]]
- 因果链断裂（同为流程域的框架缺口）：[[issue-00137-the-bridge-starts-a-new-causal-chain]]
