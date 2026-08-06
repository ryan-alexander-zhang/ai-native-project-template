---
id: issue-00078-six-places-still-describe-the-repositories-as-in-memory
type: issue
status: resolved
blocks: [report-00002-scaffold-ddd-review]
---

# 内存仓储时代的注释还留在六个地方，另有一处 README 断言与端点实际不符

## 问题（现状，file:line 为证）

- **等级：Low（不影响运行；但这是一份被当作教材读的代码，注释是它的主要产出之一）**。
- **A. 六处仍称仓储是 in-memory**，而 plan-00007 早已把它们换成 MyBatis + PostgreSQL：

  | 文件 | 行 | 现文 |
  |---|---|---|
  | `ordering-infrastructure/pom.xml` | `:16-18` | "the repositories are kept in memory so the project runs without a database" |
  | `ordering-infrastructure/pom.xml` | `:22` | `<description>…of the ordering context (in-memory).</description>` |
  | `inventory-infrastructure/pom.xml` | `:14` | "in-memory stock and the event transport" |
  | `inventory-infrastructure/pom.xml` | `:17` | `<description>…(in-memory).</description>` |
  | `ordering/.../persistence/order/package-info.java` | `:1` | "In-memory implementation of the Order repository port." |
  | `ordering/.../persistence/customer/package-info.java` | `:1` | "In-memory implementation of the Customer repository port, with seed data." |
  | `inventory/.../persistence/package-info.java` | `:1` | "In-memory implementation of the Stock repository port, with seed data." |
  | `inventory/.../infrastructure/package-info.java` | `:3` | "the in-memory stock store and the event transport" |
  | `ApplicationSmokeTest.java` | `:9` | "the in-memory repositories … all resolve" |

  实际实现是 `MyBatisOrders` / `MyBatisCustomers` / `MyBatisStocks` / `MyBatisReservations`，
  各自的类级 javadoc **已经更新且写得很好**（如 `MyBatisOrders.java:18-23` 解释了
  与 outbox 同事务、行集整体重写的取舍）——漂的只是外层的 pom 与 package-info。
  同一个包里两份互相矛盾的描述，比只有一份错的更容易误导。

- **B. README 的一条断言与控制器实际不符**（`README.md:141`）：

  > "so `OrderController` offers only `place`, `approve-review`, and `read`."

  实际有五个端点（`OrderController.java`）：`POST /orders`(`:61`)、
  `POST /{id}/approve-review`(`:71`)、**`POST /{id}/cancel`**(`:89`)、
  **`GET /orders`**(`:108`)、`GET /{id}`(`:127`)。
  该句所在段落论证的是"为什么没有公开的 `confirm` 端点"——**论证本身完全成立**，
  只是结尾那句枚举没有随 F3/F4 加进来的两个端点更新。
  README 自己的能力表反而是对的（`:78` 列了 list，`:82` 列了 cancel）。

## 根因（第一性）

1. **观察 vs 期望**：期望"同一事实在文档里只有一处声明"；
   实际"同一事实被声明在类 javadoc、package-info、pom description、README 四个层级，
   只有最靠近代码的那层被维护"。
2. **最小机制**：类 javadoc 与代码同文件、改代码时在视野内；
   package-info / pom / README 不在。没有任何检查把它们绑在一起。
3. **真根因**：这些描述**重复**了代码已经表达的东西（"这是什么实现"），
   而不是补充代码无法表达的东西（"为什么这么实现"）。
   重复的描述必然漂移——它没有独立的存在理由，因此没人有理由去维护它。
   反证：那些解释**为什么**的 javadoc（`MyBatisOrderQueries.java:18-20` 说明为何用
   `@Component` 而非 `@Repository`；`OrderListMapper.java:22-25` 说明为何不手写租户谓词）
   一处都没漂——因为它们不可从代码推导，所以改代码时必须回看。
4. **排除的伪根因**：不是 `PackageInfoTest` 失职——它只断言 package-info **存在**
   （`PackageInfoTest.java:29-39`），不可能校验内容真伪。

## 复现（test-first）

内容真伪无法自动断言，但这一类具体错误可以：

```java
@Test
void noSourceStillCallsThePersistenceAdaptersInMemory() throws IOException {
  List<String> offenders = sourcesUnderReactor()
      .filter(p -> readString(p).matches("(?s).*[Ii]n-memory.*"))
      .filter(p -> p.toString().contains("persistence") || p.endsWith("pom.xml"))
      .map(Path::toString).toList();
  assertEquals(List.of(), offenders,
      "持久化适配器早已是 MyBatis/PostgreSQL；仍自称 in-memory 的文件：" + offenders);
}
```

例外需白名单：`payment-infrastructure` 的 in-memory 描述是**准确的**
（`InMemoryPaymentOperations` 确实在内存里），不能一刀切。

## 修复

1. 六处逐一改成 MyBatis/PostgreSQL 的实际描述；`ApplicationSmokeTest` 的 javadoc 同改。
2. README `:141` 改为："…so `OrderController` exposes no `confirm` endpoint"——
   把句子从"枚举有什么"改成"声明没有什么"，后者才是那段真正要说的，且不会随端点增减而失效。
3. 更根本的做法：**package-info 与 pom description 不再复述实现技术**，
   只写包/模块的**职责与边界**。技术选型让类 javadoc 说，它离代码最近。

## 验证结果

已修。

- 六处 in-memory 描述全部改写。改法采用修复第 3 条的原则：**package-info 与 pom description
  不再复述实现技术**，只写职责与边界（技术选型留给最靠近代码的类 javadoc）——这样它们不再有
  漂移的理由。`ApplicationSmokeTest` 的 javadoc 同改。
- README `:141` 由枚举端点改为声明"没有 `confirm`"，这正是那段本来要说的，且不会随端点增减失效。
- 验证：`mvn -o compile` 与 `mvn -o spotless:check` 通过；134 条非 Docker 测试全绿。
  本 issue 无行为变更，故无新增回归测试；防再犯的结构断言（禁止 persistence 包自称 in-memory）
  留待与 [[issue-00089-the-generated-project-links-a-document-it-does-not-have]] 的文档检查合并实现。

## 关联

- [[report-00002-scaffold-ddd-review]]
- [[issue-00072-demo-seed-data-ships-in-a-production-migration]]（同一次内存→PostgreSQL 迁移留下的另一处债）
- [[issue-00089-the-generated-project-links-a-document-it-does-not-have]]（另一类文档缺陷）
