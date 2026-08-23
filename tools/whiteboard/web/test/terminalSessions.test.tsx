// @vitest-environment jsdom
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SessionListing } from '../src/api.ts'
import { Terminal } from '../src/Terminal.tsx'

/**
 * The terminals themselves, under the test's hand. What FR-5 promises is about
 * the *instance*: a session put away and brought back must be the same terminal,
 * with the same buffer and the same scroll position — replaying output could
 * restore the first but never the second (spec-00003-AC-5.1, design-00002 §12).
 * That is only observable at this level, so the terminal is a stand-in that
 * records what happened to it.
 */
const xterms = vi.hoisted(() => ({
  made: [] as Array<{
    options: Record<string, unknown>
    written: string[]
    /** Where the reader had scrolled to. Nothing in the board writes it; a switch must not either. */
    viewportY: number
    cols: number
    rows: number
    opened: unknown
    disposed: boolean
    type: (data: string) => void
  }>,
}))

vi.mock('@xterm/xterm', () => ({
  Terminal: class {
    options: Record<string, unknown>
    written: string[] = []
    viewportY = 0
    cols = 80
    rows = 24
    opened: unknown = undefined
    disposed = false
    private listener?: (data: string) => void

    constructor(options: Record<string, unknown>) {
      this.options = options
      xterms.made.push(this as unknown as (typeof xterms.made)[number])
    }

    loadAddon(addon: { activate?: (terminal: unknown) => void }) {
      addon.activate?.(this)
    }

    open(element: unknown) {
      this.opened = element
    }

    write(data: string) {
      this.written.push(data)
    }

    onData(listener: (data: string) => void) {
      this.listener = listener
    }

    /** A keystroke, as xterm would hand it on. */
    type(data: string) {
      this.listener?.(data)
    }

    resize(cols: number, rows: number) {
      this.cols = cols
      this.rows = rows
    }

    dispose() {
      this.disposed = true
    }
  },
}))

/** The panel's measurement, as panels.test.tsx stands it in: what it is worth in columns and rows. */
const panelSize = vi.hoisted(() => ({ cols: 100, rows: 40 }))

vi.mock('@xterm/addon-fit', () => ({
  FitAddon: class {
    private terminal?: { resize: (cols: number, rows: number) => void }

    activate(terminal: { resize: (cols: number, rows: number) => void }) {
      this.terminal = terminal
    }

    dispose() {}

    proposeDimensions() {
      if (panelSize.cols < 0) return undefined
      return { cols: panelSize.cols, rows: panelSize.rows }
    }

    fit() {
      const proposed = this.proposeDimensions()
      if (proposed && proposed.cols > 0) this.terminal?.resize(proposed.cols, proposed.rows)
    }
  },
}))

/** One session's channel, kept by the url it was dialled on — one per session (spec-00003-FR-5). */
class FakeSocket {
  static opened: FakeSocket[] = []
  static readonly OPEN = 1
  readyState = 1
  closed = false
  sent: unknown[] = []
  private listeners: Record<string, (event: { data: string }) => void> = {}

  constructor(readonly url: string) {
    FakeSocket.opened.push(this)
  }

  addEventListener(type: string, listener: (event: { data: string }) => void) {
    this.listeners[type] = listener
  }

  emit(data: string) {
    this.listeners.message?.({ data })
  }

  send(frame: unknown) {
    this.sent.push(frame)
  }

  close() {
    this.closed = true
  }
}

function socketOf(sessionId: string): FakeSocket {
  return FakeSocket.opened.find((socket) => socket.url.includes(`sessionId=${sessionId}`))!
}

/** The size frames one session's channel has carried, read back as the pair each holds. */
function sizeFrames(sessionId: string): Array<{ cols: number; rows: number }> {
  return socketOf(sessionId)
    .sent.filter((frame): frame is Uint8Array => typeof frame !== 'string')
    .map((frame) => JSON.parse(new TextDecoder().decode(frame)))
}

function listing(overrides: Partial<SessionListing> = {}): SessionListing {
  return {
    id: 'a',
    kind: 'clarify',
    agent: 'claude',
    sourceId: 'prd-00001-x',
    status: 'running',
    startedAt: '2026-01-01T00:00:00.000Z',
    ...overrides,
  }
}

const A = listing({ id: 'a', sourceId: 'prd-00001-x' })
const B = listing({ id: 'b', kind: 'ask', sourceId: 'idea-00001-x' })

function reportResize() {
  ;(globalThis as { reportResize?: () => void }).reportResize?.()
}

