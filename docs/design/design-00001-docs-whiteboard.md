---
id: design-00001-docs-whiteboard
type: design
status: active
informs: [spec-00001-docs-whiteboard, spec-00002-whiteboard-governance, spec-00003-whiteboard-parallel-sessions, spec-00005-whiteboard-ask-threads, spec-00006-whiteboard-co-write, spec-00007-doc-annotations, spec-00009-whiteboard-agent-settings, spec-00010-whiteboard-directory-groups-and-exclude]
---

# Design: Docs 白板 MVP

> 一个本地 Node 服务 + 浏览器前端：文件是唯一事实来源，服务端持有解析、裁决、
> 会话与 git 四个能力，前端只做呈现与交互。

## 1. 形态与技术选型

单进程本地服务（Node.js + TypeScript），托管前端静态资源并暴露 HTTP/WebSocket
接口；浏览器访问 `localhost`。选型及理由：

| 关切 | 选型 | 理由 |
| --- | --- | --- |
| 服务端 | Node.js + TypeScript | PTY（node-pty）与前端同栈；单进程即可承载 MVP |
| 前端画布 | React + React Flow | 节点/边/浮窗交互开箱即用 |
| 自动布局 | 自有的类型分列布局（同步纯函数） | 列＝类型、行＝id 序，位置可预期；从关系边推导层次的算法（ELK/dagre）做不到这件事，且会把阶段流画反——取舍见 `decision-00002-whiteboard-layout` |
| 编辑器 | CodeMirror 6（markdown 模式） | 纯文本可靠；编辑的是整文件原文，front matter 可见可改（异常节点靠它修复） |
| 预览渲染 | react-markdown 10 + remark-gfm 4 | remark/rehype 生态的默认 React 渲染器；不启用 `rehype-raw` 时丢弃原始 HTML，承接 FR-24 |
| 图表 | mermaid 11 | 与 `docs/` 里既有的 mermaid 图同源（`docs/design/README.md` 的 Guideline 即 "Prefer Mermaid"），无需第二套语法 |
| 内嵌终端 | xterm.js ↔ WebSocket ↔ node-pty | 事实标准的 PTY 通道 |
| front matter 解析 | gray-matter | 容错好；仅用于读——写回见 §6 |
| git | simple-git | 只需 add/commit/log 的薄封装 |
| 文件监听 | chokidar | 外部修改推送刷新 |

## 2. 模块结构

```mermaid
flowchart LR
  subgraph Browser
    GV[Graph View<br/>React Flow + 类型分列布局]
    ED[Editor<br/>CodeMirror + Preview]
    TM[Terminal<br/>xterm.js]
  end
  subgraph Node service
    API[HTTP/WS API]
    DR[Doc Repository<br/>解析 docs/**·图模型]
    WE[Workflow Engine<br/>rule-00001 的执行者]
    SM[Session Manager<br/>node-pty 会话注册表·持有会话前快照]
    GL[Git Layer<br/>暂存目标路径·快照与差集·commit]
    CFG[Flow Config<br/>启动时加载校验]
    WA[Watcher<br/>chokidar·去抖·广播]
  end
  GV & ED --> API
  TM <--WS--> SM
  GV <--WS 事件--> WA
  WA --> FS
  API --> DR & WE & SM & GL
  WE --> CFG
  DR --> CFG
  DR --> FS[(docs/**/*.md)]
  SM --> CLI[agent CLI 子进程]
  GL --> GIT[(git repo)]
```

- **Doc Repository**：扫描 `docs/**/*.md`（排除 `README.md`、`TEMPLATE.md`；
  **第二十七轮起再减去流程配置 `exclude` 命中的文件**，§14），
  产出图模型 `{nodes, edges, issues}`；类型集与关系字段集取自流程配置，front
  matter 缺失/非法、断链（无法解析的引用——可解析的细粒度引用不算，见下）进
  `issues` 并在节点/边上打异常标记（spec FR-1/FR-2 的载体）。节点标题取正文
  首个 H1，缺失取文件名。
  **第四轮起它还解析文档正文的需求条目**：spec 的 `FR`、rule 的 `BR` 及各自的
  AC——列表项与决策表行两种声明形态都认，AC 按其标注的「(所验条目 id)」归属；
  并从 record 解析**验收行**（验收清单表格中首列为被验 id、含测试与结果列的
  行），在服务端推导覆盖三态（spec FR-31…FR-33 的载体，口径见
  decision-00004 §5）——前端不自行推导。关系目标的解析随 FR-2 的修订变为两段：
  先按文档 id，再按需求条目/AC id 落到其**所属文档**（边保留所声明的原始 id），
  二者皆不中才是异常。（BR-18 保证「类型+编号」唯一，文档 id 与条目 id 语法上
  也不同形，两段不会同时命中——次序只是防御性规定。）
  **第六轮起（decision-00005）**：正文解析由行级正则升级为 **remark AST** 遍历，
  行为契约不变（既有测试为回归护栏），AST 的位置信息供诊断定位到行；解析同时
  按各文件夹 README 的「机器可读形态」小节校验，产出**解析诊断**（疑似条目而
  不合形态、验收清单的不合式行、无法归属——spec FR-40 的载体），随图与
  `/items` 下发。
- **Workflow Engine**：唯一的裁决点。状态流转候选、接收裁决、澄清与答疑的
  发起裁决（status、可澄清类型、并发约束（同文档互斥 + 总数上限，第十六轮
  由单会话约束改写）——第八轮起澄清是会话不是写回，
  decision-00006）、审计的发起裁决（status 为 `draft` 且类型可审计——第十轮，
  decision-00007）、plan 的 **resolved 门**（第十轮，spec FR-52：按 BR-24 从
  `implements` 解析交付范围，把证据限定为 `parent` 指向该 plan 的 record 后，
  复用 Doc Repository 的**同一**覆盖推导判定每个条目——推导函数以 record 集
  为入参，门只是换了证据集，口径与 `/items` 永不相异）、下一步候选、新文档
  id 中「类型 + 编号」的分配（BR-18；slug 由 agent 自取）全部在服务端计算，
  前端从不自行判断（spec FR-6…FR-10 的载体）。流转表（BR-2…BR-9）、可澄清
  类型集（BR-20）、可审计类型集（BR-23）与 resolved 门（BR-24、BR-25）由
  代码内建，不进配置；配置承载的是类型二分、产品流（BR-1、BR-13…BR-17，
  第十轮起含 `plan → issue/record`）与每个可澄清类型的焦点行（spec FR-48）。
  **治理轮（spec-00002）：状态流转通路上再加两道门**，二者与 resolved 门同处
  一地——`docService.changeStatus` 里 `assertScopeVerified` 所在的那一段：先由
  `applyStatusChange`（workflow.ts，纯函数）按流转表算出新正文，再依次过门，
  全部通过才写盘。放这里而不是放进 `applyStatusChange`，理由是归档门要读**全仓
  的 front matter 声明**，而 `DocService` 是唯一同时持有图与「刚重读的那份原文」
  的地方；三道门写在同一个函数里，`spec-00002-FR-1` 的「不得有一条通路绕过」
  才是读一个函数就能核的事。**以下两条内不带前缀的 `AC-n.m` 一律指
  `spec-00002`。**
  - **促进门**（`spec-00002-FR-1`/`FR-2`，`rule-00001-BR-12`）：来源为 `draft`
    且目标为该种类的促进态（living 的 `active`、work 的 `open`，取
    `statusRules.promotedStatus(kind)`）时，对**刚重读的整文件原文**调用
    `workflow.hasOpenQuestions`——与 `applyAccept` 调用的是同一个函数、同一个
    入参形态（两条通路都从 `readOrConflict` 取 content）。「未决」因此只有一处
    定义，两条通路不可能给出不同结论（`spec-00002-AC-1.7`）；日后改判定只改
    `hasOpenQuestions`，不动任何一道门。条件写成「目标是促进态」而不是「目标非
    draft」，`draft → archived`、work 的 `draft → wontfix` 与 `open → resolved`、
    living 的 `active → draft`（修订轮）因此自然不经此门（`FR-2`）。
  - **归档门**（`spec-00002-FR-3`/`FR-4`，`rule-00001-BR-19`）：目标为
    `archived` 时，在全图中找**另一份**文档的 front matter `supersedes` 列出该
    文档 id。三条判定细节写在设计里而非留给实现：
    1. **读声明，不读健康**——候选不按 `node.ok` 过滤，异常节点的 `supersedes`
       照样算数（`AC-4.6`）。这与 `toEdges` 里 `knownIds` 只收 `ok` 节点是**有意
       的不一致**：边解析问的是「目标是不是一份可用的文档」，配对只问「有没有
       另一份文档声明过替代」。边界一句：front matter **整体不可解析**的文件
       `doc.data` 为空，读不出任何 `supersedes`，故它不配对任何人——这不是本门
       的例外，是「读声明」在没有声明时的自然结果。
    2. **「另一份」按文件路径判，不按 id 判**（`node.path !== candidate.path`）：
       自己的 `supersedes` 不构成对自己的配对（`AC-4.3`），而路径是每份文档唯一
       的键——撞 id 时 id 不是（见下条 Doc Repository）。
    3. 不区分替代文档的类型与 status（`AC-4.1`、`AC-4.2`）；一份文档列出多个
       被替代 id、多份文档同列一个被替代 id 都成立（`AC-4.5`、`AC-4.4`）。
    已知后果，记明不修：`DocNode.relations` 的键取自流程配置的 `relations`，
    配置若不声明 `supersedes`，本门将永远找不到配对、一切归档都到不了。本轮不为
    它加启动校验（`spec-00002-FR-6` 未列该项），留给 plan 轮判断是否补。
- **Editor**：编辑与预览是同一份正文的两个视图——预览渲染的是编辑器**当前
  缓冲区**而非磁盘内容，切换不落盘也不丢改动（spec FR-22）。预览时 CodeMirror
  视图只隐藏、不卸载，光标与滚动位置因此保留（spec FR-25）。渲染前剥掉 front
  matter：编辑器持有整文件（front matter 要可见可改），但 `---` 块作为 Markdown
  会渲染成分隔线加 setext 标题。`mermaid` 代码块由 react-markdown 的
  `components` 钩子截获后交给 `mermaid.render()`，每个图独立渲染，一个图失败
  只坏一个图（spec FR-23）。FR-24 由两件事共同保证：不启用 `rehype-raw`，
  文档中的原始 HTML 被丢弃；mermaid 以 `securityLevel: 'strict'` 初始化，它产出
  并经 innerHTML 注入的 SVG 由它自己消毒——这条通路是 `rehype-raw` 那一半论证
  覆盖不到的。
- **Session Manager**：会话注册表——**多会话，键为会话 id，容量
  `max_sessions`**（第十六轮随 decision-00009 由单例槽位改写；并发约束
  见 §5），生命周期与浏览器连接解耦（spec FR-21）；每会话各自持有 PTY 与
  最近 1 MB 的滚动缓冲，重连与切换时回放——「此前输出」的完整性以该窗口
  为限。**第十一轮起（decision-00008）**：会话
  收尾时把元数据落盘 `.whiteboard/sessions/<会话 id>.json`、纯文本转写落盘
  `<会话 id>.log`（第一读者是人，与 JSON 状态文件的分工互补——
  decision-00008 §2 第 3 条；gitignore 内，落盘失败只提示不阻塞收尾，
  spec FR-54）；发起会话可指定流程配置 `agents` 中任一条，缺省第一条
  （spec FR-55）。
- **Doc Repository（第十一轮补）**：解析结果按变更失效缓存——watcher 事件
  与白板自身的写路径（编辑、状态、新建、会话收尾）都使缓存失效，未失效的
  重复请求不重读整树（spec §7 非功能项）。会话产物的异常标记（FR-17 的
  `lastFinding`）随每次图构建按磁盘当前内容重验，验证通过即清除——不再等
  下一次推进（issue-00014 的修复口径）。
- **Doc Repository（治理轮补，spec-00002）**——三件事，都落在既有的
  `buildGraph` 一遍里。**本条内不带前缀的 `FR-n` / `AC-n.m` 一律指
  `spec-00002`**（低号 FR/AC 在 `spec-00001` 里另有其人，凡指后者处均写全）：

  **撞 id（`FR-8`/`FR-9`）**：`docs.map(toNode)` 之后按 front matter id 分组，
  凡一个 id 落在两份及以上文件上，**每一份**都改以**文件路径**为节点键
  （`node.id = node.path`）、置 `ok = false`、problem 点名其余同 id 文件的路径，
  并新增字段 `duplicateOf` ＝那个撞的 id（节点标签与命令面板检索都要它，
  `AC-8.1`/`AC-8.4`，见 design-00002 §4）。这与无 id 节点的处置是同一处置——路径
  本来就是 `toNode` 的回退键。**下游三件事因此自动成立，不加新判断**：
  1. 撞的那个 id 不再是任何节点的键，`toEdges` 的 `knownIds` 与 `itemOwners`
     都命不中它，指向它的边即判 `ok: false`（`AC-8.6`）；
  2. `itemOwners` 本就跳过 `!node.ok` 的节点，撞 id 文档的条目因此不被任何一处
     认领（`FR-8`）；
  3. 呈现状态按节点键保持，键即路径，刷新后选中仍落在同一份文件上（`AC-8.9`）。

  **要改的有三处，一处都不能少**：

  1. **resolved 门的条目文档集**（`docService.assertScopeVerified` 里
     `declaresItems(type)` 那一步）加 `duplicateOf === undefined`；
  2. **全局覆盖率视图的文档集**同样加 `duplicateOf === undefined`。
     这两处今天不按 `ok` 过滤，因而不被上面第 2 条捎带；**判据取 `duplicateOf`
     而不是 `ok`**，因为两处都必须继续服务「front matter 异常但正文可解析」的
     文档（`FR-10` 明写，resolved 门今天亦然），只有撞 id 才出局。撞的 id 既不在
     条目集、也不在 `docIds` 里，落到它上面的交付范围 id 因此经 `deliveryScope`
     的 `unresolved` 一支计为无法解析的缺口（`AC-8.8`），这一步不加新判断。
  3. **取号必须按「声明的 id」数，不能按节点键数**——这一处是**改键的反作用，
     不修就出新缺陷**：`highestNumber`（`docRepository.ts:277`）拿
     `ID_PATTERN` 去 `exec(node.id)`，而撞 id 节点的键已被改成文件路径，路径不
     匹配该模式，于是那个被撞的编号**从计数里消失**，`allocateNumber` 会把它
     **再发一次**——本来只是两份撞 id，一取号就成了三份，直接违反
     `rule-00001-BR-18`。**实现要求**：本节引入一个统一读法——**声明的 id**
     ＝`node.duplicateOf ?? node.id`——`highestNumber` 按它匹配。
     **`DocService.create` 的存在性校验同样按它判**：现行
     `findNode(graph, id) || existsSync(absolute)` 两条都不够——`findNode` 找的是
     节点键（撞 id 后不含那个 id），而 `existsSync` 只看**规范路径**
     `docs/<type>/<id>.md`，一份放在非规范路径上的同 id 文档它看不见，于是新建
     会再落一份、把两份撞 id 变成三份。校验必须问「有没有任何节点的**声明的
     id** 等于它」，`existsSync` 仅作最后一道防线保留。
  按撞的 id 寻址的写入（`FR-9` a）：`DocService.require` 找不到节点时先看是否有
  节点的 `duplicateOf` 等于该 id——是则抛 `ConflictError`（`409`），消息点名那几份
  文件并要求先修复 id 冲突（`AC-9.2`）。**状态码取 409 而不是 422，本文钉死**：
  `FR-9` a 只说「拒绝」，没指定码；409 的语义（请求与资源的当前状态冲突）正是
  「这个 id 现在指不到唯一一份文档」，且它复用 `require` 既有的 `ConflictError`
  通路，不新增错误类与映射。422 留给「工作流裁定拒绝了这个动作」——两道新门用它
  （§7）。按路径寻址的编辑（`FR-9` b）不需要
  新端点：异常节点的编辑入口本就用节点键，而它现在就是路径；**但节点键含 `/`，
  客户端必须 `encodeURIComponent` 后再拼进 URL**——已实测 Express 5 的 `:id` 会把
  `%2F` 解回斜杠，**路由不必改，改的全在客户端**。
  **范围要说全**：`web/src/api.ts` 里 `/api/docs/:id` 这一族的**七个调用点**
  （`doc`、`items`、`save`、`transitions`、`setStatus`、`accept`、`nextSteps`）
  一律直接字符串拼接、都不编码；只有 `createPrefill` 走查询参数并已编码。所以
  这不是「编辑那一处」的问题——凡以节点键寻址的调用都要一起补，否则异常节点仍会
  在别的入口上失败。这是**先于本轮存在的缺口**（无 id 的异常节点今天同样编辑
  不了），`FR-9` b 的落地依赖它被补上；按仓库约定先开 issue 复现再修，**归宿已
  定**：`issue-00016` 与 `plan-00012` T4，本轮不就地处置。

  **关系矩阵校验（`FR-5`…`FR-7`）**：这是**第一条不来自正文的诊断**——它读
  `doc.data`（front matter 原值），因此产在节点那一遍里，与正文解析、正文缓存
  都无关。两件事各产一条 `relation-field` 诊断：该文档 `type` 在矩阵中不被允许
  的关系字段；以及 `parent` 声明为含**两个及以上** id 的列表（单元素列表按单值
  读，不诊断——`FR-7` 说的是「多值」）。诊断行沿用 `GraphDiagnostic`：
  `kind: 'relation-field'`（`DiagnosticKind` 新增此值）、`docId` ＝节点键、
  `text` ＝字段名与该类型（诊断清单要显示的那一行）；**不带 `line`**——front
  matter 的行号不在其余诊断所用的「正文相对行号」编号里，清单须容忍缺行号（该
  字段本就可选）。矩阵缺失、或某类型不在矩阵中，则该文档不过此校验（`AC-6.4`、
  `AC-5.4`）。诊断不改 `node.ok`、不改边、不改覆盖推导——它与 `graphDiagnostics`
  并列拼进 `graph.diagnostics`，无任何连锁（`AC-7.2`）。
  两条裁定写明，因为它们决定了配置长什么样（二者均已由领域负责人裁定，不是
  本文的读法）：
  - **`supersedes` 在查表前先放行**。`docs/README.md` 把它授予每一种类型（替代
    文档一律带它），它不是任何文件夹 README 的 Relations 约定，故不进矩阵；矩阵
    只回答「某文件夹 README 的关系字段属不属于这个类型」。这正是 `idea` 与
    `prompt` 能保持空集合（`FR-5` 明写「这两型不带任何关系字段」）而它们仍可被
    替代的原因。反面做法——把 `supersedes` 逐类型列 16 遍——被否决：它不携带任何
    信息，且日后新增一个类型忘了带它就会产出假诊断。
  - **`parent` 单值一条是代码，不是配置**——**治理轮已裁定**，`spec-00002-FR-5`
    与 `CONTEXT.md` 的「关系矩阵」条目已同步改写为这一分工。理由是它属于文档
    体系的**结构不变量**，不是逐项目可调的旋钮，故与流转表同类：写死在代码里，
    由矩阵这一遍报出，与矩阵违规共用同一条 `relation-field` 诊断类别。矩阵的形状
    是「类型 → 字段列表」，本就表达不了元数；曾考虑另立 `single: [parent]` 键，
    否决：`docs/` 里没有第二处声明元数，多一套语法换不到任何人要过的可配置性。

  **正文解析缓存下延一层（`spec-00002` §7 非功能项）**：第十一轮的缓存只存
  `DocGraph`（front matter 一层），`/items`、resolved 门与 `graphDiagnostics`
  每次都重新 `readDocBody` 整棵树。全局覆盖率视图一次要读全部 spec/rule 与全部
  record，是现有读取里最重的一处，故缓存**两样东西**：（1）「文件读取 +
  gray-matter 分离」的结果，键为文件路径；（2）以**全部 record** 为证据集的那
  一次 `scanRecords` 与逐文档 `requirementViewFrom` 结果，**按文档缓存**。
  **共用的是缓存与证据集，不是文档集——这三者各选各的文档，不得合一**：
  - `graphDiagnostics` **保留它现有的 `node.ok` 过滤**（`spec-00001-FR-40` 的
    当前行为），front matter 异常的文档不产文法诊断；
  - `/coverage` 选 `declaresItems(type) && duplicateOf === undefined`，
    **不按 `ok` 过滤**——`FR-10` 明写异常但正文可解析的文档在列；
  - `/items` 选被点名的那一份。

  写明是为了防一种「顺手统一」：三处都读同一份缓存，看上去像是可以抽成一个
  「算出全部文档的覆盖」的函数再各自取用——**那会把 `graphDiagnostics` 的
  `ok` 过滤一并抹掉，静默改掉 `spec-00001-FR-40` 的行为**。缓存共用到
  `requirementViewFrom` 的**逐文档结果**为止，选哪些文档是各调用点自己的事。
  resolved 门的证据集是收窄过的
  （`parent` 指向该 plan 的 record），**不复用推导结果**，但复用（1）。
  **失效条件不新增**：与 `DocGraph` 同一个 `DocService.invalidate()`——一个缓存、
  一个失效信号，图与正文因此不可能各自停在不同的磁盘状态上。
