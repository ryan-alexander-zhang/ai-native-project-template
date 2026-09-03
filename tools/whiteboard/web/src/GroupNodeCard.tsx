import { Folder, TriangleAlert } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import type { GroupNode } from './canvasModel.ts'
import { NodeHandles } from './NodeHandles.tsx'
import { SessionMarker, type SessionMarkerState } from './SessionMarker.tsx'
import { kindColour } from './status.ts'

export interface GroupNodeCardProps {
  node: GroupNode
  /** The kind of the column the group sits in — its members all share it. */
  kind?: string
  /** Recedes while another node holds the focus (spec-00001-AC-29.2). */
  suppressed?: boolean
  /**
   * Whether the selected document is folded away inside this group: the group
   * then carries the selection's presentation, since the selection itself never
   * moves onto it (spec-00010-AC-6.6).
   */
  holdsSelection?: boolean
  /** The members' sessions, already aggregated by `groupMarker` (spec-00010-FR-5). */
  session?: SessionMarkerState
  /** Expand or collapse — the one act the whole card performs (spec-00010-FR-6). */
  onToggle: () => void
}

/**
 * A directory group on the canvas: its name, how many documents it holds, and
 * the aggregated anomaly and session markers (spec-00010-FR-5). The shell is a
 * `div` like `NodeCard`'s and **not** a `<button>` — a button may not contain
 * one, and the card carries two real controls of its own (design-00002 §19.2).
 *
 * The size is declared, not measured: the layout reserved exactly this box for
 * the group's row, and the minimap sizes its block off the node object
 * (design-00002 §17.4, §19.5).
 */
export function GroupNodeCard({
  node,
  kind,
  suppressed = false,
  holdsSelection = false,
  session,
  onToggle,
}: GroupNodeCardProps) {
  const { group } = node
  const problems = group.nodes.filter((member) => !member.ok).length
  return (
    <div
      data-testid={`group-${group.columnKey}-${group.key}`}
      aria-current={holdsSelection ? 'true' : undefined}
      className={`text-foreground flex h-[92px] w-[240px] flex-col items-start gap-1 overflow-hidden rounded-xl border-2 px-3 py-2 shadow-sm transition-shadow ${
        holdsSelection ? 'ring-ring/50 shadow-md ring-2' : ''
      } ${suppressed ? 'node--suppressed' : ''}`}
      // The column's kind, and it stays the column's kind while a member is
      // broken: a group is not one bad document, and the anomaly marker beside
      // this already says which reading applies (design-00002 §19.2).
      style={{ backgroundColor: 'var(--group-node)', borderColor: kindColour(kind) }}
    >
      <NodeHandles />

      {/* The name row: what the group is, how many it holds, and the way in and
          out of it. `Folder` is a shape, not a term — the vocabulary is still
          «directory group» (design-00002 §19.2). */}
      <Button
        variant="ghost"
        size="sm"
        aria-expanded={node.expanded}
        aria-label={`${group.name}, ${group.nodes.length} document${group.nodes.length === 1 ? '' : 's'}`}
        className="max-w-full justify-start gap-1.5 px-1"
        onPointerDown={(event) => event.stopPropagation()}
        onMouseDown={(event) => event.stopPropagation()}
        onKeyDown={(event) => {
          if (event.key === 'Enter' || event.key === ' ') event.stopPropagation()
        }}
        onClick={(event) => {
          event.stopPropagation()
          onToggle()
        }}
      >
        <Folder className="size-3.5" aria-hidden />
        <span className="truncate text-[13px] leading-tight font-semibold">{group.name}</span>
        <Badge variant="secondary" className="text-[10px]">
          {group.nodes.length}
        </Badge>
      </Button>

      {/*
        Some of the documents in here do not parse (spec-00010-FR-5). A marker
        and nothing more: which ones is read on the real nodes once the group is
        open, and the anomaly list names each of them whether it is open or not,
        so there is no popover here (design-00002 §19.2).
      */}
      {node.anomalous ? (
        <Badge
          variant="destructive"
          className="gap-1 text-[10px]"
          aria-label={`${problems} document${problems === 1 ? '' : 's'} with problems`}
        >
          <TriangleAlert className="size-3.5" aria-hidden />
          {problems}
        </Badge>
      ) : null}

      {/* Somebody is working in here. Activating it only opens the group: a
          session is entered from the marker on its own document's node, so
          spec-00003-FR-10's branches are not replayed here (spec-00010-AC-6.7). */}
      {session === undefined ? null : (
        <SessionMarker
          label={`${session.state} session in ${group.name}`}
          icon={session.icon}
          onActivate={onToggle}
        />
      )}
    </div>
  )
}
