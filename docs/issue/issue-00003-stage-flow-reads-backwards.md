---
id: issue-00003-stage-flow-reads-backwards
type: issue
status: resolved
blocks: [design-00001-docs-whiteboard, design-00002-whiteboard-ui, spec-00001-docs-whiteboard, record-00001-docs-whiteboard-acceptance]
---

# Issue: 布局把阶段流画反了

> design-00001 说分层布局「贴合 docs 的阶段流方向」，实际 `spec` 在最上、
> `idea` 在最下。关系边指向的是**来源**，而布局把边的起点当成上游。

## 1. Problem

- Observed: 用本仓真实的 `idea-00001 ← prd-00001 ← spec-00001`（`parent` 链）
  跑 `layoutGraph()`，得到的坐标是

  ```
  spec-00001-x y=12    prd-00001-x y=200    idea-00001-x y=388
  ```

  即 `spec` 在顶、`idea` 在底，阶段流自下而上读。
- Expected: `web/src/layout.ts:17` 的注释写的是「the docs flow
  (idea -> prd -> spec -> plan) reads as depth」，design-00001 §1 选型表写的是
  「分层布局贴合 docs 的阶段流方向」。二者都承诺阶段流顺着布局方向读。
- Trigger: 无条件。凡由声明在**依赖方**的关系字段（`parent`、`implements`、
  `motivated_by`、`verifies`、`supersedes`）相连的文档对，阶段顺序都是反的；
  本仓的 `parent` 链（idea → prd → spec）即上例。

## 2. Impact

- Affected: 白板的每一个使用者。不过在 `issue-00002` 修复之前**没有人看得见**
  这个反向，因为边一条都没画出来——两个缺陷互相遮掩。
- Since: commit `3156bbd5`（布局首次落地） · Still occurring: no（本 issue 已修）
- Severity: 中。图仍可用，但阅读方向与文档流程相反，而「看清依赖链」正是白板
  的立论。

## 3. Root Cause (first principles)

1. 分歧：布局把**关系边的起点**当成上游，而关系边的起点是**下游**那一份。
2. 最小机制：`web/src/layout.ts:30` 把每条关系边按
   `{ sources: [edge.from], targets: [edge.to] }` 喂给 ELK，配合
   `web/src/layout.ts:24` 的 `'elk.direction': 'DOWN'`——ELK layered 把
   source 放进更靠前的层，于是 `from` 在上、`to` 在下。而 `docs/README.md` 的
   「Declare each edge once, on the document that depends on the other」意味着
   `from` 恰恰是**依赖方**（下游）：`prd` 的 front matter 写 `parent: idea`，
   所以边是 `prd → idea`，ELK 把 `prd` 排在 `idea` 之上。
3. 真正的根因：**把「依赖方向」直接当成了「阶段方向」**，而在本文档体系里，
   声明在依赖方的那五个字段恰好与阶段方向相反（另有 `informs`、`constrains`、
   `blocks` 三个字段声明在被依赖方，方向再次不同）。更根本的是：**阶段顺序
   根本不是关系图的拓扑序**——它是一份人定的阅读顺序，从任何一组边里都推不
   出来。所以修法不是翻转方向，而是让层次不再来自边（§6）。
   它**不是**的症状：不是 ELK 的 bug（它严格按给定的边分层），也不是
   `docRepository` 的 bug（`from`/`to` 完全符合 front matter 的声明）。

- Introduced by: `3156bbd5`。同一 commit 写下了「贴合阶段流方向」的注释与与之
  相反的实现。

## 4. Scope (same-cause sweep)

根因是「把关系边当作阶段流方向的边」。

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `web/src/layout.ts:24,30` | yes | yes | 本次修复：布局不再从关系边推层次，见 §6 |
| `web/src/canvasModel.ts:20` `toFlowEdges()` | 部分 | no | 它按声明方向出边且当前不画箭头，没有做出方向主张；本次改造后箭头明确指向被引用文档（decision-00002） |
| `src/docRepository.ts:129` `toEdges()` | no | no | 产出的就是声明方向，是正确的数据 |
| `src/workflow.ts` `nextStepsFor()`、`src/advance.ts` | no | no | 推进候选取自流程配置的 `flow`，从不读关系边 |

