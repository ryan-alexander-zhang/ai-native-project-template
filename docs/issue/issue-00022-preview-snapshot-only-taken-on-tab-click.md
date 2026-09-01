---
id: issue-00022-preview-snapshot-only-taken-on-tab-click
type: issue
status: resolved
blocks: [plan-00023-doc-annotations]
---

# Issue: 预览快照只在点击预览页签时截取，外部切换视图态渲染空或过期内容

> `Editor` 的预览快照原先只在用户**点击预览页签**的处理器（`show()`）里
> 从缓冲截取。当视图态由外部驱动切到 `preview` 时——按文档保持值恢复、
> 标注定位、重选模式都会这么做——快照没人截，预览渲染空串或上一份文档
> 遗留的过期内容。plan-00023 T4 期间发现并按 effect 驱动修复。

## 1. Problem

- Observed: 视图态不经页签点击而变为 `preview` 时（如标注定位在预览侧
  唤起编辑器），预览区渲染为空或上次截取的旧缓冲，与当前文档不符。
- Expected: 预览渲染的是**当前缓冲区**正文（`CONTEXT.md`「预览」词条），
  无论视图态怎么到达 `preview`。
- Trigger: 任何外部驱动的视图态切换——T4 的定位（`spec-00007-AC-9.12`
  要求预览侧滚动高亮）首次让该路径成为常规通路，缺陷随即显形。

## 2. Impact

- Affected: T4 之前唯一的外部切态来源是「呈现状态按文档保持」的恢复
  路径（刷新后直接落在 preview 态），属低频；T4 的定位与重选把它变成
  高频通路，不修则 `AC-9.12` 无法成立。
- Since: 预览视图态引入时即存在 · Still occurring: no（本 issue 已修）。
- Severity: 中。无数据损坏，纯呈现错误，但呈现的是**错误的正文**。

## 3. Root Cause (first principles)

1. 分歧：同一个「切到预览」，点页签走 `show()`（截快照 + 置态），外部
   驱动只置态——两条路径对「快照何时截」的回答不同。
2. 最小机制：快照是 `show()` 的副作用，而视图态是可被外部写入的
   props/state；副作用没有跟随状态，只跟随了其中一个写入者。
3. 真正的根因：**把「派生数据」实现成了「事件副作用」。** 预览文本是
   「当前缓冲 + 当前视图态」的纯派生，理应由 effect 对这两个输入响应；
   挂在点击处理器上等于假设点击是唯一写入者——该假设在按文档保持值
   恢复引入时已经失效，只是当时无人走到。它不是渲染层缺陷（Preview
   组件对给定文本渲染正确），也不是保持机制的错（保持值本身正确）。

## 4. Scope (same-cause sweep)

同机制（事件副作用承载派生数据）扫 `Editor.tsx` 其余视图态：编辑态
直接持有 CodeMirror 缓冲、问题列表态与标注列表态由载荷驱动，均无
「切态时截取」的副作用，不受同因影响。无其他同类点。

## 5. Reproduction (test-first)

`tools/whiteboard/web/test/annotationEditor.test.tsx` 的
`marks the rendered passage in the preview`：以 `startOn: 'preview'`
（外部指定初始视图态，不经页签点击）打开编辑器并断言预览渲染出正文
与定位标记——修复前该用例因预览为空而红。

## 6. Fix

`Editor.tsx`：预览文本改为 effect 驱动——对（缓冲内容, 视图态）响应
截取，`show()` 不再持有截取副作用；随 plan-00023 T4 落地。

## 7. Verification

- 上述用例转绿；`web` 全套件 1634 通过、覆盖率门未降（T4 门禁）。
- 手动路径：预览态下从标注列表定位 → 预览滚动并高亮当前正文。

## 8. Follow-through

- 无遗留：同因扫描（§4）无其他站点；不需要新的守护性检查——组件
  测试已把「外部切态渲染当前正文」钉为回归面。

## Links

- Plan: [plan-00023-doc-annotations](../plan/plan-00023-doc-annotations.md)（T4 期间发现与修复）
- Spec: [spec-00007-doc-annotations](../spec/spec-00007-doc-annotations.md)（`spec-00007-AC-9.12` 是显形通路）
