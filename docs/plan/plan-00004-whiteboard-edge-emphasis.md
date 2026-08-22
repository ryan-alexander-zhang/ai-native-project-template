---
id: plan-00004-whiteboard-edge-emphasis
type: plan
status: resolved
implements: [spec-00001-FR-28, spec-00001-FR-29, spec-00001-FR-30, design-00002-whiteboard-ui]
---

# Plan: 边的弱化/强调与关系列表

> 让默认视图从「34 条线 + 34 个标签」变成一层可读的淡背景，选中节点时它的关系
> 才成为主角；中枢节点另给一份可读的关系列表。
>
> （第十轮按 `rule-00001-BR-24` 把 `implements` 从整文档回填为条目粒度：本轮
> 交付 FR-28…FR-30 的行为，但 FR-28 的 AC-28.5 属第四轮、由 plan-00005 的
> record 验收，故交付范围只声明 FR-29、FR-30——FR-28 的验收证据见
> record-00003，其条目收口归 plan-00005。）

## Design

- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) §3
  （关系列表控件）、§4（边的两个呈现态）。
- [decision-00003-whiteboard-edge-emphasis](../decision/decision-00003-whiteboard-edge-emphasis.md)
  —— 三条做法的理由，以及被实测否决的正交走线。

## Tasks

代码位于 `tools/whiteboard/web/`。E1 与 E3 可并行；E2 依赖 E1。

| # | 任务 | 交付 | 覆盖 |
| --- | --- | --- | --- |
| E1 | 边的三态 | `toFlowEdges()` 接收当前选中项，产出 `edge--dim` / `edge--emphasis` / `edge--suppressed` 三个类（design §4 定义），强调态另给 `zIndex` 与关系名 `label`，其余两态 `label` 置空；同一对文档间的多个关系字段合并为一条边、标签列出全部字段名 | spec FR-28、FR-29；design §4 |
| E2 | 节点压弱 | 选中时与选中项无关的节点降透明度；`NodeCard` 接一个 `dimmed` 属性，取消选中即恢复 | spec AC-29.2；design §4 |
| E3 | 关系列表 | 工具栏加 `Popover`：按关系字段分组列出该节点的全部关系（字段名、方向、对端 id），点击定位并选中对端；无关系时呈现「no relations」；异常节点同样提供。方向与排序的定义在 `spec-00001-FR-30`，**不在本 plan 里另行裁定** | spec FR-30 及其 AC |
| E4 | 测试 | `AC-28.1`…`AC-28.4`、`AC-29.1`…`AC-29.8`、`AC-30.1`…`AC-30.5` 共 **17 条**全部落测；按 design §7 第三轮改写受影响的既有断言 | 全部 |
| E5 | 收尾 | 新建 `record-00003` 承载本 plan 的验收，其 `verifies` 列**具体的 requirement id**（`spec-00001-FR-28`…`FR-30`），而不是只列文档 id（`docs/plan/README.md` 的要求；`record-00002` 只列了文档 id，本次不追改）；并把默认视图与中枢节点的实测观感记入其中 | — |

## Detailed Acceptance Path

1. **每任务完成即验证**：`npm test` 全绿、`npm run typecheck` 无错、
   `npm run build` 通过。
2. **新 AC**：`AC-28.1`…`AC-28.4`、`AC-29.1`…`AC-29.8`、`AC-30.1`…`AC-30.5`
   共 17 条，每条有对应通过的测试。
3. **不回归**：FR-1…FR-27 的既有 AC 仍全部通过。特别是 `AC-1.1`——边**仍然全部
   画出**，本次只改呈现强度；若某条测试因为标签消失而失败，那是它原本在断言
   标签，按 design §4 更新观察点，不是回归。
4. **覆盖率**：自有代码仍 ≥90% 行/分支/函数。
5. **实测核对**：用本仓真实文档开一次白板，确认默认视图不再有叠字、选中
   `spec-00001` 时它的 17 条边可辨、关系列表把这 17 条读得清楚。**decision-00003
   §5 把后两点列为未经验证的前提**——这一步就是验证它们；若 17 行的列表或十余个
   同时出现的标签仍不可读，据实记入 `record-00003` 并提出下一步，不得默认通过。
6. **收尾门槛**：由未参与实现的 subagent 按文档核验；`record-00003` 建好并链上
   GWT id 后本 plan 方可 `resolved`。任何 gap 阻塞 `resolved`。
