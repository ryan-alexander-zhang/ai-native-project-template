---
id: record-00007-whiteboard-refresh-and-commit-scope-acceptance
type: record
status: active
parent: plan-00008-whiteboard-refresh-and-commit-scope
verifies: [spec-00001-FR-42, spec-00001-FR-43, spec-00001-FR-44, spec-00001-AC-14.5, spec-00001-AC-14.6]
---

# 验收记录：变更推送与会话暂存范围

对 [plan-00008-whiteboard-refresh-and-commit-scope](../plan/plan-00008-whiteboard-refresh-and-commit-scope.md)
的验收——两个「设计写了、实现没做」的缺口：
[issue-00007](../issue/issue-00007-the-board-never-hears-about-disk-changes.md)
（推送刷新通道，域主裁定取补通道）与
[issue-00008](../issue/issue-00008-advance-commits-unrelated-dirty-docs.md)
（会话暂存范围），两者均已 `resolved`。

- 套件：`cd tools/whiteboard && npm test` → **29 个测试文件、557 个测试全部
  通过**（plan-00007 验收时为 518，净增 39）
- 覆盖率：语句 99.14%、分支 95.21%、函数 98.51%、行 99.63%（门槛 90%）；
  新增的 `watcher.ts`、`eventSocket.ts` 满覆盖
- 类型检查与构建：`npm run typecheck` 无错误；`npm run build` 通过
- 契约测试 `test/contract.test.ts`（真实 `docs/` 零诊断）保持常绿
- 连跑 5 次无 flake
- GWT 核验由未参与实现的 subagent 完成：22/22 有对应通过的测试，六项抽查
  （快照时序、内容差集的第三支、合并不依赖墙上时钟、缓冲区两侧断言、
  AC-44.3 走的确是动作通路、AC-43.3/43.4 是两种不同状态）全部非恒真

测试名为 `tools/whiteboard/` 下的用例标题：`t/` = `test/`，`w/` = `web/test/`。

## 新增 GWT

| GWT id | 测试 | 结果 |
| --- | --- | --- |
| spec-00001-AC-14.5 | leaves a file that was dirty before the session out of the commit (t/docService)；commits the product and leaves the dirt the session started from behind (t/server) | pass |
| spec-00001-AC-14.6 | makes no commit when only the inherited dirt is there (t/docService)；makes no commit when a session on a dirty tree produces nothing (t/server) | pass |
| spec-00001-AC-42.1 | signals a document written under docs, and one deleted (t/server)；brings a new document onto the graph with no user action (w/refresh) | pass |
| spec-00001-AC-42.2 | shows the new status of a document changed elsewhere；re-reads the items of the document on show (w/refresh) | pass |
| spec-00001-AC-42.3 | signals a document written under docs, and one deleted (t/server)；takes a deleted document off the graph (w/refresh) | pass |
| spec-00001-AC-42.4 | folds a burst of writes into a single signal (t/server)——断言 1 次信号 < 3 次变化且终态一致，不断墙上时钟 | pass |
| spec-00001-AC-42.5 | says nothing about a file outside docs (t/server)——并随后证明通道当时是活的 | pass |
| spec-00001-AC-42.6 | leaves an unsaved buffer and its cursor alone (w/refresh)——正文相邻可见且 `api.doc` 调用次数不变 | pass |
| spec-00001-AC-42.7 | signals what a running session writes, before it ends (t/server) | pass |
| spec-00001-AC-42.8 | carries on with no board connected at all (t/server) | pass |
| spec-00001-AC-42.9 | signals every connected board (t/server) | pass |
| spec-00001-AC-43.1 | keeps the whole graph and every control working (w/refresh) | pass |
| spec-00001-AC-43.2 | catches up on reconnection (w/refresh)；reports a connection as a change（通道层） | pass |
| spec-00001-AC-43.3 | draws the graph when the channel was never available at all；says nothing when the socket cannot be built at all (w/refresh) | pass |
| spec-00001-AC-43.4 | catches up again on the second reconnection (w/refresh) | pass |
| spec-00001-AC-44.1 | stays in the sub-canvas (w/refresh) | pass |
| spec-00001-AC-44.2 | keeps the detail on the same target (w/refresh) | pass |
| spec-00001-AC-44.3 | stays in the sub-canvas through a refresh the board itself caused (w/refresh)——全程未触发推送信号 | pass |
| spec-00001-AC-44.4 | comes back up to the board (w/refresh) | pass |
| spec-00001-AC-44.5 | closes the detail when its row is deleted (w/refresh)——详情关闭且子画布仍在 | pass |
| spec-00001-AC-44.6 | drops the selection and its toolbar (w/refresh) | pass |
| spec-00001-AC-44.7 | collapses an expanded row (w/refresh) | pass |

**FR-44 的「承接」约定已守住**：`AC-29.6`（选中跨刷新保持）与 `AC-38.5`
（展开行跨刷新保持）原文与其测试均未改动，新测试不重复覆盖它们，
record-00003 / record-00005 中引用这两条的验收行仍然有效。

另有非 AC 的护栏测试：退避 1s→2s→…→30s 封顶、连接后退避归零、close 后不再
拨号；`DocsWatcher` 的订阅者计数、重复 start、重复 close；gitLayer 的暂存
重命名与「在别人的脏草稿上继续写→应当暂存」两条 design-00001 §4 分支。

## 既有断言的预期更新（非回归）

按 plan-00008 验收路径第 3 条的三分类，实际发生的与预期一致：

