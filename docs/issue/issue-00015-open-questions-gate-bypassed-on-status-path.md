---
id: issue-00015-open-questions-gate-bypassed-on-status-path
type: issue
status: open
blocks: [spec-00002-whiteboard-governance]
---

# Issue: 带未决 Open Questions 的文档，走状态切换就能促进出 draft

> `rule-00001-BR-12` 只在「接收」那条通路上有人守。状态切换是另一条通路，
> 它把同一份文档促成 `active` / `open`，一句把关都没有。

## 1. Problem

- Observed: 一份带未决 Open Questions 的 `draft` 文档，经
  `POST /api/docs/:id/status`（白板浮窗的状态切换入口）指定目标状态
  `active`（living doc）或 `open`（work item），流转成功、文件被改写、
  commit 照常产生。同一份文档若改走 `POST /api/docs/:id/review` 的接收，
  会被拒绝。
- Expected: `rule-00001-BR-12` 说的是「带未决 Open Questions 的文档**不得被
  促进出 `draft`**」——它约束的是**促进**这件事，不是「接收」这个按钮。两条
  通路都到达同一个促进，就都该被同一道门拦住。
- Trigger: 任何 `draft` 文档 + 内容非空的 Open Questions 小节 + 用状态切换
  而不是接收去促进它。白板的浮窗同时提供这两个入口，选哪个纯看用户习惯。

## 2. Impact

- Affected: 使用白板的任何人，以及**每一份**经此路促进的文档。后果不是报错，
  是**静默通过**：文档带着未回答的问题变成 `active`（源头文档的「当前有效
  版本」）或 `open`（进入交付流），而门本该在这里拦下它。
- Since: commit `adae2b17`（白板写通路首次落地） · Still occurring: yes
- Severity: 高。它不是一个坏掉的功能，是一道**看起来在的门**——`rule-00001`
  写了它、`spec-00001-FR-8` 承载了它、测试覆盖了接收那条通路，于是没人会去
  怀疑另一条通路上它不存在。规则被绕过时系统不报任何异常。

## 3. Root Cause (first principles)

1. 分歧：规则约束的是**状态跃迁**（促进出 `draft`），而代码把门挂在了
   **动作**（接收）上。只要还有第二个动作能产生同一个跃迁，门就有洞。
2. 最小机制：`tools/whiteboard/src/docService.ts:208` 的 `changeStatus()`
   读文件后直接调 `tools/whiteboard/src/workflow.ts:56` 的
   `applyStatusChange()`，后者只问 `allowedTransitions()`（`rule-00001-BR-2`
   … `BR-9` 的流转表）合不合法，**从不调用 `hasOpenQuestions()`**。
   `hasOpenQuestions()`（`tools/whiteboard/src/workflow.ts:134`）在全仓只有
   一个调用点：`applyStatusChange` 隔壁的 `applyAccept()`
   （`tools/whiteboard/src/workflow.ts:65`，判定在 `:70`）。两个函数并排放着，
   一个有门一个没有。
3. 真正的根因：**把「谁能促进」建模成了动作的属性，而不是跃迁的属性**。
   `changeStatus` 与 `review` 是两条并列的写通路，中间没有一层共同的「促进
   守卫」，于是每加一条通路就要记得再抄一遍门——`spec-00001-FR-52` 的
   resolved 门后来正是被单独补进 `changeStatus`（`docService.ts:222` 的
   `assertScopeVerified`）的，那次补的是另一道门，没有回头看这一道。
   它**不是**这些症状：不是状态流转表写错了（表是对的，`draft → active`
   本来就合法）；不是 `hasOpenQuestions()` 判定有误（它在接收路上工作正常）；
   也不是前端少禁用了一个按钮（服务端接口本身就放行）。

- Introduced by: `adae2b17`（`feat(whiteboard): flow config, doc repository,
  workflow engine, and write pipeline`）。正是这次提交同时引入了
  `changeStatus`（无门）与 `applyAccept`（有门）这一对并列通路。在它之前
  白板没有任何写路径，缺陷无从发生。

## 4. Scope (same-cause sweep)

