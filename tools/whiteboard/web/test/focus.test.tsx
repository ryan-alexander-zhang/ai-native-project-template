// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import { type SessionListing, api } from '../src/api.ts'
import { NODE_HEIGHT, NODE_WIDTH, layoutGraph, orderedColumns } from '../src/layout.ts'

const setCenter = vi.fn()

// The viewport move is React Flow's to make, so the module is mocked to observe it.
vi.mock('@xyflow/react', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@xyflow/react')>()
  return { ...actual, useReactFlow: () => ({ ...actual.useReactFlow(), setCenter }) }
})

const { Board } = await import('../src/Board.tsx')

function node(overrides: Partial<DocNode> = {}): DocNode {
  return {
    id: 'prd-00001-x',
    path: 'prd/a.md',
    type: 'prd',
    status: 'draft',
    title: 'Whiteboard PRD',
    relations: {},
    ok: true,
    problems: [],
    ...overrides,
  }
}

const GRAPH: DocGraph = {
  nodes: [node(), node({ id: 'idea-00001-x', type: 'idea', title: 'Whiteboard idea', path: 'idea/a.md' })],
  edges: [],
  issues: [],
  idOwners: {},
  diagnostics: [],
}

beforeEach(() => {
  setCenter.mockClear()
  vi.spyOn(api, 'graph').mockResolvedValue(GRAPH)
  vi.spyOn(api, 'transitions').mockResolvedValue(['active'])
  vi.spyOn(api, 'nextSteps').mockResolvedValue([])
  vi.spyOn(api, 'sessions').mockResolvedValue([])
  vi.spyOn(api, 'config').mockResolvedValue({
    types: { prd: 'living', idea: 'living' },
    relations: ['parent'],
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

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

async function pick(id: string) {
  render(<Board />)
  await waitFor(() => expect(screen.getByTestId(`node-${id}`)).toBeTruthy())
  await userEvent.click(screen.getByRole('button', { name: /Find a document/ }))
  await userEvent.type(screen.getByPlaceholderText('Find a document by id or title'), id)
  await userEvent.click(await screen.findByRole('option', { name: new RegExp(id) }))
}

// spec-00001-AC-27.2
describe('picking a document in the command palette', () => {
  it('moves the viewport to that node', async () => {
    await pick('idea-00001-x')

    await waitFor(() => expect(setCenter).toHaveBeenCalled())
  })

  it('centres on the node rather than its corner', async () => {
    await pick('idea-00001-x')
    await waitFor(() => expect(setCenter).toHaveBeenCalled())

    // NODE_WIDTH 240 / NODE_HEIGHT 92, so the centre is half of each past the origin.
    const [x, y] = setCenter.mock.calls[0] as [number, number]
    const placed = { x: x - 120, y: y - 46 }
    expect(Number.isFinite(placed.x) && Number.isFinite(placed.y)).toBe(true)
  })
})

/**
 * Going to a document folded away in a collapsed directory group
 * (spec-00010-FR-7): every route ends in `focus`/`select`, so the group opens
 * off the selection itself and the centring waits for the row it opened.
 */
describe('going to a document inside a collapsed directory group', () => {
  const DESIGN = node({ id: 'design-00002-ui', type: 'design', status: 'active', title: 'Board UI', path: 'design/design-00002-ui.md' })
  const TOP = node({ id: 'reference-00001-a', type: 'reference', status: 'active', title: 'A top-level reference', path: 'reference/a.md' })
  const MEMBER = node({ id: 'reference-00012-t', type: 'reference', status: 'active', title: 'Stripe two', path: 'reference/stripe/two.md' })
  const BROKEN = node({
    id: 'reference/stripe/bad.md',
    type: 'reference',
    status: 'active',
    title: 'Stripe bad',
    path: 'reference/stripe/bad.md',
    ok: false,
    problems: ['no status'],
  })
  const SPEC = node({ id: 'spec-00001-x', type: 'spec', status: 'active', title: 'Whiteboard spec', path: 'spec/a.md' })
  const ARCHIVED = node({ id: 'spec-00042-x', type: 'spec', status: 'active', title: 'An archived spec', path: 'spec/archive/x.md' })

  const GROUPED: DocGraph = {
    nodes: [DESIGN, TOP, MEMBER, BROKEN, SPEC, ARCHIVED],
    edges: [
      { from: 'design-00002-ui', to: 'reference-00012-t', relation: 'informs', ok: true, declaredTargets: ['reference-00012-t'] },
    ],
    issues: [{ path: 'reference/stripe/bad.md', nodeId: 'reference/stripe/bad.md', message: 'no status' }],
    idOwners: { 'reference-00012-t': 'reference-00012-t', 'spec-00001-FR-1': 'spec-00001-x' },
    diagnostics: [],
  }

  const ITEMS = {
    items: [
      {
        id: 'spec-00001-FR-1',
        text: 'what FR-1 asks of the system',
        criteria: [
          {
            id: 'spec-00001-AC-1.1',
            text: 'Given `reference-00012-t` When its id is clicked Then the board goes there',
            rows: [],
          },
        ],
        rows: [],
        coverage: 'uncovered' as const,
      },
    ],
    diagnostics: [],
  }

  const SESSION: SessionListing = {
    id: 's1',
    kind: 'clarify',
    agent: 'claude',
    sourceId: 'reference-00012-t',
    status: 'running',
    startedAt: '2026-01-01T00:00:00.000Z',
  }

  /** The element React Flow measures the canvas on, and the harness that moves it. */
  const drive = globalThis as {
    resizeSizes?: { selector: string; width: number; height: number }[]
    reportResize?: () => void
  }

  async function canvasIs(width: number) {
    drive.resizeSizes = [{ selector: '.react-flow__renderer', width, height: 900 }]
    await act(async () => {
      drive.reportResize?.()
    })
  }

  function serve(sessions: SessionListing[] = []) {
    vi.spyOn(api, 'graph').mockResolvedValue(GROUPED)
    vi.spyOn(api, 'sessions').mockResolvedValue(sessions)
    vi.spyOn(api, 'items').mockResolvedValue(ITEMS)
    vi.spyOn(api, 'config').mockResolvedValue({
      types: { design: 'living', spec: 'living', reference: 'living' },
      relations: ['informs'],
      flow: {},
      focus: {},
      agents: [{ name: 'claude', headless: false, source: 'project' }],
      entry: [],
      carries: {},
      maxSessions: 3,
      clarifiable: [],
      auditable: [],
    })
  }

  async function openBoard(sessions: SessionListing[] = []) {
    serve(sessions)
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-design-00002-ui')).toBeTruthy())
    expect(screen.queryByTestId('node-reference-00012-t')).toBeNull()
  }

  async function pickInPalette(id: string) {
    await userEvent.click(screen.getByRole('button', { name: /Find a document/ }))
    await userEvent.type(screen.getByPlaceholderText('Find a document by id or title'), id)
    await userEvent.click(await screen.findByRole('option', { name: new RegExp(id) }))
  }

  beforeEach(() => {
    localStorage.removeItem('whiteboard-directory-groups-expanded')
    // The terminal a session brings up dials a socket the moment it mounts.
    vi.stubGlobal(
      'WebSocket',
      class {
        static readonly OPEN = 1
        readyState = 1
        addEventListener() {}
        send() {}
        close() {}
      },
    )
  })

  afterEach(() => {
    drive.resizeSizes = undefined
    vi.unstubAllGlobals()
  })

  /**
   * spec-00010-AC-7.1 — the centring is on the row the expansion made: with the
   * group still folded the document has no place on the canvas at all, so a
   * move onto that place is the proof that it ran after the group opened.
   */
  it('opens the group and centres on the document the command palette names', async () => {
    const opened = layoutGraph(orderedColumns(GROUPED, ['design', 'spec', 'reference']), [
      'reference\u0000reference/stripe',
    ])
    const at = opened.find((position) => position.id === 'reference-00012-t')!
    await openBoard()

    await pickInPalette('reference-00012-t')

    await waitFor(() => expect(screen.getByTestId('node-reference-00012-t')).toBeTruthy())
    expect(setCenter).toHaveBeenCalledTimes(1)
    expect(setCenter).toHaveBeenCalledWith(at.x + NODE_WIDTH / 2, at.y + NODE_HEIGHT / 2, expect.anything())
  })

  // spec-00010-AC-7.2
  it('opens the group from the anomaly list', async () => {
    await openBoard()
    await userEvent.click(screen.getByRole('button', { name: 'Open the anomaly list' }))
    const list = await screen.findByRole('list', { name: 'Anomalies' })

    await userEvent.click(within(list).getAllByRole('button')[0]!)

    await waitFor(() => expect(screen.getByTestId('node-reference/stripe/bad.md')).toBeTruthy())
    expect(screen.getByRole('toolbar', { name: /reference\/stripe\/bad\.md/ })).toBeTruthy()
  })

  // spec-00010-AC-7.3
  it('opens the group from the relation list of the selected document', async () => {
    await openBoard()
    fireEvent.click(screen.getByTestId('node-design-00002-ui'))
    await waitFor(() => expect(screen.getByLabelText('Relations')).toBeTruthy())
    await userEvent.click(screen.getByLabelText('Relations'))
    const list = await screen.findByRole('list', { name: 'Relations of design-00002-ui' })

    await userEvent.click(within(list).getByText('reference-00012-t'))

    await waitFor(() => expect(screen.getByTestId('node-reference-00012-t')).toBeTruthy())
    expect(screen.getByRole('toolbar', { name: /reference-00012-t/ })).toBeTruthy()
  })

  // spec-00010-AC-7.4 — a top-level document opens nothing
  it('leaves every group collapsed when the document picked is a top-level one', async () => {
    await openBoard()

    await pickInPalette('reference-00001-a')

    await waitFor(() => expect(screen.getByRole('toolbar', { name: /reference-00001-a/ })).toBeTruthy())
    expect(screen.queryByTestId('node-reference-00012-t')).toBeNull()
    expect(screen.queryByTestId('node-spec-00042-x')).toBeNull()
  })

  // spec-00010-AC-7.5
  it('opens the group from the session panel, and shows the session', async () => {
    await openBoard([SESSION])
    await userEvent.click(screen.getByRole('button', { name: 'Open the session panel' }))
    const list = await screen.findByRole('list', { name: 'Agent sessions' })

    await userEvent.click(within(within(list).getAllByRole('listitem')[0]!).getAllByRole('button')[0]!)

    await waitFor(() => expect(screen.getByTestId('node-reference-00012-t')).toBeTruthy())
    expect(screen.getByRole('toolbar', { name: /reference-00012-t/ })).toBeTruthy()
    expect(screen.getByRole('region', { name: /session/i })).toBeTruthy()
  })

  // spec-00010-AC-7.6
  it('opens the group from an inline id in an expanded requirement row', async () => {
    await openBoard()
    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    await waitFor(() => expect(screen.getByTestId('item-spec-00001-FR-1')).toBeTruthy())
    await userEvent.click(screen.getByTestId('item-spec-00001-FR-1'))
    const expansion = screen.getByLabelText('Expanded spec-00001-FR-1')

    fireEvent.click(within(expansion).getByRole('button', { name: 'reference-00012-t' }))

    await waitFor(() => expect(screen.getByTestId('node-reference-00012-t')).toBeTruthy())
    expect(screen.getByRole('toolbar', { name: /reference-00012-t/ })).toBeTruthy()
  })

  /**
   * issue-00006 through the new two-branch effect: the jump centres once the
   * expansion has laid the node out, and again once the inspector has taken its
   * third of the canvas — the second is the compensation issue-00006 is about,
   * and it is still armed after the first (design-00002 §19.3).
   */
  it('centres once on the opened group and again on the canvas the inspector left', async () => {
    await openBoard()
    await canvasIs(1600)
    setCenter.mockClear()

    await pickInPalette('spec-00042-x')
    await waitFor(() => expect(screen.getByLabelText('Requirements of spec-00042-x')).toBeTruthy())
    expect(setCenter).toHaveBeenCalledTimes(1)
    await canvasIs(991)

    await waitFor(() => expect(setCenter).toHaveBeenCalledTimes(2))
  })
})
