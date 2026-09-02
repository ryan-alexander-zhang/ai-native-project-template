import { type Edge, MarkerType, type Node, Position } from '@xyflow/react'
import type { DocEdge, DocGraph, DocNode } from '../../src/docRepository.ts'
import type { RequirementItem } from '../../src/requirements.ts'
import { NODE_HEIGHT, NODE_WIDTH, type Placed } from './layout.ts'

/** The four sides a relation edge can leave from or arrive at. */
export const SIDES = ['top', 'right', 'bottom', 'left'] as const
export type Side = (typeof SIDES)[number]

export const SIDE_POSITION: Record<Side, Position> = {
  top: Position.Top,
  right: Position.Right,
  bottom: Position.Bottom,
  left: Position.Left,
}

/** A custom node owns its handles; the ids are the contract between it and the edges. */
export function handleId(kind: 'source' | 'target', side: Side): string {
  return `${kind}-${side}`
}

/**
 * Graph plus layout as React Flow nodes; an unplaced node still lands on the
 * canvas. The size is declared rather than left to be measured — the card is
 * exactly the one the layout reserves for it — which is what lets anything
 * reading the graph off the store size a node before it has been drawn: the
 * minimap's block, above all (spec-00008-FR-7). `subCanvas` declares its own
 * for the same reason.
 */
export function toFlowNodes(graph: DocGraph, placed: Placed[], selected?: string): Node[] {
  return graph.nodes.map((node) => {
    const at = placed.find((position) => position.id === node.id)
    return {
      id: node.id,
      type: 'doc',
      position: { x: at?.x ?? 0, y: at?.y ?? 0 },
      width: NODE_WIDTH,
      height: NODE_HEIGHT,
      data: { node },
      selected: node.id === selected,
    }
  })
}

/**
 * Which sides an edge leaves from and arrives at, so the two ends face each
 * other: left/right across columns, top/bottom within one
 * (design-00002 §4). A document referencing itself gets a loop.
 */
function sides(from: Placed | undefined, to: Placed | undefined): [Side, Side] {
  if (!from || !to) return ['right', 'left']
  if (from.x < to.x) return ['right', 'left']
  if (from.x > to.x) return ['left', 'right']
  return from.y < to.y ? ['bottom', 'top'] : ['top', 'bottom']
}

/** Lifts an emphasised edge over the node layer; see design-00002 §4. */
const EMPHASIS_Z = 1

/**
 * How many cited AC ids one edge's label lists before it folds (spec-00001-FR-34
 * as amended, decision-00003 §5). Counted per edge, not per item: the label is a
 * signpost, and the full list is read in the panel or the detail panel.
 */
const LABEL_LIMIT = 3

/** «first id +N» past the threshold, where N is how many the label left out. */
function edgeLabel(acIds: string[]): string {
  return acIds.length > LABEL_LIMIT ? `${acIds[0]} +${acIds.length - 1}` : acIds.join(' · ')
}

/**
 * One edge per declared relation, drawn in the direction the front matter
 * declares it: the arrow lands on the referenced document (spec-00001-AC-1.10).
 * A broken one is drawn but marked (spec-00001-AC-2.2).
 *
 * Two relations declared between the same pair in the same direction share one
 * path exactly, so they are merged into a single edge carrying both field names
 * (spec-00001-FR-28) — drawing one line twice is not a distinction anyone can see.
 *
 * `selected` drives the three presentation states: with nothing selected every
 * edge is dim and unlabelled; with a node selected its own edges are emphasised
 * and labelled, and the rest are suppressed (spec-00001-FR-28, FR-29).
 *
 * `evidence` is the hovered item's, keyed by the record that verified it: those
 * edges take the emphasis and the label becomes the cited AC ids
 * (spec-00001-FR-34).
 */
export function toFlowEdges(
  graph: DocGraph,
  placed: Placed[],
  selected?: string,
  evidence?: Map<string, string[]>,
): Edge[] {
  const merged = new Map<string, { edge: DocEdge; relations: string[] }>()
  for (const edge of graph.edges) {
    const key = `${edge.from}\u0000${edge.to}`
    const existing = merged.get(key)
    if (existing) {
      existing.relations.push(edge.relation)
      // One broken end makes the whole line broken: it does point at a ghost.
      if (!edge.ok) existing.edge = edge
    } else {
      merged.set(key, { edge, relations: [edge.relation] })
    }
  }

  const drawn = [...merged.values()]
  // The AC ids each edge would carry while an item is hovered. The hover only
  // takes over when at least one edge answers it: an uncovered item, or a
  // record with no edge to the selection, leaves the FR-29 presentation alone
  // rather than dimming the whole board for nothing
  // (spec-00001-AC-34.3, AC-34.5).
  const cited = drawn.map(({ edge }) => citedAcross(edge, selected, evidence))
  const hovering = cited.some((ids) => ids !== undefined)

  return drawn.map(({ edge, relations }, index) => {
    const [from, to] = sides(
      placed.find((position) => position.id === edge.from),
      placed.find((position) => position.id === edge.to),
    )
    const connected = selected !== undefined && (edge.from === selected || edge.to === selected)
    const acIds = hovering ? cited[index] : undefined
    const emphasised = hovering ? acIds !== undefined : connected
    const emphasis = selected === undefined ? 'edge--dim' : emphasised ? 'edge--emphasis' : 'edge--suppressed'
    return {
      id: `e${index}`,
      source: edge.from,
      target: edge.to,
      sourceHandle: handleId('source', from),
      targetHandle: handleId('target', to),
      // A label is the emphasised state's job; carrying it always is what made
      // the board unreadable (decision-00003 §1).
      label: emphasised ? (acIds === undefined ? relations.join(' · ') : edgeLabel(acIds)) : undefined,
      zIndex: emphasised ? EMPHASIS_Z : undefined,
      markerEnd: { type: MarkerType.ArrowClosed },
      className: edge.ok ? emphasis : `${emphasis} edge--broken`,
    }
  })
}

