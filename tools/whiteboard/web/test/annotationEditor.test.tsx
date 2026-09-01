// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { EditorView } from 'codemirror'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CONTEXT_CODE_POINTS } from '../../src/annotationAnchor.ts'
import { api } from '../src/api.ts'
import type { MarkRange } from '../src/annotationMarks.ts'
import { type EditorAnnotate, Editor, type EditorMode } from '../src/Editor.tsx'
import { LOCATED_CLASS, MARK_CLASS } from '../src/previewSourcePos.ts'

const FRONT_MATTER = '---\nid: prd-00001-x\ntype: prd\nstatus: draft\n---\n'
const BODY = '\n## Context\n\nthe sentence that carries the anchor.\n'
const CONTENT = FRONT_MATTER + BODY
const ANCHOR_AT = CONTENT.indexOf('the anchor')
const TRACE: MarkRange = { id: 'n-1', start: ANCHOR_AT, end: ANCHOR_AT + 'the anchor'.length }

interface Harness {
  traces?: readonly MarkRange[]
  locate?: { id: string; range: { start: number; end: number } }
  eligible?: { question: boolean; issue: boolean }
  onAdd?: EditorAnnotate['onAdd']
  onUnsaved?: (unsaved: boolean) => void
  startOn?: EditorMode
  /** Whether the layer is attached from the start; the board attaches it once the payload lands. */
  startAttached?: boolean
}

/** The other document on the board, for the cases about switching between them. */
const OTHER_ID = 'idea-00001-x'
const OTHER_CONTENT = '---\nid: idea-00001-x\ntype: idea\nstatus: draft\n---\n\n## Elsewhere\n\nquite another sentence entirely.\n'

/**
 * The editor with its annotation layer, and the four things the board would
 * otherwise be driving: which view state is on show, which document is open,
 * whether the payload has arrived at all, and which annotation the locate is on
 * — the last carried with a counter, the way `useBoard` carries it, so that
 * locating the same row twice is two locates.
 */
function Editing({
  traces = [],
  locate,
  eligible,
  onAdd,
  onUnsaved,
  startOn = 'source',
  startAttached = true,
}: Harness) {
  const [mode, setMode] = useState<EditorMode>(startOn)
  const [docId, setDocId] = useState('prd-00001-x')
  const [attached, setAttached] = useState(startAttached)
  const [located, setLocated] = useState<{ id: string; askedAt: number }>()
  const layer: EditorAnnotate = {
    // The document the payload was read for. It is not always the one the editor
    // is on: a switch changes the editor's document at once and the payload
    // arrives a read later (design-00002 §16.8).
    docId: 'prd-00001-x',
    eligible: eligible ?? { question: true, issue: true },
    traces,
    ...(located === undefined || locate === undefined
      ? {}
      : { locate: { id: locate.id, range: locate.range, askedAt: located.askedAt } }),
    onAdd: onAdd ?? (async () => 'n-2'),
    onReanchor: async () => true,
    onLeaveLocate: () => setLocated(undefined),
    onUnsaved: onUnsaved ?? (() => {}),
  }
  return (
    <>
      <button
        type="button"
        onClick={() => setLocated((one) => ({ id: locate?.id ?? '', askedAt: (one?.askedAt ?? 0) + 1 }))}
      >
        locate from the list
      </button>
      <button type="button" onClick={() => setLocated(undefined)}>
        stop locating
      </button>
      <button type="button" onClick={() => setAttached(true)}>
        attach the layer
      </button>
      <button type="button" onClick={() => setDocId(OTHER_ID)}>
        open the other document
      </button>
      <Editor
        docId={docId}
        mode={mode}
        onMode={setMode}
        annotations={<p>the annotation list</p>}
        annotate={attached ? layer : undefined}
        onSaved={vi.fn()}
        onClose={vi.fn()}
      />
    </>
  )
}

async function open(harness: Harness = {}) {
  render(<Editing {...harness} />)
  await waitFor(() => expect(screen.getByTestId('editor-host').textContent).toContain('## Context'))
}

