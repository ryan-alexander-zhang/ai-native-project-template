import { type Edge, MarkerType, type Node } from '@xyflow/react'
import type { AcceptanceRow, Criterion, ItemsView, RequirementItem } from '../../src/requirements.ts'
import { handleId } from './canvasModel.ts'

/** Column widths, left to right: item | AC | acceptance row (design-00002 §9). */
export const SUB_COLUMN_WIDTH = [240, 200, 220] as const
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

/** A record's acceptance row has no id of its own, so the citing AC and its ordinal make one. */
export function acceptanceRowId(criterionId: string, index: number): string {
  return `${criterionId}@${index}`
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
      position: { x: columnX(0), y: row * SUB_ROW_PITCH },
      data: { item } satisfies ItemNodeData,
    })

    for (const criterion of item.criteria) {
      nodes.push({
        id: criterion.id,
        type: 'criterion',
        position: { x: columnX(1), y: row * SUB_ROW_PITCH },
        data: { criterion } satisfies CriterionNodeData,
      })
      edges.push(edge(item.id, criterion.id))

      criterion.rows.forEach((entry, index) => {
        const id = acceptanceRowId(criterion.id, index)
        nodes.push({
          id,
          type: 'acceptanceRow',
          position: { x: columnX(2), y: row * SUB_ROW_PITCH },
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
