---
id: analysis-90001-doc-code-drift
type: analysis
status: active
informs: [decision-90001-trace-ids-and-trace-check]
---

# Analysis: 多轮迭代后 code↔doc 与 doc↔doc 的漂移

> 本模板在多轮迭代后会不会出现代码与文档、文档与文档之间的 GAP；现有机制覆盖到哪里，缺口在哪里，业界哪些做法可迁移。

## 1. Question and Method

- Question: 用本模板的项目迭代多轮后，(1) spec/rule/design 与代码、(2) 文档链条内部（prd→spec→rule→design→plan→record，以及 CONTEXT.md / ARCHITECTURE.md / decision）是否会漂移；现有规则哪些能预防、发现、修复；哪些漂移会静默发生。
- Method: 逐行清点根指南（AGENTS.md、DOCUMENT.md、ACCEPTANCE.md、DEVELOPMENT.md、TESTING.md、AUTOPILOT.md）、`docs/*/README.md` 与 `TEMPLATE.md`、`.githooks/`、`.github/workflows/`、`scripts/`、`whiteboard.config.yaml`、`.template-sync.json`，按「覆盖哪条边 / PREVENT-DETECT-REPAIR / 工具强制还是 prose 约束」分类。另调研两组外部实践：AI 时代 spec-driven 工具（GitHub Spec Kit、AWS Kiro、OpenSpec、Tessl、Cursor rules / CLAUDE.md 生态、Fiberplane Drift、Swimm、Mintlify、DocDrift 类 Action）与传统追溯实践（DO-178C / ISO 26262 的双向追溯与 suspect link、Doorstop、StrictDoc、OpenFastTrace、doctest / rustdoc / mdBook test、ArchUnit / dependency-cruiser、ADR 生命周期、g3doc freshness、GitLab handbook、Vale）。

## 2. Findings

现有机制：

- 与漂移相关的机制共 19 项，由工具强制的只有 3 项，且都不涉及 code↔doc：`.githooks/pre-commit:10-20` 拒绝提交 `status: draft` 的 md（opt-in，`--no-verify` 可绕过，见 `COMMIT.md:32-37`）；`.githooks/pre-commit:34-49` 与 `.github/workflows/frozen-docs.yml:10-42` 保护冻结骨架（CI 只在 `lang/**` 分支触发）；`scripts/sync-docs.sh:34` 手动合并骨架。
- 所有真正的门禁都在仓库外：ID 唯一、关系字段合法、resolved gate、Open Questions gate、`supersedes` 检查，全部由 persimmon 白板执行（`whiteboard.config.yaml:1-10`、`README.md:74-82`），只在人打开白板时触发。下游由模板创建的项目没有任何文档 CI。
- 代码不带任何需求 id。没有规则要求测试或源码引用 `spec-<n>-FR-<i>` / `AC-<i>.<k>`；record 的 Test 列是自由文本（`docs/record/README.md:37-40`、`TEMPLATE.md:42-45`）。GWT→test 这条边单向、手写、不可校验。
- 反向边「靠读或靠脚本推导」（`docs/README.md:46`），但 `scripts/` 里不存在这样的脚本。
- 没有「代码变了 ⇒ 哪些文档必须变」的规则，只有 `DEVELOPMENT.md:63,97-98` 靠判断的一行和 issue 模板 §8 Doc verdict（仅 bug 场景，`docs/issue/TEMPLATE.md:65-74`）。
- 没有过期状态：`archived` 要求先有替代文档（`docs/README.md:23,26`）；无 last-verified 字段、无复审周期、无「仍 active 但已不真」的状态。
- resolved gate 只在 `open→resolved` 触发一次（`docs/plan/README.md:23-26`）；record 一旦 active 即不可变（`docs/record/README.md:60-65`）。spec 走修订轮后，没有规则重开 plan 或让 record 失效。
- pre-resolved 核验是「从文档核验」（`AGENTS.md:102`），不运行测试套件。
- `ARCHITECTURE.md` 只有初始填写（`AGENTS.md:89-94`）和一句 prose（`ARCHITECTURE_TEMPLATE.md:8`）约束；本仓库自己的 `ARCHITECTURE.md` 仍是通用占位符。
- `whiteboard.config.yaml:66-102` 手工复制了各 README 的 Relations 段（`:68-69` 注释自认），仓库内无比对。
- `CONTEXT.md` 默认不存在（`REVIEW.md:8` 以「once that file exists」让步），无术语与文档/代码的比对。

