import {
  Bot,
  ChevronDown,
  CircleHelp,
  Crosshair,
  Highlighter,
  LoaderCircle,
  NotebookPen,
  Pencil,
  Replace,
  Send,
  Trash2,
  Unlink,
} from 'lucide-react'
import { type ReactElement, type ReactNode, useState } from 'react'
import { ToggleGroup } from 'radix-ui'
import { toast } from 'sonner'
import type { SubmitPreview } from './api.ts'
import {
  type AnnotationRow,
  BLOCKED_TEXT,
  CHANGED_TEXT,
  HANDED_BACK_TEXT,
  ORPHAN_TEXT,
  ROW_BADGE,
} from './annotationRows.ts'
import type { AnnotationChange, AnnotationType } from './api.ts'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { REANCHOR_HINT } from './AnnotateArea.tsx'

/** The list's own empty state (spec-00007-AC-9.9). */
export const NO_ANNOTATIONS = 'no annotations yet — select a passage in the editor or the preview and right-click'

/** Why the submit will not go while the buffer holds unsaved edits (spec-00007-AC-5.4). */
export const SAVE_FIRST = 'save this document before submitting its annotations'

/** Why a locate entry is out (design-00002 §16.4). */
export const NOT_LOCATABLE = 'this annotation’s anchor lands nowhere in the document as it stands'

/** What the finished batch's hash is a reference to (design-00002 §16.6). */
export const COMMIT_TOOLTIP = 'the collapse commit of this batch of revisions'

/** How much of a commit hash the row shows; the payload carries the whole of it. */
const HASH_LENGTH = 7

const TOGGLE_ITEM =
  'flex items-center gap-1 rounded-sm border px-2 py-1 text-xs data-[state=on]:bg-accent disabled:opacity-50'

export interface AnnotationListProps {
  rows: readonly AnnotationRow[]
  /**
   * What the submit will do, **verbatim from the server** (design-00002 §16.5).
   * The list neither counts its own rows nor works out the transition: the
   * statement has to say what the submit will really do, and two computations of
   * it are two things to drift.
   */
  preview: SubmitPreview
  /** Whether this row can be located from the view the locate would land in. */
  locatable: (row: AnnotationRow) => boolean
  /** The row the board is located on — presentation state, kept by id. */
  located?: string
  /** The row re-anchor mode is waiting on, if any (design-00002 §16.4). */
  reanchoring?: string
  /** Whether the editor buffer holds unsaved edits: this entry is its one judge. */
  unsaved: boolean
  /** Whether a submit of this document is on its way out. */
  submitting: boolean
  /** Every agent, and of those the ones that declare a headless form. */
  agents: readonly string[]
  askAgents: readonly string[]
  onLocate: (row: AnnotationRow) => void
  onThread: (row: AnnotationRow) => void
  onSession: (row: AnnotationRow) => void
  onChange: (id: string, change: AnnotationChange) => Promise<boolean>
  onRemove: (id: string) => void
  /** Enter re-anchor mode on a row, or leave it. */
  onReanchor: (id?: string) => void
  onSubmit: (agents: { question?: string; cowrite?: string }) => void
}

/**
 * The annotation list of spec-00007-FR-9: the editor's fourth view state, beside
 * editing, preview and the ask list (design-00002 §16.1). Every annotation of the
 * document is a row in the order they were made — unsubmitted ones mixed among
 * the rest, since which are still unsubmitted is readable off a badge — and the
 * unified submit has its one entry in the header, where what it is about to
 * submit is on screen.
 */
export function AnnotationList(props: AnnotationListProps) {
  const { rows, preview, unsaved, submitting, onSubmit } = props
  const [confirming, setConfirming] = useState(false)
  const unsubmitted = preview.questions + preview.issues

  /**
   * The submit is disabled on **one** ground only: there is nothing unsubmitted
   * (spec-00007-AC-5.3). An unsaved buffer does not disable it — the refusal is
   * what AC-5.4 asks to be observable, and a disabled entry would leave that
   * execution with nowhere to happen. Same-document exclusion and the session cap
   * disable nothing either: spec-00007 §1 excludes this entry from them, and they
   * are judged per path at the submit.
   */
  function press() {
    if (unsaved) {
      toast.error(SAVE_FIRST)
      return
    }
    setConfirming(true)
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <header className="flex items-center gap-2 border-b px-3 py-2">
        <span className="text-muted-foreground text-xs">{unsubmitted} unsubmitted</span>
        <Button
          size="sm"
          className="ml-auto"
          disabled={unsubmitted === 0 || submitting}
          onClick={press}
        >
          {submitting ? (
            <LoaderCircle className="size-4 animate-spin" aria-hidden />
          ) : (
            <Send className="size-4" aria-hidden />
          )}
          Submit
        </Button>
      </header>

      <SubmitConfirm
        open={confirming}
        onOpenChange={setConfirming}
        preview={preview}
        agents={props.agents}
        askAgents={props.askAgents}
        onConfirm={(agents) => {
          setConfirming(false)
          onSubmit(agents)
        }}
      />

      {rows.length === 0 ? (
        <p className="text-muted-foreground flex items-center gap-2 p-3 text-xs">
          <Highlighter className="size-4 shrink-0" aria-hidden />
          {NO_ANNOTATIONS}
        </p>
      ) : (
        <ul aria-label="Annotations" className="min-h-0 flex-1 divide-y overflow-auto">
          {rows.map((row) => (
            <Row key={row.id} row={row} {...props} />
          ))}
        </ul>
      )}
    </div>
  )
}

