---
id: issue-00166-the-event-listener-rules-do-not-see-transactionaleventlistener
type: issue
role: main
status: open
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
