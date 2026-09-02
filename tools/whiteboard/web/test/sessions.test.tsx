// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import { Board } from '../src/Board.tsx'
import { SessionPanel } from '../src/SessionPanel.tsx'
import { type SessionListing, api } from '../src/api.ts'
import { CAP_REACHED, DOC_BUSY } from '../src/Toolbar.tsx'

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

function serve(sessions: SessionListing[] = [], maxSessions = 3) {
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
    agents: [{ name: 'claude', headless: false, source: 'project' }],
    entry: [],
    carries: {},
    maxSessions,
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

/** The session panel, opened from the resident top-bar entry (spec-00003-FR-4). */
async function openPanel() {
  await userEvent.click(screen.getByRole('button', { name: 'Open the session panel' }))
  return screen.findByRole('list', { name: 'Agent sessions' })
}

const rows = () => screen.getAllByRole('listitem')

/** One panel row's own control, told from the stop that sits beside it. */
function row(list: HTMLElement, index: number): HTMLElement {
  return within(within(list).getAllByRole('listitem')[index]!).getAllByRole('button')[0]!
}
const clarify = () => screen.getByRole<HTMLButtonElement>('button', { name: 'Clarify' })
const advance = () => screen.getByLabelText<HTMLButtonElement>('Advance to the next step')

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

describe('the session panel', () => {
  function renderPanel(sessions: SessionListing[], showAgent = false) {
    const onStop = vi.fn()
    render(
      <SessionPanel
        open
        onOpenChange={vi.fn()}
        sessions={sessions}
        showAgent={showAgent}
        onPick={vi.fn()}
        onStop={onStop}
      />,
    )
    return onStop
  }

  /**
   * spec-00003-AC-4.1 — two running, one exited normally and one terminated: all
   * four are there, each with its kind, its target document, its state and when it
   * started, and the two that ended are not read as the same ending.
   */
  it('lists every session with its kind, document, state and start time', () => {
    renderPanel([
      listing({ id: 's1', kind: 'clarify', sourceId: 'prd-00001-x' }),
      listing({ id: 's2', kind: 'ask', sourceId: 'idea-00001-x' }),
      listing({ id: 's3', kind: 'advance', sourceId: 'spec-00001-x', status: 'exited', exitCode: 0 }),
      listing({ id: 's4', kind: 'audit', sourceId: 'rule-00001-x', status: 'terminated' }),
    ])

    expect(rows()).toHaveLength(4)
    const listed = rows().map((row) => row.textContent ?? '')
    expect(listed[0]).toContain('clarify')
    expect(listed[0]).toContain('prd-00001-x')
    expect(listed[0]).toContain('running')
    // The start time is on every row; it parses, so it is shown as a local stamp.
    expect(listed[0]).toContain(new Date('2026-02-01T09:00:00.000Z').toLocaleString())
    expect(listed[1]).toContain('ask')
    expect(listed[2]).toContain('exited')
    expect(listed[3]).toContain('terminated')
    expect(listed[3]).not.toContain('exited')
  })

  // spec-00003-AC-4.2 — nothing has run yet, and the panel says so rather than
  // showing an empty list
  it('shows an empty state when no session has run', () => {
    renderPanel([])

    expect(screen.getByText('no sessions since the board came up')).toBeTruthy()
    expect(screen.queryByRole('list', { name: 'Agent sessions' })).toBeNull()
  })

  // design-00002 §3 — running first, ended after: what is still going on is what
  // the panel was opened for
  it('puts the running sessions before the ended ones', () => {
    renderPanel([
      listing({ id: 's1', sourceId: 'prd-00001-x', status: 'exited' }),
      listing({ id: 's2', sourceId: 'idea-00001-x' }),
    ])

    expect(rows()[0]!.textContent).toContain('idea-00001-x')
    expect(rows()[1]!.textContent).toContain('prd-00001-x')
  })

  // spec-00003-AC-4.6 — a session whose agent CLI was not there never ran, and it
  // is in the panel as failed
  it('shows a session that failed to start as failed', () => {
    renderPanel([listing({ status: 'failed', error: 'spawn claude ENOENT' })])

    expect(rows()[0]!.textContent).toContain('failed')
  })

  // spec-00003-AC-4.7 — a process that exited non-zero ended, and ended badly
  it('shows a session that exited non-zero as failed', () => {
    renderPanel([listing({ status: 'failed', exitCode: 2 })])

    expect(rows()[0]!.textContent).toContain('failed')
  })

  // spec-00003-AC-4.8 — more than one agent is declared, so which one ran it is a
  // fact about the session
  it('names the agent when the config declares more than one', () => {
    renderPanel([listing({ agent: 'codex' })], true)

    expect(rows()[0]!.textContent).toContain('codex')
  })

  // spec-00003-AC-4.9 — exactly one agent declared: naming it says nothing
  it('names no agent when the config declares exactly one', () => {
    renderPanel([listing({ agent: 'claude' })])

    expect(rows()[0]!.textContent).not.toContain('claude')
  })

  // spec-00003-AC-6.1 / AC-6.5 in the panel: a running session that has gone quiet
  // reads as awaiting, and a live one reads as running
  it('shows a session waiting on an answer as awaiting', () => {
    renderPanel([
      listing({ id: 's1', sourceId: 'prd-00001-x', awaiting: true }),
      listing({ id: 's2', sourceId: 'idea-00001-x', awaiting: true }),
      listing({ id: 's3', sourceId: 'spec-00001-x' }),
    ])

    const listed = rows().map((row) => row.textContent ?? '')
    expect(listed.filter((text) => text.includes('awaiting'))).toHaveLength(2)
    expect(listed[2]).toContain('running')
  })

  /**
   * design-00002 §6 and §12 — every row of this fourth same-shaped list is a real
   * control: Tab reaches it and Enter fires it, the same obligation the governance
   * round's three lists carry.
   */
  it('makes every row a control the keyboard can reach and fire', async () => {
    const onPick = vi.fn()
    render(
      <SessionPanel
        open
        onOpenChange={vi.fn()}
        sessions={[listing()]}
        showAgent={false}
        onPick={onPick}
        onStop={vi.fn()}
      />,
    )

    // The row itself, which is the first control of the two it now carries: the
    // other is that session's stop (spec-00005-FR-7).
    const row = within(screen.getByRole('list', { name: 'Agent sessions' })).getAllByRole('button')[0]!
    row.focus()
    expect(document.activeElement).toBe(row)
    await userEvent.keyboard('{Enter}')

    expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ id: 's1' }))
  })
})

