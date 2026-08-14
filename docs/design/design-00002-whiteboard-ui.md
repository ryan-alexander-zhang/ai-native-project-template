---
id: design-00002-whiteboard-ui
type: design
status: active
informs: [spec-00001-docs-whiteboard]
---

# Design: Docs 白板界面

> 白板界面的结构：设计令牌、布局、控件清单与图标语言。技术基座由
> [decision-00001-whiteboard-ui-stack](../decision/decision-00001-whiteboard-ui-stack.md)
> 定下（Tailwind 4 + shadcn/ui + Lucide）。

本文只管界面。数据模型、API、会话与 git 由
[design-00001-docs-whiteboard](design-00001-docs-whiteboard.md) 持有。两处触及
它：节点种类的下发方式（§4），以及画布布局——布局选型属 design-00001 §1，其
规则由 [decision-00002-whiteboard-layout](../decision/decision-00002-whiteboard-layout.md)
持有，本文 §2 只画出它的样子、§4 只定锚点与边的呈现。

**本文取代 design-00001 §7 末段的「前端 Graph View 职责补充」**（着色与搜索/
定位）——那一段与本文 §2、§4 是同一主题，两份活文档不能同时持有；其中的搜索/
定位已由 `spec-00001-FR-26`、`FR-27` 承接，不再是非功能项。本文进入 `active`
时，design-00001 该段替换为指向本文的一行指针，`spec-00001` §5 的 Technical
Design 表与 Links 同时补上本文。除 §8 裁定引入的 FR-26/FR-27 及其 AC 与 §7
非功能项外，在此之前不改动那两份 `active` 文档。

## 1. 设计令牌

单一来源是 `web/src/style.css`（Tailwind 4 的 CSS-first 配置；若按 Tailwind 约定
更名为 `index.css`，`main.tsx` 的导入同步更新）。`:root` 与 `.dark` 各一套变量，
shadcn/ui 的组件读的就是这套，因此改主题只改一处。

除 shadcn 的基础语义色（`--background`、`--foreground`、`--primary`、
`--destructive`、`--muted`、`--border`、`--ring`）外，白板另立两组领域令牌：

| 令牌组 | 成员 | 用途 |
| --- | --- | --- |
| `--status-*` | `draft` `active` `open` `resolved` `wontfix` `archived` | 文档状态，每个状态一组前景/背景对 |
| `--kind-*` | `living` `work` | 文档种类，用于节点的类型标记 |

异常态不进 `--status-*`：它不是一个状态值，取 `--destructive`。`status.ts` 现有的
硬编码十六进制（6 个状态色 + `ANOMALY_COLOUR`）随之退场，`statusColour()` 改为
返回令牌引用（`var(--status-*)`）而非字面色值——它喂进 inline style，因此仍是
色值位置上的表达式，只是随主题变；`statusLabel()` 不变。

**主题三态的机制**（浅色 / 深色 / 跟随系统）：

- Tailwind 4 的 `dark:` 变体默认走 `prefers-color-scheme`，而本设计需要 class
  策略，因此显式声明 `@custom-variant dark (&:where(.dark, .dark *))`。
- localStorage 存三态之一；`light`/`dark` 直接决定 `.dark` 类的有无。
- `system` 态下监听 `matchMedia('(prefers-color-scheme: dark)')` 的变化事件增删
  `.dark`，页面加载时先同步一次。
- 主题偏好是纯呈现状态，不进 `docs/`，也不构成对「文件是唯一事实来源」的例外。

## 2. 布局

常驻区域（自上而下、自左而右）：

```mermaid
flowchart TB
  TB[Top bar<br/>标题 · 搜索触发 · 异常计数 · 主题切换]
  subgraph Work[工作区]
    direction LR
    CV[Canvas<br/>React Flow] ---|可拖动分隔| EP[Editor panel<br/>宽度可调]
  end
  TB --- Work
  Work --- TP[Terminal panel<br/>高度可调]
```

浮于其上的四者不占布局：**浮窗工具栏**贴选中节点悬浮于画布；**命令面板**与
**澄清对话框**是覆盖全屏的对话框；**提示条**堆叠在画布一角。

与当前实现的结构差异，以及每项的代价：

