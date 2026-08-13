---
id: decision-00001-whiteboard-ui-stack
type: decision
status: active
motivated_by: [spec-00001-docs-whiteboard]
constrains: [design-00002-whiteboard-ui, plan-00002-whiteboard-ui]
---

# Decision: 白板前端采用 Tailwind CSS + shadcn/ui + Lucide

> 白板的界面用手写 CSS 与原生控件搭成，交互密度已超出它能承载的范围；改用
> Tailwind 4 + shadcn/ui + Lucide React 作为 UI 基座。

## 1. 需要做这个决定的原因

白板 MVP 的界面是 212 行手写 CSS 加原生 `<select>`/`<button>`：

- 节点动作（状态切换、推进）用 `<select>` 承载，语义是「选一个值」，实际是
  「执行一个动作」；澄清表单直接塞在浮窗工具栏里。
- 动作被拒的信息是画布下方一条红条，出现时压缩画布、挤动布局。
- 文档类型只有一行文字标签，没有图标；状态靠一个颜色加一个词。
- 没有暗色模式、没有统一的焦点样式、没有加载与空状态。

`spec-00001` §7 已经要求「节点状态一眼可辨」「图随规模增长仍可读」，这些是界面
承诺，而当前实现靠一次性 CSS 兑现，继续加功能会线性增加手写样式与可访问性债。

## 2. 决定

采用：

| 层 | 选型 | 角色 |
| --- | --- | --- |
| 样式 | Tailwind CSS 4（`@tailwindcss/vite`） | 原子化样式与设计令牌的唯一来源 |
| 组件 | shadcn/ui（CLI 拷入源码） | 对话框、下拉菜单、命令面板等有可访问性要求的复合控件 |
| 图标 | lucide-react | 文档类型、动作、状态的统一图标语言 |

shadcn/ui **不是运行时依赖**：它的 CLI 把组件源码拷进仓库。被拷入的组件依赖
Radix UI primitives、`class-variance-authority`、`clsx`、`tailwind-merge`，另按用到
的组件引入 `cmdk`（命令面板）、`sonner`（提示条）、`react-resizable-panels`
（可调尺寸面板）。「拷入而非安装」是本决定最重要的性质，见 §4。

本表的具体版本与依赖形态（含 Radix 是逐包还是统一包）**在实施第一步实测确认后
回填**；未确认前本决定不得进入 `active`。同样待实测确认的还有：Tailwind 4 的
CSS-first 配置与本仓 Vite 8 的兼容性、design-00002 中每一个 Lucide 图标标识符在
所装版本中确实存在。这些都是装一次包即可判定的事实，因此是接收前的验证清单，
不是 Open Question。

## 3. 考虑过的其他选项

| 选项 | 为什么不选 |
| --- | --- |
| 维持手写 CSS | 零依赖，但对话框、下拉菜单、命令面板的键盘导航与焦点陷阱要自己实现——这正是最容易做错、且错了最难发现的部分 |
| MUI / Ant Design / Chakra | 开箱即用，但它们是运行时依赖且主题体系强势；白板主体是一块自绘画布（React Flow），套整套设计语言会在画布内外产生两种视觉体系 |
| Tailwind + Radix（不用 shadcn） | 同样的可访问性底座，但每个控件的变体样式要从零写；shadcn 提供的正是这层已经调好的样式，且拷进来后可改 |
| 只加 Lucide 图标，不动样式 | 成本最低，但解决不了「用 select 执行动作」「红条挤动布局」这类结构问题 |

## 4. 后果

**接受的代价**

- **拷入的组件成为本仓库代码**。`THIRDPARTY.md` 只覆盖「不 vendored 的参考
  代码」，因此这些文件不归它管，而是像自有代码一样进 git、进评审。
- 由此产生一个门禁问题：`TESTING.md` 要求可执行代码 90% 覆盖率，而拷入的组件
  多数分支我们从不使用。处置是把 `tools/whiteboard/web/src/components/ui/**`
  排除出覆盖率统计。**这条排除有边界**：
  - 只覆盖 shadcn CLI 生成后**未经修改**的文件。任何被我们改动（超出 CLI 输出）
    的文件必须移出该目录、或从排除名单中单独摘出，按 `TESTING.md` 的 90% 计
    ——否则「可以直接改这些文件」就变成了对自有代码免测。
  - 只适用于该目录，**不构成全仓覆盖率政策的先例**。
  - 按 `CODE_QUALITY.md` §6 第 3 档（Suppress：可见 + 有理由）落地：在
    `tools/whiteboard/vitest.config.ts` 的 `coverage.exclude` 增加该目录并就地
    写明上述边界，同时在 `CODE_QUALITY.md` §2 门禁表的 Coverage 行记录 scope。
    （不是 §3——§3 是阈值偏离，本处置不动阈值，只缩小统计范围。）
  - `TESTING.md` 要求例外「approved in advance」，因此本决定进入 `active` 之前
    该排除不得落地。
- 组件更新是手动的：上游修了 bug 不会自动流下来。换来的是可以直接改这些文件而
  不必与库的抽象搏斗（改动后按上面的边界处理）。
- 构建链新增 Tailwind 的 Vite 插件；样式从「一个 style.css」变成「令牌 + 原子类」，
  阅读方式改变。

**得到的**

- 对话框、下拉菜单、命令面板的键盘与焦点行为由 Radix 提供，不再是我们从零实现
  的风险（这些行为在落地时须逐条实测，见 design-00002 §6）。
- 设计令牌集中，暗色模式与「状态一眼可辨」由同一套变量驱动，不再是散落的十六进制。
- 图标语言统一，文档类型与动作在画布内外可以用同一套符号。

**不变的**

- 后端与 `docs/` 数据模型不受影响；本决定只约束 `tools/whiteboard/web/`。
  （唯一的例外是节点种类 living/work 的下发方式，见 design-00002 §4——它需要
  一次 API 或数据来源的选择，不属于本决定。）
- React Flow 与 CodeMirror、xterm 保留；它们各自解决画布、编辑、终端，与本层不冲突。

## 5. 这个决定约束什么

- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) —— 界面
  设计必须建立在这三者之上，不得引入第二套样式体系或第二个图标库。
- 后续任何白板前端改动：新控件优先取自 shadcn/ui，新图标一律取自 Lucide；确需
  自绘时在设计文档中说明理由。
- 本决定进入 `active` 后新建的白板前端 `plan`，须回填进本文件的 `constrains`
  （`docs/decision/README.md` 要求：该列表是覆盖面元数据，回填不算修订）。
