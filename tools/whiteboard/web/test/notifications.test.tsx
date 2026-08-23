// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import { Board } from '../src/Board.tsx'
import { type SessionListing, api } from '../src/api.ts'
import { BLOCKED } from '../src/notify.ts'

// Rendering the whole board and pushing a refresh through it is heavier than the
// default five seconds allows on a loaded machine; none of these cases measures
// how long anything takes.
vi.setConfig({ testTimeout: 30_000 })

/** Where the switch's boolean is kept (design-00002 §13); a test starts from a clean one. */
const SWITCH_KEY = 'whiteboard-desktop-notifications'

/** Something worth not leaking: the document's own words, and a session's own output. */
const SECRET = 'the credentials are hunter2'

function node(overrides: Partial<DocNode> = {}): DocNode {
  return {
    id: 'prd-00001-x',
    path: 'prd/a.md',
    type: 'prd',
    status: 'draft',
    title: `Whiteboard PRD — ${SECRET}`,
    relations: {},
    ok: true,
    problems: [],
    ...overrides,
  }
}

const IDEA = node({ id: 'idea-00001-x', type: 'idea', status: 'active', title: 'Idea', path: 'idea/a.md' })
const GRAPH: DocGraph = {
  nodes: [node(), IDEA],
  edges: [{ from: 'prd-00001-x', to: 'idea-00001-x', relation: 'parent', ok: true, declaredTargets: ['idea-00001-x'] }],
  issues: [],
  idOwners: {},
  diagnostics: [],
}

/** One row of `GET /api/sessions` (design-00001 §7). */
function listing(overrides: Partial<SessionListing> = {}): SessionListing {
  return {
    id: 's1',
    kind: 'clarify',
    agent: 'claude',
    sourceId: 'prd-00001-x',
    status: 'running',
    startedAt: '2026-02-01T09:00:00.000Z',
    ...overrides,
  }
}

/**
 * The browser's notification, under the test's hand. jsdom implements none, which
 * is exactly why `notify.ts` reads the constructor off the global rather than
 * importing one (design-00002 §13): that seam is where this stands in, and a
 * browser without it is the silent degradation spec-00004-FR-4 asks for.
 */
class Notice {
  static permission: NotificationPermission = 'default'
  /** What the user answers the one request with. */
  static answer: NotificationPermission = 'granted'
  /** How many times the page has asked — spec-00004-AC-1.3 is a claim about this number. */
  static requests = 0
  static made: Notice[] = []
  onclick: (() => void) | null = null
  onclose: (() => void) | null = null
  /**
   * How many times the page closed this one. Replacement is the page's own act
   * now, not the browser's reading of a shared tag (issue-00019), so
   * `spec-00004-AC-6.3` is a claim about this number.
   */
  closed = 0

  constructor(
    readonly title: string,
    readonly options: NotificationOptions = {},
  ) {
    Notice.made.push(this)
  }

  close(): void {
    this.closed += 1
    this.onclose?.()
  }

  static requestPermission(): Promise<NotificationPermission> {
    Notice.requests += 1
    Notice.permission = Notice.answer
    return Promise.resolve(Notice.answer)
  }
}

/** Everything a notification carries, as one string: what must and must not be in it. */
function content(notice: Notice): string {
  return `${notice.title} ${JSON.stringify(notice.options)}`
}

/**
 * The sockets the board dials: the docs-change channel, which a test signals to
 * make a refresh happen (spec-00001-FR-42), and one per session terminal, which
 * only has to answer.
 */
class Socket {
  static channel?: Socket
  static readonly OPEN = 1
  readyState = 1
  private listeners: Record<string, Array<(event: { data: string }) => void>> = {}

  constructor(readonly url: string) {
    if (url.includes('/api/events')) Socket.channel = this
  }

  addEventListener(type: string, listener: (event: { data: string }) => void) {
    ;(this.listeners[type] ??= []).push(listener)
  }

  removeEventListener() {}
  send() {}
  close() {}

  /** One «docs changed» frame, which carries nothing of its own (design-00001 §6). */
  signal() {
    for (const listener of this.listeners.message ?? []) listener({ data: '' })
  }
}