- **编辑器面板到右侧、终端面板留在底部**，两者各自可调、互不占位。依据是内容
  形状不同：文档是竖排长文，编辑与预览在窄而高的区域里更可读；终端输出是宽行
  （框线、表格、diff、长路径），在右侧面板里会折行到不可读，而底部放得下。
  （量级估算：1400px 视窗、12px 等宽字号下，右侧面板约 480px ≈ 55 列，底部
  约 180 列。默认宽度与字号落地时定，此处只作定性依据。）
  代价有两处：两种面板形态都要实现，而不是共用一个容器；**面板状态模型也要改**
  ——现实现的 `Panel` 是 `none | editor | terminal` 三选一，编辑器与终端不能同时
  在场，而本设计要求两者并存，须改为两个独立开关。
  两者的尺寸都由 `react-resizable-panels` 持有。落地实测的结果与原设想不同：
  v4 **没有** `autoSaveId`，持久化走 `useDefaultLayout({ id, panelIds })`——它
  返回喂给 `Group` 的 `defaultLayout` 与 `onLayoutChanged`，默认落 localStorage。
- 动作被拒与失败的信息从画布下方的红条改为**提示条（toast）**：它是对某次动作
  的反馈，不该占据布局、压缩画布。
- 「找文档」从顶栏常驻输入框改为**命令面板**（⌘K / Ctrl-K），承载 spec-00001
  FR-26 与 FR-27 的检索与跳转。**代价是可发现性净损失**：搜索入口从常驻输入框
  退为一次点击或一个快捷键；补偿是顶栏保留触发按钮，让快捷键可被看见。

**画布内部**是一张类型分列的网格。**规则本身由
`decision-00002-whiteboard-layout` §2 持有**，此处不复述，只画出它的样子——
下面是本设计落地时本仓文档的一次快照（17 份、用到 9 个类型），不是活的清单：

```
        idea         prd          spec         rule       decision       design        plan        issue       record
row0   idea-00001  prd-00001   spec-00001  rule-00001  decision-00001  design-00001  plan-00001  issue-00001  record-00001
row1                                                   decision-00002  design-00002  plan-00002  issue-00002  record-00002
row2                                                                                 plan-00003  issue-00003
row3                                                                                             issue-00004
```

读法：横向是阶段，纵向是同一类型内的序号。图宽等于**实际用到的类型数**（此处 9）
而非配置里声明的 16——没有文档的类型不占列。

这套规则是同步纯函数，不需要布局引擎；它换掉的 ELK 与所接受的代价（无交叉
最小化、纵向无界、列序由 YAML 声明顺序承载）见 decision-00002 §4。

**布局与配置的到位次序**：列序来自 `GET /api/config`，而当前 `useBoard` 是
把它与 `GET /api/graph` 各自独立地取的（`web/src/useBoard.ts:63-66`），首屏
因此可能没有列序。本设计要求**两者都到位后才落位**，而不是先按无序布局画一遍
再重排——后者会让节点在首屏跳一次，直接违背 decision-00002「位置可预期」的立论。

## 3. 控件映射

| 界面位置 | 现在 | 改为 | 图标（Lucide） |
| --- | --- | --- | --- |
| 顶栏标题 | 纯文本 | 文本 + 图标 | `LayoutDashboard` |
| 找文档 | 裸 `<input>` | `Command` + `Dialog`（⌘K），顶栏留触发按钮 | `Search` |
| 异常计数 | 纯文本 | `Badge`；为 0 时保持现有的 `no issues` 文案，>0 时 destructive 变体 | `TriangleAlert` |
| 主题切换 | 无 | `DropdownMenu`（浅色/深色/跟随系统） | `Sun` `Moon` `Monitor` |
| 节点 | 手写 div | shadcn `Card` 承载 + `Badge` | 见 §4 |
| 浮窗工具栏 | 浮动 div + 原生控件 | `Card` 容器 + `Tooltip` 包裹的 `Button` | 见下 |
| 状态切换 | `<select>` | `DropdownMenu`，逐项列出合法目标状态 | `GitBranch` |
| 接收 | `<button>` | `Button`（default 变体） | `Check` |
| 澄清 | 工具栏内联 textarea | `Dialog` + `Textarea` + 提交按钮 | `MessageCircleQuestionMark` |
| 推进 | `<select>` | `DropdownMenu`，逐项列出下一步类型；**无候选时按钮 disabled，并在 `Tooltip` 与菜单内呈现「no next step」**（spec-00001-AC-10.3） | `Plus` |
| 编辑 | `<button>` | `Button`（ghost 变体） | `Pencil` |
| 编辑/预览切换 | 两态按钮 | `Tabs`（Source / Preview） | `Code` `Eye` |
| 保存 | `<button>` | `Button`，保存中为 disabled + spinner | `Save` `Loader` |
| 关闭面板 | `<button>` | `Button`（ghost, icon） | `X` |
| 动作被拒 / 冲突 | 面板内纯文本 / 画布下红条 | `Sonner` 提示条，错误态（`toast.error`） | `TriangleAlert` |
| 编辑器面板 | 底部固定 45vh | 右侧 `ResizablePanel`，宽度可调并持久化 | — |
| 终端面板 | 底部固定 45vh | 底部 `ResizablePanel` + `Card` 头 + 会话状态 `Badge`（running/exited/failed），高度可调 | `Terminal` |
| 空画布 | 空白 | 空状态：图标 + 一句说明 | `FileQuestionMark` |

