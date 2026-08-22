---
id: issue-00017-an-unanchored-gitignore-rule-hides-a-source-module
type: issue
status: resolved
blocks: [plan-00013-whiteboard-coverage-and-drilldowns]
---

# Issue: 一条不带锚的 `lib/` 让白板的一个源码模块从未进过版本库

> `.gitignore` 里从 Python 模板抄来的裸 `lib/` 在任意层级匹配，挡住了
> `tools/whiteboard/web/src/lib/utils.ts`——每个 vendored shadcn 组件都 import
> 它。文件在每一份工作副本里都在，在版本库里从来没有过：全新 clone 跑不起任何
> 一个 web 测试。

## 1. Problem

- Observed: 在一份全新 checkout（clone 或 worktree）里，
  `tools/whiteboard/web/src/lib/utils.ts` 不存在。20 个 web 测试文件有 19 个在
  **import 阶段**就失败，报
  `Failed to resolve import "@/lib/utils" from "web/src/components/ui/badge.tsx"`；
  唯一活下来的是 `web/test/api.test.tsx`，因为它不 import 任何组件。本地工作
  副本里同一条命令全绿。
- Expected: 该模块导出 `cn()`，是每个 vendored shadcn 组件的第一行 import，也是
  `web/src/components.json` 里 `aliases.utils` 指向的那一个文件——构建与测试都
  以它为前置，它必须被 git 跟踪。`decision-00001` §4 把
  `web/src/components/ui/**` 记为「CLI 生成、原样保留的第三方源码」；「原样
  保留」的前提是它在版本库里，而不是在某个人的磁盘上。
- Trigger: 任何一次 clone、worktree 或 CI checkout。由 `plan-00013` 的验收在一个
  一次性 worktree 里跑 web 测试时撞上。

## 2. Impact

- Affected: 任何**不是**从这台机器的现有工作副本继续干活的人或流程——新 clone
  的协作者、CI、以及每一个 `git worktree`。后果不是某个用例坏了，是整个 web
  测试套件在 clone 状态下起不来。
- Since: commit `5939fc56`（2026-08-13） · Still occurring: no（本 issue 已修）
- Severity: 高。它同时具备「后果全局」与「本地永远看不见」两条：所有既有检查
  （`npm test`、`typecheck`、`build`）读的都是工作副本，因此没有一处会报，缺陷
  可以无限期潜伏——它已经潜伏了两个多月、跨越十来轮开发。

## 3. Root Cause (first principles)

1. 分歧：工作副本里文件在、套件全绿；版本库里这个文件从来没有存在过
   （`git log --all -- tools/whiteboard/web/src/lib/utils.ts` 为空）。两个状态
   之间唯一的差别，是 git 有没有把它收进来。
2. 最小机制：`.gitignore:79` 是一条**裸的** `lib/`。gitignore 的匹配规则是——
   模式若不含斜杠、或只以斜杠结尾，就在**任意层级**匹配——所以它挡住的不只是
   仓库根部的 `lib/`，还有 `tools/whiteboard/web/src/lib/`。
   `git check-ignore -v tools/whiteboard/web/src/lib/utils.ts` 直接答出
   `.gitignore:79:lib/`。
3. 真正的根因：**从别的语言的模板整段抄来的忽略规则，没有被锚定到它本来要挡
   的那一层。** `lib/` 与它周围的 `build/`、`dist/`、`var/`、`wheels/` 同出于
   「Distribution / packaging」那一段，本意是仓库根部 setuptools 的产物目录。
   抄进来的那一刻它不匹配任何东西，所以无人察觉；四个月后 shadcn 脚手架在
   `web/src/` 下建了一个同名目录，这条规则才第一次生效——而它生效的方式是
   **静默的**：`git add` 对被忽略的路径不报错，只是不加。
   它**不是**这些症状：不是谁忘了 `git add`（加了也会被静默跳过，除非 `-f`）；
   不是 vite 的 `@` 别名配错（别名对，文件不在）；也不是 shadcn 该换个目录名
   （`lib/` 是它的约定，写在 `components.json` 里）。
   这一类缺陷有一个判别特征，值得单独记下：**它只在 clone 状态下存在**。凡断言
   对象是「磁盘内容」的检查，一概测不到它。