- **Git Layer**：每个动作 `git add <涉及路径>` 后 commit，从不 `add -A`
  （spec FR-14 的"只暂存本次动作涉及的文件"）。commit 失败不回滚已写盘内容
  （spec FR-20），失败信息沿 API 返回给前端呈现。**快照与差集的能力归它**
  （取快照、按内容求差，见 §4）；**快照的生命周期归 Session Manager**——它在
  启动会话时取一次并随会话状态保存，结束时交回 Git Layer 求差集。分工的理由：
  git 语义不外泄，会话期状态不进 git 层。
- **Watcher**：chokidar 监听 `docs/**`，去抖后经 WS 向每个已连接的白板广播
  「图已变」信号（spec FR-42/FR-43 的载体，链路见 §6）。它只读文件系统事件，
  不解析文档、不持有图——解析仍归 Doc Repository。

## 3. 流程配置契约

`whiteboard.config.yaml` 位于仓库根部（与 `docs/` 同级、用户直接可编辑），
服务启动时加载并校验；**缺失或非法即拒绝启动**（spec FR-15，无内置默认回退；
开箱即用靠模板仓库自带一份该文件）。

```yaml
types:
  idea:     { kind: living }
  prd:      { kind: living }
  spec:     { kind: living }
  rule:     { kind: living }
  design:   { kind: living }
  decision: { kind: living }
  # …其余 living 类型
  issue:    { kind: work }
  plan:     { kind: work }
  task:     { kind: work }

relations: [parent, implements, informs, motivated_by, constrains, blocks, verifies, supersedes]

flow:                       # rule-00001-BR-13…BR-17
  idea: [{ next: prd, carry: parent }, { next: spec, carry: parent }]
  prd:  [{ next: spec, carry: parent }]
  spec: [{ next: rule, carry: informs }, { next: design, carry: informs }, { next: plan, carry: implements }]
  plan: [{ next: task, carry: parent }, { next: issue, carry: blocks }, { next: record, carry: parent }]
  # 未列出的类型即"无下一步"

entry: [idea, prd]          # rule-00001-BR-26 的流程入口类型（spec FR-53）；缺失或空 = 无新建入口

max_sessions: 3             # 会话并发上限（spec-00003-FR-3，第十六轮）；缺失取缺省 3，非正整数拒绝启动

exclude: []                 # 配置排除（spec-00010-FR-1，第二十七轮，§14）：相对 docs/ 的 glob 列表；缺失/null/空 = 不排除，形态非法拒绝启动

carries:                    # 治理轮（spec-00002-FR-5）的关系矩阵：类型 → 该类型允许声明的关系字段
  spec:   [parent]
  design: [informs]
  record: [parent, verifies]
  idea:   []                # 空列表 = 不带任何关系字段
  # 未出现在此处的类型不做该校验；supersedes 不列，它对每种类型都允许

agents:
  claude:
    command: claude
    args: []            # 权限相关参数见下方「写权限约束」；接入时逐 CLI 验证
    cwd: docs           # 会话工作目录取 docs/，作为第一层越界屏障
    # 第二十六轮（spec-00009-FR-1）两个可选键：
    # model: claude-sonnet-5          # 非空字符串；有它则 args（及 headless 两数组，若声明）须含 {model}
    # env: { FOO: bar }               # 字符串映射，叠加到子进程环境；{} 合法
    # args 与 headless.first/resume 中的 {model} 每处替换为 model（子串亦可，如 --model={model}）
```

- 校验规则（spec FR-15 的"文档类型、关系字段、下一步映射、入口类型列表、
  agent 命令与写权限约束"）：`flow` 中的类型必须在 `types` 中且 `carry` 在
  `relations` 中；`entry` 若有，其中每个名字必须在 `types` 中（FR-53）；
  `agents` 至少一项，`command` 非空字符串、`args` 为字符串数组、`cwd` 若有必须
  是 `docs` 内路径；`max_sessions` 若有必须是正整数（缺失取缺省 3——
  spec-00003-AC-3.4/AC-3.5，第十六轮）。任何违规 → 启动失败并指明条目。
  **第二十七轮增（`spec-00010-FR-2`）**：`exclude` 的读法与逐项校验见 §14.1。
  **第二十六轮增（`spec-00009-FR-2`）**：`model` 若有须是非空字符串；`env`
  若有须是字符串到字符串的映射；`{model}` 与 `model` **按形态成对**——
  `args` 或 `headless` 任一数组含 `{model}` 而无 `model` → 拒绝；有 `model`
  而 `args` 无 `{model}` → 拒绝（点名 `agents.<n>.model`）；有 `model` 且声明
  `headless` 而 `first` 或 `resume` 无 `{model}` → 拒绝（点名该数组）。「含」
  是至少一次（现有 `placeholders(argv, placeholder)` 计数、`requirePlaceholder`
  只做恰等，需新加一个「至少一次」的检查；模型可在一条命令里出现多次无害，
  与 `{question}` 的恰一次不同）。这套条目校验抽成一个对**单条**条目的纯
  函数 `readAgentEntry(name, raw, at)`——今天的 `readAgent` 把错误位置硬编码
  为 `agents.<name>`，抽出后位置前缀由调用方给：项目层传 `agents.<name>`、
  本地层传 `overrides.<name>` / `entries.<name>`（§13.2 的 `at` 由此而来）。
  项目层由启动校验调用、抛 `ConfigError`；本地层由 §13 的合并调用、把同一
  错误当作「本地层不合式」收下而不抛——两层一套规则（`spec-00009-FR-3`
  末句）靠的就是共用这一个函数。
- **写权限约束（spec FR-13）**：机制为 per-CLI 适配——首选「会话工作目录设为
  `docs/` + CLI 自身的权限模式（越界写需显式批准）」，辅以 CLI 的
  allow/deny 权限参数。**本节的具体参数是意向而非已验证事实**：每个接入的
  CLI 必须先以 spec AC-13.2（越界写不落盘）实测通过，才可进入模板自带配置；
  未通过验证的 CLI 不进默认配置。
- 多 agent 并存时可在发起会话时指定其中一条（`POST /sessions*` 的可选
  `agent` 字段），缺省取配置中的**第一项**；未知名字拒绝且不启动（spec
  FR-55，第十一轮取代原「由用户选择 CLI 留后续版本」的搁置）。**第二十六轮
  起「配置中」读作有效 agent 列表**（§13）：本节的 `agents` 是它的项目层，
  本地层与合并规则在 §13；本节的校验规则对两层同一。
- **关系矩阵 `carries`（治理轮，spec-00002-FR-5/FR-6）**。**键名取
  `carries` 的理由**：配置里已有 `flow[].carry`（「这一步带上哪个关系」），
  `carries` 是同一个动词在类型这一层的用法（「这个类型带哪些关系字段」），不引入
  第二套词汇；两者的分工写进配置文件注释——`carry` 是逐步的，`carries` 是逐类型
  的。校验规则（与 `types`/`relations`/`entry` 同一遍，任何违规即拒绝启动）：
  - `carries` 必须是映射；其每个键必须在 `types` 中，否则拒绝启动并**指明该
    类型**（`AC-6.1`）；
  - 每个值必须是**字符串列表**，否则拒绝启动并指明该类型（`AC-6.3`，一个裸
    字符串即此情形）；
  - 列表中每个字段必须在 `relations` 中，否则拒绝启动并**指明该字段**
    （`AC-6.2`）；
  - `carries` 缺失、为 null 或为空映射：**照常启动，不做字段-类型校验**
    （`AC-6.4`）——与 `entry`、`focus` 缺失的读法一致，向后兼容优先。
  - **空列表与「不出现」不同**，这是 `FR-5` 明写的两种语义：空列表＝该类型不许
    带任何关系字段（会产诊断），不出现＝不校验该类型（不产诊断）。校验代码因此
    要分得清「键存在且值为 `[]`」与「键不存在」，不能把二者归一。
- **本轮就把矩阵写进仓库根的 `whiteboard.config.yaml`**，按各文件夹 README 的
  「Relations」小节填满 16 个类型（`idea` 与 `prompt` 为空列表，它们没有该小节）。
  这**不会拦住今天的白板**：现行 `parseFlowConfig`（`tools/whiteboard/src/config.ts`）
  在 `asRecord(raw, 'config root')` 之后只读自己认识的键，没有未知顶层键的拒绝
  分支，故矩阵在实现落地前只是一段被忽略的配置——已实测启动与既有配置用例照常
  通过。因此不采用「注释掉 + 留 TODO」的写法。已按这份矩阵对全仓 front matter
  跑过一遍：**现有文档产出 0 条 `relation-field` 诊断**（含 `parent` 多值一项），
  与 `spec-00002` §1 的读数一致——矩阵落地时不会一上来就亮一片诊断。

## 4. 推进（Advance）交互

```mermaid
sequenceDiagram
  actor U as 用户
  participant FE as 前端
  participant WE as Workflow Engine
  participant SM as Session Manager
  participant GL as Git Layer
  U->>FE: 点击节点「+」
  FE->>WE: GET next-steps(docId)
  WE-->>FE: 候选类型（flow 表）
  U->>FE: 选定类型
  FE->>SM: POST sessions {sourceId, targetType}
  SM->>WE: 取新文档的类型+编号（BR-18；编号入保留集，§5，第十六轮）
  SM->>SM: 渲染任务指令，spawn pty（command/args/cwd）
  SM-->>FE: sessionId
  FE->>SM: WS attach（?sessionId，§7）→ xterm.js 双向流
  Note over SM: 会话运行，与浏览器连接解耦
  SM->>SM: pty exit → 按 <type>-<编号>- 前缀扫描回收完整 id
  SM->>GL: commit 会话变更（wb(advance): <new-id>）
  SM-->>FE: 会话结束事件
  FE->>WE: 刷新图 + 定向校验（FR-17）
```

- 任务指令模板（作为会话的初始输入经 PTY 写给 CLI——各 CLI 都支持交互式
  stdin，免去命令行转义差异）：目标类型、指定的 `<type>-<编号>-<slug>` id
  格式（编号已定，slug 自取）、按 flow `carry` 应携带的关系与来源 id、对应
  文件夹 `TEMPLATE.md` 与 `README.md` 的路径、「status 保持 draft」约束、
  **来源文档路径**（第十三轮）；
  目标类型有条目文法时（spec/rule/record）另附该类型的「机器可读形态」要求
  （spec FR-41，decision-00005）；目标类型带 Open Questions 语义（可澄清集）
  时另附**上游未决点继承**要求（spec FR-11 第十三轮，与文法段同为条件追加，
  排在无条件指令行之后）。会话结束的产出校验相应扩展到正文文法，
  诊断按 FR-40 呈现、不阻塞 commit。
- **会话结束处理**：按会话记录的期望 `{targetType, 编号, carry, sourceId}`
  定向校验产出文档（id 前缀匹配、carry 关系指向来源）——这就是 FR-17 的
  "会话感知校验"，不合规进 `issues` 标异常。找不到前缀匹配文件时视为无产出，
  commit 信息退化为 `wb(advance): <sourceId>`。
- **会话的暂存范围**（第十六轮起四种会话同一机制，本节以 advance 为例）：
  commit 时暂存 `docs/` 下自会话启动以来的全部变动
  路径。**「相对会话前快照」是实现约束，不是修辞**：会话启动时必须取一次
  `docs/` 的快照，结束时以差集为暂存集——只按前缀过滤当前 `git status` 会把
  会话前就脏的文件一并卷入（`issue-00008` 即此缺陷，由 `AC-14.5`/`AC-14.6`
  守住）。
  **差集按内容算，不按路径集算**（这是唯一能让 FR-14、`AC-14.4`、`AC-14.5`
  同时成立的读法，故写在设计里而非留给实现）：快照记录每个当时已脏的 `docs/`
  路径**及其内容摘要**；结束时对当前脏路径分三种处置——快照里没有的（会话新建
  或新改的）**暂存**；快照里有且摘要不变的（会话没碰）**排除**；快照里有而摘要
  已变的（会话在别人的脏文件上继续改的）**暂存**。若只按路径集求差，第三种会被
  误排除，agent 对一份本来就脏的文档所做的修订将永远提交不进去——而「在既有
  草稿上继续写」正是本仓最常见的推进形态。
  边界声明：会话期间外部对 `docs/` 的改动无法与会话产出区分，单人前提下
  接受；**并行会话在对方快照之后写入的路径，归属按结束顺序**——先结束者
  的差集按上述内容规则计算，故其 commit 允许含对方已写入的内容，同一文件
  与不同文件皆然（内容差集不携带归属，这是机制的固有边界；已知噪音，
  decision-00009 §2 第 9 条及其推广追注），任何变更不丢失；后结束者收尾
  时无残余则不产生 commit（第十六轮 T5 推广）。
  **第十六轮起快照-差集机制推广到四种会话**（`CONTEXT.md`「会话前快照」
  已随之扩义；成员随轮次变——答疑第二十一轮退出（无快照无 commit，
  §10.3），共写第二十二轮入列（内容快照，§11.3）：现四种 = 推进、澄清、
  审计、共写），且白板发起的全部 commit——终端会话的收尾 commit 与用户
  动作 commit（FR-14）——进**同一条串行队列**：逐会话在收尾时刻取当前
  脏路径对自己快照求差、暂存、commit，队列保证互不吞并
  （spec-00003-FR-8）。
- 会话正常退出但 `docs/` 无任何变动时跳过 commit。

## 5. 会话生命周期

```mermaid
stateDiagram-v2
  [*] --> running: POST sessions（并发约束放行时，第十六轮）
  running --> running: 浏览器断开/重连（缓冲回放）
  running --> exited: pty exit
  running --> terminated: DELETE sessions/:id（终止，FR-49；第十六轮增第三结束态）
  running --> failed: spawn 失败（FR-16，无 commit，释放槽位）
  exited --> [*]: 有变更则 commit + 刷新
  terminated --> [*]: 收尾同 exited；面板与历史标「终止」
  failed --> [*]: 终端呈现错误
```

**第二十六轮（`spec-00009-FR-1`/`FR-5`）**：`POST sessions*` 受理时
`resolveAgent` 从有效 agent 列表（§13）取条目并整份存进会话——`Session.agent`
今天已经持有整个 `AgentConfig`、`launchTerminal` 与 headless 的 `launch` 也已
只读它不查列表，这一点**不是新改动**；新的是列表每次重算、每次返回**新建**
的对象（§13.2）——快照语义靠这一点成立，合并结果**不得 memoize** 成共享
引用。命令、`args`、`cwd`、`model`、`env`、`headless` 由此在受理时定格，其后
的设置保存不再触及这个会话（已受理未 spawn 的亦然）。
spawn 时 `args` 经 `fillModel(args, model)` 把每处 `{model}` 换成 `model`
（无 `model` 时原样——校验已保证此时数组里没有占位），`SpawnPty` 增第四参
`env`，实现为 `{ ...process.env, ...agent.env }` 喂 node-pty；headless seam
同形（§10.1）。

退出收尾（commit + 刷新）恰执行一次：`pty.onExit` 只触发一次，终止与自然
退出竞态时先到者定（FR-49）。第十一轮起收尾序列在 commit 之前多一步
**历史落盘**（元数据 `.json` + 转写 `.log`，spec FR-54）：落盘失败不阻塞
后续步骤——commit 与刷新照常，失败仅提示。

**第十六轮（并行会话，spec-00003 / decision-00009）**——生命周期按会话
各自独立，另加五条注册表级规则：

- **槽位记账**：槽位在服务端**受理发起时**占用（互斥与上限的判定与占用在
  同一次受理里串行完成——先到先得由此免费获得，spec-00003-FR-3）；spawn
  失败即释放，failed 会话不计入运行中总数，但照常入会话列表（面板呈现
  「失败」）并发提示条（spec-00003-FR-7）。
- **id 保留**：推进会话受理时分配的目标文档编号进注册表的保留集；取号时
  把保留中的号视同已占用。这是并发下取号的**系统机制**，不修订
  `rule-00001-BR-18` 的业务语义（「现有最大编号加一」）——保留的号正是
  即将落盘的文档的号。会话结束（产出落盘或无产出）即出保留集。无此保留，
  两个并行推进会拿到同一个号（spec-00003-FR-1）。
- **等待输入判定**（spec-00003-FR-6；第十八轮改双通路 + 锁存，
  decision-00011）：每会话维护「最近输出时刻」与一个**锁存位**。
  **静默通路（主判定）**：连续无输出达静默阈值（实现常数，取 10s）且
  进程存活 → 会话状态附 `awaiting: true`；锁存期间该计时照常武装，其
  触发是幂等空操作（无需特判停摆）。**信号通路（锁存）**：`onData` 在
  解除标志与重臂静默计时**之前**先做序列识别——输出中匹配到
  `\x1b]777;notify;`（ESC 1 字节 + 12 字符，共 13 字节；onData 交付
  string，模式全 ASCII、解码不影响切分；只认前缀，标题正文不解析）即
  `awaiting: true` 并置锁存位；跨块识别用逐会话尾缀缓冲：每块扫描前拼
  上上一块留下的尾缀，扫描后留「拼接串的**最长**真前缀后缀」（上界 =
  前缀长度减一 = 12 字节），拆块投递不漏判、缓冲有界；已锁存时再匹配
  为幂等（标志无翻转即无广播，见 §7 事件来源）。**定序**：含信号序列
  的输出块只置位、不解除——同块中序列前后的其余字节亦不触发解除（信号
  识别先于输出解除，spec-00003-FR-6 明文）。**解除**：未锁存时任何新
  输出或退出即清除（现行弱语义）；已锁存时仅两事件解除——**用户输入**
  （终端 WS 文本帧转发的那次 `SessionManager.write`，即任一按键、无需
  提交；服务端自发的写不算：任务指令正文、延迟提交键与共写的手改注记
  前缀（§11.4，第二十二轮增），§7 / issue-00011）
  或会话结束；解除即清锁存位并重臂静默计时。未呈现的会话无终端接入、
  收不到文本帧（design-00002 §12：只有挂载中的 xterm 转发按键），其
  锁存保持到被呈现并输入。进程已退出（收尾中）不置位，两通路皆然。
  判定只改会话载荷（面板与徽标读它），不触发状态机迁移、不触发
  commit——弱语义，允许误报（两条接受的代价见 decision-00011 §4：CLI
  发信号后不经输入自行续跑则标志错误保持；会话输出内容自带真实
  OSC 777 字节则误锁存）。锁存位的状态机：

  ```mermaid
  stateDiagram-v2
    unlatched --> latched: 输出中匹配到 \x1b]777;notify;（进程存活）
    latched --> latched: 再次匹配（幂等）/ 任何输出（不解除）
    latched --> unlatched: 用户输入（write 转发帧）/ 会话结束
    unlatched --> unlatched: 输出照现行弱语义解除标志并重臂静默
  ```