/** The AC ids the hovered item's evidence cites across this edge, if it runs to such a record. */
function citedAcross(edge: DocEdge, selected?: string, evidence?: Map<string, string[]>): string[] | undefined {
  if (selected === undefined || evidence === undefined) return undefined
  if (edge.from === selected) return evidence.get(edge.to)
  return edge.to === selected ? evidence.get(edge.from) : undefined
}

/**
 * Which record cited which AC ids while verifying this item (spec-00001-FR-34).
 * Only rows naming an AC count: the label the hover puts on the edge is the
 * cited AC id, which an item-level row does not carry.
 */
export function evidenceOf(item: RequirementItem): Map<string, string[]> {
  const byRecord = new Map<string, string[]>()
  for (const row of item.criteria.flatMap((criterion) => criterion.rows)) {
    const cited = byRecord.get(row.recordId) ?? []
    if (!cited.includes(row.targetId)) cited.push(row.targetId)
    byRecord.set(row.recordId, cited)
  }
  return byRecord
}

/** Nodes that neither are the selection nor share an edge with it. */
export function suppressedNodes(graph: DocGraph, selected?: string): Set<string> {
  if (selected === undefined) return new Set()
  const keep = new Set([selected])
  for (const edge of graph.edges) {
    if (edge.from === selected) keep.add(edge.to)
    if (edge.to === selected) keep.add(edge.from)
  }
  return new Set(graph.nodes.map((node) => node.id).filter((id) => !keep.has(id)))
}

export interface RelationItem {
  field: string
  direction: 'out' | 'in'
  /** The id as declared — a requirement item id when the reference is fine-grained. */
  otherId: string
  /** The document the item belongs to, which is where picking it goes. */
  targetId: string
  ok: boolean
}

/**
 * The selected document's relations as a list (spec-00001-FR-30). Direction is
 * the checkable fact — whose front matter carries the declaration — not a
 * judgement about which document depends on the other, which differs per field
 * in this taxonomy (docs/README.md).
 *
 * An outgoing relation is listed once per declared id, so the three item ids
 * merged into one edge stay individually readable (spec-00001-AC-28.5); each
 * one leads to the document holding it (spec-00001-FR-2 as amended).
 */
export function relationsOf(graph: DocGraph, id: string, fieldOrder: string[]): RelationItem[] {
  const items = graph.edges.flatMap((edge): RelationItem[] => {
    if (edge.from === id) {
      return edge.declaredTargets.map((declared) => ({
        field: edge.relation,
        direction: 'out' as const,
        otherId: declared,
        targetId: edge.to,
        ok: edge.ok,
      }))
    }
    if (edge.to === id) {
      return [{ field: edge.relation, direction: 'in', otherId: edge.from, targetId: edge.from, ok: edge.ok }]
    }
    return []
  })
  const rank = (item: RelationItem) => {
    const field = fieldOrder.indexOf(item.field)
    return [item.direction === 'out' ? 0 : 1, field < 0 ? fieldOrder.length : field] as const
  }
  return items.sort((a, b) => {
    const [ad, af] = rank(a)
    const [bd, bf] = rank(b)
    return ad - bd || af - bf || a.otherId.localeCompare(b.otherId)
  })
}

/**
 * spec-00001-FR-26: every document whose id or title contains the query as a
 * case-insensitive substring, in graph order, uncapped. An anomalous document
 * carries its file path as its id, so it is searchable by path.
 *
 * The id a document collides on is matched too (spec-00002-FR-8): the key of a
 * colliding node is its path, so a path fragment finds that one file
 * (spec-00002-AC-8.5) and the colliding id finds every file declaring it, each
 * still going to its own node (spec-00002-AC-8.4).
 */
export function matchDocuments(nodes: DocNode[], query: string): DocNode[] {
  const needle = query.trim().toLowerCase()
  if (needle === '') return nodes
  const hit = (text?: string) => text !== undefined && text.toLowerCase().includes(needle)
  return nodes.filter((node) => hit(node.id) || hit(node.title) || hit(node.duplicateOf))
}
