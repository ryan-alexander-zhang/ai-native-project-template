// @vitest-environment jsdom
import { act, cleanup, render, renderHook, screen, waitFor } from '@testing-library/react'
import { ReactFlowProvider } from '@xyflow/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import { NodeCard } from '../src/NodeCard.tsx'
import { ANOMALY_TOKEN, statusColour, statusLabel } from '../src/status.ts'
import { connectTerminal } from '../src/terminalSocket.ts'
import { useBoard } from '../src/useBoard.ts'
import { toast } from 'sonner'
import { api } from '../src/api.ts'
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

const GRAPH: DocGraph = {
  nodes: [node(), node({ id: 'idea-00001-x', type: 'idea', status: 'active', path: 'idea/a.md' })],
  edges: [{ from: 'prd-00001-x', to: 'idea-00001-x', relation: 'parent', ok: true, declaredTargets: ['idea-00001-x'] }],
  issues: [],
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
})

// spec-00001-AC-1.1, AC-1.2 and AC-1.6 … AC-1.9 (decision-00002 §2)
describe('the layout', () => {
  const ORDER = ['idea', 'prd', 'spec', 'rule']

  function graphOf(...nodes: DocNode[]): DocGraph {
    return { nodes, edges: [], issues: [], diagnostics: [] }
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
    expect(layoutGraph({ nodes: [], edges: [], issues: [], diagnostics: [] }, ORDER)).toEqual([])
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
        diagnostics: [],
      },
      ORDER,
    )
    expect(placed).toHaveLength(1)
  })
})

