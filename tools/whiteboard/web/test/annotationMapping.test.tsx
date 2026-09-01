// @vitest-environment jsdom
import { markdown } from '@codemirror/lang-markdown'
import { render, screen } from '@testing-library/react'
import { EditorView } from 'codemirror'
import { afterEach, describe, expect, it } from 'vitest'
import { CONTEXT_CODE_POINTS } from '../../src/annotationAnchor.ts'
import type { AnnotationListView } from '../../src/annotations.ts'
import type { AskThread } from '../src/api.ts'
import {
  bodyPrefix,
  bufferOffset,
  normalized,
  normalizedOffset,
  toBodyRange,
  toFileOffset,
} from '../src/annotationCoords.ts'
import {
  annotationMarks,
  locateInEditor,
  previewMarks,
  sameRanges,
  setTraces,
  traceOf,
  traces,
} from '../src/annotationMarks.ts'
import { annotationRows } from '../src/annotationRows.ts'
import { editorAnchor, previewAnchor } from '../src/annotationSelection.ts'
import { Preview } from '../src/Preview.tsx'
import { previewSelection } from '../src/previewSelection.ts'
import { frontMatterPrefix, stripFrontMatter } from '../src/frontMatter.ts'

const FRONT_MATTER = '---\nid: spec-00007-x\ntype: spec\nstatus: active\n---\n'
const BODY = '## Context\n\nthe sentence that carries the anchor.\n\n```ts\nconst a = 1\n```\n\nand `inline` too.\n'
const FILE = FRONT_MATTER + BODY

function view(doc: string): EditorView {
  return new EditorView({ doc, extensions: [markdown(), annotationMarks] })
}

let open: EditorView[] = []

function editorOn(doc: string): EditorView {
  const made = view(doc)
  open.push(made)
  return made
}

afterEach(() => {
  for (const one of open) one.destroy()
  open = []
})

/** Select a range in the rendered preview, the way a mouse drag leaves it. */
function selectDom(from: Node, start: number, to: Node, end: number): void {
  const range = document.createRange()
  range.setStart(from, start)
  range.setEnd(to, end)
  const selection = window.getSelection()!
  selection.removeAllRanges()
  selection.addRange(range)
}

/**
 * The text span at a body offset. Named as `span` on purpose: a block element
 * shares the offset its first text node starts at, and the character mapping is
 * the span's.
 */
const spanAt = (start: number) => screen.getByTestId('preview').querySelector(`span[data-src-start="${start}"]`)!

describe('the two coordinate systems', () => {
  /**
   * design-00002 §16.3 — the front matter prefix is computed once and used both
   * ways, and a case with front matter is what pins both directions: without one
   * a missing conversion is invisible, and with one it is the whole document
   * shifted.
   */
  it('converts both ways over one computation of the front matter prefix', () => {
    const prefix = bodyPrefix(FILE)
    expect(prefix).toBe(FRONT_MATTER.length)
    expect(frontMatterPrefix(FILE)).toBe(prefix)
    expect(stripFrontMatter(FILE)).toBe(BODY)

    // Body → file: the sentence stands where the addition says it does.
    const inBody = BODY.indexOf('the sentence')
    expect(FILE.slice(toFileOffset(inBody, prefix), toFileOffset(inBody + 12, prefix))).toBe('the sentence')
    // File → body: the same interval comes back to where the preview has it.
    expect(toBodyRange({ start: toFileOffset(inBody, prefix), end: toFileOffset(inBody + 12, prefix) }, prefix)).toEqual(
      { start: inBody, end: inBody + 12 },
    )
    // And an interval inside the front matter has no place in the preview at all.
    expect(toBodyRange({ start: 4, end: 6 }, prefix)).toBeUndefined()
  })

  /** design-00001 §12.2 — the one normalisation, applied on the reading side. */
  it('reads buffer offsets as offsets of the normalised text', () => {
    const buffer = 'one\r\ntwo\r\nthree'
    expect(normalized(buffer)).toBe('one\ntwo\nthree')
    expect(normalizedOffset(buffer, buffer.indexOf('three'))).toBe(normalized(buffer).indexOf('three'))
    expect(bufferOffset(buffer, normalized(buffer).indexOf('three'))).toBe(buffer.indexOf('three'))
    // With no CRLF it is the identity, which is the case this repo is in.
    expect(normalizedOffset(BODY, 12)).toBe(12)
    expect(bufferOffset(BODY, 12)).toBe(12)
  })
})