## 5. Reproduction (test-first)

- Failing test: `web/test/board.test.tsx::places each type in its own column,
  left to right` —— 给定 `idea-00001`、`prd-00001`、`spec-00001` 三份文档，
  断言三者的 x 严格递增且 y 相同。当前实现下 x 全相等、y 递减（方向既反且
  维度也不对），因此失败。
- 第二条：`::stacks documents of the same type in one column` —— 给定
  `spec-00001` 与 `spec-00002`，断言二者 x 相同、`spec-00001` 的 y 更小。

## 6. Fix

- Change: 布局改为**列＝类型、行＝同类型内的 id 序**，方向左→右，不再从关系边
  推导层次。这是一次布局引擎的更换（去掉 elkjs），其取舍记在
  `decision-00002-whiteboard-layout`，落地见 `plan-00003`。
- Why this addresses the root cause and not the symptom: 根因是「层次来自关系
  边」，修法是**让层次不再来自关系边**——列由类型决定，与边彻底解耦，于是无论
  各字段的方向语义如何都不再能影响布局。只翻转 `elk.direction` 或翻转喂进去的
  边，层次仍然来自边，同类型文档照旧被拆到不同列、位置照旧随文档增删重排。
- Alternatives rejected:
  - **翻转边再喂 ELK**：方向也许能对，但层次仍来自边，于是同类型文档会因彼此
    有边而落到不同列（`spec-00002 supersedes spec-00001` 被拆成两列），且位置
    仍随文档增删重排。
  - **保留 ELK，改用 interactive 分层**：需要额外提供每个节点的位置提示，等于
    我们自己先算出列，ELK 只剩层内排序；而层内我们要的恰是可预期的 id 序，
    不是交叉最小化的结果。
- Doc change required: design-00001 §1 选型表与 §2 模块图（不再是 ELK）、
  design-00002 §2（画布布局）、`spec-00001-FR-1` 及其新增 AC。

## 7. Verification

- §5 的两条回归测试通过（`web/test/board.test.tsx` 的
  `places each type in its own column, left to right` 与
  `stacks documents of the same type in one column, by id`）。
- 用本仓真实的 17 份文档实跑 `readGraph → layoutGraph`，得到 9 列：

  ```
  x=   0 idea      x= 336 prd       x= 672 spec      x=1008 rule    x=1344 decision
  x=1680 design    x=2016 plan      x=2352 issue     x=2688 record
  ```

  `idea` 最左、`record` 最右；`decision-00001` 在 `decision-00002` 之上、
  `design-00001` 在 `design-00002` 之上、`plan-00001…3` 依序自上而下。阶段流
  与阅读方向一致，缺陷不再出现。

## 8. Follow-through

- Detection gap: `web/test/board.test.tsx` 对布局只断言了两件事——空图返回空
  数组、断链的边不把幽灵节点拖进布局。**没有任何一条断言涉及方向或相对位置**，
  所以一个方向完全相反的布局可以全绿通过。新增的两条回归测试正是补这个维度。
- Doc verdict: **both** —— 代码违反了 design-00001「贴合阶段流方向」的主张；
  同时该文档本身也不足：它从未说清方向是上下还是左右、同类型文档如何摆放，
  这些空白正是缺陷得以长存的原因。两侧都改。
- Residual state: `record-00001:28` 为 `spec-00001-AC-1.2` 记的证据是「ELK 实跑，
  y 不相等」，该证据随本次修复失效，须换成新布局的用例。

## Links

- Blocks: design-00001-docs-whiteboard, design-00002-whiteboard-ui,
  spec-00001-docs-whiteboard, record-00001-docs-whiteboard-acceptance
- Related: issue-00002-relation-edges-never-render（同一区域、不同根因，且两者
  互相遮掩）、decision-00002-whiteboard-layout、plan-00003-whiteboard-relation-edges
