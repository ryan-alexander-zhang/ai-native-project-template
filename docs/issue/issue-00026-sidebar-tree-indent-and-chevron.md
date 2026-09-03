---
id: issue-00026-sidebar-tree-indent-and-chevron
type: issue
status: resolved
blocks: [plan-00026-whiteboard-directory-groups-and-exclude, spec-00008-whiteboard-navigation-sidebar]
---

# Issue: 导航栏的层级读不出来——子行对齐到父级箭头、目录组头箭头在右、徽章不齐

> 类型组之下的文档行与目录组头从父级**箭头**的 x 起排而不是从父级**文字**的 x
> 起排，目录组头又把折叠箭头放在行尾，计数徽章因此错位；域主 UAT 时发现三层
> 结构在视觉上几乎平掉。

## 1. Problem

- Observed（域主 2026-09-03 截图）：类型组头「▸ 🗎 rule ······ 2」展开后，其
  文档行的状态点落在 ▸ 的正下方，行的第二行标题再顶到状态点的 x；目录组头
  「📁 reference/ccbill ····· 224 ▸」的图标顶格、箭头在行尾，徽章比类型组的
  徽章向左错开一个箭头宽；目录组成员行只比目录组头多 8px。
- Expected：树形列表的通行语法——每一级固定缩进；折叠箭头永远在行首的固定
  宽度槽位，叶子行也保留该槽位以使同级文字对齐；子级文字起点 = 父级文字起点
  + 一级缩进；行尾只放次级信息（计数）且贴同一右边线。design-00002 §17.2「组头」
  「行」与 §19.4「渲染」写的是「缩进一级 / 再缩进一级」，意图即此；`spec-00008-FR-1`
  与 `spec-00010-FR-8` 只规定结构与顺序，不规定像素，所以这是设计与实现层的
  缺陷，不是 spec 缺陷。
- Trigger：任一类型组展开即见第 1 点；任一列有子目录即见第 2、3 点。

## 2. Impact

- Affected：导航栏全部三种行（文档行、目录组头、目录组成员行），每个用户每次
  打开导航栏。
- Since：文档行的对齐问题自 `9752e997`（spec-00008 首次落地）即在，目录组头的
  箭头位置自 `8669e959`（plan-00026）· Still occurring：yes。
- Severity：中。不影响功能与可访问性（`aria-expanded`、`aria-current` 都在），
  但导航栏的全部价值在于「一眼看出谁属于谁」，层级平掉就是价值打折；目录组
  越多越明显（hancock 的 untyped 列下四个组）。

## 3. Root Cause (first principles)

1. 分歧陈述：子级应比父级的**文字**更靠右；实际子级只比父级的**箭头**靠右。
2. 机制：`web/src/Sidebar.tsx:125` 类型组头 `px-2`，箭头是它的第一个子元素，
   文字在箭头 + 图标之后（≈ 8 + 12 + 8 + 16 + 8 px）；`:143` 顶层文档行
   `indent="pl-4"`（16px），`:158` 目录组头 `pl-4`，`:178` 成员行 `pl-6`
   （24px）。缩进只按「比父级 padding 多一档」取值，没有把父级行首的箭头槽与
   图标算进去，于是子级的第一个元素（状态点 / 文件夹图标）恰落在父级箭头列。
   目录组头（`:158`–`:170`）的 `ChevronRight` 放在 `Badge` 之后，与类型组头
   （`:129`）箭头在前的语法相反；`Badge` 的 `ml-auto` 因此把徽章推到箭头之前
   而不是右边线。文档行（`:37` `Row`）第二行标题与第一行的状态点同一起点，
   没有为状态点留槽。
3. 真因是**没有一个树形缩进模型**：三种行各自写死一个 Tailwind padding 类，
   没有「层级 × 单位缩进 + 行首固定槽」的公共规则；箭头位置也未被规定为「行首
   槽位」。不是：数据模型问题（`Column`/`DirectoryGroup` 层级正确）、也不是
   可访问性问题（语义属性齐全）。

