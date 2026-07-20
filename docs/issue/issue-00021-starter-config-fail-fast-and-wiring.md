---
id: issue-00021-starter-config-fail-fast-and-wiring
type: issue
role: main
status: resolved
parent: plan-00003-durable-process-manager-implementation
---

# starter 配置校验与装配的三处健壮性缺陷

一组低危、同属"启动期配置/装配正确性"的缺陷,合并处置。

## 1. `schema-validation` 取值不校验,拼写错误静默关闭表检查

- `ProcessManagerStartupValidator` 由 `@ConditionalOnProperty(name="schema-validation", havingValue="validate",
  matchIfMissing=true)` 门控(大小写敏感),而 `ProcessManagerJdbcProperties.validate()` 从不校验该值。
  `schema-validation: Validate`/拼写错误会**静默关闭**这道安全网,故障推迟到首次推进。
- **修复**:`validate()` 要求 `schemaValidation ∈ {"validate","none"}`(与门控同为大小写敏感),否则 fail-fast。

## 2. `start-duplicate-business-key` 校验比消费更严(大小写)

- `validate()` 要求精确小写 `reject`/`fold`(`ProcessManagerJdbcProperties.java:251`),而运行时用
  `DuplicateBusinessKeyPolicy.valueOf(value.toUpperCase(...))` 消费(大小写无关,`AutoConfiguration:152`)。
  `REJECT`/`Fold` 运行时能接受,却在启动校验被拒。
- **修复**:`validate()` 用 `equalsIgnoreCase` 比较。

## 3. Jackson 便利层自动配置排序脆弱

- `codec/JacksonProcessCodecConfiguration.java:28-30`:`@ConditionalOnBean(ObjectMapper.class)` 顺序敏感,却未
  `after = JacksonAutoConfiguration`(`ObjectMapper` 的来源)。求值早于 Jackson 自动配置时条件失败,便利层被静默跳过、
  catalog 被忽略、注册表建空 → 首次编解码抛 `ProcessSerializationException`。
- **修复**:`@AutoConfiguration` 增 `after = JacksonAutoConfiguration.class`(保留原 `before = 核心自动配置`)。

## 复现(test-first)

`ProcessManagerJdbcPropertiesTest`:`schema-validation=Validate` 时 `validate()` 抛异常;`start-duplicate-business-key=REJECT`
时 `validate()` 不抛。修复前前者不抛(静默关闭)、后者错误抛出。(#3 为纯装配顺序修复,由既有 Jackson slice 用例守护:
`after` 使 `ObjectMapper` 在条件求值前就绪。)

## 验证结果

- 新回归测试通过;既有 starter 用例不回归。
- starter 模块 test 全绿。

## 关联

- [[plan-00003-durable-process-manager-implementation]]
