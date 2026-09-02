// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import type { DocGraph, DocNode } from '../../src/docRepository.ts'
import type { AnnotationListView, SubmitResult } from '../../src/annotations.ts'
import { EditorView } from 'codemirror'
import { Board } from '../src/Board.tsx'
import { ApiError, type AskThread, type SessionListing, api } from '../src/api.ts'
import { BLOCKED_TEXT, SUBMIT_REFUSAL } from '../src/annotationRows.ts'

vi.setConfig({ testTimeout: 30_000 })

const FRONT_MATTER = '---\nid: prd-00001-x\ntype: prd\nstatus: draft\n---\n'
const BODY = '\n## Context\n\nthe sentence that carries the anchor.\n'
const CONTENT = FRONT_MATTER + BODY
const ANCHOR_AT = CONTENT.indexOf('the anchor')

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

/** A second document, for the cases about what one document's state must not follow. */
const OTHER = node({ id: 'idea-00001-x', path: 'idea/a.md', type: 'idea', title: 'Idea' })
const OTHER_CONTENT = '---\nid: idea-00001-x\ntype: idea\nstatus: draft\n---\n\n## Elsewhere\n\nquite another sentence entirely.\n'
const GRAPH: DocGraph = { nodes: [node(), OTHER], edges: [], issues: [], idOwners: {}, diagnostics: [] }

function listing(overrides: Partial<SessionListing> = {}): SessionListing {
  return {
    id: 's9',
    kind: 'cowrite',
    agent: 'claude',
    sourceId: 'prd-00001-x',
    status: 'running',
    startedAt: '2026-09-01T09:00:00.000Z',
    ...overrides,
  }
}

function thread(overrides: Partial<AskThread> = {}): AskThread {
  return {
    id: 't-1',
    agent: 'claude',
    exchanges: [
      {
        question: 'is this still true?',
        askedAt: '2026-09-01T09:00:00.000Z',
        outcome: 'answered',
        answer: 'it is not',
        runSessionId: 's1',
      },
    ],
    ...overrides,
  }
}

function annotation(overrides: Partial<AnnotationListView['annotations'][number]> = {}) {
  return {
    id: 'n-1',
    type: 'question' as const,
    text: 'is this still true?',
    anchor: { selected: 'the anchor', before: '', after: '' },
    quote: 'the anchor',
    createdAt: '2026-09-01T09:00:00.000Z',
    state: 'pending' as const,
    locate: { start: ANCHOR_AT, end: ANCHOR_AT + 'the anchor'.length },
    ...overrides,
  }
}

