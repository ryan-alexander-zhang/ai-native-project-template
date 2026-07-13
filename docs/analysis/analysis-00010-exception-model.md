---
id: analysis-00010-exception-model
type: analysis
role: main
status: active
parent:
---

# aipersimmon-ddd 异常/错误体系:现状盘点、对照与缺口分析

给整个库的**异常与错误处理体系**做一次 gap 分析,为 [[design-00003-exception-model]] 提供依据。
方法沿用 [[analysis-00008-web-api-response-envelope]]:先穷举现状(以源码 file:line 为证),再对照
`docs/reference` 八个参考项目与大厂/IETF 标准,最后按严重度列缺口。

**一句话结论**:异常体系呈**两极分化**——传输层(`-web`/`-web-spring`)的线上错误契约设计优秀、
高于多数大厂平均线;但**领域层/应用层的异常模型非常薄弱**(全库仅 4 个自定义异常,`DomainException`/
`ApplicationException` 只有一个 String message),且两层之间有一道**没接通的缝**,导致
[[design-00002-web-layer]] 自己写的旗舰 wire 示例目前根本产不出来。传输层不欠缺,**内核欠缺**。

> 说明:本仓当前代码是**开发中的脚手架,不是 truth**。本分析记录的 file:line 是"现状证据",
> 不是"应然契约";该改的由 [[design-00003-exception-model]] 统一改。

---

## 一、现状盘点:库到底提供了什么

### 1.1 自定义异常类型——总共只有这些

| 类型 | 模块 | 父类 | 携带信息 | 证据 |
| --- | --- | --- | --- | --- |
| `DomainException` | `-core` | `RuntimeException` | **仅 String message** | `core/exception/DomainException.java:8` |
| `IllegalStateTransitionException` | `-core` | `DomainException` | 仅 message(`from -> to`) | `core/state/IllegalStateTransitionException.java:10` |
| `ApplicationException` | `-application` | `RuntimeException` | **仅 String message** | `application/ApplicationException.java:9` |
| `ApiException` | `-web` | `RuntimeException` | `ProblemType` + `List<FieldError>`(唯一结构化) | `web/error/ApiException.java:12` |
| `ProblemType`(接口)/ `ApiError` / `FieldError` | `-web` | — | RFC 9457 错误契约值对象 | `web/error/*` |

其余全部使用 JDK/Spring 原生异常:CQRS 总线 `IllegalStateException`(`RegistryCommandBus.java:31,48`);
saga 生命周期 `IllegalStateException`(`SagaState.java:89,97`);outbox/inbox/saga-spring 用
`OptimisticLockingFailureException`/`DuplicateKeyException`;JSON 失败一律包成 `IllegalStateException`。

### 1.2 映射层:`@RestControllerAdvice`(`-web-spring`)