/** Let the effects of a render land. */
async function settled() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0))
  })
}

const editorMarks = () => Array.from(document.querySelectorAll(`.cm-content .${MARK_CLASS}`))
const previewMarkElements = () => Array.from(screen.getByTestId('preview').querySelectorAll('mark'))

beforeEach(() => {
  vi.spyOn(api, 'doc').mockImplementation(async (id: string) =>
    id === OTHER_ID
      ? { path: 'idea/a.md', content: OTHER_CONTENT, hash: 'hash-2' }
      : { path: 'prd/a.md', content: CONTENT, hash: 'hash-1' },
  )
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('the fourth view state', () => {
  /** spec-00007-FR-9 — the list is reachable from the document's own editor. */
  it('stands beside the other three', async () => {
    await open()

    expect(screen.getByRole('tab', { name: 'Annotations' })).toBeTruthy()
    await userEvent.click(screen.getByRole('tab', { name: 'Annotations' }))

    expect(screen.getByText('the annotation list')).toBeTruthy()
    expect(screen.getByTestId('editor-host').hidden).toBe(true)
  })

  /**
   * spec-00007-AC-9.7 — the buffer is only hidden while another view state is on
   * show, so every unsaved edit is where it was on the way back. The same
   * mechanism spec-00001-AC-25.1 rests on, extended to the fourth state.
   */
  it('keeps an unsaved buffer across the list and back', async () => {
    const save = vi.spyOn(api, 'save').mockResolvedValue({ committed: true })
    await open()
    await userEvent.click(screen.getByTestId('editor-host').querySelector('.cm-content')!)
    await userEvent.keyboard('edited ')

    await userEvent.click(screen.getByRole('tab', { name: 'Annotations' }))
    await userEvent.click(screen.getByRole('tab', { name: 'Source' }))

    expect(screen.getByTestId('editor-host').textContent).toContain('edited ')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(save.mock.calls[0]![1]).toContain('edited ')
  })

  /**
   * design-00002 §16.5 — the submit entry is the only judge of whether the buffer
   * is saved, so the editor is what tells it: dirty on the first change, and
   * clean again once the save has landed.
   */
  it('reports whether the buffer is saved', async () => {
    vi.spyOn(api, 'save').mockResolvedValue({ committed: true })
    const onUnsaved = vi.fn()
    await open({ onUnsaved })
    expect(onUnsaved).toHaveBeenLastCalledWith(false)

    await userEvent.click(screen.getByTestId('editor-host').querySelector('.cm-content')!)
    await userEvent.keyboard('X')
    expect(onUnsaved).toHaveBeenLastCalledWith(true)

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onUnsaved).toHaveBeenLastCalledWith(false))
  })
})

