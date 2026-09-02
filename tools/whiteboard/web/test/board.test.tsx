// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import { ReactFlowProvider } from '@xyflow/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import { NodeCard } from '../src/NodeCard.tsx'
import { ANOMALY_TOKEN, statusColour, statusLabel } from '../src/status.ts'
import { connectTerminal } from '../src/terminalSocket.ts'
import { useBoard } from '../src/useBoard.ts'
import { toast } from 'sonner'
import { ApiError, type AskThread, type SessionListing, api } from '../src/api.ts'
import { COLUMN_GAP, NODE_HEIGHT, NODE_WIDTH, ROW_GAP, layoutGraph } from '../src/layout.ts'
import { toFlowEdges } from '../src/canvasModel.ts'

function node(overrides: Partial<DocNode> = {}): DocNode {
  return {
    id: 'prd-00001-x',
    path: 'prd/a.md',
    type: 'prd',
    status: 'draft',
    title: 'X',
    relations: {},
    ok: true,
    problems: [],
    ...overrides,
  }
}

/**
 * One row of `GET /api/sessions` (design-00001 §7). The list is what the board
 * works off since the sixteenth round: several sessions run at once, and which
 * one the terminal shows is the board's own state (spec-00003-FR-4, FR-5).
 */
function listing(overrides: Partial<SessionListing> = {}): SessionListing {
  return {
    id: 's1',
    kind: 'clarify',
    agent: 'claude',
    sourceId: 'prd-00001-x',
    status: 'running',
    startedAt: '2026-01-01T00:00:00.000Z',
    ...overrides,
  }
}

const GRAPH: DocGraph = {
  nodes: [node(), node({ id: 'idea-00001-x', type: 'idea', status: 'active', path: 'idea/a.md' })],
  edges: [{ from: 'prd-00001-x', to: 'idea-00001-x', relation: 'parent', ok: true, declaredTargets: ['idea-00001-x'] }],
  issues: [],
  idOwners: {},
  diagnostics: [],
}

afterEach(cleanup)

describe('status colours', () => {
  it('gives every status its own colour', () => {
    const colours = ['draft', 'active', 'open', 'resolved', 'wontfix', 'archived'].map((status) =>
      statusColour(node({ status })),
    )
    expect(new Set(colours).size).toBe(colours.length)
  })

  it('paints an anomalous document as a problem, whatever its status', () => {
    expect(statusColour(node({ ok: false }))).toBe(ANOMALY_TOKEN)
    expect(statusLabel(node({ ok: false }))).toBe('front matter problem')
  })

  it('paints an unknown status as a problem', () => {
    expect(statusColour(node({ status: 'review' }))).toBe(ANOMALY_TOKEN)
    expect(statusColour(node({ status: undefined }))).toBe(ANOMALY_TOKEN)
  })

  it('labels a healthy document with its status', () => {
    expect(statusLabel(node({ status: 'active' }))).toBe('active')
    expect(statusLabel(node({ status: undefined }))).toBe('')
  })
})

