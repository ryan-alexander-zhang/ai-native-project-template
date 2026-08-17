// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import type { AcceptanceRow, Criterion, ItemsView, RequirementItem } from '../../src/requirements.ts'
import { Board } from '../src/Board.tsx'
import { api } from '../src/api.ts'
import { detailTarget } from '../src/subCanvas.ts'

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
  diagnostics: [],
}

function row(targetId: string, overrides: Partial<AcceptanceRow> = {}): AcceptanceRow {
  return { recordId: 'record-00001-x', targetId, test: 'canvas.test.tsx › draws the edges', result: 'pass', ...overrides }
}

function criterion(id: string, rows: AcceptanceRow[] = []): Criterion {
  return { id, text: `Given ${id} holds When the board loads Then the chain reads across`, rows }
}

function item(id: string, overrides: Partial<RequirementItem> = {}): RequirementItem {
  return { id, text: `what ${id} asks of the system`, criteria: [], rows: [], coverage: 'uncovered', ...overrides }
}

function view(overrides: Partial<ItemsView> = {}): ItemsView {
  return { items: [], diagnostics: [], ...overrides }
}

/**
 * FR-1 carries two criteria: the first verified by a row that offers evidence,
 * the second by a row from a checklist with no Evidence column at all. FR-2 has
 * no criteria, so its detail has an AC list to say nothing about.
 */
const ITEMS = view({
  items: [
    item('spec-00001-FR-1', {
      coverage: 'verified',
      criteria: [
        criterion('spec-00001-AC-1.1', [row('spec-00001-AC-1.1', { evidence: 'coverage/index.html' })]),
        criterion('spec-00001-AC-1.2', [row('spec-00001-AC-1.2', { test: 'canvas.test.tsx › labels them' })]),
      ],
    }),
    item('spec-00001-FR-2'),
  ],
})

const WITH_EVIDENCE = 'sub-row-record-00001-x-spec-00001-AC-1.1'
const WITHOUT_EVIDENCE = 'sub-row-record-00001-x-spec-00001-AC-1.2'

function details(id: string) {
  return screen.queryByLabelText(`Details of ${id}`)
}

/** Open the board, select the spec, drill into its sub-canvas. */
async function openSubCanvas() {
  const rendered = render(<Board />)
  await waitFor(() => expect(screen.getByTestId('node-spec-00001-x')).toBeTruthy())
  fireEvent.click(screen.getByTestId('node-spec-00001-x'))
  await waitFor(() => expect(screen.getByLabelText('Requirements of spec-00001-x')).toBeTruthy())
  await userEvent.click(screen.getByRole('button', { name: /Expand as sub-canvas/ }))
  await waitFor(() => expect(screen.getByTestId('sub-item-spec-00001-FR-1')).toBeTruthy())
  return rendered
}

afterEach(cleanup)

