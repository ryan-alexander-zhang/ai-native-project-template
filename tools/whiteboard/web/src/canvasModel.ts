import type { Edge, Node } from '@xyflow/react'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import type { Placed } from './layout.ts'

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

/** One edge per declared relation; a broken one is drawn but marked (spec-00001-AC-2.2). */
export function toFlowEdges(graph: DocGraph): Edge[] {
  return graph.edges.map((edge, index) => ({
    id: `e${index}`,
    source: edge.from,
    target: edge.to,
    label: edge.relation,
    className: edge.ok ? undefined : 'edge--broken',
  }))
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
