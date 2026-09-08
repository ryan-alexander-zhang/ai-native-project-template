---
id: issue-00172-the-idempotency-fingerprint-does-not-cover-the-body
type: issue
status: resolved
blocks: [design-00002-web-layer, analysis-00017-samples-http-idempotency]
---

# Issue：幂等 fingerprint 不含请求体——同键、同端点、等长同类型的不同请求被当作重试回放

> 一个客户端把同一个 `Idempotency-Key` 用在两个 body 不同但长度相同的 POST 上，第二个拿到第一个的响应，业务代码没跑；应用层的"键冲突拒绝"规则在 HTTP 边上永远触发不到。

## 1. Problem

- Observed：`POST /orders` 两次，同 `Idempotency-Key`，body 分别为 `{"clientReference":"ref-6","amountCents":1000}`
  与 `{"clientReference":"ref-7","amountCents":2000}`（等长、同 `application/json`）。第二次返回 `201` 与第一次完全相同的响应体，
  handler 未执行（`aipersimmon-ddd-samples/s02-http-idempotency` 的 `aDifferentBodyOfTheSameShapeIsNOTDetected` 把这一行为**断言为预期**）。
- Expected：`422 /problems/idempotency-key-reused`。依据是幂等键的契约本身：同键 + 同请求 → 回放；同键 + 不同请求 → 拒绝
  （IETF `draft-ietf-httpapi-idempotency-key-header`：同键不同 payload SHOULD 422；Stripe 同）。对 POST 而言"请求"由其 payload 定义，
  fingerprint 的**唯一职责**就是判定"是不是同一个请求"，不覆盖 payload 的 fingerprint 没有履行这个职责。
- Trigger：任何一个 body 形状固定的写端点（`{"sku":"A1","qty":1}` vs `{"sku":"B2","qty":1}`）；chunked 传输下 `Content-Length` 为 `-1`，
  任意两个 body 都相等。在实践项目里由 rule 层的键冲突测试暴露：应用层能拒绝，HTTP 层却在 handler 之前回放。

## 2. Impact

- Affected：所有开启 `aipersimmon.ddd.web.idempotency.enabled=true` 的应用；脚手架 `multi-module` 的 `POST /orders`；samples s02。
- Since：`c2b4e730`（2026-07-29，issue-00101）· Still occurring：yes
- Severity：P1。它把"重复副作用"变成了它的镜像——一个**应当执行**的写被静默吞掉且返回一个看似成功的、属于别的请求的结果；客户端无法从响应上察觉。
  比"多买一次"更难发现。

## 3. Root Cause (first principles)

1. 期望：fingerprint 与"请求是否相同"等价。实际：fingerprint 只与"请求的描述符是否相同"等价。
2. 机制：`aipersimmon-ddd-web-spring-boot-starter/src/main/java/com/aipersimmon/ddd/web/spring/IdempotencyFilter.java:224-244`（描述符材料止于 `:235`）
   `fingerprint()` 的输入是 method、URI、query、content-type、`getContentLengthLong()`，**没有 body**。长度是 body 的一个有损投影，
   而且不是 body 的函数（chunked 时为 -1），所以既漏判（等长不同 body）也误判（同一 body 以 chunked 与非 chunked 各发一次会被判 Mismatch）。
3. 真因：issue-00101 在把"先查后存"改成 claim 状态机时，**用一个 DoS 顾虑替换掉了契约**——"为哈希缓冲 body 会给未认证调用方无上限的内存放大面"。
   这个顾虑把两件事混在一起：它与之权衡的**泄漏**（读到别人的响应）由 `principal` 进身份关闭，与摘要无关；
   而**放大面**本身的正解是有界缓冲——同一模块的 `ReplayProtectionFilter.java:93` 已经用 `CachedBodyRequestWrapper(request, maxBytes)`
   做了**边读边截断**的缓冲（超限 413）。匿名调用方确实仍能到达本过滤器（permitAll、或未装 Spring Security 时 principal 为空），
   所以上限是必需的，而"不读 body"不是——后者用放弃契约来换取本可以用一个上限换到的东西。这不是 store 的问题（Mismatch 分支本身正确），
   也不是 principal 的问题（它解决的是"读到别人的响应"，与本 issue 正交）。
