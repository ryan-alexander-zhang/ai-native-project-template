---
id: issue-00173-the-precheck-order-annotation-is-ignored
type: issue
status: resolved
blocks: [analysis-00022-samples-validation-layers]
---

# Issue：预检的 `@Order` 被忽略——两个都成立的拒绝里，客户端被告知哪一个取决于类名字母序

> `ObjectProvider.stream()` 不排序，所以 `CommandPrecheck` 的执行顺序是 bean 注册顺序而不是 `@Order`；应用按契约挂 `@Order` 调整拒绝优先级时，得到的错误码是另一个预检的。

## 1. Problem

- Observed：同一命令上的两个预检 `@Order(10)` 与 `@Order(20)`，若类的注册顺序与 `@Order` 相反，则 `@Order(20)` 先执行；两者都要拒绝时，客户端拿到 `@Order(20)` 的错误码。
  下游实践项目：`InvoiceMustBeCollectible`（`@Order(20)`）跑在 `PaymentMethodMustBeUsable`（`@Order(10)`）之前，Bruno `checkout/08` 返回 `invoicing.invoice-not-found` 而非 `payment.not-found`。
- Expected：按 `@Order` 升序执行，首个拒绝获胜。依据是 `CommandPrecheck` 的 javadoc（"all of them run, in bean order, and the first refusal wins"）与
  `analysis-00022` §"三件事" 第 1 条 / s19 `README.md:48`：**`@Order` 是契约，不是装饰**；`WarehouseMustBeOpen.java:12` 同样措辞。
- Trigger：同一命令挂两个及以上预检，且类名字母序（组件扫描的注册顺序）与 `@Order` 不一致。s19 里 `CustomerMustNotBeBlocked`(10) 字母序恰在 `WarehouseMustBeOpen`(20) 之前，所以样例从未暴露。

## 2. Impact

- Affected：所有给同一命令注册多个 `CommandPrecheck` 并依赖 `@Order` 的应用。
- Since：`de4303af`（2026-07-31，预检扩展点落地）· Still occurring：no（本 issue 修复）
- Severity：P2。不丢数据、不多写一次——命令仍然被拒绝，拒绝也仍然发生在事务之前；坏的是**拒绝的身份**：错误码、HTTP 状态、面向用户的文案来自另一条规则。
  它不会让测试变红（除非测试恰好排到反序），所以靠人工排查才会发现。

## 3. Root Cause (first principles)

1. 期望：预检列表的顺序等价于 `@Order` 升序。实际：等价于 bean 注册顺序。
2. 机制：`aipersimmon-ddd-cqrs-spring-boot-starter/src/main/java/com/aipersimmon/ddd/cqrs/spring/AipersimmonDddCqrsAutoConfiguration.java:105`（修前 `:103`）
   用 `prechecks.stream().toList()`。Spring 的 `ObjectProvider.stream()` 按注册顺序产出且**不排序**，`orderedStream()` 才应用 `@Order`/`Ordered`。
   下游的 `PrecheckCommandInterceptor.build()` 按给定 list 顺序分组（`PrecheckCommandInterceptor.java:88`），自身不排序，`intercept` 也按该顺序遍历——
   所以这一行是顺序的唯一决定点。
3. 真因：预检是框架里**唯一"顺序敏感且无人替它排序"的集合**，而它的装配沿用了同文件里另外两处顺序无关集合的写法。
   它**不是** `PrecheckCommandInterceptor` 的问题（保持输入顺序是它该做的），也不是 `@Order` 用法的问题（javadoc 与样例都要求这么写）。
- Introduced by：`de4303af feat(cqrs): give the pre-transaction check a home of its own`。该提交同时引入了扩展点、"按 bean 顺序、首个拒绝获胜"的 javadoc 契约，以及这行不排序的装配；在它之前没有预检，缺陷无从发生。

## 4. Scope (same-cause sweep)

机制 = "从 `ObjectProvider` 取集合，而集合的顺序有语义"。全仓 `main` 源码里 `ObjectProvider` + `.stream()` 的站点：

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `AipersimmonDddCqrsAutoConfiguration.java:105` prechecks | yes | yes | fixed here：改 `orderedStream()` |
| `AipersimmonDddCqrsAutoConfiguration.java:125` commandBus handlers | 取集合 | no | 按命令类型建索引，顺序无语义 |
| `AipersimmonDddCqrsAutoConfiguration.java:125` commandBus interceptors | 取集合 | no | `RegistryCommandBus.java:124` 自己 `sorted(Comparator.comparingInt(CommandInterceptor::order))`——排序用的是接口方法 `order()`，与 `@Order` 无关 |
| `AipersimmonDddCqrsAutoConfiguration.java:137` queryBus handlers / interceptors | 取集合 | no | 同上，`RegistryQueryBus.java:135` 自己排序 |
| `AipersimmonDddMessagingKafkaAutoConfiguration.java:303` upcasters | 取集合 | no | `EventUpcasterChain.of` 按 source 版本类建索引并拒绝重复，顺序无语义 |
| process-manager / mybatis-plus / operation-log 各注册表 | 取集合 | no | 已用 `orderedStream()` |
| s19 `ValidationLayersTest.everyPrecheckRunsInOrderAndTheFirstRefusalWins` | 断言顺序 | 见 §8 | 不改：它的字母序与 `@Order` 一致，两种实现下都绿，是文档不是守卫 |

