---
id: issue-00023-annotation-store-defaults-stand-in-for-state
type: issue
status: resolved
blocks: [plan-00023-doc-annotations]
---

# Issue: 标注存储以缺省值代替状态——损坏文件读作空列表，无改动回合照旧落盘

> 标注存储的读侧把"文件读不出"折进"没有标注"（`GET` 呈现零标注、统一
> 提交答"无未提交标注"），而写侧对同一份损坏文件正确拒绝——读写对同一
> 状态各说各话，且读侧的谎言会诱导用户重建标注、覆盖仍可手工抢救的
> 数据；同一处不节制还让**任何**手工共写的收束为该文档凭空建一个空的
> `.whiteboard/annotations/<docId>.json`。plan-00023 T4 review 期间确证。

## 1. Problem

- Observed:
  - （B，主缺陷）`.whiteboard/annotations/<docId>.json` 损坏（截断、非
    JSON、缺 `annotations`/`batches` 数组）时，`GET /api/annotations/:id`
    答 200 空列表、`POST …/submit` 答 422 `empty-submit`；而同一文件上的
    `POST`/`PATCH`/`DELETE`/批回填一律拒绝并说"cannot be read"。
  - （A）对一份**从无标注**的文档手工发起共写，会话收束时盘上多出一个
    `{"annotations":[],"batches":[],…}` 的空文件。
- Expected:
  - 存储读不出时读侧与写侧同一口径：明确报错，绝不冒充"空"
    （`spec-00007-FR-3` 的"未提交标注保留"以该文件为唯一副本；把它读成空
    等于对用户宣布标注不存在）。
  - 一份没有标注的文档不产生标注文件（`spec-00007-FR-3`/`FR-11`：存储
    按文档 id 键控、只为有标注的文档而存在；`spec-00007-FR-8` 的"与手工
    发起的共写无行为差别"亦要求共写收束不因标注能力多出副作用）。
- Trigger:
  - B：任何写坏该文件的途径（进程被杀在 rename 之前的极端窗口、人工
    编辑、外部工具）。T3 第二轮把写侧的静默 return 改成拒绝
    （`landBatch`）之后，读写不对称成为可观察缺陷。
  - A：任何手工共写会话的正常结束——`onSessionEnd` 一律经
    `Annotations.landBatch` 落到该文档的存储写路径。

## 2. Impact

- Affected:
  - B：任何标注存储损坏的文档。用户看到"没有标注"，据此重新加注并提交
    ——第一次成功的写盘就把损坏文件整份覆盖，原本可由人打开文件手工
    抢救的内容（标注文本、引用）随之消失。这是本 issue 判定为高的唯一
    理由：它把一次可恢复故障变成不可恢复。
  - A：全部手工共写的目标文档，每次收束一个空文件。无数据损坏，但
    `.whiteboard/annotations/` 会积累与标注无关的文件，且违反"没有标注
    就没有文件"的存续口径。
- Since: plan-00023 T3 实现落地（本轮，尚未 commit）· Still occurring: no
  （本 issue 已修）。
- Severity: B 高（诱导覆盖仍可抢救的数据）；A 低（噪声与口径违反，无
  数据损失）。两者同因，一并修。

## 3. Root Cause (first principles)

1. 分歧陈述：
   - B：同一份损坏文件，写路径（`annotationStore.ts:395` `startWrite`）
     判为"读不出、拒绝"，读路径（`annotationStore.ts:190` `read`）判为
     "空列表、放行"。一个文件，两个答案。
   - A：一个**什么都没改**的回合（`landBatch` 在该文档找不到任何批）与
     一个真改了的回合，在写路径上得到同一处置：落盘。
2. 最小机制：两处都以同一个缺省值收口——
   `return this.load(docId) ?? empty(docId)`（`read` 与 `startWrite` 各
   一份），以及 `annotationStore.ts:380` `write()` 里无条件的
   `this.save(list)`。`empty()` 既被用作"这份文档还没有标注"的**合法
   状态**，又被用作"读不出时的兜底"，而 `save()` 被用作"回合结束"的
   同义词而非"内容变了"的结论。