- Introduced by：`c2b4e730 fix(web): claim the idempotency key before the request runs`。之前的实现没有 fingerprint 概念，
  同键一律回放——缺陷"更大"但从未声称能拒绝键复用；引入 fingerprint 并宣称能产生 `Mismatch` 之后，这个不覆盖 body 的实现才成为缺陷。

## 4. Scope (same-cause sweep)

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `IdempotencyFilter.java:224` `fingerprint()` | yes | yes | fixed here：body 进 digest，content-length 移出 |
| `ReplayProtectionFilter.java:93` | 也缓冲 body | no | 它已经缓冲并对 body 验签；本次复用其 `CachedBodyRequestWrapper` |
| `aipersimmon-ddd-web` 各 store 的 `Mismatch` 比对 | 比对 fingerprint 字串 | no | 对输入不感知，无需改动 |
| `aipersimmon-ddd-samples/s02` `aDifferentBodyOfTheSameShapeIsNOTDetected` | 把缺陷断言为预期 | yes | 反转为 422 断言，改名 |
| `aipersimmon-ddd-samples/s02` `reusingAKeyForAMeasurablyDifferentRequestIsRefused:63` | 注释与名字说"长度进指纹" | yes | 修后仍绿但理由错了：改名、改注释 |
| `IdempotencyFilter.java:213-222` javadoc | 宣称"刻意不含 body" | yes | 重写 |
| `CachedBodyRequestWrapper.java:15-38` javadoc 与异常文案 | replay 专属措辞 | yes | 泛化——本次使它成为共享构件 |
| 两个过滤器同时开启时的双重缓冲 | 同一 body 两份拷贝、两个上限 | yes | 到达时已是 `CachedBodyRequestWrapper` 则复用；先缓冲者的上限管用 |
| `CONTEXT.md` | 无 fingerprint 词条 | yes | 增补 **Request Fingerprint** |
| `CONFIGURATION.md:96-101`、`design-00002` §5.5、`analysis-00017` §2/错法表、s02 `README.md:41,64` | 把缺陷写成使用约束 | yes | 改写为新语义 |
| `issue-00101` "与原方案的差异" | 历史记录 | no | 不改，加一行指向本 issue |

## 5. Reproduction (test-first)

1. `IdempotencyFilterTest.aKeyReusedWithADifferentBodyOfTheSameLengthIsRefused`：同 `/idem`、同键、`content("aaaa")` 与 `content("bbbb")`，期望第二次 `422`，handler 计数不变。
   修前失败于 `expected 422 but was 200`。
2. `IdempotencyFilterTest.theSameBodyIsTheSameRequestWhateverItsContentLengthHeaderSays`：同 body，一次带 `Content-Length`、一次模拟 chunked（长度 -1），期望第二次回放（`200`，同响应体）。修前失败于 `expected 200 but was 422`。
3. `IdempotencyFilterTest.aBodyOverTheCapIsRefusedBeforeExecuting`：`idempotency.max-body-size` 设小，超限期望 `413 /problems/request-too-large`，handler 不跑。修前失败于 `expected 413 but was 200`。
4. s02 样例：反转 `aDifferentBodyOfTheSameShapeIsNOTDetected` → `aDifferentBodyUnderTheSameKeyIsRefused`（422，`orderCount()==1`）。

- Failing test：`aipersimmon-ddd-web-spring-boot-starter/src/test/java/com/aipersimmon/ddd/web/spring/IdempotencyFilterTest.java::aKeyReusedWithADifferentBodyOfTheSameLengthIsRefused` — fails with `Status expected:<422> but was:<200>`

## 6. Fix

- Change：`IdempotencyFilter` 在键合法后用 `CachedBodyRequestWrapper(request, maxBodyBytes)` 包装请求（超限 → 413，同 replay 过滤器），
  fingerprint = SHA-256(method, URI, query, content-type, **body 字节**)，移除 content-length；wrapper 传给下游，handler 照常读 body。
  `Idempotency` 属性新增 `maxBodySize`（`DataSize`，默认 `1MB`，与 `replay.max-body-size` 同）。
  `CachedBodyRequestWrapper` 增加 `byte[] body()`，其异常信息与 javadoc 去掉 replay 专属措辞（本次改动使之成为共享构件）。
  到达时已是 `CachedBodyRequestWrapper`（防重放过滤器已缓冲）则复用，不拷贝、不按第二个上限截断——先缓冲者的上限管用。