describe('the top-bar session entry', () => {
  // spec-00003-AC-4.5 — two of three slots are running
  it('reads the running count against the cap', async () => {
    serve([listing({ id: 's1', sourceId: 'prd-00001-x' }), listing({ id: 's2', sourceId: 'idea-00001-x' })])
    await openBoard()

    expect(screen.getByRole('button', { name: 'Open the session panel' }).textContent).toContain('2/3')
  })

  // spec-00003-AC-3.8 — a cap of one degenerates to single-session, panel and badge unchanged
  it('keeps the panel entry and badge working at a cap of one', async () => {
    serve([listing({ awaiting: true })], 1)
    await openBoard()

    expect(screen.getByRole('button', { name: 'Open the session panel' }).textContent).toContain('1/1')
    expect(screen.getByLabelText('1 awaiting input')).toBeTruthy()
  })

  // spec-00003-AC-6.1 — the badge appears with the count of sessions waiting
  it('shows the awaiting count beside the entry', async () => {
    serve([listing({ awaiting: true })])
    await openBoard()

    expect(screen.getByLabelText('1 awaiting input')).toBeTruthy()
  })

  // spec-00003-AC-6.5 — two waiting, and the count says two
  it('counts both sessions when two are waiting', async () => {
    serve([
      listing({ id: 's1', sourceId: 'prd-00001-x', awaiting: true }),
      listing({ id: 's2', sourceId: 'idea-00001-x', awaiting: true }),
    ])
    await openBoard()

    expect(screen.getByLabelText('2 awaiting input')).toBeTruthy()
  })

  /**
   * spec-00003-AC-6.2 — the user answers, the session prints again, and the mark
   * comes down: the count falls with it.
   */
  it('drops the count when a session answers and goes on printing', async () => {
    serve([
      listing({ id: 's1', sourceId: 'prd-00001-x', awaiting: true }),
      listing({ id: 's2', sourceId: 'idea-00001-x', awaiting: true }),
    ])
    await openBoard()
    expect(screen.getByLabelText('2 awaiting input')).toBeTruthy()

    served = [
      listing({ id: 's1', sourceId: 'prd-00001-x' }),
      listing({ id: 's2', sourceId: 'idea-00001-x', awaiting: true }),
    ]
    await push()

    await waitFor(() => expect(screen.getByLabelText('1 awaiting input')).toBeTruthy())
  })

  /**
   * spec-00003-AC-6.3 — the one waiting session ends, so the mark comes down, the
   * count is zero and the badge is not drawn at all (the diagnostics count's zero
   * reading, design-00002 §3).
   */
  it('draws no badge once no session is waiting', async () => {
    serve([listing({ awaiting: true })])
    await openBoard()
    expect(screen.getByLabelText('1 awaiting input')).toBeTruthy()

    served = [listing({ status: 'exited', awaiting: undefined })]
    await push()

    await waitFor(() => expect(screen.queryByLabelText('1 awaiting input')).toBeNull())
    expect(screen.getByRole('button', { name: 'Open the session panel' }).textContent).toContain('0/3')
  })
})

