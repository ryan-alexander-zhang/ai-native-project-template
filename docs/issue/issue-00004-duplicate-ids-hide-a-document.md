---
id: issue-00004-duplicate-ids-hide-a-document
type: issue
status: resolved
blocks: [spec-00001-docs-whiteboard, spec-00002-whiteboard-governance, plan-00012-whiteboard-governance-gates]
---

# Issue: 两份文档撞 id 时，一份从白板上消失，动作还会落到另一份上

> id 被整个系统当作唯一键，却从未校验唯一性。撞 id 时白板只画出其中一份，
> 而编辑、状态切换、评审全部作用在**另一份**上。

## 1. Problem

- Observed: 两个文件的 front matter 写同一个 id（例如都写
  `spec-00002-something`）时，实测渲染结果：
  - 画布上只有**一个**节点，内容是**后一个**文件的（先扫到的那份被顶掉）；
  - 顶栏异常计数为 `no issues`，两个节点都是 `ok: true`；
  - React 没有任何重复 key 告警——它不是 React 的去重，是 React Flow 的节点
    索引按 id 建表、后写覆盖先写。
- Expected: 撞 id 是 front matter 非法的一种，应按 `spec-00001-FR-2` 标为异常并
  保持其余图可用；无论如何不该让一份文档从视图里无声消失。
- Trigger: `docs/` 下存在两份 id 相同的文档。两人各自新建、或复制一份文档改内容
  忘了改 id，都会到达。

## 2. Impact

- Affected: 使用白板的任何人。两个后果，第二个更重：
  1. **一份文档不可见**，且没有任何提示说它存在。
  2. **动作会落到看不见的那一份上**。`src/docRepository.ts:177` 的 `findNode`
     取**第一个**匹配，而画布显示的是**第二个**。于是用户点开可见节点、按下
     「编辑」或「接收」，服务端改的是另一个文件——用户从未看过它的内容。
     这条路径经 `src/docService.ts:104` 通向写盘与 commit。
- Since: commit `adae2b17`（doc repository 首次落地） · Still occurring: yes
- Severity: 高（后果），低（触达概率）。它要求先有一次 id 撞车；但一旦发生，
  白板会在用户不知情的情况下修改一份未展示的文档。

## 3. Root Cause (first principles)

1. 分歧：id 在系统各处被当作**唯一键**（节点索引、关系目标解析、`findNode` 的
   查找、命令面板的跳转），但没有任何一处校验它真的唯一。
2. 最小机制：`src/docRepository.ts:93-102` 的 `frontMatterProblems()` 校验三件
   事——id 存在、匹配 `<type>-<nnnnn>-<slug>`、前缀与 `type` 一致——**唯一性不在
   其中**。于是 `src/docRepository.ts:135` 的 `docs.map(toNode)` 一文件一节点地
   产出两个同 id 节点，两者 `ok: true`；到了前端，React Flow 的节点表按 id 建
   索引，后者覆盖前者。
3. 真正的根因：**唯一性是 id 这个概念的前提，却被当成了输入数据的既有属性**。
   `docs/README.md` 只说 id 的形状（`<type>-<nnnnn>-<slug>`），从未把唯一性写成
   一条可校验的约束，于是解析层没有理由去查它，而下游每一处都假定它成立。
   它**不是**的症状：不是布局问题（节点根本没进画布），也不是 React 的
   重复 key 问题（无告警，且被覆盖的是数据而非 DOM）。

- Introduced by: `adae2b17`。此前没有图模型，也就没有以 id 为键的索引。

## 4. Scope (same-cause sweep)

根因是「以 id 为键但不校验唯一」，凡按 id 索引或查找的地方都共享它。

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `src/docRepository.ts:93` `frontMatterProblems()` | yes | yes | 修复点：唯一性校验应加在这里（它是异常的唯一产地） |
| `src/docRepository.ts:177` `findNode()` | yes | yes | 取第一个匹配，与画布展示的不是同一份——动作错位的直接来源 |
| `src/docRepository.ts:141` `knownIds` | yes | yes | `Set` 把两份塌成一个条目，指向该 id 的关系边一律判为 `ok`，无法分辨指的是哪一份 |
| `src/docRepository.ts:143` issue 路径归属 | yes | yes | 同样 `nodes.find`，断链报错会指到错的文件路径上 |
| `web/src/canvasModel.ts:6` `toFlowNodes()` | yes | 传导 | 忠实产出两个同 id 节点，覆盖发生在 React Flow 内 |
| `web/src/canvasModel.ts:35` `matchDocuments()` | yes | 部分 | 命令面板会列出两条，但选任一条都跳到同一个节点 |
| `src/workflow.ts` `allocateNumber()`/`highestNumber()` | no | no | 取的是已用编号的最大值，不会主动造出重复 |

## 5. Reproduction (test-first)

计划的两条已随修复落地为回归护栏（见 §7）：

- `test/docRepository.test.ts` 的 `describe('documents that collide on an id')`
  —— 两个文件写同一个 id，断言两个节点都 `ok: false`、`problems` 各自点名对方
  的文件路径、节点键各是自己的路径。
