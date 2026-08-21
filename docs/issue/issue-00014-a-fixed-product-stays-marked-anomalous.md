---
id: issue-00014-a-fixed-product-stays-marked-anomalous
type: issue
status: resolved
blocks: [plan-00011-whiteboard-revision-create-and-session-reach]
---

# Issue: 产物在盘上修好了，白板还标它异常

> 推进产物一旦被 FR-17 判为不合规，那个异常标记就粘在节点上：用户按提示把
> 关系补齐、刷新，节点照旧红着——只有下一次推进会话才会把它抹掉。

## 1. Problem

- Observed: 推进产出的 prd 缺 `parent`，节点标异常（正确）；用户在编辑器里
  补上 `parent: idea-00001-x` 并保存，白板刷新，节点仍带
  `parent does not point at idea-00001-x`。此后每一次 `GET /api/graph`
  都继续附加该标记，直到下一次推进会话结束才被清除或改写。
- Expected: 标记由磁盘当前内容导出——文档修好即不再标异常。
  spec-00001-FR-42「刷新反映最新状态」与 FR-44「不产生第二套数据」，
  以及 FR-2「异常是文档当前 front matter 的读数」都这么承诺；
  修订后的 FR-17 与 AC-17.3 把这句话写成了明文。
- Trigger: 任何被 FR-17 标为异常的推进产物，在没有新推进会话的情况下于盘上
  被修复（编辑器保存、答疑会话改写、板外编辑皆同）。

## 2. Impact

- Affected: 全部推进产物的修复路径。用户被告知"补上关系"，补完了白板却不
  认账；唯一的解锁手段是再跑一次推进（会新建第二份文档）或重启服务。
- Since: 第五轮 FR-17 的产出校验落地（`lastFinding` 引入）· Still
  occurring: yes
- Severity: 中高——它把"修好"这条路堵死，而"标记异常并让用户去修"正是 FR-17
  设计中的唯一出路；且它使白板呈现与磁盘事实长期不一致，违背 FR-42…FR-44 的
  单一事实来源承诺。

## 3. Root Cause (first principles)

1. 异常标记应当是**导出值**（由磁盘现状按 FR-17 的规则算出），实现里却是
   **状态**（一次会话算出的结论，存起来反复贴）。二者只在"文档此后不变"时
   等价，而 FR-17 的整个用意就是让文档改变。
2. 最小机制：`tools/whiteboard/src/server.ts:35` 的 `lastFinding` 保存的是
   *problems 字符串数组*，`server.ts:51-54` 的 `graph()` 每次都把它原样贴回
   图上；写入点只有 `server.ts:86-97` 的 `finishAdvance`——即只有下一次推进
   会话结束才会重算或清空。图构建本身（`docRepository.ts` 的 `readGraph`）
   全程按磁盘现状工作，与之无关。
3. 真根因是**结论被缓存成状态而失效信号只接了一路**（下一次推进），不是
   `productProblems` 的判定错（它给的读数在当时是对的），也不是刷新链路没
   接通（信号照常到、图照常重取——重取回来的图又被同一个陈旧标记盖住）。

- Introduced by: 第五轮引入 FR-17 产出校验时把发现存进 `lastFinding`。在那
  之前不存在会话产物的额外标记，本缺陷无从发生。

## 4. Scope (same-cause sweep)

同形机制是"服务端把一次算出的结论存起来，在后续响应里反复附加"。

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `tools/whiteboard/src/server.ts:35,51-54,94` `lastFinding` | yes | yes | 改存 `Expectation`，每次图构建按磁盘重验，通过即清除 |
| `SessionInfo.outcome`（`sessionManager.ts:52`） | yes | no | 它是**那次会话的史实**（当时提交了什么、当时发现了什么），不是文档现状的读数；会话历史（FR-54）同理 |
| `DocGraph.issues` / `diagnostics` | no | no | 每次 `readGraph` 从磁盘重算，无缓存 |
| 图解析缓存（本轮新增） | no | no | 缓存的是解析结果本身，按变更失效；不叠加任何独立结论 |

## 5. Reproduction (test-first)

1. 推进会话产出一份缺 `parent` 的 prd → `GET /api/graph` 该节点 `ok: false`
   （既有 AC-17.1 的行为，保持）。
2. 在盘上把 `parent` 补齐，不发起任何新会话 → 再取 `GET /api/graph`。
3. 断言该节点 `ok: true` 且不带该 problem。

- Failing test: `test/server.test.ts::drops the mark on the next refresh once
  the relation is there` —— 修复前失败于第 3 步：节点仍 `ok: false`，
  `problems` 仍含 `parent does not point at idea-00001-x`（陈旧标记原样
  贴回）。

## 6. Fix

- Change: `Board` 不再保存 problems，而是保存上一次推进的 `Expectation`；
  `graph()` 每次按磁盘现状重跑 `findProduct` + `productProblems`——有发现才
  标记，无发现即丢弃该 expectation（此后连重验都不必再做），产物在盘上消失
  时同样不标。spec 修订：FR-17 补"标记由磁盘当前内容导出、随每次图构建重验、
  不粘滞"与 AC-17.3；design-00001 §2 补同口径的一行。
- Why this addresses the root cause and not the symptom: 把标记从"存下来的
  状态"还原成"算出来的导出值"——失效信号不再需要接线，因为不再有需要失效的
  东西。
- Alternatives rejected: 在每个写路径上清 `lastFinding` —— 仍是状态，仍要
  为每条新写路径补一次接线，漏一条就复发（本缺陷正是漏接的产物）。

## 7. Verification

- §5 的回归测试通过（AC-17.3）；AC-17.1 与 AC-17.2 的既有测试同时保持——
  异常仍在该标的时候标。
- 白板后端套件与类型检查全绿，覆盖率四项不降。

## 8. Follow-through

- Detection gap: AC-17.1/17.2 都只在会话刚结束时取一次图，"取第二次"从未被
  采样，"取图之间文档变了"更未被采样；AC-17.3 即护栏。
- Doc verdict: **the doc was incomplete** —— FR-17 原文只说"标记为异常"，
  未说标记的生命周期；随本 issue 补明并加 AC-17.3。
- Residual state: none —— 标记从不落盘，重启即消失。

## Links

- Blocks: plan-00011-whiteboard-revision-create-and-session-reach
- Related: decision-00008-whiteboard-revision-create-and-session-reach §2 第 7 条 ·
  issue-00013-the-board-never-hears-a-changeless-session-end（同族：刷新链路的读数与事实不一致）
