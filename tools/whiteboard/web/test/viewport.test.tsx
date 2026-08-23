// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import type { AcceptanceRow, Criterion, ItemsView, RequirementItem } from '../../src/requirements.ts'
import { api } from '../src/api.ts'

type StoreProbe = { getState: () => { width: number; minZoom: number } }

/** The store React Flow is running on, so a test can read what the board set on it. */
let store: StoreProbe | undefined
/**
 * The canvas width held at each `setCenter` — which *is* the width the move was
 * made against: React Flow computes `width / 2 - x * zoom`. Centring a node
 * against a width the inspector has already taken away is the whole of
 * issue-00006, and this is where it shows.
 */
const centredAt: number[] = []

vi.mock('@xyflow/react', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@xyflow/react')>()
  return {
    ...actual,
    useReactFlow: () => {
      const flow = actual.useReactFlow()
      store = actual.useStoreApi() as unknown as StoreProbe
      return {
        ...flow,
        setCenter: async () => {
          centredAt.push(store!.getState().width)
          return true
        },
      }
    },
  }
})

const { Board } = await import('../src/Board.tsx')

/** The element React Flow measures the canvas on. */
const CANVAS = '.react-flow__renderer'
/** The pair record-00004 measured: the whole canvas, and what the inspector leaves of it. */
const FULL = 1600
const WITH_PANEL = 991

const drive = globalThis as {
  resizeSizes?: { selector: string; width: number; height: number }[]
  reportResize?: () => void
}

/** What the canvas measures from now on, reported the way the browser's observer would. */
async function canvasIs(width: number) {
  drive.resizeSizes = [{ selector: CANVAS, width, height: 900 }]
  await act(async () => {
    drive.reportResize?.()
  })
}

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
  edges: [],
  issues: [],
  idOwners: {},
  diagnostics: [],
}

function row(targetId: string): AcceptanceRow {
  return { recordId: 'record-00001-x', targetId, test: 'a test', result: 'pass' }
}

function criterion(id: string, rows: AcceptanceRow[] = []): Criterion {
  return { id, text: 'Given a board When it loads Then it works', rows }
}

function item(id: string, overrides: Partial<RequirementItem> = {}): RequirementItem {
  return { id, text: `what ${id} asks of the system`, criteria: [], rows: [], coverage: 'uncovered', ...overrides }
}

const ITEMS: ItemsView = {
  items: [
    item('spec-00001-FR-1', { criteria: [criterion('spec-00001-AC-1.1', [row('spec-00001-AC-1.1')])] }),
    item('spec-00001-FR-2', { criteria: [criterion('spec-00001-AC-2.1')] }),
  ],
  diagnostics: [],
}

/** Tall enough that no fit of it clears React Flow's own floor of 0.5. */
const MANY_ITEMS: ItemsView = {
  items: Array.from({ length: 30 }, (_, index) =>
    item(`spec-00001-FR-${index + 1}`, {
      criteria: [
        criterion(`spec-00001-AC-${index + 1}.1`, [row(`spec-00001-AC-${index + 1}.1`)]),
        criterion(`spec-00001-AC-${index + 1}.2`, [row(`spec-00001-AC-${index + 1}.2`)]),
      ],
    }),
  ),
  diagnostics: [],
}

/** Open the board on a full-width canvas, with nothing selected. */
async function openBoard() {
  const rendered = render(<Board />)
  await waitFor(() => expect(screen.getByTestId('node-spec-00001-x')).toBeTruthy())
  await canvasIs(FULL)
  centredAt.length = 0
  return rendered
}

/** Go to the spec through the command palette (spec-00001-AC-27.2). */
async function pickInPalette(id: string) {
  await userEvent.click(screen.getByRole('button', { name: /Find a document/ }))
  await userEvent.type(screen.getByPlaceholderText('Find a document by id or title'), id)
  await userEvent.click(await screen.findByRole('option', { name: new RegExp(id) }))
}

function panelIsUp() {
  return waitFor(() => expect(screen.getByLabelText('Requirements of spec-00001-x')).toBeTruthy())
}

