---
id: plan-00010-whiteboard-audit-and-resolved-gate
type: plan
status: resolved
implements: [spec-00001-FR-50, spec-00001-FR-51, spec-00001-FR-52, rule-00001-BR-16, rule-00001-BR-23, rule-00001-BR-24, rule-00001-BR-25, decision-00007-whiteboard-audit-and-resolved-gate, design-00001-docs-whiteboard, design-00002-whiteboard-ui]
---

# Plan: 审计评审动作与 plan 的 resolved 门——落地第十轮（FR-50…52）

对 [decision-00007](../decision/decision-00007-whiteboard-audit-and-resolved-gate.md)
三项裁定的实现：审计成为第三种评审动作、plan 以 `implements` 声明交付范围、
`open → resolved` 据覆盖缺口拒绝；流程配置的 `plan → issue/record` 延伸已随
文档轮落入 `whiteboard.config.yaml`，本 plan 验证其在既有 FR-10/FR-15 通路上
成立。本 plan 亦是 resolved 门的第一个真实用户：其 `implements` 即按条目粒度
声明的交付范围。

## Design

- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md)
  §2（Workflow Engine 的审计裁决与 resolved 门）、§7（`POST
  /api/sessions/audit`、status 422 的门拒绝、commit action `audit`）。
- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md)
  §3（浮窗工具栏的审计按钮）。

## Tasks

前三个任务相互独立、可并行；T4 依赖 T1…T3。

- **T1 — 服务端：审计会话**（FR-50、FR-51）
  - `clarifyRules.ts` 旁新增可审计类型集（`spec`、`rule`、`design`，BR-23
    内建，形态同 `isClarifiable`）。
  - `workflow.ts` 新增 `assertAuditable`（draft + 可审计类型 + 非异常，422
    语义同 clarify）。
  - `sessionTasks.ts` 新增 `auditInstruction`（目标文档路径、该类型文件夹
    README 路径、BR-22 的审查要求与落点契约、不得改 status、写权限约束，
    英文，形态同 `clarifyInstruction`）。
  - `server.ts` 新增 `POST /api/sessions/audit`，会话通道、单会话约束、终止
    与收尾 commit（`wb(audit): <doc-id>`）全部复用既有机制。
- **T2 — 服务端：resolved 门**（FR-52、BR-24、BR-25）
  - 交付范围解析：从 plan 的 `implements` 分拣条目 id、spec/rule 整文档 id
    （展开为全部条目）、其他目标（忽略）；无法解析的 id 记为缺口。
  - 门判定：证据集 = `parent` 指向该 plan 的 record，复用 `requirements.ts`
    的同一覆盖推导（以 record 集为入参），范围内任一条目非 `verified` 即
    拒绝；拒绝消息逐条点名。范围为空放行；仅 `plan` 的 `open → resolved`
    经此门。
  - 挂载点：`docService` 的 status 写路径，在合法流转校验（FR-7）之后、写
    文件之前。
- **T3 — 前端：审计按钮**（FR-50、FR-51 的 UI 面）
  - Toolbar 新增审计按钮（outline 变体、`ShieldCheck` 图标），仅 `draft` 的
    可审计类型节点呈现；禁用与「session running」提示同澄清/答疑。
  - status 422 的门拒绝消息经既有 `toast.error` 呈现，无新组件。
- **T4 — 测试与验收**
  - 单元/集成测试覆盖本 plan 交付范围内全部 AC（FR-50…FR-52 与
    BR-16/BR-23/BR-24/BR-25 的全部 AC；`rule-00001-BR-22` 是 agent 行为
    规则，不入交付范围，其可断言面由 AC-50.2 的指令契约承载）；
    `acceptance.test.ts` 增审计会话与 resolved 门的端到端用例。
  - 质量门：`tsc --noEmit` 与覆盖率（line/branch/function ≥ 90%）不降。
  - 写 record 验收清单（`parent` 指向本 plan），以本 plan 自身过 resolved 门
    收口。

## Detailed Acceptance Path

1. T1…T3 完成后 `npm test` 与 `npm run typecheck` 全绿 → verify: CI 命令退出码。
2. 交付范围内每条 AC 在 record 验收清单中有通过行 → verify: 白板检视面板
   覆盖三态全 `verified`。
3. 本 plan `open → resolved` 经白板执行成功——门放行即最终验收（门失败则
   门自身有缺陷，先建 issue）。

## Out of Scope

- 历史 plan（00001…00009）的 `implements` 回填条目粒度。
- 审计对 `active` 文档修订轮的适用（decision-00007 §3 已否决，MVP 不展开）。
- CI 承载门（decision-00007 §3 已否决）。