3. 真正的根因：**缺省值代替了状态。** 存储层必须分辨三种文件状态
   （没有文件 / 有文件但读不出 / 有文件且可读）与两种回合结局（改了 /
   没改），而实现只有一个 `empty()` 缺省和一个无条件写：读侧把"读不出"
   折进"没有"（B），写侧把"没改"折进"改了"（A）。
   它**不是**"忘了判存在性"，也**不是**"JSON.parse 该包得更细"——两者
   都只是这一处折叠的表征；也不是并发或队列缺陷（同 docId 串行队列
   工作正常）。
- Introduced by: plan-00023 T3 的首轮实现（尚未 commit）。存储按 §12.1
  的"与问题列表同侧同构"照 `askStore` 抄形态时，把它的**宽容读**一并
  抄来——而 `askStore` 的宽容有 design-00001 §10.2 的明文裁定，标注侧
  没有任何裁定要求它；`write()` 的无条件落盘同轮写下。在该实现之前，
  这段代码不存在，缺陷无从发生。

## 4. Scope (same-cause sweep)

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `tools/whiteboard/src/annotationStore.ts:190` `read()` | 是——`?? empty()` 兼作兜底 | 是 | 本 issue 修：读不出即拒绝，与写侧同一错误 |
| `tools/whiteboard/src/annotationStore.ts:380` `write()` | 是——无条件 `save()` | 是 | 本 issue 修：回合无改动即不落盘 |
| `tools/whiteboard/src/annotationStore.ts:395` `startWrite()` | 是——同一 `?? empty()` 收口 | 否 | 已对"读不出"拒绝；对"没有文件"取空列表是正确的——新建的第一次写盘正是从空开始 |
| `tools/whiteboard/src/annotationStore.ts:336` `reconcile()` | 无条件 `save()` | 否 | 已有 `if (running.length === 0) continue`，无改动即跳过；不可读的文件亦跳过 |
| `tools/whiteboard/src/askStore.ts:117` `read()` | 是（同形宽容读） | 不在本 issue 范围 | design-00001 §10.2 对答疑列表**明文裁定**宽容读（"a file that will not parse costs the user that list and not the board"），且属 `spec-00005` 既有行为与既有测试面。同形风险据实登记，是否比照收严归域主 |
| `tools/whiteboard/src/askStore.ts:179` `finish()` | 无条件 `save()`？ | 否 | `if (!thread || !exchange) return` 先返回，找不到线程不落盘——同因不成立 |
| `tools/whiteboard/src/askStore.ts:223` `reconcile()` | 同上 | 否 | 有 `if (running.length === 0) continue` |

## 5. Reproduction (test-first)

三条先写后修，各自因其缺陷本身而红：

- B（存储层口径）：`tools/whiteboard/test/annotationStore.test.ts::refuses to
  read a file it cannot parse, the way a write refuses it` —— 修前 `read()`
  返回空列表，断言"抛 `WorkflowError`"失败。
- B（服务层与呈现）：`tools/whiteboard/test/annotations.test.ts::refuses to
  serve a list and to submit it when the stored file cannot be read` ——
  修前 `list()` 答空列表、`submit()` 答 `empty-submit`。
- B（HTTP 口径）：`tools/whiteboard/test/server.test.ts::answers 422 naming
  the file when the stored annotations cannot be read` —— 修前 GET 答 200
  空载荷。
- A（存储层）：`tools/whiteboard/test/annotationStore.test.ts::writes no file
  at all for a turn that changed nothing` —— 修前 `landBatch` 在无文件的
  文档上建出空文件。
- A（端到端，报告形态）：`tools/whiteboard/test/server.test.ts::leaves no
  annotation file behind for a cowrite nobody annotated` —— 修前手工共写
  收束后 `.whiteboard/annotations/<docId>.json` 存在。

## 6. Fix

