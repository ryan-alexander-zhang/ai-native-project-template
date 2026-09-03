import { type Edge, MarkerType, type Node, Position } from '@xyflow/react'
import type { DocEdge, DocGraph, DocNode } from '../../src/docRepository.ts'
import type { RequirementItem } from '../../src/requirements.ts'
import { type Column, type DirectoryGroup, NODE_HEIGHT, NODE_WIDTH, type Placed, groupNodeId } from './layout.ts'

/** A collapsed or expanded directory group as a node on the canvas (spec-00010-FR-5). */
export interface GroupNode {
  /** `group:` + the group's expand key; see `groupNodeId`. */
  id: string
  group: DirectoryGroup
  expanded: boolean
  /** Whether any member is an anomalous node — the marker the card carries. */
  anomalous: boolean
}

/**
 * The graph the canvas draws: the folded members are gone, the group nodes are
 * in their place, and every edge end has moved onto whichever of the two is
 * visible (design-00002 §19.2). Nothing else reads it — the anomaly and
 * diagnostic counts, the three lists, the command palette and the relation list
 * all keep reading `board.graph`.
 */
export interface FoldedGraph {
  nodes: (DocNode | GroupNode)[]
  edges: DocEdge[]
  /** A document id → the node standing for it: itself, or the group node folding it away. */
  representative: (id: string) => string
}

/** Group nodes are the only ones carrying a `group`; a document node never does. */
export function isGroupNode(node: DocNode | GroupNode): node is GroupNode {
  return 'group' in node
}

/**
 * Folding as one pure transformation of the graph (design-00002 §19.2): the
 * members of a collapsed group leave the node list (spec-00010-AC-5.1) and
 * every edge end that pointed at one moves onto the group node, so an edge with
 * both ends inside a single collapsed group has nothing left to draw
 * (spec-00010-AC-5.4).
 *
 * Nothing is merged here: `toFlowEdges` merges by from + to, which on the moved
 * ends is exactly the FR-28 rule the spec asks for on group nodes
 * (spec-00010-AC-5.3, AC-5.7 … AC-5.9). Sessions and the selection are not here
 * either — they are decoration the board injects.
 */
export function foldGraph(graph: DocGraph, columns: Column[], expanded: ReadonlySet<string> | string[]): FoldedGraph {
  const open = new Set(expanded)
  const nodes: (DocNode | GroupNode)[] = []
  const folded = new Map<string, string>()
  for (const column of columns) {
    nodes.push(...column.top)
    for (const group of column.groups) {
      const id = groupNodeId(group)
      const isOpen = open.has(group.expandKey)
      nodes.push({ id, group, expanded: isOpen, anomalous: group.nodes.some((node) => !node.ok) })
      if (isOpen) nodes.push(...group.nodes)
      else for (const member of group.nodes) folded.set(member.id, id)
    }
  }

  const representative = (id: string) => folded.get(id) ?? id
  const edges = graph.edges.flatMap((edge) => {
    const from = representative(edge.from)
    const to = representative(edge.to)
    // A document's own loop is drawn as long as the document is on the canvas;
    // two folded members pointing at each other are not.
    return from === to && from !== edge.from ? [] : [{ ...edge, from, to }]
  })
  return { nodes, edges, representative }
}

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
 *
 * Dispatched by node shape: a group node is a `docGroup` carrying its group,
 * and the React Flow `selected` flag is not given to it — a group is never the
 * selection (spec-00010-AC-5.6, design-00002 §19.2).
 */
export function toFlowNodes(graph: Pick<FoldedGraph, 'nodes'>, placed: Placed[], selected?: string): Node[] {
  return graph.nodes.map((node) => {
    const at = placed.find((position) => position.id === node.id)
    const common = {
      id: node.id,
      position: { x: at?.x ?? 0, y: at?.y ?? 0 },
      width: NODE_WIDTH,
      height: NODE_HEIGHT,
      // Not draggable is not «free for the pane to pan from»: React Flow marks a
      // draggable node `nopan` itself, and dropping the drag drops that with it,
      // which hands the press on a node's own control to the pan gesture — the
      // same swallowed click, one layer down (issue-00024).
      className: 'nopan',
    }
    // `docGroup`, not `group`: React Flow's built-in `group` type comes with a
    // container style sheet of its own (design-00002 §19.2).
    return isGroupNode(node)
      ? { ...common, type: 'docGroup', data: { group: node } }
      : { ...common, type: 'doc', data: { node }, selected: node.id === selected }
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
 *
 * Fed the folded graph, the merge key lands on the moved ends, which is the same
 * FR-28 rule read on group nodes (spec-00010-AC-5.3, AC-5.7 … AC-5.9). `selected`
 * is then the caller's own `representative(selected)` — the mapping belongs to
 * whoever folded the graph, not here (design-00002 §19.2).
 */
export function toFlowEdges(
  graph: Pick<FoldedGraph, 'edges'>,
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

/**
 * Nodes that neither are the selection nor share an edge with it. Fed the folded
 * graph and the selection's `representative`, this is also what leaves a group
 * node holding the selection, or one an aggregated edge reaches, unsuppressed
 * (spec-00010-AC-5.10, AC-5.11).
 */
export function suppressedNodes(graph: Pick<FoldedGraph, 'nodes' | 'edges'>, selected?: string): Set<string> {
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