describe('a node on the canvas', () => {
  // A handle reads the React Flow store, so the node only renders inside a
  // provider now — the cost of owning the connection contract (issue-00002).
  function renderCard(props: Parameters<typeof NodeCard>[0]) {
    return render(
      <ReactFlowProvider>
        <NodeCard {...props} />
      </ReactFlowProvider>,
    )
  }

  it('shows the type, title, id, and status', () => {
    renderCard({ node: node(), selected: false })

    expect(screen.getByText('prd')).toBeTruthy()
    expect(screen.getByText('X')).toBeTruthy()
    expect(screen.getByText('prd-00001-x')).toBeTruthy()
    expect(screen.getByText('draft')).toBeTruthy()
  })

  // spec-00001-AC-2.1 — problems live behind a popover so long text cannot burst the node
  it('shows the problems of an anomalous document on request', async () => {
    renderCard({ node: node({ ok: false, problems: ['front matter is missing'] }), selected: true })
    expect(screen.queryByText('front matter is missing')).toBeNull()

    await userEvent.click(screen.getByLabelText('Front matter problems of prd-00001-x'))

    expect(screen.getByText('front matter is missing')).toBeTruthy()
  })

  it('counts the problems on the node face', () => {
    renderCard({ node: node({ ok: false, problems: ['a', 'b'] }), selected: true })
    expect(screen.getByLabelText('Front matter problems of prd-00001-x').textContent).toContain('2 problems')
  })

  /**
   * spec-00003-FR-10 and design-00002 §12 — the marker's whole gesture stops at
   * the marker: the click that fires it, the Enter that fires it, and the press
   * that would otherwise have React Flow select or drag the node underneath. A
   * key that is neither Enter nor Space is not the marker's, and is left to travel.
   */
  it('keeps the node gestures off the session marker', () => {
    const reachedTheNode = vi.fn()
    render(
      <ReactFlowProvider>
        {/* Standing in for React Flow's own node wrapper, which is what selects
            and drags the node the marker sits on. */}
        <div
          onClick={reachedTheNode}
          onPointerDown={reachedTheNode}
          onMouseDown={reachedTheNode}
          onKeyDown={reachedTheNode}
        >
          <NodeCard node={node()} selected={false} sessions={[listing()]} onShowSession={vi.fn()} />
        </div>
      </ReactFlowProvider>,
    )
    const marker = screen.getByLabelText('Running session of prd-00001-x')

    fireEvent.pointerDown(marker)
    fireEvent.mouseDown(marker)
    fireEvent.click(marker)
    fireEvent.keyDown(marker, { key: 'Enter' })
    expect(reachedTheNode).not.toHaveBeenCalled()

    fireEvent.keyDown(marker, { key: 'Escape' })
    expect(reachedTheNode).toHaveBeenCalledTimes(1)
  })

  // The marker is not offered a way onto the terminal here, and pressing it is
  // still not an error — a card rendered outside the canvas has no session to show.
  it('takes a press on the marker with nowhere to send it', () => {
    renderCard({ node: node(), selected: false, sessions: [listing({ awaiting: true })] })

    fireEvent.click(screen.getByLabelText('Awaiting input session of prd-00001-x'))

    expect(screen.getByLabelText('Awaiting input session of prd-00001-x')).toBeTruthy()
  })

  it('shows a placeholder type when the front matter carries none', () => {
    renderCard({ node: node({ type: undefined }), selected: false })
    expect(screen.getByText('—')).toBeTruthy()
  })

  // spec-00001-AC-29.2 — the node recedes while another node holds the focus
  it('recedes when suppressed', () => {
    const { container } = renderCard({ node: node(), selected: false, suppressed: true })
    expect(container.querySelector('.node--suppressed')).toBeTruthy()
  })

  it('stays at full strength when nothing is suppressed', () => {
    const { container } = renderCard({ node: node(), selected: false })
    expect(container.querySelector('.node--suppressed')).toBeNull()
  })

  /**
   * A node that collides on its id (spec-00002-AC-8.1, design-00002 §4): it is
   * an anomalous node like any other — same border, same badge, same popover —
   * and the one difference is the fourth line, which shows the file path the
   * node is keyed by and the id it collided on beside it. Both have to be
   * there: the path tells the two files apart, the id says what they collided on.
   */
  it('shows the file path and the colliding id of a duplicated document', async () => {
    const clashing = node({
      id: 'spec/second.md',
      path: 'spec/second.md',
      duplicateOf: 'spec-00002-clash',
      ok: false,
      problems: ['id "spec-00002-clash" is also declared by spec/first.md'],
    })
    renderCard({ node: clashing, selected: false })

    expect(screen.getByText('spec/second.md')).toBeTruthy()
    expect(screen.getByText('spec-00002-clash')).toBeTruthy()

    await userEvent.click(screen.getByLabelText('Front matter problems of spec/second.md'))
    expect(screen.getByText('id "spec-00002-clash" is also declared by spec/first.md')).toBeTruthy()
  })

  it('shows no second id on a document whose id is its own', () => {
    renderCard({ node: node(), selected: false })
    expect(screen.getByText('prd-00001-x').textContent).toBe('prd-00001-x')
  })
})

