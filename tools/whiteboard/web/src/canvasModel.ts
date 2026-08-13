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

/** The document a search box query points at, by id or title. */
export function findMatch(nodes: DocNode[], query: string): DocNode | undefined {
  const trimmed = query.trim()
  if (trimmed === '') return undefined
  return nodes.find((node) => node.id.includes(trimmed) || node.title.includes(trimmed))
}
