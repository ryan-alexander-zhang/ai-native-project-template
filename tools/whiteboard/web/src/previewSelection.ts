import type { SourceRange } from './annotationCoords.ts'

/**
 * What the user has selected in the preview, as an interval of the rendered
 * document's **body** source text (design-00002 §16.3). `undefined` is «not
 * mappable», and it covers every one of the three unmappable regions: a
 * selection inside a `code` or `pre` subtree, where the rehype plugin leaves no
 * character-level mapping; a position with no `[data-src-start]` ancestor at
 * all; and an empty selection, which selects nothing to anchor.
 *
 * Each end is read against **its own** span, so a selection spanning two blocks
 * comes back as the closure over them — which takes the Markdown markup between
 * the two ends into the selected text. That is honest: the anchor has to hold
 * against the source, and those characters really are between the two ends of it.
 */
export function previewSelection(root: HTMLElement): SourceRange | undefined {
  const selection = root.ownerDocument.defaultView?.getSelection()
  if (!selection || selection.rangeCount === 0) return undefined
  const range = selection.getRangeAt(0)
  if (range.collapsed) return undefined
  if (!root.contains(range.startContainer) || !root.contains(range.endContainer)) return undefined
  const start = edge(root, range.startContainer, range.startOffset, 'start')
  const end = edge(root, range.endContainer, range.endOffset, 'end')
  if (start === undefined || end === undefined || end <= start) return undefined
  return { start, end }
}

/** One end of the selection, in body coordinates, or `undefined` if it cannot be mapped. */
function edge(root: HTMLElement, container: Node, offset: number, side: 'start' | 'end'): number | undefined {
  const element = container.nodeType === Node.ELEMENT_NODE ? (container as HTMLElement) : container.parentElement
  if (element === null || !root.contains(element) || inCode(root, element)) return undefined
  const span = element.closest<HTMLElement>('[data-src-start]')
  if (span === null || !root.contains(span)) return undefined
  const from = Number(span.dataset.srcStart)
  const to = Number(span.dataset.srcEnd)
  // The 1:1 test failed on this node, so no character index inside it means
  // anything: the whole node is the interval (design-00002 §16.3).
  if (span.dataset.srcExact === 'false') return side === 'start' ? from : to
  return from + within(span, container, offset)
}

/**
 * How far into the span's own text the position stands — measured, not counted.
 *
 * Neither half of the pair can be read on its own: an interval already drawn in
 * the span has split its text into several nodes, so an offset against one piece
 * is short by everything before it; and when the container is an **element** the
 * offset is not a character index at all but a **child index**, so reading it as
 * one — or as «not zero, therefore the end» — widens the interval to the whole
 * node and the quote then carries source text the user never selected.
 *
 * A range from the start of the span to the position answers both at once, in the
 * DOM's own terms.
 */
function within(span: HTMLElement, container: Node, offset: number): number {
  const measure = span.ownerDocument.createRange()
  measure.setStart(span, 0)
  measure.setEnd(container, offset)
  return measure.toString().length
}

/** Whether a position sits in a code subtree, which carries no character mapping. */
function inCode(root: HTMLElement, element: HTMLElement): boolean {
  const code = element.closest('code, pre')
  return code !== null && root.contains(code)
}
