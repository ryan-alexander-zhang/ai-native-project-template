// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocGraph, DocNode, GraphIssue } from '../../src/docRepository.ts'
import type { GraphDiagnostic } from '../../src/requirements.ts'
import { Board } from '../src/Board.tsx'
import { api } from '../src/api.ts'

// Rendering the whole board per case, in a suite whose files run side by side.
vi.setConfig({ testTimeout: 30_000 })

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

/** Sound in itself, but its `implements` names an id no document declares. */
const SPEC = node()
/** Anomalous front matter, and the source of a diagnostic as well (spec-00002-AC-14.3). */
const BAD_SPEC = node({
  id: 'spec-00002-bad',
  path: 'spec/bad.md',
  status: 'nope',
  title: 'Governance spec',
  ok: false,
  problems: ['status "nope" is not a status of a living document'],
})
/** No front matter to parse at all, so the node is keyed and labelled by its path. */
const NO_ID = node({
  id: 'idea/none.md',
  path: 'idea/none.md',
  type: undefined,
  status: undefined,
  title: 'none',
  ok: false,
  problems: ['front matter is missing'],
})

const ISSUES: GraphIssue[] = [
  { path: 'spec/a.md', nodeId: 'spec-00001-x', message: 'implements points at unknown document "spec-09999-ghost"' },
  { path: 'spec/bad.md', nodeId: 'spec-00002-bad', message: 'status "nope" is not a status of a living document' },
  { path: 'idea/none.md', nodeId: 'idea/none.md', message: 'front matter is missing' },
]

const DIAGNOSTICS: GraphDiagnostic[] = [
  { docId: 'spec-00001-x', kind: 'item-shape', line: 4, text: '**spec-00001-FR-2** drifted out of shape' },
  { docId: 'spec-00002-bad', kind: 'relation-field', text: 'informs is not a relation field a spec document carries' },
]

const GRAPH: DocGraph = {
  nodes: [SPEC, BAD_SPEC, NO_ID],
  edges: [],
  issues: ISSUES,
  diagnostics: DIAGNOSTICS,
  idOwners: {},
}

let graph: DocGraph