// spec-00001-AC-1.1, AC-1.2 and AC-1.6 … AC-1.9 (decision-00002 §2)
describe('the layout', () => {
  const ORDER = ['idea', 'prd', 'spec', 'rule']

  function graphOf(...nodes: DocNode[]): DocGraph {
    return { nodes, edges: [], issues: [], diagnostics: [], idOwners: {} }
  }

  function at(placed: { id: string; x: number; y: number }[], id: string) {
    return placed.find((item) => item.id === id)!
  }

  // spec-00001-AC-1.6
  it('places each type in its own column, left to right', () => {
    const placed = layoutGraph(
      graphOf(
        node({ id: 'spec-00001-x', type: 'spec', path: 'spec/a.md' }),
        node({ id: 'idea-00001-x', type: 'idea', path: 'idea/a.md' }),
        node(),
      ),
      ORDER,
    )

    expect(at(placed, 'idea-00001-x').x).toBeLessThan(at(placed, 'prd-00001-x').x)
    expect(at(placed, 'prd-00001-x').x).toBeLessThan(at(placed, 'spec-00001-x').x)
    expect(new Set(placed.map((item) => item.y))).toEqual(new Set([0]))
  })

  // spec-00001-AC-1.7
  it('stacks documents of the same type in one column, by id', () => {
    const placed = layoutGraph(
      graphOf(
        node({ id: 'spec-00002-b', type: 'spec', path: 'spec/b.md' }),
        node({ id: 'spec-00001-a', type: 'spec', path: 'spec/a.md' }),
      ),
      ORDER,
    )

    expect(at(placed, 'spec-00001-a').x).toBe(at(placed, 'spec-00002-b').x)
    expect(at(placed, 'spec-00001-a').y).toBeLessThan(at(placed, 'spec-00002-b').y)
  })

  // spec-00001-AC-1.8 — `prd` is declared between them but has no document
  it('leaves no empty column for a type with no documents', () => {
    const placed = layoutGraph(
      graphOf(
        node({ id: 'idea-00001-x', type: 'idea', path: 'idea/a.md' }),
        node({ id: 'spec-00001-x', type: 'spec', path: 'spec/a.md' }),
      ),
      ORDER,
    )

    const gap = at(placed, 'spec-00001-x').x - at(placed, 'idea-00001-x').x
    expect(gap).toBe(NODE_WIDTH + COLUMN_GAP)
  })

  // spec-00001-AC-1.9
  it('puts an undeclared type after every declared one', () => {
    const placed = layoutGraph(
      graphOf(node({ id: 'weird-00001-x', type: 'weird', path: 'weird/a.md' }), node()),
      ORDER,
    )

    expect(at(placed, 'weird-00001-x').x).toBeGreaterThan(at(placed, 'prd-00001-x').x)
  })

  it('puts a document with no type last of all', () => {
    const placed = layoutGraph(
      graphOf(
        node({ id: 'docs/broken.md', type: undefined, path: 'docs/broken.md', ok: false }),
        node({ id: 'weird-00001-x', type: 'weird', path: 'weird/a.md' }),
        node(),
      ),
      ORDER,
    )

    expect(at(placed, 'docs/broken.md').x).toBeGreaterThan(at(placed, 'weird-00001-x').x)
  })

  // issue-00004 is still open: an empty `type:` must not become a column of its
  // own, sorting ahead of the genuinely named unknown types.
  it('treats an empty type as a missing one', () => {
    const placed = layoutGraph(
      graphOf(
        node({ id: 'empty-00001-x', type: '', path: 'empty/a.md' }),
        node({ id: 'weird-00001-x', type: 'weird', path: 'weird/a.md' }),
        node(),
      ),
      ORDER,
    )

    expect(at(placed, 'empty-00001-x').x).toBeGreaterThan(at(placed, 'weird-00001-x').x)
  })

  // Two documents may share an id (issue-00004); the row order stays total, so
  // the layout function itself never returns two identical coordinates.
  it('breaks an id tie with the file path', () => {
    const placed = layoutGraph(
      graphOf(node({ path: 'prd/b.md' }), node({ path: 'prd/a.md' })),
      ORDER,
    )

    expect(placed.map((item) => item.y)).toEqual([0, NODE_HEIGHT + ROW_GAP])
  })

  // spec-00001-AC-1.13 — the lone document still lands in its own type column,
  // so the fixture needs a neighbour of another type to make that observable.
  it('places a document that declares no relations, with no edge on it', () => {
    const graph = graphOf(node(), node({ id: 'idea-00001-x', type: 'idea', path: 'idea/a.md' }))
    const placed = layoutGraph(graph, ORDER)

    expect(at(placed, 'prd-00001-x')).toEqual({ id: 'prd-00001-x', x: NODE_WIDTH + COLUMN_GAP, y: 0 })
    expect(toFlowEdges(graph, placed)).toEqual([])
  })

  // spec-00001-AC-1.4
  it('places nothing for an empty graph', () => {
    expect(layoutGraph({ nodes: [], edges: [], issues: [], diagnostics: [], idOwners: {} }, ORDER)).toEqual([])
  })

  // spec-00001-AC-2.2 — a broken edge must not drag its ghost target into the layout
  it('ignores edges pointing at an unknown document', () => {
    const placed = layoutGraph(
      {
        nodes: [node()],
        edges: [
          {
            from: 'prd-00001-x',
            to: 'idea-09999-ghost',
            relation: 'parent',
            ok: false,
            declaredTargets: ['idea-09999-ghost'],
          },
        ],
        issues: [],
        idOwners: {},
        diagnostics: [],
      },
      ORDER,
    )
    expect(placed).toHaveLength(1)
  })
})

/**
 * What a clicked desktop notification would go to (spec-00004-FR-5). These cases
 * drive the hook directly and never post one, so the destination is a stand-in.
 */
const GO_TO_SESSION = () => {}

/** One thread of an ask list, as `GET /api/asks/:id` hands it over (design-00001 §10.2). */
function askThread(id: string): AskThread {
  return {
    id,
    agent: 'claude',
    exchanges: [
      { question: 'why?', askedAt: '2026-01-01T00:00:00.000Z', outcome: 'running', runSessionId: 's1' },
    ],
  }
}