describe('the preview’s source positions', () => {
  /**
   * design-00002 §16.3's one measured premise: react-markdown passes a hast
   * `data-*` property through to the DOM untouched. Asserted rather than assumed,
   * the discipline §2 and §4 were twice corrected by.
   */
  it('writes the source offsets onto the elements and the text spans', () => {
    render(<Preview markdown={FILE} marks={[]} />)

    const heading = screen.getByRole('heading', { level: 2, name: 'Context' })
    expect(heading.dataset.srcStart).toBe('0')
    expect(heading.dataset.srcEnd).toBe(String('## Context'.length))
    const span = spanAt(BODY.indexOf('the sentence'))
    expect(span.textContent).toBe('the sentence that carries the anchor.')
    expect(span.tagName).toBe('SPAN')
  })

  /**
   * design-00002 §16.3 — no span inside a code subtree: `Preview`'s mermaid
   * branch reads `String(children)`, and a wrapped child makes that
   * `[object Object]`. The block still carries its own offsets; what it has no
   * more of is a character-level mapping.
   */
  it('leaves code subtrees unwrapped and mermaid’s source a string', () => {
    const { container } = render(<Preview markdown={FILE} marks={[]} />)
    const code = container.querySelector('pre code')!
    expect(code.querySelector('span')).toBeNull()
    expect((code as HTMLElement).dataset.srcStart).toBeDefined()

    render(<Preview markdown={'```mermaid\nflowchart TD\n  A-->B\n```\n'} marks={[]} />)
    expect(screen.getAllByTestId('mermaid').length).toBeGreaterThan(0)
  })

  /**
   * design-00002 §16.3's 1:1 test — an escape makes the rendered value shorter
   * than the source it came from, so no character index inside it means anything
   * and the node is taken whole.
   */
  it('marks a node the 1:1 test fails and takes it whole', () => {
    const source = 'a \\*starred\\* word\n'
    const { container } = render(<Preview markdown={source} marks={[]} />)
    const span = container.querySelector<HTMLElement>('p > span')!
    expect(span.textContent).toBe('a *starred* word')
    expect(span.dataset.srcExact).toBe('false')
  })

  /** spec-00007-AC-9.13 — the trace of an unsubmitted, locatable annotation. */
  it('draws a trace over the interval it is given', () => {
    const start = BODY.indexOf('carries')
    const { container } = render(
      <Preview
        markdown={FILE}
        marks={[{ start, end: start + 7, ids: ['n-1'], trace: true, located: false }]}
      />,
    )
    const mark = container.querySelector('mark')!
    expect(mark.textContent).toBe('carries')
    expect(mark.className).toContain('annotation-mark')
    expect(mark.getAttribute('data-annotation-ids')).toBe('n-1')
  })

  /**
   * design-00002 §16.6 — overlapping intervals cut at every boundary and the
   * overlapped stretch carries every id it belongs to; the located one adds its
   * own class to the mark that is already there rather than nesting a second.
   */
  it('cuts overlapping intervals at their boundaries', () => {
    const at = BODY.indexOf('sentence')
    const { container } = render(
      <Preview
        markdown={FILE}
        marks={[
          { start: at, end: at + 8, ids: ['n-1'], trace: true, located: false },
          { start: at + 4, end: at + 12, ids: ['n-2'], trace: true, located: true },
        ]}
      />,
    )
    const marks = Array.from(container.querySelectorAll('mark'))
    expect(marks.map((one) => one.textContent)).toEqual(['sent', 'ence', ' tha'])
    expect(marks[1]!.getAttribute('data-annotation-ids')).toBe('n-1 n-2')
    expect(marks[1]!.className).toContain('annotation-mark--located')
    expect(marks[0]!.className).not.toContain('annotation-mark--located')
  })

  /**
   * design-00002 §16.6 — locating a submitted annotation, which has no trace.
   * The mark still carries the base class: without it the element falls back to
   * the browser's own `mark` styling — black on yellow, unreadable in the dark
   * theme — and the editor's own located decoration carries both classes, which
   * is the reading this side is held to.
   */
  it('draws a located mark that still carries the base class', () => {
    const at = BODY.indexOf('anchor')
    const { container } = render(
      <Preview markdown={FILE} marks={[{ start: at, end: at + 6, ids: ['n-9'], trace: false, located: true }]} />,
    )
    const mark = container.querySelector('mark')!
    expect(Array.from(mark.classList)).toEqual(['annotation-mark', 'annotation-mark--located'])
  })
})

