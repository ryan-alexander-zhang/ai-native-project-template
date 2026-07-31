---
id: issue-00136-the-second-process-pays-the-boilerplate-again
type: issue
role: main
status: resolved
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

2026-07-31 修复，四项全落地。API 级缺口的红以"修复前编译失败"成立（issue-00129 同款论证）；
行为级检查各有失败测试。

1. **端口补齐 + by-key 重载**：`findRef(ProcessType, ProcessBusinessKey)` 升入 `ProcessQuery`
   端口（`DefaultProcessQuery` 只余 `@Override`）；`ProcessRuntime` 增
   `handle(type, businessKey, input, cause)`，engine 实现按 `cause.tenantId()` 域内解析
   （非锁定读，by-ref `handle` 自己再加锁）、缺实例抛 `ProcessNotFoundException`（新增
   by-key 构造器）。新测试：by-key 推进命中同实例、未知键点名拒绝、**他租户同键不可达**。
   scaffold `RuntimeOrderFulfilmentProcess` 两个协作者全部换成端口——engine 类 import 清零，
   手写 `handle` helper 连同它的 `IllegalStateException` 一起删除；`orderCancelled` 的
   "无实例是业务常态"路径保留 `findRef` 先查。

2. **`ProcessDecision` 工厂四件套 + `HasStep`**：`running/compensating/completed(outcome)/
   ignored(context, state, input)` 静态工厂；可选接口 `HasStep.processStep()`（不叫 `step()`——
   消费方 state record 的组件访问器多半已占用该签名）。step 双写的**检查**放在紧凑构造器：
   state 实现 `HasStep` 而显式 step 与之不符即拒绝——不只是"可以省略"，是"想写错也写不进"。
   `ignored` 从 context 取 lifecycle+step、自动生成 `ignored:<step>:<InputType>` 码。
   scaffold definition 的四个私有工厂 + `decision` helper（65 行）删除，全部决策点 step
   参数消失；`ignore` 只剩 ReadyForFulfilment 的流程私有拒绝。

3. **mixin 注册入口，手写 codec 删除**：catalog 增 `mixIn(target, mixInSource)`（同目标二次
   注册即拒）；Jackson 层把 mixin 施加在 **mapper 副本**上（应用共享 mapper 永不被改，
   有测试钉住）。**比 issue 预测多一层**：Jackson 对未映射子类型的默认行为是回退类简单名——
   encode 在 advance 事务里"成功"，decode 在 relay 里变毒丸（试过
   `REQUIRE_TYPE_ID_FOR_SUBTYPES`，对 `Id.NAME` 序列化不生效）；故框架在注册表构建期强制
   **sealed 目标的 mixin 必须覆盖全部 permitted subclasses**，缺一即启动失败点名——比旧手写
   codec 的 encode 期 default 分支更早更全。scaffold：`CancellationReasonMixIn`（四变体全映射，
   判别符即线上契约）+ `CancelOrder` 回归 catalog 一行；手写 codec 连同两个真 bug
   （split 后未校验字段数的 AIOOBE、null 写成 `"null"` 字面量）与 US 分隔符格式整体删除。
   `OrderFulfilmentCodecsTest` 重写为经真实 Jackson 层构建的 codec 钉 JSON 线格式（判别符
   非类名、含 0x1F 的自由文本往返、未派发变体也忠实往返、畸形载荷点名拒绝）。同
   `(type, version)` 换线格式是刻意决定：pre-production，无存量行。

4. **`declaredPayloads()` 启动对账**：`ProcessDefinition` 默认空集=不校验（纯增益 opt-in）；
   validator 汇总**全部**缺失后一次性报（不让部署一次重启发现一个）。scaffold definition
   声明全部 16 个载荷类。新 `ProcessManagerStartupValidatorTest` 三条。

验收标尺（issue 原文）：scaffold codecs 文件 245→113 行，definition 净减 ~60 行样板，
第二条流程不再需要手写 codec/决策工厂/engine import。库全 reactor `clean install`
BUILD SUCCESS（jdbc/mybatis-plus 后端未破坏，两个 worker 测试假件补齐重载）；scaffold
`clean test -pl start -am` 全绿（验收套件含全流程、双租户、Kafka、死信重放）。

## 残余取舍（在案）

- 旧 codec 的 "本流程不派发此 reason 即拒绝 encode" 是把流程规则放进序列化层；新世界里
  codec 忠实编码整个类型，"可派发哪些 reason"回到 definition 的构造点强制。语义边界更对，
  但少了一道运行期护栏——由 sealed-coverage 启动检查与 definition 单测补位。

## 关联

- codec null 字面量与失败码为空的叠加：[[issue-00131-one-side-allowed-null-the-other-side-threw]]
- 因果链断裂（同为流程域的框架缺口）：[[issue-00137-the-bridge-starts-a-new-causal-chain]]