/**
 * The statement of design-00002 §16.5, and the agent of each path. A dialog
 * rather than a popover or an immediate submit: this is the one action on the
 * board that opens a session, moves a document's status and produces a commit all
 * at once, and none of it can be undone.
 */
function SubmitConfirm({
  open,
  onOpenChange,
  preview,
  agents,
  askAgents,
  onConfirm,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  preview: SubmitPreview
  agents: readonly string[]
  askAgents: readonly string[]
  onConfirm: (agents: { question?: string; cowrite?: string }) => void
}) {
  const [question, setQuestion] = useState<string>()
  const [cowrite, setCowrite] = useState<string>()
  // One agent is no choice at all: nothing is drawn and nothing is sent, so the
  // server takes the first of that path's own set (spec-00007-AC-5.6).
  const chosen = {
    ...(askAgents.length > 1 ? { question: question ?? askAgents[0] } : {}),
    ...(agents.length > 1 ? { cowrite: cowrite ?? agents[0] } : {}),
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Submit these annotations?</DialogTitle>
        </DialogHeader>
        <ul className="flex flex-col gap-1 text-sm">
          {preview.questions > 0 ? <li>{`will start ${preview.questions} ask thread(s)`}</li> : null}
          {preview.issues > 0 ? <li>will start one cowrite session</li> : null}
          {preview.willTransitionTo === null ? null : (
            <li>{`will move this document to ${preview.willTransitionTo}`}</li>
          )}
        </ul>
        {preview.questions > 0 && askAgents.length > 1 ? (
          <AgentPick label="Question agent" agents={askAgents} chosen={chosen.question} onPick={setQuestion} />
        ) : null}
        {preview.issues > 0 && agents.length > 1 ? (
          <AgentPick label="Co-write agent" agents={agents} chosen={chosen.cowrite} onPick={setCowrite} />
        ) : null}
        <DialogFooter>
          <Button variant="ghost" size="sm" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button size="sm" onClick={() => onConfirm(chosen)}>
            <Send className="size-4" aria-hidden />
            Submit
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function AgentPick({
  label,
  agents,
  chosen,
  onPick,
}: {
  label: string
  agents: readonly string[]
  chosen?: string
  onPick: (name: string) => void
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="sm" className="self-start" aria-label={label}>
          <Bot className="size-4" aria-hidden />
          {chosen}
          <ChevronDown className="size-3 opacity-60" aria-hidden />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start">
        {agents.map((name) => (
          <DropdownMenuItem key={name} onSelect={() => onPick(name)}>
            {name}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/**
 * One row: the type icon and the state badge, the annotation's own text with the
 * quoted source under it, and the actions. No accordion — a row holds every field
 * it has, so there is no second layer to open.
 */
function Row({
  row,
  locatable,
  located,
  reanchoring,
  onLocate,
  onThread,
  onSession,
  onChange,
  onRemove,
  onReanchor,
  askAgents,
}: { row: AnnotationRow } & AnnotationListProps) {
  const [editing, setEditing] = useState(false)
  const badge = ROW_BADGE[row.state]
  const pending = row.state === 'pending'
  const canLocate = locatable(row)
  const chosen = reanchoring === row.id

  /** The whole of design-00002 §16.4's dispatch, and «none» really does nothing. */
  function open() {
    if (row.action === 'locate') onLocate(row)
    if (row.action === 'thread') onThread(row)
    if (row.action === 'session') onSession(row)
  }

  return (
    <li className={chosen || located === row.id ? 'bg-accent/50' : undefined}>
      <button
        type="button"
        onClick={open}
        className="hover:bg-accent flex w-full items-start gap-2 px-3 py-2 text-left text-xs"
      >
        {row.type === 'question' ? (
          <CircleHelp className="mt-0.5 size-3.5 shrink-0" aria-label="question annotation" />
        ) : (
          <NotebookPen className="mt-0.5 size-3.5 shrink-0" aria-label="issue annotation" />
        )}
        <span className="flex min-w-0 flex-1 flex-col gap-1">
          <span className="truncate">{row.text}</span>
          {/* The quote is **always** there: it is independent of whether the
              anchor still lands, and that is the whole of spec-00007-FR-2's
              fallback. An orphan opens it out in full — it is the only clue left. */}
          <span
            className={`text-muted-foreground border-l-2 pl-2 ${row.orphan === undefined ? 'line-clamp-2' : ''}`}
          >
            {row.quote}
          </span>
        </span>
        {badge.spinning ? <LoaderCircle className="mt-0.5 size-3 shrink-0 animate-spin" aria-hidden /> : null}
        <Badge variant={badge.variant} className="shrink-0 text-[10px]">
          {badge.label}
        </Badge>
      </button>

      <div className="flex flex-col gap-1 px-3 pb-2 text-xs">
        {/* The failure mark is drawn off `locate`, never off the stored `orphan`
            flag: the flag only records that a submit once held this one back, and
            reading it here would leave red text on an annotation that is fine
            again (design-00002 §16.4). */}
        {row.orphan === undefined ? null : (
          <p className="text-destructive flex items-center gap-1">
            <Unlink className="size-3.5 shrink-0" aria-hidden />
            {ORPHAN_TEXT[row.orphan]}
          </p>
        )}
        {/* The same icon in the muted colour: a document that has moved on is not
            an error, and nothing about the row's state changes (FR-12). */}
        {row.changed ? (
          <p className="text-muted-foreground flex items-center gap-1">
            <Unlink className="size-3.5 shrink-0" aria-hidden />
            {CHANGED_TEXT}
          </p>
        ) : null}
        {row.blocked === undefined ? null : (
          <p className="text-muted-foreground">{BLOCKED_TEXT[row.blocked]}</p>
        )}
        {row.handedBack === undefined ? null : (
          <p className="text-muted-foreground">{HANDED_BACK_TEXT[row.handedBack]}</p>
        )}
        {row.commit === undefined ? null : (
          <Tooltip>
            <TooltipTrigger asChild>
              <span className="text-muted-foreground w-fit font-mono">
                {row.commit === null ? 'no change' : row.commit.slice(0, HASH_LENGTH)}
              </span>
            </TooltipTrigger>
            <TooltipContent>{COMMIT_TOOLTIP}</TooltipContent>
          </Tooltip>
        )}
        {chosen ? <p className="text-muted-foreground">{REANCHOR_HINT}</p> : null}

        {editing ? (
          <Edit row={row} askAgents={askAgents} onDone={() => setEditing(false)} onChange={onChange} />
        ) : (
          <div className="flex items-center gap-1 self-end">
            <Guarded reason={canLocate ? undefined : NOT_LOCATABLE}>
              <Button
                variant="ghost"
                size="icon"
                aria-label={`Locate ${row.id}`}
                disabled={!canLocate}
                onClick={() => onLocate(row)}
              >
                <Crosshair className="size-4" aria-hidden />
              </Button>
            </Guarded>
            {pending ? (
              <>
                <Button variant="ghost" size="icon" aria-label={`Edit ${row.id}`} onClick={() => setEditing(true)}>
                  <Pencil className="size-4" aria-hidden />
                </Button>
                {/* Promoted on an orphan: re-anchoring is the way out, so it is
                    the one action that stands out there (design-00002 §16.4). */}
                <Button
                  variant={row.orphan === undefined ? 'ghost' : 'default'}
                  size="icon"
                  aria-label={`Re-anchor ${row.id}`}
                  onClick={() => onReanchor(chosen ? undefined : row.id)}
                >
                  <Replace className="size-4" aria-hidden />
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={`Delete ${row.id}`}
                  onClick={() => onRemove(row.id)}
                >
                  <Trash2 className="size-4" aria-hidden />
                </Button>
              </>
            ) : null}
          </div>
        )}
      </div>
    </li>
  )
}

/** The row's in-place edit: the same shape the annotation was written in. */
function Edit({
  row,
  askAgents,
  onDone,
  onChange,
}: {
  row: AnnotationRow
  askAgents: readonly string[]
  onDone: () => void
  onChange: (id: string, change: AnnotationChange) => Promise<boolean>
}) {
  const [text, setText] = useState(row.text)
  const [type, setType] = useState<AnnotationType>(row.type)

  async function save() {
    if (await onChange(row.id, { text, type })) onDone()
  }

  return (
    <div className="flex flex-col gap-2">
      <ToggleGroup.Root
        type="single"
        value={type}
        aria-label={`Type of ${row.id}`}
        onValueChange={(value) => (value === '' ? undefined : setType(value as AnnotationType))}
        className="flex gap-1"
      >
        {/* Moving to question is under the same configuration gate the entry is
            (spec-00007-AC-10.5); moving to issue is under the status gate, and
            that refusal is the server's word (spec-00007-FR-3). */}
        {askAgents.length > 0 ? (
          <ToggleGroup.Item value="question" className={TOGGLE_ITEM}>
            question
          </ToggleGroup.Item>
        ) : null}
        <ToggleGroup.Item value="issue" className={TOGGLE_ITEM}>
          issue
        </ToggleGroup.Item>
      </ToggleGroup.Root>
      <textarea
        aria-label={`Text of ${row.id}`}
        value={text}
        onChange={(event) => setText(event.target.value)}
        rows={3}
        className="border-input focus-visible:ring-ring/50 min-h-16 rounded-md border bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-[3px]"
      />
      <div className="flex items-center gap-2 self-end">
        <Button variant="ghost" size="sm" onClick={onDone}>
          Cancel
        </Button>
        <Button size="sm" disabled={text.trim() === ''} onClick={save}>
          Save
        </Button>
      </div>
    </div>
  )
}

/** A disabled entry says why, the way every other disabled entry on the board does. */
function Guarded({ reason, children }: { reason?: string; children: ReactElement }): ReactNode {
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
