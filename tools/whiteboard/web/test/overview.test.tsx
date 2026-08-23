// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import type { ItemsView } from '../../src/requirements.ts'
import { Board } from '../src/Board.tsx'
import { type CoverageRow, api } from '../src/api.ts'

// Opening the board, drilling into a sub-canvas and pushing a change through it
// is heavy, and the suite runs its files side by side; the default five seconds
// measures the load, not the view.
vi.setConfig({ testTimeout: 30_000 })

/** The docs-change channel under the test's hand — the one a refresh comes down. */
class ChannelSocket {
  static opened: ChannelSocket[] = []
  static readonly OPEN = 1
  static get last(): ChannelSocket {
    return ChannelSocket.opened[ChannelSocket.opened.length - 1]!
  }

  readyState = 0
  private readonly listeners: Record<string, Array<(event: { data: string }) => void>> = {}

  constructor(readonly url: string) {
    ChannelSocket.opened.push(this)
  }

  addEventListener(type: string, listener: (event: { data: string }) => void) {
    ;(this.listeners[type] ??= []).push(listener)
  }

  removeEventListener() {}
  send() {}
  close() {
    this.readyState = 3
    this.emit('close')
  }

  connect() {
    this.readyState = ChannelSocket.OPEN
    this.emit('open')
  }

  signal() {
    this.emit('message', { data: '' })
  }

