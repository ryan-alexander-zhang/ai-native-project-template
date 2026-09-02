---
id: spec-00009-whiteboard-agent-settings
type: spec
status: active
parent: prd-00001-docs-whiteboard
---

# Spec: agent 设置——模型进条目、本地层随机器、设置面板可改

> agent 条目可声明模型与环境变量；`whiteboard.config.yaml` 的 `agents`（项目层）
> 之上叠一份不入 git 的本地 agent 设置（本地层），两层合并为有效 agent 列表；
> 白板顶栏的设置面板读写本地层，保存即对其后发起的会话生效。
> 取舍全部在案于 decision-00017。

## 1. Context

- canonical terms 见 `CONTEXT.md`：白板、流程配置、Agent 会话、headless 调用、
  答疑线程、动作被拒、刷新。
- 输入：`parent` 为 [prd-00001-docs-whiteboard](../prd/prd-00001-docs-whiteboard.md)
  （功能需求 8「agent 会话」、17「agent 选择」的配置侧；随接收 prd-00001 在其
  修订轮增一行功能需求「agent 设置」，与共写、标注两轮同例）；取舍在案于
  [decision-00017-whiteboard-agent-settings](../decision/decision-00017-whiteboard-agent-settings.md)。
- 本 spec 是 `spec-00001` … `spec-00008` 的**并列新 spec**（Sizing and
  Splitting 第 1 条），同 `parent`，不 supersede 任何一份。
- **对既有 active spec 的影响**：`spec-00001-FR-55` 与 `spec-00001-AC-55.4`
  今天读「流程配置 `agents`」，本 spec 落地后它们读的对象是有效 agent 列表
  ——这是名词替换、口径（可指定其一、未指定取第一条、未知名拒绝、恰一条时
  不呈现选择）不变，但 `AC-55.4` 的 Given「流程配置只声明一条」在本地层追加
  一条时不再蕴含其 Then，故 `spec-00001` 须进一轮修订轮把这两处的名词换为
  「有效 agent 列表」（`rule-00001-BR-3`）。`spec-00005-FR-8` 同样须换名词：
  它写的是「**流程配置的** agent 条目应可携带 headless 声明」且「不合式时
  拒绝启动」，而本地层条目也可声明 headless、其不合式按本 spec FR-4 不拦
  启动——修订为「agent 条目（项目层的按 `spec-00001-FR-15` 拒绝启动，本地层
  的按 `spec-00009-FR-4` 处置）」。`spec-00001-FR-13` / `FR-15`、
  `spec-00005-FR-2`、`spec-00007-FR-5` 的文字不需改：它们经 `spec-00001-FR-55`
  或「配置下发」间接读列表。
- 本 spec 新增术语（随接收进 `CONTEXT.md`）：
  - **Agent 设置（Agent Settings）**：agent 从哪来、怎么启动这件事的两层配置
    （项目层 + 本地层）与其设置面板的统称。
    _Avoid_：agent 配置、agent 管理、模型设置（模型只是条目的一个键）。
  - **Agent 条目（Agent Entry）**：以名字为键的一条 agent 声明：`command`、
    `args`、`cwd`，可选的 `model`、`env`、`headless`。项目层的条目在流程配置
    `agents` 下；本地层的条目是对项目条目的**覆盖**或本机独有的**追加**。
    _Avoid_：profile、预设。
  - **本地 agent 设置（Local Agent Settings）**，简称**本地层**：`.whiteboard/`
    下不入 git 的一份文件，按名字对项目层条目做键级覆盖、追加本机独有条目、
    标记禁用与缺省；由设置面板写，手改亦可。对应地，流程配置的 `agents` 称
    **项目层**。
    _Avoid_：用户配置、本地配置（泛指）、override 文件。
  - **有效 agent 列表（Effective Agent List）**，简称**有效列表**：项目层与
    本地层合并后的有序列表——一切「可选 agent」「缺省 agent」的唯一来源。
    _Avoid_：合并配置、最终配置、注册表。
  - **设置面板（Settings Panel）**：顶栏入口打开的面板，呈现有效 agent 列表并
    编辑本地 agent 设置。
    _Avoid_：偏好、选项、配置页。
