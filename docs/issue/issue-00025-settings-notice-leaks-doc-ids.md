---
id: issue-00025-settings-notice-leaks-doc-ids
type: issue
status: resolved
blocks: [spec-00009-whiteboard-agent-settings, plan-00025-whiteboard-agent-settings]
---

# Issue: 设置面板的写域说明把 `design-00001 §11.5` 漏进了用户可见文案

> 本地追加的 agent 条目旁那行「未经写域校验」的说明里带着内部文档引注，用户在
> 界面上看到了工程文档的编号与章节号；域主验收时发现。

## 1. Problem

- Observed：设置面板中每条本地追加的 agent 卡片下方呈现
  「This CLI has not been checked against the write scope (design-00001 §11.5):
  a write outside docs/ is not stopped by the board, and that is yours to answer
  for.」——括号里是仓库内部 design 文档的编号与章节。
- Expected：用户可见文案只说事，不带文档编号或章节号；文档编号是文档与代码
  注释的词汇（design-00002 §18.2 修订后明写；`spec-00009-FR-7` 补注、
  `spec-00009-AC-7.9`）。
- Trigger：本地层有任一追加条目，打开设置面板即见。

## 2. Impact

- Affected：设置面板的写域说明一处；有本地追加条目的每台机器。
- Since：`3d760fa0`（2026-09-02，plan-00025 T2）· Still occurring：no。
- Severity：低。不影响功能；但它是文案层的边界失守——工程内部引用出现在
  产品界面上，且现有测试对此类文案一无所知。

## 3. Root Cause (first principles)

1. 分歧陈述：文案应只含用户能理解的内容；实际含内部文档引注。
2. 机制：design-00002 §18.2 初稿把这条说明**作为引号内的界面文案**写成
   「此 CLI 未经写域校验（design-00001 §11.5），越界写入不由白板拦截」——引注
   落在引号之内；实现（`web/src/SettingsDialog.tsx:332`，修前）照抄了引号内
   全文，引注随之进了 JSX 文本节点。
3. 真因是**设计文档没有区分「文案」与「文案的出处」**：本仓惯例是代码注释
   逐处引文档 id（`web/src/**` 有上百处），实现者见到引注自然沿用；设计把
   出处写在文案引号内，实现就无从区分。不是：实现者不懂惯例（同文件其余
   引注都在 JSX 注释里）、也不是 i18n 或渲染问题。

- Introduced by：`3d760fa0`。在此之前设置面板不存在，这条文案不存在。

## 4. Scope (same-cause sweep)

对 `web/src/**`（除 `components/ui/**`）去掉注释后全文扫描
`(design|spec|decision|rule|plan|issue|record)-\d{5}` 与 `§\d`：

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `web/src/SettingsDialog.tsx:332` | yes | yes | fixed here |
| `web/src/MaterialsInput.tsx:91` 的 placeholder `spec-00001-whiteboard` | 形似 | no | 那是用户可输入内容的示例（材料可以是仓内文档 id），非引注；守卫测试按文件 + 字面串放行 |
| `web/src/**` 其余 90 余处 | no | no | 全部位于 `{/* */}` 或 `//` 注释内，不渲染 |
| `src/workflow.ts:120`、`src/annotations.ts:456/711` 的拒绝文案含 `rule-00001-BR-29` | 部分 | 待裁 | 业务规则 id 出现在「为什么被拒」的提示条里——它是用户可以去查的**规则**，与工程 design 引注性质不同；是否也去掉由域主定，本 issue 不动 |

## 5. Reproduction (test-first)

- Failing test 1：`web/test/settings.test.tsx::warns about the write scope
  without citing an internal doc`（`// issue-00025`、`// spec-00009-AC-7.9`）
  ——修前失败于
  `expected 'This CLI has not been checked against…' not to match
  /(design|spec|decision|rule|plan|issue|record)-\d{5}|§/`。
- Failing test 2（守卫）：`web/test/copy.test.tsx`——遍历 `web/src/**/*.{ts,tsx}`
  去注释后扫描，修前报出恰一处：
  `SettingsDialog.tsx:312: This CLI has not been checked against the write
  scope (design-00001 §11.5)…`。

## 6. Fix

- Change：文案改为「This CLI has not been checked against the write scope: a
  write outside docs/ is not stopped by the board, and that is yours to answer
  for.」，引注移入其上方的 JSX 注释
  `{/* … (design-00001 §11.5, spec-00009-FR-7). */}`。
- Why：把出处放回它在本仓的正当位置（注释），文案本身不再承载引注；守卫测试
  让「引注进文案」在下一处出现时即刻失败。
- 同轮文档修正：design-00002 §18.2 把引注移出引号并明写「编号与章节号只进
  代码注释，不进用户可见文案」；`spec-00009-FR-7` 补注同一口径，增
  `spec-00009-AC-7.9`。

## 7. Verification

- 两条测试修后通过；`npm test` 59 文件 / 1806 用例通过；`npm run typecheck`
  通过；覆盖率 98.68 / 95.37 / 98.57 / 99.27，阈值未动。
- 域主重开面板目视：说明行不再含编号。

## 8. Follow-through

- Detection gap：既有测试只断言文案「存在」（`AC-7.1`），不断言文案「不含
  什么」；任何文案都没有针对内部引注的检查。新增的 `copy.test.tsx` 是全仓
  守卫，覆盖 `web/src/**` 每一处渲染文本；`.ts` 与 `.tsx` 皆扫。局限：只剥行首
  或 `{` 后起头的 `//` 注释与块注释，代码尾随的 `//` 注释不剥——只会更严，
  不会漏。
- Doc verdict：**the doc was wrong**——design-00002 §18.2 把引注写进了文案
  引号内；已修订，`spec-00009-FR-7` 补注并加 `AC-7.9` 覆盖。
- Residual state：none（文案随下一次 `npm run build` 更新，无持久数据）。

## Links

- Blocks: [spec-00009-whiteboard-agent-settings](../spec/spec-00009-whiteboard-agent-settings.md) ·
  [plan-00025-whiteboard-agent-settings](../plan/plan-00025-whiteboard-agent-settings.md)
- Related: [design-00002-whiteboard-ui](../design/design-00002-whiteboard-ui.md) §18.2 ·
  [record-00026-whiteboard-agent-settings-acceptance](../record/record-00026-whiteboard-agent-settings-acceptance.md)
