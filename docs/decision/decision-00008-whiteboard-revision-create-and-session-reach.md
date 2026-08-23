---
id: decision-00008-whiteboard-revision-create-and-session-reach
type: decision
status: active
motivated_by: [spec-00001-docs-whiteboard]
constrains: [spec-00001-docs-whiteboard, rule-00001-docs-workflow, design-00001-docs-whiteboard, design-00002-whiteboard-ui]
---

# Decision: 修订轮回 draft、流程入口可新建、会话有历史可选人

> 第十一轮的一组裁定，来自第十轮 review 的缺口清单（域主逐项选定）：living
> doc 新增合法流转 **active → draft**（修订轮——回到 draft 后审计、澄清与
> 接收门全部自然适用）；白板顶栏开**新建入口**，只对流程配置声明的**流程
> 入口类型**（idea、prd）开放；会话转写**落盘成历史**、重启不丢；发起会话时
> **agent 可选**（不再硬取第一条）；可澄清/可审计类型集经 `GET /api/config`
> 下发为单一来源；白板的 commit 改带 `--no-verify`（化解与 pre-commit hook
> 的 draft 拦截冲突）；`lastFinding` 陈旧标记定性为缺陷走 issue；图解析加
> 按变更失效的缓存。多会话并行仍在范围外（域主裁定不做）（该句被
> decision-00009 推翻——原地追注）。

## 1. 需要做这个决定的原因

第十轮 review 对「只用白板从 idea 到上线」的评审留下一组缺口，域主选定其中
三项进入本轮：

- **流程入口断点**：文档只能由 agent 从源文档推进产生，第一份 idea 必须
  板外手写——流程的起点恰恰不在流程里。
- **会话是易失的**：转写只在 1 MB 内存缓冲里，服务一停全部蒸发；`agents`
  配置能声明多条，代码却硬取第一条（`config.agents[0]`），配置形同虚设。
- **active 文档的修订无把关入口**：审计只对 `draft` 开放（BR-23，
  decision-00007 §3 的 MVP 边界），而本仓的 spec/rule/design 全是
  `active`——对它们的实质修订在白板上没有任何评审动作可用，把关只能板外跑。
  根因是状态表没有「修订」这条路：living doc 一旦 `active`，除归档外无路
  可走。

落地过程中还暴露一个机制冲突：repo 的 pre-commit hook 拦截 status 为
`draft` 的文档入库，而 spec 定义的白板行为（FR-14/FR-17/FR-53）恰恰要提交
draft 产物（推进的半成品、新建的第一版）——两个都对的机制撞在一起，必须
裁定谁让路。

## 2. 决定

| # | 做法 | 理由 |
| --- | --- | --- |
| 1 | **修订轮**：living doc 的状态表新增 `active → draft`（BR-3 修订）。回到 `draft` 即进入修订轮：审计（BR-23）、澄清（BR-20）、接收门（BR-12）全部按既有语义自然适用，无任何专属新机制 | 与其为 active 造一套「advisory 审计 + 未决徽标」的平行把关，不如让文档回到唯一有牙齿的状态；「当前有效版本是上一次接收的内容」这一语义由 git 历史承载，与「文件是唯一事实来源」一致 |
| 2 | **新建入口**只对**流程入口类型**开放，列表由流程配置新字段 `entry` 持有（本仓为 `idea`、`prd`）；id 前缀按 BR-18 分配、slug 用户自取，编辑器以该类型 `TEMPLATE.md` 预填，初始 `status: draft`，保存即建档并 commit（`wb(create): <id>`） | 产品流的入口属于产品流，配置是它的既有载体（BR-13…17 同理）；只开入口类型使其余类型仍经推进产生，流程知识不被绕过；预填模板让第一版就长在正确的骨架上 |
| 3 | **会话历史**：每个会话的终端转写落盘 `.whiteboard/sessions/`（已在 gitignore 的 `.whiteboard/` 下），会话结束与服务重启后仍可经白板列出、查看全文；MVP 不做自动清理 | 逐题澄清、审计发现的沉没成本值得保护（与澄清状态文件同一理由，decision-00006 §2 第 5 条）；纯文本转写因为第一读者是人（回看「agent 当时怎么说的」），与 JSON 状态文件的分工互补 |
| 4 | **agent 可选**：发起任何会话可指定流程配置 `agents` 中的一条，缺省取第一条（兼容现状）；指定不存在的条目拒绝且不启动；UI 仅在配置多于一条时呈现选择 | 配置早已允许声明多条（每条都要求先过 AC-13.2 的写越界验证），代码硬取第一条是实现债不是设计；缺省第一条保证零配置行为不变 |
| 5 | **类型集单一来源**：`GET /api/config` 下发代码内建的可澄清与可审计类型集，前端据此判定入口呈现，不再自持副本 | 第十轮留下的双副本（`auditRules.ts` 与 `Toolbar.tsx` 各一份）是同一事实的两个家，漂移只是时间问题；config 端点本就是「生效规则的只读镜像」 |
| 6 | 白板的 commit 带 `--no-verify` | pre-commit hook 的受众是人手提交（其自述「--no-verify 是有意保存进行中工作的通道」）；白板提交 draft 产物是 spec 裁定过的行为（FR-14/FR-17），且白板自身的工作流门（接收门、resolved 门）就是它的评审保证——同一政策不需要两个执法者互相拦截 |
| 7 | **`lastFinding` 陈旧**定性为缺陷：走 `docs/issue`（根因 + 失败测试）再修——图构建时按磁盘当前内容重验会话产物，验证通过即清除异常标记，不再等下一次推进 | 用户修好文档后白板仍标异常，违背 FR-42…44「刷新反映最新状态、不产生第二套数据」的既有承诺；这是实现与 spec 的偏差，按工作流该立 issue |
| 8 | **图缓存**：解析结果按变更失效（watcher 事件与白板自身写路径都使其失效），命中时不重读整树；作为非功能项进 spec §7，不写 GWT | 每请求全量重读随文档数线性放大是 review 实测的结构性开销；失效信号已有现成通路（FR-42 的 watcher + 写路径），缓存只是把它们接到读侧 |
| 9 | **修订纪律**（域主裁定，自下一轮起）：对 `active` 的 spec/rule/design 的实质修订必须走修订轮——板上转回 `draft`、修订、审计、再接收；不得原地改 active 文档（笔误级修正豁免，存疑即按实质算）。已写入 docs/README.md | 修订轮的全部价值在于把 AGENTS.md §6 的审计门收回板内；可选的纪律等于没有纪律——本轮与上轮「原地改 + 板外审计」正是它要终结的形态 |

