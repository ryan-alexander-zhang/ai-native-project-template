---
id: issue-00168-the-audit-classifier-records-every-application-refusal-as-unexpected
type: issue
status: resolved
---

# 操作日志把每一次 application 层的业务拒绝都记成 `FAILED` / `unexpected`（P2，可运维性）

2026-08-04 写 S27 的 samples 时撞到（`aipersimmon-ddd-samples/s27-soft-delete-and-erasure`）。
不是正确性缺陷——行写进去了、码也对得上一半——是**分类错误**：一次 404、一次 409，在审计表里与
"数据库炸了" 无法区分，且它自己带的 `ErrorCode` 被丢掉。

## 现象（实测）

S27 的擦除命令在 outbox 未排空时拒绝，抛的是

```java
throw new ApplicationException(
    CustomerErrorCode.ANNOUNCEMENTS_STILL_QUEUED,   // code=customer.announcements-still-queued, category=CONFLICT
    "... have not been delivered yet ...");
```

审计行里量到的是：

| 列 | 实测值 | 应该是 |
| --- | --- | --- |
| `outcome` | `FAILED` | `REJECTED` |
| `failure_code` | `unexpected` | `customer.announcements-still-queued` |
| `failure_category` | `UNEXPECTED` | `CONFLICT` |

（断言写在 `ErasureAndAuditTest.arefusedErasureIsAudited`，就地断言实测值并指向本 issue。）

对照：S14 里领域拒绝（`DomainException` 的子类，S1 的 `ordering.order-not-confirmable`）记的是
`REJECTED` + 本上下文自己的码 + `CONFLICT`。**同样是"业务不允许"，只因为抛的基类不同，一个进
REJECTED 一个进 unexpected。**

## 原因（一处 `instanceof` 链缺一个分支）

`aipersimmon-ddd-operation-log-engine/src/main/java/com/aipersimmon/ddd/operationlog/engine/classifier/DefaultFailureClassifier.java:20-40`

```java
if (failure instanceof ConcurrencyConflictException) { ... FAILED, CONCURRENCY ... }
if (failure instanceof DomainException domain) {
  String code = domain.errorCode().map(ErrorCode::code).orElse("domain.rejected");
  ...
  return ClassifiedOutcome.rejected(code, category, "business rule rejected");
}
if (BeanValidationFailures.isBeanValidation(failure)) { ... rejected ... }
return ClassifiedOutcome.failed("unexpected", ErrorCategory.UNEXPECTED.name(), "unexpected error");
```

`ApplicationException` 没有分支，于是落到最后一行。而 `ApplicationException` 恰恰是库自己为这类失败
准备的基类，它的 javadoc 写着：

> Base type for exceptions raised while orchestrating a use case — failures that are not domain-rule
> violations, **such as a missing aggregate or a conflicting request**.

并且它**也带 `ErrorCode`**（`errorCode()`，与 `DomainException` 同形）。库里现成的三个子类
`EntityNotFoundException`、`DuplicateEntityException`、`ConcurrencyConflictException` 全部 extends 它——
其中只有第三个被分类器认出来（而且是被认成 `FAILED`，这一条是对的）。

**所以每一个 `EntityNotFoundException`（→ HTTP 404）都在审计表里是 `unexpected`。** 这不是边角：
`EntityNotFoundException` 是本系列每个 sample 的 handler 里最常见的抛出物。

## 为什么值得修

分类器自己的注释已经把理由写好了，只是没把 `ApplicationException` 算进去：

> Malformed input is a rejection … Left in the unexpected bucket it would both mislabel the row and
> **inflate the FAILED counter with every bad request**

Bean Validation 因为这个理由被单独接住了。一次"查不到这个客户"和一次"这个邮箱已被占用"是同一类
东西——客户端错了，服务没错——现在它们每一次都在给 `FAILED` 计数加一。后果两条：

1. **告警不可用。** 挂在 `outcome = FAILED` 上的告警会被 404 淹没，于是要么被调高阈值到失效，要么被
   关掉。真正的 `FAILED`（数据库坏了、bug）从此没人看。
2. **码丢了。** `failure_code` 本该是审计行与 HTTP problem document 之间的连接点（S14 §5 量过这条
   在 `DomainException` 路上是通的），而 application 路上它是常量 `unexpected`，两边对不上。

