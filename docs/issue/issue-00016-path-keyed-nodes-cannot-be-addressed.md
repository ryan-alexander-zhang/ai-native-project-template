---
id: issue-00016-path-keyed-nodes-cannot-be-addressed
type: issue
status: open
blocks: [plan-00012-whiteboard-governance-gates]
---

# Issue: 节点键是文件路径时，前端拼出的 URL 把它拆成了多段

> 异常节点以**文件路径**为键，路径含 `/`；`web/src/api.ts` 把键直接拼进
> `/api/docs/:id` 而不做 URL 编码，于是这些节点今天一个动作也发不出去。

## 1. Problem

- Observed: 一个以文件路径为键的节点（front matter 没有 id，或——治理轮起
  ——撞 id）在浮窗上点「编辑」，前端请求的是
  `GET /api/docs/spec/duplicate-b.md`。Express 5 按 `/` 分段，`:id` 只吃到
  `spec`，后面的 `/duplicate-b.md` 不匹配任何路由，请求以 404 收场；保存、
  状态切换、评审、条目、下一步同理。
- Expected: 节点键是什么，寻址就该到达什么。`spec-00001-FR-2` 让无 id 的文件
  以路径为键成为节点，`spec-00001-FR-4` 让**任何**节点都能打开编辑器——编辑
  正是异常节点唯一的修复通路。键含 `/` 是这一设计的必然结果，不是意外输入。
- Trigger: 任何以文件路径为键的节点上的任何动作。今天由「front matter 没有
  id」的文件到达；`spec-00002-FR-8` 落地后，撞 id 的文档也一律以路径为键，
  到达面随之扩大。

## 2. Impact

- Affected: 使用白板的任何人，以及每一份 front matter 坏到没有 id 的文档。
  后果是**修复通路本身不通**：白板把这些文件标为异常并告诉用户「去编辑它」，
  而编辑入口发出的请求到不了服务端。
- Since: commit `3156bbd5`（2026-08-13，`feat(whiteboard): canvas, editor,
  terminal, and the board ui`，`web/src/api.ts` 首次落地） · Still occurring: yes
- Severity: 中。触达要求先有一份 front matter 缺 id 的文档，本仓当前没有；但
  `spec-00002-FR-9` b 把「按路径寻址的编辑保存照常落盘」定为撞 id 的**唯一**
  修复通路，本缺陷因此从「边角情形」变成 `plan-00012` T4 的前置阻塞。

## 3. Root Cause (first principles)

1. 分歧：`DocNode.id` 是**节点键**，不是文档 id——`docRepository.ts:122` 明写
   「front matter id，没有时取仓库相对路径」。前端却把这个字段当作一个可以
   直接嵌进 URL 路径段的**不透明短标识**用。
2. 最小机制：`web/src/api.ts:75`（`doc`）等七处一律写作
   `` `/api/docs/${id}` `` ——模板字符串直接拼接，不经 `encodeURIComponent`。
   键里的 `/` 因此以路径分隔符的身份进入 URL，被 Express 5 的路由解析成额外
   的路径段，`req.params.id` 拿不到完整的键。
3. 真正的根因：**「节点键」与「URL 路径段」之间缺一次编码**——这是一个类型层
   面的失配，不是某一个调用点写错。同一文件里 `createPrefill`
   （`api.ts:102`）走查询参数并**已经**调了 `encodeURIComponent`，可见作者知
   道要编码；只是路径段这一族没有一处这么做，因为写这七行时节点键实际上总是
   一个合法 id。
   它**不是**这些症状：不是 Express 路由要改（已实测 Express 5 会把 `%2F`
   解回斜杠交给 `:id`，服务端不必动）；不是编辑器的问题（编辑器拿到内容后
   一切正常）；也不是节点键该换成别的东西（路径正是无 id 文件唯一可用的键）。

- Introduced by: `3156bbd5`。此前没有前端 API 客户端，也就没有拼 URL 这件事。

