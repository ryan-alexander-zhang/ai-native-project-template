---
id: issue-00139-the-query-bus-kept-the-mine-the-command-bus-defused
type: issue
role: main
status: open
---

# 命令总线专门拆掉的那颗雷，查询总线原样留着

2026-07-30 全面评审（P1）。

## 问题

`RegistryQueryBus`（`aipersimmon-ddd-cqrs-spring-boot-starter/.../RegistryQueryBus.java:20-23`）
在构造器里立即遍历并注册全部 handler；`AipersimmonDddCqrsAutoConfiguration.java:100-102` 的
`handlers.stream().toList()` 在 `queryBus` bean 创建时立刻实例化所有 `QueryHandler`。

而 `RegistryCommandBus` 专门为完全对称的场景——handler 构造器注入 bus →
`BeanCurrentlyInCreationException`——做了惰性 registry + `SmartInitializingSingleton` 修复
（`RegistryCommandBus.java:28-41` 注释自陈），并有回归测试
`HandlerInjectingTheBusStartsUpTest`。

## 根因（第一性）

- 期望：一个组合式 `QueryHandler` 注入 `QueryBus` 分发子查询（读侧完全正当的组合），应用
  能启动。
- 分歧机制：查询侧 handler 的实例化发生在 `queryBus` bean 创建期，循环依赖在启动时炸。
- 真根因：命令侧修复时没有同步到查询侧，两条总线对同一种消费方写法给出不同的生死。

## 复现（先写失败测试）

镜像 `HandlerInjectingTheBusStartsUpTest`：一个构造器注入 `QueryBus` 的 `QueryHandler`，
断言上下文启动成功。修复前 `BeanCurrentlyInCreationException`。

## 改法

把 `RegistryCommandBus` 的 `Supplier` + double-checked registry + `afterSingletonsInstantiated`
模式原样搬过来，保留启动期重复注册检查。约 30 行。

## 验证结果

未修复。