describe('the traces in the body', () => {
  /**
   * spec-00007-AC-9.13 — an unsubmitted, locatable annotation leaves a visible
   * mark in **both** the editor and the preview, drawn from one interval set: the
   * editor's is a CodeMirror decoration and the preview's a `mark` element, and
   * the preview's has crossed the front matter prefix on the way
   * (design-00002 §16.6).
   */
  it('marks the passage in the editor and in the preview', async () => {
    await open({ traces: [TRACE] })

    await waitFor(() => expect(editorMarks().map((one) => one.textContent)).toEqual(['the anchor']))

    await userEvent.click(screen.getByRole('tab', { name: 'Preview' }))
    expect(previewMarkElements().map((one) => one.textContent)).toEqual(['the anchor'])
    expect(previewMarkElements()[0]!.className).toContain(MARK_CLASS)
  })

  /**
   * The annotation payload lands **after** the editor is on screen — one read of
   * the refresh path — and again goes away when another document is opened. The
   * layer arriving must not restructure the body: the CodeMirror view is built
   * once, in an effect of its own, and a wrapper element swapped in around its
   * host remounts that host and leaves the view attached to a node nobody is
   * showing — a Source view that is blank for good.
   */
  it('keeps the editor mounted when the annotation layer arrives', async () => {
    render(<Editing startAttached={false} />)
    await waitFor(() => expect(screen.getByTestId('editor-host').textContent).toContain('## Context'))

    await userEvent.click(screen.getByRole('button', { name: 'attach the layer' }))

    expect(screen.getByTestId('editor-host').textContent).toContain('## Context')
    expect(screen.getByTestId('editor-host').querySelector('.cm-content')).toBeTruthy()
    // And the layer works on that same buffer.
    const content = screen.getByTestId('editor-host').querySelector<HTMLElement>('.cm-content')!
    EditorView.findFromDOM(content)!.dispatch({
      selection: { anchor: ANCHOR_AT, head: ANCHOR_AT + 'the anchor'.length },
    })
    fireEvent.mouseUp(content)
    expect(fireEvent.contextMenu(content)).toBe(false)
  })

  /**
   * design-00002 §16.6 — the intervals belong to the document they were read for.
   * Held on past a switch, they are drawn over whichever passage now stands at
   * those offsets in the next document: a phantom mark on text nobody annotated.
   */
  it('draws no trace of the document it was on before', async () => {
    await open({ traces: [TRACE] })
    await waitFor(() => expect(editorMarks()).toHaveLength(1))

    // The editor's document changes at once; the payload is still the last
    // document's until the next read lands.
    await userEvent.click(screen.getByRole('button', { name: 'open the other document' }))
    await waitFor(() => expect(screen.getByTestId('editor-host').textContent).toContain('## Elsewhere'))

    expect(editorMarks()).toHaveLength(0)
    await userEvent.click(screen.getByRole('tab', { name: 'Preview' }))
    expect(previewMarkElements()).toHaveLength(0)
  })

  /** An interval nothing was handed for leaves nothing drawn. */
  it('draws nothing when there is no interval', async () => {
    await open()

    expect(editorMarks()).toHaveLength(0)
    await userEvent.click(screen.getByRole('tab', { name: 'Preview' }))
    expect(previewMarkElements()).toHaveLength(0)
  })

  /**
   * design-00002 §16.6 — with unsaved edits the local set is kept and mapped
   * forward through the change rather than rebuilt from the disk's offsets, and
   * nothing is said about it: text typed **before** the passage moves the trace
   * along with it.
   */
  it('maps a trace forward through an unsaved change', async () => {
    await open({ traces: [TRACE] })
    await waitFor(() => expect(editorMarks()).toHaveLength(1))

    // Typed at the very start of the body, ahead of the marked passage.
    const content = screen.getByTestId('editor-host').querySelector('.cm-content')!
    await userEvent.click(content)
    await userEvent.keyboard('{Control>}{Home}{/Control}inserted ')

    // Still on the same words, not on whatever now stands at the old offsets.
    await waitFor(() => expect(editorMarks().map((one) => one.textContent)).toEqual(['the anchor']))
    await userEvent.click(screen.getByRole('tab', { name: 'Preview' }))
    expect(previewMarkElements().map((one) => one.textContent)).toEqual(['the anchor'])
  })
})