  private emit(type: string, event: { data: string } = { data: '' }) {
    for (const listener of this.listeners[type] ?? []) listener(event)
  }
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

const SPEC = node()
const RULE = node({ id: 'rule-00001-y', type: 'rule', status: 'draft', title: 'Docs workflow', path: 'rule/a.md' })
const OTHER = node({ id: 'spec-00002-z', type: 'spec', status: 'archived', title: 'Governance', path: 'spec/b.md' })
const GRAPH: DocGraph = { nodes: [SPEC, RULE, OTHER], edges: [], issues: [], diagnostics: [], idOwners: {} }

/** Five items over the three states: two verified, one failing, two uncovered. */
const SPEC_ROW: CoverageRow = {
  docId: 'spec-00001-x',
  title: 'Whiteboard spec',
  verified: 2,
  failing: 1,
  uncovered: 2,
  items: [
    { id: 'spec-00001-FR-1', coverage: 'verified' },
    { id: 'spec-00001-FR-2', coverage: 'failing' },
    { id: 'spec-00001-FR-3', coverage: 'uncovered' },
    { id: 'spec-00001-FR-4', coverage: 'uncovered' },
    { id: 'spec-00001-FR-5', coverage: 'verified' },
  ],
}
/** A rule that declares nothing — the expansion has an empty state of its own. */
const RULE_ROW: CoverageRow = {
  docId: 'rule-00001-y',
  title: 'Docs workflow',
  verified: 0,
  failing: 0,
  uncovered: 0,
  items: [],
}
const OTHER_ROW: CoverageRow = {
  docId: 'spec-00002-z',
  title: 'Governance',
  verified: 1,
  failing: 0,
  uncovered: 0,
  items: [{ id: 'spec-00002-FR-1', coverage: 'verified' }],
}

const ITEMS: ItemsView = {
  items: [{ id: 'spec-00001-FR-1', text: 'the first thing', criteria: [], rows: [], coverage: 'verified' }],
  diagnostics: [],
}

/** What the server is serving; a test moves the disk by moving these. */
let graph: DocGraph
let rows: CoverageRow[]

function serve() {
  graph = GRAPH
  rows = [SPEC_ROW, RULE_ROW, OTHER_ROW]
  vi.spyOn(api, 'graph').mockImplementation(async () => structuredClone(graph))
  vi.spyOn(api, 'coverage').mockImplementation(async () => structuredClone(rows))
  vi.spyOn(api, 'items').mockResolvedValue(ITEMS)
  vi.spyOn(api, 'transitions').mockResolvedValue([])
  vi.spyOn(api, 'nextSteps').mockResolvedValue([])
  vi.spyOn(api, 'session').mockResolvedValue({ current: null })
  vi.spyOn(api, 'config').mockResolvedValue({
    types: { spec: 'living', rule: 'living' },
    relations: [],
    flow: {},
    focus: {},
    agents: [{ name: 'claude', command: 'claude', args: [] }],
    entry: [],
    carries: {},
    maxSessions: 3,
    clarifiable: [],
    auditable: [],
  })
}

const SETTLED = { timeout: 20_000, interval: 25 }

async function settle(links = 3) {
  for (let link = 0; link < links; link += 1) {
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
  }
}

async function openBoard() {
  render(<Board />)
  await waitFor(() => expect(screen.getByTestId('node-spec-00001-x')).toBeTruthy(), SETTLED)
  await act(async () => ChannelSocket.last.connect())
  await settle()
}

/** The top-bar entry, and the view it opens (spec-00002-FR-10). */
async function openCoverage() {
  await userEvent.click(screen.getByRole('button', { name: 'Coverage' }))
  await waitFor(() => expect(screen.getByRole('list', { name: 'Coverage by document' })).toBeTruthy(), SETTLED)
}

const list = () => screen.getByRole('list', { name: 'Coverage by document' })
const docRow = (docId: string) => within(list()).getByRole('button', { name: new RegExp(docId) })

/** A change on disk, pushed down the channel. */
async function push() {
  await act(async () => ChannelSocket.last.signal())
  await settle()
}

beforeEach(() => {
  ChannelSocket.opened = []
  vi.stubGlobal('WebSocket', ChannelSocket)
  serve()
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** spec-00002-FR-10: the whole repo's coverage, from the top bar. */
describe('the global coverage view', () => {
  // spec-00002-AC-10.1
  it('lists every spec and rule, each with its three counts', async () => {
    await openBoard()

    await openCoverage()

    const rendered = within(list()).getAllByRole('button')
    expect(rendered.map((row) => row.textContent)).toEqual([
      'spec-00001-xWhiteboard spec212',
      'rule-00001-yDocs workflow000',
      'spec-00002-zGovernance100',
    ])
  })

  // spec-00002-AC-10.2 — and the count is readable without reading its colour
  it('marks each count with its own icon and accessible name', async () => {
    await openBoard()

    await openCoverage()

    const row = docRow('spec-00001-x')
    expect(within(row).getByLabelText('2 uncovered')).toBeTruthy()
    expect(within(row).getByLabelText('2 verified')).toBeTruthy()
    expect(within(row).getByLabelText('1 failing')).toBeTruthy()
  })

  // spec-00002-AC-10.3
  it('says the repo holds no spec and no rule rather than showing an empty list', async () => {
    rows = []
    await openBoard()

    await userEvent.click(screen.getByRole('button', { name: 'Coverage' }))

    expect(await screen.findByText('no spec or rule under docs/ yet')).toBeTruthy()
    expect(screen.queryByRole('list', { name: 'Coverage by document' })).toBeNull()
  })

  // spec-00002-AC-10.4 — a record gained a passing row outside the board
  it('updates its counts when a refresh arrives while it is open', async () => {
    await openBoard()
    await openCoverage()
    expect(within(docRow('spec-00001-x')).getByLabelText('2 uncovered')).toBeTruthy()
    rows = [{ ...SPEC_ROW, verified: 3, uncovered: 1 }, RULE_ROW, OTHER_ROW]

    await push()

    await waitFor(() => expect(within(docRow('spec-00001-x')).getByLabelText('1 uncovered')).toBeTruthy(), SETTLED)
    expect(within(docRow('spec-00001-x')).getByLabelText('3 verified')).toBeTruthy()
  })

  /**
   * The other half of the ruling (design-00001 §6): the heaviest read the board
   * makes is not made while nobody is looking at it.
   */
  it('is not read at all while the view is closed', async () => {
    await openBoard()

    await push()

    expect(api.coverage).not.toHaveBeenCalled()
  })

  // spec-00002-AC-10.5 — a dialog takes no side slot
  it('opens over an editor holding an unsaved buffer, leaving it alone', async () => {
    vi.spyOn(api, 'doc').mockResolvedValue({ path: 'spec/a.md', content: '# Spec\n\nbody\n', hash: 'hash-1' })
    await openBoard()
    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }))
    await waitFor(() => expect(screen.getByTestId('editor-host').textContent).toContain('body'), SETTLED)
    await userEvent.click(screen.getByTestId('editor-host').querySelector('.cm-content')!)
    await userEvent.keyboard('X')

    await openCoverage()

    expect(screen.getByTestId('editor-host')).toBeTruthy()
    expect(document.querySelector('.cm-content')?.textContent).toContain('X')
  })

  // spec-00002-AC-10.6
  it('opens while the board is inside a sub-canvas', async () => {
    await openBoard()
    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    await userEvent.click(await screen.findByRole('button', { name: /Expand as sub-canvas/ }))
    await waitFor(() => expect(screen.getByTestId('sub-item-spec-00001-FR-1')).toBeTruthy(), SETTLED)

    await openCoverage()

    // The dialog is modal, so the canvas behind it is out of the a11y tree; the
    // sub-canvas is still standing all the same.
    expect(screen.getByTestId('sub-item-spec-00001-FR-1')).toBeTruthy()
  })

  // spec-00002-AC-10.7 — Radix's own escape, tested because we rely on it
  it('closes on Escape', async () => {
    await openBoard()
    await openCoverage()

    await userEvent.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByRole('list', { name: 'Coverage by document' })).toBeNull(), SETTLED)
  })

  // spec-00002-AC-10.8
  it('closes on the close control', async () => {
    await openBoard()
    await openCoverage()

    await userEvent.click(screen.getByRole('button', { name: 'Close' }))

    await waitFor(() => expect(screen.queryByRole('list', { name: 'Coverage by document' })).toBeNull(), SETTLED)
  })
})