**异常节点的工具栏只保留「编辑」一项**（spec-00001-AC-2.4），其余控件不渲染——
上表描述的是正常节点的完整形态。

**用下拉菜单而不是 `<select>`**：状态切换与推进都是「执行一个动作」，不是「选定
一个值」——现有实现要把 `<select>` 的 value 强行复位成空串才能重复触发同一动作，
这是语义错配的症状。`DropdownMenu` 的每一项是一个动作项，语义对上了。

## 4. 节点

一个节点承载四件事，按信息层级排布（以 shadcn `Card` 组件作为实现载体）：

```mermaid
flowchart LR
  subgraph Node
    direction TB
    R1["① 类型图标 + 类型名 · · · ② 状态 Badge"]
    R2["③ 标题（H1，两行截断）"]
    R3["④ id（等宽小字）"]
    R4["⑤ 异常时：Badge + Popover 列出 problems"]
  end
```

- **类型图标**：每个文档类型一个 Lucide 图标 —— `idea: Lightbulb`、
  `prd: Target`、`spec: FileText`、`rule: Scale`、`design: DraftingCompass`、
  `decision: Gavel`、`plan: ListChecks`、`task: SquareCheck`、`issue: Bug`、
  `record: ClipboardCheck`、`analysis: ChartLine`、`integration: Plug`、
  `reference: BookMarked`、`operation: Wrench`、`prompt: MessageSquare`、
  `report: FileChartColumn`。配置里出现而此处未列的类型回落到 `File`。
  （以上标识符已对 lucide 上游逐个核实；落地时按所装 `lucide-react` 版本复验。）
- **种类描边**：living 与 work 用 `--kind-*` 区分。**种类当前不经 API 下发**——
  `DocNode` 没有 `kind` 字段，它只存在于流程配置。两条路径二选一：给 `DocNode`
  增加 `kind`（则 design-00001 §7 的 `GET /api/graph` 契约需同步修订），或前端读
  `GET /api/config` 自行由 `type` 映射。**已裁定取后者**并落地：`useBoard` 拉
  `GET /api/config` 得到 `types`，节点按 `type` 查其 kind；`GET /api/graph` 的
  契约不变。
- **状态 Badge**：保持现有的「颜色 + 状态词」（`statusLabel()` 已渲染状态词），
  令牌化后颜色可随主题变，不靠颜色单独传达。
- **异常节点**：描边转 `--destructive`，节点面只留一个 `TriangleAlert` Badge，
  problems 列表进 `Popover`（不是 `Tooltip`：tooltip 内容对键盘与触摸用户不可达，
  而可访问性正是采用本基座的理由）。现在的实现把 problems 直接铺在节点上，长
  文本会撑破布局。
- **选中态**：`--ring` 描边，与 React Flow 自身的选中样式统一。

边沿用 React Flow 的默认边型：标签为关系字段名，`ok: false` 的边用 destructive
虚线。另有三条规则：

- **箭头指向被引用的那份文档**。边的方向就是 front matter 的声明方向——`prd`
  写 `parent: idea`，箭头就落在 `idea` 一端。不做任何反推：`docs/README.md`
  规定每条边只在依赖方声明一次，画成别的方向就是在图上改写声明。已由产品负责人
  裁定采用此方向。
  **一条边在图上朝左还是朝右，取决于两端类型的列位，而不取决于关系字段名**：
  同一个字段两次出现可以指向相反的方向（`rule informs spec` 朝左，
  `design informs plan` 朝右）。因此不存在「某几个字段一律朝左」的规律，图上
  两个方向都会出现。**落地实测**（本仓 17 份文档、34 条边）：26 条朝左、8 条
  朝右、0 条同列。朝左占多数，但那是这批文档的类型分布所致，不是字段名的规律。
