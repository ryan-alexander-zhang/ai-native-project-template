import { Handle } from '@xyflow/react'
import { TriangleAlert } from 'lucide-react'
import { Fragment } from 'react'
import type { DocNode } from '../../src/docRepository.ts'
import { Badge } from '@/components/ui/badge'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { SIDES, SIDE_POSITION, handleId } from './canvasModel.ts'
import { kindColour, statusColour, statusLabel, typeIcon } from './status.ts'

export interface NodeCardProps {
  node: DocNode
  selected: boolean
  kind?: string
}

/** A document on the canvas: type, status, title, id, and any anomaly. */
export function NodeCard({ node, selected, kind }: NodeCardProps) {
  const Icon = typeIcon(node.type)
  return (
    <div
      data-testid={`node-${node.id}`}
      className={`bg-card text-card-foreground flex h-[92px] w-[240px] flex-col gap-1 overflow-hidden rounded-xl border-2 px-3 py-2 shadow-sm transition-shadow ${
        selected ? 'ring-ring/50 shadow-md ring-2' : ''
      }`}
      style={{ borderColor: node.ok ? kindColour(kind) : 'var(--destructive)' }}
    >
      {/*
        A custom node owns the connection contract too: without handles React
        Flow drops every edge that touches it (issue-00002). They are hidden
        with opacity, never `display: none` — an unlaid-out handle cannot be
        measured, which brings the same defect back.

        The three connect flags are set here rather than left to the canvas.
        `<ReactFlow nodesConnectable={false}>` only passes a flag down to the
        node component, which a custom node must forward. And `isConnectable`
        alone is not enough: `Handle` defaults `isConnectableStart` and
        `isConnectableEnd` independently, and the pointer-down guard reads
        `isConnectableStart` — so without all three the drag stays armed and
        only the CSS class goes away.
      */}
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

      <div className="flex items-center justify-between gap-2">
        <span className="text-muted-foreground flex items-center gap-1.5 text-[11px] tracking-wide uppercase">
          <Icon className="size-3.5" aria-hidden />
          {node.type ?? '—'}
        </span>
        <Badge
          className="border-transparent text-[10px] text-white"
          style={{ backgroundColor: statusColour(node) }}
        >
          {statusLabel(node)}
        </Badge>
      </div>

      <div className="line-clamp-2 text-[13px] leading-tight font-semibold">{node.title}</div>
      <div className="text-muted-foreground truncate font-mono text-[10px]">{node.id}</div>

      {node.ok ? null : (
        <Popover>
          <PopoverTrigger asChild>
            <button
              type="button"
              aria-label={`Front matter problems of ${node.id}`}
              className="text-destructive mt-auto flex items-center gap-1 self-start text-[11px] underline-offset-2 hover:underline"
            >
              <TriangleAlert className="size-3.5" aria-hidden />
              {node.problems.length} problem{node.problems.length === 1 ? '' : 's'}
            </button>
          </PopoverTrigger>
          <PopoverContent className="w-80 text-xs">
            <ul className="list-disc space-y-1 pl-4">
              {node.problems.map((problem) => (
                <li key={problem}>{problem}</li>
              ))}
            </ul>
          </PopoverContent>
        </Popover>
      )}
    </div>
  )
}
