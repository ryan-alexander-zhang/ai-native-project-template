---
id: decision-00007-web-api-response-envelope
type: decision
role: main
status: active
parent:
---

# Web 层封装:无通用信封 + RFC 9457,横切能力全做但都 opt-in + 可插拔

固化 `aipersimmon-ddd` 的入站 HTTP(interface)层封装策略。选型依据(15+ 大厂对比 + 多份
IETF/跨语言标准,逐条附一手证据)见 [[analysis-00008-web-api-response-envelope]];本文只记**决策与
取舍**,拍板该分析 §八 遗留的六个问题(含**防重放攻击**),并把幂等、防重放、限流等横切能力
**纳入**——但每项都是**开关控制 + 可插拔实现**(内存 / JDBC / Redis)。落位严守
[[analysis-00006-ddd-building-blocks-library]] 的"纯/脏分离",与 `-cqrs`/`-cqrs-spring`、
outbox 的"契约 + 可换存储"同构。

> **修订说明(v2):** 初版曾把"签名请求防重放"判为"不进构件核心"——经复议**推翻**:Web 层横切标配
> 该做的都做,约束是"**默认关、可开关、实现可插拔**"而非"要不要做"。
> **修订说明(v3):** 经复议再收敛范围——**CORS 去掉**(用 Spring 原生 CORS / 网关即可,构件不重复封装);
> **401/403 → Problem Details 移出 v1**,列为**未来项**(见 §六)。二者均非"反最佳实践",只是不进本期构件范围。

## Context

构件库当前**整层缺失** interface(Web)构件:清单里有 `-core`/`-application`/`-cqrs`/
`-cqrs-spring`/`-integration`/`-events-spring`/outbox·inbox·messaging/`-archunit`/`-bom`,
**唯独没有负责入站 HTTP 适配的模块**。用户诉求是补 `ApiResponse`/`ApiRequest` 之类封装。

核心张力([[analysis-00008-web-api-response-envelope]] §三~§四给了证据):

1. **成功响应要不要通用信封**?15 家大厂里 12 家**不套** `{code,message,data}`,直接返资源、
   用 HTTP 状态码表达结果(PayPal 明文禁止 2xx 返错误体);只有 Slack(`{ok}`)与中国部分厂商
   (腾讯云/飞书)套通用信封且**恒返 HTTP 200**——后者掏空 HTTP 语义(缓存/网关/重试/监控失灵)。
2. **错误格式**正向 **RFC 9457**(取代 7807)收敛:它是唯一有 IETF 背书、且被 Spring 6/Boot 3
   原生 `ProblemDetail` 实现的格式;Zalando 强制要求它。各家私有错误体的共性就是它的超集。
3. **HTTP 是 interface 层关注点**,绝不能污染 framework-free 的 domain——落位必须纯/脏分离。

## Decision

**采用"流派 A":成功直接返资源 + 正确 HTTP 状态码,不套通用成功信封;错误统一走 RFC 9457
Problem Details。把 Web 层横切(错误映射、traceId、分页、幂等、防重放、限流)封装为一套构件:
framework-free 契约 `aipersimmon-ddd-web`(纯)+ Spring starter `aipersimmon-ddd-web-spring`(脏)
+ 可插拔存储后端 `-web-store-redis`/`-web-store-jdbc`。每项横切能力**独立开关、默认关**
(除 Problem Details/traceId 这类零风险项),需要状态的能力(幂等/nonce/限流计数)走**统一 SPI + 可换存储**。
**CORS 用 Spring 原生 / 网关,不进构件;401/403→Problem Details 移出 v1(未来项,见 §六)。**
明确不做的只有 `ApiResponse`/`ApiRequest` 通用外壳。**

### 一、响应与错误的线上形态

| 场景 | 形态 | 说明 |
| --- | --- | --- |
| 成功·单资源 | **资源本身作 body** + `200`/`201`/`204` | 无 `{code,message,data}` 外壳 |
| 成功·集合 | 轻量**分页壳** `{items, nextCursor, ...}` | 这是"分页壳"不是"成功信封"(§二④),单资源仍直返 |
| 失败 | **RFC 9457 `application/problem+json`** | `{type,title,status,detail,instance}` + 扩展成员(见下) |

**错误体扩展成员**(用 9457 的扩展机制承载,不新造私有错误体):

