import { describe, expect, it } from 'vitest'
import type { DocEdge, DocGraph, DocNode } from '../../src/docRepository.ts'
import { foldGraph, suppressedNodes, toFlowEdges, toFlowNodes } from '../src/canvasModel.ts'
import { NODE_HEIGHT, NODE_WIDTH, layoutGraph, orderedColumns } from '../src/layout.ts'

const ORDER = ['decision', 'design', 'reference']

/** The expand key as `layout.ts` builds it: the column key, NUL, the group key. */
const expandKey = (columnKey: string, key: string) => `${columnKey}\u0000${key}`
const STRIPE = expandKey('reference', 'reference/stripe')
const CCBILL = expandKey('reference', 'reference/ccbill')
const STRIPE_NODE = `group:${STRIPE}`
const CCBILL_NODE = `group:${CCBILL}`

function node(id: string, path: string, type: string, overrides: Partial<DocNode> = {}): DocNode {
  return { id, path, type, status: 'active', title: id, relations: {}, ok: true, problems: [], ...overrides }
}

function edge(from: string, to: string, relation: string): DocEdge {
  return { from, to, relation, ok: true, declaredTargets: [to] }
}

const DESIGN = node('design-00002-ui', 'design/design-00002-ui.md', 'design')
const DECISION = node('decision-00003-x', 'decision/decision-00003-x.md', 'decision')
const STRIPE_ONE = node('reference-00011-a', 'reference/stripe/a.md', 'reference')
const STRIPE_TWO = node('reference-00012-b', 'reference/stripe/b.md', 'reference')
const STRIPE_THREE = node('reference-00013-c', 'reference/stripe/c.md', 'reference')
const CCBILL_ONE = node('reference-00021-a', 'reference/ccbill/a.md', 'reference')
const CCBILL_TWO = node('reference-00022-b', 'reference/ccbill/b.md', 'reference')

const NODES = [DESIGN, DECISION, STRIPE_ONE, STRIPE_TWO, STRIPE_THREE, CCBILL_ONE, CCBILL_TWO]

function graphOf(edges: DocEdge[], nodes: DocNode[] = NODES): DocGraph {
  return { nodes, edges, issues: [], diagnostics: [], idOwners: {} }
}

/** The graph as the canvas sees it, with the groups named in `expanded` open. */
function fold(graph: DocGraph, expanded: string[] = []) {
  const columns = orderedColumns(graph, ORDER)
  const folded = foldGraph(graph, columns, expanded)
  return { folded, placed: layoutGraph(columns, expanded) }
}

