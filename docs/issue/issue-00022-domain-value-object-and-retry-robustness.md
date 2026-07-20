---
id: issue-00022-domain-value-object-and-retry-robustness
type: issue
role: main
status: resolved
parent: plan-00003-durable-process-manager-implementation
---

# 一组值对象/重试健壮性缺陷(EncodedPayload 值语义、ProcessView 不变量、亚毫秒退避、Jackson 异常包装)

一组低危、彼此独立但同属"契约健壮性"的缺陷,合并处置。

## 1. `EncodedPayload` 破坏值语义(byte[] 无 equals/hashCode)

- `codec/EncodedPayload.java:12`:record 含 `byte[] data`,构造与访问都 `clone()`(意在做值对象),却未覆写
  `equals`/`hashCode` → 编译器生成的 record `equals` 按数组**引用**比较。两次 `encode(x)` 得到字节相同的实例却不相等,
  任何以其为 key 的 `Set`/`Map`、去重或断言都会误判。
- **修复**:覆写 `equals`/`hashCode`/`toString`,用 `Arrays.equals`/`Arrays.hashCode`。

## 2. `ProcessView` 未强制领域不变量

- `runtime/ProcessView.java:40-68` 只做 null 校验,允许构造出领域上非法的视图(终态无 outcome、SUSPENDED 无 resume 信息等),
  与 `ProcessDecision` 已强制的不变量不一致。
- **修复**:强制 `终态 ⟺ outcome 存在`、`SUSPENDED ⟺ resumeLifecycle/suspensionReason 存在`。

## 3. 亚毫秒 `initial` 退避退化为热重试

- `retry/ExponentialBackoffPolicy.java:57`:用 `initial.toMillis()`,`initial < 1ms` 通过构造校验却截断为 0,
  所有退避为 0 → 紧凑重试循环。
- **修复**:构造时拒绝 `initial.toMillis() == 0`(与"非法值 fail-fast"一致)。

## 4. Jackson codec 只包 `IOException`

- `autoconfigure/codec/JacksonPayloadCodec.java:44,53` 与 `JacksonStateCodec` 只 `catch (IOException)`;`ObjectMapper` 抛的
  非 `IOException` 运行时异常(类型/泛型不匹配等)会裸奔,不被映射为 `ProcessSerializationException`。
- **修复**:`catch (IOException | RuntimeException)` 一并包装。

## 复现(test-first)

- `EncodedPayloadTest#twoEncodingsOfEqualBytesAreEqual`(core)——修复前不相等。
- `ExponentialBackoffPolicyTest#rejectsSubMillisecondInitial`(jdbc)——修复前构造成功且 `backoff` 返回 0。
- `ProcessViewTest#terminalRequiresOutcome` / `#suspendedRequiresResumeInfo`(core)——修复前可构造非法视图。
- `ProcessManagerJdbcJacksonCodecTest` 增用例:解码非法 JSON 抛 `ProcessSerializationException`(修复前抛裸 `RuntimeException`)。

## 验证结果

- 上述回归测试通过;三模块 test 全绿(含 Testcontainers)。

## 关联

- [[plan-00003-durable-process-manager-implementation]]
