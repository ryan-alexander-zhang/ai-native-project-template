---
id: issue-00101-idempotency-records-instead-of-claiming
type: issue
status: resolved
parent: report-00003-ddd-library-review-2026-07-29
---

# 幂等只记录不抢占：并发首次请求双执行，且键不含调用方——任何人猜到键就能取回别人的响应

## 问题（现状，file:line 为证）

两个 Critical 出自同一个 SPI 形状，故一并修。

### C2：「只执行一次」不成立

- 旧 SPI 只有两个动作（`IdempotencyStore.java`）：`find(key)` 与 `saveIfAbsent(key, response, ttl)`。
- 旧过滤器时序（`IdempotencyFilter.java:83-95`）：

```java
Optional<StoredResponse> replay = store.find(key);   // 1. miss
if (replay.isPresent()) { writeStored(...); return; }
filterChain.doFilter(request, wrapper);              // 2. 处理器执行，副作用提交
store.saveIfAbsent(key, new StoredResponse(...), ttl); // 3. 才落库
```

- 两个并发首次请求都在步骤 1 miss、都执行步骤 2。`saveIfAbsent` 的原子性只决定**谁的响应被留下**，
  而两次副作用都已提交 → 重复扣款。
- **这不是罕见竞态，而是幂等键存在的那个场景本身**：客户端首次超时后重试，而首次仍在飞行中。
- SPI 的 javadoc 写着 "so concurrent first-time requests do not both execute"——
  这个承诺在这个形状下**无法实现**：等到有东西可存，两次副作用已经发生了。

### C1：重放先于认证，且键只按租户区分

- 过滤器注册在 `HIGHEST_PRECEDENCE + 40`（`AipersimmonDddWebAutoConfiguration.java:173`），
  即 Spring Security 链（order `-100`）**之前**。
- `store.find(key)` 与响应回写完全不引用认证主体、方法或路径；存储键仅 `tenant + key`
  （`JdbcIdempotencyStore.tenant()`、`RedisIdempotencyStore.tenantKey()`、`InMemoryIdempotencyStore.tenantKey()`）。
- 加之默认 `HeaderTenantResolver` 读客户端可控的 `X-Tenant-Id`（`issue-00099` 已改为需显式 opt-in）：
  攻击者带上受害者的 key 与租户值发**任意**请求，即可拿回其含账户数据的已存响应，全程绕过安全链。

### 同源的次级缺陷（一并修）

- **5xx 被冻结 24 小时**（旧 `:93-94` 无状态过滤）：瞬时故障存进键里，此后每次重试都拿到那个失败——
  正好废掉幂等键的用途。
- **落定失败会吞掉响应**（旧 `:93-95`）：`saveIfAbsent` 抛异常时 `copyBodyToResponse()` 还没执行，
  客户端收到 500，而副作用已提交。
- **输家返回自己算出的响应**：`saveIfAbsent` 返回 `false`（跨实例竞争落败）时不重读赢家的结果，
  "一个键一个答案"恰在最需要的时刻破功。
- **键长度不校验**：客户端可控值直接进 `VARCHAR(255)`，超长时在请求**已执行之后**才炸。

## 根因（第一性）

1. **观察 vs 期望**：期望"一个键一次执行"；实际"一个键一份被保留的响应"。
2. **最小机制**：SPI 只提供了"记录既成事实"的动作。`saveIfAbsent` 是**执行后**的原子写，
   而要阻止第二次执行，需要一个**执行前**的原子写。缺的不是实现的仔细程度，是缺一个状态。
3. **真根因**：把幂等建模成了"响应缓存"（key → response），而它其实是**并发控制**
   （key → 谁在处理 / 结果是什么）。缓存模型里没有"正在进行中"这个状态可表达，
   于是"另一个请求正在跑"只能被误当成"还没有人跑过"。
   同理，缓存的键只需要能查到值，而并发控制的键必须能表达**归属**——这就是 C1 的同一个根。
4. **排除的伪根因**：不是三个后端实现得不够原子（它们各自的原子原语都用对了：PK 冲突 / `SET NX` /
   `compute`）；不是 TTL 或时钟问题。**缺陷在 SPI 的形状里，所以三个后端不可能有一个是对的。**

## 复现（test-first）

```java
// 旧 SPI 下无法表达的场景，新 SPI 下是一等状态：
IdempotencyKey attempt = new IdempotencyKey("acme", "", "k1", "fp");
store.claim(attempt, LEASE);                       // Won
store.claim(attempt, LEASE);                       // 修复前：等价于再次 miss → 双执行
                                                   // 修复后：InProgress
```

```java
// C1:同一租户内,alice 已完成的结果对 bob 不可达
store.claim(new IdempotencyKey("acme", "alice", "shared-key", "fp"), LEASE);   // Won
store.complete(alice, new StoredResponse(201, "alice's account".getBytes(), Map.of()), TTL);
store.claim(new IdempotencyKey("acme", "bob", "shared-key", "fp"), LEASE);
// 修复前(键无 principal):bob 拿到 alice 的响应体
// 修复后:Won —— bob 的键与 alice 的无关
```

