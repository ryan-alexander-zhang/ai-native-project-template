// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import type { AcceptanceRow, Criterion, ItemsView, RequirementItem } from '../../src/requirements.ts'
import { api } from '../src/api.ts'
import { SUB_COLUMN_GAP, SUB_COLUMN_WIDTH, SUB_ROW_PITCH, acceptanceRowId, subCanvas } from '../src/subCanvas.ts'

const setCenter = vi.fn()

// The viewport move is React Flow's to make, so the module is mocked to observe it.
vi.mock('@xyflow/react', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@xyflow/react')>()
  return { ...actual, useReactFlow: () => ({ ...actual.useReactFlow(), setCenter }) }
})

const { Board } = await import('../src/Board.tsx')

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

const SPEC = node()
const RECORD = node({ id: 'record-00001-x', type: 'record', title: 'First record', path: 'record/a.md' })

const GRAPH: DocGraph = {
  nodes: [SPEC, RECORD],
  edges: [
    { from: 'record-00001-x', to: 'spec-00001-x', relation: 'verifies', ok: true, declaredTargets: ['spec-00001-x'] },
  ],
  issues: [],
  idOwners: {},
  diagnostics: [],
}

function row(targetId: string, test = 'canvas.test.tsx › draws the edges', result = 'pass'): AcceptanceRow {
  return { recordId: 'record-00001-x', targetId, test, result }
}

function criterion(id: string, rows: AcceptanceRow[] = []): Criterion {
  return { id, text: 'Given a board When it loads Then it works', rows }
}

function item(id: string, overrides: Partial<RequirementItem> = {}): RequirementItem {
  return { id, text: `what ${id} asks of the system`, criteria: [], rows: [], coverage: 'uncovered', ...overrides }
}

function view(overrides: Partial<ItemsView> = {}): ItemsView {
  return { items: [], diagnostics: [], ...overrides }
}

/** One verified item with two criteria, one uncovered item, one item with no criteria at all. */
const ITEMS = view({
  items: [
    item('spec-00001-FR-1', {
      coverage: 'verified',
      criteria: [
        criterion('spec-00001-AC-1.1', [row('spec-00001-AC-1.1')]),
        criterion('spec-00001-AC-1.2', [row('spec-00001-AC-1.2', 'canvas.test.tsx › labels them')]),
      ],
    }),
    item('spec-00001-FR-2', { criteria: [criterion('spec-00001-AC-2.1')] }),
    item('spec-00001-FR-3'),
  ],
})

function expandButton() {
  return screen.getByRole('button', { name: /Expand as sub-canvas/ })
}

/** Open the board, select the spec, wait for its panel. */
async function openPanel() {
  const rendered = render(<Board />)
  await waitFor(() => expect(screen.getByTestId('node-spec-00001-x')).toBeTruthy())
  fireEvent.click(screen.getByTestId('node-spec-00001-x'))
  await waitFor(() => expect(screen.getByLabelText('Requirements of spec-00001-x')).toBeTruthy())
  return rendered
}

/** …then drill into it (spec-00001-FR-35). */
async function openSubCanvas() {
  const rendered = await openPanel()
  await userEvent.click(expandButton())
  await waitFor(() => expect(screen.getByTestId('sub-item-spec-00001-FR-1')).toBeTruthy())
  return rendered
}

afterEach(cleanup)

