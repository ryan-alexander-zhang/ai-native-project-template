---
id: issue-00024-node-buttons-start-a-drag-gesture
type: issue
status: resolved
blocks: [spec-00001-docs-whiteboard, plan-00024-whiteboard-navigation-sidebar]
---

# Issue: 节点内的按钮按下即启动节点拖动手势——点击可被吞、位置却从不真动

> 画布对节点的拖动手势默认开启，而节点卡片上的两个按钮（会话标记、异常徽标）
> 没有 `nodrag`：按下时 React Flow 的 d3-drag 先接管，手指稍动一下这次点击就被
> 吞掉；而本板的节点位置由布局算法给出、受控图不接位置变更，拖动本身什么也
> 不会改变。plan-00024 实现期由测试环境的崩溃暴露。

## 1. Problem

- Observed：在节点卡片的会话标记按钮或异常 `TriangleAlert` 徽标上按下鼠标并
  轻微移动再松开，按钮的点击不触发（终端不切到该会话、problems 弹层不开）；
  期间光标呈拖动态，松手后节点回到原位。纯粹的按下即松开可以点中。
- Expected：节点上的控件与页面上任何按钮同权——按下即点中，不存在「稍微动了
  就不算」的手势层；节点也不应对拖动有任何响应，`spec-00001-AC-1.2` 明写
  「节点位置由布局算法给出，无需手工摆放」，`decision-00002` §3 又否决了手工
  摆放。
- Trigger：plan-00024 给工作区外包一层面板组后，测试桩为面板报出真实盒尺寸，
  jsdom 里对节点控件的合成按下第一次真正抵达 React Flow 的节点拖动处理器，
  d3-drag 读 `event.view.document` 于 `view === null` 处崩溃（未处理异常）。
  崩溃是测试环境的表征；它暴露的是浏览器里也存在的手势层。

## 2. Impact

- Affected：节点卡片上的两个控件——会话标记按钮（`web/src/NodeCard.tsx:108`）
  与异常徽标弹层触发器（`web/src/NodeCard.tsx:149`）；触控板用户尤其容易在
  按下时带一点位移。
- Since：异常徽标自 `5939fc56`（2026-08-13）、会话标记自 `b267816e`
  （2026-08-23）· Still occurring：no（本 issue 已修）。
- Severity：低。有绕法（按下不动即可），无数据影响；但它让 plan-00024 的测试
  环境多了一处针对 d3-drag 的桩，修掉根因即可撤桩。

## 3. Root Cause (first principles)

1. 分歧陈述：按钮的 `click` 应无条件到达；实际是「按下→位移→松开」序列中
   `click` 不到达。
2. 机制：React Flow 对每个 `nodesDraggable`（缺省 `true`）的节点挂 XYDrag，
   它在节点 DOM 上绑 d3-drag 的 `mousedown`；只有带 `nodrag` 类的后代元素被
   过滤（`noDragClassName`）。`NodeCard.tsx:108` 与 `:149` 的两个 `<button>`
   都不带该类，于是按下先被 d3-drag 接管；一旦有位移，d3-drag 在 `mouseup`
   时以捕获阶段的 `click.drag` 监听吞掉随后的 `click`（`nodrag`/`yesdrag`
   的 `moved` 分支）。`Toolbar.tsx:155` 的浮窗工具栏带 `nodrag nopan`，正说明
   作者知道这一层，只是节点卡片自己的两个后来加的按钮漏了。
3. 真因是**画布对本板无意义的手势层仍处于开启态**：节点位置由 `layoutGraph`
   决定，`Board.tsx` 的 `ReactFlow` 不接 `onNodesChange`，拖动产生的位置变更
   无处可落——手势层唯一的效果就是吞点击。逐控件补 `nodrag` 只是把漏洞补到
   今天这两个按钮上，下一个加进卡片的控件会再漏。
   不是：按钮事件绑定错误（按下不动能点中）、Popover/Radix 的问题（会话标记
   是裸 `<button>` 同样受影响）。

- Introduced by：`5939fc56`（2026-08-13，UI 重建轮把 problems 从平铺改成
  `Popover` 触发按钮——节点上第一次出现可点控件）。在此之前节点卡片上没有
  任何按钮，手势层吞不到东西；`b267816e` 加会话标记按钮时沿用同一漏法。