function serve() {
  graph = GRAPH
  vi.spyOn(api, 'graph').mockImplementation(async () => structuredClone(graph))
  vi.spyOn(api, 'items').mockResolvedValue({ items: [], diagnostics: [] })
  vi.spyOn(api, 'transitions').mockResolvedValue([])
  vi.spyOn(api, 'nextSteps').mockResolvedValue([])
  vi.spyOn(api, 'sessions').mockResolvedValue([])
  vi.spyOn(api, 'config').mockResolvedValue({
    types: { spec: 'living', idea: 'living' },
    relations: [],
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

const SETTLED = { timeout: 20_000, interval: 25 }

async function openBoard() {
  render(<Board />)
  await waitFor(() => expect(screen.getByTestId('node-spec-00001-x')).toBeTruthy(), SETTLED)
}

async function openList(entry: string, label: string) {
  await userEvent.click(screen.getByRole('button', { name: entry }))
  await waitFor(() => expect(screen.getByRole('list', { name: label })).toBeTruthy(), SETTLED)
  return screen.getByRole('list', { name: label })
}

const openAnomalies = () => openList('Open the anomaly list', 'Anomalies')
const openDiagnostics = () => openList('Open the diagnostics list', 'Diagnostics')

beforeEach(serve)

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

/** spec-00002-FR-13: the anomaly count is the way into the anomalies. */
describe('the anomaly list', () => {
  // spec-00002-AC-13.1
  it('lists every anomaly with its source and its problem text', async () => {
    await openBoard()
    expect(screen.getByText('3 issues')).toBeTruthy()

    const list = await openAnomalies()

    const rows = within(list).getAllByRole('button')
    expect(rows).toHaveLength(3)
    expect(rows.map((row) => row.textContent)).toEqual([
      'spec/a.mdspec-00001-ximplements points at unknown document "spec-09999-ghost"',
      'spec/bad.mdspec-00002-badstatus "nope" is not a status of a living document',
      'idea/none.mdfront matter is missing',
    ])
  })

  // spec-00002-AC-13.2 — nothing to list, so nothing to click
  it('keeps the no-issues wording and offers no entry when the count is zero', async () => {
    graph = { ...GRAPH, issues: [] }
    await openBoard()

    expect(screen.getByText('no issues')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Open the anomaly list' })).toBeNull()
  })

  // spec-00002-AC-13.3
  it('closes on Escape', async () => {
    await openBoard()
    await openAnomalies()

    await userEvent.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByRole('list', { name: 'Anomalies' })).toBeNull(), SETTLED)
  })

  // spec-00002-AC-13.4 — a broken link belongs to the side that declared it
  it('gives a broken relation the declaring document as its source', async () => {
    await openBoard()

    const list = await openAnomalies()

    // The source is A — its path and its id — and the id it failed to reach is
    // only ever part of the problem text.
    const row = within(list).getAllByRole('button')[0]!
    expect(row.textContent).toMatch(/^spec\/a\.mdspec-00001-x/)
    expect(within(row).queryByText('spec-09999-ghost')).toBeNull()
  })

  /**
   * The three-case source reading of design-00001 §7: the path is always the
   * source, and which id stands beside it is not one test but three. The
   * colliding node is the case a single «nodeId !== path» would have got wrong —
   * its key *is* its path, and the id it collided on is the anomaly itself.
   */
  it('shows the colliding id beside the path of a node keyed by its path', async () => {
    const clash = node({
      id: 'rule/first.md',
      path: 'rule/first.md',
      type: 'rule',
      duplicateOf: 'rule-00001-clash',
      ok: false,
      problems: ['id "rule-00001-clash" is also declared by rule/second.md'],
    })
    graph = {
      ...GRAPH,
      nodes: [SPEC, clash],
      issues: [
        { path: 'rule/first.md', nodeId: 'rule/first.md', message: 'id "rule-00001-clash" is also declared by rule/second.md' },
      ],
    }
    await openBoard()

    const list = await openAnomalies()

    expect(within(list).getAllByRole('button')[0]!.textContent).toBe(
      'rule/first.mdrule-00001-clashid "rule-00001-clash" is also declared by rule/second.md',
    )
  })
})

/** spec-00002-FR-14: the diagnostics count is the way into the diagnostics. */
describe('the diagnostics list', () => {
  // spec-00002-AC-14.1
  it('lists every diagnostic with its source, its kind, and its detail', async () => {
    await openBoard()
    expect(screen.getByText('2 diagnostics')).toBeTruthy()

    const list = await openDiagnostics()

    const rows = within(list).getAllByRole('button')
    expect(rows).toHaveLength(2)
    expect(rows.map((row) => row.textContent)).toEqual([
      'spec-00001-xitem-shapeline 4**spec-00001-FR-2** drifted out of shape',
      'spec-00002-badrelation-fieldinforms is not a relation field a spec document carries',
    ])
  })

  // spec-00002-AC-14.2 — the zero state is no badge at all, so there is no entry
  it('renders nothing at all when the count is zero', async () => {
    graph = { ...GRAPH, diagnostics: [] }
    await openBoard()

    expect(screen.queryByText(/diagnostics/)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Open the diagnostics list' })).toBeNull()
  })

  /**
   * spec-00002-AC-14.3: `spec-00002-bad` is an anomalous node *and* the source
   * of a diagnostic. Each list carries its own reason for it, and neither
   * carries the other's.
   */
  it('carries no anomaly of a document that is also an anomalous node', async () => {
    await openBoard()

    const list = await openDiagnostics()

    expect(within(list).queryByText(/is not a status of a living document/)).toBeNull()
    expect(within(list).getAllByRole('button')).toHaveLength(DIAGNOSTICS.length)
  })

  /**
   * The detail is evidence, not reading matter, so a long source line is cut;
   * a diagnostic that carries none — `unattributable` need not — shows the
   * source and the kind and stops there.
   */
  it('cuts a long detail and shows none where a diagnostic carries none', async () => {
    const line = `**spec-00001-FR-9** ${'drifted '.repeat(20)}`
    graph = {
      ...GRAPH,
      diagnostics: [
        { docId: 'spec-00001-x', kind: 'item-shape', line: 4, text: line },
        { docId: 'spec-00001-x', kind: 'unattributable', declaredId: 'spec-00001-AC-9.1' },
      ],
    }
    await openBoard()

    const rows = within(await openDiagnostics()).getAllByRole('button')

    expect(within(rows[0]!).getByText(`${line.slice(0, 80)}…`)).toBeTruthy()
    expect(rows[1]!.textContent).toBe('spec-00001-xunattributable')
  })

  // spec-00002-AC-14.4 — the kind the governance round added, named as it is
  it('names the relation-field kind, and leaves the line empty for it', async () => {
    await openBoard()

    const list = await openDiagnostics()

    const row = within(list).getAllByRole('button')[1]!
    expect(within(row).getByText('relation-field')).toBeTruthy()
    expect(row.textContent).not.toContain('line')
  })
})

/** spec-00002-FR-15: every row goes to a node, and every entry has one. */
describe('picking a row of a drilldown list', () => {
  // spec-00002-AC-15.1 — broken front matter, but an id all the same
  it('goes to the node of an anomalous document that still has an id', async () => {
    await openBoard()
    const list = await openAnomalies()

    await userEvent.click(within(list).getAllByRole('button')[1]!)

    await waitFor(() => expect(screen.getByRole('toolbar', { name: /spec-00002-bad/ })).toBeTruthy(), SETTLED)
    expect(screen.queryByRole('list', { name: 'Anomalies' })).toBeNull()
  })

  // spec-00002-AC-15.2
  it('goes to the spec a diagnostic came from', async () => {
    await openBoard()
    const list = await openDiagnostics()

    await userEvent.click(within(list).getAllByRole('button')[0]!)

    await waitFor(() => expect(screen.getByRole('toolbar', { name: /spec-00001-x/ })).toBeTruthy(), SETTLED)
    expect(screen.queryByRole('list', { name: 'Diagnostics' })).toBeNull()
  })

  // spec-00002-AC-15.3 — a file with no readable front matter is a node too
  it('goes to the path-keyed node of a file whose front matter will not parse', async () => {
    await openBoard()
    const list = await openAnomalies()

    await userEvent.click(within(list).getAllByRole('button')[2]!)

    await waitFor(() => expect(screen.getByRole('toolbar', { name: /idea\/none\.md/ })).toBeTruthy(), SETTLED)
  })

  // spec-00002-AC-15.4
  it('goes to the declaring document of a broken relation', async () => {
    await openBoard()
    const list = await openAnomalies()

    await userEvent.click(within(list).getAllByRole('button')[0]!)

    await waitFor(() => expect(screen.getByRole('toolbar', { name: /spec-00001-x/ })).toBeTruthy(), SETTLED)
  })

  // spec-00002 §7 第 1 条 — the keyboard reaches and fires a row, as the pointer does
  it('opens a list and fires a row from the keyboard alone', async () => {
    await openBoard()

    screen.getByRole('button', { name: 'Open the anomaly list' }).focus()
    await userEvent.keyboard('{Enter}')
    const list = await screen.findByRole('list', { name: 'Anomalies' })
    within(list).getAllByRole('button')[0]!.focus()
    await userEvent.keyboard('{Enter}')

    await waitFor(() => expect(screen.getByRole('toolbar', { name: /spec-00001-x/ })).toBeTruthy(), SETTLED)
  })
})
