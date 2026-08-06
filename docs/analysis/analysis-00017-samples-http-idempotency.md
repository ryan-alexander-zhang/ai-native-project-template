---
id: analysis-00017-samples-http-idempotency
type: analysis
status: draft
informs: [analysis-00014-ddd-samples-scenario-catalog]
---

# S2 HTTP 写接口的幂等提交与重放防护

对应 sample：`aipersimmon-ddd-samples/s02-http-idempotency`。场景清单见
[[analysis-00014-ddd-samples-scenario-catalog]]，模板与工程约定见
[[analysis-00015-samples-http-command-query]]。

## 0. 本篇定位

同一个写操作被提交了不止一次——前端重试、网关超时重发、用户双击、三方重复回调。本篇讲清库在
HTTP 边界提供的三道防护（幂等提交、重放防护、限流）各自解决什么、**各自解决不了什么**，以及它们
与领域层的业务唯一约束如何分工。

sample 在 s01 的写路径上加了两个东西：一个 `POST /orders` 的幂等键，一个需要验签的三方回调
`POST /webhooks/payment`。聚合刻意很薄——建模是 S16。

**一句话结论**：这三道防护是**边界**机制，靠一个带 TTL 的共享存储生效；它们不能替代领域层的
唯一约束，而领域层的唯一约束也不能替代它们。sample 用一个测试把这条分工钉住了。

## 1. 四个 SPI 与一条必须自己写的实现

| SPI | 谁实现 | 说明 |
| --- | --- | --- |
| `IdempotencyStore` | 库（in-memory / jdbc / redis） | 声明式抢占 + 落结果 |
| `ReplayGuard` | 库（同上三种） | nonce 是否用过 |
| `RateLimiter` | 库（同上三种） | 固定窗口计数 |
| `IdempotencyPrincipalResolver` | 库有默认 | 有 Spring Security 时取当前用户，否则一律空串 |
| **`RequestSignatureVerifier`** | **只能自己写，库里没有任何实现** | 见 §3 |

### 1.1 头号陷阱：没有 verifier bean，防重放静默失效

`ReplayProtectionFilter` 的注册条件是 `@ConditionalOnBean(RequestSignatureVerifier.class)`。于是
把 `aipersimmon.ddd.web.replay.enabled=true` 配上、却没有提供这个 bean 时：

- 应用**正常启动**；
- **不打任何 WARN**；
- 过滤器根本没注册，每个请求都不验签直接过。

配置文件看起来"防护已开启"，实际什么都没做。这是本篇最需要读者记住的一条。sample 里做了负向
对照来证明：把 `HmacRequestSignatureVerifier` 上的 `@Component` 去掉后重跑
`WebhookReplayProtectionTest`，**5 个断言全部从 401 变成 200**——未签名、被篡改、时间戳过期、
重放的请求全部通过。

## 2. 幂等提交

### 2.1 键的身份与作用域

`IdempotencyKey` 是 `(tenant, principal, key)` 三元组构成身份，外加一个不参与身份、但查询时会
比对的 `fingerprint`。

- **tenant** 来自 `TenantContext.effective()`（关掉多租户时是 `__root__` 哨兵）；
- **principal** 来自 `IdempotencyPrincipalResolver`。装了 Spring Security 时是当前认证主体，
  **没装时是空串**——意味着所有未认证调用方共享同一个命名空间，只按租户隔离。公开端点要留意这
  一点；
- **key** 是客户端送来的 `Idempotency-Key` 头，上限 255 字符（`IdempotencyKey.MAX_KEY_LENGTH`），
  超了直接 400。

### 2.2 四种结果

`store.claim(key, claimLease)` 返回一个 sealed 接口的四种情况，过滤器分别处理：

| 结果 | 含义 | 响应 |
| --- | --- | --- |
| `Won` | 首次，抢到了 | 放行到业务代码 |
| `Replay` | 已有结果 | 原样重放存下来的状态码、头、body |
| `InProgress` | 别人正在处理 | **409** `/problems/idempotency-in-progress` + `Retry-After: 1` |
| `Mismatch` | 同一个键、不同的请求 | **422** `/problems/idempotency-key-reused` |

注意"抢占"始终发生，**没有只读路径**：claim 自带 lease（默认 1 分钟），所以进程猝死后键会在
lease 到期时释放，而不是等结果保留期（默认 24 小时）。

### 2.3 指纹**不包含 body**——本篇最重要的一条限制

指纹是这五样东西的 SHA-256：

```
method + "\n" + requestURI + "\n" + queryString + "\n" + contentType + "\n" + contentLength
```

