// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import { Board } from '../src/Board.tsx'
import { ApiError, type AskThread, type SessionListing, api } from '../src/api.ts'

// Rendering the whole board and pushing a refresh through it is heavier than the
// default five seconds allows on a loaded machine; none of these cases measures
// how long anything takes.
vi.setConfig({ testTimeout: 30_000 })

function node(overrides: Partial<DocNode> = {}): DocNode {
  return {
    id: 'prd-00001-x',
    path: 'prd/a.md',
    type: 'prd',
    status: 'draft',
    title: 'Whiteboard PRD',
    relations: {},
    ok: true,
    problems: [],
    ...overrides,
  }
}

/** A document whose front matter is broken: its editor is reachable, its entries are not. */
const BROKEN = node({
  id: 'docs/spec/bad.md',
  path: 'spec/bad.md',
  type: undefined,
  ok: false,
  problems: ['front matter is missing'],
})
const IDEA = node({ id: 'idea-00001-x', type: 'idea', status: 'active', title: 'Idea', path: 'idea/a.md' })
const GRAPH: DocGraph = {
  nodes: [node(), IDEA, BROKEN],
  edges: [],
  issues: [],
  idOwners: {},
  diagnostics: [],
}

function listing(overrides: Partial<SessionListing> = {}): SessionListing {
  return {
    id: 's1',
    kind: 'ask',
    agent: 'claude',
    sourceId: 'prd-00001-x',
    status: 'running',
    startedAt: '2026-02-01T09:00:00.000Z',
    ...overrides,
  }
}

/** One exchange of an ask thread, as `GET /api/asks/:id` hands it over (design-00001 §10.2). */
function exchange(overrides: Partial<AskThread['exchanges'][number]> = {}): AskThread['exchanges'][number] {
  return {
    question: 'why is this still a draft?',
    askedAt: '2026-02-01T09:00:00.000Z',
    outcome: 'running',
    runSessionId: 's1',
    ...overrides,
  }
}

function thread(overrides: Partial<AskThread> = {}): AskThread {
  return { id: 't-1', agent: 'claude', exchanges: [exchange()], ...overrides }
}

/** The sockets the board dials: the docs-change channel, and one per terminal. */
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

/** What the two payloads a case moves the server by answer with. */
let served: SessionListing[] = []
let threads: AskThread[] = []

function serve(sessions: SessionListing[] = [], lists: AskThread[] = []) {
  served = sessions
  threads = lists
  vi.spyOn(api, 'graph').mockImplementation(async () => structuredClone(GRAPH))
  vi.spyOn(api, 'sessions').mockImplementation(async () => served)
  vi.spyOn(api, 'asks').mockImplementation(async () => structuredClone(threads))
  vi.spyOn(api, 'transitions').mockResolvedValue(['active'])
  vi.spyOn(api, 'nextSteps').mockResolvedValue([])
  vi.spyOn(api, 'items').mockResolvedValue({ items: [], diagnostics: [] })
  vi.spyOn(api, 'doc').mockResolvedValue({ path: 'prd/a.md', content: '# X\n\nbody\n', hash: 'hash-1' })
  vi.spyOn(api, 'config').mockResolvedValue({
    types: { idea: 'living', prd: 'living', spec: 'living' },
    relations: ['parent'],
    flow: {},
    focus: {},
    // The one agent that declares a headless form, which is what makes an ask
    // possible at all (spec-00005-FR-8).
    agents: [{ name: 'claude', headless: true, source: 'project' }],
    entry: [],
    carries: {},
    maxSessions: 3,
    clarifiable: [],
    auditable: [],
  })
}

async function openBoard() {
  const rendered = render(<Board />)
  await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
  return rendered
}

/** Select a node and open its editor on the ask list — the third view state. */
async function openList(id = 'prd-00001-x') {
  fireEvent.click(screen.getByTestId(`node-${id}`))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy())
  await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
  // The tab is switched with the event Radix acts on. `userEvent.click` reaches
  // no editor tab once the editor is inside the board's resizable panel — the
  // same jsdom limit the canvas' own node clicks work around with fireEvent.
  fireEvent.mouseDown(await screen.findByRole('tab', { name: 'Questions' }))
  await settle(1)
}

/** The session panel, opened from the resident top-bar entry (spec-00003-FR-4). */
async function openPanel() {
  await userEvent.click(screen.getByRole('button', { name: 'Open the session panel' }))
  return screen.findByRole('list', { name: 'Agent sessions' })
}

