---
id: spec-00008-whiteboard-navigation-sidebar
type: spec
status: active
parent: prd-00001-docs-whiteboard
---

# Spec: 导航栏与缩略图——文档多了也翻得动

> 工作区左侧一条可收起的导航栏，按类型列归为类型组、组内按行序列出图上全部
> 文档，点一行即定位并选中，选中变化反向同步；画布右下角一张缩略图给出整图
> 位置。
> 取舍全部在案于 decision-00016。

## 1. Context

- canonical terms 见 `CONTEXT.md`：白板、节点、类型列、命令面板、子画布、
  呈现状态、刷新、就近关闭、动作被拒、异常、撞 id。
- 输入：`parent` 为 [prd-00001-docs-whiteboard](../prd/prd-00001-docs-whiteboard.md)
  （功能需求 1「节点图」的浏览侧）；取舍在案于
  [decision-00016-whiteboard-navigation-sidebar](../decision/decision-00016-whiteboard-navigation-sidebar.md)；
  归组与排序规则复用 [decision-00002-whiteboard-layout](../decision/decision-00002-whiteboard-layout.md) §2。
- 本 spec 是 `spec-00001` … `spec-00007` 的**并列新 spec**（Sizing and
  Splitting 第 1 条），同 `parent`，不 supersede 任何一份。
- 零服务端改动：归组、排序与行内容全部由页面从既有的 `GET /api/graph` 与
  `GET /api/config` 载荷推导。
- 本 spec 新增术语（随接收已进 `CONTEXT.md`）：
  - **导航栏（Navigation Sidebar）**：工作区左侧常驻、可收起、宽度可调的
    文档目录：按类型列的列序列出各类型组、组内按行序列出图上全部节点，每行
    含状态、id 与标题；点一行即定位并选中该节点，选中变化时同步高亮。
    _Avoid_：侧边栏（泛指）、文件树、目录树（物理目录不是归组依据）、
    资源管理器。
  - **类型组（Type Group）**：导航栏中同一类型列的文档构成的一组，可折叠；
    组序即列序，组内序即行序。（「类型列」词条的 _Avoid_ 排除「分组」指称
    画布上的列；类型组是导航栏一侧对同一集合的名字，两词各守一处。）
    _Avoid_：分组、文件夹、目录。
  - **缩略图（MiniMap）**：画布一角的整图缩略视图，每个节点一个按状态着色
    的缩略块，标出当前视口范围。
    _Avoid_：小地图、概览图、导航器。
- 随接收需修订 `CONTEXT.md`「呈现状态」词条：枚举增「导航栏的开合与各组
  展开态」。

## 2. Stories

| Story | Value | Delivers |
| --- | --- | --- |
| S1 | 作为文档负责人，我要在一张列表里按类型翻看全部文档、点一下就落到那个节点上，这样文档多到一列长过屏幕时也不用在画布上拖着找 | spec-00008-FR-1, spec-00008-FR-2, spec-00008-FR-8 |
| S2 | 作为文档负责人，我在画布上或命令面板里选中一份文档后，要在列表里看到它在哪、旁边是谁 | spec-00008-FR-3 |
| S3 | 作为文档负责人，我进来先看到一份按类型的目录，点开关心的类型组再看行，也能收起整条导航栏，而且下次打开还是我上次的样子 | spec-00008-FR-4, spec-00008-FR-5 |
| S4 | 作为文档负责人，文档增删改后列表要跟着变，我展开的组不要被合上 | spec-00008-FR-6 |
| S5 | 作为文档负责人，我要一眼知道自己在整图的哪个位置 | spec-00008-FR-7 |

## 3. Business Rules

| Rule set | Doc | Covers |
| --- | --- | --- |
| Docs 工作流 | [rule-00001-docs-workflow](../rule/rule-00001-docs-workflow.md) | 不因导航而变；本 spec 不新增业务规则 |

## 4. System Requirements

- **spec-00008-FR-1** (Ubiquitous) 系统应在工作区左侧提供导航栏，列出图上
  全部节点：**按类型列归为类型组**，组序即画布列序（`decision-00002` §2：
  已声明类型按 `whiteboard.config.yaml` 声明顺序，未声明类型随后按名字典序，
  `type` 缺失者最后），没有文档的类型不成组；**组内按画布行序**（id 升序，
  同 id 按路径）；组头呈现类型名（未声明类型呈现其原名，`type` 缺失的组呈现
  `untyped`）与该组文档数；每行呈现状态、id 与标题——
  撞 id 节点的 id 位呈现路径并列其撞的 id（同节点 ④ 行口径，
  `spec-00002-FR-8`），异常节点的状态位呈现异常而非状态词
  （`spec-00001-FR-2` 的口径）。
