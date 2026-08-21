import {
  Check,
  ChevronDown,
  CircleHelp,
  GitBranch,
  MessageCircleQuestionMark,
  Pencil,
  Plus,
  ShieldCheck,
  Waypoints,
} from 'lucide-react'
import { type ReactElement, createElement } from 'react'
import type { FlowStep } from '../../src/config.ts'
import type { DocNode } from '../../src/docRepository.ts'
import type { RelationItem } from './canvasModel.ts'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'

export interface ToolbarProps {
  node: DocNode
  transitions: string[]
  nextSteps: FlowStep[]
  relations: RelationItem[]
  /** Whether this document's type may be clarified — the config's answer, not the toolbar's (spec-00001-FR-48). */
  clarifiable: boolean
  /** A session of any kind holds the one slot, so no entry may start another (spec-00001-FR-18). */
  sessionRunning: boolean
  onPickRelation: (id: string) => void
  onEdit: () => void
  onStatus: (to: string) => void
  onAccept: () => void
  onClarify: () => void
  onAsk: () => void
  onAudit: () => void
  onAdvance: (targetType: string) => void
}

/**
 * The types an audit is about, built into the code rather than read off the
 * config — the folder READMEs these three are audited against are what makes
 * them auditable, and that is not a configurable fact (spec-00001-FR-50).
 */
const AUDITABLE_TYPES = new Set(['spec', 'rule', 'design'])

/**
 * An entry that is disabled has to say why it is (spec-00001-AC-10.3, AC-49.5):
 * a board that has locked its starting points and given no reason cannot be
 * told from a broken one (issue-00010). No reason, no wrapper — the tooltip
 * belongs to the disabled state, not to the entry. The reason hangs on a span
 * because a disabled control takes no pointer events, and the span carries
 * `tabIndex` so the reason is reachable by keyboard too.
 */
function Disabled({ reason, children }: { reason?: string; children: ReactElement }): ReactElement {
  if (reason === undefined) return children
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span tabIndex={0}>{children}</span>
      </TooltipTrigger>
      <TooltipContent>{reason}</TooltipContent>
    </Tooltip>
  )
}

/**
 * The floating toolbar of spec-00001-FR-3. A document with front matter problems
 * offers the editor — the way to repair it — and the relation list, which is how
 * you find out what its broken link pointed at (spec-00001-AC-2.4).
 *
 * Status and advance are menus, not selects: each entry runs an action, so the
 * same one can be picked twice running.
 */
