import { markdown } from '@codemirror/lang-markdown'
import { EditorView, basicSetup } from 'codemirror'
import { useEffect, useRef, useState } from 'react'
import { ApiError, type DocContent, api } from './api.ts'

export interface EditorProps {
  docId: string
  onSaved: () => void
  onClose: () => void
}

/** Edits the whole file, front matter included, and refuses to clobber a changed file. */
export function Editor({ docId, onSaved, onClose }: EditorProps) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView>(null)
  const [opened, setOpened] = useState<DocContent>()
  const [message, setMessage] = useState('')

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

  async function save() {
    if (!opened || !view.current) return
    try {
      await api.save(docId, view.current.state.doc.toString(), opened.hash)
      setMessage('saved')
      onSaved()
    } catch (error) {
      setMessage(
        error instanceof ApiError && error.status === 409
          ? `${error.message} — reopen it to pick up the change`
          : String(error instanceof Error ? error.message : error),
      )
    }
  }

  return (
    <section className="panel" aria-label={`Editing ${docId}`}>
      <header className="panel__head">
        <strong>{docId}</strong>
        <button type="button" onClick={save}>
          Save
        </button>
        <button type="button" onClick={onClose}>
          Close
        </button>
      </header>
      <div className="panel__body" ref={host} data-testid="editor-host" />
      {message ? <p className="panel__message">{message}</p> : null}
    </section>
  )
}