## 4. Scope (same-cause sweep)

根因是「节点键进 URL 路径段前少一次编码」，凡以节点键构造 `/api/docs/:id`
的调用点都共享它。

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `web/src/api.ts:75` `doc` | yes | yes | 修复点：`encodeURIComponent(id)` |
| `web/src/api.ts:76` `items` | yes | yes | 同上 |
| `web/src/api.ts:78` `save` | yes | yes | 同上——`spec-00002-FR-9` b 的修复通路 |
| `web/src/api.ts:79` `transitions` | yes | yes | 同上 |
| `web/src/api.ts:80` `setStatus` | yes | yes | 同上 |
| `web/src/api.ts:81` `accept` | yes | yes | 同上 |
| `web/src/api.ts:82` `nextSteps` | yes | yes | 同上 |
| `web/src/api.ts:102` `createPrefill` | yes | no | 已编码（查询参数），是本文件里做对的那一处 |
| `web/src/api.ts:108` `sessionTranscript` | 形似 | no | 入参是会话 id（UUID 形），由服务端生成，永不含 `/` |
| 服务端 `server.ts:156` 起的 `/api/docs/:id` 路由 | no | no | Express 5 把 `%2F` 解回斜杠交给 `:id`，路由不必改（design-00001 §2） |

## 5. Reproduction (test-first)

失败测试先于修复落地在 `tools/whiteboard/web/test/api.test.tsx` 的
`describe('addressing a document whose key is a file path')` 下——直接断言 URL
构造，是能观察到本缺陷的最便宜一层（不必渲染画布、不必起服务端）：

- `encodes the key when reading the document`
- `encodes the key when saving the document — the repair path of spec-00002-FR-9`
- `encodes the key on every other call that addresses a document`

修复前的实际失败输出：

```
 FAIL  web/test/api.test.tsx > addressing a document whose key is a file path > encodes the key when reading the document
AssertionError: expected "vi.fn()" to be called with arguments: [ …(2) ]

  1st vi.fn() call:

  [
-   "/api/docs/spec%2Fduplicate-b.md",
+   "/api/docs/spec/duplicate-b.md",
  ]

 Test Files  1 failed (1)
      Tests  3 failed | 17 skipped (20)
```

即 §1 所述：斜杠原样进了 URL。

## 6. Fix

- Change: 七个调用点一律改为 `encodeURIComponent(id)` 后再拼进路径段。
- Why this addresses the root cause and not the symptom: 缺的是「节点键 → URL
  路径段」这一次转换，补在每一个做该转换的地方；服务端与路由都不动，因为它们
  本来就是对的。
- Alternatives rejected: 改服务端路由为通配段（`/api/docs/*`）——服务端没有
  错，且会把「id 里可以有斜杠」这个错误的读法固化进契约；给节点键改用别的
  编码（如 base64）——无 id 的文件需要一个人读得懂的键，路径正是它。

## 7. Verification

未修复。`open` 期间的现状即 §1 所述。

## 8. Follow-through

- Detection gap: 既有测试全部用合法文档 id 作节点键，于是「键即 id」这个巧合
  从未被打破过。补上的护栏不止一条回归用例——三条测试覆盖了全部七个调用点，
  「凡按节点键寻址的调用都编码」因此成了可回归的事实。
- Doc verdict: **code was non-conformant** —— `spec-00001-FR-2`（路径作键）与
  `FR-4`（任何节点都能编辑）都没写错，是前端没有兑现它们的合取。
- Residual state: none。缺陷只影响请求构造，不曾写坏任何文件。

## Links

- Blocks: plan-00012-whiteboard-governance-gates
- Related: spec-00002-whiteboard-governance（`FR-9` b 的按路径编辑通路依赖本
  修复）、issue-00004-duplicate-ids-hide-a-document、
  design-00001-docs-whiteboard §2（治理轮已点名这七个调用点并把归宿定给本 issue）