describe('the board state', () => {
  beforeEach(() => {
    vi.spyOn(api, 'graph').mockResolvedValue(GRAPH)
    vi.spyOn(api, 'transitions').mockResolvedValue(['active', 'archived'])
    vi.spyOn(api, 'nextSteps').mockResolvedValue([{ next: 'spec', carry: 'parent' }])
    vi.spyOn(api, 'sessions').mockResolvedValue([])
    vi.spyOn(api, 'config').mockResolvedValue({
      types: { prd: 'living', idea: 'living' },
      relations: ['parent'],
      flow: {},
      focus: {},
      agents: [{ name: 'claude', headless: false, source: 'project' }],
      entry: [],
      carries: {},
      maxSessions: 3,
      clarifiable: [],
      auditable: ['spec', 'rule', 'design'],
    })
    vi.spyOn(toast, 'error').mockImplementation(() => 'id')
  })

  afterEach(() => vi.restoreAllMocks())

  it('loads the graph and its layout on mount', async () => {
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))

    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))
    expect(result.current.placed).toHaveLength(2)
  })

  /**
   * issue-00018 — the reads queue so their readings are folded in in order, and
   * a read that failed must not be a queue nobody can get past: the next one
   * still runs and the board catches up.
   */
  it('takes the next read after one that failed', async () => {
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))

    vi.spyOn(api, 'graph').mockRejectedValueOnce(new Error('graph: unreadable'))
    await act(async () => {
      await expect(result.current.refresh()).rejects.toThrow('graph: unreadable')
    })

    vi.spyOn(api, 'graph').mockResolvedValue({ ...GRAPH, nodes: [GRAPH.nodes[0]!] })
    await act(async () => void (await result.current.refresh()))

    expect(result.current.graph.nodes).toHaveLength(1)
  })

  // The column order arrives from GET /api/config; laying out before it lands
  // would put every node in the unknown-type bucket (design-00002 §2).
  it('lays out with the column order the config declares', async () => {
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.placed).toHaveLength(2))

    const idea = result.current.placed.find((item) => item.id === 'idea-00001-x')!
    const prd = result.current.placed.find((item) => item.id === 'prd-00001-x')!
    // The config above declares prd first, then idea — so prd is the left column.
    expect(prd.x).toBeLessThan(idea.x)
  })

  // The column order is a nicety; the graph is the point. Losing the config
  // must not cost the user the board (verifier finding on plan-00003).
  it('still draws the graph when the config cannot be read', async () => {
    vi.spyOn(api, 'config').mockRejectedValue(new Error('config: no flow config at whiteboard.config.yaml'))
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))

    await waitFor(() => expect(result.current.placed).toHaveLength(2))
    expect(toast.error).toHaveBeenCalledWith('config: no flow config at whiteboard.config.yaml')
  })

  // spec-00001-AC-1.12
  it('puts every node back where it was after a refresh', async () => {
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.placed).toHaveLength(2))
    const before = result.current.placed

    await act(() => result.current.refresh().then(() => undefined))

    expect(result.current.placed).toEqual(before)
  })

  it('selects a node and loads what it may do', async () => {
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))

    await act(() => result.current.select('prd-00001-x'))

    expect(result.current.selectedNode!.id).toBe('prd-00001-x')
    expect(result.current.transitions).toEqual(['active', 'archived'])
    expect(result.current.nextSteps).toEqual([{ next: 'spec', carry: 'parent' }])
  })

  // spec-00001-AC-3.2
  it('drops the selection on deselect', async () => {
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await act(() => result.current.select('prd-00001-x'))

    act(() => result.current.deselect())

    expect(result.current.selectedNode).toBeUndefined()
  })

  it('keeps the editor and the terminal as independent switches', async () => {
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))

    act(() => result.current.edit('prd-00001-x'))
    act(() => result.current.setTerminalOpen(true))

    expect(result.current.editing).toBe('prd-00001-x')
    expect(result.current.terminalOpen).toBe(true)
  })

  it('refreshes the graph after an action', async () => {
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))
    const action = vi.fn().mockResolvedValue(undefined)

    await act(() => result.current.run(action))

    expect(action).toHaveBeenCalled()
    expect(api.graph).toHaveBeenCalledTimes(2)
  })

  // spec-00001-AC-7.1 as the user sees it — the refusal reaches the user as a toast
  it('reports a refusal instead of throwing', async () => {
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))

    await act(() => result.current.run(() => Promise.reject(new Error('not a legal transition'))))

    expect(toast.error).toHaveBeenCalledWith('not a legal transition')
  })

  /**
   * The ask list is held by document id (design-00002 §10), and two reads of it
   * can be in flight at once — a refresh's and a switch's. The answer is only
   * good for the list it was asked about: a slow one landing last would paint
   * one document's threads under another document's name.
   */
  it('drops an ask list read that lands after the list has moved on', async () => {
    let releaseFirst!: (threads: AskThread[]) => void
    vi.spyOn(api, 'asks').mockImplementation((docId) =>
      docId === 'prd-00001-x'
        ? new Promise<AskThread[]>((resolve) => {
            releaseFirst = resolve
          })
        : Promise.resolve([askThread('t-idea')]),
    )
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))

    // The first document's list is asked for and still in flight; the second's
    // is asked for and lands.
    void act(() => void result.current.showAsks('prd-00001-x'))
    await act(() => result.current.showAsks('idea-00001-x'))
    expect(result.current.threads.map((one) => one.id)).toEqual(['t-idea'])

    await act(async () => {
      releaseFirst([askThread('t-prd')])
      await Promise.resolve()
    })

    expect(result.current.threads.map((one) => one.id)).toEqual(['t-idea'])
  })

  /**
   * The same holding by document id on the way in and on the way down: opening
   * another document's list starts empty rather than showing the last one's, and
   * a read that failed leaves nothing painted — an empty list with the reason
   * said out loud beats another document's threads under this name.
   */
  it('starts an ask list empty on a switch, and empties it when the read fails', async () => {
    let refuseSecond!: (cause: Error) => void
    vi.spyOn(api, 'asks')
      .mockResolvedValueOnce([askThread('t-prd')])
      .mockReturnValueOnce(
        new Promise<AskThread[]>((_resolve, reject) => {
          refuseSecond = reject
        }),
      )
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))
    await act(() => result.current.showAsks('prd-00001-x'))
    expect(result.current.threads).toHaveLength(1)

    // The switch itself: the next document's list opens empty, before any answer
    // about it has come back.
    void act(() => void result.current.showAsks('idea-00001-x'))
    await waitFor(() => expect(result.current.threads).toEqual([]))

    await act(async () => {
      refuseSecond(new Error('no list'))
      await Promise.resolve()
    })

    expect(result.current.threads).toEqual([])
    expect(toast.error).toHaveBeenCalledWith('no list')
  })

  /**
   * spec-00001-FR-52 as the user sees it (design-00002 §3): the gate's refusal
   * is a list of gaps, and the toast leads with how many there are — the number
   * the user has to work through — before naming them.
   */
  it('reports a resolved-gate refusal as a count of unverified items and their ids', async () => {
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))
    const refusal = new ApiError(422, 'plan-00001-x has unverified items', [
      'spec-00001-FR-1',
      'idea-09999-ghost',
    ])

    await act(() => result.current.run(() => Promise.reject(refusal)))

    expect(toast.error).toHaveBeenCalledWith('2 items unverified: spec-00001-FR-1, idea-09999-ghost')
  })

  // A plan can deliver dozens of items; the list is cut and the count is not.
  it('keeps the count when the gap list is too long to name in full', async () => {
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))
    const gaps = Array.from({ length: 8 }, (_, index) => `spec-00001-FR-${index + 1}`)

    await act(() => result.current.run(() => Promise.reject(new ApiError(422, 'unverified', gaps))))

    const said = vi.mocked(toast.error).mock.calls[0]![0] as string
    expect(said).toContain('8 items unverified')
    expect(said).toContain('spec-00001-FR-5')
    expect(said).not.toContain('spec-00001-FR-6')
    expect(said).toContain('and 3 more')
  })

  // A 422 that is not the gate's names no gaps, and reads as it always did.
  it('reports a refusal that names no gaps as its own message', async () => {
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))

    await act(() =>
      result.current.run(() => Promise.reject(new ApiError(422, 'draft → resolved is not a legal transition'))),
    )

    expect(toast.error).toHaveBeenCalledWith('draft → resolved is not a legal transition')
  })

  it('reports a non-error refusal as text', async () => {
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await act(() => result.current.run(() => Promise.reject('nope')))
    expect(toast.error).toHaveBeenCalledWith('nope')
  })

  // spec-00001-FR-48 — the clarifiable types are the config's answer, read off
  // the focus block; the board holds no type list of its own.
  it('takes the clarifiable types from the config focus block', async () => {
    vi.spyOn(api, 'config').mockResolvedValue({
      types: { prd: 'living', idea: 'living' },
      relations: ['parent'],
      flow: {},
      focus: { prd: 'roles, scope, and the value trade-offs' },
      agents: [{ name: 'claude', headless: false, source: 'project' }],
      entry: [],
      carries: {},
      maxSessions: 3,
      clarifiable: ['prd'],
      auditable: ['spec', 'rule', 'design'],
    })
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))

    await waitFor(() => expect(result.current.clarifiable).toEqual(['prd']))
  })

  // spec-00003-AC-2.1 — a refused start leaves the running session alone
  it('keeps the session it has when a second start is refused', async () => {
    vi.spyOn(api, 'sessions').mockResolvedValue([listing()])
    vi.spyOn(api, 'clarify').mockRejectedValue(new Error('an agent session is already running'))
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.shownSession?.id).toBe('s1'))

    await act(() => result.current.startSession(() => api.clarify('idea-00001-x')))

    expect(result.current.shownSession).toMatchObject({ id: 's1', status: 'running' })
    expect(toast.error).toHaveBeenCalledWith(expect.stringMatching(/already running/))
  })

  /**
   * spec-00003-AC-4.5 — the entry divides the running ones by the cap, and the
   * cap is the config's word (`GET /api/config`, design-00001 §7).
   */
  it('counts the running sessions against the cap the config declares', async () => {
    vi.spyOn(api, 'sessions').mockResolvedValue([
      listing({ id: 's1' }),
      listing({ id: 's2', sourceId: 'idea-00001-x' }),
      listing({ id: 's3', sourceId: 'spec-00001-x', status: 'exited' }),
    ])
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))

    await waitFor(() => expect(result.current.running).toHaveLength(2))
    expect(result.current.maxSessions).toBe(3)
    expect(result.current.sessions).toHaveLength(3)
  })

  // spec-00003-AC-6.5 — awaiting input is counted off the running sessions
  it('counts the sessions that are waiting on an answer', async () => {
    vi.spyOn(api, 'sessions').mockResolvedValue([
      listing({ id: 's1', awaiting: true }),
      listing({ id: 's2', sourceId: 'idea-00001-x', awaiting: true }),
      listing({ id: 's3', sourceId: 'spec-00001-x' }),
      // Ended and quiet is not waiting on anybody (spec-00003-AC-6.4).
      listing({ id: 's4', sourceId: 'rule-00001-x', status: 'exited', awaiting: true }),
    ])
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))

    await waitFor(() => expect(result.current.awaitingCount).toBe(2))
  })

  /**
   * spec-00001-AC-11.1 and spec-00003-AC-5.4 — the terminal comes up on the
   * session that was just started, whether or not one was on it before. The
   * stand-in answers the POST *and* the listing the refresh reads right after, as
   * the server does: a session the server denied holding would be closed again on
   * the spot (close nearest, design-00002 §10).
   */
  it('opens the terminal on the session an advance starts', async () => {
    const started = listing({ id: 's9', kind: 'advance', sourceId: 'idea-00001-x', targetType: 'prd' })
    let held: SessionListing[] = []
    vi.spyOn(api, 'sessions').mockImplementation(async () => held)
    vi.spyOn(api, 'advance').mockImplementation(async () => {
      held = [started]
      return started
    })
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))

    await act(() => result.current.advance('idea-00001-x', 'prd'))

    expect(result.current.terminalOpen).toBe(true)
    expect(result.current.shownSession?.id).toBe('s9')
  })

  it('keeps the terminal closed when the advance is refused', async () => {
    vi.spyOn(api, 'advance').mockRejectedValue(new Error('an agent session is already running'))
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))

    await act(() => result.current.advance('idea-00001-x', 'prd'))

    expect(result.current.terminalOpen).toBe(false)
    expect(toast.error).toHaveBeenCalledWith(expect.stringMatching(/already running/))
  })

  /**
   * spec-00001-AC-21.2 / spec-00003-AC-9.1 — the board reattaches to the sessions
   * that outlived the page, and comes up on the newest running one; the panel is
   * what picks another (spec-00003-FR-5).
   */
  it('opens the terminal on load on the newest running session', async () => {
    vi.spyOn(api, 'sessions').mockResolvedValue([
      listing({ id: 's1', status: 'exited' }),
      listing({ id: 's2', sourceId: 'idea-00001-x' }),
      listing({ id: 's3', sourceId: 'spec-00001-x' }),
    ])
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))

    await waitFor(() => expect(result.current.terminalOpen).toBe(true))
    expect(result.current.shownSession?.id).toBe('s3')
  })

  // With nothing running, the newest session there was is still what the terminal
  // shows — how the last one ended is worth seeing (spec-00003-FR-4).
  it('falls back to the newest ended session when none is running', async () => {
    vi.spyOn(api, 'sessions').mockResolvedValue([
      listing({ id: 's1', status: 'exited' }),
      listing({ id: 's2', sourceId: 'idea-00001-x', status: 'terminated' }),
    ])
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))

    await waitFor(() => expect(result.current.shownSession?.id).toBe('s2'))
    expect(result.current.terminalOpen).toBe(false)
  })

  /**
   * spec-00003-AC-4.3 / AC-10.1 — putting a session on the terminal is one act,
   * whether the session panel or a node marker asks for it, and it brings the
   * panel back up with it.
   */
  it('puts the asked-for session on the terminal', async () => {
    vi.spyOn(api, 'sessions').mockResolvedValue([
      listing({ id: 's1' }),
      listing({ id: 's2', sourceId: 'idea-00001-x' }),
    ])
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.shownSession?.id).toBe('s2'))

    act(() => result.current.showSession('s1'))

    expect(result.current.shownSession?.id).toBe('s1')
    expect(result.current.terminalOpen).toBe(true)
  })

  // spec-00003-AC-5.6 — the session on show is held by id across a refresh
  it('keeps the session on show when the graph is re-read', async () => {
    vi.spyOn(api, 'sessions').mockResolvedValue([
      listing({ id: 's1' }),
      listing({ id: 's2', sourceId: 'idea-00001-x' }),
    ])
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.shownSession?.id).toBe('s2'))
    act(() => result.current.showSession('s1'))

    await act(() => result.current.refresh().then(() => undefined))

    expect(result.current.shownSession?.id).toBe('s1')
  })

  /**
   * design-00002 §10 — close nearest: the session on show has gone from the
   * listing (a restarted server holds none of the old ones), so its terminal view
   * goes and nothing else does.
   */
  it('closes the terminal view when the session on show is gone', async () => {
    let held = [listing()]
    vi.spyOn(api, 'sessions').mockImplementation(async () => held)
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.terminalOpen).toBe(true))

    held = []
    await act(() => result.current.refresh().then(() => undefined))

    expect(result.current.shownSession).toBeUndefined()
    expect(result.current.terminalOpen).toBe(false)
    expect(result.current.graph.nodes).toHaveLength(2)
  })

  // spec-00001-AC-49.3 — stopping the session hands the three entries back
  it('takes the stopped session out of the way of the next one', async () => {
    // The server is the one authority on the session, and the refresh that
    // follows the stop reads it again (issue-00013) — so the stand-in has to end
    // the session too, not go on reporting the one it was asked to stop.
    let held = [listing()]
    vi.spyOn(api, 'sessions').mockImplementation(async () => held)
    const stop = vi.spyOn(api, 'stopSession').mockImplementation(async () => {
      held = [listing({ status: 'terminated' })]
      return held[0]!
    })
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.shownSession?.status).toBe('running'))

    await act(() => result.current.stopSession())

    expect(stop).toHaveBeenCalledWith('s1')
    expect(result.current.shownSession?.status).toBe('terminated')
    expect(result.current.running).toHaveLength(0)
  })

  /**
   * spec-00001-AC-49.4 — with no session on show there is no session to stop, so
   * the board asks nothing of the server.
   */
  it('stops nothing when no session is on show', async () => {
    vi.spyOn(api, 'sessions').mockResolvedValue([])
    const stop = vi.spyOn(api, 'stopSession')
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))

    await act(() => result.current.stopSession())

    expect(stop).not.toHaveBeenCalled()
    expect(result.current.shownSession).toBeUndefined()
  })

  it('keeps the session it has when the stop is refused', async () => {
    vi.spyOn(api, 'sessions').mockResolvedValue([listing()])
    vi.spyOn(api, 'stopSession').mockRejectedValue(new Error('there is no running agent session to stop'))
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.shownSession?.status).toBe('running'))

    await act(() => result.current.stopSession())

    expect(result.current.shownSession?.status).toBe('running')
    expect(toast.error).toHaveBeenCalledWith(expect.stringMatching(/no running agent session/))
  })

  it('leaves the terminal closed when the last session already exited', async () => {
    vi.spyOn(api, 'sessions').mockResolvedValue([listing({ status: 'exited' })])
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))

    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))
    expect(result.current.terminalOpen).toBe(false)
  })

  /**
   * spec-00003-AC-7.1 / AC-7.3 — an ending reaches the board as a difference
   * between two readings of the listing, and each one gets its own toast: two
   * sessions ending in the same batch are two notifications, not one.
   */
  it('announces each session that has ended since the last reading', async () => {
    const message = vi.spyOn(toast, 'message').mockImplementation(() => 'id')
    let held = [listing({ id: 's1' }), listing({ id: 's2', kind: 'ask', sourceId: 'idea-00001-x' })]
    vi.spyOn(api, 'sessions').mockImplementation(async () => held)
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.running).toHaveLength(2))
    expect(message).not.toHaveBeenCalled()

    held = [
      listing({ id: 's1', status: 'exited' }),
      listing({ id: 's2', kind: 'ask', sourceId: 'idea-00001-x', status: 'terminated' }),
    ]
    await act(() => result.current.refresh().then(() => undefined))

    expect(message).toHaveBeenCalledTimes(2)
    expect(message).toHaveBeenCalledWith('clarify · prd-00001-x', { description: 'exited' })
    expect(message).toHaveBeenCalledWith('ask · idea-00001-x', { description: 'terminated' })
  })

  /**
   * The same reading twice is not a second ending: the notification follows the
   * change of state, so a board that keeps refreshing does not keep announcing
   * (spec-00003-FR-7).
   */
  it('announces an ended session once and not again', async () => {
    const message = vi.spyOn(toast, 'message').mockImplementation(() => 'id')
    let held = [listing()]
    vi.spyOn(api, 'sessions').mockImplementation(async () => held)
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.running).toHaveLength(1))

    held = [listing({ status: 'exited' })]
    await act(() => result.current.refresh().then(() => undefined))
    await act(() => result.current.refresh().then(() => undefined))

    expect(message).toHaveBeenCalledTimes(1)
  })

  /**
   * spec-00003-AC-7.2 — a session the user stopped is announced like any other
   * ending, and the end state it is announced with is «terminated».
   */
  it('announces a session the user stopped as terminated', async () => {
    const message = vi.spyOn(toast, 'message').mockImplementation(() => 'id')
    let held = [listing()]
    vi.spyOn(api, 'sessions').mockImplementation(async () => held)
    vi.spyOn(api, 'stopSession').mockImplementation(async () => {
      held = [listing({ status: 'terminated' })]
      return held[0]!
    })
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.shownSession?.status).toBe('running'))

    await act(() => result.current.stopSession())

    expect(message).toHaveBeenCalledWith('clarify · prd-00001-x', { description: 'terminated' })
  })

  /**
   * spec-00003-AC-7.4 — the agent CLI was not there, so the session failed on the
   * spawn (spec-00001-FR-16). It never ran, and it is announced the same way: the
   * end state is «failed».
   */
  it('announces a session that failed to start', async () => {
    const message = vi.spyOn(toast, 'message').mockImplementation(() => 'id')
    let held: SessionListing[] = []
    vi.spyOn(api, 'sessions').mockImplementation(async () => held)
    const failed = listing({ status: 'failed', error: 'spawn claude ENOENT' })
    vi.spyOn(api, 'clarify').mockImplementation(async () => {
      held = [failed]
      return failed
    })
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))
    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))

    await act(() => result.current.startSession(() => api.clarify('prd-00001-x')))

    expect(message).toHaveBeenCalledWith('clarify · prd-00001-x', { description: 'failed' })
    // The terminal is on it: what went wrong is shown there (spec-00001-FR-16).
    expect(result.current.shownSession?.status).toBe('failed')
    expect(result.current.terminalOpen).toBe(true)
  })

  /**
   * A board opened after a session ended shows it as ended and announces nothing:
   * the first reading is the baseline, not news (spec-00003-AC-9.2).
   */
  it('announces nothing for a session that had already ended before it looked', async () => {
    const message = vi.spyOn(toast, 'message').mockImplementation(() => 'id')
    vi.spyOn(api, 'sessions').mockResolvedValue([listing({ status: 'exited' })])
    const { result } = renderHook(() => useBoard(GO_TO_SESSION))

    await waitFor(() => expect(result.current.sessions).toHaveLength(1))
    expect(message).not.toHaveBeenCalled()
  })
})

