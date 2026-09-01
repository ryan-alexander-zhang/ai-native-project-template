import { CircleHelp, Highlighter, NotebookPen } from 'lucide-react'
import { type ReactNode, useEffect, useRef, useState } from 'react'
import { ContextMenu, ToggleGroup } from 'radix-ui'
import type { SelectionAnchor } from '../../src/annotationAnchor.ts'
import type { Selected } from './annotationSelection.ts'
import type { AnnotationType } from './api.ts'
import type { SourceRange } from './annotationCoords.ts'
import { Button } from '@/components/ui/button'
import { Popover, PopoverAnchor, PopoverContent } from '@/components/ui/popover'

/** What the entry says when the document's status allows no cowrite (design-00002 §16.2). */
export const ISSUE_INELIGIBLE = 'this document’s status allows no cowrite'

/** What the re-anchor mode asks for while it waits (design-00002 §16.4). */
export const REANCHOR_HINT = 'select the new passage in the editor or the preview'

const MENU_CONTENT =
  'bg-popover text-popover-foreground z-50 min-w-56 overflow-hidden rounded-md border p-1 shadow-md'
const MENU_ITEM =
  'flex cursor-default items-center gap-2 rounded-sm px-2 py-1.5 text-xs outline-hidden select-none data-[highlighted]:bg-accent data-[disabled]:pointer-events-none data-[disabled]:opacity-50'
const TOGGLE_ITEM =
  'flex items-center gap-1 rounded-sm border px-2 py-1 text-xs data-[state=on]:bg-accent disabled:opacity-50'

export interface AnnotateAreaProps {
  className?: string
  children: ReactNode
  /**
   * The two gates, **verbatim from `submitPreview`** (design-00002 §16.2). The
   * front end rules on nothing here: `issueEligible` is a reading of the status
   * table and of the cowrite rule, and computing it a second time over here
   * would be a second implementation to drift.
   */
  eligible: { question: boolean; issue: boolean }
  /** This side's reading of its own selection; `undefined` is «nothing annotatable there». */
  read: () => Selected | undefined
  onAdd: (input: {
    type: AnnotationType
    text: string
    anchor: SelectionAnchor
    range: SourceRange
  }) => Promise<boolean>
  /** The annotation the list is waiting for a new selection for (design-00002 §16.4). */
  reanchor?: { id: string; text: string }
  onReanchor: (input: { anchor: SelectionAnchor; range: SourceRange }) => Promise<boolean>
  /**
   * Stop being located on an annotation. One of the four clearing conditions of
   * design-00002 §16.6: a press in the body is a reader who has gone back to
   * reading, and the «here it is» mark has done its work.
   */
  onLeaveLocate: () => void
}

/** Where on the screen the selection ended, which is where its popover hangs. */
interface Spot {
  left: number
  top: number
}

/** The draft, which belongs to **this** selection and is dropped when the popover closes. */
interface Draft extends Selected {
  type: AnnotationType
  text: string
  at: Spot
}

/**
 * The rectangle of the live selection, read off the document rather than off
 * either side's own machinery: CodeMirror draws its selection with the browser's,
 * so one reading serves the editor and the preview alike.
 */
function selectionSpot(): Spot {
  const selection = window.getSelection()
  if (!selection || selection.rangeCount === 0) return { left: 0, top: 0 }
  const rect = selection.getRangeAt(0).getBoundingClientRect()
  return { left: rect.left, top: rect.bottom }
}

/**
 * Focus leaving one of these popovers is **not** a dismissal: the context menu
 * that opened the draft hands focus back to the body on its way out, and a draft
 * that vanished with it would be the words typed twice this design keeps
 * guarding against (design-00002 §16.2). A pointer press outside still closes
 * it, which is «the draft belongs to this selection», and so does Escape.
 */
function keepOpen(event: Event): void {
  event.preventDefault()
}

