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

/** The name a group of documents without a declared type goes under. */
const UNTYPED = 'untyped'

/**
 * One directory group: the documents of a single column sharing the first two
 * segments of their path (spec-00010-FR-4, design-00002 §19.1).
 */
export interface DirectoryGroup {
  /** The group key: the first two path segments, `<folder>/<subdirectory>`. */
  key: string
  /** The `Column.key` of the column it sits in — what its kind is looked up by. */
  columnKey: string
  /** Column key + NUL + group key: the key its expanded state is remembered by. */
  expandKey: string
  /** The header name: the second segment when the first is the column's own type, both otherwise. */
  name: string
  nodes: DocNode[]
}

/** One drawn column: a document type, its top-level documents and its directory groups. */
export interface Column {
  /**
   * The declared type as written, empty for a document carrying none: what a
   * collapsed navigation group is remembered by, and the key of `board.kinds`.
   */
  key: string
  /** The name on the header — the type itself, or `untyped` for the documents without one. */
  type: string
  /** The documents of the column that belong to no directory group. */
  top: DocNode[]
  groups: DirectoryGroup[]
}

/**
 * Column sort key for a node: its declared type, or a bucket for anything the
 * flow config does not know. Sorting the buckets after the declared types is
 * what puts an anomalous document at the right-hand end (spec-00001-AC-1.9).
 * Local to this file: `Column.key` is the type as written, not this.
 */
function columnSortKey(node: DocNode, typeOrder: string[]): string {
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
 * The directory group a document belongs to: the first two segments of its
 * `docs/`-relative path, or nothing when the path has fewer than three segments
 * — a top-level document (spec-00010-AC-4.6). A deeper document falls into its
 * first-level subdirectory, since only the first two segments are read
 * (spec-00010-AC-4.2).
 */
export function groupKey(node: DocNode): string | undefined {
  const segments = node.path.split('/')
  return segments.length >= 3 ? `${segments[0]}/${segments[1]}` : undefined
}

/** The id the canvas and the layout both know a group node by (design-00002 §19.1). */
export function groupNodeId(group: DirectoryGroup): string {
  return `group:${group.expandKey}`
}

/**
 * Column key + NUL + group key, the same shape as the `toFlowEdges` merge key.
 * The column's `key` and not its display `type`: the same directory may sit in
 * two columns, and `untyped` is a name, not a key (design-00002 §19.1).
 */
function expandKeyOf(columnKey: string, key: string): string {
  return `${columnKey}\u0000${key}`
}

/**
 * The group name: the second segment when the first one is the column's own
 * type, both segments otherwise (spec-00010-AC-5.13, AC-5.14). The comparison
 * is against `Column.key`, so a directory named `untyped` in the column without
 * a declared type still shows both segments.
 */
function groupName(columnKey: string, key: string): string {
  const [folder, subdirectory] = key.split('/')
  return folder === columnKey ? subdirectory! : key
}

/** The rows of one column, already sorted by `byIdThenPath`, split into top documents and groups. */
function toColumn(nodes: DocNode[]): Column {
  // Every node of a column shares its type, so the first one names the column.
  const key = nodes[0]!.type ?? ''
  const top: DocNode[] = []
  const grouped = new Map<string, DocNode[]>()
  for (const node of nodes) {
    const group = groupKey(node)
    if (group === undefined) {
      top.push(node)
      continue
    }
    const members = grouped.get(group)
    if (members) members.push(node)
    else grouped.set(group, [node])
  }

  const groups = [...grouped.keys()]
    .sort((a, b) => a.localeCompare(b))
    .map((group) => ({
      key: group,
      columnKey: key,
      expandKey: expandKeyOf(key, group),
      name: groupName(key, group),
      nodes: grouped.get(group)!,
    }))
  return { key, type: key === '' ? UNTYPED : key, top, groups }
}

/**
 * Column is the document type, row is the id order within it, reading left to
 * right (decision-00002-whiteboard-layout §2). No layout engine: edges take no
 * part, so the stage order stays the one `typeOrder` declares and a node's
 * position does not move when its neighbours change.
 *
 * Within a column the rows are the top-level documents, then one row per
 * directory group — its group node — each followed by its members while the
 * group is expanded (spec-00010-FR-4, FR-6). The pitch is unchanged, so
 * expanding a group moves the rows below it down by exactly its size.
 */
export function layoutGraph(columns: Column[], expanded: ReadonlySet<string> | string[]): Placed[] {
  const open = new Set(expanded)

  return columns.flatMap((column, index) => {
    const rows = column.top.map((node) => node.id)
    for (const group of column.groups) {
      rows.push(groupNodeId(group))
      if (open.has(group.expandKey)) rows.push(...group.nodes.map((node) => node.id))
    }
    return rows.map((id, row) => ({
      id,
      x: index * (NODE_WIDTH + COLUMN_GAP),
      y: row * (NODE_HEIGHT + ROW_GAP),
    }))
  })
}

/**
 * The columns in the order they are drawn, each already in row order. The one
 * grouping, read by the canvas here and by the navigation sidebar through
 * `sidebarModel.ts`: group order is column order and row order is row order
 * because it is the same code, not two rules aimed at each other
 * (design-00002 §17.2, §19.1).
 */
export function orderedColumns(graph: DocGraph, typeOrder: string[]): Column[] {
  const columns = new Map<string, DocNode[]>()
  for (const node of graph.nodes) {
    const key = columnSortKey(node, typeOrder)
    const column = columns.get(key)
    if (column) column.push(node)
    else columns.set(key, [node])
  }

  return [...columns.keys()].sort().map((key) => toColumn(columns.get(key)!.sort(byIdThenPath)))
}
