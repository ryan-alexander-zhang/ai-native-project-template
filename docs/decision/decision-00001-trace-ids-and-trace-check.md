---
id: decision-00001-trace-ids-and-trace-check
type: decision
status: active
motivated_by: [analysis-00001-doc-code-drift]
---

# Decision: 测试携带 AC id，仓库内 trace-check 脚本进 CI

> 每条验收 GWT 由一个带 AC id 标记的测试实现，record 的 Test 列引用该标记；仓库自带 `scripts/trace-check` 校验 id、关系、覆盖率与 record 证据，并在 CI 上作为门禁。取代「GWT→测试靠 record 自由文本、门禁靠外部白板」的现状。

## 1. 需要做这个决定的原因

- 代码不携带任何需求 id：没有规则要求测试或源码引用 `spec-<n>-FR-<i>` / `AC-<i>.<k>`，record 的 Test 列是自由文本（`docs/record/README.md:37-40`）。GWT→测试这条边单向、手写、不可校验（`analysis-00001-doc-code-drift` §2）。
- 反向边「靠读或靠脚本推导」（`docs/README.md:46`），仓库内不存在该脚本；ID 唯一、关系合法、resolved gate 全在外部白板执行，下游项目出厂零文档 CI。
- 由此缺口 1、2、3、5、6、8（`analysis-00001-doc-code-drift` §3）全部静默：spec 修订后已验证的 record 仍「看起来通过」，测试改名后证据指向空，孤儿 FR 与孤儿行为均不可见。

## 2. 决定

| # | 做法 | 理由 |
| --- | --- | --- |
| 1 | 每条在 plan 交付范围内的 AC 至少有一个测试携带该 AC 的完整 id。载体统一为测试名后缀：测试名以 `__<id>` 结尾，id 中的 `-` 与 `.` 写作 `_`（如 `test_duplicate_webhook_is_noop__spec_00001_AC_5_1`）；多个 id 依次追加。一个 id 可由多个测试携带。各语言 `*_TESTING.md` 只写明该语言的测试名规则如何容纳这一后缀，不得改用其他载体。 | 让 GWT 与测试成为同一条可被 grep 的边。doctest 模式是业界唯一「文档与测试同一工件」的做法。选测试名而非标注，是因为它在所有语言、所有测试框架和测试运行器的输出里都可见，trace-check 只需 grep，不必解析各语言的标注语法。 |
| 2 | record 验收清单的 Test 列写测试的稳定标识（携带 id 的测试名或 `路径::测试名`），不再写自由描述。`docs/record/README.md` 与 `TEMPLATE.md` 同步改。 | 让 trace-check 能核对「record 引用的测试存在且携带该 id」。 |
| 3 | 新增 `scripts/trace-check`，仅依赖 git、grep、awk 或单一脚本语言标准库，输出 CARRIED / UNCOVERED / ORPHANED 三类报告并在任一失败时非零退出。检查项：(a) 文档 id 全仓唯一、形如 `<type>-<五位数>-<slug>`，type 与目录和 id 前缀一致，status 在该类型词表内；(b) 关系字段只出现在该类型允许的字段上（矩阵读自 `whiteboard.config.yaml` 的 `carries:`，缺失则跳过并提示），所列 id 均指向存在的文档或条目，条目 id 只允许出现在 plan 的 `implements` 与 record 的 `verifies`；(c) 每个 `active` spec/rule 的每个 FR/BR 至少一条 AC；(d) 每个 `open`/`resolved` plan 交付范围内的每条 AC 至少一个携带该 id 的测试——`resolved` 缺失即失败，`open` 只报告（UNCOVERED），实现进行中不该红灯；(e) 每个 `active` record 的每一行，其首列 id 存在，Test 列所指测试携带该 id 且在某个受跟踪文件中出现；(f) 代码中携带的 AC 后缀必须指向存在的 AC——不存在即失败（ORPHANED），存在但不在任何 plan 的范围内只报告；(g) `resolved` plan 范围内每条 AC 在某个 `parent` 指向该 plan 的 `active` record 里有 pass 行——即白板的 resolved gate；(h) record 行 Evidence 列写 `ac:<hash>`（`scripts/trace-check --hash <ac-id>` 给出，取 FR 声明行加 AC 的 GWT 块归一化后的 sha1 前 8 位），AC 或其 FR 行之后改动即 SUSPECT 失败，未写 hash 的行只提示——这是 Doorstop 式 suspect link 的最小形态；(i) 关系矩阵以各 `docs/<type>/README.md` 的 Relations 段为源解析，`whiteboard.config.yaml` 的 `carries:` 与之不一致即失败，消灭双写。 | 把白板的检查搬进仓库，下游开箱即有；StrictDoc / OpenFastTrace 模式已验证「构建即出覆盖矩阵、有 gap 即失败」可行。只用标准工具是为了让任何语言分支都能直接运行。 |
| 4 | `.github/workflows/trace-check.yml` 在所有分支的 push / pull_request 上运行 `scripts/trace-check`；`.githooks/pre-commit` 在有 `docs/**/*.md` 或测试文件被 staged 时也运行它。 | 现有 `frozen-docs.yml` 只在 `lang/**` 触发，pre-commit 只查 draft；门禁必须默认开、在主路径上。 |
| 5 | `AGENTS.md` 的 pre-resolved 核验改为：先运行 `scripts/trace-check` 且通过，再由子代理核验；`docs/plan/README.md` 的 resolved gate 描述同步引用该脚本。 | 「从文档核验」（`AGENTS.md:102`）不运行测试也不查 id，替换为脚本 + 子代理双重核验。 |
| 6 | `scripts/trace-check`、`.github/workflows/trace-check.yml` 加入 `.template-sync.json` 的 `frozen`。 | 它们是文档系统骨架的一部分，须在所有 lang 分支保持一致。 |