- Introduced by: 两个改动的合流，缺一不可——这也是它为什么这么难被看见。
  - `22e55983`（2026-04-15, `chore: add python to git ignore`）引入未锚定的
    `lib/`（与 `lib64/` 等一整段）。此前 `.gitignore` 中没有它，该路径不会被
    忽略。
  - `5939fc56`（2026-08-13, `feat(whiteboard): rebuild the ui on tailwind,
    shadcn/ui and lucide`）引入 `components.json` 与第一批 `@/lib/utils` 调用
    方。**这个 commit 里没有 `web/src/lib/utils.ts`**：脚手架在磁盘上生成了它，
    忽略规则挡下了它，作者手里的一切依旧是绿的。

## 4. Scope (same-cause sweep)

根因是「借来的模板规则未锚定，于是在任意层级匹配」。逐条扫 `.gitignore`
中所有此形模式，判据是它是否遮蔽本仓一条**本应入库**的路径
（`git check-ignore -v` 对真实路径实测）。

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `.gitignore:79` `lib/` | yes | yes | 锚定为 `/lib/`，并把 `tools/whiteboard/web/src/lib/utils.ts` 入库 |
| `.gitignore:80` `lib64/` | yes | no | 全树无 `lib64` 目录，TS 与 Java 项目也不会产生一个；按最小改动不动它 |
| `.gitignore:73,75,76,77,81,82,83,84` `build/` `dist/` `downloads/` `eggs/` `parts/` `sdist/` `var/` `wheels/` | yes | no | 同样在任意层级匹配（已实测），但当前无任何真实路径落在其下 |
| `.gitignore:139` `target/` | yes | no | 确实在深处匹配了 9494 个文件，全部是 `aipersimmon-ddd/**/target/` 的 Maven 产物——**意图与效果一致**，不动 |
| `.gitignore:102,114,127,145,191,217-221` `htmlcov/` `cover/` `instance/` `profile_default/` `__pypackages__/` `env/` `venv/` `ENV/` … | yes | no | 无真实路径 |
| `.gitignore:59` `.idea/` | yes | no | 只命中根部 `.idea/` 的 4 个 IDE 配置文件，意图一致 |
| `.gitignore:290` `tools/*/dist/` | — | no | **冗余**：第 75 行的裸 `dist/` 早已覆盖它。其上第 288 行的注释写着「上面的 `dist/` 与 `coverage.*` 管的是 Python 构建与 Go profile，不是这些路径」——同一个误解在四个月后又出现了一次，记明不改 |
| `.gitignore:44` `coverage.*` | yes（文件名形，非目录形） | no | 今天不遮蔽任何入库路径，但它在 `plan-00013` T2 里吞掉过一个新建的 `web/test/coverage.test.tsx`，靠改名绕开。是下一个最可能中招的模式，见 §8 |

结论：**只有 `lib/` 一条遮蔽了真实的应入库路径**，故本轮只锚定它。其余同形模式
一律记录在案而不改——把整段模板重写属于另一件事，不该混进一次缺陷修复。

## 5. Reproduction (test-first)

**其一，真实复现（缺陷的原样）**：把 HEAD 检出到一个全新 worktree，在其中跑
web 测试。

```
git worktree add --detach <tmp> HEAD
cd <tmp>/tools/whiteboard && npx vitest run web/test
```

```
FAIL  web/test/board.test.tsx [ web/test/board.test.tsx ]
Error: Failed to resolve import "@/lib/utils" from "web/src/components/ui/badge.tsx". Does the file exist?
  Plugin: vite:import-analysis
  File: …/tools/whiteboard/web/src/components/ui/badge.tsx:5:19

 Test Files  19 failed | 1 passed (20)
      Tests  20 passed (20)
```

