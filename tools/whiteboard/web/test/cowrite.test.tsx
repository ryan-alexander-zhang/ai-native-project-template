// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import type { DocContent, DocGraph, DocNode } from '../../src/docRepository.ts'
import { TooltipProvider } from '@/components/ui/tooltip'
import { Board } from '../src/Board.tsx'
import { CO_WRITE_LOCK, DISK_MOVED } from '../src/Editor.tsx'
import { CO_WRITING, DOC_BUSY, Toolbar, type ToolbarProps } from '../src/Toolbar.tsx'
import { ApiError, type ConfigPayload, type SessionListing, api } from '../src/api.ts'
import { readMaterials } from '../src/cowriteMaterials.ts'

// Rendering the whole board, launching a session and pushing a refresh through
// it is heavier than the default five seconds allows on a loaded machine; none
// of these cases measures how long anything takes.
vi.setConfig({ testTimeout: 30_000 })

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

/** A document whose front matter is broken: its editor is reachable, its entries are not. */
const BROKEN = node({
  id: 'docs/spec/bad.md',
  path: 'spec/bad.md',
  type: undefined,
  ok: false,
  problems: ['front matter is missing'],
})
const SPEC = node({ id: 'spec-00001-x', type: 'spec', status: 'active', title: 'Spec', path: 'spec/a.md' })

function board(): DocGraph {
  return { nodes: [node(), SPEC, BROKEN], edges: [], issues: [], idOwners: {}, diagnostics: [] }
}

function listing(overrides: Partial<SessionListing> = {}): SessionListing {
  return {
    id: 's1',
    kind: 'cowrite',
    agent: 'claude',
    sourceId: 'prd-00001-x',
    status: 'running',
    startedAt: '2026-02-01T09:00:00.000Z',
    ...overrides,
  }
}

function config(agents = ['claude']): ConfigPayload {
  return {
    types: { idea: 'living', prd: 'living', spec: 'living' },
    relations: ['parent'],
    flow: {},
    focus: {},
    // Every agent here also declares a headless form, so the question entry is
    // drawn: spec-00005 must be seen to be untouched by the lock (AC-4.6).
    agents: agents.map((name) => ({ name, headless: true, source: 'project' as const })),
    entry: ['idea'],
    carries: {},
    maxSessions: 3,
    clarifiable: [],
    auditable: [],
  }
}

/** The sockets the board dials: the docs-change channel, and one per terminal. */
class Socket {
  static channel?: Socket
  static readonly OPEN = 1
  readyState = 1
  private listeners: Record<string, Array<(event: { data: string }) => void>> = {}

  constructor(readonly url: string) {
    if (url.includes('/api/events')) Socket.channel = this
  }

  addEventListener(type: string, listener: (event: { data: string }) => void) {
    ;(this.listeners[type] ??= []).push(listener)
  }

  removeEventListener() {}
  send() {}
  close() {}

  signal() {
    for (const listener of this.listeners.message ?? []) listener({ data: '' })
  }
}

/** What the server is currently serving; a case moves the disk by moving these. */
let served: SessionListing[] = []
let graph: DocGraph
let onDisk: DocContent

function serve(sessions: SessionListing[] = [], agents = ['claude']) {
  served = sessions
  graph = board()
  onDisk = { path: 'prd/a.md', content: '# PRD\n\nfirst body\n', hash: 'hash-1' }
  vi.spyOn(api, 'graph').mockImplementation(async () => structuredClone(graph))
  vi.spyOn(api, 'sessions').mockImplementation(async () => structuredClone(served))
  vi.spyOn(api, 'doc').mockImplementation(async () => structuredClone(onDisk))
  vi.spyOn(api, 'asks').mockResolvedValue([])
  vi.spyOn(api, 'transitions').mockResolvedValue(['active'])
  vi.spyOn(api, 'nextSteps').mockResolvedValue([])
  vi.spyOn(api, 'items').mockResolvedValue({ items: [], diagnostics: [] })
  vi.spyOn(api, 'config').mockResolvedValue(config(agents))
}

/** Let the read chain a signal starts land; React only takes in what arrives inside an act. */
async function settle(links = 4) {
  for (let link = 0; link < links; link += 1) {
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
  }
}

/** A change pushed from disk, and nothing else: no click, no keystroke. */
async function push() {
  await act(async () => Socket.channel!.signal())
  await settle()
}