export function Toolbar(props: ToolbarProps) {
  const { node, transitions, nextSteps, relations, clarifiable, sessionRunning, onPickRelation } = props
  const { onEdit, onStatus, onAccept, onClarify, onAsk, onAudit, onAdvance } = props
  // The one reason every starting point shares (spec-00001-FR-18).
  const busy = sessionRunning ? 'session running' : undefined

  return (
    <div
      role="toolbar"
      aria-label={`Actions for ${node.id}`}
      // React Flow excludes panning by `nopan` and node dragging by `nodrag`; the
      // toolbar floats above the canvas and must drive neither (issue-00001).
      className="nodrag nopan bg-popover text-popover-foreground flex items-center gap-1 rounded-lg border p-1 shadow-lg"
      onPointerDown={(event) => event.stopPropagation()}
      onMouseDown={(event) => event.stopPropagation()}
    >
      <Button variant="ghost" size="sm" onClick={onEdit}>
        <Pencil className="size-4" aria-hidden />
        Edit
      </Button>

      {/*
        The list is the only readable answer for a hub: `spec-00001` touches 17
        of the graph's 39 edges, so highlighting them does not narrow anything
        down (decision-00003 §2). Direction is stated as the checkable fact —
        which document's front matter carries the declaration.
      */}
      <Popover>
        <PopoverTrigger asChild>
          <Button variant="ghost" size="sm" aria-label="Relations">
            <Waypoints className="size-4" aria-hidden />
            {relations.length}
          </Button>
        </PopoverTrigger>
        <PopoverContent align="start" className="w-96 p-0">
          {relations.length === 0 ? (
            <p className="text-muted-foreground p-3 text-xs">no relations</p>
          ) : (
            <ul aria-label={`Relations of ${node.id}`} className="max-h-80 overflow-y-auto py-1">
              {relations.map((relation) => (
                <li key={`${relation.direction}-${relation.field}-${relation.otherId}`}>
                  {/*
                    A broken relation has nowhere to go, so it is not offered as
                    something you can go to (issue-00005). It is still listed —
                    finding out what a broken link pointed at is the whole point
                    of reading this list (spec-00001-AC-30.5).
                  */}
                  {createElement(
                    relation.ok ? 'button' : 'div',
                    {
                      ...(relation.ok
                        ? { type: 'button', onClick: () => onPickRelation(relation.targetId) }
                        : {}),
                      className: `flex w-full items-baseline gap-2 px-3 py-1.5 text-left text-xs ${
                        relation.ok ? 'hover:bg-accent' : ''
                      }`,
                    },
                    <>
                      <span className="text-muted-foreground w-24 shrink-0 truncate">{relation.field}</span>
                      <span className="text-muted-foreground shrink-0" aria-hidden>
                        {relation.direction === 'out' ? '→' : '←'}
                      </span>
                      <span className="sr-only">
                        {relation.direction === 'out' ? 'declared here, points at' : 'declared by'}
                      </span>
                      <span className={`truncate font-mono ${relation.ok ? '' : 'text-destructive'}`}>
                        {relation.otherId}
                      </span>
                      {relation.ok ? null : <span className="text-destructive shrink-0">missing</span>}
                    </>,
                  )}
                </li>
              ))}
            </ul>
          )}
        </PopoverContent>
      </Popover>

      {node.ok ? (
        <>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="sm" aria-label="Change status">
                <GitBranch className="size-4" aria-hidden />
                {node.status}
                <ChevronDown className="size-3 opacity-60" aria-hidden />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start">
              {transitions.map((to) => (
                <DropdownMenuItem key={to} onSelect={() => onStatus(to)}>
                  {to}
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>

          <Button size="sm" onClick={onAccept}>
            <Check className="size-4" aria-hidden />
            Accept
          </Button>

          {/*
            Clarify sits inside the review group beside accept and is shown for a
            clarifiable type whatever the status: a document is either the kind of
            thing that gets questioned or it is not, and hiding the entry on
            anything but the type would leave the user guessing why it comes and
            goes. A clarify of a non-`draft` document is refused where the ruling
            belongs, on the server (spec-00001-FR-9).
          */}
          {clarifiable ? (
            <Disabled reason={busy}>
              <Button variant="ghost" size="sm" onClick={onClarify} disabled={sessionRunning}>
                <MessageCircleQuestionMark className="size-4" aria-hidden />
                Clarify
              </Button>
            </Disabled>
          ) : null}

          {/* Asking is not a review action: any status, any type (spec-00001-FR-47). */}
          <Disabled reason={busy}>
            <Button variant="ghost" size="sm" onClick={onAsk} disabled={sessionRunning}>
              <CircleHelp className="size-4" aria-hidden />
              Ask
            </Button>
          </Disabled>

          {/*
            Audit is the gate before review, so unlike clarify the entry follows
            the status as well as the type: it is offered on a `draft` of an
            auditable type and nowhere else (spec-00001-FR-51). Both halves of
            that are also ruled on by the server, so a hidden entry is a reading
            of the same rule, not the only enforcement of it.
          */}
          {AUDITABLE_TYPES.has(node.type ?? '') && node.status === 'draft' ? (
            <Disabled reason={busy}>
              <Button variant="outline" size="sm" onClick={onAudit} disabled={sessionRunning}>
                <ShieldCheck className="size-4" aria-hidden />
                Audit
              </Button>
            </Disabled>
          ) : null}

          {nextSteps.length === 0 ? (
            <Disabled reason="no next step">
              <Button variant="ghost" size="sm" aria-label="Advance to the next step" disabled>
                <Plus className="size-4" aria-hidden />
                no next step
              </Button>
            </Disabled>
          ) : (
            <Disabled reason={busy}>
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" size="sm" aria-label="Advance to the next step" disabled={sessionRunning}>
                    <Plus className="size-4" aria-hidden />
                    Advance
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="start">
                  {nextSteps.map((step) => (
                    <DropdownMenuItem key={step.next} onSelect={() => onAdvance(step.next)}>
                      {step.next}
                      <span className="text-muted-foreground ml-auto text-xs">{step.carry}</span>
                    </DropdownMenuItem>
                  ))}
                </DropdownMenuContent>
              </DropdownMenu>
            </Disabled>
          )}
        </>
      ) : null}

    </div>
  )
}
