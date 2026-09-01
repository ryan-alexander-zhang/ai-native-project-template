import { markdown } from '@codemirror/lang-markdown'
import { Compartment } from '@codemirror/state'
import type { ViewUpdate } from '@codemirror/view'
import { EditorView, basicSetup } from 'codemirror'
import { Code, Eye, Highlighter, List, LoaderCircle, Lock, Save, X } from 'lucide-react'
import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { toast } from 'sonner'
import type { SelectionAnchor } from '../../src/annotationAnchor.ts'
import { AnnotateArea } from './AnnotateArea.tsx'
import { type SourceRange, bodyPrefix, normalized, toBodyRange } from './annotationCoords.ts'
import {
  type MarkRange,
  annotationMarks,
  locateInEditor,
  previewMarks,
  sameRanges,
  setLocated,
  setTraces,
  traceOf,
  traces,
} from './annotationMarks.ts'
import { type Selected, editorAnchor, previewAnchor } from './annotationSelection.ts'
import { ApiError, type AnnotationType, type DocContent, api } from './api.ts'
import { Button } from '@/components/ui/button'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Preview } from './Preview.tsx'

/**
 * The editor's four view states: the text, its rendering, the document's ask
 * list (spec-00005-FR-9) and its annotation list (spec-00007-FR-9). Which one is
 * on show is the board's presentation state rather than the editor's own — a
 * session panel row and a desktop notification both set it from outside
 * (design-00002 §14, §16.1).
 */
export type EditorMode = 'source' | 'preview' | 'asks' | 'annotations'

/** What the annotation layer needs of the body it is drawn over (design-00002 §16). */
export interface EditorAnnotate {
  /**
   * The document this payload was read for. Not always the one the editor is on:
   * a switch changes the editor's document at once and the next read lands a
   * moment later (design-00002 §16.8). The editor draws nothing from a payload of
   * another document — intervals of one document over the text of another are
   * marks on passages nobody annotated.
   */
  docId: string
  /** The submit statement's two gates, verbatim — the front end rules on neither. */
  eligible: { question: boolean; issue: boolean }
  /** Where every unsubmitted, locatable annotation's anchor lands, in file coordinates. */
  traces: readonly MarkRange[]
  /**
   * The annotation to locate, where its anchor lands, and **which** locate this
   * is: `askedAt` counts them, so asking for the same annotation twice is two
   * locates. Keyed on the annotation alone, the second ask is a no-op — and once
   * a change has cleared the mark, the only entry that would bring it back is the
   * one that reads as already served (design-00002 §16.6).
   */
  locate?: { id: string; range: SourceRange; askedAt: number }
  /** The annotation the list is waiting for a new selection for. */
  reanchor?: { id: string; text: string }
  /** Records one annotation; what comes back is its id, or nothing when it was refused. */
  onAdd: (input: { type: AnnotationType; text: string; anchor: SelectionAnchor }) => Promise<string | undefined>
  onReanchor: (input: { anchor: SelectionAnchor; range: SourceRange }) => Promise<boolean>
  /** Stop being located on an annotation (design-00002 §16.6's third clearing condition). */
  onLeaveLocate: () => void
  /** Whether the buffer holds unsaved edits: the submit entry's one judge (design-00002 §16.5). */
  onUnsaved: (unsaved: boolean) => void
}

/** What `served` reads as while no locate is on: no counter ever takes this value. */
const NOTHING_LOCATED = -1

/** The gates of a body with no annotation layer: neither type, so nothing is offered. */
const NO_TYPES = { question: false, issue: false } as const
const nothingSelected = (): undefined => undefined
const nothing = (): void => {}
const refuse = async (): Promise<boolean> => false

/** Why the buffer is locked, said where it is locked (spec-00006-AC-4.3). */
export const CO_WRITE_LOCK = 'the agent is writing this document'

/** What the notice says when the disk moved under edits that are not saved yet (spec-00006-AC-4.5). */
export const DISK_MOVED = 'this document changed on disk; your unsaved edits are kept, and saving will report the conflict'

