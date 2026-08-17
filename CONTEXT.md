# Docs Whiteboard

docs 工作流可视化操作台的语境：人与 agent 围绕 `docs/` 目录协作时使用的统一语言。

## Language

**白板（Whiteboard）**：
`docs/` 目录的可视化视图与操作台；Markdown 文件是唯一事实来源，白板可随时丢弃重建。
_Avoid_：画布（指白板内的绘图区域时除外）、看板

**节点（Node）**：
白板上代表一个 docs 文档的图元，展示其类型、id、标题与状态。
_Avoid_：卡片、块

**关系边（Edge）**：
白板上代表一条 front matter 关系声明的图元，自声明方指向被引用方。
_Avoid_：依赖线、引用线

**弱化态（Dim）**：
未选中任何节点时关系边的默认呈现：可见但不夺目，且不带关系名。

**强调态（Emphasis）**：
与当前选中节点相连的关系边的呈现：高对比、带关系名、绘于节点之上。

**压弱（Suppress）**：
选中某节点时，对与它无关的边与节点所做的进一步淡化。
_Avoid_：隐藏（压弱的东西仍然可见）

**关系列表（Relation List）**：
选中节点的工具栏中按关系字段分组列出的全部关系，每项含字段名、方向与对端文档 id。
_Avoid_：依赖列表、引用列表

**需求条目（Requirement Item）**：
spec 的 FR 与 rule 的 BR 的统称——文档内部带正式编号的最小可验收单位；AC 附属于条目，不是条目。
_Avoid_：需求点、规则条款

**验收行（Acceptance Row）**：
record 验收清单表格中「被验 id · 测试 · 结果」的一行，被验 id 是 AC id 或需求条目 id；覆盖状态的唯一证据来源。
_Avoid_：测试行、清单行

**覆盖状态（Coverage）**：
由验收行推导出的需求条目验证情况，三态：已验证、未通过、未覆盖。
_Avoid_：测试状态、通过率

**检视面板（Inspector Panel）**：
选中 spec/rule 节点后停靠右侧、列出其需求条目与覆盖状态的面板；与编辑器互斥占用右侧槽位。
_Avoid_：属性面板、详情栏

**子画布（Sub-canvas）**：
下钻进单份 spec/rule 后的画布：需求条目、AC 与验收行是其中的节点，面包屑返回顶层。
_Avoid_：详情视图、二级白板

**详情面板（Detail Panel）**：
子画布中单击节点后停靠右侧槽位的只读全文视图；卡片管辨认，详情管阅读。
_Avoid_：属性面板、侧边栏

**条目文法（Item Grammar）**：
spec/rule 的条目声明与 record 验收行的机器可读写作形态，各文件夹 README 的「机器可读形态」小节持有；白板与 agent 产出都受它约束。
_Avoid_：格式规范、模板规则

**解析诊断（Parse Diagnostics）**：
不合条目文法的行的显性清单——疑似条目而形态残缺、验收行不合式、无法归属；白板呈现并计数，不使文档节点转异常。
_Avoid_：解析错误（诊断不阻塞任何功能）、警告列表

**锚点（Handle）**：
关系边落在节点上的位置；白板只用它定位既有的关系边，不据此建边。
_Avoid_：接口、连接桩

**类型列（Type Column）**：
白板上属于同一文档类型的那一列；同列即同类型，跨列即跨类型。
_Avoid_：分组、泳道、层

**评审动作（Review Action）**：
文档负责人在节点上做出的把关动作，只有两种：接收、澄清。
_Avoid_：审批、审核操作

**接收（Accept）**：
认可一个 `draft` 文档，将其按文档种类促为 `active`（living doc）或 `open`（work item）。
_Avoid_：通过、批准

**澄清（Clarify）**：
不促进状态，把待澄清点记入文档的 Open Questions，文档保持 `draft`。
_Avoid_：打回、驳回

**状态流转（Status Transition）**：
文档 `status` 沿合法路径的一次变更；合法路径由 `rule-00001-docs-workflow` 的决策表定义。
_Avoid_：状态迁移、状态变更

**促进（Promote）**：
沿状态流转把 `draft` 文档向前推一步（living doc 促为 `active`，work item 促为 `open`）。
_Avoid_：升级、提升

**预览（Preview）**：
编辑器当前缓冲区的渲染视图，与编辑互斥切换；渲染的是未落盘的缓冲区正文，不含 front matter。
_Avoid_：渲染视图、实时预览

**动作被拒（Refusal）**：
系统对一次不合法动作的拒绝及其反馈（非法流转、冲突、文档已不存在等）；反馈以提示条呈现。
_Avoid_：拒绝（该词专指白板不提供的评审动作）、驳回

**命令面板（Command Palette）**：
按 id 或标题检索文档并跳转到该节点的入口，快捷键唤起。
_Avoid_：搜索框、快捷菜单

**异常（Anomaly）**：
文档的 front matter 不合法、或关系指向不存在的文档；白板在对应节点或边上标记它。
_Avoid_：问题、错误、待办问题

**推进（Advance）**：
从一个节点按流程配置发起下一阶段文档的创建（如 idea → prd），由 agent 会话执行。
_Avoid_：流转、派生

**流程配置（Flow Config）**：
机器可读的配置文件，定义文档类型、关系字段、每种类型的下一步候选、agent 命令与写权限约束；白板读它而不解析散文规范。
_Avoid_：流程图、工作流定义

**Agent 会话（Agent Session）**：
白板通过本地 CLI（Claude Code / Codex 等）发起的一次文档代写过程，在内嵌终端中实时交互；写权限按流程配置约束（MVP 默认仅 `docs/`），浏览器断开后会话在服务端存续。
_Avoid_：AI 任务、机器人

**留痕（Audit Trail）**：
白板发起的每次文件变更都落为一次 git commit，commit 信息指明动作与文档 id。
_Avoid_：日志、历史记录

## Example Dialogue

> **Dev**：用户在 prd 节点上点了「接收」，我要把它标成 resolved 吗？
> **Domain expert**：不。prd 是 living doc，接收把它促为 `active`；`resolved` 只属于 work item。这次接收要留痕——一次 commit，写明是对哪个 id 的接收。
> **Dev**：那用户觉得 prd 还没写清楚呢？
> **Domain expert**：那是澄清，不是拒绝——我们没有拒绝这个动作。文档保持 `draft`，把问题记进它的 Open Questions。之后用户可以再推进：点加号，流程配置说 prd 的下一步是 spec，白板就发起一个 agent 会话去写，会话只能动 `docs/`。
