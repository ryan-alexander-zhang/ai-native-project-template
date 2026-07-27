---
id: issue-00064-a-replayed-idempotent-response-loses-its-location-header
type: issue
role: main
status: resolved
parent: plan-00015-scaffold-depth-and-evaluability
---

# 幂等重放只还原 `Content-Type`：重试拿到 201 却没有 `Location`，找不到自己创建的资源

## 问题（现状，file:line 为证）

- **等级：Medium（不丢数据，但让幂等在它最主要的用途——创建资源——上只完成了一半）**。
- `POST /orders` 带 `Idempotency-Key` 首次返回 `201` + `Location: /orders/<id>`；
  带**同一个 key** 重试返回 `201`，但**没有 `Location`**。客户端因此知道"没有重复下单"，
  却不知道那张订单在哪——而它重试的原因通常正是没收到第一次的响应。
- 存的时候就只存了一个头：
  `aipersimmon-ddd-web-spring-boot-starter/.../IdempotencyFilter.java:82-87`

  ```java
  byte[] body = wrapper.getContentAsByteArray();
  String contentType = wrapper.getContentType();
  Map<String, String> headers =
      contentType == null ? Map.of() : Map.of(HttpHeaders.CONTENT_TYPE, contentType);
  store.saveIfAbsent(key, new StoredResponse(wrapper.getStatus(), body, headers), ttl);
  ```

  回放时 `writeStored`（`:90-95`）忠实地把这个 map 写回去——所以丢失发生在**写入**，不在回放。
- 实测：样例的 `OrderIdempotencyTest#retryingTheSameKeyPlacesOneOrderAndEmitsOneEvent` 断言
  首次响应有 `Location`、重放响应没有。该断言当前**通过**。

## 根因（第一性）

1. **观察 vs 期望**：期望"重放与首次响应等价"；实际"重放只等价于 (status, body, content-type)"。
2. **最小机制**：`StoredResponse` 能装任意 header map，但写入点只挑了 `Content-Type` 一个。
   没有任何地方决定"哪些 header 属于响应的语义"——只是选了最省事的那一个。
3. **真根因**：把"可回放的响应"定义成了"能渲染出同样的字节"，而不是"能让客户端得到同样的结论"。
   对 `201 Created` 来说，`Location` 不是装饰，它是这个状态码的载荷——RFC 9110 §15.3.2 里
   201 的语义就包含"由 `Location` 指出被创建的资源"。丢掉它，重放的 201 就不再是同一个答复。
4. **为什么没被库的测试发现**：`IdempotencyFilterTest` 用的响应没有 `Location`，
   断言的是 status 与 body 一致。它测的是机制，不是"创建型响应"这一最常见的用法。
5. **排除的伪根因**：不是 `ContentCachingResponseWrapper` 拿不到 header——
   `wrapper.getHeaderNames()` / `getHeader(name)` 都可用；写入点没有去取而已。

## 复现（test-first）

样例 `OrderIdempotencyTest`（真 HTTP + PostgreSQL）：

```java
assertNotNull(first.getHeaders().getLocation());
assertNull(retry.getHeaders().getLocation());   // 当前行为
```

修复后第二行应改为 `assertEquals(first…, retry…)`；测试里已注明这一点，
所以修复时不会漏改。

## 修复

在写入点保存一份**可回放的 header 白名单**：`Location`、`Content-Type`、`ETag`、`Content-Language`。
不整份复制：`Date`、`Set-Cookie`、连接相关的头回放出去是错的，而白名单让"哪些头属于响应的语义"
成为一个显式决定，而不是一个遗漏。

`IdempotencyFilter` 新增 `REPLAYABLE_HEADERS`（小写比较——header 名不区分大小写）与
`replayableHeaders(wrapper)`。其中一处不是多余的谨慎：`Content-Type` 仍走 `wrapper.getContentType()`
单独读，因为 Tomcat 把 content type 存在专门的字段里而**不在** `MimeHeaders` 中，
只遍历 `getHeaderNames()` 会在真容器上丢掉它（MockMvc 上不会——这正是库内单测看不见的那类差异）。

## 验证结果

**已修复。**

- 库侧 test-first：`IdempotencyFilterTest#aReplayedCreateKeepsTheHeadersThatCarryItsMeaning`
  （新增 `POST /idem/create` 返回 201 + `Location` + `ETag` + 一个非语义头 `X-Trace-Note`）。
  修复前红：`Response header 'Location' expected:</idem/3> but was:<null>`；修复后绿。
  断言同时钉住"白名单之外的头不回放"——否则修复很容易滑成整份复制。
- 样例侧断言按 issue 里预留的提示同步翻转：`OrderIdempotencyTest` 由 `assertNull(retry…Location)`
  改为 `assertEquals(first…Location, retry…Location)`，类注释的"rough edge"一节改写为
  "重放是同一个答复，不只是同一串字节"。
- 库 BUILD SUCCESS（773 项测试，0 失败）；样例 `verify` BUILD SUCCESS（189 项，0 失败）。

## 关联

- [[issue-00062-web-store-module-does-not-displace-the-in-memory-stores]] ·
  [[issue-00063-in-memory-web-store-cannot-be-built-when-several-clocks-exist]]（同一次落地 F2 时发现的三件事）
- [[plan-00015-scaffold-depth-and-evaluability]]（F2）