export interface EditorProps {
  docId: string
  /**
   * The prefilled buffer of a document that is not on disk yet (spec-00001-FR-53).
   * Its presence is what makes this a creation: nothing is read, and saving
   * creates the file rather than revising one.
   */
  draft?: string
  /** Which of the three views is on show, and the way to ask for another. */
  mode: EditorMode
  onMode: (mode: EditorMode) => void
  /**
   * The question entry, when this document has one: an anomalous document does
   * not, and neither does any document while no agent declares a headless form
   * (spec-00005-AC-7.3, AC-7.4).
   */
  ask?: ReactNode
  /** The ask list, which is what the third view state shows. */
  asks?: ReactNode
  /** The annotation list, which is what the fourth view state shows (spec-00007-FR-9). */
  annotations?: ReactNode
  /**
   * The annotation layer over the body. Absent on a document that has none — a
   * buffer that is not a document yet — and the tab goes with it.
   */
  annotate?: EditorAnnotate
  /**
   * What the document says on disk just now, re-read with each refresh while a
   * cowrite session holds it — the fifth item of the one refresh path
   * (design-00002 §10). A **clean** buffer takes it: that reload is
   * spec-00001-FR-42's cowrite exception (spec-00006-AC-4.2). A **dirty** one
   * keeps every unsaved edit and is told the disk moved instead, so the save that
   * follows meets the existing conflict rather than overwriting the agent
   * (AC-4.5, AC-5.4). Absent while no cowrite session holds the document, which
   * is when FR-42 applies untouched.
   */
  disk?: DocContent
  /**
   * Whether the agent has the pen: a cowrite session running and not waiting on
   * the user (spec-00006-FR-4). It locks the **Source view's editing and saving**
   * and nothing else — the preview, the ask list and the question entry are
   * unaffected (AC-4.6) — and its arrival never clears an unsaved buffer
   * (AC-4.7).
   */
  readOnly?: boolean
  onSaved: () => void
  onClose: () => void
}