- **spec-00008-FR-2** (Event) 当用户点击导航栏的一行时，系统应定位并选中该
  节点（与命令面板跳转同一通路，`spec-00001-FR-27`）；处于子画布时先退出
  子画布与其详情（与面包屑「Board」同构，`spec-00001-FR-36`）。点击当前已
  选中的行同样走此通路（视口重居中于它）；所指文档不在图上的拒绝侧由 FR-8
  持有。
- **spec-00008-FR-3** (Event) 当当前选中变化时——不论来源是画布点选、命令
  面板、三份清单、会话面板还是导航栏本身——系统应在导航栏高亮该节点的行并
  将其滚入视野；该行所在组处于折叠态则展开该组；选中取消时无行高亮。导航栏
  处于收起态时不做任何事，重新展开时按当时的选中补做高亮与滚入视野。
- **spec-00008-FR-4** (Event) 当用户点击一个组头时，系统应展开或折叠该组
  （折叠态只呈现组头与计数、行不呈现）；各组展开态按类型持久于浏览器本地
  （不入 `docs/`），**缺省全部折叠**——首屏只有类型组的目录，点开才见行
  （域主 2026-09-02 第二次裁定，推翻初版的缺省展开：百余份文档全展开首屏杂乱）。
  用户折叠的是当前选中行所在的组时，该组保持折叠——FR-3 的展开只随选中
  **变化**发生，不随折叠动作反弹（域主 2026-09-02 裁定）。
- **spec-00008-FR-5** (Event) 当用户点击顶栏的导航栏开关时，系统应收起或
  展开导航栏；开合态持久于浏览器本地，缺省展开。
- **spec-00008-FR-6** (Event) 当刷新到达时，系统应按新图重建导航栏：新增
  文档的行出现在其组与行序位置，消失文档的行消失（组随最后一行消失），类型、
  状态、标题的变化即时反映；各组展开态按类型键、选中行高亮按文档 id **保持**，
  不因重建而重置（就近关闭：消失的是选中文档时，高亮随选中一并消失）。
- **spec-00008-FR-7** (Ubiquitous) 系统应在画布右下角显示缩略图：图上每个
  节点一个缩略块、按节点状态着色（异常节点取异常色）、标出当前视口范围；
  顶层白板与子画布皆有，缩略内容随画布内容切换。
- **spec-00008-FR-8** (Unwanted) 若被点击的行所指文档已不在图上（推送尚未
  到达页面的竞态窗口），系统应按动作被拒的既有口径以提示条拒绝，当前选中、
  视口与子画布状态不变（`spec-00001-AC-57.8` 的就近拒绝）；再次点击同一行
  得到同样的拒绝。

**Acceptance (GWT)**

- **spec-00008-AC-1.1** (spec-00008-FR-1)
  Given 图上有 `spec`、`plan`、`record` 三类文档，配置列序为 spec → plan →
  record，`plan` 类型有三份、id 不按加载顺序，三组均已展开
  When 白板呈现
  Then 导航栏依次为 spec、plan、record 三组，`plan` 组头计数为 3，组内三行按
  id 升序，每行含状态词、id 与标题
- **spec-00008-AC-1.2** (spec-00008-FR-1)
  Given 图上另有一份 `type: memo`（配置未声明）与一份 `type` 缺失的文档
  （组头即可观察，无需展开）
  When 白板呈现
  Then 导航栏末尾依次是名为 `memo` 的组与名为 `untyped` 的组，两者都在全部
  已声明类型之后
- **spec-00008-AC-1.3** (spec-00008-FR-1)
  Given 两份文档声明同一个 id，所在组已展开
  When 白板呈现
  Then 两者各占一行，id 位呈现各自的文件路径并列那个撞的 id
- **spec-00008-AC-1.4** (spec-00008-FR-1)
  Given 一份文档的 front matter 不合法（异常节点），所在组已展开
  When 白板呈现
  Then 其行的状态位呈现异常标记而非状态词
- **spec-00008-AC-1.5** (spec-00008-FR-1)
  Given `docs/` 下没有任何可列文档
  When 白板呈现
  Then 导航栏没有任何组，画布的空态提示照常
