// @vitest-environment jsdom
import { act, cleanup, render, renderHook, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import { NodeCard } from '../src/NodeCard.tsx'
import { ANOMALY_COLOUR, statusColour, statusLabel } from '../src/status.ts'
import { connectTerminal } from '../src/terminalSocket.ts'
import { useBoard } from '../src/useBoard.ts'
import { api } from '../src/api.ts'
import { layoutGraph } from '../src/layout.ts'

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
  edges: [{ from: 'prd-00001-x', to: 'idea-00001-x', relation: 'parent', ok: true }],
  issues: [],
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
    expect(statusColour(node({ ok: false }))).toBe(ANOMALY_COLOUR)
    expect(statusLabel(node({ ok: false }))).toBe('front matter problem')
  })

  it('paints an unknown status as a problem', () => {
    expect(statusColour(node({ status: 'review' }))).toBe(ANOMALY_COLOUR)
    expect(statusColour(node({ status: undefined }))).toBe(ANOMALY_COLOUR)
  })

  it('labels a healthy document with its status', () => {
    expect(statusLabel(node({ status: 'active' }))).toBe('active')
    expect(statusLabel(node({ status: undefined }))).toBe('')
  })
})

describe('a node on the canvas', () => {
  it('shows the type, title, id, and status', () => {
    render(<NodeCard node={node()} selected={false} />)

    expect(screen.getByText('prd')).toBeTruthy()
    expect(screen.getByText('X')).toBeTruthy()
    expect(screen.getByText('prd-00001-x')).toBeTruthy()
    expect(screen.getByText('draft')).toBeTruthy()
  })

  // spec-00001-AC-2.1
  it('shows the problems of an anomalous document', () => {
    render(<NodeCard node={node({ ok: false, problems: ['front matter is missing'] })} selected />)
    expect(screen.getByText('front matter is missing')).toBeTruthy()
  })

  it('shows a placeholder type when the front matter carries none', () => {
    render(<NodeCard node={node({ type: undefined })} selected={false} />)
    expect(screen.getByText('—')).toBeTruthy()
  })
})

// spec-00001-AC-1.1 and AC-1.2
describe('the layout', () => {
  it('places every node without overlapping', async () => {
    const placed = await layoutGraph(GRAPH)

    expect(placed.map((item) => item.id).sort()).toEqual(['idea-00001-x', 'prd-00001-x'])
    expect(placed[0]!.y).not.toBe(placed[1]!.y)
  })

  // spec-00001-AC-1.4
  it('places nothing for an empty graph', async () => {
    expect(await layoutGraph({ nodes: [], edges: [], issues: [] })).toEqual([])
  })

  // spec-00001-AC-2.2 — a broken edge must not drag its ghost target into the layout
  it('ignores edges pointing at an unknown document', async () => {
    const placed = await layoutGraph({
      nodes: [node()],
      edges: [{ from: 'prd-00001-x', to: 'idea-09999-ghost', relation: 'parent', ok: false }],
      issues: [],
    })
    expect(placed).toHaveLength(1)
  })
})

describe('the board state', () => {
  beforeEach(() => {
    vi.spyOn(api, 'graph').mockResolvedValue(GRAPH)
    vi.spyOn(api, 'transitions').mockResolvedValue(['active', 'archived'])
    vi.spyOn(api, 'nextSteps').mockResolvedValue([{ next: 'spec', carry: 'parent' }])
    vi.spyOn(api, 'session').mockResolvedValue({ current: null })
  })

  afterEach(() => vi.restoreAllMocks())

  it('loads the graph and its layout on mount', async () => {
    const { result } = renderHook(() => useBoard())

    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))
    expect(result.current.placed).toHaveLength(2)
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
  it('drops the selection and closes the panel on deselect', async () => {
    const { result } = renderHook(() => useBoard())
    await act(() => result.current.select('prd-00001-x'))
    act(() => result.current.setPanel({ kind: 'editor', docId: 'prd-00001-x' }))

    act(() => result.current.deselect())

    expect(result.current.selectedNode).toBeUndefined()
    expect(result.current.panel).toEqual({ kind: 'none' })
  })

  it('refreshes the graph after an action', async () => {
    const { result } = renderHook(() => useBoard())
    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))
    const action = vi.fn().mockResolvedValue(undefined)

    await act(() => result.current.run(action))

    expect(action).toHaveBeenCalled()
    expect(api.graph).toHaveBeenCalledTimes(2)
  })

  // spec-00001-AC-7.1 as the user sees it
  it('shows a refusal instead of throwing', async () => {
    const { result } = renderHook(() => useBoard())
    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))

    await act(() => result.current.run(() => Promise.reject(new Error('not a legal transition'))))

    expect(result.current.message).toBe('not a legal transition')
  })

  it('reports a non-error refusal as text', async () => {
    const { result } = renderHook(() => useBoard())
    await act(() => result.current.run(() => Promise.reject('nope')))
    expect(result.current.message).toBe('nope')
  })

  // spec-00001-AC-11.1
  it('opens the terminal when an advance starts', async () => {
    vi.spyOn(api, 'advance').mockResolvedValue({
      id: 's1',
      sourceId: 'idea-00001-x',
      targetType: 'prd',
      status: 'running',
    })
    const { result } = renderHook(() => useBoard())

    await act(() => result.current.advance('idea-00001-x', 'prd'))

    expect(result.current.panel).toEqual({ kind: 'terminal' })
  })

  it('keeps the terminal closed when the advance is refused', async () => {
    vi.spyOn(api, 'advance').mockRejectedValue(new Error('an agent session is already running'))
    const { result } = renderHook(() => useBoard())

    await act(() => result.current.advance('idea-00001-x', 'prd'))

    expect(result.current.panel).toEqual({ kind: 'none' })
    expect(result.current.message).toMatch(/already running/)
  })

  // spec-00001-AC-21.2 — the board reattaches to a session that outlived the page
  it('opens the terminal on load when a session is still running', async () => {
    vi.spyOn(api, 'session').mockResolvedValue({
      current: { id: 's1', sourceId: 'idea-00001-x', targetType: 'prd', status: 'running' },
    })
    const { result } = renderHook(() => useBoard())

    await waitFor(() => expect(result.current.panel).toEqual({ kind: 'terminal' }))
  })

  it('leaves the terminal closed when the last session already exited', async () => {
    vi.spyOn(api, 'session').mockResolvedValue({
      current: { id: 's1', sourceId: 'idea-00001-x', targetType: 'prd', status: 'exited' },
    })
    const { result } = renderHook(() => useBoard())

    await waitFor(() => expect(result.current.graph.nodes).toHaveLength(2))
    expect(result.current.panel).toEqual({ kind: 'none' })
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
