// @vitest-environment jsdom
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import type { SessionInfo } from '../src/api.ts'
import { ApiError, api } from '../src/api.ts'
import { Editor } from '../src/Editor.tsx'
import { Terminal } from '../src/Terminal.tsx'

/**
 * The real fit addon measures a rendered terminal, and jsdom renders nothing to
 * measure. This stand-in is the measurement: `panelSize` is what the panel is
 * worth in columns and rows, and `fit()` is the moment that lands on the
 * terminal — which is exactly what FR-12 says has to be reported (issue-00009).
 */
const panelSize = vi.hoisted(() => ({ cols: 100, rows: 40 }))

vi.mock('@xterm/addon-fit', () => ({
  FitAddon: class {
    private terminal?: { resize: (cols: number, rows: number) => void }

    activate(terminal: { resize: (cols: number, rows: number) => void }) {
      this.terminal = terminal
    }

    dispose() {}

    /**
     * What the panel is worth right now, as the real addon computes it — and like
     * the real one, nothing at all when there is no rendered terminal to measure
     * (a negative `panelSize` stands for that here).
     */
    proposeDimensions() {
      if (panelSize.cols < 0) return undefined
      return { cols: panelSize.cols, rows: panelSize.rows }
    }

    // The real one refuses a degenerate proposal too: a collapsed panel leaves
    // the terminal at the size it already had.
    fit() {
      const proposed = this.proposeDimensions()
      if (proposed && proposed.cols > 0 && proposed.rows > 0) {
        this.terminal?.resize(proposed.cols, proposed.rows)
      }
    }
  },
}))

const CONTENT = '---\nid: prd-00001-x\ntype: prd\nstatus: draft\n---\n\n# X\n'
const RUNNING: SessionInfo = { id: 's1', kind: 'clarify', agent: 'claude', sourceId: 'prd-00001-x', status: 'running' }

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('the editor', () => {
  beforeEach(() => {
    vi.spyOn(api, 'doc').mockResolvedValue({ path: 'prd/a.md', content: CONTENT, hash: 'hash-1' })
    vi.spyOn(toast, 'success').mockImplementation(() => 'id')
    vi.spyOn(toast, 'error').mockImplementation(() => 'id')
  })

  it('opens the whole file, front matter included', async () => {
    render(<Editor docId="prd-00001-x" onSaved={vi.fn()} onClose={vi.fn()} />)

    await waitFor(() => expect(screen.getByTestId('editor-host').textContent).toContain('status: draft'))
  })

  // spec-00001-AC-4.1 as the user sees it
  it('saves the edited text against the hash it opened', async () => {
    const save = vi.spyOn(api, 'save').mockResolvedValue({ committed: true })
    const onSaved = vi.fn()
    render(<Editor docId="prd-00001-x" onSaved={onSaved} onClose={vi.fn()} />)
    await waitFor(() => expect(screen.getByTestId('editor-host').textContent).toContain('# X'))

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(save).toHaveBeenCalledWith('prd-00001-x', CONTENT, 'hash-1')
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('saved prd-00001-x'))
    expect(onSaved).toHaveBeenCalled()
  })

  // spec-00001-AC-5.1 as the user sees it
  it('shows the conflict and tells the user to reopen', async () => {
    vi.spyOn(api, 'save').mockRejectedValue(new ApiError(409, 'prd-00001-x changed on disk since it was opened'))
    render(<Editor docId="prd-00001-x" onSaved={vi.fn()} onClose={vi.fn()} />)
    await waitFor(() => expect(screen.getByTestId('editor-host').textContent).toContain('# X'))

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('prd-00001-x changed on disk since it was opened', {
        description: 'reopen it to pick up the change',
      }),
    )
  })

  it('shows any other refusal as it came back', async () => {
    vi.spyOn(api, 'save').mockRejectedValue(new ApiError(500, 'disk is on fire'))
    render(<Editor docId="prd-00001-x" onSaved={vi.fn()} onClose={vi.fn()} />)
    await waitFor(() => expect(screen.getByTestId('editor-host').textContent).toContain('# X'))

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('disk is on fire', { description: undefined }))
  })

  it('does not save before the document has loaded', async () => {
    const save = vi.spyOn(api, 'save').mockResolvedValue({ committed: true })
    vi.spyOn(api, 'doc').mockReturnValue(new Promise(() => {}))
    render(<Editor docId="prd-00001-x" onSaved={vi.fn()} onClose={vi.fn()} />)

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(save).not.toHaveBeenCalled()
  })

  it('closes on request', async () => {
    const onClose = vi.fn()
    render(<Editor docId="prd-00001-x" onSaved={vi.fn()} onClose={onClose} />)

    await userEvent.click(screen.getByRole('button', { name: 'Close' }))

    expect(onClose).toHaveBeenCalled()
  })
})

