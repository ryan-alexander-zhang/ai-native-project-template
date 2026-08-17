---
id: issue-00008-advance-commits-unrelated-dirty-docs
type: issue
status: open
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
- Since: MVP 的 advance 实现起。Still occurring: yes。
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
- Failing test: 待修复轮按上式落地。

## 6. Fix

- Change: 待修复轮（快照 + 差集）。
- 顺带修检测缺口：`AC-14.2` 的 Given 是「与本次动作无关的脏文件」，但现有测试
  夹具只用了 docs/ **之外**的脏文件，恰好绕开本缺陷——补 docs/ 之内的用例。

## 7. Verification

- 待填。

## 8. Follow-through

- Detection gap: AC-14.2 的测试夹具口径窄于 AC 文本（docs 外 vs docs 内），
  绿灯掩盖了缺陷两个多轮次；修复轮补 docs 内夹具即回归守卫。
- Doc verdict: **code was non-conformant**——FR-14 与 design-00001 §4 的文本
  无误且互相一致。
- Residual state: 本次误提交已当场 `git reset` 还原，工作树逐行核对与基线
  一致——none。

## Links

- Blocks: spec-00001-docs-whiteboard（FR-14 的留痕承诺）
- Related: record-00006（发现现场）、plan-00007（实测 (c) 期间发现）
