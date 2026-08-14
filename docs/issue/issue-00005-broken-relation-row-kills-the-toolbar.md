---
id: issue-00005-broken-relation-row-kills-the-toolbar
type: issue
status: resolved
blocks: [spec-00001-docs-whiteboard, plan-00004-whiteboard-edge-emphasis]
---

# Issue: 点击断链的关系项，工具栏无声消失

> 关系列表把断链也列了出来（这是对的），但点它会去选中一个不存在的文档：
> 工具栏随即消失、没有任何提示，两条 promise rejection 无人接管。

## 1. Problem

- Observed: 选中一个节点 → 打开关系列表 → 点击一条标着 `missing` 的关系项。结果：
  浮窗工具栏**直接消失**，没有提示条、没有任何解释；测试环境下抛出 2 条
  `Unhandled Rejection`（`{ status: 409 }`）。
- Expected: 该项指向的文档不存在，这是它被标为 `missing` 的原因。点它要么不可点，
  要么明确告诉用户「这份文档不存在」，而不是让当前视图静静塌掉。
- Trigger: `docs/` 中存在断链（某个关系字段指向不存在的 id），且用户从关系列表
  点了那一项。本仓当前就有三条这样的边（见 §8）。

## 2. Impact

- Affected: 任何用关系列表去查断链的人——而**查断链正是这份列表最有价值的用途**
  （`spec-00001-AC-30.5` 专门要求列出它们）。用户点下去，界面塌了，还不知道为什么。
- Since: 本轮改动（关系列表首次引入可点击的行） · Still occurring: no（本 issue 已修）
- Severity: 中。不损坏数据，也不影响其他操作；但它出现在一条被 spec 明确设计过的
  路径上，且失败方式是「无声」——最难自查的一种。

## 3. Root Cause (first principles)

1. 分歧：`focus()` 假定它拿到的 id 一定对应画布上的一个节点。关系列表打破了这个
   假定——它是第一个能交出**不存在的 id** 的调用方。
2. 最小机制：`web/src/Toolbar.tsx` 的关系项 → `web/src/Board.tsx` 的 `focus(id)`
   → `void board.select(id)`。`web/src/useBoard.ts` 的 `select()` 先
   `setSelected(id)`，再去取 `api.transitions(id)` 与 `api.nextSteps(id)`。两件事
   随之发生：
   - `selectedNode` 由 `graph.nodes.find(...)` 得出，对幽灵 id 是 `undefined`，
     于是 `Board` 里 `selected ? <NodeToolbar> : null` 这一支不再渲染——工具栏消失；
   - 服务端对不存在的文档回 409，`select()` 不 catch，调用点又是 `void`，于是
     rejection 无人接管。
3. 真正的根因：**`select()` 没有守卫**。它把「选中」当成一个纯粹的状态赋值，而
   实际上它只对图中存在的节点有意义。此前所有调用方（点画布节点、命令面板）都
   只会交出真实 id，所以这个缺口一直没有被触到——直到关系列表按 `AC-30.5` 的
   要求把断链也列出来。
   它**不是**的症状：不是关系列表的数据错了（它正确地列出了断链并标记了它），
   也不是服务端错了（对不存在的文档回 409 是对的）。

- Introduced by: 本轮 `plan-00004` 的 E3。在此之前没有任何 UI 能交出幽灵 id。

## 4. Scope (same-cause sweep)

根因是「`select()` 接受任意 id 而不校验」。

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `web/src/useBoard.ts` 的 `select()` | yes | yes | 修复点：不存在的 id 一律拒绝并提示，不改变当前选中 |
| `web/src/Toolbar.tsx` 关系列表的行 | yes | yes | 断链项不再是按钮——没有可去的地方就不该假装可以去 |
| `web/src/Board.tsx` 的 `onNodeClick` | yes | no | id 来自画布上真实存在的节点 |
| `web/src/CommandPalette.tsx` → `focus()` | yes | no | 候选项来自 `graph.nodes`，恒为真实 id |
| `Editor` 的保存后刷新 | no | no | 不触碰选中项 |

## 5. Reproduction (test-first)

- Failing test: `web/test/canvas.test.tsx::refuses to jump to a relation whose
  document does not exist` —— 图中含一条 `ok: false` 的边，打开关系列表点击该项。
  修复前：工具栏消失、无提示、抛出 unhandled rejection；修复后：工具栏保留、
  出现一条说明该文档不存在的提示条，且无 unhandled rejection。
- 已实测确认修复前的行为：`TOOLBAR STILL THERE: false`、`TOAST SHOWN: false`、
  `Errors 2`。

## 6. Fix

- Change:
  1. `useBoard.select()` 加守卫——id 不在 `graph.nodes` 中时提示并原样返回，不改
     选中、不发请求。这是根因所在，任何未来的调用方都自动受益。
  2. `Toolbar` 的断链关系项渲染为非按钮的静态行（保留 `missing` 标记）。没有可去
     之处就不呈现为可点击——这是把「拒绝」提前到交互形态上，而不是等用户点了再拒。
- Why this addresses the root cause and not the symptom: 症状是「工具栏消失」，
  若只去修渲染分支（比如选中项不存在时保留上一次的工具栏），幽灵选中仍然存在、
  请求仍然会发、rejection 仍然无人接管。守卫放在 `select()` 才是把不变量补回它
  应该在的地方。
- Alternatives rejected: 在 `focus()` 里 catch —— 只堵住这一个调用点，下一个调用
  方会重新踩到；且它把「选中一个不存在的东西」当成可恢复的正常路径。

## 7. Verification

- §5 的回归测试通过：`web/test/canvas.test.tsx::refuses to jump to a relation
  whose document does not exist` —— 断链项照常列出并标着 `missing`，但**不是
  按钮**；点击尝试之后工具栏仍在，且**没有任何 unhandled rejection**（修复前
  实测为工具栏消失、无提示、2 条 rejection）。
- `useBoard.select()` 的守卫另有一层意义：即便未来某个调用方交出幽灵 id，得到的
  也是一条提示条，而不是塌掉的界面。
- 全量 367 个测试通过，无未处理异常。

## 8. Follow-through

- Detection gap: 关系列表的测试都用了存在的对端 id；`AC-30.5` 只断言断链**被列出
  并标记**，没有断言**点它会怎样**。这是同一条 AC 的两半，只写了一半。修复时补上
  的正是后一半。
- Doc verdict: **the doc was wrong or missing** —— `spec-00001-FR-30` 说「点击其中
  一项即定位并选中该对端文档」，却没说对端不存在时怎么办，而同一条 FR 又要求把
  断链列出来。二者合起来必然产生这条路径。已补：FR-30 增加「断链项不可点击」一句，
  并新增 `spec-00001-AC-30.6`。
- Residual state: 本仓当前有三条断链边，全部来自 `record-00003` 的
  `verifies: [spec-00001-FR-28, …]`——那是 `docs/record/README.md` 明确许可的写法
  （「requirement ids down to `spec-00001-AC-1.1` granularity」），但白板的图模型
  把每个关系目标都当作**文档 id**，于是它们成了断链。这不是本 issue 的根因，而是
  白板与文档约定之间的一处真实冲突，需单独裁定：要么白板识别 requirement id，
  要么 record 只写文档 id。在裁定之前，白板会一直显示「3 issues」。
