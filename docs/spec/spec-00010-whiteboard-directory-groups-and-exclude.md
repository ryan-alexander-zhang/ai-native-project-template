---
id: spec-00010-whiteboard-directory-groups-and-exclude
type: spec
status: active
parent: prd-00001-docs-whiteboard
---

# Spec: 配置排除与目录组——文档多了、有子目录了也看得清

> 流程配置增 `exclude` glob 列表，命中的文件对白板不存在；类型目录的第一级子目录
> 在其类型列内折叠为一个目录组节点，导航栏同构呈现，展开态两处共享、缺省折叠、
> 选中落入即自动展开。取舍全部在案于 decision-00018。

## 1. Context

- canonical terms 见 `CONTEXT.md`：白板、节点、关系边、弱化态、强调态、压弱、
  类型列、导航栏、类型组、缩略图、命令面板、异常、异常清单、撞 id、呈现状态、
  刷新、动作被拒、流程配置、子画布。
- 输入：`parent` 为 [prd-00001-docs-whiteboard](../prd/prd-00001-docs-whiteboard.md)
  ；取舍在案于
  [decision-00018-whiteboard-directory-groups-and-exclude](../decision/decision-00018-whiteboard-directory-groups-and-exclude.md)；
  分列与行序规则复用 [decision-00002-whiteboard-layout](../decision/decision-00002-whiteboard-layout.md) §2；
  导航栏的类型组行为复用 [spec-00008-whiteboard-navigation-sidebar](spec-00008-whiteboard-navigation-sidebar.md)。
- 本 spec 是 `spec-00001` … `spec-00009` 的**并列新 spec**（Sizing and
  Splitting 第 1 条），同 `parent`，不 supersede 任何一份。它**修订**以下
  `active` 文档的断言，全部随本 spec 接收在原文**原地追注**指向本 spec
  （`spec-00003` 对 `spec-00001-FR-14` 的追注先例）：
  - `prd-00001` 功能需求 1：「解析 `docs/**/*.md`」**收窄**为「减去 `exclude`
    命中者」；
  - `spec-00001-FR-1`：扫描范围同上；列内行序在顶层文档之后接目录组；
  - `spec-00001-FR-15`：流程配置的键枚举增 `exclude` 及其拒绝分支
    （`max_sessions` 经 `spec-00003-AC-3.4`/`AC-3.5` 进入 FR-15 的先例）；
  - `spec-00002-FR-8`：撞 id 的判定集合收窄为可见文档（FR-11）；`docs/README.md`
    「id 全仓唯一」与「撞 id 在每一份声明它的文件上标记异常」两句随之收窄为白板
    可见的文件（域主 2026-09-03 裁定：不存在没有例外）；
  - `rule-00001-BR-18`：「该类型现有最大编号」以白板可见文档为准（FR-12）；
  - `spec-00003-FR-10`：节点会话标记在组节点上以聚合形态出现，激活聚合标记只
    展开目录组、不呈现会话（FR-5、FR-6）；
  - `spec-00008-FR-1`：类型组内不再是平铺的「图上全部节点」，而是顶层文档之后
    接目录组；`spec-00008-FR-3`：选中联动须一并展开目录组；`spec-00008-FR-7`：
    折叠的目录组是一个缩略块；`spec-00008` §6 Out of Scope 的「按物理目录归组」
    与「画布内的列折叠或分页」两条随 `decision-00018` 失效；
  - `decision-00002` §2 行序与 §4「不做折叠」、`decision-00016` §3 两行与 §5
    一句：由 `decision-00018` §5 持有。
- 服务端改动为扫描范围（FR-1）与流程配置校验（FR-2）两处；其**下游后果**触及
  三处既有行为，逐项承接：关系与行内 id 的可解析集合收窄（FR-3、FR-11）；
  新建取号只计可见文档（FR-12）；共写收口对被排除路径沿用现状（§6、AC-1.13）。目录组的归组、折叠
  与导航栏镜像全部由页面从既有的 `GET /api/graph`（节点带相对 `docs/` 的
  `path`）与 `GET /api/config` 载荷推导，`/api/graph` 的契约不变。
- 本 spec 新增术语（随接收进 `CONTEXT.md`）：
  - **配置排除（Exclude）**：流程配置 `exclude` 字段列出的、相对 `docs/` 的
    glob 模式集；命中的文件对白板不存在——不成节点、不进异常与诊断、不可检索、
    其文档 id 与条目 id 不可被解析。与**模板文件排除**（文件名为 `README.md` /
    `TEMPLATE.md` 者不作为文档，`spec-00001-AC-1.3`）叠加，两者互不替代。
    _Avoid_：忽略（ignore，gitignore 的联想会带来「仍占位」的误读）、隐藏、过滤。
  - **模板文件排除（Template-file Exclusion）**：既有规则的名字——任何层级下
    文件名为 `README.md` 或 `TEMPLATE.md` 的文件不作为文档。_Avoid_：README
    排除、内置排除。
  - **目录组（Directory Group）**：同一类型列内、`docs/` 下路径前两段相同的文档
    构成的一组；组键即那两段路径；在画布上以一个组节点占位、在导航栏中以类型组
    之下的一层呈现；可折叠，两处共享展开态。_Avoid_：文件夹、子目录（指物理
    目录本身时除外）、分组、子组。
  - **组节点（Group Node）**：折叠态目录组在其类型列内占据的那个图元：呈现组名
    与文档数，带异常与会话的聚合标记，点击即展开或折叠；它代表一组文档而不是
    一份，因此不是「节点」词条意义上的节点，不可被选中为文档。_Avoid_：文件夹
    节点、聚合节点、父节点。
  - **组名（Group Name）**：组节点与导航栏组头呈现的名字，取自组键：组键第一段
    与该列类型同名时只呈现第二段（`stripe`），否则呈现两段（`spec/stripe`）。_Avoid_：目录名（指物理目录时除外）、标题。
  - **汇聚边（Aggregate Edge）**：折叠态下落在组节点上的关系边，代表组内文档
    与对端之间按 `spec-00001-FR-28` 口径合并后的若干条声明；展开即拆回真节点。
    _Avoid_：聚合边、折叠边、组边。
  - **顶层文档（Top-level Document）**：路径不足三段的文档——直接位于类型目录
    下，或直接位于 `docs/` 下——不属于任何目录组。_Avoid_：根文档、散文件。