```java
// 5xx 不得冻结
mvc.perform(post("/idem/fail").header("Idempotency-Key", "e1"));  // 500
mvc.perform(post("/idem/fail").header("Idempotency-Key", "e1"));  // 修复前:回放 500,处理器只跑 1 次
                                                                  // 修复后:再次执行,处理器跑 2 次
```

## 修复

**SPI 重写为 claim 状态机。** 三个动作、四个状态：

```java
IdempotencyClaim claim(IdempotencyKey key, Duration leaseTtl);
void complete(IdempotencyKey key, StoredResponse response, Duration ttl);
void abandon(IdempotencyKey key);

sealed interface IdempotencyClaim { Won | InProgress | Replay(StoredResponse) | Mismatch }
```

- **`Won`** → 执行；**`InProgress`** → 409 + `Retry-After`（没有结果可给，执行则重复副作用）；
  **`Replay`** → 原样回放；**`Mismatch`** → 422（键被用于另一个请求，执行与回放都不对）。
- **身份是三元组 `(tenant, principal, key)`**。`principal` 由新 SPI
  `IdempotencyPrincipalResolver` 提供（默认实现 `SecurityContextPrincipalResolver` 读 Spring Security
  上下文，该依赖 optional；匿名认证与未认证都返回空，因为所有匿名调用方共享一个名字，
  按它归集正是要防的池化）。无认证端点返回空是正确的——那里租户就是身份的全部。
- **`fingerprint` 被比对但不进身份** → 产生 `Mismatch`。**刻意不含请求体**：
  为哈希缓冲每个请求体会给未认证调用方一个内存放大面，而"读到别人的响应"由 `principal` 关闭，不靠摘要。
  取舍写进 javadoc：同一键换端点/换载荷形状能抓到，同端点下两个等长同类型的不同请求体抓不到。
- **claim 自带 lease**（新配置 `claim-lease`，默认 1min，独立于 `ttl` 的 24h）：
  中途死掉的调用方不会把键占到保留期结束；lease 过后下一次尝试可接管。
- **过滤器移到认证之后**（order `0`；Spring Security 链在 `-100`）。
- **5xx 走 `abandon`**，4xx 是已决结果照常 `complete`；`copyBodyToResponse()` 放进 `finally`，
  使落定阶段的存储故障不会吞掉一个副作用已提交的响应；处理器抛异常时也 `abandon`
  （异常继续交给它的错误处理）；`abandon` 自身失败只 WARN——lease 会自行过期，
  把一个已完成的写变成 500 更糟。
- **键长度在执行前校验** → 400。

**三个后端各自的原子原语**：JDBC 用 PK `(tenant_id, principal, idempotency_key)` 的
`INSERT` 冲突作串行点（V3 迁移 × 3 方言：加 `principal`/`fingerprint`/`state`、
响应列改可空、重建 PK，并给 `expires_at` 建索引以便将来的保留期清理）；
Redis 用 `SET NX` 写 pending 标记，其 TTL 即 lease；内存用 `compute` 持桶锁做状态跃迁。

## 验证结果

- **库全量 `mvn verify`（47 模块，含 Testcontainers PG/MySQL、spotless-check、PMD/CPD、SpotBugs）：
  BUILD SUCCESS**；脚手架 `multi-module` 全量 verify 亦 SUCCESS（`OrderIdempotencyTest` 的
  两租户同键场景在新语义下不变）。
- 新增覆盖：`JdbcWebStoreTest` 7 个（两租户同键、两主体同键、in-flight→InProgress、
  完成→回放→过期后重新可用、lease 过期被接管、abandon 释放但不删已完成结果、fingerprint 不符→Mismatch）；
  `RedisWebStoreTest` 4 个同构场景；`IdempotencyFilterTest` 4 个新增
  （5xx 不落库故重试仍执行、4xx 落库并回放、同键换端点 422、超长键在执行前 400）。
- **与原方案的差异**：原计划把请求体哈希进 fingerprint。实测发现那会在认证之前引入无上限的请求体缓冲，
  与 `ReplayProtectionFilter` 已知的内存 DoS 面同源；而 C1 的实际泄漏由 `principal` 关闭，
  摘要只是纵深防御，故改为只用请求行与内容描述符，并把取舍写进文档。
- **仍然开着的相邻问题**：web-store 两张表仍无保留期清理任务（本次只加了 `expires_at` 索引使其可行）；
  `JdbcRateLimiter` 的窗口边界竞态、`ReplayProtectionFilter` 的无上限体缓冲、兜底 500 不记日志
  均未动——见 `report-00003` §2。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（C1 / C2 由本 issue 结掉）
- 设计：[[design-00002-web-layer]] §4.3 与 §5.5 已据此修订
- 前序：[[issue-00099-tenant-isolation-fails-open-below-the-edge]]（键里的 tenant 段依赖它的收口点）
- 相关：[[issue-00064-a-replayed-idempotent-response-loses-its-location-header]]（回放 header allow-list，保留）
