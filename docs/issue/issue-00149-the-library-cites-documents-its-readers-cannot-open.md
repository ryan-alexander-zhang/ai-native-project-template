---
id: issue-00149-the-library-cites-documents-its-readers-cannot-open
type: issue
role: main
status: open
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

未修复。