beforeEach(() => {
  xterms.made.length = 0
  FakeSocket.opened = []
  panelSize.cols = 100
  panelSize.rows = 40
  vi.stubGlobal('WebSocket', FakeSocket)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('the terminal panel with several sessions', () => {
  /**
   * spec-00003-AC-5.1 — switching to B and back to A must cost A neither its
   * output nor where the user had scrolled to, and must not interrupt either
   * session: the instance is unmounted, never disposed, so there is nothing to
   * replay and nothing to rebuild (design-00002 §12).
   */
  it('keeps each session own terminal alive across a switch and back', async () => {
    const { rerender } = render(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={A} />)
    await waitFor(() => expect(xterms.made).toHaveLength(1))
    const first = xterms.made[0]!
    act(() => socketOf('a').emit('what A printed\r\n'))
    // The reader scrolls back through A's output before switching away.
    first.viewportY = 42

    rerender(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={B} />)
    await waitFor(() => expect(xterms.made).toHaveLength(2))
    rerender(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={A} />)

    // The very same terminal, not a second one made for the same session.
    expect(xterms.made).toHaveLength(2)
    expect(xterms.made[0]).toBe(first)
    expect(first.disposed).toBe(false)
    expect(first.written.join('')).toContain('what A printed')
    expect(first.viewportY).toBe(42)
    // Neither session was interrupted: both channels are still open.
    expect(socketOf('a').closed).toBe(false)
    expect(socketOf('b').closed).toBe(false)
  })

  // Each session has a channel of its own, so output and keystrokes cannot cross
  // between them (spec-00003-AC-1.2 at the terminal).
  it('dials a channel of its own for each session', async () => {
    const { rerender } = render(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={A} />)
    await waitFor(() => expect(xterms.made).toHaveLength(1))
    rerender(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={B} />)
    await waitFor(() => expect(xterms.made).toHaveLength(2))

    act(() => socketOf('b').emit('what B printed\r\n'))
    xterms.made[1]!.type('an answer for B')

    expect(xterms.made[1]!.written.join('')).toContain('what B printed')
    expect(xterms.made[0]!.written.join('')).not.toContain('what B printed')
    expect(socketOf('b').sent).toContain('an answer for B')
    expect(socketOf('a').sent).not.toContain('an answer for B')
  })

  /**
   * spec-00003-AC-5.7 — only the mounted terminal can be measured, so only the
   * session on show ever sends a size; the one switched to is synchronised with
   * the size as it now stands, and the one switched away from is sent nothing
   * more.
   */
  it('sends size frames only from the session on show', async () => {
    const { rerender } = render(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={A} />)
    await waitFor(() => expect(sizeFrames('a')).toEqual([{ cols: 100, rows: 40 }]))
    panelSize.cols = 60
    panelSize.rows = 20
    act(reportResize)
    await waitFor(() => expect(sizeFrames('a').at(-1)).toEqual({ cols: 60, rows: 20 }))
    const sentToA = sizeFrames('a').length

    rerender(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={B} />)

    // B is told the size that is current, and A is told nothing more.
    await waitFor(() => expect(sizeFrames('b')).toEqual([{ cols: 60, rows: 20 }]))
    panelSize.cols = 30
    panelSize.rows = 10
    act(reportResize)
    await waitFor(() => expect(sizeFrames('b').at(-1)).toEqual({ cols: 30, rows: 10 }))
    expect(sizeFrames('a')).toHaveLength(sentToA)
  })

  /**
   * design-00002 §12 — memory grows with the terminals kept, so the cap on
   * running sessions is the cap on them: past it, the one nobody is watching goes.
   */
  it('keeps no more terminals than the session cap allows', async () => {
    const { rerender } = render(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={A} keep={1} />)
    await waitFor(() => expect(xterms.made).toHaveLength(1))

    rerender(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={B} keep={1} />)

    await waitFor(() => expect(xterms.made[0]!.disposed).toBe(true))
    expect(socketOf('a').closed).toBe(true)
    expect(xterms.made[1]!.disposed).toBe(false)
  })

  // The panel going away is the end of every terminal it held: nothing else holds
  // them, so a channel left open would be one nobody can close.
  it('closes every session channel when the panel itself goes away', async () => {
    const { rerender, unmount } = render(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={A} />)
    await waitFor(() => expect(xterms.made).toHaveLength(1))
    rerender(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={B} />)
    await waitFor(() => expect(xterms.made).toHaveLength(2))

    unmount()

    expect(socketOf('a').closed).toBe(true)
    expect(socketOf('b').closed).toBe(true)
    expect(xterms.made.every((terminal) => terminal.disposed)).toBe(true)
  })

  // A theme change is not a new terminal: retuning the live ones costs no output
  // and no scroll position (design-00002 §5).
  it('retunes the terminals it has when the theme changes', async () => {
    const { rerender } = render(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={A} dark={false} />)
    await waitFor(() => expect(xterms.made).toHaveLength(1))

    rerender(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={A} dark />)

    expect(xterms.made).toHaveLength(1)
    expect(xterms.made[0]!.options.theme).toMatchObject({ background: '#09090b' })
  })

  // spec-00003-FR-5 / design-00002 §3 — the header says which session this is
  it('names the kind and the target document of the session on show', async () => {
    render(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={B} />)

    const panel = screen.getByLabelText('Agent session')
    expect(panel.textContent).toContain('ask')
    expect(panel.textContent).toContain('idea-00001-x')
    await waitFor(() => expect(xterms.made).toHaveLength(1))
  })

  // spec-00003-AC-6.1 at the terminal — awaiting is derived from the payload, not
  // a fifth status (design-00002 §3)
  it('shows a running session that has gone quiet as awaiting', async () => {
    render(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={{ ...A, awaiting: true }} />)

    expect(screen.getByLabelText('Agent session').textContent).toContain('awaiting')
    await waitFor(() => expect(xterms.made).toHaveLength(1))
  })

  it.each(['exited', 'failed', 'terminated'] as const)('shows an ended session as %s', async (status) => {
    render(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={{ ...A, status }} />)

    expect(screen.getByLabelText('Agent session').textContent).toContain(status)
    await waitFor(() => expect(xterms.made).toHaveLength(1))
  })

  /**
   * spec-00003-AC-5.5 and spec-00001-AC-49.7 at the entry: the stop acts on the
   * session on show, so an ended one on show offers no stop — however many others
   * are still running.
   */
  it('offers no stop while the session on show has ended', async () => {
    render(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={{ ...A, status: 'terminated' }} />)

    expect(screen.queryByRole('button', { name: 'Stop the agent session' })).toBeNull()
    await waitFor(() => expect(xterms.made).toHaveLength(1))
  })
})