beforeEach(() => {
  centredAt.length = 0
  store = undefined
  drive.resizeSizes = [{ selector: CANVAS, width: FULL, height: 900 }]
  vi.spyOn(api, 'graph').mockResolvedValue(GRAPH)
  vi.spyOn(api, 'transitions').mockResolvedValue(['archived'])
  vi.spyOn(api, 'nextSteps').mockResolvedValue([])
  vi.spyOn(api, 'sessions').mockResolvedValue([])
  vi.spyOn(api, 'items').mockResolvedValue(ITEMS)
  vi.spyOn(api, 'config').mockResolvedValue({
    types: { spec: 'living', record: 'work' },
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

afterEach(() => {
  cleanup()
  drive.resizeSizes = undefined
  vi.restoreAllMocks()
})

// issue-00006 — the guard design-00002 §8 left the toolbar-versus-slot rule without.
describe('centring a document the inspector is about to squeeze', () => {
  it('waits for the narrowed canvas when the command palette picks one', async () => {
    await openBoard()

    await pickInPalette('spec-00001-x')
    await panelIsUp()
    // The panel is mounted; React Flow reports the canvas it left a frame later.
    await canvasIs(WITH_PANEL)

    await waitFor(() => expect(centredAt.at(-1)).toBe(WITH_PANEL))
  })

  it('waits for the narrowed canvas when a node is clicked', async () => {
    await openBoard()

    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    await panelIsUp()
    await canvasIs(WITH_PANEL)

    await waitFor(() => expect(centredAt.at(-1)).toBe(WITH_PANEL))
  })

  it('waits for the narrowed canvas when the breadcrumb comes back up', async () => {
    await openBoard()
    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    await panelIsUp()
    await canvasIs(WITH_PANEL)
    // Down into the sub-canvas: the panel gives the slot back, so the canvas is whole again.
    await userEvent.click(screen.getByRole('button', { name: /Expand as sub-canvas/ }))
    await waitFor(() => expect(screen.getByTestId('sub-item-spec-00001-FR-1')).toBeTruthy())
    await canvasIs(FULL)
    centredAt.length = 0

    await userEvent.click(screen.getByRole('button', { name: 'Board' }))
    await panelIsUp()
    await canvasIs(WITH_PANEL)

    await waitFor(() => expect(centredAt.at(-1)).toBe(WITH_PANEL))
  })

  it('leaves the viewport alone when the selection costs the canvas no width', async () => {
    await openBoard()
    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    await panelIsUp()
    await canvasIs(WITH_PANEL)
    await waitFor(() => expect(centredAt.at(-1)).toBe(WITH_PANEL))
    centredAt.length = 0

    // The panel is already in the slot: selecting again changes no width, so
    // nothing should move.
    fireEvent.click(screen.getByTestId('node-record-00001-x'))
    await waitFor(() => expect(screen.queryByLabelText('Requirements of spec-00001-x')).toBeNull())

    expect(centredAt).toEqual([])
  })
})

describe('the zoom floor', () => {
  it('is React Flow’s default on the top-level board', async () => {
    await openBoard()

    expect(store?.getState().minZoom).toBe(0.5)

    // And the inspector opening does not move it: the floor belongs to the
    // dataset, not to the slot.
    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    await panelIsUp()
    expect(store?.getState().minZoom).toBe(0.5)
  })

  it('drops only as far as the sub-canvas on show needs, and is put back on the way up', async () => {
    vi.spyOn(api, 'items').mockResolvedValue(MANY_ITEMS)
    await openBoard()
    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    await panelIsUp()

    await userEvent.click(screen.getByRole('button', { name: /Expand as sub-canvas/ }))
    await waitFor(() => expect(screen.getByTestId('sub-item-spec-00001-FR-30')).toBeTruthy())
    // 30 items of two criteria each cannot be fitted above 0.5, so the floor gives way.
    expect(store!.getState().minZoom).toBeLessThan(0.5)

    await userEvent.click(screen.getByRole('button', { name: 'Board' }))
    await waitFor(() => expect(store!.getState().minZoom).toBe(0.5))
  })
})
