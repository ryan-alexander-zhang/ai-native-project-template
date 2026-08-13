import mermaid from 'mermaid'
import { useEffect, useState } from 'react'

// `strict` sanitises the svg mermaid produces; nothing from the document is
// interpolated into it beyond the diagram source itself.
mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'neutral' })

let counter = 0

/** One diagram. A source mermaid cannot parse shows its error here and nowhere else. */
export function Mermaid({ source }: { source: string }) {
  const [svg, setSvg] = useState('')
  const [error, setError] = useState('')

  useEffect(() => {
    let live = true
    setError('')
    mermaid
      .render(`mermaid-${(counter += 1)}`, source)
      .then((result) => {
        if (live) setSvg(result.svg)
      })
      .catch((cause: Error) => {
        if (live) setError(cause.message)
      })
    return () => {
      live = false
    }
  }, [source])

  if (error) {
    return (
      <pre className="preview__diagram-error" data-testid="mermaid-error">
        {error}
      </pre>
    )
  }
  return (
    <div
      className="preview__diagram"
      data-testid="mermaid"
      // The svg comes from mermaid, sanitised by its `strict` security level.
      dangerouslySetInnerHTML={{ __html: svg }}
    />
  )
}
