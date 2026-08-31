---
id: issue-00080-problem-title-key-has-no-message-bundle
type: issue
status: resolved
blocks: [report-00002-scaffold-ddd-review]
---

# RFC 9457 的 title 是 message key，但 scaffold 没有任何 messages 资源包

## 问题（现状，file:line 为证）

- **等级：Low（错误契约的 i18n 一半是断的，且这个缺口连一条断言都没有覆盖）**。
- `ProblemDescriptor` 的第三个参数是**消息源的 key**，不是字面标题——库侧写得很清楚：
  - `ProblemDescriptor.java:21` —— "`@param titleKey` message-source key for the `title`"；
  - `ProblemDescriptor.java:24` —— `public record ProblemDescriptor(String typeUri, int status, String titleKey)`。
- scaffold 传的正是一个 key（`OrderingProblemCatalog.java:26-29`）：

```java
OrderingErrorCode.CREDIT_EXCEEDED,
new ProblemDescriptor("/problems/insufficient-credit", 422, "ordering.insufficient-credit.title")
```

- 但整个 scaffold **没有任何 `messages*.properties`**
  （`find aipersimmon-ddd-scaffold -name 'messages*'` 无命中；
  唯二的 `.properties` 是 `archetype.properties` 与测试用 `application.properties`）。
- 于是这个 key 无处可解析，客户端在 `problem+json` 的 `title` 字段里拿到的
  要么是原始 key 字符串，要么是库的兜底值——**总之不是一句人类可读的标题**。
- 缺口没有被任何测试覆盖：`ExceptionContractTest` 断言了
  `$.status`、`$.type`、`$.code`（`:83-89`、`:106-111`、`:127-129` 等），
  **一次都没有断言 `$.title`**。
- 这不只是 CREDIT_EXCEEDED 一处：所有走 `ErrorCategory` 家族兜底的错误码
  （`OrderingErrorCode` 里 17 个中的 16 个）也都要通过消息源渲染各自的家族标题。

## 根因（第一性）

1. **观察 vs 期望**：期望"RFC 9457 的 `title` 是给人看的一句话"；
   实际"它是一个没人解析得了的标识符"。
2. **最小机制**：`titleKey` → `MessageSource` → `messages.properties`。
   链条的最后一环不存在，而 `MessageSource` 在 key 缺失时的行为是"降级"，不是"报错"——
   所以启动不失败、请求不失败、日志不告警。
3. **真根因**：错误契约被**分成两半交付**——
   机器可读的一半（`type` + `code` + `status`）在领域侧定义、被测试完整覆盖；
   人可读的一半（`title`）在表现侧定义，而 scaffold 把它当成"库自己会处理的事"。
   参数名叫 `titleKey` 而不是 `title` 是唯一的提示，且只在库的 javadoc 里说明。
4. **排除的伪根因**：不是库设计有问题——key 而非字面量正是为了 i18n，是对的。
   也不是 `OrderingProblemCatalog` 用错了 API——它传的确实是一个格式良好的 key。
   缺的只是 key 的另一端。

## 复现（test-first）

在 `ExceptionContractTest` 里给已有用例补一条断言：

```java
mvc.perform(post("/orders").header("X-Tenant-Id", TENANT)
        .contentType(APPLICATION_JSON).content(creditExceedingBody))
   .andExpect(jsonPath("$.status").value(422))
   .andExpect(jsonPath("$.code").value("ordering.credit-exceeded"))
   // 新增：title 必须是人类可读的一句话，不能是那个 key 本身
   .andExpect(jsonPath("$.title").value("Insufficient credit"));
```

当前会红（拿到的是 `ordering.insufficient-credit.title` 或兜底值）。
建议同时补一条家族兜底的：`duplicate-sku` 走 `DOMAIN_RULE` 家族，它的 `title` 也应可读。

## 修复

1. 新增 `start/src/main/resources/messages.properties`：

```properties
ordering.insufficient-credit.title=Insufficient credit
```

   以及各 `ErrorCategory` 家族标题（具体 key 名以库侧 `ProblemCatalog` 默认族为准，实施时对齐）。
2. 若要真正演示 i18n，再加一份 `messages_zh_CN.properties`，
   并在 `ExceptionContractTest` 里加一条带 `Accept-Language: zh-CN` 的用例——
   这会顺带补上评审列出的"i18n 未演示"缺口，成本很低。
3. 在 `OrderingProblemCatalog` 的类 javadoc 里点明：第三个参数是 **key**，
   对应的文案在 `messages.properties`。目前那段 javadoc（`:9-20`）
   把覆盖策略讲得很透，唯独没提这一点。

## 验证结果

已修，修复一节的三条全做了。

**1. `messages.properties`（`start/src/main/resources/`）**：**九个 key，不是一个**。
原稿只举了 `ordering.insufficient-credit.title` 一例，但真正缺的大头是**家族兜底标题**——
`OrderingErrorCode` 里 17 个码只有 1 个有 override，其余 16 个全部渲染家族标题。
对着库侧 `DefaultProblemFamilies` 抄全了 8 个：`domain-rule-violation` / `resource-not-found` /
`resource-conflict` / `validation-failed` / `unauthorized` / `forbidden` / `internal-error`，
加上那一条 override。`unauthorized` / `forbidden` 目前不可达（无安全域），
但资源包是它们该在的地方，先备着不算超范围。

**2. `messages_zh_CN.properties`（i18n 真的演示了）**，并在 `ExceptionContractTest` 里加了一条
带 `Accept-Language: zh-CN` 的用例。这条顺带补上评审列的"i18n 未演示"缺口，成本确实很低。

**3. `OrderingProblemCatalog` 的类 javadoc** 点明第三个参数是 key，
并且**写清了缺文件时的失败形态**——`ProblemTitleResolver` 兜底返回 key 而不是抛异常，
所以启动不失败、请求不失败、日志不告警，只有客户端作者会发现。
这个"静默降级"才是本 issue 能存活到评审才被发现的原因。

**测试三条（原有 `ExceptionContractTest`，不新建上下文）**：
override 的 title、家族兜底的 title、zh-CN 的 title。

**负向对照（实测）**：把两个 `.properties` 移走（并清掉 `start/target/classes` 下的副本，
否则 Flyway/classpath 会从陈旧副本命中，假绿），三条全红，拿到的正是裸 key：

```
creditExceededRendersProblemWith422AndCode:93
  JSON path "$.title" expected:<Insufficient credit> but was:<ordering.insufficient-credit.title>
duplicateSkuViolatesAggregateRuleWith422AndCode:140
  JSON path "$.title" expected:<Business rule violated> but was:<problem.domain-rule-violation.title>
theProblemTitleIsRenderedInTheRequestedLanguage:115
  JSON path "$.title" expected:<信用额度不足> but was:<ordering.insufficient-credit.title>
```

`mvn -o test -pl start -am -Dtest=ExceptionContractTest` 12 条全绿。

## 关联

- [report-00002-scaffold-ddd-review](../report/report-00002-scaffold-ddd-review.md)
- [design-00003-exception-model](../design/design-00003-exception-model.md)
- [decision-00010-exception-model](../decision/decision-00010-exception-model.md)