- 随接收需修订 `CONTEXT.md` 既有词条：
  - 「节点」：「代表一个 docs 文档的图元」保留，补一句「组节点不是节点」；
  - 「关系边」：补「落在组节点上的汇聚边代表组内文档的若干条声明」；
  - 「导航栏」：正文「组内按行序列出图上全部节点」改为「组内先列顶层文档、再列
    目录组」；_Avoid_ 括注「物理目录不是归组依据」改为「物理目录不是分列依据」；
  - 「类型组」：补「其下可再分目录组」；_Avoid_ 的「目录」加限定「（指其归组
    依据时）」；
  - 「呈现状态」：枚举增「各目录组的展开态（画布与导航栏共享）」，括注「导航栏的
    两项还跨页面重载保持」改为「导航栏的两项与各目录组的展开态还跨页面重载保持」；
  - 「缩略图」：「每个节点一个按状态着色的缩略块」补「折叠的目录组一块」，持有方
    加 `spec-00010-FR-10`；
  - 「流程配置」：枚举增「配置排除（`exclude`）」。

## 2. Stories

| Story | Value | Delivers |
| --- | --- | --- |
| S1 | 作为文档负责人，我要在配置里声明哪些路径是语料而不是文档，这样抓取的镜像与原始材料不会以上千个异常节点的形态淹没白板 | spec-00010-FR-1, spec-00010-FR-2, spec-00010-FR-3, spec-00010-FR-11, spec-00010-FR-12 |
| S2 | 作为文档负责人，一个类型有几十上百份文档、按子目录分册时，我要在画布上先看到「正文 + 几个分册」，点开分册才见其中的文档 | spec-00010-FR-4, spec-00010-FR-5, spec-00010-FR-6 |
| S3 | 作为文档负责人，我在导航栏里要看到与画布同一份结构：类型组之下是目录组，在一处展开另一处也展开 | spec-00010-FR-8 |
| S4 | 作为文档负责人，我从命令面板、异常清单、关系列表或会话面板落到分册里的某份文档时，它所在的分册要自动打开，不要让我先去找它在哪个组 | spec-00010-FR-7 |
| S5 | 作为文档负责人，文档增删移动后目录组要跟着变，我展开的组不要被合上 | spec-00010-FR-9 |
| S6 | 作为文档负责人，缩略图上折叠的分册只占一块，我仍能一眼知道自己在整图哪里 | spec-00010-FR-10 |

## 3. Business Rules

| Rule set | Doc | Covers |
| --- | --- | --- |
| Docs 工作流 | [rule-00001-docs-workflow](../rule/rule-00001-docs-workflow.md) | 不因配置排除与目录组而变；本 spec 不新增业务规则。`rule-00001-BR-18` 的「现有最大编号」以白板可见文档为准（FR-12），随接收在 BR-18 原文追注 |

## 4. System Requirements

- **spec-00010-FR-1** (Event) 当白板加载或刷新时，系统应在解析 `docs/` 下的
  `.md` 之前先按流程配置 `exclude` 列出的 glob 模式排除命中的文件（与模板文件
  排除叠加）：命中的文件**对白板不存在**——不成节点、不进异常清单与诊断清单、
  不可被命令面板检索、不出现在导航栏、其变更不改变图。glob 语义：模式相对
  `docs/`、以 `/` 分隔；`*` 匹配单段内任意字符、不跨 `/`；`**` 跨任意层；只
  匹配文件路径，目录形态的模式（如 `reference/stripe`）不命中其下的文件；区分
  大小写；不支持 `!` 取反；命中为空或命中全部都不是错误、不提示。`exclude`
  缺失、为 `null` 或为空列表时不排除任何文件，行为与今天相同。`exclude` 只在
  启动时读取（design-00001 §3 的既有口径），改动经重启生效，刷新按启动时读到
  的模式排除。
