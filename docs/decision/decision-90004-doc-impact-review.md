---
id: decision-90004-doc-impact-review
type: decision
status: active
verified_against: 9d3547ce
motivated_by: [analysis-90001-doc-code-drift]
---

# Decision: PR 上由 diff 反查受影响的 AC，doc-agent 逐条判定是否仍成立

> `scripts/trace-check --impact <base>..<head>` 从 diff 触到的文件反查携带测试、AC、所属 spec/rule 与引用该路径的 design，输出 IMPACT 清单；PR 阶段 doc-agent 对清单中每条 AC 读新代码与 GWT，给出「仍成立 / 不再成立 / 需修订」并贴为 PR 评论。「不再成立」阻塞合并。

## 1. 需要做这个决定的原因

- 行为改了、测试没动、spec 没动、id 全在、hash 全对，现有全部检查都绿（`analysis-90001-doc-code-drift` §3 第 2 条）。这是 decision-90001 / 00003 明确留下的洞。
- 业界唯一能触及这一层的手段是 LLM 判官（DocDrift、Mintlify Workflows、Kiro hooks），且全部停在「建议、不做硬门禁」。
- 现有 REVIEW.md 是空清单，PR 复审没有任何与 spec 相关的检查项。

## 2. 决定

| # | 做法 | 理由 |
| --- | --- | --- |
| 1 | `scripts/trace-check --impact <base>..<head>`：对 diff 触到的每个非 md 文件，(a) 找出该文件内携带 AC 后缀的测试名，反查 AC 及其 spec/rule 与条目；(b) 找出 `anchor:` 指向该路径的 design 小节；(c) 若该文件被任何携带测试直接 import / require（按语言的 import 行正则，只做同仓库相对路径），把那些测试也计入。输出 IMPACT 块；无命中时输出 `IMPACT none: no carried test touches this diff`。 | 机械部分越多，判官读的越少。三层反查全部是 grep，不需要语言解析。 |
| 2 | `.github/workflows/trace-check.yml` 在 pull_request 事件下追加一步，运行 `--impact` 并把输出写入 job summary。 | 让清单在 PR 页面可见，不依赖任何模型。 |
| 3 | `REVIEW.md` 增加一节「Spec impact」：复审者对 IMPACT 中每条 AC 读其 GWT 与新代码，给出三值之一并写明理由；`不再成立` 阻塞合并，`需修订` 要求先走修订轮再合并，`仍成立` 放行。`IMPACT none` 时复审者写一句确认「本 diff 不在任何验收之内」。 | 判定是判断题，属于 REVIEW.md 而非脚本；三值让结论可执行。 |
| 4 | `AUTOPILOT.md` 验收阶段的整轮 diff 复审引用同一节；`pr` 阶段把 IMPACT 判定写进 PR body。 | 不新增阶段。 |
| 5 | 判定只读 IMPACT 命中的 AC 与其条目，不读整份 spec。 | 控制成本；整份阅读是审计的事。 |

## 3. 考虑过的其他选项

| 选项 | 结论与理由 |
| --- | --- |
| 用 AST 哈希锚（Fiberplane Drift 形态）替代 LLM 判定 | **否决为唯一手段**。哈希只能说「变了」，说不出「还对不对」；作为 design anchor 的补充在 decision-90003 里已有等价物。 |
| 让判官读整份 spec 与整个 diff | **否决**。成本随 diff 增长，且大部分 AC 与本次 diff 无关。 |
| 把判定做成硬门禁（CI 里跑模型，红灯阻塞） | **否决**。非确定、会误报堵合并；由复审者贴结论，`不再成立` 阻塞是人的决定不是脚本的。 |
| 在保存时用 hook 让代理顺手改文档（Kiro 形态） | **否决**。把「文档还对不对」的判断交给正在改代码的同一个上下文，没有独立性。 |

## 4. 后果

**接受的代价**

- 每个 PR 多一次读 IMPACT 的模型调用；无携带测试的代码只会得到 `IMPACT none`，这是诚实信号而非覆盖。
- import 反查只做相对路径，跨包别名解析不做；漏掉的间接影响由 `IMPACT none` 的确认句兜底。
- 判定质量取决于复审者；与所有 REVIEW.md 条目相同。

**得到的**

- 缺口 2 第一次有信号：改了行为没改 spec 的 PR 会被点名到具体 AC。
- REVIEW.md 有了第一条不依赖项目填空的检查项。

**不变的**

- trace-check 主命令与 (a)–(l) 不变；`--impact` 是只读子命令，不影响退出码。
- 合并规则仍是 PR.md：人审、检查通过。

## 5. 这个决定约束什么

- `scripts/trace-check`、`scripts/test_trace_check.py`：`--impact` 子命令。
- `.github/workflows/trace-check.yml`：pull_request 下的 impact 步骤。
- `REVIEW.md`：Spec impact 一节。
- `AUTOPILOT.md` 验收与 pr 阶段、`PR.md` Description。
- `CONTEXT.md`：Impact 词条。
