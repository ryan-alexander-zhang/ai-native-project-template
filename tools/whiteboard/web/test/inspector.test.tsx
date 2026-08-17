// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import type { DocEdge, DocGraph, DocNode } from '../../src/docRepository.ts'
import type { AcceptanceRow, Criterion, ItemsView, RequirementItem } from '../../src/requirements.ts'
import { requirementView } from '../../src/requirements.ts'
import { Board } from '../src/Board.tsx'
import { api } from '../src/api.ts'
import { evidenceOf } from '../src/canvasModel.ts'

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

function verifies(from: string): DocEdge {
  return { from, to: 'spec-00001-x', relation: 'verifies', ok: true, declaredTargets: ['spec-00001-x'] }
}

const SPEC = node()
const RECORD_ONE = node({ id: 'record-00001-x', type: 'record', title: 'First record', path: 'record/a.md' })
const RECORD_TWO = node({ id: 'record-00002-x', type: 'record', title: 'Second record', path: 'record/b.md' })
// Present on the board but with no relation to the spec — AC-34.5 needs exactly that.
const RECORD_THREE = node({ id: 'record-00003-x', type: 'record', title: 'Third record', path: 'record/c.md' })
const PLAN = node({ id: 'plan-00001-x', type: 'plan', title: 'A plan', path: 'plan/a.md' })

const GRAPH: DocGraph = {
  nodes: [SPEC, RECORD_ONE, RECORD_TWO, RECORD_THREE, PLAN],
  edges: [verifies('record-00001-x'), verifies('record-00002-x')],
  issues: [],
}

function row(recordId: string, targetId: string, result = 'pass'): AcceptanceRow {
  return { recordId, targetId, test: 'a test', result }
}

function criterion(id: string, rows: AcceptanceRow[] = []): Criterion {
  return { id, text: 'Given a board When it loads Then it works', rows }
}

function item(id: string, overrides: Partial<RequirementItem> = {}): RequirementItem {
  return { id, text: `what ${id} asks of the system`, criteria: [], rows: [], coverage: 'uncovered', ...overrides }
}

function view(overrides: Partial<ItemsView> = {}): ItemsView {
  return { items: [], unattributed: [], ...overrides }
}

/** Open the board, wait for it, and select `id`. */
async function selectNode(id: string) {
  const rendered = render(<Board />)
  await waitFor(() => expect(screen.getByTestId(`node-${id}`)).toBeTruthy())
  fireEvent.click(screen.getByTestId(`node-${id}`))
  return rendered
}

function panel() {
  return screen.queryByLabelText('Requirements of spec-00001-x')
}

afterEach(cleanup)