- **刷新合并**：会话收尾的 `/api/events` 广播经一个短去抖窗口（量级
  100ms，与 §6 watcher 推送的去抖同语义）合并——两个「几乎同时」结束的
  会话经串行队列相继收尾，其广播落在同一窗口即只发一次
  （spec-00003-FR-8 / AC-8.3）；提示条逐会话各发一条，不合并
  （spec-00003-FR-7）。
- **关停收尾**：服务端收到正常关停信号时，对注册表中每个 running 会话
  依次执行终止路径（信号升级同 issue-00012），逐会话走既有收尾
  （历史落盘 + commit，经同一串行队列）后再退出进程；异常崩溃不保证
  （spec-00003-FR-9）。重启后注册表为空，面板空态；「本次服务启动以来」
  的已结束会话列表也由注册表内存持有，不做持久化——跨重启回看走
  `.whiteboard/sessions/` 的会话历史（FR-54）。

## 6. 写路径与冲突

- 所有写（编辑保存、状态切换、接收、新建的保存——第十一轮）走同一条服务端
  管道：**读盘校验 → 裁决（Workflow Engine）→ 写盘 → commit**。第十六轮起，
  本管道末端的 commit 与四种会话的收尾 commit 进**同一条串行队列**（§4/§5）：
  用户动作与会话收尾并发时由队列定序、互不吞并（spec-00003-AC-8.4）。新建的
  「读盘校验」是不存在性校验（目标 id 已存在 → 409，FR-53/AC-53.3）。澄清与答疑不走
  写管道——它们发起会话，写由会话内的 agent 完成（第八轮，decision-00006）。
- **第二十二轮追注**：状态切换与接收在进入下述裁决段**之前**另有一道
  会话锁前置判定——目标文档有运行中共写会话即 409 `doc-busy`（§11.4）。
  它不入三门次序：门裁决的是流转合法性，锁拒绝的是资源占用冲突，码也
  不同（409 vs 422）。
- **状态切换这一支的裁决段有固定次序**（治理轮，spec-00002）：
  **流转合法性表（`spec-00001-FR-7`）→ 促进门（`spec-00002-FR-1`）→ 归档门
  （`spec-00002-FR-3`）→ resolved 门（`spec-00001-FR-52`）**，全部通过才写盘。事实上这三道门**两两互斥**
  ——促进门只看「draft → 促进态」，归档门只看「→ archived」，resolved 门只看
  「plan open → resolved」，一次流转至多触发其中一道，故次序不改变任何一次拒绝的
  结论；把它定死是为了两件事：拒绝消息与用例的可预期，以及代价递增（合法性只看
  两个字段，促进门只读一份文件，归档门读全图 front matter，resolved 门要正文
  推导）。**拒绝不留半写**：新正文是 `applyStatusChange` 在内存里算出的字符串，
  门在 `writeFileSync` 之前跑完，任何一道拒绝都发生在唯一那次写盘之前，也就没有
  commit（`spec-00002` §7 第 3 条、`spec-00002-AC-1.1`、`AC-3.1`）；重复请求同样
  被拒且同样不改文件（`AC-1.4`、`AC-3.4`——本条内 `AC-n.m` 均指 `spec-00002`），
  因为门是纯判定、不留状态。

  ```mermaid
  flowchart LR
    RQ[POST /status] --> LG{流转合法性表<br/>spec-00001-FR-7}
    LG -->|不合法| RJ[422 拒绝<br/>不写盘·无 commit]
    LG -->|合法| OQ{促进门<br/>spec-00002-FR-1}
    OQ -->|有未决 Open Questions| RJ
    OQ -->|通过或不适用| AR{归档门<br/>spec-00002-FR-3}
    AR -->|无 supersedes 配对| RJ
    AR -->|通过或不适用| RV{resolved 门<br/>spec-00001-FR-52}
    RV -->|交付范围有缺口| RJ
    RV -->|通过或不适用| WR[写盘 → commit]
  ```

- 冲突检测（spec FR-5）：编辑器打开时记录整文件 hash；保存时 hash 不符或文件
  不存在 → `409`。状态切换/评审按「动作发起时的 status」做 compare-and-swap：
  落盘前重读，status 已变或文件已删 → 拒绝（FR-19）。
- **写回策略**：状态切换只做 front matter `status:` 行的原地替换——不经
  front matter 重新序列化，避免键序重排与注释丢失。编辑保存则整文件覆写
  （内容本来自编辑器全文）。~~澄清在 Open Questions 小节追加列表项~~——
  第八轮起澄清没有服务端写回，收尾写入 Open Questions 由澄清会话的 agent
  完成，小节定位约定（匹配 `Open Questions` 标题、不命中才文末建节）随之
  移入澄清任务指令（spec FR-45）。
- **commit 失败语义（FR-20）**：写盘成功而 commit 失败时，API 返回
  `200 { committed: false, error }`——文件保留，前端据此呈现错误。
- **变更推送**（`spec-00001-FR-42`…`FR-44`，第七轮由 `issue-00007` 落地——
  此前本段只是承诺，服务端从不监听、前端从不订阅）：chokidar 监听 `docs/**`
  → 合并窗口内的连续事件（去抖 ~100ms；上界由 `FR-42` 的「1 秒内可见」约束，
  在其下自由取值）→ 经 WS 广播一个「图已变」信号（**不带载荷**：前端收到即
  重取，避免推送与请求两条路径各自维护一份图）→ 前端按 id 保持呈现状态
  （`FR-44`）。
  **一次刷新重取的范围**：`GET /api/graph`，**以及当前选中或下钻文档的
  `GET /api/docs/:id/items`**——覆盖状态、诊断、展开行与详情目标都活在 items
  载荷里，只重取 graph 则条目侧的变化不可见（`AC-42.2`、`AC-44.1`…`AC-44.7`
  依赖这一半）。无选中且未下钻时只取 graph。
  **治理轮（spec-00002）加第三项，已由领域负责人裁定**：**全局覆盖率视图打开
  期间**，同一次刷新一并重取 `GET /api/coverage`；视图未打开时**不取**——它是
  最重的一次读取，没人在看就不该跑。（第四项——问题列表**或标注列表**
  打开期间重取 `GET /api/asks/:id`（后一半第二十三轮增，理由见 §12.6）
  ——见 §10.3；**第二十二轮加第五项**：共写目标文档的
  编辑器打开期间重取 `GET /api/docs/:id` 与当前 `baseHash` 比对，缓冲重载的
  判据来源，design-00002 §15；未在共写中不取。**第二十三轮加第六项**：目标
  文档的**编辑器打开期间**重取 `GET /api/annotations/:id`——条件取编辑器
  而非「列表打开」，是对前五项口径的有意偏离，裁定与理由属 design-00002
  §16.8，服务端侧见 §12.6。）三份载荷共用同一条通路，覆盖率视图因此
  没有自己的刷新机制。后果按 design-00002 §10 的既有规则落位：行随刷新重新推导，计数当场
  更新（`spec-00002-AC-10.4`）；一份已被删除的文档**整行消失**——这就是「就近
  关闭」用在视图行上（并入 `spec-00001-FR-44` 族）；展开态按**文档 id** 保持
  （`spec-00002-AC-11.5`），所指文档没了则该展开态一并消失。`spec-00002-AC-12.5`
  因此守的不是「刷新之后」，而是**推送尚未到达视图、或点击与刷新同刻**的那个
  竞态窗口：点击落到一份磁盘上已不存在的文档时，沿用既有的选中失败通路，以
  提示条拒绝、不改变当前选中。
  连接未建立或中断时白板照常可用、自动重连（间隔递增，起始 1s、上限 30s），
  连接建立或重建即刷新一次补回断连期间的变化（`FR-43`）；不轮询。白板自身动作
  与会话结束后的刷新通路照旧，三者共用同一个「重取 + 保持呈现状态」实现。
  **推送不自激**：白板自身写盘同样会触发监听，但收到信号后只做只读重取，
  不再写盘，故不形成回环；「变化→删除→再建」一类序列由「无载荷 + 全量重取」
  自然收敛到磁盘现状，无需事件排序。
  **零订阅者不是错误**（`AC-42.8`），多个白板各自收到同一信号（`AC-42.9`；
  多人协作仍在 spec §6 范围外——这里只是同机多标签页）。
  **与会话的关系**：会话期间 agent 的写入照常触发推送（`AC-42.7`），FR-12 的
  「会话退出时刷新」因此从「唯一的可见时机」退为「最后一次刷新」——两者不冲突，
  只是前者不再是唯一入口；半成品文档的异常/诊断态是过程态，FR-17 的结束校验
  仍是权威判定（取舍写在 `FR-42` 正文）。
  会话运行期间白板自身的写动作不加锁——由上述 compare-and-swap 兜底。

## 7. API 契约

```
GET  /api/graph                       → {nodes, edges, issues, diagnostics, idOwners}   # diagnostics: 解析诊断（FR-40），行含 {docId, kind, line?, text}；idOwners: 可解析 id → 所属文档 id（第十五轮，FR-57，见下）
GET  /api/docs/:id                    → {content, hash}            # 整文件原文，front matter 可改
PUT  /api/docs/:id                    {content, baseHash}          → 200 {committed, error?} | 409 冲突
GET  /api/docs/:id/transitions        → [status]                   # 合法目标状态（FR-6）
POST /api/docs/:id/status             {to}                         → 200 {committed, error?} | 422 {error, gaps?: [<item-id | unresolved-id>]} 非法流转/resolved 门拒绝（FR-52：plan open→resolved 时按交付范围守门，缺口以 gaps 逐条点名，文件不变；非门拒绝无 gaps 字段）
POST /api/docs/:id/review             {action: accept}             → 200 {committed, error?} | 422   # clarify 分支第八轮移除（decision-00006），非 accept 一律 422
GET  /api/docs/:id/next-steps         → [{type, carry}]
GET  /api/sessions                    → {sessions: [{id, kind, sourceId, agent, status, awaiting, startedAt, endedAt?, exitCode?}]}   # 全部会话：运行中 + 本次服务启动以来已结束——会话面板与重连发现的数据源（FR-21、spec-00003-FR-4/FR-9；第十六轮由 {current|null} 改列表）。sourceId 即目标文档 id（沿 SessionInfo 既有字段名，落地时对齐）。status ∈ running|exited|failed|terminated——terminated 第十六轮新增，承载面板与历史的「终止」态（spec-00003-FR-4）；上限只随 /api/config 下发，不在此重复（FR-56 的单一来源原则）
POST /api/sessions                    {sourceId, targetType, agent?} → {sessionId} | 409 {error, reason: doc-busy|cap-reached|doc-missing} | 422 未知 agent（FR-55）   # 409 的 reason 四个发起端点同形（spec-00003-FR-2/FR-3 的「原因可区分」由它承载）；同文档互斥与上限并存时取 doc-busy（更具体者，spec FR-49 的悬停文案同序）   # 推进会话；任务指令正文单独写入（不带提交字节），提交键为会话首批输出后延迟发出的独立 `\r`（再延迟补发一次；空输入框回车幂等）——同一突发里的 `\r` 会被 cooked 模式的 ICRNL 翻回 LF 或被粘贴检测吞掉（issue-00011）
POST /api/sessions/clarify            {docId, agent?}              → {sessionId} | 409 同文档已有会话/已达上限/文档已删 | 422 非 draft/非可澄清类型/未知 agent   # 澄清会话（FR-9，第八轮；agent 第十一轮；并发 409 第十六轮）
POST /api/sessions/ask                {docId, question, agent?, threadId?, resend?} → {sessionId, threadId} | 409 {error, reason: thread-busy|cap-reached|doc-missing}（thread-busy 优先，同 sessions 行「更具体者先」的约定） | 422 异常文档/未知 agent/agent 未声明 headless/问题为空/threadId 不存在/resend 无可重发（客户端错误而非状态碰撞，故不入 409 三因——T3 据实补记）。resend=true 才就地改写末条未答 exchange；缺省追加——新追问与重发在 API 上显式区分，否则失败线程上的新问题会覆盖旧问的记录（T3 评审补记，「只增不删计的是问」由此机械成立）   # 答疑线程调用（spec-00005-FR-1/FR-2/FR-7，第二十一轮改造；原终端答疑形态 FR-47 退役）。无 threadId = 新线程（headless 首调）；带 threadId = 该线程追问或失败/终止问的重发——形态按 §10.2（有 resumeId 走 resume，无则 first）。**无 doc-busy 分支**——答疑不占文档（spec-00005-FR-6）；agent 缺省 = 声明了 headless 的第一条（FR-55 口径按 spec-00005-FR-2 收窄）
GET  /api/asks/:id                    → {threads}                   # 问题列表（spec-00005-FR-5/FR-9 的数据源）：即 .whiteboard/asks/<docId>.json 的存储原样——running 在开笔时即落盘、收尾回填、启动核销，注册表无可再合成（T3 据实校正，原「与注册表运行态合成」）；exchange 带 runSessionId（§10.3 反查）；无列表时 {threads: []}。id 形态先校验（沿会话历史的文件名守卫口径）再作路径；不挂 /api/docs/:id/ 下——列表脱离文档存续（文档删除后仍可寻址），与 /api/create 避开路由重叠同例
POST /api/sessions/audit              {docId, agent?}              → {sessionId} | 409 同文档已有会话/已达上限/文档已删 | 422 非 draft/非可审计类型/异常文档/未知 agent   # 审计会话（FR-50/FR-51，第十轮）
GET  /api/create?type=<t>             → {idPrefix, template} | 422 非入口类型   # 新建预填：取号 + 模板，不写盘（FR-53；独立路径避开 /api/docs/:id 的路由重叠）
POST /api/docs                        {id, content}                → 201 {committed} | 409 id 已存在 | 422 非入口类型/id 不合分配前缀或 slug 非法   # 新建的保存（FR-53，第十一轮）：保存才建档——写盘 + commit wb(create)，与 FR-4/FR-5 同一条写管道的创建分支；此后修订走既有 PUT
GET  /api/sessions/history            → [{id, kind, docId, agent, startedAt, endedAt, status, exitCode?}]   # 历史会话列表（FR-54，第十一轮），读 .whiteboard/sessions/；status/exitCode 沿用会话状态词汇（exited/failed/terminated——第十六轮增，元数据落盘时记下终止，重启后「退出状态」仍如实呈现）
GET  /api/sessions/history/:id        → {meta, transcript}         # 单条元数据 + 转写全文（FR-54；meta 读 <会话 id>.json，transcript 读 <会话 id>.log）
DELETE /api/sessions/:id              → 200 | 404 该会话不存在或非运行中（已 exited/failed——重复终止同 404，不二次 commit；逐会话判定，spec-00003-FR-5）   # 终止指定会话（FR-49，issue-00010；第十六轮加会话标识，原无标识形态废止）；退出收尾照常、恰一次；信号升级 SIGHUP→宽限→SIGKILL（issue-00012），等待因此有界。ask 会话同端点同语义，升级阶梯为第二 seam 自持的 SIGTERM→宽限→SIGKILL（§10.3，第二十一轮）
WS   /api/terminal?sessionId=<id>     双向。文本帧 = stdin 原样字节；二进制帧 = JSON 控制（现仅 {cols, rows} 尺寸帧：前端 fit 后与面板变化时上报，服务端调 pty.resize；非法控制帧忽略不断连——FR-12/issue-00009）；服务端→前端仍为 stdout 文本帧 + exit 事件。（本行原写作 /api/sessions/:id/term，与实现不符，第七轮据实校正；第十六轮加 sessionId——终端接入指定会话，尺寸帧只随呈现中的会话连接到达，未呈现的会话自然无帧，spec-00003-FR-5）
WS   /api/events                      服务端→前端：无载荷信号，收到即刷新（重取 graph + 当前 items + 会话状态；FR-42/FR-43）。三个来源：docs/ 变更（watcher），会话收尾——**无论有无 commit**（FR-12/issue-00013，触发源由此真正共用一条通路），以及等待标志的翻转（onAwaitingChange → watcher.signal，只在标志真变时广播——重复信号因此不重播，spec-00003-FR-6；第十八轮据实补记，spec-00004-FR-2 的「置位经刷新到达页面」依赖它）。同批多会话收尾只广播一次（spec-00003-FR-8，第十六轮）
GET  /api/config                      → 生效的流程配置（只读）+ 代码内建的可澄清/可审计类型集（FR-56，第十一轮：前端入口呈现的单一来源，不再自持副本）；entry 列表随配置下发（FR-53）；max_sessions 随配置下发（spec-00003-FR-4 的「运行中数/上限」，第十六轮）；agents 第二十六轮起为**有效 agent 列表**（§13）——每项 {name, headless: boolean, source: project|local|overridden, default?: boolean}——被禁用者**不在其中**（`spec-00009-AC-3.8`；禁用态只经 /api/settings/agents 可见），每次请求重新计算；另带 agentSettings: {error?: {message, at?}, notices: [{name, message}]}（本地层不合式的原因 / 无所指的单条）
GET  /api/settings/agents             → {project: [<项目层条目全量，含 model/env/args/headless>], local: <本地层文件原样 | null>, effective: <同 /api/config 的 agents>, captures: [<代码内建的 capture 名集合，今天 ['claude-json']>], error?, notices}   # 设置面板的数据源（spec-00009-FR-7），第二十六轮；captures 供 headless.capture 下拉——不进 /api/config，FR-56 的两个内建集不增第三个
PUT  /api/settings/agents             <本地层文件全量>             → 200 {effective, notices} | 422 {error, at}（合并校验不过，不写盘，spec-00009-FR-6）| 500 {error}（写盘失败——临时文件 + rename，磐上无半写文件）   # 保存（spec-00009-FR-5）；返回即已生效——下一次受理读的就是这份
GET  /api/docs/:id/items              → {items, diagnostics}        # 需求条目：id、正文、AC（含 GWT 文本）、验收行、覆盖三态（FR-31…FR-33）；diagnostics 吸收原 unattributed（FR-40），子画布同源复用（FR-35），无第二个端点
GET  /api/coverage                    → [{docId, title, verified, failing, uncovered, items: [{id, coverage}]}]   # 全局覆盖率视图（spec-00002-FR-10/FR-11，治理轮）：全仓每份 spec/rule 一行，三态计数 + 逐条目覆盖；不区分文档 status，撞 id 的文档不在列；无可列文档时为 []
```

