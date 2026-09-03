---
id: record-00027-whiteboard-directory-groups-and-exclude-acceptance
type: record
status: active
parent: plan-00026-whiteboard-directory-groups-and-exclude
verifies: [spec-00010-whiteboard-directory-groups-and-exclude]
---

# 验收记录：配置排除与目录组

对 [plan-00026-whiteboard-directory-groups-and-exclude](../plan/plan-00026-whiteboard-directory-groups-and-exclude.md)
的验收。交付范围为 `spec-00010` 全部 12 条 FR 的 82 条 AC，无范围外一并验收的
条目——`spec-00010` §6 的十项与 `/api/config` / `/api/graph` 契约在本 plan 的
Out of Scope 内，`rule-00001-BR-18` 与 `docs/README.md` 的追注已随 spec-00010
接收完成，均不在此验收。T1（两份 design 的第二十七轮）、T2/T3（服务端两处小
改）、T4/T5/T6（页面归组、组节点与展开态、导航栏与缩略图）、T7（本记录与
收口）分段落地。测试路径相对 `tools/whiteboard/`。

本轮据实校正回写两份 design，取舍见「实现期的既定取舍」：design-00001 §14.4
的已知边界补上 `DocService.newCowriteDoc`——`create` 与它是新建的两个形态、共用
同一条规范路径 `existsSync` 守卫，初稿只点了 `create`，同号窗口的实际大小要按
两处一起算；design-00002 §19 的六处「落地据实校正」——`orderedColumns` 返回
`Column`（而非初稿的导出 `columnKey` / `byIdThenPath` 一对函数）、组名行与会话
标记各自 `stopPropagation` 后调 `onToggle`（而非冒泡到 `onNodeClick`）、8 个锚点
抽成 `NodeHandles.tsx`、`centred` 初值按来源定（`focus` 置 `false`、`select` 置
`true`）。

plan §Detailed Acceptance Path 第 4 项的手工验证已于 2026-09-03 在仓库的一份
用后即弃的副本上执行完毕，七项检查全部通过，结果见下文「手工验证」。

## 质量门

四门在 T7 的补测之后整体重跑，命令均在 `tools/whiteboard/` 下执行：

- `npm test`：退出码 0，62 个文件、1903 个测试全部通过（T2…T6 结束时的
  62 / 1901 基线 + `AC-6.2` 与 `AC-5.14` 各一条）。
- `npm run typecheck`：退出码 0，`tsc --noEmit` 无输出。
- `npx vitest run --coverage --coverage.reportsDirectory=<临时目录>`：退出码 0，
  statements 98.72% / branches 95.49% / functions 98.62% / lines 99.29%
  （branches 2692/2819、functions 1436/1456、lines 3927/3955）。
  `vitest.config.ts` 只对 lines / branches / functions 设 90 门槛，三项皆过，
  statements 一并列出仅作参考；阈值与排除项一字未改，无被压制的发现。
  报告目录指到仓外临时目录，不在工作树留产物。
- `npm run build`：退出码 0（附带的 chunk 大小提示是既有的，非错误）。

## 手工验证

plan §Detailed Acceptance Path 第 4 项，2026-09-03 执行。做法：把本仓复制一份到
临时目录（只带配置与 `docs/`，改动不回流工作树），在副本里建
`docs/reference/stripe/reference-00003-stripe-webhooks.md`、
`reference-00004-stripe-payouts.md` 两份合式 reference 与一份无 front matter 的
`docs/reference/stripe/source/mirror.md`，`whiteboard.config.yaml` 置
`exclude: ['reference/*/source/**']`，`npm run build && npm start` 后由一个驱动
浏览器的 agent 逐项核对。七项检查全部通过：

1. **折叠与排除**：reference 列有一个 `stripe` 组节点、计数 2；`mirror.md` 既
   不在画布上，也不在异常清单里（清单读作 `no issues`）。
2. **点击展开与折叠**：点组节点，两份 reference 出现在其下；再点，收回。
3. **导航栏**：先两条顶层行，再是 `stripe` 组头、计数 2；点组头，导航栏与画布
   同时展开；点画布组节点，两边同时折叠。
4. **命令面板**：⌘K 选 `reference-00004-stripe-payouts`，该组展开、该节点被选中
   且视口居中于它。