const listRows = () => within(screen.getByRole('list', { name: 'Ask threads' })).getAllByRole('listitem')

beforeEach(() => {
  Socket.channel = undefined
  vi.stubGlobal('WebSocket', Socket)
  vi.spyOn(toast, 'error').mockImplementation(() => 'id')
  vi.spyOn(toast, 'message').mockImplementation(() => 'id')
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('asking a question', () => {
  /**
   * spec-00005-AC-3.4 — nothing is running and the terminal is not up: the
   * question is submitted, the input puts itself away, and no terminal panel
   * comes with it (the contrast with spec-00003-AC-5.4's first session).
   */
  it('opens no terminal when a question is submitted', async () => {
    serve()
    const ask = vi.spyOn(api, 'ask').mockResolvedValue({ sessionId: 's1', threadId: 't-1' })
    await openBoard()
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Ask' })).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: 'Ask' }))
    await userEvent.type(await screen.findByLabelText('Question'), 'why is this still a draft?')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(ask).toHaveBeenCalledWith({
      docId: 'prd-00001-x',
      question: 'why is this still a draft?',
      agent: undefined,
    })
    // The input is gone, and no terminal took its place.
    await waitFor(() => expect(screen.queryByLabelText('Question')).toBeNull())
    expect(screen.queryByLabelText('Agent session')).toBeNull()
  })

  /**
   * The draft belongs to the document it was written about. The entry is one
   * component in one place, so words typed about one node and left there would
   * otherwise be submitted against the next node selected — the wrong document
   * asked the right question (design-00002 §14).
   */
  it('keeps no draft from one document to the next', async () => {
    serve()
    vi.spyOn(api, 'ask').mockResolvedValue({ sessionId: 's1', threadId: 't-1' })
    await openBoard()
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Ask' })).toBeTruthy())
    await userEvent.click(screen.getByRole('button', { name: 'Ask' }))
    await userEvent.type(await screen.findByLabelText('Question'), 'why is this still a draft?')

    fireEvent.click(screen.getByTestId('node-idea-00001-x'))
    await waitFor(() => expect(screen.getByRole('toolbar', { name: 'Actions for idea-00001-x' })).toBeTruthy())
    await userEvent.click(screen.getByRole('button', { name: 'Ask' }))

    expect((await screen.findByLabelText<HTMLTextAreaElement>('Question')).value).toBe('')
  })

  /**
   * A refusal — the cap, a thread already busy, a network that dropped it — must
   * cost the user nothing but the press: the words are theirs, and an input that
   * empties itself before the answer comes back makes them type the question
   * twice (spec-00005-FR-7's refusals as the interface takes them).
   */
  it('keeps the question when the submit is refused', async () => {
    serve()
    const ask = vi
      .spyOn(api, 'ask')
      .mockRejectedValue(new ApiError(409, 'the session limit is reached'))
    await openBoard()
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Ask' })).toBeTruthy())
    await userEvent.click(screen.getByRole('button', { name: 'Ask' }))
    await userEvent.type(await screen.findByLabelText('Question'), 'why is this still a draft?')

    await userEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(ask).toHaveBeenCalledTimes(1)
    expect(toast.error).toHaveBeenCalledWith('the session limit is reached')
    // Still open, still holding what was typed.
    expect(screen.getByLabelText<HTMLTextAreaElement>('Question').value).toBe('why is this still a draft?')
  })

  /**
   * spec-00005-AC-3.5 — an ask is the newest running session and an advance an
   * older one: a board opening fresh puts the advance on the terminal, because
   * an ask is never what the terminal falls back to.
   */
  it('falls back to the terminal-form session, never to the newer ask', async () => {
    serve([
      listing({ id: 's1', kind: 'advance', sourceId: 'idea-00001-x', startedAt: '2026-02-01T09:00:00.000Z' }),
      listing({ id: 's2', kind: 'ask', sourceId: 'prd-00001-x', startedAt: '2026-02-01T10:00:00.000Z' }),
    ])
    await openBoard()

    const terminal = await screen.findByLabelText('Agent session')
    expect(terminal.textContent).toContain('idea-00001-x')
    expect(terminal.textContent).not.toContain('prd-00001-x')
  })

  /**
   * spec-00005-AC-7.3 — an anomalous document's editor is still the way to
   * repair it (spec-00001-FR-2), and it offers no question entry: there is
   * nothing to answer about a document whose front matter will not parse.
   */
  it('offers no question entry in an anomalous document editor', async () => {
    serve()
    await openBoard()
    fireEvent.click(screen.getByTestId('node-docs/spec/bad.md'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy())
    // The floating toolbar of an anomalous node carries no starting point at all.
    expect(screen.queryByRole('button', { name: 'Ask' })).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))

    await waitFor(() => expect(screen.getByRole('tab', { name: 'Questions' })).toBeTruthy())
    expect(screen.queryByRole('button', { name: 'Ask' })).toBeNull()
  })
})

describe('the ask list', () => {
  /**
   * spec-00005-AC-3.1 — the call ended with an answer: the thread reads
   * answered, the answer is rendered as Markdown through the preview's own
   * pipeline, and the end of the call is announced like any other session's
   * (spec-00003-FR-7) with nothing said about a commit.
   */
  it('shows an answered thread with its answer rendered as Markdown', async () => {
    serve(
      [listing({ id: 's1', status: 'running' })],
      [thread({ exchanges: [exchange({ outcome: 'running' })] })],
    )
    await openBoard()
    await openList()

    served = [listing({ id: 's1', status: 'exited', exitCode: 0 })]
    threads = [
      thread({
        exchanges: [
          exchange({
            outcome: 'answered',
            answer: '## Because\n\nit has open questions',
            answeredAt: '2026-02-01T09:05:00.000Z',
          }),
        ],
      }),
    ]
    await push()

    await waitFor(() => expect(listRows()[0]!.textContent).toContain('answered'))
    await userEvent.click(within(listRows()[0]!).getAllByRole('button')[0]!)
    expect(screen.getByRole('heading', { level: 2, name: 'Because' })).toBeTruthy()
    expect(toast.message).toHaveBeenCalledWith('ask · prd-00001-x', { description: 'exited' })
  })

  // spec-00005-AC-3.2 — the call is in flight, and the thread says so
  it('shows a thread whose call is in flight as running', async () => {
    serve([listing()], [thread()])
    await openBoard()

    await openList()

    expect(listRows()).toHaveLength(1)
    expect(listRows()[0]!.textContent).toContain('running')
  })

  /**
   * spec-00005-AC-3.3 — a page opening on a call that is already in flight reads
   * it back off the registry listing: the node is marked and the list says
   * running. The refresh that follows is what carries the answer to the page —
   * the ask list is the fourth read of the one refresh path, taken only while
   * the list is open (design-00002 §10).
   */
  it('restores a call in flight on a fresh page and carries its answer in', async () => {
    serve([listing()], [thread()])
    await openBoard()
    expect(screen.getByRole('button', { name: 'Ask session of prd-00001-x' })).toBeTruthy()
    await openList()
    expect(listRows()[0]!.textContent).toContain('running')

    served = [listing({ status: 'exited', exitCode: 0 })]
    threads = [thread({ exchanges: [exchange({ outcome: 'answered', answer: 'because of the gaps' })] })]
    await push()

    await waitFor(() => expect(listRows()[0]!.textContent).toContain('answered'))
  })

  /**
   * spec-00005-AC-9.1 — three threads, three states, each row saying which; the
   * answered one opens on its whole exchange and on the way to carry on asking,
   * which is a follow-up on **that** thread (spec-00005-FR-2).
   */
  it('lists every thread with its state and opens one on its questions and answers', async () => {
    serve(
      [listing({ id: 's2' })],
      [
        thread({
          id: 't-1',
          exchanges: [exchange({ question: 'why a draft?', outcome: 'answered', answer: 'open questions' })],
        }),
        thread({ id: 't-2', exchanges: [exchange({ question: 'what is missing?', runSessionId: 's2' })] }),
        thread({ id: 't-3', exchanges: [exchange({ question: 'who owns it?', outcome: 'failed' })] }),
      ],
    )
    const ask = vi.spyOn(api, 'ask').mockResolvedValue({ sessionId: 's3', threadId: 't-1' })
    await openBoard()
    await openList()

    const listed = listRows().map((row) => row.textContent ?? '')
    expect(listed[0]).toContain('why a draft?')
    expect(listed[0]).toContain('answered')
    expect(listed[1]).toContain('running')
    expect(listed[2]).toContain('failed')

    await userEvent.click(within(listRows()[0]!).getAllByRole('button')[0]!)
    expect(within(listRows()[0]!).getByText('open questions')).toBeTruthy()
    // Typed with the event the control reads, for the same reason the tab above
    // is: keystrokes do not reach a field inside the board's resizable panel
    // under jsdom.
    fireEvent.change(screen.getByLabelText('Follow-up question on t-1'), {
      target: { value: 'which ones?' },
    })
    await userEvent.click(within(listRows()[0]!).getByRole('button', { name: 'Send' }))

    expect(ask).toHaveBeenCalledWith({ docId: 'prd-00001-x', question: 'which ones?', threadId: 't-1' })
  })

  /**
   * spec-00005-AC-7.1 as the界面 half — a thread with a call in flight takes no
   * second submit: the follow-up input is disabled while it runs (the refusal
   * itself is the server's, design-00001 §7).
   */
  it('takes no follow-up while that thread has a call running', async () => {
    serve([listing()], [thread()])
    await openBoard()
    await openList()

    await userEvent.click(within(listRows()[0]!).getAllByRole('button')[0]!)

    expect(screen.getByLabelText<HTMLTextAreaElement>('Follow-up question on t-1').disabled).toBe(true)
  })

  /**
   * spec-00005-AC-7.5 as the user does it — the call ended non-zero, the
   * question stands failed with the rest of the list intact, and resending puts
   * that same question again on the same thread (`resend`, design-00001 §7).
   */
  it('resends a failed question on its own thread', async () => {
    serve(
      [listing({ status: 'failed', exitCode: 2 })],
      [
        thread({
          exchanges: [
            exchange({ question: 'why a draft?', outcome: 'answered', answer: 'open questions' }),
            exchange({ question: 'which ones?', outcome: 'failed' }),
          ],
        }),
      ],
    )
    const ask = vi.spyOn(api, 'ask').mockResolvedValue({ sessionId: 's2', threadId: 't-1' })
    await openBoard()
    await openList()

    await userEvent.click(within(listRows()[0]!).getAllByRole('button')[0]!)
    // The answered exchange is still whole beside the failed one.
    expect(within(listRows()[0]!).getByText('open questions')).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: 'Resend the question of t-1' }))

    expect(ask).toHaveBeenCalledWith({
      docId: 'prd-00001-x',
      question: 'which ones?',
      threadId: 't-1',
      resend: true,
    })
  })

  /**
   * A failed question says why on the thread (design-00001 §10.3): a call can
   * exit zero and answer nothing, so «exited» tells the reader nothing about the
   * question — the reason is what does (spec-00005-FR-7).
   */
  it('shows why a failed question has no answer', async () => {
    serve(
      [listing({ status: 'exited', exitCode: 0 })],
      [
        thread({
          exchanges: [exchange({ outcome: 'failed', reason: 'Credit balance too low' })],
        }),
      ],
    )
    await openBoard()
    await openList()

    expect(listRows()[0]!.textContent).toContain('failed')
    await userEvent.click(within(listRows()[0]!).getAllByRole('button')[0]!)

    expect(within(listRows()[0]!).getByText('Credit balance too low')).toBeTruthy()
  })

  /**
   * design-00001 §10.2's ruling as the interface carries it: a continuation the
   * CLI refused is marked rather than quietly swapped for a fresh conversation,
   * so the thread takes no new follow-up and says where to go instead — while
   * the resend stays, since it retries with that same resume id.
   */
  it('points a thread whose continuation died at a new question', async () => {
    serve(
      [],
      [
        thread({
          resumeInvalid: true,
          exchanges: [exchange({ question: 'which ones?', outcome: 'failed' })],
        }),
      ],
    )
    await openBoard()
    await openList()

    await userEvent.click(within(listRows()[0]!).getAllByRole('button')[0]!)

    expect(screen.getByLabelText<HTMLTextAreaElement>('Follow-up question on t-1').disabled).toBe(true)
    expect(screen.getByText(/can no longer be continued/)).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Resend the question of t-1' })).toBeTruthy()
  })
})