- Introduced by：`9752e997`（文档行对齐父级箭头）与 `8669e959`（目录组头的行尾
  箭头与 `pl-4`/`pl-6`）。前者之前没有导航栏；后者之前没有目录组。

## 4. Scope (same-cause sweep)

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `web/src/Sidebar.tsx:125` 类型组头 | 行首箭头，`px-2` | 是（作为 level 0 基准参与模型） | 改为 level 0，箭头槽固定宽 |
| `web/src/Sidebar.tsx:143` 顶层文档行 `pl-4` | 写死 padding | 是 | level 1，状态点占行首槽位 |
| `web/src/Sidebar.tsx:158` 目录组头 `pl-4` + 行尾箭头 | 写死 padding、箭头在尾 | 是 | level 1，箭头移到行首槽位 |
| `web/src/Sidebar.tsx:178` 成员行 `pl-6` | 写死 padding | 是 | level 2 |
| `web/src/Sidebar.tsx:37` `Row` 第二行标题 | 无槽位 | 是 | 标题从槽位之后起 |
| `web/src/GroupNodeCard.tsx` 组名行的 `Folder` + `Badge` | 形似 | 否 | 画布卡片不是树，无缩进语义；不动 |
| `web/src/Inspector.tsx` 条目行的展开箭头 | 行首箭头 | 否 | 单层列表，无层级；不动 |

## 5. Reproduction (test-first)

- Failing test 1：`web/test/sidebar.test.tsx::a directory group in the sidebar >
  indents each level past its parent’s label`（`// issue-00026`）——渲染含两个目录
  组的 reference 类型组、展开 ccbill，读四种行的 `data-level` 与内联
  `padding-left`（jsdom 不解析 CSS 变量，读的是推导式本身），再读文档行行首的
  槽位。修前失败于类型组头既无 `data-level` 也无按层级推导的 padding：

  ```
  AssertionError: expected [ null, '' ] to deeply equal [ '0', 'calc(var(--tree-indent) * 0)' ]
  ❯ web/test/sidebar.test.tsx:799:41
  ```

  余下三行同因：缩进写死为 `pl-4`(16px) / `pl-6`(24px)，文档行的 16px 落在类型组头
  文字起点（48px）左侧、恰在其箭头列上。
- Failing test 2：`web/test/sidebar.test.tsx::a directory group in the sidebar >
  puts the fold chevron first on a directory header, as on a type header`
  （`// issue-00026`）——目录组头按钮的第一个元素是 `ChevronRight`（与类型组头同）、
  第二个是 `Folder`、最后一个是计数 `Badge`，且整行只有一个箭头。修前失败于第一个
  元素是 `Folder`：

  ```
  AssertionError: expected false to be true // Object.is equality
  ❯ web/test/sidebar.test.tsx:822:70
     directory[0]!.classList.contains('lucide-chevron-right')
  ```

## 6. Fix

- Change：`Sidebar.tsx` 引入一个树形缩进模型——`level` 0/1/2，行的
  `padding-left = level × 一级缩进`（一级缩进取 16px，CSS 变量 `--tree-indent`，
  在 `index.css`）；缩进之后**每行都以同两个固定 16px 的列开头**：
  1. **折叠列**——可折叠行（类型组头、目录组头）放 `ChevronRight`，叶子行（文档
     行）留**空**，无箭头可折的行也不占箭头的语义，只占它的位置；
  2. **图标列**——组头放类型图标 / `Folder`，文档行放状态点；

  文字是第三列。于是同级的文字永远对齐（level 1 的文档行与目录组头的文字同一
  起点），且任一级的文字起点 = 父级文字起点 + 一级缩进。目录组头改为「箭头 →
  `Folder` → 组名 → 徽章」与类型组头同形；徽章一律 `ml-auto` 贴同一右边线；
  `Row` 的 id 行与标题行同在第三列里，标题不再顶到状态点。
