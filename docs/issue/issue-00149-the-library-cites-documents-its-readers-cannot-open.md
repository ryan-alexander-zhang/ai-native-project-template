---
id: issue-00149-the-library-cites-documents-its-readers-cannot-open
type: issue
role: main
status: resolved
---

# 库的 javadoc 引用了 28 处读者打不开的内部文档 ID

2026-07-30 全面评审（P2）。

## 问题

`aipersimmon-ddd/*/src/main` 下约 28 处 javadoc 引用 `issue-000xx`/`design-000xx`/
`decision-000xx`（如 `AbstractAggregateRoot.java:21` 的 "See design-00011"、
`RegistryCommandBus.java:68`、`IdGenerator`、`DomainEvents`）。这些 ID 只在本仓库的 docs/
里可解析，对库的外部消费者（以及发布后的 javadoc jar）是死引用。

## 根因（第一性）

项目自己有明文规则："no docs/ ID references inside the aipersimmon-ddd/ library tree"
（库内注释自包含）。规则存在但没有守卫，新增注释时又渗回来了。

## 复现（先写失败测试）

一条构建期检查（或 `PackageInfoTest` 式测试）：grep `aipersimmon-ddd/*/src/main` 中的
`(issue|design|decision|analysis|plan)-\d{5}` 断言为零。当前 ~28 处命中。

## 改法

把结论内联——多数注释其实已经复述了结论，直接删 ID 即可；个别只靠 ID 承载论证的，把论证
搬进注释或 package-info。然后让上面的检查常驻，规则从自觉变成机制。

顺带（同为文档质量）：部分类注释长达 80+ 行，IDE 悬浮阅读体验下降——"历史论证"段落可
移往 package-info。

## 验证结果

2026-07-31 修复。门禁先落（红），清理后转绿。**范围比 issue 写的宽**：清理途中发现引注
不止在 javadoc——SQL 迁移注释（随 jar 发布）和各模块 pom（连注释一起发布到 Maven 仓库）
里还有 44 处，一并纳入。

- **命中已从评审时的 ~28 涨到 .java 50 处**（45 个文件）+ SQL 25 处 + pom 34 处 + 一个
  `sed -i.bak` 留在 main 树里的 `.bak` 备份（git 忽略、肉眼看不见，门禁抓出来的）。.java
  涨出来的正是评审修复期间新写的类（`CommandContexts`、`CommandPrecheck`、
  `PrecheckCommandInterceptor`、`EventUpcaster`、`EventUpcasterChain`、
  `aipersimmon-ddd-test` 全家……）——知道规则的作者在两周内又渗了二十多处，"规则存在但
  没有守卫"的根因不证自明。
- **门禁**：`LibraryCommentsAreSelfContainedTest`（aipersimmon-ddd-archunit 测试树，与
  `BomExportsOnlyItsOwnModulesTest` 同一 reactor 级门禁模式，`reactorRoot()` 向上爬定位）：
  扫**一切随发布出门的东西**——全 reactor `*/src/main/**`（任意文件类型，坏字节按替换
  字符解码故二进制藏不住 ID）+ 根与各模块 `pom.xml`——断言
  `(issue|design|decision|analysis|plan)-\d{5}` 零命中，失败消息逐条列
  `文件:行 cites ID` 并告诉修法（内联结论，别引票号）。测试源与 scaffold 豁免
  （不发布 / 与 docs 同仓）。落地当刻红：.java 50 条 → 扩范围后再红 61 条，逐一点名。
- **清理**：全部 105 处（.java 50 + SQL 25 + pom 34，按正则命中计）+ `.bak` 删除。绝大多数
  注释本就把结论写在 ID 旁边，删引注即可；需要真改写的少数：
  `ModuleNamingChecks` 的**运行期失败消息**也引了 design-00012（构建失败时读者同样打不开）、
  `ProcessClaimSql` 用 issue-00125 当叙事时间标记（改为直接叙述"两种拼写都被证明是承重的"）、
  两处 "for the reason given in design-00011 §3" 改为把原因（MyBatis-Plus 只认一个
  interceptor bean，竞争注册静默让位）说在原地、operation-log classifier 的
  "repository exception model (design-00003)" 改为 "the library's exception model"。
- **SQL 迁移注释改动会改变 Flyway checksum**：对任何已跑过这些迁移的持久库意味着
  checksum mismatch。在案：pre-production、无兼容要求（本轮评审的前提条款），库与
  scaffold 的测试库均为一次性容器/内存库，不受影响。
- 顺带条款（80+ 行类注释迁 package-info）未动：本次改动以"删引注、内联结论"为界，
  注释瘦身与 issue-00150 伞单的文档质量项一并考虑。

验证：门禁修复前红（50 条命中）修复后绿；库全 reactor `clean install` BUILD SUCCESS。