- 随接收需修订 `CONTEXT.md`：「流程配置」词条的 `agents` 一句补「agent 条目
  可带 `model` 与 `env`，是 agent 设置的项目层」；「Agent 会话」词条补「agent
  取自有效 agent 列表」；「呈现状态」词条的枚举增「设置面板的开合」。

## 2. Stories

| Story | Value | Delivers |
| --- | --- | --- |
| S1 | 作为文档负责人，我要给一个 agent 指定模型，写一处、交互与答疑两种形态都用上，填错了启动时就告诉我 | spec-00009-FR-1, spec-00009-FR-2 |
| S2 | 作为文档负责人，我要在自己机器上换模型、加环境变量、试一个新 CLI，不动团队共享的配置文件、不弄丢里面的验证记录 | spec-00009-FR-3, spec-00009-FR-4 |
| S3 | 作为文档负责人，我要在白板界面里做这些改动，保存后下一次发起会话就用新的，正在跑的会话别受影响 | spec-00009-FR-5, spec-00009-FR-6, spec-00009-FR-7, spec-00009-FR-8 |
| S4 | 作为文档负责人，我禁用或删掉了一个 agent 之后，用它开的答疑线程要能告诉我为什么追问不了，而不是悄悄换一个 | spec-00009-FR-9 |

## 3. Business Rules

| Rule set | Doc | Covers |
| --- | --- | --- |
| Docs 工作流 | [rule-00001-docs-workflow](../rule/rule-00001-docs-workflow.md) | 不因 agent 设置而变；本 spec 不新增业务规则 |

## 4. System Requirements

- **spec-00009-FR-1** (Ubiquitous) agent 条目应可携带可选的 `model`（非空
  字符串）与 `env`（字符串到字符串的映射，空映射合法、等于不设）；`{model}`
  占位符可出现在 `args`、`headless.first`、`headless.resume` 的任一元素中
  （元素内子串亦可），会话启动或 headless 调用构造命令时每处出现都替换为该
  条目的 `model`——这把占位替换**新扩展到终端形态的 `args`**（今天只有
  headless 声明做替换，`args` 原样传入）；`env` 中每一项在启动子进程时叠加
  到白板服务自身的进程环境之上（同名覆盖、其余保留），终端形态与 headless
  形态皆然。两键都缺失、且数组中无 `{model}` 的条目：命令、参数、环境与
  今天逐项相同。
- **spec-00009-FR-2** (Unwanted) 若**项目层**的一条 agent 条目出现下列任一
  情形，系统应按 `spec-00001-FR-15` 的启动校验拒绝启动，错误信息点名该条目
  与该键：`model` 不是非空字符串；`env` 不是字符串到字符串的映射；某数组中
  出现 `{model}` 而该条目无 `model`；该条目有 `model` 而 `args` 中没有
  `{model}`；该条目有 `model` 且声明了 `headless`，而 `headless.first` 或
  `headless.resume` 中没有 `{model}`（答疑形态会静默用不上模型）。「有」指
  至少出现一次。本地层条目违反同一规则的处置由 FR-4 持有。
- **spec-00009-FR-3** (Ubiquitous) 系统应维护有效 agent 列表：以项目层为底，
  叠加本地层——本地对同名项目条目的覆盖按键生效（**`cwd` 除外**，本地层
  不得改它）；本地追加的条目（无同名项目条目，须自带 `command`，`cwd`
  **不可声明、恒为 `docs`**——写域第一屏障对追加条目同样成立）排在项目
  条目之后；本地标记禁用的条目（任意条）不进列表；本地指定为缺省的条目
  （至多一条）排在列表首位——缺省只有这一个，答疑不另设缺省、取列表中
  第一条声明 headless 的（域主 2026-09-02 裁定）。凡 `spec-00001-FR-55`（会话发起的选择与缺省）、
  `spec-00005-FR-2`（答疑可选集）、`spec-00007-FR-5`（统一提交的两个可选集）
  与配置下发读「agent 列表」处，一律读有效 agent 列表，被禁用的名字在发起
  时按 `spec-00001-FR-55` 的「未知名」拒绝、在配置下发中不出现。有效列表在**每次会话发起受理时与每次配置
  下发时**按两层当时的内容重新计算，不在启动时定格；合并后的每条仍受
  design-00001 §3 与 FR-2 的同一套条目校验。本地层文件不存在时有效列表即
  项目层。
