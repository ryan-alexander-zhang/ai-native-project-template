// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MarkerType } from '@xyflow/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import { Board } from '../src/Board.tsx'
import { api } from '../src/api.ts'
import { matchDocuments, toFlowEdges, toFlowNodes } from '../src/canvasModel.ts'
import { onFlowError } from '../src/flowError.ts'

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

const IDEA = node({ id: 'idea-00001-x', type: 'idea', status: 'active', title: 'Whiteboard idea', path: 'idea/a.md' })
const GRAPH: DocGraph = {
  nodes: [node(), IDEA],
  edges: [{ from: 'prd-00001-x', to: 'idea-00001-x', relation: 'parent', ok: true }],
  issues: [],
}
const PLACED = [
  { id: 'prd-00001-x', x: 10, y: 200 },
  { id: 'idea-00001-x', x: 10, y: 0 },
]

afterEach(cleanup)

describe('toFlowNodes', () => {
  it('places each document where the layout put it', () => {
    const nodes = toFlowNodes(GRAPH, PLACED)

    expect(nodes.map((item) => item.position)).toEqual([
      { x: 10, y: 200 },
      { x: 10, y: 0 },
    ])
    expect(nodes.every((item) => item.type === 'doc')).toBe(true)
  })

  it('marks the selected document', () => {
    expect(toFlowNodes(GRAPH, PLACED, 'idea-00001-x').map((item) => item.selected)).toEqual([false, true])
  })

  it('drops an unplaced document at the origin rather than losing it', () => {
    expect(toFlowNodes(GRAPH, [])[0]!.position).toEqual({ x: 0, y: 0 })
  })
})

describe('toFlowEdges', () => {
  // The layout in these cases: prd is to the right of idea, one row each.
  const COLUMNS = [
    { id: 'idea-00001-x', x: 0, y: 0 },
    { id: 'prd-00001-x', x: 336, y: 0 },
  ]

  it('carries the relation as the edge label', () => {
    expect(toFlowEdges(GRAPH, COLUMNS)[0]).toMatchObject({
      id: 'e0',
      source: 'prd-00001-x',
      target: 'idea-00001-x',
      label: 'parent',
      className: undefined,
    })
  })

  // spec-00001-AC-1.10 — the arrow lands on the referenced document
  it('points the arrow at the document being referenced', () => {
    const edge = toFlowEdges(GRAPH, COLUMNS)[0]!

    expect(edge.target).toBe('idea-00001-x')
    expect(edge.markerEnd).toEqual({ type: MarkerType.ArrowClosed })
  })

  it('anchors a cross-column edge on the sides that face each other', () => {
    const edge = toFlowEdges(GRAPH, COLUMNS)[0]!

    // prd sits right of idea, so the edge leaves prd's left and enters idea's right.
    expect(edge.sourceHandle).toBe('source-left')
    expect(edge.targetHandle).toBe('target-right')
  })

  it('anchors the other way round when the source is the left-hand node', () => {
    const graph = { ...GRAPH, edges: [{ from: 'idea-00001-x', to: 'prd-00001-x', relation: 'informs', ok: true }] }
    const edge = toFlowEdges(graph, COLUMNS)[0]!

    expect(edge.sourceHandle).toBe('source-right')
    expect(edge.targetHandle).toBe('target-left')
  })

  // spec-00001-AC-1.11
  it('anchors a same-column edge top to bottom', () => {
    const graph: DocGraph = {
      nodes: [],
      edges: [{ from: 'spec-00002-b', to: 'spec-00001-a', relation: 'supersedes', ok: true }],
      issues: [],
    }
    const placed = [
      { id: 'spec-00001-a', x: 0, y: 0 },
      { id: 'spec-00002-b', x: 0, y: 140 },
    ]

    const edge = toFlowEdges(graph, placed)[0]!

    // spec-00002 is below, so it leaves its top and arrives at the other's bottom.
    expect(edge.sourceHandle).toBe('source-top')
    expect(edge.targetHandle).toBe('target-bottom')
  })

  it('anchors a same-column edge the other way when the source is above', () => {
    const graph: DocGraph = {
      nodes: [],
      edges: [{ from: 'spec-00001-a', to: 'spec-00002-b', relation: 'informs', ok: true }],
      issues: [],
    }
    const placed = [
      { id: 'spec-00001-a', x: 0, y: 0 },
      { id: 'spec-00002-b', x: 0, y: 140 },
    ]

    const edge = toFlowEdges(graph, placed)[0]!

    expect(edge.sourceHandle).toBe('source-bottom')
    expect(edge.targetHandle).toBe('target-top')
  })

  it('loops a document that references itself', () => {
    const graph: DocGraph = {
      nodes: [],
      edges: [{ from: 'spec-00001-a', to: 'spec-00001-a', relation: 'supersedes', ok: true }],
      issues: [],
    }
    const edge = toFlowEdges(graph, [{ id: 'spec-00001-a', x: 0, y: 0 }])[0]!

    expect(edge.sourceHandle).toBe('source-top')
    expect(edge.targetHandle).toBe('target-bottom')
  })

  // spec-00001-AC-2.2
  it('marks an edge pointing at an unknown document', () => {
    const graph = { ...GRAPH, edges: [{ from: 'prd-00001-x', to: 'ghost', relation: 'parent', ok: false }] }
    const edge = toFlowEdges(graph, COLUMNS)[0]!

    expect(edge.className).toBe('edge--broken')
    // The ghost has no position; the edge still gets usable anchors.
    expect(edge.sourceHandle).toBe('source-right')
    expect(edge.targetHandle).toBe('target-left')
  })
})