- **spec-00008-AC-2.1** (spec-00008-FR-2)
  Given 顶层白板，未选中任何节点
  When 用户点击导航栏中 `plan-00002` 的行
  Then `plan-00002` 节点被选中且视口居中于它
- **spec-00008-AC-2.2** (spec-00008-FR-2)
  Given 正处于某份 spec 的子画布且其详情面板打开
  When 用户点击导航栏中另一份文档的行
  Then 子画布与详情关闭、面包屑消失，该文档节点在顶层被选中且视口居中于它
- **spec-00008-AC-2.3** (spec-00008-FR-2)
  Given `plan-00002` 已选中，用户随后把视口拖离了它
  When 用户再次点击导航栏中 `plan-00002` 的行
  Then 视口重新居中于 `plan-00002`，选中不变
- **spec-00008-AC-3.1** (spec-00008-FR-3)
  Given 各类型组均已展开，未选中任何节点
  When 用户在画布上点选 `spec-00001` 节点
  Then 导航栏中 `spec-00001` 的行呈高亮态并被滚入视野，其余行不高亮
- **spec-00008-AC-3.2** (spec-00008-FR-3)
  Given `spec` 组已折叠
  When 用户经命令面板跳转到 `spec-00001`
  Then `spec` 组展开，`spec-00001` 的行呈高亮态
- **spec-00008-AC-3.3** (spec-00008-FR-3)
  Given `spec-00001` 已选中且其行高亮
  When 用户点击画布空白处取消选中
  Then 导航栏无任何行高亮
- **spec-00008-AC-3.4** (spec-00008-FR-3)
  Given 导航栏已收起，`spec` 组处于折叠态
  When 用户在画布上点选 `spec-00001` 节点，随后展开导航栏
  Then 展开后的导航栏中 `spec` 组已展开，`spec-00001` 的行呈高亮态并被滚入视野
- **spec-00008-AC-4.1** (spec-00008-FR-4)
  Given `plan` 组折叠、含三份文档
  When 用户点击 `plan` 组头
  Then 该组的三行呈现，组头与计数 3 仍在
- **spec-00008-AC-4.2** (spec-00008-FR-4)
  Given 用户展开了 `plan` 组
  When 重新打开白板
  Then `plan` 组仍为展开态，其余组折叠
- **spec-00008-AC-4.3** (spec-00008-FR-4)
  Given 从未展开过任何组
  When 打开白板
  Then 全部组为折叠态，只见组头与计数
- **spec-00008-AC-4.4** (spec-00008-FR-4)
  Given `plan` 组已展开
  When 用户点击 `plan` 组头
  Then 该组的行不再呈现，组头与计数仍在
- **spec-00008-AC-4.5** (spec-00008-FR-4)
  Given `plan-00002` 已选中且其行高亮（所在组因选中而展开）
  When 用户点击 `plan` 组头折叠该组
  Then 该组保持折叠，不被选中态反弹展开
- **spec-00008-AC-5.1** (spec-00008-FR-5)
  Given 从未操作过导航栏开关
  When 打开白板
  Then 导航栏呈现
- **spec-00008-AC-5.2** (spec-00008-FR-5)
  Given 导航栏呈现
  When 用户点击顶栏的导航栏开关
  Then 导航栏收起、画布占满工作区左侧
- **spec-00008-AC-5.4** (spec-00008-FR-5)
  Given 导航栏已收起
  When 用户点击顶栏的导航栏开关
  Then 导航栏重现
- **spec-00008-AC-5.3** (spec-00008-FR-5)
  Given 用户已收起导航栏
  When 重新打开白板
  Then 导航栏保持收起
- **spec-00008-AC-6.1** (spec-00008-FR-6)
  Given 导航栏呈现，`plan` 组有 `plan-00001` 与 `plan-00003`
  When 新增 `plan-00002` 的刷新到达
  Then `plan` 组计数变为 3，`plan-00002` 的行出现在 `plan-00001` 与
  `plan-00003` 之间
- **spec-00008-AC-6.2** (spec-00008-FR-6)
  Given `plan` 组折叠、`spec` 组展开
  When 一次不涉及这两组文档的刷新到达
  Then `plan` 组仍折叠、`spec` 组仍展开
- **spec-00008-AC-6.7** (spec-00008-FR-6)
  Given `spec-00001` 已选中并高亮
  When 一次不涉及它的刷新到达
  Then `spec-00001` 的行仍高亮
- **spec-00008-AC-6.3** (spec-00008-FR-6)
  Given `spec-00001` 已选中并高亮
  When 删除 `spec-00001` 的刷新到达
  Then 其行消失，导航栏无行高亮