const SETTLED = { timeout: 20_000, interval: 25 }

async function openBoard() {
  const rendered = render(<Board />)
  await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy(), SETTLED)
  await settle(1)
  return rendered
}

/** Select a document and open its editor on the document's own text. */
async function openEditor(id = 'prd-00001-x') {
  fireEvent.click(screen.getByTestId(`node-${id}`))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy(), SETTLED)
  await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
  await waitFor(() => expect(screen.getByTestId('editor-host').textContent).toContain('first body'), SETTLED)
}

/** Open the launch input of the selected document's cowrite entry. */
async function openLaunch(id = 'prd-00001-x') {
  fireEvent.click(screen.getByTestId(`node-${id}`))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Co-write' })).toBeTruthy(), SETTLED)
  await userEvent.click(screen.getByRole('button', { name: 'Co-write' }))
  return await screen.findByLabelText('Pasted material')
}

/** Type into the live buffer, which is what makes it dirty. */
async function typeInBuffer(text: string) {
  await userEvent.click(screen.getByTestId('editor-host').querySelector('.cm-content')!)
  await userEvent.keyboard(text)
}

const buffer = () => document.querySelector('.cm-content')?.textContent ?? ''
const content = () => screen.getByTestId('editor-host').querySelector<HTMLElement>('.cm-content')!

const TOOLBAR_NODE = node()

/** The toolbar on its own, for the cases that are about one control's state. */
function renderToolbar(overrides: Partial<ToolbarProps> = {}) {
  const props: ToolbarProps = {
    node: TOOLBAR_NODE,
    transitions: ['active', 'archived'],
    nextSteps: [],
    relations: [],
    clarifiable: false,
    auditable: false,
    docBusy: false,
    capReached: false,
    cowriting: false,
    agents: ['claude'],
    askAgents: [],
    onPickAgent: vi.fn(),
    onPickRelation: vi.fn(),
    onEdit: vi.fn(),
    onStatus: vi.fn(),
    onAccept: vi.fn(),
    onClarify: vi.fn(),
    onAsk: vi.fn(async () => true),
    knownDoc: () => true,
    onCowrite: vi.fn(async () => true),
    onAudit: vi.fn(),
    onAdvance: vi.fn(),
    ...overrides,
  }
  render(
    <TooltipProvider>
      <Toolbar {...props} />
    </TooltipProvider>,
  )
  return props
}

