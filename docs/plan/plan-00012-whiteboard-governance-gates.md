---
id: plan-00012-whiteboard-governance-gates
type: plan
status: open
implements: [spec-00002-FR-1, spec-00002-FR-2, spec-00002-FR-3, spec-00002-FR-4, spec-00002-FR-5, spec-00002-FR-6, spec-00002-FR-7, spec-00002-FR-8, spec-00002-FR-9, design-00001-docs-whiteboard]
---

# Plan: 治理轮之一——促进门、归档门、关系矩阵与撞 id（FR-1…FR-9）

对 [spec-00002](../spec/spec-00002-whiteboard-governance.md) 前九条 FR 的
实现：把 `rule-00001-BR-12`/`BR-19` 的两道门补进状态切换通路、以流程配置的
关系矩阵产出 `relation-field` 诊断、撞 id 文档按路径成节点并拒绝按 id 寻址
的写。缺陷出处见
[issue-00015](../issue/issue-00015-open-questions-gate-bypassed-on-status-path.md)
（促进门）与
[issue-00004](../issue/issue-00004-duplicate-ids-hide-a-document.md)（撞 id）。

## Design

- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md)
  治理轮修订：§2（Workflow Engine 两道门、Doc Repository 的撞 id 键与
  `relation-field` 诊断）、§3（流程配置的关系矩阵块与启动校验）、§6（状态
  切换管道的门序）、§7（422 拒绝形状）。

## Tasks

T1 先行（test-first 的缺陷修复）；T2…T4 相互独立可并行；T5 收口。

- **T1 — 促进门**（FR-1/FR-2，issue-00015）：先落 issue-00015 §5 计划的两条
  失败测试并把失败输出记回该 issue，再把 `hasOpenQuestions` 判定加进
  `changeStatus` 的促进流转（living→`active`、work→`open`），与接收同一
  判定；拒绝消息点名未决 Open Questions；issue-00015 随修复与回归转绿置
  `resolved`。
- **T2 — 归档门**（FR-3/FR-4）：`changeStatus` 目标为 `archived` 时查全图
  front matter 的 `supersedes` 配对（异常声明方也算、自指不算），无配对
  422 拒绝并说明；与 resolved 门并排、按 design-00001 §6 的门序执行。
- **T3 — 关系矩阵**（FR-5…FR-7）：config 增 `carries` 矩阵解析与启动校验
  （未声明类型/字段、值非字符串列表即拒启动并点名；矩阵或类型缺失即不校验
  该范围）；图构建产出 `relation-field` 诊断（不允许的字段、`parent` 多值），
  不改变节点异常判定；启用仓库根 config 的矩阵块。
- **T4 — 撞 id**（FR-8/FR-9）：`docRepository` 对撞 id 文档以文件路径为节点
  键并各自标异常、problem 互指；其条目退出条目归属/覆盖/resolved 门（范围
  命中撞 id 文档视为无法解析缺口）；指向撞 id 的关系边按无法解析处置；按
  撞 id 寻址的写一律 409 拒绝（状态冲突语义，复用 ConflictError——
  design-00001 §2 治理轮裁定）、按路径寻址的编辑保存照常；命令面板可按路径
  与撞 id 检索。前置缺陷：`web/src/api.ts` 构造 `/api/docs/:id` 未做 URL
  编码，路径键含 `/` 的节点今日即无法编辑（设计轮发现）——先按
  `docs/issue/README.md` 立 issue-00016 并以失败测试复现，再修
  `encodeURIComponent`；FR-9 的按路径编辑通路依赖它。issue-00004 随本任务
  置 `resolved`（其回归测试转正）。
- **T5 — 文档收尾**：`spec-00001` §6 的「id 唯一性校验」「归档配对自动化」
  两条加指针注记（已由 spec-00002 接管；typo 级修订，不动其余正文）；
  `docs/README.md` 补一句 id 在仓库内唯一的约束（BR-18 取号保证新档不撞，
  存量撞 id 由白板标异常并拒动作）。
- **T6 — 测试与验收**：覆盖交付范围内全部 AC；质量门（typecheck、覆盖率
  ≥90%）不降；写 record（`parent` 指向本 plan），以本 plan 过 resolved 门
  收口。

## Detailed Acceptance Path

1. issue-00015 的两条测试先红后绿，失败输出已记回 issue §5 → verify: issue
   正文与测试。
2. `npm test`、`npm run typecheck`、覆盖率门全绿 → verify: 命令退出码与阈值。
3. 交付范围内每条 AC 在 record 验收清单有通过行 → verify: 检视面板覆盖三态。
4. 本 plan 经 `open → resolved` 放行 → verify: resolved 门通过。

## Out of Scope

- FR-10…FR-15（全局覆盖率视图与下钻，plan-00013）。
- 未知关系字段（拼写错误）的检测、边方向语义执法、归档门回溯（spec-00002
  §6）。
