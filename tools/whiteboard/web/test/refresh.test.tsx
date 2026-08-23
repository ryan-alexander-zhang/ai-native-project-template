// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import type { AcceptanceRow, Criterion, ItemsView, RequirementItem } from '../../src/requirements.ts'
import { type SessionListing, api } from '../src/api.ts'
import { Board } from '../src/Board.tsx'
import { type EventLink, FIRST_RETRY_MS, MAX_RETRY_MS, connectEvents } from '../src/eventSocket.ts'

// Rendering the whole board, drilling into a sub-canvas and pushing a change
// through it is the heaviest thing this suite does, and it shares the machine
// with every other file; the default five seconds is a measure of the load, not
// of the board.
vi.setConfig({ testTimeout: 30_000 })

/**
 * The docs-change channel under the test's hand: it opens, drops and signals
 * when the test says so, and remembers every socket the board asked for, which
 * is how a reconnection is observed (spec-00001-FR-43).
 */
class ChannelSocket {
  static opened: ChannelSocket[] = []
  static throwOnConstruct = false
  static readonly OPEN = 1
  static get last(): ChannelSocket {
    return ChannelSocket.opened[ChannelSocket.opened.length - 1]!
  }

  readyState = 0
  private readonly listeners: Record<string, Array<(event: { data: string }) => void>> = {}

  constructor(readonly url: string) {
    if (ChannelSocket.throwOnConstruct) throw new Error('the board cannot even dial')
    ChannelSocket.opened.push(this)
  }

  addEventListener(type: string, listener: (event: { data: string }) => void) {
    ;(this.listeners[type] ??= []).push(listener)
  }

  removeEventListener() {}
  send() {}

  close() {
    this.drop()
  }

  /** The server accepted the connection. */
  connect() {
    this.readyState = ChannelSocket.OPEN
    this.emit('open')
  }

  /** One «docs changed» frame, which carries nothing (design-00001 §6). */
  signal() {
    this.emit('message', { data: '' })
  }

