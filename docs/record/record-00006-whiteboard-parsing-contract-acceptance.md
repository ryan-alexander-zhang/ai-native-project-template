---
id: record-00006-whiteboard-parsing-contract-acceptance
type: record
status: active
parent: plan-00007-whiteboard-parsing-contract
verifies: [spec-00001-FR-40, spec-00001-FR-41]
---

# 验收记录：解析契约——AST、诊断与 agent 输出约束

对 [plan-00007-whiteboard-parsing-contract](../plan/plan-00007-whiteboard-parsing-contract.md)
的验收。架构取舍见
[decision-00005-whiteboard-parsing-contract](../decision/decision-00005-whiteboard-parsing-contract.md)。
实测期间发现的计划外缺陷见
[issue-00008](../issue/issue-00008-advance-commits-unrelated-dirty-docs.md)（open）。

- 套件：`cd tools/whiteboard && npm test` → **27 个测试文件、518 个测试全部
  通过**（plan-00006 验收时为 483）
- 覆盖率：语句 98.98%、分支 95.47%、函数 98.6%、行 99.59%（门槛 90%）
- 类型检查与构建：`npm run typecheck` 无错误；`npm run build` 通过
- **V1 完成定义达成**：AST 替换经双重验证——纯 V1（临时兼容层）跑既有套件
  483/483 一条不改全绿；新旧解析器对本仓全部 30 份真实文档逐字节差分完全一致
- GWT 核验由未参与实现的 subagent 完成：13/13 有对应通过的测试且断言实义；
  文法逐条勾对**零反向漂移**（README 写的与代码做的完全一致）；既有 AC 抽查
  未改弱；AC-33.1/33.3 的更名是加强不是改弱

测试名为 `tools/whiteboard/` 下的用例标题：`t/` = `test/`，`w/` = `web/test/`。

## 新增 GWT

| GWT id | 测试 | 结果 |
| --- | --- | --- |
| spec-00001-AC-40.1 | reports a checklist row written as a range, and keeps it out of coverage (t/requirements)；lists a malformed checklist row with its record and its source line (w/inspector) | pass |
| spec-00001-AC-40.2 | reports a line opening with a bold item id that is neither declaration shape (t/requirements)；lists a shape diagnostic under the document that holds the line (w/inspector) | pass |
| spec-00001-AC-40.3 | counts the parse diagnostics apart from the anomalies (w/canvas)；counts apart from the anomalies, which count apart from it (t/diagnostics) | pass |
| spec-00001-AC-40.4 | leaves the node sound and the other items' coverage untouched (t/diagnostics) | pass |
| spec-00001-AC-40.5 | parses with no diagnostic at all (t/contract，真实 docs/)；reports nothing when every document follows the grammar (t/diagnostics)；does not render the region at all when there is no diagnostic (w/inspector) | pass |
| spec-00001-AC-40.6 | drops the diagnostic and takes the rows once the range is expanded (t/requirements) | pass |
| spec-00001-AC-40.7 | reports a checklist row holding two ids in one cell (t/requirements) | pass |
| spec-00001-AC-40.8 | reports a bold rule id left in a single table cell (t/requirements) | pass |
| spec-00001-AC-40.9 | lists a criterion that names no item at all, and leaves it uncounted (t/requirements) | pass |
| spec-00001-AC-41.1 | carries the item grammar of a spec（另有 rule/record 两条）(t/advance) | pass |
| spec-00001-AC-41.2 | says nothing about an item grammar for a type that has none（idea/prd/design/plan 四型）(t/advance) | pass |
| spec-00001-AC-41.3 | reports a drifted declaration and commits the document anyway (t/server)——诊断呈现与 commit 照常两半均断言 | pass |
| spec-00001-AC-41.4 | adds no diagnostic and leaves the node sound when the body follows the grammar (t/server) | pass |

另有负例护栏（他文档 id 合式声明行、反引号引用、围栏代码块、缩进块——均不
诊断）与**契约测试** `t/contract`：对真实 `whiteboard.config.yaml` + 真实
`docs/`（30 份文档）全量解析，断言零诊断——核验期间它在实测夹具在位时如实
变红（逐条列出 docId/类别/行号/原文）、夹具删除后转绿，证明这条门禁是活的。

## 实测核对（plan 验收路径第 4 条）

- **(a) 零诊断常态——通过**。顶栏仅 `no issues`、诊断 Badge 缺席（不是显示
  零）；`/api/graph` 与 `/items` 的 diagnostics 均为空。
- **(b) 夹具诊断——通过，夹具形态照 FR-40 口径修正**：指令原拟把区间行放进
  spec 夹具，实测证实 spec 内的清单表本就不被扫描（FR-40 只扫 record 的验收
  清单）——故拆为 spec 夹具（形态残缺行）+ record 夹具（区间行）两份。在位时：
  顶栏 `no issues` 与 `2 diagnostics` 并存（计数独立）、诊断区两行各带来源
  id/类别/原文行、夹具节点保持正常节点、区间行不进覆盖；删除后诊断归零。
  夹具护栏全程成立：在位期间未跑 `npm test`，删除后契约测试转绿、
  `git status` 与基线逐行一致、无残留。
- **(c) 推进指令的文法段——通过（真实会话）**。对 prd-00001 发起推进（下一步
  spec），经会话终端通道的回放缓冲读到完整任务指令：两种声明形态、AC 归属
  必写、「散文与他文档 id 用反引号、粗体是声明形态」逐条在场（AC-41.1 的真实
  数据面）。注：内嵌 xterm 的可见面因嵌套 CLI 只渲染了 banner，属实测环境
  限制而非白板缺陷；证据取自同一 WS 通道的原始字节。

## 计划外发现（已按流程立 issue）

**issue-00008（open，严重度高）**：实测 (c) 期间，agent 未写任何文件而会话
退出即产生 `wb(advance)` commit，卷入 docs/ 下全部**会话前**既有脏文件（8 个
文件、291 行）——违反 FR-14「只暂存本次动作涉及的文件」，与 design-00001 §4
「相对会话前快照」的承诺不符。根因已定位（`gitLayer.changedPaths` 无快照、
仅前缀过滤）；检测缺口是 AC-14.2 的测试夹具只用了 docs/ 之外的脏文件。误提交
已当场 `git reset` 还原、基线逐字节复核一致。修复待下一轮。

## 观察项（不阻塞，留待后续）

1. 四个「已实现、缺专项负例测试」的文法点：测试/结果列不得是首列、中文「证据」
   列名的正向用例、列表项缺 `(EARS)` 标注不诊断、行中粗体 id 不诊断。
2. 诊断行号是**正文行号**（不含 front matter 偏移）；UI 不显示行号，无 AC
   要求文件行号，类型注释已写明。
3. 缩进的粗体 id 行按文法「整行起头」的字面既不算声明也不进诊断——这类漂移
   仍无声；要更响需域主放宽启发式（代价是假阳性面变大）。
4. 流程配置中 `record` 不是任何类型的下一步，其文法段目前仅单元测试层可达。
5. `Diagnostic` 载荷比 design-00001 §7 所列多一个 `attributedTo` 字段
   （AC-33.3 的呈现需要）；design 用「至少含」措辞，不构成冲突。

## 结论

13/13 GWT 通过、契约测试常绿且被证明是活的、文法与实现零漂移、既有 518 测试
全绿；实测三项通过、护栏成立；计划外缺陷 issue-00008 已立案待修。plan-00007
置 `resolved`。
