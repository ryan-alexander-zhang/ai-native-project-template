// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import type { AcceptanceRow, Criterion, ItemsView, RequirementItem } from '../../src/requirements.ts'
import { Board } from '../src/Board.tsx'
import { InlineMarkdown } from '../src/InlineMarkdown.tsx'
import { api } from '../src/api.ts'

const MARKED = 'the **status** field must be `active`'
const RENDERED = 'the status field must be active'

function node(overrides: Partial<DocNode> = {}): DocNode {
  return {
    id: 'spec-00001-x',
    path: 'spec/a.md',
    type: 'spec',
    status: 'active',
    title: 'Whiteboard spec',
    relations: {},
    ok: true,
    problems: [],
    ...overrides,
  }
}

const GRAPH: DocGraph = {
  nodes: [node(), node({ id: 'record-00001-x', type: 'record', title: 'First record', path: 'record/a.md' })],
  edges: [
    { from: 'record-00001-x', to: 'spec-00001-x', relation: 'verifies', ok: true, declaredTargets: ['spec-00001-x'] },
  ],
  issues: [],
  idOwners: {},
  diagnostics: [],
}

function row(targetId: string): AcceptanceRow {
  return { recordId: 'record-00001-x', targetId, test: 'canvas.test.tsx › draws the edges', result: 'pass' }
}

function criterion(id: string, text: string, rows: AcceptanceRow[] = []): Criterion {
  return { id, text, rows }
}

function item(id: string, overrides: Partial<RequirementItem> = {}): RequirementItem {
  return { id, text: MARKED, criteria: [], rows: [], coverage: 'uncovered', ...overrides }
}

/** FR-1 carries the marked-up text; FR-2 carries none at all. */
const ITEMS: ItemsView = {
  items: [
    item('spec-00001-FR-1', {
      coverage: 'verified',
      criteria: [criterion('spec-00001-AC-1.1', `Given ${MARKED} When it renders Then it reads`, [
        row('spec-00001-AC-1.1'),
      ])],
    }),
    item('spec-00001-FR-2', { text: '' }),
  ],
  diagnostics: [],
}

afterEach(cleanup)

describe('inline markdown', () => {
  // spec-00001-AC-39.1 — the marks style the text and then get out of the way
  it('renders bold and inline code as themselves, not as their source', () => {
    const { container } = render(<InlineMarkdown text={MARKED} />)

    expect(container.querySelector('strong')!.textContent).toBe('status')
    expect(container.querySelector('code')!.textContent).toBe('active')
    expect(container.textContent).toBe(RENDERED)
    expect(container.textContent).not.toContain('**')
    expect(container.textContent).not.toContain('`')
  })

  it('renders emphasis too', () => {
    const { container } = render(<InlineMarkdown text="a *stressed* word" />)

    expect(container.querySelector('em')!.textContent).toBe('stressed')
    expect(container.textContent).toBe('a stressed word')
  })

  // spec-00001-AC-39.3 — the FR-24 line: no raw HTML goes in, so no script comes out
  it('puts no script element on the page', () => {
    const { container } = render(<InlineMarkdown text={'careful <script>alert(1)</script> now'} />)

    expect(container.querySelector('script')).toBeNull()
    expect(document.querySelector('script')).toBeNull()
    expect(container.textContent).toContain('careful')
  })

  // spec-00001-AC-39.4 — block syntax degrades to the characters it was written with
  it('makes no block element out of a heading or a fence', () => {
    const { container } = render(<InlineMarkdown text={'# a heading\n\n```js\nconst a = 1\n```'} />)

    expect(container.querySelector('h1')).toBeNull()
    expect(container.querySelector('h2, h3, h4, h5, h6')).toBeNull()
    expect(container.querySelector('pre')).toBeNull()
    expect(container.querySelector('code')).toBeNull()
    expect(container.textContent).toContain('# a heading')
    expect(container.textContent).toContain('```js')
    expect(container.textContent).toContain('const a = 1')
  })

  it('makes no list or table either', () => {
    const { container } = render(<InlineMarkdown text={'- one\n- two'} />)

    expect(container.querySelector('ul, ol, li')).toBeNull()
    expect(container.textContent).toContain('- one')
  })

  // spec-00001-AC-39.6 — nothing to navigate to, nothing to fetch
  it('degrades a link and an image to their text', () => {
    const { container } = render(
      <InlineMarkdown text={'see [the doc](https://example.com) and ![a chart](https://example.com/x.png)'} />,
    )

    expect(container.querySelector('a')).toBeNull()
    expect(container.querySelector('img')).toBeNull()
    expect(container.textContent).toBe('see the doc and a chart')
  })

  // spec-00001-AC-39.5, at the component's own level
  it('renders empty text as nothing at all', () => {
    const { container } = render(<InlineMarkdown text="" />)

    expect(container.textContent).toBe('')
  })
})