5. **展开态过刷新**：展开后刷新仍为展开态，折叠后刷新仍为折叠态
   （`localStorage['whiteboard-directory-groups-expanded']`）。
6. **缩略图**：折叠组在缩略图上是一个 `minimap-group` 方块。
7. **去掉 `exclude` 后重启**：`mirror.md` 成为异常节点，落在 untyped 列一个折叠
   的 `reference/stripe` 组内，顶栏异常计数从 0 变 1。

截图留在会话的临时目录（`mv-shots/`），不入仓。

## 验收清单

| 被验 id | 测试 | 结果 |
| --- | --- | --- |
| spec-00010-AC-1.1 | keeps a matched file out of the nodes, the issues, and the diagnostics (test/docRepository.test.ts) | pass |
| spec-00010-AC-1.2 | reads the same file as an anomalous node keyed by its path when no pattern is configured (test/docRepository.test.ts) | pass |
| spec-00010-AC-1.3 | gives back the same graph after a matched file changes on disk (test/docRepository.test.ts) | pass |
| spec-00010-AC-1.4 | leaves out a README the glob does not reach and one it reaches too (test/docRepository.test.ts) | pass |
| spec-00010-AC-1.5 | puts the title of a matched file on no node for the command palette to find (test/docRepository.test.ts) | pass |
| spec-00010-AC-1.6 | reads an empty list as nothing excluded (test/config.test.ts) | pass |
| spec-00010-AC-1.7 | reads a null exclude, and a missing one, as nothing excluded (test/config.test.ts) | pass |
| spec-00010-AC-1.8 | matches a single segment with `*` and no deeper (test/docRepository.test.ts) | pass |
| spec-00010-AC-1.9 | matches nothing for a pattern naming a directory (test/docRepository.test.ts) | pass |
| spec-00010-AC-1.10 | yields the empty graph, and no issue, for a pattern matching everything (test/docRepository.test.ts) | pass |
| spec-00010-AC-1.11 | yields the graph it would without any pattern for one matching nothing (test/docRepository.test.ts) | pass |
| spec-00010-AC-1.12 | survives a refresh, and only a service started on the new config excludes (test/docService.test.ts) | pass |
| spec-00010-AC-1.13 | deletes a reference the session filed under an excluded path (test/docService.test.ts) | pass |
| spec-00010-AC-2.1 | rejects a scalar exclude, naming the key (test/config.test.ts) | pass |
| spec-00010-AC-2.2 | rejects an item that is not a string, naming its position (test/config.test.ts) | pass |
| spec-00010-AC-2.3 | refuses a second start on the unchanged config with the same message (test/config.test.ts) | pass |
| spec-00010-AC-2.4 | rejects a mapping exclude, naming the key (test/config.test.ts) | pass |
| spec-00010-AC-2.5 | rejects an empty pattern, naming its position (test/config.test.ts) | pass |
| spec-00010-AC-2.6 | rejects a pattern holding a `..` segment, naming its position (test/config.test.ts) | pass |
| spec-00010-AC-2.7 | rejects a pattern starting with a slash, naming its position (test/config.test.ts) | pass |
| spec-00010-AC-2.8 | rejects a negated pattern, saying negation is not supported (test/config.test.ts) | pass |
| spec-00010-AC-2.9 | rejects a backslash pattern, saying patterns separate segments with a slash (test/config.test.ts) | pass |
| spec-00010-AC-3.1 | breaks a relation edge into an excluded document and files the issue on the declaring one (test/docRepository.test.ts) | pass |
| spec-00010-AC-3.2 | breaks a record’s verifies into an item only an excluded spec declares (test/docRepository.test.ts) | pass |
| spec-00010-AC-3.3 | keeps an item id only an excluded spec declares out of idOwners (test/docRepository.test.ts) | pass |
| spec-00010-AC-3.4 | breaks the same edge, once, on a second read (test/docRepository.test.ts) | pass |
| spec-00010-AC-4.1 | orders a column as its top-level documents, then its groups by key (web/test/layout.test.ts) | pass |
| spec-00010-AC-4.2 | folds a deeper document into its first-level subdirectory (web/test/layout.test.ts) | pass |
| spec-00010-AC-4.3 | splits one directory across the columns of its types (web/test/layout.test.ts) | pass |
| spec-00010-AC-4.4 | groups the documents of the column without a declared type (web/test/layout.test.ts) | pass |
| spec-00010-AC-4.5 | leaves a column of top-level documents exactly as it was (web/test/layout.test.ts) | pass |
| spec-00010-AC-4.6 | keeps a document sitting directly under docs/ at the top of its column (web/test/layout.test.ts) | pass |
| spec-00010-AC-4.7 | makes a group of a subdirectory holding a single document (web/test/layout.test.ts) | pass |
| spec-00010-AC-4.8 | makes no group of a directory whose files all yield no node (web/test/layout.test.ts) | pass |
| spec-00010-AC-5.1 | stands one group node in for the three documents it holds (web/test/canvas.test.tsx) | pass |
| spec-00010-AC-5.2 | marks a group holding an anomalous document without changing the counts (web/test/canvas.test.tsx) | pass |
| spec-00010-AC-5.3 | lands the two edges into one group on the group node, as one edge (web/test/foldGraph.test.ts) | pass |
| spec-00010-AC-5.4 | drops an edge between two members of one collapsed group (web/test/foldGraph.test.ts) | pass |
| spec-00010-AC-5.5 | carries a session marker for a session running on a member (web/test/canvas.test.tsx) | pass |
| spec-00010-AC-5.6 | leaves the selection and the toolbar alone when the group node is clicked (web/test/canvas.test.tsx) | pass |
| spec-00010-AC-5.7 | merges the edges between two collapsed groups into one dim edge (web/test/foldGraph.test.ts) | pass |
| spec-00010-AC-5.8 | labels the emphasised aggregated edge with every field name it carries (web/test/foldGraph.test.ts) | pass |
| spec-00010-AC-5.9 | does not merge two aggregated edges running opposite ways (web/test/foldGraph.test.ts) | pass |
| spec-00010-AC-5.10 | leaves a group node reached by the selection unsuppressed and suppresses the other (web/test/foldGraph.test.ts) | pass |
| spec-00010-AC-5.11 | merges the edges between two collapsed groups into one dim edge (web/test/foldGraph.test.ts) | pass |
| spec-00010-AC-5.12 | reads as waiting when any member’s session awaits input (web/test/canvas.test.tsx) | pass |
| spec-00010-AC-5.13 | splits one directory across the columns of its types (web/test/layout.test.ts) | pass |
| spec-00010-AC-5.14 | names two groups of one column by what their folders share with it (web/test/layout.test.ts) | pass |
| spec-00010-AC-6.1 | puts the members below the group node when the name row is activated (web/test/canvas.test.tsx) | pass |
| spec-00010-AC-6.2 | splits the aggregated edge back onto the members once the group is open (web/test/foldGraph.test.ts) | pass |
| spec-00010-AC-6.3 | folds the members away again on the second activation (web/test/canvas.test.tsx) | pass |
| spec-00010-AC-6.4 | opens again on the group the user left open (web/test/canvas.test.tsx) | pass |
| spec-00010-AC-6.5 | opens with every group collapsed when none was ever opened (web/test/canvas.test.tsx) | pass |
| spec-00010-AC-6.6 | keeps the selection, and hands the group node its presentation, when the group folds (web/test/canvas.test.tsx) / reads the selection through the group standing in for it (web/test/foldGraph.test.ts) | pass |
| spec-00010-AC-6.7 | opens the group when its session marker is activated (web/test/canvas.test.tsx) | pass |
| spec-00010-AC-7.1 | opens the group and centres on the document the command palette names (web/test/focus.test.tsx) | pass |
| spec-00010-AC-7.2 | opens the group from the anomaly list (web/test/focus.test.tsx) | pass |
| spec-00010-AC-7.3 | opens the group from the relation list of the selected document (web/test/focus.test.tsx) | pass |
| spec-00010-AC-7.4 | leaves every group collapsed when the document picked is a top-level one (web/test/focus.test.tsx) | pass |
| spec-00010-AC-7.5 | opens the group from the session panel, and shows the session (web/test/focus.test.tsx) | pass |
| spec-00010-AC-7.6 | opens the group from an inline id in an expanded requirement row (web/test/focus.test.tsx) | pass |
| spec-00010-AC-8.1 | lists the top documents, then a header per directory group, counting them all (web/test/sidebar.test.tsx) | pass |
| spec-00010-AC-8.2 | opens the group in the list and on the canvas when its header is pressed (web/test/sidebar.test.tsx) | pass |
| spec-00010-AC-8.3 | opens the group in the list when the canvas group node is clicked (web/test/sidebar.test.tsx) | pass |
| spec-00010-AC-8.4 | opens the type group and the directory group the selection lands in (web/test/sidebar.test.tsx) | pass |
| spec-00010-AC-8.5 | stays collapsed when the group closed is the selected row’s own (web/test/sidebar.test.tsx) | pass |
| spec-00010-AC-9.1 | takes in a new directory group between the two it already had (web/test/refresh.test.tsx) | pass |
| spec-00010-AC-9.2 | drops a group whose last document is deleted (web/test/refresh.test.tsx) | pass |
| spec-00010-AC-9.3 | leaves the open group open and the collapsed one collapsed (web/test/refresh.test.tsx) | pass |
| spec-00010-AC-9.4 | moves a top-level document into the group it was filed under (web/test/refresh.test.tsx) | pass |
| spec-00010-AC-9.5 | keeps the group open when the selected document inside it is deleted (web/test/refresh.test.tsx) | pass |
| spec-00010-AC-9.6 | starts a renamed directory collapsed again (web/test/refresh.test.tsx) | pass |
| spec-00010-AC-9.7 | drops the group and the selection when the only document in it is deleted (web/test/refresh.test.tsx) | pass |
| spec-00010-AC-9.8 | moves a document from one group to the other, counts and all (web/test/refresh.test.tsx) | pass |
| spec-00010-AC-9.9 | rebuilds the group in the column the changed type puts it in (web/test/refresh.test.tsx) | pass |
| spec-00010-AC-10.1 | draws a collapsed group holding an anomalous document as one anomalous block (web/test/canvas.test.tsx) | pass |
| spec-00010-AC-10.2 | keeps the group’s own block when it is expanded and colours each member by its status (web/test/canvas.test.tsx) | pass |
| spec-00010-AC-10.3 | draws a sound collapsed group as one block of the group colour (web/test/canvas.test.tsx) | pass |
| spec-00010-AC-11.1 | leaves a visible document sole owner of an id an excluded file also declares (test/docRepository.test.ts) | pass |
| spec-00010-AC-11.2 | reports nothing at all for two excluded files declaring one id (test/docRepository.test.ts) | pass |
| spec-00010-AC-12.1 | passes over the number an excluded file declares (test/workflow.test.ts) | pass |
| spec-00010-AC-12.2 | takes the next number after the highest visible one when nothing is excluded (test/workflow.test.ts) | pass |

