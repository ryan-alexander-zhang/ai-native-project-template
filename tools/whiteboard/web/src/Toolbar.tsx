import { Check, ChevronDown, GitBranch, MessageCircleQuestionMark, Pencil, Plus, Waypoints } from 'lucide-react'
import { createElement, useState } from 'react'
import type { FlowStep } from '../../src/config.ts'
import type { DocNode } from '../../src/docRepository.ts'
import type { RelationItem } from './canvasModel.ts'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Textarea } from '@/components/ui/textarea'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'

export interface ToolbarProps {
  node: DocNode
  transitions: string[]
  nextSteps: FlowStep[]
  relations: RelationItem[]
  onPickRelation: (id: string) => void
  onEdit: () => void
  onStatus: (to: string) => void
  onAccept: () => void
  onClarify: (questions: string[]) => void
  onAdvance: (targetType: string) => void
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
  const { node, transitions, nextSteps, relations, onPickRelation } = props
  const { onEdit, onStatus, onAccept, onClarify, onAdvance } = props
  const [clarifying, setClarifying] = useState(false)
  const [questions, setQuestions] = useState('')

  function submitQuestions() {
    const list = questions
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean)
    if (list.length > 0) onClarify(list)
    setQuestions('')
    setClarifying(false)
  }

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
                        ? { type: 'button', onClick: () => onPickRelation(relation.otherId) }
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

          <Dialog open={clarifying} onOpenChange={setClarifying}>
            <DialogTrigger asChild>
              <Button variant="ghost" size="sm">
                <MessageCircleQuestionMark className="size-4" aria-hidden />
                Clarify
              </Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>Clarify {node.id}</DialogTitle>
                <DialogDescription>
                  One question per line. They go to the document's Open Questions; it stays a draft.
                </DialogDescription>
              </DialogHeader>
              <Textarea
                aria-label="Open questions, one per line"
                rows={5}
                value={questions}
                onChange={(event) => setQuestions(event.target.value)}
              />
              <DialogFooter>
                <Button onClick={submitQuestions}>Record questions</Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>

          {nextSteps.length === 0 ? (
            <Tooltip>
              <TooltipTrigger asChild>
                <span tabIndex={0}>
                  <Button variant="ghost" size="sm" aria-label="Advance to the next step" disabled>
                    <Plus className="size-4" aria-hidden />
                    no next step
                  </Button>
                </span>
              </TooltipTrigger>
              <TooltipContent>no next step</TooltipContent>
            </Tooltip>
          ) : (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="sm" aria-label="Advance to the next step">
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
          )}
        </>
      ) : null}

    </div>
  )
}
