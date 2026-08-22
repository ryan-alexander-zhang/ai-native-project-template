---
id: issue-00006-stale-width-recentre-clips-the-toolbar
type: issue
status: resolved
blocks: [plan-00006-whiteboard-text-rendering]
---

# Issue: 检视面板挂载的同一帧重居中，用过期宽度把工具栏推到面板底下

> 面板从无到有的那次选中，节点被居中到**全宽**画布的中点；面板随即占走右侧
> 608px，浮窗工具栏右端因此压进面板底下——Advance 按钮被盖掉约 68%，只剩一个
> 无字图标。

## 1. Problem

- Observed: 从子画布点面包屑「Board」返回、或经命令面板选中一个 spec/rule
  （检视面板此前关闭）时，工具栏右端 x=1065 而面板左沿 x=992，Advance 按钮
  99px 被盖 67px，等待 5s 不自愈。
- Expected: 工具栏完整可见——design-00002 §9「视口修正」明文：返回顶层后浮窗
  工具栏不得被检视面板裁边（record-00004 观察项 4 的处置）。
- Trigger: 任何「选中即令检视面板从无到有挂载」的路径：面包屑返回
  （spec-00001-AC-36.1 场景）、命令面板选中（AC-27.2 场景）、直接点选节点。
  面板已在场时再次选中则不复现。

## 2. Impact

- Affected: 所有 spec/rule 节点的选中后首屏——恰恰是本仓最常用的两个中枢文档；
  被盖住的是推进（Advance）入口。
- Since: plan-00005 引入检视面板起（961d0b74）；plan-00006 的 U3 试图以
  「重居中」修复，实测（2026-08-17）证实未修复。Still occurring: no（本 issue 已修）。
- Severity: 中——功能可用（放大或拖动可见），但首屏即缺一个主要动作入口，
  且 record-00004 已记录过一次、宣称修复后仍在。

## 3. Root Cause (first principles)

1. 期望「居中到**面板挂载后**的画布中点」，实际「居中到面板挂载**前**的画布
   中点」。
2. 机制：`web/src/Board.tsx` 的 `pendingFocus` 补偿 effect 与检视面板的挂载
   发生在同一次 React commit；此时 React Flow 的 `ResizeObserver` 尚未上报
   画布的新宽度，`setCenter` 读到的仍是全宽（实测 1600px → 中点 800；对照组
   面板已在场时画布 991px → 中点 496，工具栏完好）。
3. 真根因是**时序**：以「渲染完成」当作「布局完成」。不是 setCenter 的坐标
   算错（同一坐标在窄画布下正确），也不是工具栏定位问题（NodeToolbar 忠实
   跟随节点）。

- Introduced by: 961d0b74（面板占槽）；plan-00006 U3 的重居中把窗口缩小到
  一帧但没有消除。之前工具栏永不与右侧面板同场，缺陷无从发生。

## 4. Scope (same-cause sweep)

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `web/src/Board.tsx` `pendingFocus` 补偿 effect | yes | yes | 在此修 |
| 面包屑「Board」返回路径（复用 focus()） | yes | yes | 同一处修复覆盖 |
| 命令面板选中路径（复用 focus()） | yes | yes | 同一处修复覆盖 |
| 编辑器关闭交还右槽（AC-31.9） | 面板→面板等宽切换，宽度不变 | no | 不涉及 |
| 详情面板挂载（子画布内） | yes | no | 子画布无浮窗工具栏，无可裁之物 |

## 5. Reproduction (test-first)

1. 写一个失败的测试：面板从无到有的选中路径，断言居中所用宽度为面板挂载后的
   画布宽度（jsdom 下经 `setCenter` mock 的实参 + 可配置的 ResizeObserver 桩
   驱动宽度变化）。
2. 修复后转绿，保留为回归守卫。

- Failing tests（`web/test/viewport.test.tsx`，修复前的失败输出即根因本身）：
  `waits for the narrowed canvas when the command palette picks one` —
  `expected 1600 to be 991`；`… when the breadcrumb comes back up` — 同上；
  `… when a node is clicked` — `expected undefined to be 991`（该路径原先根本
  不重居中）；另有 minZoom 两条（见 §6 第二项）。ResizeObserver 桩为此扩展为
  可按用例上报尺寸变化（`web/test/setup.ts` 的 `resizeSizes`/`reportResize`）。

## 6. Fix

- Change: 重居中不再以「检视面板渲染了」为契机，改为订阅 React Flow 自己上报
  的画布宽度（`useStore` 的 `width`）：`pendingFocus` 记下请求时的宽度，effect
  只在上报宽度**变化后**执行——等的是布局真正稳定，而不是把一次 React commit
  当作布局完成；不用任何定时器。选中不改变画布宽度时不重居中，面板在场时改选
  不发生跳动。三条进入路径（面包屑、命令面板、直接点选）汇入同一机制。
  随手收编同轮副作用：`minZoom` 不再全局 0.005——顶层回到 React Flow 默认
  0.5，仅子画布按其包围盒推导（上限 0.5），退出即恢复。
- Why this addresses the root cause and not the symptom: 根因是「拿渲染完成当
  布局完成」的时序错位；本修复直接以布局事实（尺寸上报）为触发条件，而不是把
  坐标再补偿一次。
- 行为变化一处，明记：直接点选一个会令面板从无到有的节点，现在也会重居中
  （仅发生在画布真正变窄的那次转换上）——修复第三条路径的必然结果。

## 7. Verification

- `web/test/viewport.test.tsx` 6 条全绿（回归守卫）；全套件 25 文件 483 测试
  通过，typecheck/build 干净。
- 实测（1600×900 视口，spec-00001）：命令面板路径与面包屑返回路径，工具栏
  右端均为 x=760、面板左沿 x=992、Advance 按钮完整 99px、间距 232px（修复前
  1065 vs 992、按钮被盖 67px）；顶层缩到底 scale=0.5、卡片 120×46px 可辨；
  子画布 363 节点仍全部入初始视口（AC-35.7 在新地板下继续成立）。

## 8. Follow-through

- Detection gap: 「工具栏避让右槽」在 design-00002 §8 被裁定为只走实测、不写
  GWT——于是自动化对它是盲的，第一次「已修复」的宣称没有任何测试背书。本次
  §5 的六条回归测试即补上的守卫（居中宽度四条 + 缩放地板两条）。已知残留：
  选中不改宽度的文档时 `pendingFocus` 会滞留，其后一次窗口 resize 会对仍选中
  的节点补一次居中——与修复前实现同寿命，非回归；进入子画布的 fitView 仍在
  面板交还槽位的同帧执行、按偏窄宽度 fit（无害：更窄的框只会更保守），同型
  时序留待需要时再动。
- Doc verdict: **code was non-conformant**——design-00002 §9 的要求本身无误。
- Residual state: none。

## Links

- Blocks: plan-00006-whiteboard-text-rendering（验收路径第 4 条 (d) 项）
- Related: record-00004（观察项 4 的首次记录）
