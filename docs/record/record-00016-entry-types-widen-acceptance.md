---
id: record-00016-entry-types-widen-acceptance
type: record
status: active
parent: plan-00016-entry-types-widen
verifies: [rule-00001-BR-26]
---

# 验收记录：流程入口类型扩为四类

对 [plan-00016-entry-types-widen](../plan/plan-00016-entry-types-widen.md)
的验收。本轮交付的是 `rule-00001-BR-26` 第十四轮修订——流程入口类型扩为
idea/prd/design/analysis。新建机制本就由配置的 `entry` 列表驱动，无产品代码
改动，测试侧两件：出厂配置守卫按新集合更新，AC-26.2 补一条自己的测试（配置
守卫验的是配置值，替代不了新建路径）。清单按条目口径列全 BR-26 的两条 AC：
`AC-26.1` 沿用 [record-00011](record-00011-whiteboard-revision-create-and-session-reach-acceptance.md)
已引测试，`AC-26.2` 引本轮新增。测试路径相对 `tools/whiteboard/`。

## 质量门

- `npm test`：37 个文件、953 个测试全部通过（后端 538 + 前端 415）。
- `npm run typecheck`：`tsc --noEmit` 无错。
- `npm run test:coverage`：statements 99.14% / branches 95.4% /
  functions 98.66% / lines 99.62%，四项均高于 90% 门槛，未调整任何阈值。

## 验收清单

| 被验 id | 测试 | 结果 |
| --- | --- | --- |
| rule-00001-AC-26.1 | allocates the next number and hands back the type template；creates the file at the allocated id and commits it as a create (test/docService.test.ts)；creates the document at the allocated id, as a draft, and commits it (test/server.test.ts) | pass |
| rule-00001-AC-26.2 | creates a design with no spec in the repo, drafted from the design template (test/docService.test.ts) | pass |

交付范围内没有未覆盖或未通过的条目。

## 实现期的既定取舍

- AC-26.2 的测试直接读仓库的 `docs/design/TEMPLATE.md` 作夹具模板，正文断言
  取其标题行——「正文自 design 模板起草」验的就是仓库真正发出去的那份骨架，
  自造一份模板只能验到机制。
- 夹具用 `{ ...config, entry: [...] }` 就地放开 design，不改 `helpers.ts` 的
  出厂 `entry: [idea, prd]`：那份共享夹具是别处许多用例的既定前提。
- 出厂配置守卫的红是配置有意变更的结果，不是缺陷，故随本轮改断言而不立
  issue。