## 3. 考虑过的其他选项

| 选项 | 结论与理由 |
| --- | --- |
| 继续依赖外部白板做全部门禁 | **否决**。只在人打开白板时触发，下游项目拿不到；且白板不读代码，无法核对测试存在与否。 |
| 只用 LLM 判官在 PR 上判断文档是否仍一致（DocDrift / Mintlify 模式） | **否决为唯一手段**。非确定、无锚点、成本随仓库增长；可作为后续叠加的第二道，但不能替代可 grep 的边。 |
| 用测试标注（`@ac("...")` / `#[ac = "..."]`）而非测试名携带 id | **否决**。每种语言与框架的标注语法不同，trace-check 需逐语言解析；标注也不出现在测试运行器输出里，record 的 Test 列无法直接对上。 |
| 用 Fiberplane Drift 式 AST 哈希把文档锚到代码 | **否决为本决定范围**。适合 design 文档引用具体模块，不解决 GWT→测试覆盖；语言支持有限。留作后续。 |
| 把 GWT 写成 Gherkin 由 Cucumber 执行 | **否决**。要求所有语言分支引入 BDD 框架；业界报告的 step-definition 漂移与场景膨胀是新的维护税。id 标注达到同样的可校验性且零依赖。 |
| 在 record 里写测试路径与行号 | **否决**。行号一改就失效，比自由文本更脆。稳定标识是 id，不是位置。 |

## 4. 后果

**接受的代价**

- 每条 AC 的测试名多一个后缀，长测试名可读性下降；各语言 `*_TESTING.md` 要写明后缀如何嵌入该语言的命名规则。
- 需要写并维护一个脚本；语言分支若有特殊测试布局，需在 `*_TESTING.md` 声明 trace-check 的测试文件 glob。
- 已存在的下游项目要补 id，首轮 trace-check 会大面积 UNCOVERED；允许以 plan 为单位分批补齐，期间把该 plan 的 id 写入仓库根的 `.trace-check-allow`（每行一个），脚本对这些 plan 只报告 UNCOVERED 不失败，并在输出里点名白名单。

**得到的**

- 缺口 1、2、3、5、6、8 从静默变成 CI 红灯：spec 修订新增 AC 立刻 UNCOVERED；测试改名立刻让 record 行失败；孤儿 FR 与孤儿标注双向可见。
- 下游项目不依赖白板即有 id、关系、覆盖率检查。
- 后续 suspect link 传播有了可用的反向边数据来源。

**不变的**

- 白板继续做它现有的解析诊断与状态流转；本决定不替换白板，只把可脚本化的检查下沉到仓库。
- record 不可变的规则不变；trace-check 对 `active` record 只做核对，不修改。
- spec/rule 的 GWT 文法（`docs/spec/README.md:25-39`、`docs/rule/README.md:60-73`）不变。
- `stale` 状态、代码变更触发的文档复核、架构断言、术语 linter 不在本决定内，留待后续 decision。suspect 传播只做到 (h) 的 hash 形态，不做跨文档级联。

## 5. 这个决定约束什么

- `docs/record/README.md` 验收清单一节与 `docs/record/TEMPLATE.md`：Test 列改为稳定测试标识。
- `docs/plan/README.md` resolved gate 一段：引用 `scripts/trace-check`。
- `AGENTS.md` §8 pre-resolved 核验条目、`TESTING.md` 与各 `*_TESTING.md`：AC id 测试名后缀规则与 trace-check 的测试 glob。
- `scripts/trace-check`、`.github/workflows/trace-check.yml`、`.githooks/pre-commit`、`.template-sync.json`。
- 后续任何引入新 id 命名空间或新关系字段的 spec/rule/design，须同时扩展 trace-check 并回填到本决定的 `constrains`。