/** Let the read chain a signal starts land; React only takes in what arrives inside an act. */
async function settle(links = 3) {
  for (let link = 0; link < links; link += 1) {
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
  }
}

/** A change pushed from disk, and nothing else: no click, no keystroke. */
async function push() {
  await act(async () => Socket.channel!.signal())
  await settle()
}

/** What `GET /api/sessions` answers with; a test moves the server by moving this. */
let served: SessionListing[] = []

function serve(sessions: SessionListing[] = []) {
  served = sessions
  vi.spyOn(api, 'graph').mockImplementation(async () => structuredClone(GRAPH))
  vi.spyOn(api, 'sessions').mockImplementation(async () => served)
  vi.spyOn(api, 'transitions').mockResolvedValue(['active'])
  vi.spyOn(api, 'nextSteps').mockResolvedValue([{ next: 'spec', carry: 'parent' }])
  vi.spyOn(api, 'config').mockResolvedValue({
    types: { idea: 'living', prd: 'living', spec: 'living' },
    relations: ['parent'],
    flow: { prd: [{ next: 'spec', carry: 'parent' }] },
    focus: { prd: 'roles, scope, and the value trade-offs' },
    agents: [{ name: 'claude', command: 'claude', args: [] }],
    entry: [],
    carries: {},
    maxSessions: 3,
    clarifiable: ['prd'],
    auditable: ['spec', 'rule', 'design'],
  })
}

/** The board, up, with the prd node drawn. */
async function openBoard() {
  const rendered = render(<Board />)
  await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
  return rendered
}

/** The switch on and the permission standing: notifications are in effect (design-00002 §13). */
function enabled() {
  localStorage.setItem(SWITCH_KEY, 'on')
  Notice.permission = 'granted'
}

/** Away = hidden or unfocused (design-00002 §13); both halves are driven from here. */
let hidden = false
let focused = true

/** Off to another window: the page is still visible, and no longer has the focus. */
async function leave() {
  focused = false
  await act(async () => void window.dispatchEvent(new Event('blur')))
}

/** Back on the page. */
async function comeBack() {
  focused = true
  await act(async () => void window.dispatchEvent(new Event('focus')))
}

/** The tab put in the background: the other half of away. */
async function hide() {
  hidden = true
  await act(async () => void document.dispatchEvent(new Event('visibilitychange')))
}

/** What the switch reads as, off its own accessible name. */
function switchLabel(): string {
  return screen.getByRole('button', { name: /^Desktop notifications:/ }).getAttribute('aria-label') ?? ''
}

const toggle = async () => {
  await act(async () => void fireEvent.click(screen.getByRole('button', { name: /^Desktop notifications:/ })))
}