`/items` 的载荷字段是 T1 与后续任务并行时的共同事实：验收行对象至少含
`{recordId, targetId, test, result, evidence?}`（FR-34 按它找引用条目 AC 的
record，FR-35 的验收行节点由它构造，FR-37 的详情面板读 `evidence`——清单表
无 Evidence 列时缺省）；`diagnostics` 行含 `{kind, recordId?, declaredId?,
line?, text?}`——无法归属（原 `unattributed`，FR-33）与文法诊断（FR-40）
共用此形。

`GET /api/graph` 的 edge 随 FR-2 修订增加 `declaredTargets`——front matter 所
声明的原始 id **列表**；细粒度引用时它们与 `target`（所属文档）不同。同一字段
的多个值落到同一文档时合并为一条边（FR-28 合并规则的延伸，AC-28.5），关系列表
（FR-30）按 `declaredTargets` 逐项展开。

**第十五轮增补（行内 id 跳转，`spec-00001-FR-57`…`FR-59`）**：graph 载荷新增
`idOwners`——「可解析 id → 所属文档 id」一张表，前端可点击判定的唯一依据。
构建：每个 ok 节点的文档 id 自映射（按**节点键**取——撞 id 节点的键是路径，
其 id 因目标歧义不入表，`spec-00002-FR-8`；构建不得经 `declaredId()`，那会
复活撞 id）；ok 且有条目文法的节点，其全部条目与 AC id 映射到所属文档 id
（与 `itemOwners` 同源的构建逻辑——该函数今日是 docRepository 模块私有、
不在任何载荷；异常文档的条目按归属的既有排除不入表）。规模 = 全仓文档、
条目与 AC 的 id 总数——本仓量级下是几百个短字符串，随 graph 一次下发即可，
不另设端点；已有的 `declaredTargets` 只覆盖被 front matter 引用过的 id，
覆盖不了散文引用面，不足以承载。该表只服务呈现层，不参与边与诊断的推导
（`spec-00001-AC-59.1` 的守卫）。

**治理轮（spec-00002）对上述载荷的增补，逐项如下**（以下四段内不带前缀的
`FR-n` / `AC-n.m` 一律指 `spec-00002`）。

`/api/coverage` **把逐条目状态折进同一次调用**，而不是「展开时再取一份」，理由
有三：三态计数本来就是逐条目覆盖数出来的——服务端为了给出计数必须先算出每个
条目的状态，`items` 因此是白拿的，拆成第二个端点等于把同一次推导做两遍；计数
与展开行来自同一次快照，二者不可能对不上（与「门与 `/items` 永不相异」同一
理由）；展开态是纯呈现状态（`FR-11` 按文档 id 跨刷新保持），本就不需要往返。
载荷代价可忽略——每条目只有 id 与三态之一，本仓最大的 spec 也只有几十条。行内
的 `items` 只带 `{id, coverage}`，**不带正文与 AC**：`FR-11` 只要 id 与状态，
读正文走检视面板与子画布的 `/items`。该端点与 `/items`、`graph.diagnostics`
共用 §2 所定的同一份「全部 record 为证据集」的推导与缓存，这也是 `spec-00002`
§7 那条非功能项的落点。

`GET /api/graph` 的两个数组现在各自还是一份**下钻清单**的数据源（`FR-13`、
`FR-14`），只增一个字段：`issues` 行增加 `nodeId`——该条异常所定位到的节点键，
边的异常取**声明方**节点（`FR-13`/`FR-15`、`AC-13.4`、`AC-15.4`）。`path` 始终
呈现（`FR-13` 的「来源」），旁边再显示哪个 id，**分两种情形**——早先写的
「`nodeId !== path` 即有 id」这条单一判据**对撞 id 的节点是错的**（它们的
`nodeId` 恰恰等于 `path`，却明明有一个 id 要给人看），故改为：
- `nodeId !== path`：该文件解析出了可用的文档 id，显示 `nodeId`；
- `nodeId === path` 且节点带 `duplicateOf`：显示 `duplicateOf`——**撞的那个 id
  正是这条异常的内容**，不显示它，清单就只剩两行长得一样的路径（`FR-13` 要
  「来源」可辨，`FR-8` 要那个 id 看得见）；
- `nodeId === path` 且无 `duplicateOf`：该文件根本没有 id，只显示路径。

判据仍然只读已有字段，不需要第三个新字段。
`diagnostics` 行**无需增补**：`docId` 取的就是节点键，`FR-15` 的定位直接可用；
新增的 `relation-field` 只是 `kind` 的一个新值（§2）。节点侧新增
`duplicateOf?`——撞 id 时那个撞的 id，节点标签与命令面板要它（§2、design-00002
§4）；不撞 id 时字段缺席。

**两道新门的拒绝沿用既有的 422 形**：`POST /api/docs/:id/status` 的
`422 {error}`，**不带 `gaps`**——`gaps` 仍是 resolved 门专有的判别标志
（`web/src/api.ts` 靠它区分两种 422）。促进门的 `error` 点名该文档有未决 Open
Questions（`AC-1.3`），归档门的 `error` 说明缺少列出该 id 的 `supersedes` 配对
（`AC-3.2`）。按撞的 id 寻址的写入沿用 `409`（`ConflictError`），消息要求先修复
id 冲突（`FR-9` a、`AC-9.2`）。

白板的 commit 一律带 `--no-verify`（第十一轮，decision-00008 §2 第 6 条）：
pre-commit hook 的受众是人手提交，白板按 spec 提交 draft 产物（FR-17 的推进
半成品、FR-53 的新建）不受其拦截，评审保证由白板自身的门承载。

commit 信息格式：`wb(<action>): <doc-id>`，action ∈
`edit | status | accept | clarify | advance | ask | audit | create | cowrite`（spec FR-14 的"指明
动作与文档 id"——AC-14.x 的中文动作词「澄清/答疑」由这些英文 key 承载，
与既有「接收=accept」同一约定；`clarify` 第八轮起是会话 commit，不再是评审
写回；`audit` 第十轮加入，其 commit 由 spec AC-50.3 承载；`ask` 第二十一轮
起不再产生——答疑无 commit（spec-00005-FR-4，`spec-00001-FR-14` 的答疑
半句属其修订轮移除），动作词保留以读历史）。

前端的呈现与交互（着色、检索与定位、面板、控件）见
[design-00002-whiteboard-ui](design-00002-whiteboard-ui.md)；其中检索与定位由
spec-00001-FR-26、FR-27 承接。

## 8. 代码位置与运行

- 代码放 `tools/whiteboard/`（独立 package.json，不影响模板本体——仓库
  根**没有** package.json）。
- 运行：在 `tools/whiteboard/` 下 `npm run build`（构建 UI 到 dist）后
  `npm start`（默认端口 4173），读取仓库根的 `./docs` 与
  `./whiteboard.config.yaml`。（本节原写作「仓库根部 npm run
  whiteboard」，与现实不符，据实校正——命令表以 tools/whiteboard/README
  为准。）

## 9. 治理轮的两处裁定余项（已裁，非未决）

- **启动校验不要求 `relations` 声明 `supersedes`（治理轮裁定）。** 归档门（§2）
  读的是 `DocNode.relations.supersedes`，该键只在流程配置的 `relations` 列出它
  时才存在；一份漏掉它的配置会让白板照常启动，而**一切归档永远找不到配对**。
  裁定：这是配置层的自担选择——`relations` 本就是项目自定的字段词表，删掉任何
  字段都会关掉依赖它的能力，`supersedes` 不特殊；`spec-00002-FR-6` 的校验集不
  扩。模板自带配置始终列全八个字段，正常项目不会踩到。
- **`graphDiagnostics` 的 `ok` 过滤保持现状（治理轮裁定）。** 本轮出现一处口径
  不齐：`/coverage` 服务「front matter 异常但正文可解析」的文档
  （`spec-00002-FR-10` 明写），而文法诊断对同一批文档不产出
  （`spec-00001-FR-40` 的现行行为，§2 已钉住不得顺手统一）。于是这些文档在
  覆盖率视图里有计数，却拿不到解释计数的诊断。裁定：按现行行为保留——修复
  front matter 是第一动作，诊断随修复自然出现；放宽属 `spec-00001-FR-40` 自己
  的修订轮，本轮不做。

**已有归宿的实现项**：`web/src/api.ts` 那七个未编码的 `/api/docs/:id`
调用点（§2）已归 `issue-00016` 与 `plan-00012` T4；从 `spec-00001` §6 移除被
`spec-00002` 覆盖的两条范围外事项、以及把「id 唯一」写进 `docs/README.md`，
已由 `spec-00002` §1 指给 plan 轮。

## 10. 答疑线程——headless 通路（第二十一轮）

承载 `spec-00005` 的服务端侧；取舍全部在案于 `decision-00012`。答疑不再
起 PTY：一次调用是一个被捕获输出的子进程，一个问题是一条独立会话，
追问 resume 它。界面侧见 design-00002 §14。（第二十三轮增第三个发起
来源：标注统一提交的 question 通路，其首调指令的选区材料拼装点见
§12.5。）

### 10.1 headless 声明（流程配置扩展，spec-00005-FR-8）

`agents` 条目新增**可选**键 `headless`，示例（写进模板自带配置前须过
10.2 的实测门）：

```yaml
agents:
  claude:
    command: claude
    args: []
    cwd: docs
    headless:
      first:  [-p, --output-format, json, --permission-mode, plan, "{question}"]
      resume: [-p, --output-format, json, --permission-mode, plan, --resume, "{session}", "{question}"]
      capture: claude-json
```

**上面示例的全部 claude 参数是意向而非已验证事实**（与 §3 写权限约束
同一纪律）。进入模板自带配置前须实测四项，任一不成立即改声明或改
capture 内建：① `-p --output-format json` 的 stdout 是单个 JSON 对象；
② 回答与接续标识的字段名确为 `.result` 与 `.session_id`；③ `--resume`
接受该值并延续上下文；④ 只读成立——指令要求 agent 改一个 `docs/`
文件，调用结束后文件不变（`spec-00005-AC-4.2` 的实测形）。未通过不进
默认配置。

- 校验（并入既有 FR-15 启动校验，违规拒绝启动并点名条目，
  `spec-00005-AC-8.1`）：`first` 与 `resume` 皆为非空字符串数组；
  `{question}` 占位在两者中各恰出现一次；`{session}` 在 `resume` 中恰
  出现一次、在 `first` 中不得出现；`capture` 必须是代码内建集合中的
  名字（与可澄清类型集同一「代码内建、配置引用」模式）。`headless`
  缺失 = 该 agent 不进答疑可选集（不校验其余键）。
- **capture 口径**（接续标识与回答从哪来，spec-00005 §5 委给本节）：
  内建集合现只有 `claude-json`——回答 = `.result`（纯文本，无控制
  序列——`spec-00005-FR-3` 的剥离由 capture 层承担：`claude-json`
  天然满足，将来的文本型 capture 在此层剥离），接续标识 =
  `.session_id`。stdout 解析失败视同失败态（非零退出同型处置）。
- **只读旗标（spec-00005-FR-4/AC-4.2）**：claude 取
  `--permission-mode plan`——预期机制是 print 模式下无人可批准写盘、
  计划模式的写申请无从放行；该预期属上方实测门第 ④ 项，不作既成
  事实引用。
- **`{question}` 携带什么（spec-00005-FR-1/FR-2 的落点）**：**首调**
  替换为「答疑指令 + 问题文本」整段——指令由 `sessionTasks` 的
  `askInstruction` 改写而来：保留目标文档路径与全部关系文档路径的
  上下文行，性质说明改为只读（删去 "Revise documents…" 半句，代之以
  「回答问题，不修改任何文件」），问题文本附于其后；**接续**只替换为
  追问文本（`spec-00005-AC-2.1`）。`SessionPlan.instruction` 对 ask
  的语义随之是「argv 载荷」而非「首笔 PTY 输入」。
- 命令构造：`command` + headless 声明数组逐项做占位替换后 spawn——
  **不拼接条目的 `args`**（那是交互形态的参数集，交互旗标误入 print
  调用逐 CLI 后果不明，headless 声明自持完整旗标）。**第二十六轮**：
  占位集增 `{model}`，与 `{question}` / `{session}` 在 `headlessArgs` 的
  **同一遍**正则里替换（一遍而非两遍的理由不变：问题正文里若出现
  `{model}` 字样不得被再次替换）；`SpawnHeadless` 增第四参 `env`，与 §5
  的 pty seam 同一合成方式。条目从会话里存的快照取（§5，今天已如此）。spawn 走与
  `SpawnPty` 并列的**第二个注入 seam**（`child_process`，非 pty；
  `cwd` 沿用条目的 `cwd`），其 kill 升级自持（§10.3）。整段指令作为
  单个 argv 元素传入，不经 shell——无转义面。

### 10.2 问题列表存储（spec-00005-FR-5）

- 位置：`.whiteboard/asks/<docId>.json`（与 `.whiteboard/sessions/` 的
  会话历史同侧，仓库 `.gitignore` 既有的 `.whiteboard/` 排除覆盖之，
  `spec-00005-AC-5.2`）。
- 形态（一文档一文件；`resumeId` 是 **CLI 的接续标识**——不叫
  `sessionId`，那个词全文属注册表会话）：

  ```json
  {
    "docId": "spec-00005-whiteboard-ask-threads",
    "threads": [
      {
        "id": "t-<取号顺序号>",
        "agent": "claude",
        "resumeId": "<capture 出的接续标识，首答成功后回填>",
        "exchanges": [
          { "question": "…", "askedAt": "<ISO>",
            "answer": "…", "answeredAt": "<ISO>",
            "outcome": "answered",
            "runSessionId": "<该次调用的注册表会话 id>",
            "reason": "<仅失败/终止落：is_error 的 .result，其次 stderr 末行，其次 exit <code>；重发成功即清——T4 评审补记，进程侧 exited/0 保持如实，线程侧自带失败原因>" }
        ]
      }
    ]
  }
  ```

  `outcome ∈ running | answered | failed | terminated`，exchange 的
  生命周期：

  ```mermaid
  stateDiagram-v2
    [*] --> running: 受理通过，先落盘再 spawn
    running --> answered: 零退出且 capture 出回答
    running --> failed: 非零退出 / stdout 解析失败 / 启动核销
    running --> terminated: 面板终止 / 服务正常关停
    failed --> running: 重发（就地改写该条）
    terminated --> running: 重发（就地改写该条）
  ```

- **写序（受理先于落盘，落盘先于 spawn）**：受理链 = 文档校验 → 线程
  串行判定（该 threadId 有 `running` exchange 即拒） → 上限记账；
  **全部通过后**才以 `running` 追加（或就地改写）exchange，随后
  spawn——受理拒绝不碰文件（`spec-00005-AC-6.4`），而落盘先于 spawn
  是启动核销的前提：崩溃时内存里的记录没有意义。`runSessionId` 在开笔
  时即写入——调用运行中面板与通知就要反查线程（§10.3；T3 据实校正，原
  列于回填集）；结束时回填 `answer/answeredAt/outcome`；`resumeId`
  由**每次已答调用刷新为最新值**（latest-wins；T3 据实校正，原写「首答
  回填」）——fork 型 CLI 的每次 resume 打印会发新 session id，锁死首个
  会把追问接回追问之前的对话。
- **写串行**：同 docId 的一切读-改-写（提交追加、收尾回填、启动核销）
  与 threadId 取号走**逐 docId 一条串行队列**（与 §4 的 commit 串行
  队列同型、彼此独立——commit 队列本轮明文不管答疑，§10.3）；写盘经
  临时文件 + rename 原子替换。无此队列，同文档并行线程（
  `spec-00005-AC-6.3` 明文要求）的两次收尾会互相吞写。
- **只增不删计的是「问」**（`spec-00005-FR-3`）：失败/终止问的重发
  **就地改写同一条 exchange** 的字段，不新增不删除——失败问无回答
  可丢，`spec-00005-AC-7.5` 的「既有问答完好」指其余各条。就地改写
  只发生在**显式 `resend`** 上（§7）；不带该标志的提交一律追加——
  末条未答时的新追问不吞旧问（T3 评审补记）。
- **重发的形态分两种**：线程尚无 `resumeId`（首问失败/终止）→ first
  形态、新会话；线程已有 `resumeId` 的追问重发 → resume 形态，接续
  保留（一律开 first 会切断线程上下文，违 `spec-00005-FR-2`）。
  **CLI 拒绝既有 `resumeId` 时（域主裁定，2026-08-26，原 Open
  Questions 一节——现 §12——的 OQ-1 取 (b)）**：该追问记失败态、可重发（`spec-00005-FR-7` 照旧
  ——重发仍以该 `resumeId` 走 resume 再试，**不静默换新会话**），
  线程同时标注「接续已失效」（存储字段 `resumeInvalid`）；失效线程禁
  的是**新追问**（界面侧引导另开新问，design-00002 §14），一次重发
  成功即清除标注。标注的判据是「resume 形态的调用以失败结束」——CLI
  的拒绝无从与其他失败机械区分，而两者的处置（失败态、原形态可重发）
  相同，差别只在这个界面提示（T3 据实补记）。
- **启动核销（spec-00005-AC-5.3）**：服务启动扫一遍 `asks/` 目录，
  `running` 的 exchange 一律改写为 `failed`——注册表空态起步
  （`spec-00003-FR-9`），磁盘上不许有幽灵进行中。
- **正常关停（spec-00005-AC-5.4）**：既有关停路径（§5 关停收尾）对
  ask 会话同样逐个终止，exchange 记 `terminated`；历史照落，无 commit。
- 文档删除或改 id：文件保留、不回收；graph 上无该 id 时前端自然无处
  呈现（`spec-00005-FR-5`，回收属范围外）。追问与重发按 threadId
  寻址文件内的线程。

### 10.3 注册表第二形态（spec-00005-FR-6/FR-7）

kind=ask 的会话进同一注册表，差异逐条：

- **受理**：跳过「目标文档无运行中会话」检查，且**非 ask 的受理在数
  该文档运行中会话时忽略 ask 会话**（两个方向都不占，
  `spec-00005-AC-6.1`/`AC-6.2`）；总上限照记账（`spec-00003-FR-3`）；
  线程内串行由存储层判定（该 threadId 有 `running` exchange 即 409）。
  受理通过、落盘之后若再有抛出（第二 seam 之前的任何一步），回滚
  要做两件事：注册表侧核销该会话（failed、还槽），存储侧把刚落的
  exchange 落成 `failed` 带原因（`resumed` 记 false——没有 CLI 拒绝
  过任何接续标识，不得标失效）——只核销会话会把 `running` 留在盘上
  堵死该线程直到重启（T4 评审补记）。
- **等待判定**：两通路均不武装（不设静默计时、不做 OSC 777 识别）——
  `awaiting` 恒缺席（`spec-00005-AC-6.5`）。
- **终端**：无缓冲、无 attach——`WS /api/terminal?sessionId=<ask>` 拒绝
  连接（`spec-00005-AC-7.7` 的接入/输入/尺寸三拒此一处全断：输入与
  尺寸帧只在该 WS 上存在）。
- **终止**：`DELETE /api/sessions/:id` 照常——对第二 seam 的子进程走
  自持的信号升级（SIGTERM→宽限→SIGKILL，issue-00012 的机制平移；无
  SIGHUP 语义——那是 pty seam 的阶梯），exchange 记 `terminated`
  （`spec-00005-AC-7.6`）。
