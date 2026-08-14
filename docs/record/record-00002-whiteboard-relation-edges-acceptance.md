---
id: record-00002-whiteboard-relation-edges-acceptance
type: record
status: active
parent: plan-00003-whiteboard-relation-edges
verifies: [spec-00001-docs-whiteboard]
---

# 验收记录：关系边与类型分列布局

对 [plan-00003-whiteboard-relation-edges](../plan/plan-00003-whiteboard-relation-edges.md)
的验收。它同时关闭
[issue-00002](../issue/issue-00002-relation-edges-never-render.md)（边一条都没画出来）
与 [issue-00003](../issue/issue-00003-stage-flow-reads-backwards.md)（阶段流画反）。

- 套件：`cd tools/whiteboard && npm test` → **19 个测试文件、337 个测试全部通过**，无未处理异常
- 覆盖率：语句 98.78%、分支 95.13%、函数 97.46%、行 99.21%（门槛 90%；
  vendored 的 `web/src/components/ui/**` 按 decision-00001 §4 排除在外，排除名单
  未加宽）。新增的 `web/src/layout.ts`、`flowError.ts` 与改写的 `NodeCard.tsx`、
  `canvasModel.ts` 均为 100%
- 类型检查：`npm run typecheck` 无错误；`npm run build` 通过
- 构建产物：主 chunk 3,219.71 kB → **1,779.92 kB**（gzip 995.61 → 551.28 kB），
  去掉 elkjs 后减少约 45%。改造前的数字取自改动前的一次构建，仓库中已无从复现

测试名为 `tools/whiteboard/` 下的用例标题：`t/` = `test/`，`w/` = `web/test/`。

## 新增 GWT

| GWT id | 测试 | 结果 | 证据 |
| --- | --- | --- | --- |
| spec-00001-AC-1.6 | places each type in its own column, left to right (w/board) | pass | 三个类型 x 严格递增、y 相同 |
| spec-00001-AC-1.7 | stacks documents of the same type in one column, by id (w/board) | pass | 同 x；`spec-00001` 的 y 更小 |
| spec-00001-AC-1.8 | leaves no empty column for a type with no documents (w/board) | pass | 列序中间的 `prd` 无文档时，间距恰为一列宽 |
| spec-00001-AC-1.9 | puts an undeclared type after every declared one (w/board)；puts a document with no type last of all (w/board) | pass | 未知类型与无类型各一条 |
| spec-00001-AC-1.10 | points the arrow at the document being referenced (w/canvas)；draws the arrow head only at the referenced end (w/canvas) | pass | 模型层 `markerEnd` 落在 `target`；DOM 层 `marker-end` 有箭头、`marker-start` 为空——只断言前者的话两端都有箭头也会通过 |
| spec-00001-AC-1.11 | anchors a same-column edge top to bottom (w/canvas) | pass | 下方节点出上锚点、上方节点入下锚点 |
| spec-00001-AC-1.12 | puts every node back where it was after a refresh (w/board) | pass | 刷新前后 `placed` 全等 |
| spec-00001-AC-1.13 | places a document that declares no relations, with no edge on it (w/board) | pass | 取样含另一类型的邻居，因此「落在对应列」这半也被真正检验（单节点时 x 恒为 0，检验不到） |
| spec-00001-AC-1.14 | offers no handle to drag a new edge from (w/canvas) | pass | 锚点存在，但 `connectable`、**`connectablestart`、`connectableend`** 三个类一个都没有——守卫读的是 `connectablestart`，只断言 `connectable` 等于断言一个不起作用的属性 |

## 被本次推翻并重记的既有 GWT

| GWT id | 原证据 | 现证据 |
| --- | --- | --- |
| spec-00001-AC-1.1 | 仅数据层「每关系一条边」 | 增 `draws an edge for each declared relation (w/canvas)`——**DOM 里边数等于声明数**，此前恒为 0 |
| spec-00001-AC-1.2 | 「ELK 实跑，y 不相等」 | 换为 `places each type in its own column, left to right (w/board)`；原证据随 issue-00003 失效 |
| spec-00001-AC-2.2 | 数据层 `ok=false` + 组件层 className | 同左，但该边现在真的画得出来 |

## 缺陷关闭的证据

| Issue | 回归测试 | 结果 |
| --- | --- | --- |
| issue-00002 | draws an edge for each declared relation (w/canvas) | 修复前 0 条边，修复后等于声明数 |
| issue-00002 | reports no error through the react flow channel while drawing the graph (w/canvas) | 画完整张图无任何 `008`。**初版是假的**（见下），现经自接的 `onError` 观察，并断言通道确有输出 |
| issue-00002 | routes a react flow error to the console (w/canvas) | 单独守住错误通道本身可用 |
| issue-00003 | places each type in its own column, left to right (w/board) | 阶段流与阅读方向一致 |
| issue-00003 | stacks documents of the same type in one column, by id (w/board) | 同类型不再被边拆到两列 |

