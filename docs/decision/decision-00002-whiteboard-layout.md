---
id: decision-00002-whiteboard-layout
type: decision
status: active
motivated_by: [issue-00003-stage-flow-reads-backwards, spec-00001-docs-whiteboard]
constrains: [spec-00001-docs-whiteboard, design-00001-docs-whiteboard, design-00002-whiteboard-ui, plan-00003-whiteboard-relation-edges, plan-00024-whiteboard-navigation-sidebar, plan-00026-whiteboard-directory-groups-and-exclude]
---

# Decision: 白板布局改为「类型分列」，去掉 ELK

> 白板的列由文档**类型**决定、行由同类型内的 **id 序**决定，方向左→右。
> 这条规则无法用「从关系边推导层次」的布局算法表达，因此 elkjs 退出。

## 1. 需要做这个决定的原因

`spec-00001-FR-1` 要求白板自动布局，但从未说清布局的形状。落地的 ELK layered
布局带来两个后果：

- **方向是反的**（`issue-00003`）。ELK 把关系边的起点排在更靠前的层，而本文档
  体系里关系边的起点是**依赖方**（下游）：`prd` 写 `parent: idea`，于是 `prd`
  被排到 `idea` 之上。
- **同类型文档会被拆开**。`spec-00002 supersedes spec-00001` 是一条边，ELK 因此
  把两份 spec 分到两层；而阅读 docs 时人期望的是「所有 spec 在一起」。

这两点都不是参数调错，而是**层次来自关系边**这一前提的直接结果。

关系字段的语义确实不一致：`parent`、`implements`、`motivated_by`、`verifies`、
`supersedes` 声明在依赖方，`informs`、`constrains`、`blocks` 声明在被依赖方
（`docs/README.md` 原先只把 `constrains` 记为例外，已随本次改动更正为三个）。
但**本决定不建立在这一点上**：一条边在图上朝左还是朝右，取决于两端
类型的列位，而列位不是关系图的拓扑序，所以从字段名推不出方向。支撑本决定的是
上面那两条可直接观察的后果。

## 2. 决定

布局是一个**确定性纯函数**，不依赖任何布局引擎：

| 维度 | 规则 |
| --- | --- |
| 列（x） | 文档 front matter 的 **`type`**（不取 id 前缀）。列序取 `whiteboard.config.yaml` 中 `types` 的**声明顺序**；没有文档的类型不占列 |
| 行（y） | 同列内按**文档 id 升序**（decision-00018 追注：顶层文档在前、目录组按组键序在后，组内仍按此行序）；id 相同时按文件路径升序，使本函数的输出恒为全序。**这并不改善撞 id 的呈现**——`toFlowNodes` 按 id 取位置，两个同 id 节点仍取到同一条，缺陷见 `issue-00004` |
| 方向 | 左→右 |
| 异常/未知类型 | `type` 缺失或不在 `types` 内的节点，排在全部已声明类型之后，按类型名字典序；`type` 缺失者最后一列 |
| 落位时机 | 列序来自 `GET /api/config`，图来自 `GET /api/graph`；**两者都到位后才落位**，不先画一遍无序布局再重排 |

列序即下表，也是 `whiteboard.config.yaml` 中 `types` 应有的声明顺序。它是一份
可读性判断、从仓库推导不出来，**已由产品负责人确认**：

| # | 类型 | 为什么在这个位置 |
| --- | --- | --- |
| 1–2 | `idea`、`prd` | `docs/README.md` 的产品流起点：`idea → prd → spec` |
| 3–5 | `analysis`、`reference`、`integration` | 喂给规格阶段的输入（三者的 `informs` 都指向 `spec`/`design`/`plan`） |
| 6–7 | `spec`、`rule` | 规格与业务规则；流程配置里 `spec` 的下一步之一即 `rule` |
| 8 | `decision` | 它 `constrains` 的是 `design`/`plan`/`operation`，因此排在它们之前 |
| 9 | `design` | 结构设计，`plan` 的输入 |
| 10–11 | `plan`、`task` | 实施与其拆分 |
| 12 | `issue` | 实施中发现的问题，`blocks` 的是 `plan`/`task` |
| 13–14 | `record`、`report` | 验收与产出物 |
| 15–16 | `operation`、`prompt` | 交付后的运行手册与可复用提示词 |

`elkjs` 从依赖中移除，`layoutGraph()` 由 `async` 变为同步。

坐标沿用既有的节点尺寸常量：`x = 列序 × (NODE_WIDTH + 96)`，
`y = 行序 × (NODE_HEIGHT + 48)`。两个间距沿用 ELK 原先的
`nodeNodeBetweenLayers`（跨层，现为跨列）与 `nodeNode`（层内，现为列内），
**但轴向互换，因此没有哪个方向的视觉节奏被保留**：纵向间距从 188px 收到 140px，
横向从 288px 放到 336px。这两个数落地后按实际观感再调，不是本决定的承诺。

