---
id: issue-00166-the-event-listener-rules-do-not-see-transactionaleventlistener
type: issue
role: main
status: resolved
---

# 三条领域事件订阅者的 ArchUnit 规则都看不见 `@TransactionalEventListener`（P2，架构守卫）

2026-08-04 写 S26 的 samples 时撞到（`aipersimmon-ddd-samples/s26-read-side-caching`）。
不是正确性缺陷，是**守卫的覆盖面缺了一半，而缺的那一半正是库自己推荐的写法**。

## 现象（实测）

S26 有两个领域事件订阅者，在同一个文件、同一个包、订阅同一批事件，只差注解：

- `ProductCacheInvalidation.Eager` 用 `@EventListener`（事务内失效，本 sample 的反面教材）
- `ProductCacheInvalidation.AfterCommit` 用 `@TransactionalEventListener(AFTER_COMMIT)`（正确的那个）

两者都没加 `@DomainEventHandler` 时跑 `AiPersimmonDddRules.all()`：

```
Rule '... domain-event @EventListener handlers should be declared in a @DomainEventHandler class ...' was violated (2 times):
Method <...ProductCacheInvalidation$Eager.renamed(...ProductRenamed)> is not declared in classes that are annotated with @DomainEventHandler
Method <...ProductCacheInvalidation$Eager.repriced(...ProductRepriced)> is not declared in classes that are annotated with @DomainEventHandler
```

**只报了 `Eager` 两条,`AfterCommit` 一条没报。** 给 `Eager` 补上 `@DomainEventHandler` 之后
`all()` 全绿——而 `AfterCommit` 依然没有标记,依然没人管。

反向对照也做了：把 `@DomainEventHandler` **只**从 `AfterCommit` 上摘掉重跑，
`AiPersimmonDddRules.all()` **绿**，只有 sample 自己写的那条 meta-annotation 版规则红：

```
[ERROR] com.example.samples.s26.ArchitectureTest.everyDomainEventSubscriberIsMarked -- FAILURE!
Method <...ProductCacheInvalidation$AfterCommit.renamed(...ProductRenamed)> is not declared in classes that are annotated with @DomainEventHandler
```

一个 in / 一个 out，同一份代码，两次运行，差别只有注解拼法。

## 原因（一行）

`aipersimmon-ddd-archunit/src/main/java/com/aipersimmon/ddd/archunit/EventRules.java:274-280`

```java
private static DescribedPredicate<JavaMethod> areEventListenersHandling(Class<?> eventMarker) {
  return DescribedPredicate.describe(
      "@EventListener methods handling a " + eventMarker.getSimpleName(),
      method ->
          method.isAnnotatedWith(SPRING_EVENT_LISTENER)      // <-- 只看直接标注
              && method.getRawParameterTypes().stream()
                  .anyMatch(parameter -> parameter.isAssignableTo(eventMarker)));
}
```

`isAnnotatedWith` 只匹配**直接**标注。Spring 的
`org.springframework.transaction.event.TransactionalEventListener` 本身被 `@EventListener`
标注（meta-annotation），所以它一条都匹配不上。

这个谓词是**三条规则**共用的（`EventRules.java:72 / 94 / 117`）：

| 规则 | 本意 | 对 `@TransactionalEventListener` 的实际效果 |
| --- | --- | --- |
| `domainEventListenersShouldResideInApplicationOrDomain` | 领域事件订阅者不能待在 adapter | 不检查 |
| `integrationEventListenersShouldResideInAdapter` | 集成事件订阅者必须在 adapter | 不检查 |
| `domainEventListenersShouldBeAnnotatedWithDomainEventHandler` | 订阅者必须可按注解检索 | 不检查 |

三条都进了 `AiPersimmonDddRules.all()`，所以是默认门禁的三个洞。

## 为什么这个洞比它看起来要大

漏掉的不是边角写法，**是库自己在教的写法**：

- `aipersimmon-ddd-application/src/main/java/com/aipersimmon/ddd/application/DomainEvents.java`
  的类 javadoc 明确写 “a transactional implementation (an outbox row, or an
  `@TransactionalEventListener`) still commits or rolls back atomically with the state change”；
- `aipersimmon-ddd-events-spring-boot-starter/.../SpringDomainEvents.java` 的 javadoc 同样并列
  `@EventListener`（或 `@TransactionalEventListener`）；
- 现成的 sample 就在用：`aipersimmon-ddd-samples/s03-domain-events-in-process/.../NotifyCustomer.java:28`
  是 `@TransactionalEventListener(phase = AFTER_COMMIT)`——**它标注了 `@DomainEventHandler`，
  但那是靠人记得，门禁从来没验证过**。

