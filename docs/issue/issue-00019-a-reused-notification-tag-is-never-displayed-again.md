---
id: issue-00019-a-reused-notification-tag-is-never-displayed-again
type: issue
status: resolved
blocks: [record-00019-whiteboard-desktop-notifications-acceptance]
---

# Issue: a session's second desktop notification is never displayed, because it reuses the first one's tag

> The page leans on the browser replacing a notification that shares a tag; on
> macOS Chrome a tag whose notification was dismissed is dead, so each session
> gets one desktop notification ever and every later one is silently dropped.

## 1. Problem

- Observed (field report, then reproduced by the domain owner in the affected
  browser): an `ask` session notifies on its first round of waiting; the user
  dismisses or clicks that notification, the agent asks again — and no second
  desktop notification arrives. The same holds for the session's end notice.
  Nothing is logged and no error is thrown; the notification object is
  constructed normally.
- Expected: `spec-00004-FR-2` owes every `false → true` waiting turn one
  notification, `spec-00004-FR-3` owes every end one, and `spec-00004-FR-6` asks
  only that a session hold **at most one at a time** — «后到替换先到», not «后到
  被丢弃».
- Trigger: any second notification of one session on macOS Chrome, once the
  first has been dismissed or clicked. In the browser's console, with nothing of
  this project involved:

  ```js
  new Notification('第一条', { tag: 'x' })                      // displayed
  // dismiss it, then:
  new Notification('第二条', { tag: 'x', renotify: true })      // never displayed
  ```

## 2. Impact

- Affected: every user of the desktop-notification switch on macOS Chrome (the
  primary browser this board is used in). Both notification kinds are affected —
  a session's second waiting round and a session's end notice after a waiting
  notice.
- Since: `a73e5684` (the feature's first commit — the tag has been the
  replacement mechanism from the start) · Still occurring: yes.
- Severity: the feature's whole point is being called back when the board is not
  in front of you, and past the first notice of a session it silently stops
  doing that. Silent is the aggravating part: no error, no fallback, and the
  in-page badge and toast look identical whether the notification was displayed
  or dropped, so a user has no way to tell the feature has gone quiet.
- Not the same defect as `issue-00018`, which shares this symptom sentence («a
  second waiting round never notifies») and is already `resolved`. That one was
  a real reordering race in the board's read path and its fix stands; it was
  **not** the cause of this report. Two independent defects produced one field
  symptom, and fixing the first left the second in place.

## 3. Root Cause (first principles)

1. Divergence: the page constructs a second `Notification` for a session and
   the operating system displays nothing. `spec-00004-FR-6` asks for
   replacement; the platform gives suppression.
2. Mechanism: `tools/whiteboard/web/src/notify.ts:120-124` posts every notice
   with `tag: session.id` and `renotify: true`, and nothing else keeps the
   notifications a session has standing. Replacement is therefore delegated
   entirely to the browser's tag matching. On macOS Chrome that matching only
   replaces a notification that is **still displayed**: once the tagged
   notification has been dismissed or clicked, the tag remains claimed for the
   origin and a later notification reusing it is dropped without display.
   `renotify: true` does not lift this — it governs the alert of a replacement
   that does happen, not whether one happens.
3. The true root cause is the untested platform assumption, not the diff, the
   round bookkeeping, or the permission path: the code treats
   «same tag ⇒ replaced» as a guarantee of the Notifications API when the
   standard leaves display behaviour to the platform. The symptom it is *not*:
   a lost waiting round (that was `issue-00018`), a revoked permission
   (`AC-4.3`, which is silent by design), or the away test.

- Introduced by: `a73e5684` («feat(whiteboard): desktop notifications when the
  board is away»), which is where `tag: session.id` and `renotify: true` first
  appear. Before that commit there were no desktop notifications at all, so the
  defect could not occur. Reinforced by `design-00002` §13, whose 通知本体 bullet
  writes the assumption down as the design («`tag` 取会话 id，天然承载「同一会话
  后到替换先到」»), which is why no implementation ever questioned it.

## 4. Scope (same-cause sweep)

The mechanism is one construction site, and both notification kinds go through
it — the sweep is about the callers that share it, not about other files.
`grep -rn 'new Notification\|renotify\|tag:'` over `tools/` finds no other
notification construction anywhere in the repo.

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `tools/whiteboard/web/src/notify.ts:120` (`post`, the only construction site) | yes | yes | fixed here |
| `tools/whiteboard/web/src/notify.ts:152` (`postWaiting` → `post`, waiting notices) | yes | yes | fixed via `post` |
| `tools/whiteboard/web/src/notify.ts:169` (`ended` → `post`, end notices) | yes | yes | fixed via `post` |
| `issue-00018` (`useBoard.ts` read ordering) | no — same symptom, different cause | n/a | already resolved; its fix stands and is unrelated to this one |

## 5. Reproduction (test-first)

Written before the fix, in `tools/whiteboard/web/test/notifications.test.tsx`.
The `Notice` mock gained `close()` (counting on the instance) and `onclose`, so
the page's own replacement is observable where the OS layer is not.

- Failing test: `web/test/notifications.test.tsx::gives two notifications of one
  session different tags` — fails with `expected 's1' not to be 's1'`: both
  notices carry the session id as their tag, which is the exact input the
  platform suppresses.