describe('going to an ask', () => {
  /**
   * spec-00005-AC-9.3 — the session panel's row of an ask leads to its thread,
   * not to a terminal: the document's editor opens on the ask list with that
   * thread located and open.
   */
  it('opens the located thread from the session panel row', async () => {
    serve([listing({ id: 's9' })], [thread({ id: 't-7', exchanges: [exchange({ runSessionId: 's9' })] })])
    await openBoard()
    const panel = await openPanel()

    await userEvent.click(within(within(panel).getAllByRole('listitem')[0]!).getAllByRole('button')[0]!)
    await settle()

    await waitFor(() => expect(screen.getByRole('tab', { name: 'Questions' })).toBeTruthy())
    expect(screen.getByLabelText('Editing prd-00001-x')).toBeTruthy()
    expect(screen.getByLabelText('Follow-up question on t-7')).toBeTruthy()
    expect(screen.queryByLabelText('Agent session')).toBeNull()
  })

  /**
   * spec-00005-AC-7.6 — a running ask has no terminal panel to be stopped from,
   * so every running session's row carries its own stop: it goes to the same
   * endpoint the terminal's does, and the panel stays where it is.
   */
  it('stops a running ask from its own panel row', async () => {
    serve([listing({ id: 's9' })], [thread()])
    const stop = vi.spyOn(api, 'stopSession').mockImplementation(async () => {
      served = [listing({ id: 's9', status: 'terminated' })]
      return { id: 's9', kind: 'ask', agent: 'claude', sourceId: 'prd-00001-x', status: 'terminated' }
    })
    await openBoard()
    const panel = await openPanel()

    await userEvent.click(within(panel).getByRole('button', { name: 'Stop the ask session of prd-00001-x' }))
    await settle()

    expect(stop).toHaveBeenCalledWith('s9')
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Stop the ask session of prd-00001-x' })).toBeNull(),
    )
  })
})

