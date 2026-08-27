import { markdown } from '@codemirror/lang-markdown'
import { Compartment } from '@codemirror/state'
import { EditorView, basicSetup } from 'codemirror'
import { Code, Eye, List, LoaderCircle, Lock, Save, X } from 'lucide-react'
import { type ReactNode, useEffect, useMemo, useRef, useState } from 'react'
import { toast } from 'sonner'
import { ApiError, type DocContent, api } from './api.ts'
import { Button } from '@/components/ui/button'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Preview } from './Preview.tsx'

/**
 * The editor's three view states (spec-00005-FR-9): the text, its rendering, and
 * the document's ask list. Which one is on show is the board's presentation
 * state rather than the editor's own — a session panel row and a desktop
 * notification both set it from outside (design-00002 §14).
 */
export type EditorMode = 'source' | 'preview' | 'asks'

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
export function Editor({ docId, draft, mode, onMode, ask, asks, disk, readOnly = false, onSaved, onClose }: EditorProps) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView>(null)
  const [opened, setOpened] = useState<DocContent>()
  const [saving, setSaving] = useState(false)
  const [preview, setPreview] = useState('')
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

  useEffect(() => {
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
      extensions: [basicSetup, markdown(), editable.of(EditorView.editable.of(!readOnly))],
      parent: host.current,
    })
    return () => view.current?.destroy()
    // `readOnly` is deliberately not a dependency: it is reconfigured below, and
    // remounting on it would take the unsaved buffer with it (spec-00006-AC-4.7).
  }, [opened, editable])

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
  function show(next: EditorMode) {
    if (next === 'preview') setPreview(view.current?.state.doc.toString() ?? '')
    onMode(next)
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
      }
      // What was saved is what the buffer is now in step with, so a cowrite
      // reload of the same text is not read as a change to be refused
      // (spec-00006-AC-5.1).
      base.current = content
      onSaved()
    } catch (error) {
      const conflict = error instanceof ApiError && error.status === 409
      // A conflict is a different problem on each path, so it gets a different
      // way out: the file moved under a revision, the id is taken for a create.
      const wayOut = draft === undefined ? 'reopen it to pick up the change' : 'pick another slug'
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

        <Tabs value={mode} onValueChange={(value) => show(value as EditorMode)} className="ml-2">
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

      <div className="min-h-0 flex-1 overflow-auto text-sm" hidden={mode !== 'source'} ref={host} data-testid="editor-host" />
      {mode === 'preview' ? (
        <div className="min-h-0 flex-1 overflow-auto">
          <Preview markdown={preview} />
        </div>
      ) : null}
      {mode === 'asks' ? <div className="min-h-0 flex-1 overflow-auto">{asks}</div> : null}
    </section>
  )
}