describe('the inspector panel', () => {
  beforeEach(() => {
    vi.spyOn(api, 'graph').mockResolvedValue(GRAPH)
    vi.spyOn(api, 'transitions').mockResolvedValue(['archived'])
    vi.spyOn(api, 'nextSteps').mockResolvedValue([])
    vi.spyOn(api, 'session').mockResolvedValue({ current: null })
    vi.spyOn(api, 'items').mockResolvedValue(view())
    vi.spyOn(api, 'config').mockResolvedValue({
      types: { spec: 'living', rule: 'living', plan: 'work', record: 'work' },
      relations: ['verifies'],
      flow: {},
      agents: [{ name: 'claude', command: 'claude', args: [] }],
    })
  })

  afterEach(() => vi.restoreAllMocks())

  // spec-00001-AC-31.1
  it('lists the items of the selected spec in the order they arrive, with id, text and AC count', async () => {
    vi.spyOn(api, 'items').mockResolvedValue(
      view({
        items: [
          item('spec-00001-FR-1', { criteria: [criterion('spec-00001-AC-1.1'), criterion('spec-00001-AC-1.2')] }),
          item('spec-00001-FR-2', { criteria: [criterion('spec-00001-AC-2.1')] }),
        ],
      }),
    )
    await selectNode('spec-00001-x')

    const list = await screen.findByRole('list', { name: 'Requirement items of spec-00001-x' })
    const rows = within(list).getAllByRole('listitem')
    expect(rows.map((entry) => entry.textContent)).toEqual([
      expect.stringContaining('spec-00001-FR-1'),
      expect.stringContaining('spec-00001-FR-2'),
    ])
    expect(rows[0]!.textContent).toContain('what spec-00001-FR-1 asks of the system')
    expect(rows[0]!.textContent).toContain('2 AC')
    expect(rows[1]!.textContent).toContain('1 AC')
  })

  // spec-00001-AC-31.2 — the payload is parsed from a body carrying both
  // declaration shapes, so the panel is exercised over the real thing.
  it('lists an item declared in a decision table beside one declared as a list entry', async () => {
    const rule = node({ id: 'rule-00001-x', type: 'rule', title: 'Workflow rule', path: 'rule/a.md' })
    vi.spyOn(api, 'graph').mockResolvedValue({ nodes: [rule], edges: [], issues: [] })
    vi.spyOn(api, 'items').mockResolvedValue(
      requirementView(
        {
          id: 'rule-00001-x',
          body: [
            '| # | 种类 | 当前状态 |',
            '| --- | --- | --- |',
            '| **rule-00001-BR-2** | living doc | `draft` |',
            '',
            '- **rule-00001-BR-10** (Definition) 接收：对 `draft` 文档的促进。',
          ].join('\n'),
        },
        [],
      ),
    )
    await selectNode('rule-00001-x')

    const list = await screen.findByRole('list', { name: 'Requirement items of rule-00001-x' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)
    expect(within(list).getByText('rule-00001-BR-2')).toBeTruthy()
    expect(within(list).getByText('rule-00001-BR-10')).toBeTruthy()
  })

  // spec-00001-AC-31.3
  it('shows no panel for a document type that declares no items', async () => {
    await selectNode('plan-00001-x')

    await waitFor(() => expect(screen.getByRole('toolbar', { name: /plan-00001-x/ })).toBeTruthy())
    expect(screen.queryByLabelText(/^Requirements of/)).toBeNull()
    expect(api.items).not.toHaveBeenCalled()
  })

  // spec-00001-AC-31.4
  it('says there are no items rather than showing an empty panel', async () => {
    await selectNode('spec-00001-x')

    expect(await screen.findByText('no requirement items')).toBeTruthy()
    expect(screen.queryByRole('list', { name: /Requirement items/ })).toBeNull()
  })

  // The board is the thing the user came for: losing the items costs the panel,
  // not the canvas — and says why, as the config path already does.
  it('keeps the board when the items cannot be read, and says why', async () => {
    vi.spyOn(api, 'items').mockRejectedValue(new Error('no document spec-00001-x'))
    const toastError = vi.spyOn(toast, 'error').mockImplementation(() => 'id')
    await selectNode('spec-00001-x')

    await waitFor(() => expect(toastError).toHaveBeenCalledWith('no document spec-00001-x'))
    expect(panel()).toBeNull()
    expect(screen.getByTestId('node-spec-00001-x')).toBeTruthy()
  })

  // spec-00001-AC-31.5 — items live in the panel, never on the top-level board
  it('puts one node on the canvas per document, whatever the items say', async () => {
    vi.spyOn(api, 'items').mockResolvedValue(
      view({ items: [item('spec-00001-FR-1', { criteria: [criterion('spec-00001-AC-1.1')] })] }),
    )
    const { container } = render(<Board />)

    await waitFor(() => expect(screen.getByTestId('node-spec-00001-x')).toBeTruthy())
    expect(container.querySelectorAll('.react-flow__node')).toHaveLength(GRAPH.nodes.length)
    expect(container.querySelectorAll('.react-flow__edge')).toHaveLength(GRAPH.edges.length)
    expect(panel()).toBeNull()
  })

  // spec-00001-AC-31.6
  it('closes the panel when the selection is dropped', async () => {
    const { container } = await selectNode('spec-00001-x')
    await waitFor(() => expect(panel()).toBeTruthy())

    fireEvent.click(container.querySelector('.react-flow__pane')!)

    await waitFor(() => expect(panel()).toBeNull())
  })

  // spec-00001-AC-31.7 — a broken front matter is a reason to read the body, not a bar
  it('opens the panel for a spec whose front matter is broken', async () => {
    vi.spyOn(api, 'graph').mockResolvedValue({
      nodes: [node({ status: undefined, ok: false, problems: ['front matter has no status'] })],
      edges: [],
      issues: [],
    })
    vi.spyOn(api, 'items').mockResolvedValue(view({ items: [item('spec-00001-FR-1')] }))
    await selectNode('spec-00001-x')

    const list = await screen.findByRole('list', { name: 'Requirement items of spec-00001-x' })
    expect(within(list).getByText('spec-00001-FR-1')).toBeTruthy()
  })

  // spec-00001-AC-31.8 — the editor holds the slot; a click on a node does not take it
  it('leaves the editor in place when a spec is selected', async () => {
    vi.spyOn(api, 'doc').mockResolvedValue({ path: 'record/a.md', content: '# R\n', hash: 'h' })
    vi.spyOn(api, 'items').mockResolvedValue(view({ items: [item('spec-00001-FR-1')] }))
    await selectNode('record-00001-x')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy())
    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    await waitFor(() => expect(screen.getByLabelText('Editing record-00001-x')).toBeTruthy())

    fireEvent.click(screen.getByTestId('node-spec-00001-x'))

    await waitFor(() => expect(screen.getByRole('toolbar', { name: /spec-00001-x/ })).toBeTruthy())
    expect(screen.getByLabelText('Editing record-00001-x')).toBeTruthy()
    expect(panel()).toBeNull()
  })

  // spec-00001-AC-31.9
  it('shows the panel as soon as the editor gives the slot back', async () => {
    vi.spyOn(api, 'doc').mockResolvedValue({ path: 'spec/a.md', content: '# S\n', hash: 'h' })
    vi.spyOn(api, 'items').mockResolvedValue(view({ items: [item('spec-00001-FR-1')] }))
    await selectNode('spec-00001-x')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy())
    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    await waitFor(() => expect(screen.getByLabelText('Editing spec-00001-x')).toBeTruthy())
    expect(panel()).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'Close' }))

    await waitFor(() => expect(panel()).toBeTruthy())
    expect(screen.getByText('spec-00001-FR-1')).toBeTruthy()
  })

  // spec-00001-AC-32.6 — the state is queryable by name, not by colour alone
  it('carries each coverage state as a named icon', async () => {
    vi.spyOn(api, 'items').mockResolvedValue(
      view({
        items: [
          item('spec-00001-FR-1', { coverage: 'verified' }),
          item('spec-00001-FR-2', { coverage: 'failing' }),
          item('spec-00001-FR-3', { coverage: 'uncovered' }),
        ],
      }),
    )
    await selectNode('spec-00001-x')
    const list = await screen.findByRole('list', { name: 'Requirement items of spec-00001-x' })

    const verified = within(list).getByLabelText('verified')
    expect(verified.getAttribute('style')).toContain('var(--coverage-verified)')
    expect(within(list).getByLabelText('failing').getAttribute('style')).toContain('var(--coverage-failing)')
    expect(within(list).getByLabelText('uncovered').getAttribute('style')).toContain('var(--coverage-uncovered)')
  })

  // spec-00001-AC-33.1
  it('lists a row that names an id no item holds, with the record it came from', async () => {
    vi.spyOn(api, 'items').mockResolvedValue(
      view({
        items: [item('spec-00001-FR-1', { criteria: [criterion('spec-00001-AC-1.1')] })],
        unattributed: [{ recordId: 'record-00001-x', declaredId: 'spec-00001-AC-99.1' }],
      }),
    )
    await selectNode('spec-00001-x')

    const unattributed = await screen.findByLabelText('Unattributable entries of spec-00001-x')
    expect(within(unattributed).getByText('record-00001-x')).toBeTruthy()
    expect(within(unattributed).getByText('spec-00001-AC-99.1')).toBeTruthy()
  })

  // spec-00001-AC-33.3 — a criterion attributed to nothing counts towards nothing
  it('lists a criterion whose attribution names no item, and leaves the AC counts alone', async () => {
    vi.spyOn(api, 'items').mockResolvedValue(
      view({
        items: [item('spec-00001-FR-1', { criteria: [criterion('spec-00001-AC-1.1')] })],
        unattributed: [{ declaredId: 'spec-00001-AC-99.1', attributedTo: 'spec-00001-FR-99' }],
      }),
    )
    await selectNode('spec-00001-x')

    const unattributed = await screen.findByLabelText('Unattributable entries of spec-00001-x')
    expect(within(unattributed).getByText('spec-00001-AC-99.1')).toBeTruthy()
    expect(within(unattributed).getByText(/spec-00001-FR-99/)).toBeTruthy()
    const list = screen.getByRole('list', { name: 'Requirement items of spec-00001-x' })
    expect(within(list).getByTestId('item-spec-00001-FR-1').textContent).toContain('1 AC')
  })
})

