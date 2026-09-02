import { useEffect, useMemo, useRef } from 'react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { stripFrontMatter } from './frontMatter.ts'
import { Mermaid } from './Mermaid.tsx'
import { LOCATED_CLASS, type PreviewMark, rehypeSourcePos } from './previewSourcePos.ts'

export interface PreviewProps {
  markdown: string
  /**
   * The annotation intervals to draw, in body coordinates. Its **presence** —
   * an empty array included — is what puts the source-position plugin on the
   * pipeline: a rendering that has to map back to its source needs the offsets
   * whether or not anything is marked, while a rendering of an agent's answer is
   * not a document and has no source to map to (design-00002 §16.3).
   */
  marks?: readonly PreviewMark[]
  /**
   * Which locate the rendering is to be scrolled onto — the identity of the ask,
   * so asking twice scrolls twice and nothing else scrolls at all. Keyed on the
   * mark set instead, every refresh that rebuilt it would drag the reader back to
   * the last passage they located, in the middle of reading somewhere else
   * (design-00002 §16.6).
   */
  scrollTo?: string
}

/**
 * Renders the buffer's body as GFM. Raw HTML in a document is neither injected
 * nor run, because `rehype-raw` is deliberately not enabled (spec-00001-FR-24).
 */
export function Preview({ markdown, marks, scrollTo }: PreviewProps) {
  const host = useRef<HTMLDivElement>(null)
  const plugins = useMemo(() => (marks === undefined ? [] : [rehypeSourcePos(marks)]), [marks])

  // Locating scrolls the rendering onto the mark the list asked for
  // (spec-00007-AC-9.12). The mark itself is drawn by the plugin, so this is the
  // whole of the preview's locate — and it runs for a **locate**, never for a
  // change of the marks around it.
  useEffect(() => {
    if (scrollTo === undefined) return
    host.current?.querySelector(`.${LOCATED_CLASS}`)?.scrollIntoView({ block: 'center' })
  }, [scrollTo])

  return (
    <div className="preview" data-testid="preview" ref={host}>
      <Markdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={plugins}
        components={{
          code({ className, children, ...props }) {
            if (className?.includes('language-mermaid')) {
              return <Mermaid source={String(children).replace(/\n$/, '')} />
            }
            return (
              <code className={className} {...props}>
                {children}
              </code>
            )
          },
        }}
      >
        {stripFrontMatter(markdown)}
      </Markdown>
    </div>
  )
}
