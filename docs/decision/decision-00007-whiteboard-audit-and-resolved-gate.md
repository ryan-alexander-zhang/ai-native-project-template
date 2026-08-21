---
id: decision-00007-whiteboard-audit-and-resolved-gate
type: decision
status: active
motivated_by: [spec-00001-docs-whiteboard]
constrains: [spec-00001-docs-whiteboard, rule-00001-docs-workflow, design-00001-docs-whiteboard, design-00002-whiteboard-ui]
---

# Decision: 审计成为第三种评审动作，plan 的 resolved 由验收覆盖守门

> 第十轮的三项互相咬合的裁定：**审计**成为与接收、澄清并列的评审动作（agent
> 对照文件夹 README 与内容审 `draft` 的 spec/rule/design，发现落进被审计文档
> 自己的 Open Questions，由既有的接收门拦截）；**plan 以 `implements` 的粒度
> 声明交付范围**（细粒度 FR/BR id 按条目交付，整文档 id 交付该文档全部条目）；
> **`plan` 的 `open → resolved` 据覆盖缺口拒绝**——推翻 decision-00004 §3
> 「白板只呈现缺口、不执法」的裁定。流程随之延伸过实现：`plan` 的下一步候选
> 增加 `issue` 与 `record`。

## 1. 需要做这个决定的原因

工作流文档（AGENTS.md §6、§8）写了两道硬门，但白板一道都没有承载：

- 「spec/rule/design 草稿写完必须由未撰写它的 subagent 审计」——白板上没有
  对应动作，评审只有接收与澄清两个入口，审计全靠人在板外记得做。
- 「feature 级 plan 转 `resolved` 前必须核验每条 GWT 有通过的测试并落
  record 验收清单」——白板的状态切换对 `open → resolved` 无条件放行
  （statusRules 只查词汇表），纸面的门在工具里不存在。

同时流程配置的 `flow` 止于 `plan → task`：验收 record 与缺陷 issue 这两类
实现期文档没有任何推进入边，只能板外手写——流程知识又回到了靠记忆。

守门先要有可判定的范围：现状每个 plan 的 `implements` 指向整份 spec
（49 个 FR），而单个 plan 只交付一个切片——按整文档算覆盖，没有一个历史
plan 能转 `resolved`。范围粒度是本轮一切裁定的前提。

## 2. 决定

| # | 做法 | 理由 |
| --- | --- | --- |
| 1 | plan 的 `implements` 声明交付范围：列出的 `spec-<n>-FR-<i>` / `rule-<n>-BR-<i>` 按条目交付；列出的整份 spec/rule 文档 id 交付该文档**全部**条目；design/report 等无条目目标不参与范围 | 范围必须由 plan 自己声明才可判定；FR-2（经 decision-00004 §5 修订）已让条目 id 作关系目标合法落边，spec 的 Stories 表本就按这个粒度切片 |
| 2 | `plan` 的 `open → resolved` 守门：仅当 `parent` 指向该 plan 的 record 的验收行使交付范围内每个条目按 FR-32 的同一推导判为「已验证」时放行；存在「未通过」「未覆盖」、或范围 id 无法解析时拒绝并逐条点名。范围为空（`implements` 无 spec/rule 目标）时不守门、照常放行 | 推翻 decision-00004 §3 的否决行——当时缺的正是范围声明，前提已变；复用同一推导（以 record 集为入参）保证面板与门口径同源；证据限 `parent` 指向该 plan，避免别的 plan 的 record 替它过门 |
| 3 | 审计成为第三种评审动作：对 `draft` 的 `spec`、`rule`、`design` 发起 agent 会话，先对照该文件夹 README 审结构与文法，再审内容本身；缺失的规则/用例/GWT、未经确认的读数、无法确认的值——未决的记入被审计文档的 Open Questions，已有既定结论的直接修订正文；文档保持 `draft` | 发现落在 Open Questions 就被既有的接收门（BR-12）自然拦住，无需第二道门；会话形态与澄清同构，复用整条会话通道 |
| 4 | 可审计类型集恰为 `spec`、`rule`、`design`，由代码内建（同 BR-20 的可澄清集） | AGENTS.md §6 点名的正是这三类——承载可验收结构（FR/BR/GWT、模块边界）的文档才有「对照 README 的结构审计」可做；idea/prd 的把关走澄清（问业务），不走结构审计 |
| 5 | 流程延伸：`plan` 的下一步候选增加 `issue`（以 `blocks` 回指来源 plan）与 `record`（以 `parent` 指向来源 plan），`task` 不变 | 实现期恰好产生这两类文档；携带关系沿用 docs/README.md 已有的语义（issue blocks plan、record parent plan），不造新边 |

## 3. 考虑过的其他选项