describe('picking a session out of the panel', () => {
  /**
   * spec-00003-AC-4.3 — the terminal comes up on that session and the board goes
   * to its document: the panel closes on the way.
   */
  it('shows the session and selects its document', async () => {
    serve([
      listing({ id: 's1', sourceId: 'prd-00001-x' }),
      listing({ id: 's2', kind: 'audit', sourceId: 'idea-00001-x' }),
    ])
    await openBoard()
    const list = await openPanel()

    await userEvent.click(row(list, 0))

    const terminal = await screen.findByLabelText('Agent session')
    expect(terminal.textContent).toContain('prd-00001-x')
    expect(screen.queryByRole('list', { name: 'Agent sessions' })).toBeNull()
    // The document is selected, which is what its floating toolbar being there says.
    await waitFor(() => expect(screen.getByRole('toolbar', { name: 'Actions for prd-00001-x' })).toBeTruthy())
  })

  /**
   * spec-00003-AC-4.4 — the session's document has left the board, so only the
   * terminal happens: the refusal is said out loud and the selection does not move
   * (close nearest, design-00002 §10).
   */
  it('shows the session and says so when its document has left the board', async () => {
    serve([listing({ id: 's1', sourceId: 'prd-00001-x' }), listing({ id: 's2', sourceId: 'gone-00009-x' })])
    await openBoard()
    // Something is selected before the pick, and it must still be selected after.
    fireEvent.click(screen.getByTestId('node-idea-00001-x'))
    await waitFor(() => expect(screen.getByRole('toolbar', { name: 'Actions for idea-00001-x' })).toBeTruthy())
    const list = await openPanel()

    await userEvent.click(row(list, 1))

    const terminal = await screen.findByLabelText('Agent session')
    expect(terminal.textContent).toContain('gone-00009-x')
    expect(toast.error).toHaveBeenCalledWith('no document gone-00009-x on the board')
    expect(screen.getByRole('toolbar', { name: 'Actions for idea-00001-x' })).toBeTruthy()
  })
})

describe('the session marker on a node', () => {
  /**
   * spec-00003-AC-10.1 — the document with a session running carries the marker,
   * and activating it puts that session on the terminal. It is not a selection:
   * the gesture stops at the marker (design-00002 §12).
   */
  it('marks the node and shows that session without selecting the node', async () => {
    serve([
      listing({ id: 's1', kind: 'clarify', sourceId: 'prd-00001-x' }),
      listing({ id: 's2', kind: 'audit', sourceId: 'idea-00001-x' }),
    ])
    await openBoard()
    // The board came up on the newest running session, which is the other one.
    await waitFor(() => expect(screen.getByLabelText('Agent session').textContent).toContain('idea-00001-x'))

    await userEvent.click(screen.getByRole('button', { name: 'Running session of prd-00001-x' }))

    await waitFor(() => expect(screen.getByLabelText('Agent session').textContent).toContain('prd-00001-x'))
    expect(screen.queryByRole('toolbar', { name: 'Actions for prd-00001-x' })).toBeNull()
  })

  // The same act from the keyboard, and with the same two halves: Enter fires the
  // marker and the node is still not selected (design-00002 §6, §12).
  it('answers Enter on the marker the same way', async () => {
    serve([
      listing({ id: 's1', kind: 'clarify', sourceId: 'prd-00001-x' }),
      listing({ id: 's2', kind: 'audit', sourceId: 'idea-00001-x' }),
    ])
    await openBoard()

    const marker = screen.getByRole('button', { name: 'Running session of prd-00001-x' })
    marker.focus()
    await userEvent.keyboard('{Enter}')

    await waitFor(() => expect(screen.getByLabelText('Agent session').textContent).toContain('prd-00001-x'))
    expect(screen.queryByRole('toolbar', { name: 'Actions for prd-00001-x' })).toBeNull()
  })

  /**
   * spec-00003-AC-10.2 — waiting on an answer is a mark of its own and is told
   * from the running one without colour: a different icon and a different name.
   */
  it('marks a waiting session apart from a running one', async () => {
    serve([
      listing({ id: 's1', sourceId: 'prd-00001-x', awaiting: true }),
      listing({ id: 's2', sourceId: 'idea-00001-x' }),
    ])
    await openBoard()

    expect(screen.getByRole('button', { name: 'Awaiting input session of prd-00001-x' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Running session of idea-00001-x' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Running session of prd-00001-x' })).toBeNull()
  })

  // spec-00003-AC-10.3 — the session ended, and the refresh that follows takes the
  // marker with it
  it('drops the marker once the session has ended', async () => {
    serve([listing()])
    await openBoard()
    expect(screen.getByRole('button', { name: 'Running session of prd-00001-x' })).toBeTruthy()

    served = [listing({ status: 'exited' })]
    await push()

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Running session of prd-00001-x' })).toBeNull(),
    )
  })

  /**
   * spec-00003-AC-10.4 — the marker is presentation and nothing else: it declares
   * no relation, so the graph has exactly the edges its front matter declares, and
   * it is no reading of a document, so no diagnostic comes of it.
   */
  it('adds no edge and no diagnostic to the graph', async () => {
    serve([])
    const { container } = await openBoard()
    await waitFor(() => expect(container.querySelectorAll('.react-flow__edge')).toHaveLength(1))

    served = [listing()]
    await push()

    await waitFor(() => expect(screen.getByRole('button', { name: 'Running session of prd-00001-x' })).toBeTruthy())
    expect(container.querySelectorAll('.react-flow__edge')).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Open the diagnostics list' })).toBeNull()
    expect(screen.getByText('no issues')).toBeTruthy()
  })
})