/** spec-00002-FR-11: one row open at a time, held by document id. */
describe('a row of the coverage view', () => {
  // spec-00002-AC-11.1
  it('lists every item id with its coverage when it is expanded', async () => {
    await openBoard()
    await openCoverage()

    await userEvent.click(docRow('spec-00001-x'))

    const entries = within(screen.getByLabelText('Items of spec-00001-x')).getAllByRole('button')
    expect(entries.map((entry) => entry.textContent)).toEqual([
      'spec-00001-FR-1',
      'spec-00001-FR-2',
      'spec-00001-FR-3',
      'spec-00001-FR-4',
      'spec-00001-FR-5',
    ])
    // The state travels by icon and accessible name, not by colour alone.
    expect(entries.map((entry) => within(entry).getByRole('img').getAttribute('aria-label'))).toEqual([
      'verified',
      'failing',
      'uncovered',
      'uncovered',
      'verified',
    ])
  })

  // spec-00002-AC-11.2
  it('says a document declares no items rather than opening onto nothing', async () => {
    await openBoard()
    await openCoverage()

    await userEvent.click(docRow('rule-00001-y'))

    expect(within(screen.getByLabelText('Items of rule-00001-y')).getByText('no requirement items')).toBeTruthy()
  })

  // spec-00002-AC-11.3
  it('collapses again when its row is clicked a second time', async () => {
    await openBoard()
    await openCoverage()
    await userEvent.click(docRow('spec-00001-x'))
    expect(screen.getByLabelText('Items of spec-00001-x')).toBeTruthy()

    await userEvent.click(docRow('spec-00001-x'))

    expect(screen.queryByLabelText('Items of spec-00001-x')).toBeNull()
    expect(docRow('spec-00001-x').getAttribute('aria-expanded')).toBe('false')
  })

  // spec-00002-AC-11.4 — at most one row open at a time
  it('collapses the open row when another is expanded', async () => {
    await openBoard()
    await openCoverage()
    await userEvent.click(docRow('spec-00001-x'))

    await userEvent.click(docRow('spec-00002-z'))

    expect(screen.queryByLabelText('Items of spec-00001-x')).toBeNull()
    expect(screen.getByLabelText('Items of spec-00002-z')).toBeTruthy()
  })

  // spec-00002-AC-11.5 — held by document id, so a refresh brings it back open
  it('stays expanded through a refresh', async () => {
    await openBoard()
    await openCoverage()
    await userEvent.click(docRow('spec-00001-x'))

    await push()

    expect(screen.getByLabelText('Items of spec-00001-x')).toBeTruthy()
  })

  /**
   * design-00002 §10 «就近关闭», one level further: the row of a document that
   * has left the tree goes, and its expansion with it — the view itself stays,
   * because it depends on no one document.
   */
  it('loses the row and its expansion when the document is deleted, and stays open', async () => {
    await openBoard()
    await openCoverage()
    await userEvent.click(docRow('spec-00001-x'))
    rows = [RULE_ROW, OTHER_ROW]

    await push()

    await waitFor(() => expect(screen.queryByLabelText('Items of spec-00001-x')).toBeNull(), SETTLED)
    expect(within(list()).queryByRole('button', { name: /spec-00001-x/ })).toBeNull()
    expect(list()).toBeTruthy()
  })
})