describe('the terminal panel', () => {
  class FakeSocket {
    static last: FakeSocket
    static readonly OPEN = 1
    readyState = 1
    closed = false
    sent: unknown[] = []
    private listeners: Record<string, (event: { data: string }) => void> = {}

    constructor() {
      FakeSocket.last = this
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

  /** Every size frame the panel has sent, read back as the pair it carries. */
  function sizeFrames(): Array<{ cols: number; rows: number }> {
    return FakeSocket.last.sent
      .filter((frame): frame is Uint8Array => typeof frame !== 'string')
      .map((frame) => JSON.parse(new TextDecoder().decode(frame)))
  }

  /** Re-measure every observed element, as the browser does after a drag (issue-00006). */
  function reportResize() {
    ;(globalThis as { reportResize?: () => void }).reportResize?.()
  }

  beforeEach(() => {
    panelSize.cols = 100
    panelSize.rows = 40
    vi.stubGlobal('WebSocket', FakeSocket)
  })
  afterEach(() => vi.unstubAllGlobals())

  // spec-00001-AC-12.1 as the user sees it
  it('writes what the session prints into the terminal', async () => {
    const { container } = render(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={RUNNING} />)

    FakeSocket.last.emit('hello from the agent\r\n')

    await waitFor(() => expect(container.textContent).toContain('hello from the agent'))
  })

  // spec-00001-AC-12.5 as the user sees it — the fit is reported, not kept
  it('sends the size the terminal fitted to as soon as it attaches', async () => {
    render(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={RUNNING} />)

    await waitFor(() => expect(sizeFrames()[0]).toEqual({ cols: 100, rows: 40 }))
  })

  // spec-00001-AC-12.6 as the user sees it — dragging the panel divider
  it('fits again and sends the new size when the panel changes size', async () => {
    render(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={RUNNING} />)
    await waitFor(() => expect(sizeFrames().length).toBeGreaterThan(0))

    panelSize.cols = 60
    panelSize.rows = 20
    act(reportResize)

    await waitFor(() => expect(sizeFrames().at(-1)).toEqual({ cols: 60, rows: 20 }))
  })

  // spec-00001-AC-12.7 — a collapsed panel is worth no columns and no rows, and
  // that is not a size to draw at: the session keeps the one it had. (node-pty
  // throws outright on a zero size, so an unfiltered fit would break the session.)
  it('sends no size while the panel is collapsed to nothing', async () => {
    render(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={RUNNING} />)
    await waitFor(() => expect(sizeFrames().length).toBeGreaterThan(0))
    const sent = sizeFrames().length

    panelSize.cols = 0
    panelSize.rows = 0
    act(reportResize)

    expect(sizeFrames()).toHaveLength(sent)
    expect(sizeFrames().at(-1)).toEqual({ cols: 100, rows: 40 })
  })

  // The same rule for the other degenerate answer: an unmeasurable terminal has
  // no size to report, and the default 80×24 is not one to invent for it.
  it('sends no size while the terminal cannot be measured at all', async () => {
    render(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={RUNNING} />)
    await waitFor(() => expect(sizeFrames().length).toBeGreaterThan(0))
    const sent = sizeFrames().length

    panelSize.cols = -1
    act(reportResize)

    expect(sizeFrames()).toHaveLength(sent)
  })

  it('closes the socket when the panel goes away', () => {
    const { unmount } = render(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={RUNNING} />)
    unmount()
    expect(FakeSocket.last.closed).toBe(true)
  })

  it('closes on request', async () => {
    const onClose = vi.fn()
    render(<Terminal onClose={onClose} onStop={vi.fn()} />)

    await userEvent.click(screen.getByRole('button', { name: 'Close' }))

    expect(onClose).toHaveBeenCalled()
  })

  // spec-00001-AC-49.1 at the entry — the way out of a stuck session (issue-00010)
  it('stops the session on request while it is running', async () => {
    const onStop = vi.fn()
    render(<Terminal onClose={vi.fn()} onStop={onStop} session={RUNNING} />)

    await userEvent.click(screen.getByRole('button', { name: 'Stop the agent session' }))

    expect(onStop).toHaveBeenCalledTimes(1)
  })

  // spec-00001-AC-49.7 — there is no process left to end, so the entry is gone
  it('offers no stop for a session that has already ended', () => {
    render(<Terminal onClose={vi.fn()} onStop={vi.fn()} session={{ ...RUNNING, status: 'exited' }} />)

    expect(screen.queryByRole('button', { name: 'Stop the agent session' })).toBeNull()
  })

  it('offers no stop when there is no session at all', () => {
    render(<Terminal onClose={vi.fn()} onStop={vi.fn()} />)

    expect(screen.queryByRole('button', { name: 'Stop the agent session' })).toBeNull()
  })
})
