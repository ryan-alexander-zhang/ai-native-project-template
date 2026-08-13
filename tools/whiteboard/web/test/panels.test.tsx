// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../src/api.ts'
import { Editor } from '../src/Editor.tsx'
import { Terminal } from '../src/Terminal.tsx'

const CONTENT = '---\nid: prd-00001-x\ntype: prd\nstatus: draft\n---\n\n# X\n'

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('the editor', () => {
  beforeEach(() => {
    vi.spyOn(api, 'doc').mockResolvedValue({ path: 'prd/a.md', content: CONTENT, hash: 'hash-1' })
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
    await waitFor(() => expect(screen.getByText('saved')).toBeTruthy())
    expect(onSaved).toHaveBeenCalled()
  })

  // spec-00001-AC-5.1 as the user sees it
  it('shows the conflict and tells the user to reopen', async () => {
    vi.spyOn(api, 'save').mockRejectedValue(new ApiError(409, 'prd-00001-x changed on disk since it was opened'))
    render(<Editor docId="prd-00001-x" onSaved={vi.fn()} onClose={vi.fn()} />)
    await waitFor(() => expect(screen.getByTestId('editor-host').textContent).toContain('# X'))

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.getByText(/changed on disk.*reopen it/)).toBeTruthy())
  })

  it('shows any other refusal as it came back', async () => {
    vi.spyOn(api, 'save').mockRejectedValue(new ApiError(500, 'disk is on fire'))
    render(<Editor docId="prd-00001-x" onSaved={vi.fn()} onClose={vi.fn()} />)
    await waitFor(() => expect(screen.getByTestId('editor-host').textContent).toContain('# X'))

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.getByText('disk is on fire')).toBeTruthy())
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

    send() {}

    close() {
      this.closed = true
    }
  }

  beforeEach(() => vi.stubGlobal('WebSocket', FakeSocket))
  afterEach(() => vi.unstubAllGlobals())

  // spec-00001-AC-12.1 as the user sees it
  it('writes what the session prints into the terminal', async () => {
    const { container } = render(<Terminal onClose={vi.fn()} />)

    FakeSocket.last.emit('hello from the agent\r\n')

    await waitFor(() => expect(container.textContent).toContain('hello from the agent'))
  })

  it('closes the socket when the panel goes away', () => {
    const { unmount } = render(<Terminal onClose={vi.fn()} />)
    unmount()
    expect(FakeSocket.last.closed).toBe(true)
  })

  it('closes on request', async () => {
    const onClose = vi.fn()
    render(<Terminal onClose={onClose} />)

    await userEvent.click(screen.getByRole('button', { name: 'Close' }))

    expect(onClose).toHaveBeenCalled()
  })
})