describe('subCanvas', () => {
  // The layout is a pure function of the payload, like layoutGraph.
  it('puts items, criteria and acceptance rows in three columns', () => {
    const { nodes } = subCanvas(ITEMS)
    const at = (id: string) => nodes.find((entry) => entry.id === id)!

    expect(at('spec-00001-FR-1').position.x).toBe(0)
    expect(at('spec-00001-AC-1.1').position.x).toBe(SUB_COLUMN_WIDTH[0] + SUB_COLUMN_GAP)
    expect(at(acceptanceRowId('spec-00001-AC-1.1', 0)).position.x).toBe(
      SUB_COLUMN_WIDTH[0] + SUB_COLUMN_WIDTH[1] + 2 * SUB_COLUMN_GAP,
    )
  })

  it('aligns each item and its criteria to the row the chain starts on, in item order', () => {
    const { nodes } = subCanvas(ITEMS)
    const rowOf = (id: string) => nodes.find((entry) => entry.id === id)!.position.y / SUB_ROW_PITCH

    // FR-1 owns two criteria of one row each, so FR-2 starts on the third row
    // and FR-3 — which owns nothing — on the fourth.
    expect(rowOf('spec-00001-FR-1')).toBe(0)
    expect(rowOf('spec-00001-AC-1.1')).toBe(0)
    expect(rowOf(acceptanceRowId('spec-00001-AC-1.1', 0))).toBe(0)
    expect(rowOf('spec-00001-AC-1.2')).toBe(1)
    expect(rowOf('spec-00001-FR-2')).toBe(2)
    expect(rowOf('spec-00001-AC-2.1')).toBe(2)
    expect(rowOf('spec-00001-FR-3')).toBe(3)
  })

  it('draws one edge per item-to-criterion and criterion-to-row link', () => {
    const { edges } = subCanvas(ITEMS)

    expect(edges.map((edge) => edge.id)).toEqual([
      'spec-00001-FR-1->spec-00001-AC-1.1',
      'spec-00001-AC-1.1->spec-00001-AC-1.1@0',
      'spec-00001-FR-1->spec-00001-AC-1.2',
      'spec-00001-AC-1.2->spec-00001-AC-1.2@0',
      'spec-00001-FR-2->spec-00001-AC-2.1',
    ])
  })

  it('draws nothing for a document with no items', () => {
    expect(subCanvas(view())).toEqual({ nodes: [], edges: [] })
  })
})