// spec-00001-FR-26
describe('matchDocuments', () => {
  // spec-00001-AC-26.1
  it('matches an id fragment', () => {
    expect(matchDocuments(GRAPH.nodes, 'idea-00001').map((n) => n.id)).toEqual(['idea-00001-x'])
  })

  // spec-00001-AC-26.2
  it('matches a title fragment', () => {
    expect(matchDocuments(GRAPH.nodes, 'Whiteboard idea').map((n) => n.id)).toEqual(['idea-00001-x'])
  })

  // spec-00001-AC-26.3
  it('ignores case', () => {
    expect(matchDocuments(GRAPH.nodes, 'IDEA-00001').map((n) => n.id)).toEqual(['idea-00001-x'])
    expect(matchDocuments(GRAPH.nodes, 'whiteboard prd').map((n) => n.id)).toEqual(['prd-00001-x'])
  })

  // spec-00001-AC-26.4 — every match, in graph order, uncapped
  it('returns every match in graph order', () => {
    const many = [node({ id: 'spec-00001-a' }), node({ id: 'spec-00002-b' }), node({ id: 'spec-00003-c' })]
    expect(matchDocuments(many, 'spec-').map((n) => n.id)).toEqual([
      'spec-00001-a',
      'spec-00002-b',
      'spec-00003-c',
    ])
  })

  // spec-00001-AC-26.5
  it('returns nothing when no document matches', () => {
    expect(matchDocuments(GRAPH.nodes, 'nothing here')).toEqual([])
  })

  it('returns every document for an empty query', () => {
    expect(matchDocuments(GRAPH.nodes, '   ')).toHaveLength(2)
  })

  // an anomalous document carries its path as its id, so it is searchable by path
  it('matches an anomalous document by its file path', () => {
    const anomalous = node({ id: 'prd/broken.md', ok: false, problems: ['front matter is missing'] })
    expect(matchDocuments([anomalous], 'broken').map((n) => n.id)).toEqual(['prd/broken.md'])
  })
})

