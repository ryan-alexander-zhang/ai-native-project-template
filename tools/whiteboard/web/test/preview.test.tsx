// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import { api } from '../src/api.ts'
import { Editor, type EditorMode } from '../src/Editor.tsx'
import { Preview } from '../src/Preview.tsx'
import { stripFrontMatter } from '../src/frontMatter.ts'

const FLOWCHART = 'flowchart LR\n  A --> B'
const FRONT_MATTER = '---\nid: prd-00001-x\ntype: prd\nstatus: draft\n---\n'

beforeEach(() => {
  vi.spyOn(toast, 'success').mockImplementation(() => 'id')
  vi.spyOn(toast, 'error').mockImplementation(() => 'id')
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('stripFrontMatter', () => {
  it('drops a leading front matter block', () => {
    expect(stripFrontMatter(`${FRONT_MATTER}\n# Title\n`)).toBe('\n# Title\n')
  })

  it('leaves a document without front matter alone', () => {
    expect(stripFrontMatter('# Title\n\n---\n\nbody\n')).toBe('# Title\n\n---\n\nbody\n')
  })

  it('drops only the leading block, keeping a later rule', () => {
    expect(stripFrontMatter(`${FRONT_MATTER}\n# Title\n\n---\n\nafter\n`)).toBe('\n# Title\n\n---\n\nafter\n')
  })

  it('handles carriage returns', () => {
    expect(stripFrontMatter('---\r\nid: x\r\n---\r\n# Title\r\n')).toBe('# Title\r\n')
  })

  it('leaves an empty buffer empty', () => {
    expect(stripFrontMatter('')).toBe('')
  })
})

describe('the preview', () => {
  // spec-00001-AC-22.1
  it('renders a heading as a heading element', () => {
    render(<Preview markdown={'## Context\n'} />)
    expect(screen.getByRole('heading', { level: 2, name: 'Context' })).toBeTruthy()
  })

  // spec-00001-AC-22.2
  it('renders list items as list elements', () => {
    render(<Preview markdown={'- first\n- second\n'} />)
    expect(screen.getAllByRole('listitem').map((item) => item.textContent)).toEqual(['first', 'second'])
  })

  // spec-00001-AC-22.3
  it('renders a GFM table as a table', () => {
    render(<Preview markdown={'| Rule | Doc |\n| --- | --- |\n| Late fees | rule-00001 |\n'} />)

    expect(screen.getByRole('table')).toBeTruthy()
    expect(screen.getByRole('columnheader', { name: 'Rule' })).toBeTruthy()
    expect(screen.getByRole('cell', { name: 'rule-00001' })).toBeTruthy()
  })

  // spec-00001-AC-22.4
  it('renders a mermaid block as a diagram rather than code', async () => {
    render(<Preview markdown={`# Design\n\n\`\`\`mermaid\n${FLOWCHART}\n\`\`\`\n`} />)

    await waitFor(() => expect(screen.getByTestId('mermaid').innerHTML).toContain('<svg'))
    expect(screen.queryByText('flowchart LR')).toBeNull()
  })

  // spec-00001-AC-22.5
  it('leaves a non-mermaid code block as code', () => {
    render(<Preview markdown={'```ts\nconst a = 1\n```\n'} />)

    expect(screen.getByText('const a = 1').tagName).toBe('CODE')
    expect(screen.queryByTestId('mermaid')).toBeNull()
  })

  // spec-00001-AC-22.6 — regression: front matter used to render as a rule plus a setext heading
  it('does not render front matter as body text', () => {
    const { container } = render(<Preview markdown={`${FRONT_MATTER}\n# Real Title\n`} />)

    expect(screen.queryByText(/id: prd-00001-x/)).toBeNull()
    expect(container.querySelector('hr')).toBeNull()
    expect(screen.getByRole('heading', { level: 1, name: 'Real Title' })).toBeTruthy()
  })

  // spec-00001-AC-22.8
  it('renders nothing for an empty buffer', () => {
    render(<Preview markdown="" />)
    expect(screen.getByTestId('preview').textContent).toBe('')
  })

  // spec-00001-AC-23.1
  it('shows the parser reason where a broken diagram would have been', async () => {
    render(<Preview markdown={'```mermaid\nflowchart LR\n  A -->\n```\n'} />)

    await waitFor(() => expect(screen.getByTestId('mermaid-error').textContent).toMatch(/\S/))
  })

  // spec-00001-AC-23.2
  it('keeps rendering the document after a broken diagram', async () => {
    render(<Preview markdown={'```mermaid\nflowchart LR\n  A -->\n```\n\n## Still here\n'} />)

    await waitFor(() => expect(screen.getByTestId('mermaid-error')).toBeTruthy())
    expect(screen.getByRole('heading', { level: 2, name: 'Still here' })).toBeTruthy()
  })

  // spec-00001-AC-23.3
  it('renders a sound diagram even when another one in the document is broken', async () => {
    render(<Preview markdown={`\`\`\`mermaid\n${FLOWCHART}\n\`\`\`\n\n\`\`\`mermaid\nflowchart LR\n  C -->\n\`\`\`\n`} />)

    await waitFor(() => expect(screen.getByTestId('mermaid').innerHTML).toContain('<svg'))
    expect(screen.getByTestId('mermaid-error')).toBeTruthy()
  })

  // spec-00001-AC-23.4
  it('renders the diagram once a broken source is corrected', async () => {
    const { rerender } = render(<Preview markdown={'```mermaid\nflowchart LR\n  A -->\n```\n'} />)
    await waitFor(() => expect(screen.getByTestId('mermaid-error')).toBeTruthy())

    rerender(<Preview markdown={`\`\`\`mermaid\n${FLOWCHART}\n\`\`\`\n`} />)

    await waitFor(() => expect(screen.getByTestId('mermaid').innerHTML).toContain('<svg'))
    expect(screen.queryByTestId('mermaid-error')).toBeNull()
  })

  // spec-00001-AC-24.1
  it('does not put raw HTML from the document into the page', () => {
    const { container } = render(<Preview markdown={'<script>window.pwned = 1</script>\n\n# Heading\n'} />)

    expect(container.querySelector('script')).toBeNull()
    expect((window as unknown as { pwned?: number }).pwned).toBeUndefined()
  })

  // spec-00001-AC-24.2 — the diagram svg is injected as html, so mermaid must sanitise it
  it('does not let a script inside a mermaid node label reach the page', async () => {
    const { container } = render(
      <Preview markdown={'```mermaid\nflowchart LR\n  A["<script>window.pwnedByDiagram = 1</script>"] --> B\n```\n'} />,
    )

    await waitFor(() => expect(screen.getByTestId('mermaid').innerHTML).toContain('<svg'))
    expect(container.querySelector('script')).toBeNull()
    expect((window as unknown as { pwnedByDiagram?: number }).pwnedByDiagram).toBeUndefined()
  })

  // spec-00001-AC-24.3
  it('keeps rendering ordinary body text around raw HTML', () => {
    render(<Preview markdown={'<div>raw</div>\n\n# Heading\n'} />)
    expect(screen.getByRole('heading', { level: 1, name: 'Heading' })).toBeTruthy()
  })
})

/**
 * Which view the editor shows is the board's state now, not the editor's
 * (design-00002 §14) — so a case that switches views holds it here, exactly as
 * the board does.
 */
function Editing({ docId }: { docId: string }) {
  const [mode, setMode] = useState<EditorMode>('source')
  return <Editor docId={docId} mode={mode} onMode={setMode} onSaved={vi.fn()} onClose={vi.fn()} />
}

describe('the editor preview toggle', () => {
  const CONTENT = `${FRONT_MATTER}\n## Context\n\n\`\`\`mermaid\n${FLOWCHART}\n\`\`\`\n`

  beforeEach(() => {
    vi.spyOn(api, 'doc').mockResolvedValue({ path: 'prd/a.md', content: CONTENT, hash: 'hash-1' })
  })

  async function openEditor() {
    render(<Editing docId="prd-00001-x" />)
    await waitFor(() => expect(screen.getByTestId('editor-host').textContent).toContain('## Context'))
  }

  // spec-00001-AC-22.1 and AC-22.4 through the editor
  it('switches the panel from source to rendered markdown', async () => {
    await openEditor()

    await userEvent.click(screen.getByRole('tab', { name: 'Preview' }))

    expect(screen.getByRole('heading', { level: 2, name: 'Context' })).toBeTruthy()
    await waitFor(() => expect(screen.getByTestId('mermaid').innerHTML).toContain('<svg'))
  })

  // spec-00001-AC-22.7
  it('hides the source while previewing and brings it back on toggle', async () => {
    await openEditor()

    await userEvent.click(screen.getByRole('tab', { name: 'Preview' }))
    expect(screen.getByTestId('editor-host').hidden).toBe(true)

    await userEvent.click(screen.getByRole('tab', { name: 'Source' }))
    expect(screen.getByTestId('editor-host').hidden).toBe(false)
    expect(screen.queryByTestId('preview')).toBeNull()
  })

  // spec-00001-AC-22.8
  it('previews nothing, without error, before the document has loaded', async () => {
    vi.spyOn(api, 'doc').mockReturnValue(new Promise(() => {}))
    render(<Editing docId="prd-00001-x" />)

    await userEvent.click(screen.getByRole('tab', { name: 'Preview' }))

    expect(screen.getByTestId('preview').textContent).toBe('')
  })

  // spec-00001-AC-25.1
  it('keeps unsaved edits when switching back to the editor', async () => {
    const save = vi.spyOn(api, 'save').mockResolvedValue({ committed: true })
    await openEditor()
    await userEvent.click(screen.getByTestId('editor-host').querySelector('.cm-content')!)
    await userEvent.keyboard('edited ')

    await userEvent.click(screen.getByRole('tab', { name: 'Preview' }))
    await userEvent.click(screen.getByRole('tab', { name: 'Source' }))
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(save.mock.calls[0]![1]).toContain('edited ')
  })

  // spec-00001-AC-25.2
  it('keeps the cursor where it was before the preview', async () => {
    await openEditor()
    await userEvent.click(screen.getByTestId('editor-host').querySelector('.cm-content')!)
    await userEvent.keyboard('X')
    const cursorBefore = document.querySelector('.cm-content')?.textContent?.indexOf('X')

    await userEvent.click(screen.getByRole('tab', { name: 'Preview' }))
    await userEvent.click(screen.getByRole('tab', { name: 'Source' }))
    // The editor takes focus back on the next frame, once the tab has had it.
    await waitFor(() => expect(document.activeElement?.closest('.cm-editor')).toBeTruthy())
    await userEvent.keyboard('Y')

    // The cursor survived the round trip, so Y landed immediately after X.
    expect(document.querySelector('.cm-content')?.textContent?.indexOf('XY')).toBe(cursorBefore)
  })
})
