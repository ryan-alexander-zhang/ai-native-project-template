// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import type { ItemsView } from '../../src/requirements.ts'
import { api } from '../src/api.ts'

// Rendering the whole board per case, in a suite whose files run side by side.
vi.setConfig({ testTimeout: 30_000 })

const setCenter = vi.fn()

// The viewport move is React Flow's to make, so the module is mocked to observe it.
vi.mock('@xyflow/react', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@xyflow/react')>()
  return { ...actual, useReactFlow: () => ({ ...actual.useReactFlow(), setCenter }) }
})

const { Board } = await import('../src/Board.tsx')

/** The docs-change channel under the test's hand: one signal is one refresh (spec-00001-FR-42). */
class ChannelSocket {
  static opened: ChannelSocket[] = []
  static readonly OPEN = 1
  static get last(): ChannelSocket {
    return ChannelSocket.opened[ChannelSocket.opened.length - 1]!
  }

  readyState = 1
  private readonly listeners: Record<string, Array<(event: { data: string }) => void>> = {}

  constructor(readonly url: string) {
    ChannelSocket.opened.push(this)
  }

  addEventListener(type: string, listener: (event: { data: string }) => void) {
    ;(this.listeners[type] ??= []).push(listener)
  }

  removeEventListener() {}
  send() {}
  close() {}

  /** One «docs changed» frame, which carries nothing (design-00001 §6). */
  signal() {
    for (const listener of this.listeners.message ?? []) listener({ data: '' })
  }
}

