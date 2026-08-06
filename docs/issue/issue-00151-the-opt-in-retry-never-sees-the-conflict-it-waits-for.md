---
id: issue-00151-the-opt-in-retry-never-sees-the-conflict-it-waits-for
type: issue
status: resolved
---

# RetryOnConflict 对框架自身的乐观锁冲突永远不会重试（P1）

2026-08-02 第四轮评审发现，已对照源码亲证。

## 现象

`aipersimmon.ddd.cqrs.retry-on-conflict.enabled=true` 打开后，框架自身乐观锁路径上的冲突
仍然直接以 409 返回客户端，一次重试都不会发生。功能是 opt-in 开启后**静默失效**——部署方
会以为它在工作。

## 成因：异常流方向与拦截器排序相反

- `CommandInterceptor.order()` 契约（`aipersimmon-ddd-cqrs/.../CommandInterceptor.java:25-26`）：
  **lower runs further out**。
- `ConcurrencyTranslationCommandInterceptor.ORDER = 50`（外层）；
  `RetryOnConflictCommandInterceptor.ORDER = 75`（内层）。
- 两个仓储基类抛的都是 Spring 的 `OptimisticLockingFailureException`
  （`JdbcAggregateRepository.java:62`、`MybatisPlusAggregateRepository.java:93`）。
- 异常向**外**传播：先经过 retry(75)——它只 catch `ConcurrencyConflictException`
  （`RetryOnConflictCommandInterceptor.java:70`），此时异常还没被翻译，放行；再到
  translation(50) 才翻译成 `ConcurrencyConflictException`，交给 web 层作 409。

retry 的 javadoc（:23-24）"Ordered at 75: inside ConcurrencyTranslationCommandInterceptor
(50), so it sees the translated ConcurrencyConflictException" 把方向写反了：要看到翻译后的
异常，翻译必须在 retry **内层**（order 更大）。

## 为什么三轮评审没抓到

两个拦截器只有各自的隔离测试：`RetryOnConflictCommandInterceptorTest` 直接抛
`ConcurrencyConflictException`；`ConcurrencyTranslationCommandInterceptorTest` 单测翻译。
没有一条测试经真 `RegistryCommandBus` 链、由 handler 抛 `OptimisticLockingFailureException`、
断言发生了第二次尝试。

## 修复要求

1. 修正排序或捕获（三选一，取其最不惊讶者）：
   - retry 同时 catch 两种异常；或
   - translation 挪到 retry 内层（order 取 76–199 之间，注意 validation=100、precheck=150、
     transaction=200 的既有占位）；或
   - 仓储基类直接抛 `ConcurrencyConflictException`。
2. **必须补链级测试**：真 `RegistryCommandBus` + 全套拦截器按 order 装配 + handler 首次抛
   `OptimisticLockingFailureException`、第二次成功，断言重试发生且客户端拿到成功结果。
3. 同步修正 retry javadoc 的方向表述。

影响有界：退化为该功能上线前的 409，无数据损坏。

## 解决记录（2026-08-02）

取修法二：翻译拦截器 ORDER 50 → 175（precheck 150 与 transaction 200 之间）。它自己声明的
两个约束（事务外、日志内）在 175 仍然成立；retry 保持单一 catch，javadoc 就此为真；默认
（retry 关闭）路径可观察行为不变。刻意不让 retry 双 catch——将来 DuplicateKey 类 create
冲突（issue-00157）要翻译成**另一个**不可重试的类型，翻译点在 retry 内层正好让这条分界
留在类型系统里。

- 链级测试 `RetryOnConflictPipelineTest`：真总线 + 全套自动装配拦截器，handler 抛
  `OptimisticLockingFailureException`——红先行已确认（栈帧顺序即缺陷实证：翻译在 retry
  外层，1 次尝试），修后 1 冲突 2 尝试成功、耗尽 3 尝试后回落翻译后的 409。
- 既有测试 `sitsBetweenConcurrencyTranslationAndValidation` 断言的恰是缺陷排序本身——
  改名 `sitsOutsideTranslationWhichSitsOutsideTheTransaction` 并钉住修正后的三角关系。
  **教训：钉常量相对关系的测试若与异常流方向脱钩，会把 bug 当契约钉住；方向性的证明
  必须由链级测试承担。**
- README/ARCHITECTURE.md 两处链序叙述同步（顺带补上此前就缺席的 retry 与 precheck）。
