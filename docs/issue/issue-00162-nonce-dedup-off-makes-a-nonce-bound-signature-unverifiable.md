---
id: issue-00162-nonce-dedup-off-makes-a-nonce-bound-signature-unverifiable
type: issue
role: main
status: resolved
---

# 关掉 nonce 去重会让"把 nonce 签进去"的验签方案彻底验不过（P2，配置陷阱）

2026-08-04 写 S7 的 samples 时撞到（`aipersimmon-ddd-samples/s07-third-party-integration/payment-service`）。
不是安全漏洞，也不是无声失败——它**大声地全量 401**，但报出来的原因（"Invalid signature"）指向的是攻击，
而真实原因是我方少配了一个开关。

## 现象

`aipersimmon.ddd.web.replay.nonce.enabled=false` 之后，**每一个合法回调都 401**，尽管请求带着正确的
`X-Nonce` 头、正确的时间戳、以及用共享密钥算出来的正确签名。

## 链条

一行代码。`aipersimmon-ddd-web-spring-boot-starter/src/main/java/com/aipersimmon/ddd/web/spring/ReplayProtectionFilter.java:65`：

```java
String nonce = nonceEnabled ? request.getHeader(nonceHeader) : null;
```

这个 `nonce` 一路传进 `SignedRequest`（同文件 `:143`）：

```java
if (!verifier.verify(new SignedRequest(signature, cached.bodyAsString(), timestamp, nonce))) {
```

于是：**开关关掉时，`RequestSignatureVerifier` 拿到的 nonce 是 `null`，即使请求头里明明有一个。**
`RequestSignatureVerifier` 只能看到 `SignedRequest`，没有别的入口拿到原始请求，所以它无法自救。

而"把 nonce 签进规范串"正是本库自己推荐的方案。S2 的 `HmacRequestSignatureVerifier` javadoc 写着：

> Binding the timestamp and nonce into the signed string is what stops an attacker replaying a captured
> body with a fresh timestamp.

规范串是 `<epochSeconds>.<nonce>.<body>`。发送方签的是真 nonce，验证方算的是空 nonce，必然不等。

## 为什么这个开关看起来像可选的

`aipersimmon.ddd.web.replay.nonce.enabled` 的语义读起来是"要不要**去重**"——防御深度的一档，关掉只是少
一层保护。属性文档也是这么说的。但对任何把 nonce 签进签名的方案来说，它其实是"**签名能不能验**"的总开关：
两件事被同一个 flag 控制着，而只有其中一件写在名字里。

## 代价

- 一个只想省掉 nonce 表（例如接受时间戳窗口内的重放风险、或者上游本身有幂等）的部署，会得到全量 401，
  而 401 的正文说的是签名无效——排查方向被指错。
- 反过来，S7 的实测：把这一行关掉，`CallbackIngestionTest` 里 **7 个测试变红**，其中只有 1 个是关于重放
  的，另外 6 个是"合法回调被拒"。

## 修复要求

**把"读 nonce"与"用 nonce 去重"拆开。** `:65` 改成无条件读头，去重仍然按开关走（`:146` 那句不动）：

```java
String nonce = request.getHeader(nonceHeader);
```

要点：

- `checkHeaders` 里"nonce 缺失即拒"（`:115-117`）**应当继续只在开关打开时生效**——不带 nonce 的方案
  （Stripe、Slack 都是 `timestamp.body`）不该被逼着发一个头；
- 改完之后，开关关掉且请求没带 nonce 头时，验证方拿到的仍是 `null`，与今天完全一致，所以对既有部署
  是零行为变化；
- `CONFIGURATION.md` 的 `web.replay.nonce` 一节该补一句：nonce 是否参与签名由验签实现决定，本开关只管
  去重。

## 复现

`aipersimmon-ddd-samples/s07-third-party-integration/payment-service`：把 `application.yaml` 里
`aipersimmon.ddd.web.replay.nonce.enabled` 改成 `false`，跑
`mvn -pl s07-third-party-integration/payment-service -am verify -Dtest=CallbackIngestionTest`，
7 红。签名两端的实现分别在
`payment-service/.../infrastructure/gateway/GatewayCallbackSignatureVerifier.java` 与
`gateway-stub/.../CallbackSigner.java`。

## 解决记录（2026-08-04）

**按修复要求原样改**：`ReplayProtectionFilter` 里那一行

```java
String nonce = nonceEnabled ? request.getHeader(nonceHeader) : null;   // 之前
String nonce = request.getHeader(nonceHeader);                          // 现在
```

去重仍按开关走（`verifyAuthenticity` 里那句不动），`checkHeaders` 的"缺 nonce 即拒"也仍然只在开关打开时
生效——所以签 `timestamp.body` 的方案（Stripe、Slack）不被逼着发一个头，且拿到的仍是 `null`，对既有部署
零行为变化。改动点旁边留了一段注释，写明"读"与"去重"是两个决定而只有一个写在了属性名里。

**测试**：新增 `ReplayProtectionNonceBindingTest`（3 条），验签实现是最小的"把 nonce 签进去"模型
（`sig:<nonce>`），dedup 关：① 带 nonce 的合法请求通过（回归本身）；② 同一个 nonce 连发两次都通过
（开关仍然只管去重，修复没有偷偷把它打开）；③ 完全不带 nonce 头的方案不受影响。既有
`ReplayProtectionFilterTest`（7 条，验签忽略 nonce，因此当初看不到这个缺陷）与
`ReplayProtectionBodyCapTest`（4 条）保持绿。

**用 issue 自己的复现验收**：S7 的 payment-service 把 `nonce.enabled` 改成 `false` 跑
`CallbackIngestionTest` —— 修复前 **7 红**（6 条是合法回调被拒），修复后 **恰好 1 红**，且是
`thesameSignedBytesCannotBeSentTwice`。**现在关掉去重的代价就是去重，仅此而已。** S7 的 yaml 注释改成
记录这次前后对比。

`CONFIGURATION.md` 的 `replay.nonce.enabled` / `replay.nonce.header` 两行改写：说明这个开关管"要求 + 去重"，
不管"nonce 是否到达验签实现"。