beforeEach(() => {
  Socket.channel = undefined
  hidden = false
  focused = true
  localStorage.removeItem(SWITCH_KEY)
  Notice.permission = 'default'
  Notice.answer = 'granted'
  Notice.requests = 0
  Notice.made = []
  vi.stubGlobal('WebSocket', Socket)
  vi.stubGlobal('Notification', Notice)
  vi.spyOn(document, 'hidden', 'get').mockImplementation(() => hidden)
  vi.spyOn(document, 'hasFocus').mockImplementation(() => focused)
  vi.spyOn(window, 'focus').mockImplementation(() => {})
  vi.spyOn(toast, 'error').mockImplementation(() => 'id')
  vi.spyOn(toast, 'message').mockImplementation(() => 'id')
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('the desktop notification switch', () => {
  /**
   * spec-00004-AC-1.1 — the switch is off and the permission undecided; one click
   * asks, the browser grants, and the switch is in effect and stays that way the
   * next time the board is opened.
   */
  it('takes effect when the user turns it on and the browser grants', async () => {
    serve()
    await openBoard()
    expect(switchLabel()).toBe('Desktop notifications: off')

    await toggle()

    expect(Notice.requests).toBe(1)
    await waitFor(() => expect(switchLabel()).toBe('Desktop notifications: on'))

    // Opened again: the boolean was remembered and the permission still stands.
    cleanup()
    await openBoard()
    expect(switchLabel()).toBe('Desktop notifications: on')
  })

  /**
   * spec-00004-AC-1.2 — the browser refuses, so the switch falls back to off and
   * the user is told where it can be turned on by hand (the page may not ask
   * again, decision-00010 §4).
   */
  it('falls back to off and points at the browser settings when the request is refused', async () => {
    Notice.answer = 'denied'
    serve()
    await openBoard()

    await toggle()

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith(BLOCKED))
    expect(switchLabel()).toBe('Desktop notifications: off')
    expect(localStorage.getItem(SWITCH_KEY)).toBe('off')
  })

  /**
   * spec-00004-AC-1.3 — a board whose switch was never touched: a session runs to
   * waiting with the page away, and nothing asks for a permission and nothing is
   * posted. The switch says off.
   */
  it('asks for no permission at all until it is turned on', async () => {
    serve()
    await openBoard()
    await leave()

    served = [listing({ awaiting: true })]
    await push()

    expect(Notice.requests).toBe(0)
    expect(Notice.made).toHaveLength(0)
    expect(switchLabel()).toBe('Desktop notifications: off')
  })

  /**
   * spec-00004-AC-1.4 — the permission was granted in some earlier visit, so
   * turning the switch on asks for nothing and simply takes effect.
   */
  it('asks for nothing when the permission is already granted', async () => {
    Notice.permission = 'granted'
    serve()
    await openBoard()
    expect(switchLabel()).toBe('Desktop notifications: off')

    await toggle()

    expect(Notice.requests).toBe(0)
    await waitFor(() => expect(switchLabel()).toBe('Desktop notifications: on'))
  })

  /**
   * spec-00004-AC-1.5 — turned off, it is silent at once: a session ends with the
   * page away and nothing is posted. The next open still says off.
   */
  it('goes silent at once when it is turned off, and stays off', async () => {
    enabled()
    serve([listing()])
    await openBoard()
    expect(switchLabel()).toBe('Desktop notifications: on')

    await toggle()
    await waitFor(() => expect(switchLabel()).toBe('Desktop notifications: off'))
    await leave()
    served = [listing({ status: 'exited', exitCode: 0 })]
    await push()

    expect(Notice.made).toHaveLength(0)
    cleanup()
    await openBoard()
    expect(switchLabel()).toBe('Desktop notifications: off')
  })

  /**
   * spec-00004-FR-4 — a browser with no notification support at all: the switch
   * shows that it is not in effect, nothing is posted, and clicking it says where
   * to turn them on rather than throwing (silent degradation, design-00002 §13).
   */
  it('degrades silently on a browser that has no notifications', async () => {
    localStorage.setItem(SWITCH_KEY, 'on')
    vi.stubGlobal('Notification', undefined)
    serve([listing()])
    await openBoard()
    expect(switchLabel()).toBe('Desktop notifications: needs permission')

    await leave()
    served = [listing({ status: 'exited', exitCode: 0 })]
    await push()

    expect(Notice.made).toHaveLength(0)
    // Off, and then a click that cannot ask anybody: the same pointer at the
    // browser's own settings, and no error.
    await toggle()
    await waitFor(() => expect(switchLabel()).toBe('Desktop notifications: off'))
    await toggle()
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith(BLOCKED))
    expect(switchLabel()).toBe('Desktop notifications: off')
  })
})