根因是「促进守卫挂在动作上而非跃迁上」，凡能写 `status` 的通路都共享它。

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `tools/whiteboard/src/docService.ts:208` `changeStatus()` | yes | yes | 修复点：促进守卫应加在这条通路上（`spec-00002-FR-1`） |
| `tools/whiteboard/src/workflow.ts:56` `applyStatusChange()` | yes | yes | 只查流转表，是缺门的那一层 |
| `tools/whiteboard/src/docService.ts:251` `review()` → `applyAccept()` | yes | no | 已有门（`workflow.ts:70`），`spec-00001-FR-8` 覆盖 |
| `tools/whiteboard/src/docService.ts:222` `assertScopeVerified()` | yes | no | 同一形状的另一道门（resolved 门），已挂在 `changeStatus` 上——本次修复应与它并排 |
| 新建通路 `POST /api/docs`（`server.ts:148`） | no | no | 产出的文档 status 恒为 `draft`，不产生促进跃迁 |
| 会话产出与外部编辑（watcher 刷新） | no | no | 白板不发起流转，只重读磁盘；`rule-00001-BR-12` 不回退已促进的文档 |
| 编辑器保存 `PUT /api/docs/:id`（`server.ts:161`） | no | no（域主裁定） | 保存整份文件时手改 `status` 确可绕过一切门，但编辑器是**原文级**修复通道（异常节点靠它修 front matter），与板外直接改文件同级——门守动作、不守原文编辑，是 `spec-00001-FR-4`/`FR-5` 的既有取舍，不是本缺陷 |

## 5. Reproduction (test-first)

**尚未落地**——本 issue 为 `draft`，修复属 `spec-00002` 的实现轮。修复轮的
第一步是先写下面这条测试并**记录它的失败输出到本节**，然后才动代码：

- 计划的失败测试：
  `tools/whiteboard/test/docService.test.ts::refuses to promote a draft with
  open questions on the status path` —— 造一份带内容非空 Open Questions 小节
  的 `draft` spec，调 `changeStatus(id, 'active')`，断言抛出拒绝、消息点名
  未决 Open Questions、且磁盘内容未变。当前实现下它会因**流转成功、文件已被
  改写**而失败。
- 配套一条 work item 侧的：同样的 plan 文档 `changeStatus(id, 'open')` 被拒。
- 两条测试落地后再改 `changeStatus`；测试转绿即为回归守卫。

## 6. Fix

- 方向由 `spec-00002-FR-1` 持有：把促进守卫加在 `changeStatus` 的通路上，
  与 `assertScopeVerified`（resolved 门）并排，判定复用
  `hasOpenQuestions()`——**同一个判定函数**，使两条通路不可能给出不同结论。
- 为什么这治的是根因而不是症状：门被挂到了「促进出 `draft` 这个跃迁」上，
  而不是再抄一份给某个动作；此后无论哪个动作产生这个跃迁都会被同一处拦下。
- Alternatives rejected: 前端禁用状态切换里的促进选项——服务端接口仍然放行，
  门就仍然不存在；`rule-00001-BR-12` 是业务约束，不能由呈现层承载。

## 7. Verification

未修复。`draft` 期间的现状即 §1 所述。

## 8. Follow-through

- Detection gap: 接收通路的门有测试（`spec-00001-AC-8.x`），状态切换通路的
  **同一道门**没有测试，因为从来没有人写下「这道门适用于哪些跃迁」。缺的不是
  一条用例，是一条把门与跃迁绑定的规格——`spec-00002-FR-2` 补的正是这条
  （明确列出哪些流转不经本门），它把「适用面」变成了可回归的东西。
- Doc verdict: **code was non-conformant** —— `rule-00001-BR-12` 与
  `spec-00001-FR-8` 都没写错，缺的是状态切换通路上的承载；补在
  `spec-00002-FR-1` 与 `spec-00002-FR-2`。
- Residual state: 本仓可能已存在经此路促进、且当时带未决 Open Questions 的
  文档。`rule-00001-BR-12` 明确不回退已促进的文档，故**无需回填**；修复只
  影响其后的促进。

## Links

- Blocks: spec-00002-whiteboard-governance
- Related: rule-00001-docs-workflow（BR-12）、spec-00001-docs-whiteboard（FR-8
  的接收通路、FR-52 的同形状守卫）
