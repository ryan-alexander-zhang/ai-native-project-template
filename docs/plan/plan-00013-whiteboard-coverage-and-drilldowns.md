---
id: plan-00013-whiteboard-coverage-and-drilldowns
type: plan
status: resolved
implements: [spec-00002-FR-10, spec-00002-FR-11, spec-00002-FR-12, spec-00002-FR-13, spec-00002-FR-14, spec-00002-FR-15, design-00001-docs-whiteboard, design-00002-whiteboard-ui]
---

# Plan: 治理轮之二——全局覆盖率视图与异常/诊断下钻（FR-10…FR-15）

对 [spec-00002](../spec/spec-00002-whiteboard-governance.md) 后六条 FR 的
实现：顶栏的全局覆盖率视图（按文档的覆盖三态计数、展开逐条目、点击定位），
异常计数与诊断计数从死数字变为可下钻清单。依赖 plan-00012 的撞 id 处置
（撞 id 文档不入覆盖视图）先行落地。

## Design

- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md)
  治理轮修订：§2（正文解析结果的按变更失效缓存）、§7（覆盖率聚合载荷、
  issues/diagnostics 清单字段）。
- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md)
  治理轮修订：§3（覆盖率视图入口与对话框、异常/诊断计数的下钻清单、图标
  与可访问性约定）。

## Tasks

T1 先行（服务端载荷）；T2…T3 依赖 T1 的载荷形状（契约已在 design 固定，可
并行开发、联调收尾）；T4 收口。

- **T1 — 覆盖率聚合载荷**（FR-10 数据侧）：按 design-00001 §7 的形状提供
  全部 spec/rule 的逐文档覆盖三态计数与逐条目状态（证据集为全部 record、
  不分 status；含 front matter 异常但正文可解析者；撞 id 文档除外）；正文
  解析结果按变更失效缓存，不逐请求重读整树。
- **T2 — 全局覆盖率视图**（FR-10…FR-12 界面侧）：顶栏入口 + 全屏对话框
  （Esc 与关闭钮）；逐文档行含三计数，单行展开（同刻至多一行、刷新后按
  文档 id 保持）；点击条目关闭视图、定位并选中所属文档节点，检视面板按
  spec-00001-FR-31 的右槽规则跟随；目标刷新后不存在时提示不定位；空仓库
  空态。
- **T3 — 异常与诊断下钻**（FR-13…FR-15）：两个计数各自成为入口，清单分别
  列异常（来源 + problem，边异常归声明方）与诊断（文档 id + 类别 + 原文
  行），零计数不提供入口；点击一条定位并选中对应节点（front matter 不可
  解析的文件定位到其路径标签节点）；清单项键盘可达。
- **T4 — 测试与验收**：覆盖交付范围内全部 AC；质量门（typecheck、覆盖率
  ≥90%）不降；写 record（`parent` 指向本 plan），以本 plan 过 resolved 门
  收口。

## Detailed Acceptance Path

1. `npm test`、`npm run typecheck`、覆盖率门全绿 → verify: 命令退出码与阈值。
2. 交付范围内每条 AC 在 record 验收清单有通过行 → verify: 检视面板覆盖三态。
3. 本 plan 经 `open → resolved` 放行 → verify: resolved 门通过。

## Out of Scope

- 覆盖率视图的筛选、排序、分组与导出（spec-00002 §6）。
- FR-1…FR-9（plan-00012）。
