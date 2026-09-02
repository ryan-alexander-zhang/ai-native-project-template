import type { DocGraph, DocNode } from '../../src/docRepository.ts'

export const NODE_WIDTH = 240
export const NODE_HEIGHT = 92
export const COLUMN_GAP = 96
export const ROW_GAP = 48

export interface Placed {
  id: string
  x: number
  y: number
}

/**
 * Column key for a node: its declared type, or a bucket for anything the flow
 * config does not know. Sorting the buckets after the declared types is what
 * puts an anomalous document at the right-hand end (spec-00001-AC-1.9).
 */
function columnKey(node: DocNode, typeOrder: string[]): string {
  const declared = node.type === undefined ? -1 : typeOrder.indexOf(node.type)
  if (declared >= 0) return `0${String(declared).padStart(4, '0')}`
  // An empty `type:` is a missing one, not an unnamed type of its own.
  if (node.type === undefined || node.type === '') return '2'
  return `1${node.type}`
}

/** id, then path — a total order, so two documents sharing an id still get distinct rows. */
function byIdThenPath(a: DocNode, b: DocNode): number {
  return a.id === b.id ? a.path.localeCompare(b.path) : a.id.localeCompare(b.id)
}

/**
 * Column is the document type, row is the id order within it, reading left to
 * right (decision-00002-whiteboard-layout §2). No layout engine: edges take no
 * part, so the stage order stays the one `typeOrder` declares and a node's
 * position does not move when its neighbours change.
 */
export function layoutGraph(graph: DocGraph, typeOrder: string[]): Placed[] {
  return orderedColumns(graph, typeOrder).flatMap((column, index) =>
    column.map((node, row) => ({
      id: node.id,
      x: index * (NODE_WIDTH + COLUMN_GAP),
      y: row * (NODE_HEIGHT + ROW_GAP),
    })),
  )
}

/**
 * The columns in the order they are drawn, each already in row order. The one
 * grouping, read by the canvas here and by the navigation sidebar through
 * `sidebarModel.ts`: group order is column order and row order is row order
 * because it is the same code, not two rules aimed at each other
 * (design-00002 §17.2).
 */
export function orderedColumns(graph: DocGraph, typeOrder: string[]): DocNode[][] {
  const columns = new Map<string, DocNode[]>()
  for (const node of graph.nodes) {
    const key = columnKey(node, typeOrder)
    const column = columns.get(key)
    if (column) column.push(node)
    else columns.set(key, [node])
  }

  return [...columns.keys()].sort().map((key) => columns.get(key)!.sort(byIdThenPath))
}