/**
 * A body container that takes the right-click over — and only when there is
 * something to offer on it (design-00002 §16.2). With no selection, with one that
 * cannot be mapped to the source, or with both types gated away, the event is
 * left alone and the browser's own menu appears: opening a menu of one
 * unavailable item is worse than opening none, and it would take away the copy
 * the reader already had (spec-00007-AC-1.5, AC-1.6, AC-4.6).
 *
 * The same container is the re-anchor mode's target: while one is waiting, the
 * first completed mappable selection offers itself as the annotation's new one
 * (spec-00007-AC-3.4).
 */
export function AnnotateArea({
  className,
  children,
  eligible,
  read,
  onAdd,
  reanchor,
  onReanchor,
  onLeaveLocate,
}: AnnotateAreaProps) {
  const [draft, setDraft] = useState<Draft>()
  /** The selection re-anchor mode has caught, waiting on its confirmation. */
  const [caught, setCaught] = useState<Selected & { at: Spot }>()
  /**
   * A render taken again, and nothing else. What is on show does not depend on
   * this number — the selection is read below — so all it has to do is bring the
   * reading up to date after an event that moved the selection.
   */
  const [, resense] = useState(0)
  const offers = eligible.question || eligible.issue

  /**
   * The selection **at render time**, never a snapshot kept from the last event
   * (design-00002 §16.2's «only when there is something to offer» has to hold at
   * the moment of the right-click). A snapshot is stale in every case the user
   * did not finish the selection with a mouse release inside this container: a
   * locate that put the selection there programmatically, a drag released outside
   * it, a touch selection. Read here, the gate is right after any render, and the
   * events below are only there to make a render happen.
   */
  const selected = read()
  const annotatable = useRef(false)
  annotatable.current = selected !== undefined

  /**
   * Re-read after an event that may have moved the selection. Only a change of
   * whether there **is** one moves the gate, and every use of the selection's
   * value reads it afresh, so nothing else is worth a render — a caret moving
   * through a paragraph would otherwise re-render on every keystroke.
   */
  function sense() {
    const now = read()
    if (reanchor !== undefined) {
      if (now !== undefined) setCaught({ ...now, at: selectionSpot() })
      return
    }
    if ((now !== undefined) !== annotatable.current) resense((count) => count + 1)
  }

  // The selection can also move with no event of this container's own — see
  // `selected` above. `selectionchange` is the document's own word for it.
  useEffect(() => {
    const owner = document
    owner.addEventListener('selectionchange', sense)
    return () => owner.removeEventListener('selectionchange', sense)
  })

  /**
   * The draft this menu opens on, over the selection **as it stands now**: the
   * gate was settled at the last render, and between that and this click the
   * selection may have moved. Nothing selected any more, nothing to write about.
   */
  function begin(type: AnnotationType) {
    const now = read()
    if (now === undefined) return
    setDraft({ ...now, type, text: '', at: selectionSpot() })
  }

  async function confirm(written: Draft) {
    // A refused confirmation keeps every word that was typed: the reason arrives
    // as a toast, and words thrown away on a refusal are words typed twice
    // (design-00002 §16.2, the ask entry's own discipline).
    const went = await onAdd({
      type: written.type,
      text: written.text,
      anchor: written.anchor,
      range: written.range,
    })
    if (went) setDraft(undefined)
  }

  async function replace(onto: Selected) {
    if (await onReanchor({ anchor: onto.anchor, range: onto.range })) setCaught(undefined)
  }

  return (
    <ContextMenu.Root>
      <ContextMenu.Trigger asChild disabled={!offers || selected === undefined || reanchor !== undefined}>
        <div className={className} onMouseDown={onLeaveLocate} onMouseUp={sense} onKeyUp={sense}>
          {children}
          {/* Both popovers hang off the selection rectangle rather than off the
              container: a draft belongs to the passage it was written about, and
              so does the offer to re-anchor onto one (design-00002 §16.2). */}
          <Popover open={draft !== undefined} onOpenChange={(open) => (open ? undefined : setDraft(undefined))}>
            <PopoverAnchor asChild>
              <span
                aria-hidden
                className="pointer-events-none fixed"
                style={{ left: draft?.at.left ?? 0, top: draft?.at.top ?? 0 }}
              />
            </PopoverAnchor>
            {draft === undefined ? null : (
              <PopoverContent
                align="start"
                className="flex w-80 flex-col gap-2"
                onFocusOutside={keepOpen}
              >
                <ToggleGroup.Root
                  type="single"
                  value={draft.type}
                  aria-label="Annotation type"
                  onValueChange={(value) =>
                    value === '' ? undefined : setDraft({ ...draft, type: value as AnnotationType })
                  }
                  className="flex gap-1"
                >
                  {eligible.question ? (
                    <ToggleGroup.Item value="question" className={TOGGLE_ITEM}>
                      <CircleHelp className="size-3.5" aria-hidden />
                      question
                    </ToggleGroup.Item>
                  ) : null}
                  <ToggleGroup.Item value="issue" disabled={!eligible.issue} className={TOGGLE_ITEM}>
                    <NotebookPen className="size-3.5" aria-hidden />
                    issue
                  </ToggleGroup.Item>
                </ToggleGroup.Root>
                <textarea
                  aria-label="Annotation text"
                  value={draft.text}
                  onChange={(event) => setDraft({ ...draft, text: event.target.value })}
                  placeholder="what is wrong here, or what you want to know"
                  rows={3}
                  className="border-input focus-visible:ring-ring/50 min-h-16 rounded-md border bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-[3px]"
                />
                {/* Empty text is refused here as well as at the server
                    (spec-00007-AC-1.4): a confirm that cannot succeed is not
                    offered. */}
                <Button size="sm" className="self-end" disabled={draft.text.trim() === ''} onClick={() => void confirm(draft)}>
                  <Highlighter className="size-4" aria-hidden />
                  Annotate
                </Button>
              </PopoverContent>
            )}
          </Popover>

          <Popover
            open={reanchor !== undefined && caught !== undefined}
            onOpenChange={(open) => (open ? undefined : setCaught(undefined))}
          >
            <PopoverAnchor asChild>
              <span
                aria-hidden
                className="pointer-events-none fixed"
                style={{ left: caught?.at.left ?? 0, top: caught?.at.top ?? 0 }}
              />
            </PopoverAnchor>
            {reanchor === undefined || caught === undefined ? null : (
              <PopoverContent
                align="start"
                className="flex w-80 flex-col gap-2"
                onFocusOutside={keepOpen}
              >
                <p className="text-xs">{reanchor.text}</p>
                <div className="flex items-center gap-2 self-end">
                  <Button variant="ghost" size="sm" onClick={() => setCaught(undefined)}>
                    Cancel
                  </Button>
                  <Button size="sm" onClick={() => void replace(caught)}>
                    Use this selection
                  </Button>
                </div>
              </PopoverContent>
            )}
          </Popover>
        </div>
      </ContextMenu.Trigger>

      <ContextMenu.Portal>
        <ContextMenu.Content className={MENU_CONTENT}>
          {/* The type is two items side by side rather than a submenu or a
              dialog that asks afterwards: one hop fewer, and the menu itself is
              where the gated set is read off (design-00002 §16.2). */}
          {eligible.question ? (
            <ContextMenu.Item className={MENU_ITEM} onSelect={() => begin('question')}>
              <CircleHelp className="size-3.5" aria-hidden />
              Add a question annotation
            </ContextMenu.Item>
          ) : null}
          <ContextMenu.Item className={MENU_ITEM} disabled={!eligible.issue} onSelect={() => begin('issue')}>
            <NotebookPen className="size-3.5" aria-hidden />
            Add an issue annotation
          </ContextMenu.Item>
          {/* A status the owner can change, so the way out is said out loud
              rather than the item being hidden (design-00002 §16.2). */}
          {eligible.issue ? null : (
            <p className="text-muted-foreground px-2 py-1 text-[10px]">{ISSUE_INELIGIBLE}</p>
          )}
        </ContextMenu.Content>
      </ContextMenu.Portal>
    </ContextMenu.Root>
  )
}
