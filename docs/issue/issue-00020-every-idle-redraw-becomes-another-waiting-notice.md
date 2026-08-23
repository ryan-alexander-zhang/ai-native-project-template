---
id: issue-00020-every-idle-redraw-becomes-another-waiting-notice
type: issue
status: resolved
blocks: [record-00019-whiteboard-desktop-notifications-acceptance]
---

# Issue: one wait notifies over and over, because the CLI keeps printing at its idle prompt

> The page counts a waiting notice per flip of the server's waiting mark, and that
> mark flips every time an idle CLI redraws its status line — so a user who asked
> one question and walked away is called back again and again for the same wait.

## 1. Problem

- Observed (field report, macOS Chrome, real board, on the fixed build of
  `issue-00019`): the user asked **one** question in an `ask` session, went away,
  and received notification after notification for that single wait — continuous
  re-reminders, no second question ever asked.
- Expected: `spec-00004-FR-2` — 每会话每次等待至多一条 — and its
  `spec-00004-AC-2.3`: one notice for one wait, and going away again inside that
  wait owes nothing.
- Trigger: any session left waiting while the page is away for longer than the
  CLI's own idle chatter takes to arrive (tens of seconds). It needs no user
  action at all, which is why it reads as a loop.

## 2. Impact

- Affected: every user of the desktop-notification switch, on every browser — the
  defect is in the page's arithmetic, not in a platform's notification
  behaviour. The longer the user stays away, the more notices they get for the
  one wait.
- Since: `77b1928d` in effect (the `issue-00019` fix, where every notice began to
  be *displayed*) · latent since `a73e5684` · Still occurring: yes.
- Severity: it is the inverse failure of the two issues before it, and it is
  worse for the feature's standing than either. `issue-00018` and `issue-00019`
  made the board too quiet; this one makes it noisy enough that the rational
  response is to turn the switch off, which costs the user the whole feature.
  Notification spam also trains the user to dismiss without reading, so the one
  notice that mattered is lost with the rest.
- The three-issue chain, honestly: `issue-00018` was a real reordering race that
  lost a waiting round; `issue-00019` was a reused tag that stopped every second
  notice of a session from being displayed. `issue-00019`'s suppression had been
  **masking** this defect — the flapping was already producing round after round,
  and the platform was silently dropping all but the first. Fixing the display
  unmasked the flapping. All three are distinct causes; none of the earlier two
  fixes is wrong or needs revisiting.

## 3. Root Cause (first principles)

1. Divergence: one wait — one span of the agent sitting at its prompt with
   nothing to do but hear from the user — produces many «not waiting → waiting»
   turns at the page, and the page posts a notice per turn.