- Why this addresses the root cause and not the symptom：把三处写死的 padding
  换成一个按层级推导的规则，以后再加一层也不会再错；箭头位置与图标位置成为
  「行首两列」这一条规则的实例，而不是每种行自定。这正是 VS Code 资源管理器的
  写法：文件行的折叠列是空的，图标列照占，所以文件名与同级的文件夹名齐头。
- Alternatives rejected：VS Code 式缩进引导线——三层还用不上，先不加；把子行
  改成单行（去掉标题行）——标题是 spec-00008-FR-1 要求的行内容，不动；只给文档
  行一个槽位（初版实现）——文档行的文字会比父级组头的文字**左** 8px，正是本
  issue 报的那个症状，故改为折叠列 + 图标列两列。

## 7. Verification

- §5 两条测试转绿。`npm test`：62 files / 1905 tests 全绿；`npm run typecheck`
  （`tsc --noEmit`）无输出；`vitest run --coverage` 门未动（lines/branches/functions
  各 90），实测全仓 98.72% stmts、95.49% branches、98.62% funcs、99.29% lines，
  `Sidebar.tsx` 语句 / 分支 / 函数三项均 100%。
- 落地形状：每行 `padding-left = calc(var(--tree-indent) * level)`，
  `--tree-indent: 16px` 加在 `index.css` 的 `:root`（无 dark 变体）；列间距一律
  8px（`gap-2`）。
  - 类型组头 level 0：`ChevronRight`(16) → 类型图标(16) → 类型名 → 计数 `Badge`
    贴右。
  - 顶层文档行 level 1：空折叠列(16) → 状态点所在图标列(16) → id 行 + 标题行。
  - 目录组头 level 1：`ChevronRight`(16) → `Folder`(16) → 组名 → 徽章贴右（行尾
    不再有箭头）。
  - 目录组成员行 level 2：形同文档行。
- 实测列位（行盒左边起，导航栏自身的 `p-2` 之外）：

  | 行 | level | 折叠 / 状态点列 | 图标列 | 文字列 |
  | --- | --- | --- | --- | --- |
  | 类型组头 | 0 | 0 | 24 | 48 |
  | 顶层文档行 | 1 | 16（空） | 40（状态点） | 64 |
  | 目录组头 | 1 | 16 | 40 | 64 |
  | 成员行 | 2 | 32（空） | 56（状态点） | 80 |

  同级文字同起点（64 = 64），子级文字 = 父级文字 + 16（48 → 64 → 80）。
- 肉眼核对（2026-09-03，含 stripe 目录组夹具的临时副本，浏览器实测）：类型组头 / 顶层文档行 / 目录组头 / 成员行的文字列分别为 56 / 72 / 72 / 88 px，同级相等、逐级 +16；目录组头无行尾箭头；类型组头与目录组头的徽章右边线严格相等（212.22 px）。

## 8. Follow-through

- Detection gap：既有测试只断言行的存在、顺序与 `aria-*`，不看几何；新增的两条
  测试以 `data-level` 与 DOM 顺序钉住缩进模型与箭头槽位，几何值本身（16px）
  仍是可调起点、不钉。
- Doc verdict：**design 措辞不够**——design-00002 §17.2「行」与 §19.4「渲染」
  的「缩进一级」没说以谁为基准、没规定箭头槽位；随本 issue 在两处据实校正为
  本 issue §6 的缩进模型。`spec-00008` / `spec-00010` 不变。
- Residual state：none。

## Links

- Blocks: plan-00026-whiteboard-directory-groups-and-exclude ·
  spec-00008-whiteboard-navigation-sidebar
- Related: design-00002-whiteboard-ui §17.2、§19.4 · decision-00016-whiteboard-navigation-sidebar
