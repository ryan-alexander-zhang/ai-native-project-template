# Docs Whiteboard

docs 工作流可视化操作台的语境：人与 agent 围绕 `docs/` 目录协作时使用的统一语言。

## Language

**白板（Whiteboard）**：
`docs/` 目录的可视化视图与操作台；Markdown 文件是唯一事实来源，白板可随时丢弃重建。
_Avoid_：画布（指白板内的绘图区域时除外）、看板

**节点（Node）**：
白板上代表一个 docs 文档的图元，展示其类型、id、标题与状态。
_Avoid_：卡片、块

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
