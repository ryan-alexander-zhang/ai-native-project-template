---
id: issue-00167-the-querybus-javadoc-denies-the-interceptor-chain-it-has
type: issue
status: resolved
---

# `QueryBus` 的 javadoc 说自己没有拦截器链，而它有（P3，文档）

2026-08-04 写 S26 时撞到。纯文档缺陷，一句话可修，但它否认的正是 S26 整篇挂靠的那个接缝。

## 现象

`aipersimmon-ddd-cqrs/src/main/java/com/aipersimmon/ddd/cqrs/QueryBus.java:5-7`

```java
 * Port that dispatches a query to its single registered {@link QueryHandler}. The query side is
 * deliberately lighter than the command side — there is no transaction or interceptor chain here —
 * because a query neither changes state nor records events. Using the bus is optional; a read port
 * may be injected directly when routing is not needed.
```

而同一个库里：

- `aipersimmon-ddd-cqrs/.../QueryInterceptor.java:4-5` —— “Around-advice applied by the
  {@link QueryBus} to **every query** before it reaches its handler”；
- `aipersimmon-ddd-cqrs-spring-boot-starter/.../RegistryQueryBus.java:18-22` —— “There is no
  transaction here … **but there is an optional {@link QueryInterceptor} chain**”；
- `AipersimmonDddCqrsAutoConfiguration.queryBus(...)` 就是把 `QueryInterceptor` 的
  `ObjectProvider` 传进去的（`:128-136`）。

端口的 javadoc 与实现、与拦截器接口本身、与装配代码三处矛盾。

## 为什么值得记一笔

`QueryBus` 是**端口**——是读接口的人最先读、也最可能只读的那一个文件。拦截器链是
issue-00150 那一伞加进来的，实现和新接口的文档都更新了，端口这句没有。结果是：想给读侧加
横切关注点（查询日志、鉴权、慢查询观测、缓存）的人，从端口文档得到的结论是“读侧没有接缝”，
于是把它写进每个 handler——这正是 `QueryInterceptor` 的 javadoc 说它要消除的那个局面。

S26 整篇就建立在这个接缝上（`CachingQueryInterceptor` 通过不调用 `proceed()` 短路，
也正是 `QueryInterceptor` javadoc 点名的两种用途之一）。

## 建议修法

把那半句改成与 `RegistryQueryBus` 一致的说法：没有事务，有可选的拦截器链，并指向
`QueryInterceptor`。一句话。

## 验收

`QueryBus`、`QueryInterceptor`、`RegistryQueryBus` 三处对“读侧有没有拦截器链”的说法一致。

## 解决记录（2026-08-05）

`QueryBus` 的类 javadoc 改成："没有事务（因为查询既不改状态也不记事件），**有**可选的
`QueryInterceptor` 链"，并点名那条链能做什么（查询日志、鉴权、慢查询观测、短路的缓存）以及
"框架自己不注册任何拦截器，所以没有拦截器的 bus 行为与从前一致"——后半句是从 `RegistryQueryBus`
与 `QueryInterceptor` 抄来的既有说法，四处现在一致。

纯文档，无测试可加。库 full 绿。
