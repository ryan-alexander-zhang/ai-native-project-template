---
id: issue-00002-relation-edges-never-render
type: issue
status: resolved
blocks: [spec-00001-docs-whiteboard, record-00001-docs-whiteboard-acceptance]
---

# Issue: 关系边一条都没画出来

> 后端产出了边、前端也把它们转成了 React Flow 的 edge，但画布上没有任何一条。
> 自定义节点没有 `Handle`，React Flow 因此丢弃每一条边。

## 1. Problem

- Observed: 白板打开后节点正常，节点之间**没有任何连线**。渲染后的 DOM 里
  `.react-flow__edge` 为 0 个，边容器是空的 `<div class="react-flow__edges"></div>`。
- Expected: `spec-00001-AC-1.1` 要求「每个关系字段呈现为一条边」，
  `spec-00001-AC-2.2` 要求断链的边「带异常标记」。design-00002 §3 还规定了边的
  两种样式（正常 / destructive 虚线）。
- Trigger: 无条件——只要节点用自定义节点类型渲染，所有边都丢。

## 2. Impact

- Affected: 白板的每一个使用者。白板的立论是「一眼看清依赖链与卡点」
  （spec-00001 §2 的 S1），没有边就只剩一堆互不相干的节点，这条价值全部落空。
- Since: commit `3156bbd5`（前端画布首次落地） · Still occurring: no（本 issue 已修）
- Severity: 高。功能主张与实际呈现不符，且它同时使 AC-1.1 与 AC-2.2 失效。

## 3. Root Cause (first principles)

1. 分歧：前端**算出**了边，却没有**画出**边。`web/src/canvasModel.ts:20`
   的 `toFlowEdges()` 返回值正确（有单测证明），`web/src/Board.tsx:49` 也把它
   喂给了 `<ReactFlow edges={edges}>`。
2. 最小机制：React Flow 的边要靠**两端节点的锚点（handle）**定位。
   `@xyflow/react` 的内建节点自带锚点，而 `web/src/Board.tsx:26` 把节点全部
   换成了自定义类型 `doc`（在 `:96` 喂给 `<ReactFlow>`）；
   `web/src/NodeCard.tsx:17` 起渲染的那棵 DOM 里**没有任何 `<Handle>`**。
   取不到锚点时 `@xyflow/system` 的 `getEdgePosition` 报 `error008`
   （"Couldn't create edge for … handle id"）并返回 `null`，`EdgeWrapper` 随即
   不渲染该边。**在生产构建里这一步是静默的**，开发模式下 `createDevWarn` 会
   经默认 `onError` 打一条 `console.warn`——即存在一个我们从未观察的信号，见 §8。
3. 真正的根因：**自定义节点接管了渲染，却没有承接内建节点的连接契约**。
   注册 `nodeTypes` 是一次「我全权负责这个节点的 DOM」的声明，handle 是这份
   DOM 的一部分；漏掉它不会报错，只会让边消失。
   它**不是**的症状：不是布局问题（ELK 确实算出了坐标，节点位置正常），也不是
   数据问题（`GET /api/graph` 的 `edges` 非空）。

- Introduced by: `3156bbd5`。此前没有画布，边无从渲染；该 commit 同时引入
  自定义节点与边模型，只落地了前者。

## 4. Scope (same-cause sweep)

根因是「自定义节点必须自带 handle」，凡注册进 `nodeTypes` 的组件都共享它。

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `web/src/Board.tsx:26` 的 `doc` 节点 → `NodeCard` | yes | yes | 本次修复：补四方位各一对 source/target 共 8 个锚点 |
| 其余 `nodeTypes` 条目 | — | — | 只有 `doc` 一种，无第二处 |
| `NodeToolbar`（工具栏） | no | no | 它不是节点类型，由 React Flow 自行定位，不参与连线 |
| `Background` / `Controls` | no | no | 画布装饰，无连接语义 |

同一区域另有一个**不同根因**的缺陷（布局方向与阶段流相反），单独记为
`issue-00003-stage-flow-reads-backwards`，不在本 issue 处置。

## 5. Reproduction (test-first)

- Failing test: `web/test/canvas.test.tsx::draws an edge for each declared
  relation` —— 渲染 `<Board />` 后查询 `.react-flow__edge`。修复前为 0，
  修复后等于关系声明数。
- **边数断言不足以定因**：`getEdgePosition` 对「没有锚点」与「节点尚未被测量」
  返回同一个 `null`，`EdgeWrapper` 也丢得一模一样。因此还须一条断言观察
  `<ReactFlow onError>` 收到的是 `008`——只有它能把本缺陷与桩不到位区分开。
  **前提是这条通道存在**：React Flow 默认的 `onError` 是 `createDevWarn`，只在
  `NODE_ENV === "development"` 下输出，而 vitest 下是 `test`。所以必须自己接上
  `onError`（`web/src/flowError.ts`），否则那条断言检查的是一个恒为空的列表。
