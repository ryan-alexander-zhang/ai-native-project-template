---
id: plan-00007-whiteboard-parsing-contract
type: plan
status: resolved
implements: [spec-00001-FR-40, spec-00001-FR-41, design-00001-docs-whiteboard, design-00002-whiteboard-ui, decision-00005-whiteboard-parsing-contract]
---

# Plan: 解析契约——AST、诊断与 agent 输出约束

> 把条目文法从解析器的正则里搬到明面上：解析升级 remark AST，漂移当场报警
> （解析诊断 + 顶栏计数），同一份校验器进测试套件当门禁、进推进指令约束
> agent 产出。事实来源不动。

## Design

- [decision-00005-whiteboard-parsing-contract](../decision/decision-00005-whiteboard-parsing-contract.md)
  —— 架构裁定（Markdown 即 DSL）、否决与退路的触发条件。
- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md) §2
  （AST 与诊断产出）、§4（任务指令的文法段）、§7（`/api/graph` 与 `/items` 的
  diagnostics 字段）。
- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) §9
  （解析诊断区与顶栏诊断计数）。
- 文法正文：`docs/spec/README.md`、`docs/rule/README.md`、
  `docs/record/README.md` 各自的「机器可读形态」小节（本轮文档阶段已成文）。

## Tasks

代码位于 `tools/whiteboard/`。V1 先行（AST 是诊断的地基）；V2 依赖 V1；V3 的
指令模板部分可与 V1/V2 并行、产出校验部分依赖 V2；V4 收尾。

| # | 任务 | 交付 | 覆盖 |
| --- | --- | --- | --- |
| V1 | AST 解析器 | `requirements.ts` 的行级正则替换为 remark AST 遍历（服务端复用前端已有的 remark 生态），**行为契约不变——既有 483 条测试一条不改全绿是本任务的完成定义**；AST 位置信息随条目/验收行携带（供 V2 定位到行） | decision-00005 §2 第 4 条 |
| V2 | 解析诊断 | 按三个 README 的文法产出诊断：疑似条目不合形态（整行以粗体条目 id 起头而不匹配两种形态）、验收清单不合式行（首列含 id 语法而非恰一个合法 id，含区间/多 id）、无法归属（既有，改挂 diagnostics）；`/api/graph` 增 `diagnostics`、`/items` 的 `unattributed` 更名 `diagnostics`；检视面板诊断区扩名、顶栏诊断计数 Badge（为零不渲染） | spec FR-40 及其 AC、FR-33 观察点更名 |
| V3 | agent 输出约束与门禁 | 任务指令模板：目标类型有条目文法时附对应 README 的「机器可读形态」要求；会话结束校验扩展到正文文法（诊断呈现、不阻塞 commit）；**契约测试**：`本仓 docs/ 全量解析零诊断` 进测试套件——它同时是文法、解析器与真实文档三方对齐的常绿门禁 | spec FR-41 及其 AC；decision-00005 §2 第 3 条 |
| V4 | 测试与收尾 | 13 条新 AC 全部落测：`AC-40.1`…`40.9`、`AC-41.1`…`41.4`；AC-33.1/33.3 的观察点随区名更新；新建 `record-00006` 承载验收（`verifies` 列 FR-40/FR-41）；实测见下方第 4 条 | 全部 |

## Detailed Acceptance Path

1. **每任务完成即验证**：`npm test` 全绿、`npm run typecheck` 无错、
   `npm run build` 通过；覆盖率 ≥90% 不回落。
2. **新 AC**：上表 V4 的 13 条，每条有对应通过的测试；V3 的契约测试常绿。
3. **不回归**：V1 的完成定义即最强不回归声明——既有测试一条不改全绿。V2 有
   两处**预期变化不是回归**：断言 `unattributed` 字段名或「无法归属」区名的
   既有用例按新载体更新（AC-33.1/33.3 的语义不变）；`/api/graph` 返回对象的
   整形断言随 `diagnostics` 字段更新。
4. **实测核对**：用本仓真实文档——(a) 全量零诊断、顶栏无诊断 Badge
   （AC-40.5 的真实数据面）；(b) 临时放入一份含区间行与形态残缺条目的 spec
   夹具（置于 docs/ 下，实测完毕即删、不入 commit），刷新后诊断区与计数如
   FR-40 呈现、节点不转异常、修复后诊断消失；(c) 发起一次推进会话（目标类型
   spec），核对任务指令含文法段——无需等 agent 写完即可断开。任一不成立，
   据实记入 `record-00006`，不得默认通过。
   **夹具护栏**：(b) 的临时夹具在位期间契约测试必红，属预期——实测与
   `npm test` 不得同时下判；夹具删除后必须重跑契约测试全绿、`git status`
   与实测前逐行一致，方可进入收尾；收尾 commit 前再核对一次无夹具残留。
5. **收尾门槛**：未参与实现的 subagent 按文档核验每条 GWT 有通过的测试，且
   范围内无 unverified 条目；`record-00006` 建好并链上 GWT id 后本 plan 方可
   `resolved`。任何 gap 阻塞 `resolved`。
