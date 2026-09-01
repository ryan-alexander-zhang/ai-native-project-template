import { type Extension, StateEffect, StateField } from '@codemirror/state'
import { Decoration, type DecorationSet, EditorView } from '@codemirror/view'
import { type SourceRange, bufferOffset, normalizedOffset } from './annotationCoords.ts'
import { LOCATED_CLASS, MARK_CLASS, type PreviewMark } from './previewSourcePos.ts'

/**
 * One annotation interval in the editor, in the coordinates of the **normalised
 * file** — the one system the server, the editor and the preview share
 * (design-00002 §16.6).
 *
 * What makes CodeMirror's own offsets those coordinates is that CodeMirror
 * normalises line endings as it takes the document: `state.doc` never holds a
 * CR, so the buffer **is** the normalised text and the conversions below are the
 * identity on every file this board opens. They are written down all the same:
 * the day that stops being true — a configured `lineSeparator`, or offsets handed
 * in from a text that is not the buffer — every trace, locate and preview mark
 * would shift by one character per line above the passage, silently. The
 * invariant is pinned by a CRLF round trip in annotationMapping.test.tsx.
 */
export interface MarkRange extends SourceRange {
  id: string
}

/**
 * Rebuild the trace set from the server's readings. Dispatched only while the
 * buffer holds no unsaved change (design-00002 §16.6): with a dirty buffer the
 * set that is already there — mapped forward through every change — is the one
 * that holds, and the disk's offsets would draw the traces onto other sentences.
 */
export const setTraces = StateEffect.define<readonly MarkRange[]>()

/** Put the temporary «this is the one» mark somewhere, or take it away. */
export const setLocated = StateEffect.define<SourceRange | undefined>()

const locatedMark = Decoration.mark({ class: `${MARK_CLASS} ${LOCATED_CLASS}` })

/**
 * The traces of the unsubmitted, locatable annotations (spec-00007-AC-9.13),
 * held as a `RangeSet` and mapped forward with each change — CodeMirror's own
 * mapping, not a relocation algorithm of ours: the front end never re-anchors
 * anything (design-00002 §16.6).
 */
const traceField = StateField.define<DecorationSet>({
  create: () => Decoration.none,
  update(marks, transaction) {
    const rebuilt = transaction.effects.find((effect) => effect.is(setTraces))
    if (rebuilt !== undefined) return build(transaction.state.doc.toString(), rebuilt.value)
    return marks.map(transaction.changes)
  },
  provide: (field) => EditorView.decorations.from(field),
})

/**
 * The located mark, whose four clearing conditions design-00002 §16.6 writes
 * down: the next locate and a switch of view state or document each dispatch
 * over it, and the next document change clears it here. It is never mapped — a
 * change is precisely what ends it.
 */
const locatedField = StateField.define<DecorationSet>({
  create: () => Decoration.none,
  update(mark, transaction) {
    const next = transaction.effects.find((effect) => effect.is(setLocated))
    if (next === undefined) return transaction.docChanged ? Decoration.none : mark
    const range = next.value
    if (range === undefined) return Decoration.none
    const buffer = transaction.state.doc.toString()
    return Decoration.set([locatedMark.range(bufferOffset(buffer, range.start), bufferOffset(buffer, range.end))])
  },
  provide: (field) => EditorView.decorations.from(field),
})

export const annotationMarks: Extension = [traceField, locatedField]

/** Where an annotation's trace stands **now** — after every change it has been mapped through. */
export function traceOf(view: EditorView, id: string): SourceRange | undefined {
  return traces(view).find((range) => range.id === id)
}

/**
 * Every trace as it now stands, which is what a locally added one is appended to.
 * Read back in **file** coordinates, the same ones they went in as — see
 * {@link MarkRange} for why that is the buffer's own offsets — and in document
 * order, which is the `RangeSet`'s and not the order they were created in.
 */
export function traces(view: EditorView): MarkRange[] {
  const buffer = view.state.doc.toString()
  const kept: MarkRange[] = []
  view.state.field(traceField).between(0, view.state.doc.length, (from, to, value) => {
    kept.push({
      id: value.spec.id as string,
      start: normalizedOffset(buffer, from),
      end: normalizedOffset(buffer, to),
    })
  })
  return kept
}

/**
 * Whether two readings of one interval set say the same thing. **By id**, not by
 * position: one of the two comes off the payload in the order the annotations
 * were created and the other out of the `RangeSet` in the order they stand in the
 * document, so comparing them index by index reads any document whose two orders
 * differ as changed for ever — and the rebuild it guards then runs on every
 * refresh, redrawing every trace as it goes.
 */
export function sameRanges(left: readonly MarkRange[], right: readonly MarkRange[]): boolean {
  if (left.length !== right.length) return false
  const held = new Map(left.map((one) => [one.id, one]))
  return right.every((one) => {
    const other = held.get(one.id)
    return other !== undefined && other.start === one.start && other.end === one.end
  })
}

/**
 * Scroll the editor onto an interval and mark it (design-00002 §16.6). The
 * selection is put on it as well, so the keyboard carries on from what was found.
 */
export function locateInEditor(view: EditorView, range: SourceRange): void {
  const buffer = view.state.doc.toString()
  const from = bufferOffset(buffer, range.start)
  const to = bufferOffset(buffer, range.end)
  view.dispatch({
    selection: { anchor: from, head: to },
    effects: [setLocated.of(range), EditorView.scrollIntoView(from, { y: 'center' })],
  })
}

function build(buffer: string, ranges: readonly MarkRange[]): DecorationSet {
  return Decoration.set(
    ranges.map((range) =>
      Decoration.mark({ class: MARK_CLASS, id: range.id }).range(
        bufferOffset(buffer, range.start),
        bufferOffset(buffer, range.end),
      ),
    ),
    true,
  )
}

/**
 * The same intervals as the preview draws them: the traces, and the located one
 * beside them when it is not a trace already — a submitted annotation leaves no
 * trace, so locating it is the only mark it ever gets (design-00002 §16.6). The
 * conversion into body coordinates is handed in, so it stays the one crossing
 * point design-00002 §16.3 fixes.
 */
export function previewMarks(
  kept: readonly MarkRange[],
  located: { id: string; range: SourceRange } | undefined,
  toBody: (range: SourceRange) => SourceRange | undefined,
): PreviewMark[] {
  const marks = kept.flatMap((range) => {
    const body = toBody(range)
    return body === undefined ? [] : [{ ...body, ids: [range.id], trace: true, located: range.id === located?.id }]
  })
  if (located === undefined || kept.some((range) => range.id === located.id)) return marks
  const body = toBody(located.range)
  return body === undefined ? marks : [...marks, { ...body, ids: [located.id], trace: false, located: true }]
}