- **收尾**（exit/终止/失败，恰一次——§5 的「`pty.onExit` 只触发一次」
  对 ask 读作第二 seam 的结束回调，保证同型；结束事件取 `close` 而非
  `exit`——exit 时管道未排空，大回答会被截断误判失败；`close` 之外另
  设自 `exit` 起的有界排空窗（量级 1s）兜底：CLI 把 stdout 留给自己的
  子进程时 `close` 永不到来，终止与关停不得因此悬死。启动失败——命令
  不存在等——读作退出码 1 的普通失败调用，原因落 stderr，不设第四种
  终局。均 T3 据实补记）：capture 解析 → 存储
  回填 → 历史落盘（`.json` 元数据照旧；`.log` 转写 = 捕获的回答文本，
  无可解析回答时 = 捕获的 stdout/stderr 原文，`spec-00005-AC-5.5`）→
  **无快照、无 commit、不进 commit 串行队列**（`spec-00005-FR-4`）→
  `/api/events` 广播照常。
- **刷新重取扩一项**（§6 与 design-00002 §10 的重取清单）：问题列表
  视图**或标注列表**打开期间，前端刷新一并重取 `GET /api/asks/:id`；
  两者都未打开不取——与覆盖率视图同一口径。无此项，`running → answered`
  的翻转永远到不了页面（`spec-00005-AC-3.3` 依赖它）。**后一半是第二十三
  轮增的**：标注列表的 question 项状态由前端按 `threadId` 从这份载荷现算
  （§12.1），用户停在标注列表这一视图态时若不取，那些项的状态会停在打开
  那一刻。
- **会话与线程的反查**：`/api/sessions` 载荷不加字段（kind=ask 即
  headless 形态，终端答疑已退役）；面板行与通知只持注册表会话 id，
  前端由 `sourceId` 取 `GET /api/asks/:id`、按 exchange 的
  `runSessionId` 反查线程——design-00002 §14 的定位由此可实现。

## 11. 共写通路（第二十二轮）

承载 `spec-00006` 的服务端侧；取舍全部在案于 `decision-00015`。共写是
**第五种会话种类、第四种交互式终端形态**：与推进/澄清/审计同走 PTY seam、
同一注册表与并发正则、同一等待判定与终止阶梯、同一历史落盘；差别只在
受理裁决、任务指令、与收束的**域过滤**。界面侧见 design-00002 §15。
（第二十三轮增第二个发起来源：标注统一提交的 issue 通路——程序化材料、
自动流转与全有或全无的实现次序见 §12.4，本节的受理与收束原样适用。）

### 11.1 受理与任务指令

- **受理裁决**（Workflow Engine）：状态合法性按 `rule-00001-BR-29`——
  `draft` 文档、或 `open` 的 work item；非法即 422 并说明原因
  （`spec-00006-FR-9`；入口不按 status 条件化，拒绝在受理处）。异常节点
  422（前端本就不提供入口，服务端兜底）；文档已删 409 `doc-missing`
  （`spec-00001-FR-19` 的拒绝枚举增共写发起，`spec-00006` §1 交接）；
  同文档互斥与上限照 `spec-00003-FR-1`…`FR-3`（其种类枚举增共写，
  `spec-00006` §1 交接；409 `doc-busy` / `cap-reached`，更具体者先的
  既有约定）。
- **启动形态即只读约束的第一半**（`spec-00006-FR-7` 的落点）：共写以
  条目的**交互式** `command`/`args`/`cwd` 原样 spawn，**不追加任何
  权限旁路旗标**（与 §10.1 headless 显式声明只读旗标互为对照——共写
  恰恰要保留 CLI 自身的权限机制，询问与否由该 CLI 决定）；权限询问
  与拒绝是普通 PTY 输出，服务端不识别、不代答、不压掉；一次拒绝后
  会话照常存续（无任何服务端处置挂在它上面）。
- **任务指令**（首笔 PTY 输入，机制同 §4——延迟提交键、issue-00011 的
  口径沿用）：目标文档路径与类型、该类型文件夹 `README.md` 路径、目标
  类型的条目文法段（有文法的类型，`spec-00001-FR-41` 复用）、**写域
  约束声明**（只许写目标文档与新建 `docs/reference/` 文档，目标文档
  front matter 的 `id`/`status` 不得动——声明是第一道约束，收束过滤是
  执行层）、**reference 新建要件**（`docs/reference/TEMPLATE.md` 与
  `README.md` 路径、`reference-<五位数>-<slug>` 取号规则与当前空闲
  起始号、初始 `status: draft`、落规范路径 `docs/reference/<id>.md`
  ——不给这些，收束过滤会把不合式的产出删掉，`rule-00001-BR-30`）、
  **材料段**（见下）、**蒸馏要求**（外部材料中支撑结论的内容写进正文
  或落成 `reference`，`rule-00001-BR-28`）。
- **材料段**（`spec-00006-FR-3`）：发起载荷的可选 `materials`
  ——`{text?, docIds?, paths?, urls?}`——逐项拼进任务指令：粘贴文本
  原样成段；仓内文档 id 换算为仓内路径行；仓外绝对路径与 URL 原样列出
  并注明「读取须经你的权限机制向用户申请」。materials 缺省即无材料段。
  会话运行中的补充材料就是终端输入，无专门机制。

### 11.2 API 契约增补

```
POST /api/sessions/cowrite   {docId, agent?, materials?}
POST /api/sessions/cowrite   {create: {type, slug}, agent?, materials?}
  → {sessionId, docId, error?} | 409 {error, reason: doc-busy|cap-reached|doc-missing|id 已存在} | 422 状态不合法（BR-29）/异常文档/非入口类型/slug 非法/未知 agent
  # error? 是 create 形建档 commit 失败的呈现通道（spec-00001-FR-20：文件保留、
  # 会话照常接续）——T3 据实校正，本行原无该字段。
```

- **一个端点两个形态**（`docId` 与 `create` 互斥，二者皆无或皆有即
  422）：既有形对一份在盘文档发起；create 形是 `spec-00006-FR-2` 的
  落点。不另立 `/api/create-cowrite`——create 家族的既有分立
  （`GET /api/create` + `POST /api/docs`）各有路由重叠的理由（§7），
  此处没有，发起共写就是 `sessions/cowrite` 的事。
- **create 形的受理次序（先占槽再建档，域主裁定）**：一次受理里串行
  完成——`spec-00001-FR-53` 的三项拒绝（id 已存在、slug 不合式、类型
  不在 `entry`）、agent 校验、并发上限记账（占槽），**全部通过后**才
  按该类型 `TEMPLATE.md` 写盘（front matter 的 id/type/status 预填，
  同 `GET /api/create` 的预填逻辑；预填内容须声明该 id——`create()` 的
  既有防异常门复用，评审轮据实补记）、commit `wb(create): <id>`、spawn
  会话。目标类型为 `reference` 时，指令的 reference 起始空闲号计入目标
  自己刚取的号（否则 agent 的第一份 reference 必然撞号，评审轮据实
  校正）。任何一步拒绝即整体拒绝、不建档、释放已占槽位——无孤儿文档，
  契约保持全有或全无。既有 `GET /api/create` 与 `POST /api/docs`
  （空白模式）原样不动。
- 会话载荷、`/api/sessions`、历史、终止、终端 WS 全部沿用既有形——
  kind 词汇加 `cowrite`。

### 11.3 收束过滤（`spec-00006-FR-6`，`rule-00001-BR-30` 的执行层）

- **快照扩为内容快照（仅共写）**：既有会话前快照记「脏路径 + 内容
  摘要」（§4），滤除要**复原**，摘要不够——共写会话启动时对 `docs/`
  当时已脏的每个路径另存**全文**（内存持有；上界 = 会话启动时刻的脏
  文件数 × 文本体量 × 并行共写数，不随会话增长，收尾或失败即释放；
  `CONTEXT.md`「会话前快照」词条的扩义随 `spec-00006` §1 变更集
  登记）。干净路径不用存：HEAD 即复原依据。
- **过滤在串行队列的临界区内**：快照差集、过滤、暂存、commit 是队列
  里的**一个原子回合**（§4 的同一条队列）——排在前面的用户动作 commit
  先完成，其写入因此在过滤时已是干净路径，不会被误复原。
- **收尾序列**（历史落盘 → 过滤 → commit）：过滤走的路径集 = git 现时
  脏差集 ∪ 内容快照中盘上内容已偏离快照值的路径（后一半接住「agent 把
  域外文件改回 HEAD 原文」的情形——git 视其为干净、快照知道它动过，
  评审轮据实校正），对其中每个路径分类处置——

  ```mermaid
  flowchart TB
    P[快照差集中的路径] --> T{是目标文档?}
    T -->|是| G{id/status 行<br/>与会话前一致?}
    G -->|是| ST[暂存]
    G -->|否| RG[改回会话前值<br/>正文保留 → 暂存]
    T -->|否| R{docs/reference/ 下<br/>的新建文件?}
    R -->|是| W{合式?}
    W -->|是| ST
    W -->|否| DEL[删除（复原）]
    R -->|否| EX{豁免?<br/>其他运行中会话的<br/>认领路径 /<br/>白板写管道的<br/>未竟提交}
    EX -->|是| SKIP[不动·不暂存]
    EX -->|否| RV[复原：快照全文 /<br/>git checkout /<br/>新文件删除]
  ```

  1. **目标文档**：暂存；先做 front matter 守卫——新内容的 `id` 或
     `status` 行与会话前不一致时，原地改回会话前值再暂存（正文改动
     保留，`spec-00006-AC-6.4`）。**收束时目标文件已不在盘上**：删除
     **不暂存**（删除不是 BR-30 授权的落地写入）、工作区保持现状，
     该情形记为发现、由既有异常与诊断体系承接
     （`spec-00001-FR-17`/`spec-00001-FR-40` 口径）。
  2. **`docs/reference/` 下的新建文件**：过**合式**判定——front
     matter 可解析、`type: reference`、`status: draft`、文件在规范
     路径 `docs/reference/<id>.md` 且文件名与 id 一致、id 形态合法。
     （第二十七轮：写进流程配置 `exclude` 命中路径的候选，白板读不出文档、
     按本款判不合式并删除——域主裁定沿用，§14.3。）
     **取号按集合判，不逐文件判**（`rule-00001-BR-18` 是「现有最大
     编号加一」，逐文件读则第二份永远不合式）：本会话的全部新建
     reference 的编号，须两两不同、且自「收束时点既有 reference 的
     最大编号 + 1」起连续——「既有」不含本会话自己的新建，含注册表
     保留集中的号（保留集今日只装推进目标号、`reference` 不是任何
     flow 的 next，故该款常空，留给把 `reference` 列进 flow 的配置）。
     撞 id 判定的三个读法（§2 的既有陷阱，此处逐条点名）：候选文件
     自身除外；按**声明的 id**（`duplicateOf ?? id`）比对而非节点键；
     对照**收束时点的新读盘**而非 ~100ms 去抖缓存。**判定次序（评审轮
     据实校正）**：逐份读法（含撞 id）**先**滤除个体，连续取号**后**对
     幸存者判——否则并行会话落盘一个同号就把本会话整组合式产出连坐
     删光。合式→暂存同 commit（多份合式即多份同落，
     `spec-00006-AC-8.4`）；不合式→删除（会话前不存在，删除即复原）。
     **并行共写同号相撞**：收束经同一串行队列定序，先收束者落盘成既有
     文档，后收束者的同号文件过不了撞 id 判定、逐份被滤除，其余幸存者
     相对新的既有最大编号续判——顺序即裁决，无需新机制。
  3. **其余路径，先过豁免再复原（域主裁定，两类豁免；豁免判定先于
     一切分类——reference 候选同样先过豁免，评审轮据实校正）**：
     （a）**其他运行中会话的认领路径**——按注册表逐会话精确计算：
     推进认领其 `idPrefix` 前缀下的产物与来源文档路径，澄清/审计认领
     其目标文档路径，共写认领其 `targetPath` 与相对**其自身**快照新增
     的 `docs/reference/` 文件。在任一认领中即不动、不暂存，留给那个
     会话自己的收尾——§4「任何变更不丢失」的不变量因此保持，并行会话
     的产出不因共写收束被毁（`spec-00003-FR-1`）。（初稿取「快照后
     差集」读法，评审轮证伪：本会话自己的越界写同样落在他会话的差集
     里，一个并行会话就令过滤整体失效——据实校正为认领读法，本会话
     的越界写不在任何认领中、照常复原。）；
     （b）**白板写管道的未竟提交**——`DocService` 维护「写盘成功而
     commit 失败」的路径集（成功即清，`spec-00001-FR-20` 的文件保留
     语义），在集内的路径不动、不暂存。
     两类都不中的才复原：快照里有全文的写回全文；快照里没有（会话前
     干净）的 `git checkout` 恢复 HEAD；HEAD 也没有（会话新建的域外
     文件）的删除。复原不进 commit。
- 过滤后无可暂存变更 → 不产生 commit（`spec-00006-AC-8.2`）。commit
  信息 `wb(cowrite): <docId>`，终止与自然结束不区分
  （`spec-00001-FR-49` 口径）。产出校验（front matter 与文法，
  `spec-00001-FR-17` 口径）对目标文档与暂存的 reference 运行，诊断照
  `spec-00001-FR-40` 呈现、不阻塞 commit。
- **三条边界声明**：过滤不可逆且先于 commit——收束 commit 失败时
  （`spec-00001-FR-20`：文件保留、不回滚）域内变更留在工作区、域外
  证据已被复原清除，失败经既有会话结束提示与 `/api/events` 到达用户；
  服务端异常崩溃时内存快照消亡、过滤不再运行，`rule-00001-BR-30` 的
  保证以进程存活为界（§5「异常崩溃不保证」的同一边界，明写不藏）。
- **与 `spec-00001-FR-13` 的关系**：`cwd: docs` + CLI 权限机制仍是
  第一层屏障（域外写入多数到不了盘）；收束过滤是共写专属的第二层，
  也是 `FR-13`「不做 git 回滚兜底」半句对共写的改写（其修订轮登记）。
  过滤只作用于 `docs/`（快照的既有范围）；`docs/` 之外的写入仍由
  第一层屏障与 `spec-00001-AC-13.2` 的接入验证承担。

### 11.4 会话期状态锁、编辑器旁路与手改注记

- **状态锁**（`spec-00006-FR-10`）：`docService.changeStatus` 与
  `applyAccept` 在裁决段之前查注册表——目标文档有运行中 `cowrite`
  会话即 **409 `doc-busy`** 拒绝并说明原因（**取 409 不取 422**，
  按 §7 钉死的词汇：这是「资源当前状态的冲突」，与同因的发起拒绝
  同码同 reason；§6 的裁决段次序图前因此多一道会话锁前置判定，见
  §6 追注）；澄清/审计的发起本就被同文档互斥拒绝
  （`spec-00003-FR-2`，其种类枚举增共写后覆盖共写目标），无需新判。
  会话结束后照常。
- **编辑器旁路封堵（域主裁定）**：`PUT /api/docs/:id` 是整文件覆写、
  能绕过状态锁改 front matter——目标文档有运行中 `cowrite` 会话且新
  内容的 front matter `id` 或 `status` 与**会话受理时刻记下的
  `preId`/`preStatus`** 不一致时，PUT 以 409 拒绝（消息点名会话；
  比对锚定受理值而非盘上现值——agent 已改盘上 status 后，按盘上比会
  放过一次把非法状态坐实的保存，核验轮据实校正）；正文手改照常
  （那正是轮流持笔）。异常节点
  不可共写，front matter 修复通路不受影响。**会话中的手改保存照常
  走写管道、一动作一 commit（`wb(edit)`）**——`spec-00006-AC-5.2`
  的「手改落地」由该 commit 承载，收束 commit 只含收束时仍脏的
  残余；一轮里因此可以有多个 commit，`spec-00006-AC-8.1` 的「一次
  commit」说的是**收束这一步**。
- **手改注记**（`spec-00006-FR-5`）：`PUT` 保存成功时，若该文档有
  运行中 `cowrite` 会话，则在该会话上置「用户已手改」标记。注入条件
  **四项同时成立**（评审轮据实收紧——初稿的「首个可打印帧」会把注记
  拼进半行输入或斜杠命令之前，正是 `issue-00011` 的拼接缺陷）：标记
  在、帧含可打印内容（剥 CSI/OSC/SS3 序列与控制字节后非空）、**输入行
  为空**（服务端按用户帧跟踪行态：可打印字符置「行中」，`\r`/`\n`
  复位）、且帧的首个可打印字符**非斜杠**。四项齐备时服务端先向 PTY
  写入注记前缀（「[用户已手改目标文档，动笔前须重读] 」）再转发该帧、
  清标记；任一不备则**顺延**（不注入、不消耗标记）。用户整轮未再触发
  注入即结束会话的，标记随会话消亡（手改已由 `wb(edit)` commit 落地，
  无信息损失）。锁存解除的键位在 **WS 帧
  处理器**而非 `SessionManager.write` 内——注记写入因此天然不解除
  锁存（`decision-00011` 的服务端自发写排除清单增此一项，§5 追注），
  解除仍由用户帧本身触发。

### 11.5 新 agent 的接入验证（流程配置纪律扩展）

`whiteboard.config.yaml` 注释的接入验证先例（`spec-00001-AC-13.2`）对
共写扩展：新增 agent 或改动启动形态进模板配置前，须实测其在共写形态下
（`cwd: docs`、交互式权限机制）的两件事——域外写入不落地；仓外读取
或被该 CLI 自身放行、或产生询问（二者皆可，是否询问是 CLI 版本自己的
策略，不作要求），板侧不预授权、不代答、不压掉询问，且**若**产生询问，
须验一次拒绝后会话可续（`spec-00006-FR-7`）。2026-08-28 对 claude
2.1.250 的实测（据实校正：本节原以「会产生权限询问」为前提）：其
分类器对仓外读取自动放行、全程未出询问；域外写入未落地——指令约束层
实测生效（agent 依写域声明拒写），收束过滤未被触发（无越界写抵达），
其执行层地位仍由 `spec-00006-AC-6.*` 的测试承载。未实测不进默认配置
（与 §3、§10.1 同一纪律）。

## 12. 文内标注通路（第二十三轮）

承载 `spec-00007` 的服务端侧。标注**不是新的会话种类**：question 走 §10 的
headless 答疑，issue 走 §11 的共写，注册表、互斥、上限、等待判定、快照、
收束过滤、收束 commit、状态锁一体沿用。本节因此只定三样新东西——板外的
标注存储、选区锚的重定位算法、统一提交这一个把两条既有通路串起来的动作
——其余每一处都是既有函数的**调用者**，`spec-00007-FR-8` 的「与手工发起
的共写无行为差别」由「同一个受理函数」机械成立，不是纪律。界面侧见
design-00002 §16。

### 12.1 标注存储（`spec-00007-FR-3`/`FR-11`）

- 位置：`.whiteboard/annotations/<docId>.json`——与 §10.2 的问题列表存储
  **同侧同构**：一文档一文件、按文档 id 键控、逐 docId 一条串行队列串行
  完成一切读-改-写、写盘经临时文件 + rename 原子替换；仓库 `.gitignore`
  既有的 `.whiteboard/` 排除覆盖之（`spec-00007-AC-3.2`），存储的读写自身
  不产生任何 commit（`spec-00007-FR-11`）。同构不是审美：标注与答疑线程在
  同一次刷新里被一起读、在一次统一提交里被一起写，两套存储机制会立刻长出
  两套失效、核销与并发口径。**同构有一处显式例外——读侧不宽容**：§10.2
  的问题列表读不出时按空列表宽容处理，标注存储的读不出（文件在而解析
  失败）与写侧同一拒绝（422，消息点名文件路径）——该文件是未提交标注的
  唯一副本，读成空会诱导用户重建覆盖、毁掉仍可手工抢救的数据；「没有
  文件」照旧空列表（`spec-00007-AC-9.9`）。存储层对状态**不以缺省值代
  答**：没有文件 / 读不出 / 有内容三态三答，回合没改内容不落盘（不为
  无标注的文档创建空文件）——评审轮据实增补，issue-00023 在案。