- 这条断言需要两个 jsdom 桩，缺一条边就画不出来（均已实测）：
  - **会上报尺寸的 `ResizeObserver`**。React Flow 只在两端节点都被测量过之后
    才画边，而现有桩（`web/test/setup.ts:35`）是三个空方法，回调永不触发。
    桩的 entry 必须带 `borderBoxSize`——`react-resizable-panels` 会读它。
  - **`DOMMatrixReadOnly`**。React Flow 读画布 transform 时用它，jsdom 没有。
- 桩补齐后同一棵 DOM 从 0 条边变为 1 条边（含 `.react-flow__edge-path`）。

## 6. Fix

- Change: `web/src/NodeCard.tsx` 补上四方位（上/下/左/右）各一对 source/target
  共 8 个锚点，以 `opacity-0` 隐藏；`web/src/canvasModel.ts` 按两端的相对位置
  为每条边指定 `sourceHandle`/`targetHandle`。详见 design-00002 §4 与 plan-00003。
- **修复时发现的另一半**：补上锚点会顺带打开手工连线。关掉它花了两轮实测——
  `<ReactFlow nodesConnectable={false}>` 管不到自定义节点的锚点（该 prop 只把
  `isConnectable` 传给节点组件），而只加 `isConnectable={false}` 也只摘掉 CSS
  类：`isConnectableStart`/`isConnectableEnd` 各自独立默认为 `true`，而
  pointer-down 的守卫读的是前者。三个标志全设才真正关上。design-00002 §4 已按
  两次实测更正。这与本缺陷是同一类错：自定义节点接管了渲染，就要把连接契约的
  每一部分都接过来。
- 另接上 `<ReactFlow onError>`（`web/src/flowError.ts`）：库自己的错误通道在
  非 dev 构建下是哑的，本缺陷当年正是因此无声。接上后它立刻报出了一条真实的
  `004`（jsdom 下画布无尺寸），证明该通道确实活着。
- Why this addresses the root cause and not the symptom: handle 是自定义节点
  欠下的那部分契约，补上它之后**任何**边都能落锚点，而不是为当前这批关系
  特例处理。
- Alternatives rejected: 改回 React Flow 内建节点 —— 会丢掉节点上的类型图标、
  状态 Badge 与异常 Popover（design-00002 §4 的全部内容），代价远大于收益。

## 7. Verification

- §5 的回归测试通过：`web/test/canvas.test.tsx::draws an edge for each declared
  relation` —— `.react-flow__edge` 数量等于关系声明数（修复前为 0）；
  `::reports no error through the react flow channel while drawing the graph`
  —— 画完整张图期间没有任何 `008`。**该断言一度是假的**：初版监听 `console.warn`
  而库在 `NODE_ENV=test` 下根本不写它，于是它检查的是一个恒空的列表；接上
  `onError` 后才成立，并额外断言该通道确实有输出，防止它再退化成恒真。
  `::routes a react flow error to the console` 单独守住通道本身。
- `spec-00001-AC-1.1`、`AC-2.2` 首次获得 DOM 级验证。
- 本仓真实文档实跑：17 个节点、**34 条边**全部有锚点（`readGraph → layoutGraph
  → toFlowEdges`，无一条落空）。

## 8. Follow-through

- Detection gap: 两层。其一，既有测试只对 `toFlowEdges()` 的**返回值**断言（
  `web/test/canvas.test.tsx:57` 起两条），从不检查边有没有进 DOM——模型正确与
  画面正确之间隔着 React Flow 的整个渲染契约，而这一侧一条断言都没有。这与
  `issue-00001` 同源：都是「断言了模型，没断言呈现」。其二，更难看的一层：库
  在开发模式下**本来就报了** `error008`，只要有一条断言观察 `onError` 或
  `console.warn`，这个缺陷从第一天就会暴露；我们既没有接 `onError`，也没有把
  意外的 `console.warn` 当作失败。本次补 DOM 级断言、`onError` 断言与两个
  jsdom 桩；「让意外的 console 输出使测试失败」是测试基建的改动，不属本 issue。
- Doc verdict: **code was non-conformant** —— AC-1.1、AC-2.2 与 design-00002 §3
  都已写明边要画出来；实现没做到。文档在这一点上无需修改（布局与锚点的新契约
  是另一件事，见 issue-00003 与 decision-00002）。
- Residual state: 修复前提交的所有 commit 里，白板都没有边；record-00001 中
  AC-1.1 与 AC-2.2 的「已验证」结论只覆盖模型层，需随本次修复更新。

## Links

- Blocks: spec-00001-docs-whiteboard, record-00001-docs-whiteboard-acceptance
- Related: issue-00003-stage-flow-reads-backwards（同一区域、不同根因）、
  decision-00002-whiteboard-layout、design-00002-whiteboard-ui §4、
  plan-00003-whiteboard-relation-edges