- **spec-00009-FR-4** (Unwanted) 若本地层不合式——文件不可解析、某条违反
  FR-2 列出的条目规则或 design-00001 §3 的条目校验、覆盖了 `cwd`、追加条目
  无 `command` 或声明了 `cwd`、缺省多于一条或指向被禁用的条目、合并后有效
  列表为空——系统不应因此拒绝启动，也不应只丢弃违规的那一
  键：本地层**整体**忽略，有效列表退为项目层，设置面板呈现该错误并点明
  条目与键，服务端记录告警。启动后本地文件被手改成不合式时同样处置，以
  FR-3 的下一次重新计算为界。本地覆盖、禁用或缺省所指的项目条目已不存在
  （项目层改名或删除）时**不算不合式**：该条单独忽略，设置面板点明它无所指
  （`git pull` 后的项目层改名不应打坏本地层）。
- **spec-00009-FR-5** (Event) 当用户在设置面板保存时，系统应把面板内容作为
  新的本地层与项目层合并并校验（FR-2、design-00001 §3、FR-4 列出的各项）；
  `headless` 作为结构原样编辑，受 design-00001 §10.1 的同一套声明校验；
  合格则写入本地层文件——`.whiteboard/` 不存在时先建目录，原文件不合式时
  照常覆盖（面板已呈现其错误）——保存请求成功返回时新的有效列表已生效：
  其后受理的每次会话发起与 headless 调用（统一提交按整批受理一次）按新列表
  解析 agent，无需重启服务；受理即把该条目解析为命令、参数、环境与工作目录
  的快照，受理在先的会话与调用——正在运行的、以及已受理尚未起进程的——按
  快照进行，不受其后的保存影响。
- **spec-00009-FR-6** (Unwanted) 若保存的内容不合式（FR-4 列出的任一情形），
  系统应拒绝保存、不写盘、点名条目与键；若内容合式而写盘失败（目录不可写、
  磁盘错误），系统应报告写入失败且不留下半写的文件；两种情形下本地层文件与
  有效列表都保持保存前的样子，再次提交同样内容得到同样的结果。
- **spec-00009-FR-7** (Ubiquitous) 系统应在顶栏提供设置面板入口；面板列出
  有效 agent 列表的每一条：名字、来源（项目 / 本地 / 项目+本地覆盖）、
  `command`、`model`、`args`、`env`、是否声明 headless、是否缺省、是否禁用
  （被禁用的项目条目仍列出，标禁用）；并可编辑本地层：对项目条目覆盖除
  `cwd` 外的任一键、新增本地条目（`command` 必填，`cwd` 呈现为 `docs` 且不可
  编辑）、禁用与启用、设为缺省、删除本地条目、撤销对项目条目的本地覆盖（该键
  回到项目值）。`model` 为自由文本；`headless` 按其结构（`first`、`resume`、
  `capture`）原样编辑；`env` 的值**缺省遮罩呈现，逐项点击后显示明文**，
  编辑态不遮罩（域主 2026-09-02 裁定）。本地新增的条目旁呈现一条说明：该 CLI 未经写域校验（design-00001
  §11.5 的接入验证纪律，`spec-00001-AC-13.2` 为其判据），后果由用户自负。
