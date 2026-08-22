---
id: plan-00002-whiteboard-ui
type: plan
status: resolved
implements: [spec-00001-FR-26, spec-00001-FR-27, design-00002-whiteboard-ui]
---

# Plan: 白板界面改造

> 把白板前端从手写 CSS + 原生控件换成 Tailwind 4 + shadcn/ui + Lucide，并交付
> spec-00001-FR-26/FR-27（命令面板）。

## Design

- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) —— 令牌、
  布局、控件映射、图标语言、可访问性。
- [decision-00001-whiteboard-ui-stack](../decision/decision-00001-whiteboard-ui-stack.md)
  —— 技术基座与其代价（含覆盖率排除的边界）。

## Tasks

代码位于 `tools/whiteboard/web/`。U1 是其余任务的前置，U2–U6 之后可并行。

| # | 任务 | 交付 | 覆盖 |
| --- | --- | --- | --- |
| U1 | 基座落地 | 装依赖并**回填 decision-00001 §2 的版本与依赖形态**；Tailwind 4 + `@tailwindcss/vite` 接入并验证与 Vite 8 兼容；shadcn 初始化；逐个验证 design §4 的 Lucide 图标名；`components/ui/**` 排除进 `vitest.config.ts` 并写明边界，同时填 `CODE_QUALITY.md` §2 的 Coverage 行 | design §1、decision §4 |
| U2 | 令牌与主题 | `--status-*`/`--kind-*` 与 shadcn 语义色；`@custom-variant dark`；三态主题（存 localStorage、system 态监听 `matchMedia`）；`statusColour()` 改返回令牌引用 | design §1、spec §7 |
| U3 | 外壳与布局 | 顶栏（标题/搜索触发/异常计数/主题切换）；`ResizablePanel` 编辑器在右、终端在底，尺寸经 `useDefaultLayout` 持久化（v4 无 `autoSaveId`）；**面板状态从三选一改为两个独立开关** | design §2、spec §7 |
| U4 | 节点与工具栏 | 节点用 `Card` + 类型图标 + 状态 Badge + 异常 `Popover`；浮窗工具栏贴选中节点悬浮，状态切换与推进改 `DropdownMenu`，澄清改 `Dialog` | design §3、§4；spec FR-3、AC-2.4、AC-10.3 |
| U5 | 命令面板 | `Command` + `Dialog`，⌘K/Ctrl-K 与顶栏按钮唤起；不区分大小写子串匹配、全部列出、选定即定位并选中 | spec FR-26、FR-27 |
| U6 | 反馈与面板内容 | 动作被拒与错误改 `Sonner`；编辑/预览改 `Tabs`；保存中态；空画布空状态；CodeMirror 与 xterm 主题接令牌 | design §3、§5；spec §7 |
| U7 | 测试与验收 | 按 design §7 的五类改写既有断言；补 jsdom 桩；FR-26/FR-27 的 AC 全部落测；更新 [record-00001](../record/record-00001-docs-whiteboard-acceptance.md) | 全部 |

## Detailed Acceptance Path

1. **每任务完成即验证**：`npm test` 全绿、`npm run typecheck` 无错、`npm run build` 通过。
2. **新 FR**：`spec-00001-AC-26.1…AC-27.5` 共 11 条，每条有对应通过的测试。
3. **不回归**：FR-1…FR-25 的既有 AC 仍全部通过；按 design §7 改写的断言只限那五类，
   其余查询不到的控件按真实回归处理。
4. **覆盖率**：自有代码仍 ≥90% 行/分支/函数；`components/ui/**` 的排除只覆盖
   CLI 未改动的文件。
5. **可访问性**：design §6 列出的每条行为都有用例实测。
6. **收尾门槛**：由未参与实现的 subagent 按文档核验，`record-00001` 补上 FR-26/FR-27
   的验收行与被改名用例的更新；任何 gap 阻塞 `resolved`。
7. 验收证据位于 record-00001（其 parent 为 plan-00001）；本 plan 早于 resolved
   门（第十轮）完成，按当时流程记录。
