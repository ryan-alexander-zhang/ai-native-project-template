import { TriangleAlert } from 'lucide-react'
import type { DocNode } from '../../src/docRepository.ts'
import { Badge } from '@/components/ui/badge'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
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
