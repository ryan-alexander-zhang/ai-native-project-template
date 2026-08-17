import { Handle } from '@xyflow/react'
import type { ReactNode } from 'react'
import { Badge } from '@/components/ui/badge'
import { SIDE_POSITION, handleId } from './canvasModel.ts'
import { COVERAGE } from './coverageMarks.ts'
import type { AcceptanceRowNodeData, CriterionNodeData, ItemNodeData } from './subCanvas.ts'

/**
 * The three node shapes of the sub-canvas (design-00002 §9). They are read, not
 * operated: the sub-canvas offers no editing, no review and no transition, so
 * none of them carries a control.
 */

interface ShellProps {
  testId: string
  className: string
  children: ReactNode
}

/**
 * A sub-canvas node's frame. The handles are the same contract the document
 * card owns: without them React Flow drops every edge that touches the node
 * (issue-00002), and they are hidden by opacity so they stay measurable.
 */
function Shell({ testId, className, children }: ShellProps) {
  return (
    <div
      data-testid={testId}
      className={`bg-card text-card-foreground flex flex-col justify-center overflow-hidden rounded-lg border px-3 py-2 shadow-sm ${className}`}
    >
      <Handle
        type="target"
        id={handleId('target', 'left')}
        position={SIDE_POSITION.left}
        isConnectable={false}
        isConnectableStart={false}
        isConnectableEnd={false}
        className="opacity-0"
      />
      {children}
      <Handle
        type="source"
        id={handleId('source', 'right')}
        position={SIDE_POSITION.right}
        isConnectable={false}
        isConnectableStart={false}
        isConnectableEnd={false}
        className="opacity-0"
      />
    </div>
  )
}

/** A requirement item: its coverage mark, its id, and two lines of its text. */
export function ItemNode({ data }: { data: ItemNodeData }) {
  const { item } = data
  const { Icon, label, token } = COVERAGE[item.coverage]
  return (
    <Shell testId={`sub-item-${item.id}`} className="h-[76px] w-[240px]">
      <div className="flex items-center gap-2">
        <Icon role="img" aria-label={label} className="size-3.5 shrink-0" style={{ color: token }} />
        <span className="truncate font-mono text-xs">{item.id}</span>
      </div>
      <p className="text-muted-foreground mt-1 line-clamp-2 text-[11px]">{item.text}</p>
    </Shell>
  )
}

/** An acceptance criterion: its id and the first line of its GWT. */
export function CriterionNode({ data }: { data: CriterionNodeData }) {
  const { criterion } = data
  return (
    <Shell testId={`sub-ac-${criterion.id}`} className="h-[60px] w-[200px]">
      <span className="truncate font-mono text-[11px]">{criterion.id}</span>
      <p className="text-muted-foreground line-clamp-1 text-[11px]">{criterion.text.replace(/\n[^]*$/, '')}</p>
    </Shell>
  )
}

/** An acceptance row: which record ran it, which test, and how it went. */
export function AcceptanceRowNode({ data }: { data: AcceptanceRowNodeData }) {
  const { row } = data
  return (
    <Shell testId={`sub-row-${row.recordId}-${row.targetId}`} className="h-[60px] w-[220px]">
      <div className="flex items-center gap-2">
        <span className="truncate font-mono text-[11px]">{row.recordId}</span>
        <Badge variant="secondary" className="ml-auto shrink-0 text-[10px]">
          {row.result}
        </Badge>
      </div>
      <p className="text-muted-foreground truncate text-[11px]">{row.test}</p>
    </Shell>
  )
}
