---
id: issue-00021-a-raw-nul-byte-makes-a-source-unmergeable
type: issue
status: resolved
blocks: [plan-00004-whiteboard-edge-emphasis]
---

# Issue: 一个裸写进源码的 NUL 字节让 git 把 `canvasModel.ts` 当成二进制文件

> `canvasModel.ts` 第 88 行把 U+0000 作为**原始字节**写进了模板字符串。git 的
> 二进制判定是「前 8000 字节内出现 NUL」，于是 `git merge-file` 拒绝合并它——
> 每个下游项目跑 `ainpt update` 都会撞上一个无法自动解决的冲突。

## 1. Problem

- Observed: 在由本模板创建的项目里执行 `ainpt update`，输出
  `error: Cannot merge binary files: tools/whiteboard/web/src/canvasModel.ts`，
  该文件被列为待解决冲突——即使下游从未改过它。
- Expected: 模板里每个被跟踪的文件都必须能被三方合并。`ainpt update` 对每个
  文件调用 `git merge-file`（ainpt 仓库 `internal/scaffold/scaffold.go:521`），
  它只接受 git 判定为文本的输入；模板源码理应全部是文本。
- Trigger: 任何一次跨越 commit `30c8ac5f` 的 `ainpt update`。用户在下游仓库
  更新 `2052a13 -> 108c3c3` 时报出。

## 2. Impact

- Affected: 所有由本模板创建、执行 `ainpt update` 的下游项目。冲突无法用
  `<<<<<<<` 标记解决——git 根本不产出合并结果，下游只能手工挑一边。
- Since: commit `30c8ac5f`（2026-08-14，plan-00004 边强调轮） · Still
  occurring: no（本 issue 已修）
- Severity: 中高。缺陷不影响白板自身的任何运行时行为（本地测试全绿），却让
  模板的分发机制对每个下游项目失效——又一个「本地永远看不见」的类型：所有
  既有检查都执行代码，没有一处检查源文件的字节形态。

## 3. Root Cause (first principles)

1. 分歧：同一个文件，vitest/tsc/vite 都当普通 TypeScript 处理，`git merge-file`
   却报 binary。两者的差别只在判定标准：工具链解码 UTF-8 时 U+0000 是合法
   标量值；git 则以「前 8000 字节内是否出现 0x00」定文本/二进制。
2. 最小机制：`tools/whiteboard/web/src/canvasModel.ts:88` 的复合 map key
   `` `${edge.from}\u0000${edge.to}` `` 里，分隔符不是六个字符的转义序列，而是
   **一个真实的 0x00 字节**（文件偏移 3486）直接躺在源文件里。
3. 真正的根因：**把「值里含 NUL」实现成了「文件里含 NUL」。** 意图没有错——
   用文档 id 不可能出现的字符做键分隔符，天然防碰撞；错在编码方式：转义序列
   `\u0000` 与原始字节在运行时产出完全相同的字符串，但前者让文件保持为纯
   ASCII 文本，后者让文件在 git 眼里变成二进制。
   它**不是**这些症状：不是 ainpt 的 bug（它正确委托 `git merge-file`，git 也
   正确执行了自己的启发式）；不是下游改动引起的普通冲突（文本文件的冲突会带
   标记可解，这里 git 直接拒绝产出）；也不是分隔符选得不好（U+0000 作为**值**
   完全合理，改成别的字符反而引入碰撞面）。

- Introduced by: `30c8ac5f`。此前 `toFlowEdges` 逐条映射边、没有复合 key，
  文件不含任何控制字节，`git merge-file` 可正常合并——缺陷不可能发生。

## 4. Scope (same-cause sweep)

机制是「源文件里躺着原始控制字节」，按 git 的同一判定扫了全部 261 个被跟踪
文件（前 8000 字节含 0x00 即中）：

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `tools/whiteboard/web/src/canvasModel.ts:88` | yes | yes | fixed here |
| 其余 260 个被跟踪文件 | no | no | 扫描无 NUL |
| `tools/whiteboard/coverage/.../canvasModel.ts.html` | yes | no | 覆盖率产物，未被 git 跟踪，不进模板包 |

key 的消费面也扫过：仅 `canvasModel.ts:89`（`merged.get`）与 `:95`
（`merged.set`），同函数内自产自销、从不反向 split——改写成转义序列对运行时
零影响。

## 5. Reproduction (test-first)

1. `tools/whiteboard/test/tracked.test.ts` 新增用例：对每个 `git ls-files`
   路径按 git 的启发式断言「前 8000 字节无 0x00」。
2. 修复前运行，用例失败并点名 `tools/whiteboard/web/src/canvasModel.ts`。
3. 留作回归守卫——它守的是机制（模板不得跟踪 git 不可合并的文件），不只是
   这一个文件。

- Failing test: `tools/whiteboard/test/tracked.test.ts::tracks only files git
  can merge as text` — fails with the offending path in the diff。

## 6. Fix

- Change: `canvasModel.ts:88` 的原始 0x00 字节改为转义序列 `\u0000`。
- Why this addresses the root cause and not the symptom: 运行时字符串一个字节
  不差，变的只有源文件的字节形态——文件回到纯文本，git 可合并；分隔符的防
  碰撞语义原样保留。
- Alternatives rejected: 换成 `|` 等可见分隔符——文档 id 理论上可含该字符，
  引入碰撞面，且改变了运行时值，不必要。

## 7. Verification

- §5 的回归用例修复后通过。
- `npm test`（whiteboard 全套 vitest）全绿。

## 8. Follow-through

- Detection gap: 既有测试全部**执行**源码——转义序列与原始字节运行时等价，
  所以没有任何用例能区分两者；能看见差别的只有以字节读文件的检查。已在
  `tracked.test.ts` 加了全仓库级守卫（见 §5），任何被跟踪文件再变二进制都会
  在本仓库先红，而不是在下游 `ainpt update` 时爆。
- Doc verdict: **doc missing** ——本仓库没有任何 spec/rule 陈述「模板被跟踪
  文件必须可被三方合并」这一分发约束；该契约属于 ainpt 的文档域，本仓库以
  §5 的守卫测试作为可执行的不变量记录，不另立 spec。
- Residual state: 每个下游仓库的 `canvasModel.ts` 副本都还带着 NUL 字节，而
  `git merge-file` 三个输入（mine/base/new）任一为二进制即拒绝——所以本修复
  进入 main 后，下游**下一次** `ainpt update` 在此文件上仍会报一次 binary。
  处理：那次 update 已把 `.ainpt.json` 的基线推进到修复后的 commit，手工用新
  模板版本覆盖本地 `canvasModel.ts` 并提交，此后所有 update 恢复纯文本三方
  合并。`CONTEXT.md` 的冲突属 issue 外的独立问题（项目私有词汇表被模板分发），
  已由 `template.json` 排除项解决，已污染的下游恢复各自版本即可。

## Links

- Blocks: plan-00004-whiteboard-edge-emphasis
- Related: issue-00017-an-unanchored-gitignore-rule-hides-a-source-module（同
  属「只在分发/克隆态可见、工作副本永远全绿」的缺陷族）
