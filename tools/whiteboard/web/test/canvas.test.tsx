// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MarkerType } from '@xyflow/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocEdge, DocGraph, DocNode } from '../../src/docRepository.ts'
import { Board } from '../src/Board.tsx'
import { type SessionListing, api } from '../src/api.ts'
import { matchDocuments, relationsOf, suppressedNodes, toFlowEdges, toFlowNodes } from '../src/canvasModel.ts'
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

/** The terminal opens a socket the moment it mounts; a session case only needs one that answers. */
function stubWebSocket() {
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
}

/**
 * One row of `GET /api/sessions` (design-00001 §7): a clarify session running on
 * the prd. Several may run at once, so the board works off the list of them
 * (spec-00003-FR-4).
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

/** What `GET /api/sessions` is answering with; a test moves it by moving this. */
let served: SessionListing[] = []

/**
 * A start, as the server answers it: the session comes back from the POST *and*
 * is in the listing the refresh reads right after (design-00001 §7). A stand-in
 * that started a session and then denied holding it would have the board close
 * the terminal it had just opened — close nearest, design-00002 §10.
 */
function starts(session: SessionListing): SessionListing {
  served = [...served, session]
  return session
}

/** A plain document-to-document relation: what it declares is the document it lands on. */
function relationEdge(from: string, to: string, relation: string, ok = true, declaredTargets = [to]): DocEdge {
  return { from, to, relation, ok, declaredTargets }
}

const IDEA = node({ id: 'idea-00001-x', type: 'idea', status: 'active', title: 'Whiteboard idea', path: 'idea/a.md' })
const GRAPH: DocGraph = {
  nodes: [node(), IDEA],
  edges: [relationEdge('prd-00001-x', 'idea-00001-x', 'parent')],
  issues: [],
  idOwners: {},
  diagnostics: [],
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

  // spec-00001-AC-28.1 — with nothing selected an edge is dim and unlabelled.
  // (Before FR-28 this test asserted the label was always present; the label is
  // now the emphasised state's job — design-00002 §7 round 3.)
  it('draws an unselected edge dim and unlabelled', () => {
    expect(toFlowEdges(GRAPH, COLUMNS)[0]).toMatchObject({
      id: 'e0',
      source: 'prd-00001-x',
      target: 'idea-00001-x',
      label: undefined,
      className: 'edge--dim',
    })
  })

  // spec-00001-AC-29.1
  it('emphasises and labels the edges of the selected node', () => {
    const edge = toFlowEdges(GRAPH, COLUMNS, 'prd-00001-x')[0]!

    expect(edge.className).toBe('edge--emphasis')
    expect(edge.label).toBe('parent')
    expect(edge.zIndex).toBeGreaterThan(0)
  })

  // spec-00001-AC-29.2
  it('suppresses the edges that have nothing to do with the selection', () => {
    const graph: DocGraph = {
      ...GRAPH,
      edges: [...GRAPH.edges, relationEdge('other-00001-x', 'idea-00001-x', 'informs')],
    }

    const [connected, unrelated] = toFlowEdges(graph, COLUMNS, 'prd-00001-x')

    expect(connected!.className).toBe('edge--emphasis')
    expect(unrelated!.className).toBe('edge--suppressed')
    expect(unrelated!.label).toBeUndefined()
  })

  // spec-00001-AC-29.4 — changing the selection moves the emphasis with it
  it('emphasises only the newly selected node edges', () => {
    const graph: DocGraph = {
      ...GRAPH,
      edges: [...GRAPH.edges, relationEdge('other-00001-x', 'idea-00001-x', 'informs')],
    }

    const after = toFlowEdges(graph, COLUMNS, 'other-00001-x')

    expect(after[0]!.className).toBe('edge--suppressed')
    expect(after[1]!.className).toBe('edge--emphasis')
  })

  // spec-00001-AC-28.4 — one path can only carry one line
  it('merges two relations declared between the same pair', () => {
    const graph: DocGraph = {
      ...GRAPH,
      edges: [
        relationEdge('prd-00001-x', 'idea-00001-x', 'parent'),
        relationEdge('prd-00001-x', 'idea-00001-x', 'informs'),
      ],
    }

    const edges = toFlowEdges(graph, COLUMNS, 'prd-00001-x')

    expect(edges).toHaveLength(1)
    expect(edges[0]!.label).toBe('parent · informs')
  })

  it('keeps a merged pair marked when one of its relations is broken', () => {
    const graph: DocGraph = {
      ...GRAPH,
      edges: [
        relationEdge('prd-00001-x', 'idea-00001-x', 'parent'),
        relationEdge('prd-00001-x', 'idea-00001-x', 'informs', false),
      ],
    }

    expect(toFlowEdges(graph, COLUMNS)[0]!.className).toBe('edge--dim edge--broken')
  })

  // spec-00001-AC-28.3
  it('draws nothing when no document declares a relation', () => {
    expect(toFlowEdges({ ...GRAPH, edges: [] }, COLUMNS)).toEqual([])
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
    const graph = { ...GRAPH, edges: [relationEdge('idea-00001-x', 'prd-00001-x', 'informs')] }
    const edge = toFlowEdges(graph, COLUMNS)[0]!

    expect(edge.sourceHandle).toBe('source-right')
    expect(edge.targetHandle).toBe('target-left')
  })

  // spec-00001-AC-1.11
  it('anchors a same-column edge top to bottom', () => {
    const graph: DocGraph = {
      nodes: [],
      edges: [relationEdge('spec-00002-b', 'spec-00001-a', 'supersedes')],
      issues: [],
      idOwners: {},
      diagnostics: [],
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
      edges: [relationEdge('spec-00001-a', 'spec-00002-b', 'informs')],
      issues: [],
      idOwners: {},
      diagnostics: [],
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
      edges: [relationEdge('spec-00001-a', 'spec-00001-a', 'supersedes')],
      issues: [],
      idOwners: {},
      diagnostics: [],
    }
    const edge = toFlowEdges(graph, [{ id: 'spec-00001-a', x: 0, y: 0 }])[0]!

    expect(edge.sourceHandle).toBe('source-top')
    expect(edge.targetHandle).toBe('target-bottom')
  })

  // spec-00001-AC-2.2
  it('marks an edge pointing at an unknown document', () => {
    const graph = { ...GRAPH, edges: [relationEdge('prd-00001-x', 'ghost', 'parent', false)] }
    const edge = toFlowEdges(graph, COLUMNS)[0]!

    expect(edge.className).toBe('edge--dim edge--broken')
    // The ghost has no position; the edge still gets usable anchors.
    expect(edge.sourceHandle).toBe('source-right')
    expect(edge.targetHandle).toBe('target-left')
  })

  // spec-00001-AC-29.8 — anomaly and emphasis stack, they do not replace each other
  it('keeps an emphasised edge marked when it points at a ghost', () => {
    const graph = { ...GRAPH, edges: [relationEdge('prd-00001-x', 'ghost', 'parent', false)] }
    const edge = toFlowEdges(graph, COLUMNS, 'prd-00001-x')[0]!

    expect(edge.className).toBe('edge--emphasis edge--broken')
    expect(edge.label).toBe('parent')
  })
})