## 4. Scope (same-cause sweep)

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `web/src/NodeCard.tsx:108` 会话标记按钮 | yes | yes | 随 `nodesDraggable={false}` 一并修 |
| `web/src/NodeCard.tsx:149` 异常徽标 `PopoverTrigger` | yes | yes | 同上 |
| `web/src/Toolbar.tsx:155` 浮窗工具栏 | 已带 `nodrag nopan` | no | 不动；`NodeToolbar` 本就渲染在节点外 |
| `web/src/SubNodes.tsx` 子画布节点 | 无内嵌控件（整节点 `onNodeClick` 开详情） | no | 随全局关闭一并不可拖，行为不变 |
| `web/test/setup.ts` 对 user-event 事件 `view` 的 `defineProperty` 桩 | 为绕过本手势层而加 | — | 已撤除 |
| 画布平移层（d3-zoom，`panOnDrag` 缺省开）对节点内按下的接管 | 同一机制的下一层：React Flow 只给**可拖动**节点自动加 `nopan`，关掉拖动后节点上的按下改由平移手势接管，位移后同样吞点击（撤桩后 `sessions.test.tsx:426` 于 `d3-zoom/src/zoom.js:279` 复现同一崩溃） | yes | `toFlowNodes` 给每个文档节点显式加 `nopan`，恢复此前「节点上不平移」的既有行为 |

## 5. Reproduction (test-first)

1. 先写失败测试：渲染白板，断言文档节点不携带 React Flow 的可拖动态
   （节点根元素无 `draggable` 类、且对会话标记按钮的「按下—位移—松开」不改变
   节点位置也不吞掉点击）。
2. 修复后通过。
3. 留作回归守卫。

- Failing test：`web/test/canvas.test.tsx::a document node is not draggable`
  ——修前失败于 `expected true to be false`（节点根元素带 `draggable` 类）。
- Failing test：`web/test/canvas.test.tsx::a press that moves on a node control
  still opens what it opens`——异常徽标上按下、移动 12px、松开、点击，修前
  失败于 `Unable to find an element with the text: no status`（弹层未开，点击
  被吞）；它同时守住 §4 末行的平移层。

## 6. Fix

- Change：`Board.tsx` 的 `ReactFlow` 加 `nodesDraggable={false}`；
  `canvasModel.ts` 的 `toFlowNodes` 给文档节点加 `className: 'nopan'`（关掉
  拖动后 React Flow 不再自动加它，见 §4 末行）；撤除 `web/test/setup.ts` 里为
  d3-drag 加的 `Object.defineProperty` 桩。
- Why root not symptom：关掉的是整个手势层，覆盖今天的两个按钮与将来任何节点
  内控件；与 `spec-00001-AC-1.2`、`decision-00002` §3 的立论一致。
- Alternatives rejected：逐控件加 `nodrag`——只修已知两处，下一个控件再漏；
  接 `onNodesChange` 让拖动生效——与「无需手工摆放」直接相悖；
  `panOnDrag={false}` 关掉整张画布的拖动平移——把用户翻画布的手段也一并
  拿掉，代价远大于问题。

## 7. Verification

- §5 的两条回归测试通过。
- `npm test`：56 个文件、1695 个测试全部通过，撤桩后零未处理异常；
  `npm run typecheck` 无错；`npm run test:coverage` statements 98.64% /
  branches 95.35% / functions 98.43% / lines 99.20%，阈值未动；
  `npm run build` 通过。

## 8. Follow-through

- Detection gap：既有测试全部用「按下即松开」点击节点控件，从未带位移；
  jsdom 的零尺寸盒又让面板分隔条命中检测把每次按下都 `preventDefault()`，
  按下从未抵达 d3-drag——两层遮蔽叠加。回归守卫即 §5 的测试。
- Doc verdict：**code was non-conformant**——`spec-00001-AC-1.2` 与
  `decision-00002` 已说清节点不由手摆，文档不改。
- Residual state：none。

## Links

- Blocks: spec-00001-docs-whiteboard（AC-1.2 的口径）· plan-00024-whiteboard-navigation-sidebar（其测试桩因本缺陷而生）
- Related: decision-00002-whiteboard-layout · record-00024-whiteboard-navigation-sidebar-acceptance