外部实践：

- AI 时代工具普遍承认漂移，几乎无 PREVENT，多为事后 DETECT 且需人记得跑。Spec Kit `/speckit.analyze` 只在实现前跨文件检查，`/implement` 曾不加载 constitution.md 导致检查出的违规被下一轮重新引入（spec-kit issue #2459）。OpenSpec 以「变更提案 + archive 合并」维护规范，并发归档会静默覆盖（OpenSpec issue #1387）；社区主流抱怨即 spec drift。Kiro 用 agent hooks 在保存时让代理更新文档，opt-in，不配则同样漂。对 118 个开源仓库的扫描显示 59% 的 CLAUDE.md/AGENTS.md 含死引用。
- 机械检测两种形态：锚定（Fiberplane Drift 用 tree-sitter 规范化 AST 哈希写入 lock，`drift check` 不一致即 exit 1；Tessl 的 link/ownership 脚本；Swimm smart tokens）与 LLM 判官（DocDrift / driftcheck / Mintlify Workflows / ClaudeDrift：从 diff 提取变更符号，检索相关文档，模型判定并在 PR 评论）。修复几乎总是人审 PR，无人做自动权威合并。
- 传统追溯：DO-178C / ISO 26262 要求双向追溯；Doorstop 在上游条目变更时自动把子项标 suspect，`doorstop clear` 显式解除；StrictDoc / OpenFastTrace 每次构建输出 COVERED / UNCOVERED / ORPHANED 并在有 gap 时失败构建。已知失败模式是批量 rubber-stamp clear。
- 可执行文档：doctest / rustdoc / mdBook test / Elixir doctest 让文档示例即测试，是清单里唯一「文档与测试同一工件」的模式；已知失败模式是写平凡示例应付检查。
- 架构：ArchUnit / dependency-cruiser / arch-go 把边界写成每次构建都跑的断言，文档不再承担可说谎的事实。ADR 的失败模式被明确记录：superseded 状态依赖有人记得改，实践中没人改。
- 新鲜度：g3doc `freshness: {owner, reviewed}` 头超期降权；GitLab handbook 每月跑链接检查 + Vale + CODEOWNERS；Stripe 在构建时校验链接直接失败。术语一致性：Datadog 用 Vale 自定义词表在 CI 上强制。

## 3. Gaps and Comparison

按可能性 × 损害降序：

1. spec 修订后 plan 已 resolved、record 不可变，漂移永久化且看起来已验证。
2. 代码行为变更无文档触发，代码不带 id 故无机械信号。
3. record 引用的测试改名或删除，证据指向空。
4. `ARCHITECTURE.md` 无变更触发，出厂即占位符。
5. 双向孤儿不可见：从未实现的 active FR，与无 FR 的已实现行为。
6. `whiteboard.config.yaml` 与各 README 的 Relations 双写。
7. decision 的 `constrains` 回填只有 prose。
8. 下游项目零文档 CI。
9. plan 以整个 doc id 声明范围，后加的 FR 自动入范围但 gate 已放行。
10. `CONTEXT.md` 默认不存在，术语无比对。

## 4. Conclusion

两类漂移都会出现，且现状下几乎全部静默。业界可迁移的做法按杠杆排序：让 GWT 与测试成为同一条可校验的边（代码携带 AC id）；把反向边与覆盖率做成仓库内脚本进 CI（消除对外部白板的依赖，下游开箱即有）；上游修订时沿反向边打 suspect 并重关 resolved gate；代码变更触发文档复核（机械锚 + LLM 判官）；架构断言可执行；living docs 加 `verified_against` 与 `stale` 状态；用 Vale 类 linter 强制 CONTEXT.md 术语；消灭 Relations 双写。前两项是一条规则改动加一个脚本，能把缺口 1、2、3、5、6、8 从不可见变成 CI 红灯，应先做；决定见 `decision-90001-trace-ids-and-trace-check`。
