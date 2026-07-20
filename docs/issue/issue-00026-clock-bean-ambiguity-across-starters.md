---
id: issue-00026-clock-bean-ambiguity-across-starters
type: issue
role: main
status: resolved
parent: plan-00006-middleware-integration
---

# 多个 starter 各自贡献 `Clock` bean 时,按参数名注入失效 → 上下文启动失败

## 问题(现状,file:line 为证)

- **等级:Medium**(装配组合缺陷:单独用任一 starter 不暴露,两个同时上即炸)。
- `process-manager-jdbc-spring-boot-starter/.../AipersimmonDddProcessManagerJdbcAutoConfiguration.java`:
  `processManagerClock()`(`:77`,`@ConditionalOnMissingBean(name = "processManagerClock")`)贡献一个
  `Clock` bean;`jdbcProcessRuntime`(`:155`)、`jdbcProcessQuery`(`:194`)、`jdbcProcessBacklog`(`:185`)、
  `jdbcProcessOperations`、`jdbcProcessEffectRelay`(`:239`)、`jdbcProcessDeadlineWorker`(`:258`)均以
  **参数名 `processManagerClock`** 注入 `Clock`。
- `inbox-jdbc/.../AipersimmonDddInboxAutoConfiguration.java`:`inboxClock()`(`:26`)贡献第二个 `Clock` bean,
  `jdbcInbox`/`inboxCleanup` 以参数名 `inboxClock` 注入。`outbox-jdbc` 同理有 `outboxClock()`(`:50`)。
- 当 process-manager-jdbc 与 inbox-jdbc(及/或 outbox-jdbc)**同时在 classpath**(典型:一个既编排 saga 又
  经 outbox/Kafka 收发集成事件的应用,见 [[plan-00006-middleware-integration]]),容器里出现 ≥2 个 `Clock` bean。
  `jdbcProcessRuntime` 的 `Clock` 参数按**类型**解析到多个候选 → `NoSuchBeanDefinitionException: expected single
  matching bean but found 2: inboxClock,processManagerClock` → **整个应用上下文启动失败**。

## 根因(第一性)

**两个正交缺陷叠加**,单 starter 场景都不暴露,多 starter 共存即触发:

1. **缺 `-parameters`(按名注入前提缺失)**。自动配置采用"定义名为 `X` 的 bean + 以参数名 `X` 注入"这一 Spring Boot
   惯用法(bean 名 = @Bean 方法名,始终可用)。**但按参数名消歧要求编译期保留方法参数名(`-parameters`)**;`javap -p`
   显示库 jar 的 `Clock` 形参无名。真根因:`aipersimmon-ddd-parent` **刻意不继承 `spring-boot-starter-parent`**(pom
   注释在案),因而没继承 Spring Boot 默认开启的 `-parameters`。缺它,存在多个同类型候选时按名消歧必然退化为按类型 → 歧义。
2. **clock 条件退避作用域不一致**。`process-manager-jdbc` 用 `@ConditionalOnMissingBean(name = "processManagerClock")`
   (**按名**,总是贡献自己的命名 clock);而 `outbox-jdbc`/`inbox-jdbc`/`outbox-mybatis-plus`/`inbox-mybatis-plus`
   的 clock 用裸 `@ConditionalOnMissingBean`(**按类型**)——只要容器里已有任一 `Clock`,它们就**整体退避**、不再贡献
   `outboxClock`/`inboxClock`,随后其按名注入(`Clock outboxClock`)找不到同名 bean、回退按类型 → 歧义。
3. **叠加效应还与自动配置顺序有关**:PM 的命名 clock 恒存在,type-scoped 的其余 clock 视顺序时有时无,故歧义在不同装配
   顺序下表现不同(报错里 `found 2: inboxClock,processManagerClock` 恰是"PM 命名 clock + 先跑的 inbox clock,而 outbox
   clock 已退避"的快照)。

**期望**:每个组件用自己命名的 `Clock`、彼此独立共存,按名各取所需。两缺陷合力使该期望在多组件下不成立。

## 复现(test-first)

在 `multi-module` 参考应用里,`start` 同时装配 process-manager-jdbc + inbox-jdbc + outbox-jdbc(三者各贡献一个
`Clock`)。修复前 `mvn -pl start -am verify` 的每个 `@SpringBootTest` 均因
`No qualifying bean of type 'java.time.Clock' available: expected single matching bean but found 2` 启动失败;
移除应用侧临时的 `@Primary Clock` 兜底后,该失败必现。修复后全绿且**无需**该兜底。

## 修复

对症两条根因,一并从库侧根治(不逐个注入点加 `@Qualifier`——那是治标且散落):

1. **开启 `-parameters`**:`aipersimmon-ddd/pom.xml` 的 `<properties>` 加
   `<maven.compiler.parameters>true</maven.compiler.parameters>`(maven-compiler-plugin 读取该属性),对整个库生效——
   与 Spring Boot 自己的 parent 一致。方法参数名进入字节码后,`Clock processManagerClock`/`inboxClock`/`outboxClock`
   各自按名解析回同名 bean。(`javap -v` 确认:修复后 PM 自动配置类含 25 个 `MethodParameters` 属性;须 `mvn clean install`
   强制重编译,仅改 pom 属性不触发增量重编。)
2. **统一 clock 退避为按名**:把 `outbox-jdbc`/`inbox-jdbc`/`outbox-mybatis-plus`/`inbox-mybatis-plus` 的 clock bean
   由裸 `@ConditionalOnMissingBean` 改为 `@ConditionalOnMissingBean(name = "outboxClock"|"inboxClock")`,对齐
   process-manager 既有写法。每个组件恒贡献自己的命名 clock,N 个 `Clock` 共存、各按名注入,与自动配置顺序无关;按名覆盖语义完整。

**验证**:库受影响模块自测全绿(PM starter 23 / outbox-jdbc / outbox-mybatis-plus 13 / inbox-jdbc 4 / inbox-mybatis-plus 4);
`multi-module`(PM+inbox+outbox 三 clock 共存)`mvn -pl start -am verify` 18 tests 全绿,且**移除**了 `OrderingApplication`
的 `@Primary Clock` 临时兜底。

**连带**:`multi-module/start` 里此前为绕开本问题加的 `@Primary Clock` 兜底随修复移除。

## 关联

- [[plan-00006-middleware-integration]](暴露现场:PM + inbox + outbox 三 starter 同装)
- [[issue-00019-starter-beans-not-gated-on-jdbctemplate]](同属自动配置装配退避/共存类缺陷)
- [[process-manager-schema-copies]]