- Failing test: `web/test/notifications.test.tsx::replaces a session own earlier
  notification instead of stacking one on it` (`spec-00004-AC-6.3`, retargeted
  from tag equality to the page's own `close()`) — fails with `expected +0 to be
  1`: nothing closes the waiting notice when the end notice is posted.
- Failing test: `web/test/notifications.test.tsx::posts a notification for a
  second round of waiting of the same session` (`spec-00004-AC-2.5`) — its tag
  assertion, retargeted the same way, fails with `expected 's1' not to be 's1'`.
Two guards were added with the fix, for what the page now owns:

- `web/test/notifications.test.tsx::leaves another session own notification
  standing` — replacement is per session, and now that the page performs it, one
  session must not take down another's notice (`spec-00004-AC-2.4`, `AC-3.3`).
- `web/test/notifications.test.tsx::keeps the standing notification when the one
  it replaced reports its close late` — a browser reports a close
  asynchronously, so the notice we closed to make room can report it after its
  replacement is standing; the handle map must then forget the closed one, not
  the standing one, or the session's next notice has nothing to replace and
  starts stacking again.

What no test in this repo can reach is the display layer itself: jsdom has no
`Notification`, and the stand-in displays everything it is handed. The
platform half of the reproduction is the manual console check in §1, run by the
domain owner in macOS Chrome.

## 6. Fix

- Change (`notify.ts` only): every notification gets a **unique** tag,
  `${session.id}:${n}` with `n` a page-local counter, so no tag is ever reused
  and the platform's suppression path is never entered. `spec-00004-FR-6`'s real
  requirement — 同一会话同刻至多一条、后到替换先到 — is then kept in the page's
  own hands: a per-session map holds the notification that session has standing,
  the next notice of that session `close()`s it before taking its place, and the
  handle is dropped when the notification is closed or clicked. `renotify` is
  removed: it only ever qualified a tag replacement, and there are no tag
  replacements left. The comment claiming the tag carries replacement goes with
  it. The map is cleared when the hook tears down, so a page that lives a long
  time holds no more handles than it has sessions.
- Why this addresses the root cause and not the symptom: the code no longer
  depends on any platform behaviour for the notification to be displayed — the
  invariant the spec states is enforced where it can be observed and tested.
- Alternatives rejected: **omit `tag` entirely** — it also avoids suppression,
  but it drops the per-notice identity the tag gives (nothing to key on when
  reading the system's notification centre or debugging a report), and leaves us
  relying on «untagged notifications never coalesce», another unverified
  platform assumption in place of the one just removed. A unique tag keeps the
  identity and needs no such assumption. **Keeping the tag and re-posting after
  a `close()`** — this is what the fix does *minus* the uniqueness, and it still
  hands a claimed tag back to the platform; the whole point is to stop asking.

## 7. Verification

- The three failing tests in §5 pass, and both guards pass.
- `tools/whiteboard`: `npm test` — 41 files, 1109 tests, all passing on the
  first run (the three known intermittents — `makes no commit when a session on
  a dirty tree produces nothing`, `answers 422 ... anomalous document`, `commits
  what the stopped session wrote, named by its kind` — did not flake).
- `npm run typecheck` — `tsc --noEmit`, no errors.
- `npm run test:coverage` — statements 99.30% / branches 95.64% / functions
  98.68% / lines 99.67%, all four above the 90% gate and none below the
  pre-change reading; `notify.ts` itself is at 100% on all four.
- Still owed, and the only check that can close the platform half: the manual
  console sequence of §1 re-run on the fixed board in macOS Chrome — two
  notices of one session, the first dismissed before the second is posted.

- 域主实机复测通过（2026-08-23，macOS Chrome）：多轮 ask——每轮回答后
  agent 再次等待均收到新通知，无论前一条通知是否被点掉；结束通知照常
  替换。现场症状消除。

## 8. Follow-through

- Detection gap: the display layer is not reachable from jsdom, and the test's
  `Notification` stand-in displays everything, so no unit test could have caught
  this and none added here does. What the new tests guard is the *input* the
  platform choked on (a reused tag) and the invariant that input used to buy us
  (per-session replacement) — both now observable. The platform behaviour itself
  belongs in the manual test checklist: **post two notifications for one
  session, dismissing the first, and see both displayed** — on each browser the
  board is used in.
- Doc verdict: **the docs were wrong**, in two places, and both are the
  orchestrator's to amend (not touched by this fix):
  - `design-00002` §13, 通知本体 bullet — «`tag` 取会话 id，天然承载「同一会话
    后到替换先到」；替换时带重新提醒标志» is false on macOS Chrome and no longer
    describes the code. It must say: tag 每条唯一（`会话 id:序号`），替换由页面
    自己持有——每会话记住在场的那一条，发下一条前 `close()` 它；不依赖平台的 tag
    语义，`renotify` 随之取消.
  - `spec-00004-FR-6` — «同一会话的通知复用同一标识，**后到替换先到**» states a
    mechanism, and the mechanism is the defect. The requirement is only the
    second half: 同一会话同刻至多一条，后到替换先到（**替换由页面自己完成，不
    依赖通知标识的平台语义**）. `spec-00004-AC-6.3` needs the same edit — its
    GWT is right, its wording must stop naming the shared tag.
- Residual state: none — nothing was persisted, and no notification that was
  dropped can be recovered or is worth recovering.

## Links

- Blocks: record-00019-whiteboard-desktop-notifications-acceptance
- Related: issue-00018-reordered-session-readings-lose-a-waiting-round (same
  field symptom, different cause — resolved, and its fix stands),
  spec-00004-whiteboard-desktop-notifications, design-00002 §13, decision-00010
</content>