describe('reading a preview selection', () => {
  // spec-00007-AC-1.2 — the selection lands as an interval of the Markdown source
  it('maps a selection to the source interval it came from', () => {
    render(<Preview markdown={FILE} marks={[]} />)
    const span = spanAt(BODY.indexOf('the sentence'))
    selectDom(span.firstChild!, 4, span.firstChild!, 12)

    const body = previewSelection(screen.getByTestId('preview'))!
    expect(BODY.slice(body.start, body.end)).toBe('sentence')
    // And lifted over the prefix, the anchor is cut from the whole file.
    const selected = previewAnchor(screen.getByTestId('preview'), FILE)!
    expect(selected.anchor.selected).toBe('sentence')
    expect(FILE.slice(selected.range.start, selected.range.end)).toBe('sentence')
    expect(selected.anchor.before.endsWith('the ')).toBe(true)
  })

  /**
   * design-00002 §16.3 — two ends in two spans come back as the closure over
   * them, markup between the blocks included. That is honest: the anchor has to
   * hold against the source, where those characters really are between the ends.
   */
  it('takes the closure over a selection spanning two blocks', () => {
    render(<Preview markdown={FILE} marks={[]} />)
    const heading = spanAt(3)
    const paragraph = spanAt(BODY.indexOf('the sentence'))
    selectDom(heading.firstChild!, 0, paragraph.firstChild!, 3)

    const body = previewSelection(screen.getByTestId('preview'))!
    expect(BODY.slice(body.start, body.end)).toBe('Context\n\nthe')
  })

  /**
   * spec-00007-AC-1.6 — a selection inside a fenced code block maps to nothing:
   * the plugin leaves no character mapping there, so there is nothing to anchor.
   */
  it('refuses a selection inside a code block', () => {
    const { container } = render(<Preview markdown={FILE} marks={[]} />)
    const code = container.querySelector('pre code')!
    selectDom(code.firstChild!, 0, code.firstChild!, 5)

    expect(previewSelection(screen.getByTestId('preview'))).toBeUndefined()
    expect(previewAnchor(screen.getByTestId('preview'), FILE)).toBeUndefined()
  })

  // spec-00007-AC-1.5 — an empty selection selects nothing to anchor
  it('refuses an empty selection', () => {
    render(<Preview markdown={FILE} marks={[]} />)
    const span = spanAt(BODY.indexOf('the sentence'))
    selectDom(span.firstChild!, 4, span.firstChild!, 4)

    expect(previewSelection(screen.getByTestId('preview'))).toBeUndefined()
  })

  /** Nothing selected at all — the ordinary state of the page. */
  it('refuses when nothing is selected', () => {
    render(<Preview markdown={FILE} marks={[]} />)
    window.getSelection()!.removeAllRanges()

    expect(previewSelection(screen.getByTestId('preview'))).toBeUndefined()
  })

  /** A selection made somewhere else on the page is not this rendering's. */
  it('refuses a selection outside the rendering', () => {
    render(
      <>
        <p data-testid="elsewhere">some other text</p>
        <Preview markdown={FILE} marks={[]} />
      </>,
    )
    const outside = screen.getByTestId('elsewhere')
    selectDom(outside.firstChild!, 0, outside.firstChild!, 4)

    expect(previewSelection(screen.getByTestId('preview'))).toBeUndefined()
  })

  /**
   * design-00002 §16.3's third unmappable region: a position with no
   * `[data-src-start]` ancestor at all, which is what any rendering without the
   * plugin is made of.
   */
  it('refuses a position with no source offsets above it', () => {
    render(
      <div data-testid="plain">
        <p>rendered without the plugin</p>
      </div>,
    )
    const plain = screen.getByTestId('plain')
    selectDom(plain.querySelector('p')!.firstChild!, 0, plain.querySelector('p')!.firstChild!, 8)

    expect(previewSelection(plain)).toBeUndefined()
  })

  /**
   * A selection snapped to element boundaries rather than into a text node — what
   * a triple click or a select-all leaves.
   */
  it('reads a selection that ends on element boundaries', () => {
    render(<Preview markdown={FILE} marks={[]} />)
    const paragraph = screen.getByTestId('preview').querySelector('p')!
    selectDom(paragraph, 0, paragraph, paragraph.childNodes.length)

    const body = previewSelection(screen.getByTestId('preview'))!
    expect(BODY.slice(body.start, body.end)).toBe('the sentence that carries the anchor.')
  })

  /**
   * design-00002 §16.3 — a node the 1:1 test failed takes the whole node on both
   * ends, since no character index inside it means anything.
   */
  it('widens a selection inside a node the 1:1 test failed', () => {
    const source = 'a \\*starred\\* word\n'
    const { container } = render(<Preview markdown={source} marks={[]} />)
    const span = container.querySelector<HTMLElement>('p > span')!
    selectDom(span.firstChild!, 2, span.firstChild!, 9)

    const body = previewSelection(container.querySelector<HTMLElement>('.preview')!)!
    expect(body).toEqual({ start: 0, end: source.indexOf('\n') })
  })

  /**
   * An end that snapped to an **element** boundary inside a span of several
   * children: the offset is a child index, not a character index. Read as a
   * character index — or as «not zero, so the end of the span» — the interval is
   * widened to the whole node and the quote carries source text the user never
   * selected.
   */
  it('reads an end that snapped to a child boundary inside the span', () => {
    const at = BODY.indexOf('sentence')
    const { container } = render(
      <Preview markdown={FILE} marks={[{ start: at, end: at + 8, ids: ['n-1'], trace: true, located: false }]} />,
    )
    const span = container.querySelector<HTMLElement>(`span[data-src-start="${BODY.indexOf('the sentence')}"]`)!
    // 'the ' | <mark>sentence</mark> | ' that carries the anchor.'
    expect(span.childNodes).toHaveLength(3)
    selectDom(span, 0, span, 2)

    const body = previewSelection(container.querySelector<HTMLElement>('.preview')!)!
    expect(BODY.slice(body.start, body.end)).toBe('the sentence')
  })

  /**
   * A selection already carrying a mark has its text in several nodes, and an
   * offset read against one piece would be short by everything before it.
   */
  it('counts an offset over the whole span, marks included', () => {
    const at = BODY.indexOf('sentence')
    const { container } = render(
      <Preview markdown={FILE} marks={[{ start: at, end: at + 8, ids: ['n-1'], trace: true, located: false }]} />,
    )
    const after = container.querySelector('mark')!.nextSibling!
    selectDom(after, 1, after, 5)

    const body = previewSelection(screen.getByTestId('preview'))!
    expect(BODY.slice(body.start, body.end)).toBe('that')
  })
})