- **节点必须自带锚点**：四个方位（上/下/左/右）各一对 source/target，共 8 个。
  自定义节点接管渲染就接管了连接契约，缺锚点的节点会让 React Flow 丢弃它的
  每一条边——这正是 `issue-00002` 的根因。取 8 个而不是 4 个是为了不依赖
  `connectionMode="loose"` 的回退语义（Loose 只让 target 侧回退到 source 锚点，
  source 侧仍要求 `type="source"`），从而避免为此改动 `<ReactFlow>` 的全局 prop。
- **锚点不可见，但必须仍被布局与测量**。`display: none` 会让 React Flow 量不到
  锚点位置，等于重新制造 `issue-00002`；隐藏只能用不影响盒模型的方式
  （如 `opacity: 0`）。
- **手工连线必须显式关闭，且要关在锚点上**。React Flow 的 `nodesConnectable`
  默认为 `true`，所以锚点一旦落地，每个节点就多出四处可拖拽连线的交互——而白板
  的边只能来自 front matter。本条的写法被落地实测**推翻了两次**，两次都记在
  这里，因为它们是同一类错的两个层次：
  1. 只在 `<ReactFlow nodesConnectable={false}>` 上设不够——该 prop 只把
     `isConnectable` 传给**节点组件**，自定义节点不转发就等于没关。
  2. 每个 `<Handle>` 加 `isConnectable={false}` 仍然不够——`Handle` 的
     `isConnectableStart` / `isConnectableEnd` 是**各自独立**默认为 `true` 的，
     而 pointer-down 的守卫读的是 `isConnectableStart`。只设 `isConnectable`
     等于只摘掉了 CSS 类，拖拽通路照旧武装着。
  故三个标志全设。由 `spec-00001-AC-1.14` 守着，且该 AC 的断言必须落在
  `connectablestart` 上——只断言 `connectable` 类，等于断言那个不起作用的属性。
- **锚点按几何选**：两端不同列时走左右侧（起点出靠向对端的那一侧，终点从其对侧
  入）；同列时走上下（上方节点的下锚点 ↔ 下方节点的上锚点）；两端为同一节点时
  （文档引用了自己，`docRepository` 视其为正常边）走该节点的上下锚点成自环。
  同列的边是竖线；跨列的边只有在两端同行时才是横线，行不同即为斜线——本仓多数
  跨列边正是斜线。

## 5. 面板

`Tabs` 承载 Source / Preview 两个视图，与 spec-00001-FR-22 的互斥切换一致。
CodeMirror 在预览时仍只隐藏不卸载（FR-25 依赖它保住光标）。

- CodeMirror 主题跟随 `--background`/`--foreground`，与页面同一套令牌。
- xterm 的 `theme` 同样由令牌喂入，暗色切换时一并变。
- Preview 的 Markdown 排版用 Tailwind 的风格类手写一层（不引入
  `@tailwindcss/typography`，避免为一处排版再加一个插件），mermaid 图容器保持
  可横向滚动。

## 6. 可访问性

以下是 **Radix 承诺的行为**，不是本项目从零实现的：

- 对话框（澄清、命令面板）有焦点陷阱，Esc 关闭，关闭后焦点回到触发元素。
- 下拉菜单支持方向键、Home/End、首字母跳转；菜单项是 `menuitem` 角色。

以下是**本设计自己的约定**：

- 所有图标按钮带 `aria-label`（现有测试按可访问名查询控件，这一约定必须保持）。
- 焦点样式统一走 `--ring`，不依赖浏览器默认。
- 状态与异常不只用颜色传达，同时有文字或图标。

落地时每一条都须有对应用例实测；**未实测的行为不得写进 spec 的 AC**——库承诺
与我们的验收是两回事。

## 7. 对现有测试的影响

覆盖率范围的处置见 decision-00001 §4，此处只讲设计后果。

现有测试按可访问名（role + name）查询的部分——`Accept` / `Clarify` / `Save` /
`Close` 按钮、工具栏的可访问名——预期继续成立。以下五类断言**必然需要改写，且
不构成可访问性回归**，实施者不应把它们当真实回归去追：

1. **`<select>` 专属 API**：`toolbar.test.tsx` 的 `getByLabelText(...).options`
   与 `userEvent.selectOptions(...)`，以及 `canvas.test.tsx` 中同类调用。
   `DropdownMenu` 上没有 `HTMLSelectElement.options`。
2. **`Preview` / `Edit` 两态按钮改 `Tabs`**：两个 tab 的可访问名为 `Source` 与
   `Preview`，role 从 `button` 变 `tab`；`preview.test.tsx` 中相关断言全部受影响。
