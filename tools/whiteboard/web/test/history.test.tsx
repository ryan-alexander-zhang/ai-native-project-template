// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import type { SessionHistoryMeta } from '../../src/sessionHistory.ts'
import { ApiError, api } from '../src/api.ts'
import { SessionHistory } from '../src/SessionHistory.tsx'

function record(overrides: Partial<SessionHistoryMeta> = {}): SessionHistoryMeta {
  return {
    id: 's1',
    kind: 'audit',
    docId: 'spec-00001-x',
    agent: 'claude',
    startedAt: '2026-08-21T10:00:00.000Z',
    endedAt: '2026-08-21T10:04:00.000Z',
    status: 'exited',
    exitCode: 0,
    ...overrides,
  }
}

beforeEach(() => {
  vi.spyOn(toast, 'error').mockImplementation(() => 'id')
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

function open() {
  render(<SessionHistory open onOpenChange={vi.fn()} />)
}

describe('the session history', () => {
  // spec-00001-AC-54.1 — the list says what each session was and how it ended
  it('names the kind, the document, the agent and how it ended', async () => {
    vi.spyOn(api, 'sessionHistory').mockResolvedValue([record()])
    open()

    const list = await screen.findByRole('list', { name: 'Session history' })

    expect(within(list).getByText('audit')).toBeTruthy()
    expect(within(list).getByText('spec-00001-x')).toBeTruthy()
    expect(within(list).getByText('claude')).toBeTruthy()
    expect(within(list).getByText('exited 0')).toBeTruthy()
  })

  // spec-00001-AC-54.4 — a session that was stopped is in the list, and its exit
  // status is not dressed up
  it('lists a session that failed as it was recorded', async () => {
    vi.spyOn(api, 'sessionHistory').mockResolvedValue([
      record({ id: 's2', kind: 'clarify', status: 'failed', exitCode: undefined }),
    ])
    open()

    expect(await screen.findByText('failed')).toBeTruthy()
  })

  it('puts the newest session first', async () => {
    vi.spyOn(api, 'sessionHistory').mockResolvedValue([
      record({ id: 'older', docId: 'idea-00001-x', startedAt: '2026-08-20T09:00:00.000Z' }),
      record({ id: 'newer', docId: 'spec-00002-x', startedAt: '2026-08-21T09:00:00.000Z' }),
    ])
    open()

    const list = await screen.findByRole('list', { name: 'Session history' })
    const rows = within(list).getAllByRole('button')
    expect(rows[0]!.textContent).toContain('spec-00002-x')
    expect(rows[1]!.textContent).toContain('idea-00001-x')
  })

  it('leaves two sessions that started together in the order they came', async () => {
    vi.spyOn(api, 'sessionHistory').mockResolvedValue([
      record({ id: 'first', docId: 'idea-00001-x' }),
      record({ id: 'second', docId: 'spec-00002-x' }),
    ])
    open()

    const list = await screen.findByRole('list', { name: 'Session history' })
    expect(within(list).getAllByRole('button')[0]!.textContent).toContain('idea-00001-x')
  })

  it('shows a stamp it cannot read as it was recorded', async () => {
    vi.spyOn(api, 'sessionHistory').mockResolvedValue([record({ startedAt: 'some time yesterday' })])
    open()

    expect((await screen.findByRole('list', { name: 'Session history' })).textContent).toContain(
      'some time yesterday',
    )
  })

  // spec-00001-AC-54.2, the reading half: the transcript is there in full
  it('shows the whole transcript of the session picked', async () => {
    vi.spyOn(api, 'sessionHistory').mockResolvedValue([record()])
    const read = vi.spyOn(api, 'sessionTranscript').mockResolvedValue({
      meta: record(),
      transcript: 'reading docs/spec/README.md\nfinding: AC-3.2 has no GWT\ndone',
    })
    open()
    const list = await screen.findByRole('list', { name: 'Session history' })

    await userEvent.click(within(list).getAllByRole('button')[0]!)

    expect(read).toHaveBeenCalledWith('s1')
    const transcript = await screen.findByLabelText('Transcript of s1')
    expect(transcript.textContent).toContain('reading docs/spec/README.md')
    expect(transcript.textContent).toContain('finding: AC-3.2 has no GWT')
    // Read-only: a transcript is a record of what happened, not a buffer.
    expect(transcript.tagName).toBe('PRE')
  })

  it('goes back from a transcript to the list', async () => {
    vi.spyOn(api, 'sessionHistory').mockResolvedValue([record()])
    vi.spyOn(api, 'sessionTranscript').mockResolvedValue({ meta: record(), transcript: 'output' })
    open()
    const list = await screen.findByRole('list', { name: 'Session history' })
    await userEvent.click(within(list).getAllByRole('button')[0]!)
    await screen.findByLabelText('Transcript of s1')

    await userEvent.click(screen.getByRole('button', { name: 'Back to the list' }))

    expect(await screen.findByRole('list', { name: 'Session history' })).toBeTruthy()
  })

  it('says there is nothing rather than showing an empty list', async () => {
    vi.spyOn(api, 'sessionHistory').mockResolvedValue([])
    open()

    expect(await screen.findByText('no sessions yet')).toBeTruthy()
    expect(screen.queryByRole('list', { name: 'Session history' })).toBeNull()
  })

  it('surfaces a refusal to read the history', async () => {
    vi.spyOn(api, 'sessionHistory').mockRejectedValue(new ApiError(500, 'cannot read .whiteboard/sessions'))
    open()

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('cannot read .whiteboard/sessions'))
  })

  it('surfaces a refusal to read one transcript', async () => {
    vi.spyOn(api, 'sessionHistory').mockResolvedValue([record()])
    vi.spyOn(api, 'sessionTranscript').mockRejectedValue(
      new ApiError(404, 'there is no session history for s1'),
    )
    open()
    const list = await screen.findByRole('list', { name: 'Session history' })

    await userEvent.click(within(list).getAllByRole('button')[0]!)

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('there is no session history for s1'))
  })

  it('reads nothing while it is closed', () => {
    const history = vi.spyOn(api, 'sessionHistory').mockResolvedValue([])
    render(<SessionHistory open={false} onOpenChange={vi.fn()} />)

    expect(history).not.toHaveBeenCalled()
  })
})
