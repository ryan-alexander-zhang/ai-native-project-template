---
id: plan-00015-advance-inherits-open-questions
type: plan
status: resolved
implements: [spec-00001-AC-11.3, spec-00001-AC-11.4]
---

# Plan: 推进指令的上游未决点继承——落地第十三轮（FR-11 修订）

对 `spec-00001-FR-11` 第十三轮修订的实现：推进任务指令给出来源文档路径
（无条件），且目标类型带 Open Questions 语义（可澄清集）时附上游未决点
继承要求——读取来源的未决 Open Questions，仍影响新文档的继承进新文档的
Open Questions 小节，不得沉默替上游做决定。来自实测讨论：推进不设 OQ 门
（既有取舍），不确定性须显式传递而非无声吸收。design-00001 §4 的指令模板
枚举已同步本条款。

## Design

- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md)
  §4 任务指令模板（指令文本层改动，不触及模块结构与 API）。实现约束（审计
  裁定）：来源路径行入无条件指令行段；继承段为**条件追加**（目标类型 ∈
  可澄清集），与 `ITEM_GRAMMAR` 段同构、排在无条件行之后。

## Tasks

- **T1 — 指令条款**：`advance.ts` 的 `taskInstruction` 增来源路径行
  （无条件）与继承要求段（条件，英文）；`advance.test.ts` 镜像既有
  containment 断言补正负两条，新断言各带 `// spec-00001-AC-11.3` 与
  `// spec-00001-AC-11.4` 溯源标注。
- **T2 — 测试与验收**：`npm test`、`npm run typecheck`、覆盖率门不降；写
  record（`parent` 指向本 plan）覆盖 FR-11 全部四条 AC（11.1/11.2 引既有
  测试，11.3/11.4 引新增），以本 plan 过 resolved 门收口。

## Detailed Acceptance Path

1. 新增正负两条 containment 断言通过 → verify: 测试名与指令文本。
2. 全量测试、typecheck、覆盖率门全绿 → verify: 命令退出码与阈值。
3. record 覆盖 FR-11 四条 AC，本 plan 经 `open → resolved` 放行 →
   verify: resolved 门通过。

## Out of Scope

- 推进的 OQ 硬门（既有取舍维持：不设门，显式传递 + 新文档接收门把关）。
- 继承行为的工具侧校验（与 BR-11 产出校验同类，独立立项）。
