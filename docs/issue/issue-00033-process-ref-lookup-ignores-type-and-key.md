---
id: issue-00033-process-ref-lookup-ignores-type-and-key
type: issue
role: main
status: open
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

## 复现(test-first)

提议单测(尚未落地):构造一个持有**真实存在实例的 instanceId**、但 `processType`/`businessKey` **故意写错**的 `ProcessRef`,分别喂给 `doHandle(...)` 与 `cancelProcess(...)`。

- 期望修复后:两者在 load 后立即抛 type/key mismatch。
- 修复前:`doHandle` 用错误 codec 解码/推进真实行、`cancelProcess` 取消该真实行——均不报错(暴露缺口)。

## 修复

对症"身份校验缺失",库侧加一道 load 后自校验:

1. 在 `doHandle`/`cancelProcess`/`find` 加载 `row` 后立即 `if (!row.ref().equals(ref)) throw new ...`(type/key mismatch,fail-fast);或
2. 让 store 查询同时带 `(instanceId, processType, businessKey)` 三字段做 WHERE,行不存在即视为 mismatch。
3. 新增 type/key mismatch 回归测试作为守卫。

> 注:**不要**逐个注入点散加 `@Qualifier`/临时判断;应在 load 边界统一自校验,语义集中且可测。

## 关联

- [[plan-00003-durable-process-manager-implementation]]
- [[design-00004-durable-process-manager-runtime]]