- **spec-00009-FR-8** (Event) 当有效 agent 列表因保存而变化时，系统应使
  **发起保存的那个页面**的 agent 选择（`spec-00001-FR-55` 的选择器、答疑与
  统一提交的可选集）呈现新列表，无需重新加载页面（其他已打开的页面在其
  下一次重新加载时见新列表——变更推送不为设置增触发源）；列表恰一条时选择器不呈现，多于一条时呈现；
  列表中不再有声明 headless 的条目时，答疑的两处入口不再呈现、经接口请求
  被拒绝（`spec-00005-FR-7` 的口径随列表变化即时生效）。
- **spec-00009-FR-9** (Unwanted) 若追问时该答疑线程记录的 agent 不在有效
  agent 列表中（已被禁用、其本地条目已删除），或仍在列表中但已不再声明
  headless 形态（本地层撤掉了它的 `headless`），系统应拒绝该追问且不发起
  调用，拒绝原因点名该 agent 不可用于答疑；线程既有问答不受影响；该 agent
  以 headless 形态重新进入有效列表后追问照常。

**Acceptance (GWT)**

- **spec-00009-AC-1.1** (spec-00009-FR-1)
  Given 一条 agent 条目声明 `model: m1`，`args` 含元素 `--model={model}`，
  `headless.first` 与 `headless.resume` 各含元素 `{model}`
  When 以该条目发起一次推进会话与一次答疑首调
  Then 推进会话的子进程参数含 `--model=m1`，答疑调用的子进程参数中原
  `{model}` 位置是 `m1`
- **spec-00009-AC-1.2** (spec-00009-FR-1)
  Given 一条 agent 条目声明 `env: { FOO: bar }`，白板服务的环境中 `HOME` 有值
  When 以该条目发起一次终端形态会话与一次 headless 调用
  Then 两个子进程的环境中 `FOO` 为 `bar`，且 `HOME` 与白板服务的相同
- **spec-00009-AC-1.3** (spec-00009-FR-1)
  Given 一条 agent 条目声明 `command: c`、`args: [a, b]`、`cwd: docs`，无
  `model`、无 `env`，`headless` 中无 `{model}`
  When 以该条目发起终端形态会话
  Then 子进程以命令 `c`、参数恰为 `[a, b]`、工作目录 `docs`、环境与白板
  服务相同启动
- **spec-00009-AC-1.4** (spec-00009-FR-1)
  Given 一条 agent 条目声明 `env: {}` 与 `model: m1`，`args` 含 `{model}`
  When 服务启动并以该条目发起会话
  Then 服务照常启动，子进程环境与白板服务相同
- **spec-00009-AC-2.1** (spec-00009-FR-2)
  Given 项目层一条 agent 条目的 `args` 含 `{model}` 而该条目无 `model`
  When 服务启动
  Then 启动被拒绝，错误信息含该条目名与 `model`
- **spec-00009-AC-2.2** (spec-00009-FR-2)
  Given 项目层一条 agent 条目声明 `model: m1`，`args` 中没有 `{model}`
  When 服务启动
  Then 启动被拒绝，错误信息含该条目名与 `model`
- **spec-00009-AC-2.3** (spec-00009-FR-2)
  Given 项目层一条 agent 条目声明 `model: m1`，`args` 含 `{model}`，声明了
  `headless` 而 `headless.resume` 中没有 `{model}`
  When 服务启动
  Then 启动被拒绝，错误信息含该条目名与 `headless.resume`
- **spec-00009-AC-2.4** (spec-00009-FR-2)
  Given 项目层一条 agent 条目的 `env` 是字符串列表而非映射
  When 服务启动
  Then 启动被拒绝，错误信息含该条目名与 `env`
- **spec-00009-AC-2.5** (spec-00009-FR-2)
  Given 项目层一条 agent 条目声明 `model: ""`
  When 服务启动
  Then 启动被拒绝，错误信息含该条目名与 `model`
- **spec-00009-AC-2.6** (spec-00009-FR-2)
  Given 项目层一条 agent 条目声明 `model: m1`，`args` 含 `{model}`，无 `headless`
  When 服务启动
  Then 服务照常启动