- **spec-00008-AC-6.4** (spec-00008-FR-6)
  Given `issue` 组只有一份文档
  When 删除该文档的刷新到达
  Then `issue` 组整组消失
- **spec-00008-AC-6.5** (spec-00008-FR-6)
  Given 一份 `draft` 文档在导航栏中呈现
  When 其状态变为 `active` 的刷新到达
  Then 其行的状态词变为 `active`
- **spec-00008-AC-6.6** (spec-00008-FR-6)
  Given 图上没有任何 `task` 文档，配置列序中 `task` 在 `plan` 之后、`issue` 之前
  When 新增第一份 `task` 文档的刷新到达
  Then 导航栏在 `plan` 组与 `issue` 组之间出现 `task` 组，计数为 1
- **spec-00008-AC-7.1** (spec-00008-FR-7)
  Given 顶层白板有若干状态不一的节点与一个异常节点
  When 白板呈现
  Then 缩略图中每个节点一个缩略块，块按各自状态着色，异常节点取异常色，
  且呈现视口范围标记
- **spec-00008-AC-7.2** (spec-00008-FR-7)
  Given 顶层白板已呈现缩略图
  When 用户下钻进一份 spec 的子画布
  Then 缩略图改为呈现子画布的节点
- **spec-00008-AC-7.3** (spec-00008-FR-7)
  Given `docs/` 下没有任何可列文档
  When 白板呈现
  Then 缩略图仍呈现，且不含任何缩略块
- **spec-00008-AC-8.1** (spec-00008-FR-8)
  Given 导航栏仍列着 `plan-00002`，而它已在磁盘上删除且推送尚未到达
  When 用户点击该行
  Then 出现拒绝提示条，当前选中与视口不变
- **spec-00008-AC-8.2** (spec-00008-FR-8)
  Given 同 AC-8.1，且用户已点击过该行一次并得到拒绝
  When 用户再次点击该行
  Then 再次出现拒绝提示条，当前选中与视口仍不变

## 5. Technical Design

| Design | Doc | Covers |
| --- | --- | --- |
| 白板 UI（第二十四轮） | [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) | §2 布局的左侧区域；§17 导航栏的停靠、内容构造、联动与持久化，缩略图的着色与令牌 |

本 spec 无服务端设计改动。

## 6. Out of Scope

- 导航栏内的过滤或搜索输入框——检索归命令面板（`decision-00016` §2 第 3 条）
- 按物理目录归组、手动排序、收藏或置顶（`decision-00016` §3）
- 画布内的列折叠或分页（`decision-00002` §4 明确不做）
- 切换导航栏的键盘快捷键（`decision-00016` §3 搁置）
- 列表虚拟化（`decision-00016` §2 第 9 条）
- 收起或展开导航栏后的视口重居中（与终端面板同口径，`decision-00016` §4）
- 缩略图的拖动平移与滚轮缩放——React Flow 的库承诺，按 design-00002 §6
  的约定不写 AC

## 7. Non-Functional

- 导航栏的每一行与每个组头都是可聚焦、可激活的真控件，键盘与鼠标同权；
  选中行的高亮不只靠颜色（design-00002 §6 的既有约定）。
- 导航栏宽度可调并被记住，与既有面板的尺寸记忆同口径（design-00002 §8
  「面板布局与尺寸记忆」不写 GWT）。
- 设计目标规模为本仓当前量级（约 100 份文档）；数百份内不做虚拟化。

## Links

- Parent: [prd-00001-docs-whiteboard](../prd/prd-00001-docs-whiteboard.md)
- Sibling specs: [spec-00001-docs-whiteboard](spec-00001-docs-whiteboard.md)
  （命令面板跳转 FR-27、子画布 FR-35…FR-37）·
  [spec-00002-whiteboard-governance](spec-00002-whiteboard-governance.md)（撞 id FR-8）
- Rules: [rule-00001-docs-workflow](../rule/rule-00001-docs-workflow.md)
- Design: [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) §17
- Decisions: [decision-00016-whiteboard-navigation-sidebar](../decision/decision-00016-whiteboard-navigation-sidebar.md)（本 spec 的全部取舍）·
  [decision-00002-whiteboard-layout](../decision/decision-00002-whiteboard-layout.md)（归组与排序规则的来源）
- Plan: [plan-00024-whiteboard-navigation-sidebar](../plan/plan-00024-whiteboard-navigation-sidebar.md)