- 形态（`resumeId` 属答疑存储，此处只持 `threadId` 引用；批与标注分列两个
  数组）：

  ```json
  {
    "docId": "spec-00007-doc-annotations",
    "annotations": [
      {
        "id": "n-<取号顺序号>",
        "type": "question",
        "text": "标注文本",
        "anchor": { "selected": "…", "before": "…", "after": "…" },
        "quote": "选区原文引用（定格副本）",
        "createdAt": "<ISO>",
        "state": "pending",
        "orphan": "missing | ambiguous（仅提交校验拦下时落，重新选区或改锚即清）",
        "blocked": "<上次提交单条拦下的原因，再次提交即清>",
        "threadId": "<question 提交后持有的答疑线程 id>",
        "batchId": "<issue 提交后所属批次 id>"
      }
    ],
    "batches": [
      {
        "id": "b-<取号顺序号>",
        "status": "cowriting",
        "sessionId": "<注册表会话 id>",
        "annotationIds": ["n-1", "n-5"],
        "startedAt": "<ISO>", "endedAt": "<ISO>",
        "commit": "<收束 commit 的完整 hash（截断呈现归前端）；null = 无落地变更>"
      }
    ]
  }
  ```

- **未提交与已提交是同一个数组里的一个字段**（`state ∈ pending |
  submitted`），不是两个数组：标注列表按创建序混排逐项呈现
  （`spec-00007-FR-9`），拆数组只是把合并的活推给每个读者。孤儿是 `pending`
  上的一个**失效标记**（`orphan`）而非第三个值——`spec-00007-FR-9` 明写它
  不是独立状态，数据形态照此，界面才不会长出第三个分支。
- **`quote` 与 `anchor.selected` 分两个字段，初始同值**。锚是**键**，引用是
  给人看的**兜底**（`spec-00007-FR-2`：锚失效后引用照常可见）；分开存，
  「引用独立于锚有效性」在数据层就成立，而不依赖任何一处读取代码记得别把
  引用从锚上现算。**`quote` 一律由服务端从 `anchor.selected` 派生，不收
  客户端给的值**——创建（`POST`）与重新选区（`PATCH` 带 `anchor`）两路
  同一派生，契约里因此没有 `quote` 入参（§12.3）。两路对称是刻意的：让
  两个字段初始同值这件事只有一处实现，客户端无从把引用与锚给成两样。
  重新选区（`spec-00007-FR-3`）两者一并替换、`orphan` 一并清除——这就是
  孤儿标注的出路的全部实现。
- **批状态是单一来源**：issue 项的进展**不在标注上存副本**，读 `batchId`
  指向的那一行 `batches[].status`（`spec-00007-FR-9` 的批粒度）。若一批 N
  条各存一份状态副本，收尾回填就是 N 次写，崩在第三条上便留下一个半批。
- **question 项的状态同样不存副本，且服务端不做 join**：标注只持
  `threadId`，`GET /api/annotations/:id` 原样下发该引用，状态由**前端**
  取 `GET /api/asks/:id`（§10.2 的那份存储）、**按 `threadId` 直取该
  线程**、读其**末条 exchange 的 `outcome`** 现算——
  `running | answered | failed | terminated` 一一对应进行中、已回答、
  失败、终止（呈现侧的合成见 design-00002 §16.4）。**这与 §10.3 末条
  会话面板的查法不是同一种**：面板行只持注册表会话 id，须按 exchange 的
  `runSessionId` **反查**线程；标注本来就持有线程 id，直取即可，不经反查。
  两份存储因此各自仍是自己的单一来源：服务端若 join，标注载荷的失效条件
  就变成两个文件的并集，而问题列表那半边已经有自己的重取项（§10.3，其
  取数条件同轮扩为「问题列表或标注列表打开期间」）。
  `spec-00007-AC-9.11`/`AC-9.14` 的「重发走问题列表的既有线程能力、
  标注项跟随」由此**不需要任何回写通路**：问题列表就地改写那条
  exchange，下一次合成就跟着变了。**镜像是机械的，粒度不作特判**
  （域主裁定：标注项就是线程末条 exchange 状态的镜像；用户在该线程上
  追问期间标注项从「已回答」退回「进行中」，这是如实呈现，不是缺陷，
  因而不引入「只镜像首问」一类的粒度规则）。
- 文档删除或改 id：文件保留、不回收，graph 上无该 id 时前端无处呈现
  （`spec-00007-FR-11`，同 §10.2 末条的问题列表口径；回收属范围外）。

### 12.2 选区锚与重定位（`spec-00007-FR-2`）

- **坐标系是整文件的规范化文本**，不是剥掉 front matter 的正文，也不是
  磁盘字节：编辑视图态的偏移本来就是对整文件数的（front matter 可见可改，
  §2 Editor），预览映射产出的偏移加上 front matter 块长度即整文件偏移
  （映射本身属 design-00002 §16）。两个视图态一个坐标系，锚因此不必记
  自己是从哪个视图态来的。**坐标系与可加注面是两回事**：可加注的是
  **可映射正文选区**——front matter 与代码块 / mermaid 源码块内不可加注
  （域主裁定，守门与呈现属 design-00002，条文半句由 `spec-00007-FR-1`
  承载），而锚的偏移照旧对整文件数。已知边界，记明不修：选区紧贴正文
  开头时其 `before`（上下文，不是选区本身）可能含 front matter 尾部，
  `status` 行随修订轮变动会打掉这条锚的整键——退到层 2，仍不中则退化为
  「原文已变更」（`spec-00007-FR-12`），不影响任何处理。
- **规范化只有一件事，且存、匹配、下发三处同一份**（这一条与上一条互锁，
  必须一起读）：服务端读到整文件后先把 `\r\n` 与裸 `\r` 一律换成 `\n`，
  **锚的三段按该规范化文本截取、重定位在该规范化文本上扫描、`locate` 的
  偏移也是该规范化文本上的偏移**。不折叠空白、不去 Markdown 语法——锚锚的
  是源文本，任何进一步的规范化都会让「锚文本被改」这个判定失真，把用户
  明明改过的句子判成命中。**不做原文件坐标的换算**：换算表要随每次读盘
  重建、还得在前端复算一遍才对得上，而**前端在切片与高亮前做同一次
  规范化**即可让两侧共用一个坐标系（CodeMirror 的 `lineSeparator` 默认
  就是 `\n`，本仓亦无 CRLF 文件——这在今天是恒等变换，写死它是为了将来
  有人拉进一份 CRLF 文件时两侧不会各说各话）。规范化只在**读侧**，不改
  磁盘内容，写盘照旧是编辑器给什么写什么。
- **锚的三段**：`selected` = 选区文本；`before`/`after` = 其前后各
  **64 个 Unicode code point**（不足则到文首/文尾），按创建时刻的规范化
  文本截取、原样存，含换行。**单位钉死为 code point 而非 UTF-16 code
  unit**：`Array.from` 计数即得，截取因此永不切开代理对（一个 emoji 或
  一个罕用汉字被劈成半个的锚，其后任何匹配都必然失败）；并且**边界不得
  落在组合序列内部**——截到第 64 个之后，若边界外紧邻的下一个码点
  （`before` 侧看其首码点之前、`after` 侧看其末码点之后，方向各自向外）
  属 `\p{M}`（组合记号），沿该方向继续吞并直到不再是组合记号为止
  （初稿括注的方向读法自相矛盾，实现轮据实校正），
  宁可多带几个码点也不留半个字位簇。**64 是设计缺省，待实测调**（域主
  裁定保留该值待实测）——它调的是层 1 的两头：太短则常见短语的整键仍会
  多处等同命中，而层 2 不猜、直接拦下；太长则任何邻近改动都打掉整键、
  把定位频繁推进层 2，与 `spec-00007-FR-2`「锚文本之外的改动不影响定位」
  相悖。两三行的量级是这两头之间的一个起点，实测后可改，改的只是一个
  常数、不动算法。**取字符不取行**：取行则边界随 Markdown 折行漂移，而
  预览映射交回来的本来就是字符偏移。
- **偏移量的单位是 UTF-16 code unit**（`String.prototype.indexOf` 与
  CodeMirror 的天然单位），与上一条的「64 按 code point」**不矛盾**：
  64 是一个**计数**，`locate` 的 `start`/`end` 是**下标**，两者各取各的
  自然单位，各自钉死即可，不必为了统一而给任何一侧套一层换算。
- **扫描是全位置重叠枚举**（`indexOf(key, hit + 1)` 递进，**不是**
  `hit + key.length`）：这一句决定歧义计数，因而决定 `AC-2.3`/`AC-2.4` 的
  成败——自重叠的键（`abab` 之于 `ababab`）按非重叠只数得一处，会把本该
  拦下的歧义静默判成命中，正是 `spec-00007-FR-2` 禁的「静默取其一」。
  命中数一旦超过 1 即可短路（下面两层都只问「零、一、还是多于一」）。
- **重定位算法，两层，恰一处命中**（`spec-00007-FR-2` 的条文级口径落在
  这里）：
  1. **整键**：`before + selected + after` 在全文中扫描。恰一处 → 命中，
     返回 `selected` 所在区间。**多于一处 → 判歧义命中、失败，不降级**
     ——`spec-00007-AC-2.4` 的「创建时刻即歧义」（整段逐字重复文本）正是
     这一支，与后续编辑造成的歧义（`AC-2.3`）同一处置。
  2. **整键零命中时退到 `selected` 单独扫描**（上下文被邻近编辑改动的常见
     情形）：零命中 → 失败（`missing`）；**恰一处 → 命中**；**多于一处
     → 失败（`ambiguous`）**。这一层**只认唯一命中，不做任何打分**
     （**域主裁定**：层 2 去打分；初稿的「公共前后缀之和 + 领先第二名
     ≥ 8」两项一并删除，那个常数不再存在）。
     **理由**：打分是在猜「哪一处更像当初那一处」，而 `spec-00007-FR-2`
     明写定位失败即拦下、**不静默取其一**——一个会猜的算法，它猜对时省下
     的是一次重新选区，猜错时给出的是一条指着别处的标注，后者的代价高得
     不成比例。去掉它，本层的判据回到一句话：**上下文帮不上忙的时候，就
     承认帮不上**。
     **「上下文参与消歧」仍然成立，落点在层 1**：整键含上下文，选区文本
     是常见短语时靠上下文照样唯一定位（`CONTEXT.md`「选区锚」词条与
     `spec-00007-FR-2` 的那半句说的正是这件事）。层 2 是上下文**已被改动**
     之后的退路，此时上下文已不是可信的判据，拿它打分等于用一份过期的
     证据做裁决。
  3. 失败只有两种原因值：`missing` 与 `ambiguous`，前端据以选提示文案，
     二者在处置上无差别（提交前拦下、提交后退化）。
- **两个执行点，同一个函数**：
  - **统一提交时**，对**刚读盘的整文件**逐条重定位（`spec-00007-FR-5`
    的「以磁盘正文重新定位」）——这也是「提交前须无未保存改动」这道前置
    存在的理由：锚校验以磁盘为准，缓冲里的新句子在盘上还不存在。
  - **标注列表读取时**（`GET /api/annotations/:id`），对当前磁盘整文件
    逐条重定位，每条产出 `locate: {start, end} | {failed: 'missing' |
    'ambiguous'}`。已提交项的失败**只是呈现退化，不改状态、不中断处理**
    （`spec-00007-FR-12`，`AC-12.1` 的「共写自己改掉了另一条的锚」即此支）。
  - 编辑期不重锚（`spec-00007` §6 排除）：列表载荷的定位一律以磁盘为准，
    缓冲有未保存改动时的文内痕迹由前端自行按缓冲定位（design-00002 §16）。
- 代价：一次全文 `indexOf` 扫描 × 标注条数，本仓文档量级（几十 KB、十条
  量级）可忽略，不设缓存——缓存要跟着磁盘失效，那是拿 §2 的缓存失效复杂度
  换一个测不出来的耗时。

### 12.3 统一提交 API（`spec-00007-FR-5`/`FR-10`）

```
GET    /api/annotations/:id              → {annotations, batches, submitPreview}
POST   /api/annotations/:id              {type, text, anchor} → 201 {annotation} | 422 {error, reason: type-ineligible|empty-text|doc-anomalous}
PATCH  /api/annotations/:id/:annId       {text?, type?, anchor?} → 200 {annotation} | 404 | 409 {error, reason: already-submitted} | 422 同 POST
DELETE /api/annotations/:id/:annId       → 200 | 404 | 409 {error, reason: already-submitted}
POST   /api/annotations/:id/submit       {unsavedChanges: boolean, agents?: {question?, cowrite?}}
  → 200 {submitted, blocked, transition, warnings?}
  | 409 {error, reason: submit-in-flight|doc-missing}
  | 422 {error, reason: doc-anomalous|unsaved-buffer|empty-submit|unknown-agent|agent-not-headless}
```

（`warnings?: string[]` 系评审轮增补：仅「线程已开、引用写不进盘」
一种情形出现——该 question 如实报 submitted 并附警告，不报 blocked，
否则重提会为同一问开出第二条线程；前端可忽略，缺省不存在。另注：
提交在途期间该文档标注的 `PATCH`/`DELETE` 同答 409
`submit-in-flight`——分派按提交时刻的快照构建，窗口内的改删会使批
与材料脱离盘上现实，评审轮增补。）

- **不挂 `/api/docs/:id/` 下**：标注存储脱离文档存续（文档删除后仍可寻址），
  与 `/api/asks/:id`、`/api/create` 同例（§7）。`:id` 的形态先校验
  再作路径，沿会话历史的文件名守卫口径。
- **改与删只对未提交的条目开放**（`spec-00007-FR-3` 明写「提交前每条可
  修改、可删除、可重新选区」）：`PATCH`/`DELETE` 命中 `state: submitted`
  的条目一律 **409 `already-submitted`**。取 409 不取 422 按 §7 钉死的
  词汇——这是「请求与资源的当前状态冲突」，条目本身合法，只是已经不在可
  改可删的那个状态上了。**这不是洁癖**：已提交的 issue 条目被删会让
  `batches[].annotationIds` 指向不存在的条目，那批的「回到未提交」就没有
  东西可回；已提交标注的删除与清空本就是 `spec-00007` §6 排除的将来轮。
  终止/失败后**回到未提交**的条目 `state` 已改回 `pending`，照常可改可删
  （`AC-10.7`）——判据只看 `state`，不看有没有提交过。
- **`submitPreview` 与列表同一次下发，不另立预演端点**
  （`spec-00007-FR-5` 的「提交入口应明示本次将发生的动作」）：
  `{questions: n, issues: m, willTransitionTo: 'draft' | null, issueEligible,
  questionEligible}`，由 Workflow Engine 现算。前端零判定（§2 的既有分工：
  裁决只有一个点），而明示与列表本就是同一次呈现的两半——拆成第二个端点等于
  把资格判定实现两遍，且必然漂移。两处口径钉死：
  - **计数不扣孤儿**：`questions`/`issues` 就是未提交区里该类型的条数，
    **预览不做锚校验**——锚校验只发生在提交那一刻（`spec-00007-FR-5`），
    预览若先算一遍，一是把一次全文扫描搬进每次刷新，二是它算出的数与真正
    提交时的数仍会不同（中间盘上还会变）。`AC-5.7` 验的是「将发生的动作
    **种类与数目按未提交区呈现**」，不是「预言最终有几条真的发起」；有
    条目被孤儿拦下时，实际结果由应答的 `blocked` 与列表里的失效标记
    承接，那才是它该出现的地方。
  - **`issueEligible`/`questionEligible` 只含状态守门与 agent 声明守门**
    ——前者是 `spec-00007-FR-4` 的 `rule-00001-BR-29`/`BR-3` 判定，后者是
    `FR-10` 的「有无 agent 声明 headless」——**不含同文档互斥与总并发
    上限**。这两样绝不能进来：`spec-00007` §1 已为统一提交入口登记了
    `spec-00003-FR-2`/`FR-3`「发起入口禁用」半句的**排除**，把互斥或上限
    算进 eligible 就等于让入口随它们整体禁用，绕回被明文排除掉的那件事。
    互斥与上限只在提交时逐通路判、以 `blocked` 呈现（`AC-10.1`、
    `AC-10.3`）。
  二者同时是 `spec-00007-FR-4`/`FR-10` **类型可选集**的唯一来源（前端不
  自行按 status 推）。
- **前置校验的固定次序**（`spec-00007-FR-5` 的「整体前置先于逐通路判定」，
  次序按代价递增，同 §6 的既有理由）：**在途（409）→ 文档存在（409
  `doc-missing`）→ 文档非异常（422 `doc-anomalous`）→ 未保存缓冲（422）
  → 空提交（422）→ agent 合法性（422）**。在途排头是并发保护——第二次
  请求根本不该去碰盘；文档存在与非异常排在缓冲之前，因为对一份已删或
  front matter 不合法的文档提示「先保存」是句错话。任一命中即整批不发生
  任何事（`AC-5.4`、`AC-5.3`、`AC-10.4`、`AC-10.6`、`AC-4.6`）。
- **异常文档整体拒绝**（`spec-00007-FR-4` 末句、`AC-4.6`）：判据是图上该
  节点的 `ok === false`——与添加标注的 `doc-anomalous` 同一判据、同一码，
  提交与添加两个入口因此不可能对同一份文档给出不同结论。前端本就不呈现
  入口，这一道是服务端兜底（同 §11.1 共写对异常节点的既有做法）。
- **`doc-missing` 的判据钉死为「按 graph 的 id 解析」**，不是按规范路径
  读盘：`DocService.require(id)` 找不到该 id 的节点即 409。`AC-10.6`
  要的正是**改 id** 也被拒——一份被改了 id 的文档，其规范路径下的文件
  可能还在（改 id 未必改文件名，甚至改了文件名而路径仍可拼出），按路径
  读盘会把它当成还在，于是拿着旧 id 的标注去提交一份已经不是它的文档。
  按 id 解析则删除与改 id 是同一支，一条判据两个 AC 都守住。撞 id 的
  情形沿 §2 的既有通路（`require` 抛 `ConflictError` / 409），不新写。
- **未保存缓冲由前端声明**（载荷字段 `unsavedChanges`，缺省读 `false`）。
  服务端**无从核验**：未保存缓冲只活在浏览器里，没有第二个观察点。这不是
  留了个洞——伪造它的全部后果是拿磁盘正文重定位（`spec-00007-FR-5` 明写
  锚以磁盘为准），用户自己看到一批孤儿被拦下，不产生任何越界写、不改任何
  状态；为它引入第二道核验（比如要求带 `baseHash`）会凭空多出一个 spec
  没有的拒绝面——外部改动过的文档将无法提交。**此定性经域主确认**（前端
  声明、服务端不核验，`spec-00007-FR-5` 的这道前置就落在这个契约上）。
- **在途判定**（`spec-00007-FR-10`）：服务端持一个**内存**的
  `Set<docId>`，在前置全部通过、进入分派时加入，分派全部完成（含逐条拒绝
  已落盘）后在 `finally` 里移除。放内存不放盘：在途是一次请求的生命期，
  跨重启没有意义，重启后未提交区照旧、重提即可。它与 §10.2 的逐 docId
  写队列**不是同一样东西**——那条队列串行的是单次读-改-写，在途要跨越
  流转、spawn 与多次落盘，队列表达不了。