describe('requirement text wherever it is shown', () => {
  beforeEach(() => {
    vi.spyOn(api, 'graph').mockResolvedValue(GRAPH)
    vi.spyOn(api, 'transitions').mockResolvedValue(['archived'])
    vi.spyOn(api, 'nextSteps').mockResolvedValue([])
    vi.spyOn(api, 'session').mockResolvedValue({ current: null })
    vi.spyOn(api, 'items').mockResolvedValue(ITEMS)
    vi.spyOn(api, 'config').mockResolvedValue({
      types: { spec: 'living', rule: 'living', plan: 'work', record: 'work' },
      relations: ['verifies'],
      flow: {},
      focus: {},
      agents: [{ name: 'claude', command: 'claude', args: [] }],
      entry: [],
      carries: {},
      maxSessions: 3,
      clarifiable: [],
      auditable: ['spec', 'rule', 'design'],
    })
  })

  afterEach(() => vi.restoreAllMocks())

  async function openPanel() {
    const rendered = render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-spec-00001-x')).toBeTruthy())
    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    await waitFor(() => expect(screen.getByTestId('item-spec-00001-FR-1')).toBeTruthy())
    return rendered
  }

  async function openSubCanvas() {
    const rendered = await openPanel()
    await userEvent.click(screen.getByRole('button', { name: /Expand as sub-canvas/ }))
    await waitFor(() => expect(screen.getByTestId('sub-item-spec-00001-FR-1')).toBeTruthy())
    return rendered
  }

  // spec-00001-AC-39.1 — the truncated row of the panel
  it('renders the panel row through the same pipeline, clamp and all', async () => {
    await openPanel()

    const row = screen.getByTestId('item-spec-00001-FR-1')
    expect(within(row).getByText('status').tagName).toBe('STRONG')
    expect(within(row).getByText('active').tagName).toBe('CODE')
    expect(row.textContent).not.toContain('**')
    // The clamp falls on the rendered text, not on the source.
    expect(row.querySelector('.line-clamp-2')!.textContent).toBe(RENDERED)
  })

  // spec-00001-AC-39.2 — the expanded row, the sub-canvas node and the detail panel
  it('renders the expansion, the sub-canvas node and the detail the same way', async () => {
    await openPanel()

    await userEvent.click(screen.getByTestId('item-spec-00001-FR-1'))
    const expansion = screen.getByLabelText('Expanded spec-00001-FR-1')
    expect(within(expansion).getByText('status').tagName).toBe('STRONG')
    expect(within(expansion).getByText('active').tagName).toBe('CODE')
    expect(expansion.textContent).not.toContain('**')

    await userEvent.click(screen.getByRole('button', { name: /Expand as sub-canvas/ }))
    const card = await screen.findByTestId('sub-item-spec-00001-FR-1')
    expect(within(card).getByText('status').tagName).toBe('STRONG')
    expect(card.textContent).not.toContain('`')

    fireEvent.click(screen.getByTestId('sub-ac-spec-00001-AC-1.1'))
    const panel = await screen.findByLabelText('Details of spec-00001-AC-1.1')
    expect(within(panel).getByText('status').tagName).toBe('STRONG')
    expect(within(panel).getByText('active').tagName).toBe('CODE')
    expect(panel.textContent).not.toContain('**')
  })

  // spec-00001-AC-39.5 — an item with no text at all is still an item
  it('keeps an empty item legible by its id everywhere', async () => {
    await openSubCanvas()

    expect(within(screen.getByTestId('sub-item-spec-00001-FR-2')).getByText('spec-00001-FR-2')).toBeTruthy()
    fireEvent.click(screen.getByTestId('sub-item-spec-00001-FR-2'))
    const panel = await screen.findByLabelText('Details of spec-00001-FR-2')
    expect(within(panel).getByText('spec-00001-FR-2')).toBeTruthy()
    expect(within(panel).getByText('no AC')).toBeTruthy()

    await userEvent.click(screen.getByRole('button', { name: 'Board' }))
    const back = await screen.findByTestId('item-spec-00001-FR-2')
    expect(within(back).getByText('spec-00001-FR-2')).toBeTruthy()
  })
})
