---
id: issue-00018-reordered-session-readings-lose-a-waiting-round
type: issue
status: resolved
blocks: [record-00019-whiteboard-desktop-notifications-acceptance]
---

# Issue: two board reads at once fold their session readings in out of order, losing a round of waiting

> The board's one read path lets a stale session listing land after a fresher
> one, so a `false → true` waiting turn is never seen and the session sits
> waiting with nobody told.

## 1. Problem

- Observed (field report, manual testing on a real board): an `ask` session
  notifies on its first round of waiting; the user answers, the agent replies
  and goes silent again, and no second desktop notification arrives.
- Expected: `spec-00004-FR-2` — every `false → true` waiting turn is a new round
  of waiting and is owed one notification. `design-00002` §13 says the same in
  the design's own words: «`false → true` 的转变即一次新的**等待回合**».
- Trigger: two `refresh` reads in flight at once. Both `GET /api/graph` and
  `GET /api/sessions` are read per refresh, and the refreshes are fired from
  every docs-change frame (`spec-00001-FR-42`) as well as from every board
  action — so a read taken before the server flipped a mark can land after a
  read taken afterwards.

**What the report's own suspicion was, and what it is not.** The reported
mechanism — the awaiting diff failing to re-arm on a second `false → true` —
does **not** exist. The round bookkeeping in `notify.ts` is correct: `waiting`
increments the round on every turn and `postWaiting` deduplicates on the round
number, so a second round posts a second notice. This was checked exhaustively:
every 3-step ordering of {go away, come back, mark set, mark cleared} from both
start states — 128 sequences — matches a model of what FR-2 owes (checked
during triage, not kept: 128 board renders for one invariant is not a test suite
worth carrying). Two of those sequences are kept as named tests, below. The
defect is one layer out: the diff is right, and it is fed readings out of order.

## 2. Impact

- Affected: every board with desktop notifications in effect — a lost round is a
  session left waiting with the user away and nothing calling them back, which
  is the whole of what `spec-00004` was for. The same reordering also makes the
  in-page end toast (`spec-00003-FR-7`) fire twice for one ending, which every
  board sees whether or not notifications are on.
- Since: `a73e5684` (plan-00019 T3, 2026-08-23) · Still occurring: yes, until
  the fix below.
- Severity: the feature's central promise fails silently and the user cannot
  tell — there is no error, no toast, and the badge (which is drawn from the
  same listing) looks right. Probability per round is low, since it needs two
  reads overlapping across a mark flip; the cost when it happens is the whole
  round. A duplicate end toast is cosmetic by comparison.

## 3. Root Cause (first principles)

1. **Divergence.** The board is told about a waiting turn by comparing two
   consecutive readings of the session listing. The server publishes both flips
   (`sessionManager.ts:369` announces the set and the clear alike, verified by
   `test/sessionManager.test.ts` «takes the mark down as soon as the session
   speaks again»), so the readings exist. The board still fails to see the turn.
2. **The smallest mechanism.** `tools/whiteboard/web/src/useBoard.ts:215` reads
   the graph and the listing, `await`s them, and only then calls `announce`
   (`useBoard.ts:152`), which diffs the listing against `seen.current` **as it
   stands when the read lands** rather than as it stood when the listing was
   taken. Nothing serialises the reads, so with read A (`awaiting: false`) still
   in flight and read B (`awaiting: true`) landing first, B is diffed against
   round one's `true` — no turn — and A then writes `false` in behind it. The
   turn between them is never anybody's difference.
3. **The true root cause:** the one read path applies its readings in response
   order, not in the order they were taken. It is *not* the round bookkeeping in
   `notify.ts`, *not* the catch-up's `?? 1` default, *not* the away judgment, and
   *not* the server — all four were checked and all four are correct.
   Why this stays lost rather than self-correcting: a session that has gone
   silent stays `awaiting: true` with no further output, so there is no later
   turn for the diff to catch. The round is gone until the user answers.

- Introduced by: `a73e5684` (plan-00019 T3). Before it, `seen` carried only
  `status`, which is monotonic — a session goes `running → ended` and stays
  there, so a reordered reading could at worst re-announce an end. That commit
  put the **non-monotonic, transient** `awaiting` flag into the same
  order-dependent diff, where a lost reading is a lost event. The reordering
  window existed before; the class of loss did not.

## 4. Scope (same-cause sweep)

The root cause is «the diff is fed readings in response order», so every arm of
that one diff shares it.

| Site | Same pattern | Affected | Action |
| --- | --- | --- | --- |
| `tools/whiteboard/web/src/useBoard.ts:152` — the awaiting arm of `announce` | yes | yes | the reported defect; fixed at the read path |
| `tools/whiteboard/web/src/useBoard.ts:152` — the ending arm of `announce` | yes | yes | a stale `running` reading landing late puts `running` back into `seen`, and the next reading toasts and notifies the same ending a second time — against `spec-00003-FR-7`'s one-toast-per-end. Reproduced, fixed by the same change |
| `tools/whiteboard/web/src/useBoard.ts:215` — `setGraph` / `setPlaced` / `setSessions` | yes | yes | a stale read could also draw an older graph over a newer one; the same ordering fixes it |
| `tools/whiteboard/web/src/notify.ts:147` — the catch-up reads `listing.current` (the `sessions` state) | yes | yes | it can only be as ordered as the reads that set it; fixed by the same change, not separately |
| `tools/whiteboard/web/src/notify.ts:158` — `waiting`'s round counter, and `sent`'s dedup | no | no | correct as written; 128 orderings plus the two kept tests show a second round posts a second notice |
| `tools/whiteboard/src/sessionManager.ts:369` — `setAwaiting` | no | no | announces both the set and the clear, each exactly once, and re-arms after the mark goes up (`test/sessionManager.test.ts`). **No server change was needed, and none was made** — `spec-00004`'s zero-server-change constraint (`decision-00010` §5) stands |

