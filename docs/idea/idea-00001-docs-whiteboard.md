---
id: idea-00001-docs-whiteboard
type: idea
status: active
---

# Docs 白板（Whiteboard）：docs 工作流的可视化操作台

> 本 idea 已由 `prd-00001-docs-whiteboard` 承接，现状以 prd 与 spec 为准。
> 正文保留起草时的设想，其中三处已被后续裁定取代：分层布局（§2.1）被
> `decision-00002` 的类型分列布局取代；「两种评审动作」（§4）已扩为接收/
> 澄清/审计三种（decision-00007）；澄清的「手动记录待澄清点」语义（§4）
> 已由 agent 逐题提问取代（decision-00006）。

## 问题陈述

当前 docs 体系（`idea → prd → spec/rule → design → plan → task/issue → record`）
完全依赖文件与 front matter：

- 文档之间的关系（`parent`、`implements`、`informs` 等）只存在于 front matter
  里，只能靠逐个打开文件阅读，无法一眼看到全局结构与依赖链。
- 状态流转（`draft → active/open → resolved/...`）靠手工改字段，容易漏改、
  改错，也看不到"哪些文档还卡在 draft"。
- 从一个阶段推进到下一阶段（如 idea 之后写 prd）需要人记住流程规则，再手工
  发起一次 agent 会话，流程知识没有产品化。

## 产品设想

一个白板式 Web 界面，作为 docs 目录的**视图与操作台**——单一事实来源仍然是
仓库里的 Markdown 文件，白板只是读写这些文件。

### 1. 画布与节点

- 每个 `docs/**/*.md` 文档是一个节点；边由 front matter 的关系字段解析生成。
- 布局自动排版（按 layout 算法，如分层布局），无需手工摆放。
- 节点上显眼地展示并可直接切换 `status`。

### 2. 节点交互

- 点击节点弹出浮窗工具栏。
- 工具栏内的编辑按钮打开前端 Markdown 编辑器，编辑即写回对应文件。

### 3. "+" 推进下一步

- 节点右侧有加号按钮；点击后按 docs 体系的产品流弹出候选的下一步类型
  （如 idea 的下一步是 prd，spec 的下一步是 plan）。
- 选定后，通过 CLI 调起本地的 Claude Code / Codex 等 agent 生成新文档；
  前端内嵌终端视图（terminal 组件）实时交互查看 agent 过程。
- 新文档自动带上正确的 front matter 关系（如 `parent` 指向来源节点）。

### 4. 评审两态

每个 doc 节点提供两种评审动作：**接收**、**澄清**。

- 接收 ≈ 现有的 promote（living doc `draft → active`，work item `draft → open`）。
- 澄清 = 保持 `draft`，并把待澄清点记为 Open Question（可回灌给 agent 继续改）。

## 早期价值判断

- 让 docs 体系"看得见"：关系图、状态分布、流程卡点一目了然，降低体系的学习
  与执行成本。
- 把流程规则（下一步是什么、front matter 怎么填）产品化，减少手工遵守规范的
  负担与出错率。
- 白板 + 内嵌终端把"人评审、agent 执行"的循环收敛到一个界面，接收/澄清两态
  使人的把关动作显式化。
- 文件仍是唯一事实来源，白板可随时丢弃重建，不引入第二套数据。

## 已定方向

1. **流程规则的机器可读来源**：用配置文件定义流程（下一步类型、关系字段），
   白板读配置而不解析 `docs/README.md` 散文。
2. **写回与版本控制**：白板上的编辑和状态切换自动 commit；评审动作（接收/
   澄清）必须留痕。
3. **agent 会话的边界**：MVP 版本 agent 只允许写 docs——权限为 `docs/` 下
   拥有全部权限，不可改动 docs 之外的内容。
4. **形态与范围**：本地单人 MVP（本地服务 + 浏览器），暂不考虑多人协作。