describe('the terminal socket', () => {
  class FakeSocket {
    static last: FakeSocket
    static readonly OPEN = 1
    readyState = 1
    sent: unknown[] = []
    closed = false
    private listeners: Record<string, (event: { data: string }) => void> = {}

    constructor(readonly url: string) {
      FakeSocket.last = this
    }

    addEventListener(type: string, listener: (event: { data: string }) => void) {
      this.listeners[type] = listener
    }

    emit(data: string) {
      this.listeners.message?.({ data })
    }

    /** The handshake completing, as the browser reports it. */
    open() {
      this.readyState = 1
      this.listeners.open?.({ data: '' })
    }

    send(data: unknown) {
      this.sent.push(data)
    }

    close() {
      this.closed = true
    }
  }

  /** A size frame read back as the pair it carries. */
  function sizeIn(frame: unknown): unknown {
    expect(typeof frame).not.toBe('string')
    return JSON.parse(new TextDecoder().decode(frame as Uint8Array))
  }

  beforeEach(() => vi.stubGlobal('WebSocket', FakeSocket))
  afterEach(() => vi.unstubAllGlobals())

  it('streams frames from the session to the terminal', () => {
    const seen: string[] = []
    connectTerminal('s1', (data) => seen.push(data))

    FakeSocket.last.emit('hello from the agent')

    expect(seen).toEqual(['hello from the agent'])
    // The channel names the session it is showing (spec-00003-FR-5).
    expect(FakeSocket.last.url).toMatch(/^ws:\/\/.*\/api\/terminal\?sessionId=s1$/)
  })

  it('forwards keystrokes while the socket is open', () => {
    const link = connectTerminal('s1', () => {})
    link.send('ping\n')
    expect(FakeSocket.last.sent).toEqual(['ping\n'])
  })

  it('drops keystrokes once the socket is gone', () => {
    const link = connectTerminal('s1', () => {})
    FakeSocket.last.readyState = 3

    link.send('ping\n')

    expect(FakeSocket.last.sent).toEqual([])
  })

  it('closes the socket on request', () => {
    connectTerminal('s1', () => {}).close()
    expect(FakeSocket.last.closed).toBe(true)
  })

  // spec-00001-AC-12.5 — a size travels as its own kind of frame, so no keystroke
  // can be read as a size and no size typed at the agent (issue-00009)
  it('sends the size as a binary frame, apart from the stdin stream', () => {
    const link = connectTerminal('s1', () => {})

    link.resize(100, 40)

    expect(FakeSocket.last.sent).toHaveLength(1)
    expect(sizeIn(FakeSocket.last.sent[0])).toEqual({ cols: 100, rows: 40 })
  })

  it('holds a size measured before the socket opened, and sends it on open', () => {
    const link = connectTerminal('s1', () => {})
    FakeSocket.last.readyState = 0

    link.resize(100, 40)
    expect(FakeSocket.last.sent).toEqual([])

    FakeSocket.last.open()

    expect(sizeIn(FakeSocket.last.sent[0])).toEqual({ cols: 100, rows: 40 })
  })

  it('sends nothing on open when no size was measured yet', () => {
    connectTerminal('s1', () => {})
    FakeSocket.last.open()

    expect(FakeSocket.last.sent).toEqual([])
  })
})