| 异常 | HTTP | 关键行为 | 证据 |
| --- | --- | --- | --- |
| `ApiException` | `ProblemType.status()` | 首选路径:type/code/title 全来自目录 | `AipersimmonDddWebExceptionHandler.java:35` |
| `DomainException` | **409** | detail=`getMessage()`,**无 code,type=about:blank** | `:42` |
| `ApplicationException` | **422** | 仅当 `-application` 在 classpath(`@ConditionalOnClass`) | `ApplicationExceptionAdvice.java:20` |
| `MethodArgumentNotValidException`/`BindException` | 400 | 填 `errors[]`(field/code/message) | `:47` |
| `NoSuchElementException` | 404 | detail=`getMessage()` | `:58` |
| 兜底 `Exception` | 500 | **不回显 message**(防泄漏,承 Zalando #177) | `:63` |

filter 层另有三条**绕过 advice**、直写 `application/problem+json` 的错误出口(`ProblemHttpResponseWriter`):
限流 429(`RateLimitFilter.java:48`)、防重放 401(`ReplayProtectionFilter`)、幂等键缺失 400
(`IdempotencyFilter.java:57`)。

### 1.3 已经做对的地方(值得保留)

- **RFC 9457 `application/problem+json`** + 扩展成员 `code`/`traceId`/`errors[]`,对齐 Stripe/PayPal/GitHub/JSON:API。
- **per-BC `ProblemType` 目录**的设计意图(`decision-00007` 问题5)。
- **i18n 挂钩**:`ProblemTitleResolver.java:13` 经 `MessageSource` + `LocaleContextHolder` 解析 `titleKey()`。
- **filter 层安全错误**(429/401/400)带 `Retry-After` / `RateLimit-*`,对齐 RFC 6585 / IETF draft。
- **兜底 500 不回显堆栈**。

---

## 二、对照 docs/reference:库走了与参考项目相反的路

八个参考项目处理"预期领域失败"的主流做法,本库**几乎都没采用**:

| 模式 | 谁在用(docs/reference) | 本库现状 |
| --- | --- | --- |
| **函数式 Result/Either**(预期失败作返回值,不抛异常) | ddd-by-examples-library(Vavr `Either<Rejection,Allowance>`,最彻底)、clean-architecture(`Ardalis.Result`)、domain-driven-hexagon(推荐) | ❌ 纯 throw,无 `Result` 类型 |
| **显式 `BusinessRule` 抽象 + `checkRule()`** | modular-monolith-with-ddd(`IBusinessRule`/`BusinessRuleValidationException`,聚合根 `CheckRule(rule)`) | ❌ 规则散落成 `if (...) throw new DomainException("...")` |
| **可组合 Policy/Specification 对象**(返回 typed rejection) | ddd-by-examples-library(`PlacingOnHoldPolicy` = `Function3<…, Either<Rejection,Allowance>>`,`allCurrentPolicies()` 组合) | ❌ 无 |
| **Guard vs Validate 分层**(不变量→抛;边缘输入→非异常返回) | domain-driven-hexagon("Bad input isn't a bug; a broken invariant is")、clean-architecture(Vogen+GuardClauses) | ⚠️ 部分:边缘有 Bean Validation,领域侧只有裸 throw |
| **Always-Valid 自校验值对象** | domain-driven-hexagon、clean-architecture、spring-modulith-with-ddd | ✅ 已有(VO 构造即校验) |

值得强调:本仓 **[[analysis-00005-structure-2-event-flow-and-cqrs]]:205-206** 已写明——"预期的领域失败
(如 `CreditExceededException`)用 **Result/受控异常**表达……依据:domain-driven-hexagon、
clean-architecture(Result-over-exceptions)"。即**内部分析原本倾向 Result,但库只落了"受控异常"的一半**,
`Result` 那一半从未实现。这是一处**声明意图 vs 实现**的缺口,非仅评审观点。

**参考项目共识里、本库最该补的三样**:①`BusinessRule` 为一等对象;②稳定机器可读错误码/typed rejection;
③Guard-vs-Validate 的清晰分工。三者都指向"把领域失败建模成结构化的东西",而不是一个字符串。

> 反面平衡:纯 throw 也是一种合法且更省心的选择(jMolecules / spring-modulith-with-ddd 根本不谈函数式错误)。
> 所以"没有 Either"本身不是错;错在**异常又如此之薄**——throw 出去的东西不携带任何结构,两头都吃亏。

---

## 三、对照大厂/标准:传输层是强项,但只覆盖了"最后一公里"

[[analysis-00008-web-api-response-envelope]] 已用 15+ 家大厂 + IETF 做过完整对照,此处不重复,只补它**没覆盖的维度**:

- 00008 讨论的是**响应/错误的线上契约**(信封 vs ProblemDetail、字段级 errors、状态码语义)——即"错误**离开进程之后**长什么样"。本库这块**达标甚至领先**。
- 00008 **没有**讨论**错误在进程内如何被建模、如何从领域一路带到边界**——即"错误**在进程内部**如何流动、如何携带机器码"。本库这块**是空的**。

大厂经验落到进程内建模,与本库直接相关的一条:

1. **机器可读错误码是"从内到外"的贯穿契约**,不是边界现拼的。Stripe(`type`/`code`)、Google(`ErrorInfo.reason`+`domain`)、阿里云/华为云(点分 `Code`)——错误码在**业务逻辑抛出的那一刻**就确定,原样透传到响应。本库的 `code` 只能由 `-web` 的 `ApiException` 携带,**领域异常带不出来**(见第五节),等于把"从内到外"截断在了边界。

(另一维度——错误的 transient/permanent 可重试性——属**异步投递可靠性**,不在本异常体系范围,独立追踪见 [[issue-00003-messaging-delivery-reliability]]。)

---

## 四、缺口清单(按严重度排序)

| # | 缺口 | 严重度 | 证据 / 说明 |
| --- | --- | --- | --- |
| 1 | **领域异常带不出 `code`/`type`** | 🔴 高 | `DomainException` 只有 message;`handleDomain` 一律 409、`code` 空、`type=about:blank`(`:42`)。要带 code 只能抛 `ApiException`,但它在 `-web`,领域层**不能依赖 web**。契约里最核心的"机器可读错误码"**对领域异常不可达**。 |
| 2 | **`ProblemType` 目录从未随库/脚手架交付** | 🔴 高 | `ProblemType` 只是接口(`web/error/ProblemType.java:14`),唯一实现都在测试里;`decision-00007` 问题5 定的"per-BC 枚举"没兑现,脚手架也无样例。使用方从零手写。 |
| 3 | **`ConstraintViolationException` → 500(应 400/422)** | 🔴 高(近似 bug) | `ValidationCommandInterceptor.java:31` 在命令总线抛 JSR-380 的 `ConstraintViolationException`,但 advice 只处理 `MethodArgumentNotValidException`/`BindException`,于是**命令级校验失败掉进 500 兜底**(`:63`)。 |
| 4 | **i18n 挂了架子没放东西** | 🟡 中 | `ProblemTitleResolver` 接了 `MessageSource`,但**全库无任何 `messages*.properties`**,title 回退成 raw key;filter 路径(`ProblemHttpResponseWriter`)干脆不走 MessageSource。 |
| 5 | **无 `BusinessRule` 抽象 / 无 `Result`** | 🟡 中 | 规则散落成 `if/throw`,可发现性、可测试性、可组合性均低于 modular-monolith / ddd-by-examples;与 `analysis-00005` 的 Result 意图不符。 |
| 6 | **消息投递无 DLQ/重试上限**(**已移出**) | — | 属**异步投递可靠性**、非异常体系;独立追踪见 [[issue-00003-messaging-delivery-reliability]]。 |
| 7 | **`DomainException` 无子类层级、恒回显 message** | 🟢 低 | 除 `IllegalStateTransitionException` 外无细分;raw message 直进 `detail`,措辞即对外契约却不受版本治理。 |
| 8 | **两处 Bean Validation 不共享 `FieldError`/code 路径** | 🟢 低 | 命令总线 JSR-380(cqrs-spring)与 web 400 映射(web-spring)各走各的,错误结构不统一。 |
| 9 | **401/403 错误格式不统一(已知推迟项)** | 🟢 低 | 见 `decision-00007` §六:Spring Security 过滤器链早于 advice,401/403 仍是 Security 默认体。设计需给出条件化补齐路径。 |

---

## 五、最关键的一条:设计与实现之间的断裂

[[design-00002-web-layer]] §八(lines 258-271)亲自给出的旗舰 wire 示例:

```
HTTP/1.1 422 Unprocessable Content
Content-Type: application/problem+json
{ "type": "/problems/credit-exceeded",
  "code": "ordering.credit-exceeded",
  "status": 422, "detail": "...", "traceId": "..." }
```

> 注:业务规则默认状态由早先的 409 修正为 **422**(见 [[decision-00010-exception-model]] §四;design-00002 §八 已同步)。

但在当前脚手架里**这个响应产不出来**:`CreditExceededException extends DomainException` → 命中
`handleDomain` → 409(状态也错,应为 422)、`type=about:blank`、**`code` 缺失**。要得到文档那个带 `code`/`type` 的响应,
必须改抛 `ApiException(OrderingProblemType.CREDIT_EXCEEDED, …)`;可 `ApiException`/`ProblemType` 都在
`-web`,**领域层不能依赖 web**(违反 [[analysis-00006-ddd-building-blocks-library]] 的 framework-free/依赖向内铁律)。

**结论**:库设计了一套漂亮的机器可读错误码契约,却**没给领域异常任何"不依赖 `-web` 就能挂上 code"的通道**。
这正是"欠缺感"的结构性根源——设计停在了传输层,没有向内贯通到领域异常。**这必须靠在 `-core` 引入一个
framework-free 的错误码抽象来解**(详见 [[design-00003-exception-model]] §二)。

---

## 六、一个完整异常体系必须交付的东西(交棒 design-00003)

1. **贯穿式错误码**:`-core` 里 framework-free 的 `ErrorCode` 抽象;`DomainException`/`ApplicationException`
   可携带;`-web` 的 `ProblemType` 与之对齐;advice 从抛出的异常读出 `code` 填进 ProblemDetail。→ 解 #1、#2、#7。
2. **`BusinessRule` 一等抽象 + `AbstractAggregateRoot.checkRule()`**,取代散落的 `if/throw`。→ 解 #5。
3. **完整且正确的异常→HTTP 映射**,含 `ConstraintViolationException`;统一两处 Bean Validation 的 `FieldError`。→ 解 #3、#8。
4. **随库交付 i18n bundle** + 让 filter 路径也走 MessageSource;脚手架给真实 `ProblemType` 枚举样例。→ 解 #2、#4。
5. **Guard-vs-Validate 分工**成文;对 `Result`/`Either` 给出明确取舍(采纳/推迟/不做)。
6. **401/403 条件化补齐**路径(仅当 classpath 有 spring-security)。→ 解 #9。

(缺口 #6 消息投递可靠性不在本清单——见 [[issue-00003-messaging-delivery-reliability]]。)

---

## 关联

- [[analysis-00008-web-api-response-envelope]] —— 传输层错误契约的大厂/标准对照(本文不重复其证据)。
- [[analysis-00006-ddd-building-blocks-library]] —— 纯/脏分离与依赖向内铁律,约束错误码抽象只能落在 `-core`。
- [[analysis-00005-structure-2-event-flow-and-cqrs]] —— 命令侧"Result/受控异常"意图的原始出处。
- [[decision-00007-web-api-response-envelope]] —— per-BC `ProblemType` 枚举、扩展成员 `code`/`traceId`/`errors` 的决策来源。
- [[design-00001-aipersimmon-ddd-and-scaffold]] / [[design-00002-web-layer]] —— 现有模块与 Web 层设计。
- [[decision-00010-exception-model]] —— 本分析驱动的决策定案。
- [[design-00003-exception-model]] —— 该决策落地的完整异常体系设计。
