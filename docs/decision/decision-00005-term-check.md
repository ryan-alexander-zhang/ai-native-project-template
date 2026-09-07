---
id: decision-00005-term-check
type: decision
status: active
verified_against: 9d3547ce
motivated_by: [analysis-00001-doc-code-drift]
---

# Decision: 从 CONTEXT.md 生成词表，`scripts/term-check` 在 CI 上拦截被规避的术语

> CONTEXT.md 每个词条的 `_Avoid_` 行就是禁用词表；`scripts/term-check` 扫描 docs/、根规约与源码注释，命中禁用词即报告并指向规范词，默认失败；无 CONTEXT.md 的仓库跳过。不引入 Vale。

## 1. 需要做这个决定的原因

- 「一个概念一个词」（`AGENTS.md` §5）只靠代理自觉；CONTEXT.md 在模板里默认不存在，`REVIEW.md:8` 以「once that file exists」让步（`analysis-00001-doc-code-drift` §3 第 10 条）。
- 本仓库自己的 CONTEXT.md 建立后立刻发现三处已经漂移的用词（GWT ids、COVERED、whiteboard），由人肉 grep 修正（`4ff53716`）。
- 业界做法（Datadog 用 Vale 词表）成熟，但 Vale 是外部二进制、YAML 规则、中文分词弱，与本仓库「只依赖 python3 与 git」的脚本面不一致。

## 2. 决定

| # | 做法 | 理由 |
| --- | --- | --- |
| 1 | `scripts/term-check`：解析 CONTEXT.md（及存在时 CONTEXT-MAP.md 所列各上下文的 CONTEXT.md），每个 `**Term**:` 词条的 `_Avoid_:` 行拆为禁用词列表；对 `docs/**/*.md`、根目录 `*.md`、以及源码文件中的注释行（`#`、`//`、`/* */`、`<!-- -->`、docstring 不做）做整词、大小写不敏感匹配；中文词做子串匹配。命中输出 `TERM <path>:<line> "<hit>" → use "<Term>"`。 | 词表已经在 CONTEXT.md 里，不需要第二份配置。 |
| 2 | 命中默认失败（exit 1）。三类例外：(a) 命中在 CONTEXT.md 自身；(b) 命中行含 `term-check: allow` 注释；(c) 词条的 `_Avoid_` 行中该词以 `?` 结尾（如 `covered?`），表示语境相关，只报告。 | 例外必须可见且局部，与 CODE_QUALITY.md §6 的 Suppress 原则一致。 |
| 3 | 无 CONTEXT.md 时输出 `term-check: skipped (no CONTEXT.md)` 并 exit 0。 | 不逼没有术语表的项目先写一份；REVIEW.md 的「once that file exists」保持真实。 |
| 4 | 接入 `.githooks/pre-commit` 与 `.github/workflows/trace-check.yml`，与 trace-check 并列；脚本与自检加入 `.template-sync.json` frozen。 | 同一依赖面，同一触发点。 |
| 5 | CONTEXT.md 模板（`CONTEXT_TEMPLATE.md`）说明 `_Avoid_` 行的机器可读约定：逗号分隔，`?` 后缀表示语境相关。 | 让下游写词表时知道它会被执行。 |
| 6 | 本仓库的 CONTEXT.md 先按 5 整理，`covered?` 标为语境相关。 | 自己先过自己的门。 |

## 3. 考虑过的其他选项

| 选项 | 结论与理由 |
| --- | --- |
| Vale 加自定义词表 | **否决**。外部二进制与 YAML 规则；中文支持弱；词表要从 CONTEXT.md 再抄一份。 |
| textlint | **否决**。需要 Node 与插件生态；同样是第二份配置。 |
| 只报告不失败 | **否决**。只报告的检查在 CODE_QUALITY.md §2 的定义里「不是 gate」；语境相关词已有 `?` 逃生口。 |
| 扫描源码标识符（变量名、类名） | **否决为首版**。标识符命名受语言约定牵制，误报会很高；注释是散文，先管散文。 |
| 检测「同一个词两种含义」 | **否决**。这是语义问题，linter 做不到；由 CONTEXT.md 的 Flagged ambiguities 人工承担。 |

## 4. 后果

**接受的代价**

- CONTEXT.md 从建议变成强制词表；写得差就是噪音源。首次在已有项目上运行会大量命中，需先清词表再开门禁（可暂以 `term-check: allow` 或不接 hook 过渡）。
- 中文子串匹配会有误报（「白板」出现在「白板配置」里是合法的）；靠 `?` 与 allow 注释处理。
- 多一个脚本要维护。

**得到的**

- 术语漂移第一次有机械信号；本仓库三处漂移那种人肉 grep 不再需要。
- REVIEW.md 的术语条款有了执行者。

**不变的**

- CONTEXT.md 的格式与 CONTEXT_TEMPLATE.md 的规则不变，只是 `_Avoid_` 行多了一个 `?` 约定。
- trace-check 不变；term-check 是独立脚本。

## 5. 这个决定约束什么

- `scripts/term-check`、`scripts/test_term_check.py`。
- `.githooks/pre-commit`、`.github/workflows/trace-check.yml`、`.template-sync.json`。
- `CONTEXT_TEMPLATE.md`：`_Avoid_` 行的机器可读约定。
- `CONTEXT.md`（本仓库）：按约定整理。
- `REVIEW.md:7-8`：引用 term-check。
