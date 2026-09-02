import type { Element, ElementContent, Parents, Properties, Root, Text } from 'hast'

/**
 * One annotation interval as the preview draws it, in **body** coordinates
 * (design-00002 §16.3's subtraction has already run). `trace` is the visible
 * mark an unsubmitted, locatable annotation leaves (spec-00007-AC-9.13) and
 * `located` the temporary «this is the one you were looking for» mark
 * (design-00002 §16.6) — two styles, one element, so an unsubmitted annotation
 * being located gets one mark carrying both rather than two nested ones.
 */
export interface PreviewMark {
  start: number
  end: number
  ids: string[]
  trace: boolean
  located: boolean
}

/** The class the traces are styled by; the located one adds its own beside it. */
export const MARK_CLASS = 'annotation-mark'
export const LOCATED_CLASS = 'annotation-mark--located'

/**
 * The rehype plugin of design-00002 §16.3. remark hands every mdast node its
 * `position`, `mdast-util-to-hast` carries it into hast, and this walk writes
 * the two offsets out as `data-src-start` / `data-src-end` — which react-markdown
 * passes through to the DOM untouched (measured, not assumed: the plugin's own
 * test asserts the attributes are on the rendered element, the discipline §2 and
 * §4 were twice corrected by).
 *
 * Element offsets only reach the boundaries of block and inline markup, and a
 * selection lands **inside** text, so each text node is wrapped in a span of its
 * own carrying the same two offsets — except inside a `code` or `pre` subtree,
 * where `Preview`'s mermaid branch reads `String(children)` and a wrapped child
 * would make that `[object Object]`. Code is therefore unannotatable, which is
 * the same ruling from the other side (spec-00007-AC-1.6).
 */
export function rehypeSourcePos(marks: readonly PreviewMark[] = []) {
  return () => (tree: Root) => {
    position(tree, false, marks)
  }
}

function position(node: Root | Element, inCode: boolean, marks: readonly PreviewMark[]): void {
  const children: ElementContent[] = []
  for (const child of (node as Parents).children as ElementContent[]) {
    if (child.type === 'text') {
      children.push(...(inCode ? [child] : spans(child, marks)))
      continue
    }
    if (child.type === 'element') {
      stamp(child)
      position(child, inCode || child.tagName === 'code' || child.tagName === 'pre', marks)
    }
    children.push(child)
  }
  ;(node as Parents).children = children
}

/** The two offsets on an element that has a position to report. */
function stamp(element: Element): void {
  const start = element.position?.start.offset
  const end = element.position?.end.offset
  if (start === undefined || end === undefined) return
  element.properties = { ...element.properties, dataSrcStart: start, dataSrcEnd: end }
}

/**
 * One text node as the span that carries its offsets, with any annotation
 * interval over it cut out as a `mark`.
 *
 * The **1:1 test** decides whether the cutting may happen at all: a source
 * offset equals «span start + index in the span» only while
 * `end - start === value.length`. An escape (`\*`), an entity (`&amp;`) or a
 * tab expansion breaks that, and such a node is marked `data-src-exact="false"`
 * and taken **whole** — an anchor is a content anchor, and a slice off by a
 * character or two would silently anchor somewhere else.
 */
function spans(text: Text, marks: readonly PreviewMark[]): ElementContent[] {
  const start = text.position?.start.offset
  const end = text.position?.end.offset
  if (start === undefined || end === undefined) return [text]
  const exact = end - start === text.value.length
  const hits = marks.filter((mark) => mark.end > start && mark.start < end)
  const properties = {
    dataSrcStart: start,
    dataSrcEnd: end,
    ...(exact ? {} : { dataSrcExact: 'false' }),
  }
  return [span(properties, exact ? cut(text, start, hits) : whole(text, hits))]
}

/** A node the 1:1 test failed, so the whole of it is the interval or none of it is. */
function whole(text: Text, hits: readonly PreviewMark[]): ElementContent[] {
  return hits.length === 0 ? [text] : [mark(hits, [text])]
}

/**
 * The node split at the interval boundaries. Overlapping intervals therefore cut
 * at every boundary and the overlapped stretch carries every id it belongs to
 * (design-00002 §16.6).
 */
function cut(text: Text, start: number, hits: readonly PreviewMark[]): ElementContent[] {
  const end = start + text.value.length
  const cuts = [...new Set([start, end, ...hits.flatMap((hit) => [hit.start, hit.end])])]
    .filter((at) => at >= start && at <= end)
    .sort((left, right) => left - right)
  const pieces: ElementContent[] = []
  for (let index = 0; index < cuts.length - 1; index += 1) {
    const [from, to] = [cuts[index]!, cuts[index + 1]!]
    const piece: Text = { type: 'text', value: text.value.slice(from - start, to - start) }
    const over = hits.filter((hit) => hit.start <= from && hit.end >= to)
    pieces.push(over.length === 0 ? piece : mark(over, [piece]))
  }
  return pieces
}

function span(properties: Properties, children: ElementContent[]): Element {
  return { type: 'element', tagName: 'span', properties, children }
}

function mark(hits: readonly PreviewMark[], children: ElementContent[]): Element {
  // The base class is on **every** mark this plugin draws, whether or not any of
  // its hits is a trace: without it the element falls back to the browser's own
  // `mark` styling — black on yellow — which is unreadable in the dark theme and
  // is not one of this board's colours at all. The editor's located decoration
  // carries both classes for the same reason, and the two sides say the same
  // thing (design-00002 §16.6).
  const classes = [MARK_CLASS, ...(hits.some((hit) => hit.located) ? [LOCATED_CLASS] : [])]
  return {
    type: 'element',
    tagName: 'mark',
    properties: {
      className: classes,
      dataAnnotationIds: hits.flatMap((hit) => hit.ids).join(' '),
    },
    children,
  }
}