describe('locating an annotation', () => {
  /**
   * spec-00007-AC-9.6 — the editor is scrolled onto the passage and marks it. The
   * mark is a temporary one of its own, so it works for any locatable annotation
   * and not only for the unsubmitted ones that leave traces.
   */
  it('marks the passage in the editor', async () => {
    await open({ locate: { id: 'n-9', range: { start: TRACE.start, end: TRACE.end } } })

    await userEvent.click(screen.getByRole('button', { name: 'locate from the list' }))

    await waitFor(() => {
      const mark = document.querySelector(`.cm-content .${LOCATED_CLASS}`)
      expect(mark?.textContent).toBe('the anchor')
    })
  })

  /**
   * spec-00007-AC-9.12 — the same locate on the preview side: the interval is
   * lowered over the front matter prefix and the rendering is scrolled onto it.
   */
  it('marks the rendered passage in the preview', async () => {
    const scrolled = vi.spyOn(Element.prototype, 'scrollIntoView')
    await open({ startOn: 'preview', locate: { id: 'n-9', range: { start: TRACE.start, end: TRACE.end } } })

    await userEvent.click(screen.getByRole('button', { name: 'locate from the list' }))

    await waitFor(() => {
      const mark = screen.getByTestId('preview').querySelector('mark')
      expect(mark?.textContent).toBe('the anchor')
      expect(mark?.className).toContain(LOCATED_CLASS)
    })
    expect(scrolled).toHaveBeenCalled()
  })

  /**
   * The mark stays until one of design-00002 §16.6's four conditions clears it —
   * and a refresh is not one of them. The board re-renders on every read of the
   * refresh path, and a locate that undid itself on the next render would leave
   * the reader watching the passage they had just found go dark.
   */
  it('keeps the located mark across a refresh', async () => {
    const { rerender } = render(
      <Editing traces={[TRACE]} locate={{ id: 'n-1', range: { start: TRACE.start, end: TRACE.end } }} />,
    )
    await waitFor(() => expect(screen.getByTestId('editor-host').textContent).toContain('## Context'))
    await userEvent.click(screen.getByRole('button', { name: 'locate from the list' }))
    await waitFor(() => expect(document.querySelector(`.cm-content .${LOCATED_CLASS}`)).toBeTruthy())

    // A read lands: same locate, a payload object built afresh.
    rerender(<Editing traces={[TRACE]} locate={{ id: 'n-1', range: { start: TRACE.start, end: TRACE.end } }} />)
    await settled()

    expect(document.querySelector(`.cm-content .${LOCATED_CLASS}`)?.textContent).toBe('the anchor')
  })

  /**
   * design-00002 §16.6 — «the next locate» is one of the clearing conditions, so
   * a second locate of the **same** annotation has to be a second locate. Keyed
   * on the annotation alone it is a no-op, and once a change has cleared the mark
   * the reader can never ask for it back: the one entry that would restore it is
   * the one the key says has already been served.
   */
  it('locates the same annotation again after a change cleared the mark', async () => {
    await open({ traces: [TRACE], locate: { id: 'n-1', range: { start: TRACE.start, end: TRACE.end } } })
    await userEvent.click(screen.getByRole('button', { name: 'locate from the list' }))
    await waitFor(() => expect(document.querySelector(`.cm-content .${LOCATED_CLASS}`)).toBeTruthy())

    const content = screen.getByTestId('editor-host').querySelector<HTMLElement>('.cm-content')!
    EditorView.findFromDOM(content)!.dispatch({ changes: { from: 0, insert: 'X' } })
    expect(document.querySelector(`.cm-content .${LOCATED_CLASS}`)).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'locate from the list' }))

    await waitFor(() =>
      expect(document.querySelector(`.cm-content .${LOCATED_CLASS}`)?.textContent).toBe('the anchor'),
    )
  })

  /**
   * The preview is scrolled onto a locate and onto nothing else. Keyed on the
   * whole mark set, every refresh that rebuilds it — a trace added, a status
   * moving — would drag the reader back to the last passage they located, in the
   * middle of reading somewhere else.
   */
  it('does not scroll again when only the traces change', async () => {
    const scrolled = vi.spyOn(Element.prototype, 'scrollIntoView')
    const { rerender } = render(
      <Editing startOn="preview" traces={[TRACE]} locate={{ id: 'n-1', range: { start: TRACE.start, end: TRACE.end } }} />,
    )
    await waitFor(() => expect(screen.getByTestId('preview').textContent).toContain('Context'))
    await userEvent.click(screen.getByRole('button', { name: 'locate from the list' }))
    await waitFor(() => expect(screen.getByTestId('preview').querySelector('mark')).toBeTruthy())
    const once = scrolled.mock.calls.length

    // A second trace arrives with a refresh; the locate has not moved.
    rerender(
      <Editing
        startOn="preview"
        traces={[TRACE, { id: 'n-2', start: CONTENT.indexOf('Context'), end: CONTENT.indexOf('Context') + 7 }]}
        locate={{ id: 'n-1', range: { start: TRACE.start, end: TRACE.end } }}
      />,
    )
    await waitFor(() => expect(screen.getByTestId('preview').querySelectorAll('mark').length).toBe(2))

    expect(scrolled.mock.calls.length).toBe(once)
  })

  /**
   * design-00002 §16.6 — one of the located mark's four clearing conditions: the
   * next change of the document. It is a «here it is» and not a state of the
   * passage.
   */
  it('clears the located mark on the next change', async () => {
    await open({ locate: { id: 'n-9', range: { start: TRACE.start, end: TRACE.end } } })
    await userEvent.click(screen.getByRole('button', { name: 'locate from the list' }))
    await waitFor(() => expect(document.querySelector(`.cm-content .${LOCATED_CLASS}`)).toBeTruthy())

    // Typed through CodeMirror rather than by clicking first: a press in the body
    // is a clearing condition of its own (below), and this case is the change.
    const content = screen.getByTestId('editor-host').querySelector<HTMLElement>('.cm-content')!
    const editor = EditorView.findFromDOM(content)!
    editor.dispatch({ changes: { from: 0, insert: 'X' } })

    expect(document.querySelector(`.cm-content .${LOCATED_CLASS}`)).toBeNull()
  })

  /**
   * design-00002 §16.6's other clearing condition on this side: a press in the
   * body is a reader who has gone back to reading, and the «here it is» mark has
   * done its work. No timed fade — a mark that vanished by itself would lose the
   * reader the position they had just found.
   */
  it('clears the located mark on a press in the body', async () => {
    await open({ locate: { id: 'n-9', range: { start: TRACE.start, end: TRACE.end } } })
    await userEvent.click(screen.getByRole('button', { name: 'locate from the list' }))
    await waitFor(() => expect(document.querySelector(`.cm-content .${LOCATED_CLASS}`)).toBeTruthy())

    fireEvent.mouseDown(screen.getByTestId('editor-host').querySelector('.cm-content')!)

    await waitFor(() => expect(document.querySelector(`.cm-content .${LOCATED_CLASS}`)).toBeNull())
  })
})