describe('suppressedNodes', () => {
  // spec-00001-AC-29.2 — the node half
  it('suppresses every node that does not share an edge with the selection', () => {
    const graph: DocGraph = {
      nodes: [node(), IDEA, node({ id: 'far-00001-x', path: 'far/a.md' })],
      edges: GRAPH.edges,
      issues: [],
      idOwners: {},
      diagnostics: [],
    }

    expect(suppressedNodes(graph, 'prd-00001-x')).toEqual(new Set(['far-00001-x']))
  })

  // spec-00001-AC-29.3 — deselecting restores everything
  it('suppresses nothing when there is no selection', () => {
    expect(suppressedNodes(GRAPH, undefined)).toEqual(new Set())
  })
})

// spec-00001-FR-30
describe('relationsOf', () => {
  const ORDER = ['parent', 'implements', 'informs']
  const graph: DocGraph = {
    nodes: [],
    edges: [
      relationEdge('plan-00001-x', 'spec-00001-x', 'implements'),
      relationEdge('spec-00001-x', 'prd-00001-x', 'parent'),
      relationEdge('rule-00001-x', 'spec-00001-x', 'informs'),
      relationEdge('spec-00001-x', 'ghost', 'informs', false),
    ],
    issues: [],
    idOwners: {},
    diagnostics: [],
  }

  // spec-00001-AC-30.1 and AC-30.2
  it('lists every relation with the field, the direction, and the other end', () => {
    expect(relationsOf(graph, 'spec-00001-x', ORDER)).toEqual([
      { field: 'parent', direction: 'out', otherId: 'prd-00001-x', targetId: 'prd-00001-x', ok: true },
      { field: 'informs', direction: 'out', otherId: 'ghost', targetId: 'ghost', ok: false },
      { field: 'implements', direction: 'in', otherId: 'plan-00001-x', targetId: 'plan-00001-x', ok: true },
      { field: 'informs', direction: 'in', otherId: 'rule-00001-x', targetId: 'rule-00001-x', ok: true },
    ])
  })

  // Direction is "whose front matter declares it", not "who depends on whom" —
  // the latter differs per field in this taxonomy (docs/README.md).
  it('calls a relation outgoing when this document declares it', () => {
    const list = relationsOf(graph, 'spec-00001-x', ORDER)

    expect(list.filter((item) => item.direction === 'out').map((item) => item.field)).toEqual(['parent', 'informs'])
    expect(list.filter((item) => item.direction === 'in').map((item) => item.otherId)).toEqual([
      'plan-00001-x',
      'rule-00001-x',
    ])
  })

  // spec-00001-AC-30.5
  it('lists a relation whose target does not exist, and marks it', () => {
    expect(relationsOf(graph, 'spec-00001-x', ORDER).find((item) => item.otherId === 'ghost')?.ok).toBe(false)
  })

  // spec-00001-AC-28.5 — one edge, but every id it was declared with is listed,
  // and each one leads to the document holding the item (spec-00001-AC-2.5).
  it('lists each declared item id of a merged edge, pointing at the document holding it', () => {
    const merged: DocGraph = {
      nodes: [],
      edges: [
        relationEdge('record-00003-x', 'spec-00001-x', 'verifies', true, [
          'spec-00001-FR-28',
          'spec-00001-FR-29',
          'spec-00001-FR-30',
        ]),
      ],
      issues: [],
      idOwners: {},
      diagnostics: [],
    }

    expect(relationsOf(merged, 'record-00003-x', ORDER)).toEqual([
      { field: 'verifies', direction: 'out', otherId: 'spec-00001-FR-28', targetId: 'spec-00001-x', ok: true },
      { field: 'verifies', direction: 'out', otherId: 'spec-00001-FR-29', targetId: 'spec-00001-x', ok: true },
      { field: 'verifies', direction: 'out', otherId: 'spec-00001-FR-30', targetId: 'spec-00001-x', ok: true },
    ])
    // …and the spec's own list still reads as one relation from that record.
    expect(relationsOf(merged, 'spec-00001-x', ORDER)).toEqual([
      { field: 'verifies', direction: 'in', otherId: 'record-00003-x', targetId: 'record-00003-x', ok: true },
    ])
  })

  // spec-00001-AC-30.4
  it('returns nothing for a document with no relations at all', () => {
    expect(relationsOf(graph, 'lonely-00001-x', ORDER)).toEqual([])
  })

  // FR-30's third ordering rule: same direction, same field, then id ascending
  it('orders two relations of the same field by the other id', () => {
    const same: DocGraph = {
      nodes: [],
      edges: [
        relationEdge('plan-00001-x', 'spec-00002-b', 'implements'),
        relationEdge('plan-00001-x', 'spec-00001-a', 'implements'),
      ],
      issues: [],
      idOwners: {},
      diagnostics: [],
    }
    expect(relationsOf(same, 'plan-00001-x', ORDER).map((item) => item.otherId)).toEqual([
      'spec-00001-a',
      'spec-00002-b',
    ])
  })

  it('puts an unknown field after every declared one', () => {
    const extra: DocGraph = {
      nodes: [],
      edges: [
        relationEdge('a-00001-x', 'z-00001-x', 'mystery'),
        relationEdge('a-00001-x', 'b-00001-x', 'parent'),
      ],
      issues: [],
      idOwners: {},
      diagnostics: [],
    }
    expect(relationsOf(extra, 'a-00001-x', ORDER).map((item) => item.field)).toEqual(['parent', 'mystery'])
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

  /**
   * The two sides of an id collision (spec-00002-FR-8): each is keyed by its own
   * path, and both carry the id they collided on, so the palette reaches them
   * either way — one by path, both by the id.
   */
  describe('a document that collides on its id', () => {
    const clashing = [
      node({ id: 'spec/first.md', path: 'spec/first.md', duplicateOf: 'spec-00002-clash', ok: false, title: 'The first' }),
      node({ id: 'spec/second.md', path: 'spec/second.md', duplicateOf: 'spec-00002-clash', ok: false, title: 'The second' }),
    ]

    // spec-00002-AC-8.4 — both come up, each still going to its own node
    it('matches every file declaring the colliding id', () => {
      expect(matchDocuments(clashing, 'spec-00002-clash').map((n) => n.id)).toEqual([
        'spec/first.md',
        'spec/second.md',
      ])
    })

    // spec-00002-AC-8.5
    it('matches only the one file a path fragment names', () => {
      expect(matchDocuments(clashing, 'second').map((n) => n.id)).toEqual(['spec/second.md'])
    })
  })
})

describe('the board', () => {
  beforeEach(() => {
    vi.spyOn(api, 'graph').mockResolvedValue(GRAPH)
    vi.spyOn(api, 'transitions').mockResolvedValue(['active', 'archived'])
    vi.spyOn(api, 'nextSteps').mockResolvedValue([{ next: 'spec', carry: 'parent' }])
    served = []
    vi.spyOn(api, 'sessions').mockImplementation(async () => served)
    // Selecting a spec or a rule reads its items for the inspector panel
    // (spec-00001-FR-31); the cases below that reach one do not care what it says.
    vi.spyOn(api, 'items').mockResolvedValue({ items: [], diagnostics: [] })
    // The clarifiable and auditable sets come down with the config and are the
    // only word on which entries are drawn (spec-00001-FR-56): prd may be
    // clarified, idea is left out of the set on purpose.
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
  })

  afterEach(() => vi.restoreAllMocks())

  it('renders the canvas with the documents on it', async () => {
    render(<Board />)

    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    expect(screen.getByTestId('node-idea-00001-x')).toBeTruthy()
    expect(screen.getByText('no issues')).toBeTruthy()
  })

  // issue-00024 — positions come from the layout (spec-00001-AC-1.2), so the
  // drag gesture has nowhere to land and only ever swallowed clicks on the
  // controls inside a node. React Flow marks a draggable node with the class.
  it('a document node is not draggable', async () => {
    render(<Board />)

    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    const wrapper = screen.getByTestId('node-prd-00001-x').closest('.react-flow__node')

    expect(wrapper).toBeTruthy()
    expect(wrapper!.classList.contains('draggable')).toBe(false)
  })

  // issue-00024 — the gesture itself, on the anomaly badge inside a node: press,
  // move, release. While the node was draggable, d3-drag took the press and, on
  // a release that had moved, swallowed the click that followed.
  it('a press that moves on a node control still opens what it opens', async () => {
    const bad = node({ id: 'spec-00002-bad', type: 'spec', path: 'spec/b.md', ok: false, problems: ['no status'] })
    vi.spyOn(api, 'graph').mockResolvedValue({ ...GRAPH, nodes: [bad], edges: [] })

    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-spec-00002-bad')).toBeTruthy())
    const badge = screen.getByLabelText('Front matter problems of spec-00002-bad')

    fireEvent.mouseDown(badge, { clientX: 0, clientY: 0 })
    fireEvent.mouseMove(document, { clientX: 12, clientY: 12 })
    fireEvent.mouseUp(document, { clientX: 12, clientY: 12 })
    fireEvent.click(badge, { clientX: 12, clientY: 12 })

    await waitFor(() => expect(screen.getByText('no status')).toBeTruthy())
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

  // spec-00001-AC-28.1 in the DOM: every edge is drawn, none carries a label
  it('shows no edge label until a node is selected', async () => {
    const { container } = render(<Board />)
    await waitFor(() => expect(container.querySelectorAll('.react-flow__edge')).toHaveLength(1))

    expect(container.querySelectorAll('.react-flow__edge.edge--dim')).toHaveLength(1)
    expect(container.querySelectorAll('.react-flow__edge-text')).toHaveLength(0)
  })

  // spec-00001-AC-29.1 and AC-29.3 as the user sees them
  it('labels the selected node edges and drops the labels again on deselect', async () => {
    const { container } = render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())

    fireEvent.click(screen.getByTestId('node-prd-00001-x'))

    await waitFor(() => expect(container.querySelectorAll('.react-flow__edge.edge--emphasis')).toHaveLength(1))
    expect(screen.getByText('parent')).toBeTruthy()

    fireEvent.click(container.querySelector('.react-flow__pane')!)

    await waitFor(() => expect(container.querySelectorAll('.react-flow__edge.edge--dim')).toHaveLength(1))
    expect(screen.queryByText('parent')).toBeNull()
  })

  // spec-00001-AC-29.6 — a refresh must not silently drop the emphasis
  it('keeps the emphasis through a refresh', async () => {
    vi.spyOn(api, 'accept').mockResolvedValue({ committed: true, status: 'active' })
    const { container } = render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Accept' })).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: 'Accept' }))
    await waitFor(() => expect(api.graph).toHaveBeenCalledTimes(2))

    expect(container.querySelectorAll('.react-flow__edge.edge--emphasis')).toHaveLength(1)
  })

  // spec-00001-AC-30.1 … AC-30.3 as the user sees them
  it('lists the relations of the selected node and jumps to the one picked', async () => {
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByLabelText('Relations')).toBeTruthy())

    await userEvent.click(screen.getByLabelText('Relations'))

    // The label is on the canvas edge too now, so scope the query to the list.
    const list = await screen.findByRole('list', { name: 'Relations of prd-00001-x' })
    expect(within(list).getByText('parent')).toBeTruthy()
    await userEvent.click(within(list).getByText('idea-00001-x'))

    await waitFor(() => expect(screen.getByRole('toolbar', { name: /idea-00001-x/ })).toBeTruthy())
  })

  // spec-00001-AC-2.5 — the fine-grained reference lands on the document holding
  // the item, drawn as an ordinary edge; picking it in the list goes there.
  it('draws an edge naming requirement items to the document holding them, unmarked', async () => {
    const spec = node({ id: 'spec-00001-x', type: 'spec', title: 'Spec', path: 'spec/a.md' })
    const record = node({ id: 'record-00003-x', type: 'record', title: 'Record', path: 'record/a.md' })
    vi.spyOn(api, 'graph').mockResolvedValue({
      nodes: [spec, record],
      edges: [
        relationEdge('record-00003-x', 'spec-00001-x', 'verifies', true, ['spec-00001-FR-28', 'spec-00001-FR-29']),
      ],
      issues: [],
      idOwners: {},
      diagnostics: [],
    })
    const { container } = render(<Board />)
    await waitFor(() => expect(container.querySelectorAll('.react-flow__edge')).toHaveLength(1))

    const drawn = container.querySelector('.react-flow__edge')!
    expect(drawn.getAttribute('aria-label')).toBe('Edge from record-00003-x to spec-00001-x')
    expect(drawn.getAttribute('class')).not.toContain('edge--broken')

    fireEvent.click(screen.getByTestId('node-record-00003-x'))
    await waitFor(() => expect(screen.getByLabelText('Relations')).toBeTruthy())
    await userEvent.click(screen.getByLabelText('Relations'))
    const list = await screen.findByRole('list', { name: 'Relations of record-00003-x' })
    expect(within(list).getByText('spec-00001-FR-28')).toBeTruthy()

    await userEvent.click(within(list).getByText('spec-00001-FR-29'))
    await waitFor(() => expect(screen.getByRole('toolbar', { name: /spec-00001-x/ })).toBeTruthy())
  })

  // issue-00005 — the list offers broken links on purpose; going to one is a
  // different matter, and it used to take the toolbar down with it.
  it('refuses to jump to a relation whose document does not exist', async () => {
    const rejections: unknown[] = []
    const onRejection = (event: PromiseRejectionEvent) => rejections.push(event.reason)
    window.addEventListener('unhandledrejection', onRejection)
    vi.spyOn(api, 'graph').mockResolvedValue({
      nodes: [node()],
      edges: [relationEdge('prd-00001-x', 'idea-09999-ghost', 'parent', false)],
      issues: [],
      idOwners: {},
      diagnostics: [],
    })
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByLabelText('Relations')).toBeTruthy())
    await userEvent.click(screen.getByLabelText('Relations'))
    const list = await screen.findByRole('list', { name: /Relations of/ })

    // Listed and marked, but not something you can travel to.
    expect(within(list).getByText('idea-09999-ghost')).toBeTruthy()
    expect(within(list).queryByRole('button', { name: /idea-09999-ghost/ })).toBeNull()

    window.removeEventListener('unhandledrejection', onRejection)
    expect(screen.getByRole('toolbar', { name: /prd-00001-x/ })).toBeTruthy()
    expect(rejections).toEqual([])
  })

  // spec-00001-AC-29.2 through the board, not just the model: an unrelated node
  // must actually receive the class. (With the two-node fixture nothing is ever
  // unrelated, so this needs a third document.)
  it('recedes the nodes that have nothing to do with the selection', async () => {
    vi.spyOn(api, 'graph').mockResolvedValue({
      ...GRAPH,
      nodes: [...GRAPH.nodes, node({ id: 'rule-00001-x', type: 'rule', title: 'Rule', path: 'rule/a.md' })],
    })
    const { container } = render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-rule-00001-x')).toBeTruthy())
    expect(container.querySelectorAll('.node--suppressed')).toHaveLength(0)

    fireEvent.click(screen.getByTestId('node-prd-00001-x'))

    await waitFor(() => expect(container.querySelectorAll('.node--suppressed')).toHaveLength(1))
    expect(screen.getByTestId('node-rule-00001-x').className).toContain('node--suppressed')
  })

  // spec-00001-AC-28.1 with more than one edge — the AC says "若干相互引用的文档"
  it('draws every edge dim and unlabelled on a graph with several', async () => {
    vi.spyOn(api, 'graph').mockResolvedValue({
      ...GRAPH,
      nodes: [...GRAPH.nodes, node({ id: 'rule-00001-x', type: 'rule', title: 'Rule', path: 'rule/a.md' })],
      edges: [
        ...GRAPH.edges,
        relationEdge('rule-00001-x', 'idea-00001-x', 'informs'),
      ],
    })
    const { container } = render(<Board />)

    await waitFor(() => expect(container.querySelectorAll('.react-flow__edge.edge--dim')).toHaveLength(2))
    expect(container.querySelectorAll('.react-flow__edge-text')).toHaveLength(0)
  })

  // spec-00001-AC-29.5 — a node with no edges at all
  it('emphasises nothing when the selected document has no relations', async () => {
    vi.spyOn(api, 'graph').mockResolvedValue({ nodes: [node()], edges: [], issues: [], diagnostics: [], idOwners: {} })
    const { container } = render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())

    fireEvent.click(screen.getByTestId('node-prd-00001-x'))

    await waitFor(() => expect(screen.getByRole('toolbar', { name: /prd-00001-x/ })).toBeTruthy())
    expect(container.querySelectorAll('.react-flow__edge')).toHaveLength(0)
    expect(container.querySelectorAll('.react-flow__edge-text')).toHaveLength(0)
  })

  // spec-00001-AC-29.7 — emphasis follows the selection, wherever it came from
  it('emphasises the edges of a document chosen in the command palette', async () => {
    const { container } = render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-idea-00001-x')).toBeTruthy())
    expect(container.querySelectorAll('.react-flow__edge.edge--dim')).toHaveLength(1)

    await userEvent.click(screen.getByRole('button', { name: /Find a document/ }))
    await userEvent.type(screen.getByPlaceholderText('Find a document by id or title'), 'idea-00001')
    await userEvent.click(await screen.findByRole('option', { name: /idea-00001-x/ }))

    await waitFor(() => expect(container.querySelectorAll('.react-flow__edge.edge--emphasis')).toHaveLength(1))
    expect(screen.getByText('parent')).toBeTruthy()
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
    vi.spyOn(api, 'graph').mockResolvedValue({ nodes: [], edges: [], issues: [], diagnostics: [], idOwners: {} })
    const { container } = render(<Board />)

    await waitFor(() => expect(screen.getByText('no issues')).toBeTruthy())
    expect(container.querySelector('.react-flow__pane')).toBeTruthy()
    expect(container.querySelectorAll('.node-card')).toHaveLength(0)
  })

  it('counts the issues it found', async () => {
    vi.spyOn(api, 'graph').mockResolvedValue({
      ...GRAPH,
      issues: [{ path: 'prd/a.md', nodeId: 'prd-00001-x', message: 'front matter is missing' }],
      diagnostics: [],
    })
    render(<Board />)

    await waitFor(() => expect(screen.getByText('1 issues')).toBeTruthy())
  })

  // spec-00001-AC-40.3 — two counts side by side, neither folded into the other
  it('counts the parse diagnostics apart from the anomalies', async () => {
    vi.spyOn(api, 'graph').mockResolvedValue({
      ...GRAPH,
      issues: [{ path: 'prd/a.md', nodeId: 'prd-00001-x', message: 'front matter is missing' }],
      diagnostics: [
        { docId: 'spec-00001-x', kind: 'item-shape', line: 4, text: '**spec-00001-FR-2** drifted' },
        { docId: 'spec-00001-x', kind: 'checklist-row', recordId: 'record-00001-x', line: 9, text: '| a … b |' },
      ],
    })
    render(<Board />)

    await waitFor(() => expect(screen.getByText('2 diagnostics')).toBeTruthy())
    expect(screen.getByText('1 issues')).toBeTruthy()
  })

  // spec-00001-AC-40.4 and AC-40.5 — a diagnostic marks no node, and zero shows nothing
  it('renders no diagnostics badge and no anomalous node when the tree follows the grammar', async () => {
    vi.spyOn(api, 'graph').mockResolvedValue({ ...GRAPH, issues: [], diagnostics: [], idOwners: {} })
    render(<Board />)

    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    expect(screen.getByText('no issues')).toBeTruthy()
    expect(screen.queryByText(/diagnostics/)).toBeNull()
    expect(screen.queryByLabelText(/^Front matter problems of/)).toBeNull()
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

  // spec-00001-AC-9.1 as the user sees it: one press, and the questioning happens
  // in the terminal.
  it('starts a clarify session from the toolbar and opens the terminal', async () => {
    stubWebSocket()
    const clarify = vi
      .spyOn(api, 'clarify')
      .mockImplementation(async () => starts(listing({ kind: 'clarify', sourceId: 'prd-00001-x' })))
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Clarify' })).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: 'Clarify' }))

    // One agent in the config, so none is named and the server takes the first
    // (spec-00001-AC-55.4).
    expect(clarify).toHaveBeenCalledWith('prd-00001-x', undefined)
    await waitFor(() => expect(screen.getByLabelText('Agent session')).toBeTruthy())
    vi.unstubAllGlobals()
  })

  // spec-00001-AC-9.3 as the user sees it — idea carries no focus line in the
  // config above, so the entry is not on its toolbar at all.
  it('offers no clarify entry for a type the config gives no focus line', async () => {
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-idea-00001-x')).toBeTruthy())

    fireEvent.click(screen.getByTestId('node-idea-00001-x'))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy())
    expect(screen.queryByRole('button', { name: 'Clarify' })).toBeNull()
  })

  // spec-00001-AC-50.1 as the user sees it — one press on a draft spec, and the
  // auditing happens in the terminal
  it('starts an audit session from the toolbar and opens the terminal', async () => {
    stubWebSocket()
    const spec = node({ id: 'spec-00001-x', type: 'spec', title: 'Whiteboard spec', path: 'spec/a.md' })
    vi.spyOn(api, 'graph').mockResolvedValue({ nodes: [spec], edges: [], issues: [], diagnostics: [], idOwners: {} })
    const audit = vi
      .spyOn(api, 'audit')
      .mockImplementation(async () => starts(listing({ kind: 'audit', sourceId: 'spec-00001-x' })))
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-spec-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Audit' })).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: 'Audit' }))

    expect(audit).toHaveBeenCalledWith('spec-00001-x', undefined)
    await waitFor(() => expect(screen.getByLabelText('Agent session')).toBeTruthy())
    vi.unstubAllGlobals()
  })

  // spec-00001-AC-51.2 as the user sees it — the entry is gone once the spec is
  // past `draft`, while the rest of the toolbar stays
  it('offers no audit entry on a spec that is no longer a draft', async () => {
    const spec = node({ id: 'spec-00001-x', type: 'spec', status: 'active', title: 'S', path: 'spec/a.md' })
    vi.spyOn(api, 'graph').mockResolvedValue({ nodes: [spec], edges: [], issues: [], diagnostics: [], idOwners: {} })
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-spec-00001-x')).toBeTruthy())

    fireEvent.click(screen.getByTestId('node-spec-00001-x'))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy())
    expect(screen.queryByRole('button', { name: 'Audit' })).toBeNull()
  })

  // spec-00003-AC-2.4 at the board: this document has a terminal-form session
  // running, so its own entries offer to start none. Asking is not among them —
  // it holds no document (spec-00005-FR-6, asserted in ask.test.tsx).
  it('disables the session entries while a session is running', async () => {
    stubWebSocket()
    vi.spyOn(api, 'sessions').mockResolvedValue([listing()])
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())

    fireEvent.click(screen.getByTestId('node-prd-00001-x'))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Clarify' })).toBeTruthy())
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Clarify' }).disabled).toBe(true)
    expect(screen.getByLabelText<HTMLButtonElement>('Advance to the next step').disabled).toBe(true)
    vi.unstubAllGlobals()
  })

  /**
   * spec-00001-AC-49.8 (sixteenth round): the stop lives in the terminal panel, so
   * a panel that has been put away must be gettable back — otherwise closing it
   * strands the user with a locked board and no way to unlock it (issue-00010).
   * The way back is the resident session panel entry now, not a conditional
   * reopen button: the panel lists the running session and picking it puts the
   * terminal — and with it the stop — back (spec-00003-FR-4).
   */
  it('reopens the terminal through the session panel once the panel is put away', async () => {
    stubWebSocket()
    vi.spyOn(api, 'sessions').mockResolvedValue([listing()])
    render(<Board />)
    await waitFor(() => expect(screen.getByLabelText('Agent session')).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(screen.queryByLabelText('Agent session')).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'Open the session panel' }))
    const rows = await screen.findByRole('list', { name: 'Agent sessions' })
    // The row itself: a running session's row carries its own stop beside it
    // (spec-00005-FR-7).
    await userEvent.click(within(rows).getAllByRole('button')[0]!)

    await waitFor(() => expect(screen.getByLabelText('Agent session')).toBeTruthy())
    expect(screen.getByRole('button', { name: 'Stop the agent session' })).toBeTruthy()
    vi.unstubAllGlobals()
  })

  // spec-00003-AC-4.2 / AC-4.5 — the entry is resident: nothing running still
  // reads «0 of 3», and the panel still opens (on an empty state).
  it('keeps the session panel entry in the top bar with nothing running', async () => {
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())

    const entry = screen.getByRole('button', { name: 'Open the session panel' })
    expect(entry.textContent).toContain('0/3')
    await userEvent.click(entry)

    expect(await screen.findByText('no sessions since the board came up')).toBeTruthy()
    expect(screen.queryByRole('list', { name: 'Agent sessions' })).toBeNull()
  })

  /**
   * spec-00001-AC-49.3 as the user sees it: the whole way out of a stuck board —
   * stop it in the terminal panel, and the entry that was locked comes back
   * (issue-00010).
   */
  it('stops the session from the terminal panel and hands the entries back', async () => {
    stubWebSocket()
    // The stop is followed by a refresh, which reads the session again
    // (issue-00013): a stand-in that kept answering «running» would put the
    // session the user just ended straight back on the board.
    let held = [listing()]
    vi.spyOn(api, 'sessions').mockImplementation(async () => held)
    const stop = vi.spyOn(api, 'stopSession').mockImplementation(async () => {
      held = [listing({ status: 'terminated' })]
      return held[0]!
    })
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() =>
      expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Clarify' }).disabled).toBe(true),
    )

    await userEvent.click(screen.getByRole('button', { name: 'Stop the agent session' }))

    expect(stop).toHaveBeenCalled()
    await waitFor(() =>
      expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Clarify' }).disabled).toBe(false),
    )
    vi.unstubAllGlobals()
  })

  // spec-00001-AC-11.1 as the user sees it
  it('starts an advance from the toolbar and opens the terminal', async () => {
    stubWebSocket()
    const advance = vi
      .spyOn(api, 'advance')
      .mockImplementation(async () => starts(listing({ kind: 'advance', targetType: 'spec' })))
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByLabelText('Advance to the next step')).toBeTruthy())

    await userEvent.click(screen.getByLabelText('Advance to the next step'))
    await userEvent.click(await screen.findByRole('menuitem', { name: /spec/ }))

    expect(advance).toHaveBeenCalledWith('prd-00001-x', 'spec', undefined)
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

