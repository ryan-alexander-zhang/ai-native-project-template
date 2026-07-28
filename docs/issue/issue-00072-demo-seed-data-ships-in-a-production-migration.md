---
id: issue-00072-demo-seed-data-ships-in-a-production-migration
type: issue
role: main
status: resolved
parent: report-00002-scaffold-ddd-review
---

# 演示种子数据写在 V1 生产迁移里，会在每一个环境执行

## 问题（现状，file:line 为证）

- **等级：Medium（本身无害，但 scaffold 就是拿来被复制的，这个坏习惯会一起被复制到真实项目的生产库）**。
- `V1__aggregates.sql:55-63` 在建表之后直接插数据：

```sql
-- seed (same demo data the in-memory repositories used) ----------------------
INSERT INTO ordering.customers (id, name, credit_minor, currency)
VALUES ('CUST-1', 'Acme', 100000, 'USD');

INSERT INTO inventory.stocks (sku, available)
VALUES ('SKU-1', 10), ('SKU-2', 5), ('SKU-RESTRICTED', 10);
```

- Flyway 的版本化迁移**在每一个环境执行且仅执行一次**，没有 profile 概念。
  一个基于本 scaffold 起的项目，第一次部署到生产时就会得到一位名叫 Acme 的客户和三个假 SKU。
- 注释里的 "same demo data the in-memory repositories used" 还暴露了它的来历：
  这是从早期内存仓储时代平移过来的种子，当时它确实只活在进程里；
  搬到 Flyway 之后性质变了，注释没跟上（与
  [[issue-00078-six-places-still-describe-the-repositories-as-in-memory]] 同源）。
- 反证据：scaffold 自己的测试**已经不依赖这份种子**了。
  多租户上线后，`(tenant_id, id)` / `(tenant_id, sku)` 成了复合主键（`V2:28-32`），
  而种子行落在 `__root__` 下，于是 8 个测试类各自在 `@BeforeEach` 里重新种自己的租户数据
  （`ExceptionContractTest.java:52-66`、`OrderIdempotencyTest.java:74-88`、
  `TwoTenantAcceptanceTest.java:60-77`、`SelfCancelTest.java:56-67` 等）。
  只有直接走 `commandBus`、不绑租户的那几个 flow 测试还在用它。

## 根因（第一性）

1. **观察 vs 期望**：期望"版本化迁移只描述**结构**"；实际"V1 同时描述了结构和一份演示数据"。
2. **最小机制**：Flyway 的 `db/migration` 目录里，DDL 与 DML 没有任何区分手段——
   放进去的一切都会在所有环境按序执行一次。种子被放进了这个目录。
3. **真根因**：把"让样例跑起来需要的数据"与"应用的 schema"当成了同一件事，
   因为在开发者的本地它们确实总是一起需要。区分它们的判据是
   "**换一个环境，这段还应该执行吗**"——种子的答案是否定的，而目录不提供表达这个否定的位置。
4. **排除的伪根因**：不是"种子数据本身不该存在"——一个 scaffold 必须能一条 curl 跑通，
   种子是必要的；问题只是它被放在了一个无法按环境关闭的位置。

## 复现（test-first）

这类问题没有自然的失败测试（种子在测试里是**期望**存在的）。用一条结构断言代替：

```java
@Test
void versionedMigrationsContainNoSeedData() throws IOException {
  for (Path sql : Files.walk(Path.of("src/main/resources/db/migration")).filter(isSql).toList()) {
    assertFalse(Files.readString(sql).toUpperCase(Locale.ROOT).contains("INSERT INTO"),
        sql + " —— 版本化迁移只描述结构；演示/种子数据放 db/dev");
  }
}
```

这条断言会立刻变红，修复后转绿，并作为回归护栏防止下一个人再塞一条 `INSERT`。

## 修复

1. 把种子移到 `src/main/resources/db/dev/afterMigrate__seed.sql`
   （`afterMigrate` 回调是幂等重跑友好的；配合 `ON CONFLICT DO NOTHING` 更稳）。
2. 在 dev profile 里追加 location：

```yaml
# application-dev.yml
spring:
  flyway:
    locations: classpath:db/migration,classpath:db/dev
```

   生产 profile 不含 `db/dev`，所以生产库永远看不到 Acme。
3. 种子行显式写上 `tenant_id = '__root__'`——现在靠列默认值兜住，
   一旦有人改了默认值就会静默落到别处。
4. 顺带把 quickstart 的租户前提写进 README（见评审 B1）：
   种子只存在于 `__root__`，所以 curl 必须带 `-H 'X-Tenant-Id: __root__'`。

本 issue 依赖 profile 拆分，实施时与
[[issue-00074-one-config-file-with-development-values-only]] 一起做最省事。

## 验证结果

已修。四条修复全部落地，与
[[issue-00074-one-config-file-with-development-values-only]] 同批（profile 拆分是前置）。

- 种子从 `V1__aggregates.sql` 移到 `db/dev/afterMigrate__seed.sql`；V1 原处留一段注释说明
  **为什么这里不能有数据**，而不是留白——留白会被下一个人填回去。
- `application-dev.yml` 的 `spring.flyway.locations: classpath:db/migration,classpath:db/dev`；
  prod 显式写 `classpath:db/migration` 单条。生产库永远看不到 Acme。
- 种子行显式写 `tenant_id`，按第 3 条：不再靠列默认值兜。
- 全部语句 `ON CONFLICT DO NOTHING`，冲突目标是 V2 起的**复合键** `(tenant_id, id)` / `(tenant_id, sku)`，
  所以 afterMigrate 每次 migrate 重跑都无害。

**第 4 条（README quickstart 租户前提）实施时发现它本身是错的**：
方案要求写 `-H 'X-Tenant-Id: __root__'`，而 `__root__` 是客户端按设计**不可命名**的保留租户，
那条 curl 必然 400。已另落 [[issue-00096-the-quickstart-curl-names-a-tenant-the-edge-rejects]]
并一并修掉：种子改为同时种 `__root__`（供 7 个走 `commandBus` 不绑租户的测试）与
`demo`（供 README 的 curl），README 改用 `demo` 并解释为什么不是 `__root__`。

`MigrationContentTest` 按复现段落地为结构断言，并且是**回归护栏优先**：
它读源码树而不是 classpath——`target/classes` 里的陈旧副本会让 classpath 扫描检查上一次构建。
（这个坑在上一组修复里真实踩过一次。）

验证：`mvn -o test -pl start -am` 全绿，62 个测试 0 失败。7 个仍依赖种子的验收测试不受影响。
负向对照：往 `V3` 塞一条 `INSERT`，`MigrationContentTest` 立刻红并点名文件；移除后绿。
另有 `ProductionProfileBootTest.theDemoSeedIsNotInAProductionDatabase` 从另一端钉住同一件事——
prod profile 下两张表 count 为 0，而 schema 完整可查。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00074-one-config-file-with-development-values-only]]（profile 拆分是本修复的前置）
- [[issue-00078-six-places-still-describe-the-repositories-as-in-memory]]（同一次内存→PostgreSQL 迁移留下的注释债）
- [[decision-00018-multi-tenancy-boundaries]]（`__root__` 哨兵）
