---
id: plan-00001-docs-whiteboard-mvp
type: plan
status: resolved
implements: [spec-00001-docs-whiteboard, design-00001-docs-whiteboard]
---

# Plan: Docs 白板 MVP 实现

> 按 design-00001 的模块结构，把 spec-00001 的 21 条 FR 全部做出来并逐条过验收。

## Design

- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md) —
  模块结构、流程配置契约、API、会话生命周期、冲突策略。

## Tasks

代码位于 `tools/whiteboard/`（design §8）。任务按模块切分，T2–T4 与 T5–T7
两组内部低依赖、可并行；每个任务含自己的测试。

| # | 任务 | 交付 | 覆盖 |
| --- | --- | --- | --- |
| T1 | 服务骨架 + 流程配置 | Node/TS 工程、HTTP/WS 框架、配置加载与启动校验；仓库自带一份 `whiteboard.config.yaml`（无内置默认回退，per design §3） | FR-15 |
| T2 | Doc Repository | 扫描/解析/图模型/异常清单，标题取法，`GET /api/graph`、`GET /api/docs/:id` | FR-1, FR-2 |
| T3 | Workflow Engine | 流转候选、接收/澄清裁决、下一步候选、id 分配；`status`/`review`/`next-steps` 接口 | FR-6, FR-7, FR-8, FR-9, FR-10 |
| T4 | Git Layer + 写管道 | 读盘校验→裁决→写盘→commit 管道；hash 冲突检测；按路径暂存；commit 信息格式 | FR-4, FR-5, FR-14, FR-19, FR-20 |
| T5 | Session Manager | PTY 会话注册表、任务指令模板、权限约束传递（含对接入 CLI 的越界写实测，per design §3）、单会话锁、断连存续与缓冲回放、失败处理、会话 commit | FR-11, FR-13, FR-16, FR-18, FR-21 |
| T6 | 前端画布 | React Flow + ELK 布局、节点渲染（类型/id/标题/status 着色）、异常标记、浮窗工具栏 | FR-1, FR-2, FR-3, NF |
| T7 | 前端编辑器 + 终端 | CodeMirror 编辑/保存/冲突呈现；xterm.js 终端接 WS、重连回放 | FR-4, FR-5, FR-12, FR-21 |
| T8 | 推进闭环 | 「+」候选、发起会话、结束后刷新 + front matter 校验 | FR-10, FR-11, FR-12, FR-17 |
| T9 | 验收与收尾 | 全量 GWT 验收测试跑通；`docs/record/` 验收清单 → [record-00001-docs-whiteboard-acceptance](../record/record-00001-docs-whiteboard-acceptance.md) | 全部 FR/BR 的 AC |

## Detailed Acceptance Path

1. **每任务完成即验证**：任务覆盖的 FR/BR，其全部 `spec-00001-AC-*` /
   `rule-00001-AC-*` 有对应通过的测试（服务端为自动化测试；纯前端交互项按
   `TESTING.md` 定级，最少为脚本化冒烟 + 手动清单）。
2. **rule-00001 全表**：BR-1…BR-19 的 25 条 AC 在 Workflow Engine 的单元测试
   中逐条落地（T3 内）。
3. **端到端主线**：在一个临时 git 仓库夹具上跑通 S1–S5 五个 story 的主路径
   （打开→编辑→接收→推进→留痕），断言 commit 序列与文件终态。
4. **收尾门槛**：`spec-00001` 的 21 条 FR 无一未被 AC 引用、无一 AC 无测试；
   由未参与实现的 subagent 按文档核验后，在 `docs/record/` 记录验收清单
   （链接全部 GWT id），本 plan 方可置 `resolved`。