beforeEach(() => {
  Socket.channel = undefined
  vi.stubGlobal('WebSocket', Socket)
  vi.spyOn(toast, 'error').mockImplementation(() => 'id')
  vi.spyOn(toast, 'success').mockImplementation(() => 'id')
  vi.spyOn(toast, 'message').mockImplementation(() => 'id')
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('the cowrite entry', () => {
  // spec-00006-AC-1.2 — an anomalous document's floating toolbar carries the two
  // whitelisted items and no starting point at all (spec-00001-AC-2.4)
  it('is not offered on an anomalous node', async () => {
    serve()
    await openBoard()

    fireEvent.click(screen.getByTestId('node-docs/spec/bad.md'))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy(), SETTLED)
    expect(screen.getByRole('button', { name: 'Relations' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Co-write' })).toBeNull()
  })

  /**
   * spec-00006-AC-9.1 — the entry is not conditioned on the status: an `active`
   * document still shows it, the refusal comes at the submit, and the materials
   * that were gathered stay where they were typed.
   */
  it('keeps the entry and the materials when the launch is refused', async () => {
    serve()
    const cowrite = vi
      .spyOn(api, 'cowrite')
      .mockRejectedValue(new ApiError(422, 'cowrite applies to a draft document; spec-00001-x is active'))
    await openBoard()
    await openLaunch('spec-00001-x')
    await userEvent.type(screen.getByLabelText('Pasted material'), 'rewrite the context section')

    await userEvent.click(screen.getByRole('button', { name: 'Start co-writing' }))

    expect(cowrite).toHaveBeenCalledTimes(1)
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('cowrite applies to a draft document; spec-00001-x is active'),
    )
    // Still open, still holding the materials, and the entry itself never went.
    expect(screen.getByLabelText<HTMLTextAreaElement>('Pasted material').value).toBe('rewrite the context section')
    expect(screen.getByRole('button', { name: 'Co-write' })).toBeTruthy()
  })

  // spec-00006-AC-3.3 — materials are an offer, not the request: an empty launch
  // input is a launch, which is where it parts from the ask input
  it('starts with no materials at all', async () => {
    serve()
    const cowrite = vi.spyOn(api, 'cowrite').mockResolvedValue({ sessionId: 's1', docId: 'prd-00001-x' })
    await openBoard()
    await openLaunch()

    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Start co-writing' }).disabled).toBe(false)
    await userEvent.click(screen.getByRole('button', { name: 'Start co-writing' }))

    expect(cowrite).toHaveBeenCalledWith({ docId: 'prd-00001-x', materials: undefined, agent: undefined })
  })

  // spec-00006-AC-3.1 — the pasted text and a URL reach the payload as what they are
  it('sends the pasted text and a URL', async () => {
    serve()
    const cowrite = vi.spyOn(api, 'cowrite').mockResolvedValue({ sessionId: 's1', docId: 'prd-00001-x' })
    await openBoard()
    await openLaunch()
    await userEvent.type(screen.getByLabelText('Pasted material'), 'the note I took in the meeting')
    await userEvent.type(screen.getByLabelText('Material references'), 'https://example.com/case-study')

    await userEvent.click(screen.getByRole('button', { name: 'Start co-writing' }))

    expect(cowrite).toHaveBeenCalledWith({
      docId: 'prd-00001-x',
      agent: undefined,
      materials: { text: 'the note I took in the meeting', urls: ['https://example.com/case-study'] },
    })
  })

  // spec-00006-AC-3.2 — an id of this repo and a path outside it, told apart by
  // the written discriminators and never by asking the user which is which
  it('sends a document id and an absolute path apart', async () => {
    serve()
    const cowrite = vi.spyOn(api, 'cowrite').mockResolvedValue({ sessionId: 's1', docId: 'prd-00001-x' })
    await openBoard()
    await openLaunch()
    await userEvent.type(screen.getByLabelText('Material references'), 'spec-00001-x\n/Users/me/notes.md')

    await userEvent.click(screen.getByRole('button', { name: 'Start co-writing' }))

    expect(cowrite).toHaveBeenCalledWith({
      docId: 'prd-00001-x',
      agent: undefined,
      materials: { docIds: ['spec-00001-x'], paths: ['/Users/me/notes.md'] },
    })
  })

  /**
   * spec-00006-FR-3 as design-00002 §15 rules it: a line that is none of the
   * three, and an id the board does not have, block the launch and are named.
   * Neither is dropped and neither is folded into the pasted text, where a
   * mistyped id would become a line of prose nobody reads.
   */
  it('blocks the launch on a line it cannot use and names it', async () => {
    serve()
    const cowrite = vi.spyOn(api, 'cowrite').mockResolvedValue({ sessionId: 's1', docId: 'prd-00001-x' })
    await openBoard()
    await openLaunch()

    await userEvent.type(screen.getByLabelText('Material references'), 'the notes I made\nspec-00099-nowhere')

    const named = within(screen.getByRole('list', { name: 'Unusable materials' })).getAllByRole('listitem')
    expect(named.map((one) => one.textContent)).toEqual([
      'the notes I made — not a document id, an absolute path or a URL',
      'spec-00099-nowhere — no document with this id is on the board',
    ])
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Start co-writing' }).disabled).toBe(true)
    await userEvent.click(screen.getByRole('button', { name: 'Start co-writing' }))
    expect(cowrite).not.toHaveBeenCalled()
  })

  // The same line typed twice is two entries: keyed by its text alone, one of the
  // two would be dropped and the count under the box would disagree with the box
  it('names a line it cannot use once for every time it was typed', async () => {
    serve()
    await openBoard()
    await openLaunch()

    await userEvent.type(screen.getByLabelText('Material references'), 'the notes I made\nthe notes I made')

    const named = within(screen.getByRole('list', { name: 'Unusable materials' })).getAllByRole('listitem')
    expect(named.map((one) => one.textContent)).toEqual([
      'the notes I made — not a document id, an absolute path or a URL',
      'the notes I made — not a document id, an absolute path or a URL',
    ])
  })

  // spec-00001-FR-55 in its cowrite reading (spec-00006-AC-1.3's front end): the
  // agent is a choice only where the config declares more than one
  it('sends the agent the user picked', async () => {
    serve([], ['claude', 'codex'])
    const cowrite = vi.spyOn(api, 'cowrite').mockResolvedValue({ sessionId: 's1', docId: 'prd-00001-x' })
    await openBoard()
    await openLaunch()

    await userEvent.click(screen.getByLabelText('Co-write agent'))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'codex' }))
    await userEvent.click(screen.getByRole('button', { name: 'Start co-writing' }))

    expect(cowrite).toHaveBeenCalledWith({ docId: 'prd-00001-x', materials: undefined, agent: 'codex' })
  })

  /**
   * The draft belongs to the document it was gathered for (design-00002 §15's
   * inherited discipline): the entry is one component in one place, so materials
   * left on one node would otherwise be submitted against the next one selected.
   */
  it('keeps no materials from one document to the next', async () => {
    serve()
    await openBoard()
    await openLaunch()
    await userEvent.type(screen.getByLabelText('Pasted material'), 'the note I took in the meeting')

    await openLaunch('spec-00001-x')

    expect(screen.getByLabelText<HTMLTextAreaElement>('Pasted material').value).toBe('')
  })

  // spec-00003-AC-2.4 at this entry: the document already has a terminal-form
  // session, so it starts nothing further and says which of the two reasons it is
  it('is locked while this document has a session and says why', async () => {
    renderToolbar({ docBusy: true })
    const entry = screen.getByRole<HTMLButtonElement>('button', { name: 'Co-write' })

    expect(entry.disabled).toBe(true)
    await userEvent.hover(entry.parentElement!)

    const tooltip = await screen.findByRole('tooltip')
    expect(tooltip.textContent).toContain(DOC_BUSY)
    expect(tooltip.textContent).not.toContain(CO_WRITING)
  })
})