- **spec-00009-AC-3.1** (spec-00009-FR-3)
  Given 项目层声明 `claude`（`model: m1`，`args` 含 `{model}`），本地层对
  `claude` 覆盖 `model: m2`
  When 未指定 agent 发起会话
  Then 子进程参数中原 `{model}` 位置是 `m2`
- **spec-00009-AC-3.2** (spec-00009-FR-3)
  Given 项目层只声明 `claude`，本地层追加 `codex-local`
  When 读取配置下发
  Then agent 列表依次为 `claude`、`codex-local`
- **spec-00009-AC-3.3** (spec-00009-FR-3)
  Given 项目层声明 `claude`、`other`，本地层将 `other` 标为缺省
  When 未指定 agent 发起会话
  Then 会话以 `other` 启动
- **spec-00009-AC-3.4** (spec-00009-FR-3)
  Given 项目层声明 `claude`、`other`，两者都声明 headless，本地层禁用 `claude`
  When 未指定 agent 发起答疑
  Then 答疑以 `other` 发起
- **spec-00009-AC-3.5** (spec-00009-FR-3)
  Given 本地层文件不存在
  When 读取配置下发
  Then agent 列表与项目层 `agents` 逐条相同
- **spec-00009-AC-3.6** (spec-00009-FR-3)
  Given 项目层声明 `claude`、`other`，本地层禁用 `other`
  When 显式指定 `other` 发起会话
  Then 请求被拒绝且不启动会话
- **spec-00009-AC-3.7** (spec-00009-FR-3)
  Given 服务运行中，本地层文件不存在
  When 用户手写一份合法的本地层文件（对 `claude` 覆盖 `model: m2`）后未指定
  agent 发起会话
  Then 子进程参数中的模型是 `m2`，服务未重启
- **spec-00009-AC-3.8** (spec-00009-FR-3)
  Given 项目层声明 `claude`、`other`，本地层禁用 `other`
  When 读取配置下发
  Then agent 列表只有 `claude`
- **spec-00009-AC-4.1** (spec-00009-FR-4)
  Given 本地层文件不可解析
  When 服务启动
  Then 服务照常启动，有效列表与项目层相同，设置面板呈现「本地设置不合式」及
  原因
- **spec-00009-AC-4.2** (spec-00009-FR-4)
  Given 本地层对项目条目 `claude` 覆盖了 `cwd`，同一文件还对 `claude` 覆盖了
  `model: m2`
  When 服务启动并未指定 agent 发起会话
  Then 服务照常启动，会话的 `cwd` 与模型都是项目层的值，设置面板点名
  `claude` 的 `cwd` 不可覆盖
- **spec-00009-AC-4.3** (spec-00009-FR-4)
  Given 本地层禁用了项目层的每一条 agent
  When 服务启动
  Then 服务照常启动，有效列表与项目层相同，设置面板呈现「有效列表为空」
- **spec-00009-AC-4.4** (spec-00009-FR-4)
  Given 服务运行中、本地层合法，用户随后手改本地文件使其不可解析
  When 下一次未指定 agent 发起会话
  Then 会话按项目层的第一条启动，设置面板呈现该错误
- **spec-00009-AC-4.5** (spec-00009-FR-4)
  Given 本地层对名为 `old` 的项目条目有一条 `model` 覆盖，而项目层已没有
  `old`；同一文件对 `claude` 覆盖 `model: m2`
  When 服务启动并未指定 agent 发起会话
  Then 服务照常启动，会话的模型是 `m2`，设置面板点明 `old` 的覆盖无所指
- **spec-00009-AC-4.6** (spec-00009-FR-4)
  Given 本地层把 `claude` 同时标为缺省与禁用
  When 服务启动
  Then 服务照常启动，有效列表与项目层相同，设置面板点名 `claude` 的缺省
  指向被禁用的条目
- **spec-00009-AC-4.7** (spec-00009-FR-4)
  Given 本地层追加条目 `codex-local` 并为它声明了 `cwd: .`
  When 服务启动
  Then 服务照常启动，有效列表与项目层相同，设置面板点名 `codex-local` 的
  `cwd` 不可声明