82 条 AC 全部有对应测试且全部通过，无未覆盖项、无 fail 行。`AC-5.7` 与
`AC-5.11` 同由一条用例钉住（同一夹具的两个断言面：合并成一条，且无选中时弱化
无标签）。`AC-5.13` 仍由 `AC-4.3` 那条「一个目录分落两列」的用例钉住；
`AC-5.14` 另起一条——它的 Given 是**同一列里的两个组**（`spec/stripe/x.md` 声明
`type: reference`，与 `reference/stripe/` 下的文档同落 reference 列），而
`AC-4.3` 的夹具是一个目录分落两列，两者不是同一形态。`AC-6.6` 的 Then 有两半：
折叠后选中、工具栏、检视面板与组节点的 `aria-current` 由 `canvas.test.tsx` 那条
钉住，「与它相连的汇聚边呈强调态」由 `foldGraph.test.ts` 那条钉住（选中经组节点
代表读出，边随之取 `edge--emphasis`）。

## 实现期的既定取舍

- **展开键用 NUL 分隔「列键 + 组键」**：与 `toFlowEdges` 的合并键同一做法，
  两段都可能含 `/`，用任何可见字符都可能被组键本身撞上。代价是这个键不能进
  DOM：组节点的 `data-testid` 因此取 `group-<columnKey>-<groupKey>`，不取展开
  键——NUL 进不了属性值，也进不了 CSS 属性选择器。两个键并存不是冗余，是
  「持久化的键」与「可查询的键」两件事。