function node(overrides: Partial<DocNode> = {}): DocNode {
  return {
    id: 'spec-00001',
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
const PLAN_1 = node({ id: 'plan-00001', type: 'plan', status: 'open', title: 'First plan', path: 'plan/a.md' })
const PLAN_2 = node({ id: 'plan-00002', type: 'plan', status: 'draft', title: 'Second plan', path: 'plan/b.md' })
const PLAN_3 = node({ id: 'plan-00003', type: 'plan', status: 'resolved', title: 'Third plan', path: 'plan/c.md' })
const RECORD = node({ id: 'record-00001', type: 'record', status: 'active', title: 'First record', path: 'record/a.md' })
const ISSUE = node({ id: 'issue-00001', type: 'issue', status: 'resolved', title: 'The one issue', path: 'issue/a.md' })

function graphOf(nodes: DocNode[]): DocGraph {
  return { nodes: [...nodes], edges: [], issues: [], diagnostics: [], idOwners: {} }
}

/**
 * Deliberately out of both column and row order: the sidebar's order has to come
 * from the layout rule, not from the order the payload happened to arrive in.
 */
const NODES = [PLAN_3, RECORD, SPEC, ISSUE, PLAN_1, PLAN_2]

/** What `GET /api/graph` is answering with; a test moves it by moving this. */
let graph: DocGraph

const ITEMS: ItemsView = {
  items: [
    {
      id: 'spec-00001-FR-1',
      text: 'what FR-1 asks of the system',
      criteria: [{ id: 'spec-00001-AC-1.1', text: 'Given a board When it loads Then it works', rows: [] }],
      rows: [],
      coverage: 'uncovered',
    },
  ],
  diagnostics: [],
}

beforeEach(() => {
  localStorage.clear()
  setCenter.mockClear()
  ChannelSocket.opened = []
  vi.stubGlobal('WebSocket', ChannelSocket)
  graph = graphOf(NODES)
  vi.spyOn(api, 'graph').mockImplementation(async () => graph)
  vi.spyOn(api, 'items').mockResolvedValue(ITEMS)
  vi.spyOn(api, 'transitions').mockResolvedValue([])
  vi.spyOn(api, 'nextSteps').mockResolvedValue([])
  vi.spyOn(api, 'sessions').mockResolvedValue([])
  vi.spyOn(api, 'config').mockResolvedValue({
    // The declared column order, which is the group order (decision-00002 §2).
    types: { spec: 'living', plan: 'work', task: 'work', record: 'work', issue: 'work' },
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
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const sidebar = () => screen.getByRole('navigation', { name: 'Documents' })

/** Every control of the sidebar, in the order it is read in. */
const entries = () => within(sidebar()).getAllByRole('button')

/** What a control reads as: the DOM runs its parts together, a reader does not. */
function words(control: Element): string {
  const parts: string[] = []
  const walker = document.createTreeWalker(control, NodeFilter.SHOW_TEXT)
  while (walker.nextNode()) {
    const part = walker.currentNode.textContent?.trim()
    if (part) parts.push(part)
  }
  return parts.join(' ')
}

const names = () => entries().map(words)

/** A group header, named by its type and its count. */
const header = (type: string) => entries().find((entry) => entry.textContent?.startsWith(type) === true)!

/** A document's row, named by the id it opens with. */
function row(id: string): HTMLElement {
  return entries().find((entry) => entry.textContent?.startsWith(id) === true)!
}

/** The row that carries the selection, if any (spec-00008-FR-3). */
const current = () => entries().filter((entry) => entry.getAttribute('aria-current') === 'true')

async function openBoard() {
  const rendered = render(<Board />)
  await waitFor(() => expect(screen.getByRole('navigation', { name: 'Documents' })).toBeTruthy())
  return rendered
}

/** Open the board with the type groups drawn and the first read settled. */
async function openWithDocuments() {
  const rendered = await openBoard()
  await waitFor(() => expect(header('plan')).toBeTruthy())
  return rendered
}

/**
 * Every group starts collapsed (spec-00008-AC-4.3), so a case that reads rows
 * opens the groups it reads the way a user does: by pressing their headers.
 */
async function expand(...types: string[]) {
  for (const type of types) await userEvent.click(header(type))
}

/** A refresh, as a push delivers it (spec-00001-FR-44). */
async function refreshWith(next: DocGraph) {
  graph = next
  await act(async () => {
    ChannelSocket.last.signal()
  })
}

const toggle = () => screen.getByRole('button', { name: /navigation$/ })

describe('the navigation sidebar', () => {
  // spec-00008-AC-1.1
  it('groups every document by type in column order, each group in row order', async () => {
    await openWithDocuments()
    await expand('spec', 'plan', 'record', 'issue')

    expect(names()).toEqual([
      'spec 1',
      'spec-00001 active Whiteboard spec',
      'plan 3',
      'plan-00001 open First plan',
      'plan-00002 draft Second plan',
      'plan-00003 resolved Third plan',
      'record 1',
      'record-00001 active First record',
      'issue 1',
      'issue-00001 resolved The one issue',
    ])
  })

  // spec-00008-AC-1.2
  it('puts an undeclared type and then the documents without one after the declared ones', async () => {
    graph = graphOf([
      ...NODES,
      node({ id: 'memo-00001', type: 'memo', status: 'draft', title: 'A memo', path: 'memo/a.md' }),
      node({ id: 'loose-00001', type: undefined, status: 'draft', title: 'No type at all', path: 'loose/a.md' }),
    ])
    await openWithDocuments()
    await expand('memo', 'untyped')

    expect(names().slice(-4)).toEqual([
      'memo 1',
      'memo-00001 draft A memo',
      'untyped 1',
      'loose-00001 draft No type at all',
    ])
  })

  // spec-00008-AC-1.3
  it('gives each half of a collision its own row, by path, beside the id they collide on', async () => {
    graph = graphOf([
      SPEC,
      node({ id: 'plan/one.md', path: 'plan/one.md', type: 'plan', title: 'One', duplicateOf: 'plan-00009' }),
      node({ id: 'plan/two.md', path: 'plan/two.md', type: 'plan', title: 'Two', duplicateOf: 'plan-00009' }),
    ])
    await openWithDocuments()
    await expand('plan')
    await waitFor(() => expect(row('plan/one.md')).toBeTruthy())

    expect(names().slice(-3)).toEqual([
      'plan 2',
      'plan/one.md plan-00009 active One',
      'plan/two.md plan-00009 active Two',
    ])
  })

  // spec-00008-AC-1.4
  it('says «front matter problem» where an anomalous document’s status would be', async () => {
    graph = graphOf([node({ id: 'spec/bad.md', path: 'spec/bad.md', status: 'nope', ok: false, title: 'Broken' })])
    await openBoard()
    await waitFor(() => expect(header('spec')).toBeTruthy())
    await expand('spec')
    await waitFor(() => expect(row('spec/bad.md')).toBeTruthy())

    expect(row('spec/bad.md').textContent).toContain('front matter problem')
    expect(row('spec/bad.md').textContent).not.toContain('nope')
  })

  // spec-00008-AC-1.5
  it('holds no group at all when no document is on the board', async () => {
    graph = graphOf([])
    await openBoard()

    await waitFor(() => expect(screen.getByText('no documents under docs/ yet')).toBeTruthy())
    expect(within(sidebar()).queryAllByRole('button')).toEqual([])
  })
})

describe('going to a document from the sidebar', () => {
  // spec-00008-AC-2.1
  it('selects the document of the row and centres the viewport on it', async () => {
    await openWithDocuments()
    await expand('plan')

    await userEvent.click(row('plan-00002'))

    await waitFor(() => expect(screen.getByRole('toolbar', { name: /plan-00002/ })).toBeTruthy())
    expect(setCenter).toHaveBeenCalled()
  })

  // spec-00008-AC-2.2
  it('leaves a sub-canvas and its detail behind on the way', async () => {
    await openWithDocuments()
    await expand('plan')
    fireEvent.click(screen.getByTestId('node-spec-00001'))
    await waitFor(() => expect(screen.getByLabelText('Requirements of spec-00001')).toBeTruthy())
    await userEvent.click(screen.getByRole('button', { name: /Expand as sub-canvas/ }))
    await waitFor(() => expect(screen.getByTestId('sub-item-spec-00001-FR-1')).toBeTruthy())
    fireEvent.click(screen.getByTestId('sub-item-spec-00001-FR-1'))
    await waitFor(() => expect(screen.getByLabelText('Details of spec-00001-FR-1')).toBeTruthy())
    setCenter.mockClear()

    await userEvent.click(row('plan-00002'))

    await waitFor(() => expect(screen.getByRole('toolbar', { name: /plan-00002/ })).toBeTruthy())
    expect(screen.queryByLabelText('Details of spec-00001-FR-1')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Board' })).toBeNull()
    expect(setCenter).toHaveBeenCalled()
  })

  // spec-00008-AC-2.3 — the Given's drag away from the node is React Flow's own
  // viewport state, which jsdom cannot move; what the AC observes is the
  // re-centring call itself, so the mock is cleared where the drag would be.
  it('centres again on the row already selected', async () => {
    await openWithDocuments()
    await expand('plan')
    await userEvent.click(row('plan-00002'))
    await waitFor(() => expect(screen.getByRole('toolbar', { name: /plan-00002/ })).toBeTruthy())
    setCenter.mockClear()

    await userEvent.click(row('plan-00002'))

    await waitFor(() => expect(setCenter).toHaveBeenCalled())
    expect(screen.getByRole('toolbar', { name: /plan-00002/ })).toBeTruthy()
  })
})

describe('the sidebar following the selection', () => {
  // spec-00008-AC-3.1
  it('highlights the row of the node picked on the canvas and scrolls it into view', async () => {
    const scrollIntoView = vi.spyOn(Element.prototype, 'scrollIntoView')
    await openWithDocuments()

    fireEvent.click(screen.getByTestId('node-spec-00001'))

    await waitFor(() => expect(current().map((entry) => entry.textContent)).toHaveLength(1))
    expect(current()[0]!.textContent).toContain('spec-00001')
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' })
  })

  // spec-00008-AC-3.2
  it('opens the collapsed group the jumped-to document sits in', async () => {
    await openWithDocuments()
    expect(header('spec').getAttribute('aria-expanded')).toBe('false')
    expect(screen.queryByRole('button', { name: /^spec-00001/ })).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: /Find a document/ }))
    await userEvent.type(screen.getByPlaceholderText('Find a document by id or title'), 'spec-00001')
    await userEvent.click(await screen.findByRole('option', { name: /spec-00001/ }))

    await waitFor(() => expect(row('spec-00001')).toBeTruthy())
    expect(row('spec-00001').getAttribute('aria-current')).toBe('true')
  })

  // spec-00008-AC-3.3
  it('highlights nothing once the selection is dropped', async () => {
    const { container } = await openWithDocuments()
    fireEvent.click(screen.getByTestId('node-spec-00001'))
    await waitFor(() => expect(current()).toHaveLength(1))

    fireEvent.click(container.querySelector('.react-flow__pane')!)

    await waitFor(() => expect(current()).toEqual([]))
  })

  // spec-00008-AC-3.4
  it('catches up on the selection when it is brought back', async () => {
    await openWithDocuments()
    await userEvent.click(toggle())
    expect(screen.queryByRole('navigation', { name: 'Documents' })).toBeNull()
    fireEvent.click(screen.getByTestId('node-spec-00001'))
    await waitFor(() => expect(screen.getByRole('toolbar', { name: /spec-00001/ })).toBeTruthy())
    const scrollIntoView = vi.spyOn(Element.prototype, 'scrollIntoView')

    await userEvent.click(toggle())

    await waitFor(() => expect(row('spec-00001').getAttribute('aria-current')).toBe('true'))
    expect(header('spec').getAttribute('aria-expanded')).toBe('true')
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' })
  })
})

describe('expanding and collapsing a group', () => {
  // spec-00008-AC-4.1
  it('brings the rows out and keeps the header and its count', async () => {
    await openWithDocuments()

    await userEvent.click(header('plan'))

    expect(names()).toEqual([
      'spec 1',
      'plan 3',
      'plan-00001 open First plan',
      'plan-00002 draft Second plan',
      'plan-00003 resolved Third plan',
      'record 1',
      'issue 1',
    ])
  })

  // spec-00008-AC-4.4
  it('puts the rows away on the next press and keeps the header and its count', async () => {
    await openWithDocuments()
    await expand('plan')

    await userEvent.click(header('plan'))

    expect(screen.queryByRole('button', { name: /^plan-00002/ })).toBeNull()
    expect(names()).toEqual(['spec 1', 'plan 3', 'record 1', 'issue 1'])
  })

  // spec-00008-AC-4.5 — the group is open because the selection opened it, which
  // is the only way FR-3 ever opens one; the press after that is the user's.
  it('stays collapsed when the group is the selected row’s own', async () => {
    await openWithDocuments()
    fireEvent.click(screen.getByTestId('node-plan-00002'))
    await waitFor(() => expect(row('plan-00002').getAttribute('aria-current')).toBe('true'))

    await userEvent.click(header('plan'))

    expect(screen.queryByRole('button', { name: /^plan-00002/ })).toBeNull()
    expect(header('plan').getAttribute('aria-expanded')).toBe('false')
  })

  // spec-00008-AC-4.2
  it('is still expanded the next time the board is opened, alone', async () => {
    const first = await openWithDocuments()
    await expand('plan')
    first.unmount()

    await openBoard()

    await waitFor(() => expect(header('plan')).toBeTruthy())
    expect(header('plan').getAttribute('aria-expanded')).toBe('true')
    expect(header('spec').getAttribute('aria-expanded')).toBe('false')
    expect(header('record').getAttribute('aria-expanded')).toBe('false')
  })

  // spec-00008-AC-4.3
  it('collapses every group when none was ever expanded', async () => {
    await openWithDocuments()

    expect(entries().filter((entry) => entry.getAttribute('aria-expanded') === 'false')).toHaveLength(4)
    expect(entries().filter((entry) => entry.getAttribute('aria-expanded') === 'true')).toEqual([])
    expect(names()).toEqual(['spec 1', 'plan 3', 'record 1', 'issue 1'])
  })
})

describe('the sidebar switch', () => {
  // spec-00008-AC-5.1
  it('opens the board with the sidebar on show', async () => {
    await openWithDocuments()

    expect(sidebar()).toBeTruthy()
    expect(toggle().getAttribute('aria-label')).toBe('Hide navigation')
  })

  // spec-00008-AC-5.2
  it('puts the sidebar away when it is pressed', async () => {
    await openWithDocuments()

    await userEvent.click(toggle())

    expect(screen.queryByRole('navigation', { name: 'Documents' })).toBeNull()
    expect(toggle().getAttribute('aria-label')).toBe('Show navigation')
  })

  // spec-00008-AC-5.4
  it('brings the sidebar back on the next press', async () => {
    await openWithDocuments()
    await userEvent.click(toggle())

    await userEvent.click(toggle())

    expect(sidebar()).toBeTruthy()
  })

  // spec-00008-AC-5.3
  it('leaves the sidebar away the next time the board is opened', async () => {
    const first = await openWithDocuments()
    await userEvent.click(toggle())
    first.unmount()

    render(<Board />)

    await waitFor(() => expect(screen.getByRole('button', { name: 'Show navigation' })).toBeTruthy())
    expect(screen.queryByRole('navigation', { name: 'Documents' })).toBeNull()
  })
})

describe('the sidebar under a refresh', () => {
  // spec-00008-AC-6.1
  it('takes a new document into its group at its row', async () => {
    graph = graphOf([SPEC, PLAN_1, PLAN_3])
    await openWithDocuments()
    await expand('spec', 'plan')
    await waitFor(() => expect(row('plan-00003')).toBeTruthy())

    await refreshWith(graphOf([SPEC, PLAN_1, PLAN_2, PLAN_3]))

    await waitFor(() => expect(row('plan-00002')).toBeTruthy())
    expect(names()).toEqual([
      'spec 1',
      'spec-00001 active Whiteboard spec',
      'plan 3',
      'plan-00001 open First plan',
      'plan-00002 draft Second plan',
      'plan-00003 resolved Third plan',
    ])
  })

  // spec-00008-AC-6.2
  it('keeps the expanded group expanded and the collapsed one collapsed', async () => {
    await openWithDocuments()
    await expand('spec')

    await refreshWith(
      graphOf([...NODES, node({ id: 'record-00002', type: 'record', title: 'Another record', path: 'record/b.md' })]),
    )

    await waitFor(() => expect(header('record').textContent).toContain('2'))
    expect(header('plan').getAttribute('aria-expanded')).toBe('false')
    expect(header('spec').getAttribute('aria-expanded')).toBe('true')
    expect(row('spec-00001')).toBeTruthy()
  })

  // spec-00008-AC-6.7
  it('keeps the selected row highlighted', async () => {
    await openWithDocuments()
    fireEvent.click(screen.getByTestId('node-spec-00001'))
    await waitFor(() => expect(row('spec-00001').getAttribute('aria-current')).toBe('true'))

    await refreshWith(
      graphOf([...NODES, node({ id: 'record-00002', type: 'record', title: 'Another record', path: 'record/b.md' })]),
    )

    await waitFor(() => expect(header('record').textContent).toContain('2'))
    expect(row('spec-00001').getAttribute('aria-current')).toBe('true')
  })

  // spec-00008-AC-6.3
  it('drops the row and the highlight with the document the selection was on', async () => {
    await openWithDocuments()
    fireEvent.click(screen.getByTestId('node-spec-00001'))
    await waitFor(() => expect(current()).toHaveLength(1))

    await refreshWith(graphOf(NODES.filter((one) => one.id !== 'spec-00001')))

    await waitFor(() => expect(screen.queryByRole('button', { name: /^spec-00001/ })).toBeNull())
    expect(current()).toEqual([])
  })

  // spec-00008-AC-6.4
  it('drops a group with its last row', async () => {
    await openWithDocuments()
    await expand('issue')

    await refreshWith(graphOf(NODES.filter((one) => one.id !== 'issue-00001')))

    await waitFor(() => expect(screen.queryByRole('button', { name: /^issue-00001/ })).toBeNull())
    expect(names().some((name) => name?.startsWith('issue') === true)).toBe(false)
  })

  // spec-00008-AC-6.5
  it('follows a status the refresh changed', async () => {
    await openWithDocuments()
    await expand('plan')
    expect(row('plan-00002').textContent).toContain('draft')

    await refreshWith(graphOf(NODES.map((one) => (one.id === 'plan-00002' ? { ...one, status: 'active' } : one))))

    await waitFor(() => expect(row('plan-00002').textContent).toContain('active'))
  })

  // spec-00008-AC-6.6
  it('opens a new group in its column place', async () => {
    await openWithDocuments()
    expect(names().some((name) => name?.startsWith('task') === true)).toBe(false)

    await refreshWith(
      graphOf([...NODES, node({ id: 'task-00001', type: 'task', status: 'open', title: 'The first task', path: 't/a.md' })]),
    )

    await waitFor(() => expect(header('task')).toBeTruthy())
    expect(names()).toEqual(['spec 1', 'plan 3', 'task 1', 'record 1', 'issue 1'])
  })
})

/**
 * The race window of spec-00008-FR-8: the document has left the board and the
 * list on show still names it. The graph the board holds is moved out from under
 * the rendered rows, which is what that window is — the rows are the last frame,
 * the graph is the truth the click is answered from.
 */
async function loseTheDocument() {
  const gone = graph.nodes.findIndex((one) => one.id === 'plan-00002')
  graph.nodes.splice(gone, 1)
}

describe('a row whose document has left the board', () => {
  // spec-00008-AC-8.1
  it('refuses in place and moves neither the selection nor the viewport', async () => {
    const toastError = vi.spyOn(toast, 'error').mockImplementation(() => 'id')
    await openWithDocuments()
    await expand('plan')
    fireEvent.click(screen.getByTestId('node-spec-00001'))
    await waitFor(() => expect(current()).toHaveLength(1))
    setCenter.mockClear()
    await loseTheDocument()

    await userEvent.click(row('plan-00002'))

    expect(toastError).toHaveBeenCalledWith('no document plan-00002 on the board')
    expect(setCenter).not.toHaveBeenCalled()
    expect(current()[0]!.textContent).toContain('spec-00001')
  })

  // spec-00008-AC-8.2
  it('refuses the same way the second time', async () => {
    const toastError = vi.spyOn(toast, 'error').mockImplementation(() => 'id')
    await openWithDocuments()
    await expand('plan')
    fireEvent.click(screen.getByTestId('node-spec-00001'))
    await waitFor(() => expect(current()).toHaveLength(1))
    setCenter.mockClear()
    await loseTheDocument()
    await userEvent.click(row('plan-00002'))
    expect(toastError).toHaveBeenCalledTimes(1)

    await userEvent.click(row('plan-00002'))

    expect(toastError).toHaveBeenCalledTimes(2)
    expect(toastError).toHaveBeenLastCalledWith('no document plan-00002 on the board')
    expect(setCenter).not.toHaveBeenCalled()
    expect(current()[0]!.textContent).toContain('spec-00001')
  })
})

/**
 * The directory groups the sidebar mirrors from the column (spec-00010-FR-8).
 * Every fixture here is three `docs/`-relative segments deep, which is what
 * makes a group at all (spec-00010-FR-4); the fixtures above are two segments,
 * so the cases above see no directory group and read exactly as they did.
 */
describe('a directory group in the sidebar', () => {
  function reference(id: string, path: string, title: string): DocNode {
    return node({ id, type: 'reference', status: 'active', title, path })
  }

  const TOP_A = reference('reference-00001-a', 'reference/a.md', 'First top-level reference')
  const TOP_B = reference('reference-00002-b', 'reference/b.md', 'Second top-level reference')
  const STRIPE = [
    reference('reference-00011-s', 'reference/stripe/one.md', 'Stripe one'),
    reference('reference-00012-t', 'reference/stripe/two.md', 'Stripe two'),
    reference('reference-00013-u', 'reference/stripe/three.md', 'Stripe three'),
  ]
  const CCBILL = [
    reference('reference-00021-c', 'reference/ccbill/one.md', 'Ccbill one'),
    reference('reference-00022-d', 'reference/ccbill/two.md', 'Ccbill two'),
  ]

  /** The two top-level rows, which every case below reads before the group headers. */
  const TOP_ROWS = [
    'reference-00001-a active First top-level reference',
    'reference-00002-b active Second top-level reference',
  ]
  const STRIPE_ROWS = ['reference-00011-s active Stripe one', 'reference-00012-t active Stripe two', 'reference-00013-u active Stripe three']

  /** The group's card on the canvas, by the testid `GroupNodeCard` carries. */
  const card = (name: string) => screen.getByTestId(`group-reference-reference/${name}`)

  /** Choosing a document from the command palette — one of FR-7's ways in (spec-00010-AC-7.1). */
  async function pick(id: string) {
    await userEvent.click(screen.getByRole('button', { name: /Find a document/ }))
    const search = screen.getByPlaceholderText('Find a document by id or title')
    // The palette keeps what was typed into it last, so a second pick clears it.
    await userEvent.clear(search)
    await userEvent.type(search, id)
    await userEvent.click(await screen.findByRole('option', { name: new RegExp(id) }))
  }

  /**
   * The board with one reference column: two top-level documents and the two
   * directory groups of AC-8.1. The type group is left as the browser has it —
   * collapsed, with nothing remembered — so each case expands what it reads.
   */
  async function openReference(nodes: DocNode[] = [TOP_A, TOP_B, ...STRIPE, ...CCBILL]) {
    vi.spyOn(api, 'config').mockResolvedValue({
      types: { spec: 'living', reference: 'living' },
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
    graph = graphOf(nodes)
    const rendered = await openBoard()
    await waitFor(() => expect(header('reference')).toBeTruthy())
    return rendered
  }

  // spec-00010-AC-8.1
  it('lists the top documents, then a header per directory group, counting them all', async () => {
    await openReference()

    await expand('reference')

    expect(names()).toEqual(['reference 7', ...TOP_ROWS, 'ccbill 2', 'stripe 3'])
    expect(header('ccbill').getAttribute('aria-expanded')).toBe('false')
    expect(header('stripe').getAttribute('aria-expanded')).toBe('false')
  })

  // spec-00010-AC-8.2 — one expanded state, so the press is felt in both places
  it('opens the group in the list and on the canvas when its header is pressed', async () => {
    await openReference()
    await expand('reference')
    expect(screen.queryByTestId('node-reference-00011-s')).toBeNull()

    await userEvent.click(header('stripe'))

    await waitFor(() => expect(row('reference-00011-s')).toBeTruthy())
    expect(names()).toEqual(['reference 7', ...TOP_ROWS, 'ccbill 2', 'stripe 3', ...STRIPE_ROWS])
    expect(header('stripe').getAttribute('aria-expanded')).toBe('true')
    expect(screen.getByTestId('node-reference-00012-t')).toBeTruthy()
    expect(screen.getByTestId('node-reference-00013-u')).toBeTruthy()
  })

  // spec-00010-AC-8.3 — and felt the other way round just the same
  it('opens the group in the list when the canvas group node is clicked', async () => {
    await openReference()
    await expand('reference')

    fireEvent.click(card('stripe'))

    await waitFor(() => expect(row('reference-00011-s')).toBeTruthy())
    expect(names()).toEqual(['reference 7', ...TOP_ROWS, 'ccbill 2', 'stripe 3', ...STRIPE_ROWS])
    expect(header('stripe').getAttribute('aria-expanded')).toBe('true')
  })

  // spec-00010-AC-8.4
  it('opens the type group and the directory group the selection lands in', async () => {
    const scrollIntoView = vi.spyOn(Element.prototype, 'scrollIntoView')
    await openReference()
    expect(header('reference').getAttribute('aria-expanded')).toBe('false')

    await pick('reference-00012-t')

    await waitFor(() => expect(row('reference-00012-t')).toBeTruthy())
    expect(header('reference').getAttribute('aria-expanded')).toBe('true')
    expect(header('stripe').getAttribute('aria-expanded')).toBe('true')
    expect(row('reference-00012-t').getAttribute('aria-current')).toBe('true')
    expect(scrollIntoView.mock.contexts).toContain(row('reference-00012-t'))

    // …and again with the type group already open, so that only the directory
    // group has to give way: the row is still scrolled to, which it can only be
    // once that expansion has drawn it. The call is read by its receiver — the
    // command palette scrolls its own option into view the same way.
    scrollIntoView.mockClear()
    await pick('reference-00021-c')

    await waitFor(() => expect(row('reference-00021-c').getAttribute('aria-current')).toBe('true'))
    expect(header('ccbill').getAttribute('aria-expanded')).toBe('true')
    expect(scrollIntoView.mock.contexts).toContain(row('reference-00021-c'))
  })

  // spec-00010-AC-8.5 — the expansion follows a change of selection and nothing
  // else, so the press that closes the group around the highlighted row holds
  it('stays collapsed when the group closed is the selected row’s own', async () => {
    await openReference()
    await expand('reference')
    await userEvent.click(header('stripe'))
    await userEvent.click(row('reference-00012-t'))
    await waitFor(() => expect(row('reference-00012-t').getAttribute('aria-current')).toBe('true'))

    await userEvent.click(header('stripe'))

    expect(names()).toEqual(['reference 7', ...TOP_ROWS, 'ccbill 2', 'stripe 3'])
    expect(header('stripe').getAttribute('aria-expanded')).toBe('false')
  })

  // spec-00010-AC-9.2 — the sidebar half: a group with no document left is no
  // group at all, so its header goes with the last of them
  it('drops a directory group header with its last document', async () => {
    await openReference([TOP_A, TOP_B, ...STRIPE, CCBILL[0]!])
    await expand('reference')
    expect(header('ccbill')).toBeTruthy()

    await refreshWith(graphOf([TOP_A, TOP_B, ...STRIPE]))

    await waitFor(() => expect(names()).toEqual(['reference 5', ...TOP_ROWS, 'stripe 3']))
  })

  // issue-00026 — the tree's own indent model: one level is one `--tree-indent`,
  // and every row opens with the same two fixed columns, so a child's text starts
  // one indent past its parent's text instead of under its parent's chevron.
  it('indents each level past its parent’s label', async () => {
    await openReference()
    await expand('reference')
    await userEvent.click(header('ccbill'))
    await waitFor(() => expect(row('reference-00021-c')).toBeTruthy())

    // jsdom resolves no CSS variable, so the derivation itself is what is read.
    const indent = (control: HTMLElement) => [control.getAttribute('data-level'), control.style.paddingLeft]

    expect(indent(header('reference'))).toEqual(['0', 'calc(var(--tree-indent) * 0)'])
    expect(indent(row('reference-00001-a'))).toEqual(['1', 'calc(var(--tree-indent) * 1)'])
    expect(indent(header('ccbill'))).toEqual(['1', 'calc(var(--tree-indent) * 1)'])
    expect(indent(row('reference-00021-c'))).toEqual(['2', 'calc(var(--tree-indent) * 2)'])

    // …and jsdom lays nothing out either, so where a row's text starts is read
    // structurally and then computed: every row opens with the same two fixed
    // 16px columns — the fold column and the icon column — and its text is the
    // third, so the text starts at the row's own indent plus that constant and
    // one level down is one indent along and nothing else.
    const INDENT = 16
    const GAP = 8
    function textStart(control: HTMLElement, label: string): number {
      const columns = Array.from(control.children)
      // `class`, not `className`: an svg's is an object, a span's a string.
      expect(columns[0]!.getAttribute('class')).toMatch(/\b(size-4|w-4)\b/)
      expect(columns[1]!.getAttribute('class')).toMatch(/\b(size-4|w-4)\b/)
      expect(columns[2]!.textContent).toContain(label)
      return Number(control.getAttribute('data-level')) * INDENT + 2 * (INDENT + GAP)
    }

    const type = textStart(header('reference'), 'reference')
    const top = textStart(row('reference-00001-a'), 'reference-00001-a')
    const directory = textStart(header('ccbill'), 'ccbill')
    const member = textStart(row('reference-00021-c'), 'reference-00021-c')

    // A document row and a directory header are siblings: one level, one text column.
    expect(top).toBe(directory)
    expect(top).toBe(type + INDENT)
    expect(member).toBe(directory + INDENT)

    // A leaf row's fold column is empty — no chevron to fold it by — and the
    // status dot holds the icon column, the very one a header's icon holds.
    const [fold, icon] = Array.from(row('reference-00021-c').children)
    expect(fold!.children).toHaveLength(0)
    expect(fold!.textContent).toBe('')
    expect(icon!.querySelector('span.rounded-full')).toBeTruthy()
  })

  // issue-00026 — the fold chevron is the leading slot of a directory header as
  // it is of a type header, and the count badge is what the row ends with.
  it('puts the fold chevron first on a directory header, as on a type header', async () => {
    await openReference()
    await expand('reference')

    const parts = (control: HTMLElement) => Array.from(control.children)
    const directory = parts(header('ccbill'))

    expect(parts(header('reference'))[0]!.classList.contains('lucide-chevron-right')).toBe(true)
    expect(directory[0]!.classList.contains('lucide-chevron-right')).toBe(true)
    expect(directory[1]!.classList.contains('lucide-folder')).toBe(true)
    expect(directory.at(-1)!.getAttribute('data-slot')).toBe('badge')
    expect(directory.filter((part) => part.classList.contains('lucide-chevron-right'))).toHaveLength(1)
  })
})