describe('the detail panel', () => {
  beforeEach(() => {
    vi.spyOn(api, 'graph').mockImplementation(async () => ({ ...GRAPH }))
    vi.spyOn(api, 'transitions').mockResolvedValue(['archived'])
    vi.spyOn(api, 'nextSteps').mockResolvedValue([])
    vi.spyOn(api, 'session').mockResolvedValue({ current: null })
    vi.spyOn(api, 'items').mockResolvedValue(ITEMS)
    vi.spyOn(api, 'config').mockResolvedValue({
      types: { spec: 'living', rule: 'living', plan: 'work', record: 'work' },
      relations: ['verifies'],
      flow: {},
      agents: [{ name: 'claude', command: 'claude', args: [] }],
    })
  })

  afterEach(() => vi.restoreAllMocks())

  // spec-00001-AC-37.1
  it('gives an AC node its whole Given/When/Then', async () => {
    await openSubCanvas()

    fireEvent.click(screen.getByTestId('sub-ac-spec-00001-AC-1.1'))

    const panel = await screen.findByLabelText('Details of spec-00001-AC-1.1')
    expect(
      within(panel).getByText('Given spec-00001-AC-1.1 holds When the board loads Then the chain reads across'),
    ).toBeTruthy()
  })

  // spec-00001-AC-37.2 — the item's own text in full, its criteria as a list
  it('gives an item node its text and the roll of its AC', async () => {
    await openSubCanvas()

    fireEvent.click(screen.getByTestId('sub-item-spec-00001-FR-1'))

    const panel = await screen.findByLabelText('Details of spec-00001-FR-1')
    expect(within(panel).getByText('what spec-00001-FR-1 asks of the system')).toBeTruthy()
    const list = within(panel).getByRole('list', { name: 'Acceptance criteria of spec-00001-FR-1' })
    expect(within(list).getAllByRole('listitem').map((entry) => entry.textContent)).toEqual([
      'spec-00001-AC-1.1',
      'spec-00001-AC-1.2',
    ])
  })

  // spec-00001-AC-37.3
  it('gives an acceptance row its record, test, result, evidence and a way back', async () => {
    await openSubCanvas()

    fireEvent.click(screen.getByTestId(WITH_EVIDENCE))

    const panel = await screen.findByLabelText('Details of spec-00001-AC-1.1@0')
    expect(within(panel).getAllByText('record-00001-x').length).toBeGreaterThan(0)
    expect(within(panel).getByText('canvas.test.tsx › draws the edges')).toBeTruthy()
    expect(within(panel).getByText('pass')).toBeTruthy()
    expect(within(panel).getByText('coverage/index.html')).toBeTruthy()
    expect(within(panel).getByRole('button', { name: /Go to record-00001-x/ })).toBeTruthy()
  })

  // spec-00001-AC-37.4
  it('closes on a click into the blank', async () => {
    const { container } = await openSubCanvas()
    fireEvent.click(screen.getByTestId('sub-item-spec-00001-FR-1'))
    await waitFor(() => expect(details('spec-00001-FR-1')).toBeTruthy())

    fireEvent.click(container.querySelector('.react-flow__pane')!)

    await waitFor(() => expect(details('spec-00001-FR-1')).toBeNull())
    expect(screen.getByTestId('sub-item-spec-00001-FR-1')).toBeTruthy()
  })

  // spec-00001-AC-37.5
  it('switches to the node clicked next', async () => {
    await openSubCanvas()
    fireEvent.click(screen.getByTestId('sub-item-spec-00001-FR-1'))
    await waitFor(() => expect(details('spec-00001-FR-1')).toBeTruthy())

    fireEvent.click(screen.getByTestId('sub-ac-spec-00001-AC-1.2'))

    await waitFor(() => expect(details('spec-00001-AC-1.2')).toBeTruthy())
    expect(details('spec-00001-FR-1')).toBeNull()
  })

  // spec-00001-AC-37.6 — the same act as the breadcrumb's, aimed at the record
  it('goes to the record the row came from, and selects it', async () => {
    const { container } = await openSubCanvas()
    fireEvent.click(screen.getByTestId(WITH_EVIDENCE))
    const panel = await screen.findByLabelText('Details of spec-00001-AC-1.1@0')

    await userEvent.click(within(panel).getByRole('button', { name: /Go to record-00001-x/ }))

    await waitFor(() => expect(screen.getByTestId('node-record-00001-x')).toBeTruthy())
    expect(screen.queryByTestId('sub-item-spec-00001-FR-1')).toBeNull()
    const selected = Array.from(container.querySelectorAll('.react-flow__node.selected'), (entry) =>
      entry.getAttribute('data-id'),
    )
    expect(selected).toEqual(['record-00001-x'])
  })

  // spec-00001-AC-37.7
  it('closes on Esc and leaves the sub-canvas standing', async () => {
    await openSubCanvas()
    fireEvent.click(screen.getByTestId('sub-ac-spec-00001-AC-1.1'))
    await waitFor(() => expect(details('spec-00001-AC-1.1')).toBeTruthy())

    await userEvent.keyboard('{Escape}')

    await waitFor(() => expect(details('spec-00001-AC-1.1')).toBeNull())
    expect(screen.getByTestId('sub-ac-spec-00001-AC-1.1')).toBeTruthy()
    expect(screen.getByRole('navigation', { name: 'breadcrumb' })).toBeTruthy()
  })

  // spec-00001-AC-37.8 — no Evidence column, so no such field
  it('shows no evidence field for a row that has none', async () => {
    await openSubCanvas()

    fireEvent.click(screen.getByTestId(WITHOUT_EVIDENCE))

    const panel = await screen.findByLabelText('Details of spec-00001-AC-1.2@0')
    expect(within(panel).getByText('canvas.test.tsx › labels them')).toBeTruthy()
    expect(within(panel).getByText('pass')).toBeTruthy()
    expect(within(panel).queryByText('evidence')).toBeNull()
  })

  // spec-00001-AC-37.9
  it('closes on the way back up, handing the slot to the inspector', async () => {
    await openSubCanvas()
    fireEvent.click(screen.getByTestId('sub-item-spec-00001-FR-1'))
    await waitFor(() => expect(details('spec-00001-FR-1')).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: 'Board' }))

    await waitFor(() => expect(details('spec-00001-FR-1')).toBeNull())
    const list = await screen.findByRole('list', { name: 'Requirement items of spec-00001-x' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)
  })

  // The sub-canvas is read-only, and so is its detail (spec-00001-FR-35, FR-37).
  it('offers nothing to write with', async () => {
    await openSubCanvas()

    fireEvent.click(screen.getByTestId('sub-item-spec-00001-FR-1'))

    const panel = await screen.findByLabelText('Details of spec-00001-FR-1')
    expect(within(panel).queryByRole('button')).toBeNull()
    expect(within(panel).queryByRole('textbox')).toBeNull()
  })
})

describe('the node a click stands for', () => {
  const VIEW = ITEMS

  it('resolves an item, a criterion and an acceptance row by node id', () => {
    expect(detailTarget(VIEW, 'spec-00001-FR-2')).toEqual({
      kind: 'item',
      id: 'spec-00001-FR-2',
      item: VIEW.items[1],
    })
    expect(detailTarget(VIEW, 'spec-00001-AC-1.2')).toEqual({
      kind: 'criterion',
      id: 'spec-00001-AC-1.2',
      criterion: VIEW.items[0]!.criteria[1],
    })
    expect(detailTarget(VIEW, 'spec-00001-AC-1.1@0')).toEqual({
      kind: 'row',
      id: 'spec-00001-AC-1.1@0',
      row: VIEW.items[0]!.criteria[0]!.rows[0],
    })
  })

  it('resolves nothing for an id the payload no longer holds', () => {
    expect(detailTarget(VIEW, 'spec-00001-AC-9.9')).toBeUndefined()
    expect(detailTarget(VIEW, 'spec-00001-AC-1.1@7')).toBeUndefined()
  })
})
