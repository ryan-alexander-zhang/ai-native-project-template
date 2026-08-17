---
id: record-00005-whiteboard-text-rendering-acceptance
type: record
status: active
parent: plan-00006-whiteboard-text-rendering
verifies: [spec-00001-FR-37, spec-00001-FR-38, spec-00001-FR-39, spec-00001-AC-34.7, spec-00001-AC-34.8, spec-00001-AC-34.9, spec-00001-AC-35.7]
---

# 验收记录：文本呈现——渲染、详情与标签阈值

对 [plan-00006-whiteboard-text-rendering](../plan/plan-00006-whiteboard-text-rendering.md)
的验收。收尾期间发现并按流程处置的缺陷见
[issue-00006](../issue/issue-00006-stale-width-recentre-clips-the-toolbar.md)（已修复）与
[issue-00007](../issue/issue-00007-the-board-never-hears-about-disk-changes.md)（open，待裁定）。

- 套件：`cd tools/whiteboard && npm test` → **25 个测试文件、483 个测试全部通过**
  （plan-00005 验收时为 443）
- 覆盖率：语句 98.99%、分支 95.23%、函数 98.48%、行 99.56%（门槛 90%）
- 类型检查与构建：`npm run typecheck` 无错误；`npm run build` 通过
- GWT 核验由未参与实现的 subagent 完成：26/26 有对应通过的测试，抽查（按边计
  阈值、块级夹具真会成块、刷新真发生、fit 突破默认缩放地板）均非恒真；既有
  39 条（record-00004）抽查未被改弱
- 环境性 flake 一笔：`server.test.ts` 单次因测试端口收到外来 TLS 字节失败
  （`HTTPParserError`），单独重跑与全量重跑均通过，非实现缺陷

测试名为 `tools/whiteboard/` 下的用例标题：`t/` = `test/`，`w/` = `web/test/`。

## 新增 GWT

| GWT id | 测试 | 结果 |
| --- | --- | --- |
| spec-00001-AC-34.7 | lists all three cited AC ids on the label (w/inspector) | pass |
| spec-00001-AC-34.8 | folds a fourth cited AC id into «first +3» (w/inspector) | pass |
| spec-00001-AC-34.9 | keeps both labels itemised when four cited AC ids split across two edges (w/inspector) | pass |
| spec-00001-AC-35.7 | fits every node of a tall sub-canvas into the first viewport (w/subcanvas)——逐节点以声明尺寸验证四边入画布，且 scale 低于 React Flow 默认地板，证明 fit 真实生效 | pass |
| spec-00001-AC-37.1 | gives an AC node its whole Given/When/Then (w/details) | pass |
| spec-00001-AC-37.2 | gives an item node its text and the roll of its AC (w/details) | pass |
| spec-00001-AC-37.3 | gives an acceptance row its record, test, result, evidence and a way back (w/details) | pass |
| spec-00001-AC-37.4 | closes on a click into the blank (w/details) | pass |
| spec-00001-AC-37.5 | switches to the node clicked next (w/details) | pass |
| spec-00001-AC-37.6 | goes to the record the row came from, and selects it (w/details) | pass |
| spec-00001-AC-37.7 | closes on Esc and leaves the sub-canvas standing (w/details) | pass |
| spec-00001-AC-37.8 | shows no evidence field for a row that has none (w/details) | pass |
| spec-00001-AC-37.9 | closes on the way back up, handing the slot to the inspector (w/details) | pass |
| spec-00001-AC-38.1 | opens the row in place, with the whole text and every AC in full (w/inspector) | pass |
| spec-00001-AC-38.2 | closes the row on a second click, back to the clamped text (w/inspector) | pass |
| spec-00001-AC-38.3 | keeps at most one row open (w/inspector) | pass |
| spec-00001-AC-38.4 | emphasises the edge on hover while the row is open (w/inspector) | pass |
| spec-00001-AC-38.5 | keeps the row open through a graph refresh (w/inspector) | pass |
| spec-00001-AC-38.6 | says «no AC» rather than opening onto nothing (w/inspector) | pass |
| spec-00001-AC-38.7 | opens the row from the keyboard (w/inspector) | pass |
| spec-00001-AC-39.1 | renders bold and inline code as themselves, not as their source；renders the panel row through the same pipeline, clamp and all (w/inline) | pass |
| spec-00001-AC-39.2 | renders the expansion, the sub-canvas node and the detail the same way (w/inline) | pass |
| spec-00001-AC-39.3 | puts no script element on the page (w/inline) | pass |
| spec-00001-AC-39.4 | makes no block element out of a heading or a fence；makes no list or table either (w/inline) | pass |
| spec-00001-AC-39.5 | renders empty text as nothing at all；keeps an empty item legible by its id everywhere (w/inline) | pass |
| spec-00001-AC-39.6 | degrades a link and an image to their text (w/inline) | pass |