## 5. Reproduction (test-first)

Both written before the fix and failing on the unfixed code, both in
`tools/whiteboard/web/test/notifications.test.tsx`:

- `keeps the round when two refreshes land out of order` — the answered reading
  is held back on the graph half of its own refresh while the «silent again»
  reading lands first. Fails with `expected [ Notice ] to have a length of 2 but
  got 1`.
- `announces an end once when two refreshes land out of order` — the same
  reordering on the ending arm. Fails with `expected [ Notice, Notice ] to have
  a length of 1 but got 2` (and the toast is doubled with it).

Two further tests pin the behaviour the field report expected, and pass before
and after — they are the acceptance gap made executable, not reproductions:

- `posts a notification for a second round of waiting of the same session`
- `posts the second round notice when the first round was answered in front of
  the board`

## 6. Fix

- Change: `useBoard.ts` — the read body becomes `read`, and `refresh` queues each
  read behind the one in flight (`reading` ref, `useBoard.ts:139`, `refresh` at
  `:259`). Readings are therefore folded into `seen` in the order they were
  taken. A read that failed does not block the queue.
- Why this addresses the root cause and not the symptom: the diff needs to see
  every reading in order to be correct, and that is now what it is given. It
  fixes all four affected sites at once rather than the awaiting arm alone.
- Alternatives rejected:
  - Drop a stale listing by sequence number — the newer reading has already been
    diffed against a baseline that is missing the older one, so the round is
    still lost.
  - Give each waiting round an identity on the server — a server change, which
    `decision-00010` §5 rules out.
  - Notify off `awaiting` being true rather than off the turn — that is a notice
    on every refresh while a session waits, against `spec-00004-FR-2`'s «每会话
    每次等待至多一条».

## 7. Verification

- The two reproductions above now pass; the two second-round tests still pass.
- `tools/whiteboard/web/test/board.test.tsx` — `takes the next read after one
  that failed`, so the queue is not something a failure blocks.
- `npm test`: 41 files, 1106 tests. One run had
  `commits what the stopped session wrote, named by its kind` fail; it passes on
  its own and on a re-run — the same git-worktree intermittent family as the
  two already known.
- `npm run typecheck`: clean.
- `npm run test:coverage`: statements 99.30% / branches 95.63% / functions
  98.67% / lines 99.67% — identical to the pre-change baseline, no threshold
  touched.

## 8. Follow-through

- **Detection gap.** Every AC in `spec-00004` is a single-transition case: AC-2.1
  is the first turn, AC-2.2 is the lift, AC-2.3 is the catch-up and its dedup,
  AC-2.4 is two sessions. **Nothing pins a second round of waiting on one
  session**, and nothing pins the board's readings being applied in order. So a
  diff that only ever re-armed once, and a read path that reorders, both pass the
  whole set. The guard added beyond the two reproductions is the two second-round
  tests, which hold the behaviour the missing AC would ask for.
- **Doc verdict: the doc was missing an AC.** The code was non-conformant with
  `spec-00004-FR-2` as written (FR-2 already says «每会话每次等待至多一条», which
  is per round, not per session), so the FR needs no change — but the acceptance
  set does not cover it, and that is what let this ship. Recommended addition
  (the amendment round is the orchestrator's):

  > - **spec-00004-AC-2.5** (spec-00004-FR-2)
  >   Given 一个会话的一次等待已通知过、其等待随后解除
  >   When 该会话再次转入等待输入且页面处于离场态
  >   Then 为这一次新的等待回合再弹一条桌面通知（每回合一条，回合不因
  >   已通知过前一回合而被判重）

  In English, for the record: given a session whose earlier round of waiting has
  already been notified and whose waiting was then lifted, when it turns to
  waiting again with the page away, then a further notification is posted for
  that new round — one per round, and an earlier round having been notified does
  not deduplicate a later one.
- The two new second-round tests carry `// issue-00018` and should be retagged to
  `spec-00004-AC-2.5` when that AC lands, and added to
  `record-00019-whiteboard-desktop-notifications-acceptance`.
- **Residual state:** none. Nothing was written to disk by the defect; a lost
  notification is lost and the session is still there to be answered.

## Links

- Blocks: record-00019-whiteboard-desktop-notifications-acceptance — its AC-2.x
  pass rows are what this defect contradicts.
- Related: spec-00004-whiteboard-desktop-notifications, design-00002-whiteboard-ui §13,
  decision-00010-whiteboard-desktop-notifications, plan-00019-whiteboard-desktop-notifications