- **应答结构，部分成功一律 200**。本节钉死这条分界：**4xx = 整批没有发生
  任何事；200 = 批已执行，逐条结局在载荷里**。提交是批动作，锚失效、资格
  丧失、上限拦下都是**单条或单通路**的结局，用 HTTP 状态码表达它们会逼着
  前端从一个码里猜哪几条成了。

  ```json
  {
    "submitted": {
      "questions": [{ "annotationId": "n-3", "threadId": "t-7", "sessionId": "s-11" }],
      "issues": { "batchId": "b-2", "sessionId": "s-10", "annotationIds": ["n-1", "n-5"] }
    },
    "blocked": [{ "annotationId": "n-2", "reason": "orphan-missing", "message": "…" }],
    "transition": { "to": "draft", "committed": true }
  }
  ```

  `submitted.issues` 与 `transition` 为 `null` 表示该通路未发生；`blocked`
  的 `reason` 枚举：`orphan-missing`、`orphan-ambiguous`、`gate-ineligible`
  （issue 资格复验不过，整批 issue 同因，`AC-4.7`）、`doc-busy`（同文档
  互斥，含自指，`AC-10.1`）、`cap-reached`（`AC-6.3`、`AC-10.3`）、
  `start-failed`（`AC-7.4`）、`no-headless-agent`。被拦下的条目
  `state` 留 `pending`、写 `blocked` 字段，可改可删可再提交
  （`AC-10.2`）。
- **逐条锚校验**在前置之后、分派之前一次做完：失败的条目就地写
  `orphan`，**不进任何通路**（`spec-00007-FR-5`：单条拦下、一条失效不阻
  整批），其余条目按 `type` 分两组。
- **issue 资格复验**（`spec-00007-FR-4`）以**刚读盘的 front matter** 判，
  不读图缓存——攒批期间的流转必须被看见；读盘在提交开头做**一次**
  （`invalidate()` 后读，同 §11.3 手法），判定次序仍在锚校验之后——
  同一请求内二次读盘无可观察差别，反会凭空多出「提交中途文档消失」的
  失败面（实现轮据实校正）。判据复用既有代码，不新写表：
  `statusRules` 的种类二分 + `rule-00001-BR-29`（`draft`，或 work item 的
  `open`）直接合格；`active` 的 living doc 经 `rule-00001-BR-3` 合格且需
  流转；其余整批 issue 拦 `gate-ineligible`，question 照常
  （`spec-00007-AC-4.7`）。
- **逐通路 agent 选择**（`spec-00007-FR-5`）：载荷 `agents` 是**两个
  字段**而非一个——`question` 的可选集是声明了 `headless` 的 agent
  （§10.1），`cowrite` 的是配置全集（§3）。缺省各取各可选集的**第一条**
  （`spec-00001-FR-55` 口径），`spec-00007-AC-5.6` 要的正是两边各缺省各的，
  单字段表达不了。可选集来源是 `/api/config` 的既有下发（`headless` 声明
  可辨），不新增端点。**三种情形三个词，对齐 §7 `/api/sessions/ask` 的
  既有 422 词汇（「未知 agent」与「agent 未声明 headless」在那里就是分开
  说的）**：显式指定的名字不在配置里 → 422 `unknown-agent`；指定的
  question agent 在配置里但未声明 `headless` → 422 `agent-not-headless`；
  二者都是**整批不发生**（前置阶段判，客户端给错了参数）。而**一条
  agent 都没有声明 headless** 时不是 422——那是 `spec-00007-FR-10` 的
  对称守门，是环境的事实不是请求的错，question 条目**逐条**拦
  `no-headless-agent`，issue 照常发起（`AC-10.5`）。
- **分派次序：先共写，后逐条 question**（`spec-00007-FR-5` 的可观察承诺）。
  实现上是一个 `await` 序列而非并发：issue 通路整个走完（判定 → 流转 → 占槽 →
  spawn）之后，才逐条顺序受理 question。`spec-00007-AC-5.8`（只剩一个名额
  时共写取走、两条 question 皆拦）由这个次序**直接**成立，不需要为它加任何
  名额预留机制。

### 12.4 issue 通路——程序化发起共写（`spec-00007-FR-7`/`FR-8`）

- **发起是服务端内部直调**，不经 `POST /api/sessions/cowrite` 的 HTTP 层：
  调用 §11.1 的同一个受理函数，`materials` 位换成程序化构成的材料行。
  同一注册表、同一受理裁决、同一收束过滤、同一收束 commit——
  `spec-00007-FR-8` 的「无行为差别」因此是**同一段代码**，而不是两处需要
  对齐的实现。
- **材料段**（`spec-00006-FR-3` 的既有拼接位，`CowriteTask.materialLines`）
  换一个构造函数产出，逐条 issue 一段，指令骨架、写域约束、reference 要件、
  蒸馏要求原样不动（`spec-00006-FR-1` 的指令骨架零改动）：

  ```
  Issue <i> of <n> — the passage the owner marked in <docPath>:
    …<before>[[<selected>]]<after>…
  What they want changed: <标注文本>
  ```

  上下文原样附上（`spec-00007-AC-7.1` 要「选区原文/上下文/标注文本」三样
  齐全），选区本身以 `[[ ]]` 夹注标出——agent 拿到的是原文不是转述，而
  夹注让它分得清哪一段是被点名的那一句。
- **纪律条款**四条，紧随材料段之后、末行 `Change nothing outside the docs
  tree.` 之前（纪律是对材料的处置方式，位置跟着材料走）：

  ```
  Work through the issues above one by one, in the order given, and report on each as you finish it.
  Where you cannot tell what an issue is asking for, stop and ask the owner — never guess.
  Where an issue implies a change to a related document, report that implication and leave it to the
    owner: writing it is outside what you may write here, and would be filtered out and restored
    when this session ends.
  Do no review action: never accept, clarify or audit anything, and never touch the status line.
  ```

  第三条同时说了「为什么」——写域外的写入**会被收束过滤复原**
  （`rule-00001-BR-30`、§11.3），指令层与执行层因此说的是同一件事，agent
  不必猜哪条是硬约束。
- **自动流转复用既有状态切换通路**（`spec-00007-FR-7`，`rule-00001-BR-3`）：
  目标为 `active` 的 living doc 时调 `DocService.changeStatus(id, 'draft')`
  ——就是 §6 写管道的状态切换支，读盘校验 → 流转合法性表 → 三道门 → 写盘 →
  `wb(status): <id>` 一动作一 commit，全部原样。**不新写流转函数、不旁路
  三道门**：`active → draft` 天然三门皆不适用（§6 已述——目标非促进态、非
  `archived`、非 plan 的 `open → resolved`），复用因此不改变任何一次拒绝的
  结论，却白拿了 compare-and-swap 冲突检测与 commit 串行队列。
- **实现次序：判定段与占槽段拆开，流转夹在中间**（`spec-00007-FR-7` 的
  全有或全无，边界是流转写盘）：

  1. 前置（§12.3）→ 逐条锚校验 → issue 资格复验；
  2. **判定段——纯判定，不入注册表**：同文档互斥与总并发上限。实现上把
     `SessionManager` 现有的私有 `admit(plan)` 提为可单独调用的判定
     （它本就无副作用，只抛 `SessionBusyError`；占槽发生在
     `startDeferred` 里 `admit` 之后的入表那一步）。不过即整批拦下
     `doc-busy`/`cap-reached`，**不流转、不建会话**（`AC-10.1`、
     `AC-10.3`）；
  3. **启动预检**：对所选 agent 的 `command` 做可执行性判定，不可执行即
     整批拦 `start-failed`、**不流转**；
  4. **流转**（目标为 `active` 的 living doc 时）——`changeStatus` 写盘 +
     commit。此刻注册表里**没有本会话**；
  5. **占槽段**：从**刚流转完的盘上内容重读** `id`/`status`，据以构造
     `plan.cowrite = {targetPath, preId, preStatus}`，再调
     `startDeferred`——它同步完成受理二次判定、占槽、会话前快照与内容
     快照、入表 `running`（§5、§11.3）；
  6. 批行以 `cowriting` 落盘、该批标注改 `submitted`（§12.6 写序）；
  7. `launchTerminal` spawn。

  **`preStatus`/`preId` 的取值时机是第 5 步，即流转之后**——这句是本节
  对 §11 的唯一接口约定，必须明写：`startDeferred` 读的是调用方给的
  `plan.cowrite`，调用方在流转前构造它就会把 `active` 钉进基线。
  **为什么必须这样排——三处冲突同一个因**（会话过早入表），改次序一次
  全消，§11 的三段代码零改动：
  - `spec-00006-FR-10` 的**状态锁**（§11.4）在 `changeStatus` 前查注册表，
    见到本文档有运行中 `cowrite` 即 409 `doc-busy`——先占槽后流转，这次
    流转会被本会话自己锁死；
  - §11.3 的 **front matter 守卫**拿 `preStatus` 把收束时的 `status` 行
    改回会话前值——基线若停在 `active`，收束会把 `draft` 无条件改回
    `active`，直接违 `spec-00007-AC-8.5`（会话不促进、不改状态，文档收束
    后仍应是 `draft`）；
  - §11.4 的 **PUT 旁路封堵**同样锚定受理时刻的 `preStatus`——基线若是
    `active`，用户在会话期间对正文的手改保存会因 `status` 与基线不符而
    必然 409，违 `spec-00006-FR-5` 与 `spec-00007-FR-8` 的「与手工发起的
    共写无行为差别」。

  另外两个候选被否决，记明理由：**「流转对本会话豁免锁」**要把 §11.4
  那条「注册表里有没有运行中 cowrite」的单行判据改成带例外的判据，
  `spec-00006-FR-10`「不得有一条通路绕过」的可核性随之打折，且它只解掉
  三处冲突里的一处；**「`preStatus` 流转后回填」**等于承认基线有一段
  时间是错的——那段窗口里用户恰好保存正文仍会 409，且它同样不解状态锁
  自锁。
  **第 3 步是本节为 `spec-00007-AC-7.4` 添的唯一新机制**：
  `spec-00001-FR-16` 的启动失败在 Node 里是 spawn 之后的异步 `error`
  事件，而 `AC-7.4` 要求「CLI 必然启动失败时文档仍为 `active`、无流转
  commit」——不做一次不 spawn 的预检就无法在写盘之前知道这件事，而
  `spec-00007-FR-7` 又明写写盘之后**不回滚**。预检的判据**与 spawn
  逐字同源**：复用 `pty.ts` 的同一个判定函数（spawn 前自查的那一个）
  ——`command` 含路径分隔符时按路径 `fs.accessSync(X_OK)`，基准是
  **服务进程的 cwd**；裸名走 `which`/`where`。初稿写的
  「`join(repoRoot, agent.cwd ?? '.')` 基准与逐段拼 `PATH`」与 spawn
  的实际判定不符——预检若自立判据，恰好制造它要防的「预检过而
  spawn 拒」落点（实现轮据实校正，评审揭出）。预检拦住的是 FR-16
  的主要情形（命令不存在/不可执行）；
  spawn 之后才崩的残余落进 FR-7 自己点名的**复合角落**（下条）。
- **两处残余角落，都落 FR-7 明写的复合角落，都不回滚**：
  （a）第 5 步的二次受理判定抛 `cap-reached` 或 `doc-busy`——第 2 步与
  第 5 步之间隔着流转的写盘与 commit（有 `await`），期间别的发起可能
  取走最后一个名额或占走该文档；同一窗口里第 4 步流转自身也可能被
  §11.4 的状态锁以 409 `doc-busy` 拒绝，该支落在写盘**之前**、归口
  与判定段拒绝相同（批行未落盘、应答直接拦）；
  （b）第 7 步 spawn 之后的异步启动失败。两者中已写过盘的，文件一律
  保留为 `draft`，修复后再提交走不流转分支；**回到未提交的路子不同**——
  (a) 发生在批行落盘之前，那批 issue 还是 `pending`，本次应答直接拦
  该 reason；(b) 时应答早已发出、批行已在盘上，issue 经 §12.6 的
  `failed` 终局回到未提交区，错误由会话结束提示与 `/api/events` 送达。
  **判定跑两遍是有意的**，不是冗余：第一遍为 `AC-10.3` 的「无流转」，
  第二遍才是 §5 那条「受理时占用、先到先得」的记账，语义各归各。
  不为 (a) 加锁——加锁就是把流转的写盘与 commit 整个放进注册表的临界区，
  而那次 commit 走的是 §4 的串行队列，会与别的会话收尾互相等待。
- **流转 commit 失败**（`spec-00001-FR-20`，`spec-00007-AC-7.5`）：
  `changeStatus` 返回的 `{committed: false, error}` 原样进应答的
  `transition`，**会话照常启动**——写盘已成，文档在盘上就是 `draft`，可
  共写（同 `spec-00006-FR-2` 对建档 commit 失败的既有裁定）。commit 失败
  不是流转失败。
- **`spec-00006-FR-4` 的 Source 视图一次覆盖不适用，不需要任何传递机制**：
  那次覆盖是**前端行为**（发起成功后把编辑器切到 Source，design-00002
  §15），服务端载荷里从来没有它。谁发起谁覆盖——共写发起 UI 的调用点做，
  统一提交的调用点不做。服务端因此零改动，例外落在 design-00002 修订轮的
  一个调用点上。
- 会话运行中的一切照 §11：等待判定与徽标（`AC-8.3`）、同文档互斥
  （`AC-8.1`）、状态锁拒绝接收（`AC-8.4`）、收束过滤（`AC-8.2`）、收束
  commit、终止收尾——本节不加码，也不豁免。

### 12.5 question 通路——首调指令的选区材料（`spec-00007-FR-6`）

- **拼装点是 `sessionTasks.askInstruction`**，增一个可选的 `selection`
  参数：在既有四行（性质说明 + 目标文档路径 + 全部关系文档路径 + 只读
  说明）之后、问题文本之前追加一节：

  ```
  The passage they marked, quoted from <docPath>:
    …<before>[[<selected>]]<after>…
  ```

  首调载荷（§10.1 的 `{question}` 整段替换）因此是「答疑指令 + 选区节 +
  标注文本」，占位形态与 capture 口径零改动。**追问不带选区节**——resume
  形态只替换追问文本（`spec-00005-FR-2` 的上下文不重付），这一点对标注
  发起的线程与手工发起的线程完全一致。
- 每条 question 各发一次首调、各开一条线程（`spec-00005-FR-2`），返回的
  `threadId` 在同一条逐 docId 串行队列里回写该标注。发起受既有拒绝面约束
  ——线程串行、总并发上限（`spec-00005-FR-6`：不占文档，但占名额）——被
  拒的**单条**拦下留未提交区、`blocked` 点名原因，不阻其余
  （`spec-00007-AC-6.3`）。
- 无 agent 声明 headless 时（`spec-00007-FR-10` 对称 FR-4）：
  `submitPreview.questionEligible` 为 false，`POST /api/annotations/:id`
  的 question 类型 422 `type-ineligible`，提交中的 question 条目拦
  `no-headless-agent`，issue 不受影响。

### 12.6 批状态生命周期、核销与刷新（`spec-00007-FR-9`/`FR-10`）

```mermaid
stateDiagram-v2
  [*] --> cowriting: 批行落盘（先于 spawn）
  cowriting --> done: 注册表 exited（收束过滤与 commit 照常）
  cowriting --> terminated: 注册表 terminated（面板终止 / 正常关停）
  cowriting --> failed: 注册表 failed（spawn 后启动失败，spec-00001-FR-16）
  cowriting --> failed: 启动核销（服务异常终止遗留）
  done --> [*]: 标注保持 submitted，呈已完成与收束 commit 引用
  terminated --> [*]: 标注解引用回 pending，可改可删可再提交
  failed --> [*]: 同上
```

**终局映射是注册表状态的一张全表**，`SessionStatus` 的四个值一个不漏：
`exited`（不论退出码）→ `done`，`terminated` → `terminated`，`failed`
→ `failed`；第四个 `running` 是进行中、不映射。三条终局共用**同一个
结束回调**，批不认识「为什么结束」，只认结束态。

- **批行的写序：落盘先于 spawn**（同 §10.2 答疑 exchange 的取法，理由
  也同一条——崩溃时内存里的记录没有意义，启动核销要有东西可核）：占槽段
  完成后（§12.4 第 5 步）先以 `cowriting` 追加批行、把该批标注改为
  `submitted`，随后才 `launchTerminal`。
- **spawn 之后的启动失败不设特设回滚，走注册表的 `failed` 终局**
  （`spec-00001-FR-16`，`spec-00007-FR-7` 点名的复合角落）：
  `launchTerminal` 的 seam 抛出时会话经既有路径落 `failed`、还槽、进
  会话列表并发提示条（§5），而 `failed` 是上表里的一条终局——**同一个
  结束回调**照常跑到批回填，批记 `failed`、标注解引用回未提交区。
  写明「不特设回滚」是因为初稿的两步手工回滚会**漏掉一半**：`failed`
  这条终局本来就要为「异常终止核销」以外的失败存在，两套写法只会在
  某个角落各写一遍、其中一遍忘了解引用。批行尚未落盘时（占槽成功而批
  写盘本身抛出）无可回填——那批标注还没改成 `submitted`，什么都没发生，
  会话按 §10.3 的 `abandon` 还槽即可。
- **回填挂点在会话的结束回调**——自然结束时它就是 §11.3 收尾序列的末端，
  次序为历史落盘 → 过滤 → commit → **批回填** → `/api/events` 广播；
  `failed` 的会话没有收束那几步，回填仍在同一个回调上。按 `sessionId`
  在 `batches` 中反查（该字段即为此存在），写
  `status`/`endedAt`/`commit`，终局按上表映射。
- **收束 commit 引用**（`spec-00007-AC-9.4`/`AC-9.5`）：`CommitOutcome`
  增一个可选 `sha`——`simple-git` 的 `commit()` 已返回 `CommitResult.commit`
  （实测为完整 40 位 hash，非初稿所记短 hash——实现轮据实校正；截断
  呈现归前端），照实带回即可；无可暂存变更时 GitLayer 本就返回
  `{committed: false}`、无 sha，批的 `commit` 记 `null`，「明示无变更」由
  这个 `null` 承载（`spec-00006-FR-8` 的无 commit 分支）。这是本轮对既有
  类型的**唯一**改动。
- **回到未提交的实现**：`terminated`/`failed` 时把该批 `annotationIds`
  的每条 `state` 改回 `pending`、清 `batchId`；批行本身**保留不删**
  （只增不删的既有先例，`spec-00007` §6），此后不再被任何标注引用，只作
  历史。`spec-00007-AC-10.7`（终止后可改后再提交、新会话）与 `AC-10.2`
  由此成立。
- **启动核销**（`spec-00007-AC-10.8`）：服务启动扫一遍
  `.whiteboard/annotations/` 目录，`status: cowriting` 的批一律改写为
  `failed` 并按上一条解引用——与 §10.2 答疑的启动核销**同一挂点**（同一次
  启动扫描，两个目录各扫一遍），理由也同一条：注册表空态起步
  （`spec-00003-FR-9`），磁盘上不许有幽灵进行中。
- **正常关停**无需新判：§5 的关停收尾对 `cowrite` 会话逐个走终止路径，
  收尾回调照常跑，批记 `terminated`。