无 fail / missing 行。issue-00006 的回归守卫另有六条（w/viewport：三条进入
路径的居中宽度、无宽度变化不居中、顶层缩放地板 0.5、子画布地板随包围盒），
守着 design-00002 §9「工具栏避让右槽」这条不写 GWT 的裁定。

## 实测核对（plan 验收路径第 4 条）

浏览器自动化实跑本仓真实文档（1600×900），逐项判定并截图留证：

- **(a) 文本渲染——通过**。面板 39 行 44 个行内代码、30 个加粗元素，原始标记
  全文命中 0；子画布 363 节点同样干净且无 a/img/块级元素。粗扫的两处「原始
  标记」报警核对源文后确认是误报——那两条 AC 的原文本就把标记写在行内代码里，
  渲染为 code 后字符必须保留，恰是管线正确性的正面证据。
- **(b) 标签阈值——通过**。FR-29 → `AC-29.1 +7`，FR-32 → `AC-32.1 +9`，
  FR-28（4+1 分属两条边）→ 一条 `+3` 一条单独列出，FR-5（3 条）逐项并列——
  **按边计**实锤；标签从 921px 长条缩为约半张卡片宽，不再覆盖三张卡片。
- **(c) 进入即见全貌——字面通过，观感留档**。363 节点全部落入初始视口
  （AC-35.7 成立），但 spec-00001 子画布包围盒宽高比 0.053，fit 后是画布中央
  一条约 46px 宽的竖直细带，横向 97% 空白，需放大才可读——「见全貌」与
  「可阅读」在此长宽比下不能同时成立，见下方观察项 1。
- **(d) 工具栏完整可见——首测 FAIL，按流程立 issue-00006 修复后复测通过**。
  根因（重居中用面板挂载前的过期画布宽度）、失败测试、修复与两条路径的复测
  坐标（工具栏右端 760 vs 面板左沿 992，间距 232px）见该 issue §3-§7。
- **(e) 详情面板——通过**。全 spec 最长的 AC-1.1 全文无截断；有/无 Evidence
  两种验收行（本仓 92/57 条）分别呈现四栏与三栏，无 Evidence 是字段缺席而非
  空值；「Go to record」跳转回顶层并选中该 record，工具栏完整。
- **(f) 落地裁定——BLOCKED，如实记录**。前端无任何图刷新订阅通道（服务端不
  监听文件系统、唯一 WS 是 PTY），外部写入 docs/ 后 `/api/graph` 零请求而
  curl 同刻已返回新文档——裁定的「刷新时按 id 保持」按构造成立（有纯函数级
  测试）但端到端不可触发；手动刷新页面则下钻/选中/详情全部丢失。已立
  **issue-00007**（open）：补通道或收回 design-00001 的推送承诺，待域主裁定。
  「文档被删返回顶层」的 effect 存在但无对应测试，随该 issue 的修复轮补。

## 观察项（不阻塞，留待后续）

1. 极端长宽比下 fit 的观感（(c) 的 46px 细带）；且 fit 态下用控件放大会以视口
   中心为锚，把偏离中心的细带推出屏幕。可能的方向：fit-to-width + 纵向滚动，
   或对 fit 的 zoom 设下限并落在首列。需要一次设计裁定。
2. 直接点选令面板从无到有时现在会重居中（issue-00006 修复第三条路径的必然
   结果，只发生在画布真正变窄的那次转换）——行为变化已在该 issue §6 明记。
3. 进入子画布的 fitView 仍按面板交还前的偏窄宽度执行（无害、保守方向），同型
   时序留待需要时再动（issue-00006 §8）。
4. record-00004 观察项 2 后半（0.5 缩放下标签字号约 5px）本轮未处置，折叠只
   解决了长度与遮挡，继续留档。
5. `web/src/canvasModel.ts` 的字面 NUL 字节仍在（record-00004 观察项 5），该
   文件的 diff 在 git 下仍不可读。

## 结论

26/26 GWT 通过、无 unverified 条目、覆盖率达标；实测 (a)(b)(e) 通过、(c) 字面
通过、(d) 经 issue-00006 修复后复测通过、(f) 端到端受阻并已立 issue-00007 交
域主裁定。plan-00006 置 `resolved`。