describe('the cowrite workspace', () => {
  /**
   * spec-00006-AC-4.1 — the editor and the terminal are on screen together, and
   * the view state is the document's own text whatever this document was last
   * left on (design-00002 §15's override of the per-document retained view).
   */
  it('opens the target on its Source view beside the terminal', async () => {
    serve()
    vi.spyOn(api, 'cowrite').mockResolvedValue({ sessionId: 's1', docId: 'prd-00001-x' })
    await openBoard()
    // The document was last left on its ask list, which is what the launch overrides.
    await openEditor()
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Questions' }))
    await settle(1)
    expect(screen.getByRole<HTMLElement>('tab', { name: 'Questions' }).getAttribute('data-state')).toBe('active')
    served = [listing()]

    await userEvent.click(screen.getByRole('button', { name: 'Co-write' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Start co-writing' }))
    await settle()

    await waitFor(() => expect(screen.getByLabelText('Editing prd-00001-x')).toBeTruthy(), SETTLED)
    expect(screen.getByRole<HTMLElement>('tab', { name: 'Source' }).getAttribute('data-state')).toBe('active')
    // The started session is the one on show, terminal and all (design-00002 §15).
    expect(screen.getByLabelText('Agent session').textContent).toContain('prd-00001-x')
  })

  /**
   * spec-00006-AC-4.2 — the buffer holds nothing unsaved, so the agent's write
   * arrives in it: spec-00001-FR-42's cowrite exception, and the only one.
   */
  it('reloads a clean buffer from the disk', async () => {
    serve([listing()])
    await openBoard()
    await openEditor()

    onDisk = { path: 'prd/a.md', content: '# PRD\n\nwhat the agent wrote\n', hash: 'hash-2' }
    await push()

    await waitFor(
      () => expect(screen.getByTestId('editor-host').textContent).toContain('what the agent wrote'),
      SETTLED,
    )
    expect(screen.getByTestId('editor-host').textContent).not.toContain('first body')
    expect(screen.queryByText(DISK_MOVED)).toBeNull()
  })

  // spec-00006-AC-4.3 — the agent has the pen: a running session that is not
  // waiting on the user locks the Source view's editing and its save
  it('locks the buffer while the session is not awaiting input', async () => {
    serve([listing()])
    await openBoard()
    await openEditor()

    expect(content().getAttribute('contenteditable')).toBe('false')
    await typeInBuffer('X')
    expect(buffer()).not.toContain('X')
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Save' }).disabled).toBe(true)
    expect(screen.getByText(CO_WRITE_LOCK)).toBeTruthy()
  })

  // spec-00006-AC-4.4 — the session ended, so the editor is an editor again
  it('gives the buffer back when the session ends', async () => {
    serve([listing()])
    await openBoard()
    await openEditor()
    expect(content().getAttribute('contenteditable')).toBe('false')

    served = [listing({ status: 'exited', exitCode: 0, endedAt: '2026-02-01T10:00:00.000Z' })]
    await push()

    await waitFor(() => expect(content().getAttribute('contenteditable')).toBe('true'), SETTLED)
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Save' }).disabled).toBe(false)
    expect(screen.queryByText(CO_WRITE_LOCK)).toBeNull()
    await typeInBuffer('X')
    expect(buffer()).toContain('X')
  })

  /**
   * spec-00006-AC-4.5 — the buffer is dirty, so it is kept and the disk change is
   * told rather than applied: FR-42 holds untouched, and the save that follows
   * meets the conflict check, which here is the protection.
   */
  it('keeps a dirty buffer and says the disk moved', async () => {
    serve([listing({ awaiting: true })])
    await openBoard()
    await openEditor()
    await typeInBuffer('X')

    onDisk = { path: 'prd/a.md', content: '# PRD\n\nwhat the agent wrote\n', hash: 'hash-2' }
    await push()

    await waitFor(() => expect(screen.getByText(DISK_MOVED)).toBeTruthy(), SETTLED)
    expect(buffer()).toContain('X')
    expect(buffer()).not.toContain('what the agent wrote')
  })

  // spec-00006-AC-4.6 — the lock is the Source view's editing and saving, and
  // nothing else: the other view states stay switchable and readable
  it('leaves the preview switchable while the buffer is locked', async () => {
    serve([listing()])
    await openBoard()
    await openEditor()

    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Preview' }))
    await settle(1)

    expect(screen.getByRole<HTMLElement>('tab', { name: 'Preview' }).getAttribute('data-state')).toBe('active')
    await waitFor(() => expect(screen.getByRole('heading', { name: 'PRD' })).toBeTruthy(), SETTLED)
    // And the question entry with it: asking holds no document, so a cowrite
    // cannot lock it (spec-00005-FR-6 applies untouched).
    expect(within(screen.getByLabelText('Editing prd-00001-x')).getByRole<HTMLButtonElement>('button', { name: 'Ask' }).disabled).toBe(false)
  })

  /**
   * spec-00006-AC-4.7 — the lock arrives on a buffer with unsaved edits in it,
   * and takes none of them: the turn changing hands is not a discard.
   */
  it('never clears a dirty buffer when the lock arrives', async () => {
    serve([listing({ awaiting: true })])
    await openBoard()
    await openEditor()
    await typeInBuffer('X')
    expect(content().getAttribute('contenteditable')).toBe('true')

    served = [listing({ awaiting: false })]
    await push()

    await waitFor(() => expect(content().getAttribute('contenteditable')).toBe('false'), SETTLED)
    expect(buffer()).toContain('X')
    // And the edits are still there once the turn comes back.
    served = [listing({ awaiting: true })]
    await push()
    await waitFor(() => expect(content().getAttribute('contenteditable')).toBe('true'), SETTLED)
    expect(buffer()).toContain('X')
  })

  /**
   * spec-00006-AC-5.4 — the agent has written and the reload has not arrived yet
   * (the debounce window): the save is refused by the existing conflict path
   * rather than overwriting what is on disk.
   */
  it('refuses a save in the window before the reload, rather than overwriting', async () => {
    serve([listing({ awaiting: true })])
    const save = vi
      .spyOn(api, 'save')
      .mockRejectedValue(new ApiError(409, 'prd-00001-x changed on disk since it was opened'))
    await openBoard()
    await openEditor()
    await typeInBuffer('X')

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(save).toHaveBeenCalledWith('prd-00001-x', expect.stringContaining('X'), 'hash-1')
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('prd-00001-x changed on disk since it was opened', {
        description: 'reopen it to pick up the change',
      }),
    )
    expect(toast.success).not.toHaveBeenCalled()
  })

  /**
   * spec-00006-AC-10.3's front-end half: a `doc-busy` 409 is the co-write lock, and
   * reopening the document picks nothing up — the refusal is about the session, not
   * about the file having moved. The two 409s a save can meet get the two ways out
   * they actually have.
   */
  it('says the lock rather than «reopen it» when the save meets the co-write lock', async () => {
    serve([listing({ awaiting: true })])
    vi.spyOn(api, 'save').mockRejectedValue(
      new ApiError(409, 'prd-00001-x has a running cowrite session', undefined, 'doc-busy'),
    )
    await openBoard()
    await openEditor()
    await typeInBuffer('X')

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('prd-00001-x has a running cowrite session', {
        description: CO_WRITE_LOCK,
      }),
    )
  })

  /**
   * The hash a save was made against is the hash of nothing once it lands: a
   * second save carrying it would meet the conflict check as though somebody else
   * had written the file (spec-00001-FR-5). The buffer's base version is read back
   * from disk, and the disk-moved notice is settled by the same read (AC-4.5).
   */
  it('saves twice in a row, the second one against the hash the first left', async () => {
    serve([listing({ awaiting: true })])
    const save = vi.spyOn(api, 'save').mockImplementation(async () => {
      onDisk = { path: 'prd/a.md', content: `${onDisk.content}X`, hash: 'hash-2' }
      return { committed: true }
    })
    await openBoard()
    await openEditor()
    await typeInBuffer('X')

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await settle()
    await typeInBuffer('Y')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await settle()

    expect(save.mock.calls.map((call) => call[2])).toEqual(['hash-1', 'hash-2'])
  })

  /**
   * The notice is about **this** document's disk moving under **this** buffer
   * (AC-4.5): left standing it would follow the reader onto the next document they
   * open and say something untrue of it.
   */
  it('clears the disk-moved notice when another document is opened', async () => {
    serve([listing({ awaiting: true })])
    await openBoard()
    await openEditor()
    await typeInBuffer('X')
    onDisk = { path: 'prd/a.md', content: '# PRD\n\nwhat the agent wrote\n', hash: 'hash-2' }
    await push()
    await waitFor(() => expect(screen.getByText(DISK_MOVED)).toBeTruthy(), SETTLED)

    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy(), SETTLED)
    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))

    await waitFor(() => expect(screen.getByLabelText('Editing spec-00001-x')).toBeTruthy(), SETTLED)
    expect(screen.queryByText(DISK_MOVED)).toBeNull()
  })

  /**
   * design-00002 §10's close-nearest, one level added: the target has left the
   * board, so its editor goes — and the session and its terminal, where the stop
   * lives, stay exactly where they were.
   */
  it('closes only the editor when the target leaves the board', async () => {
    serve([listing()])
    await openBoard()
    await openEditor()
    graph = { ...graph, nodes: graph.nodes.filter((one) => one.id !== 'prd-00001-x') }

    await push()

    await waitFor(() => expect(screen.queryByLabelText('Editing prd-00001-x')).toBeNull(), SETTLED)
    expect(screen.getByLabelText('Agent session')).toBeTruthy()
  })
})