## 建议修法

在 `DomainException` 分支后面加一个同形的分支：

```java
if (failure instanceof ApplicationException application) {
  String code = application.errorCode().map(ErrorCode::code).orElse("application.rejected");
  String category =
      application.errorCode().map(c -> c.category().name()).orElse(ErrorCategory.UNEXPECTED.name());
  return ClassifiedOutcome.rejected(code, category, "request rejected");
}
```

**顺序要紧**：必须在 `ConcurrencyConflictException` 那一条之后（它是 `ApplicationException` 的子类，
而并发冲突确实是技术性的 `FAILED`，那条判断现在是对的、不能被新分支吃掉）。

无 `ErrorCode` 时的 fallback category 用什么可以讨论：`UNEXPECTED` 保守但会保留一半的错误标签，
`DOMAIN_RULE` 与 `DomainException` 分支对称但对 application 层不太贴。倾向前者，理由是**一个没带码的
`ApplicationException` 本身就是应该被补码的**，而不是被归好类。

依赖问题已经查过：`aipersimmon-ddd-operation-log-engine/pom.xml:51` 已经依赖
`aipersimmon-ddd-application`（它就是靠这条看见 `ConcurrencyConflictException` 的），所以**这是三行的
修法，不牵动模块依赖**。

## 验收

- 一个带 `ErrorCode` 的 `ApplicationException` 记成 `REJECTED` + 那个码 + 那个 category；
- `ConcurrencyConflictException` 仍然是 `FAILED` / `CONCURRENCY`（不能被新分支抢走）；
- `DomainException` 与 Bean Validation 两条路不变；
- 不带码的 `ApplicationException` 有确定行为且不抛；
- S27 的 `ErasureAndAuditTest.arefusedErasureIsAudited` 现在断言的是缺陷现状
  （`FAILED` / `unexpected` / `UNEXPECTED`），修好后那三条断言要反过来——与 S22 的 issue-00165 同一手法：
  **把缺陷写进断言，修好就打红，不靠人记得回来收尾。**

## 解决记录（2026-08-05）

**改法与建议一致，包括 fallback category 的取舍。** `DefaultFailureClassifier` 在
`ConcurrencyConflictException` 之后、`DomainException` 之后加了 `ApplicationException` 分支：
带码就用那个码与那个 category，不带码是 `application.rejected` + `UNEXPECTED`（按 issue 里倾向的
第一种，理由写进注释：没带码的 `ApplicationException` 该补码，不该被归好类）。类 javadoc 与
`aipersimmon-ddd-application` 无关的依赖都不用动——engine 的 pom 早就依赖 application。

**测试**（`DefaultFailureClassifierTest` 从 6 条到 9 条）：

- `anapplicationExceptionWithACodeIsRejectedWithThatCode`：`EntityNotFoundException` 带
  `customer.not-found` / `NOT_FOUND` → `REJECTED` + 那个码 + 那个 category；
- `anapplicationExceptionWithoutACodeIsRejectedWithoutBorrowingACategory`：不抛，且不借
  `DOMAIN_RULE`；
- `aconcurrencyConflictIsNotSwallowedByTheApplicationBranch`：带码的 `ConcurrencyConflictException`
  仍是 `FAILED` / `concurrency.conflict` / `CONCURRENCY`。

**负向对照做了两次，第二次才是有信息量的那次。** 第一次把新分支停掉：恰好红 2 条（带码/不带码那两条），
而顺序那条**照旧绿**——说明它当时并没有在测顺序。于是把新分支移到 `ConcurrencyConflictException`
**之上**再跑：红的正是顺序那条加上既有的 `concurrency_conflict_is_failed_concurrency`
（`expected: <FAILED> but was: <REJECTED>`），这才证明那条用例真的钉住了"窄的判断必须在前"。恢复后 9 全绿。

**验收**：S27 的 `ErasureAndAuditTest.arefusedErasureIsAudited` 三条断言按预期反过来——
`REJECTED` / `customer.announcements-still-queued` / `CONFLICT`，并在 javadoc 里写明它们原先是
缺陷现状的记录。全仓查过没有别的地方断言 `"unexpected"`。库 full 绿，25 个 sample 全绿，scaffold 绿。
