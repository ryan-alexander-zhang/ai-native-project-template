---
id: issue-00008-advance-commits-unrelated-dirty-docs
type: issue
status: resolved
blocks: [spec-00001-docs-whiteboard]
---

# Issue: 推进会话退出时，把 docs/ 下所有既有脏文件卷进会话 commit

> agent 一个字没写，会话退出却提交了 8 个文件、291 行——全是会话启动**之前**
> 就在工作区里的别人的活，commit 信息还署名 `wb(advance)`。

## 1. Problem

- Observed: 在 docs/ 有未提交改动的工作树上发起推进，agent 未产出任何文件即
  退出；会话仍报 `{"committed": true}` 并产生
  `wb(advance): prd-00001-docs-whiteboard`（8 files, +291），内容全部是会话前
  的既有脏文件（decision/plan/README/spec 等）。
- Expected: `spec-00001-FR-14`「只暂存本次动作涉及的文件」；design-00001 §4
  明文「commit 时暂存 docs/ 下**自会话启动以来**的全部变动路径（相对**会话前
  快照**）……会话启动之前已存在的脏文件不会被暂存」。
- Trigger: 任何在 docs/ 脏工作树上运行的推进会话——本仓的常态工作方式
  （plan-00007 实测 (c) 当场复现，误提交已 `git reset --mixed HEAD~1` 还原）。

## 2. Impact

- Affected: 推进（advance）动作的留痕正确性——无关工作被归因到错误的动作与
  文档 id 下，git 历史失真；若使用者不核对，半成品文档会被悄悄提交。
- Since: MVP 的 advance 实现起。Still occurring: no（本 issue 已修）。
- Severity: 高——留痕（S5）是本产品的核心承诺之一，且触发条件是常态而非边角。

## 3. Root Cause (first principles)

1. 期望「会话启动后的增量」，实际「commit 时刻的全部脏文件」。
2. 机制：`tools/whiteboard/src/gitLayer.ts:38-41` 的 `changedPaths(dir)` 只取
   `git status` 当前值过滤 `docs/` 前缀——**没有任何会话前快照**，无从计算
   增量。
3. 真根因：设计写了快照语义，实现只做了过滤语义。不是 simple-git 的问题，也
   不是「外部改动无法区分」那个已声明的边界（那说的是会话**期间**的改动；
   会话**之前**的脏文件设计明文承诺不暂存）。

- Introduced by: advance 首版实现（plan-00001 轮）。此前无会话即无此路径。

## 4. Scope (same-cause sweep)

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `gitLayer.ts` `changedPaths`（仅 advance 调用） | yes | yes | 在此修 |
| 编辑/状态切换/接收/澄清的 commit | 显式传入单文档路径，不经 `changedPaths` | no | 不涉及 |

## 5. Reproduction (test-first)

1. 失败测试：会话启动前在 docs/ 放一个脏文件，会话产出另一文件后退出，断言
   commit **只含**会话产出——现实现下该测试以「脏文件也在 commit 里」失败。
2. 修复方向：会话启动时记录 `git status -- docs/` 快照，结束时取差集（即
   design-00001 §4 的原话落地）。
- Failing test（先于修复跑红，输出即本 issue 的现场）：
  `test/docService.test.ts` 的
  `commitSessionChanges > leaves a file that was dirty before the session out of the commit`

  ```
  AssertionError: expected [ 'docs/prd/a.md', 'docs/spec/new.md' ]
                  to deeply equal [ 'docs/spec/new.md' ]
  ```

## 6. Fix

- Change（plan-00008 W1）：`GitLayer.snapshot(dir)` 取快照（已脏 `docs/` 路径
  + sha256 摘要），`GitLayer.changedSince(dir, before)` 以
  `before.get(path) !== digest(path)` 一次比较落 design-00001 §4 的三种处置；
  差集为空则不 commit。快照的生命周期归 Session Manager：**在 spawn 之前
  同步取**——异步取会与 agent 的写盘赛跑，把会话自己的产出误记为「本来就脏」
  而排除掉。
- Why this addresses the root cause and not the symptom: 缺陷是「没有会话前的
  参照系」，本修复给出参照系并按内容比较；不是给暂存集再加一层过滤规则。
- 按内容而非按路径集求差的理由见 design-00001 §4：路径集差会把「会话在一份
  本来就脏的草稿上继续改」的产出误排除，而那正是本仓最常见的推进形态。
- 检测缺口的更正：初稿写「`AC-14.2` 的夹具只用了 docs/ 之外的脏文件」——**这是
  错的**，实查 `test/docService.test.ts` 的脏文件是 `docs/idea/b.md`，本就在
  docs/ 之内；AC-14.2 之所以绿，是因为编辑动作走显式路径暂存、根本不经过
  `changedPaths`。真正的缺口是：**advance 这条路径上从来没有过「会话启动前已有
  脏文件」的用例**——`AC-14.5`/`AC-14.6` 补的正是它。

## 7. Verification

- §5 的回归测试通过；另有 `AC-14.5`/`AC-14.6` 各两条（单元 + 完整会话生命
  周期）、以及「会话在别人的脏草稿上继续改 → 该文件**应当**被暂存」一条守住
  差集的第三支。全套 557 测试绿，`test/acceptance.test.ts` **一字未改仍通过**
  ——它正是「快照取在会话启动时」的证据。
- 现场复验（plan-00008 实测 (d)，`record-00007` 存档）：在有 6 份脏 `docs/`
  文件的工作树上发起推进并立即终止会话 → **无 commit**、HEAD 未动、暂存区空、
  6 份脏文件 sha256 逐一不变。且服务端在找不到产出时**仍会调用**
  `commitSessionChanges`，故本次确实走到了暂存范围这段逻辑，是差集把它们全数
  排除，而非跳过了提交路径。

## 8. Follow-through

- Detection gap: advance 的 commit 范围从来只被「会话产出都在 commit 里」
  （AC-14.4）这一侧覆盖，没有任何用例问过「不该在里面的东西是否被排除」——
  一个只测正向、不测反向的验收面。AC-14.5/AC-14.6 即补上的反向守卫。
  （初稿把缺口误记为 AC-14.2 的夹具窄，见 §6 的更正。）
- Doc verdict: **code was non-conformant**——FR-14 与 design-00001 §4 的文本
  无误且互相一致。
- Residual state: 本次误提交已当场 `git reset` 还原，工作树逐行核对与基线
  一致——none。

## Links

- Blocks: spec-00001-docs-whiteboard（FR-14 的留痕承诺）
- Related: record-00006（发现现场）、plan-00007（实测 (c) 期间发现）