**body 不在里面。** 后果是具体的：两个 body 不同、但方法/路径/查询串/Content-Type/**长度**都相同的
请求，指纹一致，于是第二个请求被判为 `Replay`，拿到第一个请求的响应，**业务代码根本没跑**。

sample 里有一个名字就叫 `aDifferentBodyOfTheSameShapeIsNOTDetected` 的测试，断言第二个请求
（不同的 clientReference、不同的金额，恰好等长）拿回了第一个的响应体。这不是 bug 演示，是使用
约束：

- 幂等键必须**按操作**分配，不能一个客户端会话复用一个键；
- 不要指望 `Mismatch` 能挡住"载荷被改了"——它只挡长度/路径/方法/类型层面的差异（sample 里
  `reusingAKeyForAMeasurablyDifferentRequestIsRefused` 用不同数量级的金额造成长度差异，才触发
  422）。

### 2.4 什么结果会被存下来

- **5xx 不存**：claim 被释放（`abandon`），下一次重试会真正执行。这是对的——服务器故障不是一个
  "已决定"的结果。
- **1xx/2xx/3xx/4xx 都存**，也都会被重放。4xx 尤其值得注意：一个被拒绝的请求是**已决定**的，重复
  提交必须得到同样的拒绝。sample 用 `aClientErrorIsADecidedOutcomeAndIsReplayedToo` 钉住这条。

### 2.5 重放时只带回四个响应头

允许清单只有 `Content-Type`、`Location`、`ETag`、`Content-Language`（大小写不敏感）。其它一律
不存不还——包括 `Date`、`Set-Cookie` 和自定义头。**没有任何"这是重放"的标记头**，客户端无法从
响应上区分首次与重放，需要区分就自己在 body 里带。

### 2.6 `require-key` 是全应用开关，不能按路径限定

幂等**没有 `url-patterns` 配置**，只有 `methods`（默认 POST/PUT/PATCH/DELETE）。所以
`require-key=true` 是整个应用范围的决定。

这一条是写 sample 时撞出来的：本来把 `require-key` 设成 true（支付型端点该这样），结果三方回调
`POST /webhooks/payment` 直接被 400 挡住——**没有任何支付网关会送 `Idempotency-Key`**。

结论：**同时对外提供接口、又接收三方回调的服务，不能简单打开 `require-key`。** sample 的默认
配置因此是 `require-key: false`，另用一个属性覆盖的测试上下文演示 true 时的 400。真要两者兼得，
只能自己写过滤器按路径分流，库不提供这个开关。

## 3. 重放防护

### 3.1 库不定义签名规范

这是与幂等最大的不同：库把**原始 body、解析好的时间戳、nonce** 交给你的
`RequestSignatureVerifier`，然后**什么都不规定**——不指定算法、不拼接规范串、不做 HMAC。
"签什么、怎么签"完全是应用的契约。

sample 定的契约是：

```
<epochSeconds> "." <nonce> "." <body>     →  HMAC-SHA256  →  小写 hex
```

把时间戳和 nonce 绑进被签名的串里是必须的——只签 body 的话，攻击者可以拿着截获的 body 配一个
新时间戳重放。测试里的客户端签名逻辑必须与 verifier 逐字一致，sample 两边都写出来了。

比较签名要用 `MessageDigest.isEqual` 这类常量时间比较：逐字节提前返回会泄漏"猜对了多少"。

### 3.2 时间戳格式与容差

`X-Timestamp` 只接受**十进制的 epoch 秒**，不认 ISO-8601、不认毫秒。容差是**双向绝对值**：
未来的时间戳和过期的一样会被拒，正好等于容差则接受（默认 5 分钟）。

### 3.3 nonce 默认是关的，而签名本身挡不住重放

`replay.nonce.enabled` 默认 `false`，**关着的时候 `ReplayGuard` 根本不会被调用**。于是"防重放"
只剩下时间戳容差——同一份签名字节在容差窗口内可以被无限次重发，而且每次签名都是合法的。

sample 的 `theSameSignedBytesCannotBeSentTwice` 就是这条：完全相同、时间戳新鲜、签名正确的请求，
第二次必须被拒。要拿到这个行为，**必须显式打开 nonce**。nonce 的 TTL 是容差的两倍。

### 3.4 七条拒绝路径共用一个 problem type

除了 body 超限是 **413** `/problems/request-too-large`，其余六条全是 **401**
`/problems/replay-rejected`，靠 `detail` 区分：`Missing signature or timestamp`、
`Malformed timestamp`、`Request timestamp outside tolerance`、`Missing nonce`、
`Invalid signature`、`Replayed request`。客户端只能按 `detail` 判断，测试也只能断言 `detail`。

### 3.5 `url-patterns` 是 servlet 模式

`replay.url-patterns` 走的是 servlet 语法（`/webhooks/*`、`*.json`、精确路径），**不是 Ant**，
所以 `/webhooks/**` 是错的。列表为空表示 `/*`（全站）。sample 只覆盖 `/webhooks/*`——对全站要求
签名会让普通客户端无法调用。

## 4. 限流

- **固定窗口，按 epoch 对齐**，三种存储实现都一样。计数**先自增再判断**，所以一个窗口里第
  `limit+1` 次调用是第一个被拒的。
- 窗口边界可以出现两倍突发（前窗末尾 + 后窗开头），这是固定窗口的固有代价。
- **桶键**：`key=header` 时取 `key-header` 的值（缺失则字面量 `"anonymous"`）；**其它任何值**
  （包括 `ip` 和拼错的值）都退化为 `getRemoteAddr()`。各存储实现再自行按租户加前缀。
- **响应头**：`headers=ietf` 出 `RateLimit: "default";r=..;t=..` 与
  `RateLimit-Policy: "default";q=..;w=..`（策略名带字面双引号）；`legacy` 出
  `X-RateLimit-Limit/Remaining/Reset`（Reset 是绝对 epoch 秒）；`both` 出全部五个；**写错值则一个
  头都不出**。
- 拒绝时 **429** `/problems/rate-limited`，`Retry-After` 至少 1（不会是 0）。

写测试踩到的一点：限流器覆盖**所有**请求，所以同一个测试类里"降低 limit 造成 429"会把配额吃光、
影响同类里其它测试。sample 的解法是把限流改成按 `X-Api-Key` 分桶，让两个测试各用各的桶。

## 5. 边界存储选型

库只提供两种共享实现，**没有 MyBatis-Plus 变体**（它们存的是框架自己的边界表，不是聚合）：

| | `-web-store-redis`（sample 用这个） | `-web-store-jdbc` |
| --- | --- | --- |
| 需要建表 | 无 | 3 张表、4 个 migration |
| 需要的配置 | 0 | `flyway.components: [web-store]`、`schema-validation`、两个 cleanup 属性 |
| 过期 | 原生 TTL | 自带清理线程，`rate-limit-retention` 必须长于最长窗口 |
| 前置 bean | `StringRedisTemplate`（自动） | `JdbcTemplate` |
| 启动失败风险 | 无 | 缺表则校验器抛异常 |

选 Redis 的理由是"要正确起来花的功夫最少"，而且库自己说它是并发下限流的推荐后端。反过来，
如果服务本来就有库、且不想多一个中间件，JDBC 方案完全可用——H2 的 migration 也在包里，能做出
零 Docker 的自包含示例。

**不要用内存兜底**。三个内存实现只在"启用了某项防护且没有共享存储"时出现，会打一条 WARN 说明
后果（重复请求会执行两次、nonce 跨实例无效、限流等于配额乘以实例数）。sample 直接配了
`allow-in-memory-stores: false`，把这条 WARN 变成启动失败——有 Redis 时它什么都不改，没有时它
阻止服务带着假防护上线。

## 6. 与业务唯一约束的分工（本篇的核心对照）

两个机制解决**不同**的问题，互相替代不了：

| | `Idempotency-Key` | 业务唯一约束 |
| --- | --- | --- |
| 解决 | **同一次提交**被重发 | **同一个业务对象**只能存在一份 |
| 生效位置 | HTTP 边界，业务代码之前 | 数据库，业务代码之内 |
| 有效期 | TTL（默认 24 小时） | 只要那行还在 |
| 谁给的 | 客户端 | 领域（sample 里是 `ClientReference` + UNIQUE 索引） |
| 表现 | 重放首次响应 | `DuplicateKeyException` → `DuplicateEntityException` → **409** |

sample 的 `adifferentKeyForTheSameBusinessOrderIsTheUniqueIndexsJob` 演示的正是这一格：**换一个
新的幂等键、提交同一个 clientReference**，边界存储没见过这次提交所以放行，命令真的执行了，
然后 UNIQUE 索引拒绝插入，拦截器链把它翻译成 409。整条路上没有一行 `catch`。

两种典型误用：

- **用幂等键顶替唯一约束**：TTL 一过，同一个业务对象就能被建第二次；
- **用唯一约束顶替幂等键**：网关超时重发时，客户端拿到的是 409 而不是首次的 201，它无法判断
  "到底成没成"。

## 7. 常见错法

| 错法 | 会发生什么 |
| --- | --- |
| 开了 `replay.enabled` 但没有 verifier bean | 启动成功、无警告、**完全不验签** |
| 开了防重放但 nonce 保持默认关闭 | 同一份签名字节可在容差窗口内无限重放 |
| 指望指纹能挡住"body 变了" | 等长的不同请求会拿到上一次的响应 |
| 一个客户端会话复用一个幂等键 | 第二个操作被判重放，静默不执行 |
| 在既接口又接回调的服务上打开 `require-key` | 三方回调全部 400 |
| `rate-limit.key` 写成 `apiKey` 之类 | 静默退化成按 IP 限流 |
| `rate-limit.headers` 写错值 | 一个配额头都不出，客户端无法自律 |
| 用 `/webhooks/**` 写 `url-patterns` | servlet 模式不认，等于没覆盖 |
| 生产保持 `allow-in-memory-stores: true` 且无共享存储 | 多实例下三项防护全部形同虚设，只有一条 WARN |
| JDBC 存储把 `rate-limit-retention` 配得比窗口短 | 清理线程把活跃窗口删掉，配额被重置 |

## 8. 本篇不覆盖

- 三方回调怎么被正确处理（翻译、业务幂等、对账兜底）——S7；
- 领域层天然幂等的命令设计、乐观锁冲突——S8；
- 多租户如何影响这三道防护的键前缀——S13；
- 认证授权本身（principal 从哪来只作为幂等作用域的一环提到）。