- **spec-00010-FR-2** (Unwanted) 若 `exclude` 存在且不是字串列表（标量、映射、
  含非字串元素的列表），或列表中有空串、含 `..` 段、含 `\`、以 `/` 起头或以
  `!` 起头的模式，
  系统应拒绝启动白板并点名 `exclude` 与不合法之处（与 `max_sessions` 非正整数
  的拒绝同口径）；不得静默忽略该字段而以「不排除」启动；配置未改时再次启动
  得到同一拒绝。
- **spec-00010-FR-3** (Unwanted) 若某文档的关系字段、或某处行内 id 跳转
  （`spec-00001-FR-57`），指向一个**仅**被排除文件声明的文档 id 或条目 id，
  系统应把该边判为无法解析（按 `spec-00001-FR-2` 的断链边处置，异常归属声明
  方）、把该行内 id 判为不可点击（`spec-00001-FR-58` 的处置）；再次刷新得到
  同样的判定。
- **spec-00010-FR-4** (Ubiquitous) 系统应把同一类型列内、`docs/` 下路径前两段
  （`<文件夹>/<子目录>`）相同的文档归为一个**目录组**，组键即那两段路径：更深
  层级的文档归入其**第一级**子目录（路径第二段）；路径不足三段的文档是顶层
  文档，不属于任何目录组；一个目录内若有不同 `type` 的文档，则按列各成一组
  （分列依据仍是 `type`，`decision-00002` §2）；`type` 缺失或未声明的文档在其
  所落的列内同样按目录成组。不设数量阈值：只含一份可见文档的子目录也成组；
  可见文档为零的子目录（只有模板文件、或全部被排除）不成组。列内顺序为：
  顶层文档按 id 升序（同 id 按路径）在前，目录组按组键字典序在后；组内按 id
  升序、同 id 按路径。
- **spec-00010-FR-5** (State) 当一个目录组处于折叠态时，系统应在其类型列内以
  一个**组节点**占一个节点位：呈现组名与组内文档数——组名取自组键，组键第一段
  与该列类型同名时只呈现第二段，否则呈现两段；组内任一文档为异常节点则
  组节点带异常标记，但顶栏异常计数与异常清单仍逐份计入组内文档、不因折叠而
  变；组内任一文档有会话则组节点带会话标记——组内有任一会话处于等待输入即取
  等待态、否则取运行态（`spec-00003-FR-10` 的两态在组上聚合；答疑与终端形态
  同一标记，`spec-00005-FR-9` 的口径）；组内文档不作为节点呈现；组内文档
  与外部节点之间的关系边落到组节点上，按 `spec-00001-FR-28` 的既有口径合并
  ——同一对、同方向合为一条、标签并列全部字段名，反向的边不合并；组内文档
  互指的边不呈现；两个折叠组之间的边按同一口径合并。组节点参与
  `spec-00001-FR-28`/`FR-29` 的呈现：无选中时其汇聚边为弱化态；与当前选中经
  汇聚边相连时不被压弱、该汇聚边取强调态，否则组节点被压弱。组节点不提供浮窗工具栏、不可被选中为文档。（进入与
  离开折叠态的行为见 FR-6。）
- **spec-00010-FR-6** (Event) 当用户点击组节点、或激活组节点上的会话标记时，
  系统应展开该目录组：组节点保留在原位（组名与计数仍在），组内文档按行序紧随
  其下成为普通节点，该列其余行相应下移，落在组节点上的汇聚边拆回各自的真节点；
  再次点击组节点即折叠回 FR-5 的形态。激活会话标记只做展开，会话本身从展开后
  真节点上的标记进入（`spec-00003-FR-10` 不在组节点上重演）。各目录组的展开态
  按「列 × 组键」持久于浏览器本地（不入 `docs/`），**缺省折叠**（持久与缺省两句
  是状态承诺，其用例的 When 为白板加载）。用户折叠的是当前选中文档所在的组时，
  该组保持折叠、选中不清：组节点取选中态呈现（与被选中的文档节点同一呈现、不被
  压弱），与它相连的汇聚边取强调态，选中文档的浮窗工具栏关闭，右槽（检视面板或
  编辑器）与命令面板不受影响。
- **spec-00010-FR-7** (Event) 当当前选中**变化**为一份处于折叠目录组内的文档
  时——不论来源是命令面板、三份清单（全局覆盖率视图、异常清单、诊断清单）、
  关系列表、导航栏、会话面板、行内 id 跳转还是详情面板的 record 跳转——系统
  应先展开该组再定位并选中该节点；面包屑返回不改变选中（`spec-00001-FR-36`
  「返回即选中」的是下钻前已选中的文档，其所在组已因该次选中而展开），不在此列；选中变化为已展开组内或顶层的
  文档时不改变任何组的展开态。展开只随选中变化发生，不随 FR-6 的折叠动作反弹。
- **spec-00010-FR-8** (Ubiquitous) 导航栏（`spec-00008-FR-1`）应在每个类型组
  之内镜像 FR-4 的列内结构：先列顶层文档的行，再列各目录组，目录组头呈现组名
  与文档数，折叠态只呈现组头、展开态其下列出组内文档的行（行内容同
  `spec-00008-FR-1`）；类型组头的计数含组内文档。目录组的展开态与画布的是
  **同一份**：在任一处展开或折叠，另一处随之改变；点击目录组头即切换该组的
  展开态。`spec-00008-FR-3` 的选中联动对目录组内的行同样成立：选中落入折叠
  目录组内的行时，其类型组与目录组一并展开、该行高亮并滚入视野。
- **spec-00010-FR-9** (Event) 当刷新到达时，系统应按新图重建目录组：新出现的
  组键成为新目录组、缺省折叠、落在其组键序的位置；可见文档归零的目录组随之
  消失；一份文档在顶层与子目录之间、或两个子目录之间移动，即离开原位置、进入
  新位置并改变相应计数；各目录组的展开态按「列 × 组键」保持、不因重建而重置
  ——键含列与组键，所以目录改名或组内文档改 `type` 而换列时旧键失效，该组从
  缺省折叠起；就近关闭：消失的是选中文档时，选中随之消失（`spec-00008-FR-6`
  同口径）。
- **spec-00010-FR-10** (Ubiquitous) 缩略图（`spec-00008-FR-7`）应把折叠态的目录
  组呈现为一个缩略块：组内含异常节点时取异常色，否则取组节点专用的一种令牌色
  （design-00002 §1 令牌表与 §17.4 需增，组节点没有 status 可取）；展开态下组
  节点仍为一块、取该令牌色，组内每份文档各一块、按其状态着色。
- **spec-00010-FR-11** (Ubiquitous) 一个 id 的归属只在可见文档中判定：一个被
  排除文件与一个未被排除文档声明同一 id 时**不构成撞 id**（`spec-00002-FR-8`
  的「文档」由本条收窄为可见文档），该 id 归未被排除的那一份；两个被排除文件
  声明同一 id 时图上无任何异常。（本条修订 `spec-00002-FR-8` 的判定集合与
  `docs/README.md` 的「全仓唯一」「每一份声明它的文件」两句——域主 2026-09-03
  裁定：不占号与不报撞 id 是同一个「不存在」的两面。）
- **spec-00010-FR-12** (Ubiquitous) 白板新建文档的取号（`rule-00001-BR-18`
  「现有最大编号加一」）应只计可见文档：被排除文件声明的编号不占号。

**Acceptance (GWT)**

- **spec-00010-AC-1.1** (spec-00010-FR-1)
  Given 流程配置 `exclude: ['reference/*/source/**']`，`docs/reference/stripe/source/a.md`
  没有 front matter，`docs/reference/stripe/summary.md` 是合式 reference
  When 打开白板
  Then `summary.md` 为一个节点，`a.md` 不是节点、不在异常清单、异常计数不计它
- **spec-00010-AC-1.2** (spec-00010-FR-1)
  Given 流程配置没有 `exclude` 字段，`docs/reference/stripe/source/a.md` 没有 front matter
  When 打开白板
  Then `a.md` 以文件路径为键成为一个异常节点（`spec-00001-FR-2` 的现状）
- **spec-00010-AC-1.3** (spec-00010-FR-1)
  Given 同 AC-1.1 且白板已呈现
  When `docs/reference/stripe/source/a.md` 在磁盘上被修改并触发刷新
  Then 图与刷新前相同，没有任何节点或异常出现或消失
- **spec-00010-AC-1.4** (spec-00010-FR-1)
  Given `exclude: ['reference/*/source/**']`，`docs/reference/stripe/README.md`
  与 `docs/reference/stripe/source/README.md` 都存在
  When 打开白板
  Then 两份 README 都不是节点——前者因模板文件排除，后者兼因两条规则
- **spec-00010-AC-1.5** (spec-00010-FR-1)
  Given 同 AC-1.1，`a.md` 的首个 H1 为「Stripe Webhooks」
  When 用户在命令面板输入「webhooks」
  Then 结果中没有 `a.md`
- **spec-00010-AC-1.6** (spec-00010-FR-1)
  Given `exclude: []`
  When 打开白板
  Then 白板启动，`docs/` 下每份非模板文件的 `.md` 都是节点
- **spec-00010-AC-1.7** (spec-00010-FR-1)
  Given 流程配置 `exclude:`（键在、值为 `null`）
  When 打开白板
  Then 白板启动且不排除任何文件，与 AC-1.6 相同
- **spec-00010-AC-1.8** (spec-00010-FR-1)
  Given `exclude: ['reference/*/notes.md']`，`docs/reference/stripe/notes.md` 与
  `docs/reference/stripe/source/notes.md` 都是合式 reference
  When 打开白板
  Then 前者不是节点，后者是节点（`*` 不跨 `/`）
- **spec-00010-AC-1.9** (spec-00010-FR-1)
  Given `exclude: ['reference/stripe']`，`docs/reference/stripe/` 下有两份合式 reference
  When 打开白板
  Then 两份都是节点（目录形态的模式不命中其下文件）
- **spec-00010-AC-1.10** (spec-00010-FR-1)
  Given `exclude: ['**']`
  When 打开白板
  Then 白板启动、画布的空态提示照常、异常计数为零，不出现任何与 `exclude` 相关
  的提示或异常
- **spec-00010-AC-1.11** (spec-00010-FR-1)
  Given `exclude: ['nowhere/**']`，`docs/` 下没有名为 `nowhere` 的目录
  When 打开白板
  Then 图与没有 `exclude` 时相同，没有任何提示
- **spec-00010-AC-1.12** (spec-00010-FR-1)
  Given 白板以 `exclude: []` 启动并已呈现，`docs/reference/stripe/source/a.md`
  是一个异常节点
  When 用户把配置改为 `exclude: ['reference/*/source/**']` 并触发一次刷新
  Then `a.md` 仍是异常节点；重启白板后它不再是节点
- **spec-00010-AC-1.13** (spec-00010-FR-1)
  Given `exclude: ['reference/*/source/**']`，一个共写会话把产出写到了
  `docs/reference/stripe/source/reference-00031-x.md`
  When 该会话收口
  Then 该文件按 `spec-00006-FR-6` 判为不合式并被删除，理由为「白板读不出该文档」
- **spec-00010-AC-2.1** (spec-00010-FR-2)
  Given 流程配置 `exclude: 'reference/*/source/**'`（标量而非列表）
  When 启动白板
  Then 白板拒绝启动，输出点名 `exclude` 且说明它须为字串列表
- **spec-00010-AC-2.2** (spec-00010-FR-2)
  Given 流程配置 `exclude: ['reference/*/source/**', 42]`
  When 启动白板
  Then 白板拒绝启动，输出点名 `exclude` 中不是字串的那一项
- **spec-00010-AC-2.3** (spec-00010-FR-2)
  Given 同 AC-2.1 且启动已被拒绝一次，配置未改
  When 再次启动白板
  Then 再次以同一输出拒绝启动
- **spec-00010-AC-2.4** (spec-00010-FR-2)
  Given 流程配置 `exclude: { reference: true }`（映射）
  When 启动白板
  Then 白板拒绝启动，输出点名 `exclude` 且说明它须为字串列表
- **spec-00010-AC-2.5** (spec-00010-FR-2)
  Given 流程配置 `exclude: ['']`
  When 启动白板
  Then 白板拒绝启动，输出点名 `exclude` 中的空串
- **spec-00010-AC-2.6** (spec-00010-FR-2)
  Given 流程配置 `exclude: ['../secrets/**']`
  When 启动白板
  Then 白板拒绝启动，输出点名 `exclude` 中含 `..` 的那一项
- **spec-00010-AC-2.7** (spec-00010-FR-2)
  Given 流程配置 `exclude: ['/reference/**']`
  When 启动白板
  Then 白板拒绝启动，输出点名 `exclude` 中以 `/` 起头的那一项
- **spec-00010-AC-2.8** (spec-00010-FR-2)
  Given 流程配置 `exclude: ['!reference/**']`
  When 启动白板
  Then 白板拒绝启动，输出点名 `exclude` 中以 `!` 起头的那一项并说明不支持取反
- **spec-00010-AC-2.9** (spec-00010-FR-2)
  Given 流程配置 `exclude: ['reference\\stripe\\**']`
  When 启动白板
  Then 白板拒绝启动，输出点名 `exclude` 中含 `\` 的那一项并说明模式一律用 `/`
- **spec-00010-AC-3.1** (spec-00010-FR-3)
  Given `exclude: ['reference/*/source/**']`，`docs/reference/stripe/source/b.md`
  声明 `id: reference-00099-stripe-b`，`design-00002` 声明
  `informs: [reference-00099-stripe-b]`
  When 打开白板
  Then `design-00002` 到 `reference-00099-stripe-b` 的边判为无法解析，异常归属
  `design-00002`，异常清单中该条的来源是 `design-00002`
- **spec-00010-AC-3.2** (spec-00010-FR-3)
  Given `exclude: ['spec/archive/**']`，`docs/spec/archive/spec-00042-old.md`
  正文声明 `spec-00042-FR-1`，某 record 声明 `verifies: [spec-00042-FR-1]`
  When 打开白板
  Then 该 record 的这条边判为无法解析，异常归属该 record
- **spec-00010-AC-3.3** (spec-00010-FR-3)
  Given 同 AC-3.2，某 spec 的检视面板中某条目的展开行内有行内代码 `spec-00042-FR-1`
  When 该检视面板呈现
  Then 该行内 id 呈现为不可点击（`spec-00001-FR-58` 的形态）
- **spec-00010-AC-3.4** (spec-00010-FR-3)
  Given 同 AC-3.1 且白板已呈现该断链
  When 一次不涉及这两份文档的刷新到达
  Then 该边仍判为无法解析，异常清单中该条仍在且只有一条
- **spec-00010-AC-4.1** (spec-00010-FR-4)
  Given `docs/reference/` 下有顶层文档 `reference-00002`、`reference-00001`，
  子目录 `stripe/` 含三份 reference，子目录 `ccbill/` 含两份 reference，
  两个目录组均折叠
  When 打开白板
  Then reference 列自上而下为 `reference-00001`、`reference-00002`、组键
  `reference/ccbill` 的组节点（计数 2）、组键 `reference/stripe` 的组节点（计数 3）
- **spec-00010-AC-4.2** (spec-00010-FR-4)
  Given `docs/reference/stripe/source/deep/x.md` 是一份合式 reference，
  `reference/stripe` 目录组已展开
  When 打开白板
  Then `x.md` 的节点在 `reference/stripe` 目录组之内，该组计数含它，画布上
  没有组键含 `source` 或 `deep` 的组节点
- **spec-00010-AC-4.3** (spec-00010-FR-4)
  Given `docs/reference/stripe/` 下有两份 `type: reference` 与一份
  `type: analysis` 的文档
  When 打开白板
  Then reference 列有一个组键 `reference/stripe` 的组节点计数 2，analysis 列有
  一个组键 `reference/stripe` 的组节点计数 1
- **spec-00010-AC-4.4** (spec-00010-FR-4)
  Given `docs/reference/stripe/source/` 下有三份没有 front matter 的文件，
  流程配置没有 `exclude`
  When 打开白板
  Then `untyped` 列有一个组键 `reference/stripe` 的组节点计数 3，`untyped` 列
  没有这三份文件各自的节点
- **spec-00010-AC-4.5** (spec-00010-FR-4)
  Given 某类型列只有顶层文档、没有任何子目录
  When 打开白板
  Then 该列没有任何组节点，行序与今天相同
- **spec-00010-AC-4.6** (spec-00010-FR-4)
  Given `docs/x.md` 是一份声明 `type: reference` 的合式文档（路径只有一段）
  When 打开白板
  Then 它是 reference 列的顶层文档，位于该列全部组节点之前，不属于任何目录组
- **spec-00010-AC-4.7** (spec-00010-FR-4)
  Given `docs/reference/manifest/` 下只有一份合式 reference
  When 打开白板
  Then reference 列有一个组键 `reference/manifest` 的组节点、计数 1
- **spec-00010-AC-4.8** (spec-00010-FR-4)
  Given `docs/reference/empty/` 下只有 `README.md`，`docs/reference/mirror/` 下的
  文件全部被 `exclude` 命中
  When 打开白板
  Then 画布与导航栏中都没有组键 `reference/empty` 或 `reference/mirror` 的组
- **spec-00010-AC-5.1** (spec-00010-FR-5)
  Given `reference/stripe` 目录组含三份 reference、处于折叠态
  When 白板呈现
  Then reference 列中有一个呈现组名与「3」的组节点，三份 reference 都不是画布
  上的节点
- **spec-00010-AC-5.2** (spec-00010-FR-5)
  Given 折叠的 `reference/stripe` 目录组内有一份 front matter 非法的文档，图上
  另有一处异常
  When 白板呈现
  Then 该组节点带异常标记，顶栏异常计数为 2，异常清单两条各指其文档
- **spec-00010-AC-5.3** (spec-00010-FR-5)
  Given `design-00002` 声明 `informs` 指向折叠的 `reference/stripe` 组内两份 reference
  When 白板呈现
  Then `design-00002` 与该组节点之间恰有一条边，箭头落在组节点一端
- **spec-00010-AC-5.4** (spec-00010-FR-5)
  Given 折叠的 `reference/stripe` 组内 `reference-00012` 声明
  `supersedes: [reference-00011]`，二者同组
  When 白板呈现
  Then 画布上没有这条边
- **spec-00010-AC-5.5** (spec-00010-FR-5)
  Given 折叠的 `reference/stripe` 组内某份文档有一个运行中的会话
  When 白板呈现
  Then 该组节点带会话标记
- **spec-00010-AC-5.6** (spec-00010-FR-5)
  Given 折叠的 `reference/stripe` 组节点
  When 用户点击它
  Then 不出现浮窗工具栏，当前选中不变（组展开的行为由 AC-6.1 持有）
- **spec-00010-AC-5.7** (spec-00010-FR-5)
  Given 折叠的 `reference/stripe` 组与折叠的 `reference/ccbill` 组，`stripe` 内
  两份文档各以 `informs` 指向 `ccbill` 内不同的文档
  When 白板呈现
  Then 两个组节点之间恰有一条边；未选中任何节点，故它为弱化态、不带关系名
- **spec-00010-AC-5.8** (spec-00010-FR-5)
  Given 折叠的 `reference/stripe` 组内两份文档分别被 `decision-00003` 以
  `constrains`、被 `design-00002` 以 `informs` 指向，且 `design-00002` 又以
  `motivated_by` 指向组内第三份文档，`design-00002` 已选中
  When 白板呈现
  Then `decision-00003` 与组节点之间一条边、因与选中无关而被压弱、不带关系名；
  `design-00002` 与组节点之间一条边、取强调态、标签并列 `informs` 与 `motivated_by`
- **spec-00010-AC-5.9** (spec-00010-FR-5)
  Given 折叠的 `reference/stripe` 组内 `reference-00012` 声明
  `informs: [design-00002]`，同时 `design-00002` 声明
  `motivated_by: [reference-00013]`，`reference-00013` 也在该组
  When 白板呈现
  Then 组节点与 `design-00002` 之间有两条方向相反的边，不合并
- **spec-00010-AC-5.10** (spec-00010-FR-5)
  Given `design-00002` 以 `informs` 指向折叠的 `reference/stripe` 组内一份文档，
  折叠的 `reference/ccbill` 组与 `design-00002` 无任何边
  When 用户点选 `design-00002`
  Then `reference/stripe` 组节点不被压弱、其到 `design-00002` 的汇聚边取强调态，
  `reference/ccbill` 组节点被压弱
- **spec-00010-AC-5.11** (spec-00010-FR-5)
  Given 折叠的 `reference/stripe` 组节点有一条汇聚边，未选中任何节点
  When 白板呈现
  Then 该边为弱化态、不带关系名
- **spec-00010-AC-5.12** (spec-00010-FR-5)
  Given 折叠的 `reference/stripe` 组内一份文档的会话运行中、另一份文档的会话
  等待输入
  When 白板呈现
  Then 该组节点的会话标记为等待态
- **spec-00010-AC-5.13** (spec-00010-FR-5)
  Given 折叠的组键 `reference/stripe` 的组落在 reference 列
  When 白板呈现
  Then 其组节点的组名为「stripe」
- **spec-00010-AC-5.14** (spec-00010-FR-5)
  Given `docs/spec/stripe/x.md` 声明 `type: reference`，与 `docs/reference/stripe/`
  下的文档同落 reference 列，两组均折叠
  When 白板呈现
  Then 两个组节点的组名分别为「spec/stripe」与「stripe」
- **spec-00010-AC-6.1** (spec-00010-FR-6)
  Given 折叠的 `reference/stripe` 组含三份 reference，其下方另有
  `reference/vendor` 组节点
  When 用户点击 `reference/stripe` 组节点
  Then 三份 reference 按 id 序出现在该组节点之下成为节点，该组节点仍在且计数
  为 3，`reference/vendor` 组节点下移三行
- **spec-00010-AC-6.2** (spec-00010-FR-6)
  Given 折叠的 `reference/stripe` 组，`design-00002` 以 `informs` 指向组内两份文档，
  二者之间因此只有一条汇聚边
  When 用户点击该组节点展开它
  Then `design-00002` 到这两份文档各有一条边，到组节点没有边
- **spec-00010-AC-6.3** (spec-00010-FR-6)
  Given `reference/stripe` 组已展开
  When 用户点击该组节点
  Then 组内文档不再是节点，该组回到 AC-5.1 的形态
- **spec-00010-AC-6.4** (spec-00010-FR-6)
  Given 用户展开了 `reference/stripe` 组
  When 重新打开白板
  Then `reference/stripe` 组仍为展开态，其余目录组折叠
- **spec-00010-AC-6.5** (spec-00010-FR-6)
  Given 从未展开过任何目录组
  When 打开白板
  Then 全部目录组为折叠态
- **spec-00010-AC-6.6** (spec-00010-FR-6)
  Given `spec/archive` 组已展开，组内 `spec-00042` 已选中、浮窗工具栏打开、
  检视面板呈现
  When 用户点击该组节点折叠该组
  Then 该组折叠、`spec-00042` 仍为当前选中、浮窗工具栏关闭、检视面板不变，
  该组节点取选中态呈现且与它相连的汇聚边呈强调态
- **spec-00010-AC-6.7** (spec-00010-FR-6)
  Given 折叠的 `reference/stripe` 组节点带会话标记
  When 用户激活该会话标记
  Then `reference/stripe` 组展开
- **spec-00010-AC-7.1** (spec-00010-FR-7)
  Given `reference/stripe` 组折叠，组内有 `reference-00012`
  When 用户经命令面板选定 `reference-00012`
  Then `reference/stripe` 组展开，`reference-00012` 被选中且视口居中于它
- **spec-00010-AC-7.2** (spec-00010-FR-7)
  Given `reference/stripe` 组折叠，组内 `reference-00012` 是异常节点
  When 用户点击异常清单中它的那一条
  Then `reference/stripe` 组展开，`reference-00012` 被选中
- **spec-00010-AC-7.3** (spec-00010-FR-7)
  Given `design-00002` 已选中，其关系列表含指向折叠的 `reference/stripe` 组内
  `reference-00012` 的一项
  When 用户点击该项
  Then `reference/stripe` 组展开，`reference-00012` 被选中
- **spec-00010-AC-7.4** (spec-00010-FR-7)
  Given `reference/stripe` 组与 `reference/ccbill` 组均折叠
  When 用户经命令面板选定顶层文档 `reference-00001`
  Then 两个组仍折叠
- **spec-00010-AC-7.5** (spec-00010-FR-7)
  Given `reference/stripe` 组折叠，组内 `reference-00012` 有一个运行中的会话
  When 用户在会话面板点选该会话
  Then `reference/stripe` 组展开，`reference-00012` 被选中，终端面板呈现该会话
- **spec-00010-AC-7.6** (spec-00010-FR-7)
  Given 某份 spec 的检视面板中某条目的展开行内有行内代码 `reference-00012`，它处于折叠的
  `reference/stripe` 组内
  When 用户单击该行内 id
  Then `reference/stripe` 组展开，`reference-00012` 被选中
- **spec-00010-AC-8.1** (spec-00010-FR-8)
  Given `docs/reference/` 有两份顶层文档与折叠的 `reference/stripe`（3 份）、
  `reference/ccbill`（2 份）两个目录组，导航栏 reference 类型组已展开
  When 白板呈现
  Then reference 类型组头计数 7，其下依次为两份顶层文档的行、`reference/ccbill`
  目录组头（计数 2）、`reference/stripe` 目录组头（计数 3），两个目录组头之下
  没有行
- **spec-00010-AC-8.2** (spec-00010-FR-8)
  Given 同 AC-8.1
  When 用户点击导航栏中 `reference/stripe` 目录组头
  Then 导航栏该组头之下列出三行，画布上 `reference/stripe` 组同时展开为 AC-6.1
  的形态
- **spec-00010-AC-8.3** (spec-00010-FR-8)
  Given 同 AC-8.1
  When 用户在画布上点击 `reference/stripe` 组节点
  Then 导航栏 `reference/stripe` 目录组头之下列出三行
- **spec-00010-AC-8.4** (spec-00010-FR-8)
  Given 导航栏 reference 类型组与 `reference/stripe` 目录组均折叠
  When 用户经命令面板选定 `reference/stripe` 组内的 `reference-00012`
  Then 导航栏 reference 类型组与 `reference/stripe` 目录组均展开，
  `reference-00012` 的行高亮并滚入视野
- **spec-00010-AC-8.5** (spec-00010-FR-8)
  Given `reference/stripe` 目录组已展开、其内 `reference-00012` 的行高亮
  When 用户点击 `reference/stripe` 目录组头折叠它
  Then 该目录组保持折叠，不被选中态反弹展开
- **spec-00010-AC-9.1** (spec-00010-FR-9)
  Given reference 列有 `reference/ccbill` 与 `reference/stripe` 两个目录组
  When 新增 `docs/reference/netbilling/reference-00030-x.md` 的刷新到达
  Then reference 列在两者之间出现折叠的 `reference/netbilling` 组节点，计数 1
- **spec-00010-AC-9.2** (spec-00010-FR-9)
  Given `reference/ccbill` 目录组只有一份文档
  When 删除该文档的刷新到达
  Then 该组节点与导航栏中的该目录组头一并消失
- **spec-00010-AC-9.3** (spec-00010-FR-9)
  Given `reference/stripe` 组已展开、`reference/ccbill` 组折叠
  When 一次不涉及这两组文档的刷新到达
  Then `reference/stripe` 仍展开、`reference/ccbill` 仍折叠
- **spec-00010-AC-9.4** (spec-00010-FR-9)
  Given 顶层文档 `reference-00002` 在图上，`reference/stripe` 组计数 3
  When 把它移动到 `docs/reference/stripe/` 下的刷新到达
  Then 它离开顶层文档的行，`reference/stripe` 组计数变为 4
- **spec-00010-AC-9.5** (spec-00010-FR-9)
  Given `reference/stripe` 组已展开，组内 `reference-00012` 已选中
  When 删除 `reference-00012` 的刷新到达
  Then 其节点消失、当前选中为空、`reference/stripe` 组仍展开且计数减一
- **spec-00010-AC-9.6** (spec-00010-FR-9)
  Given `reference/stripe` 组已展开
  When 把 `docs/reference/stripe/` 改名为 `docs/reference/stripe-v2/` 的刷新到达
  Then `reference/stripe` 组消失，出现折叠的 `reference/stripe-v2` 组、计数不变
- **spec-00010-AC-9.7** (spec-00010-FR-9)
  Given `reference/ccbill` 组已展开，组内唯一的文档 `reference-00020` 已选中
  When 删除 `reference-00020` 的刷新到达
  Then 该组消失，当前选中为空
- **spec-00010-AC-9.8** (spec-00010-FR-9)
  Given `reference/stripe` 组计数 3、`reference/ccbill` 组计数 2
  When 把 `reference-00012` 从 `stripe/` 移到 `ccbill/` 的刷新到达
  Then `reference/stripe` 组计数 2、`reference/ccbill` 组计数 3
- **spec-00010-AC-9.9** (spec-00010-FR-9)
  Given `docs/reference/stripe/` 下唯一的文档在 reference 列的 `reference/stripe`
  组内，该组已展开
  When 该文档的 `type` 改为 `analysis` 的刷新到达
  Then reference 列不再有该组，analysis 列出现组键 `reference/stripe` 的组、
  处于折叠态
- **spec-00010-AC-10.1** (spec-00010-FR-10)
  Given 折叠的 `reference/stripe` 组含三份 reference，其中一份异常
  When 白板呈现
  Then 缩略图中该组对应一个缩略块、取异常色，没有那三份文档各自的块
- **spec-00010-AC-10.2** (spec-00010-FR-10)
  Given `reference/stripe` 组已展开、组内三份文档状态不一
  When 白板呈现
  Then 缩略图中该组节点一块取组节点令牌色，三份文档各一块、各按自身状态着色
- **spec-00010-AC-10.3** (spec-00010-FR-10)
  Given 折叠的 `reference/ccbill` 组内两份文档均无异常
  When 白板呈现
  Then 缩略图中该组对应一个取组节点令牌色的缩略块
- **spec-00010-AC-11.1** (spec-00010-FR-11)
  Given `exclude: ['reference/*/source/**']`，`docs/reference/stripe/source/b.md`
  与未被排除的 `docs/reference/reference-00099-stripe-b.md` 都声明
  `id: reference-00099-stripe-b`，`design-00002` 声明 `informs: [reference-00099-stripe-b]`
  When 打开白板
  Then `reference-00099-stripe-b` 是一个以该 id 为键的正常节点、不标撞 id，
  `design-00002` 的那条边解析到它
- **spec-00010-AC-11.2** (spec-00010-FR-11)
  Given `exclude: ['reference/*/source/**']`，`docs/reference/stripe/source/b.md`
  与 `docs/reference/ccbill/source/c.md` 都声明 `id: reference-00099-dup`
  When 打开白板
  Then 图上没有任何异常，异常计数为零
- **spec-00010-AC-12.1** (spec-00010-FR-12)
  Given `exclude: ['reference/*/source/**']`，可见 reference 的最大编号为 `00003`，
  被排除的 `docs/reference/stripe/source/b.md` 声明 `id: reference-00099-b`
  When 用户经顶栏新建一份 reference
  Then 新文档的 id 编号为 `00004`
- **spec-00010-AC-12.2** (spec-00010-FR-12)
  Given 流程配置没有 `exclude`，reference 的最大编号为 `00003`
  When 用户经顶栏新建一份 reference
  Then 新文档的 id 编号为 `00004`（与今天相同）

## 5. Technical Design

| Design | Doc | Covers |
| --- | --- | --- |
| 服务端（新修订轮） | [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md) | §3 流程配置契约的 `exclude` 字段、glob 语义与校验；§2 `docRepository` 的扫描范围；§11 写明共写收口对被排除路径沿用现状 |
| 白板 UI（新修订轮） | [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) | §1 令牌表增组节点令牌色；§2 列内构造增目录组；§4 组节点形态、聚合标记与压弱；§17.1/§17.2 导航栏的目录组一层与共享展开态；§17.3 选中联动扩到目录组；§17.4 缩略图着色 |

## 6. Out of Scope

- 多级嵌套折叠（`decision-00018` §2 第 5 条）
- 用组内 `README.md` 的标题命名目录组（`decision-00018` §3 搁置）
- 按数量阈值自动折叠或分页（`decision-00018` §3 否决）
- `exclude` 的 `!` 取反与 gitignore 式目录形态（`decision-00018` §3）
- `exclude` 的热重载——改动经重启生效（FR-1）
- 共写收口对被排除路径的特殊处置——沿用 `spec-00006-FR-6` 现状：写进被排除
  路径的产物读不出文档即判不合式并删除（AC-1.13；域主 2026-09-03 裁定）
- 语义缩放（`decision-00018` §3 搁置）
- 在设置面板（`spec-00009`）中编辑 `exclude`（`decision-00018` §3 搁置）
- 目录组的手动排序、重命名或颜色（`decision-00018` §5 站立约束：不得为目录组
  引入 `docs/` 之外的排序或命名状态）
- 组节点自身的浮窗工具栏、关系列表或检视面板——组不是文档
- 导航栏的虚拟化（`decision-00016` §2 第 9 条不变）；画布的虚拟化未提出
- 展开目录组后的视口调整（沿用 `decision-00016` §4「布局变化不重居中」的口径）

## 7. Non-Functional

- 组节点与导航栏目录组头都是可聚焦、可激活的真控件，键盘与鼠标同权；选中态
  呈现与异常标记不只靠颜色（design-00002 §6 的既有约定）。
- 设计目标规模：配置排除后的可见文档数百份、单个目录组数十至百余份；不做
  虚拟化。不配 `exclude` 时目录组只承诺**画布**可读，不承诺异常清单、命令面板
  与解析用时的规模。
- glob 匹配在既有的路径归一化之后进行（`listDocFiles` 已把路径统一为 `/` 分隔），
  模式一律用 `/` 书写。

## Links

- Parent: [prd-00001-docs-whiteboard](../prd/prd-00001-docs-whiteboard.md)
- Sibling specs: [spec-00001-docs-whiteboard](spec-00001-docs-whiteboard.md)
  （扫描与布局 FR-1、异常 FR-2、配置校验 FR-15、命令面板 FR-26/FR-27、边的
  三态与合并 FR-28/FR-29、子画布 FR-35…FR-37、行内 id 跳转 FR-57…FR-59）·
  [spec-00002-whiteboard-governance](spec-00002-whiteboard-governance.md)
  （撞 id FR-8、异常清单 FR-13、清单定位 FR-15）·
  [spec-00003-whiteboard-parallel-sessions](spec-00003-whiteboard-parallel-sessions.md)
  （节点会话标记 FR-10、配置键进 FR-15 的先例 AC-3.4/AC-3.5）·
  [spec-00006-whiteboard-co-write](spec-00006-whiteboard-co-write.md)（共写收口 FR-6）·
  [spec-00008-whiteboard-navigation-sidebar](spec-00008-whiteboard-navigation-sidebar.md)
  （导航栏 FR-1…FR-7）
- Rules: [rule-00001-docs-workflow](../rule/rule-00001-docs-workflow.md)
- Design: [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md) §2、§3、§11 ·
  [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) §2、§4、§17
- Decisions: [decision-00018-whiteboard-directory-groups-and-exclude](../decision/decision-00018-whiteboard-directory-groups-and-exclude.md)（本 spec 的全部取舍）·
  [decision-00002-whiteboard-layout](../decision/decision-00002-whiteboard-layout.md)（分列与行序）·
  [decision-00016-whiteboard-navigation-sidebar](../decision/decision-00016-whiteboard-navigation-sidebar.md)（类型组）