- **spec-00009-AC-4.8** (spec-00009-FR-4)
  Given 本地层禁用名为 `old` 的条目而项目层已没有 `old`；同一文件对 `claude`
  覆盖 `model: m2`
  When 服务启动并未指定 agent 发起会话
  Then 会话的模型是 `m2`，设置面板点明 `old` 的禁用无所指
- **spec-00009-AC-5.1** (spec-00009-FR-5)
  Given 设置面板打开，用户把项目条目 `claude` 的 `model` 改为 `m2`
  When 用户保存
  Then 本地层文件含 `claude` 的 `model: m2`
- **spec-00009-AC-5.2** (spec-00009-FR-5)
  Given 同 AC-5.1 且保存已成功返回，服务未重启
  When 未指定 agent 发起会话
  Then 子进程参数中的模型是 `m2`
- **spec-00009-AC-5.3** (spec-00009-FR-5)
  Given 一次以 `claude`（`model: m1`）启动的会话正在运行
  When 用户在设置面板把 `claude` 的 `model` 改为 `m2` 并保存
  Then 运行中的会话不中断、其进程参数不变
- **spec-00009-AC-5.4** (spec-00009-FR-5)
  Given 一次以 `claude` 发起的答疑首调正在进行
  When 用户保存一份禁用 `claude` 的设置
  Then 该次调用照常完成并回填回答
- **spec-00009-AC-5.5** (spec-00009-FR-5)
  Given 一次统一提交含两条 question，已受理、第一条调用进行中
  When 用户在第一条完成前保存一份禁用该批所用 agent 的设置
  Then 第二条 question 仍以该批受理时的 agent 发起
- **spec-00009-AC-5.6** (spec-00009-FR-5)
  Given 本地层文件不可解析，设置面板呈现着该错误
  When 用户在面板填好合式内容并保存
  Then 保存成功，本地层文件被新内容覆盖
- **spec-00009-AC-6.1** (spec-00009-FR-6)
  Given 设置面板中用户为项目条目 `claude` 填了 `model: m2`，而 `claude` 的
  `args` 没有 `{model}`
  When 用户保存
  Then 保存被拒绝并点名 `claude` 的 `model`，本地层文件未改变
- **spec-00009-AC-6.2** (spec-00009-FR-6)
  Given 同 AC-6.1，且用户已被拒绝一次
  When 用户不改内容再次保存
  Then 再次得到同样的拒绝，本地层文件仍未改变
- **spec-00009-AC-6.3** (spec-00009-FR-6)
  Given 设置面板中用户新增本地条目而未填 `command`
  When 用户保存
  Then 保存被拒绝并点名该条目的 `command`
- **spec-00009-AC-6.4** (spec-00009-FR-6)
  Given 面板内容合式，而 `.whiteboard/` 目录不可写或不可创建
  When 用户保存
  Then 面板报告写入失败，磁盘上没有半写的本地层文件，配置下发的列表与保存前
  相同
- **spec-00009-AC-6.5** (spec-00009-FR-6)
  Given 同 AC-6.4，且用户已得到一次写入失败
  When 用户再次保存
  Then 再次报告写入失败，配置下发的列表仍与保存前相同
- **spec-00009-AC-6.6** (spec-00009-FR-6)
  Given 设置面板中用户为本地条目填了 `headless`，其 `first` 中没有 `{question}`
  When 用户保存
  Then 保存被拒绝并点名该条目的 `headless.first`，本地层文件未改变
- **spec-00009-AC-7.1** (spec-00009-FR-7)
  Given 项目层声明 `claude`，本地层对它覆盖 `model`、并追加 `codex-local`
  When 用户打开设置面板
  Then 面板列出两条：`claude` 来源为「项目+本地覆盖」并呈现覆盖后的 `model`，
  `codex-local` 来源为「本地」并呈现写域校验的说明
