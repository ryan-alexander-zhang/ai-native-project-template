---
id: issue-00102-failed-operations-are-not-recorded-under-an-outer-transaction
type: issue
role: main
status: resolved
parent: report-00003-ddd-library-review-2026-07-29
---

# 外层事务里派发的命令，失败审计被静默跳过；两个默认策略又把常见失败判错

## 问题（现状，file:line 为证）

### C3：只要外层已有事务，失败记录就被跳过——无日志、无指标

- `FailedOperationLogInterceptor.intercept`（旧 `:89,93`）：

```java
boolean nested = transactionState.hasActiveTransaction();
try { return invocation.proceed(); }
catch (RuntimeException failure) {
  if (!nested && definition.isPresent()) { recordQuietly(...); }
  throw failure;
}
```

- 语义意图是"我是嵌套子命令，让根去记录"（`TransactionState` javadoc 明确这么写）。
- 但**根派发也可能看见活动事务**：最外层 `commandBus.send()` 从
  `@Transactional` 服务方法、调度任务、`@EventListener` 里被调用——这是消费方极常见的写法，
  框架不阻止也无从分辨。此时 `nested == true`，而**根本不存在会记录的"根"**：
  整条流程的每一个失败都被跳过，且这个跳过是设计上的静默——不打日志，也不触发
  `OperationLogMetrics.failureRecordLost`（那个指标只在准备/写入抛异常时才响）。
- 审计日志丢的恰好是它存在的理由：失败行。
- 两个加重情形（都已核实）：
  - 根命令没有 definition 而嵌套子命令有时，子命令的失败也丢——`:93` 判的是根的 `definition.isPresent()`；
  - 根确实记录时，记的是**根的** operation code / target，真正失败的子操作从不出现在审计里。
- 不对称：`CompletedOperationLogInterceptor` 没有这个判定，所以嵌套子命令有成功行、永远没有失败行。

### M1：Hibernate 的约束冲突被按 simple name 误判为「事务未启动」

- `DefaultFailureCompletionPolicy`（旧 `:17`）：`"ConstraintViolationException".equals(t.getClass().getSimpleName())`
  → `Completion.NOT_STARTED`。
- `org.hibernate.exception.ConstraintViolationException`（唯一/外键冲突在 flush 时抛出，被 Spring 包成
  `DataIntegrityViolationException`）**simple name 完全相同**。
- 场景：JPA 消费方，处理器插入一行违反唯一键 → 事务**已启动并回滚** → 审计行写
  `Completion=NOT_STARTED`。completion 维度是这个组件的设计核心，而它给出了与事实相反的答案。
- 该策略自己的测试（旧 `DefaultFailureCompletionPolicyTest:13`）就用一个**同名的假类**来通过，
  等于把"匹配冒名者"这件事固定成了预期。

### M2：Bean Validation 拒绝被记成 `FAILED/unexpected` 而非 `REJECTED`

- `ValidationCommandInterceptor`（order 100）抛 `jakarta.validation.ConstraintViolationException`，
  它既非 `ConcurrencyConflictException` 也非 `DomainException`，于是落到
  `DefaultFailureClassifier` 的兜底分支 → `FAILED` + `unexpected`。
- 而 `Outcome.REJECTED` 的定义原文就是 "rejected by a business rule, **validation**, or authorization
  decision"。三条常规路径之一被系统性判错，且每个格式错误的请求都会去抬高 `FAILED` 计数器
  （`AppendTags` 指标按 outcome 打标）。

## 根因（第一性）

1. **观察 vs 期望**：期望"失败的操作一定留下一行审计"；实际"只有在派发时线程上没有事务时才留下"。
2. **最小机制（C3）**：判定用的信号是"当前线程有没有活动事务"，而它**无法区分**两件不同的事——
   "我是别人事务里的子命令" 与 "我的调用者恰好开了事务"。这两者在 `TransactionSynchronizationManager`
   眼里完全一样。
3. **真根因（C3）**：为了省一次 `REQUIRES_NEW` 的挂起-恢复，把"谁负责记录"做成了**推断**而非事实。
   推断依赖一个观察不到的前提（"活动事务 ⇒ 存在一个会记录的外层拦截器"），前提不成立时
   失败模式是静默丢数据——而这是一个审计组件。省下的是连接压力，丢掉的是审计完整性，取舍反了。
4. **最小机制（M1/M2）**：两处都用"类型的名字"做语义判断。simple name 不是身份——
   `jakarta.validation` 与 `org.hibernate.exception` 下同名的两个类含义相反
   （前者：输入被拒，什么都没开始；后者：事务开了又回滚）。