- 实现偏差：加入缓冲 try/catch 后 `doFilterInternal` NPath 超 PMD 阈值（256 > 200），按 `CODE_QUALITY.md` 拆为 `buffer(...)` 与
  `answerExistingClaim(...)`（对 sealed `IdempotencyClaim` 的穷尽 switch），未调阈值、未抑制。§5 的第 2、3 条用直接驱动过滤器的单测
  （`MockFilterChain`）而非 MockMvc：`MockHttpServletRequest` 的长度派生自 content 数组，MockMvc 下造不出 -1。
- Why this addresses the root cause and not the symptom：fingerprint 重新等价于"请求本身"；DoS 顾虑由它本来的正解（有界缓冲）承担，而不是由放弃契约承担。
- Alternatives rejected：
  - 加开关 `fingerprint-body=false` 保留旧行为——旧行为是缺陷，不给它一个名字。
  - 超限时退回描述符 fingerprint——静默降级正是造成本 issue 的思路；给不出保证就拒绝。
  - 保留 content-length——它是 body 的派生量，且在 chunked 下产生误判。

## 7. Verification

- 修前三条新测试按 §5 预期失败：`expected:<422> but was:<200>`、`expected:<200> but was:<422>`、`expected:<413> but was:<200>`。
- `mvn -f aipersimmon-ddd/pom.xml -pl aipersimmon-ddd-web-spring-boot-starter -am verify`：BUILD SUCCESS（实现者与主会话各跑一次）；
  `IdempotencyFilterTest` 10/10（原 6 + 新 4，含 `anAlreadyBufferedBodyIsStillPartOfTheFingerprint`），`ReplayProtectionBodyCapTest` 4/4；
  Spotless / PMD+CPD / SpotBugs 全过。
- `mvn -f aipersimmon-ddd/pom.xml install -DskipTests` 后：s02 样例 `mvn test` 18/18（`IdempotentWriteTest` 7/7，含反转后的
  `aDifferentBodyUnderTheSameKeyIsRefused`）；脚手架 `OrderIdempotencyTest` 5/5（新增 `theSameKeyOnADifferentOrderIsRefused`，Testcontainers）。
- 未能运行：`spotless:apply` 前缀在本机不可解析（离线、无 plugin metadata）；改用完整坐标对该模块无 scope 执行，`verify` 阶段的 `spotless:check` 通过。

## 8. Follow-through

- Detection gap：s02 样例与三处文档把缺陷**固化为预期行为**，测试因此不可能红。修正：s02 测试反转为守卫；库内新增三条回归。
- Doc verdict：**the doc was wrong** → 修 `design-00002` §5.5、`analysis-00017` §2 与"错法"表、`aipersimmon-ddd/CONFIGURATION.md`、s02 `README.md`；`CONTEXT.md` 增补 **Request Fingerprint** 词条。
- Residual state：已存的 idempotency 记录带旧 fingerprint；在配置的 `idempotency.ttl`（默认 24h）内对这些键的重试得到 `422` 而不是回放（客户端看到错误但不会重复副作用）。窗口过后自愈，不做迁移。
- 行为变化需写进发布说明：① 带 `Idempotency-Key` 且 body 超过 `idempotency.max-body-size`（默认 1MB）的请求现在得 413；
  ② 指纹按字节比对，同键下仅空白/键序不同的 JSON 以前回放、现在 422——重试应重发同一串字节，过滤器不在 handler 之前解析未信任 body；
  ③ body 在 `store.claim` 之前读完（指纹是 claim 的输入），慢上传期间尚无 claim 存在。
- Open Question（domain owner）：`docs/` 中没有任何 `spec`/`rule` 管辖 HTTP 幂等，design-00002 是唯一权威。
  fingerprint 契约（同键不同 payload → 422）是否应立为一条 `rule` BR 并配 GWT？本 issue 不替 owner 决定。

## Links

- Blocks: design-00002-web-layer、analysis-00017-samples-http-idempotency
- Related: issue-00101-idempotency-records-instead-of-claiming（引入本取舍）、issue-00099-tenant-isolation-fails-open-below-the-edge