describe('the sub-canvas', () => {
  beforeEach(() => {
    setCenter.mockClear()
    vi.spyOn(api, 'graph').mockResolvedValue(GRAPH)
    vi.spyOn(api, 'transitions').mockResolvedValue(['archived'])
    vi.spyOn(api, 'nextSteps').mockResolvedValue([])
    vi.spyOn(api, 'sessions').mockResolvedValue([])
    vi.spyOn(api, 'items').mockResolvedValue(ITEMS)
    vi.spyOn(api, 'config').mockResolvedValue({
      types: { spec: 'living', rule: 'living', plan: 'work', record: 'work' },
      relations: ['verifies'],
      flow: {},
      focus: {},
      agents: [{ name: 'claude', headless: false, source: 'project' }],
      entry: [],
      carries: {},
      maxSessions: 3,
      clarifiable: [],
      auditable: ['spec', 'rule', 'design'],
    })
  })

  afterEach(() => vi.restoreAllMocks())

  // spec-00001-AC-35.1
  it('replaces the document nodes with the items, criteria and acceptance rows', async () => {
    await openSubCanvas()

    expect(screen.getByTestId('sub-item-spec-00001-FR-2')).toBeTruthy()
    expect(screen.getByTestId('sub-ac-spec-00001-AC-1.1')).toBeTruthy()
    expect(screen.getByTestId('sub-row-record-00001-x-spec-00001-AC-1.1')).toBeTruthy()
    expect(screen.queryByTestId('node-spec-00001-x')).toBeNull()
    expect(screen.queryByTestId('node-record-00001-x')).toBeNull()
    // Read-only: nothing to edit, review or advance while down here.
    expect(screen.queryByRole('toolbar')).toBeNull()
  })

  // spec-00001-AC-35.2
  it('links item to criterion to acceptance row, and says which record ran which test', async () => {
    const { container } = await openSubCanvas()

    const drawn = Array.from(container.querySelectorAll('.react-flow__edge'), (edge) => edge.getAttribute('data-id'))
    expect(drawn).toContain('spec-00001-FR-1->spec-00001-AC-1.1')
    expect(drawn).toContain('spec-00001-AC-1.1->spec-00001-AC-1.1@0')

    const evidence = screen.getByTestId('sub-row-record-00001-x-spec-00001-AC-1.1')
    expect(within(evidence).getByText('record-00001-x')).toBeTruthy()
    expect(within(evidence).getByText('canvas.test.tsx › draws the edges')).toBeTruthy()
    expect(within(evidence).getByText('pass')).toBeTruthy()
  })

  // spec-00001-AC-35.3 — the same mark the panel uses (spec-00001-FR-32)
  it('marks an uncovered item on its node', async () => {
    await openSubCanvas()

    const uncovered = screen.getByTestId('sub-item-spec-00001-FR-2')
    expect(within(uncovered).getByLabelText('uncovered')).toBeTruthy()
    expect(within(screen.getByTestId('sub-item-spec-00001-FR-1')).getByLabelText('verified')).toBeTruthy()
  })

  // spec-00001-AC-35.4
  it('shows the trail «Board / <document id>» in the header', async () => {
    await openSubCanvas()

    const trail = screen.getByRole('navigation', { name: 'breadcrumb' })
    expect(within(trail).getByRole('button', { name: 'Board' })).toBeTruthy()
    expect(within(trail).getByText('/')).toBeTruthy()
    const page = within(trail).getByText('spec-00001-x')
    expect(page.getAttribute('aria-current')).toBe('page')
    expect(screen.queryByText('docs whiteboard')).toBeNull()
  })

  // spec-00001-AC-35.5
  it('offers no way down for a document with no items', async () => {
    vi.spyOn(api, 'items').mockResolvedValue(view())
    await openPanel()

    expect(expandButton().hasAttribute('disabled')).toBe(true)
  })

  // spec-00001-AC-35.6
  it('shows no breadcrumb on the top-level board', async () => {
    await openPanel()

    expect(screen.queryByRole('navigation', { name: 'breadcrumb' })).toBeNull()
    expect(screen.getByText('docs whiteboard')).toBeTruthy()
  })

  // spec-00001-AC-35.7 — a spec of dozens of items opens showing all of them
  it('fits every node of a tall sub-canvas into the first viewport', async () => {
    const many = view({
      items: Array.from({ length: 30 }, (_, index) =>
        item(`spec-00001-FR-${index + 1}`, {
          coverage: 'verified',
          criteria: [
            criterion(`spec-00001-AC-${index + 1}.1`, [row(`spec-00001-AC-${index + 1}.1`)]),
            criterion(`spec-00001-AC-${index + 1}.2`, [row(`spec-00001-AC-${index + 1}.2`)]),
          ],
        }),
      ),
    })
    vi.spyOn(api, 'items').mockResolvedValue(many)
    const { container } = await openPanel()

    await userEvent.click(expandButton())
    await waitFor(() => expect(screen.getByTestId('sub-item-spec-00001-FR-30')).toBeTruthy())

    const canvas = container.querySelector('.react-flow__renderer') as HTMLElement
    const transform = (container.querySelector('.react-flow__viewport') as HTMLElement).style.transform
    const [, x, y, zoom] = /translate\((-?[\d.]+)px,(-?[\d.]+)px\) scale\(([\d.]+)\)/.exec(transform)!
    const [panX, panY, scale] = [Number(x), Number(y), Number(zoom)]

    // React Flow's own floor is 0.5; a chain this tall cannot fit above it, so
    // the assertion below only holds because the board lowers it.
    expect(scale).toBeLessThan(0.5)
    for (const node of subCanvas(many).nodes) {
      expect(node.position.x * scale + panX).toBeGreaterThanOrEqual(-0.5)
      expect(node.position.y * scale + panY).toBeGreaterThanOrEqual(-0.5)
      expect((node.position.x + node.width!) * scale + panX).toBeLessThanOrEqual(canvas.offsetWidth + 0.5)
      expect((node.position.y + node.height!) * scale + panY).toBeLessThanOrEqual(canvas.offsetHeight + 0.5)
    }
  })

  // spec-00001-AC-36.1
  it('returns to the board on the document, selected and in view', async () => {
    const { container } = await openSubCanvas()
    setCenter.mockClear()

    await userEvent.click(screen.getByRole('button', { name: 'Board' }))

    await waitFor(() => expect(screen.getByTestId('node-spec-00001-x')).toBeTruthy())
    expect(screen.queryByTestId('sub-item-spec-00001-FR-1')).toBeNull()
    const selected = Array.from(container.querySelectorAll('.react-flow__node.selected'), (entry) =>
      entry.getAttribute('data-id'),
    )
    expect(selected).toEqual(['spec-00001-x'])
    await waitFor(() => expect(setCenter).toHaveBeenCalled())
  })

  // spec-00001-AC-36.2
  it('brings the panel back with it, as a direct selection would', async () => {
    await openSubCanvas()

    await userEvent.click(screen.getByRole('button', { name: 'Board' }))

    const list = await screen.findByRole('list', { name: 'Requirement items of spec-00001-x' })
    expect(within(list).getAllByRole('listitem').map((entry) => entry.getAttribute('data-testid'))).toEqual([
      'item-spec-00001-FR-1',
      'item-spec-00001-FR-2',
      'item-spec-00001-FR-3',
    ])
  })
})