## 本仓真实文档实跑

`readGraph → layoutGraph → toFlowEdges` 跑本仓 17 份文档：

```
x=   0 idea      idea-00001
x= 336 prd       prd-00001
x= 672 spec      spec-00001
x=1008 rule      rule-00001
x=1344 decision  decision-00001, decision-00002
x=1680 design    design-00001, design-00002
x=2016 plan      plan-00001, plan-00002, plan-00003
x=2352 issue     issue-00001, issue-00002, issue-00003, issue-00004
x=2688 record    record-00001, record-00002
```

17 个节点、34 条边全部落到锚点，无异常。箭头方向实测 **26 条朝左、8 条朝右、
0 条同列**——朝左占多数是这批文档的类型分布所致，不是关系字段名的规律
（design-00002 §4 已按此更正原先的错误推理）。

## 独立核验推翻的三件事

核验由未参与实现的 subagent 执行，结论是 BLOCK；三条都已改，改法与结论记在这里，
因为它们是本次交付中**证据比行为更弱**的部分：

1. **`008` 断言原本是假的。** 它监听 `console.warn`，而 React Flow 的
   `createDevWarn` 只在 `NODE_ENV === "development"` 下输出，vitest 下是 `test`。
   那条断言检查的是一个恒为空的列表，加不加修复都通过。处置：给 `<ReactFlow>`
   接上自己的 `onError`（`web/src/flowError.ts`），断言改为观察它，并**额外断言
   该通道确有输出**，防止它再退化成恒真。接上当天它就报出一条真实的 `004`
   （jsdom 下画布无尺寸），证明通道是活的。
2. **AC-1.14 断言了一个不起作用的属性。** 实测：`connectable=0` 而
   `connectablestart=16`、`connectableend=16`——`Handle` 的这两个标志各自独立
   默认为 `true`，pointer-down 的守卫读的正是 `connectablestart`。当时真正挡住
   连线的是 `nodesConnectable={false}`（不渲染连接线）与「没有 `onConnect`」，
   而不是文档所称的机制。处置：三个标志全设，断言落在 `connectablestart` 上；
   design-00002 §4 与 issue-00002 §6 按两次实测更正。
3. **「撞 id 不再重合」是过度声称。** `layoutGraph` 确实返回两个不同坐标，但
   `toFlowNodes` 按 id 取位置，两个同 id 节点取到同一条，且其中一个在 React Flow
   的节点表里就被覆盖。`(id, path)` 全序**没有任何可观察效果**。decision-00002
   §2 与 `issue-00004` §6 已改为如实描述。

核验同时确认了 9 项（AC 与测试一一对应、`layout.ts` 与决定逐条相符且列序在
10/11/100 个类型下都正确、边确实进 DOM、无回归、elkjs 彻底移除、列序有测试钉住、
真实仓库布局与数字全部复现、`issue-00004` 的描述准确、无悬空引用）。

## 核验后补的四处

| 缺口 | 处置 |
| --- | --- |
| `type: ""` 落进「未知类型」桶而非「缺类型」桶，排在所有具名未知类型之前 | `layout.ts` 视空串为缺失；补 `treats an empty type as a missing one` |
| `GET /api/config` 失败会连带让图不加载（改造前两个请求是独立的） | `useBoard` 捕获配置失败、提示并照常拉图；补 `still draws the graph when the config cannot be read` |
| 同列边「上方节点指向下方」的分支无测试 | 补 `anchors a same-column edge the other way when the source is above` |
| AC-1.10 只有模型层证据 | 补 DOM 断言（`marker-end` 有、`marker-start` 无） |

## 未覆盖与已知缺口

- **撞 id** 仍未处理：`issue-00004` 保持 `open`，且**一点也没被顺带修好**——
  见上文核验第 3 条。`spec-00001` §6 Out of Scope 已显式记录该缺口。
- **边不做路由**：跨多列的边从中间列节点下方穿过，为 decision-00002 §4 明确
  接受的代价，无测试守护。
- **纵向无界**：某一类型文档过多时该列长过视窗，同上。
- **未做真实浏览器验收**：`plan-00003` 验收路径第 6 条要求人工开一次白板。
  已用 `readGraph → layoutGraph → toFlowEdges` 对真实仓库完整复现（列序、行序、
  34 条边全部落锚、箭头方向），但「在浏览器里看着对」这一条只在 jsdom 与模型层
  得到验证，未在真实几何下核对过锚点拖拽的观感。
- **`plan-00001` T6 仍写着「React Flow + ELK 布局」**：那是一份 `resolved` 的
  plan，记录的是它当时交付的东西，故不改。读者若把它当现状会被误导——本记录与
  decision-00002 是现状的出处。
