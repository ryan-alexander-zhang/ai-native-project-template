---
id: issue-00019-starter-beans-not-gated-on-jdbctemplate
type: issue
role: main
status: resolved
parent: plan-00003-durable-process-manager-implementation
---

# 无 DataSource 时 starter 不干净退避,反而让应用上下文启动失败

## 问题(现状,file:line 为证)

- **等级:Medium**。
- `autoconfigure/AipersimmonDddProcessManagerJdbcAutoConfiguration.java`:store bean 皆 `@ConditionalOnBean(JdbcTemplate.class)`
  (`:110,117,124,131`),但 `jdbcProcessRuntime`(`:144`)、`jdbcProcessQuery`(`:181`)、`jdbcProcessOperations`(`:189`)、
  `jdbcProcessEffectRelay`(`:225`)、`jdbcProcessDeadlineWorker`(`:242`)、`processWorkerScheduler`(`:254`)只有
  `@ConditionalOnMissingBean`(relay/worker 另加 `@ConditionalOnProperty`),类级仅 `@ConditionalOnClass(JdbcTemplate.class)`
  (`:62`,只看 classpath)。
- 若 starter 在 classpath 但没有 `DataSource`/`JdbcTemplate` bean:store 退避,而 `jdbcProcessRuntime` 仍被实例化,注入
  `JdbcProcessInstanceStore` 失败 → `NoSuchBeanDefinitionException` → **整个应用上下文启动失败**,而不是干净地禁用 Process Manager。

## 根因(第一性)

1. **观察 vs 期望**:期望"没有数据源时,自动配置整体退避,应用照常启动";实际"部分 bean 退避、部分 bean 仍装配并因缺依赖而炸上下文"。
2. **最小机制**:退避条件不一致——底层 store 以 `JdbcTemplate` bean 存在为条件,上层 runtime/relay/... 却没有同样的条件,形成
   "上层强依赖下层、下层可缺失"的空洞。
3. **真根因**:自动配置缺一个统一的启用前提。整套 JDBC Process Manager 的存在前提就是有一个 `JdbcTemplate`,该前提应放在类级。

## 复现(test-first)

`ProcessManagerJdbcBackoffTest#backsOffCleanlyWithoutADataSource`:用 `ApplicationContextRunner` 只加载本自动配置、不提供
`DataSource`,断言 `context.hasNotFailed()` 且不存在 `JdbcProcessRuntime` bean。修复前上下文因 `NoSuchBeanDefinitionException`
启动失败。

## 修复

给 `AipersimmonDddProcessManagerJdbcAutoConfiguration` 加类级 `@ConditionalOnBean(JdbcTemplate.class)`(类已
`@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)`,该前置自动配置先注册 `JdbcTemplate` bean 定义,条件求值时可见)。
无 `JdbcTemplate` 时整套自动配置(含属性校验)干净退避。

## 验证结果

- 新回归测试通过;既有 `ProcessManagerJdbcAutoConfigurationTest`(有 H2 数据源)不回归。
- starter 模块 test 全绿。

## 关联

- [[plan-00003-durable-process-manager-implementation]]