3. **顶栏 `Find a document` 输入框的两处用例**（`canvas.test.tsx`）：该控件由
   命令面板取代，须按 FR-26/FR-27 的新 AC 重写。
4. **动作被拒的观察点**（`board.test.tsx` 对 `result.current.message` 的断言）：
   改走 `toast.error` 后，hook 的 message 通路随之改变。
5. **非零异常计数的文案**（`canvas.test.tsx` 的 `1 issues`）：Badge 化后可能变化。
   为零时的 `no issues` 按 §3 保持不变，那两处断言应当继续通过。

落地时另有四处改写超出上述五类，均有据可依，一并记明以免日后被误读为回归：
异常 problems 移入 `Popover` 后，节点面上查不到其文本（§4 所定）；面板状态从
三选一拆为两个独立开关（§2 所定）；编辑器自身的保存/冲突提示也改走提示条（§3
所定，§7 原先只点名了 `board.test` 的通路）；「再次点击 Clarify 收起」这一行为
随对话框化消失，未保留替代覆盖。

**第二轮（布局与边）另有三处必然失败，同样不是回归**（§2、§4 与
decision-00002 所致）：

6. **`board.test.tsx::places every node without overlapping`**：它的取样是
   `prd-00001-x` 与 `idea-00001-x`——两个**不同类型**，新布局下二者在同一行
   的两列，`expect(placed[0]!.y).not.toBe(placed[1]!.y)` 因此必失败。它同时是
   `record-00001` 中 `spec-00001-AC-1.2` 的唯一证据行，须一并换掉。
7. **`canvas.test.tsx::carries the relation as the edge label`**：整对象相等
   断言，`toFlowEdges()` 增加 `sourceHandle`/`targetHandle`/`markerEnd` 后必失败。
8. **两个函数的签名要加参数**：`layoutGraph(graph)` 与 `toFlowEdges(graph)` 现为
   单参（`web/src/layout.ts:19`、`web/src/canvasModel.ts:20`），前者需要列序、
   后者需要两端位置。受影响的调用点：`board.test.tsx:94,102,107`、
   `canvas.test.tsx:39,49,53,59,66`、`web/src/useBoard.ts:26`、
   `web/src/Board.tsx:49`。

以上两轮之外查询不到的控件、或断言不成立的行为，才按真实回归处理。

**jsdom 需要补的桩**（本段两次落地后的实际清单）：`web/test/setup.ts` 目前有
`matchMedia`、空实现的 `ResizeObserver`、`localStorage`、三个 pointer-capture
方法与 `scrollIntoView`、`Range.getClientRects`、三个 SVG 度量方法。画布要画出
边还缺两个：**会上报尺寸的 `ResizeObserver`**（React Flow 只在两端节点被测量后
才画边；entry 必须带 `borderBoxSize`，否则 `react-resizable-panels` 会读到
undefined）与 **`DOMMatrixReadOnly`**。下一段是首次落地时写下的判断，其中对现有
桩清单的描述已被上面这份取代：

Radix 的
`DropdownMenu` / `Dialog` / `Popover` 在 jsdom 下通常还需
`Element.prototype.hasPointerCapture`、`releasePointerCapture`、`scrollIntoView`
与 `PointerEvent`。现有 `matchMedia` 桩恒返回 `matches: false`，须改为可按用例
配置——理由是主题相关组件在 jsdom 下需要一个可控的系统偏好才能渲染两种形态，
不是为了给「跟随系统」写 GWT（§8 已裁定它不写）。

实施完成后须更新 `record-00001`：既有验收行中被改名的用例名要同步，且本次新增的
FR-26/FR-27 及其 AC 需要新的验收行。

## 8. 验收归属

新增的用户可见行为按以下方式归属，已由产品负责人裁定：

| 行为 | 归属 |
| --- | --- |
| 命令面板的检索与跳转 | `spec-00001-FR-26`、`FR-27` 及其 AC |
| 动作被拒与错误的提示条 | 不新增 FR——凡承诺拒绝或错误呈现的 FR（FR-5、FR-7…FR-9、FR-16、FR-18…FR-20）本已承诺该行为，改的只是呈现载体，其 AC 的观察点随之更新 |
| 主题三态、面板布局与尺寸记忆、保存中态、空状态、异常计数呈现 | `spec-00001` §7 非功能项，不写 GWT |

不写 GWT 的项没有回归保护，这是明知的取舍。两处例外：空画布「无错误」由 AC-1.4
保证（§7 只涉及它长什么样），异常计数为零时的 `no issues` 文案有现存断言守着。