function view(overrides: Partial<AnnotationListView> = {}): AnnotationListView {
  return {
    annotations: [annotation()],
    batches: [],
    submitPreview: {
      questions: 1,
      issues: 0,
      willTransitionTo: null,
      issueEligible: true,
      questionEligible: true,
    },
    ...overrides,
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

async function settle(links = 3) {
  for (let link = 0; link < links; link += 1) {
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
  }
}

async function push() {
  await act(async () => Socket.channel!.signal())
  await settle()
}

let served: SessionListing[] = []
let threads: AskThread[] = []
let annotations: AnnotationListView = view()

function serve(options: { sessions?: SessionListing[]; lists?: AskThread[]; view?: AnnotationListView } = {}) {
  served = options.sessions ?? []
  threads = options.lists ?? []
  annotations = options.view ?? view()
  vi.spyOn(api, 'graph').mockImplementation(async () => structuredClone(GRAPH))
  vi.spyOn(api, 'sessions').mockImplementation(async () => served)
  vi.spyOn(api, 'asks').mockImplementation(async () => structuredClone(threads))
  vi.spyOn(api, 'annotations').mockImplementation(async () => structuredClone(annotations))
  vi.spyOn(api, 'transitions').mockResolvedValue(['active'])
  vi.spyOn(api, 'nextSteps').mockResolvedValue([])
  vi.spyOn(api, 'items').mockResolvedValue({ items: [], diagnostics: [] })
  vi.spyOn(api, 'doc').mockImplementation(async (id: string) =>
    id === 'idea-00001-x'
      ? { path: 'idea/a.md', content: OTHER_CONTENT, hash: 'hash-2' }
      : { path: 'prd/a.md', content: CONTENT, hash: 'hash-1' },
  )
  vi.spyOn(api, 'config').mockResolvedValue({
    types: { prd: 'living', idea: 'living' },
    relations: ['parent'],
    flow: {},
    focus: {},
    agents: [
      {
        name: 'claude',
        command: 'claude',
        args: [],
        headless: {
          first: ['-p', '{question}'],
          resume: ['-p', '--resume', '{session}', '{question}'],
          capture: 'claude-json',
        },
      },
    ],
    entry: [],
    carries: {},
    maxSessions: 3,
    clarifiable: [],
    auditable: [],
  })
}

async function openBoard() {
  const rendered = render(<Board />)
  await waitFor(() => expect(screen.getByTestId('node-prd-00001-x')).toBeTruthy())
  await settle(1)
  return rendered
}

/** Open the document's editor; the tab is switched with the event Radix acts on. */
async function openEditor() {
  fireEvent.click(screen.getByTestId('node-prd-00001-x'))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Edit' })).toBeTruthy())
  await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
  await waitFor(() => expect(screen.getByTestId('editor-host').textContent).toContain('## Context'))
  await settle(1)
}

async function showTab(name: string) {
  fireEvent.mouseDown(await screen.findByRole('tab', { name }))
  await settle(1)
}

const rows = () => within(screen.getByRole('list', { name: 'Annotations' })).getAllByRole('listitem')

/** Select a word in the open buffer, the way finishing a drag over it does. */
function selectIn(at: number, docId: string): HTMLElement {
  const content = screen.getByLabelText(`Editing ${docId}`).querySelector<HTMLElement>('.cm-content')!
  EditorView.findFromDOM(content)!.dispatch({ selection: { anchor: at, head: at + 8 } })
  fireEvent.mouseUp(content)
  return content
}

beforeEach(() => {
  Socket.channel = undefined
  vi.stubGlobal('WebSocket', Socket)
  vi.spyOn(toast, 'error').mockImplementation(() => 'id')
  vi.spyOn(toast, 'message').mockImplementation(() => 'id')
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('the sixth read of the one refresh path', () => {
  /**
   * design-00002 §16.8 — the annotations are read while that document's
   * **editor** is open, in whichever view state, and not only while the list is:
   * the traces are drawn in the editing and preview states, and a list-only
   * condition would leave them stale exactly where they are visible.
   */
  it('reads the annotations while the editor is open, in the editing state', async () => {
    serve()
    await openBoard()
    expect(api.annotations).not.toHaveBeenCalled()

    await openEditor()

    expect(api.annotations).toHaveBeenCalledWith('prd-00001-x')
    const before = vi.mocked(api.annotations).mock.calls.length
    await push()
    expect(vi.mocked(api.annotations).mock.calls.length).toBeGreaterThan(before)
    // The trace is drawn in the editing state, where the reader is
    // (spec-00007-AC-9.13).
    await waitFor(() => expect(document.querySelector('.cm-content .annotation-mark')).toBeTruthy())
  })

  /**
   * A list that could not be read is a list nobody may be shown: the last
   * reading is let go of and the reason is said out loud, the same discipline the
   * ask list's own read keeps (design-00002 §10).
   */
  it('lets the reading go when the annotations cannot be read', async () => {
    serve()
    vi.mocked(api.annotations).mockRejectedValue(new Error('the annotation file will not parse'))
    await openBoard()
    await openEditor()

    expect(toast.error).toHaveBeenCalledWith('the annotation file will not parse')
    expect(screen.queryByRole('tab', { name: 'Annotations' })).toBeNull()
  })

  /**
   * design-00002 §16.8 — the threads are read while **either** list is open: a
   * question row's state is the last exchange of its thread, so stopping on the
   * annotation list without them would leave every question row stale.
   */
  it('reads the threads while the annotation list is open', async () => {
    serve({ lists: [thread()], view: view({ annotations: [annotation({ state: 'submitted', threadId: 't-1' })] }) })
    await openBoard()
    await openEditor()
    vi.mocked(api.asks).mockClear()

    await showTab('Annotations')

    expect(api.asks).toHaveBeenCalledWith('prd-00001-x')
    // And the row reads the thread's own outcome (spec-00007-AC-9.1).
    await waitFor(() => expect(rows()[0]!.textContent).toContain('answered'))
  })
})

describe('going where a row leads', () => {
  /**
   * spec-00007-AC-9.2 — a submitted question leads to the question list with its
   * thread located and open, whatever state the thread is in. The located
   * annotation is **not** cleared on the way: the two locate items do not disturb
   * each other (design-00002 §16.6).
   */
  it('opens the question list on the thread of a question row', async () => {
    serve({ lists: [thread()], view: view({ annotations: [annotation({ state: 'submitted', threadId: 't-1' })] }) })
    await openBoard()
    await openEditor()
    await showTab('Annotations')

    await userEvent.click(within(rows()[0]!).getAllByRole('button')[0]!)
    await settle(1)

    expect(await screen.findByLabelText('Follow-up question on t-1')).toBeTruthy()
    expect(screen.getByText('it is not')).toBeTruthy()
  })

  /**
   * design-00002 §16.6 — a thread that has left the payload is the close-nearest
   * case: the reason is said and the view stays where it was.
   */
  it('says so when the thread has gone and stays where it is', async () => {
    serve({ lists: [], view: view({ annotations: [annotation({ state: 'submitted', threadId: 't-9' })] }) })
    await openBoard()
    await openEditor()
    await showTab('Annotations')

    await userEvent.click(within(rows()[0]!).getAllByRole('button')[0]!)

    expect(toast.error).toHaveBeenCalledWith('no thread t-9 on this document')
    expect(screen.getByRole('list', { name: 'Annotations' })).toBeTruthy()
  })

  /**
   * spec-00007-AC-9.3 — a batch being cowritten leads to its session on the
   * terminal, and the editor keeps the annotation list it was on.
   */
  it('shows the cowrite session of a batch being cowritten', async () => {
    serve({
      sessions: [listing()],
      view: view({
        annotations: [annotation({ id: 'n-3', type: 'issue', state: 'submitted', batchId: 'b-1' })],
        batches: [{ id: 'b-1', status: 'cowriting', sessionId: 's9', annotationIds: ['n-3'], startedAt: 'now' }],
        submitPreview: { ...view().submitPreview, questions: 0 },
      }),
    })
    await openBoard()
    await openEditor()
    await showTab('Annotations')

    await userEvent.click(within(rows()[0]!).getAllByRole('button')[0]!)
    await settle(1)

    await waitFor(() => expect(screen.getByLabelText('Agent session').textContent).toContain('cowrite'))
    expect(screen.getByRole('list', { name: 'Annotations' })).toBeTruthy()
  })

  /**
   * spec-00007-AC-9.6, AC-9.12 — locating from the list lands on the body state
   * the reader was last on, and marks the passage there.
   */
  it('locates in the view state the reader was last on', async () => {
    serve()
    await openBoard()
    await openEditor()
    // Last on the preview, so that is where the locate lands.
    await showTab('Preview')
    await showTab('Annotations')

    await userEvent.click(screen.getByRole('button', { name: 'Locate n-1' }))
    await settle(1)

    const mark = await waitFor(() => {
      const found = screen.getByTestId('preview').querySelector('mark')
      expect(found?.className).toContain('annotation-mark--located')
      return found
    })
    expect(mark!.textContent).toBe('the anchor')

    // Held by id across a refresh, like every other presentation state
    // (design-00002 §16.8).
    await push()
    expect(screen.getByTestId('preview').querySelector('mark')?.className).toContain('annotation-mark--located')
  })

  /**
   * design-00002 §16.8 — close nearest: an annotation that has gone from the
   * store takes only the location with it, and the list state stays.
   */
  it('clears only the location when the annotation has gone', async () => {
    serve()
    await openBoard()
    await openEditor()
    await showTab('Annotations')
    await userEvent.click(screen.getByRole('button', { name: 'Locate n-1' }))
    await settle(1)
    await showTab('Annotations')

    annotations = view({ annotations: [], submitPreview: { ...view().submitPreview, questions: 0 } })
    await push()

    expect(screen.getByRole('button', { name: /Submit/ })).toBeTruthy()
    expect(screen.queryByRole('list', { name: 'Annotations' })).toBeNull()
  })
})

describe('the unified submit', () => {
  const submitted = (overrides: Partial<SubmitResult> = {}): SubmitResult => ({
    submitted: { questions: [], issues: null },
    blocked: [],
    transition: null,
    ...overrides,
  })

  async function confirmSubmit() {
    await userEvent.click(screen.getByRole('button', { name: /Submit/ }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: /Submit/ }))
    await settle(2)
  }

  /**
   * design-00002 §16.5 — a 4xx is a batch that did not happen at all: one toast
   * in the words of the refusal, and not a row of the list moves.
   * spec-00007-AC-10.6 takes this branch — the document has been renamed on disk
   * and the annotations are kept.
   */
  it('says a refused submit in one toast and leaves the list alone', async () => {
    serve()
    const submit = vi
      .spyOn(api, 'submitAnnotations')
      .mockRejectedValue(new ApiError(409, 'prd-00001-x is not a document in this repo', undefined, 'doc-missing'))
    await openBoard()
    await openEditor()
    await showTab('Annotations')

    await confirmSubmit()

    expect(submit).toHaveBeenCalledWith('prd-00001-x', { unsavedChanges: false, agents: {} })
    expect(toast.error).toHaveBeenCalledWith(SUBMIT_REFUSAL['doc-missing'])
    expect(rows()).toHaveLength(1)
    expect(rows()[0]!.textContent).toContain('unsubmitted')
  })

  /**
   * design-00002 §16.5 — a 200 is a batch that ran: one summary toast, and each
   * held-back annotation's reason left on its own row, because the toast goes
   * away and the annotation still has to be dealt with (spec-00007-AC-10.2's
   * «waitable» refusals).
   */
  it('summarises a partial submit and leaves each reason on its row', async () => {
    serve({
      view: view({
        annotations: [annotation({ id: 'n-1' }), annotation({ id: 'n-3', type: 'issue' })],
        submitPreview: { ...view().submitPreview, questions: 1, issues: 1 },
      }),
    })
    vi.spyOn(api, 'submitAnnotations').mockImplementation(async () => {
      // What the refreshed payload then says: one gone, one held back with its
      // reason written on it by the server.
      annotations = view({
        annotations: [
          annotation({ id: 'n-1', state: 'submitted', threadId: 't-1' }),
          annotation({ id: 'n-3', type: 'issue', blocked: 'cap-reached' }),
        ],
        submitPreview: { ...view().submitPreview, questions: 0, issues: 1 },
      })
      return submitted({
        submitted: { questions: [{ annotationId: 'n-1', threadId: 't-1', sessionId: 's1' }], issues: null },
        blocked: [{ annotationId: 'n-3', reason: 'cap-reached', message: 'the session limit is reached' }],
      })
    })
    await openBoard()
    await openEditor()
    await showTab('Annotations')

    await confirmSubmit()

    expect(toast.error).toHaveBeenCalledWith('submitted 1, held back 1')
    await waitFor(() => expect(screen.getByText(BLOCKED_TEXT['cap-reached'])).toBeTruthy())
  })

  /**
   * spec-00007-AC-8.6 — the editor keeps whatever it was showing: the one-off
   * Source view override of spec-00006-FR-4 does not apply to an annotation
   * submit. The other half of design-00002 §15 does apply, so the terminal
   * switches to the cowrite session that was started.
   */
  it('keeps the preview on show and switches the terminal to the session', async () => {
    serve({
      view: view({
        annotations: [annotation({ id: 'n-3', type: 'issue' })],
        submitPreview: { ...view().submitPreview, questions: 0, issues: 1, willTransitionTo: null },
      }),
    })
    vi.spyOn(api, 'submitAnnotations').mockImplementation(async () => {
      served = [listing({ id: 's9' })]
      annotations = view({
        annotations: [annotation({ id: 'n-3', type: 'issue', state: 'submitted', batchId: 'b-1' })],
        batches: [{ id: 'b-1', status: 'cowriting', sessionId: 's9', annotationIds: ['n-3'], startedAt: 'now' }],
        submitPreview: { ...view().submitPreview, questions: 0, issues: 0 },
      })
      return submitted({
        submitted: { questions: [], issues: { batchId: 'b-1', sessionId: 's9', annotationIds: ['n-3'] } },
      })
    })
    await openBoard()
    await openEditor()
    // The reader is on the preview when the submit goes.
    await showTab('Preview')
    await showTab('Annotations')

    await confirmSubmit()

    expect(toast.message).toHaveBeenCalledWith('submitted 1 annotations')
    await waitFor(() => expect(screen.getByLabelText('Agent session').textContent).toContain('cowrite'))
    // Still the annotation list, and the Source view was never forced on.
    expect(screen.getByRole('list', { name: 'Annotations' })).toBeTruthy()
    await showTab('Preview')
    expect(screen.getByTestId('preview')).toBeTruthy()
  })

  /**
   * A refusal whose word is not one of the seven the submit contract names keeps
   * the server's own sentence: the table is a fallback for those seven, and the
   * front end invents no eighth (design-00002 §16.5).
   */
  it('keeps the server’s sentence for a word the table does not name', async () => {
    serve()
    vi.spyOn(api, 'submitAnnotations').mockRejectedValue(
      new ApiError(409, 'prd-00001-x already has a session running', undefined, 'doc-busy'),
    )
    await openBoard()
    await openEditor()
    await showTab('Annotations')

    await confirmSubmit()

    expect(toast.error).toHaveBeenCalledWith('prd-00001-x already has a session running')
  })

  /**
   * A failure with no word on it — the network dropped the request — is said in
   * its own words: the table of reasons is a fallback for the seven the contract
   * names, never a replacement for what actually went wrong.
   */
  it('says a failure that carries no reason in its own words', async () => {
    serve()
    vi.spyOn(api, 'submitAnnotations').mockRejectedValue(new Error('Failed to fetch'))
    await openBoard()
    await openEditor()
    await showTab('Annotations')

    await confirmSubmit()

    expect(toast.error).toHaveBeenCalledWith('Failed to fetch')
  })

  /**
   * spec-00007-AC-7.5 — a transition whose commit failed is a notice, not a
   * refusal: the file is `draft` on disk and the session went ahead.
   */
  it('reports a transition whose commit failed as a notice', async () => {
    serve()
    vi.spyOn(api, 'submitAnnotations').mockResolvedValue(
      submitted({
        submitted: { questions: [{ annotationId: 'n-1', threadId: 't-1', sessionId: 's1' }], issues: null },
        transition: { to: 'draft', committed: false, error: 'the commit failed' },
      }),
    )
    await openBoard()
    await openEditor()
    await showTab('Annotations')

    await confirmSubmit()

    expect(toast.error).toHaveBeenCalledWith('the commit failed')
  })

  /**
   * spec-00007-AC-5.4 — the buffer holds unsaved edits, so the submit is refused
   * whole with the way out, and no request is made: the front end is this one
   * condition's only judge (design-00002 §16.5).
   */
  it('refuses the submit while the buffer is unsaved', async () => {
    serve()
    const submit = vi.spyOn(api, 'submitAnnotations').mockResolvedValue(submitted())
    await openBoard()
    await openEditor()
    await userEvent.click(screen.getByTestId('editor-host').querySelector('.cm-content')!)
    await userEvent.keyboard('X')
    await showTab('Annotations')

    await userEvent.click(screen.getByRole('button', { name: /Submit/ }))

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(submit).not.toHaveBeenCalled()
  })
})

describe('what one document’s editor leaves behind', () => {
  /**
   * design-00002 §16.4 — the re-anchor mode is a gesture under way on **one**
   * annotation of **one** document. Carried into the next document's editor it
   * takes the right-click away there and points the first selection made at an
   * annotation of the document that was closed, which the server answers with a
   * 404 nobody asked for.
   */
  it('leaves the re-anchor mode behind when another document opens', async () => {
    serve({
      sessions: [listing({ id: 's7', kind: 'ask', sourceId: 'idea-00001-x' })],
      lists: [thread({ id: 't-5', exchanges: [{ ...thread().exchanges[0]!, runSessionId: 's7' }] })],
      view: view({ annotations: [annotation({ locate: { failed: 'missing' }, orphan: 'missing' })] }),
    })
    await openBoard()
    await openEditor()
    await showTab('Annotations')
    await userEvent.click(screen.getByRole('button', { name: 'Re-anchor n-1' }))
    await settle(1)
    // The mode is on: a selection in this document's body offers itself.
    selectIn(CONTENT.indexOf('sentence'), 'prd-00001-x')
    await screen.findByRole('button', { name: 'Use this selection' })

    // The session panel's row of an ask on the **other** document.
    await userEvent.click(screen.getByRole('button', { name: 'Open the session panel' }))
    const panel = await screen.findByRole('list', { name: 'Agent sessions' })
    await userEvent.click(within(panel).getAllByRole('listitem')[0]!.querySelector('button')!)
    await settle(2)
    await waitFor(() => expect(screen.getByLabelText('Editing idea-00001-x')).toBeTruthy())
    await showTab('Source')

    // The gesture did not follow: no selection here is offered to the other
    // document's annotation, and this body takes the right-click for itself.
    const content = selectIn(OTHER_CONTENT.indexOf('another'), 'idea-00001-x')
    expect(screen.queryByRole('button', { name: 'Use this selection' })).toBeNull()
    expect(fireEvent.contextMenu(content)).toBe(false)
    expect(await screen.findByRole('menuitem', { name: 'Add a question annotation' })).toBeTruthy()
  })
})

describe('a question row following its thread', () => {
  /**
   * spec-00007-AC-9.11 — the resend is the ask list's own, and the annotation row
   * has no path of its own into it: the row reads failed off the thread's last
   * exchange, the owner resends from the question list, and the row is back to
   * running because the thread is. Nothing writes the state onto the annotation.
   */
  it('reads failed, and follows the thread back to running after a resend from the question list', async () => {
    const failed = thread({
      exchanges: [{ ...thread().exchanges[0]!, question: 'which gate?', outcome: 'failed', answer: undefined }],
    })
    serve({
      lists: [failed],
      view: view({ annotations: [annotation({ state: 'submitted', threadId: 't-1' })] }),
    })
    const resend = vi.spyOn(api, 'ask').mockImplementation(async () => {
      // What the server's own ask list then says: the same thread, one exchange on.
      threads = [thread({ exchanges: [...failed.exchanges, { ...failed.exchanges[0]!, outcome: 'running' }] })]
      return { sessionId: 's2', threadId: 't-1' }
    })
    await openBoard()
    await openEditor()
    await showTab('Annotations')
    expect(rows()[0]!.textContent).toContain('failed')

    // The resend goes through the question list, the way the existing thread
    // ability is reached (spec-00005-FR-7).
    await showTab('Questions')
    const listed = within(screen.getByRole('list', { name: 'Ask threads' })).getAllByRole('listitem')
    await userEvent.click(within(listed[0]!).getAllByRole('button')[0]!)
    await userEvent.click(screen.getByRole('button', { name: 'Resend the question of t-1' }))
    await settle(2)

    expect(resend).toHaveBeenCalledWith({
      docId: 'prd-00001-x',
      question: 'which gate?',
      threadId: 't-1',
      resend: true,
    })
    await showTab('Annotations')
    await waitFor(() => expect(rows()[0]!.textContent).toContain('running'))
    // And the annotation itself never moved: the row is a mirror.
    expect(annotations.annotations[0]).toMatchObject({ state: 'submitted', threadId: 't-1' })
  })
})

describe('annotating while a cowrite holds the buffer', () => {
  /**
   * design-00002 §16.2 末条 — the read-only of spec-00006-FR-4 is over the Source
   * view's editing and saving alone. Annotating writes no document and touches no
   * buffer, so the right-click is offered through the whole read-only period; the
   * submit is the thing that has to wait for a save, which is FR-5's own refusal.
   */
  it('offers the annotate menu while the session has the buffer locked', async () => {
    serve({ sessions: [listing()] })
    await openBoard()
    await openEditor()

    const content = screen.getByLabelText('Editing prd-00001-x').querySelector<HTMLElement>('.cm-content')!
    expect(content.getAttribute('contenteditable')).toBe('false')
    selectIn(CONTENT.indexOf('the anchor'), 'prd-00001-x')

    expect(fireEvent.contextMenu(content)).toBe(false)
    expect(await screen.findByRole('menuitem', { name: 'Add a question annotation' })).toBeTruthy()
    expect(screen.getByRole('menuitem', { name: 'Add an issue annotation' })).toBeTruthy()
  })
})

describe('changing an annotation from the list', () => {
  /** spec-00007-AC-3.1 — a change and a delete, each through the one refresh path. */
  it('edits and drops an annotation', async () => {
    serve({ view: view({ annotations: [annotation({ id: 'n-1' }), annotation({ id: 'n-2' })] }) })
    const change = vi
      .spyOn(api, 'changeAnnotation')
      .mockResolvedValue({ annotation: { ...annotation(), text: 'reworded' } as never })
    const remove = vi.spyOn(api, 'removeAnnotation').mockResolvedValue({ annotationId: 'n-2' })
    await openBoard()
    await openEditor()
    await showTab('Annotations')

    await userEvent.click(screen.getByRole('button', { name: 'Edit n-1' }))
    fireEvent.change(await screen.findByLabelText('Text of n-1'), { target: { value: 'reworded' } })
    // Scoped to the row: the editor header has a Save of its own.
    await userEvent.click(within(rows()[0]!).getByRole('button', { name: 'Save' }))
    await settle(1)
    expect(change).toHaveBeenCalledWith('prd-00001-x', 'n-1', { text: 'reworded', type: 'question' })

    await userEvent.click(screen.getByRole('button', { name: 'Delete n-2' }))
    await settle(1)
    expect(remove).toHaveBeenCalledWith('prd-00001-x', 'n-2')
  })

  /**
   * spec-00007-AC-3.4 — the way out of an orphan, whole: the re-anchor mode takes
   * the editor to the body state it was last on, the first completed selection
   * offers itself, and confirming replaces the anchor and comes back to the list.
   */
  it('re-anchors an orphan onto a new selection', async () => {
    serve({
      view: view({ annotations: [annotation({ locate: { failed: 'missing' }, orphan: 'missing' })] }),
    })
    const change = vi
      .spyOn(api, 'changeAnnotation')
      .mockResolvedValue({ annotation: annotation() as never })
    await openBoard()
    await openEditor()
    await showTab('Annotations')

    await userEvent.click(screen.getByRole('button', { name: 'Re-anchor n-1' }))
    await settle(1)
    // Back on the editing state, waiting for a selection.
    expect(screen.getByTestId('editor-host').hidden).toBe(false)

    const content = screen.getByTestId('editor-host').querySelector<HTMLElement>('.cm-content')!
    const { EditorView } = await import('codemirror')
    EditorView.findFromDOM(content)!.dispatch({
      selection: { anchor: CONTENT.indexOf('sentence'), head: CONTENT.indexOf('sentence') + 8 },
    })
    fireEvent.mouseUp(content)
    await userEvent.click(await screen.findByRole('button', { name: 'Use this selection' }))
    await settle(1)

    expect(change).toHaveBeenCalledWith('prd-00001-x', 'n-1', {
      anchor: expect.objectContaining({ selected: 'sentence' }),
    })
    // And the list is what the editor comes back to.
    expect(screen.getByRole('list', { name: 'Annotations' })).toBeTruthy()
  })

  /**
   * A refused re-anchor leaves the mode waiting: the annotation is still an
   * orphan and the selection is still the answer to it.
   */
  it('stays in the re-anchor mode when the replacement is refused', async () => {
    serve({ view: view({ annotations: [annotation({ locate: { failed: 'missing' }, orphan: 'missing' })] }) })
    vi.spyOn(api, 'changeAnnotation').mockRejectedValue(new ApiError(422, 'that anchor selects nothing'))
    await openBoard()
    await openEditor()
    await showTab('Annotations')
    await userEvent.click(screen.getByRole('button', { name: 'Re-anchor n-1' }))
    await settle(1)

    const content = screen.getByTestId('editor-host').querySelector<HTMLElement>('.cm-content')!
    const { EditorView } = await import('codemirror')
    EditorView.findFromDOM(content)!.dispatch({
      selection: { anchor: CONTENT.indexOf('sentence'), head: CONTENT.indexOf('sentence') + 8 },
    })
    fireEvent.mouseUp(content)
    await userEvent.click(await screen.findByRole('button', { name: 'Use this selection' }))
    await settle(1)

    expect(toast.error).toHaveBeenCalledWith('that anchor selects nothing')
    expect(screen.getByTestId('editor-host').hidden).toBe(false)
  })
})
