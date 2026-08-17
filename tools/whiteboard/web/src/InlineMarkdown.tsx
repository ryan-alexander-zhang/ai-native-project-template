import Markdown, { type Components, type ExtraProps } from 'react-markdown'
import remarkGfm from 'remark-gfm'

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
 */
export function InlineMarkdown({ text }: { text: string }) {
  return (
    <Markdown remarkPlugins={[remarkGfm]} components={inlineOnly(text)}>
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

function inlineOnly(source: string): Components {
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
  }
}