## 5. Reproduction (test-first)

`aipersimmon-ddd-cqrs-spring-boot-starter/src/test/java/com/aipersimmon/ddd/cqrs/spring/PrecheckOrderTest.java`：
两个预检故意让**注册顺序与 `@Order` 相反**——`AlphaLate`（`@Order(20)`，`@Bean` 方法声明在前）与 `ZuluEarly`（`@Order(10)`，声明在后）。
若名字与顺序一致，测试在两种实现下都绿，等于没防住回归。

- Failing test：`PrecheckOrderTest::prechecksRunInAtOrderNotInRegistrationOrder` — 修前失败于
  `element at index 0: expected "zulu-order-10" but was "alpha-order-20"`
- Failing test：`PrecheckOrderTest::theLowestAtOrderRefusalIsTheOneTheClientIsTold` — 修前失败于
  `Expecting message to be: "zulu-order-10 refuses" but was: "alpha-order-20 refuses"`

## 6. Fix

- Change：`AipersimmonDddCqrsAutoConfiguration:105` `prechecks.stream()` → `prechecks.orderedStream()`，并在该行上方写明为何是 `orderedStream()`。
  `CommandPrecheck` javadoc 的 "in bean order" 改为"按 `@Order` 升序（未标注者最后），首个拒绝获胜"——原措辞把一个具体契约写成了含糊词。
- Why this addresses the root cause and not the symptom：顺序的唯一决定点就是这一行；改它，所有命令类型、所有应用一次修复，无需任何一处调用方配合。
- 惰性未受影响：supplier 仍在首次 dispatch 才求值，`orderedStream()` 与 `stream()` 一样不在装配期实例化预检，
  `HandlerInjectingTheBusStartsUpTest` 覆盖的 `BeanCurrentlyInCreationException` 场景不变。
- Alternatives rejected：
  - 应用侧自己注册 `PrecheckCommandInterceptor` bean——复制框架装配，只为同一个单词之差。
  - 应用侧把两个上下文的预检并成一个复合预检——把 payment 与 invoicing 的检查耦合在一起，违背 s19 的模式。
  - 在 `PrecheckCommandInterceptor` 内部按 `@Order` 排序——把 Spring 注解的语义塞进一个与 Spring 无关的构件；它拿到的是 `List`，保持输入顺序才是它的职责。

## 7. Verification

- 修前两条新测试按 §5 预期失败。
- `mvn -o -f aipersimmon-ddd/pom.xml -pl aipersimmon-ddd-cqrs-spring-boot-starter -am verify`：BUILD SUCCESS，该模块 49/49（原 47 + 新 2），Spotless / PMD+CPD / SpotBugs 全过。
- 框架 `install` 后 s19 样例 `mvn test` 11/11，`ValidationLayersTest` 9/9——修复不改变字母序与 `@Order` 一致时的行为。

## 8. Follow-through

- Detection gap：s19 是唯一覆盖预检顺序的测试，而它的两个预检类名字母序恰好与 `@Order` 一致，所以 `stream()` 与 `orderedStream()` 下都绿。
  "顺序断言"的测试只有在**注册顺序与声明顺序冲突**时才是守卫。补的 `PrecheckOrderTest` 按这条原则构造；s19 保持原样，它的职责是讲解，不是回归网。
- Doc verdict：**code was non-conformant**——`analysis-00022`、s19 `README.md` 与 `CommandPrecheck` javadoc 所述的契约本身正确，代码没有兑现；
  仅把 javadoc 里含糊的 "in bean order" 改写为 `@Order` 升序。无 `spec`/`rule` 管辖 CQRS 预检，`analysis-00022` 是唯一权威。
- Residual state：none。没有被污染的数据；应用只需升级框架版本，无需改动自己的预检。
- Open Question（domain owner）：预检的执行顺序目前只被 `analysis` 与 javadoc 约束。是否应立为一条 `rule` BR（"同一命令的多个预检按 `@Order` 升序执行，首个拒绝获胜"）并配 GWT？本 issue 不替 owner 决定。

## Links

- Blocks: analysis-00022-samples-validation-layers
- Related: issue-00141-the-precheck-holds-a-transaction-hostage（同一扩展点的事务边界缺陷）