describe('the editor’s interval set', () => {
  const CRLF = '---\r\nid: prd-00001-x\r\n---\r\n\r\n## Context\r\n\r\nthe sentence that carries the anchor.\r\n'

  /**
   * design-00002 §16.6 — the set is held in **file** coordinates, the normalised
   * whole file's, because that is the one system the server, the editor and the
   * preview share.
   *
   * What makes the editor's own offsets those same coordinates is **CodeMirror's
   * own normalisation**: it converts every line ending as it takes the document,
   * so `state.doc` never holds a CR and the buffer is the normalised text by
   * construction. That is the invariant every conversion in `annotationMarks`
   * rests on, and it is asserted here rather than assumed — configure a
   * `lineSeparator`, or feed the set offsets of a text that is not the buffer,
   * and the whole round trip below shifts by one character per line above the
   * passage.
   */
  it('reads its intervals back in file coordinates over CRLF', () => {
    const editor = editorOn(CRLF)
    const file = normalized(CRLF)
    const at = file.indexOf('the anchor')
    // The premise: the buffer is already the normalised text, so a CRLF file's
    // offsets are the same on both sides of the seam.
    expect(editor.state.doc.toString()).toBe(file)
    expect(editor.state.doc.toString()).not.toContain('\r')
    expect(CRLF.indexOf('the anchor')).not.toBe(at)

    editor.dispatch({ effects: setTraces.of([{ id: 'n-1', start: at, end: at + 'the anchor'.length }]) })

    expect(traces(editor)).toEqual([{ id: 'n-1', start: at, end: at + 'the anchor'.length }])
    // And round trip: what comes back locates the very words it went in for.
    locateInEditor(editor, traceOf(editor, 'n-1')!)
    const { from, to } = editor.state.selection.main
    expect(editor.state.sliceDoc(from, to)).toBe('the anchor')
    // The same offsets, lowered for the preview, still name that passage.
    const prefix = bodyPrefix(file)
    const drawn = previewMarks(traces(editor), undefined, (range) => toBodyRange(range, prefix))
    expect(stripFrontMatter(file).slice(drawn[0]!.start, drawn[0]!.end)).toBe('the anchor')
  })

  /**
   * The guard in front of a rebuild compares two readings of the same set — one
   * in the order the payload lists them, one in the order they stand in the
   * document. Compared by position, two annotations whose creation order is not
   * their document order read as different for ever, and every refresh rebuilds
   * and redraws the lot.
   */
  it('compares two readings of one set regardless of their order', () => {
    const first = { id: 'n-1', start: 90, end: 100 }
    const second = { id: 'n-2', start: 20, end: 30 }
    expect(sameRanges([first, second], [second, first])).toBe(true)
    expect(sameRanges([first, second], [second, { ...first, start: 91 }])).toBe(false)
    expect(sameRanges([first], [first, second])).toBe(false)
  })
})

