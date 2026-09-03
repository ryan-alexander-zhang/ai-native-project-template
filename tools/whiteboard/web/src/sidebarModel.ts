import type { DocNode } from '../../src/docRepository.ts'
import type { Column, DirectoryGroup } from './layout.ts'

/** What a group is remembered and shown as (design-00002 §17.2, §19.4). */
export interface TypeGroup {
  /** The declared type as written, empty for a document carrying none: what a collapsed group is remembered by. */
  key: string
  /** The name on the header — the type itself, or `untyped` for the group of the documents without one. */
  type: string
  /** Every document of the type, the directory groups' members included: what the header counts (spec-00010-AC-8.1). */
  nodes: DocNode[]
  /** The documents of the type that belong to no directory group. */
  top: DocNode[]
  directories: DirectoryGroup[]
}

/**
 * The navigation sidebar's groups: the canvas columns read as a list
 * (spec-00008-FR-1). It is the board's own `columns` that comes in — the same
 * array the canvas folds — so group order is column order and row order is row
 * order with nothing here to drift from (design-00002 §17.2, §19.2, §19.4).
 */
export function typeGroups(columns: Column[]): TypeGroup[] {
  return columns.map((column) => ({
    key: column.key,
    type: column.type,
    nodes: [...column.top, ...column.groups.flatMap((group) => group.nodes)],
    top: column.top,
    directories: column.groups,
  }))
}