describe('being called back to a session that is waiting', () => {
  /**
   * spec-00004-AC-2.1 — the tab is in the background and a session starts waiting:
   * one notification, carrying its kind, its document and the state it is in.
   */
  it('posts a notification when a session starts waiting while the page is away', async () => {
    enabled()
    serve()
    await openBoard()
    await hide()

    served = [listing({ awaiting: true })]
    await push()

    expect(Notice.made).toHaveLength(1)
    const notice = Notice.made[0]!
    expect(notice.title).toBe('clarify · prd-00001-x')
    expect(notice.options.body).toBe('awaiting')
  })

  /**
   * spec-00004-AC-2.2 — the session goes on printing, so the waiting is over: that
   * is the user's own doing and there is nothing to say about it.
   */
  it('says nothing when the waiting is lifted', async () => {
    enabled()
    serve()
    await openBoard()
    await leave()
    served = [listing({ awaiting: true })]
    await push()
    expect(Notice.made).toHaveLength(1)

    served = [listing({ awaiting: undefined })]
    await push()

    expect(Notice.made).toHaveLength(1)
  })

  /**
   * spec-00004-AC-2.3 — the session was already waiting while the user was looking
   * at the board, so nothing was posted; going away is what it is owed a notice
   * for, and going away twice within the same round of waiting does not owe a
   * second one (design-00002 §13).
   */
  it('catches up on a session already waiting when the page goes away, once', async () => {
    enabled()
    serve([listing({ awaiting: true })])
    await openBoard()
    expect(Notice.made).toHaveLength(0)

    await leave()
    expect(Notice.made).toHaveLength(1)
    expect(Notice.made[0]!.options.body).toBe('awaiting')

    await comeBack()
    await leave()

    expect(Notice.made).toHaveLength(1)
  })

  /**
   * spec-00004-AC-2.5 (issue-00018) — the second round of waiting of the same session. The agent
   * asked, the user answered, the agent worked and went silent again: that is a
   * new "not waiting → waiting" turn, and spec-00004-FR-2 owes it its own
   * notification.
   */
  it('posts a notification for a second round of waiting of the same session', async () => {
    enabled()
    serve()
    await openBoard()
    await leave()

    served = [listing({ awaiting: true })]
    await push()
    expect(Notice.made).toHaveLength(1)

    // Answered: the user came back to the board to answer — the only way a new
    // turn can come about at all, since input reaches a session through this
    // page's terminal and nowhere else (issue-00020) — the agent prints again so
    // the mark comes down, and the user goes away once more.
    await comeBack()
    served = [listing({ awaiting: false })]
    await push()
    await leave()
    expect(Notice.made).toHaveLength(1)

    // Silent again — a second round, and a second notice.
    served = [listing({ awaiting: true })]
    await push()

    expect(Notice.made).toHaveLength(2)
    expect(Notice.made[1]!.options.body).toBe('awaiting')
    // A second round of the same session is a notification of its own, and it
    // does not reuse the first round's tag (issue-00019).
    expect(Notice.made[1]!.options.tag).not.toBe(Notice.made[0]!.options.tag)
  })

  /**
   * spec-00004-AC-2.5 (issue-00018) — the first round was answered from in front of the board, so
   * nothing was posted for it (the badge carried it); the user leaves before the
   * second round, and that round is still owed its notice.
   */
  it('posts the second round notice when the first round was answered in front of the board', async () => {
    enabled()
    serve()
    await openBoard()

    // Round one, with the user looking at the board: badge only.
    served = [listing({ awaiting: true })]
    await push()
    expect(Notice.made).toHaveLength(0)

    // Answered, and only then does the user go away.
    served = [listing({ awaiting: false })]
    await push()
    await leave()
    expect(Notice.made).toHaveLength(0)

    served = [listing({ awaiting: true })]
    await push()

    expect(Notice.made).toHaveLength(1)
    expect(Notice.made[0]!.options.body).toBe('awaiting')
  })

  /**
   * issue-00020 — the server's waiting mark is not a wait: any output at all takes
   * it down and ten seconds of silence puts it back (spec-00003-FR-6), and a CLI
   * sitting at an idle prompt redraws its status line long after it stopped
   * answering. One wait therefore reaches the page as turn after turn. A real new
   * turn needs the user's own input and input only reaches a session through this
   * page's terminal, so while the user has not come back it is the same wait and
   * is owed nothing more.
   */
  it('posts one notice however often the waiting mark flickers while the user stays away', async () => {
    enabled()
    serve()
    await openBoard()
    await leave()

    served = [listing({ awaiting: true })]
    await push()
    expect(Notice.made).toHaveLength(1)

    // A cosmetic redraw at the idle prompt takes the mark down; ten seconds of
    // silence later it is back up. Nobody has answered anything.
    served = [listing({ awaiting: false })]
    await push()
    served = [listing({ awaiting: true })]
    await push()

    expect(Notice.made).toHaveLength(1)
  })

  /**
   * issue-00020 — the browser reports blur and visibility oftener than the page
   * changes state. A reading that says what the last one said is not a new
   * departure, and the catch-up is not owed a second run for it.
   */
  it('says nothing again when a blur arrives on a page that was already away', async () => {
    enabled()
    serve([listing({ awaiting: true })])
    await openBoard()

    await leave()
    expect(Notice.made).toHaveLength(1)

    await leave()

    expect(Notice.made).toHaveLength(1)
  })

  /**
   * spec-00004-AC-2.4 — two sessions start waiting one after the other, and each
   * gets its own notification (their tags each name their own session).
   */
  it('posts one notification per session when two start waiting', async () => {
    enabled()
    serve()
    await openBoard()
    await leave()

    served = [listing({ id: 's1', sourceId: 'prd-00001-x', awaiting: true })]
    await push()
    served = [
      listing({ id: 's1', sourceId: 'prd-00001-x', awaiting: true }),
      listing({ id: 's2', kind: 'ask', sourceId: 'idea-00001-x', awaiting: true }),
    ]
    await push()

    expect(Notice.made).toHaveLength(2)
    expect(Notice.made.map((notice) => notice.options.tag?.split(':')[0])).toEqual(['s1', 's2'])
    expect(Notice.made[1]!.title).toBe('ask · idea-00001-x')
  })

  /**
   * issue-00018 — a refresh that read «not waiting» is still in flight when a
   * later one reads «waiting». Waiting is not a state a session climbs to and
   * stays in: the earlier reading has to be folded in before the later one, or
   * the turn between them is never seen and the session sits waiting with
   * nobody told.
   */
  it('keeps the round when two refreshes land out of order', async () => {
    enabled()
    serve()
    await openBoard()
    await leave()

    served = [listing({ awaiting: true })]
    await push()
    expect(Notice.made).toHaveLength(1)

    // The user came back to answer and went away again, which is what makes the
    // turn below a wait of its own rather than the same one printing
    // (issue-00020).
    await comeBack()
    await leave()

    // The answered reading, held back on the graph half of the same refresh.
    let release: (() => void) | undefined
    vi.spyOn(api, 'graph').mockImplementationOnce(
      () => new Promise((resolve) => (release = () => resolve(structuredClone(GRAPH)))),
    )
    served = [listing({ awaiting: false })]
    await act(async () => Socket.channel!.signal())
    await settle()

    // Silent again, and this refresh has nothing holding it up.
    served = [listing({ awaiting: true })]
    await push()
    // The held one lands.
    await act(async () => void release?.())
    await settle()

    expect(Notice.made).toHaveLength(2)
    expect(Notice.made[1]!.options.body).toBe('awaiting')
  })
})

