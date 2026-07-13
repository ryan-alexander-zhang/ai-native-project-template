---
id: decision-00010-exception-model
type: decision
role: main
status: active
parent:
---

# 异常/错误体系:领域贯穿式错误码 + Invariant 一等抽象 + 默认 throw(不上 Result)

固化 `aipersimmon-ddd` 的**跨层异常与错误处理策略**。缺口证据与大厂/参考对照见
[[analysis-00010-exception-model]](及其引用的 [[analysis-00008-web-api-response-envelope]]);
落地模块设计见 [[design-00003-exception-model]]。本文只记**决策与理由、被拒选项**。

## Context

现状是两极分化(详见 [[analysis-00010-exception-model]]):传输层(`-web`/`-web-spring`)的 RFC 9457 线上
契约优秀,但**领域/应用层异常模型极薄**(全库仅 4 个自定义异常,`DomainException`/`ApplicationException`
只有一个 String message),两层之间还有一道断裂——领域异常**带不出**机器可读 `code`/`type`,导致
[[design-00002-web-layer]] §八 的旗舰示例产不出来。

约束不可动:[[analysis-00006-ddd-building-blocks-library]] 的**依赖一律指向内/下**、**纯净层
framework-free**(`-core` 零依赖是验收红线)。

## Decision

### 一、引入贯穿式错误码 `ErrorCode`(落 `-core`,framework-free)

- `-core` 定义 `ErrorCode`(稳定、BC 前缀、点分的 `code()` + 可选语义 `ErrorCategory`);**HTTP 状态码绝不进 `-core`**。
- `DomainException` / `ApplicationException` **可携带**(可空)`ErrorCode`;旧 message-only 构造保留(加法式)。
- `-web` 用**组合而非继承**接通:领域抛出的纯 `ErrorCode` 由 `ProblemRegistry.resolve(ErrorCode)` 在边界解析成
  `ProblemDescriptor`(type/status/title)——per-code `ProblemCatalog` override 优先,否则其 `ErrorCategory` 的
  `DefaultProblemFamilies` family 默认。`-web` 依赖 `-core`,`-core` 永不认识 `-web`;`ErrorCategory` 留在 `-core` 纯语义,
  family 映射(含 HTTP status/URI)放 `-web`。**放弃早先的 `ProblemType extends ErrorCode`**(把传输焊进身份、逼码与 type
  1:1、契约随领域码膨胀,且六个参考项目无一如此),理由详见 [[design-00003-exception-model]] §4.7。

### 二、`Invariant` 一等抽象 + `AbstractAggregateRoot.checkInvariant()`

- 库**提供** `Invariant`(`isBroken()`/`message()`/**必填** `errorCode()`)+ 聚合根 `checkInvariant(rule)` → `InvariantViolationException`(采 modular-monolith-with-ddd 的 `IBusinessRule`/`CheckRule` 模式,但改名 `Invariant` 以避免与边缘输入校验 `Validator` 混淆——见 [[design-00003-exception-model]] §4.6)。
- **但它是升级项、不是默认**:聚合内不变量默认用 coded `throw`,仅当**非平凡 / 可复用 / 需清点或组合**时才升级为 `Invariant`(判据见 [[design-00003-exception-model]] §4.5)。两者走同一错误码通道,升降级不影响线上契约。避免"一行条件套个类"的仪式化。

### 三、新增语义化应用异常子类

- `EntityNotFoundException`(→404)、`ConcurrencyConflictException`(→409),均属 `-application`、零 web 依赖。
- `EntityNotFoundException` **取代**脚手架先前"抛 `NoSuchElementException` 换 404"的临时手法。

### 四、异常→HTTP 状态映射:业务规则默认 422,409 收窄给冲突

采用 [[design-00003-exception-model]] §六 映射表。核心取舍(纠正早先"`DomainException` 默认 409"的可疑默认):

- **领域业务规则违反默认 422**(Unprocessable Content):报文合法但语义不可处理(RFC 9110 §15.5.21)。对齐 GitHub/Rails/Spring 社区。
- **409 收窄**给"与当前状态冲突":乐观锁/并发(gRPC `ABORTED`)、重复(`ALREADY_EXISTS`)、状态机非法迁移(`IllegalStateTransitionException`)。**不**作一般业务规则默认——与 409 惯例(幂等/乐观锁)一致。
- **400** 仅报文畸形 + 字段级校验;**修复 `ConstraintViolationException`(命令总线 JSR-380)当前掉进 500 的缺陷 → 400**,并与 `MethodArgumentNotValidException` 共享 `FieldError`。
- **权威由 `registry.resolve(code)` 的 descriptor 决定**(per-code override,否则 category family),无码才回落异常类型基类默认。取 422 派而非 Google/Stripe 的 400 派,因本模板已选 RFC 9457 + `errors[]`,422 语义更精确。带码错误的 `type` 恒为有意义的 family/override(绝非 `about:blank`);`about:blank` 只留给无码/内部错误。

### 五、i18n 与 401/403

- `-web-spring` 交付默认英文错误 bundle,filter 路径也接入 `MessageSource`。
- 401/403 → ProblemDetail 仅在 classpath 有 spring-security 时条件化激活(承 [[decision-00007-web-api-response-envelope]] §六)。

## 明确不做 / 取舍

1. **默认 throw + 结构化错误码,不上 `Result`/`Either` 作默认口径**。理由(诚实排序,见 [[design-00003-exception-model]] §十):
   ①**人机工程**——`throw` 无法被调用方"忘记",`Result` 作返回值可被静默丢弃,一处漏检即把业务失败吞成成功;
   ②与生态近邻 jMolecules / spring-modulith-with-ddd 一致;③事务语义**有限**契合(预期拒绝多在状态变更前,Result 也不会漏回滚,故此条只支持"更省心"而非"Result 不安全")。
   注意:`Result` **不等于 Vavr**——零依赖红线只否决 Vavr 依赖、不否决 Result 模式;`-core` 不引 Vavr 是本结论的**副产品**,不是前提。
   **允许**团队在纯领域策略边界局部自采 `Either`/零依赖 `Result`(不进 `-core` 公共 API);分布式消息边界(如接入 Axon)应在边界转结构化失败。此为成本/一致性权衡,非"反最佳实践";重估须走独立 decision。
2. **HTTP 语义不进 `-core`/`-application`**。状态码只由 `-web`/`-web-spring` 决定。
3. **不做大而全的异常上下文**(任意 key-value map、堆栈携带等)。错误结构收敛为 `code` + message + 可选 `FieldError[]`。

### 被拒选项

- **让 `DomainException` 直接依赖 `-web` 的 `ProblemDescriptor`/HTTP**:最省事,但反向依赖、破坏 framework-free,拒。
- **`ProblemType extends ErrorCode`(身份即传输)**:见 [[design-00003-exception-model]] §4.7,已改为组合。
- **只在边界(adapter)现拼错误码**:边界无法得知领域语义,码不稳定、易漂移,拒(码必须"从内到外")。
- **全面函数式(`Either` 进 core)**:见"明确不做 1"。

## Consequences

- **正向**:`code`/`type` 对领域异常首次可达,[[design-00002-web-layer]] §八 示例可复现;规则可测可组合;
  未找到/并发/校验有稳定语义。纯/脏与依赖向内不变。
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