/**
 * design-00002 §15's icon language: `NotebookPen` carries the entry and the
 * session panel row, and the node marker keeps the terminal-form icons it always
 * had — a cowrite is a fourth terminal form, not a second kind of marker.
 */
describe('the node marker of a cowrite', () => {
  it('is the terminal-form marker, running and awaiting alike', async () => {
    serve([listing()])
    await openBoard()

    expect(screen.getByLabelText('Running session of prd-00001-x')).toBeTruthy()

    served = [listing({ awaiting: true })]
    await push()

    await waitFor(() => expect(screen.getByLabelText('Awaiting input session of prd-00001-x')).toBeTruthy(), SETTLED)
  })
})

describe('the status lock', () => {
  // spec-00006-AC-10.1 — the two controls a running cowrite locks, with the third
  // reason, which is not either of the two the starting points give
  it('disables the status change and the review, and says why', async () => {
    renderToolbar({ cowriting: true })
    const status = screen.getByLabelText<HTMLButtonElement>('Change status')
    const accept = screen.getByRole<HTMLButtonElement>('button', { name: 'Accept' })

    expect(status.disabled).toBe(true)
    expect(accept.disabled).toBe(true)
    await userEvent.hover(accept.parentElement!)

    const tooltip = await screen.findByRole('tooltip')
    expect(tooltip.textContent).toContain(CO_WRITING)
    expect(tooltip.textContent).not.toContain(DOC_BUSY)
  })

  // spec-00006-AC-10.2 — the session is over, so the review gate is what decides
  // again and the board asks it
  it('hands both back once no cowrite session is running', async () => {
    const props = renderToolbar({ cowriting: false })

    await userEvent.click(screen.getByRole('button', { name: 'Accept' }))

    expect(props.onAccept).toHaveBeenCalledTimes(1)
    expect(screen.getByLabelText<HTMLButtonElement>('Change status').disabled).toBe(false)
  })

  // spec-00006-AC-10.1 through the board, which is where the reading comes from:
  // a running cowrite session on this document and no other
  it('reads the lock off the running session of this document', async () => {
    serve([listing()])
    await openBoard()

    fireEvent.click(screen.getByTestId('node-prd-00001-x'))
    await waitFor(() => expect(screen.getByLabelText('Change status')).toBeTruthy(), SETTLED)
    expect(screen.getByLabelText<HTMLButtonElement>('Change status').disabled).toBe(true)

    // Another document's cowrite locks nothing here (spec-00001-AC-12.8).
    fireEvent.click(screen.getByTestId('node-spec-00001-x'))
    await waitFor(
      () => expect(screen.getByRole('toolbar', { name: 'Actions for spec-00001-x' })).toBeTruthy(),
      SETTLED,
    )
    expect(screen.getByLabelText<HTMLButtonElement>('Change status').disabled).toBe(false)
  })
})