describe('being told a session has ended', () => {
  // spec-00004-AC-3.1 — a session exits on its own with the page away
  it('posts a notification when a session ends while the page is away', async () => {
    enabled()
    serve([listing()])
    await openBoard()
    await leave()

    served = [listing({ status: 'exited', exitCode: 0 })]
    await push()

    expect(Notice.made).toHaveLength(1)
    expect(Notice.made[0]!.title).toBe('clarify · prd-00001-x')
    expect(Notice.made[0]!.options.body).toBe('exited')
  })

  /**
   * spec-00004-AC-3.2 — the agent CLI was not there, so the session never ran: it
   * appears already ended and is posted the same way, as failed.
   */
  it('posts a notification when a session fails to start', async () => {
    enabled()
    serve()
    await openBoard()
    await leave()

    served = [listing({ status: 'failed', error: 'spawn claude ENOENT' })]
    await push()

    expect(Notice.made).toHaveLength(1)
    expect(Notice.made[0]!.options.body).toBe('failed')
  })

  // spec-00004-AC-3.3 — two sessions end one after the other, and each is posted
  it('posts one notification per session when two end', async () => {
    enabled()
    serve([listing({ id: 's1', sourceId: 'prd-00001-x' }), listing({ id: 's2', sourceId: 'idea-00001-x' })])
    await openBoard()
    await leave()

    served = [
      listing({ id: 's1', sourceId: 'prd-00001-x', status: 'exited', exitCode: 0 }),
      listing({ id: 's2', sourceId: 'idea-00001-x' }),
    ]
    await push()
    served = [
      listing({ id: 's1', sourceId: 'prd-00001-x', status: 'exited', exitCode: 0 }),
      listing({ id: 's2', sourceId: 'idea-00001-x', status: 'failed', exitCode: 2 }),
    ]
    await push()

    expect(Notice.made).toHaveLength(2)
    expect(Notice.made.map((notice) => notice.options.tag?.split(':')[0])).toEqual(['s1', 's2'])
    expect(Notice.made[1]!.options.body).toBe('failed')
  })

  /**
   * issue-00018 — the same reordering, on the ending half of the one diff: a
   * reading taken while the session still ran, landing after the reading that
   * says it ended, would put «running» back and let the very next reading
   * announce the same end a second time (spec-00003-FR-7 is one toast per end).
   */
  it('announces an end once when two refreshes land out of order', async () => {
    enabled()
    serve([listing()])
    await openBoard()
    await leave()

    let release: (() => void) | undefined
    vi.spyOn(api, 'graph').mockImplementationOnce(
      () => new Promise((resolve) => (release = () => resolve(structuredClone(GRAPH)))),
    )
    served = [listing()]
    await act(async () => Socket.channel!.signal())
    await settle()

    served = [listing({ status: 'exited', exitCode: 0 })]
    await push()
    await act(async () => void release?.())
    await settle()
    await push()

    expect(Notice.made).toHaveLength(1)
    expect(toast.message).toHaveBeenCalledTimes(1)
  })
})