## 3. 考虑过的其他选项

| 选项 | 结论与理由 |
| --- | --- |
| 对 `active` 直接开审计（advisory，发现落 Open Questions 但无门拦截） | **否决**（域主裁定）。没有牙齿的审计把把关退回自觉；还得为「未决徽标」造一套新的可视化与人肉流程 |
| 修订轮走「新文档 + supersedes」而非 active → draft | **否决**。docs/README.md 明文「一个主题一份文档、原地修订」，supersedes 专属「已发布/被外部引用」的例外；每轮修订裂殖出新 id 会摧毁关系图的稳定性 |
| 新建对全部 16 种类型开放 | **否决**（域主裁定）。绕过「下一步由流程配置持有」的设计意图，关系字段全靠手写；非入口类型经推进产生本就是产品的核心主张 |
| 新建走 agent 会话（口述意图、agent 写第一版） | **否决**（域主裁定）。空手起草比有源推进难，首版质量不稳；编辑器 + 模板预填是更诚实的起点，agent 把关留给澄清与审计 |
| 多会话并行 | **否决**（域主裁定，维持 spec §6 Out of Scope）。快照/commit 范围、刷新、UI 全受影响，改动面配得上单独一轮（本行被 decision-00009 §2 第 1 条推翻，那单独一轮即 spec-00003-whiteboard-parallel-sessions——原地追注） |
| 会话历史入 git（提交转写文件） | **否决**。转写是过程态不是文档，与澄清状态文件同一裁定（decision-00006 §2 第 5 条）；且会把每次会话变成两个 commit |
| hook 侧开豁免（识别白板提交的环境变量）而非 `--no-verify` | **否决**。hook 还承载 lang/* 分支的 frozen 校验，环境变量豁免要么全跳（等价 --no-verify）要么在 hook 里长出白板专属分支——把白板的知识泄漏进 hook；`--no-verify` 是 hook 自己声明的通道，且白板只写 docs/（frozen 校验对它无实义） |
| `lastFinding` 顺手修掉不立 issue | **否决**。工作流明文：缺陷先立 issue、根因分析、失败测试复现，然后才修 |

## 4. 后果

**接受的代价**

- 修订轮期间文档在板上显示为 `draft`，读者需知「上一有效版在 git 历史里」；
  MVP 不做修订 diff 呈现（范围外）。
- `active → draft` 对全部 living doc 种类开放（状态表按 kind 而非按类型），
  record/report 等也获得此流转——无害但语义上略宽，换取状态表不碎片化。
- 会话历史无自动清理，`.whiteboard/sessions/` 随使用增长；本地单人工具可
  接受，清理策略留待需要时。
- `--no-verify` 使白板提交跳过 hook 的全部检查（含 frozen 校验）；边界是
  白板只写 `docs/**`，且其提交路径本身就是被测行为。

**放弃的收益**

- 未选 advisory 审计，意味着「不想回 draft 的轻量看一眼」不存在——修订
  把关的最小单位就是一次修订轮。
- 未做多会话并行，审计/澄清/推进仍互斥排队。（decision-00009 后不再
  成立——原地追注。）

## 5. 这个决定约束什么

- `rule-00001-docs-workflow`：BR-3 修订（living `active` 的目标状态增
  `draft`）、新增 BR-26（流程入口类型与新建语义）与 BR-27（非入口类型
  不得新建）及各自 AC。
- `spec-00001-docs-whiteboard`：新增 FR-53（新建）、FR-54（会话历史）、
  FR-55（agent 可选）、FR-56（类型集下发）及其 AC；FR-15 启动校验扩展
  （`entry` 列表）；§7 非功能新增图缓存项；§6 Out of Scope 调整。
- `design-00001-docs-whiteboard`：§2（图缓存、产物重验）、§5（转写落盘）、
  §7（`POST /api/docs`、`GET /api/docs/new`、`GET /api/sessions/history*`、
  会话 POST 的 `agent` 参数、`GET /api/config` 的类型集、commit
  `--no-verify`、`wb(create)`）。
- `design-00002-whiteboard-ui`：§3（顶栏新建按钮与对话框、会话历史入口、
  agent 选择控件）。
- `whiteboard.config.yaml`：新增 `entry: [idea, prd]`。（第十四轮注记：
  入口集经 `rule-00001-BR-26` 修订扩为 idea/prd/design/analysis——本行的
  取值是当时的落地记录，不是对集合的裁定；§4 否决的是「对全部 16 种类型
  开放」，本次只增两个无上游类型，不触其否决理由。）
- `CONTEXT.md`：新增修订轮、流程入口类型、新建、会话历史四条术语。
- 实现期：`issue-00014`（lastFinding 陈旧）先立后修。