/**
 * spec-00008-FR-7: where in the whole graph the viewport is. The blocks take
 * their colour from a class per status, so the theme's tokens carry it
 * (design-00002 §17.4); the classes are what a test can read, the fill is CSS.
 */
describe('the minimap', () => {
  const SPEC = node({ id: 'spec-00001-x', type: 'spec', status: 'active', title: 'Whiteboard spec', path: 'spec/a.md' })
  const BAD = node({
    id: 'spec-00002-bad',
    type: 'spec',
    status: 'nope',
    title: 'Governance spec',
    path: 'spec/bad.md',
    ok: false,
    problems: ['status "nope" is not a status of a living document'],
  })
  const STATUSES: DocGraph = {
    nodes: [node(), IDEA, SPEC, BAD],
    edges: [],
    issues: [],
    idOwners: {},
    diagnostics: [],
  }
  const ITEMS = {
    items: [
      {
        id: 'spec-00001-FR-1',
        text: 'what FR-1 asks of the system',
        criteria: [{ id: 'spec-00001-AC-1.1', text: 'Given a board When it loads Then it works', rows: [] }],
        rows: [],
        coverage: 'uncovered' as const,
      },
    ],
    diagnostics: [],
  }

  function serve(served: DocGraph) {
    vi.spyOn(api, 'graph').mockResolvedValue(served)
    vi.spyOn(api, 'items').mockResolvedValue(ITEMS)
    vi.spyOn(api, 'transitions').mockResolvedValue([])
    vi.spyOn(api, 'nextSteps').mockResolvedValue([])
    vi.spyOn(api, 'sessions').mockResolvedValue([])
    vi.spyOn(api, 'config').mockResolvedValue({
      types: { prd: 'living', idea: 'living', spec: 'living' },
      relations: [],
      flow: {},
      focus: {},
      agents: [{ name: 'claude', headless: false, source: 'project' }],
      entry: [],
      carries: {},
      maxSessions: 3,
      clarifiable: [],
      auditable: [],
    })
  }

  const blocks = (container: HTMLElement) => Array.from(container.querySelectorAll('.react-flow__minimap-node'))

  afterEach(() => vi.restoreAllMocks())

  // spec-00008-AC-7.1
  it('draws a block per node, coloured by its status and by the anomaly colour', async () => {
    serve(STATUSES)
    const { container } = render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-spec-00002-bad')).toBeTruthy())

    await waitFor(() => expect(blocks(container)).toHaveLength(4))
    expect(blocks(container).map((block) => block.getAttribute('class'))).toEqual([
      'react-flow__minimap-node minimap-status-draft',
      'react-flow__minimap-node minimap-status-active',
      'react-flow__minimap-node minimap-status-active',
      'react-flow__minimap-node minimap-anomaly',
    ])
    expect(container.querySelector('.react-flow__minimap-mask')).toBeTruthy()
  })

  // spec-00008-AC-7.2
  it('shows the sub-canvas’s nodes once the board has drilled into one', async () => {
    serve(STATUSES)
    const { container } = render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-spec-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    await waitFor(() => expect(screen.getByLabelText('Requirements of spec-00001-x')).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: /Expand as sub-canvas/ }))

    await waitFor(() => expect(screen.getByTestId('sub-item-spec-00001-FR-1')).toBeTruthy())
    // The item and its one criterion, and no status to colour either by.
    await waitFor(() => expect(blocks(container)).toHaveLength(2))
    expect(blocks(container).map((block) => block.getAttribute('class'))).toEqual([
      'react-flow__minimap-node',
      'react-flow__minimap-node',
    ])
  })

  // spec-00008-AC-7.3
  it('is still drawn, and empty, when no document is on the board', async () => {
    serve({ nodes: [], edges: [], issues: [], idOwners: {}, diagnostics: [] })
    const { container } = render(<Board />)

    await waitFor(() => expect(screen.getByText('no documents under docs/ yet')).toBeTruthy())
    expect(container.querySelector('.react-flow__minimap')).toBeTruthy()
    expect(blocks(container)).toEqual([])
  })
})