describe('the cases where nothing is posted', () => {
  /**
   * spec-00004-AC-4.1 — the user is looking at the board, so the toast is the whole
   * of it: what is on the screen is not repeated on the desktop.
   */
  it('posts nothing while the page is visible and focused, and still toasts', async () => {
    enabled()
    serve([listing()])
    await openBoard()

    served = [listing({ status: 'exited', exitCode: 0 })]
    await push()

    expect(Notice.made).toHaveLength(0)
    expect(toast.message).toHaveBeenCalledWith('clarify · prd-00001-x', { description: 'exited' })
  })

  /**
   * spec-00004-AC-4.2 — the switch is off, so an away page hears nothing; the badge
   * is there as ever when the user comes back (spec-00003 unchanged).
   */
  it('posts nothing while the switch is off, and the badge still counts', async () => {
    serve()
    await openBoard()
    await leave()

    served = [listing({ awaiting: true })]
    await push()
    expect(Notice.made).toHaveLength(0)

    await comeBack()

    expect(screen.getByLabelText('1 awaiting input')).toBeTruthy()
  })

  /**
   * spec-00004-AC-4.3 — the permission was taken back by the browser while the page
   * was away: two sessions end and nothing is posted, nothing is asked for again,
   * and on the way back the switch says it is the permission that is missing rather
   * than looking as if the user had turned it off (design-00002 §13).
   */
  it('goes quiet and shows it needs permission when the permission is taken back', async () => {
    enabled()
    serve([listing({ id: 's1', sourceId: 'prd-00001-x' }), listing({ id: 's2', sourceId: 'idea-00001-x' })])
    await openBoard()
    expect(switchLabel()).toBe('Desktop notifications: on')
    await leave()

    Notice.permission = 'denied'
    served = [
      listing({ id: 's1', sourceId: 'prd-00001-x', status: 'exited', exitCode: 0 }),
      listing({ id: 's2', sourceId: 'idea-00001-x' }),
    ]
    await push()
    served = [
      listing({ id: 's1', sourceId: 'prd-00001-x', status: 'exited', exitCode: 0 }),
      listing({ id: 's2', sourceId: 'idea-00001-x', status: 'exited', exitCode: 0 }),
    ]
    await push()
    expect(Notice.made).toHaveLength(0)

    await comeBack()

    expect(switchLabel()).toBe('Desktop notifications: needs permission')
    expect(Notice.requests).toBe(0)
    expect(toast.error).not.toHaveBeenCalled()
  })
})