describe('foldGraph', () => {
  // spec-00010-AC-5.1
  it('stands a collapsed group in for each of its members', () => {
    const { folded } = fold(graphOf([]))

    expect(folded.nodes.map((item) => item.id)).toEqual([
      DECISION.id,
      DESIGN.id,
      CCBILL_NODE,
      STRIPE_NODE,
    ])
    expect(folded.representative(STRIPE_TWO.id)).toBe(STRIPE_NODE)
    expect(folded.representative(DESIGN.id)).toBe(DESIGN.id)
    expect(folded.representative(STRIPE_NODE)).toBe(STRIPE_NODE)
    expect(folded.representative('reference-09999-ghost')).toBe('reference-09999-ghost')
  })

  // spec-00010-AC-6.1 — an expanded group keeps its group node and stands for nobody.
  it('puts the members of an expanded group back on the canvas', () => {
    const { folded } = fold(graphOf([]), [STRIPE])

    expect(folded.nodes.map((item) => item.id)).toEqual([
      DECISION.id,
      DESIGN.id,
      CCBILL_NODE,
      STRIPE_NODE,
      STRIPE_ONE.id,
      STRIPE_TWO.id,
      STRIPE_THREE.id,
    ])
    expect(folded.representative(STRIPE_TWO.id)).toBe(STRIPE_TWO.id)
    expect(folded.nodes.map((item) => ('group' in item ? item.expanded : undefined))).toContain(true)
  })

  // spec-00010-AC-5.2 — the group carries the marker; the counts stay per document.
  it('marks a group holding an anomalous document', () => {
    const broken = node('reference/stripe/broken.md', 'reference/stripe/broken.md', 'reference', { ok: false })
    const { folded } = fold(graphOf([], [...NODES, broken]))
    const groups = folded.nodes.filter((item) => 'group' in item)

    expect(groups.map((item) => ('group' in item ? [item.group.name, item.anomalous] : []))).toEqual([
      ['ccbill', false],
      ['stripe', true],
    ])
  })

  // spec-00010-AC-5.4
  it('drops an edge between two members of one collapsed group', () => {
    const { folded } = fold(graphOf([edge(STRIPE_TWO.id, STRIPE_ONE.id, 'supersedes')]))

    expect(folded.edges).toEqual([])
  })

  it('keeps that edge once the group is open', () => {
    const { folded } = fold(graphOf([edge(STRIPE_TWO.id, STRIPE_ONE.id, 'supersedes')]), [STRIPE])

    expect(folded.edges).toEqual([edge(STRIPE_TWO.id, STRIPE_ONE.id, 'supersedes')])
  })

  // A document referencing itself is drawn as a loop (design-00002 §4); only a
  // fold may make an edge disappear.
  it('keeps a visible document own loop', () => {
    const { folded } = fold(graphOf([edge(DESIGN.id, DESIGN.id, 'informs')]))

    expect(folded.edges).toEqual([edge(DESIGN.id, DESIGN.id, 'informs')])
  })

  // spec-00010-AC-5.3
  it('lands the two edges into one group on the group node, as one edge', () => {
    const graph = graphOf([edge(DESIGN.id, STRIPE_ONE.id, 'informs'), edge(DESIGN.id, STRIPE_TWO.id, 'informs')])
    const { folded, placed } = fold(graph)
    const edges = toFlowEdges(folded, placed)

    expect(edges).toHaveLength(1)
    expect(edges[0]).toMatchObject({ source: DESIGN.id, target: STRIPE_NODE })
  })

  // spec-00010-AC-6.2 — the same fixture with the group open: the one aggregate
  // edge splits back into an edge per member and the group node carries none.
  it('splits the aggregated edge back onto the members once the group is open', () => {
    const graph = graphOf([edge(DESIGN.id, STRIPE_ONE.id, 'informs'), edge(DESIGN.id, STRIPE_TWO.id, 'informs')])
    const { folded, placed } = fold(graph, [STRIPE])
    const edges = toFlowEdges(folded, placed)

    expect(edges.map((item) => [item.source, item.target])).toEqual([
      [DESIGN.id, STRIPE_ONE.id],
      [DESIGN.id, STRIPE_TWO.id],
    ])
    expect(edges.some((item) => item.source === STRIPE_NODE || item.target === STRIPE_NODE)).toBe(false)
  })

  // spec-00010-AC-5.7, AC-5.11 — two collapsed groups, one edge between them;
  // with nothing selected it is dim and unlabelled.
  it('merges the edges between two collapsed groups into one dim edge', () => {
    const graph = graphOf([
      edge(STRIPE_ONE.id, CCBILL_ONE.id, 'informs'),
      edge(STRIPE_TWO.id, CCBILL_TWO.id, 'informs'),
    ])
    const { folded, placed } = fold(graph)
    const edges = toFlowEdges(folded, placed)

    expect(edges).toHaveLength(1)
    expect(edges[0]).toMatchObject({
      source: STRIPE_NODE,
      target: CCBILL_NODE,
      label: undefined,
      className: 'edge--dim',
    })
  })

  // spec-00010-AC-5.8
  it('labels the emphasised aggregated edge with every field name it carries', () => {
    const graph = graphOf([
      edge(DECISION.id, STRIPE_ONE.id, 'constrains'),
      edge(DESIGN.id, STRIPE_TWO.id, 'informs'),
      edge(DESIGN.id, STRIPE_THREE.id, 'motivated_by'),
    ])
    const { folded, placed } = fold(graph)
    const edges = toFlowEdges(folded, placed, folded.representative(DESIGN.id))

    expect(edges).toHaveLength(2)
    expect(edges[0]).toMatchObject({ source: DECISION.id, label: undefined, className: 'edge--suppressed' })
    expect(edges[1]).toMatchObject({
      source: DESIGN.id,
      target: STRIPE_NODE,
      label: 'informs · motivated_by',
      className: 'edge--emphasis',
    })
  })

  // spec-00010-AC-5.9
  it('does not merge two aggregated edges running opposite ways', () => {
    const graph = graphOf([
      edge(STRIPE_TWO.id, DESIGN.id, 'informs'),
      edge(DESIGN.id, STRIPE_THREE.id, 'motivated_by'),
    ])
    const { folded, placed } = fold(graph)
    const edges = toFlowEdges(folded, placed)

    expect(edges.map((item) => [item.source, item.target])).toEqual([
      [STRIPE_NODE, DESIGN.id],
      [DESIGN.id, STRIPE_NODE],
    ])
  })

  // spec-00010-AC-5.10
  it('leaves a group node reached by the selection unsuppressed and suppresses the other', () => {
    const graph = graphOf([edge(DESIGN.id, STRIPE_ONE.id, 'informs')])
    const { folded, placed } = fold(graph)
    const selected = folded.representative(DESIGN.id)

    expect(suppressedNodes(folded, selected)).toEqual(new Set([DECISION.id, CCBILL_NODE]))
    expect(toFlowEdges(folded, placed, selected)[0]).toMatchObject({ className: 'edge--emphasis' })
  })

  // spec-00010-AC-6.6 — the selection inside a collapsed group is held by the
  // group node, which is what the edges and the suppression then read.
  it('reads the selection through the group standing in for it', () => {
    const graph = graphOf([edge(DESIGN.id, STRIPE_ONE.id, 'informs')])
    const { folded, placed } = fold(graph)
    const selected = folded.representative(STRIPE_ONE.id)

    expect(selected).toBe(STRIPE_NODE)
    expect(suppressedNodes(folded, selected)).toEqual(new Set([DECISION.id, CCBILL_NODE]))
    expect(toFlowEdges(folded, placed, selected)[0]).toMatchObject({ className: 'edge--emphasis', label: 'informs' })
  })
})

describe('toFlowNodes on a folded graph', () => {
  // spec-00010-AC-5.1
  it('draws a group node as a docGroup carrying its group', () => {
    const { folded, placed } = fold(graphOf([]))
    const drawn = toFlowNodes(folded, placed)
    const group = drawn.find((item) => item.id === STRIPE_NODE)!

    expect(group).toMatchObject({ type: 'docGroup', width: NODE_WIDTH, height: NODE_HEIGHT, className: 'nopan' })
    expect((group.data as { group: { group: { name: string } } }).group.group.name).toBe('stripe')
    expect(drawn.find((item) => item.id === DESIGN.id)).toMatchObject({ type: 'doc' })
  })

  // spec-00010-AC-5.6 — a group is never the selection, so it never carries the flag.
  it('never marks a group node selected', () => {
    const { folded, placed } = fold(graphOf([]))
    const drawn = toFlowNodes(folded, placed, STRIPE_NODE)

    expect(drawn.find((item) => item.id === STRIPE_NODE)!.selected).toBeUndefined()
    expect(drawn.find((item) => item.id === DESIGN.id)!.selected).toBe(false)
  })
})
