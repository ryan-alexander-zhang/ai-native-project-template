---
id: issue-00096-the-quickstart-curl-names-a-tenant-the-edge-rejects
type: issue
status: resolved
blocks: [report-00002-scaffold-ddd-review]
---

# README 快速开始的 curl 必然 400：种子只在 `__root__`，而 `__root__` 是客户端不可命名的保留租户

## 问题（现状，file:line 为证）

- **等级：Medium（这是读者接触 scaffold 的第一条命令，而它不可能成功）**。
- 本 issue **不是** [[report-00002-scaffold-ddd-review]] 评审时发现的，
  而是在实施 [[issue-00072-demo-seed-data-ships-in-a-production-migration]]
  第 4 条（"把 quickstart 的租户前提写进 README"）时撞出来的——
  那一条要求写的正是 `-H 'X-Tenant-Id: __root__'`，而这个 header 值是**必然被拒**的。
- 两边各自都对，合起来不成立：
  - 种子数据落在 `__root__`（`V1__aggregates.sql` 原种子无 `tenant_id`，
    由 `V2` 的列默认值 `'__root__'` 兜住）；
  - 而 `Tenants.of(String)`（`Tenants.java:25-31`）**显式拒绝** `__` 保留前缀：

    ```java
    if (value != null && value.startsWith(RESERVED_PREFIX)) {
      throw new IllegalArgumentException(
          "tenant id must not use the reserved '" + RESERVED_PREFIX + "' prefix: " + value);
    }
    ```

  - `HeaderTenantResolver`（`:27`）走的就是 `Tenants::of`，
    `TenantResolutionFilter`（`:85-86`）把这个异常变成 `sendError(400, "invalid tenant")`。
- 所以 `README.md` 快速开始的两条 curl（`-H 'X-Tenant-Id: __root__'`）**都是 400**，
  且响应体是 Spring 默认错误体、不是 RFC 9457 problem 文档（`sendError` 绕过了问题目录）。
- 为什么一直没被发现：**没有任何测试用 HTTP 发过 `__root__`**。
  走 HTTP 的测试用 `acme` / `globex`（21 处 / 12 处），
  用到 `__root__` 的测试全部直接走 `commandBus`（无绑定租户 ⇒ 命令总线填 `__root__`，
  见 `OperationLogRecordingTest:67-69`）或直接写 `JdbcTemplate`。
  两条路径各自被覆盖，**它们的交叉点没有**。

## 根因（第一性）

1. **观察 vs 期望**：期望"种子数据所在的租户可以被客户端请求"；
   实际"种子在一个客户端按设计无法命名的租户里"。
2. **最小机制**：`__root__` 有两个互斥的身份——
   它既是**框架哨兵**（无租户上下文时的兜底），又被当成了**演示数据的归属租户**。
   前者要求客户端不能伪造它（故有保留前缀校验），后者要求客户端能请求它。
3. **真根因**：种子是在多租户**之前**写的，那时它不属于任何租户；
   多租户上线时它由列默认值被动落到了 `__root__`——
   **没有人选择过这个归属**，它是默认值的副产品。
   而 `__root__` 的"不可从外部命名"是被明确设计过的
   （`Tenants.java:6-10` 注释专门解释了为什么）。
   一个被设计过的约束撞上了一个没被设计过的默认值。
4. **排除的伪根因**：不是保留前缀校验太严——它完全正确，
   客户端能自称框架哨兵才是真漏洞；也不是 `missing-policy=REJECT` 的问题——
   不带 header 是 400，带 `__root__` 也是 400，但原因不同。

## 复现（test-first）

```java
@Test
void theQuickstartTenantIsAcceptedAtTheEdge() {
  HttpHeaders headers = new HttpHeaders();
  headers.set("X-Tenant-Id", "__root__");        // README 快速开始照抄
  assertEquals(200, http.exchange("/v3/api-docs", GET,
      new HttpEntity<>(headers), String.class).getStatusCode().value());
}
```

当前红（400 "invalid tenant"）。实际是在写
`ProductionProfileBootTest.theContractIsPublishedButTheInteractiveConsoleIsNot`
时以这个形态撞出来的。

## 修复

演示需要一个**普通**租户，而不是哨兵：

1. `db/dev/afterMigrate__seed.sql` 同时种两个租户：
   - `__root__` —— 供 7 个直接走 `commandBus`、不绑租户的验收测试使用（它们靠的就是哨兵兜底）；
   - `demo` —— 供 README 的 curl 使用，一个普通租户，没有任何保留语义。

   两份行是**同一组自然键在两个租户下**，正好也是复合主键 `(tenant_id, id)` 最小的活例子。
2. README 快速开始改用 `-H 'X-Tenant-Id: demo'`，并加一段引述说明**为什么不是** `__root__`——
   这一句比改掉的那个 header 更重要，它解释的是哨兵为什么不可外部命名。
3. 刻意**不**种 `acme` / `globex`：多租户测试自己种这两个租户并设定自己的信用额度与库存量
   （`TwoTenantAcceptanceTest` 用 credit 1000000 / stock 1000，`OrderListPagingTest` 用 stock 1000），
   而它们用的是 `ON CONFLICT DO NOTHING`——种子先落地就会**赢下冲突**、静默改掉它们的夹具。
   `OrderListPagingTest` 一个类就要消耗 22 单位 SKU-1，种子的 10 会让它红。

## 未修的关联缺口（不在本 issue 范围）

`TenantResolutionFilter` 用 `response.sendError(400, ...)` 拒绝请求，绕过了
`OrderingProblemCatalog`，所以租户相关的 400 是 Spring 默认错误体而非 RFC 9457 problem 文档，
与 [[issue-00080-problem-title-key-has-no-message-bundle]] 所在的错误契约不一致。
这是框架侧（`aipersimmon-ddd-tenancy-spring-boot-starter`）的问题，不是 scaffold 的，另行落 issue。

## 验证结果

已修。随 profile 拆分一并落地。

- 种子改为 `__root__` + `demo` 两个租户；README 快速开始改用 `demo` 并说明原因。
- `ProductionProfileBootTest` 的 `tenantHeader()` 用 `demo`，javadoc 记下为什么不能用 `__root__`。
- 62 个测试全绿（`mvn -o test -pl start -am`）；7 个依赖 `__root__` 种子的测试不受影响，
  因为那一份仍在。

## 关联

- [[issue-00072-demo-seed-data-ships-in-a-production-migration]]（本 issue 由其第 4 条实施时暴露）
- [[issue-00074-one-config-file-with-development-values-only]]（同一次 profile 拆分）
- [[decision-00018-multi-tenancy-boundaries]]（`__root__` 哨兵的由来）
- [[design-00009-multi-tenancy-tenant-id]]
- [[report-00002-scaffold-ddd-review]]
