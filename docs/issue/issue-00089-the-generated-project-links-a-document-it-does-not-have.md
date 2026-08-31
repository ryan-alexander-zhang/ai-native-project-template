---
id: issue-00089-the-generated-project-links-a-document-it-does-not-have
type: issue
status: resolved
blocks: [report-00002-scaffold-ddd-review]
---

# 生成出来的项目引用了两篇它不拥有的文档

## 问题（现状，file:line 为证）

- **等级：Low（只影响从 archetype 生成项目的人——也就是这个 scaffold 的主要目标读者）**。
- scaffold 里两处引用 `CHOOSING-MODULES.md`：
  - `README.md:128` —— "`CHOOSING-MODULES.md` presents it as an equal path; only this one has a worked example."
  - `TestInfrastructure.java:20` —— "which is what `CHOOSING-MODULES.md` points a consumer at"
- 另有一处引用 `CONFIGURATION.md`：
  - `application.yml:101-102` —— "Two settings here are the ones CONFIGURATION.md's production checklist asks to be decided rather than inherited"
- 这两个文件都在 **`aipersimmon-ddd/`** 下（`aipersimmon-ddd/CHOOSING-MODULES.md`、
  `aipersimmon-ddd/CONFIGURATION.md`），**不在 `aipersimmon-ddd-scaffold/multi-module/` 里**。
- `archetype.properties` 的打包范围是 `multi-module` 目录本身
  （`excludePatterns` 只排除 `.idea`/`*.iml`/`target`/`.DS_Store`/`.data`）——
  也就是说 archetype 生成的项目**只包含 multi-module 树**。
  在生成出来的项目里，这三处引用指向不存在的文件，且没有任何线索说明去哪里找。
- 引用形式还都是**裸文件名**，不是 URL 也不是相对路径，所以连"去上游仓库找"都不明显。
- 同类问题的一个变体：这两处引用的内容本身是有价值的
  （模块选型、生产配置检查清单），恰恰是新项目最需要的两篇。

## 根因（第一性）

1. **观察 vs 期望**：期望"scaffold 里的每一处文档引用，在它被复制之后仍然可解析"；
   实际"有三处只在**开发 scaffold 的仓库里**可解析"。
2. **最小机制**：`multi-module/` 在开发时是单仓库的一个子目录，
   同仓库里的 `aipersimmon-ddd/CHOOSING-MODULES.md` 触手可及；
   经 archetype 分发后它变成一个独立仓库的根，兄弟目录消失。
   **写作时的上下文与消费时的上下文不同**，而裸文件名引用在两种上下文里看起来完全一样。
3. **真根因**：scaffold 有两个身份（"仓库里的一个样例" / "被生成出去的模板"），
   文档写作时默认了第一个。这与
   [issue-00078-six-places-still-describe-the-repositories-as-in-memory](issue-00078-six-places-still-describe-the-repositories-as-in-memory.md) 属于同族——
   都是**文档的假设没有随载体变化而更新**——但这一处的假设是关于**分发边界**的，
   本地怎么读都不会暴露。
4. **排除的伪根因**：不是应该把两篇文档复制进 scaffold——
   复制会立刻产生两份会分叉的副本（正是 scaffold 一直在避免的事，
   参见 `TestInfrastructure.java:21-23` 关于镜像版本"there is no version number to maintain here at all"的论证）。

## 复现（test-first）

```java
@Test
void everyDocumentTheScaffoldReferencesIsResolvableFromTheGeneratedProject() throws IOException {
  Pattern docRef = Pattern.compile("\\b([A-Z][A-Z-]+\\.md)\\b");
  List<String> dangling = new ArrayList<>();
  for (Path src : filesUnder(REACTOR_ROOT)) {                 // 只走 multi-module 树
    Matcher m = docRef.matcher(Files.readString(src));
    while (m.find()) {
      if (!Files.exists(REACTOR_ROOT.resolve(m.group(1)))) {
        dangling.add(src + " → " + m.group(1));
      }
    }
  }
  assertEquals(List.of(), dangling,
      "生成出来的项目里这些引用无法解析：" + dangling);
}
```

当前会报三条。这条测试放在 `start` 模块（与 `PackageInfoTest` 同类，
它已经在从 reactor root 走文件树，`PackageInfoTest.java:44-53`）。

## 修复

把裸文件名换成**在任何上下文都能解析**的形式，三选一：

1. **稳定 URL**（最简单）：改成指向上游仓库的链接，例如
   `https://github.com/<org>/<repo>/blob/main/aipersimmon-ddd/CHOOSING-MODULES.md`。
2. **在 scaffold 里放一篇薄的 `DOCS.md`**：只列"上游文档在哪、各讲什么"，
   由它承担全部外链。这样引用只有一处需要维护，且生成出来的项目里
   有一个明确的"去哪儿找更多"的入口。
3. **就地写清楚**：`application.yml:101-102` 那处引用的实际内容
   （outbox 租约算术、cleanup 取舍）**已经在注释里完整写出来了**，
   引用只是溯源——这种情况改成"（依据见上游 CONFIGURATION.md 的生产检查清单）"即可，
   读者不打开也不影响理解。

推荐 2 + 3 组合：新项目拿到一个入口，而单点引用不再制造悬空链接。

## 验证结果

已修。采用修复方案 2 + 3 的组合。

- 新增 `multi-module/DOCS.md`：唯一知道"上游文档在哪"的地方，列出
  `CHOOSING-MODULES.md` / `CONFIGURATION.md` / `ARCHITECTURE.md` 各自回答什么问题，
  并给出采纳时应替换成自己 fork URL 的模板。单点引用，不再制造多处悬空链接。
- 三处裸文件名引用改为指向 `DOCS.md`：`README.md`（模块选型那行）、
  `TestInfrastructure` 的 javadoc、`application.yml` 的 outbox 注释。
  其中 `application.yml` 那处按方案 3 处理——它引用的内容本来就已经完整写在注释里，
  引用只是溯源，改成"（见 DOCS.md）"即可，读者不打开也不影响理解。
- 验证：`mvn -o compile`、`spotless:check` 通过。
- 未做：复现一节那条"扫描全树、校验 .md 引用可解析"的断言。

## 关联

- [report-00002-scaffold-ddd-review](../report/report-00002-scaffold-ddd-review.md)
- [issue-00078-six-places-still-describe-the-repositories-as-in-memory](issue-00078-six-places-still-describe-the-repositories-as-in-memory.md)（同族：文档假设未随变化更新）
- [design-00001-aipersimmon-ddd-and-scaffold](../design/design-00001-aipersimmon-ddd-and-scaffold.md)（scaffold 与库的分发关系）