/** spec-00002-FR-12: an item is the way to the document that declares it. */
describe('picking an item in the coverage view', () => {
  async function pick(itemId: string, docId: string) {
    await openCoverage()
    await userEvent.click(docRow(docId))
    await userEvent.click(screen.getByRole('button', { name: new RegExp(`${itemId}$`) }))
    await settle()
  }

  // spec-00002-AC-12.1 and AC-12.3
  it('closes the view, selects the document, and opens its inspector', async () => {
    await openBoard()

    await pick('spec-00001-FR-3', 'spec-00001-x')

    await waitFor(() => expect(screen.getByRole('toolbar', { name: /spec-00001-x/ })).toBeTruthy(), SETTLED)
    expect(await screen.findByLabelText('Requirements of spec-00001-x')).toBeTruthy()
    expect(screen.queryByRole('list', { name: 'Coverage by document' })).toBeNull()
  })

  // spec-00002-AC-12.2 — the editor keeps the right slot; the selection happens anyway
  it('selects the document without an inspector while the editor holds the slot', async () => {
    vi.spyOn(api, 'doc').mockResolvedValue({ path: 'spec/b.md', content: '# Other\n', hash: 'hash-2' })
    await openBoard()
    fireEvent.click(screen.getByTestId('node-spec-00002-z'))
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }))
    await waitFor(() => expect(screen.getByTestId('editor-host')).toBeTruthy(), SETTLED)

    await pick('spec-00001-FR-1', 'spec-00001-x')

    await waitFor(() => expect(screen.getByRole('toolbar', { name: /spec-00001-x/ })).toBeTruthy(), SETTLED)
    expect(screen.queryByLabelText('Requirements of spec-00001-x')).toBeNull()
    expect(screen.getByTestId('editor-host')).toBeTruthy()
  })

  // spec-00002-AC-12.4
  it('comes back up out of a sub-canvas to the document it was told to go to', async () => {
    await openBoard()
    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    await userEvent.click(await screen.findByRole('button', { name: /Expand as sub-canvas/ }))
    await waitFor(() => expect(screen.getByTestId('sub-item-spec-00001-FR-1')).toBeTruthy(), SETTLED)

    await pick('spec-00002-FR-1', 'spec-00002-z')

    await waitFor(() => expect(screen.queryByRole('navigation', { name: 'breadcrumb' })).toBeNull(), SETTLED)
    expect(screen.getByRole('toolbar', { name: /spec-00002-z/ })).toBeTruthy()
  })

  /**
   * spec-00002-AC-12.5 — the race window the ruling of FR-12 is about: the push
   * has not reached the view yet, so the row is still there while the document
   * is not.
   */
  it('refuses with a toast and keeps the selection when the document has gone', async () => {
    const toastError = vi.spyOn(toast, 'error').mockImplementation(() => 'id')
    await openBoard()
    fireEvent.click(screen.getByTestId('node-spec-00002-z'))
    await waitFor(() => expect(screen.getByRole('toolbar', { name: /spec-00002-z/ })).toBeTruthy(), SETTLED)
    // The graph has moved on; the payload the open view holds has not.
    graph = { ...GRAPH, nodes: [RULE, OTHER] }
    await push()

    await pick('spec-00001-FR-1', 'spec-00001-x')

    await waitFor(() => expect(toastError).toHaveBeenCalledWith('no document spec-00001-x on the board'), SETTLED)
    expect(screen.getByRole('toolbar', { name: /spec-00002-z/ })).toBeTruthy()
  })
})