describe('the node marker over several sessions', () => {
  /**
   * spec-00005-AC-9.6 — an ask and an advance run on the same document: one
   * marker, not two, and activating it shows the advance on the terminal — the
   * terminal-form session is the one there can only be one of.
   */
  it('draws one marker and shows the terminal-form session', async () => {
    serve([
      listing({ id: 's1', kind: 'advance', sourceId: 'prd-00001-x' }),
      listing({ id: 's2', kind: 'ask', sourceId: 'prd-00001-x' }),
    ])
    await openBoard()

    expect(screen.getAllByRole('button', { name: /session of prd-00001-x$/ })).toHaveLength(1)
    // Clicked with the event alone: a full pointer sequence hands React Flow's
    // own drag a mousedown the canvas has no window for under jsdom.
    fireEvent.click(screen.getByRole('button', { name: 'Running session of prd-00001-x' }))

    await waitFor(() => expect(screen.getByLabelText('Agent session').textContent).toContain('advance'))
  })

  /**
   * spec-00005-AC-9.7 — only an ask is running, so the marker is the ask entry's
   * own icon and activating it opens that document's ask list. No terminal comes
   * up: there is none to come up.
   */
  it('opens the ask list from a document whose only session is an ask', async () => {
    serve([listing({ id: 's9' })], [thread({ id: 't-4', exchanges: [exchange({ runSessionId: 's9' })] })])
    await openBoard()

    fireEvent.click(screen.getByRole('button', { name: 'Ask session of prd-00001-x' }))
    await settle()

    await waitFor(() => expect(screen.getByLabelText('Follow-up question on t-4')).toBeTruthy())
    expect(screen.queryByLabelText('Agent session')).toBeNull()
    // Activating the marker is not selecting the node (design-00002 §12).
    expect(screen.queryByRole('toolbar', { name: 'Actions for prd-00001-x' })).toBeNull()
  })

  /**
   * spec-00005-FR-6 at the entries — an ask on this document locks none of its
   * starting points, and the terminal-form session on another document locks
   * nothing here either (spec-00001-AC-12.8).
   */
  it('locks no starting point of a document that only has an ask running', async () => {
    serve([listing({ id: 's9', sourceId: 'prd-00001-x' })])
    vi.spyOn(api, 'nextSteps').mockResolvedValue([{ next: 'spec', carry: 'parent' }])
    await openBoard()

    fireEvent.click(screen.getByTestId('node-prd-00001-x'))

    await waitFor(() => expect(screen.getByLabelText('Advance to the next step')).toBeTruthy())
    expect(screen.getByLabelText<HTMLButtonElement>('Advance to the next step').disabled).toBe(false)
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Ask' }).disabled).toBe(false)
  })
})