- **第一类（预期变化，2 条）**：`t/docService` 中直接调 `commitSessionChanges`
  的两个用例（AC-14.4 用例与「skips the commit when the session changed
  nothing」）各插入取快照一步并补上新入参，断言逐字未动；后者保留（干净树
  场景），`AC-14.6` 是其脏树加强版，二者并存。
- **第二类（必须一字不改，已核实）**：`t/acceptance.test.ts` **完全未被修改**
  （`git diff --stat` 中不在改动列表内）——它是「快照确实取在会话启动时」的
  证据；`t/server` 的 advance 生命周期用例正文亦一字未动。
- **第三类**：`AC-14.2` 的夹具未动（该 AC 从无缺口，见 issue-00008 §6 的更正）。
- 三处测试基础设施改动（非用例正文）：`t/server` 的 `afterEach` 增加 watcher
  释放（否则数十个 board 的文件监听耗尽 fd）、两个文件设置 vitest 超时（原
  20s 等待本就被默认 5s 静默截断，属既存隐患）、`w/setup.ts` 增加惰性
  WebSocket 桩（白板挂载即开事件通道；驱动通道的测试自行覆盖它）。

## 实测核对（plan 验收路径第 4 条）

浏览器自动化实跑，全程仅由另一 shell 改动磁盘：

- **(a) 自动刷新——通过**。新增 **432 ms**、改 status **468 ms**、删除
  **306 ms** 内自动可见（FR-42 承诺 1 秒内），期间浏览器未接受任何输入。
  连续写入 6 份文档只触发 **1 轮**刷新（163 ms），终态与磁盘一致。抓到的
  请求序列为 `/api/graph` → `/api/docs/<当前文档>/items`，与「刷新范围含
  items」的要求相符。
- **(b) 呈现状态保住——通过**（分两组覆盖，原因见观察项 1）：选中 + 展开行
  经外部改动后全部保住；下钻（442 个子节点）+ 详情目标经刷新后面包屑不变、
  详情仍指同一目标，零错误提示。
- **(c) 就近关闭——通过**。详情目标所在的验收行被删 → **103 ms** 后详情关闭
  而子画布仍在（节点 442→441，正好少掉被删那行）；正在下钻的文档被删 →
  **377 ms** 后退回顶层、选中取消、工具栏关闭，**无白屏无报错**（顶栏出现的
  `48 issues` 是删掉被广泛引用的 spec 后真实的悬空引用计数，属正确呈现）。
  两份被删文件均以 sha 校验逐字节还原。
- **(d) 脏树推进不误提交——通过**（issue-00008 现场复验）。在有 6 份脏
  `docs/` 文件的工作树上发起推进并立即终止会话：**无 commit**、HEAD 未动、
  暂存区空、6 份脏文件 sha256 逐一不变、`git status` 前后 diff 为空。且服务端
  在找不到产出时仍会调用 `commitSessionChanges`，故本次确实走到了暂存范围
  逻辑——是差集把它们全数排除，而非跳过了提交路径。
- **(e) 断连与重连——通过**。杀掉服务后白板**不弹任何错误提示条**、33 节点
  与检视面板照常、缩放与命令面板可用；4 个断连-重连循环均自动重连，短断连
  **628 ms** 补齐、73 秒长断连 **17.7 s** 补齐（后者是 FR-43 规定的递增退避
  所致，非缺陷）。断连期间的磁盘变化均被补回。

收尾核对：`docs/` 已恢复原状（sha 校验）、557 测试与契约测试全绿、
`git status` 与基线逐行一致、无新 commit、端口与进程已释放。

## 观察项（不阻塞，留待后续）

1. **检视面板与「下钻/详情」在实现上互斥**（`Board.tsx`：`inspector` 仅在未
   下钻时呈现）——因此 FR-44 的四级保持无法在一次交互里同时观察，实测分两组
   覆盖。这与 design-00002 §9「详情面板占用右槽」的分工一致，不是缺陷，但
   「四项同时保住」这句话在 UI 上不可构造，日后措辞需注意。
2. **单次外部编辑偶发触发两轮刷新**：`sed` 一类「截断 + 写」的编辑会在 100ms
   去抖窗口外再投递一次事件（推测为 macOS fsevents 抖动）。无害（刷新幂等、
   呈现状态照常保住），但「一次编辑 = 一次刷新」并非严格成立。
3. **行为退化**：前端不再有独立的「会话结束即刷新」通路，FR-12 的那个时机
   现已依赖推送通道；推送不可用时，会话产出要等下一次动作或页面重载才可见。
   design-00001 §6 预告了这一点（三条通路共用一个实现），此处据实记档。
4. 服务端在握手完成与订阅注册之间有一个极短窗口，落入其中的变化会漏给刚
   连上的那个白板；因 FR-43 规定「连接建立即刷新」，实际不丢信息。
5. `t/acceptance.test.ts` 中连开两个 board 的用例各留一份文件监听未关闭
   （为保持「一字不改」未动它），每次运行泄漏两组 fd，无害。
6. 实测机上另有两个早于本次启动的白板服务进程在监听同一 `docs/` 树；日后在
   脏树上复验 (d) 时需确认它们没有在跑推进会话，否则提交来源会混淆。
7. macOS 上 chokidar 的 `ready` 早于系统真正开始投递事件，测试需用探针文件
   确认监听已就绪（helpers 的 `armWatch`）；真实使用中被「连接即刷新」兜住。

## 结论

22/22 GWT 通过、既有 AC 无回归（`t/acceptance.test.ts` 一字未改仍绿）、
四道质量门全绿；实测五项全部通过且数值远优于承诺（1 秒上界 vs 实测
306–468 ms）；issue-00007 与 issue-00008 均已 `resolved`。plan-00008 置
`resolved`。
