---
id: analysis-00001-out-of-board-notifications
type: analysis
status: active
informs: [spec-00004-whiteboard-desktop-notifications]
---

# Analysis: 板外通知的技术路线（2026-08 调研）

> 人不在白板页面（标签页后台、标签页关闭、浏览器整个退出）时，等待输入与
> 会话结束如何送达？四条路线的现状与本地单人工具下的适配度。

## 1. Question and Method

- Question: 白板是本机 Node 服务 + 浏览器页面的本地单人工具；`spec-00003`
  的等待徽标与结束提示条只活在页面里。人工测试证实「必须来回看页面」是
  真痛点（域主实录，2026-08-23 手测）。哪条通知通路能覆盖到「不在页面」
  乃至「浏览器已关」？
- Method: 2026-08 web 检索（Declarative Web Push 现状、自托管 Web Push、
  Node 桌面通知生态），对照本工具的形态（localhost、单人、服务端常驻、
  事件源已有——awaiting 翻转与会话结束都已在服务端）。主要来源：
  WebKit《Meet Declarative Web Push》
  （webkit.org/blog/16535）、aimtell《State of Declarative Web Push in
  2026》、codercops《Web Push Notifications Without a Vendor》(2026)、
  Chrome for Developers《Push Notifications on the Open Web》、
  github.com/mikaelbr/node-notifier 与 github.com/Aetherinox/node-toasted-notifier
  （周下载与维护状态读自 npm/仓库页）。

## 2. Findings

- **A. 页面级 Notification API（无 Service Worker）**：页面开着（含标签页
  后台、浏览器失焦）即可弹系统通知，点击可聚焦回标签页；标签页关闭即失效。
  localhost 是安全上下文，可用；需一次浏览器权限授权。零基础设施。
- **B. Service Worker + Push API（自签 VAPID，无厂商账号）**：标签页关闭
  仍可达（浏览器进程需在运行）；但消息物理路径必须绕经**浏览器厂商的推送
  服务**（Chrome→FCM、Firefox→Mozilla autopush）——本地工具的通知出岛
  过一次公网，离线即断；需 SW + 订阅管理 + 权限流程，复杂度最高。
- **C. Declarative Web Push**：已并入 W3C Push API 主规范草案，2026 年
  Safari/iOS/macOS 全线原生支持、无需 SW；但 Chromium 与 Firefox 仍停在
  issue tracker，跨浏览器不可用，且同样绕经推送服务。对本工具超前。
- **D. 服务端直发系统通知**：白板服务就在本机，直接调操作系统通知——
  macOS `osascript display notification` / `terminal-notifier`（后者可带
  `-open <url>` 点击回跳）、Linux `notify-send`、Windows PowerShell toast。
  浏览器后台、关闭、离线**全都可达**；无浏览器权限流程（macOS 对发起
  进程授权一次）。npm 生态：`node-notifier`（周下载 ~9M，但对新 macOS
  有兼容抱怨，维护趋缓）与其活跃 fork `node-toasted-notifier`；直接
  spawn 平台命令则零依赖、行为最可控。
- **服务端已握有判定「在不在页面」的信号**：`/api/events` WebSocket 的
  连接数（无连接 = 没开白板页面）；页面可再经该 socket 上报一位
  可见性（Page Visibility API），区分「开着但在后台」。

## 3. Gaps and Comparison

| 路线 | 标签页后台 | 标签页关闭 | 浏览器退出 | 离线 | 基础设施 |
| --- | --- | --- | --- | --- | --- |
| A 页面 Notification | ✅ | ❌ | ❌ | ✅ | 无 |
| B SW + Push (VAPID) | ✅ | ✅ | ❌（进程需在） | ❌（绕公网） | SW + 订阅 |
| C Declarative Push | ✅（仅 Safari 系） | ✅（仅 Safari 系） | 未核实（Safari 系经 APNs 或可达） | ❌ | 推送服务 |
| D 服务端系统通知 | ✅ | ✅ | ✅ | ✅ | spawn 平台命令 |

- D 的短板：点击回跳能力按平台参差（`terminal-notifier -open` 可、裸
  `osascript` 不可）；非三大平台静默降级；**与多人/远程形态不兼容**——
  服务端不在用户本机时够不着用户桌面，迁移即推倒重来。
- A 与 D 组合的分工天然无重叠：页面可见 → 既有提示条；页面开着但隐藏 →
  A（浏览器自己弹系统通知）；页面没开 → D（服务端直发）。
- **产品形态视角**（域主评审补充）：关标签页/退浏览器是用户的主动离场，
  越过该线仍推送并不符合预期——「标签页关闭不可达」对本工具是**期望
  行为**而非 A 的短板；A 的权限模型与通知渲染同时是将来升级 B/C 的台阶。

## 4. Conclusion

对本地单人工具，B/C 为了「标签页关闭可达」引入公网绕行与订阅设施，且 C
跨浏览器不可用、B 离线即断；D 以最低复杂度覆盖全部离场形态但与多人形态
不兼容，A 覆盖「页面开着但人不在看」并保有向 B/C 升级的台阶。技术上各自
可行——采哪条、通知哪些事件、去重边界，属 decision；此处不裁。另注：
A 的「离场」判定若只取页面可见性（`document.hidden`），盖不住「窗口可见
但失焦」——需并用焦点信号，属 design 细节。