- `code`:机器可读领域错误码(如 `ordering.credit-exceeded`)。
- `traceId`:贯穿追踪锚(≈ 云厂商 RequestId / 9457 `instance`)。
- `errors`:字段级校验明细数组,元素 `{field, code, message}`(对齐 PayPal/GitHub/JSON:API 的 `details`)。
- `type`:相对 URI(如 `/problems/credit-exceeded`),对齐 Zalando 惯例,**不要求可解析**。

### 二、模块拓扑:契约 + starter + 可插拔存储(mirror outbox)

| 模块 | 层 / 性质 | 依赖 | 内容 |
| --- | --- | --- | --- |
| `aipersimmon-ddd-web` | interface 契约,**framework-free** | `-core`(极薄) | `ProblemType` 错误码目录抽象;`ApiError`/字段错误语义模型(对齐 9457,不绑 Spring);`Page<T>`/`Slice<T>`/`Cursor` 分页值对象;**横切 SPI**:`IdempotencyStore`、`ReplayGuard`(nonce)、`RateLimiter`、`RequestSignatureVerifier` |
| `aipersimmon-ddd-web-spring` | interface 实现,脏 starter | `-web` + Spring Web | 共享 `@RestControllerAdvice`(异常 → `ProblemDetail`,含 400/404/409/422/429);`traceId` filter;分页序列化;i18n(`MessageSource`);各横切能力的 filter + **开关** + 默认**内存** SPI 实现 |
| `aipersimmon-ddd-web-store-redis` | infrastructure,可选后端 | `-web` + Redis | Redis 实现 `IdempotencyStore`/`ReplayGuard`/`RateLimiter`(TTL、原子 `INCR`、令牌桶 Lua) |
| `aipersimmon-ddd-web-store-jdbc` | infrastructure,可选后端 | `-web` + JDBC | JDBC 实现同一批 SPI(表 + 过期清理);与 Redis 同 SPI 可互换 |

- **统一 SPI + 可换存储**:幂等 key、防重放 nonce、限流计数本质都是"带 TTL 的短时键值状态",
  故共用一套 store 后端——**与 outbox `-outbox`+`-outbox-jdbc`/`-mybatis-plus` 完全同构**。
- **装配确定性**(照搬 outbox 模式):store 后端在 classpath → 用它;否则内存兜底(`@ConditionalOnMissingBean`)。
  内存实现仅供单机/开发;多实例生产必须显式引入一个 `-web-store-*`。
- 拓扑无关:三种拓扑复用同一批 web 构件。可选:最小形态可不引,各 BC 自写。
- 异常映射的上游锚点已就位:`-core` 的 `DomainException`、`-application` 的 `ApplicationException`。

### 三、全套横切能力:全做,但每项 opt-in + 可插拔

**原则:该做的都做;区别只在"默认开/关"与"实现可换"。** 零风险的常开;有成本/有副作用的默认关、按需开;
需要状态的走 §二 的 SPI + 可换存储。

| 能力 | 默认 | 开关(`aipersimmon.ddd.web.*`) | 需存储 | 线上形态 / 说明 |
| --- | --- | --- | --- | --- |
| **Problem Details 错误映射** | **开** | `problem-details.enabled` | 否 | 异常 → RFC 9457;这是核心价值,常开 |
| **traceId 追踪** | **开** | `trace.enabled` | 否 | 生成/透传 + MDC + 响应头 + 写 9457 扩展成员 |
| **分页** | 开(用到才生效) | — | 否 | `Page`/`Cursor` 值对象 + 序列化 |
| **i18n 错误消息** | 开(缺省英文) | `i18n.*` | 否 | `MessageSource` 按错误码取文案 |
| **幂等键** | **关** | `idempotency.enabled` + `.header=Idempotency-Key` | **是** | 存首次响应并回放;`store=in-memory\|jdbc\|redis` |
| **防重放** | **关** | `replay.enabled` + `.tolerance=5m` + `.nonce.enabled=false` | nonce 档需 | 校验签名 + 时间戳容差;可选 nonce 单次去重(需 store) |
| **限流 429** | **关** | `rate-limit.enabled` + 策略配置 | **是** | 出 `429` + `Retry-After` + `RateLimit`/`RateLimit-Policy` 头 + ProblemDetail 体 |