describe('the intervals the preview is handed', () => {
  const prefix = bodyPrefix(FILE)
  const toBody = (range: { start: number; end: number }) => toBodyRange(range, prefix)
  const at = FILE.indexOf('the anchor')
  const trace = { id: 'n-1', start: at, end: at + 10 }

  /**
   * design-00002 §16.6 — the traces, lowered over the front matter prefix, with
   * the located one among them marked rather than drawn twice.
   */
  it('lowers the traces and marks the located one among them', () => {
    expect(previewMarks([trace], { id: 'n-1', range: trace }, toBody)).toEqual([
      { start: at - prefix, end: at + 10 - prefix, ids: ['n-1'], trace: true, located: true },
    ])
  })

  /**
   * A submitted annotation leaves no trace, so locating it is the only mark it
   * gets — added beside the traces rather than folded into one of them.
   */
  it('adds the located one when it is no trace', () => {
    expect(previewMarks([], { id: 'n-9', range: trace }, toBody)).toEqual([
      { start: at - prefix, end: at + 10 - prefix, ids: ['n-9'], trace: false, located: true },
    ])
    expect(previewMarks([trace], undefined, toBody)).toEqual([
      { start: at - prefix, end: at + 10 - prefix, ids: ['n-1'], trace: true, located: false },
    ])
  })

  /**
   * An interval that lowers into the front matter has no place in the preview, so
   * nothing is drawn for it — neither as a trace nor as a locate.
   */
  it('draws nothing for an interval inside the front matter', () => {
    const inFrontMatter = { id: 'n-2', start: 4, end: 9 }
    expect(previewMarks([inFrontMatter], undefined, toBody)).toEqual([])
    expect(previewMarks([], { id: 'n-2', range: inFrontMatter }, toBody)).toEqual([])
  })
})