describe('clicking a notification', () => {
  /** The one notification a case has posted, clicked as the user would click it. */
  async function click(notice: Notice) {
    await act(async () => void notice.onclick?.())
    await settle()
  }

  /**
   * spec-00004-AC-5.1 — the session is still there and its document is on the
   * board: the terminal comes up on that session, its node is centred and
   * selected, and the page has asked to be brought forward.
   */
  it('shows the session and selects its document', async () => {
    enabled()
    serve([
      listing({ id: 's1', sourceId: 'prd-00001-x' }),
      listing({ id: 's2', kind: 'ask', sourceId: 'idea-00001-x' }),
    ])
    await openBoard()
    // The board came up on the newest running session, which is the other one.
    await waitFor(() => expect(screen.getByLabelText('Agent session').textContent).toContain('idea-00001-x'))
    await leave()

    served = [
      listing({ id: 's1', sourceId: 'prd-00001-x', awaiting: true }),
      listing({ id: 's2', kind: 'ask', sourceId: 'idea-00001-x' }),
    ]
    await push()
    await click(Notice.made[0]!)

    expect(window.focus).toHaveBeenCalled()
    await waitFor(() => expect(screen.getByLabelText('Agent session').textContent).toContain('prd-00001-x'))
    await waitFor(() => expect(screen.getByRole('toolbar', { name: 'Actions for prd-00001-x' })).toBeTruthy())
  })

  /**
   * spec-00004-AC-5.2 — the service restarted, so the session the notification was
   * about is not in the listing any more: the click is refused out loud and the
   * view does not move.
   */
  it('refuses and leaves the view alone when the session is gone', async () => {
    enabled()
    serve([
      listing({ id: 's1', sourceId: 'prd-00001-x' }),
      listing({ id: 's2', kind: 'ask', sourceId: 'idea-00001-x' }),
    ])
    await openBoard()
    await leave()
    served = [
      listing({ id: 's1', sourceId: 'prd-00001-x', awaiting: true }),
      listing({ id: 's2', kind: 'ask', sourceId: 'idea-00001-x' }),
    ]
    await push()
    expect(Notice.made).toHaveLength(1)

    served = [listing({ id: 's2', kind: 'ask', sourceId: 'idea-00001-x' })]
    await push()
    await click(Notice.made[0]!)

    expect(toast.error).toHaveBeenCalledWith('no session s1 on the board')
    expect(screen.getByLabelText('Agent session').textContent).toContain('idea-00001-x')
    expect(screen.queryByRole('toolbar', { name: 'Actions for prd-00001-x' })).toBeNull()
  })

  /**
   * spec-00004-AC-5.3 — the session is still there but its document has left the
   * board: the terminal shows it, the refusal is said out loud, and the selection
   * and the viewport stay where they were (close nearest, design-00002 §10).
   */
  it('shows the session and says so when its document has left the board', async () => {
    enabled()
    serve([listing({ id: 's1', sourceId: 'gone-00009-x' })])
    await openBoard()
    // Something is selected before the click, and it must still be selected after.
    fireEvent.click(screen.getByTestId('node-idea-00001-x'))
    await waitFor(() => expect(screen.getByRole('toolbar', { name: 'Actions for idea-00001-x' })).toBeTruthy())
    await leave()

    served = [listing({ id: 's1', sourceId: 'gone-00009-x', awaiting: true })]
    await push()
    await click(Notice.made[0]!)

    await waitFor(() => expect(screen.getByLabelText('Agent session').textContent).toContain('gone-00009-x'))
    expect(toast.error).toHaveBeenCalledWith('no document gone-00009-x on the board')
    expect(screen.getByRole('toolbar', { name: 'Actions for idea-00001-x' })).toBeTruthy()
  })
})

