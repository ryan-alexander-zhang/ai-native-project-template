---
id: issue-00112-the-bom-republished-every-pin-the-library-builds-against
type: issue
role: main
status: resolved
parent: report-00003-ddd-library-review-2026-07-29
---

# BOM 继承 parent：把「本库恰好用什么版本编译」当成对消费方的承诺发布出去

## 症状（先量出来，再动手）

在 scratchpad 造了一个只 import `aipersimmon-ddd-bom`、别的什么都没有的 probe 工程，
跑 `help:effective-pom`：

| | 修前 | 修后 |
|---|---|---|
| 被管理的坐标数 | **1626** | **72** |
| `org.springframework:spring-core` | 6.2.15 | 不再出现 |
| `org.springframework.boot:spring-boot` | 3.5.10 | 不再出现 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.19.4 | 不再出现 |
| junit / testcontainers / mybatis-plus / shedlock | 全部 | 不再出现 |

原因是 Maven 解析被 import 的 BOM 时用的是**有效模型**——parent 的 `dependencyManagement` 连同它 import 的
`spring-boot-dependencies` 等一并算数。

**比"噪音"严重得多的一点，是它砸中了谁。** 两个 import 的 BOM 之间**先 import 的赢**；
而本库要求消费方把这个 BOM 排在 `spring-boot-dependencies` **之前**（为了 OTel 那条线，
自家脚手架就是这么写的）。于是——**不继承 `spring-boot-starter-parent`、靠 import BOM 管版本**的应用，
自己选的 Boot 版本会被本库**悄悄压掉**，而那恰恰就是本库推荐的装配方式。

实测（同一个 probe，只换 BOM）：

```
消费方 import: [aipersimmon-ddd-bom, spring-boot-dependencies:3.4.4]
  旧 BOM → org.springframework.boot:spring-boot:jar:3.5.10   ← 消费方的选择被压掉
  新 BOM → org.springframework.boot:spring-boot:jar:3.4.4    ← 消费方说了算
```

**一处自我修正**：我最初以为受害者是继承 `spring-boot-starter-parent` 的应用。**实测是反的**——
Maven 里**继承来的 dependencyManagement 优先于 import 的 BOM**，那类消费方从头到尾没受影响。
受害者恰好是本库自己推荐的那种装配。这也解释了为什么一直没人发现：脚手架 pin 的 Boot 版本
与库里的**恰好相同**，覆盖发生了但看不出来。

而且这件事**在文件里完全看不出来**：泄漏藏在一个 `<parent>` 元素里。BOM 自己还特意重声明了
springdoc/OTel，注释写着"以便消费方不继承 parent 也能对齐"——写的人显然以为其余不传播。

## 决定：BOM 不要 parent

BOM 该发布的是**它的拥有者承诺维持对齐的东西**。本库承诺 `com.aipersimmon.ddd:*` 各模块之间的版本一致，
**不对应用跑在哪条 Spring Boot 线上做任何承诺**。

去掉 `<parent>` 后剩 72 条 = 约 48 个 aipersimmon 模块 + 三处**刻意再导出**。

### 三处再导出为什么留下

判据不是"方便"，而是**本库自己的代码在别的版本上就是不工作**：

- `io.opentelemetry:opentelemetry-bom` —— observability-otel starter 的 instrumentation(2.29.0) 就建在这条
  core 线上；Spring Boot 把 `opentelemetry-api` 管到更老的一条(1.49.0)，不覆盖它启动即
  `NoClassDefFoundError: io.opentelemetry.common.ComponentLoader`。**这是能兑现的承诺**，
  与"Jackson 恰好是 2.19.4"性质完全不同。已验证脚手架仍解析到 1.63.0。
- `org.springdoc` / `io.swagger.core.v3` —— 两者 `spring-boot-dependencies` 都不管；且注解 jar 是消费方
  adapter 层以 **`provided`** 作用域编译用的，而 `provided` 不传递——没有这里的管理，消费方只能手写版本号，
  且会与 openapi starter 传递进来的 springdoc 运行时漂移。

## 代价：四个版本号现在写两遍，用测试锁住

没有 parent 就没有可继承的属性，于是 `opentelemetry.version` / `springdoc.version` / `swagger.version` /
以及 BOM 自身的 `<version>` 在 parent 与 BOM 里各写一份。~48 个模块条目仍用 `${project.version}`
（自引用，不构成重复）。

新增 `BomExportsOnlyItsOwnModulesTest`（在 `-archunit` 的测试树里，那里已经是"关于代码库形状的规则"的家；
测试不随包发布）三条断言：

1. BOM **没有** `<parent>`——因为 parent 正是泄漏本身；
2. 被管理的每个 groupId 要么是 `com.aipersimmon.ddd`，要么在**显式白名单** `DELIBERATE_RE_EXPORTS` 里
   （失败信息直接告诉后来者：只有"本库在别的版本上真的不工作"才配加进白名单）；
3. 四个版本字面量与 parent 的属性一致。

用 JDK 自带的 DOM 解析而非正则——顺带没有加重报告第 13 项对 `ModuleNamingChecks` 正则解析 pom 的那条批评。

**负向对照**：往 BOM 里塞回一条 `spring-boot-dependencies` import，第 2 条断言按预期失败并点名该坐标。

## 消费方那边不用改

脚手架 `multi-module` 本来就自己 import 了 `spring-boot-dependencies` 与 `mybatis-plus-bom`
（它同样刻意不继承 `spring-boot-starter-parent`），所以版本一个都不缺，`mvn clean verify` 直接绿。
它把 aipersimmon BOM 排在 Spring Boot BOM **之前**的那条注释依然成立——但现在那个顺序**只**为 OTel 那一条服务，
不再顺带决定 1600 个坐标的归属。这才是它当初想表达的意思。

## 落地

- `aipersimmon-ddd-bom/pom.xml`：删 `<parent>`，自带 `groupId`/`version`；三个第三方版本改字面量并说明为何是字面量；
  三处再导出的注释改写为"本库在别的版本上不工作"这条判据。
- 新增 `aipersimmon-ddd-archunit` 测试 `BomExportsOnlyItsOwnModulesTest`（3 例）。
- `README.md` 快速上手：原示例的 `mybatis-plus-spring-boot3-starter` **没写版本号**——它靠的正是这次堵掉的泄漏。
  改成消费方自己 import `mybatis-plus-bom`（MyBatis-Plus 是消费方选的持久化，本库不替它定版本），
  并在旁边加一段「这个 BOM 不替你选 Spring Boot 版本」的说明。已用一个照抄 README 的 probe 工程验证可解析。
- **无配置项变化，无 Java API 变化。**

库 48 模块全门禁 + 脚手架 `multi-module` 两个 reactor `mvn clean verify` 全绿。

## 关联

- 父：[[report-00003-ddd-library-review-2026-07-29]]（§2 架构层「BOM 继承 parent」那条、§3 第 11 项）
- 同源判断：本库刻意不继承 `spring-boot-starter-parent`（[[design-00001-aipersimmon-ddd-and-scaffold]]），
  这一项是把同一条主张贯彻到**发布物**上——不继承别人的意见，也就不该把自己的意见塞给别人
- 白名单里 OTel 那条的由来：[[design-00005-observability-and-distributed-tracing]]