**限流细节**:`429 Too Many Requests`(RFC 6585)+ `Retry-After`(RFC 9110,秒数或 HTTP-date)。
响应头优先采纳 IETF 草案的 **`RateLimit` / `RateLimit-Policy`**(结构化字段),兼容输出传统
`X-RateLimit-*`;body 仍是 ProblemDetail(`type=/problems/rate-limited`)。限流策略(窗口/配额/键提取)
可配置,计数走 `RateLimiter` SPI(Redis 令牌桶 / JDBC)。

**不在本期范围**:CORS(用 Spring 原生 `CorsConfigurationSource` / 网关,构件不重复封装)、
401/403→ProblemDetail(移出 v1,见 §六未来项)。

### 四、拍板 [[analysis-00008-web-api-response-envelope]] §八 的六个遗留问题

| # | 遗留问题 | 决策 | 依据 |
| --- | --- | --- | --- |
| 1 | 字段命名默认 | **camelCase**,全局可经 `@JsonNaming` 一处切换 | 顺 Spring/Jackson 与 Java 字段名,零配置;Google(JSON)/Microsoft/Adyen 亦用。命名阵营本就分裂,重点是"定一个默认并全局一致" |
| 2 | 版本策略 | **URL 路径 `/v{major}/`**(仅大版本,如 `/v1/`) | 最直观,对齐 Google/PayPal/Adyen/Shopify;Zalando 反 URL 版本属少数派 |
| 3 | 幂等键是否进 v1 | **v1 提供支持点但默认关闭**(opt-in);不设为强制 | YAGNI;与 inbox 幂等边界见下方 Consequences。Stripe/PayPal/Adyen 均有,故保留 hook |
| 4 | 模块命名 | **`-web` / `-web-spring`** | 与 `-cqrs`/`-cqrs-spring`、`-events-spring` 命名对称;避开 `-integration`(已指集成事件契约,见 [[analysis-00002-domain-vs-integration-events]])与 `interface`(与 Java 关键字/分层名歧义)。`-rest`/`-rest-spring` 为落选备选 |
| 5 | 错误码目录组织 | **按 BC 归属的枚举,实现 `-web` 的 `ProblemType` 接口**;每项含稳定 `type`(相对 URI)+ 默认 HTTP 状态 + 消息 key(i18n) | 枚举天然是稳定、可穷举的机器码集合;i18n 走 starter 的 `MessageSource`,`type` 相对 URI 对齐 Zalando |
| 6 | 防重放攻击落位与范围 | **全做,opt-in + 可插拔(见 §三/§五)**:签名请求与 webhook 两种防重放都提供为 `-web-spring` 可选能力,时间窗默认 **5 分钟**,可选 nonce 单次去重走可换 store。默认关 | 是 Web 层安全标配;架构约束是"默认关 + 可插拔",而非"不做"([[analysis-00008-web-api-response-envelope]] §六B) |

### 五、安全:防重放(replay)≠ 幂等(idempotency)——分三层,都做但不混淆

三件事**关注点/层次不同,不可互相替代**,故各自独立提供、独立开关:

| 关注点 | 防什么 | 机制 | 归属层 | 在本库落位 |
| --- | --- | --- | --- | --- |
| **防重放(安全)** | 攻击者重发**已签名的合法请求** | 签名 + 时间戳(+ nonce)+ 时间窗拒绝 | 鉴权 / 网关层,或 webhook 接收端 | `-web-spring` **可选**能力,默认关;nonce 去重走可换 store |
| **幂等(可靠性)** | 合法请求因重试**重复生效** | `Idempotency-Key` 存首次结果并回放 | 应用 / 数据层 | `-web-spring` **可选**,默认关;store 可换(问题 3) |
| **事件去重(可靠性)** | at-least-once 投递的**事件重复消费** | inbox 消费端去重 | 消费端基础设施 | 已有 [[analysis-00006-ddd-building-blocks-library]] 的 inbox |

**防重放决策细节:**

- **两种入口都提供,均默认关**:①**签名请求防重放**(`RequestSignatureVerifier` + 时间戳容差),
  ②**webhook 接收防重放**(校验签名 + 时间戳,如 Slack/Stripe)。虽然网关/鉴权层常已做①,但不假设人人有网关,
  故构件提供、由使用者按需开;开了也能与网关叠加(纵深防御)。
- **时间窗默认 5 分钟(300s)**,对齐 Slack/Stripe/腾讯云/微信支付;基础设施签名场景可调 15 分钟(AWS/阿里云)。
- **nonce 单次去重是"更强档",可开**:AWS/腾讯/Slack/Stripe 仅靠时间窗(窗内可重放);OAuth 1.0/RFC 9421/阿里云
  要求单次 nonce 去重。本库经 `ReplayGuard` SPI + 可换 store(Redis TTL / JDBC)提供,`nonce.enabled` 默认关。