describe('the board', () => {
  beforeEach(() => {
    vi.spyOn(api, 'graph').mockResolvedValue(GRAPH)
    vi.spyOn(api, 'transitions').mockResolvedValue(['active', 'archived'])
    vi.spyOn(api, 'nextSteps').mockResolvedValue([{ next: 'spec', carry: 'parent' }])
    vi.spyOn(api, 'session').mockResolvedValue({ current: null })
    vi.spyOn(api, 'config').mockResolvedValue({
      types: { prd: 'living', idea: 'living' },
      relations: ['parent'],
      flow: {},
      agents: [{ name: 'claude', command: 'claude', args: [] }],
    })
  })

  afterEach(() => vi.restoreAllMocks())

  it('renders the canvas with the documents on it', async () => {
    render(<Board />)

    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    expect(screen.getByTestId('node-idea-00001-x')).toBeTruthy()
    expect(screen.getByText('no issues')).toBeTruthy()
  })

  // issue-00002 / spec-00001-AC-1.1 — asserted on the DOM, not on the model:
  // toFlowEdges() was right all along while the canvas stayed empty.
  it('draws an edge for each declared relation', async () => {
    const { container } = render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())

    await waitFor(() =>
      expect(container.querySelectorAll('.react-flow__edge')).toHaveLength(GRAPH.edges.length),
    )
    expect(container.querySelectorAll('.react-flow__edge-path').length).toBeGreaterThan(0)
  })

  // issue-00002 §5 — an edge count alone cannot tell "no handle" from "not yet
  // measured": both drop the edge, so only the error id distinguishes them.
  // React Flow's own channel is silent outside a dev build, which is why the
  // board wires `onError` (flowError.ts) — without it this assertion is vacuous.
  it('reports no error through the react flow channel while drawing the graph', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})

    const { container } = render(<Board />)
    await waitFor(() => expect(container.querySelectorAll('.react-flow__edge')).toHaveLength(1))

    // `004` (container has no width/height) is a jsdom artifact — the canvas is
    // unsized here and would not be in a browser. `008` is the one that matters.
    const reported = warn.mock.calls.map((call) => String(call[0])).filter((m) => m.includes('react-flow'))
    expect(reported.filter((m) => m.includes('008'))).toEqual([])
    expect(reported.length).toBeGreaterThan(0) // the channel is live, not silent
  })

  // ...and the guard above only means something if that channel can speak:
  it('routes a react flow error to the console', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})

    onFlowError('008', "Couldn't create edge for source handle id: some-handle")

    expect(warn).toHaveBeenCalledWith(expect.stringContaining('react-flow 008'))
  })

  // spec-00001-AC-1.10 in the DOM: an arrow head at the referenced end, and
  // none at the declaring end — the model assertion alone would pass with both.
  it('draws the arrow head only at the referenced end', async () => {
    const { container } = render(<Board />)
    await waitFor(() => expect(container.querySelectorAll('.react-flow__edge-path')).toHaveLength(1))

    const path = container.querySelector('.react-flow__edge-path')!
    expect(path.getAttribute('marker-end')).toContain('arrowclosed')
    expect(path.getAttribute('marker-start')).toBeNull()
    expect(container.querySelector('.react-flow__edge')!.getAttribute('aria-label')).toBe(
      'Edge from prd-00001-x to idea-00001-x',
    )
  })

  // spec-00001-AC-1.14
  it('offers no handle to drag a new edge from', async () => {
    const { container } = render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())

    // `connectable` is only the CSS class; `connectablestart` is the one the
    // pointer-down guard reads, so it is the one that must be gone.
    expect(container.querySelectorAll('.react-flow__handle').length).toBeGreaterThan(0)
    expect(container.querySelectorAll('.react-flow__handle.connectable')).toHaveLength(0)
    expect(container.querySelectorAll('.react-flow__handle.connectablestart')).toHaveLength(0)
    expect(container.querySelectorAll('.react-flow__handle.connectableend')).toHaveLength(0)
  })

  // spec-00001-AC-1.4
  it('renders an empty canvas without error for an empty docs tree', async () => {
    vi.spyOn(api, 'graph').mockResolvedValue({ nodes: [], edges: [], issues: [] })
    const { container } = render(<Board />)

    await waitFor(() => expect(screen.getByText('no issues')).toBeTruthy())
    expect(container.querySelector('.react-flow__pane')).toBeTruthy()
    expect(container.querySelectorAll('.node-card')).toHaveLength(0)
  })

  it('counts the issues it found', async () => {
    vi.spyOn(api, 'graph').mockResolvedValue({
      ...GRAPH,
      issues: [{ path: 'prd/a.md', message: 'front matter is missing' }],
    })
    render(<Board />)

    await waitFor(() => expect(screen.getByText('1 issues')).toBeTruthy())
  })

  // spec-00001-AC-3.1
  it('opens the toolbar for the node the user clicks', async () => {
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())

    fireEvent.click(screen.getByTestId('node-prd-00001-x'))

    await waitFor(() => expect(screen.getByRole('toolbar', { name: /prd-00001-x/ })).toBeTruthy())
    expect(screen.getByRole('button', { name: 'Accept' })).toBeTruthy()
  })

  // spec-00001-AC-3.2
  it('closes the toolbar when the canvas background is clicked', async () => {
    const { container } = render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByRole('toolbar', { name: /prd-00001-x/ })).toBeTruthy())

    fireEvent.click(container.querySelector('.react-flow__pane')!)

    await waitFor(() => expect(screen.queryByRole('toolbar')).toBeNull())
  })

  // issue-00001 — the toolbar floats above the canvas and must not drive it
  it('does not pan the canvas when the toolbar is used', async () => {
    const errors: string[] = []
    const onError = (event: ErrorEvent) => errors.push(String(event.error?.message ?? event.message))
    window.addEventListener('error', onError)
    vi.spyOn(api, 'accept').mockResolvedValue({ committed: true, status: 'active' })
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Accept' })).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: 'Accept' }))

    window.removeEventListener('error', onError)
    expect(errors).toEqual([])
  })

  // spec-00001-AC-8.1 as the user sees it
  it('accepts a draft from the toolbar and refreshes', async () => {
    const accept = vi.spyOn(api, 'accept').mockResolvedValue({ committed: true, status: 'active' })
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Accept' })).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: 'Accept' }))

    expect(accept).toHaveBeenCalledWith('prd-00001-x')
    await waitFor(() => expect(api.graph).toHaveBeenCalledTimes(2))
  })

  // spec-00001-AC-8.3 as the user sees it
  it('shows the refusal when an action is rejected', async () => {
    vi.spyOn(api, 'accept').mockRejectedValue(new Error('accept applies to a draft document'))
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Accept' })).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: 'Accept' }))

    await waitFor(() => expect(screen.getByText('accept applies to a draft document')).toBeTruthy())
  })

  // spec-00001-AC-6.1 as the user sees it
  it('changes status from the toolbar', async () => {
    const setStatus = vi.spyOn(api, 'setStatus').mockResolvedValue({ committed: true, status: 'active' })
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByLabelText('Change status')).toBeTruthy())

    await userEvent.click(screen.getByLabelText('Change status'))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'active' }))

    expect(setStatus).toHaveBeenCalledWith('prd-00001-x', 'active')
  })

  // spec-00001-AC-9.1 as the user sees it
  it('records clarify questions from the toolbar', async () => {
    const clarify = vi.spyOn(api, 'clarify').mockResolvedValue({ committed: true, status: 'draft' })
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Clarify' })).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: 'Clarify' }))
    await userEvent.type(screen.getByLabelText('Open questions, one per line'), 'who owns this?')
    await userEvent.click(screen.getByRole('button', { name: 'Record questions' }))

    expect(clarify).toHaveBeenCalledWith('prd-00001-x', ['who owns this?'])
  })

  // spec-00001-AC-11.1 as the user sees it
  it('starts an advance from the toolbar and opens the terminal', async () => {
    vi.stubGlobal(
      'WebSocket',
      class {
        static readonly OPEN = 1
        readyState = 1
        addEventListener() {}
        send() {}
        close() {}
      },
    )
    const advance = vi.spyOn(api, 'advance').mockResolvedValue({
      id: 's1',
      sourceId: 'prd-00001-x',
      targetType: 'spec',
      status: 'running',
    })
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByLabelText('Advance to the next step')).toBeTruthy())

    await userEvent.click(screen.getByLabelText('Advance to the next step'))
    await userEvent.click(await screen.findByRole('menuitem', { name: /spec/ }))

    expect(advance).toHaveBeenCalledWith('prd-00001-x', 'spec')
    await waitFor(() => expect(screen.getByLabelText('Agent session')).toBeTruthy())
    vi.unstubAllGlobals()
  })

  it('opens the editor from the toolbar', async () => {
    vi.spyOn(api, 'doc').mockResolvedValue({ path: 'prd/a.md', content: '# X\n', hash: 'h' })
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))

    await waitFor(() => expect(screen.getByLabelText('Editing prd-00001-x')).toBeTruthy())
  })

  // spec-00001-AC-27.1 and AC-27.3
  it('selects the document picked in the command palette and closes it', async () => {
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-idea-00001-x')).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: /Find a document/ }))
    await userEvent.type(screen.getByPlaceholderText('Find a document by id or title'), 'idea-00001')
    await userEvent.click(await screen.findByRole('option', { name: /idea-00001-x/ }))

    await waitFor(() => expect(screen.getByRole('toolbar', { name: /idea-00001-x/ })).toBeTruthy())
    await waitFor(() => expect(screen.queryByPlaceholderText('Find a document by id or title')).toBeNull())
  })

  // spec-00001-AC-26.6
  it('says there is no match when nothing matches', async () => {
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-idea-00001-x')).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: /Find a document/ }))
    await userEvent.type(screen.getByPlaceholderText('Find a document by id or title'), 'nothing here')

    expect(await screen.findByText('no match')).toBeTruthy()
  })

  // spec-00001-AC-27.4 and AC-27.5
  it('selects nothing and stays open when the list is empty', async () => {
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-idea-00001-x')).toBeTruthy())
    await userEvent.click(screen.getByRole('button', { name: /Find a document/ }))
    await userEvent.type(screen.getByPlaceholderText('Find a document by id or title'), 'nothing here')

    await userEvent.keyboard('{Enter}')

    expect(screen.queryByRole('toolbar')).toBeNull()
    expect(screen.getByPlaceholderText('Find a document by id or title')).toBeTruthy()
  })

  it('opens the command palette with the keyboard', async () => {
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-idea-00001-x')).toBeTruthy())

    await userEvent.keyboard('{Meta>}k{/Meta}')

    expect(await screen.findByPlaceholderText('Find a document by id or title')).toBeTruthy()
  })
})