2. Mechanism, in two halves that only bite together:
   - `tools/whiteboard/src/sessionManager.ts:354-362` (`armSilence`, called from
     the pty's `onData` at `:284-288`): **any** output at all takes the waiting
     mark down and re-arms the ten-second window, and `:369-373` announces every
     change. The mark is therefore «has printed nothing for 10s», which is a
     proxy for waiting and not the thing itself.
   - `tools/whiteboard/web/src/notify.ts:85-87` (pre-fix) defines a **round** of
     waiting as a count of those flips, and `:162-179` allows one notice per
     round. `web/src/useBoard.ts:169-171` faithfully reports every flip it sees.
     Nothing in the chain asks whether a flip could be the same wait twice.
3. The true root cause is that the page took the server's flag as the *event* it
   needed. The flag is a heuristic reading of silence; the event the spec is
   about — «the agent needs the user» — has a defining property the flag does not
   carry: it cannot recur without the user's own input, and input reaches a
   session only through this page's terminal. The page had that invariant
   available and did not use it. The symptoms it is *not*: a lost round
   (`issue-00018`), a suppressed display (`issue-00019`), a permission taken back
   (`AC-4.3`, silent by design), or anything about the notification API.

- Introduced by: `a73e5684` («feat(whiteboard): desktop notifications when the
  board is away», T3/T6 of plan-00019) — the commit that first turned a flip into
  a notice. The flapping mark itself pre-dates it, from `4dae1088`
  («awaiting-input detection on the session payload», `spec-00003-FR-6`), where
  it was harmless: a badge and a count that flicker cost nothing. Before
  `a73e5684` no flip could produce a notification, so the defect could not occur.

**The flapping, measured.** A probe (`node-pty`, spawning `claude` exactly as
`tools/whiteboard/src/pty.ts` does — same `xterm-color`, 120×30 — typing an
instruction, submitting it the way `sessionManager` does, and replaying
`armSilence`'s own 10s judgment over the timestamped output):

| t | gap since previous output | what arrived |
| --- | --- | --- |
| 4.1s | — | the answer itself; the CLI returns to its prompt |
| 4.4s | 0.3s | last of the answer's redraws |
| 10.4s | 6.0s | a status-line redraw (below the threshold, harmless) |
| **20.4s** | 10.0s of silence | **awaiting → true** — the real wait, one notice owed |
| **64.1s** | **53.7s of silence** | `ESC]777;notify;Claude Code;Claude is waiting for your input` — the CLI's *own* idle notification, on the pty like any other byte → **awaiting → false** |
| **74.1s** | 10.0s of silence | **awaiting → true** again → a second «round», a second notice |

A second run, nine idle minutes long, shows the same thing from a different
source: at 52.9s, after **42.5s** of silence, `Plugin updated: frontend-design`
printed into the status area (→ false), a clearing redraw at 62.9s, the
`777;notify` at 64.5s, and the mark back up at 74.5s — after which that CLI
happened to stay quiet for the remaining seven and a half minutes.

Read honestly, the measurements settle the mechanism and not the rate: the CLI at
an idle prompt is **not** silent, its bursts arrive on its own schedule with gaps
far past the threshold (53.7s and 42.5s here), and each burst past the threshold
costs exactly one spurious round — one in each run, two notices where one was
owed. The rate is nothing the board can bound: every byte the agent prints while
nobody is typing is another round, and a real session has more reasons to print
than this probe gave it (plugin and MCP notices, the rate-limit hint, background
tasks, and the redraw the attached terminal's own resize provokes). The field's
notice-after-notice is this mechanism at whatever rate that session's idle
chatter arrived; nothing in the fix depends on which rate it was.

## 4. Scope (same-cause sweep)

The mechanism is «a flip of the waiting mark is treated as an event of its own».
Every reader of that mark shares it; what differs is what a spurious flip costs.

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `tools/whiteboard/web/src/notify.ts:162-179` (`postWaiting`/`waiting`, one notice per flip) | yes | yes | fixed here — the unit becomes the away stint |
| `tools/whiteboard/web/src/useBoard.ts:169-171` (the diff that reports every flip) | yes | yes, as the feed | unchanged: as a diff it is correct, and `issue-00018`'s ordering fix lives here; the meaning of a turn is decided in `notify.ts`, which is where one fix covers both entries into it |
| `tools/whiteboard/web/src/useBoard.ts:487` (`awaitingCount`, the badge) | yes | yes, cosmetically | not fixed, deliberately: a badge that flickers while the user is away is seen only as its final state, costs nothing, and de-flapping it would mean changing `spec-00003-FR-6` on the server (`decision-00010` §5 forbids server changes this round) |
| `tools/whiteboard/src/sessionManager.ts:354-373` (the mark itself) | it *is* the mechanism | n/a | untouched by design — zero server changes; `spec-00003-FR-6` keeps its semantics |
| `notify.ts` end notices (`ended` → `post`) | no | no | a session ends once and `status` is monotone in the diff's `seen` map, so an end cannot flap |
| `spec-00003-FR-7` end toasts | no | no | same reason |

## 5. Reproduction (test-first)

Written before the fix, in `tools/whiteboard/web/test/notifications.test.tsx`:

- Failing test: `web/test/notifications.test.tsx::posts one notice however often
  the waiting mark flickers while the user stays away` — the page is away, a
  session's mark goes true → false → true with **no return in between** (the
  §3 table's 64.1s/74.1s pair, in the page's terms). Fails with
  `expected [ Notice, Notice ] to have a length of 1 but got 2`: the second flip
  is taken for a second wait.

A guard added with the fix, for the other reading of «the same state, reported
twice»:

- `web/test/notifications.test.tsx::says nothing again when a blur arrives on a
  page that was already away` — browsers report blur and visibility oftener than
  the page changes state, and a repeat reading must not re-run the catch-up.

What no test here reaches is the pty half: the flapping is the CLI's own output
schedule, reproduced by the probe in §3 rather than by a test. `sessionManager`'s
tests already pin `armSilence`'s behaviour, and that behaviour is correct and
unchanged — the defect is what the page concludes from it.

## 6. Fix

- Change (`notify.ts` only, plus the tests): the unit «one notice per wait»
  is counted in becomes the **away stint** — the span between going away and
  coming back — instead of the server's flip. Two per-session sets replace the
  round/sent counters:
  - `notified` — this session's waiting notice has gone out and is still the last
    word. While it holds an id, no further waiting notice of that session is
    posted, from a turn or from the catch-up alike.
  - `returned` — of those, the sessions the user has been in front of the board
    for since. Coming back (the away→present transition) adds every notified id;
    a turn *spends* the mark and clears `notified`, so the next wait notifies.
    Going away again does **not** spend it, which is what keeps
    `spec-00004-AC-2.3` (going away twice inside one wait owes nothing).
- Why this addresses the root cause and not the symptom: it stops asking the
  server's heuristic what a wait is and uses the property a wait actually has —
  a new one needs the user's input, and input only reaches a session through this
  page's terminal, so the user must have come back in between. A flip with no
  return cannot be a new wait, no matter how many arrive; the bound is «at most
  one waiting notice per session per away stint», and it holds however chatty the
  CLI is.
- What the bound still allows, said plainly: a user who comes back, does **not**
  answer, and leaves again can be told once more about that same wait, if the mark
  happens to flip during the new stint — the page cannot tell that flip from the
  lift a real answer would have caused. It is one notice per return, paced by the
  user's own coming and going rather than by the CLI's chatter, and it errs the
  safe way. The signal that would remove even that is a keystroke into the board's
  terminal, i.e. the user's input itself; plumbing it from the terminal into the
  notification layer is more machinery than this defect justifies, and getting it
  wrong costs a *missed* notice, which is the failure mode of the two issues
  before this one.
- Alternatives rejected:
  - **De-flap the server** (require some real quiet before announcing, or judge
    waiting from the terminal's content): it is the honest place for it, and it
    is out of bounds this round — `decision-00010` §5 stands on zero server
    changes — and it would still be a heuristic, only a quieter one.
  - **Clear the mark on coming back** (the plain per-stint reading): simpler by a
    set, but it breaks `spec-00004-AC-2.3` — the user who comes back, sees the
    waiting session, and leaves again without answering would be re-notified.
    Requiring a turn *and* a return is what keeps both ACs true at once.
  - **Rate-limit the notices** (one per session per N minutes): it treats the
    number rather than the cause, needs a threshold nobody can justify, and would
    delay a genuine second wait by however long it was set to.

## 7. Verification

- The failing test of §5 passes, and so does its guard.
- `tools/whiteboard`: `npm test` — 41 files, 1111 tests, all passing.
- `npm run typecheck` — `tsc --noEmit`, no errors.
- `npm run test:coverage` — statements 99.31% / branches 95.63% / functions
  98.68% / lines 99.67%, all four far above the 90% gate and level with the
  pre-change reading; `notify.ts` itself at 100% on all four. The first coverage
  run flaked one commit test of the known intermittent family (`keeps a user save
  and a session wrap-up in two commits`, a `Forbidden` where JSON was expected —
  the sandbox, not the code); it passed on the re-run and in both `npm test`
  runs.
- Three existing tests had to be adjusted, and the adjustment is part of the
  finding — they encoded the flap-prone semantics:
  - `posts a notification for a second round of waiting of the same session`
    (`spec-00004-AC-2.5`) went away once and then flipped the mark true → false →
    true without ever coming back, which is **the defect's own input**. It now
    comes back to answer and leaves again, which is the only way its own Given
    («其等待随后解除») can come about in the field.
  - `keeps the round when two refreshes land out of order` (`issue-00018`) had
    the same shape; with the return inserted it still discriminates exactly what
    it was written for — without the ordering fix the held reading is lost, no
    turn is seen, and no second notice is posted.
  - `keeps the standing notification when the one it replaced reports its close
    late` (`issue-00019`) needed two notices of one session, and now earns them
    the same way.
- Still owed, and the only check that can close it: the domain owner re-running
  the field case on the fixed board in macOS Chrome — ask one question, go away
  for several minutes, and receive exactly one waiting notice.

## 8. Follow-through

- Detection gap: every notification test drove the waiting mark by hand, and none
  of them asked whether the sequence they typed was one a real CLI produces —
  «true → false → true while away» was written *as* two waits because the mark
  said so. The guard beyond the regression test is the probe in §3: the cheapest
  way to answer «what does the pty actually do while nobody is typing» is to
  spawn the agent and log it, and that question belongs in the manual checklist
  for anything derived from `spec-00003-FR-6`. The unit test cannot substitute
  for it — the CLI's idle schedule is not ours to model.
- Doc verdict: **the doc was wrong** — `spec-00004-FR-2`'s «每会话每次等待至多
  一条» ties the guarantee to the server's flag, and the flag flaps, so the
  requirement as written is unimplementable without the noise. The amendments are
  the orchestrator's (the spec round), and this issue recommends:
  - `spec-00004-FR-2`, replacing 「（每会话每次等待至多一条）」:
    「（每会话在**一次离场期间**至多一条等待通知；「一次等待」以用户的离场
    区间为界，**不以 `spec-00003-FR-6` 置位的每次翻转为界**——该标志遇任何输出
    即解除、静默十秒又置位，而 CLI 停在空闲提示符上仍会自行打印，一次等待因此
    到达页面为一连串翻转，见 issue-00020；再弹一条的前提是用户**已回到页面**
    ——真正的新一轮等待必经用户输入，而输入只经白板终端到达）」。
  - `spec-00004-AC-2.5`, whose Given must name the return, since without it the
    Given describes the defect's input: 「Given 一个会话的一次等待已通知过、
    用户已回到页面并作答、其等待随后解除」。
  - A new `spec-00004-AC-2.6` (spec-00004-FR-2) for what this issue is:
    「Given 开关生效、页面离场、某会话的一次等待已通知过
    When 用户未回到页面而该会话的等待标志反复解除又置位（CLI 空闲重绘）
    Then 不再弹新的桌面通知——该次离场至多一条」。
  - `design-00002` §13's 通知触发/补发 bullet needs the same unit: 去抖以离场
    区间为界，页面自己记「已通知」与「其后是否回来过」，转折**且**回来过才再
    弹一条；服务端等待标志的抖动不入通知层（徽标照旧，属 `spec-00003-FR-6`）。
- Residual state: none. Nothing was persisted, and the notices already sent
  cannot be recalled.

## Links

- Blocks: record-00019-whiteboard-desktop-notifications-acceptance
- Related: issue-00019-a-reused-notification-tag-is-never-displayed-again (its
  fix unmasked this one; that fix stands),
  issue-00018-reordered-session-readings-lose-a-waiting-round (the first of the
  chain; its fix stands and its test is adjusted here, not undone),
  spec-00004-whiteboard-desktop-notifications, spec-00003-FR-6, design-00002 §13,
  decision-00010 §5
</content>
