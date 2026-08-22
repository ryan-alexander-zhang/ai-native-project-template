import type { ReactNode } from 'react'
import Markdown, { type Components, type ExtraProps } from 'react-markdown'
import remarkGfm from 'remark-gfm'

export interface InlineMarkdownProps {
  text: string
  /**
   * Every resolvable id → its owning document (spec-00001-FR-57), straight off
   * the graph payload. Together with `onJump` it makes a code span whose whole
   * content is one of these ids clickable; leave either out and every code span
   * renders as it always did.
   */
  idOwners?: Record<string, string>
  /** Jump to the owning document — the board's own focus path, never a link out. */
  onJump?: (docId: string) => void
}

/**
 * Requirement text rendered as *inline* Markdown and nothing more
 * (spec-00001-FR-39). It is the same react-markdown pipeline the preview uses
 * (design-00002 §9), with three deliberate narrowings:
 *
 * - only the inline elements are kept — code, strong and em carry their style;
 * - every block construct falls back to the source characters it was written
 *   with, so a heading or a fence reads as text and produces no block element;
 * - links and images degrade to text: no navigable anchor, no `img`, and so no
 *   request to anywhere (spec-00001-AC-39.6).
 *
 * `rehype-raw` stays off, for the reason FR-24 already gives: raw HTML in a
 * document is neither injected nor run.
 *
 * The fifteenth round adds the one enrichment: a code span that *is* a
 * resolvable id becomes a button to its owning document (spec-00001-FR-57) —
 * a board-internal act, not the link element FR-39 degrades.
 */
export function InlineMarkdown({ text, idOwners, onJump }: InlineMarkdownProps) {
  return (
    <Markdown remarkPlugins={[remarkGfm]} components={inlineOnly(text, idOwners, onJump)}>
      {text}
    </Markdown>
  )
}

/**
 * A block element renders as the slice of source it was parsed from — the way
 * to drop the element without dropping the characters the author typed
 * (spec-00001-AC-39.4).
 */
function sourceOf(source: string) {
  return function Source({ node }: ExtraProps) {
    const position = node?.position
    return <>{position === undefined ? null : source.slice(position.start.offset, position.end.offset)}</>
  }
}

/**
 * A code span exactly one resolvable id long, and nothing else: prose ids are
 * backticked by convention (decision-00005 §4), so recognition reaches no
 * further than the inline code — a span carrying anything beside the id, or an
 * id outside backticks, stays plain text (spec-00001-FR-58). The table's keys
 * are whole ids, so the lookup is the exact-match test.
 */
function jumpTargets(idOwners?: Record<string, string>, onJump?: (docId: string) => void) {
  return function Code({ children }: { children?: ReactNode }) {
    const target = typeof children === 'string' ? idOwners?.[children] : undefined
    if (target === undefined || onJump === undefined) return <code>{children}</code>
    return (
      <button
        type="button"
        // Underline, not colour alone, tells it apart from plain code
        // (spec-00001-FR-59); a button, not an anchor, so nothing here
        // navigates out of the board (AC-39.6 stands).
        className="cursor-pointer underline"
        // The activation is a jump and only a jump: stopping both events is
        // what keeps the row's expand/collapse and the sub-canvas node's
        // detail from firing on the same gesture (spec-00001-FR-57 — Enter
        // fires the button's own click natively).
        onClick={(event) => {
          event.stopPropagation()
          onJump(target)
        }}
        onKeyDown={(event) => {
          if (event.key === 'Enter') event.stopPropagation()
        }}
      >
        <code>{children}</code>
      </button>
    )
  }
}

function inlineOnly(
  source: string,
  idOwners?: Record<string, string>,
  onJump?: (docId: string) => void,
): Components {
  const asSource = sourceOf(source)
  return {
    // The paragraph is the container of inline content: keep the children, drop
    // the element, and the text joins the line it was truncated into.
    p: ({ children }) => <>{children}</>,
    h1: asSource,
    h2: asSource,
    h3: asSource,
    h4: asSource,
    h5: asSource,
    h6: asSource,
    pre: asSource,
    blockquote: asSource,
    ul: asSource,
    ol: asSource,
    table: asSource,
    hr: asSource,
    a: ({ children }) => <>{children}</>,
    img: ({ alt }) => <>{alt}</>,
    code: jumpTargets(idOwners, onJump),
  }
}