| 选项 | 结论与理由 |
| --- | --- |
| 守门按 plan 所链整份 spec 的全部条目算（不引入范围声明） | **否决**。多 plan 分片交付同一 spec 是既成事实，整文档口径下任何单个 plan 永远差着别人的条目，门形同虚设或永远关死 |
| 范围声明放独立字段（如 `delivers`）而非 `implements` | **否决**。`implements` 本义就是「这个 plan 使什么成真」，细化粒度即范围；新字段是同一语义的第二个家，还得改关系字段集 |
| 证据取全仓所有 record（不限 `parent` 指向该 plan） | **否决**。别的 plan 的验收会替这个 plan 过门，「这个 plan 做完了」退化成「这些条目曾经被谁验过」；record 的 `parent` 本就指向它验收的 plan |
| 范围为空时拒绝 resolved（强制所有 plan 声明范围） | **否决**。修复类小 plan（如纯 issue 修复）可以没有条目级范围，其验收由 issue 的复现测试承载；强制声明是为门而门 |
| 审计发现落独立报告文档（record/report） | **否决**。发现的归宿就是被审计文档自己——未决点进 Open Questions 恰好被接收门拦住；另立文档则门与发现脱钩，还得人肉搬运 |
| 审计对任意类型、任意状态开放 | **否决**。审计的准绳是文件夹 README 的约定，只有 spec/rule/design 有可对照的结构或取舍；`active` 后的修订审计属于下一轮修订的 draft 语义，MVP 不展开 |
| 把 open→resolved 门做成警告（可强制通过） | **否决**。可绕过的门等于没有门——AGENTS.md 的原话是「任何缺口阻塞 resolved」；真要绕过可以在板外改文件，留痕会暴露 |
| 门只认 `active` 的 record 为证据 | **否决**（域主裁定）。沿用 decision-00004 §5 裁定二的「不区分 record status」，门与面板同一口径；draft record 由 pre-commit hook 拦在仓库之外，分叉口径的收益撑不起两套判定 |
| 对 spec/rule 禁止整文档粒度的 `implements` | **否决**（域主裁定）。小 spec 或真的整份交付时它是诚实写法；README 与模板已引导优先条目 id，写错粒度的后果由门直接暴露 |
| CI 承载 resolved 门（而非白板） | **否决**。本仓定位是语言无关的 docs 工作流模板，刻意不预设 CI；白板是唯一保证存在的执法点 |

## 4. 后果

**接受的代价**

- 历史 plan 中 plan-00001…00003、plan-00005…00009 已 `resolved`，其
  `implements` 仍是整文档粒度。门只在 `open → resolved` 流转时执行，不回溯
  已 resolved 的文档，这八份不受影响；但它们若被回退重走流转，将按整文档
  口径守门。仍为 `open` 的 plan-00004 已随本轮回填为条目粒度
  （FR-29、FR-30——FR-28 的 AC-28.5 由 plan-00005 验收，不入其范围），
  避免门一上线就制造死锁工作项。
- 范围声明依赖 plan 作者自觉列全——漏列的条目不进门检。缓解：推进生成 plan
  的任务指令要求按来源 spec 的 Stories 切片声明范围；审计动作也把「范围是否
  列全」纳入 plan 上游 spec 的审计着眼点。
- 审计会话与澄清、答疑、推进共用单会话约束（FR-18），审计进行中其余入口
  互斥——与既有取舍一致（decision-00006 §2 第 7 条）。
- decision-00004 §3 的「覆盖执法」否决行被本决定 §2 第 2 条部分推翻（已在该
  行原地追注）；decision-00004 其余各节不受影响，该文档保持 `active`，本决定
  不 supersede 整份文档。

**放弃的收益**

- 未选 CI 守门，意味着板外直接改 status 仍可绕过门（文件是唯一事实来源的
  既有边界）；留痕（git 历史）是最后的对账手段。

## 5. 这个决定约束什么

- `spec-00001-docs-whiteboard`：FR-3（浮窗评审入口含审计）、FR-14/FR-18/
  FR-49（第四种会话入列）、新增 FR-50…FR-52 及其 AC；§6 Out of Scope 的
  「覆盖执法」删除线。
- `rule-00001-docs-workflow`：BR-16 行扩展（`plan → task/issue/record`）、
  新增 BR-22…BR-25 及其 AC。
- `design-00001-docs-whiteboard`：§2 Workflow Engine（审计裁决与 resolved
  门）、§7 API 契约（`POST /api/sessions/audit`、status 422 的 `gaps`、
  commit action `audit`）。
- `design-00002-whiteboard-ui`：§3 控件映射（审计按钮、发起入口禁用说明、
  resolved 门拒绝的呈现）。
- `whiteboard.config.yaml`：`flow.plan` 增 `issue`（carry `blocks`）与
  `record`（carry `parent`）。
- `docs/README.md`、`docs/plan/README.md`、`docs/plan/TEMPLATE.md`、
  `docs/record/README.md`：`implements` 的条目粒度写法与交付范围语义、
  record `parent` 对门的必要性。
- `CONTEXT.md`：评审动作扩为三种；新增审计、可审计类型、交付范围、
  resolved 门四条术语。