describe('the board state', () => {
  beforeEach(() => {
    vi.spyOn(api, 'graph').mockResolvedValue(GRAPH)
    vi.spyOn(api, 'transitions').mockResolvedValue(['active', 'archived'])
    vi.spyOn(api, 'nextSteps').mockResolvedValue([{ next: 'spec', carry: 'parent' }])
    vi.spyOn(api, 'session').mockResolvedValue({ current: null })
    vi.spyOn(api, 'config').mockResolvedValue({
      types: { prd: 'living', idea: 'living' },
      relations: ['parent'],
      flow: {},
      focus: {},
      agents: [{ name: 'claude', command: 'claude', args: [] }],
    })
    vi.spyOn(toast, 'error').mockImplementation(() => 'id')
  })

  afterEach(() => vi.restoreAllMocks())

  it('loads the graph and its layout on mount', async () => {
    const { result } = renderHook(() => useBoard())

    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))
    expect(result.current.placed).toHaveLength(2)
  })

  // The column order arrives from GET /api/config; laying out before it lands
  // would put every node in the unknown-type bucket (design-00002 §2).
  it('lays out with the column order the config declares', async () => {
    const { result } = renderHook(() => useBoard())
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
    const { result } = renderHook(() => useBoard())

    await waitFor(() => expect(result.current.placed).toHaveLength(2))
    expect(toast.error).toHaveBeenCalledWith('config: no flow config at whiteboard.config.yaml')
  })

  // spec-00001-AC-1.12
  it('puts every node back where it was after a refresh', async () => {
    const { result } = renderHook(() => useBoard())
    await waitFor(() => expect(result.current.placed).toHaveLength(2))
    const before = result.current.placed

    await act(() => result.current.refresh().then(() => undefined))

    expect(result.current.placed).toEqual(before)
  })

  it('selects a node and loads what it may do', async () => {
    const { result } = renderHook(() => useBoard())
    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))

    await act(() => result.current.select('prd-00001-x'))

    expect(result.current.selectedNode!.id).toBe('prd-00001-x')
    expect(result.current.transitions).toEqual(['active', 'archived'])
    expect(result.current.nextSteps).toEqual([{ next: 'spec', carry: 'parent' }])
  })

  // spec-00001-AC-3.2
  it('drops the selection on deselect', async () => {
    const { result } = renderHook(() => useBoard())
    await act(() => result.current.select('prd-00001-x'))

    act(() => result.current.deselect())

    expect(result.current.selectedNode).toBeUndefined()
  })

  it('keeps the editor and the terminal as independent switches', async () => {
    const { result } = renderHook(() => useBoard())
    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))

    act(() => result.current.setEditing('prd-00001-x'))
    act(() => result.current.setTerminalOpen(true))

    expect(result.current.editing).toBe('prd-00001-x')
    expect(result.current.terminalOpen).toBe(true)
  })

  it('refreshes the graph after an action', async () => {
    const { result } = renderHook(() => useBoard())
    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))
    const action = vi.fn().mockResolvedValue(undefined)

    await act(() => result.current.run(action))

    expect(action).toHaveBeenCalled()
    expect(api.graph).toHaveBeenCalledTimes(2)
  })

  // spec-00001-AC-7.1 as the user sees it — the refusal reaches the user as a toast
  it('reports a refusal instead of throwing', async () => {
    const { result } = renderHook(() => useBoard())
    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))

    await act(() => result.current.run(() => Promise.reject(new Error('not a legal transition'))))

    expect(toast.error).toHaveBeenCalledWith('not a legal transition')
  })

  it('reports a non-error refusal as text', async () => {
    const { result } = renderHook(() => useBoard())
    await act(() => result.current.run(() => Promise.reject('nope')))
    expect(toast.error).toHaveBeenCalledWith('nope')
  })

  // spec-00001-AC-11.1
  it('opens the terminal when an advance starts', async () => {
    vi.spyOn(api, 'advance').mockResolvedValue({
      id: 's1',
      kind: 'advance',
      sourceId: 'idea-00001-x',
      targetType: 'prd',
      status: 'running',
    })
    const { result } = renderHook(() => useBoard())

    await act(() => result.current.advance('idea-00001-x', 'prd'))

    expect(result.current.terminalOpen).toBe(true)
  })

  it('keeps the terminal closed when the advance is refused', async () => {
    vi.spyOn(api, 'advance').mockRejectedValue(new Error('an agent session is already running'))
    const { result } = renderHook(() => useBoard())

    await act(() => result.current.advance('idea-00001-x', 'prd'))

    expect(result.current.terminalOpen).toBe(false)
    expect(toast.error).toHaveBeenCalledWith(expect.stringMatching(/already running/))
  })

  // spec-00001-AC-21.2 — the board reattaches to a session that outlived the page
  it('opens the terminal on load when a session is still running', async () => {
    vi.spyOn(api, 'session').mockResolvedValue({
      current: { id: 's1', kind: 'advance', sourceId: 'idea-00001-x', targetType: 'prd', status: 'running' },
    })
    const { result } = renderHook(() => useBoard())

    await waitFor(() => expect(result.current.terminalOpen).toBe(true))
  })

  it('leaves the terminal closed when the last session already exited', async () => {
    vi.spyOn(api, 'session').mockResolvedValue({
      current: { id: 's1', kind: 'advance', sourceId: 'idea-00001-x', targetType: 'prd', status: 'exited' },
    })
    const { result } = renderHook(() => useBoard())

    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))
    expect(result.current.terminalOpen).toBe(false)
  })
})

describe('the terminal socket', () => {
  class FakeSocket {
    static last: FakeSocket
    static readonly OPEN = 1
    readyState = 1
    sent: string[] = []
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

    send(data: string) {
      this.sent.push(data)
    }

    close() {
      this.closed = true
    }
  }

  beforeEach(() => vi.stubGlobal('WebSocket', FakeSocket))
  afterEach(() => vi.unstubAllGlobals())

  it('streams frames from the session to the terminal', () => {
    const seen: string[] = []
    connectTerminal((data) => seen.push(data))

    FakeSocket.last.emit('hello from the agent')

    expect(seen).toEqual(['hello from the agent'])
    expect(FakeSocket.last.url).toMatch(/^ws:\/\/.*\/api\/terminal$/)
  })

  it('forwards keystrokes while the socket is open', () => {
    const link = connectTerminal(() => {})
    link.send('ping\n')
    expect(FakeSocket.last.sent).toEqual(['ping\n'])
  })

  it('drops keystrokes once the socket is gone', () => {
    const link = connectTerminal(() => {})
    FakeSocket.last.readyState = 3

    link.send('ping\n')

    expect(FakeSocket.last.sent).toEqual([])
  })

  it('closes the socket on request', () => {
    connectTerminal(() => {}).close()
    expect(FakeSocket.last.closed).toBe(true)
  })
})
