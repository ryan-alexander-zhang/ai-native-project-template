import { syntaxTree } from '@codemirror/language'
import type { EditorView } from '@codemirror/view'
import { type SelectionAnchor, anchorAt } from '../../src/annotationAnchor.ts'
import {
  type SourceRange,
  bodyPrefix,
  normalized,
  normalizedOffset,
  toFileOffset,
} from './annotationCoords.ts'
import { previewSelection } from './previewSelection.ts'

/**
 * The Lezer node names a code region goes by in `@codemirror/lang-markdown`:
 * a fenced block, an indented one, the text inside either, and inline code
 * (design-00002 §16.2).
 */
const CODE_NODES = new Set(['FencedCode', 'CodeBlock', 'CodeText', 'InlineCode'])

/**
 * What one side hands over when the user has selected something annotatable: the
 * anchor cut from the normalised whole file, and the interval it was cut at. The
 * interval is only ever used to slice and to draw — **it is not stored**, because
 * an anchor is a content anchor and where it lands is the server's to work out
 * afresh (design-00002 §16.3).
 */
export interface Selected {
  anchor: SelectionAnchor
  range: SourceRange
}

/**
 * The editor's selection as an annotatable one, or `undefined` when it is not
 * (design-00002 §16.2): an empty selection, or one either end of which falls in
 * an unannotatable region.
 *
 * The two regions are the preview's own, read on this side from the **buffer**
 * rather than from the payload:
 *
 * - the **front matter interval** `[0, prefix)`, the prefix taken from its one
 *   computation and not asked for a second time here;
 * - the **code regions**, read off CodeMirror's Lezer tree. The subject of the
 *   judgment is the buffer and not the disk — a fence the user has just typed
 *   has to count at once — and the tree is already there, so this adds no
 *   contract, no re-read, and no second source to drift from the disk.
 */
export function editorAnchor(view: EditorView): Selected | undefined {
  const { from, to } = view.state.selection.main
  if (from === to) return undefined
  // Each end resolved **into** the selection, so a fence the selection merely
  // touches from outside is not read as containing it.
  if (inCode(view, from, 1) || inCode(view, to, -1)) return undefined
  const buffer = view.state.doc.toString()
  const text = normalized(buffer)
  const range = { start: normalizedOffset(buffer, from), end: normalizedOffset(buffer, to) }
  if (range.start < bodyPrefix(text)) return undefined
  return { anchor: anchorAt(text, range.start, range.end), range }
}

/**
 * The preview's selection as an annotatable one. The rendering is the body alone,
 * so the mapping's body offsets are lifted into file coordinates over the front
 * matter prefix — the addition of design-00002 §16.3, whose subtraction the marks
 * take the other way, both off the one computation of that prefix.
 */
export function previewAnchor(host: HTMLElement, wholeFile: string): Selected | undefined {
  const body = previewSelection(host)
  if (body === undefined) return undefined
  const prefix = bodyPrefix(wholeFile)
  const range = { start: toFileOffset(body.start, prefix), end: toFileOffset(body.end, prefix) }
  return { anchor: anchorAt(wholeFile, range.start, range.end), range }
}

function inCode(view: EditorView, at: number, side: 1 | -1): boolean {
  let node: ReturnType<typeof syntaxTree>['topNode'] | null = syntaxTree(view.state).resolveInner(at, side)
  while (node !== null) {
    if (CODE_NODES.has(node.name)) return true
    node = node.parent
  }
  return false
}
