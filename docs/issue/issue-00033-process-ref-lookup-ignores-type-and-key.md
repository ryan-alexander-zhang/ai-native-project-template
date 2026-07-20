---
id: issue-00033-process-ref-lookup-ignores-type-and-key
type: issue
role: main
status: resolved
parent: plan-00003-durable-process-manager-implementation
---

# `ProcessRef` 只按 `instanceId` 定位行,`processType`/`businessKey` 从不参与校验

## 问题(现状,file:line 为证)

- **等级:Low/Medium**(防御性加固,非可利用 correctness bug)。原始 review 定为 **High**;本 issue **降级**并在根因中说明理由。
- `ProcessRef`(`model/ProcessRef.java:13-16`)是 `(instanceId, processType, businessKey)` 三元 record,构造器仅对三字段做 null-check,不做任何一致性约束。
- 三个调用点全部只按 `instanceId` 查行,**从不比对**持久化的 `processType`/`businessKey`:
  - **`doHandle()`**(`runtime/JdbcProcessRuntime.java`):以 `instances.findForUpdate(ref.instanceId())` 加载(store SQL `WHERE instance_id = ? FOR UPDATE`),随后用**调用方**传入的 `ref.processType()`——而非 `row.ref().processType()`——解析 definition/codec(`:340-341`,以及回写 `encodeState` `:357`)。三者中危害最大:错误 `processType` 会用**错误的 Definition/codec** 去解码并推进**真实存在**的那一行。
  - **`ProcessQuery.find()`**(`runtime/JdbcProcessQuery.java:63-64`):仅 `instances.find(ref.instanceId())`,返回 `toView(row)` 基于持久化的 `row.ref()`。会静默返回一个与传入 ref 的 type/key 不匹配的实例(只读,危害最低)。
  - **`cancelProcess()`**(`operation/JdbcProcessOperations.java:158-176`):仅 `findForUpdate(ref.instanceId())` + revision 守卫;`updateSnapshot` 的 WHERE 只有 `instance_id = ? AND revision = ?`,`process_type`/`business_key` 既不在 WHERE 也不在 SET → 纯按 `instanceId` 取消。
- 全模块 grep 无任何 `row.ref().equals(ref)` / `processType().equals` / `businessKey().equals` 的 load 后校验;`row.ref()` 仅被用于构造返回值与按 `instanceId` 写行。

## 根因(第一性)

1. **观察 vs 期望**:期望"完整 `ProcessRef` 唯一确定一个实例,type/key 参与身份校验";实际"只有 `instanceId` 参与,另两字段是 API 表象"。
2. **最小机制**:所有 store 查询的键都是 `instanceId` 单列,加载后代码直接采信**调用方**的 `ref.processType()` 去选 Definition/codec(`JdbcProcessRuntime.java:340-341`),而非采信行内持久化的 type。
3. **为何降级(排除高危)**:`instanceId` 是**全局唯一随机 UUID 且为主键**(`ProcessInstanceId` javadoc;`new ProcessInstanceId(idGenerator.get())` `JdbcProcessRuntime.java:255`,默认生成器 `() -> UUID.randomUUID().toString()`)。且 `ProcessRef` **永远由 runtime 从行铸造并回传**——`findRef` 用 `.map(ProcessInstanceRow::ref)`,各 `ProcessAdvanceResult` 携带 `row.ref()`。正常 API 路径下 `(instanceId, processType, businessKey)` 三元组内部天然一致;"真 instanceId + 错 type" 的 ref **不是 API 能产生的形态**,只能来自手工拼装、数据损坏或程序错误。故这是**加固缺口 / fail-fast 缺失**,而非活的可利用高危 correctness bug。真根因:身份校验与身份构造耦合在同一来源(runtime),缺一道独立的 load 后自校验。

## 复现(已落地)

三个回归测试,各构造一个持有**真实存在实例的 instanceId**、但 `processType` **故意写错**的 `ProcessRef`,喂给三个入口:

- `JdbcProcessRuntimeTest#handleWithARealInstanceIdButWrongProcessTypeIsRejected`:错 type 的 ref → `handle` 抛
  `IllegalArgumentException`;并断言真实行未被推进(仍一条 transition、revision 仍为 1)。
- `JdbcProcessOperationsTest#cancelProcessWithARealInstanceIdButWrongProcessTypeIsRejected`:错 type 的 ref →
  `cancelProcess` 抛 `IllegalArgumentException`;真实行 lifecycle 仍 `RUNNING`,无 `OPERATOR_CANCEL` transition。
- `JdbcProcessQueryTest#findWithARealInstanceIdButWrongProcessTypeIsRejected`:错 type 的 ref → `find` 抛
  `IllegalArgumentException`,而非静默返回不匹配的实例。

修复前三者都不报错(`doHandle` 用错误 codec 推进真实行、`cancelProcess` 取消真实行、`find` 返回不匹配视图)。

## 修复(已实施)

对症"身份校验缺失",在 load 边界统一加一道自校验:身份从行铸造、绝不采信调用方的 type/key。

1. **`JdbcProcessRuntime`**:新增私有静态 `requireRefMatch(ref, row)`,在 `doHandle` 加载 `row`(`findForUpdate`)后
   立即调用;`!row.ref().equals(ref)` 即抛 `IllegalArgumentException`,附上 supplied vs stored 的 type/key。
2. **`JdbcProcessOperations#cancelProcess`**:`findForUpdate` 后、revision 守卫前调同一形态的 `requireRefMatch`。
3. **`JdbcProcessQuery#find`**:在 `map` 内对读到的行做同一比对,不匹配即抛;实例不存在仍返回 `Optional.empty()`。

选方案 1(load 后自校验)而非"三字段 WHERE",语义集中、可测,且错 type 与"实例不存在"能区分开(前者 fail-fast、后者按各入口既有语义)。`withRetry` 只吞 `StaleProcessRevisionException`/`DuplicateKeyException`,故该 `IllegalArgumentException` 直接向上抛出。

## 验证结果

- 三个新回归测试通过;`handleOnUnknownInstanceThrowsNotFound` 等既有测试不回归(不存在的 instanceId 仍抛 `ProcessNotFoundException`,与"存在但 type 不符"区分清楚)。
- `mvn -o -pl aipersimmon-ddd-process-manager-jdbc -am test` 与 `-pl ...-spring-boot-starter -am test` 均 BUILD SUCCESS,失败/错误计数为 0。

## 关联

- [[plan-00003-durable-process-manager-implementation]]
- [[design-00004-durable-process-manager-runtime]]