**其二，回归守卫（进套件的那一个）**：既有的每一个测试读的都是工作副本，在那里
「被忽略但存在」与「已跟踪」无从分辨——所以守卫必须问版本库，不能问磁盘。

- Failing test: `test/tracked.test.ts::tracks the module every vendored ui component imports`
  ——以仓库根为 cwd 跑 `git ls-files`，断言
  `tools/whiteboard/web/src/lib/utils.ts` 在其中。修复前失败于：

```
FAIL  test/tracked.test.ts > the files a fresh clone has to get > tracks the module every vendored ui component imports
AssertionError: expected [] to deeply equal [ Array(1) ]

- Expected
+ Received

- [
-   "tools/whiteboard/web/src/lib/utils.ts",
- ]
+ []
```

## 6. Fix

- Change: `.gitignore:79` 的 `lib/` 改为 `/lib/`，随后
  `git add tools/whiteboard/web/src/lib/utils.ts`。
- Why this addresses the root cause and not the symptom: 锚定把这条规则还原成
  它本来的意思——仓库根部的 Python 打包产物目录——于是**任何**层级的
  `lib/` 都不再被深度匹配，不只是今天这一个。
- Alternatives rejected:
  - `!tools/whiteboard/web/src/lib/` 例外：只救这一个路径，根因原地不动，下一个
    同名目录照样中招，而且否定模式对目录的语义还有坑。
  - `git add -f` 而不改 `.gitignore`：文件确实入库了，但该目录里下一个新文件
    仍会被静默吞掉——这是把缺陷留在原地并加一层伪装。
  - 整段重写 Python 忽略块：超出一次缺陷修复该有的范围，且 §4 已证明其余模式
    今天不遮蔽任何东西。

## 7. Verification

- `test/tracked.test.ts::tracks the module every vendored ui component imports`
  ——修复后通过；`git ls-files tools/whiteboard/web/src/lib/` 列出该文件。
- `git check-ignore tools/whiteboard/web/src/lib/utils.ts` 以退出码 1 结束
  （无匹配，即不再被忽略）。
- `cd tools/whiteboard && npm test` 全绿：37 个测试文件、948 个用例。
- §5 的真实复现不再复现：从修复后的 HEAD 重开一个全新 worktree，其中
  `web/src/lib/utils.ts` 在位，`npx vitest run web/test` 20 个文件全过。
- 锚定没有放开任何本该被忽略的路径：对全树（`node_modules` 与 `.git` 除外）逐
  文件跑 `git check-ignore`，改动前后的命中统计只差这一个文件——`target/`
  的 9494 个、`tools/*/dist/` 的 97 个、`coverage/` 的 60 个、`.idea/` 的 4 个
  一个不动。

## 8. Follow-through

- Detection gap: 既有测试**全部**以工作副本为断言对象，而本缺陷只在版本库状态
  里存在，因此没有一个能看见它——缺的不是某一条用例，是这一整类断言。补上的
  守卫 `test/tracked.test.ts` 是本仓第一处问 `git ls-files` 而非问磁盘的测试；
  日后再有「构建离不开、却可能被忽略」的路径，加进那一处即可。
- Doc verdict: **code was non-conformant**，docs 不改。没有哪条 spec 或 rule 说
  过这件事，也不该有——这是仓库卫生，不是产品行为；`decision-00001` §4 关于
  vendored 源码「原样保留」的说法已经隐含它在版本库里。
- Residual state: none（工作副本里的文件内容本身没坏，入库即完整）。但 §4 记下
  的其余未锚定模式仍在原处：它们今天不遮蔽任何东西，`coverage.*` 已经逼过一次
  改名，是最可能的下一个。真要收口，属于「重写借来的忽略模板」这件独立的事。

## Links

- Blocks: [plan-00013-whiteboard-coverage-and-drilldowns](../plan/plan-00013-whiteboard-coverage-and-drilldowns.md)
- Related: [decision-00001-whiteboard-ui-stack](../decision/decision-00001-whiteboard-ui-stack.md)