- **spec-00009-AC-7.2** (spec-00009-FR-7)
  Given `claude` 的 `model` 在本地层被覆盖为 `m2`，项目层为 `m1`
  When 用户在面板撤销该覆盖并保存
  Then 面板呈现 `claude` 的 `model` 为 `m1`、来源为「项目」
- **spec-00009-AC-7.3** (spec-00009-FR-7)
  Given 项目层声明 `claude`
  When 用户打开设置面板查看 `claude`
  Then `cwd` 呈现为项目值且不可编辑，`command`、`model`、`args`、`env`、
  `headless` 可编辑
- **spec-00009-AC-7.4** (spec-00009-FR-7)
  Given 本地层禁用了项目条目 `other`
  When 用户打开设置面板
  Then `other` 仍列出并标禁用，可在面板启用
- **spec-00009-AC-7.5** (spec-00009-FR-7)
  Given 有效列表只有项目层的一条 `claude`，无本地层
  When 用户打开设置面板
  Then 面板列出 `claude` 一条、来源为「项目」，且提供新增本地条目的入口
- **spec-00009-AC-7.6** (spec-00009-FR-7)
  Given 本地层追加了 `codex-local`
  When 用户在设置面板查看 `codex-local`
  Then `cwd` 呈现为 `docs` 且不可编辑
- **spec-00009-AC-7.7** (spec-00009-FR-7)
  Given `claude` 的 `env` 含 `FOO: bar`
  When 用户打开设置面板
  Then `FOO` 的值以遮罩呈现，明文 `bar` 不可见
- **spec-00009-AC-7.8** (spec-00009-FR-7)
  Given 同 AC-7.7，面板已打开
  When 用户点击 `FOO` 的遮罩值
  Then 该值显示为 `bar`，其余遮罩值不变
- **spec-00009-AC-8.1** (spec-00009-FR-8)
  Given 有效列表只有 `claude` 一条，页面已打开、agent 选择器不呈现
  When 用户在设置面板追加本地条目 `codex-local` 并保存
  Then 不重新加载页面，发起会话的入口出现 agent 选择器且含两条
- **spec-00009-AC-8.2** (spec-00009-FR-8)
  Given 有效列表有 `claude`、`codex-local` 两条，页面已呈现选择器
  When 用户在设置面板删除本地条目 `codex-local` 并保存
  Then 不重新加载页面，选择器不再呈现
- **spec-00009-AC-8.3** (spec-00009-FR-8)
  Given 有效列表有两条，仅 `claude` 声明 headless，页面已打开
  When 用户在设置面板为 `codex-local` 补上 headless 声明并保存
  Then 不重新加载页面，答疑入口的可选集含两条
- **spec-00009-AC-8.4** (spec-00009-FR-8)
  Given 有效列表中仅 `claude` 声明 headless，页面已呈现浮窗与编辑器两处答疑
  入口
  When 用户在设置面板禁用 `claude` 并保存
  Then 不重新加载页面，两处答疑入口不再呈现，经接口发起答疑被拒绝
- **spec-00009-AC-9.1** (spec-00009-FR-9)
  Given 一条答疑线程以 `codex-local` 开出并已有一问一答，用户随后在设置面板
  删除了本地条目 `codex-local` 并保存
  When 用户对该线程追问
  Then 追问被拒绝且不发起调用，拒绝原因含 `codex-local`；线程的既有问答不变
- **spec-00009-AC-9.2** (spec-00009-FR-9)
  Given 同 AC-9.1，用户随后重新追加同名本地条目 `codex-local`（含 headless）
  并保存
  When 用户对该线程追问
  Then 追问以 `codex-local` 的接续形态发起
- **spec-00009-AC-9.3** (spec-00009-FR-9)
  Given 一条答疑线程以 `claude` 开出，用户随后在本地层撤掉了 `claude` 的
  `headless` 声明并保存，`claude` 仍在有效列表
  When 用户对该线程追问
  Then 追问被拒绝且不发起调用，拒绝原因含 `claude`