describe('the starting points under the concurrency rules', () => {
  /**
   * spec-00001-AC-12.8 (sixteenth round) — a session on another document locks
   * nothing here: the rule is per target document, and the cap is not reached.
   */
  it('leaves another document starting points alone', async () => {
    serve([listing({ id: 's1', sourceId: 'idea-00001-x' })])
    await openBoard()

    fireEvent.click(screen.getByTestId('node-prd-00001-x'))

    await waitFor(() => expect(clarify()).toBeTruthy())
    expect(clarify().disabled).toBe(false)
    expect(advance().disabled).toBe(false)
  })

  /**
   * spec-00001-AC-49.11 — the cap is reached, so a document with no session of its
   * own cannot start one either, and the reason says which rule holds.
   */
  it('locks a free document starting points at the cap and says why', async () => {
    serve([
      listing({ id: 's1', sourceId: 'idea-00001-x' }),
      listing({ id: 's2', sourceId: 'spec-00001-x' }),
      listing({ id: 's3', sourceId: 'rule-00001-x' }),
    ])
    await openBoard()

    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(clarify().disabled).toBe(true))
    await userEvent.hover(clarify().parentElement!)

    const tooltip = await screen.findByRole('tooltip')
    expect(tooltip.textContent).toContain(CAP_REACHED)
    expect(tooltip.textContent).not.toContain(DOC_BUSY)
  })

  /**
   * spec-00001-AC-49.12 — one session ends, the total falls below the cap, and the
   * free document's starting points come back with no user action at all.
   */
  it('hands the starting points back when the total falls below the cap', async () => {
    serve([
      listing({ id: 's1', sourceId: 'idea-00001-x' }),
      listing({ id: 's2', sourceId: 'spec-00001-x' }),
      listing({ id: 's3', sourceId: 'rule-00001-x' }),
    ])
    await openBoard()
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(clarify().disabled).toBe(true))

    served = [
      listing({ id: 's1', sourceId: 'idea-00001-x', status: 'exited' }),
      listing({ id: 's2', sourceId: 'spec-00001-x' }),
      listing({ id: 's3', sourceId: 'rule-00001-x' }),
    ]
    await push()

    await waitFor(() => expect(clarify().disabled).toBe(false))
    expect(screen.getByRole('button', { name: 'Open the session panel' }).textContent).toContain('2/3')
  })

  /**
   * spec-00003-AC-2.4 and spec-00001-AC-49.5 at the board: this document's own
   * session is the reason named, and it is named in preference to the cap.
   */
  it('names this document own session as the reason its entries are locked', async () => {
    serve([
      listing({ id: 's1', sourceId: 'prd-00001-x' }),
      listing({ id: 's2', sourceId: 'spec-00001-x' }),
      listing({ id: 's3', sourceId: 'rule-00001-x' }),
    ])
    await openBoard()

    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(clarify().disabled).toBe(true))
    await userEvent.hover(clarify().parentElement!)

    expect((await screen.findByRole('tooltip')).textContent).toContain(DOC_BUSY)
  })

  /**
   * spec-00001-AC-12.8's other half — the document's session ended without
   * touching `docs/`, and its own entries come back with no user action.
   */
  it('hands this document entries back when its session ends', async () => {
    serve([listing()])
    await openBoard()
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(clarify().disabled).toBe(true))

    served = [listing({ status: 'exited' })]
    await push()

    await waitFor(() => expect(clarify().disabled).toBe(false))
  })
})