describe('reading an editor selection', () => {
  /**
   * spec-00007-AC-1.1 — the anchor is the selected text and the context on either
   * side, cut from the normalised whole file, front matter included.
   */
  it('cuts the anchor and its context from the whole file', () => {
    const editor = editorOn(FILE)
    const at = FILE.indexOf('the anchor')
    editor.dispatch({ selection: { anchor: at, head: at + 10 } })

    const selected = editorAnchor(editor)!
    expect(selected.anchor.selected).toBe('the anchor')
    expect(selected.range).toEqual({ start: at, end: at + 10 })
    expect([...selected.anchor.before].length).toBeLessThanOrEqual(CONTEXT_CODE_POINTS)
    expect(FILE.slice(at - selected.anchor.before.length, at)).toBe(selected.anchor.before)
  })

  /**
   * spec-00007-AC-1.3 — a selection over text that has not been saved is
   * annotated like any other: the anchor holds against the buffer, which is the
   * only place that text exists.
   */
  it('anchors a selection over unsaved text', () => {
    const editor = editorOn(FILE)
    const at = FILE.indexOf('## Context')
    editor.dispatch({ changes: { from: at, insert: 'a brand new sentence.\n\n' } })
    const typed = editor.state.doc.toString().indexOf('brand new')
    editor.dispatch({ selection: { anchor: typed, head: typed + 9 } })

    const selected = editorAnchor(editor)!
    expect(selected.anchor.selected).toBe('brand new')
    expect(editor.state.doc.toString().slice(selected.range.start, selected.range.end)).toBe('brand new')
  })

  // spec-00007-AC-1.5 — no selection, nothing to offer
  it('refuses an empty selection', () => {
    const editor = editorOn(FILE)
    editor.dispatch({ selection: { anchor: 60, head: 60 } })
    expect(editorAnchor(editor)).toBeUndefined()
  })

  /**
   * spec-00007-AC-1.6 — the unannotatable regions, read on this side off the
   * buffer: the front matter interval, and the code regions off the Lezer tree.
   * The same front matter text and the same fenced block the preview side refuses
   * above, which is what keeps the two implementations on one rule.
   */
  it('refuses the front matter and the code regions', () => {
    const editor = editorOn(FILE)
    const inFrontMatter = FILE.indexOf('spec-00007-x')
    editor.dispatch({ selection: { anchor: inFrontMatter, head: inFrontMatter + 5 } })
    expect(editorAnchor(editor)).toBeUndefined()

    const inFence = FILE.indexOf('const a = 1')
    editor.dispatch({ selection: { anchor: inFence, head: inFence + 5 } })
    expect(editorAnchor(editor)).toBeUndefined()

    const inInline = FILE.indexOf('inline')
    editor.dispatch({ selection: { anchor: inInline, head: inInline + 6 } })
    expect(editorAnchor(editor)).toBeUndefined()
  })

  /**
   * design-00002 §16.2 — the judgment is of the **buffer**: a fence just typed
   * counts at once, where the payload from the disk would still say otherwise.
   */
  it('judges a fence that has only just been typed', () => {
    const editor = editorOn(FILE)
    const at = FILE.length
    editor.dispatch({ changes: { from: at, insert: '\n```\njust typed\n```\n' } })
    const typed = editor.state.doc.toString().indexOf('just typed')
    editor.dispatch({ selection: { anchor: typed, head: typed + 4 } })

    expect(editorAnchor(editor)).toBeUndefined()
  })
})

function listView(overrides: Partial<AnnotationListView> = {}): AnnotationListView {
  return {
    annotations: [],
    batches: [],
    submitPreview: {
      questions: 0,
      issues: 0,
      willTransitionTo: null,
      issueEligible: true,
      questionEligible: true,
    },
    ...overrides,
  }
}

function annotation(overrides: Partial<AnnotationListView['annotations'][number]> = {}) {
  return {
    id: 'n-1',
    type: 'question' as const,
    text: 'why this?',
    anchor: { selected: 'the anchor', before: '', after: '' },
    quote: 'the anchor',
    createdAt: '2026-09-01T09:00:00.000Z',
    state: 'pending' as const,
    locate: { start: 10, end: 20 },
    ...overrides,
  }
}

function thread(overrides: Partial<AskThread> = {}): AskThread {
  return {
    id: 't-1',
    agent: 'claude',
    exchanges: [{ question: 'why this?', askedAt: '2026-09-01T09:00:00.000Z', outcome: 'running', runSessionId: 's1' }],
    ...overrides,
  }
}