/** Edits the whole file, front matter included, and refuses to clobber a changed file. */
export function Editor({
  docId,
  draft,
  mode,
  onMode,
  ask,
  asks,
  annotations,
  annotate,
  disk,
  readOnly = false,
  onSaved,
  onClose,
}: EditorProps) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView>(null)
  const previewHost = useRef<HTMLDivElement>(null)
  /**
   * The layer, but only while it is **this** document's (see `EditorAnnotate`).
   * One name for that reading, used everywhere below, so no path can draw from
   * the last document's payload.
   */
  const layer = annotate?.docId === docId ? annotate : undefined
  const [opened, setOpened] = useState<DocContent>()
  const [saving, setSaving] = useState(false)
  const [preview, setPreview] = useState('')
  /**
   * The trace intervals **as they now stand** — rebuilt from the payload while
   * the buffer is clean, and otherwise the local set mapped forward through every
   * change (design-00002 §16.6). Held beside the CodeMirror field because the
   * preview renders from the same buffer and draws the same intervals.
   */
  const [drawn, setDrawn] = useState<readonly MarkRange[]>([])
  /** Whether the disk moved under a buffer that could not take the change (AC-4.5). */
  const [moved, setMoved] = useState(false)
  /**
   * The text the buffer was last in step with — what was opened, or what was
   * saved. It is what «unsaved edits» is measured against, and measuring it
   * against `opened.content` alone would read a saved buffer as dirty for ever.
   */
  const base = useRef('')
  /**
   * The editability of the view, in a compartment so it can be reconfigured
   * rather than rebuilt: rebuilding the view would mount `opened.content` over
   * whatever the user has not saved yet (spec-00006-AC-4.7).
   */
  const editable = useMemo(() => new Compartment(), [])
  /**
   * The annotation layer as it stands now, readable from the CodeMirror update
   * listener — which is built once with the view and cannot close over a prop.
   */
  const live = useRef<EditorAnnotate>(undefined)
  live.current = layer
  /** Which locate has been served, so a re-render does not scroll again. */
  const served = useRef<number>(NOTHING_LOCATED)
  // Read out as values rather than kept as the object: the object is built fresh
  // on every render of the board, and a mark set rebuilt with it would scroll the
  // reader back to it each time a refresh lands.
  const locateId = layer?.locate?.id
  const locateStart = layer?.locate?.range.start
  const locateEnd = layer?.locate?.range.end
  const locateAskedAt = layer?.locate?.askedAt

  /**
   * Every change of the buffer, reported twice over: the submit entry has to know
   * whether the buffer is saved (design-00002 §16.5), and the traces have to be
   * read back after CodeMirror has mapped them forward (§16.6).
   */
  const watch = useCallback((update: ViewUpdate) => {
    if (!update.docChanged) return
    live.current?.onUnsaved(update.state.doc.toString() !== base.current)
    setDrawn(traces(update.view))
  }, [])

  useEffect(() => {
    // The notice is about **this** document's disk moving under **this** buffer
    // (AC-4.5), so it goes when the buffer does: left standing, it would follow
    // the reader onto the next document they open and say something untrue of it.
    setMoved(false)
    // A new document has nothing to read: the buffer it opens with is the
    // prefill, and there is no base version to be in conflict with.
    if (draft !== undefined) {
      setOpened({ path: '', content: draft, hash: '' })
      return
    }
    let live = true
    api.doc(docId).then((content) => {
      if (live) setOpened(content)
    })
    return () => {
      live = false
    }
  }, [docId, draft])

  useEffect(() => {
    if (!host.current || !opened) return
    base.current = opened.content
    view.current = new EditorView({
      doc: opened.content,
      extensions: [
        basicSetup,
        markdown(),
        annotationMarks,
        EditorView.updateListener.of(watch),
        editable.of(EditorView.editable.of(!readOnly)),
      ],
      parent: host.current,
    })
    // A buffer just opened is a buffer in step with the disk, whatever the last
    // one was.
    live.current?.onUnsaved(false)
    return () => view.current?.destroy()
    // `readOnly` is deliberately not a dependency: it is reconfigured below, and
    // remounting on it would take the unsaved buffer with it (spec-00006-AC-4.7).
  }, [opened, editable, watch])

  useEffect(() => {
    view.current?.dispatch({ effects: editable.reconfigure(EditorView.editable.of(!readOnly)) })
  }, [readOnly, editable, opened])

  /**
   * The reload of spec-00006-FR-4, and the one condition on it: the buffer holds
   * nothing the user has not saved. A dirty buffer is kept and the notice says
   * the disk moved — spec-00001-FR-42 applies to it untouched, and the save that
   * follows is refused by the existing conflict check, which here is the
   * protection rather than the defect (AC-4.2, AC-4.5).
   */
  useEffect(() => {
    if (disk === undefined || opened === undefined || disk.hash === opened.hash) return
    if ((view.current?.state.doc.toString() ?? base.current) !== base.current) {
      setMoved(true)
      return
    }
    setMoved(false)
    setOpened(disk)
  }, [disk, opened])

  /**
   * How the readings that arrive with a refresh join what is already drawn
   * (design-00002 §16.6):
   *
   * - a **clean** buffer rebuilds the whole set from them — the disk and the
   *   buffer agree, so the server's fresh reading is the truest one there is;
   * - a **dirty** buffer keeps its own set, mapped forward through every change,
   *   and says nothing about it: the disk's offsets do not hold against the text
   *   in front of the user, and drawing by them would put the traces on other
   *   sentences. Silently, because a trace is a reading aid and its position
   *   being local for a moment has no consequence to warn about.
   */
  useEffect(() => {
    const current = view.current
    if (current === null || layer === undefined) return
    if (current.state.doc.toString() !== base.current) return
    if (sameRanges(traces(current), layer.traces)) return
    current.dispatch({ effects: setTraces.of(layer.traces) })
    setDrawn(layer.traces)
  }, [layer, opened])

  // The intervals belong to the document they were read for: another document's
  // editor starts with none, and draws none until its own payload lands
  // (design-00002 §16.6).
  useEffect(() => setDrawn([]), [docId])

  /**
   * The temporary mark of a locate (design-00002 §16.6), put on the interval the
   * **buffer** now holds rather than the one the disk reported: with unsaved edits
   * the mapped trace is where that sentence has moved to.
   */
  // Two of the four clearing conditions, and they come **first**: a view state
  // switched and a document opened both put the mark away. Before the locate
  // below, because the one act that changes the view state *and* asks for a
  // locate is a locate from the list, and it has to end with the mark on.
  useEffect(() => {
    view.current?.dispatch({ effects: setLocated.of(undefined) })
  }, [mode, docId, opened])

  useEffect(() => {
    const current = view.current
    const target = layer?.locate
    if (current === null) return
    // The last of the four: the locate itself let go of — a press in the body, an
    // annotation gone from the payload. Said once and not on every render after
    // it, which is what `served` is counting.
    if (target === undefined) {
      if (served.current === NOTHING_LOCATED) return
      served.current = NOTHING_LOCATED
      current.dispatch({ effects: setLocated.of(undefined) })
      return
    }
    // And the mark itself, for a locate nobody has served yet: the payload object
    // is built afresh on every render of the board, so acting on its identity
    // would put the mark away again with the next read of the refresh path.
    if (served.current === target.askedAt) return
    served.current = target.askedAt
    locateInEditor(current, traceOf(current, target.id) ?? target.range)
  }, [layer?.locate, locateAskedAt, opened])

  // Coming back from the preview, typing should continue where it stopped, so the
  // editor takes focus again — its selection was never lost (spec-00001-FR-25).
  useEffect(() => {
    if (mode !== 'source') return
    // After the tab itself takes focus, hand it back to the editor.
    const handle = requestAnimationFrame(() => view.current?.focus())
    return () => cancelAnimationFrame(handle)
  }, [mode])

  /**
   * Preview and the ask list render beside the live buffer, never over it: the
   * editor is only hidden, so coming back finds every unsaved edit where it was
   * (spec-00001-FR-25, spec-00005-AC-9.2).
   */
  /**
   * The text the preview renders, taken whenever the preview comes up — from the
   * tab, or from the board asking for it, which is what a locate does
   * (design-00002 §16.6). Normalised **once**, here, and everything downstream —
   * the anchor cut from a preview selection, the front matter prefix, the
   * intervals drawn — reads that one text (design-00002 §16.3).
   */
  useEffect(() => {
    if (mode !== 'preview') return
    setPreview(normalized(view.current?.state.doc.toString() ?? ''))
  }, [mode, opened])

  const readEditor = () => (view.current === null ? undefined : editorAnchor(view.current))
  const readPreview = () =>
    previewHost.current === null ? undefined : previewAnchor(previewHost.current, preview)

  /**
   * The intervals the preview draws: the same set the editor holds, each lowered
   * over the front matter prefix — the one crossing point between the two
   * coordinate systems (design-00002 §16.3, §16.6).
   */
  const marks = useMemo(() => {
    const prefix = bodyPrefix(preview)
    const target =
      locateId === undefined || locateStart === undefined || locateEnd === undefined
        ? undefined
        : { id: locateId, range: { start: locateStart, end: locateEnd } }
    return previewMarks(drawn, target, (range) => toBodyRange(range, prefix))
  }, [drawn, preview, locateId, locateStart, locateEnd])
  /**
   * Which locate the preview is to scroll onto — the identity of the ask, not the
   * whole mark set. Keyed on the set, every refresh that rebuilt it would drag the
   * reader back to the last passage they located (design-00002 §16.6).
   */
  const scrollTo = locateId === undefined ? undefined : `${locateId}@${locateAskedAt}`

  /**
   * One annotation recorded, and its interval put straight into the local set:
   * an annotation made on an **unsaved** buffer is anchored in a text only this
   * browser holds, so no refresh can bring its trace back — the next clean
   * rebuild replaces it with the server's own reading (spec-00007-AC-1.3,
   * design-00002 §16.6).
   */
  async function add(input: {
    type: AnnotationType
    text: string
    anchor: SelectionAnchor
    range: SourceRange
  }): Promise<boolean> {
    if (layer === undefined) return false
    const created = await layer.onAdd({ type: input.type, text: input.text, anchor: input.anchor })
    if (created === undefined) return false
    const current = view.current
    if (current !== null) {
      const next = [...traces(current), { id: created, ...input.range }]
      current.dispatch({ effects: setTraces.of(next) })
      setDrawn(next)
    }
    return true
  }

  /** Either body, wrapped in the layer that takes the right-click when it has something to offer. */
  function annotated(body: ReactNode, read: () => Selected | undefined): ReactNode {
    // **Always** the same element around the body, layer or no layer. The
    // CodeMirror view is built once, in an effect that does not run again, so a
    // wrapper appearing around its host remounts that host and leaves the view
    // attached to a node nobody is showing — a Source view blank for good. With
    // no layer the area has both gates shut and nothing to read, which is the
    // same «offers nothing, takes no right-click» state an anomalous document is
    // in (spec-00007-AC-4.6).
    return (
      <AnnotateArea
        eligible={layer?.eligible ?? NO_TYPES}
        read={layer === undefined ? nothingSelected : read}
        onAdd={add}
        reanchor={layer?.reanchor}
        onReanchor={layer?.onReanchor ?? refuse}
        onLeaveLocate={layer?.onLeaveLocate ?? nothing}
      >
        {body}
      </AnnotateArea>
    )
  }

  async function save() {
    if (!opened || !view.current) return
    setSaving(true)
    const content = view.current.state.doc.toString()
    try {
      // Saving a prefilled buffer creates the document; saving an opened one
      // revises it. Two calls, one button — which one it is was settled when the
      // buffer was opened (spec-00001-FR-53).
      if (draft !== undefined) {
        await api.createDoc(docId, content)
        toast.success(`created ${docId}`)
      } else {
        await api.save(docId, content, opened.hash)
        toast.success(`saved ${docId}`)
        // The hash the save was made against is now the hash of nothing: the file
        // on disk has moved on, and a second save carrying the old one would meet
        // the conflict check as though somebody else had written the file. Read
        // back, so the buffer's base version is what is actually there — and the
        // notice about the disk having moved is settled by the same read.
        const saved = await api.doc(docId)
        setOpened(saved)
        setMoved(false)
      }
      // What was saved is what the buffer is now in step with, so a cowrite
      // reload of the same text is not read as a change to be refused
      // (spec-00006-AC-5.1).
      base.current = content
      // Saved, so the submit entry is free again and the traces may be rebuilt
      // from the next refresh's readings (design-00002 §16.5, §16.6).
      layer?.onUnsaved(false)
      onSaved()
    } catch (error) {
      const conflict = error instanceof ApiError && error.status === 409
      // A conflict is a different problem on each path, so it gets a different
      // way out: the co-write lock is about the session and reopening picks up
      // nothing, the file moved under a revision, the id is taken for a create.
      const wayOut =
        error instanceof ApiError && error.reason === 'doc-busy'
          ? CO_WRITE_LOCK
          : draft === undefined
            ? 'reopen it to pick up the change'
            : 'pick another slug'
      toast.error(error instanceof Error ? error.message : String(error), {
        description: conflict ? wayOut : undefined,
      })
    } finally {
      setSaving(false)
    }
  }

  return (
    <section aria-label={`Editing ${docId}`} className="flex h-full min-h-0 flex-col">
      <header className="flex items-center gap-2 border-b px-3 py-2">
        <span className="truncate font-mono text-xs font-medium">{docId}</span>

        <Tabs value={mode} onValueChange={(value) => onMode(value as EditorMode)} className="ml-2">
          <TabsList className="h-7">
            <TabsTrigger value="source" className="text-xs">
              <Code className="size-3.5" aria-hidden />
              Source
            </TabsTrigger>
            <TabsTrigger value="preview" className="text-xs">
              <Eye className="size-3.5" aria-hidden />
              Preview
            </TabsTrigger>
            {/* The third view state, beside the other two rather than in a slot
                of its own (spec-00005-FR-9, spec-00001-FR-31). */}
            <TabsTrigger value="asks" className="text-xs">
              <List className="size-3.5" aria-hidden />
              Questions
            </TabsTrigger>
            {/* And the fourth, beside the question list rather than merged into
                it: the two carry different fields, different gestures and state
                models that are not alike (design-00002 §16.1). */}
            {annotations === undefined ? null : (
              <TabsTrigger value="annotations" className="text-xs">
                <Highlighter className="size-3.5" aria-hidden />
                Annotations
              </TabsTrigger>
            )}
          </TabsList>
        </Tabs>

        {ask}

        {/* The lock says why it is there, next to what it disabled: a Save that
            is out and gives no reason cannot be told from a broken one
            (spec-00006-AC-4.3, the reading issue-00010 settled). */}
        {readOnly ? (
          <span className="text-muted-foreground ml-auto flex items-center gap-1 text-xs">
            <Lock className="size-3.5" aria-hidden />
            {CO_WRITE_LOCK}
          </span>
        ) : null}

        <Button size="sm" className={readOnly ? '' : 'ml-auto'} onClick={save} disabled={saving || readOnly}>
          {saving ? (
            <LoaderCircle className="size-4 animate-spin" aria-hidden />
          ) : (
            <Save className="size-4" aria-hidden />
          )}
          Save
        </Button>
        <Button variant="ghost" size="icon" aria-label="Close" onClick={onClose}>
          <X className="size-4" aria-hidden />
        </Button>
      </header>

      {/* Non-blocking: the buffer stays exactly as the user left it, and this
          says what happened underneath it (spec-00006-AC-4.5). */}
      {moved ? (
        <p role="status" className="text-muted-foreground border-b px-3 py-1.5 text-xs">
          {DISK_MOVED}
        </p>
      ) : null}

      <div
        className="min-h-0 flex-1 overflow-auto text-sm"
        hidden={mode !== 'source'}
        data-testid="editor-host"
      >
        {annotated(<div ref={host} />, readEditor)}
      </div>
      {mode === 'preview' ? (
        <div className="min-h-0 flex-1 overflow-auto">
          {annotated(
            <div ref={previewHost}>
              <Preview
              markdown={preview}
              marks={layer === undefined ? undefined : marks}
              scrollTo={scrollTo}
            />
            </div>,
            readPreview,
          )}
        </div>
      ) : null}
      {mode === 'asks' ? <div className="min-h-0 flex-1 overflow-auto">{asks}</div> : null}
      {mode === 'annotations' ? <div className="min-h-0 flex-1 overflow-auto">{annotations}</div> : null}
    </section>
  )
}