describe('the create dialog', () => {
  async function openDialog() {
    await userEvent.click(screen.getByRole('button', { name: 'New' }))
    return await screen.findByRole('dialog')
  }

  async function pickCowrite() {
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Co-write' }))
    await settle(1)
  }

  /**
   * spec-00006-AC-2.1 — confirming in the cowrite mode is the whole act: the
   * server files the document and starts the session, and the workspace opens on
   * the document it names, since only it knows the number.
   */
  it('files the document and opens its workspace', async () => {
    serve()
    const cowrite = vi.spyOn(api, 'cowrite').mockImplementation(async () => {
      graph = { ...graph, nodes: [...graph.nodes, node({ id: 'idea-00002-notes', type: 'idea', path: 'idea/notes.md' })] }
      served = [listing({ id: 's2', sourceId: 'idea-00002-notes' })]
      return { sessionId: 's2', docId: 'idea-00002-notes' }
    })
    const prefill = vi.spyOn(api, 'createPrefill')
    await openBoard()
    await openDialog()
    await pickCowrite()
    await userEvent.type(screen.getByLabelText('Slug'), 'notes')
    await userEvent.type(screen.getByLabelText('Pasted material'), 'the three cases we discussed')

    await userEvent.click(screen.getByRole('button', { name: 'Create' }))
    await settle()

    expect(cowrite).toHaveBeenCalledWith({
      create: { type: 'idea', slug: 'notes' },
      agent: undefined,
      materials: { text: 'the three cases we discussed' },
    })
    // Nothing went down the blank mode's two-step path.
    expect(prefill).not.toHaveBeenCalled()
    await waitFor(() => expect(screen.getByLabelText('Editing idea-00002-notes')).toBeTruthy(), SETTLED)
    expect(screen.getByLabelText('Agent session').textContent).toContain('idea-00002-notes')
  })

  // spec-00006-AC-2.2, the interface's half: a slug that is not a slug is refused
  // where it is typed, in the cowrite mode as in the blank one
  it('refuses an ill-formed slug in the cowrite mode too', async () => {
    serve()
    const cowrite = vi.spyOn(api, 'cowrite')
    await openBoard()
    await openDialog()
    await pickCowrite()

    await userEvent.type(screen.getByLabelText('Slug'), 'Not A Slug')

    expect(await screen.findByText('a slug is lowercase words joined by hyphens')).toBeTruthy()
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Create' }).disabled).toBe(true)
    expect(cowrite).not.toHaveBeenCalled()
  })

  // spec-00006-AC-2.5 — the blank mode is what it always was: the template opens
  // in the buffer, the save creates the file, and no session starts
  it('leaves the blank mode exactly as it was', async () => {
    serve()
    const cowrite = vi.spyOn(api, 'cowrite')
    const prefill = vi
      .spyOn(api, 'createPrefill')
      .mockResolvedValue({ idPrefix: 'idea-00002-', template: '---\nid: x\ntype: idea\nstatus: draft\n---\n\nbody\n' })
    await openBoard()
    await openDialog()

    await userEvent.type(screen.getByLabelText('Slug'), 'notes')
    await userEvent.click(screen.getByRole('button', { name: 'Create' }))

    expect(prefill).toHaveBeenCalledWith('idea')
    expect(cowrite).not.toHaveBeenCalled()
    await waitFor(
      () => expect(screen.getByTestId('editor-host').textContent).toContain('id: idea-00002-notes'),
      SETTLED,
    )
    expect(screen.queryByLabelText('Agent session')).toBeNull()
  })

  // design-00002 §15 — a refused launch leaves the dialog standing with
  // everything that was typed in it (the create form's reading of AC-9.1)
  it('keeps the dialog and its inputs when the launch is refused', async () => {
    serve()
    vi.spyOn(api, 'cowrite').mockRejectedValue(new ApiError(409, 'the session limit is reached'))
    await openBoard()
    await openDialog()
    await pickCowrite()
    await userEvent.type(screen.getByLabelText('Slug'), 'notes')
    await userEvent.type(screen.getByLabelText('Pasted material'), 'the three cases we discussed')

    await userEvent.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('the session limit is reached'))
    expect(screen.getByRole('dialog')).toBeTruthy()
    expect(screen.getByLabelText<HTMLInputElement>('Slug').value).toBe('notes')
    expect(screen.getByLabelText<HTMLTextAreaElement>('Pasted material').value).toBe(
      'the three cases we discussed',
    )
  })

  /**
   * design-00001 §11.2 — the filing's commit failed with the document on disk:
   * the session goes ahead and the failure is a notice, not a refusal
   * (spec-00001-FR-20).
   */
  it('opens the workspace and says so when the filing commit failed', async () => {
    serve()
    vi.spyOn(api, 'cowrite').mockImplementation(async () => {
      graph = { ...graph, nodes: [...graph.nodes, node({ id: 'idea-00002-notes', type: 'idea', path: 'idea/notes.md' })] }
      served = [listing({ id: 's2', sourceId: 'idea-00002-notes' })]
      return { sessionId: 's2', docId: 'idea-00002-notes', error: 'nothing to commit' }
    })
    await openBoard()
    await openDialog()
    await pickCowrite()
    await userEvent.type(screen.getByLabelText('Slug'), 'notes')

    await userEvent.click(screen.getByRole('button', { name: 'Create' }))
    await settle()

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('nothing to commit'))
    await waitFor(() => expect(screen.getByLabelText('Editing idea-00002-notes')).toBeTruthy(), SETTLED)
  })
})