### 六、未来项(不在 v1,但非"反最佳实践")

这两项**该做**、只是本期不做,与"明确不做"的三项性质不同——将来可作为独立 opt-in 增量补齐:

- **CORS**:直接用 Spring 原生 `CorsConfigurationSource` / `@CrossOrigin` / Spring Security `.cors()`,或在
  网关/ingress 层配置。构件**不重复封装**——CORS 本质是声明式配置,且浏览器强制、服务端只声明策略,
  自造属性只是给 Spring 已有能力套壳,低价值。
- **401/403 → Problem Details**:移出 v1。原因是它需要引入 Spring Security 依赖,且有一处非平凡的坑——
  `spring.mvc.problemdetails.enabled` **只覆盖经 DispatcherServlet 的 MVC 异常**,而 Security 的 401/403 在
  **过滤器链**里先发生,**不会**自动变 problem+json,需自定义 `AuthenticationEntryPoint`/`AccessDeniedHandler`。
  代价是"错误格式在 auth 边界不统一"(承接 [[analysis-00008-web-api-response-envelope]] §六C);将来若做,应作为
  **仅当 classpath 有 spring-security 时激活**的条件化增量(`@ConditionalOnClass`),对不引 Security 的用户零成本。

## 明确不做(仅此三项)

- **通用成功信封 `{code,message,data}`(`ApiResponse`)**:15 家里 12 家不套,PayPal 明文禁止。
- **恒返 HTTP 200 + 业务 code**:掏空 HTTP 语义,与本模板"面向标准、可审计"定位冲突。
- **通用请求外壳(`ApiRequest`)**:无一家把请求套通用外壳;请求 DTO 随用例走(per-use-case record)。

> 除以上三项(它们是"反最佳实践",非"成本问题"),其余 Web 层横切能力**一律提供**,只是默认关 + 可插拔。
> 若某组织确有 BFF/前端聚合诉求需要中式信封,应做成**独立、opt-in 的适配器**,不得作为纯契约层默认。

## Consequences

**正向**

- 保住 HTTP 状态码语义:缓存/条件请求、网关限流熔断、客户端 `raise_for_status()`、按状态码告警全部按标准工作。
- 错误对齐唯一有 IETF 背书 + Spring 原生支持的格式,零成本、可互操作,不新增第 16 种私有错误体。
- 落位与既有 starter 对称(`-web` 纯 + `-web-spring` 脏 + `-web-store-*` 可换),不破坏
  [[analysis-00006-ddd-building-blocks-library]] 的 domain framework-free 硬约束。
- 分页值对象在纯层、序列化在 starter,cursor 为主、offset 兼容,契合大厂新接口趋势。
- **本期横切能力零强加**:幂等/防重放/限流都在,默认关、逐项开关;需状态者共用一套
  SPI + 可换存储(内存/JDBC/Redis),既不绑死 Redis 也不逼单机用户装 Redis。CORS/401-403 收敛为未来项(§六),
  范围更聚焦。

**负向 / 注意**

- 客户端需同时看 HTTP 状态码与 body(而非只读一个 `code` 字段);习惯中式信封的前端有迁移成本。
- camelCase 与偏好 snake_case 的多数大厂不一致——已提供全局 `@JsonNaming` 切换,但**同一服务内必须统一**,勿混用。
- **幂等键、防重放、inbox 三者边界需在实现前划清(见 §五表)**:`Idempotency-Key` 是**入站 HTTP 去重**
  (合法请求重试返首次结果)、**防重放**是**安全**(拒收攻击者重发的已签名请求)、
  inbox 是**消费端事件去重**——三者关注点/层次不同,不可互相替代,实现时勿混做或重复造。
- **默认时间窗内可重放**:防重放不开 nonce 时仅靠 5 分钟时间窗,窗内仍可重放;高安全场景须开 `nonce.enabled`
  并接一个 `-web-store-*`,承担其存储/清理成本。
- **内存实现不可用于多实例生产**:幂等/nonce/限流的内存 SPI 只在单进程有效;多实例必须显式引入
  `-web-store-redis` 或 `-web-store-jdbc`,否则去重/限流在实例间失效(与 outbox 存储选择同理)。
