---
id: plan-00006-whiteboard-text-rendering
type: plan
status: resolved
implements: [spec-00001-docs-whiteboard, design-00001-docs-whiteboard, design-00002-whiteboard-ui]
---

# Plan: 文本呈现——渲染、详情与标签阈值

> 补上「读全文」这条路：条目/AC/验收行的文本处处按行内 Markdown 渲染，子画布
> 单击出详情面板，面板行单击就地展开；悬停标签设密度阈值，消除 900px 长条遮挡。
> 连带修掉 plan-00005 实测的两处视口瑕疵。

## Design

- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) §9
  第五轮各段（文本呈现、详情面板与行内展开、标签密度阈值、视口修正）。
- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md) §7
  （`/items` 验收行的 `evidence?` 字段）。
- 原则出处：decision-00004（卡片管辨认/详情管阅读的同构延伸）与
  decision-00003 §5（标签密度阈值）。

## Tasks

代码位于 `tools/whiteboard/`。U1 先行（渲染组件被 U2 复用）；U3 不依赖 U1，
可与之并行；U4 收尾。

| # | 任务 | 交付 | 覆盖 |
| --- | --- | --- | --- |
| U1 | 行内渲染 | 行内 Markdown 渲染组件（复用 Preview 管线，components 只映射行内元素，块级降级纯文本，不启用 rehype-raw）；应用到检视面板行、子画布节点；截断改为 line-clamp 作用于渲染结果；`/items` 验收行补 `evidence?` 字段（服务端一并） | spec FR-39 及其 AC；design-00001 §7 |
| U2 | 详情面板与行内展开 | 子画布单击节点 → 右槽只读详情面板（AC 全 GWT、条目全文+AC 清单、验收行含 Evidence——缺省则不呈现该字段——与跳回 record 入口），空白/Esc/面包屑返回均关闭，宽度独立持久化；检视面板条目行单击或 Enter 展开/收起（单开），展开态含 AC 全文（零 AC 呈「无 AC」），图刷新后按 id 保持，悬停联动不变。**落地裁定**（spec Out of Scope 预告归本 plan）：子画布打开期间图刷新 → `/items` 重取，下钻、详情与展开按 id 保持，所指对象消失则就近关闭；该文档被删 → 返回顶层。此裁定无 GWT，实测覆盖 | spec FR-37、FR-38 及其 AC |
| U3 | 标签阈值与视口 | 悬停边标签 ≤3 逐项、>3 折叠「首个 +N」；进入子画布 fit 全部节点；工具栏避让右槽不被裁边 | spec FR-34 修订（AC-34.7/34.8）、AC-35.7；design §9 视口修正 |
| U4 | 测试与收尾 | 26 条新 AC 全部落测：`AC-34.7`…`34.9`、`AC-35.7`、`AC-37.1`…`37.9`、`AC-38.1`…`38.7`、`AC-39.1`…`39.6`；新建 `record-00005` 承载验收（`verifies` 列 FR-37…FR-39 与 AC-34.7…34.9、AC-35.7）；实测见下方第 4 条 | 全部 |

## Detailed Acceptance Path

1. **每任务完成即验证**：`npm test` 全绿、`npm run typecheck` 无错、
   `npm run build` 通过；覆盖率 ≥90% 不回落。
2. **新 AC**：上表 U4 的 26 条，每条有对应通过的测试。
3. **不回归**：既有 39 条（plan-00005）与更早的 AC 全部通过。两处**预期变化不是
   回归**：面板行与子画布节点的文本断言若按原始 Markdown 源码匹配（含 `**`、
   反引号），按 FR-39 更新为渲染后文本；悬停标签断言若按全量并列匹配多于 3 条
   的场景，按 FR-34 修订更新（`AC-34.1` 的两条并列场景不受影响）。
4. **实测核对**：用本仓真实文档——(a) 面板与子画布的文本无原始标记噪声；
   (b) 悬停 FR-29（8 条 AC）标签为「首个 +7」形态，不再遮挡三张卡片（0.5 缩放
   下标签字号的可读性不在本轮，record-00004 观察项 2 后半继续留档）；(c) 子画布
   进入即见全貌；(d) 返回顶层后工具栏完整可见（design §9 视口修正的实测验收）；
   (e) 详情面板读 AC-8.1 一类长 GWT 全文无截断；(f) U2 落地裁定的两个场景
   （子画布打开期间图刷新、文档被删）行为如裁定。任一不成立，据实记入
   `record-00005`，不得默认通过。
5. **收尾门槛**：未参与实现的 subagent 按文档核验每条 GWT 有通过的测试，且范围
   内无 unverified 条目（每条 FR 的每条 AC 都被 record 行引用）；`record-00005`
   建好并链上 GWT id 后本 plan 方可 `resolved`。任何 gap 阻塞 `resolved`。
