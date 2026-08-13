---
id: issue-00001-toolbar-pans-the-canvas
type: issue
status: resolved
blocks: [plan-00002-whiteboard-ui]
---

# Issue: 点击浮窗工具栏会驱动画布平移

> 工具栏的按钮点击一路冒泡到 React Flow 的平移处理器，用户点「接收」时画布会
> 跟着动；在 jsdom 里它还会抛出未处理异常，污染测试输出。

## 1. Problem

- Observed: 点击浮窗工具栏中的任一按钮，React Flow 的 zoom/pan 处理器同时收到
  该 `mousedown`。在浏览器里表现为画布可能随指针轻微平移；在测试中表现为
  `npx vitest run` 输出 4 条 `TypeError: Cannot read properties of null
  (reading 'document')`。
- Expected: 工具栏是浮于画布之上的控件，对它的操作不应驱动画布。
  design-00002 §2 把工具栏定义为「浮于其上、不占布局」。
- Trigger: 工具栏改用 React Flow 的 `NodeToolbar` 贴节点悬浮后，它就位于
  `.react-flow__pane` 的子树内，指针事件因此会经过平移处理器。

## 2. Impact

- Affected: 使用白板的任何人——每次通过工具栏执行动作都可能让画布位移；以及
  读测试输出的人，4 条未处理异常会掩盖真实失败。
- Since: commit `5939fc56`（本次 UI 改造） · Still occurring: no（本 issue 已修）
- Severity: 中。功能不失效、断言不失败，但它同时污染交互与测试信噪比，且
  `vitest` 遇未处理异常仍返回 exit 0，CI 不会拦。

## 3. Root Cause (first principles)

1. 分歧：工具栏应「拦下」自己的指针事件，实际却让它们继续传到画布。
2. 最小机制：`web/src/Toolbar.tsx:61` 起用 React 的合成事件
   `onPointerDown`/`onMouseDown` 调 `stopPropagation`。React 17+ 把合成事件挂在
   根容器上，而 React Flow 的 d3-zoom 用**原生**监听器挂在
   `.react-flow__pane`——pane 是工具栏的祖先且位于根容器之下，所以原生监听器
   先于 React 的合成分发触发。合成事件的 `stopPropagation` 来得太晚。
3. 真正的根因：**用错了排除类名**。`Toolbar.tsx:61` 加的是 `nodrag`，那是
   React Flow 用来排除**节点拖拽**的类；排除**画布平移**的是 `noPanClassName`，
   默认值 `nopan`（`@xyflow/react` 的 `createFilter` 读它）。工具栏从未被
   排除在平移之外。
   它**不是**的症状：不是 jsdom 的 bug，也不是 user-event 的 bug——那两者只是
   让同一个缺陷在测试里以异常形式显形（见 §8）。

- Introduced by: `5939fc56`。在此之前工具栏是绝对定位在画布**之外**的普通 div，
  事件根本不经过 pane，因此该缺陷不可能发生。

## 4. Scope (same-cause sweep)

根因是「浮于画布之上的控件必须带 `nopan`」，凡渲染进 React Flow 子树的控件都
共享它。

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `web/src/Toolbar.tsx:61` | yes | yes | 本次修复：`nodrag` 改为 `nodrag nopan` |
| `web/src/NodeCard.tsx` | yes | no | 节点本身应当可拖动/可被画布平移，正是要参与这些手势 |
| `web/src/Board.tsx` 的空状态覆盖层 | yes | no | 带 `pointer-events-none`，不接收指针事件 |
| `web/src/CommandPalette.tsx`、澄清 `Dialog`、`Popover` | no | no | Radix 把内容 portal 到 `document.body`，在 React Flow 子树之外 |

## 5. Reproduction (test-first)

- Failing test: `web/test/canvas.test.tsx::does not pan the canvas when the
  toolbar is used` —— 监听 `window` 的 `error` 事件后点击工具栏按钮；修复前
  捕获到 `Cannot read properties of null (reading 'document')`（画布的平移
  处理器确实收到了该事件），修复后没有任何 error 事件。

## 6. Fix

- Change: `web/src/Toolbar.tsx` 的类名加上 `nopan`；合成事件的
  `stopPropagation` 保留（对 portal 之外的同层控件仍有意义），但不再是主要手段。
- Why this addresses the root cause and not the symptom: `nopan` 让 React Flow
  的事件过滤器在**原生**监听器里就放弃该手势，早于任何 React 分发；而不是在
  合成层追着已经发生的事去补救。
- Alternatives rejected: 在测试 setup 里给事件补 `view` —— 那只让异常消失，
  画布仍会被工具栏驱动平移，是掩盖而非修复。

## 7. Verification

- §5 的回归测试通过。
- `npx vitest run`：302 → 303 个测试全部通过，**未处理异常从 4 条降为 0**。

## 8. Follow-through

- Detection gap: 既有测试只断言「点了按钮 → 调了 API」，从不观察这次点击**还**
  做了什么。异常之所以只在这四条用例出现，是因为它们用 `userEvent.click`
  （其合成事件的实例自带 `view: null`，遮蔽了 `setup.ts` 打在原型上的桩），
  而点节点的用例用 `fireEvent`（`view` 正常）——两者的差异掩盖了缺陷的普遍性。
  除本次的回归测试外，未新增更宽的守卫：更好的守卫是让 vitest 在出现未处理
  异常时返回非零，但那是测试基建的改动，不属本 issue。
- Doc verdict: **code was non-conformant** —— design-00002 §2 已把工具栏定义为
  浮于画布之上，实现没做到；文档无需修改。
- Residual state: none。
