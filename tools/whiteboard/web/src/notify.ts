import { useCallback, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import type { SessionListing } from './api.ts'

/**
 * Desktop notifications: what calls the user back when the board is not in front
 * of them (spec-00004). Nothing here reaches the server — the events are the
 * session listing the board already re-reads, and the whole feature is the page's
 * own (decision-00010 §5).
 */

/**
 * The switch's boolean, in the same local layer the panel sizes live in
 * (design-00002 §13). Both states are written: «off» is a decision the user made
 * and it has to survive the next open as much as «on» does (spec-00004-AC-1.5).
 */
const STORAGE_KEY = 'whiteboard-desktop-notifications'

/** What the switch reads as, and the only three states there are (design-00002 §13). */
export type NotifyState = 'off' | 'inactive' | 'active'

/** Where a refused permission is turned back on, since the page may not ask again. */
export const BLOCKED =
  'desktop notifications are blocked — turn them on for this site in your browser settings'

/**
 * The constructor and the permission, read off the global at each use rather than
 * imported. A browser that has neither is not an error: the badge, the toast and
 * the panel carry on and nothing is said about it, which is the silent
 * degradation spec-00004-FR-4 asks for. It is also the one seam a test stands a
 * notification in through.
 */
type Notifier = typeof Notification
function notifier(): Notifier | undefined {
  return (globalThis as { Notification?: Notifier }).Notification
}

/** «unsupported» is a fourth reading of the permission, and it is never granted. */
function permissionNow(): NotificationPermission | 'unsupported' {
  return notifier()?.permission ?? 'unsupported'
}

/**
 * Away: the page is not in front of the user — hidden (a background tab) or in a
 * window that does not have the focus (working in another window). Both halves
 * are needed; visibility alone misses the main case (design-00002 §13,
 * decision-00010 §2).
 */
function isAway(): boolean {
  return document.hidden || !document.hasFocus()
}

function remember(on: boolean): void {
  localStorage.setItem(STORAGE_KEY, on ? 'on' : 'off')
}

function wantedNow(): boolean {
  return localStorage.getItem(STORAGE_KEY) === 'on'
}

/**
 * The desktop notifications of one page (spec-00004). `sessions` is the listing
 * the board already holds — the events are differences between two readings of
 * it, which the caller diffs and reports through `waiting` and `ended`; `open` is
 * what a click does once the session has been found, the same act the session
 * panel's row performs (spec-00004-FR-5).
 */
export function useDesktopNotifications(sessions: SessionListing[], open: (session: SessionListing) => void) {
  const [wanted, setWanted] = useState(wantedNow)
  const [permission, setPermission] = useState(permissionNow)

  // Everything a notification is posted from is read through a ref: the posting
  // hangs off the board's refresh, which must not be rebuilt when the switch
  // moves (it would re-dial the docs-change channel, design-00002 §10).
  const switchedOn = useRef(wanted)
  const away = useRef(isAway())
  const listing = useRef(sessions)
  const opener = useRef(open)
  /**
   * The waiting round each session is in: its own count of «not waiting →
   * waiting» turns, page-local (design-00002 §13). It is the key both the
   * catch-up and the dedup are decided on, which is what makes «one notice per
   * round of waiting» answerable (spec-00004-AC-2.3).
   */
  const round = useRef(new Map<string, number>())
  /** The round each session's waiting notice has already gone out for. */
  const sent = useRef(new Map<string, number>())
  /**
   * The notification each session has standing, so the next one of that session
   * can take its place. «同一会话同刻至多一条» is the page's own to keep: a tag
   * cannot be leaned on for it — on macOS Chrome a tag whose notification has
   * been dismissed is never displayed again, so the second notice of a session
   * was silently dropped (issue-00019).
   */
  const standing = useRef(new Map<string, Notification>())
  /** Notices posted, ever: what makes every tag its own (issue-00019). */
  const posted = useRef(0)

  // The handles outlive nothing: a page going away takes its notifications'
  // onclick with it (spec-00004 §6), and holding the objects past that is a leak.
  useEffect(() => () => standing.current.clear(), [])

  useEffect(() => {
    switchedOn.current = wanted
    listing.current = sessions
    opener.current = open
  }, [wanted, sessions, open])

  /**
   * One notification, or none. Nothing is posted while the switch is off, while
   * the permission is anything but granted, or while the page is in front of the
   * user — the badge and the toast are what carry those (spec-00004-FR-4). The
   * title and the body are built out of the kind, the document id and the state,
   * and out of nothing else: a notification lands in the system's notification
   * centre (spec-00004-FR-6).
   */
  const post = useCallback((session: SessionListing, status: string): boolean => {
    const api = notifier()
    // The permission is read here and not remembered: a browser can take it back
    // while the page is not being looked at, and what that must produce is
    // silence — no notification, no error, no second request
    // (spec-00004-FR-4, AC-4.3).
    if (api === undefined || api.permission !== 'granted' || !switchedOn.current || !away.current) return false
    // Never a stack of one session's notices, and never a tag reused: the one
    // that session has standing is closed here, by us (spec-00004-FR-6,
    // issue-00019).
    standing.current.get(session.id)?.close()
    posted.current += 1
    const notice = new api(`${session.kind} · ${session.sourceId}`, {
      tag: `${session.id}:${posted.current}`,
      body: status,
    })
    standing.current.set(session.id, notice)
    /** Gone from the screen, however it went: there is nothing left to replace. */
    const forget = () => {
      if (standing.current.get(session.id) === notice) standing.current.delete(session.id)
    }
    notice.onclose = forget
    notice.onclick = () => {
      forget()
      // Best effort, and said as such: whether the window comes forward is the
      // browser's and the system's to decide (spec-00004-FR-5).
      window.focus()
      // Resolved against the listing as it stands now, not as it was when the
      // notice went out: a session the server no longer holds — it restarted —
      // is refused, and the view does not move (spec-00004-AC-5.2).
      const current = listing.current.find((one) => one.id === session.id)
      if (current === undefined) {
        toast.error(`no session ${session.id} on the board`)
        return
      }
      opener.current(current)
    }
    return true
  }, [])

  /**
   * The waiting notice of one session, at most one per round. A session already
   * waiting when the board first read the listing is in round one: no turn was
   * there to be seen, and the catch-up still owes it a notice.
   */
  const postWaiting = useCallback(
    (session: SessionListing) => {
      const current = round.current.get(session.id) ?? 1
      round.current.set(session.id, current)
      if (sent.current.get(session.id) === current) return
      if (post(session, 'awaiting')) sent.current.set(session.id, current)
    },
    [post],
  )

  /** A session has just turned to waiting: a new round, and a notice for it. */
  const waiting = useCallback(
    (session: SessionListing) => {
      round.current.set(session.id, (round.current.get(session.id) ?? 0) + 1)
      postWaiting(session)
    },
    [postWaiting],
  )

  /** A session has just ended, however it ended (spec-00004-FR-3). */
  const ended = useCallback(
    (session: SessionListing) => {
      post(session, session.status)
    },
    [post],
  )

  // Going away is an event of its own: what was already waiting when the user
  // left would otherwise never be said, which is the very thing being missed
  // (spec-00004-FR-2, decision-00010 §2). The permission is re-read here too —
  // it can be taken back while the page is not being looked at, and the switch
  // then shows «inactive» on the way back with nothing else happening
  // (spec-00004-FR-4, AC-4.3).
  useEffect(() => {
    const check = () => {
      setPermission(permissionNow())
      const now = isAway()
      const was = away.current
      away.current = now
      if (!now || was) return
      for (const session of listing.current) {
        if (session.status === 'running' && session.awaiting === true) postWaiting(session)
      }
    }
    document.addEventListener('visibilitychange', check)
    window.addEventListener('focus', check)
    window.addEventListener('blur', check)
    return () => {
      document.removeEventListener('visibilitychange', check)
      window.removeEventListener('focus', check)
      window.removeEventListener('blur', check)
    }
  }, [postWaiting])

  /**
   * The switch. Turning it off is immediate silence and is remembered; turning it
   * on is the **only** place a permission is ever asked for — the user's click is
   * the gesture browsers want, and no other moment has it (spec-00004-FR-1,
   * AC-1.3). A permission already granted is not asked for again (AC-1.4), and a
   * denied one cannot be asked again at all, so it is not: the boolean stays
   * false and the user is pointed at the browser's own settings (AC-1.2,
   * decision-00010 §4).
   */
  const toggle = useCallback(() => {
    if (wanted) {
      remember(false)
      setWanted(false)
      return
    }
    const api = notifier()
    const settled = api === undefined ? Promise.resolve<NotificationPermission>('denied') : ask(api)
    void settled.then((answer) => {
      const granted = answer === 'granted'
      setPermission(api === undefined ? 'unsupported' : answer)
      remember(granted)
      setWanted(granted)
      if (!granted) toast.error(BLOCKED)
    })
  }, [wanted])

  // Off, on but not in effect, or in effect: two inputs — the boolean and the
  // permission — and three readings of them. A presentation with only two of the
  // three cannot tell a permission that died from a switch the user turned off,
  // which is the confusion design-00002 §13 names.
  const state: NotifyState = !wanted ? 'off' : permission === 'granted' ? 'active' : 'inactive'

  return { state, toggle, waiting, ended }
}

/** What the browser answers: the standing permission, or the user's answer to the one request. */
function ask(api: Notifier): Promise<NotificationPermission> {
  return api.permission === 'default' ? api.requestPermission() : Promise.resolve(api.permission)
}