- **限流通常网关也做**:构件内限流是"应用兜底/细粒度",与网关限流可叠加;别把二者配额混算。
- **错误格式在 auth 边界暂不统一**:401/403 移出 v1(见 §六),故这两个状态码仍是 Spring Security 默认体、
  非 problem+json;调用方需知悉此不一致,将来补条件化增量消除。
- `type` 用相对 URI 且不要求可解析——需在文档约定这是**标识符**而非可访问链接(承接 Zalando 的取舍)。
- 错误码枚举跨 BC 增长需治理(命名前缀按 BC),否则易冲突;`type`/`code` 一旦发布即成对外契约,变更要走版本。
- **构件增多**:新增 `-web-store-redis`/`-web-store-jdbc` 两个后端,需纳入 `-bom` 统一版本管理。

## Sources

内部:

- [[analysis-00008-web-api-response-envelope]] —— 15+ 大厂 + RFC 7807/9457 + JSON:API 对比、
  六维度、差距分析、纯/脏落位建议(本决策的证据底座)。
- [[analysis-00006-ddd-building-blocks-library]] —— 构件库按 Layer×可插拔性切分、纯/脏分离硬约束、模块清单。
- [[analysis-00002-domain-vs-integration-events]] —— `-integration` 命名的既有归属(避免混淆)。
- [[design-00001-aipersimmon-ddd-and-scaffold]] —— 构件库与脚手架总设计(待补 `-web`/`-web-spring` 章节)。

外部(一手):

- RFC 9457 *Problem Details for HTTP APIs*(取代 RFC 7807)。https://www.rfc-editor.org/rfc/rfc9457
- Zalando RESTful API Guidelines —— #176 problem JSON、#160 cursor、#118 snake_case、#114/#115 版本。
  https://opensource.zalando.com/restful-api-guidelines/
- Google AIP-193 Errors / AIP-131 资源直返。https://google.aip.dev/193 、 https://google.aip.dev/131
- Stripe Idempotent requests(`Idempotency-Key`)。https://docs.stripe.com/api/idempotent_requests
- PayPal API Style Guide(2xx 禁返错误体)。https://github.com/KuSh/api-standards/blob/master/api-style-guide.md

防重放(§四):

- Slack 验请求(时间戳 5 分钟防重放)。https://docs.slack.dev/authentication/verifying-requests-from-slack/
- Stripe webhook 签名(`t=` 时间戳,5 分钟容差)。https://docs.stripe.com/webhooks/signature
- AWS SigV4(`x-amz-date`,15 分钟 `RequestTimeTooSkewed`)。https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-authenticating-requests.html
- 阿里云 V3 签名(`x-acs-signature-nonce`+时间戳,15 分钟)。https://www.alibabacloud.com/help/en/sdk/product-overview/v3-request-structure-and-signature
- 腾讯云 TC3(`X-TC-Timestamp`,5 分钟)。https://www.tencentcloud.com/document/product/845/32207
- OAuth 1.0 RFC 5849 §3.3(nonce+timestamp)。https://datatracker.ietf.org/doc/html/rfc5849#section-3.3
- RFC 9421 §7.2.2 Signature Replay(`created`/`expires`/`nonce`)。https://datatracker.ietf.org/doc/html/rfc9421#section-7.2.2

限流(§三):

- `Retry-After`(RFC 9110 §10.2.3,HTTP-date 或 delay-seconds)。https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3
- `429 Too Many Requests`(RFC 6585 §4)。https://datatracker.ietf.org/doc/html/rfc6585
- IETF RateLimit 头草案(`RateLimit` / `RateLimit-Policy`)。https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/
- GitHub 限流(`x-ratelimit-*`,403/429)。https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api
- Slack 限流(429 + `Retry-After`)。https://docs.slack.dev/apis/web-api/rate-limits/

未来项参考(§六,CORS / 401-403):

- 401/403 语义(RFC 9110 §15.5.2 / §15.5.4)。https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.2
- Spring 6 ProblemDetail / `spring.mvc.problemdetails.enabled`(覆盖不到 Security 过滤器链)。https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html
- Spring Security RFC 9457 支持(issue #15549,401/403 需自定义处理器)。https://github.com/spring-projects/spring-security/issues/15549
- CORS:Spring 原生即可;规范 WHATWG Fetch。https://fetch.spec.whatwg.org/#http-cors-protocol