  drop() {
    if (this.readyState === 3) return
    this.readyState = 3
    this.emit('close')
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

function row(targetId: string, test = 'canvas.test.tsx › draws the edges'): AcceptanceRow {
  return { recordId: 'record-00001-x', targetId, test, result: 'pass' }
}

function criterion(id: string, rows: AcceptanceRow[] = []): Criterion {
  return { id, text: 'Given a board When it loads Then it works', rows }
}

function item(id: string, overrides: Partial<RequirementItem> = {}): RequirementItem {
  return { id, text: `what ${id} asks of the system`, criteria: [], rows: [], coverage: 'uncovered', ...overrides }
}

const ITEMS: ItemsView = {
  items: [
    item('spec-00001-FR-1', {
      coverage: 'verified',
      criteria: [
        criterion('spec-00001-AC-1.1', [row('spec-00001-AC-1.1')]),
        criterion('spec-00001-AC-1.2', [row('spec-00001-AC-1.2', 'canvas.test.tsx › labels them')]),
      ],
    }),
    item('spec-00001-FR-2', { criteria: [criterion('spec-00001-AC-2.1')] }),
  ],
  diagnostics: [],
}

/** What the server is currently serving; a test moves the disk by moving these. */
let graph: DocGraph
let items: ItemsView

function serve() {
  graph = GRAPH
  items = ITEMS
  // A fresh payload per read, as the wire gives: a board that re-read the very
  // same object would have nothing to re-render.
  vi.spyOn(api, 'graph').mockImplementation(async () => structuredClone(graph))
  vi.spyOn(api, 'items').mockImplementation(async () => structuredClone(items))
  vi.spyOn(api, 'transitions').mockResolvedValue(['archived'])
  vi.spyOn(api, 'nextSteps').mockResolvedValue([])
  vi.spyOn(api, 'sessions').mockResolvedValue([])
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
}

/**
 * A push travels through a socket frame, a re-read and a re-render of the whole
 * canvas; with the suite's files running side by side that chain outlasts the
 * one-second default, and none of these tests is measuring how long it takes
 * («visible within a second» is FR-42's, and is measured on the real thing).
 */
const SETTLED = { timeout: 20_000, interval: 25 }

/** The board, open, with its channel connected — the state every push starts from. */
async function openBoard() {
  const rendered = render(<Board />)
  await waitFor(() => expect(screen.getByTestId('node-spec-00001-x')).toBeTruthy(), SETTLED)
  // One board, one channel: a socket left dialling by an earlier test would be
  // the one every push below went to, and this board would never hear a thing.
  expect(ChannelSocket.opened).toHaveLength(1)
  await act(async () => ChannelSocket.last.connect())
  await settle()
  return rendered
}

/**
 * Let an async chain land. React only takes in state that arrives while an act
 * is open, and the board's answer to a signal is a chain of reads — the graph,
 * then the items the new graph sends it after — each resolving a turn after the
 * one that issued it. So: one act per link, each held open past the microtasks.
 */
async function settle(links = 3) {
  for (let link = 0; link < links; link += 1) {
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
  }
}

/** A change on disk, pushed. Nothing else happens — no click, no keystroke. */
async function push() {
  await act(async () => ChannelSocket.last.signal())
  await settle()
}

/**
 * The channel drops and comes back. The clock is faked before the drop, not
 * after: the delay is scheduled the moment the socket closes, and a timer left
 * running on the real clock fires in whatever test happens to be running then.
 */
async function reconnect(after = FIRST_RETRY_MS) {
  const before = ChannelSocket.opened.length
  vi.useFakeTimers()
  await act(async () => ChannelSocket.last.drop())
  await act(async () => {
    await vi.advanceTimersByTimeAsync(after)
  })
  vi.useRealTimers()
  expect(ChannelSocket.opened).toHaveLength(before + 1)
  await act(async () => ChannelSocket.last.connect())
  await settle()
}

async function openPanel() {
  const rendered = await openBoard()
  fireEvent.click(screen.getByTestId('node-spec-00001-x'))
  await waitFor(() => expect(screen.getByLabelText('Requirements of spec-00001-x')).toBeTruthy(), SETTLED)
  return rendered
}

async function openSubCanvas() {
  const rendered = await openPanel()
  await userEvent.click(screen.getByRole('button', { name: /Expand as sub-canvas/ }))
  await waitFor(() => expect(screen.getByTestId('sub-item-spec-00001-FR-1')).toBeTruthy(), SETTLED)
  return rendered
}

beforeEach(() => {
  ChannelSocket.opened = []
  ChannelSocket.throwOnConstruct = false
  vi.stubGlobal('WebSocket', ChannelSocket)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** The channel itself, away from the board (spec-00001-FR-42, FR-43). */
describe('the docs-change channel', () => {
  const links: EventLink[] = []
  /** Opened links are closed again: one left dialling would outlive its test. */
  const open = (onChange: () => void) => {
    const link = connectEvents(onChange)
    links.push(link)
    return link
  }

  afterEach(() => {
    for (const link of links.splice(0)) link.close()
  })

  it('reports a frame as a change, whatever the frame carries', () => {
    const changes = vi.fn()
    open(changes)

    ChannelSocket.last.signal()
    ChannelSocket.last.signal()

    expect(changes).toHaveBeenCalledTimes(2)
    expect(ChannelSocket.last.url).toMatch(/^ws:\/\/.*\/api\/events$/)
  })

  // spec-00001-AC-43.2 — the connection itself is a change: anything missed
  // while the channel was down is caught by re-reading.
  it('reports a connection as a change', () => {
    const changes = vi.fn()
    open(changes)

    ChannelSocket.last.connect()

    expect(changes).toHaveBeenCalledTimes(1)
  })

  it('dials again on a widening delay, up to the ceiling', () => {
    vi.useFakeTimers()
    try {
      open(vi.fn())
      const delays = [FIRST_RETRY_MS, 2 * FIRST_RETRY_MS, 4 * FIRST_RETRY_MS]

      for (const delay of delays) {
        const before = ChannelSocket.opened.length
        ChannelSocket.last.drop()
        // Not a moment before the delay is up, and not a moment after.
        vi.advanceTimersByTime(delay - 1)
        expect(ChannelSocket.opened).toHaveLength(before)
        vi.advanceTimersByTime(1)
        expect(ChannelSocket.opened).toHaveLength(before + 1)
      }

      // Widening for ever would mean an unattended board never comes back.
      for (let attempt = 0; attempt < 10; attempt += 1) {
        ChannelSocket.last.drop()
        vi.advanceTimersByTime(MAX_RETRY_MS)
      }
      const before = ChannelSocket.opened.length
      ChannelSocket.last.drop()
      vi.advanceTimersByTime(MAX_RETRY_MS)
      expect(ChannelSocket.opened).toHaveLength(before + 1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('starts the delay over once a connection is made', () => {
    vi.useFakeTimers()
    try {
      open(vi.fn())
      ChannelSocket.last.drop()
      vi.advanceTimersByTime(FIRST_RETRY_MS)
      ChannelSocket.last.drop()
      vi.advanceTimersByTime(2 * FIRST_RETRY_MS)

      ChannelSocket.last.connect()
      ChannelSocket.last.drop()

      const before = ChannelSocket.opened.length
      vi.advanceTimersByTime(FIRST_RETRY_MS)
      expect(ChannelSocket.opened).toHaveLength(before + 1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('stops dialling once the board lets go of the channel', () => {
    vi.useFakeTimers()
    try {
      const link = open(vi.fn())
      link.close()

      vi.advanceTimersByTime(10 * MAX_RETRY_MS)

      expect(ChannelSocket.opened).toHaveLength(1)
    } finally {
      vi.useRealTimers()
    }
  })

  // spec-00001-AC-43.3, the channel's half: a socket that cannot even be built
  // is the disconnected case, silent and retried.
  it('says nothing when the socket cannot be built at all', () => {
    vi.useFakeTimers()
    try {
      ChannelSocket.throwOnConstruct = true
      const changes = vi.fn()

      expect(() => open(changes)).not.toThrow()

      ChannelSocket.throwOnConstruct = false
      vi.advanceTimersByTime(FIRST_RETRY_MS)
      expect(ChannelSocket.opened).toHaveLength(1)
      expect(changes).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })
})

/** spec-00001-FR-42 as the user meets it: the board keeps up on its own. */
describe('a change pushed to an open board', () => {
  beforeEach(serve)

  // spec-00001-AC-42.1
  it('brings a new document onto the graph with no user action', async () => {
    await openBoard()
    const added = node({ id: 'plan-00001-new', type: 'plan', title: 'A plan written elsewhere', path: 'plan/a.md' })
    graph = { ...GRAPH, nodes: [...GRAPH.nodes, added] }

    await push()

    await waitFor(() => expect(screen.getByTestId('node-plan-00001-new')).toBeTruthy(), SETTLED)
  })

  // spec-00001-AC-42.2
  it('shows the new status of a document changed elsewhere', async () => {
    graph = { ...GRAPH, nodes: [node({ status: 'draft' }), RECORD] }
    await openBoard()
    expect(within(screen.getByTestId('node-spec-00001-x')).getByText('draft')).toBeTruthy()
    graph = GRAPH

    await push()

    await waitFor(() => expect(within(screen.getByTestId('node-spec-00001-x')).getByText('active')).toBeTruthy(), SETTLED)
  })

  // spec-00001-AC-42.3
  it('takes a deleted document off the graph', async () => {
    await openBoard()
    graph = { ...GRAPH, nodes: [SPEC], edges: [] }

    await push()

    await waitFor(() => expect(screen.queryByTestId('node-record-00001-x')).toBeNull(), SETTLED)
    expect(screen.getByTestId('node-spec-00001-x')).toBeTruthy()
  })

  // spec-00001-AC-42.2 and AC-44.x depend on this half: the items of the
  // document on show are re-read too, not just the graph (design-00001 §6).
  it('re-reads the items of the document on show', async () => {
    await openPanel()
    const reads = vi.mocked(api.items).mock.calls.length
    items = { ...ITEMS, items: [...ITEMS.items, item('spec-00001-FR-3')] }

    await push()

    await waitFor(() => expect(screen.getByTestId('item-spec-00001-FR-3')).toBeTruthy(), SETTLED)
    expect(vi.mocked(api.items).mock.calls.length).toBeGreaterThan(reads)
  })

  // spec-00001-AC-42.6 — a refresh reads the server, never the editor
  it('leaves an unsaved buffer and its cursor alone', async () => {
    vi.spyOn(api, 'doc').mockResolvedValue({ path: 'spec/a.md', content: '# Spec\n\nbody\n', hash: 'hash-1' })
    await openBoard()
    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }))
    await waitFor(() => expect(screen.getByTestId('editor-host').textContent).toContain('body'), SETTLED)
    await userEvent.click(screen.getByTestId('editor-host').querySelector('.cm-content')!)
    await userEvent.keyboard('X')
    const reads = vi.mocked(api.doc).mock.calls.length

    await push()

    await userEvent.keyboard('Y')
    expect(document.querySelector('.cm-content')?.textContent).toContain('XY')
    expect(vi.mocked(api.doc).mock.calls.length).toBe(reads)
  })
})

/** spec-00001-FR-43: a channel that is down is quiet, not broken. */
describe('a board whose channel is down', () => {
  beforeEach(serve)

  // spec-00001-AC-43.1
  it('keeps the whole graph and every control working', async () => {
    await openBoard()

    vi.useFakeTimers()
    await act(async () => ChannelSocket.last.drop())
    vi.useRealTimers()

    expect(screen.getByTestId('node-spec-00001-x')).toBeTruthy()
    expect(screen.getByTestId('node-record-00001-x')).toBeTruthy()
    // Nothing about the drop is put in front of the user (design-00002 §10).
    expect(screen.queryByRole('alert')).toBeNull()
    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    expect(await screen.findByRole('toolbar', { name: /spec-00001-x/ })).toBeTruthy()
  })

  // spec-00001-AC-43.3
  it('draws the graph when the channel was never available at all', async () => {
    ChannelSocket.throwOnConstruct = true
    render(<Board />)

    await waitFor(() => expect(screen.getByTestId('node-spec-00001-x')).toBeTruthy(), SETTLED)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  // spec-00001-AC-43.2 — what changed while nobody was listening
  it('catches up on reconnection', async () => {
    await openBoard()
    graph = { ...GRAPH, nodes: [...GRAPH.nodes, node({ id: 'plan-00001-new', type: 'plan', path: 'plan/a.md' })] }

    await reconnect()

    await waitFor(() => expect(screen.getByTestId('node-plan-00001-new')).toBeTruthy(), SETTLED)
  })

  // spec-00001-AC-43.4 — the response to a reconnection is not a one-off
  it('catches up again on the second reconnection', async () => {
    await openBoard()
    for (const id of ['plan-00001-first', 'plan-00002-second']) {
      graph = { ...graph, nodes: [...graph.nodes, node({ id, type: 'plan', path: `plan/${id}.md` })] }

      await reconnect()

      await waitFor(() => expect(screen.getByTestId(`node-${id}`)).toBeTruthy(), SETTLED)
    }
  })
})

/**
 * spec-00001-AC-12.8 (issue-00013): a session that ends without touching `docs/`
 * has no commit to ride on, so the end itself is signalled — and a refresh has to
 * re-read the session state, or the board keeps showing a session the server has
 * already forgotten.
 */
describe('a session that ends with no docs change', () => {
  const RUNNING: SessionListing = {
    id: 's1',
    kind: 'ask',
    agent: 'claude',
    sourceId: 'spec-00001-x',
    status: 'running',
    startedAt: '2026-01-01T00:00:00.000Z',
  }

  /** The one socket that carries the signal; the terminal opens one of its own. */
  const channel = () => ChannelSocket.opened.find((socket) => socket.url.endsWith('/api/events'))!

  const clarify = () => screen.getByRole<HTMLButtonElement>('button', { name: 'Clarify' })
  const ask = () => screen.getByRole<HTMLButtonElement>('button', { name: 'Ask' })
  const advance = () => screen.getByLabelText<HTMLButtonElement>('Advance to the next step')

  beforeEach(() => {
    serve()
    // The three entries only exist where the payload puts them: clarify needs
    // the type in the clarifiable set, advance a flow step out of it.
    vi.spyOn(api, 'config').mockResolvedValue({
      types: { spec: 'living', rule: 'living', plan: 'work', record: 'work' },
      relations: ['verifies'],
      flow: { spec: [{ next: 'plan', carry: 'implements' }] },
      focus: { spec: 'the boundaries of each FR and the gaps in its acceptance' },
      agents: [{ name: 'claude', command: 'claude', args: [] }],
      entry: [],
      carries: {},
      maxSessions: 3,
      clarifiable: ['spec'],
      auditable: ['spec', 'rule', 'design'],
    })
    vi.spyOn(api, 'nextSteps').mockResolvedValue([{ next: 'plan', carry: 'implements' }])
  })

  it('shows the end state, hands the entries back and takes the stop away', async () => {
    let held = [RUNNING]
    vi.spyOn(api, 'sessions').mockImplementation(async () => held)
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-spec-00001-x')).toBeTruthy(), SETTLED)
    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    await waitFor(() => expect(clarify()).toBeTruthy(), SETTLED)
    const terminal = screen.getByLabelText('Agent session')
    expect(within(terminal).getByText('running')).toBeTruthy()
    expect(clarify().disabled).toBe(true)
    expect(screen.getByRole('button', { name: 'Stop the agent session' })).toBeTruthy()

    // The session ends by itself — `/exit`, nothing written — and the server says so.
    held = [{ ...RUNNING, status: 'exited', exitCode: 0 }]
    await act(async () => channel().signal())
    await settle()

    await waitFor(() => expect(within(terminal).getByText('exited')).toBeTruthy(), SETTLED)
    expect(clarify().disabled).toBe(false)
    expect(ask().disabled).toBe(false)
    expect(advance().disabled).toBe(false)
    expect(screen.queryByRole('button', { name: 'Stop the agent session' })).toBeNull()
  })
})

/** spec-00001-FR-44: a refresh keeps the reader where they were, by id. */
describe('what a refresh keeps', () => {
  beforeEach(serve)

  // spec-00001-AC-44.1
  it('stays in the sub-canvas when the document is still there', async () => {
    await openSubCanvas()

    await push()

    expect(screen.getByTestId('sub-item-spec-00001-FR-1')).toBeTruthy()
    const trail = screen.getByRole('navigation', { name: 'breadcrumb' })
    expect(within(trail).getByText('spec-00001-x')).toBeTruthy()
  })

  // spec-00001-AC-44.2
  it('keeps the detail on the same target when its item is still there', async () => {
    await openSubCanvas()
    fireEvent.click(screen.getByTestId('sub-ac-spec-00001-AC-1.1'))
    await waitFor(() => expect(screen.getByLabelText('Details of spec-00001-AC-1.1')).toBeTruthy(), SETTLED)

    await push()

    expect(screen.getByLabelText('Details of spec-00001-AC-1.1')).toBeTruthy()
  })

  /**
   * spec-00001-AC-44.3 — the board's own act re-reads by the same path, so it
   * keeps exactly what a push keeps (design-00002 §10). The act is an accept
   * whose commit is still in flight when the reader expands the sub-canvas: the
   * toolbar and the panel are on screen together, so this is a sequence a user
   * can actually perform, and its refresh lands with the sub-canvas already up.
   */
  it('stays in the sub-canvas through a refresh the board itself caused', async () => {
    let commit: (result: { committed: boolean; status: string }) => void = () => {}
    vi.spyOn(api, 'accept').mockReturnValue(
      new Promise((resolve) => {
        commit = resolve
      }),
    )
    await openPanel()
    await userEvent.click(screen.getByRole('button', { name: 'Accept' }))
    await userEvent.click(screen.getByRole('button', { name: /Expand as sub-canvas/ }))
    await waitFor(() => expect(screen.getByTestId('sub-item-spec-00001-FR-1')).toBeTruthy(), SETTLED)
    const reads = vi.mocked(api.graph).mock.calls.length

    await act(async () => commit({ committed: true, status: 'active' }))
    await settle()

    // The accept's own re-read, with no signal anywhere near it…
    expect(vi.mocked(api.graph).mock.calls.length).toBe(reads + 1)
    // …and the reader is where they were.
    expect(screen.getByTestId('sub-item-spec-00001-FR-1')).toBeTruthy()
    const trail = screen.getByRole('navigation', { name: 'breadcrumb' })
    expect(within(trail).getByText('spec-00001-x')).toBeTruthy()
  })

  // spec-00001-AC-44.4
  it('comes back up to the board when the drilled document is deleted', async () => {
    await openSubCanvas()
    graph = { ...GRAPH, nodes: [RECORD], edges: [] }
    items = { items: [], diagnostics: [] }

    await push()

    await waitFor(() => expect(screen.queryByTestId('sub-item-spec-00001-FR-1')).toBeNull(), SETTLED)
    expect(screen.queryByRole('navigation', { name: 'breadcrumb' })).toBeNull()
    expect(screen.getByTestId('node-record-00001-x')).toBeTruthy()
  })

  // spec-00001-AC-44.5 — only the level that lost its target closes
  it('closes the detail when its row is deleted, and stays in the sub-canvas', async () => {
    await openSubCanvas()
    fireEvent.click(screen.getByTestId('sub-ac-spec-00001-AC-1.2'))
    await waitFor(() => expect(screen.getByLabelText('Details of spec-00001-AC-1.2')).toBeTruthy(), SETTLED)
    items = {
      ...ITEMS,
      items: [
        item('spec-00001-FR-1', {
          coverage: 'verified',
          criteria: [criterion('spec-00001-AC-1.1', [row('spec-00001-AC-1.1')])],
        }),
        ITEMS.items[1]!,
      ],
    }

    await push()

    await waitFor(() => expect(screen.queryByLabelText('Details of spec-00001-AC-1.2')).toBeNull(), SETTLED)
    expect(screen.getByTestId('sub-item-spec-00001-FR-1')).toBeTruthy()
  })

  // spec-00001-AC-44.6
  it('drops the selection and its toolbar when the selected document is deleted', async () => {
    await openBoard()
    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    await waitFor(() => expect(screen.getByRole('toolbar', { name: /spec-00001-x/ })).toBeTruthy(), SETTLED)
    graph = { ...GRAPH, nodes: [RECORD], edges: [] }

    await push()

    await waitFor(() => expect(screen.queryByRole('toolbar')).toBeNull(), SETTLED)
    expect(screen.queryByTestId('node-spec-00001-x')).toBeNull()
    expect(screen.getByTestId('node-record-00001-x')).toBeTruthy()
  })

  /**
   * spec-00002-AC-8.9: presentation state is held by node key, and the key of a
   * document that collides on its id is its file path. Two nodes carrying the
   * same `duplicateOf` are told apart by nothing else, so the selection has to
   * come back on the same file.
   */
  it('keeps the selection on the same file when the node is keyed by its path', async () => {
    const first = node({ id: 'spec/first.md', path: 'spec/first.md', duplicateOf: 'spec-00002-clash', ok: false, title: 'The first' })
    const second = node({ id: 'spec/second.md', path: 'spec/second.md', duplicateOf: 'spec-00002-clash', ok: false, title: 'The second' })
    graph = { nodes: [first, second], edges: [], issues: [], diagnostics: [], idOwners: {} }
    render(<Board />)
    await waitFor(() => expect(screen.getByTestId('node-spec/second.md')).toBeTruthy(), SETTLED)
    await act(async () => ChannelSocket.last.connect())
    await settle()
    fireEvent.click(screen.getByTestId('node-spec/second.md'))
    await waitFor(() => expect(screen.getByRole('toolbar', { name: /spec\/second\.md/ })).toBeTruthy(), SETTLED)

    await push()

    expect(screen.getByRole('toolbar', { name: /spec\/second\.md/ })).toBeTruthy()
    expect(screen.queryByRole('toolbar', { name: /spec\/first\.md/ })).toBeNull()
  })

  // spec-00001-AC-44.7
  it('collapses an expanded row whose item is deleted, and keeps the rest', async () => {
    await openPanel()
    await userEvent.click(screen.getByTestId('item-spec-00001-FR-1'))
    expect(screen.getByLabelText('Expanded spec-00001-FR-1')).toBeTruthy()
    items = { ...ITEMS, items: [ITEMS.items[1]!] }

    await push()

    await waitFor(() => expect(screen.queryByTestId('item-spec-00001-FR-1')).toBeNull(), SETTLED)
    expect(screen.queryByLabelText('Expanded spec-00001-FR-1')).toBeNull()
    expect(screen.getByTestId('item-spec-00001-FR-2')).toBeTruthy()
  })
})
