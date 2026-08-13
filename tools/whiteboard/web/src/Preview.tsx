import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { stripFrontMatter } from './frontMatter.ts'
import { Mermaid } from './Mermaid.tsx'

/**
 * Renders the buffer's body as GFM. Raw HTML in a document is neither injected
 * nor run, because `rehype-raw` is deliberately not enabled (spec-00001-FR-24).
 */
export function Preview({ markdown }: { markdown: string }) {
  return (
    <div className="preview" data-testid="preview">
      <Markdown
        remarkPlugins={[remarkGfm]}
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