**边不参与布局**，只按 front matter 的声明连接两个已定位的节点；箭头指向被
引用的那份文档（即声明方向）。锚点按两端的相对位置选：跨列走左右侧 handle，
同列走上下 handle。

## 3. 考虑过的其他选项

| 选项 | 为什么不选 |
| --- | --- |
| 保留 ELK，把 `elk.direction` 改成 `RIGHT` | 只改了方向，层次仍来自关系边：同类型文档照样被边拆到不同列，`idea` 照样在 `spec` 右边 |
| 保留 ELK，喂进去的边先翻转 | 方向也许能对，但**同类型被拆开的问题一个也没解决**：`spec-00002 supersedes spec-00001` 仍是一条边，两份 spec 照旧落到不同层；且层次仍随文档增删重排，位置不可预期 |
| 保留 ELK，改用 interactive 分层 | 需要为每个节点提供位置提示，等于我们先自己算出列、ELK 只剩层内排序；而层内我们要的恰是可预期的 id 序，不是交叉最小化的结果——留着它只剩体积 |
| 换 dagre / d3-hierarchy | 同类问题：都是从边推导层次的算法，前提没变 |
| 让用户手工摆放并持久化位置 | 与 `spec-00001-AC-1.2`「无需手工摆放」直接冲突；也把 `docs/` 之外的位置状态引入了这个本来无状态的工具 |

## 4. 后果

**接受的代价**

- **没有交叉最小化**。跨越多列的边（例如 `plan → spec`，中间隔着 `rule`、
  `design` 两列）会从中间列的节点**下面**穿过（边在 React Flow 中的 z 序低于
  节点）。原实现其实也没有真正的路由——只取 ELK 的节点坐标、丢弃其弯折点——
  所以这不是新增的代价，但它现在是**明确接受**的：不做边路由。
  升级路径：真需要时在同列内改用交叉最少的行序，而不是换回布局引擎。
- **列序由 YAML 的声明顺序承载**，这是一处「远距离作用」：有人为了整理而重排
  `types` 块，白板的列序就变了。缓解有两条——配置里就地写明该顺序即列序，
  以及一条钉住既定顺序的测试。代价换来的是单一事实来源：新增一个类型只需在
  一处、按想要的位置写下它。
- **布局代码归我们所有**，须按 `TESTING.md` 达到 90% 覆盖率。它是纯函数，
  这部分成本很低。
- **一列一类型会让图变宽**：16 个类型全用上时是 16 列。缓解是空类型不占列，
  实际宽度等于「用到的类型数」。
- **纵向无界**。列高等于该类型的文档数，某一类型文档很多时该列会长过视窗，
  只能靠画布平移与缩放看完。本决定不做纵向分页或折叠（decision-00018 部分推翻：仍不按数量分页或折叠，按目录折叠）；命令面板
  （`spec-00001-FR-26`/`FR-27`）是「找到某一份」的既有出路。

**得到的**

- **位置可预期**。`spec-00003` 一定在 `spec-00002` 下面，`idea` 一定在最左。
  ELK 的交叉最小化会在文档增删时重排整张图，同一份文档换个位置出现。
- **布局与关系方向解耦**。逐字段不同的方向语义再也不能影响布局，`issue-00003`
  那一类缺陷从此不可能发生。
- **纯同步函数**，无需 `await`、无异步测试。
- **构建产物变小**。实测：主 chunk 从 **3,219.71 kB（gzip 995.61 kB）降到
  1,779.67 kB（gzip 551.22 kB）**——少了 1,440 kB，约 45%；gzip 后省 444 kB。
  （`elk.bundled.js` 是 1.53 MiB 由 Java 转译而来的源码，压缩率很差，所以减幅
  比「一个依赖」的直觉大。）

**不变的**

- React Flow、CodeMirror、xterm 与后端图模型都不受影响；`GET /api/graph` 的
  契约不变。
- `spec-00001-AC-1.2`「节点位置由布局算法给出，无需手工摆放」仍然成立——本决定
  换的是算法，不是「自动」这件事。

## 5. 这个决定约束什么

- [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md) ——
  §1 选型表的「自动布局」一行与 §2 模块图不再是 ELK。
- [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) ——
  §2 的画布布局与 §4 的边/锚点规则以本决定为前提。
- 后续任何白板布局改动：不得重新引入「从关系边推导层次」的布局；新增文档类型
  时在 `whiteboard.config.yaml` 的 `types` 中按期望的列位置写入。
- 本决定进入 `active` 后新建的白板布局相关 `plan`，须回填进本文件的
  `constrains`（`docs/decision/README.md`：该列表是覆盖面元数据，回填不算修订）。