/** The written discriminators of design-00002 §15, read on their own. */
describe('a material line', () => {
  const known = (id: string) => id === 'spec-00001-x'

  it('is an absolute path when it starts with one slash', () => {
    expect(readMaterials('', '/Users/me/notes.md', known).materials).toEqual({ paths: ['/Users/me/notes.md'] })
  })

  it('is a URL when it carries a scheme, wherever the scheme is', () => {
    expect(readMaterials('', 'https://example.com/a\nfile://x/y', known).materials).toEqual({
      urls: ['https://example.com/a', 'file://x/y'],
    })
  })

  it('is a document id when it is a type, five digits and a slug the board has', () => {
    expect(readMaterials('', 'spec-00001-x', known).materials).toEqual({ docIds: ['spec-00001-x'] })
  })

  it('is unusable when the board has no document of that id', () => {
    const read = readMaterials('', 'spec-00002-x', known)

    expect(read.materials).toBeUndefined()
    expect(read.unusable).toEqual([{ line: 'spec-00002-x', reason: 'no document with this id is on the board' }])
  })

  it('is unusable when it is none of the three', () => {
    for (const line of ['//comment', 'spec-1-x', 'SPEC-00001-X', 'just some words']) {
      expect(readMaterials('', line, known).unusable).toHaveLength(1)
    }
  })

  it('drops blank lines and the whitespace around each line', () => {
    const read = readMaterials('  ', '\n  spec-00001-x  \n\n', known)

    expect(read.materials).toEqual({ docIds: ['spec-00001-x'] })
    expect(read.unusable).toEqual([])
  })

  it('is nothing at all when nothing was given', () => {
    expect(readMaterials('', '', known).materials).toBeUndefined()
  })
})
