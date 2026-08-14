import { type Edge, MarkerType, type Node, Position } from '@xyflow/react'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import type { Placed } from './layout.ts'

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

/** Graph plus layout as React Flow nodes; an unplaced node still lands on the canvas. */
export function toFlowNodes(graph: DocGraph, placed: Placed[], selected?: string): Node[] {
  return graph.nodes.map((node) => {
    const at = placed.find((position) => position.id === node.id)
    return {
      id: node.id,
      type: 'doc',
      position: { x: at?.x ?? 0, y: at?.y ?? 0 },
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

/**
 * One edge per declared relation, drawn in the direction the front matter
 * declares it: the arrow lands on the referenced document (spec-00001-AC-1.10).
 * A broken one is drawn but marked (spec-00001-AC-2.2).
 */
export function toFlowEdges(graph: DocGraph, placed: Placed[]): Edge[] {
  return graph.edges.map((edge, index) => {
    const [from, to] = sides(
      placed.find((position) => position.id === edge.from),
      placed.find((position) => position.id === edge.to),
    )
    return {
      id: `e${index}`,
      source: edge.from,
      target: edge.to,
      sourceHandle: handleId('source', from),
      targetHandle: handleId('target', to),
      label: edge.relation,
      markerEnd: { type: MarkerType.ArrowClosed },
      className: edge.ok ? undefined : 'edge--broken',
    }
  })
}

/**
 * spec-00001-FR-26: every document whose id or title contains the query as a
 * case-insensitive substring, in graph order, uncapped. An anomalous document
 * carries its file path as its id, so it is searchable by path.
 */
export function matchDocuments(nodes: DocNode[], query: string): DocNode[] {
  const needle = query.trim().toLowerCase()
  if (needle === '') return nodes
  return nodes.filter(
    (node) => node.id.toLowerCase().includes(needle) || node.title.toLowerCase().includes(needle),
  )
}