- **刷新重取扩一项**（§6 与 §10.3 的重取清单，第六项）：目标文档的
  **编辑器打开期间**，前端刷新一并重取 `GET /api/annotations/:id`；
  编辑器未打开不取。条件取编辑器而非「列表打开」是对前五项口径的**有意
  偏离**——未提交标注的文内痕迹要在编辑与预览两态里呈现
  （`spec-00007-AC-9.13`），裁定与理由属 design-00002 §16.8，服务端侧
  只需知道这条载荷会被高频重取（12.2 末所说的「不设缓存」正是对着它
  算的）。无此项，`cowriting → done` 的翻转到不了页面（question 项的
  线程状态走的是 §10.3 的问题列表那一项，两半各有各的重取）；载荷里逐条现算的 `locate`
  也随之刷新，`spec-00007-FR-12` 的「原文已变更」提示因此在共写写入的
  下一次推送里就到位（`AC-12.1`）。
- **连带把第四项的条件一并改了**（§10.3 与 §6 那两处措辞，本轮就地改，
  非留待办）：question 项的状态由前端按 `threadId` 从 `GET /api/asks/:id`
  合成（§12.1），而第四项原来的取数条件是「问题列表视图打开期间」——
  用户停在标注列表这一视图态时它不取，那些项的状态会停在打开那一刻。
  条件因此扩为「问题列表**或标注列表**打开期间」，两处原文已同轮
  改写；对侧的前端登记见 design-00002 §16.8。服务端载荷零改动，改的只是
  谁在什么时候来取。

## 13. Agent 设置——两层配置与设置面板的服务端（第二十六轮）

承载 `spec-00009` 的服务端；取舍全部在案于 `decision-00017`。界面侧归
design-00002 §18。§3 的 `agents` 从本轮起是**项目层**；本节持有本地层的文件
形态、合并规则、不合式降级、按次重新计算与两个 API 的语义。

### 13.1 本地层文件

`.whiteboard/agents.json`（`.whiteboard/` 已 gitignore；JSON 而非 YAML 的理由
见 `decision-00017` §2 第 3 条）。形态：

```json
{
  "default": "claude",
  "disabled": ["other"],
  "overrides": {
    "claude": { "model": "claude-sonnet-5", "args": ["--model", "{model}"], "env": { "FOO": "bar" } }
  },
  "entries": {
    "codex-local": { "command": "codex", "args": ["-m", "{model}"], "model": "gpt-5", "env": {} }
  }
}
```

- 四个键都可选；文件不存在 = 空本地层。`overrides` 与 `entries` **分两个键**
  而不混在一张表里：这样「覆盖所指的项目条目不存在」是一次键比对就能判的
  事（`spec-00009-FR-4` 末句的单条忽略），追加条目「不得声明 `cwd`」也只是
  对 `entries` 的一条键检查。
- `overrides.<name>` 可出现的键：`command`、`args`、`model`、`env`、
  `headless`——**没有 `cwd`**（出现即整层不合式，`spec-00009-AC-4.2`）。撤销
  一项覆盖 = 从该对象删掉那个键；对象为空时删掉整条。覆盖是**键级整体替换**，
  不做深合并：本地写了 `env` 就整个换掉项目层的 `env`，写了 `headless` 就整个
  换掉 `headless`——深合并会让「撤销」失去明确语义（撤到哪一层？）。唯一的
  空值形态：`overrides.<name>.headless: null` = **撤掉**项目条目的 headless
  声明（该条目退出答疑可选集，`spec-00009-AC-9.3` 的前提由此在面板可达；
  实施期据实补，T2 发现无此形态则 AC-9.3 只能手改文件）；其他键不接受
  `null`。
- `entries.<name>` 是一条完整条目减 `cwd`：`command` 必填，其余同 §3；合并时
  `cwd` 恒填 `docs`（`spec-00009-FR-3`）。与项目层同名、或同一名字同时出现在
  `overrides` 与 `entries` → 整层不合式（`spec-00009-FR-4` 第二十六轮补注的
  两项；那应写成 override）。
- `disabled` 是字符串数组、`default` 是**单个字符串**——形态上就只能有一个
  缺省，FR-4 的「缺省多于一条」由此是结构性不可能；`default` 非字符串按形态
  错处置（整层不合式）。两处的名字可指项目条目或追加条目；所指不存在 → 单条
  忽略并进 `notices`（与无所指的 override 同处置）；`default` 指向被禁用者 →
  整层不合式（`AC-4.6`）。

### 13.2 合并与有效列表

`agentSettings.ts`（新模块）导出一个纯函数

```
mergeAgents(project: AgentConfig[], local: LocalAgentSettings | null)
  → { agents: EffectiveAgent[], notices: Notice[] }   // 或抛 LocalSettingsError（整层不合式）
```

`EffectiveAgent = AgentConfig & { source: 'project' | 'local' | 'overridden', default: boolean }`。
步骤：① 项目条目按 YAML 键序为底；② 逐条应用 `overrides`——把项目条目的
原始键与覆盖键**浅合并后重新过 `readAgentEntry`**（§3 抽出的单条校验：
`{model}` 成对、`env` 形态、`headless` 声明……一律在合并结果上判，而不是各层
分别判——否则「项目层有 `{model}`、本地把 `args` 覆盖成没有」这种跨层错抓
不到）；③ 追加 `entries`（`cwd` 填 `docs` 后同样过 `readAgentEntry`）；④ 去掉
`disabled` 所指；⑤ `default` 所指移到首位；⑥ 为空 → 整层不合式。任一步的
校验错都以 `LocalSettingsError { message, at }` 抛出，`at` 形如
`overrides.claude.model` / `entries.codex-local.cwd`。

**读取点**：`EffectiveAgents`（同模块的一个小类）持有项目层数组与本地文件
路径，`current(): { agents, notices, error? }` **每次调用都重读文件**并合并——
文件几百字节、调用点少（会话受理、配置下发、设置面板的 GET/PUT），不做
mtime 缓存；文件不存在 = 空本地层；读失败、JSON 解析失败或合并抛错 →
`agents` 退为项目层、`error` 带原因（`spec-00009-FR-4`）；`console.warn` 只在
错误**出现或变化**时记一次（记住上一次的 `error.message` 比对），不随每次
受理重复。`SessionManager.options.agents` 与 `Annotations.options.agents`
（`annotations.ts:126`，今天同样是启动时冻结的数组）都从 `AgentConfig[]` 改为
`() => AgentConfig[]`——「不在启动时定格」（`decision-00017` §5 的站立约束）
在类型上就成立。

**整批一次解析**（`spec-00009-AC-5.5`）：统一提交的 `chooseAgents` 今天解析出
`AgentConfig` 后，`openThread` / `startIssues` 往下只传 **名字**，`server.ts`
再经 `SessionManager.start` 按名字重新解析——本轮起两层间传的是**已解析的
条目**：`openAsk` / `start` 增一个「预解析条目」入参形态（有它就不再查列表），
批内每条会话用的都是受理时那一份。追问同理但反向：`spec-00009-FR-9` 的
拒绝在 `resolveAgent` 里已成立——线程记录的名字不在当前列表 → 既有的
「不是有效列表中的 agent」拒绝；在列表但 `headless` 为空 → 既有的「未声明
headless 形态」拒绝——两条用户可见文案里的 "flow config" 随本轮改为
"effective agent list"（`sessionManager.ts`、`annotations.ts` 三处、
`sessionHistory.ts` 注释同改）。

### 13.3 保存

`PUT /api/settings/agents` 收本地层**全量**：① 形态校验（四个键的类型）；②
`mergeAgents(project, body)` 试合并——抛错即 422 `{error, at}`，不写盘；③ 写
`.whiteboard/agents.json`：`mkdir -p .whiteboard` → 写 `agents.json.tmp` →
`rename`——rename 原子，失败面上磁盘要么是旧文件要么是新文件，没有半写
（`spec-00009-AC-6.4`）；写入或 rename 任一步失败时**尽力删掉暂存文件**
（`unlink` 自身的错误吞掉，原错误照常抛出），不留 `agents.json.tmp`；写失败
500，不改内存里任何东西（本来也没有——有效列表按次重读）。④ 返回新的 `effective` 与 `notices`。**不广播
`/api/events`**：变更推送的三个来源不加第四个（`decision-00017` §2 第 8 条），
发起保存的页面用返回值就地更新选择器，其他页面下次重新加载见新列表。

### 13.4 进程环境与模型注入

- `env` 合成：`{ ...process.env, ...agent.env }`，两个 seam 同一函数
  `childEnv(agent)`。不过滤键名（`PATH` 可被覆盖，`spec-00009` §7 已接受）。
- `{model}`：`fillModel(argv, model)` 对每个元素做全局子串替换；`args` 在
  `launchTerminal` 里过它；headless 在 `headlessArgs` 的同一遍正则里连同
  `{question}` / `{session}` 一起替（§10.1）。无 `model` 的条目校验已保证数组
  里没有占位，函数对此是恒等。
- 会话历史（`.whiteboard/sessions/<id>.json`）的 `agent` 字段仍记**名字**；
  快照（§5）只活在运行中的会话对象里，不入历史——历史回答「用了哪条」，
  不回答「那条当时长什么样」。

### 13.5 接入验证纪律的适用面

§3 / §10.1 / §11.5 的「未实测不进默认配置」只约束**项目层**（模板自带的
`whiteboard.config.yaml`）。本地层是用户机器上的用户选择，白板不验、也不
拦；设置面板在追加条目旁呈现「未经写域校验」的说明即是全部（design-00002
§18）。模板自带的 `claude` 条目要加 `model` / `{model}`，仍须先对当前 claude
版本实测 `--model` 在交互与 `-p` 两种形态下生效，实测记录写进 YAML 注释——
与既有条目同一纪律。

## 14. 配置排除——扫描范围与 `exclude` 契约（第二十七轮）

承载 `spec-00010-FR-1` … `FR-3`、`FR-11`、`FR-12` 的服务端；取舍全部在案于
`decision-00018`。界面侧（目录组）零服务端改动，归 design-00002 §19。本节只
改两处代码——流程配置多读一个键、`listDocFiles` 多过一遍过滤——其余全部是
「图上没有即不存在」的既有推导自然带出的后果，逐条点名以免被当成未做。

### 14.1 `exclude` 字段契约

```yaml
exclude: []                 # 第二十七轮（spec-00010-FR-1）：相对 docs/ 的 glob 列表，命中的文件对白板不存在
# exclude:
#   - reference/*/source/**   # 供应商文档镜像
```

- **读法**（`readExclude(raw): string[]`，与 `readMaxSessions` 同一层）：
  `undefined` 与 `null` 都读作缺失 → `[]`（`spec-00010-AC-1.7`）；不是数组
  （标量、映射）→ `ConfigError`「`exclude` must be a list of strings」
  （`AC-2.1`/`AC-2.4`）；逐项校验，错误位置点名 `exclude[<i>]`：非字串
  （`AC-2.2`）、空串（`AC-2.5`）、含 `\`（`AC-2.9`，说明模式一律用 `/`）、以
  `/` 起头（`AC-2.7`）、以 `!` 起头（`AC-2.8`，说明不支持取反）、任一段为
  `..`（`AC-2.6`）——任一违规即拒绝启动，与 `carries` 的逐项点名同口径。
  `FlowConfig` 增 `exclude: string[]`。
- **只在启动时读取**：`loadFlowConfig` 是唯一读点、`FlowConfig` 经构造函数传进
  `Board`/`DocService`（`server.ts`），`DocsWatcher` 只看 `docsDir`——这与其余
  字段完全一致，`spec-00010-AC-1.12` 只是把既有事实钉住，不加热重载。
- **不下发**：`GET /api/config` 不带 `exclude`。页面没有消费者——目录组的归组
  与折叠只读节点的 `path`；`FR-56` 的单一来源原则只约束有前端消费的键。需要时
  再加，不预留。**机制**：今天 `server.ts` 的该路由是 `res.json({ ...config,
  agents, … })`——整份 `FlowConfig` 展开下发，`FlowConfig` 一加字段它就跟着出去；
  故路由改为剔除 `exclude`（与已被替换的 `agents` 同一处），前端
  `ConfigPayload` 的 `Omit<FlowConfig, 'agents'>` 同步改为
  `Omit<FlowConfig, 'agents' | 'exclude'>`，`server.test.ts` 的 `GET /api/config`
  用例加一条 `body.exclude` 为 `undefined` 的断言钉住。

### 14.2 匹配与扫描

- **匹配器取 `node:path` 的 `path.posix.matchesGlob`**（Node ≥ 22.5；本机
  Node 26 实测无实验警告）。选它而不引 `picomatch`/`minimatch` 的理由：零依赖，
  且实测语义正是 `spec-00010-FR-1` 钉的那一套——`*` 不跨 `/`、`**` 跨任意层、
  目录形态的模式不命中其下文件（`matchesGlob('reference/stripe/a.md',
  'reference/stripe')` 为 false）、区分大小写。**显式用 `posix` 变体**：
  `path.win32.matchesGlob` 对大小写与分隔符的读法不同，而 `listDocFiles` 已把
  路径归一为 `/` 分隔（`entry.split(/[\\/]/).join('/')`），匹配必须在归一化
  **之后**、用 posix 语义进行（spec §7 第三条）。落地前以 `AC-1.8`/`AC-1.9`
  钉住这两个语义点，防 Node 升级改口。`tools/whiteboard/package.json` 今天没有
  `engines`，仓内也没有任何 Node 地板的声明；本轮同时声明
  `engines.node: ">=23.6"`——原生 TypeScript 剥离（`bin/whiteboard.js` 直接
  跑 `.ts`）已隐含这个地板，`matchesGlob` 的可用性从此有一处可查。
  `@types/node` 今天是 `^22.15.3`（22.20.1 已带 `matchesGlob` 的类型，typecheck
  不受阻）；与地板对齐到 24 系随 plan-00026 T2 一并做，不在本文档承诺版本号。
- **过滤点**：`listDocFiles(docsDir, exclude)` 今天的链是 `.filter(.md 且非
  模板文件) → .map(归一化为 '/') → .sort()`。glob 过滤**加在 `.map` 之后**——
  `.filter(rel => !exclude.some(pattern => posix.matchesGlob(rel, pattern)))`
  ——不能并进第一个 `.filter`：那一步拿到的还是原始分隔符的路径，Windows 上正是
  反斜杠。两套过滤因此分处归一化前后、互不替代（`AC-1.4`）。`readGraph` 把
  `config.exclude` 传下去；`DocService` 不需要知道排除的存在。
- **命中为空或命中全部都不是错误**（`AC-1.10`/`AC-1.11`）：过滤器不计数、不
  提示；空板走既有的空态路径（`spec-00001-AC-1.4`）。这是 `decision-00018` §4
  明写接受的「远距离作用」，不在服务端补提示。

### 14.3 下游后果——全部由「图上没有」推导，无新代码

被排除的文件不进 `listDocFiles`，于是从未成为 `ParsedDoc`，以下每一条都是
既有代码在新输入上的行为，本节只点名、不改动：

| 后果 | 载体 | 验收 |
| --- | --- | --- |
| 不成节点、不进 `issues`/`diagnostics`，命令面板检索不到（`matchDocuments` 只扫节点） | `readGraph` | `AC-1.1`、`AC-1.5` |
| 其文档 id 与条目 id 不在 `knownIds`/`itemOwners`/`idOwners` 中：指向它的关系边断链、归属声明方；行内 id 不可点击 | `toEdges`、`itemOwners`、§7 `idOwners` | `AC-3.1`…`AC-3.4` |
| 与可见文档同 id 不构成撞 id（撞 id 判定只在 `docs` 数组内两两比对）；两个被排除文件同 id 无任何异常 | `readGraph` 的 duplicate 判定 | `AC-11.1`、`AC-11.2` |
| 新建取号只计可见文档：`highestNumber(graph, type)` 读图上节点的声明 id | `workflow.ts` `allocateNumber` | `AC-12.1`、`AC-12.2` |
| 文件变更触发 watcher → 重读 → 图相同 → 推送一次内容不变的刷新 | `DocsWatcher` | `AC-1.3` |
| 共写收口：写进被排除路径的候选 `nodeAt` 为 `undefined` → `malformed` 返回「it is no document the board can read」→ 删除（复原） | `docService.ts` 收束、`cowrite.ts` `judgeReferences` | `AC-1.13` |

最后一行是域主 2026-09-03 的裁定（`decision-00018` §5）：**沿用现状**，共写
会话不该往语料目录写，写了即越界，删除即复原。§11.3 第 2 款原地加注。

「文件变更 → 一次内容不变的刷新」这一行是有意留下的：`AC-1.3` 断言的是**图**
不变，不是「不推送」。在 watcher 里预判路径是否被排除能省一次推送，但要把
`exclude` 传进 `DocsWatcher`、并复制一份匹配逻辑；一次空刷新的代价（去抖后一
次 `readGraph`）远低于两处匹配。

### 14.4 对 `rule-00001-BR-18` 与 `docs/README.md` 的追注

取号与撞 id 都以白板可见文档为准（`spec-00010-FR-11`/`FR-12`），
`rule-00001-BR-18`「现有最大编号」与 `docs/README.md`「全仓唯一」两处随
spec-00010 接收已追注。**已知边界**（`decision-00018` §4）：一份带 id 的被排除
文件与新建文档可能在磁盘上同号而白板不报；`DocService.create` 与
`DocService.newCowriteDoc`（新建的共写形态，同一守卫）对规范路径的 `existsSync`
拦截（`ConflictError`）仍在，故被排除文件恰在规范路径上时新建会被拒，窗口只剩
非规范路径的那部分。不加任何为被排除文件单独扫盘的代码——那就是
「不存在」的第一条例外，站立约束禁止。

### 14.5 测试与配置

- `config.test.ts`：`AC-2.1`…`AC-2.9` 各一例，`AC-1.6`/`AC-1.7` 各一例。
  `server.test.ts`：`GET /api/config` 不带 `exclude`（§14.1）。
- `docRepository.test.ts`（或 `docService.test.ts` 的读图用例）：`AC-1.1`…
  `AC-1.5`、`AC-1.8`…`AC-1.11`、`AC-3.1`…`AC-3.4`、`AC-11.1`/`AC-11.2`；
  `AC-1.12` 以「同一 `docsDir`、两份 config 各起一个 `DocService`」写，不模拟
  重启。
- `workflow` 取号用例：`AC-12.1`/`AC-12.2`。`cowrite.test.ts`：`AC-1.13`。
- 本仓 `whiteboard.config.yaml` 本轮就写入 `exclude: []` 与注释——与 §3 写入
  `carries` 时同一理由：`parseFlowConfig` 只读认识的键，落地前这一行被忽略、
  不拦启动；落地后它就是空排除。

## 15. Open Questions

- 本文档当前无未决项（第二十一轮的取舍全部由 decision-00012 在案；
  接续失效的出路已由域主裁定取「诚实标注」并回写 §10.2，2026-08-26；
  claude headless 声明的参数按 §10.1 的实测门落地；第二十二轮的取舍
  全部由 decision-00015 在案，共写的接入验证按 §11.5 的实测门落地；
  第二十三轮委给本文档的三项——锚的数据形态、恰一处命中的判定细则、
  统一提交 API 的语义——已在 §12 逐项落定，随本轮评审一并接受或推翻，
  未留待定项；第二十六轮的取舍全部由 decision-00017 在案，委给本文档的
  本地层文件形态与读取点已在 §13 落定；第二十七轮的取舍全部由
  decision-00018 在案，`exclude` 的匹配器选型与三处下游后果已在 §14 落定）。