describe('annotating the body itself', () => {
  /** The live buffer, and a selection put on it the way any CodeMirror command would. */
  function select(from: number, to: number): HTMLElement {
    const content = screen.getByTestId('editor-host').querySelector<HTMLElement>('.cm-content')!
    EditorView.findFromDOM(content)!.dispatch({ selection: { anchor: from, head: to } })
    return content
  }

  /**
   * spec-00007-AC-1.1 as the user meets it: a passage selected in the editing
   * state, the right-click offering the two types, and the confirmation recording
   * the anchor cut from that very selection — the selected text and the context
   * around it.
   */
  it('annotates a selection made in the editor', async () => {
    const onAdd = vi.fn<EditorAnnotate['onAdd']>().mockResolvedValue('n-2')
    await open({ onAdd })
    const content = select(ANCHOR_AT, ANCHOR_AT + 'the anchor'.length)

    fireEvent.mouseUp(content)
    expect(fireEvent.contextMenu(content)).toBe(false)
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Add a question annotation' }))
    fireEvent.change(await screen.findByLabelText('Annotation text'), { target: { value: 'is this still true?' } })
    await userEvent.click(screen.getByRole('button', { name: 'Annotate' }))

    expect(onAdd).toHaveBeenCalledWith({
      type: 'question',
      text: 'is this still true?',
      anchor: {
        selected: 'the anchor',
        // Cut from the **whole file**, front matter included: that is the one
        // coordinate system both view states share (design-00001 §12.2).
        before: CONTENT.slice(ANCHOR_AT - CONTEXT_CODE_POINTS, ANCHOR_AT),
        after: '.\n',
      },
    })
    // And its trace is in the set at once: the anchor may be over text only this
    // buffer holds, so no refresh could bring it back (spec-00007-AC-1.3).
    await waitFor(() => expect(editorMarks().map((one) => one.textContent)).toEqual(['the anchor']))
  })

  /**
   * spec-00007-AC-1.3 — a selection over text that has not been saved is
   * annotated like any other, and the anchor is cut from the buffer.
   */
  it('annotates text that has not been saved', async () => {
    const onAdd = vi.fn<EditorAnnotate['onAdd']>().mockResolvedValue('n-2')
    await open({ onAdd })
    const content = screen.getByTestId('editor-host').querySelector<HTMLElement>('.cm-content')!
    await userEvent.click(content)
    await userEvent.keyboard('{Control>}{End}{/Control}a brand new sentence.')
    const typed = EditorView.findFromDOM(content)!.state.doc.toString().indexOf('brand new')
    select(typed, typed + 'brand new'.length)

    fireEvent.mouseUp(content)
    fireEvent.contextMenu(content)
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Add an issue annotation' }))
    fireEvent.change(await screen.findByLabelText('Annotation text'), { target: { value: 'rewrite this' } })
    await userEvent.click(screen.getByRole('button', { name: 'Annotate' }))

    expect(onAdd).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'issue', anchor: expect.objectContaining({ selected: 'brand new' }) }),
    )
  })

  /**
   * spec-00007-AC-1.6 — the front matter and the fenced code regions take no
   * annotation on this side either, and the event is left alone over them. What
   * the two readers refuse is pinned in annotationMapping.test.tsx; this is the
   * entry answering to it.
   */
  it('offers nothing over the front matter', async () => {
    await open()
    const content = select(CONTENT.indexOf('prd-00001-x'), CONTENT.indexOf('prd-00001-x') + 5)

    fireEvent.mouseUp(content)

    expect(fireEvent.contextMenu(content)).toBe(true)
    expect(screen.queryByRole('menuitem', { name: /Add a/ })).toBeNull()
  })

  /**
   * design-00002 §16.2's «only when there is something to offer» has to hold at
   * the moment of the right-click. A selection the **locate** put there arrived
   * with no mouse release and no keystroke: gated on the last such event, the
   * menu is refused over a selection that is plainly on screen, and — worse — a
   * menu opened over a selection that has since moved would anchor the annotation
   * to the old one without a word.
   */
  it('offers the menu over a selection a locate made', async () => {
    const onAdd = vi.fn<EditorAnnotate['onAdd']>().mockResolvedValue('n-2')
    await open({ traces: [TRACE], locate: { id: 'n-1', range: { start: TRACE.start, end: TRACE.end } }, onAdd })
    await userEvent.click(screen.getByRole('button', { name: 'locate from the list' }))
    await waitFor(() => expect(document.querySelector(`.cm-content .${LOCATED_CLASS}`)).toBeTruthy())

    // No mouse release of the user's own: the selection is the locate's.
    const content = screen.getByTestId('editor-host').querySelector<HTMLElement>('.cm-content')!
    expect(fireEvent.contextMenu(content)).toBe(false)
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Add a question annotation' }))
    fireEvent.change(await screen.findByLabelText('Annotation text'), { target: { value: 'is this still true?' } })
    await userEvent.click(screen.getByRole('button', { name: 'Annotate' }))

    expect(onAdd).toHaveBeenCalledWith(
      expect.objectContaining({ anchor: expect.objectContaining({ selected: 'the anchor' }) }),
    )
  })

  /** spec-00007-AC-1.5 — no selection, so no application menu at all. */
  it('offers nothing with no selection', async () => {
    await open()
    const content = select(ANCHOR_AT, ANCHOR_AT)

    fireEvent.mouseUp(content)

    expect(fireEvent.contextMenu(content)).toBe(true)
    expect(screen.queryByRole('menu')).toBeNull()
  })
})