describe('what a notification carries', () => {
  /**
   * spec-00004-AC-6.1 — the document's own words and the session's output are
   * nowhere in either notification: a notification lands in the system's
   * notification centre, so it is the kind, the document id and the state and
   * nothing else (spec-00004-FR-6).
   */
  it('carries the kind, the document id and the state, and nothing else', async () => {
    enabled()
    serve()
    await openBoard()
    await leave()

    served = [listing({ awaiting: true, error: SECRET })]
    await push()
    served = [listing({ status: 'exited', exitCode: 0, error: SECRET })]
    await push()

    expect(Notice.made).toHaveLength(2)
    expect(content(Notice.made[0]!)).not.toContain('hunter2')
    expect(content(Notice.made[1]!)).not.toContain('hunter2')
    expect(Notice.made[0]!.title).toBe('clarify · prd-00001-x')
    expect(Notice.made[0]!.options.body).toBe('awaiting')
    expect(Notice.made[1]!.options.body).toBe('exited')
  })

  /**
   * spec-00004-AC-6.2 — a start that failed carries the failure and not the reason
   * it failed: no message, no stack.
   */
  it('carries no error text when a session failed to start', async () => {
    enabled()
    serve()
    await openBoard()
    await leave()

    served = [
      listing({
        status: 'failed',
        error: 'spawn claude ENOENT\n    at ChildProcess.handle.onexit (node:internal/child_process:293:19)',
      }),
    ]
    await push()

    expect(Notice.made).toHaveLength(1)
    expect(Notice.made[0]!.options.body).toBe('failed')
    expect(content(Notice.made[0]!)).not.toContain('ENOENT')
    expect(content(Notice.made[0]!)).not.toContain('child_process')
  })

  /**
   * spec-00004-AC-6.3 — the end notification of one session replaces its waiting
   * notification, so the session never has two standing at once. The replacement
   * is the page's own act: it closes the one it is standing on (issue-00019 —
   * leaving it to the browser's tag lost the second notification altogether).
   */
  it('replaces a session own earlier notification instead of stacking one on it', async () => {
    enabled()
    serve([listing()])
    await openBoard()
    await leave()

    served = [listing({ awaiting: true })]
    await push()
    served = [listing({ status: 'exited', exitCode: 0 })]
    await push()

    expect(Notice.made).toHaveLength(2)
    expect(Notice.made[0]!.closed).toBe(1)
    expect(Notice.made[1]!.closed).toBe(0)
  })

  /**
   * issue-00019 — two notifications of one session never carry the same tag. On
   * macOS Chrome a tag that has already been dismissed is not a replacement
   * channel: a later notification reusing it is silently never displayed, so
   * the session got one notification and never another.
   */
  it('gives two notifications of one session different tags', async () => {
    enabled()
    serve([listing()])
    await openBoard()
    await leave()

    served = [listing({ awaiting: true })]
    await push()
    served = [listing({ status: 'exited', exitCode: 0 })]
    await push()

    expect(Notice.made).toHaveLength(2)
    expect(Notice.made[0]!.options.tag).not.toBe(Notice.made[1]!.options.tag)
  })

  /**
   * issue-00019 — a browser reports a close asynchronously, so the notice we
   * closed to make room can report it after its replacement is already standing.
   * That late report must forget the closed one and not the one standing, or the
   * session's next notice has nothing to replace and starts stacking.
   */
  it('keeps the standing notification when the one it replaced reports its close late', async () => {
    enabled()
    serve()
    await openBoard()
    await leave()

    served = [listing({ awaiting: true })]
    await push()
    // Answered from in front of the board, and away again: the second round is a
    // wait of its own only because the user came back for it (issue-00020).
    await comeBack()
    served = [listing({ awaiting: false })]
    await push()
    await leave()
    served = [listing({ awaiting: true })]
    await push()
    expect(Notice.made).toHaveLength(2)

    // The first round's notice, closed by the second, only now says so.
    await act(async () => void Notice.made[0]!.onclose?.())

    served = [listing({ status: 'exited', exitCode: 0 })]
    await push()

    expect(Notice.made).toHaveLength(3)
    expect(Notice.made[1]!.closed).toBe(1)
  })

  /**
   * issue-00019 — replacement is per session, and it stays that way now that the
   * page performs it: one session's notice must not take down another's, which
   * is what spec-00004-AC-2.4 and AC-3.3 ask for on the posting side.
   */
  it('leaves another session own notification standing', async () => {
    enabled()
    serve([listing({ id: 's1', sourceId: 'prd-00001-x' }), listing({ id: 's2', sourceId: 'idea-00001-x' })])
    await openBoard()
    await leave()

    served = [
      listing({ id: 's1', sourceId: 'prd-00001-x', awaiting: true }),
      listing({ id: 's2', sourceId: 'idea-00001-x' }),
    ]
    await push()
    served = [
      listing({ id: 's1', sourceId: 'prd-00001-x', awaiting: true }),
      listing({ id: 's2', sourceId: 'idea-00001-x', status: 'exited', exitCode: 0 }),
    ]
    await push()

    expect(Notice.made).toHaveLength(2)
    expect(Notice.made[0]!.closed).toBe(0)
    expect(Notice.made[1]!.closed).toBe(0)
  })
})
