import { Handle } from '@xyflow/react'
import { Fragment } from 'react'
import { SIDES, SIDE_POSITION, handleId } from './canvasModel.ts'

/**
 * A custom node owns the connection contract too: without handles React Flow
 * drops every edge that touches it (issue-00002). They are hidden with opacity,
 * never `display: none` — an unlaid-out handle cannot be measured, which brings
 * the same defect back.
 *
 * The three connect flags are set here rather than left to the canvas.
 * `<ReactFlow nodesConnectable={false}>` only passes a flag down to the node
 * component, which a custom node must forward. And `isConnectable` alone is not
 * enough: `Handle` defaults `isConnectableStart` and `isConnectableEnd`
 * independently, and the pointer-down guard reads `isConnectableStart` — so
 * without all three the drag stays armed and only the CSS class goes away.
 *
 * The document card and the directory group's card are the two node shapes on
 * the board, and an aggregate edge has to land on the second exactly as a
 * relation edge lands on the first — so the eight anchors are one component
 * rather than the same block written twice (design-00002 §4, §19.2).
 */
export function NodeHandles() {
  return (
    <>
      {SIDES.map((side) => (
        <Fragment key={side}>
          <Handle
            type="source"
            id={handleId('source', side)}
            position={SIDE_POSITION[side]}
            isConnectable={false}
            isConnectableStart={false}
            isConnectableEnd={false}
            className="opacity-0"
          />
          <Handle
            type="target"
            id={handleId('target', side)}
            position={SIDE_POSITION[side]}
            isConnectable={false}
            isConnectableStart={false}
            isConnectableEnd={false}
            className="opacity-0"
          />
        </Fragment>
      ))}
    </>
  )
}