- **`layoutGraph` 不留过渡重载**：`expanded` 定为必填第二参
  （`ReadonlySet<string> | string[]`），而不是先加可选参再分批迁调用点。全仓
  只有两处调用，一次改完；留一个「省略即全折叠」的重载会让「没传」与
  「传了空集」在类型上不可分。既有的 `orderedColumns` 用例随之从
  `DocNode[][]` 改读 `Column.top`。
- **自环只在不是折叠产物时保留**：`foldGraph` 的边过滤写作
  `from === to && from !== edge.from` 才丢弃。文档引用自己在画布上本来就画成
  一个环（design-00002 §4），不能因为加了折叠就把它一起吃掉；能让边消失的只有
  折叠——两个成员在同一折叠组内互指才丢。
- **`centred` 的初值按来源定**：`focus`（命令面板、清单、关系列表、导航栏、
  行内 id）置 `false`——跳转欠一次落位后的居中；`select`（画布点选）置
  `true`——点选不欠居中，只武装宽度补偿。不分来源则每次画布点选都会居中，
  `viewport.test.tsx` 的「选中不改变画布宽度时视口不动」即破。
- **8 个锚点抽成 `NodeHandles.tsx`**：`GroupNodeCard` 照抄 `NodeCard` 的锚点是
  三十行重复加两段 issue 注记。抽一个无参组件，两处共用；§4 的锚点规则不变，
  `NodeCard` 的 DOM 不变。
