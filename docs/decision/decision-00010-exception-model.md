---
id: decision-00010-exception-model
type: decision
role: main
status: active
parent:
---

# 异常/错误体系:领域贯穿式错误码 + BusinessRule 一等抽象 + 默认 throw(不上 Result)

固化 `aipersimmon-ddd` 的**跨层异常与错误处理策略**。缺口证据与大厂/参考对照见
[[analysis-00010-exception-model]](及其引用的 [[analysis-00008-web-api-response-envelope]]);
落地模块设计见 [[design-00003-exception-model]]。本文只记**决策与理由、被拒选项**。

## Context

现状是两极分化(详见 [[analysis-00010-exception-model]]):传输层(`-web`/`-web-spring`)的 RFC 9457 线上
契约优秀,但**领域/应用层异常模型极薄**(全库仅 4 个自定义异常,`DomainException`/`ApplicationException`
只有一个 String message),两层之间还有一道断裂——领域异常**带不出**机器可读 `code`/`type`,导致
[[design-00002-web-layer]] §八 的旗舰示例产不出来。同时消息链路无重试上限、无死信。

约束不可动:[[analysis-00006-ddd-building-blocks-library]] 的**依赖一律指向内/下**、**纯净层
framework-free**(`-core` 零依赖是验收红线)。

## Decision

### 一、引入贯穿式错误码 `ErrorCode`(落 `-core`,framework-free)

- `-core` 定义 `ErrorCode`(稳定、BC 前缀、点分的 `code()` + 可选语义 `ErrorCategory`);**HTTP 状态码绝不进 `-core`**。
- `DomainException` / `ApplicationException` **可携带**(可空)`ErrorCode`;旧 message-only 构造保留(加法式)。
- `-web` 的 `ProblemType extends ErrorCode`,并提供 `ProblemTypeRegistry`:领域抛出的 `ErrorCode` 因此能被
  `-web` 认识并补齐 `type`/`status`/`title`,而 `-core` 永不认识 `-web`。**这是接通断裂的唯一合规方式**。

### 二、`BusinessRule` 一等抽象 + `AbstractAggregateRoot.checkRule()`

- 业务不变量建模为 `BusinessRule`(`isBroken()`/`message()`/可选 `errorCode()`),聚合根经 `checkRule(rule)` 触发
  `BusinessRuleViolationException`,取代散落的 `if (...) throw new DomainException("...")`。采 modular-monolith-with-ddd 模式。

### 三、新增语义化应用异常子类

- `EntityNotFoundException`(→404)、`ConcurrencyConflictException`(→409),均属 `-application`、零 web 依赖。
- `EntityNotFoundException` **取代**脚手架先前"抛 `NoSuchElementException` 换 404"的临时手法。

### 四、完整且正确的异常→HTTP 映射

- 采用 [[design-00003-exception-model]] §六 的映射表;**修复 `ConstraintViolationException`(命令总线 JSR-380)
  当前掉进 500 的缺陷 → 400**,并与 `MethodArgumentNotValidException` 共享 `FieldError` 结构。

### 五、消息链路可靠性错误模型

- `transient` / `permanent` 分类 + `max-attempts`(默认 8)+ 指数退避;超上限或 permanent → **死信**
  (`-outbox` 加 `DeadLetterStore` + `dead_letter` 表;Kafka 用 `DefaultErrorHandler` + DLT)。终结"无限重投毒丸"。

### 六、i18n 与 401/403

- `-web-spring` 交付默认英文错误 bundle,filter 路径也接入 `MessageSource`。
- 401/403 → ProblemDetail 仅在 classpath 有 spring-security 时条件化激活(承 [[decision-00007-web-api-response-envelope]] §六)。

## 明确不做 / 取舍

1. **不在 `-core` 引入 `Result`/`Either`/Vavr**。默认 throw + 结构化错误码。理由:①保 `-core` 零依赖红线;
   ②契合 Spring/MyBatis 事务 rollback-on-exception 语义;③与生态近邻 jMolecules / spring-modulith-with-ddd 一致。
   **允许**团队在纯领域策略边界局部自采 Vavr `Either`(不进 `-core` 公共 API)。此为成本/一致性权衡,非"反最佳实践";
   重估须走独立 decision。
2. **HTTP 语义不进 `-core`/`-application`**。状态码只由 `-web`/`-web-spring` 决定。
3. **不做大而全的异常上下文**(任意 key-value map、堆栈携带等)。错误结构收敛为 `code` + message + 可选 `FieldError[]`。

### 被拒选项

- **让 `DomainException` 直接依赖 `-web` 的 `ProblemType`**:最省事,但反向依赖、破坏 framework-free,拒。
- **只在边界(adapter)现拼错误码**:边界无法得知领域语义,码不稳定、易漂移,拒(码必须"从内到外")。
- **全面函数式(`Either` 进 core)**:见"明确不做 1"。

## Consequences

- **正向**:`code`/`type` 对领域异常首次可达,[[design-00002-web-layer]] §八 示例可复现;规则可测可组合;
  未找到/并发/校验有稳定语义;消息毒丸不再无限重投。纯/脏与依赖向内不变。
- **治理成本**:`code`/`typeUri` 一旦发布即**对外契约**,变更走版本;错误码枚举跨 BC 增长需命名前缀治理;
  `-application` 新增两个语义子类,消费者需知其映射。
- **迁移**:全为加法(旧构造保留),存量不强制一次性改;脚手架作为示范应改齐(见 [[design-00003-exception-model]] §十二)。

## Sources

- [[analysis-00010-exception-model]] —— 缺口证据、参考项目与大厂对照、验收清单。
- [[analysis-00008-web-api-response-envelope]] —— 传输层 RFC 9457 线上契约的一手证据(本决策沿用,不改格式)。
- [[analysis-00006-ddd-building-blocks-library]] —— framework-free / 依赖向内铁律。
- `docs/reference`:modular-monolith-with-ddd(`IBusinessRule`/`CheckRule`)、ddd-by-examples-library(Vavr `Either`)、
  clean-architecture(`Ardalis.Result`)、domain-driven-hexagon(Guard vs Validate)。
- IETF:RFC 9457(Problem Details)、RFC 6585(429)。