- `web/test/board.test.tsx` 的节点标签用例与 `web/test/canvas.test.tsx` 的命令
  面板用例：两份同 id 文档产出两个各自可定位的节点，而不是一个。
- 复现观察点已实测过（本 issue §1 的三条结论均来自一次真实渲染）。

## 6. Fix

- **裁定已作出，修复已落地**（`spec-00002-FR-8`/`FR-9`）：两份都标异常各占
  一格，节点键改用文件路径；异常清单的「id 不唯一」由 `spec-00002-FR-8` 承载
  （它沿用 `spec-00001-FR-2` 的异常语义，不改写 `spec-00001`）。以下为当时
  推迟的记录，保留原文。
- ~~**本次不修**~~。它先于本轮改动存在，且需要一次产品裁定：撞 id 的两份文档在
  白板上如何呈现——两个都标异常各占一格（`type` 相同，故同列上下），还是合成
  一处并列出冲突路径。裁定后 `spec-00001-FR-2` 的异常清单需加入「id 不唯一」，
  并补对应 AC。
- 拟定方向：唯一性校验加在 `frontMatterProblems()`，需要它看到全体文档（当前
  签名只看单份），因此校验位置要上移到 `buildGraph()`——这是本修复唯一的结构性
  改动。`findNode` 在撞 id 时应明确拒绝而不是取第一个。
- **没有任何一半被顺带做掉**。`plan-00003` 把同列排序改为 `(id, path)` 全序，
  那只让 `layoutGraph` 的**返回值**不再出现两个相同坐标；而 `toFlowNodes` 是按
  id 去查位置的，两个同 id 节点查到同一条，且其中一个在 React Flow 的节点表里
  就被覆盖了。落地后实测复核：仍是一个节点、渲染的是后一份、`findNode` 返回前
  一份——与 §1、§2 完全一致。

## 7. Verification

已修复并验证（`spec-00002-FR-8`/`FR-9`，`plan-00012` T4）。裁定落在
`spec-00002-FR-8`：**两个都标异常各占一格**，节点键改用文件路径。

- `docRepository.buildGraph` 在 `docs.map(toNode)` 之后过一遍 `markDuplicates`：
  凡一个 id 落在两份及以上文件上，每一份都改以**文件路径**为节点键、置
  `ok = false`、problem 点名其余同 id 文件，并记下 `duplicateOf`。§5 计划的
  `test/docRepository.test.ts` 那条落地为
  `describe('documents that collide on an id')`，覆盖 `AC-8.1`…`AC-8.3`、
  `AC-8.6`、`AC-8.10`、`AC-8.11`。
- §4 扫到的下游因改键而自动收敛：`knownIds` 与 `itemOwners` 都命不中那个撞的
  id，指向它的边判为断链；`findNode` 不再「取第一个」——它一个也取不到，
  `DocService.require` 因此改为先查 `duplicateOf` 并抛 `ConflictError`（409，
  消息要求先修复 id 冲突），动作再也落不到看不见的那一份上（`AC-9.2`、
  `AC-9.3`）。
- §4 判为「no」的 `highestNumber`/`allocateNumber` 因改键**反转成 yes**并一并
  修掉：取号按「声明的 id」＝`duplicateOf ?? id` 数，撞掉的编号不会被再发一次
  （`rule-00001-BR-18`）；`DocService.create` 的存在性校验同样按它判。
- 前端：`matchDocuments` 加匹配 `duplicateOf`（`AC-8.4`、`AC-8.5`），
  `NodeCard` 第四行并列路径与撞的 id（`AC-8.1`）。呈现状态按节点键即路径保持
  （`AC-8.9`）。按路径寻址的编辑保存照常落盘，这就是修复通路（`AC-9.4`）——
  它依赖 `issue-00016` 先补上 URL 编码。

§5 计划的画布那条以 `web/test/board.test.tsx` 的节点标签用例与
`web/test/canvas.test.tsx` 的命令面板用例落地：两份同 id 文档产出两个各自可
定位的节点，不再是一个。

## 8. Follow-through

- Detection gap: 没有任何一层校验 id 唯一。`frontMatterProblems()` 的三条检查
  都是「单份文档自身合法吗」，而唯一性是**跨文档**的性质，现有结构里没有任何
  一处在跨文档层面校验 front matter。这是一个结构性盲区，不止 id：例如
  「`parent` 成环」同样无人检查。
- Doc verdict: **the doc was wrong or missing** —— `docs/README.md` 定义了 id 的
  形状却没把唯一性写成约束，`spec-00001-FR-2` 的异常清单里也没有它。两处都要补，
  修复时一并处理。
- Residual state: 若仓库中当前已存在撞 id 的文档，它们在白板上一直是隐藏的。
  本仓已核对：17 份文档 id 互不相同，无既存受害者。

## Links

- Blocks: spec-00001-docs-whiteboard · spec-00002-whiteboard-governance
  （撞 id 的呈现与寻址裁定落在 spec-00002 的 FR-8 与 FR-9）
- Related: plan-00003-whiteboard-relation-edges（其 `(id, path)` 排序与本 issue
  相邻但不构成修复）、decision-00002-whiteboard-layout