describe('synthesising the list', () => {
  /**
   * design-00002 §16.4 — the three payloads, each part off the one place that owns
   * it, and the order they were created in with the unsubmitted ones mixed among
   * the rest (spec-00007-AC-9.1).
   */
  it('reads each part off the payload that owns it', () => {
    const rows = annotationRows(
      listView({
        annotations: [
          annotation({ id: 'n-1' }),
          annotation({ id: 'n-2', state: 'submitted', threadId: 't-1' }),
          annotation({ id: 'n-3', type: 'issue', state: 'submitted', batchId: 'b-1' }),
        ],
        batches: [
          { id: 'b-1', status: 'cowriting', sessionId: 's9', annotationIds: ['n-3'], startedAt: 'now' },
        ],
      }),
      [thread({ id: 't-1', exchanges: [{ ...thread().exchanges[0]!, outcome: 'answered', answer: 'because' }] })],
    )

    expect(rows.map((row) => row.state)).toEqual(['pending', 'answered', 'cowriting'])
    expect(rows.map((row) => row.action)).toEqual(['locate', 'thread', 'session'])
    expect(rows[2]!.sessionId).toBe('s9')
    expect(rows.every((row) => row.quote === 'the anchor')).toBe(true)
  })

  /**
   * spec-00007-AC-9.10 — a question whose first call has only just gone reads
   * running: the annotation payload is the faster of the two reads, and the
   * thread is not in the other one yet.
   */
  it('reads a question with no thread payload yet as running', () => {
    const rows = annotationRows(
      listView({ annotations: [annotation({ state: 'submitted', threadId: 't-7' })] }),
      [],
    )
    expect(rows[0]!.state).toBe('running')
  })

  /**
   * spec-00007-AC-9.11, AC-9.14 — the row mirrors the thread's **last** exchange,
   * so a stopped call reads terminated and a resend carries the row back to
   * running with no write-back path at all.
   */
  it('mirrors the thread’s last exchange, resend included', () => {
    const view = listView({ annotations: [annotation({ state: 'submitted', threadId: 't-1' })] })
    const first = thread().exchanges[0]!
    expect(annotationRows(view, [thread({ exchanges: [{ ...first, outcome: 'terminated' }] })])[0]!.state).toBe(
      'terminated',
    )
    expect(annotationRows(view, [thread({ exchanges: [{ ...first, outcome: 'failed' }] })])[0]!.state).toBe('failed')
    expect(
      annotationRows(view, [thread({ exchanges: [{ ...first, outcome: 'failed' }, { ...first, outcome: 'running' }] })])[
        0
      ]!.state,
    ).toBe('running')
  })

  /**
   * spec-00007-AC-9.4, AC-9.5 — a finished batch's collapse commit, and the
   * «no landed change» branch of it.
   */
  it('carries a finished batch’s commit, or says there was no change', () => {
    const done = (commit: string | null) =>
      annotationRows(
        listView({
          annotations: [annotation({ id: 'n-3', type: 'issue', state: 'submitted', batchId: 'b-1' })],
          batches: [
            { id: 'b-1', status: 'done', sessionId: 's9', annotationIds: ['n-3'], startedAt: 'now', commit },
          ],
        }),
        [],
      )[0]!
    expect(done('0f1e2d3c4b5a6978')).toMatchObject({ state: 'done', commit: '0f1e2d3c4b5a6978', action: 'locate' })
    expect(done(null)).toMatchObject({ state: 'done', commit: null })
  })

  /**
   * spec-00007-FR-10 — a batch that was stopped or failed hands its annotations
   * back. Its `batchId` is cleared with it, so membership in the batch row is
   * what the note is read off (design-00002 §16.6).
   */
  it('says which batch handed an annotation back', () => {
    const rows = annotationRows(
      listView({
        annotations: [annotation({ id: 'n-3', type: 'issue' })],
        batches: [
          { id: 'b-1', status: 'terminated', sessionId: 's8', annotationIds: ['n-3'], startedAt: 'now' },
        ],
      }),
      [],
    )
    expect(rows[0]).toMatchObject({ state: 'pending', handedBack: 'terminated', action: 'locate' })
  })

  /**
   * design-00002 §16.4 — the failure marks are read off the freshly computed
   * `locate` and never off the stored `orphan` flag: an unsubmitted one is an
   * orphan with nowhere to click, and a submitted one has only «the source has
   * changed» while its state stays exactly what it was
   * (spec-00007-AC-12.1 … AC-12.3).
   */
  it('separates an orphan from a submitted anchor that no longer lands', () => {
    const rows = annotationRows(
      listView({
        annotations: [
          annotation({ id: 'n-1', locate: { failed: 'ambiguous' }, orphan: 'missing' }),
          annotation({ id: 'n-2', state: 'submitted', threadId: 't-1', locate: { failed: 'missing' } }),
          annotation({
            id: 'n-3',
            type: 'issue',
            state: 'submitted',
            batchId: 'b-1',
            locate: { failed: 'missing' },
          }),
        ],
        batches: [
          { id: 'b-1', status: 'done', sessionId: 's9', annotationIds: ['n-3'], startedAt: 'now', commit: 'abc1234' },
        ],
      }),
      [thread({ exchanges: [{ ...thread().exchanges[0]!, outcome: 'answered', answer: 'because' }] })],
    )

    // The reading is `locate`'s, not the stored flag's.
    expect(rows[0]).toMatchObject({ state: 'pending', orphan: 'ambiguous', changed: false, action: 'none' })
    expect(rows[1]).toMatchObject({ state: 'answered', changed: true, action: 'thread' })
    expect(rows[2]).toMatchObject({ state: 'done', changed: true, commit: 'abc1234', action: 'none' })
  })
})