5. **真根因（M1/M2）**：同一个语义问题（"这是不是一次输入校验拒绝"）被两个模块各自用字符串重新回答了一遍，
   于是它们既可以各自答错，也可以互相答得不一致。
6. **排除的伪根因**：不是 `REQUIRES_NEW` 不好用（`SpringIndependentTransactionRunner` 本来就是
   `PROPAGATION_REQUIRES_NEW`，具备挂起能力，只是被那个判定绕过了）；
   不是 `failureRecordLost` 指标没接（它接了，但跳过路径根本不经过它）。

## 复现（test-first）

```java
// C3:派发发生在调用方事务内
TransactionTemplate callerTransaction = new TransactionTemplate(txManager);
assertThrows(IllegalStateException.class,
    () -> callerTransaction.executeWithoutResult(s -> bus.send(new FailingUpdate("res-5"))));
assertEquals(0, businessCount(jdbc, "res-5"));   // 调用方事务回滚
assertEquals(1, logCount(jdbc, "res-5"));        // 修复前:0 —— 失败审计静默丢失
```

```java
// M1:同 simple name、不同包的冒名者
static final class ConstraintViolationException extends RuntimeException {}   // 非 jakarta.validation
assertEquals(Completion.ROLLED_BACK, policy.decide(new ConstraintViolationException()));
// 修复前:NOT_STARTED
```

```java
// M2
var out = classifier.classify(new jakarta.validation.ConstraintViolationException("bad", Set.of()), null);
assertEquals(Outcome.REJECTED, out.outcome());   // 修复前:FAILED / "unexpected"
```

## 修复

**C3：删掉推断，每个失败的命令记录自己的失败。**

- `intercept` 不再读事务状态：`definition.ifPresent(d -> recordQuietly(command, context, d, failure))`。
- `TransactionState` 接口与它的 bean 随之删除——它只服务于这个被删掉的判定，留着就是死代码。
- `SpringIndependentTransactionRunner` 现在是**真正的挂起-恢复**，javadoc 据此改写，并写明代价：
  被挂起的事务仍持有自己的连接，故每层嵌套多占一个连接，连接池按"每请求恰好一个连接"配的部署需要留余量。
- 副作用（有意为之）：一个子命令失败向上冒泡时，子与父各记一行。这不是重复——两个操作都被尝试过、
  都失败了，审计本就该都说。两行的幂等键含 operation code，不会互相冲突。

**M1/M2：把"是不是 Bean Validation 拒绝"收口到一处。**

- 新增 `BeanValidationFailures.isBeanValidation(Throwable)`（放在 engine 的 classifier 包，
  两个模块都能用），**按包前缀 `jakarta.validation.` 匹配**并走 cause 链；仍用字符串以免 engine
  硬依赖 Bean Validation API。
- `DefaultFailureCompletionPolicy` 与 `DefaultFailureClassifier` 都改用它，
  于是两者不可能再对同一个异常给出不一致的判断。
- 分类器新增分支：Bean Validation → `REJECTED` + `validation.rejected` + `VALIDATION`（该类目已存在）。

## 验证结果

- **库全量 `mvn verify`（47 模块，含 Testcontainers PG/MySQL 与全部质量门禁）：BUILD SUCCESS**；
  脚手架 `multi-module` 全量 verify 亦 SUCCESS。
- C3 的护栏是端到端的：`OperationLogEndToEndScenarios` 新增场景 5，在 **H2 与真实 PostgreSQL 两处**
  都跑（该 scenario 类被 `OperationLogEndToEndH2Test` / `OperationLogEndToEndPostgresTest` 共用）。
- 反转了 `failed_nested_defers_to_root` —— 它把被删掉的跳过固定成了预期，
  现名 `failed_records_even_when_a_transaction_is_already_open`。
- `DefaultFailureCompletionPolicyTest` 改为对**真实** `jakarta.validation.ConstraintViolationException`
  断言（新增 test-scope 依赖），并保留一个同名不同包的冒名者断言 `ROLLED_BACK` 作为回归护栏；
  `DefaultFailureClassifierTest` 同样两侧都测。
- **仍然开着的相邻问题**（见 `report-00003` §2）：`writeQuietly` 只 catch `RuntimeException`，
  `Error` 仍会替换业务异常（评审列为 minor，未动）；成功路径上模板渲染失败仍会连带回滚业务操作（M4）；
  租户仍取自 `TenantContext` 而非权威的 `CommandContext.tenantId`（M5）；
  `tenant.enabled` 仍是无人读取的死配置（M6）。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（C3 / M1 / M2 由本 issue 结掉）
- 设计：[[design-00008-operation-log-component]]、[[decision-00017-operation-log-component-boundaries]]
