import { CircleHelp, Keyboard, TerminalIcon, TriangleAlert } from 'lucide-react'
import type { DocNode } from '../../src/docRepository.ts'
import type { SessionListing } from './api.ts'
import { Badge } from '@/components/ui/badge'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { NodeHandles } from './NodeHandles.tsx'
import { SessionMarker } from './SessionMarker.tsx'
import { kindColour, statusColour, statusLabel, typeIcon } from './status.ts'

export interface NodeCardProps {
  node: DocNode
  selected: boolean
  kind?: string
  /** Recedes while another node holds the focus (spec-00001-AC-29.2). */
  suppressed?: boolean
  /**
   * The sessions running on this document (spec-00003-FR-10 as spec-00005-FR-9
   * rewrites it): one document may now carry several — one terminal-form
   * session and any number of asks — and they are one marker, never a count.
   */
  sessions?: SessionListing[]
  /** Go to that session — the same act as picking it in the session panel, kind and all. */
  onShowSession?: (id: string) => void
}

/**
 * The one marker's session: a terminal-form one if the document has it, and
 * otherwise the oldest ask. Which it is decides both the icon and what
 * activating the marker does — the terminal for the first, the document's ask
 * list for the second (spec-00005-AC-9.6, AC-9.7).
 */
function markerOf(sessions: SessionListing[]): SessionListing | undefined {
  return sessions.find((one) => one.kind !== 'ask') ?? sessions[0]
}

/** A document on the canvas: type, status, title, id, and any anomaly. */
export function NodeCard({ node, selected, kind, suppressed = false, sessions = [], onShowSession }: NodeCardProps) {
  const Icon = typeIcon(node.type)
  const session = markerOf(sessions)
  // Running, waiting on an answer, or only being asked a question: three
  // readings, three icons, and the accessible name says which — never colour
  // alone (design-00002 §14).
  const state = session === undefined ? '' : session.kind === 'ask' ? 'Ask' : session.awaiting === true ? 'Awaiting input' : 'Running'
  const Marker = session?.kind === 'ask' ? CircleHelp : session?.awaiting === true ? Keyboard : TerminalIcon
  return (
    <div
      data-testid={`node-${node.id}`}
      className={`bg-card text-card-foreground flex h-[92px] w-[240px] flex-col gap-1 overflow-hidden rounded-xl border-2 px-3 py-2 shadow-sm transition-shadow ${
        selected ? 'ring-ring/50 shadow-md ring-2' : ''
      } ${suppressed ? 'node--suppressed' : ''}`}
      style={{ borderColor: node.ok ? kindColour(kind) : 'var(--destructive)' }}
    >
      <NodeHandles />

      <div className="flex items-center justify-between gap-2">
        <span className="text-muted-foreground flex items-center gap-1.5 text-[11px] tracking-wide uppercase">
          <Icon className="size-3.5" aria-hidden />
          {node.type ?? '—'}
        </span>
        {/* Slot ⑥ (design-00002 §4), shared with the group node's card: what it
            is and what activating it does are in `SessionMarker`. */}
        {session === undefined ? null : (
          <SessionMarker
            label={`${state} session of ${node.id}`}
            icon={Marker}
            onActivate={() => onShowSession?.(session.id)}
          />
        )}
        <Badge
          className="border-transparent text-[10px] text-white"
          style={{ backgroundColor: statusColour(node) }}
        >
          {statusLabel(node)}
        </Badge>
      </div>

      <div className="line-clamp-2 text-[13px] leading-tight font-semibold">{node.title}</div>
      {/*
        The key, and — for a node that collides on its id — the id it collides
        on beside it (spec-00002-AC-8.1, design-00002 §4). Both have to be
        there: without the path there is no telling the two files apart, and
        without the id there is no seeing what they collided on.
      */}
      <div className="text-muted-foreground truncate font-mono text-[10px]">
        {node.id}
        {node.duplicateOf === undefined ? null : (
          <span className="text-destructive ml-1.5">{node.duplicateOf}</span>
        )}
      </div>

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
