---
id: record-00019-whiteboard-desktop-notifications-acceptance
type: record
status: active
parent: plan-00019-whiteboard-desktop-notifications
verifies: [spec-00004-whiteboard-desktop-notifications]
---

# 验收记录：桌面通知——离场时叫回用户

对 [plan-00019-whiteboard-desktop-notifications](../plan/plan-00019-whiteboard-desktop-notifications.md)
的验收。本轮交付整份 `spec-00004-whiteboard-desktop-notifications`
（6 FR、23 AC——`spec-00004-AC-2.5` 由 issue-00018 的验收缺口补钉、
`spec-00004-AC-2.6` 由 issue-00020 补钉，随各自修复补入本清单）：三态桌面通知开关与权限路径（T3）、离场判定（隐藏或
失焦）、等待输入与结束的通知触发（含转入离场的补发、同会话替换）、点击
回跳；design-00002 §13 与 spec-00003 §6 的文档轮（T1/T2）先行完成。纯
前端实现，零服务端改动（decision-00010 §5）。清单按 AC 逐条列全，测试
路径相对 `tools/whiteboard/`。

## 质量门

- `npm test`：41 个文件、1111 个测试全部通过（issue-00018/00019/00020 的回归测试并入后的计数）。
- `npm run typecheck`：`tsc --noEmit` 无错。
- `npm run test:coverage`：statements 99.31% / branches 95.63% /
  functions 98.68% / lines 99.67%，四项均高于 90% 门槛且不低于改动前，
  未调整任何阈值。

## 验收清单

| 被验 id | 测试 | 结果 |
| --- | --- | --- |
| spec-00004-AC-1.1 | takes effect when the user turns it on and the browser grants (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-1.2 | falls back to off and points at the browser settings when the request is refused (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-1.3 | asks for no permission at all until it is turned on (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-1.4 | asks for nothing when the permission is already granted (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-1.5 | goes silent at once when it is turned off, and stays off (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-2.1 | posts a notification when a session starts waiting while the page is away (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-2.2 | says nothing when the waiting is lifted (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-2.3 | catches up on a session already waiting when the page goes away, once (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-2.4 | posts one notification per session when two start waiting (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-2.5 | posts a notification for a second round of waiting of the same session (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-2.6 | posts one notice however often the waiting mark flickers while the user stays away (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-3.1 | posts a notification when a session ends while the page is away (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-3.2 | posts a notification when a session fails to start (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-3.3 | posts one notification per session when two end (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-4.1 | posts nothing while the page is visible and focused, and still toasts (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-4.2 | posts nothing while the switch is off, and the badge still counts (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-4.3 | goes quiet and shows it needs permission when the permission is taken back (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-5.1 | shows the session and selects its document (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-5.2 | refuses and leaves the view alone when the session is gone (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-5.3 | shows the session and says so when its document has left the board (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-6.1 | carries the kind, the document id and the state, and nothing else (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-6.2 | carries no error text when a session failed to start (web/test/notifications.test.tsx) | pass |
| spec-00004-AC-6.3 | replaces a session own earlier notification instead of stacking one on it (web/test/notifications.test.tsx) | pass |