也就是说：凡是“提交后才能跑”的订阅者（发通知、调外部、清缓存——正是**最需要**被放对层、最需要
能被检索到的那一类），全部不在守卫范围内。

## 建议修法

`areEventListenersHandling` 改成同时接受 meta-annotation：

```java
method.isAnnotatedWith(SPRING_EVENT_LISTENER) || method.isMetaAnnotatedWith(SPRING_EVENT_LISTENER)
```

（`||` 是为了不依赖 ArchUnit 对“直接标注是否算 meta”的具体语义。）按名字匹配的既有约定不变，
所以 archunit 模块仍然不需要编译期依赖 Spring。

**存量已经查过了，一条不用改。** 全仓（去掉 `target/`）用 `@TransactionalEventListener` 的领域事件
订阅者只有三处，三处都已标注 `@DomainEventHandler` 且都在 application 层：

| 位置 | 标记 |
| --- | --- |
| `aipersimmon-ddd-samples/s03-.../NotifyCustomer.java:19` | 有 |
| `aipersimmon-ddd-samples/s12-.../OrderListProjection.java:54`（该类同时有 `@EventListener` 方法，所以本来就被管着） | 有 |
| `aipersimmon-ddd-scaffold/multi-module/.../OrderFulfilmentStarter.java:35` | 有 |

所以这是一个**改完就绿**的修法，成本只有那一行谓词。反过来说也正是这一点让洞不容易被发现：
到目前为止每个人都恰好记得加注解，于是没有任何一次构建暴露过守卫没在看。

## 验收

- 一个 `@TransactionalEventListener` 的领域事件订阅者，不加 `@DomainEventHandler` 时
  `AiPersimmonDddRules.all()` 必须红；
- `@EventListener` 的行为不变（既有断言不动）；
- 一个既非直接也非 meta 标注的普通方法不被误报；
- 全仓（库 + scaffold + samples）跑一遍确认仍然全绿（按上表，应当如此）。

S26 的 `ArchitectureTest.everyDomainEventSubscriberIsMarked` 是这条规则的 meta-annotation 版，
就地写在 sample 里并指向本 issue。修好之后那条 sample 规则就是多余的，可以删掉——
它存在的唯一理由是库这条还没修。

## 解决记录（2026-08-05）

**改法与建议一致，一行谓词。** `EventRules.areEventListenersHandling` 现在是
`isAnnotatedWith(...) || isMetaAnnotatedWith(...)`，三条规则同时修好。三条规则与谓词的 javadoc
都补了"meta 也算"以及为什么这个洞正好盖住 after-commit 那一类。存量一条没改（issue 里查过的三处
都已标注），全仓复跑确认。

**测试**：新增 `fixture/aftercommit/` 一个隔离包，好处是断言可以精确——里面只有两个方法：
一个错放且未标记的 `@TransactionalEventListener`（`AfterCommitSubscriberInAdapter`），
一个只是"参数恰好是领域事件"的普通方法（`NotASubscriberInAdapter`）。四条新用例：

- 两条断言两个领域事件规则报出了前者（按名字断言，不是靠"抛了就算"）；
- 一条断言两个规则都**没有**报出后者——把谓词放宽到 meta 之后，不能退化成"接受领域事件参数就算订阅者"；
- 一条在 `bad` 包里断言集成事件规则报出了新增的 `BadAfterCommitIntegrationEventListenerInApplication`
  （三条规则里的第三条）。

另加 `fixture/good/.../GoodOrderPlacedAfterCommitHandler`（`AFTER_COMMIT` + 已标记，GOOD 仍绿）与
`fixture/bad/.../BadAfterCommitDomainEventListenerInAdapter`。archunit 模块加了 test-scope 的
`spring-tx`（`@TransactionalEventListener` 不在 spring-context 里），规则本身仍按字符串匹配，
**不引入编译期 Spring 依赖**。

**负向对照**：把谓词退回只看直接标注，`AiPersimmonDddRulesTest` 恰好红 4 条（65 用例），而且红的方式
正是这个洞的形状——两条 after-commit 规则**什么都没抛**（"Expected AssertionError to be thrown, but
nothing was thrown"），集成事件那条只报了直接标注的那个。恢复后 65 全绿。

**验收**：库 full `mvn clean install` + `mvn test` 绿；25 个 sample 全绿；multi-module scaffold 绿。
S26 的 `ArchitectureTest.everyDomainEventSubscriberIsMarked` 及其 `subscribeToADomainEvent()` 谓词
已删除（它存在的唯一理由是本条没修），把来龙去脉挪到该文件 `ddd` 字段的 javadoc 上。