describe('hovering an item in the panel', () => {
  const VERIFIED = item('spec-00001-FR-1', {
    coverage: 'verified',
    criteria: [
      criterion('spec-00001-AC-1.1', [row('record-00001-x', 'spec-00001-AC-1.1')]),
      criterion('spec-00001-AC-1.2', [row('record-00001-x', 'spec-00001-AC-1.2')]),
    ],
  })
  const UNCOVERED = item('spec-00001-FR-2')

  beforeEach(() => {
    vi.spyOn(api, 'graph').mockResolvedValue(GRAPH)
    vi.spyOn(api, 'transitions').mockResolvedValue(['archived'])
    vi.spyOn(api, 'nextSteps').mockResolvedValue([])
    vi.spyOn(api, 'session').mockResolvedValue({ current: null })
    vi.spyOn(api, 'items').mockResolvedValue(view({ items: [VERIFIED, UNCOVERED] }))
    vi.spyOn(api, 'config').mockResolvedValue({
      types: { spec: 'living', rule: 'living', plan: 'work', record: 'work' },
      relations: ['verifies'],
      flow: {},
      agents: [{ name: 'claude', command: 'claude', args: [] }],
    })
  })

  afterEach(() => vi.restoreAllMocks())

  /** Select the spec and wait for its panel, edges and all. */
  async function openPanel() {
    const rendered = await selectNode('spec-00001-x')
    await waitFor(() => expect(screen.getByTestId('item-spec-00001-FR-1')).toBeTruthy())
    await waitFor(() =>
      expect(rendered.container.querySelectorAll('.react-flow__edge.edge--emphasis')).toHaveLength(2),
    )
    return rendered
  }

  // spec-00001-AC-34.1
  it('emphasises the edge to the record that verified the item, labelled with the cited AC ids', async () => {
    const { container } = await openPanel()

    await userEvent.hover(screen.getByTestId('item-spec-00001-FR-1'))

    await waitFor(() => expect(container.querySelectorAll('.react-flow__edge.edge--emphasis')).toHaveLength(1))
    expect(screen.getByText('spec-00001-AC-1.1 · spec-00001-AC-1.2')).toBeTruthy()
    expect(container.querySelectorAll('.react-flow__edge.edge--suppressed')).toHaveLength(1)
    expect(screen.queryByText('verifies')).toBeNull()
  })

  // spec-00001-AC-34.2
  it('gives the selected-state presentation back when the pointer leaves', async () => {
    const { container } = await openPanel()
    const row = screen.getByTestId('item-spec-00001-FR-1')
    await userEvent.hover(row)
    await waitFor(() => expect(container.querySelectorAll('.react-flow__edge.edge--emphasis')).toHaveLength(1))

    await userEvent.unhover(row)

    await waitFor(() => expect(container.querySelectorAll('.react-flow__edge.edge--emphasis')).toHaveLength(2))
    expect(screen.getAllByText('verifies')).toHaveLength(2)
    expect(screen.queryByText(/spec-00001-AC-1\.1/)).toBeNull()
  })

  // spec-00001-AC-34.3 — nothing to point at, so nothing moves
  it('emphasises nothing extra for an uncovered item', async () => {
    const { container } = await openPanel()

    await userEvent.hover(screen.getByTestId('item-spec-00001-FR-2'))

    expect(container.querySelectorAll('.react-flow__edge.edge--emphasis')).toHaveLength(2)
    expect(screen.getAllByText('verifies')).toHaveLength(2)
    expect(screen.queryByText(/spec-00001-AC-/)).toBeNull()
  })

  // spec-00001-AC-34.4 — the keyboard reaches the same path as the pointer
  it('emphasises the same edge when the row takes keyboard focus', async () => {
    const { container } = await openPanel()
    const row = screen.getByTestId('item-spec-00001-FR-1')
    expect(row.getAttribute('tabindex')).toBe('0')

    fireEvent.focus(row)

    await waitFor(() => expect(container.querySelectorAll('.react-flow__edge.edge--emphasis')).toHaveLength(1))
    expect(screen.getByText('spec-00001-AC-1.1 · spec-00001-AC-1.2')).toBeTruthy()

    fireEvent.blur(row)

    await waitFor(() => expect(container.querySelectorAll('.react-flow__edge.edge--emphasis')).toHaveLength(2))
  })

  // spec-00001-AC-34.5
  it('emphasises nothing when the verifying record shares no edge with the document', async () => {
    vi.spyOn(api, 'items').mockResolvedValue(
      view({
        items: [
          item('spec-00001-FR-1', {
            coverage: 'verified',
            criteria: [criterion('spec-00001-AC-1.1', [row('record-00003-x', 'spec-00001-AC-1.1')])],
          }),
        ],
      }),
    )
    const errors: string[] = []
    const onError = (event: ErrorEvent) => errors.push(String(event.error?.message ?? event.message))
    window.addEventListener('error', onError)
    const { container } = await openPanel()

    await userEvent.hover(screen.getByTestId('item-spec-00001-FR-1'))

    window.removeEventListener('error', onError)
    expect(container.querySelectorAll('.react-flow__edge.edge--emphasis')).toHaveLength(2)
    expect(screen.queryByText('spec-00001-AC-1.1')).toBeNull()
    expect(errors).toEqual([])
  })

  // spec-00001-AC-34.6
  it('emphasises both edges when two records verified the item', async () => {
    vi.spyOn(api, 'items').mockResolvedValue(
      view({
        items: [
          item('spec-00001-FR-1', {
            coverage: 'verified',
            criteria: [
              criterion('spec-00001-AC-1.1', [row('record-00001-x', 'spec-00001-AC-1.1')]),
              criterion('spec-00001-AC-1.2', [row('record-00002-x', 'spec-00001-AC-1.2')]),
            ],
          }),
        ],
      }),
    )
    const { container } = await openPanel()

    await userEvent.hover(screen.getByTestId('item-spec-00001-FR-1'))

    await waitFor(() => expect(screen.getByText('spec-00001-AC-1.1')).toBeTruthy())
    expect(container.querySelectorAll('.react-flow__edge.edge--emphasis')).toHaveLength(2)
    expect(screen.getByText('spec-00001-AC-1.2')).toBeTruthy()
  })
})

describe('the evidence of an item', () => {
  // spec-00001-FR-34: one entry per record, its cited AC ids in criterion order
  it('groups the cited AC ids by the record that cited them, without repeats', () => {
    const evidence = evidenceOf(
      item('spec-00001-FR-1', {
        criteria: [
          criterion('spec-00001-AC-1.1', [
            row('record-00001-x', 'spec-00001-AC-1.1'),
            // The same criterion verified twice by one record — one id, not two.
            row('record-00001-x', 'spec-00001-AC-1.1'),
          ]),
          criterion('spec-00001-AC-1.2', [row('record-00002-x', 'spec-00001-AC-1.2')]),
        ],
        rows: [row('record-00001-x', 'spec-00001-FR-1')],
      }),
    )

    expect([...evidence]).toEqual([
      ['record-00001-x', ['spec-00001-AC-1.1']],
      ['record-00002-x', ['spec-00001-AC-1.2']],
    ])
  })

  it('finds no evidence for an item nothing referenced', () => {
    expect([...evidenceOf(item('spec-00001-FR-9'))]).toEqual([])
  })
})
