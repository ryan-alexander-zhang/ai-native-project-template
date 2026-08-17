import { type Edge, MarkerType, type Node } from '@xyflow/react'
import type { AcceptanceRow, Criterion, ItemsView, RequirementItem } from '../../src/requirements.ts'
import { handleId } from './canvasModel.ts'

/** Column widths, left to right: item | AC | acceptance row (design-00002 §9). */
export const SUB_COLUMN_WIDTH = [240, 200, 220] as const
/**
 * And their heights — the item card is the tall one. Declaring the size React
 * Flow would otherwise have to measure is what lets it know when the dataset is
 * ready, which is what «fit on entry» waits for (spec-00001-AC-35.7).
 */
export const SUB_NODE_HEIGHT = [76, 60, 60] as const
export const SUB_COLUMN_GAP = 80
/** One row of the grid; every node of a row shares its top edge. */
export const SUB_ROW_PITCH = 88

/** Left edge of a column — the widths differ, so the offsets accumulate. */
function columnX(column: number): number {
  let x = 0
  for (let index = 0; index < column; index += 1) x += SUB_COLUMN_WIDTH[index]! + SUB_COLUMN_GAP
  return x
}

export type ItemNodeData = { item: RequirementItem }
export type CriterionNodeData = { criterion: Criterion }
export type AcceptanceRowNodeData = { row: AcceptanceRow }

/** What a clicked sub-canvas node stands for, which is what its detail panel reads (spec-00001-FR-37). */
export type DetailTarget =
  | { kind: 'item'; id: string; item: RequirementItem }
  | { kind: 'criterion'; id: string; criterion: Criterion }
  | { kind: 'row'; id: string; row: AcceptanceRow }

/**
 * The node id resolved against the current payload. Looking it up afresh on
 * every render is what makes the detail survive a graph refresh by id, and what
 * closes it when the thing it pointed at is gone (plan-00006 U2).
 */
export function detailTarget(view: ItemsView, nodeId: string): DetailTarget | undefined {
  for (const item of view.items) {
    if (item.id === nodeId) return { kind: 'item', id: nodeId, item }
    for (const criterion of item.criteria) {
      if (criterion.id === nodeId) return { kind: 'criterion', id: nodeId, criterion }
      const row = criterion.rows.find((_, index) => acceptanceRowId(criterion.id, index) === nodeId)
      if (row) return { kind: 'row', id: nodeId, row }
    }
  }
  return undefined
}

/** A record's acceptance row has no id of its own, so the citing AC and its ordinal make one. */
export function acceptanceRowId(criterionId: string, index: number): string {
  return `${criterionId}@${index}`
}

/** Where a node of `column` sits on `row`, at the size its column fixes. */
function place(column: number, row: number) {
  return {
    position: { x: columnX(column), y: row * SUB_ROW_PITCH },
    width: SUB_COLUMN_WIDTH[column]!,
    height: SUB_NODE_HEIGHT[column]!,
  }
}

function edge(source: string, target: string): Edge {
  return {
    id: `${source}->${target}`,
    source,
    target,
    sourceHandle: handleId('source', 'right'),
    targetHandle: handleId('target', 'left'),
    markerEnd: { type: MarkerType.ArrowClosed },
    className: 'edge--emphasis',
  }
}

/**
 * The sub-canvas of spec-00001-FR-35: the same React Flow instance, a different
 * dataset. Three columns, item | AC | acceptance row, rows in the item order the
 * server already sorted; each AC sits on the row of the first line it owns and
 * each item on the row of its first AC, so a chain reads straight across
 * (design-00002 §9).
 *
 * Synchronous and pure, like `layoutGraph`: no engine takes part, so a node's
 * place depends only on the payload it came from.
 */
export function subCanvas(view: ItemsView): { nodes: Node[]; edges: Edge[] } {
  const nodes: Node[] = []
  const edges: Edge[] = []
  let row = 0

  for (const item of view.items) {
    nodes.push({
      id: item.id,
      type: 'item',
      ...place(0, row),
      data: { item } satisfies ItemNodeData,
    })

    for (const criterion of item.criteria) {
      nodes.push({
        id: criterion.id,
        type: 'criterion',
        ...place(1, row),
        data: { criterion } satisfies CriterionNodeData,
      })
      edges.push(edge(item.id, criterion.id))

      criterion.rows.forEach((entry, index) => {
        const id = acceptanceRowId(criterion.id, index)
        nodes.push({
          id,
          type: 'acceptanceRow',
          ...place(2, row),
          data: { row: entry } satisfies AcceptanceRowNodeData,
        })
        edges.push(edge(criterion.id, id))
        row += 1
      })
      // An AC nobody verified still takes a row: the gap is the thing to see.
      if (criterion.rows.length === 0) row += 1
    }

    if (item.criteria.length === 0) row += 1
  }

  return { nodes, edges }
}