/**
 * The directory groups on the canvas (spec-00010-FR-5 … FR-7, FR-10). Every
 * fixture here has `docs/`-relative paths three segments deep, which is what
 * makes a group at all (spec-00010-FR-4).
 */
describe('a directory group on the canvas', () => {
  const DESIGN = node({ id: 'design-00002-ui', type: 'design', status: 'active', title: 'Board UI', path: 'design/design-00002-ui.md' })
  const TOP = node({ id: 'reference-00001-a', type: 'reference', status: 'active', title: 'A top-level reference', path: 'reference/a.md' })

  function reference(id: string, path: string, overrides: Partial<DocNode> = {}): DocNode {
    return node({ id, type: 'reference', status: 'active', title: id, path, ...overrides })
  }

  const STRIPE_ONE = reference('reference-00011-s', 'reference/stripe/one.md')
  const STRIPE_TWO = reference('reference-00012-t', 'reference/stripe/two.md')
  const STRIPE_THREE = reference('reference-00013-u', 'reference/stripe/three.md')
  const CCBILL_ONE = reference('reference-00021-c', 'reference/ccbill/one.md')
  const CCBILL_TWO = reference('reference-00022-d', 'reference/ccbill/two.md')

  /** The card of the collapsed or expanded group, and the row that toggles it. */
  const STRIPE_CARD = 'group-reference-reference/stripe'
  const CCBILL_CARD = 'group-reference-reference/ccbill'
  const STRIPE_ROW = 'stripe, 3 documents'

  const GROUPED: DocGraph = {
    nodes: [DESIGN, TOP, STRIPE_ONE, STRIPE_TWO, STRIPE_THREE, CCBILL_ONE, CCBILL_TWO],
    edges: [relationEdge('design-00002-ui', 'reference-00012-t', 'informs')],
    issues: [],
    idOwners: {},
    diagnostics: [],
  }

  function serve(graph: DocGraph = GROUPED) {
    vi.spyOn(api, 'graph').mockResolvedValue(graph)
    vi.spyOn(api, 'transitions').mockResolvedValue([])
    vi.spyOn(api, 'nextSteps').mockResolvedValue([])
    vi.spyOn(api, 'items').mockResolvedValue({ items: [], diagnostics: [] })
    vi.spyOn(api, 'sessions').mockImplementation(async () => served)
    vi.spyOn(api, 'config').mockResolvedValue({
      types: { design: 'living', spec: 'living', reference: 'living' },
      relations: ['informs'],
      flow: {},
      focus: {},
      agents: [{ name: 'claude', headless: false, source: 'project' }],
      entry: [],
      carries: {},
      maxSessions: 3,
      clarifiable: [],
      auditable: [],
    })
  }

  /** The board, open, with every group in the state the browser remembers. */
  async function openBoard(graph: DocGraph = GROUPED) {
    serve(graph)
    const rendered = render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-design-00002-ui')).toBeTruthy())
    return rendered
  }

  beforeEach(() => {
    served = []
    // Collapsed by default is the state with no key at all (spec-00010-AC-6.5);
    // an expanded set left by another case would be this one's starting point.
    localStorage.removeItem('whiteboard-directory-groups-expanded')
  })

  afterEach(() => vi.restoreAllMocks())

  // spec-00010-AC-5.1
  it('stands one group node in for the three documents it holds', async () => {
    await openBoard()

    expect(screen.getByRole('button', { name: STRIPE_ROW })).toBeTruthy()
    expect(screen.queryByTestId('node-reference-00011-s')).toBeNull()
    expect(screen.queryByTestId('node-reference-00012-t')).toBeNull()
    expect(screen.queryByTestId('node-reference-00013-u')).toBeNull()
    // The top-level document of the same column is still a node of its own.
    expect(screen.getByTestId('node-reference-00001-a')).toBeTruthy()
  })

  // spec-00010-AC-5.2 — the group carries a marker; the counts stay per document
  it('marks a group holding an anomalous document without changing the counts', async () => {
    const broken = reference('reference/stripe/bad.md', 'reference/stripe/bad.md', {
      ok: false,
      problems: ['no status'],
    })
    const elsewhere = node({ id: 'design/bad.md', type: 'design', path: 'design/bad.md', ok: false, problems: ['no id'] })
    await openBoard({
      ...GROUPED,
      nodes: [...GROUPED.nodes, broken, elsewhere],
      issues: [
        { path: 'reference/stripe/bad.md', nodeId: 'reference/stripe/bad.md', message: 'no status' },
        { path: 'design/bad.md', nodeId: 'design/bad.md', message: 'no id' },
      ],
    })

    expect(screen.getByLabelText('1 document with problems')).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: 'Open the anomaly list' }))
    const list = await screen.findByRole('dialog')
    expect(within(list).getByText('reference/stripe/bad.md')).toBeTruthy()
    expect(within(list).getByText('design/bad.md')).toBeTruthy()
    expect(screen.getByText('2 issues')).toBeTruthy()
  })

  // spec-00010-AC-5.5 — one member is being worked on, so the group says so
  it('carries a session marker for a session running on a member', async () => {
    stubWebSocket()
    served = [listing({ sourceId: 'reference-00012-t' })]
    await openBoard()

    expect(screen.getByLabelText('Running session in stripe')).toBeTruthy()
  })

  // spec-00010-AC-5.12 — one member running, another waiting: the group waits
  it('reads as waiting when any member’s session awaits input', async () => {
    stubWebSocket()
    served = [
      listing({ sourceId: 'reference-00012-t' }),
      listing({ id: 's2', kind: 'ask', sourceId: 'reference-00013-u', awaiting: true }),
    ]
    await openBoard()

    expect(screen.getByLabelText('Awaiting input session in stripe')).toBeTruthy()
    expect(screen.queryByLabelText('Running session in stripe')).toBeNull()
  })

  // spec-00010-AC-5.6 — a group is not a document: clicking one selects nothing
  it('leaves the selection and the toolbar alone when the group node is clicked', async () => {
    await openBoard()
    fireEvent.click(screen.getByTestId('node-design-00002-ui'))
    await waitFor(() => expect(screen.getByRole('toolbar', { name: /design-00002-ui/ })).toBeTruthy())

    fireEvent.click(screen.getByTestId(STRIPE_CARD))

    await waitFor(() => expect(screen.getByTestId('node-reference-00011-s')).toBeTruthy())
    expect(screen.getByRole('toolbar', { name: /design-00002-ui/ })).toBeTruthy()
    expect(screen.queryByRole('toolbar', { name: /stripe/ })).toBeNull()
  })

  // spec-00010-AC-6.1
  it('puts the members below the group node when the name row is activated', async () => {
    await openBoard()

    await userEvent.click(screen.getByRole('button', { name: STRIPE_ROW }))

    await waitFor(() => expect(screen.getByTestId('node-reference-00011-s')).toBeTruthy())
    expect(screen.getByTestId('node-reference-00012-t')).toBeTruthy()
    expect(screen.getByTestId('node-reference-00013-u')).toBeTruthy()
    // The group node stays where it was, still counting all three.
    expect(screen.getByRole('button', { name: STRIPE_ROW })).toBeTruthy()
    // …and the group below it is untouched by its neighbour opening.
    expect(screen.getByTestId(CCBILL_CARD)).toBeTruthy()
    expect(screen.queryByTestId('node-reference-00021-c')).toBeNull()
  })

  /**
   * spec-00010-AC-6.1 by the keyboard: the name row is a real button, so Tab
   * reaches it and Enter fires it, and the key is stopped here rather than
   * reaching the canvas under it (design-00002 §19.6).
   */
  it('opens the group from the keyboard', async () => {
    await openBoard()

    screen.getByRole('button', { name: STRIPE_ROW }).focus()
    await userEvent.keyboard('{Enter}')

    await waitFor(() => expect(screen.getByTestId('node-reference-00011-s')).toBeTruthy())
    expect(screen.getByRole('button', { name: STRIPE_ROW }).getAttribute('aria-expanded')).toBe('true')

    // …and Space closes it again, the other key a button answers to.
    screen.getByRole('button', { name: STRIPE_ROW }).focus()
    await userEvent.keyboard(' ')

    await waitFor(() => expect(screen.queryByTestId('node-reference-00011-s')).toBeNull())
    expect(screen.getByRole('button', { name: STRIPE_ROW }).getAttribute('aria-expanded')).toBe('false')
  })

  // spec-00010-AC-6.3
  it('folds the members away again on the second activation', async () => {
    await openBoard()
    await userEvent.click(screen.getByRole('button', { name: STRIPE_ROW }))
    await waitFor(() => expect(screen.getByTestId('node-reference-00011-s')).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: STRIPE_ROW }))

    await waitFor(() => expect(screen.queryByTestId('node-reference-00011-s')).toBeNull())
    expect(screen.getByRole('button', { name: STRIPE_ROW })).toBeTruthy()
  })

  // spec-00010-AC-6.4 — the expanded set is the browser's, so it outlives the page
  it('opens again on the group the user left open', async () => {
    const first = await openBoard()
    await userEvent.click(screen.getByRole('button', { name: STRIPE_ROW }))
    await waitFor(() => expect(screen.getByTestId('node-reference-00011-s')).toBeTruthy())
    first.unmount()

    render(<Board />)

    await waitFor(() => expect(screen.getByTestId('node-reference-00011-s')).toBeTruthy())
    expect(screen.queryByTestId('node-reference-00021-c')).toBeNull()
  })

  // spec-00010-AC-6.5
  it('opens with every group collapsed when none was ever opened', async () => {
    await openBoard()

    expect(screen.getByTestId(STRIPE_CARD)).toBeTruthy()
    expect(screen.getByTestId(CCBILL_CARD)).toBeTruthy()
    expect(screen.queryByTestId('node-reference-00011-s')).toBeNull()
    expect(screen.queryByTestId('node-reference-00021-c')).toBeNull()
  })

  /**
   * spec-00010-AC-6.6 — folding the group of the selected document keeps the
   * selection: the toolbar goes because React Flow draws none for a node that
   * has left the canvas, and the right slot, which is driven by the selection
   * itself, does not move (design-00002 §19.2).
   */
  it('keeps the selection, and hands the group node its presentation, when the group folds', async () => {
    const inside = node({ id: 'spec-00042-x', type: 'spec', status: 'active', title: 'An archived spec', path: 'spec/archive/x.md' })
    await openBoard({ ...GROUPED, nodes: [...GROUPED.nodes, inside] })
    await userEvent.click(screen.getByRole('button', { name: 'archive, 1 document' }))
    fireEvent.click(await screen.findByTestId('node-spec-00042-x'))
    await waitFor(() => expect(screen.getByLabelText('Requirements of spec-00042-x')).toBeTruthy())

    fireEvent.click(screen.getByTestId('group-spec-spec/archive'))

    await waitFor(() => expect(screen.queryByTestId('node-spec-00042-x')).toBeNull())
    expect(screen.queryByRole('toolbar')).toBeNull()
    expect(screen.getByLabelText('Requirements of spec-00042-x')).toBeTruthy()
    expect(screen.getByTestId('group-spec-spec/archive').getAttribute('aria-current')).toBe('true')
  })

  // spec-00010-AC-6.7 — the aggregated marker only opens the group
  it('opens the group when its session marker is activated', async () => {
    served = [listing({ id: 's3', kind: 'ask', sourceId: 'reference-00012-t' })]
    await openBoard()

    await userEvent.click(screen.getByLabelText('Running session in stripe'))

    await waitFor(() => expect(screen.getByTestId('node-reference-00012-t')).toBeTruthy())
    expect(screen.queryByRole('toolbar')).toBeNull()
  })

  describe('on the minimap', () => {
    const blocks = (container: HTMLElement) =>
      Array.from(container.querySelectorAll('.react-flow__minimap-node')).map((block) => block.getAttribute('class'))

    // spec-00010-AC-10.1 — one block for the group, in the anomaly colour
    it('draws a collapsed group holding an anomalous document as one anomalous block', async () => {
      const broken = reference('reference/stripe/bad.md', 'reference/stripe/bad.md', { ok: false, problems: ['no status'] })
      const { container } = await openBoard({
        ...GROUPED,
        nodes: [DESIGN, TOP, STRIPE_ONE, STRIPE_TWO, STRIPE_THREE, broken],
        edges: [],
      })

      await waitFor(() => expect(blocks(container)).toHaveLength(3))
      expect(blocks(container)).toEqual([
        'react-flow__minimap-node minimap-status-active',
        'react-flow__minimap-node minimap-status-active',
        'react-flow__minimap-node minimap-anomaly',
      ])
    })

    // spec-00010-AC-10.2 — open, the group is still one block, and its members are their own
    it('keeps the group’s own block when it is expanded and colours each member by its status', async () => {
      const { container } = await openBoard({
        ...GROUPED,
        nodes: [
          DESIGN,
          TOP,
          reference('reference-00011-s', 'reference/stripe/one.md', { status: 'draft' }),
          reference('reference-00012-t', 'reference/stripe/two.md', { status: 'active' }),
          reference('reference-00013-u', 'reference/stripe/three.md', { status: 'archived' }),
        ],
        edges: [],
      })
      await userEvent.click(screen.getByRole('button', { name: STRIPE_ROW }))
      await waitFor(() => expect(screen.getByTestId('node-reference-00011-s')).toBeTruthy())

      await waitFor(() => expect(blocks(container)).toHaveLength(6))
      expect(blocks(container)).toEqual([
        'react-flow__minimap-node minimap-status-active',
        'react-flow__minimap-node minimap-status-active',
        'react-flow__minimap-node minimap-group',
        'react-flow__minimap-node minimap-status-draft',
        'react-flow__minimap-node minimap-status-active',
        'react-flow__minimap-node minimap-status-archived',
      ])
    })

    // spec-00010-AC-10.3 — no anomaly in it, so it takes the group node's own token
    it('draws a sound collapsed group as one block of the group colour', async () => {
      const { container } = await openBoard({
        ...GROUPED,
        nodes: [DESIGN, TOP, CCBILL_ONE, CCBILL_TWO],
        edges: [],
      })

      await waitFor(() => expect(blocks(container)).toHaveLength(3))
      expect(blocks(container)).toEqual([
        'react-flow__minimap-node minimap-status-active',
        'react-flow__minimap-node minimap-status-active',
        'react-flow__minimap-node minimap-group',
      ])
    })
  })
})
