import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import { orderedColumns } from './layout.ts'

/** What a group is remembered and shown as (design-00002 §17.2). */
export interface TypeGroup {
  /** The declared type as written, empty for a document carrying none: what a collapsed group is remembered by. */
  key: string
  /** The name on the header — the type itself, or `untyped` for the group of the documents without one. */
  type: string
  nodes: DocNode[]
}

/** The name a group of documents without a declared type goes under. */
const UNTYPED = 'untyped'

/**
 * The navigation sidebar's groups: the canvas columns read as a list
 * (spec-00008-FR-1). Group order is column order and row order is row order
 * because both come from `orderedColumns` — there is no second rule here to
 * drift from the first (design-00002 §17.2).
 */
export function typeGroups(graph: DocGraph, typeOrder: string[]): TypeGroup[] {
  return orderedColumns(graph, typeOrder).map((nodes) => {
    // Every node of a column shares its type, so the first one names the group.
    const key = nodes[0]!.type ?? ''
    return { key, type: key === '' ? UNTYPED : key, nodes }
  })
}