- Change（`tools/whiteboard/src/annotationStore.ts`）：
  1. 读不出即拒绝——`read()` 不再吞 `load()` 的异常；两条路径的拒绝出自
     **同一个**私有 `unreadable(docId, cause)`，因此同类同文案（`WorkflowError`
     → 422，消息点名文件路径，便于人去抢救）。"没有文件"照旧读作空列表：
     三种状态各归各。
  2. 回合无改动即不落盘——`write()` 比较回合前后的序列化，只在内容真的
     变了时 `save()`。一处收口，覆盖全部调用者（`landBatch`、`blockEach`、
     `update` 的空转回合都不再建文件）。
- Why this addresses the root cause and not the symptom: 两处修改各消掉
  §3 的一次折叠——缺省值退回"只表示没有文件"，落盘退回"只表示内容变了"。
  不是给 `landBatch` 加一条"文件不存在就别写"的特判（那只修 A 的一个
  调用点，`blockEach` 等空转回合照旧建文件），也不是给 `read()` 加一层
  "损坏就再试一次"。
- Alternatives rejected：
  - 读侧继续宽容、只在 UI 上提示——读侧的返回值是"零标注"，界面无从
    分辨"真没有"与"读不出"，而覆盖风险恰在此处。
  - 让 `landBatch` 单独判存在性——见上，漏其余空转回合。

## 7. Verification

- §5 的五条测试全部转绿；各自修前红的原因见 §5。
- 门禁（服务端侧）：`tsc --noEmit` 对 `src/`+`test/` 零错误；
  `vitest run test/ --exclude 'web/**'` 24 文件 / 936 测试全过；覆盖率
  Statements 99.37% · Branches 96.17% · Functions 99.55% · Lines 99.63%，
  `annotationStore.ts` 单文件 99.19 / 95.74 / 100 / 99.07。
- 界面侧同轮由 plan-00023 T4 在跑，其 `web/test/annotationMapping.test.tsx`
  与 `annotationMarks.ts` 处于编辑中（3 红 + 3 处 typecheck），与本 issue
  无关、不在本轮修复面内。
- 端到端：`leaves no annotation file behind for a cowrite nobody annotated`
  以真实路由发起并结束一次手工共写，断言 `.whiteboard/annotations/`
  目录根本不存在。

## 8. Follow-through

- Detection gap：T3 的测试只断言了读侧的**宽容**（`refuses to write over a
  file it cannot read` 里顺手断言 `read()` 返回空列表）——把抄来的假设当
  成了要求，于是没人问过"读写对同一损坏是否同一口径"；也没有任何测试
  问过"一份没有标注的文档，会话结束后盘上有什么"。本轮补的守护面即这
  两问：存储层各一条，加 HTTP 与端到端各一条，把口径与文件存续钉住。
- Doc verdict：
  - A：**code was non-conformant**，docs 不改——"存储按文档 id 键控、
    为有标注的文档而存在"已由 `spec-00007-FR-3`/`FR-11` 与 design-00001
    §12.1 承载，实现没做到。
  - B：**design 缺一句**——design-00001 §12.1 以"与 §10.2 的问题列表
    同侧同构"一句带过存储机制，而 §10.2 的"宽容读"是那一份的明文裁定，
    §12.1 未就标注侧表态，实现遂照抄。据实补一句（读不出即拒绝、读写
    同一口径、422），由协调方落笔；本 issue 的裁定见其 §6。
- Residual state：本仓 `.whiteboard/annotations/` 当前为空，无遗留空
  文件；他处已由缺陷 A 建出的空文件无害且自愈（内容即"无标注"，下一次
  真实写盘照常覆盖），可由人直接删除，无需迁移。

## Links

- Blocks: [plan-00023-doc-annotations](../plan/plan-00023-doc-annotations.md)（T3 服务端实现，T4 review 期间确证）
- Spec: [spec-00007-doc-annotations](../spec/spec-00007-doc-annotations.md)（`FR-3` 存续与唯一副本、`FR-11` 存储口径、`FR-8` 共写无行为差别）
- Design: [design-00001-docs-whiteboard](../design/design-00001-docs-whiteboard.md)（§12.1 存储形态；§10.2 是被照抄的那份裁定）
