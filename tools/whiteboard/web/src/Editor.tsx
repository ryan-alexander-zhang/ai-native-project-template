import { markdown } from '@codemirror/lang-markdown'
import { EditorView, basicSetup } from 'codemirror'
import { Code, Eye, LoaderCircle, Save, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { ApiError, type DocContent, api } from './api.ts'
import { Button } from '@/components/ui/button'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Preview } from './Preview.tsx'

export interface EditorProps {
  docId: string
  onSaved: () => void
  onClose: () => void
}

type View = 'source' | 'preview'

/** Edits the whole file, front matter included, and refuses to clobber a changed file. */
export function Editor({ docId, onSaved, onClose }: EditorProps) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView>(null)
  const [opened, setOpened] = useState<DocContent>()
  const [saving, setSaving] = useState(false)
  const [mode, setMode] = useState<View>('source')
  const [preview, setPreview] = useState('')

  useEffect(() => {
    let live = true
    api.doc(docId).then((content) => {
      if (live) setOpened(content)
    })
    return () => {
      live = false
    }
  }, [docId])

  useEffect(() => {
    if (!host.current || !opened) return
    view.current = new EditorView({
      doc: opened.content,
      extensions: [basicSetup, markdown()],
      parent: host.current,
    })
    return () => view.current?.destroy()
  }, [opened])

  // Coming back from the preview, typing should continue where it stopped, so the
  // editor takes focus again — its selection was never lost (spec-00001-FR-25).
  useEffect(() => {
    if (mode !== 'source') return
    // After the tab itself takes focus, hand it back to the editor.
    const handle = requestAnimationFrame(() => view.current?.focus())
    return () => cancelAnimationFrame(handle)
  }, [mode])

  /** Preview renders the live buffer, so switching back keeps unsaved edits. */
  function show(next: View) {
    if (next === 'preview') setPreview(view.current?.state.doc.toString() ?? '')
    setMode(next)
  }

  async function save() {
    if (!opened || !view.current) return
    setSaving(true)
    try {
      await api.save(docId, view.current.state.doc.toString(), opened.hash)
      toast.success(`saved ${docId}`)
      onSaved()
    } catch (error) {
      const conflict = error instanceof ApiError && error.status === 409
      toast.error(error instanceof Error ? error.message : String(error), {
        description: conflict ? 'reopen it to pick up the change' : undefined,
      })
    } finally {
      setSaving(false)
    }
  }

  return (
    <section aria-label={`Editing ${docId}`} className="flex h-full min-h-0 flex-col">
      <header className="flex items-center gap-2 border-b px-3 py-2">
        <span className="truncate font-mono text-xs font-medium">{docId}</span>

        <Tabs value={mode} onValueChange={(value) => show(value as View)} className="ml-2">
          <TabsList className="h-7">
            <TabsTrigger value="source" className="text-xs">
              <Code className="size-3.5" aria-hidden />
              Source
            </TabsTrigger>
            <TabsTrigger value="preview" className="text-xs">
              <Eye className="size-3.5" aria-hidden />
              Preview
            </TabsTrigger>
          </TabsList>
        </Tabs>

        <Button size="sm" className="ml-auto" onClick={save} disabled={saving}>
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

      <div className="min-h-0 flex-1 overflow-auto text-sm" hidden={mode === 'preview'} ref={host} data-testid="editor-host" />
      {mode === 'preview' ? (
        <div className="min-h-0 flex-1 overflow-auto">
          <Preview markdown={preview} />
        </div>
      ) : null}
    </section>
  )
}