- **spec-00009-AC-9.4** (spec-00009-FR-9)
  Given 一条答疑线程以 `claude` 开出，用户随后禁用了 `claude`
  When 用户对**另一份文档**以 `other` 发起新答疑
  Then 新答疑照常发起

## 5. Technical Design

| Design | Doc | Covers |
| --- | --- | --- |
| 白板服务（修订轮） | [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md) | §3 契约增 `model` / `env` 与成对校验、追加条目的 `cwd` 恒 `docs`；§5 会话启动的 pty seam 增 `{model}` 替换与 `env` 叠加；§10.1 headless 命令构造同样增 `{model}` 与 `env`；§7 增设置端点、配置下发改为有效列表并携来源；新增一节承载本地层文件形态与命名、合并规则、不合式降级、按次重新计算；front matter `informs` 增本 spec |
| 白板 UI（修订轮） | [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) | 新增一节承载设置面板：入口、列表与来源标记、编辑表单、拒绝呈现、`env` 值的遮罩与点击显示；front matter `informs` 增本 spec |

两份 design 的修订轮在本 spec `active` 后、`plan` 开工前完成。

## 6. Out of Scope

- Codex 或任何第二个 CLI 的实际接入——需要新的 capture 内建与两种形态的
  实测门，另开 spec（`decision-00017` §2 第 9 条）
- 由设置面板写 `whiteboard.config.yaml`（`decision-00017` §3）
- 模型下拉框或由 CLI 枚举模型（`decision-00017` §3）
- 自动化的写域校验——仍是人做的实测（design-00001 §11.5）
- 密钥管理：`env` 的值原样存于本地层文件，不加密、不与系统密钥链集成
- 多用户或远程访问下的设置隔离，以及并发保存的保护——白板是单机单人工具，
  两个标签页同时保存以后写为准（`decision-00017` §4）
- 项目层的显式 `default` 键（`decision-00017` §3 否决）
- 答疑独立的第二个缺省 agent（`decision-00017` §3 否决，域主裁定）
- 限制 `env` 可设置的键——`PATH` 等可被覆盖，改变 `command` 的解析；这是
  用户在自己机器上的选择（见 §7）

## 7. Non-Functional

- 本地层文件位于 `.whiteboard/`（已 `.gitignore`），可能含密钥；白板服务只
  服务本机，不新增任何外发通道。「不入 git」与「适合放密钥」是两件事，后者
  由本机访问边界担保，不由前者。
- `env` 不限制键名：能改动本机会启动什么程序的人，只有本机的用户自己。
- 设置面板的每个输入与按钮都是可聚焦、可激活的真控件，键盘与鼠标同权；
  拒绝原因不只靠颜色（design-00002 §6 的既有约定）。
- 保存请求成功返回时新有效列表已对其后的受理生效，无轮询或延迟窗口。

## Links

- Parent: [prd-00001-docs-whiteboard](../prd/prd-00001-docs-whiteboard.md)
- Sibling specs: [spec-00001-docs-whiteboard](spec-00001-docs-whiteboard.md)
  （写域 FR-13、启动校验 FR-15、agent 选择 FR-55——FR-55 与 AC-55.4 随本 spec
  进修订轮换名词）·
  [spec-00005-whiteboard-ask-threads](spec-00005-whiteboard-ask-threads.md)
  （答疑可选集 FR-2、拒绝面 FR-7、headless 声明 FR-8——FR-8 随本 spec 进修订轮换名词）·
  [spec-00007-doc-annotations](spec-00007-doc-annotations.md)（统一提交的两个可选集 FR-5）
- Rules: [rule-00001-docs-workflow](../rule/rule-00001-docs-workflow.md)
- Design: [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md) §3 / §7 / §10.1 / §11.5 ·
  [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md)
- Decisions: [decision-00017-whiteboard-agent-settings](../decision/decision-00017-whiteboard-agent-settings.md)（本 spec 的全部取舍）·
  [decision-00008-whiteboard-revision-create-and-session-reach](../decision/decision-00008-whiteboard-revision-create-and-session-reach.md)（agent 选择 FR-55 的来源）