- **`groupMarker` 与 `SessionMarker` 同住 `SessionMarker.tsx`**：聚合函数只有
  这张卡片会调，跟它服务的那个组件放一起，不为一个函数另起模块。
- **`AC-7.1` 断言 `setCenter` 的坐标而不是 DOM**：坐标取**展开后**布局给该成员
  的位置——折叠时那个位置根本不存在，所以「坐标对」本身就证明了「先展开再定
  位」的先后。不探 DOM 是因为 React Flow 提交节点比 `placed` 慢一拍，靶 DOM 会
  测成时序而不是行为。
- **导航栏目录组头不写 `aria-label`**：可访问名就是可见文案（`Folder` 图标
  `aria-hidden`、组名 + 计数 `Badge`）。画布组节点的组名行要写，是因为那里的
  计数 `Badge` 只有数字、读出来是「stripe 3」；导航栏的行文案已经成句，再加一个
  `aria-label` 就是同一句话的两份维护。
- **缩进只两级，`pl-4` / `pl-6`**：目录组头与顶层文档行同为 `pl-4`（类型组之下
  一级），组内文档行 `pl-6`。不按层级算缩进量——本轮只有一级子目录（spec §6
  把多级嵌套划在范围外），一个函数式的缩进阶梯是为不存在的层级写的。
- **`AC-8.4` 加了第二次选取**：第一次选取（类型组也是折叠的）之后，再选另一个
  组里的文档、此时类型组已开——只有目录组要让开。这一次仍断言 `scrollIntoView`
  被调用，钉的是 `selectedRow` 效果的依赖数组里有 `expandedGroups`：少了它，
  行会被画出来但滚不到。
- **`exclude` 的非字串项与空串共用一条消息**：`readExcludePattern` 第一关就是
  `typeof value !== 'string' || value === ''` → 「must be a non-empty string」。
  两者是同一件事的两个面（不是一个可用的模式），分两条消息只会让调用方去猜
  区别；反斜杠、绝对路径、`!` 取反、`..` 段各有自己的消息，因为它们各自指向
  一种别的 glob 方言。
- **`AC-2.3` 写成对同一段文本的两次 `parseFlowConfig`**：「配置未改时再次启动
  得到同一拒绝」的可测内核是解析纯函数的确定性，不是进程重启。起两个进程只会
  把一条断言换成一套夹具。
- **`AC-1.12` 写成同一个 `docsDir` 上的两个 `DocService`**：`exclude` 只在启动
  时读（design-00001 §3），所以「改动经重启生效」的可测形态是「旧配置的实例
  照旧不排除、新配置的实例才排除」。同理不模拟重启。
- **vitest 的 `include` 放宽到 `web/test/**/*.test.ts`**：本轮新增的
  `layout.test.ts`、`foldGraph.test.ts`、`sidebarModel.test.ts` 三份是纯函数
  用例、没有 JSX，原来的 `*.test.tsx` 收不进去——收不进去的测试是不存在的测试。
  改成 `{ts,tsx}` 一处，不给纯函数测试改后缀。
