---
id: plan-00016-entry-types-widen
type: plan
status: open
implements: [rule-00001-AC-26.2]
---

# Plan: 流程入口类型扩为四类——落地第十四轮（BR-26 修订）

对 `rule-00001-BR-26` 第十四轮修订的实现：流程入口类型扩为 idea/prd/
design/analysis（design 与 analysis 是无上游的思考承载类型，可先于 spec
存在）。代码本就由配置驱动（`entry` 列表），本轮无产品代码改动：配置、
规则、术语、模板与注记已随修订轮落地，余下测试侧两件事与验收收口。

## Design

- 无设计改动：`spec-00001-FR-53` 的新建机制对 entry 列表通用，
  `config.readEntry`/`assertEntryType`/`CreateDialog` 零硬编码（审计
  第 17 条实测确认）。design 模板已随本轮补齐正文骨架（审计第 16 条）。

## Tasks

- **T1 — 出厂配置守卫更新**：`config.test.ts` 的
  `declares idea and prd as the flow entry types` 按新集合更新断言、测试名
  与其上方注释（四类与两段理由）——它是配置有意变更的显式守卫，红是预期
  结果不是缺陷（审计第 19 条裁定）。
- **T2 — AC-26.2 的新测试**：新增一条建 design 的新建路径测试（夹具
  entry 含 design、仓库无 spec，断言取号、draft 与模板正文），带
  `// rule-00001-AC-26.2` 溯源标注——配置守卫验的是配置值，不能替代它
  （审计第 21 条）。
- **T3 — 测试与验收**：`npm test`、`npm run typecheck`、覆盖率门不降；写
  record（`parent` 指向本 plan，`verifies: [rule-00001-BR-26]`）按条目
  口径列全 AC-26.1 与 AC-26.2 两行（26.1 沿用 record-00011 已引测试），
  以本 plan 过 resolved 门收口。

## Detailed Acceptance Path

1. 新增测试通过、守卫测试转绿 → verify: 测试名与断言。
2. 全量测试、typecheck、覆盖率门全绿 → verify: 命令退出码与阈值。
3. record 列全 BR-26 两条 AC，本 plan 经 `open → resolved` 放行 →
   verify: resolved 门通过。

## Out of Scope

- 其余类型（reference/integration 等）的入口化——无提案。
- 新建对话框的分组呈现（entry 内类型不多，平铺即可）。
